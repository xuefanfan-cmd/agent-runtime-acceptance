/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.moa_session_persistence.fixture;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Direct JDBC probe into the openGauss backend used by FEAT-089 SIT.
 *
 * <p>This probe connects to the same openGauss instance that the moa-agent-demo
 * uses (configured via {@code openjiuwen.service.middleware.opengauss}), and
 * queries the two persistence tables directly:
 * <ul>
 *   <li>{@code moa.moa_kv} — checkpoint KV store (store_key, value_type, payload, updated_at)</li>
 *   <li>{@code moa.moa_a2a_task} — A2A task store (agent_id, task_id, context_id, task_json, updated_at)</li>
 * </ul>
 *
 * <p>Used by SIT to:
 * <ul>
 *   <li>Verify that context was persisted after a session pause/completion</li>
 *   <li>Verify that context was updated (overwritten) after a second pause</li>
 *   <li>Verify that no historical versions accumulate (single row per key)</li>
 *   <li>Verify isolation — records for one context_id are not visible to another</li>
 *   <li>Clean up tables between test runs</li>
 * </ul>
 *
 * <p>JDBC driver is expected on the test classpath (declared in pom.xml).
 */
public final class OpenGaussProbe implements AutoCloseable {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String schema;

    private Connection conn;

    public OpenGaussProbe(String jdbcUrl, String username, String password, String schema) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.schema = (schema != null && !schema.isBlank()) ? schema : "moa";
    }

    public OpenGaussProbe connect() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            return this;
        }
        conn = DriverManager.getConnection(jdbcUrl, username, password);
        return this;
    }

    /**
     * Counts rows in {@code moa_kv} for keys matching a prefix.
     */
    public int countKvByPrefix(String keyPrefix) throws SQLException {
        ensureConnected();
        String sql = "SELECT count(*) FROM " + quote(schema) + ".moa_kv WHERE store_key LIKE ? ESCAPE '!'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, escapeLike(keyPrefix) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Counts rows in {@code moa_kv} for an exact key.
     */
    public int countKvByKey(String key) throws SQLException {
        ensureConnected();
        String sql = "SELECT count(*) FROM " + quote(schema) + ".moa_kv WHERE store_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Counts rows in {@code moa_a2a_task} for a given agent_id and context_id.
     */
    public int countTasksByContext(String agentId, String contextId) throws SQLException {
        ensureConnected();
        String sql = "SELECT count(*) FROM " + quote(schema) + ".moa_a2a_task WHERE agent_id = ? AND context_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, contextId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Counts rows in {@code moa_a2a_task} for a given agent_id (all contexts).
     */
    public int countTasksByAgent(String agentId) throws SQLException {
        ensureConnected();
        String sql = "SELECT count(*) FROM " + quote(schema) + ".moa_a2a_task WHERE agent_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Retrieves the {@code updated_at} value for a specific KV key.
     * Returns -1 if the key does not exist.
     */
    public long getKvUpdatedAt(String key) throws SQLException {
        ensureConnected();
        String sql = "SELECT updated_at FROM " + quote(schema) + ".moa_kv WHERE store_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return -1L;
            }
        }
    }

    /**
     * Retrieves the {@code updated_at} value for the latest task of an agent+context.
     * Returns -1 if no task exists.
     */
    public long getLatestTaskUpdatedAt(String agentId, String contextId) throws SQLException {
        ensureConnected();
        String sql = "SELECT max(updated_at) FROM " + quote(schema)
                + ".moa_a2a_task WHERE agent_id = ? AND context_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, contextId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long val = rs.getLong(1);
                return rs.wasNull() ? -1L : val;
            }
        }
    }

    /**
     * Deletes a single KV row by exact store_key. Returns true if a row was deleted.
     */
    public boolean deleteKvByKey(String key) throws SQLException {
        ensureConnected();
        String sql = "DELETE FROM " + quote(schema) + ".moa_kv WHERE store_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Updates the payload of a single KV row by exact store_key.
     */
    public boolean updateKvPayload(String key, byte[] newPayload) throws SQLException {
        ensureConnected();
        String sql = "UPDATE " + quote(schema) + ".moa_kv SET payload = ? WHERE store_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, newPayload);
            ps.setString(2, key);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Lists all KV keys matching a prefix — useful for debugging isolation.
     */
    public List<String> listKvKeysByPrefix(String keyPrefix) throws SQLException {
        ensureConnected();
        String sql = "SELECT store_key FROM " + quote(schema)
                + ".moa_kv WHERE store_key LIKE ? ESCAPE '!' ORDER BY store_key";
        List<String> keys = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, escapeLike(keyPrefix) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
        }
        return keys;
    }

    /**
     * Corrupts the dump_type value of a KV checkpoint by contextId.
     * Finds the *_dump_type key and replaces its payload with the given type string.
     */
    public boolean corruptKvDumpType(String contextId, String fakeType) throws SQLException {
        ensureConnected();
        List<String> keys = listKvKeysByPrefix(contextId);
        String dumpTypeKey = null;
        for (String k : keys) {
            if (k.contains("dump_type")) {
                dumpTypeKey = k;
                break;
            }
        }
        if (dumpTypeKey == null) {
            return false;
        }
        return updateKvPayload(dumpTypeKey, fakeType.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Replaces the agent_state_blobs payload with a Java-serialized empty HashMap.
     * An empty Map is a structurally valid state that should be accepted by restoreState.
     */
    public boolean replaceKvBlobWithEmptyMap(String contextId) throws SQLException {
        ensureConnected();
        List<String> keys = listKvKeysByPrefix(contextId);
        String blobKey = null;
        for (String k : keys) {
            if (k.contains("agent_state_blobs") && !k.contains("dump_type")) {
                blobKey = k;
                break;
            }
        }
        if (blobKey == null) {
            return false;
        }
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
            oos.writeObject(new java.util.HashMap<>());
            oos.flush();
            return updateKvPayload(blobKey, bos.toByteArray());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Replaces the agent_state_blobs payload with a Java-serialized String (not a Map).
     * A non-Map object should be rejected by restoreState per the verification matrix.
     */
    public boolean replaceKvBlobWithNonMap(String contextId) throws SQLException {
        ensureConnected();
        List<String> keys = listKvKeysByPrefix(contextId);
        String blobKey = null;
        for (String k : keys) {
            if (k.contains("agent_state_blobs") && !k.contains("dump_type")) {
                blobKey = k;
                break;
            }
        }
        if (blobKey == null) {
            return false;
        }
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
            oos.writeObject("this is a string, not a Map");
            oos.flush();
            return updateKvPayload(blobKey, bos.toByteArray());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
    public List<String> listContextIdsByAgent(String agentId) throws SQLException {
        ensureConnected();
        String sql = "SELECT DISTINCT context_id FROM " + quote(schema)
                + ".moa_a2a_task WHERE agent_id = ? ORDER BY context_id";
        List<String> ctxIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ctxIds.add(rs.getString(1));
                }
            }
        }
        return ctxIds;
    }

    /**
     * Retrieves the {@code task_json} for a specific agent_id + task_id.
     */
    public String getTaskJson(String agentId, String taskId) throws SQLException {
        ensureConnected();
        String sql = "SELECT task_json FROM " + quote(schema)
                + ".moa_a2a_task WHERE agent_id = ? AND task_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return null;
            }
        }
    }

    /**
     * Deletes all KV rows matching a prefix. Used for cleanup.
     */
    public int deleteKvByPrefix(String keyPrefix) throws SQLException {
        ensureConnected();
        String sql = "DELETE FROM " + quote(schema) + ".moa_kv WHERE store_key LIKE ? ESCAPE '!'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, escapeLike(keyPrefix) + "%");
            return ps.executeUpdate();
        }
    }

    /**
     * Deletes all task rows for a given agent_id. Used for cleanup.
     */
    public int deleteTasksByAgent(String agentId) throws SQLException {
        ensureConnected();
        String sql = "DELETE FROM " + quote(schema) + ".moa_a2a_task WHERE agent_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            return ps.executeUpdate();
        }
    }

    /**
     * Truncates both tables. Use sparingly — prefer targeted cleanup.
     */
    public void truncateAll() throws SQLException {
        ensureConnected();
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("TRUNCATE TABLE " + quote(schema) + ".moa_kv");
            st.executeUpdate("TRUNCATE TABLE " + quote(schema) + ".moa_a2a_task");
        }
    }

    /**
     * Deletes all rows for a specific context_id across both tables.
     * For moa_kv, the contextId is typically embedded in the key.
     * For moa_a2a_task, it's a dedicated column.
     */
    public int deleteByContextId(String agentId, String contextId) throws SQLException {
        ensureConnected();
        int deleted = 0;
        // Delete tasks
        String taskSql = "DELETE FROM " + quote(schema)
                + ".moa_a2a_task WHERE agent_id = ? AND context_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(taskSql)) {
            ps.setString(1, agentId);
            ps.setString(2, contextId);
            deleted += ps.executeUpdate();
        }
        // Delete KV rows with contextId in the key
        String kvSql = "DELETE FROM " + quote(schema)
                + ".moa_kv WHERE store_key LIKE ? ESCAPE '!'";
        try (PreparedStatement ps = conn.prepareStatement(kvSql)) {
            ps.setString(1, "%" + escapeLike(contextId) + "%");
            deleted += ps.executeUpdate();
        }
        return deleted;
    }

    /**
     * Checks whether any rows exist at all in moa_kv.
     */
    public boolean hasKvRows() throws SQLException {
        ensureConnected();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM " + quote(schema) + ".moa_kv LIMIT 1")) {
            return rs.next();
        }
    }

    /**
     * Checks whether any rows exist at all in moa_a2a_task.
     */
    public boolean hasTaskRows() throws SQLException {
        ensureConnected();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM " + quote(schema) + ".moa_a2a_task LIMIT 1")) {
            return rs.next();
        }
    }

    private void ensureConnected() throws SQLException {
        if (conn == null || conn.isClosed()) {
            throw new SQLException("OpenGaussProbe not connected — call connect() first");
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    @Override
    public void close() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // best-effort
            }
        }
    }
}
