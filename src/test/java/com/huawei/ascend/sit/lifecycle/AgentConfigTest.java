package com.huawei.ascend.sit.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentConfig} — verifies the Spring Boot program-argument
 * format the launcher depends on. Pure logic; no process access.
 */
class AgentConfigTest {

    @Test
    void emitsServerPortThenProfileThenPropertiesInOrder() {
        AgentConfig config = new AgentConfig()
                .port(38211)
                .profile("test")
                .property("agent-runtime.access.a2a.agent-card.name", "x")
                .property("main-plan-agent.checkpointer", "redis");

        assertThat(config.toProgramArgs()).containsExactly(
                "--server.port=38211",
                "--spring.profiles.active=test",
                "--agent-runtime.access.a2a.agent-card.name=x",
                "--main-plan-agent.checkpointer=redis");
    }

    @Test
    void omitsServerPortWhenUnresolvedAndProfileWhenBlank() {
        AgentConfig config = new AgentConfig()
                .property("some.key", "value");

        // port defaults to 0 (launcher picks), profile blank => neither emitted
        assertThat(config.toProgramArgs()).containsExactly("--some.key=value");
    }

    @Test
    void downstreamUrlUsesRemoteAgentsSlot() {
        AgentConfig config = new AgentConfig().downstreamUrl("http://localhost:42111");

        assertThat(config.properties())
                .containsEntry(AgentConfig.REMOTE_AGENTS_URL, "http://localhost:42111");
        assertThat(config.toProgramArgs())
                .contains("--" + AgentConfig.REMOTE_AGENTS_URL + "=http://localhost:42111");
    }

    @Test
    void accumulatesEnvironment() {
        AgentConfig config = new AgentConfig().env("SAA_SAMPLE_LLM_API_KEY", "sk-xxx");

        assertThat(config.environment())
                .containsEntry("SAA_SAMPLE_LLM_API_KEY", "sk-xxx");
    }
}
