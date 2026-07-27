---
feature_id: FEAT-004
feature_title: 任务驱动远程智能体调用
sut: WorkflowAgent（expense-review-main / edpa-plan-agent 远程编排链路）
scope: 本档覆盖 WorkflowAgent SIT 侧可外部黑盒断言的 FEAT-004 事实要求。story 1（A2A 远端智能体调用 + 任务全生命周期中断与接续）已由 workflow_call 现有用例覆盖；剩余编排层业务可达故障语义按 `<agent><Capability>Test` 规则命名并标记待补齐。story 2（任务驱动并行子任务）归 deepagent 档，不在本文展开
status: designed
owner: TBD
tags: [integration, workflowagent, feat-004, remote-orchestration]
depends_on:
  - openjiuwen profile（-Dtest.env=openjiuwen，需 LLM_API_KEY 等 SAA_* 环境变量）
  - 被测 jar 就绪：expense-review-workflow、edpa-plan-agent、edpa-adapter、envexplorer
  - 编排超时 / 取消语义配置键确认（ExpenseReviewRemoteOrchestrationFailureTest 前置）
related_docs:
  - FEAT-004 特性文档（version-scope，外部契约）：`spring-ai-ascend/version-scope/FEAT-004-remote-agent-orchestration.md`
  - 姊妹篇（FEAT-001 入口/框架分工边界）：`FEAT-001-standardized-agent-service-entrypoint-workflow.md`
  - 姊妹篇（FEAT-002 异构框架）：`FEAT-002-heterogeneous-agent-framework-compatibility-workflow.md`
  - 姊妹篇（FEAT-003 状态缓存）：`FEAT-003-agent-task-state-cache-workflow.md`
  - 同 feature DeepAgent 设计档：`FEAT-004-remote-agent-orchestration-deepagent.md`
  - 本仓 SIT 现状总述：`../SIT.md`
  - 框架/基础设施用法：`../framework/STATUS.md`（§4 配置体系、§6 基础设施、§7 协议参数化、§11 引入新 SUT）
---

# FEAT-004 任务驱动远程智能体调用 — WorkflowAgent SIT 测试设计

> **一句话**：以 WorkflowAgent 为对象，把 FEAT-004 §2 能力表 MUST 项、§6 可靠性/取消语义映射为可黑盒断言的子用例，并按 story 标注实现/覆盖现状（2026-07-22 登记）：story 1 的正常编排链路已由 `workflow_call` 用例覆盖（`ExpenseReviewAcceptanceTest`、`PlanAgentDirectStreamingTest`）；协议/入口层与框架/控制器层剩余覆盖归 FEAT-001/FEAT-002 档；本档剩余缺口收敛为 **编排层业务可达故障语义** ⬜ 待新建（1 类 2 方法）、**取消级联传播** 🚧 后续落地（1 方法 @Disabled 骨架）；嵌套远程调用与 REMOTE_TIMEOUT 已有 deepagent 侧 watchdog，不重复建设；story 2 归 deepagent 档。

> **组织原则（与 FEAT-001/002/003 档一致）**：
> 1. **同类项合并到单一测试类**：同一被测面的多条事实要求合并为一个测试类的多个 `@Test` 方法（如 A2A 编排链路的远端中断/取消 → `ExpenseReviewRemoteOrchestrationFailureTest` 多方法聚合），不逐条独立成类。
> 2. **非法输入场景与 SDK 错误码验证不在 SIT 考虑**：parse error / invalid request / method-not-found / not-found 等输入校验与 A2A SDK 错误码语义属 JSON-RPC 分发层与 SDK 的组件级关注点，归 component 层测试；SIT 不建任何非法输入用例，也不断言具体错误码。**SIT 的故障覆盖只采纳逻辑合理的故障模拟**——WorkflowAgent 处于 A2A 编排调用链的父端，其 downstream 故障（网络、远端崩溃）是本档的合理覆盖对象。
> 3. **业务流程优先，传输层能力不单独建用例**：SIT 的核心是以业务流程串通整体逻辑。SSE wire 帧格式、Agent Card 发现、getTask 一致性在传输层/入口层已记录完整日志或由 FEAT-001 档覆盖；不单独建用例。

