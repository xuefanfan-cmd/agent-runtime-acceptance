---
title: FEAT-065 确定性编排层（蓝图调度）测试设计初稿
feature_id: FEAT-065
document_type: test-design
status: draft
automation_status: design-only
feature_source: develop/02-features/FEAT-065-deterministic-blueprint-orchestration.md @561f02dd sha256=d7aa8eeb39b1d239518ff2bdb3d7ab657f3a8e7ac346c3b0ce4c43bf0eb994a5
l2_source: develop/03-architecture/L2-Low-Level-Design/deepanalyze/Feat-Func-065-deterministic-blueprint-orchestration.md @561f02dd sha256=e709a37a70c2e5de31c4d797dc5484967d02625007b0a5d047eef73848601a7d
requirement_catalog: FEAT-065/FEAT-065-需求条目目录.json（38 条：in 34 / out 4；execution 33 / static 5）
requirement_preview: ../02-需求与测试预览-FEAT-065-067.md
change_scope: ../01-变更包范围卡-FEAT-065-067.md
input_maturity: merged
updated: 2026-10-15
---

# FEAT-065 确定性编排层（蓝图调度）测试设计初稿

> **⚠️ 基线已刷新（2026-10-15 19:21/19:29）——本文件的部分期望值已作废。**
> docs 由 `561f02dd` → `9ab89fc8`、agent-solution 由 `53c1a174a` → `f9da5b54f`，开发与文档侧已按 ISSUE #31/#32/#33 逐条修订。
> **实现 Java 前必须先读** `/Users/zhangjiusi/资料/06_工作/Test_work/01-Design_File/20261015-feat-065-067-orchestration/03-增量审视与基线刷新记录-20261015.md` **§4**，
> 其中列明了本文件哪些用例的期望值必须更正（例如 FEAT-066 的 `066-S15` 主断言已**完全反转**：由"断言静默降级"改为"断言装载期拒绝"）。
> 本文件按 RULES 保留原样作为旧基线下的审计痕迹，不回头改写。


> 本文件是**测试设计细档**（逐用例判定规则、证据要求、自动化落点），**不是正式 TestPlan**；
> 正式 TestPlan 另按 `references/testplan-authoring.md` 生成并发布到 docs 仓 `develop/04-testplan/`。
> 动态结果只链接覆盖台账，不在本文件手工重复维护。

## 0. 用户必读：需求与测试方案

- **一句话说明**：让多 Agent 协作有一条"照图施工"的确定性通道——人先用图纸（YAML）写清成员、步骤与依赖，引擎严格照图执行、每步留痕，而不是全靠组长模型临场规划。
- **关键链路**：调用方装载图纸 →（装载期 fail-fast 校验，违规即拒且不产生任何执行）→ 引擎按拓扑分层、层内并行执行 → 每步输入 = 成员指令 + 上游产物段 + 待读消息段 + 原始用户输入段 → 每步（含跳过步）在层汇合后按确定序登记留痕 → **收口产物按拓扑层逐层收集**：本层全空白保留此前结果、单份非空白原文替换、多份非空白按声明序分节替换（L1 现行口径，2026-11-18 评审刷新）。
- **本期测试**：23 个场景，分三层——
  - **L1 组件层（21 个场景，确定性执行体）**：证明引擎语义本身，含四形态执行序、装载违规全集、投影四段、跳过传播、留痕登记序、失败终止、第二后端拒绝口径、收口回退链；
  - **L2 端到端（1 个场景，真实 LLM 成员）**：证明同一批图纸在"成员 = 真实模型"下仍成立，且**留痕结构与 L1 一致**；
  - **静态（1 个场景）**：执行层无 `cross_validation` 专用分支（设计本来要求 grep 结构性审查）。
- **本期不测**：自由谓词跳过、断点恢复与跨进程持久化、HITL 断点交互、core 仓改动（Feature §2 四项 OUT）；模板库内容；core Workflow 引擎自身行为（L2 §1.3 明示归 core 自测）。
- **未决影响**：`Q065-D1`（空/空白判据）、`Q065-D3`（弃收可观测）、`Q065-D4`（违规全集边界）、`Q065-D5`（第二后端 gating 归属）与偏差 `DV065-01/02/03` 已提交 **ISSUE #31**；未答前受影响用例按 §6 各条标注的形态执行，`partial` 项不阻塞其余场景。
- 详细预览：`/Users/zhangjiusi/资料/06_工作/Test_work/01-Design_File/20261015-feat-065-067-orchestration/02-需求与测试预览-FEAT-065-067.md`
- 本期范围：同目录 `01-变更包范围卡-FEAT-065-067.md`
- **输入成熟度**：`merged`（文档 `561f02dd`、代码 `53c1a174a` 均已合入正式分支；!805 与二轮修复经 `git merge-base --is-ancestor` 核实为该快照祖先）⇒ 可用于正式收口
- **用户理解确认**：`confirmed`，2026-10-15（范围卡 §7.1 第 1/3 项，含"L1 用测试自带确定性执行体不属 Mock LLM、L2 用真实模型互为对照"的判断）

## 1. 依据、当前口径与范围

- **设计真相源**：`Technical-AF/docs` @ `561f02dd`，`develop/02-features/FEAT-065-deterministic-blueprint-orchestration.md`（145 行）
- **L2 实现说明**：同 revision，`develop/03-architecture/L2-Low-Level-Design/deepanalyze/Feat-Func-065-deterministic-blueprint-orchestration.md`（276 行）
- **已确认裁决**（用户 2026-10-15）：① 三层执行体分工（L1 确定性执行体 / L2 真实 LLM 成员 / L3 真实 LLM 主 Agent），L1 与 L2 互为对照；② 本期范围；③ 需求理解与测试方向；④ 授权测试侧注入 `-Ddeepanalyze.workflow.enabled=true`
- **已自行核实的实现口径**（不外抛、直接作为判据；证据见 `issues/ISSUE统一台账.md` §3）：
  - 缺席判据 = **`isBlank()`**（`null`/`""`/`"   "` 全算缺席）——`StepOutcome.java:26`、`BlueprintResult.java:29`、`BlueprintScheduler.java:171,177,220`
  - 留痕查询**保序**——`BlueprintResult.outcomes` 为 `LinkedHashMap`，javadoc 自称"登记序 = 执行层序 × 层内声明序——parity 判据"；`stepOutcomes()` = `List.copyOf(outcomes.values())`
  - **失败步自身不登记**留痕；`execute` 抛异常时**不返回** `BlueprintResult`——`BlueprintScheduler.java:160-193`
  - 编译步（含交叉验证）`skipIf` **恒为 `null`→`NEVER`**——`BlueprintLoader.java:113,118,163`
  - 装载校验实际为 **9 项**（javadoc `BlueprintLoader.java:25-31`），比 Feature §2 的 7 项多 4 项
- **范围内**：需求条目目录 34 条 `scope=in`
- **范围外**：4 条 `scope=out`（Feature §2 的 OUT 行）+ 范围卡 §4.1 的 12 条类型 (b) 条目 + §4.2 的 FEAT-054 宿主装配面
- **未决问题**（逐项标责任人层次）：

| 未决项 | 责任人层次 | 状态 | 对本期用例的影响 |
|---|---|---|---|
| `Q065-D1` 空/空白判据的产品口径 | 设计确认 | 已提 ISSUE #31 D1；实现侧已核实为 `isBlank()` | `"   "` 形态可构造但**期望值待裁决** ⇒ 相关断言标 `partial`，主断言只用 `null`/`""`/被跳过 |
| `Q065-D3` 失败时并行步"弃收"的可观测判据 | 设计确认 | 已提 ISSUE #31 D3；实现侧已核实"失败步及其后不登记、不返回结果" | 留痕条数断言按已核实事实写，但**设计口径未确认** ⇒ 标 `partial` |
| `Q065-D4` 违规全集边界（`steps: []` 等未列举形态） | 设计确认 | 已提 ISSUE #31 D2；javadoc 已给 9 项 | 按 **L2 §2.1 唯一权威清单**构造（2026-10-19 裁决后为 **12 项**，新增 ⑪「非法跳过策略值」）；未列举形态**不断言应拒**，登记缺口 |
| `Q065-D5` 第二后端 SHOULD vs §6"必须覆盖" | 设计确认 | 已提 ISSUE #31 D4 | 照 §6 实现并执行，但覆盖台账标注 gating 归属待裁决 |
| `DV065-01` 装载错误是否含图纸 id（三方不一致） | 设计 + 开发 | 已提 ISSUE #31 V1 | 主断言取三方**交集**；"含图纸 id"按违规族分别断言 |
| `L065-04` 真机门控变量名是否两套 | 开发-L2 确认 | 已提 ISSUE #31 L4 | 影响 065-S22（L2 层）能否跑通；未答则该场景标 `blocked` |
| 制品构建、`~/.llmrc`、SIT pom 依赖 | 测试侧自行核实 + 用户授权 | 见 §7 | 影响执行阶段，不影响设计 |

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| `com.openjiuwen:agent-core-ext-orchestration`（**直接依赖，组件层**） | 被测模块本体 | 全部 33 条 `execution` 条目中的引擎语义面：装载校验、四形态调度、投影、跳过、留痕登记序、失败终止、第二后端编译、收口 | 会话侧工具契约（归 FEAT-067）；宿主装配与门控 | **适用，且是唯一能覆盖全部引擎语义的层次**。依据：① Feature §3「并行度 \| 装配参数 \| 层内并行度由宿主在构造期给定」⇒ 经宿主无法给定并行度，构造不出并行证据；② Feature §3「成员绑定契约 \| 宿主按成员标识提供执行体」⇒ 组件层可自带确定性执行体；③ Feature §1 面向角色明写「测试与验收团队：以确定性图纸做**可重放**的行为判据」 |
| `deepanalyze`（DA 宿主 SUT，`com.openjiuwen:deepanalyze-engine:<rev>:exec`） | L2 层真实成员载体 | 065-S22：真实 LLM 成员下的执行与留痕结构对照 | 装载违规全集、并行闩锁、投影段结构（宿主不暴露这些内部面） | **适用但仅 1 个场景**。依据：既有 `SutStack.agent("deepanalyze")` 通路现成（`cases/e2e/deepanalyze/` 29 个文件、26 个带 `@Tag("feat-054")`）；宿主经 FEAT-067 `workflow_run` 驱动图纸 |
| `deepagent` / `reactagent` / `workflow` 三个既有 SUT | — | — | 全部 FEAT-065 能力 | **不适用**。依据：RULES「SUT、fixture 与代码边界」第 1 条要求"依据设计/L2、能力归属和适用性分析决定，不得仅因存在三个 SUT 就默认全覆盖"；FEAT-065 §1 明确本特性是 `agent-solution` 仓的**编排引擎模块**，Feature §2 OUT「core 仓改动」+ L2 §1.1「不承接…core 仓任何改动」⇒ 与这三个 SUT 无能力交集 |

