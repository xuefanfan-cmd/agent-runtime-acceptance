---
feature_id: FEAT-001
feature_title: 标准化智能体服务入口
sut: travel-demo-mainplan ReactAgent（openjiuwen profile）
scope: Agent Card、A2A JSON-RPC over HTTP、SSE、Task 查询和 runtime-to-runtime webhook callback 对外黑盒
status: implemented
owner: TBD
tags: [integration, blackbox, openjiuwen, reactagent, feat-001]
depends_on:
  - com.openjiuwen.example:travel-demo-mainplan:0.1.0 可由 SutStack 启动
  - 普通调用具备可稳定响应的 LLM
  - callback 部署 profile 提供公开可用的 sender、receiver、store/handler、trust 和授权能力
  - 大载荷场景使用测试类内置的确定性 travel-demo 输入
related_docs:
  - D:/code-agent/spring-ai-ascend-experimental/version-scope/FEAT-001-standardized-agent-service-entrypoint.md
  - D:/code-agent/spring-ai-ascend-experimental/architecture/L2-Low-Level-Design/agent-runtime/FEAT-001-standardized-agent-service-entrypoint.md
  - docs/cases/FEAT-003-agent-task-state-cache-reactagent.md
---

# FEAT-001 - ReactAgent 标准化智能体服务入口黑盒测试设计

## 1. 验收目标

将 `travel-demo-mainplan` 作为不可见内部实现的真实 ReactAgent 服务启动，仅通过 Agent Card、`POST /a2a`、
SSE、`GetTask` 和固定 webhook callback endpoint 验证 FEAT-001 的对外事实：

- Agent 可发现、可阻塞调用、可流式调用并可查询 Task。
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

## 4. 精简后的全量用例

| Story | 测试方法 | 覆盖范围 |
|---|---|---|
| `FEAT-001.entry.discovery` | `feat001DiscoveryEndpointsExposeOneTruthfulCard` | 三个 Card endpoint、字段、public URL、skills、capabilities、JSONRPC 接口可调用 |
| `FEAT-001.entry.jsonrpc` | `feat001JsonRpcEntrypointAndErrorsFollowContract` | `/a2a[/]`、parse/request/params/method 错误、Push Config CRUD OUT、异常后服务存活 |
| `FEAT-001.message.blocking-query` | `feat001BlockingSendAndTaskQueryFollowContract` | 阻塞结果、context 延续、GetTask 稳定/not-found、correlation、有界等待 |
| `FEAT-001.message.streaming-lifecycle` | `feat001StreamingLifecycleAndInputRequiredFollowContract` | SSE wire、submitted/working/completed、闭流、INPUT_REQUIRED 与同 context 续接 |
| `FEAT-001.message.failure` | `feat001ExecutionFailuresUseObservableTaskSurface` | 同步/流式 LLM 失败、FAILED Task、公开错误原因、服务存活 |
| `FEAT-001.callback.delivery` | `feat001CallbackDeliversOnlyStandardTerminalResults` | capability、inline config、accepted Task、completed/failed、文本/大载荷、无中间态、streaming 分离 |
| `FEAT-001.callback.security-failure` | `feat001CallbackSecurityAndDeliveryFailureFollowContract` | 关闭能力、非法 callback URL、未授权/畸形 receiver 请求、投递 5xx、Task 终态及可选重试稳定性 |

所有代码位于：

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

## 6. Feature/Story 与执行

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

## 7. 退出标准

- Java 类仅有上述 7 个 `@Test`，所有方法均有 `FEAT-001.*` Story 和 `Feat-001` DisplayName。
- `feat-001` 标签可选择全部用例，callback MUST 不通过条件注解静默跳过。
- 没有预绑定 taskId、任意 Spring 属性透传或内部 SPI/store 访问。
- 核心与 callback profile 的真实 travel-demo 黑盒执行全部通过。
