package com.huawei.ascend.sit.judge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.huawei.ascend.sit.judge.EvidenceChecks.*;

class EvidenceChecksTest {
    private static final Rule MUST = new Rule("r", "contract#r", Category.EXTERNAL_CONTRACT, Purpose.ACCEPTANCE, Obligation.MUST);
    private static final Context READY = Context.ready("setup#1");
    private static final Observation<Integer> VALUE = Observation.captured(503, EvidenceSource.HTTP, "response");

    @Test void sixOutcomesHaveExplicitMeaning() {
        assertEquals(Outcome.PASS, equalTo(MUST, READY, VALUE, 503).outcome());
        assertEquals(Reason.MUST_VIOLATION, equalTo(MUST, READY, VALUE, 200).reason());
        Rule should = new Rule("s", "contract#s", Category.INTERNAL_EXECUTION_OBSERVABILITY, Purpose.ACCEPTANCE, Obligation.SHOULD);
        assertEquals(Outcome.WARN, equalTo(should, READY, VALUE, 200).outcome());
        assertEquals(Outcome.EXCLUDED, equalTo(MUST, Context.out("profile#r", "OUT"), VALUE, 200).outcome());
        assertEquals(Outcome.UNSUPPORTED, equalTo(MUST, Context.unsupported("capabilities#r", "declared false"), VALUE, 200).outcome());
        assertEquals(Outcome.NOT_OBSERVED, equalTo(MUST, READY,
                Observation.unavailable(EvidenceSource.HTTP, "missing-response"), 503).outcome());
    }

    @Test void absentFieldIsNotAbsentEvidence() {
        assertEquals(Reason.MUST_VIOLATION, equalTo(MUST, READY,
                Observation.captured(null, EvidenceSource.HTTP, "body#/code"), "ERROR").reason());
        assertEquals(Reason.TRACE_MISSING, equalTo(MUST, READY,
                Observation.unavailable(EvidenceSource.HTTP, "body"), "ERROR").reason());
        assertEquals(Reason.PROTOCOL_UNOBSERVABLE, equalTo(MUST, READY,
                Observation.protocolUnobservable(EvidenceSource.A2A, "protocol-contract"), "ERROR").reason());
    }

    @Test void predicatesAreSkippedForNonapplicableUnexecutedAndMissingEvidence() {
        for (Context context : new Context[] {Context.out("profile", "OUT"),
                Context.unsupported("declaration", "not implemented"),
                Context.notTriggered("setup", "condition not reached"),
                Context.blocked(Reason.INFRA_FAILURE, "setup", "server unreachable", null)}) {
            check(MUST, context, VALUE, "503", value -> { throw new AssertionError("must not run"); });
        }
        check(MUST, READY, Observation.unavailable(EvidenceSource.LOG, "trace"), "present",
                value -> { throw new AssertionError("must not run"); });
        Observation.unavailable(EvidenceSource.HTTP, "body").field("code", value -> {
            throw new AssertionError("must not read unavailable body");
        });
    }

    @Test void failAttributionAndIssueReferenceAreRetained() {
        for (Reason reason : new Reason[] {Reason.INFRA_FAILURE, Reason.MODEL_VARIANCE, Reason.OTHER_UNTESTABLE}) {
            Finding finding = equalTo(MUST, Context.blocked(reason, "execution", "cannot execute", "issue/42"), VALUE, 503);
            assertEquals(Outcome.FAIL, finding.outcome());
            assertEquals(reason, finding.reason());
            assertEquals("issue/42", finding.issueRef());
        }
        Finding finding = equalTo(MUST, Context.notTriggered("setup", "condition not reached"), VALUE, 503);
        assertEquals(Outcome.FAIL, finding.outcome());
        assertEquals("conditional_not_triggered", finding.reasonCode());
    }

    @Test void invalidOutcomeReasonPairsAreRejectedExhaustively() {
        for (Outcome outcome : Outcome.values()) for (Reason reason : Reason.values()) {
            boolean valid = switch (outcome) {
                case PASS -> reason == Reason.NONE;
                case FAIL -> java.util.Set.of(Reason.MUST_VIOLATION, Reason.INFRA_FAILURE, Reason.MODEL_VARIANCE,
                        Reason.CONDITIONAL_NOT_TRIGGERED, Reason.OTHER_UNTESTABLE).contains(reason);
                case WARN -> reason == Reason.SHOULD_VIOLATION;
                case EXCLUDED -> reason == Reason.OUT_OF_SCOPE;
                case UNSUPPORTED -> reason == Reason.DECLARED_UNSUPPORTED;
                case NOT_OBSERVED -> reason == Reason.TRACE_MISSING || reason == Reason.PROTOCOL_UNOBSERVABLE;
            };
            Rule rule = reason == Reason.SHOULD_VIOLATION
                    ? new Rule("s", "contract#s", Category.EXTERNAL_CONTRACT, Purpose.ACCEPTANCE, Obligation.SHOULD) : MUST;
            if (valid) assertDoesNotThrow(() -> new Finding(rule, outcome, reason, "expected", "actual",
                    EvidenceSource.HTTP, "evidence", "context", "issue/42"));
            else assertThrows(IllegalArgumentException.class, () -> new Finding(rule, outcome, reason,
                    "expected", "actual", EvidenceSource.HTTP, "evidence", "context", "issue/42"));
        }
    }

