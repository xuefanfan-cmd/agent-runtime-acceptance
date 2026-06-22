---
id: A-06
title: tasks/cancel 取消任务
module: A — A2A 协议与通讯模型
owner: haozhizhuang
priority: P1
feature: 特性4-5
status: designed
sut: main-plan-agent
stack: mainplan（单 agent，无需 trip/hotel）
tags: [component, smoke]
depends_on:
  - mainplan fat jar 可构建并安装至本地 Maven 仓库
  - LLM 凭据已配置（见 §6）
smoke_scope: 仅 test.env=SIT 或 test.env=UAT 且 LLM 凭据齐全时纳入 SmokeTestSuite；流式 + 同步两条子场景均纳入
---

# A-06 — tasks/cancel 取消任务

> **一句话**：在托管栈拉起的 mainplan 上，任务执行过程中调用 `tasks/cancel`，
> 验证任务终态变为 `CANCELED`，且 `cancelTask` 响应与 `getTask` 查询一致。
>
> 本用例验证 **特性 4-5（tasks/cancel）**，覆盖 **两条传输子场景**：
> **A-06-S** 流式 `message/stream`；**A-06-Y** 同步 `message/send`（后台阻塞 send + 主线程 cancel）。
> 自包含执行，不硬依赖 A-04 / A-05 用例必须先跑过。

---

## 1. 场景目标

验证 A2A 任务取消路径在 mainplan 上可端到端工作：

1. 发送 **长耗时** 用户输入，使任务在 cancel 前处于非终态（优先 `WORKING`，否则 `SUBMITTED`）；
2. 在任务未完成时调用 `cancelTask(taskId)`；
3. `cancelTask` 返回 `TASK_STATE_CANCELED`，且 `id` 与目标 `taskId` 一致；
4. 随后 `getTask(taskId)` 查询结果亦为 `CANCELED`，`taskId` 一致；
5. **流式子场景** 额外要求：SSE 事件流终态为 `CANCELED`，不得以 `COMPLETED` 收尾。

**本用例不覆盖**（由其他用例负责）：

