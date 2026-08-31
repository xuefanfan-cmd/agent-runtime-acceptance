package com.huawei.ascend.sit.fixtures.reconnect;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fault.FaultLink;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.EndpointType;

/** Managed WorkflowAgent topology used by the runtime reconnect E2E. */
public final class WorkflowReconnectFixture implements AutoCloseable {
    private static final String WORKFLOW = "expense-review-workflow";
    private static final String AGENT_ID = "expense-review";

    private final SutStack stack;

    private WorkflowReconnectFixture(SutStack stack) {
        this.stack = stack;
    }

    public static WorkflowReconnectFixture runtimeDirect() {
        TestConfig config = TestConfig.load();
        SutStack stack = SutStack.builder(config)
                .agent(WORKFLOW, workflow -> workflow
                        .cardEndpointRedirect("openjiuwen.service.a2a.public-url"))
                .start();
        return new WorkflowReconnectFixture(stack);
    }

    public AgentClient client() {
        return AgentClients.builder()
                .endpointType(EndpointType.RUNTIME)
                .endpointUrl(faultLink().listenUrl())
                .build();
    }

    public FaultLink faultLink() {
        return stack.faultLink(WORKFLOW);
    }

    public static String agentId() {
        return AGENT_ID;
    }

    @Override
    public void close() {
        stack.close();
    }
}
