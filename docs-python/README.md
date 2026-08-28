# agent-runtime Python 开发文档

面向 Python Agent 开发者、宿主与适配器开发者、部署方与 AI 编码助手的 OpenJiuwen 生态文档。新业务 Agent 默认先用 `openjiuwen` agent-core 完成语义能力，再由 runtime 承担托管与服务化。

**所有技术结论以当前 Python 实现与已安装的 `openjiuwen==0.1.16` 为准**：类型与签名回源码或已安装包核实，行为回可执行测试核实。

## 内容地图

| 模块 | 目录 | 回答的问题 |
|---|---|---|
| 开发规范 | [conventions/project-conventions.md](conventions/project-conventions.md) | 怎么分层、依赖往哪个方向、哪些做法越过红线 |
| 开发指导手册 | [conventions/openjiuwen开发指导.md](conventions/openjiuwen开发指导.md) | 按能力分章的正确与错误对照：Handler、领域结果、组合根、通道、状态、Rail、边界能力 |
| 架构设计 | [architecture/00-OpenJiuwen技术架构总览.md](architecture/00-OpenJiuwen技术架构总览.md) | 整体分层、主调用链与关键边界（分层细节见 01~05 子文档） |
| 接口文档 · agent-core | [api/agent-core-python.md](api/agent-core-python.md) | ReAct / Workflow / DeepAgent、Tool、Rail、Runner 的 Python 接口 |
| 接口文档 · agent-runtime | [api/agent-runtime-python.md](api/agent-runtime-python.md) | AgentHandler SPI、领域契约、两类入站入口、状态端口与配置模型 |
| 接口文档 · runtime 扩展 | [api/runtime-ext.md](api/runtime-ext.md) | 出站适配器矩阵与 Python 侧 ext 的归属说明 |
| **how-to 指南（按类型与能力分入口）** | [how-to/overview.md](how-to/overview.md) | ReAct / Workflow / DeepAgent / Versatile 对接，以及 Tool、Rail、配置装配、A2A、自定义 REST、中间件、SkillHub |
| 版本兼容与依赖基线 | [compatibility.md](compatibility.md) | 依赖坐标、推荐发布件、配置来源、运行前置与已知漂移 |
| Agent 源码用例 | [examples/overview.md](examples/overview.md) | 完整源码集的索引、目录约定与共享最小构建模板 |
| 可叠加片段 | [snippets/overview.md](snippets/overview.md) | 在已有工程上加一项能力要加哪个文件、哪段配置 |

完整可复制源码在 [`examples/`](examples/overview.md) 下的类型目录里——那是唯一源码来源，正文只摘录关键接线。

## 事实源分工（按领域各认一家）

内容有交叉时按事实领域认定唯一来源，避免不同步的重复描述互相矛盾：

| 事实领域 | 唯一来源 |
|---|---|
| 版本与依赖基线 | [compatibility.md](compatibility.md) |
| agent-core 公开接口与行为 | 已安装的 `openjiuwen==0.1.16`，文档面在 [api/agent-core-python.md](api/agent-core-python.md) |
| runtime 公开签名与契约 | `agent_runtime/` 源码，文档面在 [api/agent-runtime-python.md](api/agent-runtime-python.md) |
| 对外 wire 形态 | runtime 的测试与部署级 E2E |
| 任务实现边界与坑位 | [how-to/](how-to/overview.md) |
| 可复制源码 | [`examples/`](examples/overview.md) |
| 原理与反模式（仅解释用） | [architecture/](architecture/00-OpenJiuwen技术架构总览.md) 与[开发指导手册](conventions/openjiuwen开发指导.md) |

发生冲突时不按页面整体定优先级：依赖版本以 compatibility 为准，公开签名以已安装包与源码为准，可复制实现以 `examples/` 为准，行为边界以 how-to 为准。

## 阅读顺序

**首次开发 Agent**：[compatibility.md](compatibility.md) → [how-to/build-environment.md](how-to/build-environment.md) → [how-to/agent-development-path.md](how-to/agent-development-path.md) → [api/agent-core-python.md](api/agent-core-python.md) → 复制 [`examples/react/`](examples/react/README.md)。

**首次接入 runtime**：[how-to/setup-and-run.md](how-to/setup-and-run.md) → [how-to/config-driven-agent.md](how-to/config-driven-agent.md) → [how-to/a2a.md](how-to/a2a.md)。

**开发新适配器**：[architecture/00-OpenJiuwen技术架构总览.md](architecture/00-OpenJiuwen技术架构总览.md) → [api/agent-runtime-python.md](api/agent-runtime-python.md) → [how-to/framework-adapter.md](how-to/framework-adapter.md) → [conventions/openjiuwen开发指导.md](conventions/openjiuwen开发指导.md)。

**排查线上协议问题**：[architecture/02-agent-runtime-python技术架构.md](architecture/02-agent-runtime-python技术架构.md) → [api/agent-runtime-python.md](api/agent-runtime-python.md)。

## 当前实现状态

| 状态 | 范围 |
|---|---|
| 已接线并有部署级验证 | 自定义 REST、A2A 入口、agent-core 三类形态托管、异构框架适配、远端代理、客户端工具、远端工具、取消、断连、总线与生命周期路径 |
| 已实现但需谨慎装配 | 状态缓存外置档、批次协调、中断协调、回调接收、技能中心客户端；是否可用以具体装配点与 E2E 为准 |
| 设计已定、仍有补齐项 | 部分对外端点可达性、特定远端出站形态、多副本一致性边界；见各架构页的「限制与待补」 |
| 不由 runtime 承载 | SubAgent 体系、跨会话记忆、存储抽象、分布式追踪接线；边界见[开发指导手册](conventions/openjiuwen开发指导.md) |

**装配门禁通过不等于真实环境可用。** 门禁用占位端点、不出网，证明的是类型、导入闭包、分层红线、运行资源登记与协议形态；真实模型、状态后端与远端依赖要按各页「端到端校验」单独验证。