**结论**：FEAT-065 的主战场是**组件层直接依赖被测模块**（21/23 场景），L2 端到端只承担"真实成员形态对照"1 个场景，静态 1 个场景。**不新建 fixture**——依据 RULES 边界第 2/3 条，被测模块的公开 API（`BlueprintLoader.load` / `BlueprintScheduler` / `BlueprintWorkflowCompiler` / `SubAgentExecutor`）已足够，无需改任何 fixture。

## 3. 黑盒边界、拓扑与前置条件

**黑盒边界**：只消费 `agent-core-ext-orchestration` 的公开面（`BlueprintLoader.load(String)`、`new BlueprintScheduler(TraceForest, int, Function)`、`BlueprintScheduler.execute(spec, bindings, userInput)`、`BlueprintWorkflowCompiler.compile(spec, bindings)`、`SubAgentExecutor.execute(userInput, subGoal)`、`BlueprintResult` / `StepOutcome` 的公开访问器）。**不 Mock LLM**（工作区铁律）；L1 层的成员执行体是**测试作为装配方按 Feature §3 的成员绑定契约提供的实现**，不是对真实 LLM 调用的替换（用户 2026-10-15 已确认该判断）。**不直连 fixture 数据库断言**（本特性无数据库）。

**拓扑**：
- L1：单 JVM，无进程、无容器、无网络。测试持有 `TraceForest` 实例并注入 `BlueprintScheduler` 构造器（Feature §3「留痕载体 \| 宿主持有」⇒ 测试即装配方）。
- L2：`SutStack` 拉起 `deepanalyze` 宿主进程 + Redis 容器；需真实模型凭据。
- 静态：只读源码，无运行时。

**前置条件**：

| # | 前置 | 状态 | 依据 |
|---|---|---|---|
| P1 | `com.openjiuwen:agent-core-ext-orchestration` 由 `common`@`53c1a174a` 源码构建并 install 到工作区 Maven 本地仓 | **未就绪**（公网镜像无此制品，已实测 404） | ENV 台账 ENV-3.1/3.2 |
| P2 | acceptance 仓 `pom.xml` 新增该依赖 | **未就绪**（实测 `grep -n orchestration pom.xml` NOT_FOUND）；**用户 2026-10-15 已授权** | ENV 台账 ENV-5.4 |
| P3 | `agent-core-ext-react-rails`（被测模块的唯一内部依赖）可解析 | 待核实（构建 P1 时一并验证） | 被测模块 `pom.xml` 依赖声明 |
| P4 | L2 层：`deepanalyze-engine:<rev>:exec` 源码构建 + Redis（Docker）+ 模型凭据 | **未就绪**（制品 404；`~/.llmrc` absent；docker/colima 状态矛盾未核实） | ENV 台账 ENV-3.1/4.1/1.4 |
| P5 | L2 层门控开启：注入 `-Ddeepanalyze.workflow.enabled=true` | **用户 2026-10-15 已授权**（范围卡 §7.1 第 4 项） | 范围卡 §7.1 |

**执行环境**：macOS 本机（构建与执行同环境，RULES「执行环境分工」第 1 条）；JDK 21（Corretto `21.0.12.1`）+ Maven 3.9.16；Maven 本地仓 `-Dmaven.repo.local=<工作区>/04-Environment/m2repo`。结论必须注明执行环境（RULES 第 4 条）。

## 4. 需求覆盖矩阵

一行 = 设计或 L2 的一条可判定条目。稳定条目目录：`FEAT-065/FEAT-065-需求条目目录.json`（38 条，sha256 见 front-matter 引用）；唯一覆盖台账与缺口视图待 manifest 阶段生成（`review-deliverables-feat-065.json`），实际结果不在本文件手工重复维护。

| REQ | 条目（锚点） | scope | 计划形态 | 用例 ID | disposition |
|---|---|---|---|---|---|
| REQ-001 | §2 图纸四种形态 | in | execution | 065-S01（4 参数实例） | planned |
| REQ-002 | §2 装载校验·依赖环 | in | execution | 065-S03#环 | planned |
| REQ-003 | §2 装载校验·依赖自环 | in | execution | 065-S03#自环 | planned（**补 L2 §1.3 自陈的开发侧缺口 C4**） |
| REQ-004 | §2 装载校验·幽灵引用 | in | execution | 065-S03#幽灵步骤 / #幽灵成员 | planned（**同上补 C4**） |
| REQ-005 | §2 装载校验·形状违规 | in | execution | 065-S03#pipeline带依赖 / #parallel带steps / #single多成员 / #single多步 / #single带依赖 | planned |
| REQ-006 | §2 装载校验·类型不可识别 | in | execution | 065-S03#type | planned |
| REQ-007 | §2 装载校验·成员标识重复 | in | execution | 065-S03#成员重复 | planned |
| REQ-008 | §2 装载校验·成员指令为空 | in | execution | 065-S03#指令空 | planned |
| REQ-009 | §2 条件跳过·非法策略值 | in | execution | 065-S03#skip_if | planned |
| REQ-010 | §2 照图严格执行·执行序 | in | execution | 065-S01 | planned |
| REQ-011 | §2 无改图通道 | in | execution | 065-S15 | planned |
| REQ-012 | §2 绑定缺口入口拒绝 | in | execution | 065-S12 | planned |
| REQ-013 | §2 产物投影·上游产物 | in | execution | 065-S04 | planned |
| REQ-014 | §2 产物投影·原始输入 | in | execution | 065-S04 | planned |
| REQ-015 | §5.1.3 投影四段结构 + 缺席段不出现 | in | execution | 065-S05 | planned（`"   "` 子形态 `partial`，挂 Q065-D1） |
| REQ-016 | §2 条件跳过·触发 | in | execution | 065-S06 | planned |
| REQ-017 | §5.1.4 无上游不跳过 | in | execution | 065-S07 | planned |
| REQ-018 | §5.1.4 链式传播边界 | in | execution | 065-S08 | planned（**补 L2 §1.3 自陈缺口 C5 的一部分**） |
| REQ-019 | §2 每步留痕·字段 | in | execution | 065-S09 | planned |
| REQ-020 | §2 每步留痕·登记序 | in | execution | 065-S10 | planned（**补 L2 §1.3 自陈缺口 C5「流水线第二步留痕无直接断言」**） |
| REQ-021 | §2 成员消息暂存与投影 | in | execution | 065-S11 | planned |
| REQ-022 | §5.1.2 层内并行 | in | execution | 065-S02 | planned（**补 L2 §1.3 自陈缺口 C5「DAG 同层并行性无直接断言」**） |
| REQ-023 | §5.1.2 失败即终止 | in | execution | 065-S13 | planned（留痕条数子断言 `partial`，挂 Q065-D3） |
| REQ-024 | §5.1.6 成员异常包装 | in | execution | 065-S14 | planned |
| REQ-025 | §2 第二后端·可编译（SHOULD） | in | execution | 065-S16 | planned（gating 归属待 Q065-D5 裁决） |
| REQ-026 | §2 第二后端·必须拒绝 | in | execution | 065-S17 | planned（同上） |
| REQ-027 | §2 结果收口 | in | execution | 065-S18 | planned（`"   "` 子形态 `partial`，挂 Q065-D1/DV065-03） |
| REQ-028 | §3 图纸 YAML 契约 | in | execution | 065-S19 + 065-S03 | planned |
| REQ-029 | §3 留痕载体归属与跨执行累积 | in | execution | 065-S23 | planned（与 FEAT-067 REQ-011 共担，主证在 067-S07） |
| REQ-030 | §3 并行度装配参数 | in | execution | 065-S20 | planned |
| REQ-031 | §5.1.7 留痕查询含跳过标记 | in | execution | 065-S23 | planned（与 FEAT-067 共担） |
| REQ-032 | §5.1.1 执行层无专用分支 | in | **static** | 065-S21 | planned（设计本来要求结构性审查 ⇒ 静态可证明能力） |
| REQ-033 | §5.1.3 原始输入拼入形态由绑定决定 | in | execution | 065-S04 | planned（只断言"两位都送达"，**不断言拼接格式**） |
| REQ-034 | §5.1.5 留痕在层汇合后登记 | in | execution | 065-S10 | planned |
| REQ-035 | §2 OUT 自由谓词跳过语言 | out | static | — | out-of-scope（承接方：Feature §2 OUT 条款，范围卡 §4.1） |
| REQ-036 | §2 OUT 断点恢复与跨进程持久化 | out | static | — | out-of-scope（同上） |
| REQ-037 | §2 OUT HITL 断点开箱即用 | out | static | — | out-of-scope（同上） |
| REQ-038 | §2 OUT core 仓改动 | out | static | — | out-of-scope（同上） |

<!-- coverage:scenarios:start -->

| 用例 ID | 场景 | 计划验证方式 |
|---|---|---|
| 065-S01 | 四形态执行序（pipeline/dag/parallel/single 四参数实例） | execution |
| 065-S02 | 层内并行真实性（闩锁构造） | execution |
| 065-S03 | 装载违规全集（按 L2 §2.1 唯一权威清单 12 项逐项对齐；错误消息按分族断言） | execution |
| 065-S04 | 投影：上游产物 + 原始输入两个输入位都送达 | execution |
| 065-S05 | 投影四段结构与缺席段不出现 | execution |
| 065-S06 | 跳过触发：全部已登记上游缺席 | execution |
| 065-S07 | 无上游的步永不因策略跳过 | execution |
| 065-S08 | 链式跳过 vs 默认档下游照常执行 | execution |
| 065-S09 | 每步留痕字段完整（含跳过步与原因） | execution |
| 065-S10 | 留痕登记序 = 层序 × 声明序，跳过步先于同层执行步 | execution |
| 065-S11 | 成员留言暂存、下一步投影携带、取走即清 | execution |
| 065-S12 | 成员绑定缺口在执行入口拒绝并列出缺口成员 | execution |
| 065-S13 | 任一步失败即终止：错误定位 + 留痕条数精确 Oracle | execution |
| 065-S14 | 成员执行体异常包装为含定位的执行错误 | execution |
| 065-S15 | 模型无改图通道：执行体入参只有两个文本位 | execution |
| 065-S16 | 第二后端：pipeline/single 编译为串行组件图 | execution |
| 065-S17 | 第二后端：dag/parallel/cross_validation 被拒并指引回主后端 | execution |
| 065-S18 | 收口三层规则：全空白保留/单份原文替换/多份分节替换/跨层空白传递 | execution |
| 065-S19 | `input`/`output` 键装载器不消费（给/不给行为一致） | execution |
| 065-S20 | 并行度构造期给定、运行期不可变 | execution |
| 065-S21 | 执行层无 `cross_validation` 专用分支 | static |
| 065-S22 | 真实 LLM 成员代表路径，与 L1 结构对照 | execution |
| 065-S23 | 留痕跨多次执行累积可查（宿主载体） | execution |
| 065-S24 | 编排留痕的会话维度（横切层 DFX-001:63） | execution |
| 065-S25 | 执行树锚点与标识职责分离（横切层 DFX-001:75/76） | execution |
| 065-S26 | 留痕按会话分区的**组件层**判据：两个不同 owner 键下，一方经 roots 与逐个 branch_id 下钻都取不到另一方的执行根与提问原文（D1 的 L1t 证据；实现类位于 orchestration 包、@Tag("feat-067")，但验的是本特性锚定的 REQ-060） | execution |

