---
feature_id: FEAT-012
feature_title: 网关组件客户端调用总线转发
sut: 正式 agent-gateway（BUS）-> agent-bus/FEAT-017 -> multi-react-travel-demo
status: designed-dependency-gated
tags: [blackbox, contract, integration, gateway, agent-bus, feat-012]
---

# FEAT-012 - 网关组件客户端调用总线转发测试设计

> 从与 DIRECT 相同的 Gateway A2A facade 发起真实 travel 调用，验证 BUS 路径的入队、五态投影折叠、STREAM_READY 后点对点 SSE、等待输入续跑及“治理/选路失败不入队”。

## 1. 设计依据与测试范围

### 1.1 输入快照

| 输入 | 锁定版本 |
|---|---|
| Feature | `D:\code-agent\feature-docs\develop\02-features\FEAT-012-client-invocation-bus-forwarding.md` |
| L2 | `D:\code-agent\feature-docs\develop\03-architecture\L2-Low-Level-Design\agent-gateway\Feat-Func-012-client-invocation-bus-forwarding.md` |
| Feature/L2 仓 | `main@7e1632dd96d49dad05747d8804631234be3cf457`，读取日期 2026-08-06 |
| acceptance 仓 | `main@eb5e3f20ca39f0a8bc647c1ca17b8a637370ce05`，读取日期 2026-08-06；本文为工作区设计变更 |
| 测试 Agent/RDC | travel 三 JAR 与 `com.openjiuwen:registry-discovery-center:0.1.0`，外部进程 |

Feature 的查询、取消、重订阅和 UNKNOWN 同键恢复是 MUST；L2 明确 730 不交付这些操作，仅保留当次 UNKNOWN 五态，因此按 `deferred` 处置。未查阅产品源码。

### 1.2 范围

本方案只验证 L2 730 交付的 Gateway BUS 黑盒行为：复用入口治理后的零入队、显式/默认 Agent 选路、创建与续跑控制事件、投影消费及五态折叠、投影去重/乱序、接受与流准备分离、点对点 SSE、同步/流式 client 断开处理，以及端侧工具结果和用户补充输入续跑。

DIRECT HTTP 转发、broker/outbox/inbox/worker 内部结构、runtime 的消费/投影代码、Agent 执行和 TaskStore 不作为本特性断言对象。

完整 E2E 必须有 FEAT-017 正式 runtime consumer/producer。L2 明确该依赖未就绪时不得宣称 BUS 端到端可用，因此所有真实 Agent 用例均为 **dependency-gated**；投影桩只能做 Gateway 单侧补充，不能替代 E2E 通过。

## 2. 拓扑与证据

```text
client -> Gateway(path=BUS) -> RDC -> Event Bus -> FEAT-017 -> travel-mainplan
                                    <- projections <-
client <- Gateway SSE <- point-to-point runtime SSE after STREAM_READY
```

- 拉起 RDC、Event Bus 正式制品、FEAT-017-enabled 三个 travel Agent；Gateway 只暴露统一 A2A facade。
- 为验证 Event Bus “有/无事件”可使用其公开订阅/审计表面记录 eventType、correlation、payload 体积和顺序；不得直接调用 Java SPI 或读取内部实现对象。
- 真实 Agent 的 taskId、业务结果和 SSE 是成功证据；事件观察只证明 Gateway BUS 行为，不替代业务完成。
- 测试不得把 BUS `correlationId` 传给 client；只断言请求无该字段、client 响应不泄漏它。
- 重复/乱序投影通过测试消费者在正式 broker 公共消息入口重放已捕获的同一投影信封并控制发送顺序；该分支单独标为 `contract`，不计作真实 Agent 全链通过。broker/RDC 故障使用进程外 `FaultLink`/Toxiproxy，禁止修改 outbox/inbox 表。

## 3. 最小用例矩阵

