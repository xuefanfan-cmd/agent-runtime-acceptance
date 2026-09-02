---
scope: v0730
deployable_units: [agent-runtime]
sut: AgentScope 样例 agent — ReActAgent + HarnessAgent 双变体 + 父侧承载 agent（travel-trip）；均 remote url-only 声明
features: [FEAT-002]
updated: 2026-08-26
---

# FEAT-002 — AgentScope 侧异构智能体框架兼容适配：用例设计与真机进展

本文件是 `docs/testplan/FEAT-002-heterogeneous-agent-framework-compatibility-agentscope.md`（方案级测试设计）的**实现层细档**：承载覆盖进度看板、逐条子用例的 G/W/T 与判据、共享前置约定、真机实测进展（滚动记录）与风险备注。

**分层纪律**：方案文档只锚定稳定的场景条目（矩阵 ID）；测试代码到场景的映射以测试仓当前代码为准，不在方案维护类目录；测试进展、判据落定、验证结论统一记录于本文件 §5。

## 1. 覆盖矩阵

> **对账基准**：本表按 testplan 方案矩阵的场景 ID（`docs/testplan/FEAT-002-*.md` §5，14 条稳定契约）逐条对账——场景条目固定，代码落点随测试仓演进。
> **图例**：✅ 已建已验；🟡 partial / 半建 / red-first 设计内；⬜ 待建；⏸ deferred（待 SUT 演示补齐）；🚧 pending（依赖上游能力）。真机进展与判据钉死见 §5。

### 1.1 覆盖进度看板

| 矩阵 ID | 场景 | 状态 | 落点与备注 |
|---|---|---|---|
| H1 | 无中断查询链路串通（U1 简单查询） | ⬜ | `AgentScopeAdapterHappyPathTest#querySimple_ReactVariant`；COMPLETED + 候选家数关键词弱断言 |
| H2 | 无中断 + tool 确定性输出（U2 详情查询） | ⬜ | `AgentScopeAdapterHappyPathTest#queryDetail_ReactVariant`；COMPLETED + tool 稳定输出字段（`BJ-001` / `BJ-001-R*`）硬断言 |
| **H3** | ⭐ ReAct + confirmation APPROVE 完整闭环 | ⬜ | `AgentScopeAdapterConfirmationInterruptTest#confirmationApproveClosesFullLoop`；首轮 INPUT_REQUIRED + 续轮 `APPROVE`+`kind=confirmation` → COMPLETED + `BK-` 前缀 tool 真调证据 |
| I1 | confirmation 精确 REJECT 转 `ConfirmResult` | ⬜ | `AgentScopeAdapterConfirmationInterruptTest#confirmationRejectMapsToConfirmResult`；续轮 COMPLETED + "取消"关键词弱断言 + 不含 `BK-` 硬断言 |
| **I2** | ⭐ §5.1.5 边界：不承诺自然语言确认（confirmation 侧） | ⬜ | `AgentScopeAdapterConfirmationInterruptTest#naturalLanguageConfirmMustNotComplete`；续轮 metadata 无 `_interrupt` → 终态 ≠ COMPLETED |
| I3 | confirmation INPUT_REQUIRED marker shape | ⬜ | `AgentScopeAdapterConfirmationInterruptTest#interruptMarkerShapeCarriesToolCallIdAndToolName`；`_interrupt.message` 非空 + `items[]` ≥1 + `toolCallId` + `toolName` 含 `hotel` |
| T1 | Harness + tool_result INPUT_REQUIRED marker | ⬜ | `AgentScopeAdapterToolResultInterruptTest#toolResultInterruptMarkerCarriesNameAndArguments`；`items[]` ≥1 + `toolName` 含 `lookup_customer_profile` + 参数信息（arguments/args/message 之一） |
| **T2** | ⭐ 外部结果续轮 → ToolResultBlock → COMPLETED | ⬜ | `AgentScopeAdapterToolResultInterruptTest#externalResultResumeReachesCompleted`；续轮 COMPLETED + text 引用外部结果关键字段（`VIP`/`铂金`/`3000`/`全季`/`亚朵` 之一） |
| **T3** | ⭐ §5.1.5 边界：不承诺自然语言确认（tool_result 侧） | ⬜ | `AgentScopeAdapterToolResultInterruptTest#naturalLanguageResultMustNotComplete`；续轮 metadata 无 `_interrupt` → 终态 ≠ COMPLETED |
| R1 | ReAct 变体不可达 → 父任务 FAILED | ⬜ | `AgentScopeAdapterReliabilityTest#hotelUnreachable_ParentFails`；父任务 FAILED/等义 + 文本含异常关键词（§5.1.5 软断言）|
| **R2** | ⭐ 中断态 ReAct 变体被杀 → 不得 COMPLETED | ⬜ | `AgentScopeAdapterReliabilityTest#hotelDiesInInterruptState_MustNotComplete`；续轮终态 ∈ {FAILED,CANCELED,REJECTED} + 无伪造正常收尾 + 异常关键词 |
| M1 | AgentScope `message` 空消息续跑 | ⏸ deferred | 当前 SUT `agentscope-hotel*` 未演示 message stop skill；补 SUT skill 后可测 |
| K1 | AgentScope 协作式取消 | 🚧 pending | 依赖 runtime 取消面实现，与 FEAT-002 workflow 档同步启用 |
| K2 | 取消幂等 | 🚧 pending | 同 K1 |
| K3 | 不承诺立即中断底层 LLM（软断言） | 🚧 pending | 同 K1（联动） |

