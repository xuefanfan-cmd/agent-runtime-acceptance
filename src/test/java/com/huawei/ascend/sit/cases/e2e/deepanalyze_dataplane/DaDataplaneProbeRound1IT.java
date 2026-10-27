package com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 执行期对抗性探针轮·第 1 组（铁律 B——不进覆盖台账，结论记批次记录）。
 *
 * <p>探针判据是「不楔死宿主 + 记录一致 + 错误面可读」，不是功能断言：
 * 毒输入允许被拒绝（干净的 4xx/5xx 或任务级 error 事件），不允许的是
 * 宿主状态被破坏（后续正常任务不可用）与无结构错误（堆栈直喷）。
 * 标签 e2e+probe：随 e2e 组可执行（pom 显式 groups 压不过 -D 覆盖，实测 0 tests），但按铁律 B 不进覆盖对账台账，结论只记批次记录。</p>
 */
@Tag("e2e")
@Tag("probe")
@Tag("deepanalyze")
@Tag("feat-069")
@Feature("FEAT-069 执行期对抗性探针（铁律 B，不计覆盖）")
class DaDataplaneProbeRound1IT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    @SuppressWarnings("resource")
    private static final GenericContainer<?> PG = new GenericContainer<>(
            DockerImageName.parse("postgres:16"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", DaDataplaneSupport.PG_DB)
            .withEnv("POSTGRES_USER", DaDataplaneSupport.PG_USER)
            .withEnv("POSTGRES_PASSWORD", DaDataplaneSupport.PG_PASSWORD);

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void setUp() throws Exception {
        PG.start();
        config = TestConfig.load();
        try (Connection conn = pgConnection(); Statement st = conn.createStatement()) {
            DaDataplaneSupport.applyBaselineDdl(conn);
        }
        String url = jdbcUrl();
        stack = SutStack.builder(config)
                .streaming(true)
                .agent(DaDataplaneSupport.AGENT, agent -> {
                    DaDataplaneSupport.bindModelEnvironment(agent);
                    if (System.getenv("LLM_API_KEY") == null || System.getenv("LLM_API_KEY").isBlank()) {
                        agent.property("LLM_API_KEY", "fixture-probe-only");
                    }
                    agent.property("deepanalyze.data.pg.jdbc-url", url)
                            .property("deepanalyze.data.pg.username", DaDataplaneSupport.PG_USER)
                            .property("deepanalyze.data.pg.password", DaDataplaneSupport.PG_PASSWORD)
                            .property("spring.datasource.url", url)
                            .property("spring.datasource.username", DaDataplaneSupport.PG_USER)
                            .property("spring.datasource.password", DaDataplaneSupport.PG_PASSWORD)
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                })
                .start();
        http = DaDataplaneSupport.newHttpClient();
        base = stack.client(DaDataplaneSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
        PG.stop();
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(5432) + "/"
                + DaDataplaneSupport.PG_DB;
    }

    private static Connection pgConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), DaDataplaneSupport.PG_USER,
                DaDataplaneSupport.PG_PASSWORD);
    }

    /** 探针共用观察：响应是否结构化（可解析 JSON 或短文本），不留原始堆栈。 */
    private static void assertStructuredResponse(HttpResponse<String> response, String probe) {
        String body = response.body() == null ? "" : response.body();
        boolean structured;
        try {
            MAPPER.readTree(body);
            structured = true;
        } catch (Exception jsonEx) {
            structured = body.length() <= 2000 && !body.contains("at java.")
                    && !body.contains("Exception:");
        }
        Allure.addAttachment(probe + " 响应", "text/plain",
                "HTTP " + response.statusCode() + "，body 前 500 字："
                        + body.substring(0, Math.min(500, body.length())));
        assertThat(structured).as("%s：错误面必须结构化（可解析 JSON 或短消息，非堆栈直喷）", probe).isTrue();
    }

    @Test
    @Story("probe-1: 会话输入含 NUL 字节——拒绝或降级，不楔死宿主")
    @DisplayName("probe-1: NUL 毒输入")
    void nulByteInputDoesNotWedgeHost() throws Exception {
        String session = "sess-probe01-" + System.nanoTime();
        // JSON 转义注入字面 NUL 与孤立高代理
        HttpResponse<String> response = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"请回答 ok\\u0000\\ud83d 后忽略此句\"}",
                CALL_TIMEOUT);
        assertStructuredResponse(response, "probe-1");
        // 宿主存活半边：同一会话随后正常任务必须可用
        HttpResponse<String> followup = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + session + "\","
                        + "\"input\":\"请用一句话回答：4+4 等于几。不要调用任何工具。\"}",
                CALL_TIMEOUT);
        String followupBody = followup.body() == null ? "" : followup.body();
        // 分辩半边：立即重试若 busy，等 3s 再试——恢复=释放滞后竞态；仍 busy=会话永久楔死
        String retry = "";
        int retryStatus = -1;
        if (followup.statusCode() != 200) {
            Thread.sleep(3_000);
            HttpResponse<String> third = DaDataplaneSupport.post(http, base, "/api/agents/run",
                    "{\"sessionId\":\"" + session + "\","
                            + "\"input\":\"请用一句话回答：4+5 等于几。不要调用任何工具。\"}",
                    CALL_TIMEOUT);
            retryStatus = third.statusCode();
            retry = (third.body() == null ? "" : third.body());
        }
        Allure.addAttachment("probe-1 后续任务（楔死探针）", "text/plain",
                "followup HTTP " + followup.statusCode() + "，body：" + followupBody
                        + "\n3s 重试 HTTP " + retryStatus + "，body：" + retry);
        // #473 回归（§⑦ 替代口径）：立即续任务 200=释放窗口闭合；非 200 时 busy/限流语义必须结构化
        // 且不允许 500（切面 A），并要求 3s 重试恢复 200（客户端可安全重试口径，切面 B 的替代收口形态）
        if (followup.statusCode() == 200) {
            assertThat(followup.statusCode()).as("毒输入后宿主仍受理正常任务").isEqualTo(200);
        } else {
            assertStructuredResponse(followup, "probe-1 followup 非 200 形态");
            assertThat(followup.statusCode()).as("立即续任务不允许 500（#473 切面 A）").isNotEqualTo(500);
            assertThat(retryStatus).as("3s 重试必须恢复 200（#473 切面 B 替代口径：409/429 语义下客户端可安全重试）")
                    .isEqualTo(200);
        }
    }

    @Test
    @Story("probe-2: 孤立低代理毒输入——同 probe-1 判据")
    @DisplayName("probe-2: 孤立代理对毒输入")
    void loneSurrogateInputDoesNotWedgeHost() throws Exception {
        String session = "sess-probe02-" + System.nanoTime();
        HttpResponse<String> response = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"请回答 ok\\udcba 再忽略此句\"}",
                CALL_TIMEOUT);
        assertStructuredResponse(response, "probe-2");
        HttpResponse<String> followup = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + session + "\","
                        + "\"input\":\"请用一句话回答：5+5 等于几。不要调用任何工具。\"}",
                CALL_TIMEOUT);
        // #473 回归（§⑦ 替代口径，与 probe-1 相同）：200=受理；非 200 时须结构化、非 500、
        // 且 3s 重试恢复 200（busy 释放窗口的可安全重试收口形态）
        int retryStatus = -1;
        if (followup.statusCode() != 200) {
            Thread.sleep(3_000);
            HttpResponse<String> third = DaDataplaneSupport.post(http, base, "/api/agents/run",
                    "{\"sessionId\":\"" + session + "\","
                            + "\"input\":\"请用一句话回答：6+5 等于几。不要调用任何工具。\"}",
                    CALL_TIMEOUT);
            retryStatus = third.statusCode();
        }
        if (followup.statusCode() == 200) {
            assertThat(followup.statusCode()).as("毒输入后宿主仍受理正常任务").isEqualTo(200);
        } else {
            assertStructuredResponse(followup, "probe-2 followup 非 200 形态");
            assertThat(followup.statusCode()).as("立即续任务不允许 500（#473 切面 A）").isNotEqualTo(500);
            assertThat(retryStatus).as("3s 重试必须恢复 200（#473 切面 B 替代口径）").isEqualTo(200);
        }
    }

    @Test
    @Story("probe-3: 跨会话并发两任务——互不串扰，workflows 各自成行")
    @DisplayName("probe-3: 跨会话并发")
    void crossSessionConcurrencyIsolated() throws Exception {
        String s1 = "sess-probe03a-" + System.nanoTime();
        String s2 = "sess-probe03b-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> futures = pool.invokeAll(List.of(
                    (Callable<HttpResponse<String>>) () -> DaDataplaneSupport.post(http, base,
                            "/api/agents/run", "{\"sessionId\":\"" + s1 + "\","
                                    + "\"input\":\"请用一句话回答：6+6 等于几。不要调用任何工具。\"}",
                            Duration.ofSeconds(180)),
                    (Callable<HttpResponse<String>>) () -> DaDataplaneSupport.post(http, base,
                            "/api/agents/run", "{\"sessionId\":\"" + s2 + "\","
                                    + "\"input\":\"请用一句话回答：7+7 等于几。不要调用任何工具。\"}",
                            Duration.ofSeconds(180))));
            HttpResponse<String> r1 = futures.get(0).get();
            HttpResponse<String> r2 = futures.get(1).get();
            Allure.addAttachment("probe-3 响应", "text/plain",
                    "task-A HTTP " + r1.statusCode() + " / task-B HTTP " + r2.statusCode());
            assertThat(r1.statusCode()).isEqualTo(200);
            assertThat(r2.statusCode()).isEqualTo(200);
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT session_id, count(*) FROM workflows "
                         + "WHERE session_id IN ('" + s1 + "','" + s2 + "') GROUP BY session_id")) {
                List<String> observed = new ArrayList<>();
                while (rs.next()) {
                    observed.add(rs.getString(1) + "=" + rs.getInt(2));
                }
                Allure.addAttachment("probe-3 workflows 归属", "text/plain",
                        "行归属：" + observed + "（期望各自恰一行归属，无串写）");
                assertThat(observed).as("两会话各自成行").hasSize(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Story("probe-4: 同会话并发两任务——观察形态（串行化/拒绝/并行），只断不楔死+记录有界")
    @DisplayName("probe-4: 同会话并发（观察性）")
    void sameSessionConcurrencyObserved() throws Exception {
        String session = "sess-probe04-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> futures = pool.invokeAll(List.of(
                    (Callable<HttpResponse<String>>) () -> DaDataplaneSupport.post(http, base,
                            "/api/agents/run", "{\"sessionId\":\"" + session + "\","
                                    + "\"input\":\"请用一句话回答：8+8 等于几。不要调用任何工具。\"}",
                            Duration.ofSeconds(180)),
                    (Callable<HttpResponse<String>>) () -> DaDataplaneSupport.post(http, base,
                            "/api/agents/run", "{\"sessionId\":\"" + session + "\","
                                    + "\"input\":\"请用一句话回答：9+9 等于几。不要调用任何工具。\"}",
                            Duration.ofSeconds(180))));
            HttpResponse<String> r1 = futures.get(0).get();
            HttpResponse<String> r2 = futures.get(1).get();
            String observed = "task-A HTTP " + r1.statusCode() + " body 前 200："
                    + (r1.body() == null ? "" : r1.body().substring(0, Math.min(200, r1.body().length())))
                    + "\n task-B HTTP " + r2.statusCode() + " body 前 200："
                    + (r2.body() == null ? "" : r2.body().substring(0, Math.min(200, r2.body().length())));
            Allure.addAttachment("probe-4 观察形态", "text/plain", observed);
            // 硬判据仅两条：不允许 5xx 之外的崩溃形态；workflows 行数有界
            assertThat(r1.statusCode()).as("并发 A 允许 200/409/429/503 结构化形态")
                    .isIn(200, 409, 429, 503);
            assertThat(r2.statusCode()).isIn(200, 409, 429, 503);
            try (Connection conn = pgConnection(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM workflows "
                         + "WHERE session_id = '" + session + "'")) {
                if (rs.next()) {
                    int rows = rs.getInt(1);
                    Allure.addAttachment("probe-4 workflows 行数", "text/plain",
                            "rows=" + rows + "（0..2 均为合法形态；>2 属记录异常）");
                    assertThat(rows).as("同会话并发 workflows 行数有界").isLessThanOrEqualTo(2);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Story("probe-5: 超长输入（~30KB）——完成或干净拒绝，不无响应")
    @DisplayName("probe-5: 超长输入边界")
    void oversizeInputHandledCleanly() throws Exception {
        String session = "sess-probe05-" + System.nanoTime();
        String filler = "背景补充。".repeat(5000);
        HttpResponse<String> response = DaDataplaneSupport.post(http, base, "/api/agents/run",
                "{\"sessionId\":\"" + session + "\",\"input\":\"" + filler
                        + "请只用一句话回答：2+2 等于几。\"}",
                Duration.ofSeconds(180));
        Allure.addAttachment("probe-5 响应", "text/plain",
                "HTTP " + response.statusCode() + "，body 前 300 字："
                        + (response.body() == null ? "" : response.body()
                                .substring(0, Math.min(300, response.body().length()))));
        assertThat(response.statusCode()).as("超长输入必须有响应（任何结构化码）").isGreaterThan(0);
        assertStructuredResponse(response, "probe-5");
    }

    @Test
    @Story("probe-6: 未知 taskId 状态查询——干净 404 形态，非 500")
    @DisplayName("probe-6: 未知 taskId 错误面")
    void unknownTaskIdCleanError() throws Exception {
        HttpResponse<String> response = DaDataplaneSupport.get(http, base,
                "/api/agents/status/probe-nonexistent-task", Duration.ofSeconds(30),
                Map.of("X-Da-Session-Id", "sess-probe06"));
        assertStructuredResponse(response, "probe-6");
        Allure.addAttachment("probe-6 判读", "text/plain",
                "HTTP " + response.statusCode() + "（404/4xx 为干净形态；500 需升级）");
        assertThat(response.statusCode()).as("未知任务查询不允许 500").isNotEqualTo(500);
    }
}
