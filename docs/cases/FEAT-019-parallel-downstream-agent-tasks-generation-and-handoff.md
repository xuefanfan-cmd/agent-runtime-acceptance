---
feature_id: FEAT-019
feature_title: 智能体生成并行的下游智能体调用委托
sut: openjiuwen 双形态栈——edpa-plan-agent(parallel-transfer profile)+edpa-adapter（交互续接面：INPUT_REQUIRED 定向续接 + 批量回灌）；deep-research-auto(parallel-search profile)+search/verify（自动完成面：无人工介入扇出 + 单次汇总）
scope: 本档只覆盖 FEAT-019（agent-core 主权）经 runtime 黑盒可观察的事实要求——同轮多 ToolCall 聚合为单一批量中断（不得 last-write-wins）、toolCallId 稳定关联、批量 checkpoint/resume、all-settled 后单次回灌与单次继续推理、结果按 toolCallId 稳定归位、单成员兼容。batch interrupt envelope 是 core→runtime 内部契约，batchId 不对客户端可见——判据一律锚语义不锚内部字段。远程 A2A child Task 编排 / 并发预算 / 超时 / INPUT_REQUIRED 外部路由归 FEAT-004；本地普通工具并行、任意 DAG 依赖、显式批量委托工具按特性档 §5.5 OUT 不列入
status: designed
owner: TBD
tags: [integration, feat-019, parallel, a2a]
depends_on:
  - openjiuwen profile（-Dtest.env=openjiuwen，需 LLM_API_KEY 等环境变量；两条主线均需真实 LLM 做同轮批量决策）
  - parallel-transfer 栈：edpa-plan-agent（profile 注入）+ edpa-adapter（envexplorer 由 service-bindings 自动拉起）
  - parallel-search 栈：deep-research-auto（隔离别名，remote-agents-prefix + 两个 name）+ search（SEARCH_AGENT_USE_STUB=true）+ verify（ReAct 判官真调）
related_docs:
  - FEAT-019 特性文档（外部契约）：`docs-agent-solution/develop/02-features/FEAT-019-parallel-downstream-agent-tasks-generation-and-handoff.md`（v0730）
---

# FEAT-019 — 智能体生成并行的下游智能体调用委托 SIT 测试设计

> **一句话**：DeepAgent / ReActAgent 在**同一轮 agent-loop** 生成 ≥2 个 runtime-proxy downstream-agent ToolCall 时，agent-core 必须把它们聚合成**一个批量中断**（不得只保留最后一个）、以 `toolCallId` 稳定关联、runtime all-settled 后**一次回灌**、core 只触发**一次**后续推理、结果按 `toolCallId` 归位不串线。本仓从 runtime 暴露的黑盒观察面（中断成员、delegation 调用树、多 part 续传路由）验证上述契约。

> **组织原则**：
> 1. **判据锚语义，不锚内部字段**——batch interrupt envelope 是 core→runtime 内部契约（特性档 §3.1），`batchId` 是内部诊断标识、不要求对客户端可见；断言一律落在"≥2 互异成员、定向路由、防串腿、单次恢复"等语义上。**观察承载位属版本事实**（现行 v0815：SSE 进度事件不再携带 `_remote_invocation.{batchId,toolCallId}` 投影，改看 ①扇出轮终态 statusUpdate 的 `status.message.metadata._interruwf.items[]` 成员 toolCallId；②delegation 事件（`agentEvent.type=delegation`）按 `target.taskId` 去重成调用树；③续传侧每轮一次多 part POST、part `metadata.toolCallId` 定向路由）——wire 再演进时改探针不改判据方向，新承载位先全字段扫描钉死再固化断言。
> 2. **两条互补主线**：**B2**（plan-agent · parallel-transfer）——含 INPUT_REQUIRED 定向续接与批量回灌的**交互续接**路径；**B1**（deepagent · parallel-search）——子任务自动完成的**无人工介入**扇出路径。一条覆盖"生成→聚合→并发分发→续接→归位"全链，一条覆盖"生成→聚合→并发分发→all-settled 单次汇总"，互补不重叠。
> 3. **同类项合并**：同载体的多条能力要求合并为一个测试类的断言组（B2 的 PT.a–g、B1 的 DA-09.A–D），不逐条独立成类。

