---
feature_id: FEAT-001
feature_title: 标准化智能体服务入口
sut: WorkflowAgent（expense-review-workflow 单 jar 双 profile 为主 SUT；edpa-plan-agent 直连栈与 edpa-gateway 拓扑作来源等价性证据）
scope: 本档只覆盖 WorkflowAgent SUT 侧可外部黑盒断言的 FEAT-001 事实要求，按三个 story 切分——story 1（A2A JSON-RPC 入口：同步阻塞 + 流式 + 异步调用与查询，不含 webhook）与 story 2（REST API 兼容调用）的业务流程面已由 workflow_call 用例覆盖（FEAT-001 的 Allure 主注册类为直连四协议矩阵 ExpenseReviewWorkflowDirectAcceptanceTest；ExpenseReview 族 / PlanAgentDirect / TransferAfterBalance 已改注册 FEAT-004/003/002，作跨特性证据）；协议面 Agent Card 发现已落 cases/component/workflow_agent 层（EdpaAdapterCardDiscoveryTest，edpa-adapter card，含 story 3 守门），主 SUT 自身 card 补钉与 workflow 侧 GetTask 待新建（§3.1/§3.5，已按落地命名规则命名并标 ⬜）；story 3（webhook 点对点 A2A 异步回调）未实现且接口未明，作为传输层抽象待落地后并入业务用例参数化复用，不建独立用例；非法输入场景与 A2A SDK 错误码验证整体移出 SIT（归 component 层）；WorkflowAgent 处于调用链最下游，downstream 故障/超时场景不适用（归后续 versatile 侧设计）；gRPC / 普通-client webhook / agent-bus 私有入口按特性档 §5.2 明示 OUT，不列入
status: designed
owner: TBD
tags: [integration, workflowagent, feat-001]
depends_on:
  - openjiuwen profile（-Dtest.env=openjiuwen，需 LLM_API_KEY 等 SAA_* 环境变量；协议面用例不消耗 LLM 但栈启动仍需 profile 环境）
  - 被测 jar 就绪：expense-review-workflow:0.2.0-SNAPSHOT（双 profile：默认 workflow / main）、edpa-plan-agent、edpa-adapter、edpa-gateway
  - envexplorer 容器由 edpa-adapter 的 service-bindings 自动拉起；redis 容器仅 Redis 变体需要（框架自管）
related_docs:
  - FEAT-001 特性文档（version-scope，外部契约）：`spring-ai-ascend/version-scope/FEAT-001-standardized-agent-service-entrypoint.md`（0715 版本）
  - 平台 SIT 总体设计：[../spring-ai-ascend-integration-test-design.md](../spring-ai-ascend-integration-test-design.md)
---

# FEAT-001 — WorkflowAgent 侧标准化 Agent 服务入口 SIT 测试设计

> **一句话**：以 WorkflowAgent（expense-review-workflow 8 节点 DAG 为最纯净 SUT）为对象，把 FEAT-001 §2 能力表 MUST 项、§3 入口要求、§4 用户旅程和 §5.1 行为语义映射为可黑盒断言的子用例，并按三个 story 标注实现/覆盖现状（2026-07-22 登记）：story 1（A2A JSON-RPC 入口）、story 2（REST API 兼容）业务流程面已由 workflow_call 覆盖（含 FEAT-001 主注册的直连四协议矩阵 ExpenseReviewWorkflowDirectAcceptanceTest），Agent Card 发现面已由 component/workflow_agent 层的 EdpaAdapterCardDiscoveryTest 落地（含 story 3 守门）；剩余缺口收敛为两个待新建协议面用例——主 SUT 自身 card 补钉、workflow 侧显式 GetTask 异步查询（顺带尾斜杠等价）；story 3（webhook 异步回调）作为传输层抽象，待落地后并入业务用例参数化复用，不建独立用例。

> **组织原则（2026-07-20 调整）**：
> 1. **同类项合并到单一测试类**：同一被测面的多条事实要求合并为一个测试类的多个 `@Test` 方法（如 Agent Card 发现/capabilities/skills → `EdpaAdapterCardDiscoveryTest` 多方法聚合），不逐条独立成类。
> 2. **非法输入场景与 SDK 错误码验证不在 SIT 考虑**：parse error / invalid request / method-not-found / not-found 等输入校验与 A2A SDK 错误码语义属 JSON-RPC 分发层与 SDK 的组件级关注点，归 component 层测试；SIT 不建任何非法输入用例，也不断言具体错误码。**SIT 的故障覆盖只采纳逻辑合理的故障模拟**——WorkflowAgent 处于调用链最下游（对外仅有 LLM 依赖），downstream 故障/超时类场景对本 SUT 不成立，不提在此（归后续 versatile / plan-agent 侧 SIT 设计，且同样只采纳合理注入面）。
> 3. **业务流程优先，传输层能力不单独建用例**：SIT 的核心是以业务流程串通整体逻辑。SSE wire 帧格式在传输层（`A2aStreamingTransport` / `A2aStreamingWire`）已记录完整日志，核查现有用例响应内容/日志即可获得证据；**webhook 同理——它是一种传输层抽象，待 story 3 落地后将作为新的传输协议值（与 `A2A_STREAM` / `REST_QUERY` 同型）在各业务用例的参数化矩阵中展开复用，不以独立用例承载**。

> **落地分层约定（2026-07-22 按实现现状登记）**：协议面正路径探针（Agent Card 发现、GetTask 异步查询）落 `cases/component/workflow_agent` 层（`@Tag("component") @Tag("smoke")`，契约数据 `testdata/component/workflow_agent/`，与 mainplan 的 `component/protocol` 同型；先例 `EdpaAdapterCardDiscoveryTest`），业务流程面留 `cases/integration/workflow_call`。注意区分：原则 2 的「归 component 层」是按被测对象所有权**移出本档**（非法输入/SDK 错误码），此处的「落 component 层」是本档覆盖用例在仓库里的**物理分层**——前者不建 SIT 用例，后者仍是本档覆盖证据。§4 早先「所有新建类落到 workflow_call」的约定以本条为准。

**状态含义**：
- **runnable**：被测能力已实现，可直接落地实现
- **partial**：核心路径可测，某些断言维度受限（如特定协议维度不可靠）
- **deferred**：依赖能力在整个栈上缺失（story 3 webhook 传输层未实现），落地等能力补齐后以传输参数形式并入业务用例

**story 标注**：S1 = story 1（A2A JSON-RPC 入口）；S2 = story 2（REST API 兼容）；S3 = story 3（webhook 点对点异步回调）；横向 = 跨 story 的入口公共事实。

---

## 1. 覆盖矩阵

对应 FEAT-001 §2 能力表、§3 入口要求、§4 场景表和 §5.1 行为语义。子用例 ID 以 `FEAT-001.wf.` 前缀与 deepagent 姊妹档区分；**「落点类」列体现合并原则——多行事实要求指向同一测试类的不同方法**。标「component」的行为移出 SIT 的非法输入/SDK 错误码场景，仅保留追溯，不建 SIT 用例。

