# OpenJiuwen 文档工程 · AI Coding 指南（AGENTS.md）

> 本文件是给**维护本仓文档的 AI 编码助手与贡献者**的工作约定。修改文档前先读完本文件。
> 布局思路参考 [langchain-ai/docs/AGENTS.md](https://github.com/langchain-ai/docs/blob/main/AGENTS.md)，
> 但范围收窄为 OpenJiuwen 三产品的公开知识，并针对「指导 AI coding」这一目标做了裁剪。

## 目标读者与文档定位

本文档工程有**两类读者**，写作时必须同时兼顾：

1. **AI 编码助手**（主要）：读到某篇文档后，能生成符合框架约定、具备落地所需接线与校验步骤的 Java 代码。
2. **人类开发者**（次要）：理解框架能力边界，找到正确示例与源码位置。

因此对文档的硬要求：

- 示例代码**只使用 jar 内可得的 API**（`com.openjiuwen.core.*`、`com.openjiuwen.service.*`——用户以依赖方式获得，类全限定名可查），禁止编造。
- 示例只演示**框架能力**（组件、接线、配置项），不展开业务逻辑；业务叙述一句话带过。
- **用户可见页面（含 frontmatter）不出现任何 `third_party/` 路径**——third_party 源码
  对用户不可见。维护锚点集中维护在 `internal/source-anchors.md`（内部文件）。
- **只写用户会直接使用的公开 API**：内部协作者（HTTP client、请求/响应抽取器、
  工具注入器等框架内部类）不出现在正文、示例与 API 锚点中——它们只会扩大 AI 的
  思考范围，用户永远不会 import 它们。

## 关键规则（Critical rules）

1. **完全自包含**：本工程是文档的唯一权威来源（single source of truth）。禁止写「详见 third_party 文档」「跳转上游文档」这类索引句——`third_party/` 里的文档是零碎的工程笔记，不是本工程的内容来源。所有结论在本文档内写全。
2. **不编造 API，发布件优先**：所有类名、配置 key、方法签名必须真实存在；生成代码会使用的公开签名以 `compatibility.md` 推荐版本的发布 jar 为最终准绳，源码用于解释行为与定位实现。正文中引用实现时**用 jar 内类的全限定名**（用户按依赖可查），且只列**用户会直接 import/调用**的公开类；`third_party/` 源码路径只允许出现在 `internal/source-anchors.md`（内部维护文件），用户可见页面（含 frontmatter）一律不得出现。
3. **改导航必改索引**：新增/重命名页面时，同步更新本文件的「导航地图」与对应目录索引页（`how-to/overview.md`、`examples/overview.md`）。
4. **示例聚焦能力、examples 与 snippets 分工**：一个示例只演示一个能力点；禁止把示例写成完整业务方案。`examples/` 只放**完整 Agent 源码用例**（一个目录 = Application + Configuration + 配套类 + application.yml 的框架源码闭环，不重复 pom）；`snippets/` 放**装配/配置片段**（单文件平铺，命名 `<能力>-<工件>.<扩展名>`，如 `middleware-checkpointer.yml`）——叠加能力（middleware/skillhub/custom-rest 式）一律放 snippets 并在 how-to 页标明「在任一 agent 服务工程上叠加哪个片段」，禁止为每个叠加能力复制一整套样板工程（副本会掩盖能力差异并产生漂移）。
5. **中文写作，代码英文**：正文用简体中文；类名、配置 key、代码保持英文原文。
6. **frontmatter 必填**：每篇 md 头部带 `title` / `description` / `audience`（+ 可选 `examples`、`snippets`；how-to 能力页另需 `status`，枚举见「页面模板」）。
7. **不改动 `third_party/`**：该目录是上游源码镜像，文档只引用、不修改。
8. **文件名即定位信号**：内容页一律用描述性 kebab-case 文件名（对齐 langchain-docs：`add-human-in-the-loop.mdx`、`agent-server-overview.mdx` 式）——`find` / `glob` / `grep` 输出里文件名是模型的首要定位依据，一排 `README.md` 没有区分度。禁止 `README.md` / `index.md` 作内容页（仓根 `README.md` 与目录索引用途的 `overview.md` 除外）；目录只在其下 ≥2 个内容页时引入。**例外**：`architecture/` 的 `00-`~`05-` 六篇采用「数字前缀 + 中文名」，与 `third_party/代码架构文档` 命名对齐，便于上游刷新时逐篇回查维护（2026-08-09 合并时登记）；`conventions/openjiuwen开发指导.md` 采用中文名，与上游 `third_party/openjiuwen开发指导.md` 对齐，同理便于刷新回查（2026-08-09 合并时登记）。
9. **同主题页面用文件名前缀分组，不急于建目录**：某入口拆分出子页时，用 `<类型>-<主题>.md` 平铺命名（如 `workflow-hitl.md`、`workflow-components.md`、`react-rails.md`）——前缀使同组页面在文件清单中按字母序相邻、可直接 grep（langchain-docs 的 `src/langsmith/` 数百个文件即全部平铺）。仅当同组页面 ≥4~5 页且需要独立落地页时，才晋升为 `<类型>/` 目录（内含 `overview.md` + 子页）；晋升后必须同步修复指向这些页面的相对链接深度。

## 仓库与源码对照表（维护元数据，供框架团队校验）

| 文档模块 | 覆盖的库（用户以 jar 获得） | 源码位置（third_party/，仅内部可见） |
| --- | --- | --- |
| `api/agent-core-java.md` | agent-core-java（ReActAgent、Workflow、DeepAgent） | 以 Maven 依赖形式引入；内部用法实例在 `agent-solution/common/example/**` |
| `api/agent-runtime-java.md` | agent-runtime-java（agent-service-spec/app/adapters） | `agent-runtime-java/service/` |
| `api/core-ext.md` | core-ext（react-rails 等） | `agent-solution/common/agent-core-ext-java/` |
| `api/runtime-ext.md` | runtime-ext（versatile / agentcore-ext / SkillHub / Custom REST，当前范围） | `agent-solution/common/agent-runtime-ext-java/` |

> ⚠️ **可见性约束**：用户只能获得上表的 jar；`third_party/` 整体（含 `agent-solution/`）
> 对文档用户不可见。因此：**正文与 `examples/` 只依赖 jar 内 API**；
> 页面级维护锚点集中登记在 [`internal/source-anchors.md`](internal/source-anchors.md)，
> 供有权限的维护者在源码演进时回查修订，绝不出现在用户可见页面（含 frontmatter）。

## 项目结构

```
docs/
├── AGENTS.md                  ← 本文件（维护规范；公共知识入口不依赖它）
├── README.md                  ← 人类入口 + 建站工具选型结论
├── conventions/
│   ├── project-conventions.md ← 模块一：OpenJiuwen 专用开发规范（红线级）
│   └── openjiuwen开发指导.md  ← 按能力分章的正确/错误对照指南（命名例外见规则 8）
├── architecture/              ← 模块二：Java 高码架构设计（TA）——整体框架唯一介绍处
│   ├── 00-OpenJiuwen技术架构总览.md   ← 总览入口（三仓关系 + 调用链 + 关键边界）
│   ├── 01-agent-core-java技术架构.md
│   ├── 02-agent-runtime-java技术架构.md
│   ├── 03-agent-solution技术架构.md
│   ├── 04-三仓协作与扩展体系.md
│   └── 05-关键技术机制总结.md   ← 00~05 与 third_party/代码架构文档 对齐（命名例外见规则 8）
├── api/                       ← 模块三：用户接口文档（一库一文件）
│   ├── agent-core-java.md
│   ├── agent-runtime-java.md
│   ├── core-ext.md
│   └── runtime-ext.md
├── how-to/                    ← 任务导向指南（AI coding 主战场），描述性文件名即入口
│   ├── overview.md            ← 指南索引 + 写作约定
│   ├── workflow-agent.md      ← WorkflowAgent 编排（core DSL + 托管）【已展开】
│   ├── versatile-agent.md     ← Versatile 对接 Agent【已展开】
│   ├── a2a.md                 ← 跨类型能力：多智能体 A2A 互调【已展开】
│   ├── config-driven-agent.md ← 跨类型能力：配置驱动 Agent（YAML 装配 + 构造边界）【已展开】
│   ├── middleware.md          ← 跨类型能力：中间件配置（checkpointer / Redis / 记忆）【已展开】
│   ├── sandbox.md             ← 跨类型能力：Sandbox 沙箱客户端（external 配置域）【已展开】
│   ├── skillhub.md            ← 跨类型能力：SkillHub 技能注入（solution 增量）【已展开】
│   ├── custom-rest.md         ← 跨类型能力：自定义 REST 协议入口（solution 增量）【已展开】
│   ├── react-agent.md         ← ReAct Agent（推理循环 + 工具两步注册）【已展开】
│   └── deepagent.md           ← DeepAgent（任务循环 + 工作区交付物）【已展开】
├── internal/                  ← 内部维护文件（source-anchors.md / doc-decisions.md，不面向文档用户，不随发行版交付）
├── snippets/                  ← 装配/配置片段（单文件平铺，非完整源码集；被 how-to 页引用）
│   └── overview.md            ← 片段索引 + 与 examples 的分工规则
└── examples/                  ← 完整 Agent 源码用例（不重复 pom）
    ├── overview.md            ← 用例索引 + 收录规则
    ├── workflow/              ← 源码用例 → how-to/workflow-agent.md
    ├── versatile/             ← 源码用例 → how-to/versatile-agent.md
    ├── react/                 ← 源码用例 → how-to/react-agent.md
    └── deepagent/             ← 源码用例 → how-to/deepagent.md
```

## 导航地图

### conventions/ — 开发规范

| 页面 | 内容 |
| --- | --- |
| `conventions/project-conventions.md` | 规范总览：分层约束、命名、依赖方向、「不侵入 core-java」红线 |
| `conventions/openjiuwen开发指导.md` | 开发指导手册：Agent 引擎/服务化/工具/Rail/装配/生命周期/异构接入/SubAgent/存储/会话持久化/A2A/记忆的正确/错误对照（断言已回源码核实，2026-08-09 合并） |

### architecture/ — TA 文档

| 页面 | 内容 |
| --- | --- |
| `architecture/00-OpenJiuwen技术架构总览.md` | 总览入口：三仓定位/依赖方向 + 核心调用链 + 三条主干 + 关键边界（A2A 机制详见 how-to/a2a.md） |
| `architecture/01~05` | 分仓架构（core / runtime / solution）、三仓协作扩展体系、关键技术机制——与 `third_party/代码架构文档` 对齐 |
| `compatibility.md` | 版本兼容与依赖坐标：artifact 版本基线 / 代码仓地址 / 坐标速查 / 已知漂移（commit/tag 登记于 internal/source-anchors.md） |

### api/ — 用户接口文档（按库，一库一文件）

| 页面 | 库 | 状态 |
| --- | --- | --- |
| `api/agent-core-java.md` | ReActAgent / WorkflowAgent / DeepAgent / Workflow 组件 / ResourceMgr / 技能 | ✅ 可用参考 |
| `api/agent-runtime-java.md` | AgentHandler SPI、JiuwenCoreAgentHandler、A2A、HTTP 对话面、middleware 指针 | ✅ 可用参考 |
| `api/core-ext.md` | react-rails 三条认知 rail；ext handler 归属只做边界提示 | ✅ 可用参考 |
| `api/runtime-ext.md` | VersatileAgentHandler、ext handler、SkillHub SPI、custom REST SPI | ✅ 可用参考 |

### how-to/ — 任务导向指南（AI coding 主战场，按 agent 类型分入口）

整体框架介绍只在 `architecture/00-OpenJiuwen技术架构总览.md` 一处；各入口页只做选型链接，不重复讲框架。
索引页：`how-to/overview.md`。

| 页面 | 入口（agent 类型） | 状态 |
| --- | --- | --- |
| `how-to/workflow-agent.md` | WorkflowAgent（core DSL 编排 DAG + 托管 + A2A 暴露） | ✅ 完整 |
| `how-to/versatile-agent.md` | Versatile 对接 Agent（远端工作流包成 Agent + 编排） | ✅ 完整 |
| `how-to/react-agent.md` | ReAct Agent（推理循环 + 工具两步注册 + 托管） | ✅ 完整 |
| `how-to/deepagent.md` | DeepAgent（任务循环 + 工作区交付物 + 受限文件工具） | ✅ 完整 |

跨类型能力（不属于任一 agent 类型）：

| 页面 | 内容 | 状态 |
| --- | --- | --- |
| `how-to/config-driven-agent.md` | 配置驱动 Agent：Runner 注册/托管 + YAML 选择边界 + solution 增量边界 | ✅ 完整 |
| `how-to/a2a.md` | A2A 跨智能体互调：服务暴露 + 有类型边界的远端工具注入 + 场景→动作表 + 组合外层职责 | ✅ 完整 |
| `how-to/middleware.md` | 中间件配置：checkpointer（in-memory / Redis）+ 命名 Redis 端点 + 长期记忆 MemoryStore | ✅ 完整 |
| `how-to/sandbox.md` | Sandbox 沙箱：external.sandbox 声明式启用 + 工厂获取 SandboxClient + 三条暴露路径 | ✅ 完整 |
| `how-to/skillhub.md` | SkillHub 技能注入：启动下载技能包 + 请求时注册进 Agent（ext handler + sysOperationId） | ✅ 完整 |
| `how-to/custom-rest.md` | 自定义 REST 入口：CustomRestProtocolAdapter 协议桥接 + query-path 端点装配 | ✅ 完整 |

> **各 agent 页只讲自身装配**，不写「别人怎么消费它」——组合逻辑统一在 `how-to/a2a.md` 一处。

### examples/ — 完整 Agent 源码用例（与 how-to 页面对应；发布件编译 + 源码行为回查）

| 目录 | 能力闭环 | 被引用于 |
| --- | --- | --- |
| `examples/workflow/` | WorkflowAgent DAG 编排 + HITL + 托管 | `how-to/workflow-agent.md` |
| `examples/versatile/` | VersatileAgentHandler 对接装配 | `how-to/versatile-agent.md` |
| `examples/react/` | ReActAgent 推理循环 + 本地工具两步注册 + 托管 | `how-to/react-agent.md` |
| `examples/deepagent/` | DeepAgent 任务循环 + 受限工作区文件工具 + 托管 | `how-to/deepagent.md` |

### snippets/ — 装配/配置片段（单文件平铺，被 how-to 页引用）

| 片段 | 能力点 | 被引用于 |
| --- | --- | --- |
| `snippets/assembly-application.yml` | agent-id 基础自动装配配置；Runner 注册契约在 how-to 内联 | `how-to/config-driven-agent.md` |
| `snippets/middleware-checkpointer.yml` | Redis checkpointer 会话持久化 + 命名端点 | `how-to/middleware.md` |
| `snippets/sandbox.yml` | `external.sandbox.*` 沙箱端点配置段 | `how-to/sandbox.md` |
| `snippets/skillhub-agent-configuration.java` + `snippets/skillhub-middleware.yml` | SkillHub 技能注入：ext handler + sysOperationId + skillhub 配置 | `how-to/skillhub.md` |
| `snippets/custom-rest-agent-configuration.java` + `snippets/custom-rest-protocol-adapter.java` + `snippets/custom-rest.yml` | CustomRestProtocolAdapter 协议桥接 + 自有协议端点 | `how-to/custom-rest.md` |

## 页面模板

每篇 md 以此开头：

```markdown
---
title: 页面标题（动词开头，说明能完成什么）
description: 一句话说明读者读完能做什么；不含 markdown 格式
audience: ai-coding        # ai-coding | human | both
status: verified           # how-to 能力页必填：verified | experimental | placeholder | planned | deprecated
examples:                  # 本文引用的完整 Agent 源码用例目录（若有）
  - examples/<name>
snippets:                  # 本文引用的装配/配置片段文件（若有）
  - snippets/<能力>-<工件>.<扩展名>
---
```

> `examples` / `snippets` 路径一律相对发行版 SPEC 根目录（即 README.md 所在目录），不得带 `docs/` 前缀；frontmatter 不含任何源码路径，维护锚点统一登记 `internal/source-anchors.md`。

### 指南（how-to）页正文模板

指南页是 AI coding 的主战场，**固定为以下 8 节，顺序不可乱**：

```markdown
# <标题：用 X 完成 Y>

<一段：这是什么（定义）+ 让你能做什么（能力边界）。>

## 适用场景 / 不适用场景        ← 帮 AI 做选型判断（如 Workflow vs ReAct）
## 最小完整示例                 ← 引用 examples/ 用例；机制页可改名「最小装配契约」并引用 snippets/ + 内联微型契约
## 能力点逐个展开               ← 每个能力点一小节：API + 最小片段 + 要点列表
## 配置项参考                   ← 粗体定义列表：- **key**：含义。默认值。注意事项。
## 坑位与排错                   ← `> ⚠️` 醒目块 + 正确 ✅ / 错误 ❌ 对照
## 端到端校验                   ← 怎么确认写对了：启动命令、请求样例、预期响应要点
## API 锚点                     ← 本文涉及的 jar 内公开类全限定名（只列用户会直接使用的）

## See also                     ← 只列本工程内的相关页面
```

写作约定：

1. **先定义，再能力，后步骤**：开头一句话说清「这是什么」，第二句「让你能做什么」，然后给最小完整示例或接线片段。
2. **完整源码与 md 分离**：完整 Agent 源码用例放在 `examples/<name>/`（一个目录 = Application + Configuration + 配套类 + application.yml，代码唯一来源，不重复 pom）；装配/配置片段放在 `snippets/`（单文件平铺，命名 `<能力>-<工件>.<扩展名>`，how-to 页必须写明「在任一 agent 服务工程上叠加哪个片段、新增还是替换」）；md 的「最小完整示例 / 最小装配契约」节**引用用例目录 / 片段文件链接 + 摘录关键接线片段**（bean 装配、DAG 骨架等 10~30 行），不整文件复制。所有 Java 源码以 compatibility 推荐发布件可编译为维护硬门禁，但目录本身不宣称是独立 Maven 工程。
3. **微型片段保持内联**：3~10 行的 API 示范、正确/错误对照直接在 md 内联，不为它们建文件。
4. **配置项用粗体定义列表，且必须声明可设置边界**：只列**支持用户设置**的属性
   （`- **url-template**：含义。默认值、占位符规则、注意事项。`）；属性类中的其余
   字段（内部/实验性配置）不列出，并用 `> ⚠️` 边界块显式声明「其余属性为框架内部
   配置，不要设置」。
5. **坑位警告独立成节**：框架的易错点（如两套模板分隔符）用 `> ⚠️` 醒目块 + 正确/错误对照。
6. **文末 See also 只链本工程页面**；「API 锚点」小节只列用户会直接 import/调用的
   jar 内公开类（内部协作者如 HTTP client、抽取器、注入器一律不列）。

## 代码示例规范

- Java 代码块标 ` ```java `，YAML 标 ` ```yaml `，禁止无语言标签。
- import 必须写全（AI 依赖 import 推断包归属）；按 java 标准库 → 第三方 → com.openjiuwen 排序。
- 示例中的配置值用占位符 + 默认值注释（如 `${LLM_API_BASE:}`），不写死密钥。
- `examples/` 与 `snippets/` 下的 Java 文件必须完整（含 package 与 import），并使用 `compatibility.md` 推荐发布件做真实编译校验；snippets 复制到临时工程时须按 public class 重命名并调整 package。禁止「略」「// ...」式省略。未接入持续校验前，不在用户页面承诺每次提交后都自动验证。

## 独立性原则（自包含与可见性）

- 本工程**完全独立**（同 langchain-docs 与其代码仓的关系）：所有内容在 `docs/` 内写全，
  不索引、不跳转 `third_party/` 内的任何文档（那里是零碎的工程笔记）。
- **用户可见性分层**：文档消费者的 classpath 里只有 jar（agent-core-java、
  agent-runtime-java、core-ext、runtime-ext）。正文、示例、配置契约一律以 jar 内
  公开 API 为准表达；未开放源码既不作内容来源，也不作呈现对象。
- `third_party/` 的合法用途是**维护者校验**：写文档前读源码确认 API，把依赖的源码
  文件登记到 `internal/source-anchors.md`（内部文件）；源码演进时按清单回查修订。
- 「已验证组合」的置信度用一句话陈述（如「该组合已经框架侧端到端验证」），
  不链接用户看不到的验证工程。

## 发行边界（Distribution boundary）

docs/ 后续整体作为指导 AI Coding 的 SPEC 独立交付：本仓是开发版（single source of
truth），发行版是本仓的**过滤产物**。过滤规则固定为三条，不做节级裁切：

1. 排除 `AGENTS.md`（写作规则/导航地图/评审清单，只服务本仓维护）；
2. 排除 `internal/` 整目录（source-anchors.md、doc-decisions.md）；
3. 排除 frontmatter `audience: maintainer | internal` 的文件（兜底规则，防未来
   维护页混入知识目录）。

发行版以 `README.md` 所在目录为 SPEC 根目录；内容 = `README.md`（内容地图）+
`conventions/` + `architecture/` + `api/` + `how-to/` + `examples/` + `snippets/` +
`compatibility.md`。新增页面时按上述规则
自证归属：面向知识消费者的内容不得写进维护文件；维护性内容不得留在发行文件中
（含「外部交付」「本仓」这类元话语）。维护者使用根目录
`scripts/package-ai-coding-spec.ps1` 生成并校验过滤产物，不手工复制整个 `docs/`。

## 外部改动评审清单（Review checklist）

其他工具/协作者对本仓文档做过改动后，按本清单逐项评审再入库——目标是把
「外部改动 → 人工/模型评审」固化为固定动作，而不是每次临时发挥：

1. **新增技术断言采用双向核实**：凡是改动引入或修改的事实性表述，公开类名/方法
   签名/返回类型先以 `compatibility.md` 推荐版本的发布 jar 核验；配置 key/默认值/
   装配时机/支持类型边界再回 `third_party/` 源码核实，并把新依赖的源码文件登记到
   `internal/source-anchors.md`。jar 与源码快照不一致时，生成代码按 jar 编写并登记漂移；
   无法核实的断言不入库。核实结论写进 commit message。
2. **删除内容必须对照需求历史**：外部改写常以「精简」为由删节。凡整节/整表被删，
   先查该内容是否源自用户的明确要求（如 a2a 的「场景→动作」表、「组合外层职责」）——
   需求驱动的内容只能改写不能删，确需删除时在提交说明中写明理由。
3. **断言措辞的「推理安全性」**：检查表述是否会被 AI 按字面推出错误结论（曾发生：
   「由 JiuwenCoreAgentHandler 在 Runner 启动前应用」→ 推出手动单参 Bean 时
   checkpointer 不生效）。关键机制写明「与 X 无关 / 两种路径均生效」这类反歧义句。
4. **风格与格式一致性**：引号统一用中文直角引号「」（不用英文弯引号 “”，中文语境
   也不用 ASCII 直引号 "…"）；中文语境的分句符号一律全角——`,` `;` `:` 左右任一
   侧为中文字符/中文标点时转换为 `，` `；` `：`（跳过代码围栏、行内代码段、
   frontmatter、URL 与版本号；转换后去掉「： 」残留空格）；章节结构遵守「页面模板」
   的固定 8 节顺序；新增内容页遵守描述性 kebab-case 命名；
   frontmatter 键（`title`/`description`/`audience`/`status`/`examples`/`snippets`）齐全。
5. **机械校验必须全绿**：内部链接零断链（改动后跑一遍链接检查）；被删文件无残留
   引用（grep 文件名）；索引同步——`AGENTS.md` 导航地图、`how-to/overview.md`、
   `examples/overview.md`、`snippets/overview.md`、`README.md` 内容地图与改动一致；
   examples 与关键 Java snippets 复制进临时 Maven 工程后，使用推荐发布件完成 `mvn compile`；
   执行 `scripts/package-ai-coding-spec.ps1`，确认公开文件、frontmatter 路径与内部链接校验全绿。
6. **可见性边界不滑坡**：用户可见页面（含 frontmatter）不出现 `third_party/` 路径、
   不出现用户拿不到的内部类；可设置配置项边界块（⚠️ 内部属性不要设置）不被删改。
   **显式黑名单检查**：改动后对用户可见页面（`docs/internal/` 以外）grep 以下三类
   模式，必须零命中——
   a. 维护面引用（2026-08-10 发行边界落地后追加）：`third_party`、`internal/`、
      `source-anchors`——公开内容不得把读者引向维护文件或源码镜像，
      违者改写为消费者视角的事实性表述；
   b. 内部实现包类名：`service/app/controller/**` 下类型（如
      `A2AEnabledServeOrchestrator`、`RemoteAgentCaller`、`RemoteAgentCardResolver`、
      `A2AAgentCardDiscovery`、`A2ARemoteAgentCardRegistry`、`A2ARemoteAgentClient`）、
      agentcore-ext 内部安装器（`RemoteA2aToolInstaller`）等只服务于 debugging 的
      实现类（排查指引可在「坑位与排错」用文字描述，不点名类名）；
   c. demo/示例工程类名：`agent-service-demo/**`、`agent-solution/**/example/**` 独有
      类型（如 `A2aDelegateRail`、`MemoryToolRegistrar`、`DecoratedSandboxToolRegistrar`、
      `ExampleReActAgentFactory`、`ExecutionLimitRail`、`EdpaAgentEnhancer`、
      `A2AGatewayRemoteAgentCaller`）以及「官方 demo」字样——demo 类用户拿不到，
      一律改写为「框架未内置 X，需自行封装」式表述；
   d. 参考命令（名单随新发现的内部类追加）：
      `grep -rn -E "third_party|internal/|source-anchors|A2AEnabledServeOrchestrator|RemoteAgentCaller|RemoteAgentCardResolver|A2AAgentCardDiscovery|A2ARemoteAgentCardRegistry|A2ARemoteAgentClient|RemoteA2aToolInstaller|A2aDelegateRail|MemoryToolRegistrar|DecoratedSandboxToolRegistrar|ExampleReActAgentFactory|ExecutionLimitRail|EdpaAgentEnhancer|A2AGatewayRemoteAgentCaller|官方 demo" docs --include=*.md | grep -v -e docs/internal/ -e docs/AGENTS.md`
   e. **单一版本口径**：用户可见页面不出现上游 commit hash / tag / 源码镜像 POM 版本
      （含 `v0.x.y` 式 tag 与 12 位 hex commit）；版本表述一律以 `compatibility.md` 的
      发布件口径为唯一来源，镜像锚点只登记到 `internal/source-anchors.md`。
      外部改动带入此类信息时迁出而非保留（2026-08-09 compatibility.md 单一化时登记）。
      唯一允许携带版本字面量的其他文件是 `docs/examples/minimal-agent-service-pom.xml`
      （共享最小 POM，2026-08-10 引入）；版本升级时它与 compatibility.md 必须同步更新。

## 演进路线（详见 internal/doc-decisions.md）

当前阶段：**md-first**，纯 Markdown + 描述性文件名 + 目录索引页，保证 AI 与 git diff 友好。
下一阶段（需要对外站点时）：迁 Docusaurus（选型结论见 `README.md`），迁移时目录结构不变，
文件名即 URL slug，仅需把索引页登记为 sidebars 配置。
