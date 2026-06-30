---
id: A-05
title: tasks/get 查询已完成任务
module: A — A2A 协议与通讯模型
owner: haozhizhuang
priority: P1
feature: 特性4-4
status: designed
sut: main-plan-agent
stack: mainplan（单 agent，无需 trip/hotel）
tags: [component, smoke]
depends_on:
  - mainplan fat jar 可构建并安装至本地 Maven 仓库
  - mainplan 启动时已配置 LLM（见 §6；由测试人员准备，用例代码不门禁）
smoke_scope: 纳入 SmokeTestSuite（@Tag("smoke")）；执行前由测试人员保证 LLM 与 mainplan 就绪
---

# A-05 — tasks/get 查询已完成任务

> **一句话**：在托管栈拉起的 mainplan 上，先经同步 `message/send` 完成一次问候对话并拿到终态 Task，
> 再对同一 `taskId` 调用 `tasks/get`，验证查询结果与 send 当场返回的快照一致。
>
> 本用例验证 **特性 4-4（tasks/get）** 的外部可观测行为。
> send 步骤与 SIT 计划中 A-03 场景等价（「你好」→ `COMPLETED`），但 **自包含执行**，不依赖 A-03 用例必须先跑过。

---

## 1. 场景目标

验证 A2A 任务查询路径在 mainplan 上可端到端工作：

1. 通过同步 `message/send`（栈 `streaming(false)`）发送用户文本，任务到达终态 `COMPLETED`；
2. 从 send 响应中捕获 `taskId` 与终态 Task 快照（send 侧）；
3. 对同一 `taskId` 调用 `getTask`（tasks/get），得到查询侧 Task 快照；
4. 查询结果的 `taskId`、`state` 与 send 侧一致，且经同一 `textOf()` 抽取的文本 **严格相等**。

**本用例不覆盖**（由其他用例负责）：

