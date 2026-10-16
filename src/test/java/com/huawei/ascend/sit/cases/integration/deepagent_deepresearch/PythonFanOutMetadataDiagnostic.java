package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.MessageProtocol;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UpdateEvent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * FEAT-019 D2 evidence gatherer(**diagnostic, 不是断言用例**) —— 面向 dev team 的证据交付器。
 *
 * <p><b>为什么存在</b>:2026-09-22 dev team 对 D2(fan-out metadata 承载位)的回复是"这条可能是
 * runtime 侧诊断问题,别急归 demo,需要 SIT 侧先出抓包证据"。本 diagnostic 就是回应这条 —— 跑一次
 * COMPARISON,dump Python demo wire 里每一层可能的 metadata 承载位,证明 SIT 侧探针
 * ({@link com.huawei.ascend.sit.conversation.RemoteInvocationProbe}) 认识的两条通路
 * (legacy {@code part.metadata._remote_invocation} + new {@code artifact/message.metadata.agentEvent})
 * 当前**都为空**,再由 dev team 判定归位到 runtime emitter 还是 demo 装配层。
 *
 * <p><b>依赖 D1 修复</b>:本 diagnostic 用 {@code awaitState(COMPLETED)},若 D1(RemoteDelegationRail
 * resume 短路)未修,任务会无限循环最终超时,dump 不会触发。**运行顺序**必须先 D1 修好、
 * {@code ParallelSearchComparisonPythonTest} 跑通,再手动 unlock 本 diagnostic 出证据。
 *
 * <p><b>使用方式</b>:
 * <ol>
 *   <li>D1 修复后,dev team 拉起 Python demo(deep-research:18090 + search:18091 + verify:18093);</li>
 *   <li>在本文件上把 {@code @Disabled} 注解注释掉;</li>
 *   <li>{@code source ~/.llmrc; unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy};</li>
 *   <li>{@code ./mvnw -Dtest.env=openjiuwen -Dtest=PythonFanOutMetadataDiagnostic test};</li>
 *   <li>把 stdout 里 {@code === D2.EVIDENCE ===} 块整段贴给 dev team。</li>
 * </ol>
 *
 * <p><b>dump 内容</b>(一次 run 产出以下四段):
 * <ol>
 *   <li>{@code === D2.COUNTS ===}:全量 event 里 {@code _remote_invocation} / {@code agentEvent} /
 *       {@code toolCallId} 出现次数。三者为 0 = SIT 侧的两条探针通路都无输入 → 需要 runtime/demo 补承载。</li>
 *   <li>{@code === D2.SAMPLE.CLIENT_EVENTS ===}:前 20 条 ClientEvent 的类名 + 顶层 kind 摘要,
 *       让 dev team 快速看到 event 骨架。</li>
 *   <li>{@code === D2.PART_METADATA ===}:每个 Part(TextPart/DataPart)的 metadata 键集合
 *       (证明"没有 _remote_invocation" 而不是"探针找错位")。</li>
 *   <li>{@code === D2.ARTIFACT_METADATA ===}:每个 Artifact 和 Task 顶层 metadata 键集合
 *       (证明"也没有 agentEvent")。</li>
 * </ol>
 *
 * <p><b>为什么不做断言</b>:diagnostic ≠ 测试。断言的语义是"回归发现问题",本类语义是"给合作方证据",
 * 命名和目录归属都保持 SIT 结构(方便 mvn 挂载 + 环境注入),但只 log 不 assert,避免污染绿态。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-parallel-search")
@Tag("python-demo")
@Tag("diagnostic")
@Feature("FEAT-019: 智能体生成并行的下游智能体调用委托")
@Story("FEAT-019.D2: fan-out metadata wire capture(证据交付)")
@Disabled("manual-only D2 evidence gatherer; enable after D1 fix + Python demo up,"
        + " see docs/cases/FEAT-019-parallel-downstream-agent-tasks-generation-and-handoff-python.md §3.D2.evidence")
