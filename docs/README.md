# OpenJiuwen 开发文档工程

面向 **AI 编码助手与外部智能体开发 Skill** 优先的 OpenJiuwen 智能体框架文档：开发规范、
技术架构（TA）、三产品的接口知识（按 jar / 模块拆页），以及任务导向的编码指南（how-to）。

- 所有读者（含 AI 编码助手、外部 Skill）从下方「内容地图」进入即可获得全部知识，
  **不依赖任何其他入口文件**。
- `AGENTS.md` 仅面向**维护本仓文档的贡献者**（写作规则、导航地图、页面模板），
  外部交付可不包含该文件，知识消费链路不依赖它。

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
| **how-to 指南（按 agent 类型分入口）** | [how-to/overview.md](how-to/overview.md) | WorkflowAgent / ReAct / DeepAgent / Versatile 对接 / 配置驱动装配 / 中间件 / SkillHub / 自定义 REST / A2A 互调 |
| 版本兼容与依赖坐标 | [compatibility.md](compatibility.md) | 生成 pom 的坐标速查、Java/Spring Boot 基线、artifact 版本基线、代码仓地址、已知漂移 |
| Agent 源码用例 | [examples/overview.md](examples/overview.md) | how-to 引用的完整框架源码集；不重复携带 pom，由统一发布件口径做编译校验 |
| 装配/配置片段 | [snippets/overview.md](snippets/overview.md) | 可叠加到任一 agent 服务工程的单文件片段（非完整工程） |

## 布局决策记录

### 为什么是「AGENTS.md + md-first」布局

参考 langchain-docs 的 AGENTS.md，但做了三处收窄：

1. **范围收窄**：langchain-docs 覆盖 4 个产品 × 2 种语言；本工程只覆盖 OpenJiuwen
   的「规范 / 架构 / 三产品接口」三块，深度优先于广度。
2. **读者收窄**：langchain-docs 面向人类读者（Mintlify 站点）附带 AI 指南；
   本工程**主读者是 AI 编码助手**，因此把「源码锚点」「完整示例」「配置项定义列表」
   提升为硬性格式要求，弱化视觉组件（MDX 组件、卡片、图标）。
3. **站点后置**：现阶段不引入任何静态站点生成器，纯 Markdown + 目录索引，
   保证 git diff 干净、AI 直接可读、零构建成本。

### 后续是否演进到 langchain-docs 的格局？

建议**结构上对齐、工具上另选**：

- **值得对齐的**：`docs.json` 集中导航 → 对应我们的目录索引；`snippets/` 复用片段；
  「产品 → tab → group」三级导航；严格的 frontmatter 与 lint（vale）规则。
- **不必对齐的**：多语言（Python/JS）双轨、Mintlify 私有组件、`pipeline/` 自研预处理。
  OpenJiuwen 是 Java 单语言框架，复杂度需求低一个量级。

### 建站工具选型：Mintlify vs Docusaurus vs 其他

| 工具 | 许可/成本 | 优势 | 风险/劣势 | 结论 |
| --- | --- | --- | --- | --- |
| Mintlify | 商用 SaaS（开源项目有免费档） | 开箱即用的美观、AI 搜索、组件丰富 | 私有格式绑定、内网部署受限、构建链路在云端 | ❌ 不适合企业内网/自主可控场景 |
| **Docusaurus** | MIT，Meta 出品 | 版本化文档、i18n、插件生态大、MDX、本地全文搜索（@easyops-cn/docusaurus-search-local）、纯静态可内网部署 | React 技术栈、构建较重 | ✅ **推荐演进目标** |
| MkDocs Material | MIT | 最贴近 md-first 现状（几乎零迁移成本）、搜索/导航开箱即用、Python 栈 | 版本化需 mike 插件、交互组件弱于 Docusaurus | ✅ 若求「最小迁移」选它 |
| VitePress | MIT | 快、Vue 栈、适合小而美 | 生态与版本化弱于 Docusaurus | 备选 |
| Docsify | MIT | 无构建 | 无 SEO、无版本化，实质上不是站点生成器 | ❌ |

**建议路径**：

1. 现在 —— md-first（本仓库当前形态）。
2. 需要对外/对内站点时 —— 上 **Docusaurus**：目录结构原样映射为 docs plugin 的
   sidebar，文件名即 URL slug、索引页登记为 category 链接，md 内容基本零改动
   （本工程已刻意不用私有 MDX 组件，迁移阻力最小）。
3. 若团队无前端人力 —— 用 **MkDocs Material** 一步到位的替代方案。

## 后续演进路线（2026-08 评审结论）

- **P1 · solution 六项能力页**（how-to/）：config-driven-agent（✅ 已交付）、middleware
  （✅ 已交付）、skillhub（✅ 已交付）、custom-rest（✅ 已交付）、multi-react-agent、
  multi-deepagent（后两篇与 react/deepagent 强相关，由对应负责人补齐，不在本次修改范围）。
  另：sandbox（✅ 已交付，2026-08-09 新增）属 runtime 侧 `external.*` 配置域能力页，
  不计入 solution 六项。
- **P1 · api/ 可用参考**（✅ 已交付）：四篇接口文档（agent-core-java / agent-runtime-java /
  core-ext / runtime-ext）按 jar / 模块粒度提供依赖归属、核心 API、配置边界与 how-to 指针。
- **P2 · 机器可读入口与持续验证**：manifest.yaml、frontmatter/link schema 校验、
  发布件版本漂移检查，以及 examples/snippets 的临时 Maven 编译脚本；当前先人工执行，
  页面数量或自动化消费复杂度上升后接入 CI。
- **examples 不做独立 Maven 工程化**（2026-08-08 评审）：pom 写法是通用知识，
  不在每个目录重复；但 Java 源码以 compatibility 推荐发布件可编译为硬门禁。
  “完整源码”与“独立构建工程”是两个不同目标。
