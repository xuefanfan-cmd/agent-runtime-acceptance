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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发流式**根因对照**（诊断用，不作为验收结论）：
 * ① 单流基线；② 并发 3 流（**不同会话**）；③ 并发 3 流（**同一会话**）。
 *
 * <p>目的：定位"并发同会话流式请求出现 500（服务端 InterruptedException: sse pump interrupted）"
 * 是并发本身、还是同会话共享状态导致。三组都要求：受理 200 且各自 start→done。
 *
 * <p><b>ISSUE #386 修复后回归（2026-09-23）</b>：{@link #sameSessionConcurrentStreamsMustNotReturnServerError()}
 * 承载本 ISSUE 的关闭判据——泵被中断时 {@code DaCompatController.poll} 必须返回优雅关流哨兵，
 * 不得向容器抛 IOException 收成 HTTP 500。原 ①~⑤ 组的"全部 200 且各自 start→done"判据保留不动：
 * 该判据还依赖 core 侧中断源（同会话共享 TaskScheduler 收尾中断兄弟任务），本 PR 未覆盖，
 * 因此修复后若仍不达标，只作为**未关闭的能力缺口**记录，不改写本 ISSUE 的缺陷面结论。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaConcurrentStreamDiagnosisE2EIT extends DaCompatStreamTestBase {

    @Override
    protected com.huawei.ascend.sit.lifecycle.SutStack.Builder buildStack(
            com.huawei.ascend.sit.config.TestConfig config) {
        // 不复用 super 的 Builder（会重复声明同一 agent），按基类口径内联装配并追加 DEBUG 开关
        return com.huawei.ascend.sit.lifecycle.SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    DaModelEnvironment.bindModelKey(agent);
                    agent.property("deepanalyze.compat.heartbeat-ms", "500")
                            // DEBUG：抓失败请求自身的内层异常（此前只有收尾期中断堆栈，不足以定根因）
                            .property("logging.level.com.openjiuwen.da", "DEBUG")
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    @Test
    @Story("诊断：单流基线")
    @DisplayName("对照组①：单条流式任务 → 200 且 start→done")
    void singleStreamBaseline() throws Exception {
        initClient();
        runGroup(1, true);
    }

    @Test
    @Story("诊断：并发 3 流（不同会话）")
    @DisplayName("对照组②：3 条并发流式任务（不同会话）→ 全部 200 且各自跑通")
    void threeConcurrentStreamsDifferentSessions() throws Exception {
        initClient();
        runGroup(3, false);
    }

    @Test
    @Story("诊断：并发 3 流（同一会话）")
    @DisplayName("对照组③：3 条并发流式任务（同一会话）→ 全部 200 且各自跑通")
    void threeConcurrentStreamsSameSession() throws Exception {
        initClient();
        runGroup(3, true);
    }

    @Test
    @Story("诊断：并发 2 流（同一会话）")
    @DisplayName("对照组④：2 条并发流式任务（同一会话）→ 失败率档位")
    void twoConcurrentStreamsSameSession() throws Exception {
        initClient();
        runGroup(2, true);
    }

    @Test
    @Story("诊断：并发 5 流（同一会话）")
    @DisplayName("对照组⑤：5 条并发流式任务（同一会话）→ 失败率档位")
    void fiveConcurrentStreamsSameSession() throws Exception {
        initClient();
        runGroup(5, true);
    }

    /**
     * **ISSUE #386 关闭判据**（修复后新增）：同一会话并发 3 条流式任务，任何一条都不得因泵被中断而
     * 返回 5xx。修复前实测该组合必现 HTTP 500（{@code java.io.IOException: sse pump interrupted}
     * @ {@code DaCompatController.poll}）；修复后 {@code poll} 遇 {@code InterruptedException} 返回
     * 优雅关流哨兵，失败形态变为"可重连断连"（客户端按 Last-Event-ID 重连补齐）。
     *
     * <p>本方法只判定本 ISSUE 的缺陷面（不出现 5xx），并把"是否各自跑到 done"作为**证据记录**而非断言——
     * 后者依赖 core 侧中断源修复，见类注释。
     */
    @Test
    @Story("ISSUE-386 回归：同会话并发流式不得收成 5xx（泵中断须优雅关流）")
    @DisplayName("#386 回归：同会话并发 3 流 → 全部 200，无 5xx")
    void sameSessionConcurrentStreamsMustNotReturnServerError() throws Exception {
        initClient();
        int count = 3;
        String sharedSession = "sess-issue386-" + UUID.randomUUID();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    HttpResponse<InputStream> response = DaCompatSseSupport.openRunStream(http, base, sharedSession,
                            "请用一句话回答：" + (index + 1) + "+1 等于几。不要调用任何工具。",
                            Duration.ofMinutes(10));
                    int status = response.statusCode();
                    if (status != 200) {
                        String body = new String(response.body().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        return "status=" + status + ",body=" + body.replace("\n", " ");
                    }
                    // 有界等待：不要求出现 done（那是未关闭的能力缺口），只记录读到的事件面。
                    DaCompatSseSupport.LiveReader reader = DaCompatSseSupport.liveReader(response.body(),
                            frame -> "done".equals(frame.event()));
                    List<Frame> frames = reader.awaitFinish(Duration.ofSeconds(120));
                    reader.close();
                    List<String> events = frames.stream().filter(f -> !f.heartbeat()).map(Frame::event).toList();
                    return "status=200,hasDone=" + events.contains("done") + ",events=" + events;
                } catch (Exception failure) {
                    return "EX:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
                }
            }));
        }
        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> future : futures) {
            results.add(future.get(8, TimeUnit.MINUTES));
        }
        Allure.addAttachment("#386 回归：同会话并发 3 流结果", "text/plain", results.toString());
        assertThat(results)
                .as("ISSUE #386 关闭判据：同会话并发流式不得出现 5xx（泵中断必须优雅关流，不得收成 500）")
                .allSatisfy(result -> assertThat(result).contains("status=200"));
    }

    private void runGroup(int count, boolean sameSession) throws Exception {
        String shared = "sess-diag-" + UUID.randomUUID();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            String session = sameSession ? shared : "sess-diag-" + UUID.randomUUID();
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    HttpResponse<InputStream> response = DaCompatSseSupport.openRunStream(http, base, session,
                            "请用一句话回答：" + (index + 1) + "+1 等于几。不要调用任何工具。", Duration.ofMinutes(10));
                    int status = response.statusCode();
                    if (status != 200) {
                        // 补证：非 200 时把响应体一起落盘（便于开发直接看到失败形态）
                        String body = new String(response.body().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        // 关键：趁 SUT 仍存活抓日志尾部（收尾后堆栈会被 shutdown 中断污染）
                        Thread.sleep(15_000L);
                        Path log = ((com.huawei.ascend.sit.lifecycle.ManagedSutInstance)
                                stack.managedInstance(AGENT)).logFile();
                        String tail = tailOf(log, 60);
                        io.qameta.allure.Allure.addAttachment("失败请求期间的 SUT 日志尾部（存活期）",
                                "text/plain", tail);
                        return "status=" + status + ",body=" + body.replace("\n", " ")
                                + ",logTail=" + tail.replace("\n", " | ");
                    }
                    List<Frame> frames = DaCompatSseSupport.readFrames(response.body(),
                            frame -> "done".equals(frame.event()), Duration.ofMinutes(5));
                    List<String> events = frames.stream().filter(f -> !f.heartbeat()).map(Frame::event).toList();
                    return "status=200,events=" + events.size()
                            + ",hasDone=" + events.contains("done");
                } catch (Exception failure) {
                    return "EX:" + failure.getMessage();
                }
            }));
        }
        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> future : futures) {
            results.add(future.get(6, TimeUnit.MINUTES));
        }
        Allure.addAttachment("组结果（count=" + count + ",sameSession=" + sameSession + "）",
                "text/plain", results.toString());
        assertThat(results).as("每组内每条流都必须 200 且跑通（count=%s sameSession=%s）", count, sameSession)
                .allSatisfy(result -> assertThat(result).contains("status=200").contains("hasDone=true"));
    }

    /** 取日志末尾 n 行（用于在 SUT 存活期捕获失败请求的内层异常）。 */
    private static String tailOf(java.nio.file.Path log, int lines) {
        try {
            java.util.List<String> all = java.nio.file.Files.readAllLines(log);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (Exception failure) {
            return "(读取 SUT 日志失败: " + failure.getMessage() + ")";
        }
    }
}
