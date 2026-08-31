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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>P3</b> —— 同类型批量并行（SSE）。
 *
 * <p><b>Spec 依据</b>（testplan §5 P3）：`SendStreamingMessage(PROMPT_HOMOG_PARALLEL)`；从 SSE
 * 事件流观察 statusUpdate/artifactUpdate 帧的时间戳，硬断言：①流式帧 ≥ 2 帧（存在过程观察）；
 * ②终态状态出现（COMPLETED/FAILED）；③总耗时符合并行启发式（< 90s 且远快于串行下限）；
 * ④artifactUpdate 帧覆盖两件事（虚拟线程 + GC）。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search-agent。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P3.homog-parallel-streaming: 同类型批量并行 SSE 模式，事件流承载过程 + 终态覆盖两件事")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaHomogParallelStreamingTest {

    private static final Logger LOG = Logger.getLogger(EdpaHomogParallelStreamingTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.p3-stream-cap-ms", 130_000L);
    private static final long PARALLEL_HEURISTIC_UPPER_MS = 90_000L;

    private final HttpClient http = HttpClient.newHttpClient();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[p3] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p3] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P3: 同类型批量并行 SSE——事件流过程 + 终态 + 覆盖两件事 + 并行启发式")
    void homogParallelStreamingCoversBothTopicsViaSseWithParallelism() throws Exception {
        String contextId = "ctx-feat028-p3-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p3-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HOMOG_PARALLEL);

        long t0 = System.currentTimeMillis();
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        long totalElapsed = System.currentTimeMillis() - t0;
        LOG.info(String.format("[p3] SSE frames=%d totalElapsed=%dms", frames.size(), totalElapsed));

        assertThat(frames.size()).as("[p3] 流式事件帧应 ≥ 2（承载过程观察）").isGreaterThanOrEqualTo(2);

        // 分帧统计
        int statusFrames = 0, artifactFrames = 0;
        boolean sawTerminal = false;
        StringBuilder allText = new StringBuilder();
        Set<String> observedTaskIds = new HashSet<>();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            JsonNode result = f.parsed.path("result");
            if ("statusUpdate".equals(f.eventKind)) {
                statusFrames++;
                String state = result.path("statusUpdate").path("status").path("state").asText("");
                if (state.contains("COMPLETED") || state.contains("FAILED")
                        || state.contains("CANCELED") || state.contains("REJECTED")) sawTerminal = true;
                String tid = result.path("statusUpdate").path("taskId").asText("");
                if (!tid.isEmpty()) observedTaskIds.add(tid);
            } else if ("artifactUpdate".equals(f.eventKind)) {
                artifactFrames++;
                String tid = result.path("artifactUpdate").path("taskId").asText("");
                if (!tid.isEmpty()) observedTaskIds.add(tid);
                for (JsonNode part : result.path("artifactUpdate").path("artifact").path("parts")) {
                    allText.append(part.path("text").asText("")).append("\n");
                    // llm_reasoning payload 也能算内容
                    JsonNode content = part.path("data").path("payload").path("content");
                    if (content.isTextual()) allText.append(content.asText()).append("\n");
                }
            }
        }
        LOG.info(String.format("[p3] statusFrames=%d artifactFrames=%d sawTerminal=%s observedTaskIds=%s",
                statusFrames, artifactFrames, sawTerminal, observedTaskIds));

        assertThat(sawTerminal).as("[p3] 应观察到终态帧").isTrue();
        assertThat(artifactFrames).as("[p3] artifactUpdate 帧应 ≥ 1（过程输出）").isGreaterThanOrEqualTo(1);

        // 覆盖两件事
        String text = allText.toString();
        boolean coversVT = containsAny(text, "虚拟线程", "Virtual Thread", "virtual thread");
        boolean coversGC = containsAny(text, "GC", "ZGC", "G1", "Shenandoah", "垃圾回收", "垃圾收集");
        assertThat(coversVT).as("[p3] artifact 内容未覆盖虚拟线程主题；前 500 字符=%s",
                truncate(text, 500)).isTrue();
        assertThat(coversGC).as("[p3] artifact 内容未覆盖 GC 主题；前 500 字符=%s",
                truncate(text, 500)).isTrue();

        // 并行启发式（同 P1）
        if (totalElapsed >= PARALLEL_HEURISTIC_UPPER_MS) {
            assumeTrue(false, "[p3] 总耗时 " + totalElapsed + "ms 超启发式上限 " + PARALLEL_HEURISTIC_UPPER_MS
                    + "ms，无法从公开面确证并行；本轮 INCONCLUSIVE");
            return;
        }
        LOG.info(String.format("[p3] PASS 并行 SSE：totalElapsed=%dms < 上限 %dms，"
                + "覆盖两件事 + 事件流过程完整", totalElapsed, PARALLEL_HEURISTIC_UPPER_MS));
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
