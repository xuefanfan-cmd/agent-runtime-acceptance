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
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 同会话并发（{@code da.compat.concurrent-same-session}）**悬挂补证**：被挡下的兄弟请求是否落终态？
 *
 * <p>背景：#386（PR !672）修复后同会话并发不再返回 500，但诊断记录显示部分流在 {@code start} 之后即关流、
 * 既无 {@code error} 也无 {@code done}。本类把该现象推进为**外部可复核**的判据，用于判定"会话忙"拒绝
 * 是否被兼容端点丢进了黑洞：
 *
 * <ol>
 *   <li>主判据：凡客户端已收到 {@code start}（即已被受理）的流，其任务在流结束之后，
 *       {@code GET /agent/status/{taskId}} 不得仍报 {@code running}；</li>
 *   <li>对照证据：每条流的事件序列、任务状态原文，以及 SUT 侧实际进入 core 执行的请求条数
 *       （{@code JiuwenCoreAgentHandler streamQuery} 计数）一并落证据。</li>
 * </ol>
 *
 * <p>Oracle 依据：DFX-002「任务执行隔离 MUST——一个任务的异常、超时或资源异常不得影响其他并行任务的正常执行；
 * 任务异常必须被包含在该任务的生命周期内」＋ L2 §2.11 兼容端点事件面（任务须有终态组）。
 * 兼容端点在任务受理时先推 {@code start}（{@code DaCompatController} 里紧随其后的才是 {@code submit}），
 * 因此"受理过却没有终态"即违反该 MUST。
 *
 * <p>本类属**诊断/补证**用，不并入 18 类验收回归集；结论只作缺陷证据，不改写 #386 的缺陷面结论。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaConcurrentSiblingTerminalE2EIT extends DaCompatStreamTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TASK_ID = Pattern.compile("\"taskId\"\\s*:\\s*\"([^\"]+)\"");
    private static final int CONCURRENCY = 3;
    private static final Duration STREAM_WAIT = Duration.ofSeconds(150);

    @Override
    protected com.huawei.ascend.sit.lifecycle.SutStack.Builder buildStack(
            com.huawei.ascend.sit.config.TestConfig config) {
        // 不复用 super 的 Builder（会重复声明同一 agent），按基类口径内联装配并追加 DEBUG 开关
        return com.huawei.ascend.sit.lifecycle.SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    agent.property("deepanalyze.compat.heartbeat-ms", "500")
                            .property("logging.level.com.openjiuwen.da", "DEBUG")
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    @Test
    @Story("da.compat.concurrent-same-session: 已受理的兄弟请求必须有终态（不得悬挂到容器超时）")
    @DisplayName("同会话并发 3 流：已被受理的任务在流结束后不得仍为 running")
    void acceptedSiblingStreamsMustReachTerminalState() throws Exception {
        initClient();
        String sharedSession = "sess-busyprobe-" + UUID.randomUUID();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> runOne(sharedSession, index)));
        }
        List<String> outcomes = new ArrayList<>();
        for (CompletableFuture<String> future : futures) {
            outcomes.add(future.get(10, TimeUnit.MINUTES));
        }
        Allure.addAttachment("每流结果（httpStatus/事件序列/taskId/终态查询）", "text/plain", outcomes.toString());
        Allure.addAttachment("SUT 侧进入 core 执行的请求条数（同会话）", "text/plain", coreExecutionEvidence(sharedSession));

        assertThat(outcomes)
                .as("每条已被受理（收到 start）的流都必须有落定结果，不得以异常字符串收场")
                .allSatisfy(outcome -> assertThat(outcome).doesNotContain("EX:"));
        assertThat(outcomes)
                .filteredOn(outcome -> outcome.contains("hasStart=true"))
                .as("按 DFX-002 任务执行隔离 MUST：已受理任务在客户端流结束之后不得仍为 running（拒绝必须被表达）")
                .allSatisfy(outcome -> assertThat(outcome).doesNotContain("statusAfter=running"));
        assertThat(outcomes)
                .filteredOn(outcome -> outcome.contains("hasStart=true"))
                .as("已受理的流必须出现终态事件 done（不得只到 start 就被容器超时关流）")
                .allSatisfy(outcome -> assertThat(outcome).contains("hasDone=true"));
        assertThat(outcomes)
                .filteredOn(outcome -> outcome.contains("hasStart=true") && !outcome.contains("hasComplete=true"))
                .as("未正常完成的流必须有明确失败面：error + done{failed}，且错误码为 ENGINE_BUSY（会话忙/池饱和同码）")
                .allSatisfy(outcome -> assertThat(outcome).contains("hasError=true").contains("hasEngineBusy=true"));
    }

    private String runOne(String sessionId, int index) {
        try {
            HttpResponse<InputStream> response = DaCompatSseSupport.openRunStream(http, base, sessionId,
                    "请用一句话回答：" + (index + 1) + "+1 等于几。不要调用任何工具。", Duration.ofMinutes(10));
            int httpStatus = response.statusCode();
            if (httpStatus != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                return "index=" + index + ",httpStatus=" + httpStatus + ",body=" + body.replace("\n", " ");
            }
            // 有界等待：不要求出现 done（那正是被测点），只收集到期前实际到达的事件。
            DaCompatSseSupport.LiveReader reader = DaCompatSseSupport.liveReader(response.body(),
                    frame -> "done".equals(frame.event()));
            List<Frame> frames = reader.awaitFinish(STREAM_WAIT);
            reader.close();
            List<String> events = frames.stream().filter(frame -> !frame.heartbeat()).map(Frame::event).toList();
            String taskId = taskIdOf(frames);
            String statusAfter = taskId == null ? "n/a" : statusOf(taskId, sessionId);
            boolean hasEngineBusy = frames.stream().anyMatch(frame -> frame.data() != null
                    && frame.data().contains("ENGINE_BUSY"));
            return "index=" + index + ",httpStatus=200,hasStart=" + events.contains("start")
                    + ",hasComplete=" + events.contains("complete")
                    + ",hasError=" + events.contains("error")
                    + ",hasDone=" + events.contains("done")
                    + ",hasEngineBusy=" + hasEngineBusy
                    + ",events=" + events + ",taskId=" + taskId + ",statusAfter=" + statusAfter;
        } catch (Exception failure) {
            return "index=" + index + ",EX:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
        }
    }

    /** 从 {@code start} 帧数据里取 taskId（兼容端点受理时会把 taskId 放进 start 的 data）。 */
    private static String taskIdOf(List<Frame> frames) {
        for (Frame frame : frames) {
            if ("start".equals(frame.event()) && frame.data() != null) {
                Matcher matcher = TASK_ID.matcher(frame.data());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }

    /** 查询任务终态；返回"状态字段|原文"，状态字段留空表示响应里没有 status。 */
    private String statusOf(String taskId, String sessionId) {
        try {
            HttpResponse<String> status = DaCompatSseSupport.getStatus(http, base, taskId, sessionId,
                    Duration.ofSeconds(20));
            String raw = status.body() == null ? "" : status.body().replace("\n", " ");
            if (status.statusCode() != 200) {
                return "http" + status.statusCode() + "|raw=" + raw;
            }
            return MAPPER.readTree(status.body()).path("status").asText("(absent)") + "|raw=" + raw;
        } catch (Exception failure) {
            return "queryFailed:" + failure.getClass().getSimpleName() + "|raw=" + failure.getMessage();
        }
    }

    /** 统计 SUT 日志里同一会话进入 core 执行的请求条数（对照"受理 3 条 / 执行 N 条"）。 */
    private String coreExecutionEvidence(String sessionId) {
        try {
            Path log = ((com.huawei.ascend.sit.lifecycle.ManagedSutInstance)
                    stack.managedInstance(AGENT)).logFile();
            List<String> lines = java.nio.file.Files.readAllLines(log).stream()
                    .filter(line -> line.contains("streamQuery convId=" + sessionId))
                    .map(line -> line.replaceAll("\\s+---\\s+\\[deepanalyze-agent\\]\\s+", " | "))
                    .toList();
            return "coreExecutions=" + lines.size() + "\n" + String.join("\n", lines);
        } catch (Exception failure) {
            return "logReadFailed:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
        }
    }
}
