package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.conversation.Conversation;
import com.huawei.ascend.sit.conversation.ConversationIdentity;
import com.huawei.ascend.sit.conversation.ConversationInteractionAdapter;
import com.huawei.ascend.sit.conversation.DriveMode;
import com.huawei.ascend.sit.conversation.ParallelTurnResult;
import com.huawei.ascend.sit.conversation.SseEvent;
import com.huawei.ascend.sit.conversation.Step;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.huawei.ascend.sit.cases.integration.workflow_call.BalanceTransferFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 real-hardware capture probe for the {@code parallel-transfer} profile. <b>Disabled in-repo</b>:
 * enable manually with {@code -Dtest.env=openjiuwen} + an LLM key + Docker to capture the kickoff wire
 * shape ({@code _remote_invocation} projections, child step sequence) to {@code target/sit-logs/wire/}
 * for inspection.
 *
 * <p>Mirrors {@link PlanAgentParallelTransferStreamingTest}'s stack/conversation construction but is a
 * CAPTURE, not an acceptance test: it prints the {@code _remote_invocation.{batchId,toolCallId}} pairs
 * across every serial step (kickoff + balance rounds + the fan-out round), the derived childCids, each
 * child's step count + capped flag + step labels, and a {@code TRANSFER_DONE} marker scan — minimal
 * assertions (the probe's job is to DISCOVER the exact wire shape so Task 9's {@code SHARED_SELECTIONS}
 * can be revised against real hardware output).
 *
 * <p><b>Wire-log</b>: the openjiuwen profile sets {@code sut.wire-log.enabled: true}, so
 * {@link ConversationInteractionAdapter} auto-resolves a {@code FileWireLogger} on the first send (via
 * {@code WireLoggerResolver.resolved()}) and dumps raw frames to
 * {@code target/sit-logs/wire/run-<yyyyMMdd-HHmmss>/}. The {@code withWireLogger} injection seam is
 * package-private to {@code com.huawei.ascend.sit.conversation} (test-only), so this probe relies on the
 * config-driven auto-resolution path — the same path production tests use on openjiuwen.
 *
 * @see PlanAgentParallelTransferStreamingTest the acceptance twin (asserts ≥2 children + completion)
 */
@Tag("integration")
@Disabled("当前不支持客户端并发续轮")
@Feature("FEAT-004: 任务驱动远程智能体调用")
@Stories({ @Story("wf.parallel-transfer: 并行转账批量派发与并发驱动 — Phase 0 真机抓包") })
class ParallelTransferCaptureTest extends BaseManagedStackTest {

