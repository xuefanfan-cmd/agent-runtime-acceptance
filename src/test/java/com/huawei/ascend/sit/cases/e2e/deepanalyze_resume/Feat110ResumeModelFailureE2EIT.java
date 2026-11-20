package com.huawei.ascend.sit.cases.e2e.deepanalyze_resume;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
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

import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 受理后模型失败（F110-12；独立 SUT 实例——DA_MODEL_BASE_URL 指向测试侧透传代理）。
 *
 * <p>构造授权（用户 2026-11-11 明确授权）：透传代理＝信道故障注入装置
 * （RecordedHookServer 同构——测试自建装置控制外部依赖通道）；真实上游真实参与、
 * 不伪造任何模型响应，仅编程「resume 后续跑段模型请求断连」。
 *
 * <p>判据（被测制品 0.1.3-feat109111-r2 = agent-solution common@76bcad137）：
 * <ul>
 *   <li>L1 出口 11：受理后模型失败 → SSE error 事件 + 终态组 done{failed}、任务落
 *       failed 终态（status 面 error 非空、resumeState=none、resumable=false）。</li>
 *   <li>同键重放：202 已固化 ⇒ 同键同载荷 200 duplicate:true（重放优先于终态、
 *       不因任务失败被逐出）。</li>
 *   <li>新键：failed 终态新恢复 → 409 ENGINE_TASK_NOT_RESUMABLE（resumeIneligible）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110ResumeModelFailureE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final long SUSPEND_TIMEOUT_MILLIS = 120_000;
    private static final Path DUMP_DIR = Path.of("target", "f110-dump");

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;
    private static String realBase;
    private static TransparentProxy proxy;
    private static AtomicBoolean failAll;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        realBase = System.getenv("LLM_API_BASE");
        assertThat(realBase).as("LLM_API_BASE 必须存在（代理转发目标）").isNotBlank();
        failAll = new AtomicBoolean(false);
        proxy = new TransparentProxy(realBase, failAll);
        workspaceRoot = Files.createTempDirectory("feat110-fail-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        // 信道注入：SUT 的模型 base 指向透传代理（customizer 后于
                        // bindModelEnvironment 执行——覆盖 passThrough 写入的值）
                        .env("DA_MODEL_BASE_URL", proxy.baseUrl()))
                .start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaResumeSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    @Test
    @Story("F110-12: resume 受理后续跑段模型请求断连 → SSE error+done{failed}、同键重放 200、新键 409")
    @DisplayName("F110-12: 受理后模型失败与重放面")
    void resumeAfterModelFailure() throws Exception {
        // 任务挂起（代理透传——首轮模型调用真实成功）
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "fail", "FAIL-MARKER-" + System.nanoTime(), SSE_READ_TIMEOUT,
                SUSPEND_TIMEOUT_MILLIS);

        // resume 受理（202——固化；键存变量供同键重放）
        String key = "idem-fail-" + System.nanoTime();
        HttpResponse<String> resp = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        assertThat(resp.statusCode()).as("受理 202（固化）").isEqualTo(202);

        // 信道注入：续跑段模型请求全断连
        failAll.set(true);

        // 等任务落 failed 终态（SSE error + done{failed} → SUT 关流）
        List<SseFrame> frames = task.awaitReader(180_000);
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-fail.txt",
                "case=F110-12 resume=" + resp.statusCode() + " proxyFails=" + proxy.failCount(),
                frames);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE))
                .as("唯一业务 done").isEqualTo(1);
        assertThat(frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("failed"))).as("终态组含 failed 语义").isTrue();
        assertThat(DaResumeSupport.countEvent(frames, "error"))
                .as("SSE error 事件出现").isGreaterThanOrEqualTo(1);

        // status 面：failed + error 非空 + resumeState=none + resumable=false
        HttpResponse<String> st = DaResumeSupport.status(http, base, task.taskId(), task.sessionId(),
                Duration.ofSeconds(15));
        assertThat(DaResumeSupport.jsonString(st.body(), "status")).isEqualTo("failed");
        assertThat(DaResumeSupport.jsonString(st.body(), "error"))
                .as("failed 终态 error 非空").isNotBlank();
        assertThat(DaResumeSupport.jsonString(st.body(), "resumeState")).isEqualTo("none");
        assertThat(DaResumeSupport.jsonBool(st.body(), "resumable")).isFalse();

        // 同键重放：200 duplicate:true（202 固化面不因任务失败被逐出）
        HttpResponse<String> replay = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        assertThat(replay.statusCode()).as("同键重放 200 duplicate").isEqualTo(200);
        assertThat(DaResumeSupport.jsonBool(replay.body(), "duplicate")).isTrue();

        // 新键：failed 终态新恢复 → 409 NOT_RESUMABLE
        HttpResponse<String> fresh = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                "idem-fail-new-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(fresh.statusCode()).as("failed 终态新键 409").isEqualTo(409);
        assertThat(DaResumeSupport.jsonString(fresh.body(), "code"))
                .isEqualTo("ENGINE_TASK_NOT_RESUMABLE");
        Allure.addAttachment("F110-12 证据", "text/plain",
                "受理 202 → 信道断连（代理失败计数=" + proxy.failCount() + "）→ SSE error+done{failed} → "
                        + "status=failed/error 非空 → 同键 200 duplicate → 新键 409");
    }

    /* ==================== 透传代理（信道故障注入装置） ==================== */

    /**
     * LLM 透传代理：真实上游真实参与，仅编程「断连」故障——不伪造任何模型响应
     * （红线合规：测试自建装置控制外部依赖通道，与 RecordedHookServer 同构）。
     */
    private static final class TransparentProxy implements AutoCloseable {

        private final HttpServer server;
        private final HttpClient forwarder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();
        private final String realBase;
        private final AtomicBoolean failAll;
        private final java.util.concurrent.atomic.AtomicInteger failCount =
                new java.util.concurrent.atomic.AtomicInteger();

        TransparentProxy(String realBase, AtomicBoolean failAll) throws java.io.IOException {
            this.realBase = realBase.replaceAll("/$", "");
            this.failAll = failAll;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        }

        String baseUrl() {
            // 钉 /v1 后缀——SUT 按 {base}/chat/completions 拼路径，与真实 base 的 /v1 层级一致
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        String failCount() {
            return String.valueOf(failCount.get());
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            if (failAll.get()) {
                failCount.incrementAndGet();
                exchange.close(); // 直接断连——SUT 侧表现为连接重置/空响应（信道故障）
                return;
            }
            // 透传：真实上游真实参与（Authorization 头受控传递——进程内转发、不打印不落盘）
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String target = realBase + exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? ""
                            : "?" + exchange.getRequestURI().getRawQuery());
            HttpRequest.Builder forward = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .header("Content-Type", "application/json");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && !auth.isBlank()) {
                forward.header("Authorization", auth);
            }
            HttpRequest forwardRequest = forward
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();
            try {
                HttpResponse<InputStream> upstream = forwarder.send(forwardRequest,
                        HttpResponse.BodyHandlers.ofInputStream());
                byte[] out = upstream.body().readAllBytes();                exchange.getResponseHeaders().set("Content-Type",
                        upstream.headers().firstValue("Content-Type").orElse("application/json"));
                exchange.sendResponseHeaders(upstream.statusCode(), out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    exchange.getResponseBody().write(out);
                }
                exchange.close();
            } catch (java.io.IOException | InterruptedException upstreamFailure) {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
