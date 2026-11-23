package com.huawei.ascend.sit.judge;

import java.util.Objects;
import java.util.function.Predicate;

/** Evidence-based verdicts; callers supply applicability and independently collected evidence. */
public final class EvidenceChecks {
    private EvidenceChecks() {}

    public enum Outcome { PASS, FAIL, WARN, EXCLUDED, UNSUPPORTED, NOT_OBSERVED }
    public enum Category { EXTERNAL_CONTRACT, INTERNAL_STATE, INTERNAL_EXECUTION_OBSERVABILITY }
    public enum Purpose { PRECONDITION, ACCEPTANCE, DIAGNOSTIC }
    /** PRODUCT_API: return values of real product classes driven in-process (component-level acceptance). */
    public enum EvidenceSource { HTTP, A2A, SSE, DIAGNOSTIC_SNAPSHOT, DB, REDIS, STUB, LOG, TRACE, PROCESS, PRODUCT_API }
    public enum Obligation { MUST, SHOULD }
    public enum Reason {
        NONE, MUST_VIOLATION, INFRA_FAILURE, MODEL_VARIANCE, CONDITIONAL_NOT_TRIGGERED,
        OTHER_UNTESTABLE, PROTOCOL_UNOBSERVABLE, TRACE_MISSING, SHOULD_VIOLATION,
        OUT_OF_SCOPE, DECLARED_UNSUPPORTED;

        public String code() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Rule(String id, String contractRef, Category category, Purpose purpose, Obligation obligation) {
        public Rule {
            requireText(id, "id");
            requireText(contractRef, "contractRef");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(obligation, "obligation");
        }
    }

    public enum ExecutionState { READY, OUT, DECLARED_UNSUPPORTED, NOT_TRIGGERED, BLOCKED }

    /** OUT and unsupported require an explicit scope/capability declaration, never an absent document. */
    public record Context(ExecutionState state, Reason reason, String reference, String detail, String issueRef) {
        public Context {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
            requireText(reference, "context reference");
            requireText(detail, "context detail");
            boolean valid = switch (state) {
                case READY -> reason == Reason.NONE;
                case OUT -> reason == Reason.OUT_OF_SCOPE;
                case DECLARED_UNSUPPORTED -> reason == Reason.DECLARED_UNSUPPORTED;
                case NOT_TRIGGERED -> reason == Reason.CONDITIONAL_NOT_TRIGGERED;
                case BLOCKED -> reason == Reason.INFRA_FAILURE || reason == Reason.MODEL_VARIANCE
                        || reason == Reason.OTHER_UNTESTABLE;
            };
            if (!valid) throw new IllegalArgumentException("Invalid execution state/reason pair");
            if (reason == Reason.OTHER_UNTESTABLE) requireText(issueRef, "issueRef");
        }
        public static Context ready(String reference) {
            return new Context(ExecutionState.READY, Reason.NONE, reference, "execution established", null);
        }
        public static Context out(String reference, String detail) {
            return new Context(ExecutionState.OUT, Reason.OUT_OF_SCOPE, reference, detail, null);
        }
        public static Context unsupported(String declarationRef, String detail) {
            return new Context(ExecutionState.DECLARED_UNSUPPORTED, Reason.DECLARED_UNSUPPORTED,
                    declarationRef, detail, null);
        }
        public static Context notTriggered(String reference, String detail) {
            return new Context(ExecutionState.NOT_TRIGGERED, Reason.CONDITIONAL_NOT_TRIGGERED,
                    reference, detail, null);
        }
        public static Context blocked(Reason reason, String reference, String detail, String issueRef) {
            return new Context(ExecutionState.BLOCKED, reason, reference, detail, issueRef);
        }
    }

    /** Captured null means an absent field in collected evidence, not a missing trace. */
    public record Observation<T>(boolean collected, T value, EvidenceSource source, String reference,
                                 Reason unavailableReason) {
        public Observation {
            Objects.requireNonNull(source, "source");
            requireText(reference, "evidence reference");
            Objects.requireNonNull(unavailableReason, "unavailableReason");
            if (collected ? unavailableReason != Reason.NONE
                    : value != null || (unavailableReason != Reason.TRACE_MISSING
                    && unavailableReason != Reason.PROTOCOL_UNOBSERVABLE)) {
                throw new IllegalArgumentException("Invalid observation availability/reason/value");
            }
        }
        public static <T> Observation<T> captured(T value, EvidenceSource source, String reference) {
            return new Observation<>(true, value, source, reference, Reason.NONE);
        }
        public static <T> Observation<T> unavailable(EvidenceSource source, String reference) {
            return new Observation<>(false, null, source, reference, Reason.TRACE_MISSING);
        }
        public static <T> Observation<T> protocolUnobservable(EvidenceSource source, String reference) {
            return new Observation<>(false, null, source, reference, Reason.PROTOCOL_UNOBSERVABLE);
        }
        public <R> Observation<R> field(String key, java.util.function.Function<? super T, ? extends R> read) {
            requireText(key, "field key");
            Objects.requireNonNull(read, "read");
            return new Observation<>(collected, collected ? read.apply(value) : null,
                    source, reference + "#/" + key.replace("~", "~0").replace("/", "~1"), unavailableReason);
        }
    }

