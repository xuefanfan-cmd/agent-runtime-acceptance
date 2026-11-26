/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa2_session_persistence.fixture;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared test helpers for all FEAT-089 test classes.
 *
 * <p>Provides:
 * <ul>
 *   <li>openGauss connection constants</li>
 *   <li>LLM env assembly</li>
 *   <li>SUT stack builder methods (mock/l2/l1 with openGauss config)</li>
 *   <li>Prerequisite guards (jar present, openGauss reachable, LLM env)</li>
 *   <li>DB corruption helper</li>
 * </ul>
 */
public final class Edpa2SutHelper {

    // openGauss connection constants
    public static final String OG_JDBC_URL = "jdbc:opengauss://localhost:7654/postgres";
    public static final String OG_USERNAME = "omm";
    public static final String OG_PASSWORD = "Test@12345";
    public static final String OG_SCHEMA = "moa";
    public static final String L1_AGENT_ID = "moa-demo-l1";
    public static final String L2_AGENT_ID = "moa-demo-l2";

    // Timeouts
    public static final java.time.Duration INVOKE_TIMEOUT = java.time.Duration.ofSeconds(120);
    public static final java.time.Duration RECOVERY_TIMEOUT = java.time.Duration.ofSeconds(120);

    private Edpa2SutHelper() {}

