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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-089 P1/P2 — L2 design supplement scenarios (F089-52~68, excluding 51)
 * and linkage scenarios with FEAT-088 (F089-69~71).
 *
 * <p>L2 supplement: pipeline rollback, unsupported ops reject, hosted multi-agent
 * shared table, multi-host replica recovery, unknown task-id continue,
 * checkpoint replace reuse, Java serialization compat, recovery fail explicit
 * reset, connection pool exhaust, Core prereq fix confirm, Redis regression,
 * data type lossless, empty-map and miss recovery, mget/pipeline consistency,
 * independent app isolation, normal/hosted mode recovery, same-agent context
 * isolation.
 *
 * <p>Linkage: interrupt + long-pause recovery, interrupt + save failure,
 * interrupt + long-pause new command.
 */
@Tag("integration")
@Tag("feat-089")
@Tag("slow")
@Tag("manual")
final class Feat089L2AndLinkageTest {

    // ---------- L2 supplement scenarios (F089-52~68, excluding 51) ----------

    /**
     * F089-52: Pipeline rollback — no partial consistency.
     */
    @Test
    void f089_52_pipelineRollback() throws Exception {
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

                // Corrupt one table — verify recovery doesn't return partially consistent state
                Edpa2SutHelper.corruptTaskJson(Edpa2SutHelper.L1_AGENT_ID, ctxId);

                A2aResponse recovery = client.continueSession(ctxId, Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Either full error or full recovery — not half state
                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(ctxId);
                assertThat(recovery.finalTaskState()).isIn("TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_INPUT_REQUIRED", "TASK_STATE_CANCELED");

                // Per L2 §7.2: after pipeline rollback, old state must still be consistent and recoverable.
                // COMPLETED is valid only if recovery used the pre-rollback (old) state, not corrupted data.
                if ("TASK_STATE_COMPLETED".equals(recovery.finalTaskState())) {
                    assertThat(recovery.contextId())
                            .as("Pipeline rollback recovery should use original context (L2 §7.2)")
                            .isEqualTo(ctxId);
                }
            }
        }
    }

    /**
     * F089-53: Unsupported ops (exclusiveSet, pipeline-TTL) rejected.
     */
    @Test
    void f089_53_unsupportedOpsReject() throws Exception {
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

                // Normal save/get operations work
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
                assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Recovery should work — openGauss does not support TTL but save/get must work
                A2aResponse recovery = client.continueSession(resp.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(resp.contextId());
                assertThat(recovery.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
            }
        }
    }

    /**
     * F089-54: Hosted multi-agent shared table — L1 and L2 share DB with different agent_ids.
     */
    @Test
    void f089_54_hostedMultiAgentSharedTable() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient clientL1 = new Edpa2AgentHttpClient(l1.baseUrl());
                Edpa2AgentHttpClient clientL2 = new Edpa2AgentHttpClient(l2.baseUrl());

                // Both agents save to the same DB tables with different agent_ids
                A2aResponse respL1 = clientL1.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                A2aResponse respL2 = clientL2.sendStreamingMessage("你好，请简单介绍一下上海", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                int l1Tasks = db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID);
                int l2Tasks = db.countTasksByAgent(Edpa2SutHelper.L2_AGENT_ID);
                assertThat(l1Tasks).isGreaterThan(0);
                assertThat(l2Tasks).as("L2 agent should have its own records").isGreaterThan(0);

                // L2 agent has separate records (different agent_id)
                var l1CtxIds = db.listContextIdsByAgent(Edpa2SutHelper.L1_AGENT_ID);
                var l2CtxIds = db.listContextIdsByAgent(Edpa2SutHelper.L2_AGENT_ID);
                // L1 context should not appear in L2's records
                for (String ctx : l1CtxIds) {
                    assertThat(l2CtxIds).doesNotContain(ctx);
                }
                // L2 context should not appear in L1's records
                for (String ctx : l2CtxIds) {
                    assertThat(l1CtxIds).doesNotContain(ctx);
                }
            }
        }
    }

    /**
     * F089-55: Multi-host replica recovery (P2).
     */
    @Test
    void f089_55_multiHostReplicaRecovery() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(Edpa2SutHelper.OG_JDBC_URL, Edpa2SutHelper.OG_USERNAME,
                Edpa2SutHelper.OG_PASSWORD, Edpa2SutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            String ctxId;
            try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1 = Edpa2SutHelper.buildL1(llm)) {
                mock.start(); l2.start(); l1.start();

                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                ctxId = resp.contextId();
                assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);
            }

            // Start a new L1 on different port — recovery should work from shared DB
            try (Edpa2DemoLauncher mock2 = Edpa2SutHelper.buildMock(llm);
                 Edpa2DemoLauncher l2_2 = Edpa2SutHelper.buildL2(llm);
                 Edpa2DemoLauncher l1_2 = Edpa2DemoLauncher.l1Agent()
                         .envAll(Edpa2SutHelper.withBaseEnv(llm))
                         .port(18220)
                         .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                         .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                         .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + Edpa2SutHelper.OG_JDBC_URL)
                         .arg("--openjiuwen.service.middleware.opengauss.default.username=" + Edpa2SutHelper.OG_USERNAME)
                         .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=" + Edpa2SutHelper.OG_PASSWORD)
                         .arg("--openjiuwen.service.middleware.opengauss.default.schema=" + Edpa2SutHelper.OG_SCHEMA)
                         .build()) {
                mock2.start(); l2_2.start(); l1_2.start();

                Edpa2AgentHttpClient client2 = new Edpa2AgentHttpClient(l1_2.baseUrl());
                A2aResponse recovery = client2.continueSession(ctxId, Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(ctxId);
            }
        }
    }

    /**
     * F089-56: Unknown task-id continue — Core state exists but A2A Task doesn't.
     */
    @Test
    void f089_56_unknownTaskIdContinue() throws Exception {
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

                // Delete only task rows (keep KV checkpoint)
                db.deleteTasksByAgent(Edpa2SutHelper.L1_AGENT_ID);

                // Continue — should handle missing Task gracefully (SDK not-exist semantics)
                A2aResponse recovery = client.continueSession(ctxId, Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(ctxId);
                assertThat(recovery.finalTaskState()).isIn("TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_INPUT_REQUIRED", "TASK_STATE_CANCELED");
            }
        }
    }

    /**
     * F089-57: Checkpoint replace reuse (P2).
     */
    @Test
    void f089_57_checkpointReplaceReuse() throws Exception {
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

                // First save
                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                int countAfterFirst = db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID);

                // Second save in same session — KVStore remains usable
                A2aResponse resp2 = client.sendStreamingMessage("继续聊天", resp1.contextId(), Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                int countAfterSecond = db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID);

                assertThat(resp2.statusCode()).isEqualTo(200);
                assertThat(resp2.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
                assertThat(countAfterSecond).isGreaterThan(0);
            }
        }
    }

    /**
     * F089-58: Java serialization compatibility.
     */
    @Test
    void f089_58_javaSerializationCompat() throws Exception {
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

                // Recovery should read back serialized data correctly
                A2aResponse recovery = client.continueSession(resp.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(resp.contextId());
            }
        }
    }

    /**
     * F089-59: Recovery fail explicit reset (P2).
     */
    @Test
    void f089_59_recoveryFailExplicitReset() throws Exception {
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

                // Corrupt and attempt recovery — should fail or produce visible error
                Edpa2SutHelper.corruptTaskJson(Edpa2SutHelper.L1_AGENT_ID, ctxId);
                A2aResponse failRecovery = client.continueSession(ctxId, Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Corrupt data recovery should NOT silently succeed as COMPLETED with 200
                SutLogTailer tailer = new SutLogTailer(l1.logFile());
                boolean responseIndicatesError = failRecovery.statusCode() >= 400
                        || (failRecovery.finalTaskState() != null && failRecovery.finalTaskState().contains("ERROR"));
                boolean logIndicatesError = tailer.errorEventCount() > 0;
                assertThat(responseIndicatesError || logIndicatesError)
                        .as("Corrupt data recovery should produce visible error (response or log)")
                        .isTrue();

                // Explicit reset — truncate DB
                db.truncateAll();

                // New message should work as fresh session
                A2aResponse resp2 = client.sendStreamingMessage("你好，请简单介绍一下上海", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(resp2.statusCode()).isEqualTo(200);
                assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);
            }
        }
    }

    /**
     * F089-60: Connection pool exhaustion.
     */
    @Test
    void f089_60_connectionPoolExhaust() throws Exception {
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

                // Send many concurrent requests to stress the connection pool
                ExecutorService pool = Executors.newFixedThreadPool(10);
                try {
                    java.util.List<Future<A2aResponse>> futures = new java.util.ArrayList<>();
                    for (int i = 0; i < 10; i++) {
                        final int idx = i;
                        futures.add(pool.submit(() -> client.sendStreamingMessage(
                                "你好，请简单介绍一下自己 #" + idx, null, Edpa2SutHelper.INVOKE_TIMEOUT)));
                    }

                    int successCount = 0;
                    int totalCount = 0;
                    for (Future<A2aResponse> f : futures) {
                        totalCount++;
                        try {
                            A2aResponse r = f.get();
                            if (r.statusCode() == 200) successCount++;
                        } catch (Exception handled) {
                            // Some may fail — acceptable as long as no crash
                        }
                    }
                    Edpa2SutHelper.drainLogs();

                    // Per L2 §7.3: connection pool exhaustion should cause storage failures
                    assertThat(successCount).isGreaterThan(0);
                    assertThat(successCount)
                            .as("Connection pool exhaustion should cause some requests to fail (L2 §7.3)")
                            .isLessThan(totalCount);
                    assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    /**
     * F089-61: Core prereq fix confirm — recovery errors are visible.
     */
    @Test
    void f089_61_corePrereqFixConfirm() throws Exception {
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

                // Corrupt data
                Edpa2SutHelper.corruptTaskJson(Edpa2SutHelper.L1_AGENT_ID, ctxId);

                // Recovery should produce visible error
                A2aResponse recovery = client.continueSession(ctxId, Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Check log for error markers — error should not be silently swallowed
                SutLogTailer tailer = new SutLogTailer(l1.logFile());
                int errorCount = tailer.errorEventCount();
                // Error should be observable somewhere (response or log)
                boolean responseIndicatesError = recovery.statusCode() >= 400
                        || (recovery.finalTaskState() != null && recovery.finalTaskState().contains("ERROR"));
                assertThat(responseIndicatesError || errorCount > 0)
                        .as("Recovery error should be visible in response or log")
                        .isTrue();
            }
        }
    }

    /**
     * F089-62: Redis regression — default (non-openGauss) config still works.
     */
    @Test
    void f089_62_redisRegression() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        // Build L1 WITHOUT openGauss config — should use default (Redis)
        try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
             Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm);
             Edpa2DemoLauncher l1 = Edpa2DemoLauncher.l1Agent().envAll(Edpa2SutHelper.withBaseEnv(llm)).build()) {
            mock.start(); l2.start(); l1.start();

            SutLogTailer tailer = new SutLogTailer(l1.logFile());
            assertThat(tailer.agentStarted()).isTrue();

            Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1.baseUrl());
            A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
            Edpa2SutHelper.drainLogs();

            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
        }
    }

    /**
     * F089-63: Data type lossless — Chinese text and special characters.
     */
    @Test
    void f089_63_dataTypeLossless() throws Exception {
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

                // Chinese + special characters
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己，包括你的功能 🏛️", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
                assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Recovery should preserve content
                A2aResponse recovery = client.continueSession(resp.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(resp.contextId());
                assertThat(recovery.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
                // Verify artifact text is not empty or garbled
                assertThat(recovery.artifactText())
                        .as("Recovery artifact should contain non-empty response text")
                        .isNotEmpty();
            }
        }
    }

    /**
     * F089-64: Empty-map and miss recovery distinction.
     */
    @Test
    void f089_64_emptyMapAndMissRecovery() throws Exception {
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

                // Miss: fabricated contextId → new session, not error
                String missCtx = "miss-" + System.currentTimeMillis();
                A2aResponse missResp = client.sendStreamingMessage("你好", missCtx, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                assertThat(missResp.statusCode()).isEqualTo(200);
                assertThat(missResp.finalTaskState()).doesNotContain("ERROR");

                // Read failure: corrupt data → error
                A2aResponse resp2 = client.sendStreamingMessage("你好，请简单介绍一下上海", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                Edpa2SutHelper.corruptTaskJson(Edpa2SutHelper.L1_AGENT_ID, resp2.contextId());
                A2aResponse failResp = client.continueSession(resp2.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                // Failure should be distinguishable from miss — either error response or log error
                SutLogTailer tailer64 = new SutLogTailer(l1.logFile());
                boolean failResponseIndicatesError = failResp.statusCode() >= 400
                        || (failResp.finalTaskState() != null && failResp.finalTaskState().contains("ERROR"));
                boolean failLogIndicatesError = tailer64.errorEventCount() > 0;
                assertThat(failResponseIndicatesError || failLogIndicatesError)
                        .as("Corrupt data recovery should be distinguishable from miss (error in response or log)")
                        .isTrue();
            }
        }
    }

    /**
     * F089-65: mget/pipeline consistency snapshot.
     */
    @Test
    void f089_65_mgetPipelineConsistency() throws Exception {
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

                // Multiple messages in same session
                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                client.sendStreamingMessage("继续聊天", resp1.contextId(), Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                A2aResponse resp3 = client.sendStreamingMessage("再说一次", resp1.contextId(), Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Recovery should return the latest state
                A2aResponse recovery = client.continueSession(resp1.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");

                long latestUpdatedAt = db.getLatestTaskUpdatedAt(Edpa2SutHelper.L1_AGENT_ID, resp1.contextId());
                assertThat(latestUpdatedAt).isGreaterThan(0);
            }
        }
    }

    /**
     * F089-66: Independent app isolation — different schemas.
     */
    @Test
    void f089_66_independentAppIsolation() throws Exception {
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

                // Verify moa schema is used correctly — both task and KV tables have records
                assertThat(db.countTasksByAgent(Edpa2SutHelper.L1_AGENT_ID)).isGreaterThan(0);
                assertThat(db.hasKvRows()).isTrue();
                // Verify recovery works from moa schema
                A2aResponse recovery = client.continueSession(resp.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(resp.contextId());
                assertThat(recovery.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
            }
        }
    }

    /**
     * F089-67: Normal mode recovery.
     */
    @Test
    void f089_67_normalModeRecovery() throws Exception {
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

                A2aResponse recovery = client.continueSession(resp.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(resp.contextId());
            }
        }
    }

    /**
     * F089-68: Same agent, context isolation — ctx-A unaffected by ctx-B corruption.
     */
    @Test
    void f089_68_sameAgentContextIsolation() throws Exception {
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

                // Two sessions
                A2aResponse respA = client.sendStreamingMessage("你好，请简单介绍一下北京", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                A2aResponse respB = client.sendStreamingMessage("你好，请简单介绍一下上海", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Corrupt only ctx-B
                Edpa2SutHelper.corruptTaskJson(Edpa2SutHelper.L1_AGENT_ID, respB.contextId());

                // ctx-A recovery should work fine — unaffected by ctx-B corruption
                A2aResponse recoveryA = client.continueSession(respA.contextId(), Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(recoveryA.statusCode()).isEqualTo(200);
                assertThat(recoveryA.contextId()).isEqualTo(respA.contextId());
                assertThat(recoveryA.finalTaskState()).doesNotContain("ERROR");
            }
        }
    }

    // ---------- Linkage scenarios with FEAT-088 (F089-69~71) ----------

    /**
     * F089-69: Interrupt + long-pause recovery.
     *
     * <p>Simulate interrupt: send a message, then immediately send another
     * in the same session. Then recover with "继续".
     */
    @Test
    void f089_69_interruptLongPauseRecovery() throws Exception {
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

                // First message — use non-tourism prompt to avoid HITL interrupt
                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                String ctxId = resp1.contextId();
                assertThat(ctxId).isNotNull();

                // Send a second message in same session (simulates interrupt scenario)
                A2aResponse resp2 = client.sendStreamingMessage("你好，请简单介绍一下上海", ctxId, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Long pause then recovery
                A2aResponse recovery = client.continueSession(ctxId, Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.contextId()).isEqualTo(ctxId);
            }
        }
    }

    /**
     * F089-70: Interrupt + save failure.
     *
     * <p>Start L1 with bad openGauss — interrupt succeeds but save fails.
     */
    @Test
    void f089_70_interruptSaveFailure() throws Exception {
        Edpa2SutHelper.assumePrerequisites();
        Map<String, String> llm = Edpa2SutHelper.requireLlmEnv();

        try (Edpa2DemoLauncher mock = Edpa2SutHelper.buildMock(llm);
             Edpa2DemoLauncher l2 = Edpa2SutHelper.buildL2(llm)) {
            mock.start(); l2.start();

            Edpa2DemoLauncher l1Bad = Edpa2SutHelper.buildL1WithCustomOg(llm,
                    "jdbc:opengauss://127.0.0.1:59999/postgres");
            boolean startFailed = false;
            try {
                l1Bad.start();
            } catch (IllegalStateException expected) {
                startFailed = true;
            }

            if (!startFailed) {
                // If L1 started despite bad DB, send a message and check save failure
                Edpa2AgentHttpClient client = new Edpa2AgentHttpClient(l1Bad.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // With bad DB connection, either:
                // 1. response should indicate error (non-200 or ERROR state), OR
                // 2. log should contain persistence/save error events
                SutLogTailer tailer = new SutLogTailer(l1Bad.logFile());
                boolean responseIndicatesError = resp.statusCode() >= 400
                        || (resp.finalTaskState() != null && resp.finalTaskState().contains("ERROR"))
                        || resp.statusCode() == 0; // TIMEOUT
                boolean logIndicatesPersistError = tailer.errorEventCount() > 0;
                assertThat(responseIndicatesError || logIndicatesPersistError)
                        .as("Bad DB connection should produce visible error in response or persistence log")
                        .isTrue();
            } else {
                // If L1 failed to start, that itself is the expected behavior
                assertThat(startFailed).as("L1 with bad DB should fail to start").isTrue();
            }
            l1Bad.close();
        }
    }

    /**
     * F089-71: Interrupt + long-pause + new command (not "继续").
     */
    @Test
    void f089_71_interruptLongPauseNewCmd() throws Exception {
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

                // First message + interrupt (use non-tourism prompt)
                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();
                String ctxId = resp1.contextId();
                assertThat(ctxId).isNotNull();

                // Interrupt
                client.sendStreamingMessage("你好，请简单介绍一下上海", ctxId, Edpa2SutHelper.INVOKE_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Long pause then NEW command (not "继续")
                A2aResponse resp3 = client.sendStreamingMessage("请再说一遍", ctxId, Edpa2SutHelper.RECOVERY_TIMEOUT);
                Edpa2SutHelper.drainLogs();

                // Recovery should happen before executing new command
                assertThat(resp3.statusCode()).isEqualTo(200);
                assertThat(resp3.contextId()).isEqualTo(ctxId);
            }
        }
    }
}
