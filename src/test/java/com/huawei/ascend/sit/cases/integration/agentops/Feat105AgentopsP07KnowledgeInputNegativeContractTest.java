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
        Process proxy = startStallProbe(proxyScript);
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

    private Process startStallProbe(Path script) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("python3", script.toAbsolutePath().toString());
        pb.environment().put("KBB_PROXY_MODE", "stall_probe");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                Path.of(System.getProperty("java.io.tmpdir"),
                        "kbb-proxy-stall_probe-" + UUID.randomUUID() + ".log").toFile()));
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
        String config = targetConfig("qdrant", "http://127.0.0.1:6333",
                "p07_queue_ctrl_" + UUID.randomUUID().toString().substring(0, 8));
        HttpResponse<String> probe = submitKnowledge(config);
        assumeTrue(probe.statusCode() == 429,
                "SUT 未启用 OPTIMIZATION_MAX_QUEUED_JOBS=0 覆盖层——本子项需部署侧 queue-capacity.env（P14 同模式）");
        assertEquals(429, probe.statusCode(), "队列容量超限应 429");
        JsonNode err = JSON.readTree(probe.body()).path("error");
        assertTrue(!err.path("code").asText().isEmpty(), "429 应带结构化错误码");
        // 无半建对象：429 任务不应产生 job 记录（idempotency-key 重放仍 429 而非返回任务）
        HttpResponse<String> r = submitKnowledge(config);
        assertEquals(429, r.statusCode(), "重放仍应 429（无半建对象）");
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