**状态含义**：**runnable** = 被测能力已实现，可直接落地；**partial** = 核心路径可测、某些断言维度受限；**不建** = 黑盒不可达或无合理注入面。

---

## 1. 覆盖矩阵

对应 FEAT-019 §2 能力表（10 MUST）与 §4 场景表。**子用例 ID 前缀 = 载体线**：`wf` = workflow 线（B2 parallel-transfer 载体，落 workflow_call）、`da` = deepagent 线（B1 parallel-search 载体，落 deepagent_deepresearch）——与仓内 Allure story 前缀约定一致（story 取子用例 ID 去特性前缀的 `<线>.<slug>`；B2/B1 既有类级 story 挂载不随本表重命名）。

| FEAT-019 事实要求（§2） | 场景（§4） | 本档子用例 ID | 现状 | 状态 | 落点类（合并） |
|---|---|---|---|---|---|
| 多下游 Agent ToolCall 生成（同轮 ≥2） | 一轮生成三个下游委托 | `FEAT-019.wf.batch-aggregation` + `FEAT-019.da.auto-fanout` | 已落地 | runnable | [PlanAgentParallelTransferStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentParallelTransferStreamingTest.java)（PT.b）+ [ParallelSearchComparisonTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/ParallelSearchComparisonTest.java)（DA-09.B） |
| 单调用工具形态（不暴露批量工具） | 同上（伴随观测） | `FEAT-019.wf.batch-aggregation` | 已落地（伴随） | runnable | 两主线均观测 N 个互异 toolCallId、无批量工具形态 |
| 下游代理工具识别（中断型，不在 core 内直调） | 下游 ToolCall 不丢失 | `FEAT-019.wf.batch-aggregation` | 已落地 | runnable | PlanAgentParallelTransferStreamingTest（PT.b：扇出轮 `_interruwf.items[]` ≥2 待输入成员——承载位=实现事实） |
| 同轮批量中断聚合（不得 last-write-wins） | 下游 ToolCall 不丢失 | `FEAT-019.wf.batch-aggregation` + `FEAT-019.da.auto-fanout` | 已落地 | runnable | PT.b（childCount ≥2）+ DA-09.B（同批 ≥2 toolCallId） |
| `toolCallId` 稳定关联（中断/回灌/归位一致） | runtime 完整回灌 | `FEAT-019.wf.toolcall-routing` | 已落地 | runnable | PlanAgentParallelTransferStreamingTest（PT.c） |
| 独立调用上下文（兄弟不互相覆盖） | — | `FEAT-019.wf.independent-context` | 已落地 | runnable | PlanAgentParallelTransferStreamingTest（PT.d：非对称腿 stepId 键控各自正确 kv） |
| 批量 checkpoint / resume（`toolCallId -> result` 一次恢复） | runtime 完整回灌 | `FEAT-019.wf.batch-resume` | 已落地 | runnable | PlanAgentParallelTransferStreamingTest（PT.g：一次多 part POST → 单条交织回复全驱动） |
| all-settled 后单次继续推理（恰好一次） | runtime 完整回灌 | `FEAT-019.wf.single-recovery` | 已落地（伴随 + 显式看守，真机待跑） | runnable | PlanAgentParallelTransferStreamingTest（伴随）+ [ParallelAllSettledSingleRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelAllSettledSingleRecoveryTest.java)（§3.4，兼 FEAT-027 fan-in 断言组） |
| 结果稳定归位（完成顺序不改变身份） | runtime 完整回灌 | `FEAT-019.wf.stable-attribution` | 已落地 | runnable | PlanAgentParallelTransferStreamingTest（PT.e：两腿收款人语义不共流） |
| 单成员兼容（批次=1 走既有单中断路径） | 单个下游 Agent 委托 | `FEAT-019.wf.single-member` | 伴随已落地；显式对照 ⬜（可选） | runnable | PlanAgentParallelTransferStreamingTest（kickoff 余额腿：单成员批=裸 parentCid 串行驱动） |
| 部分失败回灌（成功/失败各归位，失败不覆盖成功） | 部分失败回灌 | `FEAT-019.wf.partial-failure` | 已落地（注入手段首跑钉死，真机待跑） | runnable | [ParallelPartialFailureResumeTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelPartialFailureResumeTest.java)（§3.3） |
| 后续轮次再委托不串线（新批次不混旧状态） | 后续轮次再次委托 | `FEAT-019.wf.next-round-isolation` | 已落地 | runnable | PlanAgentParallelTransferStreamingTest（PT.f：余额批→转账批两轮，树去重/单调/根唯一） |