<!-- coverage:scenarios:end -->

### 4.1 未承接与降级条目（**2026-10-15 21:20 按新基线 `9ab89fc8` / `f9da5b54f` 更新**）

| 缺口 ID | 条目 | 原形态 | **现状态** | 依据 / 恢复条件 |
|---|---|---|---|---|
| GAP-065-01 | REQ-015/027 的 `"   "`（仅空白字符）子形态 | `partial` | ✅ **已关闭** | Feature §5.1.3 新增「**产物缺席的统一判据**：null、空串或仅含空白字符(isBlank)即缺席；成员指令为空的判定同此」；§2 结果收口改为「步骤产物**缺席(空白,判据见 §5.1.3)**不覆盖既有收口产物」⇒ `"   "` 解禁构造，期望为"算缺席"。关联 ISSUE #31 D1 |
| GAP-065-02 | REQ-023 的留痕条数子断言 | `partial` | ✅ **已关闭 → 升为 gating** | Feature §5.1.2 新增「**失败步自身不登记留痕**，不再登记后续留痕(同层已在跑的步不取消、其结果弃收)；**执行以异常终止时不返回执行结果**」——与测试侧读码核实的事实逐字一致 ⇒ 065-S13 的留痕条数断言（= 失败步之前的步数，含跳过步）与"不返回 `BlueprintResult`"断言均升为 gating。关联 ISSUE #31 D3 |
| GAP-065-03 | REQ-002~009 未列举形态（`steps: []`、缺顶层 `blueprint`、空 YAML、`single` 零成员） | `unassigned` | ✅ **已关闭** | L2 §2.1 立「**装载违规唯一权威清单(12 项，Feature §2 与本节以此为唯一口径)**」并编号 ①–⑫，其中含「顶层 blueprint 映射缺失」「图纸 id 空」「步骤标识重复」「pipeline 带 depends_on **或空步骤**／dag **空步骤**」⇒ 原先"未列举"的形态现全部有权威口径。065-S03 改为**按该 12 项清单逐项对齐**（不再用测试侧推定的并集）。关联 ISSUE #31 D2/V3 |
| GAP-065-04 | REQ-002~009 的"错误消息含图纸 id"子断言 | `partial` | ✅ **已关闭 → 升为 gating** | Feature §2 写入分族口径（形状族与交叉验证编译族含图纸 id；解析层/顶层校验族只含定位，并注明"id 缺失族本身无 id 可用"）；**L2 §7.3 的自相矛盾已修**为「消息**分族携带图纸标识(与 §2.1 同口径)**」；FEAT-067 §5.1.5 亦改为「与 FEAT-065 §2 同口径」⇒ 三处收敛，可按族分别做完整断言。关联 ISSUE #31 V1 |
| GAP-065-05 | REQ-025/026 的 gating 归属 | `planned`（归属待定） | ✅ **已关闭** | Feature §2 裁决为「第二后端**本体为可选能力**；若提供，则…**拒绝义务在提供该后端时为 MUST**」⇒ 本期该后端**已提供**，故 065-S16/S17 均为 **gating**。关联 ISSUE #31 D4 |
| GAP-065-06 | 065-S22（L2 真实成员）与 065-S23 | `blocked` | ⛔ **仍 blocked（环境类，按用户指示统一到最后解决）** | **制品已就绪**（`agent-core-ext-orchestration` 与 `deepanalyze-engine` 的 `-r2` 版本均已构建并核验指纹）；**真机门控变量名已由 L2 §1.3 明确为两套并存**（本模块 `OPENJIUWEN_API_KEY`/`OPENJIUWEN_BASE_URL`；宿主 engine 用 `LLM_API_KEY`/`DA_MODEL_*`，"跑哪侧配哪侧"）⇒ 原 `L065-04` 已答。剩余前置只有环境类：`~/.llmrc` absent + Docker Hub 不可达致无 redis 镜像（ENV-1.5） |

**汇总**：原 6 条缺口 → **5 条关闭**（01/02/03/04/05）、**1 条仍 blocked 于环境**（06）。
**本期 FEAT-065 的 L1 组件层 21 个场景已无任何设计侧阻塞**，只等 Java 实现授权。

## 5. 端到端场景

**L1 主旅程（组件层，覆盖 REQ-001~034 的绝大部分）**：
测试作为装配方 → 用 YAML 字符串声明图纸 → `BlueprintLoader.load(yaml)` → 得 `BlueprintSpec`（不可变）→
自备 `TraceForest` + 确定性执行体绑定（`Map<String, SubAgentExecutor>`，每个执行体回显**唯一标记串**并可选阻塞/抛异常/返回空白）→
`new BlueprintScheduler(forest, parallelism, messageDrain)` → `execute(spec, bindings, userInput)` →
断言 `BlueprintResult`（`blueprintId` / `outcomes`（`LinkedHashMap`，保序）/ `stepOutcomes()` / `finalOutput`）+ `forest` 的分支与状态 + 执行体实收输入原文 + 执行体调用计数。

**L2 主旅程（端到端，真实成员）**：
`SutStack` 拉起 `deepanalyze`（门控开启）+ Redis → 经标准 A2A 入口发一句自然语言（显式点名模板）→
宿主主 Agent 调 `workflow_run` → 引擎以**真实 LLM 成员**照图执行 → 断言出参 `steps[]` 条数与图纸步数一致、
每步 `member` 与图纸声明一致、`final_output` == 末步 output、`workflow_id` 非空 → **与 L1 同一图纸的执行结果做结构对照**。

**静态旅程**：只读 `BlueprintScheduler.java` 全文，断言不含 `cross_validation` 字面量（REQ-032 / 066 REQ-016）。

## 6. 详细用例

> 每条格式：可执行性/优先级 · 计划形态 · 适用 SUT · 层次 · Oracle 来源 · G/W/T · PASS/FAIL/INCONCLUSIVE · 证据 · 不应断言 · 自动化落点。
> "唯一数据"纪律：所有标记串带用例 ID 前缀（如 `M-065-S04-UP1-<uuid8>`），不依赖方法顺序或其他用例残留状态（RULES「测试证据门禁」第 6 条）。

### 065-S01：四形态执行序（4 参数实例）
- 可执行性/优先级：`runnable` / **P0**（Feature §6 明文要求覆盖"四形态执行序"）
- 计划形态：`execution`；SUT：`agent-core-ext-orchestration`；层次：component
- Oracle 来源：Feature §2「图纸四种形态」+ §5.1.2 调度语义；L2 §3.2/§3.3
- **Given**：四张最小图纸——① `pipeline` 两步（s1→s2）；② `dag` 三步（s1、s2 无前置，s3 依赖二者）；③ `parallel` 三成员（省略 steps）；④ `single` 单成员。每成员绑定回显唯一标记的确定性执行体，`parallelism=4`
- **When**：逐张 `load` + `execute`
- **Then**：① `stepOutcomes()` 序 == [s1, s2]；② == [s1, s2, s3]（s1/s2 同层，按声明序）；③ == 三成员声明序、单层；④ 单步，其产物即 `finalOutput`。四张图纸 `spec.type()` 分别为 PIPELINE/DAG/PARALLEL/SINGLE（装载期可区分）
- **PASS**：四个实例的执行序与 type 全部符合 **FAIL**：任一实例序或 type 不符 **INCONCLUSIVE**：`load` 抛非预期异常或执行体未被调用（先查测试装配）
- 证据：`stepOutcomes()` 的 `stepId` 序列原文 + `spec.type()` + 执行体调用计数
- 不应断言：执行耗时长短、层内并行的时间重叠（归 065-S02）
- 自动化：`component/orchestration/BlueprintFourShapesExecutionOrderTest`，`@Tag("component")` `@Tag("feat-065")`，`@Feature("FEAT-065: 确定性编排层(蓝图调度)")`，`@Story("065-S01: 四形态执行序")`，4 个 `@ParameterizedTest` 实例

### 065-S02：层内并行真实性（闩锁构造）
- 可执行性/优先级：`runnable` / **P0**（补 L2 §1.3 自陈缺口 C5「DAG 同层并行性无直接断言」）
- 计划形态：`execution`；层次：component
- Oracle 来源：Feature §5.1.2「层内并行」+ §2「依赖图(前置就绪即调度、层内并行)」
- **Given**：`dag` 图纸两步无前置；两个执行体各自 `countDownAndAwait(latch, 2, timeout=10s)`（到达即等对方），超时抛异常；`parallelism=2`
- **When**：`execute`
- **Then**：两步都成功完成 ⇒ **必然真的并发过**（若串行执行，第一个执行体会等不到第二个而超时失败）
- **PASS**：两步均非 skipped 且产物含各自标记 **FAIL**：任一步超时或抛异常（即实际串行） **INCONCLUSIVE**：`parallelism` 未按构造值生效（先查装配）
- 证据：两执行体的到达时间戳 + 用例结果；对照组：`parallelism=1` 时同一图纸**必须超时失败**（反例，证明该用例真能拦住串行化故障）
- 不应断言：并行度上限、线程名
- 自动化：`BlueprintLayerParallelismTest`，`@Story("065-S02: 层内并行闩锁证明")`，含 `parallelism=1` 反例

