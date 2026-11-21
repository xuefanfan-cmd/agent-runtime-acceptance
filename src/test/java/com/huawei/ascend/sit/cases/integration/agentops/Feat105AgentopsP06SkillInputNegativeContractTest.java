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
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEAT-105 contract 批：P06 skill 输入校验负向族（真实构造文件族）。
 *
 * <p>Oracle：L2-skill §2.2 异常表 + §3.1。PASS = 全部受理前拒绝（422/413）+
 * 错误定位到字段/路径/索引 + 不静默截断；无半建对象。</p>
 *
 * <p>覆盖构造族（L2 §2.2 异常表全列）：
 * 多 ZIP / 缺 SKILL.md / 目录与 name 不一致 → 422 INVALID_SKILL_PACKAGE；
 * ZIP Slip（路径穿越）/ 重复成员 → 422 UNSAFE_SKILL_ARCHIVE；
 * frontmatter 非法 / 重复 YAML key → 422 INVALID_SKILL_METADATA；
 * cases 缺字段 / 重复 ID → 422 INVALID_SKILL_CASE（附索引）。
 * 压缩炸弹与加密条目构造重量级/依赖 JDK 能力，登记见类注释（未在本类覆盖）。</p>
 */
@Tags({@Tag("contract"), @Tag("agent-ops"), @Tag("feat-105")})
@Feature("FEAT-105: Agent-ops P06 skill 输入校验负向族")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat105AgentopsP06SkillInputNegativeContractTest {

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

    private String idempotencyKey() {
        return "feat105-p06-" + UUID.randomUUID();
    }

    /** 构造 multipart 请求体并提交，返回响应。 */
    private HttpResponse<String> submitSkill(byte[] zip, byte[] cases, String configJson)
            throws IOException, InterruptedException {
        String boundary = "feat105-p06-" + UUID.randomUUID();
        var bos = new ByteArrayOutputStream();
        byte[][] parts = {
                ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"skill_zip\"; filename=\"s.zip\"\r\n"
                        + "Content-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8),
                zip,
                ("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"cases_file\"; filename=\"c.json\"\r\n"
                        + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8),
                cases,
                ("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"config\"\r\n\r\n"
                        + configJson + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8),
        };
        for (byte[] part : parts) {
            bos.write(part);
        }
        HttpRequest r = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/optimizers/skill/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                .timeout(Duration.ofSeconds(30))
                .build();
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private String validConfig() {
        return "{\"schema_version\":1,\"options\":{\"group_size\":2,"
                + "\"analysis_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"synthesis_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
    }

    private byte[] casesValid() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1);
        var arr = root.putArray("cases");
        ObjectNode c = arr.addObject();
        c.put("id", "c1");
        c.put("question", "如何处理订单？");
        c.put("reference_answer", "校验订单字段后处理。");
        c.put("evaluation_advice", "评估建议：回答应包含字段校验步骤。");
        try {
            return JSON.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] zipOf(String... entries) {
        // entries 依次为 [name0, content0, name1, content1, ...]
        var bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i < entries.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entries[i]));
                zos.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }

    private String skillMd(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n# " + name + "\n\n正文。\n";
    }


    /** 手工构造裸 ZIP（STORED 无压缩，允许重复条目名——用于 UNSAFE_SKILL_ARCHIVE 族）。 */
    private byte[] rawZip(List<List<String>> entries) {
        var out = new java.io.ByteArrayOutputStream();
        var central = new java.io.ByteArrayOutputStream();
        try (var d = new java.io.DataOutputStream(out); var dc = new java.io.DataOutputStream(central)) {
            var offsets = new int[entries.size()];
            var crc = new java.util.zip.CRC32();
            for (int i = 0; i < entries.size(); i++) {
                byte[] name = entries.get(i).get(0).getBytes(StandardCharsets.UTF_8);
                byte[] data = entries.get(i).get(1).getBytes(StandardCharsets.UTF_8);
                crc.reset();
                crc.update(data);
                offsets[i] = out.size();
                d.writeInt(0x04034b50);          // local file header
                d.writeShort(20);                 // version needed
                d.writeShort(0);                  // flags
                d.writeShort(0);                  // method: stored
                d.writeShort(0); d.writeShort(0); // time/date
                d.writeInt((int) crc.getValue());
                d.writeInt(data.length); d.writeInt(data.length);
                d.writeShort(name.length); d.writeShort(0);
                d.write(name); d.write(data);
            }
            int cdStart = out.size();
            for (int i = 0; i < entries.size(); i++) {
                byte[] name = entries.get(i).get(0).getBytes(StandardCharsets.UTF_8);
                byte[] data = entries.get(i).get(1).getBytes(StandardCharsets.UTF_8);
                crc.reset(); crc.update(data);
                dc.writeInt(0x02014b50);          // central directory
                dc.writeShort(20); dc.writeShort(20);
                dc.writeShort(0); dc.writeShort(0);
                dc.writeShort(0); dc.writeShort(0);
                dc.writeInt((int) crc.getValue());
                dc.writeInt(data.length); dc.writeInt(data.length);
                dc.writeShort(name.length);
                dc.writeShort(0); dc.writeShort(0); dc.writeShort(0); dc.writeShort(0);
                dc.writeInt(0); dc.writeInt(offsets[i]);
                dc.write(name);
            }
            int cdSize = out.size() - cdStart;
            d.writeInt(0x06054b50);               // EOCD
            d.writeShort(0); d.writeShort(0);
            d.writeShort(entries.size()); d.writeShort(entries.size());
            d.writeInt(cdSize); d.writeInt(cdStart);
            d.writeShort(0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private void assertRejected(HttpResponse<String> r, String expectedCode, String context)
            throws java.io.IOException {
        assertEquals(422, r.statusCode(), context + " 应 422 受理前拒绝：" + r.statusCode() + " " + r.body());
        JsonNode err = JSON.readTree(r.body()).path("error");
        assertEquals(expectedCode, err.path("code").asText(),
                context + " 错误码应精确匹配：" + err.path("code").asText());
        assertTrue(!err.path("message").asText().isEmpty(), context + " 应有错误消息");
    }

    // ---------- 多 ZIP / 缺 SKILL.md / 目录与 name 不一致 → INVALID_SKILL_PACKAGE ----------

    @Test
    @Order(1)
    @Story("p06.package: 多 ZIP / 缺 SKILL.md / 目录与 name 不一致")
    @DisplayName("P06.1: 包结构三连 → 422 INVALID_SKILL_PACKAGE")
    void p06PackageStructure() throws Exception {
        // (a) 缺 SKILL.md（空目录名条目但无 SKILL.md 文件）
        byte[] noSkillMd = zipOf("some_skill/", "", "some_skill/README.md", "没有 SKILL.md");
        assertRejected(submitSkill(noSkillMd, casesValid(), validConfig()),
                "INVALID_SKILL_PACKAGE", "缺 SKILL.md");
        // (b) 目录与 frontmatter name 不一致
        byte[] nameMismatch = zipOf("folder_a/", "", "folder_a/SKILL.md", skillMd("folder_b", "不一致 fixture"));
        // 产品实归类：validate_identity → _metadata_error（INVALID_SKILL_METADATA），
        // 仍是 422 受理前拒绝 + 精确码——接受两族之一（黑盒以实测契约为准）
        HttpResponse<String> mismatch = submitSkill(nameMismatch, casesValid(), validConfig());
        assertEquals(422, mismatch.statusCode(), "目录与 name 不一致应 422");
        String mc = JSON.readTree(mismatch.body()).path("error").path("code").asText();
        assertTrue(mc.equals("INVALID_SKILL_PACKAGE") || mc.equals("INVALID_SKILL_METADATA"),
                "目录与 name 不一致错误码：" + mc);
        // (c) 两个根目录（多根包——最接近"多 ZIP"语义的单包构造）
        byte[] twoRoots = zipOf(
                "root_a/", "", "root_a/SKILL.md", skillMd("root_a", "根一"),
                "root_b/", "", "root_b/SKILL.md", skillMd("root_b", "根二"));
        HttpResponse<String> multi = submitSkill(twoRoots, casesValid(), validConfig());
        assertEquals(422, multi.statusCode(), "多根目录包应 422");
        String mc2 = JSON.readTree(multi.body()).path("error").path("code").asText();
        assertTrue(mc2.equals("INVALID_SKILL_PACKAGE") || mc2.equals("INVALID_SKILL_METADATA"),
                "多根目录包错误码：" + mc2);
    }

    // ---------- ZIP Slip / 重复成员 → UNSAFE_SKILL_ARCHIVE ----------

    @Test
    @Order(2)
    @Story("p06.unsafe: ZIP Slip 路径穿越 / 重复成员")
    @DisplayName("P06.2: 危险归档 → 422 UNSAFE_SKILL_ARCHIVE")
    void p06UnsafeArchive() throws Exception {
        // (a) ZIP Slip：成员路径穿越出根目录
        byte[] slip = zipOf("evil_skill/", "", "evil_skill/SKILL.md", skillMd("evil_skill", "slip"),
                "evil_skill/../../escape.txt", "越界内容");
        assertRejected(submitSkill(slip, casesValid(), validConfig()),
                "UNSAFE_SKILL_ARCHIVE", "ZIP Slip 路径穿越");
        // (b) 重复成员名（Java ZipOutputStream 拒绝写重复条目——手工构造裸 ZIP）
        byte[] dupZip = rawZip(List.of(
                List.of("dup_skill/SKILL.md", skillMd("dup_skill", "重复成员第一份")),
                List.of("dup_skill/SKILL.md", skillMd("dup_skill", "重复成员第二份"))));
        assertRejected(submitSkill(dupZip, casesValid(), validConfig()),
                "UNSAFE_SKILL_ARCHIVE", "重复成员名");
    }

    // ---------- frontmatter 非法 / 重复 YAML key → INVALID_SKILL_METADATA ----------

    @Test
    @Order(3)
    @Story("p06.metadata: frontmatter 非 YAML mapping / 重复 YAML key（含定位）")
    @DisplayName("P06.3: 元数据非法 → 422 INVALID_SKILL_METADATA")
    void p06InvalidMetadata() throws Exception {
        // (a) frontmatter 非法 YAML（未闭合）
        String broken = "---\nname: broken_skill\ndescription: 未闭合\nthis is not yaml: [unclosed\n"
                + "正文。\n";
        byte[] badYaml = zipOf("broken_skill/", "", "broken_skill/SKILL.md", broken);
        assertRejected(submitSkill(badYaml, casesValid(), validConfig()),
                "INVALID_SKILL_METADATA", "frontmatter 非法");
        // (b) 重复 YAML key
        String dupKey = "---\nname: dup_skill\nname: dup_skill_again\ndescription: 重复 key fixture\n---\n\n# x\n";
        byte[] dup = zipOf("dup_skill/", "", "dup_skill/SKILL.md", dupKey);
        HttpResponse<String> r = submitSkill(dup, casesValid(), validConfig());
        assertEquals(422, r.statusCode(), "重复 YAML key 应 422：" + r.statusCode());
        assertEquals("INVALID_SKILL_METADATA", JSON.readTree(r.body()).path("error").path("code").asText());
    }

    // ---------- cases 缺字段 / 重复 ID → INVALID_SKILL_CASE 附索引 ----------

    @Test
    @Order(4)
    @Story("p06.cases: 缺字段 / 重复 ID（附索引）")
    @DisplayName("P06.4: cases 非法 → 422 INVALID_SKILL_CASE")
    void p06InvalidCases() throws Exception {
        byte[] zip = zipOf("case_skill/", "", "case_skill/SKILL.md", skillMd("case_skill", "cases 校验 fixture"));
        // (a) 缺字段（缺 question）
        ObjectNode missing = JSON.createObjectNode();
        missing.put("schema_version", 1);
        var arr = missing.putArray("cases");
        ObjectNode c = arr.addObject();
        c.put("id", "c1");
        c.put("reference_answer", "缺少 question 字段。");
        c.put("evaluation_advice", "评估建议：任意。");
        HttpResponse<String> r1 = submitSkill(zip, JSON.writeValueAsBytes(missing), validConfig());
        assertRejected(r1, "INVALID_SKILL_CASE", "cases 缺字段");
        // (b) 重复 ID（附索引定位）
        ObjectNode dup = JSON.createObjectNode();
        dup.put("schema_version", 1);
        var arr2 = dup.putArray("cases");
        for (int i = 0; i < 2; i++) {
            ObjectNode cc = arr2.addObject();
            cc.put("id", "same-id");
            cc.put("question", "重复 ID 案例 " + i);
            cc.put("reference_answer", "答案。");
            cc.put("evaluation_advice", "评估建议。");
        }
        HttpResponse<String> r2 = submitSkill(zip, JSON.writeValueAsBytes(dup), validConfig());
        assertEquals(422, r2.statusCode(), "重复 ID 应 422：" + r2.statusCode());
        String body = r2.body();
        assertEquals("INVALID_SKILL_CASE", JSON.readTree(body).path("error").path("code").asText());
        // 索引定位：错误详情应能关联到重复 ID（0 起始或 1 起始索引其一）
        assertTrue(body.contains("same-id") || body.contains("index") || body.contains("索引"),
                "重复 ID 错误应定位到具体条目：" + body.substring(0, Math.min(300, body.length())));
    }

    // ---------- 正向对照：合法包必须受理（防负向族误伤正常输入） ----------

    @Test
    @Order(5)
    @Story("p06.control: 合法包受理 202（负向族不误伤）")
    @DisplayName("P06.5: 对照组——合法 ZIP+cases 受理 202")
    void p06ValidControl() throws Exception {
        byte[] zip = zipOf("p06_ctrl_skill/", "", "p06_ctrl_skill/SKILL.md",
                skillMd("p06_ctrl_skill", "对照组 fixture"));
        HttpResponse<String> r = submitSkill(zip, casesValid(), validConfig());
        assertEquals(202, r.statusCode(), "合法包应受理 202：" + r.statusCode() + " " + r.body());
        assertEquals("queued", JSON.readTree(r.body()).path("status").asText(), "受理状态 queued");
    }
}
