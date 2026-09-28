/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.MavenArtifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.jar.JarFile;

/** Audits only explicitly controlled endpoints, files, and the configured external SUT artifact. */
public final class OfflineAccessAuditFixture {
    private OfflineAccessAuditFixture() {
    }

    public static AuditSnapshot snapshot(
            Map<String, IntSupplier> endpointCounters, Collection<Path> controlledRoots) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        endpointCounters.forEach((name, counter) -> counts.put(name, counter.getAsInt()));
        Map<String, FileStamp> files = new LinkedHashMap<>();
        for (Path root : controlledRoots) {
            if (root == null || !Files.exists(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    files.put(path.toAbsolutePath().normalize().toString(), stamp(path));
                }
            }
        }
        return new AuditSnapshot(Map.copyOf(counts), Map.copyOf(files));
    }

    public static Path configuredAgentJar(TestConfig config, String agentName) {
        String prefix = "sut.agents." + agentName + ".";
        MavenArtifact artifact = new MavenArtifact(
                config.getString(prefix + "group"),
                config.getString(prefix + "artifact"),
                config.getString(prefix + "version"));
        String repository = config.getString(
                "sut.m2.repo", System.getProperty("user.home") + "/.m2/repository");
        return artifact.jarPath(repository).toAbsolutePath().normalize();
    }

    public static JarAudit inspectExecutableJar(Path jarPath) throws IOException {
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException("SUT executable jar does not exist: " + jarPath);
        }
        List<String> libraries;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            libraries = jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar"))
                    .sorted()
                    .toList();
        }
        boolean hasFetch = libraries.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains("agent-core-ext-studio-dsl-fetch"));
        return new JarAudit(jarPath, libraries, hasFetch);
    }

    public static List<Path> findCanary(Collection<Path> roots, String canary) throws IOException {
        if (canary == null || canary.isBlank()) {
            throw new IllegalArgumentException("canary must not be blank");
        }
        List<Path> matches = new ArrayList<>();
        for (Path root : roots) {
            if (root == null || !Files.exists(root)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String value = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    if (value.contains(canary)) {
                        matches.add(path.toAbsolutePath().normalize());
                    }
                }
            }
        }
        return List.copyOf(matches);
    }

    private static FileStamp stamp(Path path) throws IOException {
        return new FileStamp(Files.size(path), Files.getLastModifiedTime(path).toMillis(), sha256(path));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (var input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record AuditSnapshot(Map<String, Integer> endpointCounts, Map<String, FileStamp> files) {
        public Map<String, Integer> endpointIncrementsFrom(AuditSnapshot before) {
            Map<String, Integer> increments = new LinkedHashMap<>();
            endpointCounts.forEach((name, count) ->
                    increments.put(name, count - before.endpointCounts.getOrDefault(name, 0)));
            return Map.copyOf(increments);
        }
    }

    public record FileStamp(long size, long lastModifiedMillis, String sha256) {
    }

    public record JarAudit(Path jar, List<String> bootLibraries, boolean containsFetchModule) {
    }
}
