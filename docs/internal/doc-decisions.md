---
title: 文档工程决策记录与演进路线（内部）
description: 本仓文档工程自身的布局决策史、建站工具选型与维护路线图，仅供维护者回查，不随发行版交付
audience: maintainer
---

# 文档工程决策记录与演进路线

> 本文件内容 2026-08-10 自 `README.md` 迁入（原文未改动）。README 只保留面向
> 知识消费者的「内容地图」；文档工程自身的决策史与路线计划归本文件维护。

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
- **P2 · 机器可读入口与持续验证**：发行过滤、frontmatter 路径、公共边界与 Markdown
  断链检查已由 `../../scripts/package-ai-coding-spec.ps1` 落地；manifest.yaml、发布件版本
  漂移检查、examples/snippets 的临时 Maven 编译脚本及 CI 接入仍待后续完成。
- **examples 不做独立 Maven 工程化**（2026-08-08 评审）：pom 写法是通用知识，
  不在每个目录重复；但 Java 源码以 compatibility 推荐发布件可编译为硬门禁。
  “完整源码”与“独立构建工程”是两个不同目标。
- **共享最小 POM**（2026-08-10 评审，对上条的细化）：不放各目录的规则不变，但
  新增一份共享模板 `examples/minimal-agent-service-pom.xml`——spring-boot-maven-plugin、
  版本配对、core 传递引入等构建契约并非纯通用知识，自由推导随机性高；该文件经
  三例 `mvn package` + fat jar 启动验证，是唯一允许携带版本字面量的例外文件，
  版本升级时与 compatibility.md 同步。
- **发行边界**（2026-08-10 评审）：docs/ 后续整体作为指导 AI Coding 的 SPEC 独立交付；
  发行版 = 本仓过滤产物，过滤规则见 `../AGENTS.md`「发行边界」——`AGENTS.md`、
  `internal/`（含本文件）不随发行版出去。
