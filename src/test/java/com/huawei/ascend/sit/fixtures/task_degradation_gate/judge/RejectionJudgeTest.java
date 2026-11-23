package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.RejectionJudge.Rejection;
import com.huawei.ascend.sit.judge.EvidenceChecks.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic evidence for the R family: correct, targeted violations, missing evidence and legal variants. */
@Tag("selftest")
class RejectionJudgeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Context READY = Context.ready("snapshot#open");

    private static ObjectNode body(String state) {
        ObjectNode b = JSON.createObjectNode().put("error", RejectionJudge.ERROR).put("code", "ENGINE_DEGRADED")
                .put("degraded", true).put("retryable", true).put("state", state).put("instanceId", "instance-a");
        if (state.equals("OPEN")) b.put("retryAfterSeconds", 87);
        return b;
    }

    private static Map<String, List<String>> headers(String state) {
        return state.equals("OPEN") ? Map.of("content-type", List.of("application/json;charset=UTF-8"), "Retry-After", List.of("87"))
                : Map.of("Content-Type", List.of("application/json"));
    }

    private static List<Finding> judge(int status, Map<String, List<String>> headers, JsonNode body, String state) {
        return RejectionJudge.judge("FEAT-112-D1-run", READY,
                Observation.captured(new Rejection(status, headers, body), EvidenceSource.HTTP, "exchange"), state, "instance-a");
    }

    private static Outcome outcome(List<Finding> findings, String rule) {
        return findings.stream().filter(f -> f.ruleId().equals("FEAT-112-D1-run." + rule)).findFirst().orElseThrow().outcome();
    }

    @Test
    void correctRefusalsAndLegalVariantsPass() {
        ObjectNode open = body("OPEN");
        open.put("requestId", "ordinary-extension");
        assertTrue(judge(503, headers("OPEN"), open, "OPEN").stream().allMatch(f -> f.outcome() == Outcome.PASS));
        assertTrue(judge(503, headers("HALF_OPEN"), body("HALF_OPEN"), "HALF_OPEN").stream().allMatch(f -> f.outcome() == Outcome.PASS));
        assertTrue(judge(503, headers("OPEN"), open, "OPEN").stream()
                .allMatch(f -> f.rule().contractRef().startsWith("FEAT-112 feature.md@05af9cc3 ")));
    }

    @Test
    void eachViolationFailsItsOwnRuleOnly() {
        ObjectNode wrongCode = body("OPEN").put("code", "ENGINE_BUSY");
        var findings = judge(503, headers("OPEN"), wrongCode, "OPEN");
        assertEquals(1, findings.stream().filter(f -> f.outcome() == Outcome.FAIL).count());
        assertEquals(Outcome.FAIL, outcome(findings, "code"));
        for (String key : List.of("degraded", "retryable")) {
            for (Object value : List.of("true", 1, false)) {
                ObjectNode b = body("OPEN");
                b.set(key, JSON.valueToTree(value));
                assertEquals(Outcome.FAIL, outcome(judge(503, headers("OPEN"), b, "OPEN"), key), key + "=" + value);
            }
        }
        assertEquals(Outcome.FAIL, outcome(judge(200, headers("OPEN"), body("OPEN"), "OPEN"), "status"));
        assertEquals(Outcome.FAIL, outcome(judge(503, Map.of("Content-Type", List.of("text/event-stream"), "Retry-After", List.of("87")),
                body("OPEN"), "OPEN"), "contentType"));
    }

    @Test
    void retryHeaderAndBodyMustAgreeAndHalfOpenOmitsBoth() {
        assertEquals(Outcome.FAIL, outcome(judge(503, Map.of("Content-Type", List.of("application/json"), "Retry-After", List.of("86")),
                body("OPEN"), "OPEN"), "retry"));
        assertEquals(Outcome.FAIL, outcome(judge(503, Map.of("Content-Type", List.of("application/json")), body("OPEN"), "OPEN"), "retry"));
        ObjectNode nullRetry = body("OPEN");
        nullRetry.putNull("retryAfterSeconds");
        assertEquals(Outcome.FAIL, outcome(judge(503, headers("OPEN"), nullRetry, "OPEN"), "retry"));
        ObjectNode halfWithRetry = body("HALF_OPEN").put("retryAfterSeconds", 3);
        assertEquals(Outcome.FAIL, outcome(judge(503, headers("HALF_OPEN"), halfWithRetry, "HALF_OPEN"), "retry"));
        assertEquals(Outcome.FAIL, outcome(judge(503, Map.of("Content-Type", List.of("application/json"), "Retry-After", List.of("0")),
                body("HALF_OPEN"), "HALF_OPEN"), "retry"));
    }

    @Test
    void stateAndInstanceAreComparedToIndependentExpectation() {
        assertEquals(Outcome.FAIL, outcome(judge(503, headers("OPEN"), body("OPEN").put("instanceId", "instance-b"), "OPEN"), "instanceId"));
        assertEquals(Outcome.FAIL, outcome(judge(503, headers("HALF_OPEN"), body("HALF_OPEN").put("state", "OPEN"), "HALF_OPEN"), "state"));
        ObjectNode missing = body("OPEN");
        missing.remove("code");
        assertEquals(Outcome.FAIL, outcome(judge(503, headers("OPEN"), missing, "OPEN"), "code"));
    }

    @Test
    void absentEvidenceIsNotObservedNotAViolation() {
        var findings = RejectionJudge.judge("FEAT-112-D1-run", READY, Observation.unavailable(EvidenceSource.HTTP, "missing"),
                "OPEN", "instance-a");
        assertTrue(findings.stream().allMatch(f -> f.outcome() == Outcome.NOT_OBSERVED && f.reason() == Reason.TRACE_MISSING));
        assertThrows(IllegalArgumentException.class, () -> judge(503, headers("OPEN"), body("OPEN"), "CLOSED"));
    }
}
