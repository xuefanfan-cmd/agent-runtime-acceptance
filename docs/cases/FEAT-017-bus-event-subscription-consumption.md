---
feature_id: FEAT-017
test_type: reactagent
scope: bus-enabled-travel-demo
deployable_units: [agent-runtime-java, agent-bus, agent-solution]
sut: three BUS-enabled travel-demo ReactAgent runtimes
features: [FEAT-017, FEAT-013, FEAT-014]
updated: 2026-08-29
---

# BUS-enabled travel-demo 三 Agent 链路验收：运行时订阅消费总线事件消息

## 1. 测试目标

验证 Runtime 订阅并消费客户端调用与 A2A 调用事件，建立或查询真实 A2A Task，发布接受、响应、等待输入、流准备、失败和终态投影，并在三 Agent BUS 链路中保持租户、关联、trace、幂等和数据面边界。测试只使用公开事件、Task 查询、SSE 和业务结果。

## 2. 范围与非范围

范围：

- 客户端与 A2A 创建、查询、流订阅事件的消费与响应投影。
- mainplan→trip→hotel 的 BUS 调用链、`INPUT_REQUIRED` 和结果性终态。
- 信封字段、target/tenant、deadline、payload 表示和 method 兼容性校验。
- message 重投、创建幂等、投影补发和 ACK 边界。
- `ACCEPTED`、`STREAM_READY`、SSE 数据面、重订阅及非法引用。

非范围：

- Broker/RDC 内部表、消费线程、序列化实现、私有 topic 和内部 TaskStore。
- token、progress 或 artifact frame 经 BUS 实时传输；实时数据只走点对点 SSE。
- FEAT-013 的 Gateway 端状态折叠和 FEAT-014 的调用方内部编排实现。

## 3. 事实来源

| 文档 | 用途 |
|---|---|
| `develop/02-features/FEAT-017-bus-event-subscription-consumption.md` | 定义八类事件、Task 语义、投影、ACK、幂等、隔离与流边界。 |
| `develop/03-architecture/L2-Low-Level-Design/agent-runtime` 下 FEAT-017 设计 | 定义 Runtime consumer、事件名、inline/payloadRef、大小限制和错误表面。 |
| FEAT-013 与 FEAT-014 设计 | 定义客户端调用及 A2A 调用事件的上下游信封语义。 |
| 测试仓同主题 ReactAgent 用例 | 仅用于确认三 Agent 拓扑、公开 producer/observer 和场景映射。 |

## 4. 部署拓扑

```text
event producer -> RocketMQ/Relay -> runtime embedded consumer
                                    travel-mainplan
                                      -> BUS -> travel-trip
                                                   -> BUS -> travel-hotel
response observer <- RocketMQ/Relay <- accepted/response/input-required/ready/terminal

A2A SDK client -> Task query / point-to-point SSE by taskId + streamRef
RDC -> three runtime service identities and routes
```

- 三个 Agent 使用同一套正式构建产物并配置不同 service-id 和 producer group。
- 客户端事件默认以 mainplan 为 owner，A2A 事件按场景以 trip 或 hotel 为 owner。
- 每个场景使用唯一 tenant、message、correlation、trace、idempotency 和敏感 canary。

## 5. 测试场景矩阵