    /**
     * Base environment variables required by all SUT processes.
     * Includes TMP/TEMP (Tomcat needs a writable temp dir), JAVA_HOME, PATH, LANG.
     */
    private static Map<String, String> baseEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        String tmpDir = System.getProperty("java.io.tmpdir", "C:/Users/" + System.getenv("USERNAME") + "/AppData/Local/Temp");
        env.put("TMP", tmpDir);
        env.put("TEMP", tmpDir);
        env.put("TMPDIR", tmpDir);
        String javaHome = System.getProperty("java.home", System.getenv("JAVA_HOME"));
        if (javaHome != null) env.put("JAVA_HOME", javaHome);
        String path = System.getenv("PATH");
        if (path != null) env.put("PATH", path);
        env.put("LANG", "en_US.UTF-8");
        // EDPA2.0 demo skill assets — required by LocalSkillDelegate at startup
        String assetRoot = System.getProperty("moa.demo.asset.root",
                "D:/openjiuwen/agent-solution/common/agents/moa-agent-demo/assets/tourism/skills");
        env.put("MOA_DEMO_ASSET_ROOT", assetRoot);
        return env;
    }

    /**
     * Merges LLM env with base env (base first, then LLM overrides).
     * Public so test classes can use it for custom L1 builder configurations.
     */
    public static Map<String, String> withBaseEnv(Map<String, String> llm) {
        Map<String, String> env = baseEnv();
        env.putAll(llm);
        return env;
    }

    // ---------- SUT builders ----------

    public static Edpa2DemoLauncher buildMock(Map<String, String> llm) {
        return Edpa2DemoLauncher.mockAgent().envAll(withBaseEnv(llm)).build();
    }

    public static Edpa2DemoLauncher buildL2(Map<String, String> llm) {
        return Edpa2DemoLauncher.l2Agent()
                .envAll(withBaseEnv(llm))
                .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + OG_JDBC_URL)
                .arg("--openjiuwen.service.middleware.opengauss.default.username=" + OG_USERNAME)
                .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=" + OG_PASSWORD)
                .arg("--openjiuwen.service.middleware.opengauss.default.schema=" + OG_SCHEMA)
                .build();
    }

    public static Edpa2DemoLauncher buildL1(Map<String, String> llm) {
        return Edpa2DemoLauncher.l1Agent()
                .envAll(withBaseEnv(llm))
                .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + OG_JDBC_URL)
                .arg("--openjiuwen.service.middleware.opengauss.default.username=" + OG_USERNAME)
                .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=" + OG_PASSWORD)
                .arg("--openjiuwen.service.middleware.opengauss.default.schema=" + OG_SCHEMA)
                .build();
    }

    /**
     * Builds L1 with custom openGauss args (for fault injection scenarios).
     */
    public static Edpa2DemoLauncher buildL1WithCustomOg(Map<String, String> llm, String ogUrl) {
        return Edpa2DemoLauncher.l1Agent()
                .envAll(withBaseEnv(llm))
                .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + ogUrl)
                .arg("--openjiuwen.service.middleware.opengauss.default.username=" + OG_USERNAME)
                .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=" + OG_PASSWORD)
                .arg("--openjiuwen.service.middleware.opengauss.default.schema=" + OG_SCHEMA)
                .build();
    }

    // ---------- prerequisite guards ----------

    public static void assumePrerequisites() {
        assumeDemoJarPresent();
        assumeOpenGaussReachable();
    }

    public static void assumeDemoJarPresent() {
        String override = System.getProperty(Edpa2DemoLauncher.DEMO_JAR_PROP);
        Path jar = override != null && !override.isBlank()
                ? Path.of(override)
                : Edpa2DemoLauncher.DEFAULT_DEMO_JAR;
        Assumptions.assumeTrue(Files.isRegularFile(jar),
                "moa-agent-demo jar not found at " + jar
                        + " — set -D" + Edpa2DemoLauncher.DEMO_JAR_PROP + " — INCONCLUSIVE");
    }

    public static void assumeOpenGaussReachable() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 7654), 1000);
        } catch (java.io.IOException unreachable) {
            Assumptions.assumeTrue(false,
                    "openGauss not reachable at localhost:7654 — INCONCLUSIVE");
        }
    }

    public static Map<String, String> requireLlmEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LLM_PROVIDER", requireEnv("LLM_PROVIDER"));
        env.put("LLM_API_KEY", requireEnv("LLM_API_KEY"));
        env.put("LLM_API_BASE", requireEnv("LLM_API_BASE"));
        env.put("LLM_MODEL", requireEnv("LLM_MODEL"));
        return env;
    }

    public static String requireEnv(String key) {
        String v = System.getenv(key);
        Assumptions.assumeTrue(v != null && !v.isBlank(),
                "env " + key + " not set — INCONCLUSIVE");
        return v;
    }

    // ---------- DB helpers ----------

    /**
     * Corrupts task_json in DB to simulate retrieval failure.
     */
    public static void corruptTaskJson(String agentId, String contextId) throws Exception {
        try (Connection conn = DriverManager.getConnection(OG_JDBC_URL, OG_USERNAME, OG_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE \"moa\".moa_a2a_task SET task_json = ? WHERE agent_id = ? AND context_id = ?")) {
            ps.setString(1, "{THIS IS NOT VALID JSON <<< CORRUPTED >>>");
            ps.setString(2, agentId);
            ps.setString(3, contextId);
            ps.executeUpdate();
        }
    }

    /**
     * Reads task_id and task_json from DB for a given agent+contextId.
     *
     * @return String[2] = {taskId, taskJson}, or null if not found
     */
    public static String[] getTaskRow(String agentId, String contextId) throws Exception {
        try (Connection conn = DriverManager.getConnection(OG_JDBC_URL, OG_USERNAME, OG_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT task_id, task_json FROM \"moa\".moa_a2a_task WHERE agent_id = ? AND context_id = ?")) {
            ps.setString(1, agentId);
            ps.setString(2, contextId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new String[]{rs.getString(1), rs.getString(2)};
                }
                return null;
            }
        }
    }

    /**
     * Updates task_json in DB for a given agent+contextId with modified JSON.
     */
    public static void updateTaskJson(String agentId, String contextId, String newJson) throws Exception {
        try (Connection conn = DriverManager.getConnection(OG_JDBC_URL, OG_USERNAME, OG_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE \"moa\".moa_a2a_task SET task_json = ? WHERE agent_id = ? AND context_id = ?")) {
            ps.setString(1, newJson);
            ps.setString(2, agentId);
            ps.setString(3, contextId);
            ps.executeUpdate();
        }
    }

    /**
     * Deletes only one of the two checkpoint keys (half-record) for a given contextId prefix.
     */
    public static int deleteHalfKvRecords(String keyPrefix) throws Exception {
        try (Connection conn = DriverManager.getConnection(OG_JDBC_URL, OG_USERNAME, OG_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM \"moa\".moa_kv WHERE store_key LIKE ? ESCAPE '!' " +
                             "AND store_key NOT IN (SELECT store_key FROM \"moa\".moa_kv " +
                             "WHERE store_key LIKE ? ESCAPE '!' ORDER BY store_key LIMIT 1)")) {
            String pattern = escapeLike(keyPrefix) + "%";
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            return ps.executeUpdate();
        }
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * Drains SUT log buffers.
     */
    public static void drainLogs() throws InterruptedException {
        Thread.sleep(2_000);
    }
}
