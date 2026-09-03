---
scope: v0815
deployable_units: [agent-runtime, agent-core]
sut: edp-agent-engine（EDPAgent，本地 managed 单节点，同 process 挂 search-agent / verify-agent 被委托方）
features: [FEAT-028]
updated: 2026-09-02
---

# FEAT-028 — EDPA 场景下子任务并行验收：用例设计与真机进展

本文件是 `docs/testplan/FEAT-028-edpa-planning-workflow-and-agent-parallel-execution-edpa.md`（方案级测试设计）的**实现层细档**：承载覆盖进度看板、逐条子用例的 G/W/T 与判据、共享前置约定、真机实测进展（滚动记录）与风险备注。

**分层纪律**（用户 2026-08-17 定）：方案文档只锚定稳定的场景条目（矩阵 ID）；测试代码到场景的映射以测试仓当前代码为准，不在方案维护类目录；测试进展、缺陷对时、验证结论统一记录于本文件 §5。

**依赖引用规范**：本细档引用的 FEAT-019 / FEAT-004 / FEAT-006 / FEAT-015 / FEAT-016 / FEAT-027 testplan 均**尚未建立**；在其建立前，引用先回落到 `develop/02-features/FEAT-XXX-*.md` + L2 详设 + 本仓 `docs/cases/FEAT-XXX-*.md`（如存在）。testplan 补齐后 grep 「TBD」批量刷回。

## 1. 覆盖矩阵

> **对账基准**：本表按 testplan 方案矩阵的场景 ID（`develop/04-testplan/FEAT-028-*.md` §5，**18 条**稳定契约：A1 · P0a · P0b · P0c · P1~P6 · **P5b** · C1~C3 · N1 · N2 · S1 · R1，其中 P0b/P0c 已 out-of-scope 但条目保留；**P5b 于 2026-09-02 新增**——原 P5 拆为「功能正确性（P5，BLOCKING 硬判据）」+「反证（P5b，SSE 告警级，⬜ 待建）」，见 §3.4 拆解说明）逐条对账——场景条目固定，代码落点随测试仓演进。
> **不计入 18 条的引用登记行**：testplan 矩阵末尾另有一行 ~~R2~~（终态 Task 重订阅回退），性质是**指名承接方而非本方案用例**——已由 FEAT-001 `TaskResubscribeTest` 的 **E4** 覆盖，不在本表统计口径内（见 §4.3 上游依赖）。
> **图例**：✅ 已建已验；🔄 代码已落但**当前判据下尚未真机验证**（判据重写/重定位后，旧绿灯不继承）；🟡 partial / 半建 / red-first 设计内；⬜ 待建。最新真机进展与缺陷对时见 §5（滚动记录）。

### 1.1 覆盖进度看板

| 矩阵 ID | 场景 | 状态 | 落点与备注 |
|---|---|---|---|
| A1 | EDPAgent Agent Card 声明并行调度能力真实性 | ✅ | `EdpaAgentCardAlignmentTest`（2026-08-24 PASS：name=edp-agent-engine、streaming=true、skills 合规） |
| P0a | 入口 Task 唯一性与状态机单调收束 | ✅ | `EdpaEntryTaskUniquenessTest`（2026-08-24 PASS：分阶段启动，状态序列 WORKING→COMPLETED 单调；未观察到 SUBMITTED 属实现事实） |
| ~~P0b~~ | ~~WORKING 期间快照承载并行进展~~ | ⬜ out-of-scope | `EdpaSnapshotBatchProgressTest`（**排除依据：特性档 §5.0.1 可见性边界**——「数据面透传仅在父 Task 调用模式为 STREAMING 时生效；BLOCKING / ASYNC 模式下远端使用 `SendMessage`，不产生中间流式事件……这是 A2A 协议设计约束，非缺陷」，`updated: 2026-08-13`；2026-08-24 设计与开发确认当期不实现，作**佐证**。*2026-09-02 更正依据来源：原文只引会议确认，属口头裁定——书面条款可复核可追溯，会议结论会因人员变动失效。* 用例加 `@Disabled` 注解归档保留，特性档刷新该边界后按新契约面复审）|
| ~~P0c~~ | ~~COMPLETED 快照的独立溯源痕迹~~ | ⬜ out-of-scope | `EdpaTerminalSnapshotTraceabilityTest`（同 P0b out-of-scope；终态 artifacts 承载 final_answer 由 A1/P0a 基础契约覆盖；`@Disabled` 归档保留）|
| **P1** | 同类型批量并行（同步阻塞）<br>*（2026-09-02 判据重定位：并行举证责任移交 P3）* | 🔄 | `EdpaHomogParallelBlockingTest`（**判据改为纯功能正确性**：终态 COMPLETED + final_answer 覆盖两个独立主题。**删除**旧硬 2「总耗时 < 90s ⇒ 并行生效」——时长与并行无因果关系，且超限走 `assumeTrue(false)` 永远红不了，是漏红；**删除**「BatchTimingObserver 观察面待 P0b 承载位钉死后升级」——该对象在 P1 里从未 `record()` 过，是死代码。**🔄 = 新判据下未真机重跑**。历史 2026-08-24 PASS（旧判据）：达终态、两件事覆盖、总耗时 65s）|
| **P2** | 异构混合并行（同步阻塞）<br>*（2026-09-02 判据重定位：异构归位举证责任移交 P4 硬 3）* | 🔄 | `EdpaHeteroParallelBlockingTest`（**判据改为纯功能正确性**：终态 COMPLETED + final_answer 覆盖 search 腿主题与 verify 腿结论。**删除**旧「< 90s 启发式」（同 P1）与「断言两子委托 agent_name 分别为 search/verify」（过程量，BLOCKING 无投影）。**🔄 = 新判据下未真机重跑**。历史 2026-08-24 PASS（旧判据）：final_answer 明确「并行完成两项独立任务：搜索...核查...」，异构两腿都真实调用，65s）|
| **P3** | 同类型批量并行（SSE） | 🔄 | `EdpaHomogParallelStreamingTest`（**2026-09-03 增补合并实体 FAIL 分支，待真机复跑**：原 `delegation < 2` 时无条件 `assumeTrue(false)` 判 INCONCLUSIVE，等于把 EDPA L2 §7.3「模型合并多个实体 → 验收判失败」这一行**整行漏掉**；现改为先按 `EdpaMergedEntityJudge` 三值分流，`MERGED` 判 FAIL、`SINGLE`/`UNDECIDABLE` 才降 INCONCLUSIVE。落码中发现的 wire 事实与设计推翻见 §5.11。**2026-09-02 新判据下重跑 PASS**：SSE 2142 帧、totalElapsed=55784ms < 90s；agentEvents=1163（delegation=4 / output=1147 / status=12），`unknownTypes=[]`、`missingSource=0`、分流键去重 5 组，硬 1/硬 2 均真判。历史 2026-08-24 PASS：SSE 15959 帧、62.5s < 90s 启发式，两件事覆盖完整、终态帧到位——**但当时代码只统计外层 `statusUpdate/artifactUpdate.taskId`，`agentEvent` 面根本没断言**。2026-09-02 补硬 1（`type` 闭集 + `source` 二元组非空）与硬 2（分流键去重 ≥ 2 且非 delegation 事件的 `source.taskId` ≠ 父 taskId），旧绿灯不能代表新判据，须重跑）|
| **P4** | 异构混合并行（SSE） | 🔄 | `EdpaHeteroParallelStreamingTest`（**2026-09-03 同 P3 增补合并实体 FAIL 分支，待真机复跑**；异构侧主题词集取「动作意图」而非实体名——P4 两件事都围绕虚拟线程，用实体名会两边恒命中、判据退化成恒红，见 §5.11。**2026-09-02 新判据下重跑 PASS**：SSE 2054 帧、totalElapsed=58584ms < 90s；agentEvents=1036（delegation=4 / output=1020 / status=12），`sourceAgentIds=[edp-agent-engine, search-agent, versatile-agent]` 满足硬 3「去重 ≥ 2」，`unknownTypes=[]`、`missingSource=0`。历史 2026-08-24 PASS：SSE 11398 帧、78s < 90s 启发式，异构主题覆盖。2026-09-02 同 P3 补硬 1/硬 2，另加硬 3「`source.agentId` 去重 ≥ 2」，须重跑）|
| P5 | 依赖型场景端到端正确性<br>*（2026-09-02 重定位，原题「反证：有依赖任务禁止并行」）* | 🔄 | `EdpaDependentTasksSerialTest`（**判据改为纯功能正确性**：终态 COMPLETED + final_answer 同时出现「搜索主题」与「核查结论」。**删除**旧硬断言 `total >= 40s`「否则判伪并行」——串行也可能 <40s、并行也可能 >40s，用 `assertThat` 会**误红**，比漏红更坏。反证立意移交 P5b。**🔄 = 新判据下未真机重跑**。历史 2026-08-24 PASS（旧判据）：total=87s）|
| **P5b** | 反证：有依赖任务不得同轮伪并行（SSE，**告警级**） | ⬜ | **待建**（2026-09-02 从 P5 拆出）——判据：两条 delegation 时间窗**不重叠**（`BatchTimingObserver.timeWindowsOverlap()==false`）且第二条轨迹出现在第一条终态 `status` 之后。**命中重叠只判 INCONCLUSIVE + 显式告警，不得判 FAIL**：它判的是模型行为而非 SUT 代码行为，单次采样不构成 planrule 违约证据。升级为 FAIL 需先建多次采样统计口径。详见 §3.4 |
| P6 | 单实体单委托兼容 | 🔄 | `EdpaSingleEntityCompatTest`（**判据改为纯功能正确性**：终态 COMPLETED + final_answer 覆盖单一主题。**删除**旧硬 2 `total <= 90s`「超限可能被误批量化」——误红源，模型/网络/下游慢都能触发，与「是否被误批量化」无关。**🔄 = 新判据下未真机重跑**。历史 2026-08-24 PASS（旧判据）：total=38.9s）|
| C1 | 同批多委托原子性（组合面） | ✅ | `EdpaBatchAtomicityTest`（**2026-09-02 新判据下重跑 PASS**：SSE 2293 帧、agentEvents=1025（delegation=4 / output=1009 / status=12），硬 A-0~A-3 全真判（`unknownTypes=[]`、`missingSource=0`、4 条 delegation 的 `target.taskId` 两两不同、member 去重 ≥ 2）+ 硬 B `coversSearch=true coversVerify=true`。历史：2026-08-24 定性更正后 PASS——FEAT-019 特性档 §3.1「参考批量中断形态」明确 `batchId` 是 core/runtime 内部诊断标识不对客户端可见，原 red-first 判据错读，当时改为「SSE toolCallId ≥ 2 且互不重复」+「最终 artifact 覆盖两件事」双证，实测 toolCallId=4 个、5270 SSE 帧。**2026-09-02 判据再次重写**：`toolCallId` 同为 MAY 级扩展字段，硬 A 改按 delegation 边判定——A-0 `type` 闭集 + `source` 二元组非空、A-1 `target` 逐条非空、A-2 `target.taskId` 两两不同、A-3 去重 ≥ 2 个 member，硬 B 提到最前先判；更正依据见 §5.5.3）|
| C2 | all-settled 单次推理恢复（组合面） | 🔄 | `EdpaAllSettledSingleRecoveryTest`（**硬 1 已落码已验、现降级为看守**：2026-08-24 PASS，终态 statusUpdate 帧恰好 1 次；**硬 2 已于 2026-09-03 落码，待真机复跑**——⚠️ **判据形态在落码时被推翻**：原设计「最后一条子回程之后父段恰好 1 段」**本身也是恒真的**（按定义最后一条子回程之后不再有子段，剩余帧必然连成一段），改判为「**每个父段起点处，已派发 − 已回程 = ∅**」，全文见 §5.11。**2026-09-02 降级理由**：现有硬 1 是**近乎恒真的弱必要条件**——A2A 状态机本就保证父 Task 只有一个终态帧，逐成员触发推理恢复同样只产生一个终态帧，它挡不住本条真正要防的失效形态。原「✅ 组合契约实证」的表述过强，已撤回；判据设计见 §3.5 C2）|
| C3 | 批次归位的端到端可观察：每个委托都有回程（组合面）<br>*（2026-09-02 判据重写，原题「toolCallId 稳定归位」）* | ✅ | `EdpaDelegationReturnBindingTest`（原 `EdpaToolCallIdStableBindingTest`，2026-09-02 随判据一并改名）（**2026-09-02 新判据下首跑 PASS**：SSE 1896 帧、agentEvents=834（delegation=4 / output=818 / status=12），4 条 delegation 全部有回程、分流键 5 组、`unknownTypes=[]`、`missingSource=0`。**2026-09-02 判据重写**：原「每个 `toolCallId` 平均出现 ≥ 2 次（tool_call + tool_result）」整条删除——wire 上不存在 `tool_result` 事件类型，`toolCallId` 为 MAY 级扩展字段；新判据按 `(source.agentId, source.taskId)` 二元组分流 + 每条 delegation 有回程；历史 FAIL 记录见 §5.6/§5.7，更正依据见 §5.5.3）|
| **N1** | 协同模式字段泄漏回归看守 + 批量语义正向实证<br>*（2026-09-02 重定位，原题「⭐ 越界约束：envelope 不含协同模式字段」）* | 🔄 | `EdpaCoordinationModeLeakGuardTest`（判据①看守：SSE+流后快照+进程日志三面扫叶子字段名；判据②正向：日志按 `batchId` 聚合，≥1 批含 ≥2 成员）+ `EdpaModeFieldScannerSelfTest`（金丝雀，非 `manual`，4 条）。**🔄 = 代码已落、金丝雀 4/4 绿且经变异验证，但重定位后的主用例尚未真机重跑**（旧类的 ✅ 是假绿，不继承）。⚠️ **不验证 §2.2**——envelope 属 core→runtime 内部面且 FEAT-019 §3 不固定序列化字段，「不含某字段」SIT 不可判定，正面举证在 agent-core 白盒单测；详见 §5.10 |
| **N2** | ⭐ 越界约束：agent-core 不直连 registry | ⬜ | **待建（§2.2 主权、red-first 看守）**——依赖 registry 侧观察器，缺失时降 INCONCLUSIVE |
| **S1** | ⭐ 数据面/控制面分离 | 🔄 | `EdpaDataControlPlaneSeparationTest`（**硬 1 已落码已验**：2026-08-24 PASS，数据面 llm_reasoning 流 37451 字符、控制面 final_answer 32407 字符，两条通道都有内容；**硬 2 已于 2026-09-03 落码、关键词断言同步降级为诊断日志，待真机复跑**：`C != D_sub` 且 `C` 不是 `D_sub` 的连续子串，`D_sub` **必须按 `source.agentId ≠ 父` 过滤**否则恒红。**2026-09-02 降级理由**：本用例当前唯一的内容级硬断言是**关键词检查**（`containsAny(controlText, "【结果汇总】", "汇总", "综上", ...)`）——而这恰是 C2 在同一天真机后**明确推翻**的判据形态（planrule 建议格式非硬约束，模型有自由度不遵守）。同一理由对 S1 同样成立，故该断言降为诊断记录，在硬 2 落码前其变红不构成缺陷证据。原「两者显著不同 → 非机械拼接」的推断也不成立：字符数不等推不出「不是拼接」。判据设计见 §3.7）|
| **R1** | ⭐ SubscribeToTask 重订阅——首帧快照 + 后续事件应看到子任务 | ✅ | `EdpaSubscribeToTaskResubscribeTest`（**2026-08-24 首跑 PASS**——SubscribeToTask HTTP 200 + Content-Type=text/event-stream；首帧=父 Task 快照（taskId 一致，state=WORKING）；重订阅流 2560 帧全字段扫描命中：**子 taskIds=2**、**子 agentId=`search-agent`**、**子 state=`submitted`/`working`（路径 `agentEvent.state`）**——三通道全绿；**重大发现**：之前 issue #93 追加评论关于 SSE state 全空的说法**过强**，仅 `source.state`/`target.state` 为空，`agentEvent.state` 平级承载了子任务生命周期 state；详见 §5.5.2）|

**台账快照（2026-09-03 第四轮：C2 硬 2 / S1 硬 2 / P3P4 合并实体分支落码后）**：18 条 = ✅ 5（A1/P0a/**C1**/**C3**/R1）· 🟡 0 · 🔄 9（**C2/S1**：硬 2 已落码，从 🟡 转 🔄——⚠️ C2 的判据形态在落码时被推翻，见 §5.11；C2 另见 §5.7 新 jar 下 FAIL 待深挖。**P3/P4**：从 ✅ 转 🔄，本轮补了合并实体 FAIL 分支，旧绿灯只覆盖硬 1/2/3。**P1/P2/P5/P6**：2026-09-02 删除时长启发式、判据改为纯功能正确性，代码已落但新判据下未真机重跑。**N1**：重定位后代码已落、金丝雀绿，主用例待真机重跑）· ⬜ 4（**P5b** 待建 + N2 registry 可选 + P0b/P0c out-of-scope 归档）。**合计 5+0+9+4=18，与对账基准一致**。**本轮新增第二只金丝雀 `EdpaJudgeSelfTest`（11 条全绿，非 `manual`，CI 常驻）**——它不是矩阵条目，不计入 18，但 C2/S1/P3/P4 的判读必须以它同轮为绿为前提。*（2026-09-02 上一版快照为「✅ 7 · 🟡 2 · 🔄 5 · ⬜ 4」；本轮 P3/P4 由 ✅ 转 🔄、C2/S1 由 🟡 转 🔄，故 ✅ 7→5、🟡 2→0、🔄 5→9。）***P3/P4/C1/C3 四条已于 2026-09-02 在新 `agentEvent` 判据下重跑全绿**（记录见 §5.8），旧判据下的绿灯不再被引用。**FEAT-028 主线现状**（2026-09-02 第二轮重定位后重新表述）：并行同/异构 × 同步/SSE 双模式在**功能正确性**维度全覆盖；**「真并行」的正面举证责任现集中在 P3/P4 的 SSE 判据**（`(source.agentId, source.taskId)` 去重 ≥ 2 组 + `source.agentId` 异构 ≥ 2 值），这两条已在 2026-09-02 新判据下重跑全绿——**这是本特性「并行」二字唯一的硬证据**。批次原子性（C1）、SubscribeToTask 重订阅（R1）两条主权/组合契约已实证。*（2026-09-02 更正：原文把**数据面/控制面分离（S1）**与 **all-settled 单次恢复（C2）**一并列入"已实证"——现已撤回。这两条各自唯一的内容级硬断言分别是「关键词检查」与「终态帧数=1」，前者是 C2 自己在 2026-08-24 推翻过的形态，后者近乎恒真；它们的绿灯只证明「通道有内容 / 状态机正常」，不构成对应契约的实证。二者当时降为 🟡；**硬 2 已于 2026-09-03 落码，现转 🔄 待真机复跑**，见 §5.11。）***⚠️ 表述更正**：原文此处写「planrule 依赖判定（P5）+ 单成员兼容（P6）**反证成立**」——该说法已撤回。P5/P6 的旧绿灯来自时长启发式，与 planrule 契约无因果关系，**从未构成反证**；反证在 P5b，⬜ 待建，且建成后也只是告警级。当前状态：**并行「能不能做到」已证，「会不会判断」未证**。**越界约束（N1）不在这份"实证"清单内**——它是回归看守而非合规判据，其绿灯不构成 §2.2 实证（见 §5.10）。**R1 首跑重大发现**：SSE `artifactUpdate.artifact.metadata.agentEvent` 结构**承载子 taskId + 子 agentId + 子 state 三种子任务信息**（子 state 值集合 `{submitted, working, ...}`），即"客户端应能观察到子任务粒度"这一诉求在**SSE/重订阅通道已具备实现基础**；之前 issue #93 追加评论关于"SSE state 全空"的说法**过强**——遗漏了 `agentEvent.state`（与 `source.state`/`target.state` 平级）。**⚠️ issue #93 的四条原始诉求现已全部消解**：①batchId 可见性——撤回（§5.5）；②SSE state 全空——撤回（§5.5.2）；③WORKING 快照全空（P0b）+ 终态无 toolCallId 溯源（P0c）——依据特性档 §5.0.1「BLOCKING/ASYNC 下 `GetTask` 终态快照不含中间流式，这是 A2A 协议设计约束，非缺陷」，已 out-of-scope（2026-08-24 设计与开发确认作佐证）；④tool_result 侧无 toolCallId 归位（C3）——撤回（§5.5.3：wire 上不存在 `tool_result` 事件类型，`toolCallId` 属 MAY 级扩展字段）。**issue #93 已关闭**（用户 2026-09-02 确认）——最终结论是**改特性文档描述，不改代码**，即这批诉求全部落在文档口径侧，SUT 行为无需变更。因此本细档中所有引用 issue #93 作为"待修缺陷"的表述均只作历史过程记录，不再构成开发跟进项。**P7 混合终态 / 接续场景已退出 FEAT-028 范围**（2026-08-24 设计团队确认；归档见 §5.2.3）。**P0b/P0c out-of-scope**：`EdpaSnapshotBatchProgressTest` / `EdpaTerminalSnapshotTraceabilityTest` 加 `@Disabled` 注解归档保留；SSE / SubscribeToTask 通道下的子任务粒度可见性（**P3/P4/R1** 已实证）不受影响。*（2026-09-02 更正：原写「P1~P4/R1」——P1/P2 走 BLOCKING，正是 §5.0.1 判定「无中间流式事件」的那条通道，它们不可能实证子任务粒度可见性。）*

**下一步优先级**：

