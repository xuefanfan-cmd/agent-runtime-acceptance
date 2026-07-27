package com.huawei.ascend.sit.transport;

import java.util.List;

/**
 * Sink for one interaction round's wire exchange — the request {@link OutboundMessage} and the
 * response {@link InboundEvent} list (decoded + their raw frames in {@link InboundEvent#raw()}).
 * Implemented by {@link FileWireLogger} (one file per round under a per-run directory); the flow
 * calls {@link #logRound} from {@code InteractionFlow.executeRound} when {@link #enabled()}.
 *
 * <p>The call happens <em>after</em> the round's exchange has settled (the await that drains the
 * inbound stream). Logging inside {@code MessageTransport.send} would race A2A's asynchronous
 * delivery — both {@code A2A_STREAM} and {@code A2A_SYNC} return from {@code send} before any
 * events arrive (delivered on a callback thread), so the response list would still be empty there.
 * REST blocks inside {@code send} and would be fine, but the single post-await call site covers all
 * four modes uniformly.
 *
 * <p>Pure transport — no config dependency. The config-aware factory lives in the client layer
 * ({@code WireLoggerResolver}), so this interface and {@link FileWireLogger} stay free of
 * {@code TestConfig}.
 */
public interface WireLogger {

    /** True when logging is active (the flow should call {@link #logRound} each round). */
    boolean enabled();

    /**
     * Persist one round's request + response. Called by {@code InteractionFlow.executeRound} after
     * the round's exchange is fully populated. Best-effort: an implementation must swallow IO
     * failures so logging never breaks a test.
     *
     * @param protocol  the wire-protocol tag (e.g. {@code A2A_STREAM}, {@code REST_QUERY_SYNC})
     * @param round     1-based round index within the flow
     * @param sessionId the flow/session tag (the JUnit invocation label from {@link SessionLabels},
     *                  set by the test-side extension), used in the filename; falls back to
     *                  contextId / {@code "nosession"}. Never read from message metadata.
     * @param request   the outbound message (the request content)
     * @param response  the full inbound event list (decoded text + raw frames)
     */
    void logRound(String protocol, int round, String sessionId,
                  OutboundMessage request, List<InboundEvent> response);

    /**
     * Variant carrying a paste-ready wire request (HTTP request-line + headers + body, produced by
     * {@link WireRequestRenderer}) for manual replay. The default delegates to the 5-arg form, ignoring
     * {@code wireRequest}; {@link FileWireLogger} overrides it to render a separate copyable block.
     * Callers should prefer this overload.
     *
     * @param wireRequest the rendered wire request, or {@code null} when not available
     */
    default void logRound(String protocol, int round, String sessionId,
                          OutboundMessage request, List<InboundEvent> response, String wireRequest) {
        logRound(protocol, round, sessionId, request, response);
    }

    /**
     * Per-round timing captured at the flow layer (not the transport). {@code sentEpochMillis} is the
     * wall-clock instant the request was handed to the transport; {@code durationMillis} is the elapsed
     * time until the round's exchange settled (the await returned or threw). Rendered in the wire-log
     * header so a fast-wrong-answer is distinguishable from a hang/timeout. {@code null} from callers
     * that do not track timing — the default {@link #logRound} overload below ignores it.
     */
    record WireTiming(long sentEpochMillis, long durationMillis) {}

    /**
     * Variant also carrying per-round {@link WireTiming}. The default delegates to the 6-arg form,
     * ignoring {@code timing}; {@link FileWireLogger} overrides it to render a {@code sent/duration}
     * header line. Callers that track timing (e.g. {@code InteractionFlow.executeRound}) should prefer
     * this overload.
     *
     * @param wireRequest the rendered wire request, or {@code null} when not available
     * @param timing      per-round send instant + settle duration, or {@code null} when not tracked
     */
    default void logRound(String protocol, int round, String sessionId,
                          OutboundMessage request, List<InboundEvent> response,
                          String wireRequest, WireTiming timing) {
        logRound(protocol, round, sessionId, request, response, wireRequest);
    }

    /** Disabled sink: {@link #enabled()} is false and {@link #logRound} is a no-op. */
    WireLogger NOOP = new WireLogger() {
        @Override public boolean enabled() { return false; }
        @Override public void logRound(String protocol, int round, String sessionId,
                                       OutboundMessage request, List<InboundEvent> response) { }
    };
}
