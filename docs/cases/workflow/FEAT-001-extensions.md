---
kind: appendix
title: FEAT-001（WorkflowAgent）扩展覆盖建议清单
feature: FEAT-001
适用环境: openjiuwen
创建日期: 2026-07-22
tags: [workflowagent, feat-001, appendix]
---

# FEAT-001 扩展覆盖建议（附录）

> **性质**：本文件**不是 TC 用例**，是 FEAT-001 各机制 TC 在「正常逻辑范围内」的可选扩展建议清单，
> 供后续按优先级落地。每条标注归属的机制 TC、扩展理由与前置解锁条件。核心机制覆盖以
> [agent-card](FEAT-001-agent-card.md) / [message-ingress](FEAT-001-message-ingress.md) /
> [task-lifecycle](FEAT-001-task-lifecycle.md) / [input-required](FEAT-001-input-required.md) /
> [task-get](FEAT-001-task-get.md) / [entry-source-equivalence](FEAT-001-entry-source-equivalence.md) /
> [metadata-propagation](FEAT-001-metadata-propagation.md) 七个 TC 为准。

## 1. message-ingress 扩展：reactive/MVC 流式等价（story 2）

- **归属机制 TC**：[message-ingress](FEAT-001-message-ingress.md)（`wf.rest-query` / `wf.rest-a2a-equivalence`）。
- **扩展内容**：`POST /v1/query/reactive`（WebFlux reactive）与 `POST /v1/query`（MVC 阻塞）流式输出口径等价对照——同一消息在 reactive/MVC 两条 REST 实现路径上结果一致。
- **落点**：[PlanAgentReactiveQueryTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentReactiveQueryTest.java)（类级 `@Disabled`）。
- **解锁条件**：重建 `webflux.enabled=true` 的载体 jar（当前 jar 下 `REST_REACTIVE` 404）；移除类级 `@Disabled` 后纳入 message-ingress 协议矩阵。
- **优先级**：P2（传输实现路径等价，非入口语义必须）。

## 2. task-get 扩展：Redis TaskStore 下快照可查（交叉 FEAT-003）

- **归属机制 TC**：[task-get](FEAT-001-task-get.md)（`wf.get-task`）。
- **扩展内容**：redis profile 栈上 taskId 落 Redis TaskStore（`a2a:task` 键），`GetTask` 走 Redis 路径仍返一致快照；进一步可验证进程重启后快照可查（持久化语义）。
- **落点**：⬜ `ExpenseReviewWorkflowTaskGetTest` 搭 redis profile 栈（复用 [PlanAgentDirectStreamingRedisTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingRedisTest.java) 已证的 `a2a:task` 键）。
- **解锁条件**：task-get 主类落地 + redis profile 栈。
- **优先级**：P2（增强项，非 FEAT-001 必须；持久化归属 FEAT-003）。

## 3. entry-source-equivalence 扩展：网关下行 a2a/rest 双模式细化

- **归属机制 TC**：[entry-source-equivalence](FEAT-001-entry-source-equivalence.md)（`wf.entry-source-equivalence` 拓扑④）。
- **扩展内容**：`edpa-gateway` 下行 a2a（`/a2a` w/ taskId resume）vs rest（`/v1/query` 无 taskId）两种转发模式的来源等价细化对照。
- **落点**：[TransferAfterBalanceAcceptanceTest](../../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/TransferAfterBalanceAcceptanceTest.java)（`-DGATEWAY_PROTOCOL=a2a|rest` 跑两遍）。
- **解锁条件**：已可运行；转发语义本身的验收归 FEAT-011/012，本扩展只断「转发复用标准化入口」。
- **优先级**：P2。

## 4. 机制载体细化：近端 workflow / 远端 versatile 全机制对齐

- **归属机制 TC**：全部七个（机制层契约对两类载体同构）。
- **扩展内容**：当前各机制 TC 主载体为近端 workflow（`expense-review-workflow`）；可补齐远端 versatile（`edpa-adapter` / gateway 后 versatile workflow）在同一机制上的对齐证据——尤其 [task-lifecycle](FEAT-001-task-lifecycle.md) / [input-required](FEAT-001-input-required.md) / [task-get](FEAT-001-task-get.md) 在远端 versatile 载体上的机制行为（若远端载体具备同型节点/查询入口）。
- **解锁条件**：远端 versatile 载体暴露对应机制（如 Questioner 类中断节点、tasks/get 入口）。
- **优先级**：P3（载体覆盖广度，非机制正确性必须）。

## 5. story 3 webhook 传输层落地后的参数化展开（deferred）

- **归属机制 TC**：[message-ingress](FEAT-001-message-ingress.md) / [input-required](FEAT-001-input-required.md)（协议矩阵扩展一格）。
- **扩展内容**：webhook 传输层落地后，作为新协议值（`A2A_WEBHOOK`，与 `A2A_STREAM`/`A2A_SYNC`/`REST_QUERY`/`REST_QUERY_SYNC` 同型）接入 `MessageProtocol` 参数化体系——在现有业务场景的协议矩阵中扩展一格即完成 webhook 维度覆盖，**不新增独立 TC**。
- **当前守门**：[agent-card](FEAT-001-agent-card.md) 断言 C（`pushNotifications=false`）在岗，防 story 3 能力夸大。
- **解锁条件**：webhook 接口契约定义（注册面 / 回调 payload / notification id / 阈值 / 信任模型）+ 传输层落地。
- **优先级**：deferred（依赖 story 3 落地）。

## 6. 不建扩展（明示边界）

下列按 FEAT-001 组织原则**不提出扩展**，避免越界：

- **JSON-RPC 错误面 / SDK 错误码**：parse error / invalid request / method-not-found / id 回显 / 未知 taskId not-found / 空文本拒绝 / 坏 REST body——归 component 层（组织原则 2）。
- **SSE wire 帧格式孤立断言**：传输层日志可核查，不建独立 TC（组织原则 3）。
- **downstream 故障 mid-stream / 下游超时 / task-failed-semantics**：近端 workflow 是调用链最下游，无 downstream 可注；归后续 versatile / plan-agent 侧 SIT（组织原则 2，仅采纳逻辑合理的故障注入）。
- **`CancelTask` / `ListTasks` / `SubscribeToTask` / gRPC northbound / agent-bus 私有入口 / 认证授权**：FEAT-001 §5.2 明示 OUT。