> **落地分层约定（2026-07-22 按实现现状登记）**：业务流程面（story 1 正常编排链路、待补齐的编排层故障语义）留在 `cases/integration/workflow_call`；REMOTE_TIMEOUT 语义与嵌套远程调用禁止已有 `cases/integration/deepagent_deepresearch` 同型 watchdog 覆盖，本档不重复建设。

**状态含义**：
- **runnable**：被测能力已实现，可直接落地实现
- **partial**：核心路径可测，某些断言维度受限
- **deferred**：依赖能力在整个栈上缺失，待补齐后回归

## 1. 文档说明

本文档承接 [`FEAT-004-remote-agent-orchestration.md`](../../third_party/spring-version-scope/version-scope/FEAT-004-remote-agent-orchestration.md)，针对其中涉及 **WorkflowAgent** 的范围输出 SIT 测试设计。

**两种 Workflow 远程编排链路**：

- **本地 workflow A2A 编排链路**：`expense-review-main`（ReAct 主控）→ YAML 静态配置 → Agent Card 拉取 → 远程工具 `review_expense` → `expense-review-workflow`（8 节点 DAG）。中断-续接流水线：远程 `INPUT_REQUIRED` → 父 Task 挂起 → 用户输入 → 续写恢复。**已由 `ExpenseReviewAcceptanceTest` 覆盖（Allure 注册在 FEAT-004）。**
- **远端 Versatile 编排链路**：`edpa-plan-agent` → `edpa-adapter` → 远端 versatile 服务（envexplorer）。多轮中断续接的业务串通。**已由 `PlanAgentDirectStreamingTest` 覆盖（Allure 注册在 FEAT-004）**；其框架/控制器层故障语义（不可达/超时/中断/取消）归 FEAT-002 档。

**SIT 设计原则**（与 FEAT-001/002/003 档一致）：业务流程串通为核心；同类关注点合并到单一测试类多 `@Test` 方法；非法输入与 A2A SDK 错误码验证归 component 层；故障注入只采纳业务中**逻辑合理、可达**的点。**网络类故障（连接重置）经框架 `cardEndpointRedirect` + `FaultLink.resetPeer()` 注入**，**进程类故障（远端崩溃）经 `SutStack.stop()` 注入**；超时语义与嵌套远程调用已有 deepagent 侧 watchdog，本档不重复建设；取消级联被测 runtime 未实现，先落 `@Disabled` 骨架。

**关联文档**：

- FEAT-004 特性文档（version-scope，外部契约）：[`spring-ai-ascend/version-scope/FEAT-004-remote-agent-orchestration.md`](../../third_party/spring-version-scope/version-scope/FEAT-004-remote-agent-orchestration.md)
- 姊妹篇（FEAT-001 入口/框架分工边界）：[`FEAT-001-standardized-agent-service-entrypoint-workflow.md`](FEAT-001-standardized-agent-service-entrypoint-workflow.md)
- 姊妹篇（FEAT-002 异构框架）：[`FEAT-002-heterogeneous-agent-framework-compatibility-workflow.md`](FEAT-002-heterogeneous-agent-framework-compatibility-workflow.md)
- 姊妹篇（FEAT-003 状态缓存）：[`FEAT-003-agent-task-state-cache-workflow.md`](FEAT-003-agent-task-state-cache-workflow.md)
- 同 feature DeepAgent 设计档：[`FEAT-004-remote-agent-orchestration-deepagent.md`](FEAT-004-remote-agent-orchestration-deepagent.md)（含 `RemoteStreamTimeoutTest`、`NestedRemoteInvocationRefusalTest`）
- 本仓 SIT 现状总述：[`../SIT.md`](../../SIT.md)
- 框架/基础设施用法：[`../framework/STATUS.md`](../framework/STATUS.md)（§4 配置体系、§6 基础设施、§7 协议参数化、§11 引入新 SUT）
- component 层取消先例：[`src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskCancelStreamTest.java`](../../src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskCancelStreamTest.java) / [`AgentTaskCancelSyncTest.java`](../../src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskCancelSyncTest.java)