1. **P0**（依赖钉死 wire 事实，其他用例基础）：A1 探测 Card（`capabilities.streaming` / `skills` / `card.version` 版本指纹）→ P0a 建父任务呈现契约的观察面。*（2026-09-02 两处更正：①删除「A1 探测 remote-agents 一致性」——`remote-agents` 是 EDPAgent 的**入站配置**，Agent Card 上无对应投影，该判据从未落码，属**幽灵判据面**；下游 agentName 集合的正确性由 P2/P4 的 `source.agentId` 异构断言间接覆盖。②删除「P0b 首轮真机 dump 快照结构、钉死承载位」——P0b 已 out-of-scope，C1/C3 的稳定参考已改为 FEAT-019 L2 §5.4 的客户端调用图坐标系，不再依赖 P0b 的 dump 结论。）*
2. **P1**（Smoke 关键）：同类型批量并行同步阻塞——端到端功能正确性冒烟（终态 + 两主题覆盖）。*（2026-09-02 更正：原文写「产出 `BatchTimingObserver` 的时间窗证据结构」——BLOCKING 通道拿不到子任务时间戳，该产出物在 P1 不成立，时间窗证据的产出方是 P3/P4/P5b 的 SSE 面。）*
3. **P2~P4**（并行主线扩展）：异构 + SSE 两个维度扩展。**P3/P4 是「真并行」的唯一硬证据来源**，优先级实际高于 P1/P2。
4. **P5/P6**（依赖场景与兼容）：依赖型端到端正确性 + 单成员兼容；**反证 P5b 单独排期**（SSE 面、告警级，见 §3.4）。
5. **C1~C3**（组合契约面）：可与 P1 复用同一次真机 run，同时采集不同断言维度。
6. **N1/N2**（越界看守）：N1 打 `manual`（复用 P3/P4 拓扑、需真实 LLM 与 ~130s 流式采集），其金丝雀 `EdpaModeFieldScannerSelfTest` 不打 `manual` 随每轮构建跑；N2 依赖 registry sniffer，可能长期 INCONCLUSIVE 等观察面建成。
7. **S1**（数据面/控制面分离）：SSE 模式下的额外断言，可与 P3/P4 复用真机 run。
8. **跟修 / 对齐**：N1 若命中协同模式字段须**先人工定性**（合法→加白名单并注明出处；越界→按 §2.2 提缺陷），不得直接判定违约；LLM 未同轮生成时的 prompt 优化。*（2026-09-02 删除「P0b 承载位钉死后与开发对齐字段口径是否合规」——P0b 已 out-of-scope，无承载位可钉。）*

## 2. 前置条件与共享约定

### 2.1 SUT 部署前置

- `edp-agent-engine-0.1.0.jar` 在 `D:\agent-solution-common\dist\`（2026-08-20 首次接入版本）；本地 managed 通过 `SutStack.agent("edp-agent")` 启动。
- 两个被委托方 `agent-search` / `agent-verify` 同 dist 目录；本地 managed 拉起后 baseUrl 注入 EDPAgent 的 `EDP_AGENT_SEARCH_A2A_URL` / `EDP_AGENT_VERSATILE_A2A_URL` 环境变量。
- 真实 LLM 密钥通过 `EDP_AGENT_MODEL_*` 环境变量注入，遵守既有 SIT 密钥管理规范（`/tmp/sit-secrets.env`，不落库）。
- 本方案**不启用业务场景**：`EDP_AGENT_SCENARIO_HOME` 设为不存在路径或空，EDPAgent 走内置 `planrule.yaml`（jar 内 `governance/planrule.yaml`）的通用并行规划路径。
- `openjiuwen.service.a2a.remote-invocation.max-concurrency=16`（jar 内置默认），足够支撑本方案的同轮 2~3 委托并发。
- `versatile-agent` 在配置中是必填 remote-agent（无默认 URL），用不可达 dummy URL（如 `http://127.0.0.1:1`）满足启动即可，本方案不使用该下游。

### 2.2 共享测试基础设施

| Fixture | 复用/新建 | 职责 |
|---|---|---|
| `SutStack` | 复用 | 本地拉起 / 停止 EDPAgent + search + verify；提供 baseUrl 注入与生命周期管理。 |
| `A2aServiceClient` / `A2aEventCollector` | 复用 | A2A SDK 驱动 `SendMessage` / `SendStreamingMessage` / `GetTask`；收集 SSE 事件、等待终态、抽取 artifact。 |
| `A2aHttpProbe` | 复用 | 底层 HttpClient 直发 JSON-RPC，绕过 SDK 断言 wire 层表面。 |
| `EdpaSseCollector` | 已建（本包内） | EDPA 专用 SSE 采集器：`collect(http, a2aUrl, requestBody, capMs)` 直发 `SendStreamingMessage`，按帧收集 `Frame(ts, rawData, parsed, kind)`（`parsed` 在 JSON 解析失败时为 `null`，用例须判空），并以 `capMs` 作采集上限截断。P3/P4/C1/C3/R1/N1 的 SSE 取证均经此入口。**补登记于 2026-09-02**——此前一直在用但漏登（T-S 类遗漏）。 |
| `BatchTimingObserver` | **已实现，当前无用例引用**（2026-09-02 复核）| 按 key（推荐 `agentEvent.source.taskId`）聚合每条子委托轨迹的首/末时间戳，输出「时间窗是否重叠」证据（`max(start_i) < min(end_i)`）。**不以 `toolCallId` 为分流键**（见 §5.5.3）：FEAT-027 §5.6 指定的分流依据是 `source.agentId + source.taskId`，`toolCallId` 是 MAY 级扩展字段，用它作分流键会在实现侧按 spec 停止透出时整条观察面失效。<br>**现状说明**：此前唯一引用在 P1，但那里是 `new` 出来立刻打一行空 `summary()`、从未 `record()` 过任何事件——P1 走 BLOCKING，特性档 §5.0.1 明写该模式不产生中间流式事件，这条通道上**根本没有子任务时间戳可喂**。该死代码已于 2026-09-02 删除。<br>**保留理由**：它实现的正是 **P5b（⬜ 待建）** 所需的算法（P5b 判据恰是其否定——依赖型场景两条 delegation 的时间窗**不应**重叠）。若 P5b 最终不建，本类应一并删除。<br>**纪律**：在用例真的调用 `record()` 之前，**不得把它写进任何用例的「观察面」栏**。 |
| `EdpaAgentEventScanner` | **新建**（2026-09-02） | P3/P4/C1/C3 的**判据面**入口，与下方 `EdpaChildVisibilityScanner`（对照面）并用：按 FEAT-027 §3.1 契约把 `Artifact.metadata.agentEvent` 解析成 `(type, source.agentId, source.taskId, target.agentId, target.taskId, state)` 结构体，输出 ①`type` 闭集越界值集合（**`type` 缺失以哨兵值计入，与取值越界同级**——§2「控制与业务语义区分」MUST 要求用 `type` 区分类型）、②`source` 二元组缺失事件列表、③delegation 中 `target` 缺失 / `target.taskId` 重复的两个事件列表、④按 `source.taskId` 分流的到达序号窗口（**仅诊断**，§2 明文不依赖跨生产者到达顺序）。**逐条断言必须用 ③ 的两个列表，不得用「去重后 target 集合大小 ≥ 2」代替**——`Set` 会静默跳过空值并折叠重复值，3 条 delegation 里混 1 条空 target 或 2 条同 target 都能蒙混过关。 |
| `EdpaToolCallArgumentsAssembler` | **新建**（2026-09-03） | P3/P4 合并实体判据的**前置观察面**。实测 wire 事实：`tool_calls[].arguments` 是 **OpenAI 风格 token 级增量分片**，路径 `result.artifactUpdate.artifact.parts[].data.payload.tool_calls[]`（`data.type == "llm_output"`）——首片带 `name`/`id` 而 `arguments` 为空串，后续片各带几个字符（实测首帧 `name="search-agent"`、`arguments=""`、`id="call_84e0…"`，次帧 `name=""`、`arguments="{"`）。**不跨帧重组，任何内容级判据都恒不开火**（漏红）。重组键 `(payload.task_id, tool_calls[].index)`；`tool_calls` 用递归查找而非硬编码路径。取不到参数返回空列表，由调用方判 INCONCLUSIVE **不得判 FAIL**——`tool_calls` 是 payload 业务内容而非 FEAT-027 wire MUST（FEAT-029 §1/§3.1：agent-runtime 只透传 payload）。 |
| `EdpaMergedEntityJudge` | **新建**（2026-09-03） | 落 EDPA L2 §7.3 错误表面验收表那一行。**三值**：`MERGED`（**单个** ToolCall 的 arguments 同时命中主题甲、乙两个词集 → FAIL）/ `SINGLE`（各 ToolCall 各命中单主题 → 容忍）/ `UNDECIDABLE`（无参数可读）。判红时回传命中词 + 参数原文（截断 800 字符）**供人工复核**。SINGLE 分支下若「≥2 个带参 ToolCall 但 wire delegation < 2」会附一条【注意】提示通道不一致，**但不断言**——delegation 计数不是本判据的观察面。 |
| `EdpaRecoverySegments` | **新建**（2026-09-03） | C2 硬 2 与 S1 硬 2 的共享入口，内部调 `EdpaAgentEventScanner`。只对 `artifactUpdate` 帧分类：无 `agentEvent` → 父段帧；含非 delegation 事件且 `source.agentId ≠ 父` → 子段帧；**仅含 delegation → 控制帧、透明跳过**（§3.1 规定 delegation 的 source 本就指向父，当父段帧会在跨轮场景凭空切段 → 误红，也会割断两个子段 → 漏红）。父 agentId 取自全部 delegation 的 `source.agentId`，恰好一个才可判。输出 ①每个父段起点处「已派发 − 已回程」差集的违规实例（C2 硬 2）、②`childPlaneText()` = 按 `source.agentId ≠ 父` 过滤后的子段文本 `D_sub`（S1 硬 2）。**四个不可判定出口**：无 delegation / `source.agentId` 不唯一（嵌套委托）/ delegation 缺 `target.taskId` / **全程无子任务终态 status**（否则「已回程」恒空 → 恒红）；另有「已派发集与终态回程集完全不相交」的一致性守卫。**主循环先判违规、后吸收本帧事件**——父段起点的「当时」状态不含本帧自身。 |
| `EdpaJudgeSelfTest` | **新建**（2026-09-03，金丝雀） | 上面三个组件的看守自检，11 条，**非 `manual`、不起 SUT、毫秒级**。合成 wire 数据双向钉死：arguments 跨帧重组、合并实体三值各自可达、四组主题词集**逐词互斥**、C2 对「甲回程即恢复、乙在途」能开火、C2 对「跨轮追加委托」不误红（并断言该轨迹确有 ≥2 个父段，否则该测试什么都没守住）、缺终态 / 无 delegation 判不可判定、`D_sub` 过滤掉父自身输出、父 agentId 不唯一时返回 `null` 不猜。**首跑即自证有效**：初版合成 JSON 少闭合一层花括号，4 条直接 error，改对后 11/11 绿。 |
| `EdpaChildVisibilityScanner` | 已建（2026-08-24） | **对照面**：按字段名做扁平全字段递归扫描，输出「子 taskId / 子 agentId / 子 state / toolCallId」四集合，**刻意不预设承载位**（回答"客户端能不能看到子任务信息"）。前三集合可作硬判据；**`toolCallId` 集合自 2026-09-02 起仅作观察记录，不得单独构成判据**。与上一行职责不同，不合并——合并会毁掉本行"不预设承载位"的优点。 |
| ~~`ToolCallSequenceObserver`~~ | ~~新建~~ **已删除（2026-09-02）** | ~~记录 SSE 事件流中 ToolCall 序列，支持「同轮 ≥2 ToolCall」与「跨轮次串行」判定~~。**删除原因（双重不可建）**：①**全仓从未存在该类**，却被 §3.4 P5 与 testplan §5/§6/§7 当作已有观察面写进判据栏——是幽灵判据面（T-M15）；②**按其描述也建不出来**：wire 上不存在 `tool_call` / `tool_result` 事件类型，FEAT-027 §3.1 把 `agentEvent.type` 钉死为闭集 `delegation | output | status`，「推理轮次」是 agent-core 内部概念，黑盒面没有投影。P5 的反证需求改由 **P5b（⬜ 待建）** 用 `BatchTimingObserver` 在 SSE 面承接，判据是 delegation 时间窗而非 ToolCall 序列。 |
| ~~`SnapshotDiffProbe`~~ | ~~新建~~ **已失效（2026-09-02）** | ~~高频 `GetTask` 轮询父任务快照，diff 出承载子任务并行进展的字段位（P0b 首轮探测工具）~~。**整条 fixture 只为 P0b 存在，而 P0b 已 out-of-scope**（特性档 §5.0.1）——从未建过，也没有任何用例引用。**纪律**：在 P0b 复活之前，不得把它写进任何用例的「观察面」栏。 |
| `RegistrySniffer` | **可选新建** | 在 registry-discovery-center 侧插桩观察调用来源；缺失时 N2 降 INCONCLUSIVE。 |
| `EdpaModeFieldScanner` | **新建**（2026-09-02） | N1 判据①的判定入口：递归遍历 JSON，**只对叶子字段名**做黑名单判定（精确集 ∪ `*mode`/`*_mode` 后缀）；另提供日志 `key=` 键名扫描（同名去重）。白名单 `WHITELIST_LEAF` **也只对叶子名生效**（祖先节点名不赦免后代字段——这是 2026-09-02 修掉的真实缺陷），当前为空，新增条目须注明合法出处（T-M21）。**性质是黑名单**：绿灯只代表「未出现已知命名形态」，不能证明「不存在」。抽为独立 helper 的原因是让金丝雀能在不起 SUT 的前提下验证它。 |
| `EdpaModeFieldScannerSelfTest` | **新建**（2026-09-02，金丝雀）| 上一行的看守自检，**不打 `manual`**、不起 SUT、毫秒级，随每轮构建跑。N1 主用例恒绿，若无此自检就无法区分「没泄漏」与「扫描器坏了」。已做变异验证：把 `isForbiddenLeaf` 强制 `return false` 后 4 条中 3 条转红并给出预期诊断。 |
| `ManagedSutInstance.logFile()` | 复用（**灰盒**）| N1 判据②的唯一观察面、判据①的第三个扫描面。日志以 `appendTo` 跨轮次累积到 `<logDir>/<agent>/stdout.log`，**必须在发请求前记字节偏移、事后只读新增段**。本仓已有先例（`RegistryRouteQueryBlackboxTest` / `RedisStandaloneBehaviorTest` 等）。日志格式**非契约**，缺失判 INCONCLUSIVE。 |

### 2.3 共享命名约定

- **contextId**：`ctx-feat028-<slug>-<uuid8>`（例 `ctx-feat028-homog-parallel-a1b2c3d4`），slug 与用例矩阵 ID 对应。
- **prompt 库常量**：定义在 `EdpaParallelPrompts.java`（新建）；每个 prompt 对应一个矩阵 ID，硬编码 planrule.yaml 期望的触发形态。
- **class 命名**：矩阵 ID + 场景短名，如 `EdpaHomogParallelBlockingTest`（P1）、`EdpaHeteroParallelStreamingTest`（P4）、`EdpaSnapshotBatchProgressTest`（P0b）。
- **Tag 命名**：`@Tag("integration")` + `@Tag("edpa")` + `@Tag("feat-028")`；LLM 依赖用例挂 `@Tag("manual")`。

## 3. 子用例设计

**说明**：以下逐条子用例的 G/W/T（Given/When/Then）、PASS/FAIL/INCONCLUSIVE 判据草案；首轮真机后按实测事实回写关键 wire 落点（C1 的 delegation 边与 `(source.agentId, source.taskId)` 观察位）。**注**：原文此处还列了「P0b 承载位」——P0b 已 out-of-scope（特性档 §5.0.1），没有承载位需要回写，2026-09-02 删除。**注**：原文此处写的是「C1 batchId 观察位」——`batchId` 按设计就不对客户端可见（FEAT-019 特性档 §3.1），不是需要回写的 wire 落点，2026-09-02 更正（见 §5.5 / §5.5.3）。原文还写「N1 envelope 字段名」待回写——2026-09-02 撤回：envelope 在任何 wire 面上都没有完整投影，没有可回写的字段名（见 §5.10）。

### 3.1 Agent Card 探针

- **A1** `EdpaAgentCardAlignmentTest`（**2026-09-02 判据落到字段与取值，T-M4**）
  - G：EDPAgent 就绪（A1 只探 Card，下游用不可达 dummy URL 即可，不需要真实被委托方）
  - W：`client(EDP_AGENT).getAgentCard()`（SDK 拉 `/.well-known/agent-card.json`）
  - T（四条字段级判据，**均已落码**）：
    - ① `card` 非 null
    - ② `card.name()` **非空白**——**只判存在性，不判取值相等**。实测 SDK 侧 `card.name` 反映的是 `spring.application.name`（`edp-agent-engine`）而非 `openjiuwen.service.a2a.agent-name`（`EDPAgent`），属实现事实；断言 `== "EDPAgent"` 会稳定误红
    - ③ `card.capabilities()` 非 null 且 `capabilities.streaming() == true`——这是 P3/P4/C1~C3/S1/R1 全部 SSE 判据的前置能力，`application.yml` 声明 `streaming: true`
    - ④ `card.skills()` 非空，且每个 skill 的 `id`/`name`/`description` 非空白、`id` 两两不同（被其他 Agent 发现并作为工具调用的基础）
  - **观察记录（不作判据）**：`card.version()`（版本指纹，见 §4 前置）、`capabilities.pushNotifications()`
  - **已删除的原判据**：「`remote-agents` 声明的 `agentName` 集合与 planrule 的 skill_routes 或 `SubagentDelegateRail` 可解析集合一致」——**幽灵判据面**：`remote-agents` 是 EDPAgent 的入站配置，**Agent Card 上没有任何投影**，`SubagentDelegateRail` 更是 agent-core 内部对象；这条从未落码，也无法在 Card 通道落码。下游 agentName 的正确性由 P2/P4 的 `source.agentId` 异构断言（去重 ≥ 2 且含 search/verify 两侧）间接覆盖
  - **已删除的原判据**：「Card 声明的 agent 身份 = `EDPAgent`」——见 ② 的实现事实
  - PASS：①~④ 全绿；FAIL：任一字段缺失或取值违约；INCONCLUSIVE：card 不可达

### 3.2 父任务对客户端的呈现契约（P0 组）

- **P0a** `EdpaEntryTaskUniquenessTest`
  - G：SUT 就绪 + 被委托方就绪
  - W：`SendMessage(PROMPT_HOMOG_PARALLEL)`；持续 `GetTask` 轮询父 taskId
  - T：返回的 result 只含唯一 taskId=P；整个执行期 GetTask(P) 存在且返回同一 Task 表面；status 演进 SUBMITTED → WORKING → COMPLETED 单调不回退
  - PASS：唯一 + 单调；FAIL：出现多 taskId 或状态回退；INCONCLUSIVE：任务未达终态

- ~~**P0b** `EdpaSnapshotBatchProgressTest`~~ / ~~**P0c** `EdpaTerminalSnapshotTraceabilityTest`~~ —— **out-of-scope**
  - **排除依据（书面，2026-09-02 改引）**：FEAT-028 特性档 **§5.0.1「可见性边界」**（`scope: v0815` / `updated: 2026-08-13`）原文——「数据面透传**仅在父 Task 调用模式为 STREAMING（远端 `SendStreamingMessage`）时生效**；BLOCKING / ASYNC 模式下远端使用 `SendMessage`，不产生中间流式事件……`GetTask` 返回终态快照（`status` + `artifacts` + `history`）不含中间流式——**这是 A2A 协议设计约束，非缺陷**。」该条款**早于** 2026-08-24 的口头确认，且可复核、可追溯、不因人员变动失效。
  - **佐证**：2026-08-24 下午设计与开发确认 SendMessage+GetTask 通道下子任务粒度可见性（父任务 WORKING 快照承载子任务并行进展 / 终态按 `toolCallId` 独立溯源）当期不实现；FEAT-028 特性档将由设计人员刷新。*（2026-09-02 更正：原文只引这条会议确认——testplan 用会议结论替上游做裁定属边缘形态，改引书面条款。）*
  - **复审触发条件**：特性档 §5.0.1 该段被删除或改写为「BLOCKING/ASYNC 下亦须承载子任务粒度」时，本方案须复审并解归档这两条。
  - **⚠️ 当期无承接方**：「父任务快照承载并行进展的承载位」这一契约面既不在本方案覆盖、也没有其他 testplan 接手，属**已知覆盖缺口**（见 testplan 附录 A 该行）——不是漏记，是明知而暂缺。
  - **处置**：两条测试类加 `@Disabled("...当期特性档不承诺...")` 注解归档保留，代码不删；台账 §1.1 状态 ⬜ out-of-scope；issue #93 **已于 2026-09-02 关闭**，结论为「改特性文档描述，不改代码」。
  - **保留**：`EdpaChildVisibilityScanner` 全字段扫描判据方法学在 R1/C1/C3 等 SSE 通道用例继续使用（**N1 自 2026-09-02 起不再挂它做辅助诊断**，见 §3.6）；SendMessage / GetTask 基础契约（入口 Task 唯一、状态机单调、final_answer 落 artifacts）由 A1/P0a 覆盖。
  - **历史证据（归档）**：首轮真机 P0b 158 快照 + P0c 终态快照 全字段扫描四集合全空——曾作为 issue #93 严格证据；判据方法学教训见 §5.5.2 与 memory `feedback_full_scan_before_red_first.md`。特性档刷新后按新契约面复审是否需要重新启用。