    /**
     * 每腿转账的人工步（Phase 0 钉死确切步序；先按串行同形假设：选卡 + 确认）。
     * 与 {@link PlanAgentParallelTransferStreamingTest#SHARED_SELECTIONS} 同形。
     */
    private static final List<Map<String, String>> SHARED_SELECTIONS = List.of(
            Map.of("accIndex", "0"),    // on_paycard_input
            Map.of("_text", "确定"));   // on_confirm_remit

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 第一行 gate：非 openjiuwen 在基类 .start() 之前 abort，不拉容器。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — re-run with -Dtest.env=openjiuwen (需 LLM_API_KEY 等)");
        return SutStack.builder(config)
                .agent("edpa-adapter")
                .agent(PLAN_AGENT, a -> a.profile("parallel-transfer").downstream("edpa-adapter"));
    }

    @Test
    @DisplayName("Phase 0 抓包：parallel-transfer kickoff wire shape（_remote_invocation + child steps）")
    void captureParallelTransferKickoffWire() {
        try (Conversation conv = Conversation
                .at(stack.baseUrl(PLAN_AGENT), stack.serviceUrl("envexplorer"))
                .identity(ConversationIdentity.loadDefault())
                .transport(new ConversationInteractionAdapter(
                        MessageProtocol.A2A_STREAM, client(PLAN_AGENT), ROUND_TIMEOUT_MS))
                .timeout(Duration.ofSeconds(600))
                .maxInteractions(20)   // tighter cap for a probe — 2 children × a few rounds each, no hang
                .open()) {

            ParallelTurnResult r;
            try {
                r = conv.turn(SENTENCE)
                        .intent("")
                        .driveMode(DriveMode.parallelStepUi(SHARED_SELECTIONS))
                        .runParallel();
            } catch (IllegalStateException e) {
                // runParallel() throws when RemoteInvocationProbe derives <2 children — the probe's job is
                // to DISCOVER whether fan-out produces 2, so surface this as a Phase 0 finding, not just a
                // bare failure. Rethrow so the test still fails (the probe didn't capture what it wanted),
                // but the stdout line makes the finding greppable.
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("≥2") || msg.contains("2 child") || msg.contains("child conversations")) {
                    System.out.println("[phase-0-finding] parallel-transfer did NOT fan out "
                            + "(ParallelStepDriver derived <2 children): " + msg);
                }
                throw e;
            }

            // ---- _remote_invocation projections across ALL serial steps (batchId/toolCallId pairs) ----
            // The parallel-transfer profile runs balance SERIALLY first; the transfer fan-out
            // (_remote_invocation projections) appears in a LATER serial round, not necessarily the kickoff.
            // runParallel() drives balance forward until a round carries ≥2 projections, then fans out —
            // so scan every serial step and tag which round each projection came from.
            List<Map<String, Object>> projections = new ArrayList<>();
            int serialRound = 0;
            for (Step serialStep : r.serialSteps()) {
                for (SseEvent e : serialStep.events()) {
                    Map<String, Object> data = e.data();
                    if (data == null) {
                        continue;
                    }
                    // The adapter surfaces a single projection as a Map and a multi-member fan-out batch
                    // as a List<Map>; normalise both so every child's (batchId, toolCallId) prints.
                    List<Map<?, ?>> riMaps = new ArrayList<>();
                    Object ri = data.get("_remote_invocation");
                    if (ri instanceof Map<?, ?> m) {
                        riMaps.add(m);
                    } else if (ri instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> im) {
                                riMaps.add(im);
                            }
                        }
                    }
                    for (Map<?, ?> rm : riMaps) {
                        Map<String, Object> proj = new LinkedHashMap<>();
                        proj.put("serialRound", serialRound);
                        proj.put("batchId", rm.get("batchId"));
                        proj.put("toolCallId", rm.get("toolCallId"));
                        proj.put("sseEvent", e.event());
                        projections.add(proj);
                    }
                }
                serialRound++;
            }
            System.out.println("[phase-0][_remote_invocation projections across " + r.serialSteps().size()
                    + " serial step(s)] count=" + projections.size() + " -> " + projections);

            // ---- Derived childCids + per-child step counts + capped flags ----
            List<String> childSummary = r.children().stream()
                    .map(c -> "{childCid=" + c.childCid() + ", toolCallId=" + c.toolCallId()
                            + ", steps=" + c.steps().size() + ", capped=" + c.capped() + "}")
                    .toList();
            System.out.println("[phase-0][children=" + r.childCount() + "] " + childSummary);

            // ---- Per-child step labels (to discover the exact manual-step sequence) ----
            // The stepLabel is the declared step_id (null for auto/Step0); printing the per-leg sequence
            // is the whole point of Phase 0 — it pins down what SHARED_SELECTIONS should be.
            for (ParallelTurnResult.ChildResult c : r.children()) {
                List<String> labels = new ArrayList<>();
                for (Step s : c.steps()) {
                    labels.add(s.stepLabel() != null ? s.stepLabel() : "(auto)");
                }
                System.out.println("[phase-0][child " + c.toolCallId() + " step labels] " + labels);
            }

            // ---- TRANSFER_DONE marker scan over the merged event blob ----
            String blob = concat(r.allEvents());
            List<String> hit = TRANSFER_DONE.stream().filter(blob::contains).toList();
            System.out.println("[phase-0][TRANSFER_DONE markers hit] " + hit);
            System.out.println("[phase-0][merged blob length] " + blob.length() + " chars");
            System.out.println("[phase-0][overall capped] " + r.capped());

            // ---- Soft assert ONLY: ≥1 child driven. The driver guarantees ≥2 (it throws <2), so ≥1 is a
            // trivially-true sanity check — the probe's real output is the stdout capture above + the
            // wire-log files under target/sit-logs/wire/. No hard assertions on step sequence or completion
            // markers (those are for Task 9 to assert once Phase 0 pins the shape). ----
            assertThat(r.childCount()).as("≥1 parallel child driven (soft sanity check)").isGreaterThanOrEqualTo(1);
        }
    }
}
