---
id: B-06
title: 跨 session 的用户级记忆（同 userId 召回历史推荐）
module: B — 多会话 / 多任务运行时
owner: TBD
priority: P1
feature: Runtime user-level memory（按 metadata.userId 跨 session 持久化记忆）
status: designed
sut: main-plan-agent
stack: mainplan + trip + hotel（三 agent 全链，须接通可用 LLM，且 mainplan 已开启 user-level memory）
tags: [integration]
depends_on:
  - mainplan / trip / hotel fat jar 均已构建并 install 至 ~/.m2
  - mainplan 已注入可用 LLM 凭据（统一 LLM_* env vars）
  - mainplan→trip→hotel 链路在正常场景下能互通
  - mainplan 已启用 user-level memory，按 metadata.userId 为 key 读写
---

# B-06 — 跨 session 的用户级记忆

> **一句话**：同一 `userId`、**不同** `contextId` 的两路差旅规划完成后，
> 用户在**第三 / 第四个全新 session** 询问"上次到 X 出差你给我推荐的是哪家酒店"，
> mainplan 必须能从 user-level memory 召回各自 plan 阶段实际推荐的酒店品牌；
> 两路召回**不得**交叉污染（南京路不能召回杭州路的品牌，反之亦然）。

---

## 1. 场景目标

验证 mainplan 的**跨 session 用户级 memory** 契约：

1. 同 userId 在 N 个 session 内产生的"已推荐酒店"记忆能在**新** session 被召回——验证 memory 按 `userId` 跨 session 持久化；
2. 不同 destination 维度下的记忆条目互不污染——验证 memory key 在用户维度内仍能区分上下文。

与已有用例的边界：

- [A-11-1](A-11-1-concurrent-session-isolation.md) 验**并发隔离**——不同 userId / 不同 contextId 同请求**不串扰**；
- [A-11-2](A-11-2-server-assigned-context-id.md) 验**协议层** contextId 透传契约；
- **B-06** 验**用户维度跨 session 的持久化**——同 userId 不同 session 之间**该串的串**（按用户记忆）、**不该串的不串**（按 destination 区分）。三者覆盖 session / context / memory 三层关系。

## 2. 前置条件

- main-plan-agent / trip-agent / hotel-agent 三 fat jar 均已构建并 install 至 `~/.m2`；
- mainplan 已启用 user-level memory，按 `metadata.userId` 为 key 写读；
- mainplan→trip→hotel 链路互通；LLM 凭据走 `LLM_*` env vars，三 agent 都从 `${LLM_*}` 占位符读，`ProcessLauncher` 自动透传；
- 框架按 per-class 拉起栈，自动注入 downstream URL；
- 客户端能访问 `http://localhost:<mainplanPort>`。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | leaf-first 拉起 hotel → trip → mainplan，默认 streaming（`message/stream` SSE） | `SutStack` + 就绪探针 | 三 agent 就绪 |
| 2 | 加载 b06-memory-cases.json，含 `sharedUserId` + 2 个 `(plan, recall)` 配对 | classpath | 两对用例（NJ / HZ）就位 |
| 3 | **Plan-A**（`contextId="b06-plan-A"`、`metadata.userId="b06-recall-user-001"`）：成都→南京 4 天 | A2A SDK `message/stream` | 终态 COMPLETED |
| 4 | 拼接 plan-A artifact 文本（artifacts → status.message → last history），按 §6 正则规则抽出实际推荐的 `brand-A` | — | 非空 |
| 5 | **Plan-B**（`contextId="b06-plan-B"`、**同** userId）：广州→杭州 6 天 | 同上 | COMPLETED + `brand-B` 非空 |
| 6 | **Recall-NJ**（`contextId="b06-recall-NJ"`、**同** userId）："上次到南京出差你给我推荐的是哪家酒店？" | 同上 | 任一 final，artifact 文本含 `brand-A` |
| 7 | **Recall-HZ**（`contextId="b06-recall-HZ"`、**同** userId）："上次到杭州出差你给我推荐的是哪家酒店？" | 同上 | 同上，含 `brand-B` |
| 8 | 断言 B-06.A / B / C / D | — | 见 §4 |

