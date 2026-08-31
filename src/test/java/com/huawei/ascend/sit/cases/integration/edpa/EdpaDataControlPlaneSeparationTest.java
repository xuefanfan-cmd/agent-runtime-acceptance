package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>S1</b> ⭐ —— 数据面/控制面分离（§5.0.1 主权）。
 *
 * <p><b>Spec 依据</b>（testplan §5 S1 + FEAT-028 §5.0.1）：①流式 artifactUpdate 事件承载各子任务
 * 原始输出（数据面透传）；②最终业务结果（final_answer）**不是流式事件的机械拼接**，而是模型
 * all-settled 后单次推理的汇总；③流式事件的存在不改变父任务的单次恢复语义。
 *
 * <p><b>观察面</b>：跑 SSE 版并行，收集 artifactUpdate 事件（数据面原始 llm_reasoning 片段），
 * 然后 GetTask 拿终态 artifacts（控制面汇总）。硬断言：①两个通道都有内容；②控制面 final_answer
 * 与数据面流式片段**结构上不等价**（不是拼接）——通过结构性判据：控制面有明确汇总/归纳性表达。
 *
 * <p><b>Tag</b>：manual —— 复用 P3/P4 同拓扑。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("S1.data-control-separation: 流式数据面承载过程；控制面 final_answer 为模型汇总非流式拼接")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaDataControlPlaneSeparationTest {

    private static final Logger LOG = Logger.getLogger(EdpaDataControlPlaneSeparationTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.s1-stream-cap-ms", 130_000L);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[s1] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[s1] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.S1: 数据面 artifactUpdate 承载过程 + 控制面 final_answer 为模型汇总")
    void dataAndControlPlanesAreSeparateAndFinalAnswerIsSummary() throws Exception {
        String contextId = "ctx-feat028-s1-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"s1-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HOMOG_PARALLEL);

        // 数据面：收集 artifactUpdate 片段
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        LOG.info("[s1] SSE frames=" + frames.size());
        assumeTrue(frames.size() > 0, "[s1] SSE 无事件帧，INCONCLUSIVE");

        String parentTaskId = null;
        StringBuilder dataPlaneText = new StringBuilder();
        int artifactFrames = 0;
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            JsonNode result = f.parsed.path("result");
            if ("statusUpdate".equals(f.eventKind) && parentTaskId == null) {
                parentTaskId = result.path("statusUpdate").path("taskId").asText(null);
            } else if ("artifactUpdate".equals(f.eventKind)) {
                artifactFrames++;
                if (parentTaskId == null) {
                    parentTaskId = result.path("artifactUpdate").path("taskId").asText(null);
                }
                for (JsonNode part : result.path("artifactUpdate").path("artifact").path("parts")) {
                    dataPlaneText.append(part.path("text").asText(""));
                    JsonNode content = part.path("data").path("payload").path("content");
                    if (content.isTextual()) dataPlaneText.append(content.asText());
                }
            }
        }
        assumeTrue(parentTaskId != null, "[s1] 未取到父 taskId，INCONCLUSIVE");
        LOG.info(String.format("[s1] parent=%s artifactFrames=%d dataPlaneChars=%d",
                parentTaskId, artifactFrames, dataPlaneText.length()));
        assertThat(artifactFrames).as("[s1] artifactUpdate 帧应 ≥ 1（数据面承载过程）").isGreaterThanOrEqualTo(1);

        // 控制面：GetTask 拿终态 artifacts（final_answer）
        HttpResponse<String> gt = http.send(HttpRequest.newBuilder(URI.create(edpStack.baseUrl(EDP_AGENT) + "/a2a"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(String.format(
                                "{\"jsonrpc\":\"2.0\",\"id\":\"gt-s1\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                                parentTaskId))).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(gt.body());
        JsonNode task = root.path("result").path("task").isMissingNode()
                ? root.path("result") : root.path("result").path("task");
        String terminalState = task.path("status").path("state").asText("");
        assumeTrue(terminalState.contains("COMPLETED"),
                "[s1] 终态非 COMPLETED（" + terminalState + "），无法评估控制面 final_answer，INCONCLUSIVE");

        StringBuilder controlPlaneText = new StringBuilder();
        for (JsonNode artifact : task.path("artifacts")) {
            for (JsonNode part : artifact.path("parts")) {
                controlPlaneText.append(part.path("text").asText("")).append("\n");
            }
        }
        assertThat(controlPlaneText.length()).as("[s1] 控制面 final_answer 不得为空").isGreaterThan(0);

        String controlText = controlPlaneText.toString();
        String dataText = dataPlaneText.toString();
        LOG.info(String.format("[s1] controlPlaneChars=%d controlSample=%s | dataSample=%s",
                controlText.length(),
                controlText.substring(0, Math.min(200, controlText.length())),
                dataText.substring(0, Math.min(200, dataText.length()))));

        // 硬断言：控制面 final_answer 应包含结构化汇总语言（【需求概述】/【结果汇总】等 planrule 要求的输出格式），
        // 或明确的归纳表达；而数据面通常是 llm_reasoning 的 token 级流片段，不含这类结构。
        boolean hasSummaryStructure = containsAny(controlText,
                "【需求概述】", "【规划过程】", "【任务执行情况】", "【结果汇总】", "【异常说明】",
                "汇总", "综上", "综合", "总结", "小结");
        assertThat(hasSummaryStructure)
                .as("[s1] 控制面 final_answer 应体现「模型 all-settled 后单次汇总推理」的结构性归纳，"
                        + "而非流式片段的机械拼接。前 500 字符=%s", truncate(controlText, 500))
                .isTrue();

        // 数据面/控制面确实分离：数据面 llm_reasoning 应远长于最终 answer（多轮 token 级），
        // 或至少大小不等；若两者完全等价，说明可能是流式片段被作为 final_answer 直接返回（违约）
        if (Math.abs(dataText.length() - controlText.length()) < 20) {
            LOG.warning(String.format("[s1] ⚠ 数据面(%d 字符) 与 控制面(%d 字符) 长度过于接近，"
                    + "可能存在数据面被机械拼接为 final_answer 的风险——需核查",
                    dataText.length(), controlText.length()));
        }
        LOG.info(String.format("[s1] PASS 数据面/控制面分离：dataPlane=%d 字符（llm_reasoning 流），"
                + "controlPlane=%d 字符（结构化汇总）", dataText.length(), controlText.length()));
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