**台账快照（2026-08-26 重建首建）**：14 条 = ⬜ 11（H1/H2/H3/I1/I2/I3/T1/T2/T3/R1/R2）· ⏸ 1（M1）· 🚧 3（K1/K2/K3）。首建目标是**11 条 ⬜ 全落地代码**——`AgentScopeAdapterHappyPathTest`（H1/H2）+ `AgentScopeAdapterConfirmationInterruptTest`（H3/I1/I2/I3）+ `AgentScopeAdapterToolResultInterruptTest`（T1/T2/T3）+ `AgentScopeAdapterReliabilityTest`（R1/R2） + `AgentScopeAdapterFixtures`（常量+助手+前置门）。首轮真机后按 P0 全绿标准过账 §5。

**下一步优先级**：

1. **P0**（先绿）：H3（confirmation APPROVE 闭环）+ T2（tool_result 完整闭环）+ I2/T3（§5.1.5 边界对称看守）+ R2（§5.1.2 无 End 不得 COMPLETED）
2. **Smoke**：H1/H2/H3/T2（各变体核心闭环各 1）
3. **R 组** 依赖外部关停样例 agent，CI 默认 `@Tag("manual")`；接入 managed 声明后自动化
4. **M 组** ⏸ 待 SUT 演示 message stop skill 补齐
5. **K 组** 🚧 待 runtime 取消面实现，与 FEAT-002 workflow 档同步

## 2. 前置约定（共享）

### 2.1 SUT 部署形态（remote url-only）

- **ReAct 变体**：`agentscope-hotel-0.1.0.jar` 独立进程，默认 18120；差旅酒店场景；skill = hotel_search / hotel_detail / book_hotel（后者 confirmation 中断）
- **Harness 变体**：`agentscope-hotel-harness-0.1.0.jar` 独立进程，默认 18121（可通过 `AGENTSCOPE_HOTEL_HARNESS_PORT` 覆盖）；CRM 客户档案查询；skill = lookup_customer_profile（external tool_result 中断）
- **父侧承载 agent**（仅 ReAct 场景）：`travel-trip-0.1.0.jar` 独立进程 8092；`TRAVEL_HOTEL_A2A_URL=http://localhost:18120` 环境变量指向 ReAct 变体

