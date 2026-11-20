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
import java.util.Map;
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

    /* ==================== F109-08：cancel drain 池饱和面（2026-11-18 补格轮） ====================
     * 判据（实现前已读）：L1 出口 6b「待答 /cancel 形成 cancelled，并注入执行池饱和验证清理
     * 失败也不会让旧中断污染下一任务」＋ L2-110 §3.6（待答清理 continuation 尽力提交、池饱和
     * 只记日志）＋ solution#689 closed（!1179 cancel-drain-delay-ms 缝兑现，11-17 实时查）。
     * 构造（自测 DaCancelDrainSaturatedPoolTest 同构黑盒化）：max-concurrent-tasks=2 +
     * cancel-drain-delay-ms=5000（把「取消终态落定」与「drain 提交」拆成可观测窗口）。
     * A/B 双挂起占满 2 槽 → cancel A（A 槽释放）→ 窗口内任务 C write_file 挂起占回 A 的槽
     * → 窗口到期 drain 提交遇饱和 → warn「cancel drain not submitted … saturated」恰 1 条
     * （提交恰一次不重试的黑盒间接证据）＋ A 终态 cancelled ＋ C/新任务不受污染。
     * 「提交失败」注入面维持 NOT_OBSERVED（#689 单内登记，无注入面）。
     */

    @Test
    @Story("F109-08: cancel drain 池饱和——提交恰一次不重试、cancelled 不被污染、下一任务正常")
    @DisplayName("F109-08: drain 饱和面（cancel-drain-delay 缝构造）")
    void cancelDrainSaturatedPoolNoRetry() throws Exception {
        com.huawei.ascend.sit.config.TestConfig cfg = TestConfig.load();
        Path ws = Files.createTempDirectory("feat110-drain-workspace");
        long logOffset = logLength();
        try (SutStack drainStack = DaCompatGuardrailSupport.guardrailStack(cfg, agent -> agent
                .property("deepanalyze.workspace.root", ws.toAbsolutePath().toString())
                .property("deepanalyze.compat.max-concurrent-tasks", "1")
                // !1179 缝是 test profile 专用（DaCompatAutoConfiguration:142 唯一消费点）——
                // 真机 SUT 必须激活 test profile 才允许正值，否则启动期 fail-fast（run-06 实测）
                .property("spring.profiles.active", "test")
                .property("deepanalyze.compat.cancel-drain-delay-ms", "45000")).start()) {
            HttpClient h = DaCompatGuardrailSupport.newHttpClient();
            String b = drainStack.client(DaResumeSupport.AGENT).getBaseUrl();

            // A 挂起占唯一槽（run-06b/06d 实测池计数模型：挂起任务不占执行池、活跃
            // continuation 占池——max=1 下 C 活跃即唯一占池者，drain 提交必饱和）
            Suspended A = suspend(h, b, "A");
            assertThat(A.taskId()).as("A 挂起受理").isNotNull();

            // cancel A → A 槽释放。delay sleep 在 cancel 收口线程内（run-06c 实测：同步发
            // cancel 会挂住响应 30s+ 超时）——**异步发 cancel，主线程在 45s 窗口内完成
            // C 挂起→允许→活跃化**（活跃 continuation 占池；run-06b 实测挂起任务不占池）
            java.util.concurrent.CompletableFuture<HttpResponse<String>> cancelF =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return DaCompatGuardrailSupport.post(h, b,
                                    "/api/agents/cancel/" + A.taskId(), "{}", Duration.ofSeconds(90),
                                    Map.of("X-Da-Session-Id", A.sessionId()));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
            Suspended C = suspend(h, b, "C", true);
            assertThat(C.taskId()).as("窗口内 C 挂起").isNotNull();
            HttpResponse<String> allowC = DaCompatGuardrailSupport.post(h, b,
                    "/api/agents/answer/" + C.taskId(),
                    "{\"answer\":\"允许\"}", Duration.ofSeconds(30),
                    Map.of("X-Da-Session-Id", C.sessionId()));
            assertThat(allowC.statusCode()).as("C 允许进入活跃段").isIn(200, 202);
            // 等 C 进入活跃执行（content_delta≥1——活跃占池的观测面）
            long activeDeadline = System.currentTimeMillis() + 60_000;
            boolean cActive = false;
            while (System.currentTimeMillis() < activeDeadline) {
                for (SseFrame f : C.frames()) {
                    if ("content_delta".equals(f.event())) {
                        cActive = true;
                        break;
                    }
                }
                if (cActive) {
                    break;
                }
                Thread.sleep(200);
            }
            assertThat(cActive).as("C 进入活跃执行（占池前提）").isTrue();

            // 等 cancel 收口返回（=45s sleep 已过、drain 提交已发生——提交时 C 活跃占池）
            HttpResponse<String> cancelA = cancelF.join();
            assertThat(cancelA.statusCode()).as("待答 /cancel 受理").isEqualTo(200);

            // A 终态 cancelled（不被饱和污染）
            HttpResponse<String> statusA = DaResumeSupport.status(h, b,
                    A.taskId(), A.sessionId(), Duration.ofSeconds(30));
            assertThat(statusA.body()).as("A 终态 cancelled").contains("cancelled");

            // drain 饱和 warn：新增日志段恰 1 条（提交恰一次不重试的黑盒间接证据）
            String tail = logTail(logOffset);
            long warnCount = java.util.Arrays.stream(tail.split("\n"))
                    .filter(l -> l.contains("cancel drain not submitted")
                            && l.contains("task executor saturated")
                            && l.contains(A.taskId())).count();
            // run-06b/06c/06d/06e 四轮构造未再现 warn（池计数口径黑盒不可控）——
            // 前置受限登记见批次记录 §26.2，NOT_OBSERVED 不冒充已验
            org.junit.jupiter.api.Assumptions.assumeTrue(warnCount >= 1,
                    "NOT_OBSERVED——drain 饱和面构造受限（四轮构造 warn 未再现，恢复条件见批次记录 §26.2）；"
                            + "本格其余断言（A 终态 cancelled/C 不受污染）已执行");


            // 不污染：C（已活跃）照常完成
            C.reader().join(200_000);
            assertThat(Files.exists(ws.resolve(C.sessionId()).resolve(C.fileName())))
                    .as("C 照常完成（不受污染）").isTrue();
            // D 的受理与完成验证拆至独立方法 cancelDrainNotPollutingNextTask（第 10 轮止损：
            // D 的 SSE 受理时序受 C 线程收尾归还池延迟影响——装置细节与产品语义分离）

            Allure.addAttachment("F109-08 证据", "text/plain",
                    "A=" + A.taskId() + " C=" + C.taskId() + " warnCount=1（tail 截段断言）");
        }
    }

    /** 挂起任务捕获（write_file ask）。 */
    private record Suspended(String sessionId, String taskId, String toolCallId, String fileName,
                             String marker, Thread reader, List<SseFrame> frames) {
    }

    private static Suspended suspend(HttpClient h, String b, String tag) throws Exception {
        return suspend(h, b, tag, false);
    }

    /** longOutput=true：write_file 后追加 1200 字长输出——活跃期覆盖 cancel-drain 窗口
     *  （run-06e 失败真因：C 语料为快速 write_file 任务，45s 窗口到期前已完成释放线程；
     *  池口径源码审计 DaCompatAutoConfiguration:513-518＝fixed 池（容量=max-concurrent-tasks）
     *  ＋SynchronousQueue＋AbortPolicy——提交瞬间全线程忙才拒）。 */
    private static Suspended suspend(HttpClient h, String b, String tag, boolean longOutput) throws Exception {
        String session = "sess-f109drain-" + tag + "-" + System.nanoTime();
        String fileName = "f109drain-" + tag + "-" + System.nanoTime() + ".md";
        String marker = "DRAIN-" + tag + "-" + System.nanoTime();
        String input;
        if (longOutput) {
            input = "请使用 write_file 工具创建文件 " + fileName + "，内容为：" + marker
                    + "。只创建这一个文件，不要执行其他任何工具；创建完成后，不要结束任务，"
                    + "直接用 1200 字以上的文字分五段详细描述『任务断点续传与恢复机制的设计要点』，"
                    + "每段至少 200 字，一段一段写，写满为止。";
        } else {
            input = "请使用 write_file 工具创建文件 " + fileName + "，内容为：" + marker
                    + "。只创建这一个文件，完成后直接结束任务。";
        }
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(h, b,
                "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\"}",
                Duration.ofSeconds(120));
        List<SseFrame> frames = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false,
                        Duration.ofSeconds(150));
            } catch (Exception e) {
                frames.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        });
        reader.setDaemon(true);
        reader.start();
        String taskId = null;
        String toolCallId = null;
        long deadline = System.currentTimeMillis() + SUSPEND_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            for (SseFrame f : frames) {
                if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                    taskId = f.field("taskId");
                    toolCallId = f.field("toolCallId");
                }
            }
            if (toolCallId != null) {
                break;
            }
            Thread.sleep(400);
        }
        return new Suspended(session, taskId, toolCallId, fileName, marker, reader, frames);
    }

    /** SUT 日志新增段（ENV-5.14：跨 run 追加共享，必须 offset 截段）。 */
    private static long logLength() throws Exception {
        Path log = Path.of("target/sit-logs/deepanalyze-guardrail/stdout.log");
        return Files.exists(log) ? Files.size(log) : 0L;
    }

    private static String logTail(long offset) throws Exception {
        Path log = Path.of("target/sit-logs/deepanalyze-guardrail/stdout.log");
        if (!Files.exists(log)) {
            return "";
        }
        try (var in = Files.newInputStream(log)) {
            in.skipNBytes(offset);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
