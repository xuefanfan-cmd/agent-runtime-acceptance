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
 * FEAT-110 同键幂等族与错误面（F110-06 核心分支 + F110-10 错误矩阵）。
 *
 * <p>判据（被测制品 0.1.3-feat109111 = agent-solution common@6fd13012b，DaCompatController +
 * DaResumeIdempotencyStore 源码 2026-11-10 读出）：
 * <ul>
 *   <li>幂等主断言（testplan §7.1）：同键同载荷任意时刻重试至多产生一次 continuation、
 *       重放不新增事件或副作用；重放优先于终态检查（终态后重放仍 200 duplicate:true）。</li>
 *   <li>摘要语义：digestOf(strip(answer), toolCallId)——首尾空白差异=规范化等价同载荷；
 *       大小写不折叠 ⇒ "ok" 与 "OK" 是不同载荷 → 409 ENGINE_RESUME_CONFLICT。</li>
 *   <li>CONFLICT 时序注记：同键已登记 ⇒ 首发已受理（202 即固化）；纯挂起态的不同载荷
 *       并发冲突属「未落定三分支」受控时序面（F110-06 第 5 分支，第二批并发构造），
 *       本类只做首发后串行面。</li>
 *   <li>错误面校验顺序（同一 handler 内）：record 查找+属主（404）→ TTL 过期（404）→
 *       缺幂等键（400）→ answer 空白（400）→ toolCallId 非原值形态（400）→ 资格检查（409）。
 *       未知/跨任务 toolCallId 在资格检查后按 404 "Task not found" 同形拒绝且不消耗资格。</li>
 *   <li>DV-110-01/02 双栏注记：本类不触 503 等待面与长度上限面（未实现，partial）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110ResumeIdempotencyE2EIT {

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
        workspaceRoot = Files.createTempDirectory("feat110-idem-workspace");
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

    /* ==================== F110-06 幂等族 ==================== */

    @Test
    @Story("F110-06: 同键同载荷挂起态重放——200 duplicate:true、无第二次执行")
    @DisplayName("F110-06a: 挂起态同键重放")
    void duplicateWhileAwaiting() throws Exception {
        DaResumeSupport.SuspendedTask task = suspendTask("dup-await");
        String key = "idem-dupa-" + System.nanoTime();

        HttpResponse<String> first = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        assertThat(first.statusCode()).as("首发 202").isEqualTo(202);
        assertThat(DaResumeSupport.jsonBool(first.body(), "duplicate")).isFalse();

        // 同键同载荷立即重放（任务续跑在途——L2 §7.2 在途重放面）
        HttpResponse<String> replay = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        assertThat(replay.statusCode()).as("同键同载荷重放 200").isEqualTo(200);
        assertThat(DaResumeSupport.jsonBool(replay.body(), "duplicate"))
                .as("重放 duplicate:true").isTrue();
        assertThat(DaResumeSupport.jsonString(replay.body(), "taskId"))
                .as("重放回显原 taskId").isEqualTo(task.taskId());

        List<SseFrame> frames = task.awaitReader(150_000);
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-dupa.txt",
                "case=F110-06a first=" + first.statusCode() + " replay=" + replay.statusCode()
                        + " replayBody=" + replay.body(), frames);
        assertThat(DaResumeSupport.countEvent(frames, "start"))
                .as("重放不得产生第二个执行分支（start 恰一次）").isEqualTo(1);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE))
                .as("唯一业务 done").isEqualTo(1);
        Path expected = workspaceRoot.resolve(task.sessionId()).resolve(task.fileName());
        assertThat(Files.readString(expected).split(task.marker(), -1).length - 1)
                .as("文件内容不因重放重复写入").isEqualTo(1);
    }

    @Test
    @Story("F110-06: 终态后同键重放——200 duplicate:true（重放优先于终态检查，直至记录 TTL 过期）")
    @DisplayName("F110-06b: 终态后同键重放")
    void duplicateAfterTerminal() throws Exception {
        DaResumeSupport.SuspendedTask task = suspendTask("dup-term");
        String key = "idem-dupb-" + System.nanoTime();

        HttpResponse<String> first = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        assertThat(first.statusCode()).as("首发 202").isEqualTo(202);

        List<SseFrame> frames = task.awaitReader(150_000);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE))
                .as("任务已到终态").isEqualTo(1);

        // 终态后同键同载荷重放——重放优先于终态检查（源码 6fd13012b replayRegisteredResume 前置）
        HttpResponse<String> replay = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-dupb.txt",
                "case=F110-06b first=" + first.statusCode() + " replayBody=" + replay.body(), frames);
        assertThat(replay.statusCode()).as("终态后同键重放 200（不因终态被拒）").isEqualTo(200);
        assertThat(DaResumeSupport.jsonBool(replay.body(), "duplicate"))
                .as("终态后重放 duplicate:true").isTrue();
    }

    @Test
    @Story("F110-06: 同键不同载荷——409 CONFLICT、不执行冲突载荷、无第二次执行")
    @DisplayName("F110-06c: 同键不同载荷冲突")
    void conflictDifferentPayload() throws Exception {
        DaResumeSupport.SuspendedTask task = suspendTask("conflict");
        String key = "idem-dupc-" + System.nanoTime();

        HttpResponse<String> first = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        assertThat(first.statusCode()).as("首发 202（answer=允许）").isEqualTo(202);

        // 同键不同载荷（拒绝 vs 允许——digest 不同）
        HttpResponse<String> conflict = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "拒绝", null, Duration.ofSeconds(30));
        assertThat(conflict.statusCode()).as("同键不同载荷 409").isEqualTo(409);
        assertThat(DaResumeSupport.jsonString(conflict.body(), "code"))
                .as("409 code=ENGINE_RESUME_CONFLICT").isEqualTo("ENGINE_RESUME_CONFLICT");

        List<SseFrame> frames = task.awaitReader(150_000);
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-dupc.txt",
                "case=F110-06c first=" + first.statusCode() + " conflict=" + conflict.statusCode()
                        + " conflictBody=" + conflict.body(), frames);

        // 主断言：冲突请求未执行（无第二执行分支、文件按首次载荷只写一次）
        assertThat(DaResumeSupport.countEvent(frames, "start")).as("start 恰一次").isEqualTo(1);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE))
                .as("唯一业务 done").isEqualTo(1);
        Path expected = workspaceRoot.resolve(task.sessionId()).resolve(task.fileName());
        assertThat(Files.exists(expected)).as("首次载荷（允许）已执行").isTrue();
        assertThat(Files.readString(expected).split(task.marker(), -1).length - 1)
                .as("冲突载荷未产生额外写入").isEqualTo(1);
    }

    @Test
    @Story("F110-06: 规范化等价载荷——首尾空白差异 strip 后同摘要 → 200 duplicate:true")
    @DisplayName("F110-06d: 规范化等价（strip 等价）")
    void normalizedEquivalentPayload() throws Exception {
        DaResumeSupport.SuspendedTask task = suspendTask("norm");
        String key = "idem-dupd-" + System.nanoTime();

        HttpResponse<String> first = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "允许", null, Duration.ofSeconds(30));
        assertThat(first.statusCode()).as("首发 202").isEqualTo(202);

        // 首尾空白差异 = 规范化等价（answer strip 后参与摘要——源码 requireResumeAnswer strip）
        HttpResponse<String> replay = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "  允许  ", null, Duration.ofSeconds(30));
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-dupd.txt",
                "case=F110-06d replayBody=" + replay.body(), task.framesSnapshot());
        assertThat(replay.statusCode()).as("strip 等价载荷重放 200").isEqualTo(200);
        assertThat(DaResumeSupport.jsonBool(replay.body(), "duplicate"))
                .as("规范化等价重放 duplicate:true").isTrue();
        task.awaitReader(150_000);
    }

    @Test
    @Story("F110-06: 摘要不折叠大小写——ok 与 OK 是不同载荷 → 409 CONFLICT")
    @DisplayName("F110-06e: 大小写不同=不同载荷")
    void caseSensitiveDifferentPayload() throws Exception {
        DaResumeSupport.SuspendedTask task = suspendTask("case");
        String key = "idem-dupe-" + System.nanoTime();

        HttpResponse<String> first = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "ok", null, Duration.ofSeconds(30));
        assertThat(first.statusCode()).as("首发 202（ok 属批准白名单）").isEqualTo(202);

        HttpResponse<String> conflict = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                key, "OK", null, Duration.ofSeconds(30));
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-dupe.txt",
                "case=F110-06e conflictBody=" + conflict.body(), task.framesSnapshot());
        assertThat(conflict.statusCode()).as("大小写不同=不同载荷 409").isEqualTo(409);
        assertThat(DaResumeSupport.jsonString(conflict.body(), "code"))
                .as("409 code=ENGINE_RESUME_CONFLICT").isEqualTo("ENGINE_RESUME_CONFLICT");
        task.awaitReader(150_000);
    }

    @Test
    @Story("F110-06: 跨 taskId 同键隔离——两个任务用同一键各自 202、互不 duplicate")
    @DisplayName("F110-06f: 跨任务同键隔离")
    void crossTaskKeyIsolation() throws Exception {
        DaResumeSupport.SuspendedTask a = suspendTask("iso-a");
        DaResumeSupport.SuspendedTask b = suspendTask("iso-b");
        String sharedKey = "idem-shared-" + System.nanoTime();

        HttpResponse<String> ra = DaResumeSupport.resume(http, base, a.taskId(), a.sessionId(),
                sharedKey, "允许", null, Duration.ofSeconds(30));
        HttpResponse<String> rb = DaResumeSupport.resume(http, base, b.taskId(), b.sessionId(),
                sharedKey, "允许", null, Duration.ofSeconds(30));
        DaResumeSupport.dumpFrames(DUMP_DIR, a.sessionId() + "-isof.txt",
                "case=F110-06f taskIdA=" + a.taskId() + " statusA=" + ra.statusCode() + " bodyA=" + ra.body()
                        + " taskIdB=" + b.taskId() + " statusB=" + rb.statusCode() + " bodyB=" + rb.body(),
                List.of());
        assertThat(ra.statusCode()).as("任务 A 同键首发 202").isEqualTo(202);
        assertThat(DaResumeSupport.jsonBool(ra.body(), "duplicate")).isFalse();
        assertThat(rb.statusCode()).as("任务 B 同键首发 202（键按 taskId 隔离，不串台）").isEqualTo(202);
        assertThat(DaResumeSupport.jsonBool(rb.body(), "duplicate")).isFalse();

        a.awaitReader(150_000);
        b.awaitReader(150_000);
        assertThat(Files.readString(workspaceRoot.resolve(a.sessionId()).resolve(a.fileName()))
                .split(a.marker(), -1).length - 1).as("A 文件只写一次").isEqualTo(1);
        assertThat(Files.readString(workspaceRoot.resolve(b.sessionId()).resolve(b.fileName()))
                .split(b.marker(), -1).length - 1).as("B 文件只写一次").isEqualTo(1);
    }

    /* ==================== F110-10 错误矩阵 ==================== */

    @Test
    @Story("F110-10: 错误矩阵（已完成任务面）——缺键/空白 answer/toolCallId 畸形 400、错 session 404")
    @DisplayName("F110-10a: 错误矩阵·400/404 面（已完成任务）")
    void errorMatrixValidationAndOwnership() throws Exception {
        // 已完成任务（record 在 TTL 内存续——400/404 校验先于资格检查，各非法形态可稳定构造）
        String session = "sess-f110-err-" + System.nanoTime();
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"请直接回答：2+2 等于几？"
                + "不要调用任何工具，用一句话回答。\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(180));
        List<SseFrame> frames = new ArrayList<>();
        Thread reader = backgroundReader(stream, frames);
        String taskId = awaitTaskId(frames, 60_000);
        assertThat(taskId).as("任务受理").isNotNull();
        reader.join(150_000);

        // 错 session → 404 防枚举同形（属主校验最前）
        HttpResponse<String> wrongSession = DaResumeSupport.resume(http, base, taskId, "sess-other-"
                + System.nanoTime(), "idem-err1-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(wrongSession.statusCode()).as("错 session resume 404").isEqualTo(404);
        assertThat(DaResumeSupport.jsonString(wrongSession.body(), "error"))
                .as("防枚举同形 404 error").isEqualTo("Task not found");

        // 缺 Idempotency-Key → 400 ENGINE_VALIDATION
        HttpResponse<String> noKey = DaResumeSupport.resumeNoKey(http, base, taskId, session,
                "允许", Duration.ofSeconds(30));
        assertThat(noKey.statusCode()).as("缺幂等键 400").isEqualTo(400);
        assertThat(DaResumeSupport.jsonString(noKey.body(), "code"))
                .as("400 code=ENGINE_VALIDATION").isEqualTo("ENGINE_VALIDATION");

        // 空白 answer → 400
        HttpResponse<String> blankAnswer = DaResumeSupport.resume(http, base, taskId, session,
                "idem-err2-" + System.nanoTime(), "   ", null, Duration.ofSeconds(30));
        assertThat(blankAnswer.statusCode()).as("空白 answer 400").isEqualTo(400);
        assertThat(DaResumeSupport.jsonString(blankAnswer.body(), "code"))
                .isEqualTo("ENGINE_VALIDATION");

        // toolCallId 带首尾空白（非原值形态）→ 400
        HttpResponse<String> paddedToolCall = DaResumeSupport.resume(http, base, taskId, session,
                "idem-err3-" + System.nanoTime(), "允许", " tc-padded ", Duration.ofSeconds(30));
        assertThat(paddedToolCall.statusCode()).as("toolCallId 非原值形态 400").isEqualTo(400);
        assertThat(DaResumeSupport.jsonString(paddedToolCall.body(), "code"))
                .isEqualTo("ENGINE_VALIDATION");

        // 未知 taskId → 404（同形 "Task not found"）
        HttpResponse<String> unknown = DaResumeSupport.resume(http, base, "no-such-task-"
                + System.nanoTime(), session, "idem-err4-" + System.nanoTime(), "允许", null,
                Duration.ofSeconds(30));
        assertThat(unknown.statusCode()).as("未知 taskId 404").isEqualTo(404);
        assertThat(DaResumeSupport.jsonString(unknown.body(), "error"))
                .isEqualTo("Task not found");
        Allure.addAttachment("F110-10a 错误面", "text/plain",
                "wrongSession=" + wrongSession.statusCode() + " noKey=" + noKey.statusCode()
                        + " blankAnswer=" + blankAnswer.statusCode()
                        + " paddedToolCall=" + paddedToolCall.statusCode()
                        + " unknownTask=" + unknown.statusCode());
    }

    @Test
    @Story("F110-10/08: 未知与跨任务 toolCallId 同形 404 且不消耗恢复资格（随后正确键可受理）")
    @DisplayName("F110-10b: toolCallId 404 面与资格不消耗")
    void unknownToolCallId404AndNotConsumed() throws Exception {
        DaResumeSupport.SuspendedTask task = suspendTask("tc404");
        String goodKey = "idem-tcgood-" + System.nanoTime();

        // 未知 toolCallId → 404
        HttpResponse<String> unknownTarget = DaResumeSupport.resume(http, base, task.taskId(),
                task.sessionId(), "idem-tc1-" + System.nanoTime(), "允许", "no-such-toolcall-"
                        + System.nanoTime(), Duration.ofSeconds(30));
        assertThat(unknownTarget.statusCode()).as("未知 toolCallId 404").isEqualTo(404);
        assertThat(DaResumeSupport.jsonString(unknownTarget.body(), "error"))
                .as("与未知任务同形 404").isEqualTo("Task not found");

        // 跨任务形态：伪他任务 toolCallId → 同形 404（openAsks 不含即同面）
        HttpResponse<String> crossTask = DaResumeSupport.resume(http, base, task.taskId(),
                task.sessionId(), "idem-tc2-" + System.nanoTime(), "允许",
                "tc-belongs-to-other-task", Duration.ofSeconds(30));
        assertThat(crossTask.statusCode()).as("跨任务 toolCallId 404").isEqualTo(404);

        // 资格未被消耗：正确构造的 resume 仍可正常受理（202）
        HttpResponse<String> good = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                goodKey, "允许", task.toolCallId(), Duration.ofSeconds(30));
        List<SseFrame> frames = task.awaitReader(150_000);
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-tc404.txt",
                "case=F110-10b unknown=" + unknownTarget.statusCode()
                        + " cross=" + crossTask.statusCode() + " good=" + good.statusCode()
                        + " goodBody=" + good.body(), frames);
        assertThat(good.statusCode()).as("404 负向不消耗恢复资格——正确 toolCallId 仍受理 202")
                .isEqualTo(202);
        assertThat(DaResumeSupport.jsonBool(good.body(), "duplicate")).isFalse();
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE))
                .as("任务正常完成").isEqualTo(1);
    }

    @Test
    @Story("F110-06: 并发同键同载荷——后到者撞 pending 预约等首发落定 → {恰一 202, 一 200 duplicate}")
    @DisplayName("F110-06g: 并发同键等待落定（r2 等待机制正面格）")
    void concurrentSameKeyWaitsForFirstSettle() throws Exception {
        // r2 判据（76bcad137 DaResumeIdempotencyStore §类注释 + find/register deadline）：
        // 撞 pending 预约的等待在**请求级 deadline**（idempotency-wait-timeout 缺省 10s）内进行——
        // 落定=固化 → 按首次受理结果重放 200；本格断言等待机制的正面路径。
        // 超时 503 面登记：首发 register→solidify 是毫秒级代码窗口、无自然延迟注入面，
        // 「10s 未落定→503」黑盒不可构造，登记 INCONCLUSIVE·构造不可达（不猜分支）。
        DaResumeSupport.SuspendedTask task = suspendTask("dup-conc");
        String sharedKey = "idem-conc-" + System.nanoTime();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<HttpResponse<String>> ra =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<HttpResponse<String>> rb =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread ta = new Thread(() -> fireResume(start, ra, task, sharedKey, "允许"));
        Thread tb = new Thread(() -> fireResume(start, rb, task, sharedKey, "允许"));
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
        HttpResponse<String> duplicate = respA.statusCode() == 202 ? respB : respA;
        List<SseFrame> frames = task.awaitReader(150_000);
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-dupconc.txt",
                "case=F110-06g A=" + respA.statusCode() + " " + respA.body()
                        + " B=" + respB.statusCode() + " " + respB.body(), frames);
        assertThat(accepted).as("并发同键同载荷恰一个 202（首发受理）").isEqualTo(1);
        assertThat(duplicate.statusCode()).as("另一个经等待落定重放 200").isEqualTo(200);
        assertThat(DaResumeSupport.jsonBool(duplicate.body(), "duplicate")).isTrue();
        assertThat(DaResumeSupport.countEvent(frames, "start")).as("start 恰一次").isEqualTo(1);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE)).isEqualTo(1);
        assertThat(Files.readString(workspaceRoot.resolve(task.sessionId()).resolve(task.fileName()))
                .split(task.marker(), -1).length - 1).as("并发等待不产生重复执行").isEqualTo(1);
    }

    private static void fireResume(java.util.concurrent.CountDownLatch start,
                                   java.util.concurrent.atomic.AtomicReference<HttpResponse<String>> sink,
                                   DaResumeSupport.SuspendedTask task, String key, String answer) {
        try {
            start.await();
            sink.set(DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(), key,
                    answer, null, Duration.ofSeconds(60)));
        } catch (Exception e) {
            sink.set(null);
        }
    }

    @Test
    @Story("DV-110-10 负向钉: member-ask 形态伪 ID 不被成员补答通道吞掉——404 主流程、待答态不变、资格不消耗")
    @DisplayName("DV-110-10: 成员补答通道关闭态负向钉")
    void memberAskFakeIdNotHonored() throws Exception {
        // 判据（76bcad137 DaCompatController answer 端点 682-697 + DaMemberAskRegistry.complete）：
        // 成员补答需三条件同时成立（主任务非待答 + 带 toolCallId + registry 精确命中）。
        // 本钉构造「registry 无该项」：普通挂起任务（无成员 ask）+ member-ask-<uuid> 形态伪 ID
        // → complete miss → 走 admitAnswer 主流程 → openAsks 不含伪 ID → REJECTED 404。
        // 安全行为：成员通道不误触发（不返回 answered 语义）、主任务待答态不变、真实
        // toolCallId 资格不消耗（随后正常 resume 完成）。
        // 开启态负向（真成员 ask + 错 ID → future 不完成）依赖成员拓扑 fixture——登记
        // 与团队 fixture 同源，不在本批（对账表 DV-110-10 注记）。
        DaResumeSupport.SuspendedTask task = suspendTask("dv10");
        String fakeMemberId = "member-ask-fake-" + System.nanoTime();
        HttpResponse<String> fake = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/answer/" + task.taskId(),
                "{\"answer\":\"允许\",\"toolCallId\":\"" + fakeMemberId + "\"}",
                Duration.ofSeconds(30), java.util.Map.of("X-Da-Session-Id", task.sessionId()));
        assertThat(fake.statusCode()).as("伪成员 ID → 主流程 404（不进成员补答语义）").isEqualTo(404);
        assertThat(DaResumeSupport.jsonString(fake.body(), "error"))
                .isEqualTo("No pending question for this task");
        assertThat(fake.body()).as("无成员 answered 语义（status 不为 answered）")
                .doesNotContain("\"status\":\"answered\"");

        // 待答态不变 + 真实资格未消耗（随后正常 resume 完成）
        HttpResponse<String> st = DaResumeSupport.status(http, base, task.taskId(), task.sessionId(),
                Duration.ofSeconds(15));
        assertThat(DaResumeSupport.jsonString(st.body(), "resumeState"))
                .as("伪 ID 拒收后任务仍待答").isEqualTo("awaiting_answer");
        HttpResponse<String> good = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                "idem-dv10-" + System.nanoTime(), "允许", task.toolCallId(), Duration.ofSeconds(30));
        assertThat(good.statusCode()).as("真实 toolCallId 仍受理 202（资格未消耗）").isEqualTo(202);
        List<SseFrame> frames = task.awaitReader(150_000);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE)).isEqualTo(1);
        assertThat(Files.readString(workspaceRoot.resolve(task.sessionId()).resolve(task.fileName()))
                .split(task.marker(), -1).length - 1).as("文件只写一次").isEqualTo(1);
        Allure.addAttachment("DV-110-10 证据", "text/plain",
                "伪 member-ask ID → 404 主流程（无 answered 语义）→ 待答态不变 → 真实 ID 202 → 完成；"
                        + "开启态负向（真成员 ask + 错 ID）依赖成员拓扑——对账表登记");
    }

    /* ==================== 私有工具 ==================== */

    private static DaResumeSupport.SuspendedTask suspendTask(String suffix) throws Exception {
        return DaResumeSupport.startSuspendedTask(http, base, workspaceRoot, suffix,
                "IDEM-MARKER-" + suffix.toUpperCase() + "-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
    }

    private static Thread backgroundReader(HttpResponse<InputStream> stream, List<SseFrame> frames) {
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false, SSE_READ_TIMEOUT);
            } catch (Exception e) {
                frames.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f110-idem-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static String awaitTaskId(List<SseFrame> frames, long timeoutMillis) throws InterruptedException {
        String taskId = null;
        long deadline = System.currentTimeMillis() + timeoutMillis;
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
        return taskId;
    }
}
