#!/usr/bin/env bash
# config.sh — nested-YAML reader for sut-sources.yml (python3 + PyYAML).
# Replaces the former flat dotted-key sed reader so a single env can declare
# MULTIPLE git sources (e.g. agent-core-java + agent-solution, which share no
# parent pom) and an ordered, cross-source build plan.
#
# cfg_load_env <file> <env> sets globals:
#   java_home              JDK for the build (may be empty → inherit)
#   src_dir[name]          checkout dir per named source
#   src_repo[name]         git URL (empty → no lazy clone; dir must exist)
#   src_ref[name]          branch / tag / commit
#   src_sync[name]         hard | pull | none  (default none)
#   steps[i]="src:module"  ordered reactor steps; module is a path under src's dir
# ${VAR} and ${VAR:default} are expanded from the environment.
_CFG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cfg_load_env() {
  local file="$1" env="$2" emitted
  command -v python3 >/dev/null || { echo "[config] python3 not on PATH (needed to parse sut-sources.yml)" >&2; return 2; }
  [ -f "$_CFG_DIR/cfg_emit.py" ] || { echo "[config] missing $_CFG_DIR/cfg_emit.py" >&2; return 2; }
  java_home=""
  mvn_test_skip=""; mvn_flags=""
  declare -gA src_dir=() src_repo=() src_ref=() src_sync=()
  declare -ga steps=()
  # NOTE: keep declaration and assignment on separate lines so a python failure
  # (e.g. unknown env, missing PyYAML) is visible under `set -e` — `local x=$(...)`
  # would mask it.
  emitted="$(python3 "$_CFG_DIR/cfg_emit.py" "$file" "$env")" || return $?
  eval "$emitted"
}
