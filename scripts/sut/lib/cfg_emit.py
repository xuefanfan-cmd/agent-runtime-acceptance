#!/usr/bin/env python3
"""cfg_emit.py — emit shell assignments for one env from nested sut-sources.yml.

Invoked by lib/config.sh as:  python3 cfg_emit.py <sut-sources.yml> <env>

Prints shell-assignable lines (consumed via `eval`) that populate, for the named
env:
  java_home='<expanded>'
  src_dir[NAME]='<expanded>'        ; src_repo[NAME]=... ; src_ref[NAME]=...
  src_sync[NAME]='hard|pull|none'
  steps+=('source:module')          # in declared order

${VAR} and ${VAR:default} are expanded from os.environ, mirroring the semantics of
the former bash cfg_expand so provision-sut.sh sees literal paths. Validates that
every step references a declared source.
"""
import os
import re
import sys

try:
    import yaml
except ModuleNotFoundError:
    sys.stderr.write("PyYAML is required to parse sut-sources.yml (pip install pyyaml)\n")
    sys.exit(2)


_VAR = re.compile(r"\$\{([A-Za-z_]\w*)(?::([^}]*))?\}")


def exp(value):
    """Expand ${VAR} / ${VAR:default} from the environment; '' for None."""
    if value is None:
        return ""
    return _VAR.sub(
        lambda m: os.environ.get(m.group(1), m.group(2) if m.group(2) is not None else ""),
        str(value),
    )


def q(value):
    """Single-quote a value for safe shell assignment (embeds quotes escaped)."""
    return "'" + str(value).replace("'", "'\"'\"'") + "'"


def die(msg):
    sys.stderr.write(f"[cfg_emit] {msg}\n")
    sys.exit(2)


def main():
    if len(sys.argv) != 3:
        die("usage: cfg_emit.py <sut-sources.yml> <env>")
    path, env_name = sys.argv[1], sys.argv[2]

    with open(path, encoding="utf-8") as fh:
        doc = yaml.safe_load(fh) or {}
    envs = (doc or {}).get("envs") or {}
    if env_name not in envs:
        die(f"env '{env_name}' not found in {path}")
    env = envs[env_name] or {}

    sources = env.get("sources") or {}
    steps = env.get("steps") or []

    out = [f"java_home={q(exp(env.get('java-home')))}"]

    mvn = env.get("maven") or {}
    # Default test-skip=True: skip the SUT's own test COMPILE+run during provisioning.
    # Provisioning only installs jars; the acceptance suite does the verification.
    # Override per-env with:  maven: { test-skip: false, flags: "-X ..." }
    out.append(f"mvn_test_skip={'1' if mvn.get('test-skip', True) else '0'}")
    out.append(f"mvn_flags={q(exp(mvn.get('flags', '')))}")

    for name, spec in sources.items():
        spec = spec or {}
        out.append(f"src_dir[{q(name)}]={q(exp(spec.get('dir')))}")
        out.append(f"src_repo[{q(name)}]={q(exp(spec.get('repo')))}")
        out.append(f"src_ref[{q(name)}]={q(exp(spec.get('ref')))}")
        out.append(f"src_sync[{q(name)}]={q(exp(spec.get('sync') or 'none'))}")

    if not steps:
        die(f"env '{env_name}' declares no steps")

    for st in steps:
        st = st or {}
        src = st.get("source")
        mod = st.get("module", ".")
        if not src:
            die(f"step missing 'source': {st}")
        if src not in sources:
            die(f"step references unknown source '{src}' (not in sources)")
        out.append(f"steps+=({q(f'{src}:{mod}')})")

    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
