package com.huawei.ascend.sit.cases.component.service;

import com.huawei.ascend.sit.base.BaseComponentTest;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component-level tests for the A2A agent card resolution (health / capability discovery).
 *
 * <p>Verifies that the SUT's agent card is resolvable and contains
 * the expected fields indicating the agent is operational.</p>
 */
@Tag("smoke")
@Disabled("示例用例，待联调验证后逐个放开")
class A2aHealthTest extends BaseComponentTest {

    @Test
    @DisplayName("Agent card is resolvable and contains agent name")
    void agentCard_shouldBeResolvable() {
        // when
        AgentCard card = a2aClient.getAgentCard();

        // then
        assertThat(card).isNotNull();
        assertThat(card.name()).isNotBlank();
    }

    @Test
    @DisplayName("Agent card declares supported skills")
    void agentCard_shouldDeclareSkills() {
        // when
        AgentCard card = a2aClient.getAgentCard();

        // then
        assertThat(card).isNotNull();
        assertThat(card.skills()).isNotEmpty();
    }
}
