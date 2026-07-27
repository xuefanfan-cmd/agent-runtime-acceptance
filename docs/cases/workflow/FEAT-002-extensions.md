---
kind: appendix
title: FEAT-002（异构智能体框架兼容适配）扩展覆盖建议清单
feature: FEAT-002
适用环境: openjiuwen
创建日期: 2026-07-22
tags: [workflowagent, versatile, feat-002, appendix]
---

# FEAT-002 扩展覆盖建议（附录）

> **性质**：本文件**不是 TC 用例**，是 FEAT-002 各机制 TC 在「正常逻辑范围内」的可选扩展建议清单，
> 以及明确**不建用例的边界**（deferred / 归 component 层 / 组织原则排除项），供后续按优先级落地。
> 核心机制覆盖以 [framework-ingress-compat](FEAT-002-framework-ingress-compat.md) /
> [multi-workflow-routing](FEAT-002-multi-workflow-routing.md) /
> [versatile-remote-reliability](FEAT-002-versatile-remote-reliability.md) /
> [cooperative-cancel](FEAT-002-cooperative-cancel.md) 四个 TC 为准。

## 1. framework-ingress-compat 扩展：近端 workflow 远端 versatile 框架兼容对齐

- **归属机制 TC**：[framework-ingress-compat](FEAT-002-framework-ingress-compat.md)（异构框架入口适配）。
- **扩展内容**：当前框架载体表覆盖 workflow / deepagent / ReAct 三类；可补齐**远端 versatile**（经 gateway 后的 versatile workflow）作为第四类框架载体，验证同一标准化入口对远端 versatile 框架的兼容驱动。
- **解锁条件**：远端 versatile 载体暴露标准化入口（A2A/REST），并具备可独立断言的终态语义。
- **优先级**：P3（框架覆盖广度，非机制正确性必须）。

## 2. versatile-remote-reliability 扩展：故障注入面细化（限逻辑合理面）

- **归属机制 TC**：[versatile-remote-reliability](FEAT-002-versatile-remote-reliability.md)（FR-REL-01 MUST）。
- **扩展内容**：在「不可达 / 超时 / 中断」三类合理故障面基础上，可补齐**慢速滴漏（slow drip）**——Versatile 端点可达但极低吞吐（经 `FaultLink` 注入 `bandwidth` 限制），验证父侧在长尾延迟下的收敛行为不退化。
- **解锁条件**：`VersatileRemoteFailureTest` 主类落地 + `FaultLink` 支持 bandwidth 类故障。
- **优先级**：P3（增强项；**只采纳逻辑合理的故障面**，不引入离谱注入）。
- **明示边界**：WorkflowAgent 处于调用链最下游（纯 workflow_call 链路对外仅 LLM 依赖），**其 downstream 故障/超时对本 SUT 不成立，不提扩展**（归 Versatile / plan-agent 侧 SIT）。

## 3. cooperative-cancel 扩展：多协议取消面参数化展开

- **归属机制 TC**：[cooperative-cancel](FEAT-002-cooperative-cancel.md)（FR-REL-02 MUST）。
- **扩展内容**：取消面（A2A `tasks/cancel` / REST 等价端点）落地后，按 FEAT-001 协议参数化惯例，把取消请求参数化到 A2A_STREAM / A2A_SYNC / REST_QUERY / REST_QUERY_SYNC 四协议（与 message-ingress 协议矩阵同型）——同一取消语义在四协议入口上行为一致。
- **解锁条件**：被测 runtime 取消逻辑实现 + 取消面四协议可达。
- **优先级**：P2（取消语义落地后顺带验证协议一致性）。

## 4. 上下文管理边界（FR-CTX-01/02/03）— deferred，不纳入扩展清单

- **归属**：FEAT-002 §2.2「上下文管理边界」、§6 FR-CTX-01/02/03。
- **状态**：⏸ **deferred**——不进扩展清单，原因：
  1. 依赖「用户可感知方式」的定义（多轮窗口截断 / compaction / 摘要触发的可观测面尚未明确）；
  2. 断言偏**内容质量**（摘要是否丢失关键信息），非黑盒终态语义，与 SIT 「业务流程串通」核心方法论不一致。
- **解锁条件**：上下文策略（截断阈值 / compaction 触发 / 摘要规则）+ 「用户可感知」可观测面（如 context-truncated 事件帧）明确后，单列 TC 承载。

## 5. 异构框架日志规范（NFR-OBS-01/02/03）— 归 component 层，不建 SIT

- **归属**：FEAT-002 §3.3 NFR-OBS-01/02/03。
- **状态**：— **归 component 层**，不在本档建 SIT 用例。原因：属**代码级日志断言**（日志级别 / 结构化字段 / 脱敏），建议在 component 层（`sprint-boot-version-scope` 适配层单测）验证。
- **SIT 间接依赖**：SIT 仅在用例通过性上间接依赖关键日志可用于故障定位（如故障用例排查时核查日志），不断言日志格式本身。

## 6. 不建扩展（明示边界 / 组织原则排除项）

下列按 FEAT-002 组织原则**不提出扩展**，避免越界：

- **非法输入场景 / A2A SDK 错误码验证**：parse error / invalid request / method-not-found / not-found / 空文本拒绝 / 坏 REST body 等——归 component 层（JSON-RPC 分发层与 SDK 组件级关注点，组织原则 2）。SIT 不建任何非法输入用例，也不断言具体错误码。
- **SSE wire 帧格式孤立断言**：传输层日志可核查，不建独立 TC（组织原则 3）。
- **webhook 传输层**：传输层抽象，待 story 3 落地后作为新协议值在各业务用例的参数化矩阵中展开复用，**不新增独立 TC**（组织原则 3）。
- **纯 workflow_call 链路的 downstream 故障**：WorkflowAgent 处于调用链最下游，无 downstream 可注（组织原则 2，仅采纳逻辑合理的故障注入）。
- **SIT 故障覆盖范围之外的能力**：FEAT-002 §6 其他未在 §2 覆盖矩阵登记的 MUST 项，待 feature 侧明确可黑盒观测面后再评估是否纳入。

## 7. 解锁路线图（按依赖排序）

| 阶段 | 解锁项 | 解锁后可启用 | 优先级 |
|---|---|---|---|
| 1 | Versatile SUT 部署形态明确（独立进程 / 内嵌）+ 配置入口 | [versatile-remote-reliability](FEAT-002-versatile-remote-reliability.md) 4 方法 ⬜→✅；故障注入手段对齐 | P1 |
| 2 | MultiWorkflowDirectStreamingTest 真机校准 6 点 | [multi-workflow-routing](FEAT-002-multi-workflow-routing.md) ⏸→✅（移除 `@Disabled`） | P1 |
| 3 | 被测 runtime 取消面实现（A2A `tasks/cancel` / REST 等价） | [cooperative-cancel](FEAT-002-cooperative-cancel.md) 🚧→✅（移除 `@Disabled` 骨架）+ 扩展 3 多协议参数化 | P1 |
| 4 | 上下文策略 + 「用户可感知」可观测面明确 | 扩展 4 上下文管理边界 deferred→TC | P3（deferred） |
