---
feature_id: FEAT-022
feature_title: ReActAgent 自定义 REST API 智能体服务入口
sut: agent-service-app-custom-rest；multi-react-travel-demo mainplan → trip → hotel
scope: ReActAgent 黑盒集成测试；Custom REST 配置、HTTP/JSON/SSE、多轮恢复、并发隔离、错误和存量入口共存
status: designed
owner: TBD
priority: P0
tags: [integration, blackbox, reactagent, custom-rest, feat-022]
depends_on:
  - agent-solution common 分支包含 PR 88
  - multi-react-travel-demo 0.1.0 三个可执行 artifact 已安装到本地 Maven 仓库
  - agent-service-app-custom-rest 0.1.0 artifact 已安装到本地 Maven 仓库
  - 本机已有可用的 LLM 配置
related_docs:
  - FEAT-022-custom-rest-api-agent-service-entrypoint.md
  - Feat-Func-022-custom-rest-api-agent-service-entrypoint.md
  - multi-react-travel-demo/README.md
---

# FEAT-022 - ReActAgent 自定义 REST API 入口黑盒测试设计

> **一句话**：acceptance 自动拉起 `multi-react-travel-demo` 的 mainplan、trip、hotel 外部进程，仅通过 HTTP、JSON、SSE 和标准 A2A 公共接口，验证 ReActAgent 对 FEAT-022 的支持程度。

> **仓库边界**：测试代码和 FEAT-022 自有数据只写入 `agent-runtime-acceptance`；不修改 travel Agent、`agent-runtime-java` 或 Custom REST 产品源码。

> **执行约束**：标准 `mvn test` 自动发现全部 FEAT-022 用例；直接运行 `CustomRestReactAgentBlackboxTest` 时，11 个已交付场景及其全部参数行均执行，tenant 已知缺口场景以 `@Disabled` 明确跳过。运行者不增加 `-D` 参数、Maven profile，不手工启动 Agent，也不手工准备测试数据。

## 1. 依据与范围

### 1.1 设计依据

1. [FEAT-022 version-scope](../../../spring-ai-ascend-experimental/version-scope/FEAT-022-custom-rest-api-agent-service-entrypoint.md) 的外部能力和边界。
2. [FEAT-022 L2](../../../spring-ai-ascend-experimental/architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-022-custom-rest-api-agent-service-entrypoint.md) 的对外配置、HTTP 合同、状态语义、错误码和验收准则。
3. [multi-react-travel-demo README](../../../agent-solution/common/example/multi-react-travel-demo/README.md) 仅用于确定真实 ReActAgent 拓扑和可触发的多轮流程，不扩大 FEAT-022 范围。
4. [FEAT-003 测试设计](./FEAT-003-agent-task-state-cache-reactagent.md) 仅参考“覆盖矩阵 + 客户旅程 + G/W/T + 方法落点”的组织方式。

### 1.2 范围内

- 一个可配置 Custom REST POST path 和一个业务 adapter。
- path、header、query、JSON body 到标准 Agent 请求的自定义映射。
- 自定义同步 JSON、流式 SSE event/data 和错误信封。
- Custom REST 与标准 A2A Task 状态语义一致。
- 客户端仅携带 conversationId 的首次创建和 `INPUT_REQUIRED` 多轮恢复。
- 终态后创建新 Task、单 JVM 同会话并发保护、不同会话隔离。
- tenant 与 conversation 的外部寻址隔离（当前disabled，等待已知缺口交付），以及 internal context 不泄漏。
- SSE 终止、错误和客户端断连的外部行为。
- Custom REST、`/a2a/`、`/v1/query` 在同一 ReActAgent runtime 中共存。

### 1.3 不在本档范围

