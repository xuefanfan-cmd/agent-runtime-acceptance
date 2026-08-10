---
title: 自定义 REST 入口：把宿主协议桥接到 A2A 执行链
description: 实现 CustomRestProtocolAdapter Bean 并配置 openjiuwen.service.custom-rest.query-path，即可在自有 REST 协议（路径变量、信封格式、SSE 事件流）下驱动已托管 Agent；含协议转换契约、同步/流式协商、会话续传与错误投影
audience: ai-coding
status: verified
snippets:
  - snippets/custom-rest-agent-configuration.java
  - snippets/custom-rest-protocol-adapter.java
  - snippets/custom-rest.yml
---

# 自定义 REST 入口：把宿主协议桥接到 A2A 执行链

custom-rest 是 runtime-ext 的**协议接入层**增量：当标准 `/v1/query` 的请求/响应
格式不满足企业自有协议（路径变量、统一信封、自定义 SSE 事件）时，你只需：

1. 实现一个 `CustomRestProtocolAdapter` Bean——声明「宿主协议 ↔ runtime A2A
   契约」的双向转换；
2. 配置 `openjiuwen.service.custom-rest.query-path`——要暴露的 POST 路径
   （支持 `{pathVariable}`）。

自动配置随即暴露该端点：请求经你的 adapter 转成 A2A 消息、由运行时 A2A
执行链驱动已托管的 Agent，结果再经 adapter 投影回宿主协议格式。

> ⚠️ 边界：custom-rest 只替换**协议外壳**——Agent 构造、托管、A2A 执行链完全
> 复用既有能力（前提是服务里已托管 Agent，见能力点 1）。当前仅支持
> **Servlet（Spring MVC）栈**，WebFlux 应用不生效。

## 适用场景 / 不适用场景

| | 说明 |
| --- | --- |
| ✅ 适用 | 企业已有统一网关协议（如 `/v1/{project}/agents/{agent}/conversations/{id}`），Agent 服务必须服从该协议 |
| ✅ 适用 | 响应需要统一信封（success/error/custom_data 等字段）或自定义 SSE 事件类型 |
| ✅ 适用 | 错误码体系需与宿主对齐（adapter 自行投影错误响应） |
| ❌ 不适用 | 标准 `/v1/query` 格式已够用——不必引入协议转换层 |
| ❌ 不适用 | WebFlux 应用——当前自动配置只装配在 Servlet 栈 |
| ❌ 不适用 | 想借此改变 Agent 执行逻辑——协议层管不到执行，去找 handler/中间件层 |

## 最小完整示例

**协议增量**：在任一 agent 服务工程（装配机制见
[配置驱动 Agent](config-driven-agent.md)，完整用例见
[examples/workflow/](../examples/workflow/)）上叠加三个片段——
**[snippets/custom-rest-agent-configuration.java](../snippets/custom-rest-agent-configuration.java)**
（Agent 装配改为手动声明 handler Bean）、
**[snippets/custom-rest-protocol-adapter.java](../snippets/custom-rest-protocol-adapter.java)**
（新增，协议转换 SPI 实现）与
**[snippets/custom-rest.yml](../snippets/custom-rest.yml)**
（`custom-rest.query-path` 配置段，并入工程 application.yml）。
一个 ReActAgent 暴露在自有协议 `POST /v1/chat/{conversation_id}` 下。核心接线摘录：

```java
// CustomProtocolAdapter.java：宿主请求 → A2A 命令
@Override
public A2ASendCommand toA2ARequest(Context context) {
    Message message = Message.builder()
            .role(Message.Role.ROLE_USER)
            .parts(new TextPart(String.valueOf(context.body().getOrDefault("input", ""))))
            .messageId(UUID.randomUUID().toString())
            .contextId(context.pathVariables().get("conversation_id"))  // 必填：业务会话 ID
            .build();
    boolean stream = Boolean.parseBoolean(
            String.valueOf(context.body().getOrDefault("stream", "false")));
    return new A2ASendCommand(MessageSendParams.builder().message(message).build(), stream);
}
```

