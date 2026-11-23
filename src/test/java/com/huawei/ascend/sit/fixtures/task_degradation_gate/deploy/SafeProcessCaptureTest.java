package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SafeProcessCaptureTest {
    @TempDir Path directory;
    public static class Child {
        public static void main(String[] args) {
            if (args.length > 0) { System.out.print("x".repeat(1048570) + "boundary-secret-value"); return; }
            System.out.println("known-value password=hunter2 api_key=abc token=xyz");
            System.exit(7);
        }
    }
    @Test void onlyRedactedBoundedOutputIsPersisted() throws Exception {
        var result = SafeProcessCapture.run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName()), directory,
                Duration.ofSeconds(10), List.of("known-value"));
        assertEquals(7, result.exit()); assertFalse(result.truncated());
        String log = Files.readString(directory.resolve("startup-redacted.log"));
        for (String secret : List.of("known-value", "hunter2", "abc", "xyz")) assertFalse(log.contains(secret));
        assertFalse(Files.exists(directory.resolve("startup.log")));
    }
    @Test void quotedJsonAndBearerAreFullyRedacted() {
        String result = SafeProcessCapture.redact("{\"password\":\"json-secret\"}\nAuthorization: Bearer bearer-secret\napi_key='quoted-secret'", List.of());
        for (String secret : List.of("json-secret", "bearer-secret", "quoted-secret", "Bearer")) assertFalse(result.contains(secret));
    }
    @Test void truncatedSecretBoundaryPersistsNoBody() throws Exception {
        var result = SafeProcessCapture.run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), "large"), directory,
                Duration.ofSeconds(10), List.of("boundary-secret-value"));
        assertTrue(result.truncated()); assertEquals("", result.redacted());
        assertFalse(Files.exists(directory.resolve("startup-redacted.log")));
        assertTrue(Files.readString(directory.resolve("process.properties")).contains("truncated=true"));
    }
    @Test void cleanupFailureIsSuppressedOntoPrimary() {
        Exception primary = new Exception("primary");
        Exception actual = assertThrows(Exception.class, () -> {
            try (AutoCloseable cleanup = () -> { throw new Exception("cleanup"); }) { throw primary; }
        });
        assertSame(primary, actual); assertEquals("cleanup", actual.getSuppressed()[0].getMessage());
    }
}
