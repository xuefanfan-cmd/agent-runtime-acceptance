package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
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
 * FEAT-054 DA 兼容面**成功路径**：同步执行、状态/任务列表、取消、注入、属主绑定（IDOR 防护）。
 *
 * <p>这些用例原先卡在 {@code GAP-054-01}（"成功路径依赖真实任务跑通"）。设计侧 B-Q1 裁决
 * "零工具最小任务可作替代路径"后解封，本类即以零工具最小任务驱动真实端点。
 *
 * <p>Oracle：特性 §4 端点明细各端点行 + L2 §2.11 端点契约（设计认可口径）。
 *
 * <p>刻意不覆盖：{@code answer} 的"有待答 → 续跑同流"成功路径需要可控的反问（{@code ask_user}）
 * 触发手段，设计侧 A-Q8 已裁决由开发侧脚本化事件源测试承接，本线只覆盖其 400/404 负路径
 * （见 {@code DaCompatControlContractE2EIT}），不在此处伪造。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCompatControlSuccessE2EIT extends DaCompatStreamTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INPUT = "请用一句话说明数据分析的第一步是什么，不要调用任何工具。";

    @Test
    @Story("da.compat.run-sync: 同步执行聚合成功态")
    @DisplayName("da.compat.run-sync: POST /agent/run → 200 且聚合出 completed 结果")
    void runSyncAggregatesCompletedResult() throws Exception {
        initClient();
        String sessionId = "sess-sync-" + UUID.randomUUID();

        HttpResponse<String> response = postJson("/agent/run", sessionId, runStreamBody(sessionId, INPUT));
        Allure.addAttachment("POST /agent/run 响应", "text/plain", response.body());
        assertThat(response.statusCode()).as("合法入体的同步执行应返回 200").isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.path("taskId").asText()).as("必须返回任务 id").isNotBlank();
        assertThat(body.path("status").asText()).as("同步聚合后的终态必须是 completed").isEqualTo("completed");
        assertThat(body.path("output").asText()).as("必须聚合出非空结果正文").isNotBlank();
    }

    @Test
    @Story("da.compat.status-tasks: 任务快照与会话任务列表 + 属主绑定")
    @DisplayName("da.compat.status-tasks: 状态/列表成功可见，异属主请求被拒（404 / 空数组）")
    void statusTasksAndOwnerBinding() throws Exception {
        initClient();
        String sessionId = "sess-status-" + UUID.randomUUID();
        String otherSession = "sess-other-" + UUID.randomUUID();
        String taskId = MAPPER.readTree(postJson("/agent/run", sessionId,
                runStreamBody(sessionId, INPUT)).body()).path("taskId").asText();

        HttpResponse<String> status = getJson("/agent/status/" + taskId, sessionId);
        Allure.addAttachment("GET /agent/status（同属主）", "text/plain", status.body());
        assertThat(status.statusCode()).as("同属主查询任务快照应 200").isEqualTo(200);
        JsonNode snapshot = MAPPER.readTree(status.body());
        assertThat(snapshot.path("id").asText()).isEqualTo(taskId);
        assertThat(snapshot.path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(snapshot.path("status").asText()).isEqualTo("completed");

        HttpResponse<String> crossStatus = getJson("/agent/status/" + taskId, otherSession);
        Allure.addAttachment("GET /agent/status（异属主）", "text/plain", crossStatus.body());
        assertThat(crossStatus.statusCode()).as("异属主查任务快照必须 404（IDOR 防护）").isEqualTo(404);

        HttpResponse<String> tasks = getJson("/agent/tasks/" + sessionId, sessionId);
        Allure.addAttachment("GET /agent/tasks（同属主）", "text/plain", tasks.body());
        assertThat(tasks.statusCode()).isEqualTo(200);
        assertThat(tasks.body()).as("会话任务列表应包含刚完成的任务").contains(taskId);

        HttpResponse<String> crossTasks = getJson("/agent/tasks/" + sessionId, otherSession);
        Allure.addAttachment("GET /agent/tasks（异属主）", "text/plain", crossTasks.body());
        assertThat(crossTasks.statusCode()).isEqualTo(200);
        assertThat(crossTasks.body().trim()).as("异属主列表必须为空数组，不泄露会话任务").isEqualTo("[]");
    }

    @Test
    @Story("da.compat.cancel: 取消运行中任务并落定终态")
    @DisplayName("da.compat.cancel: 运行中任务受理为 cancelled，事件面落定 cancelled→done，状态面可见")
    void cancelRunningTaskSettlesCancelled() throws Exception {
        initClient();
        String sessionId = "sess-cancel-" + UUID.randomUUID();
        HttpResponse<InputStream> stream =
                DaCompatSseSupport.openRunStream(http, base, sessionId, INPUT, READ_TIMEOUT);
        assertThat(stream.statusCode()).isEqualTo(200);
        DaCompatSseSupport.LiveReader reader = DaCompatSseSupport.liveReader(stream.body(),
                frame -> "done".equals(frame.event()));
        try {
            Frame start = reader.awaitFrame(
                    frame -> !frame.heartbeat() && "start".equals(frame.event()), Duration.ofSeconds(60));
            String taskId = stripSequence(start.id());

            HttpResponse<String> cancel = postJson("/agent/cancel/" + taskId, sessionId, "{}");
            Allure.addAttachment("POST /agent/cancel/{taskId}", "text/plain", cancel.body());
            assertThat(cancel.statusCode()).as("运行中任务的取消请求应被受理").isEqualTo(200);
            JsonNode cancelBody = MAPPER.readTree(cancel.body());
            assertThat(cancelBody.path("taskId").asText()).isEqualTo(taskId);
            assertThat(cancelBody.path("status").asText()).isEqualTo("cancelled");

            List<String> names = reader.awaitFinish(Duration.ofSeconds(60)).stream()
                    .filter(frame -> !frame.heartbeat())
                    .map(Frame::event)
                    .toList();
            Allure.addAttachment("取消后事件序列", "text/plain", names.toString());
            assertThat(names)
                    .as("取消为协作式：执行桥须在下一个 chunk 处落定 cancelled→done 终态组")
                    .containsSubsequence("cancelled", "done");

            // 取消落定是"下一个 chunk"处发生，状态面允许短暂滞后，故轮询到期限。
            String status = awaitStatus(taskId, sessionId, "cancelled", Duration.ofSeconds(60));
            Allure.addAttachment("GET /agent/status（取消后）", "text/plain", status);
            assertThat(status).contains("\"status\":\"cancelled\"");
        } finally {
            reader.close();
        }
    }

    @Test
    @Story("da.compat.inject: 运行中任务注入消息被受理")
    @DisplayName("da.compat.inject: 运行中任务注入 → 200 {status:injected}")
    void injectRunningTaskAccepted() throws Exception {
        initClient();
        String sessionId = "sess-inject-" + UUID.randomUUID();
        HttpResponse<InputStream> stream =
                DaCompatSseSupport.openRunStream(http, base, sessionId, INPUT, READ_TIMEOUT);
        assertThat(stream.statusCode()).isEqualTo(200);
        DaCompatSseSupport.LiveReader reader = DaCompatSseSupport.liveReader(stream.body(),
                frame -> "done".equals(frame.event()));
        try {
            Frame start = reader.awaitFrame(
                    frame -> !frame.heartbeat() && "start".equals(frame.event()), Duration.ofSeconds(60));
            String taskId = stripSequence(start.id());

            HttpResponse<String> inject = postJson("/agent/inject/" + taskId, sessionId,
                    "{\"message\":\"补充要求：回答控制在 20 字以内。\"}");
            Allure.addAttachment("POST /agent/inject/{taskId}", "text/plain", inject.body());
            assertThat(inject.statusCode()).as("运行中任务的注入请求应被受理（非运行中会 404）").isEqualTo(200);
            JsonNode injectBody = MAPPER.readTree(inject.body());
            assertThat(injectBody.path("taskId").asText()).isEqualTo(taskId);
            assertThat(injectBody.path("status").asText()).isEqualTo("injected");
            assertThat(injectBody.has("queueLength")).as("响应须带注入计数").isTrue();
        } finally {
            reader.close();
        }
    }

    /** 轮询状态面直到出现期望终态（协作式取消/终态落定允许短暂滞后）。 */
    private String awaitStatus(String taskId, String sessionId, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String body = "";
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = getJson("/agent/status/" + taskId, sessionId);
            if (response.statusCode() == 200) {
                body = response.body();
                if (body.contains("\"status\":\"" + expected + "\"")) {
                    return body;
                }
            }
            Thread.sleep(500L);
        }
        return body;
    }

    private static String stripSequence(String eventId) {
        return eventId.substring(0, eventId.lastIndexOf('-'));
    }

    private HttpResponse<String> postJson(String path, String sessionId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(SESSION_HEADER, sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> getJson(String path, String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(READ_TIMEOUT)
                .header(SESSION_HEADER, sessionId)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
