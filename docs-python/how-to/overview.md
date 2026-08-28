---
title: 任务导向指南（how-to）
description: 按 Agent 类型与跨类型能力分入口的 Python 开发指南——先选型，再进对应指南
audience: ai-coding
---

# 任务导向指南

按 **Agent 类型**分入口：先选型（我要写哪种 Agent），再进入对应指南。整体架构介绍只有一处：[Agent Runtime Python 技术架构总览](../architecture/00-OpenJiuwen技术架构总览.md)。

每篇指南固定 8 节（适用场景 / 不适用场景 → 最小完整示例或最小装配契约 → 能力点逐个展开 → 配置项参考 → 坑位与排错 → 端到端校验 → API 锚点 → See also）：完整源码在 [`examples/`](../examples/overview.md)，可叠加片段在 [`snippets/`](../snippets/overview.md)，正文只摘录关键接线。

> `status: verified` 表示公开接口、配置边界与文档接线已按 `openjiuwen==0.1.16` 与当前 runtime 源码核验，且有源码工程的装配门禁通过。它**不等同于**已在真实模型、Redis、技能中心或远端 Agent 环境完成端到端联调——运行验证按各页「端到端校验」执行。

## 按 Agent 类型

| 入口 | 指南 | 源码工程 | 状态 |
|---|---|---|---|
| **ReAct Agent**（模型自主决策工具调用） | [react-agent.md](react-agent.md) | [`docs/examples/react/`](../../docs/examples/react/README.md) | 已核验 |
| **Workflow**（DAG 编排 + 人工审批） | [workflow-agent.md](workflow-agent.md) | [`docs/examples/workflow/`](../../docs/examples/workflow/README.md) | 已核验 |
| **DeepAgent**（目标导向任务循环） | [deepagent.md](deepagent.md) | [`docs/examples/deepagent/`](../../docs/examples/deepagent/README.md) | 已核验 |
| **Versatile 对接**（远端工作流包成 Agent） | [versatile-agent.md](versatile-agent.md) | [`docs/examples/versatile/`](../../docs/examples/versatile/README.md) | 已核验 |

选型还没定，先读 [Agent 开发路径](agent-development-path.md)。

## 跨类型能力

| 能力 | 指南 | 状态 |
|---|---|---|
| **Tool 定义与跨类型注册**（含客户端工具回传） | [tools.md](tools.md) | 已核验 |
| **Rail**（模型与工具调用的钩子链、强制收尾） | [rails.md](rails.md) | 已核验 |
| **配置驱动装配**（运行资源登记与配置消费链） | [config-driven-agent.md](config-driven-agent.md) | 已核验 |
| **A2A 跨智能体调用**（卡片、Task、流式事件） | [a2a.md](a2a.md) | 已核验 |
| **自定义 REST 入口**（宿主协议桥接到执行链） | [custom-rest.md](custom-rest.md) | 已核验 |
| **中间件配置**（状态缓存 checkpointer 与 Redis 端点） | [middleware.md](middleware.md) | 已核验 |
| **SkillHub 技能注入**（启动期下载、请求期装配） | [skillhub.md](skillhub.md) | 已核验 |
| **取消、中断与续接** | [interrupt-and-resume.md](interrupt-and-resume.md) | 已核验 |
| **接入异构本地框架** | [framework-adapter.md](framework-adapter.md) | 已核验 |
| **Task 状态与缓存** | [state-and-cache.md](state-and-cache.md) | 已核验 |
| **总线事件订阅** | [bus-events.md](bus-events.md) | 部分实现，缺真实总线证据 |

## 环境、部署与交付

| 任务 | 指南 |
|---|---|
| 从空目录建 `.venv` 与依赖 | [build-environment.md](build-environment.md) |
| 安装、检查与启动参考宿主 | [setup-and-run.md](setup-and-run.md) |
| Runtime 生命周期与宿主义务 | [lifecycle.md](lifecycle.md) |
| 部署与存量形态切换 | [deployment.md](deployment.md) |
| 验证你写的 Agent | [verification.md](verification.md) |

## runtime 不承载的能力

下面这些能力**当前 runtime 不提供托管接线与配置面**，写代码前先知道边界在哪。表里写的是**本版的承载状态**，不是「以后也不做」的结论。

| 能力 | 结论 |
|---|---|
| 沙箱（远程代码执行） | **runtime 侧未承载**：无 `external.*` 配置域、无客户端工厂、无治理层。能力在 agent-core 的 `openjiuwen.core.sys_operation.sandbox`（`SandboxRegistry`），要用就在语义层直接调。**代价是治理要自己写**——超时、重试、熔断、审计每个业务重复一遍，runtime 不提供统一的客户端工厂 |
| 认知 rail（verify / replan / self-heal） | Python 侧无 core 扩展包，无此实现；用 `AgentRail` 钩子链自行实现，见 [rails.md](rails.md) |
| `BaseInterruptRail` 与内建结构化追问 | `openjiuwen==0.1.16` 的 rail 包未导出；工作流用 `QuestionerComponent`，单 Agent 自己实现 `before_tool_call` 挂起，见 [rails.md](rails.md) |
| `WorkflowAgent` 包装层 | 类存在，但配置类在 legacy 模块、构造即告警废弃；托管工作流改用工作流资源登记，见 [workflow-agent.md](workflow-agent.md) |
| SubAgent、跨会话记忆、存储抽象、分布式追踪 | agent-core 侧有实现，runtime 无托管接线与配置开关；在语义层直接使用，见[开发指导手册](../conventions/openjiuwen开发指导.md) |