## 2. 覆盖矩阵

### 2.1 Story 1 — A2A 远端智能体调用 + 任务全生命周期中断与接续

| Feature 需求 | workflow 侧验证设计 | 用例 | 状态 |
|---|---|---|---|
| YAML 配置远程端点 / Agent Card 启动拉取 / RemoteAgentToolSpec 生成与 Tool 安装 | expense-review-main 经 YAML `remote-agents[0]` 指向 workflow agent；业务证据 = LLM 成功调用远程工具 `review_expense` 完成审核 | `workflow_call/ExpenseReviewAcceptanceTest`（场景 1/2，四协议参数化；**Allure 注册在 FEAT-004**，stories `wf.agent-once` / `wf.agent-twoturn`） | ✅ 已实现（workflow_call） |
| 远程 A2A 调用（SendStreamingMessage 独立 streaming） | main → workflow 远程调用，客户端可见流式状态轨迹 `SUBMITTED → WORKING → …` | `ExpenseReviewAcceptanceTest` 场景 1/2（A2A_STREAM 参数） | ✅ 已实现 |
| 中断-续接（远程 `INPUT_REQUIRED` → 父挂起 → 用户输入 → 续写） | 超标报销 → workflow Questioner `INPUT_REQUIRED` 透传至客户端 → 续 `approved` → 恢复执行至 `COMPLETED` | `ExpenseReviewAcceptanceTest` 场景 1 | ✅ 已实现 |
| 结果回灌（远程 `COMPLETED` → 本地 resume） | 续写后父任务 `COMPLETED`，`workflow_final` 审核报告经 main 汇总返回客户端 | `ExpenseReviewAcceptanceTest` 场景 1/2 | ✅ 已实现 |
| 父 Task 进度投射（中断形态） | 远程 `INPUT_REQUIRED` 进度透传到父任务事件流（客户端可见审批提示） | `ExpenseReviewAcceptanceTest` 场景 1 | ✅ 已实现 |
| Versatile 远端链路多轮中断续接 | plan-agent → adapter → 远端 versatile：查余额 + 转账 stepUi 多轮交互到终态 | `workflow_call/PlanAgentDirectStreamingTest` / `PlanAgentDirectStreamingRedisTest`（**Allure 注册在 FEAT-004**，stories `wf.verstaile-once` / `wf.verstaile-input-required`） | ✅ 已实现 |
| 远端 A2A 编排链路：运行中远端崩溃 | 进程形态 `SutStack.stop("expense-review-workflow")` 杀掉 workflow agent；父任务有界失败、不静默挂起、不假 `COMPLETED` | ⬜ `workflow_call/ExpenseReviewRemoteOrchestrationFailureTest::remoteAgentKilledMidExecutionFailsParent` | ⬜ 待新建 |
| 远端 A2A 编排链路：连接重置 | 网络形态 `FaultLink.resetPeer()` 切断 main→workflow 连接；父任务有界收敛到 FAILED/可诊断错误语义 | ⬜ `ExpenseReviewRemoteOrchestrationFailureTest::remoteConnectionResetFailsParent` | ⬜ 待新建 |
| 取消级联传播（父 cancel → 远程 CancelTask） | 父任务在远程调用进行中发起取消：父收敛到 `CANCELED`；远端 workflow Task 收到取消，不成孤儿 | 🆕 `workflow_call/ExpenseReviewRemoteOrchestrationFailureTest::cancelCascadesToRemoteTask` | 🚧 后续落地（被测 runtime 当前未实现取消逻辑，用例落 `@Disabled` 骨架，待 feature 侧实现后启用） |

