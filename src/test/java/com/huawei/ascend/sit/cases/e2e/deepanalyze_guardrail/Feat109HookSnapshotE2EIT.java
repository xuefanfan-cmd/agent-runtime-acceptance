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
 * FEAT-109 hooks 任务快照（F109-10）与生命周期顺序（F109-12 正向面）。
 *
 * <p>判据：
 * <ul>
 *   <li>快照：新任务只消费本次 runtimeConfig.hooks（L2 §3.3 首次建档）；T0 挂起后续跑沿用首次
 *       runner（DaHookSupport 首次建档复用）；T1 新快照、T2 空 hooks 零执行。</li>
 *   <li>hook 执行计数取证：command 脚本每执行一次向共享计数文件 append 一行——文件行数=执行次数
 *       （跨任务、跨挂起-恢复全程可数）。</li>
 *   <li>生命周期：AgentStart 是执行实例首个 hook、AgentComplete 最后、Stop 在 Complete 前
 *       （L2 §2.4）；正向收尾顺序经 SSE hook_result 事件的出现序断言。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109HookSnapshotE2EIT {

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;
    private static Path hookScriptDir;
    private static Path counterDir;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat109-snap-ws");
        hookScriptDir = Files.createTempDirectory("feat109-snap-hooks");
        counterDir = Files.createTempDirectory("feat109-snap-counters");
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

    /* ==================== 构造 ==================== */

    /** 计数 hook 脚本：每次执行向 counter 文件 append 一行（含任务 sessionId 环境变量）。 */
    private String counterHook(String id, String counterName) throws Exception {
        Path counter = counterDir.resolve(counterName + ".log");
        Path script = hookScriptDir.resolve("snap-" + id + "-" + System.nanoTime() + ".sh");
        Files.writeString(script, "#!/bin/sh\n"
                + "echo \"$(date +%s) $SESSION_ID\" >> " + counter.toAbsolutePath() + "\n"
                + "echo \"" + id + "\" >> " + orderLog() + "\n"
                + "exit 0\n");
        String json = "{\"id\":\"" + id + "\",\"event\":\"%EVENT%\",\"type\":\"command\","
                + "\"config\":{\"command\":\"sh " + script.toAbsolutePath() + "\"},\"enabled\":true}";
        return json.replace("%EVENT%", eventOf(id));
    }

    private String eventOf(String id) {
        return switch (id) {
            case "snap-a", "snap-b" -> "PreToolUse";
            case "lc-start" -> "AgentStart";
            case "lc-stop" -> "Stop";
            case "lc-complete" -> "AgentComplete";
            default -> "PreToolUse";
        };
    }

    /** 共享执行序文件（追加序=实际执行序——run-15 教训：Stop/Complete 的 hook_result
     *  事件不进 SSE 流（异步收尾在流 EOF 后），顺序断言改用文件追加序取证。 */
    private static Path orderLog() {
        return counterDir.resolve("order.log");
    }

    private int counter(String counterName) throws Exception {
        Path counter = counterDir.resolve(counterName + ".log");
        if (!Files.exists(counter)) {
            return 0;
        }
        return Files.readAllLines(counter).size();
    }

    /** write_file 任务（ask→answer 允许→完成），带指定 hooks；返回帧。 */
    private List<SseFrame> runWriteFileWithHooks(String session, String hooksJson, String fileName,
                                                 String marker) throws Exception {
        Files.createDirectories(workspaceRoot.resolve(session));
        String input = "使用 write_file 工具创建文件 " + fileName + "，内容：" + marker
                + "。只创建这一个文件，完成后结束。";
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
        }, "f109-snap-reader");
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
        if (toolCallId != null) {
            DaCompatGuardrailSupport.post(http, base, "/api/agents/answer/" + taskId,
                    "{\"answer\":\"允许\",\"toolCallId\":\"" + toolCallId + "\"}",
                    Duration.ofSeconds(30), Map.of("X-Da-Session-Id", session));
        }
        reader.join(170_000);
        return List.copyOf(collected);
    }

    /* ==================== 用例 ==================== */

    @Test
    @Story("F109-10: 快照——T0 全程 A（含挂起-恢复不重放）、T1 用 B、T2 空 hooks 零执行")
    @DisplayName("F109-10: hooks 任务快照 T0/T1/T2")
    void hookSnapshotAcrossTasksAndResume() throws Exception {
        // T0：挂 snap-a（PreToolUse）→ ask 挂起 → 恢复 → 完成。恢复不得重放 PreToolUse
        //（L1 出口 9：ask 恢复时同一 PreToolUse 总调用次数仍为 1）
        int t0Base = counter("a");
        String hookA = counterHook("snap-a", "a");
        String file0 = "snap-t0-" + System.nanoTime() + ".md";
        List<SseFrame> t0 = runWriteFileWithHooks("sess-snap-t0-" + System.nanoTime(),
                "[" + hookA + "]", file0, "T0-MARKER");
        int aAfterT0 = counter("a") - t0Base;
        assertThat(aAfterT0).as("T0 全程 PreToolUse hook A 恰执行 1 次（挂起前 1 次，恢复不重放）")
                .isEqualTo(1);
        assertThat(t0.stream().anyMatch(f -> f.data() != null && f.data().contains("T0-MARKER")))
                .as("T0 任务完成").isTrue();

        // T1：新任务只带 snap-b → 只执行 B，不执行 A（T0 快照不复用）
        long aBeforeT1 = counter("a");
        String hookB = counterHook("snap-b", "b");
        String file1 = "snap-t1-" + System.nanoTime() + ".md";
        runWriteFileWithHooks("sess-snap-t1-" + System.nanoTime(),
                "[" + hookB + "]", file1, "T1-MARKER");
        assertThat(counter("b")).as("T1 执行 B").isGreaterThanOrEqualTo(1);
        assertThat(counter("a") - aBeforeT1).as("T1 不执行 A（不复用 T0 快照）").isZero();

        // T2：空 hooks → A/B 均零执行
        long aBeforeT2 = counter("a");
        long bBeforeT2 = counter("b");
        String file2 = "snap-t2-" + System.nanoTime() + ".md";
        runWriteFileWithHooks("sess-snap-t2-" + System.nanoTime(), "[]", file2, "T2-MARKER");
        assertThat(counter("a") - aBeforeT2).as("T2 空 hooks 不执行 A").isZero();
        assertThat(counter("b") - bBeforeT2).as("T2 空 hooks 不执行 B").isZero();
        Allure.addAttachment("F109-10 计数", "text/plain",
                "A=" + counter("a") + " B=" + counter("b") + "（T0=1A，T1=1B，T2=0/0）");
    }

    @Test
    @Story("F109-12: 生命周期——AgentStart 首个、Stop 前于 AgentComplete、顺序可观测")
    @DisplayName("F109-12: 生命周期 hook 顺序")
    void lifecycleHookOrder() throws Exception {
        String hookStart = counterHook("lc-start", "lc-start");
        String hookStop = counterHook("lc-stop", "lc-stop");
        String hookComplete = counterHook("lc-complete", "lc-complete");
        String session = "sess-snap-lc-" + System.nanoTime();
        // read_file 任务（allow 面，单轮快速收尾——生命周期不依赖工具种类）
        String fileName = "lc-" + System.nanoTime() + ".txt";
        Files.createDirectories(workspaceRoot.resolve(session));
        Files.writeString(workspaceRoot.resolve(session).resolve(fileName), "lc-content");
        String input = "使用 read_file 工具读取文件 " + fileName + "，然后结束。不要执行其他工具。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\","
                + "\"runtimeConfig\":{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\""
                + session + "\",\"hooks\":[" + hookStart + "," + hookStop + "," + hookComplete + "]}}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(150));
        List<SseFrame> frames = DaCompatGuardrailSupport.readFrames(stream, f -> false,
                Duration.ofSeconds(150));

        // 计数面：三个生命周期 hook 各恰 1 次。run-14 教训：SSE completed ≠ 生命周期 hook 已执行
        //（L2 §3.3 控制面可异步收尾）——有界轮询等计数到位再断言，不固定 sleep
        long deadlineLc = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadlineLc
                && (counter("lc-start") < 1 || counter("lc-stop") < 1 || counter("lc-complete") < 1)) {
            Thread.sleep(300);
        }
        assertThat(counter("lc-start")).as("AgentStart 恰 1 次").isEqualTo(1);
        assertThat(counter("lc-stop")).as("Stop 恰 1 次").isEqualTo(1);
        assertThat(counter("lc-complete")).as("AgentComplete 恰 1 次").isEqualTo(1);

        // 顺序面：执行序取证 = 共享 order.log 的追加行序（run-15 教训：Stop/Complete 的
        // hook_result 事件不进 SSE 流——异步收尾在流 EOF 后；文件追加序是执行序的直接证据）
        List<String> order = Files.readAllLines(orderLog());
        List<String> lcOrder = order.stream().filter(List.of("lc-start", "lc-stop", "lc-complete")::contains)
                .toList();
        assertThat(lcOrder).as("执行序 AgentStart < Stop < AgentComplete（文件追加序）")
                .containsExactly("lc-start", "lc-stop", "lc-complete");
        Allure.addAttachment("F109-12 顺序", "text/plain", "执行序=" + lcOrder
                + " 计数 start/stop/complete=" + counter("lc-start") + "/" + counter("lc-stop")
                + "/" + counter("lc-complete"));
    }
}
