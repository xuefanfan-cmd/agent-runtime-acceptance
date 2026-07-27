---
feature_id: FEAT-002
feature_title: 异构智能体框架兼容适配
sut: WorkflowAgent / Versatile（openJiuwen workflow/deepagent/ReAct/versatile 控制器）
scope: 本档覆盖 WorkflowAgent / Versatile SUT 侧可外部黑盒断言的 FEAT-002 事实要求。story 1/2 的业务流程面已由 workflow_call 现有用例覆盖（FEAT-002 主注册类为 MultiWorkflowDirectStreamingTest，但当前 @Disabled；TransferAfterBalanceAcceptanceTest 为实际在跑 story-2 证据）；协议/控制器适配层剩余缺口收敛为：远程 Versatile 故障语义 ⬜ 待新建（1 类 4 方法）、协作式取消 🚧 后续落地（1 类 2 方法 @Disabled 骨架）、上下文管理边界 ⏸ deferred；日志规范归 component 层不建
status: designed
owner: TBD
tags: [integration, workflowagent, versatile, feat-002]
depends_on:
  - openjiuwen profile（-Dtest.env=openjiuwen，需 LLM_API_KEY 等 SAA_* 环境变量）
  - 被测 jar 就绪：expense-review-workflow、edpa-plan-agent、edpa-adapter、edpa-gateway、envexplorer（容器由 service-bindings 自动拉起）
  - 远程 Versatile 控制器部署形态与配置入口（VersatileRemoteFailureTest 前置）
  - 运行时取消面实现（WorkflowCancelExecutionTest 启用前置）
related_docs:
  - FEAT-002 特性文档（version-scope，外部契约）：`spring-ai-ascend/version-scope/FEAT-002-heterogeneous-agent-framework-compatibility.md`（0715 版本）
  - 姊妹篇（FEAT-001 分工边界）：[`FEAT-001-standardized-agent-service-entrypoint-workflow.md`](FEAT-001-standardized-agent-service-entrypoint-workflow.md)
  - 本仓 SIT 现状总述：[../SIT.md](../SIT.md)
  - 框架/基础设施用法：[../framework/STATUS.md](../framework/STATUS.md)（§4 配置体系、§6 基础设施、§7 协议参数化、§11 引入新 SUT）
  - 同 feature DeepAgent 设计档：[`FEAT-001-standardized-agent-service-entrypoint-deepagent.md`](FEAT-001-standardized-agent-service-entrypoint-deepagent.md)
---

# FEAT-002 异构智能体框架兼容适配 — WorkflowAgent / Versatile SIT 测试设计

> **一句话**：以 WorkflowAgent / Versatile（openJiuwen workflow/deepagent/ReAct/versatile 控制器）为对象，把 FEAT-002 §2 能力表 MUST 项、§6 可靠性/取消语义映射为可黑盒断言的子用例，并按 story 标注实现/覆盖现状（2026-07-22 登记）：story 1/2 的正常入口串通已由 workflow_call 用例覆盖（FEAT-002 主注册类为 `MultiWorkflowDirectStreamingTest`，但当前 `@Disabled("There is issue about this scenario")`；实际在跑的 story-2 证据为 `TransferAfterBalanceAcceptanceTest`）；协议/控制器适配层剩余缺口收敛为：远程 Versatile 故障语义 ⬜ 待新建（1 类 4 方法）、协作式取消 🚧 后续落地（1 类 2 方法 `@Disabled` 骨架）、上下文管理边界 ⏸ deferred；日志规范归 component 层不建。