三个 jar 位于 `D:\agent-solution-common\dist\`，**未安装到本地 Maven 仓库**；本方案以 `application-local.yml` 中 `sut.agents.*.url` 直接声明 remote 接入。测试运行前须手工/`run.ps1` 启动。

### 2.2 前置门（`@BeforeAll` 探测）

- `AgentScopeAdapterFixtures.assumeTripAndHotelReady(...)` —— ReAct 场景：`travel-trip-agentscope` (8092) + `agentscope-hotel` (18120) 两端 agent-card 均 200 可达；否则 `assumeTrue(false, ...)` 全类 skip
- `AgentScopeAdapterFixtures.assumeHarnessReady(...)` —— Harness 场景：`agentscope-hotel-harness` (18121) agent-card 200 可达；否则全类 skip
- `AgentScopeAdapterFixtures.assumeTripReadyAndHotelUnreachable(...)` —— R1 场景：`travel-trip-agentscope` 可达 + `agentscope-hotel` 不可达（外部关停）；否则全类 skip

### 2.3 LLM 凭据

- 环境变量 `EDP_AGENT_MODEL_*` 派生 `DEEPSEEK_*` / `OPENJIUWEN_TRAVEL_TRIP_LLM_*` 注入到样例 agent 进程；**凭据不落 yml/代码**
- 前置门缺 LLM key → `assumeTrue(false, ...)` 前置 skip（不判 FAIL）

### 2.4 客户端 wire 契约（U1/U2/U3 抓包锚点）

- ReAct 场景 U1（简单查询）/ U2（详情查询）/ U3a（book_hotel INPUT_REQUIRED）/ U3b（续轮 APPROVE）四份 wire 抓包位于 `D:\agent-solution-common\dist\sit-runs\{U1,U2,U3a,U3b}.{request.json,response.sse}`——作为 fixture 与断言 shape 的校准锚点
- Harness 场景 wire 契约见 `agentscope-hotel-harness-demo-src-0.1.0.zip` 内 README

## 3. 用例设计

> 每个测试类 file-level 说明 Story 与 §依据；每个 @Test 方法对应一个矩阵 ID；G/W/T 详见 testplan §5。

### 3.1 无中断链路串通（H1/H2）

- **H1** `AgentScopeAdapterHappyPathTest#querySimple_ReactVariant`
  - G：ReAct 变体 + 父侧承载 agent 就绪（`assumeTripAndHotelReady`）
  - W：客户端 → trip 提交 `AgentScopeAdapterFixtures.QUERY_SEARCH`（U1 样例 query），A2A_STREAM 模式
  - T：终态 `COMPLETED`；final text 含 `AgentScopeAdapterFixtures.SEARCH_RESULT_HINT_KEYWORDS` 之一（候选家数关键词弱断言）
  - PASS：终态 + 弱断言均绿；FAIL：非 COMPLETED 或无关键词；INCONCLUSIVE：前置门未通过

- **H2** `AgentScopeAdapterHappyPathTest#queryDetail_ReactVariant`
  - G：同 H1
  - W：提交 `AgentScopeAdapterFixtures.QUERY_DETAIL`（U2 样例 query）
  - T：终态 `COMPLETED`；final text **硬断言**含 `AgentScopeAdapterFixtures.ORDER_ID_HOTEL_PREFIX`（`"BJ-001"` 或 `"BJ-001-R"`——hotel_detail tool 稳定输出字段，证 tool 真调而非 LLM 编造）
  - PASS：终态 + 硬断言均绿；FAIL：无 `BJ-001*` 关键词

### 3.2 confirmation 侧（H3/I1/I2/I3）

- **H3** ⭐ `AgentScopeAdapterConfirmationInterruptTest#confirmationApproveClosesFullLoop`（P0 必须绿）
  - G：ReAct 变体 + 父侧就绪
  - W：①提交 `QUERY_BOOKING`（U3 样例 query）等首轮 `INPUT_REQUIRED`；②`InteractionFlow.send("APPROVE").withMetadata("_interrupt.kind", "confirmation")` 续轮
  - T：首轮 `INPUT_REQUIRED`；续轮 `COMPLETED`；final text **硬断言**含 `AgentScopeAdapterFixtures.ORDER_ID_BOOK_PREFIX`（`"BK-"`——book_hotel tool 真调订单号前缀）
  - PASS：两轮状态 + `BK-` 硬断言均绿；FAIL：任一未过

