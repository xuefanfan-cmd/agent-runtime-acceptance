---
title: FEAT-066 交叉验证与汇总审计 测试设计初稿
feature_id: FEAT-066
document_type: test-design
status: draft
automation_status: design-only
feature_source: develop/02-features/FEAT-066-cross-validation-audit.md @561f02dd sha256=8a3bb395f8cb7cc2f7fd80c9554be3c6816034b43ac62b1a19bd38cfb5ab4693
l2_source: develop/03-architecture/L2-Low-Level-Design/deepanalyze/Feat-Func-066-cross-validation-audit.md @561f02dd sha256=30440f34dba003ad469f699ea5bc25c6f13ef132875ace87ae5e557762fad812
requirement_catalog: FEAT-066/FEAT-066-需求条目目录.json（24 条：in 19 / out 5；execution 18 / static 6）
requirement_preview: ../02-需求与测试预览-FEAT-065-067.md
change_scope: ../01-变更包范围卡-FEAT-065-067.md
input_maturity: merged
updated: 2026-10-15
---

# FEAT-066 交叉验证与汇总审计 测试设计初稿

> **⚠️ 基线已刷新（2026-10-15 19:21/19:29）——本文件的部分期望值已作废。**
> docs 由 `561f02dd` → `9ab89fc8`、agent-solution 由 `53c1a174a` → `f9da5b54f`，开发与文档侧已按 ISSUE #31/#32/#33 逐条修订。
> **实现 Java 前必须先读** `/Users/zhangjiusi/资料/06_工作/Test_work/01-Design_File/20261015-feat-065-067-orchestration/03-增量审视与基线刷新记录-20261015.md` **§4**，
> 其中列明了本文件哪些用例的期望值必须更正（例如 FEAT-066 的 `066-S15` 主断言已**完全反转**：由"断言静默降级"改为"断言装载期拒绝"）。
> 本文件按 RULES 保留原样作为旧基线下的审计痕迹，不回头改写。


> 测试设计细档，非正式 TestPlan。动态结果只链接覆盖台账。
> **本特性零新增顶层类与公共 API**（L2 §4）——全部落在 `BlueprintLoader` 的编译区段与既有执行面 ⇒
> 测试入口与 FEAT-065 完全相同（`BlueprintLoader.load` / `BlueprintScheduler.execute`），
> 因此本文件只写 FEAT-066 **特有**的判据，与 FEAT-065 共用的机制（投影四段、留痕登记序、收口、失败终止）
> 直接引用 `FEAT-065/FEAT-065-测试设计初稿.md` 的对应场景，不重复设计。

## 0. 用户必读：需求与测试方案

- **一句话说明**：同一个问题让多个 Agent 从不同视角**独立**作答，可选互相挑错一轮，最后由审计员看过**全体原文**再下结论——用机制保证可信度，而不是靠"再写一个更努力的提示词"。
- **关键链路**：`cross_validation` 图纸（视角成员列表 + `review` 开关 + 审计成员）→ **装载期**编译成一张普通依赖图（视角层 → 互审层 → 审计层）→ 之后执行语义与一张手写等价 DAG **完全一致**（执行层零专用分支）→ 审计员输入的 `[upstream outputs]` 段含全体视角与互审产物**原文** → 最终产物 = 审计步产物。
- **本期测试**：16 个场景——**L1 组件层 13 个**（编译产物结构、互审排除自身、审计汇聚双标记/六标记、互审关闭变体、装载拒绝、与手写 DAG 等价、视角缺席、`review` 静默降级）、**L2 端到端 1 个**（真实 LLM 成员的三视角六标记，**补开发自陈缺口**）、**静态 1 个**（执行层零专用分支）、**共担 1 个**（留痕）。
- **本期不测**：多轮互审、程序化审计判定、投票/打分聚合、审计员判定质量、跨图纸复用（Feature §2 三项 OUT + §5.2 两项）。**特别地：不得因审计结论质量差判产品 FAIL**（Feature §5.2 明文不承诺）。
- **未决影响**：`DV066-01`/`DV066-02`（碰撞守卫的触发条件与判定机制**两个维度都与实现相反**）⇒ **2 种图纸形态不构造用例**，登记 `unassigned`；`DV066-03`（`review` 键静默降级）⇒ 按实现口径断言 + 登记产品风险缺口。三项均已提交 **ISSUE #32**。
- 详细预览：`/Users/zhangjiusi/资料/06_工作/Test_work/01-Design_File/20261015-feat-065-067-orchestration/02-需求与测试预览-FEAT-065-067.md`
- 本期范围：同目录 `01-变更包范围卡-FEAT-065-067.md`
- **输入成熟度**：`merged` ⇒ 可用于正式收口
- **用户理解确认**：`confirmed`，2026-10-15（范围卡 §7.1）

## 1. 依据、当前口径与范围

- **设计真相源**：`Technical-AF/docs` @ `561f02dd`，`develop/02-features/FEAT-066-cross-validation-audit.md`（108 行）
- **L2 实现说明**：同 revision，`develop/03-architecture/L2-Low-Level-Design/deepanalyze/Feat-Func-066-cross-validation-audit.md`（159 行）
- **已确认裁决**：同 FEAT-065 §1（三层执行体分工、本期范围、测试方向、门控注入授权）
- **已自行核实的实现口径**（作为判据，不回写为设计预期；证据见 `issues/ISSUE统一台账.md` §3）：
  - 编译产物：视角步 `p_<视角id>` 无依赖；互审步 `r_<视角id>` 依赖**其他**视角的 `p_` 步（`collectOtherStepIds` 排除自身）；审计步 id 为**固定字面量 `audit`**，依赖 = 全部 `p_` 后接全部 `r_`（`BlueprintLoader.java:116-118`）
  - 编译步 `skipIf` **恒为 `null`→`NEVER`**（`:113,118,163`）⇒ 视角/互审/审计步**一律不跳过**，"审计步不可跳过"由结构保证
  - 装载后 `spec.type() == DAG`、图纸 id 保留、成员集合 = 视角 + 审计 + 合成互审（L2 §2.2）
  - 互审成员 id = `review_<视角id>`，按视角 **1:1** 合成（L2 §3.1）
  - 碰撞守卫 = **派生重名检查**（`seenMemberIds`，`:120-126`），且**仅在 `review` 开启时**才可能触发（`buildReviewLayer` 在 `!review` 时返回空，`:144-146`）
  - `review` 判定 = `"true".equalsIgnoreCase(text(bp.get("review")))`（`:102`）⇒ 非该形态**静默不启用、不抛异常**
  - `compileCrossValidation` **从未读取 `steps` 键**（`:96-128` 区间 `grep -c 'get("steps")'` = 0）⇒ 多写 `steps` 被静默忽略
- **范围内**：需求条目目录 19 条 `scope=in`
- **范围外**：5 条 `scope=out`
- **未决问题**：