    @Test void otherUntestableRequiresAnIssueAndWarningRequiresShould() {
        assertThrows(IllegalArgumentException.class, () -> Context.blocked(Reason.OTHER_UNTESTABLE, "ctx", "blocked", null));
        assertThrows(IllegalArgumentException.class, () -> new Finding(MUST, Outcome.FAIL, Reason.OTHER_UNTESTABLE,
                "expected", "actual", EvidenceSource.HTTP, "evidence", "context", " "));
        assertThrows(IllegalArgumentException.class, () -> new Finding(MUST, Outcome.WARN, Reason.SHOULD_VIOLATION,
                "expected", "actual", EvidenceSource.HTTP, "evidence", "context", null));
        assertThrows(IllegalArgumentException.class, () -> Context.blocked(Reason.TRACE_MISSING, "ctx", "blocked", null));
        assertThrows(IllegalArgumentException.class, () -> Context.unsupported(" ", "no declaration"));
    }

    @Test void categoriesAndSourcesAreIndependentAndMetadataSurvives() {
        for (Category category : Category.values()) for (EvidenceSource source : EvidenceSource.values()) {
            Rule rule = new Rule("r", "contract#r", category, Purpose.ACCEPTANCE, Obligation.MUST);
            Finding finding = equalTo(rule, READY, Observation.captured(503, source, "evidence#1"), 503);
            assertEquals(rule, finding.rule());
            assertEquals(category, finding.rule().category());
            assertEquals(source, finding.source());
            assertEquals("503", finding.expected());
            assertEquals("503", finding.actual());
            assertEquals("setup#1", finding.contextRef());
            assertEquals("evidence#1", finding.evidenceRef());
        }
    }

    @Test void sameComparisonPreservesExternalAndInternalBoundaries() {
        Rule response = new Rule("response.id", "api#id", Category.EXTERNAL_CONTRACT,
                Purpose.ACCEPTANCE, Obligation.MUST);
        Rule audit = new Rule("audit.id", "audit#id", Category.INTERNAL_EXECUTION_OBSERVABILITY,
                Purpose.ACCEPTANCE, Obligation.MUST);
        Finding external = equalTo(response, READY,
                Observation.captured("req-1", EvidenceSource.HTTP, "response#/requestId"), "req-1");
        Finding internal = equalTo(audit, READY,
                Observation.captured("req-1", EvidenceSource.LOG, "audit#/requestId"), "req-1");
        assertEquals(Outcome.PASS, external.outcome());
        assertEquals(Outcome.PASS, internal.outcome());
        assertNotEquals(external.rule().category(), internal.rule().category());
    }

    @Test void httpEvidenceCanDescribeInternalState() {
        Rule rule = new Rule("pool.released", "resource#release", Category.INTERNAL_STATE,
                Purpose.ACCEPTANCE, Obligation.MUST);
        Finding finding = equalTo(rule, READY,
                Observation.captured(0, EvidenceSource.HTTP, "diagnostics#/pool/active"), 0);
        assertEquals(Category.INTERNAL_STATE, finding.rule().category());
        assertEquals(EvidenceSource.HTTP, finding.source());
    }

    @Test void purposeDoesNotChangeTheObservationButControlsAcceptance() {
        for (Purpose purpose : Purpose.values()) {
            Rule rule = new Rule("cache.ready", "cache#ready", Category.INTERNAL_STATE,
                    purpose, Obligation.MUST);
            Finding finding = equalTo(rule, READY,
                    Observation.captured(false, EvidenceSource.REDIS, "cache/probe"), true);
            assertEquals(Outcome.FAIL, finding.outcome());
            assertEquals(purpose == Purpose.ACCEPTANCE, finding.participatesInAcceptance());
            assertEquals(purpose != Purpose.DIAGNOSTIC, finding.blocksAcceptance());
        }
    }

    @Test void requiredAuditLogMayBlockWhileDiagnosticFailureDoesNot() {
        Rule required = new Rule("audit", "security#audit", Category.INTERNAL_EXECUTION_OBSERVABILITY,
                Purpose.ACCEPTANCE, Obligation.MUST);
        Rule diagnostic = new Rule("trace", "diagnostics#trace", Category.INTERNAL_EXECUTION_OBSERVABILITY,
                Purpose.DIAGNOSTIC, Obligation.MUST);
        Observation<Boolean> missing = Observation.unavailable(EvidenceSource.TRACE, "trace/1");
        assertTrue(equalTo(required, READY, missing, true).blocksAcceptance());
        assertFalse(equalTo(diagnostic, READY, missing, true).blocksAcceptance());
        assertThrows(NullPointerException.class, () -> new Rule("r", "ref", Category.INTERNAL_STATE,
                null, Obligation.MUST));
    }

    @Test void unavailableDependencyDocumentDoesNotDeclareUnsupported() {
        Finding finding = check(MUST, Context.blocked(Reason.OTHER_UNTESTABLE, "dependency-doc",
                "contract document unavailable; manual investigation required", "issue/docs-1"), VALUE,
                "known contract", value -> { throw new AssertionError("must not run"); });
        assertEquals(Outcome.FAIL, finding.outcome());
        assertEquals(Reason.OTHER_UNTESTABLE, finding.reason());
    }
}
