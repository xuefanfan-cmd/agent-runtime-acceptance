---
id: DA-05-3
title: deep-research 同 contextId 两轮流式 in-memory checkpointer 记忆召回
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P0
feature: A2A 特性 4-3 流式 + langgraph in-memory checkpointer 短期记忆
status: designed
sut: deep-research-agent
stack: 单 agent（remote，url-only）+ 默认 `streaming(true)`
tags: [integration, deepagent]
depends_on:
  - deep-research 已启动并监听 :18090（默认 in-memory checkpointer，未激活 redis profile）
  - deep-research 侧 `deep_agent_task_1 already exists` bug 未复现
---

# DA-05-3 — deep-research in-memory checkpointer 跨轮召回（流式变体）

> **一句话**：与 [DA-05-1](DA-05-1-in-memory-checkpointer-recall.md) 同题、镜像 SSE 路径——
> 两轮同 `contextId` 的 `SendStreamingMessage`，turn1 存 "张三"，turn2 问姓名；
> checkpointer 应让 turn2 的**合并 artifact** 里出现 "张三"。任一轮命中 bug 标志串即 FAIL。

---

## 1. 场景目标

对 langgraph in-memory checkpointer **SSE 路径下跨轮记忆能力**做端到端验证：

1. 两轮 send 共享 `contextId`，走 SSE `message/stream`——与 DA-05-1（`streaming(false)`）形成
   sync × stream 双路径覆盖矩阵。
2. `A2aEventCollector.collectArtifactText()` 合并的 turn2 artifact 必须包含 "张三"。
   turn2 用户查询本身没有 "张三"、`inputs.messages` 只回显当轮消息、`query` 只回显当轮问句，
   出现 "张三" 就是 checkpointer 透过 SSE 路径生效的干净信号。
3. **bug 断言**：任一轮合并 artifact 命中 `deep_agent_task_1 already exists`
   / `controller task parameter error` 即 FAIL（复用 DA-02 口径）。

## 2. 前置条件

- deep-research 已启动并监听 SIT 服务器 `http://7.209.189.82:18090`；
- **未启用** `redis-checkpointer` profile（默认 in-memory）；跨进程持久化由 [DA-05-4](DA-05-4-streaming-redis-checkpointer-recall.md) 覆盖；
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中 `sut.agents.deep-research.url` 已声明；
- 无外部 Redis / MySQL 依赖。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 声明 deep-research（remote），保持默认 `streaming(true)` | `SutStack` | stack 就绪 |
| 2 | 生成 `contextId=ctx-da05-3-stream-inmem-<uuid8>` | — | 每次跑独立 |
| 3 | turn1: 流式 `sendMessage("我叫张三,请记住")` + `awaitTerminalState(240s)` | `message/stream` | COMPLETED，合并 artifact 非空、无 bug |
| 4 | turn2: 同 contextId, 流式 `sendMessage("我叫什么名字?")` + `awaitTerminalState(240s)` | `message/stream` | COMPLETED，合并 artifact 非空、无 bug |
| 5 | `collector.collectArtifactText()` for turn2 | — | 结果包含 "张三" |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-05-3.A — 两轮均达到 COMPLETED
- **Given**：deep-research 就绪；SSE 通道建立无异常（或异常在终态后发生，属良性）。
- **When**：turn1 / turn2 均 `awaitTerminalState(240s)`。
- **Then**：两轮终态均 `TASK_STATE_COMPLETED`。
- **PASS**：满足。**FAIL**：任一轮未达终态 / 超时。

### DA-05-3.B — 两轮合并 artifact 均非空且无 bug 标志
- **Given**：DA-05-3.A PASS。
- **When**：对每轮 `collector.collectArtifactText()`。
- **Then**：非空 && 不含 `deep_agent_task_1 already exists` && 不含 `controller task parameter error`。
- **PASS**：满足。**FAIL**：文本空 / 命中任一 bug 标志（此时"记忆召回"根本无从谈起）。

### DA-05-3.C — turn2 合并 artifact 复述 turn1 姓名（**核心档**）
- **Given**：DA-05-3.B PASS。
- **When**：`turn2.artifactText.contains("张三")`。
- **Then**：`true`。
- **PASS**：命中。**FAIL**：turn2 未复述——SSE 路径下 in-memory checkpointer 未生效，或 LLM
  拿到历史但未使用。

## 5. 测试数据

- 无外置数据文件；两轮用户输入固定：
  - turn1 = `"我叫张三,请记住"`
  - turn2 = `"我叫什么名字?"`
- 召回 token = `"张三"`（与 DA-05-1 保持一致，便于 sync / stream 双路径直观对比）。
- `contextId` 用 `UUID` 后缀化。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/deepagent/StreamingInMemoryCheckpointerRecallTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/deepagent/StreamingInMemoryCheckpointerRecallTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent")` |
| 基类 | `BaseManagedStackTest` |
| streaming | 默认 `streaming(true)` |
| 超时 | `ROUND_TIMEOUT_MS = 240_000` |
| 客户端 | `client("deep-research").sendMessage(...)`（两次流式） |
| 事件收集 | `A2aEventCollector` + `awaitTerminalState` + `collectArtifactText` |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `isNotBlank` / `contains("张三")` / `doesNotContain(BUG_MARKER)` |

## 7. 运行方式

```bash
./mvnw -Dtest.env=SIT -Dtest=StreamingInMemoryCheckpointerRecallTest test
```

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| langgraph in-memory checkpointer 跨轮召回（SSE 路径） | DA-05-3.C | ✅ |
| A2A 流式 send 契约 + 已知 bug 回归 | DA-05-3.A / B | ✅ |
| SSE artifact 合并（`collectArtifactText()`） + checkpoint 协同 | DA-05-3.B / C | ✅ |

## 9. 风险与备注

- **对比 DA-05-1**：DA-05-1 走同步 send 一次拿终态 task 快照并 `TaskTextExtractor.textOf(task)`；
  DA-05-3 走 SSE 用 `collectArtifactText()` 合并流式 chunk。两者共同 PASS 说明 checkpointer
  与 A2A 事件时序在 sync / stream 两侧一致。
- **SSE 清理 race**：SDK 在终态后偶发抛 `CancellationException` / `IOException`，本档沿用 DA-03/DA-06
  口径—— `terminalState.isFinal()` 时忽略 error handler 上报的异常。
- **对比 DA-06**：DA-06 也走 SSE，但验证的是 "研究报告主题" 级别的长期记忆（DeepSeek + 定价词）；
  DA-05-3 验证的是短期消息历史 (姓名回显)，颗粒度更细。
- **不测跨进程持久化**：本档只在 deep-research 单进程内跑；跨 JVM 由 [DA-05-4](DA-05-4-streaming-redis-checkpointer-recall.md) 覆盖。