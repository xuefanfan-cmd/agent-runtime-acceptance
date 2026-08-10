---
title: ReAct Agent 指南
description: ReActAgent 推理循环（LLM 自主决策工具调用）的创建、工具注册与托管——new+configure 官方路径、两步工具注册、会话上下文
audience: ai-coding
status: verified
examples:
  - examples/react
---

# ReAct Agent 指南

ReActAgent 基于 Reasoning-Acting 循环：每一轮迭代由 LLM 自主决定调用哪个工具、何时收尾，
适合**开放式任务**——你不知道会走几步、调什么工具。创建路径为 `new + configure`（无工厂类），
服务化用库存 `JiuwenCoreAgentHandler` 直接托管。

## 适用场景 / 不适用场景

| | |
| --- | --- |
| ✅ 适用 | 开放式问答/分析任务：步骤数不可预知，让 LLM 自由决策工具调用顺序 |
| ✅ 适用 | 单 Agent + 少量本地工具即可闭环的任务（最小接入成本） |
| ❌ 不适用 | 流程确定、步骤可枚举的任务——用 [WorkflowAgent](workflow-agent.md)（确定性控制流） |
| ❌ 不适用 | 需要持续维护工作区交付物、多轮任务循环的目标导向任务——用 [DeepAgent](deepagent.md) |

## 最小完整示例

完整代码在 **[examples/react/](../examples/react/)**（3 个 Java 文件 + 1 个 `application.yml`：
`ReactAgentApplication.java` / `ReactAgentConfiguration.java` / `TextStatsTool.java` /
`application.yml`），闭环能力：ReAct 推理循环 → 本地工具调用 → 托管 + A2A 暴露。核心接线摘录：

```java
@Bean
AgentHandler reactHandler(/* LLM 配置注入 */) {
    ReActAgentConfig config = ReActAgentConfig.builder()
            .promptTemplate(List.of(Map.of("role", "system", "content", "...")))
            .maxIterations(6)
            .build()
            .configureModelClient("OpenAI", apiKey, apiBase, modelName, true);
    ReActAgent agent = new ReActAgent(card);
    agent.configure(config);                        // 必需：绑定模型客户端

    agent.getAbilityManager().add(tool.getCard());  // 工具元数据 → LLM 可见
    Runner.resourceMgr().addTool(tool, List.of(card.getId()), true);  // 执行体 → 运行时可调
    return new JiuwenCoreAgentHandler(agent);       // 库存 handler 直接托管，不子类化
}
```

启动后获得：REST `POST /v1/query`、A2A skill `analyze_text`，全部由框架提供。

## 能力点逐个展开

### 创建：new + configure（无工厂）

`ReActAgent` 没有工厂类，`new ReActAgent(card)` + `agent.configure(config)` 是唯一官方路径：

- `ReActAgentConfig.builder()`：`promptTemplate(List<Map>)`（role/content 消息数组）、
  `maxIterations(int)` 等；`build()` 后链式 `configureModelClient(provider, apiKey, apiBase,
  modelName, verifySsl)` 绑定模型客户端。
- 采样参数：`config.getModelConfigObj().setTemperature(...)` / `setTopP(...)`（`ModelRequestConfig`）。
- **跳过 `configure()` 是运行时 NPE 的第一来源**——模型客户端在 configure 时绑定。

### 工具：两步注册，缺一不可

| 步骤 | API | 作用 |
| --- | --- | --- |
| 1 | `agent.getAbilityManager().add(tool.getCard())` | 工具元数据（名称/描述/参数 schema）写入 Agent 清单，影响 LLM 的 tool_choice |
| 2 | `Runner.resourceMgr().addTool(tool, List.of(agentId), true)` | 执行体（`LocalFunction`）绑定到 Agent，影响运行时调度；tag 为**集合** |

