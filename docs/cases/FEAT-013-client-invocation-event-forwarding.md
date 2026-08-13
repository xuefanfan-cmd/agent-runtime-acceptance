---
feature_id: FEAT-013
feature_title: 总线支持客户端调用事件转发
sut: 正式 Gateway + Event Bus + FEAT-017-enabled multi-react-travel-demo
status: designed-dependency-gated
tags: [blackbox, contract, integration, agent-bus, feat-013]
---

# FEAT-013 - 总线支持客户端调用事件转发测试设计

> 通过 Gateway 发起真实客户端调用，并在 Event Bus 的公开消息边界观察请求与响应事件，验证事件信封、双向转发、幂等、租户隔离、大载荷引用及 token 流不入总线。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `D:\code-agent\feature-docs\develop\02-features\FEAT-013-client-invocation-event-forwarding.md` |
| L2 | `D:\code-agent\feature-docs\develop\03-architecture\L2-Low-Level-Design\agent-bus\feat-013-client-invocation-event-forwarding.md` |
| Feature/L2 仓 | `main@7e1632dd96d49dad05747d8804631234be3cf457`，读取日期 2026-08-06 |
| acceptance 仓 | `main@eb5e3f20ca39f0a8bc647c1ca17b8a637370ce05`，读取日期 2026-08-06；本文为工作区设计变更 |
| 测试 Agent | `com.openjiuwen.example:travel-demo-mainplan/trip/hotel:0.1.0`，外部 JAR |

L2 为 `as-built`，但其正式 Gateway 未落地、runtime response producer 仍 in-flight；因此完整往返保持 `dependency-gated`。Feature 未把 `INVOCATION_INPUT_REQUIRED` 列入 FEAT-013 事件族和验收清单，L2 中该投影来自 FEAT-017 修订；本方案只把它视为 FEAT-012/017 依赖事实，不作为 FEAT-013 单特性通过条件。未查阅产品源码。

### 1.2 范围

FEAT-013 的被测黑盒边界是 Gateway、Event Bus 与 runtime 之间的事件协议，不是 broker/outbox 的代码实现。

| 纳入 | 不纳入 |
|---|---|
| CLIENT_INVOCATION_REQUESTED 与 INVOCATION_ACCEPTED/REJECTED/FAILED/RESPONSE/STREAM_READY/TERMINAL 的外部事件事实 | outbox/inbox 表、relay worker 状态机、RocketMQ adapter 类、SQL/RLS 实现方式 |
| 外层治理字段、A2A payload/payloadRef、双向关联、at-least-once 去重 | Gateway 五态折叠细节（FEAT-012）、runtime TaskStore/执行逻辑 |
| 租户隔离、重复投递、非法信封、大载荷引用、物理机制透明 | registry 查询行为（FEAT-016）、服务间 A2A 事件族（FEAT-014） |
| STREAM_READY 与 ACCEPTED 分离，实时 token/SSE 不进入 Bus | Gateway SSE 桥接实现本身（FEAT-012） |

以下真实 Agent 双向用例要求正式 Gateway、Event Bus 和 runtime 事件端均可执行，因此为 **dependency-gated**；只验证 relay 单侧或临时 Gateway 测具不能宣称 FEAT-013 端到端通过。

## 2. Fixture 与证据

