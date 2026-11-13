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
import static org.assertj.core.api.Assertions.within;

/**
 * FEAT-111 缓存计价公式精确值（F111-10/14，integration，确定性执行体轨道）。
 *
 * <p><b>判据（L1 出口 10/14＋§3.3 公式；Q2 双轨已批，Q4 cached_input 流式来源待 #277
 * ——本类走非流式确定性来源，公式断言有效，不声明"真实流式缓存"通过）：</b></p>
 * <ul>
 *   <li>公式＝(input−cached)×p_in + cached×p_cached + output×p_out（UsageAdjudicator
 *       BigDecimal 精确）；usage 1000/200/500 × 价 1/0.25/2 → 0.00185/call。</li>
 *   <li>token 三分项（input/output/cached_input）各自独立计数，total 不重复计费。</li>
 *   <li>十进制容差 max(1e-12, abs×1e-9)；单调用微额不舍入为 0（DoubleCounter 累计）。</li>
 * </ul>
 *
 * <p><b>构造：</b>stub 每调用 usage 恒 1000/200/500；期望值按 stub 观测调用数 N 推导
 * （公式对任意 N 精确成立），并断言 N==4（2 会话×2 任务，planning 关闭锚定每任务
 * 1 次主调用）。OTLP 推送间隔 1s（env OTEL_METRIC_EXPORT_INTERVAL）＋writer 冲刷
 * PT1S，有界轮询取数。</p>
 */