### 065-S03：装载违规全集（11 参数实例）
- 可执行性/优先级：`runnable` / **P0**（Feature §6 明文"装载违规全集"）
- 计划形态：`execution`；层次：component
- Oracle 来源：Feature §2「图纸装载校验」+ §5.1.7 错误表；L2 §2.1；代码 javadoc `BlueprintLoader.java:25-31`（9 项）
- **Given**：11 张违规图纸（取 Feature 7 项与 javadoc 9 项的**并集**，去重后 11 个实例）：依赖环、依赖自环、幽灵步骤引用、幽灵成员引用、pipeline 带 `depends_on`、parallel 带 `steps`、single 多成员、single 多步、single 带 `depends_on`、type 不可识别、成员 id 重复、成员 instruction 为空、`skip_if` 非法（小写/未知值）；每张图纸的成员绑定一个**带调用计数器的执行体**
- **When**：逐张 `load`（并对能装载的形态追加 `execute` 以验证"不产生任何执行"）
- **Then**：每个实例都抛 `BlueprintException`；消息含**违规定位**（步骤/成员标识）；形状族的消息另含**图纸 id**；且执行体调用计数 **== 0**
- **PASS**：11 个实例全部拒绝 + 定位可见 + 计数 0 **FAIL**：任一实例装载成功，或消息无定位，或计数 > 0 **INCONCLUSIVE**：抛出的不是 `BlueprintException`（先核对依赖版本）
- 证据：11 条异常消息**原文** + 每条的执行体计数
- **不应断言**：① 解析层/顶层校验族的消息含图纸 id（`DV065-01` 三方不一致 ⇒ 只按族分别断言，见 GAP-065-04）；② 未列举形态应拒（GAP-065-03）；③ 消息的完整措辞（只断言含定位子串）
- 自动化：`BlueprintLoadViolationCatalogTest`，11 个 `@ParameterizedTest` 实例，`@Story("065-S03: 装载违规全集")`

### 065-S04：投影——上游产物与原始输入两个输入位都送达
- 可执行性/优先级：`runnable` / **P0**
- 计划形态：`execution`；层次：component
- Oracle 来源：Feature §2「产物投影传递」+ §3「成员绑定契约」+ §5.1.3 末条；L2 §2.2
- **Given**：两步 pipeline；s1 执行体返回唯一标记 `M-065-S04-UP1-<uuid8>`；s2 执行体把**收到的 `userInput` 与 `subGoal` 两个入参原文**拼接后返回；`userInput` = 唯一串 `U-065-S04-<uuid8>`
- **When**：`execute`
- **Then**：s2 的返回文本（即 s2 实收输入的镜像）**同时含** `M-065-S04-UP1-…`（上游产物）与 `U-065-S04-…`（原始用户输入）；且 s2 实收的两个入参**都非空**
- **PASS**：两个标记都在 **FAIL**：缺任一 **INCONCLUSIVE**：s2 未被调用
- 证据：s2 实收 `userInput` 与 `subGoal` 的**完整原文**（脱敏后）
- **不应断言**：`[user input]` 段的拼接位置或分隔符（Feature §5.1.3 明写"拼入形态由成员绑定实现决定"，REQ-033 只承诺两位都送达）
- 自动化：`BlueprintProjectionTest#upstreamAndUserInputBothDelivered`

### 065-S05：投影四段结构与缺席段不出现
- 可执行性/优先级：`runnable` / **P1**
- 计划形态：`execution`；层次：component；Oracle：Feature §5.1.3 四段结构；L2 §3.4
- **Given**：三个实例——① dag 两上游汇点（两段上游产物，`---` 分隔）；② 有待读留言（`[pending messages]` 段出现）；③ 上游产物缺席（`null` 与 `""` 两种，**不含 `"   "`**，见 GAP-065-01）
- **When**：`execute`
- **Then**：① 汇点步输入含两个上游标记且以分隔符分开；② 输入含留言段与留言内容；③ 缺席上游**不产生投影段**（输入里不出现空的分隔片段）
- **PASS/FAIL/INCONCLUSIVE**：如各实例 Then；`"   "` 形态不构造（GAP-065-01）
- 证据：汇点步与缺席场景下下游步的实收输入原文
- 不应断言：段的字面标题文本（`[upstream outputs]` 等属实现约定，Feature 只承诺"四段均为纯文本"）
- 自动化：`BlueprintProjectionTest#fourSegmentStructureAndAbsentSegmentOmitted`，3 实例

### 065-S06 / S07 / S08：跳过语义三件
- 可执行性/优先级：`runnable` / **P0**（Feature §6 明文"跳过传播"）；层次：component；Oracle：Feature §2「条件跳过」+ §5.1.4
- **065-S06 Given**：dag，s1 执行体返回 `""`（缺席），s2 依赖 s1 且 `skip_if: DEPS_INCONCLUSIVE` → **Then** s2 `skipped==true`、留痕含跳过原因、s2 执行体计数 0
- **065-S07 Given**：单步图纸、无上游、`skip_if: DEPS_INCONCLUSIVE` → **Then** 该步**照常执行**（`skipped==false`、计数 1），验证 `anyDep` 守卫
- **065-S08 Given**：dag，s1 缺席；s2 依赖 s1 且声明 `DEPS_INCONCLUSIVE`；s3 依赖 s2 且声明 `DEPS_INCONCLUSIVE`；s4 依赖 s1 但为**默认档** → **Then** s2、s3 链式跳过（各留痕含原因）；**s4 照常执行**（计数 1），其输入的上游产物段不出现
- **PASS**：三条全部符合 **FAIL**：s4 被跳过（过度传播）或 s2/s3 未跳过（传播不足）或 s7 场景的无上游步被跳过 **INCONCLUSIVE**：s1 未按预期返回空
- 证据：`stepOutcomes()` 全表的 `stepId`/`skipped`/`output` + 各执行体计数 + 跳过原因原文
- 不应断言：跳过原因的字面格式（L2 §2.4 给的是 `blueprint-skip:<步骤id>`，属实现约定；Feature 只要求"原因可查"）
- 自动化：`BlueprintSkipPropagationTest`，3 个方法

### 065-S09 / S10：留痕字段与登记序
- 可执行性/优先级：`runnable` / **P0**（Feature §6 明文"留痕登记序"；补 L2 §1.3 缺口 C5「流水线第二步留痕无直接断言」）
- 层次：component；Oracle：Feature §2「每步留痕」+ §5.1.5；已核实的保序事实（`outcomes` 为 `LinkedHashMap`，javadoc 自称 parity 判据）
- **065-S09 Given**：dag 含一个被跳过的步 → **Then** 每步（含跳过步）都有留痕，字段齐备：`stepId`、`memberId`、`skipped`、`output`（跳过步为缺席）、`branchId` 非空；森林中跳过步的分支状态与原因可查
- **065-S10 Given**：三层 dag，第二层两步（声明序 sB、sA，故意与字母序相反）+ 一个跳过步在同层 → **Then** `stepOutcomes()` 的 `stepId` 序列严格等于「拓扑层序 × 层内**声明**序」，且**同层跳过步排在执行步之前**；连跑 3 次结果序列完全一致（可重放）
- **PASS**：字段齐备 + 序列精确匹配 + 3 次一致 **FAIL**：序列按完成序而非声明序，或跳过步未先登记，或 3 次不一致 **INCONCLUSIVE**：`stepOutcomes()` 为空
- 证据：`stepOutcomes()` 的完整 `stepId` 序列（3 次各一份）+ 森林分支状态
- 不应断言：`branchId` 的具体格式
- 自动化：`BlueprintTraceLedgerTest`，2 个方法，S10 用 `@RepeatedTest(3)`

### 065-S11：成员留言暂存、投影携带、取走即清
- 可执行性/优先级：`runnable` / **P1**；层次：component；Oracle：Feature §2「成员消息暂存与投影」+ §5.1.3
- **Given**：两次 `execute`（同一 `TraceForest` 与同一 `messageDrain` 装配）；第一次运行前向成员 B 投递留言 `MSG-065-S11-<uuid8>`；B 在第一次运行中是某步的成员
- **When**：第一次 `execute` → 第二次 `execute`
- **Then**：第一次运行中 B 的实收输入含 `[pending messages]` 段与该留言；**第二次运行中 B 的实收输入不再含该留言**（取走即清）
- **PASS**：首次含、二次不含 **FAIL**：二次仍含（未清）或首次不含（未投递） **INCONCLUSIVE**：B 未被调用
- 证据：两次运行 B 的实收输入原文
- 不应断言：留言的来源标识（`from` 字段在宿主侧恒空串，属 FEAT-067 范畴）
- 自动化：`BlueprintMemberMessageTest`

### 065-S12 / S13 / S14：失败与缺口表面
- 可执行性/优先级：`runnable` / **P0**；层次：component；Oracle：Feature §2「照图严格执行」+ §5.1.2 + §5.1.6 + §5.1.7 错误表
- **065-S12 Given**：图纸声明成员 A、B，绑定只给 A → **Then** `execute` 在入口抛 `BlueprintException`，消息**列出缺口成员标识 B**；A 的执行体计数 0（入口即拒，未开始执行）
- **065-S13 Given**：三层 dag，第二层的 s2 执行体抛 `IllegalStateException`，同层 s3 为慢速正常执行体 → **Then** `execute` 抛 `BlueprintException` 且消息含**图纸 id 与失败步骤 id**；**不返回** `BlueprintResult`；森林中留痕条数 == 失败步**之前**的步数（含第一层与同层排在 s2 之前的跳过步），s2 自身与其后步**均无留痕**
- **065-S14 Given**：单步图纸，执行体抛 `RuntimeException("BOOM-065-S14-<uuid8>")` → **Then** 抛 `BlueprintException`，消息含图纸 id、步骤 id 与原始异常信息
- **PASS**：三条的错误载体与定位齐备 **FAIL**：吞异常、缺定位、或返回了部分结果 **INCONCLUSIVE**：抛出的异常类型不符（先核对依赖版本）
- 证据：三条异常消息原文 + 森林分支计数 + 执行体计数
- **不应断言**：同层并行步 s3 的产物去向（"弃收"的设计口径未定，GAP-065-02）；留痕条数断言标 `partial`
- 自动化：`BlueprintFailureSurfaceTest`，3 个方法

### 065-S15：模型无改图通道
- 可执行性/优先级：`runnable` / **P1**；层次：component；Oracle：Feature §2「执行体只收文本输入，模型无任何改图通道」+ §5.1.3「不共享对象、不共享状态」
- **Given**：一个执行体，把收到的两个入参的**运行时类型**与 `getClass().getName()` 记录下来，并尝试在返回文本里嵌入"改图指令"
- **When**：`execute` 两步 pipeline（第一步返回含伪改图指令的文本）
- **Then**：① 两个入参的运行时类型都是 `String`（无任何图纸/spec 对象传入）；② 第二步之后的执行序与留痕仍严格等于图纸声明（伪改图指令**未生效**）
- **PASS**：两项都成立 **FAIL**：入参含非 `String` 的图纸句柄，或执行序被第一步的输出改变 **INCONCLUSIVE**：执行体未被调用
- 证据：入参类型清单 + `stepOutcomes()` 序列
- 不应断言：真实 LLM 是否会尝试改图（L1 用确定性执行体；真实模型形态归 065-S22）
- 自动化：`BlueprintNoGraphMutationChannelTest`