```yaml
# application.yml
openjiuwen:
  service:
    custom-rest:
      query-path: /v1/chat/{conversation_id}   # 必须以 / 开头；缺省则端点不装配
```

所需 artifact 为 `com.openjiuwen:agent-service-app-custom-rest`；当前推荐版本见 [版本兼容与上游锚点](../compatibility.md)。

## 能力点逐个展开

### 1. 端点装配与执行前提

- 设置 `custom-rest.query-path` 即触发自动配置：注册一个 `POST <query-path>`
  端点（路径变量原样进入 `Context.pathVariables()`）；**不配则不装配任何端点**。
- query-path 必须以 `/` 开头，否则启动期抛 `IllegalArgumentException`。
- 请求体要求 JSON object：非 JSON 内容类型 → `415 unsupported_media_type`；
  JSON 非法或根节点不是对象 → `400`。
- **执行前提**：custom-rest 把请求转给运行时 A2A 执行链，因此服务里必须已有
  托管 Agent（handler Bean 或 agent-id 自动装配，见
  [配置驱动 Agent](config-driven-agent.md)）——本页示例复用与 skillhub 示例
  相同的 Agent 装配，差异只在 adapter 与 query-path。
- Agent 未就绪时请求返回 `503 agent_not_ready`。

### 2. 协议转换契约（5 个方法 + 4 个 record）

`CustomRestProtocolAdapter` 的全部方法：

| 方法 | 职责 |
| --- | --- |
| `toA2ARequest(Context)` | 宿主请求 → `A2ASendCommand(params, stream)` |
| `fromA2ATask(Task, Context)` | 同步执行结果 → 宿主响应体 |
| `fromA2AStreamEvent(StreamingEventKind, Context)` | 流式事件 → 宿主 `SseEvent` |
| `fromError(CustomRestError, Context)` | 失败 → 宿主错误响应体 |
| `fromStreamError(CustomRestError, Context)` | 流式失败 → 宿主 SSE 错误事件 |

配套 record（同一接口内）：`A2ASendCommand(params, stream)`、
`SseEvent(event, data)`、`CustomRestError(httpStatus, code, message)`、
`Context(headers, pathVariables, queryParams, body)`——**全部不可变**，
adapter 实现不要尝试修改。

契约要点：

- `message.contextId` **必填**（业务会话 ID）：为空 → `400 invalid_custom_request`；
  后续多轮对话、会话互斥、任务续传都以它为键。
- 投影方法（`fromA2ATask` / `fromA2AStreamEvent`）返回 `null` →
  `500 adapter_execution_failed`；返回值必须 Jackson 可序列化，否则同样 500。
- A2A 侧类型（`Message` / `MessageSendParams` / `TextPart` / `Task` /
  `StreamingEventKind`）来自 A2A SDK，随依赖传递可用。

### 3. 同步 / 流式：由命令与客户端协商

- `A2ASendCommand.stream=false` → 同步执行，响应为 `fromA2ATask` 的投影
  （`application/json`）。
- `stream=true` → SSE 响应（`text/event-stream`），每个 A2A 流事件经
  `fromA2AStreamEvent` 投影成一帧 `SseEvent(event, data)`。
- 流式要求客户端 `Accept` 包含 `text/event-stream`，否则 `406
  stream_not_acceptable`——「要不要流式」由你的 adapter 按宿主协议字段决定
  （示例用 body 的 `stream` 字段），框架只做协商校验。

### 4. 会话续传与会话互斥

- **续传**：`message.taskId` 为空时，框架按 `(tenant, contextId)` 查找既有
  A2A 任务并自动续接——同一 `conversation_id` 的多轮请求无需自己管 taskId。
- **互斥**：同一 `conversationId` 同一时间只允许一个请求在处理，并发到达
  → `409 conversation_busy`。宿主侧如需排队/重试，在网关层处理。

### 5. 错误投影

所有失败先归一为 `CustomRestError(httpStatus, code, message)`，再交给你的
`fromError` / `fromStreamError` 投影成宿主格式；投影返回 null 时框架退回
`{"error": {"code", "message"}}` 兜底。常见 code：

