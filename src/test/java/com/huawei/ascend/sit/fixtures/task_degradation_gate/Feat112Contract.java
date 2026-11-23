package com.huawei.ascend.sit.fixtures.task_degradation_gate;

import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.Obligation;
import com.huawei.ascend.sit.judge.EvidenceChecks.Purpose;
import com.huawei.ascend.sit.judge.EvidenceChecks.Rule;

import java.util.regex.Pattern;

/**
 * The only source of FEAT-112 acceptance expectations: the feature document at the pinned design commit.
 * Every acceptance rule names the clause it enforces; the testplan and the L2 design are derived documents and
 * are never cited as the basis of an expectation.
 */
public final class Feat112Contract {
    private Feat112Contract() {}

    public static final String DESIGN_COMMIT = "05af9cc3796ac953aee157e9c0b35621552232f4";
    public static final String FEATURE = "FEAT-112 feature.md@05af9cc3";
    public static final String ID_PREFIX = "FEAT-112-";

    private static final Pattern CLAUSE = Pattern.compile("§\\d+(\\.\\d+)*|验收出口\\d+|能力表“[^”]+”|场景表“[^”]+”");

    /** Clause list separated by "/", for example {@code §3.2/验收出口16}. */
    public static String ref(String clauses) {
        if (clauses == null || clauses.isBlank()) throw new IllegalArgumentException("clause required");
        for (String clause : clauses.split("/")) {
            if (!CLAUSE.matcher(clause.trim()).matches()) {
                throw new IllegalArgumentException("not a feature clause reference: " + clause);
            }
        }
        return FEATURE + " " + clauses;
    }

    public static Rule rule(String scenarioId, String check, String clauses, Category category) {
        return new Rule(scenarioId + "." + check, ref(clauses), category, Purpose.ACCEPTANCE, Obligation.MUST);
    }

    /** An observation kept for diagnosis that does not decide acceptance. */
    public static Rule observation(String scenarioId, String check, String clauses, Category category) {
        return new Rule(scenarioId + "." + check, ref(clauses), category, Purpose.DIAGNOSTIC, Obligation.MUST);
    }

    public static String id(String shortId) {
        return ID_PREFIX + shortId;
    }
}
