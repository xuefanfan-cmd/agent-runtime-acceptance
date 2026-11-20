package com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.MemoryReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ModelStubServer;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
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
 * FEAT-111 进程退出不补跑负向钉（F111-19，对账表缺口 #6——L1 §5.1.4 / OUT「持久化反思队列」）。
 *
 * <p><b>判据（被测制品 0.1.3-feat109111-r3 = common@babb882b9）：</b></p>
 * <ul>
 *   <li>L1 §5.1.4 负向钉：进程退出前未执行的反思<b>允许丢弃、不补跑</b>——重启后无补跑、
 *       无跨重启 exactly-once（OUT「持久化反思队列」= 明文不承诺）。</li>
 *   <li>构造：stub 模型（反思延迟 90s）+ 主任务脚本化失败（HTTP 500 → MODEL_SERVICE_FAILURE
 *       失败终态）→ 反思调度进入待执行队列（睡在 90s 延迟里）→ <b>stop SUT</b> →
 *       <b>start SUT</b>（原端口，F110-15 同款基建）→ 有界等待 → 断言该会话无任何
 *       da_reflection_result 记录、agent_memory 零写入（待执行反思随进程消亡，重启后不补跑）。</li>
 *   <li>重启基建：SutStack.stop(name)/start(name)（SutStack.java:315-360 Pin the original port，
 *       F110-15 restartLosesRegistry404 已验证形态）。</li>
 *   <li>诚实边界：本格证「丢弃且不补跑」；反思在重启前已完成的面由
 *       Feat111ReflectionGatingE2EIT/BoundedE2EIT 承接，不属本格。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111ReflectionRestartE2EIT {

    /** 反思延迟：远大于「重启+等待」窗口，保证 kill 时反思必然仍在队列未执行。 */
    private static final long REFLECTION_DELAY_MILLIS = 90_000;
    /** 重启后有界观察窗：若产品错误地补跑，记录会落日志；窗口够任何快速补跑显形。 */
    private static final long POST_RESTART_OBSERVE_MILLIS = 20_000;

    @Test
    @Story("F111-19: 进程退出前未执行的反思允许丢弃——重启后不补跑、无跨重启 exactly-once")
    @DisplayName("F111-19: 重启丢弃待执行反思（负向钉）")
    void restartDiscardsPendingReflection() throws Exception {
        TestConfig config = TestConfig.load();
        ModelStubServer stub = new ModelStubServer();
        stub.reflectionDelay(REFLECTION_DELAY_MILLIS);
        // 主调用持久失败（script 队列耗尽后回落普通回答——run-01c 实测：单条 error 会被
        // core 重试吞掉后任务反而完成；mainError 对全部非反思请求持续生效）
        stub.mainError(500, "{\"error\":{\"message\":\"stub persistent failure\"}}");
        MemoryReceiverServer memory = new MemoryReceiverServer();
        Path hooksRoot = Files.createTempDirectory("feat111-rs-hooks");
        SutStack stack = DaReflectionCostSupport.baseStack(config, agent -> {
            DaReflectionCostSupport.bindStubModel(agent, stub);
            DaReflectionCostSupport.gatesOpen(agent);
            agent.property("deepanalyze.compat.hooks.command-root",
                    hooksRoot.toAbsolutePath().toString());
            // 反思超时调大到 120s（缺省 10s——run-01d 实证：10s 超时先于 kill 到点，
            // 反思以 outcome=failed(reason=reflection_error) 落账，"待执行"前提不成立）；
            // 调大后 stub 的 90s 延迟窗内 kill，反思必然在途
            agent.property("deepanalyze.reflection.timeout", "PT120S");
            // memory 写面接线（DA_TOOL_PLATFORM_URL——agent_memory 经工具平台服务，wireObservability 同款）
            agent.env("DA_TOOL_PLATFORM_URL", memory.baseUrl());
        }).start();
        HttpClient http = DaCompatGuardrailSupport.newHttpClient();
        String base = stack.client(DaReflectionCostSupport.AGENT).getBaseUrl();
        try {
            // ---- 失败终态（反思触发条件）----
            String session = "sess-f111-rs-" + System.nanoTime();
            String body = "{\"sessionId\":\"" + session + "\",\"input\":\"触发反思的重启丢弃格任务\"}";
            HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                    Duration.ofSeconds(120));
            if (stream.statusCode() != 200) {
                String rejected = new String(stream.body().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                throw new IllegalStateException("run-stream 受理失败 HTTP " + stream.statusCode()
                        + " body=" + rejected);
            }
            List<SseFrame> frames = new ArrayList<>();
            DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false,
                    Duration.ofSeconds(120));
            Files.createDirectories(Path.of("target", "f111-dump"));
            Files.writeString(Path.of("target", "f111-dump", session + "-restart.txt"),
                    frames.stream().map(f -> "[" + f.event() + "] " + f.data())
                            .reduce((a, b) -> a + "\n" + b).orElse("<空>"));
            assertThat(frames.stream().anyMatch(f -> f.data() != null
                    && f.data().contains("failed")))
                    .as("主任务失败终态（反思调度前置）").isTrue();
            // 反思请求异步发出——有界轮询至 stub 收到（防 EOF 后立刻断言的竞态）
            long reflectDeadline = System.currentTimeMillis() + 15_000;
            while (stub.reflectionCalls() < 1 && System.currentTimeMillis() < reflectDeadline) {
                Thread.sleep(300);
            }
            assertThat(stub.reflectionCalls()).as("反思 provider 调用已发生（调度进入执行）").isGreaterThanOrEqualTo(1);

            // ---- kill：反思睡在 90s 延迟里，必然未完成 ----
            stack.stop(DaReflectionCostSupport.AGENT);
            stack.start(DaReflectionCostSupport.AGENT);

            // ---- 重启完成信号：新进程受理新任务 ----
            long deadline = System.currentTimeMillis() + 60_000;
            boolean ready = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    HttpResponse<String> health = DaCompatGuardrailSupport.post(http, base,
                            "/api/agents/run-stream",
                            "{\"sessionId\":\"sess-f111-rs-hc-" + System.nanoTime()
                                    + "\",\"input\":\"health\"}", Duration.ofSeconds(10));
                    if (health.statusCode() == 200) {
                        ready = true;
                        break;
                    }
                } catch (Exception reconnectWindow) {
                    // 重启窗口期连接拒绝——继续轮询
                }
                Thread.sleep(1_000);
            }
            assertThat(ready).as("重启后 SUT 就绪（60s 内受理新任务）").isTrue();

            // ---- 有界观察窗：无补跑（原会话零记录、memory 零写入）----
            Thread.sleep(POST_RESTART_OBSERVE_MILLIS);
            assertThat(DaReflectionCostSupport.recordsForSession(session))
                    .as("原会话无 da_reflection_result（待执行反思被丢弃且不补跑）").isEmpty();
            assertThat(memory.writeCount()).as("agent_memory 零写入（无补跑副作用）").isEqualTo(0);
            Allure.addAttachment("F111-19 证据", "text/plain",
                    "失败终态→反思调度（reflectionCalls≥1，延迟 90s）→kill→重启→"
                            + POST_RESTART_OBSERVE_MILLIS / 1000 + "s 观察窗内零记录零写入；"
                            + "L1 §5.1.4 负向钉（丢弃允许、不补跑）成立");
        } finally {
            stub.close();
            memory.close();
            stack.close();
        }
    }
}
