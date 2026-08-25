---
feature_id: FEAT-001
feature_title: 标准化智能体服务入口
sut: travel-demo-mainplan ReactAgent（openjiuwen profile）
scope: Agent Card、A2A JSON-RPC over HTTP、SSE、Task 查询、断点续行和 runtime-to-runtime webhook callback 对外黑盒
status: partial
status_note: 历史 7 个 Story 已实现；本期 ReAct 断点续行合同层有执行证据，Client 直连 Runtime 真实 E2E 已 PASS
owner: TBD
tags: [integration, blackbox, openjiuwen, reactagent, feat-001]
depends_on:
  - com.openjiuwen.example:travel-demo-mainplan:0.1.0 可由 SutStack 启动
  - 普通调用具备可稳定响应的 LLM
  - callback 部署 profile 提供公开可用的 sender、receiver、store/handler、trust 和授权能力
  - 大载荷场景使用测试类内置的确定性 travel-demo 输入
related_docs:
  - 03-Upstream-Docs/develop/02-features/FEAT-001-standardized-agent-service-entrypoint.md
  - 03-Upstream-Docs/develop/03-architecture/L2-Low-Level-Design/agent-runtime/FEAT-001-standardized-agent-service-entrypoint.md
  - docs/cases/FEAT-003-agent-task-state-cache-reactagent.md
---

# FEAT-001 - ReactAgent 标准化智能体服务入口黑盒测试设计

## 1. 验收目标

将 `travel-demo-mainplan` 作为不可见内部实现的真实 ReactAgent 服务启动，仅通过 Agent Card、`POST /a2a`、
SSE、`GetTask` 和固定 webhook callback endpoint 验证 FEAT-001 的对外事实：

- Agent 可发现、可阻塞调用、可流式调用并可查询 Task。
- 已取得 taskId 的流式 Task 在 SSE 断开后继续执行，可查询当前快照并对活动 Task 重订阅。
- 断点恢复保持原 taskId，不重新创建 Task、不重复执行 Agent，也不隐式 Cancel。
- `SendMessage.params.pushNotificationConfig` 可触发受信任 runtime 间的点对点异步完成通知。
- callback 只承载结果性终态，并复用 A2A Task/Message result 表面。
- 能力关闭、非法 callback URL、未授权 receiver 请求及投递失败均具有稳定的外部行为。

测试不判断 controller、executor、TaskStore、队列、bean 或 SDK 扩展点如何实现这些行为。

## 2. 设计依据

1. version-scope 中的 MUST、OUT 和外部旅程是测试判定事实。
2. L2 的 endpoint、wire、状态和公开配置约束用于细化黑盒输入与输出。
3. L2 中的实现方案不作为黑盒测试输入或断言。

- 三个 Agent Card 发现地址及 Card 字段、public URL、skills/capabilities 真实性。
- `/a2a` 与 `/a2a/`、`SendMessage`、`SendStreamingMessage`、`GetTask` 和 JSON-RPC 错误面。
- 阻塞聚合、上下文连续性、有界等待、SSE 生命周期、`INPUT_REQUIRED` 及失败 Task。
- inline push config、异步 accepted Task、终态 callback、文本/大载荷标准表面及 streaming 分离。
- callback capability gating、可信 target、receiver 授权、投递失败和可选重试稳定性。
- 独立 Push Notification Config CRUD、gRPC 和普通 client 任意 callback URL 的明确 OUT 边界。

## 3. 黑盒测试拓扑

```text
普通 client / A2A SDK
          |
          v
travel-demo-mainplan ReactAgent -----> 外部 LLM
          |
          +---- HTTP POST ----> 测试侧 CallbackReceiver
```

- SUT 始终使用真实 `travel-demo-mainplan` 制品启动。
- 测试侧 LLM/CallbackReceiver 只是外部依赖，不注入 SUT，不调用 SUT 内部 SPI。
- 默认 profile：streaming 开启，pushNotifications 关闭。
- callback profile：测试只设置 L2 公开的 `openjiuwen.service.a2a.push-notifications=true`；其余 callback
  能力必须由正式 travel-demo 部署 profile 提供。能力不完整时 callback Story 失败，不能 skip。
- 大载荷输入由测试类内置，直接运行用例即可，不依赖系统属性、环境变量或外部测试数据文件。

## 4. 历史基线用例

