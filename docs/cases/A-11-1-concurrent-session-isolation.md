---
id: A-11-1
title: 并发 Session 隔离
module: B — 多会话 / 多任务运行时
owner: TBD
priority: P0
feature: 特性2 / Runtime session 隔离 + 特性4 / A2A 协议
status: designed
sut: main-plan-agent
stack: mainplan + trip + hotel（三 agent 全链，mainplan→trip→hotel，须接通可用 LLM）
tags: [integration]
depends_on:
  - mainplan / trip / hotel fat jar 均已构建并 install 至 ~/.m2
  - mainplan 已注入可用 LLM 凭据（统一 LLM_* env vars）
  - mainplan→trip→hotel 链路在正常场景下能互通
---

# A-11-1 — 并发 Session 隔离

> **一句话**：同一进程向 mainplan 同时发起 2 个并发 A2A 请求、各自在 `message.contextId`
> 上携带不同的 sessionId，经 mainplan→trip→hotel 全链返回完整出差规划，断言两路在
> **协议字段**与**业务输出**两个层面均不串扰。
>
> 覆盖：特性 2「Runtime session（外部连续会话管理）与 Agent session 分离」
> 在并发维度下的运行时不变量；特性 4「A2A `message/stream`」在并发下的 task 隔离。

---

## 1. 场景目标

验证"不同 sessionId 独立运行、结果不串"，从弱到强分四档可观测断言：

1. 两 task 的 `task.id` 不同；
2. 两 task 的 `task.contextId` 不同，且各自在自身事件流中**保持稳定**（一个值，不漂移）；
3. 两路 A2A 事件流互不污染——A 路 collector 收到的所有 task-bearing 事件，
   其 `task.id` 严格属于 A，不包含 B 的 task.id；
4. 终态 artifact 的文本内容跟各自的输入语义一致（关键词命中），即业务语义层亦不串。

## 2. 前置条件

- main-plan-agent / trip-agent / hotel-agent 三个 fat jar 均已构建并 install 至 `~/.m2`
  （`com.huawei.ascend.examples:agent-travel-mainplan-a2a` /
   `com.huawei.ascend.examples:agent-trip-a2a` /
   `com.huawei.ascend.examples:agent-hotel-a2a`，均 `0.1.0-SNAPSHOT`）；
- mainplan 已配置可正常响应的 LLM endpoint + 凭据（用例形态为**正常场景**：A-11-1 假定 LLM 可用、
  mainplan→trip→hotel 链路互通；链路不通（trip / hotel 任一不可达、或 LLM 不可用）属异常场景，
  由后续异常类用例覆盖）；
- LLM 凭据按仓约定走 `LLM_*` env vars，三 agent 都从 `${LLM_*}` 占位符读，`ProcessLauncher` 自动透传；
- 框架按 per-class 粒度 leaf-first 拉起三 agent（随机端口，自动注入 downstream URL）；
- 客户端能访问 `http://localhost:<mainplanPort>`。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 拉起栈：leaf-first 顺序启动 hotel → trip → mainplan，自动注入 downstream URL；栈走默认 streaming 模式（`message/stream` SSE） | `SutStack` + 就绪探针 | 三 agent 均就绪；`GET /.well-known/agent.json` 返回 200 |
| 2 | 加载用例数据 a11-1-isolation-cases.json | classpath | 两个 SessionCase（A / B），各自含 `sessionId / input / expectedKeyword` |
| 3 | 用 Java 21 virtual threads + `CountDownLatch(1)` 准备两路并发任务 | — | 两路同时释放 |
| 4 | A 路：向 mainplan 发 `message/stream`，**`message.contextId="manual-session-a"`** + metadata `userId/agentId`，消息 "我是张三，计划 2026-07-01 飞北京出差 3 天，帮我订一家四星级商务酒店" | A2A SDK | mainplan→trip→hotel 串联回填，A 路 collector 持续收事件 |
| 5 | B 路：向 mainplan 发 `message/stream`，**`message.contextId="manual-session-b"`** + metadata `userId/agentId`，消息 "我是李四，计划 2026-07-10 飞上海出差 5 天，帮我订一家经济型连锁酒店" | A2A SDK | 同上 |
| 6 | 两路各自 `awaitTerminalState(120s)` | Awaitility | 各自到达终态 COMPLETED |
| 7 | 两路各自 `getTask(taskId)` 取完整 Task | A2A SDK | 用于 artifact 抽取 |
| 8 | 断言 A-11-1.A / B / C / D | — | 见 §4 |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 A2A SDK 事件流 + `getTask` 取回的 Task 观测，不读 mainplan 内部类 / 配置。
> 三态语义：PASS 满足、FAIL 违反、INCONCLUSIVE 表面不足以判定。

