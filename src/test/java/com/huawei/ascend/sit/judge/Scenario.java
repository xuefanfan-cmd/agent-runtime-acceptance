package com.huawei.ascend.sit.judge;

import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.Context;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Finding;
import com.huawei.ascend.sit.judge.EvidenceChecks.Obligation;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.EvidenceChecks.Outcome;
import com.huawei.ascend.sit.judge.EvidenceChecks.Purpose;
import com.huawei.ascend.sit.judge.EvidenceChecks.Reason;
import com.huawei.ascend.sit.judge.EvidenceChecks.Rule;
import com.huawei.ascend.sit.judge.ScenarioVerdict.Entry;
import com.huawei.ascend.sit.judge.ScenarioVerdict.Phase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Recorder handed to a test execution. Tests and drivers state what the verdict rests on (preconditions,
 * activation evidence, artifacts) and record acceptance findings; they never assert outcomes directly.
 *
 * <p>Acceptance findings can only be produced after at least one precondition or activation has been recorded:
 * the judging context is derived from that evidence, never declared by a label.
 */
public final class Scenario {
    private final ScenarioVerdict verdict;
    private final String testId;
    private final List<String> gateEvidence = new ArrayList<>();
    private int sequence;

    public Scenario(ScenarioVerdict verdict, String testId) {
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        if (testId == null || testId.isBlank()) throw new IllegalArgumentException("testId required");
        this.testId = testId;
    }

    public List<String> ids() {
        return verdict.scenarioIds();
    }

    /** The single covered scenario; executions covering several scenarios must name the one they judge. */
    public String id() {
        if (ids().size() != 1) throw new IllegalStateException("execution covers " + ids() + "; name the scenario");
        return ids().getFirst();
    }

    public AcceptanceLayer layer() {
        return verdict.layer();
    }

    public ScenarioVerdict verdict() {
        return verdict;
    }

    /** A state the scenario needs (deployment form, gate state, task terminal). Failure invalidates the run. */
    public void precondition(String requirement, boolean holds, EvidenceSource source, String evidenceRef) {
        gate(Phase.PRECONDITION, Reason.CONDITIONAL_NOT_TRIGGERED, requirement, holds, source, evidenceRef);
    }

    /** Proof that the condition the scenario is about actually occurred through the declared path. */
    public void activation(String requirement, boolean proven, EvidenceSource source, String evidenceRef) {
        gate(Phase.ACTIVATION, Reason.CONDITIONAL_NOT_TRIGGERED, requirement, proven, source, evidenceRef);
    }

    /** A resource the execution needs (artifact, process, endpoint) is available. Failure is infra_failure. */
    public void infrastructure(String requirement, boolean available, EvidenceSource source, String evidenceRef) {
        gate(Phase.PRECONDITION, Reason.INFRA_FAILURE, requirement, available, source, evidenceRef);
    }

    public void artifact(ArtifactIdentity identity) {
        verdict.artifact(identity);
    }

    private synchronized void gate(Phase phase, Reason failure, String requirement, boolean holds,
                                   EvidenceSource source, String evidenceRef) {
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("requirement required");
        String reference = evidenceRef == null || evidenceRef.isBlank() ? "no evidence reference" : evidenceRef;
        Rule rule = new Rule(testId + "." + phase.name().toLowerCase() + "." + (++sequence),
                "test " + phase.name().toLowerCase() + ": " + requirement, Category.INTERNAL_STATE,
                Purpose.PRECONDITION, Obligation.MUST);
        Finding finding = new Finding(rule, holds ? Outcome.PASS : Outcome.FAIL, holds ? Reason.NONE : failure,
                requirement, holds ? "established" : "not established", source, reference, "execution " + testId, null);
        verdict.record(new Entry(null, phase, finding));
        if (!holds) throw new PreconditionNotMet(failure, requirement, reference);
        gateEvidence.add(reference);
    }

    /** Judging context derived from the recorded preconditions and activation evidence. */
    public synchronized Context context() {
        if (gateEvidence.isEmpty()) {
            throw new IllegalStateException("record the precondition this verdict rests on before judging");
        }
        return Context.ready(String.join(" ; ", gateEvidence));
    }

    public <T> Finding check(String scenarioId, Rule rule, Observation<T> observation, String expected,
                             Predicate<? super T> predicate) {
        Finding finding = EvidenceChecks.check(rule, context(), observation, expected, predicate);
        verdict.record(new Entry(requireCovered(scenarioId), Phase.ACCEPTANCE, finding));
        return finding;
    }

    public <T> Finding check(Rule rule, Observation<T> observation, String expected, Predicate<? super T> predicate) {
        return check(id(), rule, observation, expected, predicate);
    }

    public <T> Finding equal(String scenarioId, Rule rule, Observation<T> observation, T expected) {
        return check(scenarioId, rule, observation, String.valueOf(expected), actual -> Objects.equals(expected, actual));
    }

    public <T> Finding equal(Rule rule, Observation<T> observation, T expected) {
        return equal(id(), rule, observation, expected);
    }

    /** Records findings a judge family produced with {@link #context()}. */
    public List<Finding> accept(String scenarioId, Collection<Finding> findings) {
        String id = requireCovered(scenarioId);
        for (Finding f : findings) verdict.record(new Entry(id, Phase.ACCEPTANCE, f));
        return List.copyOf(findings);
    }

    public List<Finding> accept(Collection<Finding> findings) {
        return accept(id(), findings);
    }

    /** The finding is already recorded; stop here when later steps depend on it having passed. */
    public Finding require(Finding finding) {
        if (finding.outcome() == Outcome.FAIL || finding.outcome() == Outcome.NOT_OBSERVED) {
            throw new AcceptanceFailure(finding.ruleId() + ": expected " + finding.expected() + ", actual "
                    + finding.actual() + " [evidence " + finding.evidenceRef() + "]");
        }
        return finding;
    }

    public List<Finding> requireAll(List<Finding> findings) {
        findings.forEach(this::require);
        return findings;
    }

    private String requireCovered(String scenarioId) {
        if (!ids().contains(scenarioId)) throw new IllegalArgumentException(scenarioId + " is not covered by " + testId);
        return scenarioId;
    }
}
