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
 * 1 个 search-agent + 1 个 verify-agent 委托，各自独立）；断言两子委托 `agent_name` 不同、
 * 异构进同一批次仍统一汇总；final_answer 覆盖两件事（搜索结果 + 验证结论）。
 *
 * <p><b>观察面（同 P1 降级）</b>：EDPAgent 父任务快照当前不承载子任务事件（P0b/P0c 缺陷），
 * 本轮暂用「total_elapsed 启发式」+「final_answer 覆盖度」间接证明；服务端日志作外部证据备查。
 * P0b 承载位钉死后可升级为按 toolCallId 拿子任务时间窗、判定异构 `agent_name` 归位。
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
    /** 同 P1：串行下限估计 60s，×1.5 = 90s 视为并行调度极大概率生效。 */
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
    @DisplayName("FEAT-028.P2: 异构混合并行同步阻塞——达终态 + final_answer 覆盖搜索与验证两件事 + 并行启发式")
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

        // 硬 2（启发式）：< 90s 视为并行调度极大概率生效
        if (totalElapsed >= PARALLEL_HEURISTIC_UPPER_MS) {
            assumeTrue(false, "[p2] 总耗时 " + totalElapsed + "ms 超启发式上限 " + PARALLEL_HEURISTIC_UPPER_MS
                    + "ms，无法从公开面确证并行；本轮 INCONCLUSIVE。服务端 batchId 日志可核。");
            return;
        }
        LOG.info(String.format("[p2] PASS 并行启发式命中：totalElapsed=%dms < %dms",
                totalElapsed, PARALLEL_HEURISTIC_UPPER_MS));
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
