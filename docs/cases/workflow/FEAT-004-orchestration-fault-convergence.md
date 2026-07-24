---
用例编号: FEAT-004-orchestration-fault-convergence
测试标题: 编排层故障收敛机制——agent-runtime-java 在远端崩溃/连接重置下父任务有界失败不静默挂起、不假成功
story: S1
优先级: P1
自动化状态: TOBUILD（`ExpenseReviewRemoteOrchestrationFailureTest` 2 方法待新建；依赖 FaultLink resetPeer/restore + SutStack.stop）
适用环境: openjiuwen
作者: TBD
创建日期: 2026-07-22
评审记录: |
  评审人: TBD
  评审日期: 待定
  结论: 待评审
tags: [integration, workflowagent, feat-004, remote-orchestration, fault]
---

# FEAT-004-orchestration-fault-convergence — 编排层故障收敛机制

> **机制一句话**：编排层故障收敛是 **agent-runtime-java 的可靠性机制**（FEAT-004 §2.1/§6 FR-REL-01
> 中断检测 MUST）——父 agent 处于 A2A 编排调用链的**父端**，其 downstream（远程 agent）的故障是合理覆盖
> 对象。当远端**执行中崩溃**或 main→workflow **连接重置**时，runtime 必须把**父任务有界收敛到失败终态**
> （FAILED/可诊断），**绝不静默挂起（无限 WORKING）、绝不假成功收敛为 COMPLETED**。

## 机制层次（三层框架）

| 层 | 角色 | 本用例体现 |
|----|------|-----------|
| **机制层 · agent-runtime-java** | 机制提供方 | 编排层 downstream 故障下的父任务终态收敛与中断检测（FR-REL-01 MUST）——不静默挂起、不假成功 |
| **载体层 · agent-solution** | 机制触发载体 | 本地编排链路（`expense-review-main` 父端 → `expense-review-workflow` downstream），workflow 端点经 `cardEndpointRedirect` + `FaultLink` 代理 |
| **测试数据层** | 载体 agent 的实现逻辑 | 超标报销（→远程执行 WORKING）——把父任务推进到远程调用进行中，使故障注入点成立的业务夹具 |

## 关联特性

- **FEAT-004（任务驱动远程智能体调用）**：§2.1 远程调用故障面；§6 FR-REL-01 中断检测（MUST）。

## 关联架构约束 / FEAT-004 事实要求

- FEAT-004 §2.1：本地目录维护 / 远程调用故障面。
- FEAT-004 §6 FR-REL-01（MUST）：远端执行中崩溃或连接重置时，父任务有界收敛到失败终态，不静默挂起、不假 `COMPLETED`。

## 前置条件

1. 被测 jar 就绪：`expense-review-main` + `expense-review-workflow`（与 `ExpenseReviewAcceptanceTest` 同栈）。
2. `-Dtest.env=openjiuwen` + `SAA_*` / `LLM_API_KEY`。
3. 故障注入就绪：workflow agent 声明 `cardEndpointRedirect`（Agent Card endpoint 经 `FaultLink`/Toxiproxy 代理）；进程类故障经 `SutStack.stop("expense-review-workflow")`。

## 测试数据

- 载体输入：超标报销（把父任务推进到远程执行 `WORKING`，使故障注入落在远程调用进行中）。
- 故障注入点：workflow agent 进程（崩溃）/ main→workflow 的 SSE 连接（重置）。

## 故障参数表（同一编排链路，两类 downstream 故障）

| 方法 | 故障形态 | 注入手段 | 预期父任务终态 |
|---|---|---|---|
| `remoteAgentKilledMidExecutionFailsParent` | 远端崩溃 | 远程执行中 `SutStack.stop("expense-review-workflow")` | FAILED/等义失败（有界，不挂起不假成功） |
| `remoteConnectionResetFailsParent` | 连接重置 | 远程执行中 `stack.faultLink("expense-review-workflow").resetPeer()` | FAILED/可诊断（有界，不挂起不假成功）；restore 后新任务可正常 |

## 测试步骤

> 两类故障合并为一个测试类（`ExpenseReviewRemoteOrchestrationFailureTest`）的两个 `@Test` 方法；按故障形态分层注入（进程类 vs 网络类）。入口协议固定 A2A_STREAM。

| # | 动作 | 预期 |
|---|------|------|
| 1 | main + workflow 拉起；workflow 端点经 FaultLink 代理（默认无故障）；客户端打 main（A2A_STREAM） | 栈就绪 |
| 2 | 提交超标报销，待事件流显示已进入远程执行（`WORKING`） | 父任务进入远程调用进行中 |
| 3a | **远端崩溃**：`SutStack.stop("expense-review-workflow")` 杀掉 workflow agent | 父任务有界收敛 FAILED/等义失败；不无限 WORKING、不假 COMPLETED；错误日志可定位远端调用失败 |
| 3b | **连接重置**：`stack.faultLink("expense-review-workflow").resetPeer()` 切断在途 SSE | 父任务有界收敛 FAILED/可诊断；不挂起、不假 COMPLETED；`restore()` 后新任务可正常完成 |

