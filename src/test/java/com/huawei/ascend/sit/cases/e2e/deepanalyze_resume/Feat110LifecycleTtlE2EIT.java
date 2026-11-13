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

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 终态生命周期 TTL 与缓冲 GC 交叉面（F110-09/13 的 GC 半格；独立 SUT 实例
 * ——completed-ttl-ms=3000 全实例生效）。
 *
 * <p>判据（被测制品 0.1.3-feat109111-r2 = agent-solution common@76bcad137）：
 * <ul>
 *   <li>DV-110-06 双面（L1 已修订接受实现口径）：/resume 面**即时**过期检查——
 *       {@code registry.isExpired(record)} → 幂等条目逐出 + 404 "Task not found"
 *       （DaCompatController resume 入口 D5 段）；status/list 面**惰性清扫**——清扫前仍
 *       投影过期记录（不 404），但缓冲已 GC ⇒ lastEventId=null（toTaskResponse:
 *       {@code buffer.lastEventId(taskId).orElse(null)}——DV-110-04 GC 后可空面）。</li>
 *   <li>/stream 面：{@code buffer.has(taskId)} 为假（GC 后）→ 404
 *       "Task not found or buffer expired"（stream 入口判据）。</li>
 *   <li>completedTtlMs 缺省 30 分钟（DaCompatProperties:26）；本实例调小为 3000ms，
 *       TTL 自 completedAt 起算。</li>
 *   <li>期望常量基线标注：以上行为均在 0.1.3-feat109111-r2 读出，换基线重测。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110LifecycleTtlE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final long SUSPEND_TIMEOUT_MILLIS = 120_000;
    private static final long TTL_MS = 3_000;

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat110-gc-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        .property("deepanalyze.compat.completed-ttl-ms", String.valueOf(TTL_MS)))
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
    @Story("F110-09/13: 终态 TTL 内三面正常，过期后 /resume 404、status 投影旧记录且 lastEventId=null、/stream 404")
    @DisplayName("F110-09/13: 过期三面交叉（TTL 内→过期）")
    void terminalExpiryAcrossThreeFaces() throws Exception {
        // 任务挂起 → resume → 完成（TTL 从 completedAt 起算）
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "gc", "GC-MARKER-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
        HttpResponse<String> resp = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                "idem-gc-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(resp.statusCode()).as("/resume 202").isEqualTo(202);
        task.awaitReader(150_000);
        long completedAt = System.currentTimeMillis();

        // ---- TTL 内：status 投影终态 + lastEventId 非空（缓冲未 GC）----
        HttpResponse<String> within = DaResumeSupport.status(http, base, task.taskId(), task.sessionId(),
                Duration.ofSeconds(15));
        assertThat(within.statusCode()).as("TTL 内 status 200").isEqualTo(200);
        assertThat(DaResumeSupport.jsonString(within.body(), "status")).isEqualTo("completed");
        assertThat(DaResumeSupport.jsonString(within.body(), "lastEventId"))
                .as("TTL 内 lastEventId 非空（缓冲在）").isNotBlank();

        // ---- 等过期（completedAt + TTL + 余量）----
        Thread.sleep(Math.max(0, completedAt + TTL_MS + 3_000 - System.currentTimeMillis()));

        // ---- 过期后 /resume 面：即时 404（幂等条目一并逐出——同键重放也是 404）----
        HttpResponse<String> resumeExpired = DaResumeSupport.resume(http, base, task.taskId(),
                task.sessionId(), "idem-gc-first-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(resumeExpired.statusCode()).as("过期后 /resume 新键 404（record-expired）")
                .isEqualTo(404);
        assertThat(DaResumeSupport.jsonString(resumeExpired.body(), "error"))
                .isEqualTo("Task not found");

        // ---- 过期后 status 面：惰性清扫前仍投影旧记录（200）+ 等缓冲 GC（挂 60s 扫描循环
        //      ——run-04 教训：TTL 过期判定与缓冲 GC 扫描是两个时钟，轮询等 lastEventId→null，
        //      上界 = TTL + 2×60s 扫描周期 + 余量）----
        String lastEventIdAfter = null;
        HttpResponse<String> stExpired = null;
        long gcDeadline = System.currentTimeMillis() + TTL_MS + 130_000;
        while (System.currentTimeMillis() < gcDeadline) {
            stExpired = DaResumeSupport.status(http, base, task.taskId(), task.sessionId(),
                    Duration.ofSeconds(15));
            lastEventIdAfter = DaResumeSupport.jsonString(stExpired.body(), "lastEventId");
            if (lastEventIdAfter == null) {
                break;
            }
            Thread.sleep(5_000);
        }
        assertThat(stExpired.statusCode()).as("过期后 status 仍投影旧记录（惰性清扫前，DV-110-06）")
                .isEqualTo(200);
        assertThat(DaResumeSupport.jsonString(stExpired.body(), "status")).isEqualTo("completed");
        assertThat(lastEventIdAfter)
                .as("缓冲 GC 后 lastEventId=null（DV-110-04 可空面；GC 挂 60s 扫描循环）").isNull();

        // ---- 过期后 /stream 面：缓冲 GC → 404 ----
        HttpResponse<String> streamExpired = DaCompatGuardrailSupport.get(http, base,
                "/api/agents/stream/" + task.taskId(), Duration.ofSeconds(15),
                java.util.Map.of("X-Da-Session-Id", task.sessionId()));
        assertThat(streamExpired.statusCode()).as("过期后 /stream 404（缓冲不存在）").isEqualTo(404);

        Allure.addAttachment("F110-09/13 GC 证据", "text/plain",
                "TTL=" + TTL_MS + "ms; TTL内: status 200 lastEventId 非空; 过期后: /resume 404 /"
                        + " status 200(投影旧记录)+lastEventId=null / /stream 404");
    }
}
