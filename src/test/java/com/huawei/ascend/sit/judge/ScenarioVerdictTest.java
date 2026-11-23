package com.huawei.ascend.sit.judge;

import com.huawei.ascend.sit.judge.EvidenceChecks.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Self-tests of verdict derivation: synthetic records, no SUT. */
@Tag("selftest")
class ScenarioVerdictTest {
    private static Rule acceptance(String id) {
        return new Rule(id, "contract §1", Category.EXTERNAL_CONTRACT, Purpose.ACCEPTANCE, Obligation.MUST);
    }

    private static Scenario scenario(String... ids) {
        return new Scenario(new ScenarioVerdict(List.of(ids), AcceptanceLayer.PUBLIC_SURFACE), "Selftest#method");
    }

    private static Observation<Integer> observed(int value) {
        return Observation.captured(value, EvidenceSource.HTTP, "exchange-1");
    }

    @Test
    void passingPreconditionAndFindingsPass() {
        Scenario s = scenario("S-1");
        s.precondition("gate OPEN", true, EvidenceSource.DIAGNOSTIC_SNAPSHOT, "snapshot-1");
        s.equal(acceptance("S-1.status"), observed(503), 503);
        var result = s.verdict().results().getFirst();
        assertEquals(Outcome.PASS, result.outcome());
        assertFalse(result.blocking());
    }

    @Test
    void failedPreconditionInvalidatesConclusionInsteadOfBlamingProduct() {
        Scenario s = scenario("S-1");
        var thrown = assertThrows(PreconditionNotMet.class,
                () -> s.precondition("gate OPEN", false, EvidenceSource.DIAGNOSTIC_SNAPSHOT, "snapshot-1"));
        assertEquals(Reason.CONDITIONAL_NOT_TRIGGERED, thrown.reason());
        var result = s.verdict().results().getFirst();
        assertEquals(Outcome.FAIL, result.outcome());
        assertEquals(Reason.CONDITIONAL_NOT_TRIGGERED, result.reason());
        assertTrue(result.detail().startsWith("precondition"));
    }

    @Test
    void missingActivationIsDistinctFromMissingInfrastructure() {
        Scenario activation = scenario("S-1");
        assertThrows(PreconditionNotMet.class,
                () -> activation.activation("fault served by stub", false, EvidenceSource.STUB, "stats"));
        assertEquals(Reason.CONDITIONAL_NOT_TRIGGERED, activation.verdict().results().getFirst().reason());
        Scenario infra = scenario("S-1");
        assertThrows(PreconditionNotMet.class,
                () -> infra.infrastructure("artifact installed", false, EvidenceSource.PROCESS, "m2"));
        assertEquals(Reason.INFRA_FAILURE, infra.verdict().results().getFirst().reason());
    }

    @Test
    void judgingWithoutRecordedPreconditionIsRefused() {
        Scenario s = scenario("S-1");
        assertThrows(IllegalStateException.class, () -> s.equal(acceptance("S-1.status"), observed(503), 503));
    }

    @Test
    void executionWithoutAcceptanceFindingDoesNotPass() {
        Scenario s = scenario("S-1");
        s.precondition("deployment running", true, EvidenceSource.PROCESS, "pid-1");
        var result = s.verdict().results().getFirst();
        assertEquals(Outcome.FAIL, result.outcome());
        assertTrue(result.detail().contains("no acceptance finding"));
    }

    @Test
    void productViolationOutranksLaterInfrastructureTermination() {
        Scenario s = scenario("S-1");
        s.precondition("deployment running", true, EvidenceSource.PROCESS, "pid-1");
        s.equal(acceptance("S-1.status"), observed(200), 503);
        s.verdict().terminate(new java.io.IOException("connection reset"));
        var result = s.verdict().results().getFirst();
        assertEquals(Reason.MUST_VIOLATION, result.reason());
    }

