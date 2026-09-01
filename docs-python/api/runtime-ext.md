---
title: runtime 扩展与适配器接口文档
description: 出站适配器矩阵——agent-core、异构框架、远端与 Versatile、技能中心、状态与总线；含 Python 侧 ext 与 runtime 合仓的归属说明
audience: ai-coding
status: verified
---

# runtime 扩展与适配器接口文档

## 扩展与包归属对照

**扩展能力不另发包，全在 runtime 包内。** Versatile、异构框架适配、技能中心客户端、自定义 REST 通道都在 runtime 的 `adapters/` 下，装上 runtime 就都有；启用与否由**配置段**决定（例如技能中心的 `skill_hub.enabled`），不靠追加依赖。

**agent-core 用原生包。** 直接依赖 `openjiuwen`，没有 core 扩展包，也不需要。

runtime 本体位于 `agent-solution` 仓的 `common/agent-runtime-ext-python`——目录名里的 `ext` 是历史命名，实体就是 Python 的 runtime。

本页写的是这批适配器的公开面。

## 适配器矩阵与对应指南

| 适配器 | 模块 | 产出的端口 / 结果 | 对应指南 |
|---|---|---|---|
| agent-core | `adapters.outbound.agentcore` | `AgentHandler`（通用适配器）、客户端工具轨、技能安装 | [ReAct](../how-to/react-agent.md)、[Workflow](../how-to/workflow-agent.md)、[DeepAgent](../how-to/deepagent.md) |
| 异构框架 | `adapters.outbound.framework` | `AgentHandler` | [接入本地框架](../how-to/framework-adapter.md) |
| 存量宿主 Agent | `adapters.outbound.hostagent` | 字典事件到 `QueryChunk` | [部署与切换](../how-to/deployment.md) |
| Versatile | `adapters.outbound.versatile` | 远端 `AgentHandler`、HTTP、帧翻译 | [Versatile 对接](../how-to/versatile-agent.md) |
| 远端调用 | `adapters.outbound.remote` | 卡片、成员调用、批次、结果投影、委派占位体 | [Versatile 对接](../how-to/versatile-agent.md) |
| 状态与缓存 | `adapters.outbound.state_db` / `state_cached` / `cache_redis` / `session` | 状态外置与过期时间 | [中间件配置](../how-to/middleware.md) |
| 总线 | `adapters.outbound.bus`、`adapters.inbound.bus` | 准入、投影、消费 | [总线事件订阅](../how-to/bus-events.md) |
| 技能中心 | `adapters.outbound.skillhub` | 协调器、Provider、材料移交 | [SkillHub](../how-to/skillhub.md) |

框架私有对象必须在适配器内部消化：`application` 与 `domain` 只接收端口与领域类型。

## agent-core 适配器

`AgentCoreHandler(agent_id, runner)` 是**通用**适配器，不区分推理型、深度型、工作流型：执行期问运行资源「这个标识是不是工作流」，是就走工作流执行入口，否则走通用智能体入口。

构造参数是位置形式的「标识 + 执行器」。旧名 `ReActAgentHandler` / `DeepAgentHandler` / `WorkflowAgentHandler` 仍作为别名保留，指向同一个类。

配套件：`stream_adapter` 把原生输出帧转成 `QueryChunk`；`client_tool` 与 `client_tool_rail` 承载客户端工具的挂起与续接——后者区分「本轮不是续接」与「续接对不上本次调用」，前者该挂起、后者该拒绝。

## versatile 与远端适配速览

`adapters.outbound.remote` 负责目录、卡片解析、成员调用、可恢复流与结果投影；`application.remote_batch` 负责预算与深度准入、成员屏障。调用方不应直接依赖 HTTP 客户端的响应对象。

`adapters.outbound.versatile` 是非标准远端服务代理：`config` 保存端点与超时，`client` 承担 HTTP，`stream_adapter` 负责分帧，`handler` 对齐 `AgentHandler`。

远端委派载荷是 `interrupt`，远端业务输出是 `remote_agent_output`——两者不能都投影成用户追问。

## 技能注入速览

`ports.skill_hub` 定义三个协议：Provider（去哪儿取材）、Installer（材料交给谁）、可选的 `SkillTargetResolver`（哪个实例能接收技能）。`adapters.outbound.skillhub.factory.build_skill_hub_coordinator` 按配置装出协调器，未启用时返回空，调用方据此不套装饰层。

装配错误在启动期暴露并带配置项路径，不推迟到请求期。失败分类九个取值与上游枚举逐字一致。

## 回调的两个方向

回调**接收**端点由入站组件消费，回调**投递**由出站客户端发送——两件事不要混。能力位必须由真实类型契约判定，不能因为对象上存在同名属性就宣称支持。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.adapters.outbound.agentcore.handler.AgentCoreHandler`
- `agent_runtime.adapters.outbound.agentcore.stream_adapter` / `client_tool` / `client_tool_rail`
- `agent_runtime.adapters.outbound.remote.client` / `member_caller` / `delegation_rail`
- `agent_runtime.adapters.outbound.versatile`
- `agent_runtime.adapters.outbound.skillhub.factory.build_skill_hub_coordinator`
- `agent_runtime.ports.skill_hub`（`SkillHubConfig`、`SkillHubErrorCategory`）
- `agent_runtime.ports.remote` / `remote_batch` / `bus` / `callback`

## See also

- [Runtime 公开接口](agent-runtime-python.md)
- [接入本地 Agent 框架](../how-to/framework-adapter.md)
- [Versatile 对接](../how-to/versatile-agent.md)
- [SkillHub 技能注入](../how-to/skillhub.md)
