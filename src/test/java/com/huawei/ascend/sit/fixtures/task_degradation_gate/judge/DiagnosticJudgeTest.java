package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.DiagnosticJudge.AuthProbe;
import com.huawei.ascend.sit.judge.EvidenceChecks.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("selftest")
class DiagnosticJudgeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Context READY = Context.ready("fixture");

    private ObjectNode body(String state, boolean enabled) {
        ObjectNode body = JSON.createObjectNode();
        body.put("status", state.equals("CLOSED") ? "healthy" : "degraded");
        ObjectNode gate = body.putObject("degradationGate");
        gate.put("state", state).put("enabled", enabled).put("instanceId", "instance-test")
                .put("sampleCount", 0).put("systemFailureCount", 0).put("systemFailureRate", 0)
                .put("probeInFlight", state.equals("HALF_OPEN"));
        if (state.equals("OPEN")) gate.put("retryAfterSeconds", 0);
        return body;
    }

    private List<Finding> judge(ObjectNode body, String state, boolean enabled) {
        return DiagnosticJudge.judge("G", READY, Observation.captured(200, EvidenceSource.HTTP, "http"),
                Observation.captured(body, EvidenceSource.DIAGNOSTIC_SNAPSHOT, "body"), state, enabled);
    }

    private void outcome(List<Finding> findings, String suffix, Outcome expected) {
        assertEquals(expected, findings.stream().filter(f -> f.ruleId().equals("G." + suffix))
                .findFirst().orElseThrow().outcome(), suffix);
    }

    @Test
    void validStatesDisabledAndExtensionsPass() {
        for (String state : List.of("CLOSED", "OPEN", "HALF_OPEN")) {
            ObjectNode body = body(state, true);
            body.put("requestId", "legal-extension");
            assertTrue(judge(body, state, true).stream().allMatch(f -> f.outcome() == Outcome.PASS), state);
        }
        assertTrue(judge(body("CLOSED", false), "CLOSED", false).stream().allMatch(f -> f.outcome() == Outcome.PASS));
    }

    @Test
    void countMutationFailsSpecificRule() {
        ObjectNode body = body("CLOSED", true);
        ObjectNode gate = (ObjectNode) body.get("degradationGate");
        gate.put("sampleCount", 2L).put("systemFailureCount", 1L).put("systemFailureRate", 0.5);
        outcome(judge(body, "CLOSED", true), "counts", Outcome.PASS);
        gate.put("systemFailureRate", 0.25);
        outcome(judge(body, "CLOSED", true), "counts", Outcome.FAIL);
        gate.put("systemFailureRate", 1.5).put("systemFailureCount", 3);
        outcome(judge(body, "CLOSED", true), "counts", Outcome.FAIL);
    }

    @Test
    void missingOrMistypedFieldsAreViolations() {
        ObjectNode body = body("OPEN", true);
        ObjectNode gate = (ObjectNode) body.get("degradationGate");
        gate.remove("retryAfterSeconds");
        gate.remove("enabled");
        gate.put("instanceId", " ");
        List<Finding> findings = judge(body, "OPEN", true);
        for (String suffix : List.of("retry", "enabled", "instanceId")) outcome(findings, suffix, Outcome.FAIL);
        ObjectNode closed = body("CLOSED", true);
        ((ObjectNode) closed.get("degradationGate")).putNull("retryAfterSeconds").put("probeInFlight", "false");
        outcome(judge(closed, "CLOSED", true), "retry", Outcome.FAIL);
        outcome(judge(closed, "CLOSED", true), "probe", Outcome.FAIL);
    }

    @Test
    void absentEvidenceDoesNotBecomeProductFailure() {
        var findings = DiagnosticJudge.judge("G", READY, Observation.captured(200, EvidenceSource.HTTP, "http"),
                Observation.unavailable(EvidenceSource.DIAGNOSTIC_SNAPSHOT, "missing"), "CLOSED", true);
        outcome(findings, "http", Outcome.PASS);
        outcome(findings, "shape", Outcome.NOT_OBSERVED);
        outcome(findings, "counts", Outcome.NOT_OBSERVED);
    }

    @Test
    void disabledCountersAndInvalidExpectationsAreRejected() {
        ObjectNode body = body("CLOSED", false);
        ((ObjectNode) body.get("degradationGate")).put("sampleCount", 1);
        outcome(judge(body, "CLOSED", false), "disabledCounts", Outcome.FAIL);
        assertThrows(IllegalArgumentException.class, () -> judge(body, "OPEN", false));
        assertThrows(IllegalArgumentException.class, () -> judge(body, "UNKNOWN", true));
    }

    private static List<Finding> auth(AuthProbe probe) {
        return DiagnosticJudge.rejectsUnauthenticated("G5-missing", READY, Observation.captured(probe, EvidenceSource.HTTP, "exchange"));
    }

    @Test
    void anonymousDiagnosticReadIsAViolation() {
        var findings = auth(new AuthProbe(200, "{\"status\":\"healthy\",\"degradationGate\":{}}", null, null));
        assertEquals(2, findings.size());
        assertTrue(findings.stream().allMatch(f -> f.outcome() == Outcome.FAIL));
    }

    @Test
    void rejectionMustMatchTheExistingServiceAuthenticationWhenConfigured() {
        var same = auth(new AuthProbe(401, "{\"error\":\"unauthorized\"}", 401, "{ \"error\" : \"unauthorized\" }"));
        assertTrue(same.stream().allMatch(f -> f.outcome() == Outcome.PASS), "JSON spacing is a legal variant");
        var bespoke = auth(new AuthProbe(401, "{\"error\":\"diagnostic locked\"}", 401, "{\"error\":\"unauthorized\"}"));
        assertEquals(Outcome.FAIL, bespoke.stream().filter(f -> f.ruleId().endsWith("sameAsProtected")).findFirst().orElseThrow().outcome());
        var forbidden = auth(new AuthProbe(403, "", null, null));
        assertEquals(Outcome.FAIL, forbidden.stream().filter(f -> f.ruleId().endsWith("rejected")).findFirst().orElseThrow().outcome(),
                "the service-authentication rejection is 401, not any refusal");
        var differentStatus = auth(new AuthProbe(401, "{\"error\":\"unauthorized\"}", 403, "{\"error\":\"unauthorized\"}"));
        assertEquals(Outcome.FAIL, differentStatus.stream().filter(f -> f.ruleId().endsWith("sameAsProtected")).findFirst().orElseThrow().outcome());
    }

    @Test
    void sentinelsAndStackTracesAreLeaks() {
        var clean = DiagnosticJudge.noLeak("G8", READY, Observation.captured("{\"status\":\"degraded\"}", EvidenceSource.HTTP, "x"),
                List.of("input-sentinel", "secret-sentinel"));
        assertEquals(Outcome.PASS, clean.outcome());
        for (String leaked : List.of("{\"e\":\"input-sentinel\"}", "{\"trace\":\"\\tat com.openjiuwen.X\"}", "{\"stackTrace\":[]}")) {
            assertEquals(Outcome.FAIL, DiagnosticJudge.noLeak("G8", READY,
                    Observation.captured(leaked, EvidenceSource.HTTP, "x"), List.of("input-sentinel")).outcome(), leaked);
        }
    }
}