| FEAT-001 事实要求 | story | 本档子用例 ID | 现状 | 状态 | 落点类（合并） |
|---|---|---|---|---|---|
| Agent Card 双入口发现（§2/§3） | 横向 | `FEAT-001.wf.agent-card` | 已覆盖（edpa-adapter card）；主 SUT 补钉 ⬜ | runnable | [EdpaAdapterCardDiscoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/component/workflow_agent/EdpaAdapterCardDiscoveryTest.java)`#discoveryEndpointsAreReachableWithJsonMediaType`（`/.well-known/agent.json` ↔ `/.well-known/agent-card.json` 双入口 200 + `application/json` + body 等价，story `wf.agent-card-endpoint`）+ `#sdkDiscoveryReturnsCardWithName`（SDK 发现路径 identity）；主 SUT expense-review-workflow 自身 card 补钉并入 ⬜ `ExpenseReviewWorkflowCardDiscoveryTest`（§3.1） |
| Agent Card capabilities 声明真实性（§2/§5.1.1） | 横向 + S3 守门 | `FEAT-001.wf.agent-card-capabilities` | 已覆盖（edpa-adapter card） | runnable | EdpaAdapterCardDiscoveryTest`#cardDeclaresStreamingAndPushCapabilities`（streaming=true 硬断言；`pushNotifications=false` 即 **story 3 守门断言在岗**，story `wf.agent-card-capabilities`）+ `#servedCardMatchesContract`（数据驱动契约 [edpa-adapter-card-assertions.json](../../src/test/resources/testdata/component/workflow_agent/edpa-adapter-card-assertions.json) 钉死 capabilities 三字段） |
| Agent Card skills 声明真实性（§2/§5.1.1） | 横向 | `FEAT-001.wf.agent-card-skills` | 已覆盖（edpa-adapter card） | runnable | EdpaAdapterCardDiscoveryTest`#cardDeclaresVersatileBankProxySkill`（skills[].id 含 versatile-bank-proxy，story `wf.agent-card-skill`）+ `#cardCarriesAllSdkRequiredFields`（a2a-sdk 强制非空字段集，复用 `a02-agent-card-required-fields.json`）+ `#descriptionIsNonBlank`；主 SUT 主 skill（review_expense）断言并入 ⬜ 补钉类 |
| `/a2a` 与 `/a2a/` 尾斜杠等价（§2/§3） | S1 | `FEAT-001.wf.jsonrpc-endpoint-slash` | 未覆盖（workflow 侧） | runnable | 并入 ⬜ `ExpenseReviewWorkflowTaskGetTest#snapshotMatchesStreamTerminal`（§3.5）——同一业务流内流式调用与 GetTask 分别走 `/a2a` 与 `/a2a/` 两种拼写，顺带验证入口等价，不单独发探针；deepagent 侧已有独立用例 JsonRpcEndpointSlashTest（姊妹档） |
| JSON-RPC parse error / invalid request / method-not-found + id 回显（§5.1.2/§5.1.8/§2） | S1 | `FEAT-001.wf.jsonrpc-error-surface` | 移出 SIT | component | **归 component 层测试**（JSON-RPC 分发层与 SDK 错误码的组件级关注点）；SIT 不建 |
| 流式 `SendStreamingMessage`（§2/§4） | S1 | `FEAT-001.wf.send-streaming-message` | 已覆盖 | runnable | **主注册**：[ExpenseReviewWorkflowDirectAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java)（直连 workflow 四协议矩阵，story `wf.direct-streaming`）；跨特性证据：[ExpenseReviewAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewAcceptanceTest.java)（A2A_STREAM 两场景，FEAT-004 注册，流程在 AbstractExpenseReviewAcceptanceTest 模板基类）、[PlanAgentDirectStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java)（FEAT-004）、[TransferAfterBalanceAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/TransferAfterBalanceAcceptanceTest.java)（gateway a2a 模式，FEAT-002）、[PlanAgentDirectStreamingRedisTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingRedisTest.java)（Redis TaskStore，FEAT-003） |
| SSE wire 帧格式（§3/§5.1.4：`event: jsonrpc` + JSON-RPC envelope + final/interrupted 后流关闭） | S1 | `FEAT-001.wf.sse-wire-format` | 不建用例 | — | 按组织原则 3：传输层（`A2aStreamingTransport`）已记录完整 wire 日志，核查现有用例响应内容/日志即可获得证据；SIT 以业务流程串通，不对 wire 格式做孤立断言 |
| 阻塞 `SendMessage`（§2/§5.1.5） | S1 | `FEAT-001.wf.send-message-blocking` | 已覆盖 | runnable | ExpenseReviewWorkflowDirectAcceptanceTest 场景2（A2A_SYNC 单轮 COMPLETED，story `wf.direct-blocking`）；AbstractExpenseReviewAcceptanceTest 场景2 同断言（三叶子继承） |
| 异步查询 `GetTask` 快照（§2/§3/§4 查询长任务） | S1 | `FEAT-001.wf.get-task` | 部分覆盖（他 SUT 同型先例）；workflow 侧 ⬜ | runnable | ⬜ `ExpenseReviewWorkflowTaskGetTest#snapshotMatchesStreamTerminal`（§3.5，需 LLM；顺带覆盖尾斜杠等价）。同型先例：mainplan [AgentTaskGetTest](../../src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskGetTest.java)（A-05：sync send→COMPLETED→getTask 快照 id/state/text 一致）、deepagent GetTaskTest（姊妹档）；workflow 侧现有链路只消费流内终态 Task（InteractionFlow 明确不做 getTask 往返），显式查询入口仍无断言。**GetTask 负路径（未知 taskId）归 component 层，不建 SIT 用例** |
| WorkflowAgent HITL 中断/恢复（§5.1.6 input-required 语义） | S1 | `FEAT-001.wf.input-required` | 已覆盖（A2A_STREAM）/ 受限（sync、REST） | partial | AbstractExpenseReviewAcceptanceTest 场景1（三叶子继承；redis 叶子另断经 redis 持久化恢复）+ [ExpenseReviewWorkflowDirectAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java) 场景1（直连，story `wf.input-required`）：超标报销 → INPUT_REQUIRED → 续接 approved → COMPLETED；INPUT_REQUIRED 仅 A2A_STREAM 可靠呈现，sync/REST 为调试矩阵已知红（REST 透传未标定，独立问题） |
| Task 状态序列 submitted→working→terminal（§5.1.6） | S1 | `FEAT-001.wf.task-lifecycle` | 已覆盖 | runnable | AbstractExpenseReviewAcceptanceTest `assertStreamTrajectory`（A2A_STREAM 严格序列 / 续轮宽松子序列，三叶子继承）+ ExpenseReviewWorkflowDirectAcceptanceTest 同型（story `wf.task-lifecycle`） |
| REST 流式/同步调用（story 2，`POST /v1/query` stream:true/false） | S2 | `FEAT-001.wf.rest-query` | 已覆盖 | runnable | ExpenseReviewWorkflowDirectAcceptanceTest（story `wf.direct-rest-query`）、AbstractExpenseReviewAcceptanceTest 三叶子（REST_QUERY / REST_QUERY_SYNC 两场景）、PlanAgentDirectStreamingTest（REST_QUERY 直连）、TransferAfterBalanceAcceptanceTest（`-DGATEWAY_PROTOCOL=rest`）；扩展对照：[PlanAgentReactiveQueryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentReactiveQueryTest.java)（`POST /v1/query/reactive` WebFlux ↔ MVC 流式等价，story `wf.rest-query`——类级 @Disabled 待 webflux jar，见 §6.2） |
| REST 与 A2A 入口结果等价（§5.1.0 的协议维度） | S1+S2 | `FEAT-001.wf.rest-a2a-equivalence` | 已覆盖 | runnable | 四协议参数化矩阵本身即等价性验证（main-routed 与直连双拓扑，同场景、同 kickoff、同断言），不另建新用例；PlanAgentReactiveQueryTest reactive/MVC 同构对照（story `wf.rest-a2a-equivalence`，@Disabled 待 jar） |
| 入口来源等价：普通 client / runtime-to-runtime / 网关转发同一入口（§5.1.0/§4） | 横向 | `FEAT-001.wf.entry-source-equivalence` | 已覆盖 | runnable | 四拓扑事实证据：① 直连 workflow（ExpenseReviewWorkflowDirectAcceptanceTest，不经 main，story `wf.workflow-agent`）② runtime-to-runtime（expense-review-main 经远程 A2A 工具 review_expense 调 workflow）③ 直连 plan-agent（ConversationInteractionAdapter）④ 网关下行双模式；无私有执行入口。edpa-gateway 转发本身属 FEAT-011/012 范畴，本档仅借作证据 |
| 输入与元数据语义（§5.1.7） | 横向 | `FEAT-001.wf.metadata-propagation` | 已覆盖（隐式） | runnable | InteractionFlow `.withMetadata(userId/agentId/sessionId)` 全协议注入且按 `<scenario>-<protocol>` 隔离 session；显式派生字段断言不列入 |
| handler/runtime exception → failed Task + 结构化错误 payload（§5.1.6/§5.1.8） | S1 | `FEAT-001.wf.task-failed-semantics` | 本 SUT 无合理注入面 | — | WorkflowAgent 处于调用链最下游，downstream 故障/超时场景不成立（见 §3.9 决策）；该语义改由后续 versatile / plan-agent 侧 SIT 设计以逻辑合理的故障模拟承接 |
| no handler registered 拒绝执行（§5.1.8） | S1 | `FEAT-001.wf.no-handler` | 不可达 | — | expense-review-workflow 启动即注册固定 handler，黑盒无注入面；且分发层拒绝语义属 component 关注点；不建用例 |
| webhook 能力声明守门（story 3 未实现的当前事实） | S3 | `FEAT-001.wf.webhook-not-advertised` | 已覆盖 | runnable（已并入） | **已并入 EdpaAdapterCardDiscoveryTest`#cardDeclaresStreamingAndPushCapabilities`**：`pushNotifications=false` 硬断言 + 契约 json 钉死，守门在岗；CRUD 拒绝探针属分发层组件关注点，归 component 层 |
| Webhook 完成/异常回调、中间态不推送、幂等、大载荷引用、不受信 target、投递失败（§2/§3/§5.1.3/§5.1.8） | S3 | `FEAT-001.wf.webhook-*` | 未覆盖 | **deferred（不建独立用例）** | 按组织原则 3：webhook 是传输层抽象，待 story 3 传输层落地后作为新传输协议值并入现有业务用例参数化复用（见 §3.8）；接口/阈值/信任模型未定项随接口定义在业务场景中展开断言 |

