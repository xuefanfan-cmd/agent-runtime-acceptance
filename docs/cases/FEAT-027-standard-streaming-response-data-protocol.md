---
feature_id: FEAT-027
feature_title: 标准流式响应数据协议
sut: agent-runtime 流式事件投射面（黑盒观察面 = 投射到客户端 SSE 的 agentEvent 事件流）。载体栈两条：travel 链 mainplan→trip→hotel（多层嵌套 / 透传主载体）+ edpa-plan-agent(parallel-transfer)+edpa-adapter（并发扇出 / 标签载体）
scope: 本档只覆盖 FEAT-027（agent-runtime 主权）可外部黑盒断言的事实要求——delegation/output/status 三类事件的生成、透传、生产者标签注入与保留、并发交织与顺序、最小公共契约（§3.1）、外层 taskId 归属、出口等价与 agentId 配置语义。客户端调用树构建 / 分流渲染 / GetTask 恢复归 FEAT-026（agent-client 主权），不在本档；tokenizer 单 token 粒度、跨源全局顺序、Artifact Parts 业务 schema、身份治理与版本协商按特性档 §5.12 OUT 不列入；SSE wire 帧格式属传输层（wire 日志可核查），不建孤立探针用例
status: designed
owner: TBD
tags: [integration, feat-027, a2a, streaming]
depends_on:
  - openjiuwen profile（-Dtest.env=openjiuwen，需 LLM_API_KEY 等环境变量）
  - travel-demo 三 agent（mainplan/trip/hotel）制品携带 v0815 运行时（delegation/标签线在线）
  - parallel-transfer 栈：edpa-plan-agent（parallel-transfer profile）+ edpa-adapter（envexplorer 由 service-bindings 自动拉起）
related_docs:
  - FEAT-027 特性文档（外部契约）：`docs-agent-solution/develop/02-features/FEAT-027-标准流式响应数据协议.md`（v0815）
---

# FEAT-027 — 标准流式响应数据协议 SIT 测试设计

> **一句话**：多跳远端调用链（A→B→C）上，调用方 Runtime 在首次获知下游 taskId 时生成一次 `delegation` 事件（先于该子 Task 首个 status/Artifact 投射）、为无标签的直接下游输出补一次 `output` 生产者标签、把远端状态投射为去重且终态保护的 `status` 事件；中间 Runtime 对已带 `agentEvent` 的事件**不改写透传**（深层标签不随跳数失真），多源事件交织写入同一 SSE 且各自保留 source。本档从 runtime 投射到客户端 SSE 的黑盒面验证上述**生产侧**契约。

> **组织原则**：
> 1. **判据锚 §3.1 最小公共契约的语义，不锚实现细节形态**——artifactId 命名、事件承载的具体字段布局属实现事实，wire 演进时改探针不改判据方向；新承载位先全字段扫描钉死再固化断言。
> 2. **断言对象是事件流的语义内容，不绕过 SDK 做孤立 wire 探针**——三类事件全部承载于标准 `TaskArtifactUpdateEvent` 的 `Artifact.metadata.agentEvent`，经 `SendStreamingMessage` SSE 到达客户端；SSE wire 帧格式本身不建用例（传输层日志已完整记录，核查现有用例即可获得证据）。
> 3. **同类项合并到单一测试类**：同一载体的多条事实要求合并为一个测试类的多个方法/断言组（travel 链共享 oracle 基类 + 两配置面；wire 契约扫描一类多断言）。
> 4. **配置注入不改共享 yml**：调用边流式差异经 buildStack `.property()` 命令行参数注入（键名必须为 `streaming`——JavaBean 绑定从访问器 `isStreaming()` 派生属性名，`is-streaming` 拼写被静默忽略），随栈销毁，同一套 yml 服务多面。

**状态含义**：**runnable** = 被测能力已实现，可直接落地；**partial** = 核心路径可测、某些断言维度受限；**deferred** = 依赖能力/载体缺失，待补齐后落地；**不建** = 移出 SIT 或无合理注入面。

**域标注**：D = delegation 面；L = 生产者标签面；X = 交织与顺序；C = wire 公共契约；S = 状态投射；B = 桥接与 fan-in；E = 出口；I = 身份标识。

---

## 1. 覆盖矩阵

对应 FEAT-027 §2 能力表（15 MUST）与 §5 行为语义。**子用例 ID 前缀 = 载体线**：`ra` = react travel 链、`wf` = workflow/plan-agent 线（parallel-transfer、versatile）——与仓内 Allure story 前缀约定一致（story 取子用例 ID 去特性前缀的 `<线>.<slug>`，如 `ra.nested-delegation-passthrough`），报告树按 feature→story 可回溯。本特性载体落 ra/wf 两线。

