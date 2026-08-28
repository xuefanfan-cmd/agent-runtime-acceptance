# coding: utf-8
"""装配门禁：不需要模型凭据，全程不出网。

覆盖：分层红线、DAG 编排可构造、运行资源登记、Handler 走工作流执行入口、A2A 服务可装配。
真实模型对话见 `docs/how-to/workflow-agent.md` 的端到端校验。
"""
from __future__ import annotations

import ast
import asyncio
from pathlib import Path

import pytest

from workflow_agent.agent import definition
from workflow_agent.agent.check_tool import RISK_THRESHOLD, TOOL_ID, execute

AGENT_DIR = Path(definition.__file__).resolve().parent
FORBIDDEN_TOP_LEVEL = {"agent_runtime", "fastapi", "starlette", "uvicorn", "a2a"}


def _imported_top_levels(path: Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            names.add(node.module.split(".")[0])
    return names


def test_semantic_layer_does_not_depend_on_runtime() -> None:
    for path in sorted(AGENT_DIR.glob("*.py")):
        offending = _imported_top_levels(path) & FORBIDDEN_TOP_LEVEL
        assert not offending, f"{path.name} 越过分层红线，import 了 {sorted(offending)}"


def test_check_tool_risk_boundary() -> None:
    """阈值边界：等于阈值不算高风险，超过才走人工分支。"""
    assert execute(total=RISK_THRESHOLD) == {"risk": "none"}
    assert execute(total=RISK_THRESHOLD + 1) == {"risk": "high"}


#: 装配门禁用的占位凭据。构造期不出网，但 agent-core 的 `ModelClientConfig`
#: 会在**构造期**校验 provider 与 api_key 的搭配（`llm/schema/config.py` 的
#: `validate_client_provider`），空 key 直接抛校验错。这与 ReAct 工程不同——
#: 那里用的是 `ReActAgentConfig` 的扁平模型字段，不走这条校验。
PLACEHOLDER_KEY = "placeholder-key"
PLACEHOLDER_BASE = "https://models.invalid/v1"


def test_empty_model_endpoint_fails_fast_at_construction() -> None:
    """坑位：工作流的模型客户端配置在构造期就要求非空 api_key 与 api_base。"""
    from openjiuwen.core.common.exception.errors import ValidationError

    with pytest.raises(ValidationError):
        definition.create(api_key="", api_base="", model_name="test-model")


def test_service_layer_translates_missing_endpoint_into_actionable_error() -> None:
    """服务层把框架校验错翻译成「去哪补配置」。"""
    from workflow_agent.runtime.configuration import HostConfig, register_resources

    with pytest.raises(ValueError) as excinfo:
        register_resources(HostConfig(api_key="", api_base="", model_name="test-model"))
    assert "deploy/.env.example" in str(excinfo.value)


def test_dag_builds_with_placeholder_credentials() -> None:
    """DAG 编排在无真实凭据环境可构造；卡片标识与版本与配置一致。"""
    defined = definition.create(api_key=PLACEHOLDER_KEY, api_base=PLACEHOLDER_BASE, model_name="test-model")
    assert defined.workflow_id == definition.WORKFLOW_ID
    assert defined.card.version == definition.WORKFLOW_VERSION
    assert defined.tool.card.id == TOOL_ID


def test_runtime_registers_workflow_and_dispatches_as_workflow() -> None:
    """服务层职责：登记工作流资源，Handler 据此走工作流执行入口。"""
    from openjiuwen.core.runner import Runner

    from workflow_agent.runtime.configuration import HostConfig, build_handler, register_resources

    async def _go():
        register_resources(HostConfig(api_key=PLACEHOLDER_KEY, api_base=PLACEHOLDER_BASE, model_name="test-model"))
        handler = build_handler()
        flow = await Runner.resource_mgr.get_workflow(definition.WORKFLOW_ID)
        return handler, flow, await handler._is_workflow()

    handler, flow, is_workflow = asyncio.run(_go())
    assert handler.agent_id == definition.WORKFLOW_ID
    assert flow is not None
    # 与 ReAct 工程相反：本标识是工作流形态，执行期必须走工作流入口。
    assert is_workflow is True


def test_a2a_app_assembles_with_card_skills() -> None:
    fastapi = pytest.importorskip("fastapi")
    pytest.importorskip("a2a")
    from fastapi.testclient import TestClient

    from workflow_agent.runtime.application import build_app
    from workflow_agent.runtime.configuration import HostConfig

    app = build_app(HostConfig(api_key=PLACEHOLDER_KEY, api_base=PLACEHOLDER_BASE, model_name="test-model"))
    with TestClient(app) as client:
        body = client.get("/.well-known/agent-card.json").json()
    assert body["name"] == definition.WORKFLOW_ID
    assert [skill["id"] for skill in body.get("skills", [])] == ["run_pipeline"]
