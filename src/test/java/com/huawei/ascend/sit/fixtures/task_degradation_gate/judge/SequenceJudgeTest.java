package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.judge.EvidenceChecks.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("selftest")
class SequenceJudgeTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void windowRequiresTerminalIdentityCountsAndTimeIsolation() throws Exception {
        var before = json.readTree("{\"instanceId\":\"a\"}");
        var after = json.readTree("{\"instanceId\":\"a\",\"sampleCount\":2,\"systemFailureCount\":1,\"systemFailureRate\":0.5,\"state\":\"OPEN\",\"extension\":true}");
        assertEquals(Outcome.PASS, judge(new SequenceJudge.Window(before, after, "failed", true)));
        assertEquals(Outcome.FAIL, judge(new SequenceJudge.Window(before, after, "completed", true)));
        assertEquals(Outcome.FAIL, judge(new SequenceJudge.Window(before, after, "failed", false)));
        for (String field : new String[]{"instanceId", "sampleCount", "systemFailureCount", "systemFailureRate", "state"}) {
            var broken = after.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) broken).remove(field);
            assertEquals(Outcome.FAIL, judge(new SequenceJudge.Window(before, broken, "failed", true)), field);
        }
        assertEquals(Outcome.NOT_OBSERVED, SequenceJudge.window("test", Context.ready("fixture"),
                Observation.unavailable(EvidenceSource.HTTP, "missing"), "failed", 2, 1, "OPEN").outcome());
    }
    private Outcome judge(SequenceJudge.Window window) {
        return SequenceJudge.window("test", Context.ready("fixture"), Observation.captured(window,
                EvidenceSource.DIAGNOSTIC_SNAPSHOT, "fixture"), "failed", 2, 1, "OPEN").outcome();
    }
    @Test void noSideEffectsRejectsCounterResetOrNewTask() throws Exception {
        var gate = json.readTree("{\"instanceId\":\"a\",\"sampleCount\":20,\"systemFailureCount\":20}");
        var tasks = json.readTree("[]");
        var stats = json.readTree("{\"generation\":1,\"totalRequests\":20}");
        assertEquals(Outcome.PASS, side(new SequenceJudge.SideEffects(gate, gate, tasks, tasks, stats, stats, true)));
        assertEquals(Outcome.FAIL, side(new SequenceJudge.SideEffects(gate, gate, tasks, json.readTree("[{\"id\":\"x\"}]"), stats, stats, true)));
        assertEquals(Outcome.FAIL, side(new SequenceJudge.SideEffects(gate, gate, tasks, tasks, stats, json.readTree("{\"generation\":2,\"totalRequests\":20}"), true)));
        assertEquals(Outcome.FAIL, side(new SequenceJudge.SideEffects(gate, gate, tasks, tasks, stats, stats, false)));
    }
    @Test void categoriesNumericRepresentationsAndTaskReordering() throws Exception {
        var gate = json.createObjectNode().put("instanceId", "a").put("sampleCount", 2).put("systemFailureCount", 1);
        var longs = json.createObjectNode().put("instanceId", "a").put("sampleCount", 2L).put("systemFailureCount", 1L);
        var stats = json.createObjectNode().put("generation", 1).put("totalRequests", 2);
        var statsLong = json.createObjectNode().put("generation", 1L).put("totalRequests", 2L);
        var before = json.readTree("[{\"id\":\"a\"},{\"id\":\"b\"}]");
        var reordered = json.readTree("[{\"id\":\"b\"},{\"id\":\"a\"}]");
        var good = new SequenceJudge.SideEffects(gate, longs, before, reordered, stats, statsLong, true);
        var findings = SequenceJudge.noSideEffects("test", Context.ready("fixture"), Observation.captured(good, EvidenceSource.HTTP, "all-evidence"));
        assertEquals(2, findings.size());
        assertEquals(Category.INTERNAL_STATE, findings.get(0).rule().category());
        assertEquals(Category.INTERNAL_EXECUTION_OBSERVABILITY, findings.get(1).rule().category());
        findings.forEach(f -> { assertEquals(Outcome.PASS, f.outcome()); assertEquals("all-evidence", f.evidenceRef()); });
        for (String invalid : new String[]{"[{\"id\":\"a\"},{\"id\":\"a\"}]", "[{\"id\":\"a\"},{}]", "[{\"id\":\"a\"},{\"id\":\"c\"}]", "[{\"id\":\"a\"},{\"id\":\"\"}]"}) {
            var bad = new SequenceJudge.SideEffects(gate, longs, before, json.readTree(invalid), stats, statsLong, true);
            var result = SequenceJudge.noSideEffects("test", Context.ready("fixture"), Observation.captured(bad, EvidenceSource.HTTP, "fixture"));
            assertEquals(Outcome.FAIL, result.get(0).outcome()); assertEquals(Outcome.PASS, result.get(1).outcome());
        }
        var changed = new SequenceJudge.SideEffects(gate, longs, before, reordered, stats, statsLong.deepCopy().put("totalRequests", 3L), true);
        var result = SequenceJudge.noSideEffects("test", Context.ready("fixture"), Observation.captured(changed, EvidenceSource.STUB, "fixture"));
        assertEquals(Outcome.PASS, result.get(0).outcome()); assertEquals(Outcome.FAIL, result.get(1).outcome());
        assertEquals(Category.INTERNAL_STATE, SequenceJudge.window("test", Context.ready("fixture"),
                Observation.unavailable(EvidenceSource.DIAGNOSTIC_SNAPSHOT, "missing"), "failed", 2, 1, "OPEN").rule().category());
    }
    private Outcome side(SequenceJudge.SideEffects evidence) {
        var findings = SequenceJudge.noSideEffects("test", Context.ready("fixture"), Observation.captured(evidence, EvidenceSource.STUB, "fixture"));
        return findings.stream().allMatch(f -> f.outcome() == Outcome.PASS) ? Outcome.PASS : Outcome.FAIL;
    }
}