### 065-S16 / S17：第二执行后端
- 可执行性/优先级：`runnable` / **P1**（gating 归属待 `Q065-D5`，GAP-065-05）；层次：component；Oracle：Feature §2「第二执行后端」+ §5.2「第二后端并行」；L2 §2.5/§3.8
- **065-S16 Given**：两步 pipeline 与单兵图纸各一张 → **Then** `BlueprintWorkflowCompiler.compile(spec, bindings)` 成功返回组件图；图内组件数与步数对应（每步一个组件 + Start/End 收口）；**首步**的 query 走 `userInput` 位、**后续步**的上游产物进 `[upstream outputs]` 段（L2 §3.8 的 !805 二轮修复点）
- **065-S17 Given**：dag、parallel、cross_validation 三张图纸 → **Then** `compile` 抛出可识别异常且消息**指引回主后端**（含 `BlueprintScheduler` 字样）；**不产生任何组件图**（即未静默降级为串行）
- **PASS**：S16 编译产物结构正确 + S17 三型全部被拒且指引明确 **FAIL**：任一并行型被编译成串行图（静默降级） **INCONCLUSIVE**：core Workflow 类不可解析（先核对依赖）
- 证据：组件图的组件清单与接线 + 三条拒绝消息原文
- **不应断言**：core Workflow 引擎的执行行为（L2 §1.3 明示"WF 引擎行为由 core 自身测试覆盖"，且 Feature §2 OUT「core 仓改动」）；异常类名（L2 给的是 `IllegalArgumentException`，属实现方式）
- 自动化：`BlueprintSecondBackendTest`，S16 两实例 + S17 三实例

### 065-S18：收口三层规则
- 可执行性/优先级：`runnable` / **P0**；层次：component；Oracle：Feature §2「结果收口」现行三层口径（L45，2026-11-18 评审刷新钉版）+ §5.1.3；原「末个非空白步产物」口径已废止（多份非空白场景旧规则会误判）
- **Given/Then 四格**：
  1. **本层全空白**：当前层产物全部空白 → 收口产物**保留此前已收集结果**（不覆盖、不变空）；
  2. **恰好一份非空白**：收口产物直接以该产物**原文替换**（不添加标题）；
  3. **多份非空白**：按步骤声明序拼接为 `## <步骤id>` 分节并**替换**既有收口结果；
  4. **跨层空白传递**：末层全空白时沿用前层结果（与 1 联动的跨层形态）。
  `outcomes` 各格均含全部步骤（含跳过步）
- **PASS**：四格逐一命中 **FAIL**：任一格偏离（如多份非空白未分节、单份误加标题、全空白误置空）
- 证据：`finalOutput` 原文 + `outcomes` 全表（多份分节格附分节结构对照）
- 不应断言：`"   "`（仅空白）形态细节（GAP-065-01）
- 自动化：`BlueprintFinalOutputFallbackTest`

### 065-S19 / S20：装载契约与装配参数
- 可执行性/优先级：`runnable` / **P2**；层次：component；Oracle：Feature §3「图纸 YAML」+「并行度」
- **065-S19**：同一张 pipeline 图纸的两个变体——带 `input`/`output` 键与不带 → **Then** 两者的 `spec`（type/members/steps）、执行序、`finalOutput` **完全一致**（装载器不消费这两键）
- **065-S20**：同一张 dag 图纸分别以 `parallelism=1` 与 `parallelism=4` 构造调度器 → **Then** `parallelism=1` 时闩锁构造超时失败、`=4` 时通过（证明并行度确实由构造期参数决定且运行期生效）
- 自动化：`BlueprintLoaderContractTest`（S19）、`BlueprintLayerParallelismTest`（S20 复用 S02 的闩锁，参数化 parallelism）

### 065-S21：执行层无 `cross_validation` 专用分支（静态）
- 可执行性/优先级：`runnable` / **P1**；计划形态：**`static`**（设计本来要求结构性审查 ⇒ 静态可证明能力）
- Oracle：Feature §5.1.1「交叉验证形态在装载期编译为依赖图，**执行层无专用分支**」；L2-066 §4「`BlueprintScheduler` 全类无 `cross_validation` 分支（grep 可证）」、§7.1「结构性审查(grep 证据)」
- **Given/When**：只读被测模块源码 `BlueprintScheduler.java` 全文（含注释）
- **Then**：不含 `cross_validation` / `CROSS_VALIDATION` 字面量
- **PASS**：命中 0 **FAIL**：命中 ≥1（说明执行层出现了专用分支，违反 Feature §5.1.1 与 FEAT-066 §6「不得在执行层为交叉验证增加专用分支」）
- 证据：grep 命令原文 + 命中计数 + 被测 commit
- 自动化：`OrchestrationNoSpecialBranchStaticTest`（把源码路径作为测试资源解析，避免硬编码本机路径）

### 065-S22：真实 LLM 成员代表路径（L2 端到端，与 L1 结构对照）
- 可执行性/优先级：**`blocked`**（P1 制品 + P4 凭据/容器 + L065-04 变量名）/ **P0**（这是"真实使用形态"的唯一承接，缺它则代表性门禁不满足）
- 计划形态：`execution`；SUT：`deepanalyze`（门控开启）；层次：e2e
- Oracle 来源：Feature §1 面向角色「Agent 开发者：以图纸声明多 Agent 协作拓扑，**绑定自有执行体**」（真实使用形态是模型成员）；`review-and-design.md`「代表性」
- **Given**：与 065-S01 ② **同一张** dag 图纸（三步：两无前置 + 一汇点），经 `workflow_run` 的 `blueprint` 入参直给；宿主成员工厂用真实模型（温度 0.3 / maxTokens 4000，L2-067 §2.6）；`deepanalyze.workflow.parallelism=4`
- **When**：经标准 A2A 入口发一句话触发主 Agent 调 `workflow_run`
- **Then**：① 出参 `status=completed`；② `steps[]` **条数 == 3**、每步 `member` 与图纸声明一致、`skipped` 全 false；③ `final_output` == 末步 output 且非空；④ **结构对照**：条数/成员/跳过标记与 065-S01 ② 的 L1 结果**逐项一致**（差异只允许出现在 output 内容层）
- **PASS**：四项全部成立 **FAIL**：条数或成员不符（说明真实成员下编排结构变形）、或 `final_output` 与末步 output 不一致 **INCONCLUSIVE**：主 Agent 未调用 `workflow_run`（模型未配合，属 L3 层不确定性，需重试或强化指令语料，**不判产品 FAIL**）
- 证据：Surefire/Failsafe XML + `workflow_run` 出参原文 + L1 对照结果
- **不应断言**：模型输出的内容质量；**不得断言"检索发生了"**（L2-067 §1.3 自陈成员是裸 `ReActAgent`、无工具——"检索员不能检索"）；不断言耗时有界（Feature §5.1.1 明示同步无超时）
- 自动化：`e2e/deepanalyze/DaOrchestrationRealMemberE2EIT`，`@Tag("e2e")` `@Tag("feat-065")`

### 065-S23：留痕跨多次执行累积可查（与 FEAT-067 共担）
- 可执行性/优先级：`blocked`（同 S22）/ **P2**；层次：e2e；主证在 `067-S07`
- Oracle：Feature §3「留痕载体…宿主决定其生命周期」+ §5.1.5「留痕结构归宿主，跨多次执行累积可查」+ §5.1.7「宿主可按执行根查询全部步骤留痕，含跳过标记」
- **Given/When**：同一宿主实例内连续两次 `workflow_run`（不同图纸），随后 `subagent_transcript(branch_id="roots")`
- **Then**：返回**两个**执行根；按各自根下钻能取到该次运行的全部步骤留痕（含跳过标记）
- **PASS/FAIL**：如上；**INCONCLUSIVE**：`branch_id` 无来源（`DV067-03` / ISSUE #33 V3 未修时本场景只能走 `roots` 逐层下钻）
- 自动化：与 067-S07 合并实现，本条只在覆盖台账登记共担关系

## 7. Fixture、环境与确认门禁

| 缺口 | 为什么需要 | 拟改内容 | 影响 | 当前授权状态 |
|---|---|---|---|---|
| acceptance 仓 `pom.xml` 缺 `agent-core-ext-orchestration` 依赖 | L1 全部 21 个场景的前提 | 在 `<dependencies>` 增一条 `com.openjiuwen:agent-core-ext-orchestration`（版本随构建 revision） | 仅测试仓；不动业务代码 | **已授权**（用户 2026-10-15「完授权你修改」） |
| 被测制品未构建 | 同上 + L2 | 由 `common`@`53c1a174a` 源码 `mvn install`（`-Drevision=<X>`），产物落工作区 Maven 本地仓 | 构建动作，需单独授权 | **待授权**（RULES：构建需各自动作所要求的授权） |
| 门控开启 | L2 场景（065-S22/S23）与 FEAT-067 全部 | SUT 启动参数注入 `-Ddeepanalyze.workflow.enabled=true`（或 `application-openjiuwen.yml` 的 `deepanalyze.java.system-properties`） | 仅测试侧 SUT 装配 | **已授权**（范围卡 §7.1 第 4 项） |
| `~/.llmrc` absent | L2 场景 | 用户配置（变量名见 ENV-4.2） | 执行排期，**不改变任何判据**（SKILL.md「环境缺口不得反推 Oracle」） | **只有用户能做** |
| docker / colima 状态矛盾 | L2 场景需 Redis 容器 | 先 `docker context ls` 复核 | 执行排期 | 待复核 |
| 新增测试目录 `cases/component/orchestration/` | L1 落点 | 新建包与 13 个测试类 | 仅测试仓 | 属 Java 实现阶段，**需测试设计细档与正式 TestPlan 双确认后**才动 |

**不修改任何 fixture**：本特性无需改 `agent-solution`（RULES 边界第 2/3 条）——被测模块公开 API 已足够，测试自带执行体是 Feature §3 明文允许的装配方式。

## 8. 自动化落点与精确执行规划

**落点**（acceptance 仓，`src/test/java/com/huawei/ascend/sit/`）：