### 3.3 并行主线（P1~P4）

- **P1** `EdpaHomogParallelBlockingTest`（同类型 · 同步阻塞；**2026-09-02 判据重定位，见 §5.5.4**）
  - G：SUT + search 就绪
  - W：`SendMessage(PROMPT_HOMOG_PARALLEL)` + `GetTask` 轮询至终态
  - T（**单层，全是硬判据**）：达终态 COMPLETED；final_answer 非空且**同时覆盖两个独立主题**（虚拟线程 + ZGC）——说明两条委托的结果都回灌进了统一汇总
  - **观察面边界（必读）**：本用例走 BLOCKING，特性档 §5.0.1 明写该模式**不产生中间流式事件**，GetTask 终态快照不含中间过程（P0b/P0c 已证，正是它们 out-of-scope 的理由）。「同轮生成了几个 ToolCall」「两条子任务时间窗是否重叠」都是**过程量**，本通道上**没有投影**——因此本用例**不断言并行**。并行的正面举证责任在 **P3**（SSE 面，`(source.agentId, source.taskId)` 去重 ≥ 2 组）
  - **已删除的旧判据（勿重蹈）**：①旧「硬 2：`totalElapsed < 90s` ⇒ 并行生效」——时长与是否并行**无因果关系**（缓存命中、模型快慢、下游快慢都能左右它），且旧实现超限时走 `assumeTrue(false)`，**永远红不了**，是漏红不是判据；②旧「观察面：`BatchTimingObserver` 时间窗证据」——代码里该对象 `new` 出来立刻打一行空 `summary()`，从未 `record()` 过任何事件，是死代码（已删）
  - PASS：终态 COMPLETED + 两主题覆盖；FAIL：终态 COMPLETED 但汇总缺主题（有委托腿的结果没回灌）；INCONCLUSIVE：超时未达终态、或终态非 COMPLETED

- **P2** `EdpaHeteroParallelBlockingTest`（异构 · 同步阻塞）
  - 同 P1 结构与全部边界说明；prompt 换 `PROMPT_HETERO_PARALLEL`；覆盖判据换成「final_answer 同时覆盖 search 腿主题与 verify 腿结论」
  - **不断言「异构归位」**（两条委托的 `agent_name` 分别是什么）：同属过程量，BLOCKING 通道无投影。**正面举证在 P4 硬 3**（去重后的 `source.agentId` 含两个不同下游）。原文此处曾写「断言两子委托 `agent_name` 分别为 search-agent + verify-agent」，2026-09-02 删除——该断言在本通道上无法实施，历史上还曾计划「P0b 钉死后按 `toolCallId` 判异构归位」，该路径同样作废（`toolCallId` 非 wire 最小公共字段，见 §5.5.3）

- **P3** `EdpaHomogParallelStreamingTest`（同类型 · SSE；**2026-09-02 判据重写，见 §5.5.3**）
  - G：SUT + search 就绪
  - W：`SendStreamingMessage(PROMPT_HOMOG_PARALLEL)`；收集全部 SSE `statusUpdate` / `artifactUpdate` 事件（wire 形态见 FEAT-001 cases §7）；用 `EdpaAgentEventScanner` 做结构化解析、`EdpaChildVisibilityScanner` 做对照扫描
  - T（分层，判据顺序 = 断言顺序）：
    - **覆盖层（先判）**：达终态 COMPLETED + artifact 内容覆盖两件事。按 testplan §8，这一层在**模型任意规划质量下必须绿**，故必须置于下面 `delegation < 2` 的 INCONCLUSIVE 早退**之前**，否则会被跳过（2026-09-02 修正的早退顺序 bug）
    - **硬 1（FEAT-027 §2「wire 协议最小结构」三条 MUST + §3.1 字段适用性表）**：`artifactUpdate` 的 `Artifact.metadata.agentEvent` 中，`type` ∈ {`delegation`,`output`,`status`} 闭集（**缺失 `type` 同样违约**——§2「控制与业务语义区分」MUST 要求用 `type` 区分、「客户端不得仅依赖 Artifact 文本内容推断事件类型」），且 `source.agentId` / `source.taskId` **均非空**。**缺失即 FAIL，不是「若存在」**
    - **硬 2（FEAT-027 §2「并发交织」MUST + §5.6）**：按 `(source.agentId, source.taskId)` 二元组去重后 **≥ 2 组**，且**非 `delegation` 事件的** `source.taskId` **≠ 父 taskId**（不得用外层父 Task ID 替代生产者 Task ID）。`delegation` **不在此列**——§3.1 字段适用性表规定 delegation 的 `source` 本就指向父 Agent/Task，等于父 taskId 是**正确**行为，不加此限定会把合规实现判红。**前置**：父 taskId 必须已从 `statusUpdate`/`artifactUpdate` 抽到并断言非空，否则「≠ 父 taskId」的判定会空转判绿
    - **到达序号窗口只作诊断输出、不作判据**：§2「并发交织」MUST 明文「不依赖不同生产者之间的到达顺序」——Runtime 按实际观察顺序串行写入同一条 SSE，交错与否受网络与调度影响，不能反推并行度
  - **原判据「两个独立 `toolCallId` 的事件穿插到达」+「若 FEAT-027 数据面激活，`source.*` 能区分轨迹」已作废**（2026-09-02）：前半句用 MAY 级扩展字段当硬判据、且把「到达顺序」当并行证据；后半句把 MUST 字段写成条件项——两处强度恰好写反。且旧代码只统计外层 `statusUpdate/artifactUpdate.taskId`，`agentEvent` 面**根本没断言**，那句「若存在」在代码里表现为"没测"
    - **`delegation < 2` 时的分流（2026-09-03 落码，此前是无条件 INCONCLUSIVE 早退）**：早退前先跑 `EdpaToolCallArgumentsAssembler` 跨帧重组 + `EdpaMergedEntityJudge` 三值判定。
      - `MERGED`（**单个** ToolCall 的 arguments 同时命中主题甲、乙）→ **FAIL**，依据 EDPA L2 §7.3 错误表面验收表：「模型合并多个实体 → 单 ToolCall 参数包含多个独立实体时，视为规划质量问题，**验收判失败**」。断言消息带命中词与参数原文，**首次判红须人工复核**再定性。
      - `SINGLE` / `UNDECIDABLE` → 维持 INCONCLUSIVE。同表另一行「模型串行生成调用」是**容忍**的（INCONCLUSIVE），两行不可混判。
      - **词集在 `EdpaParallelPrompts`**（testplan §5 注：候选集与 prompt 一并集中，不得散落各用例）：`HOMOG_TOPIC_A`＝虚拟线程族、`HOMOG_TOPIC_B`＝GC 族。**两集合必须互斥**，否则单主题命中被误计成双主题 → 误红；金丝雀逐词断言。
  - PASS：覆盖层 + 硬 1 + 硬 2 均绿；FAIL：`type` 越界/缺失、`source` 二元组缺失、非 delegation 事件用父 taskId 顶替、轨迹不足 2 组、**或 `delegation < 2` 且判定为合并实体**；INCONCLUSIVE：SSE 无帧，或 `delegation < 2` 且判定为 `SINGLE`/`UNDECIDABLE`（模型跨轮串行生成，或读不到 ToolCall 参数）

- **P4** `EdpaHeteroParallelStreamingTest`（异构 · SSE）
  - 同 P3 结构与全部分层；prompt 换 `PROMPT_HETERO_PARALLEL`
  - **`delegation < 2` 分流同 P3，但词集换 `HETERO_TOPIC_A`/`HETERO_TOPIC_B`**：取「**动作意图**」词（甲＝`核心特性`/`特性说明`/`官方特性`/`有哪些特性`，乙＝`OOM`/`线程池`/`核查`/`是否准确`/`这个说法`）而**不用实体名**——P4 的两件事都围绕**虚拟线程**，用实体名会两边恒命中，判据退化成恒红（误红）。金丝雀专门断言「『虚拟线程』不得进入任一异构词集」。异构侧判 `MERGED` 还额外意味着 search / verify 两类工具的职责被并进了同一个委托
  - **追加硬 3（异构专属）**：去重后的 `source.agentId` 集合含**两个不同值**（`search-agent` + `verify-agent`）。依据 FEAT-027 §2「agentId 来源」MUST：远端身份使用 `a2a_delegate` 上下文中非空的 `agentName`，「**不得**用外层父 Task 的 `agentId`、当前 Runtime 自身 `agentId` 或 tool name 填补」——两个下游配置不同，就必须在 `agentId` 维度可区分；若实测只有 1 个值，说明身份被父 agentId 或 tool name 顶替，属 MUST 违约

### 3.4 依赖型场景正确性、反证与兼容（P5 / P5b / P6）

> **2026-09-02 拆解说明（先读这段再看条目）**：本组原本是一条「反证：有依赖任务禁止并行」。**立意成立且本组合独有**——没有反证，P1~P4 的正向断言只证明「能并行」，不证明「会判断」，一个无条件全并行的实现也能全绿。但原实现三重问题叠加：①宣称的判据面 `ToolCallSequenceObserver` **全仓不存在**，代码里实际跑的是时长启发式；②实际判据 `totalElapsed >= 40s` 与契约**无因果关系**（串行也可能 <40s，并行也可能 >40s），且它用 `assertThat` 会真红，红的原因却与契约无关，属**误红**——比漏红更坏，因为它训练团队把用例当 flaky 忽略，自毁价值；③**换对观察面也当不成硬判据**：「同轮/跨轮」判的是**模型行为**（planrule.yaml 是提示词规则），模型这一轮听不听话是随机变量，单次绿不证明 planrule 对、单次红也不证明 planrule 错；testplan §6 已承认「核心断言依赖模型规划质量」、§8 已定「LLM 抖动降 INCONCLUSIVE」，本条不应例外。
> **故拆为两条**：**P5** 只守功能正确性（BLOCKING，硬判据、必须绿）；**P5b** 承接反证（SSE，⬜ 待建，**只能红成告警不能红成 FAIL**）。

- **P5** `EdpaDependentTasksSerialTest`（依赖型场景端到端正确性）
  - G：SUT + search + verify 就绪
  - W：`SendMessage(PROMPT_DEPENDENT_SERIAL)`（先搜 Java 21 虚拟线程、再据首条结论核查，有数据依赖）+ `GetTask` 轮询至终态
  - T（**单层，全是硬判据**）：达终态 COMPLETED；final_answer 非空且**同时出现「搜索主题」与「核查结论」两个语义片段**——说明两步都执行了、第二步产出了结论、结果回灌进了汇总
  - **绿灯含义的边界（别过度解读）**：本用例**不**证明第二步真的**消费了**第一步的输出。关键词命中只说明汇总文本里两个语义片段都在，模型完全可能在依赖断裂后自行编造核查结论。「依赖是否被真正满足」的强判据在 P5b。本条绿灯仅意味着「依赖型 prompt 在本 SUT 上能端到端跑通」
  - **已删除的旧判据**：`totalElapsed >= 40s`「否则判模型伪并行」——见上方拆解说明②，已降为纯诊断日志
  - PASS：终态 COMPLETED + 两语义片段均命中；FAIL：终态 COMPLETED 但缺任一片段（某一步未执行或结果未回灌）；INCONCLUSIVE：超时未达终态、或终态非 COMPLETED

- **P5b** ⬜ **待建** —— 反证：有依赖任务不得同轮伪并行（SSE 面，**告警级**）
  - G：同 P5，但走 SSE 拓扑
  - W：`SendStreamingMessage(PROMPT_DEPENDENT_SERIAL)`；用 `EdpaAgentEventScanner` 解析 `agentEvent`，把每条 delegation 轨迹按 `source.taskId` 喂进 `BatchTimingObserver`
  - T：两条 delegation 的时间窗**不重叠**（即 `timeWindowsOverlap() == false`，恰是 `BatchTimingObserver` 判定的**否定**），且第二条轨迹的首个事件出现在第一条终态 `status` **之后**
  - **判读等级（本条的关键约束）**：命中「时间窗重叠」时**只判 INCONCLUSIVE 并打显式告警**，**不得判 FAIL**——单次采样不构成 planrule 违约证据。若要升级为 FAIL，需先建立多次采样的统计口径（如 N 次中重叠 ≥ K 次），那是独立的排期项
  - **前置**：`delegation < 2` 时早退 INCONCLUSIVE（模型只派了一条，无从比较）
  - **删除的原判据**：「`ToolCallSequenceObserver` 观察到同轮仅 1 个 ToolCall；第 2 个 ToolCall 出现在新一轮」——该类不存在且按描述建不出来（wire 无 `tool_call`/`tool_result` 事件类型，`agentEvent.type` 是闭集 `delegation | output | status`，「推理轮次」是 agent-core 内部概念）。见 §2.2 该行
  - **✅ 已定口径（2026-09-02）· ✅ 已落码（2026-09-03，见 §3.3 与 §5.11）**：原文 PASS/FAIL 栏写「FAIL：LLM 违反 planrule 强行并行（对齐 §7.3 E4「模型合并多个实体」）」。**回源定位**：该句出自 `develop/03-architecture/L2-Low-Level-Design/edpa/Feat-Func-028-edpa-planning-workflow-and-agent-parallel-execution.md` §7.3「错误表面验收」表（docs 私仓 @ `6c879a2`），原文「模型合并多个实体｜单 ToolCall 参数包含多个独立实体时，视为规划质量问题，**验收判失败**」——**该表 11 行全部无编号，E3/E4 属伪造锚点，引用一律改写为「§7.3 表『模型合并多个实体』行」**。设计侧是**精确区分了两种模型行为**的：上一行「模型串行生成调用」明确容忍（「功能仍可完成，但失去并行收益」），下一行「合并实体」明确判死。**因此本方案「模型行为一律 INCONCLUSIVE」的分层边界画错了一条，须拆成两支**——这不需要设计侧裁定，L2 原文已足够明确，属本仓落码工作。**命中点是 P3/P4 而非 P5b**（P5b 本身仍是告警级）：那两条现有的 `delegation < 2` 早退分支须拆为——检查该轮 `call_subagent` 的 `arguments`，**参数内含多个独立实体 → FAIL**（引 L2 §7.3 该行）；**只含一个实体**（模型这轮只做了一个主题、或把两个主题拆到两轮）**→ INCONCLUSIVE**。落码点见 §3.3

- **P6** `EdpaSingleEntityCompatTest`（单成员兼容）
  - G：SUT + search 就绪
  - W：`SendMessage(PROMPT_SINGLE_ENTITY)` + `GetTask` 轮询至终态
  - T（**单层，全是硬判据**）：达终态 COMPLETED；final_answer 覆盖单一查询主题。守的是 planrule 兜底条款（「仅识别到 1 个子任务时走常规路径，不强制并行」）的**可观察后果**——单实体场景在批量并行特性引入后**仍能端到端正常完成**
  - **定位（2026-09-02 用户拍板）**：本条是 **BLOCKING 通道上的回归守护用例**——**存在价值是证明「引入并行子任务后，单任务特性不受影响」**。判据只需落在「单任务还能端到端正常跑完」，**委托数、走哪条路径都不在其职责内**，不需要也不应该补过程量断言
  - **观察面边界**：「只生成 1 个 ToolCall」是 agent-core 内部过程量，BLOCKING 通道与 GetTask 终态快照上**都没有投影**，本用例不断言。**且已定案不另建 SSE 计数用例**（原拟 P6b）：该语义判的是模型规划行为（planrule 提示词），与 P5b 同类而价值更低——**这是已知且已定案的不覆盖项，不是漏测，后续不得作为缺口重新提出**
  - **⚠️ 撤销一条曾被列为"缺口"的项**（2026-09-02 回源更正）：「走单成员兼容路径而非批次路径」**不是可判契约**。L2 §7.1 能力矩阵原文为「只有一个任务时保持单 ToolCall、单中断路径，**不强制批次**」，FEAT-028 特性档第 234 行为「**不强制**走批量路径」——「不强制」≠「禁止」，单实体走批次路径不构成违约，故无义务可判、也就不存在这项漏测
  - **已删除的旧判据**：`totalElapsed <= 90s`「超限可能被误批量化」——误红源：模型慢、网络抖、search-agent 自身慢都会让它超限，而这些与「有没有被误批量化」毫无关系；反过来真被误批量化时它也未必超限。已降为纯诊断日志
  - PASS：终态 COMPLETED + 单主题覆盖；FAIL：终态 COMPLETED 但汇总不含该主题；INCONCLUSIVE：超时未达终态、或终态非 COMPLETED

### 3.5 组合契约面（C1~C3，引用 FEAT-019）

- **C1** `EdpaBatchAtomicityTest`（引用 FEAT-019 主权；2026-08-24 定性更正见 §5.5；**2026-09-02 判据再更正见 §5.5.3**）
  - G：同 P1
  - W：`SendStreamingMessage` 发 `PROMPT_HETERO_PARALLEL`；采 SSE 全帧
  - T（双证判据 + 全字段扫描；**判据顺序 = 断言顺序**）：
    - **硬 B（先判）**：最终 artifact 内容覆盖 **search + verify 两个主题**——证明批次全部完成后模型汇总了 ≥ 2 个子结果（不是丢弃后拿单结果凑答）。按 testplan §8，这一层在**模型任意规划质量下必须绿**，故必须置于硬 A 的 `delegation < 2` INCONCLUSIVE 早退**之前**，否则会被跳过
    - **硬 A-0（wire 最小结构，无条件）**：只要 wire 上出现了 `agentEvent`，`type` 就必须落在 §3.1 闭集内（**缺失 `type` 同样违约**），且每条事件的 `source` 二元组非空（FEAT-027 §2 三条 wire 最小结构 MUST）。与 C3/P3/P4 同一套分层，避免同一 prompt 下 C1 判 FAIL 而其余判 INCONCLUSIVE 的不自洽
    - **硬 A-1**：每条 `delegation` 的 `target.agentId` / `target.taskId` **逐条非空**（§3.1 字段适用性表 target 必须 + §2「delegation 生成」MUST 明文「不得生成空 target Task ID」）
    - **硬 A-2**：`target.taskId` **两两不同**（§2 对一个下游 Task 只「生成一次」delegation；重复即意味着两个 member 被折叠到同一个子 Task 上）
    - **硬 A-3**：去重后指向 **≥ 2 个互不相同的 member**——证明 runtime 派发了 ≥ 2 个远端委托（不静默丢弃、不只保留最后一个）
    - **⚠️ A-1 / A-2 不可用「去重后 target 集合 ≥ 2」代替**：`Set` 会静默跳过空值、把重复值折叠为一个——3 条 delegation 里混 1 条空 target，或 2 条共用同一 target，两种失效形态都能通过集合大小判定。逐条断言必须走 `delegationsMissingTarget()` / `delegationsWithDuplicateTarget()` 两个列表
    - **观察记录（不作判据）**：`EdpaChildVisibilityScanner` 命中的 `toolCallId` 值集合与出现位置
  - **PASS**：硬 B + 硬 A-0~A-3 全绿；**FAIL**：任一不成立；**INCONCLUSIVE**：SSE 无帧，或 `delegation < 2`（模型未同轮派发；注意——若模型确实同轮生成了 ≥2 个 ToolCall 而 wire 上只有 1 条 delegation，那是真缺陷「批次被折叠」，但该情形**无法从客户端黑盒面与 LLM 抖动区分**，需查服务端 `RemoteInvocationBatchCoordinator` 日志）
  - **原 red-first 判据「batchId 应从公开面可见」已撤回**：FEAT-019 特性档 §3.1「参考批量中断形态」 明确 `batchId` 是 core/runtime **内部诊断标识**，不要求对客户端可见——此为测试判据误读特性档，撤回并更新 issue #93（详见 §5.5）
  - **原硬 A「`toolCallId` ≥ 2 个互不重复」已降为观察记录**：`toolCallId` 是 MAY 级扩展字段（FEAT-027 §5.9）、「不构成用户侧调用图协议」（FEAT-019 L2 §5.4），不能承担 wire 面硬判据（详见 §5.5.3）

