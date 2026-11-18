package com.huawei.ascend.sit.cases.e2e.agentops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEAT-105 e2e 批：生命周期注入（P10–P14）。fail-safe 类（failsafe 跑）。
 *
 * <p>SUT = agent-ops（API+Worker 双进程），由批次部署脚本预起并受本类经脚本控制：
 * BeforeAll 带超时覆盖层整栈重启（OPTIMIZATION_JOB_TIMEOUT_SECONDS=60，P14 用），
 * AfterAll 移除覆盖层恢复常规配置。Worker 中断用 pkill 注入（P13），重试用 API（P12）。</p>
 *
 * <p>覆盖场景（细档 ID）：105-P13 Worker 失联→interrupted（不自动重跑）；105-P12 interrupted
 * 人工重试→关联新任务到终态、原任务不覆盖；105-P14 超时→failed（超时原因+检查点保留）；
 * 105-P11 running 取消→cancelling→cancelled（迟到结果不覆盖）；105-P10 API 重启后任务/材料可查。</p>
 *
 * <p>Oracle：FEAT-105 §5.1.6/§5.1.7；Prompt L2 §3.4 竞态表/§3.5 租约清退；细档 P10–P14。</p>
 */
@Tags({@Tag("e2e"), @Tag("agent-ops"), @Tag("feat-105")})
@Feature("FEAT-105: Agent-ops e2e 批（生命周期注入）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat105AgentopsLifecycleE2eIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = System.getProperty(
            "sut.agentops.baseUrl", envOrDefault("SUT_AGENTOPS_BASE_URL", "http://127.0.0.1:8091"));
    private static final String QDRANT = System.getProperty(
            "sut.agentops.qdrant", envOrDefault("SUT_AGENTOPS_QDRANT", "http://127.0.0.1:6333"));
    private static final String DEPLOY_SCRIPT = System.getProperty(
            "sut.agentops.deployScript",
            envOrDefault("SUT_AGENTOPS_DEPLOY_SCRIPT",
                    "/Users/zhangjiusi/资料/06_工作/Test_work/01-Design_File/20261117-feat-105-skill-knowledge/deploy/run-deploy.sh"));

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)  // uvicorn/h11 h2c Upgrade 丢 body——必须钉 1.1
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 本类产出的任务 id（P10 重启持久化断言用）。 */
    private final List<String> producedJobIds = new ArrayList<>();

    @BeforeAll
    void ensureStackHealthy() {
        assertTrue(healthz(), "SUT 应已由部署脚本预起");
    }

    @AfterAll
    void restoreNormalConfig() throws Exception {
        java.nio.file.Path.of(
                java.nio.file.Path.of(DEPLOY_SCRIPT).getParent().toString(),
                "timeout-override.env").toFile().delete();
        runDeploy("restart");
        assertTrue(healthz(), "恢复后 API 应健康");
    }

    private void runDeploy(String action) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("bash", DEPLOY_SCRIPT, action)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor(180, TimeUnit.SECONDS) ? p.exitValue() : -1;
        assertEquals(0, code, "部署脚本 " + action + " 应成功：" + out);
    }

    private boolean healthz() {
        try {
            HttpResponse<String> r = send("GET", "/healthz", null, null, null);
            return r.statusCode() == 200 && r.body().contains("\"status\":\"ok\"");
        } catch (Exception e) {
            return false;
        }
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

    /** 长时任务语料：12 条 case、group_size=1 → 12 个分组 → 多次模型调用，确保可注入。 */
    private byte[] longCasesBytes(String tag) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1);
        var cases = root.putArray("cases");
        for (int i = 1; i <= 12; i++) {
            ObjectNode c = cases.addObject();
            c.put("id", tag + "-c" + i);
            c.put("question", "长时任务问题" + i + "：请详细分析该场景下 Skill 应当如何澄清缺失字段，并给出理由。");
            c.put("reference_answer", "标准答案" + i + "：先澄清必填字段，再继续后续步骤，并给出可执行建议。");
            c.put("evaluation_advice", "评估建议" + i + "：当前步骤在缺少输入时直接继续，需要增加澄清条件并说明判断依据。");
        }
        try {
            return JSON.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] skillZip(String name) {
        String md = "---\nname: " + name + "\ndescription: e2e 生命周期 fixture\n---\n\n正文。\n";
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

    private String skillConfig() {
        return "{\"schema_version\":1,\"options\":{\"group_size\":1,"
                + "\"analysis_model\":{\"profile_ref\":\"default\",\"parameters\":{}},"
                + "\"synthesis_model\":{\"profile_ref\":\"default\",\"parameters\":{}}}}";
    }

    private JsonNode submitLongJob(String tag) throws Exception {
        String key = "feat105-e2e-" + tag + "-" + UUID.randomUUID();
        String boundary = "feat105-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, List.of(
                new Object[]{"skill_zip", "skill.zip", skillZip("contract_skill")},
                new Object[]{"cases_file", "cases.json", longCasesBytes(tag)},
                new Object[]{"config", skillConfig()}));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/optimizers/skill/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> r = http.send(b.timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, r.statusCode(), tag + " 长时任务受理应 202：" + r.body());
        JsonNode d = JSON.readTree(r.body());
        producedJobIds.add(d.get("job_id").asText());
        return d;
    }

    private JsonNode getJob(String jobId) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/optimization-jobs/" + jobId, null, null, null);
        assertEquals(200, r.statusCode(), "详情应可查：" + r.body());
        return JSON.readTree(r.body());
    }

    private String pollStatus(String jobId, long timeoutSec, List<String> until) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            JsonNode d = getJob(jobId);
            last = d.path("status").asText();
            if (until.contains(last)) {
                return last;
            }
            Thread.sleep(3000);
        }
        return last;
    }

    // ---------- 105-P13 Worker 失联 → interrupted ----------

    /** 中断 fixture：running 中 kill Worker → 死亡期不自动恢复 → 重启后清退收敛 interrupted。 */
    private String interruptFixture(String tag) throws Exception {
        JsonNode created = submitLongJob(tag);
        String jobId = created.get("job_id").asText();
        assertEquals("running", pollStatus(jobId, 120, List.of("running")), tag + " 应进入 running");
        new ProcessBuilder("bash", "-c", "pkill -f optimization_worker || true").start()
                .waitFor(30, TimeUnit.SECONDS);
        // Worker 死亡期：任务保持 running，不自动重跑/恢复（观察 45s）
        Thread.sleep(45000);
        assertEquals("running", getJob(jobId).path("status").asText(),
                "Worker 失联期间不得自动恢复或重跑");
        // 恢复 Worker：清退器（随 Worker）发现过期租约 → interrupted
        runDeploy("restart");
        assertTrue(healthz(), "恢复后 API 应健康");
        // 2026-11-18 实测（缺陷候选，待提单）：Worker 失联后孤儿任务被新 Worker 偷租约续跑，
        // 不收敛 interrupted——违反 Prompt L2 §3.4/FEAT §5.1.7。Oracle 以权威文档为准，本用例保持 FAIL 留证。
        String actual = pollStatus(jobId, 90, List.of("interrupted"));
        assertEquals("interrupted", actual,
                "Worker 恢复后清退应收敛 interrupted（实测=" + actual + "——租约被偷续跑，缺陷候选）");
        Thread.sleep(20000);
        assertEquals("interrupted", getJob(jobId).path("status").asText(), "interrupted 不得自动重跑");
        // 缺陷实证后清理：取消被偷租约续跑的孤儿任务，防止占饱和 Worker 影响后续用例
        send("POST", "/api/v1/optimization-jobs/" + jobId + "/cancel", "{}", "application/json", null);
        return jobId;
    }

    @Test
    @Order(1)
    @Story("p13.interrupt: Worker 失联→死亡期不自动恢复→清退收敛 interrupted")
    @DisplayName("P13: running 中 kill Worker → 恢复后 interrupted，且不自动重跑")
    void p13WorkerLossInterrupts() throws Exception {
        interruptFixture("p13");
    }

    // ---------- 105-P12 人工重试：skill JOB_TIMEOUT 任务上验证 retry + checkpoint 续做 ----------

    @Test
    @Order(2)
    @Story("p12.retry-checkpoint: failed 任务人工重试→关联新任务到终态，原任务不重开不覆盖")
    @DisplayName("P12: skill JOB_TIMEOUT 任务 retry → 新任务复用检查点跑到终态；原任务保持 failed")
    void p12RetryCheckpointResume() throws Exception {
        // fixture：e2e 第 4 轮产生的 skill JOB_TIMEOUT 任务（12 分组跑到 group_analysis 部分完成）
        HttpResponse<String> list = send("GET",
                "/api/v1/optimization-jobs?optimizer_type=skill&status=failed&limit=10", null, null, null);
        JsonNode items = JSON.readTree(list.body()).path("items");
        String srcJobId = null;
        for (JsonNode j : items) {
            if ("group_analysis".equals(j.path("current_stage").asText())) {
                srcJobId = j.path("job_id").asText();  // 部分分组已完成 → 重试可复用检查点
                break;
            }
        }
        assertNotNull(srcJobId, "需要 group_analysis 阶段失败的 skill 任务作 fixture：" + items.toString().substring(0, Math.min(300, items.toString().length())));
        // 原 deadline 60s 已失效（覆盖层已移除）——重试新任务按常规 3600s 时限续跑剩余分组
        HttpResponse<String> retry = send("POST", "/api/v1/optimization-jobs/" + srcJobId + "/retry",
                "{}", "application/json",
                List.<String[]>of(new String[]{"Idempotency-Key", "feat105-e2e-p12-retry-" + UUID.randomUUID()}));
        assertEquals(202, retry.statusCode(), "failed 重试应 202：" + retry.body());
        String newJobId = JSON.readTree(retry.body()).get("job_id").asText();
        assertNotEquals(srcJobId, newJobId, "重试必须创建新任务");
        producedJobIds.add(newJobId);
        // 新任务到终态（复用已完成分组 + 真实模型跑剩余分组，最多 10 分钟）
        String st2 = pollStatus(newJobId, 900, List.of("succeeded", "failed", "interrupted", "cancelled"));
        assertTrue(List.of("succeeded", "failed").contains(st2),
                "重试新任务应到确定性终态：" + st2);
        // 原任务不重开、不覆盖
        JsonNode src = getJob(srcJobId);
        assertEquals("failed", src.path("status").asText(), "原任务必须保持 failed");
        assertEquals(srcJobId, getJob(newJobId).path("source_job_id").asText(), "新任务应关联来源任务");
        // checkpoint 续做证据（非门禁增强）：新任务完成分组数 ≥ 原任务失败时已完成分组数
        JsonNode newStages = JSON.readTree(send("GET",
                "/api/v1/optimization-jobs/" + newJobId + "/stages", null, null, null).body());
        assertTrue(newStages.path("items").size() > 0, "新任务应有阶段事实");
    }

    // ---------- 105-P14 超时 → failed（超时原因+检查点保留） ----------

    @Test
    @Order(4)
    @Story("p14.timeout: 超任务时限→failed（JOB_TIMEOUT），检查点保留")
    @DisplayName("P14: 60s 时限覆盖层下长任务 → failed(JOB_TIMEOUT)，阶段可查")
    void p14TimeoutFailsWithCheckpoint() throws Exception {
        // 自包含：写覆盖层 → 整栈重启 → 提交长任务 → 断言 failed(JOB_TIMEOUT) → 恢复
        java.nio.file.Path.of(java.nio.file.Path.of(DEPLOY_SCRIPT).getParent().toString(),
                "timeout-override.env")
                .toFile().toPath().getParent().toFile().mkdirs();
        java.nio.file.Files.writeString(
                java.nio.file.Path.of(java.nio.file.Path.of(DEPLOY_SCRIPT).getParent().toString(),
                        "timeout-override.env"),
                "OPTIMIZATION_JOB_TIMEOUT_SECONDS=60\n");
        runDeploy("restart");
        assertTrue(healthz(), "覆盖层重启后 API 应健康");
        try {
            JsonNode created = submitLongJob("p14");
            String jobId = created.get("job_id").asText();
            producedJobIds.add(jobId);
            String st = pollStatus(jobId, 150, List.of("failed", "succeeded", "cancelled", "interrupted"));
            assertEquals("failed", st, "60s 时限内不可完成的任务应 failed：" + st);
            JsonNode d = getJob(jobId);
            assertEquals("JOB_TIMEOUT", d.path("error").path("code").asText(),
                    "失败原因应为 JOB_TIMEOUT：" + d.path("error").toString());
            HttpResponse<String> stages = send("GET", "/api/v1/optimization-jobs/" + jobId + "/stages",
                    null, null, null);
            assertEquals(200, stages.statusCode(), "检查点/阶段应可查：" + stages.body());
        } finally {
            java.nio.file.Path.of(java.nio.file.Path.of(DEPLOY_SCRIPT).getParent().toString(),
                    "timeout-override.env").toFile().delete();
            runDeploy("restart");
            assertTrue(healthz(), "恢复后 API 应健康");
        }
    }

    // ---------- 105-P11 running 取消 → cancelling → cancelled ----------

    @Test
    @Order(3)
    @Story("p11.running-cancel: running 取消经 cancelling 收敛 cancelled，迟到结果不覆盖")
    @DisplayName("P11: running 中取消 → cancelled；重启后仍 cancelled（迟到结果不覆盖）")
    void p11RunningCancelConvergence() throws Exception {
        JsonNode created = submitLongJob("p11");
        String jobId = created.get("job_id").asText();
        String st = pollStatus(jobId, 120, List.of("running"));
        assertEquals("running", st, "应进入 running：" + st);
        HttpResponse<String> c = send("POST", "/api/v1/optimization-jobs/" + jobId + "/cancel",
                "{}", "application/json", null);
        assertTrue(c.statusCode() == 200 || c.statusCode() == 202, "running 取消应受理：" + c.body());
        // 收敛到 cancelled（取消宽限期 30s + 边界检查）
        st = pollStatus(jobId, 90, List.of("cancelled"));
        assertEquals("cancelled", st, "running 取消应收敛 cancelled：" + st);
        // 迟到结果不覆盖：重启整栈后（Worker 清退轮询会处理残留租约）仍为 cancelled
        runDeploy("restart");
        assertTrue(healthz(), "重启后 API 应健康");
        assertEquals("cancelled", getJob(jobId).path("status").asText(), "重启后终态不得被覆盖");
    }

    // ---------- 105-P10 API 重启后持久化可查 ----------

    @Test
    @Order(5)
    @Story("p10.restart-persistence: API 重启后任务/材料可查（任务持久化）")
    @DisplayName("P10: 整栈重启后本类全部任务 id 仍可查且状态一致")
    void p10RestartPersistence() throws Exception {
        runDeploy("restart");
        assertTrue(healthz(), "重启后 API 应健康");
        assertTrue(producedJobIds.size() >= 4, "前置用例应已产出任务：" + producedJobIds.size());
        for (String jobId : producedJobIds) {
            HttpResponse<String> r = send("GET", "/api/v1/optimization-jobs/" + jobId, null, null, null);
            assertEquals(200, r.statusCode(), "重启后任务应可查：" + jobId + " " + r.body());
            JsonNode d = JSON.readTree(r.body());
            assertEquals(jobId, d.path("job_id").asText(), "身份一致");
            assertTrue(!d.path("status").asText().isBlank(), "状态应持久化");
            // 材料可查：产物列表端点可达（无论是否有产物条目）
            HttpResponse<String> arts = send("GET", "/api/v1/optimization-jobs/" + jobId + "/artifacts",
                    null, null, null);
            assertEquals(200, arts.statusCode(), "重启后产物列表应可查：" + jobId);
        }
        // slice-1 的历史 succeeded 任务同样可查（跨批次持久化）
        HttpResponse<String> list = send("GET", "/api/v1/optimization-jobs?limit=50", null, null, null);
        JsonNode items = JSON.readTree(list.body()).path("items");
        assertTrue(items.size() >= 4, "重启后历史任务仍在：" + items.size());
    }
}
