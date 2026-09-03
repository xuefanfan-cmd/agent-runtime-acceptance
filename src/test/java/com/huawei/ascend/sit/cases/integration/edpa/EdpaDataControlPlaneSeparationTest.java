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
 * <p><b>观察面</b>：跑 SSE 版并行，收集 artifactUpdate 事件（数据面），然后 GetTask 拿终态
 * artifacts（控制面 final_answer）。判据：
 * <ul>
 *   <li><b>硬 1</b>：两个通道都有内容（artifactUpdate 帧 ≥ 1、final_answer 非空）；</li>
 *   <li><b>硬 2</b>（2026-09-03 落码）：控制面 {@code C} <b>既不等于、也不是</b>子段数据面
 *       {@code D_sub} 的<b>连续子串</b>——照搬即无汇总推理。{@code D_sub} 必须按
 *       {@code source.agentId ≠ 父} 过滤，见 {@link EdpaRecoverySegments#childPlaneText}。</li>
 *   <li><s>控制面须含【结果汇总】等结构化汇总词</s>——2026-09-03 <b>降级为诊断日志</b>：
 *       planrule 的输出格式是建议非硬约束（同一判据 C2 已在 2026-08-24 自行推翻）。</li>
 * </ul>
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
    @DisplayName("FEAT-028.S1: 数据面 artifactUpdate 承载过程 + 控制面 final_answer 不是子段数据面的拼接/子串")
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

        // ── 2026-09-03：原「控制面须含【结果汇总】等结构化汇总词」硬断言**降级为诊断日志** ──
        // 理由：这些标签是 planrule 的**建议**输出格式而非硬约束，模型有自由度不遵守。
        // 同一件事 C2 在 2026-08-24 真机后已经自己推翻过一次（见 EdpaAllSettledSingleRecoveryTest
        // 判据演进第 1 段）——同一份方案里一条推翻、另一条照用，是本轮返工要清掉的不一致。
        // 它现在只是「格式提示」，不承载 §5.0.1 的分离契约。
        boolean hasSummaryStructure = containsAny(controlText,
                "【需求概述】", "【规划过程】", "【任务执行情况】", "【结果汇总】", "【异常说明】",
                "汇总", "综上", "综合", "总结", "小结");
        LOG.info("[s1] [诊断，不判定] 控制面是否出现 planrule 建议的汇总性表达=" + hasSummaryStructure
                + "（false 不代表违约：格式是建议非约束）");

        // ────────────────────────────────────────────────────────────────
        // 硬 2（2026-09-03 落码）：控制面 C ⊄ 子段数据面 D_sub
        //
        // §5.0.1 主权要求 final_answer 是「模型 all-settled 后**单次推理的汇总**」，
        // 而不是流式片段的**机械拼接**。可复算的投影：C 既不等于 D_sub，也不是 D_sub 的连续子串。
        // 若 C 是 D_sub 的连续子串，说明控制面内容整段来自子任务原始输出，没有经过父 Agent 的汇总推理。
        //
        // ⚠️ D_sub **必须只取 source.agentId ≠ 父 的帧**（testplan §5 S1 注记的误红陷阱）：
        // 不过滤的话，父 Agent 自己的汇总流也在数据面里，C 天然是它的子串 → 判据恒红。
        // ────────────────────────────────────────────────────────────────
        String parentAgentId = EdpaRecoverySegments.parentAgentId(frames);
        assumeTrue(parentAgentId != null,
                "[s1] 硬 2 不可判定：delegation 的 source.agentId 不唯一或无 delegation 事件，"
                        + "无法确定父 Agent 身份，D_sub 的过滤基准建不起来，INCONCLUSIVE");
        String subText = EdpaRecoverySegments.childPlaneText(frames, parentAgentId);
        LOG.info(String.format("[s1] parentAgentId=%s D_sub=%d 字符（已按 source.agentId≠父 过滤；"
                        + "未过滤的全量数据面=%d 字符）", parentAgentId, subText.length(), dataText.length()));
        assumeTrue(!subText.isBlank(),
                "[s1] 硬 2 不可判定：过滤后子段数据面为空——本轮未观察到子 Agent 透传输出"
                        + "（可能模型未派发委托、或流被 cap 截断），「C ⊄ D_sub」无观察面，INCONCLUSIVE");

        String cNorm = normalize(controlText);
        String dNorm = normalize(subText);
        assertThat(cNorm)
                .as("[s1] ⭐ 硬 2（§5.0.1）：控制面 final_answer 不得等于子段数据面拼接——"
                        + "相等即说明它就是流式片段的机械拼接，没有 all-settled 单次汇总推理。%n"
                        + "C=%d 字符，D_sub=%d 字符（归一化后）", cNorm.length(), dNorm.length())
                .isNotEqualTo(dNorm);
        assertThat(dNorm.contains(cNorm))
                .as("[s1] ⭐ 硬 2（§5.0.1）：控制面 final_answer 不得是子段数据面的**连续子串**——"
                        + "整段照搬子任务原始输出，即未经过父 Agent 的汇总推理。%n"
                        + "C 前 500=%s%n D_sub 前 500=%s",
                        truncate(cNorm, 500), truncate(dNorm, 500))
                .isFalse();

        LOG.info(String.format("[s1] PASS 数据面/控制面分离：C=%d 字符，既不等于也不是 D_sub(%d 字符) 的连续子串",
                cNorm.length(), dNorm.length()));
    }

    /** 归一化：去掉所有空白，避免换行/缩进差异让「机械拼接」逃过子串判据。 */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
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
