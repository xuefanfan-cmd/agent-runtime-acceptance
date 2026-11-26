#!/usr/bin/env bash
# Fetch the FEAT-112 testplan from the docs repository into target/expectations/.
#
#   scripts/fetch-expectations.sh            fetch the testplan the tests run against
#
# Within a version cycle the tests follow the latest testplan on the docs branch (main): the test repo carries no
# copy and no pin, and the commit actually fetched is recorded in target/expectations/PROVENANCE.txt. Only after a
# version is released is its testplan pinned, for the archive of that version: set FEAT112_TESTPLAN_COMMIT (in the
# environment or in scripts/expectation-sources.env) to reproduce a released version's run.
# Needs read access to the docs repository. Cache: target/expectations/docs-repo; set DOCS_CACHE to reuse a clone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PINNED_FROM_ENV="${FEAT112_TESTPLAN_COMMIT:-}"
. "$ROOT/scripts/expectation-sources.env"
FEAT112_TESTPLAN_COMMIT="${PINNED_FROM_ENV:-${FEAT112_TESTPLAN_COMMIT:-}}"
OUT="$ROOT/target/expectations"
CACHE="${DOCS_CACHE:-$OUT/docs-repo}"
mkdir -p "$OUT"

if [ ! -e "$CACHE" ]; then
  git clone --filter=blob:none --no-checkout "$DOCS_REPO_URL" "$CACHE"
fi

case "${1:-fetch}" in
  fetch)
    if [ -n "$FEAT112_TESTPLAN_COMMIT" ]; then
      git -C "$CACHE" cat-file -e "$FEAT112_TESTPLAN_COMMIT^{commit}" 2>/dev/null || git -C "$CACHE" fetch origin "$FEAT112_TESTPLAN_BRANCH"
      git -C "$CACHE" cat-file -e "$FEAT112_TESTPLAN_COMMIT^{commit}" 2>/dev/null || {
        echo "archived testplan commit $FEAT112_TESTPLAN_COMMIT not reachable via branch $FEAT112_TESTPLAN_BRANCH" >&2; exit 1; }
      COMMIT="$(git -C "$CACHE" rev-parse "$FEAT112_TESTPLAN_COMMIT^{commit}")"
      SOURCE="archived version (FEAT112_TESTPLAN_COMMIT)"
    else
      git -C "$CACHE" fetch origin "$FEAT112_TESTPLAN_BRANCH"
      COMMIT="$(git -C "$CACHE" rev-parse FETCH_HEAD)"
      SOURCE="latest of $FEAT112_TESTPLAN_BRANCH at $(date -Iseconds)"
    fi
    git -C "$CACHE" show "$COMMIT:$FEAT112_TESTPLAN_PATH" > "$OUT/$(basename "$FEAT112_TESTPLAN_PATH")"
    {
      echo "# documents in this directory were fetched by scripts/fetch-expectations.sh; do not edit"
      echo "$(basename "$FEAT112_TESTPLAN_PATH") <- $DOCS_REPO_URL $FEAT112_TESTPLAN_PATH @ $COMMIT ($SOURCE)"
    } > "$OUT/PROVENANCE.txt"
    echo "fetched $(basename "$FEAT112_TESTPLAN_PATH") @ ${COMMIT:0:12} ($SOURCE) -> $OUT"
    ;;
  *)
    echo "usage: $0 [fetch]" >&2; exit 64 ;;
esac
