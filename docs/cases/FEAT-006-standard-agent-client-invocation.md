---
feature_id: FEAT-006
feature_title: 客户端发起标准化智能体调用
sut: 合同层=测试 JVM 内的正式 agent-client SDK；E2E=正式 Client SDK -> Runtime / Gateway -> Runtime -> 真实 Agent
status: partial
tags: [blackbox, contract, integration, feat-006]
updated: 2026-08-29
input_maturity: merged
automation_status: implemented-and-contract-verified
---

# FEAT-006 - 客户端发起标准化智能体调用测试设计

> 由业务应用只使用正式 `agent-client` facade 调用 `travel-mainplan`，在 Runtime 直连与 Gateway 转发两条路径上
> 验证既有 STREAMING 行为以及本期新增的已知 Task 断点重连、周期重试和观察熔断；业务侧只操作
> `invocationRef`，不把 A2A `taskId` 当业务操作句柄。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `03-Upstream-Docs/develop/02-features/FEAT-006-standard-agent-client-invocation.md`；file commit `79dadca7`；SHA-256 `07B42090BF7E2A15888D5920765F225EFCD46706F8F215FDEDCE05B865265F6B` |
| L2 | `03-Upstream-Docs/develop/03-architecture/L2-Low-Level-Design/agent-client/Feat-Func-006-standard-agent-client-invocation.md`；file commit `3479d310`；SHA-256 `A33949F6862F25357E2E1496F6AD7C8F9DBC9FD08E6BD1BB11765B01A41CDC53`；`authority: non-authoritative` |
| Feature/L2 仓 | `03-Upstream-Docs/main@1277db6c`，读取日期 2026-08-29，merged；Feature 内容未更新 |
| acceptance 仓 | `feat/reconnect-acceptance@ca7b79a5d63247e693c465ff3c6f7ae98673b758` |
| Client ISSUE206 复现/校正回归基线 | `common@1cf9b574be46e12380e77575290ac927b9cd2577`；无产品修复 commit；重建 JAR SHA-256 `4200A1F380FFB8F6EE6B5DA26EFC6CB2313AF3A602435977A24E6E6343219376` |
| 测试 Agent | `com.openjiuwen.example:travel-demo-mainplan/trip/hotel:0.1.0`，由 `application-openjiuwen.yml` 和 `SutStack` 以外部 JAR 拉起 |

当前权威设计/L2 和最新代码已包含正式 Client 公共 API、`GetTask`、`SubscribeToTask`、恢复重试与两种
`EndpointType` 路径，不能再沿用“生产 Client 未落地、查询/重订阅全部 deferred”的旧结论。代码只用于确认
可执行入口和实现准备度，测试 Oracle 仍以权威设计为准；代码存在不等于 SIT 已通过。

2026-08-29 最新增量裁决：L2 把 Subscribe 与其后的 GetTask 定义为一个恢复周期，两者均失败时连续失败只
增加 1，并新增默认 6 次的已知 Task 总恢复预算。但是 Feature 仍写“连续三次重试请求失败”，且要求
WORKING 成功后继续重试直到终态或连续三次失败。L2 标明 `non-authoritative`，因此不能单独覆盖 Feature MUST。

| ID | Feature | L2 `3479d310` | 当前处置 |
|---|---|---|---|
| `D006-206-01` | 连续 3 个失败重试请求后熔断 | 一个 Subscribe+GetTask 失败周期只计 1 次；默认 3 个周期、6 个恢复请求 | `D006-206-DEC-01` 已确认本期采用 L2，覆盖 `F006-B01/B02/B04/B06` |
| `D006-206-02` | WORKING 成功后继续重试，直到终态或连续 3 次失败 | WORKING 清零连续失败但不返还总预算；默认最多 6 个恢复周期 | `D006-206-DEC-01` 已确认本期采用 L2，新增 `F006-B05` |

原 ISSUE206 四条 FAIL 保留为历史事实；周期级计数和总预算已由 `D006-206-DEC-01` 冻结为本期 Oracle。更正后自动化在同一 Client 源码基线重建制品上精确执行，结果为 `14 executed / 14 PASS / 0 failure / 0 error / 0 skipped`；XML SHA-256 为 `712B4B408F683DE865F9EEA975B15244EC1247520D5D4118464EB34DC8A0BD91`。合同层已 PASS；GitCode ISSUE206 已于 2026-08-29 回填结论并关闭，远端状态为 `CLOSED`。

### 1.2 范围

本方案保留既有 `STREAMING invoke`、conversation、invocation 回显、归一化事件、状态投影、继续输入和
错误分类设计，并增量验证已知 taskId 后的断流恢复、`GetTask` 即时快照、`SubscribeToTask` 重订阅、
周期重试和观察熔断。测试不直接调用 Client 内部 transport、映射表或状态存储。

FEAT-007 的工具注册、审批、执行和去重由其单特性用例验证；本方案只以“不暴露工具时普通调用不受影响”和 FEAT-007 闭环结果作为等价证据。

当前 `agent-runtime-acceptance` 的 `com.huawei.ascend.sit.client.AgentClient` 仍只是验收辅助类，不能替代正式
Client SDK。正式 Client JAR 已可本地构建，但真实 E2E 仍受 travel fixture JAR、LLM 配置和可执行环境门禁；
公共 MockWebServer 合同成功不能替代 Runtime/Gateway 真实链路结论。

### 1.3 Client SUT 与 fixture 边界

Client SDK 是嵌入业务应用 JVM 的库，不是监听入站端口的独立服务。生产中由业务应用调用其 Java
facade，SDK 再主动向 Runtime 或 Gateway 发送 HTTP/JSON-RPC/SSE。合同测试中的角色如下：

| 角色 | 当前实现 | 是否属于本层 SUT |
|---|---|---|
| SDK 宿主和测试驱动 | JUnit JVM，调用 `client.invoke(...)` 等公开 API | 否；只负责加载和驱动产品库 |
| 被测对象 | 正式 `agent-client-sdk-for-jvm` JAR 构建的 `AgentClient` 实例 | 是 |
| 对端 fixture | `ClientSdkBlackboxFixture` 内的 MockWebServer，按脚本返回 HTTP/SSE | 否；它模拟 SDK 所依赖的 Runtime/Gateway 协议边界 |

```text
合同：JUnit 宿主 -> [正式 Client SDK SUT] -> [可控 Runtime/Gateway 协议端点 fixture]
E2E： JUnit 宿主 -> [正式 Client SDK -> 正式 Gateway/Runtime -> 真实 Agent]
```

合同层可精确制造断流、静默 SSE 和错误序列，判定 SDK 自身的恢复行为；它不验证业务应用的独立部署、
配置装配或真实 Runtime/Gateway 联通性，这些由 E2E 承接。

## 2. 前置条件与证据

- Client 合同层由 JUnit JVM 直接加载正式 SDK JAR，不需要另外启动“Client 服务”，也不依赖 LLM；
  MockWebServer 只是 Runtime/Gateway 对端 fixture，不得写成 Client SUT 或真实链路。
