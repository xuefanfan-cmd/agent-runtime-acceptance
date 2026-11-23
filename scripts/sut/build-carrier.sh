#!/usr/bin/env bash
# Rebuild the FEAT-112 carrier from source and write a provenance manifest.
#
# Sources (standing requirement, 2026-11-13):
#   - core:     openJiuwen/agent-core-java, latest origin/develop (built from a clean worktree; the
#               develop pom still says 0.1.17, so it lands in $M2 under the released version number and the
#               manifest records the commit that actually produced it)
#   - solution: the local agent-solution checkout's current HEAD (branch common), built from a clean
#               worktree so uncommitted changes of others in that checkout are NOT included; the FEAT-112
#               demo directory is untracked there and is copied in, its content hash recorded
#
# Usage (from the acceptance repo root):
#   M2=D:/m2-feat112 scripts/sut/build-carrier.sh
# Optional: CORE_REPO, SOL_REPO, WORK, SKIP_FETCH=1, SKIP_BACKUP=1,
#   SOL_REF (default origin/common after a fetch: the latest common branch, built in a clean worktree so the
#   local checkout is neither touched nor needs pulling; use SOL_REF=HEAD for the checkout's own commit),
#   SKIP_CORE_BUILD=1 (reuse the core already installed in $M2 from the same origin/develop commit; recorded in the manifest)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
M2="${M2:?set M2 to the isolated Maven repository}"
CORE_REPO="${CORE_REPO:-D:/openjiuwen/agent-core-java}"
SOL_REPO="${SOL_REPO:-D:/agent-solution-common}"
STAMP="$(date +%Y%m%d-%H%M%S)"
WORK="${WORK:-D:/b112/$STAMP}"   # keep short: solution paths exceed Windows MAX_PATH under deep roots
LOG="$ROOT/target/feat112-build/$STAMP"
DEMO_REL="common/example/task-degradation-gate-acceptance-demo"
MVN_HOME="$(ls -d "$HOME"/.m2/wrapper/dists/apache-maven-3.9.16/*/ | sed -n 1p)"
MVN="$MVN_HOME/bin/mvn"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot}"
# low-memory machines: a smaller heap and C1-only JIT keep the build within the commit limit
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx1g -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=96m}"
mkdir -p "$LOG" "$WORK"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG/steps.log"; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
redact() { sed -E 's#//[^@/]*@#//REDACTED@#g'; }

say "work=$WORK m2=$M2 log=$LOG"

# 0. back up the artifacts this build overwrites (keeps the previous run reproducible)
BACKUP="$(dirname "$M2")/m2-feat112-backup-$STAMP"
[ "${SKIP_BACKUP:-0}" = "1" ] || for a in com/openjiuwen/agent-core-java com/openjiuwen/agent-core-java-reactor com/openjiuwen/example/degradation-agent-runtime \
         com/openjiuwen/example/degradation-demo-common com/openjiuwen/agent-service-adapters-agentcore-ext; do
  if [ -d "$M2/$a" ]; then mkdir -p "$BACKUP/$(dirname "$a")"; cp -r "$M2/$a" "$BACKUP/$a"; fi
done
say "backup=$BACKUP"

# 1. core: latest origin/develop in a clean worktree
if [ "${SKIP_FETCH:-0}" != "1" ]; then
  GIT_TERMINAL_PROMPT=0 timeout 120 git -C "$CORE_REPO" fetch -q origin develop 2>&1 | redact | tee -a "$LOG/steps.log" || say "core fetch failed; using existing origin/develop"
fi
CORE_COMMIT="$(git -C "$CORE_REPO" rev-parse origin/develop)"
CORE_DATE="$(git -C "$CORE_REPO" log -1 --format=%ci "$CORE_COMMIT")"
say "core commit=$CORE_COMMIT ($CORE_DATE)"
CORE_BUILD="built in this run"
if [ "${SKIP_CORE_BUILD:-0}" = "1" ]; then
  CORE_BUILD="reused from an earlier build-carrier run of the same commit"
  say "core build skipped: $CORE_BUILD"
else
  git -c core.longpaths=true -C "$CORE_REPO" worktree add --detach "$WORK/core" "$CORE_COMMIT" >>"$LOG/steps.log" 2>&1
  "$MVN" -B -ntp -f "$WORK/core/pom.xml" -Dmaven.repo.local="$M2" -DskipTests -Dformatter.skip=true install >"$LOG/core.log" 2>&1
fi
CORE_JAR="$M2/com/openjiuwen/agent-core-java/0.1.17/agent-core-java-0.1.17.jar"
say "core installed sha256=$(sha "$CORE_JAR")"

# 2. solution: local HEAD in a clean worktree + the untracked demo directory
SOL_REF="${SOL_REF:-origin/common}"
if [ "${SKIP_FETCH:-0}" != "1" ] && [ "$SOL_REF" = "origin/common" ]; then
  GIT_TERMINAL_PROMPT=0 timeout 900 git -C "$SOL_REPO" fetch -q origin common 2>&1 | redact | tee -a "$LOG/steps.log" || say "solution fetch failed; using existing origin/common"
