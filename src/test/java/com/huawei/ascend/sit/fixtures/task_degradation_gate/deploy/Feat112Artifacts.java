package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.judge.ArtifactIdentity;
import com.huawei.ascend.sit.lifecycle.MavenArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The provisioned FEAT-112 carrier (DA host, gate extension and demo stub in one executable jar), resolved from
 * the same {@code sut.agents.<agent>} coordinates SutStack launches, with its checksum recorded.
 *
 * <p>Build source: when {@code feat112.build-manifest} names the manifest written by
 * {@code scripts/sut/build-carrier.sh}, the core and solution commits come from it, and a carrier whose checksum
 * differs from the manifest is refused (the manifest would describe another build). Without a manifest the
 * free-text {@code feat112.source-revision} is used as before.</p>
 */
public record Feat112Artifacts(Path jar, ArtifactIdentity identity) {
    public static Feat112Artifacts resolve(TestConfig config, String agent) throws IOException {
        String base = "sut.agents." + agent;
        String group = config.getString(base + ".group");
        String artifact = config.getString(base + ".artifact");
        String version = config.getString(base + ".version");
        if (group == null || artifact == null || version == null) {
            throw new IllegalStateException(base + ".group/artifact/version must be declared in application-<env>.yml "
                    + "(FEAT-112 carrier, provisioned by scripts/sut/provision-sut.sh)");
        }
        String classifier = config.getString(base + ".classifier");
        MavenArtifact coordinates = new MavenArtifact(group, artifact, version, classifier);
        String m2 = config.getString("sut.m2.repo", System.getProperty("user.home") + "/.m2/repository");
        Path jar = coordinates.jarPath(m2);
        String gav = group + ":" + artifact + ":" + version + (classifier == null || classifier.isBlank() ? "" : ":" + classifier);
        String role = "FEAT-112 carrier (DA host + gate ext + demo stub)";
        String manifest = config.getString("feat112.build-manifest");
        if (manifest == null || manifest.isBlank()) {
            return new Feat112Artifacts(jar, ArtifactIdentity.ofFile(role, gav, jar, config.getString("feat112.source-revision")));
        }
        JsonNode built = new ObjectMapper().readTree(Files.readString(Path.of(manifest)));
        ArtifactIdentity identity = ArtifactIdentity.ofFile(role, gav, jar, sourceRevision(built, manifest));
        String expected = built.path("carrier").path("sha256").asText();
        if (!identity.sha256().equals(expected)) {
            throw new IllegalStateException("carrier " + jar + " sha256 " + identity.sha256() + " does not match build manifest "
                    + manifest + " (" + expected + "); rebuild with scripts/sut/build-carrier.sh or drop the stale manifest");
        }
        return new Feat112Artifacts(jar, identity);
    }

    static String sourceRevision(JsonNode built, String manifest) {
        JsonNode solution = built.path("solution");
        JsonNode core = built.path("core");
        JsonNode demo = built.path("demo");
        return "solution " + solution.path("branch").asText() + "@" + shortSha(solution.path("commit").asText())
                + " (" + solution.path("builtFrom").asText() + ")"
                + "; demo " + (demo.path("versionControlled").asBoolean() ? "versioned" : "untracked, content "
                + shortSha(demo.path("contentSha256").asText()))
                + "; core " + core.path("ref").asText() + "@" + shortSha(core.path("commit").asText())
                + "; manifest " + manifest;
    }

    private static String shortSha(String sha) {
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }
}
