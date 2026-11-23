package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.RejectionJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.RejectionJudge.Rejection;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.SequenceJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Finding;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/** OPEN refusal on both new-task entries (feature §3.1, acceptance exits 7 and 8). */
class Feat112AdmissionTest extends DeployedGateCase {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected DeploymentForm form() {
        return DeploymentForm.standard();
    }

    @Test
    @Covers(value = {"FEAT-112-D1-run", "FEAT-112-D1-stream", "FEAT-112-D3", "FEAT-112-D5", "FEAT-112-D6"}, layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-D1-run/D1-stream/D3/D5/D6] OPEN 双入口统一 503 拒收且无副作用")
    void openGateRefusesBothEntriesWithoutSideEffects(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var open = d.openGate();
            for (String route : List.of("/api/agents/run", "/api/agents/run-stream")) {
                String session = d.session();
                var before = d.snapshot();
                s.precondition("quiet-period margin left for the refused request", before.retryAfterSeconds() >= 3,
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, before.evidenceRef());
                JsonNode tasksBefore = d.tasks(session);
                JsonNode statsBefore = d.stats();
                DaEvidenceClient.Exchange response = d.sut().post(route, Map.of("sessionId", session, "input", "Reply with a short greeting"));
                var after = d.snapshot();
                var refusal = Observation.captured(new Rejection(response.status(), response.headers(), parse(response.body())),
                        EvidenceSource.HTTP, response.evidenceRef());
                String entryScenario = route.endsWith("/run") ? "FEAT-112-D1-run" : "FEAT-112-D1-stream";
                s.accept(entryScenario, RejectionJudge.judge(entryScenario, s.context(), refusal, "OPEN", open.instanceId()));
                s.accept("FEAT-112-D3", only(RejectionJudge.judge("FEAT-112-D3", s.context(), refusal, "OPEN", open.instanceId()), ".retry"));
                JsonNode refusalBody = parse(response.body());
                long announced = refusalBody == null ? -1 : refusalBody.path("retryAfterSeconds").asLong(-1);
                Expect.that(s, "FEAT-112-D3", "remainingQuiet" + route.substring(route.lastIndexOf('/')), "§3.1/验收出口7",
                        Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP, response.evidenceRef() + " vs " + before.evidenceRef(), announced,
                        "seconds until HALF_OPEN: within 1s of the diagnostic's " + before.retryAfterSeconds() + " just before",
                        value -> value >= 0 && Math.abs(value - before.retryAfterSeconds()) <= 1);
                s.accept("FEAT-112-D5", only(RejectionJudge.judge("FEAT-112-D5", s.context(), refusal, "OPEN", open.instanceId()),
                        ".state", ".instanceId"));
                var effects = new SequenceJudge.SideEffects(before.gate(), after.gate(), tasksBefore, d.tasks(session),
                        statsBefore, d.stats(), "OPEN".equals(after.state()));
                s.accept("FEAT-112-D6", SequenceJudge.noSideEffects("FEAT-112-D6", s.context(),
                        Observation.captured(effects, EvidenceSource.DIAGNOSTIC_SNAPSHOT, response.evidenceRef())));
            }
        }
    }

    private static List<Finding> only(List<Finding> findings, String... suffixes) {
        return findings.stream().filter(f -> java.util.Arrays.stream(suffixes).anyMatch(f.ruleId()::endsWith)).toList();
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception notJson) {
            return null;
        }
    }
}
