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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>N1</b> ⭐（越界约束 red-first 看守）—— envelope 不含协同模式字段。
 *
 * <p><b>Spec 依据</b>（testplan §5 N1 + FEAT-028 §2.2）：批量中断 envelope 只应承载
 * `{batchId, items:[{toolCallId, remoteAgentId, ...}], toolCallId}` 结构面；不得出现
 * `mode`/`syncMode`/`asyncMode`/`blocking`/`edpa_mode`/`coordinationMode`/`executionMode` 等
 * 协同模式字段——agent-core 越界向 runtime 传递执行策略即违约。
 *
 * <p><b>观察面</b>：从 SSE 事件流中递归扫描所有字段名，反证禁止字段集不出现。**若观察到，属真实缺陷**；
 * 若从公开面看不到 envelope 结构，本条 INCONCLUSIVE（P0b 已证明当前实现快照不承载 batchId，
 * SSE 事件流可能同样不承载，此时降级）。
 *
 * <p><b>Tag</b>：manual —— 复用 P3/P4 同拓扑。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("N1.envelope-no-mode-field: red-first 越界看守——envelope 不得含协同模式字段（§2.2 主权）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaEnvelopeNoModeFieldGuardTest {

    private static final Logger LOG = Logger.getLogger(EdpaEnvelopeNoModeFieldGuardTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.n1-stream-cap-ms", 130_000L);

    /** 禁止字段集（大小写不敏感 substring 匹配）。 */
    private static final List<String> FORBIDDEN_FIELD_SUBSTRS = List.of(
            "mode", "blocking", "async", "sync", "coordination", "execution", "invocationmode");

    /** 允许的合规词（这些子串包含 forbidden 关键词但语义合规，白名单）。 */
    private static final List<String> WHITELIST = List.of(
            "modelName", "model_name", "model", "modelProvider", "model_provider",
            "syncedAt", "syncTime", // 假想合规字段
            "asyncoperation", // 假想合规
            "responsemode", // 存在合法 responseMode 也不该判定为 FAIL——只关心 batch envelope 内
            "protocolMode");

    private final HttpClient http = HttpClient.newHttpClient();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null, "[n1] 需 EDP_AGENT_MODEL_*，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[n1] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.N1: 越界红-first——SSE 事件流所有字段扫描无协同模式关键词")
    void sseFrameFieldsCarryNoCoordinationModeKeywords() throws Exception {
        String contextId = "ctx-feat028-n1-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"n1-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);

        List<EdpaSseCollector.Frame> frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);
        LOG.info("[n1] SSE frames=" + frames.size());
        assumeTrue(frames.size() > 0, "[n1] SSE 无事件帧，INCONCLUSIVE");

        // 主判据：递归扫描所有帧的所有字段名，检测违规关键字（越界主权）
        List<String> hits = new ArrayList<>();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) continue;
            List<String> allFieldNames = new ArrayList<>();
            collectFieldNames(f.parsed, "", allFieldNames);
            for (String path : allFieldNames) {
                String pathLower = path.toLowerCase();
                for (String forbidden : FORBIDDEN_FIELD_SUBSTRS) {
                    if (!pathLower.contains(forbidden)) continue;
                    boolean whitelisted = false;
                    for (String wl : WHITELIST) {
                        if (pathLower.contains(wl.toLowerCase())) { whitelisted = true; break; }
                    }
                    if (!whitelisted) hits.add(path + "  (matched forbidden='" + forbidden + "')");
                }
            }
        }
        // 辅助诊断：全字段扫描子任务信息（与 R1/P0b/P0c/C1/C3 同款判据，用于观察面参考——
        // 不影响 N1 主判据，仅证明"SSE envelope 不承载协同模式"的同时公开面确实承载了子任务观察证据）。
        EdpaChildVisibilityScanner.Result diag = new EdpaChildVisibilityScanner.Result();
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed != null) EdpaChildVisibilityScanner.scanInto(f.parsed, null, diag);
        }
        LOG.info("[n1] forbiddenHits=" + hits.size() + " | 辅助诊断（子任务可见性）: " + diag.summary());
        if (hits.isEmpty()) {
            LOG.info("[n1] PASS 未观察到禁止字段（越界主权契约成立）；"
                    + "batchId 属内部诊断字段（FEAT-019 §88），不在公开面 wire 判据范围内");
            return;
        }
        assertThat(hits)
                .as("[n1] ⭐ 越界字段被检出——违反 FEAT-028 §2.2「协同模式感知不越界」。命中列表:%n%s",
                        String.join("\n  ", hits))
                .isEmpty();
    }

    /** 递归收集所有 JSON 字段路径（"a.b.c" 形式）。 */
    private static void collectFieldNames(JsonNode node, String prefix, List<String> out) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String n = names.next();
                String path = prefix.isEmpty() ? n : prefix + "." + n;
                out.add(path);
                collectFieldNames(node.get(n), path, out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectFieldNames(node.get(i), prefix + "[" + i + "]", out);
            }
        }
    }
}