| 未决项 | 责任人层次 | 状态 | 对本期用例的影响 |
|---|---|---|---|
| `DV066-01` 碰撞守卫触发条件（Feature 无条件 vs 实现仅 `review` 开启时） | 设计确认 | 已提 ISSUE #32 V1 | 066-S09 只构造两侧结论一致的 2 种形态；另 2 种 `unassigned`（GAP-066-01） |
| `DV066-02` 碰撞判定机制（前缀检查 vs 派生重名） | 设计确认 | 已提 ISSUE #32 V2 | 同上 |
| `DV066-03` `review` 非法值应拒还是静默 | 设计确认 | 已提 ISSUE #32 V3 | 066-S15 按实现口径断言（无 `r_` 层）+ 登记产品风险缺口（GAP-066-03），**不判 FAIL** |
| `D1`（066 侧）`cross_validation` 带 `steps` 键的处置 | 设计确认 | 已提 ISSUE #32 D1 | 066-S15 附带断言"被忽略"，登记缺口（GAP-066-04），**不断言应拒** |
| `L066-03` 审计员投影"按声明序"指哪个序 | 开发-L2 确认 | 已提 ISSUE #32 L1 | 066-S06 的顺序子断言标 `partial`；集合包含断言为 gating（GAP-066-02） |
| `Q065-D1` 空白判据（继承） | 设计确认 | 已提 ISSUE #31 D1 | 066-S14 只用 `""` 形态，`"   "` 不构造（GAP-066-05） |
| 制品构建 / 凭据 / 容器（仅 L2 场景需要） | 用户授权 + 测试侧 | 见 §7 | 066-S16 `blocked`（GAP-066-06） |

## 2. SUT 适用性

| SUT/参与者 | 角色 | 适用能力 | 不适用能力 | 结论与依据 |
|---|---|---|---|---|
| `agent-core-ext-orchestration`（组件层直接依赖） | 被测模块本体 | 18 条 `execution` 条目中的编译产物结构、汇聚义务、装载拒绝、等价性、缺席处理 | 会话侧工具契约（FEAT-067）；模板资产内容（模板库特性） | **适用，且是唯一层次**。依据：L2 §4「本特性**零新增顶层类与公共 API**——全部落在 `BlueprintLoader` 的编译区段与既有执行面」⇒ 没有独立的 066 入口，只能经 `BlueprintLoader.load` + `BlueprintScheduler.execute`；且 Feature §5.1.1 明写「编译在装载期完成且**可观测**：编译产物的分层结构、依赖集**均可由测试断言**」——这是设计**主动授予**的断言面 |
| `deepanalyze`（DA 宿主） | L2 层真实成员载体 | 066-S16：真实 LLM 成员下的三视角六标记汇聚 | 编译产物结构（宿主不透出 `BlueprintSpec`） | **适用但仅 1 个场景**，经 FEAT-067 的 `workflow_run` + `multi-perspective-council` 模板（Feature §4「高可信度分析」场景行明写"实装即 `cross_validation` 形态"） |
| deepagent / reactagent / workflow | — | — | 全部 | **不适用**（同 FEAT-065 §2 NC-01） |

**结论**：**不新建 fixture**。交叉验证图纸是自含的 YAML（Feature §5.2「交叉验证图纸自含成员声明，不引用外部图纸」），测试只需提供图纸字符串与成员绑定。

## 3. 黑盒边界、拓扑与前置条件

**黑盒边界**：同 FEAT-065 §3（只消费公开 API；不 Mock LLM；L1 的成员执行体是按 Feature §3 成员绑定契约由测试作为装配方提供）。**本特性额外的一处边界**：编译产物 `BlueprintSpec` 是 `load` 的**返回值**，属公开面 ⇒ 断言其 `type()`/`id()`/`members()`/`steps()`（含 `dependsOn()`/`skipIf()`）是黑盒断言，不是白盒。

**拓扑**：L1 单 JVM 无容器无网络；L2 同 FEAT-065 例 2。

**前置条件**：P1（制品构建）、P2（pom 依赖，**已授权**）为 L1 前提；P4（凭据/容器）、P5（门控，**已授权**）仅 066-S16 需要。同 FEAT-065 §3 表。

## 4. 需求覆盖矩阵

稳定条目目录：`FEAT-066/FEAT-066-需求条目目录.json`（24 条）。

| REQ | 条目（锚点） | scope | 计划形态 | 用例 ID | disposition |
|---|---|---|---|---|---|
| REQ-001 | §2 多视角独立作答 | in | execution | 066-S01 | planned |
| REQ-002 | §2 可选互审·派生 | in | execution | 066-S02 | planned |
| REQ-003 | §2 可选互审·排除自身 | in | execution | 066-S03 | planned |
| REQ-004 | §2 可选互审·关闭变体 | in | execution | 066-S04 | planned |
| REQ-005 | §2 审计员复查全体·依赖集 | in | execution | 066-S05 | planned |
| REQ-006 | §2/§5.1.2 汇聚义务·双标记 | in | execution | 066-S06（L1 双视角四标记）+ 066-S16（L2 三视角六标记） | planned |
| REQ-007 | §2 最终产物 = 审计步产物 | in | execution | 066-S07 | planned |
| REQ-008 | §2 审计步不可跳过 | in | execution | 066-S07 | planned |
| REQ-009 | §2 审计产物为空时回退 | in | execution | 066-S08 | planned |
| REQ-010 | §2/§5.1.1 装载期编译·可断言 | in | execution | 066-S05 | planned |
| REQ-011 | §2 装载期编译·碰撞 fail-fast（**互审启用时 + 派生重名**，新基线口径） | in | execution | 066-S09（4 参数实例） | **planned**（GAP-066-01 已关闭） |
| REQ-012 | §2 留痕 | in | execution | 066-S10 | planned |
| REQ-013 | §5.1.3 视角数不足 | in | execution | 066-S11 | planned |
| REQ-014 | §5.1.3 审计成员缺失 | in | execution | 066-S11 | planned |
| REQ-015 | §5.1.1 与手写 DAG 等价 | in | execution | 066-S12 | planned |
| REQ-016 | §5.1.1 执行层零专用分支 | in | **static** | 066-S13 | planned |
| REQ-017 | §4 视角部分缺席 | in | execution | 066-S14 | planned（`"   "` 子形态**已解禁**，GAP-066-05 已关闭） |
| REQ-018 | §3 编译产物对外形态 | in | execution | 066-S05 | planned |
| REQ-019 | §3 成员声明同构 | in | execution | 066-S11 | planned |
| REQ-020 | §2 OUT 多轮互审 | out | static | — | out-of-scope |
| REQ-021 | §2 OUT 程序化审计判定 | out | static | — | out-of-scope |
| REQ-022 | §2 OUT 投票/打分聚合 | out | static | — | out-of-scope |
| REQ-023 | §5.2 不承诺审计员判定质量 | out | static | — | out-of-scope |
| REQ-024 | §5.2 不承诺跨图纸复用 | out | static | — | out-of-scope |
| — | `review` 非法值静默降级（Feature 未定义，L2 自陈） | in（风险登记） | execution | 066-S15 | **degraded**（按实现口径断言 + 产品风险缺口 GAP-066-03） |
| — | `cross_validation` 带 `steps` 键静默忽略（Feature/L2 均未定义） | in（风险登记） | execution | 066-S15 | **degraded**（同上，GAP-066-04） |

<!-- coverage:scenarios:start -->

