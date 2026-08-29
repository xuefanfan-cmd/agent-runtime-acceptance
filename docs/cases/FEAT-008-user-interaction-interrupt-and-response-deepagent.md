---
feature_id: FEAT-008
test_type: deepagent
scope: v0730
deployable_units: [agent-runtime-java, agent-runtime-ext-java]
sut: real DeepAgent runtime with local and one-hop remote A2A execution paths
features: [FEAT-008]
updated: 2026-08-29
---

# DeepAgent 验收：运行时用户交互式任务中断与请求响应

## 1. 测试目标

验证真实 DeepAgent Runtime 在本地及一跳远端交互等待点上的公开 A2A 行为：`INPUT_REQUIRED` 的非终态语义、同一 `taskId/contextId` 续接、任务隔离、多轮恢复、等待期间查询与订阅，以及恢复失败的标准错误表面。测试只观察标准消息、流、Task 快照、错误与公开审计事件。

## 2. 范围与非范围

范围：

- 本地和远端交互中断投影、同步/流式入口以及同 Task 续接。
- 同 Task 业务语义不匹配、非同 Task 隔离、单等待点幂等和多轮交互。
- 等待期间查询、订阅、长时挂起和当前实例恢复。
- Task/context 不可访问、协议非法、终态续接、恢复上下文不可用、本地恢复失败和远端续接失败。
- 中断建立、续接、再次中断和收束的公开审计关联与脱敏。

非范围：

- 跨实例或重启后的持久化恢复；由任务状态缓存特性承接。
- 新 A2A method、Part 类型、表单 schema、审批协议或客户端专用 wire 格式。
- 内部 Rail、checkpoint、Registry、路由算法、数据库 key、线程和远端内部 Task。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-008-user-interaction-interrupt-and-response.md` | 定义 MUST/SHOULD、用户旅程、错误语义和非范围。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-008-user-interaction-interrupt-and-response.md` | 定义 Runtime 公开 A2A 观察面、状态/错误语义和跨特性职责。 |
| 测试仓 `DeepAgentInteractiveInterruptAcceptanceTest` | 仅用于确认公开触发方式、Fixture 与场景映射。 |

## 4. 部署拓扑

```text
SIT A2A client
  -> real DeepAgent runtime public A2A endpoint
       -> optional real remote DeepAgent A2A dependency
  <- A2A response/SSE events + GetTask/Subscribe observations
```