| code | HTTP | 触发 |
| --- | --- | --- |
| `invalid_custom_request` | 400 | contextId 缺失、JSON 根节点非对象 |
| `invalid_json` / `unsupported_media_type` | 400 / 415 | 请求体非法 |
| `stream_not_acceptable` | 406 | stream=true 但客户端不接受 SSE |
| `conversation_busy` | 409 | 同会话并发 |
| `agent_not_ready` | 503 | Agent 未就绪 |
| `a2a_<code>` | 按 A2A 错误码 | 执行链拒绝 |

## 配置项参考

- **openjiuwen.service.custom-rest.query-path**：要暴露的 POST 路径，必须以 `/`
  开头，支持 `{pathVariable}` 占位（值进入 `Context.pathVariables()`）；
  **缺省则整个 custom-rest 端点不装配**。

> ⚠️ 可设置属性边界：`openjiuwen.service.custom-rest.*` 下只有 `query-path`
> 一个对外属性。协议字段、超时、缓冲等均由 adapter 实现或框架内部决定，
> 不要尝试设置其他键。

## 坑位与排错

> ⚠️ **忘设 `message.contextId`**：每个请求 400 `conversationId is required`。
> 会话 ID 一般取自路径变量或 body 字段（示例取路径变量）。

> ⚠️ **`stream=true` 但客户端没声明接受 SSE**：固定 406。要么在 adapter 里按
> 宿主协议字段决定 stream，要么调用方加 `Accept: text/event-stream`。

> ⚠️ **query-path 不以 `/` 开头或为空串**：启动期
> `IllegalArgumentException: openjiuwen.service.custom-rest.query-path must be a
> non-blank absolute path pattern`。

> ⚠️ **投影返回了不可序列化对象**（如持有循环引用的内部类型）：500
> `adapter_execution_failed`。投影一律返回 Map/record/POJO 等 Jackson 友好类型。

> ⚠️ **在 WebFlux 应用里等端点出现**：custom-rest 只装配 Servlet 栈；标准
> `/v1/query` 的 MVC/WebFlux 开关与 custom-rest 无关（见
> [agent-runtime-java 接口文档](../api/agent-runtime-java.md)）。

## 端到端校验

1. 启动示例应用（端口 18095），确认无 query-path 校验异常。
2. 同步请求：

   ```bash
   curl -X POST http://localhost:18095/v1/chat/c-1001 \
     -H 'Content-Type: application/json' \
     -d '{"input":"你好，介绍一下你自己"}'
   ```

   预期：JSON 信封，`conversation_id` 回显 `c-1001`。
3. 流式请求：

   ```bash
   curl -N -X POST http://localhost:18095/v1/chat/c-1002 \
     -H 'Content-Type: application/json' \
     -H 'Accept: text/event-stream' \
     -d '{"input":"讲个短笑话","stream":true}'
   ```

   预期：SSE 帧序列（`chunk` × N → `final`）；去掉 `Accept` 头重发应得 406。
4. 多轮续传：用同一 `c-1001` 再问「我刚才问了什么？」，预期能承接上文
   （taskId 自动续接）。
5. 会话互斥：同一会话 ID 并发两个请求，后到一个应得 `409 conversation_busy`。

## API 锚点（jar 内类，按依赖可查）

- 协议 SPI：`com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter`
  （含 `A2ASendCommand` / `SseEvent` / `Context` / `CustomRestError` 四个 record）
- A2A 契约类型：`org.a2aproject.sdk.spec.Message`、`MessageSendParams`、
  `TextPart`、`Task`、`StreamingEventKind`、`TaskStatusUpdateEvent`
- 完整片段：[../snippets/custom-rest-protocol-adapter.java](../snippets/custom-rest-protocol-adapter.java) 等三件（本工程自有，见上方「最小完整示例」）

## See also

- [配置驱动 Agent](config-driven-agent.md)：托管 Agent 的装配方式（custom-rest 的执行前提）
- [A2A 跨智能体调用机制](a2a.md)：底层 A2A 契约与中断语义
- [runtime-ext 接口文档](../api/runtime-ext.md)：runtime-ext 各适配器速览
- [版本兼容与依赖坐标](../compatibility.md)：artifact 版本基线与坐标速查
