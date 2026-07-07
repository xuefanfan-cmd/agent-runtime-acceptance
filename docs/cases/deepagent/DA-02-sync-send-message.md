---
id: DA-02
title: deep-research 同步 SendMessage（message/send）
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P0
feature: A2A 特性 4-2 同步消息（`method: SendMessage`）
status: designed
sut: deep-research-agent
stack: 单 agent（remote，url-only）+ `streaming(false)`
tags: [integration, deepagent]
depends_on:
  - deep-research 已启动并监听 :18090
  - deep-research 侧 `deep_agent_task_1 already exists` bug 未复现（否则用例 FAIL——本档的存在意义之一）
---

# DA-02 — deep-research 同步 SendMessage

> **一句话**：走 A2A 同步 `message/send` 路径向 deep-research 发一句自介绍问询，期望
> 单个 task 终态为 COMPLETED 且 artifact 非空；artifact 中**不能**包含已知 bug 标志串
> `deep_agent_task_1 already exists` / `controller task parameter error`——命中即用例 FAIL。
>
> §2 手工脚本记录了两种 bug 症状，都由本档负责看门狗：
> - **variant 1**：task COMPLETED，但 artifact 里 `rounds[0].error` 含 `controller task parameter error, reason: deep_agent_task_1 already exists!`——DA-02.D 命中。
> - **variant 2**：task COMPLETED，但 `artifacts[0].parts[0].text = ""`（空字符串），agent 未产出任何内容——DA-02.C 的 `isNotBlank` 命中。

---

## 1. 场景目标

对 A2A **同步消息路径**做端到端契约验证：

1. `SutStack.Builder.streaming(false)` 让 SDK 走 `message/send`（而非 SSE `message/stream`）——
   与 [deepagent测试结果.txt §2](../../../../openjiuwen-java/2012/agent-solution/common/example/deepagent测试结果.txt) 手工 curl 口径一致。
2. task 到达 `TASK_STATE_COMPLETED`；`task.contextId` 应回显 send 时传入的值。
3. artifact 文本非空。
4. **bug 断言**：artifact 中不含 `deep_agent_task_1 already exists` 与 `controller task parameter error`——
   手工脚本 §2 已经复现该 bug（rounds[0].error 里带此串）；本档作为一个"契约存证"，只要 SUT 未修复就
   持续 FAIL，直到项目组把 bug 修掉。

## 2. 前置条件

- deep-research 已启动并监听 SIT 服务器 `http://7.209.189.82:18090`；
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中 `sut.agents.deep-research.url` 已声明；
- 无 search agent 依赖（deep-research 首轮不必真调 search）；
- 无 LLM 密钥客户侧依赖——deep-research 服务端自持 `LLM_*` 环境变量。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 声明 deep-research（remote），`streaming(false)` | `SutStack` | stack 就绪 |
| 2 | 用 `UUID` 后缀生成本次 `contextId=ctx-da02-sync-<uuid8>` | — | 每次跑独立 |
| 3 | 构造 `Message(role=USER, messageId=<uuid>, contextId=<...>, parts=[TextPart("你好,请用一句话介绍你是什么 agent")])` | — | 消息合法 |
| 4 | `client.sendMessage(message, consumers, errorHandler)` | A2A SDK `message/send` | 服务端返回 task |
| 5 | `collector.awaitTerminalState(240s)` | — | 终态非空 |
| 6 | 从终态 event 抽出 `Task` 快照 | — | id 非空、contextId 与 §2 一致 |
| 7 | 断言 §4 各子档 | — | — |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-02.A — 同步 send 到达 COMPLETED
- **Given**：deep-research 就绪；send 无异常。
- **When**：`awaitTerminalState(240s)`。
- **Then**：终态 `TASK_STATE_COMPLETED`。
- **PASS**：满足。**FAIL**：终态 FAILED / CANCELED / INPUT_REQUIRED 或超时未达终态。

