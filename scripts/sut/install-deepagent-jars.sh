#!/usr/bin/env bash
#
# install-deepagent-jars.sh — 把本地发布目录里的 agent fat jar 装进 ~/.m2，供 managed 模式启动。
#
# 框架只从本地 Maven 仓按 <group>/<artifact>/<version> 坐标解析 jar
# （ProcessLauncher，仓根可用 sut.m2.repo 覆盖），不认任意目录。所以拿到 dist 目录的发布件后，
# 先用本脚本 install-file 一次，application-local.yml 里声明的 deep-research / search / verify
# 三个 agent 即可直接 managed 启动，无需改任何 YAML 坐标。
#
# 用法：
#   ./scripts/sut/install-deepagent-jars.sh                       # 默认 dist 目录，默认版本 0.1.0
#   ./scripts/sut/install-deepagent-jars.sh /path/to/dist         # 指定 dist 目录
#   AGENT_VERSION=0.1.1 ./scripts/sut/install-deepagent-jars.sh    # 指定版本（需同步改 YAML 的 version）
#
# 幂等：重复执行只是覆盖同坐标的 jar。

set -euo pipefail

dist_dir="${1:-D:/agent-solution-common/dist}"
group_id="${AGENT_GROUP:-com.openjiuwen.example}"
version="${AGENT_VERSION:-0.1.0}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null || printf '%s' "$script_dir/../..")"

if [ ! -d "$dist_dir" ]; then
    printf '❌ dist 目录不存在: %s\n' "$dist_dir" >&2
    printf '   用法: %s [dist目录]\n' "$0" >&2
    exit 1
fi

mvn_cmd="$repo_root/mvnw"
[ -x "$mvn_cmd" ] || mvn_cmd="mvn"

# artifactId 与 dist 下的文件名一一对应：agent-<name>-<version>.jar
artifacts="agent-deep-research agent-search agent-verify"

installed=0
for artifact in $artifacts; do
    jar="$dist_dir/$artifact-$version.jar"
    if [ ! -f "$jar" ]; then
        printf '⚠️  跳过（文件不存在）: %s\n' "$jar" >&2
        continue
    fi
    printf '→ install %s:%s:%s\n' "$group_id" "$artifact" "$version"
    "$mvn_cmd" -q org.apache.maven.plugins:maven-install-plugin:3.1.1:install-file \
        -Dfile="$jar" \
        -DgroupId="$group_id" \
        -DartifactId="$artifact" \
        -Dversion="$version" \
        -Dpackaging=jar
    installed=$((installed + 1))
done

if [ "$installed" -eq 0 ]; then
    printf '❌ 没有装入任何 jar，检查 dist 目录与版本号\n' >&2
    exit 1
fi

printf '\n✅ 已装入 %s 个 jar。接下来：\n' "$installed"
printf '   cp scripts/sut/deepagent.env.example .env.local && 填入真实密钥\n'
printf '   ./scripts/sut/run-deepagent-local.sh -Dgroups=feat-001\n'