> **不在本档范围**（对齐特性档 §2 OUT / §5.5）：
> - **FEAT-004 主权**：远程 A2A child Task 创建、并发预算与调度、超时、状态投射、INPUT_REQUIRED 外部路由歧义——B2 的并发续轮只断 FEAT-019 面（聚合/路由/归位）。
> - **特性档明示 OUT**：本地普通工具并行（文件/Shell/REST/MCP 等）、任意依赖图 / DAG 解析、显式批量委托工具（`delegate_many` 形态）。
> - **黑盒不可达，不建**：`toolCallId` 缺失/重复时"core 产生可诊断错误"（§5.2）——黑盒面无法构造与观测；`batchId` 内部性决定了批次归属只能靠"共同 source + 同轮时序"推导，不作独立断言。

### 1.1 覆盖进度看板

> **图例**：✅ 已落地；⬜ 待新建；（伴随）= 断言作为其他主线断言组的副产物在岗，无独立方法。

| 测试类 | 子用例 | 状态 | 说明 |
|---|---|---|---|
| [PlanAgentParallelTransferStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentParallelTransferStreamingTest.java)（B2 交互续接面） | wf.batch-aggregation / wf.toolcall-routing / wf.independent-context / wf.batch-resume / wf.stable-attribution / wf.next-round-isolation；wf.single-member（伴随）；wf.single-recovery（伴随） | ✅ | openjiuwen 限定，仅 A2A_STREAM；断言组 PT.a–PT.g（§3.1） |
| [ParallelSearchComparisonTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/ParallelSearchComparisonTest.java)（B1 自动完成面） | da.auto-fanout | ✅ | InteractionFlow 参数化线协议（A2A_STREAM/A2A_SYNC）；断言组 DA-09.A–D（§3.2）；单 TC 设计档 [deepagent/DA-09-parallel-search-comparison.md](deepagent/DA-09-parallel-search-comparison.md) |
| [ParallelPartialFailureResumeTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelPartialFailureResumeTest.java) | wf.partial-failure | ✅ | P1 已落地（§3.3；注入有效性首跑钉死，真机待跑） |
| [ParallelAllSettledSingleRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelAllSettledSingleRecoveryTest.java) | wf.single-recovery（显式看守） | ✅ | P2 已落地（§3.4；双 @Feature 兼 FEAT-027 wf.fanin-single-recovery，真机待跑） |
| （可选）单委托变体 | wf.single-member（显式对照） | ⬜ | P3 可选（§3.5） |

**进度**：10 条 MUST 全部有落点且均已实现——显式/伴随 9 条 + 补强 2 类（部分失败、单次恢复显式化，真机待跑）+ 1 可选未落。

---

## 2. 前置条件与共享约定

### 2.1 SUT 部署前置