- 由 `SutStack` 按 hotel -> trip -> mainplan 拉起 `multi-react-travel-demo` 三个外部 JAR；使用有效 LLM。
- 正式 Client 只切换公开 `EndpointType` 与 endpoint URL：直连时配置 Runtime，主链路时配置 Gateway；
  业务测试代码不得配置 routeHandle、broker、topic 或 taskId。
- 每个测试生成唯一 `conversationId`、业务标记和请求文本；服务端响应需包含该轮业务语义。
- 主要证据为产品 client facade 返回对象、事件流、最终业务结果以及平台公开审计；Gateway/runtime 增量日志只用于证明请求确实到达真实 Agent和敏感字段未泄漏，不检查内部结构。
- `diagnosticTaskRef` 即使存在也只断言“非必填、非操作性”，测试后续步骤始终使用 `invocationRef`。
- 断流通过 acceptance 现有 `FaultLink.resetPeer()/restore()` 在 client 与 Gateway 之间制造；网络超时使用独立外部延迟代理（Toxiproxy latency toxic 或等价黑盒 HTTP 代理）并把 client 超时调到测试值，不能声称现有 `FaultLink` 已提供 latency API。未知 TaskState 分支使用只改写一个状态枚举值的黑盒 HTTP/SSE 协议代理，其他帧仍来自真实 Agent，禁止用 fake Agent 替代。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-006.streaming.lifecycle` | Feature §2/§4/§5.1.1-5.1.4；L2 §2.1 | contract | runnable, P0 | partial（公共合同已实现） | STREAMING 创建、回显、事件、终态、同 conversation 新 invocation | client facade、脚本化 SSE、请求记录 | 真实 Agent 结果由 E2E 补充 |
| `FEAT-006.streaming.continue-input` | Feature §2/§4/§5.1.3；L2 §2.1 | contract | runnable, P0 | partial（公共合同已实现） | INPUT_REQUIRED、新 invocation 关联续接、多等待状态消歧、错误关联拒绝 | invocationRef、状态投影、请求记录 | 工具治理不在本特性 |
| `FEAT-006.streaming.failure-boundary` | Feature §5.1.4/§5.1.6；L2 §5.2/§6 | contract | runnable, P0 | partial（公共合同已实现） | 网络、路由、服务端、业务失败和 SSE 中断分类 | client 错误、Failed 投影、请求记录 | 真实网络边界由 E2E 补充 |
| `FEAT-006.streaming.unknown-state-contract` | Feature §5.1.5；L2 §2.1/§3.4 | contract | runnable, P1 | implemented | 未识别 TaskState 映射 UNKNOWN 且不崩溃 | SDK 公开事件/快照 | 只证明 Client 兼容合同 |
| `F006-R01` | 当前 Feature/L2 断点续行 | contract + E2E | runnable, P0 | implemented；合同与 ReAct/DeepAgent/Workflow E2E 落点齐备 | 已知 taskId 的 SSE 断开自动恢复原 Task | Client 请求序列、invocation 投影、原 taskId | Workflow 最终结果投影 blocked |
| `F006-R02` | 当前 Feature/L2 idle timeout | contract + E2E | partial, P1 | implemented | SSE idle timeout 触发恢复 | 公开 transport 短超时、静默订阅、请求序列 | 真实 Agent 未命中空窗可 INCONCLUSIVE |
| `F006-R03` | 当前 Feature/L2 查询恢复 | contract + E2E | runnable, P0 | implemented；合同与风险 E2E 落点齐备 | `GetTask` 返回即时快照 | Client 公开投影、wire | Workflow 原始 DataPart 存在，但 Client 查询快照未公开，blocked |
| `F006-R04` | 当前 Feature/L2 终态竞态 | contract + E2E | runnable, P0 | contract-verified；已补结构化终态错误和真正 INVALID_PARAMS 对照 | Subscribe 错误后 GetTask 收敛 | 请求顺序、结构化错误码、单次 future 结算 | message 兼容只作增强证据 |
| `F006-B01-B04` | 当前 Feature/L2 重试熔断 | contract + E2E | runnable, P0 | contract-verified；按完整失败周期计数 | 周期重试、阈值、成功清零、熔断不 Cancel | 请求/周期数、间隔、错误分类、Task 快照 | 合同层已 PASS；真实 E2E 单独记账 |
| `F006-B05` | L2 已知 Task 总恢复预算 | contract | runnable, P0 | contract-verified | 默认 6 次及 `maxKnownTaskRecoveryAttempts` 可配置性；WORKING 不返还预算 | 周期数、最终投影、completion、无 Cancel | 默认/自定义预算已 PASS |
| `F006-B06` | 当前 Feature/L2 invocation 隔离 | contract | runnable, P0 | contract-verified | 连续失败数和总恢复预算按 invocation 隔离 | 两个 invocation 的最终状态、invocationRef、请求序列 | 合同层已 PASS |
| `F006-E01` | 当前 Feature 双 Endpoint | contract + E2E | runnable, P0 | implemented | Runtime 直连与 Gateway 主链路业务表现一致 | 正式 Client、真实 Agent、taskId/canary | ReAct 双路径落点齐备 |
| `F006-E02` | DeepAgent Gateway 风险路径 | E2E | runnable, P1 | partial；基础恢复落点已实现，恰好一次 Oracle 待补 | 长流断开后恢复原 Task 并完成 | 原 taskId、最终快照、业务 marker | 远程节点恰好一次 Oracle 仍 partial |
| `F006-E03` | Workflow INPUT_REQUIRED 风险路径 | E2E | blocked, P1 | implemented；Client 结果投影 blocked | 断流后恢复原 Workflow Task 并续轮 | 原 taskId、INPUT_REQUIRED/COMPLETED 快照、Runtime 原始 DataPart | Client completion/getInvocation 均未公开 Runtime 终态 DataPart |
| `FEAT-006.deferred.cancel-and-create-retry` | 当前 Feature OUT/既有决策 | boundary | deferred | design-only | Cancel、未取得 taskId 时创建安全重发/幂等 | 不执行 | 后续需求承接 |
| `FEAT-006.streaming.concurrency-isolation` | Feature §5.1.4；L2 §4.2/§9.4/§9.6/§9.8 | contract | runnable, P0 | implemented（FEAT-026 交叉验证） | 并发 invocation 状态与事件隔离、重复 completion 幂等 | client 事件流、快照隔离、请求计数 | close 竞态等待公开 API |
| `FEAT-006.streaming.child-input-boundary` | Feature §5.1.3；L2 §4.6/§9.3 | contract | runnable, P1 | implemented（FEAT-026 交叉验证） | 子节点 input_required 不结算根 Task | 状态投影、树快照 | 多 pending 批量续传另行门禁 |
| `FEAT-006.streaming.sse-protocol-contract` | Feature §5.1.6；L2 §4.3.1/§7/§9.5 | contract | partial, P1 | partial（FEAT-026 交叉验证） | 多订阅、JSON Content-Type 和标准 SSE | SDK 公开事件/快照、错误码 | 自定义多 data/comment 帧待 mock 扩展 |
| `FEAT-006.streaming.runtime-direct` | Feature §2；L2 §4.1/§9.7 | contract | partial, P1 | partial（FEAT-026 交叉验证） | Runtime 直连 body 级 wire 隔离 | Runtime wire fixture | header 级隔离待 fixture 扩展 |

### 当前交付能力追踪

| L2 当前交付能力 | 覆盖用例 |
|---|---|
| facade 创建、STREAMING、conversation 传入/委托生成、字段传递、invocation 回显、统一平台入口、普通多轮 | `FEAT-006.streaming.lifecycle` |
| Accepted/Status/Content/InputRequired/Completed/Failed 归一化与 TaskState 只读投影 | 两条用例合并覆盖 |
| ToolView 上报与端侧工具结果自动续跑 | `FEAT-006.streaming.lifecycle` 的显式工具分支；FEAT-007 闭环提供交叉证据 |
| 用户补充输入、目标消歧、续接幂等与关联错误 | `FEAT-006.streaming.continue-input` |
| 网络/路由/A2A/业务/SSE 错误分类和拓扑隐藏 | `FEAT-006.streaming.failure-boundary` |
| 未识别 TaskState 的 UNKNOWN 兜底 | `FEAT-006.streaming.unknown-state-contract` |
| GetTask、SubscribeToTask、恢复重试与观察熔断 | `F006-R01-R04`、`F006-B01-B06` |
| 双 Endpoint 公开行为一致 | `F006-E01` |
| Cancel、未取得 taskId 时创建安全重发/幂等 | `FEAT-006.deferred.cancel-and-create-retry` |
| 并发调用隔离（≥20 invocation）、close/terminal 竞态资源释放、随机化属性测试 | `FEAT-006.streaming.concurrency-isolation` |
| 子节点 input_required 不结算根、根 INPUT_REQUIRED 才公开 pending | `FEAT-006.streaming.child-input-boundary` |
| SSE 协议边界（多 data 行/注释/JSON Content-Type→STREAMING_UNAVAILABLE）、延迟启动 | `FEAT-006.streaming.sse-protocol-contract` |
| Client→Runtime 直连 E2E、Runtime wire 隔离 | `FEAT-006.streaming.runtime-direct` |

## 4. 详细用例

### FEAT-006.streaming.lifecycle - 标准流式调用生命周期

- **状态/优先级**：runnable, P0。
- **自动化状态**：partial；`StandardAgentClientBlackboxTest` 已覆盖公共合同，真实 E2E 待补。
- **Story/来源**：Feature §2、§4、§5.1.1-5.1.4；L2 §2.1、§3.2-§3.5。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的 conversation/invocation、STREAMING、平台入口和状态投影语义；L2 当前交付的公开 facade 与六类事件。
- **G**：三 Agent 就绪，Gateway 默认 Agent 指向 `travel-mainplan`；业务应用创建正式 client；分别准备业务传入的唯一 conversation 和由 client 生成器委托生成的 conversation；基线不暴露工具，附加分支显式暴露一个 Observation 工具；为 agentId、业务输入、correlation、trace、幂等键和凭证上下文准备唯一标记。
- **W**：以 `STREAMING` 分别发起指定/委托生成 conversation 的调用，并增加一次省略 agentId 的调用；同一 invocation 的 `events()` 由正常订阅者和按 demand 逐项请求的慢订阅者同时消费；终态后在指定 conversation 发起“改住朝阳”的新调用；附加分支让真实 Agent 请求已暴露工具并由 SDK 自动回传结果。故障分类和未知状态不混入本例。
- **T**：
  - 指定 conversation 原值回显；委托生成时得到非空且稳定回显的 conversationId；每次调用有非空且不同的 `invocationRef`、幂等键和实际模式 `STREAMING`；
  - 省略 agentId 时请求由 Gateway 默认 Agent 正常受理，client 不发送空字符串 agentId；显式 agentId 时目标标识原样到达受治理入口；
  - agentId、输入、correlation、trace 和幂等关联到达受治理入口及真实 Agent；凭证在 Gateway 被正确鉴权，授权请求才到达 Agent，凭证原文不出现在事件、结果或日志中；
  - 首次事件至少包含 Accepted/StatusChanged、内容或 artifact 投影以及 Completed，顺序不得在终态后倒退；
  - 两个订阅者看到同一有序业务事件事实，慢订阅者的 demand 不触发第二次 Agent 调用，也不使事件顺序倒退；
  - 两次最终结果均来自真实 travel Agent；第二次形成新的 invocation，公开审计显示两次请求稳定使用同一 conversationId，本用例不把 runtime 是否利用历史记忆作为 FEAT-006 断言；
  - 基线请求不携带 clientTools；显式工具分支的当前 ToolView 到达服务端，工具结果由 SDK 内部续跑原 Task且业务应用不提交 taskId；
  - 业务全程不提交 taskId，不需要解析 A2A JSON-RPC/SSE；返回对象不泄漏 runtime endpoint、routeHandle、topic。
- **不应断言**：固定自然语言、固定 token 数、固定状态轮数、runtime 是否利用历史记忆、client 内部 taskRef 映射结构。
- **失败归类**：合同字段、状态或结果不符为 Failure；正式 client/Gateway/Agent 制品或密钥缺失为 Skipped；测试代码和环境意外异常为 Error。
- **方法**：`feat006StreamingInvocationProjectsLifecycleAndKeepsConversation()`。
- **标签**：类级 `@Feature("FEAT-006: 客户端发起标准化智能体调用")`、`@Tag("feat-006")`、`@Tag("integration")`；方法级 `@Tag("blackbox")`、`@Story("FEAT-006.streaming.lifecycle: 标准流式调用生命周期")`、`@Tag("story-feat-006-streaming-lifecycle")`。
- **DisplayName**：`Feat-006 标准流式调用回显 invocation 并保持 conversation`。

### FEAT-006.streaming.continue-input - 等待输入续接

- **状态/优先级**：runnable, P0。
- **自动化状态**：partial；`StandardAgentClientBlackboxTest` 已覆盖公共合同，真实 E2E 待补。
- **Story/来源**：Feature §2、§4、§5.1.3；L2 §2.1、§3.4。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的新 invocation 续接、同 conversation、消歧和关联错误；L2 当前 `continueInput` facade 与 wire 约束。
- **G**：同一 client 和唯一 conversation；Agent 使用测试侧确定性 LLM endpoint，按两个业务标记分别生成 INPUT_REQUIRED，使两个 invocation 均稳定进入等待输入。
- **W**：只选择第一个投影的 `invocationRef`/等待输入引用调用 `continueInput`，并用相同新 invocationId/幂等键/正文重复一次；随后续接第二个等待状态；再参数化提交不存在、其他 conversation 和已终态的关联引用。
- **T**：
  - 两个首轮投影均为 INPUT_REQUIRED 且非终态；每次补充输入形成新的 `invocationRef`，保持同一 `conversationId` 并最终 COMPLETED；
  - 第一次续接只推进被明确指定的等待状态，第二个等待状态不被误选；两份结果分别保留各自首轮意图与补充条件；
  - 重复的同一 continuation 返回同一业务可见结果且原 Task 只推进一次，不产生重复副作用；
  - 错误关联返回稳定可编程错误，不产生新的可观察 Agent 执行或成功 invocation；
  - 测试只用 client 公开关联引用，不能把响应中的诊断 taskRef 作为入参。
- **不应断言**：固定追问文案、内部恢复点 key、TaskStore 状态布局或续接实现算法。
- **失败归类**：续接错目标、重复推进或非法关联假成功为 Failure；确定性 LLM/正式制品缺失为 Skipped；夹具异常为 Error。
- **方法**：`feat006ContinueInputCreatesRelatedInvocationAndRejectsInvalidRelations()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-006.streaming.continue-input: 等待输入续接")`、`@Tag("story-feat-006-streaming-continue-input")`。
- **DisplayName**：`Feat-006 补充输入以新 invocation 续接指定等待状态`。

