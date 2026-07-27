package com.huawei.ascend.sit.client;

import com.huawei.ascend.sit.transport.A2aStreamingTransport;
import com.huawei.ascend.sit.transport.A2aStreamingWire;
import com.huawei.ascend.sit.transport.A2aSyncTransport;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.InboundExchange;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.huawei.ascend.sit.transport.MessageTransport;
import com.huawei.ascend.sit.transport.OutboundMessage;
import com.huawei.ascend.sit.transport.ProtocolResolver;
import com.huawei.ascend.sit.transport.RestGatewayTransport;
import com.huawei.ascend.sit.transport.RestExchange;
import com.huawei.ascend.sit.transport.RestQueryTransport;
import com.huawei.ascend.sit.transport.RestVersatileTransport;
import com.huawei.ascend.sit.transport.SessionLabels;
import com.huawei.ascend.sit.transport.WireLogger;
import com.huawei.ascend.sit.transport.WireRequestRenderer;

import org.a2aproject.sdk.spec.TaskState;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

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
 *         .assertAnswer(text -> assertThat(text).isNotEmpty())
 *     .execute();
 * }</pre>
 *
 * <h3>Design principles:</h3>
 * <ul>
 *   <li>Each {@code .send(text)} starts a new interaction round</li>
 *   <li>Chain expectations and assertions on the round result</li>
 *   <li>{@link InboundExchange} (await + derive) handles async→sync under the hood</li>
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
    private MessageTransport transport;          // drop `final`; null until execute() on the of(client) path
    private WireLogger wireLogger;               // null → resolved from config at execute(); test-injected otherwise
    private final List<RoundDefinition> rounds = new ArrayList<>();
    private long timeoutMs = 30_000;
    private Map<String, Object> defaultMetadata;
    private String fixedContextId;
    private MessageProtocol protocolOverride;

    private InteractionFlow(A2aServiceClient client, MessageTransport transport) {
        this.client = client;
        this.transport = transport;
    }

    /** Start building an interaction flow with the given A2A client (default A2A-streaming transport). */
    public static InteractionFlow of(A2aServiceClient client) {
        return new InteractionFlow(client, null);   // transport resolved from protocol at execute time
    }

    /**
     * Transport-only entry for unit tests of the flow logic: no A2A client. All asserter methods
     * work, because assertions read the local event stream ({@link InboundExchange#answerText()}
     * / events), not an A2A {@code getTask} snapshot — so none of them needs a client. The
     * send/await/continuation logic is exercised against an injected fake transport with no network.
     */
    static InteractionFlow using(MessageTransport transport) {
        return new InteractionFlow(null, transport);
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

    /** Builder override for the wire protocol (precedence: builder > env > default A2A_STREAM). */
    public InteractionFlow protocol(MessageProtocol protocol) {
        this.protocolOverride = protocol;
        return this;
    }

    /** Resolved protocol for this flow (override > env MESSAGE_PROTOCOL > A2A_STREAM). */
    MessageProtocol resolvedProtocol() {
        return new ProtocolResolver().resolve(protocolOverride).orElse(MessageProtocol.A2A_STREAM);
    }

    /**
     * Test-only injection of the wire logger (the {@code using(transport)} path has no config to read).
     * Production resolves it from {@code sut.wire-log.*} via {@link WireLoggerResolver} at execute time.
     */
    InteractionFlow withWireLogger(WireLogger logger) {
        this.wireLogger = logger;
        return this;
    }

    /**
     * Build the transport for the resolved protocol. The A2A transports bind the protocol-correct
     * SDK send path: {@code A2A_STREAM} → {@code sendMessageStreaming} (a streaming=true SDK Client
     * → SSE message/stream), {@code A2A_SYNC} → {@code sendMessageSync} (a streaming=false SDK
     * Client → blocking message/send). The SDK bakes the wire mode into the Client at build time, so
     * the two protocols MUST hit two different clients — binding both to {@code client::sendMessage}
     * (the legacy default) made the wire identical regardless of protocol.
     */
    private MessageTransport transportFor(A2aServiceClient client) {
        return switch (resolvedProtocol()) {
            case A2A_STREAM   -> new A2aStreamingTransport(new A2aStreamingWire(client::sendMessageStreaming));
            case A2A_SYNC     -> new A2aSyncTransport(client::sendMessageSync);
            case REST_QUERY   -> new RestQueryTransport(new RestExchange(),
                    URI.create(client.getBaseUrl() + "/v1/query"), true);
            case REST_QUERY_SYNC -> new RestQueryTransport(new RestExchange(),
                    URI.create(client.getBaseUrl() + "/v1/query"), false);
            case REST_REACTIVE -> new RestQueryTransport(new RestExchange(),
                    URI.create(client.getBaseUrl() + "/v1/query/reactive"), true);
            case REST_REACTIVE_SYNC -> new RestQueryTransport(new RestExchange(),
                    URI.create(client.getBaseUrl() + "/v1/query/reactive"), false);
            // The two EDPA-style wires share an identity-bearing conversation endpoint
            // (/v1/{pid}/agents/{aid}/conversations/{cid}) built from the shared GatewayIdentity +
            // the pinned cid (ensureConversationCid guarantees fixedContextId is set at execute()).
            case REST_VERSATILE -> new RestVersatileTransport(new RestExchange(),
                    GatewayIdentity.loadDefault().conversationEndpoint(client.getBaseUrl(), fixedContextId));
            case REST_GATEWAY -> new RestGatewayTransport(new RestExchange(),
                    GatewayIdentity.loadDefault().conversationEndpoint(client.getBaseUrl(), fixedContextId));
            default -> throw new IllegalStateException(
                    "Protocol not supported on InteractionFlow: " + resolvedProtocol());
        };
    }

    /**
     * Pin a conversation cid for the gateway/adapter wires ({@code REST_VERSATILE}/{@code REST_GATEWAY})
     * before the transport is built: their endpoint URL carries {@code {cid}}, which must equal the
     * body's {@code conversation_id} (one coherent conversation). A test-supplied
     * {@link #withContextId(String)} wins; otherwise a UUID is minted and pinned as the fixed context
     * id so every round — and the endpoint — share it. No-op for the other protocols (their endpoints
     * carry no cid; {@code RestQueryTransport} mints its own conversation_id in the body).
     */
    private void ensureConversationCid() {
        MessageProtocol p = resolvedProtocol();
        if ((p == MessageProtocol.REST_VERSATILE || p == MessageProtocol.REST_GATEWAY)
                && (fixedContextId == null || fixedContextId.isBlank())) {
            fixedContextId = UUID.randomUUID().toString();
        }
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
        ensureConversationCid();   // gateway/adapter wires carry {cid} in the endpoint URL — pin one before building it
        if (transport == null) {
            transport = transportFor(client);   // honours resolvedProtocol(): override > env > A2A_STREAM
        }
        if (wireLogger == null) {
            wireLogger = WireLoggerResolver.resolved();   // sut.wire-log.* → FileWireLogger, else NOOP
        }
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
            RoundResult result = executeRound(round, timeoutMs, i + 1,
                    continuation ? lastTaskId : null,
                    continuation ? lastContextId : null);
            results.add(result);

            LOG.info("  ✓ State: " + result.taskState()
                    + " (task: " + result.taskId()
                    + ", ctx: " + result.contextId()
                    + ", events: " + result.eventCount()
                    + ", took: " + result.durationMs() + "ms)"
                    + " answer: \"" + truncate(result.answerText(), 60) + "\""
                    + " generated: \"" + truncate(result.generatedText(), 60) + "\""
                    + (continuation ? "  [continuation]" : "  [fresh]"));

            // 非续轮时这两个值是服务端新生成的；续轮时是服务端回显的携带值 —— 都用它覆盖本地缓存。
            lastTaskId = result.taskId();
            lastContextId = result.contextId();
            lastState = result.taskState();
        }

        LOG.info("✔ InteractionFlow completed (" + results.size() + " rounds)");
        return new FlowResult(results, lastTaskId);
    }

    private RoundResult executeRound(RoundDefinition round, long timeoutMs, int roundNumber,
                                     String continuationTaskId, String continuationContextId) {
        // Resolve effective metadata: round-specific > flow-default > null
        Map<String, Object> effectiveMetadata = round.metadata != null ? round.metadata : defaultMetadata;

        // 续轮：携带上一轮的 taskId + contextId（A2A 协议要求两者都带方可在原任务上继续）。
        // 非续轮：两者都不带，由服务端新生成；contextId 若有 flow 级固定值（withContextId）则始终携带。
        String effectiveContextId = (continuationContextId != null && !continuationContextId.isEmpty())
                ? continuationContextId : fixedContextId;

        OutboundMessage outbound = new OutboundMessage(
                round.inputText,
                effectiveMetadata,
                (continuationTaskId != null && !continuationTaskId.isEmpty()) ? continuationTaskId : null,
                effectiveContextId);

        // Let the transport stamp transport-specific request identity (e.g. the REST conversation_id)
        // BEFORE send, so the wire-log's paste-ready request carries the exact bytes that will go out —
        // otherwise the renderer (which sees only the OutboundMessage, before send) would invent a
        // representative value that never matches the real send or the server's echoed response.
        outbound = transport.resolveOutbound(outbound);

        // T0 — captured before the request leaves so the wire-log header and any failure message can
        // report how long this round took (a fast-wrong-answer vs a hang/timeout). sentEpochMillis is
        // the wall-clock send instant; sendNanos drives the monotonic settle duration.
        final long sentEpochMillis = System.currentTimeMillis();
        final long sendNanos = System.nanoTime();

        InboundExchange exchange = transport.send(outbound);

        // Pre-render the paste-ready wire request once (cheap); reused by the post-await wire log.
        final String wireRequest = (wireLogger != null && wireLogger.enabled())
                ? WireRequestRenderer.render(resolvedProtocol(), outbound,
                        client == null ? null : endpointFor(resolvedProtocol(), client, fixedContextId))
                : null;

        // The await (success or AssertionError-on-timeout) is the drain/synchronization point: by the
        // time it returns/throws, exchange.events() holds everything that arrived this round. We wrap
        // the whole settle + assert + return in try/finally so the wire log is written EVEN WHEN the
        // round fails — otherwise the failing round's received packets were silently lost (the original
        // gap: the post-await log sat AFTER the throw). settledNanos is set only on the success path so
        // the finally can tell a settled round (accurate duration) from one that threw mid-await
        // (duration measured up to the throw ≈ the timeout).
        long settledNanos = 0L;
        TaskState observedState;
        try {
            // Await the expected state
            if (round.expectedState != null) {
                if (round.expectedState == TaskState.TASK_STATE_INPUT_REQUIRED) {
                    boolean gotInputRequired = exchange.awaitInputRequired(timeoutMs);
                    if (round.expectStateRequired && !gotInputRequired) {
                        observedState = exchange.awaitTerminalState(timeoutMs);
                        throw new AssertionError(roundLabel(roundNumber, outbound)
                                + ": expected TASK_STATE_INPUT_REQUIRED but got " + observedState
                                + " after " + elapsedMs(sendNanos) + "ms"
                                + " (sent \"" + truncate(round.inputText, 40) + "\")");
                    }
                    observedState = gotInputRequired
                            ? TaskState.TASK_STATE_INPUT_REQUIRED
                            : exchange.awaitAnyState(timeoutMs);
                } else if (round.expectedState.isFinal()) {
                    observedState = exchange.awaitTerminalState(timeoutMs);
                } else {
                    observedState = exchange.awaitAnyState(timeoutMs);
                }

                if (round.expectStateRequired) {
                    if (observedState != round.expectedState) {
                        throw new AssertionError(String.format(
                                "%s: expected state %s but got %s after %dms (sent \"%s\")",
                                roundLabel(roundNumber, outbound), round.expectedState, observedState,
                                elapsedMs(sendNanos), truncate(round.inputText, 40)));
                    }
                }
            } else {
                // No explicit state expectation (no .awaitState / .mayReachState): prefer a terminal state,
                // but leniently settle for any observed state if no terminal arrives within the timeout.
                // Note: a round that lingers in a non-terminal state (e.g. WORKING) passes here — set an
                // explicit .awaitState(...) to enforce a specific outcome. InboundExchange awaits throw
                // AssertionError on timeout; if no state ever arrives, awaitAnyState rethrows and fails.
                try {
                    observedState = exchange.awaitTerminalState(timeoutMs);
                } catch (AssertionError e) {
                    observedState = exchange.awaitAnyState(timeoutMs);
                }
            }
            settledNanos = System.nanoTime();   // exchange settled — response complete

            String taskId = exchange.taskId();
            int eventCount = exchange.eventCount();
            List<InboundEvent> events = exchange.events();
            String contextId = exchange.contextId();
            // The answer is read purely from the local event stream — never via an A2A getTask round-trip.
            // `answerText` is strict (ANSWER events only — the discrete final result, e.g. payload.output or
            // the REST non-stream result.content); `generatedText` is the superset (ANSWER/LLM_OUTPUT/
            // LLM_REASONING/CONTENT) for rounds that legitimately carry no discrete answer (an
            // INPUT_REQUIRED clarification streamed as llm_output while the model is mid-thought). Every
            // transport surfaces both from the same local events, so this is uniform across protocols and
            // needs no client at all — the transport-only `using(transport)` path runs with client == null.
            // REST has no server-side TASK concept and no valid taskId, so a getTask would 500 there;
            // reading locally sidesteps that entirely.
            String answerText = exchange.answerText();
            String generatedText = exchange.generatedText();

            // Run custom answer / generated-text assertions
            if (round.answerAsserter != null) {
                round.answerAsserter.accept(answerText);
            }
            if (round.generatedAsserter != null) {
                round.generatedAsserter.accept(generatedText);
            }

            if (round.eventsAsserter != null) {
                round.eventsAsserter.accept(eventCount);
            }

            // Run generic assertion with full round context (including event stream)
            if (round.genericAsserter != null) {
                RoundContext ctx = new RoundContext(taskId, observedState, eventCount,
                        answerText, generatedText, events, contextId);
                round.genericAsserter.accept(ctx);
            }

            return new RoundResult(round.inputText, taskId, observedState, eventCount, answerText,
                    generatedText, events, contextId, elapsedMs(sendNanos, settledNanos));
        } finally {
            // Wire log: record this round's request + the events that actually arrived — ALWAYS, including
            // when the round failed the await or an asserter above. Logging inside send would race A2A's
            // async delivery (send returns before events arrive on a callback thread); the await is the
            // drain point, so by here exchange.events() is the complete ordered response (REST blocked in
            // send; A2A has now drained). Moved into finally precisely so a FAILING round is logged too.
            if (wireLogger != null && wireLogger.enabled()) {
                long settled = settledNanos != 0L ? settledNanos : System.nanoTime();
                WireLogger.WireTiming timing = new WireLogger.WireTiming(
                        sentEpochMillis, Math.max(0L, (settled - sendNanos) / 1_000_000L));
                wireLogger.logRound(resolvedProtocol().name(), roundNumber,
                        sessionIdOf(outbound), outbound, exchange.events(), wireRequest, timing);
            }
        }
    }

    /** Elapsed milliseconds since {@code sendNanos}, floored at 0 (never negative on clock skew). */
    private static long elapsedMs(long sendNanos) {
        return Math.max(0L, (System.nanoTime() - sendNanos) / 1_000_000L);
    }

    /** Elapsed milliseconds between two nanoTime samples, floored at 0. */
    private static long elapsedMs(long sendNanos, long settledNanos) {
        return Math.max(0L, (settledNanos - sendNanos) / 1_000_000L);
    }

    /** Concise identity for failure messages: which round of how many, plus protocol + session. */
    private String roundLabel(int roundNumber, OutboundMessage outbound) {
        return "round " + roundNumber + "/" + rounds.size()
                + " [protocol=" + resolvedProtocol()
                + ", sessionId=" + sessionIdOf(outbound) + "]";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Flow identity for wire-log file naming: JUnit invocation label > contextId > "nosession". */
    private static String sessionIdOf(OutboundMessage m) {
        return SessionLabels.resolveLogName(m.contextId());
    }

    /**
     * Wire endpoint for the paste-ready request — the same target {@code transportFor} sends to: A2A →
     * the agent card URL (falling back to the base URL when the card advertises none); REST →
     * {@code <base>/v1/query} for {@code REST_QUERY}/{@code REST_QUERY_SYNC},
     * {@code <base>/v1/query/reactive} for {@code REST_REACTIVE}/{@code REST_REACTIVE_SYNC}
     * (bare, as {@code transportFor} builds it for the flow path). Resolved only
     * when a client is present (the {@code using(transport)} unit-test seam has none → caller passes null).
     */
    private static String endpointFor(MessageProtocol protocol, A2aServiceClient client,
                                      String conversationId) {
        return switch (protocol) {
            case A2A_STREAM, A2A_SYNC -> {
                String url = client.getAgentCard() == null ? null : client.getAgentCard().url();
                yield (url == null || url.isBlank()) ? client.getBaseUrl() : url;
            }
            case REST_QUERY, REST_QUERY_SYNC -> client.getBaseUrl() + "/v1/query";
            case REST_REACTIVE, REST_REACTIVE_SYNC -> client.getBaseUrl() + "/v1/query/reactive";
            // Same identity-bearing endpoint transportFor builds for these wires.
            case REST_VERSATILE, REST_GATEWAY -> GatewayIdentity.loadDefault()
                    .conversationEndpoint(client.getBaseUrl(), conversationId).toString();
            default -> null;
        };
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
        private Consumer<String> answerAsserter;
        private Consumer<String> generatedAsserter;
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

        /**
         * Assert on the round's answer text — the discrete final result, read strictly from
         * {@link InboundEvent.Kind#ANSWER} events in the local stream (never an A2A {@code getTask}
         * snapshot). Use this for rounds that <em>complete</em> ({@code COMPLETED}) and so should emit
         * a discrete answer. For rounds that legitimately carry no discrete answer — e.g. an
         * {@code INPUT_REQUIRED} clarification streamed as {@code llm_output} while the model is
         * mid-thought — use {@link #assertGenerated(Consumer)} instead, or {@code answerText} will be
         * blank by design. Protocol-neutral and client-free (works on the
         * {@link InteractionFlow#using(MessageTransport)} path).
         */
        public RoundDefinition assertAnswer(Consumer<String> asserter) {
            this.answerAsserter = asserter;
            return this;
        }

        /**
         * Assert on the round's <em>generated</em> text — the ordered concatenation of everything the
         * model produced ({@code ANSWER}/{@code LLM_OUTPUT}/{@code LLM_REASONING}/{@code CONTENT}). This
         * is the right hook for a round whose reply is not a discrete {@code ANSWER}: an
         * {@code INPUT_REQUIRED} round where the agent asks a clarifying question (streamed output, maybe
         * preceded by reasoning) has no final processed result, but it was not silent. Protocol-neutral
         * and client-free. See {@link InboundExchange#generatedText()}.
         */
        public RoundDefinition assertGenerated(Consumer<String> asserter) {
            this.generatedAsserter = asserter;
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
     * <p>Includes the round's {@link #answerText()} (the discrete final result, strict), its
     * {@link #generatedText()} (all model-generated text — the superset, for rounds with no discrete
     * answer), and the full event-stream snapshot for detailed inspection.</p>
     */
    public record RoundContext(
            String taskId,
            TaskState taskState,
            int eventCount,
            String answerText,
            String generatedText,
            List<InboundEvent> events,
            String contextId
    ) {}

    /**
     * Result of a single round within the flow.
     *
     * <p>Contains a snapshot of all events received during this round,
     * captured by {@link InboundExchange#events()}.</p>
     */
    public record RoundResult(
            String inputText,
            String taskId,
            TaskState taskState,
            int eventCount,
            String answerText,
            String generatedText,
            List<InboundEvent> events,
            String contextId,
            long durationMs
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