| 排除项 | 原因 |
|---|---|
| DeepAgent、WorkflowAgent | 由其他测试负责人覆盖 |
| Custom REST GetTask、CancelTask、SubscribeToTask、webhook | 两份设计文档均未把它们列为当前交付能力 |
| 异步提交/轮询、multipart、文件、多模态、批量消息 | 非当前 MUST 或明确排除 |
| OAuth、签名、mTLS、认证授权、trace、审计 | version-scope OUT |
| runtime-to-runtime、agent-bus 使用 Custom REST | version-scope OUT |
| Redis 容量、性能、多副本 CAS、跨 JVM 并发 | L2 已知部署限制，不属于当前黑盒功能范围 |
| ReActAgent 规划质量、工具选择、酒店结果准确性 | travel Agent 只是真实执行依赖 |
| 产品内部类、算法分支和数据结构 | 黑盒集成测试不直接观察或操纵内部实现 |

## 2. 黑盒约束

允许的测试动作：

- 通过现有 acceptance 生命周期能力启动、停止 mainplan、trip、hotel 外部进程。
- 通过部署配置启用/关闭 Custom REST，并装配 acceptance 提供的业务 adapter。
- 通过 Custom REST、`/a2a/`、`/v1/query` 公共 HTTP 接口发请求。
- 断言 HTTP status、headers、JSON、SSE 帧、公开 Task id/状态和 Agent Card。
- 为 Agent 执行失败场景启动使用不可达 LLM endpoint 的独立 ReActAgent 进程。

禁止的测试动作：

- 直接调用、mock、spy 或反射 Custom REST 内部类。
- 直接读取、插入、修改或删除 TaskStore 数据来制造状态。
- 替换 runtime 的 RequestHandler、TaskStore、EventBus、A2A adapter 或 Agent handler。
- 通过内部日志证明调用了某个类或方法，并把它作为功能通过证据。
- 除文档明确标注的 tenant 已知缺口外，禁止使用 `@Disabled`、assumption、环境门禁或默认排除标签隐藏失败用例。
- 读取 OJ 测试 JSON、调用 OJ 测试类或继承 OJ fixture。

产品模块现有 34 个自动化测试是内部算法回归证据，本档不复制这些测试。

## 3. SUT 与零参数执行

### 3.1 真实 Agent

真实 ReActAgent 位于 `D:\code-agent\agent-solution\common\example\multi-react-travel-demo`：

```text
Custom REST client
        |
        v
travel-mainplan
        |
       A2A
        v
travel-trip
        |
       A2A
        v
travel-hotel + in-memory inventory
```

直接复用 `application-openjiuwen.yml` 已有的 `mainplan`、`trip`、`hotel` SUT 坐标，不为特性测试复制 Agent 坐标。FEAT-022 的 Custom REST path、adapter 和 launcher 仅由测试构建 `SutStack` 时注入到该测试拥有的独立进程，不会改写 FEAT-003/OJ 配置。唯一测试类启动一个共享的正常 travel 栈；非法装配和故障场景在测试方法内启动并关闭独立进程。

### 3.2 加载 Custom REST

mainplan fat JAR 当前未内置 `agent-service-app-custom-rest` 和业务 adapter。测试使用 JAR 已包含的 Spring Boot `PropertiesLauncher`，从外部加载：

- `agent-service-app-custom-rest-0.1.0.jar`；
- acceptance `target/test-classes` 中的 `CustomRestTestApplication` 和
  `CustomRestTestInitializer`，其中前者启动原始 travel application，后者注册业务 adapter。

测试类中的私有 `LayeredLauncher implements SutLauncher` 对 trip/hotel 委托现有 `ProcessLauncher`，对 mainplan 自动注入 `loader.path`、Custom REST path、trip 地址及：

```text
-Dloader.main=com.huawei.ascend.sit.cases.integration.react_travel.CustomRestTestApplication
```

Spring Boot 4 已不再通过旧的 `context.initializer.classes` 属性加载 initializer；同文件的
`CustomRestTestApplication` 因此以 `SpringApplication.addInitializers(...)` 在 context refresh 前显式
注册 `CustomRestTestInitializer`，再启动未修改的 travel mainplan application。运行者不传上述参数。

