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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 恢复主干旅程端到端（F110-01/02/05，F110-09 状态取证随行）。
 *
 * <p>构造（109 已验证形态）：真模型驱动 write_file → actrule 默认 {@code *: ask} →
 * ask_user(permission_confirm) 挂起 → status 取证 → /resume（幂等键+属主头）→ 续跑至 done。
 *
 * <p>Oracle 主断言（L1 出口 1/2/4/8 + L2 §7.2，偏差双栏见各方法注记）：
 * <ul>
 *   <li>F110-01：202 {taskId,status:"resumed",duplicate:false,streamUrl}；原输入不重跑
 *       （文件 marker 恰一次）；原 SSE 流接续（无第二个 start、seq 单调、唯一业务 done）；
 *       status 三态 awaiting_answer→resume_in_flight→none（DV-110-04：lastEventId 只断
 *       缓冲在时非空，GC 面不在此类）。</li>
 *   <li>F110-02：断连重连 Last-Event-ID 游标——重放帧不含断连前已见 id、seq 接续、
 *       跨两段视角业务 done 恰一次（reconnect_done 哨兵不计）。</li>
 *   <li>F110-05：活跃/终态任务的 /resume 新键 → 409 ENGINE_TASK_NOT_RESUMABLE
 *       （resumeIneligible 两态同面——源码 6fd13012b 判据；无第二次执行）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110ResumeJourneyE2EIT {

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
        workspaceRoot = Files.createTempDirectory("feat110-workspace");
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

    /* ==================== F110-01 / F110-09 ==================== */

    @Test
    @Story("F110-01: /resume 基础恢复——202 受理、原 taskId、原输入不重跑、事件流接续原序号")
    @DisplayName("F110-01/09: 基础恢复旅程与状态三态")
    void resumeJourney202CompletesWithoutRerun() throws Exception {
        String marker = "JOURNEY-MARKER-" + System.nanoTime();
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "journey", marker, SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);

        // ---- F110-09 挂起态取证：awaiting_answer / resumable=true / lastEventId 非空 ----
        HttpResponse<String> st = DaResumeSupport.status(http, base, task.taskId(), task.sessionId(),
                Duration.ofSeconds(15));
        assertThat(st.statusCode()).as("status 查询 200").isEqualTo(200);
        assertThat(DaResumeSupport.jsonString(st.body(), "resumeState"))
                .as("挂起态 resumeState（L1 出口8/L2 §2.1）").isEqualTo("awaiting_answer");
        assertThat(DaResumeSupport.jsonBool(st.body(), "resumable"))
                .as("挂起态 resumable=true").isTrue();
        assertThat(DaResumeSupport.jsonString(st.body(), "lastEventId"))
                .as("挂起态 lastEventId 非空（DV-110-04 共同面：缓冲在、GC 未发生）")
                .isNotBlank();

        // ---- 挂起态文件必须不存在（resume 前工具未执行）----
        Path expected = workspaceRoot.resolve(task.sessionId()).resolve(task.fileName());
        assertThat(Files.exists(expected)).as("恢复前文件必须不存在: " + expected).isFalse();

        // ---- /resume 受理 ----
        String key = "idem-journey-" + System.nanoTime();
        HttpResponse<String> resp = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        assertThat(resp.statusCode()).as("/resume 首次受理 202").isEqualTo(202);
        assertThat(DaResumeSupport.jsonString(resp.body(), "taskId"))
                .as("202 体回显原 taskId").isEqualTo(task.taskId());
        assertThat(DaResumeSupport.jsonString(resp.body(), "status"))
                .as("202 体 status=resumed（入口动作回显，A-01 留档口径）").isEqualTo("resumed");
        assertThat(DaResumeSupport.jsonBool(resp.body(), "duplicate"))
                .as("202 体 duplicate=false").isFalse();
        assertThat(DaResumeSupport.jsonString(resp.body(), "streamUrl"))
                .as("202 体 streamUrl 指向原任务事件流").isEqualTo("/api/agents/stream/" + task.taskId());

        // ---- F110-09 续跑在途窗口取证（尽力抓取；响应过快则不强断，登记观察）----
        HttpResponse<String> st2 = DaResumeSupport.status(http, base, task.taskId(), task.sessionId(),
                Duration.ofSeconds(15));
        String inFlightState = DaResumeSupport.jsonString(st2.body(), "resumeState");
        Boolean inFlightResumable = DaResumeSupport.jsonBool(st2.body(), "resumable");
        // 主断言只对可确定性面：续跑已启动后任务不再待答（resumable=false）；
        // resume_in_flight 是时序窗口值（模型响应快时可能直接 none+completed），抓到即取证
        if ("resume_in_flight".equals(inFlightState)) {
            assertThat(inFlightResumable).as("resume_in_flight 态 resumable=false").isFalse();
        }

        // ---- 等原流 EOF（done 后 SUT 关流）----
        List<SseFrame> frames = task.awaitReader(150_000);
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-journey.txt",
                "case=F110-01/09 taskId=" + task.taskId() + " resumeStatus=" + resp.statusCode()
                        + " resumeBody=" + resp.body() + " statusAfterResume=" + st2.body(), frames);

        // ---- F110-01 主断言：原输入不重跑（write_file 只执行一次）----
        assertThat(Files.exists(expected)).as("恢复后文件必须存在: " + expected).isTrue();
        assertThat(Files.readString(expected).split(marker, -1).length - 1)
                .as("marker 只出现一次（原输入不重跑、无第二个执行分支）").isEqualTo(1);

        // ---- F110-01/02 事件流接续：同一逻辑任务恰一个 start、唯一业务 done、seq 单调 ----
        assertThat(frames.stream().filter(f -> "start".equals(f.event())).count())
                .as("全程恰一个 start（/resume 不得产生第二个执行分支）").isEqualTo(1);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE))
                .as("唯一业务 done").isEqualTo(1);

        // ---- F110-09 终态取证：resumeState=none / resumable=false ----
        HttpResponse<String> st3 = DaResumeSupport.status(http, base, task.taskId(), task.sessionId(),
                Duration.ofSeconds(15));
        assertThat(DaResumeSupport.jsonString(st3.body(), "resumeState"))
                .as("终态 resumeState=none").isEqualTo("none");
        assertThat(DaResumeSupport.jsonBool(st3.body(), "resumable"))
                .as("终态 resumable=false").isFalse();

        // resume_in_flight 窗口取证结果记录（不强断——设计初稿 F110-04b 同款时序宽容）
        Allure.addAttachment("F110-09 状态三态取证", "text/plain",
                "awaiting=awaiting_answer/resumable=true; after-resume(resumeStatus=" + resp.statusCode()
                        + ")=resumeState=" + inFlightState + ",resumable=" + inFlightResumable
                        + "; terminal=none/false");
    }

    /* ==================== F110-02 ==================== */

    @Test
    @Story("F110-02: 断连重连 Last-Event-ID——seq 接续无重复、历史实时交界无重复、业务 done 恰一次")
    @DisplayName("F110-02: 事件流连挂与游标重连")
    void streamReconnectContinuesSequence() throws Exception {
        String marker = "RECONNECT-MARKER-" + System.nanoTime();
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "reconn", marker, SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);

        // 记录断连前已见 id/seq（首段视角）
        List<SseFrame> before = task.framesSnapshot();
        List<String> seenIds = before.stream().map(SseFrame::id)
                .filter(id -> id != null && !id.isBlank()).toList();
        String lastId = seenIds.isEmpty() ? null : seenIds.get(seenIds.size() - 1);

        // 断开首流（模拟客户端断连）
        task.disconnect();

        // /resume 续跑
        String key = "idem-reconn-" + System.nanoTime();
        HttpResponse<String> resp = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        assertThat(resp.statusCode()).as("/resume 受理 202").isEqualTo(202);

        // 重连：Last-Event-ID=断连前最后 id（无帧则不带头——纯 live 订阅，属合法退化形态）
        HttpResponse<InputStream> resumed = DaCompatGuardrailSupport.openStream(http, base,
                task.taskId(), task.sessionId(), lastId, Duration.ofSeconds(180));
        List<SseFrame> after = new ArrayList<>();
        try {
            // 前台读至 EOF（done 后 SUT 关流）；断言面依赖帧完整性，无需后台轮询
            DaCompatGuardrailSupport.readFramesInto(resumed, after::add, f -> false, SSE_READ_TIMEOUT);
        } finally {
            resumed.body().close();
        }
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-reconnect.txt",
                "case=F110-02 taskId=" + task.taskId() + " lastEventId=" + lastId
                        + " resumeBody=" + resp.body() + " beforeFrames=" + before.size()
                        + " afterFrames=" + after.size(), after);

        // ---- 游标语义：重连帧不含断连前已见 id（历史不重复推送）----
        List<String> afterIds = after.stream().map(SseFrame::id)
                .filter(id -> id != null && !id.isBlank() && !"reader-error".equals(id)).toList();
        if (lastId != null) {
            assertThat(afterIds).as("重连帧不得重复断连前已见 id（Last-Event-ID 重放起点）")
                    .doesNotContainAnyElementsOf(seenIds);
        }

        // ---- seq 接续：重连帧 seq 全部大于断连前最大 seq（有 seq 的帧才参与）----
        List<Long> beforeSeqs = DaResumeSupport.seqSeries(before);
        List<Long> afterSeqs = DaResumeSupport.seqSeries(after);
        if (!beforeSeqs.isEmpty() && !afterSeqs.isEmpty()) {
            long maxBefore = beforeSeqs.stream().mapToLong(Long::longValue).max().orElse(0);
            assertThat(afterSeqs.stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE))
                    .as("重连帧 seq 必须全部大于断连前最大 seq=" + maxBefore + "（事件流接着原序号走）")
                    .isGreaterThan(maxBefore);
        }

        // ---- 跨两段视角：唯一业务 done；重放段自身无重复 id ----
        List<SseFrame> all = new ArrayList<>(before);
        all.addAll(after);
        assertThat(DaResumeSupport.countEvent(all, DaResumeSupport.EVENT_DONE))
                .as("跨断连前后业务 done 恰一次（reconnect_done 不计）").isEqualTo(1);
        assertThat(afterIds).as("重连段无重复 id（重放/live 泵按 seq 去重）").doesNotHaveDuplicates();

        // ---- 续跑同样不重跑原输入（文件 marker 恰一次）----
        Path expected = workspaceRoot.resolve(task.sessionId()).resolve(task.fileName());
        assertThat(Files.exists(expected)).as("重连旅程后文件必须存在").isTrue();
        assertThat(Files.readString(expected).split(marker, -1).length - 1)
                .as("marker 只出现一次").isEqualTo(1);
    }

    /* ==================== F110-05 ==================== */

    @Test
    @Story("F110-05: 活跃/终态任务 /resume 新键 → 409 TASK_NOT_RESUMABLE、无第二次执行")
    @DisplayName("F110-05: 活跃非待答 /resume 被拒")
    void activeTaskResumeRejected409() throws Exception {
        // 无 ask 构造：纯问答不调工具——/resume 新键在「活跃非待答」或「终态」两态同面
        // 409 ENGINE_TASK_NOT_RESUMABLE（resumeIneligible 判据 6fd13012b：非在途即 notResumable）
        String session = "sess-f110-active-" + System.nanoTime();
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"请直接回答：1+1 等于几？"
                + "不要调用任何工具，用一句话回答。\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(180));
        List<SseFrame> frames = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false,
                        SSE_READ_TIMEOUT);
            } catch (Exception e) {
                frames.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f110-active-reader");
        reader.setDaemon(true);
        reader.start();

        // 等 status 出现（记录在册即发 resume——活跃/终态两态判据同面）
        String taskId = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (frames) {
                for (SseFrame f : frames) {
                    String tid = f.field("taskId");
                    if (tid != null && !tid.isBlank()) {
                        taskId = tid;
                    }
                }
            }
            if (taskId != null) {
                break;
            }
            Thread.sleep(300);
        }
        assertThat(taskId).as("60s 内事件流未出现 taskId（任务未受理）").isNotNull();

        HttpResponse<String> resp = DaResumeSupport.resume(http, base, taskId, session,
                "idem-active-" + System.nanoTime(), "继续", null, Duration.ofSeconds(30));
        // run-11 教训：reader 退出后 frames 即全量——不做 snapshot+frames 双拼（同帧会计两次）
        reader.join(150_000);
        List<SseFrame> all = List.copyOf(frames);
        DaResumeSupport.dumpFrames(DUMP_DIR, session + "-active409.txt",
                "case=F110-05 taskId=" + taskId + " resumeStatus=" + resp.statusCode()
                        + " resumeBody=" + resp.body(), all);

        assertThat(resp.statusCode()).as("活跃/终态任务 /resume 新键应 409").isEqualTo(409);
        assertThat(DaResumeSupport.jsonString(resp.body(), "code"))
                .as("409 code=ENGINE_TASK_NOT_RESUMABLE").isEqualTo("ENGINE_TASK_NOT_RESUMABLE");

        // 无第二次执行：全程恰一个 start、唯一 done（若已完成）
        assertThat(all.stream().filter(f -> "start".equals(f.event())).count())
                .as("无第二个执行分支（start 恰一次）").isEqualTo(1);
        long doneCount = DaResumeSupport.countEvent(all, DaResumeSupport.EVENT_DONE);
        assertThat(doneCount).as("业务 done 至多一次（任务未因 resume 重启）").isLessThanOrEqualTo(1);
    }

    /* ==================== F110-15 ==================== */

    @Test
    @Story("F110-15: SUT 进程重启后注册表丢失——/resume 同 taskId 404、不新建执行；重启后新任务正常受理")
    @DisplayName("F110-15: 进程重启负向（B-04 已核实 SutStack stop/start 复用原端口）")
    void restartLosesRegistry404() throws Exception {
        // 判据（B-04 核实 + r2 源码）：SutStack.stop(name) 停进程、start(name) 原端口重拉
        //（SutStack.java:315-360「Pin the original port」）；SUT registry 为内存态——重启后
        // 旧 taskId 无记录 → /resume 404 "Task not found"（requireOwnedRecord），不新建执行。
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "restart", "RESTART-MARKER-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
        String taskBefore = task.taskId();

        // 重启 SUT（原端口）
        stack.stop(DaResumeSupport.AGENT);
        stack.start(DaResumeSupport.AGENT);

        // 有界轮询重启完成：对旧任务的 status 从（重启前可能可达）变为 404
        long deadline = System.currentTimeMillis() + 60_000;
        HttpResponse<String> st = null;
        int lastCode = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                st = DaResumeSupport.status(http, base, taskBefore, task.sessionId(), Duration.ofSeconds(10));
                lastCode = st.statusCode();
                if (lastCode == 404) {
                    break;
                }
            } catch (java.io.IOException connectFailure) {
                // 重启窗口期连接拒绝——继续轮询
            }
            Thread.sleep(1_000);
        }
        assertThat(lastCode).as("重启后 60s 内旧任务 status 404（注册表丢失）").isEqualTo(404);

        // /resume 同 taskId → 404 且不新建执行（后续 status 仍 404 佐证无新记录）
        HttpResponse<String> resp = DaResumeSupport.resume(http, base, taskBefore, task.sessionId(),
                "idem-restart-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(resp.statusCode()).as("重启后 /resume 旧 taskId 404").isEqualTo(404);
        assertThat(DaResumeSupport.jsonString(resp.body(), "error")).isEqualTo("Task not found");
        HttpResponse<String> stAgain = DaResumeSupport.status(http, base, taskBefore, task.sessionId(),
                Duration.ofSeconds(10));
        assertThat(stAgain.statusCode()).as("resume 尝试未新建记录（仍 404）").isEqualTo(404);

        // 重启后新任务正常受理（实例健康 sanity）
        DaResumeSupport.SuspendedTask fresh = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "restart-fresh", "RESTART-FRESH-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
        assertThat(fresh.taskId()).as("重启后新任务受理").isNotEqualTo(taskBefore);
        fresh.awaitReader(60_000);
        Allure.addAttachment("F110-15 证据", "text/plain",
                "before=" + taskBefore + " 重启后: status 404 / resume 404 / 无新记录 / 新任务正常受理");
    }

    /* ==================== F110-22 ==================== */

    @Test
    @Story("F110-22: 取消槽位所有权——cancel A 只 abort A 的执行槽，并行任务 B 不受污染；待答任务（无执行体）cancel 协作收口")
    @DisplayName("F110-22: 取消槽位归属隔离")
    void cancelOwnsOnlyItsOwnSlot() throws Exception {
        // 判据（76bcad137 L2 §3.6/§7.4 + cancel 端点）：abort 仅当执行槽明确归属本任务；
        // 待答任务无执行体在场（run 边界已收尾）→ 目标守卫不发 abort，协作标记收口。
        // 黑盒观察面 = cancel A 后：A 落 cancelled 完整终态、并行 B 不受污染（仍待答→恢复完成）。
        DaResumeSupport.SuspendedTask a = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "slot-a", "SLOT-A-" + System.nanoTime(), SSE_READ_TIMEOUT,
                SUSPEND_TIMEOUT_MILLIS);
        DaResumeSupport.SuspendedTask b = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "slot-b", "SLOT-B-" + System.nanoTime(), SSE_READ_TIMEOUT,
                SUSPEND_TIMEOUT_MILLIS);
        // A 恢复（占执行槽）
        HttpResponse<String> ra = DaResumeSupport.resume(http, base, a.taskId(), a.sessionId(),
                "idem-slot-a-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(ra.statusCode()).as("A 恢复 202（执行槽归属 A）").isEqualTo(202);
        // cancel A（abort 只打 A 的槽）
        HttpResponse<String> cancelA = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/cancel/" + a.taskId(), "{}", Duration.ofSeconds(30),
                java.util.Map.of("X-Da-Session-Id", a.sessionId()));
        assertThat(cancelA.statusCode()).as("cancel A 200").isEqualTo(200);
        // A 落 cancelled 完整收口（非静默消失）
        long deadline = System.currentTimeMillis() + 60_000;
        String statusA = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> st = DaResumeSupport.status(http, base, a.taskId(), a.sessionId(),
                    Duration.ofSeconds(10));
            statusA = DaResumeSupport.jsonString(st.body(), "status");
            if ("cancelled".equals(statusA)) {
                break;
            }
            Thread.sleep(1_000);
        }
        assertThat(statusA).as("A 落 cancelled 终态").isEqualTo("cancelled");
        // B 不受污染：仍待答 → 正常恢复 → 完成
        HttpResponse<String> stB = DaResumeSupport.status(http, base, b.taskId(), b.sessionId(),
                Duration.ofSeconds(15));
        assertThat(DaResumeSupport.jsonString(stB.body(), "resumeState"))
                .as("B 未被 cancel A 波及（仍待答）").isEqualTo("awaiting_answer");
        HttpResponse<String> rb = DaResumeSupport.resume(http, base, b.taskId(), b.sessionId(),
                "idem-slot-b-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(rb.statusCode()).as("B 正常恢复 202").isEqualTo(202);
        List<SseFrame> bFrames = b.awaitReader(150_000);
        assertThat(DaResumeSupport.countEvent(bFrames, DaResumeSupport.EVENT_DONE))
                .as("B 完整收口").isEqualTo(1);
        assertThat(Files.readString(workspaceRoot.resolve(b.sessionId()).resolve(b.fileName()))
                .split(b.marker(), -1).length - 1).as("B 文件只写一次").isEqualTo(1);
        Allure.addAttachment("F110-22 证据", "text/plain",
                "A cancelled（完整收口）；B 全程不受污染（待答→202→done→文件恰一次）");
    }

    /* ==================== 私有工具 ==================== */
}