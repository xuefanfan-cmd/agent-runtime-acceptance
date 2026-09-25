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
 * FEAT-054 并发与容量面（黑盒）。
 *
 * <p>覆盖：① 同会话并发多任务的受理与隔离；② **慢 SSE 客户端**：客户端不读导致服务端队列溢出后
 * 主动断开（实现既有语义：`sse replay pump disconnected slow client (queue overflow)`），
 * 且**断连不取消任务**（L2 §3.11/§2.11）；③ 任务池饱和 503 由开发侧白盒承接（`DaCompatControllerUnitTest`），
 * 本类不重复构造（会污染同栈其它用例的资源池）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaConcurrencyCapacityE2EIT extends DaCompatStreamTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 产出事件较多的语料，便于在"不读"的 25 秒内让服务端队列溢出。 */
    private static final String INPUT_MANY_EVENTS =
            "请分 8 条列出数据分析的关键步骤，每条一句话，不要调用任何工具。";

    @Test
    @Story("并发：同会话并发多任务受理与任务隔离")
    @DisplayName("并发：同会话 3 个并发任务 → 全部受理、taskId 互不相同、无 5xx")
    void concurrentTasksInSameSessionAreAccepted() throws Exception {
        initClient();
        String session = "sess-concurrent-" + UUID.randomUUID();
        // 三条流并发**消费到 done**：既验证并发受理，也避免"中途关流打断 SSE 泵"引入的假 500
        List<CompletableFuture<List<Frame>>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    HttpResponse<InputStream> response = DaCompatSseSupport.openRunStream(http, base, session,
                            "请用一句话回答：1+" + index + " 等于几。不要调用任何工具。", Duration.ofMinutes(10));
                    assertThat(response.statusCode()).as("并发任务必须受理，不得 5xx").isEqualTo(200);
                    return DaCompatSseSupport.readFrames(response.body(),
                            frame -> "done".equals(frame.event()), Duration.ofMinutes(5));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }));
        }
        List<String> taskIds = new ArrayList<>();
        for (CompletableFuture<List<Frame>> future : futures) {
            List<Frame> frames = future.get(6, TimeUnit.MINUTES);
            List<String> events = frames.stream().filter(frame -> !frame.heartbeat()).map(Frame::event).toList();
            Allure.addAttachment("并发流事件序列", "text/plain",
                    events.size() + " events, last=" + (events.isEmpty() ? "-" : events.get(events.size() - 1)));
            assertThat(events).as("每条并发流都必须自行跑通（start→done）").contains("start", "done");
            taskIds.add(frames.stream().filter(frame -> "start".equals(frame.event())).findFirst()
                    .orElseThrow().id().replaceAll("-\\d+$", ""));
        }
        assertThat(taskIds).as("同一会话内并发任务必须有各自独立的 taskId")
                .doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    @Story("容量：慢 SSE 客户端溢出断开后任务仍继续")
    @DisplayName("容量：客户端不读 → 服务端溢出断开；任务不被取消且状态可查")
    void slowSseClientIsDisconnectedButTaskContinues() throws Exception {
        initClient();
        String session = "sess-slow-" + UUID.randomUUID();
        HttpResponse<InputStream> stream =
                DaCompatSseSupport.openRunStream(http, base, session, INPUT_MANY_EVENTS, READ_TIMEOUT);
        assertThat(stream.statusCode()).isEqualTo(200);

        // 完全不消费事件（真正的"慢客户端"）：25 秒内服务端事件持续产出，队列应溢出并断开本连接
        Thread.sleep(25_000L);
        List<Frame> tail = DaCompatSseSupport.readFramesUntilEof(stream.body(), Duration.ofSeconds(60));
        Allure.addAttachment("溢出断开后剩余帧数", "text/plain", tail.size() + " frames（连接应已被服务端断开）");

        // 通过会话任务列表反查 taskId（不依赖被断开的流）
        HttpResponse<String> tasks = http.send(HttpRequest.newBuilder()
                .uri(URI.create(base + "/agent/tasks/" + session))
                .timeout(READ_TIMEOUT)
                .header(SESSION_HEADER, session)
                .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String taskId = MAPPER.readTree(tasks.body()).path(0).path("id").asText();
        Allure.addAttachment("慢客户端场景下的任务 id", "text/plain", taskId);
        assertThat(taskId).as("任务必须已登记（客户端断连不得导致任务消失）").isNotBlank();

        // 关键断言：任务不因客户端被断开而取消，状态查询仍可拿到终态
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        String status = "";
        while (System.nanoTime() < deadline) {
            HttpResponse<String> probe = DaCompatSseSupport.getStatus(http, base, taskId, session, READ_TIMEOUT);
            if (probe.statusCode() == 200) {
                status = probe.body();
                if (status.contains("\"status\":\"completed\"") || status.contains("\"status\":\"failed\"")) {
                    break;
                }
            }
            Thread.sleep(2000L);
        }
        Allure.addAttachment("慢客户端场景下的任务终态", "text/plain",
                status.substring(0, Math.min(400, status.length())));
        assertThat(status)
                .as("客户端被断开后任务必须继续执行并落定终态（断连不取消任务）")
                .contains("\"status\":");
        assertThat(status).as("任务终态应为 completed（零工具最小任务不应失败）").contains("completed");
    }
}
