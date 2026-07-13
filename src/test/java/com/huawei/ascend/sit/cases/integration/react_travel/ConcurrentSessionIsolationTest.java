package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-11-1 — 并发 Session 隔离（特性 2 Runtime session 隔离 + 特性 4 A2A 并发 task 隔离）.
 *
 * <p>同一进程向 mainplan 同时发起 2 个并发 A2A 请求，各自携带不同 sessionId，
 * 经 mainplan→trip→hotel 全链返回完整出差规划；断言两路在**协议字段**与**业务输出**
 * 两个层面均不串扰。
 *
 * <p>判定四档：A 两 task.id 互异 / B 两 contextId 各自稳定且互异 / C 两路事件流
 * 互不污染 / D 终态 artifact 文本各自命中关键词。详见 docs/cases/reactagent/A-11-1-*.md。
 *
 * <p>本类用 Java 21 virtual threads + {@link CountDownLatch} 同时起跑两路；
 * **不**走 {@link com.huawei.ascend.sit.client.InteractionFlow}—— Fluent DSL 为串行设计，
 * 在并发判定下两路 collector 各持一份事件队列才能验"互不污染"。
 *
 * <p>同 {@code SyncTravelPlanningTest}，本类依赖三 agent 全链 + 真 LLM，归 {@code integration} 层。
 * 栈走默认 streaming 模式（{@code message/stream} SSE，与 {@link SutStack.Builder} 默认一致）。
 */
@Tag("integration")
class ConcurrentSessionIsolationTest extends BaseManagedStackTest {

