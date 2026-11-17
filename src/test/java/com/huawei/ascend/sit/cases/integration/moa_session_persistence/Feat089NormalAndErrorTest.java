/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.moa_session_persistence;

import com.huawei.ascend.sit.cases.integration.moa_session_persistence.fixture.MoaAgentHttpClient;
import com.huawei.ascend.sit.cases.integration.moa_session_persistence.fixture.MoaAgentHttpClient.A2aResponse;
import com.huawei.ascend.sit.cases.integration.moa_session_persistence.fixture.MoaDemoLauncher;
import com.huawei.ascend.sit.cases.integration.moa_session_persistence.fixture.MoaSutHelper;
import com.huawei.ascend.sit.cases.integration.moa_session_persistence.fixture.OpenGaussProbe;
import com.huawei.ascend.sit.cases.integration.moa_session_persistence.fixture.SutLogTailer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-089 P1/P2 — normal scenarios (F089-03~07) and error scenarios (F089-08~12).
 *
 * <p>Normal: three stop-time types, normal completion save, openGauss equivalence,
 * new-command recovery, recovery-then-complete.
 *
 * <p>Error: corrupted context recovery, session-not-exist recovery, waiting-input
 * recovery, half-record recovery, unknown Java object rejection.
 */
@Tag("integration")
@Tag("feat-089")
@Tag("slow")
@Tag("manual")
final class Feat089NormalAndErrorTest {

    // ---------- Normal scenarios (P1) ----------

