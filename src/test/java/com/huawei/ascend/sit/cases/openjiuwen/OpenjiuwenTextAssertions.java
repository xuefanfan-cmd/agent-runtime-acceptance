package com.huawei.ascend.sit.cases.openjiuwen;

import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenSessionIsolationScenarioData;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenSkillHubScenarioData;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenTwoTurnScenarioData;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantic heuristics for openjiuwen multi-turn reply text.
 */
public final class OpenjiuwenTextAssertions {

    private OpenjiuwenTextAssertions() {
    }

    public static void assertTurn2Understanding(String turn2Text, OpenjiuwenTwoTurnScenarioData scenario) {
        assertTurn2Understanding(turn2Text, scenario, "openjiuwen turn2");
    }

    public static void assertTurn2Understanding(String turn2Text, OpenjiuwenTwoTurnScenarioData scenario,
                                                String label) {
        assertThat(turn2Text).as(label + " text").isNotBlank();

        boolean matchedPositive = scenario.turn2MustMatchAny().stream()
                .anyMatch(turn2Text::contains);
        assertThat(matchedPositive)
                .as(label + " turn2MustMatchAny — text should reflect Turn1 travel intent")
                .isTrue();

        for (String forbidden : scenario.turn2MustNotMatchAny()) {
            assertThat(turn2Text)
                    .as(label + " turn2MustNotMatchAny — must not repeat fresh-session prompts")
                    .doesNotContain(forbidden);
        }
    }

    /**
     * OJ-04 session isolation heuristics (aligned with OJ-03 weak-semantic discipline).
     *
     * <p>Primary signal: Turn2 must not treat the other session's city as the destination.
     * Turn2 may be a follow-up prompt without repeating the city from Turn1; in that case
     * {@code turn2MustMatchAny} or prior agent dialogue mentioning {@code expectedCity} suffices.</p>
     */
    public static void assertCityIsolation(String turn2AgentText, String agentDialogueCorpus,
                                           String expectedCity, String forbiddenDominantCity,
                                           List<String> turn2MustMatchAny) {
        assertThat(turn2AgentText).as("turn2 agent text").isNotBlank();

        assertThat(turn2AgentText)
                .as("turn2 must not treat forbidden session city as destination")
                .doesNotContain("去" + forbiddenDominantCity);

        int forbiddenIndex = turn2AgentText.indexOf(forbiddenDominantCity);
        int expectedIndex = turn2AgentText.indexOf(expectedCity);
        if (forbiddenIndex >= 0 && expectedIndex >= 0) {
            assertThat(expectedIndex)
                    .as("expected city should appear before forbidden city when both present in turn2")
                    .isLessThan(forbiddenIndex);
        }

        boolean turn2Continuation = turn2MustMatchAny.stream().anyMatch(turn2AgentText::contains);
        boolean agentRetainedCity = agentDialogueCorpus.contains(expectedCity);
        assertThat(turn2Continuation || agentRetainedCity)
                .as("turn2 should continue the session (matchAny=%s) or agent dialogue retains %s",
                        turn2MustMatchAny, expectedCity)
                .isTrue();
    }

    public static void assertCityIsolation(String turn2AgentText, String agentDialogueCorpus,
                                           OpenjiuwenSessionIsolationScenarioData.SessionCase session,
                                           OpenjiuwenSessionIsolationScenarioData scenario) {
        assertCityIsolation(
                turn2AgentText,
                agentDialogueCorpus,
                session.expectedCity(),
                session.forbiddenDominantCity(),
                scenario.resolvedTurn2MustMatchAny());
    }

    /** OJ-11.B — skill output template heuristics (weak semantic). */
    public static void assertSkillWorkflowTemplate(String responseText, OpenjiuwenSkillHubScenarioData scenario,
                                                   String label) {
        assertThat(responseText).as("%s response text", label).isNotBlank();

        long matched = scenario.mustMatchAny().stream()
                .filter(marker -> marker != null && !marker.isBlank())
                .filter(responseText::contains)
                .count();
        assertThat(matched)
                .as("%s skill template markers (mustMatchAny=%s, min=%d, text truncated=%s)",
                        label,
                        scenario.mustMatchAny(),
                        scenario.mustMatchMinCount(),
                        truncate(responseText, 200))
                .isGreaterThanOrEqualTo(scenario.mustMatchMinCount());
    }

    /** OJ-11.C — destination and budget/star context retained in reply. */
    public static void assertSkillBusinessContext(String responseText, OpenjiuwenSkillHubScenarioData scenario,
                                                  String label) {
        assertThat(responseText).as("%s response text", label).isNotBlank();
        assertThat(responseText)
                .as("%s must mention city %s", label, scenario.mustMentionCity())
                .contains(scenario.mustMentionCity());

        List<String> keywords = scenario.businessContextKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return;
        }
        boolean matched = keywords.stream()
                .anyMatch(keyword -> responseText.toLowerCase(Locale.ROOT)
                        .contains(keyword.toLowerCase(Locale.ROOT)));
        assertThat(matched)
                .as("%s business context keywords (any of %s)", label, keywords)
                .isTrue();
    }

    /** OJ-11.D — reply must contain concrete hotel ranking, not only capability blurb. */
    public static void assertNotOnlyCapabilityBlurb(String responseText, String label) {
        assertThat(responseText).as("%s response text", label).isNotBlank();

        boolean numberedList = responseText.contains("1.")
                || responseText.contains("**1.")
                || responseText.matches("(?s).*\\n\\s*\\d+\\..*");
        boolean concreteHotel = responseText.contains("酒店")
                && (responseText.contains("亚朵")
                || responseText.contains("全季")
                || responseText.contains("希尔顿")
                || responseText.contains("桔子")
                || responseText.contains("BJ-"));
        assertThat(numberedList || concreteHotel)
                .as("%s must contain ranked hotel entries, not only skill capability blurb (text truncated=%s)",
                        label, truncate(responseText, 200))
                .isTrue();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
