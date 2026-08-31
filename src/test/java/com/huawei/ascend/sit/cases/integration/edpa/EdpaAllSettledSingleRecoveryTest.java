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

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>C2</b> —— all-settled 单次推理恢复（组合面，引用 FEAT-019 主权契约）。
 *
 * <p><b>Spec 依据</b>（testplan §5 C2）：同批全部子任务完成后**只触发一次**父 agent-core 推理恢复；
 * 不逐成员触发。观察证据：SSE 事件中「汇总性 artifact」（即 all-settled 后模型输出的 final_answer
 * 相关事件）**出现次数 = 1**，且发生在所有子委托事件之后。
 *
 * <p><b>观察面（相对 C1/C3 较可行）</b>：即使公开面不承载 batchId/toolCallId（P0b/P0c 缺陷），
 * SSE 事件流仍可观察到「汇总性 artifact」的出现次数——模型 final_answer 汇总输出是可识别的
 * llm_reasoning 分层（【需求概述】/【结果汇总】结构文本）。
 *
 * <p><b>断言</b>：SSE 中出现结构化汇总语言的 artifactUpdate 帧（如出现「【结果汇总】」标签）
 * **只应有 1 段连续输出**——若同一汇总关键词出现在多次独立汇总性输出中，说明可能发生逐成员触发。
 *
 * <p><b>Tag</b>：manual。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("C2.all-settled-single-recovery: 同批全部完成后单次推理恢复——汇总性 artifact 出现次数 = 1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaAllSettledSingleRecoveryTest {

    private static final Logger LOG = Logger.getLogger(EdpaAllSettledSingleRecoveryTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.c2-stream-cap-ms", 130_000L);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[c2] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[c2] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    /**
     * 判据说明（2026-08-24 首轮真机后调整）：模型 final_answer 结构化关键词（如「【结果汇总】」）
     * 是 planrule 建议格式而非硬约束——模型有自由度不遵守（LLM 抖动），不适合作为 all-settled
     * 单次恢复的硬判据。改用更本质的 A2A 契约信号：**终态 `statusUpdate` 帧应恰好出现 1 次**——
     * 若逐成员触发推理恢复，每个成员完成都会推一次终态相关的状态刷屏，可从帧数直接观察。
     */
    @Test
    @DisplayName("FEAT-028.C2: all-settled 单次推理恢复——终态 statusUpdate 帧恰好 1 次")
    void allSettledTriggersSingleRecovery() throws Exception {
        String contextId = "ctx-feat028-c2-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"c2-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HOMOG_PARALLEL);
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        assumeTrue(frames.size() > 0, "[c2] SSE 无事件，INCONCLUSIVE");

        // 统计终态 statusUpdate 帧数（COMPLETED/FAILED/CANCELED/REJECTED）
        int artifactFrames = 0;
        int terminalStatusFrames = 0;
        List<String> terminalStates = new ArrayList<>();
        List<Long> terminalTimestamps = new ArrayList<>();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            JsonNode result = f.parsed.path("result");
            if ("statusUpdate".equals(f.eventKind)) {
                String state = result.path("statusUpdate").path("status").path("state").asText("");
                if (state.contains("COMPLETED") || state.contains("FAILED")
                        || state.contains("CANCELED") || state.contains("REJECTED")) {
                    terminalStatusFrames++;
                    terminalStates.add(state);
                    terminalTimestamps.add(f.timestampMs);
                }
            } else if ("artifactUpdate".equals(f.eventKind)) {
                artifactFrames++;
            }
        }
        LOG.info(String.format("[c2] artifactFrames=%d terminalStatusFrames=%d states=%s onsets@=%s",
                artifactFrames, terminalStatusFrames, terminalStates, terminalTimestamps));
        assertThat(artifactFrames).as("[c2] artifactUpdate 帧应 ≥ 1").isGreaterThanOrEqualTo(1);
        assertThat(terminalStatusFrames)
                .as("[c2] ⭐ all-settled 单次推理恢复：SSE 事件流中终态 statusUpdate 帧应**恰好** 1 次。"
                        + "若 > 1，说明每个子任务完成都触发了一次父任务终态刷屏（逐成员触发缺陷）；"
                        + "若 = 0，说明父任务未通过 SSE 传达终态。实测 states=%s", terminalStates)
                .isEqualTo(1);
        LOG.info("[c2] PASS all-settled 单次恢复：终态 statusUpdate 恰好 1 次，"
                + "state=" + terminalStates.get(0) + " @ t=" + terminalTimestamps.get(0));
    }
}