| 子用例 ID | Story/来源 | 类型 | 执行状态 | 自动化状态 | 覆盖 | 主要证据 | 等价覆盖/排除 |
|---|---|---|---|---|---|---|---|
| `FEAT-012.bus.sync-five-state` | Feature §2/§4/§5.1.0-5.1.4；L2 IN-2/3/9 | blackbox | dependency-gated, P0 | design-only | clientInvocationId、默认/显式选路、入队、inline/payloadRef、当次五态、同步断开 | Gateway 响应、事件审计、真实 Task 结果 | 重复/乱序由 contract 用例覆盖 |
| `FEAT-012.bus.streaming` | Feature §2/§4/§5.1.1；L2 IN-4 | blackbox | dependency-gated, P0 | design-only | STREAM_READY、点对点 SSE、token 不入总线、断开释放 | 事件摘要、SSE、真实终态 | 不验证 FEAT-013 底座内部 |
| `FEAT-012.bus.gate-and-continuation` | Feature §2/§5.1.0-5.1.2；L2 IN-1/5-7 | blackbox | dependency-gated, P0 | design-only | 治理/选路失败零入队、input_required/工具结果续跑 | 事件零增量、原 taskId、真实 Task 结果 | 工具治理归 FEAT-007 |
| `FEAT-012.bus.projection-contract` | Feature §5.1.1/§5.1.5；L2 Gateway 投影合同 | contract | dependency-gated, P1 | design-only | 投影重复、乱序、非法 streamRef 的折叠边界 | Gateway 公开结果和连接计数 | 不宣称 runtime/Agent E2E 通过 |
| `FEAT-012.deferred.task-operations` | Feature §2/§4/§6；L2 IN-10/11 | blackbox | deferred | design-only | GetTask、CancelTask、SubscribeToTask、UNKNOWN 同 clientInvocationId+幂等键恢复 | 待 730 后正式接口 | 当次 UNKNOWN 仍由 sync 用例覆盖；不生成空测试 |

### 当前交付能力追踪

| L2 730 交付能力 | 覆盖用例 |
|---|---|
| 统一入口、入口治理零入队、显式/default Agent、选路失败零入队 | `FEAT-012.bus.gate-and-continuation` 与 sync 用例 |
| 创建事件、外层信封、inline/payloadRef、投影消费者就绪 | `FEAT-012.bus.sync-five-state` |
| 同步五态、接受后响应超时、同步 client 断开 | `FEAT-012.bus.sync-five-state` |
| 投影去重/乱序、非法 streamRef | `FEAT-012.bus.projection-contract` |
| ACCEPTED/STREAM_READY 分离、点对点 SSE、token 不入 Bus、流断开 | `FEAT-012.bus.streaming` |
| INPUT_REQUIRED/TERMINAL、用户输入和实际工具结果续跑 | `FEAT-012.bus.gate-and-continuation` |
| Event Bus 不执行 Agent、不拥有 Task、物理路径透明 | 三条用例共同覆盖 |
| GetTask、CancelTask、SubscribeToTask、UNKNOWN 同键恢复 | `FEAT-012.deferred.task-operations`（L2 730 不交付） |

## 4. 详细用例

### FEAT-012.bus.sync-five-state - 同步入队与五态折叠

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.0-§5.1.4；L2 IN-2/3/9、S2。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature 的 clientInvocationId、入队、五态、payloadRef 和 UNKNOWN 语义；L2 730 Gateway 对外折叠合同。
- **G**：BUS path 就绪；RDC 有显式 mainplan 候选且默认 Agent 指向 mainplan；准备阈值内 inline 输入和超阈值 payloadRef 输入；通过上述黑盒夹具制造 broker produce 失败、投影消费者未就绪、接受后响应超时、明确拒绝、确定失败和接受窗口无投影。
- **W**：客户端生成唯一 `clientInvocationId`，不携带任何 path/correlation/topic 字段，分别带/不带 agentId 参数化 SendMessage；观察创建事件信封；通过真实 runtime 公开 admission/执行结果获得 RESPONSE/ACCEPTED/REJECTED/FAILED；在一个同步等待窗口内主动断开 client；对已完成请求以相同 `clientInvocationId`、幂等键和正文重试。
- **T**：
  - 显式目标与默认 Agent 均正确选路；正常调用只发布一次创建控制事件并返回真实 travel 结果；小输入以内联 payload 到达 Agent，大输入仅以 payloadRef 进入事件且目标仍取得完整原文；
  - 创建事件准确包含 `clientInvocationId`、`messageId`、`eventType`、权威 `tenantId`、`traceId`、Gateway 生成的 `correlationId`、`idempotencyKey`、opaque `routeHandle`、`capability`、`sourceServiceId`、`targetServiceId`、`deadlineMillisEpoch`、`payloadPolicy`，以及按合同需要的 `originalCaller`；A2A 正文只处于 `inlinePayload` 或 `payloadRef` 二者之一；client 响应不泄漏 path/correlation/topic/routeHandle/endpoint；
  - broker produce 失败不返回已入队或 taskId；投影消费者未就绪时入口明确失败且不发布；同步 client 断开后释放等待窗口、不自动取消 Task、不二次完整回传；
  - 正常完成折叠为 `COMPLETED_RESPONSE`；已见 taskId 后响应超时只返回 `ACCEPTED_WITH_TASK`，不得返回 `UNKNOWN`；从未见接受/拒绝/失败/响应才返回当次 `UNKNOWN`；
  - 明确拒绝/失败分别折叠为 `REJECTED`/`FAILED` 且不伪造 taskId；
  - 已 complete 的同 `clientInvocationId`、幂等键和正文重试回放结果且不二次 publish；所有响应隐藏 correlationId、routeHandle、topic、worker、endpoint。
