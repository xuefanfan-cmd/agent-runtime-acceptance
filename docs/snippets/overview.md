---
title: 语义与装配增量片段（snippets）
description: Tool、Rail、SubAgent 与 runtime 配置等单文件增量，供 how-to 页面引用并并入已有 Agent 服务工程
audience: ai-coding
---

# 语义与装配增量片段（snippets）

本目录存放**单文件增量片段**：Tool/Rail/SubAgent 等语义增量、中间件、Sandbox 沙箱、SkillHub、Custom REST，以及 Runner 基础托管所需的
通用 YAML。Agent 类型自身的完整框架源码集放在 [../examples/](../examples/)，不在此重复。

| | examples/ | snippets/（本目录） |
| --- | --- | --- |
| 形态 | 完整源码用例（入口 + 语义定义 + runtime 装配 + 配套类 + YAML，不含 pom） | 单文件片段（一个类 / 一段 YAML） |
| 回答的问题 | 「这种 Agent 类型的框架源码如何完整接线？」 | 「在已有工程上增加这个能力，要加哪个文件/配置？」 |
| 使用方式 | 先生成常规构建工程，再整体复制对应源码集 | 复制后并入工程；调整 package，YAML 合并到 application.yml |

规则：

1. Java 片段是完整类（含 package/import），YAML 可整段并入；但片段不是完整工程，不含 main 类与 pom。
2. 仓内文件名使用 `<能力>-<工件>.<扩展名>` 便于检索。复制 Java 文件时，必须按下表的
   **目标文件名**落盘，使文件名与 `public class` 一致，并按目标工程调整 package。
3. 每个片段至少被一篇 how-to 或 api 页引用，引用方必须说明它是新增、替换还是合并。

## 片段索引

| 仓内片段 | 复制到工程时的目标文件名 | 内容 | 引用它的 how-to / api 页 |
| --- | --- | --- | --- |
| [assembly-application.yml](assembly-application.yml) | 合并到 `application.yml` | `agent-id` + `handler` + `a2a.skills` | [配置驱动 Agent](../how-to/config-driven-agent.md) |
| [middleware-checkpointer.yml](middleware-checkpointer.yml) | 合并到 `application.yml` | Redis checkpointer + 命名 Redis endpoint | [中间件配置](../how-to/middleware.md) |
| [sandbox.yml](sandbox.yml) | 合并到 `application.yml` | `external.sandbox.*` 沙箱端点配置段 | [Sandbox 沙箱](../how-to/sandbox.md) |
| [skillhub-agent-configuration.java](skillhub-agent-configuration.java) | `AgentConfiguration.java` | SkillHub：Agent Bean + ext handler + `sysOperationId` | [SkillHub 技能注入](../how-to/skillhub.md) |
| [skillhub-middleware.yml](skillhub-middleware.yml) | 合并到 `application.yml` | `middleware.skillhub.*` 配置段 | [SkillHub 技能注入](../how-to/skillhub.md) |
| [custom-rest-agent-configuration.java](custom-rest-agent-configuration.java) | `AgentConfiguration.java` | Custom REST 所需手动 handler Bean 变体 | [自定义 REST 入口](../how-to/custom-rest.md) |
| [custom-rest-protocol-adapter.java](custom-rest-protocol-adapter.java) | `CustomProtocolAdapter.java` | `CustomRestProtocolAdapter` 协议转换实现 | [自定义 REST 入口](../how-to/custom-rest.md) |
| [custom-rest.yml](custom-rest.yml) | 合并到 `application.yml` | `custom-rest.query-path` 配置段 | [自定义 REST 入口](../how-to/custom-rest.md) |
| [custom-rail.java](custom-rail.java) | `FinalAnswerGuardRail.java` | 自定义 Rail：终态答案护栏（afterModelCall + forceFinish 门） | [Rail 与工具中断](../how-to/rails.md) |
| [tool-interrupt-rail.java](tool-interrupt-rail.java) | `ConfirmToolExecutionRail.java` | BaseInterruptRail：指定工具执行前中断、恢复后批准/拒绝 | [Rail 与工具中断](../how-to/rails.md) |
| [ask-user-interrupt.java](ask-user-interrupt.java) | `AskUserInterruptSupport.java` | 内建 AskUserTool + AskUserRail：为 ReAct/BaseAgent 增加结构化追问；runtime 继续注册 Tool 执行体 | [Rail 与工具中断](../how-to/rails.md) |
| [deepagent-subagents.java](deepagent-subagents.java) | `DeepAgentSubagents.java` | DeepAgent 可选进程内 SubAgent 声明清单 | [DeepAgent 指南](../how-to/deepagent.md) |
