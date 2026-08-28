# coding: utf-8
"""Runtime 程序级服务层：读取宿主配置、注册运行资源、把工作流托管为服务。

三件事：读宿主配置、把工作流登记进运行资源、装配出 Handler。

**一处顺序约束**：工作流注册必须发生在事件循环内——编译过程使用异步原语，
模块导入期注册会失败（同样的坑记在当前 runtime 的参考宿主 `deploy/host_app.py` 的
`_register_workflow` 注释里）。因此本模块把注册暴露成协程，由组合根挂进 `init_hooks`，
不在模块顶层执行。
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

DEFAULT_PORT = 18090

RESOURCES = Path(__file__).resolve().parents[3] / "resources"

#: 进程内首次登记的产物。见 `register_resources` 的幂等说明。
_REGISTERED: dict[str, "definition.DefinedPipeline"] = {}


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

    @classmethod
    def load(
        cls, env: dict[str, str] | None = None, *, resources: Path | None = None
    ) -> "HostConfig":
        """取值顺序：默认值 < `resources/application.yml` 的 `runtime:` 段 < 环境变量。"""
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
    path = path or (RESOURCES / "application.yml")
    return ConfigLoader().load(
        RuntimeConfig, sources=(ConfigSource(SourceKind.FILE, str(path)),)
    )


def register_resources(config: HostConfig | None = None) -> definition.DefinedPipeline:
    """构造 DAG 并登记运行资源。**必须在事件循环内调用**。

    幂等由本层保证：同一进程内重复调用返回首次登记的产物。
    """
    config = config or HostConfig.load()
    _require_model_endpoint(config)
    cached = _REGISTERED.get(definition.WORKFLOW_ID)
    if cached is not None:
        logger.debug("工作流 %s 已登记，复用首次登记的产物", definition.WORKFLOW_ID)
        return cached
    defined = definition.create(
        api_key=config.api_key,
        api_base=config.api_base,
        model_name=config.model_name,
        model_provider=config.model_provider,
    )
    # 工具已在语义层绑进 ToolComponent，此处不再登记进运行资源；
    # 只有 ReAct 那种「Agent 按标识选工具」的形态才需要 add_tool。
    # provider 是零参可调用：运行资源按 `resource_provider()` 取实例
    _require_ok(
        Runner.resource_mgr.add_workflow(defined.card, lambda: defined.workflow), "工作流"
    )
    _REGISTERED[definition.WORKFLOW_ID] = defined
    return defined


def _require_model_endpoint(config: HostConfig) -> None:
    """前置校验：工作流的模型客户端配置在**构造期**就要求非空 api_key 与 api_base。

    agent-core 的 `ModelClientConfig` 在构造期校验 provider 与端点的搭配
    （`openjiuwen/core/foundation/llm/schema/config.py` 的 `validate_client_provider`），
    缺任一项直接抛校验错。本函数先把它翻译成一句可操作的提示——框架的原始报错
    只说「api_base is required」，不会告诉宿主该去哪份 `.env` 里补。
    """
    missing = [
        name
        for name, value in (("LLM_API_KEY", config.api_key), ("LLM_API_BASE", config.api_base))
        if not value
    ]
    if missing:
        raise ValueError(
            f"缺少模型端点配置 {missing}；请复制 deploy/.env.example 为 .env 并填写后显式装载。"
            "工作流的 LLM 与 HITL 节点在构造期即校验该配置，不是到调用时才失败。"
        )


def make_init_hook(config: HostConfig | None = None):
    """生成启动钩子：在处理器启动之前注册工具与工作流。"""

    async def _init() -> None:
        register_resources(config)

    return _init


def build_handler() -> Any:
    """装配 AgentHandler。

    Handler 只持有「标识 + 执行器」，实例由运行资源在执行期解析，
    因此它可以先于工作流注册而构造。
    """
    return AgentCoreHandler(definition.WORKFLOW_ID, Runner)
