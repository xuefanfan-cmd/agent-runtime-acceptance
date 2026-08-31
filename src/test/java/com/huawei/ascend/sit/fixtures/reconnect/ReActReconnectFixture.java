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

/** Shared managed-SUT fixture for Runtime contracts and reconnect E2E journeys. */
public final class ReActReconnectFixture implements AutoCloseable {
    private static final String MAINPLAN = "mainplan";
    private static final String HOTEL = "hotel";
    private static final String TRIP = "trip";
    private static final String REGISTRY = "registry-center";
    private static final String GATEWAY = "gateway-direct";
    private static final String AGENT_ID = "travel-mainplan";

    private final SutStack stack;
    private final BackingServices services;
    private final EndpointType endpointType;

    private ReActReconnectFixture(SutStack stack, BackingServices services, EndpointType endpointType) {
        this.stack = stack;
        this.services = services;
        this.endpointType = endpointType;
    }

    public static boolean hasLlmCredentials() {
        String apiKey = System.getenv("LLM_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }

    public static ReActReconnectFixture runtimeDirect() {
        TestConfig config = TestConfig.load();
        SutStack stack = SutStack.builder(config)
                .agent(HOTEL, hotel -> applyLlm(hotel, HOTEL))
                .agent(TRIP, trip -> {
                    trip.downstream(HOTEL);
                    applyLlm(trip, TRIP);
                })
                .agent(MAINPLAN, mainplan -> {
                    mainplan.downstream(TRIP);
                    mainplan.cardEndpointRedirect("main-plan-agent.agent-card-endpoint");
                    applyLlm(mainplan, MAINPLAN);
                })
                .start();
        return new ReActReconnectFixture(stack, null, EndpointType.RUNTIME);
    }

    public static ReActReconnectFixture gateway() throws Exception {
        TestConfig config = TestConfig.load();
        BackingServices services = new BackingServices(
                config, Set.of("postgres"), new TestContainerFactory(null));
        SutStack stack = null;
        try {
            stack = SutStack.builder(config).backingServices(services)
                    .agent(HOTEL, hotel -> applyLlm(hotel, HOTEL))
                    .agent(TRIP, trip -> {
                        trip.downstream(HOTEL);
                        applyLlm(trip, TRIP);
                    })
                    .agent(MAINPLAN, mainplan -> {
                        mainplan.downstream(TRIP);
                        applyLlm(mainplan, MAINPLAN);
                    })
                    .agent(REGISTRY)
                    .agent(GATEWAY, gateway -> gateway
                            .downstream(REGISTRY, "gateway.rdc.base-url")
                            .cardEndpointRedirect("gateway.public-base-url"))
                    .start();
            AgentBusExternalFixture rdc = AgentBusExternalFixture.forEndpoints(
                    stack.baseUrl(REGISTRY), stack.baseUrl(GATEWAY), null);
            rdc.registerRuntime(AGENT_ID, "travel-mainplan-" + shortId(), stack.baseUrl(MAINPLAN));
            return new ReActReconnectFixture(stack, services, EndpointType.GATEWAY);
        } catch (Exception error) {
            if (stack != null) {
                stack.close();
            }
            services.close();
            throw error;
        }
    }

    public AgentClient client() {
        AgentClients.Builder builder = AgentClients.builder()
                .endpointType(endpointType)
                .endpointUrl(publicUrl());
        if (endpointType == EndpointType.GATEWAY) {
            builder.credentialProvider(conversationId -> AgentBusExternalFixture.TOKEN);
        }
        return builder.build();
    }

    public EndpointType endpointType() {
        return endpointType;
    }

    public String publicUrl() {
        return faultLink().listenUrl();
    }

    public FaultLink faultLink() {
        return stack.faultLink(endpointType == EndpointType.RUNTIME ? MAINPLAN : GATEWAY);
    }

    public static String agentId() {
        return AGENT_ID;
    }

    @Override
    public void close() {
        stack.close();
        if (services != null) {
            services.close();
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static void applyLlm(SutStack.AgentBuilder agent, String name) {
        String apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        putEnvIfPresent(agent, "LLM_API_KEY", apiKey);
        putEnvIfPresent(agent, "LLM_API_BASE", System.getenv("LLM_API_BASE"));
        putEnvIfPresent(agent, "LLM_MODEL", System.getenv("LLM_MODEL"));
        putEnvIfPresent(agent, "LLM_PROVIDER", System.getenv("LLM_PROVIDER"));
        putEnvIfPresent(agent, "LLM_SSL_VERIFY", System.getenv("LLM_SSL_VERIFY"));
        String property = switch (name) {
            case HOTEL -> "openjiuwen.travel.hotel.llm.api-key";
            case TRIP -> "openjiuwen.travel.trip.llm.api-key";
            case MAINPLAN -> "openjiuwen.travel.mainplan.llm.api-key";
            default -> "";
        };
        if (!property.isBlank()) {
            agent.property(property, apiKey);
        }
    }

    private static void putEnvIfPresent(SutStack.AgentBuilder agent, String name, String value) {
        if (value != null && !value.isBlank()) {
            agent.env(name, value);
        }
    }
}