| FEAT-027 事实要求 | 域 | 本档子用例 ID | 现状 | 状态 | 落点类（合并） |
|---|---|---|---|---|---|
| delegation 生成（调用方 Runtime、source=父/target=子、不空 target） | D | `FEAT-027.ra.nested-delegation-passthrough` | 已落地（流式注入面） | runnable | [TravelNestedDelegationTreeRemoteStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/TravelNestedDelegationTreeRemoteStreamingTest.java)（自含 oracle，§3.1；方法级 @Story ra.nested-delegation-passthrough） |
| delegation 透传（保留 source/target/Artifact identity，不改写为中间节点） | D | 同上 | 已落地（流式注入面） | runnable | 同上 |
| 多层嵌套（C 的输出经 B、A 后仍标记为 C，树深可达 ≥3） | D | 同上 | 已落地（流式注入面） | runnable | 同上 |
| delegation 先于该子 Task 首个 status/Artifact 投射 | X | `FEAT-027.ra.order-delegation-first` | 已落地（真机待跑） | runnable | [AgentEventContractScanTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/AgentEventContractScanTest.java)（§3.3，方法级断言组） |
| 同源不重排 / 跨源无序容忍（per-source 观察序保持，分流不依赖到达顺序） | X | 同上 + `FEAT-027.wf.interleave-label-preservation` | 已落地（扫描 + 交织伴随） | runnable | [AgentEventContractScanTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/AgentEventContractScanTest.java) + [PlanAgentParallelTransferStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentParallelTransferStreamingTest.java)（跨特性证据：主注册 FEAT-019/026，本档登记其生产侧断言，§3.2） |
| 流式输出生产者标签注入（无标签的直接下游输出补一次 output+非空 source，不重复标） | L | `FEAT-027.wf.interleave-label-preservation` | 已落地（伴随） | runnable | PlanAgentParallelTransferStreamingTest（扇出回复每事件携带互异 source） |
| 已有标签保留（不叠加不失真，中间 Runtime 不解释不校验） | L | `FEAT-027.ra.nested-delegation-passthrough` | 已落地（hotel 标签两跳保真） | runnable | TravelNestedDelegationTreeRemoteStreamingTest |
| 并发交织（同一条 SSE 承载多路下游事件，每事件保留自己的 source） | X | `FEAT-027.wf.interleave-label-preservation` | 已落地（伴随） | runnable | PlanAgentParallelTransferStreamingTest（≥2 生产者同流、防串腿） |
| 控制/业务语义区分（type 三值区分，不依赖文本内容推断） | C | `FEAT-027.ra.wire-minimal-contract` | 已落地（真机待跑） | runnable | [AgentEventContractScanTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/AgentEventContractScanTest.java)（§3.3） |
| delegation wire 最小结构（type/source.agentId/source.taskId/target.agentId/target.taskId 非空） | C | 同上 | 已落地（真机待跑） | runnable | 同上 |
| output wire 最小结构（type/source.agentId/source.taskId 非空） | C | 同上 | 已落地（真机待跑） | runnable | 同上 |
| status wire 最小结构（type/source.agentId/source.taskId/state 非空） | C | 同上 | 已落地（真机待跑） | runnable | 同上 |
| taskId 归属分离（外层 TaskArtifactUpdateEvent.taskId=父 SSE，source.taskId=实际生产者） | C | 同上 | 已落地（真机待跑） | runnable | 同上（对全部带标签事件断言外层 taskId==根） |
| status 投射去重（同 state 同 message 一次）+ 直接下游终态保护（终态后无迟到 status/output，迟到终态不覆盖） | S | `FEAT-027.wf.status-dedup-terminal-guard` | 已落地（真机待跑） | runnable | [StatusProjectionDedupTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/StatusProjectionDedupTest.java)（§3.5） |
| Runtime 层桥接 / fan-in 不变（流式透传不进 core 推理，父只在 all-settled 后单次恢复） | B | `FEAT-027.wf.fanin-single-recovery` | 已落地（真机待跑） | runnable | [ParallelAllSettledSingleRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelAllSettledSingleRecoveryTest.java)（§3.6，兼 FEAT-019 单次恢复显式看守） |
| 私有 `/v1/query` 出口保留 agentEvent 公共字段与语义（来源语义一致，不要求 wire 表面一致） | E | `FEAT-027.wf.v1-query-egress-equivalence` | 待新建 | runnable | ⬜ `V1QueryAgentEventEquivalenceTest`（versatile 栈，§3.7） |
| 合法独立 Message 结果不形成 delegation、不参与 source.taskId 分流 | D | `FEAT-027.ra.message-non-delegation` | 构造面待探活 | partial | ⬜（§3.8-1；当前下游均产生 Task，需可返回独立 Message 的下游端点） |
| agentId 配置语义（本地缺省 `agent`；远端 agentName 缺失=配置错误拒绝，不得填补） | I | `FEAT-027.ra.agentid-config-semantics` | 待新建 | runnable | ⬜（§3.8-2；env-gated 栈改造） |
| Task 型事件 taskId 缺失/不一致 → 远端协议错误 | I | `FEAT-027.ra.taskid-protocol-error` | 需可控下游 | deferred | ⬜（§3.8-3；需 mock 下游基建） |
| sync 调用边的事件投射语义（口径已定：边模式——非流式边对该边子树上游不透明，§3.1-2/§3.4） | D | `FEAT-027.ra.sync-edge-opacity` | 默认面反向见证 + 混合拓扑判别均已落地（预期红-启用） | runnable | [StreamingTravelPlanningTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/StreamingTravelPlanningTest.java)`#defaultRemoteEdgesProjectNoAgentEvents`（默认配置面，方法级双挂 FEAT-004 栈）+ [MixedTopologySyncEdgeOpacityTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/MixedTopologySyncEdgeOpacityTest.java)（§3.4） |

