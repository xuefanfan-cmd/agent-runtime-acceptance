---
title: A2A 跨智能体调用机制
description: 用 create_a2a_app 把 Handler 暴露为标准 A2A 服务——Card、Task、流式事件与 Artifact 的装配与 wire 验收合成一页
audience: ai-coding
status: verified
examples: ../../docs/examples/a2a/
snippets: ../snippets/a2a-card-config.yml、../snippets/a2a-send-request.json
---

# A2A 跨智能体调用机制

## 适用场景 / 不适用场景

**适用**：Agent 需要被其他 Agent 或标准 A2A 客户端发现、提交 Task、查询状态、消费流式事件并读取 Artifact。服务暴露基本与 Agent 类型无关——ReAct、Workflow、DeepAgent、Versatile 对接都走同一条暴露路径。

**不适用**：

- 调用方已有固定业务 JSON / SSE 协议 —— 用 [自定义 REST 入口](custom-rest.md)，不要把业务 body 塞进 A2A parser。
- 只测 Handler 语义 —— 直接用确定性替身，不必启动 A2A 应用。
- 需要新增协议字段 —— 扩展 inbound adapter 与 wire 判据，不要在 Handler 里拼字段。

## 最小完整示例

完整源码：[`docs/examples/a2a/`](../../docs/examples/a2a/README.md)（协议闭环，确定性替身）；接真实 agent-core Agent 的形态见 [`docs/examples/react/`](../../docs/examples/react/README.md)。

```python
from agent_runtime.bootstrap.a2a_app import create_a2a_app

app = create_a2a_app(
    handler,
    name="a2a-demo",
    description="A minimal runtime A2A service",
    url="http://127.0.0.1:8080/a2a",   # 公开回连地址；留空即按请求地址推导
    config=load_runtime_config(),      # 卡片元数据、技能项与能力位从配置来
)
```

ASGI server、模型与宿主配置由部署层提供。Handler 只产领域 chunk，A2A Task 与 event 由入口投影。

## 能力点逐个展开

### Card

Card 的名称、描述、版本、公开 URL、skills、输入输出模态与 capabilities 都可以来自配置或显式参数。优先级：**显式关键字参数 > `config` / `access` 配置 > 安全默认值**。

`skills=[]` 与省略 `skills` 不是一回事：前者是「我声明这个 Agent 没有技能」，会盖掉配置；后者才表示「本参数没有意见，用配置的」。无技能的 Card 不应被远端工具安装链当作可调用工具集合。

`url` 留空不是缺省成 localhost，而是按当前请求地址推导。硬编码 localhost 会让按卡片回连的对端连错地方。

### TaskStore

不注入 `task_store` 时用协议库的进程内实现，适合本地验收。多副本或需要重启恢复必须注入共享实现。不要在 Handler 里维护第二份 Task 字典，否则 Task API 与执行状态会分裂。

### Executor 桥接

入口把 message 转成 `ServeRequest`，executor 调用 `handler.stream_query`，再把 `QueryChunk` 投影为 A2A event：

- 终答必须先以内容 chunk 出现，再让流正常结束；
- `interrupt` 进入 `input-required`；
- `error` 进入 `failed`；
- 其余 chunk 继续 `working`。

### Task 与 Artifact 的验收维度

不能只断言 POST 返回 200。必须保存 task id，检查状态序列、终态、Artifact 内容与 mime type、流式事件顺序；交互型 Agent 还要检查 `input-required` 之后的恢复请求。

### 两入口共存

同进程同时暴露 A2A 与 REST 时，REST 应复用 A2A 应用的 `app.state.orchestrator` 与同一个 TaskStore；否则远端委派、等待登记、续接与排水各自维护私有状态。

## 配置项参考

- **`openjiuwen.service.a2a_access.public_url`**：卡片对外声明的可达地址。留空即按请求地址推导。
- **`openjiuwen.service.a2a_access.json_rpc_path`**：JSON-RPC 挂载路径，默认 `/a2a`。
- **`openjiuwen.service.a2a_access.description` / `version`**：卡片描述与版本，默认版本 `1.0.0`。
- **`openjiuwen.service.a2a_access.capabilities.streaming` / `push_notifications`**：能力位，默认均为 `true`。
- **`openjiuwen.service.a2a_access.skills[]`**：技能项，字段为 `id` / `name` / `description` / `tags` / `examples` / `input_modes` / `output_modes`。
- **`openjiuwen.service.a2a_access.default_input_modes` / `default_output_modes`**：卡片级默认模态；两处都空即不输出该字段。
- **函数参数 `task_store` / `task_observer` / `readiness` / `init_hooks`**：共享状态、状态投影、就绪视图与启动钩子。

## 坑位与排错

**注意：配置里写了不等于运行时生效。** `create_rest_app` 不会自行读 `application.yml`，`create_a2a_app` 也只消费宿主传入的 `config`。验证要沿着 `ConfigLoader -> 宿主 -> 工厂` 检查消费链。

| 现象 | 检查 |
|---|---|
| Card URL 是 localhost | `url` 参数、`a2a_access.public_url`、反向代理公开地址 |
| Task 不结束 | Handler 的流是否正常结束、TaskStore 是否可写 |
| Artifact 为空 | 终答是否为内容 chunk、终态是否过早发送 |
| 返回 400 | message / parts 形状、role、text part 与 parser 日志 |
| 返回 404 | 入口挂载路径或反向代理前缀不一致 |
| 两入口状态不一致 | 是否共享 orchestrator 与 TaskStore |
| 重启后 Task 丢失 | 是否仍在用进程内 TaskStore |

## 端到端校验

```bash
curl -fsS "$BASE_URL/.well-known/agent-card.json"
curl -fsS -X POST "$BASE_URL" -H 'content-type: application/json' \
  -d @docs/snippets/a2a-send-request.json
curl -fsS "$BASE_URL/tasks/$TASK_ID"
```

记录 Card 原文、提交响应、Task 状态序列、Artifact、事件原始字节、退出码与环境。每次改动至少重跑一次同步提交与一次流式提交。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.bootstrap.a2a_app.create_a2a_app`
- `agent_runtime.ports.handler.AgentHandler`
- `agent_runtime.domain.result.QueryChunk` / `QueryResponse`
- `agent_runtime.adapters.inbound.a2a.card` / `protocol_adapter`
- `agent_runtime.bootstrap.config.runtime_config.A2AAccessConfig`

## See also

- [自定义 REST 入口](custom-rest.md)
- [取消、中断与续接](interrupt-and-resume.md)
- [A2A 入站 API](../api/agent-runtime-python.md)
- [协议、流式与兼容性架构](../architecture/02-agent-runtime-python技术架构.md)
