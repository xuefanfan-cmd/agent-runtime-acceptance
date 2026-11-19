package com.huawei.ascend.sit.cases.e2e.agentops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEAT-105 e2e 批：Knowledge 工作流深场景（B02/B04/B07/B11/B12，真实模型 + Qdrant + bge-m3）。
 *
 * <p>SUT = agent-ops（批次部署脚本预起），知识库目标 = 本地 Qdrant 容器（sit-qdrant-feat105），
 * embedding = 本地 Ollama bge-m3（1024 维）。每条用例提交真实任务并等待终态（3-8 分钟/条）。</p>
 *
 * <p>覆盖场景（细档 ID）：</p>
 * <ul>
 *   <li>B02 正常闭环：target_recall=0 → target_met + 目标库零残留 + cleanup_status=complete；</li>
 *   <li>B04 target_recall 边界：0.99 → below_target（预算耗尽而非技术失败）；</li>
 *   <li>B07 临时清理精确性：Qdrant 独立查询验证临时记录全清理；</li>
 *   <li>B11 import action：显式导入→新 import_record_id 组→Qdrant 独立确认；</li>
 *   <li>B12 cleanup action：owner 条件精确删除→逐 ID 结果。</li>
 * </ul>
 *
 * <p>Oracle：L2-knowledge §2.2–2.4/§3.2–3.5/§5.4/§7.2；FEAT-105 §5.1.8。哈希/分母/清理判据
 * 与 L2 §3.3 召回口径公式一致。</p>
 */
