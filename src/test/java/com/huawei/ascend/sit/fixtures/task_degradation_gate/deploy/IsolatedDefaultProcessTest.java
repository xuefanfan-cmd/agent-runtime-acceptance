package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("selftest")
class IsolatedDefaultProcessTest {
    @Test void stripsEveryUnknownEnvironmentChannel() {
        var clean = IsolatedDefaultProcess.cleanEnvironment(Map.of("PATH", "safe", "JAVA_TOOL_OPTIONS", "-Dsecret=x",
                "SPRING_APPLICATION_JSON", "{}", "OPENJIUWEN_SERVICE_DEGRADATION_GATE_ENABLED", "false",
                "CUSTOM_CONFIG_PATH", "anything", "JDK_JAVA_OPTIONS", "-Dtest=x"));
        assertEquals(Map.of("PATH", "safe"), clean);
    }
    @Test void keepsLocaleSoChildPathsStayUtf8() {
        var clean = IsolatedDefaultProcess.cleanEnvironment(Map.of("LANG", "en_US.UTF-8", "LC_ALL", "C.UTF-8",
                "JAVA_TOOL_OPTIONS", "-Dsecret=x"));
        assertEquals(Map.of("LANG", "en_US.UTF-8", "LC_ALL", "C.UTF-8"), clean);
    }
    @Test void refusesRelaxedBindingAndConfigIndirection() {
        for (String key : List.of("openjiuwen.service.degradation-gate.enabled", "OPENJIUWEN_SERVICE_DEGRADATIONGATE_ENABLED",
                "spring.config.import", "spring.profiles.active", "spring.application.json")) {
            Properties p = new Properties(); p.setProperty(key, "value");
            assertThrows(IllegalArgumentException.class, () -> IsolatedDefaultProcess.validate(p));
        }
        Properties p = new Properties(); p.setProperty("deepanalyze.model.base-url", "http://127.0.0.1:1");
        assertDoesNotThrow(() -> IsolatedDefaultProcess.validate(p));
    }
    @Test void ownsAndStopsLocalJvmAndRedactsOutput() throws Exception {
        Path directory = Files.createTempDirectory("feat112-child-test-");
        String classpath = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        long pid;
        try (var child = new IsolatedDefaultProcess(List.of(IsolatedDefaultProcess.javaExecutable(), "-cp", classpath,
                Child.class.getName()), directory, List.of("local-secret"))) {
            pid = child.pid(); long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (!child.diagnostics().contains("READY") && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(child.isAlive()); assertTrue(child.diagnostics().contains("READY"));
            assertFalse(child.diagnostics().contains("local-secret"));
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertFalse(Files.exists(directory));
    }
    @Test void interruptedCloseStillReapsRealChildAndRestoresInterrupt() throws Exception {
        Path directory = Files.createTempDirectory("feat112-interrupt-child-");
        String classpath = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var child = new IsolatedDefaultProcess(List.of(IsolatedDefaultProcess.javaExecutable(), "-cp", classpath,
                Child.class.getName()), directory, List.of("local-secret"));
        try {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (!child.diagnostics().contains("READY") && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(child.diagnostics().contains("READY"));
            Thread.currentThread().interrupt();
            child.close();
            assertTrue(Thread.currentThread().isInterrupted(), "cleanup must restore caller interrupt");
            assertFalse(child.isAlive(), "interrupt must not strand owned child");
            assertFalse(Files.exists(directory));
        } finally {
            Thread.interrupted();
            if (child.isAlive()) child.close();
        }
    }
    @Test void watchedMarkerIsSeenBeyondTheCaptureLimit() throws Exception {
        Path directory = Files.createTempDirectory("feat112-flood-child-");
        String classpath = Path.of(Flood.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var child = new IsolatedDefaultProcess(List.of(IsolatedDefaultProcess.javaExecutable(), "-cp", classpath,
                Flood.class.getName()), directory, List.of())) {
            child.watch("FLOOD-MARKER");
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            while (!child.observed("FLOOD-MARKER") && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(child.observed("FLOOD-MARKER"), "marker after 1.2MB of output must still be observed");
            assertEquals("Startup evidence truncated", child.diagnostics(), "bounded capture itself stays bounded");
            assertThrows(IllegalStateException.class, () -> child.observed("never-watched"));
        }
    }
    public static final class Flood {
        public static void main(String[] args) throws Exception {
            Thread.sleep(1000);
            String line = "x".repeat(1023) + System.lineSeparator();
            for (int i = 0; i < 1200; i++) System.out.print(line);
            System.out.print("FLOOD-");
            System.out.flush();
            Thread.sleep(50);
            System.out.println("MARKER");
            System.out.flush();
            Thread.sleep(60000);
        }
    }
    public static final class Child {
        public static void main(String[] args) throws Exception {
            System.out.println("READY local-secret"); System.out.flush(); Thread.sleep(60000);
        }
    }
}
