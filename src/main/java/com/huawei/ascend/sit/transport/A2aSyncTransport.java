package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.spec.Message;

/**
 * The A2A-sync {@code MessageTransport}: a degenerate-terminal adapter over the sync-configured
 * A2A client. It drives the same {@link A2aStreamingWire} send, and when the terminal task lands it
 * surfaces that task's answer as classified events ({@link A2aEventMapping#contentEventsOf(Task)} —
 * typed envelopes become {@code ANSWER}/{@code LLM_OUTPUT}/…, plain text becomes {@code CONTENT})
 * followed by the terminal STATE, all in an {@link InboundExchange} (no intermediate trajectory).
 * Surfacing the answer this way keeps the neutral model uniform across protocols —
 * {@code exchange.answerText()} then carries the answer for sync just as streaming/REST do, with no
 * {@code getTask} round-trip. The sync vs streaming wire distinction is a client-level SDK knob
 * (spec §10 follow-up); this adapter reuses the existing (sync-configured) client.
 */
public final class A2aSyncTransport implements MessageTransport {

    /** Mirrors {@code A2aServiceClient.sendMessage(Message, Map, List, Consumer)}. */
    @FunctionalInterface
    public interface SyncSender {
        void send(Message message, java.util.Map<String, Object> metadata,
                  java.util.List<java.util.function.BiConsumer<
                          org.a2aproject.sdk.client.ClientEvent, org.a2aproject.sdk.spec.AgentCard>> consumers,
                  java.util.function.Consumer<Throwable> errorHandler);
    }

    private final SyncSender sender;

    public A2aSyncTransport(SyncSender sender) {
        this.sender = sender;
    }

    @Override
    public InboundExchange send(OutboundMessage message) {
        InboundExchange exchange = new InboundExchange();
        Message sdkMessage = A2aStreamingWire.buildMessage(
                message.text(), message.taskId(), message.contextId());
        // A settled state — final (COMPLETED/FAILED/...) or INPUT_REQUIRED — means the round's reply is
        // ready. The SDK may re-deliver the cumulative snapshot on several consecutive callbacks, so
        // surface the reply at most ONCE (compareAndSet): both to avoid re-emitting the whole artifact
        // set per callback and to bound the wire log. INPUT_REQUIRED is included (even though the SDK's
        // isFinal() excludes it) so an INPUT_REQUIRED sync round surfaces its status.message reply the
        // same way a COMPLETED round surfaces its artifacts.
        java.util.concurrent.atomic.AtomicBoolean surfaced = new java.util.concurrent.atomic.AtomicBoolean();
        sender.send(sdkMessage, message.metadata(),
                java.util.List.of((clientEvent, card) -> {
                    java.util.List<InboundEvent> es = A2aEventMapping.toEventList(clientEvent);
                    boolean settled = es.stream().anyMatch(A2aSyncTransport::isSettledState);
                    if (settled && surfaced.compareAndSet(false, true)) {
                        // Fallback for agents whose message/send returns only a terminal task with no
                        // streamed chunks: surface that task's answer as classified events before the
                        // STATE, so exchange.answerText() carries it uniformly across protocols without
                        // an A2A getTask call (which REST cannot satisfy — no server-side TASK concept).
                        // For SUTs that DO stream the answer as intermediate artifact updates this is
                        // redundant — hence the once-guard (compareAndSet), which also bounds the wire
                        // log: without it the cumulative Task's parts were re-emitted per callback.
                        org.a2aproject.sdk.spec.Task t = A2aEventMapping.taskOf(clientEvent);
                        if (t != null) {
                            for (InboundEvent ce : A2aEventMapping.contentEventsOf(t)) {
                                exchange.add(ce);
                            }
                        }
                    }
                    es.forEach(exchange::add);
                }),
                err -> {});
        exchange.markStreamEnd();
        return exchange;
    }

    /**
     * A sync round has settled — its reply is ready to surface — when the task reaches a state that
     * won't auto-progress: any {@link org.a2aproject.sdk.spec.TaskState#isFinal() final} state, or
     * {@link org.a2aproject.sdk.spec.TaskState#TASK_STATE_INPUT_REQUIRED} (paused for user input, whose
     * clarifying reply lives in {@code status.message}). INPUT_REQUIRED is included even though the
     * SDK's {@code isFinal()} excludes it, so an INPUT_REQUIRED sync round surfaces its reply the same
     * way a COMPLETED round does.
     */
    private static boolean isSettledState(InboundEvent e) {
        return e.kind() == InboundEvent.Kind.STATE
                && e.state() != null
                && (e.state().isFinal()
                        || e.state() == org.a2aproject.sdk.spec.TaskState.TASK_STATE_INPUT_REQUIRED);
    }
}
