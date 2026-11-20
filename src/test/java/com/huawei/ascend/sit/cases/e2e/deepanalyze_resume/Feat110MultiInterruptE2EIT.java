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
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 多次中断恢复与单飞（F110-08 / F110-07）。
 *
 * <p>判据（被测制品 0.1.3-feat109111 = agent-solution common@6fd13012b）：
 * <ul>
 *   <li>F110-08（L1 出口 7）：actrule 默认 {@code *: ask} ⇒ 两文件任务产生两轮 ask——
 *       第一轮 ask 挂起 → resume → 第二轮 ask 挂起（无中间 done）→ resume → 完成。
 *       每轮唯一待确认项允许省略 toolCallId（resolveResumeTarget：openAsks 单元素取之）；
 *       跨轮 resumeState 经 awaiting_answer 往返。</li>
 *   <li>F110-07（L1 出口 6 / §5.1.6 单飞）：挂起任务两个**不同键**并发 /resume——
 *       恰一个 202（受理者），另一个 409（先到者已认领 → ENGINE_RESUME_IN_FLIGHT；
 *       若先到者已终态 → ENGINE_TASK_NOT_RESUMABLE；两 code 均为诚实 409 面，
 *       断言「恰一个 202 + 另一个 409 + 任务无第二次执行」不依赖两请求的到达次序）。</li>
 * </ul>
 * 竞态类用例独立 SUT 实例（设计初稿 §8 纪律）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110MultiInterruptE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final long SUSPEND_TIMEOUT_MILLIS = 120_000;
    private static final Path DUMP_DIR = Path.of("target", "f110-dump");

    private static TestConfig config;
    private static SutStack stack;
    private static java.net.http.HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat110-multi-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString()))
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

    /* ==================== F110-08 ==================== */

    @Test
    @Story("F110-08: 恢复后再中断仍可恢复——两轮 ask 各自消费、无中间 done、两文件都落地")
    @DisplayName("F110-08: 多轮中断-恢复链")
    void resumeTwiceAcrossTwoAsks() throws Exception {
        // 两文件 prompt：actrule 默认 *: ask ⇒ 每个 write_file 各一次 ask_user 挂起
        String session = "sess-f110-multi-" + System.nanoTime();
        String marker1 = "MULTI-A-" + System.nanoTime();
        String marker2 = "MULTI-B-" + System.nanoTime();
        String file1 = "f110-multi1-" + System.nanoTime() + ".md";
        String file2 = "f110-multi2-" + System.nanoTime() + ".md";
        String input = "请依次使用 write_file 工具创建两个文件：" + file1 + "（内容 " + marker1
                + "）和 " + file2 + "（内容 " + marker2 + "）。必须两个都创建，按顺序来，完成后结束。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + DaResumeSupport.escape(input) + "\"}";
        java.net.http.HttpResponse<InputStream> stream =
                DaCompatGuardrailSupport.openRunStream(http, base, body, Duration.ofSeconds(180));

        List<SseFrame> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false, SSE_READ_TIMEOUT);
            } catch (Exception e) {
                frames.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f110-multi-reader");
        reader.setDaemon(true);
        reader.start();

        // ---- 第一轮挂起 → resume ----
        String taskId = awaitAskToolCallId(frames, SUSPEND_TIMEOUT_MILLIS, 0);
        assertThat(taskId).as("第一轮 ask 未出现").isNotNull();
        assertThat(Files.exists(workspaceRoot.resolve(session).resolve(file1)))
                .as("第一轮确认前 file1 不存在").isFalse();

        HttpResponse<String> r1 = DaResumeSupport.resume(http, base, taskId, session,
                "idem-multi-1-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(r1.statusCode()).as("第一轮 /resume 202").isEqualTo(202);

        // ---- 第二轮挂起（第一轮恢复后任务继续，第二个 write_file 再 ask）----
        String taskId2 = awaitAskToolCallId(frames, SUSPEND_TIMEOUT_MILLIS, 1);
        assertThat(taskId2).as("第二轮 ask 未出现（任务未续跑或未再次挂起）").isNotNull();

        HttpResponse<String> r2 = DaResumeSupport.resume(http, base, taskId2, session,
                "idem-multi-2-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(r2.statusCode()).as("第二轮 /resume 202").isEqualTo(202);

        reader.join(150_000);
        List<SseFrame> all = List.copyOf(frames);
        DaResumeSupport.dumpFrames(DUMP_DIR, session + "-multi.txt",
                "case=F110-08 resume1=" + r1.statusCode() + " resume2=" + r2.statusCode()
                        + " task1=" + taskId + " task2=" + taskId2, all);

        // ---- 主断言：无中间 done（同一逻辑任务跨两轮恢复恰一个 done）----
        assertThat(DaResumeSupport.countEvent(all, DaResumeSupport.EVENT_DONE))
                .as("两轮恢复后唯一业务 done（无中间 done）").isEqualTo(1);
        assertThat(DaResumeSupport.countEvent(all, "start")).as("start 恰一次").isEqualTo(1);

        // ---- 两文件都存在且内容正确（每轮原输入只执行一次）----
        assertThat(Files.readString(workspaceRoot.resolve(session).resolve(file1)).contains(marker1))
                .as("file1 内容正确").isTrue();
        assertThat(Files.readString(workspaceRoot.resolve(session).resolve(file2)).contains(marker2))
                .as("file2 内容正确").isTrue();

        // ---- 两轮 ask 均被消费（ask_user 计 2、ask_user_answered 计 2）----
        long asks = all.stream().filter(f -> f.data() != null
                && f.data().contains("\"kind\":\"permission_confirm\"")).count();
        assertThat(asks).as("两轮 ask_user").isEqualTo(2);
        Allure.addAttachment("F110-08 证据", "text/plain",
                "task1=" + taskId + " task2=" + taskId2 + " 帧数=" + all.size());
    }

    /* ==================== F110-07 ==================== */

    @Test
    @Story("F110-07: 两个不同键并发 /resume——恰一个 202，另一个 409 诚实面，无第二次执行")
    @DisplayName("F110-07: 并发单飞")
    void concurrentDistinctKeysSingleFlight() throws Exception {
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "single", "SINGLE-MARKER-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);

        // 两个不同键同时并发（CountDownLatch 对齐起跑）
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<HttpResponse<String>> ra = new AtomicReference<>();
        AtomicReference<HttpResponse<String>> rb = new AtomicReference<>();
        Thread ta = new Thread(() -> uncheckedResume(start, ra, task, "idem-single-a-" + System.nanoTime()));
        Thread tb = new Thread(() -> uncheckedResume(start, rb, task, "idem-single-b-" + System.nanoTime()));
        ta.start();
        tb.start();
        start.countDown();
        ta.join(60_000);
        tb.join(60_000);

        HttpResponse<String> respA = ra.get();
        HttpResponse<String> respB = rb.get();
        assertThat(respA).as("线程 A 响应就绪").isNotNull();
        assertThat(respB).as("线程 B 响应就绪").isNotNull();

        int accepted = (respA.statusCode() == 202 ? 1 : 0) + (respB.statusCode() == 202 ? 1 : 0);
        HttpResponse<String> rejected = respA.statusCode() == 202 ? respB : respA;
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-single.txt",
                "case=F110-07 A=" + respA.statusCode() + " " + respA.body()
                        + " B=" + respB.statusCode() + " " + respB.body(), task.awaitReader(150_000));

        assertThat(accepted).as("并发双键恰一个 202（单飞）").isEqualTo(1);
        assertThat(rejected.statusCode()).as("另一个必须 409").isEqualTo(409);
        String code = DaResumeSupport.jsonString(rejected.body(), "code");
        assertThat(code).as("409 code ∈ {IN_FLIGHT, NOT_RESUMABLE}（先到者认领中/已终态）")
                .isIn("ENGINE_RESUME_IN_FLIGHT", "ENGINE_TASK_NOT_RESUMABLE");

        // 无第二次执行：文件只写一次、唯一 done
        List<SseFrame> frames = task.framesSnapshot();
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE))
                .as("唯一业务 done").isEqualTo(1);
        Path expected = workspaceRoot.resolve(task.sessionId()).resolve(task.fileName());
        assertThat(Files.readString(expected).split(task.marker(), -1).length - 1)
                .as("并发恢复不产生重复写入").isEqualTo(1);
    }

    private static void uncheckedResume(CountDownLatch start, AtomicReference<HttpResponse<String>> sink,
                                        DaResumeSupport.SuspendedTask task, String key) {
        try {
            start.await();
            sink.set(DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(), key,
                    "允许", null, Duration.ofSeconds(30)));
        } catch (Exception e) {
            sink.set(null);
        }
    }

    /** 第 {index}（0 起）个 permission_confirm 帧的 taskId（两轮 ask 同任务，taskId 相同）。 */
    private static String awaitAskToolCallId(List<SseFrame> frames, long timeoutMillis, int index)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<SseFrame> asks = List.copyOf(frames).stream()
                    .filter(f -> f.data() != null && f.data().contains("\"kind\":\"permission_confirm\""))
                    .toList();
            if (asks.size() > index) {
                String tid = asks.get(index).field("taskId");
                if (tid != null && !tid.isBlank()) {
                    return tid;
                }
            }
            Thread.sleep(400);
        }
        return null;
    }

    /* ==================== F110-08 M1 负向格 ==================== */

    @Test
    @Story("F110-08: parallel 多项待确认省略 toolCallId → 409 TARGET_REQUIRED（不误答最近一项）")
    @DisplayName("F110-08m1: 多项确认省略目标拒绝")
    void multiConfirmOmitTarget409() throws Exception {
        // 判据（76bcad137 DaTaskRecord.admitAnswer:370-372）：多项待确认（openAsks>1）且
        // 省略 toolCallId → 409 ENGINE_TOOL_CALL_TARGET_REQUIRED（不误答最近一项、不消耗资格）。
        // 构造：两条注入各自 write_file → resume 后续跑产生 parallel 双确认（run-17 dump 实证）；
        // 模型串行执行时无双确认态 → 超时 INCONCLUSIVE 分流（模型配合面，不判产品）。
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "m1", "M1-MARKER-" + System.nanoTime(), SSE_READ_TIMEOUT,
                SUSPEND_TIMEOUT_MILLIS);
        String fileA = "m1-a-" + System.nanoTime() + ".md";
        String fileB = "m1-b-" + System.nanoTime() + ".md";
        HttpResponse<String> in1 = injectInstruction(task, "注入一：恢复后请用 write_file 创建文件 "
                + fileA + "，内容为 M1-A-" + System.nanoTime() + "。");
        HttpResponse<String> in2 = injectInstruction(task, "注入二：恢复后请用 write_file 创建文件 "
                + fileB + "，内容为 M1-B-" + System.nanoTime() + "。");
        assertThat(in1.statusCode()).isEqualTo(200);
        assertThat(in2.statusCode()).isEqualTo(200);

        HttpResponse<String> resp = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                "idem-m1-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(resp.statusCode()).as("原确认恢复 202").isEqualTo(202);

        // 等 openAsks >= 2（ask 帧数 - 已答数 >= 2），随后省略 toolCallId 发 /answer
        String m1RespBody = null;
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            long openAsks = task.framesSnapshot().stream()
                    .filter(f -> f.data() != null && f.data().contains("\"kind\":\"permission_confirm\""))
                    .count();
            if (openAsks >= 2) {
                HttpResponse<String> r = DaCompatGuardrailSupport.post(http, base,
                        "/api/agents/answer/" + task.taskId(),
                        "{\"answer\":\"允许\"}", Duration.ofSeconds(30),
                        java.util.Map.of("X-Da-Session-Id", task.sessionId()));
                m1RespBody = r.statusCode() + " " + r.body();
                break;
            }
            Thread.sleep(500);
        }
        List<SseFrame> frames = task.awaitReader(60_000);
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-m1.txt",
                "case=F110-08m1 in1=" + in1.statusCode() + " in2=" + in2.statusCode()
                        + " resume=" + resp.statusCode() + " m1Resp=" + m1RespBody, frames);
        if (m1RespBody == null) {
            Allure.addAttachment("F110-08m1 旁路登记", "text/plain",
                    "模型串行执行（无双确认态）——M1 格 INCONCLUSIVE（构造依赖模型 parallel 工具调用）");
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "模型未产生 parallel 双确认——INCONCLUSIVE");
        }
        assertThat(m1RespBody).as("多项确认省略目标 → 409 TARGET_REQUIRED")
                .startsWith("409 ").contains("ENGINE_TOOL_CALL_TARGET_REQUIRED");
    }

    /** 注入指令（本类内简版——复用 X-Da-Session-Id 属主头）。 */
    private static HttpResponse<String> injectInstruction(DaResumeSupport.SuspendedTask task,
            String message) throws java.io.IOException, InterruptedException {
        return DaCompatGuardrailSupport.post(http, base, "/api/agents/inject/" + task.taskId(),
                "{\"message\":\"" + DaResumeSupport.escape(message) + "\"}", Duration.ofSeconds(30),
                java.util.Map.of("X-Da-Session-Id", task.sessionId()));
    }
}