acceptance 侧配置只注册业务 adapter，不替换 runtime 核心 Bean。该做法等价于真实宿主引入扩展依赖和实现 SPI，黑盒断言仍全部来自进程外部。

### 3.3 零参数规则

- 测试类显式调用 `TestConfig.load(TestEnvironment.OPENJIUWEN)`，不要求 `-Dtest.env=openjiuwen`。
- Agent 进程继承本机现有 `${LLM_API_KEY}`、`${LLM_API_BASE}`、`${LLM_MODEL}`。
- artifact、LLM 或端口等必要依赖缺失时 setup 失败，不跳过。
- 已交付场景只使用`feat-022`、`integration`、`blackbox`标签；tenant场景额外使用`known-gap`和`@Disabled`。不使用Surefire默认排除的`e2e`、`performance`。
- 参数化用例没有条件过滤，每个数据行都必须产生执行结果。

### 3.4 测试 adapter 客户协议

Custom REST path 固定为：

```http
POST /v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}
Content-Type: application/json
Accept: application/json | text/event-stream
X-Tenant-Id: <optional>
```

请求 body 固定为：

```json
{
  "input": "用户输入",
  "stream": false,
  "workspace_id": "workspace-a",
  "custom_data": {
    "source": "feat022-acceptance"
  }
}
```

adapter 使用以下确定规则：

- path `conversation_id` 是唯一外部 conversationId；body 若出现同名字段且值不同，返回400 `field_conflict`。
- path `agent_id` 必须为 `travel-mainplan`；其他值返回400 `agent_not_supported`，不得路由到 trip 或 hotel。
- path `project_id`、可选 header、全部多值 query、`workspace_id` 和 `custom_data` 映射为请求 metadata；重复值保持到达顺序。
- `X-Tenant-Id` 是唯一 tenant 来源；header 缺失时 tenant 为null，不补默认值。
- `input` 必须是非空字符串；空HTTP body、`{}`、缺少 `input`、null或空白 `input`均返回400 `invalid_request`。
- `stream` 必须是boolean；缺失时默认true，false选择同步JSON，true选择SSE。

同步成功或当前Task投影固定为：

```json
{
  "success": true,
  "agent_id": "travel-mainplan",
  "conversation_id": "conversation-1",
  "task_id": "task-1",
  "state": "TASK_STATE_COMPLETED",
  "output": "...",
  "request": {
    "project_id": "project-a",
    "workspace_id": "workspace-a",
    "query": {},
    "headers": {},
    "custom_data": {}
  }
}
```

`request` 是 adapter 对本次客户请求映射结果的外部投影，用于黑盒验证 path/header/query/body；其中 header 名使用小写，多值使用JSON数组。`output` 在当前Task没有输出时为null，不据此伪造完成状态。

流式 event 名称固定为 `task`、`status`、`artifact`、`error`，分别对应公开A2A事件类型；只断言实际收到的事件，不要求每次执行产生全部类型。非错误 data 使用统一核心字段：

```json
{
  "success": true,
  "conversation_id": "conversation-1",
  "task_id": "task-1",
  "state": "TASK_STATE_WORKING",
  "final": false,
  "output": null
}
```

同步错误body和SSE `error` event的data固定为：

```json
{
  "success": false,
  "conversation_id": "conversation-1",
  "task_id": null,
  "error": {
    "code": "invalid_request",
    "message": "sanitized diagnostic"
  }
}
```

adapter保留framework给出的实际HTTP status和稳定小写错误码，包括`unsupported_media_type`、`invalid_json`、`stream_not_acceptable`、`invalid_custom_request`和`conversation_busy`；adapter自有校验使用`invalid_request`、`field_conflict`和`agent_not_supported`。同文件的宿主`@RestControllerAdvice`只把adapter校验异常转换为上述客户错误信封，不处理或改写framework异常。所有信封均不得出现internal contextId。