- **B2 栈**：`edpa-adapter` + `edpa-plan-agent(profile=parallel-transfer, downstream=edpa-adapter)`（不起 edpa-gateway；envexplorer 由 edpa-adapter 的 service-bindings 自动拉起）。profile 切换 plan-agent 系统提示为并行分解：余额串行查完后，李四/王五两笔转账在**同一轮批量派发**，runtime 扇出 2 个并发子会话（共享 parentContextId）。
- **B1 栈**：单 stack `deep-research-auto(parallel-search)` `.downstreams(SEARCH=use-stub, VERIFY)`——框架先起叶子、等就绪，再把各自 baseUrl 注入 `--openjiuwen.service.a2a.remote-agents[0]/[1].url`；search 走 stub fixture（不烧外部搜索配额），verify 是 ReAct 判官真调。
- 两栈均由 `BaseManagedStackTest` 统一 `.start()/.close()`，类级生命周期。

### 2.2 共享测试基础设施

- **B2 驱动**：`Conversation` + `DriveMode.parallelStepUi(SELECTIONS_BY_STEP)` + `Turn.runParallel()`；`ParallelTurnResult` 出口（`childCount/children/serialSteps/parallelEvents/allEvents`）。子会话发现：运行时把子 mid cid 推导为 `parentCid_<batchId>_<toolCallId>` 且不再上线，驱动器经中台 `GET /admin/conversations` 枚举后按 `parentCid_` 前缀 + `_<toolCallId>` 后缀精确配对——余额腿单成员批的 cid 就是裸 parentCid，被前缀天然排除（即"单成员≠并行批"的判别器）。
- **B1 驱动**：`InteractionFlow`（`.assertThat(ctx -> ...)` 读 `ctx.events()`，`.assertAnswer(...)` 读离散 ANSWER）。
- **探针**：`RemoteInvocationProbe`——`fromClientEvents`（fan-out 子项扫描）、`delegations`/`delegationsOfTask`（调用树）、`pendingToolCallIds`/`fanOutToolCallIds`（中断成员）、`streamsByProducer`/`outputProducers`（交织分流）。
- **数据**：B2 用 `BalanceTransferFixtures`（`SENTENCE`/`TRANSFER_DONE`/`assertCoreSemantics`/权威人工步序 `on_payee_input→on_paycard_input→on_confirm_remit`）；B1 用 COMPARISON 查询 + vendor/价格 marker（marker 只取 fixture 结果串、非查询子串，防回显假通过）。
- **断言库/标签**：AssertJ；`@Feature("FEAT-019: 智能体生成并行的下游智能体调用委托")` + story（B2 类级 `ra.parallel-transfer` + `wf.parallel-transfer`；B1 `FEAT-019.parallel-search` + `da.parallel-search`）。

### 2.3 共享设计约定

- **LLM 决策固定**：两主线的 profile 提示就是为并行分解设计的；若模型偶发退化为串行导致扇出断言失败，这是**真实失效信号**（非夹具噪声），但排查时先看 wire 日志区分"模型未同轮生成"vs"生成了但 runtime 未聚合"。
- **续传请求契约**：每轮一次多 part POST，每个 part 的 `metadata.toolCallId` 路由到对应子成员（`body.conversation_id` 保持 parentCid）——请求侧契约不随 wire 演进变化。
- **选择键控**：人工步选择按 `step_id` 键控（非位置序），两腿**非对称**（一腿多一个收款人步）时各自按当前 step_id 取 kv，不被位置序错配。

---

## 3. 子用例设计

> 约定：G/W/T（Given/When/Then），结论分 PASS/FAIL/INCONCLUSIVE；每条附状态与 FEAT 依据。已落地用例给断言组拆解（实现即判据），待建用例给完整 G/W/T。

### 3.1 ✅ B2 — `PlanAgentParallelTransferStreamingTest`（交互续接面，wf.* 主落点）

