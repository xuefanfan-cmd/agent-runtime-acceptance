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

}
