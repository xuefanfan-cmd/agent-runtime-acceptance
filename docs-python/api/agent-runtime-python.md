---
title: agent-runtime Python 接口文档
description: AgentHandler SPI、领域契约、两类入站入口、状态端口与配置模型的自包含参考——按模块边界一页写完
audience: ai-coding
status: verified
---

# agent-runtime Python 接口文档

本 runtime 是嵌入宿主进程的 Agent 托管 SDK：提供执行契约、标准服务入口、状态与生命周期，不替代 Agent 的推理循环。本页按模块边界记录宿主与适配器开发者会直接 import 的公开面。

## 模块划分

| 模块 | 职责 | 宿主是否直接用 |
|---|---|---|
| `agent_runtime.ports` | 端口协议：Handler、状态、缓存、远端、总线、技能中心 | 是（实现或注入） |
| `agent_runtime.domain` | 领域类型：请求、结果、Task 状态机、续接输入 | 是（产出与消费） |
| `agent_runtime.application` | 执行编排、在途流登记、远端批次 | 少数（共享编排器时） |
| `agent_runtime.adapters.inbound` | A2A、REST、总线入站 | 间接（经工厂） |
| `agent_runtime.adapters.outbound` | agent-core、异构框架、远端、状态、技能中心出站 | 见 [runtime-ext.md](runtime-ext.md) |
| `agent_runtime.bootstrap` | 组合根工厂、配置加载、生命周期、各类 wiring | 是（装配入口） |

依赖方向单向：`adapters` 依赖 `application` 与 `domain`，`domain` 不依赖任何适配层。框架私有对象必须在适配器内部消化。

## AgentHandler SPI（唯一接入点）

```python
class AgentHandler(Protocol):
    agent_id: str
    priority: int
    def is_healthy(self) -> bool: ...
    async def query(self, request: ServeRequest) -> QueryResponse: ...
    def stream_query(self, request: ServeRequest) -> AsyncIterator[QueryChunk]: ...
    async def start(self) -> None: ...
    async def stop(self) -> None: ...
    async def clear_session(self, conversation_id: str) -> None: ...
```

`start` 只做依赖初始化与就绪所需检查，`stop` 关闭连接并等待在途执行。端口上**没有** `cancel` 方法：取消由消费侧停止拉取驱动，实现在异步生成器的 `finally` 里释放资源。

契约检查用 `ports.contract.satisfies` 或 `bootstrap.contract_check.require`；不要只用 `isinstance` 判断协议——运行时协议检查不验证成员类型与签名。

## 关键领域契约

| 对象 | 模块 | 用途 |
|---|---|---|
| `ServeRequest` | `domain.context` | 执行输入；`metadata` 是关联事实闭集，`request_body` 经 `REQUEST_BODY_META_KEY` 存取 |
| `QueryResponse` | `domain.result` | 非流式聚合结果 `(result, conversation_id)` |
| `QueryChunk` | `domain.result` | 流式领域块；构造器 `of_chunk` / `of_event` / `of_final_answer` / `of_interrupt` / `of_remote_agent_output` / `of_error` |
| `TaskState` 与状态机 | `domain.task.state_machine` | Task 生命周期推进，终态单向 |
| `ResumeInput` | `domain.waiting.continuation` | 交互续接输入 |
| `ClientToolRequest` / `ClientToolOutcome` | `domain.client_tool` | 客户端工具请求与结果 |
| `ServeOrchestrator` | `application.serve` | 执行、在途流登记、批次与状态协作 |

结果规则：内容用 `of_event` / `of_chunk`；终答用 `of_final_answer` 且随后正常结束流；用户输入用 `of_interrupt` 并携带交互标识；错误用 `of_error` 且保留 code、kind、message；远端成员输出用 `of_remote_agent_output`——它不等于发起委派。

## 入站入口：A2A

```python
app = create_a2a_app(handler, name="example-agent", description="...",
                     url="https://agent.example.com/a2a/", config=runtime_config,
                     task_store=task_store, init_hooks=(init,), readiness=readiness)
```

工厂位于 `bootstrap.a2a_app`。runtime 提供卡片、协议路由、执行器、Task 与就绪视图；`/health` 由宿主自建。公开地址位于卡片的 `supportedInterfaces[0].url`，未配置公开 base URL 时按当前请求地址推导。**显式关键字参数优先于配置段**。

| 操作 | JSON-RPC 方法 | 关键结果 |
|---|---|---|
| 提交 | `message/send` | `result.task` 或状态更新 |
| 流式提交 | `message/stream` | JSON-RPC 事件流 |
| 查询 | `tasks/get` | 参数键为 `id`，返回 Task |
| 列表 | `tasks/list` | Task 列表 |
| 取消 | runtime 取消入口 | `canceled` 状态或约定错误 |

## 入站入口：自定义 REST

```python
app = create_rest_app(handler, channel=MobileBankChannel(),
                      session_store=session_store, task_store=task_store)
```

工厂位于 `bootstrap.rest_app`，路由由 `build_rest_router` 生成，典型路径 `/v1/{project}/agents/{agent}/conversations/{conversation}` 及其 `/cancel`。**必须显式传 `channel`**：契约检查发生在默认赋值之前。

`RestChannel`（`adapters.inbound.rest.channel`）的五个边界：`parse_request`、`build_context`、`format_event`、`format_error`、聚合出口。

`MobileBankChannel.build_context` 产出的 `metadata` 是**闭集**，只有四个关联事实键：

