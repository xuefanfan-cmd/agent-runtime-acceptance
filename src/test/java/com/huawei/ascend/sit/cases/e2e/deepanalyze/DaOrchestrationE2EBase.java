/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import org.junit.jupiter.api.Assumptions;

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
 * FEAT-065/066/067 端到端用例的公共栈基座（**测试基础设施，不含 {@code @Test}**）。
 *
 * <p>与 FEAT-054 的 {@code DaCompatStreamTestBase} 的**唯一实质差别**是 SUT 名：
 * 本族用 {@code deepanalyze-feat065067}（`application-openjiuwen.yml` 里 2026-10-16 新增的条目，
 * 制品 {@code deepanalyze-engine:0.1.3-feat065067-r2:exec}、门控 {@code deepanalyze.workflow.enabled=true}、
 * {@code parallelism=4}），**不改** FEAT-054 线钉 {@code 0.1.4} 的既有 {@code deepanalyze} 条目。
 *
 * <p><b>为什么走 compat 通道而不是只看 A2A 答复正文</b>：设计要求断言的是 {@code workflow_run} 的
 * **出参契约**（{@code status} / {@code workflow_id} / {@code steps[]} 的条数与每步 {@code member} /
 * {@code final_output}）。这些在 A2A 答复正文里只能靠"模型是否复述"间接观察，不可靠。
 * 宿主把工具调用聚合进了 compat 的 status 出参：{@code DaCompatController.java:757}
 * {@code result.put("toolCalls", aggregator.getToolCalls())}，元素形如
 * {@code {id, toolName, input, status, output}}（{@code DaEventTranslator.java:316-324}，
 * {@code output} 由 {@code tool_result} 回填）⇒ **可直接取到工具的入参与出参原文**，
 * <b>{@code output} 的形态自 r3 起是纯 JSON</b>（{@code DaToolResultJsonRail}，issue #441 裁 1-B2；
 * 详见 {@link #requireSuccessOutput} 的 javadoc 与批次记录 §7BD），不再是 {@code ToolOutput.toString()}。
 * 不必依赖模型复述。这是本族用例的主取证面。
 *
 * <p><b>模型不配合的处置（设计明文）</b>：主 Agent **未调用** {@code workflow_run} 时记
 * {@code INCONCLUSIVE}（JUnit {@link Assumptions#abort}→SKIPPED），**不判产品 FAIL**——
 * 设计未承诺"措辞 → 调用"的确定性（065-S22 / 066-S16 / 067-S16 三处均如此规定）。
 */
abstract class DaOrchestrationE2EBase extends BaseManagedStackTest {

    protected static final String AGENT = "deepanalyze-feat065067";
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    /** 真实模型成员 + 宿主启动，超时给足（设计明令不得断言耗时有界，故只作装置上限）。 */
    protected static final Duration RUN_TIMEOUT = Duration.ofMinutes(25);
    protected static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    /** 绕 agent-solution#473 的 ENGINE_BUSY 有界退避：最多重发次数（不改判据，退避用尽仍按原断言 FAIL）。 */
    private static final int BUSY_RETRY_MAX = 4;
    /** 绕 agent-solution#473 的 ENGINE_BUSY 有界退避：每次退避毫秒数。 */
    private static final long BUSY_RETRY_BACKOFF_MS = 8_000L;
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
                    // 【已登记的代表性偏差】把工具调用超时从产品默认 300s 放大到 1800s。
                    // 依据：core AbilityManager 的 DEFAULT_TOOL_CALL_TIMEOUT 是**环境变量**
                    // （envDouble("DEFAULT_TOOL_CALL_TIMEOUT", 300.0)，:72-73；resolveCallTimeout 在
                    //   ToolCard 未声明超时时回落到它），故可在 SUT 进程环境里调。
                    // 为什么要调：本族用例验的是**编排语义**（steps 条数/成员/收口/L1-L3 结构对照），
                    // 不是超时策略；7 成员真实模型（每成员是裸 ReActAgent；【2026-10-27 更正】成员迭代上限是 DaWorkflowRunner.MEMBER_MAX_ITERATIONS=30（L2-067:195「30 轮实现级安全上限」），我方 fixture 的 deepanalyze.model.max-iterations=4 **只作用于主 Agent、从未作用于成员**；另 docs !379 后成员按发起方会话范围带 14 件只读工具，不再是「无工具」）
                    // 在 300s 内跑不完，会把"编排语义对不对"这个问题被超时掩盖。
                    // 代价（如实登记）：**默认 300s 下 shipped 模板不可达**这一事实不在本族用例的判据内，
                    // 已单独作为产品缺陷 D3 记录（issues/drafts/20261016-D3-*.md）；
                    // 因此本轮 PASS **不得**被读成"用户用默认配置也能跑通模板"。
                    agent.env("DEFAULT_TOOL_CALL_TIMEOUT", "1800");
                    // 运行时 A2A TaskStore 与 core checkpointer 走 Redis；默认主机名 "redis" 在本地解析不到。
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    protected void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            final String root = client(AGENT).getBaseUrl();
            assertThat(root).as("应能取到 SUT 的 base url").isNotBlank();
            // 本期被测制品（agent-solution common@f9da5b54f 构建）的 compat 控制器映射是
            // @RequestMapping("/api/agents")（DaCompatController.java:66）⇒ 端点为
            // /api/agents/run 与 /api/agents/status/{taskId}。
            // 注意：FEAT-054 那批用例打的 /agent/* 是 0.1.4 制品的口径，**不适用本期制品**，
            // 首跑即因此收 404（{"path":"/agent/run"}），已按被测源码更正。
            base = root.endsWith("/") ? root + "api/agents" : root + "/api/agents";
        }
    }

    /**
     * 把**跨轮累积**的 SUT 日志按「当前 SUT 进程」开窗。
     *
     * <p>为什么必须开窗：{@code target/sit-logs/<entry>/stdout.log} 由 ProcessLauncher 以 append 方式写入，
     * **跨 Maven 轮次持续累积**（本批实测同一文件里同时存在 2026-10-21 与 2026-10-27 的记录、9 MB 量级）。
     * 对全文做 max/min/存在性/计数判断，会把他轮记录算进本轮判据：
     * <ul>
     *   <li>**假失败**：067-S26 曾对全文取 {@code duration_ms} 最大值，取到他轮 423322.36 ms（本轮实际 20.26 ms）；</li>
     *   <li>**假通过**：067-S19 用全文构建 {@code Map<tool, level>}，本轮若漏发某工具的决策，
     *       历史值会**静默顶替**，断言照样绿——这是更危险的方向。</li>
     * </ul>
     *
     * <p>开窗键选 **PID** 而不是 sessionId：注册期/启动期日志行（如 {@code tool_count=}、
     * {@code Started DeepAnalyzeApplication}）不带 sessionId，按会话过滤会漏；而 PID 唯一标识
     * "本次 Maven 轮起的那个 SUT 进程"，正是判据要的范围。日志行格式为
     * {@code <时间戳>  <级别> <pid> --- [<logger>] …}，故以 {@code " <pid> "} 作行内锚。
     *
     * <p>**已知局限（如实登记）**：跨行的续行（异常栈、原样打印的 JSON 响应体）不带 PID 前缀，会被过滤掉。
     * 需要读续行时改用 {@code readSutLogForSession} 那种"按会话过滤"或显式位点窗口
     * （见 {@code DaWorkflowMessageIsolationE2EIT} 的 logMark 位点法），不要退回全文扫描。
     */
    public static String windowByPid(final String fullLog, final long pid) {
        final String anchor = " " + pid + " ";
        final StringBuilder windowed = new StringBuilder();
        for (String line : fullLog.split("\n", -1)) {
            if (line.contains(anchor)) {
                windowed.append(line).append('\n');
            }
        }
        return windowed.toString();
    }

    /** 行数（用于把"窗口内/全文"两个规模写进 Allure，让开窗这件事可审计、不静默）。 */
    public static int countLines(final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.split("\n", -1).length;
    }

    /** 新会话 id（数据唯一性：每次运行独立会话，不复用其他用例状态）。 */
    protected static String newSession(final String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 经 compat 同步端点（{@code POST /api/agents/run} → {@code GET /api/agents/status/{taskId}}）
     * 跑一轮会话，返回 status 快照（含 {@code toolCalls}）。
     *
     * @return {@code GET /api/agents/status/{taskId}} 的 JSON 快照
     */
    /**
     * 经 compat 同步端点跑一轮会话，返回 **{@code POST /api/agents/run} 的响应体**。
     *
     * <p><b>取证面更正（run-15 实测）</b>：{@code toolCalls} 只在 {@code /run} 的同步聚合响应里
     * （{@code DaCompatController.collectSyncResult}，{@code :753-758}）；
     * {@code GET /api/agents/status/{taskId}} 走的是另一个处理器（{@code :427} → {@code toTaskResponse(record)}），
     * 实测形状为 {@code {id, agentType, status, input, output, error, parentId, sessionId, createdAt, completedAt}}，
     * **不含 toolCalls**。首跑因此拿不到工具调用记录，已按被测源码更正。
     */
    protected JsonNode runAndSnapshot(final String sessionId, final String input) throws Exception {
        initClient();
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("input", input);
        // 【2026-10-26 有界退避·绕 agent-solution#473（open）】
        // run-77 实测：上一轮完成信号发出（controller "Completion signal sent"，14:36:04.900）后 **1 毫秒**，
        // 同会话的下一个同步请求即被判 409 ENGINE_BUSY（14:36:04.901 WARN "task body failed before stream
        // terminal … Conversation busy"），taskId=null ⇒ 请求未被受理、重发无副作用。该竞态由 #473 跟踪，
        // 本处只做**有界退避重发**并逐次留痕，**不改任何判据**；退避用尽仍 409 时按原断言 FAIL 并保留原文。
        final String runPayload = MAPPER.writeValueAsString(body);
        HttpResponse<String> runResponse = null;
        int busyRetries = 0;
        while (true) {
            final HttpRequest run = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/run"))
                    .timeout(RUN_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header(SESSION_HEADER, sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(runPayload, StandardCharsets.UTF_8))
                    .build();
            runResponse = http.send(run, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final String respBody = runResponse.body() == null ? "" : runResponse.body();
            final boolean busy = runResponse.statusCode() == 409 && respBody.contains("ENGINE_BUSY");
            if (!busy || busyRetries >= BUSY_RETRY_MAX) {
                break;
            }
            busyRetries++;
            Allure.addAttachment("ENGINE_BUSY 有界退避（第 " + busyRetries + "/" + BUSY_RETRY_MAX
                            + " 次，绕 agent-solution#473）", "text/plain",
                    "HTTP " + runResponse.statusCode() + " body=" + truncate(respBody, 2048)
                            + "\n退避 " + BUSY_RETRY_BACKOFF_MS
                            + " ms 后重发同一请求（409 未被受理、taskId=null，无副作用）");
            Thread.sleep(BUSY_RETRY_BACKOFF_MS);
        }
        if (busyRetries > 0) {
            Allure.addAttachment("ENGINE_BUSY 退避合计（绕 agent-solution#473）", "text/plain",
                    "本会话轮共遇到 " + busyRetries + " 次 ENGINE_BUSY；竞态由 agent-solution#473 跟踪，"
                            + "本处只做有界退避、不改判据。");
        }
        Allure.addAttachment("POST /api/agents/run 响应（截断 8KB）", "text/plain",
                truncate(runResponse.body(), 8192));
        assertThat(runResponse.statusCode())
                .as("同步执行应返回 200；实收 body=%s", truncate(runResponse.body(), 2048))
                .isEqualTo(200);
        final JsonNode runBody = MAPPER.readTree(runResponse.body());
        // /run 的同步聚合响应即含 toolCalls；status 快照只作补充证据（不含 toolCalls）
        if (runBody.path("toolCalls").isMissingNode() || runBody.path("toolCalls").isNull()) {
            final String taskId = runBody.path("taskId").asText();
            if (!taskId.isBlank()) {
                final HttpRequest status = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/status/" + taskId))
                        .timeout(READ_TIMEOUT)
                        .header(SESSION_HEADER, sessionId)
                        .GET()
                        .build();
                final HttpResponse<String> statusResponse = http.send(status,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                Allure.addAttachment("GET /api/agents/status 补充证据（截断 16KB）", "text/plain",
                        truncate(statusResponse.body(), 16384));
            }
        }
        return runBody;
    }

    /**
     * 从 status 快照里取出 {@code workflow_run} 的调用记录；**模型未调用即中止为 INCONCLUSIVE**。
     *
     * @return {@code {id, toolName, input, status, output}} 形态的节点
     */
    protected JsonNode requireWorkflowRunCall(final JsonNode snapshot) {
        final JsonNode calls = snapshot.path("toolCalls");
        assertThat(calls.isArray()).as("status 快照应含 toolCalls 数组（DaCompatController.java:757）；实收=%s",
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
        // 设计明文：主 Agent 未调用该工具属 L3 层不确定性 ⇒ INCONCLUSIVE，不判产品 FAIL
        Assumptions.abort("主 Agent 本轮未调用 workflow_run（模型未配合，属 L3 不确定性）；"
                + "实收 toolCalls 的 toolName 集合=[" + names.toString().trim() + "]。"
                + "按 FEAT-065 065-S22 / FEAT-066 066-S16 / FEAT-067 067-S16 的规定记 INCONCLUSIVE，不判 FAIL；"
                + "恢复动作 = 重试或强化指令语料。");
        throw new IllegalStateException("unreachable");
    }

    /**
     * 任务级失败的归因分流（**产品 FAIL 与模型未配合 INCONCLUSIVE 必须分开**）。
     *
     * <p>设计口径：067-S16 / 065-S22 / 066-S16 的 INCONCLUSIVE 只适用于
     * 「主 Agent **未调用** {@code workflow_run}（模型未配合）」。而实测到的
     * {@code Tool 'workflow_run' timed out after 300.0s} 是**工具被调用了但没跑完**——
     * 属产品侧失败，必须记 **FAIL** 并归因，绝不能混记为 INCONCLUSIVE（那会把产品缺陷洗成"模型不配合"）。
     */
    protected void failIfTaskFailed(final JsonNode snapshot) {
        if (!"failed".equals(snapshot.path("status").asText())) {
            return;
        }
        final String error = snapshot.path("error").asText();
        throw new AssertionError("【产品侧失败，非模型未配合】任务以 failed 结束：" + error
                + "。归因：core AbilityManager.java:1014-1016 的工具调用超时——resolveCallTimeout(toolCard) "
                + "在 ToolCard 未声明超时时回落到硬上限（实测 300.0s），而 WorkflowRunTool 的 ToolCard "
                + "只声明了 id/name/description/inputParams、**未声明超时** ⇒ 测试侧无法通过配置绕过。"
                + "上位口径冲突见 L0-overview:76 Principle 4（控制面/数据面/流式面严禁混用，防长尾阻塞控制通道）。"
                + "详见批次记录 §7I.3 / §7J.2。");
    }

    /**
     * 从 {@code toolCalls[].output} 取出工具出参，并断言"工具成功"。
     *
     * <p><b>形态变更（2026-10-21，r4 基线 {@code cf4571b30} / run-62 实测更正）</b>：该字段现为**纯 JSON**，
     * 形如 {@code {"status":"completed","workflow_id":"wf-…","final_output":"…","steps":[…],"files":"…"}}，
     * **不再是** {@code ToolOutput.toString()} 的括号文本。三方定位（证据：批次记录 §7BD、
     * {@code evidence/20261021-run-62/allure-preserved/workflow_run-output-r4-actual.json}）：
     * <ol>
     *   <li><b>变更来源＝设计侧明文裁决</b>：{@code DaToolResultJsonRail}（agent-solution {@code a6765a781}，
     *       2026-10-13，issue #441 <b>裁 1-B2</b>）在 AFTER_TOOL_CALL 窗口把 {@code ToolOutput} 信封
     *       Jackson 序列化为 JSON 写回 {@code ToolCallInputs}，优先级 110 &gt; ToolTrackingRail 100
     *       ⇒ 模型消息面与 SSE/compat 观测面同源拿到 JSON。**不是缺陷**。</li>
     *   <li><b>为什么 r3 没发现</b>：该提交在 r3（{@code ce9019d86}）就已在，但 {@code agent-solution#462}
     *       （core 把原生结果块类型 {@code tool_result} 改名 {@code tool_output}，compat 翻译层只认旧名）
     *       令 {@code output} 永不回填 ⇒ 形态变化被**完全遮蔽**；!860（{@code 14f68d83e}）修好断链后
     *       它才第一次真正外流。</li>
     *   <li><b>排除 core 漂移</b>：r3/r4 的 {@code agent-core-java-0.1.17.jar} 逐字相同
     *       （sha256 {@code e9abe364ac539c9191dc7f4f94f6234114b67d4613b6c74240bddfad0600c8e0}）。</li>
     * </ol>
     *
     * <p><b>"工具成功"的判据怎么搬（语义不变、只换观测面）</b>：JSON rail 只序列化 {@code ToolOutput.data}，
     * 信封的 {@code success}/{@code error} 不再出现在出参文本里，故旧判据 {@code contains("success=true")}
     * 已无对应串。现行等价面两条，**两条都断言**：
     * ①出参 JSON **不含** {@code error} 键（rail 仅在 {@code error} 非空时写 {@code {"error":…}}）；
     * ②{@code toolCalls[].status == "completed"}（!860 起 core 的 {@code status=error} 载荷映射为
     * {@code failed}，此前一律 {@code completed}）。
     *
     * <p>另：出参顶层新增 {@code files}、每步新增 {@code output_chars}/{@code output_file}，
     * 来自 {@code OutputArchive}（r3 引入）的落盘契约，见 {@link Step} 与
     * {@link #assertFinalOutputMatchesLastStep}。
     */
    protected String requireSuccessOutput(final JsonNode call) {
        final String raw = call.path("output").asText();
        assertThat(raw).as("workflow_run 的 tool_result 应回填 output").isNotBlank();
        Allure.addAttachment("workflow_run 出参原文（截断 16KB）", "text/plain", truncate(raw, 16384));
        final JsonNode json = parseJsonObject(raw, "工具出参应是 JSON 对象（DaToolResultJsonRail，裁 1-B2）");
        assertThat(json.hasNonNull("error"))
                .as("成功出参不应带 error 键（JSON rail 仅在 ToolOutput.error 非空时写 {\"error\":…}）；实收=%s",
                        truncate(raw, 1024))
                .isFalse();
        assertThat(call.path("status").asText())
                .as("工具调用应成功——旧判据 success=true 的现行等价面：信封被 JSON rail 吃掉后由 "
                        + "toolCalls[].status 承接（!860 起 error 载荷映射 failed）；实收=%s", truncate(raw, 1024))
                .isEqualTo("completed");
        return raw;
    }

    /**
     * 一步留痕的提取结果。
     *
     * <p>{@code outputChars}/{@code outputFile} 来自 {@code OutputArchive.view}（r3 引入的落盘契约）：
     * 每步 output 的 inline 上限 {@code STEP_INLINE_CHARS=1500} 字、final_output 的
     * {@code FINAL_INLINE_CHARS=4000} 字；超限则 {@code output} = 前 N 字 +
     * {@code "\n…[共 X 字，完整内容见 <路径>]"}，原长另给 {@code output_chars}，全文落盘到会话目录
     * {@code workflows/<workflow_id>/}。步骤 output **总是**落盘（{@code isAlwaysSaved=true}），
     * 故 {@code outputFile} 非空**不**代表被截断，判据只看 {@link #truncated()}。
     */
    protected record Step(String id, String member, boolean skipped, String branchId, String output,
                          int outputChars, String outputFile) {

        /** OutputArchive 的每步 inline 上限（源：{@code OutputArchive.STEP_INLINE_CHARS}，r4 实测 1500）。 */
        static final int INLINE_CHARS = 1500;

        /** 本步 output 是否被落盘契约截断（判据④的比对方式据此分流）。 */
        boolean truncated() {
            return outputChars > INLINE_CHARS || (output != null && outputChars >= 0 && outputChars != output.length());
        }

        /** 截断尾标之前的 inline 正文（未截断时即全文）。 */
        String inlineBody() {
            return truncateMarkerPrefix(output);
        }
    }

    /** 从 JSON 出参里取出全部步骤留痕（顺序即登记序）。 */
    protected List<Step> extractSteps(final String raw) {
        final JsonNode steps = parseJsonObject(raw, "工具出参应是 JSON 对象").path("steps");
        assertThat(steps.isArray())
                .as("出参应含 steps[] 数组（工具出参契约）；实收=%s", truncate(raw, 4096)).isTrue();
        final List<Step> result = new ArrayList<>();
        for (final JsonNode s : steps) {
            // skipped 缺失时 asBoolean 会静默回落 false，把"不应被跳过"洗成通过 ⇒ 必须先断言字段在场
            assertThat(s.has("skipped"))
                    .as("每步应带 skipped 字段；实收步骤=%s", truncate(s.toString(), 512)).isTrue();
            result.add(new Step(
                    s.path("id").asText(""),
                    s.path("member").asText(""),
                    s.path("skipped").asBoolean(false),
                    s.path("branch_id").asText(""),
                    s.path("output").asText(""),
                    s.path("output_chars").asInt(-1),
                    s.path("output_file").asText("")));
        }
        assertThat(result).as("应能从工具出参里切出步骤留痕；实收原文=%s", truncate(raw, 4096)).isNotEmpty();
        return result;
    }

    /** 取出参顶层 {@code status}（设计契约：成功 = {@code completed}）。 */
    protected String extractStatus(final String raw) {
        return topLevelText(raw, "status");
    }

    /** 取出参顶层 {@code workflow_id}（SSE 订阅键来源）。 */
    protected String extractWorkflowId(final String raw) {
        return topLevelText(raw, "workflow_id");
    }

    /** 取出参顶层 {@code final_output}（收口产物）。 */
    protected String extractFinalOutput(final String raw) {
        return topLevelText(raw, "final_output");
    }

    private static String topLevelText(final String raw, final String key) {
        final JsonNode json = parseJsonObject(raw, "工具出参应是 JSON 对象");
        assertThat(json.hasNonNull(key))
                .as("出参应含 %s 键；实收=%s", key, truncate(raw, 2048)).isTrue();
        return json.path(key).asText("");
    }

    /**
     * 判据④「{@code final_output} == 末步 {@code output}」的**截断感知**比对。
     *
     * <p>为什么不是一句 {@code isEqualTo}：{@code OutputArchive.view} 的 inline 上限是每步 1500 字 /
     * final_output 4000 字，超限即截断并落盘。r4 首跑（run-62）三步分别 540/475/260 字，未触发，
     * 严格相等成立；但真实模型话多一点就会触发，届时"严格相等"会**假失败**。故按三档分流，
     * 并把走了哪一档写进 Allure，避免出现"判据被悄悄放宽"的审计盲区：
     * <ul>
     *   <li><b>A 档（最强＝原判据）</b>：两侧均未截断 ⇒ {@code final_output} 严格等于末步 output。</li>
     *   <li><b>B 档</b>：末步被截断、final_output 未截断 ⇒ final_output 即全文，
     *       故其字数应等于末步 {@code output_chars}，且应以末步的 inline 正文开头。</li>
     *   <li><b>C 档</b>：两侧都被截断 ⇒ 同一份全文的两个前缀，比"较短的那个前缀"。</li>
     * </ul>
     */
    protected void assertFinalOutputMatchesLastStep(final String raw, final Step last) {
        final String finalOutput = extractFinalOutput(raw);
        assertThat(finalOutput).as("④ 收口产物 final_output 不应为空").isNotBlank();
        assertThat(last.output()).as("④ 末步产物不应为空").isNotBlank();
        final String fBody = truncateMarkerPrefix(finalOutput);
        final boolean finalTruncated = fBody.length() != finalOutput.length();
        final String sBody = last.inlineBody();
        final String grade;
        if (!last.truncated() && !finalTruncated) {
            grade = "A（两侧均未截断 ⇒ 严格相等，原判据原样保留）";
            assertThat(finalOutput)
                    .as("④ 收口产物应严格等于末步 output 原文（不得错位、不得改写）；档=%s", grade)
                    .isEqualTo(last.output());
        } else if (last.truncated() && !finalTruncated) {
            grade = "B（末步被 OutputArchive 截断、final_output 未截断 ⇒ 比全文字数 + inline 前缀）";
            assertThat(finalOutput.length())
                    .as("④ final_output 应是末步全文，故其字数应等于末步 output_chars；档=%s", grade)
                    .isEqualTo(last.outputChars());
            assertThat(finalOutput).as("④ final_output 应以末步的 inline 正文开头；档=%s", grade)
                    .startsWith(sBody);
        } else {
            grade = "C（两侧均被截断 ⇒ 比公共 inline 前缀）";
            final String shorter = sBody.length() <= fBody.length() ? sBody : fBody;
            assertThat(fBody).as("④ 收口产物与末步产物的 inline 前缀应一致；档=%s", grade).startsWith(shorter);
        }
        Allure.addAttachment("判据④ 比对档位（截断感知）", "text/plain",
                "档=" + grade
                        + "\n末步 output_chars=" + last.outputChars()
                        + "\n末步 output.length=" + last.output().length()
                        + "\n末步 truncated=" + last.truncated()
                        + "\nfinal_output.length=" + finalOutput.length()
                        + "\nfinalTruncated=" + finalTruncated
                        + "\n末步 output_file=" + last.outputFile());
    }

    /** 去掉 OutputArchive 的截断尾标（{@code "\n…[共 X 字，…]"}) 后的 inline 正文。 */
    private static String truncateMarkerPrefix(final String text) {
        if (text == null) {
            return "";
        }
        final int at = text.indexOf("\n…[共 ");
        return at < 0 ? text : text.substring(0, at);
    }

    /** 把出参文本解析成 JSON 对象；解不出即判契约不符。 */
    protected static JsonNode parseJsonObject(final String raw, final String because) {
        assertThat(raw).as("%s：出参不应为空", because).isNotBlank();
        final int from = raw.indexOf('{');
        final int to = raw.lastIndexOf('}');
        assertThat(from).as("%s；实收=%s", because, truncate(raw, 2048)).isNotNegative();
        assertThat(to).as("%s；实收=%s", because, truncate(raw, 2048)).isGreaterThan(from);
        try {
            final JsonNode node = MAPPER.readTree(raw.substring(from, to + 1));
            assertThat(node.isObject()).as("%s：应解析成 JSON 对象；实收=%s", because, truncate(raw, 2048)).isTrue();
            return node;
        } catch (Exception ex) {
            throw new AssertionError(because + "：无法解析为 JSON；实收=" + truncate(raw, 2048), ex);
        }
    }

    /** 把工具出参（{@code output} 字段，字符串）解析成 JSON；解不出即判契约不符。 */
    protected JsonNode parseToolOutput(final JsonNode call) {
        return parseJsonObject(call.path("output").asText(),
                "workflow_run 的 tool_result 应回填 output 且为 JSON 对象（工具出参契约）");
    }

    protected static String truncate(final String text, final int max) {
        if (text == null) {
            return "(null)";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(截断)";
    }
}