- **状态**：runnable（已落地，openjiuwen 限定）｜ **FEAT 依据**：§2 全部 10 MUST（除部分失败）；§4 场景 1/2/4/6；§5.2/§5.3。
- **落点**：[PlanAgentParallelTransferStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentParallelTransferStreamingTest.java)`#parallelTransfersA2aStream`。
- **G**：B2 栈就绪；`Conversation` 以 A2A_STREAM 打开。
- **W**：发转账句式（余额查询 + 李四/王五两笔转账）→ 驱动器串行推进至扇出轮 → `runParallel()` 并发驱动 2 个子会话的人工步 → 收终态。
- **T**（断言组，实现即判据）：

| ID | 断言 | 对应能力 |
|---|---|---|
| PT.a | 核心语义不泄露 + ≥2 子会话被驱动 + 未被 maxInteractions 熔断 + 命中转账完成态标记其一 + 终态收束 | 端到端前置 |
| PT.b | 扇出轮终态 statusUpdate 的 `_interruwf.items[]` 列出 ≥2 待输入成员 toolCallId → `childCount() ≥ 2`；且成员 toolCallId 互异、不存在批量工具调用形态 | 多 ToolCall 生成 + 中断型识别 + **批量聚合（核心）** + 单调用形态 |
| PT.c | 每轮**一次多 part POST**、每 part `metadata.toolCallId` 定向路由（conversation_id 保持 parentCid） | toolCallId 稳定关联 + 批量 resume 通道 |
| PT.d | 选择按 step_id 键控：两腿非对称（一腿跳过 `on_payee_input`）仍各自正确 kv；per-leg labels 逐腿复核步序 | 独立调用上下文 |
| PT.e | 防串腿：李四/王五收款人语义落**不同**生产者流（`streamsByProducer` 分流后不共流） | 结果稳定归位 |
| PT.f | kickoff 余额批与扇出转账批两轮委托：delegation 按 `target.taskId` 去重、已完成成员重发不增节点、逐轮前缀树单调只增、source 单一根任务 | 后续轮次再委托不串线 |
| PT.g | 批量续传回复是**单条交织 SSE 流**，分流出 ≥2 个各带 output 事件的生产者，且各桶保持到达序子序列 | 批量 checkpoint/resume（一次回灌承载全部子腿） |
| PT.伴随 | kickoff 余额腿单成员批被串行驱动正常完成（cid=裸 parentCid）；终态一次收尾 | 单成员兼容；all-settled 单次恢复（伴随） |

- **PASS**：全部断言组成立。**FAIL**：任一断裂——PT.b <2 即聚合丢失（last-write-wins 缺陷信号）；PT.e 共流即归位串线；PT.g 分流 <2 即批量回灌退化为逐成员。**INCONCLUSIVE**：模型未同轮批量生成（先按 §2.3 排查口径区分模型侧/ runtime 侧）。

### 3.2 ✅ B1 — `ParallelSearchComparisonTest`（自动完成面，da.*）

- **状态**：runnable（已落地；真机探活待跑——fan-out 事件形状按 v0815 承载位全字段扫描后固化）｜ **FEAT 依据**：§2 多 ToolCall 生成/批量聚合/all-settled 单次汇总；§4 场景 1。
- **落点**：[ParallelSearchComparisonTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/ParallelSearchComparisonTest.java)`#parallelSearchComparisonFanOutAndCompletes`（参数化 A2A_STREAM / A2A_SYNC；单 TC 设计档 [deepagent/DA-09-parallel-search-comparison.md](deepagent/DA-09-parallel-search-comparison.md)）。
- **G**：B1 栈就绪；COMPARISON 查询（fixture 覆盖 deepseek/qwen/豆包三条 route）。
- **W**：发一句 COMPARISON 查询，客户端只发 kickoff、等终态（search/verify 均无状态自动完成，不需并发续轮——与 B2 的差异化价值）。
- **T**（断言组）：