> **不在本档范围**（对齐特性档 §2 OUT / §5.12）：
> - **客户端消费面**（FEAT-026 主权）：调用树构建、交织流分流渲染、GetTask 断点恢复——本档消费同一探针（`RemoteInvocationProbe`）但只断生产侧语义。
> - **不建用例**：SSE wire 帧格式（传输层日志核查）；未知 `type` 的扩展规则（仅作透传观察记录，不独立用例）；tokenizer 粒度、跨源全局顺序（不承诺项，跨源顺序不作断言）。
> - **特性档明示 OUT**：Artifact Parts 业务 schema、身份治理与版本协商、客户端连接协议适配（FEAT-006）。
> - **归 component / mock 层**：JSON-RPC 分发层错误面；本 SUT 作为被调方的入参校验。

### 1.1 覆盖进度看板

> **图例**：✅ 已落地；⬜ 待新建；🔴 watchdog（oracle 锚 spec 字面要求，当前 SUT 行为与之分歧，对齐后转绿）；△ 跨特性证据（主注册在其他 feature，本档登记）。

| 测试类 | 子用例 | 状态 | 说明 |
|---|---|---|---|
| [TravelNestedDelegationTreeRemoteStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/TravelNestedDelegationTreeRemoteStreamingTest.java) | ra.nested-delegation-passthrough（A/B/C 断言组）· 流式注入面 | ✅ | 主执行面（自含 oracle，方法级 @Story；全链流式，MUST 主路径） |
| [StreamingTravelPlanningTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/StreamingTravelPlanningTest.java)`#defaultRemoteEdgesProjectNoAgentEvents` | ra.sync-edge-opacity · 默认配置面 | 🔴 | 全 sync 边反向见证（0 agentEvent，§3.1-2；启用-预期红，搭 FEAT-004 默认栈顺风车） |
| [PlanAgentParallelTransferStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentParallelTransferStreamingTest.java) | wf.interleave-label-preservation | △ | 主注册 FEAT-019/026；其交织/标签断言即本档生产侧证据（§3.2） |
| [AgentEventContractScanTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/AgentEventContractScanTest.java)（ra 线） | ra.wire-minimal-contract + ra.order-delegation-first | ✅ | P1 已落地（§3.3；两跳流式注入栈单驱动三断言组，真机待跑） |
| [MixedTopologySyncEdgeOpacityTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/MixedTopologySyncEdgeOpacityTest.java)（ra 线） | ra.sync-edge-opacity（判别面） | 🔴 | P2 已落地（§3.4；启用-预期红，真机首跑记录穿帮证据） |
| [StatusProjectionDedupTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/StatusProjectionDedupTest.java)（wf 线） | wf.status-dedup-terminal-guard | ✅ | P2 已落地（§3.5；真机待跑） |
| [ParallelAllSettledSingleRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelAllSettledSingleRecoveryTest.java)（wf 线） | wf.fanin-single-recovery | ✅ | P3 已落地（§3.6；兼 FEAT-019 单次恢复显式看守，真机待跑） |
| ⬜ `V1QueryAgentEventEquivalenceTest`（wf 线） | wf.v1-query-egress-equivalence | ⬜ | P3（§3.7；versatile 栈，单独跟进） |
| ⬜ message / agentid / taskid 三项（ra 线） | ra.message-non-delegation / ra.agentid-config-semantics / ra.taskid-protocol-error | ⬜ / deferred | P4（§3.8；构造受限） |

