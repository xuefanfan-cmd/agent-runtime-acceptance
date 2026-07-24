---
用例编号: FEAT-002-cooperative-cancel
测试标题: 协作式取消机制——agent-runtime-java 在 LLM/工具调用边界收敛为 CANCELED 且取消幂等
story: S2
优先级: P1
自动化状态: BLOCKED（`WorkflowCancelExecutionTest` 2 方法 `@Disabled` 骨架先行——被测 runtime 当前未实现取消逻辑，待 feature 侧实现取消面后启用）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, versatile, cancel, feat-002]
---

# FEAT-002-cooperative-cancel — 协作式取消机制

> **机制一句话**：协作式取消是 **agent-runtime-java 的生命周期管理机制**（FEAT-002 §6 FR-REL-02
> MUST）——客户端发起取消请求后，runtime 在**当前 LLM/工具调用边界**协作式收敛，任务终态为 CANCELED
> （或运行时等义终态）而非残留 WORKING；已产出部分**不伪造为 COMPLETED**。本机制进一步验证取消的
> **幂等性**：对已终态任务取消无错、状态不变；重复取消行为一致。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 运行时取消传播 + 协作边界收敛（FR-REL-02 MUST）——取消在 LLM/工具边界收敛 CANCELED + 幂等 |
| **载体层 · agent-solution** | 机制触发载体 | `expense-review-workflow`（合规报销场景驱动任务进入执行中） |
| **测试数据层** | 载体 agent 的实现逻辑 | 合规报销场景消息——把任务推进到 LLM/工具调用进行中，使取消边界成立 |

## 关联特性

- **FEAT-002（异构智能体框架兼容适配）**：§6 FR-REL-02 协作式取消（MUST）。

## 关联架构约束 / FEAT-002 事实要求

- FEAT-002 §2.2：取消与中断协同——协作式取消 + 取消幂等性（2 个子用例）。
- FEAT-002 §6 FR-REL-02（MUST）：取消请求后智能体在当前 LLM/工具调用边界收敛，终态为 CANCELED（或等义终态）而非残留 WORKING；取消操作幂等。
- FEAT-002 §5.2 验收标准：取消在调用边界收敛 CANCELED、不残留 WORKING、已产出部分不伪造 COMPLETED；取消幂等（终态任务取消无错状态不变、重复取消行为一致）。

## 前置条件

1. **被测 runtime 实现取消逻辑**（当前缺失——本用例整体 `@Disabled`，骨架先行）。
2. 取消面就绪：A2A `tasks/cancel` 或 REST 等价端点（待 feature 侧实现）。
3. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。

## 测试数据

- 载体输入：合规报销场景消息（把任务推进到执行中，使取消请求落在 LLM/工具调用进行中）。
- 取消目标：任务 A（已到达 COMPLETED 终态，验证终态取消幂等）、任务 B（执行中，验证重复取消）。

## 取消参数表（同一载体，两类取消语义）

| 方法 | 取消语义 | 前置状态 | 预期终态 |
|---|---|---|---|
| `cancelAtLlmBoundary` | 执行中取消 | 任务进入 LLM/工具调用进行中 | CANCELED（或等义），不残留 WORKING，不伪造 COMPLETED |
| `cancelTerminalAndIdempotent` | 终态取消 + 重复取消 | 任务 A=COMPLETED；任务 B=执行中 | A：无错、保持 COMPLETED；B：两次取消行为一致，同收敛 CANCELED |

## 测试步骤

> 两类取消语义合并为一个测试类（`WorkflowCancelExecutionTest`）的两个 `@Test` 方法；整体 `@Disabled` 骨架，待 runtime 取消面实现后启用。

