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
 * FEAT-111 三门矩阵与同码复用端到端（F111-07/08＋F111-02 确定性排除面）。
 * 确定性执行体轨道（stub 模型——用户 2026-11-09 批准的测试侧装配方，不属 Mock LLM；
 * 门控判定是宿主逻辑，与模型无关）。
 *
 * <p><b>判据（DV-111-01/02 裁决前按 L2 三门口径，Oracle 双栏留痕）：</b>
 * 门序＝enabled（onTerminalFact 同步 disabled/reflection_disabled，零 IO）→
 * selfEvolutionGate（worker 内，disabled/self_evolution_disabled）→ identity
 * （skipped/tenant_gate，不读写全局记忆）。租户注入＝X-Da-Tenant-Id 请求头
 * （缺省 default-user；{@code DaRunParamMapper.META_TENANT_ID}）。记忆 id 由受控写面
 * 按内容哈希派生（同内容同 id——出口 7「同码复用 memoryId」的宿主侧记录断言；
 * 平台侧去重属协作方契约，登记不承接）。</p>
 *
 * <p><b>构造：</b>每用例独立栈＋stub（三门是启动期配置，逐态起栈）；stub 主调用回
 * business_failure 工具调用（确定性 BUSINESS_FAILURE 终态），反思调用回
 * TOOL_INPUT_INVALID。</p>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-111")
@Feature("FEAT-111: 轨迹反思与成本追踪")
class Feat111ReflectionGatingE2EIT {

    private static final Duration GATE_WAIT = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TestConfig config;

    private static HttpClient http;

    @BeforeAll
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