**进度**：已落地 1 条主线（travel 两面）+ 1 条伴随 + 4 类补强（契约扫描 / 混合拓扑判别 / 状态去重 / 单次恢复，真机待跑）；⬜ 待新建 1 类（V1Query）+ 3 项构造受限。

---

## 2. 前置条件与共享约定

### 2.1 SUT 部署前置

- **travel 栈**（ra 线，嵌套/透传主载体）：leaf-first 声明 `hotel` → `trip(downstream hotel)` → `mainplan(downstream trip)`，框架注入下游 resolved base URL；两配置面共用同一套 `application-openjiuwen.yml`——流式注入面在 mainplan/trip 的远程代理条目上叠加 `.property("openjiuwen.service.a2a.remote-agents[0].streaming", "true")`（各上游单下游，均落下标 0；hotel 无远程调用不需注入）。
- **parallel-transfer 栈**（wf 线，交织/标签载体）：`edpa-adapter` + `edpa-plan-agent(profile=parallel-transfer, downstream=edpa-adapter)`；profile 切换提示为并行分解——余额串行查完后，两笔转账同轮批量派发，天然产生多生产者交织回复。
- **versatile 栈**（wf 线，⬜ 出口等价载体）：REST_VERSATILE 线（`/v1/query` 私有 SSE 出口）+ 一次远端委托调用。

### 2.2 共享测试基础设施

- **驱动面**：travel 线 `InteractionFlow`（A2A_STREAM，`send().awaitState(COMPLETED).execute()`——终态判定 + 事件流出口 + 整轮 wire 日志一步到位）；parallel-transfer 线 `Conversation` + `DriveMode.parallelStepUi(...)` + `Turn.runParallel()`。
- **投影权威（线格式唯一权威）**：`ConversationInteractionAdapter.agentEventOf(raw)`——从 SDK `TaskUpdateEvent`/`MessageEvent` 的 artifact/message metadata 中提取 `agentEvent` Map；所有用例复用同一投影，不各自重写 metadata walk。
- **探针**：`RemoteInvocationProbe`——`delegations`/`delegationsOfTask`（按 target.taskId 建树/去重）、`outputProducers`/`streamsByProducer`（按 source 标签分流）、`pendingToolCallIds`/`fanOutToolCallIds`（中断成员）。
- **wire 日志**：FileWireLogger r 文件（含 `raw:` 帧，失败轮也记）——契约扫描用例（§3.3）的数据源之一，也用于排查"事件未生成"vs"生成未投射"。
- **断言库/标签**：AssertJ；`@Tag("integration")` + `@Feature("FEAT-027: 标准流式响应数据协议")` + 方法级 `@Story`（story = 子用例 ID 去特性前缀，如 `ra.nested-delegation-passthrough`）。

### 2.3 共享命名与数据约定

- 驱动协议固定 A2A_STREAM（本特性验证的是流式投射面）；注入键名固定 `streaming`（组织原则 4）。
- travel 驱动语句 `COMPLETE_REQUEST`（与 `StreamingTravelPlanningTest` 同文：差旅请求差标/品牌/偏好全指定）——固定 LLM 单轮成行且必走 trip→hotel 两跳派发；若 trip 未派发 hotel 属夹具问题（INCONCLUSIVE），区别于 hop-2 事件缺失（SUT 信号）。
- 一跳多候选防御：若 mainplan 多次调 trip，结构性过滤（source ∈ 一跳 target 集合）天然支持多一跳/多二跳，任一有效二跳即满足，深度断言取首个二跳的 target 作 hotel 节点。

---

## 3. 子用例设计

> 约定：G/W/T（Given/When/Then），结论分 PASS/FAIL/INCONCLUSIVE；每条附状态与 FEAT 依据。

### 3.1 嵌套 delegation 生成与透传（ra 线，D 域 + L 域）

#### ✅ `FEAT-027.ra.nested-delegation-passthrough` — 流式注入面（主执行面）

- **状态**：runnable（已落地）｜ **FEAT 依据**：§2 delegation 生成/透传/多层嵌套/已有标签保留；§5.1/§5.2/§5.4。
- **落点**：[TravelNestedDelegationTreeRemoteStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/TravelNestedDelegationTreeRemoteStreamingTest.java)`#feat027NestedDelegationAttachesToParentNode`（自含 oracle，buildStack 注入两跳流式；方法级 @Story ra.nested-delegation-passthrough）。
- **G**：travel 三 agent 栈已启动，mainplan→trip、trip→hotel 两条调用边的远程代理条目已注入 `streaming=true`；客户端经 A2A_STREAM 发 `COMPLETE_REQUEST`。
- **W**：收流至 COMPLETED；`round.events()` 逐事件 `agentEventOf` 投影 → `delegations` 建树、`outputProducers` 取生产者标签集合。
- **T**（断言组）：
  - **A 挂载/深度**：一跳 (mainplan→trip) delegation source=根任务存在；二跳 (trip→hotel) delegation 存在且 source.taskId=trip 任务（**由调用方 trip 的 Runtime 生成、挂父节点下，不挂根**）；三层节点 taskId 互异（树深 3）。
  - **B 深层标签不失真**：hotel 的 output 事件经 trip、mainplan 两跳转发后，生产者标签仍是 hotel 自身 `agentId+taskId`（透传不覆盖、不叠加）。
  - **C 链路真实走通**：单轮 COMPLETED（业务终态前置，树/标签断言建立在其上）。
