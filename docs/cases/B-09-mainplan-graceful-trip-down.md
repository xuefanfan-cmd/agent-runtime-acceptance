---
id: B-09
title: trip-agent 停止时 mainplan 优雅答复
module: B — 多会话 / 多任务运行时（异常分支）
owner: TBD
priority: P1
feature: Runtime 下游不可用时的优雅降级（mainplan 对客户端的失败语义契约）
status: designed
sut: main-plan-agent
stack: mainplan + trip + hotel（三 agent 全链；**前置要求 trip 必须停止**）
tags: [degraded]
depends_on:
  - application-local.yml 已配 sut.agents.{mainplan,trip,hotel}.remote-url（远端栈预部署）
  - trip-agent 在运行测试前**必须**被停止（人工 / 运维侧操作）
  - mainplan / hotel 仍在线
  - mainplan 已注入可用 LLM 凭据（统一 LLM_* env vars）
---

# B-09 — trip-agent 停止时 mainplan 优雅答复

> **一句话**：trip-agent 停掉后，用户向 mainplan 发起完整出差请求，mainplan 不得挂死、
> 不得抛裸异常、不得幻觉式假装规划成功；必须在合理时间内给出**可读的失败答复**
> （明确失败终态，或 COMPLETED + 致歉/降级说明文本）。

---

## 1. 场景目标

验证 mainplan 在**下游 trip-agent 不可用**这一典型链路故障下的客户端契约：

1. **不挂死**：mainplan 在超时窗口内必须到达终态（不能永远 WORKING）；
2. **不漏堆栈**：返回给客户端的 artifact / status.message 必须是人话，不得包含 JVM 堆栈片段（`java.net.*` / `Caused by:` / `at io.netty.*` 等）；
3. **不幻觉**：mainplan 不得在 trip 不可达时假装规划成功并输出虚构行程；
4. **协议正确**：errorHandler 不收到非预期异常（仅允许 post-terminal SDK 清理 race 这种良性形态，与 [B-06](B-06-cross-session-user-memory.md) §6 同口径）。

与已有用例的边界：

- [A-11-1](A-11-1-concurrent-session-isolation.md) / [B-06](B-06-cross-session-user-memory.md) 验**链路全通**下的隔离 / 记忆契约；
- **B-09** 验**链路断点（trip down）**下 mainplan 对客户端的失败契约——异常分支首个用例。
  其他下游故障（hotel down / LLM down / trip 超时但不断连）由后续 B-09.x / B-10 系列覆盖。

## 2. 前置条件

- mainplan / hotel 在 application-local.yml 配置的远端地址（默认 `7.209.189.82`）正常运行；
- **trip-agent 必须被停止**（如 `systemctl stop` 或 `kill <pid>`），即 `http://<host>:13001/.well-known/agent.json` 应返回连接拒绝 / 超时；
- 测试代码会做一次 agent-card 探活——若发现 trip 仍可达，整个用例 SKIPPED（视为 INCONCLUSIVE，不当 FAIL），避免环境未就绪的误报；
- 客户端能访问 `http://<mainplan-host>:13003`。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 探活：HTTP GET `<trip-base-url>/.well-known/agent.json`（3s timeout） | 内嵌 HttpClient | 不可达（连接拒绝 / 超时） → 继续；可达 → SKIPPED |
| 2 | 加载 b09-trip-down-cases.json，含 1 条完整差旅请求 | classpath | 用例就位 |
| 3 | 拼装 message：`contextId=<sessionId>+runSuffix`、`metadata.userId=<userId>+runSuffix` | A2A SDK | runSuffix 防跨次跑残留 |
| 4 | `client(mainplan).sendMessage(...)`（同步语义；客户端 `setStreaming(false)`） | A2A SDK | mainplan 收到请求，尝试调 trip 失败 |
| 5 | `awaitTerminalState(180s)` 取终态 | `A2aEventCollector` | 在 180s 内到终态（关键：不挂死） |
| 6 | `getTask(taskId)` 取完整 Task，拼接 artifact 文本（artifacts → status.message → last history） | A2A SDK | 拿到业务输出 |
| 7 | 检查 errorHandler 是否被调用 | `AtomicReference<Throwable>` | 无 / 仅 post-terminal benign |
| 8 | 断言 B-09.A / B / C / D | — | 见 §4 |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 A2A SDK 事件流 + `getTask` 取回的 Task 观测，不读 mainplan 内部类 / 配置。
> 三态语义：PASS 满足、FAIL 违反、INCONCLUSIVE 表面不足以判定。

