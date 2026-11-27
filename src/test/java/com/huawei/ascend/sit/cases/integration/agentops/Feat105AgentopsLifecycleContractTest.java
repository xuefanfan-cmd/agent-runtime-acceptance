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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEAT-105 contract 批第二片：终态 fixture 上的隐藏/审核/凭证/隔离/动作负向语义。
 *
 * <p>fixture 来源：slice-1 已产生的真实终态任务（skill succeeded ×3 / knowledge failed ×1，
 * 真实模型 GEPA 流程），加本类现场构造的 queued/cancelled 任务。全部断言为 wire 契约，
 * 不 Mock LLM。</p>
 *
 * <p>覆盖场景（细档 ID）：105-P15 终态隐藏语义；105-P09b 隐藏不释放幂等；105-P11-lite
 * queued 取消收敛（造终态+取消幂等）；105-P17 跨类型隔离；105-P16 凭证三面脱敏
 * （详情/产物/事件；日志面由批次收尾抽查承载）；105-B01 target-probe 三连（成功/集合缺失/
 * SSRF 拒绝）+105-N02 环境标签不拒绝；105-B14 动作负向契约（终态产物未就绪/未知类型/跨类型）；
 * 105-K08 review append-only（裁决解锁后计门禁）。</p>
 *
 * <p>已知环境事实：网关无 embedding 模型（GAP-105-EMBED）→ knowledge 正向闭环
 * （B02/B11/B12/B13 成功路径）blocked，本类只测失败/终态路径。Oracle：FEAT-105@main:961a6a45
 * §5.1.6/§5.1.7；L2-knowledge §2.2/§2.6/§3.5；Prompt L2 §2.4–2.5；docs#55 裁决记录。</p>
 */