- **PASS**：三组全满足。**FAIL**：二跳缺失（hop-2 事件未生成/未投射）/ 挂根（深层映射错）/ hotel 输出被改贴 trip/mainplan 标签。**INCONCLUSIVE**：LLM 未走两跳派发（夹具问题，先修语句再校准）。
- **设计说明（为何需要注入面）**：远程代理条目级流式开关缺省 false，默认配置下远程调用全走 sync——本面通过配置注入使两条边流式化，把 §5.1–§5.4 的 MUST 语义置于其主路径（流式透传分支）上验证；注入后仍缺失则排除配置因素，透传/投射链另有独立缺陷。

#### 🔴 `FEAT-027.ra.sync-edge-opacity`（默认配置面）— 全 sync 边反向见证（口径已定：边模式）

- **状态**：runnable（已落地；启用-预期红——SUT 门禁修复后转绿）｜ **FEAT 依据**：§5.1/§5.6（可见性按调用边模式判定）。
- **落点**：[StreamingTravelPlanningTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/StreamingTravelPlanningTest.java)`#defaultRemoteEdgesProjectNoAgentEvents`——方法级双挂（主注册 FEAT-004），搭该类默认 travel 栈顺风车，不另起栈。
- **G/W**：同栈默认配置（两条调用边均 sync，`remote-agents[].streaming` 缺省 false），客户端 A2A_STREAM 发 `COMPLETE_REQUEST` 至 COMPLETED。
- **T**：客户端流 **0 个 agentEvent**——全 sync 链上调用树对客户端完全不可见（非流式边对该边子树上游不透明，传递生效）。
- **口径（已定案，边模式判读）**：agentEvent 树/标签只在全部调用边流式时可见。当前 SUT 把投射门禁挂在 serve 模式而非调用边（run-20260817-231200：全 sync 链一次 sync 调用仍向客户端投影 delegation/output/status 3 事件），本用例预期红即缺陷现场（缺陷档 `docs/a2a-sync-call-agent-event-projection-defect.cn.md` §8）；门禁改挂调用边后本用例转绿。混合拓扑判别（§3.4）负责区分"碰巧正确"与"规则正确"，全流式正向对照 = §3.1。
- **PASS**：0 agentEvent。**FAIL**：任何 agentEvent 穿透 sync 边（诊断 println 给出 type@source 明细）。**INCONCLUSIVE**：链路未走通（前置断言单轮 COMPLETED）。

### 3.2 并发交织与标签保持（wf 线，X 域 + L 域）— 跨特性证据登记

- **状态**：runnable（已落地；主注册 FEAT-019/FEAT-026，本档登记其生产侧断言为证据）｜ **FEAT 依据**：§2 并发交织/流式输出生产者标签；§5.3/§5.6。
- **落点**：[PlanAgentParallelTransferStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentParallelTransferStreamingTest.java)（并行转账：同轮扇出 2 个子会话，批量续传回复为单条交织 SSE）。
- **本档视角的断言映射**（断言实现已在该类，不重复建设）：
  - 扇出轮 delegation ≥2 个互异 `target.taskId`、按 target 去重 → delegation 生成（每子调用一次）；
  - 交织回复分流出 ≥2 个各带 output 事件的生产者 → 同一 SSE 多源承载；
  - 每事件携带互异 source、两腿收款人语义不共流（防串腿）→ 每事件保留自己的 source；
  - per-producer 桶为到达序子序列（按事件恒等）→ 同源不重排、按实际观察顺序串行写入；
  - 无孤儿标签（每个带标签事件的生产者 ∈ 调用树已知节点）+ 根自身输出无标签 → 标签完备性 / 本地根输出不注入 agentEvent。

### 3.3 ✅ `AgentEventContractScanTest`（ra 线）— wire 最小契约扫描 + 顺序（C 域 + X 域，P1）