四路按时序**严格串行**——B-06 测的是"先写后读"，不是隔离。顺序刻意安排为 plan-A → plan-B → recall-NJ → recall-HZ：让 plan-B 在 recall-NJ 之前落地，可同时验证 plan-B **不会**冲走 NJ 维度的 memory 条目（多维度共存）。

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 A2A SDK 事件流 + `getTask` 取回的 Task 观测，不读 mainplan 内部类 / 配置。
> 三态语义：PASS 满足、FAIL 违反、INCONCLUSIVE 表面不足以判定。

### B-06.A — plan 阶段可被观测出实际推荐品牌
- **Given**：plan-A / plan-B 各自到达终态。
- **When**：拼接 artifact 文本（artifacts → status.message → last history 三级降级）→ 正则 `([一-龥]{2,5})酒店` 遍历 → 跳过停用词后取**第一个**匹配作为 brand-token。
- **Then**：`brand-A` 与 `brand-B` 均非空；plan 终态为 COMPLETED。
- **PASS**：满足。**FAIL**：任一 brand-token 为空（mainplan 没真给出酒店推荐，后续档失去观测面）。
- **INCONCLUSIVE**：plan 终态非 COMPLETED（如 LLM 异常返 INPUT_REQUIRED / FAILED）——此时业务输出缺失，按 §9 风险纪律记录。

### B-06.B — recall-NJ 命中 plan-A 推荐
- **Given**：recall-NJ artifact 文本拼接后；B-06.A PASS。
- **When**：检查文本是否包含 `brand-A` 子串。
- **Then**：包含。
- **PASS**：包含。**FAIL**：不包含（memory 未存或未召回）。
- **INCONCLUSIVE**：B-06.A 已 FAIL 时本档不可判（前置缺失）。

### B-06.C — recall-HZ 命中 plan-B 推荐
- 同 B-06.B，目标替换为 `brand-B`。

### B-06.D — 两路 recall 不交叉污染
- **Given**：`brand-A != brand-B`（若 LLM 巧合两路推同品牌，本档跳过为 INCONCLUSIVE）；B-06.B / C PASS。
- **When**：检查 recall-NJ 是否包含 `brand-B`；检查 recall-HZ 是否包含 `brand-A`。
- **Then**：均不包含。
- **PASS**：互不包含。**FAIL**：任一路含对方品牌（memory key 维度坍塌，userId 内不同 destination 串扰）。
- **INCONCLUSIVE**：`brand-A == brand-B` 时（观测面坍塌但非协议违规，由 LLM 概率事件触发）。

## 5. 测试数据

- `src/test/resources/testdata/integration/travel_assistant/b06-memory-cases.json`：
  - `sharedUserId`：4 个 session 全部共享，写入 `metadata.userId`；
  - `pairs[]`：每对含 `plan`（`sessionId / destination / input`）与 `recall`（`sessionId / input`）。
- 输入语义：参考 [A-11-1](A-11-1-concurrent-session-isolation.md) §5——完整出差请求模板（出发地 / 出发日期 / 返程日期 / 时长 / 差标 / 偏好），确保 mainplan 一次性完成、不落 INPUT_REQUIRED。
- destination 选省会 / 直辖市以保证 trip / hotel agent 可识别；两路 destination 互不为对方子串（南京 / 杭州 ✓）。
- 出发地刻意各不相同（成都 / 广州）以与 destination 区分，避免出发地名意外命中品牌抽取。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/integration/travel_assistant/CrossSessionUserMemoryTest.java` |
| 标签 | `@Tag("integration")`（依赖全链 + 真 LLM，归 integration 层，与 [A-11-1](A-11-1-concurrent-session-isolation.md) 同口径） |
| 基类 | `BaseManagedStackTest`（per-class 栈），栈 = hotel(LEAF) + trip(MIDDLE→hotel) + mainplan(ENTRY→trip) + 默认 streaming 模式 |
| 并发原语 | **无**——四路严格串行（`runPlan` 顺序：plan-A → plan-B → recall-NJ → recall-HZ） |
| 客户端调用 | `client("mainplan").sendMessage(message, metadata, consumers, errorHandler)`——**不**走 `InteractionFlow`（DSL 把每轮自动续 contextId 的逻辑封进 `executeRound`，对本用例反而屏蔽了我们要观测的 "同 userId 不同 contextId" 协议面） |
| metadata | `Map.of("userId", sharedUserId, "agentId", "main-plan-agent")`——4 路**完全相同**；不传 tenantId（runtime 落 `default`，与 [A-11-1](A-11-1-concurrent-session-isolation.md) §9 同口径） |
| `message.contextId` | 4 路**互不相同**（plan-A / plan-B / recall-NJ / recall-HZ）——session 维度分离，由 a2a-sdk 服务端落到 `RuntimeIdentity.sessionId` |
| 事件收集 | 每路独立 `A2aEventCollector` + `awaitTerminalState(120s)` |
| 文本拼接 | `extractArtifactText(Task)`：artifacts → status.message → last history（同 [A-11-1](A-11-1-concurrent-session-isolation.md)）；保证业务输出抽取面与 plan 终态形态无关 |
| 品牌抽取 | 正则 `([一-龥]{2,5})酒店`；停用词：四星级 / 五星级 / 三星级 / 商务 / 经济型 / 经济 / 快捷 / 连锁 / 推荐 / 目标 / 预订 / 预定 / 该 / 此 / 这家 / 那家。遍历所有匹配，**首个**非停用匹配 = 该路 brand-token。**不预设品牌白名单**，brand-token 是 mainplan 返回什么就记什么 |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `isNotBlank` / `contains` / `doesNotContain`；B-06.D 用 `Assumptions.assumeFalse(brandA.equals(brandB), ...)` 处理 LLM 巧合同品牌 |
| 数据 | `testdata/integration/travel_assistant/b06-memory-cases.json` |

## 7. 运行方式

```bash
# 仅本类（integration 层）
./mvnw -Dtest=CrossSessionUserMemoryTest test

