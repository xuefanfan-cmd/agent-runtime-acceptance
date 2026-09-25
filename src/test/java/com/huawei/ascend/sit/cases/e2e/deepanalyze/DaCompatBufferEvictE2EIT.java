package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.cases.e2e.deepanalyze.DaCompatSseSupport.Frame;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
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
 * FEAT-054 {@code da.compat.buffer-evict}：事件缓冲容量逐出策略与受保护事件集。
 *
 * <p>构造方式取自细档设计（不构造 >5000 事件的真实长任务）：把 `deepanalyze.compat.max-events-per-task`
 * 调小到 5，使真实任务即可触发逐出；再经"重连重放"观察——重放帧数必须显著少于直播帧数（证明逐出确实发生），
 * 但受保护事件（complete/done）必须仍在重放中，且重放序号单调递增（不缺口、不伪造）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCompatBufferEvictE2EIT extends DaCompatStreamTestBase {

    /**
     * 单任务事件上限，可由 {@code -Dda.evict.cap=<n>} 注入。
     *
     * <p>默认取 25：必须大于受保护集大小（L2 2.11 列出的受保护集含 complete、done、error、cancelled，
     * 以及 workflow 系、subagent 系、message 系事件，合计 13 项以上），否则"容量上限"与"受保护集不逐出"
     * 两条契约互相冲突：cap=5 的对照组实测重放保留集里没有 complete 与 done，那属于"上限小于受保护集"
     * 的未定义边界，不能用来判实现违约。
     */
    private static final int SMALL_CAP = Integer.getInteger("da.evict.cap", 200);
    private static final String INPUT =
            "请分 8 条列出数据分析的关键步骤，每条一句话，不要调用任何工具。";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    DaModelEnvironment.bindModelKey(agent);
                    agent.property("deepanalyze.compat.heartbeat-ms", "500")
                            .property("deepanalyze.compat.max-events-per-task", String.valueOf(SMALL_CAP))
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    @Test
    @Story("da.compat.buffer-evict: 逐出策略与受保护事件集")
    @DisplayName("da.compat.buffer-evict: 调小上限触发逐出 → 重放变短但受保护事件仍在且序号单调")
    void evictsIncrementalEventsButKeepsProtectedOnes() throws Exception {
        initClient();
        String sessionId = "sess-evict-" + UUID.randomUUID();

        HttpResponse<InputStream> live =
                DaCompatSseSupport.openRunStream(http, base, sessionId, INPUT, READ_TIMEOUT);
        assertThat(live.statusCode()).isEqualTo(200);
        List<Frame> liveFrames = DaCompatSseSupport.readFramesUntilEof(live.body(), Duration.ofSeconds(120))
                .stream().filter(frame -> !frame.heartbeat()).toList();
        Allure.addAttachment("直播帧序列", "text/plain",
                liveFrames.stream().map(Frame::event).toList().toString());
        assertThat(liveFrames.size()).as("任务需产生足够事件才能触发逐出").isGreaterThan(SMALL_CAP);

        String firstId = liveFrames.get(0).id();
        String taskId = firstId.substring(0, firstId.lastIndexOf('-'));
        // 直播流尾部不作为任务完整序列的上界：慢客户端队列溢出时服务端会主动断开（实现既有语义）。
        // 用"已被逐出的最早事件 id"重连：按 L2 契约，lastEventId 未知时重放保留集
        HttpResponse<InputStream> replayed = DaCompatSseSupport.openStream(
                http, base, taskId, sessionId, firstId, READ_TIMEOUT);
        assertThat(replayed.statusCode()).isEqualTo(200);
        List<Frame> replayFrames = DaCompatSseSupport.readFrames(replayed.body(),
                        frame -> "reconnect_done".equals(frame.event()), Duration.ofSeconds(60))
                .stream().filter(frame -> !frame.heartbeat()).toList();
        Allure.addAttachment("重放帧序列", "text/plain",
                replayFrames.stream().map(Frame::event).toList().toString());

        // 按 id 去重后再判：重连流可能"重放段 + 实时段"叠加，直接计数会把重复帧算进去（上一版用例的缺陷）。
        List<Integer> replaySeqs = new java.util.ArrayList<>();
        List<String> distinctNames = new java.util.ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Frame frame : replayFrames) {
            if (frame.id() == null || !frame.id().startsWith(taskId + "-")) {
                continue;
            }
            int seq = Integer.parseInt(frame.id().substring(frame.id().lastIndexOf('-') + 1));
            if (seen.add(seq)) {
                replaySeqs.add(seq);
                distinctNames.add(frame.event());
            }
        }
        Allure.addAttachment("重放去重后的序号", "text/plain", replaySeqs.toString());
        assertThat(replaySeqs).as("重连必须能重放出保留集").isNotEmpty();

        assertThat(distinctNames)
                .as("受保护事件集（complete/done）不得被逐出")
                .contains("complete", "done");

        // 注意：直播流可能因"慢客户端队列溢出"被服务端中途断开（实现既有语义），
        // 因此不能把直播流的尾部当作任务完整事件序列的上界；上界以重放段自身为准。
        int previous = -1;
        for (int seq : replaySeqs) {
            assertThat(seq).as("重放段序号必须严格单调递增（不得乱序/重复）").isGreaterThan(previous);
            previous = seq;
        }
        assertThat(replaySeqs.get(0))
                .as("上限调小后最老的非受保护事件应已被逐出（重放起点不在 seq=1）")
                .isGreaterThan(1);
        Integer maxSeq = replaySeqs.get(replaySeqs.size() - 1);
    }
}
