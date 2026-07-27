---
用例编号: FEAT-001-input-required
测试标题: HITL 中断/恢复机制——agent-runtime-java input-required 语义（handler 需用户输入时中断而非伪装 completed）
story: S1
优先级: P0
自动化状态: PARTIAL（`A2A_STREAM` 硬断言 ✅；`A2A_SYNC`/REST 维度受限 🟡）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, feat-001]
---

# FEAT-001-input-required — HITL 中断/恢复机制

> **机制一句话**：Human-In-The-Loop 中断/恢复是 **agent-runtime-java 的机制能力**——当 handler 输出
> 需要用户输入时，Task 必须进入 `input-required` 类语义，**而不是伪装成 completed**（§5.1.6）；
> `interrupted` 时当前 message stream 必须关闭（§5.1.4）；续接（携原 taskId+contextId）后恢复至终态。
> 本用例验证该机制在流式入口的完整中断/恢复轨迹。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | `input-required` 中断态语义 + stream 关闭 + 续接恢复（§5.1.6 / §5.1.4） |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow agent 的 **Questioner 节点**——8 节点 DAG 中 `risk=high` 分支触发的审批中断点 |
| **测试数据层** | 载体 agent 的实现逻辑 | 超标报销（住宿 800>600、晚餐 800>300 ⇒ `risk=high` ⇒ Questioner 中断）；续接 `"approved"` 恢复 |

## 关联特性

- **FEAT-001**：§5.1.6「handler 输出需要用户输入的中断时，Task 必须进入 input-required 类语义，而不是伪装成 completed」+ §5.1.4「interrupted 时 stream 必须关闭」。

## 关联架构约束 / FEAT-001 事实要求

- FEAT-001 §5.1.6 / §5.1.4：HITL 中断/恢复语义（机制能力）。
- FEAT-001 §1 覆盖矩阵：`wf.input-required`（partial——`A2A_STREAM` 可靠，sync/REST 维度受限）。

## 前置条件

1. 被测 jar 就绪：`expense-review-workflow:0.2.0-SNAPSHOT`。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. 单 agent 直连栈（`ExpenseReviewWorkflowDirectAcceptanceTest`）或 main-routed 栈（`AbstractExpenseReviewAcceptanceTest` 三叶子）。
4. `A2A_STREAM` 协议为硬断言主路径；sync/REST 为调试矩阵（已知受限）。

## 测试数据

- 首轮（触发中断）：`"帮我审核这笔报销：机票5000，酒店3晚每晚800共2400，客户晚餐800"` ⇒ `risk=high` ⇒ Questioner ⇒ `INPUT_REQUIRED`（审批提示）。
- 续轮（恢复）：`"approved"`（`InteractionFlow` 续轮自动携 taskId+contextId 续传原任务）⇒ `COMPLETED`。
- `sessionId` 用 `expense-direct-scenario1-<protocol>`。

## 协议维度表（中断态可见性）

| 协议 | 中断态 `INPUT_REQUIRED` 可见性 | 断言级别 |
|---|---|---|
| `A2A_STREAM` | 可靠呈现 | 硬断言（本用例 PASS 判据） |
| `A2A_SYNC` | 不可靠（调试矩阵已知红） | 不升为硬断言 |
| `REST_QUERY` / `REST_QUERY_SYNC` | 透传未标定（独立问题） | 不升为硬断言 |

## 测试步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | 单 agent 直连栈，`A2A_STREAM` | 栈就绪 |
| 2 | `.send(超标报销)` → `.awaitState(INPUT_REQUIRED)` | 中断态呈现（机制：未伪装 completed） |
| 3 | `.assertThat(assertStreamTrajectory(continuation=false, INPUT_REQUIRED))` | 严格序列 `SUBMITTED→WORKING→INPUT_REQUIRED` |
| 4 | `.assertGenerated(...)` | 审批提示非空 |
| 5 | `.send("approved")` → `.awaitState(COMPLETED)` | 续接恢复终态（携原 taskId+contextId） |
| 6 | `.assertThat(assertStreamTrajectory(continuation=true, COMPLETED))` | 续轮 `WORKING→COMPLETED` |
| 7 | `.assertGenerated(...)` | 审核结果非空 |

