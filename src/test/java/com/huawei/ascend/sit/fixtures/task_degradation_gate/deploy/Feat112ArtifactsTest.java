package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import com.huawei.ascend.sit.config.TestConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Self-tests of carrier provenance: the build manifest names the commits, and a carrier it does not describe is refused. */
@Tag("selftest")
class Feat112ArtifactsTest {
    private static final String AGENT = "feat112-selftest";

    @Test
    void manifestSuppliesBuildSourceAndMismatchIsRefused(@TempDir Path dir) throws Exception {
        Path m2 = dir.resolve("m2");
        Path jar = m2.resolve("com/example/carrier/1.0/carrier-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "carrier bytes");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        Path good = Files.writeString(dir.resolve("good.json"), manifest(sha));
        Path stale = Files.writeString(dir.resolve("stale.json"), manifest("0".repeat(64)));

        Map<String, String> props = new LinkedHashMap<>();
        props.put("sut-agents-" + AGENT + "-group", "com.example");
        props.put("sut-agents-" + AGENT + "-artifact", "carrier");
        props.put("sut-agents-" + AGENT + "-version", "1.0");
        props.put("sut-m2-repo", m2.toString());
        props.put("feat112-build-manifest", good.toString());
        Map<String, String> previous = new LinkedHashMap<>();
        props.forEach((k, v) -> previous.put(k, System.getProperty(k)));
        try {
            props.forEach(System::setProperty);
            var resolved = Feat112Artifacts.resolve(TestConfig.load(), AGENT);
            assertEquals(sha, resolved.identity().sha256());
            String revision = resolved.identity().sourceRevision();
            assertTrue(revision.contains("solution common@6bf40476c111"), revision);
            assertTrue(revision.contains("core origin/develop@0db55c68e222"), revision);
            assertTrue(revision.contains("demo untracked, content abcdefabcdef"), revision);

            System.setProperty("feat112-build-manifest", stale.toString());
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> Feat112Artifacts.resolve(TestConfig.load(), AGENT));
            assertTrue(refused.getMessage().contains("does not match build manifest"), refused.getMessage());
        } finally {
            previous.forEach((k, v) -> {
                if (v == null) System.clearProperty(k); else System.setProperty(k, v);
            });
        }
    }

    private static String manifest(String carrierSha) {
        return """
                {"core": {"ref": "origin/develop", "commit": "0db55c68e2220000"},
                 "solution": {"branch": "common", "commit": "6bf40476c1110000", "builtFrom": "clean worktree of the commit"},
                 "demo": {"versionControlled": false, "contentSha256": "abcdefabcdef0000"},
                 "carrier": {"sha256": "%s"}}
                """.formatted(carrierSha);
    }
}