## 4. 覆盖矩阵

本档规划 **12 个黑盒集成场景**。输入变体是同一场景的数据行，不额外编号。

| 能力 | 场景 ID | 主要外部证据 |
|---|---|---|
| 入口启停、单 path、错误装配 | `FEAT-022.config.entry` | 进程启动结果 + HTTP 路由 |
| 单 hosted Agent、存量入口共存 | `FEAT-022.entry.coexistence` | Agent Card + 三种 HTTP 响应 |
| path/header/query/body 映射与字段冲突 | `FEAT-022.request.mapping` | 客户响应 + 公开 Task 内容 |
| 非法 HTTP/JSON/业务输入 | `FEAT-022.request.invalid` | HTTP status + 客户错误信封 |
| 同步 JSON 与 Task 状态 | `FEAT-022.sync.call` | JSON 信封 + 公开 Task 状态 |
| SSE 协商、事件和收束 | `FEAT-022.stream.call` | headers + event/data 序列 |
| conversationId 自动恢复 | `FEAT-022.conversation.resume` | 首轮可恢复状态 + 下一轮同一 Task id |
| 终态后同 conversation 新 Task | `FEAT-022.conversation.new-task` | 前后 Task id/状态 |
| 同会话保护与不同会话隔离 | `FEAT-022.conversation.concurrency` | 并发 HTTP 结果 + Task id |
| tenant/conversation 外部隔离（已知缺口，disabled） | `FEAT-022.conversation.tenant` | 客户可见 Task id/恢复结果 |
| SSE 客户端断连边界 | `FEAT-022.stream.disconnect` | A2A GetTask + 后续请求 |
| 同步/流式 Agent 执行失败 | `FEAT-022.error.execution` | JSON/SSE 失败语义 |

## 5. 黑盒子用例

### FEAT-022.config.entry - 入口配置与装配

- **G**：按参数行启动独立 mainplan：合法绝对 path、未配置、字面量 `false`、空白 path、相对 path、mapping 冲突、零 adapter、两个 adapter。
- **W**：等待进程达到 ready 或明确退出；对已启动实例探测 custom path、Agent Card 和 `/a2a/`。
- **T**：合法配置只暴露一个可用 POST path；未配置或 `false` 时 custom path 不存在且存量入口可用；非法/不完整装配在启动期失败，不出现半可用 custom 入口。
- **方法**：`feat022CustomRestEntryFollowsDeploymentConfiguration()`，参数化执行全部配置行。

### FEAT-022.entry.coexistence - 单 Agent 与三入口共存

- **G**：启动完整 travel 栈并启用一个 custom path；Agent Card 显示 hosted Agent 为 mainplan。
- **W**：分别调用 custom path、`/a2a/`、`/v1/query`；custom 请求中的 `agent_id` 使用 mainplan 值和一个不同值。
- **T**：合法agentId下三种入口均可执行且使用各自响应协议；Custom REST 未覆盖存量mapping；非`travel-mainplan` agentId返回400 `agent_not_supported`，不得路由到trip或hotel。
- **方法**：`feat022CustomRestCoexistsWithExistingEntrypointsForOneHostedAgent()`。

### FEAT-022.request.mapping - 自定义请求映射

- **G**：按3.4节准备path、重复header、重复query和嵌套JSON body中的唯一标志。
- **W**：发送一组合法映射请求，再发送body conversationId与path值冲突的请求。
- **T**：成功响应的`request`投影逐项等于输入，header名为小写且多值顺序不变；external conversationId等于path值；冲突请求返回400 `field_conflict`，不创建可见Task。
- **方法**：`feat022AdapterMapsHttpContextUsingDeclaredBusinessRules()`。

### FEAT-022.request.invalid - 非法请求