### FEAT-006.streaming.failure-boundary - 调用失败边界

- **状态/优先级**：runnable, P0；**自动化状态**：partial，公共合同已实现、真实故障边界 E2E 待补。
- **Story/来源**：Feature §5.1.4/§5.1.6；L2 §5.2/§6。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的错误分类与 SSE 中断恢复语义；L2 当前公开错误/事件投影。
- **G**：真实 Agent 正常流式调用可完成；在 client-Gateway 和 Gateway-runtime 外部网络边界准备可控超时/断流，并准备能经公开输入稳定触发路由、A2A/Task 与业务失败的场景。
- **W**：参数化执行各故障场景并消费 client 的公开返回和事件流。
- **T**：每类故障返回不混淆的可编程错误或 Failed 投影；SSE 中断不得伪造 Completed，也不得泄漏拓扑。
- **不应断言**：源码异常字符串、内部重试次数或固定超时实现。
- **失败归类**：合同不符为 Failure；正式依赖缺失为 Skipped；故障代理异常为 Error。
- **方法**：参数化 `feat006FailuresRemainClassifiedWithoutFalseCompletion()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-006.streaming.failure-boundary: 调用失败边界")`、`@Tag("story-feat-006-streaming-failure-boundary")`。
- **DisplayName**：`Feat-006 网络、路由与业务失败保持分类且不伪造完成`。