以下 7 个 Story 为上一期已执行通过的既有能力。本期保留原设计，并在目标新制品上选择受断点续行影响的
流式生命周期、Task 查询和 INPUT_REQUIRED 场景复跑；历史通过不替代本期结果。

| Story | 测试方法 | 覆盖范围 |
|---|---|---|
| `FEAT-001.entry.discovery` | `feat001DiscoveryEndpointsExposeOneTruthfulCard` | 三个 Card endpoint、字段、public URL、skills、capabilities、JSONRPC 接口可调用 |
| `FEAT-001.entry.jsonrpc` | `feat001JsonRpcEntrypointAndErrorsFollowContract` | `/a2a[/]`、parse/request/params/method 错误、Push Config CRUD OUT、异常后服务存活 |
| `FEAT-001.message.blocking-query` | `feat001BlockingSendAndTaskQueryFollowContract` | 阻塞结果、context 延续、GetTask 稳定/not-found、correlation、有界等待 |
| `FEAT-001.message.streaming-lifecycle` | `feat001StreamingLifecycleAndInputRequiredFollowContract` | SSE wire、submitted/working/completed、闭流、INPUT_REQUIRED 与同 context 续接 |
| `FEAT-001.message.failure` | `feat001ExecutionFailuresUseObservableTaskSurface` | 同步/流式 LLM 失败、FAILED Task、公开错误原因、服务存活 |
| `FEAT-001.callback.delivery` | `feat001CallbackDeliversOnlyStandardTerminalResults` | capability、inline config、accepted Task、completed/failed、文本/大载荷、无中间态、streaming 分离 |
| `FEAT-001.callback.security-failure` | `feat001CallbackSecurityAndDeliveryFailureFollowContract` | 关闭能力、非法 callback URL、未授权/畸形 receiver 请求、投递 5xx、Task 终态及可选重试稳定性 |

历史代码位于：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/
  ReactAgentStandardizedEntrypointBlackboxTest.java
