#!/usr/bin/env bash
# provision-sut.sh — Stage 1 core: obtain SUT source (lazy clone) + idempotent
# mvn install + verify every artifact declared in application-<env>.yml exists in .m2.
# Standalone: depends only on bash, git, mvn (+ python3 to parse sut-sources.yml).
#
# A single env may declare MULTIPLE git sources (e.g. agent-core-java + agent-solution,
# which share no parent pom) and an ordered `steps:` plan that may cross sources.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/lib/config.sh"
. "$HERE/lib/m2path.sh"
. "$HERE/lib/verify.sh"

ENV=""; SOURCES_YML="$HERE/sut-sources.yml"; APP_YML=""; FORCE=0; DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --env)         ENV="$2"; shift 2;;
    --sources-yml) SOURCES_YML="$2"; shift 2;;
    --app-yml)     APP_YML="$2"; shift 2;;
    --force)       FORCE=1; shift;;
    --dry-run)     DRY_RUN=1; shift;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done
[ -n "$ENV" ] || { echo "usage: provision-sut.sh --env <name> [--sources-yml F] [--app-yml A] [--force] [--dry-run]" >&2; exit 2; }
[ -f "$SOURCES_YML" ] || { echo "sources yml not found: $SOURCES_YML" >&2; exit 2; }
[ -z "$APP_YML" ] && APP_YML="$HERE/../../src/test/resources/application-${ENV}.yml"
[ -f "$APP_YML" ] || { echo "application yml not found: $APP_YML" >&2; exit 2; }

cfg_load_env "$SOURCES_YML" "$ENV"

# m2-root resolution (spec §5): application yml sut.m2.repo → SUT_M2_REPO → ~/.m2/repository
m2_root_from_app() {
  awk '
    /^sut:[[:space:]]*$/ { insut=1; next }
    insut && /^[[:space:]]*m2:[[:space:]]*$/ { inm2=1; next }
    inm2 && /^[[:space:]]*repo:[[:space:]]*/ { v=$0; sub(/^[[:space:]]*repo:[[:space:]]*/,"",v); sub(/[[:space:]]*#.*$/,"",v); print v; exit }
    insut && /^[A-Za-z]/ { insut=0; inm2=0 }
  ' "$1"
}
M2ROOT="$(m2_root_from_app "$APP_YML")"
[ -n "$M2ROOT" ] || M2ROOT="${SUT_M2_REPO:-$HOME/.m2/repository}"

# --- dry-run: print the resolved plan and stop (no git, no mvn, no marker write) ---
if [ "$DRY_RUN" -eq 1 ]; then
  echo "[provision] DRY RUN — env=$ENV m2=$M2ROOT java_home=${java_home:-<inherited>}"
  for k in $(printf '%s\n' "${!src_dir[@]}" | sort); do
    printf '  source %-20s dir=%s ref=%s sync=%s repo=%s\n' \
      "$k" "${src_dir[$k]}" "${src_ref[$k]}" "${src_sync[$k]:-none}" "${src_repo[$k]:-<none>}"
  done
  echo "  steps (${#steps[@]}):"
  for entry in "${steps[@]}"; do printf '    - %s\n' "$entry"; done
  echo "  (would obtain sources, build steps in order, then verify jars in m2)"
  exit 0
fi

# toolchain checks
command -v git >/dev/null || { echo "git not on PATH" >&2; exit 2; }
command -v mvn >/dev/null || { echo "mvn not on PATH" >&2; exit 2; }
if [ -n "$java_home" ]; then
  [ -x "$java_home/bin/java" ] || { echo "java-home not found/invalid: $java_home" >&2; exit 2; }
  export JAVA_HOME="$java_home"
fi

# --- 1a. obtain each named source (lazy clone / sync) ---
# sync modes for an EXISTING checkout:
#   hard = fetch + reset --hard origin/<ref>   (CI / clean third_party clones)
#   pull = fetch + ff-only pull                (track upstream, no destructive reset)
#   none = true no-op — NON-destructive; uses the checkout exactly as the dev left it
#          (no fetch, no branch switch). Default. Ideal for sources pointed at
#          ~/agent_java/* dev trees where the dev owns the branch/working state.
# A freshly cloned source always checks out <ref>; sync only governs existing dirs.
git_checkout_ref() { # <dir> <ref> — check out a branch/tag/commit, immune to name collisions
  # `git checkout <ref>` aborts when <ref> matches BOTH a branch and a path (e.g. ref
  # 'common' vs a 'common/' dir inside agent-solution: "could be both a local file and
  # a tracking branch"). git switch resolves only branches/commits — never pathspecs —
  # so it can't collide; the -B fallback uses a full origin/ refspec (also unambiguous).
  local dir="$1" ref="$2"
  git -C "$dir" switch "$ref" 2>/dev/null \
    || git -C "$dir" switch --detach "$ref" 2>/dev/null \
    || git -C "$dir" checkout -B "$ref" "origin/${ref}"
}

git_obtain() { # <name> <dir> <repo> <ref> <sync>  → prints HEAD (stdout); logs to stderr
  local name="$1" dir="$2" repo="$3" ref="$4" sync="${5:-none}"
  if [ ! -d "$dir/.git" ]; then
    [ -n "$repo" ] || { echo "[provision] source '$name' missing and no repo configured: $dir" >&2; exit 2; }
    echo "[provision] cloning $name: $repo → $dir" >&2
    git clone --quiet "$repo" "$dir"
    git_checkout_ref "$dir" "$ref"
  else
    case "$sync" in
      hard)
        # -B <ref> origin/<ref> resets the local branch to origin/<ref> and checks it
        # out — exactly the hard-sync semantics, and unambiguous (full refspec).
        echo "[provision] sync '$name' (hard): fetch + reset --hard origin/$ref" >&2
        git -C "$dir" fetch --quiet --tags --force
        git -C "$dir" checkout -B "$ref" "origin/${ref}" --quiet
        ;;
      pull)
        echo "[provision] sync '$name' (pull): fetch + ff-only" >&2
        git -C "$dir" fetch --quiet --tags --force
        git_checkout_ref "$dir" "$ref"
        git -C "$dir" pull --ff-only --quiet 2>/dev/null || true
        ;;
      none|*)
        echo "[provision] sync '$name' (none): using checkout as-is (no fetch/checkout)" >&2 ;;
    esac
  fi
  # Only the HEAD sha goes to stdout (this function's return value).
  git -C "$dir" rev-parse HEAD
}

