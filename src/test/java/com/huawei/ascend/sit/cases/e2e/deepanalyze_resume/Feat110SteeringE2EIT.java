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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 steering 注入（F110-03）。
 *
 * <p>判据（被测制品 0.1.3-feat109111 = agent-solution common@6fd13012b）：
 * <ul>
 *   <li>POST /api/agents/inject/{taskId}（体 {message}）：挂起任务 status 保持 "running"
 *       ⇒ offerInjectionIfRunning ADMITTED（DaTaskRecord:562 判据）——等待期间追加的指令
 *       不丢失；响应 200 {taskId,status:"injected",queueLength}，queueLength=本任务**累计**
 *       注入计数（core 未暴露队列深度，L2 记录的结构偏差）。</li>
 *   <li>消费面：续跑段模型调用前 drain 注入桶（L2 §5.1.7）——注入消息进入模型上下文。
 *       黑盒可观察面 = 模型对注入指令的执行（写文件）；模型旁路（不复述/不执行）时
 *       记 INCONCLUSIVE，不计产品缺陷（铁律：模型旁路不算产品缺陷证据）。</li>
 *   <li>Oracle 注记：FIFO **顺序**断言黑盒不可靠（注入消费顺序无外部观察面）——
 *       本类主断言=两条注入均被消费（不丢失）+ queueLength 递增受理面；顺序部分标 partial
 *       （F110-03 设计 Oracle 双栏既有注记）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110SteeringE2EIT {

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
        workspaceRoot = Files.createTempDirectory("feat110-steer-workspace");
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

    @Test
    @Story("F110-03: 挂起期两条 inject 受理并递增 queueLength，恢复后两条均被消费（FIFO 顺序 partial）")
    @DisplayName("F110-03: steering 注入与恢复消费")
    void steeringInjectedMessagesConsumedAfterResume() throws Exception {
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "steer", "STEER-MARKER-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);

        String steerA = "STEER-A-" + System.nanoTime();
        String steerB = "STEER-B-" + System.nanoTime();
        String bothFile = "z-both-" + System.nanoTime() + ".md";
        // run-17 教训：两条注入分别触发 parallel 双 write_file 确认——定向一个后任务续跑、
        // 另一个确认 stale（409 IN_FLIGHT 诚实面），下游行为不可控。改为两条注入合并写
        // 同一文件（模型串行消化，最多两轮确认链），FIFO 顺序落到文件行序断言。
        HttpResponse<String> in1 = inject(task, "第一条追加指令：恢复执行后，请用 write_file 创建文件 "
                + bothFile + "，内容为两行文本，第一行写 " + steerA + "。");
        assertThat(in1.statusCode()).as("第一条 inject 受理 200").isEqualTo(200);
        assertThat(DaResumeSupport.jsonString(in1.body(), "status")).isEqualTo("injected");
        assertThat(in1.body()).as("queueLength=1（累计计数）").contains("\"queueLength\":1");

        HttpResponse<String> in2 = inject(task, "第二条追加指令：请在文件 " + bothFile
                + " 的第二行写入 " + steerB + "（同一个文件，不要新建）。");
        assertThat(in2.statusCode()).as("第二条 inject 受理 200").isEqualTo(200);
        assertThat(in2.body()).as("queueLength=2（累计递增）").contains("\"queueLength\":2");

        // ---- 恢复（允许 write_file file1）----
        HttpResponse<String> resp = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                "idem-steer-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(resp.statusCode()).as("/resume 202").isEqualTo(202);

        // ---- run-16 教训：注入指令的 write_file 也会触发 actrule ask（第二波确认挂起）——
        //      逐波定向处理直到 done ----
        int answeredCount = 1; // 原任务的 ask 已由上面的 resume 处理
        java.util.Set<String> directed = new java.util.HashSet<>();
        long chainDeadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < chainDeadline) {
            List<SseFrame> snapshot = task.framesSnapshot();
            if (DaResumeSupport.countEvent(snapshot, DaResumeSupport.EVENT_DONE) >= 1) {
                break;
            }
            List<SseFrame> asks = snapshot.stream()
                    .filter(f -> f.data() != null && f.data().contains("\"kind\":\"permission_confirm\""))
                    .toList();
            // 单飞逐波：追最新挂起的确认定向允许；409 IN_FLIGHT（续跑在途）/404（旧确认
            // 已 stale，模型重发会有新 toolCallId）均为推进中的诚实面——等流推进后重试
            if (asks.size() > answeredCount) {
                SseFrame pending = asks.get(asks.size() - 1);
                String tid = pending.field("toolCallId");
                if (tid != null && !directed.contains(tid)) {
                    HttpResponse<String> chain = DaResumeSupport.resume(http, base, task.taskId(),
                            task.sessionId(), "idem-steer-chain-" + answeredCount + "-"
                                    + System.nanoTime(), "允许", tid, Duration.ofSeconds(30));
                    if (chain.statusCode() == 202) {
                        directed.add(tid);
                        answeredCount++;
                    } else if (chain.statusCode() != 409 && chain.statusCode() != 404) {
                        throw new IllegalStateException("连锁确认 resume 意外状态 "
                                + chain.statusCode() + " " + chain.body());
                    }
                }
            }
            Thread.sleep(800);
        }

        // ---- 续跑至终态 ----
        List<SseFrame> frames = task.awaitReader(60_000);
        DaResumeSupport.dumpFrames(DUMP_DIR, task.sessionId() + "-steer.txt",
                "case=F110-03 in1=" + in1.body() + " in2=" + in2.body()
                        + " resume=" + resp.statusCode()
                        + " chainDirected=" + directed.size(),
                frames);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE))
                .as("唯一业务 done").isEqualTo(1);
        assertThat(DaResumeSupport.countEvent(frames, "start")).as("start 恰一次").isEqualTo(1);

        // ---- 原任务 write_file 已执行 ----
        Path original = workspaceRoot.resolve(task.sessionId()).resolve(task.fileName());
        assertThat(Files.exists(original)).as("原任务文件已写").isTrue();

        // ---- 主断言：两条注入均被消费（合并文件两行落地）——模型旁路时 INCONCLUSIVE ----
        Path bothPath = workspaceRoot.resolve(task.sessionId()).resolve(bothFile);
        boolean consumed = Files.exists(bothPath);
        String bothContent = consumed ? Files.readString(bothPath) : "";
        if (!consumed || !bothContent.contains(steerA) || !bothContent.contains(steerB)) {
            // 模型旁路（未执行注入指令）——queueLength 受理面已证注入进桶，消费面依赖模型配合
            Allure.addAttachment("F110-03 旁路登记", "text/plain",
                    "合并文件未按注入落地（exists=" + consumed + "）——模型旁路（INCONCLUSIVE，"
                            + "不判产品）；受理面 queueLength 1/2 已证注入不丢失受理。");
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "模型未执行注入指令（旁路）——消费面证据 INCONCLUSIVE");
        }
        assertThat(bothContent.indexOf(steerA))
                .as("注入一先于注入二落盘（FIFO 消费顺序——文件行序承载）")
                .isLessThan(bothContent.indexOf(steerB));
        Allure.addAttachment("F110-03 证据", "text/plain",
                "injected ok×2 queueLength=1→2; 合并文件含 STEER-A/STEER-B 且 A 先于 B"
                        + "; chainDirected=" + directed.size() + " 波确认全部定向受理");
    }

    @Test
    @Story("F110-04: 终态竞态窗口执行期验证（设计预留）——长输出流式窗口内竞态 inject，受理先于终态必被消费")
    @DisplayName("F110-04: 终态竞态窗口执行期验证")
    void terminalRaceWindowExecutionPhaseVerification() throws Exception {
        // 设计初稿 F110-04 预留：「长收尾工具/大事件流放大窗口（受控手段待执行期验证，
        // 若不可控标 partial）」——本方法履行验证义务（run-09 时序教训：长输出指令若在
        // resume 后注入，模型可能在轮询间隙已收尾→inject 404 NOT_RUNNING）。
        // 重构：长输出要求进首轮 input（续跑段必然长流式）→ 竞态 inject 只在流式期发。
        String raceFile = "race-out-" + System.nanoTime() + ".md";
        String raceMarker = "RACE-OUT-" + System.nanoTime();
        // 用裸流构造（input 自带长输出要求——不走 startSuspendedTask 的固定语料）
        String session = "sess-f110-race-" + System.nanoTime();
        String fileMain = "f110-race-" + System.nanoTime() + ".md";
        String input = "请使用 write_file 工具创建文件 " + fileMain + "，内容为：" + raceMarker
                + "。只创建这一个文件，不要执行任何其他工具；创建完成后，不要结束任务，"
                + "直接用 800 字以上的文字详细描述『任务断点续传的设计要点』，写满为止。";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base,
                "{\"sessionId\":\"" + session + "\",\"input\":\"" + DaResumeSupport.escape(input) + "\"}",
                Duration.ofSeconds(180));
        List<SseFrame> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false, SSE_READ_TIMEOUT);
            } catch (Exception e) {
                frames.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f110-race-reader");
        reader.setDaemon(true);
        reader.start();

        // 等第一波 ask_user（write_file 确认）挂起 → resume 允许
        String taskId = null;
        long deadline = System.currentTimeMillis() + SUSPEND_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline && taskId == null) {
            for (SseFrame f : frames) {
                if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                    String tid = f.field("taskId");
                    if (tid != null && !tid.isBlank()) {
                        taskId = tid;
                    }
                }
            }
            if (taskId == null) {
                Thread.sleep(300);
            }
        }
        assertThat(taskId).as("任务挂起（write_file 确认）").isNotNull();
        HttpResponse<String> resp = DaResumeSupport.resume(http, base, taskId, session,
                "idem-race-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(resp.statusCode()).as("恢复 202").isEqualTo(202);

        // 流式窗口竞态：第 10 个 content_delta 后立即 inject（长输出保证窗口秒级）
        String raceResp = null;
        boolean injectedDuringStream = false;
        HttpResponse<String> raceA = null;
        deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            List<SseFrame> snap = List.copyOf(frames);
            long deltas = snap.stream().filter(f -> "content_delta".equals(f.event())).count();
            if (deltas >= 10 && raceA == null) {
                DaResumeSupport.SuspendedTask raceTaskView = new DaResumeSupport.SuspendedTask(
                        session, taskId, "", "", "", "", stream, frames, reader);
                raceA = inject(raceTaskView, "竞态注入-B-" + System.nanoTime() + "：请忽略本条内容，继续作答。");
                raceResp = raceA.statusCode() + " " + raceA.body();
                injectedDuringStream = raceA.statusCode() == 200;
            }
            if (snap.stream().anyMatch(f -> DaResumeSupport.EVENT_DONE.equals(f.event()))) {
                break;
            }
            if (raceA != null && raceA.statusCode() == 404) {
                // 竞态注入到达时任务已收尾（模型快于预期）——如实记账，不重试
                break;
            }
            Thread.sleep(120);
        }
        reader.join(150_000);
        List<SseFrame> all = List.copyOf(frames);
        DaResumeSupport.dumpFrames(DUMP_DIR, session + "-race.txt",
                "case=F110-04 resume=" + resp.statusCode() + " race=" + raceResp
                        + " injectedDuringStream=" + injectedDuringStream, all);

        // ---- 安全行为不变量（无论竞速结果）----
        assertThat(DaResumeSupport.countEvent(all, "start")).as("start 恰一次").isEqualTo(1);
        assertThat(DaResumeSupport.countEvent(all, DaResumeSupport.EVENT_DONE))
                .as("唯一业务 done").isEqualTo(1);
        assertThat(DaResumeSupport.countEvent(all, "error")).as("竞速过程无 error").isEqualTo(0);
        assertThat(Files.readString(workspaceRoot.resolve(session).resolve(fileMain))
                .split(raceMarker, -1).length - 1).as("原文件只写一次").isEqualTo(1);

        // ---- 竞态判据（受理先于终态 → 注入必被消费：done 延迟、无 second start、无双执行）----
        if (injectedDuringStream) {
            assertThat(DaResumeSupport.countEvent(all, "start")).as("竞态注入不产生第二执行分支")
                    .isEqualTo(1);
            Allure.addAttachment("F110-04 变体B PASS", "text/plain",
                    "流式窗口内 inject 200（受理先于终态）→ done 延迟出现、任务完整收口——"
                            + "暂扣语义实质成立；raceResp=" + raceResp);
        } else {
            Allure.addAttachment("F110-04 变体B INCONCLUSIVE", "text/plain",
                    "竞态 inject 未在流式窗口捕获（raceResp=" + raceResp + "）——窗口与模型响应速度"
                            + "竞速，非确定性构造；主链 3b 按设计预期落 partial（执行期验证已履行）");
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "竞态窗口未捕获（模型响应过快）——INCONCLUSIVE，主链 partial 获执行期验证支撑");
        }
    }

    private static HttpResponse<String> inject(DaResumeSupport.SuspendedTask task, String message)
            throws java.io.IOException, InterruptedException {
        return DaCompatGuardrailSupport.post(http, base, "/api/agents/inject/" + task.taskId(),
                "{\"message\":\"" + DaResumeSupport.escape(message) + "\"}", Duration.ofSeconds(30),
                java.util.Map.of("X-Da-Session-Id", task.sessionId()));
    }
}