| 键 | 语义 |
|---|---|
| `trace_id` | 入站请求追踪标识 |
| `agent_id` | 路径或请求体中的宿主 Agent 标识 |
| `caller_params` | 查询串的原样映射 |
| `request_body` | 原始请求体，对位存量宿主的上下文 body |

A2A 入站从消息数据片段的 `session_context.body` 取同一个 `request_body` 键；缺失时按空映射处理。runtime 领域层不解释其中字段，它不是控制面。

wire 形态：流式帧为 `custom_rsp_data.event` / `custom_rsp_data.content`，非流式聚合为 `{success, answer}`。错误出口包括 `channel_route_not_found`（404）、`unsupported_media_type`（415）、`invalid_json`、`invalid_body`、`invalid_request` 与远端失败终态——协议错误必须保留状态码与错误字段，不要让框架默认响应替代产品信封。

## 存量兼容入口

`python -m agent_runtime.bootstrap.legacy_compat` 的默认工厂是 `bootstrap.legacy_compat.host:create_app`，按 `agents.EDPAgent` 装载存量宿主 Agent，并复用参考宿主装配。`RUNTIME_LEGACY_APP` 覆盖工厂，`RUNTIME_LEGACY_AGENT` 覆盖导入名；设置 `RUNTIME_BACKEND` 时改用参考宿主内建后端而不装载存量 Agent。这是绞杀者过渡形态，目标是迁回 SDK 方式。

## 状态、缓存与其他端口

| 端口 | 模块 | 用途 |
|---|---|---|
| `SessionRequestStore` / `StateStore` | `ports.session` / `ports.state_store` | 会话快照、状态外置 |
| `RuntimeRedisClient` / `JsonCache` | `ports.cache` / `ports.json_cache` | 过期时间、键与值序列化 |
| `RemoteClient` / `RemoteBatchRunner` | `ports.remote` / `ports.remote_batch` | 远端卡片、调用、批次 |
| `CallbackSink` / `CallbackBackfiller` | `ports.callback` | 回调接收与异步回灌 |
| `BusDeliveryPort` / `BusResponsePublisher` | `ports.bus` | 事件消费、确认、发布与投影 |
| `SkillHubConfig` 与三个协议 | `ports.skill_hub` | 技能中心的取材、移交与目标解析 |
| `SecretValue` | `ports.secret` | 掩码凭据类型 |

Task 状态是**协议可见事实**，会话状态是宿主内部事实，在途流句柄是**进程内运行态**（不可外置）。三者可以共享后端，接口语义必须分开。边界与判据见 [Task 状态与缓存](../how-to/state-and-cache.md)。

## 服务配置速查

`bootstrap.config.runtime_config.RuntimeConfig` 是配置根，六个段：`extensions`、`lifecycle`、`credential`、`a2a_access`、`a2a`、`runtime_db`。状态缓存段 `middleware` 由 `bootstrap.cache_wiring` 单独按「段是否存在」判定装配。

加载器 `bootstrap.config.loader.ConfigLoader` 按 文件 -> secret 目录 -> 环境变量 顺序绑定，环境变量以双下划线表达层级，**不做 `${VAR}` 插值**。逐项说明见 [配置驱动装配](../how-to/config-driven-agent.md)。

## 生命周期

runtime 提供五阶段编排，宿主提供进程与探针端点：

```text
configure -> initialize（init_hooks）-> ready（就绪视图翻转）-> serve -> drain -> close
```

- **`init_hooks`**：启动钩子序列，按序执行。注册运行资源、连库建表都在这一步；**工作流注册必须在这里**（编译要事件循环）。
- **`readiness`**：就绪视图由 runtime 提供、端点由宿主自建。注入自己的实例时，runtime 会把同一个对象既交给生命周期又导出到应用状态——各建一个的话宿主永远读到初始值。
- **`lifecycle.init_fail_fast`**：启动钩子失败时是否让进程启动失败。
- **`lifecycle.shutdown_timeout_s`**：排水上限，默认 30。容器的终止宽限期必须大于它。

**不要再用 Web 框架的启动事件装饰器**：组合根已挂应用生命周期，二者互斥，用事件装饰器注册的逻辑会静默不执行。

## 事件与 wire 的转换边界

```text
框架原生事件 -> QueryChunk -> channel / 协议适配器 -> wire event
                                  └──────────────> 总线事件（可选）
```

三层可以共享事件类别，但对象不能互换。每个投影必须保留关联标识、终态与错误语义，并定义未知事件类别的降级方式。

## API 锚点（包内符号，按依赖可查）

- `agent_runtime.ports.handler.AgentHandler`
- `agent_runtime.domain.context.ServeRequest`、`domain.result.QueryChunk` / `QueryResponse`
- `agent_runtime.application.serve.ServeOrchestrator`、`application.active_streams`
- `agent_runtime.bootstrap.a2a_app.create_a2a_app`、`bootstrap.rest_app.create_rest_app`
- `agent_runtime.bootstrap.config.loader.ConfigLoader`、`config.runtime_config.RuntimeConfig`
- `agent_runtime.bootstrap.cache_wiring` / `task_store_wiring` / `state_store_wiring` / `remote_wiring` / `bus_wiring`
- `agent_runtime.adapters.inbound.rest.channel.RestChannel`、`inbound.rest.mobile_bank.MobileBankChannel`

## See also

- [agent-core Python 接口](agent-core-python.md)
- [Runtime 扩展与适配器接口](runtime-ext.md)
- [A2A](../how-to/a2a.md)、[自定义 REST 入口](../how-to/custom-rest.md)
- [Agent Runtime Python 技术架构总览](../architecture/00-OpenJiuwen技术架构总览.md)
