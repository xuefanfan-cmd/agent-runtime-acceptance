package com.huawei.ascend.sit.cases.integration.agent_memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-104 记忆批量运维操作（批量删除扩展 + 批量更新）黑盒验收。
 *
 * <p>SUT = agent-memory（Python，jiuwen_memory_entry.http_server），由本类经 ProcessBuilder
 * 从 feature_source 源码目录直接拉起（offline profile + dev auth + root 身份）并自主管生命周期。
 * 全部断言落在 HTTP wire 契约上：</p>
 * <ul>
 *   <li>特性文档 §4.2 扩展端点：POST /v1/delete 选择器新增 after/limit/keep_latest
 *       三个可选参数（默认为空时行为不变，mode 语义不变）；</li>
 *   <li>§4.3 新端点：POST /v1/batch_update（更新选择器 + patches 列表 +
 *       continue_on_error + limit），补丁长度 1 为统一补丁、大于 1 为逐条对应，
 *       返回按扫描顺序对齐的 outcomes；</li>
 *   <li>既有端点：/v1/add（occurred_at 注入 t_message）、/v1/get、/v1/create_space；</li>
 *   <li>错误封套：{"error","message","retryable","request_id"}，ValidationError→400、
 *       未知 verb→404 UnknownVerb。</li>
 * </ul>
 *
 * <p>期望值口径：特性文档 FEAT-104（2026-10-23）§3.2/§3.3、§6.2/§6.3/§6.6/§6.7 与
 * FRS-104 v0.1 FR-01~FR-11 / AC-01~AC-12。
 * 红 = 产品行为与文档不符（实现缺口），不通过改用例消化。</p>
 */