| 用例 ID | 场景 | 计划验证方式 |
|---|---|---|
| 066-S01 | 多视角独立作答：视角步无依赖、同层并行、互不可见 | execution |
| 066-S02 | 互审派生：`review:true` → 每视角 1:1 合成互审成员与互审步 | execution |
| 066-S03 | 互审排除自身：输入含其他视角产物、不含自身视角、不含审计、不含其他互审 | execution |
| 066-S04 | 互审关闭变体：`review` 缺省 → 编译产物无 `r_` 层 | execution |
| 066-S05 | 编译产物结构：三层分层 / `p_` 无依赖 / `audit` 依赖全集且为唯一汇点 / `type=DAG` / 图纸 id 保留 | execution |
| 066-S06 | 审计汇聚双标记（L1，双视角四标记） | execution |
| 066-S07 | 最终产物 = 审计步产物；审计步不跳过 | execution |
| 066-S08 | 审计产物为空 → 按 FEAT-065 收口规则回退 | execution |
| 066-S09 | 碰撞拒绝（4 种形态全部可判定：互审启用时 + 派生重名） | execution |
| 066-S10 | 留痕：视角/互审/审计每步登记，审计复查路径可追溯 | execution |
| 066-S11 | 装载拒绝：视角 <2（0 与 1）/ 审计缺失 / 成员 instruction 为空 | execution |
| 066-S12 | 与手写等价 DAG 完全一致（无隐藏通道） | execution |
| 066-S13 | 执行层零专用分支 | static |
| 066-S14 | 视角部分缺席：缺席视角不参与投影、审计仍收其余全体、留痕可见缺席 | execution |
| 066-S15 | `review` 非法形态与多余 `steps` 键 → 装载期 fail-fast（主断言已由"断言静默降级"反转） | execution |
| 066-S16 | 真实 LLM 成员三视角六标记汇聚（补开发自陈缺口） | execution |

<!-- coverage:scenarios:end -->

### 4.1 未承接与降级条目（**2026-10-15 21:20 按新基线 `9ab89fc8` / `f9da5b54f` 更新**）

| 缺口 ID | 条目 | 原形态 | **现状态** | 依据 / 恢复条件 |
|---|---|---|---|---|
| GAP-066-01 | REQ-011 的 2 种碰撞形态（`review` 关闭 + 视角 `review_x`；视角 {`review_x`,`a`,`b`} + `review: true`） | `unassigned` | ✅ **已关闭** | Feature §2/§4/§5.1.3 已裁决为「**互审启用时** + 与**派生互审成员标识重名**」⇒ 两种形态均判**应纳**；066-S09 已扩为 4 参数实例，全部 gating。关联 ISSUE #32 V1/V2 |
| GAP-066-02 | REQ-006 的**顺序**子断言 | `partial` | 🔶 **收窄为 non-gating**（事实层已解除） | 已读码核实 `BlueprintScheduler.java:235-247`：严格按 `dependsOn()` 序拼接、分隔符 `\n---\n`、缺席不加入；配合 `auditDeps` 构造序 ⇒ 顺序**完全确定、可断言**。但**设计仍未承诺顺序**（L2 §3.2 仍只写"按声明序"，已在 #32 评论催答）⇒ 按门禁顺序断言只能作 `OUT / non-gating` 增强证据，顺序变化**不判 FAIL**；主断言仍为"集合包含 + 逐字原文" |
| GAP-066-03 | `review` 非法形态静默不启用 | `degraded` | ✅ **已关闭**（产品风险消除） | Feature §2「仅 true/false/缺省三种形态合法，其余**装载期拒绝**」+ §5.1.3 新增错误行「…**不静默降级**」；代码新增 `parseReviewFlag`。066-S15 主断言已**完全反转**为"断言拒绝"。关联 ISSUE #32 V3 |
| GAP-066-04 | `cross_validation` 带 `steps` 键被静默忽略 | `degraded` | ✅ **已关闭** | Feature §2「**不得声明 steps 键**——声明即拒绝」+ §5.1.3 新增错误行；代码在 `compileCrossValidation` 开头即 `containsKey("steps")` 拒绝。关联 ISSUE #32 D1 |
| GAP-066-05 | REQ-017 的 `"   "`（仅空白）子形态 | `partial` | ✅ **已关闭** | Feature §5.1.3 新增「产物缺席的统一判据：null、空串或仅含空白字符(**isBlank**)即缺席」⇒ `"   "` **解禁构造**，期望为"算缺席"。关联 ISSUE #31 D1 |
| GAP-066-06 | 066-S16（L2 真实成员三视角六标记） | `blocked` | ⛔ **仍 blocked（环境类，按用户指示统一到最后解决）** | **制品已就绪**（`deepanalyze-engine:0.1.3-feat065067-r2:exec` 已构建并核验指纹）；剩余前置只有两项环境类：① `~/.llmrc` absent；② **Docker Hub 不可达且本地无 redis 镜像** ⇒ testcontainers 起 Redis 会失败（ENV-1.5）。恢复条件 = 凭据配置 + redis 镜像可得 |
| GAP-066-07 | `review` 的**大小写变体**（`TRUE` / `True`）行为 | — | ⛔ **撤销 partial（2026-11-18 复审裁决）** | L1 现行明文「仅 true/false/缺省三种形态合法，其余形态**装载期拒绝**」——`TRUE`/`True` 属非法形态，进 066-S15 负向断言；实现若忽略大小写接受，记**开发缺陷**，不作为可收口的 partial |

**汇总**：原 6 条缺口 → **4 条关闭**（01/03/04/05）、**1 条收窄为 non-gating**（02）、**1 条仍 blocked 于环境**（06）、**新增 1 条 partial**（07）。

## 5. 端到端场景

**L1 主旅程**：`cross_validation` 图纸字符串 → `BlueprintLoader.load` →（装载期编译）→ 得 `BlueprintSpec(type=DAG)` →
**先断言编译产物结构**（分层 / `p_` 依赖集 / `r_` 依赖集排除自身 / `audit` 依赖全集且唯一汇点 / 成员集合 / 图纸 id 保留）→
再绑定确定性执行体（每个视角与互审回显**唯一标记串**）→ `execute` →
断言审计员实收输入含全体标记原文 + `finalOutput` == 审计步产物 + 留痕逐层齐备。

**L2 主旅程**：门控开启的 `deepanalyze` + 真实模型 → 会话里说"用多视角会商模板分析 X" →
主 Agent 调 `workflow_run(template="multi-perspective-council")` → 真实 LLM 成员执行三视角 + 互审 + 审计 →
断言 `steps[]` 条数 == 7（3 视角 + 3 互审 + 1 审计）、成员标识形态正确、`final_output` == `audit` 步 output。

## 6. 详细用例

### 066-S01：多视角独立作答
- 可执行性/优先级：`runnable` / **P0**；形态 `execution`；层次 component；Oracle：Feature §2「多视角独立作答」+ §5.1.1
- **Given**：三视角 + 审计员图纸，`review` 缺省；每个视角执行体回显 `P-066-S01-<视角id>-<uuid8>` 并**记录自己实收的 `subGoal` 原文**
- **When**：`load` + `execute`
- **Then**：① 三个 `p_` 步 `dependsOn()` 均为空且在同一拓扑层；② 每个视角实收 `subGoal` **不含**任何其他视角的标记串；③ 三个视角执行体的启动区间**时间重叠**（用闩锁构造，同 FEAT-065 065-S02）
- **PASS**：三项全成立 **FAIL**：任一视角实收输入含他人标记（独立性被破坏）或 `p_` 步有依赖 **INCONCLUSIVE**：视角数不等于 3
- 证据：三个视角实收 `subGoal` 原文 + 编译产物分层 + 闩锁到达时间戳
- 不应断言：视角产出的**内容质量**
- 自动化：`component/orchestration/CrossValidationCompileTest#perspectivesAreIndependentAndParallel`，`@Tag("component")` `@Tag("feat-066")`，`@Feature("FEAT-066: 交叉验证与汇总审计")`，`@Story("066-S01: 多视角独立作答")`

