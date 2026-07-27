---
用例编号: FEAT-001-task-lifecycle
测试标题: Task 生命周期状态机机制——agent-runtime-java 保证 submitted→working→terminal 单调收束
story: S1
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

# FEAT-001-task-lifecycle — Task 生命周期状态机机制

> **机制一句话**：Task 状态机是 **agent-runtime-java 的核心机制**——每个消息入口创建的 Task 至少
> 经历 `submitted → working`，并以 `completed` / `failed` / `canceled` 或 `interrupted` 类状态收束
> （§5.1.6）。本用例验证该**状态序列单调性**：新轮严格 `SUBMITTED→WORKING→terminal`，续轮宽松
> `WORKING→terminal`（容忍恢复时合法重发 `SUBMITTED`）。状态序列只在流式入口（`A2A_STREAM`）可见——
> sync/REST 只呈现终态，非缺陷（终态等价性已在 [message-ingress](FEAT-001-message-ingress.md) 覆盖）。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | Task 状态机：`submitted/working` 必经 + terminal 收束（§5.1.6），中断态 `input-required` 不伪装 completed |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow agent（`expense-review-workflow`，`A2A_STREAM` 直连） |
| **测试数据层** | 载体 agent 的实现逻辑 | 合规报销（单轮 ⇒ `COMPLETED`，验证新轮严格序列）/ 超标报销（多轮 ⇒ 续轮恢复，验证宽松子序列） |

## 关联特性

- **FEAT-001**：§5.1.6「至少经历 submitted/working，并以 completed/failed/canceled 或 interrupted 类状态收束」+ §2「Task 状态查询」。

## 关联架构约束 / FEAT-001 事实要求

- FEAT-001 §5.1.6：Task 生命周期状态序列单调性（机制能力）。
- FEAT-001 §1 覆盖矩阵：`wf.task-lifecycle` 单行。

## 前置条件

1. 被测 jar 就绪：`expense-review-workflow:0.2.0-SNAPSHOT`。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`（载体 LLM 调用）。
3. 单 agent 直连栈，`A2A_STREAM` 协议（状态序列仅流式入口可见）。
4. 依赖 [message-ingress](FEAT-001-message-ingress.md) 的入口可达性（同一栈、同一载体）。

## 测试数据

- 新轮（严格序列）载体输入：合规报销 `"审核这笔报销：机票3000，酒店2晚每晚500共1000，餐费200"` ⇒ `risk=none` ⇒ 单轮 `COMPLETED`。
- 续轮（宽松子序列）载体输入：超标报销首轮 `"帮我审核这笔报销：机票5000，酒店3晚每晚800共2400，客户晚餐800"` ⇒ `INPUT_REQUIRED`；续轮 `"approved"` ⇒ 恢复 `COMPLETED`。
- `sessionId` 用 `expense-direct-scenarioN-A2A_STREAM`。

## 测试步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | 单 agent 直连栈，`A2A_STREAM` 协议 | 栈就绪 |
| 2 | `.send(合规报销)` → `.awaitState(COMPLETED)` | 终态 `COMPLETED` |
| 3 | `.assertThat(assertStreamTrajectory(continuation=false, COMPLETED))` | 新轮严格序列 `containsExactly(SUBMITTED, WORKING, COMPLETED)` |
| 4 | `.send(超标报销)` → `.awaitState(INPUT_REQUIRED)` | 中断态 |
| 5 | `.send("approved")` → `.awaitState(COMPLETED)` | 续轮恢复终态 |
| 6 | `.assertThat(assertStreamTrajectory(continuation=true, COMPLETED))` | 续轮宽松 `containsSubsequence(WORKING, COMPLETED)` |

## 预期结果（机制断言）

### A — 新轮严格状态序列
- **Given**：载体 agent 栈就绪，`A2A_STREAM`。
- **When**：发送合规报销至 `COMPLETED`，提取去重保序的状态轨迹（`distinctStatesInOrder`，只看 STATE 类事件）。
- **Then**：`containsExactly(SUBMITTED, WORKING, COMPLETED)`。
- **PASS**：三态严格齐序。**FAIL**：跳过 `SUBMITTED` 或 `WORKING` / 顺序错乱 / 多出非预期中间态。

### B — 续轮宽松子序列（容忍恢复重发）
- **Given**：A 通过；首轮已达 `INPUT_REQUIRED`。
- **When**：续接 `"approved"` 恢复至 `COMPLETED`，提取续轮轨迹。
- **Then**：`containsSubsequence(WORKING, COMPLETED)`（`SUBMITTED` 可选——运行时恢复进行中的任务时合法重发）。
- **PASS**：满足。**FAIL**：续轮无 `WORKING` 或未收束 `COMPLETED`。

### C — terminal 收束类合法
- **Given**：A/B 轨迹。
- **When**：检查终态。
- **Then**：终态属于 `{COMPLETED, FAILED, CANCELED, INPUT_REQUIRED(interrupted)}` 之一，不得停留在 `WORKING`。
- **PASS**：合法收束。**FAIL**：终态停在 `WORKING`（任务挂死，未收束）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [ExpenseReviewWorkflowDirectAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java)（`assertStreamTrajectory` + `distinctStatesInOrder`） |
| 跨特性证据 | [AbstractExpenseReviewAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/AbstractExpenseReviewAcceptanceTest.java) 三叶子同型 |
| 标签 | `@Tag("integration")`；Allure `@Story("wf.task-lifecycle")` |
| 断言器 | `assertStreamTrajectory(protocol, desc, continuation, terminal)`——非 `A2A_STREAM` 协议为 no-op（终态已由 `awaitState` 覆盖） |
| 轨迹提取 | `distinctStatesInOrder(events)`：去重保序，只计 `InboundEvent.Kind.STATE` |

## 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowDirectAcceptanceTest test
```

## 覆盖追溯

| FEAT-001 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| `wf.task-lifecycle`（状态序列单调性） | A/B/C | ✅ |

## 清理策略

- 栈由类级生命周期管理；in-memory TaskStore 无需额外清理。

## 风险与备注

- **可见性仅限流式**：sync/REST 入口只呈现终态事件，本机制断言对它们为 no-op——这不是缺口，终态可达性已在 [message-ingress](FEAT-001-message-ingress.md) A 断言覆盖。若需 sync/REST 的中间态可见性，属传输层增强，非状态机机制缺陷。
- **WORKING 去重**：载体 agent 可能发多次 `WORKING` 进度，`distinctStatesInOrder` 只计一次，避免进度噪声干扰序列断言。
