package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code REQ-115}/{@code GAP-054-17}：DA 同源路由与设计定名路由**双挂载等价**。
 *
 * <p>依据：设计侧 2026-09-22 残余回复 R1（PR `agent-solution!671`，已合入 common）——
 * answer/status 端点同时挂载 `/agent/message/{taskId}`、`/agent/task/{taskId}`，同一处理逻辑、零行为分叉；
 * "DA 前端零改动"承诺成立，不再是已知偏差。
 *
 * <p>主断言：同一目标任务下，DA 同源路由与设计定名路由的**状态码与错误体逐字一致**（等价性），
 * 而不是"两条路都返回 404"这种空过断言——因此断言里比对两边的完整响应体。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCompatRouteAliasE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private HttpClient http;
    private String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
                    passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
                    passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
                    passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    private void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            base = client(AGENT).getBaseUrl();
        }
    }

    @Test
    @Story("REQ-115: DA 同源路由 answer/status 与设计定名双挂载等价")
    @DisplayName("双挂载等价：/agent/message 与 /agent/answer、/agent/task 与 /agent/status 响应逐字一致")
    void daNativeRoutesAreEquivalentToDesignNamedRoutes() throws Exception {
        initClient();
        String taskId = "no-such-task-" + UUID.randomUUID();
        String session = "sess-alias-" + UUID.randomUUID();

        HttpResponse<String> answer = post("/agent/answer/" + taskId, "{}", session);
        HttpResponse<String> message = post("/agent/message/" + taskId, "{}", session);
        Allure.addAttachment("answer vs message",
                "text/plain", answer.statusCode() + " / " + message.statusCode() + "\n"
                        + answer.body() + "\n" + message.body());
        assertThat(message.statusCode()).as("DA 同源路由必须存在（不再 404-Not-Found-Route）").isEqualTo(answer.statusCode());
        assertThat(message.body()).as("两条路由错误体必须逐字一致（同一处理逻辑，零行为分叉）")
                .isEqualTo(answer.body());

        HttpResponse<String> status = get("/agent/status/" + taskId, session);
        HttpResponse<String> task = get("/agent/task/" + taskId, session);
        Allure.addAttachment("status vs task",
                "text/plain", status.statusCode() + " / " + task.statusCode() + "\n"
                        + status.body() + "\n" + task.body());
        assertThat(task.statusCode()).as("DA 同源状态路由必须存在").isEqualTo(status.statusCode());
        assertThat(task.body()).as("两条状态路由响应体必须逐字一致").isEqualTo(status.body());
    }

    /** 正向等价：真实任务在两条状态路由上返回同一快照。 */
    @Test
    @Story("REQ-115: 真实任务快照在两条状态路由上一致")
    @DisplayName("双挂载等价（正向）：run 后 /agent/status 与 /agent/task 返回同一任务快照")
    void taskSnapshotIsIdenticalOnBothStatusRoutes() throws Exception {
        initClient();
        String session = "sess-alias-run-" + UUID.randomUUID();
        HttpResponse<String> run = post("/agent/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"请用一句话回答：2+3 等于几。不要调用任何工具。\"}", session);
        Allure.addAttachment("POST /agent/run", "text/plain", run.body());
        assertThat(run.statusCode()).as("任务必须受理（正向前置）").isEqualTo(200);
        String status = get("/agent/status/" + taskIdOf(run), session).body();
        String task = get("/agent/task/" + taskIdOf(run), session).body();
        Allure.addAttachment("状态快照对比", "text/plain", status + "\n---\n" + task);
        assertThat(task).as("同源路由必须返回与设计定名路由完全一致的任务快照").isEqualTo(status);
    }

    private static String taskIdOf(HttpResponse<String> run) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(run.body()).path("taskId").asText();
    }

    private HttpResponse<String> post(String path, String body, String session) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Da-Session-Id", session)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("X-Da-Session-Id", session)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
