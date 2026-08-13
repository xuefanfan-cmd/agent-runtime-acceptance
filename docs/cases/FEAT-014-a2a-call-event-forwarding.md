---
feature_id: FEAT-014
feature_title: 总线支持 A2A 调用事件转发
sut: FEAT-014-enabled multi-react-travel-demo + Event Bus
status: designed-dependency-gated
tags: [blackbox, contract, integration, a2a, agent-bus, feat-014]
---

# FEAT-014 - 总线支持 A2A 调用事件转发测试设计

> 让 `travel-mainplan -> travel-trip -> travel-hotel` 两跳真实远端调用改走 A2A 事件通道，验证调用方/被调用方 runtime 之间的请求、接受、响应、流准备、幂等、租户与 Task owner 边界。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `D:\code-agent\feature-docs\develop\02-features\FEAT-014-a2a-call-event-forwarding.md` |
| L2 | `D:\code-agent\feature-docs\develop\03-architecture\L2-Low-Level-Design\agent-bus\feat-014-a2a-call-event-forwarding.md` |
| Feature/L2 仓 | `main@7e1632dd96d49dad05747d8804631234be3cf457`，读取日期 2026-08-06 |
| acceptance 仓 | `main@eb5e3f20ca39f0a8bc647c1ca17b8a637370ce05`，读取日期 2026-08-06；本文为工作区设计变更 |
| 测试 Agent | `com.openjiuwen.example:travel-demo-mainplan/trip/hotel:0.1.0`，外部 JAR |

L2 为 `as-built` 的 Event Bus 设计，但 caller/target runtime producer/consumer 和 FEAT-014 BUS profile 仍 in-flight，完整链路保持 `dependency-gated`。Feature 将远端 INPUT_REQUIRED 的本地回灌与中断续接归 FEAT-004；本方案不再断言补充输入推进原 Task。未查阅产品源码。

### 1.2 范围

| 纳入 | 不纳入 |
|---|---|
| `A2A_CALL_*` / `A2A_STREAM_*` 事件族、远端 taskId、A2A payload 兼容、双向转发 | 客户端调用事件族（FEAT-013）和 Gateway client facade（FEAT-011/012） |
| 调用方 runtime 是主生产方，被调用方 runtime 拥有远端 Task | agent-bus 中心化编排、parent-child Task 树、回灌内部代码 |
| 重复投递/同键重试、租户隔离、route handle 不可解析、大载荷引用 | Agent Card/能力发现/健康选择（FEAT-015/016） |
| STREAM_READY 后调用方与被调用方点对点 SSE，token 不入 Bus | 具体 broker/outbox/inbox/worker 和 runtime transport 实现类 |

原 demo 当前按配置 URL 直接 A2A，不能证明 FEAT-014。以下用例只有在三个 demo JAR 提供正式 FEAT-014 BUS profile 后执行，均为 **dependency-gated**。

## 2. 拓扑与证据

```text
mainplan runtime -> A2A_CALL_* -> Event Bus -> trip runtime
trip runtime     -> A2A_CALL_* -> Event Bus -> hotel runtime
responses reverse through Event Bus; realtime SSE is point-to-point
```

