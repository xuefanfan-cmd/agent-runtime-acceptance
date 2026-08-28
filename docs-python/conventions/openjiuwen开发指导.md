---
title: Python Agent Runtime 开发指导手册
description: 面向 Agent、Handler、适配器、入口和宿主开发者的可执行开发规范。
audience: both
status: verified
examples:
  - examples/minimal-host.py
  - examples/a2a
  - examples/rest
  - examples/interactive
  - examples/versatile
snippets:
  - snippets/handler-fixture.py
  - snippets/rest-custom-channel.py
  - snippets/interrupt-resume-flow.py
---

# OpenJiuwen Python Agent 开发指导手册

本文是面向 AI coding 和工程开发者的 OpenJiuwen Python 开发指导。默认路径是先用 `openjiuwen` agent-core 完成 Agent 语义，再用当前 runtime 完成托管与服务化；每个主题都回答：应该怎样装配、什么做法会破坏边界、为什么、怎样配置、如何验收。

## 0. Agent 开发总则

### 0.1 首选分层

```text
agent/                  # agent-core：Agent、Tool、Rail、Workflow、Prompt、模型语义
        ↓
runtime/                # 当前项目：Runner 注册、AgentCoreHandler、协议和生命周期
        ↓
REST/A2A/宿主部署       # 对外服务和进程运行
```

新建 ReAct、Workflow 或 DeepAgent 时，先从 [`../api/agent-core-python.md`](../api/agent-core-python.md) 和 [`../how-to/agent-development-path.md`](../how-to/agent-development-path.md) 开始。不要从一个空的 `AgentHandler` 开始模拟 AgentCore 的推理循环。

### 0.2 依赖方向

| 层 | 可以依赖 | 不可以依赖 |
|---|---|---|
| `agent/` | `openjiuwen` agent-core、业务纯 Python 类型 | FastAPI、A2A SDK、runtime 内部模块、Redis |
| `runtime/` | `agent/`、runtime 公开端口和组合根 | 复制 AgentCore、把协议对象塞回 agent/ |
| `agent_runtime` | 自身 domain/ports/application/adapters 规则 | 让业务 Agent 反向修改 runtime 源码 |

Python 包路径没有编译期约束，但 `agent/` 与 `runtime/` 的职责边界必须保留。

### 0.3 何时直接实现 Handler

宿主 Agent 当前有两种接入方式：

| 方式 | 做法 | 适用场景 |
|---|---|---|
| SDK 方式 | 宿主实现 `AgentHandler`，或继承、组合 `agent_runtime/adapters/outbound/agentcore/handler.py` 的 `AgentCoreHandler` | 新部署，或宿主已经能提供自己的处理器；这是目标形态 |
| 存量方式 | `python -m agent_runtime.bootstrap.legacy_compat` 默认按 `agents.EDPAgent` 装载 `initialize` / `agent_stream`，由兼容桥接到 `AgentHandler` | 已有 `applications/a2a_service` 落位和存量调用约定，只想先替换 runtime；这是绞杀者迁移的过渡形态 |

因此，只有接入 AgentScope 等非 OpenJiuwen 引擎、远端 Agent、runtime 契约 fixture，或确实选择 SDK 方式承接宿主 Agent 时，才直接实现 `AgentHandler`。新 OpenJiuwen 业务优先使用 agent-core，再用 `AgentCoreHandler` 或等价公开 adapter 接入；存量方式的目标是迁移到 SDK 方式后下线兼容桥。

## 1. 心智模型

当前项目是“统一领域端口 + 入站/出站适配器 + 宿主组合根”的 runtime。Agent 框架、模型和协议不能直接穿透到其他层。

```text
HTTP/A2A/REST -> inbound adapter -> ServeOrchestrator -> AgentHandler
                                      |                   |
                                      |                   +-- AgentCore
                                      |                   +-- AgentScope
                                      |                   +-- Versatile/remote
                                      +-- Task/Session/interrupt/bus
                                      +-- QueryChunk -> wire projection
```