### 066-S02 / S03 / S04：互审层三件
- 可执行性/优先级：`runnable` / **P0**（Feature §6 明文要求"编译产物结构断言（分层/依赖集/**排除自身**）"与"互审关闭变体"）
- **066-S02 Given**：三视角 + `review: true` + 审计员 → **Then** 编译产物成员集合含 `review_<视角id>` 三个（1:1 合成）、步集合含 `r_<视角id>` 三个；图纸**未声明**互审成员而产物有 ⇒ 证明由引擎派生；互审成员 `instruction()` 非空且**嵌入了原视角指令**（L2 §3.1）
- **066-S03 Given**：同上，每个视角执行体回显 `P-…-<id>`、每个互审执行体回显 `R-…-<id>` 并记录实收 `subGoal` → **Then** 对每个 `r_<id>`：实收输入**含其余两个视角的标记**、**不含自身视角的标记**、**不含任何互审标记**、**不含审计标记**；且 `r_<id>` 的 `dependsOn()` == 其余两个 `p_` 步 id
- **066-S04 Given**：同图纸但 `review` 缺省（或 `false`）→ **Then** 编译产物**无 `r_` 步、无 `review_` 成员**；`audit` 的 `dependsOn()` == 全部 `p_` 步（不含互审）
- **PASS/FAIL/INCONCLUSIVE**：逐条对应 Then；S03 若任一互审看到自身视角产物即 **FAIL**（这是本特性最核心的判据）
- 证据：编译产物的成员/步骤/依赖集清单 + 每个互审执行体实收 `subGoal` 原文
- 不应断言：互审成员 `instruction()` 的**具体文案**（Feature §2 只说"指令由引擎生成"，未给内容要求 ⇒ 按门禁标 `OUT / non-gating`）；互审成员 id 的完整命名规则**可以**断言（已核实为 `review_<视角id>`）
- 自动化：`CrossValidationCompileTest`，3 个方法

### 066-S05：编译产物结构断言（本特性的主 Oracle）
- 可执行性/优先级：`runnable` / **P0**；Oracle：Feature §5.1.1「编译在装载期完成且**可观测**：编译产物的分层结构、依赖集均可由测试断言」+ §3「装载后对外表现为普通依赖图图纸」
- **Given**：三视角 + `review: true` + 审计员图纸，图纸 id = `T-066-S05`
- **When**：`load`
- **Then**：① `spec.type() == DAG`；② `spec.id() == "T-066-S05"`（id 保留）；③ 拓扑分层 == 3 层，第 1 层 = 3 个 `p_`、第 2 层 = 3 个 `r_`、第 3 层 = `audit`；④ 每个 `p_` 的 `dependsOn()` 为空；⑤ `audit` 的 `dependsOn()` == 全部 3 个 `p_` + 全部 3 个 `r_`（共 6 项）；⑥ `audit` 是**唯一**出度为 0 的步（唯一汇点）；⑦ 全部编译步的 `skipIf()` 规范化后为 `NEVER`
- **PASS**：七项全成立 **FAIL**：任一不成立（尤其 ⑥——若出现第二个汇点说明拓扑被改） **INCONCLUSIVE**：`load` 抛异常
- 证据：`spec` 的完整结构导出（成员/步骤/依赖/类型/id）
- 不应断言：`perspectives[]` 在 `members()` 里的排列位置（未承诺）
- 自动化：`CrossValidationCompileTest#compiledSpecStructure`

### 066-S06：审计员汇聚双标记（L1，双视角四标记）
- 可执行性/优先级：`runnable` / **P0**（Feature §6 明文"双标记断言=同时断言审计输入含各视角产物文本与互审产物文本"）
- **Given**：**双**视角 + `review: true` + 审计员；视角执行体回显 `P-066-S06-A-<uuid8>` / `P-066-S06-B-<uuid8>`；互审执行体回显 `R-066-S06-A-<uuid8>` / `R-066-S06-B-<uuid8>`；审计执行体把**实收 `subGoal` 原文**回显
- **When**：`execute`
- **Then**：审计员实收输入**同时含 4 个标记**（2 视角 + 2 互审），且每个标记**逐字完整**（证明是原文、未被摘要或改写）
- **PASS**：4 个标记全部命中且逐字一致 **FAIL**：缺任一标记，或标记被截断/改写（⇒ 违反"引擎不做摘要或改写"） **INCONCLUSIVE**：审计步未执行
- 证据：审计执行体实收 `subGoal` 的**完整原文** + 4 个标记的命中位置
- 不应断言：4 段的**顺序**（GAP-066-02）；不断言有分栏标识或来源标签（L2 §1.3 自陈"无结构化分栏"⇒ 要求它会把符合设计的实现判 FAIL）
- 自动化：`CrossValidationAuditAggregationTest#auditorReceivesAllUpstreamVerbatim`

### 066-S07 / S08：收口与回退
- 可执行性/优先级：`runnable` / **P0**；Oracle：Feature §2「最终答案收口」+ §5.1.2；FEAT-065 §2 收口规则
- **066-S07 Given**：双视角 + 审计（`review` 缺省），审计执行体回显 `AUDIT-066-S07-<uuid8>` → **Then** `finalOutput` == `AUDIT-066-S07-…`；`audit` 步 `skipped == false`；且**不存在**任何能让审计步被跳过的图纸写法（编译步 `skipIf` 恒 `NEVER`，已由 066-S05 ⑦ 证明）
- **066-S08 Given**：同上但审计执行体返回 `""`，某视角返回 `FALLBACK-066-S08-<uuid8>` → **Then** `finalOutput` == `FALLBACK-066-S08-…`（按 FEAT-065 收口规则回退为末个非空白步产物）；`outcomes` 仍含全部步（含审计步，其 output 为缺席）
- **PASS/FAIL**：如 Then；S08 若 `finalOutput` 为空 ⇒ **FAIL**（空覆盖了既有收口产物）
- 证据：`finalOutput` 原文 + `outcomes` 全表
- 不应断言：`"   "`（仅空白）形态（GAP-066-05）
- 自动化：`CrossValidationFinalOutputTest`，2 个方法

### 066-S09：碰撞拒绝（**新基线：4 种形态全部可判定**）
- 可执行性/优先级：`runnable` / **P0**（原为"部分可跑"，`DV066-01`/`DV066-02` 已由 docs `9ab89fc8` 裁决）；形态 `execution`；层次 component
- Oracle：Feature §2（新基线）「**互审启用时**，视角成员标识与**派生出的互审成员标识(review_<视角 id>)重名**即 fail-fast」+ §4「标识碰撞」前置条件"互审启用时" + §5.1.3 错误表「成员标识与派生互审成员标识重名(**互审启用时**) \| 装载期拒绝，错误含碰撞定位」；L2 §7.2「视角 id 与派生互审成员**重名**(review 开启)」
- **Given**：4 个参数实例
  | # | 图纸 | 期望 | 依据 |
  |---|---|---|---|
  | ① | `review: true` + 视角含 `review_a` **且**另有视角 `a` | **拒**（派生 `review_a` 与已声明视角 `review_a` 重名） | Feature §2 新口径 |
  | ② | `review` 缺省 + 视角 id = `review_x` | **纳**（互审未启用 ⇒ 无派生成员 ⇒ 无从重名） | Feature §2「互审启用时」这一新增触发条件 |
  | ③ | `review: true` + 视角集合 {`review_x`, `a`, `b`}（**无**视角 `x`） | **纳**（派生 `review_a`/`review_b`/`review_review_x`，与已声明成员无一重名） | 判定机制为"重名"而非"前缀" |
  | ④ | `review: true` + 视角 id 全不以 `review_` 开头 | **纳**（正常图纸） | 对照组 |
