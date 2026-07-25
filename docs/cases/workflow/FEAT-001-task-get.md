---
用例编号: FEAT-001-task-get
测试标题: GetTask 异步查询机制——agent-runtime-java tasks/get 快照与流内终态一致（顺带 /a2a 尾斜杠等价）
story: S1
优先级: P1
自动化状态: PENDING（⬜ 待新建 `ExpenseReviewWorkflowTaskGetTest`；同型先例 mainplan `AgentTaskGetTest`(A-05) + deepagent `DA-04` 已证正路径语义）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [component, smoke, workflowagent, feat-001]
---

# FEAT-001-task-get — GetTask 异步查询机制

> **机制一句话**：异步任务查询是 **agent-runtime-java 的机制能力**——`tasks/get`（`POST /a2a`）返回指定
> task 的当前快照，供调用方查询长任务（§2/§3/§4）。本用例验证该机制：流式入口跑至终态后，用 `GetTask`
> 独立查询同一 taskId，断言快照 `state` / 结果文本与流内终态一致。**顺带**在同一业务流内分别走 `/a2a` 与
> `/a2a/` 两种拼写（stream 用 `/a2a`、get 用 `/a2a/`），验证入口尾斜杠等价——不单独发探针。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | `tasks/get` 快照查询（§2/§3）+ `POST /a2a` 与 `POST /a2a/` 尾斜杠等价（§3） |
| **载体层 · agent-solution** | 机制触发载体 | 近端 workflow agent（`expense-review-workflow`，单 profile 直连） |
| **测试数据层** | 载体 agent 的实现逻辑 | 合规报销（单轮 `COMPLETED`，提供稳定可查的终态 taskId） |

## 关联特性

- **FEAT-001**：§2「异步查询」+ §3「GetTask 返回指定 task 当前快照 / `POST /a2a` 与 `POST /a2a/`」+ §4「查询长任务」。

## 关联架构约束 / FEAT-001 事实要求

- FEAT-001 §2 / §3 / §4：`tasks/get` 异步查询机制 + 尾斜杠入口等价。
- FEAT-001 §1 覆盖矩阵：`wf.get-task`（部分覆盖——他 SUT 同型先例已证，近端 workflow 侧未覆盖）+ `wf.jsonrpc-endpoint-slash`（并入）。
- **负路径边界**：未知 taskId 的 not-found 语义归 component 层（组织原则 2），本用例不建。

## 前置条件

1. 被测 jar 就绪：`expense-review-workflow:0.2.0-SNAPSHOT`。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`（stream 侧需 LLM 跑至终态）。
3. 单 agent 直连栈；`A2A_STREAM` 协议跑 stream，SDK `getTask` 走 `/a2a/`。
4. 同型先例已证机制正路径：mainplan [AgentTaskGetTest](../../../src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskGetTest.java)（A-05）、deepagent DA-04。

## 测试数据

- 载体输入（合规报销）：`"审核这笔报销：机票3000，酒店2晚每晚500共1000，餐费200"` ⇒ 单轮 `COMPLETED`。
- `contextId` 用 `ctx-feat001-wf-gettask-<uuid8>`（避免跨用例记忆串扰）。

## 测试步骤

| # | 动作 | 协议 / 方法 | 预期 |
|---|------|------------|------|
| 1 | 单 agent 直连栈 | `SutStack` | 栈就绪 |
| 2 | `SendStreamingMessage`（走 `/a2a`）发合规报销至 `COMPLETED` | A2A SDK `message/stream` | 终态 `COMPLETED` |
| 3 | 从流内终态事件抽出 Task，记录 `taskId` 与流内结果文本 | — | taskId 非空、文本非空 |
| 4 | `client.getTask(taskId)`（走 `/a2a/` 尾斜杠拼写） | A2A SDK `tasks/get` | 返回 Task 快照 |
| 5 | 断言 §「预期结果」各机制断言 | — | — |

## 预期结果（机制断言）

### A — 流内终态可提取 taskId
- **Given**：载体 agent 栈就绪。
- **When**：stream 跑合规报销至 `COMPLETED`，抽终态 Task。
- **Then**：`taskId` 非空、流内结果文本非空。
- **PASS**：满足。**FAIL**：终态事件无 Task / taskId 空 / 文本空。

### B — GetTask 快照状态一致
- **Given**：A 通过。
- **When**：`client.getTask(taskId)`。
- **Then**：`queried.id == taskId` && `queried.status().state() == COMPLETED`。
- **PASS**：状态对齐。**FAIL**：GetTask 返回非 `COMPLETED` / id 不匹配（快照查询机制漂移）。

### C — 快照结果文本与流内终态一致
- **Given**：B 通过。
- **When**：`TaskTextExtractor.textOf(queried)` vs 流内终态文本。
- **Then**：两字符串 `isEqualTo`（容差仅时间戳类字段）。
- **PASS**：一致。**FAIL**：两侧文本漂移（`tasks/get` 返回的 artifact 与 stream 侧不一致）。

### D — /a2a 尾斜杠等价（顺带）
- **Given**：同一业务流内，stream 走 `/a2a`、get 走 `/a2a/`。
- **When**：两种拼写各自服务。
- **Then**：两种拼写均正常服务（stream 达终态、get 返回快照）。
- **PASS**：两种拼写均可用。**FAIL**：任一拼写不可服务（入口尾斜杠不等价）。
- **说明**：不单独发尾斜杠探针；deepagent 侧已有独立 `JsonRpcEndpointSlashTest`（姊妹档）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类（待新建） | ⬜ `ExpenseReviewWorkflowTaskGetTest`（包 `cases/component/workflow_agent`，与 `EdpaAdapterCardDiscoveryTest` 同包同栈型） |
| 同型先例 | mainplan [AgentTaskGetTest](../../../src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskGetTest.java)（A-05）、deepagent DA-04 |
| 标签 | `@Tag("component") @Tag("smoke")`；Allure `@Story("wf.get-task")` |
| 基类 | `BaseManagedStackTest`（per-class 栈） |
| 客户端 | `client("expense-review-workflow").sendMessageStreaming(...)` + `client(...).getTask(taskId)` |
| 文本抽取 | `TaskTextExtractor.textOf(task)`（统一序列化，容差时间戳） |
| 断言 | `isEqualTo(COMPLETED)` / `isEqualTo(taskId)` / `isEqualTo(sendText)` |

## 运行方式

```bash
# ⬜ 待新建类落地后（需 LLM）
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowTaskGetTest test
```

## 覆盖追溯

| FEAT-001 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| `wf.get-task`（tasks/get 快照查询机制） | A/B/C | ⬜ 待新建（同型先例已证） |
| `wf.jsonrpc-endpoint-slash`（/a2a 尾斜杠等价） | D | ⬜ 待新建（顺带） |

## 清理策略

- 栈由类级生命周期管理；in-memory TaskStore 随栈销毁。

## 风险与备注

- **缺口说明**：现有链路只消费流内终态 Task（`InteractionFlow` 明确「答案只从本地事件流读，不做 getTask 往返」），story 1 承诺的异步查询独立入口无显式断言，本用例补齐。
- **增强（可选，搭 redis 载体）**：redis profile 栈上 taskId 落 Redis TaskStore（`a2a:task` 键已由 `PlanAgentDirectStreamingRedisTest` 断言），GetTask 走 Redis 路径仍返一致快照——见 [extensions](FEAT-001-extensions.md)。
- **负路径不建**：未知 taskId 的 not-found 语义归 component 层（组织原则 2），本用例只走正路径 SDK API。
