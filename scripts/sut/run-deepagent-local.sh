#!/usr/bin/env bash
#
# run-deepagent-local.sh — 载入 .env.local 后跑本地 managed SUT 的测试，密钥全程不入库、不上命令行。
#
# 为什么需要它：框架读 sut.java.system-properties 走的是 getStringMap，只认 YAML，
# 没有逐 key 的环境变量覆盖通道；而被跟踪的 application-*.yml 有 pre-commit 密钥扫描
# （scripts/check-secrets.sh 直接拒收含 '@' 或 'sk-' 的行），密钥本来就不该写进去。
# 好在 agent jar 的 application.yml 全部用 ${VAR} 占位符，且框架用 ProcessBuilder
# 继承父进程环境启动 jar，所以「export 后再跑 mvnw」是唯一干净的注入路径。
# 用 -D 传密钥会留在 shell history 和 ps 输出里，不要那么做。
#
# 用法：
#   ./scripts/sut/run-deepagent-local.sh -Dgroups=feat-001
#   ./scripts/sut/run-deepagent-local.sh -Dtest=CallbackReceiverSurfaceTest
#   ENV_FILE=.env.ci ./scripts/sut/run-deepagent-local.sh -Dgroups=integration
#
# 前置：./scripts/sut/install-deepagent-jars.sh 已把三个 jar 装进 ~/.m2。

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null || printf '%s' "$script_dir/../..")"
cd "$repo_root"

env_file="${ENV_FILE:-.env.local}"

if [ ! -f "$env_file" ]; then
    printf '❌ 缺少 %s\n' "$env_file" >&2
    printf '   先执行: cp scripts/sut/deepagent.env.example %s  然后填入真实值\n' "$env_file" >&2
    exit 1
fi

# 只接受 KEY=VALUE 行；注释与空行忽略。set -a 让后续赋值自动 export，
# 从而被 mvnw → surefire → ProcessBuilder 一路继承到 agent JVM。
set -a
# shellcheck disable=SC1090
. "$env_file"
set +a

# 早失败好过让 agent 起来后报一个难懂的 LLM 401。
: "${LLM_API_KEY:?未设置 LLM_API_KEY（见 scripts/sut/deepagent.env.example）}"

if [ "${LLM_API_KEY}" = "sk-REPLACE_ME" ]; then
    printf '❌ LLM_API_KEY 仍是模板占位值，请填入真实密钥\n' >&2
    exit 1
fi

printf '→ env: %s | model=%s | base=%s | deep-research push=%s | tavily=%s\n' \
    "$env_file" "${LLM_MODEL:-<unset>}" "${LLM_API_BASE:-<unset>}" \
    "${DEEP_RESEARCH_PUSH_NOTIFICATIONS:-false}" \
    "$([ -n "${TAVILY_API_KEY:-}" ] && printf 'set' || printf 'unset')"

# test.env 缺省 local；调用方可用 -Dtest.env=openjiuwen 覆盖（原样透传即可，后写的 -D 生效）。
exec ./mvnw test -Dtest.env=local "$@"
