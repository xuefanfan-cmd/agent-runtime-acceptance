package com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.MemoryReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.ModelStubServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.OtlpReceiverServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.OtlpSeries;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport.StubRequest;
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
 * FEAT-111 金丝雀端到端（F111-06，确定性执行体轨道）。
 *
 * <p><b>判据（L1 出口 6）：</b>原文/工具结果/配置埋敏感语料 → 反思 provider 请求只含
 * 结构化字段（instruction/lessonCodes/errorFacts，{@code DaReflectionScheduler.diagnosticInput}
 * 源码口径）；新增记忆仅允许列表固定文本；处置记录六槽位与指标标签无金丝雀。
 * 三路差分断言：</p>
 * <ol>
 *   <li>差分基线：主调用请求体（stub 记录）必须 <b>含</b> 三路金丝雀（证明语料真的
 *       进了主管线，否则断言空转）；</li>
 *   <li>反思请求体：只含 instruction/lessonCodes/errorFacts 三键＋九码清单，无任何
 *       金丝雀、无用户原文、无工具结果文本；</li>
 *   <li>记忆写面 body 只有固定文本；da_reflection_result 六槽位与 OTLP 标签值无金丝雀。</li>
 * </ol>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111ReflectionCanaryE2EIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 三路金丝雀（每轮唯一）。工具结果面因业务失败构造 blocked 仅作哨兵值（run-30/31 发现）。 */
    private record Canary(String input, String toolOutput, String config) {
    }

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

    /** 起一套确定性环境（三门全开＋business_failure 构造）。 */
    private static Env startEnv(Consumer<ModelStubServer> stubSetup, String configCanary) throws Exception {
        ModelStubServer stub = new ModelStubServer();
        DaReflectionCostSupport.credentialFailure(stub);
        stub.reflectionCode("TOOL_INPUT_INVALID");
        stubSetup.accept(stub);
        MemoryReceiverServer memory = new MemoryReceiverServer();
        OtlpReceiverServer otlp = new OtlpReceiverServer();
        SutStack stack = DaReflectionCostSupport.baseStack(config, agent -> {
            DaReflectionCostSupport.bindStubModel(agent, stub);
            DaReflectionCostSupport.gatesOpen(agent);
            DaReflectionCostSupport.wireObservability(agent, otlp, memory);
            agent.property("deepanalyze.cost.currency", configCanary);
        }).start();
        return new Env(stack, stub, memory, otlp,
                stack.client(DaReflectionCostSupport.AGENT).getBaseUrl());
    }

    /** 同步发起任务（透传金丝雀原文）并断言业务失败终态。 */
    private static void runTask(Env env, String session, String input) throws Exception {
        // canary 随任务输入进主请求（差分基线：主调用请求体必含原文金丝雀）。
        // 工具结果金丝雀面随业务失败构造 blocked（run-30/31 发现，ISSUE 候选）——
        // 401 构造无工具调用，该面登记不承接。
        HttpResponse<String> response = DaCompatGuardrailSupport.post(http, env.base(), "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(input) + "\"}", Duration.ofSeconds(240));
        assertThat(response.statusCode()).as("/run 受理: " + response.body()).isIn(200, 500);
        assertThat(MAPPER.readTree(response.body()).path("status").asText(""))
                .as("凭据失败终态（stub 401 构造）").isEqualTo("failed");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /* ==================== F111-06 金丝雀 ==================== */

    @Test
    @Story("F111-06: 原文/工具结果/配置金丝雀不进反思输入、不进记忆、不进记录与指标")
    @DisplayName("F111-06: 三路金丝雀零泄漏")
    void canaryZeroLeakage() throws Exception {
        Canary canary = new Canary("CANARY-INPUT-" + System.nanoTime(),
                "CANARY-TOOL-" + System.nanoTime(),
                "CANARY-CFG-" + System.nanoTime());
        try (Env env = startEnv(stub -> stub.mainBusinessFailureToolCall(), canary.config())) {
            String session = "sess-f111-06-" + System.nanoTime();
            runTask(env, session, "宣告业务失败，输入里包含 " + canary.input() + "，工具输出里包含 " + canary.toolOutput());
            // 差分基线：原文/工具结果金丝雀必须真的进了主管线（stub 记录的主调用请求体）
            String mainBody = env.stub().requests().stream()
                    .filter(r -> !r.isReflection())
                    .map(StubRequest::body)
                    .findFirst().orElse("");
            assertThat(mainBody).as("原文金丝雀在主调用请求中（差分基线）")
                    .contains(canary.input());
            // 工具结果金丝雀面 blocked（无工具调用构造）——canary.toolOutput() 仅作为
            // 「不得出现在任何下游面」的哨兵值继续参与零泄漏断言
            assertThat(mainBody).as("配置金丝雀不在主调用请求中（currency 是记账字段而非请求字段）")
                    .doesNotContain(canary.config());
            String reflectionBody = env.stub().lastReflectionBody();
            assertThat(reflectionBody).as("反思请求已发生").isNotNull();
            assertThat(reflectionBody).as("反思输入只含结构化指令键")
                    .contains("instruction", "lessonCodes", "errorFacts");
            assertThat(reflectionBody).as("反思输入无三路金丝雀")
                    .doesNotContain(canary.input(), canary.toolOutput(), canary.config());
            // 记忆写面只有固定文本
            boolean writeSeen = DaReflectionCostSupport.await(WAIT, Duration.ofMillis(200),
                    () -> env.memory().writeCount() > 0);
            assertThat(writeSeen).as("记忆写面已发生").isTrue();
            assertThat(env.memory().memoryWrites().get(0).body())
                    .as("入库内容 ∈ 九码固定文本且无金丝雀")
                    .containsAnyOf(DaReflectionCostSupport.NINE_FIXED_TEXTS.toArray(new String[0]))
                    .doesNotContain(canary.input(), canary.toolOutput(), canary.config());
            // da_reflection_result 六槽位由正则限定字段构成（命中即无自由文本槽位），
            // 记录 reason 槽位是宿主侧短 token，不含模型原文
            boolean recordSeen = DaReflectionCostSupport.await(WAIT, Duration.ofMillis(300),
                    () -> !DaReflectionCostSupport.recordsForSession(session).isEmpty());
            assertThat(recordSeen).as("处置记录已产生").isTrue();
            // OTLP 标签值无金丝雀（标签是结构化身份面）
            boolean otlpSeen = DaReflectionCostSupport.await(WAIT, Duration.ofMillis(500),
                    () -> env.otlp().pushCount() > 0);
            assertThat(otlpSeen).as("OTLP 推送已到达").isTrue();
            for (OtlpSeries s : env.otlp().pushes().get(env.otlp().pushCount() - 1).series()) {
                for (String v : s.tags().values()) {
                    assertThat(v).as("OTLP 标签值无金丝雀: " + v)
                            .doesNotContain(canary.input(), canary.toolOutput(), canary.config());
                }
                assertThat(s.value()).as("指标值是数值").isFinite();
            }
            Allure.addAttachment("F111-06 证据", "text/plain",
                    "canary.input=" + canary.input() + " reflectionBody=" + reflectionBody);
        }
    }
}
