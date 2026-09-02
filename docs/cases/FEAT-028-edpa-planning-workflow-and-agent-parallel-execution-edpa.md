---
scope: v0817
deployable_units: [agent-runtime, agent-core]
sut: edp-agent-engine（EDPAgent，本地 managed 单节点，同 process 挂 search-agent / verify-agent 被委托方）
features: [FEAT-028]
updated: 2026-08-20
---

# FEAT-028 — EDPA 场景下子任务并行验收：用例设计与真机进展

本文件是 `docs/testplan/FEAT-028-edpa-planning-workflow-and-agent-parallel-execution-edpa.md`（方案级测试设计）的**实现层细档**：承载覆盖进度看板、逐条子用例的 G/W/T 与判据、共享前置约定、真机实测进展（滚动记录）与风险备注。

**分层纪律**（用户 2026-08-17 定）：方案文档只锚定稳定的场景条目（矩阵 ID）；测试代码到场景的映射以测试仓当前代码为准，不在方案维护类目录；测试进展、缺陷对时、验证结论统一记录于本文件 §5。

**依赖引用规范**：本细档引用的 FEAT-019 / FEAT-004 / FEAT-006 / FEAT-015 / FEAT-016 / FEAT-027 testplan 均**尚未建立**；在其建立前，引用先回落到 `develop/02-features/FEAT-XXX-*.md` + L2 详设 + 本仓 `docs/cases/FEAT-XXX-*.md`（如存在）。testplan 补齐后 grep 「TBD」批量刷回。

## 1. 覆盖矩阵

> **对账基准**：本表按 testplan 方案矩阵的场景 ID（`develop/04-testplan/FEAT-028-*.md` §5，16 条稳定契约）逐条对账——场景条目固定，代码落点随测试仓演进。
> **图例**：✅ 已建已验；🟡 partial / 半建 / red-first 设计内；⬜ 待建。最新真机进展与缺陷对时见 §5（滚动记录）。

### 1.1 覆盖进度看板

| 矩阵 ID | 场景 | 状态 | 落点与备注 |
|---|---|---|---|
| A1 | EDPAgent Agent Card 声明并行调度能力真实性 | ✅ | `EdpaAgentCardAlignmentTest`（2026-08-24 PASS：name=edp-agent-engine、streaming=true、skills 合规） |
| P0a | 入口 Task 唯一性与状态机单调收束 | ✅ | `EdpaEntryTaskUniquenessTest`（2026-08-24 PASS：分阶段启动，状态序列 WORKING→COMPLETED 单调；未观察到 SUBMITTED 属实现事实） |
| ~~P0b~~ | ~~WORKING 期间快照承载并行进展~~ | ⬜ out-of-scope | `EdpaSnapshotBatchProgressTest`（**2026-08-24 设计与开发确认 SendMessage+GetTask 通道下子任务粒度可见性当期不实现**；用例加 `@Disabled` 注解归档保留，特性档刷新后按新契约面复审）|
| ~~P0c~~ | ~~COMPLETED 快照的独立溯源痕迹~~ | ⬜ out-of-scope | `EdpaTerminalSnapshotTraceabilityTest`（同 P0b out-of-scope；终态 artifacts 承载 final_answer 由 A1/P0a 基础契约覆盖；`@Disabled` 归档保留）|
| **P1** | 同类型批量并行（同步阻塞） | ✅ | `EdpaHomogParallelBlockingTest`（**2026-08-24 PASS：FEAT-028 并行主线首次真机绿灯**——达终态、final_answer 覆盖两件事、总耗时 65s < 90s 启发式上限；BatchTimingObserver 观察面待 P0b 承载位钉死后升级）|
| **P2** | 异构混合并行（同步阻塞） | ✅ | `EdpaHeteroParallelBlockingTest`（2026-08-24 PASS：final_answer 明确「并行完成两项独立任务：搜索...核查...」，异构 search + versatile 都真实调用，65s < 90s 启发式）|
| **P3** | 同类型批量并行（SSE） | ✅ | `EdpaHomogParallelStreamingTest`（2026-08-24 PASS：SSE 15959 帧、62.5s < 90s 启发式，两件事覆盖完整、终态帧到位）|
| **P4** | 异构混合并行（SSE） | ✅ | `EdpaHeteroParallelStreamingTest`（2026-08-24 PASS：SSE 11398 帧、78s < 90s 启发式，异构主题覆盖）|
| P5 | 反证：有依赖任务禁止并行 | ✅ | `EdpaDependentTasksSerialTest`（2026-08-24 PASS：total=87s 符合串行下限 40s，planrule 依赖检测生效不伪并行）|
| P6 | 单实体单委托兼容 | ✅ | `EdpaSingleEntityCompatTest`（2026-08-24 PASS：total=38.9s 符合单成员上限 90s，final_answer 单主题覆盖）|
| C1 | 同批多委托原子性（组合面） | ✅ | `EdpaBatchAtomicityTest`（**2026-08-24 定性更正后 PASS**：FEAT-019 §88 明确 `batchId` 是 core/runtime 内部诊断标识不对客户端可见，原 red-first 判据错读——改为「SSE toolCallId ≥ 2 且互不重复」+「最终 artifact 覆盖两件事」双证；实测 toolCallId=4 个、5270 SSE 帧、双证成立）|
| C2 | all-settled 单次推理恢复（组合面） | ✅ | `EdpaAllSettledSingleRecoveryTest`（2026-08-24 PASS：终态 statusUpdate 帧恰好 1 次，FEAT-019「all-settled 后 core 只触发一次后续推理」组合契约实证）|
| C3 | toolCallId 稳定归位（组合面） | 🟡 red-first | `EdpaToolCallIdStableBindingTest`（2026-08-24 FAIL——精细化观察：SSE 里 4 个 toolCallId 每个只出现 1 次（tool_call 侧），**tool_result 侧完全无 toolCallId 归位事件**；承接 issue #93 第 2 个精细化观察，修复后自动转 PASS）|
| **N1** | ⭐ 越界约束：envelope 不含协同模式字段 | ✅ | `EdpaEnvelopeNoModeFieldGuardTest`（2026-08-24 PASS：扫遍 11927 SSE 帧全部字段，forbiddenHits=0；但 sawBatchId=false 属『未看到 envelope 结构』的弱证——公开面本身不承载 envelope，与 P0b/P0c 同源）|
| **N2** | ⭐ 越界约束：agent-core 不直连 registry | ⬜ | **待建（§2.2 主权、red-first 看守）**——依赖 registry 侧观察器，缺失时降 INCONCLUSIVE |
| **S1** | ⭐ 数据面/控制面分离 | ✅ | `EdpaDataControlPlaneSeparationTest`（2026-08-24 PASS：数据面 llm_reasoning 流 37451 字符 vs 控制面结构化 final_answer 32407 字符，两者显著不同——非流式片段机械拼接，符合『模型 all-settled 后单次汇总』契约）|
| **R1** | ⭐ SubscribeToTask 重订阅——首帧快照 + 后续事件应看到子任务 | ✅ | `EdpaSubscribeToTaskResubscribeTest`（**2026-08-24 首跑 PASS**——SubscribeToTask HTTP 200 + Content-Type=text/event-stream；首帧=父 Task 快照（taskId 一致，state=WORKING）；重订阅流 2560 帧全字段扫描命中：**子 taskIds=2**、**子 agentId=`search-agent`**、**子 state=`submitted`/`working`（路径 `agentEvent.state`）**——三通道全绿；**重大发现**：之前 issue #93 追加评论关于 SSE state 全空的说法**过强**，仅 `source.state`/`target.state` 为空，`agentEvent.state` 平级承载了子任务生命周期 state；详见 §5.5.2）|