- 已完成任务上 cancel 的行为（非法/幂等语义 → 后续边界用例）
- `ListTasks`、远程 Agent 级联取消（release features 有述，Travel 单 mainplan 栈不测）
- `SubscribeToTask` 断线重连与 cancel 交叉（→ C-10 等）
- cancel 后 Agent 进程内是否立即停止 LLM 推理（OpenJiuwen 可能仅停止结果消费；**不断言**进程内行为，只断言北向 A2A 状态）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-travel-mainplan-a2a` fat jar 已构建并可在 `~/.m2` 解析 | 托管栈启动失败 → **FAIL** |
| 2 | 测试框架按 per-class 粒度拉起 **仅 mainplan** | 就绪探针失败 → **FAIL** |
| 3 | 环境变量 `SIT_LLM_API_KEY` 已设置且非空 | **FAIL** |
| 4 | `test.env` 为 `SIT` 或 `UAT` 时纳入 smoke（见 §8） | `LOCAL` 可单类调试 |
| 5 | **无需** trip / hotel | — |
| 6 | 流式 / 同步子场景各用 **独立测试类**（`streaming` 栈级配置互斥，见 §8） | — |

> LLM 为**硬性前置**：未配置凭据即 FAIL，不使用 `Assumptions` 跳过。

---

## 3. 场景步骤（总览）

两条子场景共享 **§5 cancel 时机** 与 **§6 终态验证**；差异仅在 send 传输与并发模型。

### 3.1 A-06-S — 流式 cancel

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 0 | 检查 `SIT_LLM_API_KEY` | 测试侧 | 缺失 → **FAIL** |
| 1 | 拉起 mainplan（`streaming(true)`） | `SutStack` | 就绪 |
| 2 | 读取 testdata `inputText`（长 prompt） | — | 非空 |
| 3 | 后台/同线程发起 `sendMessage` + `A2aEventCollector` | `message/stream` | 收到 `taskId` |
| 4 | 按 §5 等待 cancel 窗口（WORKING 优先） | — | 未出现 `COMPLETED` |
| 5 | `cancelTask(taskId)` | `tasks/cancel` | 返回 `CANCELED` |
| 6 | `getTask(taskId)` | `tasks/get` | `CANCELED` |
| 7 | 检查 SSE 终态 | 流式事件 | 终态 `CANCELED`，非 `COMPLETED` |

### 3.2 A-06-Y — 同步 cancel

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 0～2 | 同 A-06-S，但栈为 `streaming(false)` | — | — |
| 3 | **后台线程**发起阻塞 `sendMessage` + collector | `message/send` | 主线程可并发观测 |
| 4 | 主线程按 §5 等待 cancel 窗口 | — | send 线程仍未返回终态 |
| 5～6 | 同 A-06-S | cancel + get | `CANCELED` |
| 7 | 等待 send 后台线程结束 | — | 不崩溃；终态观测以 cancel/get 为准 |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 A2A SDK / JSON-RPC / SSE 观测。
> 流式子场景前缀 **A-06.S.**；同步子场景前缀 **A-06.Y.**；公共门禁 **A-06.0**。

### A-06.0 — LLM 凭据门禁

- **Given**：准备执行 A-06-S 或 A-06-Y。
- **When**：读取 `SIT_LLM_API_KEY`。
- **Then**：值非空。
- **PASS** / **FAIL**：同 A-05.0。

### A-06.S.0b — 流式传输门禁

- **Given**：A-06-S。
- **When**：`buildStack` 中 `streaming(true)`。
- **Then**：客户端走 `message/stream`。
- **PASS**：为 true。**FAIL**：为 false。

### A-06.Y.0b — 同步传输门禁

- **Given**：A-06-Y。
- **When**：`buildStack` 中 `streaming(false)`。
- **Then**：客户端走 `message/send`。
- **PASS**：为 false。**FAIL**：为 true。

### A-06.*.A — cancel 窗口（send 未终态）

- **Given**：collector 已收到含 `taskId` 的事件。
- **When**：在 `cancelWaitMs` 内按 §5 策略等待 `WORKING` 或回退 `SUBMITTED`。
- **Then**：
  - cancel 发出前 **不得** 已观测到终态 `COMPLETED`（若已 `COMPLETED` → **FAIL**，记录 `cancel_window_missed=true`）；
  - 至少处于 `SUBMITTED` 或 `WORKING` 之一。
- **PASS**：在非终态下发 cancel。**FAIL**：任务已 `COMPLETED` / `FAILED` 才 cancel，或超时无 `taskId`。

### A-06.*.B — cancelTask 响应

- **Given**：A-06.*.A 已发出 cancel。
- **When**：读取 `cancelTask` 返回 Task。
- **Then**：非空；`id == taskId`；`status().state() == TASK_STATE_CANCELED`。
- **PASS**：满足。**FAIL**：null、id 不一致、state 非 `CANCELED`。

### A-06.*.C — getTask 一致性

- **Given**：A-06.*.B 已通过。
- **When**：调用 `getTask(taskId)`（允许 `WaitUtils` 短轮询至 `cancelPollMs`）。
- **Then**：`id == taskId`；`state == TASK_STATE_CANCELED`。
- **PASS**：满足。**FAIL**：不一致或超时仍为 `WORKING`/`COMPLETED`。

### A-06.S.D — 流式终态非 COMPLETED

- **Given**：A-06-S 的 collector 事件流。
- **When**：等待流结束或 `streamTimeoutMs`。
- **Then**：若存在终态事件，必须为 `CANCELED`；**不得**以 `COMPLETED` 作为最后任务状态。
- **PASS**：终态为 `CANCELED` 或流在 cancel 后关闭且未再推送 `COMPLETED`。**FAIL**：终态 `COMPLETED`。

### A-06.*.E — cancel 触发点观测（不改变主 verdict）

- **Given**：A-06.*.A 已 PASS。
- **When**：记录 cancel 发出时最近观测到的状态。
- **Then**：日志写入 `cancel_at_state=WORKING|SUBMITTED`。
- **PASS**（主判据）：A-06.*.B / C 满足。**FAIL**：不适用。

---

## 5. cancel 时机策略

两条子场景 **共用** 下列逻辑（实现时可抽 `CancelWindow.await(collector, cancelWaitMs)`）：

```
1. 等待 collector 出现首个非空 taskId（上限 taskIdWaitMs）
2. 在 cancelWaitMs 内轮询：
   - 若观测到 WORKING → 立即 cancel，cancel_at_state=WORKING
   - 若已 COMPLETED / FAILED / CANCELED → FAIL（窗口错过）
3. 若 cancelWaitMs 内仅有 SUBMITTED、无 WORKING → 仍 cancel，cancel_at_state=SUBMITTED
4. 若超时仍无 taskId → FAIL
```

**同步子场景并发模型**：

```text
Thread sendThread:
  client.sendMessage(message, collector, errorHandler)  // 阻塞至流结束或错误

Main thread:
  §5 等待窗口 → cancelTask(taskId) → getTask 验证 → join sendThread
