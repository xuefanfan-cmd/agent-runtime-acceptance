package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.cases.e2e.deepanalyze.DaCompatSseSupport.Frame;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 故障注入面（黑盒）：**模型不可达**时的错误终态与错误表面。
 *
 * <p>为什么要这组：A-Q8 曾把"错误分支"记为"真实任务里不可按需触发、由开发侧脚本化事件源承接"。
 * 本类证明该分支**可由黑盒稳定触发**——把模型 base_url 指向黑洞地址即可，从而把该分支的证据从
 * "开发侧白盒"提升为"本线黑盒可复核"。
 *
 * <p>判据：L2 §2.11 错误映射（`error` chunk → `error` + `done{status:"failed"}`，并附 `code` 映射
 * `ENGINE_MODEL_ERROR`/`ENGINE_TIMEOUT`）；同步端点失败形态为 `{taskId:null,status:"failed",error}`。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaFaultInjectionE2EIT extends DaCompatStreamTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 黑洞地址：本地未监听端口，连接必然失败，用于稳定触发模型侧错误。 */
    private static final String BLACKHOLE_BASE_URL = "http://127.0.0.1:9";

    @Override
    protected com.huawei.ascend.sit.lifecycle.SutStack.Builder buildStack(
            com.huawei.ascend.sit.config.TestConfig config) {
        return com.huawei.ascend.sit.lifecycle.SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    agent.env("LLM_API_KEY", System.getenv().getOrDefault("LLM_API_KEY", "unused"))
                            .env("DA_MODEL_BASE_URL", BLACKHOLE_BASE_URL)
                            .env("DA_MODEL_NAME", System.getenv().getOrDefault("LLM_MODEL", "unused"))
                            .env("DA_MODEL_PROVIDER", System.getenv().getOrDefault("LLM_PROVIDER", "openai"))
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    @Test
    @Story("故障注入：模型不可达时同步端点返回失败终态")
    @DisplayName("故障注入：模型黑洞 → /agent/run 失败终态（status=failed，error 非空）")
    void unreachableModelFailsSyncRunWithDiagnosableError() throws Exception {
        initClient();
        String session = "sess-fault-" + UUID.randomUUID();
        HttpResponse<String> response = post("/agent/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"请回答：1+1。不要调用任何工具。\"}", session);
        Allure.addAttachment("模型黑洞时的同步执行响应", "text/plain",
                response.statusCode() + "\n" + response.body());
        assertThat(response.statusCode())
                .as("模型不可达属执行期失败：允许 500（失败形态）或 200+failed，不得 2xx 成功")
                .isIn(200, 500);
        String body = response.body();
        if (response.statusCode() == 200) {
            assertThat(MAPPER.readTree(body).path("status").asText())
                    .as("200 响应的终态必须是 failed").isEqualTo("failed");
        } else {
            assertThat(MAPPER.readTree(body).path("status").asText())
                    .as("500 失败形态必须带 status=failed").isEqualTo("failed");
        }
        assertThat(body).as("失败必须带可诊断错误信息（不得空错误）").contains("error");
        assertThat(body).as("错误信息不得为空串").doesNotContain("\"error\":\"\"");
    }

    @Test
    @Story("故障注入：模型不可达时流式端点落定 error→done 终态组")
    @DisplayName("故障注入：模型黑洞 → /agent/run-stream 事件面出现 error 与 done（含失败状态）")
    void unreachableModelEmitsErrorTerminalGroupOnStream() throws Exception {
        initClient();
        String session = "sess-fault-stream-" + UUID.randomUUID();
        HttpResponse<InputStream> response = DaCompatSseSupport.openRunStream(http, base, session,
                "请回答：2+2。不要调用任何工具。", Duration.ofMinutes(5));
        assertThat(response.statusCode()).as("流式端点受理阶段应为 200（错误在事件面表达）").isEqualTo(200);

        List<Frame> frames = DaCompatSseSupport.readFramesUntilEof(response.body(), Duration.ofMinutes(5))
                .stream().filter(frame -> !frame.heartbeat()).toList();
        List<String> events = frames.stream().map(Frame::event).toList();
        Allure.addAttachment("模型黑洞时的事件序列", "text/plain", events.toString());
        assertThat(events).as("模型失败必须落定 error 终态事件").contains("error");
        assertThat(events).as("error 之后必须有 done 关流（不得悬挂）").contains("done");
        Frame done = frames.stream().filter(frame -> "done".equals(frame.event()))
                .reduce((first, second) -> second).orElseThrow();
        Allure.addAttachment("done 帧数据", "text/plain", done.data());
        assertThat(done.data()).as("done 必须标记失败状态").contains("failed");
    }

    private HttpResponse<String> post(String path, String body, String session) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header(SESSION_HEADER, session)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
