---
title: 构建 WorkflowAgent：core DSL 编排 DAG 并托管（JiuwenCoreAgentHandler）
description: 用 agent-core-java 的 Workflow/WorkflowAgent 以代码编排 LLM+工具+分支+人工审批 DAG，经 JiuwenCoreAgentHandler 托管为 REST/A2A 服务
audience: ai-coding
status: verified
examples:
  - examples/workflow
---

# 构建 WorkflowAgent：core DSL 编排 DAG 并托管

`WorkflowAgent` 是 agent-core-java 的**命令式 DAG 编排 agent**：用代码显式声明节点
（LLM / 工具 / 分支 / 人工提问）与连线，框架负责执行、节点间数据传递、结构化输出解析、
人工中断/续传。装配完成后经 `JiuwenCoreAgentHandler` 托管为 Spring Bean，
即自动获得 REST `/v1/query` 与 A2A 端点。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | 流程确定、步骤可枚举：固定流水线、审批流、数据处理 DAG、需要结构化中间结果的多步任务 |
| ✅ 适用 | 需要**确定性控制流**（条件分支、必达路径、人工卡点可审计） |
| ❌ 不适用 | 下一步由 LLM 自主决策的开放任务——用 ReActAgent |
| ❌ 不适用 | 单步问答——直接 ReActAgent 或裸 LLM 调用即可，不必建图 |

## 最小完整示例

完整代码在 **[examples/workflow/](../examples/workflow/)**
（4 个文件：`PipelineApplication.java` / `PipelineConfiguration.java` / `CheckTool.java` /
`application.yml`），闭环能力：LLM 结构化输出 → 工具校验 → 分支 → 人工(HITL)/自动收尾 →
托管 + A2A 暴露。核心接线摘录：

```java
@Bean
AgentHandler pipelineHandler(/* LLM 配置注入 */) {
    WorkflowAgent agent = new WorkflowAgent(WorkflowAgentConfig.builder()
            .id("pipeline").description("...").build());
    agent.addWorkflows(List.of(buildWorkflow(...)));   // 单 workflow 模式：请求直达 DAG
    return new JiuwenCoreAgentHandler(agent);          // 库存 handler 直接托管，不子类化
}
```

DAG 骨架（`buildWorkflow` 内的装配顺序，完整代码见示例文件）：

```java
wf.setStartComp("start", new Start(), Map.of("query", "${query}"), null);
wf.addWorkflowComp("transform", new LLMComponent(llmCfg), Map.of("query", "${start.query}"), null);
wf.addWorkflowComp("check", new ToolComponent(new ToolComponentConfig()).bindTool(new CheckTool()),
        Map.of("total", "${transform.total}"), null);
// branch.addBranch("${check.data.risk} == \"high\"", "confirm", "high");
// branch.addBranch("true", "finish", "normal");
wf.addWorkflowComp("route", branch, Map.of("risk", "${check.data.risk}"), null);
wf.addWorkflowComp("confirm", new QuestionerComponent(qCfg), Map.of("summary", "${transform.summary}"), null);
wf.addWorkflowComp("finish", new LLMComponent(autoCfg), Map.of("summary", "${transform.summary}"), null);
wf.setEndComp("end", new End(),
        Map.of("manual_result", "${confirm.user_response}", "auto_result", "${finish.text}"), null);
wf.addConnection("start", "transform");
wf.addConnection("transform", "check");
wf.addConnection("check", "route");
wf.addConnection("confirm", "end");
wf.addConnection("finish", "end");
```

启动后获得：REST `POST /v1/query`、A2A skill `run_pipeline`、HITL 中断/续传，全部由框架提供。

## 能力点逐个展开

### 节点类型与装配 API