> **组织原则（与 FEAT-001 档一致）**：
> 1. **同类项合并到单一测试类**：同一被测面的多条事实要求合并为一个测试类的多个 `@Test` 方法（如 Versatile 的不可达/超时/中断/正常链路 → `VersatileRemoteFailureTest` 多方法聚合），不逐条独立成类。
> 2. **非法输入场景与 SDK 错误码验证不在 SIT 考虑**：parse error / invalid request / method-not-found / not-found 等输入校验与 A2A SDK 错误码语义属 JSON-RPC 分发层与 SDK 的组件级关注点，归 component 层测试；SIT 不建任何非法输入用例，也不断言具体错误码。**SIT 的故障覆盖只采纳逻辑合理的故障模拟**——WorkflowAgent 处于调用链最下游（对外仅有 LLM 依赖），downstream 故障/超时类场景对本 SUT 不成立，不提在此（归 Versatile / plan-agent 侧 SIT 设计，且同样只采纳合理注入面）。
> 3. **业务流程优先，传输层能力不单独建用例**：SIT 的核心是以业务流程串通整体逻辑。SSE wire 帧格式在传输层（`A2aStreamingTransport` / `A2aStreamingWire`）已记录完整日志，核查现有用例响应内容/日志即可获得证据；**webhook 同理——它是一种传输层抽象，待 story 3 落地后将作为新的传输协议值（与 `A2A_STREAM` / `REST_QUERY` 同型）在各业务用例的参数化矩阵中展开复用，不以独立用例承载**。

> **落地分层约定（2026-07-22 按实现现状登记）**：业务流程面（story 1/2 入口串通、Versatile 故障链路）留 `cases/integration/workflow_call`；协作式取消的组件级先例已落 `cases/component/protocol` 层（`AgentTaskCancelStreamTest` 在 mainplan 上验证 `tasks/cancel` 可达 CANCELED，`AgentTaskCancelSyncTest` 因框架缺同步 cancel 观测而 `@Disabled`）。本档新增的取消用例属于 workflow 业务面，仍落在 `workflow_call`，但实现时须复用 `A2aServiceClient.cancelTask(taskId)` 与 `TaskCancelVerifiers` 同型断言（见 §5.2）。注意区分：原则 2 的「归 component 层」是按被测对象所有权**移出本档**（非法输入/SDK 错误码），此处的「落 component 层」是已有取消探针在仓库里的**物理分层**——前者不建 SIT 用例，后者是本档的同型先例证据。

**状态含义**：
- **runnable**：被测能力已实现，可直接落地实现
- **partial**：核心路径可测，某些断言维度受限（如特定协议维度不可靠或 SUT 部署形态未明）
- **deferred**：依赖能力在整个栈上缺失，待补齐后回归

## 1. 文档说明

本文档承接 [`FEAT-002-heterogeneous-agent-framework-compatibility.md`](../../third_party/spring-version-scope/version-scope/FEAT-002-heterogeneous-agent-framework-compatibility.md)，针对其中涉及 **WorkflowAgent / Versatile** 的范围输出 SIT 测试设计；执行入口、代码位置、运行方式等参照本文档。

**与 FEAT-001 设计档的关系与分工**：本 feature 的 story 1/2 以 FEAT-001 交付的服务化入口（A2A / REST / 会话）为承载面，即"同一入口、换框架实现的智能体"。因此两档存在天然的覆盖交叠，分工原则为：

- **FEAT-001 档**（[`FEAT-001-standardized-agent-service-entrypoint-workflow.md`](FEAT-001-standardized-agent-service-entrypoint-workflow.md)）保留：四类服务入口各自的**业务链路串通**（提交→执行→终态），以及入口层的**横切通用关注点**——AgentCard 发现、getTask 异步查询快照一致性、尾斜杠等价。这些不随框架/控制器实现变化，验一次即可。
- **FEAT-002 档（本文档）**保留：**框架/控制器适配层特有的行为**——异构框架（openJiuwen）生命周期的故障语义（远程调用中断、协作式取消）与运行时可观测性。入口串通已由 workflow_call 现有用例在 openJiuwen profile 下默认覆盖，本档不重复建设。

