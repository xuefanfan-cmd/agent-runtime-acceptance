package com.huawei.ascend.sit.client;

import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import java.util.Objects;

/**
 * Fluent DSL for composing multi-turn A2A agent interaction tests.
 *
 * <p>Designed for <b>simple to moderate linear flows</b> where the test code
 * itself should be the documentation — inputs, expected states, and assertions
 * are all visible inline.</p>
 *
 * <p>For complex branching scenarios with conditional jumps, use
 * {@link ScenarioExecutor} with YAML definitions instead.</p>
 *
 * <h3>Usage example:</h3>
 * <pre>{@code
 * InteractionFlow.of(a2aClient)
 *     .send("今天天气怎么样")
 *         .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
 *     .send("北京")
 *         .awaitState(TaskState.TASK_STATE_COMPLETED)
 *         .assertTask(task -> assertThat(task.artifacts()).isNotEmpty())
 *     .execute();
 * }</pre>
 *
 * <h3>Design principles:</h3>
 * <ul>
 *   <li>Each {@code .send(text)} starts a new interaction round</li>
 *   <li>Chain expectations and assertions on the round result</li>
 *   <li>{@link A2aEventCollector} + Awaitility handles async→sync under the hood</li>
 *   <li>All rounds are executed in order on {@code .execute()}</li>
 *   <li>Multi-turn continuity (A2A protocol): a round whose previous round did <b>not</b> reach a
 *       terminal state (e.g. {@code INPUT_REQUIRED} — task still open, awaiting more input) is a
 *       <b>continuation</b> — it carries the previous round's {@code taskId} <em>and</em>
 *       {@code contextId} (both, per protocol, to resume the <em>same</em> task). Once a round
 *       reaches a terminal state ({@code COMPLETED}/{@code FAILED}/{@code CANCELED}) the next round
 *       is <b>fresh</b> — it carries neither, letting the server assign a new task/contextId
 *       (captured from the response, overwriting any stale local value). Use
 *       {@link #withContextId(String)} to pin one {@code contextId} across the whole flow regardless
 *       of terminal boundaries.</li>
 * </ul>
 */
public class InteractionFlow {

    private static final Logger LOG = Logger.getLogger(InteractionFlow.class.getName());

    private final A2aServiceClient client;
    private final List<RoundDefinition> rounds = new ArrayList<>();
    private long timeoutMs = 30_000;
    private Map<String, Object> defaultMetadata;
    private String fixedContextId;

    private InteractionFlow(A2aServiceClient client) {
        this.client = client;
    }

    /** Start building an interaction flow with the given A2A client. */
    public static InteractionFlow of(A2aServiceClient client) {
        return new InteractionFlow(client);
    }

    /** Set the timeout for each round's state waiting (default: 30s). */
    public InteractionFlow withTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    /**
     * Set default metadata for all rounds in this flow.
     *
     * <p>Individual rounds can override this via {@link RoundDefinition#withMetadata(Map)}.</p>
     *
     * @param metadata per-request metadata passed to the A2A SDK
     */
    public InteractionFlow withMetadata(Map<String, Object> metadata) {
        this.defaultMetadata = metadata;
        return this;
    }

    /**
     * Pin a {@code contextId} carried on <em>every</em> round (including fresh rounds that follow a
     * terminal state).
     *
     * <p>By default a fresh round (the first round, or any round after the previous reached a
     * terminal state) sends no {@code contextId} and lets the server allocate one; a continuation
     * round carries the previous round's server-assigned {@code contextId}. Setting this overrides
     * both: every round carries this exact {@code contextId}, grouping the whole flow under one
     * conversation even across terminal boundaries. {@code taskId} is unaffected — continuation
     * rounds still carry the previous task, fresh rounds still let the server assign a new one.
     */
    public InteractionFlow withContextId(String contextId) {
        this.fixedContextId = contextId;
        return this;
    }

    /** Start a new interaction round by sending the given text. */
    public RoundDefinition send(String text) {
        RoundDefinition round = new RoundDefinition(this, text);
        rounds.add(round);
        return round;
    }