### FEAT-006.streaming.unknown-state-contract - 未知状态兼容

- **状态/优先级**：runnable, P1；**自动化状态**：implemented。
- **Story/来源**：Feature §5.1.5；L2 §2.1/§3.4。
- **测试类型**：contract。
- **Oracle 来源**：Feature 与 L2 的未知 TaskState 只读 UNKNOWN 兜底合同。
- **G**：真实 Agent 流包含一个非终态状态和后续真实终态；协议代理只把该非终态枚举改为未来值，其他帧保持原样。
- **W**：SDK 通过公开流接口消费完整序列。
- **T**：未来枚举映射为只读 UNKNOWN 且 SDK 不崩溃，随后仍按真实终态完成。
- **不应断言**：真实 runtime 会产生该未来枚举、SDK 内部反序列化类型或分支实现。
- **失败归类**：映射错误或崩溃为 Failure；代理异常为 Error；正式 SDK 缺失为 Skipped。
- **方法**：`feat006UnknownTaskStateMapsToReadonlyUnknown()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-006.streaming.unknown-state-contract: 未知状态兼容")`、`@Tag("story-feat-006-streaming-unknown-state-contract")`。
- **DisplayName**：`Feat-006 未识别状态映射 UNKNOWN 且不阻断后续终态`。

## 5. 本期断点重连增量详细设计

### 5.1 当前实施切片与范围边界

Client 合同本身与 Agent 类型无关，完整协议/重试矩阵只实施一次；Agent 差异通过 Runtime 适配器探针和
风险导向 E2E 验证。两种 Endpoint 都必须有真实证据，但不机械展开三种 Agent 乘两条路径：
合同层的“正式 Client”指测试 JVM 内运行的正式 SDK 实例，不是另外启动的进程。

```text
对照：Client -> Runtime
主链路：Client -> Gateway -> Runtime
```

两条路径执行同一旅程：创建流式 Task、取得 taskId、断流、查询/重订阅原 Task、等待最终收敛。
FEAT-006 只判定 Client 公开行为；Gateway owner 路由、拓扑清洗等额外断言归 FEAT-011。

| SUT 架构 | 当前处置 | 架构特有 Oracle |
|---|---|---|
| ReActAgent travel | Runtime 直连与 Gateway 两条 E2E，P0 | trip/hotel 请求 canary 不重复；原 Task 最终收敛 |
| DeepAgent | Gateway 风险 E2E，P1；Runtime 适配器直连探针 | 长流回原 Task；search/verify 或等价公开 canary 不重复 |
| WorkflowAgent | Runtime 直连风险 E2E，P1 | 断流后进入 `INPUT_REQUIRED` 或终态；Workflow 快照完整；节点/审批不重复 |

本期明确不测：`CancelTask`、未取得 taskId 时的创建安全重发/幂等、Client 进程重启后的 invocation 映射、
历史事件逐帧重放、cursor/offset 和完整调用模式矩阵。

### 5.2 F006-R01/R03/R04 - 已知 Task 恢复

- **Given**：正式 Client 以 STREAMING 创建被测 Agent 长任务，业务侧已取得 `invocationRef`，Client 内部已关联服务端 taskId，Task 非终态。
- **When**：SSE 在终态前断开，Client 自动调用 `SubscribeToTask`；订阅遇到结构化终态错误、能力不可用、基础设施失败、可识别空流或协议不完整时，在同一个恢复周期内进入一次 `GetTask` 对账。
- **Then**：创建请求只发送一次；恢复始终观察原 taskId；终态/根 INPUT_REQUIRED 由 GetTask 结算，只有 WORKING/SUBMITTED 且总恢复预算未耗尽时才允许启动下一恢复周期；业务侧继续只用原 invocationRef；最终 future 只结算一次。终态竞态固定为一次 Subscribe 加一次 GetTask，连续失败增量 0、总预算消耗 1。
- **FAIL**：第二次 `SendStreamingMessage`、taskId 改变、重复执行、业务侧必须传 taskId，或终态竞态无可编程恢复结果。

### 5.3 F006-R02/B01-B06 - idle、重试与观察熔断

