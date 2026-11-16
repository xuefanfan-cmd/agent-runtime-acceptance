package com.huawei.ascend.sit.cases.integration.agent_memory;

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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-103 生命周期与容量治理（软删除与保留期 + 容量与 LRU 淘汰）黑盒验收。
 *
 * <p>SUT = agent-memory（Python，{@code jiuwen_memory_entry.http_server}），由本类经 ProcessBuilder
 * 从 feature_source 源码目录直接拉起（offline profile + dev auth + root 身份）并自主管生命周期。
 * 全部断言落在 HTTP wire 契约上：</p>
 * <ul>
 *   <li>特性文档 §4.1 新端点：{@code POST /v1/restore}（恢复选择器 + limit，返回
 *       restored_ids / not_found_ids / skipped_ids）；</li>
 *   <li>§4.4 扩展端点：{@code POST /v1/space_usage}（active_count/forgotten_count/purged_count/
 *       superseded_count/archived_count/memory_count + max_units/usage_rate）；</li>
 *   <li>清扫手动触发入口：{@code POST /v1/sweep}（FRS-103 DEC-07 / 附录 A.1）；</li>
 *   <li>既有端点：{@code /v1/add /v1/search /v1/get /v1/delete /v1/update /v1/admin_set
 *       /v1/admin_get /v1/get_space_policy /v1/set_space_policy /v1/create_space /v1/audit}；</li>
 *   <li>错误封套：{@code {"error","message","retryable","request_id"}}，ValidationError→400、
 *       NotFoundError→404、未知 verb→404 UnknownVerb。</li>
 * </ul>
 *
 * <p>场景覆盖 = 测试设计矩阵全量 F103-01~F103-27（单一有序测试类，按依赖顺序执行）：</p>
 * <ul>
 *   <li>主矩阵 F103-01~16：软删除与保留期 + 容量与 LRU 淘汰主门禁；</li>
 *   <li>补充矩阵 F103-17~27（原 Feat103LifecycleCapacityGapBlackboxTest，2026-11-11 并入）：
 *       restore 选择器参数矩阵 tags/before/after/filters/limit（F103-17~20）、空间级保留期
 *       覆盖清扫判定（F103-21）、quotas 子键 batch/headroom/开关/LRU 兜底（F103-22~25）、
 *       ARCHIVED 态 restore 跳过（F103-26）、合规审计留痕（F103-27）。</li>
 * </ul>
 *
 * <p>期望值口径：特性文档 FEAT-103（2026-10-27）§3.1/§3.4/§3.5、§6.1/§6.4 与
 * FRS-103 v0.1 FR-01~FR-17 / AC-01~AC-15。
 * 红 = 产品行为与文档不符（实现缺口），不通过改用例消化。</p>
 */