| # | 动作 | 预期 |
|---|------|------|
| 1 | expense-review-workflow 拉起，客户端提交合规报销使任务进入执行中 | 任务进入 WORKING（LLM/工具调用进行中） |
| 2 | 任务执行期间发起取消请求（`A2aServiceClient.cancelTask(taskId)`） | 智能体在当前调用边界收敛；终态 CANCELED（或运行时等义），不残留 WORKING，已产出部分不伪造 COMPLETED |
| 3 | 任务 A 已达 COMPLETED；对 A 发起取消 | 无错误返回，A 状态保持 COMPLETED 不变 |
| 4 | 任务 B 执行中；对 B 连续发起两次取消 | 两次行为一致，B 收敛到同一 CANCELED 终态 |

## 预期结果（机制断言）

### A — 执行中取消在调用边界收敛 CANCELED
- **Given**：任务进入 LLM/工具调用执行中。
- **When**：任务执行期间发起取消请求。
- **Then**：智能体在当前调用边界收敛；任务终态为 CANCELED（或运行时等义终态），不残留 WORKING；已产出部分不伪造为 COMPLETED。
- **PASS**：收敛 CANCELED。**FAIL**：残留 WORKING（取消未传播）/ 伪造 COMPLETED（FR-REL-02 MUST 违反）。

### B — 取消幂等（终态无错 + 重复一致）
- **Given**：任务 A=COMPLETED；任务 B 执行中。
- **When**：对 A 取消；对 B 连续两次取消。
- **Then**：A：无错误返回、状态保持 COMPLETED 不变；B：两次行为一致，收敛到同一 CANCELED 终态。
- **PASS**：取消幂等成立。**FAIL**：终态取消报错 / 重复取消行为不一致（幂等性缺陷）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | 🆕 `WorkflowCancelExecutionTest`（新建，落 `cases/integration/workflow_call`，整体 `@Disabled` 骨架） |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-002")` + `@Story("wf.cooperative-cancel")` |
| 基类 | `BaseManagedStackTest` + `SutStack` |
| 客户端 | `A2aServiceClient.cancelTask(taskId)`（取消面无论 A2A/REST，测试代码统一复用该 client 方法）+ `InteractionFlow`（提交与事件收集） |
| 断言复用 | `cases/component/protocol/TaskCancelVerifiers.assertCancelAndGet(...)`（取消 + 轮询断言，同型先例） |

## 运行方式

```bash
# 🚧 当前 @Disabled 骨架；待 runtime 取消面实现后移除注解
mvn test -Dtest=WorkflowCancelExecutionTest -Dtest.env=openjiuwen \
  -DLLM_BASE_URL=... -DLLM_API_KEY=... -DLLM_MODEL_NAME=...
```

## 覆盖追溯

| FEAT-002 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| story 2：协作式取消（FR-REL-02 MUST） | A | 🚧 后续落地 |
| story 2：取消幂等性 | B | 🚧 后续落地 |

## 清理策略

- 栈由类级生命周期管理；取消任务随栈销毁。

## 风险与备注

- **被测 runtime 当前未实现取消逻辑**：本用例整体标记为后续落地，以 `@Disabled` 骨架先行定义 G/W/T 与双端取证方式。feature 侧实现取消面（A2A `tasks/cancel` 或 REST 等价端点）后移除注解启用并回归。
- **启用时联动 component 层先例**：`cases/component/protocol/AgentTaskCancelStreamTest`（在 mainplan 上验证 `tasks/cancel` 可达 CANCELED）+ `AgentTaskCancelSyncTest`（框架缺同步 cancel 观测而 `@Disabled`）。注意区分两种「落 component 层」：原则 2 的「归 component 层」是按被测对象所有权**移出本档**（非法输入/SDK 错误码，不建 SIT 用例）；component 层取消探针是仓库里的**物理分层同型先例**，本档启用时复用其 `TaskCancelVerifiers`。
- **与中断检测边界**：[versatile-remote-reliability](FEAT-002-versatile-remote-reliability.md) 的中断检测（被动杀进程不得假成功，FR-REL-01）与本机制（主动取消收敛 CANCELED，FR-REL-02）是两个不同语义，勿混淆。
