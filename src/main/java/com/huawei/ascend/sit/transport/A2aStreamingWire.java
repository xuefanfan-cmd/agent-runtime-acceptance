package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
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

    /**
     * The SDK subscribe action the wire drives — {@code A2aServiceClient::subscribeTask} in production
     * (a streaming=true SDK Client → SSE {@code SubscribeToTask}). Same {@code ClientEvent} sink shape
     * as {@link MessageSender}; the only difference is the input is a {@code taskId}, not a {@link Message}.
     */
    @FunctionalInterface
    public interface SubscribeSender {
        void subscribe(String taskId,
                       List<BiConsumer<ClientEvent, AgentCard>> consumers,
                       Consumer<Throwable> errorHandler);
    }

    private final MessageSender sender;
    private final SubscribeSender subscribeSender;

    public A2aStreamingWire(MessageSender sender) {
        this.sender = sender;
        this.subscribeSender = null;
    }

    /** Subscribe-only construction — {@code A2A_SUBSCRIBE} binds {@code A2aServiceClient::subscribeTask} here. */
    public A2aStreamingWire(SubscribeSender subscribeSender) {
        this.sender = null;
        this.subscribeSender = subscribeSender;
    }

    /**
     * Build the A2A user {@link Message}: start from a user-text message, then set
     * {@code taskId}/{@code contextId} only when present and non-blank. Mirrors the pre-refactor
     * {@code InteractionFlow.executeRound} builder exactly, so continuation behavior is unchanged.
     * Equivalent to {@link #buildMessage(String, Map, String, String)} with no part metadata.
     */
    public static Message buildMessage(String text, String taskId, String contextId) {
        return buildMessage(text, null, taskId, contextId);
    }

    /**
     * Build the A2A user {@link Message} with optional part-level {@code metadata} stamped onto
     * {@code parts[0]} — the parallel-resume routing channel: the adapter sets
     * {@code parts[0].metadata.toolCallId} so the runtime's {@code RemoteInvocationBatchCoordinator} routes
     * the round's input to a specific child member. When {@code partMetadata} is null/empty, this is
     * byte-identical to {@link A2A#toUserMessage} — a bare {@link TextPart} with role=USER and an
     * auto-generated messageId. When present, the {@link TextPart} is built directly carrying that
     * metadata, with the same role=USER + auto messageId as {@code toUserMessage}.
     * {@code taskId}/{@code contextId} are set only when present and non-blank.
     */
    public static Message buildMessage(String text, Map<String, Object> partMetadata,
                                       String taskId, String contextId) {
        TextPart part = (partMetadata == null || partMetadata.isEmpty())
                ? new TextPart(text)
                : new TextPart(text, partMetadata);
        Message.Builder builder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(List.of(part));
        if (taskId != null && !taskId.isBlank()) {
            builder.taskId(taskId);
        }
        if (contextId != null && !contextId.isBlank()) {
            builder.contextId(contextId);
        }
        return builder.build();
    }

    /**
     * Build the A2A user {@link Message} for a multi-part (batch) resume — one {@link TextPart} per
     * {@link OutboundPart}, each carrying its own {@code metadata.toolCallId} so the runtime's
     * {@code RemoteInvocationBatchCoordinator.resumeWaitingBatch} routes that part's input to the matching
     * child. {@code taskId}/{@code contextId} set only when non-blank.
     */
    public static Message buildMessage(List<OutboundPart> parts, String taskId, String contextId) {
        List<Part<?>> textParts = new ArrayList<>();
        for (OutboundPart p : parts) {
            textParts.add(new TextPart(p.text(), p.metadata() == null ? Map.of() : p.metadata()));
        }
        Message.Builder builder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(textParts);
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

    /**
     * Drive the A2A subscribe: feed every streamed task event into the neutral {@code sink}, and log
     * any stream error. The single sink is wrapped into a one-element consumer list to match
     * {@link SubscribeSender} — exactly mirroring {@link #send}.
     *
     * @param taskId the existing, non-terminal task id to observe
     * @param sink   the neutral event sink
     */
    public void subscribe(String taskId, BiConsumer<ClientEvent, AgentCard> sink) {
        Consumer<Throwable> errorHandler = error ->
                LOG.warning("A2A subscribe stream error: " + error.getMessage());
        subscribeSender.subscribe(taskId, List.of(sink), errorHandler);
    }
}