    private static final String CASES_RESOURCE =
            "testdata/integration/react_travel/a11-1-isolation-cases.json";
    private static final long ROUND_TIMEOUT_MS = 120_000;
    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .agent(HOTEL)
                .agent(TRIP, a -> a.downstream(HOTEL))
                .agent(MAINPLAN, a -> a.downstream(TRIP));
    }

    @Test
    @DisplayName("A-11-1: 2 个并发 session 在协议字段与业务输出层均不串扰")
    void concurrentSessionsStayIsolated() throws Exception {
        List<SessionCase> cases = loadCases();
        assertThat(cases).as("A-11-1 needs at least 2 session cases").hasSizeGreaterThanOrEqualTo(2);

        A2aServiceClient client = client(MAINPLAN);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<SessionOutcome>> futures = new ArrayList<>();
            for (SessionCase c : cases) {
                futures.add(executor.submit(() -> runOneSession(client, c, startLatch)));
            }
            startLatch.countDown(); // fire all at once

            List<SessionOutcome> outcomes = new ArrayList<>();
            for (Future<SessionOutcome> f : futures) {
                outcomes.add(f.get(ROUND_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS));
            }

            assertNoStreamErrors(outcomes);
            assertTaskIdsAreUnique(outcomes);
            assertContextIdsAreStableAndDistinct(outcomes);
            assertEventStreamsDoNotCrossPollinate(outcomes);
            assertArtifactsCarryOwnKeyword(outcomes);
        } finally {
            executor.shutdown();
        }
    }

    // ---- One session: send + await terminal + harvest observations ----

    private SessionOutcome runOneSession(A2aServiceClient client,
                                         SessionCase c,
                                         CountDownLatch startLatch) throws Exception {
        startLatch.await();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        Map<String, Object> metadata = Map.of(
                "userId", "concurrent-user-" + c.sessionId(),
                "agentId", "main-plan-agent");
        // sessionId 走 message.contextId — A2aAgentExecutor 从 contextId 派生
        // RuntimeIdentity.sessionId，metadata.sessionId 在 runtime 侧不被读取。
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(c.sessionId())
                .parts(List.of(new TextPart(c.input())))
                .build();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = streamError::set;

        client.sendMessage(message, metadata, consumers, errorHandler);
        TaskState finalState = collector.awaitTerminalState(ROUND_TIMEOUT_MS);

        List<ClientEvent> events = collector.snapshotAllEvents();
        Set<String> observedTaskIds = collectFromEvents(events, ConcurrentSessionIsolationTest::extractTaskId);
        Set<String> observedContextIds = collectFromEvents(events, ConcurrentSessionIsolationTest::extractContextId);

        String taskId = collector.findFirstTaskId();
        Task finalTask = taskId.isEmpty() ? null : client.getTask(taskId);
        String contextId = finalTask != null && finalTask.contextId() != null ? finalTask.contextId() : "";
        String artifactText = extractArtifactText(finalTask);

        return new SessionOutcome(
                c.sessionId(), c.expectedKeyword(),
                taskId, contextId,
                observedTaskIds, observedContextIds,
                finalState, artifactText,
                streamError.get());
    }

    // ---- A-11-1 assertions ----

    private static void assertNoStreamErrors(List<SessionOutcome> outcomes) {
        for (SessionOutcome o : outcomes) {
            Throwable err = o.streamError();
            if (err == null) continue;
            // Post-terminal cleanup race（同 B-06 §assertNoStreamErrors）：
            // awaitTerminalState 一收到 terminal 事件就返回（不等 SSE 完全 close），下一路
            // sendMessage 复用同一个 A2A SDK client 时取消上一路的残流，errorHandler 收到
            // CancellationException / IOException("Request cancelled") 等不同包装。
            // 关键定位：finalState.isFinal()=true 表明本路 terminal 事件已到、协议层数据完整，
            // post-terminal 的清理异常不影响 A-11-1 隔离语义层断言。
            boolean benignCleanup = o.finalState() != null && o.finalState().isFinal();
            assertThat(benignCleanup)
                    .as("session %s 流中产生非预期异常 (finalState=%s): %s",
                            o.sessionId(), o.finalState(), err)
                    .isTrue();
        }
    }

    /** A-11-1.A — task.id 唯一. */
    private static void assertTaskIdsAreUnique(List<SessionOutcome> outcomes) {
        List<String> taskIds = outcomes.stream().map(SessionOutcome::taskId).toList();
        assertThat(taskIds)
                .as("A-11-1.A: 两路 task.id 必须均非空且彼此不同")
                .allSatisfy(id -> assertThat(id).isNotBlank())
                .doesNotHaveDuplicates();
    }

    /** A-11-1.B — contextId 各自稳定（单值）且互异. */
    private static void assertContextIdsAreStableAndDistinct(List<SessionOutcome> outcomes) {
        for (SessionOutcome o : outcomes) {
            assertThat(o.observedContextIds())
                    .as("A-11-1.B: session %s 的事件流 contextId 应稳定（去重后只 1 个）",
                            o.sessionId())
                    .hasSizeLessThanOrEqualTo(1);
        }
        List<String> contextIds = outcomes.stream()
                .map(SessionOutcome::contextId)
                .filter(s -> !s.isEmpty())
                .toList();
        assertThat(contextIds)
                .as("A-11-1.B: 各路 contextId 必须互不相同")
                .doesNotHaveDuplicates();
    }

    /** A-11-1.C — 两路事件流互不污染. */
    private static void assertEventStreamsDoNotCrossPollinate(List<SessionOutcome> outcomes) {
        for (int i = 0; i < outcomes.size(); i++) {
            SessionOutcome self = outcomes.get(i);
            for (int j = 0; j < outcomes.size(); j++) {
                if (i == j) continue;
                String otherTaskId = outcomes.get(j).taskId();
                assertThat(self.observedTaskIds())
                        .as("A-11-1.C: session %s 不应观测到对方 task.id=%s",
                                self.sessionId(), otherTaskId)
                        .doesNotContain(otherTaskId);
            }
        }
    }

    /** A-11-1.D — artifact 文本各自命中自己的 expectedKeyword 且不命中对方. */
    private static void assertArtifactsCarryOwnKeyword(List<SessionOutcome> outcomes) {
        for (SessionOutcome o : outcomes) {
            assertThat(o.finalState())
                    .as("A-11-1.D: session %s 应到达 COMPLETED（否则 artifact 业务语义层失去观测面）",
                            o.sessionId())
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(o.artifactText())
                    .as("A-11-1.D: session %s 的 artifact 文本应包含自己的关键词 '%s'",
                            o.sessionId(), o.expectedKeyword())
                    .contains(o.expectedKeyword());
        }
        for (int i = 0; i < outcomes.size(); i++) {
            SessionOutcome self = outcomes.get(i);
            for (int j = 0; j < outcomes.size(); j++) {
                if (i == j) continue;
                String otherKeyword = outcomes.get(j).expectedKeyword();
                assertThat(self.artifactText())
                        .as("A-11-1.D: session %s 的 artifact 不应包含对方关键词 '%s'",
                                self.sessionId(), otherKeyword)
                        .doesNotContain(otherKeyword);
            }
        }
    }

    // ---- helpers ----

    private static Set<String> collectFromEvents(List<ClientEvent> events,
                                                 java.util.function.Function<ClientEvent, String> extractor) {
        Set<String> set = new LinkedHashSet<>();
        for (ClientEvent e : events) {
            String v = extractor.apply(e);
            if (v != null && !v.isEmpty()) {
                set.add(v);
            }
        }
        return set;
    }

    private static String extractTaskId(ClientEvent event) {
        if (event instanceof TaskEvent te) return te.getTask().id();
        if (event instanceof TaskUpdateEvent ue) return ue.getTask().id();
        return "";
    }

    private static String extractContextId(ClientEvent event) {
        if (event instanceof TaskEvent te) return nullSafe(te.getTask().contextId());
        if (event instanceof TaskUpdateEvent ue) return nullSafe(ue.getTask().contextId());
        return "";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Concatenate the task's textual output: artifacts → status message → last history message.
     * Mirrors {@code SyncTravelPlanningTest.textOf} so 业务语义层断言对 SUT 是否填 {@code artifacts} 不敏感。
     */
    private static String extractArtifactText(Task task) {
        if (task == null) return "";
        StringBuilder sb = new StringBuilder();
        if (task.artifacts() != null) {
            for (Artifact a : task.artifacts()) {
                appendText(sb, a.parts());
            }
        }
        if (sb.length() == 0 && task.status() != null && task.status().message() != null) {
            appendText(sb, task.status().message().parts());
        }
        if (sb.length() == 0 && task.history() != null && !task.history().isEmpty()) {
            appendText(sb, task.history().get(task.history().size() - 1).parts());
        }
        return sb.toString();
    }

    private static void appendText(StringBuilder sb, List<Part<?>> parts) {
        if (parts == null) return;
        for (Part<?> p : parts) {
            if (p instanceof TextPart tp && tp.text() != null) {
                sb.append(tp.text()).append('\n');
            }
        }
    }

    private List<SessionCase> loadCases() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CASES_RESOURCE)) {
            assertThat(is).as("cases resource %s on classpath", CASES_RESOURCE).isNotNull();
            JsonNode root = mapper.readTree(is);
            JsonNode arr = root.get("sessions");
            assertThat(arr).as("$.sessions array").isNotNull();
            List<SessionCase> list = new ArrayList<>();
            for (JsonNode n : arr) {
                list.add(new SessionCase(
                        n.get("sessionId").asText(),
                        n.get("input").asText(),
                        n.get("expectedKeyword").asText()));
            }
            return list;
        }
    }

    // ---- inner types ----

    private record SessionCase(String sessionId, String input, String expectedKeyword) {}

    private record SessionOutcome(
            String sessionId,
            String expectedKeyword,
            String taskId,
            String contextId,
            Set<String> observedTaskIds,
            Set<String> observedContextIds,
            TaskState finalState,
            String artifactText,
            Throwable streamError) {}
}