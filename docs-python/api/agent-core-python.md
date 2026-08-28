---
title: agent-core Python 接口文档
description: ReActAgent、WorkflowAgent、DeepAgent、Workflow、Tool、Runner 和 AgentCard 的 Python 开发接口。
audience: ai-coding
status: verified
---

# agent-core Python 接口文档

`openjiuwen` 是 OpenJiuwen Python 侧的核心 SDK。新业务 Agent 应优先使用本层完成 Agent 语义和智能能力，再通过当前项目的 AgentCore adapter 接入 runtime。当前 runtime 只负责托管、协议投影、状态和生命周期，不替代 Agent 的推理循环。

## 包结构速查

| 能力 | Python 入口 | 用途 |
|---|---|---|
| Agent 名片 | `openjiuwen.core.single_agent.schema.agent_card.AgentCard` | Agent 的 id、name、description 和输入/输出元数据 |
| ReAct Agent | `openjiuwen.core.single_agent.agents.react_agent.ReActAgent` | LLM 推理—工具调用循环 |
| ReAct 配置 | `openjiuwen.core.single_agent.agents.react_agent.ReActAgentConfig` | 模型、Prompt、上下文、迭代和工具相关配置 |
| Workflow Agent | `openjiuwen.core.application.workflow_agent.workflow_agent.WorkflowAgent` | 多 Workflow 的控制器型 Agent。**配置类 `WorkflowAgentConfig` 在 `single_agent.legacy.config`，构造即告警废弃**——托管单条工作流时改用工作流资源登记，见 [Workflow 指南](../how-to/workflow-agent.md) |
| DeepAgent | `openjiuwen.harness.deep_agent.DeepAgent` | 在 ReAct 之上提供任务循环、Workspace、SubAgent 和 Rail |
| Workflow | `openjiuwen.core.workflow` | `Start`、`End`、组件和连接组成的确定性 DAG |
| Runner | `openjiuwen.core.runner.Runner` | Agent/Workflow 注册、启动和执行入口 |
| 原生流 | `openjiuwen.core.session.stream.OutputSchema` | AgentCore 输出帧；由 runtime adapter 转换为 `QueryChunk` |

版本基线以 [`compatibility.md`](../compatibility.md) 和当前 runtime 的 `agent_runtime/requirements.txt` 为准。本文的模块路径以已验证的 `openjiuwen==0.1.16` 环境为准；升级后必须重新做导入、编译和端到端验证。

## ReActAgent 最小用法

```python
import os

from openjiuwen.core.single_agent.agents.react_agent import (
    ReActAgent,
    ReActAgentConfig,
)
from openjiuwen.core.single_agent.schema.agent_card import AgentCard


def build_agent() -> ReActAgent:
    card = AgentCard(
        id="my-agent",
        name="my-agent",
        description="一句话说明 Agent 职责",
    )
    config = (
        ReActAgentConfig()
        .configure_prompt_template([
            {"role": "system", "content": "你是一个可靠的助手。"},
        ])
        .configure_max_iterations(10)
        .configure_model_client(
            provider="OpenAI",
            api_key=os.environ["LLM_API_KEY"],
            api_base=os.environ["LLM_API_BASE"],
            model_name=os.environ["LLM_MODEL"],
            verify_ssl=True,
        )
    )
    return ReActAgent(card).configure(config)
```

DeepSeek 等 OpenAI 兼容端点使用 agent-core 支持的 `OpenAI` provider，实际模型、网关地址和密钥从环境或宿主配置注入，不写入源码。`AgentCard` 不能省略 id/name；模型配置不能只创建对象而不调用 `configure()`。

## 直接调用与 runtime 托管

开发阶段可以直接调用 Agent 的 `invoke()` / `stream()` 验证语义；服务化时由 runtime 适配器统一持有会话、取消和输出翻译：

```python
from agent_runtime.adapters.outbound.agentcore.handler import AgentCoreHandler
from openjiuwen.core.runner import Runner


def build_handler(agent_id: str) -> AgentCoreHandler:
    # Agent 在启动阶段已经通过 Runner.resource_mgr 注册。
    return AgentCoreHandler(agent_id, Runner)
```

