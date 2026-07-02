#!/usr/bin/env bash
# config.sh — flat-YAML reader + ${VAR:default} expansion for sut-sources.yml.
# Depends only on bash (>=4 for `${var//pat/repl}` and indirect expansion).

# cfg_expand <string> — resolve ${VAR} and ${VAR:default} from the environment.
cfg_expand() {
  local s="$1" pre post name val
  # ${VAR:default} first (so a colon-default is not mistaken for plain ${VAR})
  while [[ "$s" =~ \$\{([A-Za-z_][A-Za-z0-9_]*):([^}]*)\} ]]; do
    pre="${s%%"${BASH_REMATCH[0]}"*}"
    post="${s#*"${BASH_REMATCH[0]}"}"
    name="${BASH_REMATCH[1]}"
    val="${!name:-${BASH_REMATCH[2]}}"
    s="${pre}${val}${post}"
  done
  # ${VAR}
  while [[ "$s" =~ \$\{([A-Za-z_][A-Za-z0-9_]*)\} ]]; do
    pre="${s%%"${BASH_REMATCH[0]}"*}"
    post="${s#*"${BASH_REMATCH[0]}"}"
    name="${BASH_REMATCH[1]}"
    val="${!name:-}"
    s="${pre}${val}${post}"
  done
  printf '%s' "$s"
}

# cfg_raw <file> <dotted-key> — raw (un-expanded) value for a flat-yaml key, or empty.
# Escape regex metacharacters in the key so dotted keys match literally (otherwise
# env.x.build.1 would also match a hypothetical env.x.build.10 — the '.' is a regex any-char).
cfg_raw() {
  local file="$1" key="$2" escaped
  escaped="${key//./\\.}"
  sed -n "s/^${escaped}[[:space:]]*:[[:space:]]*//p" "$file" | head -n1
}

# cfg_load_env <file> <env> — sets globals: source_repo source_ref source_dir java_home + build[] array.
cfg_load_env() {
  local file="$1" env="$2" i v
  source_repo=$(cfg_expand "$(cfg_raw  "$file" "env.${env}.source.repo")")
  source_ref=$( cfg_expand "$(cfg_raw  "$file" "env.${env}.source.ref")")
  source_dir=$( cfg_expand "$(cfg_raw  "$file" "env.${env}.source.dir")")
  java_home=$(  cfg_expand "$(cfg_raw  "$file" "env.${env}.java-home")")
  build=()
  i=1
  while v=$(cfg_raw "$file" "env.${env}.build.${i}") && [ -n "$v" ]; do
    build+=( "$(cfg_expand "$v")" )
    i=$((i+1))
  done
  export source_repo source_ref source_dir java_home
}
