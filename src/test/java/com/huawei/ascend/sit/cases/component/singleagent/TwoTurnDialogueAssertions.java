package com.huawei.ascend.sit.cases.component.singleagent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-03.D / B-03.E semantic heuristics for Turn2 reply text.
 */
final class TwoTurnDialogueAssertions {

    private TwoTurnDialogueAssertions() {
    }

    static void assertTurn2Understanding(String turn2Text) {
        assertThat(turn2Text).as("B-03.C turn2 text").isNotBlank();

        boolean matchedPositive = TwoTurnDialogueRunner.TURN2_MUST_MATCH_ANY.stream()
                .anyMatch(turn2Text::contains);
        assertThat(matchedPositive)
                .as("B-03.D turn2MustMatchAny — text should reflect Turn1 travel intent")
                .isTrue();

        for (String forbidden : TwoTurnDialogueRunner.TURN2_MUST_NOT_MATCH_ANY) {
            assertThat(turn2Text)
                    .as("B-03.E turn2MustNotMatchAny — must not repeat fresh-session prompts")
                    .doesNotContain(forbidden);
        }
    }
}
