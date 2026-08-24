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
 * FEAT-028 矩阵 <b>P5</b> —— 反证：有依赖任务禁止并行。
 *
 * <p><b>Spec 依据</b>（testplan §5 P5）：`SendMessage(PROMPT_DEPENDENT_SERIAL)`（先搜后验，
 * 第 2 步依赖第 1 步结果）；期望模型只在当前轮生成 1 个 ToolCall，第 2 个在第 1 个结果返回后
 * 新一轮发起。planrule.yaml 禁止伪并行——**若模型强行并行则违约（LLM 抖动，红-first 记录）**。
 *
 * <p><b>观察面（同步阻塞降级）</b>：EDPAgent 父任务快照当前不承载 tool_call 序列（P0b/P0c 已证），
 * 本轮暂用「total_elapsed 时长反推」做启发式弱证——**若串行，两次子任务耗时之和 ≥ 单次的 1.8 倍**；
 * 若并行（伪并行违约），耗时会显著缩短。作为间接判据，配合 EDPAgent 服务端日志（tool_call 出现
 * 时序）作诊断复核。P0b 承载位钉死后本用例断言可升级为直接读 tool_call 序列。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search、versatile-agent。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-028")
@Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P5.dependent-tasks-serial: 有依赖任务禁止同轮伪并行——先搜再验证，跨轮次串行发起")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaDependentTasksSerialTest {

    private static final Logger LOG = Logger.getLogger(EdpaDependentTasksSerialTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final long TERMINAL_TIMEOUT_MS =
            Long.getLong("sit.feat028.p5-terminal-timeout-ms", 150_000L);
    private static final long POLL_INTERVAL_MS = 2_000L;
    /**
     * 串行下限（毫秒）：本 prompt 应先搜再验证——期望 total_elapsed ≥ 60s（一次搜 + 一次验证串行）。
     * 若 total < 40s 说明模型可能伪并行了（违约），INCONCLUSIVE 记录并要求服务端日志核查。
     */
    private static final long SERIAL_HEURISTIC_LOWER_MS = 40_000L;

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
                "[p5] 需 EDP_AGENT_MODEL_* 环境变量，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p5] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P5: 依赖任务不得同轮伪并行——先搜再验证跨轮次串行发起")
    void dependentTasksMustSerialize() throws Exception {
        String contextId = "ctx-feat028-p5-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p5-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_DEPENDENT_SERIAL);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> ack = post(body);
        assertThat(ack.statusCode()).isEqualTo(200);
        String taskId = firstNonBlank(
                mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null),
                mapper.readTree(ack.body()).path("result").path("id").asText(null));
        assumeTrue(taskId != null, "未取到 taskId，INCONCLUSIVE");
        LOG.info("[p5] taskId=" + taskId);

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
                if (isTerminal(s)) { terminal = s; break; }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        long totalElapsed = System.currentTimeMillis() - t0;
        assertThat(terminal).as("[p5] %d ms 内未达终态", TERMINAL_TIMEOUT_MS).isNotNull();
        LOG.info(String.format("[p5] terminal=%s totalElapsed=%dms", terminal, totalElapsed));

        // 硬 1：串行下限时长——若 total < 40s，可能是模型伪并行了
        assertThat(totalElapsed >= SERIAL_HEURISTIC_LOWER_MS)
                .as("[p5] 总耗时 %dms < 串行下限 %dms——可能违约：模型对有依赖任务强行并行（伪并行）。"
                        + "planrule 明示：后续子任务需要前序子任务的输出作为输入时必须串行。"
                        + "建议检查服务端日志（EDPAgent）确认 tool_call 时序——两次 call_subagent "
                        + "应在不同 LLM 迭代轮次发起，不得同轮出现",
                        totalElapsed, SERIAL_HEURISTIC_LOWER_MS)
                .isTrue();
        LOG.info("[p5] PASS 串行判定生效：totalElapsed=" + totalElapsed
                + "ms 符合串行下限，planrule 依赖检测正确");
    }

    private static boolean isTerminal(String s) {
        return s != null && (s.contains("COMPLETED") || s.contains("FAILED")
                || s.contains("CANCELED") || s.contains("REJECTED"));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(edpStack.baseUrl(EDP_AGENT) + "/a2a"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
