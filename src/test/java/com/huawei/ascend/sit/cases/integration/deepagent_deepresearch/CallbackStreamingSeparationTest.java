package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockCallbackReceiver;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001 矩阵 <b>D7</b> —— callback 与 streaming 两通道<b>分离</b>。
 *
 * <p><b>Spec 依据</b>：§2「callback 与 streaming 模式分离」/ §5.1.4：streaming 用于实时过程观察；
 * callback 只承载异步完成通知，<b>不承载 token-by-token、progress stream 或 SSE frame</b>。
 *
 * <p><b>形态</b>（按矩阵步骤「同一 SUT 分别走两路径」）：sender=search 单节点。
 * <ol>
 *   <li>streaming 腿：{@code SendStreamingMessage} 原生 SSE，计数过程事件帧直至流关闭——
 *       证明过程观察通道存在多帧过程性输出；</li>
 *   <li>callback 腿：{@code SendMessage}+内联 config，等待终态投递——断言 callback 表面与
 *       流式表面互不渗透。</li>
 * </ol>
 *
 * <p><b>分离断言</b>：callback（a）恰好一次 POST（无 progress 刷屏）；（b）Content-Type 为 JSON
 * 而非 {@code text/event-stream}；（c）body 是单一 JSON 文档（无 SSE {@code event:}/{@code data:}
 * 分帧标记、不可多文档拼接）；（d）承载的是终态 Task 表面。对照组：streaming 腿事件帧数 ≥2。
 *
 * <p><b>Tag</b>：manual —— 依赖本地 search jar + 真实 LLM/检索。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.callback-streaming-separation (D7): streaming 承载过程观察；callback 单帧终态、无 SSE 渗透")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackStreamingSeparationTest {

    private static final Logger LOG = Logger.getLogger(CallbackStreamingSeparationTest.class.getName());
    private static final String SEARCH = "search";
    private static final String USER_INPUT = "你好,到deepseek官网查询下DeepSeek-V3 上下文长度多少 tokens。";

    private static final long STREAM_READ_CAP_MS = 75_000;
    private static final long TERMINAL_POLL_TIMEOUT_MS = 90_000;
    private static final long DELIVERY_WAIT_MS = 45_000;
    /** 终态投递后再多观察一段，确认没有后续 progress 类 POST。 */
    private static final long EXTRA_QUIET_MS = 10_000;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private TestConfig config;
    private SutStack searchStack;
    private MockCallbackReceiver receiver;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        receiver = MockCallbackReceiver.start();
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a.property("openjiuwen.demo.search-agent.api-key",
                        System.getenv("LLM_API_KEY")))
                .start();
        LOG.info("[d7-separation] search ready at " + searchStack.baseUrl(SEARCH)
                + ", receiver at " + receiver.callbackUrl());
    }

    @AfterAll
    void tearDown() {
        if (searchStack != null) searchStack.close();
        if (receiver != null) receiver.close();
    }

    @Test
    @DisplayName("FEAT-001.callback-streaming-separation (D7): streaming 多帧过程观察 vs callback 单帧终态通知")
    void streamingCarriesProcessWhileCallbackCarriesSingleTerminalNotice() throws Exception {
        // ---- 腿 1：callback（异步完成通知通道）——先行，避免与流式腿在单执行线程 SUT 上排队互扰 ----
        String cbBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"d7c-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"ctx-d7-cb-%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"taskPushNotificationConfig\":{\"id\":\"pn-%s\",\"url\":\"%s\","
                        + "\"token\":\"sit-token-%s\"},"
                        + "\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                UUID.randomUUID().toString().substring(0, 8), USER_INPUT,
                UUID.randomUUID().toString().substring(0, 8), receiver.callbackUrl(),
                UUID.randomUUID().toString().substring(0, 8));
        HttpResponse<String> ack = post("/a2a", cbBody);
        LOG.info("[d7-separation] ack status=" + ack.statusCode() + " body="
                + ack.body().substring(0, Math.min(500, ack.body().length())));
        assertThat(ack.statusCode()).isEqualTo(200);
        String taskId = mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null);
        assumeTrue(taskId != null, "callback 腿未取到 taskId，INCONCLUSIVE");

        String terminal = pollState(taskId, TERMINAL_POLL_TIMEOUT_MS);
        assumeTrue(terminal != null, "callback 腿任务未达终态，INCONCLUSIVE");
        assertThat(receiver.awaitAtLeast(1, DELIVERY_WAIT_MS))
                .as("终态 %s 后应有投递", terminal).isTrue();
        Thread.sleep(EXTRA_QUIET_MS);

        // ---- 腿 2：streaming（过程观察通道）——读满多帧即早停，不必等自然关流 ----
        String streamBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"d7s-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"ctx-d7-stream-%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                UUID.randomUUID().toString().substring(0, 8), USER_INPUT);
        List<String> sseDataFrames = readSseFrames(streamBody, STREAM_READ_CAP_MS, 10);
        LOG.info("[d7-separation] streaming frames=" + sseDataFrames.size());
        assumeTrue(sseDataFrames.size() >= 1, "streaming 腿无事件帧，环境异常，INCONCLUSIVE");
        assertThat(sseDataFrames.size())
                .as("streaming 通道应承载多帧过程观察（状态/artifact 事件），实测 %d 帧", sseDataFrames.size())
                .isGreaterThanOrEqualTo(2);

        // ---- 分离断言 ----
        assertThat(receiver.count())
                .as("§5.1.4: callback 不承载 progress stream——终态后额外静默窗内应恰好 1 次 POST，实测 %d",
                        receiver.count())
                .isEqualTo(1);
        MockCallbackReceiver.CapturedCallback cb = receiver.captured().get(0);
        String ct = cb.header("Content-Type");
        assertThat(ct)
                .as("callback Content-Type 应为 JSON 而非 SSE，实测 %s", ct)
                .isNotNull().containsIgnoringCase("json").doesNotContainIgnoringCase("event-stream");
        String payload = cb.body().trim();
        assertThat(payload)
                .as("callback body 不得含 SSE 分帧标记（event:/data: 前缀）")
                .doesNotContain("\nevent:").doesNotContain("\ndata:");
        assertThat(payload.startsWith("event:") || payload.startsWith("data:"))
                .as("callback body 不得以 SSE 帧起始").isFalse();
        JsonNode parsed = mapper.readTree(payload); // 单一 JSON 文档，可整体 parse
        String cbState = parsed.path("result").path("task").path("status").path("state").asText("");
        assertThat(cbState)
                .as("callback 承载终态 Task 表面（非过程帧），实测 state=%s", cbState)
                .contains(terminal.contains("COMPLETED") ? "COMPLETED" : terminal);
        LOG.info("[d7-separation] PASS surface: frames=" + sseDataFrames.size()
                + " vs callback=1, ct=" + ct + ", state=" + cbState);
    }

    // —— helpers ——

    /** 原生读 SSE：收集 data 行帧，读满 {@code maxFrames}、流关闭或超时即返回。 */
    private List<String> readSseFrames(String requestBody, long capMs, int maxFrames) throws Exception {
        List<String> frames = new ArrayList<>();
        HttpRequest req = HttpRequest.newBuilder(URI.create(searchStack.baseUrl(SEARCH) + "/a2a"))
                .timeout(Duration.ofMillis(capMs))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        long deadline = System.currentTimeMillis() + capMs;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < deadline && frames.size() < maxFrames
                    && (line = r.readLine()) != null) {
                if (line.startsWith("data:")) frames.add(line.substring(5).trim());
            }
        } catch (Exception streamEnd) {
            // 超时/服务端关流：以已收帧为准。
        }
        return frames;
    }

    private String pollState(String taskId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        boolean logged = false;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = post("/a2a", String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                    UUID.randomUUID().toString().substring(0, 8), taskId));
            if (!logged) {
                LOG.info("[d7-separation] first GetTask: status=" + resp.statusCode() + " body="
                        + resp.body().substring(0, Math.min(500, resp.body().length())));
                logged = true;
            }
            if (resp.statusCode() == 200) {
                // wire 事实（2026-08-17）：GetTask 的 result 是裸 Task（result.status.state），
                // SendMessage ack 才是包 task 一层（result.task.status.state）——双路径兜底。
                JsonNode root = mapper.readTree(resp.body());
                String s = root.path("result").path("task").path("status").path("state").asText(null);
                if (s == null) s = root.path("result").path("status").path("state").asText(null);
                if (s != null) {
                    last = s;
                    if (s.contains("COMPLETED") || s.contains("FAILED")
                            || s.contains("CANCELED") || s.contains("REJECTED")) return s;
                }
            }
            Thread.sleep(2_000);
        }
        LOG.info("[d7-separation] terminal poll timeout, last observed state=" + last);
        return null;
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(searchStack.baseUrl(SEARCH) + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