异构框架**日志规范**（`NFR-OBS-01/02/03`，FEAT-002 §3.3）属代码级日志断言，建议在 component 层（对应 `sprint-boot-version-scope` 适配层单测）验证，SIT 不重复建设；SIT 仅在用例通过性上间接依赖关键日志可用于故障定位。

**SIT 设计原则**（与 FEAT-001 档一致）：业务流程串通为核心；同类关注点合并到单一测试类多 `@Test` 方法；输入非法场景与 A2A SDK 错误码验证不做（归 component 层）；SSE wire 格式、webhook 推送不建独立用例（传输层抽象，核查日志/作为协议值复用）；故障注入只采纳业务中**逻辑合理、可达**的点——Versatile 作为远程控制器会下行调用模型/工具，其 downstream 故障（网络、超时、中断）是本档的合理覆盖对象；纯 workflow_call 链路上 WorkflowAgent 仍为最下游，不引入下游故障。

**与 workflow_call 现有用例的关系**：FEAT-002 story 1/2 的"通过标准化入口调用 openJiuwen workflow/deepagent/ReAct/Versatile 智能体并返回正确结果"，在 openJiuwen profile（`-Dtest.env=openjiuwen`）下已由 `workflow_call` 现有用例覆盖——`ExpenseReviewAcceptanceTest`（workflow 智能体经 expense-review-main ReAct 入口，四协议；**Allure 注册在 FEAT-004**）、`PlanAgentDirectStreamingTest`（deepagent / Versatile 远端链路；**Allure 注册在 FEAT-004**）、`TransferAfterBalanceAcceptanceTest`（REST 入口 ReAct 智能体，**FEAT-002 实际在跑 story-2 证据**）、`MultiWorkflowDirectStreamingTest`（多 workflow intent 路由到远程 Versatile，**FEAT-002 主注册类，但当前 `@Disabled("There is issue about this scenario")`**）。本档不重复建设入口串通用例，仅补足故障语义与取消协同两个新增类。

**关联文档**：

- FEAT-002 特性文档（version-scope，外部契约）：[`spring-ai-ascend/version-scope/FEAT-002-heterogeneous-agent-framework-compatibility.md`](../../third_party/spring-version-scope/version-scope/FEAT-002-heterogeneous-agent-framework-compatibility.md)（0715 版本）
- 姊妹篇（FEAT-001 分工边界）：[`FEAT-001-standardized-agent-service-entrypoint-workflow.md`](FEAT-001-standardized-agent-service-entrypoint-workflow.md)
- 本仓 SIT 现状总述：[../SIT.md](../SIT.md)
- 框架/基础设施用法：[../framework/STATUS.md](../framework/STATUS.md)（§4 配置体系、§6 基础设施、§7 协议参数化、§11 引入新 SUT）
- 同 feature DeepAgent 设计档：[`FEAT-001-standardized-agent-service-entrypoint-deepagent.md`](FEAT-001-standardized-agent-service-entrypoint-deepagent.md)
- component 层取消先例：[`src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskCancelStreamTest.java`](../../src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskCancelStreamTest.java) / [`AgentTaskCancelSyncTest.java`](../../src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskCancelSyncTest.java)

## 2. 覆盖矩阵

### 2.1 Story 1 — 兼容 openJiuwen workflow/deepagent 智能体

| Feature 需求 | SIT 验证点 | 用例 | 状态 |
|---|---|---|---|
| openJiuwen workflow 智能体经 A2A 流式/同步阻塞调用（复用 FEAT-001 入口） | 超标报销 → INPUT_REQUIRED → 补充审批 → COMPLETED（8 节点 DAG 全序执行）；合规报销 → 一次调用直达 COMPLETED；`workflow_final` 结果帧 + 累计成本 | `workflow_call/ExpenseReviewAcceptanceTest`（A2A_STREAM/A2A_SYNC 参数；**Allure 注册在 FEAT-004**） | ✅ 已实现（workflow_call） |
| openJiuwen workflow 智能体经 REST 同步/异步调用 | 同上两业务场景，REST 协议承载 | `workflow_call/ExpenseReviewAcceptanceTest`（REST_QUERY/REST_QUERY_SYNC 参数；**Allure 注册在 FEAT-004**） | ✅ 已实现（workflow_call） |
| openJiuwen deepagent 智能体经 A2A / REST 调用 | 直连 edpa-plan-agent 流式出币到终态 | `workflow_call/PlanAgentDirectStreamingTest`（A2A_STREAM/REST_QUERY；**Allure 注册在 FEAT-004**） | ✅ 已实现（workflow_call） |