    /**
     * Execute all rounds in sequence and return the flow result.
     *
     * @return the execution result containing per-round traces
     * @throws AssertionError if any round's expectations are not met
     */
    public FlowResult execute() {
        List<RoundResult> results = new ArrayList<>();
        String lastTaskId = null;
        String lastContextId = null;
        TaskState lastState = null;

        LOG.info("▶ Starting InteractionFlow (" + rounds.size() + " rounds)");

        for (int i = 0; i < rounds.size(); i++) {
            RoundDefinition round = rounds.get(i);
            LOG.info("  → Round " + (i + 1) + ": send \"" + truncate(round.inputText, 50) + "\"");

            // 续轮判定（A2A 协议契约）：
            //   上一轮存在且未到终态（如 INPUT_REQUIRED —— 任务仍开放、可补输入）
            //   ⇒ 本轮续传**同一任务**：携带上一轮的 taskId + contextId。
            //   上一轮已终态（COMPLETED/FAILED/CANCELED —— 收到 COMPLETED 后即视为会话结束）
            //   ⇒ 本轮是新会话：不携带旧 taskId/contextId，由服务端重新分配；
            //     result 里的 taskId/contextId 即为服务端新生成的值，用来覆盖本地缓存。
            boolean continuation = lastTaskId != null && !lastTaskId.isEmpty()
                    && lastState != null && !lastState.isFinal();
            RoundResult result = executeRound(round, client, timeoutMs,
                    continuation ? lastTaskId : null,
                    continuation ? lastContextId : null);
            results.add(result);

            LOG.info("  ✓ State: " + result.taskState()
                    + " (task: " + result.taskId()
                    + ", ctx: " + result.contextId()
                    + ", events: " + result.eventCount() + ")"
                    + (continuation ? "  [continuation]" : "  [fresh]"));

            // 非续轮时这两个值是服务端新生成的；续轮时是服务端回显的携带值 —— 都用它覆盖本地缓存。
            lastTaskId = result.taskId();
            lastContextId = result.contextId();
            lastState = result.taskState();
        }

        LOG.info("✔ InteractionFlow completed (" + results.size() + " rounds)");
        return new FlowResult(results, lastTaskId);
    }

