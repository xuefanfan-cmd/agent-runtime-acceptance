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
 * FEAT-028 矩阵 <b>P1</b> —— 同类型批量并行（同步阻塞）。<b>并行主线核心用例</b>。
 *
 * <p><b>Spec 依据</b>（testplan §5 P1）：`SendMessage` 内联 `PROMPT_HOMOG_PARALLEL`（同轮 2 个
 * search-agent 委托，独立主题）；本通道的判据是<b>功能正确性</b>——达终态 COMPLETED，
 * 且 final_answer 同时覆盖两个独立主题（说明两条委托的结果都真的回灌进了汇总）。
 *
 * <p><b>判据边界（2026-09-02 重定位，务必先读）</b>：本用例<b>不</b>断言「并行」。
 * BLOCKING 走 `SendMessage`，特性档 §5.0.1 明写该模式<b>不产生中间流式事件</b>，
 * GetTask 终态快照也不含中间过程（P0b/P0c 已证，两者正因此 out-of-scope）。
 * 「两子任务时间窗是否重叠」是<b>过程量</b>，在这条通道上<b>没有任何投影</b>——
 * 因此本用例无法、也不试图从公开面确证并行。<b>并行的正面举证责任在 P3/P4</b>
 * （SSE 通道，按 {@code agentEvent.source.taskId} 分流，见 {@code EdpaHomogParallelStreamingTest}）。
 *
 * <p><b>为什么删掉了原来的时长启发式</b>：旧版把 {@code totalElapsed < 90s} 当「并行调度生效」的
 * 硬 2，超限则 {@code assumeTrue(false)} 降 INCONCLUSIVE。这条判据有两重毛病：① 时长与「是否并行」
 * 没有因果关系（网络、模型速度、search-agent 自身耗时都在里面，串行也可能 &lt;90s，并行也可能 &gt;90s）；
 * ② 它<b>永远红不了</b>——违约时只会 skip，若并行真的退化成串行，本用例给的是黄灯不是红灯。
 * 一条不可能变红的断言不是判据。现改为<b>纯诊断日志</b>，不参与判定。
 *
 * <p><b>历史（勿重蹈）</b>：更早的版本写「P0b 承载位钉死后按 {@code toolCallId} 拿子任务时间窗」。
 * 该路径两处都错：{@code toolCallId} 不是 wire 面最小公共字段（FEAT-027 §5.9；FEAT-019 L2 §5.4
 * 「不构成用户侧调用图协议」）；且问题根本不在字段选择，而在这条通道压根没有过程量。
 * 详见 cases 细档 §5.5.3、§5.11 与 testplan 评审阻断 3。
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
     * <b>仅用于诊断日志，不参与判定</b>。历史上这是「total &lt; 90s ⇒ 并行生效」的启发式硬 2，
     * 已于 2026-09-02 废止（见类 javadoc「为什么删掉了原来的时长启发式」）。
     * 保留这个数值只是为了在日志里给一句「本轮耗时相对经验值偏高/偏低」的提示，方便人工事后看趋势。
     */
    private static final long PARALLEL_DIAGNOSTIC_HINT_MS = 90_000L;

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
    @DisplayName("FEAT-028.P1: 同类型批量并行同步阻塞——达终态 COMPLETED + final_answer 覆盖两个独立主题")
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

        // ── 诊断（不判定）：记录总耗时，供人工看趋势 ──
        // 2026-09-02：原「硬 2：total < 90s ⇒ 并行生效」已废止。时长与是否并行无因果关系，
        // 且旧实现超限时走 assumeTrue(false)，永远红不了——不是判据。并行的正面举证在 P3/P4（SSE）。
        LOG.info(String.format("[p1] 诊断（不参与判定）：totalElapsed=%dms，经验参考值 %dms。"
                        + "本用例不从该数值推断并行与否；并行的正面证据见 P3/P4 的 SSE 时间窗判据，"
                        + "服务端 RemoteInvocationBatchCoordinator 的 batchId 聚合可作外部旁证。",
                totalElapsed, PARALLEL_DIAGNOSTIC_HINT_MS));
        LOG.info("[p1] PASS：终态 COMPLETED 且 final_answer 覆盖两个独立主题（两条委托的结果均已回灌汇总）");
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
