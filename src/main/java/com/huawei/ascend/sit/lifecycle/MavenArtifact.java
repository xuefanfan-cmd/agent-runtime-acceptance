package com.huawei.ascend.sit.lifecycle;

import java.nio.file.Path;

/**
 * Maven coordinates that resolve to a jar inside the local repository.
 *
 * <p>The framework launches SUT agents from jars in the local Maven repository
 * only — never from {@code third_party}. {@code third_party} exists for code
 * analysis; the local repository is the single source of truth for buildable
 * artifacts. Resolving by coordinates (rather than a hardcoded path) keeps the
 * SUT definition declarative in {@code application-*.yml} and decoupled from
 * where the build happens to lay files down.
 */
public record MavenArtifact(String groupId, String artifactId, String version) {

    public MavenArtifact {
        requireNonBlank(groupId, "groupId");
        requireNonBlank(artifactId, "artifactId");
        requireNonBlank(version, "version");
    }

    /** Parse a {@code groupId:artifactId:version} coordinate string. */
    public static MavenArtifact parse(String gav) {
        String[] parts = gav.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Expected groupId:artifactId:version but got: " + gav);
        }
        return new MavenArtifact(parts[0].trim(), parts[1].trim(), parts[2].trim());
    }

    /**
     * Absolute path of this artifact's jar inside the given local repository root.
     *
     * @param m2RepoRoot local repository root (e.g. {@code ~/.m2/repository}), no trailing slash
     */
    public Path jarPath(String m2RepoRoot) {
        return Path.of(m2RepoRoot,
                groupId.replace('.', '/'),
                artifactId,
                version,
                artifactId + "-" + version + ".jar");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
