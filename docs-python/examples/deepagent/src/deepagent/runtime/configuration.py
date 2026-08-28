# coding: utf-8
"""Runtime 程序级服务层：读取宿主配置、登记运行资源、托管 DeepAgent。

三件事：读宿主配置、把 DeepAgent 登记进运行资源、装配出 Handler。
`AgentCoreHandler` 持有「标识 + 执行器」，因此实例要先登记。

**DeepAgent 持有工作区资源**：由 runtime 生命周期的停机阶段触发 Handler 的 `stop`；
宿主另有清理需求时，在组合根的关闭钩子里显式释放。
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

DEFAULT_PORT = 18092

RESOURCES = Path(__file__).resolve().parents[3] / "resources"

#: 默认工作区。
DEFAULT_WORKSPACE = "./data/deep-workspace"

#: 进程内首次登记的产物。见 `register_resources` 的幂等说明。
_REGISTERED: dict[str, "definition.DefinedDeepAgent"] = {}


def _host_section(path: Path) -> dict[str, Any]:
    """读 `application.yml` 的宿主命名空间。文件缺失即视为全部取默认值。"""
    if not path.is_file():
        return {}
    loaded = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    section = loaded.get("runtime")
    return section if isinstance(section, dict) else {}


def _require_ok(result: Any, what: str) -> None:
    """登记结果必须检查。

    **重复标识不会抛异常**：运行资源返回 `Error`、只在框架日志里记一条，
    而**先登记的实例继续服务**，本次构造的实例被静默丢弃（实测于 `openjiuwen==0.1.16`）。
    不看返回值就会出现「改了配置重新装配、跑的还是旧实例」这种查不出来的偏差。
    """
    if isinstance(result, Error):
        raise RuntimeError(
            f"{what}登记失败：标识已被占用，先登记的实例会继续服务，本次构造的实例不会生效。"
            "同一进程只装配一次；重复装配请复用 register_resources 的返回值。"
        )


@dataclass(frozen=True)
class HostConfig:
    """宿主运行配置（只读）。字段与 `deploy/.env.example` 的旋钮逐项对应。"""

    host: str = "127.0.0.1"
    port: int = DEFAULT_PORT
    model_name: str = "gpt-4o-mini"
    model_provider: str = "openai"
    api_base: str = ""
    api_key: str = ""
    workspace_path: str = DEFAULT_WORKSPACE
    #: 模型端点是否校验证书。开启时 agent-core 要求同时给出证书路径。
    verify_ssl: bool = True
    ssl_cert: str = ""

    @classmethod
    def load(
        cls, env: dict[str, str] | None = None, *, resources: Path | None = None
    ) -> "HostConfig":
        """取值顺序：默认值 < `resources/application.yml` 的 `runtime:` 段 < 环境变量。"""
        env = dict(env or os.environ)
        section = _host_section((resources or RESOURCES) / "application.yml")
        server = section.get("server") or {}
        model = section.get("model") or {}
        workspace = section.get("workspace") or {}
        verify = (env.get("LLM_VERIFY_SSL") or "true").strip().lower()
        return cls(
            host=env.get("RUNTIME_HOST") or server.get("host") or "127.0.0.1",
            port=int(env.get("RUNTIME_PORT") or server.get("port") or DEFAULT_PORT),
            model_name=env.get("LLM_MODEL") or model.get("name") or "gpt-4o-mini",
            model_provider=env.get("LLM_PROVIDER") or model.get("provider") or "openai",
            # 凭据只从环境变量来：YAML 是可提交文件，不承载密钥。
            api_base=env.get("LLM_API_BASE") or "",
            api_key=env.get("LLM_API_KEY") or "",
            workspace_path=env.get("DEEP_WORKSPACE") or workspace.get("path") or DEFAULT_WORKSPACE,
            verify_ssl=verify not in ("false", "0", "no"),
            ssl_cert=env.get("LLM_SSL_CERT") or "",
        )


def load_runtime_config(path: Path | None = None) -> RuntimeConfig:
    path = path or (RESOURCES / "application.yml")
    return ConfigLoader().load(
        RuntimeConfig, sources=(ConfigSource(SourceKind.FILE, str(path)),)
    )


def _require_model_endpoint(config: HostConfig) -> None:
    """前置校验：把 agent-core 的构造期校验翻译成「去哪补配置」。

    三条构造期硬约束（`openjiuwen/core/foundation/llm/` 的 `ModelClientConfig`
    与 `base_model_client.py` 的 `_validate_config`）：
    provider 为 openai 时 `api_key`、`api_base` 必填；`verify_ssl` 为真时 `ssl_cert` 必填。
    """
    missing = [
        name
        for name, value in (("LLM_API_KEY", config.api_key), ("LLM_API_BASE", config.api_base))
        if not value
    ]
    if config.verify_ssl and not config.ssl_cert:
        missing.append("LLM_SSL_CERT（或把 LLM_VERIFY_SSL 显式置为 false）")
    if missing:
        raise ValueError(
            f"缺少模型端点配置 {missing}；请复制 deploy/.env.example 为 .env 并填写后显式装载。"
            "DeepAgent 在构造期即校验该配置，不是到调用时才失败。"
        )


def register_resources(config: HostConfig | None = None) -> definition.DefinedDeepAgent:
    """构造 DeepAgent 并登记为运行资源。

    幂等由本层保证：同一进程内重复调用返回首次登记的产物。
    """
    config = config or HostConfig.load()
    _require_model_endpoint(config)
    cached = _REGISTERED.get(definition.AGENT_ID)
    if cached is not None:
        logger.debug("Agent %s 已登记，复用首次登记的产物", definition.AGENT_ID)
        return cached
    defined = definition.create(
        api_key=config.api_key,
        api_base=config.api_base,
        model_name=config.model_name,
        workspace_path=config.workspace_path,
        model_provider=config.model_provider,
        verify_ssl=config.verify_ssl,
        ssl_cert=config.ssl_cert,
    )
    # provider 是**零参可调用**：运行资源按 `resource_provider()` 取实例。
    _require_ok(Runner.resource_mgr.add_agent(defined.card, lambda: defined.agent), "Agent")
    _REGISTERED[definition.AGENT_ID] = defined
    return defined


def build_handler(config: HostConfig | None = None) -> Any:
    """装配 AgentHandler：runtime 与语义层之间的唯一接入点。"""
    defined = register_resources(config)
    return AgentCoreHandler(defined.agent_id, Runner)
