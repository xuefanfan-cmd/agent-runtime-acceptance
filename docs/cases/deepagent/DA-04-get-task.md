---
id: DA-04
title: deep-research GetTask 快照与 send 侧终态一致性
module: DA — deep-research 场景（openjiuwen 变体）
owner: TBD
priority: P0
feature: A2A 特性 4-4 GetTask（`method: tasks/get`）
status: designed
sut: deep-research-agent
stack: 单 agent（remote，url-only）+ `streaming(false)`
tags: [integration, deepagent]
depends_on:
  - deep-research 已启动并监听 :18090
  - deep-research 侧 `deep_agent_task_1 already exists` bug 未复现
---

# DA-04 — deep-research GetTask 查询已完成任务

> **一句话**：走同步 `message/send` 拿到 `taskId`，再独立发一次 `tasks/get`，
> 断言两侧 task 快照的 id / contextId / state / artifact 文本完全一致，
> 且无已知 bug 标志串。

---

## 1. 场景目标

对 A2A **GetTask 查询路径**做端到端契约验证：

1. `SutStack.Builder.streaming(false)` 让 send 侧走同步 `message/send`——先拿到确定的
   `taskId` 与终态快照，再进行 GetTask 断言（这是 [deepagent测试结果.txt §4](../../../../openjiuwen-java/2012/agent-solution/common/example/deepagent测试结果.txt) 手工 curl 的口径）。
2. `A2aServiceClient.getTask(taskId)` 返回的 Task 与 send 侧 `findTerminalEvent()` 抽出的 Task
   在 `id` / `contextId` / `status().state()` / `TaskTextExtractor.textOf(task)` 上完全一致。
3. **bug 断言**：send 侧或 get 侧任一 artifact 命中 `deep_agent_task_1 already exists`
   / `controller task parameter error` 即 FAIL（复用 DA-02 口径）。

## 2. 前置条件

- deep-research 已启动并监听 SIT 服务器 `http://7.209.189.82:18090`；
- [application-sit.yml](../../../src/test/resources/application-sit.yml) 中 `sut.agents.deep-research.url` 已声明；
- 无 search agent 依赖；
- 无 LLM 密钥客户侧依赖。

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 声明 deep-research（remote），`streaming(false)` | `SutStack` | stack 就绪 |
| 2 | 生成 `contextId=ctx-da04-getTask-<uuid8>` | — | 每次跑独立 |
| 3 | 构造 `Message(role=USER, parts=[TextPart("你好,请用一句话介绍你是什么 agent")])` | — | 消息合法 |
| 4 | `client.sendMessage(...)` | A2A SDK `message/send` | 服务端返回 task |
| 5 | `collector.awaitTerminalState(240s)` | — | 终态 COMPLETED |
| 6 | 从终态事件抽出 send 侧 Task；记录 `taskId` 与 `sendText` | — | id 非空、文本非空 |
| 7 | `client.getTask(taskId)` | A2A SDK `tasks/get` | 返回 Task |
| 8 | 断言 §4 各子档 | — | — |

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

### DA-04.A — send 侧终态 COMPLETED
- **Given**：deep-research 就绪；send 无异常。
- **When**：`awaitTerminalState(240s)`。
- **Then**：终态 `TASK_STATE_COMPLETED`。
- **PASS**：满足。**FAIL**：send 侧未达 COMPLETED。

### DA-04.B — send 侧 task 快照可提取
- **Given**：DA-04.A PASS。
- **When**：`findTerminalEvent().flatMap(taskFrom)`；`taskId = sendTask.id()`。
- **Then**：taskId 非空、`TaskTextExtractor.textOf(sendTask)` 非空。
- **PASS**：满足。**FAIL**：终态事件里没有 Task / taskId 空 / 文本空。

### DA-04.C — GetTask 快照与 send 侧关键字段一致
- **Given**：DA-04.B PASS。
- **When**：`client.getTask(taskId)`；
- **Then**：`queried.id == taskId` && `queried.contextId == 发送时的 contextId` &&
  `queried.status().state() == COMPLETED`。
- **PASS**：三字段全对齐。**FAIL**：任一字段不匹配（协议漂移）。

