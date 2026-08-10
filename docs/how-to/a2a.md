---
title: A2A 跨智能体调用机制
description: 区分类型无关的服务暴露与有类型边界的 remote-agents 工具注入，说明 A2A skill、同步/流式调用及中断透传
audience: ai-coding
status: verified
---

# A2A 跨智能体调用机制

A2A（agent-to-agent）是 OpenJiuwen 中智能体跨进程互调的标准通道，但需要区分两件事：

- **被调用侧的服务托管与 A2A 暴露基本类型无关**：ReActAgent、DeepAgent、WorkflowAgent
  都可经 `AgentHandler` 托管并发布 `a2a.skills`。
- **主控侧的 `remote-agents → tool` 自动注入有类型边界**：当前只支持
  `BaseAgent`（包括 ReActAgent）和 `DeepAgent` 内部的 BaseAgent，不支持把远端工具
  自动注入 `WorkflowAgent`。

因此不存在「所有主控 × 被调组合只靠同一段 YAML 全部支持」的结论。先判断本 agent
是**被调用方**还是需要**主动消费远端能力的主控方**，再选择接线方式：

- **被调用侧**发布已构造/已托管的 Agent：通常只需 YAML（`a2a.skills` 等），零 Java 改动；
- **主控侧**消费远端能力：是否需要 Java 取决于 Agent 类型与 handler 路径——
  ReAct/DeepAgent 经 ext handler 自动注入（部署增量），WorkflowAgent 必须在 DAG 中
  显式建模（代码增量）。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | 把已托管的 Agent 作为 A2A 服务发布，供其他进程发现和调用 |
| ✅ 适用 | ReActAgent / BaseAgent 或 DeepAgent 通过 ext handler 自动获得远端 Agent 工具 |
| ✅ 适用 | A2A 链路中透传 `INPUT_REQUIRED`，由最外层调用方完成续传 |
| ❌ 不适用 | 让 WorkflowAgent 仅靠 `remote-agents` 自动获得远端工具；应在 workflow 中显式建模 Tool/组件 |
| ❌ 不适用 | 把 versatile 等宿主私有协议直接当作 A2A；应先经 adapter 或协议桥接 |

## 最小完整示例

### 被调用侧：发布 A2A skill

```yaml
spring:
  application:
    name: expense-review          # A2A 卡片 name 的唯一来源
openjiuwen:
  service:
    agent-id: expense-review
    a2a:
      streaming: true
      default-input-modes: ["text"]
      default-output-modes: ["text"]
      skills:
        - id: review_expense
          name: review_expense
          description: "一句话说清能做什么、何时调用、入参形态（LLM 据此决策）"
```

Agent 的具体构造由 ReAct / DeepAgent / Workflow 各自指南负责；A2A 层只要求最终存在
可工作的 `AgentHandler` Bean。基础 runtime 也可通过 `agent-id` 为已注册 Agent 自动创建
`JiuwenCoreAgentHandler`。

### 支持自动注入的主控侧：声明远端 Agent

```yaml
openjiuwen:
  service:
    a2a:
      remote-agents:
        - name: expense-review
          url: ${EXPENSE_REVIEW_CARD_URL:}
          # streaming: false      # 默认同步；需要消费对端流式帧时设 true
```

```java
// agent 必须是 BaseAgent（如 ReActAgent），或 DeepAgent；由类型专属指南负责构造。
AgentHandler handler = new JiuwenCoreAgentExtHandler(agent);
```

ext handler 只能通过实例构造并手动声明为 Bean；`handler: agentcore-ext` 不会触发自动装配。

## 能力点逐个展开

### 1. 被调用侧：托管与 skill 发布

- 本地 core Agent 可由 `JiuwenCoreAgentHandler` 托管；需要 solution 增量能力时可用
  `JiuwenCoreAgentExtHandler`。
- `spring.application.name` 是 A2A 卡片 `name` 的来源。
- `openjiuwen.service.a2a.skills[]` 发布能力清单；其中 `description` 是调用方 LLM 的选型依据。
- 作为**被调用方**时，WorkflowAgent 与 ReAct/DeepAgent 没有额外 A2A 配置差异。

