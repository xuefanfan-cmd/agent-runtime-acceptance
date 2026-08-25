package com.huawei.ascend.sit.fixtures.reconnect;

import com.huawei.ascend.sit.cases.integration.agent_bus.AgentBusExternalFixture;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fault.FaultLink;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.EndpointType;

import java.util.Set;
import java.util.UUID;

/** Managed DeepAgent topology used by the gateway reconnect E2E. */
public final class DeepAgentReconnectFixture implements AutoCloseable {
    private static final String DEEP_RESEARCH = "deep-research-auto";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final String REGISTRY = "registry-center";
    private static final String GATEWAY = "gateway-direct";
    private static final String AGENT_ID = "deep-research";

    private final SutStack stack;
    private final BackingServices services;

    private DeepAgentReconnectFixture(SutStack stack, BackingServices services) {
        this.stack = stack;
        this.services = services;
    }

    public static boolean hasLlmCredentials() {
        String apiKey = System.getenv("LLM_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }

    public static DeepAgentReconnectFixture gateway() throws Exception {
        TestConfig config = TestConfig.load();
        BackingServices services = new BackingServices(
                config, Set.of("postgres"), new TestContainerFactory(null));
        SutStack stack = null;
        try {
            stack = SutStack.builder(config).backingServices(services)
                    .streaming(true)
                    .agent(SEARCH, search -> search.env("SEARCH_AGENT_USE_STUB", "true"))
                    .agent(VERIFY)
                    .agent(DEEP_RESEARCH, deep -> deep
                            .downstreams(SEARCH, VERIFY)
                            .profile("parallel-search"))
                    .agent(REGISTRY)
                    .agent(GATEWAY, gateway -> gateway
                            .downstream(REGISTRY, "gateway.rdc.base-url")
                            .cardEndpointRedirect("gateway.public-base-url"))
                    .start();
            AgentBusExternalFixture rdc = AgentBusExternalFixture.forEndpoints(
                    stack.baseUrl(REGISTRY), stack.baseUrl(GATEWAY), null);
            rdc.registerRuntime(AGENT_ID, "deep-research-" + shortId(), stack.baseUrl(DEEP_RESEARCH));
            return new DeepAgentReconnectFixture(stack, services);
        } catch (Exception error) {
            if (stack != null) {
                stack.close();
            }
            services.close();
            throw error;
        }
    }

    public AgentClient client() {
        return AgentClients.builder()
                .endpointType(EndpointType.GATEWAY)
                .endpointUrl(faultLink().listenUrl())
                .credentialProvider(conversationId -> AgentBusExternalFixture.TOKEN)
                .build();
    }

    public FaultLink faultLink() {
        return stack.faultLink(GATEWAY);
    }

    public static String agentId() {
        return AGENT_ID;
    }

    @Override
    public void close() {
        stack.close();
        services.close();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