| ID | 断言 | 对应能力 |
|---|---|---|
| DA-09.A | 终态 COMPLETED（协议中立） | 端到端收束 |
| DA-09.B | **核心**（A2A 面）：同一 batch ≥2 个不同 toolCallId（`RemoteInvocationProbe.fromClientEvents` 扫事件流） | 多 ToolCall 生成 + 批量聚合 + 并发分发 |
| DA-09.C | artifact 含 ≥2 vendor 名 + ≥1 价格信号词（marker 只取 fixture 结果串） | all-settled 后单次汇总语义（结果纳入 ≥2 家真实搜索） |
| DA-09.D | bug 标志串缺席（`deep_agent_task_1 already exists` 等） | 回归看门狗 |

- **PASS/FAIL**：如上。**INCONCLUSIVE**：fan-out 事件在流中完全扫不到承载位（按 DA-09 档降级处理，不直接判 FAIL；探活时优先全字段扫描）。

### 3.3 ✅ `ParallelPartialFailureResumeTest` — 部分失败回灌（wf.partial-failure，P1）

- **状态**：runnable（已落地，真机待跑；注入有效性首跑钉死）｜ **FEAT 依据**：§4 场景"部分失败回灌" + §5.3（成功项与结构化失败项均按 `toolCallId` 归位；失败不覆盖成功；未收到结果的成员不得伪造成功）。
- **落点**：[ParallelPartialFailureResumeTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelPartialFailureResumeTest.java)`#feat019PartialFailureResume`（基类 [AbstractParallelTransferAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/AbstractParallelTransferAcceptanceTest.java) 共享 B2 栈与会话工厂）。
- **G**：B2 栈就绪；注入手段已定型 = **非对称步业务级**——仅李四腿（收款人需人工解析）有 `on_payee_input` 步、王五腿跳过，把该步 `recSerialNum` 给成无效值（`SN-INVALID-0000`）即只让一腿拿无效入参（公共驱动 API 的选择表按 step_id 共享，非对称步是唯一 per-leg 差异化面；栈级 per-leg dummy URL 已证当前栈无 per-leg 别名，不采用）。
- **W**：发转账句式驱动两腿并行（选择表仅 payee 步无效，paycard/confirm 保持有效值）。
- **T**：成功腿完成态标记命中且其语义只落在自己的生产者流；失败腿出现**结构化失败信号**（从宽匹配：state=failed 的 status agentEvent ∨ 腿流文本含失败类标记）；失败腿语义与成功腿不共流；父任务达终态（不被 maxInteractions 熔断悬挂）+ 汇总覆盖两腿。
- **PASS**：四条全成立。**FAIL**：失败覆盖成功结果 / 父悬挂 / 逐成员恢复。**INCONCLUSIVE**（assumeTrue 跳过留痕）：注入未生效——业务不校验无效 `recSerialNum`，记录测试性事实回本档钉承载位/换注入手段，不静默降级为双成功通过。

### 3.4 ✅ `ParallelAllSettledSingleRecoveryTest` — all-settled 单次恢复显式看守（wf.single-recovery，P2）

- **状态**：runnable（已落地，真机待跑；双 @Feature 兼 FEAT-027 wf.fanin-single-recovery 断言组）｜ **FEAT 依据**：§2"all-settled 后单次继续推理"（不得每个下游完成后各自触发一次）+ §5.3。
- **落点**：[ParallelAllSettledSingleRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelAllSettledSingleRecoveryTest.java)`#feat019AllSettledSingleRecovery`。
- **G**：B2 业务流，两腿均已驱动至完成（标准有效选择驱动）。
- **W**：扫描 `parallelEvents()` 到达序至终态的全部事件。
- **T**：父实质输出只出现在全部子腿带标签事件之后且非空（透传期间父无中间推理轮 + all-settled 后单次恢复）；fan-in delegation 稳定（并行阶段 target 集合 == 串行阶段终态、source 单一根——已完成成员重发不增节点）。**测试性缺口（记录不作硬断）**：终态 statusUpdate 恰一次在 Conversation 面不可见（adapter 丢弃无文本终态帧），以 STATE 事件计数 println 作人工证据；裸帧看守由 edpa 线既有 `EdpaAllSettledSingleRecoveryTest` 形态覆盖（另栈）。
- **PASS**：时序正确 + fan-in 稳定。**FAIL**：父中间推理轮穿插（逐成员恢复缺陷）或收尾先于某腿 output / 树增节点。**INCONCLUSIVE**：父收尾不在 `parallelEvents()`（按线格式事实校准承载面）。