    @Test
    void terminationWithoutViolationIsInfraFailure() {
        Scenario s = scenario("S-1");
        s.precondition("deployment running", true, EvidenceSource.PROCESS, "pid-1");
        s.equal(acceptance("S-1.status"), observed(503), 503);
        s.verdict().terminate(new java.io.IOException("connection reset"));
        var result = s.verdict().results().getFirst();
        assertEquals(Outcome.FAIL, result.outcome());
        assertEquals(Reason.INFRA_FAILURE, result.reason());
    }

    @Test
    void multiScenarioFindingsAreAttributedPerScenario() {
        Scenario s = scenario("S-1", "S-2");
        s.precondition("shared state", true, EvidenceSource.DIAGNOSTIC_SNAPSHOT, "snapshot");
        s.equal("S-1", acceptance("S-1.status"), observed(503), 503);
        s.equal("S-2", acceptance("S-2.status"), observed(200), 503);
        Map<String, Outcome> outcomes = s.verdict().results().stream()
                .collect(java.util.stream.Collectors.toMap(ScenarioVerdict.Result::scenarioId, ScenarioVerdict.Result::outcome));
        assertEquals(Map.of("S-1", Outcome.PASS, "S-2", Outcome.FAIL), outcomes);
        assertThrows(IllegalStateException.class, s::id);
        assertThrows(IllegalArgumentException.class, () -> s.equal("S-3", acceptance("S-3.status"), observed(503), 503));
    }

    @Test
    void unavailableEvidenceIsNotObservedAndDiagnosticFindingsDoNotDecide() {
        Scenario s = scenario("S-1");
        s.precondition("deployment running", true, EvidenceSource.PROCESS, "pid-1");
        s.check(acceptance("S-1.body"), Observation.unavailable(EvidenceSource.HTTP, "missing"), "body", v -> true);
        assertEquals(Outcome.NOT_OBSERVED, s.verdict().results().getFirst().outcome());
        Scenario diagnostic = scenario("S-1");
        diagnostic.precondition("deployment running", true, EvidenceSource.PROCESS, "pid-1");
        Rule observe = new Rule("S-1.observe", "contract §1", Category.INTERNAL_STATE, Purpose.DIAGNOSTIC, Obligation.MUST);
        diagnostic.equal(observe, observed(200), 503);
        diagnostic.equal(acceptance("S-1.status"), observed(503), 503);
        assertEquals(Outcome.PASS, diagnostic.verdict().results().getFirst().outcome());
    }

    @Test
    void requireStopsOnlyOnBlockingFindings() {
        Scenario s = scenario("S-1");
        s.precondition("deployment running", true, EvidenceSource.PROCESS, "pid-1");
        assertDoesNotThrow(() -> s.require(s.equal(acceptance("S-1.a"), observed(1), 1)));
        assertThrows(AcceptanceFailure.class, () -> s.require(s.equal(acceptance("S-1.b"), observed(2), 1)));
    }

    @Test
    void preconditionRulesCannotBeRecordedAsAcceptance() {
        var verdict = new ScenarioVerdict(List.of("S-1"), AcceptanceLayer.COMPONENT);
        Rule pre = new Rule("S-1.pre", "setup", Category.INTERNAL_STATE, Purpose.PRECONDITION, Obligation.MUST);
        Finding f = EvidenceChecks.equalTo(pre, Context.ready("x"), observed(1), 1);
        assertThrows(IllegalArgumentException.class, () -> verdict.record(new ScenarioVerdict.Entry("S-1", ScenarioVerdict.Phase.ACCEPTANCE, f)));
    }