- **I1** `AgentScopeAdapterConfirmationInterruptTest#confirmationRejectMapsToConfirmResult`
  - G/W 同 H3 但续轮送 `REJECT`
  - T：续轮 `COMPLETED`；final text 含 `AgentScopeAdapterFixtures.REJECT_SEMANTICS_KEYWORDS` 之一（`取消/已取消/撤销/cancel`，弱断言）；**不含** `BK-`（硬断言）
  - PASS：终态 + 弱断言 + 硬断言均绿

- **I2** ⭐ `AgentScopeAdapterConfirmationInterruptTest#naturalLanguageConfirmMustNotComplete`（P0 必须绿）
  - G/W 同 H3 但续轮 metadata **不带** `_interrupt.kind`（模拟自然语言"确认"）
  - T：续轮终态 **≠** `COMPLETED`（§5.1.5 明说不承诺自然语言确认；FAILED/仍 INPUT_REQUIRED/CANCELED 均可接受）
  - PASS：终态非 COMPLETED；FAIL：终态 COMPLETED（违约）

- **I3** `AgentScopeAdapterConfirmationInterruptTest#interruptMarkerShapeCarriesToolCallIdAndToolName`
  - G/W：等首轮 `INPUT_REQUIRED`，`A2aServiceClient.getTask(taskId)` 读 Task 快照
  - T：快照 `metadata._interrupt.message` 非空；`items[]` 大小 ≥ 1；每项含 `toolCallId` 非空 + `toolName` 非空且含 `hotel`（§2 marker 完整性契约）
  - PASS：三项 shape 断言均绿

### 3.3 tool_result 侧（T1/T2/T3）

- **T1** `AgentScopeAdapterToolResultInterruptTest#toolResultInterruptMarkerCarriesNameAndArguments`
  - G：Harness 变体就绪（`assumeHarnessReady`）；**直连不经父侧**
  - W：提交 `AgentScopeAdapterFixtures.QUERY_CRM_LOOKUP`；等首轮 `INPUT_REQUIRED`，`getTask` 读快照
  - T：`_interrupt.items[]` 大小 ≥ 1；每项 `toolName` 含 `AgentScopeAdapterFixtures.EXTERNAL_TOOL_NAME_KEYWORD`（`"lookup_customer_profile"`）；含参数信息（`arguments`/`args`/`message` 之一非空——不断言 `items[].toolCallId` 语义，§2 明说"不得暴露内部 tool-call ID"）
  - PASS：三项 shape 断言均绿

- **T2** ⭐ `AgentScopeAdapterToolResultInterruptTest#externalResultResumeReachesCompleted`（P0 必须绿）
  - G/W：同 T1 → 续轮送 `AgentScopeAdapterFixtures.EXTERNAL_TOOL_RESULT_TEXT`（`"VIP 铂金,月度差旅上限 3000 元,偏好品牌:全季/亚朵"`）+ `_interrupt.kind=tool_result`
  - T：续轮 `COMPLETED`；final text **硬断言**含 `AgentScopeAdapterFixtures.CRM_RESULT_KEYWORDS` 之一（`VIP/铂金/3000/全季/亚朵`——证 ToolResultBlock 恢复生效，LLM 无外部结果输入无法编造）
  - PASS：终态 + 硬断言均绿

- **T3** ⭐ `AgentScopeAdapterToolResultInterruptTest#naturalLanguageResultMustNotComplete`（P0 必须绿）
  - G/W：同 T2 但续轮 metadata **不带** `_interrupt.kind`
  - T：终态 **≠** `COMPLETED`（§5.1.5 tool_result 侧对称边界）
  - PASS：终态非 COMPLETED；FAIL：COMPLETED

### 3.4 可靠性（R1/R2，`@Tag("manual")`）

- **R1** `AgentScopeAdapterReliabilityTest#hotelUnreachable_ParentFails`
  - G：父侧就绪 + ReAct 变体**未启动**（外部关停或从未起）；`assumeTripReadyAndHotelUnreachable`
  - W：向父侧提交 `QUERY_BOOKING`（U3）
  - T：父任务收敛到 `FAILED`/等义（`REJECTED`/`CANCELED` 均可，只要非 COMPLETED）；final text 含 `AgentScopeAdapterFixtures.EXCEPTION_KEYWORDS` 之一（`connect refused/unreachable/无法连接/超时` 等异常关键词，§5.1.5 异常因果链软断言）
  - PASS：终态 + 弱断言均绿

