/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.studio_dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.studio_dsl.OfflineAccessAuditFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingOpenAiEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingPluginMcpEndpointFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslHostControlClient;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslRedisBackedE2EBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslTestResources;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Standard A2A coverage for workflows assembled from Studio IR. */
@Tag("e2e")
@Tag("studio-dsl")
@Tag("feat-031")
@Tag("issue-399")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioDslRuntimeE2EIT extends StudioDslRedisBackedE2EBase {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @Story("ISSUE399.PR707: 会话变量经 RuntimeRedisClient 写入后同流读回")
    @DisplayName("PR707：真实 RuntimeRedisClient 支持会话变量写入与同流读回")
    void issue399SessionVariableRedisRoundTrip() throws Exception {
        StudioDslHostControlClient control = controlClient();
        String canary = "ISSUE399-CANARY-" + UUID.randomUUID();
        long logOffset = hostLogOffset();
        try {
            StudioDslHostControlClient.HostState loaded =
                    control.replace("issue399:session-var", sessionVarIr(canary), Map.of(), Map.of());
            assertThat(loaded.workflowId())
                    .as("会话变量 IR 必须注册成功（workflowId 与宿主默认 IR 不同，避免 resource already exist）")
                    .isEqualTo("wf-issue399-session-var");
            assertThat(loaded.componentIds())
                    .containsExactlyInAnyOrder("node_start", "node_echo", "node_set_variable", "node_end");

            A2aServiceClient client = client("studio-dsl-ir-sit");
            InteractionFlow.of(client)
                    .withContextId("issue399-pr707-" + System.nanoTime())
                    .send("ignored-input")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertGenerated(text -> assertThat(text)
                            .as("SetVariable 写入的会话变量必须被下游节点在同一次执行内读回")
                            .contains(canary))
                    .execute();

            assertThat(readHostLogSince(logOffset))
                    .as("配置 Redis 时不得出现 :6379 自建连接或静默读空告警")
                    .doesNotContain(":6379")
                    .doesNotContain("Failed to get session vals")
                    .doesNotContain("Failed to save session vals");
        } finally {
            control.reset();
        }
    }

    /** 本轮 Studio DSL 宿主标准输出（ProcessLauncher：{@code <sut.logging.dir>/<agent>/stdout.log}）。 */
    private static Path hostLog() {
        return Path.of("target/sit-logs/studio-dsl-ir-sit/stdout.log");
    }

    /** 记录本轮起点，避免历史运行日志干扰「不得出现 :6379」断言。 */
    private static long hostLogOffset() throws IOException {
        Path log = hostLog();
        return Files.exists(log) ? Files.size(log) : 0L;
    }

    private static String readHostLogSince(long offset) throws IOException {
        Path log = hostLog();
        if (!Files.exists(log)) {
            return "";
        }
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long start = Math.min(offset, file.length());
            file.seek(start);
            byte[] buffer = new byte[(int) (file.length() - start)];
            file.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    /** 载入 ISSUE399 会话变量 IR，并把占位 canary 替换为本轮唯一值。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sessionVarIr(String canary) throws Exception {
        String json = MAPPER.writeValueAsString(StudioDslTestResources.map("issue399-session-var-ir.json"))
                .replace("__CANARY__", canary);
        return MAPPER.readValue(json, Map.class);
    }

    @Test
    @Story("RT-031-04-15: 运行期完全离线")
    @DisplayName("宿主运行期不回源且不携带 fetch 模块")
    void runWithoutStudioObsOrFetchClasspath() throws Exception {
        OfflineAccessAuditFixture.JarAudit jarAudit;
        try {
            jarAudit = OfflineAccessAuditFixture.inspectExecutableJar(
                    OfflineAccessAuditFixture.configuredAgentJar(config, "studio-dsl-ir-sit"));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("cannot audit configured studio-dsl-ir-sit artifact", exception);
        }
        assertThat(jarAudit.containsFetchModule()).isFalse();

        StudioDslHostControlClient control = controlClient();
        Map<String, Object> offlineIr = StudioDslTestResources.map("runtime-version-v1.json");
        StudioDslHostControlClient.HostState loaded = control.replace(
                "rt-031-04-15-offline-bundle", offlineIr, Map.of(), Map.of());
        Path deliveredBundle = StudioDslTestResources.copy(
                "runtime-version-v1.json", tempDir.resolve("delivered-bundle/ir/workflow.json"));

        try (RecordingOpenAiEndpointFixture studioEndpoint = new RecordingOpenAiEndpointFixture();
                RecordingPluginMcpEndpointFixture obsEndpoint = new RecordingPluginMcpEndpointFixture()) {
            Map<String, java.util.function.IntSupplier> counters = Map.of(
                    "studio", studioEndpoint::requestCount,
                    "obs", obsEndpoint::requestCount);
            OfflineAccessAuditFixture.AuditSnapshot before =
                    OfflineAccessAuditFixture.snapshot(counters, List.of(deliveredBundle.getParent()));

            invokeAndExpect("rt-031-04-15", "ignored-offline-input", "rt-031-04-15-offline-canary");

            OfflineAccessAuditFixture.AuditSnapshot after =
                    OfflineAccessAuditFixture.snapshot(counters, List.of(deliveredBundle.getParent()));
            StudioDslHostControlClient.HostState invoked = control.status();
            assertThat(invoked.loadCount()).isEqualTo(loaded.loadCount());
            assertThat(invoked.queryCount()).isEqualTo(loaded.queryCount());
            assertThat(invoked.streamQueryCount()).isEqualTo(loaded.streamQueryCount() + 1);
            assertThat(after.endpointIncrementsFrom(before))
                    .containsOnly(Map.entry("studio", 0), Map.entry("obs", 0));
            assertThat(after.files()).isEqualTo(before.files());
        } finally {
            control.reset();
        }
    }

    @Test
    @Story("RT-031-04-22: IR 版本显式替换")
    @DisplayName("重新加载并显式替换后标准调用只返回 v2 canary")
    void replaceValidatedAssemblyExplicitly() throws Exception {
        StudioDslHostControlClient control = controlClient();
        Map<String, Object> versionOne = StudioDslTestResources.map("runtime-version-v1.json");
        Map<String, Object> versionTwo = StudioDslTestResources.map("runtime-version-v2.json");
        StudioDslHostControlClient.HostState baseline = control.status();

        try {
            StudioDslHostControlClient.HostState v1 = control.replace(
                    "release:workflow/ir/wf-versioned/v1.json", versionOne, Map.of(), Map.of());
            assertRevision(v1, baseline.loadCount() + 1, "1", versionOne);
            invokeAndExpect("rt-031-04-22-v1", "ignored-v1-input", "rt-031-04-22-v1-canary");

            StudioDslHostControlClient.HostState v2 = control.replace(
                    "release:workflow/ir/wf-versioned/v2.json", versionTwo, Map.of(), Map.of());
            assertRevision(v2, v1.loadCount() + 1, "2", versionTwo);
            assertThat(v2.irSha256()).isNotEqualTo(v1.irSha256());
            A2aServiceClient client = client("studio-dsl-ir-sit");
            InteractionFlow.of(client)
                    .withContextId("rt-031-04-22-v2-" + System.nanoTime())
                    .send("ignored-v2-input")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertGenerated(text -> assertThat(text)
                            .contains("rt-031-04-22-v2-canary")
                            .doesNotContain("rt-031-04-22-v1-canary"))
                    .execute();

            StudioDslHostControlClient.HostState invoked = control.status();
            assertThat(invoked.loadCount()).isEqualTo(v2.loadCount());
            assertThat(invoked.streamQueryCount()).isEqualTo(v2.streamQueryCount() + 1);
        } finally {
            control.reset();
        }
    }

    private StudioDslHostControlClient controlClient() {
        return new StudioDslHostControlClient(stack.baseUrl("studio-dsl-ir-sit"));
    }

    private static void assertRevision(
            StudioDslHostControlClient.HostState state,
            long expectedLoadCount,
            String expectedVersion,
            Map<String, Object> ir) throws Exception {
        assertThat(state.workflowId()).isEqualTo("wf-versioned");
        assertThat(state.workflowVersion()).isEqualTo(expectedVersion);
        assertThat(state.loadCount()).isEqualTo(expectedLoadCount);
        assertThat(state.irSha256()).isEqualTo(sha256(MAPPER.writeValueAsString(ir)));
        // 宿主状态改用公开装配快照的 componentIds（不再读 core 内部图结构），断言集合语义不变。
        assertThat(state.componentIds())
                .containsExactlyInAnyOrder("node_start", "node_message", "node_end");
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void invokeAndExpect(String caseId, String input, String expectedText) {
        A2aServiceClient client = client("studio-dsl-ir-sit");
        InteractionFlow.of(client)
                .withContextId(caseId + "-" + System.nanoTime())
                .send(input)
                .awaitState(TaskState.TASK_STATE_COMPLETED)
                .assertGenerated(text -> assertThat(text).contains(expectedText))
                .execute();
    }
}
