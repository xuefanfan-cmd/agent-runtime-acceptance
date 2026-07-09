---
id: DA-05-4
title: deep-research 流式 Redis checkpointer 跨进程记忆持久化（手工两步）
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P1
feature: A2A 特性 4-3 流式 + langgraph Redis checkpointer 跨进程记忆
status: designed
sut: deep-research-agent（`--spring.profiles.active=redis-checkpointer`）
stack: 单 agent（remote，url-only）+ 默认 `streaming(true)`
tags: [integration, deepagent, manual]
depends_on:
  - deep-research 已用 `redis-checkpointer` profile 启动并监听 :18090
  - Redis 已就绪，deep-research 可连
  - Step1 / Step2 之间由算子手工 kill + 重启 deep-research（保持同 profile）
  - deep-research 侧 `deep_agent_task_1 already exists` bug 未复现
---

# DA-05-4 — deep-research Redis checkpointer 跨进程召回（流式变体）

> **一句话**：与 [DA-05-2](DA-05-2-redis-checkpointer-recall.md) 同题、镜像 SSE 路径——
> Step1 用同 `contextId` 通过 `SendStreamingMessage` 存姓名 "薛凡凡" 到 Redis；算子手工 kill
> 并用同 `redis-checkpointer` profile 重启 deep-research 进程；Step2 用同 `contextId` 再走 SSE
> 询问姓名，期望合并 artifact 复述 "薛凡凡"。

---

## 1. 场景目标

对 langgraph Redis checkpointer **跨进程 + SSE 路径**联合可靠性做端到端验证：

1. Step1 与 Step2 共享 `contextId`（`-Dda054.contextId=<共享 id>`）；两步都走 SSE 流式路径——
   与 DA-05-2（sync）形成镜像。
2. Step1 与 Step2 之间必须发生 JVM 进程重启（`SutStack` 对 remote agent 明示不支持 stop/start，
   由算子手工完成）；本档验证 checkpoint 在**流式路径**下同样能跨 JVM 持久化。
3. Step2 合并 artifact 必须包含 `"薛凡凡"`；jvm 重启后仍能命中说明 Redis 侧 checkpoint 生效、
   SSE 事件时序未破坏 checkpoint 生命周期。
4. **bug 断言**：任一 Step 的合并 artifact 命中 `deep_agent_task_1 already exists`
   / `controller task parameter error` 即 FAIL。

## 2. 前置条件

- **算子准备**（Step1 之前）：
  - Redis 就绪（deep-research 侧 `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` 已配置）；
  - deep-research 以 `--spring.profiles.active=redis-checkpointer` 启动并监听 SIT `http://7.209.189.82:18090`。
- **算子操作**（Step1 与 Step2 之间）：
  - `kill $(cat deep-research.pid)`（或等价方式停止 jar）；
  - 用同 `redis-checkpointer` profile 重启 jar，重新监听同一端口。
- **测试执行**：`-Dda054.contextId=ctx-...` 传入共享 contextId；缺失时用例被 Assumptions 跳过。
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中 `sut.agents.deep-research.url` 已声明。

## 3. 场景步骤

| # | 动作 | 由谁 | 预期 |
|---|------|------|------|
| 1 | 算子：Redis 就绪 → 用 `redis-checkpointer` profile 启动 deep-research | 算子 | agent 监听 :18090 |
| 2 | `mvn test -Dtest.env=SIT -Dda054.contextId=<共享id> -Dtest=StreamingRedisCheckpointerRecallTest#redisStreamingStep1Store` | CI/开发 | Step1 COMPLETED，合并 artifact 无 bug |
| 3 | 算子：kill deep-research 进程 → 用同 profile 重启 jar | 算子 | agent 重新监听 :18090 |
| 4 | `mvn test -Dtest.env=SIT -Dda054.contextId=<共享id> -Dtest=StreamingRedisCheckpointerRecallTest#redisStreamingStep2Recall` | CI/开发 | Step2 COMPLETED，合并 artifact 包含 "薛凡凡" |

内部单步：每 Step 用默认 `streaming(true)` 建立 SSE 通道 + `awaitTerminalState(300s)`（比 DA-05-2 更宽松，
Redis + SSE 冷启动可能略慢）+ `collectArtifactText()` 合并 chunk。

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-05-4.A — Step1 COMPLETED 且合并 artifact 无 bug 标志
- **Given**：算子已按 §2 用 `redis-checkpointer` profile 启动 deep-research；`-Dda054.contextId` 已传入。
- **When**：流式 `sendMessage("我叫薛凡凡，请记住")` + `awaitTerminalState(300s)` + `collectArtifactText()`。
- **Then**：终态 COMPLETED；合并 artifact 不含 bug 标志。
- **PASS**：满足。**FAIL**：终态非 COMPLETED / 命中任一 bug 标志。
- **INCONCLUSIVE**：`-Dda054.contextId` 未传入 → Assumptions 跳过；agent 探活失败 → Assumptions abort。

