/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.run_budget_react.fixture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Assertion facade over a {@code List<RunBudgetEvent>} tailed from the SUT log.
 *
 * <p>API inputs use the upper SNAKE_CASE {@link BudgetEventName} enum (contract
 * readability — the enum names mirror agent-core-java {@code LogEventType}
 * constants); internal matching happens against the wire-level lowercase
 * snake_case string in the {@code event_type} JSON field (handled by
 * {@link BudgetEventName#fromWire(String)} inside {@link SutLogTailer}).
 *
 * <p>Each method throws {@link AssertionError} on failure with a diagnostic
 * message that includes the full observed event list (one line each with
 * {@code name + logLineOffset}) so that CI logs give enough signal to
 * triage LLM-jitter vs real contract breach without re-running.
 *
 * <p>The {@link #classify} helper applies the §5.2 iterated-version rules:
 * missing-but-no-extra → INCONCLUSIVE (LLM skipped the scenario); missing-and-
 * extra-mislocated → FAIL (product violation).
 */
public final class RunBudgetEventAsserter {

    private final List<RunBudgetEvent> events;

    public RunBudgetEventAsserter(List<RunBudgetEvent> events) {
        this.events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    public List<RunBudgetEvent> events() {
        return events;
    }

    public List<RunBudgetEvent> of(BudgetEventName name) {
        return events.stream().filter(e -> e.name() == name).collect(Collectors.toList());
    }

    public int countOf(BudgetEventName name) {
        return (int) events.stream().filter(e -> e.name() == name).count();
    }

    public RunBudgetEventAsserter assertPresent(BudgetEventName... names) {
        for (BudgetEventName name : names) {
            if (countOf(name) == 0) {
                throw new AssertionError("Expected event present but absent: " + name
                        + "\n--- Observed events ---\n" + render());
            }
        }
        return this;
    }

    public RunBudgetEventAsserter assertAbsent(BudgetEventName... names) {
        for (BudgetEventName name : names) {
            int count = countOf(name);
            if (count > 0) {
                throw new AssertionError("Expected event absent but found " + count + "x: " + name
                        + "\n--- Observed events ---\n" + render());
            }
        }
        return this;
    }

    public RunBudgetEventAsserter assertCount(BudgetEventName name, int expected) {
        int actual = countOf(name);
        if (actual != expected) {
            throw new AssertionError("Event count mismatch for " + name
                    + ": expected=" + expected + ", actual=" + actual
                    + "\n--- Observed events ---\n" + render());
        }
        return this;
    }

    /**
     * Strict order assertion: events of each listed type must appear in the
     * given sequence by their {@code logLineOffset}. Each type must appear at
     * least once; additional occurrences of the same type between consecutive
     * list members are tolerated (common for CHECKPOINT between
     * GUARANTEE_EFFECTIVE and SAFETY_VALVE_TRIGGERED), but intermediate
     * occurrences of a disallowed type must be constrained via a separate
     * {@link #assertNoneBetween} call.
     */
    public RunBudgetEventAsserter assertSequence(BudgetEventName... names) {
        if (names.length < 2) {
            throw new IllegalArgumentException("sequence needs ≥2 names, got " + names.length);
        }
        int cursor = -1;
        for (BudgetEventName name : names) {
            int found = -1;
            for (int i = cursor + 1; i < events.size(); i++) {
                if (events.get(i).name() == name) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                throw new AssertionError("Sequence broken: " + name
                        + " not found after position " + cursor
                        + " (expected sequence: " + Arrays.toString(names) + ")"
                        + "\n--- Observed events ---\n" + render());
            }
            cursor = found;
        }
        return this;
    }

    /**
     * Between the first occurrence of {@code from} and the first occurrence of
     * {@code to} that follows it (both exclusive), none of {@code forbidden}
     * may appear. Useful for the FEAT-057 safety-valve rule: no CHECKPOINT /
     * STALL_PROMPTED / GENTLE_REMINDER between SAFETY_VALVE_TRIGGERED and
     * TERMINATED.
     */
    public RunBudgetEventAsserter assertNoneBetween(BudgetEventName from,
                                                     BudgetEventName to,
                                                     BudgetEventName... forbidden) {
        int fromIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).name() == from) {
                fromIdx = i;
                break;
            }
        }
        if (fromIdx < 0) {
            throw new AssertionError("assertNoneBetween: anchor 'from'=" + from + " not found"
                    + "\n--- Observed events ---\n" + render());
        }
        int toIdx = -1;
        for (int i = fromIdx + 1; i < events.size(); i++) {
            if (events.get(i).name() == to) {
                toIdx = i;
                break;
            }
        }
        if (toIdx < 0) {
            throw new AssertionError("assertNoneBetween: anchor 'to'=" + to
                    + " not found after 'from'=" + from
                    + "\n--- Observed events ---\n" + render());
        }
        EnumSet<BudgetEventName> forbiddenSet = EnumSet.noneOf(BudgetEventName.class);
        forbiddenSet.addAll(Arrays.asList(forbidden));
        for (int i = fromIdx + 1; i < toIdx; i++) {
            if (forbiddenSet.contains(events.get(i).name())) {
                throw new AssertionError("Forbidden event " + events.get(i).name()
                        + " at line " + events.get(i).logLineOffset()
                        + " between " + from + " (line " + events.get(fromIdx).logLineOffset()
                        + ") and " + to + " (line " + events.get(toIdx).logLineOffset() + ")"
                        + "\n--- Observed events ---\n" + render());
            }
        }
        return this;
    }

    /**
     * Payload predicate assertion. The predicate is applied to the first event
     * of the given type; if the type has zero occurrences, this throws (use
     * {@link #assertPresent} first if the presence check is a separate rule).
     */
    public RunBudgetEventAsserter assertFirstPayload(BudgetEventName name,
                                                      Predicate<RunBudgetEvent> predicate,
                                                      String description) {
        List<RunBudgetEvent> matched = of(name);
        if (matched.isEmpty()) {
            throw new AssertionError("assertFirstPayload: event " + name + " not present"
                    + "\n--- Observed events ---\n" + render());
        }
        RunBudgetEvent first = matched.get(0);
        if (!predicate.test(first)) {
            throw new AssertionError("Payload predicate failed for " + name
                    + " [" + description + "]\n  payload=" + first.payload()
                    + "\n--- Observed events ---\n" + render());
        }
        return this;
    }

    /**
     * All occurrences of {@code name} must satisfy the predicate.
     */
    public RunBudgetEventAsserter assertAllPayload(BudgetEventName name,
                                                    Predicate<RunBudgetEvent> predicate,
                                                    String description) {
        List<RunBudgetEvent> matched = of(name);
        if (matched.isEmpty()) {
            throw new AssertionError("assertAllPayload: event " + name + " not present"
                    + "\n--- Observed events ---\n" + render());
        }
        for (RunBudgetEvent e : matched) {
            if (!predicate.test(e)) {
                throw new AssertionError("Payload predicate failed for " + name
                        + " at line " + e.logLineOffset()
                        + " [" + description + "]\n  payload=" + e.payload()
                        + "\n--- Observed events ---\n" + render());
            }
        }
        return this;
    }

    /**
     * §5.2 iterated-version classification. Call this at the top of a test to
     * decide PASS vs FAIL vs INCONCLUSIVE *before* running any specific
     * payload asserts.
     *
     * <ul>
     *   <li>All expected present and no forbidden extras → PASS
     *   <li>Expected missing AND no forbidden extras → INCONCLUSIVE
     *       (LLM jitter skipped the scenario)
     *   <li>Expected missing AND forbidden extras present → FAIL
     *       (product violation)
     *   <li>Expected present but forbidden extras also present → FAIL
     * </ul>
     */
    public Verdict classify(EnumSet<BudgetEventName> expectedPresent,
                            EnumSet<BudgetEventName> forbiddenAbsent) {
        List<BudgetEventName> missing = new ArrayList<>();
        for (BudgetEventName e : expectedPresent) {
            if (countOf(e) == 0) {
                missing.add(e);
            }
        }
        List<BudgetEventName> intruders = new ArrayList<>();
        for (BudgetEventName f : forbiddenAbsent) {
            if (countOf(f) > 0) {
                intruders.add(f);
            }
        }
        if (missing.isEmpty() && intruders.isEmpty()) {
            return new Verdict(VerdictKind.PASS, "all expected present, no forbidden extras");
        }
        if (!intruders.isEmpty()) {
            return new Verdict(VerdictKind.FAIL,
                    "forbidden events present: " + intruders
                            + (missing.isEmpty() ? "" : "; also missing: " + missing));
        }
        return new Verdict(VerdictKind.INCONCLUSIVE,
                "expected events missing (likely LLM jitter): " + missing);
    }

    public String render() {
        if (events.isEmpty()) {
            return "  (no budget events observed)";
        }
        StringBuilder sb = new StringBuilder();
        for (RunBudgetEvent e : events) {
            sb.append("  L").append(e.logLineOffset()).append(" ")
                    .append(e.name()).append(" payload=").append(e.payload()).append('\n');
        }
        return sb.toString();
    }

    public enum VerdictKind { PASS, FAIL, INCONCLUSIVE }

    public record Verdict(VerdictKind kind, String reason) {}
}
