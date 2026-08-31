---
用例编号: FEAT-027-nested-delegation-passthrough
测试标题: travel 链二跳（mainplan→trip→hotel）delegation 由调用方 Runtime 生成挂父节点、hotel 深层标签两跳透传不失真
story: ra.nested-delegation-passthrough
优先级: P1
自动化状态: READY（自含 oracle 落 TravelNestedDelegationTreeRemoteStreamingTest，buildStack 注入两跳远程流式【机制已实证：run-20260817-115508 客户端流 866 agentEvent、嵌套树完整可见】；默认配置面反向见证（全 sync 边 0 agentEvent）已并入 StreamingTravelPlanningTest#defaultRemoteEdgesProjectNoAgentEvents，见特性档 §3.1-2；根因已定性——门禁错挂 serve 模式，见风险与备注与缺陷档）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-08-16
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, feat-027, a2a, streaming, nested, travel]
---

# FEAT-027-nested-delegation-passthrough — 二跳 delegation 生成/透传与深层标签不失真

> **机制一句话**：多层嵌套链 `mainplan → trip → hotel` 上，trip 的 runtime 首次获知下游 hotel
> taskId 时生成一次 (trip→hotel) delegation（FEAT-027 §5.1：delegation 由**调用方** Runtime
> 生成、source=父），该事件与 hotel 的 output 经 mainplan 的 runtime **透传不改写**（§5.2/§5.4）
> 到达客户端流——hotel 挂在 **trip 节点下**（source.taskId=trip 的 taskId，不挂根），树深 3、
> 三层节点互异；hotel 的 output 事件经两跳转发后生产者标签仍是 hotel 自身四元组（深层标签不失真）。
> 本用例是特性级设计 [FEAT-027-standard-streaming-response-data-protocol](../FEAT-027-standard-streaming-response-data-protocol.md) 的 S1。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 每跳 runtime 生成各自 delegation；上游 runtime 透传下游的 delegation/output 事件并保留生产者标签（透传不失真是本用例真正盯的机制面） |
| **载体层 · agent-solution** | 机制触发载体 | travel-demo 三 agent 链：`mainplan`（dispatch_travel_plan 派发 trip）→ `trip`（可派发 hotel）→ `hotel` |
| **测试数据层** | 载体 agent 的实现逻辑 | 完整指定的差旅请求（差标/品牌/偏好全给足）——固定 LLM 单轮成行且必走 trip→hotel 两跳派发 |

## 关联特性

- **FEAT-027**（spec：docs-agent-solution `develop/02-features/FEAT-027-标准流式响应数据协议.md`，v0815）：
  §2「多层嵌套适用」、§5.1 delegation 生成、§5.2/§5.4 透传不改写、§5.7 外层 Task 与实际生产者。
- 一跳树/分流**消费**语义归 FEAT-026 客户端面（parallel-transfer 载体断言落
  `PlanAgentParallelTransferStreamingTest` / `PlanAgentParallelTransferTreeRecoveryTest`，
  并在 [FEAT-027 特性档 §3.2](../FEAT-027-standard-streaming-response-data-protocol.md) 登记为生产侧伴随证据）
  ——本用例是其**深度维度**的 runtime 生产面。
- 边模式可见性口径与 sync 投影缺陷：`docs/a2a-sync-call-agent-event-projection-defect.cn.md`（本用例默认面的缺陷 track）。

## 前置条件

1. `-Dtest.env=openjiuwen` + `LLM_API_KEY` + Docker（travel-demo mainplan/trip/hotel 三 agent）。
2. **travel-demo 制品需携带 v0815 运行时**（已证实携带——hop-1 delegation/标签线在线，校准项关闭）。
3. 驱动面走 `InteractionFlow`（与 `StreamingTravelPlanningTest` 同面）——终态判定、事件流出口与**整轮 wire 日志**（FileWireLogger r 文件）一步到位；投影仍用公开 `ConversationInteractionAdapter.agentEventOf`（与 adapter 同一线格式权威，避免每用例重写 metadata walk）。
4. 两面共用同一套 `application-openjiuwen.yml`（复用最大化）：流式差异经 buildStack 的 `.property("openjiuwen.service.a2a.remote-agents[0].streaming", "true")` 注入（Spring 命令行参数，随栈销毁，不改 yml）。**键名必须是 `streaming` 而非 `is-streaming`**——JavaBean 绑定从访问器 `isStreaming()` 派生属性名 `streaming`，`is-streaming` 被静默忽略（2026-08-17 实测）。

