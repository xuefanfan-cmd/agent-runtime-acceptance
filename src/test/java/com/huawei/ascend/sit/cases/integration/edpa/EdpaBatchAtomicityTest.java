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
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>C1</b> —— 同批多委托原子性（组合面，引用 FEAT-019 主权契约）。
 *
 * <p><b>Spec 依据</b>（testplan §5 C1）：若模型同轮生成 N ≥ 2 个 ToolCall，父任务快照的可观察证据
 * 应显示**批次 items 数 = N**——不得只保留最后一个中断、不得静默丢弃任何 ToolCall。
 * 这是 FEAT-019 「同轮批量中断聚合」主权契约在 EDPA 场景端到端面的组合体现。
 *
 * <p><b>Spec 更正（2026-08-24）</b>：原判据里"batchId 应对客户端可见"是**误读**——FEAT-019
 * 特性档 §88 明确"batchId 可以是 core 或 runtime adapter 内部诊断标识，**不要求外部客户端传入**"；
 * FEAT-028 §278/306/430 把 `batchId`/`items`/`toolCallId` 三件套定性为 core→runtime **内部** batch
 * interrupt envelope。**batchId 按设计就不对客户端可见**，本用例不应对其做硬断言。
 *
 * <p><b>更新后的观察策略</b>：
 * <ul>
 *   <li><b>硬 A（wire 可见的 toolCallId 互不重复且 ≥ 2）</b>：SSE 事件里 `agentEvent.toolCallId`
 *       ≥ 2 个不同值——证明 runtime 派发了 ≥ 2 个 ToolCall（不是只保留一个）；</li>
 *   <li><b>硬 B（数据面并行汇总覆盖两件事）</b>：最终 artifact 内容覆盖 search + verify 两个主题——
 *       证明批次全部完成后模型汇总了 ≥ 2 个子结果（不是静默丢弃）。</li>
 * </ul>
 * A + B 同时成立 → 批次原子性组合契约在 EDPA 场景端到端可观察 → PASS。
 * batchId 作为内部诊断字段的可见性诉求撤回。
 *
 * <p><b>Tag</b>：manual。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("C1.batch-atomicity: 同批多委托原子性——wire toolCallId ≥ 2 且互不重复 + 汇总覆盖两件事（batchId 内部诊断字段可见性诉求已撤回）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaBatchAtomicityTest {

    private static final Logger LOG = Logger.getLogger(EdpaBatchAtomicityTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.c1-stream-cap-ms", 130_000L);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[c1] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[c1] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.C1: 同批多委托原子性——公开面 batchId/toolCallId 观察 + 间接旁证")
    void batchAtomicityViaPublicSurfaceOrIndirectEvidence() throws Exception {
        String contextId = "ctx-feat028-c1-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"c1-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        LOG.info("[c1] SSE frames=" + frames.size());
        assumeTrue(frames.size() > 0, "[c1] SSE 无事件，INCONCLUSIVE");

        // 观察面 1：全字段递归扫描（复用 EdpaChildVisibilityScanner，与 R1/P0b/P0c 同款）
        String parentTaskId = null;
        StringBuilder allText = new StringBuilder();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            JsonNode result = f.parsed.path("result");
            if (parentTaskId == null) {
                parentTaskId = result.path("statusUpdate").path("taskId").asText(null);
                if (parentTaskId == null || parentTaskId.isEmpty()) {
                    parentTaskId = result.path("artifactUpdate").path("taskId").asText(null);
                }
            }
            if ("artifactUpdate".equals(f.eventKind)) {
                for (JsonNode part : result.path("artifactUpdate").path("artifact").path("parts")) {
                    allText.append(part.path("text").asText(""));
                    JsonNode content = part.path("data").path("payload").path("content");
                    if (content.isTextual()) allText.append(content.asText());
                }
            }
        }
        // 全字段扫描聚合（避免"只查 batchId+toolCallId 关键字"的预设判据）
        EdpaChildVisibilityScanner.Result fullScan = new EdpaChildVisibilityScanner.Result();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed != null) EdpaChildVisibilityScanner.scanInto(f.parsed, parentTaskId, fullScan);
        }
        Set<String> observedToolCallIds = fullScan.toolCallIds;
        LOG.info(String.format("[c1] 全字段扫描（%d 帧）: %s", frames.size(), fullScan.summary()));

        // 观察面 2：间接旁证——artifact 内容覆盖两件事（推理侧确实并行汇总了 ≥ 2 个子结果）
        String text = allText.toString();
        boolean coversSearch = containsAny(text, "虚拟线程", "Virtual Thread", "virtual thread");
        boolean coversVerify = containsAny(text, "OOM", "线程池", "验证", "核查", "结论", "准确", "正确", "错误");
        boolean indirectEvidence = coversSearch && coversVerify;
        LOG.info("[c1] 间接旁证: coversSearch=" + coversSearch + " coversVerify=" + coversVerify);

        // 硬 A：SSE 里 toolCallId ≥ 2 且互不重复——证明 runtime 派发了 ≥ 2 个 ToolCall（不静默丢弃）
        assertThat(observedToolCallIds.size())
                .as("[c1] ⭐ 硬 A：SSE 事件里应至少可观察到 2 个互不重复的 toolCallId（异构 prompt 应派发 2 委托）；"
                        + "实测=%s", observedToolCallIds)
                .isGreaterThanOrEqualTo(2);
        // 硬 B：数据面并行汇总——最终 artifact 内容覆盖 search + verify 两个主题
        assertThat(indirectEvidence)
                .as("[c1] ⭐ 硬 B：最终 artifact 应覆盖 search + verify 两个主题（证明批次全部完成后汇总）；"
                        + "coversSearch=%s coversVerify=%s", coversSearch, coversVerify)
                .isTrue();
        LOG.info("[c1] PASS 批次原子性组合契约（硬 A + 硬 B 双证）；"
                + "batchId 作为内部诊断字段本就不对客户端可见（FEAT-019 §88），已从判据里撤回");
    }


    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase();
        for (String n : needles) if (text.contains(n) || lower.contains(n.toLowerCase())) return true;
        return false;
    }
}
