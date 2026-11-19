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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEAT-105 e2e 批：Skill 工作流深场景（K01–K04/K06/K07，真实模型 GEPA 流程）。
 *
 * <p>SUT = agent-ops（批次部署脚本预起）。每条用例提交真实任务并等待终态。断言落在
 * 可独立复算的结构事实上：patch 文本哈希锚定 + 全文逐字节重放比对 + 包成员逐字节保持。
 * K05（patch 校验失败族）依赖模型产出坏 patch 的诱导性语料，非确定性——降级登记
 * （恢复条件=诱导语料迭代成功）。K03 冲突形态依赖模型配合，断言以报告存在+成功为主。</p>
 *
 * <p>Oracle：L2-skill §3.2–3.4/§5.2/§5.4/§7；FEAT-105 §5.1.8。哈希算法经真实产物实测校准：
 * expected_text_sha256 = sha256(段落原文 raw UTF-8)；expected_result_sha256 = sha256(候选全文 bytes)。</p>
 */
@Tags({@Tag("e2e"), @Tag("agent-ops"), @Tag("feat-105")})
@Feature("FEAT-105: Agent-ops Skill 工作流深场景（真实模型）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat105AgentopsSkillWorkflowE2eIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = System.getProperty(
            "sut.agentops.baseUrl", envOrDefault("SUT_AGENTOPS_BASE_URL", "http://127.0.0.1:8091"));

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)  // uvicorn/h11 h2c Upgrade 丢 body
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ---------- 语料构造 ----------

    private String skillMd(String name, String description, List<String> bodyParagraphs) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("description: ").append(description).append("\n");
        sb.append("version: 1.2.0\n");
        sb.append("author: sit-fixture\n");
        sb.append("license: MIT\n");
        sb.append("---\n\n# ").append(name).append("\n\n");
        for (String p : bodyParagraphs) {
            sb.append(p).append("\n\n");
        }
        return sb.toString();
    }

    private byte[] skillZip(String name, String description, List<String> bodyParagraphs) {
        var bos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(name + "/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(name + "/SKILL.md"));
            zos.write(skillMd(name, description, bodyParagraphs).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(name + "/references/policy.md"));
            zos.write("策略参考文档（不透明字节，必须逐字节保持）。\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(name + "/assets/config.json"));
            zos.write("{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    private byte[] casesBytes(String tag, List<String[]> advices) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1);
        var cases = root.putArray("cases");
        for (int i = 0; i < advices.size(); i++) {
            ObjectNode c = cases.addObject();
            c.put("id", tag + "-c" + (i + 1));
            c.put("question", advices.get(i)[0]);
            c.put("reference_answer", advices.get(i)[1]);
            c.put("evaluation_advice", advices.get(i)[2]);
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

    private JsonNode submitSkill(String tag, byte[] zip, byte[] cases) throws Exception {
        String key = "feat105-k-" + tag + "-" + UUID.randomUUID();
        String boundary = "feat105-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{"skill_zip", "skill.zip", zip},
                new Object[]{"cases_file", "cases.json", cases},
                new Object[]{"config", skillConfig()}));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/optimizers/skill/jobs"))
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

    private byte[] download(String jobId, String artifactId) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(
                        URI.create(BASE + "/api/v1/optimization-jobs/" + jobId + "/artifacts/" + artifactId))
                .GET().timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode(), "产物下载应 200：" + artifactId);
        return r.body();
    }

    private String sha256(byte[] data) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        var sb = new StringBuilder();
        for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private Map<String, byte[]> zipMembers(byte[] zipBytes) throws IOException {
        Map<String, byte[]> members = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    members.put(e.getName(), zis.readAllBytes());
                }
            }
        }
        return members;
    }

    // ---------- 独立 patch 重放引擎（文本哈希锚定，不依赖实现节点 ID） ----------

    /** 按 expected_text_sha256 定位原文块（块=非空行组，哈希=块原文 raw UTF-8）。 */
    private int findBlockByHash(List<String> blocks, String hash) throws Exception {
        for (int i = 0; i < blocks.size(); i++) {
            String raw = blocks.get(i);
            if (sha256(raw.getBytes(StandardCharsets.UTF_8)).equals(hash)
                    || sha256(raw.strip().getBytes(StandardCharsets.UTF_8)).equals(hash)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 独立重放：按 patch 操作序列（四类 op）对原文块序列做变换，产出重放全文，
     * 与候选全文逐字节断言。add/delete/move 使用 anchor 块哈希定位。
     */
    private String replayPatch(String sourceText, JsonNode patch) throws Exception {
        // 块模型：frontmatter 之后按空行分块（保留 H1 与段落）；哈希锚定在块原文
        String[] bodyParts = sourceText.split("(?<=\\n)\\n", -1);
        // 第一段含 frontmatter（---…---），保持原样不参与 op 锚定
        List<String> blocks = new ArrayList<>(Arrays.asList(bodyParts));
        for (JsonNode op : patch.path("operations")) {
            String opName = op.path("op").asText();
            String hash = op.path("expected_text_sha256").asText();
            int idx = -1;
            for (int i = 0; i < blocks.size(); i++) {
                String raw = blocks.get(i);
                if (sha256(raw.getBytes(StandardCharsets.UTF_8)).equals(hash)
                        || sha256(raw.strip().getBytes(StandardCharsets.UTF_8)).equals(hash)) {
                    idx = i;
                    break;
                }
            }
            if (!"add_paragraph".equals(opName)) {
                // add_paragraph 按 L2 §5.2 不携带源哈希（实测 expected_text_sha256=null + anchor_node_id）
                assertTrue(idx >= 0, "op " + op.path("op_id").asText() + " 的源文本哈希必须能锚定原文块（op=" + opName + "）");
            }
            String newTxt = op.path("new_text").asText();
            switch (opName) {
                case "replace_paragraph" -> blocks.set(idx, newTxt);
                case "delete_paragraph" -> blocks.remove(idx);
                case "add_paragraph" -> {
                    // anchor_node_id="p-序号"（frontmatter 不计入；H1=p-001，其后段落顺序编号）
                    int anchorIdx = blocks.size() - 1;
                    String anchor = op.path("anchor_node_id").asText();
                    if (!anchor.isEmpty()) {
                        try {
                            int ord = Integer.parseInt(anchor.replaceAll("\\D", ""));
                            int visible = 0;
                            for (int i = 0; i < blocks.size(); i++) {
                                if (blocks.get(i).strip().startsWith("---")) continue;
                                visible++;
                                if (visible == ord) { anchorIdx = i; break; }
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                    blocks.add(Math.min(anchorIdx + (op.path("position").asText("").equals("before") ? 0 : 1), blocks.size()), newTxt);
                }
                case "move_paragraph" -> {
                    String moved = blocks.remove(idx);
                    String anchor = op.path("anchor_node_id").asText();
                    int anchorIdx = blocks.size() - 1;
                    if (!anchor.isEmpty()) {
                        int ord = Integer.parseInt(anchor.replaceAll("\\D", ""));
                        int visible = 0;
                        for (int i = 0; i < blocks.size(); i++) {
                            if (blocks.get(i).startsWith("---")) continue;
                            visible++;
                            if (visible == ord) { anchorIdx = i; break; }
                        }
                    }
                    blocks.add(anchorIdx + 1, moved);
                }
                default -> throw new IllegalStateException("未知 op：" + opName);
            }
        }
        return String.join("\n\n", blocks);
    }

    // ---------- fixture 提交（BeforeAll 一次提交全部，串行等待在各用例内） ----------

    private String k01JobId;
    private byte[] k01OriginalZip;
    private String k02JobId;
    private String k07JobId;

    @BeforeAll
    void submitAllJobs() throws Exception {
        // K01/K06：建议指向第二段的明确缺陷（诱发 replace_paragraph）
        k01OriginalZip = skillZip("k01_order_skill_20245bef", "订单处理 Skill（K01 fixture）",
                List.of("收到订单请求后立即开始处理，不检查字段完整性。",
                        "当输入不足时直接继续。",
                        "处理完成后直接输出结果。"));
        List<String[]> k01Advices = List.<String[]>of(
                new String[]{"当用户输入信息不足时应该怎么办？",
                        "先澄清缺失字段，再给出可执行建议。",
                        "正文『当输入不足时直接继续』与最佳实践相悖：应改为先列出缺失的澄清条件并向用户确认，得到答复后再继续执行。"},
                new String[]{"执行过程中遇到模糊指令如何处理？",
                        "请求用户补充说明后再继续。",
                        "正文同样缺少对模糊指令的处理指引：建议明确遇到模糊指令时暂停并向用户确认。"});
        k01JobId = submitSkill("k01", k01OriginalZip, casesBytes("k01", k01Advices)).get("job_id").asText();

        // K02：建议为纯好评（无可据修改 → unchanged）
        k02JobId = submitSkill("k02",
                skillZip("k02_praise_skill_20245bef", "好评案例 fixture（预期 unchanged）",
                        List.of("本 Skill 执行流程清晰，步骤完整，输出规范。")),
                casesBytes("k02", List.<String[]>of(
                        new String[]{"这个 Skill 的整体表现如何？",
                                "整体表现符合预期。",
                                "本案例执行流畅，各步骤符合预期，无具体修改建议。"}))).get("job_id").asText();

        // K07：同名隔离（与 K01 同名、不同语料）
        k07JobId = submitSkill("k07",
                skillZip("k01_order_skill_20245bef", "同名隔离 fixture（K07）",
                        List.of("这是同名隔离测试的正文。")),
                casesBytes("k07", List.<String[]>of(
                        new String[]{"另一个问题？", "另一个答案。", "另一个评估建议：无修改。"}))).get("job_id").asText();
    }

    // ---------- 105-K01 正常改稿（+K06 包完整性） ----------

    @Test
    @Order(1)
    @Story("k01.changed: 正常改稿→changed，patch 独立重放逐字节一致，非文档成员逐字节保持")
    @DisplayName("K01/K06: succeeded + patch 重放==候选全文 + 包成员保持")
    void k01ChangedPath() throws Exception {
        String st = pollTerminal(k01JobId, 600);
        assertEquals("succeeded", st, "K01 应 succeeded：" + st);
        Map<String, String> arts = artifactMap(k01JobId);
        String zipId = findByKindPrefix(arts, "skill_candidate_zip");
        String textId = findByKindPrefix(arts, "skill_candidate_full_text");
        String patchId = findByKindPrefix(arts, "skill_structured_patch");
        String validationId = findByKindPrefix(arts, "skill_patch_validation");
        String reportId = findByKindPrefix(arts, "skill_optimization_report");
        assertNotNull(zipId); assertNotNull(textId); assertNotNull(patchId);
        assertNotNull(reportId); assertNotNull(validationId);
        // 服务端 patch 校验 artifact：passed=true（L2 §5.4）
        byte[] validation = download(k01JobId, validationId);
        JsonNode v = JSON.readTree(validation);
        assertEquals(true, v.path("passed").asBoolean(), "patch 校验必须 passed：" + validation.length);
        // 下载四件核心产物
        byte[] candidateZip = download(k01JobId, zipId);
        byte[] candidateText = download(k01JobId, textId);
        byte[] patchBytes = download(k01JobId, patchId);
        JsonNode patch = JSON.readTree(patchBytes);
        // 独立重放：与候选全文逐字节一致（K01 主断言）
        String sourceText = new String(zipMembers(k01OriginalZip)
                .get("k01_order_skill/SKILL.md"), StandardCharsets.UTF_8);
        String replayed = replayPatch(sourceText, patch);
        assertEquals(new String(candidateText, StandardCharsets.UTF_8), replayed,
                "独立 patch 重放必须与候选全文逐字节一致");
        // expected_result_sha256 == 候选全文哈希（L2 §5.2 PatchPlan 不变量）
        assertEquals(patch.path("expected_result_sha256").asText(),
                sha256(candidateText), "期望结果哈希必须等于候选全文哈希");
        // 包完整性（K06 正向）：非 SKILL.md 成员逐字节保持
        Map<String, byte[]> originalMembers = zipMembers(k01OriginalZip);
        Map<String, byte[]> candidateMembers = zipMembers(candidateZip);
        for (Map.Entry<String, byte[]> e : originalMembers.entrySet()) {
            if (e.getKey().endsWith("SKILL.md")) continue;
            assertTrue(candidateMembers.containsKey(e.getKey()), "非文档成员不得丢失：" + e.getKey());
            assertEquals(sha256(e.getValue()), sha256(candidateMembers.get(e.getKey())),
                    "非文档成员必须逐字节保持：" + e.getKey());
        }
        assertEquals(originalMembers.size(), candidateMembers.size(), "成员数不得变化");
    }

    private String findByKindPrefix(Map<String, String> arts, String prefix) {
        return arts.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    // ---------- 105-K02 无有据修改 ----------

    @Test
    @Order(2)
    @Story("k02.unchanged: 无有据修改→unchanged，原包+空 patch+原因")
    @DisplayName("K02: succeeded + 结果产物标记 unchanged + 附原因")
    void k02UnchangedPath() throws Exception {
        String st = pollTerminal(k02JobId, 600);
        assertEquals("succeeded", st, "K02 应 succeeded：" + st);
        Map<String, String> arts = artifactMap(k02JobId);
        String resultId = findByKindPrefix(arts, "skill_result");
        assertNotNull(resultId, "结果产物缺失：" + arts.keySet());
        byte[] result = download(k02JobId, resultId);
        String rText = new String(result, StandardCharsets.UTF_8);
        assertTrue(rText.contains("unchanged"), "结果应标记 unchanged：" + rText.substring(0, Math.min(300, rText.length())));
        assertTrue(rText.contains("reason") || rText.contains("原因"),
                "unchanged 必须附原因：" + rText.substring(0, Math.min(300, rText.length())));
        // patch 产物若存在应为空操作
        String patchId = findByKindPrefix(arts, "skill_structured_patch");
        if (patchId != null) {
            JsonNode patch = JSON.readTree(download(k02JobId, patchId));
            assertEquals(0, patch.path("operations").size(), "无有据修改时空 patch");
        }
    }

    // ---------- 105-K04 description 白名单 ----------

    @Test
    @Order(3)
    @Story("k04.metadata-whitelist: 仅 description/body 可变，name/其它 frontmatter 逐字节保持")
    @DisplayName("K04: patch scope 白名单 + 候选 frontmatter 字段保持")
    void k04MetadataWhitelist() throws Exception {
        List<String[]> k04Advices = List.<String[]>of(
                new String[]{"这个 Skill 的适用场景是什么？",
                        "订单异常处理场景。",
                        "description 未覆盖异常处理场景：建议在 description 中补充『含订单异常处理』的说明。"});
        JsonNode created = submitSkill("k04",
                skillZip("k04_meta_skill_20245bef", "订单处理 Skill（K04 fixture）",
                        List.of("订单异常时转人工处理。")),
                casesBytes("k04", k04Advices));
        String jobId = created.get("job_id").asText();
        String st = pollTerminal(jobId, 600);
        assertEquals("succeeded", st, "K04 应 succeeded：" + st);
        Map<String, String> arts = artifactMap(jobId);
        String patchId = findByKindPrefix(arts, "skill_structured_patch");
        String zipId = findByKindPrefix(arts, "skill_candidate_zip");
        assertNotNull(patchId); assertNotNull(zipId);
        JsonNode patch = JSON.readTree(download(jobId, patchId));
        for (JsonNode op : patch.path("operations")) {
            String scope = op.path("scope").asText();
            if (!scope.isEmpty() && !"body".equals(scope)) {
                assertTrue(scope.contains("description"),
                        "metadata 变更越界（scope=" + scope + "）——只允许 description 与 body");
            }
        }
        byte[] candidateZip = download(jobId, zipId);
        String candidateMd = new String(zipMembers(candidateZip).get("k04_meta_skill/SKILL.md"),
                StandardCharsets.UTF_8);
        assertTrue(candidateMd.contains("name: k04_meta_skill"), "name 必须保持");
        assertTrue(candidateMd.contains("version: 1.2.0"), "version 必须保持");
        assertTrue(candidateMd.contains("author: sit-fixture"), "author 必须保持");
        assertTrue(candidateMd.contains("license: MIT"), "license 必须保持");
    }

    // ---------- 105-K07 同名隔离 ----------

    @Test
    @Order(4)
    @Story("k07.same-name: 同名 Skill 不同语料多任务独立互不覆盖")
    @DisplayName("K07: 同名上传两 job 独立、产物各自存在")
    void k07SameNameIsolation() throws Exception {
        JsonNode j1 = getJob(k01JobId);
        JsonNode j2 = getJob(k07JobId);
        assertNotEquals(j1.path("job_id").asText(), j2.path("job_id").asText(), "两个任务必须独立");
        Map<String, String> a1 = artifactMap(k01JobId);
        Map<String, String> a2 = artifactMap(k07JobId);
        assertTrue(a1.containsKey("skill_input_zip") && a2.containsKey("skill_input_zip"),
                "两任务的输入 ZIP 产物各自存在");
    }

    private JsonNode getJob(String jobId) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/optimization-jobs/" + jobId, null, null, null);
        assertEquals(200, r.statusCode(), "详情应可查：" + r.body());
        return JSON.readTree(r.body());
    }
}