| ID | 场景 | 前置条件 | 步骤 | 期望结果 | Fixture |
|---|---|---|---|---|---|
| FEAT-017.client.create.accepted | 客户端创建调用被接受 | G：三 Agent、consumer、BUS caller 和 observer 就绪 | W：发布合法 `CLIENT_INVOCATION_REQUESTED` 到 mainplan | T：唯一 `INVOCATION_ACCEPTED` 含 taskId、correlation 和幂等结果，Task 可查询 | event producer + observer + three Agents |
| FEAT-017.a2a.create.accepted | A2A 创建调用被接受 | G：trip/hotel BUS 链路就绪 | W：发布合法 `A2A_CALL_REQUESTED` 到 trip | T：唯一 `A2A_CALL_ACCEPTED`，Task、tenant、correlation 和 trace 保留 | producer + observer + trip/hotel |
| FEAT-017.client.create.response-terminal | 客户端阻塞调用完成 | G：三 Agent 可在窗口内完成 | W：发布完成型客户端调用 | T：accepted 后依次可见 response 与唯一 terminal，Task 查询终态一致 | deterministic LLM + observer |
| FEAT-017.a2a.create.response-terminal | A2A 阻塞调用完成 | G：trip/hotel 可完成 | W：发布完成型 A2A 调用 | T：accepted 后有 A2A response 与唯一 terminal，关联字段保持 | deterministic LLM + observer |
| FEAT-017.client.input-required | 客户端调用等待输入 | G：mainplan 可确定触发用户输入等待 | W：发布缺少业务信息的客户端调用 | T：产生 `INVOCATION_INPUT_REQUIRED`，含 taskId、提示、correlation 与恢复引用，不声明完成 | controllable LLM + observer |
| FEAT-017.a2a.input-required | A2A 调用等待输入 | G：A2A target 可触发等待 | W：发布缺少业务信息的 A2A 调用 | T：产生 `A2A_CALL_INPUT_REQUIRED` 并保留 A2A 关联 | controllable LLM + observer |
| FEAT-017.client.query.existing | 客户端查询已有 Task | G：本租户已有客户端 Task | W：发布客户端查询事件 | T：返回 Task 快照且不创建新 Task | event producer + Task query |
| FEAT-017.a2a.query.existing | A2A 查询已有 Task | G：本租户已有目标 A2A Task | W：发布 A2A 查询事件 | T：返回目标 Task 快照，不以调用方本地 ID 替代目标 taskId | event producer + Task query |
| FEAT-017.client.query.not-found | 客户端查询不可见 Task | G：准备随机或其他租户 taskId | W：发布客户端查询 | T：确定性不可见错误，不创建 Task、不枚举其他租户 | producer + observer |
| FEAT-017.a2a.query.not-found | A2A 查询不可见 Task | G：准备随机或跨租户 taskId | W：发布 A2A 查询 | T：失败投影或等价错误，不创建 Task、不泄露数据 | producer + observer |
| FEAT-017.client.subscribe.existing | 客户端订阅已有 Task | G：客户端流 Task 可订阅 | W：发布客户端流订阅事件 | T：stream-ready 含 taskId 和非空 streamRef，不创建 Task | producer + SSE client |
| FEAT-017.a2a.subscribe.existing | A2A 订阅已有 Task | G：A2A 流 Task 可订阅 | W：发布 A2A 流订阅事件 | T：A2A stream-ready 可建立 SSE，来源事件族不漂移 | producer + SSE client |
| FEAT-017.client.subscribe.not-found | 客户端订阅不可见 Task | G：准备随机或不可见 taskId | W：发布客户端订阅事件 | T：Task 不可见错误，不隐式创建 Task | producer + observer |
| FEAT-017.a2a.subscribe.not-available | A2A 订阅不可用流 | G：Task 已终态或不支持 SSE | W：发布 A2A 订阅事件 | T：stream-not-available 错误，不创建流 | producer + observer |
| FEAT-017.envelope.event-type | 未知事件类型 | G：broker 和 observer 就绪 | W：发布未知 eventType | T：按合同丢弃，不产生业务投影或 Task | producer + observer |
| FEAT-017.envelope.required-fields | 信封必填字段缺失 | G：可构造不完整信封 | W：分别缺失 messageId、tenant、source、target、correlation、eventType 或 deadline | T：确定性拒绝，不生成泄漏性投影或 Task | event producer |
| FEAT-017.envelope.target-mismatch | 目标服务不匹配 | G：Runtime identity 已知 | W：发布 targetServiceId 不属于该 Runtime 的事件 | T：订阅过滤边界排除消息，不响应、不建 Task、不 fallback | producer + observer |
| FEAT-017.envelope.tenant-mismatch | 租户不匹配 | G：信封 tenant 与 Runtime 范围不同 | W：发布携带其他租户 taskId 的事件 | T：拒绝或不可见错误，不查询或复用其他租户数据 | producer + observer |
| FEAT-017.envelope.deadline | deadline 非法 | G：可控制事件时间 | W：发送过期或超前视窗口 deadline | T：确定性拒绝或失败，不创建或修改 Task | producer + observer |
| FEAT-017.envelope.payload-reference | payload 表示冲突 | G：可构造 inline/ref | W：发送二者同时存在或同时缺失的事件 | T：payload-reference 错误，不建 Task、不把引用当 JSON | producer + observer |
| FEAT-017.payload-ref-only.current | payloadRef-only 行为 | G：Runtime 只处理 inline payload | W：发布仅含 payloadRef 的事件 | T：payload-empty 或等价失败，不进入 A2A bridge | producer + observer |
| FEAT-017.payload-inline-limit | inline payload 超限 | G：已知 65,536 bytes 上限 | W：发布超限 inline payload | T：payload-too-large 错误，不建 Task、不进入 Agent | producer + canary scanner |
| FEAT-017.payload.invalid-json | 非法 JSON 载荷 | G：inlinePayload 应承载 A2A JSON-RPC | W：发布不可解析载荷 | T：invocation-failed 且含 payload-invalid 类别，不建 Task | producer + observer |
| FEAT-017.payload-method-compatibility | method 与事件族不兼容 | G：可构造 A2A method | W：query 携带创建 method 或 create 携带订阅 method | T：payload-invalid 或等价失败，不执行错误操作 | producer + observer |
| FEAT-017.delivery.message-redelivery | 相同 messageId 重投 | G：创建请求可保持非终态 | W：原样重投同 tenant/messageId 信封 | T：只产生一个 Task 和一次副作用，可补发等价投影 | producer + observer |
| FEAT-017.admission.same-key-same-request | 同幂等请求重试 | G：同 tenant、同 key、同摘要 | W：用不同 messageId 重试相同创建请求 | T：复用同一 taskId，不创建第二 Task | producer + observer |
| FEAT-017.admission.same-key-conflict | 幂等键冲突 | G：已有同 key admission | W：使用不同正文重试同 key | T：幂等冲突错误，不执行冲突请求 | producer + observer |
| FEAT-017.projection.republish | 投影失败后补发 | G：publisher 可短暂故障且 Task 已接受 | W：注入一次发布失败后恢复 | T：Task 不回滚；相同 eventId 或等价语义补发；终态不重复 | fault proxy + observer + Task query |
| FEAT-017.ack.long-task | 长任务 ACK 不等待终态 | G：Agent 可保持非终态 | W：发布创建事件并观察 receipt 和 Task | T：Task 进入控制面并写投影后确认消费，ACK 早于终态 | receipt observer + controllable LLM |
| FEAT-017.stream.ready-separation | accepted 与 stream-ready 分离 | G：流式 Task 可运行 | W：发布流式创建并观察两个投影 | T：accepted 表示创建，ready 表示可订阅，ready 不表示完成 | observer + SSE client |
| FEAT-017.stream.reference-boundary | streamRef 脱敏 | G：已收到 stream-ready | W：检查 streamRef 和关联投影 | T：可关联 taskId，不含 URL、endpoint、token、SSE frame 或大正文 | observer + scanner |
| FEAT-017.stream.sse-off-bus | SSE 数据不进入 BUS | G：流式 Task 可产生内容 | W：建立 SSE 并扫描同 correlation 的 BUS 投影 | T：token/progress/artifact frame 只走 SSE，BUS 仅含控制投影或引用 | SSE client + observer |
| FEAT-017.stream.resubscribe | 断开后重订阅 | G：Task 非终态且原流断开 | W：使用 taskId+streamRef 再订阅 | T：仍归属原 Task，不创建新 Task、不重新执行 Agent | SSE client + Task query |
| FEAT-017.stream.invalid-reference | 非法 streamRef | G：准备错误、过期和跨租户引用 | W：发起 SSE 订阅 | T：失败且不泄露存在性，不创建 Task、不发送其他租户数据 | SSE client + observer |
| FEAT-017.stream.terminal-not-available | 终态 Task 不签发流引用 | G：Task 已终态 | W：发布流订阅事件 | T：stream-not-available，不签发新引用、不重放 token | producer + observer |