### 2. 主控侧：remote-agents 自动工具注入

ext handler 在每次执行前读取 `remote-agents`，拉取远端 AgentCard，并尝试把 skill
安装为本地 Agent 工具。当前安装器只解析：

- `BaseAgent`，包括 ReActAgent；
- `DeepAgent` 内部承载执行的 BaseAgent。

对其他类型（包括 WorkflowAgent）安装器会跳过注入。WorkflowAgent 若要主动消费远端
Agent，应在 DAG 中显式增加负责远端调用的 Tool/组件，而不是依赖 `remote-agents` 自动注入。

### 3. 同步 / 流式：提供能力与调用方式分开配置

| 配置 | 角色 | 含义 | 默认值 |
| --- | --- | --- | --- |
| `openjiuwen.service.a2a.streaming` | 被调方 | AgentCard 上声明是否支持流式 | `true` |
| `openjiuwen.service.a2a.remote-agents[].streaming` | 主控方 | 调用该远端 Agent 时是否消费流式输出 | `false` |

被调方的卡片声明不会替主控选择调用方式。主控保持默认 `false` 时等待终态结果；设为
`true` 时消费对端过程帧。两种方式下，`INPUT_REQUIRED` 都作为中断状态向外传递。

### 4. 中断与续传

被调 Agent 执行中需要人工输入时，会产生 `interrupt` / `INPUT_REQUIRED`。在已支持的
远端工具调用链中，该状态会向主控和最外层调用方透传。最外层调用方负责保存
`(contextId, taskId)` 或协议要求的等价上下文，并在获得人工输入后回灌；中间 Agent
不应自行发明另一套续传协议。

### 5. 场景 → 需要对 agent 做什么

| 场景 | 要做的动作 | 不需要做的 |
| --- | --- | --- |
| 纯本地执行，无互调 | 基础 `JiuwenCoreAgentHandler` 托管（或 agent-id 自动装配） | 不配任何 a2a.* |
| 本 agent 要**被**远端调用（任意类型） | yaml 发布 `spring.application.name` + `a2a.skills` | 不改 Java、不实现服务端 |
| ReAct/BaseAgent 或 DeepAgent 主控要**调**远端 agent | 换 `JiuwenCoreAgentExtHandler` + yaml `remote-agents` | 不写 A2A 客户端代码、不改 agent 装配逻辑 |
| WorkflowAgent 主控要**调**远端 agent | 在 DAG 中显式建模负责远端调用的 Tool/组件 | 不依赖 `remote-agents` 自动注入（安装器会跳过） |
| 链路含人工中断（HITL） | 两侧 agent **零额外动作**：`interrupt` 帧沿调用链自动透传 | 不写续传代码 |
| 最外层调用方需要续传 | 由调用方/网关记录 `(contextId, taskId)` 并回灌（见下节） | agent 侧无感知 |
| 调用方不是 A2A 客户端 | 最外层加协议翻译层（无 LLM，宿主报文 ↔ A2A） | 不动任何 agent |
| 对端是 versatile 平台 | 先经 `agent-service-adapters-versatile` 包成普通 agent，再按普通被调处理 | versatile 报文不进 A2A 链路 |
| 链式组合（A 调 B、B 又调 C） | 每一跳独立按上面各行配置即可 | 无需全局拓扑配置 |

### 6. 组合时的外层职责（双角色配置之外的部分）

各 agent 按上表配置即可互调，真正超出双角色配置的只有最外层三件事：

1. **续传上下文记录在最外层**：链路含 HITL 时，`(contextId, taskId)` 的保存与
   续传回灌由**最外层调用方**承担（自定义前端、集成层或网关）；链路中所有 agent
   无感知，不写续传代码。
2. **调用方非 A2A 客户端时加协议翻译层**：当最外层调用方只会说自己的协议
   （如 versatile 风格报文、自定义 REST 信封），在最外层放一个**无 LLM 的纯翻译
   层**：最小入参重建完整信封、记录续传上下文（中断时吞状态帧、续传时回灌）、
   下游用 A2A（默认）或 REST `/v1/query` 调主控 agent。产品化实现见
   [自定义 REST 入口](custom-rest.md)。