> **不在本档范围**（对齐 FEAT-001 §5.2 + version-scope §2 MUST 集 + 本档组织原则）：
> - **归 component 层**（组织原则 2）：JSON-RPC parse error / invalid request / method-not-found / id 回显等 SDK 错误码语义、GetTask 未知 taskId 负路径、空文本输入拒绝、REST 坏 body / 未知路径、push config CRUD 的 method-not-found 拒绝探针。
> - **不建独立用例**（组织原则 3）：SSE wire 帧格式（传输层日志可核查）、webhook 全族（待传输层落地后并入业务用例参数化）。
> - **本 SUT 不适用**：downstream 故障 mid-stream、下游超时等注入场景——WorkflowAgent 是调用链最下游，无 downstream 可注；此类场景（仅限逻辑合理者）归后续 versatile / plan-agent 侧 SIT 设计。
> - **特性档明示 OUT**：`CancelTask` / `ListTasks` / `SubscribeToTask`（不在 MUST 集）、多 Agent 路由（单 runtime 单 handler）、gRPC northbound、普通 client webhook 自报 URL、webhook 中间态订阅、webhook token 流、webhook HITL 继续执行、非文本输入、outbound 远程 Agent 编排（FEAT-004/005/006）、agent-bus 专用私有入口、认证授权协议。
> - 多 workflow 路由（[MultiWorkflowDirectStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/MultiWorkflowDirectStreamingTest.java)，当前 @Disabled）属 adapter 路由能力而非 FEAT-001 入口事实，不列入。

### 1.1 状态分布快照

| 状态 | 子用例行数 | 对应测试类 | 说明 |
|---|---|---|---|
| runnable（已覆盖） | 11 | 2 个主注册类 + 跨特性证据 | story 1 & 2 主路径（streaming / blocking / rest×2 / 等价性 / 来源等价 / lifecycle / metadata）+ card 三行 + webhook-not-advertised 守门：FEAT-001 主注册 ExpenseReviewWorkflowDirectAcceptanceTest（业务流程面）+ EdpaAdapterCardDiscoveryTest（协议面，component 层）；跨特性证据 ExpenseReview 族 / PlanAgentDirect / TransferAfterBalance / PlanAgentRedis |
| runnable（待新建） | 2 | **2 个新类（component/workflow_agent 层）** | ⬜ `ExpenseReviewWorkflowTaskGetTest`（get-task + 尾斜杠并入，1 行）、⬜ `ExpenseReviewWorkflowCardDiscoveryTest`（主 SUT card 补钉，增强项——同族 AgentCardController 已有 mainplan + edpa-adapter 双证，1 行） |
| partial（已覆盖） | 1 | — | input-required（sync/REST 维度受限） |
| deferred | 1 族 | 不建独立类 | story 3 webhook 全族——待传输层落地后并入业务用例参数化 |
| component / 不建 / 不可达 / 不适用 | 4 | — | jsonrpc-error-surface（归 component）；sse-wire-format（日志核查）；no-handler（不可达且属 component 关注点）；task-failed-semantics（最下游 SUT 无合理注入面，归 versatile 侧） |

**落地优先级**：⬜ 待新建 2 类（P1 get-task 搭 LLM 车次 → P2 主 SUT card 补钉）→ story 3 传输层落地后并入业务用例。

### 1.2 覆盖进度看板

> **用法**：随开发推进直接改 ✅ / ⬜ 状态位。**图例**：✅ 已落地并 PASS；🟡 已落地但 partial；⬜ 待落地；⏸ deferred（story 3 能力缺失）；— 不建/移出/不可达/不适用。

| 测试类 | 子用例（方法级） | story | 状态 | 说明 |
|---|---|---|---|---|
| **现有 — FEAT-001 主注册（业务流程面）** | | | | |
| [ExpenseReviewWorkflowDirectAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java) | direct-streaming / direct-blocking / direct-rest-query / rest-a2a-equivalence / input-required / task-lifecycle / workflow-agent | S1+S2 | ✅ / 🟡 | **直连 expense-review-workflow（不经 main）**，2 场景 × 4 协议 = 8 用例；input-required 仅 A2A_STREAM 硬断言（🟡），sync/REST 为调试矩阵 |
| [PlanAgentReactiveQueryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentReactiveQueryTest.java) | rest-query（reactive/MVC 流式等价）/ rest-a2a-equivalence | S2 | ⏸ | 类级 @Disabled：待 `webflux.enabled=true` 重建 jar（当前 jar 下 REST_REACTIVE 404） |
| **现有 — 跨特性证据（业务流程面）** | | | | |
| [AbstractExpenseReviewAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/AbstractExpenseReviewAcceptanceTest.java) + 叶子 [ExpenseReviewAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewAcceptanceTest.java) / [ExpenseReviewRedisAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisAcceptanceTest.java) / [ExpenseReviewRedisClusterAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewRedisClusterAcceptanceTest.java) | send-streaming / send-blocking / rest-query / rest-a2a-equivalence / input-required / task-lifecycle | S1+S2 | ✅ / 🟡 / ⏸ | 模板基类（2 final 模板方法 × 4 协议）+ 三叶子：in-memory（FEAT-004 注册）、redis 密码场景（FEAT-003）、redis-cluster（FEAT-003，@Disabled 待真机） |
| [PlanAgentDirectStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java) | send-streaming / rest-query（直连 plan-agent 栈） | S1+S2 | ✅ | FEAT-004 注册；A2A_STREAM / REST_QUERY 参数化 |
| [TransferAfterBalanceAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/TransferAfterBalanceAcceptanceTest.java) | entry-source-equivalence（gateway 双模式） | 横向 | ✅ | FEAT-002 注册；`-DGATEWAY_PROTOCOL=a2a\|rest` 跑两遍 |
| [PlanAgentDirectStreamingRedisTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingRedisTest.java) | send-streaming + Redis TaskStore | S1 | ✅ | FEAT-003 交叉 |
| **已落地 — 入口协议面（component/workflow_agent 层）** | | | | |
| [EdpaAdapterCardDiscoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/component/workflow_agent/EdpaAdapterCardDiscoveryTest.java) | 双入口等价 / capabilities（**含 story 3 守门**）/ skills / 接口契约 / 数据驱动契约 | 横向+S3 | ✅ | hermetic 无 LLM（需 Docker envexplorer + 本地 adapter jar）；10 方法 + 契约 json；2 争议点（顶层 url、provider.organization 空串）待与开发对齐 |
| **待新建 — 入口协议面（component/workflow_agent 层，按落地命名规则）** | | | | |
| `ExpenseReviewWorkflowTaskGetTest` | snapshotMatchesStreamTerminal（顺带尾斜杠等价） | S1 | ⬜ | 需 LLM；P1；同型先例 mainplan AgentTaskGetTest（A-05） |
| `ExpenseReviewWorkflowCardDiscoveryTest` | 主 SUT card 双入口 / capabilities 守门 / skills（review_expense 主 skill） | 横向+S3 | ⬜ | hermetic 无 LLM；P2 增强项；契约数据 `expense-review-workflow-card-assertions.json` |
| **story 3 webhook —— 不建独立用例** | | | | |
| （并入业务用例参数化） | webhook 完成/异常回调、中间态不推送、幂等、大载荷、不受信、投递失败 | S3 | ⏸ | 守门在岗（EdpaAdapter capabilities pushNotifications=false）；待 webhook 传输层落地后作为新传输协议值在 ExpenseReview 等业务用例的参数化矩阵中展开（见 §3.8） |