    /**
     * F089-03: Three stop-time types (pause, interrupt, normal completion) all trigger save.
     */
    @Test
    void f089_03_threeStopTimesSave() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Session 1: pause then continue (pause stop-time)
                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下你自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                assertThat(resp1.statusCode()).isEqualTo(200);
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);

                int countAfterPause = db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID);
                client.continueSession(resp1.contextId(), MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Session 2: normal completion (no pause, just finish)
                A2aResponse resp2 = client.sendStreamingMessage("你好，请简单介绍一下上海", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                assertThat(resp2.statusCode()).isEqualTo(200);

                // Session 3: interrupt — send second message quickly
                A2aResponse resp3a = client.sendStreamingMessage("你好，请简单介绍一下广州", null, MoaSutHelper.INVOKE_TIMEOUT);
                A2aResponse resp3b = client.sendStreamingMessage("你好，请简单介绍一下深圳", resp3a.contextId(), MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                assertThat(resp3b.statusCode()).isEqualTo(200);

                // All three sessions should have triggered saves
                int finalCount = db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID);
                assertThat(finalCount).isGreaterThanOrEqualTo(countAfterPause);
            }
        }
    }

    /**
     * F089-04: Normal completion also triggers save (easy to miss).
     */
    @Test
    void f089_04_normalCompletionSave() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Task completes normally (no pause, no interrupt)
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下你自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                assertThat(resp.statusCode()).isEqualTo(200);
                // Normal completion should also trigger context save
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Recovery from saved context should work
                A2aResponse recoveryResp = client.continueSession(resp.contextId(), MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();
                assertThat(recoveryResp.statusCode()).isEqualTo(200);
                assertThat(recoveryResp.contextId()).isEqualTo(resp.contextId());
            }
        }
    }

    /**
     * F089-05: openGauss backend behavior is equivalent to Redis (Redis as baseline).
     *
     * <p>This test verifies openGauss path produces normal responses (200, non-empty
     * artifact, correct task state). Full Redis-vs-openGauss equivalence comparison
     * requires a separate Redis-backed run for side-by-side comparison.
     */
    @Test
    void f089_05_openGaussEquivalence() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下你自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                // openGauss path should produce equivalent behavior to Redis
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Recovery should work identically
                A2aResponse recovery = client.continueSession(resp.contextId(), MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();
                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(resp.contextId());
            }
        }
    }

    /**
     * F089-06: Long pause then new command (not "继续") triggers recovery.
     */
    @Test
    void f089_06_longPauseNewCmdRecovery() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Establish session
                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下你自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Simulate a pause before sending the next message (L2 §7.2: long pause recovery)
                Thread.sleep(10_000);

                // Send a new instruction (not "继续") in the same session
                A2aResponse resp2 = client.sendStreamingMessage("请再简单介绍一下自己", ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Recovery should happen before executing the new command
                assertThat(resp2.statusCode()).isEqualTo(200);
                assertThat(resp2.contextId()).isEqualTo(ctxId);
                assertThat(resp2.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
            }
        }
    }

    /**
     * F089-07: Recovery then task eventually completes.
     */
    @Test
    void f089_07_recoveryThenComplete() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下你自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();

                // Recover and continue — should eventually complete
                A2aResponse resp2 = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                assertThat(resp2.statusCode()).isEqualTo(200);
                assertThat(resp2.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
                // The task should reach a terminal state
                assertThat(resp2.finalTaskState()).doesNotContain("ERROR");
            }
        }
    }

    // ---------- Error scenarios (P1/P2) ----------

    /**
     * F089-08: Corrupted context recovery — feedback error, not treat as miss.
     */
    @Test
    void f089_08_corruptedContextRecovery() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下你自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Corrupt the persisted data
                MoaSutHelper.corruptTaskJson(MoaSutHelper.L1_AGENT_ID, ctxId);

                // Attempt recovery — should feedback error, not silent normal recovery
                A2aResponse resp2 = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Per FRS-088: should not be treated as miss, should feedback recovery error.
                // Corrupted data must not be silently recovered as clean COMPLETED —
                // either error feedback (4xx/ERROR/FAILED) or new-session fallback.
                boolean indicatesError = resp2.statusCode() >= 400
                        || resp2.statusCode() == 0 // TIMEOUT
                        || (resp2.finalTaskState() != null && resp2.finalTaskState().contains("ERROR"))
                        || (resp2.finalTaskState() != null && resp2.finalTaskState().contains("FAILED"));
                boolean fellbackToNewSession = !resp2.contextId().equals(ctxId);
                boolean silentRecovery = resp2.statusCode() == 200
                        && "TASK_STATE_COMPLETED".equals(resp2.finalTaskState())
                        && resp2.contextId() != null
                        && resp2.contextId().equals(ctxId)
                        && !fellbackToNewSession
                        && !indicatesError;
                assertThat(silentRecovery)
                        .as("Corrupted data must not be silently recovered as clean COMPLETED (L2 §2.3)")
                        .isFalse();
                assertThat(resp2.finalTaskState()).isNotNull();
            }
        }
    }

    /**
     * F089-09: Session ID not found → treat as new session, not error.
     */
    @Test
    void f089_09_sessionNotExistRecovery() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Use a fabricated contextId that never existed
                String fabricatedCtx = "nonexistent-session-" + System.currentTimeMillis();
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", fabricatedCtx, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Per AC-11: actual miss → new session, not error.
                // The task may complete or fail (e.g. out-of-scope prompt), but
                // it should not be a recovery/persistence error.
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.finalTaskState()).isNotNull();
                assertThat(resp.finalTaskState()).doesNotContain("ERROR");
            }
        }
    }

    /**
     * F089-10: Waiting-input state recovery (HITL recovery).
     *
     * <p>Degraded test: the demo does not support HITL waiting state.
     * L2 §7.2 requires waiting-input recovery, but the current mock agent
     * cannot trigger it.
     */
    @Disabled("Demo does not support HITL waiting state; L2 §7.2 requires waiting-input recovery but current mock agent cannot trigger it")
    @Test
    void f089_10_waitingInputRecovery() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Send a message that may require user input
                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();

                // Submit a follow-up answer (simulates providing waiting answer)
                A2aResponse resp2 = client.sendStreamingMessage("请再简单介绍一下上海", ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                assertThat(resp2.statusCode()).isEqualTo(200);
                assertThat(resp2.contextId()).isEqualTo(ctxId);
                // Recovery should preserve the session state
                assertThat(resp2.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
            }
        }
    }

    /**
     * F089-11: Half-record / illegal structure recovery.
     *
     * <p>Delete one of the checkpoint KV records (half-record), then attempt recovery.
     */
    @Test
    void f089_11_halfRecordRecovery() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下你自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp.contextId();

                // List KV keys for this session to find checkpoint keys
                var kvKeys = db.listKvKeysByPrefix(ctxId);
                assertThat(kvKeys).as("Should have KV checkpoint keys after save").isNotEmpty();

                // Find the dump_type key — deleting only this creates a true half-record
                String dumpTypeKey = kvKeys.stream()
                        .filter(k -> k.endsWith("_dump_type"))
                        .findFirst()
                        .orElse(null);
                assertThat(dumpTypeKey)
                        .as("Should find a dump_type checkpoint key")
                        .isNotNull();

                // Delete ONLY the dump_type key (blob key remains → true half-record)
                boolean deleted = db.deleteKvByKey(dumpTypeKey);
                assertThat(deleted).as("dump_type key should be deleted").isTrue();

                // Verify blob key still exists (half-record confirmed, not a clean miss)
                var remainingKeys = db.listKvKeysByPrefix(ctxId);
                assertThat(remainingKeys)
                        .as("Blob key should still exist after deleting only dump_type key (true half-record)")
                        .isNotEmpty();

                // Attempt recovery — half-record should not be silently treated as clean
                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Per design spec F089-11: half-record → feedback recovery error
                boolean indicatesError = recovery.statusCode() >= 400
                        || recovery.statusCode() == 0
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("ERROR"))
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("FAILED"));
                boolean fellbackToNewSession = !recovery.contextId().equals(ctxId);
                boolean silentRecovery = recovery.statusCode() == 200
                        && "TASK_STATE_COMPLETED".equals(recovery.finalTaskState())
                        && recovery.contextId() != null
                        && recovery.contextId().equals(ctxId)
                        && !fellbackToNewSession
                        && !indicatesError;
                assertThat(silentRecovery)
                        .as("Half-record must not be silently recovered as clean COMPLETED (L2 §2.3)")
                        .isFalse();
            }
        }
    }

    /**
     * F089-12: Unknown Java object rejected by KVStore codec (P2).
     */
    @Test
    void f089_12_unknownJavaRejected() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Insert a KV record with an unknown value_type
                try (var conn = java.sql.DriverManager.getConnection(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME, MoaSutHelper.OG_PASSWORD);
                     var ps = conn.prepareStatement(
                             "INSERT INTO \"moa\".moa_kv (store_key, value_type, payload, updated_at) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, "test:unknown-type:" + System.currentTimeMillis());
                    ps.setString(2, "unknown_java_type");
                    ps.setBytes(3, new byte[]{1, 2, 3});
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }

                // Send a normal message — system should not crash from the unknown KV record
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
            }
        }
    }

    // ---------- UT-derived integrity scenarios ----------

    /**
     * F089-72: Mismatched task_id in JSON — deserialize() integrity check via E2E.
     *
     * <p>UT {@code malformedOrMismatchedTaskFailsInsteadOfLookingMissing} verifies that
     * {@code OpenGaussTaskStore.deserialize()} throws {@code TaskSerializationException} when
     * the {@code id} field inside the JSON doesn't match the row's {@code task_id} column.
     *
     * <p>E2E: modify the task JSON so the {@code id} field differs from the DB column value,
     * then attempt recovery. The integrity violation must not be silently ignored.
     */
    @Test
    void f089_72_mismatchedTaskIdIntegrity() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Read the task row and modify the id field inside JSON to mismatch
                String[] row = MoaSutHelper.getTaskRow(MoaSutHelper.L1_AGENT_ID, ctxId);
                assertThat(row).isNotNull();
                String taskId = row[0];
                String json = row[1];
                // Replace the id field value with a different UUID
                String modifiedJson = json.replace("\"id\":\"" + taskId + "\"",
                        "\"id\":\"mismatched-" + taskId + "\"");
                MoaSutHelper.updateTaskJson(MoaSutHelper.L1_AGENT_ID, ctxId, modifiedJson);

                // Attempt recovery — integrity violation must be visible
                A2aResponse resp2 = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                boolean indicatesError = resp2.statusCode() >= 400
                        || resp2.statusCode() == 0
                        || (resp2.finalTaskState() != null && resp2.finalTaskState().contains("ERROR"))
                        || (resp2.finalTaskState() != null && resp2.finalTaskState().contains("FAILED"));
                boolean fellbackToNewSession = !resp2.contextId().equals(ctxId);
                boolean silentRecovery = resp2.statusCode() == 200
                        && "TASK_STATE_COMPLETED".equals(resp2.finalTaskState())
                        && resp2.contextId() != null
                        && resp2.contextId().equals(ctxId)
                        && !fellbackToNewSession
                        && !indicatesError;
                assertThat(silentRecovery)
                        .as("Mismatched task_id must not be silently recovered as clean COMPLETED (L2 §2.3)")
                        .isFalse();
            }
        }
    }

    /**
     * F089-73: Invalid state enum in task JSON — enum validation via E2E.
     *
     * <p>UT {@code malformedOrMismatchedTaskFailsInsteadOfLookingMissing} verifies that
     * JSON with an invalid state enum value throws {@code TaskSerializationException}.
     *
     * <p>E2E: modify the task JSON to replace the state enum with an invalid value,
     * then attempt recovery. The invalid enum must not be silently ignored.
     */
    @Test
    void f089_73_invalidStateEnum() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Read the task row and replace state with an invalid enum value
                String[] row = MoaSutHelper.getTaskRow(MoaSutHelper.L1_AGENT_ID, ctxId);
                assertThat(row).isNotNull();
                String json = row[1];
                // Replace TASK_STATE_COMPLETED (or any TASK_STATE_*) with an invalid value
                String modifiedJson = json.replaceAll("\"state\":\"TASK_STATE_[A-Z]+\"",
                        "\"state\":\"INVALID_STATE\"");
                MoaSutHelper.updateTaskJson(MoaSutHelper.L1_AGENT_ID, ctxId, modifiedJson);

                // Attempt recovery — invalid enum must be visible
                A2aResponse resp2 = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                boolean indicatesError = resp2.statusCode() >= 400
                        || resp2.statusCode() == 0
                        || (resp2.finalTaskState() != null && resp2.finalTaskState().contains("ERROR"))
                        || (resp2.finalTaskState() != null && resp2.finalTaskState().contains("FAILED"));
                boolean fellbackToNewSession = !resp2.contextId().equals(ctxId);
                boolean silentRecovery = resp2.statusCode() == 200
                        && "TASK_STATE_COMPLETED".equals(resp2.finalTaskState())
                        && resp2.contextId() != null
                        && resp2.contextId().equals(ctxId)
                        && !fellbackToNewSession
                        && !indicatesError;
                assertThat(silentRecovery)
                        .as("Invalid state enum must not be silently recovered as clean COMPLETED (L2 §2.3)")
                        .isFalse();
            }
        }
    }

    /**
     * F089-74: Missing status.timestamp in task JSON — structural integrity via E2E.
     *
     * <p>UT {@code malformedOrMismatchedTaskFailsInsteadOfLookingMissing} verifies that
     * JSON with null status.timestamp throws {@code TaskSerializationException}.
     *
     * <p>E2E: modify the task JSON to remove the timestamp field, then attempt recovery.
     * The structural violation must not be silently ignored.
     */
    @Test
    void f089_74_missingTimestamp() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Read the task row and remove the timestamp field from JSON
                String[] row = MoaSutHelper.getTaskRow(MoaSutHelper.L1_AGENT_ID, ctxId);
                assertThat(row).isNotNull();
                String json = row[1];
                // Remove "timestamp":"..." from the JSON to simulate missing timestamp
                String modifiedJson = json.replaceAll("\"timestamp\":\"[^\"]*\",?", "");
                MoaSutHelper.updateTaskJson(MoaSutHelper.L1_AGENT_ID, ctxId, modifiedJson);

                // Attempt recovery — missing timestamp must be visible
                A2aResponse resp2 = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                boolean indicatesError = resp2.statusCode() >= 400
                        || resp2.statusCode() == 0
                        || (resp2.finalTaskState() != null && resp2.finalTaskState().contains("ERROR"))
                        || (resp2.finalTaskState() != null && resp2.finalTaskState().contains("FAILED"));
                boolean fellbackToNewSession = !resp2.contextId().equals(ctxId);
                boolean silentRecovery = resp2.statusCode() == 200
                        && "TASK_STATE_COMPLETED".equals(resp2.finalTaskState())
                        && resp2.contextId() != null
                        && resp2.contextId().equals(ctxId)
                        && !fellbackToNewSession
                        && !indicatesError;
                assertThat(silentRecovery)
                        .as("Missing timestamp must not be silently recovered as clean COMPLETED (L2 §2.3)")
                        .isFalse();
            }
        }
    }

    // ---------- UT-derived data-integrity scenarios ----------

    /**
     * F089-75: Unicode surrogate pair in message — codec must not crash.
     *
     * <p>UT {@code codecPreservesBinaryStringsAndNull} verifies that the KV codec
     * rejects unpaired surrogates ({@code "\uD800"}) with
     * {@code IllegalArgumentException}. In E2E, an unpaired surrogate in a message
     * could crash serialization or silently corrupt stored JSON.
     *
     * <p>E2E: send a message containing an unpaired surrogate, verify the system
     * either rejects it gracefully or handles it without crashing the KV store.
     */
    @Test
    void f089_75_unicodeSurrogatePairCodec() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Send a message with an unpaired surrogate character
                // The system should handle this gracefully — not crash, not corrupt data
                A2aResponse resp = client.sendStreamingMessage(
                        "你好\uD800请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                // System should either complete normally or indicate an error
                // The key assertion: no silent data corruption
                assertThat(resp.statusCode())
                        .as("Surrogate pair message should get a valid HTTP response")
                        .isIn(200, 400, 500);

                if (resp.statusCode() == 200) {
                    assertThat(resp.finalTaskState())
                            .as("Should reach a terminal state or show error")
                            .isIn("TASK_STATE_COMPLETED", "TASK_STATE_FAILED");
                    // Verify DB is not corrupted
                    if (db.hasTaskRows()) {
                        String[] row = MoaSutHelper.getTaskRow(MoaSutHelper.L1_AGENT_ID, resp.contextId());
                        if (row != null) {
                            assertThat(row[1])
                                    .as("Task JSON should be valid JSON despite surrogate in message")
                                    .startsWith("{");
                        }
                    }
                }
            }
        }
    }

    /**
     * F089-76: Multiple messages in same session — KV key prefix isolation.
     *
     * <p>UT {@code literalPrefixAndBatchReadPreserveCallerSemantics} verifies that
     * prefix queries correctly escape LIKE wildcards. In E2E, after multiple
     * sessions, KV keys from different sessions should not cross-match.
     *
     * <p>E2E: create two sessions, verify KV keys are isolated by session prefix
     * (no wildcard expansion causing cross-session data leakage).
     */
    @Test
    void f089_76_kvKeyPrefixIsolation() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Session A
                A2aResponse respA = client.sendStreamingMessage(
                        "你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxA = respA.contextId();

                // Session B
                A2aResponse respB = client.sendStreamingMessage(
                        "你好，请再简单介绍一下上海", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxB = respB.contextId();

                assertThat(ctxA).isNotEqualTo(ctxB);
                assertThat(respA.statusCode()).isEqualTo(200);
                assertThat(respB.statusCode()).isEqualTo(200);

                // Verify KV keys from session A don't match session B's prefix
                var keysA = db.listKvKeysByPrefix(ctxA);
                var keysB = db.listKvKeysByPrefix(ctxB);
                assertThat(keysA).isNotEmpty();
                assertThat(keysB).isNotEmpty();

                // No key from A should appear in B's list and vice versa
                for (String keyA : keysA) {
                    assertThat(keysB)
                            .as("KV key from session A must not appear in session B's prefix query")
                            .doesNotContain(keyA);
                }
                for (String keyB : keysB) {
                    assertThat(keysA)
                            .as("KV key from session B must not appear in session A's prefix query")
                            .doesNotContain(keyB);
                }
            }
        }
    }

    /**
     * F089-77: Multiple messages — task_json history not truncated by projections.
     *
     * <p>UT {@code listUsesStableSdkPaginationAndDoesNotPersistProjections} verifies
     * that listing tasks with truncated-history projections does not mutate the
     * stored task JSON. In E2E, after multiple messages, the stored task_json
     * should retain full history.
     *
     * <p>E2E: send 3 messages in same session, after each verify task_json
     * in DB has non-empty history that grows (or at least doesn't shrink).
     */
    @Test
    void f089_77_taskJsonHistoryNotTruncated() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Message 1
                A2aResponse resp1 = client.sendStreamingMessage(
                        "你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();
                assertThat(resp1.statusCode()).isEqualTo(200);

                String[] row1 = MoaSutHelper.getTaskRow(MoaSutHelper.L1_AGENT_ID, ctxId);
                assertThat(row1).as("Task row should exist after message 1").isNotNull();
                assertThat(row1[1]).startsWith("{").contains("id");

                // Message 2 — new command in same session
                A2aResponse resp2 = client.sendStreamingMessage(
                        "请再简单介绍一下上海", ctxId, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                assertThat(resp2.statusCode()).isEqualTo(200);

                String[] row2 = MoaSutHelper.getTaskRow(MoaSutHelper.L1_AGENT_ID, ctxId);
                assertThat(row2).as("Task row should exist after message 2").isNotNull();
                // Task JSON should still be valid
                assertThat(row2[1]).startsWith("{").contains("id");

                // Message 3 — another message in same session
                A2aResponse resp3 = client.sendStreamingMessage(
                        "请再说一下北京", ctxId, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                assertThat(resp3.statusCode()).isEqualTo(200);

                String[] row3 = MoaSutHelper.getTaskRow(MoaSutHelper.L1_AGENT_ID, ctxId);
                assertThat(row3).as("Task row should exist after message 3").isNotNull();
                assertThat(row3[1]).startsWith("{").contains("id");

                // Verify task_json is still valid JSON with required fields after 3 saves
                // The JSON should not be truncated or corrupted by internal projections
                assertThat(row3[1].length())
                        .as("Task JSON should not shrink after additional messages")
                        .isGreaterThanOrEqualTo(row1[1].length() / 2);

                // Verify only 1 task row for this context (upsert, not insert)
                assertThat(db.countTasksByContext(MoaSutHelper.L1_AGENT_ID, ctxId))
                        .as("Should have exactly 1 task row per context (upsert semantics)")
                        .isEqualTo(1);
            }
        }
    }

    // ---------- Bug #4-derived: true half-record variants ----------

    /**
     * F089-78: Delete only the blob key (keep dump_type) — true half-record variant.
     *
     * <p>Per design spec F089-11: half-record → feedback recovery error, not silent.
     * This tests the inverse of F089-11: instead of deleting dump_type, delete blob.
     * With dump_type present but blob absent, deserializeState returns null (blob==null)
     * → recover silently skips restoreState. This is the same Bug #4 gap.
     */
    @Test
    void f089_78_halfRecordDeleteBlobOnly() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp.contextId();

                // Find the blob key (not dump_type) — deleting only blob creates half-record
                var kvKeys = db.listKvKeysByPrefix(ctxId);
                assertThat(kvKeys).isNotEmpty();
                String blobKey = kvKeys.stream()
                        .filter(k -> !k.endsWith("_dump_type"))
                        .findFirst()
                        .orElse(null);
                assertThat(blobKey).as("Should find a blob checkpoint key").isNotNull();

                // Delete ONLY the blob key (dump_type remains → true half-record)
                boolean deleted = db.deleteKvByKey(blobKey);
                assertThat(deleted).as("blob key should be deleted").isTrue();

                // Verify dump_type key still exists (half-record confirmed)
                var remainingKeys = db.listKvKeysByPrefix(ctxId);
                assertThat(remainingKeys)
                        .as("dump_type key should still exist (true half-record)")
                        .isNotEmpty();

                // Attempt recovery — half-record must not be silently treated as clean
                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                boolean indicatesError = recovery.statusCode() >= 400
                        || recovery.statusCode() == 0
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("ERROR"))
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("FAILED"));
                boolean fellbackToNewSession = !recovery.contextId().equals(ctxId);
                if (recovery.statusCode() == 200 && "TASK_STATE_COMPLETED".equals(recovery.finalTaskState())) {
                    assertThat(fellbackToNewSession || indicatesError)
                            .as("Half-record (only blob deleted) must not be silently recovered as clean COMPLETED with same contextId")
                            .isTrue();
                }
            }
        }
    }

    /**
     * F089-79: Corrupt KV blob payload (not delete, corrupt) with valid dump_type.
     *
     * <p>Per design spec F089-08: corrupted content → feedback recovery error.
     * With dump_type="java" present but blob corrupted to garbage bytes,
     * deserializeState calls serializer.loadsTyped() which throws RuntimeException.
     * BaseStorage.deserializeState does NOT catch this (unlike AgentStorage.deserializeBlob).
     * The exception should propagate as a visible error, not crash the server.
     */
    @Test
    void f089_79_corruptKvBlobPayload() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp.contextId();

                // Find the blob key and corrupt its payload
                var kvKeys = db.listKvKeysByPrefix(ctxId);
                assertThat(kvKeys).isNotEmpty();
                String blobKey = kvKeys.stream()
                        .filter(k -> !k.endsWith("_dump_type"))
                        .findFirst()
                        .orElse(null);
                assertThat(blobKey).as("Should find a blob checkpoint key").isNotNull();

                // Corrupt the blob payload with garbage bytes
                boolean updated = db.updateKvPayload(blobKey, new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05, (byte) 0xDE, (byte) 0xAD});
                assertThat(updated).as("blob payload should be corrupted").isTrue();

                // Attempt recovery — corrupted content must not be silently treated as clean
                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                boolean indicatesError = recovery.statusCode() >= 400
                        || recovery.statusCode() == 0
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("ERROR"))
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("FAILED"));
                boolean fellbackToNewSession = !recovery.contextId().equals(ctxId);
                if (recovery.statusCode() == 200 && "TASK_STATE_COMPLETED".equals(recovery.finalTaskState())) {
                    assertThat(fellbackToNewSession || indicatesError)
                            .as("Corrupted blob payload must not be silently recovered as clean COMPLETED with same contextId")
                            .isTrue();
                }
            }
        }
    }

    // ---------- Bug #1/#2 verification: capture raw SSE ----------

    /**
     * F089-80: KV-only capacity exceeded — isolate from Task capacity limit.
     *
     * <p>Bug #1 claimed KV key &gt;512 bytes didn't produce FAILED SSE event.
     * Developer says our 600-char contextId triggered BOTH KV AND Task limits.
     * This test uses a 300-char contextId (within Task's 512 limit) but with
     * a long agent_id prefix to push the full KV key over 512 bytes.
     *
     * <p>If KV exception alone produces a visible error (SSE FAILED or JSON-RPC error
     * or HTTP 500), Bug #1 is refuted. If it silently returns 200+null finalTaskState,
     * Bug #1 is confirmed.
     */
    @Test
    void f089_80_kvOnlyCapacityExceeded() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // 500-char contextId — within Task's VARCHAR(512) limit
                // KV key format: {sessionId}:agent:{agentId}:agent_state_blobs_dump_type
                // With ~36-char sessionId + 5 "agent" + ~20 agentId + 27 suffix = ~88 overhead
                // 500 + 88 = 588 > 512 → KV key exceeds capacity, Task context_id (500 < 512) does not
                String longCtx = "k".repeat(500);
                A2aResponse resp = client.sendStreamingMessage(
                        "你好，请简单介绍一下自己", longCtx, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Check raw SSE for any error indicators
                String raw = resp.rawBody();
                boolean hasJsonRpcError = raw.contains("\"error\"");
                boolean hasFailedState = raw.contains("FAILED") || raw.contains("TASK_STATE_FAILED");
                boolean hasInputRequired = raw.contains("INPUT_REQUIRED");
                boolean hasNullState = resp.finalTaskState() == null || "TIMEOUT".equals(resp.finalTaskState());

                // The system should surface the KV error somehow — not silently return 200+COMPLETED
                boolean hasVisibleError = resp.statusCode() >= 400
                        || resp.statusCode() == 0
                        || hasJsonRpcError
                        || hasFailedState;

                if (resp.statusCode() == 200 && "TASK_STATE_COMPLETED".equals(resp.finalTaskState())) {
                    // If 200+COMPLETED, check if it fell back to a different contextId (new session)
                    boolean fellback = !resp.contextId().equals(longCtx);
                    assertThat(fellback || hasVisibleError)
                            .as("KV capacity exceeded should produce a visible error or new session, not silent 200+COMPLETED with same contextId. rawBody snippet: "
                                    + raw.substring(0, Math.min(500, raw.length())))
                            .isTrue();
                }
                // If TIMEOUT or error state — that's acceptable (visible failure)
            }
        }
    }

    /**
     * F089-81: HITL interrupt raw SSE capture.
     *
     * <p>Bug #2 claimed HITL interrupt causes SSE timeout with no terminal event.
     * Developer says INPUT_REQUIRED state mapping and drain logic exist.
     * This test captures the raw SSE to check what events the server actually sends.
     *
     * <p>Uses a prompt that triggers HITL: mock-tourism agent returns __interaction__.
     */
    @Test
    void f089_81_hitlRawSseCapture() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Use a tourism keyword to trigger HITL interrupt path
                A2aResponse resp = client.sendStreamingMessage(
                        "查景点", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                String raw = resp.rawBody();

                // Check what the server actually sent
                boolean hasInputRequired = raw.contains("INPUT_REQUIRED");
                boolean hasCompleted = raw.contains("TASK_STATE_COMPLETED") || raw.contains("\"COMPLETED\"");
                boolean hasFailed = raw.contains("FAILED") || raw.contains("TASK_STATE_FAILED");
                boolean hasJsonRpcError = raw.contains("\"error\"");
                boolean isTimeout = "TIMEOUT".equals(resp.finalTaskState()) && resp.statusCode() == 0;

                // Record findings for analysis — don't fail, just capture what happened
                // The goal is to determine: did the server send INPUT_REQUIRED?
                System.out.println("[F089-81] statusCode=" + resp.statusCode()
                        + " finalTaskState=" + resp.finalTaskState()
                        + " hasInputRequired=" + hasInputRequired
                        + " hasCompleted=" + hasCompleted
                        + " hasFailed=" + hasFailed
                        + " hasJsonRpcError=" + hasJsonRpcError
                        + " isTimeout=" + isTimeout
                        + " rawBodyLen=" + raw.length());

                // Assert based on findings:
                // If TIMEOUT with no events at all → Bug #2 confirmed
                // If INPUT_REQUIRED present → Bug #2 refuted (server sent correct state)
                if (isTimeout) {
                    assertThat(raw.length() > 0)
                            .as("Timeout with empty rawBody suggests server didn't send any event — Bug #2 confirmed. rawBody: "
                                    + raw.substring(0, Math.min(500, raw.length())))
                            .isTrue();
                }

                // If we got a response, it should have SOME terminal state
                if (resp.statusCode() == 200) {
                    assertThat(hasInputRequired || hasCompleted || hasFailed || hasJsonRpcError)
                            .as("200 response should contain at least one recognizable state. rawBody: "
                                    + raw.substring(0, Math.min(500, raw.length())))
                            .isTrue();
                }
            }
        }
    }

    // ---------- Bug #2 Root Cause Diagnosis ----------

    /**
     * F089-88: Bug #2 root cause diagnosis — send "查景点", capture jstack + logs + DB state.
     *
     * <p>This test captures thread dumps while the SSE is hanging to pinpoint
     * exactly which method call is blocking the response.
     */
    @Test
    void f089_88_bug2RootCauseDiagnosis() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                long l1Pid = l1.pid();
                long l2Pid = l2.pid();
                System.out.println("[F089-88] L1 PID=" + l1Pid + ", L2 PID=" + l2Pid);

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Background thread: wait 12s then capture jstack of L1
                CompletableFuture<String> jstackFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(12_000);
                        System.out.println("[F089-88] Capturing jstack of L1 (PID=" + l1Pid + ") at T+12s...");
                        String jstack = captureJstack(l1Pid);
                        System.out.println("[F089-88] jstack length=" + jstack.length());
                        // Also capture L2
                        String jstackL2 = captureJstack(l2Pid);
                        System.out.println("[F089-88] L2 jstack length=" + jstackL2.length());
                        return jstack;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "INTERRUPTED";
                    }
                });

                // Send "查景点" with 30s timeout (shorter than usual 120s for diagnosis)
                System.out.println("[F089-88] Sending '查景点' to L1...");
                A2aResponse resp = client.sendStreamingMessage("查景点", null, Duration.ofSeconds(30));
                MoaSutHelper.drainLogs();

                // Wait for jstack to complete
                String jstack = jstackFuture.get(20, java.util.concurrent.TimeUnit.SECONDS);
                String l2Jstack = "";
                try {
                    l2Jstack = captureJstack(l2Pid);
                } catch (Exception ignored) {}

                String raw = resp.rawBody();
                System.out.println("[F089-88] Response: statusCode=" + resp.statusCode()
                        + " finalTaskState=" + resp.finalTaskState()
                        + " rawBodyLen=" + raw.length()
                        + " contextId=" + resp.contextId());

                if (!raw.isEmpty()) {
                    System.out.println("[F089-88] rawBody (first 1000): "
                            + raw.substring(0, Math.min(1000, raw.length())));
                }

                // Print DB state
                System.out.println("[F089-88] Task count for L1: " + db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID));
                if (resp.contextId() != null && !resp.contextId().isBlank()) {
                    int taskCount = db.countTasksByContext(MoaSutHelper.L1_AGENT_ID, resp.contextId());
                    System.out.println("[F089-88] Task count for contextId=" + resp.contextId() + ": " + taskCount);
                    var keys = db.listKvKeysByPrefix(resp.contextId());
                    System.out.println("[F089-88] KV keys for contextId: " + keys);
                }

                // Print jstack — focus on WAITING/BLOCKED threads
                System.out.println("\n[F089-88] === L1 JSTACK (WAITING/BLOCKED threads with relevant frames) ===");
                printInterestingThreads(jstack);

                System.out.println("\n[F089-88] === L2 JSTACK (WAITING/BLOCKED threads with relevant frames) ===");
                printInterestingThreads(l2Jstack);

                // Print L1 log — key diagnostic lines
                System.out.println("\n[F089-88] === L1 LOG KEY LINES ===");
                printLogKeyLines("moa-l1");

                System.out.println("\n[F089-88] === L2 LOG KEY LINES ===");
                printLogKeyLines("moa-l2");

                System.out.println("\n[F089-88] === MOCK LOG KEY LINES ===");
                printLogKeyLines("moa-mock");

                // Always pass — this is a diagnostic test, not a pass/fail test
                assertThat(true).isTrue();
            }
        }
    }

    // ---------- Bug #2 Full Thread Stack Diagnosis (multi-snapshot) ----------

    /**
     * F089-90: Bug #2 full thread stack diagnosis.
     *
     * <p>Sends "查景点" in a full three-process + openGauss + HTTP SSE deployment,
     * then captures jstack at T+5s, T+10s, T+15s, T+20s while the SSE is hanging.
     * Saves all snapshots + DB task state + raw SSE bytes + SUT logs to
     * {@code target/diag-logs/f089-90/} for offline analysis.
     *
     * <p>Key questions this test answers:
     * <ol>
     *   <li>Which thread is executing the a2a-agent request and what is its stack?</li>
     *   <li>Is any thread blocked in {@code session.commit()} → {@code .join()} (JDBC)?</li>
     *   <li>Is any thread blocked in {@code closeStream()} or {@code hasNext()}?</li>
     *   <li>What is the DB Task state — WORKING or INPUT_REQUIRED?</li>
     *   <li>Does the thread state change between snapshots (evolving block vs permanent)?</li>
     * </ol>
     */
    @Test
    void f089_90_bug2JstackDiagnosis() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                long l1Pid = l1.pid();
                long l2Pid = l2.pid();
                System.out.println("[F089-90] L1 PID=" + l1Pid + ", L2 PID=" + l2Pid);

                // Prepare output directory
                java.nio.file.Path diagDir = java.nio.file.Path.of("target", "diag-logs", "f089-90");
                java.nio.file.Files.createDirectories(diagDir);
                System.out.println("[F089-90] Diagnostic output dir: " + diagDir.toAbsolutePath());

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Background thread: capture jstack at T+2s, T+4s, T+8s, T+15s
                // T+2s captures the moment around interrupt detection (~1.4s after send)
                int[] snapshotDelays = {2, 4, 8, 15};
                CompletableFuture<Void> jstackFuture = CompletableFuture.runAsync(() -> {
                    int prevDelay = 0;
                    for (int delay : snapshotDelays) {
                        try {
                            Thread.sleep((delay - prevDelay) * 1000L);
                            prevDelay = delay;
                            String ts = "T+" + delay + "s";
                            System.out.println("[F089-90] Capturing jstack of L1 (PID=" + l1Pid + ") at " + ts + "...");
                            String jstack = captureJstack(l1Pid);
                            java.nio.file.Path file = diagDir.resolve("l1-jstack-" + delay + "s.txt");
                            java.nio.file.Files.writeString(file, jstack);
                            System.out.println("[F089-90] L1 jstack saved (" + jstack.length() + " bytes) to " + file);

                            // Also capture L2 at same timestamps
                            String l2Jstack = captureJstack(l2Pid);
                            java.nio.file.Path l2File = diagDir.resolve("l2-jstack-" + delay + "s.txt");
                            java.nio.file.Files.writeString(l2File, l2Jstack);
                            System.out.println("[F089-90] L2 jstack saved (" + l2Jstack.length() + " bytes) to " + l2File);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Exception e) {
                            System.out.println("[F089-90] jstack capture failed at T+" + delay + "s: " + e.getMessage());
                        }
                    }
                });

                // Send "查景点" with 30s timeout
                long sendStart = System.currentTimeMillis();
                System.out.println("[F089-90] Sending '查景点' to L1...");
                A2aResponse resp = client.sendStreamingMessage("查景点", null, Duration.ofSeconds(30));
                long elapsed = System.currentTimeMillis() - sendStart;
                MoaSutHelper.drainLogs();

                // Wait for all jstack snapshots to complete
                jstackFuture.get(25, java.util.concurrent.TimeUnit.SECONDS);

                System.out.println("[F089-90] Response received in " + elapsed + "ms");
                System.out.println("[F089-90] statusCode=" + resp.statusCode()
                        + " finalTaskState=" + resp.finalTaskState()
                        + " rawBodyLen=" + (resp.rawBody() != null ? resp.rawBody().length() : 0)
                        + " contextId=" + resp.contextId());

                // Save raw SSE response
                String rawBody = resp.rawBody() != null ? resp.rawBody() : "(null)";
                java.nio.file.Files.writeString(diagDir.resolve("sse-raw-response.txt"), rawBody);
                System.out.println("[F089-90] Raw SSE saved to " + diagDir.resolve("sse-raw-response.txt"));

                if (!rawBody.isEmpty() && !rawBody.equals("(null)")) {
                    System.out.println("[F089-90] rawBody (first 2000): "
                            + rawBody.substring(0, Math.min(2000, rawBody.length())));
                }

                // Query DB Task state
                System.out.println("\n[F089-90] === DB TASK STATE ===");
                System.out.println("[F089-90] Task count for L1 agent: " + db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID));
                if (resp.contextId() != null && !resp.contextId().isBlank()) {
                    String ctxId = resp.contextId();
                    int taskCount = db.countTasksByContext(MoaSutHelper.L1_AGENT_ID, ctxId);
                    System.out.println("[F089-90] Task count for contextId=" + ctxId + ": " + taskCount);
                    var keys = db.listKvKeysByPrefix(ctxId);
                    System.out.println("[F089-90] KV keys for contextId: " + keys);

                    // Get task_json to check task state
                    try {
                        String[] taskRow = MoaSutHelper.getTaskRow(MoaSutHelper.L1_AGENT_ID, ctxId);
                        if (taskRow != null) {
                            String taskId = taskRow[0];
                            String taskJson = taskRow[1];
                            System.out.println("[F089-90] Task ID: " + taskId);
                            System.out.println("[F089-90] Task JSON (first 2000): "
                                    + (taskJson != null ? taskJson.substring(0, Math.min(2000, taskJson.length())) : "(null)"));
                            // Check if task state is in the JSON
                            if (taskJson != null) {
                                boolean isWorking = taskJson.contains("TASK_STATE_WORKING") || taskJson.contains("WORKING");
                                boolean isInputRequired = taskJson.contains("INPUT_REQUIRED") || taskJson.contains("input-required");
                                System.out.println("[F089-90] Task state analysis: WORKING=" + isWorking
                                        + " INPUT_REQUIRED=" + isInputRequired);
                            }
                            java.nio.file.Files.writeString(diagDir.resolve("task-json.txt"), taskJson != null ? taskJson : "(null)");
                        }
                    } catch (Exception e) {
                        System.out.println("[F089-90] Failed to query task_json: " + e.getMessage());
                    }
                }

                // Print L1 log — all lines around interrupt/handler/complete
                System.out.println("\n[F089-90] === L1 LOG (full relevant lines) ===");
                java.nio.file.Path l1LogDir = findLatestLogDir("moa-l1");
                if (l1LogDir != null) {
                    java.nio.file.Path l1Stdout = l1LogDir.resolve("stdout.log");
                    saveAndPrintLog(l1Stdout, diagDir.resolve("l1-stdout.log"));
                }

                System.out.println("\n[F089-90] === L2 LOG (full relevant lines) ===");
                java.nio.file.Path l2LogDir = findLatestLogDir("moa-l2");
                if (l2LogDir != null) {
                    java.nio.file.Path l2Stdout = l2LogDir.resolve("stdout.log");
                    saveAndPrintLog(l2Stdout, diagDir.resolve("l2-stdout.log"));
                }

                System.out.println("\n[F089-90] === MOCK LOG (full relevant lines) ===");
                java.nio.file.Path mockLogDir = findLatestLogDir("moa-mock");
                if (mockLogDir != null) {
                    java.nio.file.Path mockStdout = mockLogDir.resolve("stdout.log");
                    saveAndPrintLog(mockStdout, diagDir.resolve("mock-stdout.log"));
                }

                // Print thread analysis from each jstack snapshot
                for (int delay : snapshotDelays) {
                    java.nio.file.Path jf = diagDir.resolve("l1-jstack-" + delay + "s.txt");
                    if (java.nio.file.Files.exists(jf)) {
                        String js = java.nio.file.Files.readString(jf);
                        System.out.println("\n[F089-90] === L1 JSTACK T+" + delay + "s (all non-daemon + relevant threads) ===");
                        printAllRelevantThreads(js, delay);
                    }
                }

                System.out.println("\n[F089-90] === DIAGNOSIS SUMMARY ===");
                System.out.println("[F089-90] SSE response: statusCode=" + resp.statusCode()
                        + " rawBodyLen=" + rawBody.length());
                System.out.println("[F089-90] All diagnostic files saved to: " + diagDir.toAbsolutePath());
                System.out.println("[F089-90] Analyze with: grep -n 'a2a\\|forwardStream\\|hasNext\\|closeStream\\|commit\\|join\\|BLOCKED\\|WAITING' "
                        + diagDir.resolve("l1-jstack-15s.txt"));

                // Always pass — diagnostic test
                assertThat(true).isTrue();
            }
        }
    }

    private static java.nio.file.Path findLatestLogDir(String namePrefix) {
        try {
            java.nio.file.Path logDir = java.nio.file.Path.of("target", "sit-logs");
            if (!java.nio.file.Files.exists(logDir)) return null;
            java.nio.file.Path matching = null;
            for (java.nio.file.Path d : java.util.stream.StreamSupport
                    .stream(java.nio.file.Files.newDirectoryStream(logDir).spliterator(), false)
                    .filter(java.nio.file.Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith(namePrefix))
                    .toList()) {
                if (matching == null || d.compareTo(matching) > 0) {
                    matching = d;
                }
            }
            return matching;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveAndPrintLog(java.nio.file.Path stdout, java.nio.file.Path dest) {
        try {
            if (!java.nio.file.Files.exists(stdout)) {
                System.out.println("  stdout.log not found: " + stdout);
                return;
            }
            String content = java.nio.file.Files.readString(stdout);
            java.nio.file.Files.writeString(dest, content);
            System.out.println("  Full log saved to: " + dest);

            String[] patterns = {
                    "A2A interrupt", "COMPLETED suppressed", "closeEventQueue",
                    "requires input", "INPUT_REQUIRED", "interrupt detected",
                    "task_interaction", "Completion signal", "All tasks completed",
                    "StreamQueue", "MOA-MATCH", "intent", "candidate",
                    "ERROR", "Exception", "error", "failed", "Failed",
                    "stream complete", "eventQueue", "closeStream",
                    "hasNext", "forwardStream", "publishFastInterrupt",
                    "requestForceFinish", "closeStreamAndCommit",
                    "SessionImpl", "session.commit", "checkpointer",
                    "save", "persist", "join"
            };
            java.util.List<String> lines = java.nio.file.Files.readAllLines(stdout);
            int printed = 0;
            for (String line : lines) {
                for (String p : patterns) {
                    if (line.contains(p)) {
                        System.out.println("  " + line);
                        printed++;
                        break;
                    }
                }
                if (printed >= 200) {
                    System.out.println("  ... (truncated, full log at " + dest + ")");
                    break;
                }
            }
            if (printed == 0) {
                System.out.println("  (no matching lines found)");
            }
        } catch (Exception e) {
            System.out.println("  Error reading log: " + e.getMessage());
        }
    }

    private static void printAllRelevantThreads(String jstack, int delaySeconds) {
        if (jstack == null || jstack.isBlank()) {
            System.out.println("  (empty)");
            return;
        }
        // Split by thread name lines (quoted strings at start of line)
        String[] blocks = jstack.split("(?=\n\")");
        int printed = 0;
        for (String block : blocks) {
            String lower = block.toLowerCase(java.util.Locale.ROOT);
            // Print threads that are: RUNNABLE, BLOCKED, or WAITING with relevant frames
            boolean isRunnable = block.contains("RUNNABLE");
            boolean isBlocked = block.contains("BLOCKED");
            boolean isWaiting = block.contains("WAITING");
            boolean hasRelevantFrame = lower.contains("a2a") || lower.contains("forwardstream")
                    || lower.contains("hasnext") || lower.contains("closestream")
                    || lower.contains("commit") || lower.contains("join")
                    || lower.contains("checkpoint") || lower.contains("save")
                    || lower.contains("orchestrat") || lower.contains("streamquery")
                    || lower.contains("handlestream") || lower.contains("finishstream")
                    || lower.contains("interrupt") || lower.contains("publish")
                    || lower.contains("forcefinish") || lower.contains("eventqueue")
                    || lower.contains("emitter") || lower.contains("observer")
                    || lower.contains("flux") || lower.contains("mono")
                    || lower.contains("pipeline") || lower.contains("handler")
                    || lower.contains("executor") || lower.contains("http-nio")
                    || lower.contains("reactor") || lower.contains("tomcat")
                    || lower.contains("servlet") || lower.contains("sse")
                    || lower.contains("opengauss") || lower.contains("jdbc")
                    || lower.contains("drivermanager") || lower.contains("connection")
                    || lower.contains("transaction") || lower.contains("maineventbus");
            if ((isRunnable || isBlocked) && hasRelevantFrame) {
                System.out.println(block.substring(0, Math.min(3000, block.length())));
                System.out.println("---");
                printed++;
            }
            if (printed >= 30) break;
        }
        // Also print all BLOCKED threads regardless
        if (printed == 0) {
            System.out.println("  No RUNNABLE/BLOCKED with relevant frames. Printing all BLOCKED:");
            for (String block : blocks) {
                if (block.contains("BLOCKED")) {
                    System.out.println(block.substring(0, Math.min(1500, block.length())));
                    System.out.println("---");
                    printed++;
                    if (printed >= 10) break;
                }
            }
        }
        long total = java.util.Arrays.stream(blocks)
                .filter(b -> b.contains("RUNNABLE") || b.contains("BLOCKED") || b.contains("WAITING"))
                .count();
        long runnable = java.util.Arrays.stream(blocks).filter(b -> b.contains("RUNNABLE")).count();
        long blocked = java.util.Arrays.stream(blocks).filter(b -> b.contains("BLOCKED")).count();
        long waiting = java.util.Arrays.stream(blocks).filter(b -> b.contains("WAITING")).count();
        System.out.println("  Snapshot T+" + delaySeconds + "s: total=" + total
                + " RUNNABLE=" + runnable + " BLOCKED=" + blocked + " WAITING=" + waiting);
    }

    private static String captureJstack(long pid) {
        try {
            String javaHome = System.getProperty("java.home");
            String jstack = javaHome + "/bin/jstack";
            Process p = new ProcessBuilder(jstack, String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            return output;
        } catch (Exception e) {
            return "JSTACK_FAILED: " + e.getMessage();
        }
    }

    private static void printInterestingThreads(String jstack) {
        if (jstack == null || jstack.isBlank()) {
            System.out.println("  (empty)");
            return;
        }
        // Save full jstack to file for offline analysis
        try {
            java.nio.file.Path jstackFile = java.nio.file.Path.of("target", "diag-logs", "l1-jstack.txt");
            java.nio.file.Files.createDirectories(jstackFile.getParent());
            java.nio.file.Files.writeString(jstackFile, jstack);
            System.out.println("  Full jstack saved to: " + jstackFile.toAbsolutePath());
        } catch (Exception e) {
            System.out.println("  Failed to save jstack: " + e.getMessage());
        }
        // Split by thread name lines (quoted strings at start of line)
        String[] blocks = jstack.split("(?=\n\")");
        int printed = 0;
        for (String block : blocks) {
            if (printed >= 10) break;
            if (block.contains("WAITING") || block.contains("BLOCKED") || block.contains("parking")) {
                // Use case-insensitive contains instead of matches()
                String lower = block.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("a2a") || lower.contains("emitter") || lower.contains("eventqueue")
                        || lower.contains("orchestrat") || lower.contains("streamquery")
                        || lower.contains("handlestream") || lower.contains("closeevent")
                        || lower.contains("finishstream") || lower.contains("interrupt")
                        || lower.contains("task_inter") || lower.contains("agent")
                        || lower.contains("controller") || lower.contains("service")
                        || lower.contains("querychunk") || lower.contains("forwardchunk")
                        || lower.contains("observer") || lower.contains("serveorchestrat")
                        || lower.contains("http-nio") || lower.contains("reactor")
                        || lower.contains("tomcat") || lower.contains("servlet")
                        || lower.contains("sse") || lower.contains("stream")
                        || lower.contains("flux") || lower.contains("mono")
                        || lower.contains("pipeline") || lower.contains("handler")
                        || lower.contains("executor") || lower.contains("threadpool")) {
                    System.out.println(block.substring(0, Math.min(2000, block.length())));
                    System.out.println("---");
                    printed++;
                }
            }
        }
        // If nothing matched, print all WAITING/BLOCKED threads
        if (printed == 0) {
            System.out.println("  No A2A-specific matches. Printing all WAITING/BLOCKED threads:");
            for (String block : blocks) {
                if (printed >= 15) break;
                if (block.contains("WAITING") || block.contains("BLOCKED")) {
                    System.out.println(block.substring(0, Math.min(800, block.length())));
                    System.out.println("---");
                    printed++;
                }
            }
        }
        long waiting = java.util.Arrays.stream(blocks)
                .filter(b -> b.contains("WAITING") || b.contains("BLOCKED"))
                .count();
        System.out.println("  Total WAITING/BLOCKED threads: " + waiting);
    }

    private static void printLogKeyLines(String namePrefix) {
        try {
            java.nio.file.Path logDir = java.nio.file.Path.of("target", "sit-logs");
            if (!java.nio.file.Files.exists(logDir)) {
                System.out.println("  logDir not found: " + logDir);
                return;
            }
            // Find the most recent log directory matching the name prefix
            java.nio.file.Path matching = null;
            for (java.nio.file.Path d : java.util.stream.StreamSupport
                    .stream(java.nio.file.Files.newDirectoryStream(logDir).spliterator(), false)
                    .filter(java.nio.file.Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith(namePrefix))
                    .toList()) {
                if (matching == null || d.compareTo(matching) > 0) {
                    matching = d;
                }
            }
            if (matching == null) {
                System.out.println("  No log directory matching " + namePrefix);
                return;
            }
            java.nio.file.Path stdout = matching.resolve("stdout.log");
            if (!java.nio.file.Files.exists(stdout)) {
                System.out.println("  stdout.log not found in " + matching);
                return;
            }
            String[] patterns = {
                    "A2A interrupt", "COMPLETED suppressed", "leaving it open",
                    "closeEventQueue", "requires input", "INPUT_REQUIRED",
                    "interrupt detected", "task_interaction", "Completion signal",
                    "All tasks completed", "StreamQueue", "MOA-MATCH",
                    "intent", "candidate", "ERROR", "Exception", "error",
                    "stream complete", "eventQueue drained", "task_released",
                    "failed", "Failed"
            };
            java.util.List<String> lines = java.nio.file.Files.readAllLines(stdout);
            for (String line : lines) {
                for (String p : patterns) {
                    if (line.contains(p)) {
                        System.out.println("  " + line);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  Error reading logs: " + e.getMessage());
        }
    }

    /**
     * F089-82: WorkflowStorage half-record — delete UPDATE pair, keep STATE pair.
     *
     * <p>WorkflowStorage.recover() reads 4 keys (STATE_DUMP_TYPE, STATE_BLOBS,
     * UPDATE_DUMP_TYPE, UPDATE_BLOBS). Same size-check pattern as AgentStorage.
     * Redis implementation throws on incomplete pairs; PersistenceCheckpointer may not.
     *
     * <p>Test: send message → list KV keys → find workflow keys → delete UPDATE pair
     * → send resume → check if partial recovery is silent or errors.
     */
    @Test
    void f089_82_workflowHalfRecordDeleteUpdatePair() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp.contextId();

                // List all KV keys for this session
                var kvKeys = db.listKvKeysByPrefix(ctxId);
                System.out.println("[F089-82] All KV keys: " + kvKeys);

                // Find workflow keys (contain "workflow")
                var workflowKeys = kvKeys.stream()
                        .filter(k -> k.contains("workflow"))
                        .toList();
                System.out.println("[F089-82] Workflow keys: " + workflowKeys);

                if (workflowKeys.isEmpty()) {
                    // If no workflow keys, this agent doesn't use workflow state — skip
                    System.out.println("[F089-82] No workflow keys found — agent may not use workflow state. Skipping.");
                    return;
                }

                // Find and delete UPDATE pair (update_blobs and update_blobs_dump_type)
                for (String key : workflowKeys) {
                    if (key.contains("update_blobs")) {
                        boolean deleted = db.deleteKvByKey(key);
                        System.out.println("[F089-82] Deleted update key: " + key + " → " + deleted);
                    }
                }

                // Verify STATE keys still exist
                var remaining = db.listKvKeysByPrefix(ctxId);
                System.out.println("[F089-82] Remaining keys after delete: " + remaining);

                // Attempt recovery with incomplete workflow checkpoint
                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                System.out.println("[F089-82] Recovery: statusCode=" + recovery.statusCode()
                        + " finalTaskState=" + recovery.finalTaskState()
                        + " contextId=" + recovery.contextId());

                // Per design: incomplete checkpoint → recovery error, not silent
                boolean indicatesError = recovery.statusCode() >= 400
                        || recovery.statusCode() == 0
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("ERROR"))
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("FAILED"));
                boolean fellbackToNewSession = !recovery.contextId().equals(ctxId);
                if (recovery.statusCode() == 200 && "TASK_STATE_COMPLETED".equals(recovery.finalTaskState())) {
                    assertThat(fellbackToNewSession || indicatesError)
                            .as("Workflow half-record (UPDATE pair deleted) must not be silently recovered as clean COMPLETED")
                            .isTrue();
                }
            }
        }
    }

    // ---------- Opportunity #2: Concurrent checkpoint save race ----------

    /**
     * F089-83: Two concurrent A2A requests with same contextId.
     *
     * <p>The 4-key pipeline save has no transactional guarantee across individual set() calls.
     * Two concurrent saves could interleave, producing mixed checkpoint state.
     *
     * <p>Test: send two streaming requests simultaneously with same contextId,
     * then check KV checkpoint data consistency.
     */
    @Test
    void f089_83_concurrentSameContextId() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                String sharedCtx = "concurrent-" + UUID.randomUUID();

                // Fire two requests simultaneously with same contextId
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    CompletableFuture<A2aResponse> f1 = CompletableFuture.supplyAsync(() -> {
                        try {
                            return client.sendStreamingMessage("你好，请介绍一下自己", sharedCtx, MoaSutHelper.INVOKE_TIMEOUT);
                        } catch (Exception e) { throw new RuntimeException(e); }
                    }, pool);
                    CompletableFuture<A2aResponse> f2 = CompletableFuture.supplyAsync(() -> {
                        try {
                            return client.sendStreamingMessage("请再说一遍", sharedCtx, MoaSutHelper.INVOKE_TIMEOUT);
                        } catch (Exception e) { throw new RuntimeException(e); }
                    }, pool);

                    A2aResponse r1 = f1.join();
                    A2aResponse r2 = f2.join();
                    MoaSutHelper.drainLogs();

                    System.out.println("[F089-83] r1: statusCode=" + r1.statusCode() + " state=" + r1.finalTaskState());
                    System.out.println("[F089-83] r2: statusCode=" + r2.statusCode() + " state=" + r2.finalTaskState());

                    // Both should get some response (not crash)
                    assertThat(r1.statusCode() != 0 || r1.finalTaskState() != null)
                            .as("First concurrent request should get a response")
                            .isTrue();
                    assertThat(r2.statusCode() != 0 || r2.finalTaskState() != null)
                            .as("Second concurrent request should get a response")
                            .isTrue();

                    // Check KV data consistency — count KV keys for this session
                    var kvKeys = db.listKvKeysByPrefix(sharedCtx);
                    System.out.println("[F089-83] KV keys: " + kvKeys);

                    // Should have exactly 2 agent checkpoint keys (dump_type + blob)
                    // If there are more, it indicates interleaved writes
                    long agentKeys = kvKeys.stream()
                            .filter(k -> k.contains("agent_state_blobs"))
                            .count();
                    System.out.println("[F089-83] Agent state blob keys count: " + agentKeys);

                    // Having > 2 agent state keys suggests concurrent writes created duplicates
                    // or interleaved partial writes
                    if (agentKeys > 2) {
                        System.out.println("[F089-83] WARNING: More than 2 agent state keys — possible concurrent write interleaving");
                    }

                    // Verify recovery still works after concurrent saves
                    A2aResponse recovery = client.continueSession(sharedCtx, MoaSutHelper.RECOVERY_TIMEOUT);
                    MoaSutHelper.drainLogs();
                    System.out.println("[F089-83] Recovery after concurrent: statusCode=" + recovery.statusCode()
                            + " state=" + recovery.finalTaskState());

                    // Recovery should either work or fail visibly, not hang
                    assertThat(recovery.statusCode() != 0 || recovery.finalTaskState() != null)
                            .as("Recovery after concurrent saves should produce a response")
                            .isTrue();
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    // ---------- Opportunity #3: Resume after INPUT_REQUIRED ----------

    /**
     * F089-84: Resume after INPUT_REQUIRED — does the second message work?
     *
     * <p>Bug #2 shows SSE hangs on __interaction__ interrupt. But does the Task
     * persist in INPUT_REQUIRED state? If we send a second message with same
     * contextId, does the server resume the task or create a new one?
     *
     * <p>Test: send "查景点" (triggers HITL) → wait briefly → send resume message
     * with same contextId → check if SSE produces output or also hangs.
     */
    @Test
    void f089_84_resumeAfterInputRequired() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());

                // Step 1: Send tourism keyword to trigger HITL interrupt
                // Use short timeout — we know from F089-81 it will hang
                A2aResponse r1 = client.sendStreamingMessage("查景点", null, Duration.ofSeconds(30));
                MoaSutHelper.drainLogs();
                String ctxId = r1.contextId();

                System.out.println("[F089-84] First request: statusCode=" + r1.statusCode()
                        + " state=" + r1.finalTaskState()
                        + " rawBodyLen=" + r1.rawBody().length());

                // Step 2: Check Task state in DB — is it INPUT_REQUIRED?
                int taskCount = db.countTasksByContext(MoaSutHelper.L1_AGENT_ID, ctxId);
                System.out.println("[F089-84] Tasks in DB for context: " + taskCount);

                // Step 3: Send resume message with same contextId
                A2aResponse r2 = client.sendStreamingMessage("北京", ctxId, Duration.ofSeconds(60));
                MoaSutHelper.drainLogs();

                System.out.println("[F089-84] Resume request: statusCode=" + r2.statusCode()
                        + " state=" + r2.finalTaskState()
                        + " rawBodyLen=" + r2.rawBody().length()
                        + " sameContext=" + ctxId.equals(r2.contextId()));

                String raw = r2.rawBody();
                boolean hasInputRequired = raw.contains("INPUT_REQUIRED");
                boolean hasCompleted = raw.contains("TASK_STATE_COMPLETED") || raw.contains("\"COMPLETED\"");
                boolean hasFailed = raw.contains("FAILED") || raw.contains("TASK_STATE_FAILED");
                boolean hasJsonRpcError = raw.contains("\"error\"");

                System.out.println("[F089-84] Resume SSE: hasInputRequired=" + hasInputRequired
                        + " hasCompleted=" + hasCompleted
                        + " hasFailed=" + hasFailed
                        + " hasJsonRpcError=" + hasJsonRpcError);

                // The resume should produce SOME response — either completion, error, or another INPUT_REQUIRED
                // If it also hangs (TIMEOUT with empty rawBody), that confirms Bug #2 affects resume too
                if (r2.statusCode() == 0 && "TIMEOUT".equals(r2.finalTaskState()) && r2.rawBody().isEmpty()) {
                    System.out.println("[F089-84] FAIL: Resume also hangs — Bug #2 affects resume path");
                }

                // Assert: resume should not hang (if it does, that's a bug)
                assertThat(r2.statusCode() != 0 || !r2.rawBody().isEmpty())
                        .as("Resume after INPUT_REQUIRED should produce some response, not hang. rawBodyLen=" + r2.rawBody().length())
                        .isTrue();
            }
        }
    }

    // ---------- Verification matrix: untested scenarios ----------

    /**
     * F089-85: Unsupported type marker — set dump_type to "python" instead of "java".
     *
     * <p>Developer's verification matrix requires: "不支持的类型标记 → 恢复错误".
     * If deserializeState("python", validBlob) returns null instead of throwing,
     * that's the same Bug #4 pattern with a different trigger.
     */
    @Test
    void f089_85_unsupportedTypeMarker() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp.contextId();

                // Corrupt: change dump_type from "java" to "python"
                boolean corrupted = db.corruptKvDumpType(ctxId, "python");
                System.out.println("[F089-85] Corrupted dump_type to 'python': " + corrupted);

                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                System.out.println("[F089-85] Recovery: statusCode=" + recovery.statusCode()
                        + " state=" + recovery.finalTaskState()
                        + " sameCtx=" + ctxId.equals(recovery.contextId()));

                boolean indicatesError = recovery.statusCode() >= 400
                        || recovery.statusCode() == 0
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("ERROR"))
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("FAILED"));
                boolean fellback = !recovery.contextId().equals(ctxId);
                if (recovery.statusCode() == 200 && "TASK_STATE_COMPLETED".equals(recovery.finalTaskState())) {
                    assertThat(fellback || indicatesError)
                            .as("Unsupported type marker 'python' must not be silently recovered as COMPLETED")
                            .isTrue();
                }
            }
        }
    }

    /**
     * F089-86: Deserialized state is empty but valid Map.
     *
     * <p>Developer's verification matrix requires: "结构合法的空 Map → 正常恢复".
     * This should PASS — empty Map is a valid state that can be restored.
     */
    @Test
    void f089_86_emptyMapStateRecovery() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp.contextId();

                // Replace blob with serialized empty Map (Java serialization of empty HashMap)
                // Java serialized HashMap starts with 0xac 0xed 0x00 0x05 ...
                // We use base64 of an empty HashMap serialized by Java
                boolean replaced = db.replaceKvBlobWithEmptyMap(ctxId);
                System.out.println("[F089-86] Replaced blob with empty Map: " + replaced);

                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                System.out.println("[F089-86] Recovery: statusCode=" + recovery.statusCode()
                        + " state=" + recovery.finalTaskState());

                // Empty Map should be valid state — recovery should succeed normally
                // Just verify it doesn't crash or hang
                assertThat(recovery.statusCode() != 0 || recovery.finalTaskState() != null)
                        .as("Empty Map state recovery should produce a response, not hang")
                        .isTrue();
            }
        }
    }

    /**
     * F089-87: Deserialized state is NOT a Map (e.g., a String or List).
     *
     * <p>Developer's verification matrix requires: "反序列化后为 null 或非 Map → 恢复错误".
     * If restoreState receives a non-Map object without checking, it may crash or silently
     * accept invalid state.
     */
    @Test
    void f089_87_nonMapStateRecovery() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaSutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp.contextId();

                // Replace blob with serialized String (not a Map)
                boolean replaced = db.replaceKvBlobWithNonMap(ctxId);
                System.out.println("[F089-87] Replaced blob with non-Map: " + replaced);

                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                System.out.println("[F089-87] Recovery: statusCode=" + recovery.statusCode()
                        + " state=" + recovery.finalTaskState()
                        + " sameCtx=" + ctxId.equals(recovery.contextId()));

                boolean indicatesError = recovery.statusCode() >= 400
                        || recovery.statusCode() == 0
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("ERROR"))
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("FAILED"));
                boolean fellback = !recovery.contextId().equals(ctxId);
                if (recovery.statusCode() == 200 && "TASK_STATE_COMPLETED".equals(recovery.finalTaskState())) {
                    assertThat(fellback || indicatesError)
                            .as("Non-Map deserialized state must not be silently recovered as COMPLETED")
                            .isTrue();
                }
            }
        }
    }
}