### DA-04.D — send / get artifact 文本一致
- **Given**：DA-04.C PASS。
- **When**：`TaskTextExtractor.textOf(sendTask)` vs `TaskTextExtractor.textOf(queried)`。
- **Then**：两字符串 `isEqualTo`。
- **PASS**：一致。**FAIL**：两侧文本不同（说明 tasks/get 返回的 artifact 与 send 侧不一致）。

### DA-04.E — 两侧 artifact 均无 bug 标志串
- **Given**：DA-04.B/C 拿到的 sendText / getText。
- **When**：检查 `deep_agent_task_1 already exists` / `controller task parameter error`。
- **Then**：均不含。
- **PASS**：均不含。**FAIL**：任一命中——SUT 复现 §2 手工脚本 bug。

### DA-04.F — 查询不存在 taskId 应走 JSON-RPC 协议错误路径（**负路径看门狗**）
- **Given**：deep-research 可达；`fakeTaskId = UUID.randomUUID()`（服务端无此 task）。
- **When**：`client.getTask(fakeTaskId)`。
- **Then**：SDK 抛 `org.a2aproject.sdk.spec.TaskNotFoundError`（对应 A2A 协议 `-32001 TaskNotFound`）。
- **PASS**：抛的是 `TaskNotFoundError`。**FAIL**：抛的是 `A2AClientHTTPError`（HTTP 500）或
  其他通用 `A2AException` / `RuntimeException`——SUT 把 `TaskNotFoundError` 泄漏成 HTTP 500 servlet
  error（§4 手工脚本记录：SUT 直接把 `TaskNotFoundError` 堆栈抛到 dispatcherServlet 层，
  客户端拿到的是 Spring 通用错误 JSON，而不是 A2A 协议错误 body）。

## 5. 测试数据

- 无外置数据文件；用户输入固定为 `"你好,请用一句话介绍你是什么 agent"`（与 DA-02 保持一致，便于对比）。
- `contextId` 用 `UUID` 后缀化。

## 6. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [src/test/java/com/huawei/ascend/sit/cases/deepagent/GetTaskTest.java](../../../src/test/java/com/huawei/ascend/sit/cases/deepagent/GetTaskTest.java) |
| 标签 | `@Tag("integration") @Tag("deepagent")` |
| 基类 | `BaseManagedStackTest`（per-class 栈；deep-research remote） |
| streaming | `streaming(false)`——同步 `message/send` |
| 超时 | `SEND_TIMEOUT_MS = 240_000` |
| 客户端 | `client("deep-research").sendMessage(...)` + `client("deep-research").getTask(taskId)` |
| 事件收集 | `A2aEventCollector` + `awaitTerminalState` + `findTerminalEvent` |
| 文本抽取 | `TaskTextExtractor.textOf(task)` |
| 断言 | AssertJ：`isEqualTo(COMPLETED)` / `isEqualTo(taskId)` / `isEqualTo(sendText)` / `doesNotContain(BUG_MARKER)` / `assertThatThrownBy(...).isInstanceOf(TaskNotFoundError.class)` |

## 7. 运行方式

```bash
./mvnw -Dtest.env=SIT -Dtest=GetTaskTest test
```

## 8. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| A2A 特性 4-4 `tasks/get` 契约 | DA-04.C | ✅ |
| send / get 视图对齐（artifact 一致） | DA-04.D | ✅ |
| deep-research 已知 bug 回归看门狗 | DA-04.E | ✅ |
| A2A `tasks/get` 负路径协议契约（TaskNotFound） | DA-04.F | ✅ |

## 9. 风险与备注

- **artifact 文本一致性**：deep-research artifact 是嵌套 map（`agent_name / rounds[…]`），`TaskTextExtractor`
  会做统一序列化；tasks/get 服务端应返回同一份存储，两侧字符串应严格 equal。若出现细微差异
  （比如时间戳字段），需与 SUT 侧对齐是否要放宽为 `contains(核心输出)`。
- **不测流式 GetTask**：本档 send 走同步；流式 send 的完整事件序列已由 [DA-03](DA-03-streaming-send-message.md) 覆盖。
- **不测跨进程持久化**：GetTask 只做同进程内查询；跨 JVM 持久化由 [DA-05-2](DA-05-2-redis-checkpointer-recall.md) 覆盖。