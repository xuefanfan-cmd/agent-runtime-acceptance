# OpenJiuwen 开发文档工程

面向 **AI 编码助手与外部智能体开发 Skill** 优先的 OpenJiuwen 智能体框架文档：开发规范、
技术架构（TA）、三产品的接口知识（按 jar / 模块拆页），以及任务导向的编码指南（how-to）。

- 所有读者（含 AI 编码助手、外部 Skill）从下方「内容地图」进入即可获得全部知识，
  **不依赖任何其他入口文件**。

## 内容地图

| 模块 | 目录 | 回答的问题 |
| --- | --- | --- |
| 开发规范 | [conventions/project-conventions.md](conventions/project-conventions.md) | OpenJiuwen 怎么用、怎么设计：分层、依赖方向、红线 |
| 开发指导手册 | [conventions/openjiuwen开发指导.md](conventions/openjiuwen开发指导.md) | 按能力分章的正确/错误对照指南：Agent 引擎 / 服务化 / 工具 / Rail / 装配 / 生命周期 / 异构接入 / SubAgent / 存储 / 会话持久化 / A2A / 记忆 |
| 架构设计（TA） | [architecture/00-OpenJiuwen技术架构总览.md](architecture/00-OpenJiuwen技术架构总览.md) | 整体技术架构：三仓定位/依赖 + 核心调用链 + 关键边界（分仓细节见 01~05 子文档） |
| 跨智能体机制 · A2A | [how-to/a2a.md](how-to/a2a.md) | 跨进程互调标准通道：skill 暴露 / 有类型边界的远端工具注入 / 中断透传 |
| 接口文档 · agent-core-java | [api/agent-core-java.md](api/agent-core-java.md) | ReActAgent / WorkflowAgent / Workflow 组件 API |
| 接口文档 · agent-runtime-java | [api/agent-runtime-java.md](api/agent-runtime-java.md) | AgentHandler SPI、托管、A2A、HTTP 对话面 |
| 接口文档 · core-ext | [api/core-ext.md](api/core-ext.md) | react-rails 认知 rail（纯 core 扩展） |
| 接口文档 · runtime-ext | [api/runtime-ext.md](api/runtime-ext.md) | versatile、agentcore-ext / SkillHub、Custom REST 与能力边界 |
| **how-to 指南（按类型与能力分入口）** | [how-to/overview.md](how-to/overview.md) | WorkflowAgent / ReAct / DeepAgent / Versatile 对接，以及 Tool / Rail / 配置驱动 / 中间件 / SkillHub / Custom REST / A2A |
| 版本兼容与依赖坐标 | [compatibility.md](compatibility.md) | 生成 pom 的坐标速查、Java/Spring Boot 基线、artifact 版本基线、代码仓地址、已知漂移 |
| Agent 源码用例 | [examples/overview.md](examples/overview.md) | how-to 引用的完整框架源码集；各类型目录不重复携带 pom，共用一份已验证的最小 POM |
| 语义/装配增量片段 | [snippets/overview.md](snippets/overview.md) | Tool、Rail、SubAgent 与 runtime 配置等可叠加单文件片段（非完整工程） |

## 事实源分工（按领域各认一家）

内容有交叉时按事实领域认定唯一来源，避免不同步的重复描述互相矛盾：

| 事实领域 | 唯一来源 |
| --- | --- |
| 版本与 artifact 坐标 | [compatibility.md](compatibility.md) |
| 公开类型、方法与契约 | [api/](api/agent-core-java.md) |
| 任务实现边界与坑位 | [how-to/](how-to/overview.md) |
| 可复制源码 | [examples/](examples/overview.md) |
| 原理与反模式（仅解释用） | architecture/ 与[开发指导手册](conventions/openjiuwen开发指导.md) |

发生冲突时不得按页面整体确定优先级：依赖版本以 compatibility 为准，公开类型与签名以 API（及其对应的推荐发布 JAR）为准，可复制实现以 examples 为准，行为边界以 how-to 为准。