### A-11-1.A — task.id 唯一
- **Given**：两路并发 `message/stream` 均完成。
- **When**：从各自首个 task-bearing 事件取 `task.id`。
- **Then**：两 `task.id` 均非空、且彼此不同。
- **PASS**：满足。**FAIL**：相等或为空。**INCONCLUSIVE**：不适用（A2A 强制每次 send 产新 task）。

### A-11-1.B — contextId 各自稳定且互异
- **Given**：两路事件流；客户端在 `message.contextId` 上显式分别给定 `manual-session-a` / `manual-session-b`。
- **When**：扫描每路所有 task-bearing 事件，去重收集 `task.contextId`。
- **Then**：每路去重后**有且仅有 1 个** `contextId`（稳定不漂移）；两路的 `contextId` 不相等；且 runtime 不得篡改客户端给定的值。
- **PASS**：满足。**FAIL**：单路漂移（>1 个），或两路相等，或 runtime 把 `contextId` 改成与客户端不同的值。
- **INCONCLUSIVE**：SUT 在事件中**始终不**返回 contextId（全为空）——此时本档判定失去观测面，
  应升级到 A-11-1.C/D 上判定，不可当作 PASS。

### A-11-1.C — 两路事件流互不污染
- **Given**：A 路 collector 的所有事件 / B 路 collector 的所有事件。
- **When**：去重收集每路所有 task-bearing 事件中的 `task.id`。
- **Then**：A 路观测到的 `task.id` 集合**不包含** B 路的 `task.id`；反之亦然。
- **PASS**：互不包含。**FAIL**：任一路收到对方 task.id。**INCONCLUSIVE**：不适用。

### A-11-1.D — 终态文本关键词命中（业务语义层不串）
- **Given**：两路终态 Task 各自的输出文本（artifacts → status.message → last history message 三级降级拼接，
  参照 `SyncTravelPlanningTest.textOf`）。
- **When**：检查 A 路拼接文本是否包含 A 路 expectedKeyword；B 路同。
- **Then**：各自命中，且各自**不**含对方关键词。
- **PASS**：均命中且互不污染。**FAIL**：任一路文本不含自己的 expectedKeyword，或含有对方的 expectedKeyword。
- **INCONCLUSIVE**：终态不是 COMPLETED（如 INPUT_REQUIRED / FAILED）——此时业务输出缺失，
  业务语义层失去观测面，不当 PASS。

## 5. 测试数据

- `src/test/resources/testdata/integration/travel_assistant/a11-1-isolation-cases.json`，
  内含两个 session 用例（`sessionId / input / expectedKeyword`）。结构便于后续扩展到 N 并发。
- 输入语义：mainplan→trip→hotel 是规划型 multi-agent，输入必须是完整的**出差规划请求**——
  覆盖 *我是谁 / 计划什么时间 / 去哪个城市 / 出差几天 / 想订什么类型的酒店*。
