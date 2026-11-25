package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.DiagnosticJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * Diagnostic surface on the standard deployment, which leaves service authentication at the delivered default
 * (feature §3.2, acceptance exit 16).
 */
class Feat112DiagnosticsTest extends DeployedGateCase {
    @Override
    protected DeploymentForm form() {
        return DeploymentForm.standard();
    }

    private static void judge(Scenario s, GateDriver d, String state) throws Exception {
        DaEvidenceClient.Exchange response = d.sut().get("/v1/health/detail");
        s.accept(DiagnosticJudge.judge(s.id(), s.context(), Observation.captured(response.status(), EvidenceSource.HTTP, response.evidenceRef()),
                Observation.captured(response.json(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, response.evidenceRef()), state, true));
    }

    @Test
    @Covers(value = "FEAT-112-G1", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G1] CLOSED 诊断契约")
    void closedDiagnostic(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            d.prepareEmptyClosed();
            judge(s, d, "CLOSED");
        }
    }

    @Test
    @Covers(value = "FEAT-112-G2", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G2] OPEN 诊断契约")
    void openDiagnostic(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            d.openGate();
            judge(s, d, "OPEN");
        }
    }

    @Test
    @Covers(value = "FEAT-112-G3", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G3] HALF_OPEN 诊断省略恢复时间")
    void halfOpenDiagnostic(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            d.openGate();
            d.establishState("HALF_OPEN", Duration.ofSeconds(90));
            judge(s, d, "HALF_OPEN");
        }
    }

    @Test
    @Covers(value = "FEAT-112-G5-missing", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G5-missing] 交付默认配置下诊断面拒绝匿名访问（401）")
    void anonymousDiagnosticRejectedInDeliveredConfiguration(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            // The launched process inherits this JVM's environment; DEEPANALYZE_AUTH_ENABLED would override the default.
            s.precondition("authentication left at the delivered default: only the secret injected, no switch set or inherited",
                    !deployment().form().declaresAuthentication() && deployment().secret() != null
                            && System.getenv().keySet().stream().noneMatch(k -> k.equalsIgnoreCase("DEEPANALYZE_AUTH_ENABLED")),
                    EvidenceSource.PROCESS, "form " + deployment().form().name() + " properties " + deployment().form().properties().keySet());
            var anonymous = deployment().client(null, "FEAT-112-G5-missing-anonymous").get("/v1/health/detail");
            s.accept(DiagnosticJudge.rejectsUnauthenticated(s.id(), s.context(), Observation.captured(
                    new DiagnosticJudge.AuthProbe(anonymous.status(), anonymous.body(), null, null),
                    EvidenceSource.HTTP, anonymous.evidenceRef())));
        }
    }

    @Test
    @Covers(value = {"FEAT-112-G9", "FEAT-112-G10"}, layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G9/G10] /health 不变，/ready 不新增门状态分量")
    void legacyHealthUnaffected(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            d.prepareEmptyClosed();
            var health = d.sut().get("/health");
            var ready = d.sut().get("/ready");
            s.precondition("legacy endpoints answer while CLOSED", health.status() == 200 && ready.status() == 200,
                    EvidenceSource.HTTP, health.evidenceRef() + " ; " + ready.evidenceRef());
            d.openGate();
            var healthOpen = d.sut().get("/health");
            var readyOpen = d.sut().get("/ready");
            Expect.equal(s, "FEAT-112-G9", "healthStatusCode", "§3.2/验收出口16", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    healthOpen.evidenceRef(), healthOpen.status(), health.status());
            Expect.equal(s, "FEAT-112-G9", "healthBody", "§3.2/验收出口16", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    healthOpen.evidenceRef(), healthOpen.json().path("status"), health.json().path("status"));
            Expect.equal(s, "FEAT-112-G10", "readyStatusCode", "§3.2/§5.1.8", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    readyOpen.evidenceRef(), readyOpen.status(), ready.status());
            Expect.equal(s, "FEAT-112-G10", "readyStatus", "§3.2/§5.1.8", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    readyOpen.evidenceRef(), readyOpen.json().path("status"), ready.json().path("status"));
            Expect.equal(s, "FEAT-112-G10", "readyComponents", "§3.2/验收出口16", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    readyOpen.evidenceRef(), readyOpen.json().path("components"), ready.json().path("components"));
        }
    }
}
