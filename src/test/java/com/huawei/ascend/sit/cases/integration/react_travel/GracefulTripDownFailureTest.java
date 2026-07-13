package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-09 — trip-agent 停止时 mainplan 优雅答复（异常分支首个用例）.
 *
 * <p>前置：trip-agent 必须不可达。两种获得方式：
 * <ul>
 *   <li><b>LOCAL（managed trip）</b>：本类 {@code ensureTripDown()} 调框架 fault-injection
 *       {@code stack.stop(TRIP)} 自停 trip 进程，无须人工 —— 跑完整个类 {@code @AfterAll}
 *       走 {@code stack.close()} 顺带清理，trip 不会复活去污染下一类。</li>
 *   <li><b>SIT（remote trip）</b>：{@code stack.stop()} 抛 IllegalStateException，被静默吃掉；
 *       需运维侧 {@code systemctl stop / kill} 把 trip 停掉，再跑本类。</li>
 * </ul>
 * 之后探活兜底（{@code abortIfTripStillReachable}）：trip 仍可达 → {@code Assumptions.assumeFalse}
 * SKIPPED，避免环境未就绪误报 FAIL。
 *
 * <p>判定四档：A 终态可达不挂死 / B 终态为失败信号 OR COMPLETED+致歉文本 /
 * C artifact 不漏堆栈 / D 流层无非预期异常。详见 docs/cases/reactagent/B-09-*.md。
 *
 * <p>标签 {@code @Tag("degraded")} 而非 {@code integration}——避免 -P integration
 * 默认套件把本类拖进绿色 CI（trip 停掉时正常用例会全红）。运行靠显式
 * {@code -Dtest=GracefulTripDownFailureTest}。
 *
 * <p>栈走 BaseManagedStackTest 默认形态；远端栈在 application-sit.yml 把三 agent 都
 * 标成 remote-url（{@code -Dtest.env=SIT}），SutStack.start() 仅注册地址不启动进程，本类天然走远端栈。
 */
@Tag("degraded")
class GracefulTripDownFailureTest extends BaseManagedStackTest {

    private static final String CASE_RESOURCE =
            "testdata/integration/react_travel/b09-trip-down-cases.json";
    private static final long ROUND_TIMEOUT_MS = 180_000;
    private static final Duration TRIP_PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";

    // B-09.B 优雅完成分支的证据词——任意命中即视为 mainplan 在 COMPLETED 时
    // 显式承认了下游不可用 / 无法规划。词表是经验启发式，**首次跑空就扩**，详见 doc §9。
    private static final List<String> APOLOGY_KEYWORDS = List.of(
            "抱歉", "对不起", "无法", "暂时不可", "不可用", "未能",
            "服务异常", "调用失败", "下游"
    );