| 层次 | 包 | 测试类 | 覆盖场景 |
|---|---|---|---|
| component | `cases/component/orchestration/` | `BlueprintFourShapesExecutionOrderTest` | 065-S01 |
| component | 同上 | `BlueprintLayerParallelismTest` | 065-S02、065-S20 |
| component | 同上 | `BlueprintLoadViolationCatalogTest` | 065-S03 |
| component | 同上 | `BlueprintProjectionTest` | 065-S04、065-S05 |
| component | 同上 | `BlueprintSkipPropagationTest` | 065-S06、S07、S08 |
| component | 同上 | `BlueprintTraceLedgerTest` | 065-S09、S10 |
| component | 同上 | `BlueprintMemberMessageTest` | 065-S11 |
| component | 同上 | `BlueprintFailureSurfaceTest` | 065-S12、S13、S14 |
| component | 同上 | `BlueprintNoGraphMutationChannelTest` | 065-S15 |
| component | 同上 | `BlueprintSecondBackendTest` | 065-S16、S17 |
| component | 同上 | `BlueprintFinalOutputFallbackTest` | 065-S18 |
| component | 同上 | `BlueprintLoaderContractTest` | 065-S19 |
| component | 同上 | `OrchestrationNoSpecialBranchStaticTest` | 065-S21 |
| e2e | `cases/e2e/deepanalyze/` | `DaOrchestrationRealMemberE2EIT` | 065-S22、065-S23（与 067-S07 共担） |

Allure 标注纪律（工作区规则）：类级 `@Feature("FEAT-065: 确定性编排层(蓝图调度)")`，方法级 `@Story("<用例 ID>: <简短描述>")`，并按层次打 `@Tag("component")` / `@Tag("e2e")` + `@Tag("feat-065")`。

**精确执行规划**（授权后执行；本轮**不执行**）：

```bash
# 预检（必须先 ready=true）
pwsh -NoProfile -File <skill>/scripts/validate-project.ps1 -Phase execution -OutputPath <batch>/gate-output/validate-project-execution.json
# L1 组件层（无凭据、无容器依赖）
mvn -B -Dmaven.repo.local=<工作区>/04-Environment/m2repo \
    -Dtest='com.huawei.ascend.sit.cases.component.orchestration.*Test' test
# L2 端到端（需凭据 + Redis + 门控开启）
mvn -B -Dmaven.repo.local=<工作区>/04-Environment/m2repo \
    -Dit.test='DaOrchestrationRealMemberE2EIT' verify
# 证据审计
pwsh -NoProfile -File <skill>/scripts/verify-surefire-evidence.ps1 ...
```

## 9. 状态汇总与退出标准

- 当前执行状态 / 用例结论 / ISSUE 状态 / 特性收口状态：链接 `FEAT-065/FEAT-065-动态状态页.md`（manifest 阶段生成）。
- **设计期不填写实际执行数字**；本轮 23 个场景全部 `not-run`，`automation_status: design-only`。
- 四种状态分开记账（RULES「测试状态、结论与收口分层」）：执行状态 `selected/executed/skipped/blocked/not-run`；用例结论 `PASS/FAIL/INCONCLUSIVE`；ISSUE 状态 `已创建/待修复/已回归/已关闭`；特性收口 `open/partial-open/closed`。当前：执行状态全 `not-run`（065-S22/S23 为 `blocked`）；ISSUE #31 `已创建`；特性收口 **`open`**。
- **退出标准**：范围内 34 条 `in` 条目各获得适当形态的实际证据；GAP-065-01~06 全部升级或经用户明确裁决延期；**任何降级、未承接、未验证项仍阻塞验收，确认延期不等于通过**。

## 10. 负结论归因记录

| 结论 ID | 结论 | 候选解释 | 排除依据 | 仍未排除项 | 下一步探针 |
|---|---|---|---|---|---|
| NC-01 | 三个既有 SUT（deepagent/reactagent/workflow）**不适用**于 FEAT-065 | ① 特性归属别的模块；② 能力可由既有 SUT 承载 | Feature §1「FEAT-065 定义 **agent-solution 仓编排引擎模块**」；§2 OUT「core 仓改动」；L2 §1.1「不承接…core 仓任何改动」⇒ 能力归属为独立模块，与三个 SUT 无交集 | 无 | — |
| NC-02 | **不新建 fixture** | ① 既有 SUT 无法承载需新建；② 公开 API 已足够 | Feature §3 给出「成员绑定契约」与「执行入口」两个跨模块契约，`BlueprintLoader`/`BlueprintScheduler`/`BlueprintWorkflowCompiler` 均为 public；测试自带执行体是设计明文允许的装配方式 | 无 | — |
| NC-03 | `DV065-02`（中断标志）**不构成偏差、不提单** | ① Feature 与实现取向相反；② 措辞差异但语义一致 | `BlueprintScheduler.java:184-188` 注释「标志随 `InterruptedException` 上抛，**本层不重设**（re-interrupt 属门禁禁止项）」与 Feature §5.1.6「中断标志不重设，处置权在调用方」一致；Java 语义上 `InterruptedException` 抛出时标志本就被清除 | 调用方能否感知中断（属调用方义务，非引擎可观察行为） | 无（黑盒层无入口，登记为 OUT/non-gating） |
| NC-04 | L2 §1.3「留痕缺输入与耗时字段」**不判 FAIL、不提产品 ISSUE** | ① 违反 Feature §2 每步留痕；② 是 L2 自设的更高标准 | Feature §2 只要求 4 项（步骤标识/成员标识/产物/跳过与否及原因），`StepOutcome` = stepId/memberId/skipped/output/branchId **全覆盖**；"审计四要素"是 L2 自提，Feature 从未承诺输入与耗时 | 无 | — |
| NC-05 | `L067-05`（`parallelism` 非法值致宿主启动失败）**不提单、不 handoff** | ① 产品缺陷；② 合理 fail-fast 但缺可诊断性 | 已读码核实：`DaWorkflowRunner.java:53-55` 抛 `IllegalArgumentException`、`DaProperties` 无 `@Min(1)`、`DaEngineConfiguration.java:183-185` 在 Bean 方法内直接 new ⇒ fail-fast 属合理设计；且**无任何权威文档承诺配置非法值的行为或错误形态** ⇒ ISSUE 四项准入第 1 项「预期明确」不满足 | 若将来某特性承诺"配置面给可读错误"，需重新判定 | 无（留 DFX 观察项于 ISSUE 台账 §3.1） |
| NC-06 | 装载违规的未列举形态（`steps: []` 等）**不断言应拒** | ① 实现会拒所以应断言；② 设计未承诺即不要求 | 门禁「不得由测试侧自行补写产品规则」+「设计本来只要求静态合同的条目不强加执行要求」；实现事实（javadoc 9 项含"PIPELINE/DAG steps 非空"）**不能**回写为设计预期 | 设计是否意图拒绝（已提 ISSUE #31 D2） | 等裁决 |

## 最小可执行例子

按四种不同执行机制各给一个最小例子（同一行为族共用，用例引用即可）。**设计阶段不以此授权实际执行。**

### 例 1：L1 组件层·确定性执行体（代表 065-S01/S03~S20，共 19 个场景）

- **真实输入声明**：图纸 YAML 字符串（内联在测试资源或 `@MethodSource`），例如三步 dag：
  ```yaml
  blueprint:
    id: T-065-S01-dag
    type: dag
    members:
      - {id: m1, instruction: "汇总技术要点"}
      - {id: m2, instruction: "汇总合规要点"}
      - {id: m3, instruction: "合并两路要点"}
    steps:
      - {id: s1, member: m1}
      - {id: s2, member: m2}
      - {id: s3, member: m3, depends_on: [s1, s2]}
  ```
- **拟发请求**：`BlueprintLoader.load(yaml)` → `new BlueprintScheduler(forest, 4, drain)` → `execute(spec, bindings, "U-065-S01-<uuid8>")`；`bindings` 为测试提供的 `Map<String, SubAgentExecutor>`，每个执行体 `(userInput, subGoal) -> "M-065-S01-" + memberId + "-" + uuid + "\n[recv]" + subGoal + "\n[user]" + userInput`
- **入口与必要资源**：入口 = 被测模块公开 API；必要资源 = **仅 P1+P2**（制品构建 + pom 依赖）。**无需**凭据、容器、网络、数据库
- **可观察输出**：`BlueprintResult.blueprintId()` / `outcomes()`（`LinkedHashMap`，保序）/ `stepOutcomes()` / `finalOutput`；各执行体实收的 `userInput` 与 `subGoal` 原文；执行体调用计数；`TraceForest` 分支与状态
- **证据落点**：`target/surefire-reports/TEST-…BlueprintFourShapesExecutionOrderTest.xml` + 用例内 `Allure.addAttachment` 附实收输入原文；运行清单落 `<batch>/evidence/<run-id>/`
- **代表性**：材料来源 = Feature §4「依赖图并行」场景行（三步 DAG，两步无前置 + 一步依赖二者）**原样照搬**；保留的真实使用条件 = 图纸声明形态、成员指令为自然语言、依赖集显式；与目标环境的关键差异 = **成员是测试提供的确定性执行体而非真实模型**（依据 Feature §3 成员绑定契约把执行体交给装配方，且 §1 明写"测试与验收团队：以确定性图纸做可重放的行为判据"）；替代办法 = 065-S22 用真实 LLM 成员跑**同一张图纸**做结构对照
- **故障与主断言**：若引擎把 dag 悄悄压扁成串行 → 065-S02 的闩锁用例必然超时失败；若执行序由完成序而非声明序决定 → 065-S10 的三次重跑序列不一致而失败；若装载校验漏放一类违规 → 065-S03 该实例的"抛 `BlueprintException` + 执行体计数 0"失败

### 例 2：L2 端到端·真实 LLM 成员（代表 065-S22/S23）

