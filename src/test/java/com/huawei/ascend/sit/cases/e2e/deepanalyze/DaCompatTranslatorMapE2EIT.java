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
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code da.compat.translator-map}：事件翻译泵映射逐行（段落状态机、轮次组织、无源不伪造）。
 *
 * <p>Oracle：L2 §2.11 事件翻译泵契约映射表（设计侧 B-Q10/B-Q9 裁决：以映射表右列为覆盖基准，
 * "17 种"记为待对齐约数）。
 *
 * <p>零工具最小任务下可观察的映射行：{@code start}、{@code thinking_start}/{@code thinking_delta}、
 * {@code content_start}/{@code content_delta}、{@code turn_usage}、{@code turn}/{@code content}、
 * {@code complete}+{@code done}。工具相关行（{@code tool_call}/{@code tool_result} 及 todo/push/ask_user
 * 特例合成）随工具团队交付后才能构造，本类不作断言。
 *
 * <p>主断言：① 段状态机不得"先 delta 后 start"或跨段错序；② 轮次用量/轮次边界必须在终态组之前；
 * ③ 终态组 {@code complete} → {@code done} 且 done 收尾；④ **无源事件一律不得出现**（不伪造）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCompatTranslatorMapE2EIT extends DaCompatStreamTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INPUT = "先想一下，再用两句话说明数据分析的第一步和第二步，不要调用任何工具。";

    /** L2 映射表中标"core 无源、本批不产出"的事件：出现即等于伪造。 */
    private static final List<String> SOURCELESS = List.of(
            "content_reset", "progress", "compaction", "advisory_limit_reached",
            "workflow_event", "workflow_complete", "workflow_start",
            "subagent_start", "subagent_end", "message_queued", "message_accepted",
            "push_content_chunk");

    @Test
    @Story("da.compat.translator-map: 段落状态机、轮次组织与无源不伪造")
    @DisplayName("da.compat.translator-map: 事件映射逐行合规，无源事件不得伪造")
    void translatorMapFollowsSegmentStateMachine() throws Exception {
        initClient();
        String sessionId = "sess-map-" + UUID.randomUUID();
        HttpResponse<InputStream> response =
                DaCompatSseSupport.openRunStream(http, base, sessionId, INPUT, READ_TIMEOUT);
        assertThat(response.statusCode()).isEqualTo(200);

        List<Frame> frames = DaCompatSseSupport.readFramesUntilEof(response.body(), Duration.ofSeconds(90))
                .stream().filter(frame -> !frame.heartbeat()).toList();
        Allure.addAttachment("da.compat.translator-map 事件序列", "text/plain",
                frames.stream().map(Frame::event).toList().toString());
        assertThat(frames).isNotEmpty();

        List<String> names = frames.stream().map(Frame::event).toList();

        // ④ 无源不伪造（先判，失败时报错最直观）
        assertThat(names).as("L2 标注无源的事件不得出现（不伪造）").doesNotContainAnyElementsOf(SOURCELESS);

        // ① 段落状态机：任何 *_delta 之前必须先有对应段的 *_start
        assertSegmentOpenedBeforeDelta(names, "thinking");
        assertSegmentOpenedBeforeDelta(names, "content");
        if (names.contains("thinking_start") && names.contains("content_start")) {
            assertThat(names.indexOf("thinking_start"))
                    .as("thinking 段必须先于 content 段开场（段切换顺序）")
                    .isLessThan(names.indexOf("content_start"));
        }

        // ② 轮次用量与轮次边界在终态组之前
        int completeIndex = names.indexOf("complete");
        assertThat(completeIndex).as("必须出现 complete 终态事件").isGreaterThanOrEqualTo(0);
        for (String turnScoped : List.of("turn", "turn_usage")) {
            int index = names.indexOf(turnScoped);
            if (index >= 0) {
                assertThat(index).as("%s 必须出现在 complete 之前", turnScoped).isLessThan(completeIndex);
            }
        }

        // ③ 终态组：complete → done，done 收尾
        assertThat(names).as("终态组必须是 complete → done").containsSubsequence("complete", "done");
        assertThat(names.get(names.size() - 1)).as("done 必须是最后一帧").isEqualTo("done");

        // 字段面：start 带 taskId/agentType；done 带 status=completed；每帧 data 为单行 JSON
        Frame start = frames.get(0);
        assertThat(start.event()).isEqualTo("start");
        JsonNode startData = MAPPER.readTree(start.data());
        assertThat(startData.path("taskId").asText()).as("start 必须带 taskId").isNotBlank();
        assertThat(startData.path("agentType").asText()).as("agentType 缺省应为 general").isEqualTo("general");

        frames.forEach(frame -> assertThat(frame.data()).as("data 必须单行").doesNotContain("\n"));
        JsonNode doneData = MAPPER.readTree(frames.get(frames.size() - 1).data());
        assertThat(doneData.path("status").asText()).isEqualTo("completed");
    }

    private static void assertSegmentOpenedBeforeDelta(List<String> names, String segment) {
        int delta = names.indexOf(segment + "_delta");
        if (delta >= 0) {
            int opened = names.indexOf(segment + "_start");
            assertThat(opened)
                    .as("%s_delta 之前必须先发 %s_start（段落状态机）", segment, segment)
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(delta);
        }
    }
}
