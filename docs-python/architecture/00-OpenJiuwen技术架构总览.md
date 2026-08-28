---
title: Agent Runtime 技术架构总览
description: Python runtime 的分层、边界和主调用链。
audience: both
---

# OpenJiuwen Python Agent 技术架构总览

## 子文档索引

| 文档 | 覆盖范围 |
|---|---|
| [01-生命周期与状态架构](02-agent-runtime-python技术架构.md) | 启动、就绪、在途流、排水、Task 与外置状态如何协作 |
| [02-协议与兼容性架构](02-agent-runtime-python技术架构.md) | A2A、REST、SSE、错误信封与共享键面的兼容边界 |
| [03-agent-core-python技术架构](01-agent-core-python技术架构.md) | agent-core 的包结构、Agent 形态、执行入口、Rail 与会话机制 |
| [04-协作与扩展体系](04-协作与扩展体系.md) | 扩展点矩阵、数据与事件流、宿主义务与部署装置 |
| [05-关键技术机制总结](05-关键技术机制总结.md) | 跨模块不变量：契约检查、终答语义、背压、错误投影、兼容分层 |

本页只讲总体定位、分层与主调用链；分层细节见上表。

## 一、总体定位与两层关系

OpenJiuwen Python Agent 由两层协同组成：`agent-core` 是 Agent 的核心 SDK，负责 Agent 语义、模型推理、工具、Rail、Workflow 和会话内执行；当前项目是嵌入宿主的 Agent 托管 runtime，负责统一执行契约、任务 / 会话控制、A2A 与自定义 REST 出口、异构框架适配、远端 Agent 通信、外置状态和生命周期编排。新业务 Agent 优先在 agent-core 层开发，再接入 runtime。宿主仍负责进程、鉴权、公开健康检查、业务路由和部署策略。

## 二、分层模型

```text
solution / agents —— 业务 Agent 与应用装配
        ↓
ext / adapters —— agent-core 扩展、异构框架、远端协议
        ↓
runtime —— Handler、REST/A2A、Task/Session、生命周期
        ↓
agent-core —— ReActAgent、WorkflowAgent、DeepAgent、Workflow、Tool、Rail
```

当前 runtime 内部的实现边界仍是：

```text
domain <- ports <- application <- adapters <- bootstrap/deploy
```

`bootstrap` 是组合根，不是业务层：它负责加载配置、发现扩展、做契约检查、绑定 lifespan 和暴露应用工厂。

## 三、核心调用链：一次 query 的旅程

1. 业务代码用 agent-core 构造 `AgentCard`、Agent、Tool/Rail/Workflow，并配置模型。
2. runtime 层把本地 Agent 注册到 agent-core `Runner`，用 `AgentCoreHandler` 包装为统一 Handler。
3. A2A / REST 入口解析请求并生成 `ServeRequest`。
4. Application 选择 Handler、记录 active stream，并按会话 / Task 绑定上下文。
5. AgentCore outbound adapter 调用 Runner，原生输出被归一为 `QueryChunk`。
6. Inbound 投影将 chunk 变成 A2A Event / Task 或 REST SSE envelope。
7. 状态观察者、总线投影和共享存储按同一终态推进规则落盘。

## 四、三条 Agent 主干与选型

| 场景 | 首选实现 | runtime 责任 |
|---|---|---|
| ReAct、Workflow、DeepAgent 等 OpenJiuwen Agent | agent-core | 通过 AgentCore adapter 托管和服务化 |
| 非 OpenJiuwen AgentScope 等框架 | 外部框架自身 | 通过对应 outbound adapter 实现 Handler |
| 远端 A2A/Versatile Agent | 远端协议/Agent | 通过 remote adapter 统一委托和结果投影 |
| runtime 契约、协议和生命周期测试 | 确定性 fixture Handler | 只验证 runtime，不宣称真实模型或 agent-core 贯通 |

## 五、关键边界

- **执行与协议分离**：Handler 不知道 SSE 或 A2A envelope；入口不理解 agent-core 私有输出。
- **终答与完成分离**：终答是内容事件，流正常结束才表达完成。
- **委托与远端输出分离**：`interrupt` 携带 delegation 表示需要编排层处理；`remote_agent_output` 表示远端成员已经产生业务输出。
- **内存运行态与外置事实分离**：active stream、等待 registry 是进程内运行态；Task、会话快照、键面和去重事实可外置。
- **装配与能力分离**：端口判定在 `ports/contract.py`，组合根只负责把不满足契约的对象在启动期拒绝。

## 六、限制与待补

部分组件已有实现但没有默认生产装配点；部分标准 A2A 回调 / 端点和多副本场景需要以具体 E2E 结果为准。不要仅凭类存在或单元测试绿灯判断能力已对外可用。

## See also

- [agent-core Python 技术架构](01-agent-core-python技术架构.md)
- [agent-runtime Python 技术架构](02-agent-runtime-python技术架构.md)
- [agent-solution 技术架构](03-agent-solution技术架构.md)
- [任务导向指南](../how-to/overview.md)