- **Given**：合同层可脚本化断流、idle、retryable 基础设施失败、确定性协议错误和有效 WORKING 快照；重试间隔与阈值可设为测试值。
- **When**：Client 进入恢复观察，分别执行“失败达到阈值”和“失败后成功再失败”的序列。
- **Then（`D006-206-DEC-01` 已确认口径）**：idle 只触发恢复，不直接把 Task 标为 FAILED/CANCELED；一个 Subscribe 及其 GetTask 对账构成一个恢复周期，两者均失败只增加一次连续失败；默认连续 3 个失败周期后停止，即 6 个恢复请求，包含首次创建共 7 个请求；不同 invocation 互不串线；wire 中没有 `CancelTask`。
- **成功与总预算**：有效 Task 快照或有效 Subscribe 帧清零连续失败；WORKING/SUBMITTED 不返还已消耗的总预算。默认 6 个恢复周期，可由 `RetryPolicy.maxKnownTaskRecoveryAttempts` 配置；预算耗尽时 `completion()` 必须有限结束并投影 `ProgressUncertain` / `RECOVERY_RETRY_EXHAUSTED`。
- **判定门禁**：周期级失败和独立总预算已由 `D006-206-DEC-01` 确认；锁定 Client 制品的指定 XML 已精确回归通过，本合同层用例判 PASS。
- **FAIL**：确定性 JSON-RPC 错误被重试、成功不清零、超过阈值仍重试、一个 invocation 熔断其他调用，或熔断取消服务端 Task。
- **INCONCLUSIVE**：真实 Agent 持续产帧而未命中 idle 窗口；合同层结果仍单独判定。
- **R02 自动化落点**：通过正式 `RuntimeTransportProvider` 的公开构造器配置短 idle timeout；脚本让初始流
  返回 `WORKING` 后断开，并让恢复订阅保持无 body 字节。断言 SDK watchdog 使用原 taskId 回退
  `GetTask`，wire 为 `SendStreamingMessage -> SubscribeToTask -> GetTask`，且无第二次创建、无
  `CancelTask`、无 FAILED/CANCELED 事件。
- **B01/B02/B04 自动化落点**：通过公开 `RetryPolicy` 和
  `AgentClients.Builder.retryPolicy(...)` 注入默认与非默认停止边界。每个阈值单位由完整失败周期构造；达到阈值后不启动下一周期，显式 `getInvocation` 仍使用原 taskId，且 wire 无第二次创建和 `CancelTask`。
- **默认退避 Oracle**：第 1、2 个失败周期后分别退避 200 ms、400 ms，第 3 个失败周期结束后立即熔断；800 ms 只验证退避函数上限或非默认策略，不作为默认第三段等待。
- **B03 自动化落点**：拆分验证两种成功清零来源：
  1. `Subscribe 503 -> GetTask WORKING -> Subscribe 503 -> GetTask WORKING`，证明每个合法 GetTask 快照清零连续失败；
  2. 一个完整失败周期后重新 Subscribe 并返回有效 WORKING 帧；测试 SSE 闭合触发的强制 GetTask 失败只计为清零后的第 1 次失败，随后再经历两个完整失败周期才达到阈值，证明有效 Subscribe 帧先清零连续失败；
  3. 前两种成功均不返还已消耗的 `knownTaskRecoveryAttempts`，达到配置总预算后仍停止观察。
  不再断言 Subscribe 基础设施失败后连续发送 Subscribe；全部恢复请求保持原 taskId 且无 `CancelTask`。
- **B05 自动化落点**：分别使用默认 6 和较小的自定义 `maxKnownTaskRecoveryAttempts`，让每个周期都返回合法 WORKING，断言连续失败始终被清零，但总预算耗尽后 completion 有限结束；配置存在和外部停止行为必须同时取证。

### 5.3.1 Subscribe 错误分类矩阵

| 输入 | 分类与动作 | 主断言 |
|---|---|---|
| `error.data.code=TASK_NOT_SUBSCRIBABLE_TERMINAL` / `SUBSCRIPTION_UNAVAILABLE` | 确定性终态/不可订阅；只调用一次 Subscribe，随后一次 GetTask | GetTask 终态或根 INPUT_REQUIRED 结算，不计为基础设施失败 |
| `-32602` 且 message 明确为 `invalid task state for subscription` | 终态 message 兼容分支 | 行为同上，但只作为兼容证据；结构化 data.code 是主 Oracle |
| `error.data.code=INVALID_PARAMS` 且无终态语义 | 真正参数错误；不重复 Subscribe、不进入基础设施重试，必要时最多一次 GetTask | `completion()` 有限结束，不误判为终态不可订阅 |
| 网络错误、连接/读超时、HTTP 408/429/5xx | 基础设施失败 | 按新 L2，一个 Subscribe+GetTask 失败周期只增加一次连续失败；该 Oracle 待 Feature 同步 |

### 5.4 F006-E01 - ReAct 双路径一致性

- **Given**：相同版本的正式 Client、Runtime、Gateway 与 ReAct travel fixture 可用；Gateway 单实例且测试期间不重启。
- **When**：仅切换公开 Endpoint 配置，在两条路径执行相同断流恢复旅程。
- **Then**：invocation 状态、结果类型、恢复线索、原 Task 保持和无重复副作用一致；仅认证/路由 wire 允许不同。
- **PASS**：公共合同和两条真实链路均有 Surefire/Allure、taskId、请求序列和业务 canary 证据。
- **INCONCLUSIVE**：LLM 或输入速度未形成活动窗口；不得写成 PASS。
- **blocked/not-run**：目标 JAR、公开方法或环境不可用；不得用 mock 成功替代。

## 6. 文件、执行与退出标准

保留既有 `StandardAgentClientBlackboxTest` 四个公共 facade 黑盒测试；自动化落点如下：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/agent_bus/
  ClientReconnectBlackboxTest.java
  RuntimeReconnectBlackboxTest.java
src/test/java/com/huawei/ascend/sit/cases/e2e/reconnect/
  ClientRuntimeReconnectIT.java
  ClientGatewayRuntimeReconnectIT.java
