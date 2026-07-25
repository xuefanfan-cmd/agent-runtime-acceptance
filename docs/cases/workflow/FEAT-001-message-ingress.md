---
用例编号: FEAT-001-message-ingress
测试标题: 标准化消息入口机制——agent-runtime-java 四协议入口（A2A 流式/阻塞 + REST 流式/同步）语义等价
story: S1 + S2
优先级: P0
自动化状态: READY
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, feat-001]
---

# FEAT-001-message-ingress — 标准化消息入口机制（四协议等价）

> **机制一句话**：标准化消息入口是 **agent-runtime-java 提供的机制能力**——同一 agent 对外暴露
> A2A JSON-RPC（`POST /a2a`，流式 SSE / 阻塞同步）与 REST 兼容（`POST /v1/query`，`stream:true` SSE /
> `stream:false` JSON）四条入口，**机制保证四入口执行语义不互相漂移**。本用例以同一消息、同一断言
> 在四入口上各跑一遍，验证「入口等价性」这一机制契约。Task 状态序列本身是另一独立机制，由
> [task-lifecycle](FEAT-001-task-lifecycle.md) 承担，本用例只断言四入口终态一致、结果一致。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 四协议入口分发 + 语义等价保证（§5.1.0「不得为不同来源/入口定义互相漂移的执行语义」） |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow agent（`expense-review-workflow`，直连、不经 main） |
| **测试数据层** | 载体 agent 的实现逻辑 | 合规报销场景（全部条目在限额内 ⇒ `risk=none` ⇒ 自动通过，单轮 `COMPLETED`）——选择单轮终态场景是为让四协议都可达成终态，排除 HITL 中断对等价性矩阵的干扰（中断机制见 [input-required](FEAT-001-input-required.md)） |

## 关联特性

- **FEAT-001（标准化智能体服务入口）**：§2「流式 `SendStreamingMessage` / 阻塞 `SendMessage`」+ §3「`POST /a2a`、`POST /v1/query`」+ story 2「REST API 兼容调用」+ §5.1.0「协议维度不得漂移」。

## 关联架构约束 / FEAT-001 事实要求

- FEAT-001 §2 / §3 / story 2：A2A 流式/阻塞 + REST 流式/同步四入口（机制能力）。
- FEAT-001 §5.1.0：不得为不同入口定义互相漂移的执行语义（**入口等价性是机制契约**）。
- FEAT-001 §1 覆盖矩阵：`wf.send-streaming-message` / `wf.send-message-blocking` / `wf.rest-query` / `wf.rest-a2a-equivalence` 四行合并至本用例（按「协议不同但消息步骤+终态预期 90% 一致」合并原则）。

## 前置条件

1. 被测 jar 就绪：`expense-review-workflow:0.2.0-SNAPSHOT`（近端 workflow 载体）。
2. `-Dtest.env=openjiuwen` + `LLM_API_KEY` 等 `SAA_*` 环境变量（载体 agent 的 LLM 调用需要）。
3. 单 agent 直连栈：仅 `expense-review-workflow`，不起 main、无 downstream。
4. 无跨用例依赖：本用例独立发送消息，不依赖其他 TC 结果。

## 测试数据

- 载体 agent 的业务输入（合规报销，单轮终态）：`"审核这笔报销：机票3000，酒店2晚每晚500共1000，餐费200"`。
  - 载体实现逻辑：机票 3000≤5000、住宿 500≤600、餐 200≤300 ⇒ `risk=none` ⇒ auto_approve ⇒ `COMPLETED`。
- `sessionId` 用 `expense-direct-scenario2-<protocol>`（按协议隔离，避免四入口互相撞 session）。
- 无外置数据文件。

## 协议参数表（四入口矩阵）

> 同一消息步骤在四行上各执行一次；任一行终态/结果漂移即矩阵对应单元变红——这正是「入口等价性」机制的可观测信号。

| 协议值（`MessageProtocol`） | 入口端点 | 线型 | story | 终态呈现 | 状态序列断言归属 |
|---|---|---|---|---|---|
| `A2A_STREAM` | `POST /a2a` | SSE 流式（`message/stream`） | S1 | 全序列 `SUBMITTED→WORKING→COMPLETED` | 本用例 + [task-lifecycle](FEAT-001-task-lifecycle.md) |
| `A2A_SYNC` | `POST /a2a` | 阻塞 JSON（`message/send`） | S1 | 仅终态 `COMPLETED` | 本用例（终态） |
| `REST_QUERY` | `POST /v1/query` `stream:true` | SSE 流式 | S2 | 仅终态 `COMPLETED` | 本用例（终态） |
| `REST_QUERY_SYNC` | `POST /v1/query` `stream:false` | 阻塞 JSON | S2 | 仅终态 `COMPLETED` | 本用例（终态） |

## 测试步骤

> 核心步骤只写一遍，对「协议参数表」四行各跑一次（`@ParameterizedTest` + `EnumSource` 四值）。

| # | 动作 | 预期 |
|---|------|------|
| 1 | `SutStack.builder(config).agent("expense-review-workflow")`，类级构栈 | 单 agent 直连栈就绪 |
| 2 | `InteractionFlow.of(client("expense-review-workflow")).protocol(<协议值>)`；`.withMetadata(userId/agentId/sessionId)` | 绑定本次入口与隔离 session |
| 3 | `.send(合规报销文本)` | 消息经该入口送达载体 agent |
| 4 | `.awaitState(TASK_STATE_COMPLETED)` | 四协议均归一化到终态 `COMPLETED`（`InboundExchange` 与 transport 无关） |
| 5 | `.assertGenerated(generated -> 非空 && length > 8)` | 结果文本非空且实质（非空错误/拒答） |
| 6 | （仅 `A2A_STREAM`）`.assertThat(assertStreamTrajectory(...))` | 流式状态序列——指向 [task-lifecycle](FEAT-001-task-lifecycle.md) 机制 |
| 7 | 全程核查 wire 日志/响应 | 无堆栈泄露标志串（见断言 D） |

