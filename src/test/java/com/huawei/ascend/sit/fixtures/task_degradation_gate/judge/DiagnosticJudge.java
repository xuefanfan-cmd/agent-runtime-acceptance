package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112Contract;
import com.huawei.ascend.sit.judge.EvidenceChecks;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.Context;
import com.huawei.ascend.sit.judge.EvidenceChecks.Finding;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * G family: the instance diagnostic surface. Its shape and state relations (§3.2), its inclusion in the existing
 * service authentication rather than being an anonymous surface (§3.2, acceptance exit 16), and the absence of
 * raw input, outcome, secrets and stack traces.
 */
public final class DiagnosticJudge {
    private DiagnosticJudge() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public static List<Finding> judge(String id, Context context, Observation<Integer> status,
                                      Observation<JsonNode> body, String state, boolean enabled) {
        if (!List.of("CLOSED", "OPEN", "HALF_OPEN").contains(state)) throw new IllegalArgumentException("unknown expected state");
        if (!enabled && !state.equals("CLOSED")) throw new IllegalArgumentException("disabled gate must be CLOSED");
        List<Finding> results = new ArrayList<>();
        results.add(EvidenceChecks.equalTo(rule(id, "http", "§3.2/§5.1.8"), context, status, 200));
        String health = state.equals("CLOSED") ? "healthy" : "degraded";
        results.add(EvidenceChecks.check(rule(id, "status", "§3.2/§5.1.8"), context, body, health,
                n -> n != null && n.path("status").isTextual() && n.path("status").textValue().equals(health)));
        Observation<JsonNode> gate = body.field("degradationGate", n -> n == null ? null : n.get("degradationGate"));
        results.add(EvidenceChecks.check(rule(id, "shape", "§3.2"), context, gate, "diagnostic object",
                n -> n != null && n.isObject()));
        results.add(EvidenceChecks.check(rule(id, "state", "§3.2"), context, gate, state,
                n -> n != null && n.path("state").isTextual() && state.equals(n.path("state").textValue())));
        results.add(EvidenceChecks.check(rule(id, "enabled", "§3.2/能力表“状态可见”"), context, gate, Boolean.toString(enabled),
                n -> n != null && n.path("enabled").isBoolean() && n.path("enabled").booleanValue() == enabled));
        results.add(EvidenceChecks.check(rule(id, "instanceId", "§3.2/§5.1.8"), context, gate, "nonblank instanceId",
                n -> n != null && n.path("instanceId").isTextual() && !n.path("instanceId").textValue().isBlank()));
        results.add(EvidenceChecks.check(rule(id, "counts", "§3.2/§5.1.2"), context, gate,
                "0 <= failures <= samples; rate = failures/samples, 0 for empty window", DiagnosticJudge::countsValid));
        results.add(EvidenceChecks.check(rule(id, "probe", "§3.2"), context, gate, "boolean; false outside HALF_OPEN",
                n -> n != null && n.path("probeInFlight").isBoolean()
                        && (state.equals("HALF_OPEN") || !n.path("probeInFlight").booleanValue())));
        results.add(EvidenceChecks.check(rule(id, "retry", "§3.2/§5.1.8"), context, gate,
                state.equals("OPEN") ? "nonnegative integer retryAfterSeconds" : "retryAfterSeconds absent",
                n -> n != null && (state.equals("OPEN") ? nonnegative(n.get("retryAfterSeconds")) : !n.has("retryAfterSeconds"))));
        if (!enabled) {
            results.add(EvidenceChecks.check(rule(id, "disabledCounts", "§3.2/验收出口16"), context, gate,
                    "zero samples and failures, probe free",
                    n -> n != null && nonnegative(n.get("sampleCount")) && nonnegative(n.get("systemFailureCount"))
                            && n.path("sampleCount").longValue() == 0 && n.path("systemFailureCount").longValue() == 0
                            && n.path("probeInFlight").isBoolean() && !n.path("probeInFlight").booleanValue()));
        }
        return List.copyOf(results);
    }

    /**
     * A diagnostic request without valid service credentials, optionally with the same request against another
     * protected endpoint of the same instance. {@code protectedStatus == null} when the deployment protects no
     * other endpoint either (delivered default without configured authentication).
     */
    public record AuthProbe(int status, String body, Integer protectedStatus, String protectedBody) {}

    public static List<Finding> rejectsUnauthenticated(String id, Context context, Observation<AuthProbe> probe) {
        List<Finding> results = new ArrayList<>();
        results.add(EvidenceChecks.check(rule(id, "rejected", "§3.2/验收出口16"), context, probe,
                "rejected by service authentication (401/403)", p -> p != null && (p.status() == 401 || p.status() == 403)));
        results.add(EvidenceChecks.check(rule(id, "withheld", "§3.2/验收出口16"), context, probe,
                "no diagnostic content", p -> p != null && (p.body() == null || !p.body().contains("degradationGate"))));
        if (probe.collected() && probe.value() != null && probe.value().protectedStatus() != null) {
            results.add(EvidenceChecks.check(rule(id, "sameAsProtected", "§3.2"), context, probe,
                    "same rejection as another protected endpoint", p -> p.status() == p.protectedStatus()
                            && sameBody(p.body(), p.protectedBody())));
        }
        return List.copyOf(results);
    }

    /** Sentinels are values the test itself placed into inputs, outcomes or credentials. */
    public static Finding noLeak(String id, Context context, Observation<String> body, List<String> sentinels) {
        List<String> forbidden = List.copyOf(sentinels);
        return EvidenceChecks.check(rule(id, "noLeak", "§3.2/验收出口16"), context, body,
                "no input/outcome/secret sentinel and no stack trace",
                b -> b != null && forbidden.stream().noneMatch(b::contains) && !b.contains("stackTrace")
                        && !b.contains("\\tat com.") && !b.contains("Exception:"));
    }

    private static boolean sameBody(String a, String b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;
        try {
            return JSON.readTree(a).equals(JSON.readTree(b));
        } catch (Exception notJson) {
            return false;
        }
    }

    private static boolean countsValid(JsonNode n) {
        if (n == null || !nonnegative(n.get("sampleCount")) || !nonnegative(n.get("systemFailureCount"))) return false;
        long samples = n.get("sampleCount").longValue(), failures = n.get("systemFailureCount").longValue();
        JsonNode rate = n.get("systemFailureRate");
        return failures <= samples && rate != null && rate.isNumber() && Double.isFinite(rate.doubleValue())
                && Math.abs(rate.doubleValue() - (samples == 0 ? 0.0 : (double) failures / samples)) <= 1e-9;
    }

    private static boolean nonnegative(JsonNode n) {
        return n != null && n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 0;
    }

    private static EvidenceChecks.Rule rule(String id, String check, String clauses) {
        return Feat112Contract.rule(id, check, clauses, Category.EXTERNAL_CONTRACT);
    }
}
