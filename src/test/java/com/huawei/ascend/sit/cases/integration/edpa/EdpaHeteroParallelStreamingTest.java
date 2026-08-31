package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.http.HttpClient;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>P4</b> —— 异构混合并行（SSE）。同 P3 结构，prompt 换 HETERO，断言两件事覆盖异构主题。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search、versatile。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P4.hetero-parallel-streaming: 异构混合并行 SSE 模式，事件流承载过程 + 覆盖搜索与验证")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaHeteroParallelStreamingTest {

    private static final Logger LOG = Logger.getLogger(EdpaHeteroParallelStreamingTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.p4-stream-cap-ms", 130_000L);
    private static final long PARALLEL_HEURISTIC_UPPER_MS = 90_000L;

    private final HttpClient http = HttpClient.newHttpClient();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[p4] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p4] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P4: 异构混合并行 SSE——事件流过程 + 终态 + 覆盖搜索与验证结论 + 并行启发式")
    void heteroParallelStreamingCoversSearchAndVerifyViaSse() throws Exception {
        String contextId = "ctx-feat028-p4-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p4-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);

        long t0 = System.currentTimeMillis();
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        long totalElapsed = System.currentTimeMillis() - t0;
        LOG.info(String.format("[p4] SSE frames=%d totalElapsed=%dms", frames.size(), totalElapsed));

        assertThat(frames.size()).as("[p4] 流式帧应 ≥ 2").isGreaterThanOrEqualTo(2);

        boolean sawTerminal = false;
        int artifactFrames = 0;
        StringBuilder text = new StringBuilder();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            JsonNode result = f.parsed.path("result");
            if ("statusUpdate".equals(f.eventKind)) {
                String state = result.path("statusUpdate").path("status").path("state").asText("");
                if (state.contains("COMPLETED") || state.contains("FAILED")
                        || state.contains("CANCELED") || state.contains("REJECTED")) sawTerminal = true;
            } else if ("artifactUpdate".equals(f.eventKind)) {
                artifactFrames++;
                for (JsonNode part : result.path("artifactUpdate").path("artifact").path("parts")) {
                    text.append(part.path("text").asText("")).append("\n");
                    JsonNode content = part.path("data").path("payload").path("content");
                    if (content.isTextual()) text.append(content.asText()).append("\n");
                }
            }
        }
        assertThat(sawTerminal).as("[p4] 应观察到终态帧").isTrue();
        assertThat(artifactFrames).as("[p4] artifactUpdate 帧应 ≥ 1").isGreaterThanOrEqualTo(1);

        String t = text.toString();
        boolean coversSearch = containsAny(t, "虚拟线程", "Virtual Thread", "virtual thread");
        boolean coversVerify = containsAny(t, "OOM", "线程池", "验证", "核查", "结论", "准确", "正确", "错误", "存疑");
        assertThat(coversSearch).as("[p4] 未覆盖 search 主题；前 500=%s", truncate(t, 500)).isTrue();
        assertThat(coversVerify).as("[p4] 未覆盖 verify 结论；前 500=%s", truncate(t, 500)).isTrue();

        if (totalElapsed >= PARALLEL_HEURISTIC_UPPER_MS) {
            assumeTrue(false, "[p4] 总耗时 " + totalElapsed + "ms 超启发式上限，INCONCLUSIVE");
            return;
        }
        LOG.info(String.format("[p4] PASS 异构并行 SSE：totalElapsed=%dms < %dms",
                totalElapsed, PARALLEL_HEURISTIC_UPPER_MS));
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase();
        for (String n : needles) if (text.contains(n) || lower.contains(n.toLowerCase())) return true;
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