- 测试只使用 Agent Card、`SendMessage`、`SendStreamingMessage`、`GetTask` 和 `SubscribeToTask`。
- 远端依赖用于形成远端等待和受控失败，不替换被测 Runtime。
- 每个场景使用独立 context、请求标识和数据，Fixture 负责就绪探测与资源回收。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| FEAT-008.local-interrupt.resume | 本地中断后同 Task 续接 | G：本地 DeepAgent 可确定地产生交互等待 | W：触发等待，观察 `INPUT_REQUIRED`，复用原 `taskId/contextId` 提交合法输入 | T：等待为非终态；原链路恢复并完成、失败或再次等待；不创建替代 Task | A2A client + local DeepAgent |
| FEAT-008.remote-interrupt.resume | 远端中断投影与续接 | G：本地 Runtime 和真实远端 Agent 就绪 | W：触发远端等待，经本地 Task 续接 | T：客户端只看到本地 Task；标识保持；远端结果收敛到该 Task | SSE collector + remote Agent |
| FEAT-008.same-task.semantic-mismatch | 同 Task 业务语义不匹配 | G：Task 处于 `INPUT_REQUIRED` | W：提交格式合法但不回答提示的文本 | T：Runtime 不按业务含义预先拒绝；消息交回智能体决定再次等待、失败或完成 | deterministic prompt fixture |
| FEAT-008.non-current-task.isolation | 非同 Task 不抢占旧等待 | G：Task A 正在等待，准备独立 Task B | W：创建或推进 B，再分别查询 A/B | T：B 使用独立标识；A 保持等待；状态、消息和结果不串线 | isolated contexts |
| FEAT-008.waiting.get-task | 等待期间 Task 查询 | G：已观察到 `INPUT_REQUIRED` | W：续接前后分别调用 `GetTask` | T：续接前返回同一 Task 的等待快照；续接后状态离开原等待并收敛 | Task snapshot probe |
| FEAT-008.waiting.subscription | 等待期间订阅 | G：Task 可通过流式入口进入等待 | W：记录中断事件和流结束，再订阅或查询原 Task | T：中断以可恢复语义呈现，不伪装完成；后续状态仍可观察 | SSE/subscription probe |
| FEAT-008.single-wait.idempotency | 单等待点一次推进 | G：等待点具备公开副作用 canary | W：重复提交同一续接或模拟客户端重试 | T：等待点和业务副作用只推进一次；重复请求返回同一结果或标准冲突 | duplicate-submit client |
| FEAT-008.multi-round | 同一 Task 多轮交互 | G：智能体确定性要求至少两轮输入 | W：逐轮提交合法输入且始终复用原标识 | T：至少两次 `WORKING -> INPUT_REQUIRED -> WORKING`；Task 身份保持并最终收敛 | bounded multi-round journey |
| FEAT-008.long-wait.resume | 长时等待后续接 | G：部署治理窗口覆盖设定等待时长 | W：等待期间只查询/订阅，延迟后续接 | T：FEAT-008 不自行引入过期；上下文可用时恢复原 Task，治理拒绝时返回标准错误 | hold-open controller |
| FEAT-008.current-instance.resume | 当前实例恢复 | G：Task、等待点和恢复上下文位于同一 Runtime 实例 | W：进入等待后通过标准入口续接 | T：回到正确执行链路，Task/context 不变 | managed Runtime |
| FEAT-008.error.task-unavailable | Task/context 不存在或不可访问 | G：准备随机或其他租户标识 | W：查询或续接该标识 | T：返回标准不可见/不可访问错误，不恢复智能体、不隐式创建旧 Task | negative A2A client |
| FEAT-008.error.protocol-format | 续接协议格式非法 | G：准备缺字段、类型错误或非法结构 | W：通过标准入口提交请求 | T：返回协议错误，不推进等待点、不创建隐式 Task | protocol-negative client |
| FEAT-008.error.invalid-state | 终态 Task 不允许续接 | G：Task 已 `COMPLETED` 或 `FAILED` | W：使用原标识发送续接 | T：返回标准状态冲突，不重新执行原工具或智能体 | terminal-task client |
| FEAT-008.error.recovery-context | 恢复上下文不可用 | G：Task 可见，受控 Fixture 使恢复上下文不可用 | W：提交合法同 Task 续接 | T：返回可区分的恢复失败，不伪装业务不匹配或成功 | recovery-context fault fixture |
| FEAT-008.error.local-recovery | 本地恢复执行失败 | G：本地等待已建立，恢复阶段依赖可受控失败 | W：续接原 Task | T：标准错误表面保留 Task/context 关联，不静默丢失 | local recovery fault fixture |
| FEAT-008.error.remote-resume | 远端续接失败 | G：远端等待已投影到本地 Task，远端故障可控 | W：使用本地标识续接 | T：公开结果明确表达业务失败及稳定错误信息，不误报成功、不重复委派 | remote fault controller |
| FEAT-008.audit.lifecycle | 中断生命周期审计 | G：部署提供公开审计或事件观察面 | W：执行中断、查询、续接、再次中断及收束并按公开标识查询 | T：事件覆盖关键生命周期并关联 Task/context；敏感输入脱敏 | public audit/event observer |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| DeepAgent Runtime | 真实 SUT | 承载公开 A2A 同步/流式、Task 查询与订阅语义。 |
| remote DeepAgent | 真实依赖 | 用于远端中断、续接和失败传播，不改变 FEAT-008 Oracle。 |
| A2A client/event collector | Fixture | 生成隔离标识并收集事件、快照、错误和终态，不写内部状态。 |
| interrupt/fault canary | Fixture | 产生确定性等待、重复提交或受控恢复失败，仅通过公开边界取证。 |
| public audit observer | Fixture | 查询公开审计事件并验证关联及脱敏，内部日志不能替代。 |

## 7. 关键链路断言

- `INPUT_REQUIRED` 是非终态；合法续接必须保持 Task/context 并交回原执行链路。
- Runtime 不解释续接文本的业务含义，业务智能体可以再次中断、失败或完成。
- 非同 Task 请求不得抢占旧等待；单等待点不得重复推进或产生重复业务副作用。
- 查询、订阅、协议错误、访问错误、状态冲突和恢复失败必须通过公开结果区分。
- 不断言固定自然语言、固定追问次数、内部 Rail 顺序、数据库 key、缓存 TTL 或远端内部 Task。

## 8. 执行策略

- 先验证本地与远端基本中断/续接，再覆盖隔离、多轮、订阅、长等待和失败矩阵。
- 每个场景使用独立 run/context；重复提交场景固定请求 canary；Task A/B 使用不同 context。
- 异步场景优先收集事件，并以有界 `GetTask` 轮询补充最终快照。
- 恢复上下文、本地恢复、生命周期审计和长时等待场景必须使用受支持的公开 Fixture 或观察面；缺少前提时不得用内部状态或临时实现替代。