业务 Agent 不应在 REST/A2A 路由中直接调用 `agent.invoke()` 后拼 JSON。正确链路是：

```text
AgentCard + AgentCore Agent
        ↓ Runner.resource_mgr 注册
AgentCoreHandler
        ↓ AgentHandler / ServeOrchestrator
REST 或 A2A 组合根
```

`AgentCoreHandler` 会根据资源登记选择 Agent 或 Workflow 的 streaming 入口，将 `OutputSchema` 转成 `QueryChunk`，并统一处理会话、终答、错误、中断和清理。适配器只读 runtime 的公开端口；不要让 `application` 或 inbound adapter 依赖 AgentCore 原生事件。

## Runner 与 ResourceMgr：注册与按标识解析

`Runner` 是执行入口与运行资源的持有者。三类资源各有登记方法，**provider 一律是零参可调用**（运行资源以 `resource_provider()` 取实例；类型注解写成接收卡片的形式，按注解写会在解析时报缺参数）：

```python
from openjiuwen.core.runner import Runner

Runner.resource_mgr.add_tool(tool, skip_if_exists=True)        # 工具执行体
Runner.resource_mgr.add_agent(card, lambda: agent)             # Agent 实例
Runner.resource_mgr.add_workflow(workflow_card, lambda: flow)  # 工作流实例
```

| 方法 | 返回 | 重复标识的行为 |
|---|---|---|
| `add_tool(tool, *, tag=, refresh=, skip_if_exists=)` | `Ok` / `Error` | 默认拒绝；`skip_if_exists` 为真时是幂等空操作，`refresh` 为真时替换 |
| `add_agent(card, provider, *, tag=, interface_url=)` | `Ok` / `Error` | 拒绝并返回 `Error`，**先登记的实例继续服务** |
| `add_workflow(card, provider, *, tag=)` | `Ok` / `Error` | 同上 |
| `get_agent(agent_id)` / `get_workflow(workflow_id)` | 协程，返回实例或空 | 按标识解析 |

**返回值必须检查**：重复登记不抛异常，只在框架日志里记一条，本次构造的实例被静默丢弃。

执行入口：`Runner.run_agent` / `run_agent_streaming` 走通用智能体路径，`run_workflow` / `run_workflow_streaming` 走工作流路径。runtime 的适配器按资源登记形态择取，业务装配不应复制这一分支判断。

## 工具（Tool）：卡片与执行体两步注册

工具由 `ToolCard`（语义契约）与执行体两部分构成，`LocalFunction(card=..., func=...)` 把两者绑在一起，`invoke` 以 `func(**inputs)` 调用——**形参名必须与 `input_params` 的属性名逐字一致**。

装配方式随 Agent 类型不同：ReAct 用「`ability_manager.add(tool.card)` 声明能力 + `resource_mgr.add_tool(tool)` 注册执行体」两步；工作流用 `ToolComponent(...).bind_tool(tool)` 绑实例；DeepAgent 经 `create_deep_agent(tools=[...])` 一次装配，并会把标识改写成 `<tool_id>_<agent_id>`。逐项说明见 [Tool 指南](../how-to/tools.md)。

`AbilityManager` 的公开方法有 `add` / `add_ability` / `get` / `list` / `list_tool_info` / `remove` / `reorder_tools` / `execute`。

## WorkflowAgent 最小用法

Workflow 适合输入校验、查询、格式化等步骤固定且需要审计的流程。WorkflowAgent 的具体配置模型在当前版本仍位于兼容模块，必须以安装环境的签名为准；不要照搬其他版本中不存在的 Builder 或方法名。典型责任划分是：

```text
agent/
  workflow_definition.py   # WorkflowCard、组件、连接和 AgentCore 定义
runtime/
  application.py           # Runner 注册、AgentCoreHandler、REST/A2A 工厂
```

运行 Workflow 时使用 `Runner.run_workflow_streaming(...)`；运行单 Agent 时使用 `Runner.run_agent_streaming(...)`。当前 runtime adapter 会基于资源管理器判断标识对应的入口，业务装配不应复制这一分支判断。

## DeepAgent