    public record Finding(Rule rule, Outcome outcome, Reason reason, String expected, String actual,
                          EvidenceSource source, String evidenceRef, String contextRef, String issueRef) {
        public Finding {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(actual, "actual");
            Objects.requireNonNull(source, "source");
            requireText(evidenceRef, "evidenceRef");
            requireText(contextRef, "contextRef");
            boolean valid = switch (outcome) {
                case PASS -> reason == Reason.NONE;
                case FAIL -> reason == Reason.MUST_VIOLATION || reason == Reason.INFRA_FAILURE
                        || reason == Reason.MODEL_VARIANCE || reason == Reason.CONDITIONAL_NOT_TRIGGERED
                        || reason == Reason.OTHER_UNTESTABLE;
                case WARN -> reason == Reason.SHOULD_VIOLATION;
                case EXCLUDED -> reason == Reason.OUT_OF_SCOPE;
                case UNSUPPORTED -> reason == Reason.DECLARED_UNSUPPORTED;
                case NOT_OBSERVED -> reason == Reason.PROTOCOL_UNOBSERVABLE || reason == Reason.TRACE_MISSING;
            };
            if (!valid) throw new IllegalArgumentException("Invalid outcome/reason pair: " + outcome + "/" + reason);
            if (reason == Reason.MUST_VIOLATION && rule.obligation() != Obligation.MUST
                    || reason == Reason.SHOULD_VIOLATION && rule.obligation() != Obligation.SHOULD) {
                throw new IllegalArgumentException("Violation reason disagrees with rule obligation");
            }
            if (reason == Reason.OTHER_UNTESTABLE) requireText(issueRef, "issueRef");
        }
        public String ruleId() { return rule.id(); }
        public String reasonCode() { return reason.code(); }

        // Diagnostic findings retain their result without deciding product acceptance.
        public boolean participatesInAcceptance() {
            return rule.purpose() == Purpose.ACCEPTANCE;
        }

        public boolean blocksAcceptance() {
            return rule.purpose() != Purpose.DIAGNOSTIC
                    && (outcome == Outcome.FAIL || outcome == Outcome.NOT_OBSERVED);
        }
    }

    /** Only applicable, executed, observable checks invoke the predicate. Exceptions are not verdicts. */
    public static <T> Finding check(Rule rule, Context context, Observation<T> observation,
                                   String expected, Predicate<? super T> predicate) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(predicate, "predicate");
        if (context.state() != ExecutionState.READY) {
            Outcome outcome = switch (context.state()) {
                case OUT -> Outcome.EXCLUDED;
                case DECLARED_UNSUPPORTED -> Outcome.UNSUPPORTED;
                default -> Outcome.FAIL;
            };
            return finding(rule, context, observation, outcome, context.reason(), expected, context.detail());
        }
        if (!observation.collected()) {
            return finding(rule, context, observation, Outcome.NOT_OBSERVED, observation.unavailableReason(),
                    expected, "evidence unavailable");
        }
        boolean matches = predicate.test(observation.value());
        Outcome outcome = matches ? Outcome.PASS : rule.obligation() == Obligation.SHOULD ? Outcome.WARN : Outcome.FAIL;
        Reason reason = matches ? Reason.NONE : rule.obligation() == Obligation.SHOULD
                ? Reason.SHOULD_VIOLATION : Reason.MUST_VIOLATION;
        return finding(rule, context, observation, outcome, reason, expected, String.valueOf(observation.value()));
    }

    public static <T> Finding equalTo(Rule rule, Context context, Observation<T> observation, T expected) {
        return check(rule, context, observation, String.valueOf(expected), actual -> Objects.equals(expected, actual));
    }

    private static Finding finding(Rule rule, Context context, Observation<?> observation, Outcome outcome,
                                   Reason reason, String expected, String actual) {
        return new Finding(rule, outcome, reason, expected, actual, observation.source(),
                observation.reference(), context.reference(), context.issueRef());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