- 关键词选择规则：取**输入里的目的地城市名**作为期望关键词——
    * 城市必须是**省会城市或直辖市**（确保 trip / hotel agent 有可识别的目的地）；
    * LLM 回答行程 + 酒店推荐时无法不提该城市，关键词命中具备稳定性；
    * 两路关键词不可互相为对方输入的子串（例：北京 / 上海 ✓；南京 / 京 ✗）。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/integration/travel_assistant/ConcurrentSessionIsolationTest.java` |
| 标签 | `@Tag("integration")`（依赖全链 + 真 LLM，归 integration 层，与 `SyncTravelPlanningTest` 同层） |
| 基类 | `BaseManagedStackTest`（per-class 管理栈），栈描述 = hotel(LEAF) + trip(MIDDLE→hotel) + mainplan(ENTRY→trip) + 默认 streaming 模式|
| 并发原语 | Java 21 `Executors.newVirtualThreadPerTaskExecutor()` + `CountDownLatch(1)` 同时起跑 |
| 客户端调用 | `client("mainplan").sendMessage(message, metadata, consumers, errorHandler)` —— **不**走 `InteractionFlow`（DSL 为串行设计）；`message.contextId` = 该路 sessionId（驱动 `RuntimeIdentity.sessionId` 派生，见 §9 contextId 路由说明） |
| 事件收集 | 每路独立 `A2aEventCollector` + `awaitTerminalState(120s)` |
| 断言 | AssertJ；contextId / taskId 集合用 `doesNotHaveDuplicates / doesNotContain`；终态文本用 `contains(expectedKeyword)` + `doesNotContain(otherKeyword)` |
| 数据 | `testdata/integration/travel_assistant/a11-1-isolation-cases.json` |

## 7. 运行方式

```bash
# 仅本类（integration 层）
./mvnw -Dtest=ConcurrentSessionIsolationTest test

# 或按层
./mvnw -P integration test
```

跑前需先 `export LLM_*`（参照 `SyncTravelPlanningTest` 头部说明），代理走
`application-local.yml` 的 `sut.java.system-properties`。

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性 2 Runtime session 隔离（并发维度） | A-11-1.B / C / D | ✅ |
| 特性 4 A2A `message/stream` 并发 task 隔离 | A-11-1.A / B / C | ✅ |

## 9. 风险与备注

- **三 agent jar 未就位**：当前 `~/.m2` 缺 `agent-travel-mainplan-a2a` / `agent-trip-a2a` /
  `agent-hotel-a2a` 任一时，本用例运行时会被 `ProcessLauncher` fail-fast。属预期：本用例所有断言
  均不可假设 SUT 在线，本类等到三个 jar 就位后即可跑。
- **LLM 凭据 / 响应能力**：A-11-1.D 假定 LLM 有响应。若 LLM 不可用导致 task 不到 COMPLETED，
  按 §4 INCONCLUSIVE 纪律记录，**不**当 PASS。无 LLM 的异常场景**不属于** A-11-1 覆盖范围，
  由后续异常类用例承担。
- **链路异常隔离**：mainplan→trip→hotel 任一断点均属异常场景，对应的"链路不通时
  不同 session 仍各自正确处理、结果不串"由独立异常用例覆盖，**不**在 A-11-1。
- **关键词非确定性**：LLM 输出有变体，但"输入目的地城市名（省会 / 直辖市）"在差旅规划中
  通常不可省略；若后续观测到漏判，将 expectedKeyword 升级为正则或同义词列表，**不**降为弱断言。
- **contextId 路由说明**：实测 `agent-runtime` v0.1.0 的 `A2aAgentExecutor.toExecutionContext`
  从 **`message.contextId`** 派生 `RuntimeIdentity.sessionId`（缺失则降级为 `taskId`），
  **不读** `metadata.sessionId`。故本用例把 sessionId 写在 `message.contextId` 上；
  `metadata` 只保留 `userId`/`agentId`（这两项才是 runtime 实际消费的键）。
  上游 L2 `a2a-protocol-and-communication-design.md` §6.2.1 的 canonical curl 示例与此一致。
  租户隔离当前不在 A-11-1 范围（B-类用例承担）；项目组确认现阶段未启用 `X-Tenant-Id`，
  本用例不传 tenantId，runtime 落 `"default"`。
- **并发度=2**：本用例的目标是**隔离性最小验证**，而非压力。压力测试由 performance 层
  `A2aConcurrencyTest`（独立用例）承担。