`DeepAgent` 适合需要任务循环、Workspace、SubAgent 或高阶 Rail 的复杂任务。它仍属于 agent-core/harness 语义层，定义和配置放在 `agent/`；服务入口、Runner 和 Handler 放在 `runtime/`。DeepAgent 的配置字段和内置工具随 `openjiuwen` 版本演进，应用必须锁定版本并在升级时重新验证：

```python
from openjiuwen.harness.deep_agent import DeepAgent
from openjiuwen.harness.schema.config import DeepAgentConfig

# card = AgentCard(...)
# agent = DeepAgent(card).configure(DeepAgentConfig(...))
```

不要为了接入 runtime 在 `runtime/` 重写 TaskLoop、SubAgent 委派或 Workspace 管理；这些属于 agent-core/harness 能力。

## 自定义 Rail：AgentRail 钩子链

- Tool 的描述、参数 schema 和执行语义属于 agent-core 语义层；协议 DTO 不属于 Tool。
- Rail 负责 Agent 推理循环中的横切逻辑，例如工具前后校验、审计和中断；REST/A2A 级别的状态投影仍由 runtime 负责。
- AgentCore 会话通过 `openjiuwen.core.session.agent.create_agent_session` 创建；续接输入通过 `InteractiveInput` 进入原生恢复路径。runtime 的 `ResumeInput` 只是外部统一契约。
- `OutputSchema` 是框架原生流，不能直接作为 REST SSE 或 A2A Event 返回。

## 不要这样做

| 反模式 | 为什么错误 | 替代 |
|---|---|---|
| 在 runtime Handler 内重写 ReAct 循环 | 丢失 agent-core 的模型、Tool、Rail 和会话语义 | 在 `agent/` 使用 `ReActAgent` |
| REST 路由直接调用 Agent 并拼 JSON | 绕过统一取消、Task、终答和错误投影 | `AgentCoreHandler` → runtime 组合根 |
| 只创建 `AgentCard` 不注册 Runner | 服务根据 id 无法找到 Agent | 启动阶段通过 `Runner.resource_mgr` 注册 |
| 直接把 `OutputSchema` 返回给客户端 | 客户端看到框架私有字段，协议无法兼容 | 经 adapter 转成 `QueryChunk` |
| 把 DeepAgent 的 Workspace/TaskLoop 复制到 runtime | 两套生命周期和状态语义会分叉 | 使用 agent-core/harness 原生能力 |
| 把密钥硬编码在 `ReActAgentConfig` | 泄漏凭据且无法按环境部署 | 环境变量或 secret provider |

## API 锚点（包内符号，按依赖可查）

| 符号 | 模块 |
|---|---|
| `AgentCard` / `ReActAgent` / `ReActAgentConfig` | `openjiuwen.core.single_agent` |
| `ToolCard` / `LocalFunction` / `RestfulApi` | `openjiuwen.core.foundation.tool` |
| `Model` / `ModelClientConfig` / `ModelRequestConfig` | `openjiuwen.core.foundation.llm` |
| `Workflow` / `WorkflowCard` / `Start` / `End` / `LLMComponent` / `ToolComponent` / `BranchComponent` / `QuestionerComponent` | `openjiuwen.core.workflow` |
| `create_deep_agent` / `DeepAgent` | `openjiuwen.harness` |
| `TaskCompletionRail` / `TaskPlanningRail` / `HeartbeatRail` | `openjiuwen.harness.rails` |
| `AgentRail` / `ForceFinishRequest` | `openjiuwen.core.single_agent.rail` |
| `Runner`（`resource_mgr`、`run_agent_streaming`、`run_workflow_streaming`） | `openjiuwen.core.runner` |
| `OutputSchema` | `openjiuwen.core.session.stream` |
| `create_agent_session` | `openjiuwen.core.session.agent` |

版本口径：`openjiuwen==0.1.16`。升级后必须重做导入检查与装配门禁。

## See also

- [Agent 开发路径](../how-to/agent-development-path.md)
- [ReAct 指南](../how-to/react-agent.md)、[Workflow 指南](../how-to/workflow-agent.md)、[DeepAgent 指南](../how-to/deepagent.md)
- [Runtime 公开接口](agent-runtime-python.md)
- [项目规范](../conventions/project-conventions.md)
