---
title: Versatile 对接：把远端工作流包成 Agent 参与编排
description: 用远端适配器把外部 HTTP/SSE 工作流包装成标准 Agent——请求与流的翻译、委派与业务输出、超时与错误映射、断连回收
audience: ai-coding
status: verified
examples: ../../docs/examples/versatile/
snippets: ../snippets/remote-versatile-config.py
---

# Versatile 对接：把远端工作流包成 Agent 参与编排

## 适用场景 / 不适用场景

**适用**：Agent 位于另一个进程、主机或服务，需要被本地编排委派，并把进度、业务输出、终态与错误纳入统一 Task 流程。

**不适用**：

- 同进程本地框架 —— 不要绕 HTTP 伪装成远端，用 [异构框架适配](framework-adapter.md)。
- 只是一次同步 HTTP 文本调用 —— 那是工具，不是 Agent，见 [Tool 定义与注册](tools.md)。
- 编排逻辑应当留在本地 —— 用 [Workflow](workflow-agent.md) 建 DAG。

## 最小完整示例

完整源码：[`docs/examples/versatile/`](../examples/versatile/README.md)，含 fake remote 与探针，可在无外部依赖下覆盖成功、错误与断连三条路径。

```python
from agent_runtime.adapters.outbound.remote.client import VersatileClient
from agent_runtime.adapters.outbound.remote.config import VersatileConfig

config = VersatileConfig(
    base_url="http://127.0.0.1:8091",
    connect_timeout_s=3,
    read_timeout_s=30,
)
client = VersatileClient(config)
```

地址、令牌与超时必须由宿主注入，字段以当前 `config.py` 为准。

## 能力点逐个展开

### 请求与流的翻译

远端客户端把本地请求转成远端协议；帧翻译器再把远端的 SSE / 状态帧转换回 `QueryChunk`。上层不应依赖远端 JSON 的字段名——那是适配器的内部事实。

### 委派与业务输出

发起委派用 `interrupt` 携带 delegation；远端产生的业务输出用 `remote_agent_output`，其中保留批次、工具调用、目标与来源。远端最终完成仍由本地编排器观察流结束与状态得出。

### 超时与错误映射

连接超时、读取超时、HTTP 错误、协议解析错误、远端业务失败、客户端取消是六种不同结果，必须可区分。把它们统一映射成「成功的空文本」是最难排查的一类缺陷。

### 断连回收

本地下游断开时要取消上游请求并关闭远端响应流；远端超时后不得留下后台任务。这条要在客户端、批次执行器与编排器三层各验证一次。

## 配置项参考（宿主远端配置，完整文件见示例目录）

- **`base_url`**：远端服务地址，宿主注入。
- **`connect_timeout_s`**：连接建立超时。
- **`read_timeout_s`**：读取下一帧超时。
- **总时限**：单次委派的整体上限，与前两项分开配置。
- **鉴权**：宿主 secret，不写进日志。
- **重试**：只对幂等且可恢复的错误重试；工具调用要带幂等标识。

## 坑位与排错

**注意：fake remote 通过不等于真实服务通过。** 两者差在 Card 与协议版本、SSE framing 与错误体形态。真实联调必须单独记录证据。

| 现象 | 检查 |
|---|---|
| 远端有输出但本地没有 | 帧翻译器是否识别远端事件类型与来源 |
| Task 停在 working | 远端流是否结束、批次执行器是否收到终态 |
| 重试导致重复工具调用 | 委派与工具调用标识是否幂等 |
| 取消不生效 | 响应流是否关闭、上游取消是否透传 |
| 所有失败都表现为空回答 | 错误映射是否把异常压成了成功 |

## 端到端校验

先跑本工程的 fake remote 覆盖成功、慢响应、HTTP 500、畸形 JSON 与断连：

```bash
cd docs/examples/versatile
PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests
```

预期 18 项通过。接真实远端服务时，另需覆盖多进程真 socket 场景，并记录远端日志、本地 Task 状态、原始 SSE 与退出码。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.adapters.outbound.remote.client`
- `agent_runtime.adapters.outbound.remote.member_caller`
- `agent_runtime.adapters.outbound.remote.delegation_rail`
- `agent_runtime.adapters.outbound.versatile`
- `agent_runtime.bootstrap.remote_wiring`

## See also

- [接入本地 Agent 框架](framework-adapter.md)
- [取消、中断与续接](interrupt-and-resume.md)
- [Runtime 扩展与适配器接口](../api/runtime-ext.md)
- [扩展体系与部署架构](../architecture/04-协作与扩展体系.md)