核心规则：

1. `AgentHandler` 是异构 Agent 的唯一执行端口；框架私有对象只留在 adapter。
2. Handler 产出领域 `QueryChunk`，不产 A2A Event、SSE 行或自定义 REST JSON。
3. `create_a2a_app` 与 `create_rest_app` 是组合根，不是业务 Handler。
4. 成功完成由流正常结束表达，不能伪造 `COMPLETED` 类型替代结束语义。
5. “类存在”不等于“生产路径已接线”，必须同时检查组合根和 E2E。

## 2. AgentHandler：runtime 唯一接入点

核心仓库：`agent_runtime/ports/handler.py`。权威方法是 `query`、`stream_query`、`start`、`stop`、`clear_session`；Python 侧另有 `agent_id`、`priority`、`is_healthy`。

### 1.1 最小实现

```python
from collections.abc import AsyncIterator
from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse


class DemoHandler:
    agent_id = "demo"
    priority = 0

    def is_healthy(self) -> bool:
        return True

    async def query(self, request: ServeRequest) -> QueryResponse:
        chunks = [chunk async for chunk in self.stream_query(request)]
        text = "".join(chunk.data.get("content", "") for chunk in chunks)
        return QueryResponse(result=text, conversation_id=request.conversation_id)

    async def stream_query(self, request: ServeRequest) -> AsyncIterator[QueryChunk]:
        yield QueryChunk.of_event("final_answer_chunk", content="hello")

    async def start(self) -> None:
        pass

    async def stop(self) -> None:
        pass

    async def clear_session(self, conversation_id: str) -> None:
        pass
```

`ServeRequest` 的真实字段和续接构造方式必须以 `agent_runtime/domain/context.py` 为准，不要用普通 `dict` 冒充请求对象。

### 1.2 推荐与反例

**推荐：框架对象在 adapter 内消化。**

```python
class AgentCoreHandler:
    async def stream_query(self, request):
        async for native in self._agent.stream(request.message):
            yield self._translator.to_chunk(native)
```

**不要：让 application 认识框架事件。**

```python
# 错误：REST/A2A/application 被迫依赖 AgentCore 的原生类型。
yield AgentCoreEvent(kind="answer", payload=...)
```

新增 AgentScope 或 Versatile 时，上层不应增加分支；差异必须在 `adapters/outbound/` 吸收。

### 1.3 流式、取消和清理

| 语义 | Python 表达 |
|---|---|
| onNext | `yield QueryChunk` |
| onComplete | async iterator 正常结束 |
| onError | 抛异常或 error chunk，按 adapter 约定 |
| 取消 | 消费侧停止请求下一帧 |
| 清理 | async generator 的 `aclose()` 或实现自己的 finally |

不要新增 `cancel()` 方法模拟取消。需要释放资源时，在 generator 中使用 `try/finally`；消费侧在对象提供 `aclose()` 时调用它。

## 3. QueryChunk：领域结果不是 wire DTO

来源：`agent_runtime/domain/result.py`。`QueryChunk` 顶层只有 `type` 和 `data`：

| 类型 | 用途 | 状态含义 |
|---|---|---|
| `chunk` | 普通增量、thought、tool、终答 | 继续工作 |
| `interrupt` | 用户输入或远端委派 | `INPUT_REQUIRED` 或内部编排 |
| `error` | 不可恢复失败 | `FAILED` |
| `remote_agent_output` | 远端 Agent 业务输出 | 继续工作 |

### 2.1 终答写法

```python
yield QueryChunk.of_final_answer("最终答案")
# 随后正常结束 async generator
```

不要把终答当成结束标记吞掉，也不要写不存在的 `QueryChunk(type="completed")`。`of_completion()` 是宿主/远端状态投影使用的普通内容事件，完成仍由流结束表达。

### 2.2 错误和中断