    @Test
    void reportTakesMostSevereOutcomeAndListsUnexecutedScenarios(@TempDir Path run) throws Exception {
        Scenario pass = scenario("S-1");
        pass.precondition("p", true, EvidenceSource.PROCESS, "pid");
        pass.equal(acceptance("S-1.a"), observed(1), 1);
        pass.artifact(ArtifactIdentity.unidentified("DA process", "g:a:1", "remote instance"));
        pass.verdict().write(run.resolve("a.json"), "run", "A#a", "t0", "t1");
        Scenario fail = scenario("S-1");
        fail.precondition("p", true, EvidenceSource.PROCESS, "pid");
        fail.equal(acceptance("S-1.a"), observed(2), 1);
        fail.verdict().write(run.resolve("b.json"), "run", "B#b", "t0", "t1");
        var summary = AcceptanceReport.aggregate(run);
        assertEquals(Outcome.FAIL, summary.get("S-1").outcome());
        assertEquals(List.of("A#a", "B#b"), summary.get("S-1").tests());
        String table = AcceptanceReport.markdown(summary, List.of("S-1", "S-2"), Map.of("S-2", "registered gap"));
        assertTrue(table.contains("| S-2 | NOT_EXECUTED |"));
        assertTrue(table.contains("registered gap"));
        assertTrue(Files.readString(run.resolve("a.json")).contains("unidentified") || Files.readString(run.resolve("a.json")).contains("remote instance"));
    }

    @Test
    void artifactIdentityRequiresChecksumOrReason(@TempDir Path dir) throws Exception {
        Path jar = Files.writeString(dir.resolve("x.jar"), "bytes");
        var identity = ArtifactIdentity.ofFile("DA", "g:a:1", jar, "abc123");
        assertTrue(identity.identified());
        assertEquals(64, identity.sha256().length());
        assertThrows(IllegalArgumentException.class, () -> new ArtifactIdentity("DA", "g:a:1", null, null, null, " "));
        assertThrows(java.io.IOException.class, () -> ArtifactIdentity.ofFile("DA", "g:a:1", dir.resolve("missing.jar"), null));
    }

    @Test
    void provenanceIsRecordedAndDisagreementIsFlaggedInTheHeader(@TempDir Path run) throws Exception {
        String key = RunProvenance.PREFIX + "test-revision";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "abc123");
            Scenario first = scenario("S-1");
            first.precondition("p", true, EvidenceSource.PROCESS, "pid");
            first.equal(acceptance("S-1.a"), observed(1), 1);
            first.artifact(ArtifactIdentity.unidentified("DA process", "g:a:1", "remote instance"));
            first.verdict().write(run.resolve("a.json"), "run", "A#a", "t0", "t1");
            assertTrue(Files.readString(run.resolve("a.json")).contains("\"test-revision\" : \"abc123\""));

            System.setProperty(key, "def456");
            Scenario second = scenario("S-2");
            second.precondition("p", true, EvidenceSource.PROCESS, "pid");
            second.equal(acceptance("S-2.a"), observed(2), 1);
            second.verdict().write(run.resolve("b.json"), "run", "B#b", "t0", "t1");

            var summary = AcceptanceReport.aggregate(run);
            String header = AcceptanceReport.header(run, summary, List.of("S-1", "S-2", "S-3"));
            assertTrue(header.contains("范围内场景 3 个，有执行记录 2 个"), header);
            assertTrue(header.contains("test-revision：abc123 ／ def456（⚠ 本次运行的记录不一致）"), header);
            assertTrue(header.contains("FAIL/must_violation=1"), header);
            assertTrue(header.contains("g:a:1"), header);
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @Test
    void headerSaysWhenNoProvenanceWasDeclared(@TempDir Path run) throws Exception {
        Scenario only = scenario("S-1");
        only.precondition("p", true, EvidenceSource.PROCESS, "pid");
        only.equal(acceptance("S-1.a"), observed(1), 1);
        only.verdict().write(run.resolve("a.json"), "run", "A#a", "t0", "t1");
        String header = AcceptanceReport.header(run, AcceptanceReport.aggregate(run), List.of("S-1"));
        assertTrue(RunProvenance.current().isEmpty() || header.contains("来源："), header);
        if (RunProvenance.current().isEmpty()) assertTrue(header.contains("结果记录未声明"), header);
    }
}
