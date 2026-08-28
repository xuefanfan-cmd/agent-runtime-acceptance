---
title: Agent 开发路径
description: 新 Agent 从 agent-core 语义开发到 runtime 托管服务的标准路径。
audience: both
status: verified
---

# Agent 开发路径

## 适用场景 / 不适用场景

**适用**：要新建一个业务 Agent，需要先判断「用哪种 agent-core 形态、工程怎么分层、什么时候才自己实现 Handler」。

**不适用**：

- 已经选定类型 —— 直接进 [ReAct](react-agent.md)、[Workflow](workflow-agent.md)、[DeepAgent](deepagent.md) 三篇之一。
- 只想验证 runtime 协议 —— 用协议闭环工程的确定性替身，见 [`examples/`](../examples/overview.md)。

## 最小装配契约

在 OpenJiuwen Python 生态中，Agent 的业务智能能力优先基于 `openjiuwen` agent-core 开发；当前项目的 runtime 用于托管 Agent、统一协议、状态、取消和生命周期。不要把 runtime 的 Handler 骨架误当作业务 Agent 的首选开发方式。

```text
agent-core
  AgentCard / ReActAgent / WorkflowAgent / DeepAgent
  Tool / Rail / Workflow / Session 语义
        ↓
runtime AgentCore adapter
  Runner 注册 / AgentCoreHandler / QueryChunk
        ↓
runtime 组合根
  REST / A2A / Task / Session / lifecycle
        ↓
宿主部署
  进程 / 鉴权 / 健康检查 / 公开地址
```

这条链上每一跳的职责固定：语义在 `agent/`，服务装配在 `runtime/`，配置在 `resources/`，进程与部署策略在宿主。

## 能力点逐个展开

### 1. 先选 Agent 类型

| 需求 | agent-core 选择 | 说明 |
|---|---|---|
| LLM 自主决定多轮推理和工具调用 | `ReActAgent` | 通用单 Agent，先从此开始 |
| 步骤固定、输入输出可审计 | `Workflow` / `WorkflowAgent` | 用 DAG 表达流程，不在 prompt 中模拟流程控制 |
| 复杂任务拆解、Workspace、SubAgent | `DeepAgent` | 使用 harness 原生任务循环和工具设施 |
| 已有其他 Agent 框架 | 其他框架 + runtime adapter | 只有此类兼容场景不以 agent-core 为首选 |
| 只是验证 runtime 协议、状态或生命周期 | fixture Handler | 只作为验收替身，必须标明不是真实 Agent |

如果需求属于前三行，先读 [`api/agent-core.md`](../api/agent-core-python.md)，不要先从 `AgentHandler` 开始。

### 2. 创建标准工程

```text
<agent-project>/
├── src/<package>/
│   ├── agent/                    # AgentCore 语义层
│   │   ├── definition.py         # AgentCard、Agent 实例/构造入口
│   │   ├── tools.py              # 可选：Tool 定义与注册
│   │   ├── rails.py              # 可选：Rail
│   │   └── workflows/            # 可选：Workflow DAG
│   └── runtime/                  # 当前 runtime 服务层
│       ├── application.py        # 组合根、Runner 注册、Handler
│       ├── configuration.py      # 可选：runtime 配置
│       └── protocol/              # 可选：Custom REST 等协议增量
├── resources/
│   └── application.yml           # 模型、A2A、中间件和宿主配置
├── tests/                        # 语义、接线、协议和生命周期测试
├── pyproject.toml
└── README.md
```

一级职责边界是强约束，二级目录按内聚性决定：只有一个 Definition 时可以保留在 `agent/` 根包，不要机械创建空的 `tools/`、`rails/` 或 `workflows/` 目录。`agent/` 不反向导入 `runtime/`。

### 3. 实现 agent-core 语义层

以 ReAct 为例：

```python
# src/my_agent/agent/definition.py
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
        description="一个可被 runtime 托管的 Agent",
    )
    config = (
        ReActAgentConfig()
        .configure_prompt_template([
            {"role": "system", "content": "你是一个可靠的助手。"},
        ])
        .configure_model_client(
            provider="OpenAI",
            api_key=os.environ["LLM_API_KEY"],
            api_base=os.environ["LLM_API_BASE"],
            model_name=os.environ["LLM_MODEL"],
            verify_ssl=True,
        )
        .configure_max_iterations(10)
    )
    return ReActAgent(card).configure(config)
```

Prompt、Tool、Rail、Workflow 和模型参数属于这一层。真实模型调用的密钥只从环境或 secret provider 读取；DeepSeek 等 OpenAI 兼容服务沿用 `OpenAI` provider，并通过 `LLM_API_BASE` 区分网关地址。

### 4. 在 runtime 层注册和托管

