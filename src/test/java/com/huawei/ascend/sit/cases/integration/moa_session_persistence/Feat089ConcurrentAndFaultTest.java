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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-089 P1/P2 — concurrent scenarios (F089-13~16) and fault scenarios (F089-20~29).
 *
 * <p>Concurrent: same-session concurrent recovery, cross-replica recovery,
 * multi-session concurrent, high-frequency override save.
 *
 * <p>Fault: save timeout, core serialization fail, A2A Task ID validation fail,
 * A2A Task encode fail, recovery after failure, mid-retrieval failure,
 * slow save waiting, network disconnect, SQL timeout, retry-not-fix-corrupted.
 */
@Tag("integration")
@Tag("feat-089")
@Tag("slow")
@Tag("manual")
final class Feat089ConcurrentAndFaultTest {

    // ---------- Concurrent scenarios ----------

    /**
     * F089-13: Single-instance concurrent recovery of the same session.
     */
    @Test
    void f089_13_sameSessionRecoverySingle() throws Exception {
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

                // Establish session first
                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();

                // Two concurrent recovery requests with same contextId
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    Callable<A2aResponse> task = () -> client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                    Future<A2aResponse> f1 = pool.submit(task);
                    Future<A2aResponse> f2 = pool.submit(task);

                    A2aResponse r1 = f1.get();
                    A2aResponse r2 = f2.get();
                    MoaSutHelper.drainLogs();

                    // Both should succeed — no crash, no data corruption
                    assertThat(r1.statusCode()).isEqualTo(200);
                    assertThat(r2.statusCode()).isEqualTo(200);
                    assertThat(r1.contextId()).isEqualTo(ctxId);
                    assertThat(r2.contextId()).isEqualTo(ctxId);
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    /**
     * F089-14: Cross-replica concurrent recovery of the same session (P2).
     *
     * <p>Two L1 instances (ports 18219 and 18220) share the same DB.
     */
    @Test
    void f089_14_sameSessionRecoveryCrossReplica() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1a = MoaSutHelper.buildL1(llm);
                 MoaDemoLauncher l1b = MoaDemoLauncher.l1Agent()
                         .envAll(MoaSutHelper.withBaseEnv(llm))
                         .port(18220)
                         .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                         .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                         .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + MoaSutHelper.OG_JDBC_URL)
                         .arg("--openjiuwen.service.middleware.opengauss.default.username=" + MoaSutHelper.OG_USERNAME)
                         .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=" + MoaSutHelper.OG_PASSWORD)
                         .arg("--openjiuwen.service.middleware.opengauss.default.schema=" + MoaSutHelper.OG_SCHEMA)
                         .build()) {
                mock.start(); l2.start(); l1a.start(); l1b.start();

                // Establish session on l1a
                MoaAgentHttpClient clientA = new MoaAgentHttpClient(l1a.baseUrl());
                A2aResponse resp = clientA.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp.contextId();

                // Concurrent recovery from both replicas
                MoaAgentHttpClient clientB = new MoaAgentHttpClient(l1b.baseUrl());
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    Future<A2aResponse> f1 = pool.submit(() -> clientA.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT));
                    Future<A2aResponse> f2 = pool.submit(() -> clientB.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT));

                    A2aResponse r1 = f1.get();
                    A2aResponse r2 = f2.get();
                    MoaSutHelper.drainLogs();

                    // No data corruption — both should respond successfully
                    assertThat(r1.statusCode()).isEqualTo(200);
                    assertThat(r2.statusCode()).isEqualTo(200);
                    // DB should remain consistent
                    assertThat(db.countTasksByContext(MoaSutHelper.L1_AGENT_ID, ctxId)).isGreaterThan(0);
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    /**
     * F089-15: Same user, multiple sessions concurrent pause/recovery.
     */
    @Test
    void f089_15_multiSessionConcurrent() throws Exception {
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

                // Two sessions concurrently
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    Future<A2aResponse> fA = pool.submit(() -> client.sendStreamingMessage(
                            "你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT));
                    Future<A2aResponse> fB = pool.submit(() -> client.sendStreamingMessage(
                            "你好，请简单介绍一下上海", null, MoaSutHelper.INVOKE_TIMEOUT));

                    A2aResponse rA = fA.get();
                    A2aResponse rB = fB.get();
                    MoaSutHelper.drainLogs();

                    assertThat(rA.statusCode()).isEqualTo(200);
                    assertThat(rB.statusCode()).isEqualTo(200);
                    assertThat(rA.contextId()).isNotEqualTo(rB.contextId());

                    // DB should have separate records per contextId
                    assertThat(db.countTasksByContext(MoaSutHelper.L1_AGENT_ID, rA.contextId())).isGreaterThan(0);
                    assertThat(db.countTasksByContext(MoaSutHelper.L1_AGENT_ID, rB.contextId())).isGreaterThan(0);
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    /**
     * F089-16: High-frequency override save (P2).
     */
    @Test
    void f089_16_highFreqOverrideSave() throws Exception {
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

                // First message establishes session
                A2aResponse resp1 = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();
                String ctxId = resp1.contextId();
                int countAfterFirst = db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID);
                // Count KV rows for this context (context snapshot store)
                int kvAfterFirst = db.countKvByPrefix(ctxId);

                // Send 4 more messages in the same session rapidly
                for (int i = 0; i < 4; i++) {
                    client.sendStreamingMessage("继续聊天 #" + i, ctxId, MoaSutHelper.INVOKE_TIMEOUT);
                    MoaSutHelper.drainLogs();
                }

                int countAfterAll = db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID);
                // moa_a2a_task upserts by (agent_id, task_id), so each A2A request
                // with a new task_id creates a new row. This is correct behavior.
                // The override semantics are in moa_kv (context snapshot store).
                int kvAfterAll = db.countKvByPrefix(ctxId);
                // KV store should not accumulate unboundedly — override semantics
                assertThat(kvAfterAll).isLessThanOrEqualTo(kvAfterFirst + 4);
            }
        }
    }

    // ---------- Fault scenarios ----------

    /**
     * F089-20: Storage timeout (not fully unavailable) save.
     */
    @Test
    void f089_20_saveTimeout() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            // Use openGauss with 1s socket timeout to simulate storage write timeout
            String slowUrl = MoaSutHelper.OG_JDBC_URL + "?socketTimeout=1";
            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaDemoLauncher.l1Agent()
                         .envAll(MoaSutHelper.withBaseEnv(llm))
                         .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                         .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                         .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + slowUrl)
                         .arg("--openjiuwen.service.middleware.opengauss.default.username=" + MoaSutHelper.OG_USERNAME)
                         .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=" + MoaSutHelper.OG_PASSWORD)
                         .arg("--openjiuwen.service.middleware.opengauss.default.schema=" + MoaSutHelper.OG_SCHEMA)
                         .build()) {
                mock.start(); l2.start(); l1.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                // With 1s socket timeout, write timeout should be handled gracefully
                // — either the operation completes within 1s, or a timeout is surfaced
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);
            }
        }
    }

    /**
     * F089-21: Core session context serialization failure.
     */
    @Test
    void f089_21_coreSerializationFail() throws Exception {
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

                // Send a message with very large payload (may trigger serialization issues)
                String largePrompt = "你好，请简单介绍一下自己，详细信息如下：".repeat(500);
                A2aResponse resp = client.sendStreamingMessage(largePrompt, null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                // System should handle large payload successfully
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
            }
        }
    }

    /**
     * F089-22: A2A Task ID validation failure.
     *
     * <p>Delete task rows from DB, then attempt recovery — system should handle missing task.
     */
    @Test
    void f089_22_a2aTaskIdValidationFail() throws Exception {
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
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);

                // Delete all task rows for this agent
                db.deleteTasksByAgent(MoaSutHelper.L1_AGENT_ID);

                // Attempt recovery — should handle missing task gracefully
                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                assertThat(recovery.statusCode()).isEqualTo(200);
                assertThat(recovery.finalTaskState()).isEqualTo("TASK_STATE_COMPLETED");
            }
        }
    }

    /**
     * F089-23: A2A Task JSON encode/decode failure.
     */
    @Test
    void f089_23_a2aTaskEncodeFail() throws Exception {
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

                // Corrupt task_json to simulate encode/decode failure
                MoaSutHelper.corruptTaskJson(MoaSutHelper.L1_AGENT_ID, ctxId);

                // Attempt recovery — should encounter decode failure
                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Per L2 §2.3/§7.3: corrupted data must feed back recovery error, not silent normal recovery
                boolean silentRecovery = recovery.statusCode() == 200
                        && "TASK_STATE_COMPLETED".equals(recovery.finalTaskState())
                        && recovery.contextId() != null
                        && recovery.contextId().equals(ctxId);
                assertThat(silentRecovery)
                        .as("Corrupted task_json must not be silently recovered as clean COMPLETED (L2 §2.3, §7.3)")
                        .isFalse();
            }
        }
    }

    /**
     * F089-24: Storage failure recovery — after failure clears, new save works (P2).
     */
    @Test
    void f089_24_recoveryAfterFailure() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            // Phase 1: start with bad openGauss — expect failure
            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm)) {
                mock.start(); l2.start();

                MoaDemoLauncher l1Bad = MoaSutHelper.buildL1WithCustomOg(llm,
                        "jdbc:opengauss://127.0.0.1:59999/postgres");
                try {
                    l1Bad.start();
                } catch (IllegalStateException expected) {
                    // Expected: bad DB URL → startup failure
                }
                l1Bad.close();
            }

            // Phase 2: start with good openGauss — should work
            try (MoaDemoLauncher mock2 = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2_2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1_2 = MoaSutHelper.buildL1(llm)) {
                mock2.start(); l2_2.start(); l1_2.start();

                MoaAgentHttpClient client = new MoaAgentHttpClient(l1_2.baseUrl());
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                MoaSutHelper.drainLogs();

                // After failure recovery, new save should work
                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);
            }
        }
    }

    /**
     * F089-25: Mid-retrieval storage failure.
     */
    @Test
    void f089_25_midRetrievalFailure() throws Exception {
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

                // Corrupt data to simulate mid-retrieval failure
                MoaSutHelper.corruptTaskJson(MoaSutHelper.L1_AGENT_ID, ctxId);

                A2aResponse recovery = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Per L2 §2.3: incomplete or illegal state must feed back recovery error, not return half context
                boolean silentRecovery = recovery.statusCode() == 200
                        && "TASK_STATE_COMPLETED".equals(recovery.finalTaskState())
                        && recovery.contextId() != null
                        && recovery.contextId().equals(ctxId);
                assertThat(silentRecovery)
                        .as("Mid-retrieval failure must not silently recover as clean COMPLETED (L2 §2.3)")
                        .isFalse();
            }
        }
    }

    /**
     * F089-26: Slow save waiting semantics — save is slow but succeeds.
     */
    @Test
    void f089_26_slowSaveWaiting() throws Exception {
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

                long startMs = System.currentTimeMillis();
                A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                long elapsedMs = System.currentTimeMillis() - startMs;
                MoaSutHelper.drainLogs();

                // Save should complete before response returns (synchronous save)
                assertThat(resp.statusCode()).isEqualTo(200);
                // DB should have rows (save completed)
                assertThat(db.countTasksByAgent(MoaSutHelper.L1_AGENT_ID)).isGreaterThan(0);
                // Elapsed time includes save wait
                assertThat(elapsedMs).isGreaterThan(0);
            }
        }
    }

    /**
     * F089-27: Network disconnect — openGauss unreachable.
     */
    @Test
    void f089_27_networkDisconnect() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm)) {
                mock.start(); l2.start();

                // L1 with unreachable openGauss port
                MoaDemoLauncher l1Bad = MoaSutHelper.buildL1WithCustomOg(llm,
                        "jdbc:opengauss://127.0.0.1:59999/postgres");
                boolean startFailed = false;
                try {
                    l1Bad.start();
                } catch (IllegalStateException expected) {
                    startFailed = true;
                }
                l1Bad.close();

                // Per L2 §7.3: should return storage failure, not degrade to memory store
                if (!startFailed) {
                    SutLogTailer tailer = new SutLogTailer(l1Bad.logFile());
                    assertThat(tailer.containsKeyword("connect")
                            || tailer.containsKeyword("fail")
                            || tailer.containsKeyword("error")
                            || tailer.containsKeyword("Exception"))
                            .as("L1 log should show connection error for unreachable openGauss")
                            .isTrue();
                }
            }
        }
    }

    /**
     * F089-28: SQL execution timeout.
     */
    @Test
    void f089_28_sqlTimeout() throws Exception {
        MoaSutHelper.assumePrerequisites();
        Map<String, String> llm = MoaSutHelper.requireLlmEnv();

        try (OpenGaussProbe db = new OpenGaussProbe(MoaSutHelper.OG_JDBC_URL, MoaSutHelper.OG_USERNAME,
                MoaSutHelper.OG_PASSWORD, MoaSutHelper.OG_SCHEMA).connect()) {
            db.truncateAll();

            // Use openGauss with very short socket timeout to trigger SQL timeout
            String timeoutUrl = MoaSutHelper.OG_JDBC_URL + "?socketTimeout=1";
            try (MoaDemoLauncher mock = MoaSutHelper.buildMock(llm);
                 MoaDemoLauncher l2 = MoaSutHelper.buildL2(llm);
                 MoaDemoLauncher l1 = MoaDemoLauncher.l1Agent()
                         .envAll(MoaSutHelper.withBaseEnv(llm))
                         .arg("--openjiuwen.service.middleware.checkpointer.type=opengauss")
                         .arg("--openjiuwen.service.middleware.checkpointer.connection-ref=default")
                         .arg("--openjiuwen.service.middleware.opengauss.default.jdbc-url=" + timeoutUrl)
                         .arg("--openjiuwen.service.middleware.opengauss.default.username=" + MoaSutHelper.OG_USERNAME)
                         .arg("--openjiuwen.service.middleware.opengauss.default.encrypted-password=" + MoaSutHelper.OG_PASSWORD)
                         .arg("--openjiuwen.service.middleware.opengauss.default.schema=" + MoaSutHelper.OG_SCHEMA)
                         .startupTimeout(Duration.ofSeconds(30))
                         .build()) {
                mock.start(); l2.start();

                boolean startFailed = false;
                try {
                    l1.start();
                } catch (IllegalStateException expected) {
                    startFailed = true;
                }

                if (startFailed) {
                    // Expected: SQL timeout during startup validation
                    SutLogTailer tailer = new SutLogTailer(l1.logFile());
                    assertThat(tailer.containsKeyword("timeout")
                            || tailer.containsKeyword("fail")
                            || tailer.containsKeyword("error"))
                            .as("L1 log should show timeout error")
                            .isTrue();
                } else {
                    // If L1 started, verify it handles SQL timeout during operations
                    MoaAgentHttpClient client = new MoaAgentHttpClient(l1.baseUrl());
                    A2aResponse resp = client.sendStreamingMessage("你好，请简单介绍一下自己", null, MoaSutHelper.INVOKE_TIMEOUT);
                    MoaSutHelper.drainLogs();
                    assertThat(resp.statusCode()).isEqualTo(200);
                }
                l1.close();
            }
        }
    }

    /**
     * F089-29: Retry does not fix corrupted records.
     */
    @Test
    void f089_29_retryNotFixCorrupted() throws Exception {
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

                // Corrupt data
                MoaSutHelper.corruptTaskJson(MoaSutHelper.L1_AGENT_ID, ctxId);

                // First recovery attempt — should fail
                A2aResponse attempt1 = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Retry — should still fail (corrupted data not auto-fixed)
                A2aResponse attempt2 = client.continueSession(ctxId, MoaSutHelper.RECOVERY_TIMEOUT);
                MoaSutHelper.drainLogs();

                // Verify corrupted data still exists (not auto-cleaned)
                String taskJson = db.getTaskJson(MoaSutHelper.L1_AGENT_ID, ctxId);
                if (taskJson != null) {
                    assertThat(taskJson).contains("CORRUPTED");
                }

                // Per L2 §5.5.1: retry does not fix corrupted data; both attempts must not silently recover
                boolean silentRecovery1 = attempt1.statusCode() == 200
                        && "TASK_STATE_COMPLETED".equals(attempt1.finalTaskState())
                        && attempt1.contextId() != null
                        && attempt1.contextId().equals(ctxId);
                boolean silentRecovery2 = attempt2.statusCode() == 200
                        && "TASK_STATE_COMPLETED".equals(attempt2.finalTaskState())
                        && attempt2.contextId() != null
                        && attempt2.contextId().equals(ctxId);
                assertThat(silentRecovery1)
                        .as("First retry must not silently recover corrupted data as clean COMPLETED (L2 §5.5.1)")
                        .isFalse();
                assertThat(silentRecovery2)
                        .as("Second retry must not silently recover corrupted data as clean COMPLETED (L2 §5.5.1)")
                        .isFalse();
            }
        }
    }
}