### B-09.A — 终态可达（不挂死）
- **Given**：测试发出 sendMessage 后 180s 内。
- **When**：`A2aEventCollector.awaitTerminalState(180_000)`。
- **Then**：`finalState != null && finalState.isFinal()`。
- **PASS**：满足。**FAIL**：超时仍未到终态（mainplan 在下游不可达时挂死，是严重客户端契约违规）。
- **INCONCLUSIVE**：探活阶段 SKIPPED 时本档不执行。

### B-09.B — 终态语义正确（明确失败 或 优雅完成）
- **Given**：B-09.A PASS。
- **When**：检查 `finalState` 和 artifact 文本。
- **Then**：满足下列任一：
  - `finalState ∈ {TASK_STATE_FAILED, TASK_STATE_REJECTED, TASK_STATE_CANCELED}`（协议层明确失败信号），**或**
  - `finalState == TASK_STATE_COMPLETED` 且 artifact 文本至少命中一个**致歉/降级关键词**（"抱歉" / "对不起" / "无法" / "暂时不可" / "不可用" / "未能" / "服务异常" / "调用失败" / "下游"）。
- **PASS**：满足。
- **FAIL**：`COMPLETED` 但 artifact 不含任何致歉/降级词（mainplan 在 trip 不可达时**幻觉式假装规划成功**，是最严重的契约违规）；或落在 `INPUT_REQUIRED` 等与故障语义不符的中间态。
- **INCONCLUSIVE**：B-09.A 已 FAIL 时本档不可判（前置缺失）。

### B-09.C — 文本可读，无堆栈泄漏
- **Given**：B-09.A PASS；artifact 文本拼接后。
- **When**：检查文本是否包含以下任一 JVM 堆栈标志：`java.net.` / `java.io.IOException` / `Caused by:` / `Exception in thread` / `at java.base/` / `at io.netty.` / `at org.springframework.` / `at reactor.`。
- **Then**：文本非空 AND 不含任何堆栈标志。
- **PASS**：满足。**FAIL**：含任一标志（mainplan 把下游异常原样抛回客户端，是协议契约违规——客户端不应见到 JVM 内部）。
- **INCONCLUSIVE**：B-09.A 已 FAIL 时本档不可判。

### B-09.D — 流层无非预期异常
- **Given**：测试结束。
- **When**：检查 errorHandler 收到的 `Throwable`。
- **Then**：`null`，**或** post-terminal SDK 清理 race（与 [B-06](B-06-cross-session-user-memory.md) §6 同口径：`finalState.isFinal()=true` 即视为良性）。
- **PASS**：满足。**FAIL**：终态前异常 / 终态后非清理 race 异常。**INCONCLUSIVE**：A 已 FAIL 时不可判。

## 5. 测试数据

- `src/test/resources/testdata/integration/travel_assistant/b09-trip-down-cases.json`：
  - 单条 `(userId, sessionId, input)`；
  - input 仍走完整差旅请求模板（同 [A-11-1](A-11-1-concurrent-session-isolation.md) §5），让 mainplan 必须经 trip 这条链路才能完成。
- 不需要 expectedKeyword——本用例不验业务输出语义层，只验失败契约。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/integration/travel_assistant/GracefulTripDownFailureTest.java` |
| 标签 | `@Tag("degraded")`——**不挂** `integration`，避免 `-P integration` 把本类拖进默认绿色套件（trip 停掉时正常用例会全红）。运行靠显式 `-Dtest=...` |
| 基类 | `BaseManagedStackTest`（per-class 栈），栈 = hotel + trip + mainplan 三 agent 全声明；当前 application-local.yml 把它们都标成 remote-url，`SutStack.start()` 仅注册地址不启动进程 |
| 前置探活 | 内嵌 `java.net.http.HttpClient` GET `<trip>/.well-known/agent.json`，3s timeout；可达 → `Assumptions.assumeFalse(...)` SKIPPED；不可达 → 继续 |
| runSuffix | UUID 后缀拼到 userId / contextId，避免与其他用例 / 跨次跑共用同一 sessionId 引起残留（同 [B-06](B-06-cross-session-user-memory.md)） |
| 客户端调用 | `client(MAINPLAN).sendMessage(message, metadata, consumers, errorHandler)`；本用例不走 `InteractionFlow`（同 B-06 / A-11-1，DSL 为多轮设计） |
| 事件收集 | `A2aEventCollector` + `awaitTerminalState(180_000)`（180s——比 A-11-1 的 120s 稍宽，给 mainplan 内部潜在重试留出余量；比 B-06 的 240s 严，避免挂死案掩盖） |
| 文本拼接 | `extractArtifactText(Task)`：artifacts → status.message → last history（与 B-06 / A-11-1 同口径） |
| 致歉/降级词表 | `抱歉 / 对不起 / 无法 / 暂时不可 / 不可用 / 未能 / 服务异常 / 调用失败 / 下游` —— 任意命中即视为 B-09.B 的"优雅完成"分支证据 |
| 堆栈标志词表 | `java.net. / java.io.IOException / Caused by: / Exception in thread / at java.base/ / at io.netty. / at org.springframework. / at reactor.` —— 任意命中 = B-09.C FAIL |
| 断言 | AssertJ：`isFinal()` / `isIn(...)` + apology 文本任一命中 / `doesNotContain(...)` |
| 数据 | `testdata/integration/travel_assistant/b09-trip-down-cases.json` |

## 7. 运行方式

```bash
# 1) 在 trip-agent 所在主机上停止服务（示例；以实际部署形态为准）
ssh deploy@7.209.189.82 'systemctl stop agent-trip-a2a'   # 或 kill <pid>

