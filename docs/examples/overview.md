---
title: Agent 源码用例（examples）
description: 完整 Agent 源码用例——每个目录覆盖一种 agent 类型或适配器的 Application、Configuration、配套类与 application.yml；不重复携带 pom
audience: ai-coding
---

# Agent 源码用例（完整框架源码集）

本目录只存放**完整框架源码集**：每个目录是一种 agent 类型/适配器的能力闭环，
包含 Application 入口、Agent 装配、配套类与 application.yml，可作为新工程的源码起点。
它们不是独立 Maven 工程；“完整”指 Java/YAML 接线不省略，并不表示目录自身携带构建与打包配置。

对 AI coding 消费者，推荐流程是：先按 [compatibility.md](../compatibility.md) 创建常规
Java 17 / Spring Boot 工程并生成依赖，再整体复制对应目录的源码。这样既保留高密度框架知识，
又避免在每个用例中复制通用 pom 与版本号。规则：

1. **必须是真实且可编译的源码**：Java 含完整 package、import 与类声明，禁止「略」「// ...」式省略；维护时必须使用 compatibility 推荐发布件执行编译校验。
2. **类型/适配器专属**：一个目录演示一个类型闭环（WorkflowAgent、Versatile 对接、
   后续 react/、deepagent/ 同），命名中性化，不含业务逻辑。
3. **不放机制片段**：类型无关的装配片段与叠加能力增量在
   [../snippets/](../snippets/)——那里是单文件片段，不是完整工程。
4. **被引用而存在**：每个目录至少被一篇 how-to 引用；md 中只摘录关键接线片段，
   完整代码以本目录为唯一来源（避免 md 与代码双副本漂移）。
5. **依赖说明**：示例不重复维护 pom.xml——工程化（Spring Boot parent、
   Java 17 编译、fat jar 打包）属通用知识；不重复 pom 不等于放弃编译门禁。版本坐标唯一来源是
   [../compatibility.md](../compatibility.md) 的依赖坐标速查表；每个目录需要的 artifact
   见下方「目录 → artifact 映射」。

## 复制到标准工程的目录约定

示例目录为便于阅读而平铺文件；生成工程时按 package 与资源类型落盘，不要把 Java 文件放在
`src/main/resources`，也不要把 `application.yml` 放在 Java package 下：

```text
src/main/java/com/openjiuwen/examples/<type>/
  <Type>Application.java
  <Type>Configuration.java
  <SupportingClass>.java
src/main/resources/
  application.yml
```

可以替换 `com.openjiuwen.examples.<type>` 与类名，但必须同步修改所有 Java 文件的 `package`、
类型引用，以及 YAML 中互相约束的 `spring.application.name` / `openjiuwen.service.agent-id`。

> **可编译不等于环境就绪。** 编译门禁验证公开类型、方法签名和依赖闭包；真实启动还依赖
> LLM 凭据、网络、Redis/SkillHub/远端 Agent 等环境条件，应继续执行对应 how-to 的
> 「端到端校验」。

## 示例索引

| 目录 | 能力闭环 | 引用它的 how-to |
| --- | --- | --- |
| [workflow/](workflow/) | WorkflowAgent DAG：LLM 结构化输出 → 分支 → HITL/自动收尾 → 托管 + A2A 暴露 | [../how-to/workflow-agent.md](../how-to/workflow-agent.md) |
| [versatile/](versatile/) | VersatileAgentHandler：远端 versatile 工作流包成 Agent（含中断翻译配置） | [../how-to/versatile-agent.md](../how-to/versatile-agent.md) |
| [react/](react/) | ReActAgent：推理循环 + 本地工具两步注册 → 托管 + A2A 暴露 | [../how-to/react-agent.md](../how-to/react-agent.md) |
| [deepagent/](deepagent/) | DeepAgent：TaskCompletionRail 任务循环 + 受限工作区文件工具 → 托管 + A2A 暴露 | [../how-to/deepagent.md](../how-to/deepagent.md) |

> 各类型共享 [配置驱动 Agent](../how-to/config-driven-agent.md) 中的 Runner / runtime
> 托管契约；类型构造细节与 solution 增量能力仍以各自指南为准。

## 目录 → artifact 映射（版本一律从 compatibility.md 速查表读取）

| 目录 | 需要引入的 artifact | 说明 |
| --- | --- | --- |
| workflow/ | `agent-service-app` + `agent-service-adapters-agentcore` | adapter 传递引入 agent-core-java，含中间件自动配置 |
| react/ | `agent-service-app` + `agent-service-adapters-agentcore` | 同上 |
| deepagent/ | `agent-service-app` + `agent-service-adapters-agentcore` | 同上 |
| versatile/ | `agent-service-app` + `agent-service-adapters-versatile` | VersatileAgentHandler 所在 adapter |
| 叠加：远端 A2A 工具注入 | 追加 `agent-service-adapters-agentcore-ext` | 仅主控侧需要自动注入时（见 [../how-to/a2a.md](../how-to/a2a.md)） |
| 叠加：SkillHub 技能注入 | 追加 `agent-service-adapters-agentcore-ext`（+ `agent-service-spec-ext` 自定义 Provider 时） | 见 [../how-to/skillhub.md](../how-to/skillhub.md) |
| 叠加：自定义 REST 入口 | 追加 `agent-service-app-custom-rest` | 见 [../how-to/custom-rest.md](../how-to/custom-rest.md) |
