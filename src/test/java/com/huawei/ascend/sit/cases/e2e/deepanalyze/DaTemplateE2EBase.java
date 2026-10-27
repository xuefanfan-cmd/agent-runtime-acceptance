/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.DynamicTeamTemplates;
import com.openjiuwen.agents.orchestration.SkipPolicy;
import io.qameta.allure.Allure;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 068-S10/S11（真机 e2e）共用基座——与 067 线 {@link DaOrchestrationE2EBase} 同型但<b>独立实现</b>：
 * SUT 钉本批自己的制品条目 {@code deepanalyze-feat068}（r3 回归起 = {@code 0.1.3-feat068-r3:exec}，
 * 源=common@cf4571b30（!860，内含 #462 观察层断链修复），指纹见 01-Design_File/20261019-feat-068-team-template/
 * evidence/20261019-build-feat068/ r3 段），证据不共用 067 线的 SUT 钉子，也不耦合其在途文件。
 *
 * <p>继承的已登记偏差（与 067 线同族，判据均为结构性、不受影响）：
 * <ul>
 *   <li>DEFAULT_TOOL_CALL_TIMEOUT=1800（本基座 env 注入）——产品默认 300s 下 shipped 模板不可达，已登记产品缺陷 D3；</li>
 *   <li>checkpointer in_memory 绕行（fat jar ServiceLoader TCCL 问题，产品缺陷 D4；撤除绑定 #465 回归轮，需 core 前移）；</li>
 *   <li>max-iterations=4（yml system-properties）——绕模型网关间歇超时；stream-idle 越界键已于 2026-10-21
 *       移除（docs#32 裁决：显式越界在 058 落地后启动即拒）。</li>
 * </ul>
 * 本轮 PASS 不得被读成"用户用默认配置也能跑通模板"（同 067 线口径）。
 *
 * <p>通道：compat 同步端点（{@code POST /api/agents/run} 聚合响应含 {@code toolCalls}）；
 * 模型未调用 {@code workflow_run} = L3 不确定性 ⇒ INCONCLUSIVE（不判 FAIL）；
 * 任务 failed = 产品侧失败 ⇒ FAIL（与模型未配合严格分流）。
 *
 * <p><b>2026-10-26（r5 起）权限确认续跑</b>：!873 权限引擎落线后，bash 类工具按治理裁定
 * permission=ask（governance/actrule.yaml，无配置开关），长任务可能以待答收场——产品设计交互。
 * {@link #resolvePermissionAsks} 沿设计面续跑（/answer 批准 → /stream 重放取 complete 聚合帧），
 * 快照与 /run 同形，下游断言不变。</p>
 */
abstract class DaTemplateE2EBase extends BaseManagedStackTest {

    protected static final String AGENT = "deepanalyze-feat068";
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    /** 真实模型成员 + 宿主启动，装置上限（非判据）。 */
    protected static final Duration RUN_TIMEOUT = Duration.ofMinutes(25);
    protected static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    protected static final String SESSION_HEADER = "X-Da-Session-Id";

    protected HttpClient http;
    protected String base;

    @Override
    protected SutStack.Builder buildStack(final TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    DaModelEnvironment.bind(agent);
                    DaModelEnvironment.bindModelKey(agent);
                    // 见类注释：D3 同族的已登记偏差（结构性判据不受影响）
                    agent.env("DEFAULT_TOOL_CALL_TIMEOUT", "1800");
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    protected void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            final String root = client(AGENT).getBaseUrl();
            assertThat(root).as("应能取到 SUT 的 base url").isNotBlank();
            base = root.endsWith("/") ? root + "api/agents" : root + "/api/agents";
        }
    }

    protected static String newSession(final String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** compat 同步端点跑一轮会话，返回 POST /run 的聚合响应（含 toolCalls）。 */
    protected JsonNode runAndSnapshot(final String sessionId, final String input) throws Exception {
        initClient();
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("input", input);
        final HttpRequest run = HttpRequest.newBuilder()
                .uri(URI.create(base + "/run"))
                .timeout(RUN_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(SESSION_HEADER, sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body),
                        StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> runResponse = http.send(run,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Allure.addAttachment("POST /api/agents/run 响应（截断 8KB）", "text/plain",
                truncate(runResponse.body(), 8192));
        assertThat(runResponse.statusCode())
                .as("同步执行应返回 200；实收 body=%s", truncate(runResponse.body(), 2048))
                .isEqualTo(200);
        return resolvePermissionAsks(MAPPER.readTree(runResponse.body()), sessionId);
    }

    /** 权限确认续跑上限（防循环；实测单任务 ask 数 ≤2 量级）。 */
    private static final int MAX_PERMISSION_ROUNDS = 3;

    /**
     * 2026-10-26（r5 起，随 !873 权限引擎落线）：治理裁定对 bash 类工具 permission=ask
     * （engine 治理资源 governance/actrule.yaml {@code tools.bash: ask}，场景合并只紧不松、无配置开关），
     * 长任务可能以待答（awaiting_answer）收场——这是<b>产品设计的交互面，不是缺陷</b>。
     * 测试侧沿设计面续跑：POST /answer/{taskId} 批准（缺省 toolCallId=回填最近一个待答，服务端组包
     * 结构化 {approved,feedback}）→ 轮询 /status/{taskId} 待记录离开待答 → 终态后经
     * GET /stream/{taskId} 重放取 complete 终帧（携带 output+toolCalls 聚合）组装与 /run 同形的快照。
     * 每轮 Allure 留证；上限 {@value MAX_PERMISSION_ROUNDS} 轮。
     */
    private JsonNode resolvePermissionAsks(final JsonNode initial, final String sessionId) throws Exception {
        JsonNode resp = initial;
        final String taskId = resp.path("taskId").asText();
        int round = 0;
        while ("awaiting_answer".equals(resp.path("status").asText()) && round++ < MAX_PERMISSION_ROUNDS) {
            Allure.addAttachment("权限确认待答（第 " + round + " 轮）响应", "text/plain",
                    truncate(resp.toString(), 2048));
            final HttpRequest answer = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/answer/" + taskId))
                    .timeout(RUN_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header(SESSION_HEADER, sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(Map.of("answer", "允许")), StandardCharsets.UTF_8))
                    .build();
            final HttpResponse<String> answered = http.send(answer,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Allure.addAttachment("POST /answer 批准（第 " + round + " 轮）", "text/plain",
                    truncate(answered.body(), 1024));
            assertThat(answered.statusCode())
                    .as("权限回答应受理（200）；实收=%s", truncate(answered.body(), 512))
                    .isEqualTo(200);
            resp = awaitContinuationSnapshot(taskId, sessionId);
        }
        return resp;
    }

    /** 轮询任务记录直到离开 awaiting_answer（completed/failed），返回末次 status 文本。 */
    private String pollUntilLeftAwaiting(final String taskId, final String sessionId) throws Exception {
        final long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
        String status = "awaiting_answer";
        while (System.nanoTime() < deadline) {
            status = taskStatus(taskId, sessionId);
            if (!"awaiting_answer".equals(status)) {
                return status;
            }
            Thread.sleep(5000L);
        }
        return status;
    }

    private String taskStatus(final String taskId, final String sessionId) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/status/" + taskId))
                .timeout(READ_TIMEOUT)
                .header(SESSION_HEADER, sessionId)
                .GET()
                .build();
        final HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return MAPPER.readTree(response.body()).path("status").asText();
    }

    /** 终态后组装与 /run 同形的快照：complete 终帧（output+toolCalls）＋记录 status 兜底。 */
    private JsonNode awaitContinuationSnapshot(final String taskId, final String sessionId) throws Exception {
        final String recordStatus = pollUntilLeftAwaiting(taskId, sessionId);
        final HttpResponse<InputStream> stream = DaCompatSseSupport.openStream(
                http, base, taskId, sessionId, null, RUN_TIMEOUT);
        final List<DaCompatSseSupport.Frame> frames = DaCompatSseSupport.readFrames(stream.body(),
                f -> "complete".equals(f.event()) || "done".equals(f.event()) || "error".equals(f.event()),
                RUN_TIMEOUT);
        JsonNode complete = null;
        boolean errorSeen = false;
        for (DaCompatSseSupport.Frame frame : frames) {
            if ("complete".equals(frame.event()) && frame.data() != null) {
                complete = MAPPER.readTree(frame.data());
            } else if ("error".equals(frame.event())) {
                errorSeen = true;
            }
        }
        final ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("taskId", taskId);
        snapshot.put("status", errorSeen ? "failed" : recordStatus);
        if (complete != null) {
            snapshot.set("output", complete.path("output"));
            snapshot.set("toolCalls", complete.path("toolCalls"));
        }
        Allure.addAttachment("权限续跑后快照", "text/plain", truncate(snapshot.toString(), 4096));
        return snapshot;
    }

    /** 取 workflow_run 调用记录；模型未调用 ⇒ INCONCLUSIVE（L3 不确定性）。 */
    protected JsonNode requireWorkflowRunCall(final JsonNode snapshot) {
        final JsonNode calls = snapshot.path("toolCalls");
        assertThat(calls.isArray()).as("聚合响应应含 toolCalls 数组；实收=%s",
                truncate(String.valueOf(snapshot), 2048)).isTrue();
        for (JsonNode call : calls) {
            if ("workflow_run".equals(call.path("toolName").asText())) {
                Allure.addAttachment("workflow_run 调用记录原文（截断 16KB）", "text/plain",
                        truncate(call.toString(), 16384));
                return call;
            }
        }
        final StringBuilder names = new StringBuilder();
        for (JsonNode call : calls) {
            names.append(call.path("toolName").asText()).append(' ');
        }
        Assumptions.abort("主 Agent 本轮未调用 workflow_run（模型未配合，属 L3 不确定性）；"
                + "实收 toolNames=[" + names.toString().trim() + "]；记 INCONCLUSIVE，不判 FAIL。");
        throw new IllegalStateException("unreachable");
    }

    /** 任务 failed = 产品侧失败 ⇒ FAIL（不得混记 INCONCLUSIVE）。 */
    protected void failIfTaskFailed(final JsonNode snapshot) {
        if (!"failed".equals(snapshot.path("status").asText())) {
            return;
        }
        throw new AssertionError("【产品侧失败，非模型未配合】任务以 failed 结束："
                + snapshot.path("error").asText());
    }

    /** 取 workflow_run 出参并断言成功形态。r3 线格式=JSON 对象（status/workflow_id/final_output/steps）
     * ——2026-10-21 e2e-07 实测锚定，取代 r2 时代自 067 run-16 携带的「ToolOutput(」文本形态假设
     * （该假设在 r2 期间因 GAP-068-01 从未见真实出参、从未被验证，属 Oracle 确认错误）。
     * 返回解析后的出参根节点。 */
    protected com.fasterxml.jackson.databind.JsonNode requireSuccessOutput(final JsonNode call) {
        final String raw = call.path("output").asText();
        assertThat(raw).as("workflow_run 的 tool_result 应回填 output（#462 修复后应非空）").isNotBlank();
        Allure.addAttachment("workflow_run 出参原文（截断 16KB）", "text/plain", truncate(raw, 16384));
        final com.fasterxml.jackson.databind.JsonNode out;
        try {
            out = MAPPER.readTree(raw);
        } catch (final Exception e) {
            throw new AssertionError("workflow_run 出参应为 JSON 对象（r3 线格式，e2e-07 实测锚定）；实收="
                    + truncate(raw, 1024), e);
        }
        assertThat(out.isObject()).as("出参应为 JSON 对象；实收=%s", truncate(raw, 512)).isTrue();
        assertThat(out.path("status").asText())
                .as("工具出参 status 应= completed（受控拒绝路径不走本辅助）；实收=%s", truncate(raw, 512))
                .isEqualTo("completed");
        assertThat(out.path("workflow_id").asText()).as("出参应带 workflow_id").isNotBlank();
        return out;
    }

    /** 一步留痕。 */
    protected record Step(String id, String member, boolean skipped, String branchId, String output) {
    }

    /** 从出参 JSON 的 steps 数组切出步骤留痕（r3 线格式：{id, member, skipped, output[, branch_id]}）。 */
    protected List<Step> extractSteps(final com.fasterxml.jackson.databind.JsonNode outputRoot) {
        final com.fasterxml.jackson.databind.JsonNode arr = outputRoot.path("steps");
        assertThat(arr.isArray())
                .as("出参应含 steps 数组；实收 keys=%s",
                        String.valueOf(outputRoot.fieldNames())).isTrue();
        final List<Step> steps = new ArrayList<>();
        for (final com.fasterxml.jackson.databind.JsonNode s : arr) {
            steps.add(new Step(s.path("id").asText(), s.path("member").asText(),
                    s.path("skipped").asBoolean(false), s.path("branch_id").asText(""),
                    s.path("output").asText("")));
        }
        assertThat(steps).as("steps 数组不应为空").isNotEmpty();
        return steps;
    }

    /* ---- 步骤级期望推导（GAP-068-01 恢复，2026-10-21 r3 回归升级）----
     * 执行基准与 SUT 同源：静态模板期望从被测制品 classpath 的随包 yaml 经同一 BlueprintLoader 编译推导；
     * 动态模板（@ 通道）期望直接调用同制品的 DynamicTeamTemplates 纯函数推导——用例内不硬编码数字。 */

    /** 期望步骤（编译产物推导）：skipAllowed=该步在资产里声明了 skip_if（非 NEVER）。 */
    protected record ExpectedStep(String id, String member, boolean skipAllowed) {
    }

    /** 静态模板：从被测制品 classpath 装载随包资产并编译出期望步骤序。 */
    protected static List<ExpectedStep> expectedStepsFromAsset(final String templateName) throws IOException {
        return expectedOf(BlueprintLoader.load(readArtifactResource(
                "workflow-templates/" + templateName + ".yaml")));
    }

    /** 动态模板（@ 通道）：以同制品纯函数 crossKbComparison(kbIds) 推导期望步骤序。 */
    protected static List<ExpectedStep> expectedCrossKbSteps(final List<String> kbIds) {
        return expectedOf(DynamicTeamTemplates.crossKbComparison(kbIds));
    }

    private static List<ExpectedStep> expectedOf(final BlueprintSpec spec) {
        final List<ExpectedStep> expected = new ArrayList<>();
        spec.steps().forEach(s -> expected.add(
                new ExpectedStep(s.id(), s.member(), s.skipIf() != SkipPolicy.NEVER)));
        assertThat(expected).as("编译产物应含步骤声明；模板=%s", spec.id()).isNotEmpty();
        return expected;
    }

    /** 被测制品 classpath 资源读取（随包 yaml 即判据来源，与 SUT 同一制品）。 */
    protected static String readArtifactResource(final String path) {
        try (InputStream in = DaTemplateE2EBase.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("被测制品 classpath 资源应存在：%s（pom 钉子与 SUT 条目须同版本）", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("读取被测制品资源失败：" + path, e);
        }
    }

    /** 步骤级断言：登记序=期望序（编译序）、逐位成员对齐；资产未声明 skip_if 的步不得 skipped。 */
    protected void assertStepsMatch(final List<Step> observed, final List<ExpectedStep> expected) {
        assertThat(observed).as("步骤留痕条数应与编译期望一致；期望=%s 实收=%s", expected, observed)
                .hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            final Step o = observed.get(i);
            final ExpectedStep e = expected.get(i);
            assertThat(o.id()).as("第 %d 步 id 应=%s（登记序=编译序）", i + 1, e.id()).isEqualTo(e.id());
            assertThat(o.member()).as("第 %d 步（%s）member 应=%s", i + 1, e.id(), e.member())
                    .isEqualTo(e.member());
            if (!e.skipAllowed()) {
                assertThat(o.skipped()).as("第 %d 步（%s）资产未声明 skip_if，不得 skipped", i + 1, e.id())
                        .isFalse();
            }
        }
    }

    protected static String extractWorkflowId(final String raw) {
        final int at = raw.indexOf("workflow_id=");
        if (at < 0) {
            return "";
        }
        final int from = at + "workflow_id=".length();
        int end = from;
        while (end < raw.length() && ", }".indexOf(raw.charAt(end)) < 0) {
            end++;
        }
        return raw.substring(from, end);
    }

    private static String between(final String text, final String from, final String to) {
        final int a = text.indexOf(from);
        if (a < 0) {
            return "";
        }
        final int b = text.indexOf(to, a + from.length());
        return b < 0 ? "" : text.substring(a + from.length(), b);
    }

    protected static String truncate(final String text, final int max) {
        if (text == null) {
            return "null";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(截断)";
    }
}
