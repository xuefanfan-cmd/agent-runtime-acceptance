package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge.Comparison;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge.Reply;
import com.huawei.ascend.sit.judge.EvidenceChecks.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("selftest")
class PrecedenceJudgeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Reply reply(int status, String json) throws Exception {
        return Reply.of(status, JSON.readTree(json));
    }

    private static List<Outcome> judge(Reply ungated, Reply gated) {
        return PrecedenceJudge.judge("F3", Context.ready("fixture"),
                Observation.captured(new Comparison(ungated, gated), EvidenceSource.HTTP, "pair")).stream().map(Finding::outcome).toList();
    }

    @Test
    void sameExistingErrorPassesIncludingNonClientStatusAndExtraFields() throws Exception {
        Reply closed = reply(503, "{\"error\":\"data plane unavailable\"}");
        Reply open = reply(503, "{\"error\":\"data plane unavailable\",\"requestId\":\"other\"}");
        assertEquals(List.of(Outcome.PASS, Outcome.PASS), judge(closed, open));
        assertTrue(closed.refusedByExistingContract());
    }

    @Test
    void degradationPreemptingValidationFailsBothRules() throws Exception {
        Reply closed = reply(400, "{\"code\":\"ENGINE_VALIDATION\",\"error\":\"sessionId and input are required\"}");
        Reply open = reply(503, "{\"code\":\"ENGINE_DEGRADED\",\"degraded\":true,\"error\":\"Agent is temporarily not accepting new tasks\"}");
        assertEquals(List.of(Outcome.FAIL, Outcome.FAIL), judge(closed, open));
    }

    @Test
    void rewrittenErrorFailsOnlyTheExistingErrorRule() throws Exception {
        Reply closed = reply(404, "{\"error\":\"team not found\"}");
        Reply open = reply(404, "{\"error\":\"team lookup skipped\"}");
        assertEquals(List.of(Outcome.FAIL, Outcome.PASS), judge(closed, open));
    }

    @Test
    void servedBaselineIsNotARefusal() throws Exception {
        assertFalse(reply(200, "{\"status\":\"completed\"}").refusedByExistingContract());
        assertFalse(reply(503, "{\"code\":\"ENGINE_DEGRADED\"}").refusedByExistingContract());
    }
}