- **C2** `EdpaAllSettledSingleRecoveryTest`（引用 FEAT-019 主权；**2026-09-02 判据可判定化，见 testplan §5 C2**）
  - G/W：同 **P3**（`SendStreamingMessage`，SSE 便于观察推理恢复次数；*2026-09-02 更正：原写「同 P1」，但代码走的是 `SendStreamingMessage`，P1 是 BLOCKING，写错了通道*）
  - **T 硬 1（已落码，降级为看守）**：SSE 中终态 `statusUpdate` 帧（state 含 COMPLETED/FAILED/CANCELED/REJECTED）**恰好 1 次**，且 `artifactUpdate` 帧 ≥ 1
    - ⚠️ **这一层单独不足以判定本条**：A2A 状态机本就保证父 Task 只有一个终态帧，逐成员触发推理恢复也只会产生一个终态帧——它能挡住的只是「终态刷屏」这种粗粒度失效。真正的判据在硬 2
  - **T 硬 2（2026-09-03 落码；⚠️ 判据形态在落码时被推翻，全文见 §5.11）**：**每个父段起点处，「已派发成员 − 已回程成员」必须为空集**。观察面 `EdpaRecoverySegments.analyze()`。
    - **分段规则**（只看 `artifactUpdate` 帧）：无 `agentEvent` → 父段帧；含非 delegation 事件且 `source.agentId ≠ 父` → 子段帧；**仅含 delegation 的帧是控制帧、分段时透明跳过**——§3.1 规定 delegation 的 `source` 本就指向父，把它当父段帧会在跨轮场景凭空切出父段（误红），也会把两个相邻子段割断（漏红）。
    - **为什么不是原设计的「最后一条子回程之后父段恰好 1 段」**：那条**也是恒真的**——按定义最后一条子回程之后不再有任何子段，剩余帧必然连成一段；它唯一能抓的是「父 Agent 一段汇总都没出」，与硬 1 抓的粗粒度失效同级。若改数「父段总数」则会**误红**跨轮追加委托（实测常见形态：4 条 delegation 分 2 轮，合法产生多个父段）。真正区分「逐成员触发」与「跨轮追加」的不是父段的**位置或数量**，而是父段起点处**本批是否还有成员在途**。
    - **不可判定出口（四个，宁可 INCONCLUSIVE 也不猜）**：无 delegation / delegation 的 `source.agentId` 不唯一（嵌套委托）/ delegation 缺 `target.taskId`（差集建不全 → 漏红）/ **全程无子任务终态 `status`**（「已回程」恒空 → 任何父段都算违规 → 恒红）。
    - **诊断输出不作判据**：`parentSegmentsAfterFirstChild`（跨轮会 >1，属合规）与 `P/C/d` 时序串。
  - **已删除的原判据**：「『汇总性 artifact』出现次数 = 1」——「汇总性」在 wire 上无定义、SSE 里无法与透传 artifact 区分，不可判定（T-M4）。2026-08-24 首轮真机时曾以「【结果汇总】等结构化关键词」实现，随即因「planrule 建议格式而非硬约束、模型有自由度不遵守」被推翻——**该形态曾原样残留在 S1，已于 2026-09-03 一并降级为诊断**，见 §3.7 S1 条。
  - PASS：硬 1 + 硬 2 全绿；FAIL：存在父段起点处仍有成员未回程（逐成员恢复），或终态帧 ≠ 1；INCONCLUSIVE：SSE 无帧，或命中上述四个不可判定出口之一

- **C3** `EdpaDelegationReturnBindingTest`（引用 FEAT-019 主权；2026-08-24 判据升级用统一 helper；**2026-09-02 判据重写见 §5.5.3**，类名由 `EdpaToolCallIdStableBindingTest` 改名而来）
  - **题目修订**：原「`toolCallId` 稳定归位」→ 现「**批次归位的端到端可观察：每个委托都有回程**」。契约面不变（仍是 FEAT-019 归位契约的组合端到端投影），换的是观察坐标系：从内部诊断字段 `toolCallId` 换成 FEAT-019 L2 §5.4 指定的客户端调用图坐标 `(agentId, taskId)` + `delegation` 边
  - G/W：同 P1（SSE 全帧）
  - T（严格判据）：
    - **硬 1a**：`agentEvent.type` 落在 FEAT-027 §3.1 闭集 {`delegation`,`output`,`status`} 内（**缺失 `type` 同样违约**，§2「控制与业务语义区分」MUST），且每条事件的 `source` 二元组非空（§2 三条 wire 最小结构 MUST）
    - **硬 1b**：按 `(agentEvent.source.agentId, agentEvent.source.taskId)` 二元组去重后 ≥ 2 组，且**非 `delegation` 事件的** `source.taskId` ≠ 父 taskId（FEAT-027 §2「并发交织」MUST：客户端通过该二元组分流，不得用外层父 Task ID 替代生产者 Task ID）。`delegation` **不在此列**——§3.1 字段适用性表规定其 `source` 本就指向父 Agent/Task，等于父 taskId 是**正确**行为。**前置**：父 taskId 必须已抽到并断言非空，否则「≠ 父 taskId」的判定会空转判绿
    - **硬 2**：每条 `delegation` 的 `target.taskId` 都能在后续某条 `output` 或 `status` 事件的 `source.taskId` 中找到——"派发出去的每个 member 都有回程事件"，无静默丢弃、无错配。依据 FEAT-019 L2 §5.4「同一 member 内只保证 delegation 先于首个 output」；跨子树可交错，故只判存在性、不判全局顺序
    - **观察记录（不作判据）**：`toolCallId` 的出现位置与次数分布，供开发对齐 wire 定型
  - PASS：硬 1a + 硬 1b + 硬 2 全绿；FAIL：`type` 越界或缺失 / source 二元组含空 / 轨迹不足 2 组 / 非 delegation 事件用父 taskId 顶替 `source.taskId` / 存在无回程的 delegation；INCONCLUSIVE：SSE 无帧，或无 delegation 且无任何子任务证据（模型未派发）
  - **原硬 2「每个 `toolCallId` 平均出现次数 ≥ 2（tool_call + tool_result 一致映射）」已整条删除**：FEAT-027 §3.1 把 `agentEvent.type` 闭集钉死为 `delegation | output | status`，wire 上**不存在 `tool_result` 事件类型**——该断言观察的是契约上不存在的对象，恒红也不构成缺陷（详见 §5.5.3）

### 3.6 越界约束看守（N1/N2，§2.2 主权）

- **N1** `EdpaCoordinationModeLeakGuardTest` **+ 金丝雀** `EdpaModeFieldScannerSelfTest`
  *（2026-09-02 判据重定位。原类名 `EdpaEnvelopeNoModeFieldGuardTest` 已删除——那个名字误称了它从未观察到的 envelope；重定位理由见 §5.10）*
  - ⚠️ **本条不验证 FEAT-028 §2.2**。envelope 按 FEAT-019 §3 属 core→runtime adapter 内部对象，§3 抬头明示不固定序列化字段，SIT 面上「不含某字段」不可判定。§2.2 的正面举证在 agent-core 白盒单测。
  - G：SUT（edp-agent + search + verify）就绪；`EDP_AGENT_MODEL_API_KEY` 存在（缺失则整类 skip）
  - W：**单次真机运行，两条判据共享证据**（避免把 ~130s 的流跑两遍）——发请求**前**记录 edp-agent 日志文件字节偏移；`SendStreamingMessage` 发 `PROMPT_HETERO_PARALLEL` 采 SSE 全帧（上限 130s，`-Dsit.feat028.n1-stream-cap-ms` 可调）；抽首个 taskId 后 `GetTask` 取**流后快照**；流关闭后轮询至多 5s 等 coordinator 行落盘，只读本轮新增日志段
    - ⚠️ 该快照**不保证是终态**：流可能因 130s 上限被截断而任务仍 WORKING。它只是判据① 的第二个**扫描面**，取不到或非终态都不影响判定口径（黑名单看守的绿灯本就只代表「扫过的面上没出现」）；实际 `status.state` 会打进日志，便于事后判断本轮覆盖了多少
  - T-①（`noCoordinationModeLeakOnObservableSurfaces`，看守）：对 **SSE 全帧 + 流后快照 + 进程日志**三面扫描，`EdpaModeFieldScanner` 命中集必须为空
    - 判定只看**叶子字段名**：精确集 `mode`/`blocking`/`syncmode`/`sync_mode`/`asyncmode`/`async_mode`/`edpamode`/`edpa_mode`/`coordinationmode`/`coordination_mode`/`executionmode`/`execution_mode`/`invocationmode`/`invocation_mode`/`callmode`/`call_mode`，并集 `*mode` / `*_mode` 后缀
    - 白名单 `WHITELIST_LEAF` **也只对叶子名生效**（祖先节点名不赦免后代字段），**当前为空**；新增条目须在代码内注明其在 A2A 协议或特性档中的合法出处（T-M21），红一次、定性一次、加一条
    - 日志面按 `key=` 抽键名同规则判定，同名只报一次
    - **绿灯含义**：「三个面上未出现已知命名形态」。**不构成 §2.2 合规证据**——黑名单无法证明不存在
  - T-②（`batchExpressesBatchAndMemberList`，正向）：按进程日志 `RemoteInvocationBatchCoordinator` 状态行的 `batchId` 聚合（正则 `batchId=(...)\s+toolCallId=(\S+)\s+remoteAgentId=(\S+)`，实测形态见 §5.9），**至少一个批次含 ≥2 个不同 `toolCallId`** —— 正面实证 FEAT-019 §3「至少能表达批次、成员列表」
  - PASS：①命中集为空；②`max(每批成员数) ≥ 2`。FAIL：①有命中（需人工定性：合法则加白名单并注明出处，越界则按 §2.2 提缺陷）；②所有批次均只含 1 项。INCONCLUSIVE：①SSE 无帧；②日志无 coordinator 状态行（**不判 FAIL**——日志格式非契约，实现可自由变更）
  - **金丝雀（`EdpaModeFieldScannerSelfTest`，4 条，不打 `manual`）**：本条主用例在正常实现下**恒绿**，恒绿无法自证是「没泄漏」还是「扫描器坏了」。金丝雀不起 SUT、毫秒级、随每轮构建跑，用合成节点验证扫描器可开火：①埋在 `model` 祖先下的 `syncMode` 与数组元素内的 `edpa_mode` 必须被检出（回归 2026-09-02 修的整条-path 白名单缺陷）；②`model`/`modelName`/`modelProvider`/`executionId`/`asyncTimeout`/`syncedAt`/`coordinationId` 等合法字段不得误红；③日志键名扫描可开火且按键名去重，coordinator 正常状态行 7 字段全不命中；④白名单为空且 `isForbiddenLeaf` 大小写不敏感、`modelName` 不命中。**判读纪律：主用例的绿灯只在金丝雀同轮全绿时才可信。**
  - **已移除**：原「辅助诊断：同步做 `EdpaChildVisibilityScanner` 子任务可见性扫描」。理由——子任务可见性由 P3/P4/C1/C3/R1 以硬判据正面覆盖，在一条否定式看守里再挂一个不作判据的诊断输出，只会稀释本用例的判据焦点；且该诊断曾被 §5.7 当作 N1 的「证据」引用，是假绿的成因之一。**`batchId` 不再被写成「内部诊断字段不列入判据」**——它在判据②里作为**批次聚合键**使用（聚合手段，非客户端可见诉求；FEAT-019 L2 §5.4 的「不构成用户侧调用图协议」约束的是客户端构图，不禁止测试侧用它做日志聚合）

- **N2** `EdpaAgentCoreNoDirectRegistryAccessRedGuardTest` ⭐
  - G：SUT 就绪；registry-discovery-center 侧启用 `RegistrySniffer` 观察器（可选）
  - W：任一并行用例的执行（复用 P1/P3 真机 run）；观察 registry 侧的访问日志/网络连接
  - T：若观察面可用，registry 收到的访问来源只能是 agent-runtime 侧（非 agent-core 侧）；agent-core 不得直接调用 registry-discovery-center
  - PASS：观察面可用且来源合规；INCONCLUSIVE：观察面缺失（部署未含 sniffer）——本条不因此降级判绿；FAIL：观察到 agent-core 直连来源

### 3.7 数据面/控制面分离（S1，§5.0.1 主权）

- **S1** `EdpaDataControlPlaneSeparationTest` ⭐
  - G：同 P3 或 P4（SSE 模式）
  - W：SSE 收集全部 `artifactUpdate` 事件（数据面）；等达终态后 `GetTask` 取 final_answer（控制面）
  - T（分层）：
    - **硬 1（已落码）**：数据面 `artifactUpdate` 帧 ≥ 1；控制面达终态 COMPLETED 且终态 `artifacts` 文本非空——两条通道都有内容，这是「分离」得以讨论的前提
    - **硬 2（2026-09-03 落码）**：设 `C` = 控制面 final_answer 文本，`D_sub` = **仅由子段** `artifactUpdate`（`artifact.metadata.agentEvent.source.agentId` **≠ 父 agentId**）拼出的数据面文本。断言 `C != D_sub` 且 `C` **不是** `D_sub` 的连续子串——即 final_answer 不是数据面片段的机械拼接或末帧代替
      - ⚠️ **必须按 `source.agentId` 过滤**：父 Agent 自身的汇总推理也走流式输出，把它算进 `D_sub` 会让 `C` 天然成为 `D_sub` 的子串而**恒红**（典型**误红陷阱**）。金丝雀 `childPlaneTextExcludesParentOwnOutput` 专门钉这一条
      - **两侧均去掉全部空白后再比**（`replaceAll("\\s+","")`）——否则换行/缩进差异就能让机械拼接逃过子串判据
      - 观察面：`EdpaRecoverySegments.parentAgentId()` + `EdpaRecoverySegments.childPlaneText()`（用例代码已实际调用）
      - **两个不可判定出口**：delegation 的 `source.agentId` 不唯一或无 delegation（父身份定不了，过滤基准建不起来）；过滤后 `D_sub` 为空（本轮没有子 Agent 透传输出，判据无观察面）
    - **诊断记录（不作判据）**：final_answer 是否含「【需求概述】/【结果汇总】/汇总/综上」等 planrule 建议格式关键词；数据面与控制面字符数差
  - **✅ 已清理（2026-09-02 登记 → 2026-09-03 随硬 2 落码一并处理）**：本用例此前**唯一的内容级硬断言恰恰是关键词检查**（`containsAny(controlText, "【需求概述】", ..., "汇总", "综上", ...)`）——而这正是 C2 在 2026-08-24 首轮真机后**明确推翻**的判据形态（理由：planrule 建议格式而非硬约束，模型有自由度不遵守）。同一份方案里一条推翻、另一条照用，是本轮返工要清掉的不一致。该断言现已改为 `LOG.info("[s1] [诊断，不判定] …")`，内容级判定由硬 2 承担
  - **已删除的原判据**：「用内容相似度判据」「final_answer 与流式事件末帧**结构等价**」——「相似度」无阈值、「结构等价」无定义，均不可判定（T-M4）；已由硬 2 的子串判据替代
  - PASS：硬 1 + 硬 2 全绿；FAIL：`C == D_sub` 或 `C` 是 `D_sub` 的连续子串（机械拼接 / 末帧代替）；INCONCLUSIVE：SSE 无帧、终态非 COMPLETED、final_answer 抽不到，或未观察到任何子段事件（`D_sub` 为空，无从比较）

### 3.8 SubscribeToTask 重订阅——子任务粒度可见性（R1，2026-08-24 新增）

- **R1** `EdpaSubscribeToTaskResubscribeTest` ⭐（2026-08-24 新增，承接设计团队反馈的三条查询/恢复观察通道之一）
  - **动机**：设计团队 2026-08-24 明确 EDPA 场景客户端应通过三种通道观察子任务信息——①SSE 实时看子智能体工作事件（已覆盖 P3/P4）、②断连后 `GetTask` 快照包含子任务终态/中间态（原拟 P0b/P0c，**已 out-of-scope**——特性档 §5.0.1 明写 BLOCKING/ASYNC 下 `GetTask` 终态快照不含中间流式，「这是 A2A 协议设计约束，非缺陷」；**当期无承接方，属已知覆盖缺口**）、③断连后 `SubscribeToTask` 重订阅（本用例覆盖）。FEAT-001 **§2「当前版本能力要求」**已定义 `SubscribeToTask` 标准 method——首帧=当前快照，之后=挂接成功后的新事件；R1 在此基础上聚焦 EDPA **子任务粒度可见性**。*（2026-09-02 锚点更正：原写「FEAT-001 §62」，62 是行号不是章节号——FEAT-001 只有 §1~§7，行号会随文档编辑漂移，等同于无锚点。）*
  - **上游依赖 / 承接分界**：FEAT-001 `TaskResubscribeTest`（**E3+E4**）已在 search-agent SUT 验证 SubscribeToTask 的基础 wire 契约，R1 不重复。其中 **E4「终态 Task 重订阅 → `UnsupportedOperation` 类错误 → `GetTask` 回退」即 FEAT-001 §2 重订阅约定的后半段**——testplan 矩阵末尾以 ~~R2~~ 行登记为「不另建，指名承接」。**已知限制**：E4 的 SUT 是 deep-research 栈而非 EDPAgent；该契约属 agent-runtime 通用 wire 面、与 EDPA 编排无关，若后续发现两栈 A2A 入口实现分叉，需在本方案补一条 EDPA 拓扑下的等价用例。
  - G：SUT 就绪 + search 就绪（PROMPT_HOMOG_PARALLEL 只需 search）；`A2aServiceClient.subscribeTask(taskId, ...)` 可用（已封装 SDK）
  - W：①`SendStreamingMessage` 发 `PROMPT_HOMOG_PARALLEL`，读到父 taskId + WORKING 状态后**主动断开**（模拟客户端断连）；②立即调 `SubscribeToTask(params.id=parentTaskId)`；③收重订阅 SSE 直到关闭或超时
  - T（分层）：
    - **硬 1（FEAT-001 §2 基础契约复用）**：SubscribeToTask 应返回 SSE 流；首帧为**当前 Task 快照**（taskId 一致 + status.state 存在）
    - **硬 2（FEAT-028 子任务可见性 · 本用例主权）**：首帧快照 + 后续所有事件的**全字段递归扫描**应能命中至少一处子任务信息——判据集合：①子 taskId（非父 taskId 的其他 taskId 值出现在任意字段）；②子 agentId（除 EDPAgent 自身外的 agentId 值，如 `search-agent`）；③子 state（在 `agentEvent.source.state` 或 `agentEvent.target.state` 或类似路径出现）。三种命中方式**任意一处**即算硬 2 PASS——**不预设 wire 字段名/结构**（承接用户 2026-08-24 明示：wire 承载位归设计定，测试只保证客户端能观察到）
    - **观察记录（无硬断言）**：命中的具体字段路径 + JSON 位置，供开发对齐 wire 定型；同时记录首帧到达前的耗时、总帧数、后续事件是否出现子任务状态变化
  - PASS：硬 1 + 硬 2 均绿；
    FAIL：硬 1 挂（method 未实现或首帧不是快照）— 违反 FEAT-001 **§2**；或硬 2 挂（全字段扫描无任何子任务信息命中）— 违反 FEAT-027 §2「wire 协议最小结构」三条 MUST（2026-09-02 更正判据依据：原写「red-first 承接 issue #93 缺陷簇第 4 处（与 P0b/P0c/C3 同源）」，但 issue #93 已关闭且 P0b/P0c 已 out-of-scope、C3 判据已撤回，本条不能再挂靠其上——它的正当依据是 FEAT-027 的 wire MUST，且 R1 实测已 PASS）；
    INCONCLUSIVE：模型未真触发并行子任务（LLM 抖动），子任务信息缺失属正常，跳过
  - **Tag**：`manual`（依赖真实 LLM + LLM 并行规划）

## 4. 运行方式

**执行集与 `@Tag` 的对应关系（2026-09-02 校准，2026-09-03 随第二只金丝雀刷新；与 testplan §8 同源）**：实测 `feat-028` 类共 **18 个**，其中**只有 3 个未打 `manual`**（A1 `EdpaAgentCardAlignmentTest`、N1 金丝雀 `EdpaModeFieldScannerSelfTest`、判据金丝雀 `EdpaJudgeSelfTest`）。`manual` 表达的是「依赖真实 LLM/密钥」，与「属于哪个执行集」是两个正交维度——**用一个 tag 承担两种语义必然对不上**，这也是下面两条命令此前互斥、不存在真正 full 的原因。

```bash
# CI 常驻集（当前唯一能在无 LLM 密钥环境下跑的集合）= A1 + 两只金丝雀，共 3 类
./mvnw -Dtest.env=local -Dgroups='feat-028 & !manual' test

# Full suite = 全部 18 类（A1、P0a、P1~P6、C1~C3、N1、S1、R1 + 两只金丝雀 + 已 @Disabled 的 P0b/P0c；N2 无用例类）
# 2026-09-02 更正：原写 -Dgroups='feat-028 & manual' 并注释为「全部 FEAT-028 相关用例」——
# 该式跑不到 A1（A1 未打 manual），既不是 full 也不是任何一个声明过的集合。
./mvnw -Dtest.env=local -Dgroups='feat-028' test

# P0 门禁集（⬜ 当前跑不出来）= A1、P0a、P5、P6
# 四条中只有 A1 不依赖 LLM，其余三条打了 manual，因此该集合没有对应的选择表达式。
# 落码方式：另起 @Tag("feat-028-p0") 显式标注这四个类，用 -Dgroups='feat-028-p0' 选出——
# 不得靠 !manual 反选。
# ./mvnw -Dtest.env=local -Dgroups='feat-028-p0' test   # 待 tag 落码后启用

# 指定单条子用例（示例：P1 同类型批量并行同步阻塞）
./mvnw -Dtest.env=local -Dtest=EdpaHomogParallelBlockingTest test

# N1 主用例单跑（~130s 流式采集；判读前须确认同轮金丝雀全绿）
./mvnw -Dtest.env=local -Dtest=EdpaCoordinationModeLeakGuardTest test
# N1 金丝雀单跑（不起 SUT、毫秒级）
./mvnw -Dtest.env=local -Dtest=EdpaModeFieldScannerSelfTest test
# 判据金丝雀单跑（不起 SUT、毫秒级）——C2/S1/P3/P4 判读前必跑
./mvnw -Dtest.env=local -Dtest=EdpaJudgeSelfTest test
```

> **判读顺序（2026-09-03 追加）**：C2 硬 2、S1 硬 2、P3/P4 合并实体这三条判据与 N1 同属「绿灯反推不出它是否还活着」的形态，判读主用例结论前须先确认 `EdpaJudgeSelfTest` 11 条同轮全绿。两只金丝雀都不需要 LLM 密钥、不起 SUT、毫秒级，跑 manual 集之前顺手跑一遍 `feat-028 & !manual` 即可。金丝雀红 = **判据实现**被改坏，与 SUT 无关，此时不得对 SUT 下任何结论。

