#!/usr/bin/env python3
"""Dump the RT-031-04-16 topology golden from the deployed Python IRConverter.

Test-side copy of ``agent-solution/common/agent-core-ext-java/agent-core-ext-studio-dsl/
tools/dump_ir_assemble_golden.py``. The only deviation is the workflow-level streaming
probe: the deployed ``openjiuwen`` Workflow keeps the flag in the private
``_is_streaming`` attribute (``Workflow.set_end_comp`` sets it when the end component
declares ``response_mode="streaming"``) and exposes no public ``is_streaming`` accessor,
so probing only the public name reported ``false`` for streaming workflows.

Everything else (schema, edge flattening, cross-cutting summary, model name collection)
is byte-identical to the product script so the comparison stays like-for-like; the reason
for the test-side copy is the agent-solution read-only boundary of this task.

Usage (inside the deployment runtime image, read-only)::

    python3 dump_ir_assemble_golden.py --ir <ir.json> --out <golden.json>
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
from pathlib import Path
from typing import Any

LOG = logging.getLogger(__name__)


def _flatten_edges(edge_map: dict[str, list[str]] | None) -> list[list[str]]:
    if not edge_map:
        return []
    out: list[list[str]] = []
    for src in sorted(edge_map.keys()):
        for tgt in sorted(edge_map.get(src) or []):
            out.append([src, tgt])
    return out


def _collect_model_names(ir: dict[str, Any]) -> list[str]:
    names: set[str] = set()
    for comp in ir.get("components") or []:
        cfg = comp.get("configs") or {}
        for key in ("model", "modelConfig", "llm", "llmConfig"):
            node = cfg.get(key)
            if isinstance(node, str) and node.strip():
                names.add(node.strip())
            elif isinstance(node, dict):
                for nk in ("modelName", "model_name", "name"):
                    v = node.get(nk)
                    if isinstance(v, str) and v.strip():
                        names.add(v.strip())
        mn = cfg.get("modelName")
        if isinstance(mn, str) and mn.strip():
            names.add(mn.strip())
    return sorted(names)


def _cross_cutting(ir: dict[str, Any]) -> dict[str, Any]:
    root_cfg = ir.get("configs") or {}
    meta = ir.get("metadata") or {}
    wf_log = (
        root_cfg.get("ioLogLevel")
        or root_cfg.get("logLevel")
        or meta.get("ioLogLevel")
        or meta.get("logLevel")
    )
    wf_stream = root_cfg.get("stream")
    if wf_stream is None:
        wf_stream = root_cfg.get("isStreamOut")
    nodes: dict[str, Any] = {}
    for comp in ir.get("components") or []:
        cid = comp.get("id")
        cfg = comp.get("configs") or {}
        log = cfg.get("ioLogLevel") or cfg.get("logLevel")
        stream = cfg.get("stream")
        if stream is None:
            stream = cfg.get("streaming")
        if stream is None:
            stream = cfg.get("isStreamOut")
        if log is None and stream is None:
            continue
        nodes[str(cid)] = {"ioLogLevel": log, "streamEnabled": stream}
    return {
        "workflowIoLogLevel": wf_log,
        "workflowStreamEnabled": wf_stream,
        "nodes": dict(sorted(nodes.items())),
    }


def _workflow_streaming(wf: Any) -> bool:
    """Read the assembled workflow's own streaming flag from the deployed runtime."""
    for name in ("is_streaming", "_is_streaming", "isStreaming", "_isStreaming"):
        value = getattr(wf, name, None)
        if value is not None:
            return bool(value)
    return False


async def _build_snapshot(ir: dict[str, Any]) -> dict[str, Any]:
    from jiuwen.serve.controllers.execution.ir_converter import IRConverter

    wf = await IRConverter.build_openjiuwen_workflow_from_ir(ir)
    internal = getattr(wf, "_internal", None)
    spec = getattr(getattr(internal, "_workflow_config", None), "spec", None)
    if spec is None:
        spec = getattr(internal, "_workflow_spec", None)
    if spec is None:
        raise RuntimeError("cannot locate WorkflowSpec on Python Workflow")

    edges = getattr(spec, "edges", None) or {}
    stream_edges = getattr(spec, "stream_edges", None) or getattr(spec, "streamEdges", None) or {}
    comps = getattr(spec, "comp_configs", None) or getattr(spec, "compConfigs", None) or {}
    starts = getattr(spec, "start_nodes", None) or getattr(spec, "startNodes", None) or []
    end_id = getattr(wf, "end_comp_id", None) or getattr(wf, "endCompId", None) or ""
    streaming = _workflow_streaming(wf)

    return {
        "schemaVersion": "1",
        "workflowId": ir.get("workflowId") or "",
        "componentIds": sorted(str(k) for k in comps.keys()),
        "startId": starts[0] if starts else "",
        "endId": str(end_id or ""),
        "edges": _flatten_edges(dict(edges)),
        "streamEdges": _flatten_edges(dict(stream_edges)),
        "workflowStreaming": streaming,
        "crossCutting": _cross_cutting(ir),
        "modelNames": _collect_model_names(ir),
        "source": "python-IRConverter",
    }


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ir", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    try:
        import jiuwen.serve.controllers.execution.ir_converter  # noqa: F401
    except Exception as exc:  # pragma: no cover
        LOG.error(
            "IRConverter import failed (%s). Install agent-runtime + openjiuwen, "
            "or keep Java-generated goldens as CI baseline.",
            exc,
        )
        return 2

    ir = json.loads(args.ir.read_text(encoding="utf-8"))
    snap = asyncio.run(_build_snapshot(ir))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(snap, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    LOG.info("wrote %s", args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