```python
yield QueryChunk(type=QueryChunk.TYPE_ERROR, data={
    "code": "UPSTREAM_TIMEOUT",
    "message": "remote agent timed out",
})

yield QueryChunk.of_interrupt(
    content="需要用户确认",
    interaction_id="interaction-123",
)
```

不要在领域帧顶层添加 `event_type`、`content`、`plugin` 等存量 wire 字段；这些字段进入 `data`，由 inbound channel 投影。

## 4. AgentCore、AgentScope 和 Versatile

### 3.1 AgentCore

源码：`agent_runtime/adapters/outbound/agentcore/handler.py`、`stream_adapter.py`。业务 Agent 来自 `openjiuwen` agent-core；runtime 只负责注册/调用边界和输出归一。必须验证 native output 逐帧转换、终答在结束前出现、`ToolCallInterruptRequest` 转为 `interrupt`、取消触发清理，以及 query/stream_query 的会话一致性。

不要在 `deploy/host_app.py` 直接调用 AgentCore 原生 `run()` 后拼 REST JSON；这会绕过统一的取消、Task 状态和协议投影。也不要把 AgentCore 的 Tool、Rail 或 Workflow 定义塞进 `runtime/`。

### 4.2 AgentScope

源码：`agent_runtime/adapters/outbound/framework/agentscope.py`。adapter 负责输入映射、事件归一、终态、异常和取消；`ServeOrchestrator` 不接收 AgentScope event。

```bash
pytest -q agent_runtime/tests -k 'agentscope or framework'
```

依赖未安装时应标记 skipped 并说明原因，不能用 stub 把“未接线”写成通过。

### 4.3 Versatile/远端 Agent

源码：`agent_runtime/adapters/outbound/remote/client.py`、`member_caller.py`、`batch_runner.py`。远端委派本身使用 `interrupt` 携带 delegation；远端业务输出使用 `remote_agent_output`，两者不能混为一谈。超时、HTTP 错误、畸形响应、断连和取消必须分别验证。

## 5. 服务化组合根

### 4.1 A2A

```python
from agent_runtime.bootstrap.a2a_app import create_a2a_app

app = create_a2a_app(
    handler,
    name="demo-agent",
    description="runtime demo",
    url="https://agent.example.com/a2a",
    task_store=task_store,
)
```

关键参数包括 `name`、`description`、`version`、`url`、`skills`、`config`、`access`、`task_store`、`readiness` 和 `task_observer`。显式参数优先于配置；传空 `skills=[]` 和不传 `skills` 不是同一语义。

不要在 Handler 内构造 A2A Task/Event。A2A SDK 的 `DefaultRequestHandler` 和 `TaskStore` 负责协议生命周期，runtime 只注入 executor bridge、Card 和观察投影。

### 4.2 自定义 REST/SSE

```python
from agent_runtime.bootstrap.rest_app import create_rest_app
from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel

app = create_rest_app(
    handler,
    channel=MobileBankChannel(),
    session_store=session_store,
    task_store=task_store,
)
```

当前工厂的契约检查要求显式 channel。两入口共存时，应共享 `ServeOrchestrator` 和 TaskStore，否则远端委派、续接和关闭排水会分裂。

## 6. RestChannel：协议转换责任

源码：`agent_runtime/adapters/inbound/rest/channel.py`、`mobile_bank.py`、`router.py`。

自定义 channel 必须分别定义：请求解析、上下文构造、事件格式化、错误格式化、非流式聚合。业务 channel 不调用模型，也不维护 Task 生命周期。

```python
class MyChannel(RestChannel):
    def parse_request(self, request):
        ...

    def format_event(self, chunk):
        return {"custom_rsp_data": {
            "event": chunk.data.get("event_type", "chunk"),
            "content": chunk.data.get("content", ""),
        }}
```

SSE 验收必须检查原始字节、媒体类型、事件顺序、终答、结束哨兵和错误信封；只执行 `json.loads` 会掩盖 framing 错误。

