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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-102 策略管理与审计（参数热调与审计回滚）黑盒验收。
 *
 * <p>SUT = agent-memory（Python，{@code jiuwen_memory_entry.http_server}），由本类经 ProcessBuilder
 * 从 feature_source 源码目录直接拉起（offline profile + dev auth + root 身份），并自主管生命周期——
 * F102-02 的跨重启持久化断言需要停/起 SUT 进程。全部断言落在 HTTP wire 契约上：</p>
 * <ul>
 *   <li>{@code POST /v1/admin_set} / {@code /v1/admin_get} / {@code /v1/admin_all}（MemoryAPI 同名方法）；
 *       成功形态：admin_get 返回 JSON 字符串值，admin_set 返回 {@code null}，admin_all 返回键值对象；</li>
 *   <li>特性文档 §4.5 新端点：{@code /v1/policy_history}、{@code /v1/policy_rollback}、
 *       {@code /v1/policy_snapshot_create}、{@code /v1/policy_snapshot_diff}、{@code /v1/policy_snapshot_rollback}；</li>
 *   <li>错误封套：{@code {"error","message","retryable","request_id"}}，PolicyError→400、
 *       NotFoundError→404、未知 verb→404 UnknownVerb。</li>
 * </ul>
 *
 * <p>期望值口径：特性文档 FEAT-102（2026-10-23）§3.6/§4.5 与 FRS-102 v0.1 FR-01~FR-08 / AC-01~AC-08。
 * 红 = 产品行为与文档不符（实现缺口），不通过改用例消化。</p>
 */
