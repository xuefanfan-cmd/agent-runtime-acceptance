package com.huawei.ascend.sit.cases.e2e.deepanalyze_dataplane;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.MavenArtifact;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-069 数据面批次共享支撑：TS 基线 DDL 回放、SUT jar 内资源提取、模型环境透传。
 *
 * <p>列真源：被测制品 0.1.3-feat069（agent-solution common@6864dc3dd）ownstore 包 SQL
 * 与 SUT 自带 agent-schema.sql；基线 DDL 见
 * {@code /deepanalyze/da-dataplane-baseline.sql}（文件头注明构成与"不预建 agent_todos/
 * a2a_task_id"的口径）。
 */
public final class DaDataplaneSupport {

    /** 批次被测制品坐标（勿与其他批次版本混用）。回归基线：0.1.3-feat069（@6864dc3dd）→ r2（@095b7fb89，!890）→ r3（@05300a8c5，!894 合入点）。 */
    public static final String SUT_GROUP = "com.openjiuwen";
    public static final String SUT_ARTIFACT = "deepanalyze-engine";
    public static final String SUT_VERSION = "0.1.3-feat069-r3";
    public static final String AGENT = "deepanalyze-dataplane";
    public static final String PG_SERVICE = "postgres";
    public static final String PG_DB = "agent_rdc";
    public static final String PG_USER = "agent_rdc";
    public static final String PG_PASSWORD = "agent_rdc";
    public static final String BASELINE_DDL = "/deepanalyze/da-dataplane-baseline.sql";
    /** SUT 自带 schema 演进脚本（fat jar 内 BOOT-INF/classes 前缀，瘦 jar 直取）。 */
    public static final String SUT_SCHEMA_RESOURCE = "db/agent-schema.sql";
    public static final String SUT_GRANT_RESOURCE = "db/grant-three-accounts.sql";

    private DaDataplaneSupport() {
    }

    /** 模型环境透传（与 FEAT-054 线同口径：宿主按 DA_MODEL_* 解析，源为通用 LLM_*）。 */
    public static void bindModelEnvironment(SutStack.AgentBuilder agent) {
        passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
        passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
        passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
        // key 经 Spring -- 参数注入（最高优先级；全局 yml 的空串 -DLLM_API_KEY= 会盖掉环境变量，
        // 见 DaModelEnvironment 注释——包私有类，此处按同口径内联）
        String apiKey = System.getenv("LLM_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            agent.property("LLM_API_KEY", apiKey);
        }
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    /** 标准数据面接线：pg/redis 栈自管容器 + agent 账号连接面。 */
    public static SutStack.Builder dataplaneStack(TestConfig config) {
        String pgUrl = "jdbc:postgresql://{{host}}:{{port}}/" + PG_DB;
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    agent.property("deepanalyze.data.pg.username", PG_USER)
                            .property("deepanalyze.data.pg.password", PG_PASSWORD)
                            .property("spring.datasource.username", PG_USER)
                            .property("spring.datasource.password", PG_PASSWORD)
                            .serviceBinding(PG_SERVICE, "deepanalyze.data.pg.jdbc-url", pgUrl)
                            .serviceBinding(PG_SERVICE, "spring.datasource.url", pgUrl)
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    /** 从栈取被管 PG 的 JDBC 连接（superuser=service 用户）。 */
    public static Connection pgConnection(SutStack stack) throws SQLException {
        String address = stack.serviceUrl(PG_SERVICE).replaceFirst("^https?://", "");
        return DriverManager.getConnection(
                "jdbc:postgresql://" + address + "/" + PG_DB, PG_USER, PG_PASSWORD);
    }

    /** 回放批次基线 DDL（逐句；先剥行注释再按分号切分，兼容行尾注释）。 */
    public static void applyBaselineDdl(Connection connection) throws Exception {
        executeScript(connection, readClasspathResource(BASELINE_DDL));
    }

    /** 在既有连接上执行任意 SQL 脚本（注释剥离 + 分号切分）。 */
    public static void executeScript(Connection connection, String script) throws Exception {
        List<String> statements = splitStatements(script);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 从被测制品 jar 提取资源文本（fat jar 走 BOOT-INF/classes 前缀，瘦 jar 直取）。 */
    public static String readArtifactResource(String resource) throws IOException {
        Path jar = sutExecJar();
        try (FileSystem fs = FileSystems.newFileSystem(jar, Map.of())) {
            for (String candidate : List.of("BOOT-INF/classes/" + resource, resource)) {
                Path entry = fs.getPath(candidate);
                if (Files.exists(entry)) {
                    return Files.readString(entry, StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("制品内未找到资源 " + resource + "（jar=" + jar + "）");
    }

    public static Path sutExecJar() {
        String m2Repo = TestConfig.load().getString("sut.m2.repo",
                System.getProperty("user.home") + "/.m2/repository");
        return new MavenArtifact(SUT_GROUP, SUT_ARTIFACT, SUT_VERSION, "exec").jarPath(m2Repo);
    }

    private static String readClasspathResource(String resource) throws IOException {
        try (InputStream stream = DaDataplaneSupport.class.getResourceAsStream(resource)) {
            assertThat(stream).as("类路径资源必须存在: " + resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 先剥每行 -- 注释再按分号切分（本批次脚本无函数体/美元引用）。 */
    public static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n")) {
            String stripped = line.replaceFirst("--.*$", "");
            if (stripped.isBlank()) {
                continue;
            }
            current.append(stripped).append('\n');
            if (stripped.trim().endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            throw new IllegalStateException("脚本存在未以分号结尾的残留语句: " + tail);
        }
        return statements;
    }

    /** 查询 information_schema 确认外键存在性。 */
    public static boolean constraintExists(Connection connection, String table, String constraint)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM information_schema.table_constraints "
                             + "WHERE table_name = '" + table + "' AND constraint_name = '" + constraint
                             + "' AND constraint_type = 'FOREIGN KEY'")) {
            return rows.next();
        }
    }

    /** 简单表存在性（information_schema.tables）。 */
    public static boolean tableExists(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_name = '" + table + "'")) {
            return rows.next();
        }
    }

    /** 列存在性（information_schema.columns）。 */
    public static boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM information_schema.columns WHERE table_name = '" + table
                             + "' AND column_name = '" + column + "'")) {
            return rows.next();
        }
    }

    public static HttpClient newHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static HttpResponse<String> get(HttpClient http, String base, String path, Duration timeout)
            throws IOException, InterruptedException {
        return get(http, base, path, timeout, Map.of());
    }

    public static HttpResponse<String> get(HttpClient http, String base, String path, Duration timeout,
                                           Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .GET();
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static HttpResponse<String> post(HttpClient http, String base, String path, String body,
                                     Duration timeout) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder()
                        .uri(URI.create(base + path))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static HttpResponse<String> post(HttpClient http, String base, String path, String body,
                                     Duration timeout, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }


    /**
     * 发起 run-stream 并把 SSE 流读到 EOF，返回全部 data: 载荷行（按到达序）。
     * 兼容 "data: x" 与 "data:x" 两种前缀形态（对齐 DaCompatSseSupport 的前缀宽容）。
     */
    public static List<String> postAndDrainSse(HttpClient http, String base, String path, String body,
                                               Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<InputStream> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("run-stream 未按 SSE 受理: HTTP " + response.statusCode()
                    + " body=" + new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        List<String> payloads = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    payloads.add(line.substring(5).stripLeading());
                }
            }
        }
        return payloads;
    }

    /** Properties 形态的账号连接参数（grant IT 用）。 */
    public static Properties connectionProps(String user, String password) {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        return props;
    }
}