`MobileBankChannel.build_context` 生成的 REST `ServeRequest.metadata` 是闭集，只包含四个关联事实键：`trace_id`、`agent_id`、`caller_params`、`request_body`。其中 `request_body` 是原始请求体，对位存量宿主收到的 `context["body"]`；它通过 `ServeRequest.REQUEST_BODY_META_KEY` / `ServeRequest.request_body` 透传，runtime 领域层不解释其业务字段，也不能把它当成控制面。查询串在 `caller_params` 下保留，不能借此注入 runtime 控制键。

## 7. 工具、SkillHub 和 Bus

客户端工具结果必须带 `call_id`，区分成功、参数错误、超时、取消和执行异常，并继承 trace/tenant/cancel 上下文。

SkillHub 源码：`adapters/outbound/skillhub/factory.py`、`coordinator.py`、`handler.py`。推荐使用 `wrap_with_skill_hub(inner, coordinator)`，而不是在 Handler 内下载和注册技能。配置、认证、下载、摘要不匹配和不支持类型由 `SkillHubErrorCategory` 区分，不能统一吞成空技能集。

Bus 源码：`ports/bus.py`、`application/bus_consume.py`、`bootstrap/bus_wiring.py`。消费者必须处理 event id 幂等、ack/retry、乱序、关闭和跨租户读取。Bus event 不是 A2A/SSE event。

## 8. 状态、会话和中断续接

TaskStore 保存协议可见 Task；SessionStore 保存请求上下文；框架 session 保存模型/Agent 恢复状态。三者可以共享 Redis，但不能合并语义。

`ServeRequest.metadata` 里的 `request_body` 是入站报文带来的关联事实，不是状态作用域字段。REST 通道从原始 body 写入它；A2A 通道从数据片段的 `session_context.body` 取值后写入同一个键。`ServeRequest.request_body` 缺失时返回空映射，宿主 Agent 自行解释载荷；runtime 不用它决定任务、会话或续接控制。REST 的四键闭集为 `trace_id`、`agent_id`、`caller_params`、`request_body`，不要把客户端任意 metadata 当成 REST 领域输入。

```python
resume_request = ServeRequest.for_resume(
    conversation_id=conversation_id,
    resume_input=resume_input,
)
async for chunk in handler.stream_query(resume_request):
    ...
```

流程是：`interrupt` → Task `input-required` → 保存 recovery point → 用户提交恢复输入 → adapter 翻译为框架原生 resume → 重走 `stream_query`。不能在 HTTP handler 中直接把 Task 改为 completed。

至少验证空输入、重复恢复、正常终答、客户端断连、进程重启和共享 store 缺失。

## 9. 生命周期和宿主装配

```text
配置读取 -> 依赖初始化 -> handler.start -> ready -> 接收请求
       -> 停止新请求 -> 排水在途流 -> handler.stop -> 释放资源
```

使用 `runtime_lifespan` 和 `init_hooks`/shutdown 机制；不要用 `@atexit` 或 `__del__` 管理模型、Redis、远端连接。健康检查只能说明相应依赖是否就绪，不能把进程存活误报为业务可用。

宿主装配有两种形态，但生命周期仍由 runtime 统一管理：

1. SDK 方式由宿主构造并注入自己的 `AgentHandler`，再交给 `create_a2a_app` / `create_rest_app` 或参考宿主装配。
2. 存量方式由 `agent_runtime.bootstrap.legacy_compat.host:create_app` 通过 `host_agent.py` 装载 `agents.EDPAgent`，把 `initialize` 作为启动钩子，把存量 `agent_stream(query=, conv_id=, cascade_result=None, context=...)` 接到同一个处理器端口。这只是迁移过渡，目标仍是 SDK 方式。