- **不应断言**：broker/outbox/inbox 实现、固定投影轮数、Agent 自然语言全文。
- **失败归类**：字段/五态/幂等不符为 Failure；FEAT-017 或正式 Gateway/Event Bus 缺失为 Skipped；环境异常为 Error。
- **方法**：参数化 `feat012BusCreateFoldsProjectionsIntoExactlyOneClientResult()`。
- **标签**：类级 `@Feature("FEAT-012: 网关组件客户端调用总线转发")`、`@Tag("feat-012")`、`@Tag("integration")`；方法级 `@Tag("blackbox")`、`@Story("FEAT-012.bus.sync-five-state: 同步入队与五态折叠")`、`@Tag("story-feat-012-bus-sync-five-state")`。
- **DisplayName**：`Feat-012 总线创建把投影折叠为唯一客户端结果`。

### FEAT-012.bus.streaming - 流准备与数据面分离

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§4、§5.1.1；L2 IN-4、S2 streaming。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature/L2 的 ACCEPTED/STREAM_READY 分离、点对点 A2A SSE 和 token 不入 Bus。
- **G**：真实 mainplan 支持 streaming；Event Bus 观察器和 Gateway/runtime 连接计数可用。
- **W**：SendStreamingMessage；先延迟 ACCEPTED、提前 STREAM_READY，再正常建立流；收到若干业务内容后关闭 client。
- **T**：控制请求经 BUS；STREAM_READY 与 ACCEPTED 可独立到达，Gateway 只在有效 streamRef 且 client 存活时开点对点 SSE；client 收到真实状态/内容/终态帧；所有 bus 事件正文均无 token chunk、SSE frame 和完整大正文；client 关闭后桥接释放、不发布 cancel、Task 不被 Gateway 改为 canceled；重复 STREAM_READY 不二次开流。
- **不应断言**：SSE 连接池、topic 名、固定 token 数或内部 streamRef 编码。
- **失败归类**：数据面越界或错误桥接为 Failure；正式链路缺失为 Skipped；观察器异常为 Error。
- **方法**：`feat012StreamingUsesBusForControlAndPointToPointSseForContent()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-012.bus.streaming: 流准备与数据面分离")`、`@Tag("story-feat-012-bus-streaming")`。
- **DisplayName**：`Feat-012 流控制走总线而实时内容走点对点 SSE`。

### FEAT-012.bus.gate-and-continuation - 入队 gate 与续跑