**进度**：已覆盖 12 行（11 runnable + 1 partial；FEAT-001 主注册 3 类——在跑 2 类 + ⏸ reactive 1 类，另有跨特性证据 4 族）；⬜ 待新建 2 类（P1 1 类、P2 增强 1 类）；⏸ story 3 一族（不建独立类）；移出/不建/不可达/不适用 4 行。

**下一步优先级**：
1. **P1** ⬜ `ExpenseReviewWorkflowTaskGetTest`（搭 LLM 车次，顺带尾斜杠等价）
2. **P2** ⬜ `ExpenseReviewWorkflowCardDiscoveryTest`（主 SUT card 补钉，hermetic 不消耗 LLM）
3. **Enable** PlanAgentReactiveQueryTest：webflux jar 重建后移除类级 @Disabled
4. **Deferred** story 3 传输层落地后，在业务用例参数化矩阵中并入 webhook 传输值

---

## 2. 前置条件与共享约定

### 2.1 SUT 部署前置

- **主 SUT**：`expense-review-workflow:0.2.0-SNAPSHOT` 单 jar 双 profile——默认 profile 为 8 节点 DAG WorkflowAgent（Start→LLM(analyze)→Tool(check_policy)→LLM(audit)→Branch(route)→[risk=high: Questioner(approve)] / [risk=none: LLM(auto_approve)]→End）；`main` profile 为主控 ReActAgent，经远程 A2A 工具 review_expense 调 workflow。两 profile 同栈时框架把 workflow 的解析端口经 `--agent-runtime.remote-agents[0].url` 注入 main。
- **协议面用例栈**（已落 `cases/component/workflow_agent` 层）：`EdpaAdapterCardDiscoveryTest` 拉 `edpa-adapter` 单 agent 栈（envexplorer 由 YAML service-bindings 自动拉起，card 发现不依赖其可达），普通 HTTP GET + SDK `getAgentCard()`，不消耗 LLM；⬜ 待新建两类（`ExpenseReviewWorkflowCardDiscoveryTest` / `ExpenseReviewWorkflowTaskGetTest`）只拉 `expense-review-workflow` 单 profile（最纯净 WorkflowAgent 服务端）。栈启动均需 openjiuwen profile 环境（`SAA_*` 占位符解析）。
- **业务场景用例栈**：`expense-review-workflow` 单 agent 直连栈（ExpenseReviewWorkflowDirectAcceptanceTest，不经 main）；或 `expense-review-workflow` + `expense-review-main`（downstream 注入，AbstractExpenseReviewAcceptanceTest 三叶子）；或 plan-agent 直连栈（edpa-adapter + edpa-plan-agent，envexplorer 由 service-bindings 自动拉起）；或 gateway 拓扑（+ edpa-gateway，`-DGATEWAY_PROTOCOL=a2a|rest`）。
- 入口端点：A2A `POST /a2a`（与 `/a2a/`）；Agent Card `GET /.well-known/agent-card.json`（与 `/.well-known/agent.json`）；REST `POST /v1/query`（`stream:true/false`）。

### 2.2 共享测试基础设施

- 业务场景客户端：`InteractionFlow`（四协议参数化：`A2A_STREAM` / `A2A_SYNC` / `REST_QUERY` / `REST_QUERY_SYNC`）；`ConversationInteractionAdapter`（Conversation SPI ↔ InteractionFlow 桥，直连栈 stepUi 驱动）；`Conversation` + `RestVersatileTransport`（gateway 拓扑）。**webhook 传输层落地后按同型新增协议值接入本参数化体系**（见 §3.8）。
- 协议面用例客户端：Agent Card 用普通 HTTP GET（JDK `HttpClient`，经共享 `transport.HttpClients.newHttp1Client()`——明文端点禁用默认 HTTP_2 的 h2c Upgrade）+ SDK `getAgentCard()`；GetTask 走 SDK `A2aServiceClient`（正路径，不涉及 error code 分流限制）。数据驱动契约：`testdata/component/workflow_agent/<agent>-card-assertions.json`（dotted-path → 期望值）；SDK required 字段集复用 `testdata/component/protocol/a02-agent-card-required-fields.json`。同包类内各方法共享一次栈启动（`BaseManagedStackTest` 类级 `@BeforeAll` 构栈）。
- 事件归一化：`InboundExchange` / `A2aEventMapping`（终态/中断态归一，与 transport 无关）；状态轨迹断言器参照 AbstractExpenseReviewAcceptanceTest `assertStreamTrajectory` / `distinctStatesInOrder`。
- 断言库：AssertJ；JUnit 标签按层：`@Tag("integration")`（workflow_call 业务面）/ `@Tag("component") + @Tag("smoke")`（component 层协议面）；特性登记走 Allure `@Feature("FEAT-001: 标准化智能体服务入口")` + `@Story("wf.<slug>: …")`（`wf.` 前缀与 deepagent 姊妹档区分，报告树按 feature→story 分组）。
- Redis 变体（FEAT-003 交叉）：`PlanAgentDirectStreamingRedisTest` 已证 Redis TaskStore + checkpointer 激活；⬜ `ExpenseReviewWorkflowTaskGetTest` 可复用 redis profile 栈验证重启后快照可查（增强项，非必须）。

### 2.3 共享命名约定

- `sessionId` 用 `<scenario>-<protocol>`（ExpenseReview 现有约定）；`contextId` 用 `ctx-feat001-wf-<slug>-<uuid8>`，避免跨用例记忆串扰。
- 场景语句（复用现有校准）：场景1 超标报销 `"帮我审核这笔报销：机票5000，酒店3晚每晚800共2400，客户晚餐800"` ⇒ risk=high ⇒ INPUT_REQUIRED；续轮 `"approved"` ⇒ COMPLETED。场景2 合规报销 `"审核这笔报销：机票3000，酒店2晚每晚500共1000，餐费200"` ⇒ risk=none ⇒ 自动 COMPLETED。
- 堆栈泄露标志串：`java.io.IOException` / `Caused by:` / `Exception in thread` / `at java.base/` / `at org.springframework.` / `at reactor.`——任一命中即 FAIL（SSE 通用断言）。

