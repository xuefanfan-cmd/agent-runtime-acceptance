package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112Contract;
import com.huawei.ascend.sit.judge.EvidenceChecks;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.Context;
import com.huawei.ascend.sit.judge.EvidenceChecks.Finding;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;

import java.util.List;
import java.util.Objects;

/**
 * O family: under OPEN/HALF_OPEN an invalid request still receives the error it gets with the gate out of the way
 * (§5.1.9 "返回既有校验错误", acceptance exit 18). The oracle is the same request answered while the gate is
 * CLOSED, so the expectation never copies an implementation's message text.
 */
public final class PrecedenceJudge {
    private PrecedenceJudge() {}

    public record Reply(int status, String code, String error, boolean degraded) {
        public static Reply of(int status, JsonNode body) {
            String code = body != null && body.path("code").isTextual() ? body.path("code").textValue() : null;
            String error = body != null && body.path("error").isTextual() ? body.path("error").textValue() : null;
            boolean degraded = "ENGINE_DEGRADED".equals(code)
                    || body != null && body.path("degraded").isBoolean() && body.path("degraded").booleanValue();
            return new Reply(status, code, error, degraded);
        }

        /** The request was refused by validation or authentication, not served and not degraded. */
        public boolean refusedByExistingContract() {
            return status >= 400 && !degraded;
        }
    }

    /** {@code ungated}: the same request answered while the gate was CLOSED on the same instance. */
    public record Comparison(Reply ungated, Reply gated) {}

    public static List<Finding> judge(String id, Context context, Observation<Comparison> evidence) {
        Finding existing = EvidenceChecks.check(Feat112Contract.rule(id, "existingError", "§5.1.9/验收出口18",
                        Category.EXTERNAL_CONTRACT), context, evidence, "same status, code and error as with the gate CLOSED",
                c -> c != null && c.gated().status() == c.ungated().status()
                        && Objects.equals(c.gated().code(), c.ungated().code())
                        && Objects.equals(c.gated().error(), c.ungated().error()));
        Finding notDegraded = EvidenceChecks.check(Feat112Contract.rule(id, "notDegraded", "§3.1/§5.1.9",
                        Category.EXTERNAL_CONTRACT), context, evidence, "not ENGINE_DEGRADED",
                c -> c != null && !c.gated().degraded());
        return List.of(existing, notDegraded);
    }
}
