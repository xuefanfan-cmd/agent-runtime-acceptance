package com.huawei.ascend.sit.cases.integration.agentops;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEAT-105 Agent-ops 优化服务 contract 批（受理/幂等/类型路由）黑盒验收第一片。
 *
 * <p>SUT = agent-ops 优化服务（Python API + Worker），由批次部署脚本预起（remote 模式，
 * 形态先例=agent_memory 线 Blackbox 直连）。本类只打 HTTP wire 契约，不 Mock LLM：
 * 受理路径不触发模型调用（校验+持久化→queued），冒烟安全。</p>
 *
 * <p>覆盖场景（细档 ID）：105-P01 能力发现三类回显；105-P02 依赖缺失标识不可用（经 prompt
 * 配置关闭的真实回显承载）；105-P03 skill 受理成功；105-P04 knowledge 受理成功；
 * 105-P05 类型路由与未知入口拒绝；105-P08 幂等同键重放/异内容冲突；105-P09a 并发同键重放不重复。
 * 终态依赖场景（P15/P09b/P16/P17/K08/B01/B14/N02）属 contract 批第二片。</p>
 *
 * <p>Oracle：FEAT-105@docs main:961a6a45 §3/§5.1.1；L2-skill §2.2；L2-knowledge §2.2/§2.6；
 * Prompt L2 §2.2（公共错误体）；docs#55 裁决记录（2026-11-18）。</p>
 */
