#!/usr/bin/env bash
# verify.sh — parse application-<env>.yml sut.agents.* and assert each jar exists in m2-root.
# Sources m2path.sh (m2_jar_path) from the same lib dir (idempotent re-source is harmless).
. "$(dirname "${BASH_SOURCE[0]}")/m2path.sh"

# verify_parse_agents <app-yml> — prints rows: name<TAB>group<TAB>artifact<TAB>version
verify_parse_agents() {
  awk '
    BEGIN { inagents=0; agent=""; n=0 }
    # enter agents: section (a line that is <indent>agents:)
    /^[[:space:]]*agents:[[:space:]]*$/ { inagents=1; agent=""; next }
    # exit agents on a 2-space sibling key (e.g. "  java:")
    inagents && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { inagents=0 }
    inagents {
      # agent header: exactly 4 leading spaces, "name:"
      if ($0 ~ /^    [A-Za-z0-9_.-]+:[[:space:]]*$/) {
        agent=$0; sub(/^    /,"",agent); sub(/:.*/,"",agent); next
      }
      # fields under an agent at 6-space indent
      if (agent != "" && $0 ~ /^      (group|artifact|version):/) {
        key=$0; sub(/^      /,"",key); sub(/:.*/,"",key)
        val=$0; sub(/^      (group|artifact|version):[[:space:]]*/,"",val)
        sub(/[[:space:]]*#.*$/,"",val)        # strip inline comment
        gsub(/^["'\'']|["'\'']$/,"",val)      # strip surrounding quotes
        data[agent SUBSEP key]=val
        if (!(agent in seen)) { seen[agent]=1; order[++n]=agent }
      }
    }
    END {
      for (i=1;i<=n;i++) {
        a=order[i]
        printf "%s\t%s\t%s\t%s\n", a, data[a SUBSEP "group"], data[a SUBSEP "artifact"], data[a SUBSEP "version"]
      }
    }
  ' "$1"
}

# verify_agents <app-yml> <m2-root> — sets global MISSING[] ; returns 0 if all jars present.
verify_agents() {
  local app="$1" m2root="$2" name group artifact version jar
  MISSING=()
  while IFS=$'\t' read -r name group artifact version; do
    [ -n "$name" ] || continue
    jar="$(m2_jar_path "$m2root" "$group" "$artifact" "$version")"
    [ -f "$jar" ] || MISSING+=("${name} -> ${jar}")
  done < <(verify_parse_agents "$app")
  [ ${#MISSING[@]} -eq 0 ]
}
