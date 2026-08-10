---
title: DeepAgent 指南
description: DeepAgent 目标导向任务循环的创建与装配——HarnessFactory 工厂、TaskCompletionRail 完成判定、工作区与受限文件工具、与 ReAct 的行为对照
audience: ai-coding
status: verified
examples:
  - docs/examples/deepagent
---

# DeepAgent 指南

DeepAgent 在 ReActAgent 基础上叠加 **TaskLoop 任务循环**、**Workspace 工作区**与 SubAgent 委派能力，
适合「目标导向」的多步骤任务——交付物不是本次响应，而是工作区中持续维护的文件。
创建走官方工厂 `HarnessFactory.createDeepAgent(...)`（不要直接 `new`），
服务化用库存 `JiuwenCoreAgentHandler` 直接托管。

## 适用场景 / 不适用场景

| | |
| --- | --- |
| ✅ 适用 | 目标导向任务：持续维护工作区交付物（文档/报告/代码），增量请求只更新受影响部分 |
| ✅ 适用 | 复杂多步骤任务：LLM 拆解任务、多轮工具调用，由完成判定 Rail 决定何时收尾 |
| ❌ 不适用 | 单次开放式问答——用 [ReActAgent](react-agent.md)（接入成本更低） |
| ❌ 不适用 | 流程确定、步骤可枚举的任务——用 [WorkflowAgent](workflow-agent.md)（确定性控制流） |

### 与 ReActAgent 的行为对照（同一请求两轮后的可观察差异）

| 对比项 | ReActAgent | DeepAgent |
| --- | --- | --- |
| 对准对象 | 当前请求 | 持续维护交付物的目标 |
| 本次交付 | HTTP 响应中的完整结果 | 工作区中的文件 + 响应中的完成说明 |
| 收到增量修改 | 结合会话上下文重新生成完整回答 | 读取已有文件，只更新受影响的事实并复查 |
| 可观察位置 | `/v1/query` 的 `result.content` | HTTP 简短结果 + 工作区目录 |

注：区别不在「有无上下文」——同一 `conversation_id` 下 ReActAgent 也能利用会话上下文；
区别在于交付物形态（本次响应 vs 工作区文件）。

## 最小完整示例

完整代码在 **[examples/deepagent/](../examples/deepagent/)**（3 个 Java 文件 + 1 个 `application.yml`：
`DeepAgentApplication.java` / `DeepAgentConfiguration.java` / `WorkspaceFileTools.java` /
`application.yml`），闭环能力：任务循环 → 受限文件工具维护交付物 → 完成判定 → 托管 + A2A 暴露。
核心接线摘录：

```java
@Bean(destroyMethod = "shutdown")
DeepAgent notesDeepAgent(/* LLM 与工作区配置注入 */) {
    TaskCompletionRail completionRail = new TaskCompletionRail(
            "持续维护工作区交付物……；当前请求如下：\n{query}",   // {query} 会被当前请求替换
            "ARTIFACTS_READY", 1, false, 3, Duration.ofSeconds(300), List.of());
    DeepAgentConfig config = DeepAgentConfig.builder()
            .systemPrompt("...").maxIterations(8)
            .enableTaskLoop(true).completionTimeout(300.0)
            .workspacePath(root.toString()).language("cn").restrictToWorkDir(true)
            .tools(WorkspaceFileTools.create(root))     // 受限文件工具（读/写/列）
            .rails(List.of(completionRail))
            .model(Map.of("model", modelName, "temperature", 0.1, "top_p", 0.8))
            .backend(Map.of("provider", "openai", "api_key", apiKey,
                    "api_base", apiBase, "verify_ssl", true, "timeout", 120L))
            .build();
    Workspace workspace = Workspace.builder()
            .rootPath(root.toString()).language("cn").build();
    DeepAgent agent = HarnessFactory.createDeepAgent(card, config, workspace);
    agent.ensureInitialized();
    return agent;
}

@Bean
AgentHandler deepHandler(DeepAgent notesDeepAgent) {
    return new JiuwenCoreAgentHandler(notesDeepAgent);
}
```

启动后获得：REST `POST /v1/query`、A2A skill `maintain_artifacts`，全部由框架提供。

## 能力点逐个展开

### 创建：HarnessFactory 工厂（不要直接 new）

`HarnessFactory.createDeepAgent(card, config, workspace)` 一次完成内部装配：Workspace 解析与初始化、
默认 Rail 注入（`SecurityRail`；开启 TaskLoop/TaskPlanning 时追加 `TaskCompletionRail`/
`TaskPlanningRail`）、`addGeneralPurposeAgent(true)` 时注入通用子代理、工具实例注册。
直接 `new DeepAgent(...)` 会跳过这些步骤，功能残缺。创建后调用 `agent.ensureInitialized()`；
DeepAgent 持有工作区资源，Bean 声明 `destroyMethod = "shutdown"` 随容器释放。

### 任务循环与完成判定（TaskCompletionRail）

TaskLoop 让 Agent 围绕「任务是否完成」多轮迭代而非一轮即返。`TaskCompletionRail` 是完成判定器：

