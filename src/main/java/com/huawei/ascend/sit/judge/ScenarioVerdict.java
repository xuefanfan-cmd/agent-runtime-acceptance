package com.huawei.ascend.sit.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.judge.EvidenceChecks.Finding;
import com.huawei.ascend.sit.judge.EvidenceChecks.Outcome;
import com.huawei.ascend.sit.judge.EvidenceChecks.Purpose;
import com.huawei.ascend.sit.judge.EvidenceChecks.Reason;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * What one test execution's verdict rests on: preconditions, activation evidence, acceptance findings, the
 * artifacts under test and how execution ended. Scenario outcomes are derived from these records; a test never
 * asserts an outcome directly.
 *
 * <p>Derivation order per scenario: a failed precondition or missing activation makes the conclusion invalid
 * (FAIL with that reason, normally conditional_not_triggered); then any acceptance FAIL; then an execution that
 * ended before a verdict (infra_failure); then a missing verdict; then NOT_OBSERVED, WARN, and PASS.
 */
public final class ScenarioVerdict {
    public enum Phase { PRECONDITION, ACTIVATION, ACCEPTANCE }

    /** {@code scenarioId == null} applies the entry to every covered scenario. */
    public record Entry(String scenarioId, Phase phase, Finding finding) {
        public Entry {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(finding, "finding");
            boolean gate = phase != Phase.ACCEPTANCE;
            if (gate != (finding.rule().purpose() == Purpose.PRECONDITION)) {
                throw new IllegalArgumentException("precondition/activation entries need PRECONDITION rules; "
                        + "acceptance entries need ACCEPTANCE or DIAGNOSTIC rules: " + finding.ruleId());
            }
        }
    }

    public record Termination(String type, String message) {}

    public record Result(String scenarioId, AcceptanceLayer layer, Outcome outcome, Reason reason, String detail) {
        /** Product acceptance is blocked; JUnit reports the execution as failed. */
        public boolean blocking() {
            return outcome == Outcome.FAIL || outcome == Outcome.NOT_OBSERVED;
        }
    }

    private final List<String> scenarioIds;
    private final AcceptanceLayer layer;
    private final List<Entry> entries = new ArrayList<>();
    private final List<ArtifactIdentity> artifacts = new ArrayList<>();
    private Termination termination;