@Tags({@Tag("integration"), @Tag("agent-memory"), @Tag("feat-102")})
@Feature("FEAT-102: agent-memory 策略管理与审计（参数热调与审计回滚）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat102PolicyConfigBlackboxTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT_TOKEN = "root-key";

    /** SUT 源码目录（feature_source）；系统属性 -Dsut.memory102.sourceDir 或 env SUT_MEMORY_SOURCE_DIR 可覆盖。 */
    private static final String SOURCE_DIR = System.getProperty(
            "sut.memory102.sourceDir",
            envOrDefault("SUT_MEMORY_SOURCE_DIR", "D:/code/test/agent-memory"));
    /** SUT 端口；缺省取一个空闲端口，避免与手工拉起的 8137 或遗留进程冲突。 */
    private static final int PORT = Integer.parseInt(System.getProperty("sut.memory102.port", "0"));

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
    /** 策略 KV 落盘 SQLite 文件——FR-02 跨重启持久化要求 KV 后端跨进程存续（默认 kv_store=memory 为进程内态）。 */
    private Path policyKvDb;

    @BeforeAll
    void startSut() throws Exception {
        assumeTrue(Files.isDirectory(Path.of(SOURCE_DIR)),
                "SUT 源码目录不存在：" + SOURCE_DIR + " — 请配置 -Dsut.memory102.sourceDir");
        port = PORT > 0 ? PORT : freePort();
        base = "http://127.0.0.1:" + port;
        devConfigFile = writeDevIdentityConfig();
        startProcess();
        waitHealthy();
    }

    @AfterAll
    void stopSut() {
        stopProcess();
        if (devConfigFile != null) {
            try {
                Files.deleteIfExists(devConfigFile);
            } catch (IOException ignored) {
                // 清理失败不影响测试结论
            }
        }
        if (policyKvDb != null) {
            try {
                Files.deleteIfExists(policyKvDb);
                Files.deleteIfExists(policyKvDb.resolveSibling(policyKvDb.getFileName() + "-wal"));
                Files.deleteIfExists(policyKvDb.resolveSibling(policyKvDb.getFileName() + "-shm"));
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

    /** dev 身份配置层：root-key → Role.ROOT（ADMINISTER_SYSTEM 要求 root 角色，见 space_roles/standard_authorizer）。 */
    private Path writeDevIdentityConfig() throws IOException {
        Path file = Path.of("target", "sit-logs", "feat102-dev-identities.json");
        Files.createDirectories(file.getParent());
        // FR-02/AC-02：策略持久化断言要求 KV 跨进程存续——配置层把 kv_store.default
        // 指到 SQLite 落盘后端（SUT 默认 kv_store=memory 为进程内态，重启即失）。
        // 注意：HTTP 入口 Server.build 只把 memory_api 配置段交给内核装配，
        // 故 kv_store 覆盖必须嵌套在 memory_api 段下（jiuwen_memory_entry/core/server.py）。
        policyKvDb = file.getParent().resolve("feat102-policy-kv.db");
        Files.deleteIfExists(policyKvDb);
        String json = "{\"http\": {\"dev_identities\": {\"" + ROOT_TOKEN + "\": "
                + "{\"actor\": {\"org\": \"local\", \"user\": \"developer\"}, \"role\": \"root\"}}}, "
                + "\"memory_api\": {\"kv_store\": {\"default\": {\"target\": \"sqlite\", "
                + "\"params\": {\"db_path\": \"" + policyKvDb.toAbsolutePath().toString().replace('\\', '/')
                + "\"}}}}}";
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private void startProcess() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "python", "-m", "jiuwen_memory_entry.http_server",
                "--auth-mode", "dev", "--host", "127.0.0.1", "--port", String.valueOf(port),
                devConfigFile.toAbsolutePath().toString());
        pb.directory(Path.of(SOURCE_DIR).toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(Path.of("target", "sit-logs", "feat102-sut-" + port + ".log").toFile());
        sut = pb.start();
    }

    private void stopProcess() {
        if (sut != null && sut.isAlive()) {
            sut.destroy();
            try {
                if (!sut.waitFor(15, TimeUnit.SECONDS)) {
                    sut.destroyForcibly();
                    sut.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sut.destroyForcibly();
            }
        }
        sut = null;
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

    /** 重启 SUT（F102-02 跨重启持久化断言依赖此能力）。 */
    private void restartSut() throws Exception {
        stopProcess();
        startProcess();
        waitHealthy();
    }

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

    private ObjectNode body(String key, String value) {
        ObjectNode node = JSON.createObjectNode();
        if (key != null) {
            node.put("key", key);
        }
        if (value != null) {
            node.put("value", value);
        }
        return node;
    }

    /** FRS-102 §10.1：space: tuple[str, str] → wire 形态为 JSON 数组 [org, space]。 */
    private ObjectNode bodyWithSpace(String key, String value, String org, String space) {
        ObjectNode node = body(key, value);
        node.putArray("space").add(org).add(space);
        return node;
    }

    private void createSpace(String org, String space) throws Exception {
        ObjectNode createSpace = JSON.createObjectNode();
        ObjectNode spec = createSpace.putObject("spec");
        spec.put("org", org);
        spec.put("space", space);
        spec.put("display_name", "F102 " + space);
        post("create_space", createSpace); // 已存在时失败可容忍
    }

    private JsonNode getSpacePolicy(String org, String space) throws Exception {
        ObjectNode req = JSON.createObjectNode();
        req.put("org", org);
        req.put("space", space);
        HttpResponse<String> resp = post("get_space_policy", req);
        assertEquals(200, resp.statusCode(), "get_space_policy 应 200 — body=" + resp.body());
        return JSON.readTree(resp.body());
    }

    // ============================================================ F102-01

    @Test
    @Order(1)
    @Tag("story-feat-102-01")
    @Story("FEAT-102.F102-01: 迁移键 retrieval.min_score 经 admin_set 运行时热调，set/get/all 值一致")
    @DisplayName("F102-01 迁移键运行时热调")
    void f10201_migrationKeyHotTuning() throws Exception {
        // set
        HttpResponse<String> set = post("admin_set", body("retrieval.min_score", "0.3"));
        assertEquals(200, set.statusCode(),
                "FR-01：retrieval.min_score 必须是可运行时热调的策略键（迁移自装配期 YAML）"
                        + " — body=" + set.body());
        // get
        HttpResponse<String> get = post("admin_get", body("retrieval.min_score", null));
        assertEquals(200, get.statusCode(), "admin_get 应 200 — body=" + get.body());
        assertEquals("0.3", JSON.readTree(get.body()).asText(),
                "admin_get(retrieval.min_score) 应返回刚写入的 \"0.3\"");
        // all
        HttpResponse<String> all = post("admin_all", body(null, null));
        assertEquals(200, all.statusCode(), "admin_all 应 200 — body=" + all.body());
        JsonNode policies = JSON.readTree(all.body());
        assertTrue(policies.has("retrieval.min_score"),
                "admin_all 应包含迁移键 retrieval.min_score — policies=" + policies);
        assertEquals("0.3", policies.get("retrieval.min_score").asText(),
                "admin_all 中 retrieval.min_score 应为 \"0.3\"");
    }

    // ============================================================ F102-02

    @Test
    @Order(2)
    @Tag("story-feat-102-02")
    @Story("FEAT-102.F102-02: 保留活键 admin_set 后跨重启持久化，不回退装配期默认")
    @DisplayName("F102-02 保留活键热调 + 跨重启持久化")
    void f10202_retainedKeyPersistsAcrossRestart() throws Exception {
        HttpResponse<String> set = post("admin_set",
                body("lifecycle.expired_active.target", "archived"));
        assertEquals(200, set.statusCode(), "lifecycle.expired_active.target 应可运行时设置"
                + " — body=" + set.body());

        HttpResponse<String> get = post("admin_get", body("lifecycle.expired_active.target", null));
        assertEquals(200, get.statusCode());
        assertEquals("archived", JSON.readTree(get.body()).asText(), "重启前 get 应为 \"archived\"");

        restartSut();

        HttpResponse<String> after = post("admin_get", body("lifecycle.expired_active.target", null));
        assertEquals(200, after.statusCode(), "重启后 admin_get 应 200 — body=" + after.body());
        assertEquals("archived", JSON.readTree(after.body()).asText(),
                "FR-02/AC-02：重启后策略值必须从 KV（SQLite 落盘后端）恢复为 \"archived\"，"
                        + "不得回退装配期默认 \"forgotten\"");
    }

    // ============================================================ F102-03

    @Test
    @Order(3)
    @Tag("story-feat-102-03")
    @Story("FEAT-102.F102-03: policy_history 按时间倒序返回含 old_value/new_value/timestamp 的变更记录")
    @DisplayName("F102-03 策略变更历史查询")
    void f10203_policyHistoryReturnsChangeRecords() throws Exception {
        // 前置：对 scope.require_space 产生一次值级变更（true→false）
        HttpResponse<String> set = post("admin_set", body("scope.require_space", "false"));
        assertEquals(200, set.statusCode(), "前置变更应成功 — body=" + set.body());

        HttpResponse<String> history = post("policy_history",
                body("scope.require_space", null));
        assertEquals(200, history.statusCode(),
                "FR-05：policy_history 端点应存在且返回 200（现 404 UnknownVerb = 端点缺失）"
                        + " — body=" + history.body());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && !records.isEmpty(),
                "policy_history 应返回非空记录数组 — body=" + history.body());
        for (JsonNode record : records) {
            assertFalse(record.path("old_value").isMissingNode(),
                    "变更记录应含 old_value 字段 — record=" + record);
            assertFalse(record.path("new_value").isMissingNode(),
                    "变更记录应含 new_value 字段 — record=" + record);
            assertFalse(record.path("timestamp").asText("").isEmpty(),
                    "变更记录应含非空 timestamp — record=" + record);
        }
        for (int i = 1; i < records.size(); i++) {
            String prev = records.get(i - 1).path("timestamp").asText();
            String cur = records.get(i).path("timestamp").asText();
            assertTrue(prev.compareTo(cur) >= 0,
                    "记录应按时间倒序 — records[" + (i - 1) + "]=" + prev + " 应 ≥ records[" + i + "]=" + cur);
        }
    }

    // ============================================================ F102-04

    @Test
    @Order(4)
    @Tag("story-feat-102-04")
    @Story("FEAT-102.F102-04: policy_rollback 空 change_id 回滚到最近一条记录的旧值，回滚本身落新记录")
    @DisplayName("F102-04 单键回滚（最近一条）")
    void f10204_rollbackToLatestOldValue() throws Exception {
        // 连续两次变更：60 → 80 → 100
        assertEquals(200, post("admin_set", body("retrieval.over_fetch_floor", "80")).statusCode(),
                "前置变更1应成功（键须已迁移）");
        assertEquals(200, post("admin_set", body("retrieval.over_fetch_floor", "100")).statusCode(),
                "前置变更2应成功");

        HttpResponse<String> rollback = post("policy_rollback",
                body("retrieval.over_fetch_floor", null));
        assertEquals(200, rollback.statusCode(),
                "FR-06：policy_rollback 端点应存在且返回 200（现 404 UnknownVerb = 端点缺失）"
                        + " — body=" + rollback.body());

        HttpResponse<String> get = post("admin_get", body("retrieval.over_fetch_floor", null));
        assertEquals(200, get.statusCode());
        assertEquals("80", JSON.readTree(get.body()).asText(),
                "AC-03：回滚应恢复最近一条记录的旧值 \"80\"");

        HttpResponse<String> history = post("policy_history", body("retrieval.over_fetch_floor", null));
        assertEquals(200, history.statusCode());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && records.size() >= 1, "history 应非空");
        assertEquals("80", records.get(0).path("new_value").asText(),
                "回滚写回本身应落一条新变更记录且为最新一条（new_value=\"80\"）");
    }

    // ============================================================ F102-05

    @Test
    @Order(5)
    @Tag("story-feat-102-05")
    @Story("FEAT-102.F102-05: policy_rollback 指定 change_id 回滚到该记录的旧值")
    @DisplayName("F102-05 单键回滚（指定 change_id）")
    void f10205_rollbackToSpecifiedChangeId() throws Exception {
        // 连续两次变更并取第一条 change_id
        assertEquals(200, post("admin_set", body("retrieval.rerank_max", "70")).statusCode(),
                "前置变更1应成功");
        assertEquals(200, post("admin_set", body("retrieval.rerank_max", "90")).statusCode(),
                "前置变更2应成功");

        HttpResponse<String> history = post("policy_history", body("retrieval.rerank_max", null));
        assertEquals(200, history.statusCode(),
                "FR-05：policy_history 端点应存在且返回 200（现 404 UnknownVerb = 端点缺失）"
                        + " — body=" + history.body());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && records.size() >= 2,
                "两次变更后 history 至少 2 条 — body=" + history.body());
        JsonNode oldest = records.get(records.size() - 1);
        String changeId = oldest.path("change_id").asText("");
        assertFalse(changeId.isEmpty(), "变更记录应含 change_id — record=" + oldest);

        ObjectNode withChange = body("retrieval.rerank_max", null);
        withChange.put("change_id", changeId);
        HttpResponse<String> rollback = post("policy_rollback", withChange);
        assertEquals(200, rollback.statusCode(),
                "指定 change_id 的回滚应 200 — body=" + rollback.body());

        HttpResponse<String> get = post("admin_get", body("retrieval.rerank_max", null));
        assertEquals(200, get.statusCode());
        assertEquals(oldest.path("old_value").asText(), JSON.readTree(get.body()).asText(),
                "AC-03：回滚值应等于指定记录的 old_value（两次变更前的初值 \"60\"）");
    }

    // ============================================================ F102-06

    @Test
    @Order(6)
    @Tag("story-feat-102-06")
    @Story("FEAT-102.F102-06: policy_snapshot_create 返回快照标识，policy_snapshot_diff 返回与当前策略差异")
    @DisplayName("F102-06 快照创建与对比")
    void f10206_snapshotCreateAndDiff() throws Exception {
        HttpResponse<String> create = post("policy_snapshot_create", body(null, null));
        assertEquals(200, create.statusCode(),
                "FR-07：policy_snapshot_create 端点应存在且返回 200（现 404 UnknownVerb = 端点缺失）"
                        + " — body=" + create.body());
        JsonNode created = JSON.readTree(create.body());
        // FRS-102 §10.1：快照 ID（snapshot_id: str）——HTTP 面对返回值机械转换，create 返回非空 JSON 字符串
        assertTrue(created.isTextual() && !created.asText().isEmpty(),
                "create 应返回非空快照标识（JSON 字符串，FRS-102 §10.1 snapshot_id: str）"
                        + " — body=" + create.body());
        String snapshotId = created.asText();

        // 制造差异
        assertEquals(200, post("admin_set", body("retrieval.min_score", "0.9")).statusCode());

        ObjectNode diffReq = JSON.createObjectNode();
        diffReq.put("snapshot_id", snapshotId);
        HttpResponse<String> diff = post("policy_snapshot_diff", diffReq);
        assertEquals(200, diff.statusCode(),
                "policy_snapshot_diff 应 200 — body=" + diff.body());
        JsonNode diffBody = JSON.readTree(diff.body());
        assertTrue(diffBody.has("changed") && diffBody.get("changed").has("retrieval.min_score"),
                "diff.changed 应含 retrieval.min_score — body=" + diff.body());
    }

    // ============================================================ F102-07

    @Test
    @Order(7)
    @Tag("story-feat-102-07")
    @Story("FEAT-102.F102-07: policy_snapshot_rollback 逐键写回快照值并返回受影响键列表")
    @DisplayName("F102-07 快照回滚")
    void f10207_snapshotRollbackWritesBackAllDiffKeys() throws Exception {
        // 快照值 = 创建快照时的当前值（先读出，用于回滚后比对）
        HttpResponse<String> before = post("admin_get", body("retrieval.min_score", null));
        assertEquals(200, before.statusCode(), "前置：读取当前值应成功 — body=" + before.body());
        String snapshotValue = JSON.readTree(before.body()).asText();

        HttpResponse<String> create = post("policy_snapshot_create", body(null, null));
        assertEquals(200, create.statusCode());
        JsonNode created = JSON.readTree(create.body());
        // FRS-102 §10.1：快照 ID（snapshot_id: str）——create 返回非空 JSON 字符串
        assertTrue(created.isTextual() && !created.asText().isEmpty(),
                "create 应返回非空快照标识（JSON 字符串）— body=" + create.body());
        String snapshotId = created.asText();

        assertEquals(200, post("admin_set", body("retrieval.min_score", "0.5")).statusCode(),
                "制造差异的变更应成功");

        ObjectNode rollbackReq = JSON.createObjectNode();
        rollbackReq.put("snapshot_id", snapshotId);
        HttpResponse<String> rollback = post("policy_snapshot_rollback", rollbackReq);
        assertEquals(200, rollback.statusCode(),
                "FR-07/A2：policy_snapshot_rollback 应 200 — body=" + rollback.body());
        JsonNode affected = JSON.readTree(rollback.body());
        String affectedText = affected.toString();
        assertTrue(affectedText.contains("retrieval.min_score"),
                "响应应返回受影响键列表且含 retrieval.min_score — body=" + rollback.body());

        HttpResponse<String> get = post("admin_get", body("retrieval.min_score", null));
        assertEquals(200, get.statusCode());
        assertEquals(snapshotValue, JSON.readTree(get.body()).asText(),
                "快照回滚后 retrieval.min_score 应回到快照值（" + snapshotValue + "）");

        HttpResponse<String> history = post("policy_history", body("retrieval.min_score", null));
        assertEquals(200, history.statusCode());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && records.size() >= 1);
        assertEquals(snapshotValue, records.get(0).path("new_value").asText(),
                "快照回滚的逐键写回应各落一条变更记录（最新一条 new_value=快照值）");
    }

    // ============================================================ F102-08

    @Test
    @Order(8)
    @Tag("story-feat-102-08")
    @Story("FEAT-102.F102-08: rerank.enabled 键可运行时读写且值往返一致")
    @DisplayName("F102-08 rerank.enabled 热调可读写")
    void f10208_rerankEnabledReadWrite() throws Exception {
        HttpResponse<String> setOff = post("admin_set", body("rerank.enabled", "false"));
        assertEquals(200, setOff.statusCode(), "rerank.enabled 应可运行时设置 — body=" + setOff.body());

        HttpResponse<String> getOff = post("admin_get", body("rerank.enabled", null));
        assertEquals(200, getOff.statusCode());
        assertEquals("false", JSON.readTree(getOff.body()).asText(),
                "FR-04：get 应返回刚写入的 \"false\"");

        assertEquals(200, post("admin_set", body("rerank.enabled", "true")).statusCode());
        HttpResponse<String> getOn = post("admin_get", body("rerank.enabled", null));
        assertEquals(200, getOn.statusCode());
        assertEquals("true", JSON.readTree(getOn.body()).asText(), "恢复后 get 应为 \"true\"");
    }

    // ============================================================ F102-09

    @Test
    @Order(9)
    @Tag("story-feat-102-09")
    @Story("FEAT-102.F102-09: admin_set 带 space 参数写空间级 pipeline_profiles.retrieval_min_score")
    @DisplayName("F102-09 空间级 pipeline_profiles 覆盖（admin_set space 参数）")
    void f10209_spaceScopedPipelineProfileOverride() throws Exception {
        // 前置：确保测试空间存在
        ObjectNode createSpace = JSON.createObjectNode();
        ObjectNode spec = createSpace.putObject("spec");
        spec.put("org", "local");
        spec.put("space", "f102-space");
        spec.put("display_name", "F102 space policy test");
        post("create_space", createSpace); // 已存在时失败可容忍

        // admin_set 带 space 参数（DEC-04/FR-08：space=(org, space) 操作空间级策略；
        // FRS-102 §10.1 space: tuple[str, str] → wire 形态为 JSON 数组 [org, space]；
        // 键为全局策略键 retrieval.min_score，空间级自动映射 pipeline_profiles.retrieval_min_score）
        ObjectNode setSpace = body("retrieval.min_score", "0.3");
        setSpace.putArray("space").add("local").add("f102-space");
        HttpResponse<String> set = post("admin_set", setSpace);
        assertEquals(200, set.statusCode(),
                "DEC-04/FR-08：admin_set 必须支持 space 参数（JSON 数组 [org, space]）写空间级策略键"
                        + " — body=" + set.body());

        ObjectNode getPolicy = JSON.createObjectNode();
        getPolicy.put("org", "local");
        getPolicy.put("space", "f102-space");
        HttpResponse<String> policy = post("get_space_policy", getPolicy);
        assertEquals(200, policy.statusCode(), "get_space_policy 应 200 — body=" + policy.body());
        JsonNode profiles = JSON.readTree(policy.body()).path("pipeline_profiles");
        assertTrue(profiles.has("retrieval_min_score"),
                "空间策略 pipeline_profiles 应含 retrieval_min_score — body=" + policy.body());
        assertEquals("0.3", profiles.get("retrieval_min_score").asText(),
                "pipeline_profiles.retrieval_min_score 应为 \"0.3\"");
    }

    // ============================================================ F102-10

    @Test
    @Order(10)
    @Tag("story-feat-102-10")
    @Story("FEAT-102.F102-10: 未知策略键 admin_set 拒绝（PolicyError 400）且键未被创建")
    @DisplayName("F102-10 异常：未知键 set 拒绝")
    void f10210_unknownKeySetRejected() throws Exception {
        HttpResponse<String> set = post("admin_set", body("not.exist.key", "x"));
        assertEquals(400, set.statusCode(), "未知键应拒绝 — body=" + set.body());
        assertEquals("PolicyError", JSON.readTree(set.body()).path("error").asText(),
                "错误封套 error 应为 PolicyError — body=" + set.body());

        HttpResponse<String> get = post("admin_get", body("not.exist.key", null));
        assertEquals(400, get.statusCode(), "拒绝后键不应存在（get 仍报错）— body=" + get.body());
    }

    // ============================================================ F102-11

    @Test
    @Order(11)
    @Tag("story-feat-102-11")
    @Story("FEAT-102.F102-11: policy_history 对无变更记录的键返回空列表")
    @DisplayName("F102-11 异常：history 查无记录")
    void f10211_historyOfUnchangedKeyReturnsEmpty() throws Exception {
        // hierarchy.enabled 为保留活键但本类从未变更过它（"0.3" 等值变更也只会落在其他键上）
        HttpResponse<String> history = post("policy_history", body("hierarchy.enabled", null));
        assertEquals(200, history.statusCode(),
                "查无记录应返回 200 而非报错 — body=" + history.body());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && records.isEmpty(),
                "FR-05/§10.1：无变更记录的键应返回空数组 — body=" + history.body());
    }

    // ============================================================ F102-12

    @Test
    @Order(12)
    @Tag("story-feat-102-12")
    @Story("FEAT-102.F102-12: 快照 diff/rollback 对不存在的 snapshot_id 返回 NotFoundError 404")
    @DisplayName("F102-12 异常：快照 id 不存在")
    void f10212_missingSnapshotIdReturnsNotFound() throws Exception {
        ObjectNode diffReq = JSON.createObjectNode();
        diffReq.put("snapshot_id", "snap_not_exist");
        HttpResponse<String> diff = post("policy_snapshot_diff", diffReq);
        assertEquals(404, diff.statusCode(), "diff 不存在快照应 404 — body=" + diff.body());
        assertEquals("NotFoundError", JSON.readTree(diff.body()).path("error").asText(),
                "错误封套 error 应为 NotFoundError — body=" + diff.body());

        ObjectNode rollbackReq = JSON.createObjectNode();
        rollbackReq.put("snapshot_id", "snap_not_exist");
        HttpResponse<String> rollback = post("policy_snapshot_rollback", rollbackReq);
        assertEquals(404, rollback.statusCode(), "rollback 不存在快照应 404 — body=" + rollback.body());
        assertEquals("NotFoundError", JSON.readTree(rollback.body()).path("error").asText(),
                "错误封套 error 应为 NotFoundError — body=" + rollback.body());
    }

    // ============================================================ F102-13

    @Test
    @Order(13)
    @Tag("story-feat-102-13")
    @Story("FEAT-102.F102-13: policy_history limit 截断为最近 N 条且保持倒序")
    @DisplayName("F102-13 边界：limit 截断")
    void f10213_historyLimitTruncation() throws Exception {
        // 前置：对 lifecycle.superseded.target 产生 3 条变更
        String[] values = {"archived", "forgotten", "archived"};
        for (String value : values) {
            assertEquals(200, post("admin_set", body("lifecycle.superseded.target", value)).statusCode(),
                    "前置变更 " + value + " 应成功");
        }

        ObjectNode req = body("lifecycle.superseded.target", null);
        req.put("limit", 2);
        HttpResponse<String> history = post("policy_history", req);
        assertEquals(200, history.statusCode(), "带 limit 的查询应 200 — body=" + history.body());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray(), "应返回数组 — body=" + history.body());
        assertTrue(records.size() <= 2, "limit=2 应截断为 ≤2 条 — 实得 " + records.size() + " 条");
        assertEquals(2, records.size(), "已有 3 条记录时 limit=2 应返回恰好 2 条（最近 2 条）");
        for (int i = 1; i < records.size(); i++) {
            String prev = records.get(i - 1).path("timestamp").asText();
            String cur = records.get(i).path("timestamp").asText();
            assertTrue(prev.compareTo(cur) >= 0, "截断结果仍须倒序");
        }
    }

    // ============================================================ F102-14

    @Test
    @Order(14)
    @Tag("story-feat-102-14")
    @Story("FEAT-102.F102-14: 并发 admin_set 全部成功且变更记录完整")
    @DisplayName("F102-14 时序/并发：并发 set 记录完整")
    void f10214_concurrentSetsAllRecorded() throws Exception {
        // hierarchy.auto_derive 是现有全局键，选它承载并发写入（值域为布尔字符串）
        String key = "hierarchy.auto_derive";
        List<String> values = List.of("true", "false", "true", "false", "true");

        HttpResponse<String> probe = post("admin_set", body(key, "false"));
        assertEquals(200, probe.statusCode(), "前置：键应可写 — body=" + probe.body());

        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (String value : values) {
                futures.add(pool.submit(() -> post("admin_set", body(key, value))));
            }
            int successCount = 0;
            for (Future<HttpResponse<String>> future : futures) {
                HttpResponse<String> response = future.get(60, TimeUnit.SECONDS);
                if (response.statusCode() == 200) {
                    successCount++;
                } else {
                    assertTrue(false, "并发 admin_set 应全部成功 — "
                            + response.statusCode() + " body=" + response.body());
                }
            }
            assertEquals(5, successCount, "5 次并发 set 应全部 200");
        } finally {
            pool.shutdownNow();
        }

        HttpResponse<String> get = post("admin_get", body(key, null));
        assertEquals(200, get.statusCode());
        String finalValue = JSON.readTree(get.body()).asText();
        assertTrue(values.contains(finalValue),
                "最终值应为 5 个写入值之一 — 实得 " + finalValue);

        HttpResponse<String> history = post("policy_history", body(key, null));
        assertEquals(200, history.statusCode());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && records.size() >= 5,
                "并发写入的变更应全部落记录（≥5 条）— 实得 " + records.size());
    }

    // ============================================================ F102-15

    @Test
    @Order(15)
    @Tag("story-feat-102-15")
    @Story("FEAT-102.F102-15: 迁移键 evolver.dedup_high_similarity 经 admin_set 运行时热调，set/get/all 值一致")
    @DisplayName("F102-15 迁移键 evolver.dedup_high_similarity 热调")
    void f10215_dedupHighSimilarityHotTuning() throws Exception {
        HttpResponse<String> set = post("admin_set", body("evolver.dedup_high_similarity", "0.85"));
        assertEquals(200, set.statusCode(),
                "FR-01：evolver.dedup_high_similarity 必须是可运行时热调的迁移键 — body=" + set.body());

        HttpResponse<String> get = post("admin_get", body("evolver.dedup_high_similarity", null));
        assertEquals(200, get.statusCode(), "admin_get 应 200 — body=" + get.body());
        assertEquals("0.85", JSON.readTree(get.body()).asText(),
                "admin_get(evolver.dedup_high_similarity) 应返回刚写入的 \"0.85\"");

        HttpResponse<String> all = post("admin_all", body(null, null));
        assertEquals(200, all.statusCode(), "admin_all 应 200 — body=" + all.body());
        JsonNode policies = JSON.readTree(all.body());
        assertTrue(policies.has("evolver.dedup_high_similarity"),
                "admin_all 应包含迁移键 evolver.dedup_high_similarity — policies=" + policies);
        assertEquals("0.85", policies.get("evolver.dedup_high_similarity").asText(),
                "admin_all 中 evolver.dedup_high_similarity 应为 \"0.85\"");
    }

    // ============================================================ F102-16

    @Test
    @Order(16)
    @Tag("story-feat-102-16")
    @Story("FEAT-102.F102-16: 本期新增键 lifecycle.forgotten_retention_days/purge_forgotten.enabled/"
            + "access_tracker.flush_interval 可 set/get、落变更记录、跨重启持久化")
    @DisplayName("F102-16 本期新增键持久化与审计")
    void f10216_newlyAddedKeysPersistAndAudit() throws Exception {
        String[][] keysAndValues = {
            {"lifecycle.forgotten_retention_days", "45"},
            {"lifecycle.purge_forgotten.enabled", "false"},
            {"access_tracker.flush_interval", "120"},
        };
        for (String[] kv : keysAndValues) {
            HttpResponse<String> set = post("admin_set", body(kv[0], kv[1]));
            assertEquals(200, set.statusCode(), "新增键 " + kv[0] + " 应可运行时设置 — body=" + set.body());
            HttpResponse<String> get = post("admin_get", body(kv[0], null));
            assertEquals(200, get.statusCode(), "新增键 " + kv[0] + " admin_get 应 200");
            assertEquals(kv[1], JSON.readTree(get.body()).asText(),
                    "admin_get(" + kv[0] + ") 应返回刚写入的 \"" + kv[1] + "\"");
        }

        // 变更记录：值级审计（FR-05）
        HttpResponse<String> history = post("policy_history", body("lifecycle.forgotten_retention_days", null));
        assertEquals(200, history.statusCode(), "policy_history 应 200 — body=" + history.body());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && !records.isEmpty(),
                "本期新增键的变更应落记录 — body=" + history.body());
        assertFalse(records.get(0).path("old_value").isMissingNode(), "记录应含 old_value");
        assertFalse(records.get(0).path("new_value").isMissingNode(), "记录应含 new_value");
        assertFalse(records.get(0).path("timestamp").asText("").isEmpty(), "记录应含非空 timestamp");

        // 跨重启持久化（FR-02/AC-02）
        restartSut();
        for (String[] kv : keysAndValues) {
            HttpResponse<String> after = post("admin_get", body(kv[0], null));
            assertEquals(200, after.statusCode(), "重启后 " + kv[0] + " admin_get 应 200");
            assertEquals(kv[1], JSON.readTree(after.body()).asText(),
                    "FR-02：重启后新增键 " + kv[0] + " 必须从 KV 恢复为 \"" + kv[1] + "\"");
        }
    }

    // ============================================================ F102-17

    @Test
    @Order(17)
    @Tag("story-feat-102-17")
    @Story("FEAT-102.F102-17: access_tracker.flush_interval 非法值（<=0/非数字）设置不报错、原样存储、"
            + "SUT 存活（消费侧回落缺省/夹紧 1s 为 daemon 时序行为）")
    @DisplayName("F102-17 flush_interval 非法值语义")
    void f10217_flushIntervalInvalidValueSemantics() throws Exception {
        // 非法值 1：<=0（消费侧 max(·, 1.0) 夹紧）
        HttpResponse<String> setZero = post("admin_set", body("access_tracker.flush_interval", "0"));
        assertEquals(200, setZero.statusCode(),
                "非法值 \"0\" 应可设置（消费侧回落/夹紧）— body=" + setZero.body());
        HttpResponse<String> getZero = post("admin_get", body("access_tracker.flush_interval", null));
        assertEquals("0", JSON.readTree(getZero.body()).asText(),
                "非法值应原样存储（键值层面不做校验拒绝）");

        // 非法值 2：非数字（消费侧 float() 异常 → 回落缺省 60）
        HttpResponse<String> setAbc = post("admin_set", body("access_tracker.flush_interval", "abc"));
        assertEquals(200, setAbc.statusCode(),
                "非数字值应可设置（消费侧回落缺省）— body=" + setAbc.body());
        HttpResponse<String> getAbc = post("admin_get", body("access_tracker.flush_interval", null));
        assertEquals("abc", JSON.readTree(getAbc.body()).asText(),
                "非数字值应原样存储");

        // SUT 存活：非法值不得令 daemon 崩溃或服务不可用
        HttpResponse<String> health = http.send(
                HttpRequest.newBuilder().uri(URI.create(base + "/healthz"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(), "非法值设置后 SUT 必须仍健康（消费侧异常回落）");

        // 恢复合法值，避免污染后续用例
        assertEquals(200, post("admin_set", body("access_tracker.flush_interval", "60")).statusCode());
    }

    // ============================================================ F102-18

    @Test
    @Order(18)
    @Tag("story-feat-102-18")
    @Story("FEAT-102.F102-18: policy_history 支持 space 参数查空间级变更记录（含 scope/actor 字段），"
            + "空间策略 set_policy 后缓存失效立即可见")
    @DisplayName("F102-18 空间级变更历史查询与缓存失效")
    void f10218_spaceScopedHistoryAndCacheInvalidation() throws Exception {
        createSpace("local", "f102-hist-space");

        HttpResponse<String> set = post("admin_set",
                bodyWithSpace("retrieval.min_score", "0.35", "local", "f102-hist-space"));
        assertEquals(200, set.statusCode(),
                "空间级 admin_set 应 200 — body=" + set.body());

        // 空间级变更历史（FR-05：按作用域查询）
        HttpResponse<String> history = post("policy_history",
                bodyWithSpace("retrieval.min_score", null, "local", "f102-hist-space"));
        assertEquals(200, history.statusCode(),
                "policy_history 带 space 参数应 200 — body=" + history.body());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && !records.isEmpty(),
                "空间级变更应落记录且可按作用域查询 — body=" + history.body());
        JsonNode latest = records.get(0);
        assertEquals("retrieval.min_score", latest.path("key").asText(),
                "空间级记录键形应为全局键（空间级映射回全局键名）");
        assertEquals("0.35", latest.path("new_value").asText(), "记录 new_value 应为 \"0.35\"");
        assertEquals("local", latest.path("scope").path("org").asText(),
                "记录 scope.org 应为空间组织 — record=" + latest);
        assertEquals("f102-hist-space", latest.path("scope").path("space").asText(),
                "记录 scope.space 应为空间名 — record=" + latest);
        assertFalse(latest.path("actor_org").asText("").isEmpty(),
                "FR-05/§3.6：变更记录应含非空操作者标识 actor_org — record=" + latest);
        assertFalse(latest.path("actor_user").asText("").isEmpty(),
                "FR-05/§3.6：变更记录应含非空操作者标识 actor_user — record=" + latest);

        // 空间策略缓存失效：set_policy 后再次写入立即可见（§3.6 策略注入查找链）
        HttpResponse<String> setAgain = post("admin_set",
                bodyWithSpace("retrieval.min_score", "0.4", "local", "f102-hist-space"));
        assertEquals(200, setAgain.statusCode(), "第二次空间级 set 应 200 — body=" + setAgain.body());
        JsonNode policy = getSpacePolicy("local", "f102-hist-space");
        assertEquals("0.4", policy.path("pipeline_profiles").path("retrieval_min_score").asText(),
                "set_policy 后空间策略缓存应失效，get_space_policy 立即返回新值 \"0.4\" — policy=" + policy);
    }

    // ============================================================ F102-19

    @Test
    @Order(19)
    @Tag("story-feat-102-19")
    @Story("FEAT-102.F102-19: policy_rollback 支持 space 参数回滚空间级策略键，回滚本身落空间级新记录")
    @DisplayName("F102-19 空间级单键回滚")
    void f10219_spaceScopedRollback() throws Exception {
        createSpace("local", "f102-rollback-space");

        assertEquals(200, post("admin_set",
                bodyWithSpace("retrieval.over_fetch_floor", "70", "local", "f102-rollback-space"))
                .statusCode(), "空间级前置变更1应成功");
        assertEquals(200, post("admin_set",
                bodyWithSpace("retrieval.over_fetch_floor", "90", "local", "f102-rollback-space"))
                .statusCode(), "空间级前置变更2应成功");

        HttpResponse<String> rollback = post("policy_rollback",
                bodyWithSpace("retrieval.over_fetch_floor", null, "local", "f102-rollback-space"));
        assertEquals(200, rollback.statusCode(),
                "FR-06：policy_rollback 带 space 参数应 200 — body=" + rollback.body());

        JsonNode policy = getSpacePolicy("local", "f102-rollback-space");
        assertEquals("70", policy.path("pipeline_profiles").path("retrieval_over_fetch_floor").asText(),
                "AC-03：空间级回滚应恢复最近一条记录的旧值 \"70\" — policy=" + policy);

        HttpResponse<String> history = post("policy_history",
                bodyWithSpace("retrieval.over_fetch_floor", null, "local", "f102-rollback-space"));
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && !records.isEmpty(), "空间级 history 应非空");
        assertEquals("70", records.get(0).path("new_value").asText(),
                "空间级回滚写回本身应落新变更记录且为最新一条（new_value=\"70\"）");
    }

    // ============================================================ F102-20

    @Test
    @Order(20)
    @Tag("story-feat-102-20")
    @Story("FEAT-102.F102-20: policy_snapshot_create/diff/rollback 支持 space 参数——空间快照 "
            + "changed 差异可对比、rollback 逐键写回空间策略")
    @DisplayName("F102-20 空间级快照三件套")
    void f10220_spaceScopedSnapshotTrio() throws Exception {
        createSpace("local", "f102-snap-space");

        assertEquals(200, post("admin_set",
                bodyWithSpace("retrieval.rerank_max", "50", "local", "f102-snap-space"))
                .statusCode(), "空间级基线变更应成功");

        HttpResponse<String> create = post("policy_snapshot_create",
                bodyWithSpace(null, null, "local", "f102-snap-space"));
        assertEquals(200, create.statusCode(),
                "FR-07：空间快照创建应 200 — body=" + create.body());
        JsonNode created = JSON.readTree(create.body());
        assertTrue(created.isTextual() && !created.asText().isEmpty(),
                "空间快照创建应返回非空快照标识 — body=" + create.body());
        String snapshotId = created.asText();

        assertEquals(200, post("admin_set",
                bodyWithSpace("retrieval.rerank_max", "55", "local", "f102-snap-space"))
                .statusCode(), "制造差异的空间级变更应成功");

        ObjectNode diffReq = JSON.createObjectNode();
        diffReq.put("snapshot_id", snapshotId);
        HttpResponse<String> diff = post("policy_snapshot_diff", diffReq);
        assertEquals(200, diff.statusCode(), "空间快照 diff 应 200 — body=" + diff.body());
        JsonNode diffBody = JSON.readTree(diff.body());
        assertEquals("55", diffBody.path("changed").path("pipeline_profiles.retrieval_rerank_max").asText(),
                "diff.changed 应含空间展平键且值为当前值 \"55\" — body=" + diff.body());
        assertFalse(diffBody.path("added").has("pipeline_profiles.retrieval_rerank_max"),
                "已存在的键不应出现在 added — body=" + diff.body());

        HttpResponse<String> rollback = post("policy_snapshot_rollback", diffReq);
        assertEquals(200, rollback.statusCode(),
                "FR-07/A2：空间快照回滚应 200 — body=" + rollback.body());
        assertTrue(rollback.body().contains("pipeline_profiles.retrieval_rerank_max"),
                "响应应返回受影响键列表且含空间展平键 — body=" + rollback.body());

        JsonNode policy = getSpacePolicy("local", "f102-snap-space");
        assertEquals("50", policy.path("pipeline_profiles").path("retrieval_rerank_max").asText(),
                "空间快照回滚后 retrieval_rerank_max 应回到快照值 \"50\" — policy=" + policy);

        HttpResponse<String> history = post("policy_history",
                bodyWithSpace("retrieval.rerank_max", null, "local", "f102-snap-space"));
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && !records.isEmpty(), "空间级 history 应非空");
        assertEquals("50", records.get(0).path("new_value").asText(),
                "空间快照回滚的写回应落一条变更记录且为最新一条（new_value=\"50\"）");
    }

    // ============================================================ F102-21

    @Test
    @Order(21)
    @Tag("story-feat-102-21")
    @Story("FEAT-102.F102-21: policy_snapshot_diff 的 added/removed 完整场景——快照后新增键入 added，"
            + "快照后删除键（set_space_policy 清空）入 removed")
    @DisplayName("F102-21 快照差异 added/removed 完整场景")
    void f10221_snapshotDiffAddedAndRemoved() throws Exception {
        createSpace("local", "f102-diff-space");

        // added：空 profiles 时不快照 → 写入新子键 → diff added 含该键
        HttpResponse<String> createS0 = post("policy_snapshot_create",
                bodyWithSpace(null, null, "local", "f102-diff-space"));
        assertEquals(200, createS0.statusCode(), "added 场景快照创建应 200 — body=" + createS0.body());
        String snapshotS0 = JSON.readTree(createS0.body()).asText();

        assertEquals(200, post("admin_set",
                bodyWithSpace("retrieval.rerank_max", "55", "local", "f102-diff-space"))
                .statusCode(), "added 场景写入应成功");

        HttpResponse<String> diffS0 = post("policy_snapshot_diff",
                JSON.createObjectNode().put("snapshot_id", snapshotS0));
        JsonNode added = JSON.readTree(diffS0.body()).path("added");
        assertEquals("55", added.path("pipeline_profiles.retrieval_rerank_max").asText(),
                "FR-07：快照后新增的键应出现在 added 且值为当前值 — body=" + diffS0.body());
        assertFalse(JSON.readTree(diffS0.body()).path("changed")
                .has("pipeline_profiles.retrieval_rerank_max"),
                "新增键不应出现在 changed — body=" + diffS0.body());

        // removed：快照含键 → set_space_policy 清空 pipeline_profiles → diff removed 含该键
        HttpResponse<String> createS1 = post("policy_snapshot_create",
                bodyWithSpace(null, null, "local", "f102-diff-space"));
        assertEquals(200, createS1.statusCode(), "removed 场景快照创建应 200 — body=" + createS1.body());
        String snapshotS1 = JSON.readTree(createS1.body()).asText();

        JsonNode policy = getSpacePolicy("local", "f102-diff-space");
        ((ObjectNode) policy).putObject("pipeline_profiles");
        ObjectNode setPolicyReq = JSON.createObjectNode();
        setPolicyReq.put("org", "local");
        setPolicyReq.put("space", "f102-diff-space");
        setPolicyReq.set("policy", policy);
        HttpResponse<String> setPolicy = post("set_space_policy", setPolicyReq);
        assertEquals(200, setPolicy.statusCode(),
                "前置：set_space_policy 清空 pipeline_profiles 应 200 — body=" + setPolicy.body());

        HttpResponse<String> diffS1 = post("policy_snapshot_diff",
                JSON.createObjectNode().put("snapshot_id", snapshotS1));
        JsonNode removed = JSON.readTree(diffS1.body()).path("removed");
        assertEquals("55", removed.path("pipeline_profiles.retrieval_rerank_max").asText(),
                "FR-07：快照后删除的键应出现在 removed 且值为快照值 — body=" + diffS1.body());
        assertFalse(JSON.readTree(diffS1.body()).path("added")
                .has("pipeline_profiles.retrieval_rerank_max"),
                "已删除键不应出现在 added — body=" + diffS1.body());
    }

    // ============================================================ F102-22

    @Test
    @Order(22)
    @Tag("story-feat-102-22")
    @Story("FEAT-102.F102-22: 空间级键映射补全——evolver.dedup_high_similarity/rerank.enabled/"
            + "lifecycle.forgotten_retention_days 经 admin_set space 写入对应空间策略子键")
    @DisplayName("F102-22 空间级键映射补全（pipeline_profiles/retention）")
    void f10222_spaceKeyMappingCompleteness() throws Exception {
        createSpace("local", "f102-profiles-space");

        // evolver.dedup_high_similarity → pipeline_profiles.evolver_dedup_high_similarity
        assertEquals(200, post("admin_set", bodyWithSpace(
                "evolver.dedup_high_similarity", "0.8", "local", "f102-profiles-space")).statusCode(),
                "空间级 evolver.dedup_high_similarity 写入应成功");
        JsonNode policy = getSpacePolicy("local", "f102-profiles-space");
        assertEquals("0.8", policy.path("pipeline_profiles").path("evolver_dedup_high_similarity").asText(),
                "FR-08：evolver.dedup_high_similarity 应映射到 pipeline_profiles.evolver_dedup_high_similarity"
                        + " — policy=" + policy);

        // rerank.enabled → pipeline_profiles.rerank_enabled
        assertEquals(200, post("admin_set", bodyWithSpace(
                "rerank.enabled", "false", "local", "f102-profiles-space")).statusCode(),
                "空间级 rerank.enabled 写入应成功");
        policy = getSpacePolicy("local", "f102-profiles-space");
        assertEquals("false", policy.path("pipeline_profiles").path("rerank_enabled").asText(),
                "FR-08：rerank.enabled 应映射到 pipeline_profiles.rerank_enabled — policy=" + policy);

        // lifecycle.forgotten_retention_days → retention.forgotten_retention_days
        assertEquals(200, post("admin_set", bodyWithSpace(
                "lifecycle.forgotten_retention_days", "45", "local", "f102-profiles-space")).statusCode(),
                "空间级 lifecycle.forgotten_retention_days 写入应成功");
        policy = getSpacePolicy("local", "f102-profiles-space");
        assertEquals("45", policy.path("retention").path("forgotten_retention_days").asText(),
                "FR-08：lifecycle.forgotten_retention_days 应映射到 retention.forgotten_retention_days"
                        + " — policy=" + policy);
    }

    // ============================================================ F102-23

    @Test
    @Order(23)
    @Tag("story-feat-102-23")
    @Story("FEAT-102.F102-23: 其余保留活键 scope.require_space/hierarchy.enabled/lifecycle.superseded.target"
            + "跨重启持久化，不回退装配期默认")
    @DisplayName("F102-23 保留活键跨重启持久化（扩展口径）")
    void f10223_remainingRetainedKeysPersistAcrossRestart() throws Exception {
        String[][] keysAndValues = {
            {"scope.require_space", "true"},
            {"hierarchy.enabled", "true"},
            {"lifecycle.superseded.target", "archived"},
        };
        for (String[] kv : keysAndValues) {
            HttpResponse<String> set = post("admin_set", body(kv[0], kv[1]));
            assertEquals(200, set.statusCode(), "保留活键 " + kv[0] + " 应可运行时设置 — body=" + set.body());
        }

        restartSut();

        for (String[] kv : keysAndValues) {
            HttpResponse<String> after = post("admin_get", body(kv[0], null));
            assertEquals(200, after.statusCode(), "重启后 " + kv[0] + " admin_get 应 200");
            assertEquals(kv[1], JSON.readTree(after.body()).asText(),
                    "FR-02/§3.6 活键保留持久化：重启后 " + kv[0] + " 必须从 KV 恢复为 \"" + kv[1] + "\"");
        }
    }

    // ============================================================ F102-24

    @Test
    @Order(24)
    @Tag("story-feat-102-24")
    @Story("FEAT-102.F102-24: 设置失败（未知键拒绝）不落变更记录——policy_history 对该键返回空")
    @DisplayName("F102-24 设置失败不落变更记录")
    void f10224_failedSetWritesNoChangeRecord() throws Exception {
        HttpResponse<String> set = post("admin_set", body("not.exist.key", "x"));
        assertEquals(400, set.statusCode(), "前置：未知键 set 应被拒绝 — body=" + set.body());

        HttpResponse<String> history = post("policy_history", body("not.exist.key", null));
        assertEquals(200, history.statusCode(),
                "未落记录的键查询应 200 而非报错 — body=" + history.body());
        JsonNode records = JSON.readTree(history.body());
        assertTrue(records.isArray() && records.isEmpty(),
                "§3.6 审计分工：设置失败时不得落变更记录 — body=" + history.body());
    }
}
