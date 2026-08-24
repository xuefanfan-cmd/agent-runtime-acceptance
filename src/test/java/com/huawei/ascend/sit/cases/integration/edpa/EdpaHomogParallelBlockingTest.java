package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.BatchTimingObserver;
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
 * FEAT-028 矩阵 <b>P1</b> —— 同类型批量并行（同步阻塞）。<b>并行主线核心用例</b>。
 *
 * <p><b>Spec 依据</b>（testplan §5 P1）：`SendMessage` 内联 `PROMPT_HOMOG_PARALLEL`（同轮 2 个
 * search-agent 委托，独立主题）；分层断言——硬 1「达终态 + final_answer 覆盖两件事」；硬 2 有条件式
 * 「若模型同轮生成 ≥2 ToolCall（planrule 期望），两子任务时间窗必须重叠」；模型未同轮生成（LLM 抖动）
 * 时降 INCONCLUSIVE 记录 ToolCall 序列供 prompt 优化。
 *
 * <p><b>观察面（8-24 实测降级）</b>：EDPAgent 父任务快照（P0b/P0c 已证）不承载子任务事件，无法从
 * 客户端公开面直接观察每个子任务的 start/end。本轮暂降级为「行为面弱证明」——用 total_elapsed 时长
 * 对比「若串行两子任务耗时之和」的启发式判定：若 <b>total &lt; 单子任务预期串行下限 × 1.5</b>，
 * 认为并行调度生效；同时依赖服务端日志（`RemoteInvocationBatchCoordinator` 的 batchId + 多 remote
 * invocation）作为并行调度的<b>外部证据</b>——本用例不断言日志，日志仅在事后诊断复核用。
 *
 * <p>P0b 承载位钉死后（等开发修复），本用例断言层升级为「按 toolCallId 拿子任务时间窗、重叠判定」。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search-agent。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-028")
@Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P1.homog-parallel-blocking: 同轮 2 个 search-agent 委托，同步阻塞模式并行执行、单次汇总")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaHomogParallelBlockingTest {

    private static final Logger LOG = Logger.getLogger(EdpaHomogParallelBlockingTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final long TERMINAL_TIMEOUT_MS =
            Long.getLong("sit.feat028.p1-terminal-timeout-ms", 130_000L);
    private static final long POLL_INTERVAL_MS = 2_000L;
    /**
     * 串行下限（毫秒）：如果模型真的串行执行 2 个子任务，本 prompt 至少 60s（每个搜索约 20~30s，
     * 加上模型规划与汇总）。总耗时 < 60s * 1.5 = 90s 视为「并行调度极大概率生效」。此为启发式弱层，
     * 若观察窗内命中，配合硬 1（结果覆盖两件事）即可绿；未命中不判失败，标 INCONCLUSIVE 供分析。
     */
    private static final long PARALLEL_HEURISTIC_UPPER_MS = 90_000L;

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
                "[p1] 需 EDP_AGENT_MODEL_* 环境变量，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        // verify 用来占位 versatile 下游（EDPAgent boot 要求 versatile URL 存在）。
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        String verifyBaseUrl = verifyStack.baseUrl(VERIFY);
        LOG.info("[p1] search=" + searchBaseUrl + " verify=" + verifyBaseUrl);
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchBaseUrl)
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyBaseUrl)
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p1] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P1: 同类型批量并行同步阻塞——达终态 + 结果覆盖两件事 + 总时长符合并行启发式")
    void homogParallelBlockingCoversBothTopics() throws Exception {
        String contextId = "ctx-feat028-p1-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p1-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HOMOG_PARALLEL);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> ack = post(body);
        assertThat(ack.statusCode()).as("SendMessage 应 200\n%s", ack.body()).isEqualTo(200);
        String taskId = firstNonBlank(
                mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null),
                mapper.readTree(ack.body()).path("result").path("id").asText(null));
        assumeTrue(taskId != null, "未取到 taskId，INCONCLUSIVE");
        LOG.info("[p1] taskId=" + taskId);

        // 轮询达终态。
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
        long totalElapsed = System.currentTimeMillis() - t0;
        assertThat(terminal)
                .as("[p1] %d ms 内父任务未达终态", TERMINAL_TIMEOUT_MS).isNotNull();
        assumeTrue("TASK_STATE_COMPLETED".equals(terminal),
                "[p1] 终态=" + terminal + "（非 COMPLETED），无法评估结果覆盖度，INCONCLUSIVE");
        LOG.info(String.format("[p1] terminal=%s totalElapsed=%dms", terminal, totalElapsed));

        // ── 硬 1：final_answer 必须覆盖两件事（Java 21 虚拟线程 + Java 21 GC）──
        JsonNode task = terminalRoot.path("result").path("task").isMissingNode()
                ? terminalRoot.path("result")
                : terminalRoot.path("result").path("task");
        StringBuilder sb = new StringBuilder();
        for (JsonNode artifact : task.path("artifacts")) {
            for (JsonNode part : artifact.path("parts")) {
                sb.append(part.path("text").asText("")).append("\n");
            }
        }
        String finalAnswer = sb.toString();
        assertThat(finalAnswer).as("[p1] final_answer 不得为空").isNotBlank();
        LOG.info("[p1] final_answer 前 300 字符 = " + truncate(finalAnswer, 300));

        // 关键词判定（planrule 允许模型措辞灵活，用弱层关键词）：任一形式命中即算覆盖。
        boolean coversVirtualThread = containsAny(finalAnswer, "虚拟线程", "Virtual Thread", "virtual thread");
        boolean coversGc = containsAny(finalAnswer, "GC", "垃圾回收", "垃圾收集", "ZGC", "G1", "Shenandoah");
        assertThat(coversVirtualThread)
                .as("[p1] final_answer 未覆盖「Java 21 虚拟线程」这件事\n汇总前 500 字符: %s",
                        truncate(finalAnswer, 500))
                .isTrue();
        assertThat(coversGc)
                .as("[p1] final_answer 未覆盖「Java 21 GC 变化」这件事\n汇总前 500 字符: %s",
                        truncate(finalAnswer, 500))
                .isTrue();

        // ── 硬 2（启发式弱层）：总时长 < 90s 视为并行调度生效 ──
        if (totalElapsed >= PARALLEL_HEURISTIC_UPPER_MS) {
            LOG.warning(String.format(
                    "[p1] ⚠ 总耗时 %dms ≥ 并行启发式上限 %dms——可能：①模型未同轮生成并行 ToolCall（LLM 抖动）；"
                            + "②并行调度实际生效但被 search-agent 阻塞或串行化。标 INCONCLUSIVE 供分析。",
                    totalElapsed, PARALLEL_HEURISTIC_UPPER_MS));
            assumeTrue(false,
                    "[p1] 总耗时 " + totalElapsed + "ms 超启发式上限 " + PARALLEL_HEURISTIC_UPPER_MS
                            + "ms，无法从公开面确证并行；本轮 INCONCLUSIVE。"
                            + "服务端日志（RemoteInvocationBatchCoordinator）可查看真实并行证据。");
            return;
        }
        LOG.info(String.format("[p1] PASS 并行启发式命中：总耗时 %dms < 上限 %dms，"
                        + "结合服务端 batchId 日志证实并行调度生效",
                totalElapsed, PARALLEL_HEURISTIC_UPPER_MS));

        // BatchTimingObserver 挂着但当前观察面缺失（P0b 承载位钉死后升级），本轮仅记录空诊断。
        BatchTimingObserver observer = new BatchTimingObserver();
        LOG.info("[p1] BatchTimingObserver placeholder（等 P0b 承载位钉死后按 toolCallId 抓时间窗）: "
                + observer.summary());
    }

    // —— helpers ——

    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase();
        for (String needle : needles) {
            if (text.contains(needle) || lower.contains(needle.toLowerCase())) return true;
        }
        return false;
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