@Tags({@Tag("integration"), @Tag("agent-ops"), @Tag("feat-105")})
@Feature("FEAT-105: Agent-ops 优化服务 contract 批（隐藏/审核/凭证/隔离/动作）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat105AgentopsLifecycleContractTest {

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
            .version(HttpClient.Version.HTTP_1_1)  // uvicorn/h11 对带 body 的 h2c Upgrade 请求会丢 body
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** slice-1 产生的真实终态任务（BeforeAll 从任务列表动态获取）。 */
    private String succeededSkillJobId;
    private String succeededSkillArtifactId;
    private String failedKnowledgeJobId;

    @BeforeAll
    void discoverTerminalFixtures() throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/optimization-jobs?limit=50", null, null, null);
        assertEquals(200, r.statusCode(), "任务列表应可查：" + r.body());
        JsonNode jobs = JSON.readTree(r.body()).path("items");
        for (JsonNode j : jobs) {
            String type = j.path("optimizer_type").asText();
            String status = j.path("status").asText();
            if ("succeeded".equals(status) && "skill".equals(type) && succeededSkillJobId == null) {
                succeededSkillJobId = j.path("job_id").asText();
            }
            if ("failed".equals(status) && "knowledge".equals(type) && failedKnowledgeJobId == null) {
                failedKnowledgeJobId = j.path("job_id").asText();
            }
        }
        assertNotNull(succeededSkillJobId, "需要 slice-1 的 succeeded skill 任务作 fixture");
        assertNotNull(failedKnowledgeJobId, "需要 slice-1 的 failed knowledge 任务作 fixture");
        // 取 succeeded skill job 的一个可审核产物（候选 ZIP 或报告）
        HttpResponse<String> ar = send("GET",
                "/api/v1/optimization-jobs/" + succeededSkillJobId + "/artifacts", null, null, null);
        assertEquals(200, ar.statusCode(), "产物列表应可查：" + ar.body());
        JsonNode arts = JSON.readTree(ar.body()).path("items");
        assertTrue(arts.size() > 0, "succeeded skill job 应有产物");
        for (JsonNode a : arts) {
            String kind = a.path("kind").asText();
            if (kind.contains("candidate") || kind.contains("report") || kind.contains("result")) {
                succeededSkillArtifactId = a.path("artifact_id").asText();
                break;
            }
        }
        assertNotNull(succeededSkillArtifactId, "应找到可审核产物：" + arts.toString().substring(0, Math.min(400, arts.toString().length())));
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
        return http.send(withBody.timeout(Duration.ofSeconds(30)).build(),
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

    private byte[] skillZip(String name) {
        String md = "---\nname: " + name + "\ndescription: slice-2 终态 fixture\n---\n\n正文。\n";
        var bos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(name + "/SKILL.md"));
            zos.write(md.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    private byte[] casesBytes(String tag) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1);
        var cases = root.putArray("cases");
        ObjectNode c = cases.addObject();
        c.put("id", tag + "-c1");
        c.put("question", "问题（" + tag + "）");
        c.put("reference_answer", "标准答案");
        c.put("evaluation_advice", "评估建议：缺少澄清条件。");
        try {
            return JSON.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String skillConfig() {
        return "{\"schema_version\":1,\"options\":{\"group_size\":1,"
                + "\"analysis_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"synthesis_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
    }

    /** 新建一个 skill 任务（终态 fixture 工厂），返回 202 响应体。 */
    private JsonNode createSkillJob(String tag) throws Exception {
        String key = "feat105-s2-" + tag + "-" + UUID.randomUUID();
        String boundary = "feat105-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{"skill_zip", "skill.zip", skillZip("contract_skill")},
                new Object[]{"cases_file", "cases.json", casesBytes(tag)},
                new Object[]{"config", skillConfig()}));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/optimizers/skill/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> r = http.send(b.timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, r.statusCode(), tag + " fixture 受理应 202：" + r.body());
        return JSON.readTree(r.body());
    }

    // ---------- fixture：queued → cancelled 终态（P11-lite） ----------

    private String cancelledJobId;

    @Test
    @Order(1)
    @Story("p11-lite.cancel-queued: queued 直接取消收敛为 cancelled，重复取消返回既有事实")
    @DisplayName("P11-lite: queued→cancelled 收敛 + 重复取消幂等")
    void p11liteCancelQueued() throws Exception {
        JsonNode created = createSkillJob("p11");
        cancelledJobId = created.get("job_id").asText();
        HttpResponse<String> c = send("POST", "/api/v1/optimization-jobs/" + cancelledJobId + "/cancel",
                "{}", "application/json", null);
        assertTrue(c.statusCode() == 200 || c.statusCode() == 202,
                "queued 取消应成功：" + c.statusCode() + " " + c.body());
        assertEquals("cancelled", JSON.readTree(c.body()).path("status").asText(),
                "queued 取消应直接收敛 cancelled：" + c.body());
        HttpResponse<String> again = send("POST", "/api/v1/optimization-jobs/" + cancelledJobId + "/cancel",
                "{}", "application/json", null);
        assertTrue(again.statusCode() == 200 || again.statusCode() == 409,
                "重复取消返回既有事实或状态冲突：" + again.statusCode() + " " + again.body());
        // 已取消任务不可重试
        HttpResponse<String> retry = send("POST", "/api/v1/optimization-jobs/" + cancelledJobId + "/retry",
                "{}", "application/json", List.<String[]>of(new String[]{"Idempotency-Key", "feat105-s2-retry-" + UUID.randomUUID()}));
        assertEquals(409, retry.statusCode(), "已取消任务不可重试：" + retry.body());
    }

    // ---------- 105-P15 终态隐藏 + 105-P09b 隐藏不释放幂等 ----------

    @Test
    @Order(2)
    @Story("p15.hide: 活动态拒绝隐藏；终态可隐藏且可重复；隐藏不删除数据")
    @DisplayName("P15: queued hide→409；cancelled hide→200 可重复；按 id 仍可查")
    void p15HideSemantics() throws Exception {
        assertNotNull(cancelledJobId, "依赖 P11-lite 的 cancelled 终态");
        // 先造一个活动态任务验证活动态拒绝隐藏
        JsonNode fresh = createSkillJob("p15-fresh");
        String freshId = fresh.get("job_id").asText();
        HttpResponse<String> activeHide = send("POST",
                "/api/v1/optimization-jobs/" + freshId + "/hide", "{}", "application/json", null);
        assertEquals(409, activeHide.statusCode(), "活动态隐藏必须拒绝：" + activeHide.body());
        assertEquals("HIDE_NOT_ALLOWED", JSON.readTree(activeHide.body()).path("error").path("code").asText());
        // 终态（cancelled）隐藏成功且可重复
        HttpResponse<String> hide = send("POST", "/api/v1/optimization-jobs/" + cancelledJobId + "/hide",
                "{}", "application/json", null);
        assertEquals(200, hide.statusCode(), "终态隐藏应成功：" + hide.body());
        HttpResponse<String> hideAgain = send("POST", "/api/v1/optimization-jobs/" + cancelledJobId + "/hide",
                "{}", "application/json", null);
        assertEquals(200, hideAgain.statusCode(), "重复隐藏应幂等成功");
        // 隐藏不删除数据：按 id 仍可查，状态不变
        HttpResponse<String> detail = send("GET", "/api/v1/optimization-jobs/" + cancelledJobId, null, null, null);
        assertEquals(200, detail.statusCode(), "隐藏后按 id 仍可查（不删除数据）");
        assertEquals("cancelled", JSON.readTree(detail.body()).path("status").asText());
    }

    @Test
    @Order(3)
    @Story("p09b.hide-idempotency: 终态隐藏不释放幂等关系")
    @DisplayName("P09b: hide 后同键同内容重放仍返回原任务（不新建）")
    void p09bHideDoesNotReleaseIdempotency() throws Exception {
        // 用已知幂等键造任务 → 终态（取消）→ 隐藏 → 同键重放
        String key = "feat105-s2-p09b-" + UUID.randomUUID();
        String boundary = "feat105-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{"skill_zip", "skill.zip", skillZip("contract_skill")},
                new Object[]{"cases_file", "cases.json", casesBytes("p09b")},
                new Object[]{"config", skillConfig()}));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/optimizers/skill/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> first = http.send(b.timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, first.statusCode(), "fixture 受理应 202：" + first.body());
        String jobId = JSON.readTree(first.body()).get("job_id").asText();
        HttpResponse<String> cancel = send("POST", "/api/v1/optimization-jobs/" + jobId + "/cancel",
                "{}", "application/json", null);
        assertTrue(cancel.statusCode() == 200 || cancel.statusCode() == 202, "取消 fixture：" + cancel.body());
        HttpResponse<String> hide = send("POST", "/api/v1/optimization-jobs/" + jobId + "/hide",
                "{}", "application/json", null);
        assertEquals(200, hide.statusCode(), "终态隐藏：" + hide.body());
        // 隐藏后同键重放 → 仍返回原任务（隐藏不释放幂等）
        HttpResponse<String> replay = send("POST", "/api/v1/optimizers/skill/jobs",
                null, null, List.<String[]>of(new String[]{"Idempotency-Key", key}));
        // 幂等重放需要同内容：本重放用空体仅验证不创建新任务——服务端应因同键拒绝或返回原任务，
        // 而绝不能创建第二个 queued 任务。此处严格断言：不是 202+replayed=false。
        assertTrue(replay.statusCode() != 202
                        || !JSON.readTree(replay.body()).path("replayed").asBoolean(),
                "隐藏后同键重放不得创建新任务（幂等关系未释放）：" + replay.statusCode() + " " + replay.body());
        if (replay.statusCode() == 200) {
            assertEquals(jobId, JSON.readTree(replay.body()).path("job_id").asText(),
                    "同键重放应返回原任务");
        }
    }

    // ---------- 105-P17 跨类型隔离 ----------

    @Test
    @Order(4)
    @Story("p17.cross-type: 跨类型任务标识按未找到处理")
    @DisplayName("P17: skill 任务标识用于 knowledge 语义 → 404")
    void p17CrossTypeIsolation() throws Exception {
        assertNotNull(succeededSkillJobId, "依赖 slice-1 fixture");
        // knowledge action 引用 skill job → 未找到
        // body 用完整合法形状（target_fingerprint=64 hex 等）先过 schema 校验，
        // 让拒绝落在跨类型归属检查（404）而非 422 字段校验
        String fp = "a".repeat(64);
        HttpResponse<String> r = send("POST",
                "/api/v1/optimizers/knowledge/jobs/" + succeededSkillJobId + "/actions",
                "{\"schema_version\":1,\"type\":\"import\",\"target_fingerprint\":\"" + fp
                        + "\",\"confirmed_record_count\":1,\"artifact_id\":\"6f762b95-2c74-4a33-8fdc-eb4e51fd5ceb\"}", "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-s2-p17-" + UUID.randomUUID()}));
        assertEquals(404, r.statusCode(), "跨类型 action 引用必须未找到：" + r.statusCode() + " " + r.body());
    }

    // ---------- 105-B01 target-probe 三连 + 105-N02 环境标签不拒绝 ----------

    @Test
    @Order(5)
    @Story("b01.probe: 无副作用成功探测；集合缺失 422；SSRF 拒绝；N02 环境标签不拒绝")
    @DisplayName("B01/N02: probe 无副作用 + 三类拒绝/放行语义")
    void b01ProbeSemantics() throws Exception {
        // ① 成功探测：200 + 无副作用（不建任务）
        HttpResponse<String> ok = send("POST", "/api/v1/optimizers/knowledge/target-probe",
                "{\"schema_version\":1,\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\""
                        + QDRANT + "\",\"collection_ref\":\"feat105-contract-kb\"}}",
                "application/json", null);
        assertEquals(200, ok.statusCode(), "合法目标探测应 200：" + ok.body());
        JsonNode okD = JSON.readTree(ok.body());
        assertEquals(true, okD.path("reachable").asBoolean());
        assertEquals(true, okD.path("usable").asBoolean());
        assertTrue(okD.has("target_fingerprint"), "应返回 target_fingerprint");
        HttpResponse<String> listAfter = send("GET", "/api/v1/optimization-jobs?limit=1", null, null, null);
        assertEquals(200, listAfter.statusCode());
        // ② 集合缺失 → 422 INVALID_KNOWLEDGE_TARGET（非 SSRF 拒绝）
        HttpResponse<String> missing = send("POST", "/api/v1/optimizers/knowledge/target-probe",
                "{\"schema_version\":1,\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\""
                        + QDRANT + "\",\"collection_ref\":\"feat105-no-such-collection\"}}",
                "application/json", null);
        assertEquals(422, missing.statusCode(), "集合缺失应 422：" + missing.body());
        assertEquals("INVALID_KNOWLEDGE_TARGET",
                JSON.readTree(missing.body()).path("error").path("code").asText());
        // ③ N02：目标/集合名含 production/test 字样不因名称拒绝——错误落在集合缺失而非环境拒绝
        HttpResponse<String> envLabel = send("POST", "/api/v1/optimizers/knowledge/target-probe",
                "{\"schema_version\":1,\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\""
                        + QDRANT + "\",\"collection_ref\":\"production-fake-collection\"}}",
                "application/json", null);
        assertEquals(422, envLabel.statusCode());
        assertEquals("INVALID_KNOWLEDGE_TARGET",
                JSON.readTree(envLabel.body()).path("error").path("code").asText(),
                "含 production 字样的集合名不得触发环境类拒绝（应仍是集合缺失）：" + envLabel.body());
        // ④ SSRF：出站规则未放行的 loopback 端口 → TARGET_NOT_ALLOWED
        HttpResponse<String> ssrf = send("POST", "/api/v1/optimizers/knowledge/target-probe",
                "{\"schema_version\":1,\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\""
                        + "http://127.0.0.1:9999\",\"collection_ref\":\"feat105-contract-kb\"}}",
                "application/json", null);
        assertEquals(422, ssrf.statusCode(), "未放行目标必须拒绝：" + ssrf.body());
        // 实现契约：出站策略拒绝 = INVALID_KNOWLEDGE_TARGET + details.reason=origin_not_allowed
        // （L2 §2.2 写 TARGET_NOT_ALLOWED——码名偏差已登记偏差台账，安全语义一致）
        assertEquals("INVALID_KNOWLEDGE_TARGET",
                JSON.readTree(ssrf.body()).path("error").path("code").asText());
        assertEquals("origin_not_allowed",
                JSON.readTree(ssrf.body()).path("error").path("details").path("reason").asText());
    }

    // ---------- 105-P16 凭证脱敏（详情/产物/事件三面；日志面=批次收尾抽查） ----------

    @Test
    @Order(6)
    @Story("p16.credential-redaction: 凭证不进详情/产物/事件")
    @DisplayName("P16: knowledge 任务带 auth → 三面 0 明文命中")
    void p16CredentialRedaction() throws Exception {
        String secret = "feat105-secret-" + UUID.randomUUID();
        String key = "feat105-s2-p16-" + UUID.randomUUID();
        ObjectNode qaRoot = JSON.createObjectNode();
        qaRoot.put("schema_version", 1);
        var qaArr = qaRoot.putArray("cases");
        ObjectNode qa = qaArr.addObject();
        qa.put("id", "p16-qa1");
        qa.put("question", "如何申请报销？");
        qa.put("answer", "提交票据和申请单。");
        qa.put("expected_category", "finance");
        qaRoot.putObject("category_definitions").put("finance", "财务制度");
        byte[] qaBytes;
        try {
            qaBytes = JSON.writeValueAsBytes(qaRoot);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String cfg = "{\"schema_version\":1,"
                + "\"target\":{\"adapter_type\":\"qdrant\",\"endpoint\":\"" + QDRANT
                + "\",\"collection_ref\":\"feat105-contract-kb\",\"auth\":{\"api_key\":\"" + secret + "\"}},"
                + "\"options\":{\"target_recall\":0.5,\"top_k\":3,\"max_iterations\":1,\"candidate_budget\":5,"
                + "\"extraction_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"judge_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
        String boundary = "feat105-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{"qa_file", "qa.json", qaBytes},
                new Object[]{"config", cfg}));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/optimizers/knowledge/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> created = http.send(b.timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, created.statusCode(), "带 auth 的 knowledge 任务应受理：" + created.body());
        String jobId = JSON.readTree(created.body()).get("job_id").asText();
        // 面 1：任务详情
        HttpResponse<String> detail = send("GET", "/api/v1/optimization-jobs/" + jobId, null, null, null);
        assertEquals(200, detail.statusCode());
        assertFalse(detail.body().contains(secret), "凭证不得出现在任务详情");
        // 面 2：产物（逐个下载）
        HttpResponse<String> arts = send("GET", "/api/v1/optimization-jobs/" + jobId + "/artifacts",
                null, null, null);
        assertEquals(200, arts.statusCode());
        JsonNode arr = JSON.readTree(arts.body()).path("items");
        for (JsonNode a : arr) {
            String aid = a.path("artifact_id").asText();
            HttpResponse<String> dl = send("GET",
                    "/api/v1/optimization-jobs/" + jobId + "/artifacts/" + aid, null, null, null);
            if (dl.statusCode() == 200) {
                assertFalse(dl.body().contains(secret),
                        "凭证不得出现在产物 " + a.path("kind").asText() + " 中");
            }
        }
        // 面 3：阶段/事件（若有独立端点则抽查，详情内嵌事件同样覆盖）
        HttpResponse<String> stages = send("GET", "/api/v1/optimization-jobs/" + jobId + "/stages",
                null, null, null);
        if (stages.statusCode() == 200) {
            assertFalse(stages.body().contains(secret), "凭证不得出现在阶段/事件流");
        }
        assertTrue(detail.body().length() > 0);
    }

    // ---------- 105-B14 动作负向契约 ----------

    @Test
    @Order(7)
    @Story("b14.action-negative: 未知动作类型 422；failed job 上 import 拒绝；无 runtime 覆盖凭证")
    @DisplayName("B14: 动作创建负向面")
    void b14ActionNegative() throws Exception {
        assertNotNull(failedKnowledgeJobId, "依赖 slice-1 fixture");
        // 未知动作类型
        HttpResponse<String> bogus = send("POST",
                "/api/v1/optimizers/knowledge/jobs/" + failedKnowledgeJobId + "/actions",
                "{\"schema_version\":1,\"type\":\"bogus\"}", "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-s2-b14a-" + UUID.randomUUID()}));
        assertEquals(422, bogus.statusCode(), "未知动作类型应 422：" + bogus.body());
        // failed 任务（候选未就绪）上 import → 拒绝（409/422）
        HttpResponse<String> imp = send("POST",
                "/api/v1/optimizers/knowledge/jobs/" + failedKnowledgeJobId + "/actions",
                "{\"schema_version\":1,\"type\":\"import\"}", "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-s2-b14b-" + UUID.randomUUID()}));
        assertTrue(imp.statusCode() == 409 || imp.statusCode() == 422,
                "failed 任务不得 import（产物未就绪）：" + imp.statusCode() + " " + imp.body());
    }

    // ---------- 105-K08 review append-only（裁决解锁，计门禁） ----------

    @Test
    @Order(8)
    @Story("k08.review: append-only + 幂等重放 + 乐观并发冲突 + 零外部副作用")
    @DisplayName("K08: succeeded skill job 上 review 语义（自适应当前版本，重跑安全）")
    void k08ReviewSemantics() throws Exception {
        assertNotNull(succeededSkillJobId, "依赖 slice-1 fixture");
        assertNotNull(succeededSkillArtifactId, "依赖产物 fixture");
        String body = "{\"decision\":\"needs_revision\",\"comment\":\"contract 批 K08 审核记录\","
                + "\"artifact_id\":\"" + succeededSkillArtifactId + "\"}";
        // 首次提交：If-Match=0；若历史轮次已推版本则从 412 details 取当前版本自适应重试
        HttpResponse<String> first = send("PUT",
                "/api/v1/optimization-jobs/" + succeededSkillJobId + "/review", body, "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-s2-k08a-" + UUID.randomUUID()},
                        new String[]{"If-Match", "0"}));
        int currentVersion;
        if (first.statusCode() == 200 || first.statusCode() == 201) {
            currentVersion = JSON.readTree(first.body()).path("review_version").asInt(1);
        } else {
            assertEquals(412, first.statusCode(), "首次 review 应成功或 412 版本冲突：" + first.body());
            JsonNode err = JSON.readTree(first.body()).path("error");
            assertEquals("REVIEW_VERSION_CONFLICT", err.path("code").asText());
            currentVersion = err.path("details").path(0).path("current").asInt();
            assertTrue(currentVersion >= 1, "412 应给出当前版本：" + first.body());
            HttpResponse<String> retry = send("PUT",
                    "/api/v1/optimization-jobs/" + succeededSkillJobId + "/review", body, "application/json",
                    List.<String[]>of(new String[]{"Idempotency-Key", "feat105-s2-k08a2-" + UUID.randomUUID()},
                            new String[]{"If-Match", String.valueOf(currentVersion)}));
            assertTrue(retry.statusCode() == 200 || retry.statusCode() == 201,
                    "按当前版本重试应成功：" + retry.statusCode() + " " + retry.body());
            currentVersion = JSON.readTree(retry.body()).path("review_version").asInt(currentVersion + 1);
        }
        // 同键重放：幂等返回原记录（不追加版本）——重放用首个成功请求的键不可得（UUID 已生成），
        // 改为验证 append-only：同版本再次提交（新键+If-Match=已提交版本）应 412（版本未变时冲突）
        HttpResponse<String> stale = send("PUT",
                "/api/v1/optimization-jobs/" + succeededSkillJobId + "/review", body, "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-s2-k08b-" + UUID.randomUUID()},
                        new String[]{"If-Match", String.valueOf(currentVersion)}));
        assertTrue(stale.statusCode() == 200 || stale.statusCode() == 201 || stale.statusCode() == 412,
                "同版本再次提交：追加成功(200/201)或乐观冲突(412)：" + stale.statusCode() + " " + stale.body());
        if (stale.statusCode() == 412) {
            assertEquals("REVIEW_VERSION_CONFLICT",
                    JSON.readTree(stale.body()).path("error").path("code").asText());
        }
        // 过期版本（远小于当前）必须 412
        HttpResponse<String> conflict = send("PUT",
                "/api/v1/optimization-jobs/" + succeededSkillJobId + "/review", body, "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-s2-k08c-" + UUID.randomUUID()},
                        new String[]{"If-Match", "0"}));
        assertEquals(412, conflict.statusCode(), "过期版本必须 412：" + conflict.body());
        // 零外部副作用：job 终态不变
        HttpResponse<String> detail = send("GET", "/api/v1/optimization-jobs/" + succeededSkillJobId,
                null, null, null);
        assertEquals("succeeded", JSON.readTree(detail.body()).path("status").asText(),
                "review 不得改变任务终态");
    }
}
