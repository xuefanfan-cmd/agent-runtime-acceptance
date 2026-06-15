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

    private final Client a2aClient;
    private final String baseUrl;
    private final AgentCard agentCard;

    public A2aServiceClient(TestConfig config) {
        this.baseUrl = config.getBaseUrl();

        // Resolve the agent card via A2A.getAgentCard()
        this.agentCard = A2A.getAgentCard(baseUrl);

        // Build the A2A client with JSON-RPC transport. streaming=false ⇒ synchronous
        // message/send: the call blocks until the server returns the terminal task.
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
        MessageSendParams params = MessageSendParams.builder()
                .message(message)
                .metadata(metadata)
                .build();
        a2aClient.sendMessage(params, consumers, errorHandler, null);
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