### 2.2 Story 2 — 兼容 openJiuwen ReAct 智能体、兼容远程 Versatile 控制器

| Feature 需求 | SIT 验证点 | 用例 | 状态 |
|---|---|---|---|
| openJiuwen ReAct 智能体经 REST 入口调用（复用 FEAT-001 REST 兼容层） | transfer-after-balance → gateway → ReAct 智能体，A2A/REST 双模式到终态 | `workflow_call/TransferAfterBalanceAcceptanceTest`（`balanceThenTransfers`、`balanceThenTransfersScript`；**FEAT-002 实际在跑 story-2 证据**） | ✅ 已实现（workflow_call） |
| 多 workflow intent 路由到远程 Versatile | 直连 + 多 workflow 路由变体；adapter 跑 `multi-workflow` profile，两条 workflow URL 经编程式 `serviceBinding` 指向同一 envexplorer，并禁用 `type` 查询参 | `workflow_call/MultiWorkflowDirectStreamingTest`（继承 `AbstractBalanceThenTransfersTest`，A2A_STREAM/REST_QUERY） | ⏸ 已建但 `@Disabled("There is issue about this scenario")`（FEAT-002 主注册类） |
| 远程 Versatile 控制器：正常业务链路 | 父智能体通过远程 Versatile 协同完成任务（多轮模型/工具交互到终态） | ⬜ `workflow_call/VersatileRemoteFailureTest::versatileNormalFlowReachesCompleted` | ⬜ 待新建 |
| 远程 Versatile 控制器：网络故障 | 父侧收到失败终态（FAILED），不产生静默挂起或假成功 | ⬜ `workflow_call/VersatileRemoteFailureTest::versatileUnreachableFailsTask` | ⬜ 待新建 |
| 远程 Versatile 控制器：调用超时 | 超时界限可观测（任务在超时内收敛到 FAILED/明确状态，不无限挂起） | ⬜ `workflow_call/VersatileRemoteFailureTest::versatileTimeoutBounded` | ⬜ 待新建 |
| 远程 Versatile 控制器：运行中中断（FR-REL-01/02，feature 明名 MUST） | **无 End 消息不得关闭为 completed**：杀掉 Versatile 端点后，父侧任务不得被错误收敛为 COMPLETED | ⬜ `workflow_call/VersatileRemoteFailureTest::versatileKilledMidRunNotCompleted` | ⬜ 待新建 |
| 取消与中断协同：协作式取消（FR-REL-02） | 取消请求后智能体在当前 LLM/工具调用边界收敛，终态为 CANCELED（或等义终态）而非残留的 WORKING | 🆕 `workflow_call/WorkflowCancelExecutionTest::cancelAtLlmBoundary` | 🚧 后续落地（被测 runtime 当前未实现取消逻辑，用例落 `@Disabled` 骨架，待 feature 侧实现后启用） |
| 取消与中断协同：取消的幂等性 | 对已终态任务取消 → 无错误、状态不变；重复取消 → 行为一致 | 🆕 `workflow_call/WorkflowCancelExecutionTest::cancelTerminalAndIdempotent` | 🚧 后续落地（同上） |
| 上下文管理边界（FR-CTX-01/02/03） | 多轮窗口截断 / compaction / 摘要触发下的任务正确性 | —（依赖"用户可感知方式"定义，且断言偏内容质量） | ⏸ deferred（待上下文策略与可观测面明确） |

