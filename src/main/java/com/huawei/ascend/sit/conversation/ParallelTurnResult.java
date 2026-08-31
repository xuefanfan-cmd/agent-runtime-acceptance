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
 * SERIALLY first; the transfer fan-out (a terminal interrupt with ≥2 pending remote members, plus the
 * round's delegation events) appears only in a later round. So a parallel turn drives a serial phase
 * (kickoff + balance rounds) until a round carries the fan-out, then fans out. {@code serialSteps}
 * holds that whole prefix; {@link #kickOff()} is {@code serialSteps.get(0)} for back-compat with
 * probe/diagnostic code.
 *
 * <p><b>Parallel events:</b> on the FEAT-027 refreshed wire the batch reply is ONE interleaved SSE
 * stream whose events are labelled with {@code agentEvent.source.{agentId,taskId}} — the wire carries
 * no per-leg routing tag, so per-child {@code Step}s carry no events; the interleaved evidence is
 * preserved verbatim in {@code parallelEvents} (arrival order, exactly what the client saw). Demux it
 * by producer label for per-node assertions ({@code RemoteInvocationProbe#streamsByProducer}).
 *
 * <p><b>Untagged events:</b> a reply event with no producer label belongs to the call-tree ROOT (the
 * delegating agent's own output — FEAT-027 §5.2: render to the root, don't drop), so
 * {@link ParallelStepDriver} collects it into {@code untaggedEvents} rather than silently dropping it.
 * The 3-arg constructor defaults both event lists to empty.
 */
public record ParallelTurnResult(
        List<Step> serialSteps,
        List<ChildResult> children,
        boolean capped,
        List<SseEvent> untaggedEvents,
        List<SseEvent> parallelEvents) {

    /**
     * One child conversation's driven steps.
     * @param childCid   the child's mid conversation id — discovered from the mid platform's
     *                   conversation list (the runtime derives it as
     *                   {@code parentCid_<batchId>_<toolCallId>}, no longer derivable from the wire)
     * @param toolCallId the pending-member toolCallId used to route resume input to this child
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
        parallelEvents = parallelEvents == null ? List.of() : List.copyOf(parallelEvents);
    }

    /** Back-compat ctor (3-arg): no event buckets. */
    public ParallelTurnResult(List<Step> serialSteps, List<ChildResult> children, boolean capped) {
        this(serialSteps, children, capped, List.of(), List.of());
    }

    /** Back-compat ctor (4-arg): untagged events only, no parallel-event bucket. */
    public ParallelTurnResult(List<Step> serialSteps, List<ChildResult> children, boolean capped,
                              List<SseEvent> untaggedEvents) {
        this(serialSteps, children, capped, untaggedEvents, List.of());
    }

    /** The kickoff step (serial step 0); null when no serial steps were captured. Back-compat accessor. */
    public Step kickOff() {
        return serialSteps.isEmpty() ? null : serialSteps.get(0);
    }

    /** Merged events across all serial steps + all children + the parallel/untagged buckets, for blob
     * assertions. The parallel bucket holds the interleaved batch replies (per-leg attribution is by
     * producer label — see {@link #parallelEvents()}); the untagged bucket holds root output. */
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
        all.addAll(parallelEvents);
        all.addAll(untaggedEvents);
        return all;
    }

    /** Number of parallel children driven. */
    public int childCount() { return children.size(); }
}