declare -A HEADS=()
for name in "${!src_dir[@]}"; do
  HEADS["$name"]="$(git_obtain "$name" "${src_dir[$name]}" "${src_repo[$name]}" "${src_ref[$name]}" "${src_sync[$name]:-none}")"
done

# --- 1b. idempotent rebuild (fingerprint = all source heads + steps + java + m2) ---
# Marker lives outside any source tree (which may be a shared/external dev checkout)
# and outside the repo: ~/.cache/agent-runtime-acceptance/. Override via SUT_PROVISION_CACHE.
# Build-fingerprint marker — small, machine-local; ~/.cache is the right home for it.
MARKER_DIR="${SUT_PROVISION_CACHE:-$HOME/.cache/agent-runtime-acceptance}"
mkdir -p "$MARKER_DIR"
marker="$MARKER_DIR/provision-${ENV}.sha"
# Per-step Maven logs: under the repo's gitignored target/ (conventional build-output
# home, easy to find) — NOT in ~/.cache. Override with SUT_BUILD_LOG_DIR to relocate,
# e.g. third_party/sut-build-logs (which also survives an explicit `mvn clean`).
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
BLDLOG_DIR="${SUT_BUILD_LOG_DIR:-$REPO_ROOT/target/sut-build-logs/${ENV}}"
mkdir -p "$BLDLOG_DIR"
# Sort heads by source name so the fingerprint is deterministic (assoc-array
# iteration order is not stable across bash runs).
heads_sorted=""
for k in $(printf '%s\n' "${!HEADS[@]}" | sort); do
  heads_sorted+="${k}=${HEADS[$k]},"
