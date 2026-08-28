# coding: utf-8
"""装配门禁：不需要模型凭据，全程不出网。

覆盖类型与依赖闭包、装配与 web 栈就绪两件事：

1. 分层红线可执行化——`agent/` 不得 import runtime 与 Web 框架；
2. 语义层可构造——不出网即可完成 Agent 与工具的构造；
3. 运行资源登记——按标识能取回同一实例；
4. Handler 装配——`AgentCoreHandler` 按标识择取执行入口；
5. 服务装配——A2A 应用可建，卡片带上配置里的技能项。

真实模型对话不在本文件覆盖范围，见 `docs/how-to/react-agent.md` 的端到端校验。
"""
from __future__ import annotations

import ast
import asyncio
from pathlib import Path

import pytest

from react_agent.agent import definition
from react_agent.agent.text_stats_tool import TOOL_ID, execute

AGENT_DIR = Path(definition.__file__).resolve().parent

#: 语义层禁止依赖的顶层模块：runtime 本身与 Web/协议栈。
#: 见 `docs/conventions/project-conventions.md`：`agent/` 不得反向依赖 `runtime/`。
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
    """分层红线：agent/ 只依赖 agent-core 与标准库。"""
    for path in sorted(AGENT_DIR.glob("*.py")):
        offending = _imported_top_levels(path) & FORBIDDEN_TOP_LEVEL
        assert not offending, f"{path.name} 越过分层红线，import 了 {sorted(offending)}"


def test_tool_executes_without_model() -> None:
    """工具执行体是纯函数：形参名与 input_params 属性名一致。"""
    assert execute(text="hello world\nsecond line") == {
        "chars": 23,
        "words": 4,
        "lines": 2,
    }
    assert execute(text="") == {"chars": 0, "words": 0, "lines": 0}


def test_definition_builds_without_credentials() -> None:
    """构造期不出网：没有凭据也能完成语义层装配。"""
    defined = definition.create(api_key="", api_base="", model_name="test-model")
    assert defined.agent_id == definition.AGENT_ID
    assert defined.card.id == definition.AGENT_ID
    assert defined.tool.card.id == TOOL_ID
    # 语义层已声明能力：ToolCard 进了 AbilityManager。
    assert TOOL_ID in str(defined.agent.ability_manager.list())


def test_runtime_registers_resources_and_builds_handler() -> None:
    """服务层职责：登记运行资源，并按标识装配 Handler。"""
    from openjiuwen.core.runner import Runner

    from react_agent.runtime.configuration import HostConfig, build_handler

    handler = build_handler(HostConfig(api_key="", api_base="", model_name="test-model"))
    assert handler.agent_id == definition.AGENT_ID
    assert handler.is_healthy() is True

    async def _resolve():
        agent = await Runner.resource_mgr.get_agent(definition.AGENT_ID)
        # 本标识不是工作流形态，执行期应走通用智能体入口。
        return agent, await handler._is_workflow()

    agent, is_workflow = asyncio.run(_resolve())
    assert agent is not None
    assert is_workflow is False


def test_a2a_app_assembles_with_card_skills() -> None:
    """服务装配：A2A 应用可建，且卡片带上配置里的技能项。"""
    fastapi = pytest.importorskip("fastapi")
    pytest.importorskip("a2a")

    from react_agent.runtime.application import build_app
    from react_agent.runtime.configuration import HostConfig

    app = build_app(HostConfig(api_key="", api_base="", model_name="test-model"))
    assert isinstance(app, fastapi.FastAPI)

    client = fastapi.testclient.TestClient(app) if hasattr(fastapi, "testclient") else None
    if client is None:
        from fastapi.testclient import TestClient

        client = TestClient(app)
    card = client.get("/.well-known/agent-card.json")
    assert card.status_code == 200
    body = card.json()
    assert body["name"] == definition.AGENT_ID
    assert [skill["id"] for skill in body.get("skills", [])] == ["analyze_text"]


def test_host_config_precedence_yaml_then_env() -> None:
    """配置取值顺序：默认值 < resources/application.yml 的 runtime 段 < 环境变量。"""
    from react_agent.runtime.configuration import HostConfig

    from_yaml = HostConfig.load(env={})
    assert from_yaml.port == 18091
    assert from_yaml.model_name == "gpt-4o-mini"

    overridden = HostConfig.load(env={"RUNTIME_PORT": "19099", "LLM_MODEL": "other-model"})
    assert overridden.port == 19099
    assert overridden.model_name == "other-model"
    # 凭据只从环境变量来：YAML 是可提交文件，不承载密钥。
    assert HostConfig.load(env={"LLM_API_KEY": "k"}).api_key == "k"


def test_registration_is_idempotent_and_duplicate_add_is_dropped_silently() -> None:
    """幂等由服务层保证；框架对重复标识返回 Error 且保留先登记的实例。"""
    from openjiuwen.core.runner import Runner
    from openjiuwen.core.runner.resources_manager.base import Error

    from react_agent.runtime.configuration import HostConfig, register_resources

    config = HostConfig(api_key="", api_base="", model_name="test-model")
    first = register_resources(config)
    assert register_resources(config) is first

    # 直接对运行资源重复登记：返回 Error，且先登记的实例继续生效——
    # 这正是服务层必须检查返回值、并自己保证幂等的原因。
    other = definition.create(api_key="", api_base="", model_name="other-model")
    result = Runner.resource_mgr.add_agent(other.card, lambda: other.agent)
    assert isinstance(result, Error)

    async def _resolve():
        return await Runner.resource_mgr.get_agent(definition.AGENT_ID)

    assert asyncio.run(_resolve()) is first.agent

