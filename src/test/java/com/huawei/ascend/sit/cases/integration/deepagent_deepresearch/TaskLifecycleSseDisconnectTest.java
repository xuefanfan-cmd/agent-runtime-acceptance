package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001 矩阵 <b>E1+E2</b>（缓存与断点续行，§5.1.8）——
 * <b>SSE 断开与 Task 生命周期解耦</b> + <b>断开后快照续查一致性</b>。
 *
 * <p><b>Spec 依据</b>（2026-08-10 版特性档【新增特性】块）：
 * <ul>
 *   <li>§5.1.8/§5.1.9：客户端主动关闭 SSE <b>不得</b>使 Task 转 failed/canceled；runtime 只释放该连接
 *       的消费资源，Agent 继续执行，后续事件继续更新 TaskStore；</li>
 *   <li>§5.1.8：断开后 {@code GetTask} 返回不早于已确认可见事件的 Task 快照——不重开事件流、
 *       不重新执行 Agent。</li>
 * </ul>
 *
 * <p><b>形态</b>：SUT=search 单节点（终态确定性可达）。原生 SSE 客户端发
 * {@code SendStreamingMessage}，读到首个携 taskId 的事件（并确认观察到 WORKING 类过程事件）后
 * <b>粗暴关闭连接</b>；随后全程只用 {@code GetTask}：
 * <ol>
 *   <li><b>E1 硬断言</b>：断开后即时快照非 FAILED/CANCELED；并在窗口内收束到自然终态
 *       （search 该 prompt 口径为 COMPLETED）；</li>
 *   <li><b>E2 硬断言</b>：断开后首个快照不早于已见事件（state 不回退到 SUBMITTED）；终态快照
 *       携带 artifacts（TaskStore 持续被更新的证据）。</li>
 * </ol>
 *
 * <p><b>Tag</b>：manual —— 依赖本地 search jar + 真实 LLM/检索。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.sse-disconnect-lifecycle (E1+E2): SSE 断开不改 Task 生命周期；断开后 GetTask 快照续查一致")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskLifecycleSseDisconnectTest {

    private static final Logger LOG = Logger.getLogger(TaskLifecycleSseDisconnectTest.class.getName());
    private static final String SEARCH = "search";
    private static final String USER_INPUT = "你好,到deepseek官网查询下DeepSeek-V3 上下文长度多少 tokens。";

    private static final long SSE_FIRST_EVENT_CAP_MS = 45_000;
    private static final long TERMINAL_POLL_TIMEOUT_MS = 100_000;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private TestConfig config;
    private SutStack searchStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a.property("openjiuwen.demo.search-agent.api-key",
                        System.getenv("LLM_API_KEY")))
                .start();
        LOG.info("[e1e2-disconnect] search ready at " + searchStack.baseUrl(SEARCH));
    }

    @AfterAll
    void tearDown() {
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-001.sse-disconnect (E1+E2): 中途断开 SSE 后 Task 不转 failed/canceled、快照不回退并收束终态")
    void abruptSseDisconnectKeepsTaskAliveAndSnapshotConsistent() throws Exception {
        String streamBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"e1-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"ctx-e1-%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                UUID.randomUUID().toString().substring(0, 8), USER_INPUT);

        // 建 SSE，抓 taskId 与已见状态，随后粗暴断开（close InputStream = 客户端主动断连）。
        String taskId = null;
        String lastSeenState = null;
        int framesSeen = 0;
        HttpRequest req = HttpRequest.newBuilder(URI.create(searchStack.baseUrl(SEARCH) + "/a2a"))
                .timeout(Duration.ofMillis(SSE_FIRST_EVENT_CAP_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(streamBody)).build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assumeTrue(resp.statusCode() == 200, "SendStreamingMessage 非 200，INCONCLUSIVE");
        long cap = System.currentTimeMillis() + SSE_FIRST_EVENT_CAP_MS;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < cap && (line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                framesSeen++;
                JsonNode frame = mapper.readTree(line.substring(5).trim());
                JsonNode result = frame.path("result");
                // wire 事实（2026-08-17 裸探）：流式事件为 result.statusUpdate / result.artifactUpdate
                // （StreamingEventKind protobuf JSON 名），taskId 在事件体内。
                if (taskId == null) {
                    String a = result.path("statusUpdate").path("taskId").asText(null);
                    if (a == null) a = result.path("artifactUpdate").path("taskId").asText(null);
                    if (a == null) a = result.path("task").path("id").asText(null);
                    if (a == null) a = result.path("id").asText(null);
                    taskId = a;
                }
                String st = firstNonBlank(
                        result.path("statusUpdate").path("status").path("state").asText(null),
                        firstNonBlank(result.path("task").path("status").path("state").asText(null),
                                result.path("status").path("state").asText(null)));
                if (st != null) lastSeenState = st;
                // 拿到 taskId 且看到 WORKING 类过程事件即断开——不等自然终态。
                if (taskId != null && lastSeenState != null && lastSeenState.contains("WORKING")) break;
            }
        } // try-with-resources close = 粗暴断开
        long disconnectAt = System.currentTimeMillis();
        LOG.info(String.format("[e1e2-disconnect] disconnected after %d frames, taskId=%s, lastSeen=%s",
                framesSeen, taskId, lastSeenState));
        assumeTrue(taskId != null, "断开前未取到 taskId，INCONCLUSIVE");
        assumeTrue(lastSeenState != null && lastSeenState.contains("WORKING"),
                "断开前未观察到 WORKING（lastSeen=" + lastSeenState + "），E2 基准缺失，INCONCLUSIVE");

        // E1 即时面：断开不得直接触发 failed/canceled。
        String immediate = getState(taskId);
        LOG.info("[e1e2-disconnect] immediate snapshot after disconnect = " + immediate);
        assertThat(immediate)
                .as("§5.1.8: SSE 断开不得使 Task 转 failed/canceled，断开后即时快照=%s", immediate)
                .isNotNull()
                .doesNotContain("FAILED").doesNotContain("CANCELED");

        // E2 一致性面：快照不早于已见事件（已见 WORKING → 不得回退 SUBMITTED）。
        assertThat(immediate)
                .as("§5.1.8: 快照不得早于已确认可见事件——断开前已见 WORKING，快照却为 %s", immediate)
                .doesNotContain("SUBMITTED");

        // E1 收束面：Agent 继续执行至自然终态（本 prompt 口径 COMPLETED）。
        String terminal = null;
        long deadline = System.currentTimeMillis() + TERMINAL_POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String s = getState(taskId);
            if (s != null && (s.contains("COMPLETED") || s.contains("FAILED")
                    || s.contains("CANCELED") || s.contains("REJECTED"))) { terminal = s; break; }
            Thread.sleep(2_000);
        }
        assertThat(terminal)
                .as("§5.1.8: 断开后 Agent 应继续执行并收束终态；%d ms 内未达", TERMINAL_POLL_TIMEOUT_MS)
                .isNotNull();
        assertThat(terminal)
                .as("§5.1.8: 断开不得导致 failed/canceled 类终态（本场景无故障注入），实测 %s", terminal)
                .contains("COMPLETED");
        LOG.info(String.format("[e1e2-disconnect] terminal=%s at t+%d ms after disconnect",
                terminal, System.currentTimeMillis() - disconnectAt));

        // E2 收束面：终态快照携带 artifacts（断开后 TaskStore 持续被更新的证据）。
        // wire 事实：GetTask 的 result 是裸 Task——先试 result.task 再回退 result。
        JsonNode gtRoot = getTask(taskId);
        JsonNode task = gtRoot.path("result").path("task");
        if (task.isMissingNode() || task.path("artifacts").isMissingNode()) {
            task = gtRoot.path("result");
        }
        assertThat(task.path("artifacts").isArray() && task.path("artifacts").size() > 0)
                .as("§5.1.8: 断开后事件应继续更新 TaskStore——终态快照应携带 artifacts\ntask=%s",
                        task.toString().substring(0, Math.min(400, task.toString().length())))
                .isTrue();
    }

    // —— helpers ——

    private String getState(String taskId) throws Exception {
        JsonNode root = getTask(taskId);
        return firstNonBlank(
                root.path("result").path("task").path("status").path("state").asText(null),
                root.path("result").path("status").path("state").asText(null));
    }

    private JsonNode getTask(String taskId) throws Exception {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                        URI.create(searchStack.baseUrl(SEARCH) + "/a2a"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(String.format(
                                "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                                UUID.randomUUID().toString().substring(0, 8), taskId))).build(),
                HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }
}