## 3. 状态分布

| 状态 | 数量 | 用例 |
|---|---|---|
| ✅ runnable | 4 | `TransferAfterBalanceAcceptanceTest`（story-2 实际在跑，gateway REST/A2A）、`MultiWorkflowDirectStreamingTest`（FEAT-002 主注册类，已建但 `@Disabled`）、`ExpenseReviewAcceptanceTest`（story-1 跨特性证据，Allure FEAT-004）、`PlanAgentDirectStreamingTest`（Versatile 远端链路跨特性证据，Allure FEAT-004） |
| ⬜ 待新建 | 1 | `VersatileRemoteFailureTest`（P1，4 方法，依赖 Versatile SUT 部署形态明确） |
| 🚧 后续落地 | 1 | `WorkflowCancelExecutionTest`（2 方法 `@Disabled` 骨架，被测 runtime 未实现取消逻辑） |
| ⏸ deferred | 1 | 上下文管理边界 |
| — component | 1 | 异构框架日志规范（NFR-OBS-01/02/03，归 `sprint-boot-version-scope` 适配层单测） |

说明：workflow_call 现有用例已覆盖 story 1/2 的正常入口串通（在 openJiuwen profile 下）。本档新增建设仅 2 个类：`VersatileRemoteFailureTest` ⬜ 待新建、`WorkflowCancelExecutionTest` 🚧 后续落地（骨架先行）。

## 4. 进度看板

| 分组 | 用例 | 状态 | 备注 |
|---|---|---|---|
| workflow_call 现有 / FEAT-002 主注册 | `MultiWorkflowDirectStreamingTest` | ⏸ 已建禁用 | story `wf.workflow-direct`，A2A_STREAM/REST_QUERY；`@Disabled("There is issue about this scenario")` |
| workflow_call 现有 / story-2 在跑证据 | `TransferAfterBalanceAcceptanceTest` | ✅ 已实现 | gateway REST/A2A 双模式；stories `wf.verpkt-gateway-rest` / `wf.verpkt-gateway-a2a` |
| workflow_call 现有 / 跨特性证据 | `ExpenseReviewAcceptanceTest` / `PlanAgentDirectStreamingTest` | ✅ 已实现（Allure 注册在 FEAT-004） | story-1 workflow 入口 / Versatile 远端链路；四协议或 A2A_STREAM/REST_QUERY |
| P1 新建 | `VersatileRemoteFailureTest`（4 个 `@Test`） | ⬜ 待新建 | 依赖 Versatile SUT 部署形态；含 feature 明名 MUST 的中断检测 |
| 后续落地 | `WorkflowCancelExecutionTest`（2 个 `@Test`） | 🚧 blocked | 被测 runtime 未实现取消逻辑；先落 `@Disabled` 骨架，feature 实现后启用 |
| deferred | 上下文管理边界 | ⏸ deferred | 待上下文策略与"用户可感知"可观测面定义 |

## 5. 子用例设计（G/W/T）

### 5.0 MultiWorkflowDirectStreamingTest（⏸ 已建禁用，FEAT-002 主注册类）

**FSD 需求条目**：FEAT-002 story 2“多 workflow intent 路由到远程 Versatile 控制器”。

**对应验收标准**：plan-agent 携带正确 intent（`查询账户余额` / `快速转账`）命中 adapter 配置的两条 workflow URL；两条 URL 经编程式 `serviceBinding` 指向同一 envexplorer 容器；REST 线上禁掉 `type` 查询参后仍按 `workspace_id` 路由；业务最终完成查余额+转账。

**覆盖业务链路**：plan-agent → edpa-adapter-multi-workflow（`multi-workflow` profile）→ envexplorer workflow 端点。

