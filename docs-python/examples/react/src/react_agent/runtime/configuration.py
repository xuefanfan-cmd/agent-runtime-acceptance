# coding: utf-8
"""Runtime 程序级服务层：读取宿主配置、注册运行资源、把 core Agent 托管为服务。

三件事：读宿主配置、把工具执行体与 Agent 实例登记进运行资源、装配出 Handler。

**为什么要先 `add_agent` 再构造 Handler**：`AgentCoreHandler` 持有的是「标识 + 执行器」
（`agent_runtime/adapters/outbound/agentcore/handler.py` 的 `AgentCoreHandler.__init__`），
执行期按标识向运行资源要实例，所以实例必须先登记。

**配置分两个命名空间**：`runtime:` 段是宿主自己的旋钮，由本模块读 YAML 再让环境变量
覆盖；`openjiuwen.service:` 才是 runtime 配置树，由 runtime 的 `ConfigLoader` 绑定，
且**不做 `${VAR}` 占位符插值**——写了只会得到字面量字符串。
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml
from agent_runtime.adapters.outbound.agentcore.handler import AgentCoreHandler
from agent_runtime.bootstrap.config.loader import ConfigLoader, ConfigSource, SourceKind
from agent_runtime.bootstrap.config.runtime_config import RuntimeConfig
from openjiuwen.core.runner import Runner
from openjiuwen.core.runner.resources_manager.base import Error

from ..agent import definition

logger = logging.getLogger(__name__)

#: 本宿主默认端口。`resources/application.yml` 的 `runtime.server.port` 覆盖它，环境变量再覆盖。
DEFAULT_PORT = 18091

#: 资源目录：`application.yml` 的位置，相对本工程根。
RESOURCES = Path(__file__).resolve().parents[3] / "resources"

#: 进程内首次登记的产物。见 `register_resources` 的幂等说明。
_REGISTERED: dict[str, "definition.DefinedReactAgent"] = {}


def _host_section(path: Path) -> dict[str, Any]:
    """读 `application.yml` 的宿主命名空间。文件缺失即视为全部取默认值。"""
    if not path.is_file():
        return {}
    loaded = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    section = loaded.get("runtime")
    return section if isinstance(section, dict) else {}


@dataclass(frozen=True)
class HostConfig:
    """宿主运行配置（只读）。

    取值顺序：默认值 < `resources/application.yml` 的 `runtime:` 段 < 环境变量。
    """

    host: str = "127.0.0.1"
    port: int = DEFAULT_PORT
    #: 模型后端。缺凭据时仍可完成装配，只有真实对话才需要它们。
    model_name: str = "gpt-4o-mini"
    model_provider: str = "openai"
    api_base: str = ""
    api_key: str = ""

    @classmethod
    def load(
        cls, env: dict[str, str] | None = None, *, resources: Path | None = None
    ) -> "HostConfig":
        env = dict(env or os.environ)
        section = _host_section((resources or RESOURCES) / "application.yml")
        server = section.get("server") or {}
        model = section.get("model") or {}
        return cls(
            host=env.get("RUNTIME_HOST") or server.get("host") or "127.0.0.1",
            port=int(env.get("RUNTIME_PORT") or server.get("port") or DEFAULT_PORT),
            model_name=env.get("LLM_MODEL") or model.get("name") or "gpt-4o-mini",
            model_provider=env.get("LLM_PROVIDER") or model.get("provider") or "openai",
            # 凭据只从环境变量来：YAML 是可提交文件，不承载密钥。
            api_base=env.get("LLM_API_BASE") or "",
            api_key=env.get("LLM_API_KEY") or "",
        )


def load_runtime_config(path: Path | None = None) -> RuntimeConfig:
    """用 runtime 自己的 ConfigLoader 绑定 `openjiuwen.service` 配置树。

    **SDK 不自己去翻文件系统**：读哪份配置由宿主决定（`bootstrap/a2a_app.py`
    的 `create_a2a_app` 对 `config` 参数的说明），所以路径由本层显式给出。
    """
    path = path or (RESOURCES / "application.yml")
    return ConfigLoader().load(
        RuntimeConfig, sources=(ConfigSource(SourceKind.FILE, str(path)),)
    )


def _require_ok(result: Any, what: str) -> None:
    """登记结果必须检查。

    **重复标识不会抛异常**：运行资源返回 `Error`、只在框架日志里记一条，
    而**先登记的实例继续服务**，本次构造的实例被静默丢弃（实测于
    `openjiuwen==0.1.16`）。不看返回值就会出现「改了配置重新装配、跑的还是旧实例」
    这种查不出来的偏差，所以这里显式失败。
    """
    if isinstance(result, Error):
        raise RuntimeError(
            f"{what}登记失败：标识已被占用，先登记的实例会继续服务，本次构造的实例不会生效。"
            "同一进程只装配一次；重复装配请复用 register_resources 的返回值。"
        )


def register_resources(config: HostConfig | None = None) -> definition.DefinedReactAgent:
    """构造语义层产物并登记为程序级运行资源。

    **幂等由本层保证，不是框架幂等**：同一进程内重复调用返回首次登记的产物，
    与运行资源里实际生效的实例保持一致。
    """
    cached = _REGISTERED.get(definition.AGENT_ID)
    if cached is not None:
        logger.debug("Agent %s 已登记，复用首次登记的产物", definition.AGENT_ID)
        return cached

    config = config or HostConfig.load()
    defined = definition.create(
        api_key=config.api_key,
        api_base=config.api_base,
        model_name=config.model_name,
        model_provider=config.model_provider,
    )

    # 执行体属于程序级运行资源；ToolCard 元数据已在语义层加入 AbilityManager。
    # `skip_if_exists` 让无状态工具的重复登记成为幂等空操作。
    _require_ok(Runner.resource_mgr.add_tool(defined.tool, skip_if_exists=True), "工具")
    # provider 是**零参可调用**：运行资源按 `resource_provider()` 取实例。
    _require_ok(Runner.resource_mgr.add_agent(defined.card, lambda: defined.agent), "Agent")

    _REGISTERED[definition.AGENT_ID] = defined
    return defined


def build_handler(config: HostConfig | None = None) -> Any:
    """装配 AgentHandler：runtime 与语义层之间的唯一接入点。"""
    defined = register_resources(config)
    # 构造参数是位置形式的「标识 + 执行器」，不是关键字形式。
    return AgentCoreHandler(defined.agent_id, Runner)