| 组件 | 装配方式 | 输出字段 | 说明 |
| --- | --- | --- | --- |
| `Start` | `wf.setStartComp(id, new Start(), mapping, null)` | 映射声明的键 | 每图一个；右值 `${query}` 引用顶层入参 |
| `End` | `wf.setEndComp(id, new End(), mapping, null)` | 即 workflow 输出 | 未走到的分支字段为 null |
| `LLMComponent` | `wf.addWorkflowComp(id, new LLMComponent(cfg), mapping, null)` | `setOutputConfig` 声明的键 | 见下「结构化输出」 |
| `ToolComponent` | `new ToolComponent(cfg).bindTool(tool)` | `data.<工具返回键>` | 见下「工具节点」 |
| `BranchComponent` | `branch.addBranch(条件, 目标id, 分支名)` | 无（仅路由） | 需 `"true"` 兜底；分支边免连线 |
| `QuestionerComponent` | `new QuestionerComponent(qCfg)` | `user_response` | HITL，见下「人工中断」 |

### 结构化输出（LLMComponent）

```java
llmCfg.setResponseFormat(new LinkedHashMap<>(Map.of("type", "json")));
llmCfg.setOutputConfig(new LinkedHashMap<>(Map.of(
        "total", Map.of("type", "number", "description", "合计"),
        "summary", Map.of("type", "string", "description", "摘要"))));
```

`setResponseFormat(json)` + `setOutputConfig(schema)` 组合 = 强制 JSON 输出 +
声明可被下游引用的字段。schema 是 JSON Schema 风格 map，支持嵌套
`properties` / `items`。纯文本输出用 `Map.of("type", "text")`。

### 工具节点（ToolComponent + LocalFunction）

工具用 `LocalFunction` 实现（完整代码见示例 `CheckTool.java`）：`ToolCard` 声明
id/描述/输入 JSON Schema，`execute(Map inputs)` 返回 `Map`：

```java
public final class CheckTool extends LocalFunction {
    public CheckTool() {
        super(ToolCard.builder().id("check").name("check")
                        .description("...").inputParams(/* JSON Schema map */).build(),
                CheckTool::execute);
    }
    static Map<String, Object> execute(Map<String, Object> inputs) { /* ... */ }
}
```

下游经 `${check.data.<返回键>}` 引用（`data` 包裹见「坑位」）。

### 人工中断与续传（HITL）

`QuestionerComponent` 执行时 workflow 挂起，runtime 把中断翻译为流式侧一帧
`type=interrupt` 的 `QueryChunk`（A2A 侧即 `INPUT_REQUIRED`）。调用方携带人工输入
续传后，节点输出 `user_response`，图继续执行。**workflow 作者不写任何续传代码**。
关键配置：`responseType("reply_directly")`（人工回复直接作为节点输出）、
`extractFieldsFromResponse(false)`（关闭字段抽取）。

### 单 workflow 模式 vs 多 workflow 模式

`addWorkflows` 传单个 workflow 为**单 workflow 模式**：请求直达该 DAG，不启用
意图 LLM——本地编排的推荐起点。传多个则进入多 workflow 模式，由意图路由选择。

## 配置项参考（application.yml，完整文件见示例目录）

- **spring.application.name**：本服务 A2A 卡片的 `name` 来源；调用方若通过 `remote-agents` 自动注入本 Agent，其 `name` 必须与该值相等。
- **openjiuwen.service.agent-id**：agent 路由标识。
- **openjiuwen.service.a2a.streaming**：A2A 侧流式开关。
- **openjiuwen.service.a2a.skills[]**：暴露给远端的 skill（`id` / `name` / `description` / `tags`）。
- **业务前缀（示例为 pipeline.\*）**：LLM 的 api-key / api-base / model-name，走环境变量占位。

### 两套模板机制（最重要的坑）

workflow 中存在两套占位符，混用是最高频错误：

