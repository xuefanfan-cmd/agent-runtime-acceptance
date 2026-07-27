---
kind: appendix
title: FEAT-004（任务驱动远程智能体调用）扩展覆盖建议清单
feature: FEAT-004
适用环境: openjiuwen
创建日期: 2026-07-22
tags: [workflowagent, feat-004, remote-orchestration, appendix]
---

# FEAT-004 扩展覆盖建议（附录）

> **性质**：本文件**不是 TC 用例**，是 FEAT-004 各机制 TC 在「正常逻辑范围内」的可选扩展建议清单，
> 以及明确**不建用例的边界**（归他档 / 显式不支持 / 排除项 / deferred），供后续按优先级落地。核心机制
> 覆盖以 [remote-orchestration-link](FEAT-004-remote-orchestration-link.md) /
> [remote-interrupt-resume](FEAT-004-remote-interrupt-resume.md) /
> [orchestration-fault-convergence](FEAT-004-orchestration-fault-convergence.md) /
> [cancel-cascade](FEAT-004-cancel-cascade.md) 四个 TC 为准。

## 1. orchestration-fault-convergence 扩展：编排超时（确定性 latency 注入）

- **归属机制 TC**：[orchestration-fault-convergence](FEAT-004-orchestration-fault-convergence.md)（FR-REL-01）。
- **扩展内容**：当前故障面覆盖远端崩溃（进程 kill）+ 连接重置（resetPeer）；可补齐**编排超时**——main→workflow 远端 SSE 长时间无数据，父任务主动收束并 best-effort 取消孤儿 Task。
- **当前归属**：已有 deepagent 档 [`RemoteStreamTimeoutTest`](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RemoteStreamTimeoutTest.java) bug-watchdog 覆盖 REMOTE_TIMEOUT 语义，**workflow 侧不重复建设**。
- **解锁条件**：若 workflow 侧需确定性编排超时验证，先扩展框架 `FaultLink` 接口暴露 latency/timeout toxic（当前仅 `resetPeer()`/`restore()`）。
- **优先级**：P3（已有 deepagent watchdog 覆盖语义；workflow 侧为载体广度）。

## 2. cancel-cascade 扩展：远端 Versatile 编排链路的取消级联变体

- **归属机制 TC**：[cancel-cascade](FEAT-004-cancel-cascade.md)（FR-REL-02）。
- **扩展内容**：当前取消级联在**本地 main→workflow A2A 编排链路**验证；远端 Versatile 编排链路（plan-agent→adapter→versatile）的父→远端取消级联是否需补齐同型语义。
- **当前归属**：远端 Versatile 链路的故障/取消归 [FEAT-002 versatile-remote-reliability](FEAT-002-versatile-remote-reliability.md) / [cooperative-cancel](FEAT-002-cooperative-cancel.md) 档；本扩展若需，与 FEAT-002 档协调避免重复。
- **解锁条件**：被测 runtime 取消面实现 + 远端 Versatile 链路取消可观测（远端 getTask / 日志 CancelTask）。
- **优先级**：P3（同一 runtime 承接，本地链路已证级联语义；远端为载体广度）。

## 3. remote-orchestration-link 扩展：多远程端点编排（>1 remote-agents）

- **归属机制 TC**：[remote-orchestration-link](FEAT-004-remote-orchestration-link.md)（装配 + 调用）。
- **扩展内容**：当前 YAML `remote-agents[0]` 单远程端点装配；feature §2.1 当前版本仅支持单层远程调用，多远程端点（`remote-agents[1…]`）装配与 Tool 安装的可扩展性验证。
- **解锁条件**：feature §2.1 明确多远程端点支持（当前 ⬜ 仅单层）。
- **优先级**：deferred（依赖 feature 多端点能力落地）。
- **边界**：与 story 2 并行子任务不同——多远程端点是「一个父装配多个远程工具」，并行子任务是「任务驱动下游并行执行」（归 deepagent 档）。

## 4. 归他档的覆盖点 — 不在本档建 SIT（防重复建设）