- **G**：Custom REST 已启用。
- **W**：参数化提交空HTTP body、`{}`、缺失/null/空白 input、stream非boolean、不支持的Content-Type、非法JSON、非object JSON根、空conversationId，以及不可接受或非法Accept。
- **T**：adapter输入错误返回400 `invalid_request`；Content-Type返回415 `unsupported_media_type`；JSON错误返回400 `invalid_json`；空conversationId返回400 `invalid_custom_request`；SSE不可接受返回406 `stream_not_acceptable`。所有响应使用3.4节错误信封且不得返回completed/success；随后以同conversationId提交合法请求仍可创建首个Task。
- **方法**：`feat022InvalidRequestsReturnDiagnosableClientErrors()`。

### FEAT-022.sync.call - 同步 JSON 调用

- **G**：准备一个信息完整输入和一个会触发 `INPUT_REQUIRED` 的输入，均设置 `stream=false`。
- **W**：分别通过 custom path 提交同步请求。
- **T**：响应为HTTP 200、`application/json`和3.4节客户信封；完整输入最终为completed；缺信息输入返回`INPUT_REQUIRED`当前Task投影，不声明completed；Task id/状态可追溯到标准A2A GetTask。任一执行失败均使本成功用例失败，并由`error.execution`单独验证失败语义。
- **方法**：`feat022SynchronousCallsReturnTraceableTaskSemantics()`，参数化执行两类输入。

### FEAT-022.stream.call - SSE 调用

- **G**：准备可完成输入和可触发 `INPUT_REQUIRED` 的输入，设置 `stream=true`。
- **W**：参数化使用缺失Accept、`text/event-stream`、`text/*`、`*/*`、非法Accept，以及`text/event-stream;q=0, */*;q=1`等精确度/q值组合发起请求。
- **T**：允许SSE的请求返回`Content-Type: text/event-stream`、`Cache-Control: no-cache, no-transform`、`Connection: keep-alive`和`X-Accel-Buffering: no`；每帧符合3.4节event/data合同且顺序与公开Task状态一致；本用例分别在`INPUT_REQUIRED`和completed后连接收束。非法Accept和精确`text/event-stream;q=0`均返回406 `stream_not_acceptable` JSON错误。
- **方法**：`feat022StreamingCallsNegotiateAndTerminateAsSse()`，参数化执行全部 Accept 行。

### FEAT-022.conversation.resume - conversationId 多轮恢复

- **G**：启动完整 mainplan → trip → hotel 栈；首轮只提供目的地，使正式 Task 进入 `INPUT_REQUIRED`。
- **W**：下一轮仅使用同一 conversationId 继续发送业务 input；两轮 request wire 均只含业务 input 和 stream 标记，不含 taskId。
- **T**：第一轮为 `INPUT_REQUIRED` 且取得非空正式 Task id；第二轮必须返回同一 Task id，公开 A2A Task 状态与客户响应一致。考虑真实 LLM 决策的非确定性，恢复后允许再次 `INPUT_REQUIRED` 或正常 `COMPLETED`，但不接受 `FAILED` 等执行异常；两轮响应均不含 `custom-rest:v1:`。
- **方法**：`feat022ConversationIdAloneResumesInputRequiredTask()`。

### FEAT-022.conversation.new-task - 终态后新 Task

- **G**：使用信息完整的输入直接创建一个 completed Task，记录 Task id。
- **W**：用同一 conversationId 再发送一个新业务请求，仍不提供 taskId。
- **T**：新请求不恢复已终态 Task，返回新的 Task id；新 Task 的公开状态与客户响应一致且不为执行失败，旧 Task 通过 A2A GetTask 仍保持 `COMPLETED`。
- **方法**：`feat022TerminalConversationStartsANewTask()`。

### FEAT-022.conversation.concurrency - 并发保护与隔离