```

## 5. 用例详细设计

### FEAT-001.entry.discovery - Agent Card 发现与能力真实性

- **Given**：默认 mainplan 已启动，配置公开 description/public URL。
- **When**：请求标准、legacy 和 prefixed 三个 well-known endpoint，并使用 Card 声明的 JSONRPC URL 调用。
- **Then**：三个 Card JSON 语义相同；字段、modes、skills 完整；URL 绝对且可拨通；streaming 与
  pushNotifications 声明和当前 profile 一致；不声明 gRPC。
- **标签**：`@Tag("feat-001") @Tag("blackbox") @Tag("smoke")`。

### FEAT-001.entry.jsonrpc - 统一入口、错误与 OUT 边界

- **Given**：默认 mainplan 已启动。
- **When**：分别访问 `/a2a`、`/a2a/`；提交非法 JSON、非法 envelope/params、未知 method；调用五个
  独立 Push Notification Config CRUD method。
- **Then**：两条入口语义一致；错误使用 JSON-RPC error 且按规则保留 id；CRUD 返回 method-not-found；
  处理错误后 Card 仍可访问。
- **合并理由**：CRUD OUT 本质上是 unknown method 错误矩阵的一部分，不单独创建用例。

### FEAT-001.message.blocking-query - 阻塞消息与 Task 查询

- **Given**：正常 LLM profile 和一个测试侧慢响应 LLM。
- **When**：连续两次阻塞 `SendMessage`，两次查询第一轮 Task，查询不存在 Task，并对慢 LLM 发起调用。
- **Then**：结果为非空完成 Task；两轮共享 contextId、taskId 不同；GetTask 快照稳定且无重执行；not-found
  为标准错误；trace/task 可从响应或进程外部日志关联；慢调用在公开窗口内返回 Task/error 而非无限挂起。

### FEAT-001.message.streaming-lifecycle - SSE 与 INPUT_REQUIRED

- **Given**：streaming profile；交互旅程按公开方式启动 mainplan/trip/hotel demo。
- **When**：发送普通 `SendStreamingMessage`，再执行三轮缺参、补参、完成的公开 travel-demo 对话。
- **Then**：HTTP media type 为 SSE，每帧 `event: jsonrpc`；可观察 submitted、working、completed 顺序；
  `INPUT_REQUIRED` 在当前流结束前可见，续接使用同一 contextId，最终完成。

### FEAT-001.message.failure - 执行失败表面

- **Given**：mainplan 指向明确不可达的外部 LLM endpoint。
- **When**：分别通过阻塞和流式公开入口调用。
- **Then**：两种模式都形成可判断的 FAILED Task 或标准传输终止；错误原因对 client 可见且不泄漏 Java
  堆栈；失败后 Agent Card 仍可获取。

### FEAT-001.callback.delivery - 点对点终态投递

- **Given**：callback profile 完整可用；测试侧 receiver 返回 200；提供公开大载荷输入。
- **When**：携带 inline push config 调用 `SendMessage`，分别触发 completed、failed 和大载荷结果；另发起
  不携带 push config 的 streaming 调用。
- **Then**：请求先返回非终态 accepted Task且可立即 GetTask；completed/failed 各只投递一个结果性 callback；
  body 复用 JSON-RPC Task/Message result，不含 callback 私有顶层字段；文本一次性返回；大载荷使用非空的
  artifact/file/data/metadata/引用表面；streaming 只产生 SSE，不触发 callback。

### FEAT-001.callback.security-failure - 门控、安全与投递失败

- **Given**：默认关闭 profile、完整 callback profile、返回 500 的测试 receiver。
- **When**：检查 Card/固定 receiver；提交 inline callback；提交非 HTTP/HTTPS 的非法 URL；向 receiver 发送缺少授权或
  notification id 不一致的请求；让合法 Task 的 callback 投递失败。
- **Then**：关闭时不声明/不暴露且不静默降级；非法 URL、未授权/畸形请求被拒绝且无 callback；投递 5xx
  不改变 Task completed/failed 终态；如果观察到多次尝试，notification id 和 payload 必须稳定。

## 6. 本期断点续行增量

### 6.1 增量范围与当前实施切片

| 用例 ID | 新增或修改的能力 | ReAct travel 处置 | 当前状态 |
|---|---|---|---|
| `F001-R01` | SSE 断开不终止 Task，TaskStore 快照继续推进 | 主验证 | runnable；需形成稳定活动窗口 |
| `F001-R02` | 活动 Task 首帧当前快照和后续新事件 | 主验证 | runnable |
| `F001-R03` | 终态或订阅终态竞态回退 `GetTask` | 主验证 | runnable |
| `F001-R04` | 恢复不新建 Task、不重复执行或产生下游副作用 | 用 trip/hotel 请求 canary 或等价公开证据 | partial；Oracle 随 fixture 能力收敛 |
| `F001-R05` | `GetTask` 快照不早于已确认事件且不倒退 | 主验证 | runnable |

FEAT-001 的公共 Runtime 合同适用于 DeepAgent、ReActAgent 和 WorkflowAgent。公共协议合同不按 Agent
类型复制；三种适配器分别验证断流后执行生命周期和架构特有快照。本档只承载 ReAct travel，DeepAgent
和 WorkflowAgent 的增量 G/W/T 与状态分别写入其既有 FEAT-001 文档。E2E 采用风险导向组合，不以简单
参数化 URL 代替架构适用性证据。

本期明确排除：`CancelTask`、未取得 taskId 时的创建安全重发和创建幂等、历史事件逐帧重放、
cursor/offset、exactly-once、Runtime 重启和 Task owner 迁移。

### 6.2 F001-R01 - 断流后 Task 继续并可查询

- **Given**：通过 `SendStreamingMessage` 创建长任务，已从服务端事件取得 taskId，并确认 Task 非终态。
- **When**：客户端主动关闭流或切断测试拥有的客户端到 Runtime 连接；恢复网络后轮询 `GetTask(taskId)`。
- **Then**：Task 不因断流进入 FAILED/CANCELED；快照不早于断开前已确认事件；Task 继续推进；taskId 不变。
- **FAIL**：断流触发 cancel、Task 消失、快照倒退、出现第二个 Task 或重复下游执行。
- **INCONCLUSIVE**：断流动作生效前 Task 已自然终态，未形成可验证的活动窗口。

### 6.3 F001-R02 - 活动 Task 重订阅

- **Given**：原 SSE 已断开，`GetTask` 确认原 Task 仍为非终态。
- **When**：调用 `SubscribeToTask(params.id=taskId)`，不再发送 `SendStreamingMessage`。
- **Then**：HTTP 为 SSE；首个业务结果是订阅时的当前 Task 快照，随后只发送挂接后的新事件；taskId 不变；
  Agent 不重新执行。
- **FAIL**：缺少首帧快照、重新创建 Task、无界重放历史事件或产生重复副作用。
- **PARTIAL**：当前合同无 cursor，不要求补齐断线窗口内每个历史帧；最终快照和挂接后事件必须一致。

### 6.4 F001-R03 - 终态竞态回退

- **Given**：Task 已终态，或在建立订阅期间转为终态。
- **When**：`SubscribeToTask` 返回 `UnsupportedOperation`、等价协议错误或可识别空流后，调用一次 `GetTask`。
- **Then**：取得唯一最终快照；taskId 和结果保持不变；不重发创建，不重新执行 Agent。
- **FAIL**：创建新 Task，或把终态误报为无恢复线索的普通网络失败。

### 6.5 F001-R04/R05 - 无重复副作用与快照顺序

- **Given**：流中已依次确认状态或 Artifact A、B，并能通过公开请求 canary 或 SUT 增量日志识别 trip/hotel 动作。
- **When**：在动作开始后断流，执行 `GetTask`/`SubscribeToTask` 恢复，并重复查询同一 taskId。
- **Then**：已确认事实不从快照消失，状态不倒退；查询不触发执行；恢复前已完成的下游动作次数不增加；
  最终结果仍属于原 taskId。
- **FAIL**：已确认 artifact 消失、终态倒退、查询触发 Agent，或同一业务动作被再次执行。

### 6.6 受影响回归与自动化落点

本期只复跑 `FEAT-001.message.blocking-query`、`FEAT-001.message.streaming-lifecycle` 和与
INPUT_REQUIRED 相关的历史场景；Card、callback 和完整 JSON-RPC 错误矩阵仅在公共入口出现回归信号时扩大。

当前已存在聚焦 Java 初稿：

```text
src/test/java/com/huawei/ascend/sit/cases/integration/agent_bus/
  RuntimeReconnectBlackboxTest.java
