#!/usr/bin/env bash
# m2path.sh — compute the .m2 jar path for a Maven coordinate.
# Bash parity of com.huawei.ascend.sit.lifecycle.MavenArtifact#jarPath:
#   Path.of(root, groupId.replace('.', '/'), artifactId, version, artifactId + "-" + version + ".jar")
# Usage: m2_jar_path <m2-root> <group> <artifact> <version>
m2_jar_path() {
  local m2root="$1" group="$2" artifact="$3" version="$4"
  local group_slashed="${group//.//}"   # replace every '.' with '/'
  printf '%s/%s/%s/%s/%s-%s.jar\n' \
    "$m2root" "$group_slashed" "$artifact" "$version" "$artifact" "$version"
}
