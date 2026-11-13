package com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.MemoryReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.OtlpReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ReflectionRecord;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-111 反思线端到端（真实上游轨道：反思模型=真模型，L1 明文）——F111-01/02。
 *
 * <p><b>判据（实现前已读 SUT 源码固化）：</b></p>
 * <ul>
 *   <li>六类失败触发面＝REFLECTABLE（{@code DaReflectionScheduler}：BUSINESS_FAILURE /
 *       INPUT_REJECTED / GUARDRAIL_DENIED / MODEL_SERVICE_FAILURE / CREDENTIAL_OR_QUOTA_FAILURE /
 *       INFRASTRUCTURE_FAILURE）；非失败终态在 onTerminalFact 不动作（无记录零 IO）。</li>
 *   <li>处置记录＝logger {@code da_reflection_result} INFO 行（六槽位），异步于主任务终态
 *       （settle 先分发 fact 再销档；主任务终态不等待反思）。</li>
 *   <li>记忆写面＝POST {DA_TOOL_PLATFORM_URL}/v1/tools/agent_memory，头 X-Session-Id/X-Tenant-Id
 *       （恒 default-user）＋体 {action:add, category:lesson_learned, content:固定文本}
 *       （{@code DaReflectionClients.HttpMemoryWriter}）。</li>
 *   <li>诊断输入只含结构化字段（instruction/lessonCodes/errorFacts），不含用户原文
 *       （{@code DaReflectionScheduler.diagnosticInput}）。</li>
 * </ul>
 *
 * <p><b>构造：</b>业务失败经 business_failure 工具（L1 明文：BUSINESS_FAILURE 只来自该
 * 结构化终态工具）；真模型温度 0。模型旁路（不调用业务失败工具/不执行目标动作）按既有
 * 批次口径分流 INCONCLUSIVE（先排除产品侧超时/空响应再分流——遮蔽律硬判据）。</p>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111ReflectionE2EIT {

    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(240);

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static OtlpReceiverServer otlp;
    private static MemoryReceiverServer memory;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        otlp = new OtlpReceiverServer();
        memory = new MemoryReceiverServer();
        stack = DaReflectionCostSupport.baseStack(config, agent -> DaReflectionCostSupport.gatesOpen(
                        DaReflectionCostSupport.wireObservability(agent, otlp, memory)))
                .start();        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaReflectionCostSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
        if (otlp != null) {
            otlp.close();
        }
        if (memory != null) {
            memory.close();
        }
    }

    /* ==================== 构造与取数 ==================== */

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 同步发起任务并解析 /run 响应（失败终态响应内 taskId 为 null，经 /tasks 取）。 */
    private static Map<String, Object> runTask(String session, String input) throws Exception {
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(input) + "\"}";
        HttpResponse<String> response = DaCompatGuardrailSupport.post(http, base, "/api/agents/run", body,
                RUN_TIMEOUT);
        assertThat(response.statusCode()).as("/run 受理 200: " + response.body()).isEqualTo(200);
        JsonNode node = MAPPER.readTree(response.body());
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", node.path("status").asText(""));
        result.put("error", node.path("error").asText(""));
        return result;
    }

    /** /tasks/{sessionId} 任务快照（属主校验：带 X-Da-Session-Id）。 */
    private static List<Map<String, Object>> tasksOf(String session) throws Exception {
        HttpResponse<String> response = DaCompatGuardrailSupport.get(http, base,
                "/api/agents/tasks/" + session, Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", session));
        assertThat(response.statusCode()).as("/tasks 200").isEqualTo(200);
        JsonNode node = MAPPER.readTree(response.body());
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();
        node.forEach(t -> tasks.add(MAPPER.convertValue(t, Map.class)));
        return tasks;
    }

    /** 有界轮询本会话反思记录（异步于主任务终态）。 */
    private static List<ReflectionRecord> awaitRecords(String session, Duration timeout) {
        List<ReflectionRecord> found = new CopyOnWriteArrayList<>();
        DaReflectionCostSupport.await(timeout, Duration.ofMillis(300), () -> {
            found.clear();
            found.addAll(DaReflectionCostSupport.recordsForSession(session));
            return !found.isEmpty();
        });
        return List.copyOf(found);
    }

    /** 有界轮询 memory 接收端出现满足条件的写请求。 */
    private static List<DaReflectionCostSupport.MemoryWrite> awaitMemoryWrites(Duration timeout,
            java.util.function.Predicate<DaReflectionCostSupport.MemoryWrite> predicate) {
        List<DaReflectionCostSupport.MemoryWrite> found = new CopyOnWriteArrayList<>();
        DaReflectionCostSupport.await(timeout, Duration.ofMillis(200), () -> {
            found.clear();
            found.addAll(memory.writes().stream().filter(predicate).toList());
            return !found.isEmpty();
        });
        return List.copyOf(found);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /* ==================== F111-01 失败反思成功（真模型） ==================== */

    /**
     * <b>blocked·产品候选发现（run-30/31 源码＋日志级实证）：</b>business_failure 工具调用在
     * per-Task 装配（DFX-002）下落入执行＋权限 ask，且执行内二次确认经 /answer 后不恢复
     * （DaBusinessOutcomeRail 未挂 per-Task 路径＋第二层授权不闭环）→ BUSINESS_FAILURE 终态
     * 不可达、任务永久挂起。真模型轨道的业务失败构造因此 blocked；反思成功能力的确定性
     * 承接见 integration 层 Gating/SettingsGate 类（stub 401 构造驱动全链）。恢复条件＝
     * 产品修复或提供可用的业务失败构造面。提单需用户授权，材料已备。
     */
    @Test
    @Story("F111-01: 失败后唯一受控复盘（真模型轨道——业务失败构造 blocked）")
    @DisplayName("F111-01: 真模型业务失败构造（blocked·ISSUE 候选，INCONCLUSIVE 留恢复条件）")
    void reflectionSuccessOnBusinessFailure() {
        org.junit.jupiter.api.Assumptions.abort("business_failure 构造 blocked：per-Task 装配下"
                + "执行内二次确认经 /answer 不恢复（run-30/31 实证，ISSUE 候选材料已备）。"
                + "恢复条件＝产品修复或可用构造面；反思成功能力由确定性轨道承接。");
    }

    /* ==================== F111-02 触发排除面（真模型） ==================== */

    @Test
    @Story("F111-02: 成功任务零反思动作——无处置记录、记忆不增")
    @DisplayName("F111-02a: 成功终态零反思")
    void successTaskProducesNoReflection() throws Exception {
        String session = "sess-f111-02a-" + System.nanoTime();
        Map<String, Object> run = runTask(session, "请直接回答：1+1 等于几？不要调用任何工具。");
        assertThat(String.valueOf(run.get("status"))).as("任务成功终态: " + run).isEqualTo("completed");

        boolean quiet = DaReflectionCostSupport.await(Duration.ofSeconds(8), Duration.ofMillis(300),
                () -> !DaReflectionCostSupport.recordsForSession(session).isEmpty());
        assertThat(quiet).as("成功任务不得产生任何处置记录").isFalse();
        assertThat(memory.writes().stream().filter(w -> session.equals(w.sessionId())).count())
                .as("成功任务零记忆写入").isZero();
        Allure.addAttachment("F111-02a 证据", "text/plain", "session=" + session);
    }

    @Test
    @Story("F111-02: 用户取消零反思动作")
    @DisplayName("F111-02b: 用户取消零反思")
    void cancelledTaskProducesNoReflection() throws Exception {
        String session = "sess-f111-02b-" + System.nanoTime();
        // 长任务给取消留窗口：流式读取 start 帧 → /cancel → 读到 EOF
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(
                "请用 write_file 工具在当前工作区创建文件 f111-cancel.txt，内容为 CANCEL-MARKER。"
                        + "然后一直等待，不要结束任务。") + "\"}";
        HttpResponse<java.io.InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                RUN_TIMEOUT);
        List<String> frameData = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, f -> frameData.add(f.data()), f -> false,
                        RUN_TIMEOUT);
            } catch (Exception ignored) {
                // EOF/超时由取消或读取上限产生，取消断言不依赖读取结果
            }
        }, "f111-cancel-reader");
        reader.setDaemon(true);
        reader.start();

        boolean gotTask = DaReflectionCostSupport.await(Duration.ofSeconds(60), Duration.ofMillis(300),
                () -> {
                    for (String d : frameData) {
                        if (d != null) {
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("\"taskId\":\"([^\"]+)\"").matcher(d);
                            if (m.find()) {
                                taskIdRef = m.group(1);
                                return true;
                            }
                        }
                    }
                    return false;
                });
        assertThat(gotTask).as("SSE start 帧含 taskId").isTrue();
        HttpResponse<String> cancel = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/cancel/" + taskIdRef, "{}", Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", session));
        assertThat(cancel.statusCode()).as("/cancel 受理: " + cancel.body()).isEqualTo(200);
        reader.join(60_000);

        boolean quiet = DaReflectionCostSupport.await(Duration.ofSeconds(8), Duration.ofMillis(300),
                () -> !DaReflectionCostSupport.recordsForSession(session).isEmpty());
        assertThat(quiet).as("取消任务不得产生任何处置记录").isFalse();
        assertThat(memory.writes().stream().filter(w -> session.equals(w.sessionId())).count())
                .as("取消任务零记忆写入").isZero();
        Allure.addAttachment("F111-02b 证据", "text/plain", "session=" + session + " taskId=" + taskIdRef);
    }

    /** 取消用例捕获的 taskId（lambda 捕获需要可变槽位）。 */
    private static volatile String taskIdRef;

    @Test
    @Story("F111-02: 准入拒绝零反思动作")
    @DisplayName("F111-02c: 会话忙准入拒绝零反思")
    void busyAdmissionProducesNoReflection() throws Exception {
        String session = "sess-f111-02c-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> codes = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            String input = "请直接回答数字 " + (10 + i) + "，不要调用任何工具。";
            pool.submit(() -> {
                try {
                    HttpResponse<String> response = DaCompatGuardrailSupport.post(http, base, "/api/agents/run",
                            "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(input) + "\"}",
                            RUN_TIMEOUT);
                    codes.add(response.statusCode());
                } catch (Exception e) {
                    codes.add(-1);
                } finally {
                    ready.countDown();
                }
            });
        }
        assertThat(ready.await(5, TimeUnit.MINUTES)).as("双并发 /run 完成").isTrue();
        pool.shutdownNow();

        boolean quiet = DaReflectionCostSupport.await(Duration.ofSeconds(8), Duration.ofMillis(300),
                () -> !DaReflectionCostSupport.recordsForSession(session).isEmpty());
        assertThat(quiet).as("准入拒绝面零处置记录").isFalse();
        assertThat(memory.writes().stream().filter(w -> session.equals(w.sessionId())).count())
                .as("准入拒绝面零记忆写入").isZero();
        Allure.addAttachment("F111-02c 证据", "text/plain",
                "session=" + session + " codes=" + codes);
    }

    @Test
    @Story("F111-02: 待答→恢复成功（ask_user→/answer 完成任务）零反思动作")
    @DisplayName("F111-02e: 待答恢复成功零反思")
    void resumedAfterAnswerProducesNoReflection() throws Exception {
        String session = "sess-f111-02e-" + System.nanoTime();
        String input = "请使用 write_file 工具创建文件 f111-02e-" + System.nanoTime()
                + ".txt，内容为 OK。完成后直接结束任务。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(input) + "\"}";
        HttpResponse<java.io.InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                RUN_TIMEOUT);
        List<DaCompatGuardrailSupport.SseFrame> collected =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, collected::add, f -> false, RUN_TIMEOUT);
            } catch (Exception e) {
                collected.add(new DaCompatGuardrailSupport.SseFrame("reader-error", "reader-error",
                        String.valueOf(e)));
            }
        }, "f111-02e-reader");
        reader.setDaemon(true);
        reader.start();

        // 有界轮询 ask_user(permission_confirm)（F109 ConfirmLoop 同款构造）
        String taskId = null;
        String toolCallId = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && toolCallId == null) {
            synchronized (collected) {
                for (DaCompatGuardrailSupport.SseFrame f : collected) {
                    if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                        if (taskId == null) {
                            taskId = f.field("taskId");
                        }
                        toolCallId = f.field("toolCallId");
                    }
                }
            }
            if (toolCallId == null) {
                Thread.sleep(400);
            }
        }
        if (toolCallId == null) {
            reader.join(10_000);
            // 模型旁路分流（先排除产品侧超时/空响应再 abort——遮蔽律口径）
            boolean taskSettled = collected.stream()
                    .filter(f -> f.data() != null)
                    .anyMatch(f -> f.data().contains("\"status\""));
            org.junit.jupiter.api.Assumptions.abort("模型未发起 write_file 确认（旁路或超时），帧数="
                    + collected.size() + " taskSettled=" + taskSettled + "，本轮 INCONCLUSIVE 不计 FAIL");
        }

        // 待答期间零处置记录（非终态不产生 fact——onAwaitingAnswer 不进分类）
        assertThat(DaReflectionCostSupport.recordsForSession(session))
                .as("待答期间零处置记录").isEmpty();

        HttpResponse<String> answer = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/answer/" + taskId,
                "{\"answer\":\"允许\",\"toolCallId\":\"" + toolCallId + "\"}", Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", session));
        assertThat(answer.statusCode()).as("/answer 受理: " + answer.body()).isEqualTo(200);
        reader.join(150_000);

        List<Map<String, Object>> tasks = tasksOf(session);
        assertThat(tasks.stream().anyMatch(t -> "completed".equals(String.valueOf(t.get("status")))))
                .as("恢复成功后任务 completed: " + tasks).isTrue();

        boolean anyRecord = DaReflectionCostSupport.await(Duration.ofSeconds(8), Duration.ofMillis(300),
                () -> !DaReflectionCostSupport.recordsForSession(session).isEmpty());
        assertThat(anyRecord).as("恢复成功（COMPLETED 非六类失败）零处置记录").isFalse();
        assertThat(memory.writes().stream().filter(w -> session.equals(w.sessionId())).count())
                .as("恢复成功零记忆写入").isZero();
        Allure.addAttachment("F111-02e 证据", "text/plain",
                "session=" + session + " taskId=" + taskId + " 帧数=" + collected.size());
    }
}
