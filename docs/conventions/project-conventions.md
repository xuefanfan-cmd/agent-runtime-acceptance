---
title: OpenJiuwen 专用开发规范
description: OpenJiuwen 怎么用、怎么设计——分层约束、依赖方向与编码红线
audience: both
---

# OpenJiuwen 专用开发规范

> 状态：骨架。本文先固化「红线级」规范（违反即错），细则逐步补充。

## 分层与依赖方向

OpenJiuwen Java 侧分四层，依赖只能**自上而下**：

```
solution 层   agents/、example/（可运行产品、演示、业务装配）
   ↓
ext 层        agent-core-ext-java（react-rails）、agent-runtime-ext-java（versatile 等 adapter）
   ↓
runtime 层    agent-runtime-java（agent-service-spec / app / adapters，Spring Boot 托管）
   ↓
core 层       agent-core-java（ReActAgent / WorkflowAgent / Workflow 组件，纯 SDK，无 Spring）
```

- **core 不依赖 Spring**：core 层类（`com.openjiuwen.core.*`）在纯 Java 环境可用；
  Spring 装配只允许出现在 runtime 层及以上。
- **库层不装业务**：`plan-agent` / `expense-review` 这类模块只允许依赖
  `agent-core-java` 与 `agent-service-app`，ReAct 循环、Workflow DAG、远端 A2A 工具注入、
  中断/续传全部使用框架能力，不自行重造。
- **不侵入 core-java**：对接外部系统（如 versatile）时，在 ext/solution 层写 adapter
  （如 `agent-service-adapters-versatile` 把外部 HTTP/SSE 工作流包成 A2A agent），
  禁止给 core 打补丁。

## 托管与装配规范

- agent 通过 **AgentHandler SPI**（`com.openjiuwen.service.spec.spi.AgentHandler`）接入 runtime：
  - 本地 core agent → 用 runtime 的 `agent-service-adapters-agentcore` 中 `JiuwenCoreAgentHandler` 包装；需要 solution 增量时，用 `agent-service-adapters-agentcore-ext` 中 `JiuwenCoreAgentExtHandler`，**不子类化**。
  - 外部协议 agent → 实现/复用 adapter handler（如 `VersatileAgentHandler`）。
- 最终必须存在一个 `AgentHandler` Bean：基础路径可由 runtime 根据 `agent-id` 自动创建，
  自定义或 ext 路径由应用手动声明 Bean。`agent-service-app` 消费该 Bean 并暴露 HTTP/A2A 端点；
  agent 标识由 `openjiuwen.service.agent-id` 与 `spring.application.name` 共同决定，
  A2A 卡片的 `name` 取自后者——**远端引用时必须保持一致**。

## 配置规范

- 配置前缀分域：`openjiuwen.service.*`（runtime 框架）、`openjiuwen.service.versatile.*`（adapter）、
  业务前缀自定（如 `expense-review.*`）。
- 密钥、地址一律走环境变量占位（`${LLM_API_KEY}`），不提交明文。
- 给配置写注释时，优先说明「这个值会影响什么框架行为」，而不是复述字段名。

## 文档与示例规范

- 示例演示**框架能力**而非业务：一个示例一个能力点，命名中性化。
- 所有 API 引用必须可 grep 到源码；文档改动涉及行为描述时同步更新 `source-anchors`。

## See also

- [架构总览](../architecture/00-OpenJiuwen技术架构总览.md)
- [WorkflowAgent 编排指南](../how-to/workflow-agent.md)
- [Versatile 对接指南](../how-to/versatile-agent.md)
