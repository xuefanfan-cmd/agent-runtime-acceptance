#!/usr/bin/env bash
# provision-sut.sh — Stage 1 core: obtain SUT source (lazy clone) + idempotent
# mvn install + verify every artifact declared in application-<env>.yml exists in .m2.
# Standalone: depends only on bash, git, mvn. Reads no test-repo Maven state.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/lib/config.sh"
. "$HERE/lib/m2path.sh"
. "$HERE/lib/verify.sh"

ENV=""; SOURCES_YML="$HERE/sut-sources.yml"; APP_YML=""; FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --env)         ENV="$2"; shift 2;;
    --sources-yml) SOURCES_YML="$2"; shift 2;;
    --app-yml)     APP_YML="$2"; shift 2;;
    --force)       FORCE=1; shift;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done
[ -n "$ENV" ] || { echo "usage: provision-sut.sh --env <name> [--sources-yml F] [--app-yml A] [--force]" >&2; exit 2; }
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

# toolchain checks
command -v git >/dev/null || { echo "git not on PATH" >&2; exit 2; }
command -v mvn >/dev/null || { echo "mvn not on PATH" >&2; exit 2; }
if [ -n "$java_home" ]; then
  [ -x "$java_home/bin/java" ] || { echo "java-home not found/invalid: $java_home" >&2; exit 2; }
  export JAVA_HOME="$java_home"
fi

# --- 1a. obtain source (lazy clone) ---
git_sync() { # <dir> <ref>
  local dir="$1" ref="$2"
  git -C "$dir" fetch --quiet --tags --force
  if git -C "$dir" rev-parse --verify --quiet "origin/${ref}" >/dev/null; then
    git -C "$dir" checkout --quiet "$ref" 2>/dev/null || true
    git -C "$dir" reset --hard --quiet "origin/${ref}"
  else
    git -C "$dir" checkout --quiet "$ref"
    git -C "$dir" reset --hard --quiet "$ref"
  fi
}

dir="$source_dir"
if [ ! -d "$dir/.git" ]; then
  if [ -z "$source_repo" ]; then
    echo "[provision] source missing and no repo configured: $dir" >&2; exit 2
  fi
  echo "[provision] cloning $source_repo → $dir"
  git clone --quiet "$source_repo" "$dir"
  git_sync "$dir" "$source_ref"
else
  echo "[provision] updating $dir @ $source_ref"
  git_sync "$dir" "$source_ref"
fi
head="$(git -C "$dir" rev-parse HEAD)"

# --- 1b. idempotent rebuild ---
marker="$dir/.sut-provision-${ENV}.sha"
recipe="$head|$source_ref|${java_home:-}|$M2ROOT|${build[*]:-}"
recipe_sha="$(printf '%s' "$recipe" | sha256sum | cut -d' ' -f1)"
prev=""; [ -f "$marker" ] && prev="$(cat "$marker")"
skip=0
if [ "$FORCE" -eq 0 ] && [ "$prev" = "$recipe_sha" ]; then
  skip=1; echo "[provision] up-to-date ($head); skipping build (will still verify)"
fi

# --- 1c. build (ordered reactor steps) ---
do_build() {
  local reactor
  for reactor in "${build[@]}"; do
    echo "[provision] mvn install: $reactor → $M2ROOT"
    ( cd "$dir/$reactor" && mvn -q clean install -DskipTests -Dmaven.repo.local="$M2ROOT" )
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
echo "[provision] OK — env=$ENV head=$head m2=$M2ROOT"
