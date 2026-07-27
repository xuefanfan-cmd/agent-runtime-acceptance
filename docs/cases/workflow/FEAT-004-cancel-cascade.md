---
用例编号: FEAT-004-cancel-cascade
测试标题: 取消级联传播机制——agent-runtime-java 父任务取消同步传播至远程 Task 不成孤儿
story: S1
优先级: P1
自动化状态: BLOCKED（`ExpenseReviewRemoteOrchestrationFailureTest::cancelCascadesToRemoteTask` `@Disabled` 骨架先行——被测 runtime 当前未实现取消逻辑，待 feature 侧实现取消面后启用）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, feat-004, remote-orchestration, cancel]
---

# FEAT-004-cancel-cascade — 取消级联传播机制

> **机制一句话**：取消级联传播是 **agent-runtime-java 的编排生命周期机制**（FEAT-004 §6 FR-REL-02
> 协作式取消 MUST）——父任务在**远程调用进行中**发起取消时，runtime 把取消**同步传播至远程 Task**
> （`CancelTask`）：父任务收敛到 `CANCELED`，远端 workflow Task 同步收到取消（远端 `getTask` 状态
> `CANCELED` 或日志 `CancelTask` 证据），**两端均不残留 `WORKING`、远端不成孤儿任务**。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 父任务取消向远程 Task 的级联传播（FR-REL-02 MUST）——父 CANCELED + 远端同步取消 + 不成孤儿 |
| **载体层 · agent-solution** | 机制触发载体 | 本地编排链路（`expense-review-main` 父端 → `expense-review-workflow` downstream） |
| **测试数据层** | 载体 agent 的实现逻辑 | 超标报销（→远程执行 WORKING）——把父任务推进到远程调用进行中，使取消落在协作边界的业务夹具 |

## 关联特性

- **FEAT-004（任务驱动远程智能体调用）**：§6 FR-REL-02 协作式取消（MUST）。

## 关联架构约束 / FEAT-004 事实要求

- FEAT-004 §2.1：取消级联传播（父 cancel → 远程 `CancelTask`）。
- FEAT-004 §6 FR-REL-02（MUST）：父任务取消时远端 Task 同步取消，不成孤儿。

## 前置条件

1. **被测 runtime 实现取消逻辑**（当前缺失——本用例 `@Disabled` 骨架先行）。
2. 取消面就绪：A2A `tasks/cancel`（待 feature 侧实现）。
3. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。

## 测试数据

- 载体输入：超标报销（把父任务推进到远程执行中，使取消请求落在 workflow 执行进行中）。
- 取消目标：父任务（远程调用进行中）。

## 取消级联参数表（同一编排链路，父→远端同步取消）

| 方法 | 取消语义 | 前置状态 | 预期 |
|---|---|---|---|
| `cancelCascadesToRemoteTask` | 父取消级联至远端 | 父任务进入远程执行（WORKING） | 父 CANCELED；远端 Task 同步 CANCELED；两端不残留 WORKING，远端不成孤儿 |

## 测试步骤

> 取消级联为 `ExpenseReviewRemoteOrchestrationFailureTest` 的第三个 `@Test` 方法；整体 `@Disabled` 骨架，待 runtime 取消面实现后启用。入口协议固定 A2A_STREAM。

| # | 动作 | 预期 |
|---|------|------|
| 1 | main + workflow 拉起；客户端打 main（A2A_STREAM）提交超标报销 | 父任务进入远程执行（`WORKING`） |
| 2 | 远程调用进行中（workflow 执行中）对父任务发起取消（`A2aServiceClient.cancelTask(taskId)`） | 父收敛 `CANCELED`（或运行时等义终态）；远端 workflow Task 同步收到取消 |
| 3 | 双端取证：父事件流 + 远端 `getTask` / 日志 `CancelTask` | 两端均不残留 `WORKING`；远端不成孤儿任务 |

## 预期结果（机制断言）

### A — 父取消级联至远程 Task，两端 CANCELED 不成孤儿
- **Given**：父任务进入远程执行（`WORKING`）。
- **When**：远程调用进行中对父任务发起取消。
- **Then**：父任务收敛到 `CANCELED`（或运行时等义终态）；远端 workflow Task 同步收到取消（远端 `getTask` 状态 `CANCELED` 或日志 `CancelTask` 证据）；两端均不残留 `WORKING`；远端不成孤儿任务。
- **PASS**：级联取消两端一致。**FAIL**：父 CANCELED 但远端残留 WORKING（孤儿任务）/ 取消未传播（FR-REL-02 MUST 违反）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | 🆕 `ExpenseReviewRemoteOrchestrationFailureTest::cancelCascadesToRemoteTask`（与 [orchestration-fault-convergence](FEAT-004-orchestration-fault-convergence.md) 同类，`@Disabled` 骨架） |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-004")` + story `wf.cancel-cascade`（待注册） |
| 基类 | `BaseManagedStackTest` + `SutStack`（`cardEndpointRedirect` / `faultLink`） |
| 客户端 | `A2aServiceClient.cancelTask(taskId)`（取消面无论 A2A/REST 统一复用）+ `InteractionFlow`（A2A_STREAM 提交与事件收集） |
| 断言复用 | `cases/component/protocol/TaskCancelVerifiers.assertCancelAndGet(...)`（取消 + 轮询断言，同型先例） |
| 双端取证 | 远端证据优先 `getTask`（业务面），其次远端日志 `CancelTask` 行（诊断面）；二者择一稳定即可，避免双硬断言（设计档风险 4） |

## 运行方式

```bash
# 🚧 当前 @Disabled 骨架；待 runtime 取消面实现后移除注解
./mvnw test -Dtest=ExpenseReviewRemoteOrchestrationFailureTest#cancelCascadesToRemoteTask -Dtest.env=openjiuwen \
  -DLLM_API_BASE=... -DLLM_API_KEY=... -DLLM_MODEL=...
```

## 覆盖追溯

| FEAT-004 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §2.1/§6 FR-REL-02 取消级联传播（父 cancel → 远程 CancelTask） | A | 🚧 后续落地 |

## 清理策略

- 栈由类级生命周期管理；取消任务随栈销毁。

## 风险与备注

- **被测 runtime 当前未实现取消逻辑**：本用例 `@Disabled` 骨架先行（G/W/T 与双端取证方式已定义），feature 侧实现取消面（A2A `tasks/cancel`）后启用并回归（设计档风险 1）。
- **启用时联动 component 层先例**：`cases/component/protocol/AgentTaskCancelStreamTest`（mainplan 上验证 `tasks/cancel` 可达 CANCELED）+ `AgentTaskCancelSyncTest`（框架缺同步 cancel 观测而 `@Disabled`）。
- **与 FEAT-002 cooperative-cancel 边界**：[FEAT-002 cooperative-cancel](FEAT-002-cooperative-cancel.md) 断**单 agent 本地**协作式取消（父任务自身收敛 CANCELED + 幂等）；本用例断**父→远端级联**取消（远端 Task 同步取消不成孤儿）——同一 FR-REL-02 MUST 在本地 vs 级联两个维度。两档同步待 runtime 取消面实现后启用。
- **与编排故障边界**：[orchestration-fault-convergence](FEAT-004-orchestration-fault-convergence.md) 断被动故障（远端崩溃/连接重置）收敛 FAILED；本用例断主动取消收敛 CANCELED——不同终态语义。