@Tag("integration")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111CostPricingIT {

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

    /** 价目（USD/百万 token 十进制串；cached 传 null＝无缓存价）。 */
    private static Consumer<SutStack.AgentBuilder> priceLine(String in, String cached, String out) {
        return agent -> DaReflectionCostSupport.priceLine(agent, 0,
                DaReflectionCostSupport.STUB_PROVIDER, DaReflectionCostSupport.STUB_MODEL,
                in, cached, out);
    }

    /** 起一套计价环境（反射门保持缺省关；planning 关闭锚定每任务 1 次主调用）。 */
    private static Env startEnv(Consumer<SutStack.AgentBuilder> extraProps,
            Consumer<ModelStubServer> stubSetup) throws Exception {
        ModelStubServer stub = new ModelStubServer();
        stubSetup.accept(stub);
        MemoryReceiverServer memory = new MemoryReceiverServer();
        OtlpReceiverServer otlp = new OtlpReceiverServer();
        SutStack stack = DaReflectionCostSupport.baseStack(config, agent -> {
            DaReflectionCostSupport.bindStubModel(agent, stub);
            DaReflectionCostSupport.wireObservability(agent, otlp, memory);
            agent.property("deepanalyze.task-loop.planning-enabled", "false");
            extraProps.accept(agent);
        }).start();
        return new Env(stack, stub, memory, otlp,
                stack.client(DaReflectionCostSupport.AGENT).getBaseUrl());
    }

    /**
     * 真实上游环境（双轨之第二轨）：不绑 stub——模型走 LLM_* 真实网关；价目行按
     * 真实 provider/model 动态构建。用途＝usage 真实性验证（非确定性数值面）。
     */
    private static Env startRealUpstreamEnv(Consumer<SutStack.AgentBuilder> extraProps) throws Exception {
        ModelStubServer stub = new ModelStubServer();
        MemoryReceiverServer memory = new MemoryReceiverServer();
        OtlpReceiverServer otlp = new OtlpReceiverServer();
        SutStack stack = DaReflectionCostSupport.baseStack(config, agent -> {
            DaReflectionCostSupport.bindModelEnvironment(agent);
            DaReflectionCostSupport.wireObservability(agent, otlp, memory);
            agent.property("deepanalyze.task-loop.planning-enabled", "false");
            extraProps.accept(agent);
        }).start();
        return new Env(stack, stub, memory, otlp,
                stack.client(DaReflectionCostSupport.AGENT).getBaseUrl());
    }

    /** 跑一个成功任务（stub 普通回答）。同会话连发时 busy 守卫释放晚于 /run 响应，存在两种
     * 时序形态：HTTP 409（准入拒收）或 200/failed＋error 含 busy（准入后任务体失败——
     * 幽灵任务的模型调用不计入指标）。两种形态都有界退避重试（≤5 次×500ms），
     * accommodation 记入证据。 */
    private static void runTask(Env env, String session) throws Exception {
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            HttpResponse<String> response = DaCompatGuardrailSupport.post(http, env.base(), "/api/agents/run",
                    "{\"sessionId\":\"" + session + "\",\"input\":\"直接回答即可\"}", Duration.ofSeconds(240));
            JsonNode node = MAPPER.readTree(response.body());
            String status = node.path("status").asText("");
            String error = node.path("error").asText("");
            boolean busyShaped = response.statusCode() == 409
                    || (error.toLowerCase().contains("busy") && !"completed".equals(status));
            if (busyShaped && attempt < 5) {
                last = new IllegalStateException("busy 窗口重试第 " + attempt + " 次: HTTP "
                        + response.statusCode() + " " + response.body());
                Thread.sleep(500);
                continue;
            }
            assertThat(response.statusCode()).as("/run 受理: " + response.body()).isEqualTo(200);
            assertThat(status).as("任务成功终态: " + response.body()).isEqualTo("completed");
            return;
        }
        throw last == null ? new IllegalStateException("runTask 重试耗尽") : last;
    }

    /** 会话内完成任务快照（过滤幽灵任务——busy 体失败的任务不参与计价断言）。 */
    private static List<Map<String, Object>> completedTasksOf(Env env, String session) throws Exception {
        List<Map<String, Object>> tasks = tasksOf(env, session);
        List<Map<String, Object>> completed = new java.util.ArrayList<>();
        for (Map<String, Object> task : tasks) {
            if ("completed".equals(String.valueOf(task.get("status")))) {
                completed.add(task);
            }
        }
        return completed;
    }

    private static List<Map<String, Object>> tasksOf(Env env, String session) throws Exception {
        HttpResponse<String> response = DaCompatGuardrailSupport.get(http, env.base(),
                "/api/agents/tasks/" + session, Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", session));
        JsonNode node = MAPPER.readTree(response.body());
        List<Map<String, Object>> tasks = new java.util.ArrayList<>();
        node.forEach(t -> tasks.add(MAPPER.convertValue(t, Map.class)));
        return tasks;
    }

    /** 有界等待 OTLP 出现满足条件的序列。 */
    private static boolean awaitSeries(Env env, Duration timeout, java.util.function.Predicate<Map<String,
            String>> tags) {
        return DaReflectionCostSupport.await(timeout, Duration.ofMillis(500),
                () -> env.otlp().hasSeries(DaReflectionCostSupport.M_TOKEN_USAGE, tags));
    }

    /**
     * 有界等待某指标的匹配累计值到位（＝expected）后返回终值。
     * writer 冲刷与 OTLP 推送各 1s，多任务聚合断言必须等全部序列落地——
     * 只等首个序列出现会读到不完整快照（run-16 实测竞态）。
     */
    private static double awaitSum(Env env, String metric, Duration timeout,
            java.util.function.Predicate<Map<String, String>> tags, double expected) {
        DaReflectionCostSupport.await(timeout, Duration.ofMillis(500),
                () -> Math.abs(env.otlp().maxSum(metric, tags) - expected) <= Math.max(1e-9, expected * 1e-9));
        return env.otlp().maxSum(metric, tags);
    }

    /* ==================== F111-10 缓存公式精确值 ==================== */

    /**
     * <b>#277 遮蔽说明（run-17 源码级实证，遮蔽律口径）：</b>core OpenAIModelClient
     * 流式路径 parseUsageMetadata(includeCacheTokens=false)（:1418/:2482）无条件丢弃
     * prompt_tokens_details → cached_input 恒 0 → L1 出口 10 的缓存半程（cached_input
     * 分项、cached 价贡献、0.00185/0.00370/0.00740 全式）在本制品上不可验，恢复条件
     * ＝core #277。无缓存半程（input/output 分项与缓存无关算术）照常执行。
     */
    @Test
    @Story("F111-10: 无缓存半程——input/output 分项与缓存无关算术精确")
    @DisplayName("F111-10a: 无缓存半程精确值")
    void uncachedFormulaPartExact() throws Exception {
        try (Env env = startEnv(priceLine("1", "0.25", "2"), stub -> { })) {
            String s1 = "sess-f111-10-a-" + System.nanoTime();
            String s2 = "sess-f111-10-b-" + System.nanoTime();
            runTask(env, s1);
            runTask(env, s1);
            runTask(env, s2);
            runTask(env, s2);
            List<Map<String, Object>> completed = new java.util.ArrayList<>();
            completed.addAll(completedTasksOf(env, s1));
            completed.addAll(completedTasksOf(env, s2));
            assertThat(completed).as("恰好 4 个完成任务（busy 幽灵任务不计入断言）").hasSize(4);
            java.util.function.Predicate<Map<String, String>> main =
                    tags -> "main".equals(tags.get(DaReflectionCostSupport.TAG_PURPOSE));
            // 本制品上 cached 被 #277 丢弃 → 计价算术输入为 input=1000/output=500（无缓存贡献）
            // → 单调用成本 = 1000×1/1e6 + 500×2/1e6 = 0.002（不是 L1 全式 0.00185——全式待 #277）
            double inputTokens = awaitSum(env, DaReflectionCostSupport.M_TOKEN_USAGE, OTLP_WAIT,
                    main.and(tags -> "input".equals(tags.get(DaReflectionCostSupport.TAG_TOKEN_TYPE))), 4000);
            double outputTokens = awaitSum(env, DaReflectionCostSupport.M_TOKEN_USAGE, OTLP_WAIT,
                    main.and(tags -> "output".equals(tags.get(DaReflectionCostSupport.TAG_TOKEN_TYPE))), 2000);
            double cost = awaitSum(env, DaReflectionCostSupport.M_COST_USD, OTLP_WAIT, main, 4 * 0.002);
            assertThat(inputTokens).as("token 分项 input=完成任务数×1000").isEqualTo(4000D);
            assertThat(outputTokens).as("token 分项 output=完成任务数×500").isEqualTo(2000D);
            assertThat(cost).as("成本=4×0.002（cached=0 投影，#277 遮蔽下的缓存无关算术）")
                    .isCloseTo(4 * 0.002D, within(1e-9));
            double session1Cost = awaitSum(env, DaReflectionCostSupport.M_COST_USD, OTLP_WAIT,
                    main.and(tags -> s1.equals(tags.get(DaReflectionCostSupport.TAG_SESSION))),
                    2 * 0.002);
            assertThat(session1Cost).as("会话聚合=2×0.002")
                    .isCloseTo(2 * 0.002D, within(1e-9));
            for (Map<String, Object> task : completed) {
                String taskId = String.valueOf(task.get("id"));
                double taskCost = awaitSum(env, DaReflectionCostSupport.M_COST_USD, OTLP_WAIT,
                        main.and(tags -> taskId.equals(tags.get(DaReflectionCostSupport.TAG_TASK))),
                        0.002);
                assertThat(taskCost).as("任务序列成本 taskId=" + taskId)
                        .isCloseTo(0.002D, within(1e-9));
            }
            Allure.addAttachment("F111-10a 证据", "text/plain",
                    "completed=4 cost=" + cost + " input=" + inputTokens + " output=" + outputTokens
                            + " stubCalls=" + env.stub().mainCalls());
        }
    }

    @Test
    @Story("F111-10: 缓存半程——cached_input 分项＋cached 价贡献＋0.00185 全式（blocked·#277）")
    @DisplayName("F111-10b: 缓存半程（#277 遮蔽，INCONCLUSIVE 留恢复条件）")
    void cachedFormulaPartBlockedBy277() {
        org.junit.jupiter.api.Assumptions.abort("core #277 前置未交付：流式路径 "
                + "OpenAIModelClient.parseUsageMetadata(includeCacheTokens=false) 丢弃 "
                + "prompt_tokens_details，cached_input 恒 0（run-17 源码级实证）。恢复条件＝#277 交付后"
                + "按 L1 出口 10 全式断言 cached_input=N×200 与 0.00185/0.00370/0.00740——"
                + "不把 cache=0 当通过（L2 §7.2）。");
    }

    /* ==================== F111-14 十进制边界 ==================== */

    @Test
    @Story("F111-14: 微额价目累计在容差内精确、不出现单调用舍入为 0（无缓存价目——Oracle 不依赖缓存维度）")
    @DisplayName("F111-14: 微额价目不归零")
    void microPriceNeverRoundsToZero() throws Exception {
        try (Env env = startEnv(priceLine("0.000001", null, "0.000002"), stub -> { })) {
            String session = "sess-f111-14-" + System.nanoTime();
            runTask(env, session);
            runTask(env, session);
            int completedCount = completedTasksOf(env, session).size();
            assertThat(completedCount).as("恰好 2 个完成任务（busy 幽灵不计入）").isEqualTo(2);
            java.util.function.Predicate<Map<String, String>> main =
                    tags -> "main".equals(tags.get(DaReflectionCostSupport.TAG_PURPOSE));
            double cost = awaitSum(env, DaReflectionCostSupport.M_COST_USD, OTLP_WAIT, main,
                    completedCount * 2e-9);
            double expected = completedCount * 2e-9;
            assertThat(cost).as("微额累计精确（容差 max(1e-12, abs×1e-9)）且大于 0——无缓存价目下单调用"
                            + "=1000×1e-12+500×2e-12=2e-9")
                    .isGreaterThan(0D)
                    .isCloseTo(expected, within(Math.max(1e-12, expected * 1e-9)));
            Allure.addAttachment("F111-14 证据", "text/plain",
                    "completed=" + completedCount + " cost=" + cost + " expected=" + expected
                            + " stubCalls=" + env.stub().mainCalls());
        }
    }

    /* ==================== F111-10 双轨·真实上游轨（usage 真实性验证） ==================== */

    /**
     * 双轨第二轨（用户 2026-11-09 批准口径）：真实网关模型调用 → usage 非确定性，
     * 验证真实性而非精确数值——① token 三分项非零且来自真实网关响应；② 价目按
     * 真实 provider/model 匹配（unknown_price 不命中）；③ 成本＝公式作用于「实测
     * token 值」精确成立（链路对真实数据无损）。cached 维度 #277 遮蔽同 10a。
     */
    @Test
    @Story("F111-10: 真实上游轨——usage 真实性＋公式对实测值精确成立")
    @DisplayName("F111-10c: 真实上游 usage 真实性")
    void realUpstreamUsageAuthenticity() throws Exception {
        String provider = System.getenv("LLM_PROVIDER");
        String model = System.getenv("LLM_MODEL");
        assertThat(provider).as("LLM_PROVIDER（~/.llmrc，runner 受控加载）").isNotBlank();
        assertThat(model).as("LLM_MODEL").isNotBlank();
        Consumer<SutStack.AgentBuilder> price = agent -> DaReflectionCostSupport.priceLine(agent, 0,
                provider, model, "1", "0.25", "2");
        try (Env env = startRealUpstreamEnv(price)) {
            String session = "sess-f111-10c-" + System.nanoTime();
            runTask(env, session);
            int completedCount = completedTasksOf(env, session).size();
            assertThat(completedCount).as("恰好 1 个完成任务").isEqualTo(1);
            java.util.function.Predicate<Map<String, String>> main =
                    tags -> "main".equals(tags.get(DaReflectionCostSupport.TAG_PURPOSE))
                            && session.equals(tags.get(DaReflectionCostSupport.TAG_SESSION));
            assertThat(awaitSeries(env, OTLP_WAIT, main))
                    .as("OTLP 出现真实主链 token 序列").isTrue();
            double inputTokens = env.otlp().maxSum(DaReflectionCostSupport.M_TOKEN_USAGE,
                    main.and(tags -> "input".equals(tags.get(DaReflectionCostSupport.TAG_TOKEN_TYPE))));
            double outputTokens = env.otlp().maxSum(DaReflectionCostSupport.M_TOKEN_USAGE,
                    main.and(tags -> "output".equals(tags.get(DaReflectionCostSupport.TAG_TOKEN_TYPE))));
            // 真实性：token 非零（真实网关响应）
            assertThat(inputTokens).as("真实 input token > 0").isGreaterThan(0D);
            assertThat(outputTokens).as("真实 output token > 0").isGreaterThan(0D);
            // 价目按真实 provider/model 匹配
            assertThat(env.otlp().hasSeries(DaReflectionCostSupport.M_UNKNOWN_PRICE, main))
                    .as("真实模型价目匹配（unknown_price 不命中）").isFalse();
            // 公式对实测 token 值精确成立（cached 维度 #277 遮蔽同 10a：cost=(in)×1/1e6+(out)×2/1e6）
            double cost = awaitSum(env, DaReflectionCostSupport.M_COST_USD, OTLP_WAIT, main,
                    (inputTokens + 2 * outputTokens) / 1e6);
            assertThat(cost).as("成本＝公式作用于实测 token 值（真实性链路无损）")
                    .isCloseTo((inputTokens + 2 * outputTokens) / 1e6, within(1e-9));
            Allure.addAttachment("F111-10c 证据", "text/plain",
                    "provider=" + provider + " model=" + model + " input=" + inputTokens
                            + " output=" + outputTokens + " cost=" + cost);
        }
    }
}