```

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；Maven 本地仓库默认
`~/.m2/repository`。正式 Client/Gateway/Runtime 使用本地目标新构建；执行前必须准备 travel fixture JAR、
LLM 配置和真实场景环境，不得把 acceptance helper 放入正式 Client 的位置。

WSL LLM凭据的安全创建、`~/.llmrc`加载、变量存在性检查和`LLM_SSL_VERIFY`布尔值要求，统一遵循
`04-Environment/local-sit/README.md`的“WSL LLM环境变量”章节；本测试文档不保存实际凭据。
## 7. FEAT-026 交叉验证扩展设计

以下用例复用 FEAT-026 的 `MockRemoteAgentServer`、`CallTreeFixtureEvents` 和 Runtime wire fixture，补充 Client 并发隔离、子节点状态边界、SSE 协议边界及 Runtime 直连的交叉验证；这些合同证据不替代 §5 的真实 reconnect 链路。

### FEAT-006.streaming.recovery - STREAMING 断线恢复与 SSE 协议边界

- **状态/优先级**：runnable, P0。
- **自动化状态**：implemented-and-contract-verified；计数单位和总预算已由 `D006-206-DEC-01` 冻结，锁定制品精确回归 14/14 PASS。
- **Story/来源**：Feature §5.1.4/§5.1.6；L2 §4.4、§9.2、§9.5、§9.8。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 §4.4 恢复状态机（OBSERVING→RECOVERING_SUBSCRIBE→MERGING_CURRENT_STATE）、§9.2 恢复与模式验收、§9.5 HTTP/SSE Transport 验收、§9.8 退避与熔断属性。
- **G**：真实 Agent 或 MockRemoteAgentServer 可在非终态断流后恢复；在 client 与服务端间用 `FaultLink.resetPeer()/restore()` 制造断点；准备 fake clock/fake scheduler 验证默认 200/400ms 后第 3 次立即熔断及 800ms 退避上限；mock 可产出结构化 JSON-RPC error、SSE 多 data 行、注释行、空行、半开 idle timeout 和 JSON Content-Type 响应。
- **W**：参数化执行恢复场景：(1) 非终态断流后 Subscribe 原 Task 恢复观察；(2) SSE 协议边界；(3) 408/429/500/502/503/504、连接拒绝、读超时及结构化终态错误/真正 INVALID_PARAMS 分类矩阵；(4) 周期级连续失败、成功清零、默认/非默认阈值熔断和总恢复预算。
- **T**：
  - 断线后 SDK 对已知 rootTaskId 只 Subscribe 原 Task，不重发创建请求；恢复后继续发布有序事件且 completeness 降为 PARTIAL；
  - 连续失败计数按 invocation 隔离，一个 invocation 熔断不影响其他；
  - SSE 多 data 行正确拼接为单帧；注释和空行被忽略；JSON Content-Type 2xx 响应以 `STREAMING_UNAVAILABLE` 失败，不静默当空 SSE 结束；
  - 结构化终态错误和真正 INVALID_PARAMS 按 §5.3.1 分类；终态竞态固定为 Subscribe+GetTask，连续失败增量 0、总预算消耗 1；
  - 按 `D006-206-DEC-01`：408/429/500/502/503/504 和连接拒绝/读超时按完整失败周期计数；默认第 1、2 个失败周期后退避 200/400ms，第 3 个周期结束后停止；合法状态清零连续失败但不返还总预算；熔断后投影 `UNKNOWN/ProgressUncertain`，不发送 CancelTask。
- **不应断言**：固定超时实现内部、源码异常字符串、内部重试计数字段名、Gateway Subscribe cursor 实现。
- **失败归类**：恢复未 Subscribe 原 Task 或重发创建为 Failure；SSE 协议误判为 Failure；retryable 分类错误为 Failure；正式制品/FaultLink 缺失为 Skipped；故障代理异常为 Error。
- **方法**：参数化 `feat006StreamingRecoverySubscribesOriginalTaskAndClassifiesErrors()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-006.streaming.recovery: STREAMING 断线恢复与 SSE 协议边界")`、`@Tag("story-feat-006-streaming-recovery")`。
- **DisplayName**：`Feat-006 断线恢复 Subscribe 原 Task 且 SSE/HTTP 错误保持分类`。

### FEAT-006.streaming.concurrency-isolation - 并发调用隔离与竞态资源释放

- **状态/优先级**：runnable, P0。
- **自动化状态**：implemented（FEAT-026 交叉验证）。
- **Story/来源**：Feature §5.1.4；L2 §4.2、§9.4、§9.6、§9.8。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 §4.2 调用级状态隔离、§9.4 同一 Client 并发不串、§9.6 ≥20 并发 invocation、§9.8 随机化竞态与 future 单次结算。
- **G**：同一 client 配置；准备 ≥20 个并发 invocation（不同 conversationId、不同 mock endpoint 或同一 mock 多 taskId fixture）；准备 close/recovery/terminal 竞态场景；准备随机化并发、断线、重复帧和查询竞态生成器。
- **W**：(1) 并发发起 ≥20 个 STREAMING invocation，各自消费事件流和调用树快照，收集每个 invocation 的 rootTaskId、revision、completeness 和最终状态；(2) 在部分 invocation 终态进行中对其调用 `close()`，验证 close/terminal 竞态后资源释放；(3) 随机化并发断线、重复帧和查询操作，检查 future 单次结算。
- **T**：
  - ≥20 个并发 invocation 的 rootTaskId 互不串；事件流和调用树快照不跨 invocation 泄漏；
  - 失败计数、pending 集合、credential、speaker/diagnostics/buffer 按 invocation 隔离；
  - `close()` 只结束本地观察，不取消服务端 Task，不产生 `InvocationEvent.Failed`，不伪造 Task FAILED；
  - close/recovery/terminal 竞态后释放线程任务、ScheduledFuture、HTTP body、Channel 和映射；
  - 重复终态、查询与 close 竞态下 `accepted()`/`completion()` 各只结算一次（幂等）；
  - 随机化竞态下无 future 悬挂或资源泄漏。
- **不应断言**：固定并发数上限、内部线程池结构、invocation map 实现类型。
- **失败归类**：跨 invocation 串 taskId/事件/资源为 Failure；竞态导致 future 重复结算或资源泄漏为 Failure；正式制品缺失为 Skipped；并发夹具异常为 Error。
- **方法**：`feat006ConcurrentInvocationsIsolateStateAndReleaseResourcesOnRaces()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-006.streaming.concurrency-isolation: 并发调用隔离与竞态资源释放")`、`@Tag("story-feat-006-streaming-concurrency-isolation")`。
- **DisplayName**：`Feat-006 并发 invocation 状态隔离且竞态资源释放幂等`。

### FEAT-006.streaming.child-input-boundary - 子节点 INPUT_REQUIRED 不结算根

- **状态/优先级**：runnable, P1。
- **自动化状态**：implemented（FEAT-026 交叉验证）。
- **Story/来源**：Feature §5.1.3；L2 §4.6、§9.3。
- **测试类型**：contract。
- **Oracle 来源**：L2 §4.6 根级 INPUT_REQUIRED 与批量续传、§9.3 多输入验收（B1 子状态到达时 B2 仍可输出、根调用不结算）。
- **G**：MockRemoteAgentServer 产出子 Agent 的 `agentEvent.status=input_required`（子节点中断）、另一子 Agent 继续 output、随后根 Task INPUT_REQUIRED 的 fixture 序列。
- **W**：以 STREAMING 发起调用并订阅 `callTree()` 和 `events()`；消费完整 fixture 序列至根 INPUT_REQUIRED；观察子节点中断时根状态和 `completion()` 结算时机。
- **T**：
  - 子节点 `status(input_required)` 只更新对应树节点，不结算根调用，不暴露可续传 pending；
  - 子节点中断后另一子 Agent 仍可继续 output，根调用不进入终态；
  - 只有根 Task INPUT_REQUIRED 到达时 `completion()` 结算为等待点并公开 pending 列表；
  - 缺少根等待点时不伪造 pending toolCallId。
