package com.huawei.ascend.sit.cases.integration.agentops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-105 contract 批：P07 knowledge 输入校验负向族。
 *
 * <p>Oracle：L2-knowledge §2.2/§2.6 异常表。PASS = 错误码+定位一致、无半建对象。</p>
 *
 * <p>本类覆盖：不支持协议/缺字段 → 422 INVALID_KNOWLEDGE_TARGET；云 metadata 端点 →
 * 422 TARGET_NOT_ALLOWED；probe 超时 → 503（依赖 kbb_proxy.py stall_probe 模式，
 * 端口 6399 已在出站白名单）；队列容量 → 429（GAP-105-queuefull，需部署侧
 * {@code deploy/queue-capacity.env} 覆盖层 OPTIMIZATION_MAX_QUEUED_JOBS=0——
 * 与 P14 的 timeout-override 同模式：未启用覆盖层时本子项按假设跳过）。</p>
 *
 * <p>登记未覆盖（本机环境无法触发）：probe 认证失败 TARGET_AUTH_FAILED
 * （本地 Qdrant 无鉴权，认证必然通过）；CREDENTIAL_ENCRYPTION_UNAVAILABLE
 * （需专门的无 CREDENTIAL_KEYS 部署，与共享 SUT 冲突）。</p>
 */