## 测试数据

- `COMPLETE_REQUEST`（与 `StreamingTravelPlanningTest` 同文）：出差 3 天 2 晚 + 差标/协议品牌/会议室偏好全指定——单轮 COMPLETED 且必走两跳。

## 测试步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | 经 `InteractionFlow.of(client("mainplan")).protocol(A2A_STREAM).send(COMPLETE_REQUEST).awaitState(COMPLETED).execute()`（单轮；良性关流由 flow 内部吸收） | 终态 COMPLETED、roundCount=1；整轮 wire 已落 r 文件 |
| 2 | `round.events()` 的 `InboundEvent.raw()` 逐事件 `agentEventOf` 投影 → `delegations` 建树；`round.taskId()` 定根 | 树非空；一跳（source=根）存在 |
| 3 | 找二跳（source ∈ 一跳 target 集合）+ 标签断言 | 二跳存在且不挂根；三层互异；hotel output 生产者=hotel taskId |

## 预期结果（机制断言）

### A — 二跳 delegation 存在且挂父节点
- **Given**：一跳 (mainplan→trip) delegation 已到客户端流。
- **When**：找 source.taskId ∈ 一跳 target 集合的 delegation。
- **Then**：(trip→hotel) 存在；其 source.taskId ≠ 根任务（hotel 挂 trip 下）；三层节点 taskId 互异（树深 3）。
- **PASS**：挂载正确。**FAIL**：二跳缺失（透传/投影缺陷，开 SUT 缺陷单 track，不降 oracle）/ 挂根（深层映射错）。

### B — 深层标签不失真
- **Given**：A 通过。
- **When**：检查 output 事件的生产者标签集合。
- **Then**：含 hotel 自身 taskId——hotel 输出经 trip、mainplan 两跳转发后标签仍指向 hotel 节点。
- **PASS**：标签保真。**FAIL**：hotel 输出被改贴 trip/mainplan 标签（深层叶节点在分流视图里"消失"）。

### C — 链路真实走通
- **Given**：完整指定请求。
- **When**：awaitTerminalState。
- **Then**：COMPLETED（无流错误）——业务终态前置；树/标签断言建立在其上。
- **PASS**：单轮成行。**FAIL/超时**：比透传缺失更基础的问题（LLM/栈/制品），先修夹具再校准。

## 框架落点