---

## 3. 子用例设计

> 约定：按**合并后的测试类**组织小节，每个类内列方法级子用例；步骤用 G/W/T（Given/When/Then）；结论分 PASS/FAIL/INCONCLUSIVE。每条附**状态**与 **story** 行。

### 3.1 Agent Card 发现（横向，component/workflow_agent 层，hermetic 无 LLM）

> 协议面探针落 `cases/component/workflow_agent` 层（与 mainplan 的 `component/protocol/AgentCardDiscoveryTest` 同型）。edpa-adapter card 已全量落地（含 story 3 守门）；主 SUT expense-review-workflow 自身 card 补钉待新建（P2 增强项）。

#### ✅ 已落地：`EdpaAdapterCardDiscoveryTest` — edpa-adapter（versatile-adapter）card
- **状态**：✅ 已落地（2026-07-20）｜ **story**：横向 + S3 守门
- **FEAT 依据**：§2「A2A Agent Card 发现 / capabilities / skills」+ §3「`/.well-known/agent-card.json` + `/.well-known/agent.json`」+ §5.1.1「capabilities 必须反映当前版本对外承诺，不得夸大未激活能力」。
- **落点**：[EdpaAdapterCardDiscoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/component/workflow_agent/EdpaAdapterCardDiscoveryTest.java)（`@Tag("component") @Tag("smoke")`，@Feature FEAT-001，stories `wf.agent-card-endpoint` / `wf.agent-card-capabilities` / `wf.agent-card-skill`）；栈：`edpa-adapter` 单 agent（envexplorer 由 service-bindings 自动拉起，card 发现不依赖其可达）；普通 HTTP GET（`HttpClients.newHttp1Client()`）+ SDK `getAgentCard()`，不消耗 LLM。
- **方法 ↔ 子用例映射**：
  - `discoveryEndpointsAreReachableWithJsonMediaType`（WA-01.A）→ `wf.agent-card`：双入口均 200 + `application/json`，alias body 与主入口逐字段等价；
  - `sdkDiscoveryReturnsCardWithName`（WA-01.B）→ SDK 发现路径 identity（name=`versatile-adapter`）；
  - `cardDeclaresStreamingAndPushCapabilities`（WA-01.C）→ `wf.agent-card-capabilities`：`streaming=true` 硬断言 + **`pushNotifications=false`——原 `wf.webhook-not-advertised` 的 story 3 守门断言在岗**；
  - `cardExposesJsonRpcInterfaceContract`（WA-01.D）→ 接口契约：`supportedInterfaces` 含 JSONRPC 绑定且 url 落 `/a2a`，modes 收窄为 `["text"]`；
  - `servedCardMatchesContract`（WA-01.D 数据驱动）→ 契约 [edpa-adapter-card-assertions.json](../../src/test/resources/testdata/component/workflow_agent/edpa-adapter-card-assertions.json) 逐字段钉死（name / version / capabilities 三字段 / modes）；
  - `cardCarriesAllSdkRequiredFields` / `descriptionIsNonBlank` / `cardDeclaresVersatileBankProxySkill`（WA-02）→ `wf.agent-card-skills`：a2a-sdk 强制非空字段集（复用 `a02-agent-card-required-fields.json`）+ description 非空 + skills 含 `versatile-bank-proxy`。
- **争议点（待与开发对齐）**：`topLevelUrlAndPreferredTransportAreAbsent`（A2A 1.0 顶层 `url`/`preferredTransport` 应缺省，adapter 仍输出）、`providerOrganizationIsNotBlank`（adapter 空串）。两方法 DisplayName 已标 DISABLED 意图，但当前无 `@Disabled` 注解——真机首跑预期红，首跑时按实测对齐（补注解或修订契约）。
- **解锁后演化**：story 3 传输层落地后守门断言反转为「与实际部署配置一致」，并作为 webhook 传输参数化用例的前置探针。

#### ⬜ 待新建：`ExpenseReviewWorkflowCardDiscoveryTest` — 主 SUT card 补钉（P2 增强）
- **状态**：runnable（待新建 ⬜）｜ **story**：横向 + S3 守门
- **命名（按落地规则）**：包 `cases/component/workflow_agent`，类名 `<agent>CardDiscoveryTest` 同型；契约数据 `testdata/component/workflow_agent/expense-review-workflow-card-assertions.json`；`@Tag("component") @Tag("smoke")`，stories 复用 `wf.agent-card-endpoint` / `wf.agent-card-capabilities` / `wf.agent-card-skill`。
- **定位**：增强项——同族 `AgentCardController` 已有 mainplan（`component/protocol/AgentCardDiscoveryTest`）与 edpa-adapter 双证；本类钉主 SUT 自身差异字段：name / version、skills 含报销审核主 skill（main profile 经远程工具 review_expense 调本 Agent，id 实跑校准后钉死）、capabilities 守门同型。
- **G**：`expense-review-workflow` 单 profile 栈已启动（不消耗 LLM）。
- **W**：分别 `GET /.well-known/agent-card.json` 与 `GET /.well-known/agent.json`；读 capabilities / skills。
- **T**：双入口 200 + `application/json` + body 等价；`streaming=true` 且 `pushNotifications` 非 true（story 3 守门同型）；skills 非空、id 唯一、主 skill 存在。
- **PASS**：全满足。**FAIL**：任一入口 4xx/5xx / 守门失守 / skills 缺主 skill。**INCONCLUSIVE**：SUT 不可达。

### 3.2 JSON-RPC 错误面与 SDK 错误码 —— 移出 SIT（归 component 层）

- **涉及事实要求**：§5.1.2 分发语义 + §5.1.8 错误表（parse error / invalid request / method-not-found）+ §2「错误 response 尽量保留原 request id」。
- **决策**：按组织原则 2，非法输入场景与 A2A SDK 错误码验证不在 SIT 考虑——这些场景（`{not-json`、缺 method、未知 method、未知 taskId、空文本、坏 REST body 等）的被测对象是 JSON-RPC 解析与分发层组件及 SDK 行为，属 component 层测试职责；SIT 不建任何对应用例，也不断言具体错误码。

### 3.3 核心 A2A 方法（S1）

#### FEAT-001.wf.send-streaming-message — 流式 send
- **状态**：runnable（已覆盖）｜ **story**：S1
- **框架落点**：主注册——[ExpenseReviewWorkflowDirectAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java)（直连 workflow，A2A_STREAM：场景1 `SUBMITTED→WORKING→INPUT_REQUIRED→…→COMPLETED`，场景2 `SUBMITTED→WORKING→COMPLETED`，story `wf.direct-streaming`）；跨特性证据——[ExpenseReviewAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewAcceptanceTest.java)（main-routed 同轨迹，流程在 AbstractExpenseReviewAcceptanceTest 模板基类，FEAT-004）、[PlanAgentDirectStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java)（FEAT-004）、[TransferAfterBalanceAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/TransferAfterBalanceAcceptanceTest.java)（gateway a2a 模式，FEAT-002）、[PlanAgentDirectStreamingRedisTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingRedisTest.java)（Redis TaskStore 开启下流式主路径，FEAT-003）。

### 3.4 SSE wire 帧格式 —— 不建独立用例（组织原则 3）

- **FEAT 依据**：§3「每个 SSE event 必须使用 `event: jsonrpc`，data 为 JSON-RPC envelope」+ §5.1.4「final 或 interrupted 后当前 message stream 必须关闭」。
- **决策**：不为该事实要求单独建用例。SIT 的核心是以业务流程串通整体逻辑；SSE wire 帧格式属传输层实现面，`A2aStreamingTransport` / `A2aStreamingWire` 在收发信息时记录了完整日志——验收时通过核查现有流式用例（`FEAT-001.wf.send-streaming-message` 各落点类）的响应内容与传输层日志即可获得该层证据，无需绕过 SDK 直发原始请求做孤立断言。
- **若未来出现回归信号**（如 SDK 升级后消费异常、frame 解析报错），再回补 wire 级探针用例；届时注意读流自限窗口 + 读超时 + 硬截止，防 SUT 不关流导致用例长挂。

