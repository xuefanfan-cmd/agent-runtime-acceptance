package com.huawei.ascend.sit.cases.integration.travel_assistant;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-07 / A-08 — 差旅规划（流式模式 {@code message/stream}）业务问答 (特性 3-1).
 *
 * <p>The first cases that exercise the <em>full travel chain</em> (mainplan → trip → hotel) over
 * the <b>streaming</b> path and assert a valid 差旅规划 (travel-plan) result. mainplan is the entry
 * ReAct agent: it collects the trip requirements, then dispatches to trip via the remote-a2a
 * {@code dispatch_travel_plan} tool, and trip in turn may dispatch to hotel. A "valid answer"
 * therefore needs all three agents wired <em>and</em> a real LLM behind each. mainplan also
 * registers a {@code request_user_input} rail, so an incomplete request pauses in
 * {@code INPUT_REQUIRED} rather than completing — that is what A-08 drives.
 *
 * <ul>
 *   <li><b>A-07 — one-shot complete input</b>: a single fully-specified request must stream the
 *       state sequence {@code SUBMITTED → WORKING → COMPLETED} with a non-empty, substantive
 *       itinerary.</li>
 *   <li><b>A-08 — multi-turn supplementary input</b>: an incomplete first turn must stream
 *       {@code SUBMITTED → WORKING → INPUT_REQUIRED} (the agent asks for the missing field) with a
 *       valid reply, then a follow-up turn — continuing the same {@code contextId} — must reach
 *       {@code COMPLETED} with a valid itinerary.</li>
 * </ul>
 *
 * <p><b>Driven through {@link InteractionFlow} over the streaming path ({@code message/stream}).</b>
 * The stack uses the framework default ({@code streaming = true}), so the A2A client opens a
 * message/stream per send; {@code InteractionFlow} awaits the expected terminal state each round and
 * judges it inline — including the <em>observed task-state sequence</em>, asserted via
 * {@link #distinctStatesInOrder(List)} on each round's event snapshot. <b>Matching is two-tier:</b> the
 * first round of a conversation is matched strictly ({@code containsExactly} — fresh
 * {@code SUBMITTED → WORKING → terminal}); <em>continuation</em> rounds are matched weakly
 * ({@code containsSubsequence(WORKING, terminal)}) so a runtime that re-emits {@code SUBMITTED} when
 * resuming an in-progress task still passes. {@link InteractionFlow} also carries each round's
 * {@code contextId} into the next, so A-08's two {@code .send(...)} calls are one continued
 * conversation (turn 2 supplies the info mainplan asked for in turn 1).
 *
 * <p><b>Credentials &amp; proxy.</b> Export the unified {@code LLM_*} env once — all three agents read
 * {@code ${LLM_*}} natively, and {@code ProcessLauncher} passes the test JVM's env to each child.
 * HTTP proxy / no-proxy go as JVM {@code -D} system properties via {@code sut.java.system-properties}
 * in {@code application-local.yml} (rendered before {@code -jar} on every agent). Then run
 * {@code ./mvnw -Dtest=StreamingTravelPlanningTest test}.
 */
@Tag("integration")
// Run this class's @Test methods concurrently against the single shared managed
// stack (mainplan → trip → hotel), brought up once at @BeforeAll. The stack and
// config fields are set in @BeforeAll and only read here, so they are safe to
// share across the concurrent methods. Requires junit.jupiter.execution.parallel
// .enabled=true (see src/test/resources/junit-platform.properties); the suite
// default stays same_thread, so only opting-in classes run in parallel.
class StreamingTravelPlanningTest extends BaseManagedStackTest {

    /** Single fully-specified turn expected to complete the whole trip with no follow-ups. */
    private static final String COMPLETE_REQUEST = "明天从上海到北京出差3天，住宿2晚。差标：每晚不超过 800 元、最低 4 星、协议品牌 全季/亚朵/希尔顿欢朋。偏好：国贸附近，需要会议室。";

    /** A-08 turn 1 — incomplete: has destination + duration, lacks departure date / origin. */
    private static final String INCOMPLETE_TURN_1 = "到北京出差3天";

    /** A-08 turn 2 — supplies the missing departure date + origin, completing the request. */
    private static final String INCOMPLETE_TURN_2 = "明天从上海出发。差标：每晚不超过 800 元、最低 4 星、协议品牌 全季/亚朵/希尔顿欢朋。偏好：国贸附近，需要会议室。";

    /** C-03 turn 1 — states intent + destination only; withholds dates and origin. */
    private static final String COLLECTION_TURN_1 = "我要去北京出差。";

    /** C-03 turn 2 — adds duration + a departure date, but still withholds the origin. */
    private static final String COLLECTION_TURN_2 = "出差3天，下周二出发。";

    /** C-03 turn 3 — supplies the origin + the rest, completing the request. */
    private static final String COLLECTION_TURN_3 = "从上海出发。差标：每晚不超过 800 元、最低 4 星、协议品牌 全季/亚朵/希尔顿欢朋。偏好：国贸附近，需要会议室。";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // Full chain, declared leaf-first. SutStack injects each downstream's resolved base URL
        // into its upstream's agent-runtime.remote-agents[0].url. The default streaming=true ⇒ the
        // A2A client uses message/stream (流式). All three agents read the unified LLM_* natively —
        // their bundled yaml uses ${LLM_*} placeholders, and ProcessLauncher passes the test JVM's
        // env to each child — so no per-agent LLM wiring is needed (export LLM_* once; proxy via
        // sut.java.system-properties in application-local.yml).
        return SutStack.builder(config)
                .agent("hotel")
                .agent("trip", a -> a.downstream("hotel"))
                .agent("mainplan", a -> a.downstream("trip"));
    }

    // ---- A-07: 流式模式 — 一次性完整输入问答（SUBMITTED → WORKING → COMPLETED）----

    /**
     * A-07.A — a fully-specified request completes in a single streaming turn with a valid 差旅规划.
     *
     * <p>Expected: the streamed task-state sequence is {@code SUBMITTED → WORKING → COMPLETED};
     * answer text non-blank and substantive (a complete itinerary from mainplan→trip→hotel,
     * not an error/empty refusal).
     */
    @Test
    @DisplayName("A-07.A: one-shot complete request streams SUBMITTED→WORKING→COMPLETED with a valid plan")
    void oneShotRequestStreamsSubmittedWorkingCompleted() {
        InteractionFlow.of(client("mainplan"))
                .withMetadata(Map.of("userId", "manual-user", "agentId", "main-plan-agent",
                        "sessionId", "manual-session-001"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send(COMPLETE_REQUEST)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> assertThat(distinctStatesInOrder(ctx.events()))
                            .as("A-07 streamed state sequence: SUBMITTED → WORKING → COMPLETED")
                            .containsExactly(
                                    TaskState.TASK_STATE_SUBMITTED,
                                    TaskState.TASK_STATE_WORKING,
                                    TaskState.TASK_STATE_COMPLETED))
                    .assertTask(task -> {
                        String answer = textOf(task);
                        assertThat(answer).as("completed travel-plan text").isNotBlank();
                        // A valid itinerary is substantive, not a bare error message.
                        assertThat(answer.length())
                                .as("answer is substantive (not a bare error/refusal)")
                                .isGreaterThan(8);
                    })
                .execute();
    }

    // ---- A-08: 流式模式 — 多次请求补充输入场景 ----

    /**
     * A-08.A — an incomplete turn streams {@code SUBMITTED → WORKING → INPUT_REQUIRED} (with a valid
     * clarifying reply), then a follow-up turn continuing the same {@code contextId} streams
     * {@code WORKING → COMPLETED} (with a valid final itinerary).
     *
     * <p>Each round must carry a valid answer: turn 1's answer is the agent's clarifying question;
     * turn 2's answer is the completed itinerary. {@link InteractionFlow} threads turn 1's
     * {@code contextId} into turn 2 automatically, so the follow-up is the same conversation.
     */
    @Test
    @DisplayName("A-08.A: incomplete turn streams SUBMITTED→WORKING→INPUT_REQUIRED, follow-up streams WORKING→COMPLETED")
    void incompleteThenFollowUpFollowsExpectedStateSequences() {
        InteractionFlow.of(client("mainplan"))
                .withMetadata(Map.of("userId", "manual-user", "agentId", "main-plan-agent",
                        "sessionId", "manual-session-002"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // Turn 1 — incomplete (no departure date / origin): agent should pause for more input.
                .send(INCOMPLETE_TURN_1)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(distinctStatesInOrder(ctx.events()))
                            .as("A-08 turn-1 streamed state sequence: SUBMITTED → WORKING → INPUT_REQUIRED")
                            .containsExactly(
                                    TaskState.TASK_STATE_SUBMITTED,
                                    TaskState.TASK_STATE_WORKING,
                                    TaskState.TASK_STATE_INPUT_REQUIRED))
                    .assertTask(task -> assertThat(textOf(task))
                            .as("turn-1 reply (clarifying question) is a valid answer")
                            .isNotBlank())
                // Turn 2 — supply the missing field. InteractionFlow continues the same contextId,
                // so mainplan sees the full conversation and can complete the itinerary. This is a
                // continuation round, so the assertion is weak (containsSubsequence): WORKING →
                // COMPLETED must appear in order, but SUBMITTED is neither required nor forbidden —
                // different runtimes may or may not re-emit it when resuming an in-progress task.
                .send(INCOMPLETE_TURN_2)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> assertThat(distinctStatesInOrder(ctx.events()))
                            .as("A-08 turn-2 streamed states include WORKING → COMPLETED "
                                    + "(continuation round — SUBMITTED optional)")
                            .containsSubsequence(
                                    TaskState.TASK_STATE_WORKING,
                                    TaskState.TASK_STATE_COMPLETED))
                    .assertTask(task -> {
                        String finalAnswer = textOf(task);
                        assertThat(finalAnswer).as("turn-2 completed travel-plan text").isNotBlank();
                        assertThat(finalAnswer.length())
                                .as("final itinerary is substantive").isGreaterThan(8);
                    })
                .execute();
    }

    // ---- C-03: 多轮信息收集（3 轮：INPUT_REQUIRED → INPUT_REQUIRED → COMPLETED）----

    /**
     * C-03 — multi-turn information collection across three rounds (特性 1+2, module C). mainplan's
     * {@code request_user_input} rail should keep asking until the request is whole: turn 1 (intent +
     * destination, no dates/origin) and turn 2 (adds duration + a date, but still no origin) each
     * stream {@code INPUT_REQUIRED} with a valid clarifying reply; turn 3 supplies the origin and the
     * rest, streaming {@code COMPLETED} with a valid itinerary.
     *
     * <p>This is the three-turn generalisation of A-08's two-turn flow: each round continues the same
     * {@code contextId} (threaded by {@link InteractionFlow}), so mainplan accumulates the prior turns
     * and only completes once the request is genuinely complete. Turn 2 deliberately withholds the
     * origin — a core field whose absence A-08 showed triggers {@code INPUT_REQUIRED} — so the rail is
     * expected to ask again before turn 3 finishes it.
     */
    @Test
    @DisplayName("C-03: three-turn info collection streams INPUT_REQUIRED→INPUT_REQUIRED→COMPLETED")
    void threeTurnCollectionFollowsExpectedStateSequences() {
        InteractionFlow.of(client("mainplan"))
                .withMetadata(Map.of("userId", "manual-user", "agentId", "main-plan-agent",
                        "sessionId", "manual-session-003"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // Turn 1 — intent + destination only (no dates, no origin): rail should ask for more.
                .send(COLLECTION_TURN_1)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(distinctStatesInOrder(ctx.events()))
                            .as("C-03 turn-1 streamed state sequence: SUBMITTED → WORKING → INPUT_REQUIRED")
                            .containsExactly(
                                    TaskState.TASK_STATE_SUBMITTED,
                                    TaskState.TASK_STATE_WORKING,
                                    TaskState.TASK_STATE_INPUT_REQUIRED))
                    .assertTask(task -> assertThat(textOf(task))
                            .as("turn-1 reply (clarifying question) is a valid answer")
                            .isNotBlank())
                // Turn 2 — adds duration + a date, but still withholds the origin: rail should ask again.
                // Continuation round ⇒ weak match: WORKING → INPUT_REQUIRED in order, SUBMITTED optional.
                .send(COLLECTION_TURN_2)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(ctx -> assertThat(distinctStatesInOrder(ctx.events()))
                            .as("C-03 turn-2 streamed states include WORKING → INPUT_REQUIRED "
                                    + "(continuation round — SUBMITTED optional)")
                            .containsSubsequence(
                                    TaskState.TASK_STATE_WORKING,
                                    TaskState.TASK_STATE_INPUT_REQUIRED))
                    .assertTask(task -> assertThat(textOf(task))
                            .as("turn-2 reply (still asking for the missing origin) is a valid answer")
                            .isNotBlank())
                // Turn 3 — supplies the origin + the rest, completing the request.
                // Continuation round ⇒ weak match: WORKING → COMPLETED in order, SUBMITTED optional.
                .send(COLLECTION_TURN_3)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> assertThat(distinctStatesInOrder(ctx.events()))
                            .as("C-03 turn-3 streamed states include WORKING → COMPLETED "
                                    + "(continuation round — SUBMITTED optional)")
                            .containsSubsequence(
                                    TaskState.TASK_STATE_WORKING,
                                    TaskState.TASK_STATE_COMPLETED))
                    .assertTask(task -> {
                        String finalAnswer = textOf(task);
                        assertThat(finalAnswer).as("turn-3 completed travel-plan text").isNotBlank();
                        assertThat(finalAnswer.length())
                                .as("final itinerary is substantive").isGreaterThan(8);
                    })
                .execute();
    }

    // ---- helpers ----

    /**
     * Ordered, de-duplicated task states observed in a round's event stream — i.e. the task's state
     * trajectory with repeats collapsed (e.g. multiple {@code WORKING} progress updates count once).
     * Used to assert the streamed state-machine sequence.
     */
    private static List<TaskState> distinctStatesInOrder(List<ClientEvent> events) {
        LinkedHashSet<TaskState> states = new LinkedHashSet<>();
        for (ClientEvent event : events) {
            Task task = taskOf(event);
            if (task != null && task.status() != null && task.status().state() != null) {
                states.add(task.status().state());
            }
        }
        return new ArrayList<>(states);
    }

    private static Task taskOf(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask();
        }
        if (event instanceof TaskUpdateEvent ue) {
            return ue.getTask();
        }
        return null;
    }

    /** Concatenate the task's textual output: artifacts → status message → last history message. */
    private static String textOf(Task task) {
        StringBuilder sb = new StringBuilder();
        if (task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) {
                appendText(sb, artifact.parts());
            }
        }
        if (sb.length() == 0 && task.status() != null && task.status().message() != null) {
            appendText(sb, task.status().message().parts());
        }
        if (sb.length() == 0 && task.history() != null && !task.history().isEmpty()) {
            appendText(sb, task.history().get(task.history().size() - 1).parts());
        }
        return sb.toString().trim();
    }

    private static void appendText(StringBuilder sb, List<Part<?>> parts) {
        if (parts == null) {
            return;
        }
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                sb.append(textPart.text());
            }
        }
    }
}