### 2.2 Story 1 — 已归其他档或已有 watchdog 的覆盖点

| Feature 需求 | 归属 | 理由 |
|---|---|---|
| 四协议入口串通 / AgentCard 发现 / getTask 一致性 / 尾斜杠等价 | FEAT-001 档 | 入口层语义；`ExpenseReviewWorkflowDirectAcceptanceTest`（FEAT-001 注册）已覆盖直连 workflow 入口四协议；`PlanAgentReactiveQueryTest`（FEAT-001 注册，当前 `@Disabled`）覆盖 reactive/MVC query 等价 |
| Versatile 控制器链路故障（不可达/超时/中断）+ 本地协作式取消 | FEAT-002 档 | 那是 adapter→远端 versatile 服务的控制器链路；本档是 main→workflow 的 **A2A 编排链路**，两条不同远程链路 |
| REMOTE_TIMEOUT 语义（远端 SSE 长时间无数据，父任务主动收束并 best-effort 取消孤儿 Task） | deepagent 档 `RemoteStreamTimeoutTest` | 已有 bug-watchdog 覆盖，不重复建设 |
| 嵌套远程调用禁止（`NESTED_REMOTE_INVOCATION_UNSUPPORTED`） | deepagent 档 `NestedRemoteInvocationRefusalTest` | 已有 spec-⬜ watchdog 覆盖，不重复建设 |

### 2.3 Story 2 — 任务驱动并行子任务 → 归 deepagent 档

| 内容 | 处置 |
|---|---|
| 任务驱动下游智能体并行执行子任务 | 由 deepagent 并行子任务调用覆盖，**不在本档展开**；feature §2.1 亦标明 Graph/Parallel 编排当前版本 ⬜（仅支持单层远程调用） |

### 2.4 显式不支持 / 排除项 — 处置结论

| 项（feature 出处） | 处置 |
|---|---|
| 嵌套远程调用（⬜，`NESTED_REMOTE_INVOCATION_UNSUPPORTED`） | 显式不支持项 + 错误码语义，已有 deepagent 档 watchdog；不建 workflow 业务用例 |
| 动态服务发现 / 远程负载均衡 / 远程调用认证（§2.2 排除） | 非编排层职责，不建 SIT 用例 |
| Agent Card 自适应刷新 / 本地目录故障降级细节 | 机制未在 feature 明确定义，⏸ deferred，待 L2 明确刷新触发条件后补设计 |
| Metadata 转发（入站 → 出站远程调用） | 线层行为，经传输层日志核查，不建独立业务用例 |
| 无 skills 的 Agent Card 不注入为 Tool | 配置生成规则，归 component 层 |

## 3. 状态分布

| 状态 | 数量 | 内容 |
|---|---|---|
| ✅ 已实现（本档纳入） | 2 类 + 参数化场景 | `ExpenseReviewAcceptanceTest`（编排串通 + 中断续接，四协议）、`PlanAgentDirectStreamingTest`（Versatile 远端链路，A2A_STREAM/REST_QUERY） |
| ⬜ 待新建 | 1 类 / 2 方法 | `ExpenseReviewRemoteOrchestrationFailureTest::remoteAgentKilledMidExecutionFailsParent`、`remoteConnectionResetFailsParent` |
| 🚧 后续落地 | 1 方法 | `ExpenseReviewRemoteOrchestrationFailureTest::cancelCascadesToRemoteTask`（被测 runtime 未实现取消逻辑） |
| ⏸ deferred | 1 | Agent Card 自适应刷新与故障降级细节（待 L2 明确） |
| — 处置项 | 4 | 嵌套远程（deepagent watchdog）、排除项（发现/负载/认证）、Metadata 转发（线层核查）、无 skills 不注入（component） |
| ↗ 归他档 | 3 | FEAT-001 入口层（`ExpenseReviewWorkflowDirectAcceptanceTest`、`PlanAgentReactiveQueryTest`）、FEAT-002 Versatile 链路故障/取消、deepagent 档 REMOTE_TIMEOUT / 嵌套远程 watchdog |