参考宿主 `deploy/host_app.py:create_app` 接受关键字参数 `handler=`；给定时只替换内建后端，Task/Session、协议入口、Skill Hub、健康检查和生命周期等其余装配不变。兼容宿主正是通过 `host_app.create_app(handler=handler)` 复用这套组合根。

## 10. 配置和机密

配置来源由宿主决定，runtime 不擅自扫描当前目录。建议优先级是：显式函数参数 > 宿主加载的运行时配置 > 安全默认值。

| 配置段 | 作用 |
|---|---|
| `runtime.a2a_access` | Card、公开 URL、skills、capabilities |
| `runtime.lifecycle` | shutdown timeout、排水、初始化策略 |
| `runtime.redis` | Task/Session/事件共享后端 |
| `runtime.remote` | Versatile URL、超时、鉴权 |
| `runtime.skill_hub` | provider、token、材料目录、重试 |

兼容入口涉及的三个宿主旋钮不属于 `openjiuwen.service` 配置；其中 `deploy/.env.example` 的 `legacy-entry` 分组只列 `RUNTIME_LEGACY_APP` 与 `RUNTIME_LEGACY_AGENT`，`RUNTIME_BACKEND` 是独立的后端选择开关：

| 变量 | 兼容入口语义 |
|---|---|
| `RUNTIME_LEGACY_APP` | 覆盖宿主工厂，写法为 `模块:工厂名`；未设置时使用 `agent_runtime.bootstrap.legacy_compat.host:create_app` |
| `RUNTIME_LEGACY_AGENT` | 覆盖存量宿主 Agent 导入名；未设置时使用 `agents.EDPAgent` |
| `RUNTIME_BACKEND` | 仅在兼容宿主装配下判定是否改用参考宿主内建后端；有值时不装载 `agents.EDPAgent`，使用 `fixture` 或 `agentcore`，未设置时才装载存量 Agent |

在直接使用参考宿主 `deploy/host_app.py` 时，`RUNTIME_BACKEND` 仍是其自身的 `fixture` / `agentcore` 后端选择；不要把 `RUNTIME_LEGACY_APP` 或 `RUNTIME_LEGACY_AGENT` 当成 runtime 配置键。

机密只从环境变量或 secret provider 注入；日志不得打印 token、完整 prompt、工具密钥或远端敏感响应。

## 10bis. Rail 拦截器

Rail 是推理循环的钩子链，不是工具、也不是分支表达式。`AgentRail`（`openjiuwen.core.single_agent.rail`）提供 invoke、model_call、tool_call、task_iteration 四级钩子与两个异常钩子；`priority` 决定同类钩子顺序；`ForceFinishRequest` 是 rail 请求终止循环的表达方式。

runtime 侧已提供两条与协议强相关的轨，不要重写：客户端工具轨（`adapters/outbound/agentcore/client_tool_rail.py`）与远端委派占位体（`adapters/outbound/remote/delegation_rail.py`）。

`openjiuwen==0.1.16` 未导出现成的中断 rail 与追问工具——需要工具审批或结构化追问时，工作流用 `QuestionerComponent`，单 Agent 自己实现 `before_tool_call` 钩子加挂起。逐项见 [Rail 指南](../how-to/rails.md)。

## 10ter. SubAgent、存储抽象与跨会话记忆的边界

这三项在 agent-core 侧有实现，**当前 runtime 不承载**：

| 能力 | agent-core 位置 | runtime 侧现状 |
|---|---|---|
| SubAgent 体系 | `openjiuwen.harness.subagents`、`create_deep_agent(subagents=...)` | 无托管接线；SubAgent 的会话与生命周期由 harness 自己管理 |
| 存储抽象层 | `openjiuwen.core.memory`、`core.retrieval` | 无对应端口；runtime 的 store 端口只覆盖 Task、会话与缓存 |
| 跨会话记忆 | `openjiuwen.core.memory.long_term_memory` | 无托管接线，也无配置面 |