- `message/stream` 流式路径（→ A-04）
- 任务取消（→ A-06）
- 对 `WORKING` 中间态轮询直至完成（异步提交场景；本用例只测 **已完成任务** 的 get 快照）
- `getTask(taskId, historyLength)` 变体、`ListTasks`（后续扩展）
- send 与 get 之间的长时间延迟 / 任务过期（不在 v0.1.0 范围）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-travel-mainplan-a2a` fat jar 已构建并可在 `~/.m2` 解析 | 托管栈启动失败 → **FAIL** |
| 2 | 测试框架按 per-class 粒度拉起 **仅 mainplan**（随机端口） | 就绪探针失败 → **FAIL** |
| 3 | 栈配置 `streaming(false)`，客户端走同步 `message/send` | 若误配 `streaming(true)` → 偏离本用例范围，**FAIL** |
| 4 | mainplan 进程已具备可用 LLM 配置（`LLM_*` 环境变量、`sut.java.system-properties` 等，见 §6） | 由测试人员启动前准备；缺失时 send 可能 **FAIL**（超时、终态 `FAILED`、空文本等） |
| 5 | **无需** trip / hotel / 远程 agent 链路 | — |

> LLM 为**运行前置**，但**不在测试类内**做环境变量门禁或 `@EnabledIf` 跳过；凭据与 profile 由执行测试的人员在拉起 agent 前配置。

---

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 拉起 mainplan（per-class，`streaming(false)`） | `SutStack` | 就绪探针通过；LLM 由启动环境注入（见 §6） |
| 2 | 从 testdata 读取 `inputText`（默认「你好」） | — | 非空字符串 |
| 3 | 发起同步 `sendMessage`，经 `A2aEventCollector` 等待终态 | A2A JSON-RPC `message/send` | 终态 `COMPLETED`；捕获 send 侧 `taskId` 与终态 Task |
| 4 | 立即调用 `getTask(taskId)` | A2A JSON-RPC `tasks/get` | 返回非空 Task；无 transport / JSON-RPC 致命错误 |
| 5 | 对比 send 快照与 get 快照 | 见 §5 | 满足 A-05.A～D |
| 6 | 记录 send 侧事件数（可选观测） | — | 仅写入日志，不影响主 verdict |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 A2A SDK / JSON-RPC 观测，不读 SUT 进程内配置、类、日志文件。
> 三态语义同 [PHILOSOPHY.md](../../PHILOSOPHY.md)（若文件尚未落地，按 PASS / FAIL 二元处理）。

### A-05.0 — 同步传输门禁（栈）

- **Given**：mainplan 已就绪。
- **When**：检查本测试类 `buildStack` 是否设置 `streaming(false)`。
- **Then**：客户端 `ClientConfig.streaming == false`（即 `message/send` 路径）。
- **PASS**：为 false。**FAIL**：为 true 或未显式配置且默认不符合预期。**INCONCLUSIVE**：不适用。

### A-05.A — send 路径终态可达

- **Given**：A-05.0 已通过；mainplan 已按 §6 配置 LLM。
- **When**：对 testdata 中的 `inputText` 发起同步 `sendMessage`，在 `sendTimeoutMs` 内等待终态。
- **Then**：
  - 收到含非空 `taskId` 的终态 Task；
  - 终态为 `TASK_STATE_COMPLETED`；
  - 若终态为 `FAILED` / `CANCELED` 等非预期 → **FAIL**。
- **PASS**：`COMPLETED` 且 `taskId` 非空。**FAIL**：超时、无 taskId、终态非 `COMPLETED`、或 LLM 不可用导致 `FAILED`。**INCONCLUSIVE**：不适用。

### A-05.B — getTask 通道可达

- **Given**：A-05.A 已获得 `taskId`。
- **When**：立即调用 `client.getTask(taskId)`（不轮询、不重试）。
- **Then**：返回非空 Task；调用过程无未捕获 transport / JSON-RPC 致命错误。
- **PASS**：满足上述条件。**FAIL**：返回 null、抛错、或 JSON-RPC error。**INCONCLUSIVE**：不适用。

### A-05.C — 结构化字段一致

- **Given**：A-05.A 的 send 快照与 A-05.B 的 get 快照。
- **When**：分别读取 `id()` 与 `status().state()`。
- **Then**：
  - `getTask.id()` == send 侧 `taskId`；
  - 两边 `state` 均为 `TASK_STATE_COMPLETED`。
- **PASS**：taskId 一致且均为 `COMPLETED`。**FAIL**：id 不一致或 state 不匹配。**INCONCLUSIVE**：不适用。

### A-05.D — 终态文本严格一致

- **Given**：A-05.C 已通过。
- **When**：对 send 快照与 get 快照分别调用 **同一** `textOf(Task)`（见 §5.3）抽取可读文本。
- **Then**：两边 trim 后非空，且 **逐字相等**（`equals`）。
- **PASS**：非空且相等。**FAIL**：任一侧为空、或文本不等（表明 get 读到的快照与 send 返回不一致）。**INCONCLUSIVE**：不适用。

### A-05.E — send 事件数观测（不改变主 verdict）

- **Given**：A-05.A 已 PASS。
- **When**：统计 `A2aEventCollector` 收到的事件条数。
- **Then**：写入测试日志 `send_event_count=<n>`（同步路径通常为少量事件；具体条数不断言）。
- **PASS**（主判据）：A-05.D 已满足。**FAIL**：不适用本子断言。**INCONCLUSIVE**：不适用。

---

## 5. Task 快照提取与对比规则

> 实现测试类时按下列规则统一提取字段，避免在测试方法内散落解析逻辑。
> 建议将 `textOf(Task)` 抽为共享 helper（与 A-04 / A-07 对齐，供后续 A-06 复用）。

### 5.1 send 侧快照来源

同步 `message/send` 阻塞返回后，从 `A2aEventCollector` 取 **终态事件** 中的 Task：

1. `collector.awaitTerminalState(sendTimeoutMs)` 确认终态；
2. `collector.findTerminalEvent().flatMap(taskFrom)` 得到 send 侧 Task；
3. `taskId = sendTask.id()`。

**禁止**仅用 `sendMessage(String)`  Convenience 方法取 taskId 而丢弃终态 Task——该方法只保证拿到 id，不保证终态快照可用于 A-05.D 对比。

可选等价路径：`InteractionFlow.of(client).send(...).awaitState(COMPLETED).execute()` 取得 `taskId` 后，send 侧文本仍须从 collector 终态事件或 **首次** get 之前的 send 响应 Task 取得；若 Flow 内部已调用 `getTask` 做断言，须区分「send 响应」与「get 查询」两次读取，避免测成同一来源。

**推荐实现**：单测方法内显式 `A2aEventCollector` + `sendMessage(message, consumers, ...)`，语义最清晰。

### 5.2 get 侧快照来源

send 终态确认后 **立即**（无 sleep、无 `WaitUtils.pollUntilTerminal`）调用：

```java
Task queried = client.getTask(taskId);
```

本用例验证的是 **已完成任务的查询一致性**，不是异步轮询能力。

### 5.3 文本抽取（`textOf`）

对 send / get 两个 Task **必须使用同一函数**，优先级与 A-04 一致：

1. `task.artifacts()` 内所有 `TextPart` 拼接；
2. 若为空，回退 `task.status().message().parts()`；
3. 若仍为空，回退 `task.history()` 最后一条 message 的 parts；
4. 结果 `trim()`。

### 5.4 超时

| 阶段 | 上限 | 来源 |
|------|------|------|
| send 等待终态 | `sendTimeoutMs` | testdata |
| getTask 单次调用 | SDK / HTTP 默认超时 | 框架默认；异常即 A-05.B **FAIL** |

### 5.5 与 A-03 / A-04 / A-06 的边界

| 用例 | 本用例关系 |
|------|-----------|
| A-03（计划） | 同输入「你好」、同 sync send、同 `COMPLETED` 期望；A-05 **自包含** send 步骤，不硬依赖 A-03 用例或文档 |
| A-04 | A-04 走 `streaming(true)` + SSE；A-05 **必须** `streaming(false)` + `getTask` |
| A-06 | A-06 测 cancel + get 观测 `CANCELED`；A-05 只测已完成任务 |

---

## 6. LLM 配置（测试人员准备，用例不门禁）

A-05 **不在测试代码中**读取 `SIT_LLM_API_KEY`、不使用 `@EnabledIf` 按 profile 跳过。执行前由测试人员保证 mainplan 能调用 LLM，方式与 A-04 / `StreamingTravelPlanningTest` 对齐：

| 方式 | 说明 |
|------|------|
| 测试 JVM 环境变量 `LLM_*` | agent bundled yaml 使用 `${LLM_*}`；`ProcessLauncher` 将测试进程环境传给子进程 |
| `application-<env>.yml` 中 `sut.java.system-properties` | 以 JVM `-D` 注入每个 launched agent（含 `LLM_API_KEY` 等） |
| 其他 Spring Boot 启动参数 / agent 自带配置 | 由 SUT 解析；**本用例不断言映射实现** |

常用变量（按实际 SUT 与网关要求配置，至少需能完成问候类单轮对话）：

| 变量名 | 典型必填 | 说明 |
|--------|----------|------|
| `LLM_API_KEY` | 是 | LLM 服务 API Key |
| `LLM_API_BASE` | 视环境 | API 基址 |
| `LLM_MODEL` | 视环境 | 模型名 |
| `LLM_PROVIDER` | 视环境 | 提供方标识 |

未配置或配置错误时，不在测试侧提前 FAIL；表现为 A-05.A（终态非 `COMPLETED`）、A-05.B（get 失败）或 A-05.D（空文本 / 不一致）等断言失败。

### 6.1 运行时探针（步骤 3～5）

send 完成后：

- 若终态为 `TASK_STATE_FAILED`，且错误信息表明上游 LLM 不可用 → **FAIL**（凭据或服务问题）。
- 若终态为 `COMPLETED` 且 A-05.D 文本非空且一致 → 视为 LLM 探针 **PASS**。

---

## 7. 测试数据

文件：`src/test/resources/testdata/component/protocol/a05-get-completed-hello.json`

```json
{
  "_doc": "A-05 tasks/get — sync send then query completed task",
  "inputText": "你好",
  "sendTimeoutMs": 60000,
  "expectedTerminalState": "TASK_STATE_COMPLETED"
}
```

| 字段 | 用途 |
|------|------|
| `inputText` | 同步 send 的用户消息（与 SIT 计划 A-03 一致） |
| `sendTimeoutMs` | 等待 send 终态上限（毫秒） |
| `expectedTerminalState` | send 路径期望终态 |

读取方式：新建 `TaskGetCompletedScenarioData` record + `TestDataLoader`，与 A-04 同模式；测试方法不硬编码输入文案。

---

## 8. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskGetTest.java` |
| 标签 | `@Tag("component")` `@Tag("smoke")` |
| 基类 | `BaseManagedStackTest` |
| 栈描述 | `SutStack.builder(config).streaming(false).agent("mainplan")` — **仅 mainplan** |
| 客户端 | `client("mainplan")`；**必须** `streaming(false)` → 同步 `message/send` |
| send 收集 | `A2aEventCollector` + `sendMessage(message, consumers, errorHandler)` |
| 查询 | `client.getTask(taskId)` — JSON-RPC `tasks/get` |
| 断言 | AssertJ；A-05.C taskId/state；A-05.D `textOf` 严格相等 |
| 数据 | `testdata/component/protocol/a05-get-completed-hello.json` |
| smoke 范围 | `@Tag("smoke")`；无 `@EnabledIf`；执行前由测试人员配置 LLM（§6） |

