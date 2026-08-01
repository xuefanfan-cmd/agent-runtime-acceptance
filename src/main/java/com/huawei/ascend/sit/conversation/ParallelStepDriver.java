package com.huawei.ascend.sit.conversation;

import com.huawei.ascend.sit.conversation.mid.dto.NextRequest;
import com.huawei.ascend.sit.conversation.mid.dto.StepUI;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Round-synchronized batch driver for {@link DriveMode.ParallelStepUi}. Derives child conversation ids
 * from the fan-out round (the last serial step, via {@link RemoteInvocationProbe}), then drives every
 * child in lock-step rounds: each round gathers each active child's {@link ResumePart}, sends ONE
 * multi-part batch resume via {@link BatchResumeExchange} (one in-flight request — the server allows
 * only one awaiting-answer request per conversation), and demultiplexes the combined reply per child.
 *
 * <p>The fan-out round is the last entry of the serial steps handed in by {@link Turn#runParallel()}:
 * the parallel-transfer profile runs balance SERIALLY first, and the transfer fan-out
 * ({@code _remote_invocation} projections) appears only in a later round — so the caller drives balance
 * forward and passes the whole serial prefix here; we derive children from its tail (the round that
 * carried ≥2 projections, or the kickoff itself when the fan-out was already present at Step 0).
 *
 * <p>Each child consumes selections <b>by step_id</b> (looked up in {@code mode.selectionByStepId()}),
 * NOT positionally — so parallel legs whose manual-step sequences differ (asymmetric legs) each get the
 * kv for the step they are actually on. See {@link #consume}.
 *
 * <p>The three function seams ({@code stepUi}, {@code nextRequest}, {@code exchange}) keep this engine
 * fully unit-testable without a real {@link com.huawei.ascend.sit.conversation.mid.MidConversationSupport}
 * or {@link Conversation} (both final). {@code Turn.runParallel()} adapts a real Conversation to them
 * via method references.
 */
final class ParallelStepDriver {

    private ParallelStepDriver() {}

    /** One batch resume round: send all parts in one multi-part POST, return reply events per toolCallId. */
    @FunctionalInterface
    interface BatchResumeExchange {
        Map<String, List<SseEvent>> sendBatchResume(String parentCid, List<ResumePart> parts);
    }

    /**
     * Demultiplex a batch reply's bridged events by the per-child {@code toolCallId} the adapter stamps
     * onto each event's {@code data}. Events with no {@code toolCallId} (untagged — should not happen with
     * the confirmed runtime tagging) collect under the {@code null} key so they are never silently dropped.
     */
    static Map<String, List<SseEvent>> groupByToolCallId(List<SseEvent> events) {
        Map<String, List<SseEvent>> out = new LinkedHashMap<>();
        for (SseEvent e : events) {
            Object tcid = (e.data() == null) ? null : e.data().get("toolCallId");
            String key = (tcid instanceof String s && !s.isBlank()) ? s : null;
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        return out;
    }

    /**
     * Drive all parallel children to completion (or cap) in round-synchronized batches.
     *
     * @param parentCid     the parent conversation id (conversationId on the wire; children share it)
     * @param serialSteps   the serial prefix driven by {@link Turn#runParallel()} — kickoff + balance
     *                      rounds + the fan-out round. Children are derived from the LAST step (the
     *                      round that carried ≥2 _remote_invocation projections, or the kickoff when the
     *                      fan-out was already present at Step 0). Must be non-empty.
     * @param mode          ParallelStepUi (selectionByStepId)
     * @param maxPerChild   safety cap on resume rounds per child
     * @param stepUi        cid → current StepUI (mid GET /step-ui)
     * @param nextRequest   (cid, selectionKv) → NextRequest (mid GET /next-request)
     * @param exchange      sends ONE multi-part batch resume for all active children this round and
     *                      returns the demultiplexed reply events keyed by toolCallId
     */
    static ParallelTurnResult drive(String parentCid,
                                    List<Step> serialSteps,
                                    DriveMode.ParallelStepUi mode,
                                    int maxPerChild,
                                    Function<String, StepUI> stepUi,
                                    BiFunction<String, Map<String, String>, NextRequest> nextRequest,
                                    BatchResumeExchange exchange) {
        Objects.requireNonNull(serialSteps, "serialSteps");
        if (serialSteps.isEmpty()) {
            throw new IllegalStateException("serialSteps must include the kickoff");
        }
        Step fanOutStep = serialSteps.get(serialSteps.size() - 1);
        List<RemoteInvocationProbe.ChildRef> children = RemoteInvocationProbe.fanOutChildren(parentCid, fanOutStep.events());
        if (children.size() < 2) {
            throw new IllegalStateException("parallel mode needs ≥2 child conversations, derived "
                    + children.size() + " after " + serialSteps.size() + " serial round(s)");
        }
        System.out.println("[parallel-fanout] " + children.size() + " children from fan-out round "
                + fanOutStep.index() + ": " + children.stream()
                        .map(c -> c.toolCallId() + "@child=" + c.childCid()).toList());

        Map<RemoteInvocationProbe.ChildRef, List<Step>> stepsByChild = new LinkedHashMap<>();
        Set<RemoteInvocationProbe.ChildRef> done = new LinkedHashSet<>();
        // Reply events the runtime did NOT tag with a toolCallId — can't be attributed to a child, so they
        // are collected here and surfaced on the result (never silently dropped). Empty under confirmed
        // runtime tagging; exists so a tagging change degrades visibly. See groupByToolCallId's null key.
        List<SseEvent> untagged = new ArrayList<>();
        for (RemoteInvocationProbe.ChildRef c : children) {
            stepsByChild.put(c, new ArrayList<>());
        }

        int round = 0;
        while (round < maxPerChild && stepsByChild.keySet().stream().anyMatch(c -> !done.contains(c))) {
            // Per driven child this round: remember the stepUi/kv so the Step we build after the batch
            // reply carries them (mirrors the serial path). request/elapsed stay null — the single batch
            // body is one POST shared by all children and is captured in the wire log, not per-child Step.
            record Pending(RemoteInvocationProbe.ChildRef child, StepUI stepUi, Map<String, String> kv) {}
            List<ResumePart> parts = new ArrayList<>();
            List<Pending> driven = new ArrayList<>();
            for (RemoteInvocationProbe.ChildRef c : children) {
                if (done.contains(c)) continue;            // already complete (prior round)
                StepUI s = stepUi.apply(c.childCid());
                // Per-round per-child trace: surfaces each leg's exact step_id sequence on a hardware run
                // (the wire log can't — step_id lives in the mid step-ui, which isn't A2A-logged). This is
                // how the asymmetric-leg shape gets pinned down.
                System.out.println("[parallel-child] round=" + round + " child=" + c.toolCallId()
                        + " step=" + (s.stepId() == null || s.stepId().isBlank() ? "(auto)" : s.stepId())
                        + " needsSel=" + s.needsSelection() + " complete=" + s.isWorkflowComplete());
                if (s.isWorkflowComplete()) { done.add(c); continue; }   // done
                Map<String, String> kv = Map.of();
                if (s.needsSelection()) {
                    kv = consume(mode.selectionByStepId(), s);           // step_id-keyed, not positional
                }
                NextRequest nr = nextRequest.apply(c.childCid(), kv);
                if (nr.query() == null) { done.add(c); continue; }       // workflow end
                parts.add(new ResumePart(c.toolCallId(), nr.query()));
                driven.add(new Pending(c, s, kv));
            }
            if (parts.isEmpty()) break;                          // every child complete

            Map<String, List<SseEvent>> reply = exchange.sendBatchResume(parentCid, parts);
            for (Pending p : driven) {
                List<SseEvent> ev = reply.get(p.child().toolCallId());
                stepsByChild.get(p.child()).add(new Step(round + 1, p.stepUi(), null, p.kv(), null,
                        ev == null ? List.of() : ev, null));
            }
            // Surface untagged reply events (the null-keyed bucket) on the result — never silently dropped.
            // Iterate entrySet rather than get(null): some Map impls (e.g. Map.of) reject null-key lookups.
            for (Map.Entry<String, List<SseEvent>> e : reply.entrySet()) {
                if (e.getKey() == null) {
                    untagged.addAll(e.getValue());
                }
            }
            round++;
        }

        List<ParallelTurnResult.ChildResult> results = new ArrayList<>();
        boolean anyCapped = false;
        for (RemoteInvocationProbe.ChildRef c : children) {
            List<Step> steps = stepsByChild.get(c);
            boolean isCapped = steps.size() >= maxPerChild;
            anyCapped |= isCapped;
            results.add(new ParallelTurnResult.ChildResult(c.childCid(), c.toolCallId(), steps, isCapped));
        }
        return new ParallelTurnResult(serialSteps, results, anyCapped, untagged);
    }

    /**
     * Look up the selection kv for the child's <b>current step_id</b> in the declared map — NOT a positional
     * index. Robust to asymmetric legs (each gets the kv for whatever step it's on), skipped steps (a leg
     * without {@code on_paycard_input} simply never looks it up), and re-presented steps (same step_id →
     * same kv, bounded by {@code maxPerChild}). Throws a clear, step_id-naming error when a leg reaches a
     * selection-needing step with no declared kv.
     */
    private static Map<String, String> consume(Map<String, Map<String, String>> selectionByStepId, StepUI s) {
        String stepId = s.stepId();
        Map<String, String> kv = (stepId == null) ? null : selectionByStepId.get(stepId);
        if (kv == null) {
            throw new IllegalStateException("child step " + stepId + " needs a selection but none is declared "
                    + "for that step_id (declared step_ids: " + selectionByStepId.keySet() + ")");
        }
        return kv;
    }
}