### 3.5 ⬜ `ExpenseReviewWorkflowTaskGetTest` — 异步查询（S1，需 LLM，P1）

> 原设计名 `WorkflowGetTaskTest`（workflow_call 层）按落地命名规则调整为 `ExpenseReviewWorkflowTaskGetTest`（`cases/component/workflow_agent` 层，与 EdpaAdapterCardDiscoveryTest 同包同栈型；story `wf.get-task`）。同型先例：mainplan 的 [AgentTaskGetTest](../../src/test/java/com/huawei/ascend/sit/cases/component/protocol/AgentTaskGetTest.java)（A-05，component/protocol 层）、deepagent 的 GetTaskTest（姊妹档）——两处已证 GetTask 正路径语义，workflow 侧仍无断言。

#### snapshotMatchesStreamTerminal — GetTask 快照与流内终态一致（顺带尾斜杠等价）
- **状态**：runnable（待新建 ⬜；现状部分覆盖——他 SUT 同型先例已证，workflow 侧未覆盖）｜ **story**：S1
- **FEAT 依据**：§2「异步查询」+ §3「GetTask 返回指定 task 当前快照」+ §4「查询长任务」+ §3「`POST /a2a` 与 `POST /a2a/`」。
- **缺口说明**：现有链路只消费流内终态 Task（InteractionFlow 明确「答案只从本地事件流读，不做 getTask 往返」），story 1 承诺的异步查询独立入口无显式断言，本方法补齐。
- **G**：`expense-review-workflow` 单 profile 栈已启动（需 LLM）。
- **W**：`SendStreamingMessage`（走 `/a2a`）跑场景2 至 COMPLETED 并记录 taskId → 随即 `GetTask(taskId)`（走 `/a2a/` 尾斜杠拼写）。
- **T**：① 快照 `status.state == COMPLETED`；② artifact/结果文本与流内终态 Task 一致（等价性断言，容差仅时间戳类字段）；③ 两种入口拼写在同一业务流内均正常服务（尾斜杠等价顺带成立，无需独立探针）。
- **PASS**：三条全满足。**FAIL**：GetTask 返回非 COMPLETED / 快照与流内终态漂移 / 任一拼写不可服务。
- **负路径边界**：未知 taskId 的 not-found 语义归 component 层（组织原则 2），本类不建。
- **增强（可选，搭 Redis 变体）**：redis profile 栈上 taskId 落 Redis TaskStore（`a2a:task` 键已由 PlanAgentDirectStreamingRedisTest 断言），GetTask 走 Redis 路径仍返一致快照。

### 3.6 WorkflowAgent HITL 中断/恢复（S1，已覆盖）

#### FEAT-001.wf.input-required — Questioner 中断 → 续接恢复
- **状态**：partial（已覆盖 A2A_STREAM；sync/REST 维度受限）｜ **story**：S1
- **FEAT 依据**：§5.1.6「handler 输出需要用户输入的中断时，Task 必须进入 input-required 类语义，而不是伪装成 completed」+ §5.1.4「interrupted 时 stream 必须关闭」。
- **现状**：场景1（超标报销）A2A_STREAM 已硬断言 `SUBMITTED→WORKING→INPUT_REQUIRED`、审批提示非空、续接 `"approved"`（携原 taskId+contextId）后 `WORKING→COMPLETED`、审核结果非空——WorkflowAgent Questioner 节点是 FEAT-001 input-required 语义在 SIT 侧的唯一真实触发面（deep-research SUT 上该状态不可达，见姊妹档 §6.3）。
- **受限维度**：INPUT_REQUIRED 在 A2A_SYNC / REST_QUERY / REST_QUERY_SYNC 下呈现不可靠（AbstractExpenseReviewAcceptanceTest 与 ExpenseReviewWorkflowDirectAcceptanceTest 类注释调试矩阵：REST 侧 INPUT_REQUIRED 透传未标定，属独立问题）。本档不把这些维度升为硬断言；REST 透传标定后回补。
- **框架落点**：现有 [ExpenseReviewWorkflowDirectAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java) 场景1（直连，story `wf.input-required`）+ [AbstractExpenseReviewAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/AbstractExpenseReviewAcceptanceTest.java) 场景1（main-routed，三叶子继承；redis 叶子另断经 redis 持久化恢复，FEAT-003 交叉）。

#### FEAT-001.wf.task-lifecycle — 状态序列单调性
- **状态**：runnable（已覆盖）｜ **story**：S1
- **FEAT 依据**：§5.1.6「至少经历 submitted/working，并以 completed/failed/canceled 或 interrupted 类状态收束」。
- **框架落点**：现有 `assertStreamTrajectory`（新轮严格 `containsExactly(SUBMITTED, WORKING, terminal)`；续轮宽松 `containsSubsequence(WORKING, terminal)`，容忍恢复时合法重发 SUBMITTED）——AbstractExpenseReviewAcceptanceTest 三叶子与 ExpenseReviewWorkflowDirectAcceptanceTest（story `wf.task-lifecycle`）双拓扑同型。

### 3.7 REST 兼容入口（S2，已覆盖）

#### FEAT-001.wf.rest-query — REST 流式与同步调用
- **状态**：runnable（已覆盖）｜ **story**：S2
- **FEAT 依据**：story 2 兼容目标——REST 调用方以 `POST /v1/query`（`stream:true` SSE / `stream:false` JSON）获得与 A2A 入口同语义的结果。
- **框架落点**：现有——[ExpenseReviewWorkflowDirectAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java)（直连，story `wf.direct-rest-query`）、[AbstractExpenseReviewAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/AbstractExpenseReviewAcceptanceTest.java) 三叶子（REST_QUERY / REST_QUERY_SYNC 两场景；场景2 全绿）、[PlanAgentDirectStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java)（REST_QUERY 直连）、[TransferAfterBalanceAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/TransferAfterBalanceAcceptanceTest.java)（`-DGATEWAY_PROTOCOL=rest` 整拓扑 rest 模式）；扩展对照——[PlanAgentReactiveQueryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentReactiveQueryTest.java)（`POST /v1/query/reactive` WebFlux ↔ MVC 流式等价，story `wf.rest-query`，类级 @Disabled 待 webflux jar）。
- **备注**：REST 无服务端 Task 概念（`conversation_id` 影子任务续轮），故 GetTask 类断言只属 A2A 入口；REST 的"查询"语义由 response body / SSE 终帧承载，已含在现有断言内。REST 侧非法输入（坏 body / 未知路径）归 component 层，不建用例。

#### FEAT-001.wf.rest-a2a-equivalence — REST 与 A2A 入口结果等价
- **状态**：runnable（已覆盖）｜ **story**：S1+S2
- **FEAT 依据**：§5.1.0「不得为不同来源/入口定义互相漂移的执行语义」。
- **设计说明**：四协议参数化矩阵**本身即等价性验证**——同一场景语句、同一 metadata、同一组断言（`.awaitState(...)` 归一化终态 + `.assertGenerated(...)` 非空/实质）在四种线协议上同时成立；任一协议漂移即矩阵对应单元变红。现有 main-routed（AbstractExpenseReviewAcceptanceTest 三叶子）与直连（ExpenseReviewWorkflowDirectAcceptanceTest，story `wf.rest-a2a-equivalence`）双拓扑矩阵，另加 PlanAgentReactiveQueryTest 的 reactive/MVC 同构对照（story `wf.rest-a2a-equivalence`，@Disabled 待 jar）。不另建新用例。

### 3.8 story 3 webhook —— 传输层抽象，不建独立用例（deferred 至传输层落地）

