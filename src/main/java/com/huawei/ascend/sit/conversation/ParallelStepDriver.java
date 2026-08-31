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
import java.util.function.Supplier;

/**
 * Round-synchronized batch driver for {@link DriveMode.ParallelStepUi}, on the FEAT-027 refreshed wire.
 * The fan-out round (the last serial step) is recognised by its <b>pending-member interrupt</b> — the
 * terminal {@code statusUpdate} carrying {@code _interrupt.items} with ≥2 {@code toolCallId}s
 * ("Multiple remote agents require input"); the call tree itself is read from the round's
 * <b>delegation events</b> ({@code agentEvent.type=delegation}, distinct {@code target.taskId}s) via
 * {@link RemoteInvocationProbe}. The driver then drives every child in lock-step rounds: each round
 * gathers each active child's {@link ResumePart}, sends ONE multi-part batch resume via
 * {@link BatchResumeExchange} (one in-flight request — the server allows only one awaiting-answer
 * request per conversation), with each part routed by {@code metadata.toolCallId}. The combined reply
 * arrives as ONE interleaved SSE stream whose events are labelled with
 * {@code agentEvent.source.{agentId,taskId}} — the driver demultiplexes it for diagnostics
 * ({@link RemoteInvocationProbe#streamsByProducer}) and records it verbatim as {@code parallelEvents};
 * per FEAT-027 the interleaving order carries no meaning and attribution is by producer label only.
 *
 * <p><b>Child conversation ids are discovered, not derived.</b> The refreshed wire no longer carries
 * {@code batchId}/{@code toolCallId} projections, so the runtime's child mid cid
 * ({@code parentCid_<batchId>_<toolCallId>}) cannot be recomputed. Instead the driver lists the mid
 * platform's conversations ({@code midConversationIds}) and pairs each pending {@code toolCallId} with
 * the conversation whose id ends in {@code "_" + toolCallId} (exact suffix match — no positional
 * assumption). The serial balance leg runs under the bare parent cid (single-member batches keep the
 * parent conversation id), so the {@code parentCid + "_"} prefix excludes it from the pairing set.
 *
 * <p>Each child consumes selections <b>by step_id</b> (looked up in {@code mode.selectionByStepId()}),
 * NOT positionally — so parallel legs whose manual-step sequences differ (asymmetric legs) each get the
 * kv for the step they are actually on. See {@link #consume}.
 *
 * <p>The four function seams ({@code stepUi}, {@code nextRequest}, {@code exchange},
 * {@code midConversationIds}) keep this engine fully unit-testable without a real
 * {@link com.huawei.ascend.sit.conversation.mid.MidConversationSupport} or {@link Conversation} (both
 * final). {@code Turn.runParallel()} adapts a real Conversation to them via method references.
 */
final class ParallelStepDriver {

    private ParallelStepDriver() {}

    /** One batch resume round: send all parts in one multi-part POST, return the interleaved reply stream. */
    @FunctionalInterface
    interface BatchResumeExchange {
        List<SseEvent> sendBatchResume(String parentCid, List<ResumePart> parts);
    }

    /** One drivable parallel child: the resume routing key + the discovered mid conversation id. */
    record ChildKey(String toolCallId, String childCid) {}

