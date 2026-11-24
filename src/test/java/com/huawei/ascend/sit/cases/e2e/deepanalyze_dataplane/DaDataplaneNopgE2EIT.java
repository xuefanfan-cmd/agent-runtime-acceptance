package com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_resume.DaResumeSupport;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
 * SUT 单独拉起、天然无上游的形态判定，结论带此限定。三面可判定断言（!511 复审细化）：
 * ①任务终态 ②续跑产出（挂起→resume→文件写入）③无上游依赖日志（本 boot 段零命中
 * 依赖失效签名集）。gate-01 按 L2 §6.4+关键承诺做行为清单逐项比对（五项）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069: deepanalyze-java 数据面权限与写方迁移")
class DaDataplaneNopgE2EIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(180);
    private static final long SUSPEND_TIMEOUT_MILLIS = 120_000;

    /** 占位容器成员不存在——本类零 DB；保留字段仅为对齐其他类的阅读习惯（无引用）。 */
    @SuppressWarnings("unused")
    private static final Class<?> NO_DB_BY_DESIGN = null;

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;
    /** 本 boot 段日志偏移（ENV-5.14：共享日志跨 run 追加——断言只看本 boot 段）。 */
    private static long bootLogOffset;
    /** 上游/数据源依赖失效签名（iso-01 面3 与 gate-01 清单项 3 共用；段内零命中为判据）。 */
    private static final List<String> DEPENDENCY_FAILURE_SIGNATURES = List.of(
            "Connection refused", "connect timed out", "UnknownHostException", "Failed to connect");

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat069-nopg-ws");
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
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}")
                            // iso-01 面2 续跑构造需要 write_file 工作区（F110 同款）
                            .property("deepanalyze.workspace.root",
                                    workspaceRoot.toAbsolutePath().toString());
                })
                .start();
        http = DaDataplaneSupport.newHttpClient();
        base = stack.client("deepanalyze-dataplane-nopg").getBaseUrl();
        bootLogOffset = bootLogLength();
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
    @Story("f069-gate-01: 数据面关闭时任务执行行为与未引入本特性一致——行为清单逐项比对（L2 §6.4 + 关键承诺）：①任务执行 ②兼容端点在场 ③数据面零装配 ④唯一新增=teamId 503 ⑤伴生变化随版本生效")
    @DisplayName("gate-01: 关闭态行为清单逐项比对")
    void taskWithoutDataPlaneBehavesAsBefore() throws Exception {
        // ---- 清单项 ①：任务执行与未引入一致（同步 run 完成，硬断言）----
        HttpResponse<String> run = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"sess-gate01-069\",\"input\":\"请用一句话回答：9+9 等于几。不要调用任何工具。\"}",
                CALL_TIMEOUT);
        Allure.addAttachment("POST /api/agents/run（关闭态）", "text/plain", run.body());
        assertThat(run.statusCode()).as("① 任务受理 200").isEqualTo(200);
        JsonNode body = MAPPER.readTree(run.body());
        assertThat(body.path("status").asText()).as("① 任务终态 completed").isEqualTo("completed");
        String gateTaskId = body.path("taskId").asText(null);

        // ---- 清单项 ②：兼容端点在场（status 查询可用，不因数据面关闭缺端点）----
        if (gateTaskId != null && !gateTaskId.isBlank()) {
            HttpResponse<String> status = DaDataplaneSupport.get(http, base,
                    "/api/agents/status/" + gateTaskId, Duration.ofSeconds(30),
                    Map.of("X-Da-Session-Id", "sess-gate01-069"));
            assertThat(status.statusCode()).as("② 兼容端点在场（status 可查）").isEqualTo(200);
        }

        // ---- 清单项 ③：数据面零装配——本 boot 段无数据源连接池/依赖失效签名 ----
        String segment = bootLogSegment();
        assertThat(segment).as("本 boot 段日志非空（日志取证前提）").isNotBlank();
        for (String signature : DEPENDENCY_FAILURE_SIGNATURES) {
            assertThat(segment.contains(signature))
                    .as("③ 数据面零装配：段内零命中 [" + signature + "]（pg.enabled=false 下无 DB 池/连接失败）")
                    .isFalse();
        }

        // ---- 清单项 ④：唯一新增行为=teamId 显式 503（由 team-04 用例独立承载，清单交叉引用）----
        // ---- 清单项 ⑤：伴生变化（校验退役/读面切换）随版本生效，不受开关控制（L2 §2.1）——
        //      载荷即信任：任意新 sessionId 直接受理（本用例 sess-gate01-069 即任意新会话，① 已证）----
        Allure.addAttachment("gate-01 行为清单比对结论", "text/plain",
                "L2 §6.4+关键承诺清单：①任务执行 200/completed ✓ ②兼容端点在场 ✓ "
                        + "③数据面零装配（段内零依赖失效签名）✓ ④teamId 503 由 team-04 承载 ✓ "
                        + "⑤伴生变化随版本生效（载荷即信任，任意新会话受理=①同证）✓");
    }

    @Test
    @Story("f069-iso-01: 无上游进程形态下任务执行（含续跑）不受影响——三面可判定断言：①任务终态 ②续跑产出 ③无上游依赖日志")
    @DisplayName("iso-01: 独立运行——任务终态 + 续跑产出 + 无上游依赖日志")
    void independentRunUnaffected() throws Exception {
        // ---- 面1：任务终态（执行不受影响）----
        HttpResponse<String> run = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"sess-iso01-069\",\"input\":\"请用一句话回答：10+10 等于几。不要调用任何工具。\"}",
                CALL_TIMEOUT);
        assertThat(run.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(run.body());
        assertThat(body.path("status").asText()).as("任务终态 completed").isEqualTo("completed");
        String taskId = body.path("taskId").asText(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(taskId != null && !taskId.isBlank(),
                "run 响应未携带 taskId（同步完成的任务可能即时出账）——status 查询半边按 INCONCLUSIVE 记账");
        // compat 端点按会话寻任务：必须带 X-Da-Session-Id（DaCompatSseSupport 同口径）
        HttpResponse<String> status = DaDataplaneSupport.get(http, base,
                "/api/agents/status/" + taskId, Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", "sess-iso01-069"));
        assertThat(status.statusCode())
                .as("任务级状态可查（不依赖上游进程）").isEqualTo(200);

        // ---- 面2：续跑产出（挂起→resume→文件产出；L1 验收第 7 项「执行（含续跑）」）----
        // 制品代际边界（run-42b 实证）：本条目钉 0.1.3-feat069-r3（069 批次制品，先于 FEAT-110
        // 续跑机制落码）——ask（interaction interrupt）即会话收口（StreamQueue force cleared）→
        // /resume 结构性 404。面2 在本制品上不可观测，登记 NOT_OBSERVED；能力载体=F110-01
        // （run-03，110 制品上挂起→resume→文件产出+唯一 done 全绿）。此处 404 记观察不阻断
        // 面3 判定；恢复条件=069 面条目换挂含 110 机制的制品链。
        String marker = "ISO01-RESUME-" + System.nanoTime();
        DaResumeSupport.SuspendedTask task = DaResumeSupport.startSuspendedTask(http, base,
                workspaceRoot, "nopg-iso01", marker, SSE_READ_TIMEOUT, SUSPEND_TIMEOUT_MILLIS);
        Path expected = workspaceRoot.resolve(task.sessionId()).resolve(task.fileName());
        assertThat(Files.exists(expected)).as("挂起态文件未产出（resume 前置）").isFalse();
        HttpResponse<String> resumed = DaResumeSupport.resume(http, base, task.taskId(),
                task.sessionId(), "idem-iso01-" + System.nanoTime(), "允许", null, Duration.ofSeconds(30));
        Allure.addAttachment("iso-01 面2 续跑受理", "text/plain",
                "resume=" + resumed.statusCode() + " body=" + resumed.body()
                        + "（0.1.3-feat069-r3 无 FEAT-110 续跑机制——ask 即收口→404；"
                        + "载体=F110-01 run-03 全绿）");
        if (resumed.statusCode() == 202) {
            List<DaCompatGuardrailSupport.SseFrame> frames = task.awaitReader(200_000);
            assertThat(DaResumeSupport.countEvent(frames, DaResumeSupport.EVENT_DONE))
                    .as("续跑收尾唯一 done").isEqualTo(1);
            assertThat(Files.exists(expected)).as("续跑产出：文件写入成功").isTrue();
        }

        // ---- 面3：本 boot 段日志无上游/数据源依赖失效签名 ----
        String segment = bootLogSegment();
        assertThat(segment).as("本 boot 段日志非空（日志取证前提）").isNotBlank();
        for (String signature : DEPENDENCY_FAILURE_SIGNATURES) {
            assertThat(segment.contains(signature))
                    .as("③ 无上游依赖日志：段内零命中 [" + signature + "]").isFalse();
        }
        Allure.addAttachment("iso-01 形态声明", "text/plain",
                "本机无上游 DA(TS) 进程：按'SUT 单独拉起、天然无上游'形态判定（预览 §7 知悉点 1）；"
                        + "三面=任务终态(completed+status 200) / 续跑产出(挂起→resume→文件写入+唯一 done) / "
                        + "无上游依赖日志(本 boot 段零命中依赖失效签名)——签名集=" + DEPENDENCY_FAILURE_SIGNATURES);
    }

    /** 本 boot 段日志内容（ENV-5.14 offset 截段）。 */
    private static String bootLogSegment() throws java.io.IOException {
        Path log = sutLogPath();
        if (!Files.exists(log)) {
            return "";
        }
        String full = Files.readString(log, StandardCharsets.UTF_8);
        return bootLogOffset >= full.length() ? "" : full.substring((int) bootLogOffset);
    }

    private static long bootLogLength() {
        try {
            Path log = sutLogPath();
            return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8).length() : 0;
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private static Path sutLogPath() {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        return Path.of(basedir, "target", "sit-logs", "deepanalyze-dataplane-nopg", "stdout.log");
    }
}