## 预期结果（机制断言）

### A — 四入口终态等价（`wf.rest-a2a-equivalence` 核心机制）
- **Given**：载体 agent 栈就绪。
- **When**：同一合规报销消息经协议参数表四入口各发送一次。
- **Then**：四次调用 `awaitState` 均达到 `TASK_STATE_COMPLETED`（四矩阵单元全绿）。
- **PASS**：四入口终态一致 `COMPLETED`。**FAIL**：任一入口终态非 `COMPLETED` 或超时（该入口机制不可用或漂移）。**INCONCLUSIVE**：载体 agent 不可达 / LLM 未配置。

### B — 四入口结果语义等价（不漂移）
- **Given**：A 通过。
- **When**：对四入口的 `.assertGenerated(...)` 结果文本。
- **Then**：四份结果均非空、长度 > 8（实质内容）；语义同源（均承载 auto_approve 决策结果）。
- **PASS**：四入口结果均实质非空。**FAIL**：某入口结果为空 / 拒答 / 与他入口语义漂移。

### C — A2A 流式状态序列（链接 [task-lifecycle](FEAT-001-task-lifecycle.md)）
- **Given**：A 通过。
- **When**：仅 `A2A_STREAM` 行，提取去重保序的状态轨迹。
- **Then**：`containsExactly(SUBMITTED, WORKING, COMPLETED)`（新轮严格序列）。
- **PASS**：满足。**FAIL**：跳过 SUBMITTED/WORKING（状态机机制缺陷，详见 task-lifecycle TC）。
- **说明**：sync/REST 入口只呈现终态（无中间态事件），非缺陷——中间态可见性是流式入口独有，终态等价性已由 A 覆盖。

### D — 无堆栈泄露（入口输出卫生）
- **Given**：四入口任一响应/wire 日志。
- **When**：扫描泄露标志串 `java.io.IOException` / `Caused by:` / `Exception in thread` / `at java.base/` / `at org.springframework.` / `at reactor.`。
- **Then**：均不命中。
- **PASS**：无泄露。**FAIL**：任一命中（机制异常泄漏到外部可观测面）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [ExpenseReviewWorkflowDirectAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java)`#compliantExpenseAutoApprovesAndCompletes` |
| 标签 | `@Tag("integration")`；Allure `@Story("wf.direct-streaming/wf.direct-blocking/wf.direct-rest-query/wf.rest-a2a-equivalence")` |
| 基类 | `BaseManagedStackTest`（per-class 栈，单 agent 直连） |
| 参数化 | `@ParameterizedTest` + `@EnumSource(INCLUDE: A2A_STREAM, A2A_SYNC, REST_QUERY, REST_QUERY_SYNC)` |
| 客户端 | `InteractionFlow.of(client("expense-review-workflow")).protocol(...)`（四协议同型） |
| 断言 | `.awaitState(COMPLETED)`（四协议归一化终态）+ `.assertGenerated(...)`（`generatedText()` 超集，覆盖 `workflow_final` 自定义结果类型）+ `assertStreamTrajectory`（仅 A2A_STREAM） |

## 运行方式

```bash
# 近端 workflow 直连四协议矩阵（需 LLM）
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowDirectAcceptanceTest#compliantExpenseAutoApprovesAndCompletes test
```

## 覆盖追溯

| FEAT-001 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| `wf.send-streaming-message`（A2A 流式入口） | A/B/C（`A2A_STREAM` 行） | ✅ |
| `wf.send-message-blocking`（A2A 阻塞入口） | A/B（`A2A_SYNC` 行） | ✅ |
| `wf.rest-query`（REST 流式/同步入口） | A/B（`REST_QUERY*` 行） | ✅ |
| `wf.rest-a2a-equivalence`（四入口语义等价） | A/B（矩阵本身） | ✅ |

## 清理策略

- 单 agent 栈由 `BaseManagedStackTest` 类级生命周期管理，用例结束自动停止进程。
- 无外部持久化（in-memory TaskStore），无需额外清理。

## 风险与备注

- **载体场景选择**：本用例选单轮 `COMPLETED`（合规报销）而非多轮 HITL，是为让四协议都可达成终态——多轮 `INPUT_REQUIRED` 仅 A2A_STREAM 可靠呈现（见 [input-required](FEAT-001-input-required.md) 调试矩阵），放入等价性矩阵会制造协议维度的假红。
- **结果帧类型**：载体 agent 把结果发在自定义 `workflow_final` 类型（非标准 `answer`），共享分类器 `LlmPayload` 已映射为 ANSWER，故文本断言用 `.assertGenerated(...)`（`generatedText()` 超集），不得只读 `answerText()`。
- **REST 入口无服务端 Task**：REST 用 `conversation_id` 影子任务续轮，无 `tasks/get` 语义；REST 的"查询"由 response body / SSE 终帧承载，已含在断言 A/B 内。
- **REST INPUT_REQUIRED 透传未标定**：本用例用单轮场景规避；多轮中断的 REST 维度见 input-required TC（受限）。