- **状态/优先级**：dependency-gated, P0；**自动化状态**：design-only。
- **Story/来源**：Feature §2、§5.1.0-§5.1.2；L2 IN-1/5-7、S3/S4。
- **测试类型**：blackbox。
- **Oracle 来源**：Feature/L2 的治理/选路失败零入队、INPUT_REQUIRED 投影与原 Task 续跑边界。
- **G**：记录控制事件基线；准备无 Bearer、伪 tenant、RDC 空候选、RDC 超时、有效 mainplan；测试侧确定性 LLM endpoint 分别稳定产生用户 INPUT_REQUIRED 和真实端侧工具 INPUT_REQUIRED。
- **W**：先参数化发送治理/选路失败请求；再分别以 730 唯一续跑主路径 `SendMessage` 提交用户补充 TextPart 和 client 实际执行所得的工具结果 TextPart；最后用未知 taskId 伪造续跑。
- **T**：治理失败、空候选、无信封目标或 RDC 不可用均返回分层错误，创建控制事件增量为 0，也不登记投影等待；两类合法续跑均携原 taskId 经 BUS 到原 Task owner、parts/metadata 原样保留，分别产生 `INVOCATION_INPUT_REQUIRED` 后的响应及 `INVOCATION_TERMINAL`；未知 taskId 返回 `CONTINUATION_FAILED` 或等价错误，不能退化为新建成功；Gateway 不解释用户输入或工具结果业务语义。
- **不应断言**：工具审批/执行、runtime TaskStore、投影消费者内部状态。
- **失败归类**：失败请求入队、续跑新建 Task 或越权成功为 Failure；依赖缺失为 Skipped；夹具异常为 Error。
- **方法**：`feat012BusRejectsBeforeEnqueueAndContinuesOnlyKnownTask()`。
- **标签**：`@Tag("blackbox")`、`@Story("FEAT-012.bus.gate-and-continuation: 入队 gate 与续跑")`、`@Tag("story-feat-012-bus-gate-and-continuation")`。
- **DisplayName**：`Feat-012 失败请求不入队且合法续跑保持原 Task`。

### FEAT-012.bus.projection-contract - 投影折叠合同

- **状态/优先级**：dependency-gated, P1；**自动化状态**：design-only。
- **Story/来源**：Feature §5.1.1/§5.1.5；L2 Gateway 投影状态机。
- **测试类型**：contract。
- **Oracle 来源**：Feature/L2 对 Gateway 投影幂等、单调和 STREAM_READY 单次建流的公开合同。
- **G**：捕获真实 runtime 产生的一组 ACCEPTED、RESPONSE/TERMINAL 和 STREAM_READY 投影，保留原 eventId 与关联字段。
- **W**：仅从正式 broker 公共入口重放相同 eventId，并参数化控制 ACCEPTED 与终态的到达顺序以及重复/非法 STREAM_READY。
- **T**：Gateway 对重复事实只交付一次；终态后迟到 ACCEPTED 不回退；非法或重复 STREAM_READY 不二次开流。
- **不应断言**：Gateway 内部投影表、锁、消费位点或 broker 实现；不把本例计为真实 Agent 端到端通过。
- **失败归类**：公开折叠结果不符为 Failure；broker 夹具异常为 Error；正式 Gateway/Event Bus 缺失为 Skipped。
- **方法**：`feat012ProjectionReplayIsIdempotentAndMonotonic()`。
- **标签**：`@Tag("contract")`、`@Story("FEAT-012.bus.projection-contract: 投影折叠合同")`、`@Tag("story-feat-012-bus-projection-contract")`。
- **DisplayName**：`Feat-012 重复乱序投影保持单调且流引用只生效一次`。

## 5. 文件、执行与退出标准

计划一个文件：`src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/Feat012GatewayBusBlackboxTest.java`。

Gateway/Agent/Bus 能启动不单独设用例；正常 BUS 调用已覆盖就绪和接线。大载荷仅断言 Gateway 对 inline/payloadRef 的外部选择与业务完整性，事件信封合同由 FEAT-013 精确验证。

执行基线：JDK 21；PowerShell 使用 `.\mvnw.cmd`，WSL/Git Bash 使用 `./mvnw`；默认 Maven 仓库 `~/.m2/repository`；Docker 提供 PostgreSQL、broker 和 Toxiproxy。正式 Gateway、Event Bus、FEAT-017 的坐标/profile/服务别名当前未进入 `application-openjiuwen.yml`，因此保持门禁。确定性 LLM、payloadRef 数据和 broker 重放资源由测试自动准备。

```powershell
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=feat-012 test
.\mvnw.cmd -Dtest.env=openjiuwen -Dgroups=story-feat-012-bus-projection-contract test
```

测试结束关闭 Gateway/Event Bus/Agent/RDC、容器和观察器，恢复故障链路并确认端口/临时目录释放。退出标准：730 能力全部通过或明确门禁，长期 MUST 均有 deferred 处置；contract 与 blackbox 结果分开统计，投影重放不得冒充真实 Agent 全链。