- **不应断言**：固定追问文案、树节点内部状态字段名、子节点 input_required 的 wire metadata 路径。
- **失败归类**：子节点中断误结算根为 Failure；根 INPUT_REQUIRED 未公开 pending 为 Failure；正式 SDK 缺失为 Skipped；mock 异常为 Error。
- **方法**：`feat006ChildInputRequiredDoesNotSettleRootInvocation()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-006.streaming.child-input-boundary: 子节点 INPUT_REQUIRED 不结算根")`、`@Tag("story-feat-006-streaming-child-input-boundary")`。
- **DisplayName**：`Feat-006 子节点 input_required 只更新子节点不结算根调用`。

### FEAT-006.streaming.sse-protocol-contract - SSE 协议边界与延迟启动

- **状态/优先级**：partial, P1。
- **自动化状态**：partial（FEAT-026 交叉验证；自定义 SSE 帧仍待 fixture 扩展）。
- **Story/来源**：Feature §5.1.6；L2 §4.2.2、§4.3.1、§7、§9.5。
- **测试类型**：contract。
- **Oracle 来源**：L2 §4.3.1 STREAMING 2xx JSON Content-Type→`STREAMING_UNAVAILABLE`、§4.2.2 延迟启动与唯一创建、§9.5 SSE 覆盖与先 onSubscribe 后发 HTTP。
- **G**：MockRemoteAgentServer 可产出 SSE 多 data 行、注释行（`: comment`）、空行、正常关流、半开 idle timeout 和 JSON Content-Type 响应；mock 可记录首次 HTTP 请求相对于 onSubscribe 的时序。
- **W**：参数化执行 SSE 协议场景：(1) 多 data 行帧（单帧拆分为多个 `data:` 行）；(2) 注释行和空行穿插在正常帧间；(3) 2xx 响应明确返回 `Content-Type: application/json`；(4) 多次订阅同一 `events()` Publisher。
- **T**：
  - 多 data 行正确拼接为单帧，不丢失或重复事件；
  - 注释行和空行被忽略，不影响帧边界和事件顺序；
  - JSON Content-Type 2xx 响应以 `STREAMING_UNAVAILABLE` 失败，SDK 不把 JSON 当作空 SSE 静默结束；
  - 首个上游订阅者完成 `onSubscribe` 后才发送 `SendStreamingMessage`；多次订阅不重复发送创建请求；
  - 同步立即响应不丢 Accepted、首帧、终态或 INPUT_REQUIRED。
- **不应断言**：SSE 解析器内部状态机、HTTP client 实现类型、Content-Type 大小写敏感性。
- **失败归类**：SSE 帧拼接错误为 Failure；JSON Content-Type 未拒绝为 Failure；延迟启动时序违反为 Failure；mock 异常为 Error；正式 SDK 缺失为 Skipped。
- **方法**：参数化 `feat006SseProtocolBoundariesAndLazyStartHoldContract()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-006.streaming.sse-protocol-contract: SSE 协议边界与延迟启动")`、`@Tag("story-feat-006-streaming-sse-protocol-contract")`。
- **DisplayName**：`Feat-006 SSE 协议边界保持且延迟启动先订阅后发 HTTP`。

### FEAT-006.streaming.runtime-direct - Runtime 直连 E2E

- **状态/优先级**：partial, P1。
- **自动化状态**：partial（FEAT-026 交叉验证；header 级 wire 捕获仍待 fixture 扩展）。
- **Story/来源**：Feature §2；L2 §4.1、§9.7。
- **测试类型**：blackbox。
- **Oracle 来源**：L2 §4.1.1 Runtime 请求差异（无 Authorization/agentId/租户 headers/attributes）、§9.7 Client→Runtime 与 Client→Gateway→Runtime 分别 E2E。
- **G**：正式 agent-client 配置 `EndpointType.RUNTIME` 直连 mock 或真实 Runtime endpoint；准备 Runtime wire allowlist golden fixture（只含 messageId/contextId/taskId/parts/returnImmediately/clientTools）。
- **W**：以 `EndpointType.RUNTIME` 发起 STREAMING 调用；捕获 Runtime 收到的完整 wire 请求；参数化验证配置了 `credentialProvider` 时 Runtime 仍不发送 Authorization。
- **T**：
  - Runtime 收到的请求不含 `Authorization`、`agentId`、租户/用户/空间/路由 headers 和任意 `attributes`；
  - Runtime 请求只含标准 A2A 字段和 `metadata.clientTools`（非空时）；
  - 调用正常完成且 SDK 不泄漏 Gateway 身份或路由字段；
  - 配置了 `credentialProvider` 时 Runtime 仍不发送 token，日志不记录 token 内容。
- **不应断言**：Runtime 内部 TaskStore 结构、A2A 信封内部字段顺序、HTTP header 大小写。
- **失败归类**：Runtime 请求含禁止字段为 Failure；wire allowlist 不符为 Failure；正式 Runtime 制品缺失为 Skipped；mock/代理异常为 Error。
- **方法**：`feat006RuntimeDirectE2EIsolatesWireFromGatewayPolicy()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-006.streaming.runtime-direct: Runtime 直连 E2E")`、`@Tag("story-feat-006-streaming-runtime-direct")`。
- **DisplayName**：`Feat-006 Runtime 直连正向投影不含 Gateway 身份字段`。

### 7.6 文件与执行落点

计划新增一个测试文件，并在已有 FEAT-026 测试文件中补充交叉验证用例：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/
  Feat006StandardAgentClientBlackboxTest.java          # 新增：lifecycle、continue-input、failure-boundary、unknown-state

src/test/java/com/huawei/ascend/sit/cases/integration/deepagent_deepresearch/
  MultiHopCallTreeBlackboxTest.java             # 已有：补充 recovery、concurrency-isolation、child-input-boundary、sse-protocol-contract
  RuntimeProducerCallTreeFixture.java           # 已有：补充 runtime-direct wire fixture 捕获
