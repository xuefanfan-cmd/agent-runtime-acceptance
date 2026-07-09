---
id: DA-05-2
title: deep-research Redis checkpointer 跨进程记忆持久化（手工两步）
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P1
feature: A2A + langgraph Redis checkpointer 跨进程记忆
status: designed
sut: deep-research-agent（`--spring.profiles.active=redis-checkpointer`）
stack: 单 agent（remote，url-only）+ `streaming(false)`
tags: [integration, deepagent, manual]
depends_on:
  - deep-research 已用 `redis-checkpointer` profile 启动并监听 :18090
  - Redis 已就绪，deep-research 可连
  - Step1 / Step2 之间由算子手工 kill + 重启 deep-research（保持同 profile）
  - deep-research 侧 `deep_agent_task_1 already exists` bug 未复现
---

# DA-05-2 — deep-research Redis checkpointer 跨进程召回（手工两步）

> **一句话**：Step1 用同 `contextId` 存姓名"薛凡凡"到 Redis；算子手工 kill 并重启 deep-research
> 进程；Step2 用同 `contextId` 再问"我叫什么名字?"——期望 artifact 复述 "薛凡凡"，
> 证明 checkpoint 已跨 JVM 持久化到 Redis。

---

## 1. 场景目标

对 langgraph Redis checkpointer **跨进程记忆持久化能力**做端到端验证：

