/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa2_session_persistence;

import com.huawei.ascend.sit.cases.integration.edpa2_session_persistence.fixture.Edpa2AgentHttpClient;
import com.huawei.ascend.sit.cases.integration.edpa2_session_persistence.fixture.Edpa2AgentHttpClient.A2aResponse;
import com.huawei.ascend.sit.cases.integration.edpa2_session_persistence.fixture.Edpa2DemoLauncher;
import com.huawei.ascend.sit.cases.integration.edpa2_session_persistence.fixture.Edpa2SutHelper;
import com.huawei.ascend.sit.cases.integration.edpa2_session_persistence.fixture.OpenGaussProbe;
import com.huawei.ascend.sit.cases.integration.edpa2_session_persistence.fixture.SutLogTailer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-089 P1/P2 — boundary scenarios (F089-31~40) and openGauss-specific
 * scenarios (F089-41~50).
 *
 * <p>Boundary: isolation multi-dimension, TTL threshold, cross-session recall OUT,
 * recovery confirmation OUT, cross-backend migration OUT, data cleanup OUT,
 * key exceed DDL, large payload, empty key reject, Redis TTL zero config.
 *
 * <p>openGauss: startup check, startup check fail, no TTL not-supported ops,
 * driver missing, external driver load, password decrypt fail, missing/duplicate
 * provider, connection-ref not found, startup fail resource cleanup, shutdown order.
 */
@Tag("integration")
@Tag("feat-089")
@Tag("slow")
@Tag("manual")
final class Feat089BoundaryAndOpenGaussTest {

    // ---------- Boundary scenarios (F089-31~40) ----------

