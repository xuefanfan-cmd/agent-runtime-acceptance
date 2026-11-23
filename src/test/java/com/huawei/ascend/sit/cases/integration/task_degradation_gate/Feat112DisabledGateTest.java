package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.DiagnosticJudge;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/** Deployment with the gate explicitly disabled (feature §3.2, acceptance exit 16). */
class Feat112DisabledGateTest extends DeployedGateCase {
    @Override
    protected DeploymentForm form() {
        return DeploymentForm.disabled();
    }

    @Test
    @Covers(value = "FEAT-112-G4", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G4] 禁用态诊断为 healthy、enabled=false、CLOSED、零计数")
    void disabledDiagnostic(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            s.precondition("gate disabled by the deployment form", "false".equals(deployment().form().gateSetting("enabled")),
                    EvidenceSource.PROCESS, "form " + deployment().form().name());
            var response = d.sut().get("/v1/health/detail");
            s.accept(DiagnosticJudge.judge(s.id(), s.context(), Observation.captured(response.status(), EvidenceSource.HTTP, response.evidenceRef()),
                    Observation.captured(response.json(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, response.evidenceRef()), "CLOSED", false));
        }
    }
}
