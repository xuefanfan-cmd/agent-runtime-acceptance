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
 * FEAT-109 凭据金丝雀（F109-19，DV-109-04 口径——红绿基线轮）。
 *
 * <p>判据与边界（先读后造）：
 * <ul>
 *   <li>埋点：合成金丝雀词（唯一 marker）放四处——任务输入、工具参数（write_file 内容）、
 *       hooks 配置（HTTP headers）、hook 输入（toolInput 载荷）。</li>
 *   <li>场景：允许（确认+执行）与拒绝各一轮——覆盖确认前/后两态。</li>
 *   <li>检索面（本批 SUT 形态已激活的表面）：SUT stdout 日志（全部行）、SSE 事件流（dump 全帧）、
 *       hook_result 摘要。**数据面表面（workflow_logs/session_memory/Redis/checkpoint/OTel）
 *       在数据面关闭形态不存在**——表面清单收窄并在附件声明，不冒充全表面。</li>
 *   <li>DV-109-04 红绿基线：L2 §5.3 点名的未覆盖路径（JSON 文本形态秘密、hook 自报 error、
 *       普通工具结果直通）**预期命中**——命中的面作为「已知差距确认轮」证据，不判产品新缺陷；
 *       未命中面记录为「已测表面未见」。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109CanaryE2EIT {

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;
    private static RecordedHookServer hookServer;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat109-canary-ws");
        hookServer = new RecordedHookServer();
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        .property("deepanalyze.compat.hooks.http-host-whitelist", hookServer.hostPort()))
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

    /** 允许/拒绝一轮：write_file ask 闭环 + hooks（PreToolUse HTTP + PostToolUse HTTP 带金丝雀 headers）。 */
    private record CanaryRun(String session, List<SseFrame> frames, int answerStatus) {
    }

    private CanaryRun runCanary(String suffix, String answer, String canary) throws Exception {
        String session = "sess-f109c-" + suffix + "-" + System.nanoTime();
        hookServer.respondWith(200, "");
        hookServer.reset();
        String fileName = "canary-" + System.nanoTime() + ".md";
        Files.createDirectories(workspaceRoot.resolve(session));
        String hooksJson = "[{\"id\":\"canary-pre\",\"event\":\"PreToolUse\",\"type\":\"http\","
                + "\"config\":{\"url\":\"" + hookServer.baseUrl() + "/hook\","
                + "\"headers\":{\"X-Canary\":\"" + canary + "\"}},\"enabled\":true},"
                + "{\"id\":\"canary-post\",\"event\":\"PostToolUse\",\"type\":\"http\","
                + "\"config\":{\"url\":\"" + hookServer.baseUrl() + "/hook\",\"headers\":{}},"
                + "\"enabled\":true}]";
        String input = "使用 write_file 工具创建文件 " + fileName + "，内容：" + canary
                + "。不要执行其他任何工具。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\","
                + "\"runtimeConfig\":{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\""
                + session + "\",\"hooks\":" + hooksJson + "}}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(180));
        List<SseFrame> collected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, collected::add, f -> false,
                        Duration.ofSeconds(170));
            } catch (Exception e) {
                collected.add(new SseFrame("err", "err", String.valueOf(e)));
            }
        }, "f109-canary-reader");
        reader.setDaemon(true);
        reader.start();
        String taskId = null;
        String toolCallId = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            for (SseFrame f : collected) {
                if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                    taskId = f.field("taskId");
                    toolCallId = f.field("toolCallId");
                }
            }
            if (toolCallId != null) {
                break;
            }
            Thread.sleep(400);
        }
        int status = 0;
        if (toolCallId != null) {
            HttpResponse<String> resp = DaCompatGuardrailSupport.post(http, base,
                    "/api/agents/answer/" + taskId,
                    "{\"answer\":\"" + answer + "\",\"toolCallId\":\"" + toolCallId + "\"}",
                    Duration.ofSeconds(30), Map.of("X-Da-Session-Id", session));
            status = resp.statusCode();
        }
        reader.join(170_000);
        // SSE 事件流 dump（检索面之一）
        Path dir = Path.of("target", "f109-canary");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("session=").append(session).append("\n---frames---\n");
        for (SseFrame f : collected) {
            sb.append(f.data()).append('\n');
        }
        Files.writeString(dir.resolve(session + ".txt"), sb.toString());
        return new CanaryRun(session, List.copyOf(collected), status);
    }

    /** 金丝雀检索：SSE dump + SUT stdout 日志（已激活表面）。 */
    private String canaryHits(CanaryRun run, String canary) throws Exception {
        Path dump = Path.of("target", "f109-canary", run.session() + ".txt");
        StringBuilder hits = new StringBuilder();
        if (Files.exists(dump) && Files.readString(dump).contains(canary)) {
            hits.append("SSE-dump ");
        }
        Path sutLog = Path.of("target", "sit-logs", "deepanalyze-guardrail", "stdout.log");
        if (Files.exists(sutLog) && Files.readString(sutLog).contains(canary)) {
            hits.append("SUT-stdout ");
        }
        for (var req : hookServer.requests()) {
            if (req.body() != null && req.body().contains(canary)) {
                hits.append("hook-input(body) ");
                break;
            }
        }
        return hits.toString().strip();
    }

    @Test
    @Story("F109-19: 金丝雀红绿基线——允许轮（确认+执行全链）")
    @DisplayName("F109-19a: 允许轮金丝雀")
    void canaryAllowRound() throws Exception {
        String canary = "CANARY-ALLOW-" + System.nanoTime();
        CanaryRun run = runCanary("allow", "允许", canary);
        assertThat(run.answerStatus()).isEqualTo(200);
        String hits = canaryHits(run, canary);
        // 红绿基线：DV-109-04 已知未覆盖路径=工具结果直通（DaEventTranslator.java:1263）——
        // SSE 工具结果/模型回复里出现金丝雀属预期命中（登记不判缺陷）；SUT stdout 命中=已知差距证据
        Allure.addAttachment("F109-19a 命中面", "text/plain",
                "金丝雀=" + canary + " 命中=[" + hits + "]（空=已测表面未见）"
                        + " 表面清单=SSE-dump/SUT-stdout/hook-input（数据面表面本形态不存在）");
        // hook 输入（body）含金丝雀是**正确行为**（toolInput 是受控 hook 被调方的真实业务输入，L2 §5.2）
        assertThat(hits).as("hook 输入应含金丝雀（真实输入到达受控被调方）").contains("hook-input");
    }

    @Test
    @Story("F109-19b: 金丝雀红绿基线——拒绝轮（denied 无副作用）")
    @DisplayName("F109-19b: 拒绝轮金丝雀")
    void canaryRejectRound() throws Exception {
        String canary = "CANARY-REJECT-" + System.nanoTime();
        CanaryRun run = runCanary("reject", "拒绝", canary);
        assertThat(run.answerStatus()).isEqualTo(200);
        String hits = canaryHits(run, canary);
        Allure.addAttachment("F109-19b 命中面", "text/plain",
                "金丝雀=" + canary + " 命中=[" + hits + "]（空=已测表面未见）"
                        + " 表面清单=SSE-dump/SUT-stdout/hook-input");
        // 拒绝轮：PreToolUse hook 未到（ask 在 hook 之后？否——PreToolUse 在权限评估前；
        // ask 后拒绝→工具不执行→PostToolUse 不触发）——hook 输入含金丝雀仅限 PreToolUse（真实输入）
        assertThat(hits).as("拒绝轮 PreToolUse hook 输入仍应含金丝雀（hook 在权限评估前执行）")
                .contains("hook-input");
    }
}