**用例设计思路**：本类是 FEAT-002 在 Allure 的主注册类，但当前 `@Disabled("There is issue about this scenario")`。实现上继承 `AbstractBalanceThenTransfersTest`，复用其 `final` 模板方法 `balanceThenTransfers`（A2A_STREAM / REST_QUERY 参数化）与核心语义断言。子类只切换栈拓扑与 transport：通过 `SutStack.Builder.serviceBinding` 把 `VERSATILE_BALANCE_WORKFLOW_URL` 与 `VERSATILE_TRANSFER_WORKFLOW_URL` 同时指向同一个 `envexplorer` 容器；override `openConversation` 给 `ConversationInteractionAdapter` 加 `.disableQueryParam("type")`。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `balanceThenTransfers`（参数化 A2A_STREAM / REST_QUERY） | openJiuwen profile，adapter 跑 `multi-workflow` profile，envexplorer 单容器承载余额/转账两条 workflow | 客户端提交“先查下余额，再给李四和王五各转50元” | 事件流/汇总不含 JVM 堆栈泄露；含余额 8200、收款人李四/王五；转账完成态软捕获 |

**依赖的外部组件 / fixture**：openJiuwen profile；`edpa-adapter-multi-workflow` YAML agent；envexplorer 容器（serviceBinding 去重后仅起一个）。

**对应实现位置**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/MultiWorkflowDirectStreamingTest.java`

**框架落点**：`BaseManagedStackTest` + `SutStack.Builder.serviceBinding(...)`；`ConversationInteractionAdapter.disableQueryParam("type")`；`InteractionFlow`（A2A_STREAM / REST_QUERY）。

**执行命令**：

```bash
mvn test -Dtest=MultiWorkflowDirectStreamingTest -Dtest.env=openjiuwen \
  -DLLM_API_KEY=... -DLLM_BASE_URL=... -DLLM_MODEL_NAME=...
```

**状态**：⏸ 已建禁用（scenario 存在未解问题；移除 `@Disabled` 并真机校准 6 点后即为 ✅ runnable）。

### 5.1 VersatileRemoteFailureTest（⬜ 待新建，P1）

**FSD 需求条目**：FEAT-002 story 2"兼容远程 Versatile 控制器"；§6 FR-REL-01 中断检测（MUST）、FR-REL-02 协作式取消（MUST）。

**对应验收标准**：远程 Versatile 不可达/中断时，父侧任务收敛到失败终态而非静默挂起或假成功；正常链路不受影响。

**覆盖业务链路**：父智能体 →（远程）Versatile 控制器 → LLM/工具执行 → 事件流 → 父侧任务终态。

**用例设计思路**：作为远程控制器，Versatile 是调用链中唯一具备"downstream"（模型/工具远程调用）的被测对象，其网络故障、超时、运行中中断均为业务中逻辑合理且可达的故障点，按 FEAT-002 §6 的可靠性要求逐项验证。中断检测（无 End 消息不得关闭为 completed）是 feature 明名 MUST 的核心语义，单独一个 `@Test` 方法承载，复用 `SutStack.stop()` 杀进程手段（deepagent 档已验证可行）。正常链路用例同时充当故障用例的对照基线。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `versatileNormalFlowReachesCompleted` | openJiuwen profile 就绪，父智能体（经 Versatile 控制器协同）与 Versatile SUT 均拉起 | 客户端向父智能体提交需多轮模型/工具协同的业务消息（A2A_STREAM 协议） | 事件流含中间过程帧（TEXT / STATUS 非终态），终态为 COMPLETED；结果含有效产出；全程无 FAILED/CANCELED/REJECTED |
| `versatileUnreachableFailsTask` | Versatile 端点配置为不可达地址（或就绪后先行关停 Versatile 进程） | 客户端提交同一业务消息 | 任务在可接受时限内收敛到 FAILED（或等义失败终态），错误语义可辨识；**不得**出现静默挂起（无限 WORKING）或错误收敛为 COMPLETED |
| `versatileTimeoutBounded` | Versatile 端点可达但响应被人为延迟（经 FaultLink/Toxiproxy 注入 latency，或以超短超时阈值配置） | 客户端提交业务消息并等待 | 任务在超时界限内收敛到 FAILED/明确终态；超时行为可观测（状态语义可区分超时与即时失败） |
| `versatileKilledMidRunNotCompleted` | 父智能体与 Versatile SUT 拉起，任务已进入多轮协同执行中 | 执行中途 `SutStack.stop()` 杀掉 Versatile 端点进程 | **无 End 消息不得关闭为 completed**：任务终态不为 COMPLETED（应为 FAILED/中断语义）；事件流未出现伪造的正常收尾 |

**依赖的外部组件 / fixture**：openJiuwen profile、Versatile SUT（远程控制器服务，部署形态待明确——独立进程 or 内嵌）；超时/网络故障经框架 `FaultLink`（Toxiproxy）注入，若链路不可代理则退化为超短超时阈值配置。

**对应实现位置（新建）**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/VersatileRemoteFailureTest.java`