# 2) 跑 B-09（显式按类跑，绕过 -P integration 过滤）
./mvnw -Dtest=GracefulTripDownFailureTest test

# 3) 跑完后恢复 trip-agent，确保其他 integration 用例可正常跑
ssh deploy@7.209.189.82 'systemctl start agent-trip-a2a'
```

> **若忘了停 trip 就跑**：测试在第一秒会探活 trip，看到仍可达 → `Assumptions.assumeFalse` 报 SKIPPED 并提示"请先停止 trip-agent 后再跑"，不会误报 FAIL。

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 下游不可用时 runtime 优雅降级（不挂死） | B-09.A | ✅ |
| 失败契约：明确失败终态 OR 优雅完成文本 | B-09.B | ✅ |
| 协议契约：客户端不见 JVM 堆栈 | B-09.C | ✅ |
| 协议契约：流层无非预期异常 | B-09.D | ✅ |
| 与 [A-11-1](A-11-1-concurrent-session-isolation.md) / [B-06](B-06-cross-session-user-memory.md) 边界互补：A-11-1/B-06 验"全通"，B-09 验"trip 断" | B-09.A-D | 🟡 间接 |

## 9. 风险与备注

- **致歉/降级词表覆盖度**：词表为经验启发式，LLM 文案变化大。若实测 mainplan 给出文本如"由于行程规划暂时遇到问题，建议稍后重试"但词表未命中，B-09.B 会落到 FAIL 分支——**首次跑空就扩词表**，不要降级为 INCONCLUSIVE 掩盖。
- **堆栈泄漏定义边界**：词表只包含**确定的** JVM 堆栈标志。文本里偶尔出现"Exception"单词（如 LLM 解释概念）不在词表里，不会误判；但若 mainplan 把 `ConnectException: Connection refused` 字面拼到答复里，会被 `java.net.` 命中——这正是契约违规要捕获的形态。
- **超时长度选择**：180s 是 A-11-1（120s）与 B-06（240s）之间的折中。trip 不可达的 fail-fast 链路理论上几秒就该返回；180s 既给 mainplan 内部潜在重试 / LLM 兜底答复留余量，又不至于把"挂死"案掩盖成 PASS。若实测发现 180s 不够，**先排查 mainplan 重试策略是否合理**，再决定是否放宽超时。
- **trip-agent 探活方式**：HTTP GET agent-card 是黑盒判断，不依赖部署细节（systemctl / docker / bare process 都通用）。若部署用 nginx 前置且 nginx 仍在线返回 502，探活会看到"可达 + 非 200"，当前代码以 statusCode==200 才算 in-service，可正确判定"trip 实质不可用"——继续测试。
- **环境恢复纪律**：测试不负责重启 trip。手册步骤 3 必须由运维侧执行；建议在团队 runbook 加一行"跑完 B-09 必恢复 trip"，避免后续 integration 套件被拖红。
- **不覆盖**：trip-agent 部分降级（如返回 500 而非完全失联）、hotel 单独 down、LLM 不可用、网络抖动重试——这些由独立 B-09.x / B-10 系列承担。
- **并发度=1**：本用例严格单路。下游故障下的并发隔离由后续压力用例承担。