```

FEAT-026 测试文件已具备 `MockRemoteAgentServer` + `CallTreeFixtureEvents` + 正式 `agent-client` SDK 基础设施，STREAMING 恢复、并发隔离、子节点边界和 SSE 协议契约用例直接复用该基础设施，避免重复搭建 mock 和 fixture builder。`RuntimeProducerCallTreeFixture` 扩展为 Runtime 直连 wire allowlist fixture 捕获，与 FEAT-026 §9.7 Provider Contract 联调要求一致。

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；Maven 本地仓库默认 `~/.m2/repository`。travel JAR 坐标见 §1.1。正式 agent-client 已作为 test-scope Maven 依赖（`com.openjiuwen:agent-client-sdk-for-jvm:0.1.0`）；交叉验证中的 mock 只控制协议输入和记录 wire，不替代正式 Client SUT 或真实 reconnect E2E。

除 `LLM_API_BASE/LLM_MODEL/LLM_API_KEY` 等标准密钥外，确定性 prompt、payload、代理规则和唯一 canary 由测试资源自动准备。测试结束必须关闭 client、Agent/Gateway 进程和代理，恢复 `FaultLink`，删除临时目录，并确认占用端口释放。落地后执行：

```bash
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-006 test
# Story 示例
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-006-streaming-lifecycle test
```

退出标准：Client 公共合同、Runtime 直连对照、Gateway 主链路、三种 Agent 风险场景和最小受影响回归通过或有明确
INCONCLUSIVE/blocked 证据；无 helper/fake 核心链路通过、无固定 LLM 文本 Oracle、无敏感信息和进程/端口泄漏。

## 8. FEAT-026 交叉验证实现规范

§7 中 `concurrency-isolation`、`sse-protocol-contract` 和 `runtime-direct` 三个用例在 FEAT-026 测试文件中实现。因 FEAT-026 测试文件使用 `feat026` 方法名前缀和 `MockRemoteAgentServer` + `CallTreeFixtureEvents` mock 基础设施，实际方法名与 §7 中指定的 `feat006*` 名不同，但 Story/Tag 标注保持 FEAT-006 标识。以下为实现级规范。

### 8.1 交叉验证实现矩阵

| §7 设计用例 | 实现文件 | 实际方法名 | Story 标识 | 状态 |
|---|---|---|---|---|
| `FEAT-006.streaming.recovery` | `MultiHopCallTreeBlackboxTest.java` | `feat026RecoveryMarksPartialAndMergesCurrentArtifactsIdempotently` | `story-feat-026-streaming-recovery-partial` | 已实现 |
| `FEAT-006.streaming.concurrency-isolation` | `MultiHopCallTreeBlackboxTest.java` | `feat026ConcurrentInvocationsIsolateStateAndReleaseResourcesOnRaces` | `story-feat-006-streaming-concurrency-isolation` | 已实现 |
| `FEAT-006.streaming.child-input-boundary` | `MultiHopCallTreeBlackboxTest.java` | `feat026ChildInputRequiredUpdatesChildWithoutSettlingRoot` | `story-feat-026-streaming-child-input-boundary` | 已实现 |
| `FEAT-006.streaming.sse-protocol-contract` | `MultiHopCallTreeBlackboxTest.java` | `feat026SseProtocolBoundariesAndLazyStartHoldContract` (参数化) | `story-feat-006-streaming-sse-protocol-contract` | 已实现 |
| `FEAT-006.streaming.runtime-direct` | `RuntimeProducerCallTreeFixture.java` | `feat026RuntimeDirectWireFixtureIsolatesFromGatewayPolicy` | `story-feat-006-streaming-runtime-direct` | 已实现 |

### 8.2 concurrency-isolation 实现细节

- **方法**：`feat026ConcurrentInvocationsIsolateStateAndReleaseResourcesOnRaces()`。
- **G**：单一 `MockRemoteAgentServer`（FIXTURE_STREAM 模式），3 帧标准 fixture（status-update → text-artifact → status-update completed）。
- **W**：
  - Part 1：用 `ExecutorService.newFixedThreadPool(20)` 并发发起 20 个 STREAMING invocation（各自唯一 conversationId），每个 invocation 独立订阅 `callTree()` 和 `events()`；等待全部完成。
  - Part 2：在独立 mock 上发起单个 invocation，调用 `completion()` 两次，验证幂等结算。
- **T**：
  - 20 个 invocation 均到达终态（COMPLETED 或 INPUT_REQUIRED）；
  - 每个 invocation 的 `events()` 列表非空（无 invocation 被饿死）；
  - 所有 invocation 的事件数一致（无跨 invocation 串流泄漏）；
  - mock `a2aPostCount()` ≥ 20（每个 invocation 发起独立 HTTP 创建请求）；
  - 重复 `completion()` 返回相同终态（幂等结算）。
- **门禁说明**：`close()` 竞态测试需 `InvocationCall.close()` API；若 SDK 未提供该方法，该子场景以 TODO 标注，不阻塞主验证。mock 服务同一 fixture 流给所有请求（taskId 相同），rootTaskId 唯一性在真实 Agent 场景验证，mock 场景验证事件流隔离。

### 8.3 sse-protocol-contract 实现细节

- **方法**：参数化 `feat026SseProtocolBoundariesAndLazyStartHoldContract(SseScenario)`。
- **参数化场景**：

| 场景 | Mock 模式 | 验证内容 |
|---|---|---|
| `MULTI_SUBSCRIBE` | FIXTURE_STREAM | 两个订阅者（早订阅 + 晚订阅）各自收到事件；mock 只收到 1 个 POST（多次订阅不重复创建） |
| `JSON_CONTENT_TYPE` | REJECT（默认） | 2xx `application/json` 响应不应被当作空 SSE 静默完成；SDK 应抛错或终态非 COMPLETED；不产生 callTree 快照 |
| `NORMAL_SSE` | FIXTURE_STREAM | 正常 SSE 帧按预期顺序到达；终态 COMPLETED；mock 收到 1 个 POST |

- **门禁说明**：SSE 多 data 行拼接、注释行/空行忽略、延迟启动时序（onSubscribe 先于 HTTP 发送）需 `MockRemoteAgentServer` 扩展自定义 SSE 帧能力（当前 mock 固定 `data: <payload>\n\n` 格式）。这些子场景在 §7 G 中列为 mock 需求，实现待 mock 扩展后补充。

### 8.4 runtime-direct 实现细节

- **方法**：`feat026RuntimeDirectWireFixtureIsolatesFromGatewayPolicy()`。
- **实现文件**：`RuntimeProducerCallTreeFixture.java`（非 `MultiHopCallTreeBlackboxTest.java`）。
- **G**：`MockRemoteAgentServer`（FIXTURE_STREAM 模式）模拟 Runtime endpoint；`AgentClients.builder().endpointType(EndpointType.RUNTIME).endpointUrl(mock.baseUrl()).build()` 直连。
- **W**：以 STREAMING 发起调用；完成后从 `mock.a2aPostBodies().get(0)` 提取完整 wire 请求 body。
- **T**：
  - 调用正常完成（Runtime 直连链路可用）；
  - wire body 含标准 A2A 字段：`"jsonrpc"`、`"method"`、`"params"`、`"message"`；
  - wire body 不含 Gateway 策略字段：`"agentId"`、`"Authorization"`、`"tenant"`、`"routeHandle"`；
  - wire body 含调用方提供的 conversationId。
- **门禁说明**：当前 `MockRemoteAgentServer` 只捕获 POST body，不捕获 HTTP headers。Header 级别隔离（Authorization header、agentId header、租户 header）需扩展 mock 或使用真实 Runtime endpoint。本用例验证 body 级别的 wire allowlist，header 级别验证标注为后续补充。
