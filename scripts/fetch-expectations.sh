#!/usr/bin/env bash
# Fetch the pinned expectation documents from the docs repository into target/expectations/.
#
#   scripts/fetch-expectations.sh              fetch at the pinned commits (default)
#   scripts/fetch-expectations.sh check-sync   diff the pinned testplan against docs main latest
#
# Pins: scripts/expectation-sources.env. Needs read access to the docs repository.
# Cache: target/expectations/docs-repo; set DOCS_CACHE to reuse an existing clone instead.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/expectation-sources.env"
OUT="$ROOT/target/expectations"
CACHE="${DOCS_CACHE:-$OUT/docs-repo}"
mkdir -p "$OUT"

if [ ! -e "$CACHE" ]; then
  git clone --filter=blob:none --no-checkout "$DOCS_REPO_URL" "$CACHE"
fi

ensure_commit() { # $1 commit, $2 branch to fetch when missing
  git -C "$CACHE" cat-file -e "$1^{commit}" 2>/dev/null && return 0
  git -C "$CACHE" fetch origin "$2"
  git -C "$CACHE" cat-file -e "$1^{commit}" 2>/dev/null || {
    echo "pinned commit $1 not reachable via branch $2 (force-pushed or deleted): re-review and update the pin" >&2
    return 1
  }
}

case "${1:-fetch}" in
  fetch)
    ensure_commit "$FEAT112_TESTPLAN_COMMIT" "$FEAT112_TESTPLAN_BRANCH"
    git -C "$CACHE" show "$FEAT112_TESTPLAN_COMMIT:$FEAT112_TESTPLAN_PATH" > "$OUT/$(basename "$FEAT112_TESTPLAN_PATH")"
    {
      echo "# documents in this directory were fetched by scripts/fetch-expectations.sh; do not edit"
      echo "$(basename "$FEAT112_TESTPLAN_PATH") <- $DOCS_REPO_URL $FEAT112_TESTPLAN_PATH @ $FEAT112_TESTPLAN_COMMIT"
    } > "$OUT/PROVENANCE.txt"
    echo "fetched $(basename "$FEAT112_TESTPLAN_PATH") @ ${FEAT112_TESTPLAN_COMMIT:0:12} -> $OUT"
    ;;
  check-sync)
    ensure_commit "$FEAT112_TESTPLAN_COMMIT" "$FEAT112_TESTPLAN_BRANCH"
    git -C "$CACHE" fetch origin main
    if git -C "$CACHE" diff --quiet "$FEAT112_TESTPLAN_COMMIT" origin/main -- "$FEAT112_TESTPLAN_PATH"; then
      echo "in sync: docs main matches the pinned testplan ${FEAT112_TESTPLAN_COMMIT:0:12}"
    else
      echo "docs main differs from the pinned testplan ${FEAT112_TESTPLAN_COMMIT:0:12}:"
      git -C "$CACHE" diff --stat "$FEAT112_TESTPLAN_COMMIT" origin/main -- "$FEAT112_TESTPLAN_PATH"
      echo "review the change against the covering tests, then update the pin (expected while the pinned PR is unmerged)"
      exit 2
    fi
    ;;
  *)
    echo "usage: $0 [fetch|check-sync]" >&2; exit 64 ;;
esac
