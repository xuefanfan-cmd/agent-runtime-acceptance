package com.huawei.ascend.sit.cases.integration.react_travel;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.MessageProtocol;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-07 / A-08 / C-03 — 差旅规划业务问答，参数化覆盖 4 种线协议 (特性 3-1 / 模块 C).
 *
 * <p>Each scenario runs 4× via {@link ParameterizedTest} + {@link EnumSource}: A2A 流式
 * ({@code message/stream}), A2A 同步 ({@code message/send}), REST 流式 ({@code POST /v1/query}
 * {@code stream:true}), REST 同步 ({@code stream:false}). The goal is to verify {@link InteractionFlow}
 * drives the full travel chain (mainplan → trip → hotel) over <em>each</em> transport, so this is a
 * <b>debug harness</b>: whichever cell goes red points at the transport adapter that is not wired yet.
 *
 * <p>mainplan is the entry ReAct agent: it collects the trip requirements, then dispatches to trip via
 * the remote-a2a {@code dispatch_travel_plan} tool, and trip in turn may dispatch to hotel. A "valid
 * answer" therefore needs all three agents wired <em>and</em> a real LLM behind each. mainplan also
 * registers a {@code request_user_input} rail, so an incomplete request pauses in
 * {@code INPUT_REQUIRED} rather than completing — that is what A-08 / C-03 drive.
 *
 * <ul>
 *   <li><b>A-07 — one-shot complete input</b>: a single fully-specified request must reach
 *       {@code COMPLETED} with a non-empty, substantive itinerary.</li>
 *   <li><b>A-08 — multi-turn supplementary input</b>: an incomplete first turn must reach
 *       {@code INPUT_REQUIRED} (the agent asks for the missing field) with a valid reply, then a
 *       follow-up turn — continuing the same {@code contextId} — must reach {@code COMPLETED}.</li>
 *   <li><b>C-03 — three-turn information collection</b>: turns 1 and 2 each reach {@code INPUT_REQUIRED};
 *       turn 3 supplies the last missing field and reaches {@code COMPLETED}.</li>
 * </ul>
 *
 * <p><b>Protocol-aware assertions (two tiers):</b>
 * <ul>
 *   <li>{@code .awaitState(...)} is <b>strict across all 4 protocols</b> — transport-agnostic
 *       ({@code InboundExchange} normalises terminal / input-required states). This is the debug
 *       signal: an INPUT_REQUIRED round failing under A2A_SYNC pinpoints that {@code A2aSyncTransport}
 *       drops non-final states; failing under REST pinpoints the uncalibrated INPUT_REQUIRED SSE
 *       marker.</li>
 *   <li><b>Text assertions are split by round outcome:</b> a {@code COMPLETED} round uses
 *       {@code .assertAnswer(...)} (strict — the discrete {@code Kind.ANSWER}, the final processed
 *       result); an {@code INPUT_REQUIRED} round uses {@code .assertGenerated(...)} instead, because
 *       intermediate rounds emit <em>no</em> discrete {@code ANSWER} — the model is mid-thought,
 *       streaming reasoning/output, and its reply is the clarifying question. Asserting
 *       {@code answerText()} there would be blank by design. Both views are read from the same local
 *       event stream, so both are protocol-neutral.</li>
 *   <li>The <b>state-trajectory assertion</b> ({@code SUBMITTED → WORKING → terminal}) runs
 *       {@link MessageProtocol#A2A_STREAM A2A_STREAM-only} via {@link #assertStreamTrajectory} — sync
 *       and REST surface only the terminal state, so the full sequence is asserted solely where it is
 *       real. For the other 3 protocols the terminal is already covered by {@code .awaitState}, so the
 *       trajectory check is a no-op.</li>
 * </ul>
 *
 * <p><b>Debug matrix:</b> A-07 should pass on all 4 protocols ({@code COMPLETED} is terminal, reachable
 * everywhere). A-08 / C-03 INPUT_REQUIRED rounds pass only on A2A_STREAM — under A2A_SYNC / REST they
 * are expected to fail or time out, which is exactly the signal that those transports do not yet
 * surface INPUT_REQUIRED.
 *
 * <p>{@link InteractionFlow} threads each round's {@code contextId} into the next, so A-08's two
 * {@code .send(...)} calls (and C-03's three) are one continued conversation. Each protocol invocation
 * uses a distinct {@code sessionId} ({@code "<scenario>-<protocol>"}) so the 12 concurrent runs do not
 * collide on the SUT side.
 *
 * <p><b>Credentials &amp; proxy.</b> Export the unified {@code LLM_*} env once — all three agents read
 * {@code ${LLM_*}} natively, and {@code ProcessLauncher} passes the test JVM's env to each child.
 * HTTP proxy / no-proxy go as JVM {@code -D} system properties via {@code sut.java.system-properties}
 * in {@code application-local.yml}. Then run {@code ./mvnw -Dtest=StreamingTravelPlanningTest test}.
 */
@Tag("integration")
// Run this class's parameterized methods (3 scenarios × 4 protocols = 12 invocations) concurrently
// against the single shared managed stack (mainplan → trip → hotel), brought up once at @BeforeAll.
// BaseManagedStackTest is @TestInstance(PER_CLASS), so the same instance — and thus config/client — is
// reused for every invocation; the stack fields are set in @BeforeAll and only read here. Each
// invocation gets a protocol-scoped sessionId so concurrent conversations don't collide on the SUT.
// Requires junit.jupiter.execution.parallel.enabled=true (src/test/resources/junit-platform.properties);
// the suite default stays same_thread, so only opting-in classes run in parallel.
@Execution(ExecutionMode.CONCURRENT)
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
        // Full chain, declared leaf-first. SutStack injects each downstream's resolved base URL into
        // its upstream's agent-runtime.remote-agents[0].url. The STACK is protocol-agnostic — the same
        // three agents serve all 4 protocols; only the InteractionFlow client's transport changes per
        // invocation (via .protocol(...)). All three agents read the unified LLM_* natively (their
        // bundled yaml uses ${LLM_*} placeholders, ProcessLauncher passes the test JVM's env to each
        // child), so no per-agent LLM wiring is needed (export LLM_* once; proxy via
        // sut.java.system-properties in application-local.yml).
        return SutStack.builder(config)
                .agent("hotel")
                .agent("trip", a -> a.downstream("hotel"))
                .agent("mainplan", a -> a.downstream("trip"));
    }

    // ---- A-07: 一次性完整输入问答（→ COMPLETED）----

    /**
     * A-07 — a fully-specified request reaches {@code COMPLETED} in a single turn with a valid 差旅规划.
     *
     * <p>Expected on every protocol: {@code COMPLETED} reached; answer non-blank and substantive (a
     * complete itinerary from mainplan→trip→hotel, not an error/empty refusal). On A2A_STREAM the full
     * streamed sequence {@code SUBMITTED → WORKING → COMPLETED} is also asserted.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC", "REST_QUERY", "REST_QUERY_SYNC"})
    @DisplayName("A-07: one-shot complete request reaches COMPLETED with a valid plan")
    void oneShotRequestStreamsSubmittedWorkingCompleted(MessageProtocol protocol) {
        InteractionFlow.of(client("mainplan"))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "main-plan-agent",
                        "sessionId", "a07-" + protocol.name()))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send(COMPLETE_REQUEST)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "A-07 streamed state sequence: SUBMITTED → WORKING → COMPLETED",
                            false, TaskState.TASK_STATE_COMPLETED))
                    .assertAnswer(answer -> {
                        assertThat(answer).as("completed travel-plan text").isNotBlank();
                        // A valid itinerary is substantive, not a bare error message.
                        assertThat(answer.length())
                                .as("answer is substantive (not a bare error/refusal)")
                                .isGreaterThan(8);
                    })
                .execute();
    }

    // ---- A-08: 多次请求补充输入场景 ----

    /**
     * A-08 — an incomplete turn reaches {@code INPUT_REQUIRED} (with a valid clarifying reply), then a
     * follow-up turn continuing the same {@code contextId} reaches {@code COMPLETED} (with a valid
     * final itinerary).
     *
     * <p>Each round must produce a valid reply: turn 1 ends in {@code INPUT_REQUIRED} with the agent's
     * clarifying question — asserted via {@code .assertGenerated(...)} (intermediate rounds carry no
     * discrete {@code ANSWER}); turn 2 ends in {@code COMPLETED} with the completed itinerary —
     * asserted via {@code .assertAnswer(...)} (the strict discrete answer). {@link InteractionFlow}
     * threads turn 1's {@code contextId} into turn 2 automatically, so the follow-up is the same
     * conversation. On A2A_STREAM the state trajectories are asserted (turn 1 strict, turn 2 weak
     * continuation); elsewhere only the terminal states are enforced (see
     * {@link #assertStreamTrajectory}).
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC", "REST_QUERY", "REST_QUERY_SYNC"})
    @DisplayName("A-08: incomplete turn → INPUT_REQUIRED, follow-up → COMPLETED")
    void incompleteThenFollowUpFollowsExpectedStateSequences(MessageProtocol protocol) {
        InteractionFlow.of(client("mainplan"))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "main-plan-agent",
                        "sessionId", "a08-" + protocol.name()))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // Turn 1 — incomplete (no departure date / origin): agent should pause for more input.
                .send(INCOMPLETE_TURN_1)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "A-08 turn-1 streamed state sequence: SUBMITTED → WORKING → INPUT_REQUIRED",
                            false, TaskState.TASK_STATE_INPUT_REQUIRED))
                    // Turn 1 ends in INPUT_REQUIRED: no discrete ANSWER (the model is mid-thought,
                    // streaming reasoning/output), so assert on the generated reply, not answerText().
                    .assertGenerated(reply -> assertThat(reply)
                            .as("turn-1 reply (clarifying question) is a non-blank generated reply")
                            .isNotBlank())
                // Turn 2 — supply the missing field. InteractionFlow continues the same contextId,
                // so mainplan sees the full conversation and can complete the itinerary.
                .send(INCOMPLETE_TURN_2)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "A-08 turn-2 streamed states include WORKING → COMPLETED "
                                    + "(continuation round — SUBMITTED optional)",
                            true, TaskState.TASK_STATE_COMPLETED))
                    .assertAnswer(answer -> {
                        assertThat(answer).as("turn-2 completed travel-plan text").isNotBlank();
                        assertThat(answer.length())
                                .as("final itinerary is substantive").isGreaterThan(8);
                    })
                .execute();
    }

    // ---- C-03: 多轮信息收集（3 轮：INPUT_REQUIRED → INPUT_REQUIRED → COMPLETED）----

    /**
     * C-03 — multi-turn information collection across three rounds (特性 1+2, module C). mainplan's
     * {@code request_user_input} rail should keep asking until the request is whole: turn 1 (intent +
     * destination, no dates/origin) and turn 2 (adds duration + a date, but still no origin) each reach
     * {@code INPUT_REQUIRED} with a valid clarifying reply; turn 3 supplies the origin and the rest,
     * reaching {@code COMPLETED} with a valid itinerary.
     *
     * <p>This is the three-turn generalisation of A-08's two-turn flow: each round continues the same
     * {@code contextId} (threaded by {@link InteractionFlow}). Turn 2 deliberately withholds the
     * origin — a core field whose absence A-08 showed triggers {@code INPUT_REQUIRED} — so the rail is
     * expected to ask again before turn 3 finishes it.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC", "REST_QUERY", "REST_QUERY_SYNC"})
    @DisplayName("C-03: three-turn info collection → INPUT_REQUIRED→INPUT_REQUIRED→COMPLETED")
    void threeTurnCollectionFollowsExpectedStateSequences(MessageProtocol protocol) {
        InteractionFlow.of(client("mainplan"))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "main-plan-agent",
                        "sessionId", "c03-" + protocol.name()))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // Turn 1 — intent + destination only (no dates, no origin): rail should ask for more.
                .send(COLLECTION_TURN_1)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "C-03 turn-1 streamed state sequence: SUBMITTED → WORKING → INPUT_REQUIRED",
                            false, TaskState.TASK_STATE_INPUT_REQUIRED))
                    // INPUT_REQUIRED rounds have no discrete ANSWER (model streams reasoning/output),
                    // so assert on the generated reply instead of answerText().
                    .assertGenerated(reply -> assertThat(reply)
                            .as("turn-1 reply (clarifying question) is a non-blank generated reply")
                            .isNotBlank())
                // Turn 2 — adds duration + a date, but still withholds the origin: rail should ask again.
                .send(COLLECTION_TURN_2)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "C-03 turn-2 streamed states include WORKING → INPUT_REQUIRED "
                                    + "(continuation round — SUBMITTED optional)",
                            true, TaskState.TASK_STATE_INPUT_REQUIRED))
                    .assertGenerated(reply -> assertThat(reply)
                            .as("turn-2 reply (still asking for the missing origin) is a non-blank generated reply")
                            .isNotBlank())
                // Turn 3 — supplies the origin + the rest, completing the request.
                .send(COLLECTION_TURN_3)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "C-03 turn-3 streamed states include WORKING → COMPLETED "
                                    + "(continuation round — SUBMITTED optional)",
                            true, TaskState.TASK_STATE_COMPLETED))
                    .assertAnswer(answer -> {
                        assertThat(answer).as("turn-3 completed travel-plan text").isNotBlank();
                        assertThat(answer.length())
                                .as("final itinerary is substantive").isGreaterThan(8);
                    })
                .execute();
    }

    // ---- helpers ----

    /**
     * Assert the streamed task-state trajectory for {@link MessageProtocol#A2A_STREAM} only. The sync
     * and REST transports surface just the terminal state (no {@code SUBMITTED}/{@code WORKING}), and
     * the terminal is already enforced by {@code .awaitState(...)} upstream — so for those protocols
     * there is nothing extra to check and this asserter is a no-op. {@code continuation} switches the
     * stream match from strict ({@code containsExactly}: {@code SUBMITTED → WORKING → terminal} for a
     * fresh round) to weak ({@code containsSubsequence}: {@code WORKING → terminal}, {@code SUBMITTED}
     * optional) for a resumed round — a runtime may legitimately re-emit {@code SUBMITTED} when
     * resuming an in-progress task.
     */
    private static Consumer<InteractionFlow.RoundContext> assertStreamTrajectory(
            MessageProtocol protocol, String description, boolean continuation, TaskState terminal) {
        return ctx -> {
            if (protocol != MessageProtocol.A2A_STREAM) {
                return;
            }
            List<TaskState> trajectory = distinctStatesInOrder(ctx.events());
            if (continuation) {
                assertThat(trajectory)
                        .as(description)
                        .containsSubsequence(TaskState.TASK_STATE_WORKING, terminal);
            } else {
                assertThat(trajectory)
                        .as(description)
                        .containsExactly(
                                TaskState.TASK_STATE_SUBMITTED,
                                TaskState.TASK_STATE_WORKING,
                                terminal);
            }
        };
    }

    /**
     * Ordered, de-duplicated task states observed in a round's event stream — i.e. the task's state
     * trajectory with repeats collapsed (e.g. multiple {@code WORKING} progress updates count once).
     * Used to assert the streamed state-machine sequence.
     */
    private static List<TaskState> distinctStatesInOrder(List<InboundEvent> events) {
        List<TaskState> seen = new ArrayList<>();
        for (InboundEvent e : events) {
            if (e.kind() == InboundEvent.Kind.STATE && !seen.contains(e.state())) {
                seen.add(e.state());
            }
        }
        return seen;
    }
}
