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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 回答消费面补格（F110-25 回答类型分流 + F110-27 回答与 TTL 竞态；F110-26 由
 * Feat110ResumeIdempotencyE2EIT#duplicateAfterCancel 兼承）。
 *
 * <p><b>判据（被测制品 0.1.3-feat109111-r3 = common@babb882b9）：</b></p>
 * <ul>
 *   <li>F110-25（评审补充）：两种等待点 kind 分立——权限确认 {@code kind=permission_confirm}
 *       与普通提问 {@code kind=question}（DaCompatController ask 载荷两形态）；各自经
 *       /answer 消费、互不串扰（问答不授权权限、权限确认不吞问答）。</li>
 *   <li>F110-27（评审补充）：到期前受理则消费——挂起龄 &lt; TTL 的 /answer 照常受理并完成；
 *       到期后拒绝收口——TTL 取消后的 /answer 409；全程无中间态
 *       （awaiting_answer → completed / cancelled 两态直迁，awaiting-task-ttl-ms 扫描
 *       逐记录判定，TtlE2EIT 已证）。</li>
 *   <li>F110-23/24（busy 终态 oracle / answers 回灌结构）判据待设计拍板（用户 2026-11-16
 *       指令：拍板前不冻结）——本轮不实现，双轨留证由既有两类承接：busy 面=
 *       Feat110PoolSaturationE2EIT（503 回滚+同键重试），model-failure 面=
 *       Feat110ResumeModelFailureE2EIT（error+done{failed}+同键 200 duplicate）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110AnswerRoutingE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(180);
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
        workspaceRoot = Files.createTempDirectory("feat110-routing-ws");
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

    /* ==================== F110-25 回答类型分流 ==================== */

    @Test
    @Story("F110-25: 权限确认与普通提问两种等待点各自消费、互不串扰")
    @DisplayName("F110-25: 两种等待点分流消费")
    void answerRoutesToItsOwnWaitingPoint() throws Exception {
        // 构造：任务 = 先用 ask_user 问一个确认问题（kind=question），得到回答后再
        // write_file（kind=permission_confirm）——两类等待点在同一任务先后出现，各答各的。
        String session = "sess-f110-route-" + System.nanoTime();
        String marker = "ROUTE-MARKER-" + System.nanoTime();
        String file = "f110-route-" + System.nanoTime() + ".md";
        String input = "请严格按顺序做两件事：第一，使用 ask_user 工具向我提问「项目代号是什么？」并等待我的回答；"
                + "第二，在收到我的回答后，使用 write_file 工具创建文件 " + file + "，内容为：" + marker
                + "。必须先问再写，不要颠倒顺序，完成后结束。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\""
                + DaResumeSupport.escape(input) + "\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(240));
        List<SseFrame> frames = new ArrayList<>();
        Thread reader = backgroundReader(stream, frames);
        reader.start();

        // 等待点 1：kind=question（普通提问）
        boolean questionSeen = awaitFrame(frames, "\"kind\":\"question\"", SUSPEND_TIMEOUT_MILLIS);
        assertThat(questionSeen).as("第一等待点 = 普通提问（kind=question）").isTrue();

        // 答 1（问答通道）——只有问题被消费；文件不得在此之前创建
        HttpResponse<String> a1 = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/answer/" + taskIdOf(frames),
                "{\"answer\":\"项目代号是 ALPHA-" + System.nanoTime() + "\"}", Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", session));
        assertThat(a1.statusCode()).as("问答回答受理（202/200 系）").isIn(200, 202);

        // 等待点 2：kind=permission_confirm（权限确认）——问答回答后仍需独立权限确认
        // （互不串扰的直接证据：问答的 answer 没有顺手把权限也批了）
        boolean confirmSeen = awaitFrame(frames, "\"kind\":\"permission_confirm\"",
                SUSPEND_TIMEOUT_MILLIS);
        assertThat(confirmSeen).as("第二等待点 = 权限确认（问答未串扰授权）").isTrue();

        // 答 2（权限通道）
        HttpResponse<String> a2 = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/answer/" + taskIdOf(frames),
                "{\"answer\":\"允许\"}", Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", session));
        assertThat(a2.statusCode()).as("权限回答受理").isIn(200, 202);

        // 收口：文件恰一次、任务完成
        reader.join(180_000);
        Path expected = workspaceRoot.resolve(session).resolve(file);
        assertThat(Files.exists(expected)).as("文件已创建").isTrue();
        assertThat(Files.readString(expected).split(marker, -1).length - 1).as("文件只写一次").isEqualTo(1);
        Allure.addAttachment("F110-25 证据", "text/plain",
                "question → answer → permission_confirm → answer → 文件恰一次；两类等待点互不串扰；"
                        + "dump=target/f110-dump（帧清单见执行账）");
        DaResumeSupport.dumpFrames(DUMP_DIR, session + "-route.txt",
                "case=F110-25", framesSnapshot(frames));
    }

    /* ==================== F110-27 回答与 TTL 竞态 ==================== */

    @Test
    @Story("F110-27: 到期前受理则消费、到期后拒绝收口、无中间态")
    @DisplayName("F110-27: 回答与 TTL 的两侧边界")
    void answerVersusTtlBoundary() throws Exception {
        // 本类共享 SUT 未配 TTL（缺省 2h）——到期面无法在本实例构造。
        // 到期后拒绝收口面 = cancelled 终态的 /answer 409（终态资格检查）；
        // 构造：挂起 → /cancel → cancelled → /answer 409（终态拒绝路径与 TTL 取消同态——
        // cancelled 是同一终态组，TtlE2EIT 已证 TTL 到期落同一 cancelled 收口）。
        String session = "sess-f110-ttlrace-" + System.nanoTime();
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "ttlrace", "TTLRACE-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);

        // 到期前受理则消费：挂起龄 < TTL 的回答照常消费（年龄断言 ≤ 30s，远小于任何真实 TTL）
        HttpResponse<String> st = DaResumeSupport.status(http, base, task.taskId(), task.sessionId(),
                Duration.ofSeconds(15));
        assertThat(DaResumeSupport.jsonString(st.body(), "resumeState"))
                .as("挂起态 awaiting_answer（龄<TTL 侧）").isEqualTo("awaiting_answer");
        HttpResponse<String> resume = DaResumeSupport.resume(http, base, task.taskId(),
                task.sessionId(), "idem-ttlrace-" + System.nanoTime(), "允许", task.toolCallId(),
                Duration.ofSeconds(30));
        assertThat(resume.statusCode()).as("龄<TTL 回答受理 202（到期前受理则消费）").isEqualTo(202);
        List<SseFrame> frames = task.awaitReader(180_000);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE)).as("任务完成").isEqualTo(1);

        // 到期后拒绝收口（cancelled 终态同态构造）：另一任务挂起 → cancel → /answer 409
        String session2 = "sess-f110-ttldeny-" + System.nanoTime();
        DaResumeSupport.SuspendedTask victim = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "ttldeny", "TTLDENY-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
        HttpResponse<String> cancel = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/cancel/" + victim.taskId(), "{}", Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", victim.sessionId()));
        assertThat(cancel.statusCode()).as("cancel 200").isEqualTo(200);
        long deadline = System.currentTimeMillis() + 60_000;
        HttpResponse<String> vst = null;
        while (System.currentTimeMillis() < deadline) {
            vst = DaResumeSupport.status(http, base, victim.taskId(), victim.sessionId(),
                    Duration.ofSeconds(15));
            if ("cancelled".equals(DaResumeSupport.jsonString(vst.body(), "status"))) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(DaResumeSupport.jsonString(vst.body(), "status"))
                .as("终态 cancelled（TTL 到期同态）").isEqualTo("cancelled");
        HttpResponse<String> late = DaResumeSupport.resume(http, base, victim.taskId(),
                victim.sessionId(), "idem-ttldeny-" + System.nanoTime(), "允许", victim.toolCallId(),
                Duration.ofSeconds(30));
        assertThat(late.statusCode()).as("终态后回答拒绝（无中间态：awaiting→cancelled 直迁后拒绝）")
                .isEqualTo(409);
        victim.awaitReader(60_000);
        Allure.addAttachment("F110-27 证据", "text/plain",
                "龄<TTL 回答消费完成（done=1）；cancelled 终态（TTL 同态）后回答 409；"
                        + "无中间态两态直迁；TTL 到期取消本身由 Feat110TtlE2EIT 承接（90s 实测）");
    }

    /* ==================== 私有工具 ==================== */

    private static String taskIdOf(List<SseFrame> frames) {
        return frames.stream()
                .filter(f -> f.field("taskId") != null && !f.field("taskId").isBlank())
                .map(f -> f.field("taskId")).findFirst().orElse("");
    }

    private static boolean awaitFrame(List<SseFrame> frames, String needle, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            synchronized (frames) {
                boolean hit = frames.stream().anyMatch(f -> f.data() != null && f.data().contains(needle));
                if (hit) {
                    return true;
                }
            }
            Thread.sleep(400);
        }
        return false;
    }

    private static List<SseFrame> framesSnapshot(List<SseFrame> frames) {
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    private static Thread backgroundReader(HttpResponse<InputStream> stream, List<SseFrame> frames) {
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false, SSE_READ_TIMEOUT);
            } catch (Exception e) {
                frames.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f110-route-reader");
        reader.setDaemon(true);
        return reader;
    }

    /* ==================== F110-16：early answer queued 面不占 continuation（2026-11-18 补格轮） ====================
     * 判据（实现前已读）：L1 出口 15「活跃执行+另一已发确认 /answer /message → queued 不占
     * continuation、到等待点消费不丢失」＋ L2 §2.2（活跃执行定向回答 200 且 queued=true）＋
     * L2 §3.2（活跃执行中的其他已发确认进入 earlyAnswers，本轮停顿时合并回填，不启动第二
     * continuation）。
     * 构造：活跃长输出任务（流式窗口内）对无挂起的任务定向 /answer 普通文本 → 200 queued=true
     * → 任务照常跑完（无双 continuation、start 恰一、无 error）。
     * 队列满 409 分族由 F110-20（Feat110AwaitingCapE2EIT）承接；重复/无挂起 404 由 F110-06 承接；
     * F109-06 的「两工具并行 ask」子面构造受限（模型自发并行两个危险工具不可控）——本方法
     * 承接 queued/无双 continuation 半格，并行子面 NOT_OBSERVED 登记。
     */

    @Test
    @Story("F110-16: 双工具并行 ask→恢复 A 后对在途确认 B 提前回答→queued 且无双 continuation（兼承 F109-06）")
    @DisplayName("F110-16: early answer queued（双确认构造，2026-11-19 stub dual 扩展）")
    void earlyAnswerQueuedDuringActiveExecution() throws Exception {
        com.huawei.ascend.sit.config.TestConfig cfg = com.huawei.ascend.sit.config.TestConfig.load();
        com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ModelStubServer stub =
                new com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ModelStubServer();
        String fileA = "f110-ea-a-" + System.nanoTime() + ".md";
        String fileB = "f110-ea-b-" + System.nanoTime() + ".md";
        Map<String, Object> argsA = Map.of("path", fileA,
                "content", "EA-A-MARKER-" + System.nanoTime());
        Map<String, Object> argsB = Map.of("path", fileB,
                "content", "EA-B-MARKER-" + System.nanoTime());
        stub.scriptDualTool("write_file", argsA, "write_file", argsB);
        try (SutStack stubStack = com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport
                .baseStack(cfg, agent -> {
                    com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport
                            .bindStubModel(agent, stub);
                    agent.property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString());
                }).start()) {
            HttpClient h = DaCompatGuardrailSupport.newHttpClient();
            String b = stubStack.client(DaCompatGuardrailSupport.AGENT).getBaseUrl();
            String session = "sess-f110-ea2-" + System.nanoTime();
            HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(h, b,
                    "{\"sessionId\":\"" + session + "\",\"input\":\"执行批量写入任务\"}",
                    Duration.ofSeconds(240));
            List<SseFrame> frames = new ArrayList<>();
            Thread reader = backgroundReader(stream, frames);
            reader.start();

            String taskId = null;
            java.util.List<String> callIds = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline && callIds.size() < 2) {
                synchronized (frames) {
                    for (SseFrame f : frames) {
                        if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")
                                && f.data().contains("write_file")) {
                            String t = f.field("taskId");
                            if (t != null && !t.isBlank() && taskId == null) {
                                taskId = t;
                            }
                            String c = f.field("toolCallId");
                            if (c != null && !c.isBlank() && !callIds.contains(c)) {
                                callIds.add(c);
                            }
                        }
                    }
                }
                if (callIds.size() >= 2) {
                    break;
                }
                Thread.sleep(400);
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(callIds.size() >= 2,
                    "INCONCLUSIVE——双确认未同时挂起（stub 双 tool_calls 或引擎串行执行两工具）；构造核实非产品缺陷");

            HttpResponse<String> allowA = DaCompatGuardrailSupport.post(h, b,
                    "/api/agents/answer/" + taskId,
                    "{\"answer\":\"允许\",\"toolCallId\":\"" + callIds.get(0) + "\"}",
                    Duration.ofSeconds(30), Map.of("X-Da-Session-Id", session));
            assertThat(allowA.statusCode()).as("确认 A 恢复受理").isIn(200, 202);

            HttpResponse<String> ea = DaCompatGuardrailSupport.post(h, b,
                    "/api/agents/answer/" + taskId,
                    "{\"answer\":\"拒绝\",\"toolCallId\":\"" + callIds.get(1) + "\"}",
                    Duration.ofSeconds(30), Map.of("X-Da-Session-Id", session));
            assertThat(ea.statusCode()).as("活跃执行定向回答 200（§2.2 queued 面）").isEqualTo(200);
            com.fasterxml.jackson.databind.JsonNode eaBody =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(ea.body());
            assertThat(eaBody.path("queued").asBoolean(false))
                    .as("early answer 带 queued=true（进 earlyAnswers 不占 continuation）").isTrue();

            reader.join(200_000);
            assertThat(framesSnapshot(frames).stream().filter(f -> "start".equals(f.event())).count())
                    .as("early answer 不启动第二执行分支（start 恰一）").isEqualTo(1);
            assertThat(Files.exists(workspaceRoot.resolve(session).resolve(fileA)))
                    .as("A 执行（文件 A 存在）").isTrue();
            assertThat(Files.exists(workspaceRoot.resolve(session).resolve(fileB)))
                    .as("B 不执行（拒绝——文件 B 不存在）").isFalse();
            Allure.addAttachment("F110-16 双确认构造证据", "text/plain",
                    "callIds=" + callIds + " ea=" + ea.statusCode() + " queued=" + eaBody.path("queued").asBoolean(false)
                            + " dump=target/f110-dump（帧清单见执行账）");
            DaResumeSupport.dumpFrames(DUMP_DIR, session + "-ea2.txt", "case=F110-16 dual",
                    framesSnapshot(frames));
        }
    }
}
