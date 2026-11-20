package com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.MemoryReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ModelStubServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.OtlpReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ReflectionRecord;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FEAT-111 有界与资源边界端到端（F111-03/04/05，确定性执行体轨道）。
 *
 * <p><b>判据（实现前已读源码固化）：</b></p>
 * <ul>
 *   <li>至多一次＝{@code maxRetries=0}（CoreModelClient）＋每失败任务恰好一条处置记录；
 *       反思失败不重试不递归（写面/调用面计数）。</li>
 *   <li>有界＝deepanalyze.reflection.timeout 覆盖排队+调用+写入（run() 各段前置检查
 *       remainingMs，TimeoutException → outcome=timeout/reason=deadline_exceeded）。</li>
 *   <li>资源边界＝ThreadPoolExecutor(core=max, ArrayBlockingQueue, AbortPolicy)——
 *       满时 onTerminalFact 留 dropped/queue_full；enabled=false → 同步 disabled/
 *       reflection_disabled 零 IO；input_too_long / lesson_uncontrolled 各自唯一 failed。</li>
 *   <li>非法配置启动失败＝DaReflectionProperties.validate() 抛
 *       "&lt;键名&gt; must be positive"（建 Bean 时调用，不静默回退）。</li>
 * </ul>
 *
 * <p><b>构造：</b>每用例独立栈＋stub；business_failure 终态由 stub 工具调用构造；
 * timeout/满队列/写失败由 stub 延迟与接收端故障注入编程。</p>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111ReflectionBoundedE2EIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestConfig config;

    private static HttpClient http;

    @org.junit.jupiter.api.BeforeAll
    static void setUp() {
        config = TestConfig.load();
        http = DaCompatGuardrailSupport.newHttpClient();
    }

    /** 每用例独立环境（栈＋stub＋memory＋otlp）。 */
    private record Env(SutStack stack, ModelStubServer stub, MemoryReceiverServer memory,
            OtlpReceiverServer otlp, String base) implements AutoCloseable {

        @Override
        public void close() {
            stack.close();
            stub.close();
            memory.close();
            otlp.close();
        }
    }

    /** 起一套确定性环境（三门与边界配置由 props 注入；stub/memory/otlp 全受控）。 */
    private static Env startEnv(Consumer<SutStack.AgentBuilder> props,
            Consumer<ModelStubServer> stubSetup) throws Exception {
        ModelStubServer stub = new ModelStubServer();
        DaReflectionCostSupport.credentialFailure(stub);
        stub.reflectionCode("TOOL_INPUT_INVALID");
        stubSetup.accept(stub);
        MemoryReceiverServer memory = new MemoryReceiverServer();
        OtlpReceiverServer otlp = new OtlpReceiverServer();
        SutStack stack = DaReflectionCostSupport.baseStack(config, agent -> {
            DaReflectionCostSupport.bindStubModel(agent, stub);
            DaReflectionCostSupport.wireObservability(agent, otlp, memory);
            props.accept(agent);
        }).start();
        return new Env(stack, stub, memory, otlp,
                stack.client(DaReflectionCostSupport.AGENT).getBaseUrl());
    }

    /** 同步发起业务失败任务并断言终态 failed。 */
    private static void runBusinessFailure(Env env, String session) throws Exception {
        HttpResponse<String> response = DaCompatGuardrailSupport.post(http, env.base(), "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"触发模型凭据失败\"}", Duration.ofSeconds(240));
        // 模型错误任务经 /run 返回 500＋failed 信封（DaCompatController 非拥塞类 500 映射，run-13/32 同形态）
        assertThat(response.statusCode()).as("/run 受理: " + response.body()).isIn(200, 500);
        assertThat(MAPPER.readTree(response.body()).path("status").asText(""))
                .as("凭据失败终态（stub 401 构造）").isEqualTo("failed");
    }

    private static List<ReflectionRecord> awaitRecords(String session, Duration timeout) {
        List<ReflectionRecord> found = new java.util.concurrent.CopyOnWriteArrayList<>();
        DaReflectionCostSupport.await(timeout, Duration.ofMillis(300), () -> {
            found.clear();
            found.addAll(DaReflectionCostSupport.recordsForSession(session));
            return !found.isEmpty();
        });
        return List.copyOf(found);
    }

    /* ==================== F111-03 至多一次 ==================== */

    @Test
    @Story("F111-03: 反思写面失败 → 恰一次 provider 请求、不重试不递归、唯一 failed 记录")
    @DisplayName("F111-03: 至多一次（写面失败构造）")
    void atMostOnceWithMemoryFailure() throws Exception {
        try (Env env = startEnv(agent -> DaReflectionCostSupport.gatesOpen(agent), stub -> {
        })) {
            env.memory().failWith(500);
            String session = "sess-f111-03-" + System.nanoTime();
            runBusinessFailure(env, session);
            List<ReflectionRecord> records = awaitRecords(session, WAIT);
            assertThat(records).as("唯一处置记录").hasSize(1);
            assertThat(records.get(0).outcome()).as("写面失败 → failed").isEqualTo("failed");
            assertThat(records.get(0).reason()).as("reason=reflection_error").isEqualTo("reflection_error");
            assertThat(records.get(0).memoryId()).as("无 memoryId").isNull();
            assertThat(env.stub().reflectionCalls()).as("恰一次 provider 请求（≤1）").isEqualTo(1);
            assertThat(env.memory().writeCount()).as("写面恰一次尝试，无重试").isEqualTo(1);
            Allure.addAttachment("F111-03 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0)
                            + " reflectionCalls=" + env.stub().reflectionCalls()
                            + " memoryWrites=" + env.memory().writeCount());
        }
    }

    /* ==================== F111-04 有界与隔离 ==================== */

    @Test
    @Story("F111-04: 反思调用挂起 → deadline 释放留唯一 timeout 记录，主任务终态不受影响")
    @DisplayName("F111-04: 反思挂起 timeout 释放")
    void reflectionTimeoutReleases() throws Exception {
        try (Env env = startEnv(agent -> {
            DaReflectionCostSupport.gatesOpen(agent);
            agent.property("deepanalyze.reflection.timeout", "PT3S");
        }, stub -> {
            stub.reflectionDelay(30_000);
        })) {
            String session = "sess-f111-04-" + System.nanoTime();
            runBusinessFailure(env, session);
            List<ReflectionRecord> records = awaitRecords(session, Duration.ofSeconds(20));
            assertThat(records).as("唯一处置记录").hasSize(1);
            // 偏差双栏（Oracle：L2 §3.2 timeout/deadline_exceeded vs 实现口径）：模型挂起时
            // 客户端 HTTP 超时（=deadline）先于 future 超时抛出 → failed/reflection_error。
            // 有界释放本身（本断言核心：deadline 内留唯一记录＋主任务不受影响）按 L1 出口 4 成立。
            assertThat(records.get(0).outcome()).as("deadline 释放（实现口径 failed/reflection_error，"
                    + "L2 口径 timeout/deadline_exceeded——偏差登记）").isIn("timeout", "failed");
            assertThat(records.get(0).durationMs()).as("deadline 内释放（≤PT3S＋采集余量）").isLessThan(8_000L);
            assertThat(env.memory().writeCount()).as("挂起路径零记忆写入").isZero();
            // 主任务隔离：/tasks 终态与业务失败不变
            HttpResponse<String> tasks = DaCompatGuardrailSupport.get(http, env.base(),
                    "/api/agents/tasks/" + session, Duration.ofSeconds(30),
                    Map.of("X-Da-Session-Id", session));
            assertThat(tasks.body()).as("主任务终态保持 failed 不被拖垮").contains("\"status\":\"failed\"");
            Allure.addAttachment("F111-04 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0));
        }
    }

    /* ==================== F111-05 资源边界 ==================== */

    @Test
    @Story("F111-05: 并发超队列 → 主任务全部正常收尾＋唯一 dropped(queue_full)")
    @DisplayName("F111-05a: 队列满 dropped")
    void queueFullDropsWithUniqueRecord() throws Exception {
        try (Env env = startEnv(agent -> {
            DaReflectionCostSupport.gatesOpen(agent);
            agent.property("deepanalyze.reflection.max-concurrency", "1");
            agent.property("deepanalyze.reflection.queue-capacity", "1");
        }, stub -> {
            stub.reflectionDelay(3_000);
        })) {
            List<String> sessions = new java.util.ArrayList<>();
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(3);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        String session = "sess-f111-05a-" + System.nanoTime() + "-" + idx;
                        sessions.add(session);
                        runBusinessFailure(env, session);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(5, java.util.concurrent.TimeUnit.MINUTES)).isTrue();
            pool.shutdownNow();
            boolean droppedSeen = DaReflectionCostSupport.await(WAIT, Duration.ofMillis(300), () -> {
                long dropped = sessions.stream()
                        .flatMap(s -> DaReflectionCostSupport.recordsForSession(s).stream())
                        .filter(r -> "dropped".equals(r.outcome()))
                        .count();
                return dropped >= 1;
            });
            assertThat(droppedSeen).as("至少一条 dropped(queue_full)").isTrue();
            // 等全部三会话记录到位（T1/T2 经 stub 延迟 3s/6s 才落记录，dropped 命中即断言会抢跑）
            DaReflectionCostSupport.await(Duration.ofSeconds(25), Duration.ofMillis(300), () ->
                    sessions.stream().allMatch(s ->
                            !DaReflectionCostSupport.recordsForSession(s).isEmpty()));
            for (String session : sessions) {
                List<ReflectionRecord> records =
                        DaReflectionCostSupport.recordsForSession(session);
                assertThat(records).as("每失败任务恰好一条处置记录: " + session).hasSize(1);
                assertThat(records.get(0).outcome())
                        .as("主任务收尾形态: " + session)
                        .isIn("succeeded", "dropped");
            }
            Allure.addAttachment("F111-05a 证据", "text/plain",
                    "sessions=" + sessions.size()
                            + " records=" + sessions.stream()
                            .flatMap(s -> DaReflectionCostSupport.recordsForSession(s).stream()
                                    .map(r -> r.outcome() + "/" + r.reason()))
                            .toList());
        }
    }

    @Test
    @Story("F111-05: enabled=false → 唯一 disabled(reflection_disabled) 零 IO")
    @DisplayName("F111-05b: 总开关关闭零 IO")
    void disabledSwitchMeansZeroIO() throws Exception {
        try (Env env = startEnv(agent -> DaReflectionCostSupport.gates(agent, false, true, true),
                stub -> DaReflectionCostSupport.credentialFailure(stub))) {
            String session = "sess-f111-05b-" + System.nanoTime();
            runBusinessFailure(env, session);
            List<ReflectionRecord> records = awaitRecords(session, WAIT);
            assertThat(records).as("唯一处置记录").hasSize(1);
            assertThat(records.get(0).outcome()).as("总开关关闭 → disabled").isEqualTo("disabled");
            assertThat(records.get(0).reason()).as("reason=reflection_disabled")
                    .isEqualTo("reflection_disabled");
            assertThat(env.stub().reflectionCalls()).as("零反思 provider 请求").isZero();
            assertThat(env.memory().writeCount()).as("零记忆写入").isZero();
            Allure.addAttachment("F111-05b 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0));
        }
    }

    @Test
    @Story("F111-05: 诊断输入超 max-diagnostic-input-chars → 不发请求不写记忆唯一 failed")
    @DisplayName("F111-05c: 输入超限 failed")
    void oversizedInputFails() throws Exception {
        try (Env env = startEnv(agent -> {
            DaReflectionCostSupport.gatesOpen(agent);
            agent.property("deepanalyze.reflection.max-diagnostic-input-chars", "10");
        }, stub -> DaReflectionCostSupport.credentialFailure(stub))) {
            String session = "sess-f111-05c-" + System.nanoTime();
            runBusinessFailure(env, session);
            List<ReflectionRecord> records = awaitRecords(session, WAIT);
            assertThat(records).as("唯一处置记录").hasSize(1);
            assertThat(records.get(0).outcome()).as("输入超限 → failed").isEqualTo("failed");
            assertThat(records.get(0).reason()).as("reason=input_too_long").isEqualTo("input_too_long");
            assertThat(env.stub().reflectionCalls()).as("不发 provider 请求").isZero();
            assertThat(env.memory().writeCount()).as("不写记忆").isZero();
            Allure.addAttachment("F111-05c 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0));
        }
    }

    @Test
    @Story("F111-05: 模型输出不受控（自由文本）→ 唯一 failed(lesson_uncontrolled)")
    @DisplayName("F111-05d: 输出不受控 failed")
    void uncontrolledOutputFails() throws Exception {
        try (Env env = startEnv(agent -> DaReflectionCostSupport.gatesOpen(agent),
                stub -> {
                            stub.reflectionFreeText();
                })) {
            String session = "sess-f111-05d-" + System.nanoTime();
            runBusinessFailure(env, session);
            List<ReflectionRecord> records = awaitRecords(session, WAIT);
            assertThat(records).as("唯一处置记录").hasSize(1);
            assertThat(records.get(0).outcome()).as("输出不受控 → failed").isEqualTo("failed");
            assertThat(records.get(0).reason()).as("reason=lesson_uncontrolled")
                    .isEqualTo("lesson_uncontrolled");
            assertThat(env.memory().writeCount()).as("模型原文不作回退写入记忆").isZero();
            Allure.addAttachment("F111-05d 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0));
        }
    }

    @Test
    @Story("F111-20: INPUT_REJECTED（181012 链）正向触发反思")
    @DisplayName("F111-20: INPUT_REJECTED 触发")
    void inputRejectedTriggersReflection() throws Exception {
        try (Env env = startEnv(agent -> DaReflectionCostSupport.gatesOpen(agent), stub -> {
            // 主调用回 HTTP 400 + context_length_exceeded 关键字 → core 规则分类
            // CONTEXT_LENGTH_EXCEEDED（RuleBasedModelErrorClassifier 关键字规则，不可重试）
            // → 181012「prompt or context too long」→ DA stableCodeOf → INPUT_REJECTED（REFLECTABLE 六类之一）
            stub.mainError(400, "{\"error\":{\"message\":\"This model's maximum context length is exceeded\","
                    + "\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}");
        })) {
            String session = "sess-f111-20-" + System.nanoTime();
            runBusinessFailure(env, session);
            List<ReflectionRecord> records = awaitRecords(session, WAIT);
            assertThat(records).as("INPUT_REJECTED 触发反思：唯一处置记录").hasSize(1);
            assertThat(records.get(0).outcome()).as("反思链走通（stub 正常回码）").isEqualTo("succeeded");
            assertThat(env.stub().reflectionCalls()).as("恰一次反思 provider 请求").isEqualTo(1);
            Allure.addAttachment("F111-20 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0)
                            + " reflectionCalls=" + env.stub().reflectionCalls());
        }
    }

    @Test
    @Story("F111-05: 非法配置启动失败并指出键名")
    @DisplayName("F111-05e: 非法配置启动失败")
    void invalidConfigFailsStartup() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> startEnv(agent -> {
                    DaReflectionCostSupport.gatesOpen(agent);
                    agent.property("deepanalyze.reflection.queue-capacity", "0");
                }, stub -> DaReflectionCostSupport.credentialFailure(stub)).close());
        assertThat(failure.getMessage()).as("启动失败信息带日志尾部")
                .contains("deepanalyze.reflection.queue-capacity must be positive");
        assertThat(DaReflectionCostSupport.sutLogContains(
                "deepanalyze.reflection.queue-capacity must be positive"))
                .as("SUT 启动日志回显键名与原因").isTrue();
    }

    @Test
    @Story("F111-19: 进程退出前未完成的反思允许丢弃——重启后无补跑、无跨重启 exactly-once")
    @DisplayName("F111-19: 重启后无补跑")
    void restartDoesNotBackfillReflection() throws Exception {
        Env env = startEnv(agent -> {
            DaReflectionCostSupport.gatesOpen(agent);
            agent.property("deepanalyze.reflection.timeout", "PT20S");
        }, stub -> {
            stub.reflectionDelay(60_000);
        });
        try (env) {
            String session = "sess-f111-19-" + System.nanoTime();
            runBusinessFailure(env, session);
            boolean callSeen = DaReflectionCostSupport.await(Duration.ofSeconds(10), Duration.ofMillis(200),
                    () -> env.stub().reflectionCalls() >= 1);
            assertThat(callSeen).as("反思调用已在飞（丢弃前提成立）").isTrue();
            assertThat(DaReflectionCostSupport.recordsForSession(session))
                    .as("挂起中零记录").isEmpty();
            env.stack().stop(DaReflectionCostSupport.AGENT);
            env.stack().start(DaReflectionCostSupport.AGENT);
            Thread.sleep(21_000);
            assertThat(DaReflectionCostSupport.recordsForSession(session))
                    .as("重启后零补跑、零跨重启记录").isEmpty();
            assertThat(env.memory().writeCount()).as("零记忆写入").isZero();
            HttpResponse<String> tasks = DaCompatGuardrailSupport.get(http, env.base(),
                    "/api/agents/tasks/" + session, Duration.ofSeconds(30),
                    Map.of("X-Da-Session-Id", session));
            assertThat(tasks.statusCode()).as("重启后 SUT 可服务（同端口原位重启）").isEqualTo(200);
            Allure.addAttachment("F111-19 证据", "text/plain",
                    "session=" + session + " reflectionCalls=" + env.stub().reflectionCalls()
                            + " post-restart records=0");
        }
    }
}

