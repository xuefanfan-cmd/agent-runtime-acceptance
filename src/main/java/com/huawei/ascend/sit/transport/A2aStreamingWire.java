package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The shared, family-agnostic A2A wire. It owns the two genuinely-invariant pieces of an A2A send:
 * <ol>
 *   <li>{@link #buildMessage(String, String, String)} — the A2A user-message shape
 *       ({@link A2A#toUserMessage} + optional continuation {@code taskId}/{@code contextId});</li>
 *   <li>{@link #send(Message, Map, BiConsumer)} — driving the SDK send with a <em>neutral</em>
 *       {@code BiConsumer<ClientEvent, AgentCard>} sink, and logging stream errors.</li>
 * </ol>
 *
 * <p>It never references a family type, so both families can reuse it: the Interaction adapter
 * ({@code A2aStreamingTransport}) now; a future Conversation-over-A2A adapter later. The SDK send
 * goes through an injectable {@link MessageSender} seam so the full path is unit-testable without a
 * real SDK {@code Client}; {@code A2aServiceClient::sendMessageStreaming} binds to it in production
 * (a streaming=true SDK Client → SSE message/stream).
 */
public final class A2aStreamingWire {

    private static final Logger LOG = Logger.getLogger(A2aStreamingWire.class.getName());

    /** The SDK send action the wire drives — {@code A2aServiceClient::sendMessageStreaming} in production. */
    @FunctionalInterface
    public interface MessageSender {
        void send(Message message, Map<String, Object> metadata,
                  List<BiConsumer<ClientEvent, AgentCard>> consumers,
                  Consumer<Throwable> errorHandler);
    }

    private final MessageSender sender;

    public A2aStreamingWire(MessageSender sender) {
        this.sender = sender;
    }

    /**
     * Build the A2A user {@link Message}: start from a user-text message, then set
     * {@code taskId}/{@code contextId} only when present and non-blank. Mirrors the pre-refactor
     * {@code InteractionFlow.executeRound} builder exactly, so continuation behavior is unchanged.
     */
    public static Message buildMessage(String text, String taskId, String contextId) {
        Message.Builder builder = Message.builder(A2A.toUserMessage(text));
        if (taskId != null && !taskId.isBlank()) {
            builder.taskId(taskId);
        }
        if (contextId != null && !contextId.isBlank()) {
            builder.contextId(contextId);
        }
        return builder.build();
    }

    /**
     * Drive the A2A send: feed every event into the neutral {@code sink}, and log any stream error.
     * The single sink is wrapped into a one-element consumer list to match {@link MessageSender}.
     *
     * @param message  the SDK {@link Message} (build with {@link #buildMessage})
     * @param metadata per-request A2A metadata (may be null)
     * @param sink     the neutral event sink (e.g. {@code A2aEventCollector.createConsumer()})
     */
    public void send(Message message, Map<String, Object> metadata,
                     BiConsumer<ClientEvent, AgentCard> sink) {
        Consumer<Throwable> errorHandler = error ->
                LOG.warning("A2A stream error: " + error.getMessage());
        sender.send(message, metadata, List.of(sink), errorHandler);
    }
}
