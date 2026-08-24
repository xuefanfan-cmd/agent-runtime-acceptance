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
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>P0c</b> —— COMPLETED 快照的独立溯源痕迹。
 *
 * <p><b>Spec 依据</b>（testplan §5 P0c）：终态快照的 artifacts 同时承载：
 * ①all-settled 汇总的最终答案（模型单次推理的 final_answer 文本）；
 * ②每个子任务的**可独立追溯痕迹**（能按 `toolCallId` 关联回具体委托与子任务结果）——对齐 FEAT-019 §5.5 独立溯源。
 *
 * <p><b>断言分层</b>（预期 red-first：本用例的层 2 与 P0b 同源缺陷）：
 * <ol>
 *   <li><b>硬 1</b>：达终态 COMPLETED；artifacts 非空且承载可读文本（final_answer 汇总）；</li>
 *   <li><b>硬 2（预期 FAIL，red-first 看守）</b>：终态快照能按 `toolCallId` 独立溯源——满足以下任一即绿：
 *       ①artifacts 中出现多个独立 artifact 且各自关联 toolCallId；
 *       ②history 中承载 tool_call/tool_result 序列携带 toolCallId；
 *       ③metadata 承载 batchId/toolCallId 结构化字段。
 *       2026-08-24 首轮真机（P0b dump）证实：终态 artifacts 只有 1 个纯 final_answer text、无 toolCallId、
 *       history 空、metadata 仅 `_agentcore_terminal:true`——独立溯源面缺失（同 P0b 缺陷）。</li>
 * </ol>
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search、verify。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-028")
@Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P0c.terminal-snapshot-traceability: COMPLETED 快照 all-settled 汇总 + toolCallId 独立溯源")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaTerminalSnapshotTraceabilityTest {

    private static final Logger LOG = Logger.getLogger(EdpaTerminalSnapshotTraceabilityTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final long TERMINAL_TIMEOUT_MS =
            Long.getLong("sit.feat028.p0c-terminal-timeout-ms", 110_000L);
    private static final long POLL_INTERVAL_MS = 2_000L;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private TestConfig config;
    private SutStack searchStack;
    private SutStack verifyStack;
    private SutStack edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null,
                "[p0c] 需 EDP_AGENT_MODEL_* 环境变量，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        String verifyBaseUrl = verifyStack.baseUrl(VERIFY);
        LOG.info("[p0c] search=" + searchBaseUrl + " verify=" + verifyBaseUrl);
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchBaseUrl)
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyBaseUrl)
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p0c] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P0c: 终态 COMPLETED 快照承载 all-settled 汇总；按 toolCallId 独立溯源（red-first 看守）")
    void terminalSnapshotCarriesSummaryAndIndependentTraces() throws Exception {
        String contextId = "ctx-feat028-p0c-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p0c-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HOMOG_PARALLEL);

        HttpResponse<String> ack = post(body);
        assertThat(ack.statusCode()).isEqualTo(200);
        String taskId = firstNonBlank(
                mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null),
                mapper.readTree(ack.body()).path("result").path("id").asText(null));
        assumeTrue(taskId != null, "未取到 taskId，INCONCLUSIVE");
        LOG.info("[p0c] ack.taskId=" + taskId);

        // 轮询达终态，取终态 body 完整分析。
        JsonNode terminalRoot = null;
        String terminal = null;
        long deadline = System.currentTimeMillis() + TERMINAL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> gt = post(String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                    UUID.randomUUID().toString().substring(0, 8), taskId));
            if (gt.statusCode() == 200) {
                JsonNode root = mapper.readTree(gt.body());
                String s = firstNonBlank(
                        root.path("result").path("status").path("state").asText(null),
                        root.path("result").path("task").path("status").path("state").asText(null));
                if (isTerminal(s)) { terminalRoot = root; terminal = s; break; }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        assertThat(terminal)
                .as("[p0c] %d ms 内父任务未达终态", TERMINAL_TIMEOUT_MS).isNotNull();
        assumeTrue("TASK_STATE_COMPLETED".equals(terminal),
                "[p0c] 终态=" + terminal + "（非 COMPLETED），本用例专测 COMPLETED 情形，INCONCLUSIVE");
        LOG.info("[p0c] terminal=" + terminal);

        // wire 事实：GetTask 的 result 是裸 Task；SendMessage ack 才包一层 task。
        JsonNode task = terminalRoot.path("result").path("task").isMissingNode()
                ? terminalRoot.path("result")
                : terminalRoot.path("result").path("task");

        // ── 硬 1：artifacts 非空 + 承载可读文本（final_answer 汇总）──
        JsonNode artifacts = task.path("artifacts");
        assertThat(artifacts.isArray() && artifacts.size() > 0)
                .as("[p0c] 终态 artifacts 必须非空（承载 all-settled 汇总），实测 artifacts=%s", artifacts)
                .isTrue();
        String summaryText = collectText(artifacts);
        assertThat(summaryText)
                .as("[p0c] 终态 artifacts 应承载可读文本（final_answer 汇总，非空）")
                .isNotBlank();
        LOG.info("[p0c] final_answer 前 200 字符 = " + truncate(summaryText, 200));

        // ── 硬 2（red-first 看守）：按 toolCallId 独立溯源 ──
        TraceCheck trace = detectTraceability(task);
        LOG.info(String.format(
                "[p0c] traceability check: artifactsMultiWithToolCallId=%s (n=%d, ids=%s) "
                        + "| historyToolCallIds=%s (n=%d, ids=%s) "
                        + "| metadataStructured=%s (keys=%s)",
                trace.artifactsMultiWithToolCallId, trace.artifactCount, trace.artifactToolCallIds,
                trace.historyHasToolCallIds, trace.historyToolCallCount, trace.historyToolCallIds,
                trace.metadataStructured, trace.metadataKeys));

        // 严格判据 —— 全字段递归扫描（复用 EdpaChildVisibilityScanner，与 R1/P0b 同款）：
        // 避免"只查预设承载位"的过强判定错误。命中 toolCallId 或 childTaskIds/childAgentIds/subStates 之一即算独立溯源可见。
        EdpaChildVisibilityScanner.Result fullScan = EdpaChildVisibilityScanner.scan(terminalRoot, taskId);
        LOG.info(String.format("[p0c] 全字段扫描（终态快照）: %s", fullScan.summary()));

        boolean anyEvidence = trace.anyIndependentTrace()
                || fullScan.anyToolCallId()
                || fullScan.anyChildEvidence();
        assertThat(anyEvidence)
                .as("[p0c] ⭐ 终态 COMPLETED 快照缺失按 toolCallId 独立溯源痕迹——"
                        + "违反 FEAT-019 §5.5 独立溯源与 FEAT-028 §5.5 组合契约。"
                        + "3 种预设承载位任一命中即绿：①artifacts[] 多元素+toolCallId 关联；"
                        + "②history[] 承载 tool_call/tool_result 序列+toolCallId；③metadata 承载 toolCallId 结构化字段。"
                        + "全字段扫描任一命中亦绿（不预设承载位，字段/结构由设计定）。"
                        + "%n3 预设承载位诊断：%s | %s | %s"
                        + "%n全字段扫描：%s"
                        + "%ntask 全量前 800 字符:\n%s",
                        trace.artifactsMultiWithToolCallId, trace.historyHasToolCallIds, trace.metadataStructured,
                        fullScan.summary(),
                        truncate(task.toString(), 800))
                .isTrue();
    }

    // —— helpers ——

    private static final class TraceCheck {
        boolean artifactsMultiWithToolCallId;
        int artifactCount;
        String artifactToolCallIds = "";
        boolean historyHasToolCallIds;
        int historyToolCallCount;
        String historyToolCallIds = "";
        boolean metadataStructured;
        String metadataKeys = "";

        boolean anyIndependentTrace() {
            return artifactsMultiWithToolCallId || historyHasToolCallIds || metadataStructured;
        }
    }

    private TraceCheck detectTraceability(JsonNode task) {
        TraceCheck c = new TraceCheck();
        // 承载位 1：artifacts 多元素，任一 artifact 的 metadata / part.metadata / part.data 携带 toolCallId 关联。
        JsonNode artifacts = task.path("artifacts");
        if (artifacts.isArray()) {
            c.artifactCount = artifacts.size();
            StringBuilder ids = new StringBuilder();
            boolean anyWithToolCallId = false;
            for (JsonNode artifact : artifacts) {
                String tcid = firstNonBlank(
                        artifact.path("metadata").path("toolCallId").asText(null),
                        artifact.path("metadata").path("tool_call_id").asText(null));
                if (tcid == null) {
                    for (JsonNode part : artifact.path("parts")) {
                        tcid = firstNonBlank(
                                part.path("metadata").path("toolCallId").asText(null),
                                part.path("data").path("toolCallId").asText(null));
                        if (tcid != null) break;
                    }
                }
                if (tcid != null) { ids.append(tcid).append(","); anyWithToolCallId = true; }
            }
            c.artifactToolCallIds = ids.toString();
            c.artifactsMultiWithToolCallId = c.artifactCount >= 2 && anyWithToolCallId;
        }
        // 承载位 2：history 承载 tool_call/tool_result 序列携带 toolCallId。
        JsonNode history = task.path("history");
        if (history.isArray()) {
            StringBuilder ids = new StringBuilder();
            for (JsonNode msg : history) {
                for (JsonNode part : msg.path("parts")) {
                    String tcid = firstNonBlank(
                            part.path("toolCallId").asText(null),
                            firstNonBlank(part.path("data").path("toolCallId").asText(null),
                                    part.path("metadata").path("toolCallId").asText(null)));
                    if (tcid != null) { ids.append(tcid).append(","); c.historyToolCallCount++; }
                }
            }
            c.historyToolCallIds = ids.toString();
            c.historyHasToolCallIds = c.historyToolCallCount >= 1;
        }
        // 承载位 3：metadata 结构化字段（batch/toolCall/remote_invocation/delegat/subagent）。
        JsonNode metadata = task.path("metadata");
        if (!metadata.isMissingNode() && !metadata.isNull()) {
            StringBuilder keys = new StringBuilder();
            metadata.fieldNames().forEachRemaining(k -> keys.append(k).append(","));
            String allKeys = keys.toString().toLowerCase();
            c.metadataKeys = keys.toString();
            c.metadataStructured = allKeys.contains("batch") || allKeys.contains("toolcall")
                    || allKeys.contains("remote_invocation") || allKeys.contains("delegat")
                    || allKeys.contains("subagent") || allKeys.contains("invocation");
        }
        return c;
    }

    private static String collectText(JsonNode artifacts) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode artifact : artifacts) {
            for (JsonNode part : artifact.path("parts")) {
                String txt = part.path("text").asText("");
                if (!txt.isBlank()) sb.append(txt).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static boolean isTerminal(String s) {
        return s != null && (s.contains("COMPLETED") || s.contains("FAILED")
                || s.contains("CANCELED") || s.contains("REJECTED"));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(edpStack.baseUrl(EDP_AGENT) + "/a2a"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