src/test/java/com/huawei/ascend/sit/fixtures/reconnect/
  ReActReconnectFixture.java
src/test/java/com/huawei/ascend/sit/cases/e2e/reconnect/
  ClientRuntimeReconnectIT.java
  ReconnectJourney.java
```

Runtime 合同默认通过 `SutStack` 拉起正式 travel Runtime；当前合同 XML 为 2 项中未知 taskId 1 PASS、正向
断流 1 skipped。独立 Failsafe XML 中 `ClientRuntimeReconnectIT` 已实际执行 PASS 1/1（65.672 秒），不是用该
合同 skipped 替代 E2E 结论。E2E 复用
`SutStack`、`FaultLink` 和正式 Client SDK；`ReconnectJourney` 统一执行创建、断流、恢复、终态和
显式查询断言。原始 JSON-RPC error 与订阅首帧由公共合同测试使用底层 HTTP/SSE 留证；不 Mock LLM、
不直连 TaskStore 或 fixture 数据库，不修改 Runtime 产品代码。

## 7. Feature/Story 与执行

```java
@Feature("FEAT-001: 标准化智能体服务入口")
@Tag("feat-001")
@Tag("integration")
class ReactAgentStandardizedEntrypointBlackboxTest {
    @Story("FEAT-001.callback.delivery: 点对点异步完成通知")
    @DisplayName("Feat-001 inline callback 先接受 Task 再投递标准终态结果且与 streaming 分离")
    void feat001CallbackDeliversOnlyStandardTerminalResults() { }
}
```

执行全部 FEAT-001：

```powershell
.\mvnw.cmd test -Dgroups=feat-001
```

执行 callback Story 所在方法：

```powershell
.\mvnw.cmd test `
  -Dtest=ReactAgentStandardizedEntrypointBlackboxTest#feat001CallbackDeliversOnlyStandardTerminalResults
```

## 8. 退出标准

- 历史类仍保持上述 7 个 `@Test`；新增断点续行测试放入独立聚焦类，方法使用 `F001-Rxx` Story。
- `feat-001` 标签可选择全部用例，callback MUST 不通过条件注解静默跳过。
- 没有预绑定 taskId、任意 Spring 属性透传或内部 SPI/store 访问。
- ReAct travel 新增用例与最小受影响回归在目标新制品上通过；未形成活动窗口时记为 INCONCLUSIVE，
  环境或目标 JAR 不可用时记为 blocked/not-run，均不得写成 PASS。
