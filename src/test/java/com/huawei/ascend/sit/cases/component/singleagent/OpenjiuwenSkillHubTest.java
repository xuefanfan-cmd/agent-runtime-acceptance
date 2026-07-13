package com.huawei.ascend.sit.cases.component.singleagent;

import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-11 — openjiuwen hotel skill-hub {@code hotel_ranking} dynamic trigger (streaming).
 *
 * <p>Uses {@link SutStack} + {@link InteractionFlow} directly (no OJ StackSupport / Runner /
 * main ScenarioData). Hotel {@code skill} profile only — do not combine with sandbox/mcp.
 * Terminal state hard-requires {@code COMPLETED}.</p>
 *
 * <p>See {@code docs/cases/reactagent/OJ-11-openjiuwen-skill-hub-dynamic-trigger.md}.</p>
 */
@Tag("component")
@Tag("openjiuwen")
@Tag("nightly")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenjiuwenSkillHubTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenSkillHubTest.class.getName());

    private static final String HOTEL = "hotel";
    private static final String SKILL_PROFILE = "skill";
    private static final String SKILL_PROFILE_MARKER = "profile is active: \"skill\"";
    private static final String SKILL_HUB_REGISTERED_MARKER = "Registered skill hub from";
    private static final String STABLE_SKILLS_DIR = ".travel-hotel-skills";
    private static final String READ_FILE_SUCCESS_MARKER = "End to read file";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Matches {@code testdata/component/singleagent/oj-11-skill-hotel-ranking.json}. */
    private static final String INPUT_TEXT =
            "请使用 hotel_ranking skill，为以下需求排序并推荐酒店：城市：北京；入住 2026-07-10，离店 2026-07-13；每晚价格上限 800 元；最低 4 星；偏好国贸附近。";
    private static final List<String> MUST_MATCH_ANY = List.of(
            "[符合差标]", "[不符合差标]", "推荐：", "★", "¥");
    private static final int MUST_MATCH_MIN_COUNT = 2;
    private static final String MUST_MENTION_CITY = "北京";
    private static final List<String> BUSINESS_CONTEXT_KEYWORDS = List.of("800", "4星", "四星", "¥");
    private static final long FLOW_TIMEOUT_MS = 180_000L;

    private TestConfig config;
    private SutStack stack;

    @BeforeAll
    void startHotelWithSkillProfile() throws Exception {
        config = TestConfig.load();
        stack = SutStack.builder(config)
                .streaming(true)
                .agent(HOTEL, a -> a.profile(SKILL_PROFILE))
                .start();
        assertHotelUsesSkillProfile(stack);
        LOG.info("OJ-11 hotel skill profile ready");
    }

    @AfterAll
    void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @DisplayName("OJ-11: hotel skill-hub hotel_ranking — 流式 COMPLETED 且体现 skill 工作流输出")
    void oj11_skillHotelRanking_streamingCompletedWithSkillWorkflow() throws Exception {
        long timeoutMs = Math.max(config.getPollTimeoutSeconds() * 1000L, FLOW_TIMEOUT_MS);

        InteractionFlow.of(stack.client(HOTEL))
                .withTimeoutMs(timeoutMs)
                .send(INPUT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertTask(task -> {
                        String text = TaskTextExtractor.textOf(task);
                        LOG.info("OJ-11 reply (truncated): "
                                + (text.length() > 300 ? text.substring(0, 300) + "..." : text));
                        assertSkillWorkflowTemplate(
                                text, MUST_MATCH_ANY, MUST_MATCH_MIN_COUNT, "OJ-11.B");
                        assertSkillBusinessContext(
                                text, MUST_MENTION_CITY, BUSINESS_CONTEXT_KEYWORDS, "OJ-11.C");
                        assertNotOnlyCapabilityBlurb(text, "OJ-11.D");
                    })
                .execute();

        assertSkillWorkflowToolsSucceeded(stack);
        assertHotelResponds(stack);
    }

    private static void assertHotelUsesSkillProfile(SutStack stack) throws IOException {
        Path log = hotelStdoutLog(stack);
        String content = Files.readString(log, StandardCharsets.UTF_8);
        String runSlice = sliceSinceLast(content, SKILL_PROFILE_MARKER);

        assertThat(lastLineContaining(runSlice, SKILL_PROFILE_MARKER))
                .as("OJ-11.C hotel active profile skill (see %s)", log)
                .isNotNull();
        String registrationLine = lastLineContaining(runSlice, SKILL_HUB_REGISTERED_MARKER);
        assertThat(registrationLine)
                .as("OJ-11.C hotel skill hub registration "
                        + "(rebuild agent-openjiuwen-travel-hotel:0.1.0 if missing; see %s)", log)
                .isNotNull();
        String materializedLine = lastLineContaining(runSlice, "Materialized bundled skills to");
        if (materializedLine != null) {
            assertThat(materializedLine)
                    .as("OJ-11.C jar materialization should use stable dir %s (see %s)",
                            STABLE_SKILLS_DIR, log)
                    .contains(STABLE_SKILLS_DIR);
            assertThat(registrationLine)
                    .as("OJ-11.C skill hub path should match materialized dir (see %s)", log)
                    .contains(STABLE_SKILLS_DIR);
        }
    }

    private static void assertSkillWorkflowToolsSucceeded(SutStack stack) throws IOException {
        Path log = hotelStdoutLog(stack);
        String runSlice = sliceSinceLast(
                Files.readString(log, StandardCharsets.UTF_8), SKILL_HUB_REGISTERED_MARKER);

        long readFileSkillFailures = runSlice.lines()
                .filter(line -> line.contains("readFile") && line.contains("SKILL.md"))
                .filter(line -> line.contains("199003") || line.contains("traverses outside"))
                .count();
        assertThat(readFileSkillFailures)
                .as("OJ-11.E readFile(SKILL.md) must not fail "
                        + "(199003 / traverses outside); see %s", log)
                .isZero();

        assertThat(runSlice)
                .as("OJ-11.E readFile(SKILL.md) must succeed (sys_operation '%s'); see %s",
                        READ_FILE_SUCCESS_MARKER, log)
                .contains(READ_FILE_SUCCESS_MARKER);
    }

    private static void assertHotelResponds(SutStack stack)
            throws IOException, InterruptedException {
        String url = stack.baseUrl(HOTEL) + "/.well-known/agent.json";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("OJ-11.P hotel agent card probe %s", url)
                .isEqualTo(200);
    }

    private static void assertSkillWorkflowTemplate(
            String responseText,
            List<String> mustMatchAny,
            int mustMatchMinCount,
            String label) {
        assertThat(responseText).as("%s response text", label).isNotBlank();

        long matched = mustMatchAny.stream()
                .filter(marker -> marker != null && !marker.isBlank())
                .filter(responseText::contains)
                .count();
        assertThat(matched)
                .as("%s skill template markers (mustMatchAny=%s, min=%d, text truncated=%s)",
                        label, mustMatchAny, mustMatchMinCount, truncate(responseText, 200))
                .isGreaterThanOrEqualTo(mustMatchMinCount);
    }

    private static void assertSkillBusinessContext(
            String responseText,
            String mustMentionCity,
            List<String> businessContextKeywords,
            String label) {
        assertThat(responseText).as("%s response text", label).isNotBlank();
        assertThat(responseText)
                .as("%s must mention city %s", label, mustMentionCity)
                .contains(mustMentionCity);

        if (businessContextKeywords == null || businessContextKeywords.isEmpty()) {
            return;
        }
        boolean matched = businessContextKeywords.stream()
                .anyMatch(keyword -> responseText.toLowerCase(Locale.ROOT)
                        .contains(keyword.toLowerCase(Locale.ROOT)));
        assertThat(matched)
                .as("%s business context keywords (any of %s)", label, businessContextKeywords)
                .isTrue();
    }

    private static void assertNotOnlyCapabilityBlurb(String responseText, String label) {
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
                .as("%s must contain ranked hotel entries, not only skill capability blurb "
                        + "(text truncated=%s)", label, truncate(responseText, 200))
                .isTrue();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private static String sliceSinceLast(String logContent, String marker) {
        int lastIdx = logContent.toLowerCase(Locale.ROOT)
                .lastIndexOf(marker.toLowerCase(Locale.ROOT));
        if (lastIdx < 0) {
            return logContent;
        }
        return logContent.substring(lastIdx);
    }

    private static String lastLineContaining(String logContent, String needle) {
        String last = null;
        for (String line : logContent.split("\n")) {
            if (line.contains(needle)) {
                last = line;
            }
        }
        return last;
    }

    private static Path hotelStdoutLog(SutStack stack) {
        var instance = stack.managedInstance(HOTEL);
        assertThat(instance)
                .as("managed hotel agent for OJ-11 log gate")
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }
}
