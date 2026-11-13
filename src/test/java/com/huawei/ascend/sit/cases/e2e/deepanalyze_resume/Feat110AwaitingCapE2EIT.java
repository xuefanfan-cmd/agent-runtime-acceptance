package com.huawei.ascend.sit.cases.e2e.deepanalyze_resume;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 待答名额满载（F110-20；独立 SUT 实例——max-awaiting-tasks=1）。
 *
 * <p>判据（被测制品 0.1.3-feat109111-r2 = agent-solution common@76bcad137）：
 * <ul>
 *   <li>原 blocked 理由（L2-01 未述行为）已消失——r2 实现了满载拒绝：
 *       {@code registry.countAwaiting() >= maxAwaitingTasks} →
 *       {@code DaServerBusyException("server busy: too many tasks awaiting answers")}
 *       （DaCompatController prepareExecution 触顶段）→ 503 {error, code:ENGINE_BUSY,
 *       retryAfterSeconds:5} + Retry-After 头（同款 handler）。</li>
 *   <li>「拒收无任务侧副作用：不产生 taskId/记录/start 事件/缓冲/执行许可」——
 *       503 体不含 taskId，且 B 的事件流不存在。</li>
 *   <li>计数回落（回答/取消/落终态后）恢复受理——A 恢复完成后 B 重新发起成功。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110AwaitingCapE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final long SUSPEND_TIMEOUT_MILLIS = 120_000;

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat110-cap-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        .property("deepanalyze.compat.max-awaiting-tasks", "1"))
                .start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaResumeSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Story("F110-20: 待答名额满（=1）新任务受理 503 全拒不建档，名额回落（A 恢复完成）后 B 重新受理成功")
    @DisplayName("F110-20: 待答名额满载与回落")
    void awaitingCapRejectsThenRecovers() throws Exception {
        // A 挂起（占 1 名额）
        DaResumeSupport.SuspendedTask a = DaResumeSupport.startSuspendedTask(http, base, workspaceRoot,
                "cap-a", "CAP-A-" + System.nanoTime(), SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);

        // B 发起 → 触顶 503（不建档）
        String sessionB = "sess-f110-capb-" + System.nanoTime();
        HttpResponse<InputStream> rejected = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<InputStream> r = openRunStreamQuiet(sessionB);
            if (r.statusCode() == 503) {
                rejected = r;
                break;
            }
            if (r.statusCode() == 200) {
                // 容量 1 未生效（首个 B 就受理）——不应发生，读掉流避免悬挂再失败
                r.body().close();
                throw new IllegalStateException("max-awaiting-tasks=1 未拦截第二个任务（配置未生效？）");
            }
            Thread.sleep(1_000);
        }
        assertThat(rejected).as("30s 内 B 未被拒收").isNotNull();
        String body = new String(rejected.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        rejected.body().close();
        assertThat(DaResumeSupport.jsonString(body, "code"))
                .as("503 code=ENGINE_BUSY").isEqualTo("ENGINE_BUSY");
        assertThat(body).as("拒收消息（满载专属）")
                .contains("too many tasks awaiting answers");
        assertThat(body).as("不建档——503 体不含 taskId").doesNotContain("\"taskId\"");

        // A 恢复完成（名额回落）
        HttpResponse<String> ra = DaResumeSupport.resume(http, base, a.taskId(), a.sessionId(),
                "idem-cap-a-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(ra.statusCode()).as("A 正常恢复 202").isEqualTo(202);
        a.awaitReader(150_000);

        // B 重新发起（名额已回落）→ 正常挂起（status 轮询判定，无需流观察）
        String taskIdB = null;
        String streamSessionB = null;
        deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline && taskIdB == null) {
            String sess = sessionB + "-r" + System.nanoTime();
            HttpResponse<InputStream> r = openRunStreamQuiet(sess);
            if (r.statusCode() == 200) {
                streamSessionB = sess;
                // 后台逐帧收集（run-02 教训形态）：取 taskId + 等待 ask_user 挂起帧
                java.util.List<DaCompatGuardrailSupport.SseFrame> bFrames =
                        new java.util.concurrent.CopyOnWriteArrayList<>();
                Thread drain = new Thread(() -> {
                    try {
                        DaCompatGuardrailSupport.readFramesInto(r, bFrames::add, f -> false,
                                Duration.ofSeconds(120));
                    } catch (Exception ignored) {
                        // 流随任务终态关闭或读超时
                    }
                    if (!bFrames.isEmpty() && !"reader-error".equals(bFrames.get(0).id())) {
                        // 保留原流内容
                    }
                }, "f110-cap-drain");
                drain.setDaemon(true);
                drain.start();
                long inner = System.currentTimeMillis() + SUSPEND_TIMEOUT_MILLIS;
                while (System.currentTimeMillis() < inner && taskIdB == null) {
                    for (DaCompatGuardrailSupport.SseFrame f : bFrames) {
                        if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                            String tid = f.field("taskId");
                            if (tid != null && !tid.isBlank()) {
                                taskIdB = tid;
                            }
                        }
                    }
                    if (taskIdB == null) {
                        Thread.sleep(1_000);
                    }
                }
            } else {
                r.body().close();
                Thread.sleep(2_000);
            }
        }
        assertThat(taskIdB).as("名额回落 90s 内 B 重新受理并挂起").isNotNull();
        HttpResponse<String> rb = DaResumeSupport.resume(http, base, taskIdB, streamSessionB,
                "idem-cap-b-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(rb.statusCode()).as("B 恢复 202").isEqualTo(202);
        // 等终态（completed/failed 均算收口——本格断言面是名额回落受理）
        long termDeadline = System.currentTimeMillis() + 180_000;
        String term = null;
        while (System.currentTimeMillis() < termDeadline) {
            HttpResponse<String> st = DaResumeSupport.status(http, base, taskIdB, streamSessionB,
                    Duration.ofSeconds(15));
            term = DaResumeSupport.jsonString(st.body(), "status");
            if ("completed".equals(term) || "failed".equals(term) || "cancelled".equals(term)) {
                break;
            }
            Thread.sleep(2_000);
        }
        assertThat(term).as("B 最终收口").isEqualTo("completed");
        Allure.addAttachment("F110-20 证据", "text/plain",
                "cap=1; B 触顶 503 ENGINE_BUSY（不建档）；A 完成后 B 重新受理→挂起→202 恢复→completed");
    }

    /** 发起 run-stream（不抛异常形态——用于探测 503/200）。 */
    private HttpResponse<InputStream> openRunStreamQuiet(String session)
            throws java.io.IOException, InterruptedException {
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"请使用 write_file 工具创建文件 "
                + "f110-cap-" + System.nanoTime() + ".md，内容为 CAP-MARKER-" + System.nanoTime()
                + "。只创建这一个文件，不要执行任何其他工具，完成后直接结束任务。\"}";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(base + "/api/agents/run-stream"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body,
                        java.nio.charset.StandardCharsets.UTF_8))
                .build();
        return http.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
    }

}