```

send 线程在 cancel 后可能仍阻塞直至服务端关闭连接；**主判据以 cancel/get 的 CANCELED 为准**，不要求 send 线程立即返回。

---

## 6. 终态验证（cancel + get 双验）

与 A-05 对称、与现有 `A2aRunCancellationTest` 意图对齐：

| 检查点 | 规则 |
|--------|------|
| `cancelTask` 返回 | `state == CANCELED`，`id == taskId` |
| `getTask` 查询 | 同上；必要时 `WaitUtils.pollUntil`（上限 `cancelPollMs`） |
| `taskId` | 全程单一非空 id |

**不**要求 cancel 前后文本/artifact 一致或为空。

---

## 7. 测试数据

文件：`src/test/resources/testdata/component/protocol/a06-cancel-long-prompt.json`

```json
{
  "_doc": "A-06 tasks/cancel — long prompt to keep task in-flight for cancel window",
  "inputText": "请为我规划一条详细的出差方案：下周三从上海虹桥到北京，出差5天4晚。请分章节详细阐述交通方式对比（高铁与航班）、每日会议日程建议、酒店区域选择、每日餐饮与市内交通预算，以及注意事项。内容尽可能完整、篇幅尽量长。",
  "taskIdWaitMs": 15000,
  "cancelWaitMs": 30000,
  "cancelPollMs": 10000,
  "streamTimeoutMs": 60000,
  "expectedTerminalStateAfterCancel": "TASK_STATE_CANCELED"
}
```

| 字段 | 用途 |
|------|------|
| `inputText` | 长耗时 prompt（**不用「你好」**，避免 cancel 前已 `COMPLETED`） |
| `taskIdWaitMs` | 等待首个 `taskId` 上限 |
| `cancelWaitMs` | 等待 `WORKING` 或回退 `SUBMITTED` 后发 cancel 的上限 |
| `cancelPollMs` | cancel 后 `getTask` 短轮询上限 |
| `streamTimeoutMs` | A-06-S 流结束等待上限 |

读取方式：`A06ScenarioData` + `TestDataLoader`；A-06-S / A-06-Y **共用** 同一 testdata。

---

## 8. 框架落点

| 项 | A-06-S（流式） | A-06-Y（同步） |
|----|----------------|----------------|
| 测试类 | `AgentTaskCancelStreamTest.java` | `AgentTaskCancelSyncTest.java` |
| 路径 | `src/test/java/.../component/protocol/` | 同上 |
| 标签 | `@Tag("component")` `@Tag("smoke")` | 同左 |
| 基类 | `BaseManagedStackTest` | 同左 |
| 栈 | `.streaming(true).agent("mainplan", LLM 注入)` | `.streaming(false)...` |
| send | 主线程或虚拟线程：`sendMessage` + `A2aEventCollector` | **后台线程**阻塞 send + 主线程 cancel |
| cancel / get | `client.cancelTask(taskId)` → `client.getTask(taskId)` | 同左 |
| smoke | `@EnabledIf`：`SIT`/`UAT` + `SIT_LLM_API_KEY` | 同左 |

> **为何两个测试类**：`BaseManagedStackTest` 在 `@BeforeAll` 按类固定 `streaming` 标志；同一类内无法交替 `true`/`false` 而不手动重建 Client。

**实现检查清单**：

- [ ] 两个类均检查 `SIT_LLM_API_KEY`
- [ ] 显式 `.streaming(true)` / `.streaming(false)`
- [ ] 共用 testdata 长 prompt
- [ ] 实现 §5 `CancelWindow` helper（可后续供集成用例复用）
- [ ] A-06.*.B + A-06.*.C 双验 CANCELED
- [ ] A-06-S.D 流式终态非 COMPLETED
- [ ] A-06.*.E 日志 `cancel_at_state`
- [ ] cancel 前已 `COMPLETED` → 明确 FAIL 信息

---

## 9. 运行方式

```bash
export SIT_LLM_API_KEY=<your-key>

# 流式子场景
./mvnw -Dtest.env=SIT -Dtest=AgentTaskCancelStreamTest test

# 同步子场景
./mvnw -Dtest.env=SIT -Dtest=AgentTaskCancelSyncTest test

# 两条一起
./mvnw -Dtest.env=SIT -Dtest=AgentTaskCancelStreamTest,AgentTaskCancelSyncTest test

# smoke
./mvnw -Dtest.env=SIT -Dtest=SmokeTestSuite test
```

---

## 10. 覆盖特性追溯

| 特性 | 子场景 | 子断言 | 覆盖 |
|------|--------|--------|------|
| 特性 4-5 tasks/cancel | S + Y | A-06.*.B / C | ✅ |
| 特性 3-2 流式（前置） | S | A-06.S.0b / S.D | ⚠️ 传输前置 |
| 特性 3-1 同步（前置） | Y | A-06.Y.0b | ⚠️ 传输前置 |
| 特性 4-4 tasks/get（观测手段） | S + Y | A-06.*.C | ⚠️ 作为 cancel 后观测，非 4-4 主覆盖 |

---

## 11. 风险与备注

- **cancel 窗口竞态**：即使用长 prompt，LLM 极快时仍可能 `COMPLETED` 先于 cancel → A-06.*.A **FAIL**；保留 collector 时间线日志，必要时调大 `cancelWaitMs` 或加长 `inputText`。
- **OpenJiuwen 语义**：release features 记载 cancel 可能仅阻止结果消费、不中断执行；本用例只验北向 `CANCELED` 状态，不验进程内是否停止推理。
- **同步 send 线程**：cancel 后 send 线程可能阻塞至连接关闭；join 需设 `streamTimeoutMs`，超时记录 warning 但不推翻 cancel/get 主判据。
- **与 SIT 计划**：计划仅写「流式过程中 cancel」；本实现 **扩展同步子场景**（用户确认 C），覆盖矩阵仍记 A-06 → 特性 4-5，同步路径在追溯表中标注为扩展。
- **与 A-04 / A-05 边界**：A-04 不断言 cancel；A-05 只测已完成 get；A-06 不测已完成任务上的 cancel 语义。