@Tags({@Tag("integration"), @Tag("agent-memory"), @Tag("feat-103")})
@Feature("FEAT-103: agent-memory 生命周期与容量治理（软删除与保留期 + 容量与 LRU 淘汰）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat103MemoryLifecycleCapacityBlackboxTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT_TOKEN = "root-key";
    private static final String ORG = "local";
    private static final String DEFAULT_RETENTION_DAYS = "30";

    /** SUT 源码目录（feature_source）；系统属性 -Dsut.memory103.sourceDir 或 env SUT_MEMORY_SOURCE_DIR 可覆盖。 */
    private static final String SOURCE_DIR = System.getProperty(
            "sut.memory103.sourceDir",
            envOrDefault("SUT_MEMORY_SOURCE_DIR", "D:/code/test/agent-memory"));
    /** SUT 端口；缺省取一个空闲端口，避免与手工拉起的实例或遗留进程冲突。 */
    private static final int PORT = Integer.parseInt(System.getProperty("sut.memory103.port", "0"));

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Process sut;
    private int port;
    private String base;
    private Path devConfigFile;
    private Path engineConfigFile;

    @BeforeAll
    void startSut() throws Exception {
        assumeTrue(Files.isDirectory(Path.of(SOURCE_DIR)),
                "SUT 源码目录不存在：" + SOURCE_DIR + " — 请配置 -Dsut.memory103.sourceDir");
        port = PORT > 0 ? PORT : freePort();
        base = "http://127.0.0.1:" + port;
        devConfigFile = writeDevIdentityConfig();
        engineConfigFile = writeEngineConfig();
        startProcess();
        waitHealthy();
    }

    @AfterAll
    void stopSut() {
        if (sut != null && sut.isAlive()) {
            sut.destroy();
            try {
                if (!sut.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    sut.destroyForcibly();
                    sut.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sut.destroyForcibly();
            }
        }
        if (devConfigFile != null) {
            try {
                Files.deleteIfExists(devConfigFile);
            } catch (IOException ignored) {
                // 清理失败不影响测试结论
            }
        }
        if (engineConfigFile != null) {
            try {
                Files.deleteIfExists(engineConfigFile);
            } catch (IOException ignored) {
                // 清理失败不影响测试结论
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** dev 身份配置层：root-key → Role.ROOT（ADMINISTER_SYSTEM 要求 root 角色）。 */
    private Path writeDevIdentityConfig() throws IOException {
        Path file = Path.of("target", "sit-logs", "feat103-dev-identities.json");
        Files.createDirectories(file.getParent());
        String json = "{\"http\": {\"dev_identities\": {\"" + ROOT_TOKEN + "\": "
                + "{\"actor\": {\"org\": \"local\", \"user\": \"developer\"}, \"role\": \"root\"}}}}";
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 引擎配置层：offline profile 默认装配 InMemoryEngine（要求 scope.space==""，不支持空间隔离）。
     * FEAT-103 场景依赖 org/space 硬隔离（FRS §11.3），经 memory_api.engine.default.target=agent
     * 切换到支持空间维的 AgentEngine，其余组件沿用内置默认（embedder=hashing、llm=echo，全离线）。
     */
    private Path writeEngineConfig() throws IOException {
        Path file = Path.of("target", "sit-logs", "feat103-engine-config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file,
                "{\"memory_api\": {\"engine\": {\"default\": {\"target\": \"agent\"}}}}",
                StandardCharsets.UTF_8);
        return file;
    }

    private void startProcess() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "python", "-m", "jiuwen_memory_entry.http_server",
                "--auth-mode", "dev", "--host", "127.0.0.1", "--port", String.valueOf(port),
                engineConfigFile.toAbsolutePath().toString(),
                devConfigFile.toAbsolutePath().toString());
        pb.directory(Path.of(SOURCE_DIR).toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(Path.of("target", "sit-logs", "feat103-sut-" + port + ".log").toFile());
        sut = pb.start();
    }

    private void waitHealthy() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder().uri(URI.create(base + "/healthz"))
                                .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // 服务未就绪，继续轮询
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("SUT 90s 内未就绪：" + base + "/healthz");
    }

    // ------------------------------------------------------------ wire helpers

    private HttpResponse<String> post(String verb, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/" + verb))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode scope(String space) {
        ObjectNode node = JSON.createObjectNode();
        node.put("org", ORG);
        node.put("space", space);
        return node;
    }

    private void ensureSpace(String space) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        ObjectNode spec = req.putObject("spec");
        spec.put("org", ORG);
        spec.put("space", space);
        spec.put("display_name", "FEAT-103 test space " + space);
        post("create_space", req); // 已存在时的失败可容忍
    }

    /** 写入一条 ACTIVE 记忆，返回分配的 unit id。 */
    private String addMemory(String space, String content) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("content", content);
        req.set("scope", scope(space));
        HttpResponse<String> response = post("add", req);
        assertEquals(200, response.statusCode(),
                "前置：写入记忆应 200 — body=" + response.body());
        JsonNode units = JSON.readTree(response.body());
        assertTrue(units.isArray() && !units.isEmpty(),
                "前置：add 应返回非空记忆单元数组 — body=" + response.body());
        String id = units.get(0).path("id").asText("");
        assertFalse(id.isEmpty(), "前置：add 返回的记忆单元应含非空 id — body=" + response.body());
        return id;
    }

    /** 对 unit_ids 执行 delete(mode=forget)。 */
    private HttpResponse<String> forget(String space, List<String> unitIds) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        ObjectNode selector = req.putObject("selector");
        selector.set("unit_ids", JSON.valueToTree(unitIds));
        selector.put("mode", "forget");
        return post("delete", req);
    }

    private HttpResponse<String> getUnit(String space, String unitId) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("unit_id", unitId);
        req.set("scope", scope(space));
        return post("get", req);
    }

    /** 检索并返回命中的 unit_id 列表。 */
    private List<String> searchUnitIds(String space, String query, String asOf) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("query", query);
        ObjectNode context = req.putObject("context");
        context.set("scope", scope(space));
        if (asOf != null) {
            req.put("as_of", asOf);
        }
        HttpResponse<String> response = post("search", req);
        assertEquals(200, response.statusCode(), "前置：search 应 200 — body=" + response.body());
        List<String> ids = new ArrayList<>();
        for (JsonNode item : JSON.readTree(response.body()).path("items")) {
            ids.add(item.path("unit_id").asText());
        }
        return ids;
    }

    /** 手动触发清扫任务（FRS-103 DEC-07 / 附录 A.1 sweep 命令的 HTTP 触达）。 */
    private HttpResponse<String> triggerSweep() throws Exception {
        return post("sweep", JSON.createObjectNode());
    }

    private void setGlobalRetention(String days) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("key", "lifecycle.forgotten_retention_days");
        req.put("value", days);
        HttpResponse<String> response = post("admin_set", req);
        assertEquals(200, response.statusCode(),
                "前置：全局保留期键 lifecycle.forgotten_retention_days 必须可运行时设置（FR-07）"
                        + " — body=" + response.body());
    }

    private void setPurgeSwitch(boolean enabled) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("key", "lifecycle.purge_forgotten.enabled");
        req.put("value", Boolean.toString(enabled));
        HttpResponse<String> response = post("admin_set", req);
        assertEquals(200, response.statusCode(),
                "前置：物理删除开关 lifecycle.purge_forgotten.enabled 必须可运行时设置（FR-10）"
                        + " — body=" + response.body());
    }

    private void setSpaceQuotas(String space, String maxUnits, String evictionEnabled,
                                String evictionBatchSize, String evictionHeadroom) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("org", ORG);
        req.put("space", space);
        ObjectNode policy = req.putObject("policy");
        ObjectNode quotas = policy.putObject("quotas");
        if (maxUnits != null) {
            quotas.put("max_units", maxUnits);
        }
        if (evictionEnabled != null) {
            quotas.put("eviction_enabled", evictionEnabled);
        }
        if (evictionBatchSize != null) {
            quotas.put("eviction_batch_size", evictionBatchSize);
        }
        if (evictionHeadroom != null) {
            quotas.put("eviction_headroom", evictionHeadroom);
        }
        HttpResponse<String> response = post("set_space_policy", req);
        assertEquals(200, response.statusCode(),
                "前置：set_space_policy 写 quotas 应 200 — body=" + response.body());
    }

    private void setSpaceRetention(String space, String days) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("org", ORG);
        req.put("space", space);
        req.putObject("policy").putObject("retention").put("forgotten_retention_days", days);
        HttpResponse<String> response = post("set_space_policy", req);
        assertEquals(200, response.statusCode(),
                "前置：set_space_policy 写 retention.forgotten_retention_days 应 200 — body=" + response.body());
    }

    private JsonNode restore(ObjectNode selector) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.set("selector", selector);
        HttpResponse<String> response = post("restore", req);
        assertEquals(200, response.statusCode(), "restore 应 200 — body=" + response.body());
        return JSON.readTree(response.body());
    }

    /** 审计查询：filters 为 {action: ...} 键值对。 */
    private JsonNode auditEvents(String action) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.putObject("filters").put("action", action);
        req.put("limit", 100);
        HttpResponse<String> response = post("audit", req);
        assertEquals(200, response.statusCode(), "audit 查询应 200 — body=" + response.body());
        return JSON.readTree(response.body());
    }

    private boolean auditTrailContains(JsonNode events, String action, String detailKey, String unitId) {
        for (JsonNode event : events) {
            if (action.equals(event.path("action").asText())
                    && event.path("detail").path(detailKey).asText("").contains(unitId)) {
                return true;
            }
        }
        return false;
    }

    private long countLifecycle(String space, List<String> unitIds, String expected) throws Exception {
        long count = 0;
        for (String id : unitIds) {
            if (expected.equals(lifecycleOf(space, id))) {
                count++;
            }
        }
        return count;
    }

    /** 写入一条带 tags / user_metadata / occurred_at 的记忆，返回 unit id。 */
    private String addMemoryFull(String space, String content, List<String> tags,
                                 String metaKey, String metaValue, String occurredAt) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("content", content);
        req.set("scope", scope(space));
        if (tags != null) {
            req.set("tags", JSON.valueToTree(tags));
        }
        if (metaKey != null) {
            req.putObject("user_metadata").put(metaKey, metaValue);
        }
        if (occurredAt != null) {
            req.put("occurred_at", occurredAt);
        }
        HttpResponse<String> response = post("add", req);
        assertEquals(200, response.statusCode(),
                "前置：写入记忆应 200 — body=" + response.body());
        return firstUnitId(response.body());
    }

    private String firstUnitId(String body) throws Exception {
        JsonNode units = JSON.readTree(body == null ? "[]" : body);
        assertTrue(units.isArray() && !units.isEmpty(),
                "前置：add 应返回非空记忆单元数组 — body=" + body);
        String id = units.get(0).path("id").asText("");
        assertFalse(id.isEmpty(), "前置：add 返回的记忆单元应含非空 id — body=" + body);
        return id;
    }

    // ============================================================ F103-01

    @Test
    @Order(1)
    @Tag("story-feat-103-01")
    @Story("FEAT-103.F103-01: FORGET 后记忆含遗忘时间戳 lifecycle=forgotten 且实时检索不召回")
    @DisplayName("F103-01 FORGET 记录遗忘时间戳并退出检索")
    void f10301_forgetRecordsForgottenTimestamp() throws Exception {
        String space = "f103-timestamp";
        ensureSpace(space);
        String id = addMemory(space, "FEAT103 保留期时间戳验证记忆 alpha");

        HttpResponse<String> delete = forget(space, List.of(id));
        assertEquals(200, delete.statusCode(),
                "FR-01：delete(mode=forget) 应 200 — body=" + delete.body());
        assertTrue(JSON.readTree(delete.body()).toString().contains(id),
                "delete 响应应包含被遗忘的 id " + id + " — body=" + delete.body());

        HttpResponse<String> get = getUnit(space, id);
        assertEquals(200, get.statusCode(), "遗忘态记忆应可点读（运维可见性）— body=" + get.body());
        JsonNode unit = JSON.readTree(get.body());
        assertEquals("forgotten", unit.path("lifecycle").asText(""),
                "AC-01：遗忘后 lifecycle 应为 forgotten — body=" + get.body());
        String forgottenAt = unit.path("system_metadata").path("forgotten_at").asText("");
        assertFalse(forgottenAt.isEmpty(),
                "A1/FR-01：遗忘操作必须在 system_metadata 写入 forgotten_at（ISO 时间戳，"
                        + "保留期计算起点）— 实得 system_metadata=" + unit.path("system_metadata"));
        assertTrue(forgottenAt.contains("T") || forgottenAt.contains(" "),
                "forgotten_at 应为时间戳格式（ISO-8601）— 实得 " + forgottenAt);

        List<String> hits = searchUnitIds(space, "alpha", null);
        assertFalse(hits.contains(id),
                "AC-01：遗忘态记忆应退出检索索引不被召回 — hits=" + hits);
    }

    // ============================================================ F103-02

    @Test
    @Order(2)
    @Tag("story-feat-103-02")
    @Story("FEAT-103.F103-02: add/update 伪造内核保留键 forgotten_at 必须拒绝且不落值")
    @DisplayName("F103-02 伪造遗忘时间戳拒绝")
    void f10302_forgedForgottenTimestampRejected() throws Exception {
        // 1) add 携带 forgotten_at
        String space = "f103-forgery-add";
        ensureSpace(space);
        ObjectNode addReq = JSON.createObjectNode();
        addReq.put("content", "FEAT103 保留键伪造校验记忆 beta");
        addReq.set("scope", scope(space));
        addReq.putObject("system_metadata").put("forgotten_at", "2026-01-01T00:00:00Z");
        HttpResponse<String> add = post("add", addReq);
        assertTrue(add.statusCode() >= 400,
                "A1/§6.1：forgotten_at 为内核保留键，经 add 伪造必须拒绝 — 实得 "
                        + add.statusCode() + " body=" + add.body());
        assertEquals("ValidationError", JSON.readTree(add.body()).path("error").asText(),
                "错误封套 error 应为 ValidationError — body=" + add.body());

        // 2) update 携带 forgotten_at
        String space2 = "f103-forgery-update";
        ensureSpace(space2);
        String id = addMemory(space2, "FEAT103 保留键伪造校验记忆 gamma");
        ObjectNode updateReq = JSON.createObjectNode();
        updateReq.put("unit_id", id);
        updateReq.set("scope", scope(space2));
        updateReq.putObject("patch").putObject("system_metadata")
                .put("forgotten_at", "2026-01-01T00:00:00Z");
        HttpResponse<String> update = post("update", updateReq);
        assertTrue(update.statusCode() >= 400,
                "A1/§6.1：经 update 伪造 forgotten_at 必须拒绝 — 实得 "
                        + update.statusCode() + " body=" + update.body());
        assertEquals("ValidationError", JSON.readTree(update.body()).path("error").asText(),
                "错误封套 error 应为 ValidationError — body=" + update.body());

        // 3) 两次伪造均不得使 forgotten_at 落到记忆单元
        HttpResponse<String> get = getUnit(space2, id);
        assertEquals(200, get.statusCode());
        JsonNode metadata = JSON.readTree(get.body()).path("system_metadata");
        assertFalse(metadata.has("forgotten_at"),
                "伪造被拒后 forgotten_at 不得出现在记忆单元 system_metadata — 实得 " + metadata);
    }

    // ============================================================ F103-03

    @Test
    @Order(3)
    @Tag("story-feat-103-03")
    @Story("FEAT-103.F103-03: 保留期内 restore 恢复回 ACTIVE 且检索索引重建")
    @DisplayName("F103-03 保留期内 restore 恢复")
    void f10303_restoreWithinRetention() throws Exception {
        String space = "f103-restore";
        ensureSpace(space);
        String id = addMemory(space, "FEAT103 误删恢复验证记忆 delta");
        assertEquals(200, forget(space, List.of(id)).statusCode(), "前置：forget 应 200");

        ObjectNode req = JSON.createObjectNode();
        req.putObject("selector").set("unit_ids", JSON.valueToTree(List.of(id)));
        HttpResponse<String> restore = post("restore", req);
        assertEquals(200, restore.statusCode(),
                "A2/FR-05：POST /v1/restore 端点应存在且返回 200（404 UnknownVerb = 端点缺失）"
                        + " — body=" + restore.body());
        JsonNode result = JSON.readTree(restore.body());
        assertTrue(result.path("restored_ids").toString().contains(id),
                "RestoreResult.restored_ids 应包含 " + id + " — body=" + restore.body());
        assertTrue(result.path("not_found_ids").isEmpty(),
                "保留期内恢复 not_found_ids 应为空 — body=" + restore.body());
        assertTrue(result.path("skipped_ids").isEmpty(),
                "保留期内恢复 skipped_ids 应为空 — body=" + restore.body());

        HttpResponse<String> get = getUnit(space, id);
        assertEquals(200, get.statusCode());
        JsonNode unit = JSON.readTree(get.body());
        assertEquals("active", unit.path("lifecycle").asText(""),
                "AC-03：恢复后 lifecycle 应为 active — body=" + get.body());
        assertFalse(unit.path("system_metadata").has("forgotten_at"),
                "AC-03：恢复应清除 forgotten_at — body=" + get.body());

        List<String> hits = searchUnitIds(space, "delta", null);
        assertTrue(hits.contains(id),
                "AC-03：恢复应重建检索索引（记忆可召回）— hits=" + hits);
    }

    // ============================================================ F103-04

    @Test
    @Order(4)
    @Tag("story-feat-103-04")
    @Story("FEAT-103.F103-04: restore 对非遗忘态记忆归入 skipped_ids 不执行恢复")
    @DisplayName("F103-04 非遗忘态跳过恢复")
    void f10304_restoreSkipsNonForgotten() throws Exception {
        String space = "f103-skip";
        ensureSpace(space);
        String id = addMemory(space, "FEAT103 跳过恢复验证记忆 epsilon");

        ObjectNode req = JSON.createObjectNode();
        req.putObject("selector").set("unit_ids", JSON.valueToTree(List.of(id)));
        HttpResponse<String> restore = post("restore", req);
        assertEquals(200, restore.statusCode(), "FR-06：restore 应 200 — body=" + restore.body());
        JsonNode result = JSON.readTree(restore.body());
        assertTrue(result.path("skipped_ids").toString().contains(id),
                "FR-06：非 FORGOTTEN 态应归入 skipped_ids — body=" + restore.body());
        assertFalse(result.path("restored_ids").toString().contains(id),
                "非遗忘态不得被恢复 — body=" + restore.body());

        assertEquals("active", JSON.readTree(getUnit(space, id).body()).path("lifecycle").asText(""),
                "跳过恢复后记忆应保持 active");
    }

    // ============================================================ F103-05

    @Test
    @Order(5)
    @Tag("story-feat-103-05")
    @Story("FEAT-103.F103-05: restore selector 全空返回 ValidationError")
    @DisplayName("F103-05 selector 全空拒绝")
    void f10305_restoreEmptySelectorRejected() throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.putObject("selector");
        HttpResponse<String> restore = post("restore", req);
        assertEquals(400, restore.statusCode(),
                "A11/FR-05：selector 全空应返回 400 — 实得 " + restore.statusCode()
                        + " body=" + restore.body());
        JsonNode error = JSON.readTree(restore.body());
        assertEquals("ValidationError", error.path("error").asText(),
                "错误封套 error 应为 ValidationError — body=" + restore.body());
        assertTrue(error.path("message").asText("").contains("选择条件")
                        || error.path("message").asText("").toLowerCase().contains("selector")
                        || error.path("message").asText("").toLowerCase().contains("at least"),
                "错误 message 应明确提示至少给出一项选择条件 — body=" + restore.body());
    }

    // ============================================================ F103-06

    @Test
    @Order(6)
    @Tag("story-feat-103-06")
    @Story("FEAT-103.F103-06: 保留期 0 天到期后清扫标记 PURGED 并物理删除，KV 中单元消失")
    @DisplayName("F103-06 清扫已清除路径到期物理删除")
    void f10306_sweepPurgesExpiredForgotten() throws Exception {
        try {
            setGlobalRetention("0");
            String space = "f103-purge";
            ensureSpace(space);
            String id = addMemory(space, "FEAT103 到期清除验证记忆 zeta");
            purgedId = id;
            assertEquals(200, forget(space, List.of(id)).statusCode(), "前置：forget 应 200");

            HttpResponse<String> sweep = triggerSweep();
            assertEquals(200, sweep.statusCode(),
                    "A4/FR-03：清扫任务手动触发入口应存在且返回 200（404 = 触达入口缺失，"
                            + "FRS DEC-07 手动触发约定）— body=" + sweep.body());

            HttpResponse<String> get = getUnit(space, id);
            assertEquals(404, get.statusCode(),
                    "AC-02：保留期到期清扫后记忆本体应物理删除（404 NotFoundError）— 实得 "
                            + get.statusCode() + " body=" + get.body());
            assertEquals("NotFoundError", JSON.readTree(get.body()).path("error").asText(),
                    "错误封套 error 应为 NotFoundError — body=" + get.body());
        } finally {
            setGlobalRetention(DEFAULT_RETENTION_DAYS);
        }
    }

    // ============================================================ F103-07

    @Test
    @Order(7)
    @Tag("story-feat-103-07")
    @Story("FEAT-103.F103-07: PURGED 终态记忆 restore 归入 not_found_ids 不降级恢复")
    @DisplayName("F103-07 PURGED 终态不可恢复")
    void f10307_restoreRejectsPurgedTerminalState() throws Exception {
        // 设计矩阵 F103-07 前置：M1（F103-06 中被清扫标记 PURGED 并物理删除的记忆）。
        // 复用 F103-06 的 purgedId，而非新建 FORGOTTEN 记忆——保留期内 FORGOTTEN 可恢复，
        // 只有 PURGED/已物理删除的记忆才应归入 not_found_ids。
        String space = "f103-purge";
        String id = purgedId;
        assertFalse(id.isEmpty(),
                "前置：F103-06 应产出被清扫标记 PURGED 的记忆 id（依赖门禁，未达成即失败呈现）");

        ObjectNode req = JSON.createObjectNode();
        req.putObject("selector").set("unit_ids", JSON.valueToTree(List.of(id)));
        HttpResponse<String> restore = post("restore", req);
        assertEquals(200, restore.statusCode(), "restore 应 200 — body=" + restore.body());
        JsonNode result = JSON.readTree(restore.body());
        assertTrue(result.path("not_found_ids").toString().contains(id),
                "A3/FR-06：不可恢复（PURGED/已物理删除）记忆应归入 not_found_ids — body=" + restore.body());
        assertFalse(result.path("restored_ids").toString().contains(id),
                "PURGED 终态不得降级为恢复成功（§6.6 fail-fast）— body=" + restore.body());
    }

    // ============================================================ F103-08

    @Test
    @Order(8)
    @Tag("story-feat-103-08")
    @Story("FEAT-103.F103-08: purge 开关关闭时清扫不物理删除到期 FORGOTTEN")
    @DisplayName("F103-08 purge 开关暂停物理删除")
    void f10308_purgeSwitchPausesPhysicalDelete() throws Exception {
        try {
            setGlobalRetention("0");
            setPurgeSwitch(false);
            String space = "f103-purgeoff";
            ensureSpace(space);
            String id = addMemory(space, "FEAT103 暂停物理删除验证记忆 theta");
            assertEquals(200, forget(space, List.of(id)).statusCode(), "前置：forget 应 200");

            HttpResponse<String> sweep = triggerSweep();
            assertEquals(200, sweep.statusCode(), "清扫触发应 200 — body=" + sweep.body());

            HttpResponse<String> get = getUnit(space, id);
            assertEquals(200, get.statusCode(),
                    "A12/FR-10：purge 开关关闭时到期 FORGOTTEN 不被物理删除 — 实得 "
                            + get.statusCode() + " body=" + get.body());
            assertEquals("forgotten", JSON.readTree(get.body()).path("lifecycle").asText(""),
                    "A12：记忆应永久停留在 forgotten 态用于排查 — body=" + get.body());
        } finally {
            setPurgeSwitch(true);
            setGlobalRetention(DEFAULT_RETENTION_DAYS);
        }
    }

    // ============================================================ F103-09

    @Test
    @Order(9)
    @Tag("story-feat-103-09")
    @Story("FEAT-103.F103-09: 全局保留期键可热调，空间级 retention 覆盖可写可读")
    @DisplayName("F103-09 保留期策略键与空间级覆盖")
    void f10309_retentionPolicyKeys() throws Exception {
        try {
            // 1) 全局键热调
            setGlobalRetention("90");
            ObjectNode getReq = JSON.createObjectNode();
            getReq.put("key", "lifecycle.forgotten_retention_days");
            HttpResponse<String> get = post("admin_get", getReq);
            assertEquals(200, get.statusCode(), "admin_get 应 200 — body=" + get.body());
            assertEquals("90", JSON.readTree(get.body()).asText(),
                    "FR-07：全局保留期键应读回刚写入的 \"90\"");

            // 2) 空间级 retention 覆盖
            String space = "f103-retention";
            ensureSpace(space);
            ObjectNode setReq = JSON.createObjectNode();
            setReq.put("org", ORG);
            setReq.put("space", space);
            setReq.putObject("policy").putObject("retention")
                    .put("forgotten_retention_days", "30");
            HttpResponse<String> set = post("set_space_policy", setReq);
            assertEquals(200, set.statusCode(),
                    "FR-07：空间策略 retention.forgotten_retention_days 应可写 — body=" + set.body());

            ObjectNode policyReq = JSON.createObjectNode();
            policyReq.put("org", ORG);
            policyReq.put("space", space);
            HttpResponse<String> policy = post("get_space_policy", policyReq);
            assertEquals(200, policy.statusCode(), "get_space_policy 应 200 — body=" + policy.body());
            JsonNode retention = JSON.readTree(policy.body()).path("retention");
            assertEquals("30", retention.path("forgotten_retention_days").asText(""),
                    "A6：空间级 retention.forgotten_retention_days 应读回 \"30\"（覆盖全局）— body="
                            + policy.body());
        } finally {
            setGlobalRetention(DEFAULT_RETENTION_DAYS);
        }
    }

    // ============================================================ F103-10

    @Test
    @Order(10)
    @Tag("story-feat-103-10")
    @Story("FEAT-103.F103-10: 历史检索 as_of 不返回已被 PURGED 物理删除的记忆")
    @DisplayName("F103-10 历史检索过滤 PURGED")
    void f10310_historySearchFiltersPurged() throws Exception {
        String space = "f103-purge";
        assertFalse(purgedId.isEmpty(),
                "依赖门禁：F103-06 未产出已物理删除记忆 id（前置场景失败）");
        List<String> hits = searchUnitIds(space, "zeta", Instant.now().toString());
        // F103-06 已把该空间到期记忆物理删除；历史检索（指定时间点）也不得返回 PURGED。
        assertFalse(hits.contains(purgedId),
                "A10/FR-09：历史检索结果不得包含已 PURGED 物理删除的记忆 " + purgedId
                        + " — hits=" + hits);
    }

    /** F103-06 产出的已物理删除记忆 id（跨用例传递，F103-10 依赖）。 */
    private String purgedId = "";

    // ============================================================ F103-11

    @Test
    @Order(11)
    @Tag("story-feat-103-11")
    @Story("FEAT-103.F103-11: space_usage 返回五态分类计数且未配 quotas 时配额字段为空")
    @DisplayName("F103-11 space_usage 分类统计")
    void f10311_spaceUsageStateBreakdown() throws Exception {
        String space = "f103-usage";
        ensureSpace(space);
        addMemory(space, "FEAT103 用量统计验证记忆 one");
        addMemory(space, "FEAT103 用量统计验证记忆 two");
        String forgottenId = addMemory(space, "FEAT103 用量统计验证记忆 three");
        assertEquals(200, forget(space, List.of(forgottenId)).statusCode(), "前置：forget 应 200");

        ObjectNode req = JSON.createObjectNode();
        req.put("org", ORG);
        req.put("space", space);
        HttpResponse<String> usage = post("space_usage", req);
        assertEquals(200, usage.statusCode(), "space_usage 应 200 — body=" + usage.body());
        JsonNode body = JSON.readTree(usage.body());
        assertEquals(2, body.path("active_count").asInt(-1),
                "A8/FR-16：active_count 应为 2 — body=" + usage.body());
        assertEquals(1, body.path("forgotten_count").asInt(-1),
                "A8/FR-16：forgotten_count 应为 1 — body=" + usage.body());
        assertEquals(0, body.path("purged_count").asInt(-1),
                "A8/FR-16：purged_count 应为 0 — body=" + usage.body());
        assertEquals(0, body.path("superseded_count").asInt(-1),
                "A8/FR-16：superseded_count 应为 0 — body=" + usage.body());
        assertEquals(0, body.path("archived_count").asInt(-1),
                "A8/FR-16：archived_count 应为 0 — body=" + usage.body());
        assertEquals(3, body.path("memory_count").asInt(-1),
                "A8/FR-16：memory_count 应为五态之和 3 — body=" + usage.body());
        assertTrue(body.path("max_units").isMissingNode() || body.path("max_units").isNull(),
                "未配 quotas 时 max_units 应为空 — body=" + usage.body());
        assertTrue(body.path("usage_rate").isMissingNode() || body.path("usage_rate").isNull(),
                "未配 quotas 时 usage_rate 应为空 — body=" + usage.body());
    }

    // ============================================================ F103-12

    @Test
    @Order(12)
    @Tag("story-feat-103-12")
    @Story("FEAT-103.F103-12: usage_rate=active_count/max_units，遗忘积压不误报")
    @DisplayName("F103-12 usage_rate 口径")
    void f10312_usageRateDenominatorIsActiveCount() throws Exception {
        String space = "f103-usage-rate";
        ensureSpace(space);
        setSpaceQuotas(space, "2", null, null, null);
        addMemory(space, "FEAT103 使用率口径验证记忆 one");
        String f1 = addMemory(space, "FEAT103 使用率口径验证记忆 two");
        String f2 = addMemory(space, "FEAT103 使用率口径验证记忆 three");
        assertEquals(200, forget(space, List.of(f1, f2)).statusCode(), "前置：forget 应 200");

        ObjectNode req = JSON.createObjectNode();
        req.put("org", ORG);
        req.put("space", space);
        HttpResponse<String> usage = post("space_usage", req);
        assertEquals(200, usage.statusCode(), "space_usage 应 200 — body=" + usage.body());
        JsonNode body = JSON.readTree(usage.body());
        assertEquals(2, body.path("max_units").asInt(-1),
                "max_units 应取空间策略 quotas.max_units=2 — body=" + usage.body());
        assertEquals(0.5, body.path("usage_rate").asDouble(-1), 1e-9,
                "DEC-05：usage_rate 应为 active_count(1)/max_units(2)=0.5，"
                        + "遗忘积压不得计入分母导致 1.5 — body=" + usage.body());
    }

    // ============================================================ F103-13

    @Test
    @Order(13)
    @Tag("story-feat-103-13")
    @Story("FEAT-103.F103-13: 超配额清扫按 LRU 淘汰到 FORGOTTEN 且误淘汰可恢复")
    @DisplayName("F103-13 LRU 淘汰产出遗忘并恢复")
    void f10313_lruEvictionProducesForgottenAndRestorable() throws Exception {
        String space = "f103-lru";
        ensureSpace(space);
        setSpaceQuotas(space, "2", "true", "10", null);
        String m1 = addMemory(space, "FEAT103 LRU 淘汰验证记忆 one");
        String m2 = addMemory(space, "FEAT103 LRU 淘汰验证记忆 two");
        String m3 = addMemory(space, "FEAT103 LRU 淘汰验证记忆 three");
        // 刷新 M1/M2 的最近访问时间（M3 从未被检索命中 → 最久未访问）
        searchUnitIds(space, "one", null);
        searchUnitIds(space, "two", null);

        HttpResponse<String> sweep = triggerSweep();
        assertEquals(200, sweep.statusCode(),
                "A7/FR-13：清扫末尾配额检查应执行（sweep 触达入口应 200）— body=" + sweep.body());

        assertEquals("active", lifecycleOf(space, m1), "M1 为热记忆应保持 active");
        assertEquals("active", lifecycleOf(space, m2), "M2 为热记忆应保持 active");
        assertEquals("forgotten", lifecycleOf(space, m3),
                "A7/FR-13/FR-15：最久未访问的 M3 应被 LRU 淘汰产出遗忘流转（非直接清除）");

        // 误淘汰可在保留期内恢复
        ObjectNode req = JSON.createObjectNode();
        req.putObject("selector").set("scope", scope(space));
        HttpResponse<String> restore = post("restore", req);
        assertEquals(200, restore.statusCode(), "按 scope 恢复应 200 — body=" + restore.body());
        assertTrue(JSON.readTree(restore.body()).path("restored_ids").toString().contains(m3),
                "AC-08：被淘汰的 M3 应可 restore 捞回 — body=" + restore.body());
        assertEquals("active", lifecycleOf(space, m3), "恢复后 M3 应回到 active");
    }

    private String lifecycleOf(String space, String unitId) throws Exception {
        HttpResponse<String> get = getUnit(space, unitId);
        assertEquals(200, get.statusCode(), "点读 " + unitId + " 应 200 — body=" + get.body());
        return JSON.readTree(get.body()).path("lifecycle").asText("");
    }

    // ============================================================ F103-14

    @Test
    @Order(14)
    @Tag("story-feat-103-14")
    @Story("FEAT-103.F103-14: quotas 未显式配置 eviction_enabled/max_units 时清扫不淘汰")
    @DisplayName("F103-14 未显式配额不淘汰")
    void f10314_noQuotaNoEviction() throws Exception {
        String space = "f103-noquota";
        ensureSpace(space);
        String a = addMemory(space, "FEAT103 无配额验证记忆 one");
        String b = addMemory(space, "FEAT103 无配额验证记忆 two");
        String c = addMemory(space, "FEAT103 无配额验证记忆 three");

        HttpResponse<String> sweep = triggerSweep();
        assertEquals(200, sweep.statusCode(), "清扫触发应 200 — body=" + sweep.body());

        assertEquals("active", lifecycleOf(space, a), "未配 quotas 时 a 不被淘汰");
        assertEquals("active", lifecycleOf(space, b), "未配 quotas 时 b 不被淘汰");
        assertEquals("active", lifecycleOf(space, c),
                "FR-13/§6.4：quotas 未显式配置（eviction_enabled/max_units 缺失）时不得淘汰");
    }

    // ============================================================ F103-15

    @Test
    @Order(15)
    @Tag("story-feat-103-15")
    @Story("FEAT-103.F103-15: LRU 淘汰只淘汰 ACTIVE，ARCHIVED 不进入淘汰候选")
    @DisplayName("F103-15 只淘汰 ACTIVE 态")
    void f10315_evictionOnlyTouchesActive() throws Exception {
        String space = "f103-onlyactive";
        ensureSpace(space);
        setSpaceQuotas(space, "1", "true", "10", null);
        String a1 = addMemory(space, "FEAT103 只淘汰活跃态验证记忆 one");
        String a2 = addMemory(space, "FEAT103 只淘汰活跃态验证记忆 two");
        String b = addMemory(space, "FEAT103 只淘汰活跃态验证记忆 archived");
        // B 归档（转冷），不参与默认召回
        ObjectNode archiveReq = JSON.createObjectNode();
        ObjectNode selector = archiveReq.putObject("selector");
        selector.set("unit_ids", JSON.valueToTree(List.of(b)));
        selector.put("mode", "archive");
        assertEquals(200, post("delete", archiveReq).statusCode(), "前置：归档 B 应 200");

        HttpResponse<String> sweep = triggerSweep();
        assertEquals(200, sweep.statusCode(), "清扫触发应 200 — body=" + sweep.body());

        assertEquals("archived", lifecycleOf(space, b),
                "FR-14：ARCHIVED 记忆不得被 LRU 淘汰");
        String lifecycleA1 = lifecycleOf(space, a1);
        String lifecycleA2 = lifecycleOf(space, a2);
        assertTrue(
                ("active".equals(lifecycleA1) && "forgotten".equals(lifecycleA2))
                        || ("forgotten".equals(lifecycleA1) && "active".equals(lifecycleA2)),
                "超限 1 条应恰好从两条 ACTIVE 中淘汰一条（一条 active 一条 forgotten）— a1="
                        + lifecycleA1 + " a2=" + lifecycleA2);
    }

    // ============================================================ F103-16

    @Test
    @Order(16)
    @Tag("story-feat-103-16")
    @Story("FEAT-103.F103-16: 重复 restore 已恢复记忆归入 skipped_ids（幂等）")
    @DisplayName("F103-16 重复 restore 幂等")
    void f10316_repeatRestoreIsIdempotent() throws Exception {
        String space = "f103-restore";
        String id = addMemory(space, "FEAT103 恢复幂等验证记忆 iota");
        assertEquals(200, forget(space, List.of(id)).statusCode(), "前置：forget 应 200");

        ObjectNode req = JSON.createObjectNode();
        req.putObject("selector").set("unit_ids", JSON.valueToTree(List.of(id)));
        HttpResponse<String> first = post("restore", req);
        assertEquals(200, first.statusCode(), "首次 restore 应 200 — body=" + first.body());
        assertTrue(JSON.readTree(first.body()).path("restored_ids").toString().contains(id),
                "前置：首次 restore 应成功恢复 — body=" + first.body());

        HttpResponse<String> second = post("restore", req);
        assertEquals(200, second.statusCode(), "重复 restore 应 200 — body=" + second.body());
        JsonNode result = JSON.readTree(second.body());
        assertTrue(result.path("skipped_ids").toString().contains(id),
                "FRS §8.2：重复 restore 应把已 ACTIVE 的记忆归入 skipped_ids — body=" + second.body());
        assertFalse(result.path("restored_ids").toString().contains(id),
                "重复 restore 不得再次计入 restored_ids — body=" + second.body());
    }

    // ============================================================ F103-17

    @Test
    @Order(17)
    @Tag("story-feat-103-17")
    @Story("FEAT-103.F103-17: restore 选择器按 tags 定位批量恢复遗忘态记忆（§4.1）")
    @DisplayName("F103-17 restore 按 tags 定位")
    void f10317_restoreByTagsSelector() throws Exception {
        String space = "f103-gap-tags";
        String tag = "f103-gap-tag-17";
        ensureSpace(space);
        String m1 = addMemoryFull(space, "FEAT103 gap tags 记忆一", List.of(tag), null, null, null);
        String m2 = addMemoryFull(space, "FEAT103 gap tags 记忆二", List.of(tag), null, null, null);
        assertEquals(200, forget(space, List.of(m1, m2)).statusCode(),
                "前置：两条记忆 forget 应 200");

        JsonNode result = restore(JSON.createObjectNode()
                .set("tags", JSON.valueToTree(List.of(tag))));

        assertTrue(result.path("restored_ids").toString().contains(m1),
                "§4.1：tags 选择器应恢复 M1 — body=" + result);
        assertTrue(result.path("restored_ids").toString().contains(m2),
                "§4.1：tags 选择器应恢复 M2 — body=" + result);
        assertTrue(result.path("skipped_ids").isEmpty(),
                "两条均遗忘态，skipped_ids 应为空 — body=" + result);
        assertEquals("active", lifecycleOf(space, m1), "恢复后 M1 应为 active");
        assertEquals("active", lifecycleOf(space, m2), "恢复后 M2 应为 active");
    }

    // ============================================================ F103-18

    @Test
    @Order(18)
    @Tag("story-feat-103-18")
    @Story("FEAT-103.F103-18: restore 选择器按 before/after 时间范围定位（§4.1，t_message 口径）")
    @DisplayName("F103-18 restore 按 before/after 时间范围")
    void f10318_restoreByTimeRange() throws Exception {
        String space = "f103-gap-time";
        String tag = "f103-gap-tag-18";
        ensureSpace(space);
        // occurred_at 映射 t_message（wire 实证）；M1 早于分界、M2 晚于分界
        String m1 = addMemoryFull(space, "FEAT103 gap 时间范围旧记忆",
                List.of(tag), null, null, "2026-01-01T00:00:00Z");
        String m2 = addMemoryFull(space, "FEAT103 gap 时间范围新记忆",
                List.of(tag), null, null, "2026-10-01T00:00:00Z");
        assertEquals(200, forget(space, List.of(m1, m2)).statusCode(),
                "前置：两条记忆 forget 应 200");

        ObjectNode beforeSelector = JSON.createObjectNode();
        beforeSelector.set("tags", JSON.valueToTree(List.of(tag)));
        beforeSelector.put("before", "2026-06-01T00:00:00Z");
        JsonNode beforeResult = restore(beforeSelector);
        assertTrue(beforeResult.path("restored_ids").toString().contains(m1),
                "§4.1：before 应命中 t_message 早于 2026-06-01 的 M1 — body=" + beforeResult);
        assertFalse(beforeResult.path("restored_ids").toString().contains(m2),
                "§4.1：before 不应命中 t_message 晚于分界的 M2 — body=" + beforeResult);
        assertEquals("forgotten", lifecycleOf(space, m2), "M2 未被 before 命中应保持 forgotten");

        // 再次遗忘两条，after 方向
        assertEquals(200, forget(space, List.of(m1, m2)).statusCode(),
                "前置：两条记忆再次 forget 应 200");
        ObjectNode afterSelector = JSON.createObjectNode();
        afterSelector.set("tags", JSON.valueToTree(List.of(tag)));
        afterSelector.put("after", "2026-06-01T00:00:00Z");
        JsonNode afterResult = restore(afterSelector);
        assertTrue(afterResult.path("restored_ids").toString().contains(m2),
                "§4.1：after 应命中 t_message 晚于 2026-06-01 的 M2 — body=" + afterResult);
        assertFalse(afterResult.path("restored_ids").toString().contains(m1),
                "§4.1：after 不应命中 t_message 早于分界的 M1 — body=" + afterResult);
    }

    // ============================================================ F103-19

    @Test
    @Order(19)
    @Tag("story-feat-103-19")
    @Story("FEAT-103.F103-19: restore 选择器按 filters 结构化谓词定位（§4.1）")
    @DisplayName("F103-19 restore 按 filters 谓词")
    void f10319_restoreByFiltersSelector() throws Exception {
        String space = "f103-gap-filters";
        String tag = "f103-gap-tag-19";
        ensureSpace(space);
        String m1 = addMemoryFull(space, "FEAT103 gap filters 销售记忆",
                List.of(tag), "dept", "sales", null);
        String m2 = addMemoryFull(space, "FEAT103 gap filters 人事记忆",
                List.of(tag), "dept", "hr", null);
        assertEquals(200, forget(space, List.of(m1, m2)).statusCode(),
                "前置：两条记忆 forget 应 200");

        ObjectNode filters = JSON.createObjectNode();
        filters.put("field", "user_metadata.dept");
        filters.put("op", "eq");
        filters.put("value", "sales");
        ObjectNode filterSelector = JSON.createObjectNode();
        filterSelector.set("tags", JSON.valueToTree(List.of(tag)));
        filterSelector.set("filters", filters);
        JsonNode result = restore(filterSelector);

        assertTrue(result.path("restored_ids").toString().contains(m1),
                "§4.1：filters(user_metadata.dept eq sales) 应恢复 M1 — body=" + result);
        assertFalse(result.path("restored_ids").toString().contains(m2),
                "§4.1：filters 不应命中 dept=hr 的 M2 — body=" + result);
        assertEquals("forgotten", lifecycleOf(space, m2), "M2 未被 filters 命中应保持 forgotten");
    }

    // ============================================================ F103-20

    @Test
    @Order(20)
    @Tag("story-feat-103-20")
    @Story("FEAT-103.F103-20: restore limit 截断——最多恢复 N 条（§4.1 limit 参数）")
    @DisplayName("F103-20 restore limit 截断")
    void f10320_restoreLimitTruncation() throws Exception {
        String space = "f103-gap-limit";
        String tag = "f103-gap-tag-20";
        ensureSpace(space);
        String m1 = addMemoryFull(space, "FEAT103 gap limit 记忆一", List.of(tag), null, null, null);
        String m2 = addMemoryFull(space, "FEAT103 gap limit 记忆二", List.of(tag), null, null, null);
        assertEquals(200, forget(space, List.of(m1, m2)).statusCode(),
                "前置：两条记忆 forget 应 200");

        ObjectNode limitSelector = JSON.createObjectNode();
        limitSelector.set("tags", JSON.valueToTree(List.of(tag)));
        limitSelector.put("limit", 1);
        JsonNode result = restore(limitSelector);

        assertEquals(1, result.path("restored_ids").size(),
                "§4.1：limit=1 时 restored_ids 应恰为 1 条（最多恢复 N 条）— body=" + result);
        String restored = result.path("restored_ids").get(0).asText();
        String remained = restored.equals(m1) ? m2 : m1;
        assertEquals("forgotten", lifecycleOf(space, remained),
                "未被恢复的一条应保持 forgotten — unit=" + remained);
    }

    // ============================================================ F103-21

    @Test
    @Order(21)
    @Tag("story-feat-103-21")
    @Story("FEAT-103.F103-21: 空间级 retention.forgotten_retention_days 覆盖全局值在清扫判定中生效（§3.1/§5.1）")
    @DisplayName("F103-21 空间级保留期覆盖清扫生效")
    void f10321_spaceRetentionOverrideConsumedBySweep() throws Exception {
        String overrideSpace = "f103-gap-retover";
        String globalSpace = "f103-gap-retglob";
        ensureSpace(overrideSpace);
        ensureSpace(globalSpace);
        setGlobalRetention("0"); // 全局保留期 0 天 → 到期即清除
        String a1 = addMemory(overrideSpace, "FEAT103 gap 空间保留期覆盖记忆");
        String b1 = addMemory(globalSpace, "FEAT103 gap 全局保留期对照记忆");
        assertEquals(200, forget(overrideSpace, List.of(a1)).statusCode(), "前置：A1 forget 应 200");
        assertEquals(200, forget(globalSpace, List.of(b1)).statusCode(), "前置：B1 forget 应 200");

        setSpaceRetention(overrideSpace, "90"); // 空间级 90 天覆盖全局 0 天
        HttpResponse<String> sweep = triggerSweep();
        assertEquals(200, sweep.statusCode(),
                "§3.1/§5.1：清扫触发入口应 200（缺失即 sweep 触达实现缺口）— body=" + sweep.body());

        assertEquals("forgotten", lifecycleOf(overrideSpace, a1),
                "§5.1：空间保留期 90 天未到期，A1 不应被清除，保持 forgotten");
        HttpResponse<String> getB1 = getUnit(globalSpace, b1);
        assertEquals(404, getB1.statusCode(),
                "§3.1：全局保留期 0 天已到期，B1 应走已清除路径物理删除（404 NotFoundError）"
                        + " — body=" + getB1.body());
    }

    // ============================================================ F103-22

    @Test
    @Order(22)
    @Tag("story-feat-103-22")
    @Story("FEAT-103.F103-22: eviction_batch_size 约束单次清扫淘汰上限（§3.5 淘汰批量）")
    @DisplayName("F103-22 eviction_batch_size 批量约束")
    void f10322_evictionBatchSizeCapsSingleSweep() throws Exception {
        String space = "f103-gap-batch";
        ensureSpace(space);
        setSpaceQuotas(space, "1", "true", "2", null); // max_units=1, batch=2
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ids.add(addMemory(space, "FEAT103 gap 批量约束记忆 " + i));
        }

        HttpResponse<String> sweep = triggerSweep();
        assertEquals(200, sweep.statusCode(),
                "§3.5：清扫触发入口应 200 — body=" + sweep.body());

        long forgotten = countLifecycle(space, ids, "forgotten");
        long active = countLifecycle(space, ids, "active");
        assertEquals(2, forgotten,
                "§3.5：to_evict=3 但 batch_size=2，单次清扫应恰淘汰 2 条 — 实得 forgotten="
                        + forgotten + " ids=" + ids);
        assertEquals(2, active,
                "§3.5：受批量约束剩余 2 条应保持 active（下轮清扫继续）— 实得 active=" + active);
    }

    // ============================================================ F103-23

    @Test
    @Order(23)
    @Tag("story-feat-103-23")
    @Story("FEAT-103.F103-23: eviction_headroom 预留降低有效配额目标（§4.4 quotas 子键）")
    @DisplayName("F103-23 eviction_headroom 预留")
    void f10323_evictionHeadroomShrinksTarget() throws Exception {
        String space = "f103-gap-headroom";
        ensureSpace(space);
        setSpaceQuotas(space, "10", "true", null, "0.5"); // 有效目标 = int(10*(1-0.5)) = 5
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            ids.add(addMemory(space, "FEAT103 gap headroom 记忆 " + i));
        }

        HttpResponse<String> sweep = triggerSweep();
        assertEquals(200, sweep.statusCode(),
                "§4.4：清扫触发入口应 200 — body=" + sweep.body());

        long forgotten = countLifecycle(space, ids, "forgotten");
        long active = countLifecycle(space, ids, "active");
        assertEquals(1, forgotten,
                "§4.4：headroom=0.5 时有效目标 5，6 条活跃应淘汰 1 条 — 实得 forgotten=" + forgotten);
        assertEquals(5, active,
                "§4.4：有效目标 5 条应保持 active — 实得 active=" + active);
    }

    // ============================================================ F103-24

    @Test
    @Order(24)
    @Tag("story-feat-103-24")
    @Story("FEAT-103.F103-24: eviction_enabled 显式 false 时该空间不做配额淘汰（§3.5 淘汰开关）")
    @DisplayName("F103-24 eviction_enabled 显式 false 不淘汰")
    void f10324_evictionExplicitlyDisabledNoEviction() throws Exception {
        String space = "f103-gap-evoff";
        ensureSpace(space);
        setSpaceQuotas(space, "1", "false", null, null); // 显式关闭
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ids.add(addMemory(space, "FEAT103 gap 显式关闭淘汰记忆 " + i));
        }

        HttpResponse<String> sweep = triggerSweep();
        assertEquals(200, sweep.statusCode(),
                "§3.5：清扫触发入口应 200 — body=" + sweep.body());

        for (String id : ids) {
            assertEquals("active", lifecycleOf(space, id),
                    "§3.5：eviction_enabled=false 时超配额也不淘汰，" + id + " 应保持 active");
        }
    }

    // ============================================================ F103-25

    @Test
    @Order(25)
    @Tag("story-feat-103-25")
    @Story("FEAT-103.F103-25: LRU 淘汰对无最近访问时间的记忆按摄入时间兜底（§3.5 LRU 扫描）")
    @DisplayName("F103-25 LRU 空值按摄入时间兜底")
    void f10325_lruNullAccessTimeFallsBackToIngestTime() throws Exception {
        String space = "f103-gap-lrunull";
        ensureSpace(space);
        setSpaceQuotas(space, "2", "true", "10", null);
        // 依序写入 3 条且 sweep 前不做任何 get/search（保持 last_accessed_at 为空）
        String m1 = addMemory(space, "FEAT103 gap LRU兜底最早摄入记忆 A");
        String m2 = addMemory(space, "FEAT103 gap LRU兜底次早摄入记忆 B");
        String m3 = addMemory(space, "FEAT103 gap LRU兜底最晚摄入记忆 C");

        HttpResponse<String> sweep = triggerSweep();
        assertEquals(200, sweep.statusCode(),
                "§3.5：清扫触发入口应 200 — body=" + sweep.body());

        assertEquals("forgotten", lifecycleOf(space, m1),
                "§3.5：三条均无访问时间时，最早摄入的 M1 应被淘汰（摄入时间兜底）");
        assertEquals("active", lifecycleOf(space, m2), "M2 摄入晚于 M1 应保留 active");
        assertEquals("active", lifecycleOf(space, m3), "M3 摄入最晚应保留 active");
    }

    // ============================================================ F103-26

    @Test
    @Order(26)
    @Tag("story-feat-103-26")
    @Story("FEAT-103.F103-26: ARCHIVED 态记忆 restore 归入 skipped_ids 不执行恢复（§5.1 非遗忘态跳过）")
    @DisplayName("F103-26 ARCHIVED 态 restore 跳过")
    void f10326_restoreSkipsArchivedState() throws Exception {
        String space = "f103-gap-arch";
        ensureSpace(space);
        String m1 = addMemory(space, "FEAT103 gap 归档态跳过恢复记忆");

        ObjectNode archiveReq = JSON.createObjectNode();
        ObjectNode archiveSelector = archiveReq.putObject("selector");
        archiveSelector.set("unit_ids", JSON.valueToTree(List.of(m1)));
        archiveSelector.put("mode", "archive");
        assertEquals(200, post("delete", archiveReq).statusCode(),
                "前置：delete(mode=archive) 应 200");
        assertEquals("archived", lifecycleOf(space, m1), "前置：M1 应为 archived");

        JsonNode result = restore(JSON.createObjectNode()
                .set("unit_ids", JSON.valueToTree(List.of(m1))));
        assertTrue(result.path("skipped_ids").toString().contains(m1),
                "§5.1：ARCHIVED 态应归入 skipped_ids — body=" + result);
        assertFalse(result.path("restored_ids").toString().contains(m1),
                "§5.1：ARCHIVED 态不得被恢复 — body=" + result);
        assertEquals("archived", lifecycleOf(space, m1), "跳过恢复后 M1 应保持 archived");
    }

    // ============================================================ F103-27

    @Test
    @Order(27)
    @Tag("story-feat-103-27")
    @Story("FEAT-103.F103-27: 遗忘与恢复操作落审计留痕（§5.1 合规审计）")
    @DisplayName("F103-27 合规审计留痕")
    void f10327_forgetAndRestoreAuditTrail() throws Exception {
        String space = "f103-gap-audit";
        ensureSpace(space);
        String m1 = addMemory(space, "FEAT103 gap 审计留痕记忆");
        assertEquals(200, forget(space, List.of(m1)).statusCode(), "前置：forget 应 200");

        JsonNode deleteEvents = auditEvents("delete");
        assertFalse(deleteEvents.isEmpty(), "遗忘操作应产生 action=delete 审计事件");
        assertTrue(auditTrailContains(deleteEvents, "delete", "before_unit_ids", m1),
                "§5.1：审计应含 action=delete 且 detail.before_unit_ids 记录被遗忘单元 " + m1
                        + " — events=" + deleteEvents);
        boolean deleteActorRecorded = false;
        for (JsonNode event : deleteEvents) {
            if ("delete".equals(event.path("action").asText())
                    && event.path("detail").path("before_unit_ids").asText("").contains(m1)) {
                deleteActorRecorded = "developer".equals(event.path("actor").path("user").asText());
                break;
            }
        }
        assertTrue(deleteActorRecorded,
                "§5.1：遗忘审计事件应记录操作者 actor.user=developer");

        JsonNode result = restore(JSON.createObjectNode().set("unit_ids", JSON.valueToTree(List.of(m1))));
        assertTrue(result.path("restored_ids").toString().contains(m1),
                "前置：恢复应成功 — body=" + result);

        JsonNode restoreEvents = auditEvents("restore");
        assertTrue(auditTrailContains(restoreEvents, "restore", "restored_ids", m1),
                "§5.1：审计应含 action=restore 且 detail.restored_ids 记录恢复单元 " + m1
                        + " — events=" + restoreEvents);
    }
}
