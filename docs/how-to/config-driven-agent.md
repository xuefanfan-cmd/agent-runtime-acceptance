---
title: 配置驱动 Agent：Runner 运行逻辑与 YAML 装配边界
description: agent-runtime-java 基于 Runner 的类型无关托管逻辑——代码构造并注册 Agent，配置选择已注册 Agent、装配基础 handler 并发布服务；solution 增量能力另有类型边界
audience: ai-coding
status: verified
snippets:
  - docs/snippets/assembly-application.yml
---

# 配置驱动 Agent：Runner 运行逻辑与 YAML 装配边界

agent-runtime-java 的基础托管建立在 core `Runner` 上。ReAct、DeepAgent、Workflow 的
**共同运行契约**是：Agent 由 Java 代码构造，按 ID 注册到 `Runner.resourceMgr()`，runtime
再根据 `openjiuwen.service.agent-id` 选择已注册实例并对外服务。

> ⚠️ 当前没有「YAML Agent Definition → 自动构造 Agent」的 DSL。YAML 负责服务装配、
> 选择和运行时参数，不负责创建 Agent、prompt、工具或 Workflow DAG。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | Agent 已由代码构造，希望端口、agent 选择、A2A 暴露等服务装配下沉到 YAML |
| ✅ 适用 | 同一套 Agent 代码部署到不同环境，仅切换模型、资源地址和被暴露 skill |
| ✅ 适用 | 需要理解 ReAct / DeepAgent / Workflow 共有的 Runner 注册与 runtime 托管逻辑 |
| ❌ 不适用 | 只写 YAML、零 Java 代码得到一个 Agent |
| ❌ 不适用 | 在本页寻找某种 Agent 的构造细节；应进入对应类型指南/完整用例 |
| ❌ 不适用 | 假设所有 Agent 都能通过 solution ext handler 自动注入远端工具；该能力有类型边界 |

## 最小装配契约

配置驱动层不维护独占 Agent 用例，避免用 ReAct 示例误导为唯一实现。完整工程形态由各
Agent 类型用例负责；本页只保留跨类型不变的两个契约。

**代码侧：构造完成后注册。** `agentCard` 与 `agentFactory` 的具体写法由 ReAct、DeepAgent、
Workflow 指南负责；注册动作相同：

```java
var registration = Runner.resourceMgr().addAgent(agentCard, agentFactory, null);
if (registration.isError()) {
    throw new IllegalStateException("Agent registration failed", registration.getError());
}
```

**配置侧：选择已注册 Agent 并发布服务。** 完整片段见
[snippets/assembly-application.yml](../snippets/assembly-application.yml)：

```yaml
openjiuwen:
  service:
    agent-id: assistant      # 必须等于已注册 AgentCard.id
    handler: agentcore       # 基础 handler 自动装配；默认值也是 agentcore
    a2a:
      skills:
        - id: ask_assistant
          name: ask_assistant
          description: "说明能力、调用时机和输入形态"
```

所需 runtime artifact 为 `agent-service-adapters-agentcore`；若叠加 solution 的 ext handler，
artifact 为 `agent-service-adapters-agentcore-ext`。版本统一见
[版本兼容与上游锚点](../compatibility.md)。

## 能力点逐个展开

### 1. Runner / ResourceMgr：类型无关的注册契约

```text
类型专属代码构造 Agent
        ↓
Runner.resourceMgr().addAgent(AgentCard, Supplier<?>, tag)
        ↓ card.id
runtime 的 JiuwenCoreAgentHandler(agentId, ...)
        ↓ 执行时 getAgent(agentId)
Agent 对外提供 REST / A2A 服务
```

- `addAgent` 按 `AgentCard.id` 注册 provider；返回 `Result`，调用方应检查失败。
- 同 ID 已存在时返回错误结果，不会覆盖；替换资源时应使用当前发布件提供的完整移除签名，并检查移除与注册结果。
- `getAgent(agentId)` 在 handler 执行路径中解析实例；未注册或 ID 不一致会导致解析失败。
- 直接把 Agent 实例交给手动创建的 handler 时，不依赖 `agent-id → ResourceMgr` 解析。

### 2. 基础 handler 自动装配

同时满足以下条件时，runtime 自动创建 `JiuwenCoreAgentHandler(agentId, ...)`：

1. `openjiuwen.service.agent-id` 非空；
2. `openjiuwen.service.handler` 为 `agentcore`（默认值）；
3. Spring 容器中没有自定义 `AgentHandler` Bean。