> **决策（组织原则 3）**：webhook 与 SSE、REST 一样是**传输层抽象**。story 3 落地后，它将作为新的传输协议值（与 `A2A_STREAM` / `A2A_SYNC` / `REST_QUERY` / `REST_QUERY_SYNC` 同型）接入 `MessageProtocol` 参数化体系与 `InteractionFlow` 传输层，**在现有业务用例（ExpenseReview 场景1/场景2 等）的参数化矩阵中展开复用**——同一个报销审核业务流程，换一种完成通知的传输方式再跑一遍，即完成 webhook 维度的 SIT 覆盖。**本档不为 webhook 建任何独立测试类。**

- **状态**：**deferred**（story 3 未实现、接口未明）｜ **story**：S3
- **FEAT 依据**：§2 webhook 家族 MUST 项 + §5.1.3 回调语义。
- **当前守门**：story 3 未实现期间，`EdpaAdapterCardDiscoveryTest#cardDeclaresStreamingAndPushCapabilities`（§3.1，已落地）断言 card `pushNotifications=false`，防止能力夸大——这是 story 3 在 SIT 侧的唯一在跑哨兵（主 SUT 补钉类落地后同型加一道）。
- **传输层落地后的复用展开方式**（供实现期参考，不新增用例类）：
  1. `MessageProtocol` 新增 webhook 协议值（如 `A2A_WEBHOOK`），配套 `MessageTransport` 适配器内部完成 push config 注册、挂接收端、等待回调、归一化为 `InboundExchange` 终态——对业务用例而言与现有四协议同构；
  2. ExpenseReview 场景2（单轮 COMPLETED）加 webhook 协议参数 → 断言终态 COMPLETED 且结果文本与流式口径一致（等价性矩阵自然扩展一格）；
  3. ExpenseReview 场景1（INPUT_REQUIRED 中断/恢复）加 webhook 协议参数 → 天然承载 §5.1.3「中间态不推送 + INPUT_REQUIRED 非结果态不回调」与 §5.2「webhook HITL 继续执行 OUT」的语义：中断期间无回调、续接完成后唯一一次回调；
  4. FAILED 回调语义：本 SUT（最下游）无逻辑合理的故障注入面，触发面留待 versatile / plan-agent 侧拓扑展开，或随业务用例自然出现的失败承载；
  5. 大载荷引用、notification id 幂等、不受信 target、投递失败不改终态等契约项，待接口契约（payload 阈值 / id 承载 / 信任模型）定义后，以场景断言形式并入对应业务用例，仍不独立成类。

### 3.9 业务故障注入 —— 本 SUT 不适用（归 versatile / plan-agent 侧）

- **决策（组织原则 2）**：SIT 的故障覆盖只采纳**逻辑合理的故障模拟**。WorkflowAgent（expense-review-workflow）处于调用链最下游——对外仅有 LLM 依赖，无 downstream agent 可注入故障；downstream 故障 mid-stream、下游超时等场景对本 SUT 不成立，**本档不提出**。
- **承接方**：此类场景由后续 versatile / plan-agent 侧 SIT 设计承接（plan-agent → adapter → envexplorer 链路存在真实下游，且注入面需同样按"逻辑合理"原则筛选，不合理的注入不提出）。
- **关联语义的处理**：FEAT-001 §5.1.6/§5.1.8「handler exception → failed Task + 结构化错误 payload」在本 SUT 上无合理注入面，标记为不适用（`FEAT-001.wf.task-failed-semantics`），由 versatile 侧设计一并承接。

---

## 4. 框架落点汇总

| 测试类 | 覆盖子用例 | story | 状态 | 类状态 |
|---|---|---|---|---|
| [ExpenseReviewWorkflowDirectAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/ExpenseReviewWorkflowDirectAcceptanceTest.java) | direct-streaming / direct-blocking / direct-rest-query / rest-a2a-equivalence / input-required / task-lifecycle / workflow-agent | S1+S2 | runnable / partial | 已有（FEAT-001 主注册，直连 workflow 四协议矩阵） |
| [EdpaAdapterCardDiscoveryTest](../../src/test/java/com/huawei/ascend/sit/cases/component/workflow_agent/EdpaAdapterCardDiscoveryTest.java) | agent-card / agent-card-capabilities（含 webhook-not-advertised 守门）/ agent-card-skills | 横向+S3 | runnable | 已有（component 层，hermetic；2 争议点待对齐） |
| [PlanAgentReactiveQueryTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentReactiveQueryTest.java) | rest-query（reactive/MVC 对照）/ rest-a2a-equivalence | S2 | runnable | 已有，@Disabled 待 webflux jar |
| [AbstractExpenseReviewAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/AbstractExpenseReviewAcceptanceTest.java) 三叶子 | send-streaming / send-blocking / rest-query / rest-a2a-equivalence / input-required / task-lifecycle | S1+S2 | runnable / partial | 已有（FEAT-004/003 注册，跨特性证据；cluster 叶子 @Disabled 待真机） |
| [PlanAgentDirectStreamingTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingTest.java) | send-streaming / rest-query（直连 plan-agent 栈） | S1+S2 | runnable | 已有（FEAT-004 注册，跨特性证据） |
| [TransferAfterBalanceAcceptanceTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/TransferAfterBalanceAcceptanceTest.java) | entry-source-equivalence（gateway 双模式） | 横向 | runnable | 已有（FEAT-002 注册，跨特性证据） |
| [PlanAgentDirectStreamingRedisTest](../../src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/PlanAgentDirectStreamingRedisTest.java) | send-streaming + Redis TaskStore | S1 | runnable | 已有（FEAT-003 注册，交叉证据） |
| `ExpenseReviewWorkflowTaskGetTest` | get-task 正路径（顺带 jsonrpc-endpoint-slash） | S1 | runnable | ⬜ 待新建（P1，需 LLM） |
| `ExpenseReviewWorkflowCardDiscoveryTest` | 主 SUT card 补钉（含守门、review_expense 主 skill） | 横向+S3 | runnable | ⬜ 待新建（P2 增强，hermetic） |
| （webhook 不建独立类） | webhook 全族 | S3 | **deferred** | 待传输层落地后并入业务用例参数化 |
| jsonrpc-error-surface / sse-wire-format / no-handler / task-failed-semantics | — | S1 | component / 不建 / 不可达 / 不适用 | 不建 |

落点目录约定（2026-07-22 起，按落地现实）：业务流程面用例落 `src/test/java/com/huawei/ascend/sit/cases/integration/workflow_call/`（包 `com.huawei.ascend.sit.cases.integration.workflow_call`）；**协议面探针（card 发现 / GetTask 异步查询）落 `src/test/java/com/huawei/ascend/sit/cases/component/workflow_agent/`**（包 `com.huawei.ascend.sit.cases.component.workflow_agent`，契约数据 `src/test/resources/testdata/component/workflow_agent/`）——与已落地的 EdpaAdapterCardDiscoveryTest 同型。

### 4.1 落地优先级建议

**P0 · hermetic 协议面（✅ 已完成 2026-07-20）**
- ✅ `EdpaAdapterCardDiscoveryTest`（10 方法 + 数据驱动契约 json；钉死 edpa-adapter card 发现面与 story 3 守门；2 争议点待与开发对齐）

**P1 · 入口方法补齐（1 个类，搭 LLM 车次）**
- ⬜ `ExpenseReviewWorkflowTaskGetTest`（顺带尾斜杠等价；同型先例 mainplan AgentTaskGetTest A-05）

**P2 · 主 SUT card 补钉（1 个类，hermetic 增强项）**
- ⬜ `ExpenseReviewWorkflowCardDiscoveryTest`（同族 AgentCardController 已有 mainplan + edpa-adapter 双证，本类钉主 SUT 差异字段）