    /** 起一套确定性环境（三门组合＋stub 行为由参数给定；reflection.timeout 缺省 10s）。 */
    private static Env startEnv(boolean enabled, boolean selfEvolution, boolean declared,
            Consumer<ModelStubServer> stubSetup) throws Exception {
        ModelStubServer stub = new ModelStubServer();
        stub.reflectionCode("TOOL_INPUT_INVALID");
        stubSetup.accept(stub);
        MemoryReceiverServer memory = new MemoryReceiverServer();
        OtlpReceiverServer otlp = new OtlpReceiverServer();
        SutStack stack = DaReflectionCostSupport.baseStack(config, agent -> {
            DaReflectionCostSupport.bindStubModel(agent, stub);
            DaReflectionCostSupport.gates(agent, enabled, selfEvolution, declared);
            DaReflectionCostSupport.wireObservability(agent, otlp, memory);
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

    /** 排除面静默断言（零记录＋零记忆——用于非六类失败终态）。 */
    private static void assertQuiet(String session, MemoryReceiverServer memory, String label) {
        assertThat(DaReflectionCostSupport.recordsForSession(session))
                .as(label + "——零处置记录").isEmpty();
        assertThat(memory.writes().stream().filter(w -> session.equals(w.sessionId())).count())
                .as(label + "——零记忆写入").isZero();
    }

    /** 门控未过面的静默断言：处置记录在（skipped/disabled 即期望产物），只断零记忆写入。 */
    private static void assertMemoryQuiet(String session, MemoryReceiverServer memory, String label) {
        assertThat(memory.writes().stream().filter(w -> session.equals(w.sessionId())).count())
                .as(label + "——零记忆写入（不读写全局记忆）").isZero();
    }

    /* ==================== F111-08 三门矩阵 ==================== */

    @Test
    @Story("F111-08: 三门全开＋default-user → succeeded，固定文本入库")
    @DisplayName("F111-08a: 授权态成功写入")
    void authorizedTenantWrites() throws Exception {
        try (Env env = startEnv(true, true, true, DaReflectionCostSupport::credentialFailure)) {
            String session = "sess-f111-08a-" + System.nanoTime();
            runBusinessFailure(env, session);
            List<ReflectionRecord> records = awaitRecords(session, GATE_WAIT);
            assertThat(records).as("唯一处置记录: " + records).hasSize(1);
            assertThat(records.get(0).outcome()).as("outcome").isEqualTo("succeeded");
            assertThat(records.get(0).memoryId()).as("memoryId 非空").isNotBlank();
            assertThat(env.memory().memoryWrites()).as("写面恰一次（/healthz 探测不计数）").hasSize(1);
            assertThat(env.memory().memoryWrites().get(0).body())
                    .as("入库内容 ∈ 九码固定文本")
                    .containsAnyOf(DaReflectionCostSupport.NINE_FIXED_TEXTS.toArray(new String[0]));
            Allure.addAttachment("F111-08a 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0));
        }
    }

    @Test
    @Story("F111-08: 非 default-user 租户 → skipped(tenant_gate) 零读写")
    @DisplayName("F111-08b: 非 default-user → skipped")
    void nonDefaultTenantSkipped() throws Exception {
        try (Env env = startEnv(true, true, true, DaReflectionCostSupport::credentialFailure)) {
            String session = "sess-f111-08b-" + System.nanoTime();
            HttpResponse<String> response = DaCompatGuardrailSupport.post(http, env.base(), "/api/agents/run",
                    "{\"sessionId\":\"" + session + "\",\"input\":\"宣告业务失败\"}", Duration.ofSeconds(240),
                    Map.of("X-Da-Tenant-Id", "tenant-b"));
            assertThat(response.statusCode()).as("/run 受理").isIn(200, 500);
            assertThat(MAPPER.readTree(response.body()).path("status").asText(""))
                    .as("凭据失败终态（stub 401 构造）").isEqualTo("failed");
            List<ReflectionRecord> records = awaitRecords(session, GATE_WAIT);
            assertThat(records).as("唯一处置记录").hasSize(1);
            assertThat(records.get(0).outcome()).as("租户门未过 → skipped").isEqualTo("skipped");
            assertThat(records.get(0).reason()).as("reason=tenant_gate").isEqualTo("tenant_gate");
            assertThat(records.get(0).memoryId()).as("无 memoryId").isNull();
            assertMemoryQuiet(session, env.memory(), "跳过后");
            Allure.addAttachment("F111-08b 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0));
        }
    }

    @Test
    @Story("F111-08: 未声明单租户 + default-user → skipped(tenant_gate)")
    @DisplayName("F111-08c: 未声明 → skipped")
    void undeclaredTenantSkipped() throws Exception {
        try (Env env = startEnv(true, true, false, DaReflectionCostSupport::credentialFailure)) {
            String session = "sess-f111-08c-" + System.nanoTime();
            runBusinessFailure(env, session);
            List<ReflectionRecord> records = awaitRecords(session, GATE_WAIT);
            assertThat(records).as("唯一处置记录").hasSize(1);
            assertThat(records.get(0).outcome()).as("声明缺失 → skipped").isEqualTo("skipped");
            assertThat(records.get(0).reason()).as("reason=tenant_gate").isEqualTo("tenant_gate");
            assertMemoryQuiet(session, env.memory(), "跳过后");
            Allure.addAttachment("F111-08c 证据", "text/plain",
                    "session=" + session + " record=" + records.get(0));
        }
    }

    // F111-08d（自进化门）已随 #44 裁决移除：settings 读面与自进化 YAML 键删除，
    // disabled 分派值仅剩 reflection_disabled（由 Bounded 类 F111-05b 承接）。

    /* ==================== F111-07 同码复用 + F111-02 确定性排除面 ==================== */

    @Test
    @Story("F111-07: 同码两任务复用 memoryId，各 taskId 独立 succeeded 记录")
    @DisplayName("F111-07: 同码复用 memoryId")
    void sameCodeReusesMemoryId() throws Exception {
        try (Env env = startEnv(true, true, true, stub -> {
            DaReflectionCostSupport.credentialFailure(stub);
            stub.reflectionCode("TOOL_INPUT_INVALID");
        })) {
            String session1 = "sess-f111-07-s1-" + System.nanoTime();
            String session2 = "sess-f111-07-s2-" + System.nanoTime();
            runBusinessFailure(env, session1);
            runBusinessFailure(env, session2);
            List<ReflectionRecord> records1 = awaitRecords(session1, GATE_WAIT);
            List<ReflectionRecord> records2 = awaitRecords(session2, GATE_WAIT);
            assertThat(records1).as("会话一唯一记录").hasSize(1);
            assertThat(records2).as("会话二唯一记录").hasSize(1);
            assertThat(records1.get(0).outcome()).as("两任务均 succeeded")
                    .isEqualTo("succeeded");
            assertThat(records2.get(0).outcome()).isEqualTo("succeeded");
            assertThat(records1.get(0).taskId()).as("各 taskId 独立记录")
                    .isNotEqualTo(records2.get(0).taskId());
            assertThat(records1.get(0).memoryId()).as("同码同文本 → 复用 memoryId")
                    .isEqualTo(records2.get(0).memoryId());
            assertThat(env.memory().writeCount()).as("写面共两次（每任务一次）").isEqualTo(2);
            Allure.addAttachment("F111-07 证据", "text/plain",
                    "s1=" + records1.get(0) + " s2=" + records2.get(0));
        }
    }

    @Test
    @Story("F111-02: 成功终态（确定性轨道）零反思调用、零记录、零记忆")
    @DisplayName("F111-02d: 成功终态零反思（stub 计数）")
    void successExclusionCountedByStub() throws Exception {
        try (Env env = startEnv(true, true, true, stub -> {
            // 主调用回普通回答 → 任务 completed → 非六类失败，反射不动作
        })) {
            String session = "sess-f111-02d-" + System.nanoTime();
            HttpResponse<String> response = DaCompatGuardrailSupport.post(http, env.base(),
                    "/api/agents/run",
                    "{\"sessionId\":\"" + session + "\",\"input\":\"普通任务\"}", Duration.ofSeconds(240));
            assertThat(response.statusCode()).as("/run 受理 200").isEqualTo(200);
            assertThat(MAPPER.readTree(response.body()).path("status").asText(""))
                    .as("任务成功终态").isEqualTo("completed");
            boolean anyRecord = DaReflectionCostSupport.await(Duration.ofSeconds(8), Duration.ofMillis(300),
                    () -> !DaReflectionCostSupport.recordsForSession(session).isEmpty());
            assertThat(anyRecord).as("成功任务零处置记录").isFalse();
            assertThat(env.stub().reflectionCalls()).as("零反思 provider 请求（stub 计数）").isZero();
            assertQuiet(session, env.memory(), "成功后");
            Allure.addAttachment("F111-02d 证据", "text/plain",
                    "session=" + session + " mainCalls=" + env.stub().mainCalls()
                            + " reflectionCalls=" + env.stub().reflectionCalls());
        }
    }
}
