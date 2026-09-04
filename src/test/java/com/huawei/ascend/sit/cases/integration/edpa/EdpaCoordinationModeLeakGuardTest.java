package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>N1</b> —— 协同模式字段泄漏回归看守（negative guard）+ 批量语义正向判据。
 *
 * <p><b>判据定位（2026-09-02 重定位，务必先读）</b>：本用例<b>不</b>验证 FEAT-028 §2.2
 * 「agent-core 不在 batch interrupt envelope 中重复声明协同模式」。原因是该 envelope 按 FEAT-019 §3
 * 外部接口表属 <b>core → runtime adapter</b> 内部对象，且 §3 抬头明示 FEAT-019
 * 「不固定 Java 类名、包路径、内部 DTO 名称或具体序列化字段」——它在任何 A2A wire 面上都没有完整投影，
 * 本仓黑盒/灰盒都拿不到「envelope 的全部字段」，因此<b>「不含某字段」在 SIT 层不可判定</b>。
 * 旧版本在 SSE 上扫黑名单、命中集恒为空、必然 PASS，属结构性假绿，已废止
 * （原类名 {@code EdpaEnvelopeNoModeFieldGuardTest}，名字本身就误称了它并未观察到的 envelope）。
 *
 * <p>§2.2 的正面举证责任在 agent-core 侧白盒单测。本用例保留下来的价值是<b>回归看守</b>：
 * 万一将来实现把协同模式字段泄漏到可观察面上，这里会红。
 *
 * <p><b>绿灯的确切含义</b>：「在 SSE 事件流 / 终态快照 / edp-agent 进程日志三个面上，
 * 未出现已知命名形态的协同模式字段」。<b>它不构成 §2.2 合规的证据</b>——本判据本质是黑名单，
 * 而 FEAT-019 §3 明说字段命名不固定，黑名单永远无法证明「不存在」。
 *
 * <p><b>看守自检</b>：扫描逻辑抽在 {@link EdpaModeFieldScanner}，由不打 {@code manual} 标签的
 * {@link EdpaModeFieldScannerSelfTest} 每轮构建验证其可开火性。没有那条自检，本用例的恒绿无法自证。
 *
 * <p><b>批量语义正向判据</b>：{@link #batchExpressesBatchAndMemberList()} 从 edp-agent 进程日志的
 * {@code RemoteInvocationBatchCoordinator} 状态行按 {@code batchId} 聚合，正面实证 FEAT-019 §3
 * 「至少能表达批次、成员列表」。注意它证明的是「批次与成员可表达」，同样不证明「不含协同模式」。
 *
 * <p><b>观察面性质</b>：进程日志属灰盒，testplan §2 的黑盒声明对本条有显式例外条款。
 * 本仓已有先例（{@code RegistryRouteQueryBlackboxTest} / {@code RedisStandaloneBehaviorTest} 等，
 * 均经 {@code ManagedSutInstance.logFile()} 读取）。日志格式非契约，因此日志面缺失时判 INCONCLUSIVE
 * 而非 FAIL。
 *
 * <p><b>Tag</b>：manual —— 复用 P3/P4 同拓扑，需真实 LLM 与 ~130s 流式采集。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("N1.coordination-mode-leak-guard: 协同模式字段泄漏回归看守 + 批量语义正向判据")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaCoordinationModeLeakGuardTest {

    private static final Logger LOG = Logger.getLogger(EdpaCoordinationModeLeakGuardTest.class.getName());
    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";
    private static final long STREAM_CAP_MS =
            Long.getLong("sit.feat028.n1-stream-cap-ms", 130_000L);
    /** 流关闭后等待 coordinator 日志落盘的上限。 */
    private static final long LOG_SETTLE_MS = 5_000L;

    /** coordinator 逐项状态行的三元组（实测形态见 cases §5.9）。 */
    private static final Pattern COORDINATOR_LINE = Pattern.compile(
            "batchId=([0-9a-fA-F-]+)\\s+toolCallId=(\\S+)\\s+remoteAgentId=(\\S+)");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private TestConfig config;
    private SutStack searchStack, verifyStack, edpStack;

    /** 单次真机运行的证据，两条判据共享（避免把 ~130s 的流跑两遍）。 */
    private List<EdpaSseCollector.Frame> frames = List.of();
    /**
     * <b>流结束后</b>的 GetTask 快照（不保证是终态）：流可能因 {@link #STREAM_CAP_MS} 截断而任务仍 WORKING。
     * 它只是判据① 的第二个<b>扫描面</b>，多扫一面就多一分捕获泄漏的机会；
     * 取不到或非终态都<b>不影响判定口径</b>（黑名单看守的绿灯本就只代表"扫过的面上没出现"）。
     * 实际 state 会记进日志，便于事后判断本轮覆盖了多少。
     */
    private JsonNode postStreamSnapshot;
    private String snapshotState = "(未取到)";
    private String appendedLog = "";

    @BeforeAll
    void startStackAndRunOnce() throws Exception {
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

        // 日志由 ProcessLauncher 以 appendTo 方式累积到 <logDir>/<agent>/stdout.log，
        // 跨轮次不清空，必须记录偏移量后只读本轮新增段。
        // logFile 可能取不到（remote-only stack 下 SutStack.managedInstance 返回 null）——
        // 那属「日志观察面缺失」，必须降级为 INCONCLUSIVE，不能让 @BeforeAll 抛 NPE 把整类判成 ERROR。
        Path logFile = edpAgentLogFile();
        long offset = (logFile != null && Files.exists(logFile)) ? Files.size(logFile) : 0L;

        String contextId = "ctx-feat028-n1-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"n1-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);

        frames = EdpaSseCollector.collect(
                http, edpStack.baseUrl(EDP_AGENT) + "/a2a", body, STREAM_CAP_MS);

        String taskId = firstTaskId(frames);
        if (taskId != null) {
            postStreamSnapshot = getTask(taskId);
            if (postStreamSnapshot != null) {
                String state = postStreamSnapshot.path("result").path("status").path("state").asText("");
                snapshotState = state.isBlank() ? "(无 status.state)" : state;
            }
        }

        // 流关闭后 coordinator 行可能仍在落盘，短暂轮询。
        long deadline = System.currentTimeMillis() + LOG_SETTLE_MS;
        do {
            appendedLog = readLogAfter(logFile, offset);
            if (COORDINATOR_LINE.matcher(appendedLog).find()) {
                break;
            }
            Thread.sleep(500);
        } while (System.currentTimeMillis() < deadline);

        LOG.info("[n1] contextId=" + contextId + " SSE 帧=" + frames.size()
                + " 流后快照=" + (postStreamSnapshot != null ? "已取(state=" + snapshotState + ")" : "未取")
                + " 本轮新增日志=" + appendedLog.length() + " 字节");
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.N1: 回归看守——SSE/终态快照/进程日志三面均无协同模式字段泄漏")
    void noCoordinationModeLeakOnObservableSurfaces() {
        assumeTrue(!frames.isEmpty(), "[n1] SSE 无事件帧，INCONCLUSIVE");

        List<String> hits = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            JsonNode parsed = frames.get(i).parsed;
            if (parsed != null) {
                hits.addAll(EdpaModeFieldScanner.scanJson(parsed, "sse[" + i + "]"));
            }
        }
        if (postStreamSnapshot != null) {
            hits.addAll(EdpaModeFieldScanner.scanJson(postStreamSnapshot, "snapshot"));
        }
        hits.addAll(EdpaModeFieldScanner.scanLogKeys(appendedLog, "edp-agent.log"));

        LOG.info("[n1] 扫描域：SSE " + frames.size() + " 帧 + 流后快照 "
                + (postStreamSnapshot != null ? "1(state=" + snapshotState + ")" : "0") + " + 日志 "
                + appendedLog.length() + " 字节；命中=" + hits.size());

        assertThat(hits)
                .as("[n1] 观察面上检出协同模式字段——实现可能已把执行策略泄漏到可观察面。需人工定性："
                        + "若确属合法字段，加入 EdpaModeFieldScanner.WHITELIST_LEAF 并注明出处；"
                        + "若确属越界，按 FEAT-028 §2.2 提缺陷。命中列表：%n  %s",
                        String.join("\n  ", hits))
                .isEmpty();
    }

    @Test
    @DisplayName("FEAT-028.N1-batch: 正向判据——batch 可表达批次与成员列表（FEAT-019 §3）")
    void batchExpressesBatchAndMemberList() {
        Matcher m = COORDINATOR_LINE.matcher(appendedLog);
        Map<String, Set<String>> callsByBatch = new LinkedHashMap<>();
        Map<String, Set<String>> agentsByBatch = new LinkedHashMap<>();
        while (m.find()) {
            callsByBatch.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(2));
            agentsByBatch.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(3));
        }
        assumeTrue(!callsByBatch.isEmpty(),
                "[n1-batch] 本轮日志未出现 RemoteInvocationBatchCoordinator 状态行，无观察面，"
                        + "INCONCLUSIVE（不判 FAIL：日志格式非契约，实现可自由变更）");

        int maxMembers = callsByBatch.values().stream().mapToInt(Set::size).max().orElse(0);
        LOG.info("[n1-batch] 批次数=" + callsByBatch.size()
                + " 每批成员数=" + callsByBatch.values().stream().map(Set::size).toList()
                + " 各批 remoteAgentId=" + agentsByBatch.values());

        assertThat(maxMembers)
                .as("[n1-batch] FEAT-019 §3 要求 batch interrupt envelope「至少能表达批次、成员列表」，"
                        + "但本轮所有批次均只含 1 项，未观察到批量语义。各批明细：%s", callsByBatch)
                .isGreaterThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // 取证辅助
    // ---------------------------------------------------------------------

    private Path edpAgentLogFile() {
        Object instance = edpStack.managedInstance(EDP_AGENT);
        if (!(instance instanceof ManagedSutInstance managed)) {
            LOG.warning("[n1] 取不到 edp-agent 的 managed 实例（remote-only stack？），日志观察面缺失："
                    + "判据① 少扫一面、判据② 将降 INCONCLUSIVE");
            return null;
        }
        return managed.logFile();
    }

    private static String readLogAfter(Path path, long offset) throws Exception {
        if (path == null || !Files.exists(path)) {
            return "";
        }
        byte[] bytes = Files.readAllBytes(path);
        int start = Math.toIntExact(Math.min(offset, bytes.length));
        return new String(Arrays.copyOfRange(bytes, start, bytes.length), StandardCharsets.UTF_8);
    }

    /** wire 事实见 [[a2a-wire-contract]]：流式帧为 result.statusUpdate / result.artifactUpdate。 */
    private static String firstTaskId(List<EdpaSseCollector.Frame> frames) {
        for (EdpaSseCollector.Frame f : frames) {
            if (f.parsed == null) {
                continue;
            }
            JsonNode result = f.parsed.path("result");
            String id = result.path("statusUpdate").path("taskId").asText(null);
            if (id == null || id.isBlank()) {
                id = result.path("artifactUpdate").path("taskId").asText(null);
            }
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return null;
    }

    private JsonNode getTask(String taskId) {
        try {
            String body = String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                    UUID.randomUUID().toString().substring(0, 8), taskId);
            HttpRequest req = HttpRequest.newBuilder(URI.create(edpStack.baseUrl(EDP_AGENT) + "/a2a"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? mapper.readTree(resp.body()) : null;
        } catch (Exception e) {
            LOG.warning("[n1] GetTask 取快照失败（少一个扫描面，不影响看守主判据）: " + e.getMessage());
            return null;
        }
    }
}