- **G**：准备可形成足够执行窗口的 travel 输入和两个唯一 conversationId。
- **W**：先并发提交两个相同 conversationId 的首轮请求，再并发提交两个不同 conversationId 的请求。
- **T**：同 conversation 最多一个请求进入执行，另一个稳定返回 409 `conversation_busy`，客户响应中只能取得一个正式 Task id；不同 conversation 均可执行、Task id 不同且无全局 busy。
- **方法**：`feat022ConcurrentRequestsAreProtectedPerConversation()`。

### FEAT-022.conversation.tenant - tenant/conversation 外部隔离

- **状态**：`@Disabled("等待runtime补齐非空tenant到ServeRequest.tenantId传播")`、`@Tag("known-gap")`；测试代码生成但当前不执行。
- **G**：adapter 从 `X-Tenant-Id` 映射 tenant；使用相同 conversationId 和不同 tenant，并覆盖 null、空字符串、非空值。
- **W**：各命名空间分别发起首轮和续轮请求。
- **T**：不同 `(tenant, conversationId)` 不交叉恢复，客户可见 Task id 和上下文结果彼此独立；所有响应不泄漏 internal context。
- **方法**：`feat022TenantAndConversationNamespacesDoNotCrossResume()`。
- **启用条件**：runtime补齐非空tenant传播并提供可从ReActAgent公开响应观察的证据后，删除`@Disabled`和`known-gap`标签再执行；不得仅因内部字段单元测试通过而启用。

### FEAT-022.stream.disconnect - 客户端断连

- **G**：发起长流式请求并从客户 SSE 帧取得 Task id。
- **W**：客户端主动关闭连接；随后通过标准 A2A GetTask 查询该 Task，并用另一 conversation 发起新请求。
- **T**：断连不会仅因订阅关闭就把正式 Task 标记为 canceled；新 conversation 仍可正常调用；不要求底层模型或远端调用立即停止。
- **方法**：`feat022ClosingSseDoesNotCancelTheFormalTask()`。

### FEAT-022.error.execution - Agent 执行失败

- **G**：启动一个使用不可达 LLM endpoint 的独立 mainplan；其余 Custom REST 装配保持一致。
- **W**：分别提交同步和流式合法请求。
- **T**：同步返回 failed Task 投影或客户错误信封；流式返回可解析 error/failed terminal 后结束，或在尚未建立 SSE 时返回 HTTP 错误；两者均不得伪装 completed/success，也不得无限挂起。
- **方法**：`feat022AgentFailuresPreserveFailureSemanticsForJsonAndSse()`，参数化执行同步/流式两行。

## 6. FEAT-022 内联恢复数据

OJ 开头的文件只提供多轮对话的设计思路，不读取、不引用也不复制其测试数据。恢复数据只服务 FEAT-022 自有场景，直接定义在 `CustomRestReactAgentBlackboxTest` 中：

```java
private static final String INITIAL_INCOMPLETE_INPUT =
        "请帮我规划一次北京出差。目前只确定目的地，出发城市、日期和差标都未确定，请先询问缺失信息。";
private static final String RESUME_INPUT =
        "计划出差三天，下周二启程；请继续处理这次北京出差。";
```

测试为每次执行生成新的 conversationId，所有 Custom REST 请求均不出现 taskId。第一轮状态固定断言为 `INPUT_REQUIRED`；第二轮只断言恢复相同 Task，并允许真实 LLM 根据已收到的信息继续追问或完成。内联数据不依赖资源加载器、外部文件或测试执行顺序。

## 7. 需求追溯与黑盒边界

| FEAT/L2 外部能力 | 黑盒场景 |
|---|---|
| 单一可配置 POST path、装配完整性 | config.entry |
| 单 hosted Agent、存量入口共存 | entry.coexistence |
| path/header/query/body 映射、字段冲突 | request.mapping |
| 请求解析、必要输入和 Accept 错误 | request.invalid |
| 同步 JSON、自定义成功/错误投影 | sync.call、error.execution |
| SSE event/data、协商、顺序、终止和错误 | stream.call、stream.disconnect、error.execution |
| 首次建 Task、conversation 自动续轮、辅助 Task 过滤 | conversation.resume |
| 终态后新 Task | conversation.new-task |
| 单 JVM 同 key 保护、不同 key 隔离 | conversation.concurrency |
| tenant/conversation 寻址隔离 | conversation.tenant（disabled，不计入当前验收） |
| internal context 不泄漏 | request.mapping、sync.call、stream.call、conversation.* |

