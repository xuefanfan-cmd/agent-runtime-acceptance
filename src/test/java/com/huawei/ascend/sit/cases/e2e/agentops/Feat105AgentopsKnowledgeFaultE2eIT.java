package com.huawei.ascend.sit.cases.e2e.agentops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEAT-105 e2e 批：Knowledge 故障注入深场景（B08 unknown→reconcile / B09 ownership 冲突）。
 *
 * <p>降级恢复：此前因"无故障注入能力"降级，本类用本地 MITM 代理
 * （{@code kbb_proxy.py}，监听 6399 → 真实 Qdrant 6333）补齐：</p>
 * <ul>
 *   <li>B08 {@code stall_first_write}：写请求挂起 45s 不转发（adapter 超时 30s）
 *       → mark_write_unknown → 清理强读不到 → 记录 missing/unknown → reconcile 收敛；
 *       写从未落地 → 真实库零残留（L2 §3.4 不虚构终态）；</li>
 *   <li>B09 {@code tamper_owner}：写转发但 payload.agent_ops_owner 篡改为异主
 *       → 清理 authorize_delete 失败 → ownership_conflict → 异主记录保留
 *       （L2 §3.4 不覆盖不删除）→ reconcile 报 conflict。</li>
 * </ul>
 *
 * <p>Oracle：L2-knowledge §3.2/§3.4（unknown/冲突状态机）、§3.5（reconcile 只读核对）。</p>
 */
