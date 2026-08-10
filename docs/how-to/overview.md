---
title: 任务导向指南（how-to）
description: 按 agent 类型和跨类型能力分入口的 AI coding 指南——提供可落地的 OpenJiuwen 生成依据
audience: ai-coding
---

# 任务导向指南

按 **agent 类型**分入口：先选型（我要写哪种 agent），再进入对应指南。
整体框架介绍只有一处：[../architecture/00-OpenJiuwen技术架构总览.md](../architecture/00-OpenJiuwen技术架构总览.md)。

每篇指南固定 8 节（适用场景 → 最小示例/装配契约 → 能力展开 → 配置参考 → 坑位 → 端到端校验 →
API 锚点 → See also）：完整 Agent 源码用例在 [../examples/](../examples/)，
装配/配置片段在 [../snippets/](../snippets/)，md 内只摘录关键接线。

> `status: verified` / 表中 ✅ 表示公开 API、配置边界和文档接线已按推荐发布件核验；
> 有源码用例或关键 Java 片段时还需通过编译门禁。它不等同于已在所有外部依赖环境完成
> LLM、Redis、SkillHub 或远端 Agent 的端到端联调，运行验证按各页「端到端校验」执行。

| 入口 | 指南 | 示例 / 片段 | 状态 |
| --- | --- | --- | --- |
| **WorkflowAgent（DAG 编排）** | [workflow-agent.md](workflow-agent.md) | [../examples/workflow/](../examples/workflow/) | ✅ |
| **Versatile 对接 Agent** | [versatile-agent.md](versatile-agent.md) | [../examples/versatile/](../examples/versatile/) | ✅ |
| **ReAct Agent** | [react-agent.md](react-agent.md) | [../examples/react/](../examples/react/) | ✅ |
| **DeepAgent** | [deepagent.md](deepagent.md) | [../examples/deepagent/](../examples/deepagent/) | ✅ |

跨类型能力（不属于任一 agent 类型）：

| 能力 | 指南 | 示例 / 片段 | 状态 |
| --- | --- | --- | --- |
| **配置驱动 Agent**（Runner 注册/托管 + YAML 选择边界） | [config-driven-agent.md](config-driven-agent.md) | [../snippets/assembly-application.yml](../snippets/assembly-application.yml) | ✅ |
| **A2A 跨智能体互调** | [a2a.md](a2a.md) | — | ✅ |
| **中间件配置**（checkpointer / Redis 端点 / 长期记忆） | [middleware.md](middleware.md) | [../snippets/middleware-checkpointer.yml](../snippets/middleware-checkpointer.yml) | ✅ |
| **Sandbox 沙箱**（远程代码执行客户端，external 配置域） | [sandbox.md](sandbox.md) | [../snippets/sandbox.yml](../snippets/sandbox.yml) | ✅ |
| **SkillHub 技能注入**（启动下载技能包 + 请求时注册，solution 增量） | [skillhub.md](skillhub.md) | [../snippets/overview.md](../snippets/overview.md)（skillhub-*） | ✅ |
| **自定义 REST 入口**（宿主协议 ↔ A2A 桥接，solution 增量） | [custom-rest.md](custom-rest.md) | [../snippets/overview.md](../snippets/overview.md)（custom-rest-*） | ✅ |

> A2A 被调用侧的服务暴露基本类型无关；主控侧 `remote-agents → tool` 自动注入目前只支持
> BaseAgent/ReActAgent 与 DeepAgent 内部 BaseAgent。WorkflowAgent 主控需显式建模远端调用，详见 [A2A 指南](a2a.md)。
