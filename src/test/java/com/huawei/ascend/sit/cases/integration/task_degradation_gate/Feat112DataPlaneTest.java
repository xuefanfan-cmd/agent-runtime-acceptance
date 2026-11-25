package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge.Comparison;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge.Reply;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.SequenceJudge;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * Checks that need the DA shared data plane ({@link DeploymentForm.Infra#POSTGRES_DATA_PLANE}): the team lookup
 * that only runs with a team channel and the reserved tenant header (feature §5.1.9, acceptance exit 18). The oracle
 * for an existing validation error is the same request answered with the gate CLOSED.
 */
class Feat112DataPlaneTest extends DeployedGateCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ENTRIES = List.of("/api/agents/run", "/api/agents/run-stream");
    private static final Map<String, String> RESERVED_TENANT = Map.of("X-Da-Tenant-Id", "*");
    private static final Map<String, String> NORMAL_TENANT = Map.of("X-Da-Tenant-Id", "feat112-tenant-normal");

    @Override
    protected DeploymentForm form() {
        return DeploymentForm.standard().named("dataplane").with(DeploymentForm.GATE + "open-quiet-period", "20s")
                .withPostgresDataPlane();
    }

    /** One invalid request: body and headers, depending only on the session it is sent in. */
    private record Invalid(Map<String, Object> body, Map<String, String> headers) {}

    private interface InvalidRequest {
        Invalid build(String session);
    }

    @Test
    @Covers(value = "FEAT-112-F4", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-F4] OPEN/HALF_OPEN 下保留租户头仍返回既有校验错误")
    void reservedTenantPrecedesGate(Scenario s) throws Exception {
        Map<String, InvalidRequest> kinds = new LinkedHashMap<>();
        kinds.put("reservedTenant", session -> new Invalid(Map.of("sessionId", session, "input", "Reply with a short greeting"),
                RESERVED_TENANT));
        verify(s, kinds, null);
    }

    /** The data plane re-applies the tenant rule as defence in depth; a normal tenant must pass both checks. */
    @Test
    @Covers(value = "FEAT-112-F4", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-F4] 数据面形态下正常租户头不被租户校验拒绝：CLOSED 正常完成计样本，OPEN 由降级门拒收")
    void normalTenantPassesTenantValidation(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            d.prepareEmptyClosed();
            d.reset();
            JsonNode statsBefore = d.stats();
            var closed = d.sut().post("/api/agents/run", Map.of("sessionId", d.session(), "input", "Reply with a short greeting"),
                    NORMAL_TENANT);
            s.activation("task outcome passed through the declared stub",
                    GateDriver.count(d.stats(), "totalRequests") > GateDriver.count(statsBefore, "totalRequests"),
                    EvidenceSource.STUB, closed.evidenceRef());
            Expect.equal(s, s.id(), "normalTenant.closedCompletes", "§5.1.9/验收出口18", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.HTTP, closed.evidenceRef(), closed.status() + " " + closed.json().path("status").asText(),
                    "200 completed");
            var accounted = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "normalTenant.accounted", accounted.gate(), accounted.evidenceRef())
                    .state("CLOSED", "§5.1.3").samples(1, "§5.1.3").failures(0, "§5.1.3");
            var open = d.openGate();
            s.precondition("gate OPEN before the normal-tenant request", "OPEN".equals(open.state()) && open.retryAfterSeconds() >= 2,
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, open.evidenceRef());
            var refused = d.sut().post("/api/agents/run", Map.of("sessionId", d.session(), "input", "Reply with a short greeting"),
                    NORMAL_TENANT);
            Reply reply = Reply.of(refused.status(), parse(refused.body()));
            Expect.equal(s, s.id(), "normalTenant.openReachesGate", "§3.1/验收出口18", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.HTTP, refused.evidenceRef(), reply.status() + " " + reply.code(), "503 ENGINE_DEGRADED");
        }
    }

    @Test
    @Covers(value = "FEAT-112-F3", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-F3] 数据面可用时 team 不存在的校验先于降级门")
    void unknownTeamPrecedesGate(Scenario s) throws Exception {
        String team = "feat112-missing-team-" + UUID.randomUUID();
        Map<String, InvalidRequest> kinds = new LinkedHashMap<>();
        kinds.put("teamNotFound", session -> new Invalid(Map.of("sessionId", session, "input", "hello", "teamId", team), Map.of()));
        verify(s, kinds, 404);
    }

    /**
     * Each invalid request is first answered with the gate CLOSED (the existing contract), then sent again under OPEN
     * and HALF_OPEN: same answer, no ENGINE_DEGRADED, no probe claim, no task, no window or model effect.
     *
     * @param closedStatus status the CLOSED answer must have to prove this deployment reaches the intended branch
     *                     ({@code null} = any refusal by the existing contract)
     */
    private void verify(Scenario s, Map<String, InvalidRequest> kinds, Integer closedStatus) throws Exception {
        try (var d = deployment().driver(s)) {
            d.prepareEmptyClosed();
            Map<String, String> sessions = new LinkedHashMap<>();
            Map<String, Reply> ungated = new LinkedHashMap<>();
            for (var kind : kinds.entrySet()) {
                for (String route : ENTRIES) {
                    String key = kind.getKey() + route;
                    String session = d.session();
                    sessions.put(key, session);
                    Invalid request = kind.getValue().build(session);
                    var response = d.sut().post(route, request.body(), request.headers());
                    Reply reply = Reply.of(response.status(), parse(response.body()));
                    s.activation(key + " refused by validation with the gate CLOSED" + (closedStatus == null ? "" : " (HTTP " + closedStatus + ")"),
                            reply.refusedByExistingContract() && (closedStatus == null || response.status() == closedStatus),
                            EvidenceSource.HTTP, response.evidenceRef());
                    ungated.put(key, reply);
                }
            }
            d.openGate();
            gated(s, d, kinds, sessions, ungated, "OPEN");
            var half = d.establishState("HALF_OPEN", Duration.ofSeconds(90));
            s.precondition("probe slot free", !half.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, half.evidenceRef());
            gated(s, d, kinds, sessions, ungated, "HALF_OPEN");
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "afterHalfOpenRequests", after.gate(), after.evidenceRef())
                    .state("HALF_OPEN", "验收出口18").probeInFlight(false, "§5.1.1/验收出口18");
        }
    }

    private void gated(Scenario s, GateDriver d, Map<String, InvalidRequest> kinds, Map<String, String> sessions,
                       Map<String, Reply> ungated, String state) throws Exception {
        for (var kind : kinds.entrySet()) {
            for (String route : ENTRIES) {
                String key = kind.getKey() + route;
                String session = sessions.get(key);
                var before = d.snapshot();
                s.precondition("gate " + state + " before " + key, state.equals(before.state())
                        && (!"OPEN".equals(state) || before.retryAfterSeconds() >= 2), EvidenceSource.DIAGNOSTIC_SNAPSHOT, before.evidenceRef());
                JsonNode tasksBefore = d.tasks(session);
                JsonNode statsBefore = d.stats();
                Invalid request = kind.getValue().build(session);
                DaEvidenceClient.Exchange response = d.sut().post(route, request.body(), request.headers());
                var after = d.snapshot();
                String ref = response.evidenceRef() + " (" + state + ", " + key + ")";
                s.accept(PrecedenceJudge.judge(s.id(), s.context(), Observation.captured(
                        new Comparison(ungated.get(key), Reply.of(response.status(), parse(response.body()))), EvidenceSource.HTTP, ref)));
                var effects = new SequenceJudge.SideEffects(before.gate(), after.gate(), tasksBefore, d.tasks(session),
                        statsBefore, d.stats(), state.equals(after.state()));
                s.accept(SequenceJudge.noSideEffects(s.id(), s.context(), Observation.captured(effects, EvidenceSource.DIAGNOSTIC_SNAPSHOT, ref)));
                GateSnapshotChecks.of(s, s.id(), state + "." + key, after.gate(), after.evidenceRef())
                        .probeInFlight(false, "§5.1.1/验收出口18");
            }
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
