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
 * FEAT-110 载荷上限矩阵与容量键（F110-18，#46 裁决落地后转 runnable，按 L1 原口径单栏断言；
 * 独立 SUT 实例——steering-queue-capacity 显式键=2 全实例生效）。
 *
 * <p>判据（被测制品 0.1.3-feat109111-r2 = agent-solution common@76bcad137，2026-11-11 读出）：
 * <ul>
 *   <li>max-request-body-bytes=65536（缺省）：POST /api/agents/{resume,answer,message,inject}
 *       请求体超限 → 过滤器直拒 400 {@code {"error":"request body exceeds 65536 bytes",
 *       "code":"ENGINE_VALIDATION"}}（DaCompatRequestBodyLimitFilter:62-67，先于 controller）。</li>
 *   <li>max-answer-chars=20000 / max-idempotency-key-chars=128 / max-tool-call-id-chars=256：
 *       requireMaxChars → 400 ENGINE_VALIDATION
 *       {@code "<field> exceeds the configured limit of <n> chars"}（DaCompatController:913-917；
 *       answer 校验在 requireResumeAnswer:919-925，键校验在 requireIdempotencyKey:906-911）。</li>
 *   <li>steering-queue-capacity 显式键优先于旧别名 max-injections-per-task
 *       （getSteeringQueueCapacity：显式>0 用显式值）——本实例显式键=2 ⇒ 第 3 条 inject
 *       409 ENGINE_INJECTION_QUEUE_FULL 且 limit=2（若优先级反转则容量=100 不触发，判据可鉴别）。</li>
 *   <li>early-answer 独立容量=100（缺省）：任务执行中（确认未挂起）先存回答 queued，
 *       每任务上限 100 → 第 101 条 409 INJECTION_QUEUE_FULL limit=100（answer 端点
 *       QUEUE_FULL 分支 DaCompatController:713-715）；且 early-answer 满不影响 steering
 *       桶受理（两桶独立——DV-110-05 修订后 L1 口径）。</li>
 *   <li>期望常量基线标注：以上限值/错误文案均在 0.1.3-feat109111-r2 源码读出（76bcad137）；
 *       换基线必须重测。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110PayloadLimitsE2EIT {

    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final long SUSPEND_TIMEOUT_MILLIS = 120_000;

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat110-limits-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        // F110-18 判据：显式新键=2（优先于旧别名缺省 100）
                        .property("deepanalyze.compat.steering-queue-capacity", "2"))
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

    /* ==================== 四超长键 400 ==================== */

    @Test
    @Story("F110-18: 超长载荷 400 矩阵——body 字节/answer 字符/幂等键字符/toolCallId 字符四键")
    @DisplayName("F110-18a: 四超长键 400")
    void oversizedPayloadsRejected400() throws Exception {
        // 已完成任务（record 在 TTL 内存续——长度校验先于资格检查，各形态稳定构造）
        String session = "sess-f110-limits-" + System.nanoTime();
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"请直接回答：3+3 等于几？"
                + "不要调用任何工具，用一句话回答。\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(180));
        List<SseFrame> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false, SSE_READ_TIMEOUT);
            } catch (Exception e) {
                frames.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f110-limits-reader");
        reader.setDaemon(true);
        reader.start();
        String taskId = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && taskId == null) {
            for (SseFrame f : frames) {
                String tid = f.field("taskId");
                if (tid != null && !tid.isBlank()) {
                    taskId = tid;
                }
            }
            if (taskId == null) {
                Thread.sleep(300);
            }
        }
        assertThat(taskId).as("任务受理").isNotNull();
        reader.join(150_000);

        // a) 请求体字节超限（过滤器先于 controller——answer 70000 字符连带 body 超 65536 字节）
        String oversizedAnswer = "x".repeat(70_000);
        HttpResponse<String> bodyLimit = DaResumeSupport.resume(http, base, taskId, session,
                "idem-limits-body-" + System.nanoTime(), oversizedAnswer, null, Duration.ofSeconds(30));
        assertThat(bodyLimit.statusCode()).as("请求体超 65536 字节 400").isEqualTo(400);
        assertThat(DaResumeSupport.jsonString(bodyLimit.body(), "error"))
                .as("过滤器直拒文案（body bytes 判据）")
                .contains("request body exceeds 65536 bytes");

        // b) answer 字符超限（20001 字符，body 约 20KB < 65536——controller 内 requireMaxChars）
        HttpResponse<String> answerLimit = DaResumeSupport.resume(http, base, taskId, session,
                "idem-limits-answer-" + System.nanoTime(), "y".repeat(20_001), null, Duration.ofSeconds(30));
        assertThat(answerLimit.statusCode()).as("answer 超 20000 字符 400").isEqualTo(400);
        assertThat(DaResumeSupport.jsonString(answerLimit.body(), "error"))
                .contains("answer exceeds the configured limit of 20000 chars");

        // c) 幂等键字符超限（129 字符——resume 方法的键参数直传任意字符串）
        HttpResponse<String> keyLimit = DaResumeSupport.resume(http, base, taskId, session,
                "k".repeat(129), "允许", null, Duration.ofSeconds(30));
        assertThat(keyLimit.statusCode()).as("幂等键超 128 字符 400").isEqualTo(400);
        assertThat(DaResumeSupport.jsonString(keyLimit.body(), "error"))
                .contains("Idempotency-Key exceeds the configured limit of 128 chars");

        // d) toolCallId 字符超限（257 字符）
        HttpResponse<String> tcLimit = DaResumeSupport.resume(http, base, taskId, session,
                "idem-limits-tc-" + System.nanoTime(), "允许", "t".repeat(257), Duration.ofSeconds(30));
        assertThat(tcLimit.statusCode()).as("toolCallId 超 256 字符 400").isEqualTo(400);
        assertThat(DaResumeSupport.jsonString(tcLimit.body(), "error"))
                .contains("toolCallId exceeds the configured limit of 256 chars");

        assertThat(DaResumeSupport.jsonString(bodyLimit.body(), "code")).isEqualTo("ENGINE_VALIDATION");
        assertThat(DaResumeSupport.jsonString(answerLimit.body(), "code")).isEqualTo("ENGINE_VALIDATION");
        assertThat(DaResumeSupport.jsonString(keyLimit.body(), "code")).isEqualTo("ENGINE_VALIDATION");
        assertThat(DaResumeSupport.jsonString(tcLimit.body(), "code")).isEqualTo("ENGINE_VALIDATION");
        Allure.addAttachment("F110-18a 证据", "text/plain",
                "bodyBytes=" + bodyLimit.statusCode() + " answerChars=" + answerLimit.statusCode()
                        + " keyChars=" + keyLimit.statusCode() + " toolCallIdChars=" + tcLimit.statusCode());
    }

    /* ==================== steering 显式键优先 ==================== */

    @Test
    @Story("F110-18: steering-queue-capacity 显式键=2 优先于旧别名——第 3 条 inject 409 满桶 limit=2")
    @DisplayName("F110-18b: steering 显式容量键优先")
    void steeringExplicitCapacityKeyWins() throws Exception {
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "limit-steer", "LIMIT-STEER-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);

        HttpResponse<String> in1 = inject(task, "注入一（容量=2 构造）");
        assertThat(in1.statusCode()).as("第 1 条受理").isEqualTo(200);
        assertThat(in1.body()).contains("\"queueLength\":1");
        HttpResponse<String> in2 = inject(task, "注入二（容量=2 构造）");
        assertThat(in2.statusCode()).as("第 2 条受理").isEqualTo(200);
        HttpResponse<String> in3 = inject(task, "注入三（应满桶拒绝）");
        assertThat(in3.statusCode()).as("第 3 条满桶 409（显式键=2 优先于缺省 100）").isEqualTo(409);
        assertThat(DaResumeSupport.jsonString(in3.body(), "code"))
                .isEqualTo("ENGINE_INJECTION_QUEUE_FULL");
        assertThat(DaResumeSupport.jsonString(in3.body(), "limit"))
                .as("limit=2（显式键生效证据；若旧别名优先则为 100，判据可鉴别）").isEqualTo("2");
        Allure.addAttachment("F110-18b 证据", "text/plain",
                "in1=" + in1.statusCode() + " in2=" + in2.statusCode() + " in3=" + in3.statusCode()
                        + " in3Body=" + in3.body());
    }

    /* ==================== early answer 独立容量 ==================== */

    @Test
    @Story("F110-18: early answer 面可构造判定——QUEUED 黑盒窗口毫秒级不可抓，承载两桶独立面")
    @DisplayName("F110-18c: early answer 独立性（可构造面）")
    void earlyAnswerIndependentCapacity() throws Exception {
        // r2 判据（76bcad137 DaTaskRecord.admitAnswer:388-395）：QUEUED 唯一条件＝
        // 「带 toolCallId 且该 id 在 openPermissionAsks 且非待答态」——即确认已发起
        // （openPermissionAsks 登记）与 settleAwaiting 置位之间的**毫秒级窗口**，
        // 黑盒不可稳定抓取（挂起态带 toolCallId 走 CLAIMED 直接消费，373-382 行）。
        // ⇒ early answer 满载格（第 101 条 409 limit=100）登记构造不可达
        //   （与 F110-06g 超时 503 格同族：代码窗口无自然注入面，不猜分支）；
        //   本方法承载可构造面：**两桶独立**——注入桶（steering 容量=2）灌满后，
        //   确认回填路径（/resume→CLAIMED）不受注入桶满影响，任务正常完成。
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "limit-early", "LIMIT-EARLY-" + System.nanoTime(),
                SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
        HttpResponse<String> in1 = inject(task, "独立性构造注入一");
        HttpResponse<String> in2 = inject(task, "独立性构造注入二");
        assertThat(in2.statusCode()).as("注入桶（容量=2）灌满").isEqualTo(200);
        HttpResponse<String> in3 = inject(task, "第三条（确认桶满不外溢）");
        assertThat(in3.statusCode()).as("注入桶满 409（与 F110-18b 同源复核）").isEqualTo(409);
        // early answer 消费路径（/resume→CLAIMED 回填）不受注入桶满影响
        HttpResponse<String> answer = DaResumeSupport.resume(http, base, task.taskId(), task.sessionId(),
                "idem-limits-early-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(answer.statusCode()).as("确认回填不受注入桶满影响（early answer 面解耦）")
                .isEqualTo(202);
        List<SseFrame> frames = task.awaitReader(180_000);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE)).isEqualTo(1);
        assertThat(Files.readString(workspaceRoot.resolve(task.sessionId()).resolve(task.fileName()))
                .split(task.marker(), -1).length - 1).isEqualTo(1);
        Allure.addAttachment("F110-18c 证据", "text/plain",
                "可构造面=两桶独立（注入满 2→409，/resume 回填 202→完成）；"
                        + "early answer 满载格登记构造不可达（QUEUED 窗口毫秒级，"
                        + "admitAnswer:388 判据——挂起态带 toolCallId 走 CLAIMED 不走 QUEUED）。");
    }

    private static HttpResponse<String> inject(DaResumeSupport.SuspendedTask task, String message)
            throws java.io.IOException, InterruptedException {
        return DaCompatGuardrailSupport.post(http, base, "/api/agents/inject/" + task.taskId(),
                "{\"message\":\"" + DaResumeSupport.escape(message) + "\"}", Duration.ofSeconds(30),
                java.util.Map.of("X-Da-Session-Id", task.sessionId()));
    }
}