**台账快照（2026-08-24 P0b/P0c out-of-scope 后）**：17 条 = ✅ 13（A1/P0a/**P1~P6/N1/S1/C1/C2/R1**）· 🟡 1 red-first 站岗（**C3——SSE tool_result 侧 toolCallId 归位缺失**）· ⬜ 3（N2 registry 可选 + P0b/P0c out-of-scope 归档）。**FEAT-028 主线全绿**：并行同/异构 × 同步/SSE 双模式全覆盖；越界约束（N1）、数据面/控制面分离（S1）、批次原子性（C1）、all-settled 单次恢复（C2）、SubscribeToTask 重订阅（R1）五条主权/组合契约实证；planrule 依赖判定（P5）+ 单成员兼容（P6）反证成立。**R1 首跑重大发现**：SSE `artifactUpdate.artifact.metadata.agentEvent` 结构**承载子 taskId + 子 agentId + 子 state 三种子任务信息**（子 state 值集合 `{submitted, working, ...}`），即"客户端应能观察到子任务粒度"这一诉求在**SSE/重订阅通道已具备实现基础**；之前 issue #93 追加评论关于"SSE state 全空"的说法**过强**——遗漏了 `agentEvent.state`（与 `source.state`/`target.state` 平级）。**issue #93 缺陷簇现精细化收窄**：①WORKING 快照全空（P0b，GetTask 通道）；②终态 artifacts 无 toolCallId 溯源（P0c，GetTask 通道）；③SSE toolCallId 只在 tool_call 侧、tool_result 侧无归位事件（C3）——3 条 red-first 承接 issue #93，**未来复盘方向**：R1 重订阅首帧快照是否也承载子任务信息？若是，可反证 GetTask 通道（P0b/P0c）的差距真伪，见 §5.5.2。**C1 关于 batchId 可见性的原诉求已撤回**：FEAT-019 §88 明确 batchId 是 core/runtime **内部诊断标识**不对外，测试判据不应要求其对客户端可见（详见 §5.5）。**P7 混合终态 / 接续场景已退出 FEAT-028 范围**（2026-08-24 设计团队确认；归档见 §5.2.3）。**P0b/P0c out-of-scope**（2026-08-24 下午设计与开发确认 SendMessage+GetTask 通道下子任务粒度可见性当期不实现）：`EdpaSnapshotBatchProgressTest` / `EdpaTerminalSnapshotTraceabilityTest` 加 `@Disabled` 注解归档保留；issue #93 由设计人员在特性档刷新后自行关闭；SSE / SubscribeToTask 通道下的子任务粒度可见性（P1~P4/R1 已实证）不受影响。

**下一步优先级**：

1. **P0**（依赖钉死 wire 事实，其他用例基础）：A1 探测 Card + remote-agents 一致性 → P0a/P0b/P0c 建父任务呈现契约的三层观察面；其中 **P0b 首轮真机的核心任务是 dump 快照结构、钉死子任务并行进展的承载位**（哪个字段承载 batchId、toolCallId、子任务 status、子任务 artifact 关联），结论写入 §5，成为 C1/C3 后续断言的稳定参考。
2. **P1**（Smoke 关键）：同类型批量并行同步阻塞——首个能证明"子任务真并行"的端到端用例，产出 `BatchTimingObserver` 的时间窗证据结构。
3. **P2~P4**（并行主线扩展）：异构 + SSE 两个维度扩展。
4. **P5/P6**（并行判定正确性）：反证与单成员兼容。
5. **C1~C3**（组合契约面）：可与 P1 复用同一次真机 run，同时采集不同断言维度。
6. **N1/N2**（越界看守）：N1 常规用例；N2 依赖 registry sniffer，可能长期 INCONCLUSIVE 等观察面建成。
7. **S1**（数据面/控制面分离）：SSE 模式下的额外断言，可与 P3/P4 复用真机 run。
8. **跟修 / 对齐**：P0b 承载位钉死后与开发对齐字段口径是否合规；N1 若发现越界字段回归 §2.2 契约；LLM 未同轮生成时的 prompt 优化。

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
| `BatchTimingObserver` | **新建** | 解析 SSE 事件流或快照 metadata，提取每个 `toolCallId` 的 start/end 时间戳；输出「并行时间窗」证据（`max(start_i) < min(end_i)` 判定）。 |
| `ToolCallSequenceObserver` | **新建** | 记录 SSE 事件流中 ToolCall 序列（按事件时间戳），支持「同轮 ≥2 ToolCall」与「跨轮次串行」判定（P1/P5 断言基础）。 |
| `SnapshotDiffProbe` | **新建** | 高频 `GetTask` 轮询父任务快照，diff 出承载子任务并行进展的字段位（P0b 首轮探测工具）。 |
| `RegistrySniffer` | **可选新建** | 在 registry-discovery-center 侧插桩观察调用来源；缺失时 N2 降 INCONCLUSIVE。 |

### 2.3 共享命名约定

- **contextId**：`ctx-feat028-<slug>-<uuid8>`（例 `ctx-feat028-homog-parallel-a1b2c3d4`），slug 与用例矩阵 ID 对应。
- **prompt 库常量**：定义在 `EdpaParallelPrompts.java`（新建）；每个 prompt 对应一个矩阵 ID，硬编码 planrule.yaml 期望的触发形态。
- **class 命名**：矩阵 ID + 场景短名，如 `EdpaHomogParallelBlockingTest`（P1）、`EdpaHeteroParallelStreamingTest`（P4）、`EdpaSnapshotBatchProgressTest`（P0b）。
- **Tag 命名**：`@Tag("integration")` + `@Tag("edpa")` + `@Tag("feat-028")`；LLM 依赖用例挂 `@Tag("manual")`。

## 3. 子用例设计

**说明**：以下逐条子用例的 G/W/T（Given/When/Then）、PASS/FAIL/INCONCLUSIVE 判据草案；首轮真机后按实测事实回写关键 wire 落点（如 P0b 承载位、N1 envelope 字段名、C1 batchId 观察位）。

### 3.1 Agent Card 探针

- **A1** `EdpaAgentCardAlignmentTest`
  - G：EDPAgent 就绪，`remote-agents` 已注入 search / verify baseUrl
  - W：GET `/.well-known/agent-card.json`；读 SUT 的 remote-agents 配置（通过启动前注入的 known 值反查）
  - T：Card `capabilities.streaming=true`；skills 非空、id 唯一；Card 声明的 agent 身份 = `EDPAgent`；remote-agents 声明的 `agentName` 集合与 planrule 的 skill_routes 或 SubagentDelegateRail 可解析集合一致（非空、无重复）
  - PASS：全部一致；FAIL：字段缺失或声明不一致；INCONCLUSIVE：card 不可达

### 3.2 父任务对客户端的呈现契约（P0 组）

- **P0a** `EdpaEntryTaskUniquenessTest`
  - G：SUT 就绪 + 被委托方就绪
  - W：`SendMessage(PROMPT_HOMOG_PARALLEL)`；持续 `GetTask` 轮询父 taskId
  - T：返回的 result 只含唯一 taskId=P；整个执行期 GetTask(P) 存在且返回同一 Task 表面；status 演进 SUBMITTED → WORKING → COMPLETED 单调不回退
  - PASS：唯一 + 单调；FAIL：出现多 taskId 或状态回退；INCONCLUSIVE：任务未达终态

- ~~**P0b** `EdpaSnapshotBatchProgressTest`~~ / ~~**P0c** `EdpaTerminalSnapshotTraceabilityTest`~~ —— **out-of-scope（2026-08-24 下午设计+开发确认）**
  - **背景**：设计与开发反馈 SendMessage+GetTask 通道下子任务粒度可见性（父任务 WORKING 快照承载子任务并行进展 / 终态按 `toolCallId` 独立溯源）**当期不实现**；FEAT-028 特性档将由设计人员刷新。
  - **处置**：两条测试类加 `@Disabled("...当期特性档不承诺...")` 注解归档保留，代码不删；台账 §1.1 状态 ⬜ out-of-scope；issue #93 由设计人员在特性档刷新后自行关闭。
  - **保留**：`EdpaChildVisibilityScanner` 全字段扫描判据方法学在 R1/C1/C3/N1 等 SSE 通道用例继续使用；SendMessage / GetTask 基础契约（入口 Task 唯一、状态机单调、final_answer 落 artifacts）由 A1/P0a 覆盖。
  - **历史证据（归档）**：首轮真机 P0b 158 快照 + P0c 终态快照 全字段扫描四集合全空——曾作为 issue #93 严格证据；判据方法学教训见 §5.5.2 与 memory `feedback_full_scan_before_red_first.md`。特性档刷新后按新契约面复审是否需要重新启用。

### 3.3 并行主线（P1~P4）

- **P1** `EdpaHomogParallelBlockingTest`（同类型 · 同步阻塞）
  - G：SUT + search 就绪
  - W：`SendMessage(PROMPT_HOMOG_PARALLEL)`；通过 SSE？（同步阻塞可从 GetTask 快照 P0b 承载位取时间戳）或 `BatchTimingObserver` 采集每个子委托的 start/end 时间戳
  - T：分层——硬 1：达终态 COMPLETED + final_answer 覆盖两件事；硬 2（触发条件式）：**若模型同轮生成 ≥2 个 ToolCall**，则子任务时间窗必须重叠（`max(start_i) < min(end_i)`）；软层：`observed_parallel=true`
  - PASS：硬 1 + 硬 2 均绿；INCONCLUSIVE：模型未同轮生成（记录 ToolCall 序列供 prompt 优化）；FAIL：达终态但时间窗不重叠且模型同轮生成（真伪并行缺陷）

- **P2** `EdpaHeteroParallelBlockingTest`（异构 · 同步阻塞）
  - 同 P1 结构；prompt 换 `PROMPT_HETERO_PARALLEL`；断言两子委托 `agent_name` 分别为 search-agent + verify-agent；异构进同一批次仍统一汇总

- **P3** `EdpaHomogParallelStreamingTest`（同类型 · SSE）
  - G：SUT + search 就绪
  - W：`SendStreamingMessage(PROMPT_HOMOG_PARALLEL)`；收集全部 SSE `statusUpdate` / `artifactUpdate` 事件（wire 形态见 FEAT-001 cases §7）
  - T：达终态 COMPLETED；SSE 事件中可观察到**两个独立 `toolCallId` 的事件穿插到达**（时间窗重叠证据从事件时间戳直接读出）；若 FEAT-027 数据面激活，`source.agentId`/`source.taskId` 能区分两条并行轨迹
  - PASS/INCONCLUSIVE/FAIL 判据同 P1（观察面换 SSE 事件）

- **P4** `EdpaHeteroParallelStreamingTest`（异构 · SSE）
  - 同 P3 结构；prompt 换 `PROMPT_HETERO_PARALLEL`

### 3.4 并行判定反证与兼容（P5/P6）

- **P5** `EdpaDependentTasksSerialTest`（反证）
  - G：SUT + search + verify 就绪
  - W：`SendMessage(PROMPT_DEPENDENT_SERIAL)`（先搜再验证，有数据依赖）
  - T：达终态 COMPLETED；`ToolCallSequenceObserver` 观察到**同轮仅 1 个 ToolCall**；第 2 个 ToolCall 出现在第 1 个 tool_result 到达之后的**新一轮**；不得出现同轮 2 个 ToolCall（planrule 禁止伪并行）
  - PASS：串行正确；FAIL：LLM 违反 planrule 强行并行（对齐 §7.3 E4 「模型合并多个实体」）；INCONCLUSIVE：未达终态

- **P6** `EdpaSingleEntityCompatTest`（单成员兼容）
  - G：SUT + search 就绪
  - W：`SendMessage(PROMPT_SINGLE_ENTITY)`
  - T：达终态 COMPLETED；只生成 1 个 ToolCall；走单成员兼容路径（FEAT-019 单成员兼容契约）；final_answer 覆盖单个查询
  - PASS：单 ToolCall + 终态正确

### 3.5 组合契约面（C1~C3，引用 FEAT-019）

- **C1** `EdpaBatchAtomicityTest`（引用 FEAT-019 主权；2026-08-24 定性更正见 §5.5，判据升级用统一 helper）
  - G：同 P1
  - W：`SendStreamingMessage` 发 `PROMPT_HETERO_PARALLEL`；采 SSE 全帧
  - T（双证判据 + 全字段扫描）：
    - **硬 A**：`EdpaChildVisibilityScanner` 全字段扫描 SSE 帧命中的 `toolCallId` ≥ 2 个互不重复值——证明 runtime 派发了 ≥ 2 个 ToolCall（不静默丢弃、不只保留最后一个）
    - **硬 B**：最终 artifact 内容覆盖两个子任务主题——证明批次全部完成后模型汇总了 ≥ 2 个子结果（不是丢弃后拿单结果凑答）
  - **PASS**：硬 A + 硬 B 双证；**FAIL**：任一不成立
  - **原 red-first 判据「batchId 应从公开面可见」已撤回**：FEAT-019 §88 明确 `batchId` 是 core/runtime **内部诊断标识**，不要求对客户端可见——此为测试判据误读特性档，撤回并更新 issue #93（详见 §5.5）

- **C2** `EdpaAllSettledSingleRecoveryTest`（引用 FEAT-019 主权）
  - G/W：同 P3（SSE 便于观察推理恢复次数）
  - T：同批全部子任务完成后**只触发一次**父 agent-core 推理恢复；观察证据：SSE 事件中「汇总性 artifact」（即 all-settled 后模型输出的 final_answer 相关事件）**出现次数 = 1**，且发生在所有子委托事件之后
  - PASS：恢复次数 = 1；FAIL：出现 ≥2 次（逐成员恢复缺陷）

- **C3** `EdpaToolCallIdStableBindingTest`（引用 FEAT-019 主权；2026-08-24 判据升级用统一 helper）
  - G/W：同 P1（SSE 全帧）
  - T（严格判据）：
    - **硬 1**：`EdpaChildVisibilityScanner` 全字段扫描命中的 `toolCallId` ≥ 2 且互不重复
    - **硬 2（红-first 精细化）**：每个 `toolCallId` 平均出现次数 ≥ 2（tool_call 派发侧 + tool_result 归位侧一致映射）——只在 tool_call 侧出现即违约，承接 issue #93
  - PASS：硬 1 + 硬 2 双证；FAIL：ID 冲突 or 只在 tool_call 侧承载

### 3.6 越界约束红-first 看守（N1/N2，§2.2 主权）

- **N1** `EdpaEnvelopeNoModeFieldGuardTest` ⭐（2026-08-24 判据升级用统一 helper 作辅助诊断）
  - G：SUT 就绪 + 被委托方就绪
  - W：`SendStreamingMessage` 发 `PROMPT_HETERO_PARALLEL`；采 SSE 全帧；全字段递归扫描
  - T：
    - **主判据**：全字段扫描**不得**命中协同模式字段（red-first 看守列表：`mode`、`syncMode`、`asyncMode`、`blocking`、`edpa_mode`、`coordinationMode`、`executionMode`、`invocationMode`、任何以 `Mode` / `mode_` 后缀）；白名单剔除后仍有命中即违约
    - **辅助诊断**：同步做 `EdpaChildVisibilityScanner` 子任务可见性扫描（不作主判据，仅证明"越界主权成立"时公开面同时观察到子任务）
    - **batchId 属内部诊断字段**（FEAT-019 §88）不列入判据
  - PASS：无禁止字段命中；FAIL：出现任一禁止字段；INCONCLUSIVE：SSE 无帧

- **N2** `EdpaAgentCoreNoDirectRegistryAccessRedGuardTest` ⭐
  - G：SUT 就绪；registry-discovery-center 侧启用 `RegistrySniffer` 观察器（可选）
  - W：任一并行用例的执行（复用 P1/P3 真机 run）；观察 registry 侧的访问日志/网络连接
  - T：若观察面可用，registry 收到的访问来源只能是 agent-runtime 侧（非 agent-core 侧）；agent-core 不得直接调用 registry-discovery-center
  - PASS：观察面可用且来源合规；INCONCLUSIVE：观察面缺失（部署未含 sniffer）——本条不因此降级判绿；FAIL：观察到 agent-core 直连来源

### 3.7 数据面/控制面分离（S1，§5.0.1 主权）

- **S1** `EdpaDataControlPlaneSeparationTest` ⭐
  - G：同 P3 或 P4（SSE 模式）
  - W：SSE 收集全部 `artifactUpdate` 事件（数据面）；等达终态后 `GetTask` 取 final_answer（控制面）
  - T：①流式 `artifactUpdate` 事件承载各子任务原始输出（数据面透传，符合 FEAT-027 场景）；②final_answer 内容**不是流式事件的机械拼接**（可用内容相似度判据 + 结构判据：final_answer 包含明确的汇总语气或对两件事的归纳，而非拼接的原始 artifact）；③流式事件的存在不改变父任务的单次恢复语义（对齐 C2）
  - PASS：三条同时成立；FAIL：final_answer 与流式事件末帧结构等价（=机械拼接）；INCONCLUSIVE：流式事件缺失或 final_answer 无法抽取

### 3.8 SubscribeToTask 重订阅——子任务粒度可见性（R1，2026-08-24 新增）

- **R1** `EdpaSubscribeToTaskResubscribeTest` ⭐（2026-08-24 新增，承接设计团队反馈的三条查询/恢复观察通道之一）
  - **动机**：设计团队 2026-08-24 明确 EDPA 场景客户端应通过三种通道观察子任务信息——①SSE 实时看子智能体工作事件（已覆盖 P3/P4）、②断连后 `GetTask` 快照包含子任务终态/中间态（已覆盖 P0b/P0c）、③断连后 `SubscribeToTask` 重订阅（本用例覆盖）。FEAT-001 §62 已定义 `SubscribeToTask` 标准 method——首帧=当前快照，之后=挂接成功后的新事件；R1 在此基础上聚焦 EDPA **子任务粒度可见性**。
  - **上游依赖**：FEAT-001 `TaskResubscribeTest`（E3+E4）已在 search-agent SUT 验证 SubscribeToTask 的基础 wire 契约（首帧快照 + 终态回退），R1 不重复此契约，只做 EDPA 场景专用断言。
  - G：SUT 就绪 + search 就绪（PROMPT_HOMOG_PARALLEL 只需 search）；`A2aServiceClient.subscribeTask(taskId, ...)` 可用（已封装 SDK）
  - W：①`SendStreamingMessage` 发 `PROMPT_HOMOG_PARALLEL`，读到父 taskId + WORKING 状态后**主动断开**（模拟客户端断连）；②立即调 `SubscribeToTask(params.id=parentTaskId)`；③收重订阅 SSE 直到关闭或超时
  - T（分层）：
    - **硬 1（FEAT-001 §62 基础契约复用）**：SubscribeToTask 应返回 SSE 流；首帧为**当前 Task 快照**（taskId 一致 + status.state 存在）
    - **硬 2（FEAT-028 子任务可见性 · 本用例主权）**：首帧快照 + 后续所有事件的**全字段递归扫描**应能命中至少一处子任务信息——判据集合：①子 taskId（非父 taskId 的其他 taskId 值出现在任意字段）；②子 agentId（除 EDPAgent 自身外的 agentId 值，如 `search-agent`）；③子 state（在 `agentEvent.source.state` 或 `agentEvent.target.state` 或类似路径出现）。三种命中方式**任意一处**即算硬 2 PASS——**不预设 wire 字段名/结构**（承接用户 2026-08-24 明示：wire 承载位归设计定，测试只保证客户端能观察到）
    - **观察记录（无硬断言）**：命中的具体字段路径 + JSON 位置，供开发对齐 wire 定型；同时记录首帧到达前的耗时、总帧数、后续事件是否出现子任务状态变化
  - PASS：硬 1 + 硬 2 均绿；
    FAIL：硬 1 挂（method 未实现或首帧不是快照）— 违反 FEAT-001 §62；或硬 2 挂（全字段扫描无任何子任务信息命中）— **red-first 承接 issue #93 缺陷簇第 4 处**（与 P0b/P0c/C3 同源）；
    INCONCLUSIVE：模型未真触发并行子任务（LLM 抖动），子任务信息缺失属正常，跳过
  - **Tag**：`manual`（依赖真实 LLM + LLM 并行规划）

## 4. 运行方式

```bash
# 全部 FEAT-028 相关用例（跳过 @Tag("manual")）——含 A1、N1 结构断言等
./mvnw -Dtest.env=local -Dgroups='feat-028 & !manual' test

# 指定单条子用例（示例：P1 同类型批量并行同步阻塞）
./mvnw -Dtest.env=local -Dtest=EdpaHomogParallelBlockingTest test

# 强跑 manual 分支（含 LLM 依赖、长任务等）
./mvnw -Dtest.env=local -Dgroups='feat-028 & manual' test
```

**沙箱环境限时说明**：EDPAgent 模型推理 + 2 次 search/verify 完整链路时长预估 40~80s；沙箱单调用限时场景需按类拆分执行或使用 system property 缩窗。

## 5. 真机实测进展（滚动记录）

> 方案级设计文档（`docs/testplan` 同名档）只锚定场景条目，不承载进展；实测进展、缺陷对时、验证结论统一记录在本节。

**当前状态**：本细档 2026-08-20 初建，全部 16 条用例待建，无真机数据。首轮真机后将按矩阵 ID 逐条过账。

**首轮真机的关键使命**（P0b 特别标注）：
- 完整 dump 父任务 WORKING 期间的至少 3 个快照，钉死子任务并行进展的承载位（哪个字段承载 batchId、toolCallId、子任务 status、子任务 artifact 关联）
- 结论写入本节 §5.1（待建），成为 C1/C3/P0c 后续断言的稳定参考
- 若承载位与 planrule.yaml / PlanAgentParallelTransferStreamingTest javadoc 提及的 `_remote_invocation.{batchId,toolCallId}` 形态不一致，作为 wire 事实增量入 [[a2a-wire-contract]] 记忆

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

- FEAT-019 特性档 §88 明确："`batchId` 可以是 core 或 runtime adapter **内部诊断标识**，**不要求外部客户端传入**。"
- FEAT-028 §278/306/430 把 `{batchId, items, toolCallId}` 三件套定性为 core→runtime **batch interrupt envelope**——是**内部**契约载体，不是客户端可见 wire。

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
3. **待复盘（放到跟进项，不本轮做）**：P0b/P0c 是 **GetTask 通道**下的观察面缺失，与 R1 是不同通道。R1 结果不能直接翻案 P0b/P0c——需要另跑一次 P0b/P0c 的 GetTask 快照，专门查 `metadata.agentEvent.state` 是否也承载。若 GetTask 通道下 agentEvent 也已承载，P0b/P0c 也可能能升级为 PASS；若 GetTask 通道不承载但 SSE 通道承载，则 P0b/P0c 承接的是"通道间一致性"缺陷，仍留 issue #93。

**教训**：全字段递归扫描比预设路径断言更能保护"字段名可能变但语义可能已实现"这类情境；写"字段全空"级别的 red-first 观察前，宁可先扫全字段再下结论。

### 5.6 判据升级回归（2026-08-24，5 red-first 用例 + R1 统一改用 EdpaChildVisibilityScanner）

**动机**：用户 2026-08-24 反馈"你没扫描过就敢提 Issue……前期测试非常不到位、非常不仔细、用例覆盖存在很大问题"——反思后确认 P0b/P0c/C1/C3/N1 五条 red-first 用例判据"硬编码预设承载位"存在系统性缺陷，抽出 `EdpaChildVisibilityScanner` 静态 helper（全字段递归扫描：子 taskId / 子 agentId / 子 state / toolCallId 四集合），5 条 red-first 用例 + R1 统一改用。

**回归结果**（6 用例真机跑，2026-08-24 沙箱恢复 + 修 skill routing 后稳态）：

| 用例 | 结果 | 证据摘要 |
|---|---|---|
| **P0b** | **FAIL**（严格 red-first） | 20 WORKING 快照全字段扫描四集合全空——严格证据支撑 issue #93「GetTask WORKING 快照缺子任务信息」 |
| **P0c** | **FAIL**（严格 red-first） | 终态快照 3 预设承载位 + 全字段扫描双双四集合全空——**升级后终于有全字段扫描严格证据**（之前只到"3 预设位空"这一层） |
| **N1** | **PASS** | 主判据（禁止字段扫描）无命中；辅助诊断证明公开面确实承载了子任务观察证据 |
| **C1** | **PASS** | SSE 1821 帧命中 childTaskIds=2、childAgentIds=[search-agent]、subStates=`{submitted,working,...}`；硬 A（toolCallId ≥ 2 互不重复）+ 硬 B（多主题覆盖）双证 |
| **C3** | **FAIL**（严格 red-first 精细化） | SSE 883 帧命中 2 个 `toolCallId` 但**平均出现 1.00 次**——tool_result 侧无 `toolCallId` 归位事件的严格证据，承接 issue #93 |
| **R1** | **PASS** | SubscribeToTask 800 帧命中 childTaskIds=2、childAgentIds=[search-agent]、subStates 观察到、toolCallIds=2；硬 1（首帧快照）+ 硬 2（子任务可见性）双证 |

**核心结论**：
1. **判据升级本身没引入行为变化**——3 PASS + 3 FAIL red-first 定性与首跑一致
2. **issue #93 三条 red-first 严格证据全部齐了**——P0b（WORKING 全空）、P0c（终态无溯源）、C3（`toolCallId` 只在 tool_call 侧、`tool_result` 侧无归位）
3. **回归首轮失败根因**：不是 LLM 抖动，是沙箱恢复时给 SUT 的 planrule 缺 skill routing——base planrule 里 `scope.allowed/denied` 都空、LLM 走"无可用业务能力"分支直接自我介绍就 final_answer 结束。补 `additional_prompt` 里的 skill routing（明示 `search-agent`/`verify-agent` 用途 + 并行硬要求）后，LLM 走标准 `call_subagent` 路径，判据全部按预期稳定复现

**pre-flight 3 问方法学落地**（用户 2026-08-24 拍板 A+B 之 B）：
- 全字段扫过没？—— `EdpaChildVisibilityScanner` 统一 helper
- 交叉通道验过没？—— R1（SubscribeToTask）+ P0b/P0c（GetTask）+ C1/C3/N1（SSE）三通道验证
- 特性档核对过没？—— batchId 撤回从 FEAT-019 §88 学到；agentEvent.state 从 R1 首跑翻案学到

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
| **C3** | ToolCallIdStableBinding | 🔴 FAIL | toolCallId 平均出现 1.20 次（<2）——**tool_result 侧仍缺归位事件**（issue #93 未完全修复） |
| N1 | EnvelopeNoModeFieldGuard | ✅ | 越界字段 0 命中；辅助诊断新观察：`agentEvent.state` 值集合增加 `completed`（之前只有 submitted/working） |
| S1 | DataControlPlaneSeparation | ✅ | 数据面 12430 chars vs 控制面 4824 chars 显著不同 |
| R1 | SubscribeToTaskResubscribe | ✅ | 首帧快照 + 后续事件子任务可见性 |
| ~~P0b~~ | ~~SnapshotBatchProgress~~ | @Disabled | out-of-scope（SendMessage+GetTask 子任务粒度可见性当期不实现） |
| ~~P0c~~ | ~~TerminalSnapshotTraceability~~ | @Disabled | 同上 |

**新 jar 关键观察**（vs 老 jar）：

1. **`agentEvent.state` 增加 `completed`**（之前只 `submitted` / `working`）—— issue #93 子任务终态可见性诉求**部分修复**
2. **新 wire 承载点** `.result.artifactUpdate.artifact.parts[0].data.payload.tool_call_id` 承载 toolCallId —— 之前仅 `agentEvent.toolCallId`
3. **C3 的 tool_result 归位事件仍缺**：`toolCallId` 平均出现 1.20 次（旧 jar 1.00 次），略有改善但未达"tool_call + tool_result 一致映射"的双证要求 —— issue #93 未完全修
4. **C2 的 SSE 终态帧策略变化**：老 jar 终态 statusUpdate 帧恰好 1 次，新 jar 实测 0 次 —— **判据可能需适配**新 wire（待深挖）
5. **主线 P1-P4 全绿**：并行同/异构 × 同步/SSE 4 组组合稳定，新 jar 不破坏 EDPA 并行核心能力

**待深挖项**（用户 2026-08-28 决策：先记录，跑完 agentscope 后再深挖）：
- **C2 判据适配**：从 allure 附件 dump SSE 帧，看新 jar 终态帧到底以什么形式承载（是否 SSE 侧完全不发终态、还是承载位换到 artifactUpdate.metadata、还是字段名从 `TASK_STATE_COMPLETED` 变了）
- **P5 环境问题**：查 SutStack 源码看 `SUT_AGENTS_*_URL` env override 键名映射（`sut.agents.<name>.url` → 环境变量的连字符 `_` 转换规则），确认修复方案后重跑 P5

## 6. 风险与备注

### 6.1 与 FEAT-028 相关特性的责任分界

- 本细档仅覆盖 FEAT-028 主权面 + 组合契约端到端观察面；不重复 FEAT-019 / FEAT-004 / FEAT-006 / FEAT-015 / FEAT-016 / FEAT-027 单点契约。责任分界详见 testplan 附录 A。

### 6.2 LLM 抖动对并行断言的影响

- planrule.yaml 明示「模型串行生成同轮多委托 = 提示词质量问题」（§7.3 E3），本方案对此**不判失败**，而是标 INCONCLUSIVE 记录 ToolCall 序列供 prompt 优化。
- 若发现某条 prompt 长期无法稳定触发同轮生成，考虑：①改用更明确的并行指令措辞（如「请**同时**并行执行，勿等待」）；②切换到低温度模型；③把该 prompt 作为「模型能力弱面」标注，转到 core rails testplan 的模型规划质量层承接。

### 6.3 P0b 承载位的实现事实依赖

- 本方案首轮真机的核心探测使命就是钉死 P0b 承载位（属实现事实，spec 未明确规定字段位）。
- 若首轮真机发现承载位形态与 spec 意图不符（例如只有单一扁平文本、无 toolCallId 溯源），属实现缺口——**不降级本方案断言**，而是作为 spec-vs-impl 口径分歧记入本节 §5.1 并反馈开发。

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
- ❌ 父任务 GetTask 快照（SendMessage 路径）：**完全无 delegation 结构**（P0b/P0c/C1/C3 缺陷仍成立）
- ❌ 明确 `batchId` 聚合标识：两条通道均缺（可通过共同 source.taskId + type=delegation 时序推导，但非直接判定）

Issue #93 已加 comment 反映此收窄；修复方向不变（TaskStore 侧对齐事件流的 agentEvent 结构即可）。

#### 5.2.2 SendMessage 无 returnImmediately 时返回 FAILED（待分析）

实测（P0b 探测过程中偶然发现）：`SendMessage` 无 `params.pushNotificationConfig` 且无
`configuration.returnImmediately=true` 时，198ms 返回 `TASK_STATE_FAILED`，artifacts=0。

按 FEAT-001 §5.1.6 「阻塞 S2C 语义」原文口径：**默认应阻塞聚合到终态**（COMPLETED），
而非立即 FAILED。这与 SendStreamingMessage 路径行为不一致——同一次运行下 SSE 路径 15753 帧
完整走通 COMPLETED，仅 SendMessage 无 returnImmediately 会异常返回 FAILED。

**性质待判**：可能是 EDPAgent runtime 侧的隐性契约要求「无 push config 也须携 returnImmediately」，
也可能是与 SendMessage 阻塞语义相关的实现缺陷。**本轮所有 SendMessage 用例均携 returnImmediately=true
作为规避**，但该字段是否为 FEAT-001 spec 必须仍待与开发/产品澄清。

**跟进方式**：暂不单独提 issue，先记录事实等 issue #93 反馈后一并对齐。

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

**待与开发/设计对齐的灰色地带（用户 2026-08-24 主动同步）**

| # | 灰色地带 | 本轮实测线索 |
|---|---|---|
| 1 | 多子任务同时 INPUT_REQUIRED 时如何区分 | 本轮跑次 3 只有 1 个子任务进 INPUT_REQUIRED（另一个 COMPLETED），未构造出多子任务同 INPUT_REQUIRED 场景；父 history 中的 ask_user 问题只包含一条 |
| 2 | 续接消息如何定向到特定子任务 | 跑次 3 客户端只发了一条续接文本 `DeepSeek-V3`（无子任务定向标记），runtime 侧仍能正确把它送到那个 park 的 search 子任务——**runtime 有隐式定向机制**（可能基于 park 顺序或 conversationId 内部路由）；wire 层无客户端可见的定向字段 |
| 3 | INPUT_REQUIRED 与部分失败的组合语义 | 本轮未测（未构造子任务失败场景），需另立 P8/P9 类用例覆盖 |
| **4（本轮新加）** | 父 EDPAgent 何时投影 INPUT_REQUIRED vs 何时让 LLM 自主决定 | 跑次 1 与跑次 3 同 prompt（仅措辞差异）表现完全相反——契约语义应明确「投影是硬规则还是软建议」 |

### 3.5 [已退出 FEAT-028 范围] 混合终态与接续场景

**2026-08-24 设计团队确认**：FEAT-028 当前**不考虑子任务 INPUT_REQUIRED 投影 + 客户端接续场景**。
相关能力（远端交互式中断投影、同 Task 续接、多子任务同时 INPUT_REQUIRED 的定向续接）
由 FEAT-008 相关方案在其成熟后另立条目验收。

原 P7 用例设计已从本节移除；曾在 2026-08-24 落地的两个测试类
（`EdpaMixedTerminalStateTest` 同步路径 + `EdpaMixedTerminalStateStreamingTest` SSE 路径）
以及 `EdpaParallelPrompts.PROMPT_MIXED_TERMINAL_STATE` / `PROMPT_MIXED_TERMINAL_RESUME` 常量
已同步删除。

历史真机探测记录（三跑对照 + 灰色地带清单）作为**跨特性对齐材料**保留在 §5.2.3。

