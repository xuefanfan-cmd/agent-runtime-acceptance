---
title: agent-core-java 技术架构
description: Agent 执行核心引擎的包结构、继承体系、图执行引擎、Runner/Session、记忆与上下文引擎、SPI、Rail 机制
audience: both
---

# agent-core-java：Agent 执行核心引擎

> 本文档为 [OpenJiuwen 三仓技术架构总览](00-OpenJiuwen技术架构总览.md) 的子文档，详细分析 `agent-core-java` 仓库的架构设计。

---

## 一、工程定位

`agent-core-java` 是一个单 jar 工程，为运行在 OpenJiuwen 框架上的智能体提供高性能运行时。它封装了 Agent 创建、工作流编排、大模型与工具调用等接口，并内置支持异步 IO 和流式处理的图执行引擎。它不引入 Spring 或 HTTP 框架，可以独立嵌入任何 Java 应用。

---

## 二、顶层包结构

源码按职责拆分为十四个顶层包，每个包承担一个明确的架构层：

| 顶层包 | 职责 |
|--------|------|
| `application` | 应用层 Agent 封装，提供 LLMAgent 和 WorkflowAgent 两种预置智能体 |
| `common` | 公共基础设施：异步工具、客户端连接池、常量、异常体系、日志、安全 |
| `context` / `context_engine` | 上下文引擎，管理消息窗口、压缩与卸载（`context_engine` 为重构版） |
| `controller` | 控制器框架，负责任务调度、事件队列、意图识别 |
| `foundation` | 基础设施层：LLM 模型抽象、工具体系、存储抽象、提示模板 |
| `graph` | 图执行引擎，基于 Pregel 模型的 BSP 图计算 |
| `memory` | 长期记忆引擎，管理变量、片段、摘要、图记忆 |
| `multiagent` / `multi_agent` | 多智能体团队支持（新旧并行关系） |
| `operator` | 算子层：LLM 调用、工具调用、记忆调用、技能调用算子 |
| `retrieval` | 检索增强（RAG）：知识库、向量存储、嵌入、重排序 |
| `runner` | 全局执行门面，统一执行入口、资源管理、回调框架 |
| `session` | 会话管理：状态、检查点、流式输出、追踪、交互 |
| `singleagent` | 单智能体实现：BaseAgent、ReActAgent、Rail 机制 |
| `workflow` | 工作流定义与执行：组件编排、连接边、执行状态 |

这些包之间存在新旧并行关系（如 `context` / `context_engine`、`multi_agent` / `multiagent`），重构版逐步替代旧版，旧版通过薄包装类保持向后兼容。

---

## 三、Agent 继承体系

> 📌 注意继承位置：`WorkflowAgent` 在 `ControllerAgent` 分支、**不是 `BaseAgent`
> 子类**——这正是远端 A2A 工具自动注入（`remote-agents`）只覆盖
> BaseAgent/ReActAgent 与 DeepAgent 内部 BaseAgent 的原因，详见
> [A2A 跨智能体调用机制](../how-to/a2a.md)。

Agent 类继承体系以 `BaseAgent` 为根，沿 ReAct、Controller 两条主线展开：

```
BaseAgent (abstract)
│
├── ReActAgent                          ReAct 范式: 思考→行动→观察循环
│   ├── ReActAgentEvolve               自演化训练变体
│   └── SupervisorAgent                层级消息总线模式的 Supervisor
│
├── ControllerAgent                     控制器驱动的 Agent
│   ├── LLMAgent                        LLM 应用 Agent
│   └── WorkflowAgent                  工作流应用 Agent,注册多条 Workflow
│
├── CommunicableAgent (abstract)        多智能体通信基类
│   └── ContainerAgent                  Handoff 模式容器 Agent
│
└── LegacyReActAgent                    旧版兼容层
```

ReActAgent 是主要的智能体范式，遵循 ReAct（Reasoning + Action）规划范式，以「思考→行动→观察」的循环迭代完成任务。ControllerAgent 是控制器驱动的 Agent，通过意图识别和任务调度编排工作流执行。体系中存在大量 `legacy/` 兼容类，通过继承新版类保持向后兼容。`SupervisorAgent` 同时继承 ReActAgent 和实现 CommunicableAgent 接口，兼具 ReAct 推理能力和团队通信能力。

---

## 四、图执行引擎

图执行引擎是 `agent-core-java` 的核心，采用 Pregel 模型的 BSP（Bulk Synchronous Parallel）图计算范式。整体执行流程为：图定义（注册节点与边）→ 图编译（生成可执行图）→ 执行引擎驱动超级步循环 → Checkpointer 状态保存与恢复。