**实现检查清单**（写测试类时逐项落实）：

- [ ] `buildStack` 显式 `.streaming(false)`
- [ ] send 使用 collector 捕获终态 Task，不单用 `sendMessage(String)` 取 id
- [ ] send 终态后立即 `getTask`，不轮询
- [ ] send / get 共用同一 `textOf(Task)` helper
- [ ] 从 testdata 读取 `inputText` 与 `sendTimeoutMs`
- [ ] A-05.E：`send_event_count` 写入日志
- [ ] 不在测试类内门禁 `SIT_LLM_API_KEY` 或按 `test.env` 跳过

---

## 9. 运行方式

```bash
# 前置：构建 mainplan jar + 配置 LLM（示例：统一 LLM_* 环境变量）
export LLM_API_KEY=<your-key>
# 按需：export LLM_API_BASE=... LLM_MODEL=... LLM_PROVIDER=...

# 单类
./mvnw -Dtest=AgentTaskGetTest test

# 指定 profile（可选，影响 application-<env>.yml）
./mvnw -Dtest.env=SIT -Dtest=AgentTaskGetTest test

# smoke
./mvnw -Dtest=SmokeTestSuite test
```

> 缺 LLM 时测试仍会执行并在 send/get 断言处失败；执行前请按 §6 自行配置凭据与代理。

