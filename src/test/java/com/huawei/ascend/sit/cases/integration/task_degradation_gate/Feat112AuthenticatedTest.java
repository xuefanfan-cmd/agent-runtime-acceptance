package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.DiagnosticJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge.Comparison;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge.Reply;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * Deployment that declares service authentication explicitly ({@code deepanalyze.auth.enabled=true} plus the
 * secret): the diagnostic surface and entry ordering under it. The delivered-default form is covered by
 * {@link Feat112DiagnosticsTest}.
 */
class Feat112AuthenticatedTest extends DeployedGateCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ENTRIES = List.of("/api/agents/run", "/api/agents/run-stream");

    @Override
    protected DeploymentForm form() {
        return DeploymentForm.standard().named("authenticated").withExplicitServiceAuthentication();
    }

    @Test
    @Covers(value = {"FEAT-112-G12", "FEAT-112-G5-invalid"}, layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G12/G5-invalid] 显式配置认证的部署上，缺失或错误凭据访问诊断面返回 401，与任务入口的拒绝相同")
    void unauthenticatedDiagnosticMatchesServiceAuthentication(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            s.precondition("service authentication declared explicitly with a secret", deployment().form().declaresAuthentication()
                    && deployment().secret() != null, EvidenceSource.PROCESS, "form " + deployment().form().name());
            String probeSession = "feat112-auth-" + UUID.randomUUID();
            var trusted = deployment().client(deployment().secret(), "FEAT-112-G12-trusted")
                    .get("/api/agents/tasks/" + probeSession + "?sessionId=" + probeSession);
            s.precondition("configured credential is accepted by another protected endpoint", trusted.status() != 401 && trusted.status() != 403,
                    EvidenceSource.HTTP, trusted.evidenceRef());
            Map<String, String> credentials = new LinkedHashMap<>();
            credentials.put("FEAT-112-G12", null);
            credentials.put("FEAT-112-G5-invalid", "invalid-" + UUID.randomUUID());
            for (var entry : credentials.entrySet()) {
                DaEvidenceClient client = deployment().client(entry.getValue(), entry.getKey() + "-unauthenticated");
                var baseline = client.post("/api/agents/run", Map.of("sessionId", "feat112-auth-" + UUID.randomUUID(),
                        "input", "Reply with a short greeting"));
                s.activation(entry.getKey() + ": the same credentials are refused by the task entry",
                        baseline.status() == 401 || baseline.status() == 403, EvidenceSource.HTTP, baseline.evidenceRef());
                var diagnostic = client.get("/v1/health/detail");
                s.accept(entry.getKey(), DiagnosticJudge.rejectsUnauthenticated(entry.getKey(), s.context(), Observation.captured(
                        new DiagnosticJudge.AuthProbe(diagnostic.status(), diagnostic.body(), baseline.status(), baseline.body()),
                        EvidenceSource.HTTP, diagnostic.evidenceRef() + " vs " + baseline.evidenceRef())));
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-G11", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G11] 合法服务间认证可读取完整诊断")
    void validCredentialReadsDiagnostic(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            d.prepareEmptyClosed();
            var response = deployment().client(deployment().secret(), "FEAT-112-G11").get("/v1/health/detail");
            s.accept(DiagnosticJudge.judge(s.id(), s.context(), Observation.captured(response.status(), EvidenceSource.HTTP, response.evidenceRef()),
                    Observation.captured(response.json(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, response.evidenceRef()), "CLOSED", true));
        }
    }

    @Test
    @Covers(value = "FEAT-112-F1", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-F1] OPEN 与 HALF_OPEN 下入站认证失败仍返回既有认证错误且不占探针")
    void authenticationPrecedesGate(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            d.prepareEmptyClosed();
            Map<String, String> credentials = new LinkedHashMap<>();
            credentials.put("missing", null);
            credentials.put("invalid", "invalid-" + UUID.randomUUID());
            Map<String, Reply> ungated = new LinkedHashMap<>();
            for (var credential : credentials.entrySet()) {
                for (String route : ENTRIES) {
                    var response = send(credential, route);
                    Reply reply = Reply.of(response.status(), parse(response.body()));
                    s.activation(credential.getKey() + " credential refused on " + route + " with the gate CLOSED",
                            reply.refusedByExistingContract(), EvidenceSource.HTTP, response.evidenceRef());
                    ungated.put(credential.getKey() + route, reply);
                }
            }
            d.openGate();
            compare(s, d, credentials, ungated, "OPEN");
            var half = d.establishState("HALF_OPEN", Duration.ofSeconds(90));
            s.precondition("probe slot free", !half.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, half.evidenceRef());
            compare(s, d, credentials, ungated, "HALF_OPEN");
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "afterHalfOpenRequests", after.gate(), after.evidenceRef())
                    .state("HALF_OPEN", "§5.1.1/验收出口18").probeInFlight(false, "§5.1.1/验收出口18");
        }
    }

    private void compare(Scenario s, GateDriver d, Map<String, String> credentials, Map<String, Reply> ungated, String state)
            throws Exception {
        for (var credential : credentials.entrySet()) {
            for (String route : ENTRIES) {
                var gate = d.snapshot();
                s.precondition("gate still " + state + " before the request", state.equals(gate.state())
                        && (!"OPEN".equals(state) || gate.retryAfterSeconds() >= 1), EvidenceSource.DIAGNOSTIC_SNAPSHOT, gate.evidenceRef());
                var response = send(credential, route);
                var comparison = new Comparison(ungated.get(credential.getKey() + route), Reply.of(response.status(), parse(response.body())));
                s.accept(PrecedenceJudge.judge(s.id(), s.context(), Observation.captured(comparison, EvidenceSource.HTTP,
                        response.evidenceRef() + " (" + state + ", " + credential.getKey() + ", " + route + ")")));
            }
        }
    }

    private DaEvidenceClient.Exchange send(Map.Entry<String, String> credential, String route) throws Exception {
        return deployment().client(credential.getValue(), "FEAT-112-F1-" + credential.getKey())
                .post(route, Map.of("sessionId", "feat112-" + UUID.randomUUID(), "input", "Reply with a short greeting"));
    }

    @Test
    @Covers(value = "FEAT-112-G8", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-G8] 诊断不泄露原始异常、用户输入、工具参数与供应商密钥")
    void diagnosticsDoNotLeakSentinels(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            d.prepareEmptyClosed();
            String input = "feat112-input-" + UUID.randomUUID();
            String rawError = "feat112-raw-error-" + UUID.randomUUID();
            String toolArgument = "feat112-tool-arg-" + UUID.randomUUID();
            // the provider key the deployment configures (GateDeployment); it is unique to the test double
            String vendorKey = "feat112-test-stub-only";
            Path outside = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().getRoot().resolve(toolArgument).resolve("x.txt");
            d.toolThen("write_file", Map.of("path", outside.toString(), "content", toolArgument),
                    GateDriver.exhaustingFailuresSaying(rawError));
            var first = d.sut().post("/api/agents/run", Map.of("sessionId", d.session(), "input", input));
            s.activation("sentinel-bearing task (denied tool call, then raw provider errors) failed", first.status() == 500
                    && "failed".equals(first.json().path("status").asText()), EvidenceSource.HTTP, first.evidenceRef());
            GateDriver.Snapshot gate = d.snapshot();
            for (int i = 0; i < 100 && !"OPEN".equals(gate.state()); i++) {
                d.failuresSaying(rawError);
                var task = d.sut().post("/api/agents/run", Map.of("sessionId", d.session(), "input", input));
                s.precondition("raw-error task failed", task.status() == 500, EvidenceSource.HTTP, task.evidenceRef());
                gate = d.snapshot();
            }
            s.precondition("gate OPEN with the sentinel tasks in this instance's history", "OPEN".equals(gate.state()),
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, gate.evidenceRef());
            var health = d.sut().get("/v1/health/detail");
            s.precondition("diagnostic readable with valid credentials", health.status() == 200, EvidenceSource.HTTP, health.evidenceRef());
            s.accept(List.of(DiagnosticJudge.noLeak(s.id(), s.context(),
                    Observation.captured(health.body(), EvidenceSource.HTTP, health.evidenceRef()),
                    List.of(input, rawError, toolArgument, vendorKey, deployment().secret()))));
        }
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception notJson) {
            return null;
        }
    }
}