runtime 层完成三件事：启动 AgentCore Runner、把 Agent 注册到资源管理器、把 Agent 包装为统一 Handler。示意：

```python
# src/my_agent/runtime/application.py
from openjiuwen.core.runner import Runner

from agent_runtime.adapters.outbound.agentcore.handler import AgentCoreHandler
from agent_runtime.bootstrap.rest_app import create_rest_app

from my_agent.agent.definition import build_agent


AGENT_ID = "my-agent"


async def init_agent() -> None:
    agent = build_agent()
    card = agent.card
    Runner.resource_mgr.add_agent(card, lambda: agent)


handler = AgentCoreHandler(AGENT_ID, Runner)
app = create_rest_app(
    handler,
    channel=channel,
    session_store=session_store,
    task_store=task_store,
)
```

实际工程必须补齐配置加载、Task/Session store、显式 channel、lifespan 和 readiness；上面只展示依赖方向。`AgentCoreHandler` 是 runtime 的边界适配器，不是业务 Agent 的基类。

### 5. 四层验证顺序

1. **语义层**：不启动 HTTP，验证 AgentCard、模型配置、Tool/Rail/Workflow 和 AgentCore 原生 `invoke`/`stream`。
2. **接线层**：验证 Runner 注册、Handler 五方法、Agent/Workflow 入口选择和 AgentCore 输出转换。
3. **协议层**：验证 REST/SSE 或 A2A 的原始 wire、终答、错误、Task 状态和结束语义。
4. **部署层**：真实进程、真实 socket、取消/断连/恢复、共享状态和可选模型网关。

Fixture 只能替代第 2、3 层中的确定性部分；它不能证明真实 agent-core 模型、Tool、Rail、Workspace 或多副本行为。

### 6. 哪些情况可以直接实现 Handler

| 情况 | 是否可直接实现 Handler | 要求 |
|---|---|---|
| 开发 ReAct/Workflow/DeepAgent | 不推荐 | 先实现 agent-core，再用 AgentCoreHandler |
| 接入 AgentScope 等非 OpenJiuwen 引擎 | 可以 | 在 outbound adapter 中归一事件和生命周期 |
| 接入远端 A2A/Versatile Agent | 可以 | 使用 remote adapter，不复制本地 AgentCore 逻辑 |
| runtime 自身契约验收 | 可以 | 使用 fixture，报告中写明替身边界 |
| 开发新的 REST/A2A 协议 | 不能绕过 Handler | 复用 `ServeOrchestrator` 和公开组合根 |

## 配置项参考

本页是选型与分层指南，不引入独立配置项。工程实际用到的配置面：宿主旋钮见各工程的 `deploy/.env.example`，runtime 配置树见 [配置驱动装配](config-driven-agent.md)。

## 坑位与排错

- 把 Agent prompt、Tool 和 Workflow 写进 REST router。
- 在 Handler 中重新实现 ReAct 循环或 DeepAgent TaskLoop。
- 用 `dict` 伪造 AgentCore `OutputSchema`，然后把它当成真实贯通证据。
- 在 Agent 定义中导入 FastAPI、A2A SDK 或 runtime 内部模块。
- 直接调用 AgentCore `run()` 后拼 SSE/A2A JSON。
- 只看到 `openjiuwen` 能导入，就宣称模型、Tool、Runner 和生产服务全部可用。

## 端到端校验

选型与分层是否落地，用两条判据检查：

```bash
# 1. 分层红线：agent/ 不得依赖 runtime 与 Web 框架
(cd docs/examples/react && PYTHONPATH=src:$RUNTIME_ROOT \
  python -m pytest -q tests/test_assembly.py::test_semantic_layer_does_not_depend_on_runtime)
# 2. 四层验证的第 2、3 层：装配与协议形态
(cd docs/examples/react && PYTHONPATH=src:$RUNTIME_ROOT python -m pytest -q tests)
```

第 1 层（语义层真实模型）与第 4 层（部署层）需要凭据与真实部署，按各类型指南的端到端校验执行。

## API 锚点（包内符号，按依赖可查）

- `openjiuwen.core.single_agent.ReActAgent` / `ReActAgentConfig` / `AgentCard`
- `openjiuwen.core.runner.Runner.resource_mgr.add_agent`
- `agent_runtime.adapters.outbound.agentcore.handler.AgentCoreHandler`
- `agent_runtime.bootstrap.rest_app.create_rest_app` / `a2a_app.create_a2a_app`

## See also

- [agent-core Python 接口](../api/agent-core-python.md)
- [ReAct Agent 指南](react-agent.md)
- [项目规范](../conventions/project-conventions.md)
- [构建 Python 运行环境](build-environment.md)
- [Agent 源码用例](../examples/overview.md)
