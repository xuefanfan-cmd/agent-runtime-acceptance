# coding: utf-8
"""锁定本工程的宿主环境样例与 runtime 配置边界。"""
from __future__ import annotations

import os
import re
from pathlib import Path

import pytest
import yaml

from agent_runtime.bootstrap.config.loader import ConfigLoader, ConfigSource, SourceKind
from agent_runtime.bootstrap.config.runtime_config import RuntimeConfig


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src"
EXAMPLE = ROOT / "deploy" / ".env.example"
SECTION = re.compile(r"^# @section (\S+)\s*$")
ENTRY = re.compile(r"^(#?)([A-Za-z_][A-Za-z0-9_]*)=(.*)$")


def _sections() -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {}
    current = "<none>"
    for raw in EXAMPLE.read_text(encoding="utf-8").splitlines():
        mark = SECTION.match(raw)
        if mark:
            current = mark.group(1)
            sections.setdefault(current, [])
            continue
        match = ENTRY.match(raw)
        if match:
            sections.setdefault(current, []).append(match.group(2))
    return sections


def _source_host_env_names() -> set[str]:
    pattern = re.compile(
        r"(?:os\.environ\.get|os\.getenv|env\.get|_env)\(\s*['\"](RUNTIME_[A-Z0-9_]+)"
    )
    return {
        name
        for path in SOURCE.rglob("*.py")
        for name in pattern.findall(path.read_text(encoding="utf-8"))
    }


def test_env_example_has_host_and_runtime_sections() -> None:
    assert set(_sections()) == {"host", "runtime"}


def test_host_section_equals_source_reads() -> None:
    declared = set(_sections()["host"])
    actual = _source_host_env_names()
    assert declared == actual, {"extra": sorted(declared - actual), "missing": sorted(actual - declared)}
    assert not any(name.startswith("RUNTIME__") for name in declared)


def test_env_example_format_is_safe_for_env_file() -> None:
    for raw in EXAMPLE.read_text(encoding="utf-8").splitlines():
        match = ENTRY.match(raw)
        if match:
            assert not match.group(3).startswith(" "), raw
            assert " #" not in match.group(3), raw


def test_runtime_section_explicitly_has_no_bound_environment_values() -> None:
    assert _sections()["runtime"] == []
    assert "本工程不从环境变量读 runtime 配置" in EXAMPLE.read_text(encoding="utf-8")


def test_application_runtime_config_binds_without_unknown_key(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    data = yaml.safe_load((ROOT / "resources" / "application.yml").read_text(encoding="utf-8")) or {}
    service = ((data.get("openjiuwen") or {}).get("service") or {})
    candidate = tmp_path / "application.yml"
    candidate.write_text(yaml.safe_dump({"openjiuwen": {"service": service}}), encoding="utf-8")
    for name in list(os.environ):
        if name.startswith(("OPENJIUWEN__SERVICE__", "RUNTIME__")):
            monkeypatch.delenv(name)
    with caplog.at_level("WARNING", logger="agent_runtime.bootstrap.config.loader"):
        ConfigLoader().load(RuntimeConfig, sources=(ConfigSource(SourceKind.FILE, str(candidate)),))
    assert "未声明的键" not in caplog.text