说明：workflow_call 现有用例已覆盖 story 1 的正常 A2A 编排链路（expense-review）与 Versatile 远端链路（plan-agent）。本档新增建设仅 1 个类 `ExpenseReviewRemoteOrchestrationFailureTest`（2 方法 ⬜ 待新建 + 1 方法 🚧 后续落地），其余覆盖点归 FEAT-001/002/deepagent 档或 component 层。

## 4. 进度看板

| 分组 | 用例 | 状态 | 备注 |
|---|---|---|---|
| workflow_call 已实现 / A2A 编排链路 | `ExpenseReviewAcceptanceTest` | ✅ 已实现 | stories `wf.agent-once` / `wf.agent-twoturn`；四协议参数化 |
| workflow_call 已实现 / Versatile 远端链路 | `PlanAgentDirectStreamingTest` / `PlanAgentDirectStreamingRedisTest` | ✅ 已实现 | stories `wf.verstaile-once` / `wf.verstaile-input-required`；A2A_STREAM/REST_QUERY |
| P1 新建 | `ExpenseReviewRemoteOrchestrationFailureTest`（2 个 `@Test`） | ⬜ 待新建 | `remoteAgentKilledMidExecutionFailsParent`（stop 杀 workflow agent）、`remoteConnectionResetFailsParent`（FaultLink resetPeer） |
| 后续落地 | `ExpenseReviewRemoteOrchestrationFailureTest::cancelCascadesToRemoteTask` | 🚧 blocked | 被测 runtime 未实现取消逻辑；先落 `@Disabled` 骨架，feature 实现后启用 |
| — 归 FEAT-001 | `ExpenseReviewWorkflowDirectAcceptanceTest`、`PlanAgentReactiveQueryTest` | ✅ 已实现 / ⏸ disabled | 入口层与协议等价，不在本档重复 |
| — 归 FEAT-002 | `TransferAfterBalanceAcceptanceTest`、`MultiWorkflowDirectStreamingTest` | ✅ 已实现 / ⏸ disabled | Versatile/gateway 链路，归 FEAT-002 档 |
| — 归 deepagent 档 | `RemoteStreamTimeoutTest`、`NestedRemoteInvocationRefusalTest` | ✅/⬜ watchdog | REMOTE_TIMEOUT 语义与嵌套远程调用禁止 |
| deferred | Agent Card 自适应刷新与故障降级细节 | ⏸ deferred | 待 L2 明确 |

## 5. 子用例设计（G/W/T）

### 5.1 ExpenseReviewRemoteOrchestrationFailureTest（🆕 新建，P1 + 后续落地 1）

**FSD 需求条目**：FEAT-004 §2.1"本地目录维护 / 远程调用故障面"、§6 FR-REL-01 中断检测（MUST）、FR-REL-02 协作式取消（MUST）。

**对应验收标准**：远端执行中崩溃或连接重置时，父任务有界收敛到失败终态，不静默挂起、不假 `COMPLETED`；（后续）父任务取消时远端 Task 同步取消，不成孤儿。

**覆盖业务链路**：客户端 → `expense-review-main`（ReAct 主控）→（FaultLink / 进程 kill）→ 远程 A2A 调用 → `expense-review-workflow`（8 节点 DAG）。

