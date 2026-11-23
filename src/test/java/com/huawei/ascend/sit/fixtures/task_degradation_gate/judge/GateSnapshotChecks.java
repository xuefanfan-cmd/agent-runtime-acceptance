package com.huawei.ascend.sit.fixtures.task_degradation_gate.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112Contract;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;

import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

/**
 * X family helper: records one finding per expected property of a captured gate snapshot (the
 * {@code degradationGate} object), each with its own feature clause.
 */
public final class GateSnapshotChecks {
    private final Scenario scenario;
    private final String scenarioId;
    private final String label;
    private final Observation<JsonNode> snapshot;

    private GateSnapshotChecks(Scenario scenario, String scenarioId, String label, JsonNode gate, String evidenceRef) {
        this.scenario = scenario;
        this.scenarioId = scenarioId;
        this.label = label;
        this.snapshot = Observation.captured(gate, EvidenceSource.DIAGNOSTIC_SNAPSHOT, evidenceRef);
    }

    /** {@code label} names the moment the snapshot was taken, for example "afterProbe". */
    public static GateSnapshotChecks of(Scenario scenario, String scenarioId, String label, JsonNode gate, String evidenceRef) {
        return new GateSnapshotChecks(scenario, scenarioId, label, gate, evidenceRef);
    }

    private GateSnapshotChecks check(String name, String clauses, String expected, Predicate<JsonNode> predicate) {
        scenario.check(scenarioId, Feat112Contract.rule(scenarioId, label + "." + name, clauses, Category.INTERNAL_STATE),
                snapshot, expected, g -> g != null && predicate.test(g));
        return this;
    }

    public GateSnapshotChecks state(String expected, String clauses) {
        return check("state", clauses, "state=" + expected, g -> expected.equals(g.path("state").asText()));
    }

    public GateSnapshotChecks stateIn(Collection<String> allowed, String clauses) {
        Set<String> states = Set.copyOf(allowed);
        return check("state", clauses, "state in " + states, g -> states.contains(g.path("state").asText()));
    }

    public GateSnapshotChecks samples(long expected, String clauses) {
        return check("samples", clauses, "sampleCount=" + expected, g -> integral(g, "sampleCount", expected));
    }

    public GateSnapshotChecks failures(long expected, String clauses) {
        return check("failures", clauses, "systemFailureCount=" + expected, g -> integral(g, "systemFailureCount", expected));
    }

    public GateSnapshotChecks rate(double expected, String clauses) {
        return check("rate", clauses, "systemFailureRate=" + expected,
                g -> g.path("systemFailureRate").isNumber() && Math.abs(g.path("systemFailureRate").doubleValue() - expected) < 1e-9);
    }

    public GateSnapshotChecks probeInFlight(boolean expected, String clauses) {
        return check("probe", clauses, "probeInFlight=" + expected,
                g -> g.path("probeInFlight").isBoolean() && g.path("probeInFlight").booleanValue() == expected);
    }

    public GateSnapshotChecks retryAfterPositive(String clauses) {
        return check("retryAfter", clauses, "retryAfterSeconds > 0",
                g -> g.path("retryAfterSeconds").isIntegralNumber() && g.path("retryAfterSeconds").longValue() > 0);
    }

    public GateSnapshotChecks retryAfterAbsent(String clauses) {
        return check("retryAfter", clauses, "retryAfterSeconds absent", g -> !g.has("retryAfterSeconds"));
    }

    public GateSnapshotChecks instance(String expectedInstanceId, String clauses) {
        return check("instance", clauses, "instanceId=" + expectedInstanceId,
                g -> g.path("instanceId").isTextual() && g.path("instanceId").textValue().equals(expectedInstanceId));
    }

    public GateSnapshotChecks newInstance(String previousInstanceId, String clauses) {
        return check("newInstance", clauses, "instanceId differs from " + previousInstanceId,
                g -> g.path("instanceId").isTextual() && !g.path("instanceId").textValue().isBlank()
                        && !g.path("instanceId").textValue().equals(previousInstanceId));
    }

    private static boolean integral(JsonNode g, String key, long expected) {
        return g.path(key).isIntegralNumber() && g.path(key).canConvertToLong() && g.path(key).longValue() == expected;
    }
}