- **状态**：runnable（已落地，真机待跑）｜ **FEAT 依据**：§3.1 字段适用性表 + §2 三条 wire 最小结构 + taskId 归属分离 + §5.6 顺序保证责任边界。
- **落点**：[AgentEventContractScanTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/AgentEventContractScanTest.java)`#feat027WireContractOrderScan`（两跳流式注入栈，单驱动跑契约/归属/顺序三断言组；外层 taskId 取自 adapter 新助手 `ConversationInteractionAdapter.outerTaskIdOf(raw)`）。
- **定位**：把 §3.1 的 MUST 契约做成全量逐事件扫描——不是孤立探针，而是对任一携带 agentEvent 的真实业务流的完整性校验；主载体 travel 流（嵌套 delegation + status 最丰富），可换 parallel-transfer 流或回放 wire 日志，落地成本低、价值密度高。
- **G**：任一携带 agentEvent 的事件流就绪（在线收流或既有 r 文件回放）。
- **W**：逐事件提取 `artifact.metadata.agentEvent`，按 `type` 分桶；记录每个事件的流内索引与外层 `TaskArtifactUpdateEvent.taskId`。
- **T**（方法级断言组）：
  - **契约组**：`delegation` → type/source.agentId/source.taskId/target.agentId/target.taskId 全非空；`output` → type/source.agentId/source.taskId 非空；`status` → type/source.agentId/source.taskId/state 非空；type ∉ 三值的事件原样在场（不降级、不被改写——观察记录，不断言扩展规则）。
  - **归属组**：全部带标签事件的外层 `taskId` == 当前 SSE 所属根任务（两个维度不得混淆）；delegation 携带 `toolCallId` 扩展时透传不删改（观察记录）。
  - **顺序组**：对每个子 Task（target.taskId），其 delegation 的流内索引 < 该子 Task 首个 status/output 索引；同一 `source.taskId` 的 output 序列保持相对顺序（天然子序列，无重排痕迹）；跨源顺序不作断言（不承诺项）。
- **PASS**：全量合规。**FAIL**：任一 MUST 字段缺失/空、外层 taskId 指向非当前 SSE 任务、delegation 迟于该子 Task 首个业务事件、同源序列重排。**INCONCLUSIVE**：流中无带标签事件（载体未产生委托调用）。

### 3.4 🔴 `MixedTopologySyncEdgeOpacityTest`（ra 线）— 混合拓扑 sync 边判别（D 域，P2，red-first）

- **状态**：runnable（已落地；**启用-预期红**，真机首跑记录穿帮证据）｜ **FEAT 依据**：§5.1 生成语义 + §5.6（判别投射规则与 serve 模式是否解耦）。
- **落点**：[MixedTopologySyncEdgeOpacityTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/MixedTopologySyncEdgeOpacityTest.java)`#feat027SyncEdgeOpaqueInMixedTopology`（缺陷档 `docs/a2a-sync-call-agent-event-projection-defect.cn.md` §8-4；正控 trip 事件 + 硬断言 0 条 hotel 事件，非空性夹具守卫防空洞通过）。
- **定位**：区分"全 sync / 全 stream 拓扑下碰巧正确"与"按规则正确"的判别用例——若投射门禁只挂 serve 模式，则仅上游 serve 流式就会把 sync 边子树的全部事件透出，sync 边"对上游不透明"的边界即被证伪。
- **G**：travel 栈，仅给 **mainplan→trip 边**注入 `streaming=true`（trip→hotel 保持缺省 sync）——构造 A—stream—B—sync—C 混合拓扑。
- **W**：A2A_STREAM 驱动 mainplan 单轮完整请求，收集客户端流全部事件。
- **T**（按边模式口径）：客户端流 **0 条 `travel-hotel` 事件**（sync 边对其上游整棵子树不透明——hotel 的 delegation/output/status 均不得穿越该边）；trip 的 sync 结果收敛为一次结果交付（不出现 delegation+output+status 多事件连发 + 正文复述）。
- **PASS**：两条满足（投射按调用边模式判定）。**FAIL**：hotel 事件穿越 sync 边 / sync 调用多事件连发。**INCONCLUSIVE**：trip 未派发 hotel（夹具问题）。
- **设计说明**：口径已定（边模式，§3.1-2）——本用例 oracle 即定案 oracle，无反转分支；与 §3.1-2 默认面反向见证（全 sync）合计覆盖两种非流式拓扑（全 sync / 混合）的回归面，全流式正向对照 = §3.1。

### 3.5 ✅ `StatusProjectionDedupTest`（wf 线）— status 去重与终态保护（S 域，P2）

