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

## 生成工程目录约定

对**新生成的应用工程**，以下是 AI Coding 的强制落盘约定。除非用户明确要求适配某个
既有目录结构，不得把 Agent 定义、Tool、Rail、Spring `@Configuration` 和 Application
平铺到同一个 Java package。框架本身不以 package 名限制编译，但本 SPEC 要求生成结果
显式保留 core 语义层与 runtime 程序级服务层的职责边界。

```text
src/main/java/<business-base-package-path>/agent/   # Agent 语义能力层（Core / Harness）：定义、Tool、Rail、Workflow DAG
src/main/java/<business-base-package-path>/runtime/ # runtime 程序级服务层：Application、@Configuration、Runner/Handler 托管
src/main/resources/                            # application.yml 及模型、A2A、中间件等资源配置
```

强制的是上述一级职责边界，而不是统一的二级目录树。生成代码时：

- 不要机械创建空的 `tool/`、`rail/`、`customrest/`；也不要让大量 Tool、Rail、DTO、配置类长期
  平铺在 `agent/` 或 `runtime/` 根 package。
- 同一业务场景中共同演进的 Tool、Rail、Agent 辅助类型，优先按业务场景纵向聚合，例如
  `agent/expense/`；跨场景复用或同类实现较多时，再按能力横向使用 `agent/tool/`、
  `agent/rail/` 等。最小示例只有少量类型时可保留在 `agent/` 根 package。Tool 所调用的领域
  服务、客户端等不属于 Agent 框架适配代码时，可继续遵循既有业务架构，不要求迁入 `agent/`。
- Custom REST 始终属于 `runtime/`：可按协议组织为 `runtime/protocol/rest/`，也可按业务组织为
  `runtime/<business-scenario>/rest/`。不得将 Controller、协议 DTO 或协议适配器放入 `agent/`。
- 新建 Java package 优先使用简短、全小写、无下划线的业务名称；接入既有工程时可遵循其
  `svcx_xxx` 等既有分区规范，但不能因此破坏 `agent/` 与 `runtime/` 的依赖方向。
- 创建 Agent 的通用语义入口优先命名为 `<AgentName>Definition`，只有真正承担参数化、可重复
  构造职责时才使用 `Factory`。语义层的 `Options` / `Spec` 保持纯 Java；Spring
  `@ConfigurationProperties` 放在 runtime 层并使用 `RuntimeProperties` 等可区分名称。

生成时遵守以下依赖方向：

- `agent/` 只承载 Agent 自身语义，默认不 import Spring 或 runtime 类型；Tool、Rail 和
  Workflow DAG 也归入该层。
- `runtime/` 可以依赖 `agent/`，负责实例构造、Bean 装配、Runner 注册、Handler 与协议暴露；
  `agent/` 不得反向依赖 `runtime/`。
- Application 放在 `runtime/` 时，应把组件扫描根包显式设为业务根包，例如
  `@SpringBootApplication(scanBasePackages = "com.acme.expense")`。
- `<business-base-package-path>` 是业务根包把 `.` 替换为 `/` 后的源码路径，不是要求
  照抄的字面量。业务 Java 根包也不一定等于 Maven `groupId`；例如
  `groupId=com.acme` 的报销应用可使用根包 `com.acme.expense`，对应目录为
  `com/acme/expense/agent` 与 `com/acme/expense/runtime`。
- `com.openjiuwen.examples.*` 只属于本 SPEC 的示例命名空间，生成业务代码时必须替换，
  不得作为用户工程的默认 package。

这里要求的是**单一 Maven 工程内的 package 分层**，不是自动拆成多个 Maven module。
只有语义层需要被多个服务独立复用，或发布周期、团队边界明确分离时，才升级为多 module。
代表性结构见 [ReAct 完整用例](../examples/react/)；复制与重命名规则见
[examples 目录约定](../examples/overview.md#复制到标准工程的目录约定)。

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
- 生成代码使用的 API、依赖坐标和版本必须与 [compatibility.md](../compatibility.md)
  推荐发布件保持一致；不要根据其他版本的示例或上游 README 推断方法签名。

## See also

- [架构总览](../architecture/00-OpenJiuwen技术架构总览.md)
- [WorkflowAgent 编排指南](../how-to/workflow-agent.md)
- [Versatile 对接指南](../how-to/versatile-agent.md)