`ServeOrchestrator` 并不按 `agent-id` 从多个 handler Bean 中做动态路由；它持有 Spring
容器提供的 `AgentHandler`。`agent-id` 用于创建基础 handler，随后由 handler 通过
ResourceMgr 解析对应 Agent。

### 3. 手动 handler 实例路径

需要自定义 handler、registrar 或 solution 扩展时，由应用提供 `AgentHandler` Bean：

```java
AgentHandler handler = new JiuwenCoreAgentHandler(agent);
```

自定义 Bean 存在后，基础自动配置让位。`agent` 的具体 Java 类型和构造过程仍由类型专属指南负责。

### 4. solution 增量：ext handler 的边界

`JiuwenCoreAgentExtHandler` 只能接收已构造的 Agent 实例，因此必须手动声明 Bean：

```java
AgentHandler handler = new JiuwenCoreAgentExtHandler(agent);
```

它可承载 SkillHub，并对**支持类型**执行 `remote-agents → tool` 注入。当前远端工具自动注入
只支持 BaseAgent/ReActAgent 和 DeepAgent 内部 BaseAgent，不支持 WorkflowAgent 主控。
WorkflowAgent 仍可被 A2A 调用；若要主动调远端 Agent，应在 DAG 中显式建模 Tool/组件。
详见 [A2A 跨智能体调用机制](a2a.md)。

## 配置项参考

- **openjiuwen.service.agent-id**：要对外服务的 Agent 注册 ID；只选择已注册实例，不构造实例。
- **openjiuwen.service.handler**：基础自动装配选择器，默认 `agentcore`；其他值不会自动生成 ext handler。
- **openjiuwen.service.a2a.skills[]**：被调用侧发布的 skill。
- **openjiuwen.service.a2a.remote-agents[]**：solution ext handler 消费的远端 Agent；仅在支持类型上自动注入工具。
- **spring.application.name**：A2A AgentCard `name` 来源。

其他运行时配置见 [agent-runtime-java 接口文档](../api/agent-runtime-java.md)；checkpointer、
Redis 与长期记忆见 [中间件配置](middleware.md)。

## 坑位与排错

> ⚠️ **只写 YAML、未注册 Agent**：`agent-id` 不会创建 Agent。应用启动早期必须完成
> `addAgent`，并检查返回结果。

> ⚠️ **重复 ID 静默覆盖**：不会覆盖；第二次 `addAgent` 返回失败。替换前先 `removeAgent`。

> ⚠️ **以为 `handler: agentcore-ext` 能自动装配 ext handler**：不会。ext handler 要求实例，必须手动声明 Bean。

> ⚠️ **把 Runner 的类型无关托管等同于远端工具类型无关**：Runner 注册/选择可复用于多种
> Agent；solution 的 remote-agents 安装器有单独的支持类型边界。

## 端到端校验

1. 启动前记录 `addAgent` 返回结果，确认注册成功且 ID 与 YAML 一致。
2. 启动应用，确认仅存在预期的 `AgentHandler` Bean；基础路径应由自动配置创建，实例路径应由应用创建。
3. 调用 `POST /v1/query`，确认请求进入目标 Agent，并带回相同 `conversation_id`。
4. 读取 AgentCard，确认 `spring.application.name` 与 skills 已发布。
5. 若叠加 ext handler，分别验证 SkillHub 或支持类型上的远端工具；不要用 WorkflowAgent 自动注入作为预期。

## API 锚点（jar 内类，按依赖可查）

- 注册/解析：`com.openjiuwen.core.runner.Runner`、`com.openjiuwen.core.runner.resourcemanager.ResourceMgr`
- 卡片：`com.openjiuwen.core.singleagent.schema.AgentCard`
- 注册结果：`com.openjiuwen.core.runner.base.Result`（推荐发布件中 `addAgent(card, supplier, tag)` 的返回契约）
- 基础托管：`com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler`
- solution 扩展托管：`com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler`
- 配置片段：[../snippets/assembly-application.yml](../snippets/assembly-application.yml)

## See also

- [Agent 类型完整用例索引](../examples/overview.md)
- [A2A 跨智能体调用机制](a2a.md)
- [agent-runtime-java 接口文档](../api/agent-runtime-java.md)
- [runtime-ext 接口文档](../api/runtime-ext.md)
- [版本兼容与上游锚点](../compatibility.md)
