---
id: DA-03
title: deep-research 流式 SendStreamingMessage（message/stream / SSE）
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P0
feature: A2A 特性 4-3 流式消息（`method: SendStreamingMessage`）
status: designed
sut: deep-research-agent
stack: 单 agent（remote，url-only）+ 默认 `streaming(true)`
tags: [integration, deepagent]
depends_on:
  - deep-research 已启动并监听 :18090
  - deep-research 侧 `deep_agent_task_1 already exists` bug 未复现
---

# DA-03 — deep-research 流式 SendStreamingMessage / SSE

> **一句话**：走 A2A `SendStreamingMessage` SSE 路径向 deep-research 请求一首古体诗，
> 期望完整事件序列 `SUBMITTED → WORKING → artifactUpdate(*) → COMPLETED`，
> 合并后的 artifact 文本非空且不含已知 bug 标志串。

---

## 1. 场景目标

对 A2A **流式消息路径**做端到端契约验证：

1. `SutStack.Builder` 使用默认 `streaming(true)` 让 SDK 走 SSE `message/stream`——
   与 [deepagent测试结果.txt §3](../../../../openjiuwen-java/2012/agent-solution/common/example/deepagent测试结果.txt) 手工 curl 口径一致。
2. **状态轨迹**：事件流中应至少观测到 `SUBMITTED` / `WORKING` / `COMPLETED` 三态。
3. **artifact 分片**：至少收到 1 个 `TaskArtifactUpdateEvent`；合并结果文本非空。
4. **bug 断言**：合并 artifact 中不含 `deep_agent_task_1 already exists` 与
   `controller task parameter error`——命中即用例 FAIL（与 DA-02 同口径）。

## 2. 前置条件

- deep-research 已启动并监听 SIT 服务器 `http://7.209.189.82:18090`；
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中 `sut.agents.deep-research.url` 已声明；
- 无 search agent 依赖；
- 无 LLM 密钥客户侧依赖。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 声明 deep-research（remote），保持默认 `streaming(true)` | `SutStack` | stack 就绪 |
| 2 | 生成本次 `contextId=ctx-da03-stream-<uuid8>` | — | 每次跑独立 |
| 3 | 构造 `Message(role=USER, parts=[TextPart("写一首关于秋天的四句古体诗")])` | — | 消息合法 |
| 4 | `client.sendMessage(message, consumers, errorHandler)` | A2A SDK `message/stream` | SDK 建立 SSE 通道 |
| 5 | `collector.awaitTerminalState(240s)` | — | 终态达到 |
| 6 | 从 `snapshotAllEvents()` 提取 TaskState 轨迹（TaskEvent + status-update TaskUpdateEvent） | — | 含 SUBMITTED / WORKING / COMPLETED |
| 7 | `collector.findFirstArtifactUpdate()` | — | 至少 1 个 artifact chunk |
| 8 | `collector.collectArtifactText()` | — | 非空 + 无 bug 标志 |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-03.A — 流式终态 COMPLETED
- **Given**：deep-research 就绪；SSE 通道建立无异常（或异常在终态后发生，属良性）。
- **When**：`awaitTerminalState(240s)`。
- **Then**：终态 `TASK_STATE_COMPLETED`。
- **PASS**：满足。**FAIL**：终态非 COMPLETED 或超时。

### DA-03.B — 状态轨迹完整
- **Given**：DA-03.A PASS。
- **When**：从事件流提取 `Task.status().state()` 轨迹，只看 TaskEvent + `TaskStatusUpdateEvent` 携带的更新，
  忽略 artifact-update 里回显的状态噪声。
- **Then**：轨迹 `contains(SUBMITTED)` && `contains(WORKING)` && `contains(COMPLETED)`。
- **PASS**：三态齐全。**FAIL**：任一态缺失（SUT 跳过了初始化 SUBMITTED 或没有 WORKING 中间态）。

### DA-03.C — 至少 1 个 artifactUpdate
- **Given**：DA-03.A PASS。
- **When**：`collector.findFirstArtifactUpdate()`。
- **Then**：`Optional` 非空。
- **PASS**：命中。**FAIL**：没有任何 artifact chunk 事件（说明 agent 直接跳过流式产出）。

### DA-03.D — 合并 artifact 非空且无 bug 标志
- **Given**：DA-03.C PASS。
- **When**：`collector.collectArtifactText()` 合并所有 artifact 分片。
- **Then**：非空白 && 不含 `deep_agent_task_1 already exists` && 不含 `controller task parameter error`。
- **PASS**：满足。**FAIL**：文本为空 / 命中任一 bug 标志。

## 5. 测试数据

- 无外置数据文件；用户输入固定为 `"写一首关于秋天的四句古体诗"`（与手工脚本 §3 一致）。
- `contextId` 用 `UUID` 后缀化。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingSendMessageTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/StreamingSendMessageTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent")` |
| 基类 | `BaseManagedStackTest`（per-class 栈；deep-research remote） |
| streaming | 默认 `streaming(true)`——SSE `message/stream` |
| 超时 | `SEND_TIMEOUT_MS = 240_000` |
| 客户端 | `client("deep-research").sendMessage(msg, consumers, errorHandler)` |
| 事件收集 | `A2aEventCollector` + `snapshotAllEvents` + `findFirstArtifactUpdate` + `collectArtifactText` |
| 轨迹提取 | `extractStateTrajectory` 内联工具（只看 TaskEvent + `TaskStatusUpdateEvent`） |
| 断言 | AssertJ：`contains(state)` / `isPresent()` / `isNotBlank` / `doesNotContain(BUG_MARKER)` |

## 7. 运行方式

```bash
./mvnw -Dtest.env=SIT -Dtest=StreamingSendMessageTest test
```

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| A2A 特性 4-3 SSE `message/stream` 事件时序 | DA-03.A / B | ✅ |
| 流式 artifact 分片 (`TaskArtifactUpdateEvent`) | DA-03.C | ✅ |
| task artifact 契约 + bug 回归看门狗 | DA-03.D | ✅ |

## 9. 风险与备注

- **SSE 清理 race**：SDK 在终态后偶发抛 `CancellationException` / `IOException`（B-06 已验证过口径），
  本档沿用 —— `terminalState.isFinal()` 时忽略 error handler 上报的异常，仅当未到终态还异常才 FAIL。
- **artifact chunk 数量**：不做严格数量断言，只要 `findFirstArtifactUpdate()` 命中即可；deep-research 可能
  一次性打包 1~N 个 chunk，两种都算合法 SSE。
- **不测 GetTask**：本档只测流式 send；GetTask 由 [DA-04](DA-04-get-task.md) 覆盖。
- **不测跨轮记忆**：本档仅一轮 stream；跨轮由 [DA-05-1](DA-05-1-in-memory-checkpointer-recall.md) /
  [DA-05-2](DA-05-2-redis-checkpointer-recall.md) / [DA-05-3](DA-05-3-streaming-in-memory-checkpointer-recall.md) /
  [DA-05-4](DA-05-4-streaming-redis-checkpointer-recall.md) / [DA-06](DA-06-long-term-memory-recall.md) 覆盖。