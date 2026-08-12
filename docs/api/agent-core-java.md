---
title: agent-core-java 接口文档
description: ReActAgent / WorkflowAgent / DeepAgent / Workflow 组件的用户接口（自包含参考）
audience: ai-coding
---

# agent-core-java 接口文档

agent-core-java 是框架的**核心 SDK**（`com.openjiuwen.core.*`，纯 Java、不依赖 Spring），
提供三类 agent 与 workflow 编排原语。Spring 托管由 runtime 层负责（见
[agent-runtime-java 文档](agent-runtime-java.md)）。

## 包结构速查

| 包 | 关键类 | 用途 |
| --- | --- | --- |
| `core.singleagent` | `ReActAgent`、`agents.ReActAgentConfig`、`schema.AgentCard` | ReAct 单 agent（reason+act 循环）；生成代码首选根包 `ReActAgent` facade |
| `core.application.workflow` | `WorkflowAgent`、`schema.WorkflowAgentConfig` | 多 workflow 容器 agent（单 workflow 模式无意图 LLM） |
| `core.workflow` | `Workflow`、`WorkflowCard`、`component.*` | 命令式 DAG：`Start`、`End`、`llm.LLMComponent`、`llm.QuestionerComponent`、`tool.ToolComponent`、`BranchComponent` |
| `core.foundation.llm.schema` | `ModelClientConfig`、`ModelRequestConfig`、`SystemMessage`、`UserMessage` | LLM 接入与消息 |
| `core.foundation.tool` | `Tool`、`ToolCard`、`function.LocalFunction` | 工具 SPI |

## ReActAgent 最小用法

```java
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;

AgentCard card = AgentCard.builder()
        .id("my-agent").name("my-agent")
        .description("一句话说明 agent 职责")
        .build();
ReActAgent agent = new ReActAgent(card);

ReActAgentConfig config = ReActAgentConfig.builder()
        .promptTemplate(List.of(Map.of("role", "system", "content", "你是……")))
        .maxIterations(12)
        .build()
        .configureModelClient("OpenAI", apiKey, apiBase, modelName, true);
ModelRequestConfig modelConfig = config.getModelConfigObj();
modelConfig.setTemperature(0.0);
modelConfig.setMaxTokens(1024);
agent.configure(config);
```

要点：

- `configureModelClient(clientProvider, apiKey, apiBase, modelName, sslVerify)`
  链式挂在 builder 产物上；工具经 `remote-agents` 配置注入（远端）或 rail/tool 注册
  （本地，见 [core-ext](core-ext.md)）。
- **provider 优先用 canonical 值 `OpenAI`**（OpenAI 及兼容端点，文档示例一律取它）。
  推荐发布件内置注册的 provider：`OpenAI` / `OpenRouter` / `SiliconFlow` / `DashScope` /
  `InferenceAffinity`（别名 `inference_affinity`）；匹配不区分大小写，但仍统一用
  canonical 拼写，不依赖大小写回退。**DeepSeek 等 OpenAI 兼容服务没有内置
  provider**——用 `"OpenAI"` + `apiBase`（如 `https://api.deepseek.com`）+ 对应
  modelName 接入；自定义协议经模型客户端注册机制显式扩展（锚点见下）。
- `ModelRequestConfig`（`getModelConfigObj()` 取得）字段全集：`modelName`、
  `temperature`（默认 0.95）、`topP`（默认 0.1）、`maxTokens`、`user`、`seed`、
  `stop`、`extraFields`（`Map<String, Object>`——厂商特定参数一律经它传递）。
- `ToolCard.inputParams` 为 `Map<String, Object>` 原样透传，框架不校验——Schema
  方言取决于目标 LLM：`type` / `properties` / `required` 三件套普遍可用，
  高级关键字（`enum` / `oneOf` / `$ref`）按模型侧支持情况使用。

## WorkflowAgent 最小用法

完整展开见 [WorkflowAgent 本地编排指南](../how-to/workflow-agent.md)，核心三行：

```java
WorkflowAgent agent = new WorkflowAgent(
        WorkflowAgentConfig.builder().id("pipeline").description("...").build());
agent.addWorkflows(List.of(workflow));            // 单 workflow 模式：请求直达 DAG
// 托管：new JiuwenCoreAgentHandler(agent) 注册为 Spring Bean
```

## Runner 与 ResourceMgr：注册与按 ID 解析

core 的运行时持有全局 `ResourceMgr`——「配置驱动装配」与「按 ID 取回 agent」
都建立在它之上：

```java
import com.openjiuwen.core.runner.Runner;

// 注册（应用启动早期完成，保证先于服务流量）
var registration = Runner.resourceMgr().addAgent(card, () -> agent, null);
if (registration.isError()) {
    throw new IllegalStateException("Agent registration failed", registration.getError());
}
```

- `addAgent(card, supplier, tag)`：按 `card.id` 注册并返回 `com.openjiuwen.core.runner.base.Result`；不使用标签时第三个参数传 `null`。同 ID 重复注册返回失败结果，不会覆盖。
- 按 ID 解析由 handler 在执行时完成（`getAgent(agentId)`，异步返回），
  业务代码通常只需注册、不需自己解析。
