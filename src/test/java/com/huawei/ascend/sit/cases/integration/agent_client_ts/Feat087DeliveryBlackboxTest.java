package com.huawei.ascend.sit.cases.integration.agent_client_ts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087MockA2aServer.JSON;
import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087TsSdkHarness.invokeOp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-087 交付面与错误表面：npm 产物、同包同入口、稳定错误码透出。
 * 对应测试设计 F087-01 / F087-02 / F087-38。
 */
@Tag("integration")
@Tag("agent-client-ts")
@Tag("feat-087")
@Feature("FEAT-087: Agent-Client支持Node服务TS语言")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat087DeliveryBlackboxTest {

    private Feat087TsSdkHarness harness;

    @BeforeAll
    void setUp() {
        harness = new Feat087TsSdkHarness();
    }

    @BeforeEach
    void assumeSdkEnvironmentReady() {
        // 依赖门禁：SUT（TS SDK）未交付时逐用例 SKIPPED，不计 FAIL
        Feat087TsSdkHarness.assumeEnvironmentReady(harness.sdkDir());
        // 用例间隔离：清空 mock 规则（防 onEvery 持久规则泄漏）与 wire 记录
        harness.server().resetRules();
        harness.server().takeRequests();
    }

    @AfterAll
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private ObjectNode client(String conversationId) {
        return Feat087TsSdkHarness.clientSpec("GATEWAY", harness.server().url(), conversationId);
    }

    @Test
    @Tag("story-feat-087-01")
    @Tag("smoke-feat-087")
    @Story("FEAT-087.F087-01: npm 产物冒烟（CJS/ESM/Node18 无 crypto）")
    @DisplayName("F087-01 npm 产物含 ESM/CJS/类型声明且 ESM 调用闭环、CJS 可加载")
    void f08701NpmArtifactsLoadWithoutJavaDependency() throws Exception {
        Path dist = harness.sdkDir().resolve("dist");
        assertThat(Files.exists(dist)).as("dist 产物目录存在").isTrue();
        assertThat(Files.exists(harness.sdkDir().resolve("package.json"))).isTrue();

        // A1：npm 包携带 TS 类型声明，TS 项目可获得类型检查
        JsonNode pkgArtifacts = JSON.readTree(Files.readString(harness.sdkDir().resolve("package.json")));
        assertThat(pkgArtifacts.has("types") || pkgArtifacts.has("typings"))
                .as("package.json 声明 TS 类型入口").isTrue();
        try (java.util.stream.Stream<Path> typeDecls = Files.walk(dist)) {
            assertThat(typeDecls.filter(p -> p.getFileName().toString().endsWith(".d.ts")).count())
                    .as("dist 至少含一个 .d.ts 类型声明").isGreaterThanOrEqualTo(1);
        }

        // ESM 入口：driver 通过 package exports 加载并完成一次 STREAMING 调用
        harness.server().onMethod("SendStreamingMessage", r ->
                Feat087MockA2aServer.sse(List.of(
                        Feat087MockA2aServer.sseOf(Feat087MockA2aServer.rpcResult(
                                Feat087MockA2aServer.statusUpdate("task-d1", "conv-d1", "TASK_STATE_COMPLETED", "done", null))))));
        ObjectNode scenario = Feat087TsSdkHarness.scenario(client("conv-d1"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-d1", "state:COMPLETED")));
        ObjectNode result = harness.runDriver(scenario);
        assertThat(result.has("fatal")).as("driver 无 fatal：" + result.path("fatal").asText("")).isFalse();
        JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("taskState").asText()).isEqualTo("COMPLETED");

        // CJS 入口可被 require（不因 ESM-only 传递依赖报 ERR_REQUIRE_ESM）
        assertThat(cjsEntryLoads()).as("CJS 产物可 require").isTrue();
    }

    private boolean cjsEntryLoads() throws Exception {
        Path cjsEntry = null;
        for (String candidate : new String[] {"index.cjs", "index.js"}) {
            Path p = harness.sdkDir().resolve("dist/cjs").resolve(candidate);
            if (Files.exists(p)) {
                cjsEntry = p;
                break;
            }
        }
        if (cjsEntry == null) {
            return false;
        }
        String probe = "try { require(process.argv[1]); console.log('CJS_OK'); }"
                + " catch (e) { console.log('CJS_FAIL:' + e.code); }";
        Process p = new ProcessBuilder("node", "-e", probe, cjsEntry.toString()).start();
        p.waitFor(30, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes());
        return out.contains("CJS_OK");
    }

    @Test
    @Tag("story-feat-087-02")
    @Story("FEAT-087.F087-02: Node 与浏览器同包同入口")
    @DisplayName("F087-02 同一入口导出 createAgentClient 且 Node 完成 BLOCKING 调用")
    void f08702SameEntryForNodeAndBrowser() throws Exception {
        JsonNode pkg = JSON.readTree(Files.readString(harness.sdkDir().resolve("package.json")));
        assertThat(pkg.path("name").asText()).isEqualTo("@openjiuwen/agent-client");
        assertThat(pkg.path("exports").has(".")).as("exports 定义统一入口").isTrue();
        assertThat(pkg.path("engines").path("node").asText()).contains("18");

        harness.server().onEvery(b -> "SendMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.rpcResult(
                        Feat087MockA2aServer.task("task-d2", "conv-d2", "TASK_STATE_COMPLETED", "ok-text", null))));
        ObjectNode scenario = Feat087TsSdkHarness.scenario(client("conv-d2"),
                JSON.createArrayNode().add(invokeOp("BLOCKING", "hi", "inv-d2", "state:COMPLETED")));
        ObjectNode result = harness.runDriver(scenario);
        assertThat(result.has("fatal")).isFalse();
        assertThat(Feat087TsSdkHarness.opResult(result, 0).path("snapshot").path("finalText").asText())
                .isEqualTo("ok-text");

        // 浏览器直连分支（-Dfeat087.playwright=true 启用）：页面 import 同一入口 createAgentClient
        // 完成 BLOCKING 调用；浏览器内核用系统 Chrome/Edge（playwright-core channel，无内核下载）
        if (Boolean.getBoolean("feat087.playwright")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    Files.exists(Path.of("src/test/resources/feat087/node_modules/playwright-core")),
                    "playwright-core 未安装（src/test/resources/feat087 下 npm install），浏览器分支依赖门禁跳过");
            Process browser = new ProcessBuilder("node", "src/test/resources/feat087/browser-branch.mjs",
                    harness.server().url(), harness.sdkDir().toString().replace('\\', '/'))
                    .redirectErrorStream(true).start();
            String out = new String(browser.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean finished = browser.waitFor(60, TimeUnit.SECONDS);
            assertThat(finished).as("浏览器分支进程超时: " + out).isTrue();
            JsonNode line = null;
            for (String l : out.split("\n")) {
                if (l.isBlank()) {
                    continue;
                }
                try {
                    line = JSON.readTree(l);
                } catch (Exception e) {
                    // 非 NDJSON 行（浏览器 stderr 等）忽略
                }
            }
            assertThat(line).as("浏览器分支输出: " + out).isNotNull();
            assertThat(line.has("error")).as("浏览器分支无错误: " + out).isFalse();
            JsonNode webSnapshot = line.path("result");
            assertThat(webSnapshot.path("taskState").asText()).isEqualTo("COMPLETED");
            assertThat(webSnapshot.path("finalText").asText()).as("浏览器调用 finalText 非空").isNotEmpty();
        }
    }

    @Test
    @Tag("story-feat-087-38")
    @Story("FEAT-087.F087-38: 错误表面与稳定错误码")
    @DisplayName("F087-38 服务端稳定错误码原样透出且可重试标记正确")
    void f08738ServerErrorCodesSurfaceProgrammatically() {
        // 401 AUTH_INVALID → AUTH_FAILED / category AUTH / retryable=false
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(401, JSON.createObjectNode().put("code", "AUTH_INVALID")));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-e1"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-e1", "result"))));
        JsonNode err = Feat087TsSdkHarness.opResult(result, 0).path("snapshot").path("error");
        assertThat(err.path("code").asText()).isEqualTo("AUTH_FAILED");
        assertThat(err.path("category").asText()).isEqualTo("AUTH");
        assertThat(err.path("serverCode").asText()).isEqualTo("AUTH_INVALID");
        assertThat(err.path("retryable").asBoolean()).isFalse();

        // 409 IDEMPOTENCY_PAYLOAD_MISMATCH → category SERVER / retryable=false
        harness.server().onWhere(b -> "SendStreamingMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(409, JSON.createObjectNode().put("code", "IDEMPOTENCY_PAYLOAD_MISMATCH")));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-e2"),
                JSON.createArrayNode().add(invokeOp("STREAMING", "hi", "inv-e2", "result"))));
        err = Feat087TsSdkHarness.opResult(result, 0).path("snapshot").path("error");
        assertThat(err.path("serverCode").asText()).isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH");
        assertThat(err.path("retryable").asBoolean()).isFalse();

        // -32001 TASK_NOT_FOUND（JSON-RPC）→ 不新建 Task、observation=INTERRUPTED
        harness.server().onWhere(b -> "SendMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(200, Feat087MockA2aServer.jsonRpcError(-32001, "task not found", "TASK_NOT_FOUND")));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-e3"),
                JSON.createArrayNode().add(invokeOp("BLOCKING", "hi", "inv-e3", "result"))));
        JsonNode snapshot = Feat087TsSdkHarness.opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("error").path("serverCode").asText()).isEqualTo("TASK_NOT_FOUND");
        assertThat(snapshot.path("observation").asText()).isEqualTo("INTERRUPTED");

        // 429 RATE_LIMITED → category TRANSPORT / retryable=true
        // （持久规则：429 可重试，SDK 会按预算同键重发，每次均应命中 429）
        harness.server().onEvery(b -> "SendMessage".equals(b.path("method").asText()), r ->
                Feat087MockA2aServer.json(429, JSON.createObjectNode().put("code", "RATE_LIMITED")));
        result = harness.runDriver(Feat087TsSdkHarness.scenario(client("conv-e4"),
                JSON.createArrayNode().add(invokeOp("BLOCKING", "hi", "inv-e4", "result"))));
        err = Feat087TsSdkHarness.opResult(result, 0).path("snapshot").path("error");
        assertThat(err.path("category").asText()).isEqualTo("TRANSPORT");
        assertThat(err.path("serverCode").asText()).isEqualTo("RATE_LIMITED");
        assertThat(err.path("retryable").asBoolean()).isTrue();
    }
}