- **When**：逐张 `load`；对①追加"能装载则 execute"以验证不产生执行
- **Then**：①抛 `BlueprintException`，消息含**图纸 id** 与**碰撞的成员 id**（实现消息形态已核实：`"blueprint id=" + id + " cross_validation duplicate member id=" + member.id() + " (perspectives/auditor/review_* must not collide)"`），且执行体计数 **0**；②③④装载成功且 `spec.type()==DAG`
- **PASS**：四种形态各自符合 **FAIL**：①装载成功（碰撞未拦）、或②③被拒（**过度拦截**——这正是旧基线下的歧义区，新口径已明确为"纳"）
- 证据：4 张图纸原文 + ①的异常消息原文 + ②③④的编译产物成员集合
- 不应断言：异常消息的完整措辞（只断言含图纸 id 与成员 id）
- **缺口状态变化**：**GAP-066-01 关闭**（原 2 种 `unassigned` 形态 → `planned` 且升级为 gating）
- 自动化：`CrossValidationLoadRejectionTest#collisionGuardFourForms`，4 参数实例

### 066-S10：留痕（视角/互审/审计每步登记）
- 可执行性/优先级：`runnable` / **P1**；Oracle：Feature §2「留痕」；机制引用 FEAT-065 065-S09/S10
- **Given**：三视角 + `review: true` + 审计（共 7 步）
- **Then**：`stepOutcomes()` 共 **7 条**，`stepId` 序列 == 3 个 `p_`（视角声明序）→ 3 个 `r_`（视角声明序）→ `audit`；每条 `memberId` 与 `skipped` 齐备；`branchId` 均非空；森林可按执行根取回全部 7 条
- **PASS/FAIL**：如 Then；序列不符即 **FAIL**（登记序被破坏）
- 自动化：`CrossValidationTraceTest`

### 066-S11：装载拒绝（视角不足 / 审计缺失 / 成员声明同构）
- 可执行性/优先级：`runnable` / **P0**；Oracle：Feature §5.1.3 错误表 + §3「成员声明与 FEAT-065 图纸成员同构」
- **Given**：4 个实例——① `perspectives` 为空列表；② 只有 1 个视角；③ 缺 `auditor` 键；④ 某视角 `instruction` 为空（验证"同构"⇒ 继承 FEAT-065 的成员约束）
- **Then**：四者均抛 `BlueprintException`；①②③ 消息含**图纸 id**（已核实：`:99` "requires at least 2 perspectives"、`:104` "requires an auditor mapping"）；④ 消息含成员定位；四者执行体计数均为 0
- **PASS/FAIL**：如 Then
- 证据：4 条异常消息原文
- 自动化：`CrossValidationLoadRejectionTest`，4 参数实例

### 066-S12：与手写等价 DAG 完全一致（无隐藏通道）
- 可执行性/优先级：`runnable` / **P1**；Oracle：Feature §5.1.1「图纸一旦装载，执行语义与一张手写等价 DAG **完全一致**——无隐藏通道」
- **Given**：① 双视角 + `review: true` + 审计的 `cross_validation` 图纸；② **手工书写**的等价 `dag` 图纸（同样 5 步：2 个视角步无依赖、2 个互审步各依赖另一视角步、1 个审计步依赖全部 4 步），成员与指令逐字相同
- **When**：分别 `load` + 用**同一组确定性执行体**分别 `execute`
- **Then**：两者的 `spec` 结构（分层、各步 `dependsOn()`、`skipIf()`）**逐项相同**；两次执行的 `stepOutcomes()` 序列、各步 `skipped`、`finalOutput` **完全一致**
- **PASS**：结构与执行结果双重一致 **FAIL**：出现任何差异（⇒ 存在交叉验证专用通道，违反 Feature §5.1.1 与 FEAT-066 §6「不得在执行层为交叉验证增加专用分支」）
- 证据：两份 `spec` 结构导出 + 两次 `stepOutcomes()` 序列的逐项 diff
- 自动化：`CrossValidationEquivalenceTest`

### 066-S13：执行层零专用分支（静态）
- 可执行性/优先级：`runnable` / **P1**；形态 **`static`**（L2 §7.1 明写"结构性审查(grep 证据)"⇒ 设计本来要求静态验证）
- **Given/When**：只读 `BlueprintScheduler.java` 全文
- **Then**：`cross_validation` / `CROSS_VALIDATION` 命中 **0**
- **PASS**：0 **FAIL**：≥1
- 证据：grep 命令原文 + 命中计数 + commit
- 自动化：与 FEAT-065 的 `OrchestrationNoSpecialBranchStaticTest` **同一个类**（同一份证据服务两个特性），在覆盖台账登记共担

### 066-S14：视角部分缺席
- 可执行性/优先级：`runnable` / **P0**（Feature §4 明列场景）；Oracle：Feature §4「视角部分缺席」+ §5.1.3；已核实编译步 `skipIf` 恒 `NEVER`
- **Given**：三视角 + `review: true` + 审计；视角 A 执行体返回 `""`（缺席），B、C 返回唯一标记
- **When**：`execute`
- **Then**：① 视角 A 步 `skipped == false`（**视角步无上游，永不因策略跳过**）且 `output` 为缺席；② 留痕可见 A 的缺席（7 条留痕齐备）；③ 审计员实收输入**不含** A 的投影段、**含** B 与 C 的标记与全部 3 个互审标记；④ 互审步**照常执行**（`skipIf` 恒 `NEVER`，即使某互审的上游有缺席）
- **PASS**：四项成立 **FAIL**：A 被标记为 skipped，或审计员因一份缺席就收不到其余全体 **INCONCLUSIVE**：A 未按预期返回空
- **不构造**："全部其他视角均空白"的极端变体原本因 `Q066-D2` 未决而不构造，**现已解禁**（`skipIf` 恒 `NEVER` 已核实）⇒ 追加第 5 个实例：B、C 均返回 `""`，断言对应互审步照常执行、其输入无上游投影段、审计步照常执行
- 证据：`outcomes` 全表 + 审计与互审执行体实收输入原文
- 不应断言：`"   "` 形态（GAP-066-05）
- 自动化：`CrossValidationAbsentPerspectiveTest`，2 参数实例

### 066-S15：`review` 非法形态与多余 `steps` 键——**新基线：装载期 fail-fast**（主断言已完全反转）
- 可执行性/优先级：`runnable` / **P0**（由"风险登记性质"升为**正面 gating**）；形态 `execution`；层次 component
- ⚠️ **本条在旧基线（docs `561f02dd` / solution `53c1a174a`）下的主断言是"装载成功且无 `r_` 层"（当时实现为静默降级）。新基线下该期望已作废**：
  Feature §2 改为「仅 **true/false/缺省**三种形态合法，其余形态**装载期拒绝**」；§5.1.3 新增两行错误表
  「互审开关非法形态(映射/列表/非布尔标量) \| 装载期拒绝，错误含图纸 id 与键定位——**不静默降级**」与
  「图纸声明 steps 键 \| 装载期拒绝(steps 由视角/审计展开，声明即写错)」；L2 §7.2 同步新增两行；
  代码新增 `parseReviewFlag` 并在 `compileCrossValidation` 开头即 `containsKey("steps")` 拒绝（已复核 5 处命中）。
- Oracle：上述 Feature §2 / §5.1.3 与 L2 §2.1 / §7.2 的新口径
- **Given**：6 个参数实例
  | # | 图纸写法 | 期望 |
  |---|---|---|
  | ① | `review: {enabled: true}`（映射） | **拒** |
  | ② | `review: [true]`（列表） | **拒** |
  | ③ | `review: 1`（非布尔标量） | **拒** |
  | ④ | `review: "enabled"`（非 true/false 字符串） | **拒** |
  | ⑤ | `review: true` 且**额外多写** `steps:` 节 | **拒** |
  | ⑥ | 对照组：`review: true` / `review: false` / `review` 缺省（三张合法图纸） | **纳**，且 `true` 有 `r_` 层、`false` 与缺省无 `r_` 层 |