- **状态**：runnable（已落地，真机待跑）｜ **FEAT 依据**：§5.5 状态投射语义（去重规则 / 终态保护时点 / 成员粒度）。
- **落点**：[StatusProjectionDedupTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/StatusProjectionDedupTest.java)`#feat027StatusDedupAndTerminalGuard`（基类 [AbstractParallelTransferAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/AbstractParallelTransferAcceptanceTest.java) 共享 parallel-transfer 栈与会话工厂，`allEvents()` 到达序逐事件扫描）。
- **G**：parallel-transfer 栈业务流（两腿 = 两个直接下游子 Task，事件量充足；travel 流式栈可替换）。
- **W**：按 `source.taskId` 归集 status/output 事件序。
- **T**：同 `state` 且同 `message` 的 status 每源至多一条（不同 message 的同名 state 可分别投射）；每个**直接下游**源：首个终态 status（completed/failed/canceled/rejected）之后，该源不再出现新的 status 或 output；迟到的不同终态不覆盖已投射终态（同源不出现第二个终态 status）；delegation 不受终态门禁影响；终态保护以调用成员为粒度，不影响其他并发子源的事件。
- **PASS**：全满足。**FAIL**：重复投射同内容 status / 终态后仍有迟到事件 / 终态被覆盖 / 保护越界波及兄弟源。**INCONCLUSIVE**：某源事件过少无法构成判定。

### 3.6 ✅ fan-in 不变 — 父单次收尾断言组（wf 线，B 域，P3）

- **状态**：runnable（已落地，真机待跑；与 FEAT-019 单次恢复显式看守同车落地）｜ **FEAT 依据**：§2 Runtime 层桥接 + fan-in 不变；§5.9。
- **落点**：[ParallelAllSettledSingleRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelAllSettledSingleRecoveryTest.java)`#feat019AllSettledSingleRecovery`（双 @Feature 挂 FEAT-019/027）。**测试性缺口**："终态 statusUpdate 恰一次"在 Conversation 面不可见（adapter 丢弃无文本终态帧）——以 STATE 事件计数 println 作人工证据，裸帧看守由 edpa 线既有 `EdpaAllSettledSingleRecoveryTest` 形态覆盖（另栈）；若首跑发现父收尾不在 `parallelEvents()`，按线格式事实校准承载面。
- **G**：parallel-transfer 业务流，两腿均已驱动至完成。
- **W**：从批量回灌（最后一次多 part POST）时刻起，扫描至终态的全部事件。
- **T**：父（根任务）的收尾推理输出恰出现一轮（观察位：根生产者的汇总性 output/推理事件簇），且该轮发生在全部子腿 output 之后；终态 statusUpdate 恰一次；流式透传期间不出现父的中间推理轮（不逐事件进 core）。
- **PASS**：收尾轮 == 1 且时序正确、终态唯一。**FAIL**：≥2 轮（逐成员恢复）或收尾先于某腿 output。**INCONCLUSIVE**：收尾输出在事件流上不可分辨（记录承载位事实，反馈测试性）。

### 3.7 ⬜ `V1QueryAgentEventEquivalenceTest`（wf 线）— 私有出口等价（E 域，P3）

- **状态**：runnable（待新建）｜ **FEAT 依据**：§3（`/v1/query` 等私有 SSE 出口投影 `content + metadata.agentEvent`，保留公共字段与语义；不要求 wire 表面一致）。
- **G**：versatile 栈（REST_VERSATILE 线，`/v1/query` 出口）+ 一次远端委托调用。
- **W**：分别经 A2A `SendStreamingMessage` 与 `/v1/query` 消费同一远端事件。
- **T**：`/v1/query` 投影携带 `agentEvent`，其公共字段（type/source[/target]/state）与 A2A 出口语义一致——按**来源语义**判等（同一事件的 type 与 source 身份一致），不逐字段比对 wire 表面；本地根 Agent 普通输出两出口均不带 agentEvent。
- **PASS**：语义等价成立。**FAIL**：私有出口丢失 agentEvent / source 身份漂移。**INCONCLUSIVE**：versatile 线远端委托构造不成立（载体校准）。

### 3.8 构造受限三项（ra 线，P4）

1. **⬜ `FEAT-027.ra.message-non-delegation`**（partial）：合法独立 Message 结果正常完成调用、不形成 delegation、不参与 `source.taskId` 分流。当前 openjiuwen 下游均产生 Task 生命周期，需可返回独立 Message 的下游端点（mock 下游或特定 fixture）；先行探活确认构造面，无载体则长期记录测试性缺口。
2. **⬜ `FEAT-027.ra.agentid-config-semantics`**（env-gated）：① 未配置 `spring.application.name` 的实例，其自产事件的 `source.agentId` 为合法默认值 `agent`（非空、不崩溃）——需去名实例栈改造；② 上游 `a2a_delegate` 条目 `agentName` 置空 → 调用在成员解析阶段被拒绝（本地配置错误），不得用父/自身 agentId 或 tool name 填补——可经 `.property` 注入构造。
3. **⬜ deferred `FEAT-027.ra.taskid-protocol-error`**：Task 型事件 taskId 缺失/不一致按远端协议错误处理（可诊断、不生成空 target、不静默吞）。需可控/mock 下游发出不一致事件，mock 下游基建就绪前 deferred。

