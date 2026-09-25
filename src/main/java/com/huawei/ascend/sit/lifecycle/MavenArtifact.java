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
public record MavenArtifact(String groupId, String artifactId, String version, String classifier) {

    /**
     * Classifier-less artifact (the common case): resolves to {@code <artifactId>-<version>.jar}.
     */
    public MavenArtifact(String groupId, String artifactId, String version) {
        this(groupId, artifactId, version, null);
    }

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
     * <p>When a {@linkplain #classifier() classifier} is declared the file name becomes
     * {@code <artifactId>-<version>-<classifier>.jar}. Host agents that package their runnable
     * jar under a classifier (Spring Boot's {@code exec} is the common one, used by both
     * edp-agent-java and deepanalyze-java) leave the plain jar non-executable, so the framework
     * must be told which file to launch.
     *
     * @param m2RepoRoot local repository root (e.g. {@code ~/.m2/repository}), no trailing slash
     */
    public Path jarPath(String m2RepoRoot) {
        String fileName = classifier == null || classifier.isBlank()
                ? artifactId + "-" + version + ".jar"
                : artifactId + "-" + version + "-" + classifier + ".jar";
        return Path.of(m2RepoRoot,
                groupId.replace('.', '/'),
                artifactId,
                version,
                fileName);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