done
recipe="${heads_sorted}|${java_home:-}|$M2ROOT|${steps[*]}"
recipe_sha="$(printf '%s' "$recipe" | sha256sum | cut -d' ' -f1)"
prev=""; [ -f "$marker" ] && prev="$(cat "$marker")"
skip=0
if [ "$FORCE" -eq 0 ] && [ "$prev" = "$recipe_sha" ]; then
  skip=1; echo "[provision] up-to-date; skipping build (will still verify)"
fi

# --- 1c. build (ordered reactor steps across sources) ---
# Each step's full Maven output is redirected to a per-step log (terminal stays
# readable); on failure the actionable lines are extracted so the cause is never
# buried in thousands of compile lines. `|| rc=$?` captures the exit without
# tripping `set -e` (no need to toggle it).
do_build() {
  local entry src mod dir log rc safe idx=0
  local tsflags=()
  # Default mvn_test_skip=1 → also skip the SUT's own test COMPILE (-Dmaven.test.skip=true),
  # on top of -DskipTests (skip running). Provisioning installs jars; the acceptance
  # suite verifies behavior.
  [ "${mvn_test_skip:-1}" = "1" ] && tsflags=(-Dmaven.test.skip=true)
  for entry in "${steps[@]}"; do
    src="${entry%%:*}"; mod="${entry#*:}"
    dir="${src_dir[$src]:-}"
    [ -n "$dir" ] || { echo "[provision] step references unknown source: $src" >&2; exit 2; }
    idx=$((idx+1))
    safe="${src}_${mod}"; safe="${safe//\//_}"
    log="$BLDLOG_DIR/$(printf '%02d' "$idx")-${safe}.log"
    echo "[provision] mvn install: $src/$mod → $M2ROOT  (log: $log)"
    rc=0
    ( cd "$dir/$mod" && mvn -q clean install -DskipTests "${tsflags[@]}" \
        -Dmaven.repo.local="$M2ROOT" ${mvn_flags:-} ) > "$log" 2>&1 || rc=$?
    if [ "$rc" -ne 0 ]; then
      echo "[provision] ✗ FAILED (rc=$rc): $src/$mod" >&2
      echo "[provision] --- failure summary (from $log) ---" >&2
      if grep -aq 'BUILD SUCCESS\|BUILD FAILURE\|Reactor Summary\|^\[ERROR\]' "$log" 2>/dev/null; then
        # Maven ran and produced build output — extract only the actionable lines.
        grep -aE 'BUILD FAILURE|Reactor Summary|^\[ERROR\]|Caused by:|Failed to execute goal' \
          "$log" 2>/dev/null | head -n 40 >&2 || true
      else
        # No Maven build output → mvn never produced a result (cd failed, mvn/java
        # missing, JVM crash). Show the (short) log verbatim instead of an empty summary.
        echo "[provision] (no Maven build output — showing full log)" >&2
        head -n 40 "$log" >&2 2>/dev/null || true
      fi
      echo "[provision] --- end summary; full log: $log  (tail -f the log next run for live output) ---" >&2
      return 1
    fi
    echo "[provision] ✓ $src/$mod"
  done
}
if [ "$skip" -eq 0 ]; then
  do_build
  printf '%s' "$recipe_sha" > "$marker"
fi

# --- 1d. verify (always; self-heal if skipped yet jars missing) ---
if ! verify_agents "$APP_YML" "$M2ROOT"; then
  if [ "$skip" -eq 1 ]; then
    echo "[provision] jars missing after skip; forcing one rebuild"
    rm -f "$marker"
    do_build
    printf '%s' "$recipe_sha" > "$marker"
    verify_agents "$APP_YML" "$M2ROOT" || {
      echo "[provision] MISSING after rebuild:" >&2
      printf '  %s\n' "${MISSING[@]}" >&2
      exit 1
    }
  else
    echo "[provision] MISSING (check Tier R runtime prerequisite or build steps):" >&2
    printf '  %s\n' "${MISSING[@]}" >&2
    exit 1
  fi
fi
echo "[provision] OK — env=$ENV sources=${#HEADS[@]} steps=${#steps[@]} m2=$M2ROOT"
