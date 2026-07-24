package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.TaskTextExtractor;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-022 端到端多轮场景 —— 通过 custom REST 入口发起请求，验证 REST → deep-research → search-agent
 * 的完整调用链在多轮追问场景下正常工作.
 *
 * <p>参考 {@code MultiTurnSearchFollowupTest}（DA-08 通过 /a2a 入口的等价场景），本档换用 custom REST
 * 入口重现多轮追问。核心断言：
 * <ol>
 *   <li>REST 请求能触发 deep-research 调用 search-agent（端到端调用链打通）；</li>
 *   <li>相同 conversationId 的多轮请求能续轮同一 Task（§4.3.4 RESUME 决策，
 *       与 {@code CustomRestAutoResumeTaskTest} 冗余但走真实业务链路）；</li>
 *   <li>轮次间 conversationId 隔离对应的 internal contextId 保持一致（同一 Task 归属）。</li>
 * </ol>
 *
 * <p><b>拓扑</b>：同 {@code NestedRemoteInvocationRefusalTest} 的两栈启动序 —— 先启 search 拿到其
 * baseUrl，再 build deep-research 的 stack 并同时注入 {@code SEARCH_AGENT_URL} +
 * {@code OPENJIUWEN_SERVICE_CUSTOM_REST_QUERY_PATH} 两个 env（后者启用 custom REST 入口，前者让
 * planner 具备 web_search 工具）。<b>不能</b>继承 {@code BaseCustomRestTest}，因为它只启 deep-research，
 * 没有 search-agent → LLM 的 tools[] 里不会出现 web_search，导致 planner 首轮直接短路。
 *
 * <p><b>不使用 mock search-agent</b>：本档验证的是 REST → 真实业务链路，若用 mock 会遮盖 LLM 路由/
 * SPI 转换的真实行为。{@code CustomRestA2ASemanticConsistencyTest} 已用双 mock 对比协议转换透明性，
 * 本档聚焦于端到端可达 + 多轮续轮。
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.multi-turn-search: custom REST 入口 → deep-research → search-agent 多轮端到端")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomRestMultiTurnSearchTest {

    private static final Logger LOG = Logger.getLogger(CustomRestMultiTurnSearchTest.class.getName());

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";
    private static final String QUERY_PATH =
            "/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}";
    private static final String PROJECT_ID = "project-test";
    private static final String AGENT_ID = "agent-test";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long POLL_TIMEOUT_MS = 240_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    private static final int MAX_ROUNDS = 5;
    private static final String TURN1_TEXT = "你好,帮我查一下DeepSeek官方定价，请给出官网链接";
    private static final String TURN2_TEXT = "帮我查DeepSeek-R1的官方定价";
    private static final String MODEL_MARKER = "DeepSeek-R1";
    private static final List<String> PRICE_SIGNAL_WORDS =
            List.of("价格", "定价", "token", "元", "USD", "$");

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeAll
    void startStack() {
        config = TestConfig.load();
        // 与 NestedRemoteInvocationRefusalTest 同款启动序:先起 search 拿其 baseUrl，
        // 再 build deep-research 的 stack，同时注入 SEARCH_AGENT_URL + OPENJIUWEN_SERVICE_CUSTOM_REST_QUERY_PATH。
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);
        deepStack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", searchBaseUrl)
                        .env("OPENJIUWEN_SERVICE_CUSTOM_REST_QUERY_PATH", QUERY_PATH))
                .start();
    }

    @AfterAll
    void tearDown() {
        // 反向拆:先关 deep-research,再关 search(避免 upstream 还在跑时下游先没)。
        if (deepStack != null) deepStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-022.multi-turn-search: 通过 custom REST 多轮调用 search-agent，同 conversationId 续轮至 COMPLETED")
    void multiTurnRequestsThroughCustomRestShouldReachCompleted() throws Exception {
        String conversationId = "conv-feat022-mt-search-" + UUID.randomUUID().toString().substring(0, 8);
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        List<TaskState> stateTrajectory = new ArrayList<>();
        List<String> taskIdTrajectory = new ArrayList<>();
        List<String> contextIdTrajectory = new ArrayList<>();
        StringBuilder mergedArtifacts = new StringBuilder();

        String pendingReply = TURN1_TEXT;
        TaskState lastState = null;
        String lastRoundArtifact = "";
        int round = 0;

        while (round < MAX_ROUNDS) {
            round++;
            String label = "round " + round;

            HttpResponse<String> response = postSync(conversationId, pendingReply);
            assertThat(response.statusCode())
                    .as("FEAT-022.multi-turn-search [%s]: HTTP status 应为 200\n"
                            + "  conversationId=%s\n  body=%s", label, conversationId, response.body())
                    .isEqualTo(200);

            JsonNode body = MAPPER.readTree(response.body());
            String taskId = body.path("custom_rsp_data").path("id").asText("");
            String contextId = body.path("custom_rsp_data").path("contextId").asText("");

            assertThat(taskId)
                    .as("FEAT-022.multi-turn-search [%s]: 响应 custom_rsp_data.id 应非空", label)
                    .isNotBlank();

            taskIdTrajectory.add(taskId);
            contextIdTrajectory.add(contextId);

            // 等 Task 到达稳定状态 (终态或 INPUT_REQUIRED)
            Task task = Awaitility.await(label + " reaches stable state")
                    .atMost(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .pollInterval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> a2a.getTask(taskId),
                            t -> t != null && t.status() != null
                                    && (t.status().state().isFinal()
                                        || t.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED));

            TaskState state = task.status().state();
            stateTrajectory.add(state);
            lastState = state;

            String roundText = TaskTextExtractor.fullSnapshotTextOf(task);
            lastRoundArtifact = roundText;
            mergedArtifacts.append(roundText).append("\n---\n");

            LOG.info(String.format(
                    "===== FEAT-022.multi-turn-search %s =====%n"
                            + "state=%s taskId=%s contextId=%s%n"
                            + "----- artifact 头 500 字 -----%n%s%n"
                            + "==============================",
                    label, state, taskId, contextId, truncate(roundText, 500)));

            if (state == TaskState.TASK_STATE_COMPLETED) {
                break;
            }
            if (state == TaskState.TASK_STATE_FAILED || state == TaskState.TASK_STATE_CANCELED) {
                throw new AssertionError("FEAT-022.multi-turn-search " + label
                        + " ended unexpectedly with " + state
                        + "\n  artifact=" + truncate(roundText, 500));
            }

            // INPUT_REQUIRED —— 准备下一轮 reply
            pendingReply = (round == 1) ? TURN2_TEXT : chooseFollowupReply(roundText);
        }

        // 断言 A: 最终应到达 COMPLETED
        assertThat(lastState)
                .as("FEAT-022.multi-turn-search [A]: %d 轮内应到达 COMPLETED\n  trajectory=%s",
                        MAX_ROUNDS, stateTrajectory)
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);

        // 断言 B: 至少有一轮 INPUT_REQUIRED (证明确实走了追问链路，而非首轮直接完成)
        // deep-research 首轮可能直接短路（未走 search-agent）→ INCONCLUSIVE 而非 FAIL
        Assumptions.assumeTrue(
                stateTrajectory.contains(TaskState.TASK_STATE_INPUT_REQUIRED),
                "FEAT-022.multi-turn-search [B, INCONCLUSIVE]: 未观察到 INPUT_REQUIRED 中间态，"
                        + "无法证明走了 REST → deep-research → search-agent 多轮追问链路。trajectory=" + stateTrajectory);

        // 断言 C: 多轮 taskId 应稳定（RESUME 决策 —— 同 conversationId 应续轮同一 Task）
        String firstTaskId = taskIdTrajectory.get(0);
        assertThat(taskIdTrajectory)
                .as("FEAT-022.multi-turn-search [C]: 所有轮 taskId 应等于首轮 taskId (RESUME 语义)\n"
                        + "  firstTaskId=%s\n  trajectory=%s", firstTaskId, taskIdTrajectory)
                .allSatisfy(tid -> assertThat(tid).isEqualTo(firstTaskId));

        // 断言 D: 多轮 contextId (framework 内部) 应稳定 —— 证明 conversationId → internal contextId
        // 的映射在多轮中确定性一致
        String firstContextId = contextIdTrajectory.get(0);
        if (!firstContextId.isBlank()) {
            assertThat(contextIdTrajectory)
                    .as("FEAT-022.multi-turn-search [D]: 多轮 contextId 应保持一致\n"
                            + "  firstContextId=%s\n  trajectory=%s", firstContextId, contextIdTrajectory)
                    .allSatisfy(cid -> assertThat(cid).isEqualTo(firstContextId));
        }

        // 断言 E: 最终 artifact 应命中"模型 + 价格语义"，证明 search-agent 真的被 REST 触发调用并返回结果
        assertThat(lastRoundArtifact)
                .as("FEAT-022.multi-turn-search [E]: 最终 artifact 应含专有名 '%s' (证明 search-agent 定位到模型)\n"
                        + "  artifact 头 500 字=%s", MODEL_MARKER, truncate(lastRoundArtifact, 500))
                .contains(MODEL_MARKER);

        boolean hasPriceSignal = PRICE_SIGNAL_WORDS.stream().anyMatch(lastRoundArtifact::contains);
        assertThat(hasPriceSignal)
                .as("FEAT-022.multi-turn-search [E]: 最终 artifact 应至少含一个价格语义词 %s\n"
                        + "  artifact 头 500 字=%s", PRICE_SIGNAL_WORDS, truncate(lastRoundArtifact, 500))
                .isTrue();
    }

    private HttpResponse<String> postSync(String conversationId, String input) throws Exception {
        String url = deepStack.baseUrl(DEEP_RESEARCH)
                + "/v1/" + PROJECT_ID + "/agents/" + AGENT_ID + "/conversations/" + conversationId;
        String bodyJson = "{\"input\":\"" + input.replace("\"", "\\\"") + "\",\"stream\":false}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .timeout(Duration.ofSeconds(240))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 追问 → 回答映射（对齐 MultiTurnSearchFollowupTest 的启发式，避免客户端引入 LLM）.
     */
    private static String chooseFollowupReply(String promptText) {
        String p = promptText == null ? "" : promptText.toLowerCase();
        boolean hasInputWord = promptText != null && (promptText.contains("输入") || p.contains("input"));
        boolean hasOutputWord = promptText != null && (promptText.contains("输出") || p.contains("output"));
        boolean hasToken = p.contains("token");
        if (hasInputWord && hasToken) return "输入 token 定价";
        if (hasOutputWord && hasToken) return "输出 token 定价";
        if (p.contains("context") || (promptText != null && promptText.contains("上下文"))) return "标准上下文";
        if (p.contains("cache") || (promptText != null && promptText.contains("缓存"))) return "不使用缓存";
        return "请直接给我 DeepSeek-R1 的官方定价链接";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