## 预期结果（机制断言）

### A — 远端崩溃父任务有界失败（不挂起、不假成功）
- **Given**：父任务已进入远程执行（`WORKING`）。
- **When**：`SutStack.stop("expense-review-workflow")` 杀掉 workflow agent。
- **Then**：父任务在可接受时限内有界收敛到 `FAILED`（或等义失败终态），不无限 `WORKING`、不错误收敛为 `COMPLETED`；错误日志可定位远端调用失败。
- **PASS**：有界失败。**FAIL**：静默挂起 / 假成功 COMPLETED（FR-REL-01 MUST 违反）。

### B — 连接重置父任务有界失败 + 可恢复
- **Given**：父任务已进入远程执行（`WORKING`），FaultLink 就位。
- **When**：`stack.faultLink("expense-review-workflow").resetPeer()` 切断在途 SSE。
- **Then**：父任务有界收敛到 `FAILED`/可诊断错误语义；不无限挂起、不假 `COMPLETED`；恢复代理（`restore()`）后新任务可正常完成。
- **PASS**：有界失败且可恢复。**FAIL**：静默挂起 / 假成功 / 不可恢复（可靠性机制失效）。

## 框架落点

| 项 | 值 |
|----|----|
| 测试类 | ⬜ `ExpenseReviewRemoteOrchestrationFailureTest`（新建，落 `cases/integration/workflow_call`，继承 `BaseManagedStackTest`） |
| 标签 | `@Tag("integration")`；Allure `@Feature("FEAT-004")` + stories `wf.orchestration-fault-kill` / `wf.orchestration-fault-reset`（待注册） |
| 基类 | `BaseManagedStackTest` + `SutStack`（`AgentConfig.cardEndpointRedirect` 声明代理、`faultLink(name)` 取链路注入/恢复、`stop()` 进程 kill） |
| 客户端 | `InteractionFlow`（A2A_STREAM 提交与事件收集） |
| 故障注入 | 进程类 `SutStack.stop(name)`；网络类 `FaultLink.resetPeer()`/`restore()`（现有 `ToxiproxyFaultLink`） |

## 运行方式

```bash
# ⬜ 待新建；故障用例需 LLM（超标报销业务）
./mvnw test -Dtest=ExpenseReviewRemoteOrchestrationFailureTest -Dtest.env=openjiuwen \
  -DLLM_API_BASE=... -DLLM_API_KEY=... -DLLM_MODEL=...
```

## 覆盖追溯

| FEAT-004 子用例（机制能力） | 本用例子断言 | 状态 |
|------|--------|------|
| §2.1/§6 FR-REL-01 远端崩溃父有界失败 | A | ⬜ 待新建 |
| §2.1/§6 FR-REL-01 连接重置父有界失败 + 可恢复 | B | ⬜ 待新建 |

## 清理策略

- 栈由类级生命周期管理；被 kill 的 workflow 进程由 `SutStack.stop()` 收尾；FaultLink 代理随栈销毁，`restore()` 确保代理干净。

## 风险与备注

- **远端崩溃后父任务终态依赖 SUT 错误路径**：与 deepagent 档 `RemoteStreamTimeoutTest` 观测一致，agent-runtime 基础错误路径可能缺失，本类故障用例首跑可能用于暴露同类问题；断言分层为「有界失败 + 不假 COMPLETED」下限，具体错误码/状态可随 SUT 修复校准（设计档风险 3）。
- **FaultLink 仅支持 resetPeer/restore**：当前 `ToxiproxyFaultLink` 提供 `resetPeer()`/`restore()`；latency toxic 尚未暴露。本档超时语义不新建用例（归 deepagent 档 `RemoteStreamTimeoutTest`）；如需确定性编排超时，先扩展 `FaultLink` 暴露 latency/timeout toxic（设计档风险 2）。
- **与 FEAT-002 versatile-remote-reliability 边界**：[FEAT-002 versatile-remote-reliability](FEAT-002-versatile-remote-reliability.md) 断 **adapter→远端 versatile 控制器链路**的故障收敛；本用例断 **main→workflow A2A 编排链路**的故障收敛——两条不同远程链路，同一 FR-REL-01 MUST 语义在不同编排层。
- **取消级联边界**：父任务主动取消（非故障）收敛 CANCELED 是另一机制，归 [cancel-cascade](FEAT-004-cancel-cascade.md)。