| 机制 | 分隔符 | 解析者 | 写在哪 | 引用对象 |
| --- | --- | --- | --- | --- |
| 图引擎引用 | `${node.field}` | workflow 引擎 | 输入映射**右值**、分支条件 | 上游节点的输出字段 |
| PromptTemplate | `{{localKey}}` | 提示词模板 | LLM 节点的 prompt | **本组件局部输入键**（输入映射左值） |

```java
wf.addWorkflowComp("transform", new LLMComponent(cfg),
        Map.of("query", "${start.query}"), null);          // ✅ 右值 ${} 跨节点引用

cfg.setUserPromptTemplate(new UserMessage("处理：{{query}}"));        // ✅ 节点内局部键
cfg.setUserPromptTemplate(new UserMessage("处理：${query}"));         // ❌ 原样输出字面量
cfg.setUserPromptTemplate(new UserMessage("处理：{{start.query}}"));  // ❌ 局部没有该键
```

记忆法：**`${}` 跨节点、`{{}}` 节点内**。

## 坑位与排错

> ⚠️ **工具输出的 `data` 包裹**：非 RESTful 工具的返回被框架包在 `data` 键下
> （`ToolComponentOutput.RESTFUL_DATA`）。下游必须写 `${check.data.risk}`；
> 漏掉 `.data` 拿到的是空值且**不报错**，表现为下游 LLM 看到空输入、分支走向兜底。

> ⚠️ **A2A name 一致性**：A2A 卡片 `name` 取自 `spring.application.name`。调用方若通过
> `remote-agents` 自动注入本 WorkflowAgent，其 `remote-agents[].name` 必须与该值相等；
> WorkflowAgent 自身作为主控时仍需在 DAG 中显式建模远端调用。

> ⚠️ **分支不连线**：`addConnection` 只声明普通边；`BranchComponent` 到目标节点的边
> 由分支条件自路由，重复声明会导致结构混乱。

## 端到端校验

1. 启动示例（`PipelineApplication`），确认日志中 handler 注册成功。
2. 低风险路径（自动收尾）：

```bash
curl -X POST http://localhost:18090/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"message": "合计 500 元", "conversation_id": "c1", "stream": false}'
```

预期：响应 result 中 `auto_result` 有值、`manual_result` 为 null。

3. 高风险路径（人工中断）：message 改为「合计 5000 元」，预期流式帧含
   `type=interrupt`（A2A 侧 `INPUT_REQUIRED`）；续传 = **同一 `conversation_id`
   + 人工输入作为下一个请求的 `message`**（REST 侧无单独续传字段）：

   ```bash
   curl -X POST http://localhost:18090/v1/query \
     -H 'Content-Type: application/json' \
     -d '{"message": "approved", "conversation_id": "c1", "stream": false}'
   ```

   预期：`manual_result` 返回审批内容。
4. DAG 结构校验建议写成单测（节点数、连线、分支条件），避免靠人肉启动验证。

## API 锚点（jar 内类，按依赖可查）

- 编排：`com.openjiuwen.core.application.workflow.WorkflowAgent`、`com.openjiuwen.core.workflow.Workflow` / `WorkflowCard`
- 组件：`com.openjiuwen.core.workflow.component.Start` / `End` / `BranchComponent`、`...component.llm.LLMComponent` / `QuestionerComponent`、`...component.tool.ToolComponent`
- 工具：`com.openjiuwen.core.foundation.tool.function.LocalFunction`、`ToolCard`
- 托管：`com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler`
- 契约：`com.openjiuwen.service.spec.dto.QueryRequest` / `QueryChunk`、`com.openjiuwen.service.spec.paths.AgentServicePaths`
- 完整示例：[../examples/workflow/](../examples/workflow/)（本工程自有）

## See also

- [Versatile 对接指南](versatile-agent.md)：本 workflow 被远端 ReAct 编排的完整链路
- [agent-runtime-java 接口文档](../api/agent-runtime-java.md)：AgentHandler SPI 与 DTO 契约
- [examples/workflow/](../examples/workflow/)：本文引用的完整示例代码