    public ScenarioVerdict(List<String> scenarioIds, AcceptanceLayer layer) {
        if (scenarioIds == null || scenarioIds.isEmpty()) throw new IllegalArgumentException("at least one scenario ID");
        for (String id : scenarioIds) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("blank scenario ID");
        }
        if (new HashSet<>(scenarioIds).size() != scenarioIds.size()) throw new IllegalArgumentException("duplicate scenario ID");
        this.scenarioIds = List.copyOf(scenarioIds);
        this.layer = Objects.requireNonNull(layer, "layer");
    }

    public List<String> scenarioIds() {
        return scenarioIds;
    }

    public AcceptanceLayer layer() {
        return layer;
    }

    public synchronized void record(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.scenarioId() != null && !scenarioIds.contains(entry.scenarioId())) {
            throw new IllegalArgumentException("finding for a scenario this execution does not cover: " + entry.scenarioId());
        }
        entries.add(entry);
    }

    public synchronized void artifact(ArtifactIdentity identity) {
        artifacts.add(Objects.requireNonNull(identity, "identity"));
    }

    /** Records an execution that ended with an exception that is neither a precondition failure nor a verdict. */
    public synchronized void terminate(Throwable thrown) {
        Objects.requireNonNull(thrown, "thrown");
        if (termination == null) termination = new Termination(thrown.getClass().getName(), String.valueOf(thrown.getMessage()));
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    public synchronized List<ArtifactIdentity> artifacts() {
        return List.copyOf(artifacts);
    }

    public synchronized List<Result> results() {
        List<Result> results = new ArrayList<>();
        for (String id : scenarioIds) results.add(result(id));
        return List.copyOf(results);
    }

    private Result result(String id) {
        List<Entry> applicable = entries.stream()
                .filter(e -> e.scenarioId() == null || e.scenarioId().equals(id)).toList();
        for (Phase gate : List.of(Phase.PRECONDITION, Phase.ACTIVATION)) {
            for (Entry entry : applicable) {
                Finding f = entry.finding();
                if (entry.phase() == gate && f.outcome() != Outcome.PASS) {
                    return new Result(id, layer, Outcome.FAIL, f.reason(), describe(gate.name().toLowerCase(), f));
                }
            }
        }
        List<Finding> acceptance = applicable.stream().filter(e -> e.phase() == Phase.ACCEPTANCE)
                .map(Entry::finding).filter(Finding::participatesInAcceptance).toList();
        for (Finding f : acceptance) {
            if (f.outcome() == Outcome.FAIL) return new Result(id, layer, Outcome.FAIL, f.reason(), describe("acceptance", f));
        }
        if (termination != null) {
            return new Result(id, layer, Outcome.FAIL, Reason.INFRA_FAILURE,
                    "execution ended before a verdict: " + termination.type() + ": " + termination.message());
        }
        if (acceptance.isEmpty()) {
            return new Result(id, layer, Outcome.FAIL, Reason.INFRA_FAILURE, "no acceptance finding recorded for " + id);
        }
        for (Outcome severity : List.of(Outcome.NOT_OBSERVED, Outcome.WARN)) {
            for (Finding f : acceptance) {
                if (f.outcome() == severity) return new Result(id, layer, severity, f.reason(), describe("acceptance", f));
            }
        }
        if (acceptance.stream().allMatch(f -> f.outcome() == Outcome.EXCLUDED)) {
            return new Result(id, layer, Outcome.EXCLUDED, Reason.OUT_OF_SCOPE, acceptance.getFirst().contextRef());
        }
        if (acceptance.stream().allMatch(f -> f.outcome() == Outcome.UNSUPPORTED)) {
            return new Result(id, layer, Outcome.UNSUPPORTED, Reason.DECLARED_UNSUPPORTED, acceptance.getFirst().contextRef());
        }
        return new Result(id, layer, Outcome.PASS, Reason.NONE, acceptance.size() + " acceptance findings passed");
    }

    private static String describe(String phase, Finding f) {
        return phase + " " + f.ruleId() + ": expected " + f.expected() + ", actual " + f.actual()
                + " [evidence " + f.evidenceRef() + "]";
    }

    /** Machine-readable record of this execution; the evidence files it names stay where they were written. */
    public synchronized ObjectNode toJson(String runId, String testId, String startedAt, String endedAt) {
        ObjectMapper json = new ObjectMapper();
        ObjectNode root = json.createObjectNode();
        root.put("runId", runId).put("test", testId).put("layer", layer.name())
                .put("startedAt", startedAt).put("endedAt", endedAt);
        ObjectNode provenance = root.putObject("provenance");
        RunProvenance.current().forEach(provenance::put);
        ArrayNode scenarios = root.putArray("scenarios");
        for (Result r : results()) {
            scenarios.addObject().put("id", r.scenarioId()).put("outcome", r.outcome().name())
                    .put("reason", r.reason().code()).put("detail", r.detail());
        }
        ArrayNode artifactArray = root.putArray("artifacts");
        for (ArtifactIdentity a : artifacts) {
            artifactArray.addObject().put("role", a.role()).put("coordinates", a.coordinates())
                    .put("location", a.location()).put("sha256", a.sha256())
                    .put("sourceRevision", a.sourceRevision()).put("note", a.note());
        }
        ArrayNode findings = root.putArray("findings");
        for (Entry e : entries) {
            Finding f = e.finding();
            findings.addObject().put("scenarioId", e.scenarioId()).put("phase", e.phase().name())
                    .put("rule", f.ruleId()).put("contractRef", f.rule().contractRef())
                    .put("category", f.rule().category().name()).put("purpose", f.rule().purpose().name())
                    .put("outcome", f.outcome().name()).put("reason", f.reasonCode())
                    .put("expected", f.expected()).put("actual", f.actual()).put("source", f.source().name())
                    .put("evidenceRef", f.evidenceRef()).put("contextRef", f.contextRef());
        }
        if (termination != null) {
            root.putObject("termination").put("type", termination.type()).put("message", termination.message());
        }
        return root;
    }

    public void write(Path file, String runId, String testId, String startedAt, String endedAt) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(file, json.writeValueAsString(toJson(runId, testId, startedAt, endedAt)), StandardCharsets.UTF_8);
    }
}
