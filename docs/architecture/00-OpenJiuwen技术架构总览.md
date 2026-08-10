---
title: OpenJiuwen 三仓技术架构总览
description: agent-core-java / agent-runtime-java / agent-solution 三仓总体定位、层级关系与依赖方向；含核心调用链与关键边界
audience: both
---

# OpenJiuwen 三仓技术架构总览

> 本文档是 OpenJiuwen 三仓技术架构的汇总入口，涵盖 `agent-core-java`、`agent-runtime-java`、`agent-solution` 三个仓库的总体定位与层级关系。各仓库的详细架构分析见下方子文档。

---

## 子文档索引

| 子文档 | 内容 |
|--------|------|
| [01-agent-core-java技术架构](01-agent-core-java技术架构.md) | Agent 执行核心引擎的包结构、继承体系、图执行引擎、Runner/Session、记忆与上下文引擎、SPI 扩展点、Rail 机制 |
| [02-agent-runtime-java技术架构](02-agent-runtime-java技术架构.md) | 分布式运行时的模块依赖链、各模块职责、HTTP/A2A 调用链、Handler 选型、Demo 示例、与 core-java 集成 |
| [03-agent-solution技术架构](03-agent-solution技术架构.md) | 扩展方案的七个模块：core-ext、runtime-ext、agent-bus、agent-client、agent-evolve、agents、example |
| [04-三仓协作与扩展体系](04-三仓协作与扩展体系.md) | 扩展点矩阵、core-ext 与 core-java 集成、runtime-ext 与 runtime-java 集成、端到端调用链 |
| [05-关键技术机制总结](05-关键技术机制总结.md) | Pregel 图执行、Checkpointer 生命周期、Rail 钩子链、AgentHandler SPI、事件总线两跳转发、自演进闭环 |
| [版本兼容与上游锚点](../compatibility.md) | 三仓模块的 Maven 坐标与版本（生成 pom 的唯一来源）、Java/Spring Boot 基线 |

---

## 一、总体定位与三仓关系

OpenJiuwen 面向大模型应用的 Java 生态由三个分工明确的仓库构成。它们不是平行的功能重复，而是「核心引擎 → 分布式运行时 → 场景扩展」的分层递进关系，通过 Maven 坐标和 SPI 契约松耦合组装。

| 仓库 | 角色 | 推荐依赖坐标（代表） | Java 版本 | 构建形态 |
|------|------|----------------------|----------|---------|
| `agent-core-java` | Agent 执行核心引擎 | `com.openjiuwen:agent-core-java:0.1.14.post1` | 17 | 单 jar 工程 |
| `agent-runtime-java` | 分布式运行时 / Agent Server | `com.openjiuwen:agent-service-app:0.1.1.post1` | 17 | 多模块聚合 |
| `agent-solution` | 扩展方案与场景实现 | `com.openjiuwen:*:0.1.0` | 17/21 | 聚合 POM + 独立叶子模块 |

> 具体能力所需 artifact 及其传递依赖统一以
> [版本兼容与依赖坐标](../compatibility.md) 为准；公开页面不混用源码快照版本。

三仓的依赖方向是单向的：`agent-solution` 依赖 `agent-runtime-java` 和 `agent-core-java`;`agent-runtime-java` 依赖 `agent-core-java`;`agent-core-java` 不依赖其他两个仓。这保证了核心引擎的独立可复用性——开发者可以只用 `agent-core-java` 构建 Agent，不引入任何 HTTP 或 Spring 依赖。

扩展层各模块与底座的关系分两类： `agent-core-ext-java` 和 `agent-runtime-ext-java` 通过 Maven 依赖 + SPI/Rail 机制**扩展**底座代码；`agent-bus` 和 `agent-client` 通过 HTTP/SSE 或 A2A 协议**调用**运行时服务，无 Maven 依赖；`agent-evolve` 是 Python 项目，依赖 Python 版 agent-core，不属于 Java 生态。

