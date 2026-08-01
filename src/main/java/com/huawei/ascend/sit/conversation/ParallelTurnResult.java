package com.huawei.ascend.sit.conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a {@link DriveMode.ParallelStepUi} turn: the serial steps (kickoff + balance rounds + the
 * fan-out round) plus the per-child driven steps. Standalone record (parallel is one turn); does NOT
 * extend {@link TurnResult} — the serial-plus-per-child-group shape differs from TurnResult's flat step
 * list. {@code serialSteps} may be empty when nothing was captured. {@link #allEvents()} merges every
 * serial step + every child's steps + the {@code untaggedEvents} bucket for blob assertions.
 *
 * <p><b>Serial-then-parallel model:</b> the {@code parallel-transfer} profile runs the balance query
 * SERIALLY first; the transfer fan-out ({@code _remote_invocation} projections) appears only in a later
 * round. So a parallel turn drives a serial phase (kickoff + balance rounds) until a round carries the
 * fan-out, then fans out. {@code serialSteps} holds that whole prefix; {@link #kickOff()} is
 * {@code serialSteps.get(0)} for back-compat with probe/diagnostic code.
 *
 * <p><b>Untagged events:</b> a batch-reply event whose parts carry no {@code toolCallId} cannot be
 * attributed to a child, so {@link ParallelStepDriver} collects it into {@code untaggedEvents} rather
 * than silently dropping it. With the confirmed runtime tagging this bucket stays empty; it exists so a
 * future runtime change degrades visibly (the events surface in {@link #allEvents()} and the wire log)
 * instead of disappearing. The 3-arg constructor defaults it to empty.
 */
public record ParallelTurnResult(
        List<Step> serialSteps,
        List<ChildResult> children,
        boolean capped,
        List<SseEvent> untaggedEvents) {

    /**
     * One child conversation's driven steps.
     * @param childCid   the child conversation id, derived as {@code parentCid + ":" + batchId + ":" + toolCallId}
     * @param toolCallId the remote-invocation toolCallId used to route resume input to this child
     * @param steps      the driven steps for this child
     * @param capped     true if this child hit the per-child interaction cap (suspected fault/deadlock)
     */
    public record ChildResult(String childCid, String toolCallId, List<Step> steps, boolean capped) {
        public ChildResult {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public ParallelTurnResult {
        serialSteps = serialSteps == null ? List.of() : List.copyOf(serialSteps);
        children = children == null ? List.of() : List.copyOf(children);
        untaggedEvents = untaggedEvents == null ? List.of() : List.copyOf(untaggedEvents);
    }

    /** Back-compat canonical ctor (3-arg): no untagged events. */
    public ParallelTurnResult(List<Step> serialSteps, List<ChildResult> children, boolean capped) {
        this(serialSteps, children, capped, List.of());
    }

    /** The kickoff step (serial step 0); null when no serial steps were captured. Back-compat accessor. */
    public Step kickOff() {
        return serialSteps.isEmpty() ? null : serialSteps.get(0);
    }

    /** Merged events across all serial steps + all children + the untagged bucket, for blob assertions. */
    public List<SseEvent> allEvents() {
        List<SseEvent> all = new ArrayList<>();
        for (Step s : serialSteps) {
            all.addAll(s.events());
        }
        for (ChildResult c : children) {
            for (Step s : c.steps()) {
                all.addAll(s.events());
            }
        }
        all.addAll(untaggedEvents);
        return all;
    }

    /** Number of parallel children driven. */
    public int childCount() { return children.size(); }
}