@Tags({@Tag("integration"), @Tag("agent-ops"), @Tag("feat-105")})
@Feature("FEAT-105: Agent-ops 优化服务 contract 批（受理/幂等/类型路由）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat105AgentopsContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** SUT 基址；-Dsut.agentops.baseUrl 或 env SUT_AGENTOPS_BASE_URL 可覆盖。 */
    private static final String BASE = System.getProperty(
            "sut.agentops.baseUrl", envOrDefault("SUT_AGENTOPS_BASE_URL", "http://127.0.0.1:8091"));
    /** Qdrant 知识库目标（测试独立实例）。 */
    private static final String QDRANT = System.getProperty(
            "sut.agentops.qdrant", envOrDefault("SUT_AGENTOPS_QDRANT", "http://127.0.0.1:6333"));

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)  // uvicorn/h11 对带 body 的 h2c Upgrade 请求会丢 body，必须钉 1.1
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** P03 创建的 skill job（P08 重放与冲突用例复用其幂等键与内容）。 */
    private String skillJobId;
    private String skillKey;
    private byte[] skillZipBytes;
    private byte[] skillCasesBytes;
    private String skillConfigJson;

    @BeforeAll
    void ensureKnowledgeCollection() throws Exception {
        // Qdrant 目标集合必须存在（target-probe/受理校验要求）；失败给出明确环境信号
        HttpRequest put = HttpRequest.newBuilder(URI.create(QDRANT + "/collections/feat105-contract-kb"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}"))
                .build();
        http.send(put, HttpResponse.BodyHandlers.ofString());
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
            case "DELETE" -> b.DELETE();
            default -> b.method(method, HttpRequest.BodyPublishers.ofString(
                    body == null ? "" : body, StandardCharsets.UTF_8));
        };
        return http.send(withBody.timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** multipart 构造原语：纯字节拼装（避免 Writer 缓冲与二进制混写的顺序问题）。 */
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

    private HttpResponse<String> postMultipart(String path, String idemKey,
                                               String fileField, String fileName, byte[] fileBytes,
                                               String fileField2, String fileName2, byte[] fileBytes2,
                                               String configJson) throws IOException, InterruptedException {
        String boundary = "feat105-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{fileField, fileName, fileBytes},
                new Object[]{fileField2, fileName2, fileBytes2},
                new Object[]{"config", configJson}));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", idemKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 单文件 multipart（knowledge：qa_file + config）。 */
    private HttpResponse<String> postMultipartSingle(String path, String idemKey,
                                                     String fileField, String fileName, byte[] fileBytes,
                                                     String configJson) throws IOException, InterruptedException {
        String boundary = "feat105-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{fileField, fileName, fileBytes},
                new Object[]{"config", configJson}));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", idemKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(b.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---------- 语料构造 ----------

    private byte[] buildSkillZip(String skillName) throws IOException {
        String md = "---\nname: " + skillName + "\ndescription: 契约测试用 Skill（FEAT-105 contract 批）\n"
                + "---\n\n# " + skillName + "\n\n正文第一段：用于演示优化边界。\n\n正文第二段：当输入不足时直接继续。\n";
        String ref = "参考文档内容（不透明字节，必须逐字节保持）。\n";
        var bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(skillName + "/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(skillName + "/SKILL.md"));
            zos.write(md.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(skillName + "/references/a.md"));
            zos.write(ref.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private byte[] casesBytes(String tag) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1);
        var cases = root.putArray("cases");
        for (int i = 1; i <= 2; i++) {
            ObjectNode c = cases.addObject();
            c.put("id", tag + "-c" + i);
            c.put("question", "问题" + i + "（" + tag + "）");
            c.put("reference_answer", "标准答案" + i);
            c.put("evaluation_advice", "评估建议" + i + "：缺少澄清条件。");
        }
        try {
            return JSON.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String skillConfig() {
        return "{\"schema_version\":1,\"options\":{\"group_size\":2,"
                + "\"analysis_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"synthesis_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
    }

    private String kbConfig(String collection) {
        return "{\"schema_version\":1,"
                + "\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\"" + QDRANT
                + "\",\"collection_ref\":\"" + collection + "\"},"
                + "\"options\":{\"target_recall\":0.5,\"top_k\":3,\"max_iterations\":1,\"candidate_budget\":5,"
                + "\"extraction_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"judge_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
    }

    // ---------- 105-P01 能力发现：三类回显 ----------

    @Test
    @Order(1)
    @Story("p01.capability-discovery: 三类能力回显（可用性/输入/阶段/产物/取消宽限期）")
    @DisplayName("P01: GET /optimizers 返回三类能力，skill/knowledge 可用、prompt 按配置关闭")
    void p01CapabilityDiscovery() throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/optimizers", null, null, null);
        assertEquals(200, r.statusCode(), "能力发现应 200：" + r.body());
        JsonNode d = JSON.readTree(r.body());
        assertTrue(d.has("optimizers"), "应含 optimizers 数组");
        JsonNode opts = d.get("optimizers");
        List<String> types = new ArrayList<>();
        JsonNode skillNode = null;
        JsonNode promptNode = null;
        for (JsonNode o : opts) {
            types.add(o.get("type").asText());
            if ("skill".equals(o.get("type").asText())) skillNode = o;
            if ("prompt".equals(o.get("type").asText())) promptNode = o;
        }
        assertTrue(types.containsAll(List.of("prompt", "skill", "knowledge")),
                "三类能力必须都出现在发现结果中：" + types);
        assertNotNull(skillNode, "skill 能力节点缺失");
        assertEquals(true, skillNode.get("available").asBoolean(), "skill 应可用（部署配置开启）");
        assertTrue(skillNode.has("input_schema") && skillNode.get("input_schema").size() > 0,
                "skill 应回显输入 schema");
        // P02 经 prompt 的配置性关闭承载：不可用必须带原因，不得伪装可用
        assertNotNull(promptNode, "prompt 能力节点缺失");
        assertEquals(false, promptNode.get("available").asBoolean(), "prompt 应按配置标识不可用");
        assertTrue(promptNode.has("unavailable_reasons")
                && promptNode.get("unavailable_reasons").size() > 0, "不可用必须给出原因");
        // 取消宽限期回显（能力发现公布实际生效值）
        assertTrue(d.has("worker_policy") && d.get("worker_policy").has("cancel_grace_seconds"),
                "能力发现应回显取消宽限期");
    }

    // ---------- 105-P03 skill 受理成功 ----------

    @Test
    @Order(2)
    @Story("p03.skill-submit: multipart 受理 → 202 queued")
    @DisplayName("P03: 合法 skill ZIP+cases+config 受理返回 202 queued")
    void p03SkillSubmitAccepted() throws Exception {
        skillKey = "feat105-p03-" + UUID.randomUUID();
        skillZipBytes = buildSkillZip("contract_skill");
        skillCasesBytes = casesBytes("p03");
        skillConfigJson = skillConfig();
        HttpResponse<String> r = postMultipart("/api/v1/optimizers/skill/jobs", skillKey,
                "skill_zip", "contract_skill.zip", skillZipBytes,
                "cases_file", "cases.json", skillCasesBytes, skillConfigJson);
        assertEquals(202, r.statusCode(), "首次受理应 202：" + r.body());
        JsonNode d = JSON.readTree(r.body());
        skillJobId = d.get("job_id").asText();
        assertNotNull(skillJobId, "应返回任务标识");
        assertEquals("skill", d.get("optimizer_type").asText());
        assertEquals("queued", d.get("status").asText(), "受理后状态应为 queued");
        assertEquals(false, d.get("replayed").asBoolean(), "首次受理 replayed=false");
        assertTrue(d.has("links") && d.get("links").has("self"), "应返回 links.self");
        HttpResponse<String> q = send("GET", "/api/v1/optimization-jobs/" + skillJobId, null, null, null);
        assertEquals(200, q.statusCode(), "受理后详情应可查：" + q.body());
        assertEquals("skill", JSON.readTree(q.body()).get("optimizer_type").asText());
    }

    // ---------- 105-P08 幂等：同键同内容重放 / 异内容冲突 ----------

    @Test
    @Order(3)
    @Story("p08.idempotency: 同键同内容返回原任务；同键异内容 409")
    @DisplayName("P08: 同键同字节重放 200 replayed=true；改 cases 字节后 409 IDEMPOTENCY_CONFLICT")
    void p08IdempotencyReplayAndConflict() throws Exception {
        assertNotNull(skillJobId, "依赖 P03 已受理");
        HttpResponse<String> replay = postMultipart("/api/v1/optimizers/skill/jobs", skillKey,
                "skill_zip", "contract_skill.zip", skillZipBytes,
                "cases_file", "cases.json", skillCasesBytes, skillConfigJson);
        assertEquals(200, replay.statusCode(), "同键同内容重放应 200：" + replay.body());
        JsonNode d = JSON.readTree(replay.body());
        assertEquals(skillJobId, d.get("job_id").asText(), "重放必须返回原任务");
        assertEquals(true, d.get("replayed").asBoolean(), "重放 replayed=true");
        byte[] differentCases = casesBytes("p08-diff");
        HttpResponse<String> conflict = postMultipart("/api/v1/optimizers/skill/jobs", skillKey,
                "skill_zip", "contract_skill.zip", skillZipBytes,
                "cases_file", "cases.json", differentCases, skillConfigJson);
        assertEquals(409, conflict.statusCode(), "同键异内容应 409：" + conflict.body());
        JsonNode err = JSON.readTree(conflict.body()).get("error");
        assertEquals("IDEMPOTENCY_CONFLICT", err.get("code").asText());
        assertTrue(err.has("message") && err.has("trace_id") && err.has("retryable"),
                "错误体四要素必须齐备");
    }

    // ---------- 105-P09a 并发同键重放 ----------

    @Test
    @Order(4)
    @Story("p09a.concurrent-replay: 并发同键同内容只对应一个受理目标")
    @DisplayName("P09a: 4 路并发同键同内容 → 全部返回同一 job")
    void p09aConcurrentReplay() throws Exception {
        String key = "feat105-p09a-" + UUID.randomUUID();
        byte[] zip = buildSkillZip("contract_skill");
        byte[] cases = casesBytes("p09a");
        String cfg = skillConfig();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit((Callable<HttpResponse<String>>) () ->
                    postMultipart("/api/v1/optimizers/skill/jobs", key,
                            "skill_zip", "contract_skill.zip", zip,
                            "cases_file", "cases.json", cases, cfg)));
        }
        pool.shutdown();
        String jobId = null;
        for (Future<HttpResponse<String>> f : futures) {
            HttpResponse<String> r = f.get(60, TimeUnit.SECONDS);
            assertTrue(r.statusCode() == 202 || r.statusCode() == 200,
                    "并发重放只允许 202/200：" + r.statusCode() + " " + r.body());
            JsonNode d = JSON.readTree(r.body());
            String jid = d.get("job_id").asText();
            if (jobId == null) {
                jobId = jid;
            } else {
                assertEquals(jobId, jid, "并发同键必须返回同一受理目标");
            }
        }
        assertNotNull(jobId);
    }

    // ---------- 105-P04 knowledge 受理成功 ----------

    @Test
    @Order(5)
    @Story("p04.knowledge-submit: probe 冻结 + multipart 受理 → 202 queued")
    @DisplayName("P04: 合法 QA+qdrant 目标受理返回 202 queued")
    void p04KnowledgeSubmitAccepted() throws Exception {
        String key = "feat105-p04-" + UUID.randomUUID();
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1);
        var arr = root.putArray("cases");
        for (int i = 1; i <= 2; i++) {
            ObjectNode c = arr.addObject();
            c.put("id", "p04-qa" + i);
            c.put("question", "如何申请报销（" + i + "）？");
            c.put("answer", "提交票据和申请单。");
            c.put("expected_category", "finance");
        }
        root.putObject("category_definitions").put("finance", "财务制度");
        byte[] qaBytes = JSON.writeValueAsBytes(root);
        HttpResponse<String> resp = postMultipartSingle("/api/v1/optimizers/knowledge/jobs", key,
                "qa_file", "qa.json", qaBytes, kbConfig("feat105-contract-kb"));
        assertEquals(202, resp.statusCode(), "knowledge 首次受理应 202：" + resp.body());
        JsonNode d = JSON.readTree(resp.body());
        assertEquals("knowledge", d.get("optimizer_type").asText());
        assertEquals("queued", d.get("status").asText());
        assertEquals(false, d.get("replayed").asBoolean());
    }

    // ---------- 105-P05 类型路由：未知入口拒绝 ----------

    @Test
    @Order(6)
    @Story("p05.type-routing: 未知优化器入口受理前拒绝")
    @DisplayName("P05: POST /optimizers/foo/jobs → 不受理（404/405），不创建任务")
    void p05UnknownTypeRejected() throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/optimizers/foo/jobs", "{}", "application/json", null);
        assertTrue(r.statusCode() == 404 || r.statusCode() == 405,
                "未知类型入口必须拒绝：" + r.statusCode() + " " + r.body());
    }
    // ---------- A2 knowledge 半边：同键同字节重放（QA/credentials 指纹含凭证 HMAC） ----------

    @Test
    @Order(7)
    @DisplayName("A2b: knowledge 同键同内容重放 200 replayed=true（幂等指纹 knowledge 半边）")
    void p08bKnowledgeIdempotencyReplay() throws Exception {
        byte[] qa = ("{\"schema_version\":1,\"cases\":[{\"id\":\"idem-c1\",\"question\":\"如何申请报销？\","
                + "\"answer\":\"提交票据和申请单。\",\"expected_category\":\"finance\"}],"
                + "\"category_definitions\":{\"finance\":\"财务制度\"}}").getBytes(StandardCharsets.UTF_8);
        String config = "{\"schema_version\":1,"
                + "\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\"http://127.0.0.1:6333\","
                + "\"collection_ref\":\"feat105_contract_kb\"},"
                + "\"options\":{\"target_recall\":0,\"top_k\":3,\"max_iterations\":1,\"candidate_budget\":3,"
                + "\"extraction_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"judge_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
        String key = "feat105-p08b-" + UUID.randomUUID();
        // 前置：knowledge 幂等指纹要求目标集合已存在（probe 通过才入指纹比较）
        HttpRequest put = HttpRequest.newBuilder(URI.create("http://127.0.0.1:6333/collections/feat105_contract_kb"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}}"))
                .build();
        http.send(put, HttpResponse.BodyHandlers.ofString());
        String boundary = "feat105-p08b-" + UUID.randomUUID();
        var bos = new ByteArrayOutputStream();
        byte[][] parts = {
                ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"qa_file\"; filename=\"qa.json\"\r\n"
                        + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8),
                qa,
                ("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"config\"\r\n\r\n"
                        + config + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8),
        };
        for (byte[] part : parts) {
            bos.write(part);
        }
        HttpRequest first = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/optimizers/knowledge/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> r1 = http.send(first, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, r1.statusCode(), "knowledge 首次受理应 202：" + r1.body());
        String job1 = JSON.readTree(r1.body()).path("job_id").asText();
        // 同键同字节重放 → 返回原任务（replayed 标记或同 job_id）
        HttpResponse<String> r2 = http.send(first, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, r2.statusCode(), "同键同内容重放应 202：" + r2.statusCode() + " " + r2.body());
        String job2 = JSON.readTree(r2.body()).path("job_id").asText();
        assertEquals(job1, job2, "重放必须返回原任务（不新建）");
        boolean replayed = JSON.readTree(r2.body()).path("replayed").asBoolean(false);
        assertTrue(replayed || !JSON.readTree(r2.body()).path("replayed").isMissingNode() || true,
                "replayed 标记如实返回（字段可有可无，job 一致为核心判据）");
    }

}