1. Step1 与 Step2 共享 `contextId`——由 `-Dda052.contextId=<共享 id>` 传入，
   {@link org.junit.jupiter.api.Assumptions#assumeTrue} 在缺失时跳过用例。
2. **Step1 与 Step2 之间必须发生进程重启**——`SutStack` 对 remote agent 明示不支持 stop/start，
   因此本档走"两个独立 @Test 方法 + `@Order`"模式，让算子在中间做 kill + 用同 `redis-checkpointer`
   profile 重新拉起 jar。
3. Step2 artifact 必须包含 `"薛凡凡"`——jvm 已重启，如果没有 Redis 侧持久化就绝无可能复述姓名。
4. **bug 断言**：任一 Step 的 artifact 命中 `deep_agent_task_1 already exists`
   / `controller task parameter error` 即 FAIL。

## 2. 前置条件

- **算子准备**（Step1 之前）：
  - Redis 就绪（deep-research 侧 `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` 已配置）；
  - deep-research 以 `--spring.profiles.active=redis-checkpointer` 启动并监听 SIT `http://7.209.189.82:18090`。
- **算子操作**（Step1 与 Step2 之间）：
  - `kill $(cat deep-research.pid)`（或等价方式停止 jar）；
  - 用同 `redis-checkpointer` profile 重启 jar，重新监听同一端口。
- **测试执行环境**：可通过 `-Dda052.contextId=ctx-...` 传入共享 contextId；缺失时用例被跳过（Assumptions）。
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中 `sut.agents.deep-research.url` 已声明。

## 3. 场景步骤

| # | 动作 | 由谁 | 预期 |
|---|------|------|------|
| 1 | 算子：Redis 就绪 → 用 `redis-checkpointer` profile 启动 deep-research | 算子 | agent 监听 :18090 |
| 2 | `mvn test -Dtest.env=SIT -Dda052.contextId=<共享id> -Dtest=RedisCheckpointerRecallTest#redisStep1Store` | CI/开发 | Step1 COMPLETED，artifact 无 bug |
| 3 | 算子：kill deep-research 进程 → 用同 profile 重启 jar | 算子 | agent 重新监听 :18090 |
| 4 | `mvn test -Dtest.env=SIT -Dda052.contextId=<共享id> -Dtest=RedisCheckpointerRecallTest#redisStep2Recall` | CI/开发 | Step2 COMPLETED，artifact 包含 "薛凡凡" |

内部单步：Step1 / Step2 各自的 send 遵循 DA-02 同步 send 模型（`streaming(false)` + `awaitTerminalState(240s)`）。

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-05-2.A — Step1 COMPLETED 且 artifact 无 bug 标志
- **Given**：算子已按 §2 用 `redis-checkpointer` profile 启动 deep-research；`-Dda052.contextId` 已传入。
- **When**：`sendMessage("我叫薛凡凡，请记住")` + `awaitTerminalState(240s)`。
- **Then**：终态 COMPLETED；artifact 不含 bug 标志。
- **PASS**：满足。**FAIL**：终态非 COMPLETED / 命中任一 bug 标志。
- **INCONCLUSIVE**：`-Dda052.contextId` 未传入 → Assumptions 跳过；agent 探活失败 → Assumptions abort。

### DA-05-2.B — Step2 COMPLETED 且 artifact 无 bug 标志
- **Given**：算子已 kill + 用同 profile 重启 deep-research；`-Dda052.contextId` 传入同一值。
- **When**：`sendMessage("我叫什么名字?")` + `awaitTerminalState(240s)`。
- **Then**：终态 COMPLETED；artifact 不含 bug 标志。
- **PASS**：满足。**FAIL**：终态非 COMPLETED / 命中任一 bug 标志。
- **INCONCLUSIVE**：`-Dda052.contextId` 未传入 → Assumptions 跳过。

### DA-05-2.C — Step2 artifact 复述 Step1 姓名（**核心档**）
- **Given**：DA-05-2.B PASS；contextId 与 Step1 一致。
- **When**：`step2.artifactText.contains("薛凡凡")`。
- **Then**：`true`。
- **PASS**：命中——Redis checkpointer 已跨进程存活。
- **FAIL**：Step2 未复述——checkpoint 未持久化到 Redis / 算子未按流程重启 jar / redis-checkpointer profile
  未激活。

## 5. 测试数据

- 无外置数据文件；两轮用户输入固定：
  - Step1 = `"我叫薛凡凡，请记住"`
  - Step2 = `"我叫什么名字？"`
- 召回 token = `"薛凡凡"`（生僻组合，避免 LLM 随机命中）。
- `contextId` 由 `-Dda052.contextId` 传入，Step1 / Step2 必须使用同一 id。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RedisCheckpointerRecallTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RedisCheckpointerRecallTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent") @Tag("manual")` |
| 基类 | `BaseManagedStackTest` |
| 方法顺序 | `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` + `@Order(1)` / `@Order(2)` |
| streaming | `streaming(false)` |
| 超时 | `ROUND_TIMEOUT_MS = 240_000` |
| 系统属性 | `-Dda052.contextId=<共享id>`（缺失时 Assumptions 跳过） |
| 探活兜底 | `Assumptions.assumeTrue(a2a.getAgentCard() != null, ...)` |
| 文本抽取 | `TaskTextExtractor.textOf(task)` |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `contains("薛凡凡")` / `doesNotContain(BUG_MARKER)` |

## 7. 运行方式

```bash
# 由算子生成一个共享 contextId
CTX=ctx-da052-$(date +%s)

# Step1：存姓名
./mvnw -Dtest.env=SIT -Dda052.contextId="$CTX" \
  -Dtest=RedisCheckpointerRecallTest#redisStep1Store test

# 算子：kill deep-research → 用同 redis-checkpointer profile 重启 jar
# ...

# Step2：验证召回
./mvnw -Dtest.env=SIT -Dda052.contextId="$CTX" \
  -Dtest=RedisCheckpointerRecallTest#redisStep2Recall test
```

> **CI 默认不跑**：`@Tag("manual")` 让本类不被常规 `-P integration` 拾起；只在有算子协助的手工窗口里跑。

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| langgraph Redis checkpointer 跨 JVM 持久化 | DA-05-2.C | ✅ |
| A2A 同步 send 契约 + 已知 bug 回归 | DA-05-2.A / B | ✅ |
| 手工两步工作流的 Assumptions 兜底 | Step1 / Step2 均 assumeTrue | ✅ |

## 9. 风险与备注

- **无法自动化的原因**：`SutStack` 对 remote agent 不支持 stop/start；本档中间需要 jar 重启，只能靠算子。
  未来若接入本地 build 能自动拉起，本档可迁移为单方法 + 内部重启。
- **CI 默认跳过**：`@Tag("manual")` + `assumeTrue(-Dda052.contextId)` 两道门，任一未满足即
  Assumptions 跳过而非 FAIL，防止在没有算子的 nightly 里误报。
- **secrets 保护**：Redis 密码、Redis host 等由 SUT 服务端环境变量持有，本档测试代码不接触任何
  Redis 凭据。
- **对比 DA-05-1**：DA-05-1 只验证同进程内 in-memory checkpointer；本档验证跨 JVM 存活。两者互补，
  同时 FAIL / 同时 PASS 分别对应 checkpointer 系统性问题 / 全通。
- **SSE 变体**：本档走同步 send；同题 SSE 路径由 [DA-05-4](DA-05-4-streaming-redis-checkpointer-recall.md) 覆盖。
  DA-05-1 / DA-05-2 / DA-05-3 / DA-05-4 共同构成 (in-mem × Redis) × (sync × stream) 的四象限覆盖。