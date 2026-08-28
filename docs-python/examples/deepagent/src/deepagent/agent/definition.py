# coding: utf-8
"""Core 语义层定义：DeepAgent 目标导向任务循环。

`TaskCompletionRail` 驱动任务循环 + 受限工作区文件工具 + 官方工厂装配。

三个构造件：`TaskCompletionRail` 定完成判定，`Model` 定模型端点与采样参数，
`create_deep_agent` 一次完成工作区初始化、默认 Rail 注入与工具注册。文件能力由
`workspace` + `restrict_to_work_dir` 挂上框架内建工具，本模块只补一个清单工具。

本层只依赖 `openjiuwen` agent-core，不依赖 runtime 与 Web 框架。
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from openjiuwen.core.foundation.llm import Model, ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.tool import LocalFunction
from openjiuwen.core.single_agent import AgentCard
from openjiuwen.harness import DeepAgent, create_deep_agent
from openjiuwen.harness.rails import TaskCompletionRail

from .workspace_tools import create_list_artifacts_tool

AGENT_ID = "notes-deep"

SYSTEM_PROMPT = (
    "你是交付物维护助手。只维护工作区内的 Markdown 文件，"
    "写入完整内容后复读检查，确认一致再发出完成信号。"
)

#: 任务指令模板。`{query}` 会被当前请求替换。
TASK_INSTRUCTION = "持续维护工作区交付物。根据当前请求创建或更新文件；当前请求如下：\n{query}"

#: 完成信号。任务循环见到它才判定交付物就绪。
COMPLETION_PROMISE = "ARTIFACTS_READY"


@dataclass(frozen=True)
class DefinedDeepAgent:
    """语义层产物：runtime 层据此托管 Agent。"""

    agent: DeepAgent
    tool: LocalFunction
    card: AgentCard
    agent_id: str
    workspace_root: Path


def create(
    api_key: str,
    api_base: str,
    model_name: str,
    workspace_path: str | Path,
    *,
    model_provider: str = "openai",
    verify_ssl: bool = True,
    ssl_cert: str = "",
    max_iterations: int = 8,
    completion_timeout_s: float = 300.0,
) -> DefinedDeepAgent:
    """构造 DeepAgent。构造期不出网，但会校验模型客户端配置。"""
    root = Path(workspace_path).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)

    model = Model(
        model_client_config=ModelClientConfig(
            client_provider=model_provider,
            api_key=api_key,
            api_base=api_base,
            verify_ssl=verify_ssl,
            ssl_cert=ssl_cert,
        ),
        model_config=ModelRequestConfig(model=model_name, temperature=0.1, top_p=0.8),
    )

    # 1) 完成判定 Rail：交付物就绪前持续任务循环
    completion_rail = TaskCompletionRail(
        task_instruction=TASK_INSTRUCTION,
        completion_promise=COMPLETION_PROMISE,
        required_confirmations=1,
        allow_promise_details=False,
        max_rounds=3,
        timeout_seconds=completion_timeout_s,
    )

    # 2) 业务工具：与框架内建的工作区文件工具共存
    tool = create_list_artifacts_tool(root)

    card = AgentCard(
        id=AGENT_ID, name=AGENT_ID, description="任务循环 + 受限工作区文件工具"
    )

    # 3) 官方工厂装配（工作区初始化、默认 Rail 注入、工具注册一次完成）
    agent = create_deep_agent(
        model,
        card=card,
        system_prompt=SYSTEM_PROMPT,
        tools=[tool],
        rails=[completion_rail],
        enable_task_loop=True,
        max_iterations=max_iterations,
        workspace=str(root),
        restrict_to_work_dir=True,
        language="cn",
    )

    # 工厂会把工具标识改写成 `<tool_id>_<agent_id>` 做 agent 级作用域，
    # 返回的工具实例携带的是改写后的标识——按原标识查找会落空。
    return DefinedDeepAgent(
        agent=agent, tool=tool, card=card, agent_id=AGENT_ID, workspace_root=root
    )