---

## 10. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性 4-4 tasks/get | A-05.B / C / D | ✅ |
| 特性 3-1 同步 send（前置步骤，非本用例主覆盖） | A-05.A | ⚠️ 仅作为 get 的前置，不单独申报 3-1 覆盖 |

---

## 11. 风险与备注

- **send vs get 同源误测**：若 send 侧文本也来自 `getTask` 而非 send 响应事件，A-05.D 可能假 PASS；实现须保证 send 快照来自 collector 终态事件。
- **LLM flaky**：网络或配额波动可能导致 send 终态 `FAILED`；失败时保留 collector 事件日志便于区分 A-05.A 与 A-05.B。
- **textOf 路径差异**：若 send 与 get 的 Task 结构不同（如 artifacts 仅在一侧填充），严格相等可能 FAIL——这恰是本用例要暴露的协议一致性问题；helper 必须一致，不得为通过测试而分岔抽取逻辑。
- **与 SIT 计划字面**：计划写「用 A-03 返回的 taskId」；本实现为自包含 send，语义等价，覆盖矩阵仍记 A-05 → 特性 4-4。
- **P1 纳入 smoke**：与 A-04 相同策略（无测试侧 LLM 门禁）；P1 用例在第三阶段（SIT 排期 Day 4-5）与 A-06、A-11 一并执行。
