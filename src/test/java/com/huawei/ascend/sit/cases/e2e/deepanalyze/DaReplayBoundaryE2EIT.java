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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 事件/重放面**边界**（黑盒）：
 * ① 异常 `Last-Event-ID`（乱码/超长/负数/其它任务的 id）→ 安全处理，不得 5xx、不得串任务；
 * ② 跨任务缓冲污染：A 任务的重放里不得出现 B 任务的事件；
 * ③ 多客户端同任务订阅：两个客户端各自完整拿到同一份事件（互不吞并）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaReplayBoundaryE2EIT extends DaCompatStreamTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @Story("da.compat.reconnect: 异常 Last-Event-ID 安全处理")
    @DisplayName("重放边界：乱码/超长/负数/异任务 id → 非 5xx 且不串任务")
    void malformedLastEventIdIsHandledSafely() throws Exception {
        initClient();
        String session = "sess-replay-" + UUID.randomUUID();
        String taskId = completedTask(session);
        String otherTaskId = completedTask(session);

        List<String> junkIds = List.of(
                "not-an-event-id",
                "x".repeat(300),
                "-1",
                taskId + "-999999",
                otherTaskId + "-1");
        for (String junk : junkIds) {
            HttpResponse<InputStream> response =
                    DaCompatSseSupport.openStream(http, base, taskId, session, junk, READ_TIMEOUT);
            Allure.addAttachment("lastEventId=" + abbrev(junk), "text/plain",
                    "status=" + response.statusCode());
            if (response.statusCode() != 200) {
                assertThat(response.statusCode()).as("异常 lastEventId 只允许 4xx：%s", abbrev(junk))
                        .isLessThan(500);
                continue;
            }
            List<Frame> frames = DaCompatSseSupport.readFrames(response.body(),
                    frame -> "reconnect_done".equals(frame.event()), Duration.ofSeconds(60));
            List<String> foreign = frames.stream()
                    .filter(frame -> frame.id() != null && !frame.id().startsWith(taskId + "-"))
                    .map(Frame::id).toList();
            Allure.addAttachment("重放帧数/越界 id", "text/plain", frames.size() + " frames, foreign=" + foreign);
            assertThat(foreign).as("重放中不得出现其它任务的事件（lastEventId=%s）", abbrev(junk)).isEmpty();
        }
    }

    @Test
    @Story("da.compat.reconnect: 跨任务缓冲互不污染")
    @DisplayName("重放边界：A 任务重放里不含 B 任务事件（双向）")
    void replayDoesNotLeakAcrossTasks() throws Exception {
        initClient();
        String session = "sess-isolation-" + UUID.randomUUID();
        String taskA = completedTask(session);
        String taskB = completedTask(session);

        assertReplayOnlyContains(taskA, session);
        assertReplayOnlyContains(taskB, session);
    }

    @Test
    @Story("da.compat.reconnect: 多客户端同任务订阅互不吞并")
    @DisplayName("重放边界：两客户端同时重放同一任务 → 各自拿到完整事件集")
    void concurrentReplaysBothReceiveFullEventSet() throws Exception {
        initClient();
        String session = "sess-multi-" + UUID.randomUUID();
        String taskId = completedTask(session);

        CompletableFuture<List<Frame>> first = replayAsync(taskId, session);
        CompletableFuture<List<Frame>> second = replayAsync(taskId, session);
        List<Frame> a = first.get(3, TimeUnit.MINUTES);
        List<Frame> b = second.get(3, TimeUnit.MINUTES);

        long countA = a.stream().filter(frame -> !frame.heartbeat()).count();
        long countB = b.stream().filter(frame -> !frame.heartbeat()).count();
        Allure.addAttachment("两客户端重放帧数", "text/plain", "A=" + countA + " B=" + countB);
        assertThat(countA).as("客户端 A 必须收到非空事件集").isGreaterThan(0);
        assertThat(countB).as("客户端 B 必须收到与 A 同等规模的事件集（不得被另一方吞并）")
                .isEqualTo(countA);
        assertThat(a.stream().map(Frame::event).toList()).contains("done");
        assertThat(b.stream().map(Frame::event).toList()).contains("done");
    }

    private CompletableFuture<List<Frame>> replayAsync(String taskId, String session) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<InputStream> response =
                        DaCompatSseSupport.openStream(http, base, taskId, session, null, READ_TIMEOUT);
                assertThat(response.statusCode()).isEqualTo(200);
                return DaCompatSseSupport.readFrames(response.body(),
                        frame -> "reconnect_done".equals(frame.event()), Duration.ofMinutes(2));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private void assertReplayOnlyContains(String taskId, String session) throws Exception {
        HttpResponse<InputStream> response =
                DaCompatSseSupport.openStream(http, base, taskId, session, null, READ_TIMEOUT);
        assertThat(response.statusCode()).isEqualTo(200);
        List<Frame> frames = DaCompatSseSupport.readFrames(response.body(),
                frame -> "reconnect_done".equals(frame.event()), Duration.ofSeconds(60));
        List<String> foreign = frames.stream()
                .filter(frame -> frame.id() != null && !frame.id().startsWith(taskId + "-"))
                .map(Frame::id).toList();
        Allure.addAttachment("任务 " + abbrev(taskId) + " 重放", "text/plain",
                frames.size() + " frames, foreign=" + foreign);
        assertThat(foreign).as("任务 %s 的重放里不得含其它任务事件", abbrev(taskId)).isEmpty();
        assertThat(frames.stream().map(Frame::event).toList()).contains("done");
    }

    /** 用同步端点跑出一个已完成任务，返回其 taskId。 */
    private String completedTask(String session) throws Exception {
        HttpResponse<String> run = http.send(HttpRequest.newBuilder()
                .uri(URI.create(base + "/agent/run"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header(SESSION_HEADER, session)
                .POST(HttpRequest.BodyPublishers.ofString("{\"sessionId\":\"" + session
                        + "\",\"input\":\"请用一句话回答：5+6 等于几。不要调用任何工具。\"}",
                        StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(run.statusCode()).as("前置任务必须跑通").isEqualTo(200);
        String taskId = MAPPER.readTree(run.body()).path("taskId").asText();
        assertThat(taskId).isNotBlank();
        return taskId;
    }

    private static String abbrev(String text) {
        return text.length() <= 24 ? text : text.substring(0, 24) + "…(" + text.length() + ")";
    }
}