**框架落点**：`BaseManagedStackTest` + `SutStack`（stop 杀 Versatile 进程、FaultLink 注入网络故障）；`A2aServiceClient` / `InteractionFlow`（A2A_STREAM / REST_QUERY 提交与事件收集）。

**执行命令**：

```bash
mvn test -Dtest=VersatileRemoteFailureTest -Dtest.env=openjiuwen \
  -DLLM_BASE_URL=... -DLLM_API_KEY=... -DLLM_MODEL_NAME=...
```

**状态**：⬜ 待新建（依赖 Versatile SUT 部署形态明确；中断/超时注入手段需与 `SutStack` / `FaultLink` 能力对齐）。

### 5.2 WorkflowCancelExecutionTest（🆕 新建，🚧 后续落地）

**FSD 需求条目**：FEAT-002 §6 FR-REL-02 协作式取消（MUST）。

**对应验收标准**：取消请求后智能体在当前 LLM/工具调用边界收敛，终态为 CANCELED（或等义终态）而非残留 WORKING；取消操作幂等。

**覆盖业务链路**：客户端取消请求 → 运行时取消传播 → 智能体在协作边界收敛 → 任务终态。

**用例设计思路**：协作式取消是异构框架生命周期管理的关键语义（feature 明名 MUST），且为业务可达操作（客户端可随时发起取消）。聚焦两点：取消在 LLM/工具边界的收敛行为、取消的幂等性。**被测 runtime 当前未实现取消逻辑**，本类整体标记为后续落地——以 `@Disabled` 骨架先行定义 G/W/T 与双端取证方式，待 feature 侧实现取消面（A2A `tasks/cancel` 或 REST 等价端点）后启用并回归。

| 方法名 | Given | When | Then |
|---|---|---|---|
| `cancelAtLlmBoundary`（🚧 后续落地） | expense-review-workflow 拉起（openJiuwen profile）；客户端提交合规报销场景使任务进入执行中 | 任务执行期间（LLM/工具调用进行中）发起取消请求 | 智能体在当前调用边界收敛；任务终态为 CANCELED（或运行时等义终态），不残留 WORKING；已产出部分不伪造为 COMPLETED |
| `cancelTerminalAndIdempotent`（🚧 后续落地） | 同一 SUT；任务 A 已到达 COMPLETED 终态，任务 B 执行中 | 对任务 A 发起取消；对任务 B 连续发起两次取消 | 对终态任务取消：无错误返回、任务状态保持 COMPLETED 不变；重复取消：两次行为一致，任务收敛到同一 CANCELED 终态 |

**依赖的外部组件 / fixture**：openjiuwen profile；运行时取消面（待 feature 侧实现）。

**对应实现位置（新建）**：`src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/WorkflowCancelExecutionTest.java`

**框架落点**：`BaseManagedStackTest` + `SutStack`；`A2aServiceClient.cancelTask(taskId)`（取消面无论 A2A/REST，测试代码统一复用该 client 方法）；`InteractionFlow`（提交与事件收集）；复用 `cases/component/protocol/TaskCancelVerifiers.assertCancelAndGet(...)` 做取消+轮询断言。