- 只被 handler 直接持有实例（手动 Bean 路径）的 agent **不需要**注册——
  注册是为「YAML `agent-id` → 实例」的解析服务的（见
  [配置驱动 Agent](../how-to/config-driven-agent.md)）。

## 技能（Skill）：本地目录注册

`BaseAgent` 提供技能注册入口，技能是含 `SKILL.md` 的本地目录：

```java
agent.registerSkill("/path/to/skill-dir");   // 目录内含 SKILL.md
```

- **前置条件**：agent 配置必须设置 `sysOperationId`
  （`ReActAgentConfig.builder().sysOperationId(...)`），否则注册不生效。
- 技能也可以运行期从 Skill Hub 下载注入——见
  [SkillHub 技能注入](../how-to/skillhub.md)（runtime-ext 中间件）。

## 自定义 Rail：AgentRail 钩子链

Rail 是挂在 Agent 执行链上的钩子类，承载护栏、观测、降级收尾等横切逻辑。继承
`com.openjiuwen.core.singleagent.rail.AgentRail` 抽象类并按需覆盖钩子；完整可复制类见
[snippets/custom-rail.java](../snippets/custom-rail.java)。

- **钩子分组**（入参均为 `AgentCallbackContext`，默认空实现，只覆盖需要的）：
  invoke 级 `beforeInvoke` / `afterInvoke`；模型级 `beforeModelCall` / `afterModelCall` /
  `onModelException`；工具级 `beforeToolCall` / `afterToolCall` / `onToolException`；生命周期
  `init` / `uninit`。DeepAgent 任务循环的 `afterTaskIteration(TaskIterationContext)` 属于独立的
  `com.openjiuwen.harness.rails.TaskIterationRail`，不是 `AgentRail` 钩子。
- **读输入**：`context.getInputs()` 按事件转型——模型事件为 `ModelCallInputs`；
  `afterModelCall` 中 `getResponse()` 是 `AssistantMessage`，`getToolCalls()` 为空即终态答案。
- **控制面**（`AgentCallbackContext`）：
  - `requestForceFinish(Map<String, Object>)`：在 `afterModelCall` 中请求短路循环，
    `invoke` 返回该 **Map**（消费方按 Map 处理，不是纯字符串）——`ReActAgent` 在
    模型回调后消费该请求，core 自带的 `SecurityRail` / `BudgetRail` 等同用此门；
  - `requestRetry(delaySeconds)`：请求一次延迟重试；
  - `pushSteering(message)`：向运行中的 Agent 推引导消息。
- **注册**：BaseAgent/ReActAgent 用 `agent.registerRail(rail)`；DeepAgent 在工厂创建前经
  `DeepAgentConfig.rails(List<Object>)` 声明，由 `HarnessFactory` 装配到内部执行 Agent。
- **适用边界**：`AgentRail` 是 BaseAgent/ReActAgent 的回调机制；DeepAgent 通过配置把 Rail
  交给内部执行 Agent；`WorkflowAgent` 属 ControllerAgent 体系，不走这条回调链。

## DeepAgent

DeepAgent（`com.openjiuwen.core` 的 deep_agent 体系）面向长任务拆解与多步执行，
本地运行 + 远端 A2A 工具的组合用法与 ReActAgent 同构（同一套 `remote-agents` 声明）。
完整装配、任务循环与工作区工具见 [DeepAgent 指南](../how-to/deepagent.md)。

## API 锚点（jar 内类，按依赖可查）

- ReAct：`com.openjiuwen.core.singleagent.ReActAgent`、`...singleagent.agents.ReActAgentConfig`、`...singleagent.schema.AgentCard`
- Workflow：`com.openjiuwen.core.application.workflow.WorkflowAgent`、`...application.schema.WorkflowAgentConfig`、`com.openjiuwen.core.workflow.*`
- 注册/解析：`com.openjiuwen.core.runner.Runner`（`resourceMgr()`）、`...runner.resourcemanager.ResourceMgr`
- 技能：`com.openjiuwen.core.singleagent.BaseAgent`（`registerSkill`）
- Rail SPI：`com.openjiuwen.core.singleagent.rail.AgentRail` / `AgentCallbackContext` / `ModelCallInputs`；BaseAgent 注册入口 `...singleagent.BaseAgent.registerRail`；DeepAgent 配置入口 `com.openjiuwen.harness.schema.config.DeepAgentConfig.rails`；任务迭代接口 `com.openjiuwen.harness.rails.TaskIterationRail`
- LLM/工具：`com.openjiuwen.core.foundation.llm.schema.*`、`com.openjiuwen.core.foundation.tool.*`
- 装配示例：[../how-to/workflow-agent.md](../how-to/workflow-agent.md) 与 [../examples/overview.md](../examples/overview.md)（本工程自有）

## See also

- [配置驱动 Agent](../how-to/config-driven-agent.md)：ResourceMgr 注册 + agent-id 装配的完整闭环
- [SkillHub 技能注入](../how-to/skillhub.md)：运行期技能下载注入（sysOperationId 前置条件）
- [core-ext（react-rails）](core-ext.md)：给 ReActAgent 补 verify/replan/self-heal 三条 rail
