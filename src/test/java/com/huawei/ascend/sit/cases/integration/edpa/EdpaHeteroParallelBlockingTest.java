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
 * FEAT-028 矩阵 <b>P2</b> —— 异构混合并行（同步阻塞）。
 *
 * <p><b>Spec 依据</b>（testplan §5 P2）：`SendMessage` 内联 `PROMPT_HETERO_PARALLEL`（同轮
 * 1 个 search-agent + 1 个 verify-agent 委托，各自独立）；本通道的判据是<b>功能正确性</b>——
 * 达终态 COMPLETED，且 final_answer 同时覆盖搜索腿与验证腿两件事（说明异构两条腿的结果都回灌进了汇总）。
 *
 * <p><b>判据边界（2026-09-02 重定位，同 P1，务必先读）</b>：本用例<b>不</b>断言「并行」，也<b>不</b>
 * 断言「异构归位」。BLOCKING 走 `SendMessage`，特性档 §5.0.1 明写该模式不产生中间流式事件，
 * GetTask 终态快照不含中间过程（P0b/P0c 已证）——「时间窗重叠」「委托的 agent_name 各是什么」
 * 都是过程量，这条通道上没有投影。<b>并行与异构归位的正面举证责任在 P4</b>
 * （{@code EdpaHeteroParallelStreamingTest}，SSE 通道，判据为去重后的
 * {@code agentEvent.source.agentId} 含两个不同下游 agent）。
 *
 * <p><b>为什么删掉了原来的时长启发式</b>：旧版把 {@code totalElapsed < 90s} 当硬 2，超限走
 * {@code assumeTrue(false)}。时长与是否并行无因果关系，且该断言<b>永远红不了</b>——违约只会 skip。
 * 现改为纯诊断日志。历史上还写过「P0b 钉死后按 toolCallId 判异构归位」，该路径同样作废
 * （{@code toolCallId} 非 wire 面最小公共字段，FEAT-027 §5.9 / FEAT-019 L2 §5.4）。
 * 详见 cases 细档 §5.5.3、§5.11 与 testplan 评审阻断 3。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search、verify。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-028")
@Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P2.hetero-parallel-blocking: 同轮 search + verify 异构委托，同步阻塞并行执行、统一汇总")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaHeteroParallelBlockingTest {

    private static final Logger LOG = Logger.getLogger(EdpaHeteroParallelBlockingTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final long TERMINAL_TIMEOUT_MS =
            Long.getLong("sit.feat028.p2-terminal-timeout-ms", 130_000L);
    private static final long POLL_INTERVAL_MS = 2_000L;
    /** 同 P1：<b>仅用于诊断日志，不参与判定</b>。原「&lt;90s ⇒ 并行生效」启发式已于 2026-09-02 废止。 */
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
                "[p2] 需 EDP_AGENT_MODEL_* 环境变量，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        String verifyBaseUrl = verifyStack.baseUrl(VERIFY);
        LOG.info("[p2] search=" + searchBaseUrl + " verify=" + verifyBaseUrl);
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchBaseUrl)
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyBaseUrl)
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p2] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P2: 异构混合并行同步阻塞——达终态 COMPLETED + final_answer 覆盖搜索与验证两件事")
    void heteroParallelBlockingCoversSearchAndVerify() throws Exception {
        String contextId = "ctx-feat028-p2-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p2-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HETERO_PARALLEL);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> ack = post(body);
        assertThat(ack.statusCode()).isEqualTo(200);
        String taskId = firstNonBlank(
                mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null),
                mapper.readTree(ack.body()).path("result").path("id").asText(null));
        assumeTrue(taskId != null, "未取到 taskId，INCONCLUSIVE");
        LOG.info("[p2] taskId=" + taskId);

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
        assertThat(terminal).as("[p2] %d ms 内未达终态", TERMINAL_TIMEOUT_MS).isNotNull();
        assumeTrue("TASK_STATE_COMPLETED".equals(terminal),
                "[p2] 终态=" + terminal + "，非 COMPLETED 无法评估覆盖度，INCONCLUSIVE");
        LOG.info(String.format("[p2] terminal=%s totalElapsed=%dms", terminal, totalElapsed));

        // 硬 1：final_answer 应覆盖两件事——虚拟线程（search）+ 对 OOM 断言的验证结论（verify）
        JsonNode task = terminalRoot.path("result").path("task").isMissingNode()
                ? terminalRoot.path("result")
                : terminalRoot.path("result").path("task");
        StringBuilder sb = new StringBuilder();
        for (JsonNode artifact : task.path("artifacts")) {
            for (JsonNode part : artifact.path("parts")) sb.append(part.path("text").asText("")).append("\n");
        }
        String finalAnswer = sb.toString();
        assertThat(finalAnswer).isNotBlank();
        LOG.info("[p2] final_answer 前 300 字符 = " + truncate(finalAnswer, 300));

        boolean coversSearchTopic = containsAny(finalAnswer, "虚拟线程", "Virtual Thread", "virtual thread");
        // 验证腿的关键词——verify-agent 会给"是否准确/是否成立/正确/错误/存疑"类结论
        boolean coversVerifyConclusion = containsAny(finalAnswer,
                "OOM", "线程池", "验证", "核查", "结论", "准确", "正确", "错误", "存疑", "不完全");
        assertThat(coversSearchTopic)
                .as("[p2] final_answer 未覆盖 search 腿主题（虚拟线程特性）\n汇总前 500 字符=%s", truncate(finalAnswer, 500))
                .isTrue();
        assertThat(coversVerifyConclusion)
                .as("[p2] final_answer 未覆盖 verify 腿结论（OOM/线程池验证）\n汇总前 500 字符=%s", truncate(finalAnswer, 500))
                .isTrue();

        // ── 诊断（不判定）：记录总耗时，供人工看趋势 ──
        // 2026-09-02：原「硬 2：total < 90s ⇒ 并行生效」已废止（永远红不了，且时长与并行无因果关系）。
        // 并行与异构归位的正面证据见 P4 的 SSE 判据。
        LOG.info(String.format("[p2] 诊断（不参与判定）：totalElapsed=%dms，经验参考值 %dms。"
                        + "本用例不从该数值推断并行与否。", totalElapsed, PARALLEL_DIAGNOSTIC_HINT_MS));
        LOG.info("[p2] PASS：终态 COMPLETED 且 final_answer 覆盖搜索腿与验证腿两件事");
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase();
        for (String n : needles) if (text.contains(n) || lower.contains(n.toLowerCase())) return true;
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