- **真实输入声明**：与例 1 **同一张** dag 图纸，经 `workflow_run` 的 `blueprint` 入参直给（不用模板，排除模板解析变量）
- **拟发请求**：经标准 A2A 入口向 `deepanalyze` 发一句自然语言（显式要求调用工作流工具并把图纸全文作为参数），例如「请用 workflow_run 工具执行下面这张图纸，问题是 U-065-S22-<uuid8>：blueprint: …」
- **入口与必要资源**：入口 = `SutStack.agent("deepanalyze")` 拉起宿主进程 + 标准 A2A 端点；必要资源 = P1 制品 + P4（`deepanalyze-engine:<rev>:exec` 源码构建、Redis 容器、模型凭据 `LLM_API_KEY`/`LLM_API_BASE`/`LLM_MODEL`/`LLM_PROVIDER`）+ P5 门控开启
- **可观察输出**：`workflow_run` 出参（`status` / `workflow_id` / `final_output` / `steps[]`）；宿主日志；Failsafe XML
- **证据落点**：`target/failsafe-reports/TEST-…DaOrchestrationRealMemberE2EIT.xml` + 出参原文附件 + `<batch>/evidence/<run-id>/run-metadata.json`
- **代表性**：材料来源 = Feature §1 面向角色第一条「Agent 开发者：以图纸声明多 Agent 协作拓扑，**绑定自有执行体**」⇒ 真实使用形态是模型成员；保留的真实使用条件 = 真实模型、真实宿主装配、真实 A2A 入口、真实 Redis；关键差异 = 由测试显式点名工具以规避"模型不配合"的不确定性（设计未承诺措辞→调用的确定性），因此 `INCONCLUSIVE` 与 `FAIL` 必须分开记
- **故障与主断言**：若真实成员下编排结构变形（步数或成员与图纸不符）→ "条数 == 3 且每步 member 与声明一致"失败；若宿主改了执行语义（违反 Feature §6 第 2 条）→ 与例 1 的 L1 结果结构对照不一致而失败；若收口错位 → "`final_output` == 末步 output"失败

### 例 3：静态合同核对（代表 065-S21）

- **真实输入声明**：被测模块源码文件 `BlueprintScheduler.java`（按 commit 检出，不依赖本机绝对路径）
- **拟发请求**：无运行时请求；测试内解析源码文本并扫描字面量
- **入口与必要资源**：入口 = 测试类读取源码（源码路径由测试资源/系统属性给出，避免硬编码）；必要资源 = 被测源码检出，**无**制品/凭据/容器
- **可观察输出**：`cross_validation` / `CROSS_VALIDATION` 的命中计数与命中行
- **证据落点**：Surefire XML + 用例内附 grep 命令原文与命中计数
- **代表性**：设计**本来要求**静态验证（L2-066 §7.1「结构性审查(grep 证据)」），故静态即充分，不属降级
- **故障与主断言**：若执行层为交叉验证加了专用分支 → 命中计数 ≥1，主断言失败

### 例 4：失败注入（代表 065-S13/S14）

- **真实输入声明**：三层 dag，第二层某步的执行体抛 `IllegalStateException("BOOM-065-S13-<uuid8>")`，同层另一步为慢速正常执行体（`sleep 500ms`）
- **拟发请求**：`execute(spec, bindings, userInput)`
- **入口与必要资源**：同例 1（仅 P1+P2）
- **可观察输出**：抛出的 `BlueprintException` 消息原文；`forest` 的分支计数；各执行体调用计数；是否返回 `BlueprintResult`
- **证据落点**：Surefire XML + 异常消息原文附件
- **代表性**：材料来源 = Feature §5.1.2「任何一步失败，执行即终止收口…同层已在跑的步不取消、其结果弃收」；保留的真实条件 = 真实并发下的失败（不是串行模拟）；关键差异 = 失败由测试执行体注入而非真实模型故障，但 Feature 承诺的是**引擎对成员异常的反应**，注入方式不影响判据
- **故障与主断言**：若引擎吞掉异常继续执行 → "抛 `BlueprintException` 且含图纸 id + 步骤 id"失败；若失败步之后仍登记留痕 → "留痕条数 == 失败步之前的步数"失败（该子断言当前标 `partial`，GAP-065-02）

## 两轮自审记录

**第一轮（真相源、范围、责任边界、黑盒性、Oracle）**

- 真相源：全部预期取自 `01_MainCode/docs` @ `561f02dd` 的 Feature 与 L2；**未用** `02_ForkCode/docs` 副本；代码事实只用于"已核实的实现口径"与静态断言，**未回写为设计预期**（6 处明确标注，见 NC-06 与 §1）✓
- 范围：34 条 `in` 全部有用例承接；4 条 `out` 有承接方（范围卡 §4.1）；未扩展为完整回归套件（范围卡 §3 = 0 项）✓
- 责任边界：不改业务代码、不修产品 bug、不改 fixture；唯一改动是测试仓 pom 依赖 + 新建测试包（均已授权或在 §7 登记）✓
- 黑盒性：只消费公开 API；L1 的执行体是 Feature §3 明文允许的装配方式，非 Mock LLM（用户已确认）；L2 用真实模型 ✓
- Oracle：23 个场景逐条给出 PASS/FAIL/INCONCLUSIVE；`partial`/`blocked`/`unassigned` 均登记缺口与 `resume_when`；未把未承诺项当断言（6 处"不应断言"）✓

**第二轮（需求处置、G/W/T、SUT 适用性、证据、状态、自动化落点）**

- 需求处置：38 条逐条有 `disposition`；`unassigned` 1 组（GAP-065-03）有负责人与恢复条件 ✓
- G/W/T：23 个场景全部具备；参数化实例数明确（S01 四实例、S03 十一实例、S05 三实例、S16/S17 五实例）✓
- SUT 适用性：§2 给出三行结论与依据，含"为什么不适用三个既有 SUT"（NC-01）✓
- 证据：每场景列出证据落点；执行级证据一律为本轮 Surefire/Failsafe XML，**不采信开发的"离线账本"**（L2 §1.3 自陈真机 e2e CI 不跑）✓
- 状态：四种状态分开；设计期不填执行数字；特性收口 `open` ✓
- 自动化落点：14 个测试类 + 包路径 + Allure 标注 + 精确执行命令 ✓

**反向挑战一个关键假设**（高风险变更要求）：
- **假设**：L1 用确定性执行体足以证明编排语义，L2 只需 1 个对照场景。
- **条件变化及依据**：把"成员执行体的输入来源"从**测试直接给定**换成**真实上游模型产出**（依据：Feature §1 面向角色第一条的真实使用形态是模型成员；`review-and-design.md`「代表性」明写"真实上游产物也可能只代表简单用法"、要检查"输入来源、执行方式与外部依赖"）。
- **检查结果**：**发现一处遗漏并已补**。真实模型产出是**非确定性长文本**，可能包含分隔符 `---`、`[user input]` 之类的字面量、或超长内容——这些会干扰"投影段结构"与"原文投影不摘要"的判定，而 L1 的短标记串永远碰不到。
  ⇒ 已在 065-S22 增加断言"结构对照逐项一致"，并在 §6 的"不应断言"里限定 L1 才做段结构细断言；同时在 FEAT-066 的审计汇聚用例（066-S06）要求标记串足够独特以排除巧合命中。
- **不再扩展**：本轮只挑战这一项最关键假设，未发现其他新的具体风险，按规则不新增自审轮次、不把一次挑战解释为已穷尽未知问题。

---

## 11. 新基线更正（**权威**；与 §1/§4/§6 冲突时以本节为准）

> 触发：docs `561f02dd` → **`9ab89fc8`**、agent-solution `53c1a174a` → **`f9da5b54f`**（2026-10-15 19:21/19:29），
> 开发与文档侧已按 ISSUE #31/#32/#33 逐条修订。本节把 §6 各用例中**已作废或需升级**的期望值集中更正，
> 避免实现 Java 时读到旧文。§6 原文按 RULES 保留不改写（旧基线下的审计痕迹）。
> 完整依据见 `../03-增量审视与基线刷新记录-20261015.md`。
> **本节更新后，三份设计里已无"待核实/待裁决"类的设计侧阻塞；剩余阻塞全部是环境类（凭据 / redis 镜像），按用户 2026-10-15 指示统一到最后解决。**

### 11.1 §1「未决问题」表的现状（7 项 → 0 项设计侧未决）

| 原未决项 | 现状态 |
|---|---|
| `Q065-D1` 空/空白判据 | ✅ **已答**：Feature §5.1.3 新增「产物缺席的统一判据：null、空串或仅含空白字符(**isBlank**)即缺席；成员指令为空的判定同此」 |
| `Q065-D3` 失败时"弃收"可观测判据 | ✅ **已答**：Feature §5.1.2 新增「**失败步自身不登记留痕**…**执行以异常终止时不返回执行结果**」 |
| `Q065-D4` 违规全集边界 | ✅ **已答**：L2 §2.1 立「装载违规**唯一权威清单(12 项)**，Feature §2 与本节以此为唯一口径」，编号 ①–⑫（2026-10-19 裁决把 `skip_if` 非法升为第 **⑪** 项、原 ⑪「形状违规」顺延为 **⑫**；docs@1c0f4594 已落地） |
| `Q065-D5` 第二后端 gating 归属 | ✅ **已答**：Feature §2「第二后端**本体为可选能力**；若提供，则…**拒绝义务在提供该后端时为 MUST**」 |
| `DV065-01` 装载错误是否含图纸 id | ✅ **已答**：Feature §2 写入分族口径；**L2 §7.3 的自相矛盾已修**为「消息**分族携带图纸标识(与 §2.1 同口径)**」；FEAT-067 §5.1.5 同步 |
| `L065-04` 真机门控变量名是否两套 | ✅ **已答**：L2 §1.3「门控变量**两套并存(如实声明)**：本模块用 `OPENJIUWEN_API_KEY`/`OPENJIUWEN_BASE_URL`；宿主 engine 的真机 e2e 用 `LLM_API_KEY`/`DA_MODEL_BASE_URL`/`DA_MODEL_NAME`/`DA_MODEL_PROVIDER`——**跑哪侧配哪侧**」 |
| 制品构建 / pom 依赖 | ✅ **已就绪**：`agent-core-ext-orchestration:0.1.3-feat065067-r2`（37932 B，sha256 前32 `03d6537c32532ab03f8d6e7e942957f0`）已构建并核验；pom 依赖改动已获授权 |

### 11.2 §6 各用例的期望值更正

