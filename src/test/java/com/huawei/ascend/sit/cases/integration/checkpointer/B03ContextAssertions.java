package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.model.integration.checkpointer.B03ScenarioData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-03.D / B-03.E semantic heuristics for Turn2 reply text.
 */
final class B03ContextAssertions {

    private B03ContextAssertions() {
    }

    static void assertTurn2Understanding(String turn2Text, B03ScenarioData scenario) {
        assertThat(turn2Text).as("B-03.C turn2 text").isNotBlank();

        boolean matchedPositive = scenario.turn2MustMatchAny().stream()
                .anyMatch(turn2Text::contains);
        assertThat(matchedPositive)
                .as("B-03.D turn2MustMatchAny — text should reflect Turn1 travel intent")
                .isTrue();

        for (String forbidden : scenario.turn2MustNotMatchAny()) {
            assertThat(turn2Text)
                    .as("B-03.E turn2MustNotMatchAny — must not repeat fresh-session prompts")
                    .doesNotContain(forbidden);
        }
    }
}