**每轮开跑前的版本指纹前置（T-M19）**：从三个 SUT 的 Agent Card 读 `version`（来源是 jar `MANIFEST.MF` 的 `Implementation-Version`，如 `edp-agent-engine-0.1.1.jar` → `0.1.1`）并记录；读不到或低于基线则整轮判 INCONCLUSIVE 而非继续跑。缺这一步时「跑在旧 jar 上」与「这一轮 LLM 没并行」在结果上完全同形（都表现为 INCONCLUSIVE），是最危险的假绿路径。参考实现：`deepagent_deepresearch/AgentCardDiscoveryTest`。

**沙箱环境限时说明**：EDPAgent 模型推理 + 2 次 search/verify 完整链路时长预估 40~80s；沙箱单调用限时场景需按类拆分执行或使用 system property 缩窗。

## 5. 真机实测进展（滚动记录）

> 方案级设计文档（`docs/testplan` 同名档）只锚定场景条目，不承载进展；实测进展、缺陷对时、验证结论统一记录在本节。

**当前状态（2026-09-02）**：17 条场景条目中 **16 条已落码**（N2 越界看守仍 ⬜ 待建，缺 registry 侧观察器；P0b/P0c 已 out-of-scope 但类仍在、加 `@Disabled` 归档）；真机记录见 §5.1（首轮，2026-08-24）、§5.6（判据升级回归）、§5.7（新 jar 回归，2026-08-28）、§5.8（`agentEvent` 判据重写后的重跑，2026-09-02）、§5.9（coordinator 日志实测形态）、§5.10（N1 判据重定位）。本轮 `agentEvent` 判据重写涉及的 P3/P4/C1/C3 四条**已于 2026-09-02 重跑全绿**（判据变更依据见 §5.5.3，重跑数据见 §5.8）。N1 于 2026-09-02 重定位为「泄漏回归看守 + 批量语义正向实证」并配金丝雀自检，**其绿灯不再作为 §2.2 合规证据**（见 §5.10）；重定位后的 `EdpaCoordinationModeLeakGuardTest` **尚未真机重跑**，金丝雀已 4/4 绿并通过变异验证。

**首轮真机的关键使命（2026-08-20 立档时的计划，已完成并部分作废——保留作过程记录）**：
- ~~完整 dump 父任务 WORKING 期间的至少 3 个快照，钉死子任务并行进展的承载位（哪个字段承载 batchId、toolCallId、子任务 status、子任务 artifact 关联）~~ —— 已执行，结论见 §5.1；其后 P0b/P0c 于 2026-08-24 out-of-scope，`batchId` / `toolCallId` 于 §5.5 / §5.5.3 两次撤回可见性诉求，**这两个字段不再是需要钉死的客户端承载位**
- 结论写入 §5.1，成为 C1/C3/P0c 后续断言的稳定参考 —— 现行稳定参考已换为 FEAT-027 §3.1 的 `agentEvent` 结构化契约（`(source.agentId, source.taskId)` 坐标 + `delegation` 边）
- 若承载位与 planrule.yaml / `PlanAgentParallelTransferStreamingTest` javadoc 提及的 `_remote_invocation.{batchId,toolCallId}` 形态不一致，作为 wire 事实增量入 [[a2a-wire-contract]] 记忆 —— 仍有效，但仅作**实现事实**记录，不作契约判据

### 5.1 首轮真机结论（2026-08-24）

**SUT 版本**：`edp-agent-engine-0.1.0.jar`（构建时间 2026-08-24 09:56）+ `agent-search-0.1.0.jar` + `agent-verify-0.1.0.jar`（8-17 版本，未更新）。
**LLM**：`deepseek-v4-pro-0813` @ aliyun 兼容端点（用户 8-20 提供的 EDPAgent 专用密钥）。
**运行期基建**：Redis 6.2.14（本地编译，`/tmp/tools/redis-server`）；scenarioHome=`/tmp/edpa-scenario-min`（governance/{planrule,actrule,scriptconfig}.yaml——**planrule 首轮真机改为通用化**，让模型能自由用 `call_subagent` 处理任何请求，否则 default planrule 只声明银行 skill、通用查询会被模型拒答）。

**用例执行结果**：

| ID | 结果 | 关键发现 |
|---|---|---|
| A1 | ✅ PASS | EDPAgent Card 就绪：`name=edp-agent-engine`（SDK card.name 反映 spring.application.name 而非 a2a.agent-name，属实现事实）、`streaming=true`、`pushNotifications=false`（本轮未启用）、`skills=[edp_banking_workflow]`。 |
| P0a | ✅ PASS | SendMessage 返回唯一 taskId；GetTask 状态序列 = `[TASK_STATE_WORKING, TASK_STATE_COMPLETED]`（**未观察到 SUBMITTED 中间态**——EDPAgent 返回 ack 时已 WORKING，属实现事实，状态机合法）。 |
| **P0b** | ❌ **FAIL（red-first 看守，发现真实缺陷）** | 见下方详述。 |

**⭐ P0b 首轮真机核心结论 — 发现 spec-vs-impl 真实缺陷**：

**并行调度实证生效**（证据链完整）：
- EDPAgent 侧：`SubagentDelegateRail` 在同一秒内并发处理 `tool-call-1`（`verify-agent`）+ `tool-call-2`（`search-agent`），模型同轮生成 2 个 `call_subagent`（对齐 planrule 并行判定准则）。
- runtime 侧：`RemoteInvocationBatchCoordinator` 记录 `batchId=c2cfc0c7` 下多条并行 Remote invocation state 事件，`A2ARemoteAgentClient` 发起 `A2A call agent=search-agent streaming=false` 调用。
- 子任务并行执行时间窗：约 41 秒（10:47:17.583 派发 → 10:48:08.890 首批异步 IO 返回结果）。
- 父任务最终达 **TASK_STATE_COMPLETED**——控制面 all-settled 汇总正常。

**但父任务快照全空——真实缺陷**：
- probe 高频（800ms 间隔）抓取全程 80 个快照，其中 **79 个 WORKING 中间态**；
- **4 种承载位全部 false**：`artifacts:[]` `history:[]` `metadata`（无 batch/toolCall/remote_invocation 关键词字段）`status.message`（无 text part）；
- 快照仅呈现 `status.state=TASK_STATE_WORKING` 与 `contextId` `id` 三个字段——客户端无从观察任何子任务并行进展。

**违反的契约**：
- FEAT-028 §5.0.1「控制面（结构化结果回填）：runtime 必须在事件对客户端可见前或以等价一致性顺序更新 TaskStore」
- FEAT-001 §5.1.8「`GetTask` 返回不早于已确认可见事件的 Task 快照」
- FEAT-019 §5.5「独立溯源：每个并行委托的 toolCallId 在中断、结果、错误、ToolMessage 之间稳定关联，天然支持执行轨迹独立追踪与归位」——**从客户端观察面看不到 toolCallId 归位痕迹**

**建议提交给开发的问题描述**：EDPAgent 父任务在 WORKING 期间（有子任务并行执行时），`GetTask(parentTaskId)` 返回的 Task 快照 artifacts/history/metadata 全为空——客户端无法从公开面观察到并行子任务的执行进展。终态时 artifacts 承载 all-settled 汇总（尚待 P0c 用例验证独立溯源痕迹），但 WORKING 期间的可观察面**完全缺失**。建议在批量 remote invocation 派发时向 TaskStore 写入结构化 metadata（如 `_remote_invocation.{batchId,toolCallId,remoteAgentId,state,startedAt}`），或在子任务返回结果时增量 append artifact 到父任务 artifacts 列表。

**P0b wire 事实钉死**（后续用例 C1/C3/P0c 断言直接依赖）：
- 承载位当前唯一可期候补：**终态 artifacts**（P0c 待验证——本轮未跑）
- 排除的承载位：`artifacts[]` `history[]` `metadata` `status.message` 在 WORKING 期间全部为空
- **P0b 用例继续保留为 red-first 看守**——EDPAgent 实现承载子任务并行进展后自动转 PASS

**测试基建修正**（本轮踩坑记录，写入 cases 便利后续复跑）：
1. **scenarioHome 硬校验**：EDPAgent boot 时 `EdpConfigValidator.validateScenarioConfig` 强校验目录存在 + `governance/` 子目录存在。sit-secrets 里注入不存在路径会 fail-fast。测试用 `/tmp/edpa-scenario-min/governance/{planrule,actrule,scriptconfig}.yaml`，其中 planrule.yaml 改为通用化（scope 非空 + 描述 search/verify 子代理 + 并行规则）。
2. **Redis 强依赖**：`run.ps1` 硬校验 6379 端口。沙箱无 apt/docker/sudo，需下载 redis 6.2.14 源码编译（gcc/make 沙箱可用），二进制放 `/tmp/tools/redis-server`。
3. **必备占位环境变量**：`EDP_MCP_ACCESS_TOKEN` / `EDP_MCP_MASTER_URL` / `EDP_MCP_STANDBY_URL` / `EDPA_SANDBOX_SERVICE_URL`（不用 MCP/sandbox 也要给占位，否则 Spring bean 初始化失败）。
4. **SutStack.env 不支持 late-bind**：`.env(k, v)` 在 stack 启动前解析，占位符字符串（如 `"PLACEHOLDER_WILL_BE_OVERRIDDEN_AT_START"`）会导致 `A2AAgentCardDiscovery` URI parse 抛异常、SUT 启动失败。P0a/P0b 改成 **`@TestInstance(PER_CLASS) + @BeforeAll` 分阶段启动**（先起 search+verify 拿 baseUrl → 再 build edp-agent stack），同 FEAT-001 D2 老套路。
5. **INPUT_REQUIRED 判定**：EDPAgent 可能在子代理返回后走 `AskUserTemplateRail` 追问用户，父任务停 INPUT_REQUIRED；P0b 断言把 INPUT_REQUIRED 也视为「父任务已停」（非 WORKING）避免超时掩盖承载位断言真实结果。

### 5.5 定性更正：`batchId` 不是客户端可见字段（2026-08-24）

**背景**：C1 `EdpaBatchAtomicityTest` 首轮真机（2026-08-24 上午）判 FAIL，判据是「SSE 事件流可见 toolCallId=3 个但 batchId 完全不可见，无法从公开面证明批次归属」——并被写入 issue #93 作为「TaskStore 未承载 batchId 字段」的精细化观察。

**更正依据**（2026-08-24 下午，用户 Q1 引发）：

- FEAT-019 特性档 §3.1「参考批量中断形态」明确："`batchId` 可以是 core 或 runtime adapter **内部诊断标识**，**不要求外部客户端传入**。"（另见 FEAT-019 L2 §5.2「远端调用批次状态」：「`batchId` 是 runtime 内部诊断标识，不是客户端续轮参数」）
- FEAT-028 特性档 §2.2「协同模式感知（agent-core 侧）」MUST 行 + §3.2.1「协同模式 → 协议动词 + runtime 行为映射表」把 `{batchId, items, toolCallId}` 三件套定性为 core→runtime 的 **batch interrupt envelope**——原文「FEAT-019 envelope 保持**纯结构化委托意图载体**（`batchId` / `items` / `toolCallId`）」「agent-core 在 batch interrupt envelope 中只携带 `batchId` / `items` / `toolCallId`」，是**内部**契约载体，不是客户端可见 wire。

**结论**：**batchId 按 spec 设计就不对客户端可见**，测试判据要求其可见属误读；把可见性作为 red-first 缺陷提交给开发团队，会诱导团队去暴露一个设计上不该暴露的字段。

**处置动作**：

1. C1 用例代码判据改为「SSE `agentEvent.toolCallId` ≥ 2 且互不重复」+「最终 artifact 覆盖两件事」双证（撤回对 batchId 的硬断言）——2026-08-24 定性更正后回归 PASS，实测 SSE toolCallId=4 个、5270 帧。
2. §1.1 dashboard C1 状态从 🟡 转 ✅；台账快照缺陷清单里删去「batchId 相关观察」条目。
3. Issue #93 追加更正 comment：撤回 batchId 部分诉求；只保留「TaskStore 未承载 toolCallId 归位」+「WORKING 快照全空」+「终态 artifacts 无 toolCallId 溯源」+「tool_result 侧无 toolCallId 归位事件」四条真实缺陷。

**教训**：写 red-first 判据前必须先在特性档确认「该字段是否属于客户端可见 wire」；避免把 core/runtime 内部诊断字段列为可见性要求。

### 5.5.2 定性更正：SSE 子任务 state **不是全空**（2026-08-24 R1 首跑发现）

**背景**：2026-08-24 上午 P3 SSE 20315 帧分析后，issue #93 追加评论里断言 `source.state` / `target.state` / `agentEvent.kind` **"全空"**（未承载子任务 state），并据此建议开发方向 A/B（补新字段或明确永远不暴露）。

**更正依据**（2026-08-24 下午，R1 用例首跑实测）：

- `EdpaSubscribeToTaskResubscribeTest` 首跑通过全字段递归扫描发现：SSE 帧的 `.result.artifactUpdate.artifact.metadata.agentEvent.state` **平级承载了子任务 state**——值集合观察到 `{submitted, working, ...}`（与 A2A `TaskState` 小写形态）
- 之前判据只查 `source.state` / `target.state`（agentEvent 的两个嵌套子结构），**遗漏了 agentEvent 本身的 `state` 字段**
- 同一次扫描还命中：子 taskIds=2 个（非父 taskId）、子 agentId=`search-agent`——即 SSE 通道**已承载子任务身份 + 生命周期 state 双证据**

**结论**：**"客户端应能观察到子任务粒度信息"这一诉求在 SSE / SubscribeToTask 通道已具备实现基础**；issue #93 追加评论里"SSE 子任务 state 全空"是错误观察，应当收窄为「`source.state` / `target.state` 空 + `agentEvent.kind` 空」的字段级观察，**不能上升到"整条 SSE 通道没有子任务 state"的通道级结论**。

**处置动作**：

1. R1 状态从 ⬜ 转 ✅（本轮直接绿）。
2. Issue #93 追加更正 comment：撤回"SSE state 全空"的说法，收窄为 wire 字段级差异（`agentEvent.state` 已实现 vs `source.state` / `target.state` / `agentEvent.kind` 未实现）；同时给出正面结论"SSE 通道子任务粒度信息事实可观察"，避免误导开发团队做无谓补丁。
3. **待复盘（放到跟进项，不本轮做）**：P0b/P0c 是 **GetTask 通道**下的观察面缺失，与 R1 是不同通道。R1 结果不能直接翻案 P0b/P0c——需要另跑一次 P0b/P0c 的 GetTask 快照，专门查 `metadata.agentEvent.state` 是否也承载。若 GetTask 通道下 agentEvent 也已承载，P0b/P0c 也可能能升级为 PASS；若 GetTask 通道不承载但 SSE 通道承载，则 P0b/P0c 承接的是"通道间一致性"缺陷，仍留 issue #93。**【2026-09-02 结项】**本条复盘无需再做：P0b/P0c 已于 2026-08-24 经设计与开发确认当期不承诺（out-of-scope），issue #93 已关闭且结论为「改特性文档描述，不改代码」——即"通道间一致性"这一分支在当期契约下不成立。特性档刷新后若把 GetTask 通道的子任务可见性写回 MUST，再重开本复盘。

**教训**：全字段递归扫描比预设路径断言更能保护"字段名可能变但语义可能已实现"这类情境；写"字段全空"级别的 red-first 观察前，宁可先扫全字段再下结论。

### 5.5.3 定性更正（第三次）：`toolCallId` 不是客户端可见判据，`tool_result` 事件类型不存在（2026-09-02）

**背景**：C3 `EdpaToolCallIdStableBindingTest` 自 2026-08-24 起持续红——判据是「每个 `toolCallId` 平均出现次数 ≥ 2（tool_call 派发侧 + tool_result 归位侧一致映射）」，实测旧 jar 1.00 次、新 jar 1.20 次，两轮都作为 issue #93 的第 3 条 red-first 严格证据记录在案（见 §5.6 判据升级回归表 / §5.7 新 jar 回归表；原文此处写「§5.4 / §5.6」，§5.4 不存在，2026-09-02 更正断链）。C1 硬 A 与 testplan P3 也建立在同一组字段假设上。

**更正依据**（2026-09-02，按 `docs/review-checklist.md` 第一遍 §1.1 回源核对，基线 `Technical-AF/docs` @ `7722e0b5`）：

1. **`toolCallId` 在客户端可观察面是 MAY 级扩展字段，不是 MUST。** FEAT-027 §5.9 注原文：「`toolCallId` 的产生和关联语义由 FEAT-004 / FEAT-019 定义，**不属于 FEAT-027 的最小公共字段**；delegation **可以**携带 `toolCallId`；中间 Runtime 透明透传时不得删除或改写该扩展字段。」——「不得删除或改写」只在**已携带的前提下**成立，不构成"必须出现"。
2. **FEAT-019 自己就说客户端不要依赖它。** L2 §5.4 用户可见远端投影边界：「`batchId`、`toolCallId`、`resultCategory`、队列 phase 和 latency 继续用于恢复、日志和诊断，**不构成用户侧调用图协议**。客户端调用图以 `(agentId, taskId)` 为节点，以 `delegation` 为边，并以 `status` 收敛下游 A2A Task 生命周期。」同节：「内部 `toolCallId` 仍用于把远端 outcome 回灌到正确 Core ToolCall，但**不要求复制到每个用户可见输出**。」L2 §12.3 E2E 验收场景第 13 条同向：「客户端按 `(agentId, taskId)` 和直接委派边构图，**不依赖 `batchId + toolCallId`**。」
3. **`tool_result` 在 wire 上根本不存在。** FEAT-027 §3.1 把 `agentEvent.type` 闭集钉死为 `delegation | output | status`（§2「控制与业务语义区分」MUST 亦写明「不新增 A2A 顶层事件类型」）。全文检索 FEAT-027 / FEAT-019 / FEAT-028 三份文档，`tool_result` / `toolResult` **零命中**。
4. **反方向漂移**：testplan P3 把 `source.agentId` / `source.taskId` 写成「若存在」，而这两个字段是 FEAT-027 §2 三条 wire 最小结构 MUST 的**共有必填项**，并由「并发交织」MUST 指定为客户端分流并行轨迹的**唯一依据**（§5.6：「客户端通过 `agentEvent.source.agentId + agentEvent.source.taskId` 分流交织事件」「不得使用外层父 Task ID 替代生产者 Task ID」）。

**结论**：C3 硬 2 断言的是一个**契约上不存在的对象**——即使永远红也不构成缺陷，属"红了也没人该修"的坏用例；C1 硬 A / C3 硬 1 / P3 则把 MAY 与 MUST 两个字段的强度**恰好写反**。正确做法是把两者**对调回来**：用 `(source.agentId, source.taskId)` 做硬判据，`toolCallId` 降为观察记录。

> 补充事实：FEAT-004 L2 §（远端事件观察契约）说明当前实现的 delegation 事件确实携带 member 的 `toolCallId`，因此 C1 硬 A / C3 硬 1 在真机上**大概率是能过的**——问题不在观察结果，在判据的契约依据。这类"碰巧能过"的判据最危险：一旦实现侧按 spec 停止透出扩展字段，用例会红，而红得没有道理。

**处置动作**：

1. testplan `docs/testplan/FEAT-028-*.md`：C1 硬 A、C3 硬 1/硬 2、P3/P4 期望值重写；§3 事实来源补 FEAT-027 行并修正 FEAT-019 L2 路径；§6「批次时间窗观察器」分流键从 `toolCallId` 改为 `source.taskId`；§6「全字段扫描 helper」标注 `toolCallId` 集合只作观察记录；附录 A 对应两行改写；§5 矩阵后新增修订注；§6 新增 `EdpaAgentEventScanner` 登记行（判据面 vs 对照面职责分工）。
2. 本细档：§3.3 P3/P4 与 §3.5 C1/C3 子用例设计重写（含题目修订说明）；§1.1 台账 C1/C3/P3/P4 四条转**待重跑**（旧绿灯是在旧判据下取得的，不能顺延）——**四条已于当日重跑全绿，见 §5.8**；§2.2 `BatchTimingObserver` 分流键改 `source.taskId`、新增 `EdpaAgentEventScanner` 与 `EdpaChildVisibilityScanner` 两行；§5 开头「16 条用例待建」等过期状态刷新；`FEAT-019 §88` / `FEAT-028 §278/306/430` 等行号式锚点改章节名；`§5.4` 断链改 `§5.6/§5.7`；末尾重复的 `### 3.5` 标题改 `### 3.9`；本节记录更正过程。
3. 用例代码（2026-09-02 已落地）：
   - 新增 `EdpaAgentEventScanner`——**结构化** `agentEvent` 扫描 helper。原计划是给 `EdpaChildVisibilityScanner` 增补 `type` / `target` 分组能力，实际改为新增同级 helper：后者是**按字段名**的扁平全字段递归（回答"客户端能不能看到子任务信息"，刻意不预设承载位），而新判据要断言的是 FEAT-027 的**结构化契约**（事件类型、source/target 二元组、delegation↔回程配对），必须保留 `agentEvent` 的对象结构，两种扫描职责不同，合并会把前者的"不预设承载位"优点毁掉。两者在 C1/C3 中**并用**：结构化扫描作判据面，全字段扫描作对照面与分层依据。
   - `EdpaToolCallIdStableBindingTest` → **改名** `EdpaDelegationReturnBindingTest`（判据变了，类名不能再指向 `toolCallId`）：删除 `avgOccurrences >= 2.0` 断言及其 `countToolCallIdOccurrences` helper，改按 source 二元组（硬 1）与 delegation→回程存在性（硬 2）判定；新增 INCONCLUSIVE/FAIL 分层——「有子任务证据却无 `agentEvent`」判 FAIL（FEAT-027 §2 MUST 违约），「无 delegation 且无子任务证据」判 INCONCLUSIVE（模型未派发）。
   - `EdpaBatchAtomicityTest`（C1）硬 A 换判据：由「toolCallId ≥ 2 且互不重复」改为「`type=delegation` 事件 ≥ 2 条 + `target.taskId` ≥ 2 个互不相同且非空 + `target.agentId` 非空」；硬 B 不动。
   - `EdpaHomogParallelStreamingTest`（P3）/ `EdpaHeteroParallelStreamingTest`（P4）**补强**：原代码只统计外层 `statusUpdate/artifactUpdate.taskId`（父 Task SSE 维度），**完全没有 `agentEvent` 断言**——即 testplan 里那句「若存在」在代码里表现为"根本没测"。现补硬 1（source 二元组非空 + type 落闭集）、硬 2（分流键去重 ≥ 2 且 `source.taskId` ≠ 父 taskId），P4 另加硬 3（`source.agentId` 去重 ≥ 2，异构专属）。
   - `EdpaHomogParallelBlockingTest`（P1）/ `EdpaHeteroParallelBlockingTest`（P2）：仅更正类注释里"P0b 承载位钉死后按 `toolCallId` 拿子任务时间窗"的**作废升级路径**——正确分流维度是 `agentEvent.source.taskId`，且时间窗观察面在 SSE 通道（P3/P4），不在 `SendMessage` 通道，与等不等 P0b 无关。断言逻辑本轮不动（属评审阻断 8 的范围）。