## 6. Test Agent 与 Fixture

| 对象 | 类型 | 设计说明 |
|---|---|---|
| travel-demo-mainplan / trip / hotel | 真实 SUT | 分别承担入口、中游和叶子 Agent，均装配正式 BUS consumer/caller。 |
| agent-service-bus-consumer | 真实 SUT 组成 | 承载事件消费、A2A Task bridge、投影与响应发布。 |
| RDC | 真实依赖 | 注册三 Agent identity 和 BUS 路由，内部表不作为断言。 |
| RocketMQ + Relay | 真实依赖 | 提供公开事件投递、消费确认与响应投影边界。 |
| A2A/Client SDK | 黑盒 Fixture | 查询 Task，并以 taskId+streamRef 建立 SSE。 |
| event producer / response observer | 黑盒 Fixture | 发布信封并捕获响应和 receipt，不调用 Runtime 内部 API。 |
| deterministic LLM / fault proxy | Fixture | 控制完成、等待、长任务和一次投影发布故障。 |

## 7. 关键链路断言

- 创建、查询和订阅事件都必须保持 tenant、target、correlation、trace 与事件族。
- `ACCEPTED` 只表示 Task 创建或复用，`STREAM_READY` 只表示可订阅，`TERMINAL` 才表示结果性终态。
- 拒绝/失败不得创建或修改不应存在的 Task，也不得回显正文、凭据、物理路由、endpoint 或 token。
- message 重投与业务幂等分别判定；同键同文复用 Task，同键异文拒绝。
- ACK 在控制面状态和必要投影建立后发生，不等待长任务终态。
- 实时 token、progress 和 artifact frame 仅走点对点 SSE，不进入 BUS 控制事件。

## 8. 执行策略

- 先验证三 Agent identity、RDC 路由、broker/relay 和 observer 的公开就绪面，再发布业务事件。
- 创建、查询、订阅、信封负向、幂等和流边界使用相互隔离的 tenant 与关联标识。
- 投影故障场景先建立正常基线，再只故障 publisher；Task 查询用于证明执行状态不回滚。
- 异步等待采用有界 observer 与 Task 轮询，不用 Broker 内部状态或 Runtime 日志替代公开投影。
- payloadRef-only、大载荷、订阅和远端调用场景按文档合同保留独立 Oracle，不因环境是否具备对应依赖而合并或删除。
