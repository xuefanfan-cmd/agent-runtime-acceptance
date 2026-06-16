#!/usr/bin/env bash
#
# check-secrets.sh — pre-commit guard against credential leaks in test-resource YAML.
#
# Rejects YAML files DIRECTLY under src/test/resources/ (not nested) that contain:
#   • an '@' character            — common in API-key / token formats and credential emails
#   • an 'sk-' substring          — OpenAI/Anthropic-style key prefix (any case: sk-/SK-/Sk-)
#
# Modes:
#   (default)  scan the STAGED content of such files — exactly what you are about to commit
#              (reads each staged blob via `git show :<file>`).
#   --all      scan every .yml/.yaml currently on disk in that directory — for CI / manual
#              full checks.
#
# Install as a shared hook (run once per clone; see githooks/pre-commit):
#   git config core.hooksPath githooks
#
# Exit status: 0 = clean, 1 = offending content found (the hook aborts the commit).

set -euo pipefail

# --- locate the repo root (works as a hook, from a subdir, or run manually) ---
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null || printf '%s' "$script_dir/..")"
cd "$repo_root"

resources_dir="src/test/resources"
# '@' (any at-sign) OR 'sk-' in any case. ERE; grep -I skips binaries.
pattern='(@|[sS][kK]-)'

offending=0

# Scans candidate content from stdin (fed by the caller via input redirection — NOT a pipe —
# so this runs in the current shell and can flip the `offending` flag; a pipe would put it in
# a subshell and silently lose the result).
scan_stream() {
    local label="$1"
    local hits
    hits="$(grep -nIE "$pattern" -)" || true   # grep exit 1 (no match) is expected
    if [ -n "$hits" ]; then
        printf '\n❌ %s — secret-like content found (@ or sk-):\n' "$label" >&2
        printf '%s\n' "$hits" >&2
        offending=1
    fi
}

# True iff path is src/test/resources/<basename> (a direct child, no nested directory).
# Enforced explicitly rather than via git pathspec globs, whose '*' may cross '/'.
is_direct_child() {
    local f="$1" rest
    case "$f" in
        "$resources_dir"/*.yml|"$resources_dir"/*.yaml) ;;
        *) return 1 ;;
    esac
    rest="${f#"$resources_dir"/}"
    case "$rest" in
        */*) return 1 ;;  # has a subdirectory segment → nested
        *)   return 0 ;;
    esac
}

if [ ! -d "$resources_dir" ]; then
    exit 0   # nothing to guard
fi

if [ "${1:-}" = "--all" ]; then
    # CI / manual: every direct-child yml/yaml on disk (find -maxdepth 1 ⇒ no nesting).
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        scan_stream "$f" < <(cat "$f")
    done < <(find "$resources_dir" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) -print)
else
    # Hook: only staged additions/copies/modifications that are direct children.
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        is_direct_child "$f" || continue
        scan_stream "$f" < <(git show ":$f" 2>/dev/null)
    done < <(git diff --cached --name-only --diff-filter=ACM)
fi

if [ "$offending" -ne 0 ]; then
    cat >&2 <<'EOF'

Commit blocked by check-secrets.sh: potential secret in src/test/resources/*.yml.
Move secrets to an environment variable or an untracked file (see project memory /
travel-sit-test-framework-design.md). To bypass locally for a known-safe case, run:
  git commit --no-verify
(use sparingly — this defeats the leak guard).
EOF
    exit 1
fi

printf 'check-secrets: no @/sk- leaks in %s/*.yml\n' "$resources_dir" >&2
exit 0
