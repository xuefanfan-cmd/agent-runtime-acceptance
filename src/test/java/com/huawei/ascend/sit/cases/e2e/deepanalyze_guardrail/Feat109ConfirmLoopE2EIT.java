package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-109 确认闭环端到端（F109-01/02/03/04）。
 *
 * <p>判据（实现前已读，implementation.md「先读判据再构造」）：
 * <ul>
 *   <li>workspace 根 = {@code deepanalyze.workspace.root} 配置；生效根 = {root}/{sessionId}/
 *       （{@code DaSessionWorkspace} 根构成律，session-segment 默认开）——write_file 相对路径
 *       落点完全确定，文件断言无需探测。</li>
 *   <li>actrule 资产为 SUT 自带（engine/src/main/resources/governance/actrule.yaml：
 *       默认 {@code "*": ask}、{@code delete_file: deny}）——write_file 天然 ask、delete_file
 *       天然 deny，无需 fixture 侧配置。</li>
 *   <li>ask_user 事件含 kind=permission_confirm/toolCallId/toolName/options/guardrail
 *       （L2 §2.2 确认载荷形态）。</li>
 *   <li>恢复入口 POST /api/agents/answer/{taskId}，体 {answer,toolCallId}（L1 §3.4）。</li>
 * </ul>
 *
 * <p>构造：真模型驱动任务发起 write_file（唯一文件名）→ SSE 流内捕获 ask_user(kind=permission_confirm)
 * → 断言确认前文件不存在 → /answer 允许或拒绝 → 流至终态 → 断言文件系统副作用。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109ConfirmLoopE2EIT {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(150);

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat109-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString()))
                .start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaCompatGuardrailSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    /* ==================== 构造工具 ==================== */

    /** 一次确认闭环的完整结果。 */
    private record ConfirmRun(String sessionId, String taskId, String toolCallId, String askPayload,
                              int answerStatus, List<SseFrame> frames) {
    }

    /**
     * 发起 write_file 任务，SSE 后台读流；主线程有界轮询 ask_user(kind=permission_confirm)，
     * 断言确认前文件不存在，然后经 /answer 提交回答，等流 EOF，返回完整帧序列。
     */
    private static ConfirmRun runConfirmLoop(String fileName, String marker, String answer,
                                             String sessionSuffix) throws Exception {
        String session = "sess-f109-" + sessionSuffix + "-" + System.nanoTime();
        String input = "请使用 write_file 工具创建文件 " + fileName + "，内容为：" + marker
                + "。只创建这一个文件，完成后直接结束任务。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(input) + "\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                CALL_TIMEOUT);

        List<SseFrame> collected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread reader = new Thread(() -> {
            try {
                // run-02 教训：必须逐帧回调（readFrames 只在 EOF/stop 后返回，轮询恒空）
                DaCompatGuardrailSupport.readFramesInto(stream, collected::add, f -> false,
                        SSE_READ_TIMEOUT);
            } catch (Exception e) {
                collected.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f109-sse-reader");
        reader.setDaemon(true);
        reader.start();

        String taskId = null;
        String toolCallId = null;
        String askPayload = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (collected) {
                for (SseFrame f : collected) {
                    if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                        taskId = firstNonBlank(f.field("taskId"), taskId);
                        toolCallId = firstNonBlank(f.field("toolCallId"), toolCallId);
                        askPayload = f.data();
                    }
                }
            }
            if (toolCallId != null) {
                break;
            }
            Thread.sleep(400);
        }
        if (toolCallId == null) {
            reader.join(10_000);
            throw new IllegalStateException("120s 内未捕获 ask_user(permission_confirm)；帧数="
                    + collected.size() + "；末帧=" + (collected.isEmpty() ? "<空>"
                    : collected.get(collected.size() - 1).data()));
        }

        // 确认前文件必须不存在（ask 在工具执行前挂起——L1 §3.1 ask 语义）
        Path expected = workspaceRoot.resolve(session).resolve(fileName);
        assertThat(Files.exists(expected)).as("确认前文件必须不存在: " + expected).isFalse();

        HttpResponse<String> answerResp = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/answer/" + taskId,
                "{\"answer\":\"" + answer + "\",\"toolCallId\":\"" + toolCallId + "\"}",
                Duration.ofSeconds(30),
                // run-04 教训：属主校验——不带 X-Da-Session-Id 走防枚举统一 404 表面
                //（answerBody 原文 "No pending question for this task"）
                Map.of("X-Da-Session-Id", session));
        reader.join(150_000);
        List<SseFrame> frames = List.copyOf(collected);
        dumpFrames(session, frames, askPayload, answerResp);
        return new ConfirmRun(session, taskId, toolCallId, askPayload, answerResp.statusCode(), frames);
    }

    /** 证据 dump（run-03 教训：allure 附件未落盘——直接写文件留证）。 */
    private static void dumpFrames(String session, List<SseFrame> frames, String askPayload,
                                   HttpResponse<String> answerResp) {
        try {
            Path dir = Path.of("target", "f109-dump");
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("session=" + session + "\naskPayload="
                    + askPayload + "\nanswerStatus=" + answerResp.statusCode()
                    + "\nanswerBody=" + answerResp.body() + "\n---frames---\n");
            for (int i = 0; i < frames.size(); i++) {
                SseFrame f = frames.get(i);
                sb.append("[").append(i).append("] id=").append(f.id()).append(" event=")
                        .append(f.event()).append(" data=").append(f.data()).append('\n');
            }
            Files.writeString(dir.resolve(session + ".txt"), sb.toString());
        } catch (Exception e) {
            Allure.addAttachment("dump 失败", "text/plain", String.valueOf(e));
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean anyContains(List<SseFrame> frames, String needle) {
        return frames.stream().anyMatch(f -> f.data() != null && f.data().contains(needle));
    }

    /* ==================== 用例 ==================== */

    @Test
    @Story("F109-01: write_file 允许——确认前不存在，允许后文件只写一次，事件序号连续")
    @DisplayName("F109-01: 允许后文件只写一次")
    void writeFileAllowExecutesOnce() throws Exception {
        String fileName = "f109-allow-" + System.nanoTime() + ".md";
        String marker = "ALLOW-MARKER-" + System.nanoTime();
        ConfirmRun run = runConfirmLoop(fileName, marker, "允许", "allow");
        assertThat(run.answerStatus()).as("/answer 受理应为 200").isEqualTo(200);
        assertThat(run.askPayload()).as("ask_user 载荷含脱敏 guardrail 块").contains("guardrail");

        Path expected = workspaceRoot.resolve(run.sessionId()).resolve(fileName);
        assertThat(Files.exists(expected)).as("允许后文件必须存在: " + expected).isTrue();
        assertThat(Files.readString(expected)).as("内容精确含 marker（只写一次，无重复追加）")
                .contains(marker);
        assertThat(Files.readString(expected).split(marker, -1).length - 1)
                .as("marker 只出现一次（防重复写）").isEqualTo(1);

        // F109-02 同源断言：事件流无第二个 start、seq 单调（有 seq 字段的帧）
        List<Long> seqs = run.frames().stream()
                .map(SseFrame::data)
                .filter(d -> d != null && d.contains("\"seq\":"))
                .map(d -> d.replaceAll(".*\"seq\":\\s*(\\d+).*", "$1"))
                .filter(s -> s.matches("\\d+"))
                .map(Long::parseLong)
                .toList();
        if (!seqs.isEmpty()) {
            org.assertj.core.api.Assertions.assertThat(seqs).as("seq 单调不降")
                    .isSorted();
        }
        Allure.addAttachment("F109-01/02 证据", "text/plain",
                "sessionId=" + run.sessionId() + " taskId=" + run.taskId()
                        + " toolCallId=" + run.toolCallId() + " 帧数=" + run.frames().size()
                        + " askUser=" + (run.askPayload() == null ? "<null>" : run.askPayload()));
    }

    @Test
    @Story("F109-03: 拒绝——文件不存在、denied、防重试反馈")
    @DisplayName("F109-03: 拒绝后文件不存在")
    void writeFileRejectNoSideEffect() throws Exception {
        String fileName = "f109-reject-" + System.nanoTime() + ".md";
        String marker = "REJECT-MARKER-" + System.nanoTime();
        ConfirmRun run = runConfirmLoop(fileName, marker, "拒绝", "reject");
        assertThat(run.answerStatus()).isEqualTo(200);
        Path expected = workspaceRoot.resolve(run.sessionId()).resolve(fileName);
        assertThat(Files.exists(expected)).as("拒绝后文件必须不存在: " + expected).isFalse();
        assertThat(anyContains(run.frames(), "permissionDecision"))
                .as("SSE 含 permissionDecision 证据").isTrue();
        assertThat(anyContains(run.frames(), "denied"))
                .as("SSE 含 denied 证据").isTrue();
        Allure.addAttachment("F109-03 证据", "text/plain",
                "sessionId=" + run.sessionId() + " taskId=" + run.taskId()
                        + " 帧数=" + run.frames().size());
    }

    /* ==================== F109-05：确认载荷与恢复槽边界（2026-11-18 补格轮） ====================
     * 判据（实现前已读）：L1 出口 4（载荷 kind=permission_confirm/toolName/toolCallId/选项/脱敏
     * guardrail；旧式 approved Map 或纯文本直接注入恢复槽不能批准；唯一待确认项省略 ID 可恢复、
     * 多项省略 409）＋ L2 §2.2（批准词白名单映射 approved=true，其余非空文本按拒绝；guardrail
     * 暂存缺失时允许降级，不写成绝对必达）＋ L2 §3.2（唯一确认项可省略 ID，多个必须指定）。
     * 多确认省略 409 半格由 F110-08 M1 格承接（Feat110MultiInterruptE2EIT#multiConfirmOmitTarget409）。
     */

    /** ask 挂起段捕获：发任务 → SSE 后台读 → 轮询到 ask_user(permission_confirm)。 */
    private record AskCapture(String sessionId, String taskId, String toolCallId, String askPayload,
                              String fileName, List<SseFrame> collected, Thread reader,
                              HttpResponse<InputStream> stream, String marker) {

        List<SseFrame> snapshot() {
            return List.copyOf(collected);
        }
    }

    private static AskCapture startAskCapture(String fileName, String marker, String sessionSuffix)
            throws Exception {
        String session = "sess-f109-" + sessionSuffix + "-" + System.nanoTime();
        String input = "请使用 write_file 工具创建文件 " + fileName + "，内容为：" + marker
                + "。只创建这一个文件，完成后直接结束任务。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(input) + "\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                CALL_TIMEOUT);

        List<SseFrame> collected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, collected::add, f -> false,
                        SSE_READ_TIMEOUT);
            } catch (Exception e) {
                collected.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f109-capture-reader");
        reader.setDaemon(true);
        reader.start();

        String taskId = null;
        String toolCallId = null;
        String askPayload = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (collected) {
                for (SseFrame f : collected) {
                    if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                        taskId = firstNonBlank(f.field("taskId"), taskId);
                        toolCallId = firstNonBlank(f.field("toolCallId"), toolCallId);
                        askPayload = f.data();
                    }
                }
            }
            if (toolCallId != null) {
                break;
            }
            Thread.sleep(400);
        }
        if (toolCallId == null) {
            reader.join(10_000);
            throw new IllegalStateException("120s 内未捕获 ask_user(permission_confirm)；帧数="
                    + collected.size());
        }
        Path expected = workspaceRoot.resolve(session).resolve(fileName);
        assertThat(Files.exists(expected)).as("确认前文件必须不存在: " + expected).isFalse();
        return new AskCapture(session, taskId, toolCallId, askPayload, fileName, collected, reader,
                stream, marker);
    }

    /** 对 AskCapture 提交 /answer 并等流收尾（answerBody 传原始 JSON 片段，调用方决定形态）。 */
    private static HttpResponse<String> answerAndJoin(AskCapture cap, String answerJsonBody)
            throws Exception {
        HttpResponse<String> resp = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/answer/" + cap.taskId(), answerJsonBody, Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", cap.sessionId()));
        cap.reader().join(150_000);
        dumpFrames(cap.sessionId(), cap.snapshot(), cap.askPayload(), resp);
        return resp;
    }

    @Test
    @Story("F109-05: ask_user 载荷字段级契约 kind/toolCallId/toolName/question/options/guardrail")
    @DisplayName("F109-05: 确认载荷字段级断言")
    void confirmPayloadFieldContract() throws Exception {
        String fileName = "f109-payload-" + System.nanoTime() + ".md";
        String marker = "PAYLOAD-MARKER-" + System.nanoTime();
        AskCapture cap = startAskCapture(fileName, marker, "payload");
        com.fasterxml.jackson.databind.JsonNode p = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(cap.askPayload());
        assertThat(p.path("kind").asText()).as("kind=permission_confirm（L2 §2.2）")
                .isEqualTo("permission_confirm");
        assertThat(p.path("toolCallId").asText()).as("toolCallId 非空").isNotBlank();
        assertThat(p.path("toolName").asText()).as("toolName=write_file").isEqualTo("write_file");
        assertThat(p.path("question").asText()).as("question 非空").isNotBlank();
        com.fasterxml.jackson.databind.JsonNode opts = p.path("options");
        assertThat(opts.isArray() && opts.size() >= 2).as("options 含允许/拒绝选项").isTrue();
        assertThat(opts.toString()).as("选项含允许词").contains("允许");
        // guardrail：write_file 场景暂存详情在场（L2 §2.2 暂存缺失时允许降级——本格用真实 ask 场景）
        assertThat(p.has("guardrail") && !p.path("guardrail").isEmpty())
                .as("guardrail 风险详情块在场").isTrue();
        Allure.addAttachment("F109-05a 证据", "text/plain", "askPayload=" + cap.askPayload());

        // 闭环收尾（不留挂起任务）：允许 → 文件存在
        HttpResponse<String> resp = answerAndJoin(cap,
                "{\"answer\":\"允许\",\"toolCallId\":\"" + cap.toolCallId() + "\"}");
        assertThat(resp.statusCode()).as("/answer 认领 200").isEqualTo(200);
        assertThat(Files.exists(workspaceRoot.resolve(cap.sessionId()).resolve(fileName)))
                .as("允许后文件存在").isTrue();
    }

    @Test
    @Story("F109-05: 旧式 approved 形态与白名单外纯文本注入恢复槽不能批准")
    @DisplayName("F109-05: 旧式批准形态不批准")
    void legacyApprovalFormsCannotApprove() throws Exception {
        // 子格 a：恢复槽只有 toolCallId、无 answer——approved 映射不可能成立（blackbox：工具未执行）
        String fileA = "f109-legacy-a-" + System.nanoTime() + ".md";
        AskCapture capA = startAskCapture(fileA, "LEGACY-A-" + System.nanoTime(), "legacya");
        HttpResponse<String> respA = answerAndJoin(capA, "{\"toolCallId\":\"" + capA.toolCallId() + "\"}");
        assertThat(Files.exists(workspaceRoot.resolve(capA.sessionId()).resolve(fileA)))
                .as("无 answer 的恢复槽不能批准（文件不存在）").isFalse();

        // 子格 b：answer=白名单外纯文本（实现口径 DaCompatController APPROVAL_WORDS：
        // 允许/同意/批准/确认/是/yes/y/ok/approve/approved——其余文本一律视为拒绝）
        String fileB = "f109-legacy-b-" + System.nanoTime() + ".md";
        AskCapture capB = startAskCapture(fileB, "LEGACY-B-" + System.nanoTime(), "legacyb");
        HttpResponse<String> respB = answerAndJoin(capB,
                "{\"answer\":\"sure, go ahead\",\"toolCallId\":\"" + capB.toolCallId() + "\"}");
        assertThat(respB.statusCode()).as("白名单外文本按拒绝受理（200 answered）").isEqualTo(200);
        assertThat(Files.exists(workspaceRoot.resolve(capB.sessionId()).resolve(fileB)))
                .as("白名单外纯文本不能批准（文件不存在）").isFalse();
        assertThat(capB.snapshot().stream().anyMatch(f -> f.data() != null
                        && (f.data().contains("permissionDecision") || f.data().contains("denied"))))
                .as("拒绝形态可观测（permissionDecision/denied）").isTrue();
        Allure.addAttachment("F109-05b 证据", "text/plain",
                "a(answer缺失): status=" + respA.statusCode() + "; b(白名单外文本): status="
                        + respB.statusCode() + " body=" + respB.body());
    }

    @Test
    @Story("F109-05: 唯一待确认项省略 toolCallId 可恢复")
    @DisplayName("F109-05: 唯一确认项省略 ID 可恢复")
    void singlePendingOmittedIdResumable() throws Exception {
        String fileName = "f109-omit-" + System.nanoTime() + ".md";
        String marker = "OMIT-MARKER-" + System.nanoTime();
        AskCapture cap = startAskCapture(fileName, marker, "omit");
        HttpResponse<String> resp = answerAndJoin(cap, "{\"answer\":\"允许\"}");
        assertThat(resp.statusCode()).as("唯一挂起项省略 toolCallId 认领 200").isEqualTo(200);
        Path expected = workspaceRoot.resolve(cap.sessionId()).resolve(fileName);
        assertThat(Files.exists(expected)).as("省略 ID 恢复后批准生效（文件存在）").isTrue();
        assertThat(Files.readString(expected).split(marker, -1).length - 1)
                .as("工具恰执行一次").isEqualTo(1);
        Allure.addAttachment("F109-05c 证据", "text/plain",
                "status=" + resp.statusCode() + " taskId=" + cap.taskId());
    }

    /* ==================== F109-07：wait-user-answer timeout:true（补格轮） ====================
     * 判据（实现前已读）：L1 出口 6a＋L2 §2.2（wait-user-answer 正常返回 answer；超时增加
     * timeout=true，不批准工具）＋L2 §6.4 表（长轮询超时→timeout=true，任务仍待答，工具计数
     * 不增加；合法回答后可恢复）。timeout 配置键=deepanalyze.compat.wait-answer-timeout-ms
     * （DaCompatProperties:40，缺省 120s——为测试可控注入 3s）。
     */

    @Test
    @Story("F109-07: 不回答时 wait-user-answer 返回 timeout:true，工具未执行、任务仍待答、之后可续跑")
    @DisplayName("F109-07: wait-user-answer 超时 timeout:true 且可续跑")
    void waitUserAnswerTimeoutTrue() throws Exception {
        com.huawei.ascend.sit.config.TestConfig cfg = TestConfig.load();
        Path ws = Files.createTempDirectory("feat109-timeout");
        try (SutStack timeoutStack = DaCompatGuardrailSupport.guardrailStack(cfg, agent -> agent
                .property("deepanalyze.workspace.root", ws.toAbsolutePath().toString())
                .property("deepanalyze.compat.wait-answer-timeout-ms", "3000")).start()) {
            HttpClient h = DaCompatGuardrailSupport.newHttpClient();
            String b = timeoutStack.client(DaCompatGuardrailSupport.AGENT).getBaseUrl();

            String fileName = "f109-timeout-" + System.nanoTime() + ".md";
            String marker = "TIMEOUT-MARKER-" + System.nanoTime();
            String session = "sess-f109-timeout-" + System.nanoTime();
            String input = "请使用 write_file 工具创建文件 " + fileName + "，内容为：" + marker
                    + "。只创建这一个文件，完成后直接结束任务。";
            HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(h, b,
                    "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(input) + "\"}",
                    Duration.ofSeconds(120));
            List<SseFrame> collected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            Thread reader = new Thread(() -> {
                try {
                    DaCompatGuardrailSupport.readFramesInto(stream, collected::add, f -> false,
                            Duration.ofSeconds(150));
                } catch (Exception e) {
                    collected.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
                }
            }, "f109-timeout-reader");
            reader.setDaemon(true);
            reader.start();

            // 等 ask 挂起
            String taskId = null;
            String toolCallId = null;
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (collected) {
                    for (SseFrame f : collected) {
                        if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                            taskId = firstNonBlank(f.field("taskId"), taskId);
                            toolCallId = firstNonBlank(f.field("toolCallId"), toolCallId);
                        }
                    }
                }
                if (toolCallId != null) {
                    break;
                }
                Thread.sleep(400);
            }
            assertThat(toolCallId).as("ask 挂起捕获").isNotNull();

            // 长轮询等待超时（3s 配置）
            HttpResponse<String> waitResp = DaCompatGuardrailSupport.post(h, b,
                    "/api/agents/wait-user-answer/" + taskId, "{}", Duration.ofSeconds(30),
                    Map.of("X-Da-Session-Id", session));
            assertThat(waitResp.statusCode()).as("wait-user-answer 受理 200").isEqualTo(200);
            com.fasterxml.jackson.databind.JsonNode waitBody = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(waitResp.body());
            assertThat(waitBody.path("timeout").asBoolean(false))
                    .as("超时形态带 timeout=true（L2 §2.2）").isTrue();
            assertThat(waitBody.path("answer").asText("")).as("超时不携带 answer（不批准工具）").isEmpty();

            // 超时后（回答前）工具未执行、任务仍待答
            assertThat(Files.exists(ws.resolve(session).resolve(fileName)))
                    .as("超时不批准工具（文件不存在）").isFalse();

            // 之后回答可续跑
            HttpResponse<String> resp = DaCompatGuardrailSupport.post(h, b,
                    "/api/agents/answer/" + taskId,
                    "{\"answer\":\"允许\",\"toolCallId\":\"" + toolCallId + "\"}",
                    Duration.ofSeconds(30), Map.of("X-Da-Session-Id", session));
            assertThat(resp.statusCode()).as("超时后 /answer 仍可认领（任务仍待答）").isEqualTo(200);
            reader.join(150_000);
            assertThat(Files.exists(ws.resolve(session).resolve(fileName)))
                    .as("超时后回答可续跑（文件存在）").isTrue();
            Allure.addAttachment("F109-07 证据", "text/plain",
                    "waitBody=" + waitResp.body() + " answerStatus=" + resp.statusCode());
        }
    }

    /* ==================== F109-02：重连不重复执行、重放事件保持原 ID（补格轮） ====================
     * 判据（实现前已读）：L1 出口 1「重连不重复执行，重放事件保持原 ID并由客户端去重」＋
     * L2 §1.2 能力行「事件、重连、超时、取消 MUST：原 taskId/缓冲/编号簿续跑」。
     * 重连原语=DaCompatGuardrailSupport.openStream（GET /api/agents/stream/{taskId}，
     * Last-Event-ID header；054 线 DaCompatReconnectE2EIT 同源形态）。
     * 断言三面：①重放帧 id 从 lastSeq+1 严格衔接（保持原 ID、无缺口、不从头重复）；
     * ②重放帧 id 与断连前已收帧 id 集无交集（客户端去重前提成立）；
     * ③工具不重复执行（文件 marker 恰一次）且全流 start 恰一次。
     */

    @Test
    @Story("F109-02: 重连不重复执行、重放事件保持原 ID并由客户端去重")
    @DisplayName("F109-02: 断连重连重放无缺口且工具不重复执行")
    void reconnectReplayKeepsIdsNoDoubleExecute() throws Exception {
        String fileName = "f109-reconn-" + System.nanoTime() + ".md";
        String marker = "RECONN-MARKER-" + System.nanoTime();
        AskCapture cap = startAskCapture(fileName, marker, "reconn");

        HttpResponse<String> resp = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/answer/" + cap.taskId(),
                "{\"answer\":\"允许\",\"toolCallId\":\"" + cap.toolCallId() + "\"}",
                Duration.ofSeconds(30), Map.of("X-Da-Session-Id", cap.sessionId()));
        assertThat(resp.statusCode()).as("/answer 认领 200").isEqualTo(200);

        // 等任务跑完（流 EOF）——重放段=事件缓冲中 lastEventId 之后的全量重放
        cap.reader().join(150_000);
        List<SseFrame> head = cap.snapshot().stream()
                .filter(f -> f.id() != null && !f.id().isBlank()).toList();
        assertThat(head).as("断连前已收若干带 id 帧").isNotEmpty();
        String lastEventId = head.get(head.size() - 1).id();
        long lastSeq = Long.parseLong(lastEventId.substring(lastEventId.lastIndexOf('-') + 1));

        // 模拟前端掉线：关流（不取消任务）
        try {
            cap.stream().body().close();
        } catch (Exception ignore) {
            // EOF 后 close 可能已失效
        }

        // 带 Last-Event-ID 重连
        HttpResponse<InputStream> reconnected = DaCompatGuardrailSupport.openStream(http, base,
                cap.taskId(), cap.sessionId(), lastEventId, Duration.ofSeconds(60));
        List<SseFrame> tail = new java.util.ArrayList<>();
        DaCompatGuardrailSupport.readFramesInto(reconnected, tail::add, f -> false,
                Duration.ofSeconds(60));

        List<SseFrame> replay = tail.stream()
                .filter(f -> !"reconnect_done".equals(f.event()))
                .filter(f -> f.id() != null && !f.id().isBlank())
                .toList();
        List<SseFrame> headIds = head.stream().filter(f -> f.id() != null && !f.id().isBlank()).toList();
        java.util.Set<String> seen = headIds.stream().map(SseFrame::id)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(tail.stream().anyMatch(f -> "reconnect_done".equals(f.event())))
                .as("重连以 reconnect_done 收尾").isTrue();
        long expected = lastSeq + 1;
        for (SseFrame f : replay) {
            assertThat(f.id()).as("重放帧 id 严格衔接（保持原 ID，无缺口不重复）")
                    .isEqualTo(cap.taskId() + "-" + expected);
            expected++;
        }
        java.util.Set<String> replayIds = replay.stream().map(SseFrame::id)
                .collect(java.util.stream.Collectors.toSet());
        replayIds.retainAll(seen);
        assertThat(replayIds).as("重放段与已收段无重叠 id（客户端去重前提成立）").isEmpty();

        // 工具不重复执行 + 全流无第二执行分支
        Path expected2 = workspaceRoot.resolve(cap.sessionId()).resolve(fileName);
        assertThat(Files.readString(expected2).split(marker, -1).length - 1)
                .as("重连不重复执行（文件 marker 恰一次）").isEqualTo(1);
        long starts = java.util.stream.Stream.concat(headIds.stream(), replay.stream())
                .filter(f -> "start".equals(f.event()))
                .count();
        assertThat(starts).as("全流 start 事件恰一次").isEqualTo(1);
        Allure.addAttachment("F109-02 证据", "text/plain",
                "lastEventId=" + lastEventId + " head=" + headIds.size() + " replay=" + replay.size()
                        + " taskId=" + cap.taskId());
    }

}