class PythonFanOutMetadataDiagnostic extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(PythonFanOutMetadataDiagnostic.class.getName());

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String DEEP_RESEARCH_URL = "http://127.0.0.1:18090";
    private static final int SAMPLE_EVENT_LIMIT = 20;

    private static final String COMPARISON_QUERY =
            "对比 DeepSeek V3、Qwen-Max、Doubao-pro 三家的大模型 API 输入定价,"
                    + "统一换算为 USD/百万 token,逐项给出价格、上下文窗口、引用来源与置信度。";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .remoteAgent(DEEP_RESEARCH, DEEP_RESEARCH_URL);
    }

    @Test
    @DisplayName("D2.EVIDENCE (Python): dump fan-out metadata channels for dev-team triage")
    void dumpFanOutMetadataChannels() {
        InteractionFlow.of(client(DEEP_RESEARCH))
                .withTimeoutMs(900_000L)
                .protocol(MessageProtocol.A2A_STREAM)
                .send(COMPARISON_QUERY)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(ctx -> {
                        List<ClientEvent> clientEvents = clientEventsFrom(ctx.events());
                        emitEvidenceBanner(ctx.events().size(), clientEvents.size());
                        emitCounts(clientEvents);
                        emitClientEventSample(clientEvents);
                        emitPartMetadata(clientEvents);
                        emitArtifactMetadata(clientEvents);
                        emitFooter();
                    })
                .execute();
    }

    private static void emitEvidenceBanner(int inboundCount, int clientCount) {
        LOG.info("");
        LOG.info("=== D2.EVIDENCE ============================================================");
        LOG.info(String.format("inboundEvents=%d rawClientEvents=%d", inboundCount, clientCount));
    }

    /**
     * 关键计数:如果三者都为 0,SIT 侧探针的两条通路都没入口,即证明 wire 缺 fan-out 承载位。
     * 反之任一非 0 都是"承载位存在但探针没读到"的线索,归位到 SIT 探针适配问题。
     */
    private static void emitCounts(List<ClientEvent> events) {
        int remoteInvocation = 0;
        int agentEventDelegation = 0;
        int agentEventAny = 0;
        int toolCallIdRefs = 0;
        for (ClientEvent e : events) {
            for (Map<String, Object> md : allPartMetadata(e)) {
                if (md.containsKey("_remote_invocation")) remoteInvocation++;
                if (md.containsKey("toolCallId")) toolCallIdRefs++;
            }
            for (Map<String, Object> md : allObjectMetadata(e)) {
                Object ae = md.get("agentEvent");
                if (ae instanceof Map<?, ?> aeMap) {
                    agentEventAny++;
                    if ("delegation".equals(aeMap.get("type"))) agentEventDelegation++;
                }
            }
        }
        LOG.info("=== D2.COUNTS ==============================================================");
        LOG.info(String.format("part.metadata._remote_invocation .......... %d", remoteInvocation));
        LOG.info(String.format("part.metadata.toolCallId .................. %d", toolCallIdRefs));
        LOG.info(String.format("artifact/message.metadata.agentEvent (any). %d", agentEventAny));
        LOG.info(String.format("artifact/message.metadata.agentEvent=deleg. %d", agentEventDelegation));
        LOG.info("(all three ~0 → SIT probe has no input channel → runtime/demo needs to emit)");
    }

    private static void emitClientEventSample(List<ClientEvent> events) {
        LOG.info("=== D2.SAMPLE.CLIENT_EVENTS (first " + SAMPLE_EVENT_LIMIT + ") ===============");
        int i = 0;
        for (ClientEvent e : events) {
            if (i >= SAMPLE_EVENT_LIMIT) break;
            LOG.info(String.format("[%02d] %s :: %s", i, e.getClass().getSimpleName(), summarize(e)));
            i++;
        }
    }

    /**
     * Part 级 metadata key 集合。dev team 关心的是"这里有没有 _remote_invocation";如果 keys 为空
     * 或不含 _remote_invocation,legacy probe 通路死路,需要 runtime 补 emit。
     */
    private static void emitPartMetadata(List<ClientEvent> events) {
        LOG.info("=== D2.PART_METADATA (per Part) ============================================");
        int idx = 0;
        for (ClientEvent e : events) {
            for (Map<String, Object> md : allPartMetadata(e)) {
                LOG.info(String.format("[%03d] %s :: keys=%s", idx++,
                        e.getClass().getSimpleName(), md.keySet()));
                if (idx >= 100) {
                    LOG.info("  ... (truncated at 100 parts) ...");
                    return;
                }
            }
        }
    }

    /**
     * Artifact / Task / Message 顶层 metadata key 集合。dev team 关心的是"这里有没有 agentEvent";
     * 如果全是 null 或不含 agentEvent,new-wire probe 通路死路,需要 runtime 补 emit 或 demo 装适配。
     */
    private static void emitArtifactMetadata(List<ClientEvent> events) {
        LOG.info("=== D2.ARTIFACT_METADATA (per Artifact/Task/Message) =======================");
        int idx = 0;
        for (ClientEvent e : events) {
            for (Map<String, Object> md : allObjectMetadata(e)) {
                LOG.info(String.format("[%03d] %s :: keys=%s", idx++,
                        e.getClass().getSimpleName(), md == null ? "<null>" : md.keySet()));
                if (idx >= 100) {
                    LOG.info("  ... (truncated at 100 objects) ...");
                    return;
                }
            }
        }
    }

    private static void emitFooter() {
        LOG.info("=== D2.EVIDENCE.END ========================================================");
        LOG.info("Copy blocks D2.COUNTS/SAMPLE/PART_METADATA/ARTIFACT_METADATA to dev team.");
        LOG.info("");
    }

    // ----- helpers -----

    private static String summarize(ClientEvent e) {
        if (e instanceof MessageEvent me) {
            Message m = me.getMessage();
            return "role=" + (m == null ? "?" : m.role())
                    + " parts=" + (m == null || m.parts() == null ? 0 : m.parts().size());
        }
        if (e instanceof TaskEvent te) {
            Task t = te.getTask();
            return "taskId=" + (t == null ? "?" : t.id())
                    + " state=" + (t == null || t.status() == null ? "?" : t.status().state());
        }
        if (e instanceof TaskUpdateEvent tue) {
            UpdateEvent ue = tue.getUpdateEvent();
            if (ue instanceof TaskStatusUpdateEvent su) {
                return "STATUS taskId=" + su.taskId()
                        + " state=" + (su.status() == null ? "?" : su.status().state());
            }
            if (ue instanceof TaskArtifactUpdateEvent au) {
                Artifact a = au.artifact();
                return "ARTIFACT taskId=" + au.taskId()
                        + " artifactId=" + (a == null ? "?" : a.artifactId())
                        + " parts=" + (a == null || a.parts() == null ? 0 : a.parts().size());
            }
            return "UNKNOWN_UPDATE " + (ue == null ? "<null>" : ue.getClass().getSimpleName());
        }
        return e.toString();
    }

    /** Collect every Part's metadata map from an event (skips parts that don't carry metadata channel). */
    private static List<Map<String, Object>> allPartMetadata(ClientEvent e) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Part<?>> parts = partsOf(e);
        if (parts == null) return out;
        for (Part<?> p : parts) {
            if (p instanceof TextPart tp && tp.metadata() != null) out.add(tp.metadata());
            else if (p instanceof DataPart dp && dp.metadata() != null) out.add(dp.metadata());
        }
        return out;
    }

    /** Collect every Artifact/Task/Message top-level metadata map from an event. Nulls are preserved as null-keyset markers. */
    private static List<Map<String, Object>> allObjectMetadata(ClientEvent e) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (e instanceof MessageEvent me) {
            Message m = me.getMessage();
            if (m != null) out.add(m.metadata());
        } else if (e instanceof TaskEvent te) {
            Task t = te.getTask();
            if (t != null) {
                out.add(t.metadata());
                if (t.artifacts() != null) {
                    for (Artifact a : t.artifacts()) {
                        if (a != null) out.add(a.metadata());
                    }
                }
            }
        } else if (e instanceof TaskUpdateEvent tue) {
            UpdateEvent ue = tue.getUpdateEvent();
            if (ue instanceof TaskArtifactUpdateEvent au && au.artifact() != null) {
                out.add(au.artifact().metadata());
            } else if (ue instanceof TaskStatusUpdateEvent su && su.status() != null
                    && su.status().message() != null) {
                out.add(su.status().message().metadata());
            }
        }
        return out;
    }

    private static List<Part<?>> partsOf(ClientEvent e) {
        if (e instanceof MessageEvent me) {
            Message m = me.getMessage();
            return m == null ? null : m.parts();
        }
        if (e instanceof TaskUpdateEvent tue
                && tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent au
                && au.artifact() != null) {
            return au.artifact().parts();
        }
        return null;
    }

    private static List<ClientEvent> clientEventsFrom(List<InboundEvent> events) {
        List<ClientEvent> out = new ArrayList<>();
        for (InboundEvent e : events) {
            if (e.raw() instanceof ClientEvent ce && !out.contains(ce)) {
                out.add(ce);
            }
        }
        return out;
    }
}