```
用户代码
  → Workflow.invoke() / stream()
    → 图定义层: 注册节点、边、分支
      → 图编译: 生成可执行图 + Checkpointer 绑定
        → 执行引擎: 超级步循环
            → 从 Store 恢复状态或触发起始节点
            → 循环: 收集通道消息 → 确定活跃节点 → 并行执行 → 路由下一批 → 持久化
            → 捕获中断（人机交互）
          → Checkpointer 保存或清理
```

关键机制：

- **BSP 模型**：每个 super-step 中所有活跃节点并行执行，步末通过 Channel 同步，再进入下一步
- **Channel 通信**：节点间通过通道传递消息，包括屏障通道（屏障同步）和触发通道（触发式调度）两种类型
- **路由策略**：条件路由（根据运行时结果选择目标节点）、静态路由（固定路由）、屏障路由（屏障同步）
- **状态持久化**：图状态保存通道值、节点版本、步数、待处理缓冲，支持中断恢复
- **中断恢复**：节点可产生图中断，引擎捕获后保存状态，下次同 sessionId 恢复执行
- **流式执行**：基于 Actor 模型的流式图执行，支持节点级流式输出

---

## 五、Runner 与 Session 协作

Runner 是全局单例门面，统一管理 Workflow、Agent、AgentGroup 的执行入口。它持有三组核心组件：资源管理器（管理 Agent/Workflow/Tool/Model/Prompt 等资源的注册与查找）、本地消息队列（pubsub 机制）、异步回调框架（提供 filter/chain/metrics/circuit breaker 能力）。

**运行 Agent 时的协作流程**：

1. Runner 从输入中提取 `conversation_id` 作为 sessionId，创建或复用 AgentSession
2. Checkpointer 在执行前恢复已有 agent 状态，注入交互输入
3. Agent 执行（ReActAgent 通过 ContextEngine 管理上下文，调用 Model，执行 Tools）
4. Checkpointer 在执行后**保存** agent 状态（支持多轮对话延续，非清理）
5. `Runner.release(sessionId)` 显式清理该 session 的所有状态

**运行 Workflow 时**：正常完成后自动清理 checkpoint；异常或中断则保存，供下次恢复。

**Checkpointer 命名空间**：Agent 会话状态、Agent 团队状态、Workflow 执行状态、图执行 checkpoint 四个独立命名空间，通过 `sessionId:namespace:entityId:suffix` 格式的 Key 隔离。Agent 完成后不自动清理（持续会话），Workflow 正常完成后自动清理（图恢复用）。

---

## 六、记忆引擎与上下文引擎

**记忆引擎** 以单例为入口，需四类存储（KV、向量、数据库、嵌入模型）齐备。`scopeId` 是业务隔离边界，配置持久化到 KV。记忆类型包括变量记忆、片段记忆、摘要记忆和图记忆。还提供离线记忆整理（「梦境」机制）和团队共享记忆能力。

**上下文引擎**（重构版）管理 `sessionId + contextId` 级别上下文，核心概念是 ContextWindow——一次送入模型的快照。内置多个处理器（当前轮次压缩、对话压缩、轮次级压缩、消息卸载、消息摘要卸载），处理器链参与消息写入和窗口获取两个阶段，确保上下文在 Token 限制内最大化有效信息。

---

## 七、SPI 扩展点

`agent-core-java` 通过 Java SPI 机制提供七类扩展点，允许第三方在不修改框架源码的情况下接入自定义实现：

| SPI 接口 | 说明 |
|----------|------|
| Model 客户端工厂 | 大模型客户端工厂，已实现 OpenAI/OpenRouter/SiliconFlow/DashScope 等 |
| MCP 客户端提供者 | MCP 协议客户端，已实现 SSE/Stdio/OpenAPI/StreamableHttp/Playwright 等 |
| 远程客户端提供者 | 分布式远程客户端，已实现消息队列和 A2A 协议两种 |
| 检查点提供者 | Checkpointer 实现，已实现内存/持久化/Redis 三种 |
| KV 存储提供者 | KV 存储，已实现内存版 |
| 对象存储提供者 | 预留扩展点，暂无实现 |
| 向量存储提供者 | 向量存储，已实现内存/Chroma/Milvus/PGVector/Elasticsearch 五种 |

---

## 八、Rail 机制与中断

Rail（轨道）机制是 Agent 执行过程中的回调钩子链，定义在 `singleagent` 包中。它提供了模型调用后（`AFTER_MODEL_CALL`）、工具异常时（`ON_TOOL_EXCEPTION`）等钩子点，扩展模块通过注册自定义 Rail 在 ReActAgent 循环中插入验证、重规划、降级等逻辑。`forceFinish` gate 允许 Rail 短路 invoke 循环，是实现「验证→降级」控制流的基础。

中断机制用于人机交互，支持工具执行中途暂停、等待用户输入后恢复。中断状态、恢复上下文、中断请求共同构成了完整的暂停-恢复闭环。

Rail 机制和中断机制共同构成了 `agent-core-ext-java` 扩展 `agent-core-java` 的基础。