- **When**：逐张 `load`
- **Then**：①–⑤ 均抛 `BlueprintException`；`review` 类错误消息含**图纸 id** 与**键定位**（实现消息形态已核实：`"blueprint id=" + id + " cross_validation review must be a boolean scalar (true/false)"`）；`steps` 类错误消息含 `"must not declare 'steps'"`；五者执行体计数均为 **0**
- **PASS**：①–⑤ 全部拒绝且消息含图纸 id 与定位，⑥ 三张合法图纸行为正确 **FAIL**：任一非法形态被静默接受（即静默降级复活，属**回归**）
- 证据：6 张图纸原文 + 5 条异常消息原文 + ⑥ 的编译产物步骤清单
- **负向断言（2026-11-18 复审升格）**：`review: TRUE` / `review: True`（大小写变体）→ 现行 L1 明文仅 true/false/缺省三种形态合法、其余**装载期拒绝**——变体被静默接受即 **FAIL（开发缺陷）**，不再豁免
- **缺口状态变化**：**GAP-066-03、GAP-066-04 关闭**（`degraded` → `planned`，产品风险缺口消除——静默降级已被 fail-fast 取代）
- 自动化：`CrossValidationLoadRejectionTest#reviewAndStepsFailFast`，6 参数实例

### 066-S16：真实 LLM 成员三视角六标记汇聚（L2 端到端，**补开发自陈缺口**）
- 可执行性/优先级：**`blocked`**（GAP-066-06）/ **P0** —— L2-066 §7.2 自陈「审计收六份产物(三视角+三互审)——**执行级背书为双视角四标记 UT；六份为结构断言+投影机制推导**」⇒ 本场景是**新增证据**，不是重复
- 形态 `execution`；SUT：`deepanalyze`（门控开启）；层次 e2e
- **Given**：模板 `multi-perspective-council`（Feature §4 明写"实装即 `cross_validation` 形态"）或等价的三视角 + `review: true` + 审计图纸直给；真实模型成员
- **When**：经标准 A2A 入口发一句"用多视角会商模板分析 X"
- **Then**：① `status=completed`；② `steps[]` **条数 == 7**（3 视角 + 3 互审 + 1 审计）；③ 每步 `member` 标识形态正确（视角 id / `review_<视角id>` / 审计 id）；④ `final_output` == `audit` 步的 output 且非空；⑤ 与 066-S05 的 L1 编译产物结构**逐项对照一致**
- **PASS**：五项成立 **FAIL**：条数或成员形态不符（⇒ 真实成员下编译或调度变形）、`final_output` 与审计步 output 不一致 **INCONCLUSIVE**：主 Agent 未调用 `workflow_run`（模型未配合，重试或强化指令语料，**不判产品 FAIL**）
- 证据：Failsafe XML + `workflow_run` 出参原文 + L1 对照结果
- **不应断言**：审计结论的**质量或正确性**（Feature §5.2 明文不承诺 ⇒ 判 FAIL 即误判）；**不得断言"检索发生了"**（L2-067 §1.3：成员是裸 `ReActAgent`、无工具，"检索员不能检索"）；不断言六份产物在审计输入里逐字可见——**真实模型输出不可控**，L2 层的汇聚证据以"条数 + 成员形态 + 收口来源"为主，逐字原文断言只在 L1（066-S06）做
- 自动化：`e2e/deepanalyze/DaCrossValidationRealMemberE2EIT`，`@Tag("e2e")` `@Tag("feat-066")`

## 7. Fixture、环境与确认门禁

同 FEAT-065 §7（P1 制品、P2 pom 依赖**已授权**、P4 凭据/容器、P5 门控**已授权**）。**本特性不需要任何额外 fixture 或装配改动**——L2 §4 明示"零新增顶层类与公共 API"，测试入口与 FEAT-065 完全相同。

## 8. 自动化落点与精确执行规划

| 层次 | 包 | 测试类 | 覆盖场景 |
|---|---|---|---|
| component | `cases/component/orchestration/` | `CrossValidationCompileTest` | 066-S01、S02、S03、S04、S05 |
| component | 同上 | `CrossValidationAuditAggregationTest` | 066-S06 |
| component | 同上 | `CrossValidationFinalOutputTest` | 066-S07、S08 |
| component | 同上 | `CrossValidationLoadRejectionTest` | 066-S09、S11 |
| component | 同上 | `CrossValidationTraceTest` | 066-S10 |
| component | 同上 | `CrossValidationEquivalenceTest` | 066-S12 |
| component | 同上 | `OrchestrationNoSpecialBranchStaticTest`（与 FEAT-065 共用） | 066-S13 |
| component | 同上 | `CrossValidationAbsentPerspectiveTest` | 066-S14 |
| component | 同上 | `CrossValidationSilentDegradationTest` | 066-S15 |
| e2e | `cases/e2e/deepanalyze/` | `DaCrossValidationRealMemberE2EIT` | 066-S16 |

Allure：类级 `@Feature("FEAT-066: 交叉验证与汇总审计")`，方法级 `@Story("<用例 ID>: <描述>")`，`@Tag("component"|"e2e")` + `@Tag("feat-066")`。

执行命令同 FEAT-065 §8，`-Dtest` 选择器改为 `com.huawei.ascend.sit.cases.component.orchestration.CrossValidation*Test`；L2 用 `-Dit.test='DaCrossValidationRealMemberE2EIT'`。

## 9. 状态汇总与退出标准

- 状态链接 `FEAT-066/FEAT-066-动态状态页.md`（manifest 阶段生成）。本轮 16 个场景全部 `not-run`（066-S16 为 `blocked`），`automation_status: design-only`。
- ISSUE 状态：#32 `已创建`。特性收口：**`open`**。
- **退出标准**：19 条 `in` 条目各获适当形态的实际证据；GAP-066-01~06 全部升级或经用户明确裁决延期。**GAP-066-03/04 属 `degraded`（风险登记）——即使 ISSUE #32 裁决为"静默降级可接受"，也必须由用户书面确认该范围变化，不能通过确认降级替代正式范围裁决**（RULES「测试状态、结论与收口分层」+ `review-and-design.md`「正式范围变化另留裁决」）。

## 10. 负结论归因记录