# 或按层
./mvnw -P integration test
```

跑前需先 `export LLM_*`（参照 `SyncTravelPlanningTest` 头部说明），代理走
`application-local.yml` 的 `sut.java.system-properties`。

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| Runtime user-level memory（按 userId 跨 session 持久化） | B-06.B / C | ✅ |
| Memory key 内多维度共存（不同 destination 互不污染） | B-06.D | ✅ |
| 与 [A-11-1](A-11-1-concurrent-session-isolation.md) 边界互补：A-11-1 验"不应串"，B-06 验"应串" + "不该跨 destination 串" | B-06.A / B / C / D | 🟡 间接 |

## 9. 风险与备注

- **品牌抽取鲁棒性**：正则 `([一-龥]{2,5})酒店` + 停用词列表是**经验启发式**，不是协议契约。若 LLM 把推荐酒店写成 "XX 大酒店" / "XX 度假村" / 超 5 字品牌名（如 6 字以上日文 / 复合品牌），会抽空 → B-06.A FAIL。**首次跑空就扩展停用词或上限**，不要降级为 INCONCLUSIVE 掩盖问题。
- **LLM 巧合同品牌**：plan-A / plan-B 都偶发推 "全季" 时，B-06.D 落 INCONCLUSIVE 跳过——这是 LLM 输出概率事件，非 SUT 缺陷。可通过差异化 plan 输入（差标 / 偏好不同）降低概率。
- **Memory 未启用 / 配置异常**：若 mainplan 实际未开 user-level memory，B-06.B / C 会 FAIL——这正是本用例的预警价值，不可降级。
- **Memory 持久层关闭测试隔离**：若 mainplan memory 写到本地文件 / 远端 KV 且不随测试进程销毁，跨次跑可能积累脏数据；建议 `sharedUserId` 用唯一前缀（如 `b06-recall-user-001`）并周期清理，或在测试开始时显式调一次 reset（视后续 mainplan 暴露的接口而定）。
- **顺序敏感**：plan-A 必须先于 recall-NJ；plan-B 必须先于 recall-HZ。当前形态 `plan-A → plan-B → recall-NJ → recall-HZ` 顺便验证 plan-B **不会**冲走 NJ 维度的 memory 条目（多 destination 共存）。
- **Tenant 不在范围**：B-06 与 [A-11-1](A-11-1-concurrent-session-isolation.md) 同口径不传 `X-Tenant-Id`，runtime 落 `default`。多 tenant 维度的隔离由独立 B-类用例承担。
- **链路异常隔离**：mainplan → trip → hotel 任一断点均属异常场景，对应"链路不通时仍能召回历史 memory"由独立异常用例覆盖，**不**在 B-06。
- **并发度=1**：本用例严格串行。memory 在**并发写**下的隔离 / 一致性由后续 B-类压力用例承担。