**拓扑与栈**：与 `ExpenseReviewAcceptanceTest` 同栈（`expense-review-main` + `expense-review-workflow`），客户端打 main；入口协议固定 **A2A_STREAM**（多轮 `INPUT_REQUIRED` 唯一可靠呈现的协议，继承既有结论）。workflow agent 声明 **cardEndpointRedirect**，使其对外 Agent Card endpoint 经 `FaultLink`（Toxiproxy）代理——main→workflow 的远程调用全部流经代理，测试经 `stack.faultLink("expense-review-workflow")` 注入/恢复网络故障。进程类故障经 `SutStack.stop("expense-review-workflow")` 直接杀掉 workflow agent 进程。本类继承 `BaseManagedStackTest`。

**用例设计思路**：按故障形态分层注入。**远端崩溃**：`SutStack.stop()` 在任务进入远程执行后杀掉 workflow agent，验证父任务不挂起、不假成功；**连接重置**：`FaultLink.resetPeer()` 在途切断 main→workflow 的 SSE 连接，验证父任务有界失败；**取消级联**：被测 runtime 当前未实现取消逻辑，方法先落 `@Disabled` 骨架（G/W/T 与取证方式先行定义），待 feature 侧实现后启用。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `remoteAgentKilledMidExecutionFailsParent` | main + workflow 拉起；workflow 端点经 FaultLink 代理 | 提交超标报销，待事件流显示已进入远程执行（`WORKING`）→ `SutStack.stop("expense-review-workflow")` 杀掉 workflow agent | 父任务在可接受时限内有界收敛到 `FAILED`（或等义失败终态），不无限 `WORKING`、不错误收敛为 `COMPLETED`；错误日志可定位远端调用失败 |
| `remoteConnectionResetFailsParent` | 同上栈（FaultLink 就位，默认无故障） | 提交超标报销，待事件流显示已进入远程执行（`WORKING`）→ `stack.faultLink("expense-review-workflow").resetPeer()` 切断在途 SSE | 父任务有界收敛到 `FAILED`/可诊断错误语义；不无限挂起、不假 `COMPLETED`；恢复代理（`restore()`）后新任务可正常完成 |
| `cancelCascadesToRemoteTask`（🚧 后续落地，`@Disabled` 骨架） | 同上栈；客户端提交超标报销 | 远程调用进行中（workflow 执行中）对父任务发起取消 | （启用后断言）父任务收敛到 `CANCELED`（或运行时等义终态）；远端 workflow Task 同步收到取消（远端 `getTask` 状态 `CANCELED` 或日志 `CancelTask` 证据）；两端均不残留 `WORKING` |

**依赖的外部组件 / fixture**：openjiuwen profile；框架 `FaultLink`/`ToxiproxyFaultLink`（现有 `resetPeer()`/`restore()`）；`SutStack.stop()` 进程 kill 能力。

**对应实现位置（新建）**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRemoteOrchestrationFailureTest.java`

**框架落点**：`BaseManagedStackTest` + `SutStack`（`AgentConfig.cardEndpointRedirect` 声明代理、`faultLink(name)` 取链路注入/恢复、`stop()` 进程 kill）；`InteractionFlow`（A2A_STREAM 提交与事件收集）；取消面启用后复用 `A2aServiceClient.cancelTask(taskId)` 与 `cases/component/protocol/TaskCancelVerifiers.assertCancelAndGet(...)`。

**执行命令**：

```bash
./mvnw test -Dtest=ExpenseReviewRemoteOrchestrationFailureTest -Dtest.env=openjiuwen \
  -DLLM_API_BASE=... -DLLM_API_KEY=... -DLLM_MODEL=...