**执行命令**：

```bash
mvn test -Dtest=WorkflowCancelExecutionTest -Dtest.env=openjiuwen \
  -DLLM_BASE_URL=... -DLLM_API_KEY=... -DLLM_MODEL_NAME=...
```

**状态**：🚧 后续落地（被测 runtime 未实现取消逻辑；`@Disabled` 骨架，feature 实现后启用）。启用后须联动 component 层先例 `AgentTaskCancelStreamTest` / `AgentTaskCancelSyncTest` 回归。

## 6. 备注与风险

### 6.1 与 FEAT-001 档的分工（防重复建设）

| 关注点 | 归属档 | 理由 |
|---|---|---|
| A2A 流式/同步、REST 同步/异步业务链路串通 | FEAT-001（workflow_call 现有用例承载） | 入口行为，不随框架实现变化 |
| AgentCard 发现 / getTask 快照一致性 / 尾斜杠等价 | FEAT-001 | 入口层横切通用语义 |
| 框架日志规范（NFR-OBS） | component 层 | 代码级日志断言 |
| 远程 Versatile 故障语义（不可达/超时/中断） | **FEAT-002 本档** | 框架/控制器适配层特有，feature 明名 MUST |
| 协作式取消 | **FEAT-002 本档**（🚧 后续落地） | 异构框架生命周期语义，feature 明名 MUST；runtime 未实现，与 FEAT-004 档级联取消用例同步待启用 |
| 上下文管理边界 | FEAT-002 本档（deferred） | 待策略与可观测面明确 |

### 6.2 风险

1. **MultiWorkflowDirectStreamingTest 当前 `@Disabled`**：它是 FEAT-002 在 Allure 的主注册类，但 scenario 存在未解问题。若长期禁用，特性看板会显示“0 运行”，须与 story 2 实际在跑的 `TransferAfterBalanceAcceptanceTest` 合并理解。解除禁用前需真机校准 6 点：plan-agent 标准 A2A 文本一轮输入、taskId 续轮、直连无需 EDPA inputs 富化、plan-agent `/v1/query` 可达、intent 正确命中 workflow 端点、`multi-workflow` profile 正确叠加 base 配置。
2. **Versatile SUT 部署形态未明确**：`VersatileRemoteFailureTest` 的全部用例依赖远程 Versatile 控制器的部署方式（独立服务进程 or 内嵌依赖）与配置入口，这是本档最大的落地前置。落地前需与特性落地侧确认 SUT 拓扑，并同步 `docs/framework/STATUS.md` §11 的新 SUT 引入流程。
3. **故障注入手段**："不可达"可经配置指向死地址或 `SutStack.stop()` 实现；"超时"优先经框架 `FaultLink`（Toxiproxy latency）注入，链路不可代理时退化为超短超时阈值配置，避免过度建设。
4. **取消逻辑未实现**：被测 runtime 当前无协作式取消实现，`WorkflowCancelExecutionTest` 标记为后续落地（`@Disabled` 骨架）；feature 侧实现后启用并回归，同时联动 `cases/component/protocol` 层取消先例 `AgentTaskCancelStreamTest` / `AgentTaskCancelSyncTest`。
5. **story 与入口的耦合**：story 1/2 复用 FEAT-001 入口，若 FEAT-001 入口行为变更（如错误语义、终态集合），本档现有 workflow_call 用例需同步回归。
6. **文档中旧框架名残留风险**：本档 §5 已统一使用 `A2aServiceClient` / `InteractionFlow` / `SutStack` / `FaultLink` 等当前框架 API；后续维护若复制粘贴旧 FEAT 文档，需避免 `A2aClientCalls` / `RestClientCalls` / `SitClientRecorder` 等已废弃或不存在名称回流。
