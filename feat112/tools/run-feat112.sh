#!/usr/bin/env bash
# FEAT-112 targeted compile + run, reusing the repository's pom for dependency resolution.
# Needed while the full test tree does not compile (other features depend on undeclared in-progress
# artifacts); this compiles only the FEAT-112 sources and the shared result layer against the pom's test
# classpath, then runs them on the JUnit Platform exactly as Surefire would.
#
# Usage (from the acceptance repo root):
#   M2=/path/to/provisioned-repo feat112/tools/run-feat112.sh compile
#   M2=... feat112/tools/run-feat112.sh run <result-file> <class|class#method>...
#   M2=... feat112/tools/run-feat112.sh report [run-directory]
#   M2=... feat112/tools/run-feat112.sh snapshot   (before packaging a copy without .git)
#
# Tests launch their own carrier processes from the coordinates in application-openjiuwen.yml, resolved in $M2
# (the repository build-carrier.sh / provision-sut.sh installed into). When $M2/feat112-build-manifest.json exists
# (written by feat112/tools/build-carrier.sh) the carrier's core and solution commits come from it and a carrier that
# does not match it is refused. Without a manifest, SOURCE_REVISION (free text) is recorded instead. RUN_ID names
# the result directory.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; ROOT="$(cygpath -m "$ROOT")";; esac
OUT="$ROOT/target/feat112-run"
M2="${M2:?set M2 to the Maven repository provisioned with the FEAT-112 carrier and product build}"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) M2="$(cygpath -m "$M2")";; esac
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
# Maven: the repository wrapper by default. On a machine without network access, MVN=/path/to/bin/mvn picks a bundled
# Maven and MVN_OFFLINE=1 resolves from $M2 only.
MVN="${MVN:-./mvnw}"
MVN_FLAGS=(-B -ntp -q)
[ "${MVN_OFFLINE:-0}" = 1 ] && MVN_FLAGS+=(-o)
mkdir -p "$OUT"

# Fingerprint of the test code (src/ and pom.xml): the same on any copy of the same tree, with or without git.
sources_sha() {
  (cd "$ROOT" && find src pom.xml -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sed 's/ \*/  /' \
    | sha256sum | cut -c1-12)
}
# Commit the test code is based on: from git in a checkout; in a packaged copy (no .git) from
# feat112/test-revision.txt, written by `run-feat112.sh snapshot` before packaging.
base_revision() {
  if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    local rev; rev="$(git -C "$ROOT" rev-parse --short=12 HEAD)"
    [ -n "$(git -C "$ROOT" status --porcelain -- src feat112/tools pom.xml 2>/dev/null)" ] && rev="$rev+uncommitted"
    echo "$rev"
  elif [ -f "$ROOT/feat112/test-revision.txt" ]; then
    head -1 "$ROOT/feat112/test-revision.txt"
  else
    echo "unknown (no git checkout, no feat112/test-revision.txt)"
  fi
}

classpath() { echo "$OUT/classes$SEP$ROOT/src/test/resources$SEP$ROOT/target/classes$SEP$(cat "$OUT/test-cp.txt")"; }
# The classpath alone approaches the Windows command-line limit: pass it through a java @argfile
# (forward slashes only: a backslash inside a quoted argfile token is an escape character).
cparg() { printf -- '-cp\n"%s"\n' "$(classpath | tr '\\' '/')" > "$OUT/cp.args"; echo "@$OUT/cp.args"; }

compile() {
  cd "$ROOT"
  "$MVN" "${MVN_FLAGS[@]}" -Dmaven.repo.local="$M2" \
    compile dependency:build-classpath -Dmdep.outputFile="$OUT/test-cp.txt" -Dmdep.includeScope=test \
    -Dmdep.pathSeparator="$SEP"
  rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
  local base="$ROOT/src/test/java/com/huawei/ascend/sit"
  find "$base/judge" "$base/base" "$base/fixtures/task_degradation_gate" \
       "$base/cases/integration/task_degradation_gate" \
       -name '*.java' > "$OUT/sources.txt"
  printf '%s\n' "$base/lifecycle/ProcessLauncherTest.java" "$ROOT/feat112/tools/RunSelected.java" >> "$OUT/sources.txt"
  javac -proc:none -encoding UTF-8 -cp "$ROOT/target/classes$SEP$(cat "$OUT/test-cp.txt")" \
    -d "$OUT/classes" @"$OUT/sources.txt"
  echo "compiled $(wc -l < "$OUT/sources.txt") sources into $OUT/classes"
}

run() {
  cd "$ROOT"   # evidence, results and the testplan are resolved relative to the repo root
  local result="$1"; shift
  # Provenance written into every result record and the report header (RunProvenance): test code revision,
  # contract baseline the expectations cite, testplan revision, and the carrier build manifest when present.
  local prov=()
  prov+=("-Dacceptance.provenance.test-revision=$(base_revision), sources sha256 $(sources_sha)")
  prov+=("-Dacceptance.provenance.contract-baseline=FEAT-112 feature.md@05af9cc3")
  local tp="$ROOT/feat112/testplan-revision-20261112.revised.md"
  if [ -f "$tp" ]; then
    prov+=("-Dacceptance.provenance.testplan=docs main a1db9d86 + local revision sha256 $(sha256sum "$tp" | cut -c1-12)")
  fi
  local manifest="$M2/feat112-build-manifest.json"
  if [ -f "$manifest" ]; then
    prov+=("-Dfeat112-build-manifest=$manifest")
    prov+=("-Dacceptance.provenance.build-manifest=$manifest sha256 $(sha256sum "$manifest" | cut -c1-12)")
  fi
  # shellcheck disable=SC2086
  java ${JAVA_OPTS:-} -Dstdout.encoding=UTF-8 -Dtest.env="${TEST_ENV:-openjiuwen}" -Dsut-m2-repo="$M2" \
    -Dfeat112-source-revision="${SOURCE_REVISION:-}" -Dacceptance.run-id="$RUN_ID" "${prov[@]}" \
    "$(cparg)" RunSelected "$result" "$@" || status=$?
  report "$ROOT/target/acceptance-results/$RUN_ID" || true
  return "${status:-0}"
}

report() {
  cd "$ROOT"
  local dir="${1:-$(ls -d "$ROOT"/target/acceptance-results/* | tail -1)}"
  java -Dstdout.encoding=UTF-8 "$(cparg)" com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112Coverage "$dir"
}

case "${1:-}" in
  compile) compile ;;
  run) shift; run "$@" ;;
  report) shift; report "$@" ;;
  fingerprints) cd "$ROOT"; java -Dstdout.encoding=UTF-8 "$(cparg)" com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112Coverage --write-fingerprints ;;
  # before packaging a copy without .git: record the base commit and the sources fingerprint it was taken at
  snapshot) printf '%s
snapshot %s, sources sha256 %s
' "$(base_revision)" "$(date +%Y-%m-%dT%H:%M:%S%z)" "$(sources_sha)"               > "$ROOT/feat112/test-revision.txt"; cat "$ROOT/feat112/test-revision.txt" ;;
  *) echo "usage: $0 compile | run <result-file> <selectors...> | report [run-directory] | fingerprints | snapshot" >&2; exit 2 ;;
esac