L2 中下列分支无法由真实 travel ReActAgent 通过公开接口稳定构造，因此不生成黑盒用例，也不伪装成已覆盖：

- 人工制造 `AUTH_REQUIRED`、未知状态、空状态、多个正式 Task 或未知同 context Task；
- 让 TaskStore 返回 null page、重复 page token 或内部异常；
- 制造 publisher 重复 terminal、迟到回调、非法投影或错误投影再次抛错；
- 统计 reservation 的创建、释放次数或内部 Task 分类过程；
- 直接读取 `ServeRequest.tenantId` 内部字段。

这些内容属于产品单元/组件测试或 runtime tenant 修复后的专项验收，不属于本次 ReActAgent 黑盒集成范围。

## 8. 代码落点

### 8.1 版本与依赖

`agent-runtime-java`、Custom REST 和 travel demo 使用 Spring Boot 4.0.6；acceptance 生成用例前应将父版本对齐到 4.0.6并先回归现有测试。增加 `agent-service-app-custom-rest` test-scope 依赖，版本与 travel Agent 内 runtime 保持一致。

### 8.2 文件规划

| 文件 | 场景 |
|---|---|
| `CustomRestReactAgentBlackboxTest.java` | 全部12个黑盒场景、内联恢复数据、私有嵌套`LayeredLauncher`、HTTP/SSE helper、进程fixture，以及同文件package-private的host main、initializer、adapter、adapter校验异常处理器和冲突controller |

测试文件落在 `src/test/java/com/huawei/ascend/sit/cases/integration/react_travel/`。

最终只生成 **1 个 Java 测试源文件**，不新增测试数据文件。Java 允许同一源文件声明多个 package-private 顶层类；因此 `CustomRestTestApplication`、`CustomRestTestInitializer`、业务 adapter 和 mapping 冲突 controller 与 JUnit 测试类放在同一 `.java` 文件中。编译后会产生多个 `.class`，但不会新增第二个 Java 源文件。

initializer 实现 `ApplicationContextInitializer<ConfigurableApplicationContext>`，根据测试类自动传给 mainplan 的 `acceptance.custom-rest.adapter-count=0|1|2` 和 mapping-conflict 开关，通过 `GenericApplicationContext.registerBean(...)` 在 context refresh 前注册对应 Bean。initializer 及 adapter 不引用 JUnit 或 acceptance 生命周期类；mainplan 只按类名加载 initializer，不扫描或加载 JUnit 测试类。

`CustomRestReactAgentBlackboxTest` 使用 `@TestInstance(PER_CLASS)`、`@Execution(SAME_THREAD)` 和 `@BeforeAll/@AfterAll` 管理共享正常栈。每个方法使用唯一 conversationId；只有 `conversation.concurrency` 在方法内部通过受控线程池并发。这样可以减少文件和 Agent 重复启动，同时保证用例之间不依赖业务状态或执行顺序。

### 8.3 测试签名

签名格式与 FEAT-003 已落地的 `RedisConfigurationAndDiagnosticsTest`、`RedisStandaloneBehaviorTest`、`RedisClusterAndSwitchTest` 保持一致：

