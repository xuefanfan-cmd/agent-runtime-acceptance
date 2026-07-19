package com.huawei.ascend.sit.client;

import com.huawei.ascend.sit.config.TestConfig;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * API client encapsulating all A2A protocol interactions via the official A2A Java SDK.
 *
 * <p>This is the core infrastructure layer. All A2A protocol calls should go through
 * this client, built on top of {@link Client} from the SDK.</p>
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code getAgentCard()} — resolve agent card (health / capability discovery)</li>
 *   <li>{@code sendMessage(text)} — send a user message and capture the task ID</li>
 *   <li>{@code sendMessage(message, consumers, errorHandler)} — send with custom event handling</li>
 *   <li>{@code getTask(taskId)} — retrieve current task state</li>
 *   <li>{@code cancelTask(taskId)} — request task cancellation</li>
 * </ul>
 */
public class A2aServiceClient {

    private static final Logger LOG = Logger.getLogger(A2aServiceClient.class.getName());

    private final Client a2aClient;
    private final String baseUrl;
    private final AgentCard agentCard;

    // Protocol-specific SDK clients, built lazily and cached per wire mode. The A2A SDK bakes the
    // wire mode (message/stream SSE vs message/send blocking) into a Client at build time via
    // ClientConfig.setStreaming — it exposes no public sendStreamingMessage, and its sendMessage
    // branches internally on config + card capabilities. So serving BOTH A2A_STREAM and A2A_SYNC
    // from one service client requires two underlying SDK Clients. Built flags track built-ness
    // independently of the cached value so a null-returning test override still caches exactly once.
    private Client streamingClient;
    private Client syncClient;
    private boolean streamingBuilt;
    private boolean syncBuilt;

    public A2aServiceClient(TestConfig config) {
        this.baseUrl = config.getBaseUrl();

        // Resolve the agent card via A2A.getAgentCard()
        this.agentCard = A2A.getAgentCard(baseUrl);

        // Legacy default client: streaming=false ⇒ synchronous message/send (the call blocks until
        // the server returns the terminal task). Used by the no-suffix sendMessage(...) overloads;
        // the protocol-specific sendMessageStreaming/sendMessageSync build their own clients.
        ClientConfig clientConfig = new ClientConfig.Builder()
                .setAcceptedOutputModes(List.of("text"))
                .setStreaming(false)
                .build();

        this.a2aClient = Client.builder(agentCard)
                .clientConfig(clientConfig)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                .build();
    }

    /**
     * Constructor for pre-built A2A Client (useful for testing with custom transport).
     */
    public A2aServiceClient(String baseUrl, Client a2aClient, AgentCard agentCard) {
        this.baseUrl = baseUrl;
        this.a2aClient = a2aClient;
        this.agentCard = agentCard;
    }

    // ===== Agent Card (Health / Discovery) =====

    /**
     * Get the cached agent card (resolved at construction time).
     */
    public AgentCard getAgentCard() {
        return agentCard;
    }

    /**
     * Re-resolve the agent card from the SUT — serves as a health check.
     */
    public AgentCard refreshAgentCard() {
        return A2A.getAgentCard(baseUrl);
    }

    // ===== Send Message =====

    /**
     * Send a text message to the A2A server agent and capture the resulting task ID.
     *
     * <p>Blocks until the first {@link TaskEvent} is received containing the task ID.</p>
     *
     * @param text the user's natural language message
     * @return the task ID assigned by the server
     */
    public String sendMessage(String text) {
        AtomicReference<String> taskIdRef = new AtomicReference<>();
        CountDownLatch taskLatch = new CountDownLatch(1);

        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(
                (event, card) -> {
                    if (event instanceof TaskEvent taskEvent && taskIdRef.get() == null) {
                        taskIdRef.set(taskEvent.getTask().id());
                        taskLatch.countDown();
                    }
                }
        );

        Consumer<Throwable> errorHandler = error -> taskLatch.countDown();

        Message message = A2A.toUserMessage(text);
        a2aClient.sendMessage(message, consumers, errorHandler);

        try {
            taskLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for task ID", e);
        }

        String taskId = taskIdRef.get();
        if (taskId == null) {
            throw new AssertionError("sendMessage did not produce a task ID within timeout");
        }
        return taskId;
    }

    /**
     * Send a text message with metadata and capture the resulting task ID.
     */
    public String sendMessage(String text, Map<String, Object> metadata) {
        // Same as plain sendMessage for now; metadata support via ClientCallContext in future
        return sendMessage(text);
    }

    /**
     * Send a message with custom event consumers and error handler.
     *
     * <p>Use this for streaming responses or when you need to collect
     * all events during task execution.</p>
     */
    public void sendMessage(Message message,
                            List<BiConsumer<ClientEvent, AgentCard>> consumers,
                            Consumer<Throwable> errorHandler) {
        sendMessage(message, null, consumers, errorHandler);
    }