4. **三处口径一致性复查（2026-09-02 第二遍，testplan / cases / 代码三方对齐）**——上面 1~3 的首遍改动留下 6 处不一致，本遍一并收口：
   - **`delegation` 例外语（三处补齐）**：「`source.taskId` ≠ 父 taskId」若不限定为**非 delegation 事件**，会把合规实现判红——§3.1 字段适用性表规定 delegation 的 `source` 本就指向父 Agent/Task。testplan P3/C3 行、本细档 §3.3 P3 / §3.5 C3、代码 P3/P4/C3 三方均已补。
   - **静默判绿防护（三处补齐）**：`eventsUsingParentAsSourceTaskId(parentTaskId)` 在 `parentTaskId` 为空时**恒返回空列表**，断言会空转判绿。P3/P4/C3 代码均补 `assertThat(parentTaskId).isNotBlank()` 前置，文档同步写明「前置」。
   - **`type` 缺失哨兵**：旧 `parse()` 写的是 `if (type != null && !VALID_TYPES.contains(type))`，`type` 缺失会**静默通过**闭集校验——而 §2「控制与业务语义区分」MUST 要求用 `type` 区分类型、「客户端不得仅依赖 Artifact 文本内容推断事件类型」，没有 `type` 等于把类型判定推回文本推断，与取值越界同级。改为记入哨兵值 `(type 缺失)`。
   - **C1 硬 A 的逐条断言（真正的判据 bug）**：首遍写的「`target.taskId` ≥ 2 个互不相同且非空」实际只落成 `delegationTargetTaskIds().size() >= 2` 一条集合大小断言——`Set` 会**静默跳过空值、把重复值折叠为一个**，3 条 delegation 里混 1 条空 target 或 2 条共用同一 target 都能过。拆成 A-1（`delegationsMissingTarget()` 为空）/ A-2（`delegationsWithDuplicateTarget()` 为空）/ A-3（去重 ≥ 2）三条。
   - **INCONCLUSIVE 早退顺序（testplan §8 分层违规）**：C1/P3/P4 的 `delegation < 2` 早退原本置于「覆盖两件事」断言**之前**，把一个 §8 要求「模型任意规划质量下必须绿」的硬层给跳过了。三处均把覆盖层提到早退之前。
   - **到达序号窗口降级为诊断**：§2「并发交织」MUST 明文「不依赖不同生产者之间的到达顺序」——Runtime 按实际观察顺序串行写入同一条 SSE，交错与否受网络与调度影响，不能反推并行度。`arrivalWindowsBySourceTaskId()` 的 javadoc 与三方文档均标注「仅诊断、不构成判据」。
   - 另：`EdpaChildVisibilityScanner.anyToolCallId()` javadoc 由「用于 C1/C3 判据」改为撤回说明；`EdpaTerminalSnapshotTraceabilityTest`（P0c，`@Disabled`）加⚠️块说明其硬 2 的三个承载位均已作废，重启用前必须重写；`EdpaEnvelopeNoModeFieldGuardTest`（**该类已于本日晚些时候删除，见下条 6**）与 `EdpaBatchAtomicityTest` 的 `FEAT-019 §88` 行号式锚点改为 `特性档 §3.1「参考批量中断形态」`；`EdpaParallelPrompts` P5 注释的 `tool_result` 措辞加层次限定。
5. **Issue #93 需要第三次更正**：撤回「tool_result 侧无 toolCallId 归位事件」这一条。撤回后 issue #93 的四条原始诉求**已全部消解**——batchId 可见性（§5.5 撤回）、SSE state 全空（§5.5.2 撤回）、WORKING 快照全空 + 终态无 toolCallId 溯源（P0b/P0c 2026-08-24 出范围，设计侧确认当期不实现）、tool_result 侧无归位（本节撤回）。**【2026-09-02 用户确认：issue #93 已关闭】**——最终结论是**改特性文档描述，不改代码**。第三次更正评论不必再发。方法学教训仍然成立并已写入 `docs/review-checklist.md` I-M6：「issue 提出后靠追加评论反复修正结论」会让开发看不出最终结论，应在提单前把 pre-flight 三问做完。

**教训（与 §5.5 同源、第三次复发）**：写 wire 面 red-first 判据前，必须先在**特性档的能力要求表**里确认该字段的**要求级别（MUST / SHOULD / MAY）**，而不只是确认"文档里提到过这个字段"。三次误判的共同形态都是：字段确实存在于某份上游文档，但它在**那一层**是内部/诊断/扩展语义，被测试判据当成了客户端 wire MUST。建议在 `feedback_full_scan_before_red_first.md` 的 pre-flight 三问里追加第四问：**"这个字段在特性档里的要求级别是什么？是哪一层的 MUST？"**

6. **N1 判据重定位 + 「同轮发起不成立」误判更正（2026-09-02，评审阻断 2）**：
   - **代码**：删除 `EdpaEnvelopeNoModeFieldGuardTest`（恒真 + 类名误称 + 白名单缺陷）；新建 `EdpaCoordinationModeLeakGuardTest`（回归看守 + 批量语义正向判据，三面扫描，单次运行两判据共享证据）、`EdpaModeFieldScanner`（叶子名判定的共享扫描器）、`EdpaModeFieldScannerSelfTest`（**非 `manual`** 金丝雀 4 条，已变异验证）。
   - **文档**：testplan §5 矩阵 N1 行重写 + 矩阵后新增「N1 判据重定位」注、§2 加观察面声明与灰盒例外、§2 新增一条非范围（§2.2 正面举证移交 agent-core 白盒单测）、§6 fixture 表新增三行、§7/§8/附录 A 同步；本细档 §1.1 矩阵行与其后「下一步优先级」第 6/8 条、§2.2 fixture 表、§3.6 全节重写，新增 §5.9（coordinator 日志实测形态）与 §5.10（重定位全文）。
   - **误判更正**：§5.8 第 3 条「每次 delegation 数为 4 说明同轮一次性发起不成立」已推翻——delegation 事件不承载轮次归属，用它数轮次是在缺失观察面上下结论；灰盒日志按 `batchId` 聚合显示 12 批中 10 批含 2 成员、异构两 agent 同批，同轮发起**确实成立**。该误判亦已从 memory 更正。
   - **方法学**：恒真判据 → 保留运行 + 重贴标签 + 配自检（不是删除、也不是降 INCONCLUSIVE，`assumeTrue` 会跳过用例、看守价值归零）；长期恒绿的用例必须配一条不与主用例同挂 `manual` 的自检。详见 §5.10 末段。

### 5.5.4 判据重定位（第四次）：BLOCKING 通道上的时长启发式与幽灵观察面全部清除（2026-09-02，评审阻断 3 + 新发现合并修）

**触发**：评审阻断 3「P1/P2 的时间窗硬断言没有观察面」，与本轮新发现的「`ToolCallSequenceObserver` 全仓不存在却被写进判据栏」「`BatchTimingObserver` 在 P1 里是死代码」合并为一批处理（用户 2026-09-02 决定「合并成一批一起修」）。

**根因一处，四条用例同病**：P1/P2/P5/P6 **全部走 `SendMessage`（BLOCKING）**。特性档 §5.0.1 明写该模式**不产生中间流式事件**；GetTask 终态快照不含中间过程（P0b/P0c 已证，那正是它们 out-of-scope 的理由）。而这四条用例宣称要判的东西——「两子任务时间窗是否重叠」「同轮生成了几个 ToolCall」「委托的 `agent_name` 各是什么」「走的是批次路径还是单成员兼容路径」——**全是过程量**，在这条通道上**没有任何投影**。给一个没有投影的语义写硬断言，结果只有两种：假绿，或误红。

**漏红 vs 误红，两种坏法要分开看**：

| 用例 | 旧判据 | 实现方式 | 坏法 |
|---|---|---|---|
| P1 / P2 | `totalElapsed < 90s` ⇒ 并行生效 | 超限走 `assumeTrue(false)` | **漏红**：违约只会 skip，**永远红不了**，本质是个伪装成断言的诊断日志 |
| P3 / P4 | 同上，但置于**全部硬断言之后** | 超限走 `assumeTrue(false)` | **误黄**：既不会红也不该黄——把一次已经全绿的运行改判成 skip，净效果是**丢证据** |
| P5 | `totalElapsed >= 40s` ⇒ 未伪并行 | `assertThat` | **误红**：能真红，但红的原因与契约无关（缓存命中、模型答得快都能击穿下限） |
| P6 | `totalElapsed <= 90s` ⇒ 未被误批量化 | `assertThat` | **误红**：模型慢、网络抖、下游慢都能超限，与「是否被误批量化」毫无关系 |

**误红比漏红更坏，误黄第三**：漏红只是没起作用；**误红**会训练团队把这条用例当 flaky 忽略——用例一旦被贴上 flaky 标签，它连同将来可能发现的真缺陷一起被静音，是自毁价值；**误黄**则是悄悄把一次有效的绿灯变成"没测"，人看台账时还以为是模型抖动。

**幽灵观察面 vs 死代码，两个反向的形态**：

- `ToolCallSequenceObserver`：**文档里有、代码里没有**。被 testplan §5 P5 行、§6 fixture 表、§7 与本细档 §2.2 / §3.4 当作已有观察面引用，实际全仓零命中，代码里跑的是时长启发式。**且按其描述根本建不出来**：wire 无 `tool_call`/`tool_result` 事件类型（FEAT-027 §3.1 `agentEvent.type` 是闭集），「推理轮次」是 agent-core 内部概念。已从三处文档删除并记录原因。
- `BatchTimingObserver`：**代码里有、没人真用**。实现完整，但唯一引用在 P1，且是 `new` 出来立刻打一行空 `summary()`、从未 `record()` 过——因为 BLOCKING 通道**根本没有时间戳可喂**。死代码已删；类本身保留给 P5b（其算法正是 P5b 判据的否定），并在 javadoc 里写明「若 P5b 最终不建，本类应一并删除」。

**举证责任的重新分配**（这是本轮最实质的改动）：

| 语义 | 旧承接方（无效） | 新承接方 |
|---|---|---|
| 「真并行」 | P1/P2 时长启发式 | **P3/P4**：`(source.agentId, source.taskId)` 去重 ≥ 2 组（SSE 面，已重跑全绿） |
| 「异构归位」 | P2「断言 agent_name 分别为 search/verify」 | **P4 硬 3**：去重后 `source.agentId` 含两个不同下游 |
| 「不伪并行」（反证） | P5 时长下限 + 幽灵观察器 | **P5b ⬜ 待建**：SSE 面 delegation 时间窗不重叠，**告警级** |
| ~~「走单成员兼容路径而非批次路径」~~ | P6 时长上限 | **不是可判契约，缺口撤销**（2026-09-02 回源更正）：L2 §7.1 能力矩阵写「只有一个任务时保持单 ToolCall、单中断路径，**不强制批次**」，FEAT-028 特性档第 234 行同为「**不强制**走批量路径」——单实体走了批次路径并不违约，无义务可判 |
| ~~「只生成 1 个 `call_subagent`」~~ | P6 时长上限 | **明确不承接，不登记新用例**（用户 2026-09-02 拍板）。**P6 的存在价值是回归守护：证明引入并行子任务后，单任务特性不受影响。** 判据只需落在「单任务还能正常跑完」，委托数不在其职责内。L2 §7.2 单实体行前半句「只生成一个 `call_subagent`」判的是模型规划行为（planrule 提示词），与 P5b 同类且价值更低，不值得占矩阵位。**本行为已知且已定案的不覆盖项，不是漏测，后续不得作为缺口重新提出** |

**P5 为什么要拆而不是删**（用户直接问了「这个 P5 有必要吗」，结论记录于此）：**立意成立且本组合独有**——没有反证，P1~P4 的正向断言只证明「能并行」，不证明「会判断」，一个无条件全并行的实现也能把 P1~P4 全刷绿。但**立意成立 ≠ 能当硬判据**：反证判的是**模型行为**（planrule.yaml 是提示词规则），不是 SUT 代码行为；模型这一轮听不听话是随机变量，单次绿不证明 planrule 对、单次红也不证明 planrule 错。testplan §6 已承认「核心断言依赖模型规划质量」、§8 已定「LLM 抖动降 INCONCLUSIVE」——本条不该例外。故拆成：**P5 = 功能正确性（硬、必须绿）** + **P5b = 反证（告警级、待建）**，两者判读等级不同，混在一条里必然一头假绿一头误红。

**「模型合并多个实体」的口径（2026-09-02 已定，✅ 2026-09-03 已落码，见 §5.11.1）**

原 §3.4 P5 的 FAIL 依据写「对齐 §7.3 E4「模型合并多个实体」→ 验收判失败」。回源结果：

- **出处**：`develop/03-architecture/L2-Low-Level-Design/edpa/Feat-Func-028-edpa-planning-workflow-and-agent-parallel-execution.md`（EDPA L2 详设）**§7.3「错误表面验收」第 506 行**，docs 私仓 @ `6c879a2`。原文：「模型合并多个实体｜单 ToolCall 参数包含多个独立实体时，视为规划质量问题，**验收判失败**。」
- **E3/E4 是伪造锚点**：该表 11 行**全部无编号**，引用形式错误，已从两篇文档删除。
- **冲突成立，且比原先描述的更尖锐**：同表**上一行**「模型串行生成调用｜框架按实际 ToolCall 执行，不伪造缺失调用，**功能仍可完成**，但失去并行收益」是明确**容忍**。设计侧是**精确区分了两种模型行为**的：串行=可接受，合并=判死。而本方案「模型行为一律 INCONCLUSIVE」把两者一视同仁——只与前者相容。
- **冲突的真正命中点是 P3/P4，不是 P5b**：P3/P4 现在的 `delegation < 2 → assumeTrue(false)` 早退分支，正好覆盖"两个实体被合并成一个 `call_subagent`"这一情形。L2 要求这里判 FAIL，我们判的是黄。
- **判据面存在性（新证据）**：SSE wire 上**有 ToolCall 参数的投影**——`docs/issues/wire-samples/sse.txt`（旧 wire-repro 抓包）的 llm_reasoning payload 带 `tool_calls[]`，含 `name`（如 `search-agent`）与**流式分片的 `arguments`**（`{` → `"remoteInput": "帮我` → …）。按 `index` 拼回分片即可看出单个 `call_subagent` 的参数里装了几个实体。这正是 L2 §7.2 要求的验收证据「模型原始 ToolCall 列表 / 每项 `toolCallId`、目标 Agent 和脱敏后的关键参数」。**但该字段不属 FEAT-027 §2 最小公共契约**（payload 面，很可能 MAY 级），且样本未在 FEAT-028 真机复核——**只撑得起告警级判读，撑不起 MUST 级硬断言**。
- **✅ 结论（2026-09-02 定，✅ 2026-09-03 已落码）**：**不需要设计侧裁定**——L2 §7.3 已把两种模型行为区分得足够明确，这属本仓落码工作。P3/P4 现有的 `delegation < 2` 单一早退分支**拆成两支**：
  - 该轮 `call_subagent` 的 `arguments`（按 `index` 拼回流式分片后）**内含多个独立实体 → FAIL**，引 L2 §7.3 表「模型合并多个实体」行
  - `arguments` **只含一个实体**（模型这轮只做了一个主题、或把两个主题拆到两轮追加）**→ INCONCLUSIVE**，引同表上一行「模型串行生成调用」的容忍口径
- **落码前的两项约束**（来自上一条「判据面存在性」的证据等级）：①`tool_calls[].arguments` 属 payload 面、很可能是 MAY 级，**不在 FEAT-027 §2 最小公共契约内**——若某轮该字段整体缺失，只能判 INCONCLUSIVE，不得因「读不到参数」而判 FAIL；②「多个独立实体」的识别口径须与 §4 prompt 库配对写死（同一套 per-topic 关键词候选集），**不得改用语义相似度 / LLM 裁判**。落码点：P3/P4 两个 Streaming 用例的早退分支

**处置动作**：

1. **代码（7 个文件）**：P1 删 `BatchTimingObserver` import 与死代码、删时长硬 2；P2 同上；P6 删时长硬 2；P5 整体重写（新 `@Story`、方法改名 `dependentScenarioCompletesEndToEnd`、新增「搜索主题 + 核查结论」双语义片段断言）；`BatchTimingObserver` javadoc 改写为「预留给 P5b，无调用方前不得写进任何用例的观察面栏」。四条用例的时长常量一律改名为 `*_DIAGNOSTIC_HINT_MS` 并只进 `LOG.info`。
   - **顺带发现并清掉 P3/P4 的第三种坏法——「误黄」**：`EdpaHomogParallelStreamingTest` / `EdpaHeteroParallelStreamingTest` 在**全部硬断言之后**还留着 `if (totalElapsed >= 90s) { assumeTrue(false); return; }`。它既非漏红（后面已无断言可漏）也非误红（不会 FAIL），而是把一次**已经全绿**的运行改判成 skip——理由还与契约无关（模型慢、网络抖、下游慢都能超限）。**净效果是丢证据**。两处均删，常量降为 `PARALLEL_DIAGNOSTIC_HINT_MS` 诊断日志。
   - **P3/P4 的台账状态不变（仍 ✅）**：本次只删掉一条位于末尾的 skip 分支，**没有改动任何硬断言**，§5.8 的 2026-09-02 重跑绿灯仍然有效，无需重跑。这与 P1/P2/P5/P6 转 🔄 的理由不同——那四条是判据本身被换掉了。
2. **testplan**：§5 矩阵 P1/P2/P5/P6 四行重写 + **新增 P5b 行**；矩阵后新增「BLOCKING 通道判据重定位」注；§6 引言加「时长一类与契约无因果关系的代理量不得用作任何判据」；§6 fixture 表 `BatchTimingObserver` 行改「已实现、当前无用例引用」、**删除 `ToolCall 序列观察器` 行**；§7 并行性证据条目 P1~P4 → **P3/P4**、反证条目改挂 P5b；§8 修掉「P5 列入必须全绿且注明不依赖模型同轮生成」与 §5/§7 定义的自相矛盾；附录 A 两格改主权方并标注 P5b 格「当前无承接方，是已知覆盖缺口」；§2 范围/非范围同步。
3. **本细档**：§1.1 对账基准 17 → **18 条**；P1/P2/P5/P6 四行 ✅ → **🔄**（判据重写，旧绿灯不继承）、新增 P5b ⬜ 行；台账快照计数改 9✅/5🔄/4⬜；「主线全绿」表述改写并撤回「P5+P6 反证成立」的说法；§2.2 两个 fixture 行改写/删除；§3.3 P1/P2 与 §3.4 全节重写；本节新增。

**教训（与 §5.5.3 的第四问并列，建议追加为 pre-flight 第五问）**：**「这条判据宣称的观察面，在这条用例实际走的通道上存在吗？」**——本轮四条用例的共同病灶不是判据写错了值，是判据**根本不在这条通道上可观察**。以及一条更硬的纪律：**不得把任何 fixture 写进用例的「观察面」栏，除非该用例的代码里真的调用了它**；文档宣称的判据面与代码实际判据不一致，是本仓 2026-09-02 一轮评审集中清理的问题形态（§5.5.3 与本节共四次）。

### 5.6 判据升级回归（2026-08-24，5 red-first 用例 + R1 统一改用 EdpaChildVisibilityScanner）

**动机**：用户 2026-08-24 反馈"你没扫描过就敢提 Issue……前期测试非常不到位、非常不仔细、用例覆盖存在很大问题"——反思后确认 P0b/P0c/C1/C3/N1 五条 red-first 用例判据"硬编码预设承载位"存在系统性缺陷，抽出 `EdpaChildVisibilityScanner` 静态 helper（全字段递归扫描：子 taskId / 子 agentId / 子 state / toolCallId 四集合），5 条 red-first 用例 + R1 统一改用。

**回归结果**（6 用例真机跑，2026-08-24 沙箱恢复 + 修 skill routing 后稳态）：

