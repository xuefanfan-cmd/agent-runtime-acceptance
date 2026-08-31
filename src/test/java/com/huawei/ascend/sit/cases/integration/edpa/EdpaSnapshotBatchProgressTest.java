package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.SnapshotDiffProbe;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>P0b</b> ⭐（首轮真机核心探测使命）—— WORKING 期间快照承载并行进展。
 *
 * <p><b>Spec 依据</b>（testplan §5 P0b）：runtime 必须在事件对客户端可见前或以等价一致性顺序更新
 * TaskStore（FEAT-001 §5.1.8）；独立溯源要求 toolCallId 稳定关联（FEAT-019 §5.5）；综合推论：
 * {@code GetTask(P)} 在父任务 WORKING 期间的快照必须承载多个子任务的独立并行进展。
 *
 * <p><b>承载位可能形态</b>（首轮真机探测目标）：①artifacts[] 多元素+toolCallId 关联；②history[] 多
 * tool_call；③metadata 结构化字段（batch/toolCall/remote_invocation 关键词）；④status.message 过程文字。
 * 满足任一即绿；4 种承载位的具体形态由本用例首轮真机 dump 钉死，写入 cases 细档 §5.1。
 *
 * <p><b>拓扑</b>：与 P0a 相同——分阶段起 search+verify → edp-agent（SutStack.env 不支持 late-bind）。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search、verify。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-028")
@Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P0b.snapshot-batch-progress: WORKING 期间快照承载多子任务并行进展（首轮真机钉死承载位）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaSnapshotBatchProgressTest {

    private static final Logger LOG = Logger.getLogger(EdpaSnapshotBatchProgressTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final long PROBE_INTERVAL_MS =
            Long.getLong("sit.feat028.p0b-probe-interval-ms", 1_000L);
    private static final long TERMINAL_TIMEOUT_MS =
            Long.getLong("sit.feat028.p0b-terminal-timeout-ms", 110_000L);

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
                "[p0b] 需 EDP_AGENT_MODEL_* 环境变量，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        String verifyBaseUrl = verifyStack.baseUrl(VERIFY);
        LOG.info("[p0b] search=" + searchBaseUrl + " verify=" + verifyBaseUrl);
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchBaseUrl)
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyBaseUrl)
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p0b] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P0b ⭐: WORKING 期间快照必须承载多子任务并行进展（首轮真机钉死承载位）")
    void workingSnapshotCarriesParallelSubtaskEvidence() throws Exception {
        String contextId = "ctx-feat028-p0b-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p0b-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);

        HttpResponse<String> ack = post(body);
        assertThat(ack.statusCode()).as("SendMessage 应 200\n%s", ack.body()).isEqualTo(200);
        String taskId = firstNonBlank(
                mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null),
                mapper.readTree(ack.body()).path("result").path("id").asText(null));
        assumeTrue(taskId != null && !taskId.isBlank(), "未取到 taskId，INCONCLUSIVE");
        LOG.info("[p0b] ack.taskId=" + taskId + " probe.interval=" + PROBE_INTERVAL_MS + "ms");

        List<SnapshotDiffProbe.Snapshot> workingSnapshots;
        try (SnapshotDiffProbe probe = SnapshotDiffProbe.start(edpStack.baseUrl(EDP_AGENT), taskId, PROBE_INTERVAL_MS)) {
            String settled = null;
            long deadline = System.currentTimeMillis() + TERMINAL_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2_000);
                List<SnapshotDiffProbe.Snapshot> all = probe.snapshots();
                if (!all.isEmpty()) {
                    String s = all.get(all.size() - 1).stateOrNull(mapper);
                    // P0b 首轮真机 (2026-08-24) 实测：EDPAgent 可能收到子代理结果后走 ask_user 澄清进入
                    // INPUT_REQUIRED；此时父任务已"停下"（非 WORKING），快照探测数据已就位——放宽为
                    // 「settled = terminal OR INPUT_REQUIRED」，避免超时掩盖承载位断言的真实结果。
                    if (isTerminal(s) || (s != null && s.contains("INPUT_REQUIRED"))) {
                        settled = s; break;
                    }
                }
            }
            assertThat(settled)
                    .as("[p0b] %d ms 内父任务既未达终态也未停 INPUT_REQUIRED——WORKING 中间态快照探测无止境",
                            TERMINAL_TIMEOUT_MS).isNotNull();
            LOG.info("[p0b] settled=" + settled + " probe.count=" + probe.count());

            workingSnapshots = new ArrayList<>();
            for (SnapshotDiffProbe.Snapshot snap : probe.snapshots()) {
                String s = snap.stateOrNull(mapper);
                if (s != null && s.contains("WORKING")) workingSnapshots.add(snap);
            }
        }

        assertThat(workingSnapshots)
                .as("[p0b] 未捕获到 WORKING 中间态快照（任务瞬间完成 or 探测频率不足）")
                .isNotEmpty();
        LOG.info("[p0b] WORKING snapshots captured: " + workingSnapshots.size());

        // 承载位判据（4 种预设，保留作为诊断输出与结构面参考）
        boolean anyCarrierMatched = false;
        // 严格判据 —— 全字段递归扫描（复用 EdpaChildVisibilityScanner，与 R1 同款判据）：
        // 不预设承载位，只要在任意字段命中「子 taskId / 子 agentId / 子 state 之一」即算 GetTask 通道
        // 承载子任务信息。用来避免"只查预设 4 位就下通道级结论"的过强判定错误。
        EdpaChildVisibilityScanner.Result fullScan = new EdpaChildVisibilityScanner.Result();
        StringBuilder diagnostics = new StringBuilder();
        for (int i = 0; i < workingSnapshots.size(); i++) {
            SnapshotDiffProbe.Snapshot snap = workingSnapshots.get(i);
            JsonNode result = mapper.readTree(snap.body()).path("result");
            CarrierCheck check = detectCarriers(result);
            diagnostics.append(String.format(
                    "%n---- WORKING snapshot #%d (t=%d) ----%n"
                            + "  status.state = %s%n"
                            + "  carriers.artifactsMulti = %s (n=%d)%n"
                            + "  carriers.historyMulti = %s (n=%d)%n"
                            + "  carriers.metadataStructured = %s (keys=%s)%n"
                            + "  carriers.statusMessage = %s (text=%s)%n"
                            + "  body (前 800 字符) = %s%n",
                    i, snap.timestampMs(), snap.stateOrNull(mapper),
                    check.artifactsMulti, check.artifactsCount,
                    check.historyMulti, check.historyCount,
                    check.metadataStructured, check.metadataKeys,
                    check.statusMessagePresent, check.statusMessageSample,
                    truncate(snap.body(), 800)));
            if (check.anyMatched()) anyCarrierMatched = true;
            EdpaChildVisibilityScanner.scanInto(mapper.readTree(snap.body()), taskId, fullScan);
        }
        LOG.info("[p0b] 4-预设承载位诊断输出:" + diagnostics);
        LOG.info(String.format("[p0b] 全字段扫描（跨 %d 个 WORKING 快照）: %s",
                workingSnapshots.size(), fullScan.summary()));

        // 严格判据：GetTask 通道下全字段扫描应命中子任务信息。
        assertThat(fullScan.anyChildEvidence())
                .as("[p0b] ⭐ GetTask 通道 WORKING 快照全字段递归扫描应命中至少一处子任务信息（子 taskId / 子 agentId /"
                        + " 子 state 之一），实测三项均空——**red-first 承接 issue #93**。全字段扫描不预设承载位，"
                        + "字段名/结构由设计与开发定；本用例修复后自动转 PASS。"
                        + "%n4-预设承载位诊断（辅助）：anyCarrierMatched=%s%s",
                        anyCarrierMatched, diagnostics)
                .isTrue();
    }

    // —— helpers ——

    private static final class CarrierCheck {
        boolean artifactsMulti;
        int artifactsCount;
        boolean historyMulti;
        int historyCount;
        boolean metadataStructured;
        String metadataKeys = "";
        boolean statusMessagePresent;
        String statusMessageSample = "";

        boolean anyMatched() {
            return artifactsMulti || historyMulti || metadataStructured || statusMessagePresent;
        }
    }

    private CarrierCheck detectCarriers(JsonNode result) {
        JsonNode task = result.path("task").isMissingNode() ? result : result.path("task");
        CarrierCheck c = new CarrierCheck();
        JsonNode artifacts = task.path("artifacts");
        if (artifacts.isArray()) {
            c.artifactsCount = artifacts.size();
            c.artifactsMulti = artifacts.size() >= 2;
        }
        JsonNode history = task.path("history");
        if (history.isArray()) {
            int toolInteractions = 0;
            for (JsonNode msg : history) {
                String role = msg.path("role").asText("");
                if (role.contains("TOOL") || role.contains("tool")) toolInteractions++;
                for (JsonNode part : msg.path("parts")) {
                    if (part.has("toolCallId") || part.path("data").has("toolCallId")) toolInteractions++;
                }
            }
            c.historyCount = toolInteractions;
            c.historyMulti = toolInteractions >= 2;
        }
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
        JsonNode statusMsg = task.path("status").path("message");
        if (!statusMsg.isMissingNode()) {
            String sample = "";
            for (JsonNode part : statusMsg.path("parts")) {
                String txt = part.path("text").asText("");
                if (!txt.isBlank()) { sample = txt; break; }
            }
            if (!sample.isBlank()) {
                c.statusMessageSample = truncate(sample, 200);
                c.statusMessagePresent = true;
            }
        }
        return c;
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
