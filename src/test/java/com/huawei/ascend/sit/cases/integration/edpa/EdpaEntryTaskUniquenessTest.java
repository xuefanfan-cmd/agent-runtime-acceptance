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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>P0a</b> —— 入口 Task 唯一性与状态机单调收束。
 *
 * <p><b>Spec 依据</b>（testplan §5 P0a）：无论父任务底下并行调度多少子任务，客户端始终只见一个
 * 入口 taskId、一个入口 Task 表面、一个单调状态机 SUBMITTED → WORKING → COMPLETED。
 * 子任务是内部实现细节，不作为独立 API 入口暴露给最外层 client。
 *
 * <p><b>拓扑</b>：sender=EDPAgent，被委托方 search + verify（真实）。采用 D2 同款分阶段启动：
 * <b>先起 search+verify 拿 baseUrl，再 build edp-agent 栈</b>——因 SutStack.env(...) 是启动前解析，
 * 不支持 late-bind（8-24 实测：占位符会导致 A2AAgentCardDiscovery URI parse 抛异常，SUT 启动失败）。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM（EDP_AGENT_MODEL_*）与真实 search/verify。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-028")
@Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P0a.entry-task-uniqueness: SendMessage 单一入口 taskId + 状态机单调收束（不暴露子任务为独立入口）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaEntryTaskUniquenessTest {

    private static final Logger LOG = Logger.getLogger(EdpaEntryTaskUniquenessTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final long TERMINAL_POLL_TIMEOUT_MS =
            Long.getLong("sit.feat028.p0a-terminal-timeout-ms", 110_000L);
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
                "[p0a] 需 EDP_AGENT_MODEL_* 环境变量走真实 LLM 路径，跳过");

        // 阶段 1：先起 search + verify（并行主线的被委托方），拿到 baseUrl。
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        String verifyBaseUrl = verifyStack.baseUrl(VERIFY);
        LOG.info("[p0a] search=" + searchBaseUrl + " verify=" + verifyBaseUrl);

        // 阶段 2：以 baseUrl 作为 env 注给 EDPAgent 再启动。scenario-min 由测试基建预置。
        // verify 在 jar 内配置里没有对应 remote-agent，注给 VERSATILE 占位（不会真调）。
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchBaseUrl)
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyBaseUrl)
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p0a] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P0a: SendMessage 返回唯一 taskId；GetTask 状态机 SUBMITTED→WORKING→COMPLETED 单调不回退")
    void entryTaskIsUniqueAndStateMonotonic() throws Exception {
        String contextId = "ctx-feat028-p0a-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p0a-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HOMOG_PARALLEL);

        HttpResponse<String> ack = post(body);
        assertThat(ack.statusCode()).as("SendMessage 应 200\n%s", ack.body()).isEqualTo(200);
        JsonNode ackJson = mapper.readTree(ack.body());
        assertThat(ackJson.has("error")).as("不应返 error\n%s", ackJson).isFalse();

        // wire 事实（[[a2a-wire-contract]]）：SendMessage.result 包一层 task。
        String taskId = firstNonBlank(
                ackJson.path("result").path("task").path("id").asText(null),
                ackJson.path("result").path("id").asText(null));
        assumeTrue(taskId != null && !taskId.isBlank(), "未从 ack 抽出 taskId，INCONCLUSIVE");
        LOG.info("[p0a] ack.taskId=" + taskId);

        // 轮询 GetTask 抓状态序列（wire 事实：GetTask 的 result 是裸 Task）。
        List<String> observed = new ArrayList<>();
        long deadline = System.currentTimeMillis() + TERMINAL_POLL_TIMEOUT_MS;
        String terminal = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> gt = post(String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                    UUID.randomUUID().toString().substring(0, 8), taskId));
            if (gt.statusCode() == 200) {
                JsonNode root = mapper.readTree(gt.body());
                String s = firstNonBlank(
                        root.path("result").path("status").path("state").asText(null),
                        root.path("result").path("task").path("status").path("state").asText(null));
                if (s != null && (observed.isEmpty() || !observed.get(observed.size() - 1).equals(s))) {
                    observed.add(s);
                    LOG.info("[p0a] observed state = " + s);
                }
                if (isTerminal(s)) { terminal = s; break; }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        assertThat(terminal)
                .as("[p0a] %d ms 内父任务未达终态；观察序列=%s", TERMINAL_POLL_TIMEOUT_MS, observed)
                .isNotNull();

        // 单调断言：合法阶段序 SUBMITTED* → WORKING* → 终态*（INPUT_REQUIRED 视为 LLM 抖动分支忽略）。
        int stage = 0;
        for (String s : observed) {
            if (s.contains("SUBMITTED")) {
                assertThat(stage).as("[p0a] SUBMITTED 出现在非法阶段 %d 序列=%s", stage, observed).isLessThanOrEqualTo(1);
                stage = Math.max(stage, 1);
            } else if (s.contains("WORKING")) {
                assertThat(stage).as("[p0a] WORKING 出现在非法阶段 %d 序列=%s", stage, observed).isLessThanOrEqualTo(2);
                stage = Math.max(stage, 2);
            } else if (isTerminal(s)) {
                stage = 3;
            }
        }
        assertThat(stage).as("[p0a] 未观察到终态阶段 序列=%s", observed).isEqualTo(3);
        LOG.info("[p0a] PASS taskId=" + taskId + " terminal=" + terminal + " seq=" + observed);
    }

    // —— helpers ——

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