```

**状态**：2 方法 ⬜ 待新建；1 方法 🚧 后续落地（取消级联，被测 runtime 未实现）。

### 5.2 已实现参考（workflow_call，✅）

| 用例 | 覆盖要点 | 备注 |
|---|---|---|
| `ExpenseReviewAcceptanceTest` | main→workflow 远程编排串通：场景 1 超标 → `INPUT_REQUIRED` 透传 → 续 `approved` → `COMPLETED`；场景 2 合规直达；四协议参数化；流式状态轨迹断言于 A2A_STREAM | story 1 主覆盖；Allure 注册在 FEAT-004 |
| `PlanAgentDirectStreamingTest` / `PlanAgentDirectStreamingRedisTest` | Versatile 远端链路：plan-agent → adapter → 远端 versatile，多轮 stepUi 中断续接到终态 | Allure 注册在 FEAT-004；redis 变体同时归 FEAT-003 档 |

## 6. 备注与风险

### 6.1 与 FEAT-001 / FEAT-002 / deepagent 档的分工（防重复建设）

| 关注点 | 归属档 | 理由 |
|---|---|---|
| 四协议入口串通 / AgentCard 发现（客户端视角）/ getTask 一致性 / 尾斜杠 | FEAT-001 档 | 入口层语义；`ExpenseReviewWorkflowDirectAcceptanceTest`（FEAT-001）、`PlanAgentReactiveQueryTest`（FEAT-001，`@Disabled`）已覆盖 |
| Versatile 控制器链路故障（不可达/超时/中断）+ 本地协作式取消 | FEAT-002 档 | adapter→远端 versatile 服务的控制器链路；本档是 main→workflow 的 **A2A 编排链路** |
| **A2A 编排层可靠性（远端崩溃 / 连接重置 / 取消级联）** | **FEAT-004 本档** | 编排层特有语义，feature §2.1/§6 明名 |
| REMOTE_TIMEOUT 语义（远端 SSE 长时间无数据） | deepagent 档 `RemoteStreamTimeoutTest` | 已有 bug-watchdog，不重复建设 |
| 嵌套远程调用禁止 | deepagent 档 `NestedRemoteInvocationRefusalTest` | 已有 spec-⬜ watchdog，不重复建设 |
| story 2 并行子任务 | deepagent 档 | 用户约定；feature 亦标明当前仅单层 |
| Redis 状态缓存下的编排恢复 | FEAT-003 档 | 存储介质维度，与编排语义正交 |

### 6.2 风险

1. **取消逻辑未实现**：被测 runtime 当前无协作式取消实现，`cancelCascadesToRemoteTask` 以 `@Disabled` 骨架落盘（G/W/T 与双端取证方式已定义），feature 侧实现后启用并回归；FEAT-002 档本地协作式取消用例同此处置。
2. **FaultLink 仅支持 resetPeer/restore**：当前 `ToxiproxyFaultLink` 已提供 `resetPeer()`/`restore()`； latency toxic 尚未暴露。本档超时语义不新建用例（归 deepagent 档 `RemoteStreamTimeoutTest`），如后续 workflow 侧需要确定性编排超时验证，需先扩展 `FaultLink` 接口暴露 latency/timeout toxic。
3. **远端崩溃后父任务终态依赖 SUT 错误路径**：与 deepagent 档 `RemoteStreamTimeoutTest` 观测一致，agent-runtime 基础错误路径可能缺失，本类故障用例首跑可能用于暴露同类问题；断言分层为"有界失败 + 不假 COMPLETED"下限，具体错误码/状态可随 SUT 修复校准。
4. **远端取证方式**：孤儿取消/级联取消的远端证据优先 `getTask`（业务面），其次远端日志 `CancelTask` 行（诊断面）；二者择一稳定即可，避免双硬断言。
5. **story 与入口的耦合**：story 1 复用 FEAT-001 入口，若 FEAT-001 入口行为变更（如错误语义、终态集合），本档现有 workflow_call 用例需同步回归。
6. **文档中旧框架名残留风险**：本档 §5 已统一使用 `A2aServiceClient` / `InteractionFlow` / `SutStack` / `FaultLink` 等当前框架 API；后续维护若复制粘贴旧 FEAT 文档，需避免 `A2aClientCalls` / `RestClientCalls` / `SitClientRecorder` 等已废弃或不存在名称回流。
