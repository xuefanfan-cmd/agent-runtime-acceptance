package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.Feat112Artifacts;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.Feat112Integration;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.GateDeployment;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.IsolatedDefaultProcess;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.SafeProcessCapture;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.SlowSseConsumer;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.SustainedModelStream;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * Process-level scenarios that need full control of how the carrier is launched: no configuration channel but
 * the declared one (enabled key absent, packaged delivered configuration, invalid binding, missing service secret),
 * and a sustained producer against a slow socket.
 */
@Feat112Integration
class Feat112ProcessConfigurationTest {
    private static Feat112Artifacts carrier(Scenario s) throws Exception {
        var artifacts = Feat112Artifacts.resolve(TestConfig.load(), GateDeployment.AGENT);
        s.artifact(artifacts.identity());
        s.infrastructure("carrier jar provisioned", Files.isRegularFile(artifacts.jar()), EvidenceSource.PROCESS, artifacts.jar().toString());
        return artifacts;
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static DaEvidenceClient awaitDiagnostic(Scenario s, IsolatedDefaultProcess process, int port, String secret,
                                                    String caseId) throws Exception {
        var client = new DaEvidenceClient("http://127.0.0.1:" + port, secret, caseId);
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (true) {
            if (!process.isAlive()) {
                s.infrastructure("owned isolated process stays up", false, EvidenceSource.PROCESS, process.diagnostics());
            }
            try {
                if (client.get("/v1/health/detail").status() == 200) return client;
            } catch (java.io.IOException notYet) {
                // bounded startup wait only
            }
            if (System.nanoTime() >= deadline) {
                s.infrastructure("isolated process serves diagnostics within 120s", false, EvidenceSource.PROCESS, process.diagnostics());
            }
            Thread.sleep(100);
        }
    }

    private static final String GATE = "openjiuwen.service.degradation-gate.";
    /** Environment variables that would override the configuration under test (Spring relaxed binding). */
    private static final List<String> ENABLED_ENV = List.of("OPENJIUWEN_SERVICE_DEGRADATIONGATE_ENABLED",
            "OPENJIUWEN_SERVICE_DEGRADATION_GATE_ENABLED");

    @Test
    @Covers(value = "FEAT-112-A1", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-A1] enabled 键缺席（ext 未显式开启）时不产生降级：诊断 enabled=false，连续系统异常不拒收")
    void enabledKeyAbsentLeavesGateOff(Scenario s) throws Exception {
        var artifacts = carrier(s);
        // The model endpoint refuses connections, so every task ends in a system failure after the product's retries.
        Properties properties = IsolatedDefaultProcess.deliveredWithoutGateKeys("http://127.0.0.1:9/v1");
        properties.setProperty(GATE + "min-sample-size", "1");
        s.precondition("enabled key absent from the only configuration channel, packaged configuration not read",
                properties.stringPropertyNames().stream().noneMatch(k -> k.endsWith("degradation-gate.enabled")),
                EvidenceSource.PROCESS, properties.stringPropertyNames().toString());
        int port = freePort();
        try (var process = IsolatedDefaultProcess.launch(artifacts.jar(), properties, port)) {
            var client = awaitDiagnostic(s, process, port, IsolatedDefaultProcess.secret(properties), "FEAT-112-A1");
            var initial = client.get("/v1/health/detail");
            JsonNode gate = initial.json().path("degradationGate");
            Expect.equal(s, s.id(), "enabled", "能力表“配置与默认值”/§3.3/验收出口1", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, initial.evidenceRef(), gate.path("enabled").asText(), "false");
            GateSnapshotChecks.of(s, s.id(), "initial", gate, initial.evidenceRef()).state("CLOSED", "§3.2").samples(0, "§3.2");
            for (int i = 1; i <= 2; i++) {
                var run = client.post("/api/agents/run", Map.of("sessionId", "feat112-a1-" + UUID.randomUUID(),
                        "input", "Reply with a short greeting"));
                String code = run.status() == 503 ? run.json().path("code").asText() : "";
                if (!"ENGINE_DEGRADED".equals(code)) {
                    s.activation("task " + i + " ended in a system failure (model endpoint unreachable)",
                            run.status() == 500 && "failed".equals(run.json().path("status").asText()), EvidenceSource.HTTP, run.evidenceRef());
                }
                Expect.that(s, s.id(), "notRefused" + i, "§3.3/验收出口1", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                        run.evidenceRef(), run.status() + " " + code, "not 503 ENGINE_DEGRADED", v -> !v.equals("503 ENGINE_DEGRADED"));
            }
            var after = client.get("/v1/health/detail");
            Expect.equal(s, s.id(), "enabledAfterFailures", "能力表“配置与默认值”/§3.3/验收出口1", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, after.evidenceRef(), after.json().path("degradationGate").path("enabled").asText(), "false");
        }
    }

    @Test
    @Covers(value = "FEAT-112-A2", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-A2] 随包交付配置显式开启：诊断 enabled=true、CLOSED、零计数")
    void deliveredConfigurationEnablesGate(Scenario s) throws Exception {
        var artifacts = carrier(s);
        Properties overlay = IsolatedDefaultProcess.packagedOverlay("http://127.0.0.1:9/v1");
        s.precondition("packaged configuration read; the deployment sets no enabled key (overlay keys "
                        + overlay.stringPropertyNames() + "; launch environment reduced to OS basics)",
                overlay.stringPropertyNames().stream().noneMatch(k -> k.contains("degradation-gate")),
                EvidenceSource.PROCESS, overlay.stringPropertyNames().toString());
        int port = freePort();
        try (var process = IsolatedDefaultProcess.launchPackaged(artifacts.jar(), overlay, port)) {
            var client = awaitDiagnostic(s, process, port, IsolatedDefaultProcess.secret(overlay), "FEAT-112-A2");
            var response = client.get("/v1/health/detail");
            var gate = response.json().path("degradationGate");
            GateSnapshotChecks.of(s, s.id(), "delivered", gate, response.evidenceRef())
                    .state("CLOSED", "能力表“配置与默认值”/§3.3").samples(0, "验收出口16");
            Expect.equal(s, s.id(), "enabled", "能力表“配置与默认值”/§3.3/验收出口1", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, response.evidenceRef(), gate.path("enabled").asText(), "true");
        }
    }

    @Test
    @Covers(value = "FEAT-112-G13", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G13] 交付默认配置缺服务间密钥：启动失败并指出缺失键")
    void missingEngineSecretFailsStartup(Scenario s) throws Exception {
        var artifacts = carrier(s);
        List<String> overriding = new ArrayList<>(List.of("DA_ENGINE_SECRET", "DEEPANALYZE_AUTH_ENABLED",
                "DEEPANALYZE_AUTH_ENGINESECRET", "DEEPANALYZE_AUTH_ENGINE_SECRET"));
        overriding.addAll(ENABLED_ENV);
        List<String> present = System.getenv().keySet().stream()
                .filter(k -> overriding.stream().anyMatch(k::equalsIgnoreCase)).toList();
        s.precondition("no secret or authentication switch in the inherited environment", present.isEmpty(),
                EvidenceSource.PROCESS, "inherited: " + present);
        Files.createDirectories(Path.of("target"));
        Path directory = Files.createTempDirectory(Path.of("target"), "feat112-g13-");
        List<String> secrets = new ArrayList<>();
        System.getenv().forEach((name, secret) -> {
            if (name.toLowerCase(Locale.ROOT).matches(".*(secret|password|token|api.?key).*")) secrets.add(secret);
        });
        // Packaged configuration as delivered; only the secret is missing (model key and checkpointer supplied).
        var result = SafeProcessCapture.run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar", artifacts.jar().toAbsolutePath().toString(), "--server.port=0",
                        "--deepanalyze.model.backend.api_key=feat112-test-stub-only",
                        "--openjiuwen.service.middleware.checkpointer.type=in_memory"),
                directory, Duration.ofSeconds(120), secrets);
        s.activation("startup output captured completely", !result.truncated(), EvidenceSource.PROCESS, directory.toString());
        String clauses = "能力表“不破坏探针与认证”/验收出口16";
        Expect.that(s, s.id(), "startupFails", clauses, Category.EXTERNAL_CONTRACT, EvidenceSource.PROCESS,
                directory.toString(), result.exit(), "nonzero exit", exit -> exit != 0);
        Expect.that(s, s.id(), "namesMissingKey", clauses, Category.EXTERNAL_CONTRACT, EvidenceSource.PROCESS,
                directory.toString(), result.redacted(), "output names deepanalyze.auth.engine-secret",
                text -> text.contains("deepanalyze.auth.engine-secret"));
    }

    @Test
    @Covers(value = "FEAT-112-A10", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-A10] 非法 enabled 布尔绑定启动失败并指出键名")
    void invalidBooleanFailsStartup(Scenario s) throws Exception {
        invalidSetting(s, s.id(), "enabled", "yesnot", "§3.3/验收出口19");
    }

    @Test
    @Covers(value = {"FEAT-112-A3", "FEAT-112-A4", "FEAT-112-A5-zero", "FEAT-112-A5-gt1", "FEAT-112-A5-nan", "FEAT-112-A6"},
            layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-A3/A4/A5-zero/A5-gt1/A5-nan/A6] 非法窗口、最小样本、阈值与静默期启动失败并指出键名")
    void invalidNumericSettingsFailStartup(Scenario s) throws Exception {
        invalidSetting(s, "FEAT-112-A3", "window", "0s", "§3.3/验收出口19");
        invalidSetting(s, "FEAT-112-A4", "min-sample-size", "0", "§3.3/验收出口19");
        invalidSetting(s, "FEAT-112-A5-zero", "failure-rate-threshold", "0", "§3.3/验收出口19");
        invalidSetting(s, "FEAT-112-A5-gt1", "failure-rate-threshold", "1.5", "§3.3/验收出口19");
        invalidSetting(s, "FEAT-112-A5-nan", "failure-rate-threshold", "NaN", "§3.3/验收出口19");
        invalidSetting(s, "FEAT-112-A6", "open-quiet-period", "-1s", "§3.3/验收出口19");
    }

    /** Starts the carrier with one illegal gate setting and records that startup fails naming the full key. */
    private void invalidSetting(Scenario s, String id, String key, String value, String clauses) throws Exception {
        var artifacts = carrier(s);
        Files.createDirectories(Path.of("target"));
        Path directory = Files.createTempDirectory(Path.of("target"), "feat112-invalid-");
        List<String> secrets = new ArrayList<>();
        System.getenv().forEach((name, secret) -> {
            if (name.toLowerCase(Locale.ROOT).matches(".*(secret|password|token|api.?key).*")) secrets.add(secret);
        });
        String fullKey = "openjiuwen.service.degradation-gate." + key;
        // The packaged (delivered) configuration is read here: its service authentication needs a secret, its model
        // backend an API key (fail-fast when incomplete) and its checkpointer a Redis, so all three are satisfied and
        // the gate key is the only illegal input.
        String engineSecret = "feat112-" + UUID.randomUUID();
        secrets.add(engineSecret);
        var result = SafeProcessCapture.run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar", artifacts.jar().toAbsolutePath().toString(), "--server.port=0",
                        "--deepanalyze.auth.engine-secret=" + engineSecret,
                        "--deepanalyze.model.backend.api_key=feat112-test-stub-only",
                        "--openjiuwen.service.middleware.checkpointer.type=in_memory",
                        "--" + fullKey + "=" + value),
                directory, Duration.ofSeconds(120), secrets);
        s.activation(id + ": startup output captured completely", !result.truncated(), EvidenceSource.PROCESS, directory.toString());
        Expect.that(s, id, "startupFails", clauses, Category.EXTERNAL_CONTRACT, EvidenceSource.PROCESS,
                directory.toString(), result.exit(), "nonzero exit", exit -> exit != 0);
        Expect.that(s, id, "namesKey", clauses, Category.EXTERNAL_CONTRACT, EvidenceSource.PROCESS,
                directory.toString(), result.redacted(), "output names " + fullKey, text -> text.contains(fullKey));
    }

    @Test
    @Covers(value = "FEAT-112-J4", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-J4] 慢消费者触发订阅溢出，任务终态与记账不变")
    void slowConsumerDoesNotChangeClassification(Scenario s) throws Exception {
        var artifacts = carrier(s);
        int port = freePort();
        try (var source = new SustainedModelStream(2048, 1024)) {
            Properties config = IsolatedDefaultProcess.deliveredWithoutGateKeys(source.baseUrl());
            config.setProperty("openjiuwen.service.degradation-gate.window", "10m");
            // The live subscriber queue holds max-events-per-task events (default 5000); a small bound makes the
            // slow socket actually overflow it within the observation instead of merely buffering.
            config.setProperty("deepanalyze.compat.max-events-per-task", "64");
            // Delivered default buffers a whole model reply before publishing it (answer-gate stream buffering), so a
            // long reply reaches the subscriber queue only at its end; per-delta publishing is what a slow consumer
            // can fall behind on. The answer gate itself stays on.
            config.setProperty("deepanalyze.answer-gate.stream-buffering-enabled", "false");
            config.setProperty("logging.level.com.openjiuwen.da.compat.DaCompatController", "DEBUG");
            try (var process = IsolatedDefaultProcess.launch(artifacts.jar(), config, port)) {
                var client = awaitDiagnostic(s, process, port, IsolatedDefaultProcess.secret(config), "FEAT-112-J4");
                var beforeResponse = client.get("/v1/health/detail");
                var before = beforeResponse.json().path("degradationGate");
                s.precondition("gate enabled, CLOSED and empty", before.path("enabled").asBoolean() && "CLOSED".equals(before.path("state").asText())
                        && before.path("sampleCount").asLong(-1) == 0, EvidenceSource.DIAGNOSTIC_SNAPSHOT, beforeResponse.evidenceRef());
                List<String> overflowMarkers = List.of("sse subscriber queue overflow, disconnecting client",
                        "sse live pump disconnected slow client (queue overflow)");
                overflowMarkers.forEach(process::watch);
                s.precondition("no earlier overflow in this fresh process", overflowMarkers.stream().noneMatch(process::observed),
                        EvidenceSource.LOG, "process output (watched over the whole stream)");
                String session = "feat112-pressure-" + UUID.randomUUID();
                try (var slow = new SlowSseConsumer(URI.create("http://127.0.0.1:" + port + "/api/agents/run-stream"),
                        "{\"sessionId\":\"" + session + "\",\"input\":\"produce sustained output\"}", IsolatedDefaultProcess.secret(config))) {
                    slow.consume(Duration.ofSeconds(10), Duration.ofMillis(200));
                    s.activation("sustained model frames produced", source.emittedFrames() >= 1024, EvidenceSource.STUB,
                            "frames=" + source.emittedFrames());
                    s.activation("producer outpaced the slow consumer", source.emittedBytes() > slow.bytesRead() * 100,
                            EvidenceSource.STUB, "emitted=" + source.emittedBytes() + " read=" + slow.bytesRead());
                    long markerDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (overflowMarkers.stream().noneMatch(process::observed) && System.nanoTime() < markerDeadline) Thread.sleep(100);
                    s.activation("DA subscriber overflow actually occurred", overflowMarkers.stream().anyMatch(process::observed),
                            EvidenceSource.LOG, "process output (watched over the whole stream)");
                }
                String terminal = "no terminal within 120s";
                String terminalRef = "session " + session;
                long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
                while (System.nanoTime() < deadline) {
                    if (!process.isAlive()) {
                        Path exit = Files.createTempDirectory(Path.of("target"), "feat112-j4-exit-");
                        Files.writeString(exit.resolve("process-tail.log"), process.tail());
                        terminal = "process exited before a terminal";
                        terminalRef = exit.resolve("process-tail.log").toString();
                        break;
                    }
                    var tasks = client.get("/api/agents/tasks/" + session + "?sessionId=" + session).json();
                    if (tasks.size() == 1 && !"running".equals(tasks.get(0).path("status").asText())) {
                        terminal = tasks.get(0).path("status").asText();
                        break;
                    }
                    Thread.sleep(100);
                }
                Expect.equal(s, s.id(), "terminalUnchanged", "能力表“传输事件不独立记账”/验收出口6", Category.EXTERNAL_CONTRACT,
                        EvidenceSource.HTTP, terminalRef, terminal, "completed");
                if (!process.isAlive()) return;
                var afterResponse = client.get("/v1/health/detail");
                GateSnapshotChecks.of(s, s.id(), "after", afterResponse.json().path("degradationGate"), afterResponse.evidenceRef())
                        .instance(before.path("instanceId").asText(), "§5.1.8").samples(1, "验收出口6").failures(0, "验收出口6")
                        .state("CLOSED", "验收出口6");
            }
        }
    }
}