| 用例 | 结果 | 证据摘要 |
|---|---|---|
| **P0b** | **FAIL**（严格 red-first） | 20 WORKING 快照全字段扫描四集合全空——严格证据支撑 issue #93「GetTask WORKING 快照缺子任务信息」 |
| **P0c** | **FAIL**（严格 red-first） | 终态快照 3 预设承载位 + 全字段扫描双双四集合全空——**升级后终于有全字段扫描严格证据**（之前只到"3 预设位空"这一层） |
| **N1** | **PASS**<br>**⚠️ 2026-09-02 该 PASS 的含义已被重定位，见 §5.10** | 主判据（禁止字段扫描）无命中；辅助诊断证明公开面确实承载了子任务观察证据。**更正**：当时把这条 PASS 读作「§2.2 合规」是错的——扫描面上本就不存在 envelope 投影，命中集恒为空、必然 PASS，属结构性假绿；且「辅助诊断证明公开面承载了子任务证据」与本用例的越界判据无逻辑关系，那是 C1/C3/R1 的判据。 |
| **C1** | **PASS** | SSE 1821 帧命中 childTaskIds=2、childAgentIds=[search-agent]、subStates=`{submitted,working,...}`；硬 A（toolCallId ≥ 2 互不重复）+ 硬 B（多主题覆盖）双证 |
| **C3** | **FAIL**（严格 red-first 精细化）<br>**⚠️ 2026-09-02 该判定已撤回，见 §5.5.3** | SSE 883 帧命中 2 个 `toolCallId` 但**平均出现 1.00 次**——tool_result 侧无 `toolCallId` 归位事件的严格证据，承接 issue #93 |
| **R1** | **PASS** | SubscribeToTask 800 帧命中 childTaskIds=2、childAgentIds=[search-agent]、subStates 观察到、toolCallIds=2；硬 1（首帧快照）+ 硬 2（子任务可见性）双证 |

**核心结论**：
1. **判据升级本身没引入行为变化**——3 PASS + 3 FAIL red-first 定性与首跑一致
2. **issue #93 三条 red-first 严格证据全部齐了**——P0b（WORKING 全空）、P0c（终态无溯源）、C3（`toolCallId` 只在 tool_call 侧、`tool_result` 侧无归位）<br>　**⚠️ 2026-09-02 更正**：这三条现已全部消解——P0b/P0c 于 2026-08-24 出范围（设计与开发确认当期不实现）、C3 判据于 2026-09-02 撤回（§5.5.3）。本条记录保留为历史过程，**不再作为 issue #93 的有效证据**。
3. **回归首轮失败根因**：不是 LLM 抖动，是沙箱恢复时给 SUT 的 planrule 缺 skill routing——base planrule 里 `scope.allowed/denied` 都空、LLM 走"无可用业务能力"分支直接自我介绍就 final_answer 结束。补 `additional_prompt` 里的 skill routing（明示 `search-agent`/`verify-agent` 用途 + 并行硬要求）后，LLM 走标准 `call_subagent` 路径，判据全部按预期稳定复现

**pre-flight 3 问方法学落地**（用户 2026-08-24 拍板 A+B 之 B）：
- 全字段扫过没？—— `EdpaChildVisibilityScanner` 统一 helper
- 交叉通道验过没？—— R1（SubscribeToTask）+ P0b/P0c（GetTask）+ C1/C3（SSE）三通道验证（原文含 N1，2026-09-02 移出：N1 与「子任务可见性」不同判据面，把它算进交叉验证是概念混淆）
- 特性档核对过没？—— batchId 撤回从 FEAT-019 特性档 §3.1「参考批量中断形态」 学到；agentEvent.state 从 R1 首跑翻案学到

### 5.7 新 jar 真机回归（2026-08-28，依赖统一升级后 14 用例）

**触发**：开发按 `develop/03-architecture/L2-Low-Level-Design/edpa/Feat-000-dependency-version-unification-design.md` 刷新依赖版本，新 jar 交付到 `D:\agent-solution-common\dist\`（edp-agent-engine / agent-search / agent-verify / agent-deep-research 均 8/28 构建）。全 14 EDPA 用例真机复跑。

**结果台账（14 = 11 ✅ · 2 🔴 · 1 💥）**：

| ID | 用例 | 结果 | 说明 |
|---|---|---|---|
| A1 | AgentCardAlignment | ✅ | Card 声明兼容 |
| P0a | EntryTaskUniqueness | ✅ | 入口 Task 唯一 |
| P1 | HomogParallelBlocking | ✅ 65.4s | 同类型并行同步 |
| P2 | HeteroParallelBlocking | ✅ 65.2s | 异构并行同步 |
| P3 | HomogParallelStreaming | ✅ 84.9s | 同类型并行 SSE |
| P4 | HeteroParallelStreaming | ✅ 78.7s | 异构并行 SSE |
| P5 | DependentTasksSerial | 💥 broken 0s | `search process exited before becoming ready`——SutStack 走 managed 模式而非 remote，`SUT_AGENTS_SEARCH_URL` env override 未生效。属**环境/框架 bug**，非特性缺陷。待深挖 |
| P6 | SingleEntityCompat | ✅ 36.8s | 单成员兼容 |
| C1 | BatchAtomicity | ✅ | toolCallId ≥ 2 互不重复 + 多主题覆盖 |
| **C2** | AllSettledSingleRecovery | 🔴 FAIL 46.5s | **判据"终态 statusUpdate 帧恰好 1 次"实测 = 0**——新 jar 疑似改了 SSE 终态帧策略（不再单发或字段名变）。**判据可能需适配新 wire**，待深挖 |
| **C3** | ToolCallIdStableBinding | 🔴 FAIL<br>**⚠️ 2026-09-02 该判定已撤回，见 §5.5.3** | toolCallId 平均出现 1.20 次（<2）——**tool_result 侧仍缺归位事件**（issue #93 未完全修复） |
| N1 | ~~EnvelopeNoModeFieldGuard~~<br>**⚠️ 2026-09-02 类已删除并重定位，见 §5.10** | ✅（假绿） | 越界字段 0 命中；辅助诊断新观察：`agentEvent.state` 值集合增加 `completed`（之前只有 submitted/working）。**更正**：该 ✅ 是恒真的——扫描面上不存在 envelope 投影，命中集必然为空。现由 `EdpaCoordinationModeLeakGuardTest`（看守 + 正向批量语义）+ `EdpaModeFieldScannerSelfTest`（金丝雀）替代。 |
| S1 | DataControlPlaneSeparation | ✅ | 数据面 12430 chars vs 控制面 4824 chars 显著不同 |
| R1 | SubscribeToTaskResubscribe | ✅ | 首帧快照 + 后续事件子任务可见性 |
| ~~P0b~~ | ~~SnapshotBatchProgress~~ | @Disabled | out-of-scope（SendMessage+GetTask 子任务粒度可见性当期不实现） |
| ~~P0c~~ | ~~TerminalSnapshotTraceability~~ | @Disabled | 同上 |

> **⚠️ 读表提醒（2026-09-02 追加）**：上表 P1/P2/P5/P6 的 ✅ 是**旧判据下**取得的——那时它们的「硬 2」是时长启发式（P1/P2 `<90s`、P5 `≥40s`、P6 `≤90s`）。这些判据已于 2026-09-02 全部废止（见 §5.5.4），**这四条的绿灯不代表当前判据下的结论**，台账 §1.1 中它们已转 🔄。C1/C3 的判据亦已在 §5.5.3 重写。本表仅作历史运行记录保留。

**新 jar 关键观察**（vs 老 jar）：

1. **`agentEvent.state` 增加 `completed`**（之前只 `submitted` / `working`）—— issue #93 子任务终态可见性诉求**部分修复**
2. **新 wire 承载点** `.result.artifactUpdate.artifact.parts[0].data.payload.tool_call_id` 承载 toolCallId —— 之前仅 `agentEvent.toolCallId`
3. ~~**C3 的 tool_result 归位事件仍缺**：`toolCallId` 平均出现 1.20 次（旧 jar 1.00 次），略有改善但未达"tool_call + tool_result 一致映射"的双证要求 —— issue #93 未完全修~~<br>　**⚠️ 2026-09-02 撤回**：wire 上不存在 `tool_result` 事件类型（FEAT-027 §3.1 闭集 `delegation|output|status`），`toolCallId` 属 MAY 级扩展字段（FEAT-027 §5.9 / FEAT-019 L2 §5.4）——此判据观察的是契约上不存在的对象，**不是缺陷**。详见 §5.5.3。4. **C2 的 SSE 终态帧策略变化**：老 jar 终态 statusUpdate 帧恰好 1 次，新 jar 实测 0 次 —— **判据可能需适配**新 wire（待深挖）
5. **主线 P1-P4 全绿**：并行同/异构 × 同步/SSE 4 组组合稳定，新 jar 不破坏 EDPA 并行核心能力

**待深挖项**（用户 2026-08-28 决策：先记录，跑完 agentscope 后再深挖）：
- **C2 判据适配**：从 allure 附件 dump SSE 帧，看新 jar 终态帧到底以什么形式承载（是否 SSE 侧完全不发终态、还是承载位换到 artifactUpdate.metadata、还是字段名从 `TASK_STATE_COMPLETED` 变了）
- **P5 环境问题**：查 SutStack 源码看 `SUT_AGENTS_*_URL` env override 键名映射（`sut.agents.<name>.url` → 环境变量的连字符 `_` 转换规则），确认修复方案后重跑 P5

### 5.8 `agentEvent` 判据重写后的重跑（2026-09-02）

**背景**：§5.5.3 撤回 `toolCallId` 判据后，C1/C3/P3/P4 四条的断言面整体换成 FEAT-027 §3.1 的 `agentEvent` 结构化契约，旧绿灯不能顺延，故重跑。

**运行环境**（与 2026-08-24 首轮的差异必须一并读）：

| 项 | 2026-08-24 首轮 | 2026-09-02 本轮 |
|---|---|---|
| SUT | `edp-agent-engine-0.1.0` + `agent-search-0.1.0` + `agent-verify-0.1.0`（本地 M2） | 同左（未换 0.1.1，见下「未覆盖」） |
| LLM | `deepseek-v4-pro-0813` @ aliyun 兼容端点 | 同左 |
| search-agent 检索 | 真 Tavily | **`SEARCH_AGENT_USE_STUB=true`**（本环境无 Tavily 密钥；`SearchAgentProperties.requireConfigured` 对 `tavily-api-key` 硬校验，不置 stub 则 search-agent 启动即 fail-fast） |
| scenarioHome | `/tmp/edpa-scenario-min`，planrule 通用化 | 同左（planrule 从 jar 内 `governance/planrule.yaml` 提取后改 `scope.allowed` 非空 + `additional_prompt` 注入 `search-agent`/`versatile-agent` skill routing 与同轮并行硬要求） |

**结果：4/4 PASS。**

| ID | 类 | 帧数 | agentEvent 统计 | 判据结论 |
|---|---|---|---|---|
| C1 | `EdpaBatchAtomicityTest` | 2293 | 1025（delegation 4 / output 1009 / status 12） | 硬 A-0~A-3 全真判 + 硬 B `coversSearch=true coversVerify=true` |
| C3 | `EdpaDelegationReturnBindingTest` | 1896 | 834（4 / 818 / 12） | 4 条 delegation 全部有回程；分流键 5 组 |
| P3 | `EdpaHomogParallelStreamingTest` | 2142 | 1163（4 / 1147 / 12） | 硬 1/硬 2 真判；totalElapsed=55784ms < 90s |
| P4 | `EdpaHeteroParallelStreamingTest` | 2054 | 1036（4 / 1020 / 12） | 硬 1/硬 2/硬 3 真判（`sourceAgentIds` 3 个）；totalElapsed=58584ms < 90s |

**四轮共同的 wire 事实**（重写后的判据面首次被真机确认非空转）：

- `unknownTypes=[]` —— `agentEvent.type` 四轮全部落在 `delegation | output | status` 闭集内，且无 `MISSING_TYPE` 哨兵命中，即 `type` 字段的 MUST 级存在性成立。
- `missingSource=0` —— `source.agentId` / `source.taskId` 二元组四轮全部非空，FEAT-027 §3.1 三条 MUST 中的两条被正面实证（这正是原 P3 写成「若存在」的等级漂移所掩盖的面）。
- `delegationTargets=4` 且 `target.taskId` 两两不同 —— 集合基数陷阱（空值/重复被 `size()>=2` 静默吸收）在真机上没有触发。
- 分流键 `(source.agentId, source.taskId)` 去重稳定 5 组（1 父 + 4 子），四轮一致。

**观察记录（不作判据）**：`toolCallId` 仍在 wire 上出现，承载于 `agentEvent.toolCallId` 与 `parts[0].data.payload.tool_call_id` 两处，四轮均为 4 个、互不重复。与 §5.5.3 的定性一致——存在但属 MAY 级扩展字段，不进判据。

**本轮未覆盖 / 需注意**：

1. **stub 检索是本轮与首轮的唯一 SUT 行为差异**。它只影响 search-agent 返回内容，不改变 EDPAgent 的委托拓扑，故 `agentEvent` 结构面判据不受影响；但 C1 硬 B 的「final_answer 覆盖两主题」是内容判据，在真 Tavily 下应复验一次。
2. **未换 0.1.1 jar**。`D:\agent-solution-common\dist\` 已于 2026-08-31 出 `edp-agent-engine-0.1.1.jar` / `agent-search-0.1.1.jar` / `agent-verify-0.1.1.jar`，而 `application-local.yml` 仍声明 0.1.0。本轮刻意保持 0.1.0 以便与首轮同基线对比；**升 0.1.1 是独立决策，升完须整批重跑**（含 §5.7 的 C2 待深挖项）。
3. ~~**每次 delegation 数为 4 而非 2**：两个主题产生 4 条 delegation（模型分两轮各发 2 条）。这不违反任何现行判据（硬 A-3 只要求 member 去重 ≥ 2），但说明「同轮一次性发起」在真机上不是每轮都严格成立——若后续要把「同轮 ≥2」升为硬判据，需先在 wire 上定义「同轮」的可观察投影（当前无该观察面，同阻断 3）。~~
   **⚠️ 2026-09-02 更正——上述推断是误判，已推翻。** 「4 条 delegation」推不出「模型分两轮各发 2 条」，更推不出「同轮一次性发起不成立」：SSE 上的 delegation 事件**没有承载轮次归属**，用它数轮次属于在缺失观察面上下结论。补做灰盒日志核验后事实相反——按 `RemoteInvocationBatchCoordinator` 状态行的 `batchId` 聚合，**12 个批次中 10 个含 2 个不同 `toolCallId`**，且 `search-agent` 与 `versatile-agent` 出现在**同一个 batchId** 下，即同轮一次性发起在真机上**确实成立**（形态见 §5.9）。真实成因是模型在得到第一批结果后又发起了第二批（跨轮次的追加委托，属正常规划行为），而非把同一轮拆成两轮。**教训**：这条误判与 N1 假绿同源——都是在「观察面不承载该语义」的地方硬下结论；下结论前必须先问「这个语义在我看的这个面上有投影吗」。该误判此前也写进了 memory，已一并更正。

### 5.9 coordinator 状态行的实测形态（灰盒，2026-09-02）

**取证方式**：`ManagedSutInstance.logFile()` 读 `<logDir>/edp-agent/stdout.log`。该文件由 `ProcessLauncher` 以 **appendTo 方式跨轮次累积**，因此必须在发请求**前**记录字节偏移、事后只读新增段，否则会把历史轮次的行算进来。

**实测行形态**（`RemoteInvocationBatchCoordinator`，字段顺序固定、共 7 个 `key=value`）：

```
Remote invocation state parentTaskId=<父 taskId> conversationId=<ctx> batchId=<uuid>
  toolCallId=call_xxx remoteAgentId=search-agent state=COMPLETED latencyMs=13179
