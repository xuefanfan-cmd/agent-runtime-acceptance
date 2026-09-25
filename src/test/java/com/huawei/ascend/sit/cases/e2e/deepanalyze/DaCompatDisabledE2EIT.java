package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 配置组合边界：**`deepanalyze.compat.enabled=false`** 时的对外行为。
 *
 * <p>判据（被测模块 `application.yml` 总开关注释 + L2 §2.11）：
 * {@code false 时不暴露 DA 形态端点、不挂 ToolTrackingRail}。
 * ⇒ 本类断言：① DA 形态端点不可用（404/405，不得 200）；② **标准 A2A 入口照常可用**（不同开关误伤主流程）；
 * ③ 探活端点不受影响。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCompatDisabledE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private HttpClient http;
    private String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(AGENT, agent -> {
                    passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
                    passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
                    passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
                    passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
                    agent.property("deepanalyze.compat.enabled", "false")
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
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
    @Story("配置组合：compat 关闭时 DA 形态端点不暴露")
    @DisplayName("配置组合：compat=false → /agent/run-stream、/agent/status 不可用（404/405）")
    void daCompatEndpointsAreNotExposedWhenDisabled() throws Exception {
        initClient();
        // 先用标准入口跑一个任务，确认服务本身是活的（避免"整体没起来"被误读为"端点被正确关闭"）
        InteractionFlow.of(client(AGENT))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send("请用一句话回答：7+8 等于几。不要调用任何工具。")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(text -> assertThat(text).isNotBlank())
                .execute();

        for (String path : List.of("/agent/status/no-such-054", "/agent/tasks/sess-none",
                "/agent/skills/active", "/agent/providers/x/test")) {
            HttpResponse<String> response = get(path);
            Allure.addAttachment("compat=false: " + path, "text/plain",
                    response.statusCode() + "\n" + response.body().substring(0,
                            Math.min(120, response.body().length())));
            assertThat(response.statusCode())
                    .as("compat 关闭时 DA 形态端点不得可用（path=%s）", path)
                    .isIn(404, 405);
        }

        HttpResponse<String> runStream = post("/agent/run-stream",
                "{\"sessionId\":\"sess-off-" + UUID.randomUUID() + "\",\"input\":\"x\"}");
        Allure.addAttachment("compat=false: POST /agent/run-stream", "text/plain",
                runStream.statusCode() + "\n" + runStream.body().substring(0,
                        Math.min(120, runStream.body().length())));
        assertThat(runStream.statusCode()).as("compat 关闭时流式端点不得可用").isIn(404, 405);
    }

    @Test
    @Story("配置组合：compat 关闭不影响标准入口与探活")
    @DisplayName("配置组合：compat=false → 标准 A2A 入口与 /health 正常")
    void standardEntranceAndProbesStayHealthy() throws Exception {
        initClient();
        InteractionFlow.of(client(AGENT))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send("请用一句话回答：9+1 等于几。不要调用任何工具。")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(text -> assertThat(text).isNotBlank())
                .execute();

        HttpResponse<String> health = get("/health");
        Allure.addAttachment("/health（compat=false）", "text/plain", health.body());
        assertThat(health.statusCode()).as("compat 开关不得影响探活端点").isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base + path)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base + path)).timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