**Enable · 待 SUT jar**
- PlanAgentReactiveQueryTest：`webflux.enabled=true` 重建 jar 后移除类级 @Disabled（当前 jar REST_REACTIVE 404）

**Deferred · story 3 webhook 传输层落地后**
- 新增 webhook 传输协议值，接入 `MessageProtocol` 参数化体系；在 ExpenseReview 等业务用例的协议矩阵中扩展一格，不新增用例类（见 §3.8）。

---

## 5. 运行方式

```bash
# 业务场景（FEAT-001 主注册：直连 workflow 四协议矩阵，需 LLM）
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowDirectAcceptanceTest test

# 跨特性证据（main-routed / 直连 plan-agent，需 LLM）
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewAcceptanceTest test
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentDirectStreamingTest test

# gateway 拓扑双模式（a2a 默认 / rest）
./mvnw -Dtest.env=openjiuwen -Dtest=TransferAfterBalanceAcceptanceTest test
./mvnw -Dtest.env=openjiuwen -DGATEWAY_PROTOCOL=rest -Dtest=TransferAfterBalanceAcceptanceTest test

# 协议面 component 层（Agent Card 发现，hermetic 不消耗 LLM；需 Docker envexplorer + 本地 adapter jar）
./mvnw -Dtest.env=openjiuwen -Dtest=EdpaAdapterCardDiscoveryTest test

# 待新建类（落地后）
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowTaskGetTest test        # 需 LLM
./mvnw -Dtest.env=openjiuwen -Dtest=ExpenseReviewWorkflowCardDiscoveryTest test  # hermetic

# REST reactive 对照（待 webflux jar 重建后移除 @Disabled）
./mvnw -Dtest.env=openjiuwen -Dtest=PlanAgentReactiveQueryTest test
```

---

## 6. 风险与备注

### 6.1 story 3 阻塞项与解锁条件

story 3（webhook 点对点 A2A 异步回调）未实现且接口未明，SIT 侧不建独立用例。解锁路径：

1. **接口契约定义**：注册面 / 回调 payload / notification id 承载 / payload 阈值 / 信任模型（与 deepagent 姊妹档评审 §1/§3/§4 同源）；
2. **webhook 传输层落地**：作为新传输协议值接入 `MessageProtocol` + `InteractionFlow` 传输层（含接收端生命周期与 `InboundExchange` 归一化）；
3. **业务用例参数化扩展**：ExpenseReview 场景1/场景2 加 webhook 协议参数（见 §3.8），契约项（大载荷/幂等/不受信/投递失败）以场景断言并入；FAILED 回调触发面留待 versatile 侧拓扑。

**过渡守门**：story 3 未实现期间，`EdpaAdapterCardDiscoveryTest#cardDeclaresStreamingAndPushCapabilities`（已落地）断言 card `pushNotifications=false`（主 SUT 补钉类落地后同型加一道）；传输层落地后该断言反转为"与部署配置一致"。

### 6.2 实现层风险

- **真机 LLM 依赖**：业务场景用例（直连/main-routed 四协议矩阵、⬜ `ExpenseReviewWorkflowTaskGetTest`）需 `LLM_API_KEY` 等 SAA_* 环境；协议面 card 用例（已落地 EdpaAdapter + ⬜ 主 SUT 补钉）刻意避开 LLM 调用（栈启动仍需 openjiuwen profile 环境解析占位符 + Docker envexplorer / 本地 jar）。
- **Allure 注册面分散**：FEAT-001 主注册类为 ExpenseReviewWorkflowDirectAcceptanceTest / PlanAgentReactiveQueryTest / EdpaAdapterCardDiscoveryTest；ExpenseReview 族、PlanAgentDirectStreamingTest、TransferAfterBalanceAcceptanceTest 已改注册 FEAT-004/002/003——报告树中本特性证据分散在多个 feature 下，覆盖对照以本档 §1.2 看板为准。
- **EdpaAdapter 争议点无注解**：`topLevelUrlAndPreferredTransportAreAbsent` / `providerOrganizationIsNotBlank` 两方法 DisplayName 标了 DISABLED 意图但当前无 `@Disabled` 注解，真机首跑预期红——首跑时按实测对齐（补注解或修订契约 json）。
- **SDK error code 不分流不再是 SIT 约束**：A2A SDK `1.0.0.Final` 不按 JSON-RPC error code 分流具体子类的问题，随错误码断言整体归 component 层而不再影响 SIT——SIT 新增类只走正路径 SDK API 与普通 HTTP GET。
- **WorkflowAgent 结果帧类型**：SUT 把结果发在自定义 `workflow_final` 类型下（非标准 `answer`），共享分类器 `LlmPayload` 已映射为 ANSWER；新建涉及结果文本断言的用例必须用 `.assertGenerated(...)`（generatedText 超集），不得只读 `answerText()`。
- **REST INPUT_REQUIRED 透传未标定**：场景1 在 sync/REST 协议下为调试矩阵已知红（独立问题），本档不将其升为硬断言；标定后回补 input-required 的 REST 维度。

### 6.3 与其他特性/文档的关系

- **与 deepagent 姊妹档**：同一 FEAT-001 在不同 SUT 的映射。deepagent 档覆盖 deep-research SUT（含 7 项评审待澄清摘要，其协议面含 JSON-RPC 错误面等非法输入用例）；本档覆盖 WorkflowAgent SUT，按本档组织原则将非法输入/SDK 错误码场景整体归 component 层、webhook 作为传输层抽象并入业务用例、最下游 SUT 不提出 downstream 故障场景——两档在该维度是**有意的分层差异**（deepagent 档落地在先，其错误面与 webhook 占位用例可视后续分层约定调整），非覆盖缺口。本档独有差异点：① input-required 真实触发面（Questioner HITL，deep-research 上不可达）；② REST `/v1/query` 兼容入口四协议矩阵（含 reactive/MVC 对照）；③ gateway 下行双模式转发证据；④ 直连 workflow 四协议矩阵（不经 main 中转）。
- **与 mainplan component 层**：`component/protocol/AgentCardDiscoveryTest` 与 `AgentTaskGetTest`（A-05）是同族协议面先例（mainplan SUT）；本档协议面落 `component/workflow_agent`（EdpaAdapterCardDiscoveryTest 已落地、⬜ ExpenseReviewWorkflow 两类待新建）与之同型分层，SDK required 字段集 `a02-agent-card-required-fields.json` 跨 agent 复用。
- **与后续 versatile / plan-agent 侧 SIT 设计**：downstream 故障 mid-stream、下游超时、webhook FAILED 回调触发面等依赖真实下游链路的场景，统一由该侧承接（注入面按"逻辑合理"原则筛选）。
- **与 FEAT-003**：Redis TaskStore + checkpointer 由 PlanAgentDirectStreamingRedisTest 覆盖；本档 GetTask 的 Redis 增强项属交叉验证，非 FEAT-001 必须。
- **与 FEAT-011/012（网关转发）**：edpa-gateway 下行 a2a/rest 双模式在本档仅作"入口来源等价"证据，转发语义本身的验收归 FEAT-011/012 档。
- **与 FEAT-004/005/006（outbound）**：main→workflow 的远程工具调用发起侧语义（发现/安装/调用）不属本档；本档只断 workflow 作为服务端的入口面。
- **多 workflow 路由**：MultiWorkflowDirectStreamingTest 当前 @Disabled（场景问题），且其属 adapter 路由能力而非 FEAT-001 入口事实，不列入本档矩阵；若后续恢复运行，其入口面断言可被 streaming / entry-source-equivalence 复用。

### 6.4 已知历史缺陷记录

ExpenseReviewAcceptanceTest 类注释曾记录 analyze/audit 节点 `json response format` 确定性失败（code=101004），2026-07-14 实跑确认当前 jar 已不复现；该缺陷属组件级问题，本档不再作为任何故障注入的候选来源（§3.9 已说明本 SUT 不适用故障注入场景）。