**这不是"不支持"，是"不由 runtime 承载"**：业务在 `agent/` 层直接使用 agent-core 的这些能力即可，但不要期待 runtime 的配置树里出现它们的开关，也不要在 runtime 侧另建一套。

## 10quater. 会话持久化与可观测性

会话持久化在 Python 侧分三层，边界不同：Task 状态是协议可见事实（可外置、必须跨副本共享），会话快照是宿主内部事实（跨副本续接时共享），在途流句柄是进程内运行态（**不可外置**）。外置档的端点与过期时间配置见 [中间件配置](../how-to/middleware.md)。

可观测性方面，agent-core 有 `openjiuwen.extensions.tracer_otel` 的 rail 形态接入点，**runtime 侧没有对应的装配接线**。当前的可观测手段是日志关联标识（`bootstrap/log_correlation.py`）与 Task 状态观察者。要接分布式追踪，属于宿主职责，按宿主自己的观测栈接入，不要在 runtime 里另造一套。

## 11. 反模式速查表

| 反模式 | 后果 | 正确替代 |
|---|---|---|
| Handler 返回 A2A Event | 领域层绑定协议 | 返回 `QueryChunk` |
| 直接调用框架 run 后拼 JSON | 绕过状态/取消 | 实现 Handler adapter |
| 只实现 `query` | 流式入口失败 | 同时实现 `stream_query` |
| 把终答当 completed 类型 | 终答被吞 | 终答 chunk + 正常结束 |
| REST/A2A 各建 Orchestrator | 状态、远端链路分裂 | 宿主注入共享实例 |
| TaskStore 用进程内字典上生产 | 多副本读不到状态 | 使用共享后端 |
| SkillHub 错误统一吞掉 | 能力静默缺失 | 保留错误分类和重试 |
| 只看 HTTP 200 | framing/终态漏检 | 原始 wire + 状态 + 退出码 |

## 12. 端到端校验

```bash
pytest -q agent_runtime/tests
python -m compileall -q deploy
```

每次新增 Agent 类型都记录 Handler 契约检查、Card/REST 可达性、首块/终答/正常结束、Task/Artifact 一致性、取消/断连/恢复清理，以及远端/Bus/SkillHub 的错误分类。

## 13. 源码锚点

| 主题 | 锚点 |
|---|---|
| Handler SPI | `agent_runtime/ports/handler.py` |
| 请求/结果 | `agent_runtime/domain/context.py`、`domain/result.py` |
| 执行编排 | `agent_runtime/application/serve.py` |
| A2A 组合根 | `agent_runtime/bootstrap/a2a_app.py` |
| REST 组合根 | `agent_runtime/bootstrap/rest_app.py` |
| REST channel/router | `agent_runtime/adapters/inbound/rest/` |
| AgentCore | `agent_runtime/adapters/outbound/agentcore/` |
| AgentScope | `agent_runtime/adapters/outbound/framework/agentscope.py` |
| Versatile | `agent_runtime/adapters/outbound/remote/` |
| SkillHub | `agent_runtime/adapters/outbound/skillhub/` |
| Bus | `agent_runtime/application/bus_consume.py`、`ports/bus.py` |
| 生命周期 | `agent_runtime/bootstrap/lifespan.py` |
| SDK / 存量宿主装配 | `deploy/host_app.py`、`agent_runtime/bootstrap/legacy_compat/host.py`、`agent_runtime/bootstrap/legacy_compat/host_agent.py` |
| 存量部署说明 | `doc/deploy-edpagent.md`、`doc/upgrade-from-a2a-service.md` |
| 存量真 Agent E2E | `deploy-e2e/run-legacy-edpagent.sh`、`deploy-e2e/Dockerfile.legacy-edpagent` |

## See also

- [项目规范](project-conventions.md)
- [Agent Runtime Python 技术架构总览](../architecture/00-OpenJiuwen技术架构总览.md)
- [验证你写的 Agent](../how-to/verification.md)