### DA-05-4.B — Step2 COMPLETED 且合并 artifact 无 bug 标志
- **Given**：算子已 kill + 用同 profile 重启 deep-research；`-Dda054.contextId` 传入同一值。
- **When**：流式 `sendMessage("我叫什么名字？")` + `awaitTerminalState(300s)` + `collectArtifactText()`。
- **Then**：终态 COMPLETED；合并 artifact 不含 bug 标志。
- **PASS**：满足。**FAIL**：终态非 COMPLETED / 命中任一 bug 标志。
- **INCONCLUSIVE**：`-Dda054.contextId` 未传入 → Assumptions 跳过。

### DA-05-4.C — Step2 合并 artifact 复述 Step1 姓名（**核心档**）
- **Given**：DA-05-4.B PASS；contextId 与 Step1 一致。
- **When**：`step2.artifactText.contains("薛凡凡")`。
- **Then**：`true`。
- **PASS**：命中——Redis checkpointer 在 SSE 路径下也跨进程存活。
- **FAIL**：Step2 未复述——checkpoint 未持久化 / 算子未按流程重启 jar / SSE 事件时序破坏了
  checkpoint 生命周期 / redis-checkpointer profile 未激活。

## 5. 测试数据

- 无外置数据文件；两轮用户输入固定：
  - Step1 = `"我叫薛凡凡，请记住"`
  - Step2 = `"我叫什么名字？"`
- 召回 token = `"薛凡凡"`（与 DA-05-2 保持一致，便于 sync / stream 双路径直观对比）。
- `contextId` 由 `-Dda054.contextId` 传入；Step1 / Step2 必须使用同一 id。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingRedisCheckpointerRecallTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingRedisCheckpointerRecallTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent") @Tag("manual")` |
| 基类 | `BaseManagedStackTest` |
| 方法顺序 | `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` + `@Order(1)` / `@Order(2)` |
| streaming | 默认 `streaming(true)` |
| 超时 | `ROUND_TIMEOUT_MS = 300_000`（Redis + SSE 冷启动兜底） |
| 系统属性 | `-Dda054.contextId=<共享id>`（缺失时 Assumptions 跳过） |
| 探活兜底 | `Assumptions.assumeTrue(a2a.getAgentCard() != null, ...)` |
| 事件收集 | `A2aEventCollector` + `awaitTerminalState` + `collectArtifactText` |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `contains("薛凡凡")` / `doesNotContain(BUG_MARKER)` |

## 7. 运行方式

```bash
# 由算子生成一个共享 contextId
CTX=ctx-da054-$(date +%s)

# Step1：流式存姓名
./mvnw -Dtest.env=SIT -Dda054.contextId="$CTX" \
  -Dtest=StreamingRedisCheckpointerRecallTest#redisStreamingStep1Store test

# 算子：kill deep-research → 用同 redis-checkpointer profile 重启 jar
# ...

# Step2：流式验证召回
./mvnw -Dtest.env=SIT -Dda054.contextId="$CTX" \
  -Dtest=StreamingRedisCheckpointerRecallTest#redisStreamingStep2Recall test
```

> **CI 默认不跑**：`@Tag("manual")` + `-Dda054.contextId` 双门；仅算子协助窗口里运行。

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| langgraph Redis checkpointer 跨 JVM 持久化（SSE 路径） | DA-05-4.C | ✅ |
| A2A 流式 send 契约 + 已知 bug 回归 | DA-05-4.A / B | ✅ |
| 手工两步工作流的 Assumptions 兜底 | Step1 / Step2 均 assumeTrue | ✅ |

## 9. 风险与备注

- **对比 DA-05-2**：DA-05-2 走同步 send 拿终态 task 快照并 `TaskTextExtractor.textOf`；本档走 SSE
  用 `collectArtifactText()` 合并。两者同时 PASS 说明 Redis checkpointer 在 sync / stream 两侧
  同等可靠。
- **SSE 清理 race**：终态后 SDK 偶发抛异常，本档沿用 DA-05-3 / DA-06 口径—— `terminalState.isFinal()`
  时忽略。
- **无法自动化的原因**：`SutStack` 对 remote agent 不支持 stop/start；本档中间需要 jar 重启，只能靠算子。
- **secrets 保护**：Redis 密码等由 SUT 服务端环境变量持有，本档测试代码不接触任何 Redis 凭据。
- **对比 DA-06**：DA-06 也是 SSE + 跨轮记忆，但走 in-memory + 长期研究报告主题；本档走 Redis + 短期姓名，
  两者互补——DA-06 关注"记忆颗粒度"，本档关注"跨 JVM 存活"。