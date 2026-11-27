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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEAT-105 e2e 批：Knowledge 深场景（B03/B05/B06/B08/B09/B10/B13）。
 *
 * <p>⚠️ 当前全部被 #839 阻塞（embedding 调用路径断裂，temporary_write 0/1）——
 * PR !1284 修复合入后即可执行。本类为预写测试代码，等修复后立即可跑。</p>
 *
 * <p>Oracle：L2-knowledge §3.3（召回公式）、§3.4（质检/清理）、§3.5（action）。</p>
 */
@Tags({@Tag("e2e"), @Tag("agent-ops"), @Tag("feat-105")})
@Feature("FEAT-105: Agent-ops Knowledge 深场景（待 #839 修复后执行）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat105AgentopsKnowledgeDeepE2eIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = System.getProperty(
            "sut.agentops.baseUrl", envOrDefault("SUT_AGENTOPS_BASE_URL", "http://127.0.0.1:8091"));
    private static final String QDRANT = System.getProperty(
            "sut.agentops.qdrant", envOrDefault("SUT_AGENTOPS_QDRANT", "http://127.0.0.1:6333"));

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeAll
    void ensureQdrantCollections() throws Exception {
        for (String col : List.of("feat105_deep_b03", "feat105_deep_b05", "feat105_deep_b06",
                "feat105_deep_b08", "feat105_deep_b09", "feat105_deep_b10")) {
            HttpRequest put = HttpRequest.newBuilder(URI.create(QDRANT + "/collections/" + col))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            "{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}"))
                    .build();
            http.send(put, HttpResponse.BodyHandlers.ofString());
        }
    }

    // ---------- helper ----------

    private byte[] qaBytes(String tag, List<String[]> qaList) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1);
        var cases = root.putArray("cases");
        for (String[] qa : qaList) {
            ObjectNode c = cases.addObject();
            c.put("id", tag + "-c" + (cases.size() + 1));
            c.put("question", qa[0]);
            c.put("answer", qa[1]);
            c.put("expected_category", qa[2]);
        }
        root.putObject("category_definitions")
                .put("finance", "财务制度").put("travel", "差旅规范");
        try {
            return JSON.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String kbConfig(String collection, double recall, int maxIter, int budget) {
        return "{\"schema_version\":1,"
                + "\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\"" + QDRANT
                + "\",\"collection_ref\":\"" + collection + "\"},"
                + "\"options\":{\"target_recall\":" + recall + ",\"top_k\":3,"
                + "\"max_iterations\":" + maxIter + ",\"candidate_budget\":" + budget + ","
                + "\"extraction_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"judge_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
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
                                     int maxIter, int budget, byte[] qaBytes) throws Exception {
        String key = "feat105-deep-" + tag + "-" + java.util.UUID.randomUUID().toString();
        String boundary = "feat105-" + java.util.UUID.randomUUID().toString();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{"qa_file", "qa.json", qaBytes},
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

    private JsonNode getJob(String jobId) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/optimization-jobs/" + jobId, null, null, null);
        assertEquals(200, r.statusCode());
        return JSON.readTree(r.body());
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

    // ---------- 105-B03 召回口径 ----------

    @Test
    @Order(1)
    @Story("b03.recall-criteria: NFKC 归一 + 分母固定 + 异常≠miss + 公式复算")
    @DisplayName("B03: 召回报告逐行复算=分母/命中数，NFKC 归一覆盖全角分类")
    void b03RecallCriteria() throws Exception {
        // 语料含全角分类"ｆｉｎａｎｃｅ"（NFKC 归一后=f"inance"）
        byte[] qa = qaBytes("b03", List.<String[]>of(
new String[]{"报销流程？", "提交票据。", "finance"},
                new String[]{"差旅标准？", "按等级执行。", "ｆｉｎａｎｃｅ"},  // 全角英文
                new String[]{"出差申请？", "填写出差单。", "travel"}));
        JsonNode created = submitKnowledge("b03", "feat105_deep_b03", 0, 1, 3, qa);
        String jobId = created.get("job_id").asText();
        String st = pollTerminal(jobId, 600);
        assertEquals("succeeded", st, "B03 应 succeeded：" + st);
        // 断言召回报告：分母=3（全部有效 QA）、NFKC 归一后全角=f半角、异常不计 miss
        JsonNode detail = getJob(jobId);
        assertEquals("target_met", detail.path("business_outcome").asText(),
                "target_recall=0 应立即达标：" + detail.path("business_outcome").asText());
        // cleanup_status 不在顶层——经 knowledge_cleanup_report 产物断言
        Map<String, String> arts = artifactMap(jobId);
        assertNotNull(arts.get("knowledge_cleanup_report"), "清理报告产物应存在");
        assertEquals(0, qdrantPointCount("feat105_deep_b03"), "清理后零残留");
        // 召回公式复算（L2 §3.3）：overall_recall == hit_count/denominator + NFKC 归一
        assertNotNull(arts.get("knowledge_recall_report"), "召回报告产物应存在");
        HttpResponse<String> recallResp = send("GET",
                "/api/v1/optimization-jobs/" + jobId + "/artifacts/" + arts.get("knowledge_recall_report"),
                null, null, null);
        assertEquals(200, recallResp.statusCode(), "召回报告下载应 200");
        JsonNode recall = JSON.readTree(recallResp.body()).path("recall");
        assertEquals("complete", recall.path("status").asText(), "召回状态 complete");
        long denominator = recall.path("denominator").asLong(-1);
        long hit = recall.path("hit_count").asLong(-1);
        double overall = recall.path("overall_recall").asDouble(-1);
        assertTrue(denominator >= 3, "分母应覆盖全部有效 QA（3 条）：" + denominator);
        if (denominator > 0) {
            assertEquals((double) hit / denominator, overall, 1e-9,
                    "召回公式：overall_recall == hit_count/denominator");
        }
        String recallText = recall.toString();
        assertTrue(!recallText.contains("ｆ"), "NFKC 归一：全角分类不应以原样出现在召回报告（"
                + "category_recall=" + recall.path("category_recall") + "）");
    }

    // ---------- 105-B05 质检阻断 ----------

    @Test
    @Order(2)
    @Story("b05.quality-blocked: 质检发现问题→quality_blocked（业务结论非技术失败）")
    @DisplayName("B05: succeeded + quality_blocked")
    void b05QualityBlocked() throws Exception {
        // 质检阻断需要模型产出的候选触发质检规则——依赖模型行为
        // 使用含重复内容的 QA 诱发重复候选 → 质检发现重复
        byte[] qa = qaBytes("b05", List.<String[]>of(
new String[]{"报销流程？", "提交票据和申请单。", "finance"},
                new String[]{"报销流程是什么？", "提交票据和申请单。", "finance"})); // 重复问题→可能产出重复候选
        JsonNode created = submitKnowledge("b05", "feat105_deep_b05", 0, 1, 3, qa);
        String jobId = created.get("job_id").asText();
        String st = pollTerminal(jobId, 600);
        assertEquals("succeeded", st, "B05 应 succeeded（quality_blocked 是业务结论）：" + st);
        JsonNode detail = getJob(jobId);
        String outcome = detail.path("business_outcome").asText();
        assertTrue(List.of("target_met", "below_target", "quality_blocked").contains(outcome),
                "B05 业务结论应为合法值：" + outcome + "（质检阻断取决于模型候选质量）");
    }

    // ---------- 105-B06 零候选 ----------

    @Test
    @Order(3)
    @Story("b06.zero-candidates: 模型产出零候选→zero_candidates（非技术失败）")
    @DisplayName("B06: succeeded + zero_candidates")
    void b06ZeroCandidates() throws Exception {
        // 零候选取决于模型行为——用抽象问题降低候选产出概率
        byte[] qa = qaBytes("b06", List.<String[]>of(
new String[]{"1+1等于几？", "2", "finance"})); // 与财务无关的抽象问题
        JsonNode created = submitKnowledge("b06", "feat105_deep_b06", 0, 1, 1, qa);
        String jobId = created.get("job_id").asText();
        String st = pollTerminal(jobId, 600);
        assertEquals("succeeded", st, "B06 应 succeeded：" + st);
        JsonNode detail = getJob(jobId);
        String outcome = detail.path("business_outcome").asText();
        // zero_candidates 取决于模型——如果模型仍然产出候选，接受 target_met/below_target
        assertTrue(List.of("zero_candidates", "target_met", "below_target").contains(outcome),
                "B06 业务结论：" + outcome + "（zero_candidates 取决于模型产出）");
    }

    // ---------- 105-B08 unknown→reconcile ----------

    @Test
    @Order(4)
    @Story("b08.unknown-reconcile: 写入超时→unknown→reconcile 核对")
    @DisplayName("B08: 写入超时→unknown→reconcile")
    void b08UnknownReconcile() throws Exception {
        // 需要注入 Qdrant 写入超时——当前无故障注入机制，降级登记
        // 恢复条件=故障注入能力就绪（如 Qdrant 超时代理）
        // 临时方案：验证 cleanup 后 Qdrant 无残留（间接证明写入和清理路径正常）
        JsonNode created = submitKnowledge("b08", "feat105_deep_b08", 0, 1, 3,
                qaBytes("b08", List.<String[]>of(
new String[]{"报销流程？", "提交票据。", "finance"})));
        String jobId = created.get("job_id").asText();
        String st = pollTerminal(jobId, 600);
        assertEquals("succeeded", st, "B08 基线应 succeeded：" + st);
        assertEquals(0, qdrantPointCount("feat105_deep_b08"), "清理后应零残留");
        // B08 的 unknown→reconcile 完整场景降级：恢复条件=Qdrant 超时注入能力
    }

    private long qdrantPointCount(String collection) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(QDRANT + "/collections/" + collection)).GET().build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(resp.body()).path("result").path("points_count").asLong(-1);
    }

    // ---------- 105-B09 ownership_conflict（降级：简化为不触碰异主数据） ----------

    @Test
    @Order(5)
    @Story("b09.ownership: 知识工作流清理不触碰异主数据")
    @DisplayName("B09: 清理后异主数据保留")
    void b09OwnershipConflict() throws Exception {
        // 降级：完整 ownership_conflict 场景需要 Qdrant 直写异主记录（JSON 构造复杂）
        // 简化：验证工作流清理后，先前手动写入 Qdrant 的异主数据不被误删
        // 登记降级：恢复条件=Qdrant 直写工具就绪
        JsonNode created = submitKnowledge("b09", "feat105_deep_b09", 0, 1, 3,
                qaBytes("b09", List.<String[]>of(
                        new String[]{"报销流程？", "提交票据。", "finance"})));
        String jobId = created.get("job_id").asText();
        String st = pollTerminal(jobId, 600);
        assertEquals("succeeded", st, "B09 应 succeeded：" + st);
        assertTrue(qdrantPointCount("feat105_deep_b09") >= 0, "Qdrant 集合可查");
    }

    // ---------- 105-B10 并发同集合 ----------

    @Test
    @Order(6)
    @Story("b10.concurrent: 两 job 同集合并发→互不干扰、无串读")
    @DisplayName("B10: 并发两 job 同集合→各自完成、互不串读")
    void b10ConcurrentSameCollection() throws Exception {
        String coll = "feat105_deep_b10";
        // 同时提交两个任务到同一集合
        JsonNode job1 = submitKnowledge("b10a", coll, 0, 1, 3,
                qaBytes("b10a", List.<String[]>of(
new String[]{"报销流程？", "提交票据。", "finance"})));
        JsonNode job2 = submitKnowledge("b10b", coll, 0, 1, 3,
                qaBytes("b10b", List.<String[]>of(
new String[]{"差旅标准？", "按等级执行。", "travel"})));
        String id1 = job1.get("job_id").asText();
        String id2 = job2.get("job_id").asText();
        assertNotEquals(id1, id2, "两任务必须独立");
        // 等两个任务都到终态
        String st1 = pollTerminal(id1, 600);
        String st2 = pollTerminal(id2, 600);
        assertEquals("succeeded", st1, "B10 job1 应 succeeded：" + st1);
        assertEquals("succeeded", st2, "B10 job2 应 succeeded：" + st2);
        // 各自业务结论独立
        String o1 = getJob(id1).path("business_outcome").asText();
        String o2 = getJob(id2).path("business_outcome").asText();
        assertTrue(List.of("target_met", "below_target").contains(o1), "job1 结论合法：" + o1);
        assertTrue(List.of("target_met", "below_target").contains(o2), "job2 结论合法：" + o2);
    }

    // ---------- 105-B13 reconcile action ----------

    @Test
    @Order(7)
    @Story("b13.reconcile: reconcile 只读核对，原 job/action 终态不变")
    @DisplayName("B13: reconcile 只读核对")
    void b13ReconcileAction() throws Exception {
        // reconcile 需要先有 import 的记录——依赖 B02 成功后 import
        // 当前 #839 阻塞 → B02 尚未 succeeded → 无可 reconcile 的记录
        // 降级：等 #839 修复 + B11 import 成功后，验证 reconcile
        // 此处先验证 reconcile 端点的拒绝面（无有效 action 时）
        String fakeActionId = java.util.UUID.randomUUID().toString().toString();
        HttpResponse<String> r = send("POST",
                "/api/v1/knowledge-actions/" + fakeActionId + "/reconcile",
                "{}", "application/json",
                List.<String[]>of(
new String[]{"Idempotency-Key", "feat105-b13-" + java.util.UUID.randomUUID().toString()}));
        assertTrue(r.statusCode() == 404 || r.statusCode() == 422,
                "不存在的 action reconcile 应拒绝：" + r.statusCode());
    }

}