    // B-09.C 堆栈泄漏证据词——任意命中即 FAIL（mainplan 把 JVM 异常原样回客户端）。
    // 只放**确定的** JVM 标志，避免误判 LLM 自然语言里的"Exception"单词。
    private static final List<String> STACK_TRACE_MARKERS = List.of(
            "java.net.",
            "java.io.IOException",
            "Caused by:",
            "Exception in thread",
            "at java.base/",
            "at io.netty.",
            "at org.springframework.",
            "at reactor."
    );

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .agent(HOTEL)
                .agent(TRIP, a -> a.downstream(HOTEL))
                .agent(MAINPLAN, a -> a.downstream(TRIP));
    }

    @Test
    @DisplayName("B-09: trip agent 停止时 mainplan 优雅答复用户（不挂死/不漏栈/不幻觉）")
    void mainplanRepliesGracefullyWhenTripIsDown() throws Exception {
        String tripBaseUrl = stack.baseUrl(TRIP);
        ensureTripDown();
        abortIfTripStillReachable(tripBaseUrl);

        SingleCase c = loadCase();
        A2aServiceClient client = client(MAINPLAN);

        // 同 B-06：runSuffix 防跨次跑残留 / 与其他用例共用 sessionId。
        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String userId = c.userId() + runSuffix;
        String sessionId = c.sessionId() + runSuffix;

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        Map<String, Object> metadata = Map.of(
                "userId", userId,
                "agentId", "main-plan-agent");
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(sessionId)
                .parts(List.of(new TextPart(c.input())))
                .build();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = streamError::set;

        client.sendMessage(message, metadata, consumers, errorHandler);
        TaskState finalState = collector.awaitTerminalState(ROUND_TIMEOUT_MS);

        String taskId = collector.findFirstTaskId();
        Task finalTask = taskId.isEmpty() ? null : client.getTask(taskId);
        String artifactText = extractArtifactText(finalTask);

        assertTerminalReached(finalState);
        assertTerminalSignalsFailureOrApology(finalState, artifactText);
        assertArtifactDoesNotLeakStackTrace(artifactText);
        assertNoUnexpectedStreamError(streamError.get(), finalState);
    }

    // ---- pre-flight ----

    /**
     * LOCAL（managed trip）：用框架 fault-injection API 自停 trip 进程，无须人工。
     * SIT（remote trip）：stop() 抛 IllegalStateException —— 静默吃掉，回落到下面的探活兜底，
     * 由人工 systemctl stop / kill 后再跑（同原 doc §3 远端口径）。
     */
    private void ensureTripDown() {
        try {
            if (stack.isRunning(TRIP)) {
                stack.stop(TRIP);
            }
        } catch (IllegalStateException remoteCannotStop) {
            // remote 模式下框架不拥有 trip，无能为力 — 留给 abortIfTripStillReachable 兜底。
        }
    }

    /**
     * 探活 trip-agent：可达 → SKIP（环境未就绪不当 FAIL）。
     * 不可达（连接拒绝 / 超时 / 任意 IOException）= 正确前置，静默继续。
     */
    private static void abortIfTripStillReachable(String tripBaseUrl) {
        boolean reachable;
        int statusCode = -1;
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(TRIP_PROBE_TIMEOUT)
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tripBaseUrl + "/.well-known/agent.json"))
                    .timeout(TRIP_PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            statusCode = resp.statusCode();
            // 200 = 服务在线；其它（502 / 503 等）视为实质不可用 = 正确前置
            reachable = statusCode == 200;
        } catch (Exception ignored) {
            reachable = false;
        }
        Assumptions.assumeFalse(
                reachable,
                "B-09 PRECONDITION: trip-agent 仍可达 (%s, status=%d). 本用例要求 trip 处于停止状态;"
                        + " 请先停止 trip-agent 后再跑（systemctl stop / kill <pid>）。"
                                .formatted(tripBaseUrl, statusCode));
    }

    // ---- B-09 assertions ----

    /** B-09.A — 终态可达，不挂死. */
    private static void assertTerminalReached(TaskState finalState) {
        assertThat(finalState)
                .as("B-09.A: 180s 内必须到达终态（mainplan 在下游不可达时挂死是严重契约违规）")
                .isNotNull();
        assertThat(finalState.isFinal())
                .as("B-09.A: finalState=%s 不是终态", finalState)
                .isTrue();
    }

    /**
     * B-09.B — 终态为协议级失败信号（FAILED/REJECTED/CANCELED），
     * 或 COMPLETED + artifact 文本至少命中一个致歉/降级关键词。
     */
    private static void assertTerminalSignalsFailureOrApology(
            TaskState finalState, String artifactText) {
        if (finalState == TaskState.TASK_STATE_FAILED
                || finalState == TaskState.TASK_STATE_REJECTED
                || finalState == TaskState.TASK_STATE_CANCELED) {
            return; // 协议层明确失败 = PASS
        }
        // 不是 COMPLETED 且不是上面三种 → 失败语义不明（INPUT_REQUIRED 等中间态）
        assertThat(finalState)
                .as("B-09.B: 期望失败终态 (FAILED/REJECTED/CANCELED) 或 COMPLETED+致歉文本，"
                        + "实得 %s（既非失败信号也非可优雅完成的成功）", finalState)
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        // COMPLETED 分支：必须有致歉/降级证据，否则可能是幻觉式假装规划成功
        boolean hasApology = APOLOGY_KEYWORDS.stream().anyMatch(artifactText::contains);
        assertThat(hasApology)
                .as("B-09.B: COMPLETED 但 artifact 不含任何致歉/降级关键词（%s），"
                        + "疑似 mainplan 在 trip 不可达时幻觉式假装规划成功。\nartifact 文本头 300 字: %s",
                        APOLOGY_KEYWORDS, truncate(artifactText, 300))
                .isTrue();
    }

    /** B-09.C — artifact 非空且不含 JVM 堆栈泄漏. */
    private static void assertArtifactDoesNotLeakStackTrace(String artifactText) {
        assertThat(artifactText)
                .as("B-09.C: artifact 文本不应为空（mainplan 至少要给客户端一个可读答复）")
                .isNotBlank();
        for (String marker : STACK_TRACE_MARKERS) {
            assertThat(artifactText)
                    .as("B-09.C: artifact 文本不应包含 JVM 堆栈标志 '%s'（mainplan 把下游异常原样回客户端）"
                            + "\nartifact 文本头 500 字: %s", marker, truncate(artifactText, 500))
                    .doesNotContain(marker);
        }
    }

    /** B-09.D — 流层无非预期异常（post-terminal cleanup race 视为良性，同 B-06）. */
    private static void assertNoUnexpectedStreamError(Throwable err, TaskState finalState) {
        if (err == null) return;
        boolean benignCleanup = finalState != null && finalState.isFinal();
        assertThat(benignCleanup)
                .as("B-09.D: 流中产生非预期异常 (finalState=%s): %s", finalState, err)
                .isTrue();
    }

    // ---- text extraction (mirrors B-06) ----

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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---- data loading ----

    private SingleCase loadCase() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CASE_RESOURCE)) {
            assertThat(is).as("cases resource %s on classpath", CASE_RESOURCE).isNotNull();
            JsonNode root = mapper.readTree(is);
            String userId = root.get("userId").asText();
            String sessionId = root.get("sessionId").asText();
            String input = root.get("input").asText();
            assertThat(userId).as("$.userId").isNotBlank();
            assertThat(sessionId).as("$.sessionId").isNotBlank();
            assertThat(input).as("$.input").isNotBlank();
            return new SingleCase(userId, sessionId, input);
        }
    }

    private record SingleCase(String userId, String sessionId, String input) {}
}