3. **有状态后端的会话生命周期约束**：被调链路末端若是有状态外部工作流
   （如 versatile，`conversation_id` 全程复用、END 后解绑），续传报文必须匹配
   后端当前会话状态——这一约束属于对端平台，不属于 A2A 机制本身
   （详见 [versatile 对接指南](versatile-agent.md) 的坑位节）。

## 配置项参考

- **openjiuwen.service.a2a.skills[]**：被调用侧发布的 skill；填写 `id`、`name`、`description`，按需加 `tags`。
- **openjiuwen.service.a2a.streaming**：被调用侧的流式能力声明，默认 `true`。
- **openjiuwen.service.a2a.remote-agents[]**：支持自动注入的主控侧消费目标；需 ext handler。
- **remote-agents[].name**：必须等于远端 `spring.application.name`。
- **remote-agents[].url**：远端 AgentCard 地址。
- **remote-agents[].streaming**：该远端调用是否采用流式，默认 `false`。
- **remote-agents[].timeout-seconds**：该远端调用的超时时间，默认 `300`（秒）。

> **另一条进程内 A2A 路径**：base runtime（agent-service-app）还提供进程内客户端路径——远端卡
> 发现与 JSON-RPC 调用由服务编排层完成，但触发入口的委派工具与中断 Rail 需应用侧自行声明
> （典型形态：`delegate_to_xxx` 工具 + `BaseInterruptRail` 子类在中断上下文中携带
> `agentName`）。本篇的 `remote-agents` 自动注入路径由 agentcore-ext 完成工具安装，两者服务的
> 运行时形态不同，按需选用。

## 坑位与排错

> ⚠️ **把「能被 A2A 调用」误写成「能自动注入远端工具」**：前者基本类型无关；后者当前
> 只覆盖 BaseAgent/ReActAgent 与 DeepAgent 内部 BaseAgent。WorkflowAgent 主控必须显式建模远端调用。

> ⚠️ **ext handler 只传 agent-id 字符串**：不支持。它要求已构造的 Agent 实例，必须手动声明 Bean。

> ⚠️ **远端名称不一致**：`remote-agents[].name` 与对方 `spring.application.name` 不相等时，发现和路由会失败。

> ⚠️ **以为 `openjiuwen.service.a2a.agent-name` 能改卡片名**：该 key 不被绑定
> （`A2AProperties` 中无此字段），卡片 `name` 只取自 `spring.application.name`。

> ⚠️ **把私有协议直接串入 A2A 链路**：versatile 或宿主自定义 REST 先在边界翻译，Agent 间仍使用 A2A 契约。

## 端到端校验

1. 启动被调用侧，读取 AgentCard，确认 `name`、skills 和 streaming 声明符合配置。
2. 若主控使用自动工具注入，确认它是支持类型，并由 `JiuwenCoreAgentExtHandler` 实例路径托管。
3. 启动主控，确认远端卡片可拉取，且对应 skill 已出现在主控工具集中。
4. 发起同步调用，验证终态结果；再按需开启 `remote-agents[].streaming` 验证过程帧。
5. 构造一次 `INPUT_REQUIRED`，确认最外层收到中断上下文并可续传。
6. 若主控是 WorkflowAgent，不应期待步骤 3 自动出现工具；改为验证 DAG 中显式 Tool/组件的远端调用。

## API 锚点（jar 内类，按依赖可查）

- 基础 handler：`com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler`
- ext handler：`com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler`
- 服务帧：`com.openjiuwen.service.spec.dto.QueryChunk`

## See also

- [配置驱动 Agent](config-driven-agent.md)：Runner 注册、agent-id 选择与 ext 实例路径
- [中间件配置](middleware.md)：中断续传依赖的 checkpointer 持久化
- [runtime-ext 接口文档](../api/runtime-ext.md)：ext handler 与 artifact 归属
- [架构总览](../architecture/00-OpenJiuwen技术架构总览.md)：A2A 在三仓分层与调用链中的位置
