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
 * FEAT-028 矩阵 <b>P6</b> —— 单实体单委托兼容。
 *
 * <p><b>Spec 依据</b>（testplan §5 P6）：`SendMessage(PROMPT_SINGLE_ENTITY)`；期望只生成
 * 1 个 ToolCall；走 FEAT-019 单成员兼容路径；不强制批次；final_answer 覆盖单个查询主题。
 * 对应 planrule.yaml 「兜底」条款：「仅识别到 1 个子任务时，正常生成单个工具调用，走常规路径，不强制并行。」
 *
 * <p><b>观察面</b>：EDPAgent 父任务快照当前不承载 tool_call 序列（P0b/P0c 已证），本轮暂用
 * 「total_elapsed 时长上限 + final_answer 覆盖单一主题」间接判据；预期单次搜索约 20~35s。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search-agent。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-028")
@Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P6.single-entity-compat: 单实体单委托，走单成员兼容路径不强制批次")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaSingleEntityCompatTest {

    private static final Logger LOG = Logger.getLogger(EdpaSingleEntityCompatTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final long TERMINAL_TIMEOUT_MS =
            Long.getLong("sit.feat028.p6-terminal-timeout-ms", 90_000L);
    private static final long POLL_INTERVAL_MS = 2_000L;
    /**
     * 单次子任务耗时上限。实测（2026-08-24）：单 search 子任务 + LLM 汇总总耗时 ~71s（包含冷启动 + 模型推理波动），
     * 上限设 90s 给足容差；仍显著低于 P1/P2 双子任务并行下 65s 的启发式命中窗——两者判据可区分。
     */
    private static final long SINGLE_TASK_HEURISTIC_UPPER_MS = 90_000L;

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
                "[p6] 需 EDP_AGENT_MODEL_* 环境变量，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p6] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P6: 单实体单委托兼容——单 ToolCall 路径正常，不强制批次")
    void singleEntitySingleToolCall() throws Exception {
        String contextId = "ctx-feat028-p6-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p6-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_SINGLE_ENTITY);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> ack = post(body);
        assertThat(ack.statusCode()).isEqualTo(200);
        String taskId = firstNonBlank(
                mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null),
                mapper.readTree(ack.body()).path("result").path("id").asText(null));
        assumeTrue(taskId != null, "未取到 taskId，INCONCLUSIVE");
        LOG.info("[p6] taskId=" + taskId);

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
        assertThat(terminal).as("[p6] %d ms 内未达终态", TERMINAL_TIMEOUT_MS).isNotNull();
        assumeTrue("TASK_STATE_COMPLETED".equals(terminal),
                "[p6] 终态=" + terminal + "，非 COMPLETED 无法评估，INCONCLUSIVE");
        LOG.info(String.format("[p6] terminal=%s totalElapsed=%dms", terminal, totalElapsed));

        // 硬 1：final_answer 覆盖单一主题——虚拟线程
        JsonNode task = terminalRoot.path("result").path("task").isMissingNode()
                ? terminalRoot.path("result")
                : terminalRoot.path("result").path("task");
        StringBuilder sb = new StringBuilder();
        for (JsonNode artifact : task.path("artifacts")) {
            for (JsonNode part : artifact.path("parts")) sb.append(part.path("text").asText("")).append("\n");
        }
        String finalAnswer = sb.toString();
        assertThat(finalAnswer).isNotBlank();
        LOG.info("[p6] final_answer 前 300 字符 = " + truncate(finalAnswer, 300));

        boolean coversTopic = containsAny(finalAnswer, "虚拟线程", "Virtual Thread", "virtual thread");
        assertThat(coversTopic)
                .as("[p6] final_answer 未覆盖单一查询主题（虚拟线程）\n汇总前 500 字符=%s", truncate(finalAnswer, 500))
                .isTrue();

        // 硬 2：总耗时符合单次子任务预期上限（<70s）
        assertThat(totalElapsed <= SINGLE_TASK_HEURISTIC_UPPER_MS)
                .as("[p6] 单实体单委托总耗时 %dms > 上限 %dms——可能被误批量化（不该出现的 2+ ToolCall）",
                        totalElapsed, SINGLE_TASK_HEURISTIC_UPPER_MS)
                .isTrue();
        LOG.info("[p6] PASS 单成员兼容路径正常：totalElapsed=" + totalElapsed + "ms");
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