- 拉起 RocketMQ（或满足同一公开事件契约的 broker）、Event Bus relay、RDC、正式 Gateway 及带 FEAT-017 的三个 travel Agent。
- Agent 仍以真实外部 JAR 运行；完成/等待输入可使用测试侧可控的外部 LLM endpoint。明确拒绝/接受前失败必须由 runtime 文档化的公开 admission 规则稳定触发；若该规则尚未提供，对应参数分支保持 dependency-gated，不能用测试代码直接构造响应事件冒充 runtime。
- 测试消费者只订阅 FEAT-013 公开事件族，并按唯一 tenant/correlation 过滤；不得读取产品数据库或反射产品类。
- 每次调用记录 client 响应、Agent 请求、事件类型/治理头/载荷摘要。敏感 payload 只比较哈希/canary 是否缺失，不在失败消息打印正文。
- 真实 Agent 完成或业务失败是往返业务证据；事件消费者只证明 FEAT-013 事件转发语义。INPUT_REQUIRED 由 FEAT-012/017 取证，不计入本特性通过条件。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-013.event.round-trip` | Feature §2/§4/§5.1.1-5.1.7 | blackbox | dependency-gated, P0 | design-only | 双向事件、信封、accepted/rejected/response/failed/terminal、接受后超时 | 公开事件、Gateway 响应、真实 Agent 结果 | INPUT_REQUIRED 不作为本特性 Oracle |
| `FEAT-013.event.delivery-safety` | Feature §2/§5.1.6/§5.1.8 | contract | dependency-gated, P0 | design-only | 重复投递、非法/跨租户、大载荷引用、broker 故障 | broker 公共边界、目标零增量、错误 | 不计为正式 Gateway/runtime E2E |
| `FEAT-013.event.stream-boundary` | Feature §2/§4/§5.1.5 | blackbox | dependency-gated, P0 | design-only | ACCEPTED/STREAM_READY 分离、token/SSE 不入 Bus | 公开事件与点对点 SSE | SSE bridge 实现归 FEAT-012 |
| `FEAT-013.deferred.task-operations` | Feature §2/§4/§6；L2 缺口 | blackbox | deferred | design-only | UNKNOWN 同键恢复、流重连、GetTask、CancelTask/终态 | 待正式 Gateway/runtime 控制事件合同 | 当次 UNKNOWN 仍由 round-trip 覆盖；不生成空测试 |

### L2 本特性能力追踪（依赖满足后执行）

| L2 当前交付能力 | 覆盖用例 |
|---|---|
| 客户端请求与服务端接受/拒绝/失败/响应/终态双向转发 | `FEAT-013.event.round-trip` |
| 外层治理信封、A2A payload、inline/payloadRef、大载荷引用 | 前两条用例合并覆盖 |
| 同步结果、已接受 Task、当次 UNKNOWN、明确拒绝/失败 | `FEAT-013.event.round-trip` 与 delivery-safety |
| 流请求、ACCEPTED/STREAM_READY 分离、A2A SSE 与实时数据边界 | `FEAT-013.event.stream-boundary` |
| bus 投递幂等、租户隔离、物理机制透明 | `FEAT-013.event.delivery-safety` |
| registry 只支撑路由、Event Bus 不拥有 Task、各单元仅通过公开契约耦合 | `FEAT-013.event.round-trip` |
| UNKNOWN 同键恢复、流重连、GetTask、CancelTask | `FEAT-013.deferred.task-operations` |
| INVOCATION_INPUT_REQUIRED | 非 FEAT-013 Feature Oracle；由 FEAT-012/017 验证 |

## 4. 详细用例

### FEAT-013.event.round-trip - 客户端调用事件往返

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.1-§5.1.7。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 定义的事件信封、双向状态、当次 UNKNOWN、Task owner 和物理机制透明语义；L2 仅补充当前事件名和交付门禁。
- **G**：真实 mainplan 已注册；Gateway 选择 BUS；事件观察器按唯一 tenant/correlation 订阅；测试侧确定性 LLM 与 runtime 公开 admission 输入分别产生正常完成、明确拒绝、明确失败以及已接受但未在响应窗口完成。
- **W**：通过 Gateway 参数化 SendMessage，等待每种真实 Agent 状态投影完成；拒绝/接受前失败只使用公开 runtime admission 输入；测试不替换或反射任一内部单元，也不直接发布伪响应投影。
- **T**：
  - 恰有一个有效 `CLIENT_INVOCATION_REQUESTED` 业务事实到达目标服务；信封完整包含 `messageId`、`eventType`、`tenantId`、`traceId`、`correlationId`、`idempotencyKey`、opaque `routeHandle`、`capability`、`sourceServiceId`、`targetServiceId`、`deadlineMillisEpoch`、`payloadPolicy`，以及按返回路由需要的 `originalCaller`；A2A JSON-RPC 只在 `inlinePayload` 或 `payloadRef`；
  - runtime 创建/复用 Task 后存在 `INVOCATION_ACCEPTED(taskId)`；窗口内完成产生 RESPONSE/TERMINAL；明确拒绝产生 `INVOCATION_REJECTED` 且无伪 taskId；明确服务失败产生 `INVOCATION_FAILED` 和 failed terminal；已接受但响应超时向 client 投影为 ACCEPTED_WITH_TASK，不丢失 taskId；接受窗口内无任何接受/拒绝/失败/响应事实时返回当次 UNKNOWN；
  - 响应事件沿相同 tenant/correlation 回到 Gateway，client 得到真实 travel 结果；client/runtime 不需要知道 broker topic、outbox、worker 或物理 endpoint；
  - Event Bus 不执行 Agent，也不产生/改写最终业务内容；registry 只提供目标路由，既不消费/生产本次 invocation 事件，也不拥有 Task；测试输入输出不含某个 broker 产品的 topic/queue/worker 字段。
- **不应断言**：outbox/inbox/relay 状态机、topic 名、固定重试次数、Agent 自然语言全文。
- **失败归类**：事件/状态/业务结果不符为 Failure；正式 Gateway/runtime producer 缺失为 Skipped；观察器或环境异常为 Error。
- **方法**：`feat013ClientInvocationEventsRoundTripThroughBusToRealAgent()`。
- **标签**：类级 `@Feature("FEAT-013: 总线支持客户端调用事件转发")`、`@Tag("feat-013")`、`@Tag("integration")`；方法级 `@Tag("blackbox")`、`@Story("FEAT-013.event.round-trip: 客户端调用事件往返")`、`@Tag("story-feat-013-event-round-trip")`。
- **DisplayName**：`Feat-013 客户端调用和真实 Agent 响应通过事件总线往返`。

### FEAT-013.event.delivery-safety - 投递安全与载荷边界

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§5.1.6/§5.1.8。
- **测试类型**：contract。
- **Oracle 来源**：Feature 的事件幂等、租户隔离、非法信封、大载荷引用和 broker 故障语义。
- **G**：可在正式 broker 公共入口重复投递同一 message/event id；另准备可由 client 以同一 clientInvocationId/idempotencyKey/原始正文重试的创建请求、小型 inline A2A 请求、跨 tenant route handle、缺字段/过期 deadline 信封、大正文 canary、短时 broker 不可用故障，以及接受窗口内无任何服务端投影的调用。
- **W**：依次执行小型 inline 请求、client 同键重试、bus 重复投递、非法/过期/跨租户事件、大正文调用、broker 暂停后恢复和无投影调用。
- **T**：
  - client 同键同文重试由 Gateway 复用同一 idempotencyKey，返回同一 taskId/业务结果且不产生第二个服务端 Task；
  - 同一 bus message/event 的重复投递只形成一个有效下游业务事实，重复响应不形成第二个 client 结果；
  - 小型 A2A envelope 可 inline 且治理字段仍只在外层；缺字段、deadline 过期或 tenant 不匹配被拒绝且不创建 Task、不跨租户 fallback；错误可编程且不泄漏 route handle 解析或 endpoint；
  - 超阈值正文通过 payloadRef/artifactRef，事件正文不含大 canary；目标 runtime 仍取得原业务输入；
  - broker 暂时不可用按投递契约重试；在接受窗口内未恢复且没有任何服务端接受/拒绝/失败/响应事实时返回当次 `UNKNOWN`，不伪造接受或成功 Task；恢复后的新调用可正常完成；
  - 接受窗口内没有接受、拒绝、失败或响应事实时只返回当次 `UNKNOWN` 观测态，不误报成功或失败。
- **不应断言**：broker 产品重试码、数据库表、worker 状态或内部去重 key。
- **失败归类**：重复副作用、跨租户投递或正文泄漏为 Failure；broker 夹具异常为 Error；正式事件合同缺失为 Skipped。
- **方法**：`feat013DeliveryIsTenantScopedAndUsesPayloadReferences()`。
- **标签**：`@Story("FEAT-013.event.delivery-safety: 投递安全与载荷边界")`、`@Tag("story-feat-013-event-delivery-safety")`、`@Tag("contract")`。
- **DisplayName**：`Feat-013 事件投递保持幂等与租户隔离并使用载荷引用`。

### FEAT-013.event.stream-boundary - 流控制与实时数据分离

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.5。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的 ACCEPTED/STREAM_READY 分离和 A2A SSE 数据面边界。
- **G**：真实 mainplan streaming 可用；事件观察器对 payload 递归检查 token/frame canary。
- **W**：Gateway SendStreamingMessage 并消费本次 SSE 至终态。
- **T**：请求仍以控制事件投递；`INVOCATION_ACCEPTED` 与 `INVOCATION_STREAM_READY` 是两个可区分事实；Bus 事件可以带 streamRef，但不得带 token chunk、`data:` SSE frame 或完整流正文；client 的实时内容来自点对点 A2A SSE；最终 TERMINAL 只表达终态事实。
- **不应断言**：streamRef 编码、SSE 内部连接实现、固定帧数或 topic。
- **失败归类**：token 入 Bus 或状态事实混淆为 Failure；正式链路缺失为 Skipped；夹具异常为 Error。
- **方法**：`feat013BusCarriesStreamReadinessButNeverRealtimeTokens()`。
- **标签**：`@Story("FEAT-013.event.stream-boundary: 流控制与实时数据分离")`、`@Tag("story-feat-013-event-stream-boundary")`、`@Tag("blackbox")`。
- **DisplayName**：`Feat-013 总线只转发流准备和终态而不承载实时 token`。

## 5. 文件、执行与退出标准

计划一个文件：`src/test/java/com/huawei/ascend/sit/cases/integration/agent_bus/Feat013ClientInvocationEventBlackboxTest.java`。

relay 启动、topic 创建和数据库 migration 不单独设特性用例；事件往返已覆盖等价可用性。具体 broker 产品可替换，不把 RocketMQ 特有重试码写入产品断言。

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；默认 Maven 仓库 `~/.m2/repository`；Docker 提供 broker、PostgreSQL 和故障代理。正式 Gateway 与 runtime producer/consumer 的坐标和 acceptance 别名当前缺失，是完整往返门禁；Event Bus 制品还须记录 classifier 和构建 SHA。事件 payload、确定性 LLM 和 canary 由测试自动准备。

```powershell
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-013 test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-013-event-delivery-safety test
```

测试结束关闭观察器、Gateway/Event Bus/Agent/RDC 和容器，恢复 broker/网络并确认端口和临时数据清理。退出标准：Feature 当前事件族完整通过或明确门禁；长期 Task 操作有 deferred 处置；contract 与 blackbox 分开统计，不以临时 Gateway 或手工成功事件冒充全链。
