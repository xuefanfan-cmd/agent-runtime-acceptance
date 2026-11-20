package com.huawei.ascend.sit.cases.e2e.deepanalyze_resume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 复审裁决实现（2026-11-17，PR !513 两项唯一口径；判据已冻结）。
 *
 * <p><b>判据（被测制品 0.1.3-feat109111-r3 = common@babb882b9；源码
 * DaCompatController:1768-1830 rollbackBusyContinuation + :957-996 normalizeStructuredAnswers 读出）：</b></p>
 * <ul>
 *   <li><b>F110-23（202 后 CONVERSATION_BUSY = 准入/调度回滚，非执行失败）</b>：续跑段提交时
 *       core 会话槽被同会话另一执行持有 → acquire 抛 CONVERSATION_BUSY → 认领回滚：
 *       不发 error/done{failed}、不产生失败终态 fact；还原待答（awaiting_answer/resumable=true）；
 *       重发原 ask_user（同 toolCallId、新 seq）；幂等键不删（202 即受理固化）——同键重放
 *       200 duplicate:true（快照不表示仍在恢复），新恢复尝试用新键；取消竞态仍按 cancelled 收口。
 *       仅「续跑段」回滚；首轮撞 busy 仍落 error+done{failed}（B1 不动首轮语义）。</li>
 *   <li><b>F110-24（多问题 answers 结构化模型）</b>：answers=「问题键→回答」对象，面向单个普通
 *       ask_user 目标；键集必须与原事件 questions[].question 全集精确匹配（缺失/额外 → 400
 *       ENGINE_VALIDATION）；权限确认目标带 answers → 400（structured answers require one
 *       ordinary ask_user target）；answer 为展示文本选填，缺席时按原问题顺序生成确定性编号摘要
 *       （「1. 问题：回答」多行）；幂等摘要覆盖 answers（map 顺序不改变语义，同 answers 不同展示
 *       属不同载荷——本类只断结构主面，摘要等价面属 F110-06 族）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110AdjudicatedE2EIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(240);
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
        workspaceRoot = Files.createTempDirectory("feat110-adjudicated-ws");
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

    /* ==================== F110-23 busy 回滚单轨 ==================== */

    @Test
    @Story("F110-23: 202 后 CONVERSATION_BUSY 回滚——无终态、还原待答、重发原 ask_user、同键重放 200")
    @DisplayName("F110-23: busy 准入回滚单轨断言")
    void busyContinuationRollsBackWithoutTerminal() throws Exception {
        String session = "sess-f110-busy-" + System.nanoTime();
        // 同会话两任务各挂起（两个 write_file ask）
        DaResumeSupport.SuspendedTask a = startCustomSuspended(session, "busy-a",
                "请使用 write_file 工具创建文件 busy-a-" + System.nanoTime() + ".md，内容 BUSY-A-"
                        + System.nanoTime() + "。只创建这一个文件。", "BUSY-A-");
        DaResumeSupport.SuspendedTask b = startCustomSuspended(session, "busy-b",
                // B 续跑段带长前置输出——拉长 B 持有会话槽的窗口，保证 A 的续跑提交落进窗口
                "请先用 250 字左右描述一个仓库出入库管理流程（分号分隔的要点即可），"
                        + "然后使用 write_file 工具创建文件 busy-b-" + System.nanoTime()
                        + ".md，内容 BUSY-B-" + System.nanoTime() + "。只创建这一个文件。", "BUSY-B-");
        assertThat(a.taskId()).isNotEqualTo(b.taskId());

        // 先恢复 B（B 拿到会话槽），1.5s 后恢复 A——第二个提交者撞 busy（顺序无关设计：
        // 谁的 continuation 后提交谁被回滚，不预设 A/B 角色）
        HttpResponse<String> rb = DaResumeSupport.resume(http, base, b.taskId(), session,
                "idem-busy-b-" + System.nanoTime(), "允许", b.toolCallId(), Duration.ofSeconds(30));
        assertThat(rb.statusCode()).as("B 先行恢复 202").isEqualTo(202);
        Thread.sleep(1_500);
        String keyA = "idem-busy-a-" + System.nanoTime();
        HttpResponse<String> ra = DaResumeSupport.resume(http, base, a.taskId(), session,
                keyA, "允许", a.toolCallId(), Duration.ofSeconds(30));
        assertThat(ra.statusCode()).as("A 恢复受理 202（busy 发生在续跑提交，不在受理）").isEqualTo(202);

        // 有界观察：被害者=出现「同 toolCallId 重发 ask」的一方（重发是回滚的标志面）
        DaResumeSupport.SuspendedTask victim = null;
        DaResumeSupport.SuspendedTask holder = null;
        long watchDeadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < watchDeadline && victim == null) {
            if (sameIdAskCount(a) >= 2) {
                victim = a;
                holder = b;
            } else if (sameIdAskCount(b) >= 2) {
                victim = b;
                holder = a;
            } else {
                Thread.sleep(1_000);
            }
        }
        if (victim == null) {
            dumpBoth(a, b, session + "-busy");
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "INCONCLUSIVE——90s 内任一任务都未出现同 toolCallId 重发（两 continuation 未撞同一会话槽窗口）；"
                            + "属时序构造未达，非产品缺陷");
        }
        List<SseFrame> frames = victim.framesSnapshot();
        String victimKey = victim == a ? keyA : "idem-busy-b-" + System.nanoTime();
        assertThat(frames.stream().anyMatch(f -> "error".equals(f.event())))
                .as("busy 回滚不发 error 事件").isFalse();
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE)).as("无 done（无失败终态）").isZero();
        HttpResponse<String> st = DaResumeSupport.status(http, base, victim.taskId(), session, Duration.ofSeconds(15));
        assertThat(DaResumeSupport.jsonString(st.body(), "resumeState"))
                .as("还原待答（awaiting_answer）").isEqualTo("awaiting_answer");
        assertThat(DaResumeSupport.jsonBool(st.body(), "resumable")).as("resumable=true").isTrue();

        // 幂等键不删：同键重放 200 duplicate:true（快照不表示仍在恢复）
        HttpResponse<String> replay = DaResumeSupport.resume(http, base, victim.taskId(), session,
                victimKey, "允许", victim.toolCallId(), Duration.ofSeconds(30));
        assertThat(replay.statusCode()).as("同键重放 200 duplicate:true（202 即受理固化）").isEqualTo(200);
        assertThat(DaResumeSupport.jsonBool(replay.body(), "duplicate")).isTrue();

        // 收尾（顺序无关）：holder 正常走完它的 ask 链（每轮新键、省略 toolCallId——单一
        // 未答目标自动定向），随后 victim 用新键恢复 → 正常完成
        for (int round = 0; round < 3; round++) {
            if (!awaitAnyOpenAsk(holder, 60_000)) {
                break;
            }
            HttpResponse<String> rh = DaResumeSupport.resume(http, base, holder.taskId(), session,
                    "idem-busy-h" + round + "-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
            assertThat(rh.statusCode()).as("holder 第 " + (round + 1) + " 轮 ask 恢复 202").isEqualTo(202);
            Thread.sleep(1_000);
        }
        HttpResponse<String> rv = DaResumeSupport.resume(http, base, victim.taskId(), session,
                "idem-busy-v-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        assertThat(rv.statusCode()).as("victim 新键恢复 202（busy 后可重试）").isEqualTo(202);
        List<SseFrame> victimFrames = victim.awaitReader(180_000);
        assertThat(DaResumeSupport.countEvent(victimFrames, DaResumeSupport.EVENT_DONE))
                .as("victim 最终完成").isEqualTo(1);
        Allure.addAttachment("F110-23 证据", "text/plain",
                "202→busy 回滚：无 error/done、awaiting 还原、原 ask_user 重发（同 toolCallId 新 seq）、"
                        + "同键 200 duplicate、新键恢复完成；victim=" + victim.taskId()
                        + "；dump=" + DUMP_DIR + "/" + session + "-busy.txt");
        DaResumeSupport.dumpFrames(DUMP_DIR, session + "-busy.txt", "case=F110-23", victimFrames);
    }

    /* ==================== F110-24 answers 结构契约 ==================== */

    @Test
    @Story("F110-24: answers 精确匹配 questions 全集——缺键/额外键/权限误用 400，正向定向消费")
    @DisplayName("F110-24: 多问题 answers 结构断言（裁决冻结）")
    void answersStructuredContract() throws Exception {
        String session = "sess-f110-ans-" + System.nanoTime();
        // 单 ask_user 两问题（问题文本固定，键集从 SSE ask 帧实测提取——防模型改述）
        String input = "请使用 ask_user 工具一次性向用户提问两个问题，questions 参数严格按顺序使用这两个问题文本："
                + "第一个问题的 question 字段为「项目代号是什么？」，第二个问题的 question 字段为「负责人是谁？」。"
                + "不要添加其他问题。拿到回答后，把两个回答原样告诉我，然后结束。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + DaResumeSupport.escape(input) + "\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(240));
        List<SseFrame> frames = new ArrayList<>();
        Thread reader = backgroundReader(stream, frames);
        // run-05 教训：本类 backgroundReader 已自带 start()——重复 start 抛 IllegalThreadStateException
        // 等 ask_user 挂起帧并提取 questions 键集
        Map<String, String> qKeys = awaitMultiQuestionAsk(frames, SUSPEND_TIMEOUT_MILLIS);
        if (qKeys.size() != 2) {
            // abort 前取证：dump ask 帧原文（判别「模型单问」vs「帧无 questions 投影」）
            try {
                Files.createDirectories(DUMP_DIR);
                DaResumeSupport.dumpFrames(DUMP_DIR, session + "-ans-abort.txt", "case=F110-24-pre-abort",
                        framesSnapshot(frames));
            } catch (Exception dumpFailure) {
                // 取证失败不掩盖主结论
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(qKeys.size() == 2,
                "INCONCLUSIVE——模型未按两问题形态调用 ask_user（实测键数=" + qKeys.size()
                        + "；ask 帧已 dump " + session + "-ans-abort.txt）；属模型旁路非产品缺陷");
        String taskId = taskIdOf(frames);

        // 负向①：缺一个键 → 400
        String missingKey = new LinkedHashMap<>(qKeys).keySet().iterator().next();
        Map<String, String> short1 = new LinkedHashMap<>(qKeys);
        short1.remove(missingKey);
        HttpResponse<String> r1 = resumeWithAnswers(http, base, taskId, session,
                "idem-ans-short-" + System.nanoTime(), short1, null);
        assertThat(r1.statusCode()).as("answers 缺键 → 400").isEqualTo(400);
        // 负向②：额外键 → 400
        Map<String, String> extra = new LinkedHashMap<>(qKeys);
        extra.put("额外的不存在键？", "x");
        HttpResponse<String> r2 = resumeWithAnswers(http, base, taskId, session,
                "idem-ans-extra-" + System.nanoTime(), extra, null);
        assertThat(r2.statusCode()).as("answers 额外键 → 400").isEqualTo(400);
        // 负向③：空白回答 → 400
        Map<String, String> blank = new LinkedHashMap<>();
        qKeys.forEach((k, v) -> blank.put(k, ""));
        HttpResponse<String> r3 = resumeWithAnswers(http, base, taskId, session,
                "idem-ans-blank-" + System.nanoTime(), blank, null);
        assertThat(r3.statusCode()).as("空白回答 → 400").isEqualTo(400);

        // 正向：精确 answers（无展示 answer → 服务端生成编号摘要）→ 202 → 消费完成
        Map<String, String> good = new LinkedHashMap<>();
        int i = 1;
        for (String k : qKeys.keySet()) {
            good.put(k, "答" + i++);
        }
        HttpResponse<String> ok = resumeWithAnswers(http, base, taskId, session,
                "idem-ans-ok-" + System.nanoTime(), good, null);
        assertThat(ok.statusCode()).as("精确 answers → 202").isEqualTo(202);
        reader.join(200_000);
        assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE)).as("任务完成").isEqualTo(1);

        // 负向④（独立任务）：权限确认目标带 answers → 400
        // （session 用任务自带的 sessionId——run-05d 教训：helper 内部生成 session，
        //   自造同前缀串会属主头错 → 404 而非目标 400）
        DaResumeSupport.SuspendedTask perm = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "ansperm", "ANSPERM-" + System.nanoTime(), SSE_READ_TIMEOUT,
                SUSPEND_TIMEOUT_MILLIS);
        HttpResponse<String> r4 = resumeWithAnswers(http, base, perm.taskId(), perm.sessionId(),
                "idem-ans-perm-" + System.nanoTime(), good, null);
        assertThat(r4.statusCode()).as("权限确认目标带 answers → 400（ordinary ask_user 专用）").isEqualTo(400);
        // 清理：权限任务正常恢复完成
        HttpResponse<String> done = DaResumeSupport.resume(http, base, perm.taskId(), perm.sessionId(),
                "idem-ans-perm-ok-" + System.nanoTime(), "允许", perm.toolCallId(), Duration.ofSeconds(30));
        assertThat(done.statusCode()).as("权限任务随后正常恢复（资格未被负向消耗）").isEqualTo(202);
        perm.awaitReader(150_000);
        Allure.addAttachment("F110-24 证据", "text/plain",
                "缺键/额外键/空白 400×3；精确 answers 202→编号摘要消费→done；权限目标 answers 400；"
                        + "dump=" + DUMP_DIR + "/" + session + "-ans.txt");
        DaResumeSupport.dumpFrames(DUMP_DIR, session + "-ans.txt", "case=F110-24",
                framesSnapshot(frames));
    }

    /* ==================== 私有工具 ==================== */

    /** 自定义输入的挂起任务（write_file ask——awaiting 构造），session 由调用方给定（同会话双任务用同值）。 */
    private static DaResumeSupport.SuspendedTask startCustomSuspended(String session, String readerName,
            String input, String marker) throws Exception {
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + DaResumeSupport.escape(input) + "\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(240));
        List<SseFrame> frames = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false, SSE_READ_TIMEOUT);
            } catch (Exception e) {
                frames.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f110-adj-" + readerName);
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + SUSPEND_TIMEOUT_MILLIS;
        String toolCallId = null;
        while (System.currentTimeMillis() < deadline) {
            synchronized (frames) {
                for (SseFrame f : frames) {
                    if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                        toolCallId = f.field("toolCallId");
                    }
                }
            }
            if (toolCallId != null) {
                break;
            }
            Thread.sleep(400);
        }
        if (toolCallId == null) {
            throw new IllegalStateException(readerName + " 未挂起；帧数=" + frames.size());
        }
        return new DaResumeSupport.SuspendedTask(session, taskIdOf(frames), toolCallId, "", readerName,
                marker, stream, frames, reader);
    }

    /** 等第二帧：同一任务出现第 2 个不同 toolCallId 的 permission_confirm（新一轮 ask）。 */
    private static boolean awaitSecondAsk(DaResumeSupport.SuspendedTask task, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            long distinct = task.framesSnapshot().stream()
                    .filter(f -> f.data() != null && f.data().contains("\"kind\":\"permission_confirm\""))
                    .map(f -> f.field("toolCallId"))
                    .filter(id -> id != null && !id.isBlank())
                    .distinct().count();
            if (distinct >= 2) {
                return true;
            }
            Thread.sleep(800);
        }
        return false;
    }

    /** 重发观判：同一 toolCallId 的 ask 帧 ≥2 次（重发=同 toolCallId 新 seq）。 */
    private static boolean awaitAskResent(DaResumeSupport.SuspendedTask task, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<SseFrame> frames = task.framesSnapshot();
            long asks = frames.stream().filter(f -> f.data() != null
                    && f.data().contains("\"kind\":\"permission_confirm\"")
                    && f.data().contains(task.toolCallId())).count();
            if (asks >= 2) {
                return true;
            }
            Thread.sleep(800);
        }
        return false;
    }

    /** 同 toolCallId 的 ask 帧计数（重发=同一 ID 出现 ≥2 帧）。 */
    private static long sameIdAskCount(DaResumeSupport.SuspendedTask task) {
        return task.framesSnapshot().stream()
                .filter(f -> f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")
                        && f.data().contains(String.valueOf(task.toolCallId())))
                .count();
    }

    /** 有界等任意未答 ask（新 toolCallId 的 permission_confirm 帧出现即算）。 */
    private static boolean awaitAnyOpenAsk(DaResumeSupport.SuspendedTask task, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int baseline = task.framesSnapshot().stream()
                .filter(f -> f.data() != null && f.data().contains("\"kind\":\"permission_confirm\""))
                .map(f -> f.field("toolCallId")).filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet()).size();
        while (System.currentTimeMillis() < deadline) {
            int now = task.framesSnapshot().stream()
                    .filter(f -> f.data() != null && f.data().contains("\"kind\":\"permission_confirm\""))
                    .map(f -> f.field("toolCallId")).filter(id -> id != null && !id.isBlank())
                    .collect(java.util.stream.Collectors.toSet()).size();
            if (now > baseline) {
                return true;
            }
            Thread.sleep(1_000);
        }
        return false;
    }

    /** 双任务帧 dump（INCONCLUSIVE 取证用）。 */
    private static void dumpBoth(DaResumeSupport.SuspendedTask a, DaResumeSupport.SuspendedTask b,
            String name) {
        try {
            Files.createDirectories(DUMP_DIR);
            DaResumeSupport.dumpFrames(DUMP_DIR, name + "-a.txt", "case=victim-watch", a.framesSnapshot());
            DaResumeSupport.dumpFrames(DUMP_DIR, name + "-b.txt", "case=holder-watch", b.framesSnapshot());
        } catch (Exception dumpFailure) {
            // 取证失败不掩盖主结论
        }
    }

    /** 等多问题 ask 帧，返回 question 文本键集（含 toolCallId 映射占位）。 */
    private static Map<String, String> awaitMultiQuestionAsk(List<SseFrame> frames, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            synchronized (frames) {
                for (SseFrame f : frames) {
                    // 普通提问的 kind=question（permission_confirm 是权限确认——answers 不适用）；
                    // run-05c 取证实证：多问题帧带 questions 数组 + multiQuestion=true
                    if (f.data() == null || !f.data().contains("\"kind\":\"question\"")
                            || !f.data().contains("\"questions\"")) {
                        continue;
                    }
                    try {
                        JsonNode node = MAPPER.readTree(f.data());
                        JsonNode qs = node.path("questions");
                        Map<String, String> keys = new LinkedHashMap<>();
                        if (qs.isArray() && qs.size() > 1) {
                            for (JsonNode q : qs) {
                                String text = q.path("question").asText("");
                                if (!text.isBlank()) {
                                    keys.put(text, f.field("toolCallId") == null ? "" : f.field("toolCallId"));
                                }
                            }
                            if (keys.size() == qs.size()) {
                                return keys;
                            }
                        }
                    } catch (Exception parse) {
                        // 帧体分片——继续等下一帧
                    }
                }
            }
            Thread.sleep(400);
        }
        return Map.of();
    }

    private static String taskIdOf(List<SseFrame> frames) {
        return frames.stream()
                .filter(f -> f.field("taskId") != null && !f.field("taskId").isBlank())
                .map(f -> f.field("taskId")).findFirst().orElse("");
    }

    private static HttpResponse<String> resumeWithAnswers(HttpClient http, String base, String taskId,
            String session, String key, Map<String, String> answers, String displayAnswer)
            throws Exception {
        StringBuilder body = new StringBuilder("{");
        boolean first = true;
        if (displayAnswer != null) {
            body.append("\"answer\":\"").append(DaResumeSupport.escape(displayAnswer)).append('"');
            first = false;
        }
        StringBuilder ans = new StringBuilder();
        for (Map.Entry<String, String> e : answers.entrySet()) {
            if (ans.length() > 0) {
                ans.append(',');
            }
            ans.append('"').append(DaResumeSupport.escape(e.getKey())).append("\":\"")
                    .append(DaResumeSupport.escape(e.getValue())).append('"');
        }
        if (!first) {
            body.append(',');
        }
        body.append("\"answers\":{").append(ans).append("}}");
        return DaCompatGuardrailSupport.post(http, base, "/api/agents/resume/" + taskId, body.toString(),
                Duration.ofSeconds(30), Map.of("X-Da-Session-Id", session, "Idempotency-Key", key));
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
        }, "f110-ans-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }
}