fi
SOL_BRANCH="$SOL_REF"
SOL_COMMIT="$(git -C "$SOL_REPO" rev-parse "$SOL_REF")"
SOL_DATE="$(git -C "$SOL_REPO" log -1 --format=%ci "$SOL_COMMIT")"
SOL_LOCAL_CHANGES="$(timeout 110 git -C "$SOL_REPO" status --porcelain -uno | wc -l | tr -d ' ')"
git -c core.longpaths=true -C "$SOL_REPO" worktree add --detach "$WORK/solution" "$SOL_COMMIT" >>"$LOG/steps.log" 2>&1
git -C "$WORK/solution" config core.longpaths true
mkdir -p "$WORK/solution/$DEMO_REL"
(cd "$SOL_REPO/$DEMO_REL" && find . -path '*/target' -prune -o -type f -print | sort | tar -cf - -T -) | (cd "$WORK/solution/$DEMO_REL" && tar -xf -)
DEMO_HASH="$(cd "$WORK/solution/$DEMO_REL" && find . -type f | sort | xargs sha256sum | sha256sum | cut -d' ' -f1)"
DEMO_FILES="$(cd "$WORK/solution/$DEMO_REL" && find . -type f | wc -l | tr -d ' ')"
say "solution branch=$SOL_BRANCH commit=$SOL_COMMIT ($SOL_DATE); local tracked changes not used=$SOL_LOCAL_CHANGES; demo files=$DEMO_FILES hash=$DEMO_HASH"
"$MVN" -B -ntp -f "$WORK/solution/pom.xml" -Dmaven.repo.local="$M2" -P mechanism,business \
  -pl common/agents/deepanalyze-java/engine -am -DskipTests install >"$LOG/solution.log" 2>&1
say "solution engine chain installed"
"$MVN" -B -ntp -f "$WORK/solution/$DEMO_REL/pom.xml" -Dmaven.repo.local="$M2" -DskipTests install >"$LOG/demo.log" 2>&1
say "demo installed"

# 3. verify and record
EXT_JAR="$M2/com/openjiuwen/agent-service-adapters-agentcore-ext/0.1.3/agent-service-adapters-agentcore-ext-0.1.3.jar"
CARRIER="$M2/com/openjiuwen/example/degradation-agent-runtime/0.1.0/degradation-agent-runtime-0.1.0.jar"
# capture listings first: under pipefail, grep -q / head closing the pipe early would fail the producer
EXT_LISTING="$(unzip -l "$EXT_JAR")"
grep -q "degradation/DegradationGate.class" <<<"$EXT_LISTING" || { say "ext jar lacks DegradationGate"; exit 1; }
CARRIER_LISTING="$(unzip -l "$CARRIER")"
BUNDLED_CORE="$(grep -o 'BOOT-INF/lib/agent-core-java-[^ ]*\.jar' <<<"$CARRIER_LISTING" | sed -n 1p)"
TMP="$LOG/bundled"; mkdir -p "$TMP"; (cd "$TMP" && unzip -o -q "$CARRIER" "$BUNDLED_CORE")
BUNDLED_CORE_SHA="$(sha "$TMP/$BUNDLED_CORE")"
[ "$BUNDLED_CORE_SHA" = "$(sha "$CORE_JAR")" ] || { say "carrier bundles a core jar that is not the develop build"; exit 1; }
cat >"$M2/feat112-build-manifest.json" <<EOF
{
  "builtAt": "$(date -Iseconds)",
  "core": {"repository": "openJiuwen/agent-core-java", "ref": "origin/develop", "commit": "$CORE_COMMIT",
           "commitDate": "$CORE_DATE", "pomVersion": "0.1.17", "jarSha256": "$(sha "$CORE_JAR")", "build": "$CORE_BUILD"},
  "solution": {"repository": "agent-solution", "checkout": "$SOL_REPO", "branch": "$SOL_BRANCH", "commit": "$SOL_COMMIT",
               "commitDate": "$SOL_DATE", "builtFrom": "clean worktree of the commit",
               "localTrackedChangesExcluded": $SOL_LOCAL_CHANGES},
  "demo": {"path": "$DEMO_REL", "versionControlled": false, "files": $DEMO_FILES, "contentSha256": "$DEMO_HASH"},
  "carrier": {"coordinates": "com.openjiuwen.example:degradation-agent-runtime:0.1.0", "sha256": "$(sha "$CARRIER")",
              "bundledCore": "$BUNDLED_CORE", "bundledCoreSha256": "$BUNDLED_CORE_SHA"},
  "gateExtension": {"coordinates": "com.openjiuwen:agent-service-adapters-agentcore-ext:0.1.3", "sha256": "$(sha "$EXT_JAR")"},
  "toolchain": {"java": "$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 1p | tr -d '"')", "maven": "3.9.16"}
}
EOF
cp "$M2/feat112-build-manifest.json" "$LOG/"
say "manifest=$M2/feat112-build-manifest.json carrier sha256=$(sha "$CARRIER")"
say "DONE"
