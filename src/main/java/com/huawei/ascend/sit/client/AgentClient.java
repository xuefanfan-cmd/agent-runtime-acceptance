package com.huawei.ascend.sit.client;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.utils.WaitUtils;
import org.a2aproject.sdk.spec.Task;

/**
 * High-level client for triggering specific Agent scenarios and querying state.
 *
 * <p>Wraps {@link A2aServiceClient} with Agent-specific convenience methods
 * built on the A2A Java SDK. Each method encapsulates the knowledge of how
 * to send messages and poll for results for a given agent type.</p>
 */
public class AgentClient {

    private final A2aServiceClient a2aClient;

    public AgentClient(A2aServiceClient a2aClient) {
        this.a2aClient = a2aClient;
    }

    public AgentClient(TestConfig config) {
        this(new A2aServiceClient(config));
    }

    // ===== Weather Agent =====

    /**
     * Trigger the Weather Agent with a natural language query.
     *
     * @param query the user's natural language weather query (e.g. "北京明天天气怎么样")
     * @return task ID for tracking the run
     */
    public String triggerWeatherAgent(String query) {
        return a2aClient.sendMessage(query);
    }

    /**
     * Trigger the Weather Agent and wait until the task reaches a terminal state.
     *
     * @param query            the user's natural language weather query
     * @param timeoutSeconds   maximum wait time in seconds
     * @param pollIntervalMs   polling interval in milliseconds
     * @return the terminal Task
     */
    public Task triggerWeatherAgentAndWait(String query, int timeoutSeconds, long pollIntervalMs) {
        String taskId = triggerWeatherAgent(query);
        return WaitUtils.pollUntilTerminal(
                () -> a2aClient.getTask(taskId),
                timeoutSeconds,
                pollIntervalMs);
    }

    // ===== Generic Agent =====

    /**
     * Trigger an arbitrary agent by sending a text message.
     *
     * @param text the message text
     * @return task ID for tracking the run
     */
    public String triggerAgent(String text) {
        return a2aClient.sendMessage(text);
    }

    /**
     * Get the underlying A2A service client for direct protocol access.
     */
    public A2aServiceClient getA2aClient() {
        return a2aClient;
    }
}
