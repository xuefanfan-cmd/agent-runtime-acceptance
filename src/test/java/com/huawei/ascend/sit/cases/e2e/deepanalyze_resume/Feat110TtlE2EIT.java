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
 * FEAT-110 待答 TTL（F110-19，独立 SUT 实例——TTL 为全实例配置）。
 *
 * <p>判据（被测制品 0.1.3-feat109111 = agent-solution common@6fd13012b）：
 * <ul>
 *   <li>配置键 deepanalyze.compat.awaiting-task-ttl-ms（DaCompatProperties:57，缺省 2h，
 *       0=关闭扫描）；扫描挂事件缓冲 GC 长循环、缺省每 60 秒一次——L2-02：扫描周期值未给出，
 *       本类只断「最终取消」（bounded 轮询上界 TTL+2×60s+余量）。</li>
 *   <li>sweepExpiredAwaitingTasks：遍历 registry.listAwaiting() 逐记录 claimAnswer 认领互斥
 *       （与 /answer、/resume、/cancel 待答分支互斥），到期处置=取消（cancelled 终态组完整收口）、
 *       清注入桶；**逐记录独立判定**（now-awaitingSince <= ttlMs → 跳过）——未超龄记录不受牵连。</li>
 *   <li>同会话双任务构造（proposal-110 ⑤「同会话其他任务不受牵连」）：B 挂起于 A 龄>TTL 之后，
 *       B 龄 &lt; TTL 时 A 被取消——逐记录判定下 B 存活。若同会话互斥（core 会话占用）则
 *       登记事实并转跨会话等价面。</li>
 *   <li>「还原待答重置 TTL 锚」分栏不独立构造（需 503 回滚+TTL 组合，F110-11 已证回滚
 *       还原待答面）——登记说明，不作本类断言。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110TtlE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final long SUSPEND_TIMEOUT_MILLIS = 120_000;
    private static final Path DUMP_DIR = Path.of("target", "f110-dump");
    /** TTL 测试值 90s：保证「A 龄>TTL 被取消、B 龄<TTL 存活」能在 60s 扫描窗内分化。 */
    private static final long TTL_MS = 90_000;

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat110-ttl-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        .property("deepanalyze.compat.awaiting-task-ttl-ms", String.valueOf(TTL_MS)))
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
    @Story("F110-19: 挂起超龄被 TTL 取消（cancelled 完整收口）、同会话未超龄任务不受牵连")
    @DisplayName("F110-19: TTL 到期取消与同会话隔离")
    void ttlExpiryCancelsOnlyExpiredTask() throws Exception {
        // ---- A 挂起；t0 = A 进入待答的时刻（awaitingSince 起点）----
        DaResumeSupport.SuspendedTask a = DaResumeSupport.startSuspendedTask(http, base, workspaceRoot,
                "ttl-a", "TTL-A-" + System.nanoTime(), SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
        long t0 = System.currentTimeMillis();

        // ---- 等 A 龄超过 TTL（+5s 余量）后，再发同会话任务 B ----
        Thread.sleep(TTL_MS + 5_000);
        DaResumeSupport.SuspendedTask b = DaResumeSupport.startSuspendedTask(http, base, workspaceRoot,
                "ttl-b", "TTL-B-" + System.nanoTime(), SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);

        // ---- 等 A 被 TTL 取消（上界 = TTL + 2×扫描周期 + 余量）----
        HttpResponse<String> stA = awaitStatusValue(a.taskId(), a.sessionId(), "cancelled",
                TTL_MS + 130_000);
        long cancelAge = System.currentTimeMillis() - t0;
        DaResumeSupport.dumpFrames(DUMP_DIR, a.sessionId() + "-ttl.txt",
                "case=F110-19 TTL_MS=" + TTL_MS + " cancelAge=" + cancelAge
                        + " A=" + stA.body(), List.of());

        // ---- A 终态面：cancelled 完整收口 ----
        assertThat(stA.statusCode()).isEqualTo(200);
        assertThat(DaResumeSupport.jsonString(stA.body(), "status"))
                .as("TTL 到期落 cancelled（非静默消失）").isEqualTo("cancelled");
        assertThat(DaResumeSupport.jsonString(stA.body(), "resumeState"))
                .as("终态 resumeState=none").isEqualTo("none");
        assertThat(DaResumeSupport.jsonBool(stA.body(), "resumable")).isFalse();

        // ---- B 未受牵连：仍待答可恢复（B 龄 < TTL 时 A 被取消）----
        HttpResponse<String> stB = DaResumeSupport.status(http, base, b.taskId(), b.sessionId(),
                Duration.ofSeconds(15));
        assertThat(DaResumeSupport.jsonString(stB.body(), "resumeState"))
                .as("B 挂起于 A 龄>TTL 之后、龄<TTL——TTL 扫描逐记录判定，B 不受牵连")
                .isEqualTo("awaiting_answer");
        assertThat(DaResumeSupport.jsonBool(stB.body(), "resumable")).isTrue();

        // ---- A 的 /resume 新键 → 409（终态面）----
        HttpResponse<String> ra = DaResumeSupport.resume(http, base, a.taskId(), a.sessionId(),
                "idem-ttl-a-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(ra.statusCode()).as("已取消任务 /resume 新键 409").isEqualTo(409);
        assertThat(DaResumeSupport.jsonString(ra.body(), "code"))
                .isEqualTo("ENGINE_TASK_NOT_RESUMABLE");

        // ---- 收尾：B 正常恢复完成（同时证明 B 的恢复资格未被 TTL 扫描污染）----
        HttpResponse<String> rb = DaResumeSupport.resume(http, base, b.taskId(), b.sessionId(),
                "idem-ttl-b-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(rb.statusCode()).as("B 正常恢复 202").isEqualTo(202);
        a.awaitReader(150_000);
        b.awaitReader(150_000);
        Path expected = workspaceRoot.resolve(b.sessionId()).resolve(b.fileName());
        assertThat(Files.readString(expected).split(b.marker(), -1).length - 1)
                .as("B 文件只写一次").isEqualTo(1);
        Allure.addAttachment("F110-19 证据", "text/plain",
                "A cancelled at age=" + cancelAge + "ms; B 仍待答且随后 202 恢复完成；"
                        + "同会话双任务（若受理互斥将以报错体现，转跨会话等价面登记）");
    }

    /** 有界轮询 status 直到目标终态值（1s 间隔）。 */
    private static HttpResponse<String> awaitStatusValue(String taskId, String session,
                                                         String expected, long timeoutMillis)
            throws Exception {
        HttpResponse<String> last = null;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            last = DaResumeSupport.status(http, base, taskId, session, Duration.ofSeconds(15));
            if (expected.equals(DaResumeSupport.jsonString(last.body(), "status"))) {
                return last;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException(timeoutMillis + "ms 内 status 未变为 " + expected
                + "；末次=" + (last == null ? "<null>" : last.body()));
    }
}
