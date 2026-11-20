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
 * <p>判据（被测制品 0.1.3-feat109111-r3 = agent-solution common@babb882b9；r2 基线 76bcad137）：
 * <ul>
 *   <li><b>r3 基线行为变更（a19824733，2026-11-17 用户裁决：以文档实际契约为准）</b>：
 *       DaTaskRegistry.get/list 读面即时逐出（D5：过期即视同记录已逐出、同形 404）——
 *       过期后 status 从 r2 的「惰性清扫前投影 200」改为同形 404；依据＝D5 注释引用的
 *       FEAT-110 L1「终态起 completedTtlMs 过期后统一同形 404」，r3 读面让该契约在
 *       无新注册时同样成立。/resume 面行为 r2→r3 不变（即时过期检查）。</li>
 *   <li>/resume 面：{@code registry.isExpired(record)} → 幂等条目逐出 + 404 "Task not found"
 *       （DaCompatController resume 入口 D5 段）。</li>
 *   <li>/stream 面：记录与缓冲同生命周期（记录逐出后缓冲不可达）→ 404。</li>
 *   <li>completedTtlMs 缺省 30 分钟（DaCompatProperties:26）；本实例调小为 3000ms，
 *       TTL 自 completedAt 起算。</li>
 *   <li>期望常量基线标注：status 404 面在 r3（babb882b9）读出；/resume 与 /stream 面在
 *       r2 读出、r3 复核未变。换基线重测。</li>
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
    @Story("F110-09/13: 终态 TTL 内三面正常，过期后 /resume、status、/stream 统一同形 404（r3 基线 D5 契约）")
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

        // ---- 过期后 status 面：r3 基线 D5 契约（DaTaskRegistry.get 读面即时逐出——
        //      a19824733「过期即视同记录已逐出，按同形 404 处理」，用户 2026-11-17 裁决按
        //      文档实际契约为准；r2 判据「惰性清扫前仍投影 200」随该提交过时）。
        //      轮询等读面 404 出现（过期即刻，无需等 60s 缓冲扫描），上界 = TTL + 扫描周期 + 余量。
        //      lastEventId=null 面随 404 一并成立（记录逐出后缓冲不可达），不再独立轮询。----
        long statusDeadline = System.currentTimeMillis() + TTL_MS + 130_000;
        HttpResponse<String> stExpired = null;
        int expiredCode = 0;
        while (System.currentTimeMillis() < statusDeadline) {
            stExpired = DaResumeSupport.status(http, base, task.taskId(), task.sessionId(),
                    Duration.ofSeconds(15));
            expiredCode = stExpired.statusCode();
            if (expiredCode == 404) {
                break;
            }
            Thread.sleep(5_000);
        }
        assertThat(expiredCode).as("过期后 status 同形 404（D5 读面即时逐出，r3 契约）").isEqualTo(404);
        assertThat(DaResumeSupport.jsonString(stExpired.body(), "error"))
                .as("同形 404 载荷").isEqualTo("Task not found");

        // ---- 过期后 /stream 面：缓冲 GC 与记录读面逐出是两个时钟（run-03d 实测：
        //      status 即时 404 的同时 /stream 仍 200——缓冲 GC 挂 60s 扫描循环）。
        //      有界轮询等 /stream 404，上界 = TTL + 2×60s 扫描周期 + 余量。----
        int streamCode = 0;
        long streamDeadline = System.currentTimeMillis() + TTL_MS + 130_000;
        while (System.currentTimeMillis() < streamDeadline) {
            HttpResponse<String> streamExpired = DaCompatGuardrailSupport.get(http, base,
                    "/api/agents/stream/" + task.taskId(), Duration.ofSeconds(15),
                    java.util.Map.of("X-Da-Session-Id", task.sessionId()));
            streamCode = streamExpired.statusCode();
            if (streamCode == 404) {
                break;
            }
            Thread.sleep(5_000);
        }
        assertThat(streamCode).as("过期后 /stream 404（缓冲 GC 后；独立 60s 扫描钟）").isEqualTo(404);

        Allure.addAttachment("F110-09/13 GC 证据", "text/plain",
                "TTL=" + TTL_MS + "ms; TTL内: status 200 lastEventId 非空; 过期后: /resume 404 / "
                        + "status 404（D5 读面即时逐出，r3 契约 a19824733） / /stream 404");
    }
}