| 用例 | §6 原文（旧基线） | **更正为（新基线，权威）** |
|---|---|---|
| **065-S03** | "按 Feature 7 项与 javadoc 9 项的**并集 11 项**逐项参数化"；"不按 §7.3 的『全部含图纸 id』写断言"；错误消息"含图纸 id"标 `partial` | 按 **L2 §2.1 唯一权威清单的 12 项**逐项对齐（编号 ①–⑫：顶层 blueprint 缺失／图纸 id 空／类型不可识别／成员标识重复／成员指令为空／步骤标识重复／引用未声明成员／引用未声明步骤(含 DAG 幽灵步骤)／依赖自环／依赖环／**非法跳过策略值(skip_if 非 NEVER/DEPS_INCONCLUSIVE，含小写)**／形状违规——含 pipeline 空步骤、dag 空步骤、cross_validation 带 steps 或 review 非法）。错误消息**按分族做完整断言**（形状族与交叉验证编译族**含图纸 id**；解析层/顶层校验族**只含定位**），**不再是 `partial`**。`BlueprintLoaderTest` 已由 11 例扩到 **13 例**（开发补了幽灵步骤/自环专属断言），SIT 仍须自产证据、不复用 |
| **065-S05 / S18** | `"   "`（仅空白）形态**不构造**，标 `partial` | **解禁构造**：`"   "` 应被判为**缺席**（不产生投影段 / 触发 `DEPS_INCONCLUSIVE` / 不覆盖收口产物）。判据 = Feature §5.1.3 的 `isBlank` 统一口径 |
| **065-S13** | 留痕条数断言标 `partial`（"弃收"口径未定） | **升为 gating**：断言 ①抛 `BlueprintException` 含图纸 id + 步骤 id；②**不返回** `BlueprintResult`；③森林留痕条数 == **失败步之前**的步数（含跳过步），失败步自身与其后步**均无留痕** |
| **065-S16 / S17** | gating 归属待裁决（SHOULD vs §6"必须覆盖"），台账标注归属待定 | **均为 gating**：本期该后端已提供 ⇒ 按 Feature §2 新口径"拒绝义务在提供该后端时为 **MUST**"。S17 断言 dag/parallel/cross_validation 三型全部被拒且消息指引回 `BlueprintScheduler`、**不产生任何组件图** |
| **065-S20** | `parallelism` 非法值处置"待开发确认" | **已答**：L2 §6「≥1，**非法值构造期拒绝(`IllegalArgumentException`)**，运行期不可变」⇒ 可加一条断言：`new BlueprintScheduler(forest, 0, drain)` 抛 `IllegalArgumentException` |
| **065-S22 / S23** | `blocked`，原因含"制品未构建 + 真机门控变量名未确认" | 制品与变量名两项**已解除**；仍 `blocked` 的**只剩环境类**：`~/.llmrc` absent + Docker Hub 不可达致无 redis 镜像（ENV-1.5）。按用户指示**统一到最后解决**，不再单独追 |

### 11.3 缺口状态（与 §4.1 一致，此处为速查）

GAP-065-01/02/03/04/05 **全部关闭**；GAP-065-06 仍 `blocked`（纯环境）。
⇒ **FEAT-065 的 21 个 L1 场景已无任何设计侧阻塞**，可在获 Java 实现授权后立即落地。


### 11.4 2026-10-21 三轮追评裁决落地（**权威**；与 §1/§4/§4.1/§6/§11.1~11.3 冲突时**以本小节为准**）

> 触发：docs `1c0f4594` → **`b4f45c06`**（!343，提交 `3ccbad5d`）；责任人 2026-10-21 09:47 在 `docs#31`/`#32`/`#33` 各答一条
> （comment `194890332` / `194890340` / `194890365`；原文固化存证 `../issues/rulings/20261021-三轮追评答复原文.md`）。
> **制品未变**（仍 `0.1.3-feat065067-r3` / `common@ce9019d86`）⇒ run-48/49 的 XML 结论不作废；
> 但冻结 run-spec 的文档哈希已过期 ⇒ 下一轮矩阵须重冻到 `b4f45c06`。
> **当前权威数字**：目录 **61** 条（in 55 / out 6，本轮**无新增条目**）；场景 **26** 个；
> 覆盖台账 **61 行 = 52 covered / 2 degraded / 7 out-of-scope**；开放缺口 **2** 个。

#### (1) REQ-061（065-S25）改判 `out-of-scope`：**义务不在本特性**

`docs#31` 裁决 2 原文要点：`workflow_id` = SSE **订阅键**（**不再赋予执行树语义**）；`branch_id`(`r<n>/b-<n>`) = **留痕寻址键**
（`r<n>` 即运行级区分、可作 `run_id` 的映射来源）；**`run_id`/`parent_run_id` 执行树的 DFX-001:75/76 MUST 义务主体
裁给「宿主(deepanalyze-java) + core runtime 的 Task 生命周期面」**——「这是跨特性的运行时标识体系，
**不属于 FEAT-065 编排特性单方可承诺的范围**」，并明确「**义务不在本特性**，REQ-061 的判据应写成
『065 提供 `r<n>` 运行编号与 `branch_id` 形态』而非『065 生成 run_id/parent_run_id 执行树』」；
执行树锚点建议经 DFX-001 的 scope 登记**单独立项**（可与 1130 候选同队列）。

⇒ **065-S25 不再按原设计实现**（原判据要断"执行标识带父标识、可还原执行树、三种标识不互相替代"，**义务主体已判不在本特性**）。
台账 REQ-061 已由 `degraded` 改判 **`out-of-scope`**，承接方＝宿主 + core runtime 的 Task 生命周期面 / DFX-001 scope 登记；
`scope_decision` 绑裁决存证件。原 gap 里记的「`ensureRoot` 复用首根 ⇒ 多运行共用一根、根载荷永远是第一次的 userInput」
**已被责任人主动更正**（`RunTranscripts`/`0f9d51173` 改为每 run 独立森林 + owner 分区；其 10-16 旧裁决 E2/E8 以新代码为准作废）。

#### (2) 🔴 但"065 提供 `r<n>` 运行编号与 `branch_id` 形态"这条**窄判据属范围内**——由 FEAT-067 独家承接

裁决把窄判据留给了本特性，而它的**被测代码在 FEAT-067 的工具出参面**（`WorkflowRunTool` → `OutputArchive:87`），
本特性侧只有 `RunTranscripts`/`TraceForest` 的登记语义。用户 2026-10-21 定案（§7AW.2）：
**不在 FEAT-065 另立条目/场景**，由 FEAT-067 的 **REQ-010/011/043（承接用例 067-S07）独家承接**——
两边各立一行会让 **D8 这一个缺陷记两次**、分母虚增、修好后要改判两处。
**该窄判据当前实测不通过**：067-S07 已按裁决 1 改写判据（断形态匹配 `r\d+/b-\d+` + 两次运行 4 步互不相同），
run-54 复跑仍 FAIL，实收 `[b-2, b-3, b-4]`（无前缀、4 步只 3 个不同值），根因 `OutputArchive:87` 未调 `RunTranscripts.Run.qualify()`
⇒ 定性 **(a) 文档已承诺、制品未做到**，归 `agent-solution#457`（D8）。详见 FEAT-067 设计细档 §11.4(4)。

#### (3) REQ-060（065-S24 / 065-S26）**依据文字改写，`covered` 判定不变**

`docs#33` 裁 DFX-001:69 范围外时明写「**`:63`/`:67`/`:68` 同族 MUST 一并按同口径标注适用域**」
（DFX-001 的适用域＝agentteams 托管场景）。⇒ 本行验收依据由「DFX-001:63 这条 MUST 直接约束 DA 宿主」改写为
「**DA 宿主侧的会话维度隔离由 `DaOrchestrationScope` 的 owner 分区承接**」——即 **D1（`agent-solution#458`）所修的那条产品属性**，
已双层复验通过（run-41 PA 端到端 PASS + `065-S26` 组件层 PASS，run-54 再 PASS）。
⚠️ **不得把"适用域改了"读成"D1 不算缺陷"**：D1 是实测复现过的 🔴 安全缺陷、已修已验已关单，与适用域无关。**判定维持 `covered`。**

#### (4) REQ-029 / REQ-031（065-S23）维持 `degraded`，但**恢复条件已有时间窗**

`docs#32` 答复：FEAT-058 的 **solution 侧未落地属实**（我方取证被书面确认），058 solution 分支（9 文件）已备好并通过门禁，
**按既定计划在 core 0.1.17 制品发布后一周内提 PR**；在此之前 `deepanalyze.model.timeout.*` 维持死配置、成员继承 60 秒。
责任人并**书面确认我方处置正确**（「066-S16 的 `HttpTimeoutException` 归因成立，REQ-031/033 保持 degraded 的处置正确」）。
⇒ `resume_when` 改指 `agent-solution#461` 的 PR 合入；**测试侧仍无绕行手段**（`buildMemberAgent` 是 private static）。

⚠️ **我方一处取证误读已更正**：此前写「core 侧三个新类均不存在 ⇒ 配置面有了、执行机构没有」——
实际 L2-058 **§4.1 类表与附录 B 勘误不一致**（看门狗关流经容器 E2E 实证对死链无效，已重构为**空闲有界流**
`IdleBoundInputStream`/`IdleGuardedBodyReader`；`IdleTimeoutWatchdog` 及三个 SSE 迭代器的 arm/disarm/readLineGuarded **全部删除**）。
⇒ 更正为「`ModelTimeoutResolver`/`TimeoutPhase` **在场**，看门狗已按附录 B 替换」。**教训**：读设计文档必须连附录/勘误一起读，
正文与附录冲突时以附录（后写的勘误）为准。**D5/D6 缺陷本体不受影响。**

#### (5) fixture 两项降级变量已撤销（影响 065-S22 / S23 / S24 的端到端代表性）

① `checkpointer.type: in_memory` **已撤**——D4（`agent-core-java#235`）已修（`CheckpointerFactory` SPI 发现改用类自身类加载器，
随 core `!400`/合并点 `14516e4e8` 落地；我方 `git merge-base --is-ancestor 14516e4e8 f7b76133` → **真** 独立核实），
**run-53 容器实证通过**（宿主以默认 redis 起成功、`Unsupported checkpointer` 0 命中、🔒 正向对照＝redis 落下本轮真实键、`DBSIZE=2`）
⇒ **原登记的"不得读成默认 redis 配置下也能起"这条代表性偏差作废**；
② `deepanalyze.model.timeout.stream-idle-seconds: 1800` **已移除**（死键；且 FEAT-058 solution 侧接线后越界值会**启动即拒**）。
**现存唯一已登记偏差只剩 `max-iterations: 4`**（生产默认 15），`#461` 落地后撤回并重跑。

#### (6) `065-S22` 的现状（台账里**无承接行**，属设计定位而非漏测）

`065-S22`（真实 LLM 成员代表路径，与 L1 结构对照）与 FEAT-067 的 `067-S14` **共用同一 testcase**
（`DaOrchestrationRealMemberE2EIT#realMemberRunMatchesL1Structure`）。r2 三轮 PASS（run-17/18/39）；
**r3 复跑被 `agent-solution#462` 阻断**（断言停在取工具出参、五组实质判据一条未执行）⇒ 归因只到 INCONCLUSIVE。
设计上它作"**真实使用形态对照**"（TestPlan :186、本细档 :468：「替代办法 = 065-S22 用真实 LLM 成员跑同一张图纸做结构对照」），
**不单独进覆盖分母**；该定位与阻塞状态已书面记在状态页 §9.1。
