---
id: A-04
title: message/stream 流式调用
module: A — A2A 协议与通讯模型
owner: haozhizhuang
priority: P0
feature: 特性3-2 / 特性4-3
status: designed
sut: main-plan-agent
stack: mainplan（单 agent，无需 trip/hotel）
tags: [component, smoke]
depends_on:
  - mainplan fat jar 可构建并安装至本地 Maven 仓库
  - A-01 已通过（Agent Card 发现链路可用）
  - mainplan 启动时已配置 LLM（见 §6；由测试人员准备，用例代码不门禁）
smoke_scope: 纳入 SmokeTestSuite（@Tag("smoke")）；执行前由测试人员保证 LLM 与 mainplan 就绪
---

# A-04 — message/stream 流式调用

> **一句话**：在托管栈拉起的 mainplan 上，通过 A2A `SendStreamingMessage` 发送用户消息，
> 收集 SSE 流式事件，验证任务状态流转与终态结果满足最小流式闭环。
>
> 本用例验证 **特性 3-2（S2C 流式）** 与 **特性 4-3（message/stream）** 的外部可观测行为。
> 不断言增量条数、增量时序、响应具体文案（留给 A-09 / A-10）。

---

## 1. 场景目标

验证 A2A 流式调用路径在 mainplan 上可端到端工作：

1. 流式调用前，Agent Card 声明 `capabilities.streaming == true`（硬门禁）；
2. 通过 `SendStreamingMessage` 发送用户文本，能收到 SSE 事件流且无传输层错误；
3. 事件流中可提取任务状态序列：至少含 `SUBMITTED` 与终态 `COMPLETED`；
4. 全流程 `taskId` 保持一致；
5. 终态任务携带非空文本结果（问候类回复即可，不断言具体内容）。

**本用例不覆盖**（由其他用例负责）：

- 增量输出条数、顺序、分片内容（→ A-09）
- 无 `WORKING` 直接 `COMPLETED` 的专项语义（→ A-10）
- `message/send` 同步路径（→ A-03）
- `tasks/get` / `tasks/cancel`（→ A-05 / A-06）

---

## 2. 前置条件

| # | 条件 | 不满足时的处理 |
|---|------|----------------|
| 1 | `agent-travel-mainplan-a2a` fat jar 已构建并可在 `~/.m2` 解析 | 托管栈启动失败 → **FAIL** |
| 2 | 测试框架按 per-class 粒度拉起 **仅 mainplan**（随机端口） | 就绪探针失败 → **FAIL** |
| 3 | mainplan 进程已具备可用 LLM 配置（`LLM_*` 环境变量、`sut.java.system-properties` 等，见 §6） | 由测试人员启动前准备；缺失时流式调用可能 **FAIL**（超时、终态 `FAILED`、空文本等） |
| 4 | **无需** trip / hotel / 远程 agent 链路 | — |

> LLM 为**运行前置**，但**不在测试类内**做环境变量门禁或 `@EnabledIf` 跳过；凭据与 profile 由执行测试的人员在拉起 agent 前配置。

---

## 3. 场景步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 拉起 mainplan（per-class，`streaming=true`） | `SutStack` | 就绪探针通过；LLM 由启动环境注入（见 §6） |
| 2 | SDK `client("mainplan").getAgentCard()` | A2A 发现 | 非空 Card；`capabilities.streaming == true` |
| 3 | 从 testdata 读取 `inputText`（默认「你好」） | — | 非空字符串 |
| 4 | 发起 `SendStreamingMessage`，注册事件收集器，直至流结束或超时 | A2A JSON-RPC + SSE | 收到 ≥1 个流式事件；无未处理 transport 错误 |
| 5 | 从事件流提取 `taskId`、状态序列、终态文本 | 见 §5 | 满足 A-04.A～D |
| 6 | 记录是否观测到 `WORKING` | 见 §4.A-04.E | 不影响主 verdict |

---

## 4. 可观测子断言（PASS / FAIL / INCONCLUSIVE）

> 黑盒边界：仅经 A2A SDK / SSE 事件流观测，不读 SUT 进程内配置、类、日志文件。
> 三态语义同 [PHILOSOPHY.md](../../../PHILOSOPHY.md)。

### A-04.0 — 流式能力硬门禁（Card）

- **Given**：mainplan 已就绪。
- **When**：`getAgentCard()`，读取 `capabilities.streaming`。
- **Then**：`== true`。
- **PASS**：为 true。**FAIL**：为 false、缺失或 Card 不可解析。**INCONCLUSIVE**：不适用。

### A-04.A — 流式通道可达

- **Given**：A-04.0 已通过；mainplan 已按 §6 配置 LLM。
- **When**：对 testdata 中的 `inputText` 发起 `SendStreamingMessage`。
- **Then**：在 `streamTimeoutMs` 内收到 ≥1 个流式事件；调用过程无未捕获的 transport / JSON-RPC 致命错误。
- **PASS**：满足上述条件。**FAIL**：超时零事件、连接错误、JSON-RPC error。**INCONCLUSIVE**：SUT 未暴露任何流式事件面且无法区分于协议层故障时（记录后按 FAIL 处理，因 Card 已声明 streaming）。

