package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112Contract;
import com.huawei.ascend.sit.judge.EvidenceChecks;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.Context;
import com.huawei.ascend.sit.judge.EvidenceChecks.Finding;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * R family: the shared 503 refusal of both new-task entries. Pure function of captured evidence; no network,
 * clock or SUT access. Ordinary extension fields, JSON key order and a charset parameter are legal variants.
 */
public final class RejectionJudge {
    private RejectionJudge() {}

    public static final String ERROR = "Agent is temporarily not accepting new tasks";

    public record Rejection(int status, Map<String, List<String>> headers, JsonNode body) {
        public Optional<String> header(String name) {
            if (headers == null) return Optional.empty();
            return headers.entrySet().stream().filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).findFirst();
        }
    }

    public static List<Finding> judge(String scenarioId, Context context, Observation<Rejection> evidence,
                                      String expectedState, String expectedInstanceId) {
        if (!List.of("OPEN", "HALF_OPEN").contains(expectedState)) throw new IllegalArgumentException("OPEN or HALF_OPEN");
        Objects.requireNonNull(expectedInstanceId, "expectedInstanceId");
        boolean open = expectedState.equals("OPEN");
        return List.of(
                check(scenarioId, "status", "§3.1/验收出口7", context, evidence, "HTTP 503", r -> r.status() == 503),
                check(scenarioId, "contentType", "§3.1/§5.1.5", context, evidence, "application/json before any SSE",
                        r -> r.header("Content-Type").map(v -> v.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                                .filter("application/json"::equals).isPresent()),
                check(scenarioId, "object", "§3.1", context, evidence, "JSON object body",
                        r -> r.body() != null && r.body().isObject()),
                check(scenarioId, "code", "§3.1", context, evidence, "code=ENGINE_DEGRADED",
                        r -> text(r.body(), "code", "ENGINE_DEGRADED")),
                check(scenarioId, "error", "§3.1", context, evidence, "error=" + ERROR, r -> text(r.body(), "error", ERROR)),
                check(scenarioId, "degraded", "验收出口7", context, evidence, "boolean degraded=true",
                        r -> bool(r.body(), "degraded")),
                check(scenarioId, "retryable", "验收出口7", context, evidence, "boolean retryable=true",
                        r -> bool(r.body(), "retryable")),
                check(scenarioId, "state", "§3.1", context, evidence, "state=" + expectedState,
                        r -> text(r.body(), "state", expectedState)),
                check(scenarioId, "instanceId", "§3.1/验收出口17", context, evidence, "instanceId=" + expectedInstanceId,
                        r -> text(r.body(), "instanceId", expectedInstanceId)),
                check(scenarioId, "retry", "§3.1/验收出口7", context, evidence,
                        open ? "nonnegative integer retryAfterSeconds equal to Retry-After" : "retryAfterSeconds and Retry-After omitted",
                        r -> open ? openRetry(r) : halfOpenRetry(r)));
    }

    private static Finding check(String id, String name, String clauses, Context context, Observation<Rejection> evidence,
                                 String expected, java.util.function.Predicate<Rejection> predicate) {
        return EvidenceChecks.check(Feat112Contract.rule(id, name, clauses, Category.EXTERNAL_CONTRACT), context, evidence,
                expected, r -> r != null && predicate.test(r));
    }

    private static boolean text(JsonNode body, String field, String expected) {
        return body != null && body.path(field).isTextual() && body.path(field).textValue().equals(expected);
    }

    private static boolean bool(JsonNode body, String field) {
        return body != null && body.path(field).isBoolean() && body.path(field).booleanValue();
    }

    private static boolean openRetry(Rejection r) {
        JsonNode seconds = r.body() == null ? null : r.body().get("retryAfterSeconds");
        if (seconds == null || !seconds.isIntegralNumber() || !seconds.canConvertToLong() || seconds.longValue() < 0) return false;
        return r.header("Retry-After").map(String::trim).filter(Long.toString(seconds.longValue())::equals).isPresent();
    }

    private static boolean halfOpenRetry(Rejection r) {
        return r.body() != null && !r.body().has("retryAfterSeconds") && r.header("Retry-After").isEmpty();
    }
}