@Tags({@Tag("integration"), @Tag("agent-memory"), @Tag("feat-104")})
@Feature("FEAT-104: agent-memory 记忆批量运维操作（批量删除扩展 + 批量更新）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat104MemoryBatchManagementBlackboxTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT_TOKEN = "root-key";
    private static final String ORG = "local";

    /** SUT 源码目录（feature_source）；系统属性 -Dsut.memory104.sourceDir 或 env SUT_MEMORY_SOURCE_DIR 可覆盖。 */
    private static final String SOURCE_DIR = System.getProperty(
            "sut.memory104.sourceDir",
            envOrDefault("SUT_MEMORY_SOURCE_DIR", "D:/code/test/agent-memory"));
    /** SUT 端口；缺省取一个空闲端口，避免与手工拉起的实例或遗留进程冲突。 */
    private static final int PORT = Integer.parseInt(System.getProperty("sut.memory104.port", "0"));

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
                "SUT 源码目录不存在：" + SOURCE_DIR + " — 请配置 -Dsut.memory104.sourceDir");
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
        deleteQuietly(devConfigFile);
        deleteQuietly(engineConfigFile);
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
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

    /** dev 身份配置层：root-key → Role.ROOT。 */
    private Path writeDevIdentityConfig() throws IOException {
        Path file = Path.of("target", "sit-logs", "feat104-dev-identities.json");
        Files.createDirectories(file.getParent());
        String json = "{\"http\": {\"dev_identities\": {\"" + ROOT_TOKEN + "\": "
                + "{\"actor\": {\"org\": \"local\", \"user\": \"developer\"}, \"role\": \"root\"}}}}";
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 引擎配置层：offline profile 默认装配 InMemoryEngine（要求 scope.space 为空，不支持空间隔离）。
     * FEAT-104 场景依赖 org/space 隔离（FRS §11.3），经 memory_api.engine.default.target=agent
     * 切换到支持空间维的 AgentEngine，其余组件沿用内置默认（embedder=hashing、llm=echo，全离线）。
     */
    private Path writeEngineConfig() throws IOException {
        Path file = Path.of("target", "sit-logs", "feat104-engine-config.json");
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
        pb.redirectOutput(Path.of("target", "sit-logs", "feat104-sut-" + port + ".log").toFile());
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
        spec.put("display_name", "FEAT-104 test space " + space);
        post("create_space", req); // 已存在时的失败可容忍
    }

    /** 写入一条 ACTIVE 记忆；occurredAt 非空时注入 t_message，返回分配的 unit id。 */
    private String addMemory(String space, String content, String occurredAt) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("content", content);
        req.set("scope", scope(space));
        if (occurredAt != null) {
            req.put("occurred_at", occurredAt);
        }
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

    private String addMemory(String space, String content) throws Exception {
        return addMemory(space, content, null);
    }

    /** 写入一条携带 user_metadata 的 ACTIVE 记忆（occurredAt/userMetadata 均可空），返回分配的 unit id。 */
    private String addMemoryWithUserMetadata(String space, String content, String occurredAt,
                                             Map<String, String> userMetadata) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("content", content);
        req.set("scope", scope(space));
        if (occurredAt != null) {
            req.put("occurred_at", occurredAt);
        }
        if (userMetadata != null && !userMetadata.isEmpty()) {
            ObjectNode um = req.putObject("user_metadata");
            userMetadata.forEach(um::put);
        }
        HttpResponse<String> response = post("add", req);
        assertEquals(200, response.statusCode(),
                "前置：写入记忆应 200 — body=" + response.body());
        return JSON.readTree(response.body()).get(0).path("id").asText("");
    }

    /** 检索：POST /v1/search（wire 契约：query + context.scope + top_k）。 */
    private HttpResponse<String> search(String space, String query) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("query", query);
        ObjectNode context = req.putObject("context");
        context.set("scope", scope(space));
        req.put("top_k", 10);
        return post("search", req);
    }

    private String daysAgoIso(long days) {
        return Instant.now().minus(Duration.ofDays(days)).toString();
    }

    /** 组装 delete 请求：selector 字段 + mode（mode 为 DeleteSelector 一等参数）。 */
    private HttpResponse<String> batchDelete(String space, ObjectNode selectorExtras,
                                             String mode) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        ObjectNode selector = req.putObject("selector");
        selector.set("scope", scope(space));
        if (mode != null) {
            selector.put("mode", mode);
        }
        if (selectorExtras != null) {
            selector.setAll(selectorExtras);
        }
        return post("delete", req);
    }

    /** 响应为被删除的 unit id 列表（list[str]）。 */
    private List<String> deletedIds(HttpResponse<String> response) throws IOException {
        JsonNode body = JSON.readTree(response.body());
        assertTrue(body.isArray(), "delete 响应应为被删除 id 列表 — body=" + response.body());
        List<String> ids = new ArrayList<>();
        for (JsonNode node : body) {
            ids.add(node.asText());
        }
        return ids;
    }

    private HttpResponse<String> getUnit(String space, String unitId) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("unit_id", unitId);
        req.set("scope", scope(space));
        return post("get", req);
    }

    private String lifecycleOf(String space, String unitId) throws Exception {
        HttpResponse<String> get = getUnit(space, unitId);
        assertEquals(200, get.statusCode(), "点读 " + unitId + " 应 200 — body=" + get.body());
        return JSON.readTree(get.body()).path("lifecycle").asText("");
    }

    /** 组装 batch_update 请求；continueOnError/limit 为 null 时不携带。 */
    private HttpResponse<String> batchUpdate(String space, ObjectNode selectorExtras,
                                             ArrayNode patches, Boolean continueOnError,
                                             Integer limit) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        ObjectNode selector = req.putObject("selector");
        selector.set("scope", scope(space));
        if (selectorExtras != null) {
            selector.setAll(selectorExtras);
        }
        req.set("patches", patches);
        if (continueOnError != null) {
            req.put("continue_on_error", continueOnError);
        }
        if (limit != null) {
            req.put("limit", limit);
        }
        return post("batch_update", req);
    }

    private ArrayNode patch(ObjectNode... patchNodes) {
        ArrayNode patches = JSON.createArrayNode();
        for (ObjectNode node : patchNodes) {
            patches.add(node);
        }
        return patches;
    }

    private ObjectNode patchTags(String tag) {
        ObjectNode node = JSON.createObjectNode();
        node.set("tags", JSON.valueToTree(List.of(tag)));
        return node;
    }

    private ObjectNode patchContent(String content) {
        ObjectNode node = JSON.createObjectNode();
        node.put("content", content);
        return node;
    }

    // ============================================================ F104-01

    @Test
    @Order(1)
    @Tag("story-feat-104-01")
    @Story("FEAT-104.F104-01: 批量删除 after+before 时间范围取交集，早于 after 的候选排除")
    @DisplayName("F104-01 批量删除时间范围交集")
    void f10401_deleteTimeRangeIntersection() throws Exception {
        String space = "f104-range";
        ensureSpace(space);
        Map<String, String> seeded = new LinkedHashMap<>();
        seeded.put("M1", addMemory(space, "FEAT104 时间范围验证记忆 one", daysAgoIso(75)));
        String m2 = addMemory(space, "FEAT104 时间范围验证记忆 two", daysAgoIso(60));
        String m3 = addMemory(space, "FEAT104 时间范围验证记忆 three", daysAgoIso(45));
        String m4 = addMemory(space, "FEAT104 时间范围验证记忆 four", daysAgoIso(10));

        ObjectNode extras = JSON.createObjectNode();
        extras.put("after", daysAgoIso(70));
        extras.put("before", daysAgoIso(30));
        HttpResponse<String> delete = batchDelete(space, extras, "forget");
        assertEquals(200, delete.statusCode(),
                "A1/FR-01：delete 选择器应接受 after/before 时间范围并返回 200"
                        + "（400 unknown field = 选择器扩展未实现）— body=" + delete.body());
        List<String> deleted = deletedIds(delete);
        assertTrue(deleted.contains(m2) && deleted.contains(m3),
                "AC-01：t_message 落在 (after, before) 内的 M2/M3 应被删除 — deleted=" + deleted);
        assertFalse(deleted.contains(seeded.get("M1")),
                "FR-01：t_message 早于 after 的 M1 应被排除 — deleted=" + deleted);
        assertFalse(deleted.contains(m4),
                "FR-01：t_message 晚于 before 的 M4 应被排除 — deleted=" + deleted);
        assertEquals(2, deleted.size(),
                "AC-01：交集外无候选，删除数应恰为 2 — deleted=" + deleted);
        assertEquals("active", lifecycleOf(space, seeded.get("M1")), "M1 应保持 active");
        assertEquals("active", lifecycleOf(space, m4), "M4 应保持 active");
    }

    // ============================================================ F104-02

    @Test
    @Order(2)
    @Tag("story-feat-104-02")
    @Story("FEAT-104.F104-02: 批量删除 limit 在候选集上截断到 N 条")
    @DisplayName("F104-02 批量删除 limit 截断")
    void f10402_deleteLimitTruncates() throws Exception {
        String space = "f104-limit";
        ensureSpace(space);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(addMemory(space, "FEAT104 截断验证记忆 " + i, daysAgoIso(i)));
        }

        ObjectNode extras = JSON.createObjectNode();
        extras.put("limit", 2);
        HttpResponse<String> delete = batchDelete(space, extras, "forget");
        assertEquals(200, delete.statusCode(),
                "A1/FR-02：delete 选择器应接受 limit 并返回 200 — body=" + delete.body());
        List<String> deleted = deletedIds(delete);
        assertEquals(2, deleted.size(),
                "AC-03：limit=2 应最多删除 2 条 — deleted=" + deleted);
        assertTrue(ids.containsAll(deleted), "删除的必须是本空间候选 — deleted=" + deleted);
        long remainingActive = 0;
        for (String id : ids) {
            if (!deleted.contains(id) && "active".equals(lifecycleOf(space, id))) {
                remainingActive++;
            }
        }
        assertEquals(3, remainingActive,
                "AC-03：截断后其余 3 条应保持 active — deleted=" + deleted);
    }

    // ============================================================ F104-03

    @Test
    @Order(3)
    @Tag("story-feat-104-03")
    @Story("FEAT-104.F104-03: keep_latest 按消息时间倒序保留活跃态前 N 条，遗忘态留在删除集")
    @DisplayName("F104-03 批量删除保留最新")
    void f10403_deleteKeepLatestKeepsNewestActive() throws Exception {
        String space = "f104-keep";
        ensureSpace(space);
        String a1 = addMemory(space, "FEAT104 保留最新验证记忆 one", daysAgoIso(40));
        String a2 = addMemory(space, "FEAT104 保留最新验证记忆 two", daysAgoIso(30));
        String a3 = addMemory(space, "FEAT104 保留最新验证记忆 three", daysAgoIso(20));
        String a4 = addMemory(space, "FEAT104 保留最新验证记忆 four", daysAgoIso(10));
        // 预置一条遗忘态：不参与保留集计算，但应留在删除集中（FR-03）
        String f = addMemory(space, "FEAT104 保留最新验证记忆 forgotten", daysAgoIso(50));
        ObjectNode forgetExtras = JSON.createObjectNode();
        forgetExtras.set("unit_ids", JSON.valueToTree(List.of(f)));
        assertEquals(200, batchDelete(space, forgetExtras, "forget").statusCode(),
                "前置：预置遗忘态应 200");

        ObjectNode extras = JSON.createObjectNode();
        extras.put("keep_latest", 2);
        HttpResponse<String> delete = batchDelete(space, extras, "forget");
        assertEquals(200, delete.statusCode(),
                "A2/FR-03：delete 选择器应接受 keep_latest 并返回 200 — body=" + delete.body());
        List<String> deleted = deletedIds(delete);
        assertTrue(deleted.contains(a1) && deleted.contains(a2),
                "AC-02：保留最新 2 条（A3/A4）后，较旧的 A1/A2 应被删除 — deleted=" + deleted);
        assertTrue(deleted.contains(f),
                "FR-03：遗忘态记忆不参与保留集计算但应留在删除集中 — deleted=" + deleted);
        assertFalse(deleted.contains(a3) || deleted.contains(a4),
                "AC-02：保留集 A3/A4 不得被删除 — deleted=" + deleted);
        assertEquals("active", lifecycleOf(space, a3), "A3 应保持 active");
        assertEquals("active", lifecycleOf(space, a4), "A4 应保持 active");
    }

    // ============================================================ F104-04

    @Test
    @Order(4)
    @Tag("story-feat-104-04")
    @Story("FEAT-104.F104-04: keep_latest 优先产出删除集，limit 在该删除集上截断")
    @DisplayName("F104-04 keep_latest 与 limit 组合")
    void f10404_keepLatestThenLimit() throws Exception {
        String space = "f104-keeplimit";
        ensureSpace(space);
        String b1 = addMemory(space, "FEAT104 组合验证记忆 one", daysAgoIso(50));
        String b2 = addMemory(space, "FEAT104 组合验证记忆 two", daysAgoIso(40));
        String b3 = addMemory(space, "FEAT104 组合验证记忆 three", daysAgoIso(30));
        String b4 = addMemory(space, "FEAT104 组合验证记忆 four", daysAgoIso(20));
        String b5 = addMemory(space, "FEAT104 组合验证记忆 five", daysAgoIso(10));

        ObjectNode extras = JSON.createObjectNode();
        extras.put("keep_latest", 2);
        extras.put("limit", 2);
        HttpResponse<String> delete = batchDelete(space, extras, "forget");
        assertEquals(200, delete.statusCode(),
                "FR-05：keep_latest 与 limit 应可组合并返回 200 — body=" + delete.body());
        List<String> deleted = deletedIds(delete);
        assertEquals(2, deleted.size(),
                "AC-02/FR-05：删除集 3 条（B1/B2/B3）被 limit 截断到 2 条 — deleted=" + deleted);
        // 契约钉点（FR-05/AC-02、FEAT-104 §6.2）：keep_latest 优先产出删除集 {B1,B2,B3}，
        // limit 在该删除集上截断到 2 条、删除的均为较旧记忆（⊆ 删除集）；删除集内部
        // 截断方向（最旧优先 vs 扫描序优先）契约未钉（FRS §6.2 步骤 4 仅"截断到 500 条"，
        // 开发侧单测 test_keep_latest_and_limit_combined 亦仅断言条数），故此处按设计矩阵
        // 断言 deleted ⊆ {B1,B2,B3}，不钉具体哪两条。
        assertTrue(deleted.contains(b1) || deleted.contains(b2) || deleted.contains(b3),
                "FR-05：截断应发生在 keep_latest 产出的删除集 {B1,B2,B3} 内 — deleted=" + deleted);
        assertFalse(deleted.contains(b4) || deleted.contains(b5),
                "FR-05：保留集 B4/B5 不得被删除 — deleted=" + deleted);
        // deleted 恰 2 条且 ⊆ {B1,B2,B3} → 删除集中恰有一条未入选截断，应保持 active
        String truncatedOut = null;
        for (String candidate : List.of(b1, b2, b3)) {
            if (!deleted.contains(candidate)) {
                truncatedOut = candidate;
                break;
            }
        }
        assertNotNull(truncatedOut,
                "FR-05：删除集 {B1,B2,B3} 中应恰有一条未入选截断 — deleted=" + deleted);
        assertEquals("active", lifecycleOf(space, truncatedOut),
                "FR-05：未入选截断的删除集成员应保持 active（本次未删，待下次操作）");
        assertEquals("active", lifecycleOf(space, b4), "B4 属保留集应保持 active");
        assertEquals("active", lifecycleOf(space, b5), "B5 属保留集应保持 active");
    }

    // ============================================================ F104-05

    @Test
    @Order(5)
    @Tag("story-feat-104-05")
    @Story("FEAT-104.F104-05: t_message 为空的候选按 t_ingest 兜底参与保留集排序")
    @DisplayName("F104-05 排序兜底 t_ingest")
    void f10405_fallbackToIngestTime() throws Exception {
        String space = "f104-fallback";
        ensureSpace(space);
        String c1 = addMemory(space, "FEAT104 兜底验证记忆 one", daysAgoIso(30));
        String c2 = addMemory(space, "FEAT104 兜底验证记忆 two", daysAgoIso(20));
        String c3 = addMemory(space, "FEAT104 兜底验证记忆 three", daysAgoIso(10));
        // D1/D2 不带 occurred_at：t_message 为 null，t_ingest 为当前（晚于所有 C 的 t_message）
        String d1 = addMemory(space, "FEAT104 兜底验证记忆 null-msg-one");
        String d2 = addMemory(space, "FEAT104 兜底验证记忆 null-msg-two");
        for (String id : List.of(d1, d2)) {
            HttpResponse<String> get = getUnit(space, id);
            assertTrue(JSON.readTree(get.body()).path("temporal").path("t_message").isNull(),
                    "前置：无 occurred_at 的记忆 t_message 应为 null — body=" + get.body());
        }

        ObjectNode extras = JSON.createObjectNode();
        extras.put("keep_latest", 2);
        HttpResponse<String> delete = batchDelete(space, extras, "forget");
        assertEquals(200, delete.statusCode(),
                "FR-04：keep_latest 应 200 — body=" + delete.body());
        List<String> deleted = deletedIds(delete);
        assertTrue(deleted.contains(c1) && deleted.contains(c2) && deleted.contains(c3),
                "AC-11/FR-04：t_message 为空的 D1/D2 按 t_ingest 兜底为最新 2 条进保留集，"
                        + "C1/C2/C3 应被删除 — deleted=" + deleted);
        assertFalse(deleted.contains(d1) || deleted.contains(d2),
                "AC-11：D1/D2 属保留集不得被删除 — deleted=" + deleted);
        assertEquals("active", lifecycleOf(space, d1), "D1 应保持 active");
        assertEquals("active", lifecycleOf(space, d2), "D2 应保持 active");
    }

    // ============================================================ F104-06

    @Test
    @Order(6)
    @Tag("story-feat-104-06")
    @Story("FEAT-104.F104-06: selector 不带新参数时现有删除语义行为不变（兼容路径）")
    @DisplayName("F104-06 无新参数行为不变")
    void f10406_selectorWithoutNewParamsUnchanged() throws Exception {
        String space = "f104-compat";
        ensureSpace(space);
        List<String> compatIds = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ObjectNode req = JSON.createObjectNode();
            req.put("content", "FEAT104 兼容验证记忆 " + i);
            req.set("scope", scope(space));
            req.set("tags", JSON.valueToTree(List.of("compat")));
            HttpResponse<String> response = post("add", req);
            assertEquals(200, response.statusCode(), "前置：add 应 200");
            compatIds.add(JSON.readTree(response.body()).get(0).path("id").asText());
        }
        String other = addMemory(space, "FEAT104 兼容验证记忆 other");

        ObjectNode extras = JSON.createObjectNode();
        extras.set("tags", JSON.valueToTree(List.of("compat")));
        HttpResponse<String> delete = batchDelete(space, extras, "forget");
        assertEquals(200, delete.statusCode(),
                "§6.7：不带新参数的 selector 应 200（现有语义不变）— body=" + delete.body());
        List<String> deleted = deletedIds(delete);
        assertEquals(compatIds.size(), deleted.size(),
                "§6.7：按 tags 命中的 3 条应全部删除 — deleted=" + deleted);
        assertTrue(deleted.containsAll(compatIds), "删除的应是 compat 标签候选 — deleted=" + deleted);
        assertEquals("active", lifecycleOf(space, other),
                "未命中选择器的记忆行为不变，保持 active");
    }

    // ============================================================ F104-07

    @Test
    @Order(7)
    @Tag("story-feat-104-07")
    @Story("FEAT-104.F104-07: batch_update 端点存在，统一补丁按 unit_ids 应用且结果按扫描顺序对齐")
    @DisplayName("F104-07 batch_update 统一补丁（unit_ids）")
    void f10407_batchUpdateUnifiedPatchByIds() throws Exception {
        String space = "f104-batch-u";
        ensureSpace(space);
        String m1 = addMemory(space, "FEAT104 统一补丁验证记忆 one", daysAgoIso(3));
        String m2 = addMemory(space, "FEAT104 统一补丁验证记忆 two", daysAgoIso(2));
        String m3 = addMemory(space, "FEAT104 统一补丁验证记忆 three", daysAgoIso(1));

        ObjectNode extras = JSON.createObjectNode();
        extras.set("unit_ids", JSON.valueToTree(List.of(m1, m2, m3)));
        HttpResponse<String> response = batchUpdate(space, extras, patch(patchTags("summary")),
                null, null);
        assertEquals(200, response.statusCode(),
                "A3/FR-06：POST /v1/batch_update 端点应存在且返回 200"
                        + "（404 UnknownVerb = 端点缺失）— body=" + response.body());
        JsonNode outcomes = JSON.readTree(response.body()).path("outcomes");
        assertEquals(3, outcomes.size(),
                "AC-04：outcomes 应恰 3 项 — body=" + response.body());
        // 契约要求 outcomes"按扫描顺序对齐"（FRS §10），且明确"始终走扫描不按 unit_ids 点读"
        // ——扫描顺序为存储扫描序，FRS 未钉方向；统一补丁模式下顺序无功能语义，
        // 故按 unit_id 映射断言逐项结果，不钉存储扫描方向。
        Map<String, JsonNode> byUnit = new LinkedHashMap<>();
        outcomes.forEach(o -> byUnit.put(o.path("unit_id").asText(), o));
        for (String id : List.of(m1, m2, m3)) {
            JsonNode outcome = byUnit.get(id);
            assertTrue(outcome != null,
                    "FR-06：outcomes 应覆盖 unit_ids 中的 " + id + " — body=" + response.body());
            assertFalse(outcome.path("updated_unit").isNull() || outcome.path("updated_unit").isMissingNode(),
                    "AC-04：" + id + " 的 updated_unit 应非 null — body=" + response.body());
            assertTrue(outcome.path("error").isNull() || outcome.path("error").asText().isEmpty(),
                    "FR-10：统一补丁模式 " + id + " 不应有 error — body=" + response.body());
            assertEquals("summary", outcome.path("updated_unit").path("tags").get(0).asText(""),
                    "AC-04/FR-07：" + id + " 的 tags 应统一改为 [summary] — body=" + response.body());
        }
    }

    // ============================================================ F104-08

    @Test
    @Order(8)
    @Tag("story-feat-104-08")
    @Story("FEAT-104.F104-08: 统一补丁按选择器条件筛选候选（无标识列表）")
    @DisplayName("F104-08 统一补丁按 scope 条件筛选")
    void f10408_batchUpdateUnifiedPatchBySelector() throws Exception {
        String space = "f104-batch-s";
        ensureSpace(space);
        List<String> noteIds = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            ObjectNode req = JSON.createObjectNode();
            req.put("content", "FEAT104 条件筛选验证记忆 " + i);
            req.set("scope", scope(space));
            req.set("tags", JSON.valueToTree(List.of("note")));
            HttpResponse<String> response = post("add", req);
            noteIds.add(JSON.readTree(response.body()).get(0).path("id").asText());
        }
        String untouched = addMemory(space, "FEAT104 条件筛选验证记忆 other");

        ObjectNode extras = JSON.createObjectNode();
        extras.set("tags", JSON.valueToTree(List.of("note")));
        HttpResponse<String> response = batchUpdate(space, extras, patch(patchTags("summary")),
                null, null);
        assertEquals(200, response.statusCode(),
                "FR-07：按选择器条件筛选候选的统一补丁应 200 — body=" + response.body());
        JsonNode outcomes = JSON.readTree(response.body()).path("outcomes");
        assertEquals(2, outcomes.size(),
                "FR-07：仅命中 note 标签的 2 条候选，outcomes 应恰 2 项 — body=" + response.body());
        for (int i = 0; i < outcomes.size(); i++) {
            assertEquals("summary", outcomes.get(i).path("updated_unit").path("tags").get(0).asText(""),
                    "FR-07：候选应统一应用补丁 tags=[summary] — body=" + response.body());
        }
        HttpResponse<String> otherGet = getUnit(space, untouched);
        List<String> otherTags = new ArrayList<>();
        JSON.readTree(otherGet.body()).path("tags").forEach(n -> otherTags.add(n.asText()));
        assertFalse(otherTags.contains("summary"),
                "FR-07：未命中选择器的记忆不得被更新 — tags=" + otherTags);
    }

    // ============================================================ F104-09

    @Test
    @Order(9)
    @Tag("story-feat-104-09")
    @Story("FEAT-104.F104-09: patches>1 逐条对应模式，每条独立应用对应补丁且顺序对齐")
    @DisplayName("F104-09 逐条对应模式")
    void f10409_batchUpdatePerItemPatches() throws Exception {
        String space = "f104-peritem";
        ensureSpace(space);
        String m1 = addMemory(space, "FEAT104 逐条验证记忆 one", daysAgoIso(3));
        String m2 = addMemory(space, "FEAT104 逐条验证记忆 two", daysAgoIso(2));
        String m3 = addMemory(space, "FEAT104 逐条验证记忆 three", daysAgoIso(1));

        ObjectNode extras = JSON.createObjectNode();
        extras.set("unit_ids", JSON.valueToTree(List.of(m1, m2, m3)));
        HttpResponse<String> response = batchUpdate(space, extras,
                patch(patchContent("fixed-one"), patchContent("fixed-two"),
                        patchContent("fixed-three")),
                null, null);
        assertEquals(200, response.statusCode(),
                "A4/FR-08：逐条对应模式应 200 — body=" + response.body());
        JsonNode outcomes = JSON.readTree(response.body()).path("outcomes");
        assertEquals(3, outcomes.size(),
                "AC-05：outcomes 应恰 3 项 — body=" + response.body());
        // AC-05 契约口径：M1→patch1 / M2→patch2 / M3→patch3（按 unit 身份逐条对应）。
        // outcomes"按扫描顺序对齐"，扫描顺序为存储扫描序（FRS 未钉方向），
        // 故按 unit_id→outcome 映射断言逐条对应关系。
        Map<String, JsonNode> byUnit = new LinkedHashMap<>();
        outcomes.forEach(o -> byUnit.put(o.path("unit_id").asText(), o));
        String[] expectedContents = {"fixed-one", "fixed-two", "fixed-three"};
        List<String> ids = List.of(m1, m2, m3);
        for (int i = 0; i < 3; i++) {
            JsonNode outcome = byUnit.get(ids.get(i));
            assertTrue(outcome != null,
                    "FR-08：outcomes 应覆盖 unit_ids 中的 " + ids.get(i) + " — body=" + response.body());
            assertEquals(expectedContents[i],
                    outcome.path("updated_unit").path("segments").get(0).path("content").asText(""),
                    "AC-05：M" + (i + 1) + " 应独立应用第 " + (i + 1) + " 个补丁 — body=" + response.body());
            assertTrue(outcome.path("error").isNull()
                            || outcome.path("error").asText().isEmpty(),
                    "AC-05：第 " + (i + 1) + " 项不应有 error — body=" + response.body());
        }
    }

    // ============================================================ F104-10

    @Test
    @Order(10)
    @Tag("story-feat-104-10")
    @Story("FEAT-104.F104-10: patches>1 但 selector 无 unit_ids 必须拒绝（fail-fast）")
    @DisplayName("F104-10 逐条模式无 unit_ids 拒绝")
    void f10410_perItemWithoutUnitIdsRejected() throws Exception {
        String space = "f104-noids";
        ensureSpace(space);
        addMemory(space, "FEAT104 无 ids 校验记忆 one");
        addMemory(space, "FEAT104 无 ids 校验记忆 two");

        HttpResponse<String> response = batchUpdate(space, null,
                patch(patchTags("a"), patchTags("b")), null, null);
        assertEquals(400, response.statusCode(),
                "AC-07/FR-08：patches>1 且无 unit_ids 必须返回 400 — 实得 "
                        + response.statusCode() + " body=" + response.body());
        JsonNode error = JSON.readTree(response.body());
        assertEquals("ValidationError", error.path("error").asText(),
                "错误封套 error 应为 ValidationError — body=" + response.body());
        String message = error.path("message").asText("");
        assertTrue(message.contains("unit_ids") || message.contains("标识"),
                "错误 message 应明确提示逐条对应模式必须有 unit_ids — body=" + response.body());
    }

    // ============================================================ F104-11

    @Test
    @Order(11)
    @Tag("story-feat-104-11")
    @Story("FEAT-104.F104-11: unit_ids 与 patches 数量不一致必须拒绝（fail-fast）")
    @DisplayName("F104-11 数量不一致拒绝")
    void f10411_countMismatchRejected() throws Exception {
        String space = "f104-count";
        ensureSpace(space);
        String m1 = addMemory(space, "FEAT104 数量校验记忆 one");
        String m2 = addMemory(space, "FEAT104 数量校验记忆 two");

        ObjectNode extras = JSON.createObjectNode();
        extras.set("unit_ids", JSON.valueToTree(List.of(m1, m2)));
        HttpResponse<String> response = batchUpdate(space, extras,
                patch(patchTags("a"), patchTags("b"), patchTags("c")), null, null);
        assertEquals(400, response.statusCode(),
                "AC-08/FR-08：len(unit_ids)!=len(patches) 必须返回 400 — 实得 "
                        + response.statusCode() + " body=" + response.body());
        JsonNode error = JSON.readTree(response.body());
        assertEquals("ValidationError", error.path("error").asText(),
                "错误封套 error 应为 ValidationError — body=" + response.body());
        String message = error.path("message").asText("");
        assertTrue(message.contains("数量") || message.contains("一致") || message.contains("match")
                        || message.contains("length") || message.contains("size")
                        || message.contains("equal"),
                "错误 message 应明确提示数量必须一致 — body=" + response.body());
    }

    // ============================================================ F104-12

    @Test
    @Order(12)
    @Tag("story-feat-104-12")
    @Story("FEAT-104.F104-12: patch 含内核保留键 forgotten_at 必须拒绝且不落值")
    @DisplayName("F104-12 内核保留键拒绝")
    void f10412_kernelReservedKeyRejected() throws Exception {
        String space = "f104-kernel";
        ensureSpace(space);
        Map<String, String> seededUm = new LinkedHashMap<>();
        seededUm.put("orig-key", "keep-me");
        String m1 = addMemoryWithUserMetadata(space, "FEAT104 内核键校验记忆 one", null, seededUm);

        ObjectNode extras = JSON.createObjectNode();
        extras.set("unit_ids", JSON.valueToTree(List.of(m1)));
        ObjectNode kernelPatch = JSON.createObjectNode();
        kernelPatch.putObject("system_metadata").put("forgotten_at", "2026-01-01T00:00:00Z");
        HttpResponse<String> response = batchUpdate(space, extras, patch(kernelPatch), null, null);
        assertEquals(400, response.statusCode(),
                "AC-06/FR-09：patch 含内核保留键 forgotten_at 必须返回 400（不静默合并）— 实得 "
                        + response.statusCode() + " body=" + response.body());
        JsonNode error = JSON.readTree(response.body());
        assertEquals("ValidationError", error.path("error").asText(),
                "错误封套 error 应为 ValidationError — body=" + response.body());
        String message = error.path("message").asText("");
        assertTrue(message.contains("forgotten_at") || message.contains("内核") || message.contains("保留键")
                        || message.toLowerCase().contains("reserved") || message.toLowerCase().contains("kernel"),
                "错误 message 应明确提示内核保留键不可覆盖 — body=" + response.body());

        HttpResponse<String> get = getUnit(space, m1);
        assertEquals(200, get.statusCode());
        assertFalse(JSON.readTree(get.body()).path("system_metadata").has("forgotten_at"),
                "FR-09：被拒补丁不得使 forgotten_at 落到记忆单元 — body=" + get.body());

        // §3.3 内核保留键保护后半句：其他系统/用户元数据默认合并语义——
        // 补丁仅携带新键时，既有 user_metadata 键与 system_metadata 键必须保留。
        ObjectNode mergeExtras = JSON.createObjectNode();
        mergeExtras.set("unit_ids", JSON.valueToTree(List.of(m1)));
        ObjectNode mergePatch = JSON.createObjectNode();
        mergePatch.putObject("user_metadata").put("merged-key", "merged-value");
        HttpResponse<String> merge = batchUpdate(space, mergeExtras, patch(mergePatch), null, null);
        assertEquals(200, merge.statusCode(),
                "§3.3：仅携带新增 user_metadata 键的补丁应 200 — body=" + merge.body());
        JsonNode mergedUnit = JSON.readTree(merge.body()).path("outcomes").get(0).path("updated_unit");
        assertFalse(mergedUnit.isMissingNode() || mergedUnit.isNull(),
                "§3.3：合并补丁应返回 updated_unit — body=" + merge.body());
        assertEquals("keep-me", mergedUnit.path("user_metadata").path("orig-key").asText(""),
                "§3.3：补丁未触及的既有 user_metadata 键应保留（合并而非替换）— body=" + merge.body());
        assertEquals("merged-value", mergedUnit.path("user_metadata").path("merged-key").asText(""),
                "§3.3：补丁新增 user_metadata 键应落值 — body=" + merge.body());
        assertEquals("user:developer", mergedUnit.path("system_metadata").path("author_principal").asText(""),
                "§3.3：补丁未触及的 system_metadata 键应保留 — body=" + merge.body());
    }

    // ============================================================ F104-13

    @Test
    @Order(13)
    @Tag("story-feat-104-13")
    @Story("FEAT-104.F104-13: continue_on_error=true（默认）逐项失败不中止后续，成功项正常返回")
    @DisplayName("F104-13 continue_on_error=true 逐项隔离")
    void f10413_continueOnErrorTrueIsolatesFailures() throws Exception {
        String space = "f104-coe-true";
        ensureSpace(space);
        String m1 = addMemory(space, "FEAT104 隔离验证记忆 one");
        String m2 = addMemory(space, "FEAT104 隔离验证记忆 two");

        // 中间项为不存在的 unit id → 该项失败（wire 可构造的逐项失败）
        ObjectNode extras = JSON.createObjectNode();
        extras.set("unit_ids", JSON.valueToTree(
                List.of(m1, "f104-no-such-unit", m2)));
        HttpResponse<String> response = batchUpdate(space, extras,
                patch(patchContent("fixed-one"), patchContent("fixed-no-such"),
                        patchContent("fixed-two")),
                true, null);
        assertEquals(200, response.statusCode(),
                "AC-09/FR-10：continue_on_error=true 时逐项失败不应使整体请求失败 — 实得 "
                        + response.statusCode() + " body=" + response.body());
        JsonNode outcomes = JSON.readTree(response.body()).path("outcomes");
        assertEquals(3, outcomes.size(),
                "FR-10：失败不中止后续，outcomes 应恰 3 项 — body=" + response.body());
        assertFalse(outcomes.get(1).path("error").isNull()
                        && outcomes.get(1).path("error").asText().isEmpty(),
                "AC-09：第 2 项（不存在的 id）error 应非空 — body=" + response.body());
        assertFalse(outcomes.get(0).path("updated_unit").isNull()
                        || outcomes.get(0).path("updated_unit").isMissingNode(),
                "AC-09：失败前的第 1 项应成功（updated_unit 非 null）— body=" + response.body());
        assertFalse(outcomes.get(2).path("updated_unit").isNull()
                        || outcomes.get(2).path("updated_unit").isMissingNode(),
                "AC-09：失败后的第 3 项应继续执行成功（不中止后续）— body=" + response.body());
    }

    // ============================================================ F104-14

    @Test
    @Order(14)
    @Tag("story-feat-104-14")
    @Story("FEAT-104.F104-14: continue_on_error=false 遇失败 break，后续项标记 Skipped")
    @DisplayName("F104-14 continue_on_error=false 中止后续")
    void f10414_continueOnErrorFalseBreaksOnFailure() throws Exception {
        String space = "f104-coe-false";
        ensureSpace(space);
        String m1 = addMemory(space, "FEAT104 中止验证记忆 one");
        String m2 = addMemory(space, "FEAT104 中止验证记忆 two");

        ObjectNode extras = JSON.createObjectNode();
        extras.set("unit_ids", JSON.valueToTree(
                List.of(m1, "f104-no-such-unit", m2)));
        HttpResponse<String> response = batchUpdate(space, extras,
                patch(patchContent("fixed-one"), patchContent("fixed-no-such"),
                        patchContent("fixed-two")),
                false, null);
        assertTrue(response.statusCode() == 200 || response.statusCode() == 207,
                "A5/FR-10：continue_on_error=false 应返回结果封套而非传输层错误 — 实得 "
                        + response.statusCode() + " body=" + response.body());
        JsonNode body = JSON.readTree(response.body());
        JsonNode outcomes = body.path("outcomes");
        assertEquals(3, outcomes.size(),
                "FR-10：后续项标记 Skipped 仍占位，outcomes 应恰 3 项 — body=" + response.body());
        assertFalse(outcomes.get(1).path("error").isNull()
                        && outcomes.get(1).path("error").asText().isEmpty(),
                "AC-10：第 2 项（失败项）error 应非空 — body=" + response.body());
        JsonNode third = outcomes.get(2);
        boolean thirdSkipped =
                (third.path("updated_unit").isNull() || third.path("updated_unit").isMissingNode())
                        && (third.path("error").asText("").toLowerCase().contains("skip")
                            || third.path("error_type").asText("").toLowerCase().contains("skip")
                            || third.path("status").asText("").toLowerCase().contains("skip"));
        assertTrue(thirdSkipped,
                "AC-10：遇失败后第 3 项应标记 Skipped（updated_unit 为空且带跳过标记）— "
                        + "third=" + third);
        HttpResponse<String> get = getUnit(space, m2);
        assertEquals("FEAT104 中止验证记忆 two",
                JSON.readTree(get.body()).path("segments").get(0).path("content").asText(""),
                "AC-10：M2 未被执行更新，内容应保持不变 — body=" + get.body());
    }

    // ============================================================ F104-15

    @Test
    @Order(15)
    @Tag("story-feat-104-15")
    @Story("FEAT-104.F104-15: batch_update 的 limit 在候选集上截断，最多更新 N 条")
    @DisplayName("F104-15 batch_update limit 截断")
    void f10415_batchUpdateLimitTruncates() throws Exception {
        String space = "f104-uplimit";
        ensureSpace(space);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ObjectNode req = JSON.createObjectNode();
            req.put("content", "FEAT104 更新截断验证记忆 " + i);
            req.set("scope", scope(space));
            req.set("tags", JSON.valueToTree(List.of("ul")));
            HttpResponse<String> response = post("add", req);
            assertEquals(200, response.statusCode(), "前置：add 应 200");
            ids.add(JSON.readTree(response.body()).get(0).path("id").asText());
        }

        ObjectNode extras = JSON.createObjectNode();
        extras.set("tags", JSON.valueToTree(List.of("ul")));
        HttpResponse<String> response = batchUpdate(space, extras, patch(patchTags("ul-done")),
                null, 2);
        assertEquals(200, response.statusCode(),
                "FR-11：batch_update limit 应 200 — body=" + response.body());
        JsonNode outcomes = JSON.readTree(response.body()).path("outcomes");
        assertEquals(2, outcomes.size(),
                "AC-12/FR-11：limit=2 应最多更新 2 条 — body=" + response.body());
        for (int i = 0; i < outcomes.size(); i++) {
            assertEquals("ul-done",
                    outcomes.get(i).path("updated_unit").path("tags").get(0).asText(""),
                    "FR-11：被更新的候选应应用补丁 — body=" + response.body());
        }
        // 被更新集合以 outcomes[].unit_id 为准：更新走 supersede（新 id 取代旧 id），
        // 按旧 id get 返回的是 superseded 旧版本快照，无法以旧 id 区分已更新/未更新。
        List<String> updatedIds = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            updatedIds.add(outcomes.get(i).path("unit_id").asText());
        }
        assertTrue(ids.containsAll(updatedIds),
                "FR-11：被更新的必须是本空间候选 — updated=" + updatedIds);
        List<String> untouched = new ArrayList<>(ids);
        untouched.removeAll(updatedIds);
        assertEquals(3, untouched.size(),
                "AC-12：limit 截断后其余 3 条候选不得被更新 — updated=" + updatedIds);
        for (String id : untouched) {
            HttpResponse<String> get = getUnit(space, id);
            assertEquals("active", JSON.readTree(get.body()).path("lifecycle").asText(""),
                    "AC-12：未选中的候选应保持 active — id=" + id + " body=" + get.body());
            List<String> tags = new ArrayList<>();
            JSON.readTree(get.body()).path("tags").forEach(n -> tags.add(n.asText()));
            assertTrue(tags.contains("ul") && !tags.contains("ul-done"),
                    "AC-12：未选中的候选不得被更新 — id=" + id + " tags=" + tags);
        }
    }

    // ============================================================ F104-16

    @Test
    @Order(16)
    @Tag("story-feat-104-16")
    @Story("FEAT-104.F104-16: 归档态候选不参与 keep_latest 保留集计算，但仍留在删除集中")
    @DisplayName("F104-16 归档态不参与保留集")
    void f10416_archivedNotInKeepLatestSet() throws Exception {
        String space = "f104-archk";
        ensureSpace(space);
        String a1 = addMemory(space, "FEAT104 归档保留验证记忆 one", daysAgoIso(40));
        String a2 = addMemory(space, "FEAT104 归档保留验证记忆 two", daysAgoIso(30));
        String a3 = addMemory(space, "FEAT104 归档保留验证记忆 three", daysAgoIso(20));
        String a4 = addMemory(space, "FEAT104 归档保留验证记忆 four", daysAgoIso(10));
        // 预置一条归档态（mode=archive）：不参与保留集计算，但应留在删除集中（§3.2 行 3）
        String g = addMemory(space, "FEAT104 归档保留验证记忆 archived", daysAgoIso(50));
        ObjectNode archiveExtras = JSON.createObjectNode();
        archiveExtras.set("unit_ids", JSON.valueToTree(List.of(g)));
        assertEquals(200, batchDelete(space, archiveExtras, "archive").statusCode(),
                "前置：预置归档态应 200");
        assertEquals("archived", lifecycleOf(space, g), "前置：G 应为归档态");

        ObjectNode extras = JSON.createObjectNode();
        extras.put("keep_latest", 2);
        HttpResponse<String> delete = batchDelete(space, extras, "forget");
        assertEquals(200, delete.statusCode(),
                "§3.2：delete 选择器应接受 keep_latest 并 200 — body=" + delete.body());
        List<String> deleted = deletedIds(delete);
        assertTrue(deleted.contains(g),
                "§3.2：归档态不参与保留集计算但应留在删除集中 — deleted=" + deleted);
        assertTrue(deleted.contains(a1) && deleted.contains(a2),
                "§3.2：保留集应为最新 2 条活跃态（A3/A4），较旧活跃态 A1/A2 应被删除 — deleted=" + deleted);
        assertFalse(deleted.contains(a3) || deleted.contains(a4),
                "§3.2：保留集 A3/A4 不得被删除 — deleted=" + deleted);
        assertEquals("active", lifecycleOf(space, a3), "A3 应保持 active");
        assertEquals("active", lifecycleOf(space, a4), "A4 应保持 active");
        assertEquals("forgotten", lifecycleOf(space, g),
                "§3.2：归档态留在删除集中被 mode=forget 执行后应转为 forgotten — get G");
    }

    // ============================================================ F104-17

    @Test
    @Order(17)
    @Tag("story-feat-104-17")
    @Story("FEAT-104.F104-17: 批量删除扩展不改变现有删除模式语义（ARCHIVE/DOWNWEIGHT/PURGE）")
    @DisplayName("F104-17 四态 mode 语义不变")
    void f10417_deleteModeSemanticsUnchanged() throws Exception {
        String space = "f104-modes";
        ensureSpace(space);
        String u1 = addMemory(space, "FEAT104 模式回归验证记忆 archive");
        String u2 = addMemory(space, "FEAT104 模式回归验证记忆 downweight");
        String u3 = addMemory(space, "FEAT104 模式回归验证记忆 purge");

        // ARCHIVE：转冷，lifecycle=archived，记忆保留可点读
        ObjectNode archExtras = JSON.createObjectNode();
        archExtras.set("unit_ids", JSON.valueToTree(List.of(u1)));
        HttpResponse<String> archive = batchDelete(space, archExtras, "archive");
        assertEquals(200, archive.statusCode(),
                "§3.2/§4.2：mode=archive 应 200 — body=" + archive.body());
        assertEquals(List.of(u1), deletedIds(archive), "archive 应恰好命中 u1");
        assertEquals("archived", lifecycleOf(space, u1),
                "§3.2：mode=archive 后 u1 应为 archived（模式语义不变）");

        // DOWNWEIGHT：保持 active，仅降低重要度（importance 减半，默认 1.0→0.5）
        ObjectNode dwExtras = JSON.createObjectNode();
        dwExtras.set("unit_ids", JSON.valueToTree(List.of(u2)));
        HttpResponse<String> downweight = batchDelete(space, dwExtras, "downweight");
        assertEquals(200, downweight.statusCode(),
                "§3.2/§4.2：mode=downweight 应 200 — body=" + downweight.body());
        assertEquals(List.of(u2), deletedIds(downweight), "downweight 应恰好命中 u2");
        HttpResponse<String> dwGet = getUnit(space, u2);
        JsonNode dwUnit = JSON.readTree(dwGet.body());
        assertEquals("active", dwUnit.path("lifecycle").asText(""),
                "§3.2：mode=downweight 后 u2 应保持 active — body=" + dwGet.body());
        assertEquals("0.5", dwUnit.path("system_metadata").path("importance").asText(""),
                "§3.2：mode=downweight 应降低重要度（默认 1.0→0.5）— body=" + dwGet.body());

        // PURGE：物理删除，点读 404，不可恢复
        ObjectNode purgeExtras = JSON.createObjectNode();
        purgeExtras.set("unit_ids", JSON.valueToTree(List.of(u3)));
        HttpResponse<String> purge = batchDelete(space, purgeExtras, "purge");
        assertEquals(200, purge.statusCode(),
                "§3.2/§4.2：mode=purge 应 200 — body=" + purge.body());
        assertEquals(List.of(u3), deletedIds(purge), "purge 应恰好命中 u3");
        HttpResponse<String> purgedGet = getUnit(space, u3);
        assertEquals(404, purgedGet.statusCode(),
                "§3.2：mode=purge 物理删除后点读应 404 — body=" + purgedGet.body());
        assertEquals("NotFoundError", JSON.readTree(purgedGet.body()).path("error").asText(""),
                "§3.2：purge 后点读错误封套应为 NotFoundError — body=" + purgedGet.body());
    }

    // ============================================================ F104-18

    @Test
    @Order(18)
    @Tag("story-feat-104-18")
    @Story("FEAT-104.F104-18: batch_update 更新选择器支持 before/after/filters 条件筛选候选")
    @DisplayName("F104-18 更新选择器时间与 filters 条件")
    void f10418_batchUpdateTemporalAndFilterSelectors() throws Exception {
        // 三个子场景各用独立空间、各只发一次 batch_update：
        // 同一空间内先改后选会使 superseded 历史版本混入候选（见 draft-feat-104-
        // batch-update-superseded-version-rescanned），干扰条件筛选的本意验证。
        // —— after：仅命中 t_message 晚于 25 天前的 W2/W3（W1 40 天前被排除）
        String spaceA = "f104-uptemporal";
        ensureSpace(spaceA);
        String w1 = addMemoryWithUserMetadata(spaceA, "FEAT104 更新条件验证记忆 old",
                daysAgoIso(40), Map.of("src", "crm"));
        String w2 = addMemoryWithUserMetadata(spaceA, "FEAT104 更新条件验证记忆 mid",
                daysAgoIso(20), Map.of("src", "crm"));
        String w3 = addMemoryWithUserMetadata(spaceA, "FEAT104 更新条件验证记忆 new",
                daysAgoIso(10), Map.of("src", "web"));
        ObjectNode afterExtras = JSON.createObjectNode();
        afterExtras.put("after", daysAgoIso(25));
        HttpResponse<String> afterRes = batchUpdate(spaceA, afterExtras,
                patch(patchTags("t-after")), null, null);
        assertEquals(200, afterRes.statusCode(),
                "§4.3：更新选择器 after 条件筛选应 200 — body=" + afterRes.body());
        Map<String, JsonNode> afterOutcomes = outcomesByUnit(afterRes);
        assertEquals(2, afterOutcomes.size(),
                "§4.3：after=25 天前应命中 W2/W3 恰 2 项 — body=" + afterRes.body());
        assertTrue(afterOutcomes.containsKey(w2) && afterOutcomes.containsKey(w3),
                "§4.3：after 应命中 t_message 晚于下界的 W2/W3 — body=" + afterRes.body());
        assertFalse(afterOutcomes.containsKey(w1),
                "§4.3：t_message 早于 after 的 W1 应被排除 — body=" + afterRes.body());
        assertEquals("t-after", afterOutcomes.get(w2).path("updated_unit").path("tags").get(0).asText(""),
                "§4.3：被 after 命中的候选应应用补丁 — body=" + afterRes.body());

        // —— after 看守断言（red-first，缺陷 C 修复看守）：上一批次已使 W2/W3 产生 superseded
        // 历史版本（同空间"先改后选"组合）。本批次同选择器再扫一次：候选只含当前有效版本
        // （outcomes 数==当前有效记忆数），superseded 旧版（旧 id w2/w3）不得再次入选
        // （draft-feat-104-batch-update-superseded-version-rescanned 的回归看守）。
        HttpResponse<String> afterGuardRes = batchUpdate(spaceA, afterExtras,
                patch(patchTags("t-after-guard")), null, null);
        assertEquals(200, afterGuardRes.statusCode(),
                "§4.3：after 看守批次应 200 — body=" + afterGuardRes.body());
        Map<String, JsonNode> afterGuardOutcomes = outcomesByUnit(afterGuardRes);
        assertEquals(2, afterGuardOutcomes.size(),
                "§4.3：看守批次 outcomes 数应等于当前有效记忆数（W2'/W3' 恰 2 项），"
                        + "superseded 历史版本不得再匹配 — body=" + afterGuardRes.body());
        assertFalse(afterGuardOutcomes.containsKey(w2) || afterGuardOutcomes.containsKey(w3),
                "§4.3：superseded 旧版（旧 id）不得被选择器重新匹配 — body=" + afterGuardRes.body());
        for (JsonNode outcome : afterGuardOutcomes.values()) {
            assertEquals("t-after-guard", outcome.path("updated_unit").path("tags").get(0).asText(""),
                    "§4.3：看守批次命中的有效版本应应用补丁 — body=" + afterGuardRes.body());
        }

        // —— before：仅命中 t_message 早于 30 天前的 V1（V2 20 天前/V3 10 天前被排除）
        String spaceB = "f104-upbefore";
        ensureSpace(spaceB);
        String v1 = addMemoryWithUserMetadata(spaceB, "FEAT104 更新条件验证记忆 old",
                daysAgoIso(40), Map.of());
        addMemoryWithUserMetadata(spaceB, "FEAT104 更新条件验证记忆 mid", daysAgoIso(20), Map.of());
        addMemoryWithUserMetadata(spaceB, "FEAT104 更新条件验证记忆 new", daysAgoIso(10), Map.of());
        ObjectNode beforeExtras = JSON.createObjectNode();
        beforeExtras.put("before", daysAgoIso(30));
        HttpResponse<String> beforeRes = batchUpdate(spaceB, beforeExtras,
                patch(patchTags("t-before")), null, null);
        assertEquals(200, beforeRes.statusCode(),
                "§4.3：更新选择器 before 条件筛选应 200 — body=" + beforeRes.body());
        Map<String, JsonNode> beforeOutcomes = outcomesByUnit(beforeRes);
        assertEquals(1, beforeOutcomes.size(),
                "§4.3：before=30 天前应仅命中 V1（t_message 早于上界）— body=" + beforeRes.body());
        assertTrue(beforeOutcomes.containsKey(v1),
                "§4.3：before 应命中 V1 — body=" + beforeRes.body());
        assertEquals("t-before", beforeOutcomes.get(v1).path("updated_unit").path("tags").get(0).asText(""),
                "§4.3：被 before 命中的候选应应用补丁 — body=" + beforeRes.body());

        // —— filters：结构化等值谓词（selector.filters 为严格 FilterExpr 形态），
        // field=user_metadata.src → 命中 U1/U2
        String spaceC = "f104-upfilter";
        ensureSpace(spaceC);
        String u1 = addMemoryWithUserMetadata(spaceC, "FEAT104 更新条件验证记忆 crm-one",
                daysAgoIso(40), Map.of("src", "crm"));
        String u2 = addMemoryWithUserMetadata(spaceC, "FEAT104 更新条件验证记忆 crm-two",
                daysAgoIso(20), Map.of("src", "crm"));
        String u3 = addMemoryWithUserMetadata(spaceC, "FEAT104 更新条件验证记忆 web",
                daysAgoIso(10), Map.of("src", "web"));
        ObjectNode filterExtras = JSON.createObjectNode();
        ObjectNode clause = filterExtras.putObject("filters");
        clause.put("field", "user_metadata.src");
        clause.put("op", "eq");
        clause.put("value", "crm");
        HttpResponse<String> filterRes = batchUpdate(spaceC, filterExtras,
                patch(patchTags("t-filter")), null, null);
        assertEquals(200, filterRes.statusCode(),
                "§4.3：更新选择器 filters 结构化谓词应 200 — body=" + filterRes.body());
        Map<String, JsonNode> filterOutcomes = outcomesByUnit(filterRes);
        assertEquals(2, filterOutcomes.size(),
                "§4.3：filters {src:crm} 应命中 U1/U2 恰 2 项 — body=" + filterRes.body());
        assertTrue(filterOutcomes.containsKey(u1) && filterOutcomes.containsKey(u2),
                "§4.3：filters 应命中 user_metadata.src=crm 的 U1/U2 — body=" + filterRes.body());
        assertFalse(filterOutcomes.containsKey(u3),
                "§4.3：src=web 的 U3 不得被命中 — body=" + filterRes.body());
        assertEquals("t-filter", filterOutcomes.get(u1).path("updated_unit").path("tags").get(0).asText(""),
                "§4.3：被 filters 命中的候选应应用补丁 — body=" + filterRes.body());
    }

    // ============================================================ F104-19

    @Test
    @Order(19)
    @Tag("story-feat-104-19")
    @Story("FEAT-104.F104-19: 删除选择器 tags+after+before 三条件取「与」交集（§5.2 场景 1 完整形态）")
    @DisplayName("F104-19 标签与时间范围三条件交集")
    void f10419_deleteTagsAndTimeRangeIntersection() throws Exception {
        String space = "f104-tri";
        ensureSpace(space);
        // T1: 早于 after；T2: 落在区间内且带标签；T3: 区间内但不带标签；T4: 晚于 before
        // 标签在 add 时直接携带（避免 update 引入 superseded 版本干扰候选集）。
        String t1 = addMemoryWithTag(space, "FEAT104 三条件交集验证记忆 too-old", daysAgoIso(75), "p90");
        String t2 = addMemoryWithTag(space, "FEAT104 三条件交集验证记忆 in-range", daysAgoIso(60), "p90");
        String t3 = addMemory(space, "FEAT104 三条件交集验证记忆 no-tag", daysAgoIso(45));
        String t4 = addMemoryWithTag(space, "FEAT104 三条件交集验证记忆 too-new", daysAgoIso(10), "p90");

        ObjectNode extras = JSON.createObjectNode();
        extras.put("after", daysAgoIso(70));
        extras.put("before", daysAgoIso(30));
        extras.set("tags", JSON.valueToTree(List.of("p90")));
        HttpResponse<String> delete = batchDelete(space, extras, "forget");
        assertEquals(200, delete.statusCode(),
                "§5.2：tags+after+before 组合选择器应 200 — body=" + delete.body());
        List<String> deleted = deletedIds(delete);
        assertEquals(List.of(t2), deleted,
                "§5.2：三条件取「与」，仅区间内且带 p90 标签的 T2 应被删除 — deleted=" + deleted);
        assertEquals("active", lifecycleOf(space, t1), "T1 早于 after 应排除");
        assertEquals("active", lifecycleOf(space, t3), "T3 不带标签应排除");
        assertEquals("active", lifecycleOf(space, t4), "T4 晚于 before 应排除");
    }

    // ============================================================ F104-20

    @Test
    @Order(20)
    @Tag("story-feat-104-20")
    @Story("FEAT-104.F104-20: 内容变更补丁重新计算嵌入向量——更新后按新内容可检索、旧内容不可检索")
    @DisplayName("F104-20 内容更新后检索索引随之更新")
    void f10420_contentUpdateReindexesForRetrieval() throws Exception {
        String space = "f104-embed";
        ensureSpace(space);
        // 新旧内容词表完全不相交：排除哈希嵌入下部分 token 重叠带来的合法低分相似
        String e1 = addMemory(space, "ZEBRA-QUARK-104 alpha gamma delta");

        // 前置基线：索引异步构建，轮询至旧内容可检索且命中原版本
        assertTrue(searchEventuallyContains(space, "ZEBRA-QUARK-104", e1, 30_000),
                "前置：写入后旧内容应可检索并命中原版本（索引就绪）");

        ObjectNode extras = JSON.createObjectNode();
        extras.set("unit_ids", JSON.valueToTree(List.of(e1)));
        HttpResponse<String> response = batchUpdate(space, extras,
                patch(patchContent("QED-PHOTON-775 omega epsilon kappa")), null, null);
        assertEquals(200, response.statusCode(),
                "前置：batch_update 内容变更应 200 — body=" + response.body());
        JsonNode updatedUnit = JSON.readTree(response.body()).path("outcomes").get(0).path("updated_unit");
        String newId = updatedUnit.path("id").asText("");
        assertFalse(newId.isEmpty(), "前置：updated_unit 应含新版本 id — body=" + response.body());

        // 新内容应可检索命中（嵌入/索引按更新后内容重建）
        assertTrue(searchEventuallyContains(space, "QED-PHOTON-775", newId, 30_000),
                "§5.3：内容变更补丁应重算嵌入向量——按新内容检索应命中更新后的记忆 " + newId);

        // 旧内容不应再命中该记忆的任何版本（新旧词表不相交，命中即索引残留）
        assertTrue(searchEventuallyExcludes(space, "ZEBRA-QUARK-104", List.of(e1, newId), 30_000),
                "§5.3：按旧内容检索不得命中已更新记忆的任何版本（索引应随更新退出旧内容）");
    }

    // ------------------------------------------------------------ 扩展断言辅助

    /** 解析 batch_update 响应为 unit_id → outcome 映射。 */
    private Map<String, JsonNode> outcomesByUnit(HttpResponse<String> response) throws IOException {
        Map<String, JsonNode> byUnit = new LinkedHashMap<>();
        JSON.readTree(response.body()).path("outcomes")
                .forEach(o -> byUnit.put(o.path("unit_id").asText(), o));
        return byUnit;
    }

    private List<String> tagsOf(String space, String unitId) throws Exception {
        HttpResponse<String> get = getUnit(space, unitId);
        List<String> tags = new ArrayList<>();
        JSON.readTree(get.body()).path("tags").forEach(n -> tags.add(n.asText()));
        return tags;
    }

    /** 写入一条带标签、带 occurred_at 的 ACTIVE 记忆，返回分配的 unit id。 */
    private String addMemoryWithTag(String space, String content, String occurredAt,
                                    String tag) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("content", content);
        req.set("scope", scope(space));
        if (occurredAt != null) {
            req.put("occurred_at", occurredAt);
        }
        req.set("tags", JSON.valueToTree(List.of(tag)));
        HttpResponse<String> response = post("add", req);
        assertEquals(200, response.statusCode(),
                "前置：写入记忆应 200 — body=" + response.body());
        return JSON.readTree(response.body()).get(0).path("id").asText("");
    }

    /** 解析 /v1/search 响应为命中 unit_id 列表（响应结构：items[].unit_id）。 */
    private List<String> hitIds(HttpResponse<String> response) throws IOException {
        List<String> ids = new ArrayList<>();
        JSON.readTree(response.body()).path("items")
                .forEach(it -> ids.add(it.path("unit_id").asText()));
        return ids;
    }

    /** 索引异步构建：轮询 search 直至预期 unit_id 命中或超时。 */
    private boolean searchEventuallyContains(String space, String query, String expectedId, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = search(space, query);
            if (resp.statusCode() == 200 && hitIds(resp).contains(expectedId)) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    /** 索引异步更新：轮询直至 banned ids 全部退出命中，并连续 3 次确认不回弹。 */
    private boolean searchEventuallyExcludes(String space, String query, List<String> bannedIds, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = search(space, query);
            List<String> hits = resp.statusCode() == 200 ? hitIds(resp) : List.of();
            if (hits.stream().noneMatch(bannedIds::contains)) {
                boolean stable = true;
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(500);
                    HttpResponse<String> again = search(space, query);
                    List<String> againHits = again.statusCode() == 200
                            ? hitIds(again) : List.of("HTTP-" + again.statusCode());
                    if (againHits.stream().anyMatch(bannedIds::contains)) {
                        stable = false;
                        break;
                    }
                }
                if (stable) {
                    return true;
                }
            }
            Thread.sleep(500);
        }
        return false;
    }
}
