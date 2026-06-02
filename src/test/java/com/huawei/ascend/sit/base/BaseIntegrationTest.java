package com.huawei.ascend.sit.base;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.AgentClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.utils.WaitUtils;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for all integration-level and above tests.
 *
 * <p>Provides:
 * <ul>
 *   <li>Environment-aware configuration loading ({@link TestConfig})</li>
 *   <li>Initialized A2A SDK clients ({@link A2aServiceClient}, {@link AgentClient})</li>
 *   <li>Global setup/teardown lifecycle hooks</li>
 * </ul>
 *
 * <p>Subclasses should extend this or one of its specialized children
 * ({@link BaseComponentTest}, {@link BaseE2ETest}).</p>
 */
public abstract class BaseIntegrationTest {

    protected static TestConfig config;
    protected static A2aServiceClient a2aClient;
    protected static AgentClient agentClient;

    @BeforeAll
    static void initBaseInfrastructure() {
        TestEnvironment env = TestEnvironment.current();
        config = TestConfig.load(env);

        a2aClient = new A2aServiceClient(config);
        agentClient = new AgentClient(a2aClient);
    }

    /**
     * Wait for SUT to be healthy before running tests.
     * Resolves the agent card to verify the SUT is reachable.
     */
    protected static void awaitSutHealthy() {
        WaitUtils.awaitHealthy(() -> {
            try {
                var card = a2aClient.getAgentCard();
                return card != null && card.name() != null;
            } catch (Exception e) {
                return false;
            }
        }, 60);
    }

    // Convenience accessors for subclasses
    protected static TestConfig getConfig() { return config; }
    protected static A2aServiceClient getA2aClient() { return a2aClient; }
    protected static AgentClient getAgentClient() { return agentClient; }
}