    /**
     * Drive all parallel children to completion (or cap) in round-synchronized batches.
     *
     * @param parentCid          the parent conversation id (conversationId on the wire; children share it)
     * @param serialSteps        the serial prefix driven by {@link Turn#runParallel()} — kickoff + balance
     *                           rounds + the fan-out round. The fan-out is read from the LAST step (the
     *                           round whose terminal interrupt carries ≥2 pending toolCallIds). Must be
     *                           non-empty.
     * @param mode               ParallelStepUi (selectionByStepId)
     * @param maxPerChild        safety cap on resume rounds per child
     * @param stepUi             cid → current StepUI (mid GET /step-ui)
     * @param nextRequest        (cid, selectionKv) → NextRequest (mid GET /next-request)
     * @param exchange           sends ONE multi-part batch resume for all active children this round and
     *                           returns the interleaved reply (events labelled by agentEvent.source)
     * @param midConversationIds lists the mid platform's conversation ids (child-cid discovery)
     */
    static ParallelTurnResult drive(String parentCid,
                                    List<Step> serialSteps,
                                    DriveMode.ParallelStepUi mode,
                                    int maxPerChild,
                                    Function<String, StepUI> stepUi,
                                    BiFunction<String, Map<String, String>, NextRequest> nextRequest,
                                    BatchResumeExchange exchange,
                                    Supplier<List<String>> midConversationIds) {
        Objects.requireNonNull(serialSteps, "serialSteps");
        if (serialSteps.isEmpty()) {
            throw new IllegalStateException("serialSteps must include the kickoff");
        }
        Step fanOutStep = serialSteps.get(serialSteps.size() - 1);
        List<String> toolCallIds = RemoteInvocationProbe.fanOutToolCallIds(fanOutStep.events());
        if (toolCallIds.size() < 2) {
            throw new IllegalStateException("parallel mode needs a fan-out round whose interrupt carries "
                    + "≥2 pending remote members, got " + toolCallIds.size()
                    + " after " + serialSteps.size() + " serial round(s)");
        }
        List<RemoteInvocationProbe.Delegation> delegations =
                RemoteInvocationProbe.delegations(fanOutStep.events());
        List<ChildKey> children = pairChildCids(parentCid, toolCallIds, midConversationIds.get());
        System.out.println("[parallel-fanout] " + children.size() + " children from fan-out round "
                + fanOutStep.index() + ": " + children.stream()
                        .map(c -> c.toolCallId() + "@child=" + c.childCid()).toList());
        System.out.println("[parallel-fanout] delegation tree: " + delegations.stream()
                .map(d -> d.source().agentId() + "/" + shortId(d.source().taskId()) + " -> "
                        + d.target().agentId() + "/" + shortId(d.target().taskId()))
                .toList());

        Map<ChildKey, List<Step>> stepsByChild = new LinkedHashMap<>();
        Set<ChildKey> done = new LinkedHashSet<>();
        // Reply events with NO producer label — root-agent output per FEAT-027 §5.2 (attributed to the
        // call-tree root, not an error). Collected here and surfaced on the result, never dropped.
        List<SseEvent> untagged = new ArrayList<>();
        // The parallel phase's interleaved reply stream, verbatim arrival order — the FEAT-027 client
        // view. Per-leg attribution is by producer label only (the wire carries no per-leg toolCallId),
        // so per-child Steps carry no events; the interleaved evidence lives here.
        List<SseEvent> parallelEvents = new ArrayList<>();
        for (ChildKey c : children) {
            stepsByChild.put(c, new ArrayList<>());
        }

        int round = 0;
        while (round < maxPerChild && stepsByChild.keySet().stream().anyMatch(c -> !done.contains(c))) {
            // Per driven child this round: remember the stepUi/kv so the Step we build after the batch
            // reply carries them (mirrors the serial path). request/elapsed stay null — the single batch
            // body is one POST shared by all children and is captured in the wire log, not per-child Step.
            record Pending(ChildKey child, StepUI stepUi, Map<String, String> kv) {}
            List<ResumePart> parts = new ArrayList<>();
            List<Pending> driven = new ArrayList<>();
            for (ChildKey c : children) {
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

            List<SseEvent> reply = exchange.sendBatchResume(parentCid, parts);
            parallelEvents.addAll(reply);
            // Demux by producer label for the round summary + the unlabelled bucket. The null-keyed
            // bucket (no agentEvent.source) is root output — iterate entrySet rather than get(null):
            // some Map impls (e.g. Map.of) reject null-key lookups.
            Map<String, List<SseEvent>> bySource = RemoteInvocationProbe.streamsByProducer(reply);
            for (Map.Entry<String, List<SseEvent>> e : bySource.entrySet()) {
                if (e.getKey() == null) {
                    untagged.addAll(e.getValue());
                }
            }
            System.out.println("[parallel-reply] round=" + round + " events=" + reply.size()
                    + " sources=" + bySource.entrySet().stream()
                            .map(e -> (e.getKey() == null ? "(root)" : shortId(e.getKey()))
                                    + "x" + e.getValue().size())
                            .toList());
            for (Pending p : driven) {
                stepsByChild.get(p.child()).add(new Step(round + 1, p.stepUi(), null, p.kv(), null,
                        List.of(), null));
            }
            round++;
        }

        List<ParallelTurnResult.ChildResult> results = new ArrayList<>();
        boolean anyCapped = false;
        for (ChildKey c : children) {
            List<Step> steps = stepsByChild.get(c);
            boolean isCapped = steps.size() >= maxPerChild;
            anyCapped |= isCapped;
            results.add(new ParallelTurnResult.ChildResult(c.childCid(), c.toolCallId(), steps, isCapped));
        }
        return new ParallelTurnResult(serialSteps, results, anyCapped, untagged, parallelEvents);
    }

    /**
     * Pair each pending {@code toolCallId} with its mid conversation id: the runtime derives a child cid
     * as {@code parentCid_<batchId>_<toolCallId>}, so the candidate set is the mid conversations sharing
     * the {@code parentCid + "_"} prefix, and each toolCallId matches the candidate ending in
     * {@code "_" + toolCallId} — an exact suffix match, robust to arbitrary list order and to the
     * balance leg (whose single-member cid is the bare parentCid, excluded by the prefix). Throws a
     * naming-both-sides error when a pending member has no discovered conversation — a fan-out the
     * driver cannot address must fail visibly, not silently skip a leg.
     */
    private static List<ChildKey> pairChildCids(String parentCid, List<String> toolCallIds,
                                                List<String> discovered) {
        String prefix = parentCid + "_";
        List<String> candidates = (discovered == null ? List.<String>of() : discovered).stream()
                .filter(cid -> cid != null && cid.startsWith(prefix))
                .toList();
        List<ChildKey> out = new ArrayList<>();
        for (String toolCallId : toolCallIds) {
            String childCid = candidates.stream()
                    .filter(cid -> cid.endsWith("_" + toolCallId))
                    .findFirst()
                    .orElse(null);
            if (childCid == null) {
                throw new IllegalStateException("pending remote member " + toolCallId
                        + " has no mid conversation (discovered under '" + prefix + "*': " + candidates + ")");
            }
            out.add(new ChildKey(toolCallId, childCid));
        }
        return out;
    }

    /** Compact a uuid-style id for trace lines: first 8 chars — enough to eyeball-match against logs. */
    private static String shortId(String id) {
        if (id == null) {
            return "(null)";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
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
