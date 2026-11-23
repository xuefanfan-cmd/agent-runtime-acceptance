package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/** Gate state machine driven by real tasks (feature §5.1.7). */
class Feat112StateMachineTest extends DeployedGateCase {
    @Override
    protected DeploymentForm form() {
        return DeploymentForm.standard();
    }

    @Test
    @Covers(value = {"FEAT-112-C3", "FEAT-112-E2"}, layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-C3/E2] 半开探针正常完成后关门、清窗，后续双入口正常")
    void healthyProbeClosesAndClearsWindow(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var opened = d.openGate();
            d.reset();
            var half = d.establishState("HALF_OPEN", Duration.ofSeconds(90));
            s.precondition("probe slot free", !half.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, half.evidenceRef());
            var probe = d.runThroughStub(false);
            s.activation("probe task completed", probe.status() == 200 && "completed".equals(probe.json().path("status").asText()),
                    EvidenceSource.HTTP, probe.evidenceRef());
            var closed = d.awaitState("CLOSED", Duration.ofSeconds(20));
            for (String id : s.ids()) {
                GateSnapshotChecks.of(s, id, "afterProbe", closed.gate(), closed.evidenceRef())
                        .state("CLOSED", "§5.1.7/验收出口12").instance(opened.instanceId(), "§5.1.8")
                        .samples(0, "验收出口12/§5.1.7").failures(0, "验收出口12/§5.1.7")
                        .probeInFlight(false, "§3.2").retryAfterAbsent("§3.2");
            }
            var next = d.runThroughStub(false);
            Expect.equal(s, "FEAT-112-E2", "nextRunStatus", "验收出口12", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.HTTP, next.evidenceRef(), next.status(), 200);
            Expect.equal(s, "FEAT-112-E2", "nextRunTerminal", "验收出口12", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.HTTP, next.evidenceRef(), next.json().path("status").asText(), "completed");
            String streamSession = d.session();
            var stream = d.sut().post("/api/agents/run-stream", Map.of("sessionId", streamSession, "input", "Reply with a short greeting"));
            Expect.equal(s, "FEAT-112-E2", "nextRunStreamStatus", "验收出口12", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.SSE, stream.evidenceRef(), stream.status(), 200);
            Expect.equal(s, "FEAT-112-E2", "nextRunStreamTerminal", "验收出口12", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.HTTP, "session " + streamSession, terminalOf(d, streamSession), "completed");
            var after = d.snapshot();
            GateSnapshotChecks.of(s, "FEAT-112-E2", "afterNextTasks", after.gate(), after.evidenceRef())
                    .state("CLOSED", "验收出口12").samples(2, "§5.1.3").failures(0, "§5.1.3");
        }
    }

    @Test
    @Covers(value = "FEAT-112-C4", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-C4] 半开探针系统失败后按真实失败返回，回 OPEN 并重算静默期")
    void failedProbeReopensGate(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var opened = d.openGate();
            var half = d.establishState("HALF_OPEN", Duration.ofSeconds(90));
            s.precondition("probe slot free", !half.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, half.evidenceRef());
            d.failures();
            var probe = d.runThroughStub(true);
            Expect.equal(s, s.id(), "probeReturnsRealFailure", "验收出口11/§5.1.6", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.HTTP, probe.evidenceRef(), probe.status() + "/" + probe.json().path("status").asText(), "500/failed");
            List<String> fields = new java.util.ArrayList<>();
            probe.json().fieldNames().forEachRemaining(fields::add);
            Expect.that(s, s.id(), "probeIdentityHidden", "§5.1.6", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    probe.evidenceRef(), fields + " " + probe.body(), "no ENGINE_DEGRADED and no probe/claim field in the task response",
                    text -> !text.contains("ENGINE_DEGRADED") && fields.stream().noneMatch(f -> f.toLowerCase(java.util.Locale.ROOT).matches(".*(probe|claim).*")));
            var reopened = d.awaitState("OPEN", Duration.ofSeconds(10));
            GateSnapshotChecks.of(s, s.id(), "afterProbe", reopened.gate(), reopened.evidenceRef())
                    .state("OPEN", "§5.1.7/验收出口11").instance(opened.instanceId(), "§5.1.8")
                    .probeInFlight(false, "§5.1.7").retryAfterPositive("验收出口11/§5.1.8");
        }
    }

    private static String terminalOf(GateDriver d, String session) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
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
