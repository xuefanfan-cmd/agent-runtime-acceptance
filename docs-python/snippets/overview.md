---
title: 语义与装配增量片段（snippets）
description: Tool、Handler、Channel、配置等单文件增量，供 how-to 页面引用并并入已有 Agent 服务工程
audience: ai-coding
---

# 语义与装配增量片段（snippets）

本目录存放**单文件增量片段**：装配替身、协议转换、配置样例、证据模板。完整源码集在 [`examples/`](../examples/overview.md)，不在此重复。

| | examples/（完整源码集） | snippets/（本目录） |
|---|---|---|
| 形态 | 完整工程（语义层 + 服务层 + 资源 + 测试 + 构建配置） | 单文件片段（一个模块 / 一段配置） |
| 回答的问题 | 「这种 Agent 类型如何完整接线？」 | 「在已有工程上加这个能力，要加哪个文件、哪段配置？」 |
| 使用方式 | 整体复制为新工程起点 | 复制后并入工程；配置片段合并进 `application.yml` 或 `.env` |

规则：

1. Python 片段是可导入的完整模块（含 import），配置片段可整段合并；但片段不是完整工程，不含入口与构建配置。
2. 仓内文件名使用 `<能力>-<工件>.<扩展名>` 便于检索。复制时按下表的**目标文件名**落盘，并按目标工程调整导入路径。
3. 每个片段至少被一篇 how-to 或 api 页引用，引用方必须说明它是新增、替换还是合并。

## 片段索引

| 仓内片段 | 复制到工程时的目标文件名 | 内容 | 引用它的页面 |
|---|---|---|---|
| [handler-fixture.py](handler-fixture.py) | `runtime/handler.py` | 确定性流式 Handler：协议验收用的语义替身 | [framework-adapter.md](../how-to/framework-adapter.md) |
| [rest-custom-channel.py](rest-custom-channel.py) | `runtime/protocol/channel.py` | `RestChannel` 的五个边界形态 | [custom-rest.md](../how-to/custom-rest.md) |
| [rest-error-contract.json](rest-error-contract.json) | 不落盘，作为断言基准 | REST 错误信封的稳定形态 | [custom-rest.md](../how-to/custom-rest.md) |
| [a2a-card-config.yml](a2a-card-config.yml) | 合并到 `resources/application.yml` | `a2a_access` 卡片与技能项配置段 | [a2a.md](../how-to/a2a.md) |
| [a2a-send-request.json](a2a-send-request.json) | 不落盘，作为提交样例 | A2A 消息提交请求体 | [a2a.md](../how-to/a2a.md)、[state-and-cache.md](../how-to/state-and-cache.md) |
| [interrupt-resume-flow.py](interrupt-resume-flow.py) | `tests/test_resume.py` 的骨架 | 中断与续接的领域调用形态 | [interrupt-and-resume.md](../how-to/interrupt-and-resume.md) |
| [state-task-store-memory.py](state-task-store-memory.py) | `runtime/state/task_store.py` | 进程内 TaskStore：单进程验收档 | [state-and-cache.md](../how-to/state-and-cache.md)、[middleware.md](../how-to/middleware.md) |
| [client-tool-outcome.py](client-tool-outcome.py) | `runtime/client_tool.py` | 客户端工具结果的结构化回传形态 | [tools.md](../how-to/tools.md) |
| [bus-consumer.py](bus-consumer.py) | `runtime/bus/consumer.py` | 幂等消费与显式确认 | [bus-events.md](../how-to/bus-events.md) |
| [framework-adapter-contract.py](framework-adapter-contract.py) | `runtime/handler.py` | 异构框架适配器契约：不绑定具体框架版本 | [framework-adapter.md](../how-to/framework-adapter.md) |
| [lifecycle-hook.py](lifecycle-hook.py) | `runtime/lifecycle.py` | 启动钩子与生命周期观测 | [lifecycle.md](../how-to/lifecycle.md)、[rails.md](../how-to/rails.md) |
| [remote-versatile-config.py](remote-versatile-config.py) | `runtime/remote.py` | 远端适配的超时与端点配置 | [versatile-agent.md](../how-to/versatile-agent.md) |
| [config-env.sh](config-env.sh) | `deploy/.env` 的装载方式 | 配置环境变量覆盖与装载 | [config-driven-agent.md](../how-to/config-driven-agent.md) |
| [deployment-checklist.yml](deployment-checklist.yml) | 部署记录，不进代码 | 部署前后必须记录的复核项 | [deployment.md](../how-to/deployment.md) |
| [verification-evidence.yml](verification-evidence.yml) | 验收记录，不进代码 | 四层证据的记录模板 | [verification.md](../how-to/verification.md) |

所有片段都刻意省略鉴权、日志、依赖注入容器与生产密钥。