- **指令模板**：第一个参数为任务指令，`{query}` 占位符会被当前请求文本替换；
- **完成信号**：第二个参数为 completion promise——LLM 确认交付物就绪后发出该信号，循环才收尾；
- **护栏**：`maxRounds`（最大任务轮次）与 `Duration timeout` 防止无限循环；
  推荐发布件提供 0/6/7 参构造，全参末位为自定义 `StopConditionEvaluator` 列表，可传 `List.of()`。

### 工作区与受限文件工具

- `workspacePath(...)` + `Workspace.builder().rootPath(path).language(language).build()` 声明工作区根目录；
  `restrictToWorkDir(true)` 约束框架自带文件操作不越界。
- **业务文件工具建议自行收口**：示例 `WorkspaceFileTools` 只放行工作区根目录下的 `.md` 文件，
  对标准化路径做 `startsWith(root)` 边界检查——与 `restrictToWorkDir` 构成「可写范围」双保险。
  工具本体仍是 `ToolCard` + `LocalFunction`，经 `DeepAgentConfig.tools(...)` 传入
  （区别于 ReActAgent 的两步注册，DeepAgent 的工具由工厂装配时注册）。

### 模型配置：model / backend 两个 Map

`model(...)` 传请求侧参数（`model`/`temperature`/`top_p`），`backend(...)` 传客户端连接参数
（`provider`/`api_key`/`api_base`/`verify_ssl`/`timeout` 秒）。键名同时兼容 camelCase
（`topP`/`apiBase`/`verifySsl`）。

## 配置项参考（application.yml，完整文件见示例目录）

- **spring.application.name**：本服务 A2A 卡片的 `name` 来源；调用方若通过 `remote-agents` 自动注入本 Agent，其 `name` 必须与该值相等。
- **openjiuwen.service.agent-id**：agent 路由标识。
- **openjiuwen.service.a2a.streaming**：A2A 侧流式开关。
- **openjiuwen.service.a2a.skills[]**：暴露给远端的 skill（`id` / `name` / `description` / `tags`）。
- **业务前缀（示例为 deep.\*）**：LLM 的 api-key / api-base / model-name 与 workspace-path，走环境变量占位。

## 坑位与排错

> ⚠️ **完成信号永远不发 → 撞 maxRounds/超时**：promise 字符串要在 system prompt 中明确告知 LLM
> 「何时、以何种形式发出」，否则任务循环以护栏收尾、表现为响应只说明中途状态。

> ⚠️ **工作区路径写相对目录**：相对路径随启动目录漂移。示例统一 `toAbsolutePath().normalize()`
> 后再传给 config 与工具；多实例部署时每个实例用独立工作区目录。

> ⚠️ **直接 new DeepAgent**：跳过工厂装配（Workspace 初始化、默认 Rail、工具注册），
> 表现为文件工具不可用、安全 Rail 缺失。始终走 `HarnessFactory.createDeepAgent(...)`。

> ⚠️ **A2A name 一致性**：A2A 卡片 `name` 取自 `spring.application.name`。调用方若通过
> `remote-agents` 自动注入本 Agent，其 `remote-agents[].name` 必须与该值相等（见 [a2a.md](a2a.md)）。

## 端到端校验

1. 启动示例（`DeepAgentApplication`），确认日志中 handler 注册成功。
2. 第一轮（创建交付物）：

```bash
curl -X POST http://localhost:18092/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"message": "整理本周进展：完成登录；支付偶发超时待排查", "conversation_id": "c1", "stream": false}'
```

预期：`result.content` 简述创建了哪些文件；工作区目录（默认 `./data/deep-workspace/`）出现 `.md` 交付物。

3. 第二轮（同一 `conversation_id` 发增量修改，如「支付排查截止时间改为本周五」）：
   预期交付物**局部更新**且未变化内容保留（可用 `read_file` 工具语义对照文件内容）。
4. 完成判定建议写成单测（断言 promise 发出前循环不退出、maxRounds 兜底生效），
   避免靠人肉启动验证。

## API 锚点（jar 内类，按依赖可查）

- Agent：`com.openjiuwen.harness.deep_agent.DeepAgent`、`com.openjiuwen.harness.factory.HarnessFactory`、`com.openjiuwen.harness.schema.config.DeepAgentConfig`、`com.openjiuwen.harness.workspace.Workspace`
- 任务循环：`com.openjiuwen.harness.rails.TaskCompletionRail`（`{query}` 占位、promise、maxRounds/Duration timeout）
- 工具：`com.openjiuwen.core.foundation.tool.ToolCard` / `...tool.function.LocalFunction`（经 `DeepAgentConfig.tools(...)` 装配）
- 托管：`com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler`
- 完整示例：[../examples/deepagent/](../examples/deepagent/)（本工程自有）

## See also

- [ReAct Agent 指南](react-agent.md)：单轮推理循环 Agent（含与 DeepAgent 的行为对照表）
- [开发指导手册 §1.2 / §8](../conventions/openjiuwen开发指导.md)：DeepAgent 创建与 SubAgent 体系的正确/错误对照
- [agent-core-java 接口文档](../api/agent-core-java.md)：DeepAgent 与 Harness API
- [examples/deepagent/](../examples/deepagent/)：本文引用的完整示例代码