- **R2** ⭐ `AgentScopeAdapterReliabilityTest#hotelDiesInInterruptState_MustNotComplete`（P0 必须绿）
  - G：两 jar 就绪
  - W：①H3 到 `INPUT_REQUIRED`；②**测试外**关停 ReAct 变体（`Stop-Process` 或 taskkill）；③续轮 `APPROVE`+`kind=confirmation`
  - T：续轮终态 ∈ `{FAILED, CANCELED, REJECTED}`（**不得 COMPLETED**，§5.1.2 + §5.1.6 MUST）；SSE 无伪造正常收尾；final text 含 `EXCEPTION_KEYWORDS`
  - PASS：终态非 COMPLETED + 弱断言均绿；FAIL：COMPLETED（违约）

### 3.5 ⏸/🚧 待落地（M1/K1/K2/K3）

- **M1** ⏸ `message` 空消息续跑 —— 当前 SUT `agentscope-hotel*` 未演示 message stop skill；SUT 补 skill 后可测
- **K1/K2/K3** 🚧 协作式取消 + 幂等 + 不承诺立即中断底层 LLM —— 依赖 runtime 取消面实现，与 FEAT-002 workflow 档同步启用

## 4. 运行方式

```bash
# ReAct 场景全绿（含父侧；先启 travel-trip + agentscope-hotel）
./mvnw -Dtest.env=local -Dtest='AgentScopeAdapterHappyPathTest,AgentScopeAdapterConfirmationInterruptTest' test

# Harness 场景全绿（直连，无需父侧；只启 agentscope-hotel-harness）
./mvnw -Dtest.env=local -Dtest='AgentScopeAdapterToolResultInterruptTest' test

# R 组（先外部关停 ReAct 变体样例 agent；manual 组）
./mvnw -Dtest.env=local -Dtest='AgentScopeAdapterReliabilityTest' -Dgroups='manual' test
```

## 5. 真机实测进展（滚动记录）

**当前状态**：本细档 2026-08-26 首次重建（因误删补偿），全部 11 条 ⬜ 用例待建；11 条 @Test 方法与 4 个测试类骨架同步落地。首轮真机后按 P0 全绿标准过账。

**首轮真机的关键使命**：
- `H3` 验证 confirmation APPROVE 完整闭环 + `BK-` 前缀 tool 真调证据
- `T2` 验证 tool_result 外部结果续轮 + `VIP/铂金/...` 关键字段 ToolResultBlock 生效
- `I2/T3` 验证 §5.1.5 不承诺自然语言确认边界（两侧对称看守）
- `R2` 验证 §5.1.2 中断态被杀不得伪造 COMPLETED

## 6. 风险与备注

1. **remote url-only 无法主动关停变体 agent**：R1/R2 需外部关停，`@Tag("manual")` 前置门；切 managed 后 `SutStack.stop(...)` 可用
2. **Harness 变体端口配置**：默认 18121，可通过 `AGENTSCOPE_HOTEL_HARNESS_PORT` 环境变量覆盖
3. **`message` 类未覆盖**：feature §2 MUST；当前 SUT 未演示 message stop，M1 ⏸ deferred；补 SUT skill 后可测
4. **AgentScope 内部 tool-call ID 保护无法黑盒验证**：§2 明说"不得暴露"；SIT 无法区分 `items[].toolCallId` 是内部原样透传还是桥接分配，归 component 层。本档 T1 只断言 `toolName` + 参数信息，**不断言 `items[].toolCallId` 语义**
5. **LLM 抖动导致弱断言漏检**：三条 query 与 CRM query 都作了确定性引导；硬断言（终态、`BK-` / CRM 结果关键字段）不放行
6. **tool 真调证据可靠性**：若 SUT 侧未真调 tool（如 stub 化），二值证据断言会误报；定期校对 README 与 wire 抓包
7. **`_interrupt` 字段名演进**：T1/I3 断言 items 结构；若 SDK 演进字段名需同步更新
8. **LLM 凭据管理**：环境变量注入；凭据不落 yml/代码
