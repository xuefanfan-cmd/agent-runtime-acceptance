---
title: 自定义 REST 入口：把宿主协议桥接到 runtime 执行链
description: 实现 RestChannel 并装配 create_rest_app，在自有 JSON / SSE 协议下驱动已托管 Agent——协议转换契约、SSE framing 与错误信封合成一页
audience: ai-coding
status: verified
examples: ../examples/rest/
snippets: ../snippets/rest-custom-channel.py、../snippets/rest-error-contract.json
---

# 自定义 REST 入口：把宿主协议桥接到 runtime 执行链

## 适用场景 / 不适用场景

**适用**：调用方已有固定业务 JSON、鉴权头、会话字段或移动端协议，同时要复用 runtime 的 Handler、Task、取消与生命周期。

**不适用**：

- 标准 Agent 间发现与 Task API —— 用 [A2A](a2a.md)。
- 想把框架原生 event 直接暴露给客户端 —— 先定义稳定 wire 合约，透传等于把内部结构变成对外契约。
- 只做 Handler 单测 —— 不必启动 Web 应用。

## 最小完整示例

完整源码：[`docs/examples/rest/`](../examples/rest/README.md)。

```python
from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel
from agent_runtime.bootstrap.rest_app import create_rest_app

app = create_rest_app(
    handler,
    channel=MobileBankChannel(),   # 必须显式传：契约检查发生在默认赋值之前
    session_store=session_store,
    task_store=task_store,
)
```

## 能力点逐个展开

### 组合根职责

`create_rest_app` 负责 Web 应用、生命周期、路由、执行编排器、就绪视图与各类 store 的接线；Handler 由宿主注入。两入口共存时，REST 应注入 A2A 应用的 `app.state.orchestrator` 与同一个 TaskStore。

### Channel 的五个边界

| 边界 | 输入 / 输出 | 必须处理 |
|---|---|---|
| `parse_request` | HTTP 请求 -> `ServeRequest` | body、query、header、缺字段与非法 JSON |
| `build_context` | 请求 metadata -> 执行上下文 | 会话、租户、trace；不把认证信息写进用户消息 |
| `format_event` | `QueryChunk` -> wire event | 事件名、字段嵌套、顺序 |
| `format_error` | 异常 -> 错误信封 | code、message、trace，且不泄漏调用栈 |
| 聚合出口 | `QueryResponse` -> JSON | 非流式结果与会话标识 |

Channel 不调模型、不写 TaskStore、不构造 A2A event。它只做业务协议转换。

### 领域块到 SSE

```python
def format_event(self, chunk: QueryChunk) -> dict:
    event = chunk.data.get("event_type", "chunk")
    return {"custom_rsp_data": {
        "event": event,
        "content": chunk.data.get("content", ""),
        "plugin": chunk.data.get("plugin", ""),
    }}
```

`final_answer_chunk` 是终答内容；`completed` 通常是内部完成信号，不应被 REST 流当成唯一终答。

### 会话与取消

session store 只保存请求上下文与续接所需关联，不替代框架会话。客户端断开时路由停止消费流并驱动底层清理；不要在 channel 里捕获异常后继续发送成功哨兵。

## 配置项参考

- **`channel`**：显式注入 `MobileBankChannel()` 或自定义 `RestChannel` 实现。
- **`session_store`**：单进程内存实现或共享实现；跨副本续接必须共享。
- **`task_store`**：与 A2A 共存时必须共享同一个实例。
- **`orchestrator`**：双入口共用同一个执行编排器。
- **`shutdown_timeout_s`**（`openjiuwen.service.lifecycle`）：在途流排水宽限期。
- **`init_hooks`**：Handler 与外部依赖的启动钩子。

## 坑位与排错

**注意：`channel` 必须显式传入。** 契约检查发生在默认值赋值之前，省略参数可能在启动期直接失败，而不是回落到默认 channel。

**注意：SSE 验收要看原始字节。** 只对响应做一次 JSON 解析会掩盖 framing 错误——`Content-Type: text/event-stream`、事件顺序、换行与结束哨兵都要逐项断言。

| 现象 | 检查 |
|---|---|
| 启动期 channel 契约错误 | 是否显式传了 channel 实例 |
| SSE 被客户端当普通 JSON | `Content-Type`、换行与 event framing |
| 有 200 但无终答 | `final_answer_chunk` 是否在正常结束前出现 |
| REST 与 A2A 续接不同步 | session / task / orchestrator 是否共享 |
| 断连后模型仍在跑 | 生成器 `finally`、消费侧停止与流关闭 |
| 错误泄漏调用栈 | `format_error` 是否统一脱敏 |

解析失败、业务失败、取消与客户端断连是四条不同路径，可以共享错误信封，但必须保留可诊断的 `code` / `message`。

## 端到端校验

```bash
curl -N -X POST "$BASE_URL/query" \
  -H 'content-type: application/json' \
  -d '{"message":"hello","conversation_id":"c1"}'
```

逐项验收原始响应：媒体类型、事件顺序、终答内容、结束哨兵、错误状态码、错误信封、客户端断连后的进程资源。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.bootstrap.rest_app.create_rest_app`
- `agent_runtime.adapters.inbound.rest.channel.RestChannel`
- `agent_runtime.adapters.inbound.rest.mobile_bank.MobileBankChannel`
- `agent_runtime.adapters.inbound.rest.router`
- `agent_runtime.domain.result.QueryChunk`

## See also

- [A2A 跨智能体调用机制](a2a.md)
- [Task 状态与缓存](state-and-cache.md)
- [Runtime 公开接口](../api/agent-runtime-python.md)
- [协议、流式与兼容性架构](../architecture/02-agent-runtime-python技术架构.md)
