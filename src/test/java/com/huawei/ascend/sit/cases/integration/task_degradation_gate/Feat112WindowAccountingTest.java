package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/** Window accounting of single terminals on the standard deployment (feature §5.1.2, §5.1.3). */
class Feat112WindowAccountingTest extends DeployedGateCase {
    @Override
    protected DeploymentForm form() {
        return DeploymentForm.standard();
    }

    @Test
    @Covers(value = "FEAT-112-B8", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-B8] 空窗口异常率为 0 且字段存在")
    void emptyWindowReportsZero(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var empty = d.prepareEmptyClosed();
            GateSnapshotChecks.of(s, s.id(), "empty", empty.gate(), empty.evidenceRef())
                    .samples(0, "§3.2/验收出口16").failures(0, "§3.2/验收出口16").rate(0.0, "§5.1.2/§3.2");
        }
    }

    @Test
    @Covers(value = "FEAT-112-K19", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-K19] 正常完成只计分母")
    void healthyTaskCountsDenominatorOnly(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var before = d.prepareEmptyClosed();
            var completed = d.complete(false);
            GateSnapshotChecks.of(s, s.id(), "after", completed.after().gate(), completed.after().evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, "§5.1.3/验收出口5").failures(0, "§5.1.3/验收出口5")
                    .state("CLOSED", "§5.1.2");
        }
    }

    @Test
    @Covers(value = "FEAT-112-K18", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-K18] 模型 5xx 重试耗尽只计一次分子与分母")
    void exhaustedModelFailureCountsOnce(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var before = d.prepareEmptyClosed();
            var failed = d.complete(true);
            GateSnapshotChecks.of(s, s.id(), "after", failed.after().gate(), failed.after().evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, "§5.1.3/§5.1.4").failures(1, "§5.1.3/验收出口5")
                    .rate(1.0, "§5.1.2");
        }
    }

    @Test
    @Covers(value = "FEAT-112-E4", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E4] 结构化 181012 上下文过长只计分母")
    void contextTooLongCountsDenominatorOnly(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var before = d.prepareEmptyClosed();
            d.contextTooLong();
            var response = d.runThroughStub(true);
            var body = response.json();
            s.activation("task failed with the structured context-length category (181012, public code ENGINE_CONTEXT_TOO_LONG)",
                    response.status() == 500 && "failed".equals(body.path("status").asText())
                            && "ENGINE_CONTEXT_TOO_LONG".equals(body.path("code").asText()),
                    EvidenceSource.HTTP, response.evidenceRef());
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "after", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, "§5.1.3/验收出口5").failures(0, "§5.1.3/验收出口5")
                    .state("CLOSED", "§5.1.2");
        }
    }

    @Test
    @Covers(value = "FEAT-112-E4-formats", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E4-formats] 不同提供方格式的上下文超长同样只计分母")
    void contextTooLongProviderFormatsCountDenominatorOnly(Scenario s) throws Exception {
        Map<String, Map<String, Object>> formats = new java.util.LinkedHashMap<>();
        formats.put("codeWithoutLengthWording", Map.of("code", "context_length_exceeded", "type", "invalid_request_error",
                "message", "Upstream refused the request (ref feat112-7f3a)"));
        formats.put("invalidParameterWithInputRange", Map.of("code", "invalid_parameter_error", "type", "invalid_request_error",
                "message", "Range of input length should be [1, 8192]"));
        try (var d = deployment().driver(s)) {
            var before = d.prepareEmptyClosed();
            long samples = 0;
            for (var format : formats.entrySet()) {
                d.providerRejection(400, format.getValue());
                var response = d.runThroughStub(true);
                s.activation(format.getKey() + ": task failed after the provider rejection",
                        response.status() == 500 && "failed".equals(response.json().path("status").asText()),
                        EvidenceSource.HTTP, response.evidenceRef());
                samples++;
                var after = d.snapshot();
                GateSnapshotChecks.of(s, s.id(), format.getKey(), after.gate(), after.evidenceRef())
                        .instance(before.instanceId(), "§5.1.8").samples(samples, "§5.1.3/验收出口5")
                        .failures(0, "§5.1.3/验收出口5").state("CLOSED", "§5.1.2");
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-E13", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E13] 主链曾被护栏拒绝、随后正常完成：只计分母")
    void deniedThenCompletedCountsDenominatorOnly(Scenario s) throws Exception {
        deniedThen(s, false);
    }

    @Test
    @Covers(value = "FEAT-112-E14", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E14] 主链曾被护栏拒绝、随后系统异常：计入分子")
    void deniedThenSystemFailureCountsNumerator(Scenario s) throws Exception {
        deniedThen(s, true);
    }

    /**
     * The model asks to write outside the workspace (denied by the host's precheck, the task continues), then either
     * answers normally or keeps failing. Feature §6: a denial seen during the task must not rewrite the classification.
     */
    private void deniedThen(Scenario s, boolean systemFailure) throws Exception {
        try (var d = deployment().driver(s)) {
            var before = d.prepareEmptyClosed();
            Path outside = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().getRoot()
                    .resolve("feat112-outside-" + UUID.randomUUID()).resolve("denied.txt");
            d.toolThen("write_file", Map.of("path", outside.toString(), "content", "feat112"),
                    systemFailure ? GateDriver.exhaustingFailures() : List.of());
            JsonNode statsBefore = d.stats();
            String session = d.session();
            var stream = d.sut().post("/api/agents/run-stream", Map.of("sessionId", session, "input", "Write a note"));
            s.activation("the tool call was denied by the guardrail and reported to the model", stream.status() == 200
                    && stream.body().contains("denied"), EvidenceSource.SSE, stream.evidenceRef());
            s.activation("the outside path was not written", !Files.exists(outside), EvidenceSource.PROCESS, outside.toString());
            String terminal = terminalOf(d, session);
            if (systemFailure) {
                s.activation("model failures after the denial were served by the stub",
                        GateDriver.count(d.stats(), "errorResponses") > GateDriver.count(statsBefore, "errorResponses"),
                        EvidenceSource.STUB, "stub stats");
            }
            s.activation("task reached terminal " + (systemFailure ? "failed" : "completed"),
                    terminal.equals(systemFailure ? "failed" : "completed"), EvidenceSource.HTTP, "session " + session);
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "after", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, "§5.1.3/§6/验收出口5")
                    .failures(systemFailure ? 1 : 0, "§5.1.3/§6/验收出口5");
        }
    }

    private static String terminalOf(GateDriver d, String session) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status = "no task listed";
        do {
            JsonNode tasks = d.tasks(session);
            if (tasks.size() == 1) {
                status = tasks.get(0).path("status").asText();
                if (!"running".equals(status)) return status;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < until);
        return status;
    }
}