### A-04.B — 状态序列最小闭环

- **Given**：A-04.A 收到的事件流。
- **When**：按 §5 规则提取状态序列（去重保留首次出现顺序）。
- **Then**：
  - 序列中包含 `TASK_STATE_SUBMITTED`；
  - 最后一个状态为 `TASK_STATE_COMPLETED`；
  - 若出现 `TASK_STATE_FAILED` / `TASK_STATE_CANCELED` 等非预期终态 → **FAIL**。
- **PASS**：含 `SUBMITTED` 且终态为 `COMPLETED`。**FAIL**：缺 `SUBMITTED`、终态非 `COMPLETED`、或 LLM 不可用导致 `FAILED`。**INCONCLUSIVE**：不适用（流式面已声明且已收到事件）。

### A-04.C — taskId 一致性

- **Given**：A-04.A 收到的事件流。
- **When**：从每个携带 `Task` 或 `taskId` 的流式事件中提取标识。
- **Then**：所有可提取的 `taskId` 相同且非空。
- **PASS**：单一非空 taskId。**FAIL**：缺失、不一致或多 taskId。**INCONCLUSIVE**：不适用。

### A-04.D — 终态文本非空

- **Given**：终态为 `COMPLETED` 的任务。
- **When**：从终态事件关联的 artifact / message parts 拼接可读文本（见 §5.3）。
- **Then**：拼接结果 trim 后非空。
- **PASS**：非空。**FAIL**：空或不可解析。**INCONCLUSIVE**：SUT 完成但未在流式事件中暴露任何文本/artifact 面（记录观测缺口；因 greeting 场景预期有回复，按 **FAIL** 处理）。

### A-04.E — WORKING 观测记录（不改变主 verdict）

- **Given**：A-04.B 已 PASS。
- **When**：检查状态序列是否曾出现 `TASK_STATE_WORKING`。
- **Then**：
  - 若出现：记录 `working_observed=true`（正常）。
  - 若未出现：主判据仍为 **PASS**；额外记录 `working_observed=false`（observation / evidence_gap 标签，供 A-10 与覆盖分析使用，**不**将 A-04 改为 FAIL 或 INCONCLUSIVE）。
- **PASS**（主判据）：A-04.B 已满足。**FAIL**：不适用本子断言。**INCONCLUSIVE**：不适用。

---

## 5. 流式事件提取规则

> 实现测试类时按下列规则从 `StreamingEventKind` / `ClientEvent` 统一提取字段，避免在测试方法内散落解析逻辑。

### 5.1 状态提取

从流式事件中按时间顺序收集 `TaskState`，来源优先级：

1. `TaskStatusUpdateEvent.status.state`
2. `Task.status.state`（完整 Task 快照事件）

**去重策略**：连续相同状态只保留一条；非连续重复（如再次 `WORKING`）保留，以反映真实推送序列。

### 5.2 taskId 提取

从每个可携带任务标识的事件中取 `Task.id` 或等价字段；首个非空值作为基准，后续事件必须一致。

### 5.3 终态文本提取

对终态 `COMPLETED` 任务，按顺序尝试：

1. 终态前序或同批事件中的 `TextPart` 文本拼接；
2. `Task.artifacts` 内 `TextPart` 拼接；
3. 若均为空 → A-04.D **FAIL**。

不校验文本语义、语言、长度上限（留给业务用例）。

### 5.4 流结束判定

满足以下任一即视为流结束，停止等待：

- 观察到 `TaskState.isFinal() == true` 且状态为 `COMPLETED`；
- SSE 连接正常关闭且已收到终态事件；
- 达到 testdata 中 `streamTimeoutMs` → **FAIL**（A-04.A）。

### 5.5 与 A-03 / A-09 / A-10 的边界

| 用例 | 本用例关系 |
|------|-----------|
| A-03 | 同输入、同 SUT，但走 `SendMessage`；A-04 不走同步路径 |
| A-09 | A-04 不断言 WORKING 与 COMPLETED 之间增量条数与时序 |
| A-10 | A-04 允许无 WORKING；A-10 专门验证「快速完成」形态 |

---

## 6. LLM 配置（测试人员准备，用例不门禁）

A-04 **不在测试代码中**读取 `SIT_LLM_API_KEY`、不使用 `@EnabledIf` 按 profile 跳过。执行前由测试人员保证 mainplan 能调用 LLM，方式与 `StreamingTravelPlanningTest` 对齐：

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

未配置或配置错误时，不在测试侧提前 FAIL；表现为 A-04.A（超时零事件）、A-04.B（终态 `FAILED`）或 A-04.D（空文本）等断言失败。

### 6.1 运行时探针（步骤 4～5）

流式调用完成后：

