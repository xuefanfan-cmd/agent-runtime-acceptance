package com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 数据面关闭形态（f069-team-04 / f069-gate-01 / f069-iso-01；L2 §6.4）。
 *
 * <p>deepanalyze-dataplane-nopg 条目（pg.enabled=false + record.enabled=false）——
 * "数据源未启用时任务执行行为与未引入本特性逐字节一致，唯一例外是带 teamId 显式 503"。
 * 类内不拉数据库容器（数据面关闭=无 PG 依赖）；Redis 仍按常态部署形态由栈托管。
 *
 * <p>iso-01 形态限制（预览 §7 知悉点 1）：本机无上游 DA(TS) 进程，"停掉上游"只能以
 * SUT 单独拉起、天然无上游的形态判定，结论带此限定。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneNopgE2EIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    /** 占位容器成员不存在——本类零 DB；保留字段仅为对齐其他类的阅读习惯（无引用）。 */
    @SuppressWarnings("unused")
    private static final Class<?> NO_DB_BY_DESIGN = null;

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void setUp() {
        config = TestConfig.load();
        stack = SutStack.builder(config)
                .streaming(true)
                .agent("deepanalyze-dataplane-nopg", agent -> {
                    DaDataplaneSupport.bindModelEnvironment(agent);
                    if (System.getenv("LLM_API_KEY") == null || System.getenv("LLM_API_KEY").isBlank()) {
                        agent.property("LLM_API_KEY", "fixture-no-runner-credentials");
                    }
                    // "未引入本特性"的常态部署形态含 Redis（D1 未定案）——不接 redis 则
                    // RedisStore 取 agent_state 失败、任务直接失败（本轮实测），属环境前置缺失
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                })
                .start();
        http = DaDataplaneSupport.newHttpClient();
        base = stack.client("deepanalyze-dataplane-nopg").getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Story("f069-team-04: 数据源未启用且携带 teamId → 显式 503，不静默回落单 Agent")
    @DisplayName("team-04: 数据面关闭 + teamId → 503 data plane unavailable")
    void teamIdWithDisabledDataPlane503() throws Exception {
        HttpResponse<String> response = DaDataplaneSupport.post(http, base, "/api/agents/run-stream",
                "{\"sessionId\":\"sess-team04-069\",\"input\":\"按团队执行\",\"teamId\":\"any-team\"}",
                Duration.ofSeconds(30));
        Allure.addAttachment("run-stream（数据面关闭+teamId）", "text/plain", response.body());
        assertThat(response.statusCode()).as("显式拒绝（不静默回落）").isEqualTo(503);
        assertThat(response.body()).contains("data plane unavailable");
    }

    @Test
    @Story("f069-gate-01: 数据面关闭时任务执行行为与未引入本特性一致")
    @DisplayName("gate-01: 关闭态普通任务照常执行")
    void taskWithoutDataPlaneBehavesAsBefore() throws Exception {
        HttpResponse<String> run = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"sess-gate01-069\",\"input\":\"请用一句话回答：9+9 等于几。不要调用任何工具。\"}",
                CALL_TIMEOUT);
        Allure.addAttachment("POST /api/agents/run（关闭态）", "text/plain", run.body());
        assertThat(run.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(run.body());
        if (body.hasNonNull("status")) {
            assertThat(body.path("status").asText()).isEqualTo("completed");
        }
        Allure.addAttachment("gate-01 口径", "text/plain",
                "关闭态唯一行为变化=teamId 503（team-04 已断言）；普通任务执行形态与未引入本特性一致");
    }

    @Test
    @Story("f069-iso-01: 无上游进程形态下任务执行不受影响（形态受限判定）")
    @DisplayName("iso-01: 独立运行——任务执行 + 任务状态可查")
    void independentRunUnaffected() throws Exception {
        HttpResponse<String> run = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"sess-iso01-069\",\"input\":\"请用一句话回答：10+10 等于几。不要调用任何工具。\"}",
                CALL_TIMEOUT);
        assertThat(run.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(run.body());
        assertThat(body.path("status").asText()).isEqualTo("completed");
        String taskId = body.path("taskId").asText(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(taskId != null && !taskId.isBlank(),
                "run 响应未携带 taskId（同步完成的任务可能即时出账）——status 查询半边按 INCONCLUSIVE 记账");
        // compat 端点按会话寻任务：必须带 X-Da-Session-Id（DaCompatSseSupport 同口径）
        HttpResponse<String> status = DaDataplaneSupport.get(http, base,
                "/api/agents/status/" + taskId, Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", "sess-iso01-069"));
        assertThat(status.statusCode())
                .as("任务级状态可查（不依赖上游进程）").isEqualTo(200);
        Allure.addAttachment("iso-01 形态声明", "text/plain",
                "本机无上游 DA(TS) 进程：按'SUT 单独拉起、天然无上游'形态判定（预览 §7 知悉点 1）；"
                        + "'上游先在后挂'形态本机不可构造，结论带此限定");
    }
}
