# coding: utf-8
"""受限工作区文件工具。

给 DeepAgent 一组**只能作用于工作区目录内**的文件能力。

`create_deep_agent(workspace=..., restrict_to_work_dir=True)` 已经挂载框架自带的读写与
目录工具（`openjiuwen.harness.tools` 的 `ReadFileTool`、`EditFileTool`、`ListDirTool`），
业务侧不必重写。本模块只补一个框架没有的清单工具，用来演示「业务工具如何与内建工具
共存」，并把越界路径挡在工作区之外。
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from openjiuwen.core.foundation.tool import LocalFunction, ToolCard

TOOL_ID = "list_artifacts"


def _resolve_within(root: Path, relative: str) -> Path:
    """把相对路径解析到工作区内；越界即拒绝。

    `restrict_to_work_dir` 约束的是框架内建工具；业务工具的越界防护要自己做，
    否则一个 `../` 就绕开了工作区边界。
    """
    target = (root / relative).resolve()
    if target != root and root not in target.parents:
        raise ValueError(f"路径越出工作区边界：{relative}")
    return target


def create_list_artifacts_tool(workspace_root: str | Path) -> LocalFunction:
    """构造清单工具：列出工作区内的 Markdown 交付物。"""
    root = Path(workspace_root).resolve()

    def execute(subdir: str = ".") -> dict[str, Any]:
        target = _resolve_within(root, subdir)
        if not target.is_dir():
            return {"artifacts": [], "reason": "目录不存在"}
        names = sorted(p.name for p in target.glob("*.md") if p.is_file())
        return {"artifacts": names}

    card = ToolCard(
        id=TOOL_ID,
        name=TOOL_ID,
        description="列出工作区内的 Markdown 交付物",
        input_params={
            "type": "object",
            "properties": {
                "subdir": {"type": "string", "description": "工作区内的相对子目录，默认为根"}
            },
            "required": [],
        },
    )
    return LocalFunction(card=card, func=execute)