- 用户入口可直接调用 mainplan 标准 A2A，以隔离 Gateway/FEAT-013；被测对象是两条 runtime-to-runtime 边。
- 三个 Agent 仍以真实外部 JAR 运行，但 LLM endpoint 指向测试侧可控服务，按输入标记稳定产生 mainplan->trip、trip->hotel 委托及指定业务结果；不得绕过 Agent 直接发布成功响应。明确拒绝/接受前失败必须使用被调用方 runtime 文档化的公开 admission 规则，否则仅对应参数分支 dependency-gated。
- RDC 只负责预置已知 route handle；本特性不测试候选发现和选择。
- 公开 broker consumer按 tenant/correlation 观察 FEAT-014 事件族；禁止读取内部 outbox/inbox 表或直接调用 SDK SPI。
- 重复、deadline 过期和乱序通过正式 broker 的公开消息入口重放/延迟真实 Agent 产生的 A2A 事件信封；网络故障通过进程外 `FaultLink`/Toxiproxy 注入，不修改 runtime 或 Event Bus 内部状态。
- 业务证据为 mainplan 最终行程同时包含 trip 规划和 hotel mock 数据；事件证据为两跳独立 remote taskId 与状态事实。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-014.a2a.two-hop-round-trip` | Feature §2/§4/§5.1.1-5.1.7 | blackbox | dependency-gated, P0 | design-only | 两跳创建、接受、完成/失败终态、阻塞退化、远端 taskId、owner 边界 | 公开事件、两跳 Agent 审计、最终业务结果 | 不验证本地回灌实现 |
| `FEAT-014.a2a.delivery-and-isolation` | Feature §2/§5.1.6/§5.1.8 | contract | dependency-gated, P0 | design-only | 重复投递、拒绝/失败/当次 UNKNOWN、tenant、route handle、大载荷 | broker 公共边界、目标零增量、错误 | 不计作两跳 Agent E2E 通过 |
| `FEAT-014.a2a.stream-boundary` | Feature §2/§4/§5.1.5 | blackbox | dependency-gated, P0 | design-only | STREAM_READY、点对点 SSE、token 不入 Bus | 公开事件和远端 A2A SSE | 不验证 transport 内部实现 |
| `FEAT-014.deferred.task-operations` | Feature §2/§4/§6；L2 §8.1 | blackbox | deferred | design-only | UNKNOWN 同键恢复、GetTask、CancelTask、SubscribeToTask、流重连 | 待 runtime 控制事件与 FEAT-001 缺口交付 | 当次 UNKNOWN 仍由 delivery 用例覆盖；不生成空测试 |

### L2 本特性能力追踪（正式 BUS profile 交付后执行）

| L2 当前交付能力 | 覆盖用例 |
|---|---|
| caller runtime 发布、target runtime 接受/拒绝/失败/响应/终态、两跳回灌 | `FEAT-014.a2a.two-hop-round-trip` |
| 外层信封、A2A payload、inline/payloadRef、routeHandle、远端 taskId | 前两条用例合并覆盖 |
| 同步完成、已接受远端 Task、拒绝/失败、当次 UNKNOWN | 前两条用例合并覆盖 |
| 流式请求、ACCEPTED/STREAM_READY 分离、点对点 A2A SSE | `FEAT-014.a2a.stream-boundary` |
| bus 投递幂等、租户隔离、物理机制透明 | `FEAT-014.a2a.delivery-and-isolation` |
| 调用方/被调用方 Task owner 不变、Event Bus 无编排状态 | `FEAT-014.a2a.two-hop-round-trip` |
| UNKNOWN 同键恢复、GetTask、CancelTask、SubscribeToTask、流重连 | `FEAT-014.deferred.task-operations` |
| INPUT_REQUIRED 本地回灌/续接 | OUT：归 FEAT-004/017；FEAT-014 只可观察已纳入 Feature 的事件事实 |

## 4. 详细用例

### FEAT-014.a2a.two-hop-round-trip - 两跳 A2A 事件调用

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.1-§5.1.7。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的 caller/target 生产消费、远端 taskId、阻塞退化、Task owner 和两跳事件语义；L2 仅补充事件名与交付门禁。
- **G**：三 Agent 以 FEAT-014 BUS profile 启动；mainplan 已知 trip route handle，trip 已知 hotel route handle；事件观察器按唯一 tenant/trace 过滤；可把 trip 响应延迟到调用方阻塞窗口之后，并可触发 hotel 明确业务失败。
- **W**：直接向 mainplan 参数化 SendMessage：正常两跳完成、trip 已接受但延迟完成、hotel 明确失败。
- **T**：
  - mainplan 作为主生产方发布面向 trip 的 `A2A_CALL_REQUESTED`，trip 发布面向 hotel 的独立请求；每跳外层含 source/target、tenant、correlation、deadline、幂等和 opaque route；
  - trip/hotel 接受后分别返回自身远端 `taskId`，两个远端 Task 由各自 runtime 拥有，Bus 不创建或推进 Task；
  - 正常响应沿相应 correlation 回到调用方 runtime 并回灌本地执行，mainplan 最终结果包含真实 hotel 数据；trip 已接受但未在窗口完成时保留 `A2A_CALL_ACCEPTED` 的远端 Task 引用而不是丢失接受事实；hotel 在已接受后发生业务失败时产生 failed `A2A_CALL_TERMINAL`，不能伪造成成功响应；接受前的确定处理失败由下一用例验证为 `A2A_CALL_FAILED`；
  - 不出现客户端 `CLIENT_INVOCATION_*` 事件，证明本用例没有越到 FEAT-013。
- **不应断言**：runtime 本地回灌/Task 树、Agent 编排算法、固定自然语言、broker/outbox 实现。
- **失败归类**：两跳事件、远端 taskId、owner 或业务结果不符为 Failure；正式 BUS profile/runtime adapters 缺失为 Skipped；环境异常为 Error。
- **方法**：`feat014TwoHopA2aEventsReturnRemoteResultsToCallingRuntime()`。
- **标签**：类级 `@Feature("FEAT-014: 总线支持 A2A 调用事件转发")`、`@Tag("feat-014")`、`@Tag("integration")`；方法级 `@Tag("blackbox")`、`@Story("FEAT-014.a2a.two-hop-round-trip: 两跳 A2A 事件调用")`、`@Tag("story-feat-014-a2a-two-hop-round-trip")`。
- **DisplayName**：`Feat-014 mainplan 经两跳 A2A 事件获得 trip 和 hotel 结果`。

### FEAT-014.a2a.delivery-and-isolation - 远端投递与隔离

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§5.1.6/§5.1.8。
- **测试类型**：contract。
- **Oracle 来源**：Feature 的事件投递幂等、当次 UNKNOWN、租户隔离、route handle 和大载荷边界。
- **G**：准备真实 Agent 产生的小型 inline A2A 请求信封、可由调用方 runtime 以同一 idempotencyKey/原始请求重试的远端创建、重复 bus event、过期 deadline、被调用方公开 admission 拒绝/接受前失败、接受窗口内无任何响应事实、跨 tenant handle、畸形 handle 和大 payloadRef 场景。
- **W**：先经调用方 runtime 执行同键远端创建重试，再在正式 broker 公共入口参数化重放/延迟已捕获信封；拒绝/接受前失败只通过公开 runtime admission 输入触发，不直接伪造成功响应。
- **T**：小型 A2A envelope 可 inline 且不承载外层治理字段；调用方同键重试复用 idempotencyKey，被调用方返回同一远端 taskId 且只创建一个远端 Task；同一 bus event 重复投递最多形成一个有效远端调用/副作用；过期事件不触达被调用方；明确拒绝不伪造 taskId，接受前确定失败产生可编程 `A2A_CALL_FAILED`；接受窗口内没有接受、拒绝、失败或响应事实时只形成当次 `UNKNOWN`；跨 tenant/畸形 handle 不投递到任何 Agent 且不以物理 endpoint fallback；大正文只通过引用进入事件；错误不泄漏物理地址、topic、queue 或 worker。
- **不应断言**：INPUT_REQUIRED 续接、本地 shadow task、内部去重 key、broker 产品重试码。
- **失败归类**：重复副作用、越租户投递或泄漏为 Failure；broker 夹具异常为 Error；正式事件合同缺失为 Skipped。
- **方法**：`feat014RemoteDeliveryIsTenantScopedAndRouteSafe()`。
- **标签**：`@Story("FEAT-014.a2a.delivery-and-isolation: 远端投递与隔离")`、`@Tag("story-feat-014-a2a-delivery-and-isolation")`、`@Tag("contract")`。
- **DisplayName**：`Feat-014 远端投递保持幂等、路由安全和租户隔离`。

### FEAT-014.a2a.stream-boundary - 远端流准备与实时数据分离

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.5。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的 ACCEPTED/STREAM_READY 分离、点对点 A2A SSE 和 token 不入 Bus。
- **G**：mainplan->trip 请求声明 streaming；trip 支持点对点 A2A SSE；观察器扫描所有 FEAT-014 事件正文。
- **W**：发起流式差旅请求，等待 `A2A_CALL_ACCEPTED`、`A2A_STREAM_READY` 和实时内容，并消费本次远端流至终态。
- **T**：accepted 与 stream-ready 可区分；streamRef/taskId 使调用方 runtime 与 trip 建点对点 A2A SSE；实时 token/SSE frame 不出现在 Event Bus；终态通过 A2A_CALL_TERMINAL 表达；断流不让 Bus 重放 token，也不改变任一端 Task owner。
- **不应断言**：streamRef 编码、transport 类、固定 token 数和内部订阅状态。
- **失败归类**：token 入 Bus、状态混淆或 owner 改变为 Failure；正式 BUS profile 缺失为 Skipped；环境异常为 Error。
- **方法**：`feat014RemoteStreamUsesBusOnlyForReadinessAndTerminalFacts()`。
- **标签**：`@Story("FEAT-014.a2a.stream-boundary: 远端流准备与实时数据分离")`、`@Tag("story-feat-014-a2a-stream-boundary")`、`@Tag("blackbox")`。
- **DisplayName**：`Feat-014 远端实时流点对点传输且总线只传控制事实`。

## 5. 文件、执行与退出标准

计划一个文件：`src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/Feat014A2aEventForwardingBlackboxTest.java`。

Agent 启动、Agent Card 可达、Event Bus relay 启动不单列；两跳真实业务已等价覆盖。

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；默认 Maven 仓库 `~/.m2/repository`；Docker 提供 broker、PostgreSQL 和故障代理。travel 坐标见 §1.1；Event Bus 与 FEAT-014-enabled runtime/profile 的坐标、classifier、构建 SHA 和 acceptance 别名当前缺失，是门禁。确定性 LLM、payloadRef 和事件重放数据由测试自动准备。

```powershell
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-014 test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-014-a2a-two-hop-round-trip test
```

测试结束关闭观察器、Event Bus、Agent/RDC、容器和代理，恢复网络并清除临时消息/目录。退出标准：Feature 当前事件转发能力全部通过或明确门禁，长期 Task 操作有 deferred 处置；不把 FEAT-004/017 回灌、直接 A2A 成功响应或 broker contract 结果冒充 FEAT-014 全链。
