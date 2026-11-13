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

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 执行池饱和与回滚（F110-11，独立 SUT 实例——max-concurrent-tasks=1）。
 *
 * <p>判据（被测制品 0.1.3-feat109111 = agent-solution common@6fd13012b，DaCompatController）：
 * <ul>
 *   <li>受理前失败回滚链：/resume 新键提交续跑 → 池饱和 DaServerBusyException →
 *       rollbackClaimOrLandCancelled（还原待答 + 重发原 ask_user，新 seq 帧）+
 *       幂等键回滚不烧键（remove 落定信号唤醒同键等待方）→ 503
 *       {@code {error, code:ENGINE_BUSY, retryAfterSeconds}} + 同值标准 Retry-After 头
 *       （BUSY_RETRY_AFTER_SECONDS 固定 5，0.1.3-feat109111 基线实测常量，换基线重测）。</li>
 *   <li>受理前失败可重试：同键同载荷释放后重发 → 202（202 未发=受理未固化）。</li>
 *   <li>Oracle 双栏（DV-110-01）注记：L1 §3.1「同键在途等待最长 10s（可配置）超时 503」
 *       在当前制品未实现——按 L2 固定 5s 立即 503 断言，L1 口径栏标 partial 待裁决；
 *       F110-18 的七配置键面同样不在本类。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110PoolSaturationE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final long SUSPEND_TIMEOUT_MILLIS = 120_000;
    private static final Path DUMP_DIR = Path.of("target", "f110-dump");

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat110-pool-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        // 池容量=1：先到的续跑占池，后到的 /resume 受理前失败 503
                        .property("deepanalyze.compat.max-concurrent-tasks", "1"))
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
    @Story("F110-11: 池饱和 503 BUSY+Retry-After 5、回滚预约与单飞、原任务仍待答、原 ask_user 重发、同键可重试成功")
    @DisplayName("F110-11: 受理前失败回滚与重试")
    void poolSaturationRollbackAndRetry() throws Exception {
        // ---- 任务 A/B 串行挂起（挂起不占池——run 边界收尾，109/110 已证形态）----
        DaResumeSupport.SuspendedTask a = DaResumeSupport.startSuspendedTask(http, base, workspaceRoot,
                "pool-a", "POOL-A-" + System.nanoTime(), SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
        DaResumeSupport.SuspendedTask b = DaResumeSupport.startSuspendedTask(http, base, workspaceRoot,
                "pool-b", "POOL-B-" + System.nanoTime(), SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);

        // ---- resume A：占池（202 时 continuation 已提交）----
        HttpResponse<String> ra = DaResumeSupport.resume(http, base, a.taskId(), a.sessionId(),
                "idem-pool-a-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(ra.statusCode()).as("A 先行受理 202（占池）").isEqualTo(202);

        // ---- resume B（新键）：池满 → 503 BUSY ----
        String keyB = "idem-pool-b-" + System.nanoTime();
        HttpResponse<String> rb = DaResumeSupport.resume(http, base, b.taskId(), b.sessionId(),
                keyB, "允许", null, Duration.ofSeconds(30));
        DaResumeSupport.dumpFrames(DUMP_DIR, b.sessionId() + "-pool503.txt",
                "case=F110-11 ra=" + ra.statusCode() + " rb=" + rb.statusCode() + " rbBody=" + rb.body()
                        + " keyB=" + keyB, b.framesSnapshot());

        assertThat(rb.statusCode()).as("池满时 B 的新键 /resume 应 503").isEqualTo(503);
        assertThat(DaResumeSupport.jsonString(rb.body(), "code"))
                .as("503 code=ENGINE_BUSY").isEqualTo("ENGINE_BUSY");
        assertThat(rb.headers().firstValue("Retry-After"))
                .as("Retry-After 头=5（L2 固定 5s，DV-110-01 双栏：L1 可配置 10s 未实现标 partial）")
                .contains("5");
        assertThat(rb.body()).as("体字段 retryAfterSeconds=5（int 序列化为数字，非字符串——run-14 教训）")
                .contains("\"retryAfterSeconds\":5");

        // ---- B 仍待答：status 面恢复原状 ----
        HttpResponse<String> st = DaResumeSupport.status(http, base, b.taskId(), b.sessionId(),
                Duration.ofSeconds(15));
        assertThat(DaResumeSupport.jsonString(st.body(), "resumeState"))
                .as("回滚后 B 仍 awaiting_answer").isEqualTo("awaiting_answer");
        assertThat(DaResumeSupport.jsonBool(st.body(), "resumable"))
                .as("回滚后 B 仍 resumable=true").isTrue();

        // ---- 原 ask_user 重发：B 的流出现第二个同 toolCallId 的 ask_user 帧 ----
        List<SseFrame> bFrames = b.framesSnapshot();
        long askCount = bFrames.stream()
                .filter(f -> f.data() != null && f.data().contains("\"kind\":\"permission_confirm\""))
                .count();
        assertThat(askCount).as("原 ask_user 重发（含重发帧共 2 次 permission_confirm）")
                .isEqualTo(2);

        // ---- 等 A 完成（释放池），同键同载荷重发 B → 202 ----
        a.awaitReader(150_000);
        HttpResponse<String> retry = DaResumeSupport.resume(http, base, b.taskId(), b.sessionId(),
                keyB, "允许", null, Duration.ofSeconds(30));
        List<SseFrame> bFinal = b.awaitReader(150_000);
        DaResumeSupport.dumpFrames(DUMP_DIR, b.sessionId() + "-poolretry.txt",
                "case=F110-11 retry=" + retry.statusCode() + " retryBody=" + retry.body(), bFinal);

        assertThat(retry.statusCode()).as("释放后同键同载荷重发 202（键未烧）").isEqualTo(202);
        assertThat(DaResumeSupport.jsonBool(retry.body(), "duplicate"))
                .as("重发为全新受理 duplicate=false").isFalse();
        Path expected = workspaceRoot.resolve(b.sessionId()).resolve(b.fileName());
        assertThat(Files.readString(expected).split(b.marker(), -1).length - 1)
                .as("B 文件只写一次").isEqualTo(1);
        Allure.addAttachment("F110-11 证据", "text/plain",
                "A=" + a.taskId() + " B=" + b.taskId() + " 503body=" + rb.body()
                        + " retry=" + retry.statusCode());
    }
}
