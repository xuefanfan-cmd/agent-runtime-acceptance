package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112Contract;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Finding;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;

import java.util.function.Predicate;

/**
 * Single-property expectations that need no judge family of their own, still recorded as findings with a feature
 * clause and an evidence reference.
 */
public final class Expect {
    private Expect() {}

    public static Finding equal(Scenario s, String scenarioId, String check, String clauses, Category category,
                                EvidenceSource source, String evidenceRef, Object actual, Object expected) {
        return s.equal(scenarioId, Feat112Contract.rule(scenarioId, check, clauses, category),
                Observation.captured(actual, source, evidenceRef), expected);
    }

    public static <T> Finding that(Scenario s, String scenarioId, String check, String clauses, Category category,
                                   EvidenceSource source, String evidenceRef, T actual, String expected,
                                   Predicate<? super T> predicate) {
        return s.check(scenarioId, Feat112Contract.rule(scenarioId, check, clauses, category),
                Observation.captured(actual, source, evidenceRef), expected, v -> v != null && predicate.test(v));
    }
}