    private RoundResult executeRound(RoundDefinition round, A2aServiceClient client, long timeoutMs,
                                     String continuationTaskId, String continuationContextId) {
        A2aEventCollector collector = new A2aEventCollector();

        // 续轮：携带上一轮的 taskId + contextId —— A2A 协议要求两者都带方可在原任务上继续
        // （仅带 contextId 会被服务端当作同会话下的新任务，taskId 互异，不符合"续传同一任务"）。
        // 非续轮：两者都不带，由服务端新生成；contextId 若有 flow 级固定值（withContextId）则始终携带。
        Message.Builder builder = Message.builder(A2A.toUserMessage(round.inputText));
        if (continuationTaskId != null && !continuationTaskId.isEmpty()) {
            builder.taskId(continuationTaskId);
        }
        String effectiveContextId = (continuationContextId != null && !continuationContextId.isEmpty())
                ? continuationContextId : fixedContextId;
        if (effectiveContextId != null && !effectiveContextId.isEmpty()) {
            builder.contextId(effectiveContextId);
        }
        Message message = builder.build();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = error -> {
            LOG.warning("Stream error: " + error.getMessage());
        };

        // Resolve effective metadata: round-specific > flow-default > null
        Map<String, Object> effectiveMetadata = round.metadata != null
                ? round.metadata : defaultMetadata;

        client.sendMessage(message, effectiveMetadata, consumers, errorHandler);

        // Await the expected state
        TaskState observedState;
        if (round.expectedState != null) {
            if (round.expectedState == TaskState.TASK_STATE_INPUT_REQUIRED) {
                boolean gotInputRequired = collector.awaitInputRequired(timeoutMs);
                if (round.expectStateRequired && !gotInputRequired) {
                    observedState = collector.awaitTerminalState(timeoutMs);
                    throw new AssertionError(
                            "Expected TASK_STATE_INPUT_REQUIRED but got " + observedState);
                }
                observedState = gotInputRequired
                        ? TaskState.TASK_STATE_INPUT_REQUIRED
                        : collector.awaitAnyTaskState(timeoutMs);
            } else if (round.expectedState.isFinal()) {
                observedState = collector.awaitTerminalState(timeoutMs);
            } else {
                observedState = collector.awaitAnyTaskState(timeoutMs);
            }

            // Verify expected state
            if (round.expectStateRequired) {
                if (observedState != round.expectedState) {
                    throw new AssertionError(String.format(
                            "Round \"%s\": expected state %s but got %s",
                            truncate(round.inputText, 30), round.expectedState, observedState));
                }
            }
        } else {
            // No explicit state expectation — just wait for any terminal or input-required
            try {
                observedState = collector.awaitTerminalState(timeoutMs);
            } catch (AssertionError e) {
                observedState = collector.awaitAnyTaskState(timeoutMs);
            }
        }

        // Extract task ID (non-destructive)
        String taskId = collector.findFirstTaskId();

        int eventCount = collector.eventCount();

        // Snapshot the full event stream before assertions
        List<ClientEvent> events = collector.snapshotAllEvents();
        // Context ID of this round's task — carried into the next round for multi-turn continuation.
        String contextId = collector.findFirstContextId();

        // Run custom assertions
        if (round.taskAsserter != null) {
            // Fetch full task for assertion
            Task task = client.getTask(taskId);
            round.taskAsserter.accept(task);
        }

        if (round.eventsAsserter != null) {
            round.eventsAsserter.accept(eventCount);
        }

        // Run generic assertion with full round context (including event stream)
        if (round.genericAsserter != null) {
            RoundContext ctx = new RoundContext(taskId, observedState, eventCount,
                    client.getTask(taskId), events, contextId);
            round.genericAsserter.accept(ctx);
        }

        return new RoundResult(round.inputText, taskId, observedState, eventCount, events, contextId);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ===== Inner types =====

    /**
     * Defines a single round of interaction (send + expectations + assertions).
     */
    public static class RoundDefinition {
        private final InteractionFlow flow;
        private final String inputText;
        private TaskState expectedState;
        private boolean expectStateRequired = true;
        private Map<String, Object> metadata;
        private Consumer<Task> taskAsserter;
        private Consumer<Integer> eventsAsserter;
        private Consumer<RoundContext> genericAsserter;

        RoundDefinition(InteractionFlow flow, String inputText) {
            this.flow = flow;
            this.inputText = inputText;
        }

        /** Expect the task to reach this state after sending the message. */
        public RoundDefinition awaitState(TaskState state) {
            this.expectedState = state;
            this.expectStateRequired = true;
            return this;
        }

        /** Expect the task to eventually reach this state, but don't fail if it doesn't match exactly. */
        public RoundDefinition mayReachState(TaskState state) {
            this.expectedState = state;
            this.expectStateRequired = false;
            return this;
        }

        /** Assert on the full Task object after the round completes. */
        public RoundDefinition assertTask(Consumer<Task> asserter) {
            this.taskAsserter = asserter;
            return this;
        }

        /** Assert on the number of events received. */
        public RoundDefinition expectEvents(Consumer<Integer> asserter) {
            this.eventsAsserter = asserter;
            return this;
        }

        /** Generic assertion with full round context. */
        public RoundDefinition assertThat(Consumer<RoundContext> asserter) {
            this.genericAsserter = asserter;
            return this;
        }

        /**
         * Set per-round metadata that overrides the flow-level default.
         *
         * <p>Precedence: round metadata > flow {@code withMetadata()} > null.</p>
         *
         * @param metadata per-request metadata for this round only
         */
        public RoundDefinition withMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /** Start a new round (chains directly to the next RoundDefinition). */
        public RoundDefinition send(String nextText) {
            return flow.send(nextText);
        }

        /** Execute the entire flow (terminal operation). */
        public FlowResult execute() {
            return flow.execute();
        }
    }

    /**
     * Context passed to custom assertions within a round.
     *
     * <p>Includes the full event stream snapshot for detailed inspection,
     * e.g. checking event ordering, verifying specific event types, etc.</p>
     */
    public record RoundContext(
            String taskId,
            TaskState taskState,
            int eventCount,
            Task task,
            List<ClientEvent> events,
            String contextId
    ) {}

    /**
     * Result of a single round within the flow.
     *
     * <p>Contains a snapshot of all events received during this round,
     * preserved via {@link A2aEventCollector#snapshotAllEvents()}.</p>
     */
    public record RoundResult(
            String inputText,
            String taskId,
            TaskState taskState,
            int eventCount,
            List<ClientEvent> events,
            String contextId
    ) {}

    /**
     * Result of the entire interaction flow.
     */
    public record FlowResult(
            List<RoundResult> rounds,
            String lastTaskId
    ) {
        /** Get a specific round result by index (0-based). */
        public RoundResult round(int index) {
            return rounds.get(index);
        }

        /** Number of rounds executed. */
        public int roundCount() {
            return rounds.size();
        }
    }
}
