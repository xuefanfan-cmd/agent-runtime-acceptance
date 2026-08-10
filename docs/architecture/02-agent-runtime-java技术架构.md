---
title: agent-runtime-java 技术架构
description: 分布式运行时的模块依赖链、各模块职责、HTTP/A2A 调用链、Handler 选型、与 core-java 集成
audience: both
---

# agent-runtime-java：分布式运行时

> 本文档为 [OpenJiuwen 三仓技术架构总览](00-OpenJiuwen技术架构总览.md) 的子文档，详细分析 `agent-runtime-java` 仓库的架构设计。

---

## 一、工程定位

`agent-runtime-java` 对应架构图中的 Agent Distributed Runtime（Java），其 `service/` 模块对应 Agent Server——一个 Spring Boot HTTP 服务，提供进程内 A2A、适配器胶水，并通过 Maven 依赖接入 `agent-core-java` 的执行能力。它让开发者最快上线 HTTP Agent 服务，主要实现或选择 `AgentHandler` 即可。

---

## 二、模块结构与依赖链

仓库采用多模块 Maven 聚合，模块间依赖单向流动：

```
agent-runtime-java (root pom)
└── service/
    ├── agent-service-spec            ← 契约与 SPI 定义层（无内部依赖）
    │
    ├── agent-service-adapters/
    │   ├── agent-service-adapters-common    ← 引擎无关共享层（依赖 spec）
    │   └── agent-service-adapters-agentcore ← AgentCore 适配层（依赖 spec + common + agent-core-java）
    │
    ├── agent-service-app             ← Ingress + Orchestrator（依赖 spec + common + agentcore）
    │
    └── agent-service-demo            ← 示例与集成测试（依赖 app + 各可选中间件）
```

依赖方向： `spec ← adapters-common ← adapters-agentcore ← app ← demo`，保证契约层不依赖实现层，便于定制镜像。

---

## 三、各模块职责

**agent-service-spec** — 契约与 SPI 定义层，无内部依赖。定义 `AgentHandler` 接口（query/streamQuery）、对话输入输出 Schema、ServeRequest/ServeResponse 等核心契约。这是整个运行时的「宪法」层，所有适配器必须遵守此契约。

**agent-service-adapters-common** — 与引擎无关的共享层。包含中间件客户端（Redis 等）、凭证解密、外部调用 DFX（超时、重试、熔断）。不依赖 `agent-core-java`，只依赖 `spec` 契约，因此可以被任何适配器复用。

**agent-service-adapters-agentcore** — AgentCore 执行后端适配层。实现 `AgentHandler` SPI，负责将 Checkpointer 和中间件配置写入 Core 的 RunnerConfig，绑定 MCP、远端 A2A、Sandbox 等出站 SPI，委派实际执行给 Core 的 Runner。

**agent-service-app** — Ingress + Orchestrator 层，是运行时的「大门」。包含 HTTP Controller（处理查询、会话重置、健康探针）、ServeOrchestrator（编排器）、A2A 协议适配（Agent Card、JSON-RPC）、生命周期 Hook、Spring Boot 自动装配。

---

## 四、HTTP 调用链与 A2A 调用链

**HTTP 调用链**：
```
HTTP Controller → ServeOrchestrator → AgentHandler → Core Runner
```

**A2A 调用链**（启用时）：
```
A2A Client → Agent Card / JSON-RPC → A2A ProtocolAdapter → ServeOrchestrator → AgentHandler → Runner
```

Controller **禁止**绕过 Orchestrator 直连 Runner，这保证了编排层对所有请求的统一治理（租户上下文、状态管理、流式注册）。

---

## 五、Handler 选型

运行时支持两种 Handler 选型：默认的 AgentCore Handler（通过配置项指定 agent-id，委派执行给 Core Runner）和自定义 Handler（通过 `@Bean AgentHandler` 覆盖默认装配，支持代理、远端引擎等场景）。

---

## 六、Demo 示例矩阵

`agent-service-demo` 下有八个示例，展示运行时如何对接不同中间件与外部服务：

| 示例 | 演示能力 |
|------|---------|
| `memory` | 对接 JiuwenMem / Mem0 记忆服务 |
| `redis` | Redis Checkpointer + Redis Stream 消息队列 |
| `sandbox` | 沙箱出站安全隔离 |
| `security` | TLS 加密通信 |
| `mcp` | MCP 出站工具（含安全配置） |
| `a2a` | 进程内 A2A（四个 Agent 互调） |
| `query` | 基础查询 |
| `outbound-security` | 出站安全策略 |

---

## 七、与 agent-core-java 的集成

`agent-runtime-java` 在根 pom 的 `dependencyManagement` 中锁定 `agent-core-java` 的版本。集成发生在 `agent-service-adapters-agentcore` 模块：它将运行时的 ServeRequest 转换为 Core 的 Runner 调用，并将 Checkpointer、中间件、出站 SPI 统一注入 RunnerConfig。