- 若终态为 `TASK_STATE_FAILED`，且暴露的错误信息表明上游 LLM 不可用（如含 `UPSTREAM_UNAVAILABLE`、`timeout`、`authentication` 等可机器识别的错误类别）→ **FAIL**（凭据或 LLM 服务问题）。
- 若终态为 `COMPLETED` 且 A-04.D 非空 → 视为 LLM 探针 **PASS**。

---

## 7. 测试数据

文件：`src/test/resources/testdata/component/protocol/a04-stream-hello.json`

```json
{
  "_doc": "A-04 message/stream — primary scenario input and timing budget",
  "inputText": "你好",
  "streamTimeoutMs": 60000,
  "expectedTerminalState": "TASK_STATE_COMPLETED",
  "requiredStates": ["TASK_STATE_SUBMITTED"],
  "optionalObservationStates": ["TASK_STATE_WORKING"]
}
```

| 字段 | 用途 |
|------|------|
| `inputText` | 流式请求用户消息 |
| `streamTimeoutMs` | 等待流结束上限（毫秒） |
| `expectedTerminalState` | 主路径期望终态 |
| `requiredStates` | 状态序列必须包含的集合 |
| `optionalObservationStates` | 仅记录、不强制（对应 A-04.E） |

读取方式：文案与超时写在测试常量；JSON 仅外置参考。**不**使用 `main` ScenarioData。

---

## 8. 框架落点

| 项 | 值 |
|----|----|
| 测试类 | `src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentStreamMessageTest.java` |
| 标签 | `@Tag("component")` `@Tag("smoke")` |
| 基类 | `BaseManagedStackTest` |
| 栈描述 | `SutStack.builder(config).streaming(true).agent("mainplan")` — 仅 mainplan |
| 客户端 | `client("mainplan")`；须使用 **`SendStreamingMessage` 流式路径**（非 `SendMessage` 阻塞路径） |
| 事件收集 | 新建或复用流式事件收集器（收集 `StreamingEventKind`，提供 `extractStates()` / `extractTaskId()` / `extractFinalText()`） |
| 断言 | AssertJ；状态序列、taskId、终态文本；A-04.E 的 `working_observed` 写入测试日志或 `@AfterEach` 报告字段 |
| 数据 | `testdata/component/protocol/a04-stream-hello.json` |
| smoke 范围 | `@Tag("smoke")`；无 `@EnabledIf`；执行前由测试人员配置 LLM（§6） |

**实现检查清单**（写测试类时逐项落实）：

- [ ] `getAgentCard()` 断言 `streaming == true`，否则 FAIL
- [ ] 调用链明确为 `SendStreamingMessage`（SSE）
- [ ] 测试常量提供 `inputText` 与 `streamTimeoutMs`（JSON 仅参考）
- [ ] 实现 §5 提取规则（建议独立 helper，供 A-09 复用）
- [ ] A-04.E：`WORKING` 缺失时测试仍 PASS，日志标记 `working_observed=false`
- [ ] 不在测试类内门禁 `SIT_LLM_API_KEY` 或按 `test.env` 跳过

---

## 9. 运行方式

```bash
# 前置：构建 mainplan jar + 配置 LLM（示例：统一 LLM_* 环境变量）
export LLM_API_KEY=<your-key>
# 按需：export LLM_API_BASE=... LLM_MODEL=... LLM_PROVIDER=...

# 单类
./mvnw -Dtest=AgentStreamMessageTest test

# 指定 profile（可选，影响 application-<env>.yml）
./mvnw -Dtest.env=SIT -Dtest=AgentStreamMessageTest test

# smoke
./mvnw -Dtest=SmokeTestSuite test
```

> 缺 LLM 时测试仍会执行并在流式断言处失败；执行前请按 §6 自行配置凭据与代理。

---

## 10. 覆盖特性追溯

| 特性 | 子断言 | 覆盖 |
|------|--------|------|
| 特性 3-2 S2C 流式 | A-04.A / B / E | ✅ |
| 特性 4-3 message/stream | A-04.A / B / C / D | ✅ |
| 特性 4-1 Agent Card（streaming 声明） | A-04.0 | ✅（门禁） |

---

## 11. 风险与备注

- **LLM  flaky**：网络或配额波动可能导致 `FAILED`；流水线应使用稳定 LLM 端点，失败时保留完整事件流日志便于区分 A-04.A 与 A-04.B。
- **WORKING 缺失**：按 §4.A-04.E 不判 FAIL；与 SIT 计划字面「含 WORKING」的偏差在覆盖分析中说明，由 A-10 补充专项验证。
- **流式 vs 同步 SDK 误用**：若实现误走 `SendMessage`，本用例无法验证特性 4-3；代码评审须核对 transport 方法为 `SendStreamingMessage`。
- **依赖 A-01**：Card 发现链路应先可用；A-04 仍独立执行 Card streaming 硬门禁，不假设 A-01 测试结果缓存。
- **超时**：问候场景默认 60s；若 LLM 慢可在 testdata 调大 `streamTimeoutMs`，不改断言逻辑。