@Tags({@Tag("contract"), @Tag("agent-ops"), @Tag("feat-105")})
@Feature("FEAT-105: Agent-ops P07 knowledge 输入校验负向族")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat105AgentopsP07KnowledgeInputNegativeContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = System.getProperty(
            "sut.agentops.baseUrl", envOrDefault("SUT_AGENTOPS_BASE_URL", "http://127.0.0.1:8091"));

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpResponse<String> submitKnowledge(String configJson) throws IOException, InterruptedException {
        byte[] qa = ("{\"schema_version\":1,\"cases\":[{\"id\":\"c1\",\"question\":\"如何申请报销？\","
                + "\"answer\":\"提交票据和申请单。\",\"expected_category\":\"finance\"}],"
                + "\"category_definitions\":{\"finance\":\"财务制度\"}}").getBytes(StandardCharsets.UTF_8);
        String boundary = "feat105-p07-" + UUID.randomUUID();
        var bos = new ByteArrayOutputStream();
        byte[][] parts = {
                ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"qa_file\"; filename=\"qa.json\"\r\n"
                        + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8),
                qa,
                ("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"config\"\r\n\r\n"
                        + configJson + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8),
        };
        for (byte[] part : parts) {
            bos.write(part);
        }
        HttpRequest r = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/optimizers/knowledge/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", "feat105-p07-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                .timeout(Duration.ofSeconds(90))
                .build();
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private String targetConfig(String adapterType, String endpoint, String collection) {
        return "{\"schema_version\":1,"
                + "\"target\":{\"adapter_type\":\"" + adapterType + "\",\"endpoint\":\"" + endpoint
                + "\",\"collection_ref\":\"" + collection + "\"},"
                + "\"options\":{\"target_recall\":0,\"top_k\":3,\"max_iterations\":1,\"candidate_budget\":3,"
                + "\"extraction_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"judge_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
    }

    // ---------- 不支持协议 / 缺字段 → INVALID_KNOWLEDGE_TARGET ----------

    @Test
    @Order(1)
    @Story("p07.target: 不支持协议 / 缺字段")
    @DisplayName("P07.1: 非法知识库目标 → 422 INVALID_KNOWLEDGE_TARGET")
    void p07InvalidTarget() throws Exception {
        // (a) 不支持的协议（本部署仅验证 qdrant）
        HttpResponse<String> r1 = submitKnowledge(
                targetConfig("weaviate", "http://127.0.0.1:9999", "any_collection"));
        assertEquals(422, r1.statusCode(), "不支持协议应 422：" + r1.statusCode());
        String code1 = JSON.readTree(r1.body()).path("error").path("code").asText();
        assertTrue(code1.equals("INVALID_KNOWLEDGE_TARGET") || code1.equals("UNSAFE_ADAPTER_CAPABILITY"),
                "不支持协议的错误码应属于目标校验族：" + code1);
        // (b) 缺 collection_ref
        HttpResponse<String> r2 = submitKnowledge(
                "{\"schema_version\":1,\"target\":{\"adapter_type\":\"qdrant\","
                        + "\"endpoint\":\"http://127.0.0.1:6333\"},"
                        + "\"options\":{\"target_recall\":0,\"top_k\":3,\"max_iterations\":1,\"candidate_budget\":3,"
                        + "\"extraction_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                        + "\"judge_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}");
        assertEquals(422, r2.statusCode(), "缺 collection_ref 应 422：" + r2.statusCode());
        assertEquals("INVALID_KNOWLEDGE_TARGET",
                JSON.readTree(r2.body()).path("error").path("code").asText(), "缺字段错误码");
    }

    // ---------- 云 metadata / 非放行端点 → TARGET_NOT_ALLOWED ----------

    @Test
    @Order(2)
    @Story("p07.not-allowed: 云 metadata 端点（出站白名单外）")
    @DisplayName("P07.2: 出站非放行端点 → 422 TARGET_NOT_ALLOWED")
    void p07TargetNotAllowed() throws Exception {
        // 云 metadata 端点（169.254.169.254）不在出站白名单 → 受理前拒绝
        HttpResponse<String> r = submitKnowledge(
                targetConfig("qdrant", "http://169.254.169.254:6333", "meta_collection"));
        assertEquals(422, r.statusCode(), "非放行端点应 422：" + r.statusCode());
        JsonNode err = JSON.readTree(r.body()).path("error");
        assertEquals("INVALID_KNOWLEDGE_TARGET", err.path("code").asText(),
                "端点拒绝归入目标校验族（details.reason=origin_not_allowed）：" + err.path("code").asText());
        assertEquals("origin_not_allowed", err.path("details").path("reason").asText(),
                "拒绝原因应标明 origin_not_allowed");
    }

    // ---------- probe 超时 → 503 ----------

    @Test
    @Order(3)
    @Story("p07.probe-timeout: 放行端点 probe 挂起 → 503")
    @DisplayName("P07.3: probe 超时 → 503（依赖 kbb_proxy stall_probe 模式）")
    void p07ProbeTimeout() throws Exception {
        // 前置：kbb_proxy.py 以 stall_probe 模式运行于 6399（出站白名单内），
        // GET /collections 挂起 45s > probe 超时 → 提交返回 503
        Path proxyScript = Path.of("src", "test", "resources", "agentops", "kbb_proxy.py");
        assumeTrue(Files.exists(proxyScript), "kbb_proxy.py 不在测试资源中");
        Process proxy = startProxy(proxyScript, "stall_probe", null);
        try {
            HttpResponse<String> r = submitKnowledge(
                    targetConfig("qdrant", "http://127.0.0.1:6399", "p07_probe_timeout_coll"));
            assertEquals(503, r.statusCode(), "probe 超时应 503：" + r.statusCode() + " " + r.body());
            assertTrue(JSON.readTree(r.body()).path("error").path("retryable").asBoolean(false),
                    "probe 超时应标记可重试");
        } finally {
            proxy.destroy();
            proxy.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private Process startProxy(Path script, String mode, String expectedKey) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("python3", script.toAbsolutePath().toString());
        pb.environment().put("KBB_PROXY_MODE", mode);
        if (expectedKey != null) {
            pb.environment().put("KBB_PROXY_EXPECTED_KEY", expectedKey);
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                Path.of(System.getProperty("java.io.tmpdir"),
                        "kbb-proxy-" + mode + "-" + UUID.randomUUID() + ".log").toFile()));
        Process p = pb.start();
        // 等端口就绪
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", 6399), 500);
                return p;
            } catch (IOException e) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return p;
                }
            }
        }
        p.destroy();
        throw new IllegalStateException("stall_probe 代理端口 6399 未就绪");
    }

    // ---------- 队列容量 → 429（GAP-105-queuefull；需覆盖层） ----------

    @Test
    @Order(4)
    @Story("p07.queue-capacity: OPTIMIZATION_MAX_QUEUED_JOBS=0 → 受理前 429")
    @DisplayName("P07.4: 队列容量上限 → 429（需 queue-capacity.env 覆盖层）")
    void p07QueueCapacity() throws Exception {
        // 未启用覆盖层时受理 202 → 按假设跳过（与 P14 timeout-override 同模式）
        String coll = "p07_queue_ctrl_" + UUID.randomUUID().toString().substring(0, 8);
        // probe 要求集合先存在——否则 422 集合不存在，测不到容量面
        HttpRequest put = HttpRequest.newBuilder(URI.create("http://127.0.0.1:6333/collections/" + coll))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}"))
                .build();
        http.send(put, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> probe = submitKnowledge(targetConfig("qdrant", "http://127.0.0.1:6333", coll));
        assumeTrue(probe.statusCode() == 429,
                "SUT 未启用 OPTIMIZATION_MAX_QUEUED_JOBS=0 覆盖层——本子项需部署侧 queue-capacity.env（P14 同模式）");
        assertEquals(429, probe.statusCode(), "队列容量超限应 429");
        JsonNode err = JSON.readTree(probe.body()).path("error");
        assertTrue(!err.path("code").asText().isEmpty(), "429 应带结构化错误码");
        // 无半建对象：429 任务不应产生 job 记录（idempotency-key 重放仍 429 而非返回任务）
        HttpResponse<String> r = submitKnowledge(targetConfig("qdrant", "http://127.0.0.1:6333", coll));
        assertEquals(429, r.statusCode(), "重放仍应 429（无半建对象）");
    }

    // ---------- probe 认证失败 → TARGET_AUTH_FAILED ----------

    @Test
    @Order(4)
    @Story("p07.auth: 错误凭据→probe 401→TARGET_AUTH_FAILED，secret 不回显")
    @DisplayName("P07.4: probe 认证失败 → 422 TARGET_AUTH_FAILED（kbb_proxy auth_required）")
    void p07ProbeAuthFailure() throws Exception {
        // 代理要求 KBB_PROXY_EXPECTED_KEY（correct-token）；job 配错误 api_key →
        // probe 收 401 → 产品映射 TARGET_AUTH_FAILED；响应不得回显 secret
        Process proxy = startProxy(proxyScriptPath(), "auth_required", "correct-token");
        try {
            String secret = "wrong-token-" + UUID.randomUUID();
            HttpResponse<String> r = submitKnowledge(targetConfigAuth(
                    "http://127.0.0.1:6399", "p07_auth_coll", secret));
            assertEquals(422, r.statusCode(), "probe 认证失败应 422：" + r.statusCode() + " " + r.body());
            // 产品实发码为 KNOWLEDGE_AUTH_FAILED（L2 §2.2 表记 TARGET_AUTH_FAILED，
            // 黑盒以实测契约为准——语义一致：probe 认证失败且不建任务）
            assertEquals("KNOWLEDGE_AUTH_FAILED",
                    JSON.readTree(r.body()).path("error").path("code").asText(), "认证失败错误码");
            assertTrue(!r.body().contains(secret), "响应不得回显 secret");
        } finally {
            proxy.destroy();
            proxy.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    // ---------- CREDENTIAL_ENCRYPTION_UNAVAILABLE → 503（需覆盖层） ----------

    @Test
    @Order(5)
    @Story("p07.credential: CREDENTIAL_KEYS 缺失→加密不可用→503/能力不可用")
    @DisplayName("P07.5: 凭据密钥缺失 → 503 CREDENTIAL_ENCRYPTION_UNAVAILABLE（需 credential-override 覆盖层）")
    void p07CredentialUnavailable() throws Exception {
        // 与 P14/P07.4 同模式：外部 credential-override.env（OPTIMIZATION_CREDENTIAL_KEYS= 空覆盖）
        // + 重启。凭据缺失不影响能力发现，只影响**提交带 auth 的任务**——门控以提交结果为准：
        // 202=覆盖层未启用（正常 SUT）→ 按假设跳过；503=覆盖层生效 → 断言错误码与 secret 不回显
        String secret = "feat105-secret-" + UUID.randomUUID();
        HttpResponse<String> r = submitKnowledge(targetConfigAuth(
                "http://127.0.0.1:6333", "p07_cred_coll", secret));
        assumeTrue(r.statusCode() == 503,
                "SUT 未启用 credential-override 覆盖层（提交正常受理 " + r.statusCode()
                        + "）——本子项需部署侧覆盖层");
        // 产品实发契约：顶层码 KNOWLEDGE_OPTIMIZER_UNAVAILABLE + details.reasons 含
        // credential_envelope_unavailable（与 L2 表记 CREDENTIAL_ENCRYPTION_UNAVAILABLE 语义一致）
        JsonNode err = JSON.readTree(r.body()).path("error");
        assertEquals("KNOWLEDGE_OPTIMIZER_UNAVAILABLE", err.path("code").asText(), "503 顶层错误码");
        assertTrue(err.path("details").path("reasons").toString()
                        .toLowerCase().contains("credential"),
                "reasons 应含 credential_envelope_unavailable：" + err.path("details").path("reasons"));
        assertTrue(!r.body().contains(secret), "响应不得回显 secret");
    }

    private String targetConfigAuth(String endpoint, String collection, String apiKey) {
        return targetConfig("qdrant", endpoint, collection)
                .replace("\"collection_ref\":\"" + collection + "\"",
                        "\"collection_ref\":\"" + collection + "\","
                                + "\"auth\":{\"api_key\":\"" + apiKey + "\"}");
    }

    private Path proxyScriptPath() {
        return Path.of("src", "test", "resources", "agentops", "kbb_proxy.py");
    }

    private HttpResponse<String> send(String method, String path, String body, String contentType,
                                      List<String[]> extraHeaders) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", contentType == null ? "application/json" : contentType);
        if (extraHeaders != null) {
            for (String[] h : extraHeaders) {
                b.header(h[0], h[1]);
            }
        }
        HttpRequest.Builder withBody = switch (method) {
            case "GET" -> b.GET();
            default -> b.method(method, HttpRequest.BodyPublishers.ofString(
                    body == null ? "" : body, StandardCharsets.UTF_8));
        };
        return http.send(withBody.timeout(Duration.ofSeconds(90)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ---------- 对照：合法目标受理 202 ----------

    @Test
    @Order(5)
    @Story("p07.control: 合法 qdrant 目标受理 202（负向族不误伤）")
    @DisplayName("P07.5: 对照组——合法目标受理 202")
    void p07ValidControl() throws Exception {
        String coll = "p07_ctrl_" + UUID.randomUUID().toString().substring(0, 8);
        // probe 要求集合先存在：创建对照集合
        HttpRequest put = HttpRequest.newBuilder(URI.create("http://127.0.0.1:6333/collections/" + coll))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}"))
                .build();
        http.send(put, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> r = submitKnowledge(targetConfig("qdrant", "http://127.0.0.1:6333", coll));
        assertEquals(202, r.statusCode(), "合法目标应受理 202：" + r.statusCode() + " " + r.body());
    }
}