只做第 1 步：LLM 会选中该工具但运行时找不到执行函数，抛异常。工具本体 =
`ToolCard`（id/name/description/inputParams JSON Schema）+ `LocalFunction` 执行函数，
完整实现见示例 `TextStatsTool.java`。

### 会话上下文

同一 `conversation_id` 的多轮请求共享会话上下文——ReActAgent 能利用前文重新生成回答，
但它始终以**本次响应**为交付物（对比：DeepAgent 以工作区文件为持续交付物，
见 [deepagent.md](deepagent.md) 的对照表）。会话持久化（中断/恢复、跨进程）依赖
checkpointer 中间件，见 [middleware.md](middleware.md)。

## 配置项参考（application.yml，完整文件见示例目录）

- **spring.application.name**：本服务 A2A 卡片的 `name` 来源；调用方若通过 `remote-agents` 自动注入本 Agent，其 `name` 必须与该值相等。
- **openjiuwen.service.agent-id**：agent 路由标识。
- **openjiuwen.service.a2a.streaming**：A2A 侧流式开关。
- **openjiuwen.service.a2a.skills[]**：暴露给远端的 skill（`id` / `name` / `description` / `tags`）。
- **业务前缀（示例为 react.\*）**：LLM 的 api-key / api-base / model-name，走环境变量占位。

## 坑位与排错

> ⚠️ **只注册工具元数据**：`getAbilityManager().add(card)` 之后忘记
> `Runner.resourceMgr().addTool(...)`——LLM 选中工具后运行时抛「找不到执行函数」。
> 两步必须成对出现（建议集中在一处装配，见开发指导手册 §5 的 Enhancer 模式）。

> ⚠️ **maxIterations 撞顶**：默认迭代次数用尽时 Agent 直接返回中途结果。工具链较长时
> 显式调大 `maxIterations`，并在 system prompt 中约束工具调用次数。

> ⚠️ **A2A name 一致性**：A2A 卡片 `name` 取自 `spring.application.name`。调用方若通过
> `remote-agents` 自动注入本 Agent，其 `remote-agents[].name` 必须与该值相等（见 [a2a.md](a2a.md)）。

## 端到端校验

1. 启动示例（`ReactAgentApplication`），确认日志中 handler 注册成功。
2. 触发一次需要工具的请求：

```bash
curl -X POST http://localhost:18091/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"message": "统计这段文字：第一行\n第二行", "conversation_id": "c1", "stream": false}'
```

预期：`result.content` 给出结论，且数字与 `text_stats` 工具返回一致（chars/words/lines）。

3. 同一 `conversation_id` 发第二轮（如「再加上一行后重算」），确认上下文生效。
4. 工具调用循环建议写成单测（mock 模型响应断言工具被选中），避免靠人肉启动验证。

## API 锚点（jar 内类，按依赖可查）

- Agent（生成代码首选 facade）：`com.openjiuwen.core.singleagent.ReActAgent`；配置：`com.openjiuwen.core.singleagent.agents.ReActAgentConfig`；卡片：`com.openjiuwen.core.singleagent.schema.AgentCard`
- 模型：`ReActAgentConfig.configureModelClient(...)`、`com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig`
- 工具：`com.openjiuwen.core.foundation.tool.ToolCard` / `...tool.function.LocalFunction`、`com.openjiuwen.core.singleagent.AbilityManager`、`com.openjiuwen.core.runner.Runner.resourceMgr()`
- 托管：`com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler`
- 完整示例：[../examples/react/](../examples/react/)（本工程自有）

## See also

- [DeepAgent 指南](deepagent.md)：任务循环 + 工作区交付物的目标导向 Agent（含与 ReAct 的对照表）
- [开发指导手册 §1.1 / §3.2](../conventions/openjiuwen开发指导.md)：ReAct 创建与工具注册的正确/错误对照
- [agent-core-java 接口文档](../api/agent-core-java.md)：ReActAgent 与工具 API
- [examples/react/](../examples/react/)：本文引用的完整示例代码