| 关注点 | 归属档 | 理由 / 落点 |
|---|---|---|
| 四协议入口串通 / AgentCard 发现（客户端视角）/ getTask 一致性 / 尾斜杠 | FEAT-001 档 | 入口层语义；`ExpenseReviewWorkflowDirectAcceptanceTest`、`PlanAgentReactiveQueryTest`（`@Disabled`）已覆盖 |
| Versatile 控制器链路故障（不可达/超时/中断）+ 本地协作式取消 | FEAT-002 档 | adapter→远端 versatile **控制器链路**；本档是 main→workflow 的 **A2A 编排链路**，两条不同远程链路 |
| Redis 状态缓存下的编排恢复 | FEAT-003 档 | 存储介质维度，与编排语义正交；`PlanAgentDirectStreamingRedisTest` 同时归 FEAT-003 |
| REMOTE_TIMEOUT 语义（远端 SSE 长时间无数据） | deepagent 档 [`RemoteStreamTimeoutTest`](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/RemoteStreamTimeoutTest.java) | 已有 bug-watchdog，不重复建设 |
| 嵌套远程调用禁止（`NESTED_REMOTE_INVOCATION_UNSUPPORTED`） | deepagent 档 [`NestedRemoteInvocationRefusalTest`](../../../src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/NestedRemoteInvocationRefusalTest.java) | 已有 spec-⬜ watchdog，不重复建设 |
| story 2 任务驱动并行子任务 | deepagent 档 | 用户约定；feature §2.1 亦标明 Graph/Parallel 编排当前版本 ⬜（仅单层远程调用） |

## 5. 显式不支持 / 排除项 — 不建 SIT（feature §2.2 / §2.4）

| 项（feature 出处） | 处置 |
|---|---|
| 嵌套远程调用（⬜，`NESTED_REMOTE_INVOCATION_UNSUPPORTED`） | 显式不支持项 + 错误码语义，已有 deepagent 档 watchdog；不建 workflow 业务用例 |
| 动态服务发现 / 远程负载均衡 / 远程调用认证（§2.2 排除） | 非编排层职责，不建 SIT 用例 |
| Agent Card 自适应刷新 / 本地目录故障降级细节 | 机制未在 feature 明确定义，⏸ deferred，待 L2 明确刷新触发条件后补设计 |
| Metadata 转发（入站 → 出站远程调用） | 线层行为，经传输层日志核查，不建独立业务用例 |
| 无 skills 的 Agent Card 不注入为 Tool | 配置生成规则，归 component 层 |

## 6. 不建扩展（明示边界 / 组织原则排除项）

- **非法输入 / SDK 错误码**：parse error / invalid request / method-not-found / not-found 等输入校验与 A2A SDK 错误码语义——归 component 层（JSON-RPC 分发层与 SDK 组件级关注点，组织原则 2）。
- **SSE wire 帧格式 / Agent Card 发现 / getTask 一致性**：传输层/入口层已记录完整日志或由 FEAT-001 档覆盖，不单独建用例（组织原则 3）。
- **离谱故障注入**：SIT 故障覆盖只采纳逻辑合理、可达的点——WorkflowAgent 处于 A2A 编排调用链父端，其 downstream（远程 agent）故障（远端崩溃/连接重置）是合理覆盖对象；不引入非业务可达的注入。

## 7. 解锁路线图（按依赖排序）

| 阶段 | 解锁项 | 解锁后可启用 | 优先级 |
|---|---|---|---|
| 1 | `cardEndpointRedirect` + `FaultLink` resetPeer/restore + `SutStack.stop()` 就位 | [orchestration-fault-convergence](FEAT-004-orchestration-fault-convergence.md) 2 方法 ⬜→✅ | P1 |
| 2 | 被测 runtime 取消面实现（A2A `tasks/cancel`） | [cancel-cascade](FEAT-004-cancel-cascade.md) 🚧→✅（移除 `@Disabled` 骨架）+ 扩展 2 远端级联变体 | P1 |
| 3 | `FaultLink` 暴露 latency/timeout toxic | 扩展 1 编排超时（workflow 侧确定性验证） | P3 |
| 4 | feature §2.1 多远程端点支持落地 | 扩展 3 多远程端点装配 | deferred |
| 5 | Agent Card 自适应刷新触发条件（L2 明确） | deferred 项 Card 刷新/故障降级补设计 | deferred |
