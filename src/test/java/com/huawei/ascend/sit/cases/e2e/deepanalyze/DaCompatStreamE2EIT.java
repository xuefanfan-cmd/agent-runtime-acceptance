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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code da.compat.stream-shape}：DA 形态流式执行的事件信封、轮次组织、心跳与关流。
 *
 * <p>Oracle：特性 §2 验收出口 #12、§4 端点明细；L2 §2.11 线格式契约（Oracle 强度：设计认可口径，
 * 一手源 DA 仓不可访问 — 见裁决记录 DES-Q10）。
 *
 * <p>主断言：帧格式（{@code id:}/{@code event: }/{@code data: } 带空格前缀、data 单行）、
 * seq 从 1 起连续、终帧 {@code done} 且 {@code status=completed}、done 后连接关闭、心跳帧在场。
 * 若适配层"写出无空格字段、seq 断号、done 不关流、心跳缺失"，主断言必然失败。
 *
 * <p>刻意不断言："17 种事件全部出现"（偏差 P-03 未对齐），也不断言无源事件。
 * 零工具路径下 {@code tool_call}/{@code tool_result} 不应出现——本类把它当**负向证据**断言。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCompatStreamE2EIT extends DaCompatStreamTestBase {

    private static final String INPUT = "请用一句话说明你能做哪类数据分析，不要调用任何工具。";

    @Test
    @Story("da.compat.stream-shape: DA 事件信封、轮次组织、心跳与关流")
    @DisplayName("da.compat.stream-shape: POST /agent/run-stream → 帧格式合规、seq 连续、done 关流、心跳在场")
    void streamFrameShapeAndHeartbeat() throws Exception {
        initClient();
        String sessionId = "sess-stream-" + UUID.randomUUID();

        HttpResponse<InputStream> response =
                DaCompatSseSupport.openRunStream(http, base, sessionId, INPUT, READ_TIMEOUT);
        assertThat(response.statusCode()).as("流式执行端点必须受理合法入体").isEqualTo(200);

        // 一次性读到 EOF：正常返回即证明服务端在 done 之后关流；不关流则超时失败。
        List<Frame> raw = DaCompatSseSupport.readFramesUntilEof(response.body(), Duration.ofSeconds(90));
        Allure.addAttachment("da.compat.stream-shape 原始帧", "text/plain", raw.toString());

        List<Frame> frames = raw.stream().filter(frame -> !frame.heartbeat()).toList();
        assertThat(frames).as("事件流至少应有 start/complete/done 三帧").isNotEmpty();

        Frame first = frames.get(0);
        assertThat(first.event()).as("首帧必须是 start").isEqualTo("start");
        assertThat(first.id()).as("首帧 id 必须是 {taskId}-1").matches("[0-9a-fA-F-]{36}-1");
        String taskId = first.id().substring(0, first.id().lastIndexOf('-'));

        for (int index = 0; index < frames.size(); index++) {
            assertThat(frames.get(index).id())
                    .as("seq 必须从 1 起连续无缺口（第 %d 帧）", index + 1)
                    .isEqualTo(taskId + "-" + (index + 1));
        }

        assertThat(frames).allSatisfy(frame -> {
            assertThat(frame.eventHasSpacePrefix())
                    .as("event 字段名后必须带空格（DA 前端只认 \"event: \"）").isTrue();
            assertThat(frame.dataHasSpacePrefix())
                    .as("data 字段名后必须带空格（DA 前端只认 \"data: \"）").isTrue();
            assertThat(frame.dataLineCount()).as("data 必须是单行 JSON").isEqualTo(1L);
            assertThat(frame.data()).as("data 不得含换行").doesNotContain("\n");
        });

        List<String> names = frames.stream().map(Frame::event).toList();
        assertThat(names).as("事件序列须包含 start→complete→done")
                .containsSubsequence("start", "complete", "done");
        assertThat(names).as("零工具最小任务不应出现工具事件（工具集未交付，按前置记账）")
                .doesNotContain("tool_call", "tool_result");

        Frame last = frames.get(frames.size() - 1);
        assertThat(last.event()).as("终帧必须是 done").isEqualTo("done");
        assertThat(last.data()).as("done 帧须标记 completed").contains("\"status\":\"completed\"");

        int doneIndex = raw.indexOf(last);
        assertThat(raw.subList(doneIndex + 1, raw.size()))
                .as("done 之后不得再推送事件帧（只允许心跳注释行）")
                .allSatisfy(frame -> assertThat(frame.heartbeat()).isTrue());

        assertThat(raw).as("心跳帧必须在场（本类把 heartbeat-ms 压到 500ms 以在短任务内可观测）")
                .anyMatch(Frame::heartbeat);
    }
}