## 预期结果（机制断言）

### A — 中断态正确呈现（不伪装 completed）
- **Given**：载体 agent 栈就绪，`A2A_STREAM`。
- **When**：发送超标报销。
- **Then**：状态序列 `containsExactly(SUBMITTED, WORKING, INPUT_REQUIRED)`；终态为 `INPUT_REQUIRED`（**非 COMPLETED**）；审批提示文本非空。
- **PASS**：满足。**FAIL**：终态直接 `COMPLETED`（机制把需要输入的中断伪装成完成）/ 缺 `INPUT_REQUIRED` / 审批提示空。

### B — 续接恢复至终态
- **Given**：A 通过（已 `INPUT_REQUIRED`）。
- **When**：续接 `"approved"`（携原 taskId+contextId）。
- **Then**：`awaitState(COMPLETED)`；续轮轨迹 `containsSubsequence(WORKING, COMPLETED)`。
- **PASS**：恢复至 `COMPLETED`。**FAIL**：续接后未收束 / taskId/contextId 未续传（恢复错任务）。

### C — 审核结果非空
- **Given**：B 通过。
- **When**：`.assertGenerated(...)` 读续轮结果。
- **Then**：非空（`workflow_final` 结果帧经分类器计入 `generatedText()`）。
- **PASS**：非空。**FAIL**：续接后结果空。

### D — 多协议维度受限（partial 标注）
- **Given**：同一超标报销场景在 sync/REST 入口。
- **When**：尝试 `awaitState(INPUT_REQUIRED)`。
- **Then**：`A2A_STREAM` 硬断言在岗；sync/REST 不升为硬断言（REST INPUT_REQUIRED 透传未标定，属独立问题）。
- **PASS（本用例）**：`A2A_STREAM` 行 A/B/C 全绿即 PASS。**后续**：REST 透传标定后回补 sync/REST 维度为本用例硬断言。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | [ExpenseReviewWorkflowDirectAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java)`#overLimitExpenseRequiresApprovalThenCompletesOnApprove` |
| 跨特性证据 | [AbstractExpenseReviewAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/AbstractExpenseReviewAcceptanceTest.java) 场景1（三叶子继承；redis 叶子另断经 redis 持久化恢复） |
| 标签 | `@Tag("integration")`；Allure `@Story("wf.input-required")` |
| 参数化 | `@ParameterizedTest` 四协议（仅 `A2A_STREAM` 行做轨迹硬断言，其余 no-op） |
| 断言 | `.awaitState(INPUT_REQUIRED)` + `.awaitState(COMPLETED)` + `assertStreamTrajectory` + `.assertGenerated(...)` |

## 运行方式

```bash
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowDirectAcceptanceTest#overLimitExpenseRequiresApprovalThenCompletesOnApprove test
```

## 覆盖追溯

| FEAT-001 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| `wf.input-required`（HITL 中断/恢复语义） | A/B/C（`A2A_STREAM`） | 🟡 partial（sync/REST 维度受限） |

## 清理策略

- 栈由类级生命周期管理；in-memory / redis TaskStore 由叶子清理。

## 风险与备注

- **载体节点唯一性**：近端 workflow agent 的 Questioner 节点是 FEAT-001 `input-required` 机制在 SIT 侧的**唯一真实触发面**（deep-research 载体上该状态不可达——业务载体差异，非机制差异）。
- **REST 透传未标定**：sync/REST 下 `INPUT_REQUIRED` 呈现不可靠，是网关/transport 层透传未标定的独立问题，本用例不升为硬断言；标定后回补。
- **续轮 taskId 续传**：`InteractionFlow` 续轮自动携原 taskId+contextId，无需测试代码手工传递——这是机制恢复的载体侧实现，断言 B 隐式验证续传正确。
