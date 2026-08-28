# coding: utf-8
"""Core 语义层定义：DAG 编排。

最小完整闭环：LLM 处理 → 工具校验 → 分支 → 人工（HITL）/ 自动两个收尾。

**托管形态的选择**：`openjiuwen==0.1.16` 提供 `WorkflowAgent`
（`openjiuwen.core.application.workflow_agent.workflow_agent`）可以包住若干 Workflow，
但它的配置类 `WorkflowAgentConfig` 落在 `openjiuwen.core.single_agent.legacy.config`，
构造时框架主动告警「AgentConfig is deprecated and will be removed in the future」。

本工程因此把 Workflow 注册成**工作流资源**，runtime 的 `AgentCoreHandler` 探到该标识是
工作流后走工作流执行入口（`agent_runtime/adapters/outbound/agentcore/handler.py` 的
`_is_workflow`），不依赖已废弃的配置类。DAG 的编排写法不受影响。

本层只依赖 `openjiuwen` agent-core，不依赖 runtime 与 Web 框架。
"""
from __future__ import annotations

from dataclasses import dataclass

from openjiuwen.core.foundation.llm import ModelClientConfig, ModelRequestConfig
from openjiuwen.core.foundation.llm.schema.message import SystemMessage, UserMessage
from openjiuwen.core.foundation.tool import LocalFunction
from openjiuwen.core.workflow import (
    BranchComponent,
    End,
    LLMCompConfig,
    LLMComponent,
    QuestionerComponent,
    QuestionerConfig,
    Start,
    ToolComponent,
    ToolComponentConfig,
    Workflow,
    WorkflowCard,
)

from .check_tool import create_check_tool

#: 工作流标识。运行资源登记名与 Handler 构造参数共用同一个值。
WORKFLOW_ID = "pipeline"
WORKFLOW_VERSION = "1.0"


@dataclass(frozen=True)
class DefinedPipeline:
    """语义层产物：runtime 层据此注册工具执行体与工作流资源。"""

    workflow: Workflow
    tool: LocalFunction
    card: WorkflowCard
    workflow_id: str


def create(
    api_key: str,
    api_base: str,
    model_name: str,
    *,
    model_provider: str = "openai",
) -> DefinedPipeline:
    """编排 DAG。构造期不出网：模型调用发生在工作流执行时。"""
    client_config = ModelClientConfig(
        client_provider=model_provider, api_key=api_key, api_base=api_base
    )
    request_config = ModelRequestConfig(model=model_name, temperature=0.0, max_tokens=1024)

    tool = create_check_tool()

    card = WorkflowCard(
        id=WORKFLOW_ID,
        name="示例流水线",
        version=WORKFLOW_VERSION,
        description="LLM 处理 -> 工具校验 -> 分支 -> 人工/自动收尾",
    )
    flow = Workflow(card=card)

    # 1) Start：把入参 query 引入图内（右值 ${query} 引用顶层入参）
    flow.set_start_comp("start", Start(), inputs_schema={"query": "${query}"})

    # 2) LLM 节点：结构化 JSON 输出（{{query}} 是本组件的局部输入键）
    transform = LLMComponent(
        LLMCompConfig(
            model_client_config=client_config,
            model_config=request_config,
            system_prompt_template=SystemMessage(content="抽取输入中的数值并以 JSON 返回。"),
            user_prompt_template=UserMessage(content="处理：{{query}}"),
            response_format={"type": "json"},
            output_config={
                "total": {"type": "number", "description": "合计"},
                "summary": {"type": "string", "description": "摘要"},
            },
        )
    )
    # ${} 是图引擎的跨节点引用
    flow.add_workflow_comp("transform", transform, inputs_schema={"query": "${start.query}"})

    # 3) 工具节点：直接绑定本地工具实例。
    #    **不能只给 tool_id**：`ToolComponent.__init__` 会当场向运行资源要实例，
    #    工具尚未注册时构造即失败（`tool_comp.py` 的 `ToolComponent.to_executable`
    #    抛「tool component not bind a valid tool」）。绑实例把顺序耦合去掉。
    flow.add_workflow_comp(
        "check",
        ToolComponent(ToolComponentConfig()).bind_tool(tool),
        inputs_schema={"total": "${transform.total}"},
    )

    # 4) 分支：risk=high 走人工，否则自动收尾；兜底分支必须存在
    branch = BranchComponent()
    branch.add_branch('${check.data.risk} == "high"', "confirm", "high")
    branch.add_branch("true", "finish", "normal")
    flow.add_workflow_comp("route", branch, inputs_schema={"risk": "${check.data.risk}"})

    # 5a) 人工审批节点（HITL）：执行到这里挂起并抛出中断，A2A 侧表现为 input-required
    confirm = QuestionerComponent(
        QuestionerConfig(
            model_client_config=client_config,
            model_config=request_config,
            response_type="reply_directly",
            extract_fields_from_response=False,
            question_content="风险超阈值，请输入 'approved' 通过，或说明拒绝理由。",
        )
    )
    flow.add_workflow_comp("confirm", confirm, inputs_schema={"summary": "${transform.summary}"})

    # 5b) 自动收尾节点（文本输出）
    finish = LLMComponent(
        LLMCompConfig(
            model_client_config=client_config,
            model_config=request_config,
            system_prompt_template=SystemMessage(content="生成通过报告。"),
            user_prompt_template=UserMessage(content="依据：{{summary}}"),
            response_format={"type": "text"},
            output_config={"text": {"type": "string", "description": "报告"}},
        )
    )
    flow.add_workflow_comp("finish", finish, inputs_schema={"summary": "${transform.summary}"})

    # 6) End：收集两分支各自的结果字段（未走到的分支为空）
    flow.set_end_comp(
        "end",
        End(),
        inputs_schema={
            "manual_result": "${confirm.user_response}",
            "auto_result": "${finish.text}",
        },
    )

    # 7) 连线（分支到目标的边由 BranchComponent 自路由，不用 add_connection）
    flow.add_connection("start", "transform")
    flow.add_connection("transform", "check")
    flow.add_connection("check", "route")
    flow.add_connection("confirm", "end")
    flow.add_connection("finish", "end")

    return DefinedPipeline(workflow=flow, tool=tool, card=card, workflow_id=WORKFLOW_ID)