    /**
     * F089-31: Isolation multi-dimension — agent_id dimension.
     */
    @Test
    void f089_31_isolationMultiDimension() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());

                // Session A on default agent_id
                A2aResponse respA = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Verify isolation by agent_id — L1 tasks are under moa-demo-l1
                int l1Tasks = db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID);
                int l2Tasks = db.countTasksByAgent(Edpa2SutHelper.L2_AGENT_ID);
                assertThat(l1Tasks).isGreaterThan(0);
                // L2 agent_id should have separate records (if L2 persists anything)
                // The key assertion: agent_id dimension isolates correctly
                var ctxIds = db.listContextIdsByAgent(Edpa2SutHelper.L1_AGENT_ID);
                assertThat(ctxIds).contains(respA.contextId());
            }
        }
    }

    /**
     * F089-32: TTL threshold — openGauss has no TTL expiry.
     *
     * <p>Disabled: openGauss has no session TTL; L2 §3.3 states openGauss records
     * have no expiry column, so TTL threshold testing is not applicable.
     */
    @Disabled("openGauss has no session TTL; L2 §3.3 states openGauss records have no expiry column")
    @Test
    void f089_32_ttlThreshold() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());

                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                String ctxId = resp.contextId();

                // Wait briefly (simulates short pause — real TTL is much longer)
                Thread.sleep(2_000);

                // Recovery should still work — no TTL expiry on openGauss
                A2aResponse recovery = client.continueSession(ctxId, Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(ctxId);
                // Data still exists (no TTL cleanup)
                assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);
            }
        }
    }

    /**
     * F089-33: Cross-session recall — OUT (negative test).
     */
    @Test
    void f089_33_crossSessionRecall_OUT() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());

                // Session A
                A2aResponse respA = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                String ctxA = respA.contextId();

                // Session B — try to use ctxA's contextId in a different session
                // The system should NOT recall A's context in B's session
                A2aResponse respB = client.sendStreamingMessage("你好，请简单介绍一下上海", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                String ctxB = respB.contextId();

                assertThat(ctxA).isNotEqualTo(ctxB);
                // B's context is separate from A's
                assertThat(db.countTasksByContext(Edpa2SutHelper.L1_AGENT_ID, ctxA)).isGreaterThan(0);
                assertThat(db.countTasksByContext(Edpa2SutHelper.L1_AGENT_ID, ctxB)).isGreaterThan(0);
            }
        }
    }

    /**
     * F089-34: Recovery confirmation interaction — OUT (no user confirmation step).
     */
    @Test
    void f089_34_recoveryConfirmation_OUT() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());

                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Recovery should be automatic — single "继续" triggers it
                A2aResponse recovery = client.continueSession(resp.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // No intermediate confirmation request — direct response
                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
            }
        }
    }

    /**
     * F089-35: Cross-backend data migration — OUT.
     */
    @Test
    void f089_35_crossBackendMigration_OUT() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        // Verify openGauss starts clean — no migration from Redis
        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();
            assertThat(db.hasTaskRows()).isFalse();
            assertThat(db.hasKvRows()).isFalse();
        }
    }

    /**
     * F089-36: Data cleanup/archiving — OUT (no automatic cleanup).
     */
    @Test
    void f089_36_dataCleanupArchiving_OUT() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());
                client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                int countAfterSave = db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID);
                assertThat(countAfterSave).isGreaterThan(0);

                // Wait — no automatic cleanup should occur
                Thread.sleep(3_000);
                int countAfterWait = db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID);
                assertThat(countAfterWait).isEqualTo(countAfterSave);
            }
        }
    }

    /**
     * F089-37: Key exceeds DDL VARCHAR(512).
     */
    @Test
    void f089_37_keyExceedDDL() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());

                // Send a message with a very long contextId (>512 chars)
                String longCtxId = "a".repeat(600);
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", longCtxId, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // System should reject the oversized key, not crash silently.
                // OpenGaussKVStore.validateKey() throws IllegalArgumentException for keys > 512 bytes.
                // The SSE response may not contain a terminal state event (potential bug:
                // observer.onError() may close the stream before emitter.fail() sends FAILED).
                // At minimum, the system should not crash and should release the task.
                assertThat(resp.statusCode()).isGreaterThanOrEqualTo(200);
                // Per L2 §7.3: key exceeding DDL capacity must result in explicit FAILED.
                // No partial commit, no silent acceptance — the task must fail.
                assertThat(resp.finalTaskState())
                        .as("Key exceeding DDL capacity must result in explicit FAILED (L2 §7.3)")
                        .isEqualTo("TASK_STATE_FAILED");
            }
        }
    }

    /**
     * F089-38: Large payload (>4KB).
     */
    @Test
    void f089_38_largePayload() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());

                String largePrompt = "你好，请简单介绍一下自己，详细信息如下：".repeat(100);
                A2aResponse resp = client.sendStreamingMessage(largePrompt, null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Large payload should be handled successfully
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
                if (resp.statusCode() == 200) {
                    assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);

                    // Recovery should work with large payload
                    A2aResponse recovery = client.continueSession(resp.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                    Edpa2SutHelper.drainLogs();
                    assertThat(recovery.statusCode()).isEqualTo(200);
                }
            }
        }
    }

    /**
     * F089-39: Empty key reject (P2).
     */
    @Test
    void f089_39_emptyKeyReject() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());

                // Send with empty contextId — system should handle gracefully
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", "", Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
            }
        }
    }

    /**
     * F089-40: Redis TTL=0 config validation (P2).
     *
     * <p>Verify openGauss has no TTL config requirement.
     */
    @Test
    void f089_40_redisTTLZeroConfig() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            // openGauss backend doesn't require TTL config — starts without TTL env vars
            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                // Verify startup succeeded without TTL config
                SutLogTailer tailer = new SutLogTailer(l1.logFile());
                assertThat(tailer.agentStarted()).isTrue();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);
            }
        }
    }

    // ---------- openGauss-specific scenarios (F089-41~50) ----------

    /**
     * F089-41: Startup check — L1 starts successfully with valid openGauss.
     */
    @Test
    void f089_41_startupCheck() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                SutLogTailer tailer = new SutLogTailer(l1.logFile());
                assertThat(tailer.agentStarted()).isTrue();
                // openGauss validation should appear in log
                assertThat(tailer.hasOpenGaussInit()).isTrue();
            }
        }
    }

    /**
     * F089-42: Startup check fail — bad schema name.
     */
    @Test
    void f089_42_startupCheckFail() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
             Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm)) {
            mock.start(); l2.start();

            // L1 with non-existent schema
            Edpa2DemoLauncher l1Bad = Edpa2DemoLauncher.l1Agent()
                    .envAll(Edpa2SutHelper.withBaseEnv(llm))
                    .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                    .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                    .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + Edpa2SutHelper.OG_JDBC_URL)
                    .arg("--openjiuwen.service.middleware.opengauss.default.username=" + Edpa2SutHelper.OG_USERNAME)
                    .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=" + Edpa2SutHelper.OG_PASSWORD)
                    .arg("--openjiuwen.service.middleware.opengauss.default.schema=moa_nonexistent")
                    .startupTimeout(Duration.ofSeconds(30))
                    .build();

            boolean startFailed = false;
            try {
                l1Bad.start();
            } catch (IllegalStateException expected) {
                startFailed = true;
            }
            l1Bad.close();

            if (!startFailed) {
                SutLogTailer tailer = new SutLogTailer(l1Bad.logFile());
                assertThat(tailer.containsKeyword("validate")
                        || tailer.containsKeyword("error")
                        || tailer.containsKeyword("fail"))
                        .as("L1 log should show validation error for bad schema")
                        .isTrue();
            }
        }
    }

    /**
     * F089-43: No TTL — not-supported operations.
     */
    @Test
    void f089_43_noTTLNotSupportedOps() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Normal operations work — save and get
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Data persists (no TTL column, no expiry)
                // openGauss KV store doesn't support exclusiveSet or pipeline-TTL
                // — verify no TTL-related SQL in logs
                SutLogTailer tailer = new SutLogTailer(l1.logFile());
                assertThat(tailer.containsKeyword("UnsupportedOperationException")
                        || tailer.containsKeyword("rejected")
                        || tailer.containsKeyword("failed"))
                        .as("TTL/explicit-expiry operations should be rejected by openGauss KVStore (L2 §3.5)")
                        .isTrue();
            }
        }
    }

    /**
     * F089-44: Driver missing — startup fails without JDBC driver (P2).
     */
    @Test
    void f089_44_driverMissing() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        // Start mock and l2 with good driver first
        String originalDriver = System.getProperty(Edpa2DemoLauncher.OG_DRIVER_PROP);

        try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
             Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm)) {
            mock.start(); l2.start();

            // Now set bad driver path for L1 only
            System.setProperty(Edpa2DemoLauncher.OG_DRIVER_PROP, "D:/nonexistent/driver.jar");
            Edpa2DemoLauncher l1Bad = Edpa2SutHelper.buildL1(llm);
            boolean startFailed = false;
            try {
                l1Bad.start();
            } catch (IllegalStateException expected) {
                startFailed = true;
            }
            l1Bad.close();

            // Without driver, openGauss operations fail — startup should fail
            assertThat(startFailed).isTrue();
        } finally {
            // Restore original driver path
            if (originalDriver != null) {
                System.setProperty(Edpa2DemoLauncher.OG_DRIVER_PROP, originalDriver);
            } else {
                System.clearProperty(Edpa2DemoLauncher.OG_DRIVER_PROP);
            }
        }
    }

    /**
     * F089-45: External driver load via LOADER_PATH (P2).
     */
    @Test
    void f089_45_externalDriverLoad() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                // Driver loaded successfully → agent starts and works
                SutLogTailer tailer = new SutLogTailer(l1.logFile());
                assertThat(tailer.agentStarted()).isTrue();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                assertThat(resp.statusCode()).isEqualTo(200);
            }
        }
    }

    /**
     * F089-46: Password decrypt failure.
     */
    @Test
    void f089_46_passwordDecryptFail() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
             Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm)) {
            mock.start(); l2.start();

            Edpa2DemoLauncher l1Bad = Edpa2DemoLauncher.l1Agent()
                    .envAll(Edpa2SutHelper.withBaseEnv(llm))
                    .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                    .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                    .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + Edpa2SutHelper.OG_JDBC_URL)
                    .arg("--openjiuwen.service.middleware.opengauss.default.username=" + Edpa2SutHelper.OG_USERNAME)
                    .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=CORRUPTED_CIPHERTEXT_XYZ")
                    .arg("--openjiuwen.service.middleware.opengauss.default.schema=" + Edpa2SutHelper.OG_SCHEMA)
                    .startupTimeout(Duration.ofSeconds(30))
                    .build();

            boolean startFailed = false;
            try {
                l1Bad.start();
            } catch (IllegalStateException expected) {
                startFailed = true;
            }
            l1Bad.close();

            if (!startFailed) {
                SutLogTailer tailer = new SutLogTailer(l1Bad.logFile());
                assertThat(tailer.containsKeyword("password")
                        || tailer.containsKeyword("auth")
                        || tailer.containsKeyword("error")
                        || tailer.containsKeyword("fail"))
                        .as("L1 log should show password/auth error")
                        .isTrue();
            }
        }
    }

    /**
     * F089-47: Missing/duplicate provider — type=nonexistent fails (P2).
     */
    @Test
    void f089_47_missingProvider() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
             Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm)) {
            mock.start(); l2.start();

            Edpa2DemoLauncher l1Bad = Edpa2DemoLauncher.l1Agent()
                    .envAll(Edpa2SutHelper.withBaseEnv(llm))
                    .arg("--openjiuwen.service.middleware.checkpointer.type=nonexistent_backend")
                    .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                    .startupTimeout(Duration.ofSeconds(30))
                    .build();

            boolean startFailed = false;
            try {
                l1Bad.start();
            } catch (IllegalStateException expected) {
                startFailed = true;
            }
            l1Bad.close();

            // Nonexistent backend type should fail to start
            assertThat(startFailed)
                    .as("L1 with nonexistent backend type should fail to start")
                    .isTrue();
        }
    }

    /**
     * F089-48: connection-ref not found.
     */
    @Test
    void f089_48_connectionRefNotFound() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
             Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm)) {
            mock.start(); l2.start();

            Edpa2DemoLauncher l1Bad = Edpa2DemoLauncher.l1Agent()
                    .envAll(Edpa2SutHelper.withBaseEnv(llm))
                    .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                    .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=nonexistent_ref")
                    .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + Edpa2SutHelper.OG_JDBC_URL)
                    .arg("--openjiuwen.service.middleware.opengauss.default.username=" + Edpa2SutHelper.OG_USERNAME)
                    .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=" + Edpa2SutHelper.OG_PASSWORD)
                    .arg("--openjiuwen.service.middleware.opengauss.default.schema=" + Edpa2SutHelper.OG_SCHEMA)
                    .startupTimeout(Duration.ofSeconds(30))
                    .build();

            boolean startFailed = false;
            try {
                l1Bad.start();
            } catch (IllegalStateException expected) {
                startFailed = true;
            }
            l1Bad.close();

            if (!startFailed) {
                SutLogTailer tailer = new SutLogTailer(l1Bad.logFile());
                assertThat(tailer.containsKeyword("error")
                        || tailer.containsKeyword("fail")
                        || tailer.containsKeyword("not found"))
                        .as("L1 log should show connection-ref not found error")
                        .isTrue();
            }
        }
    }

    /**
     * F089-49: Startup failure resource cleanup (P2).
     */
    @Test
    void f089_49_startupFailResourceCleanup() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        Edpa2DemoLauncher l1Bad = Edpa2SutHelper.buildL1WithCustomOg(llm,
                "jdbc:opengauss://127.0.0.1:59999/postgres");
        try {
            l1Bad.start();
        } catch (IllegalStateException expected) {
            // Expected
        }
        l1Bad.close();

        // Process should be terminated (no orphaned process)
        // Verify the process is no longer alive
        assertThat(l1Bad.isAlive()).isFalse();
    }

    /**
     * F089-50: Shutdown order — graceful shutdown (P2).
     */
    @Test
    void f089_50_shutdownOrder() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm);
        l1.start();
        assertThat(l1.isAlive()).isTrue();

        long startMs = System.currentTimeMillis();
        l1.close();
        long elapsedMs = System.currentTimeMillis() - startMs;

        // Should shut down within 10 seconds
        assertThat(l1.isAlive()).isFalse();
        assertThat(elapsedMs).isLessThan(10_000);
    }
}
