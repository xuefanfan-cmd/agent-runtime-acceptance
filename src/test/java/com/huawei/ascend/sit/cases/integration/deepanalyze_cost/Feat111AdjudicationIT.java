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
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-111 usage 判定顺序与模型身份标记（F111-11/12，integration，确定性执行体轨道）。
 *
 * <p><b>判据（L1 出口 11/12＋§3.3 固定判定序 missing→invalid→unknown_price→priced，
 * {@code UsageAdjudicator} 源码口径；DV-111-06：字段级缺失在成员/反思来源压 0——
 * 本类只在"整个 usage 对象/必需字段缺失"面断言，字段级 partial；DV-111-07：主/反思
 * 走 request_fallback（responseModel 未接入），成员响应模型面待成员拓扑，登记不承接）：</b></p>
 * <ul>
 *   <li>missing＝usage 对象/必需 token 字段缺席 → usage.missing 计数，无 token/成本；</li>
 *   <li>invalid＝负数或 cached&gt;input → usage.invalid 计数，token 与成本永不报告；</li>
 *   <li>unknown_price＝有效 usage 但无适用价目（provider/model 对缺席）→
 *       cost.unknown_price 计数＋price_profile=unknown，仍报有效 token；</li>
 *   <li>字段在场的真实 0 → 不误报 missing/invalid（真实 0 成本不产生增量）；</li>
 *   <li>模型身份＝gen_ai.response.model=请求快照模型＋
 *       openjiuwen.llm.model_identity_source=request_fallback。</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111AdjudicationIT {

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

    /** 起一套判定环境（extraProps 注入价目形态；stubSetup 注入 usage 形态）。 */
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

    private static void runTask(Env env, String session) throws Exception {
        HttpResponse<String> response = DaCompatGuardrailSupport.post(http, env.base(), "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"直接回答即可\"}", Duration.ofSeconds(240));
        assertThat(response.statusCode()).as("/run 受理 200: " + response.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("status").asText(""))
                .as("任务成功终态").isEqualTo("completed");
    }

    private static java.util.function.Predicate<Map<String, String>> sessionTags(String session) {
        return tags -> session.equals(tags.get(DaReflectionCostSupport.TAG_SESSION))
                && "main".equals(tags.get(DaReflectionCostSupport.TAG_PURPOSE));
    }

    private static boolean awaitSeries(Env env, String metric, Duration timeout,
            java.util.function.Predicate<Map<String, String>> tags) {
        return DaReflectionCostSupport.await(timeout, Duration.ofMillis(500),
                () -> env.otlp().hasSeries(metric, tags));
    }

    /** 有界等待累计值到位（＝expected）后返回终值（防多序列落地竞态，同 Pricing awaitSum）。 */
    private static double awaitSum(Env env, String metric, Duration timeout,
            java.util.function.Predicate<Map<String, String>> tags, double expected) {
        DaReflectionCostSupport.await(timeout, Duration.ofMillis(500),
                () -> Math.abs(env.otlp().maxSum(metric, tags) - expected) <= Math.max(1e-9, expected * 1e-9));
        return env.otlp().maxSum(metric, tags);
    }

    /* ==================== F111-12 判定顺序 ==================== */

    @Test
    @Story("F111-12: 缺 usage 必需字段 → usage.missing 计数（blocked：core 归一化使缺席形态不可达）")
    @DisplayName("F111-12a: missing 格（core 归一化遮蔽，INCONCLUSIVE 留恢复条件）")
    void missingUsageBlockedByCoreNormalization() {
        org.junit.jupiter.api.Assumptions.abort("core 流式解析把缺席 usage 字段归一为 0"
                + "（OpenAIModelClient.intValue(null)→0 :2772-2778）→ tracker 判定面收不到"
                + "缺席形态，γ2-M3 分支在流式主路径无触发前提（run-18 实证 45s 无计数）。"
                + "恢复条件＝core 透传字段在场性（#277 关联）或成员/反思来源补证。"
                + "DV-111-06 口径：通用判定器逻辑属单元层，不在黑盒范围。");
    }

    @Test
    @Story("F111-12: 负数 input → usage.invalid 计数，token 与成本永不报告")
    @DisplayName("F111-12b: invalid 负数格")
    void negativeInputCountsInvalid() throws Exception {
        try (Env env = startEnv(agent -> DaReflectionCostSupport.priceLine(agent, 0,
                DaReflectionCostSupport.STUB_PROVIDER, DaReflectionCostSupport.STUB_MODEL,
                "1", "0.25", "2"), stub -> stub.usageMode(DaReflectionCostSupport.UsageMode.NEGATIVE_INPUT))) {
            String session = "sess-f111-12b-" + System.nanoTime();
            runTask(env, session);
            assertThat(awaitSeries(env, DaReflectionCostSupport.M_USAGE_INVALID, OTLP_WAIT,
                    sessionTags(session))).as("usage.invalid 计数出现").isTrue();
            assertThat(env.otlp().maxSum(DaReflectionCostSupport.M_USAGE_INVALID, sessionTags(session)))
                    .as("恰好一笔 invalid").isEqualTo(1D);
            assertThat(env.otlp().hasSeries(DaReflectionCostSupport.M_TOKEN_USAGE, sessionTags(session)))
                    .as("invalid 格无 token 序列").isFalse();
            Allure.addAttachment("F111-12b 证据", "text/plain", "session=" + session);
        }
    }

    @Test
    @Story("F111-12: cached>input → invalid（blocked·#277：流式路径 cached 恒被丢弃，前提不可达）")
    @DisplayName("F111-12c: cached>input 格（#277 遮蔽，INCONCLUSIVE 留恢复条件）")
    void cachedOverInputBlockedBy277() {
        org.junit.jupiter.api.Assumptions.abort("core #277 前置未交付：流式路径 parseUsageMetadata"
                + "(includeCacheTokens=false) 丢弃 prompt_tokens_details，cached 在场前提不可达"
                + "（run-17 源码级实证）。恢复条件＝#277 交付后断言 usage.invalid 计数与"
                + "cached>input 判定序。");
    }

    @Test
    @Story("F111-12: 字段在场的真实 0 不误报 missing/invalid")
    @DisplayName("F111-12d: 真实 0 格")
    void realZeroNotMisreported() throws Exception {
        try (Env env = startEnv(agent -> DaReflectionCostSupport.priceLine(agent, 0,
                DaReflectionCostSupport.STUB_PROVIDER, DaReflectionCostSupport.STUB_MODEL,
                "1", "0.25", "2"), stub -> stub.usageMode(DaReflectionCostSupport.UsageMode.ALL_ZERO))) {
            String session = "sess-f111-12d-" + System.nanoTime();
            runTask(env, session);
            boolean anyVerdict = DaReflectionCostSupport.await(OTLP_WAIT, Duration.ofMillis(500), () ->
                    env.otlp().hasSeries(DaReflectionCostSupport.M_USAGE_MISSING, sessionTags(session))
                            || env.otlp().hasSeries(DaReflectionCostSupport.M_USAGE_INVALID, sessionTags(session))
                            || env.otlp().hasSeries(DaReflectionCostSupport.M_UNKNOWN_PRICE,
                                    sessionTags(session)));
            assertThat(anyVerdict).as("真实 0 不误报 missing/invalid/unknown_price").isFalse();
            assertThat(env.otlp().hasSeries(DaReflectionCostSupport.M_COST_USD, sessionTags(session)))
                    .as("真实 0 成本无导出增量（零增量不产生空 meter）").isFalse();
            Allure.addAttachment("F111-12d 证据", "text/plain", "session=" + session);
        }
    }

    @Test
    @Story("F111-12: 无适用价目 → unknown_price 计数＋price_profile=unknown，仍报有效 token")
    @DisplayName("F111-12e: unknown_price 格")
    void unpricedModelCountsUnknownPrice() throws Exception {
        try (Env env = startEnv(agent -> DaReflectionCostSupport.priceLine(agent, 0,
                DaReflectionCostSupport.STUB_PROVIDER, "other-model",
                "1", "0.25", "2"), stub -> { })) {
            String session = "sess-f111-12e-" + System.nanoTime();
            runTask(env, session);
            assertThat(awaitSeries(env, DaReflectionCostSupport.M_UNKNOWN_PRICE, OTLP_WAIT,
                    sessionTags(session))).as("unknown_price 计数出现").isTrue();
            assertThat(env.otlp().maxSum(DaReflectionCostSupport.M_UNKNOWN_PRICE, sessionTags(session)))
                    .as("恰好一笔 unknown_price").isEqualTo(1D);
            java.util.function.Predicate<Map<String, String>> unknown =
                    sessionTags(session).and(tags ->
                            "unknown".equals(tags.get(DaReflectionCostSupport.TAG_PRICE_PROFILE)));
            assertThat(env.otlp().hasSeries(DaReflectionCostSupport.M_TOKEN_USAGE, unknown))
                    .as("unknown_price 格仍报有效 token（profile=unknown）").isTrue();
            assertThat(env.otlp().hasSeries(DaReflectionCostSupport.M_COST_USD, sessionTags(session)))
                    .as("无伪造成本").isFalse();
            Allure.addAttachment("F111-12e 证据", "text/plain", "session=" + session);
        }
    }

    /* ==================== F111-11 模型身份标记（DV-111-07 分栏） ==================== */

    @Test
    @Story("F111-11: 主/反思走 request_fallback，价格按请求快照模型匹配（成员响应模型面待拓扑）")
    @DisplayName("F111-11: fallback 标记与按请求模型计价")
    void fallbackIdentityAndRequestModelPricing() throws Exception {
        try (Env env = startEnv(agent -> DaReflectionCostSupport.priceLine(agent, 0,
                DaReflectionCostSupport.STUB_PROVIDER, DaReflectionCostSupport.STUB_MODEL,
                "1", "0.25", "2"), stub -> { })) {
            String session = "sess-f111-11-" + System.nanoTime();
            runTask(env, session);
            java.util.function.Predicate<Map<String, String>> fallback =
                    sessionTags(session).and(tags ->
                            "request_fallback".equals(tags.get(DaReflectionCostSupport.TAG_MODEL_SOURCE))
                                    && DaReflectionCostSupport.STUB_MODEL
                                            .equals(tags.get(DaReflectionCostSupport.TAG_MODEL))
                                    && DaReflectionCostSupport.STUB_PROVIDER
                                            .equals(tags.get(DaReflectionCostSupport.TAG_PROVIDER)));
            assertThat(awaitSeries(env, DaReflectionCostSupport.M_TOKEN_USAGE, OTLP_WAIT, fallback))
                    .as("主调用序列带 request_fallback 标记＋请求模型身份").isTrue();
            double cost = awaitSum(env, DaReflectionCostSupport.M_COST_USD, OTLP_WAIT, fallback, 0.002);
            assertThat(cost).as("按请求快照模型匹配价目计价（cached=0 投影，#277 遮蔽说明同 F111-10a）")
                    .isCloseTo(0.002D, org.assertj.core.data.Offset.offset(1e-9));
            Allure.addAttachment("F111-11 证据", "text/plain",
                    "session=" + session + " cost=" + cost);
        }
    }
}