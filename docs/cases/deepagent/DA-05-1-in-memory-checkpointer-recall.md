---
id: DA-05-1
title: deep-research 同 contextId 两轮 in-memory checkpointer 记忆召回
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P0
feature: A2A + langgraph in-memory checkpointer 短期记忆
status: designed
sut: deep-research-agent
stack: 单 agent（remote，url-only）+ `streaming(false)`
tags: [integration, deepagent]
depends_on:
  - deep-research 已启动并监听 :18090（默认 in-memory checkpointer，未激活 redis profile）
  - deep-research 侧 `deep_agent_task_1 already exists` bug 未复现
---

# DA-05-1 — deep-research in-memory checkpointer 跨轮召回

> **一句话**：同一 `contextId` 内发两轮同步消息——turn1 存姓名"张三"，
> turn2 问"我叫什么名字?"；deep-research 默认 in-memory checkpointer 应让 turn2
> 的 artifact 里出现 "张三"。任一轮命中 bug 标志串即 FAIL。

---

## 1. 场景目标

对 langgraph in-memory checkpointer **单进程内跨轮记忆能力**做端到端验证：

1. 两轮 send 共享同一 `contextId`——deep-research 内部 checkpointer 会以 contextId 作为 thread 键
   聚合消息历史；turn2 应能从中读到 turn1 的 "我叫张三,请记住"。
2. `TaskTextExtractor.textOf` 抽出的 turn2 artifact 文本**必须包含** "张三"——
   turn2 用户查询本身没有 "张三"，`inputs.messages` 只回显当轮消息、`query` 只回显当轮问句，
   均不含姓名；出现 "张三" 就是"LLM 通过 checkpointer 拿到了 turn1 记忆"的干净信号，
   不是任何机械回显。
3. **bug 断言**：任一轮 artifact 命中 `deep_agent_task_1 already exists`
   / `controller task parameter error` 即 FAIL（复用 DA-02 口径）。

## 2. 前置条件

- deep-research 已启动并监听 SIT 服务器 `http://7.209.189.82:18090`；
- **未启用** `redis-checkpointer` profile（默认 in-memory）；DA-05-2 才是 Redis 场景；
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中 `sut.agents.deep-research.url` 已声明；
- 无外部 Redis / MySQL 依赖。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 声明 deep-research（remote），`streaming(false)` | `SutStack` | stack 就绪 |
| 2 | 生成本次 `contextId=ctx-da05-1-inmem-<uuid8>` | — | 每次跑独立、避免与前次跑 checkpointer 串扰 |
| 3 | turn1: `sendMessage("我叫张三,请记住")` + `awaitTerminalState(240s)` | `message/send` | COMPLETED，artifact 非空、无 bug |
| 4 | turn2: 同 contextId, `sendMessage("我叫什么名字?")` + `awaitTerminalState(240s)` | `message/send` | COMPLETED，artifact 非空、无 bug |
| 5 | 从 turn2 终态事件抽 Task，`TaskTextExtractor.textOf(task)` | — | 结果包含 "张三" |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-05-1.A — 两轮均达到 COMPLETED
- **Given**：deep-research 就绪。
- **When**：turn1 / turn2 均 `awaitTerminalState(240s)`。
- **Then**：两轮终态均 `TASK_STATE_COMPLETED`。
- **PASS**：满足。**FAIL**：任一轮未达终态 / 超时。

### DA-05-1.B — 两轮 artifact 均非空且无 bug 标志
- **Given**：DA-05-1.A PASS。
- **When**：对每轮 `TaskTextExtractor.textOf(task)`。
- **Then**：非空 && 不含 `deep_agent_task_1 already exists` && 不含 `controller task parameter error`。
- **PASS**：满足。**FAIL**：文本空 / 命中任一 bug 标志（此时"记忆召回"根本无从谈起）。

### DA-05-1.C — turn2 artifact 复述 turn1 姓名（**核心档**）
- **Given**：DA-05-1.B PASS。
- **When**：`turn2.artifactText.contains("张三")`。
- **Then**：`true`。
- **PASS**：命中。**FAIL**：turn2 未复述——in-memory checkpointer 未生效，或 LLM 拿到历史但未使用。
- **INCONCLUSIVE**：不适用（同进程 in-memory，不需要外部 store）。

## 5. 测试数据

- 无外置数据文件；两轮用户输入固定：
  - turn1 = `"我叫张三,请记住"`
  - turn2 = `"我叫什么名字?"`
- 召回 token = `"张三"`。
- `contextId` 用 `UUID` 后缀化，避免不同次跑之间在同一进程内的 checkpointer 里相互串扰。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InMemoryCheckpointerRecallTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/InMemoryCheckpointerRecallTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent")` |
| 基类 | `BaseManagedStackTest` |
| streaming | `streaming(false)` |
| 超时 | `ROUND_TIMEOUT_MS = 240_000` |
| 客户端 | `client("deep-research").sendMessage(...)`（两次） |
| 事件收集 | `A2aEventCollector` + `awaitTerminalState` + `findTerminalEvent` |
| 文本抽取 | `TaskTextExtractor.textOf(task)` |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `isNotBlank` / `contains("张三")` / `doesNotContain(BUG_MARKER)` |

## 7. 运行方式

```bash
./mvnw -Dtest.env=SIT -Dtest=InMemoryCheckpointerRecallTest test
```

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| langgraph in-memory checkpointer 同进程跨轮召回 | DA-05-1.C | ✅ |
| A2A 两轮 send 契约 + 已知 bug 回归 | DA-05-1.A / B | ✅ |

## 9. 风险与备注

- **同进程记忆**：本档只验证 deep-research 进程内 in-memory checkpointer；跨进程 / 跨 JVM 由
  [DA-05-2](DA-05-2-redis-checkpointer-recall.md) 覆盖。
- **SSE 变体**：本档走同步 send；同题 SSE 路径由 [DA-05-3](DA-05-3-streaming-in-memory-checkpointer-recall.md) 覆盖，
  两档同题异路径构成 sync × stream 双路径矩阵。
- **contextId 隔离**：每次跑用独立 uuid 后缀，避免 in-memory checkpointer 与前次跑串扰导致
  "上次的 张三 被复述"的假 PASS。
- **不测长期记忆**：本档关注短期消息历史；跨轮"研究报告"级别记忆由 [DA-06](DA-06-long-term-memory-recall.md) 覆盖。
- **对 LLM 质量的依赖**：本档隐式假设 deep-research 背后的 LLM 会按记忆内容如实复述姓名；若 LLM 换成
  hallucination 严重的模型（复述成 "李四"），本档会 FAIL——这也是它作为端到端契约的正确信号。