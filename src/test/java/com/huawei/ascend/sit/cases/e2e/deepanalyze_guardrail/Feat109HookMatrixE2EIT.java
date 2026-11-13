package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.RecordedHookServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

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
 * FEAT-109 hooks 输出协议结果矩阵（F109-15，L1 §3.3 × L2 §3.5，DV-109-05 口径）。
 *
 * <p>判据：
 * <ul>
 *   <li>hooks 是每任务 runtimeConfig 快照（L2 §3.3 首次建档）——同一 SUT 实例可承载全部矩阵格，
 *       每格一个任务、hooks 数组内联构造。</li>
 *   <li>构造工具 = read_file（actrule {@code read_file: allow}——不触发 ask，hooks 全链
 *       PreToolUse→权限 allow→工具→PostToolUse 在一条流内走完；目标文件由用例预先写入 workspace）。</li>
 *   <li>PreToolUse 列断言「工具执行与否」：deny 格=流中无 read_file 的 tool_result 且有 denied 痕迹；
 *       allow 格=tool_result 出现。非 blocking 列断言 hook_result 的 status 字段
 *       （completed/denied/failed/timeout，L2 §2.3）。</li>
 *   <li>模型旁路分流：模型未调 read_file（纯文本回复）⇒ INCONCLUSIVE（铁律）。</li>
 *   <li>64KiB 超限格（DV-109-05）：L2 明示「达到上限不额外探测超限，不保证判超限」——本格只测
 *       截断解释行为，Oracle 双栏标 partial。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109HookMatrixE2EIT {

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;
    private static RecordedHookServer hookServer;
    private static Path hookScriptDir;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat109-matrix-ws");
        hookScriptDir = Files.createTempDirectory("feat109-matrix-hooks");
        hookServer = new RecordedHookServer();
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
        if (hookServer != null) {
            hookServer.close();
        }
    }

    /* ==================== 构造 ==================== */

    /** 写命令 hook 脚本（相对 command-root=user.dir 的路径须落在根内 → 用绝对 tmp 路径配 command-root）。 */
    private static String scriptHook(String scriptBody) throws Exception {
        Path script = hookScriptDir.resolve("h-" + System.nanoTime() + ".sh");
        Files.writeString(script, scriptBody);
        return script.toAbsolutePath().toString();
    }

    /** 发起带 hooks 的 read_file 任务并整流读取，返回帧列表。 */
    private static List<SseFrame> runReadFileTask(String hooksJson, String marker) throws Exception {
        // 预写目标文件（read_file 的对象；marker 用于内容断言）
        String fileName = "matrix-" + System.nanoTime() + ".txt";
        Files.writeString(workspaceRoot.resolve(fileName), "content-" + marker);
        String session = "sess-f109m-" + System.nanoTime();
        String input = "使用 read_file 工具读取文件 " + fileName + "，然后告诉我文件内容。"
                + "不要执行其他任何工具。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\","
                + "\"runtimeConfig\":{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\""
                + session + "\",\"hooks\":" + hooksJson + "}}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(150));
        List<SseFrame> frames = DaCompatGuardrailSupport.readFrames(stream, f -> false,
                Duration.ofSeconds(150));
        // dump（逐轮取证纪律）
        Path dir = Path.of("target", "f109-dump");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("session=").append(session).append("\n---frames---\n");
        for (int i = 0; i < frames.size(); i++) {
            sb.append("[").append(i).append("] data=").append(frames.get(i).data()).append('\n');
        }
        Files.writeString(dir.resolve(session + ".txt"), sb.toString());
        return frames;
    }

    private static void assertModelCalledReadFile(List<SseFrame> frames) {
        boolean readToolSeen = frames.stream().anyMatch(f -> f.data() != null
                && (f.data().contains("\"toolName\":\"read_file\"")
                || f.data().contains("read_file")));
        boolean confirm = frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("\"kind\":\"permission_confirm\""));
        org.junit.jupiter.api.Assumptions.assumeTrue(readToolSeen || confirm,
                "INCONCLUSIVE——模型未调 read_file（纯文本回复未触达 hooks 面）；属模型旁路非产品缺陷");
    }

    private static boolean toolExecuted(List<SseFrame> frames) {
        // 工具执行观察面：read_file 的成功结果（tool_result / content 帧含目标内容）
        return frames.stream().anyMatch(f -> f.data() != null
                && (f.data().contains("content-matrix") || f.data().contains("tool_result")));
    }

    private static String hookResultStatus(List<SseFrame> frames, String hookId) {
        return frames.stream()
                .filter(f -> f.data() != null && f.data().contains("\"event\":\"PostToolUse\"")
                        && f.data().contains(hookId))
                .map(f -> f.field("status")).filter(s -> s != null).findFirst().orElse(null);
    }

    /* ==================== PreToolUse 列（deny/allow） ==================== */

    private static String preToolUseCmdHook(String id, String scriptBody) throws Exception {
        return "{\"id\":\"" + id + "\",\"event\":\"PreToolUse\",\"type\":\"command\","
                + "\"config\":{\"command\":\"" + scriptHook(scriptBody) + "\"},\"enabled\":true}";
    }

    @Test
    @DisplayName("F109-15a: PreToolUse 空输出 → 未发表意见 allow")
    void preToolUseEmptyOutputAllows(TestInfo info) throws Exception {
        String hook = preToolUseCmdHook("mtx-a", "#!/bin/sh\nexit 0\n");
        List<SseFrame> frames = runReadFileTask("[" + hook + "]", "a" + System.nanoTime());
        assertModelCalledReadFile(frames);
        assertThat(toolExecuted(frames)).as("空输出=无阻断意见，工具执行").isTrue();
    }

    @Test
    @DisplayName("F109-15b: PreToolUse allowed=false → deny 工具不执行")
    void preToolUseDenyBlocksTool(TestInfo info) throws Exception {
        String hook = preToolUseCmdHook("mtx-b",
                "#!/bin/sh\nprintf '{\"allowed\":false,\"error\":\"matrix-deny\"}'\nexit 0\n");
        List<SseFrame> frames = runReadFileTask("[" + hook + "]", "b" + System.nanoTime());
        assertModelCalledReadFile(frames);
        assertThat(toolExecuted(frames)).as("allowed=false → 工具不执行").isFalse();
        assertThat(frames.stream().anyMatch(f -> f.data() != null && f.data().contains("denied")))
                .as("deny 证据可见").isTrue();
    }

    @Test
    @DisplayName("F109-15c: PreToolUse 非 JSON → 非法结果 deny")
    void preToolUseNonJsonDenies(TestInfo info) throws Exception {
        String hook = preToolUseCmdHook("mtx-c", "#!/bin/sh\nprintf 'not-json-at-all'\nexit 0\n");
        List<SseFrame> frames = runReadFileTask("[" + hook + "]", "c" + System.nanoTime());
        assertModelCalledReadFile(frames);
        assertThat(toolExecuted(frames)).as("非 JSON → deny，工具不执行").isFalse();
    }

    @Test
    @DisplayName("F109-15d: PreToolUse JSON 顶层数组 → 非法结果 deny")
    void preToolUseTopLevelArrayDenies(TestInfo info) throws Exception {
        String hook = preToolUseCmdHook("mtx-d", "#!/bin/sh\nprintf '[1,2,3]'\nexit 0\n");
        List<SseFrame> frames = runReadFileTask("[" + hook + "]", "d" + System.nanoTime());
        assertModelCalledReadFile(frames);
        assertThat(toolExecuted(frames)).as("顶层数组 → deny").isFalse();
    }

    @Test
    @DisplayName("F109-15e: PreToolUse modifiedInput → 运行时非法 deny")
    void preToolUseModifiedInputDenies(TestInfo info) throws Exception {
        String hook = preToolUseCmdHook("mtx-e",
                "#!/bin/sh\nprintf '{\"allowed\":true,\"modifiedInput\":{\"path\":\"x\"}}'\nexit 0\n");
        List<SseFrame> frames = runReadFileTask("[" + hook + "]", "e" + System.nanoTime());
        assertModelCalledReadFile(frames);
        assertThat(toolExecuted(frames)).as("modifiedInput（含 null）→ 运行时非法 deny").isFalse();
    }

    @Test
    @DisplayName("F109-15f: PreToolUse 非零退出 → 执行失败 deny")
    void preToolUseNonZeroExitDenies(TestInfo info) throws Exception {
        String hook = preToolUseCmdHook("mtx-f", "#!/bin/sh\nexit 3\n");
        List<SseFrame> frames = runReadFileTask("[" + hook + "]", "f" + System.nanoTime());
        assertModelCalledReadFile(frames);
        assertThat(toolExecuted(frames)).as("非零退出 → deny").isFalse();
    }

    /* ==================== 非 blocking 列（PostToolUse 状态） ==================== */

    private static String postToolUseCmdHook(String id, String scriptBody) throws Exception {
        return "{\"id\":\"" + id + "\",\"event\":\"PostToolUse\",\"type\":\"command\","
                + "\"config\":{\"command\":\"" + scriptHook(scriptBody) + "\"},\"enabled\":true}";
    }

    @Test
    @DisplayName("F109-15g: PostToolUse 非 JSON → failed 记录且业务继续")
    void postToolUseNonJsonFailedButToolOk(TestInfo info) throws Exception {
        String hook = postToolUseCmdHook("mtx-g", "#!/bin/sh\nprintf 'garbage'\nexit 0\n");
        List<SseFrame> frames = runReadFileTask("[" + hook + "]", "g" + System.nanoTime());
        assertModelCalledReadFile(frames);
        assertThat(toolExecuted(frames)).as("非 blocking 失败不改业务结果（工具已执行）").isTrue();
        assertThat(frames.stream().anyMatch(f -> f.data() != null
                        && f.data().contains("\"event\":\"PostToolUse\"")
                        && f.data().contains("mtx-g") && f.data().contains("\"failed\"")))
                .as("hook_result status=failed（failed 记录并继续）").isTrue();
    }

    @Test
    @DisplayName("F109-15h: PostToolUse HTTP 非 2xx → failed（受控接收端 500）")
    void postToolUseHttpNon2xxFailed(TestInfo info) throws Exception {
        hookServer.respondWith(500, "boom");
        String hook = "{\"id\":\"mtx-h\",\"event\":\"PostToolUse\",\"type\":\"http\","
                + "\"config\":{\"url\":\"" + hookServer.baseUrl() + "/hook\"},\"enabled\":true}";
        List<SseFrame> frames = runReadFileTask("[" + hook + "]", "h" + System.nanoTime());
        assertModelCalledReadFile(frames);
        assertThat(toolExecuted(frames)).as("HTTP 500 → failed 记录且业务不变").isTrue();
        assertThat(hookServer.requestCount()).as("受控接收端收到 POST").isGreaterThanOrEqualTo(1);
        assertThat(hookServer.requests().get(hookServer.requestCount() - 1).header("Content-Type"))
                .as("HTTP hook 输入 Content-Type=application/json").contains("application/json");
        hookServer.reset();
        hookServer.respondWith(200, "");
    }

    @Test
    @DisplayName("F109-13: HTTP hook 收到真实 toolInput/toolResult（POST body 结构）")
    void httpHookReceivesRealToolInput(TestInfo info) throws Exception {
        hookServer.respondWith(200, "");
        String marker = "real-input-" + System.nanoTime();
        String hook = "{\"id\":\"mtx-i\",\"event\":\"PreToolUse\",\"type\":\"http\","
                + "\"config\":{\"url\":\"" + hookServer.baseUrl() + "/hook\"},\"enabled\":true}";
        List<SseFrame> frames = runReadFileTask("[" + hook + "]", marker);
        assertModelCalledReadFile(frames);
        // PreToolUse HTTP hook 输入含 toolName/toolCallId/toolInput（L2 §2.3 最小上下文）
        assertThat(hookServer.requestCount()).as("受控接收端收到 PreToolUse POST").isGreaterThanOrEqualTo(1);
        String body = hookServer.requests().get(hookServer.requestCount() - 1).body();
        assertThat(body).as("输入含 toolName=read_file").contains("read_file");
        assertThat(body).as("输入含 hookId/event/taskId/sessionId/tenantId/occurredAt 最小字段")
                .contains("hookId").contains("\"event\"").contains("taskId")
                .contains("sessionId").contains("tenantId").contains("occurredAt");
        Allure.addAttachment("F109-13 HTTP 输入", "text/plain", body);
        hookServer.reset();
        hookServer.respondWith(200, "");
    }
}