| 结论 ID | 结论 | 候选解释 | 排除依据 | 仍未排除项 | 下一步探针 |
|---|---|---|---|---|---|
| NC-01 | 互审步**看不到其他互审步**的产物（设计未明写，判为可推导、不外问） | ① 设计缺口需澄清；② 可由两份设计推导 | FEAT-066 §5.1.1 定义互审步依赖集 = **其他视角步**；FEAT-065 §5.1.3 定义下游输入 = **其依赖步骤**的产物文本 ⇒ 互审步输入不含其他互审产物。已由 066-S03 直接断言 | 无 | — |
| NC-02 | 审计步**不可能被图纸声明为可跳过**（不外问） | ① 需澄清；② 结构上无处声明 | FEAT-066 §3 的配置节只有 `perspectives`/`review`/`auditor` 三项，**无步骤声明位**；FEAT-065 §3 的 `skip_if` 是**步骤级**键 ⇒ 图纸作者无处声明。已核实编译步 `skipIf` 恒 `null`→`NEVER`（`BlueprintLoader.java:113,118,163`） | 无 | — |
| NC-03 | 审计产出为空时的回退口径与 FEAT-065 **一致**（不提偏差） | ① 两处措辞不同即偏差；② 语义等价 | FEAT-066 §2「按 FEAT-065 收口规则回退为末个非空步产物」与 FEAT-065 §2「末个实际执行步的产物 + 空不覆盖既有收口产物」在"从后往前取第一个非空白"上等价；L2-066 §3.2 亦自陈"与 FEAT-066 §2 口径一致" | "空 vs 空白"的措辞漂移仍在（已归 ISSUE #31 D1/V2，不重复开单） | — |
| NC-04 | **不因审计结论质量判产品 FAIL** | ① 审计员没给出可信结论即缺陷；② 设计明文不承诺 | FEAT-066 §5.2「审计员判定质量 \| 审计员是 LLM 成员，其结论可信度由其指令与模型能力决定；**引擎只承诺汇聚义务**」；L2 §1.3 同；Feature §6 要求测的是"编译产物结构 + 审计员输入含全体产物" | 无 | — |
| NC-05 | 066-S15 **不判产品 FAIL**（静默降级） | ① 静默降级即缺陷；② 无权威预期不可判 | Feature §3 只定义 `review` "可选、布尔标量、缺省不启用"，§5.1.3 错误表**无 `review` 非法值条目** ⇒ 设计未承诺拒绝；门禁「不得由测试侧自行补写产品规则」。但**风险已登记**（GAP-066-03）并提 ISSUE #32 V3 请设计裁决 | 设计是否意图 fail-fast（对照 `skip_if` 是 fail-fast，哲学不一致） | 等 ISSUE #32 裁决 |
| NC-06 | `cross_validation` 带 `steps` **不断言应拒** | ① 类比 `parallel` 带 steps 应拒；② 设计未列举即不要求 | FEAT-065 §2 形状违规枚举里有"全并行带步骤"但**无** "cross_validation 带步骤"；FEAT-066 §3/§5.1.3 亦无 ⇒ 无权威预期。类比推理不能作为判据（门禁：设计未写的登记为缺口） | 设计是否意图拒绝（已提 ISSUE #32 D1） | 等裁决 |

## 最小可执行例子

### 例 1：L1 编译产物结构 + 汇聚双标记（代表 066-S01~S12、S14、S15，共 13 个场景）

- **真实输入声明**：
  ```yaml
  blueprint:
    id: T-066-S06
    type: cross_validation
    perspectives:
      - {id: lens-tech, instruction: "从技术可行性视角独立作答"}
      - {id: lens-legal, instruction: "从合规风险视角独立作答"}
    review: true
    auditor:
      {id: auditor, instruction: "复查全体视角与互审产物原文后给出最终结论"}
  ```
  成员绑定：视角执行体回显 `P-066-S06-<视角id>-<uuid8>`；互审执行体回显 `R-066-S06-<视角id>-<uuid8>`；审计执行体回显**自己实收的 `subGoal` 原文**
- **拟发请求**：`BlueprintLoader.load(yaml)` → 断言 `spec` 结构 → `new BlueprintScheduler(forest, 4, drain).execute(spec, bindings, "U-066-S06-<uuid8>")`
- **入口与必要资源**：被测模块公开 API；必要资源 = **仅 P1+P2**（制品 + pom 依赖）。**无需**凭据/容器/网络/数据库
- **可观察输出**：`spec.type()/id()/members()/steps()`（含 `dependsOn()`、`skipIf()`）；`BlueprintResult.stepOutcomes()`；审计执行体实收 `subGoal` 原文；`finalOutput`
- **证据落点**：`target/surefire-reports/TEST-…CrossValidation*Test.xml` + Allure 附件（编译产物结构导出、审计实收输入原文）+ `<batch>/evidence/<run-id>/`
- **代表性**：材料来源 = Feature §4「双视角无互审」与「三视角带互审」两行场景 + §6 明文要求的"双标记断言"；保留的真实使用条件 = 图纸由业务语言撰写、视角指令互不相同、审计员指令由图纸作者撰写（引擎不代写）；关键差异 = 成员是确定性执行体，产物是**短标记串**而非真实长文本 ⇒ 由 066-S16 用真实模型补真实上游产物形态
- **故障与主断言**：若引擎让某互审看到自身视角产物 → 066-S03 的"不含自身视角标记"失败；若审计员只收到部分产物 → 066-S06 的"4 个标记全部命中"失败；若引擎替审计员做了摘要 → 066-S06 的"逐字完整"失败；若编译产物多出一个汇点或层次错乱 → 066-S05 的 ⑥/③ 失败；若执行层为交叉验证开了专用分支 → 066-S12 的"与手写等价 DAG 逐项一致"与 066-S13 的 grep 同时失败

### 例 2：L2 端到端·真实 LLM 成员（代表 066-S16）

- **真实输入声明**：模板名 `multi-perspective-council`（或等价的三视角 + `review: true` + 审计图纸直给）
- **拟发请求**：经标准 A2A 入口向 `deepanalyze` 发「请用多视角会商模板分析：<问题>」
- **入口与必要资源**：`SutStack.agent("deepanalyze")` + Redis 容器 + 模型凭据 + 门控 `-Ddeepanalyze.workflow.enabled=true`（P1/P4/P5）
- **可观察输出**：`workflow_run` 出参（`status`/`workflow_id`/`final_output`/`steps[]` 共 7 条）
- **证据落点**：`target/failsafe-reports/TEST-…DaCrossValidationRealMemberE2EIT.xml` + 出参原文附件
- **代表性**：材料来源 = Feature §4「高可信度分析」场景行（明写"多视角会商模板 `multi-perspective-council`，实装即 `cross_validation` 形态"）⇒ 这是设计**自己指定**的真实使用路径；关键差异与限制 = 真实模型输出不可控，故 L2 层**不做逐字原文断言**（那是 L1 的职责），只断言结构（条数 7 / 成员形态 / 收口来源）；**不得断言检索发生**（成员无工具）
- **故障与主断言**：若真实成员下互审层未被派生 → "`steps[]` 条数 == 7"失败（会是 4）；若宿主改了编排语义 → 与 L1 编译产物结构对照失败；若收口取错步 → "`final_output` == `audit` 步 output"失败

## 两轮自审记录

**第一轮（真相源、范围、责任边界、黑盒性、Oracle）**

- 真相源：预期全部取自 Feature 与 L2 @ `561f02dd`；代码事实（编译产物结构、碰撞机制、`review` 判定、`steps` 未读取）只用作**判据补强**与 `degraded` 场景的实现口径，**未回写为设计预期**（NC-05/NC-06 明确记录）✓
- 范围：19 条 `in` 全部有承接；5 条 `out` 有承接方；未把"审计结论质量""多轮互审""投票聚合"纳入 ✓
- 责任边界：不改业务代码、不改 fixture（L2 §4 明示零新增公共 API）✓
- 黑盒性：只消费 `load`/`execute` 的入出参与 `BlueprintSpec` 的公开访问器；断言编译产物是 Feature §5.1.1 **明文授予**的断言面，不属白盒 ✓
- Oracle：16 个场景逐条给 PASS/FAIL/INCONCLUSIVE；6 个缺口登记 `resume_when`；7 处"不应断言"防止把未承诺项当 FAIL ✓

**第二轮（需求处置、G/W/T、SUT 适用性、证据、状态、自动化落点）**

- 需求处置：24 条 + 2 条风险登记逐条有 `disposition`（含 1 组 `unassigned`、2 条 `degraded`、1 条 `blocked`）✓
- G/W/T：16 个场景全备；参数实例数明确（S09 两实例、S11 四实例、S14 两实例、S15 五实例）✓
- SUT 适用性：§2 三行结论 + "为什么不适用三个既有 SUT" + "为什么不新建 fixture" ✓
- 证据：L1 一律本轮 Surefire XML；L2 用 Failsafe XML；**不采信开发的离线账本**（L2 §1.3 自陈真机 e2e CI 不跑）✓
- 状态：四种状态分开；设计期不填执行数字；收口 `open` ✓
- 自动化落点：10 个测试类（其中 1 个与 FEAT-065 共用）+ 包路径 + Allure 标注 + 执行命令 ✓

