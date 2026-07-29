package com.huawei.ascend.sit.conversation;

import com.huawei.ascend.sit.conversation.mid.dto.NextRequest;
import com.huawei.ascend.sit.conversation.mid.dto.StepUI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Concurrent fan-out engine for {@link DriveMode.ParallelStepUi}. Derives child conversation ids from the
 * <b>fan-out round</b> (the last serial step, via {@link RemoteInvocationProbe}), then drives each child's
 * step-ui/next-request loop concurrently on virtual threads, resuming per child through {@link ResumePoster}
 * (which stamps parts[0].metadata.toolCallId = the child's id so the runtime routes the input to that child).
 *
 * <p>The fan-out round is the last entry of the serial steps handed in by {@link Turn#runParallel()}: the
 * parallel-transfer profile runs balance SERIALLY first, and the transfer fan-out ({@code _remote_invocation}
 * projections) appears only in a later round — so the caller drives balance forward and passes the whole
 * serial prefix here; we derive children from its tail (the round that carried ≥2 projections, or the
 * kickoff itself when the fan-out was already present at Step 0).
 *
 * <p>Each child consumes its OWN positional copy of the shared selection list (selIdx starts at 0 per
 * child) — valid when all parallel legs need the same manual steps (the parallel-transfer transfer legs).
 *
 * <p>The three function seams ({@code stepUi}, {@code nextRequest}, {@code resumePoster}) keep this
 * engine fully unit-testable without a real {@link com.huawei.ascend.sit.conversation.mid.MidConversationSupport}
 * or {@link Conversation} (both final). {@code Turn.runParallel()} adapts a real Conversation to them
 * via method references.
 */
final class ParallelStepDriver {

    /** Virtual-thread-per-task: cheap, no pooling, threads terminate after each task. */
    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());

    private ParallelStepDriver() {}

    /** Posts a per-child resume and returns the resulting Step. Mirrors {@link Conversation#postResume}. */
    @FunctionalInterface
    interface ResumePoster {
        Step postResume(String parentCid, String toolCallId, String query,
                        Map<String, String> selectionKv, int index, StepUI stepUi);
    }

    /**
     * Drive all parallel children to completion (or cap).
     *
     * @param parentCid     the parent conversation id (conversationId on the wire; children share it)
     * @param serialSteps   the serial prefix driven by {@link Turn#runParallel()} — kickoff + balance
     *                      rounds + the fan-out round. Children are derived from the LAST step (the
     *                      round that carried ≥2 _remote_invocation projections, or the kickoff when the
     *                      fan-out was already present at Step 0). Must be non-empty.
     * @param mode          ParallelStepUi (sharedSelections)
     * @param maxPerChild   safety cap on resume POSTs per child
     * @param stepUi        cid → current StepUI (mid GET /step-ui)
     * @param nextRequest   (cid, selectionKv) → NextRequest (mid GET /next-request)
     * @param resumePoster  posts a per-child resume (stamps parts[0].metadata.toolCallId routing)
     */
    static ParallelTurnResult drive(String parentCid,
                                    List<Step> serialSteps,
                                    DriveMode.ParallelStepUi mode,
                                    int maxPerChild,
                                    Function<String, StepUI> stepUi,
                                    BiFunction<String, Map<String, String>, NextRequest> nextRequest,
                                    ResumePoster resumePoster) {
        Objects.requireNonNull(serialSteps, "serialSteps");
        if (serialSteps.isEmpty()) {
            throw new IllegalStateException("serialSteps must include the kickoff");
        }
        // The fan-out rides on the last serial round. fanOutChildren returns only the ≥2-member batch
        // (the transfer legs), excluding any completed serial invocation — e.g. the balance — that
        // happens to share the round (a 1-member batch).
        Step fanOutStep = serialSteps.get(serialSteps.size() - 1);
        List<RemoteInvocationProbe.ChildRef> children = RemoteInvocationProbe.fanOutChildren(parentCid, fanOutStep.events());
        if (children.size() < 2) {
            throw new IllegalStateException("parallel mode needs ≥2 child conversations, derived "
                    + children.size() + " after " + serialSteps.size() + " serial round(s) "
                    + "(no ≥2-member fan-out batch after the balance phase?)");
        }
        System.out.println("[parallel-fanout] " + children.size() + " children from fan-out round "
                + fanOutStep.index() + ": " + children.stream()
                        .map(c -> c.toolCallId() + "@child=" + c.childCid()).toList());
        List<CompletableFuture<ParallelTurnResult.ChildResult>> futures = children.stream()
                .map(c -> CompletableFuture.supplyAsync(
                        () -> driveChild(parentCid, c, mode.sharedSelections(), maxPerChild,
                                stepUi, nextRequest, resumePoster),
                        EXECUTOR))
                .toList();
        List<ParallelTurnResult.ChildResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        boolean capped = results.stream().anyMatch(ParallelTurnResult.ChildResult::capped);
        return new ParallelTurnResult(serialSteps, results, capped);
    }

    private static ParallelTurnResult.ChildResult driveChild(String parentCid,
                                                             RemoteInvocationProbe.ChildRef c,
                                                             List<DeclaredSelection> selections,
                                                             int maxPerChild,
                                                             Function<String, StepUI> stepUi,
                                                             BiFunction<String, Map<String, String>, NextRequest> nextRequest,
                                                             ResumePoster resumePoster) {
        List<Step> steps = new ArrayList<>();
        int selIdx = 0;
        int idx = 1;
        while (true) {
            if (steps.size() >= maxPerChild) {
                return new ParallelTurnResult.ChildResult(c.childCid(), c.toolCallId(), steps, true);  // capped
            }
            StepUI s = stepUi.apply(c.childCid());
            if (s.isWorkflowComplete()) {
                return new ParallelTurnResult.ChildResult(c.childCid(), c.toolCallId(), steps, false);
            }
            Map<String, String> kv = Map.of();
            if (s.needsSelection()) {
                kv = consume(selections, selIdx++, s);
            }
            NextRequest nr = nextRequest.apply(c.childCid(), kv);
            if (nr.query() == null) {
                return new ParallelTurnResult.ChildResult(c.childCid(), c.toolCallId(), steps, false);  // workflow end
            }
            steps.add(resumePoster.postResume(parentCid, c.toolCallId(), nr.query(), kv, idx++, s));
        }
    }

    private static Map<String, String> consume(List<DeclaredSelection> selections, int selIdx, StepUI s) {
        if (selIdx >= selections.size()) {
            throw new IllegalStateException("child step " + s.stepId() + " needs a selection but the shared "
                    + "list is exhausted (used " + selIdx + ", declared " + selections.size() + ")");
        }
        DeclaredSelection ds = selections.get(selIdx);
        if (ds.label() != null && !ds.label().equals(s.stepId())) {
            throw new IllegalStateException("selection label drift: declared step_id=" + ds.label()
                    + " but actual=" + s.stepId());
        }
        return ds.kv();
    }
}
