package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.cases.e2e.deepanalyze.DaCompatSseSupport.Frame;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code da.compat.reconnect}：SSE 断线重连的 Last-Event-ID 续传与缓冲重放无缺口。
 *
 * <p>Oracle：特性 §2 验收出口 #13；L2 §2.11 缓冲重放契约、§3.11 无缺口判据（设计认可口径）。
 *
 * <p>判据（设计侧 B-Q5 裁决：采纳 L2 条件口径）：**不构造超过缓冲上限 5000 的逐出场景**；
 * 在"未触发容量逐出"前提下，重放段 seq 必须与断点衔接、无缺口无重复错序，
 * 已完成任务重放后补 {@code reconnect_done} 收尾。
 *
 * <p>主断言：断线后重连的起始 seq 必须是"断点 + 1"，且序列连续走到 {@code done}
 * 与 {@code reconnect_done}。若缓冲重放丢事件、从头重放造成重复、或重连端点行为异常，主断言必然失败。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCompatReconnectE2EIT extends DaCompatStreamTestBase {

    private static final String INPUT = "请用两句话说明数据分析的基本步骤，不要调用任何工具。";
    private static final int DISCONNECT_AFTER_FRAMES = 3;

    @Test
    @Story("da.compat.reconnect: 断线重连无缺口")
    @DisplayName("da.compat.reconnect: 断线后带 Last-Event-ID 重连 → 重放无缺口并以 reconnect_done 收尾")
    void reconnectReplaysWithoutGap() throws Exception {
        initClient();
        String sessionId = "sess-reconnect-" + UUID.randomUUID();

        HttpResponse<InputStream> first =
                DaCompatSseSupport.openRunStream(http, base, sessionId, INPUT, READ_TIMEOUT);
        assertThat(first.statusCode()).as("流式执行端点必须受理合法入体").isEqualTo(200);

        List<Frame> head = DaCompatSseSupport.readFrames(first.body(),
                frame -> !frame.heartbeat() && frame.id() != null
                        && frame.id().endsWith("-" + DISCONNECT_AFTER_FRAMES),
                Duration.ofSeconds(90));
        // 模拟 DA 前端掉线：直接关闭连接（不取消任务）
        first.body().close();

        List<Frame> headFrames = head.stream().filter(frame -> !frame.heartbeat()).toList();
        assertThat(headFrames).as("断线前必须已收到若干事件帧").hasSizeGreaterThanOrEqualTo(DISCONNECT_AFTER_FRAMES);
        String firstId = headFrames.get(0).id();
        String taskId = firstId.substring(0, firstId.lastIndexOf('-'));
        String lastEventId = headFrames.get(headFrames.size() - 1).id();
        int lastSeq = Integer.parseInt(lastEventId.substring(lastEventId.lastIndexOf('-') + 1));
        Allure.addAttachment("断线点", "text/plain", "taskId=" + taskId + ", lastEventId=" + lastEventId);

        // 等待任务跑完；顺带覆盖状态查询的成功路径（属主绑定：同一 session）
        String status = awaitCompleted(taskId, sessionId);
        Allure.addAttachment("GET /agent/status/{taskId}", "text/plain", status);
        assertThat(status).as("任务快照须显示已完成").contains("completed");

        HttpResponse<InputStream> reconnected =
                DaCompatSseSupport.openStream(http, base, taskId, sessionId, lastEventId, READ_TIMEOUT);
        assertThat(reconnected.statusCode()).as("带合法属主的重连必须受理").isEqualTo(200);
        List<Frame> tail = DaCompatSseSupport.readFrames(reconnected.body(),
                frame -> "reconnect_done".equals(frame.event()), Duration.ofSeconds(60));
        Allure.addAttachment("重连帧", "text/plain", tail.toString());

        List<Frame> frames = tail.stream().filter(frame -> !frame.heartbeat()).toList();
        assertThat(frames).as("重连后必须有帧（重放段 + reconnect_done）").isNotEmpty();

        int expectedSeq = lastSeq + 1;
        List<String> names = new ArrayList<>();
        for (Frame frame : frames) {
            names.add(frame.event());
            if (frame.id() != null && frame.id().startsWith(taskId + "-")) {
                assertThat(frame.id())
                        .as("重放段 seq 必须与断点无缝衔接（不得缺口、不得从头重复）")
                        .isEqualTo(taskId + "-" + expectedSeq);
                expectedSeq++;
            }
        }
        assertThat(names).as("重放段必须包含 done").contains("done");
        assertThat(frames.get(frames.size() - 1).event())
                .as("已完成任务重放后以 reconnect_done 收尾").isEqualTo("reconnect_done");
    }

    private String awaitCompleted(String taskId, String sessionId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String body = "";
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = DaCompatSseSupport.getStatus(
                    http, base, taskId, sessionId, READ_TIMEOUT);
            if (response.statusCode() == 200) {
                body = response.body();
                if (body.contains("\"status\":\"completed\"")) {
                    return body;
                }
            }
            Thread.sleep(1000L);
        }
        throw new IllegalStateException("任务未在期限内完成，最后一次状态响应: " + body);
    }
}
