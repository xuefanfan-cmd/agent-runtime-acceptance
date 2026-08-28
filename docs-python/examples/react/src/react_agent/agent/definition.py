# coding: utf-8
"""Core 语义层定义：只负责 AgentCard、prompt、模型参数与工具元数据。

两条边界不可越：

1. **本层不依赖 runtime 与 Web 框架**：只 import `openjiuwen`（见
   `docs/conventions/project-conventions.md` 的分层红线）。
2. **元数据在语义层，执行体在服务层**：`ability_manager.add(tool.card)` 是语义层的
   能力声明；`Runner.resource_mgr.add_tool(tool)` 是程序级运行资源注册，放在
   `runtime/configuration.py`。

版本口径：`openjiuwen==0.1.16`。`ReActAgentConfig` 在该版本同时接受扁平模型字段
（`model_name` / `model_provider` / `api_key` / `api_base`）与 `model_config_obj`
采样参数对象，本文件用前者声明后端、用后者声明采样参数。
"""
from __future__ import annotations

from dataclasses import dataclass

from openjiuwen.core.foundation.llm import ModelRequestConfig
from openjiuwen.core.foundation.tool import LocalFunction
from openjiuwen.core.single_agent import AgentCard, ReActAgent, ReActAgentConfig

from .text_stats_tool import create_text_stats_tool

#: Agent 标识。A2A 卡片名、运行资源登记名与 Handler 构造参数三处共用同一个值。
AGENT_ID = "notes-react"

SYSTEM_PROMPT = "你是文本分析助手。需要统计时调用 text_stats 工具，不要自己估算数字。"


@dataclass(frozen=True)
class DefinedReactAgent:
    """语义层产物：runtime 层据此注册工具执行体并托管 Agent。

    """

    agent: ReActAgent
    tool: LocalFunction
    card: AgentCard
    agent_id: str


def create(
    api_key: str,
    api_base: str,
    model_name: str,
    *,
    model_provider: str = "openai",
    max_iterations: int = 6,
    temperature: float = 0.1,
    top_p: float = 0.8,
) -> DefinedReactAgent:
    """构造 ReAct Agent 与它的本地工具。

    **构造期不出网**：`configure` 只写配置，模型调用发生在 `invoke` / `stream`。
    因此本函数可以在没有模型凭据的环境里执行，用于装配门禁。
    """
    config = ReActAgentConfig(
        model_name=model_name,
        model_provider=model_provider,
        api_key=api_key,
        api_base=api_base,
        prompt_template=[{"role": "system", "content": SYSTEM_PROMPT}],
        max_iterations=max_iterations,
        model_config_obj=ModelRequestConfig(
            model=model_name, temperature=temperature, top_p=top_p
        ),
    )

    card = AgentCard(
        id=AGENT_ID,
        name=AGENT_ID,
        description="ReAct 推理循环 + 本地工具调用",
    )
    agent = ReActAgent(card=card).configure(config)

    tool = create_text_stats_tool()
    # 语义层只声明能力：ToolCard 进 AbilityManager，执行体由服务层注册。
    agent.ability_manager.add(tool.card)

    return DefinedReactAgent(agent=agent, tool=tool, card=card, agent_id=AGENT_ID)
