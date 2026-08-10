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
| `core.singleagent` | `ReActAgent`、`agents.ReActAgentConfig`、`schema.AgentCard` | ReAct 单 agent（reason+act 循环） |
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
        .configureModelClient("openai", apiKey, apiBase, modelName, true);
ModelRequestConfig modelConfig = config.getModelConfigObj();
modelConfig.setTemperature(0.0);
modelConfig.setMaxTokens(1024);
agent.configure(config);
```

要点：`configureModelClient(clientProvider, apiKey, apiBase, modelName, sslVerify)`
链式挂在 builder 产物上；工具经 `remote-agents` 配置注入（远端）或 rail/tool 注册
（本地，见 [core-ext](core-ext.md)）。

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

## DeepAgent

DeepAgent（`com.openjiuwen.core` 的 deep_agent 体系）面向长任务拆解与多步执行，
本地运行 + 远端 A2A 工具的组合用法与 ReActAgent 同构（同一套 `remote-agents` 声明）。
完整装配、任务循环与工作区工具见 [DeepAgent 指南](../how-to/deepagent.md)。

## API 锚点（jar 内类，按依赖可查）

- ReAct：`com.openjiuwen.core.singleagent.ReActAgent`、`...singleagent.agents.ReActAgentConfig`、`...singleagent.schema.AgentCard`
- Workflow：`com.openjiuwen.core.application.workflow.WorkflowAgent`、`...application.schema.WorkflowAgentConfig`、`com.openjiuwen.core.workflow.*`
- 注册/解析：`com.openjiuwen.core.runner.Runner`（`resourceMgr()`）、`...runner.resourcemanager.ResourceMgr`
- 技能：`com.openjiuwen.core.singleagent.BaseAgent`（`registerSkill`）
- LLM/工具：`com.openjiuwen.core.foundation.llm.schema.*`、`com.openjiuwen.core.foundation.tool.*`
- 装配示例：[../how-to/workflow-agent.md](../how-to/workflow-agent.md) 与 [../examples/overview.md](../examples/overview.md)（本工程自有）

## See also

- [配置驱动 Agent](../how-to/config-driven-agent.md)：ResourceMgr 注册 + agent-id 装配的完整闭环
- [SkillHub 技能注入](../how-to/skillhub.md)：运行期技能下载注入（sysOperationId 前置条件）
- [core-ext（react-rails）](core-ext.md)：给 ReActAgent 补 verify/replan/self-heal 三条 rail