**反向挑战一个关键假设**

- **假设**：L1 用短标记串做"审计员收全体原文"的双标记断言，足以证明汇聚义务。
- **条件变化及依据**：把审计员的上游产物从**短标记串**换成**真实模型产出的长文本**（依据：Feature §1 的目标场景是"公检法/审计/风控/零幻觉分析"，真实产物是长文；`review-and-design.md`「代表性」明写"真实上游产物也可能只代表简单用法"、要检查"输入来源"与"结果是否被下游消费"）。
- **检查结果**：**发现两处遗漏并已补**。
  ① 长文本可能**本身包含**类似标记的子串，短标记断言存在巧合命中风险 ⇒ 已要求标记串带用例 ID + `uuid8`（066-S06 的 `P-066-S06-A-<uuid8>`），并在 FEAT-067 侧禁止用"输出含某关键词"这类宽泛断言（对应 L2-067 §1.3 自陈的"子串命中宽泛"弱点）；
  ② 长文本会暴露**投影拼接的分隔行为**：L2-065 §3.4 说上游产物以 `---` 分隔，若某份真实产物内部本身含 `---`，下游（含审计员）能否正确区分边界？设计未承诺转义 ⇒ 已在 066-S06 的"不应断言"里明确**不断言分栏可解析**（未承诺），并在 §6 记录为观察项，若将来设计要求可解析分栏则需新开条目。
- **不再扩展**：本轮只挑战这一项，未发现其他新的具体风险；不新增自审轮次，也不把一次挑战解释为已穷尽未知问题。

---

## 11. 新基线更正（**权威**；与 §1/§4/§6 冲突时以本节为准）

> 触发：docs `561f02dd` → **`9ab89fc8`**、agent-solution `53c1a174a` → **`f9da5b54f`**（2026-10-15 19:21/19:29），
> 开发与文档侧已按 ISSUE #31/#32/#33 逐条修订。本节把 §6 各用例中**已作废或需升级**的期望值集中更正，
> 避免实现 Java 时读到旧文。§6 原文按 RULES 保留不改写（旧基线下的审计痕迹）。
> 完整依据见 `../03-增量审视与基线刷新记录-20261015.md`。
> **本节更新后，三份设计里已无"待核实/待裁决"类的设计侧阻塞；剩余阻塞全部是环境类（凭据 / redis 镜像），按用户 2026-10-15 指示统一到最后解决。**

### 11.1 §1「未决问题」表的现状（7 项 → 1 项仍待开发答 + 1 项环境）

| 原未决项 | 现状态 |
|---|---|
| `DV066-01` 碰撞触发条件 | ✅ **已答**：Feature §2/§4/§5.1.3 三处均改为「**互审启用时**」 |
| `DV066-02` 碰撞判定机制 | ✅ **已答**：Feature 三处改为「与**派生出的互审成员标识(review_<视角 id>)重名**」；L2 §7.2 同步 |
| `DV066-03` `review` 非法值 | ✅ **已答**：Feature §2「仅 true/false/缺省三种形态合法，其余**装载期拒绝**」+ §5.1.3 新错误行「…**不静默降级**」；代码新增 `parseReviewFlag` |
| `D1` `cross_validation` 带 `steps` | ✅ **已答**：Feature §2「**不得声明 steps 键**——声明即拒绝」+ §5.1.3 新错误行；代码已实现 |
| `L066-03` 投影"按声明序"指哪个序 | ⚠️ **文档仍未答**（已在 #32 评论催答），但**事实层已读码解除**：`BlueprintScheduler.java:235-247` 严格按 `dependsOn()` 序拼接、分隔符 `\n---\n`、缺席不加入 ⇒ 顺序**可断言**；因设计未承诺 ⇒ 只作 `OUT / non-gating` 增强断言 |
| `Q065-D1` 空白判据（继承） | ✅ **已答**：Feature §5.1.3 的 `isBlank` 统一判据 |
| 制品 / 凭据 / 容器 | 制品 ✅ **已就绪**；凭据与 redis 镜像 ⛔ 环境类，统一到最后解决 |

### 11.2 §6 各用例的期望值更正

| 用例 | §6 原文（旧基线） | **更正为（新基线，权威）** |
|---|---|---|
| **066-S09** | 只构造 2 种"两侧结论一致"的形态，另 2 种 `unassigned` | **4 种形态全部可判定**（已按此重写 §6 该条）：① `review: true` + 视角含 `review_a` 且另有视角 `a` → **拒**；② `review` 缺省 + 视角 `review_x` → **纳**；③ `review: true` + 视角 {`review_x`,`a`,`b`}（无视角 `x`）→ **纳**；④ 正常图纸 → **纳**。**②③ 被拒即为 FAIL（过度拦截）** |
| **066-S15** | 主断言 = "装载成功且编译产物无 `r_` 层"（按当时实现的静默降级口径），并登记产品风险缺口 | **主断言完全反转**（已按此重写 §6 该条）：`review` 的映射/列表/非布尔标量/非 true-false 字符串四种形态与 `cross_validation` 多写 `steps` **一律装载期拒绝**，错误消息含**图纸 id 与键定位**（实现消息形态已核实：`must be a boolean scalar (true/false)` / `must not declare 'steps'`），执行体计数 0。**若任一非法形态被静默接受即判 FAIL（静默降级复活 = 回归）** |
| **066-S06** | 顺序子断言标 `partial`，等 `L066-03` 答复 | 顺序**事实已确定**（按 `dependsOn()` 序 = 全部 `p_` 按 `perspectives` 声明序，后接全部 `r_` 按视角声明序；分隔符 `\n---\n`）⇒ 可写顺序断言，但因**设计未承诺顺序** ⇒ 标 `OUT / non-gating`，顺序变化**不判 FAIL**；主断言仍为"4 个标记全部命中且逐字完整" |
| **066-S14** | 只构造单个视角空白；`"   "` 不构造 | `"   "` **解禁构造**（`isBlank` 已入 Feature）；另"全部其他视角均空白"的极端变体也**已解禁**（编译步 `skipIf` 恒 `NEVER` 已核实 + Feature 已明确），期望为"互审步照常执行、输入无上游投影段、审计步照常执行" |
| **066-S11** | 4 个装载拒绝实例 | **可扩为 6 个**：原 4 个（视角空列表 / 单视角 / 缺 `auditor` / 视角 `instruction` 为空）+ 新增 2 个（`review` 非法形态、`cross_validation` 带 `steps`）——后两个与 066-S15 共用断言实现，覆盖台账分别登记 REQ-013/014 与 REQ-011 的承接 |
| **066-S16** | `blocked`，原因含"制品未构建" | 制品 ✅ 已就绪；仍 `blocked` 的**只剩环境类**（`~/.llmrc` + redis 镜像），统一到最后解决 |

### 11.3 缺口状态（与 §4.1 一致，此处为速查）

GAP-066-01/03/04/05 **关闭**；GAP-066-02 **收窄为 non-gating**；GAP-066-06 仍 `blocked`（纯环境）；**GAP-066-07 已撤销 partial（2026-11-18 复审裁决）**——大小写变体属非法形态（066-S15 负向承接）；实现若接受记开发缺陷。
⇒ **FEAT-066 的 15 个 L1/静态场景已无设计侧阻塞**。