- 类为 package-private，类级使用 `@Feature`、特性标签和 `integration` 标签。
- 每个测试方法为 package-private `void feat022...(...)`，仅在调用链需要时声明 `throws Exception`。
- 每个方法单独使用 `@Tag("blackbox")`、`@Stories({@Story("场景ID: 场景名称")})` 和以 `Feat-022` 开头的 `@DisplayName`。
- 单输入场景使用 `@Test`；多输入行场景使用 `@ParameterizedTest(name = "[{index}] {0}")` 和 `@MethodSource`。两者不会同时出现。
- `config.entry`、`request.invalid`、`sync.call`、`stream.call`、`error.execution` 为参数化方法；其余七个场景为普通 `@Test` 方法，其中`conversation.tenant`额外使用`@Disabled`和`known-gap`标签。
- `@TestInstance(PER_CLASS)` 和 `@Execution(SAME_THREAD)` 是单文件共享外部 Agent 栈所需的生命周期约束，不改变 FEAT-003 的 Allure/JUnit 归属格式。

## 9. 标签、运行与退出标准

```java
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Tag("feat-022")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class CustomRestReactAgentBlackboxTest {
    @Test
    @Tag("blackbox")
    @Stories({
            @Story("FEAT-022.conversation.resume: conversationId 自动恢复 INPUT_REQUIRED Task")
    })
    @DisplayName("Feat-022 客户端不传 taskId 可恢复 INPUT_REQUIRED Task")
    void feat022ConversationIdAloneResumesInputRequiredTask() throws Exception { }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidRequestCases")
    @Tag("blackbox")
    @Stories({
            @Story("FEAT-022.request.invalid: 非法请求")
    })
    @DisplayName("Feat-022 非法请求返回可诊断客户错误")
    void feat022InvalidRequestsReturnDiagnosableClientErrors(InvalidRequestCase testCase)
            throws Exception { }

    @Test
    @Disabled("等待runtime补齐非空tenant到ServeRequest.tenantId传播")
    @Tag("blackbox")
    @Tag("known-gap")
    @Stories({
            @Story("FEAT-022.conversation.tenant: tenant/conversation 外部隔离")
    })
    @DisplayName("Feat-022 tenant 与 conversation 命名空间不交叉恢复")
    void feat022TenantAndConversationNamespacesDoNotCrossResume() throws Exception { }
}
```

运行方式：

```bash
# 无附加参数，执行仓库全部测试并自动发现 FEAT-022
./mvnw test
```

在 IDE 或 JUnit 测试运行器中直接运行`CustomRestReactAgentBlackboxTest`时，不配置VM options、program arguments或profile；JUnit发现12个测试方法，执行11个已交付场景及其全部参数行，并明确跳过tenant场景。命令行按类筛选时只允许Maven自身的测试选择器，它不作为测试环境参数或用例生效条件。

退出标准：

1. `CustomRestReactAgentBlackboxTest`发现12个场景；11个已交付场景及其参数行全部执行，tenant已知缺口场景固定skip 1。
2. 除tenant已知缺口外，缺artifact、LLM不可用或Agent启动失败均记失败，不通过assumption转为跳过。
3. 首轮进入 `INPUT_REQUIRED` 后，下一轮仅凭 conversationId 恢复同一正式 Task；不强制真实 LLM 固定追问次数；终态后同 conversation 创建新 Task。
4. 同conversation并发不重复创建；不同conversation不交叉恢复。
5. 同步、SSE、错误、断连和存量入口共存的外部语义符合两份设计文档。
6. 所有客户响应均不泄漏 `custom-rest:v1:`；报告不把不可观察的内部实现声明为通过。
7. 报告将tenant场景标记为`DISABLED/KNOWN_GAP`，不纳入当前ReActAgent表象验收结论。

## 10. 代码生成可行性

本设计可以直接生成测试代码：真实Agent、拓扑、启动顺序、扩展加载方式、客户协议、请求数据、12个测试方法、参数行、外部断言和文件落点均已确定。生成时不需要新增测试范围、选择Agent类型或复用OJ文件。

tenant用例保留代码但默认disabled；其余11个场景构成当前ReActAgent在FEAT-022下的黑盒表象验收范围。