---

## 4. 框架落点汇总

| 测试类 | 覆盖子用例 | 状态 | 类状态 |
|---|---|---|---|
| [TravelNestedDelegationTreeRemoteStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/TravelNestedDelegationTreeRemoteStreamingTest.java) | ra.nested-delegation-passthrough（全流式注入，自含 oracle） | runnable | 已有 |
| [StreamingTravelPlanningTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/StreamingTravelPlanningTest.java)`#defaultRemoteEdgesProjectNoAgentEvents` | ra.sync-edge-opacity（默认面反向见证） | runnable | 已有（方法级双挂，主注册 FEAT-004） |
| [PlanAgentParallelTransferStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentParallelTransferStreamingTest.java) | wf.interleave-label-preservation | runnable | 已有（跨特性证据，主注册 FEAT-019/026） |
| [AgentEventContractScanTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/AgentEventContractScanTest.java)（ra 线） | ra.wire-minimal-contract + ra.order-delegation-first | runnable | 已有（P1，真机待跑） |
| [MixedTopologySyncEdgeOpacityTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/MixedTopologySyncEdgeOpacityTest.java)（ra 线） | ra.sync-edge-opacity（判别面） | runnable | 已有（P2，启用-预期红） |
| [StatusProjectionDedupTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/StatusProjectionDedupTest.java)（wf 线） | wf.status-dedup-terminal-guard | runnable | 已有（P2，真机待跑） |
| [ParallelAllSettledSingleRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelAllSettledSingleRecoveryTest.java)（wf 线） | wf.fanin-single-recovery | runnable | 已有（P3，兼 FEAT-019 显式看守） |
| `V1QueryAgentEventEquivalenceTest`（wf 线） | wf.v1-query-egress-equivalence | runnable | ⬜ 待新建（P3，versatile 栈） |
| message / agentid / taskid（ra 线） | ra.message-non-delegation / ra.agentid-config-semantics / ra.taskid-protocol-error | partial / runnable / deferred | ⬜（P4，构造受限） |

落点目录：ra 线落 `src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/`；wf 线落 `src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/`（含 versatile 线）。

### 4.1 落地优先级建议

1. **P1**（已落地，真机探活）`AgentEventContractScanTest`——契约/归属/顺序三组断言一次补齐 §3.1 全部 MUST。
2. **P2**（已落地，真机探活）`MixedTopologySyncEdgeOpacityTest`（预期红：sync 边口径判别，与 §3.1-2 默认面反向见证同属边模式 oracle）+ `StatusProjectionDedupTest`（状态投射面）。
3. **P3**（已落地，真机探活）wf.fanin-single-recovery 断言组（[ParallelAllSettledSingleRecoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ParallelAllSettledSingleRecoveryTest.java)，兼 FEAT-019）；⬜ `V1QueryAgentEventEquivalenceTest`——versatile 栈单独跟进。
4. **P4** ⬜/deferred 构造受限三项——随 mock 下游与栈改造能力解锁。

---

## 5. 运行方式

```bash
# 嵌套 delegation 生成与透传（ra 线，travel 链两面）
./mvnw -Dtest.env=openjiuwen -Dtest=TravelNestedDelegationTreeRemoteStreamingTest test   # 流式注入面（主执行面，全流式自含 oracle）
./mvnw -Dtest.env=openjiuwen -Dtest=StreamingTravelPlanningTest test                     # 默认配置面反向见证（#defaultRemoteEdgesProjectNoAgentEvents 预期红；余为 FEAT-004 A-07/A-08/C-03）

# 并发交织与标签保持（wf 线，跨特性证据，主注册 FEAT-019/026）
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentParallelTransferStreamingTest test

# 本档补强用例（已落地，真机待跑）
./mvnw -Dtest.env=openjiuwen -Dtest=AgentEventContractScanTest test           # 契约/归属/顺序三组扫描（ra，§3.3）
./mvnw -Dtest.env=openjiuwen -Dtest=MixedTopologySyncEdgeOpacityTest test     # 混合拓扑 sync 边判别（ra，§3.4，预期红）
./mvnw -Dtest.env=openjiuwen -Dtest=StatusProjectionDedupTest test           # status 去重 + 终态保护（wf，§3.5）
./mvnw -Dtest.env=openjiuwen -Dtest=ParallelAllSettledSingleRecoveryTest test # fan-in 单次收尾断言组（wf，§3.6，兼 FEAT-019）

# 待新建
./mvnw -Dtest.env=openjiuwen -Dtest=V1QueryAgentEventEquivalenceTest test
```
