# coding: utf-8
"""装配门禁：不需要真实模型凭据，全程不出网。

覆盖：分层红线、工作区越界防护、DeepAgent 可构造、运行资源登记与 Handler 装配、
A2A 服务可装配、缺配置时的失败语义。真实任务循环见 `docs/how-to/deepagent.md`。
"""
from __future__ import annotations

import ast
from pathlib import Path

import pytest

from deepagent.agent import definition
from deepagent.agent.workspace_tools import TOOL_ID, create_list_artifacts_tool

AGENT_DIR = Path(definition.__file__).resolve().parent
FORBIDDEN_TOP_LEVEL = {"agent_runtime", "fastapi", "starlette", "uvicorn", "a2a"}

#: 装配门禁用的占位端点。DeepAgent 构造期即校验模型客户端配置，
#: 但不出网——真实调用发生在任务循环执行时。
PLACEHOLDER_KEY = "placeholder-key"
PLACEHOLDER_BASE = "https://models.invalid/v1"


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


def test_workspace_tool_lists_only_markdown(tmp_path: Path) -> None:
    (tmp_path / "plan.md").write_text("# plan", encoding="utf-8")
    (tmp_path / "notes.txt").write_text("x", encoding="utf-8")
    tool = create_list_artifacts_tool(tmp_path)
    assert tool.card.id == TOOL_ID
    assert tool._func(subdir=".") == {"artifacts": ["plan.md"]}


def test_workspace_tool_rejects_escape(tmp_path: Path) -> None:
    """越界防护：业务工具自己挡 `..`，不依赖框架的 restrict_to_work_dir。"""
    tool = create_list_artifacts_tool(tmp_path / "ws")
    with pytest.raises(ValueError):
        tool._func(subdir="../../etc")


def test_deep_agent_builds_with_placeholder_endpoint(tmp_path: Path) -> None:
    defined = definition.create(
        api_key=PLACEHOLDER_KEY,
        api_base=PLACEHOLDER_BASE,
        model_name="test-model",
        workspace_path=tmp_path / "ws",
        verify_ssl=False,
    )
    assert defined.agent_id == definition.AGENT_ID
    assert defined.workspace_root.is_dir()
    # 坑位：`create_deep_agent` 会把工具标识改写成 `<tool_id>_<agent_id>` 做 agent 级作用域，
    # 因此构造后不能再按原标识断言相等（`openjiuwen/harness/factory.py` 的 `_normalize_tools`）。
    assert defined.tool.card.id.startswith(TOOL_ID)
    assert defined.tool.card.id.endswith(definition.AGENT_ID)


def test_verify_ssl_without_cert_is_rejected_with_actionable_error(tmp_path: Path) -> None:
    """坑位：verify_ssl 为真时 agent-core 要求证书路径，服务层先给出可操作提示。"""
    from deepagent.runtime.configuration import HostConfig, register_resources

    config = HostConfig(
        api_key=PLACEHOLDER_KEY,
        api_base=PLACEHOLDER_BASE,
        model_name="test-model",
        workspace_path=str(tmp_path / "ws"),
        verify_ssl=True,
        ssl_cert="",
    )
    with pytest.raises(ValueError) as excinfo:
        register_resources(config)
    assert "LLM_SSL_CERT" in str(excinfo.value)


def test_runtime_registers_agent_and_builds_handler(tmp_path: Path) -> None:
    import asyncio

    from openjiuwen.core.runner import Runner

    from deepagent.runtime.configuration import HostConfig, build_handler

    handler = build_handler(
        HostConfig(
            api_key=PLACEHOLDER_KEY,
            api_base=PLACEHOLDER_BASE,
            model_name="test-model",
            workspace_path=str(tmp_path / "ws"),
            verify_ssl=False,
        )
    )
    assert handler.agent_id == definition.AGENT_ID

    async def _resolve():
        return await Runner.resource_mgr.get_agent(definition.AGENT_ID), await handler._is_workflow()

    agent, is_workflow = asyncio.run(_resolve())
    assert agent is not None
    assert is_workflow is False


def test_a2a_app_assembles_with_card_skills(tmp_path: Path) -> None:
    pytest.importorskip("fastapi")
    pytest.importorskip("a2a")
    from fastapi.testclient import TestClient

    from deepagent.runtime.application import build_app
    from deepagent.runtime.configuration import HostConfig

    app = build_app(
        HostConfig(
            api_key=PLACEHOLDER_KEY,
            api_base=PLACEHOLDER_BASE,
            model_name="test-model",
            workspace_path=str(tmp_path / "ws"),
            verify_ssl=False,
        )
    )
    with TestClient(app) as client:
        body = client.get("/.well-known/agent-card.json").json()
    assert body["name"] == definition.AGENT_ID
    assert [skill["id"] for skill in body.get("skills", [])] == ["maintain_artifacts"]