```

**用途与限制**：

- 判据②按 `batchId=(...)\s+toolCallId=(\S+)\s+remoteAgentId=(\S+)` 聚合，正面实证 FEAT-019 §3「至少能表达批次、成员列表」。实测 12 批中 10 批含 2 成员，异构两个 agent 同批出现。
- **这 7 个字段不是 envelope 的完整投影**，而是 coordinator 打印的选择性固定字段集，且用例无从区分哪些来自 envelope、哪些是 coordinator 自身上下文——所以它**修不好 N1 的不可判定性**（见 §5.10），只能用来做正向实证与多扫一层泄漏。
- 这 7 个键名全部不命中 `EdpaModeFieldScanner` 的禁止规则（金丝雀 canary-3 对此有专门断言，防止规则收紧后在真机上误红）。
- **日志格式非契约**，实现可自由变更；缺失时判 INCONCLUSIVE，不判 FAIL，也不得据以提缺陷。

### 5.10 N1 判据重定位：从「§2.2 wire 硬断言」到「回归看守 + 正向实证」（2026-09-02）

**问题**：原 `EdpaEnvelopeNoModeFieldGuardTest` 宣称验证 FEAT-028 §2.2「agent-core 不在 batch interrupt envelope 中重复声明协同模式」，实测两轮均 PASS（§5.6 / §5.7）。复核发现这是**结构性假绿**：

1. **不可判定**：FEAT-019 §3 外部接口表把 batch interrupt envelope 定位在 **core → runtime adapter** 内部面，§3 抬头明示 FEAT-019「不固定 Java 类名、包路径、内部 DTO 名称或具体序列化字段」。它在任何 A2A wire 面上都没有完整投影，SIT 拿不到「envelope 的全部字段」，「不含某字段」这一**全称否定**不可判定（T-M15）。旧实现实际在 SSE 上扫黑名单，命中集恒为空、必然 PASS。
2. **类名误称**：`EnvelopeNoModeFieldGuard` 这个名字指认了它从未观察到的对象。
3. **灰盒不解决问题**（已验证的负面结果）：换到进程日志面同样不行——日志打的是选择性固定字段集（§5.9），不是完整投影。同理，「字段集 ⊆ §2.2 给的 `{batchId, items, toolCallId}` 闭集」这个思路也失败，原因相同。
4. **看守自身有缺陷**：白名单套在**整条累积 JSON path** 上（`pathLower.contains(entry)`），任何祖先节点名含 `model` 就赦免其下所有后代字段——看守可被静默缴械；白名单条目自标「假想合规字段」、无出处，违反 T-M21；禁止词用宽子串（`async` / `sync` / `execution`）会误红 `asyncTimeout` / `syncedAt` / `executionId`；javadoc 写了 INCONCLUSIVE 分支但代码里根本没实现。

**整改**（对应 testplan §5 矩阵 N1 与本档 §3.6）：

| 动作 | 说明 |
|---|---|
| §2.2 正面举证移出本仓 | 已写入 testplan §2 非范围；责任在 agent-core 白盒单测 |
| 保留用例、重贴标签 | 恒真的危害是绿灯含义不实，不是用例本身没用——扫描面够宽的黑名单是有价值的**回归看守**。类改名 `EdpaCoordinationModeLeakGuardTest`，javadoc 首段明写「本用例**不**验证 §2.2」「绿灯 = 未出现已知形态」 |
| 扫描面从 1 扩到 3 | SSE 全帧 + 终态 GetTask 快照 + edp-agent 本轮新增日志段；单次真机运行两条判据共享证据，不跑两遍 130s |
| 修白名单缺陷 | 判定与白名单**只对叶子字段名**生效；白名单清空，新增须注明出处（T-M21）；禁止规则改为「精确集 ∪ `*mode`/`*_mode` 后缀」，消除宽子串误红 |
| 加金丝雀 | 扫描逻辑抽为 `EdpaModeFieldScanner`，配**不打 `manual`** 的 `EdpaModeFieldScannerSelfTest`（4 条，不起 SUT、毫秒级）。**已做变异验证**：把 `isForbiddenLeaf` 强制 `return false` 后 4 条中 3 条转红并给出预期诊断（「看守失效：合成违规字段未被检出……本轮的绿灯不可信」），恢复后 4/4 绿 |
| 补正向判据 | 灰盒日志能证有不能证否——按 `batchId` 聚合正面实证 FEAT-019 §3「至少能表达批次、成员列表」，即判据② |
| 去掉无关辅助诊断 | 移除挂在 N1 上的 `EdpaChildVisibilityScanner` 子任务可见性诊断：它与越界判据无逻辑关系，却曾被 §5.6/§5.7 当作 N1 的「证据」引用，是假绿的成因之一 |

**方法学沉淀（可复用到其他用例）**：

- 恒真判据的处置不是「删掉」也不是「降 INCONCLUSIVE」（`assumeTrue` 会跳过用例，看守价值归零），而是**保留运行 + 重贴标签 + 配自检**。
- **任何长期恒绿的用例都必须配一条能证明它会开火的自检**，否则无法区分「没问题」与「探针坏了」；自检不得与主用例同挂 `manual`，否则一起被挡掉。
- 下断言前先问：**这个语义在我要看的这个面上有投影吗**？没有投影就不是「判据」，最多是「看守」——两者的绿灯含义必须在文档与代码 javadoc 里分别写清。

### 5.11 三条待落码判据的落码记录（2026-09-03）：一条 wire 事实 + 一次判据自我推翻

**背景**：§1.1 台账此前挂着三条 ⬜——C2 硬 2、S1 硬 2、P3/P4 的合并实体 FAIL 分支。本次一并落码，编译通过，并新增第二只金丝雀 `EdpaJudgeSelfTest`（11 条全绿）。过程中产出两条**设计文档里没有、也不可能从文档推出**的结论，记录如下。

#### 5.11.1 wire 事实：`tool_calls[].arguments` 是 token 级分片，不重组则判据恒不开火

实测样本路径 `result.artifactUpdate.artifact.parts[].data.payload.tool_calls[]`（`data.type == "llm_output"`），形态是 **OpenAI 风格增量 delta**：

| 帧 | `index` | `id` | `name` | `arguments` |
|---|---|---|---|---|
| 1 | 0 | `call_84e0…` | `search-agent` | `""` |
| 2 | 0 | `null` | `""` | `"{"` |
| … | 0 | `null` | `""` | 每帧几个字符 |

**含义**：任何「看 ToolCall 参数内容」的判据，若按**单帧**实现，看到的永远是几个字符的碎片，**永远不可能命中两个主题词**——即判据在实现层面就是**漏红**的，且绿得毫无意义（恒真）。必须按 `(payload.task_id, tool_calls[].index)` 跨帧重组，这就是 `EdpaToolCallArgumentsAssembler` 存在的唯一理由。**这条事实不在任何设计文档里**，只能从真机 wire 样本得出，故记入本节备查。

**判级**：重组不出参数时判 `UNDECIDABLE` → INCONCLUSIVE，**不得 FAIL**。依据 FEAT-029 §1/§3.1——agent-runtime 只**透传** payload，`toolCallId` 与 `agentEvent` 是并列关系而非包含关系，因此 `tool_calls` 属 payload 业务内容，**不是 FEAT-027 的 wire MUST**，缺失不构成违约。

#### 5.11.2 C2 硬 2 的判据形态在落码时被推翻（⚠️ 与 MR !123 已提交稿偏离）

**要点先说**：testplan §5 C2 行原写的硬 2 是「**最后一条子回程之后，父段恰好 1 段**」。落码时发现，**这条与它要替代的那条犯的是同一个错——恒真**。

- 按定义，最后一条子回程**之后**不再有任何子段帧，因此剩余帧必然连成**恰好一段**。这个「1」不是被测系统挣来的，是分段定义送的。
- 它唯一能抓的失效是「一段都没有」，即父 Agent 压根没出汇总——与硬 1「终态帧恰好 1 次」抓的粗粒度失效同级。
- **改数「父段总数」也不行**，那是**误红**：实测常见形态是 4 条 delegation 分 2 轮（跨轮追加委托），每轮 all-settled 后各出一个父段，合法产生多个父段。

**改判为**：**每个父段起点处，「已派发 − 已回程」必须为空集**。

| 形态 | 轨迹 | 差集判据 | 原「父段总数」判据 |
|---|---|---|---|
| 逐成员触发（**缺陷**） | 甲乙都派发 → 甲回程 → **父段**（乙在途）→ 乙回程 → 父段 | 🔴 红（应红）| 🔴 红 |
| 跨轮追加委托（**合规**） | 甲乙派发 → 甲乙都回程 → 父段 → 丙派发 → 丙回程 → 父段 | 🟢 绿（应绿）| 🔴 **误红** |
| 最后一条子回程后数段数 | 任意 | — | 🟢 **恒绿** |

两个方向都由金丝雀钉死（`perMemberRecoveryIsCaught` / `crossRoundAppendedDelegationIsNotFlagged`），后者还额外断言该合成轨迹确有 ≥2 个父段——否则这条测试什么都没守住。

**落码中的三个实现陷阱**（都是能造成静默假绿/假红的那类）：

1. **仅含 delegation 的帧必须透明跳过**。§3.1 规定 delegation 的 `source` 指向**父**，若把它当父段帧：跨轮场景会凭空切出父段（误红），两个相邻子段之间夹一条 delegation 会被割断（漏红）。
2. **先判违规、后吸收本帧事件**。父段起点的「当时」状态不能包含本帧自身，否则刚回程的那个成员会被算进「已回程」，逐成员触发漏红。
3. **`sourceTaskId` 不能拿去比 `parentAgentId`**（§5.7「两个维度不得混淆」）。初版写成 `!e.sourceTaskId.equals(parentAgentId)`，恒为 true，等于过滤失效。

**四个不可判定出口**：无 delegation / `source.agentId` 不唯一（嵌套委托）/ delegation 缺 `target.taskId`（差集建不全 → 漏红）/ **全程无子任务终态 `status`**（「已回程」恒空 → 恒红）。另有「已派发集与终态回程集完全不相交」的一致性守卫。

> **⚠️ 文档状态**：MR !123（gitcode `Technical-AF/docs`，分支 `testplan/feat-028-edpa-sit`）提交的 testplan 里仍是被推翻的旧措辞。本地 testplan 与本细档已改为新判据，**是否补第二个 commit 到该分支需用户裁定**。

#### 5.11.3 P4 的主题词集不能用实体名

P4 的两件事（查虚拟线程特性 / 核查一条关于虚拟线程的说法）**都围绕「虚拟线程」**。若沿用 P3 的实体名词集，任一 ToolCall 参数都会同时命中甲乙两集合，`MERGED` 判据退化为**恒红**。故异构侧改用「**动作意图**」词：甲＝`核心特性`/`特性说明`/`官方特性`/`有哪些特性`，乙＝`OOM`/`线程池`/`核查`/`是否准确`/`这个说法`。金丝雀 `topicSetsAreMutuallyExclusive` **逐词**断言四个集合两两不交，并专门钉「『虚拟线程』不得进入任一异构词集」。

#### 5.11.4 方法学沉淀（承接 §5.10）

- **「替代判据」也要过一遍恒真检查**。本次推翻的那条，是上一轮为了修恒真判据而写的替代品——修恒真时写出了一条新的恒真，说明「换个观察量」不等于「换出了鉴别力」。落码是最后一道检查关：**能不能写出一条让它变红的合成输入**，写不出来就说明判据没有鉴别力。
- **红绿两个方向都要有金丝雀**。只钉「该红时红」会漏掉误红；只钉「该绿时绿」会漏掉恒绿。本次每条判据都配了两个方向，C2 的「不误红」用例还额外断言输入本身确实触发了被测路径。
- **金丝雀首跑就自证了价值**：初版合成 JSON 少闭合一层花括号，4 条直接 error。若没有这层自检，这个错误会以「主用例恒绿」的形式潜伏下去。
- **判级要与契约等级对齐**：三条新判据的「观察面缺失」全部路由到 INCONCLUSIVE 而非 FAIL，因为它们观察的 `tool_calls` 是 payload 业务内容而非 wire MUST。等级判错的代价是「红得没有道理」，与 §5.5.3 撤回 `toolCallId` 判据是同一类错误。

## 6. 风险与备注

### 6.1 与 FEAT-028 相关特性的责任分界

- 本细档仅覆盖 FEAT-028 主权面 + 组合契约端到端观察面；不重复 FEAT-019 / FEAT-004 / FEAT-006 / FEAT-015 / FEAT-016 / FEAT-027 单点契约。责任分界详见 testplan 附录 A。

### 6.2 LLM 抖动对并行断言的影响

- **L2 §7.3「错误表面验收」表「模型串行生成调用」行**明确容忍模型串行生成同轮多委托（原文「框架按实际 ToolCall 执行，不伪造缺失调用，功能仍可完成，但失去并行收益」），本方案对此**不判失败**，而是标 INCONCLUSIVE 记录 ToolCall 序列供 prompt 优化。*（2026-09-02 两处更正：①原写「§7.3 E3」——该表 11 行全部无编号，E3 属伪造锚点；②原写「planrule.yaml 明示……=提示词质量问题」——原文无此措辞，出现「规划质量问题」的是**同表另一行**「模型合并多个实体」，且那一行判**失败**不判容忍，见 §5 该节。）*
- **与「合并多个实体」的分界**：串行生成 → INCONCLUSIVE；单 ToolCall 参数内含多个独立实体 → **FAIL**（✅ 2026-09-03 已落码；口径见 §5「模型合并多个实体的口径」节，wire 事实与实现见 §5.11.1）。两者都表现为「同轮 delegation < 2」，但设计侧精确区分了，不得一视同仁吸收成 INCONCLUSIVE。
- 若发现某条 prompt 长期无法稳定触发同轮生成，考虑：①改用更明确的并行指令措辞（如「请**同时**并行执行，勿等待」）；②切换到低温度模型；③把该 prompt 作为「模型能力弱面」标注，转到 core rails testplan 的模型规划质量层承接。

### 6.3 ~~P0b 承载位的实现事实依赖~~（2026-09-02 整节失效）

- **本节已失效**：P0b 已 out-of-scope（依据特性档 §5.0.1，见 §1.1 该行），不存在「首轮真机钉死承载位」这个使命，也不存在待反馈的 spec-vs-impl 口径分歧。
- C1/C3 曾计划以 P0b 的 dump 结论作为断言的稳定参考，现已改为 **FEAT-019 L2 §5.4 指定的客户端调用图坐标系**（节点 `(agentId, taskId)`、边 `delegation`、生命周期由 `status` 收敛），不再依赖 P0b。
- **保留本节标题只为存档**：`EdpaSnapshotBatchProgressTest` / `EdpaTerminalSnapshotTraceabilityTest` 仍以 `@Disabled` 归档在仓内，特性档刷新可见性边界后按新契约面复审时，从这里接回上下文。

### 6.4 N2 观察面的部署依赖

- N2 「agent-core 不直连 registry」的观察需要在 registry-discovery-center 侧插桩 `RegistrySniffer`；若观察面缺失，本条长期 INCONCLUSIVE。
- 长期 INCONCLUSIVE 不等于绿——建议后续补 registry 侧 mock/sniffer，或在联合调试环境搭建可观察面。当前状态下，N2 契约合规性的信心主要依赖代码审阅 + FEAT-016 主权验证的间接证据。

### 6.5 异步非阻塞模式（模式二）的延迟覆盖

- 本方案版本明确不覆盖异步非阻塞模式（依赖 FEAT-004 增强，特性档 §5.3/§6.2 明示「不是 FEAT-028 agent-core 主权」）。
- FEAT-004 增强就绪后，另立一批用例覆盖异步模式下的 P1/P2/P0b/P0c（可复用现有 fixture）；届时矩阵扩为 20+ 条。

### 6.6 与 `PlanAgentParallelTransferStreamingTest` 的关系

- `src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentParallelTransferStreamingTest`（挂 `@Feature("FEAT-004")`）测的是 openjiuwen `edpa-plan-agent` + `edpa-adapter` 在 `parallel-transfer` profile 下的业务并行——**不属 FEAT-028 主权面**（其 SUT 是 openjiuwen 变体、观察面是业务 UI 步骤驱动）。
- 该用例的**驱动经验**可参考（如 `_remote_invocation.{batchId,toolCallId}` × 2 的识别、childCid 推导、并发驱动子腿），但不作为 FEAT-028 的落点引用。本方案的落点全部新写在 `src/test/java/com/huawei/ascend/sit/cases/integration/edpa/`（新建目录，与 deepagent_deepresearch 平级）。

### 6.7 与 FEAT-001 已建能力的复用

- A2A wire 事实（`SendMessage` 结果包一层 task、`GetTask` 结果裸 Task、流式帧 `statusUpdate` / `artifactUpdate` 形态、`ROLE_USER` 枚举名）已在 [[a2a-wire-contract]] 记忆与 `docs/cases/FEAT-001-*.md` §7 钉死，本方案写用例直接沿用，不再重复探测。
- `MockCallbackReceiver.failFirst` 故障注入能力（FEAT-001 D6 补建）本方案未使用（不覆盖异步 callback）；异步模式覆盖时可复用。


### 5.2 2026-08-24（下午）二次深挖修正与待分析

#### 5.2.1 SSE 事件流精细化观察修正（原 §5.1 部分结论修订）

**新事实**：15753 帧 SSE 全字段递归扫描后，SSE 事件流其实完整承载了「delegation 派发事件」：
每次派发子任务都推一个 artifactUpdate 帧，metadata.agentEvent 承载 `toolCallId` + `target.{agentId,taskId}` + `source.{agentId,taskId}` + `type=delegation`。artifactId 命名规范为 `delegation:<父 taskId>:<子 taskId>`。

**缺陷范围应大幅收窄**：
- ✅ SSE 事件流（SendStreamingMessage）：**已实现完备**——含子任务分流标识，满足 FEAT-028 §5.0.1 「数据面透传按 source/target 分流渲染」契约
- ❌ 父任务 GetTask 快照（SendMessage 路径）：**完全无 delegation 结构**（当时记「P0b/P0c/C1/C3 缺陷仍成立」）
- ❌ 明确 `batchId` 聚合标识：两条通道均缺（可通过共同 source.taskId + type=delegation 时序推导，但非直接判定）

Issue #93 已加 comment 反映此收窄；修复方向不变（TaskStore 侧对齐事件流的 agentEvent 结构即可）。

> **⚠️ 2026-09-02 更正**：上面两条 ❌ 都已不再成立为缺陷。GetTask 快照无 delegation 结构 → P0b/P0c 于 2026-08-24 经设计与开发确认**当期不承诺**该能力，已 out-of-scope（§1.1）；`batchId` 缺失 → 按 FEAT-019 特性档 §3.1 本就是内部诊断标识，不对客户端可见（§5.5）；C1/C3 的原判据已于 2026-09-02 撤回重写（§5.5.3）。issue #93 已关闭，结论为「**改特性文档描述，不改代码**」——**"修复方向不变（TaskStore 侧对齐）"这句作废**，SUT 侧无需变更。本段保留为历史观察记录。

#### 5.2.2 [已迁出] SendMessage 无 returnImmediately 时返回 FAILED

**2026-09-02 迁出**：该观察断言的是 FEAT-001 §5.1.6「阻塞 S2C 语义」，与 FEAT-028 的 EDPA 并行主题无关，不应记在本档。分析与跟进已整体迁至 [`FEAT-001-standardized-agent-service-entrypoint-deepagent.md` §6.9](FEAT-001-standardized-agent-service-entrypoint-deepagent.md)。

本档只保留与 FEAT-028 用例设计直接相关的一句：**本方案所有 `SendMessage` 形态的子用例一律携 `configuration.returnImmediately=true`**，否则 198ms 即返回 `TASK_STATE_FAILED`、拿不到任何观察面。该参数属规避手段，不是 FEAT-028 的断言对象。

#### 5.2.3 [归档 · 已退出 FEAT-028] P7 混合终态（部分子任务 INPUT_REQUIRED）三跑真机记录

> **状态说明（2026-08-24）**：本条目原为 FEAT-028 P7 用例的真机记录；设计团队于
> 2026-08-24 确认 FEAT-028 当前**不考虑子任务 INPUT_REQUIRED 投影 + 客户端接续**，
> 相关能力由 FEAT-008 相关方案在其成熟后另立条目验收。本节保留原三跑对照 + 灰色地带
> 清单作为**跨特性对齐材料**（对 FEAT-008 验收方案有直接参考价值），但**不再作为 FEAT-028
> 的场景条目**——不进 §1.1 覆盖看板、不在 §8 执行策略中调度、代码已删。

**结论先行**（2026-08-24 P7 用例第 3 跑，退出 FEAT-028 前）：EDPAgent runtime 有能力把
子 agent 的 INPUT_REQUIRED 投影到父 Task、客户端可用父 taskId 续接达 COMPLETED——即
**FEAT-008「远端交互式中断投影 + 同 Task 续接」双契约在 EDPA 组合场景下实测端到端可用**；
但**触发稳定性受 LLM 影响明显**（同一 prompt 跑 3 次结果不同），需 FEAT-008 验收方案对
「父 EDPAgent 收到子任务 INPUT_REQUIRED 应无条件投影 vs 允许 LLM 自主决定继续规划」的
语义边界给出契约结论。

**三跑对照表**

| 跑次 | Prompt 版本 | 观察序列 | 硬 1 | 硬 2 | 软层 | 结论/根因 |
|---|---|---|---|---|---|---|
| 1 | "DeepSeek 官网的模型报价" | `[WORKING, COMPLETED]` | ⛔ | — | — | LLM 抖动：EDPAgent 侧模型自作主张替用户选了 SKU（`SubagentDelegateRail` desc 已含"如 deepseek-chat、deepseek-reasoner"），search-agent 收到已扩写的 desc 就直接查询完成，未触发 ask_user |
| 2 | 加"【一字不差原样转发】"约束 + 70s park timeout | `[WORKING]` | ⛔ timeout | — | — | 服务端日志证实 search-agent 触发了 4 次 `ask_user` tool_call，但 70s 内父未投影 INPUT_REQUIRED（超时太短）+ 观察到 EDPAgent 侧 LLM 在子任务返回后又发起了新一批 subagent 调用把 SKU 补上（首轮误判为契约缺失） |
| **3** | 同跑次 2 + 90s park timeout | `[WORKING, INPUT_REQUIRED]` | ✅ | ✅ HTTP 200 | ✅ COMPLETED，final_answer 覆盖两件事 | **契约完整生效**：父 Task ≈40~60s 后转 INPUT_REQUIRED（history 中包含子 agent ask_user 生成的澄清问题：`[型号] DeepSeek 有多款模型，...`）；用父 taskId 续接 `DeepSeek-V3`；续接后重新触发 search 完成查询并汇总 |

**关键实测事实**

1. **契约实现存在**：`EdpaMixedTerminalStateTest` 第 3 跑证明 EDPAgent runtime **有能力**把子 agent
   的 INPUT_REQUIRED 投影到父 Task；客户端**用父 taskId 续接被接受**且能续跑到 COMPLETED。
2. **触发不稳定**：同一 prompt 在跑次 1 被 EDPAgent 侧 LLM「自作主张补 SKU」绕过投影；跑次 2/3
   显式约束"原样转发"后子 agent 才能真触发 ask_user。语义边界在设计对齐时应明确：
   - **选项 A**：子任务 INPUT_REQUIRED 无条件投影到父（当前 pass 跑证据支持）
   - **选项 B**：允许父 core LLM 自主决定要不要 forward（当前 fail 跑证据支持）
   两种选项对 EDPA 场景意义完全不同：A 保证客户端体验一致，B 增加自动化能力但吞噬 ask_user 会引入错误。
3. **服务端日志证据**：`RemoteInvocationBatchCoordinator.Remote invocation state` 只记 batchId
   /parentTaskId/conversationId，**未记子任务返回的 state 值**——客户端只能靠 GetTask 轮询父任务
   状态推断，日志侧观测面稀薄（若要断言"子 A 投了 B 没投"级别的细粒度契约，需要 runtime 侧
   补 diagnostic log）。
   > **⚠️ 2026-09-02 更正**：此条描述已过时。当前 jar 的 coordinator 状态行是**七字段**
   > `parentTaskId` / `conversationId` / `batchId` / `toolCallId` / `remoteAgentId` / `state` / `latencyMs`，
   > **含 `state`**（实测形态见 §5.9）。当时的结论要么基于更早的 jar，要么是只读了行首几个字段。
   > 差异原因未回溯确认，因此不据此提缺陷；以 §5.9 的实测形态为准。
   > 注意这不改变"日志格式非契约"的口径——它仍只能用于灰盒实证与 INCONCLUSIVE 降级。

**待与开发/设计对齐的灰色地带（用户 2026-08-24 主动同步）**

| # | 灰色地带 | 本轮实测线索 |
|---|---|---|
| 1 | 多子任务同时 INPUT_REQUIRED 时如何区分 | 本轮跑次 3 只有 1 个子任务进 INPUT_REQUIRED（另一个 COMPLETED），未构造出多子任务同 INPUT_REQUIRED 场景；父 history 中的 ask_user 问题只包含一条 |
| 2 | 续接消息如何定向到特定子任务 | 跑次 3 客户端只发了一条续接文本 `DeepSeek-V3`（无子任务定向标记），runtime 侧仍能正确把它送到那个 park 的 search 子任务——**runtime 有隐式定向机制**（可能基于 park 顺序或 conversationId 内部路由）；wire 层无客户端可见的定向字段 |
| 3 | INPUT_REQUIRED 与部分失败的组合语义 | 本轮未测（未构造子任务失败场景），需另立 P8/P9 类用例覆盖 |
| **4（本轮新加）** | 父 EDPAgent 何时投影 INPUT_REQUIRED vs 何时让 LLM 自主决定 | 跑次 1 与跑次 3 同 prompt（仅措辞差异）表现完全相反——契约语义应明确「投影是硬规则还是软建议」 |

### 3.9 [已退出 FEAT-028 范围] 混合终态与接续场景

> **编号与位置说明（2026-09-02）**：本节原标题为 `### 3.5`，与上文 §3.5「组合契约面（C1~C3）」**标题重复**，锚点冲突已改为 §3.9。本节物理位置在文件末尾（§5.2.3 之后）属历史追加所致，内容仍归属 §3 子用例设计一章。

**2026-08-24 设计团队确认**：FEAT-028 当前**不考虑子任务 INPUT_REQUIRED 投影 + 客户端接续场景**。
相关能力（远端交互式中断投影、同 Task 续接、多子任务同时 INPUT_REQUIRED 的定向续接）
由 FEAT-008 相关方案在其成熟后另立条目验收。

原 P7 用例设计已从本节移除；曾在 2026-08-24 落地的两个测试类
（`EdpaMixedTerminalStateTest` 同步路径 + `EdpaMixedTerminalStateStreamingTest` SSE 路径）
以及 `EdpaParallelPrompts.PROMPT_MIXED_TERMINAL_STATE` / `PROMPT_MIXED_TERMINAL_RESUME` 常量
已同步删除。

历史真机探测记录（三跑对照 + 灰色地带清单）作为**跨特性对齐材料**保留在 §5.2.3。