### DA-02.B — task 快照结构完整
- **Given**：DA-02.A PASS。
- **When**：从终态 event 抽出 `Task`。
- **Then**：`task.id` 非空、`task.contextId == send 时的 contextId`。
- **PASS**：满足。**FAIL**：id 空 / contextId 未回显。

### DA-02.C — artifact 文本非空（**bug variant 2 看门狗**）
- **Given**：DA-02.B PASS。
- **When**：`TaskTextExtractor.textOf(task)`（artifacts → status message → last history 三级降级）。
- **Then**：结果非空白字符串。
- **PASS**：非空。**FAIL**：空——即 §2 手工脚本 **variant 2**：`artifacts[0].parts[0].text = ""`，
  task 状态是 COMPLETED 但 agent 内部提前终止、未产出任何内容。

### DA-02.D — bug 标志串缺席（**bug variant 1 看门狗**）
- **Given**：DA-02.C 抽出的文本。
- **When**：检查是否含 `deep_agent_task_1 already exists` 或 `controller task parameter error`。
- **Then**：均不含。
- **PASS**：均不含。**FAIL**：任一命中——SUT 复现 §2 **variant 1**（rounds[0].error 里带此串，
  即使 A2A 层返 COMPLETED，业务侧未真正回答）。

## 5. 测试数据

- 无外置数据文件；用户输入固定为 `"你好,请用一句话介绍你是什么 agent"`（与手工脚本 §2 一致）。
- `contextId` 用 `UUID` 后缀化，避免不同次跑之间在服务端 checkpointer 里相互串扰。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/deepagent/SyncSendMessageTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/deepagent/SyncSendMessageTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent")` |
| 基类 | `BaseManagedStackTest`（per-class 栈；deep-research remote） |
| streaming | `streaming(false)`——同步 `message/send` |
| 超时 | `SEND_TIMEOUT_MS = 240_000`（deep-research 内部含 LLM 调用，需宽松窗口） |
| 客户端 | `client("deep-research").sendMessage(msg, consumers, errorHandler)` |
| 事件收集 | `A2aEventCollector` + `awaitTerminalState` + `findTerminalEvent` |
| 文本抽取 | `TaskTextExtractor.textOf(task)` |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `isNotBlank` / `doesNotContain(BUG_MARKER)` |

## 7. 运行方式

```bash
./mvnw -Dtest.env=SIT -Dtest=SyncSendMessageTest test
```

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| A2A 特性 4-2 同步 `message/send` 路径 | DA-02.A / B | ✅ |
| task artifact 契约（业务输出可观测） | DA-02.C | ✅ |
| deep-research 已知 bug variant 1 回归看门狗（`deep_agent_task_1 already exists`） | DA-02.D | ✅ |
| deep-research 已知 bug variant 2 回归看门狗（artifact 文本空串） | DA-02.C | ✅ |

## 9. 风险与备注

- **本档在当前 SUT 版本上会 FAIL**——手工脚本 §2 已经复现 bug 标志串。这是有意的存证策略，
  不要在项目组未修复前将 DA-02.D 降级为 `@Disabled` 或 INCONCLUSIVE。
- **超时**：deep-research 首轮通常在数秒内完成，但含 LLM 调用；240s 是宽松兜底，若发生 network stall
  快速失败，日志会给出终态信息便于排查。
- **不测流式**：DA-02 只测 sync；SSE 由 [DA-03](DA-03-streaming-send-message.md) 覆盖。
- **不测记忆**：本档仅一轮 send；跨轮 checkpointer / 长期记忆由 [DA-05-1](DA-05-1-in-memory-checkpointer-recall.md) /
  [DA-05-2](DA-05-2-redis-checkpointer-recall.md) / [DA-05-3](DA-05-3-streaming-in-memory-checkpointer-recall.md) /
  [DA-05-4](DA-05-4-streaming-redis-checkpointer-recall.md) / [DA-06](DA-06-long-term-memory-recall.md) 覆盖。