| 项 | 值 |
|----|----|
| 落点方法 | 自含 oracle 在 [TravelNestedDelegationTreeRemoteStreamingTest#feat027NestedDelegationAttachesToParentNode](../../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/TravelNestedDelegationTreeRemoteStreamingTest.java)（buildStack 注入 `remote-agents[0].streaming=true` 于 mainplan+trip）；默认配置面反向见证（全 sync 边 0 agentEvent）在 [StreamingTravelPlanningTest#defaultRemoteEdgesProjectNoAgentEvents](../../../src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/StreamingTravelPlanningTest.java)（方法级双挂，主注册 FEAT-004），见特性档 §3.1-2 |
| 标签 | `@Tag("story-feat-027-nested-delegation-passthrough")`；Allure `@Feature("FEAT-027: 标准流式响应数据协议")` + `@Story("ra.nested-delegation-passthrough: …")`（方法级；story = 特性档子用例 ID `FEAT-027.ra.nested-delegation-passthrough` 去特性前缀，线前缀 `ra` = react travel 载体） |
| 基类 | `BaseManagedStackTest`；栈 leaf-first：`hotel` → `trip(downstream hotel)` → `mainplan(downstream trip)`（与 StreamingTravelPlanningTest 同栈） |
| 客户端 | `InteractionFlow`（A2A_STREAM，与 StreamingTravelPlanningTest 同驱动面——不走 Conversation step-ui 驱动栈）；wire 交互日志（r 文件）由 flow 的 finally 记轮落盘，失败轮也记 |
| 提取链 | `ConversationInteractionAdapter.agentEventOf`（已公开化）→ `SseEvent` → `RemoteInvocationProbe.delegations/outputProducers` |

## 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=TravelNestedDelegationTreeRemoteStreamingTest test   # 流式注入面（自含 oracle，应绿）
./mvnw -Dtest.env=openjiuwen -Dtest=StreamingTravelPlanningTest test                     # 默认配置面反向见证（#defaultRemoteEdgesProjectNoAgentEvents 预期红；余为 FEAT-004）
```

## 覆盖追溯

| FEAT-027 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| delegation 生成（调用方 Runtime、source=父）+ 多层嵌套 | A（挂载/深度） | ✅ 已落；流式面真机实证（run-20260817-115508） |
| 透传不改写 / 深层叶节点标签保真（§5.2/§5.4） | B | ✅ 已落；流式面真机实证（同上，866 agentEvent 含 386 hotel） |
| 任意深度（>2） | 机制同构延拓（A 的结构性断言对任意深度可递归），暂无 >2 跳真实链 | ⬜ 无载体 |

## 清理策略

- 类级栈生命周期（三 agent）。

## 风险与备注

- **默认面缺陷已定性（两表现同根，oracle 不降）**：
  - **表现① hop-2 事件从未生成**（2026-08-16 首跑 run-20260816-191024 + SUT 源码）：默认配置下
    `remote-agents[].is-streaming` 缺省 false（agent-runtime-java `A2AProperties.RemoteAgentProperties`）
    且 travel-demo 两个上游 yml 均未显式设置 → `A2ARemoteAgentClient.callOutcome` 的
    `entry.isStreaming() && call.isCallerStreaming()` 判 false → 远程调用全非流式 → 中间 hop（trip）
    非流式 serve → 投影门禁 `shouldProjectEvents = batch.request.isStream()`
    （`RemoteInvocationBatchCoordinator`）关闭 → `publishDelegation` 跳过、`onArtifact/onStatus` 早退——
    trip 任务上无 trip→hotel delegation、无 hotel 标签输出（**hotel 容器日志证实二跳业务真实发生**，
    连带 GetTask 快照也恢复不出 hotel 边，与 §5.4/§5.7 冲突）。
  - **表现② sync 边三连发**（run-20260817-231200）：全 sync 链上一次 sync 调用投影
    delegation/output/status 3 个 agentEvent + ANSWER 复述。
  - **同根**：门禁错挂 **serve 模式**而非**边模式**（可见性应按边判定：sync 边对上游不透明、传递生效）。
    缺陷档 `docs/a2a-sync-call-agent-event-projection-defect.cn.md`（含混合拓扑判别建议 → 特性档 S4 用例），
    开缺陷单 track。处置：拆两面——默认面反向见证（`StreamingTravelPlanningTest#defaultRemoteEdgesProjectNoAgentEvents`，预期红）+ 流式注入面本用例（对照，应绿）。
- **流式注入面已实证**（run-20260817-115508）：注入键名修正（`streaming`，见前置条件 4）后两跳
  streaming=true，客户端流 866 agentEvent（1 mainplan delegation + 479 trip + 386 hotel），
  A/B 证据在网；绿灯固化随下轮真机复跑。
- travel-demo 制品已证实携带 v0815 运行时（hop-1 delegation/标签线在线）——制品时效校准项关闭。
- **澄清（防回流）**：`NestedRemoteInvocationRefusalTest` 是 FEAT-004 spec-⬜ watchdog——盯**同一父 Task resume 后再次远程调用**（常量全库零命中、SUT 现不拦截），与调用图深度无关，不构成本用例门禁；链式二跳今天即可构造。
- **LLM 决策固定**：prompt 全指定避免"trip 不派发 hotel"的 INCONCLUSIVE；不断言派发决策本身、透传内部实现、固定轮数。
- **一跳多候选防御**：若 mainplan 多次调 trip（多 target），A 的结构性过滤（source ∈ 一跳 target 集合）天然支持多一跳/多二跳——任一有效二跳即满足，深度断言用首个二跳的 target 作 hotel 节点。
