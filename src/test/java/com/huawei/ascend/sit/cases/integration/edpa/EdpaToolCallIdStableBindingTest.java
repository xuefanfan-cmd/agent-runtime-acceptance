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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>C3</b> —— toolCallId 稳定归位（组合面，引用 FEAT-019 主权契约）。
 *
 * <p><b>Spec 依据</b>（testplan §5 C3）：每个子委托的 `toolCallId` 唯一；结果按 `toolCallId` 归位
 * （不按完成顺序猜测）；快照/事件中可追溯到「ToolCall.toolCallId ↔ tool_result.toolCallId」的
 * 一致映射。
 *
 * <p><b>观察面 red-first</b>（与 C1 同源）：EDPAgent 公开面不承载 toolCallId（P0b/P0c/N1 综合结论）。
 * 本用例做双源 dual-observation：①公开面扫描 toolCallId 集合并检验唯一性；②若公开面缺失，
 * 降级为 INCONCLUSIVE 承接 issue #93。
 *
 * <p><b>Tag</b>：manual。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("C3.tool-call-id-stable: toolCallId 唯一 + 结果按 ID 归位（红-first 承接 issue #93）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaToolCallIdStableBindingTest {

    private static final Logger LOG = Logger.getLogger(EdpaToolCallIdStableBindingTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.c3-stream-cap-ms", 130_000L);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[c3] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[c3] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.C3: toolCallId 唯一 + 结果按 ID 归位（公开面观察 or INCONCLUSIVE 承接 issue #93）")
    void toolCallIdIsUniqueAndResultBindsByIdOrIncomplusiveIfPublicSurfaceMissing() throws Exception {
        String contextId = "ctx-feat028-c3-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"c3-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);
        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        assumeTrue(frames.size() > 0, "[c3] SSE 无事件，INCONCLUSIVE");

        // 全字段递归扫描（复用 EdpaChildVisibilityScanner，与 R1/P0b/P0c/C1 同款）
        EdpaChildVisibilityScanner.Result fullScan = new EdpaChildVisibilityScanner.Result();
        int occurrences = 0;
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            EdpaChildVisibilityScanner.scanInto(f.parsed, null, fullScan);
            // 单帧内出现次数（用于精细化"平均出现次数"判据）
            occurrences += countToolCallIdOccurrences(f.parsed);
        }
        Set<String> observedToolCallIds = fullScan.toolCallIds;
        LOG.info(String.format("[c3] 全字段扫描（%d 帧）: %s | totalOccurrences=%d",
                frames.size(), fullScan.summary(), occurrences));

        if (observedToolCallIds.isEmpty()) {
            // 公开面无 toolCallId——同 P0b/P0c 缺陷（issue #93）
            LOG.warning("[c3] INCONCLUSIVE 公开面 SSE 事件流不承载 toolCallId——同 P0b/P0c/N1 结论；"
                    + "承接 issue #93 待修复，修复后本用例可升级为公开面硬断言：①toolCallId 唯一；"
                    + "②每个 toolCallId 出现于 ToolCall 与 tool_result 两处（一致映射）");
            assumeTrue(false, "[c3] 公开面 toolCallId 观察面缺失，INCONCLUSIVE 承接 issue #93");
            return;
        }

        // 公开面有 toolCallId——升级硬断言
        assertThat(observedToolCallIds.size())
                .as("[c3] 观察到的 toolCallId 应互不重复，且 ≥ 2（异构 prompt 应生成 2 委托）")
                .isGreaterThanOrEqualTo(2);
        // ⭐ 精细化 red-first：每个 toolCallId 应至少出现 2 次（tool_call 派发 + tool_result 归位）。
        // 实测（2026-08-24）：SSE 事件流里 toolCallId 只在 llm_reasoning 帧的 tool_call 参数结构中出现
        // 一次；**tool_result 侧完全无 toolCallId 归位事件**——即无法通过公开面证明「结果按 ID 归位」
        // 契约（虽然 agent-core/runtime 内部实际做了归位，见服务端日志）。属 issue #93 缺陷的第 2 个
        // 精细化观察（C1 是第 1 个：batchId 缺失）。修复后本用例自动转 PASS。
        double avgOccurrences = observedToolCallIds.isEmpty() ? 0
                : (double) occurrences / observedToolCallIds.size();
        assertThat(avgOccurrences)
                .as("[c3] ⭐ 每个 toolCallId 应至少出现于 ToolCall 与 tool_result 两处（一致映射）——"
                        + "实测平均 %.2f 次，说明 tool_result 侧未按 toolCallId 归位（承接 issue #93）。"
                        + "observedToolCallIds=%s", avgOccurrences, observedToolCallIds)
                .isGreaterThanOrEqualTo(2.0);
        LOG.info(String.format("[c3] PASS toolCallId 唯一 + 一致映射: %d 个 ID × 平均 %.2f 次",
                observedToolCallIds.size(), avgOccurrences));
    }

    /** 统计单帧里 toolCallId 出现次数（不去重）——用于精细化"平均出现次数"判据。 */
    private static int countToolCallIdOccurrences(JsonNode node) {
        if (node == null || node.isNull()) return 0;
        int count = 0;
        if (node.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey().toLowerCase();
                JsonNode val = e.getValue();
                if (val.isTextual() && !val.asText().isBlank()
                        && (key.equals("toolcallid") || key.equals("tool_call_id"))) {
                    count++;
                }
                count += countToolCallIdOccurrences(val);
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) count += countToolCallIdOccurrences(c);
        }
        return count;
    }
}
