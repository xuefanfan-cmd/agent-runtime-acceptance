#!/usr/bin/env bash
# run-pipeline.sh — chain Stage 1 (provision) → Stage 2/3 (mvnw test) → Stage 4 (report).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PROV="$HERE/sut/provision-sut.sh"

ENV=""; SKIP_PROVISION=0; ONLY_PROVISION=0; MVN_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --env)             ENV="$2"; shift 2;;
    --skip-provision)  SKIP_PROVISION=1; shift;;
    --only-provision)  ONLY_PROVISION=1; shift;;
    --) shift; while [ $# -gt 0 ]; do MVN_ARGS+=("$1"); shift; done;;
    *) MVN_ARGS+=("$1"); shift;;
  esac
done
[ -n "$ENV" ] || { echo "usage: run-pipeline.sh --env <name> [--skip-provision|--only-provision] [-- mvn args...]" >&2; exit 2; }

# Run from the repo root so relative paths resolve consistently regardless of where
# the caller invoked us: provision's default source.dir (third_party/...) and ./mvnw.
cd "$ROOT"

# Stage 1
if [ "$SKIP_PROVISION" -eq 0 ]; then
  "$PROV" --env "$ENV"
fi
[ "$ONLY_PROVISION" -eq 1 ] && { echo "[pipeline] --only-provision: done after Stage 1"; exit 0; }

# Stage 2 + 3
set +e
./mvnw test -Dtest.env="$ENV" -Dmaven.test.failure.ignore=true "${MVN_ARGS[@]}"
rc=$?
set -e

# Stage 4 (optional Allure — generates a Markdown report for AI consumption.
# Uses `allure agent inspect` which reads existing allure-results and writes
# index.md + per-test .md files + machine-readable JSONL manifests.
# If it's also desirable to generate an HTML report in the same location, you
# can also run `allure generate target/allure-results -o target/allure-report-html`.)
if grep -q 'allure-maven' pom.xml 2>/dev/null; then
  if ! command -v allure &>/dev/null && [ -s "$HOME/.nvm/nvm.sh" ]; then
    export NVM_DIR="$HOME/.nvm"
    . "$NVM_DIR/nvm.sh" 2>/dev/null || true
  fi
  if command -v allure &>/dev/null; then
    allure agent inspect target/allure-results -o target/allure-report-md --report off \
      || echo "[pipeline] allure agent inspect failed (non-fatal)"
  else
    echo "[pipeline] allure CLI not found (not in PATH and nvm not available), skipping report"
  fi
fi

exit "$rc"