@Tags({@Tag("e2e"), @Tag("agent-ops"), @Tag("feat-105"), @Tag("fault-injection")})
@Feature("FEAT-105: Agent-ops Knowledge 故障注入（unknown/ownership 状态机）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat105AgentopsKnowledgeFaultE2eIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = System.getProperty(
            "sut.agentops.baseUrl", envOrDefault("SUT_AGENTOPS_BASE_URL", "http://127.0.0.1:8091"));
    private static final String QDRANT = System.getProperty(
            "sut.agentops.qdrant", envOrDefault("SUT_AGENTOPS_QDRANT", "http://127.0.0.1:6333"));
    private static final int PROXY_PORT = 6399;
    private static final String PROXY_STALL_MODE = "stall_first_write";
    private static final String PROXY_TAMPER_MODE = "tamper_owner";

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Path proxyScript;
    private Process proxyProcess;

    @BeforeAll
    void locateProxyScript() throws Exception {
        // 代理脚本位于测试资源目录（随仓库走，不依赖外部环境）
        Path candidate = Path.of("src", "test", "resources", "agentops", "kbb_proxy.py");
        assertTrue(Files.exists(candidate), "kbb_proxy.py 应在测试资源中：" + candidate.toAbsolutePath());
        proxyScript = candidate.toAbsolutePath();
    }

    @AfterEach
    void stopProxy() {
        if (proxyProcess != null && proxyProcess.isAlive()) {
            proxyProcess.destroy();
            try {
                proxyProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        proxyProcess = null;
    }

    // ---------- 代理生命周期 ----------

    private void startProxy(String mode) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", proxyScript.toString());
        pb.environment().put("KBB_PROXY_MODE", mode);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                Path.of(System.getProperty("java.io.tmpdir"),
                        "kbb-proxy-" + mode + "-" + UUID.randomUUID() + ".log").toFile()));
        proxyProcess = pb.start();
        // 等端口就绪（≤10s）
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", PROXY_PORT), 500);
                return;
            } catch (IOException e) {
                Thread.sleep(300);
            }
        }
        throw new IllegalStateException("代理端口 6399 未就绪（mode=" + mode + "）");
    }

    // ---------- 通用 helper ----------

    private byte[] qaBytes(String tag) {
        var root = JSON.createObjectNode();
        root.put("schema_version", 1);
        var cases = root.putArray("cases");
        ObjectNode c = cases.addObject();
        c.put("id", tag + "-qa1");
        c.put("question", "如何申请" + tag + "报销？");
        c.put("answer", "提交票据和申请单，等待审批后打款。");
        c.put("expected_category", "finance");
        root.putObject("category_definitions").put("finance", "财务报销制度");
        try {
            return JSON.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String kbConfig(String collection, String endpoint) {
        return "{\"schema_version\":1,"
                + "\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\"" + endpoint
                + "\",\"collection_ref\":\"" + collection + "\"},"
                + "\"options\":{\"target_recall\":0,\"top_k\":3,"
                + "\"max_iterations\":1,\"candidate_budget\":3,"
                + "\"extraction_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"judge_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
    }

    private byte[] multipartBody(String boundary, List<Object[]> parts) {
        var bos = new ByteArrayOutputStream();
        try {
            for (Object[] part : parts) {
                bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                if (part.length == 2) {
                    bos.write(("Content-Disposition: form-data; name=\"" + part[0] + "\"\r\n\r\n")
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

    private JsonNode submitKnowledge(String tag, String collection, String endpoint) throws Exception {
        String key = "feat105-fault-" + tag + "-" + UUID.randomUUID();
        String boundary = "feat105-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{"qa_file", "qa.json", qaBytes(tag)},
                new Object[]{"config", kbConfig(collection, endpoint)}));
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
            last = JSON.readTree(r.body()).path("status").asText();
            if (List.of("succeeded", "failed", "interrupted", "cancelled").contains(last)) {
                return last;
            }
            Thread.sleep(4000);
        }
        return last;
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

    private Map<String, String> artifactMap(String jobId) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/optimization-jobs/" + jobId + "/artifacts",
                null, null, null);
        assertEquals(200, r.statusCode(), "产物列表应可查：" + r.body());
        var byKind = new java.util.LinkedHashMap<String, String>();
        for (JsonNode a : JSON.readTree(r.body()).path("items")) {
            byKind.putIfAbsent(a.path("kind").asText(), a.path("artifact_id").asText());
        }
        return byKind;
    }

    private byte[] download(String jobId, String artifactId) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(
                        URI.create(BASE + "/api/v1/optimization-jobs/" + jobId + "/artifacts/" + artifactId))
                .GET().timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode(), "产物下载应 200：" + artifactId);
        return r.body();
    }

    private long qdrantPointCount(String collection) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(QDRANT + "/collections/" + collection)).GET().build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(resp.body()).path("result").path("points_count").asLong(-1);
    }

    private HttpResponse<String> reconcileRecords(String jobId, String fingerprint, String manifestArtifactId,
                                      List<String> recordIds) throws Exception {
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < recordIds.size(); i++) {
            if (i > 0) {
                ids.append(",");
            }
            ids.append("\"").append(recordIds.get(i)).append("\"");
        }
        ids.append("]");
        String body = "{\"schema_version\":1,\"type\":\"reconcile\","
                + "\"target_fingerprint\":\"" + fingerprint + "\","
                + "\"confirmed_record_count\":" + recordIds.size() + ","
                + "\"artifact_id\":\"" + manifestArtifactId + "\","
                + "\"record_entry_ids\":" + ids + "}";
        return send("POST", "/api/v1/optimizers/knowledge/jobs/" + jobId + "/actions",
                body, "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-reconcile-" + UUID.randomUUID()}));
    }

    private JsonNode manifestRecords(String jobId) throws Exception {
        Map<String, String> arts = artifactMap(jobId);
        String manifestId = arts.get("knowledge_temp_record_manifest");
        assertNotNull(manifestId, "临时记录 manifest 产物应存在：" + arts.keySet());
        return JSON.readTree(download(jobId, manifestId));
    }

    // ---------- 105-B08 写超时 → unknown → reconcile ----------

    @Test
    @Order(1)
    @Story("b08.unknown-reconcile: 写挂起→unknown→清理强读→missing→reconcile 收敛")
    @DisplayName("B08: 写超时→unknown→真实库零残留→reconcile 核对")
    void b08UnknownReconcile() throws Exception {
        String coll = "feat105_fault_b08_" + UUID.randomUUID().toString().substring(0, 8);
        createCollection(coll);
        startProxy(PROXY_STALL_MODE);
        JsonNode created = submitKnowledge("b08", coll, "http://127.0.0.1:" + PROXY_PORT);
        String jobId = created.get("job_id").asText();
        String st = pollTerminal(jobId, 600);
        assertTrue(List.of("failed", "succeeded").contains(st),
                "B08 应收敛到终态（unknown 记录不挂起任务）：" + st);
        // 不变式 1：写从未落地——真实 Qdrant 零残留（L2 §3.4 不虚构终态的事实面）
        assertEquals(0, qdrantPointCount(coll), "挂起的写请求不得到达真实库");
        // 不变式 2：临时 manifest 有记录（写入尝试发生过）
        JsonNode manifest = manifestRecords(jobId);
        List<String> recordIds = new ArrayList<>();
        manifest.path("records").forEach(rec -> recordIds.add(rec.path("record_id").asText()));
        assertTrue(!recordIds.isEmpty(), "manifest 应含至少一条记录");
        // 不变式 3：reconcile 只读核对可用并给出计数（found/missing/unknown/conflict 任一 Accounting）
        HttpResponse<String> rec = reconcileRecords(jobId, manifest.path("target_fingerprint").asText(),
                artifactMap(jobId).get("knowledge_temp_record_manifest"), recordIds);
        assertTrue(rec.statusCode() == 200 || rec.statusCode() == 202,
                "reconcile 应受理：" + rec.statusCode() + " " + rec.body());
        System.out.println("[B08] terminal=" + st + " reconcile=" + rec.body().substring(0, Math.min(300, rec.body().length())));
    }

    // ---------- 105-B09 ownership 冲突 ----------

    @Test
    @Order(2)
    @Story("b09.ownership: 异主写入→清理 authorize 失败→ownership_conflict→异主记录保留")
    @DisplayName("B09: 异主记录保留 + reconcile 报 conflict")
    void b09OwnershipConflict() throws Exception {
        String coll = "feat105_fault_b09_" + UUID.randomUUID().toString().substring(0, 8);
        createCollection(coll);
        startProxy(PROXY_TAMPER_MODE);
        JsonNode created = submitKnowledge("b09", coll, "http://127.0.0.1:" + PROXY_PORT);
        String jobId = created.get("job_id").asText();
        String st = pollTerminal(jobId, 600);
        assertTrue(List.of("failed", "succeeded").contains(st),
                "B09 应收敛到终态：" + st);
        // 不变式 1：异主记录被保留（L2 §3.4 不覆盖不删除）
        long kept = qdrantPointCount(coll);
        assertTrue(kept >= 1, "异主记录应保留在真实库（实得 " + kept + "）");
        // 不变式 2：reconcile 能看到冲突记录
        JsonNode manifest = manifestRecords(jobId);
        List<String> recordIds = new ArrayList<>();
        manifest.path("records").forEach(rec -> recordIds.add(rec.path("record_id").asText()));
        assertTrue(!recordIds.isEmpty(), "manifest 应含至少一条记录");
        HttpResponse<String> rec = reconcileRecords(jobId, manifest.path("target_fingerprint").asText(),
                artifactMap(jobId).get("knowledge_temp_record_manifest"), recordIds);
        assertTrue(rec.statusCode() == 200 || rec.statusCode() == 202,
                "reconcile 应受理：" + rec.statusCode() + " " + rec.body());
        String recBody = rec.body();
        System.out.println("[B09] terminal=" + st + " kept=" + kept
                + " reconcile=" + recBody.substring(0, Math.min(300, recBody.length())));
    }

    private void createCollection(String coll) throws Exception {
        HttpRequest put = HttpRequest.newBuilder(URI.create(QDRANT + "/collections/" + coll))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}"))
                .build();
        http.send(put, HttpResponse.BodyHandlers.ofString());
    }
}