    /**
     * Send a message with per-request metadata, custom event consumers and error handler.
     *
     * <p>Uses {@link MessageSendParams} to carry metadata alongside the message.
     * Metadata is merged with any global metadata from {@link ClientConfig}.</p>
     *
     * @param message     the A2A message to send
     * @param metadata    per-request metadata (may be null to use global config metadata only)
     * @param consumers   event consumers for streaming responses
     * @param errorHandler error handler for stream failures
     */
    public void sendMessage(Message message,
                            Map<String, Object> metadata,
                            List<BiConsumer<ClientEvent, AgentCard>> consumers,
                            Consumer<Throwable> errorHandler) {
        sendWith(a2aClient, message, metadata, consumers, errorHandler);
    }

    /**
     * Send over the A2A-streaming wire: a streaming=true SDK Client, which dispatches to the SDK's
     * internal {@code sendStreamingMessage} (SSE / {@code message/stream}) when the agent card also
     * advertises streaming. For {@link com.huawei.ascend.sit.client.InteractionFlow} protocol
     * {@code A2A_STREAM}.
     */
    public void sendMessageStreaming(Message message,
                                     Map<String, Object> metadata,
                                     List<BiConsumer<ClientEvent, AgentCard>> consumers,
                                     Consumer<Throwable> errorHandler) {
        sendWith(sdkClient(true), message, metadata, consumers, errorHandler);
    }

    /**
     * Send over the A2A-sync wire: a streaming=false SDK Client, which dispatches to the SDK's
     * blocking {@code message/send} (the call returns the terminal task). For
     * {@link com.huawei.ascend.sit.client.InteractionFlow} protocol {@code A2A_SYNC}.
     */
    public void sendMessageSync(Message message,
                                Map<String, Object> metadata,
                                List<BiConsumer<ClientEvent, AgentCard>> consumers,
                                Consumer<Throwable> errorHandler) {
        sendWith(sdkClient(false), message, metadata, consumers, errorHandler);
    }

    private void sendWith(Client client,
                          Message message,
                          Map<String, Object> metadata,
                          List<BiConsumer<ClientEvent, AgentCard>> consumers,
                          Consumer<Throwable> errorHandler) {
        MessageSendParams params = MessageSendParams.builder()
                .message(message)
                .metadata(metadata)
                .build();
        client.sendMessage(params, consumers, errorHandler, null);
    }

    /**
     * The cached SDK Client for the requested wire mode, built on first use. Package-private: the
     * transports bind {@code sendMessageStreaming}/{@code sendMessageSync} to it, and tests observe
     * the streaming flag by overriding {@link #buildSdkClient(boolean)}.
     */
    Client sdkClient(boolean streaming) {
        if (streaming) {
            if (!streamingBuilt) {
                streamingClient = buildSdkClient(true);
                streamingBuilt = true;
            }
            return streamingClient;
        }
        if (!syncBuilt) {
            syncClient = buildSdkClient(false);
            syncBuilt = true;
        }
        return syncClient;
    }

    /**
     * Build a fresh SDK {@link Client} for the given wire mode. Protected so tests can observe the
     * streaming flag without standing up a server. Warns when building a streaming client against a
     * card that does not advertise streaming — the SDK would otherwise silently fall back to
     * message/send, making A2A_STREAM wire-identical to A2A_SYNC (the very failure this dual-path
     * design exists to prevent).
     */
    protected Client buildSdkClient(boolean streaming) {
        if (streaming && !cardAdvertisesStreaming()) {
            LOG.warning("Building a streaming A2A client for agent '"
                    + (agentCard == null ? "?" : agentCard.name())
                    + "' but its capabilities do not advertise streaming=true; the SDK will fall back"
                    + " to message/send on the wire — A2A_STREAM will not actually stream.");
        }
        ClientConfig clientConfig = new ClientConfig.Builder()
                .setAcceptedOutputModes(List.of("text"))
                .setStreaming(streaming)
                .build();
        return Client.builder(agentCard)
                .clientConfig(clientConfig)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                .build();
    }

    private boolean cardAdvertisesStreaming() {
        return agentCard != null
                && agentCard.capabilities() != null
                && agentCard.capabilities().streaming();
    }

    // ===== Get Task =====

    /**
     * Retrieve the current state of a task by its ID.
     */
    public Task getTask(String taskId) {
        return a2aClient.getTask(new TaskQueryParams(taskId));
    }

    /**
     * Retrieve a task with history length limit.
     */
    public Task getTask(String taskId, int historyLength) {
        return a2aClient.getTask(new TaskQueryParams(taskId, Integer.valueOf(historyLength)));
    }

    // ===== Cancel Task =====

    /**
     * Cancel an ongoing task.
     */
    public Task cancelTask(String taskId) {
        return a2aClient.cancelTask(new CancelTaskParams(taskId));
    }

    /**
     * Cancel a task with additional metadata (e.g. reason).
     */
    public Task cancelTask(String taskId, Map<String, Object> metadata) {
        return a2aClient.cancelTask(CancelTaskParams.builder()
                .id(taskId)
                .metadata(metadata)
                .build());
    }

    // ===== Convenience accessors =====

    /** Get the configured base URL. */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** Get the underlying A2A SDK Client (for advanced use cases). */
    public Client getA2aClient() {
        return a2aClient;
    }
}