```text
                          agent-solution (扩展层)
  ┌─────────────────┐  ┌──────────────────┐  ┌────────────┐  ┌──────────────┐
  │ agent-core-ext  │  │ agent-runtime    │  │ agent-bus  │  │ agent-client │
  │     -java       │  │     -ext-java    │  │(gateway/bus│  │  (端侧SDK)   │
  │ (认知能力扩展)  │  │ (协议/框架扩展)  │  │  /rdc)     │  │              │
  └───────┬─────────┘  └────────┬─────────┘  └─────┬──────┘  └──────┬───────┘
          │                     │                  │                │
     Maven依赖+Rail       Maven依赖+SPI       HTTP/SSE协议     A2A HTTP/SSE
          │                     │                  │                │
          │                     ▼                  ▼                ▼
          │           ┌──────────────────────────────────────────────────┐
          │           │          agent-runtime-java (运行时)              │
          │           │       spec ← adapters ← app ← demo               │
          │           └──────────────────────┬───────────────────────────┘
          │                                  │ depends on
          ▼                                  ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                  agent-core-java (执行核心引擎)                           │
  │  application · controller · foundation · graph · runner                  │
  │  · session · memory · context · singleagent · multiagent                │
  └──────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────┐
  │  agent-evolve    │  Python 项目,依赖 Python 版 agent-core,非 Java 生态
  │  (自演进引擎)    │
  └──────────────────┘
```

---

## 二、核心调用链：一次 query 的旅程

> 第二~四节为本仓在源码验证后补充的事实点（外部材料未展开），
> 供 AI coding 建立正确的运行时心智模型。

1. 应用代码构造 Agent。若采用配置选择路径，先用 `Runner.resourceMgr().addAgent(...)`
   按 ID 注册（返回 `Result`，重复 ID 注册失败不覆盖）。
2. runtime 自动配置根据 `openjiuwen.service.agent-id` 与 `handler=agentcore` 创建基础
   `JiuwenCoreAgentHandler(agentId, ...)`；若应用提供自定义 `AgentHandler` Bean，
   则自动配置让位。
3. `agent-service-app` 创建 `ServeOrchestrator`，它持有已经确定的 `AgentHandler`
   Bean，**不会**按 agent-id 在多个 handler Bean 之间动态路由。
4. 调用方 `POST /v1/query` 或发送 A2A 请求；controller 把请求交给
   `ServeOrchestrator`，再委托给该 handler（Controller 不绕过 Orchestrator 直连
   Runner）。
5. 基础 core handler 在执行时通过 Runner/ResourceMgr 按 ID 解析 Agent，或直接使用
   构造时传入的实例；versatile 等外部 adapter 则负责协议翻译。
6. 结果以 `QueryResponse` / `QueryChunk` 返回；人工输入需求以 `interrupt`（A2A 侧
   `INPUT_REQUIRED`）向最外层调用方传递。

## 三、三条 Agent 主干

| 形态 | 入口类 | 本地运行与远端边界 | 指南状态 |
| --- | --- | --- | --- |
| ReAct | `com.openjiuwen.core.singleagent.ReActAgent` | Runner 托管；可作为 ext 远端工具注入目标 | 由对应负责人补齐 |
| DeepAgent | deep_agent 体系 | Runner 托管；ext 安装器解析其内部 BaseAgent | 由对应负责人补齐 |
| Workflow | `com.openjiuwen.core.application.workflow.WorkflowAgent` | Runner 托管；可被 A2A 调用，但不能靠 ext 自动注入远端工具 | [Workflow 指南](../how-to/workflow-agent.md) |
| Workflow ↔ versatile | `agent-service-adapters-versatile` | 外部工作流通过 adapter 接入 runtime | [Versatile 指南](../how-to/versatile-agent.md) |

## 四、关键边界

- **配置驱动不等于 YAML 构造 Agent**：runtime YAML 选择已注册 Agent、装配基础
  handler、发布服务；Agent 实例仍由代码创建（详见
  [配置驱动 Agent](../how-to/config-driven-agent.md)）。
- **A2A 服务暴露与远端工具注入不是同一个能力边界**：各类 Agent 可作为被调用方
  发布 skill；`remote-agents → tool` 当前只覆盖 BaseAgent/ReActAgent 与 DeepAgent
  内部 BaseAgent（详见 [A2A 跨智能体调用机制](../how-to/a2a.md)）。
- **中间件核心在 runtime**：checkpointer、Redis endpoint 与 MemoryStore 属于
  agent-runtime-java；solution 只在需要时叠加 ext handler/SkillHub 等增量。
- **versatile 协议 ≠ A2A**：外部 HTTP+SSE 工作流协议应在 adapter 边界翻译，业务
  Agent 间仍使用标准 A2A 契约。

## See also

- [开发规范](../conventions/project-conventions.md)
- [配置驱动 Agent](../how-to/config-driven-agent.md)
- [A2A 跨智能体调用机制](../how-to/a2a.md)
- [agent-runtime-java 接口文档](../api/agent-runtime-java.md)
- [版本兼容与上游锚点](../compatibility.md)
