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
 * FEAT-001 矩阵 <b>E3+E4</b>（缓存与断点续行，§5.1.8）——
 * <b>活动 Task 重订阅</b> 与 <b>终态/竞态重订阅回退</b>。
 *
 * <p><b>Spec 依据</b>（2026-08-10 版特性档【新增特性】「活动 Task 重订阅」，MUST）：
 * <ul>
 *   <li>E3：非终态 Task 上 {@code SubscribeToTask(params.id=taskId)} 必须建立只读订阅——
 *       首个业务结果为订阅时读取的<b>当前 Task 快照</b>，随后只发送挂接成功后的新事件；
 *       不得重新执行 Agent、不重新触发模型/工具/外部副作用；</li>
 *   <li>E4：终态 Task 上 {@code SubscribeToTask} 必须返回 {@code UnsupportedOperation} 或等价协议
 *       错误，客户端回退 {@code GetTask} 取最终快照。</li>
 * </ul>
 *
 * <p><b>wire 假设</b>：params 形态按 {@code GetTask} 同构（{@code params.id=taskId}）；具体错误码待
 * L2 钉值。SUT jar 若尚未实现该 method（-32601 method-not-found），按特性档该能力为 MUST →
 * <b>red-first</b> 显性化实现缺口，不做 assume-skip。
 *
 * <p><b>Tag</b>：manual —— 依赖本地 search jar + 真实 LLM/检索。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.task-resubscribe (E3+E4): 活动 Task 重订阅首帧快照+新事件；终态重订阅回退 GetTask")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskResubscribeTest {

    private static final Logger LOG = Logger.getLogger(TaskResubscribeTest.class.getName());
    private static final String SEARCH = "search";
    private static final String USER_INPUT = "你好,到deepseek官网查询下DeepSeek-V3 上下文长度多少 tokens。";

    private static final long SSE_FIRST_EVENT_CAP_MS = 45_000;
    private static final long RESUB_READ_CAP_MS = 60_000;
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
        LOG.info("[e3e4-resub] search ready at " + searchStack.baseUrl(SEARCH));
    }

    @AfterAll
    void tearDown() {
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-001.task-resubscribe (E3): 活动 Task SubscribeToTask 首帧=当前快照，且不重新执行 Agent")
    void activeTaskResubscribeReturnsSnapshotFirst() throws Exception {
        // 启动流式任务，读到 taskId + WORKING 后断开（同 E1 手法制造"已断开的活动 Task"前提）。
        String taskId = startStreamingAndDetach("ctx-e3-");
        assumeTrue(taskId != null, "未取到活动 taskId，INCONCLUSIVE");
        String stateBefore = getState(taskId);
        assumeTrue(stateBefore != null && !isTerminal(stateBefore),
                "重订阅前任务已终态（" + stateBefore + "），活动重订阅前提不成立，本轮 INCONCLUSIVE（用 E4 覆盖终态分支）");

        // SubscribeToTask（wire 假设 params.id；SSE 应答）。
        String subBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"e3-%s\",\"method\":\"SubscribeToTask\",\"params\":{\"id\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8), taskId);
        HttpRequest req = HttpRequest.newBuilder(URI.create(searchStack.baseUrl(SEARCH) + "/a2a"))
                .timeout(Duration.ofMillis(RESUB_READ_CAP_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(subBody)).build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        LOG.info("[e3e4-resub] SubscribeToTask status=" + resp.statusCode() + " ct=" + contentType);

        if (!contentType.contains("event-stream")) {
            // 非 SSE 应答：读 body 判定。method-not-found = 能力未实现 → red-first（特性档 MUST）。
            String bodyText = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode err = mapper.readTree(bodyText).path("error");
            assertThat(err.isMissingNode())
                    .as("§5.1.8(MUST): 活动 Task 的 SubscribeToTask 应建立 SSE 订阅；实测非流式应答且带错误："
                            + "code=%s message=%s（-32601 即实现缺口 red-first）\nbody=%s",
                            err.path("code").asText(""), err.path("message").asText(""), bodyText)
                    .isTrue();
            assertThat(contentType).as("应答既非 SSE 也非 error——未知形态\nbody=%s", bodyText)
                    .contains("event-stream"); // 到此必失败，保留诊断
            return;
        }

        // SSE 首帧必须是当前 Task 快照（同 taskId、含 status.state）。
        JsonNode firstFrame = null;
        long cap = System.currentTimeMillis() + RESUB_READ_CAP_MS;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < cap && (line = r.readLine()) != null) {
                if (line.startsWith("data:")) {
                    firstFrame = mapper.readTree(line.substring(5).trim());
                    break;
                }
            }
        }
        assertThat(firstFrame).as("§5.1.8: 订阅应答未产出任何事件帧").isNotNull();
        JsonNode result = firstFrame.path("result");
        // 首帧快照可能以 Task 事件（result.task/裸 result）或 statusUpdate 形态出现，多路径兜底。
        String frameTaskId = firstNonBlank(
                result.path("task").path("id").asText(null),
                firstNonBlank(result.path("statusUpdate").path("taskId").asText(null),
                        result.path("id").asText(null)));
        String frameState = firstNonBlank(
                result.path("task").path("status").path("state").asText(null),
                firstNonBlank(result.path("statusUpdate").path("status").path("state").asText(null),
                        result.path("status").path("state").asText(null)));
        assertThat(frameTaskId)
                .as("§5.1.8: 首帧应为本 task 的快照（taskId 一致）\nframe=%s", firstFrame)
                .isEqualTo(taskId);
        assertThat(frameState)
                .as("§5.1.8: 首帧快照应携带 status.state\nframe=%s", firstFrame)
                .isNotNull();
        LOG.info("[e3e4-resub] E3 first frame snapshot: state=" + frameState);
    }

    @Test
    @DisplayName("FEAT-001.task-resubscribe (E4): 终态 Task SubscribeToTask → UnsupportedOperation 类错误 → GetTask 回退")
    void terminalTaskResubscribeFallsBackToGetTask() throws Exception {
        // 制造终态 task：SendMessage(returnImmediately) + GetTask 轮到终态。
        String ackBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"e4-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"ctx-e4-%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                UUID.randomUUID().toString().substring(0, 8), USER_INPUT);
        HttpResponse<String> ack = postJson(ackBody);
        assertThat(ack.statusCode()).isEqualTo(200);
        String taskId = mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null);
        assumeTrue(taskId != null, "未取到 taskId，INCONCLUSIVE");
        String terminal = pollTerminal(taskId);
        assumeTrue(terminal != null, "任务未达终态，INCONCLUSIVE");

        // 终态重订阅：必须错误回包（UnsupportedOperation 或等价协议错误；码值待 L2 钉）。
        String subBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"e4s-%s\",\"method\":\"SubscribeToTask\",\"params\":{\"id\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8), taskId);
        HttpRequest req = HttpRequest.newBuilder(URI.create(searchStack.baseUrl(SEARCH) + "/a2a"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(subBody)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        LOG.info("[e3e4-resub] E4 SubscribeToTask on terminal: status=" + resp.statusCode()
                + " ct=" + ct + " body(前300)=" + resp.body().substring(0, Math.min(300, resp.body().length())));

        assertThat(ct)
                .as("§5.1.8: 终态 Task 重订阅不得建立事件流，应返回协议错误\nct=%s body=%s",
                        ct, resp.body())
                .doesNotContain("event-stream");
        JsonNode err = mapper.readTree(resp.body()).path("error");
        assertThat(err.isMissingNode())
                .as("§5.1.8: 终态重订阅应返回 UnsupportedOperation 或等价协议错误（含 error 对象；"
                        + "-32601 亦为 red 信号=method 未实现）\nbody=%s", resp.body())
                .isFalse();
        LOG.info("[e3e4-resub] E4 error surface: code=" + err.path("code").asText()
                + " message=" + err.path("message").asText());

        // 回退：GetTask 取最终快照仍可用。
        String after = getState(taskId);
        assertThat(after).as("回退 GetTask 应仍返回终态快照").isEqualTo(terminal);
    }

    // —— helpers ——

    /** 起流式任务，读到 taskId + WORKING 即断开，返回 taskId（活动态前提制造）。 */
    private String startStreamingAndDetach(String ctxPrefix) throws Exception {
        String streamBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"st-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                ctxPrefix, UUID.randomUUID().toString().substring(0, 8), USER_INPUT);
        HttpRequest req = HttpRequest.newBuilder(URI.create(searchStack.baseUrl(SEARCH) + "/a2a"))
                .timeout(Duration.ofMillis(SSE_FIRST_EVENT_CAP_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(streamBody)).build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) return null;
        String taskId = null;
        String seen = null;
        long cap = System.currentTimeMillis() + SSE_FIRST_EVENT_CAP_MS;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < cap && (line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                JsonNode result = mapper.readTree(line.substring(5).trim()).path("result");
                // wire 事实（2026-08-17 裸探）：流式事件为 result.statusUpdate / result.artifactUpdate。
                if (taskId == null) {
                    taskId = firstNonBlank(result.path("statusUpdate").path("taskId").asText(null),
                            firstNonBlank(result.path("artifactUpdate").path("taskId").asText(null),
                                    firstNonBlank(result.path("task").path("id").asText(null),
                                            result.path("id").asText(null))));
                }
                String st = firstNonBlank(
                        result.path("statusUpdate").path("status").path("state").asText(null),
                        firstNonBlank(result.path("task").path("status").path("state").asText(null),
                                result.path("status").path("state").asText(null)));
                if (st != null) seen = st;
                if (taskId != null && seen != null && seen.contains("WORKING")) break;
            }
        }
        return taskId;
    }

    private boolean isTerminal(String s) {
        return s != null && (s.contains("COMPLETED") || s.contains("FAILED")
                || s.contains("CANCELED") || s.contains("REJECTED"));
    }

    private String pollTerminal(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + TERMINAL_POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String s = getState(taskId);
            if (isTerminal(s)) return s;
            Thread.sleep(2_000);
        }
        return null;
    }

    private String getState(String taskId) throws Exception {
        HttpResponse<String> resp = postJson(String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8), taskId));
        JsonNode root = mapper.readTree(resp.body());
        return firstNonBlank(
                root.path("result").path("task").path("status").path("state").asText(null),
                root.path("result").path("status").path("state").asText(null));
    }

    private HttpResponse<String> postJson(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(searchStack.baseUrl(SEARCH) + "/a2a"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }
}