### 3.5 ⬜（可选）单委托显式对照（wf.single-member，P3）

- **现状**：单成员兼容已有两路伴随证据——B2 kickoff 余额腿（单成员批、裸 parentCid、串行驱动）+ 既有串行主线（`AbstractBalanceThenTransfersTest` 系，如 `PlanAgentDirectStreamingTest`）。
- **可选补强**：同栈同句式只发"查余额"的单委托变体，形成 parallel profile 下"单委托 vs 批量"的显式对照；优先级低，挂 ⬜ 观察。

### 3.6 不建 / 黑盒不可达

- **`toolCallId` 缺失/重复 → 可诊断错误**（§5.2）：黑盒面无法构造（LLM 不会生成非法 ID）也无法观测 core 内部错误；依赖日志/源码级验证，不建用例。
- **batchId 可见性**：内部诊断标识不对客户端可见（§3.1），批次归属只靠"共同 source + 同轮时序"推导，不建独立断言。

---

## 4. 框架落点汇总

| 测试类 | 覆盖子用例 | 状态 | 类状态 |
|---|---|---|---|
| [PlanAgentParallelTransferStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentParallelTransferStreamingTest.java) | wf.batch-aggregation / toolcall-routing / independent-context / batch-resume / stable-attribution / next-round-isolation + 伴随（single-member / single-recovery） | runnable | 已有（B2 交互续接面） |
| [ParallelSearchComparisonTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/ParallelSearchComparisonTest.java) | da.auto-fanout | runnable | 已有（B1 自动完成面；真机探活待跑） |
| [ParallelPartialFailureResumeTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelPartialFailureResumeTest.java) | wf.partial-failure | runnable | 已有（P1，真机待跑；注入首跑钉死） |
| [ParallelAllSettledSingleRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelAllSettledSingleRecoveryTest.java) | wf.single-recovery（显式） | runnable | 已有（P2，兼 FEAT-027 fan-in 断言组，真机待跑） |
| （可选）单委托变体 | wf.single-member（显式） | runnable | ⬜（P3 可选） |

落点目录：B2 线落 `src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/`；B1 线落 `src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/`。

### 4.1 落地优先级建议

1. **P0**：B1 真机探活——钉死 deep-research 流里 fan-out 事件承载位（v0815 后优先按 `_interrupt`/delegation 形态全字段扫描），据实固化或降级 DA-09.B。
2. **P1**（已落地，真机探活）`ParallelPartialFailureResumeTest`——10 MUST 中唯一无任何证据的行（部分失败回灌）；注入有效性首跑钉死（业务不校验无效 `recSerialNum` ⇒ assumption 跳过留痕）。
3. **P2**（已落地，真机探活）`ParallelAllSettledSingleRecoveryTest`——把"恰好一次"从伴随证据升为显式看守（兼 FEAT-027 fan-in 断言组）。
4. **P3**（可选）单委托显式对照。

---

## 5. 运行方式

```bash
# B2 — plan-agent 并行转账（交互续接面，openjiuwen 限定）
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentParallelTransferStreamingTest test

# B1 — DA-09 parallel-search COMPARISON（自动完成面，openjiuwen 限定，需 LLM_API_KEY）
./mvnw -Dtest.env=openjiuwen -Dtest=ParallelSearchComparisonTest test

# 补强用例（已落地，真机待跑）
./mvnw -Dtest.env=openjiuwen -Dtest=ParallelPartialFailureResumeTest test    # 部分失败回灌（§3.3，注入有效性首跑钉死）
./mvnw -Dtest.env=openjiuwen -Dtest=ParallelAllSettledSingleRecoveryTest test # 单次恢复显式看守（§3.4，兼 FEAT-027）
```