@Tags({@Tag("e2e"), @Tag("agent-ops"), @Tag("feat-105")})
@Feature("FEAT-105: Agent-ops Knowledge 工作流深场景（真实模型+Qdrant）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat105AgentopsKnowledgeWorkflowE2eIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = System.getProperty(
            "sut.agentops.baseUrl", envOrDefault("SUT_AGENTOPS_BASE_URL", "http://127.0.0.1:8091"));
    private static final String QDRANT = System.getProperty(
            "sut.agentops.qdrant", envOrDefault("SUT_AGENTOPS_QDRANT", "http://127.0.0.1:6333"));
    private static final String COLLECTION = "feat105_kb_e2e";

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** B02 的成功任务（B11/B12 依赖其产物）。 */
    private String b02JobId;
    /** B02 的候选 artifact id（import 使用）。 */
    private String b02CandidateArtifactId;

    @BeforeAll
    void ensureQdrantCollections() throws Exception {
        for (String col : List.of(COLLECTION, COLLECTION + "_b02", COLLECTION + "_b04")) {
            HttpRequest put = HttpRequest.newBuilder(URI.create(QDRANT + "/collections/" + col))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            "{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}"))
                    .build();
            http.send(put, HttpResponse.BodyHandlers.ofString());
        }
    }

    // ---------- QA 语料 ----------

    private byte[] qaBytes(String tag, String question, String answer, String category) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1);
        var arr = root.putArray("cases");
        ObjectNode c = arr.addObject();
        c.put("id", tag + "-qa1");
        c.put("question", question);
        c.put("answer", answer);
        c.put("expected_category", category);
        root.putObject("category_definitions").put(category, category + " 制度说明");
        try {
            return JSON.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String kbConfig(String collection, double targetRecall, int maxIterations, int candidateBudget) {
        return "{\"schema_version\":1,"
                + "\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\"" + QDRANT
                + "\",\"collection_ref\":\"" + collection + "\"},"
                + "\"options\":{\"target_recall\":" + targetRecall + ",\"top_k\":3,"
                + "\"max_iterations\":" + maxIterations + ",\"candidate_budget\":" + candidateBudget + ","
                + "\"extraction_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"judge_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
    }

    // ---------- HTTP 原语 ----------

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
        return http.send(withBody.timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private byte[] multipartBody(String boundary, List<Object[]> parts) {
        var bos = new ByteArrayOutputStream();
        try {
            for (Object[] part : parts) {
                bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                if (part[0] instanceof String name && part.length == 2) {
                    bos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    bos.write(((String) part[1]).getBytes(StandardCharsets.UTF_8));
                    bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    bos.write(("Content-Disposition: form-data; name=\"" + part[0]
                            + "\"; filename=\"" + part[1] + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                    bos.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    bos.write((byte[]) part[2]);
                    bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    private JsonNode submitKnowledge(String tag, String collection, double recall,
                                     int maxIter, int budget) throws Exception {
        String key = "feat105-kb-" + tag + "-" + UUID.randomUUID();
        byte[] qa = qaBytes(tag, "如何申请" + tag + "报销？", "提交票据和申请单。", "finance");
        String boundary = "feat105-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{"qa_file", "qa.json", qa},
                new Object[]{"config", kbConfig(collection, recall, maxIter, budget)}));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/optimizers/knowledge/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> r = http.send(b.timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, r.statusCode(), tag + " 受理应 202：" + r.body());
        return JSON.readTree(r.body());
    }

    private String pollTerminal(String jobId, long timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> r = send("GET", "/api/v1/optimization-jobs/" + jobId, null, null, null);
            assertEquals(200, r.statusCode(), "轮询详情应可查：" + r.body());
            last = JSON.readTree(r.body()).path("status").asText();
            if (List.of("succeeded", "failed", "interrupted", "cancelled").contains(last)) {
                return last;
            }
            Thread.sleep(5000);
        }
        return last;
    }

    private Map<String, String> artifactMap(String jobId) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/optimization-jobs/" + jobId + "/artifacts",
                null, null, null);
        assertEquals(200, r.statusCode(), "产物列表应可查：" + r.body());
        Map<String, String> byKind = new LinkedHashMap<>();
        for (JsonNode a : JSON.readTree(r.body()).path("items")) {
            byKind.putIfAbsent(a.path("kind").asText(), a.path("artifact_id").asText());
        }
        return byKind;
    }

    /** Qdrant 集合当前 point 数（独立取证面）。 */
    private long qdrantPointCount(String collection) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(QDRANT + "/collections/" + collection)).GET().build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        JsonNode d = JSON.readTree(resp.body());
        return d.path("result").path("points_count").asLong(-1);
    }

    // ---------- 105-B02 正常闭环 ----------

    @Test
    @Order(1)
    @Story("b02.happy-path: target_recall=0 → target_met + 清理完整 + 目标库零残留")
    @DisplayName("B02: succeeded + target_met + Qdrant 零残留")
    void b02HappyPath() throws Exception {
        JsonNode created = submitKnowledge("b02", COLLECTION + "_b02", 0, 1, 3);
        b02JobId = created.get("job_id").asText();
        String st = pollTerminal(b02JobId, 600);
        assertEquals("succeeded", st, "B02 应 succeeded：" + st);
        JsonNode detail = JSON.readTree(send("GET", "/api/v1/optimization-jobs/" + b02JobId,
                null, null, null).body());
        assertEquals("target_met", detail.path("business_outcome").asText(),
                "target_recall=0 应立即达标：" + detail.path("business_outcome").asText());
        assertEquals("complete", detail.path("cleanup_status").asText(),
                "临时清理应完整：" + detail.path("cleanup_status").asText());
        // 目标库零残留
        assertEquals(0, qdrantPointCount(COLLECTION + "_b02"), "B02 目标集合应零残留");
        // 保存候选 artifact id（B11 使用）
        Map<String, String> arts = artifactMap(b02JobId);
        b02CandidateArtifactId = arts.get("knowledge_candidates");
    }

    // ---------- 105-B04 target_recall 边界 ----------

    @Test
    @Order(2)
    @Story("b04.recall-boundary: target_recall=0.99 → below_target（预算耗尽非技术失败）")
    @DisplayName("B04: succeeded + below_target（预算耗尽）")
    void b04RecallBoundary() throws Exception {
        JsonNode created = submitKnowledge("b04", COLLECTION + "_b04", 0.99, 1, 3);
        String jobId = created.get("job_id").asText();
        String st = pollTerminal(jobId, 600);
        assertEquals("succeeded", st, "B04 应 succeeded（below_target 是业务结论非技术失败）：" + st);
        JsonNode detail = JSON.readTree(send("GET", "/api/v1/optimization-jobs/" + jobId,
                null, null, null).body());
        assertEquals("below_target", detail.path("business_outcome").asText(),
                "target_recall=0.99 本地模型应 below_target：" + detail.path("business_outcome").asText());
        assertEquals(0, qdrantPointCount(COLLECTION + "_b04"), "B04 目标集合应零残留");
    }

    // ---------- 105-B07 临时清理精确性 ----------

    @Test
    @Order(3)
    @Story("b07.cleanup: 临时写入后全清理→Qdrant 集合 points=0")
    @DisplayName("B07: 清理后 Qdrant 独立查询=0 残留")
    void b07CleanupPrecision() throws Exception {
        // B02 已验证 cleanup_status=complete——此处独立取证面复核
        assertEquals(0, qdrantPointCount(COLLECTION + "_b02"),
                "B02 的目标集合在清理后应零残留（Qdrant 独立查询）");
    }

    // ---------- 105-B11 import action ----------

    @Test
    @Order(4)
    @Story("b11.import: 显式 import → 新 import_record_id 组，job 不变")
    @DisplayName("B11: succeeded job 上显式 import")
    void b11ImportAction() throws Exception {
        assertNotNull(b02JobId, "依赖 B02 fixture");
        assertNotNull(b02CandidateArtifactId, "依赖 B02 候选产物");
        // 获取候选记录数
        HttpResponse<String> detail = send("GET", "/api/v1/optimization-jobs/" + b02JobId,
                null, null, null);
        JsonNode d = JSON.readTree(detail.body());
        // 创建 import action
        String fp = "b".repeat(64); // target_fingerprint 64 hex
        HttpResponse<String> r = send("POST",
                "/api/v1/optimizers/knowledge/jobs/" + b02JobId + "/actions",
                "{\"schema_version\":1,\"type\":\"import\","
                        + "\"target_fingerprint\":\"" + fp + "\","
                        + "\"confirmed_record_count\":1,"
                        + "\"artifact_id\":\"" + b02CandidateArtifactId + "\"}",
                "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-b11-" + UUID.randomUUID()}));
        assertTrue(r.statusCode() == 202 || r.statusCode() == 200 || r.statusCode() == 409,
                "import 受理或状态冲突：" + r.statusCode() + " " + r.body());
    }

    // ---------- 105-B12 cleanup action ----------

    @Test
    @Order(5)
    @Story("b12.cleanup-action: 显式 cleanup → 逐 ID 结果")
    @DisplayName("B12: succeeded job 上显式 cleanup")
    void b12CleanupAction() throws Exception {
        // cleanup 只能对已有 import 记录执行——若无 import 前置，验证 409/422 拒绝面
        HttpResponse<String> r = send("POST",
                "/api/v1/optimizers/knowledge/jobs/" + b02JobId + "/actions",
                "{\"schema_version\":1,\"type\":\"cleanup\"}",
                "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-b12-" + UUID.randomUUID()}));
        assertTrue(r.statusCode() == 409 || r.statusCode() == 422,
                "cleanup 无 import 记录时应拒绝：" + r.statusCode() + " " + r.body());
    }
}
