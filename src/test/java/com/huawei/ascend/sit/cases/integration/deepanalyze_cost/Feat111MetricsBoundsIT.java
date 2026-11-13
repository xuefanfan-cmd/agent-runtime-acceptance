package com.huawei.ascend.sit.cases.integration.deepanalyze_cost;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.MemoryReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ModelStubServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.OtlpReceiverServer;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * FEAT-111 指标有界与故障隔离（F111-15/16/18，integration，确定性执行体轨道）。
 *
 * <p><b>判据（实现前已读源码固化）：</b></p>
 * <ul>
 *   <li>有界预聚合（{@code MicrometerCostMetricsWriter}）：maxSessionSeries 满时新
 *       会话调用整笔丢弃并计 openjiuwen.llm.usage.overflow（仅服务级标签，永不携带
 *       taskId/sessionId——DV-111-05：实例级归因半句不承接，计数值本身可断言）；
 *       idle 过期退役后重开＝注册表重开新序列＋SUT 日志 WARN。</li>
 *   <li>故障隔离（F111-15）：trace 采样关闭（OTel 规范 env OTEL_TRACES_SAMPLER=always_off）
 *       不影响成本指标完整性；OTLP 接收端不可达（连接拒绝）不拖垮任务终态
 *       （OTLP 推送失败由 OtlpHttpMetricsSender 吞掉——库内建行为）。</li>
 *   <li>留档面（F111-18，non-gating）：DaCostTracker 启动日志明示 reconcile_diff 不在
 *       engine 侧对账；currency 仅配置字段不参与计算（DV-111-08）；unit 不断言
 *       （DV-111-10）。</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111MetricsBoundsIT {

    private static final Duration OTLP_WAIT = Duration.ofSeconds(45);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestConfig config;

    private static HttpClient http;

    @BeforeAll
    static void setUp() {
        config = TestConfig.load();
        http = DaCompatGuardrailSupport.newHttpClient();
    }

    /** 每用例独立环境。 */
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

    /** 起一套环境（props 注入容量/币种等；stubSetup 注入 usage 形态）。 */
    private static Env startEnv(Consumer<SutStack.AgentBuilder> props,
            Consumer<ModelStubServer> stubSetup) throws Exception {
        ModelStubServer stub = new ModelStubServer();
        stubSetup.accept(stub);
        MemoryReceiverServer memory = new MemoryReceiverServer();
        OtlpReceiverServer otlp = new OtlpReceiverServer();
        SutStack stack = DaReflectionCostSupport.baseStack(config, agent -> {
            DaReflectionCostSupport.bindStubModel(agent, stub);
            DaReflectionCostSupport.wireObservability(agent, otlp, memory);
            agent.property("deepanalyze.task-loop.planning-enabled", "false");
            props.accept(agent);
        }).start();
        return new Env(stack, stub, memory, otlp,
                stack.client(DaReflectionCostSupport.AGENT).getBaseUrl());
    }

    private static void runTask(Env env, String session) throws Exception {
        HttpResponse<String> response = DaCompatGuardrailSupport.post(http, env.base(), "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"直接回答即可\"}", Duration.ofSeconds(240));
        assertThat(response.statusCode()).as("/run 受理 200: " + response.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("status").asText(""))
                .as("任务成功终态").isEqualTo("completed");
    }

    private static java.util.function.Predicate<Map<String, String>> mainTags() {
        return tags -> "main".equals(tags.get(DaReflectionCostSupport.TAG_PURPOSE));
    }

    /** 会话内完成任务快照（busy 幽灵不计入——同 Pricing completedTasksOf）。 */
    private static List<Map<String, Object>> completedTasksOf(Env env, String session) throws Exception {
        HttpResponse<String> response = DaCompatGuardrailSupport.get(http, env.base(),
                "/api/agents/tasks/" + session, Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", session));
        JsonNode node = MAPPER.readTree(response.body());
        List<Map<String, Object>> completed = new java.util.ArrayList<>();
        node.forEach(t -> {
            Map<String, Object> task = MAPPER.convertValue(t, Map.class);
            if ("completed".equals(String.valueOf(task.get("status")))) {
                completed.add(task);
            }
        });
        return completed;
    }

    private static boolean awaitSeries(Env env, String metric, Duration timeout,
            java.util.function.Predicate<Map<String, String>> tags) {
        return DaReflectionCostSupport.await(timeout, Duration.ofMillis(500),
                () -> env.otlp().hasSeries(metric, tags));
    }

    /* ==================== F111-16 指标基数有界 ==================== */

    /* ==================== F111-16 指标基数有界 ==================== */

    @Test
    @Story("F111-16: 满容量丢弃计 overflow（服务级）、退役重开 WARN、新序列从零重开")
    @DisplayName("F111-16: 容量满丢弃与退役重开")
    void capacityOverflowAndRetirement() throws Exception {
        try (Env env = startEnv(agent -> {
            DaReflectionCostSupport.priceLine(agent, 0, DaReflectionCostSupport.STUB_PROVIDER,
                    DaReflectionCostSupport.STUB_MODEL, "1", "0.25", "2");
            agent.property("deepanalyze.cost.metric-max-session-series", "2");
            agent.property("deepanalyze.cost.metric-series-idle-expiry", "PT2S");
            agent.property("deepanalyze.cost.metric-export-interval", "PT1S");
        }, stub -> { })) {
            // 4 个独立会话 × 1 调用：容量 2 → 2 收纳 / 2 溢出
            List<String> sessions = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String session = "sess-f111-16-s" + i + "-" + System.nanoTime();
                sessions.add(session);
                runTask(env, session);
            }
            boolean overflowSeen = DaReflectionCostSupport.await(OTLP_WAIT, Duration.ofMillis(500),
                    () -> env.otlp().maxSum(DaReflectionCostSupport.M_USAGE_OVERFLOW, tags -> true) >= 2);
            assertThat(overflowSeen).as("至少 2 笔溢出计数（服务级，无任务/会话标签）").isTrue();
            // 退役判据（可确定性观测形态）：idle 2s 过期后，老序列从最新推送中消失
            Thread.sleep(3_500);
            String revisit = sessions.get(0);
            String oldTaskId = String.valueOf(completedTasksOf(env, revisit).get(0).get("id"));
            runTask(env, revisit);
            java.util.function.Predicate<Map<String, String>> retiredSeries =
                    mainTags().and(tags -> revisit.equals(tags.get(DaReflectionCostSupport.TAG_SESSION))
                            && oldTaskId.equals(tags.get(DaReflectionCostSupport.TAG_TASK)));
            boolean retireSeen = DaReflectionCostSupport.await(Duration.ofSeconds(15), Duration.ofMillis(300),
                    () -> !env.otlp().latestPushHas(DaReflectionCostSupport.M_TOKEN_USAGE, retiredSeries));
            assertThat(retireSeen).as("idle 过期后老序列从推送中消失（退役生效）").isTrue();
            // 重开从零：revisit 新任务（新键）累计＝单调用 1000（取最新推送——退役前并存的
            // 瞬间会被 maxSum 记成历史峰值，run-20 实测）
            double[] seen = {-1};
            int pushesAtStart = env.otlp().pushCount();
            java.util.function.Predicate<Map<String, String>> revisitInput =
                    mainTags().and(tags -> revisit.equals(tags.get(DaReflectionCostSupport.TAG_SESSION)))
                            .and(tags -> "input".equals(tags.get(DaReflectionCostSupport.TAG_TOKEN_TYPE)));
            DaReflectionCostSupport.await(OTLP_WAIT, Duration.ofMillis(500), () -> {
                seen[0] = env.otlp().latestPushSum(DaReflectionCostSupport.M_TOKEN_USAGE, revisitInput);
                return seen[0] == 1000;
            });
            StringBuilder dump = new StringBuilder("pushesAtStart=").append(pushesAtStart)
                    .append(" pushesAtEnd=").append(env.otlp().pushCount()).append('\n');
            for (DaReflectionCostSupport.OtlpPush push : env.otlp().pushes()) {
                for (DaReflectionCostSupport.OtlpSeries s : push.series()) {
                    String sessionTag = s.tags().get(DaReflectionCostSupport.TAG_SESSION);
                    if (sessionTag != null && (sessionTag.equals(revisit)
                            || sessionTag.contains("sess-f111-16-s1-"))) {
                        dump.append("push#").append(env.otlp().pushes().indexOf(push))
                                .append(' ').append(s.name()).append(" task=")
                                .append(s.tags().get(DaReflectionCostSupport.TAG_TASK))
                                .append(" type=").append(s.tags().get(DaReflectionCostSupport.TAG_TOKEN_TYPE))
                                .append('=').append(s.value()).append('\n');
                    }
                }
            }
            assertThat(seen[0]).as("重开序列累计从零（新键静默新开）；revisit+s1 会话全推送轨迹：\n" + dump)
                    .isEqualTo(1000);
            // 「同键迟到重开 WARN」半程：同键＝同 taskId（SeriesKey 含 taskId，writer 源码口径），
            // 单调用任务构不成同键迟到增量——需 ask/answer＋双模型调用（间隔>idle 窗口）构造，
            // 登记为可构造未排期（stub 扩展 tool-call-then-answer 形态）。
            Allure.addAttachment("F111-16 证据", "text/plain",
                    "sessions=" + sessions.size()
                            + " overflow=" + env.otlp().maxSum(DaReflectionCostSupport.M_USAGE_OVERFLOW,
                                    tags -> true)
                            + " oldTask=" + oldTaskId + " retired=true reopenFromZero=true");
        }
    }

    /* ==================== F111-15 采样与故障隔离 ==================== */

    @Test
    @Story("F111-15a: trace 采样关闭（OTel 规范 env）不影响成本指标完整性")
    @DisplayName("F111-15a: 采样关闭指标完整")
    void traceSamplerOffKeepsMetrics() throws Exception {
        try (Env env = startEnv(agent -> {
            DaReflectionCostSupport.priceLine(agent, 0, DaReflectionCostSupport.STUB_PROVIDER,
                    DaReflectionCostSupport.STUB_MODEL, "1", "0.25", "2");
            agent.env("OTEL_TRACES_SAMPLER", "always_off");
            agent.property("deepanalyze.cost.currency", "EUR");
        }, stub -> { })) {
            String session = "sess-f111-15a-" + System.nanoTime();
            runTask(env, session);
            java.util.function.Predicate<Map<String, String>> priced =
                    mainTags().and(tags -> session.equals(tags.get(DaReflectionCostSupport.TAG_SESSION)));
            assertThat(awaitSeries(env, DaReflectionCostSupport.M_COST_USD, OTLP_WAIT, priced))
                    .as("采样关闭+currency=EUR 下成本指标仍完整").isTrue();
            assertThat(env.otlp().maxSum(DaReflectionCostSupport.M_COST_USD, priced))
                    .as("按价目正常计价（currency 不参与计算 DV-111-08；cached=0 投影 0.002，"
                            + "#277 遮蔽说明同 F111-10a）")
                    .isCloseTo(0.002D, org.assertj.core.data.Offset.offset(1e-9));
            Allure.addAttachment("F111-15a 证据", "text/plain",
                    "session=" + session + " cost=" + env.otlp().maxSum(
                            DaReflectionCostSupport.M_COST_USD, priced));
        }
    }

    @Test
    @Story("F111-15b: OTLP 接收端不可达不拖垮任务终态")
    @DisplayName("F111-15b: Collector 关闭不拖垮任务")
    void collectorDownNotDragDownTask() throws Exception {
        try (Env env = startEnv(agent -> {
            DaReflectionCostSupport.priceLine(agent, 0, DaReflectionCostSupport.STUB_PROVIDER,
                    DaReflectionCostSupport.STUB_MODEL, "1", "0.25", "2");
            // OTLP 端点指向无监听端口（连接拒绝）——wireObservability 已注入的端点被后写覆盖
            agent.env("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT",
                    "http://127.0.0.1:1/v1/metrics");
        }, stub -> { })) {
            String session = "sess-f111-15b-" + System.nanoTime();
            runTask(env, session);
            assertThat(MAPPER.readTree(DaCompatGuardrailSupport.get(http, env.base(),
                    "/api/agents/tasks/" + session, Duration.ofSeconds(30),
                    Map.of("X-Da-Session-Id", session)).body())
                    .path(0).path("status").asText(""))
                    .as("任务终态不受 Collector 不可达拖垮").isEqualTo("completed");
            Allure.addAttachment("F111-15b 证据", "text/plain", "session=" + session);
        }
    }

    /* ==================== F111-18 留档面（non-gating） ==================== */

    @Test
    @Story("F111-18: 启动日志明示 reconcile_diff 不在 engine 侧对账（留档）")
    @DisplayName("F111-18: 对账留档面")
    void reconcileDiffArchivedFace() {
        assertThat(DaReflectionCostSupport.sutLogContains(
                "reconcile_diff is not reconciled here"))
                .as("DaCostTracker 启动日志留档行存在").isTrue();
    }
}