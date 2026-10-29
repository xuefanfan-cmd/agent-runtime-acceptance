package com.huawei.ascend.sit.cases.integration.agent_client_ts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087MockA2aServer.JSON;
import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087TsSdkHarness.invokeOp;
import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087TsSdkHarness.op;
import static com.huawei.ascend.sit.cases.integration.agent_client_ts.Feat087TsSdkHarness.opResult;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-087 真实服务端联调 profile（real-server，测试设计 §9）：
 * 对本地 example 真实 Runtime 做行为级增强验证，不替代 MockWebServer 主门禁，也不替代 A51 联调交付。
 *
 * <p>门禁（逐用例，缺省 SKIPPED，与 dependency-gated 风格一致）：
 * <ul>
 *   <li>{@code -Dfeat087.real.tool.url}（或环境变量 FEAT087_REAL_TOOL_URL）：
 *       agentcore-ext-client-tool-deepagent-demo 的 /a2a/ 入口 → F087-42、F087-43</li>
 *   <li>{@code -Dfeat087.real.travel.url}（或环境变量 FEAT087_REAL_TRAVEL_URL）：
 *       multi-react-travel-demo mainplan 的 /a2a/ 入口 → F087-44、F087-45</li>
 * </ul>
 *
 * <p>真实 LLM 输出非确定，断言只覆盖 SDK 确定性契约（§9.1）：终态可达与回显一致、
 * 同 Task 续接、已声明且已暴露工具的执行记录必为 OK、STREAMING 下调用树模式可用；
 * LLM 是否触发工具 / 委派 / 等待输入按假设（assumption）SKIPPED 并记录证据，不计 FAIL。
 * LLM 凭据与代理只经进程环境变量传入 demo 进程（见测试设计 §9.2），本类不读写任何密钥。
 */
@Tag("integration")
@Tag("agent-client-ts")
@Tag("feat-087")
@Tag("real-server")
@Feature("FEAT-087: Agent-Client支持Node服务TS语言")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat087RealServerBlackboxTest {

    /** 真实 LLM 链路（含代理与多轮工具回环）远慢于 mock，放宽 driver 进程级超时。 */
    private static final long REAL_DRIVER_TIMEOUT_MS = 360_000;
    private static final long REAL_OP_TIMEOUT_MS = 240_000;

    private Feat087TsSdkHarness harness;

    @BeforeAll
    void setUp() {
        harness = new Feat087TsSdkHarness();
    }

    @BeforeEach
    void assumeSdkEnvironmentReady() {
        // SDK 依赖门禁与主 profile 共用：SUT 产物 / Node 缺失时 SKIPPED
        Feat087TsSdkHarness.assumeEnvironmentReady(harness.sdkDir());
    }

    @AfterAll
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    // ---------- 门禁与场景构造 ----------

    private String assumeRealUrl(String property, String envVar) {
        String url = System.getProperty(property, System.getenv(envVar));
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "real-server profile 未启用：缺少 -D" + property + "（SKIPPED，见测试设计 §9.3）");
        // SDK 对不以 /a2a 结尾的 url 自动补齐；去掉尾斜杠避免 /a2a//a2a
        return url.replaceAll("/+$", "");
    }

    private ObjectNode realClient(String kind, String url, String conversationId) {
        ObjectNode client = Feat087TsSdkHarness.clientSpec(kind, url, conversationId + "-" + System.currentTimeMillis());
        // 真实 DeepAgent 会把每个 reasoning/usage token 作为独立产物下发（8MiB 默认输出预算
        // 会被思考流耗尽，挤掉最终 text 产物导致 finalText 为空），放宽到 64MiB。
        client.putObject("limits").put("maxOutputBytesPerInvocation", 64L * 1024 * 1024);
        return client;
    }

    private ObjectNode toolSpec(String toolId, String kind, java.util.List<String> required, String behavior) {
        ObjectNode spec = JSON.createObjectNode();
        ObjectNode descriptor = spec.putObject("descriptor");
        descriptor.put("toolId", toolId).put("kind", kind);
        if (required != null) {
            ArrayNode req = descriptor.putArray("required");
            required.forEach(req::add);
        }
        spec.put("behavior", behavior);
        return spec;
    }

    private ObjectNode streamingInvoke(String input, String invocationId, String await, long timeoutMs) {
        ObjectNode invoke = invokeOp("STREAMING", input, invocationId, await);
        invoke.put("timeoutMs", timeoutMs);
        return invoke;
    }

    private void assertNoFatal(ObjectNode result) {
        assertThat(result.has("fatal"))
                .as("driver 不应 fatal（真实服务端联调）：%s", result.path("fatal").asText())
                .isFalse();
    }

    // ---------- F087-42：真实 Runtime 直连流式调用闭环 ----------

    @Test
    @Tag("story-feat-087-42")
    @Story("FEAT-087.F087-42: 真实 Runtime 直连流式调用闭环")
    @DisplayName("F087-42 kind=RUNTIME 直连真实 DeepAgent，STREAMING 调用终态 COMPLETED 且回显一致")
    void f08742RealRuntimeStreamingInvocation() {
        String url = assumeRealUrl("feat087.real.tool.url", "FEAT087_REAL_TOOL_URL");
        ObjectNode cli = realClient("RUNTIME", url, "conv-real-42");

        ArrayNode ops = JSON.createArrayNode();
        ops.add(streamingInvoke("请用一句话介绍你自己。", "inv-real-42", "state:COMPLETED", REAL_OP_TIMEOUT_MS));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops), REAL_DRIVER_TIMEOUT_MS);

        assertNoFatal(result);
        JsonNode op0 = opResult(result, 0);
        assertThat(op0.path("reached").asBoolean()).as("应在超时窗口内到达 COMPLETED").isTrue();
        JsonNode snapshot = op0.path("snapshot");
        assertThat(snapshot.path("taskState").asText()).isEqualTo("COMPLETED");
        assertThat(snapshot.path("finalText").asText()).as("真实模型应产出最终文本").isNotBlank();
        assertThat(snapshot.path("conversationId").asText())
                .isEqualTo(cli.path("conversationId").asText());
        assertThat(snapshot.path("mode").asText()).isEqualTo("STREAMING");
        assertThat(snapshot.path("invocationRef").asText()).isEqualTo("inv-real-42");
    }

    // ---------- F087-43：真实 DeepAgent 本地工具闭环 ----------

    @Test
    @Tag("story-feat-087-43")
    @Story("FEAT-087.F087-43: 真实 DeepAgent 本地工具闭环")
    @DisplayName("F087-43 注册并全量暴露本地工具，真实模型触发工具轮后终态 COMPLETED，执行记录全部 OK")
    void f08743RealLocalToolRoundTrip() {
        String url = assumeRealUrl("feat087.real.tool.url", "FEAT087_REAL_TOOL_URL");
        ObjectNode cli = realClient("RUNTIME", url, "conv-real-43");
        ArrayNode tools = JSON.createArrayNode();
        tools.add(toolSpec("getLocalWeather", "OBSERVATION", java.util.List.of("city"), "echo"));
        tools.add(toolSpec("createCalendarEvent", "ACTION",
                java.util.List.of("title", "date", "durationMinutes"), "ok"));
        cli.set("tools", tools);
        cli.putObject("exposure").put("type", "all");

        ArrayNode ops = JSON.createArrayNode();
        // 强工具倾向提示：促使真实模型同时请求查询与创建两类本地工具
        ops.add(streamingInvoke(
                "请先调用本地工具查询深圳的天气，然后调用本地工具创建一个日程：标题为 FEAT-087 联调、"
                        + "日期为 2026-10-28、时长 60 分钟。完成后用一句话汇总结果。",
                "inv-real-43", "state:COMPLETED", REAL_OP_TIMEOUT_MS));
        ops.add(op("toolRecords"));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops), REAL_DRIVER_TIMEOUT_MS);

        assertNoFatal(result);
        JsonNode snapshot = opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("taskState").asText()).isEqualTo("COMPLETED");
        assertThat(snapshot.path("finalText").asText()).isNotBlank();

        // 确定性契约（§9.1-3）：客户端已注册且全量暴露，任何执行记录都不得 rejected/timeout/error
        JsonNode records = opResult(result, 1).path("records");
        assertThat(records.isArray()).as("toolRecords 应为数组").isTrue();
        for (JsonNode rec : records) {
            assertThat(rec.path("status").asText())
                    .as("已声明且已暴露工具 %s 的执行记录必须 OK（A31/A35/A37）", rec.path("toolId").asText())
                    .isEqualTo("OK");
        }
        // 软证据：LLM 未触发工具轮属非确定行为，仅记录，不计 FAIL
        System.out.println("[F087-43] 本地工具执行记录数=" + records.size()
                + "，finalText=" + snapshot.path("finalText").asText());
    }

    // ---------- F087-44：真实 travel 用户补充输入续接 ----------

    @Test
    @Disabled("上游依赖缺陷跳过：真实 travel demo 链（mainplan/trip/hotel）在恢复路径抛 REMOTE_BATCH_PARENT_MISMATCH，"
            + "首轮请求即 FAILED，非 TS SDK（SUT）缺陷，见 draft-feat-087-remote-batch-parent-mismatch.md")
    @Tag("story-feat-087-44")
    @Story("FEAT-087.F087-44: 真实 travel 用户补充输入续接")
    @DisplayName("F087-44 信息不全触发 INPUT_REQUIRED，continueInput 补全后沿用同一 taskId 完成")
    void f08744RealUserInputContinue() {
        String url = assumeRealUrl("feat087.real.travel.url", "FEAT087_REAL_TRAVEL_URL");
        ObjectNode cli = realClient("RUNTIME", url, "conv-real-44");

        ArrayNode ops = JSON.createArrayNode();
        // 信息不全 → mainplan request_user_input 中断等待补充
        ops.add(streamingInvoke("我想去旅行，帮我安排一下。", "inv-real-44", "pending:user_input", REAL_OP_TIMEOUT_MS));
        ObjectNode complete = op("continueInput");
        complete.put("input", "目的地三亚，下周三出发，差标每晚不超过 800 元，需要包含酒店推荐。");
        complete.put("timeoutMs", REAL_OP_TIMEOUT_MS);
        ops.add(complete);
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops), REAL_DRIVER_TIMEOUT_MS);

        assertNoFatal(result);
        JsonNode op0 = opResult(result, 0);
        boolean waited = !op0.path("timedOut").asBoolean()
                && "user_input".equals(op0.path("snapshot").path("pendingKind").asText());
        // 真实 LLM 可能直接补全默认信息而不等待输入：按假设 SKIPPED 并记录证据（§9.1-5）
        Assumptions.assumeTrue(waited,
                "真实 LLM 未触发用户补充输入等待（行为非确定），本轮 SKIPPED；"
                        + "快照=" + op0.path("snapshot"));
        String taskId = op0.path("snapshot").path("taskId").asText();
        assertThat(taskId).as("等待点应归属同一 Task").isNotBlank();

        JsonNode op1 = opResult(result, 1);
        assertThat(op1.has("error"))
                .as("续接不应被拒绝：%s", op1.path("error")).isFalse();
        assertThat(op1.path("timedOut").asBoolean()).as("续接应在窗口内结算").isFalse();
        JsonNode resumed = op1.path("snapshot");
        assertThat(resumed.path("taskId").asText())
                .as("续接沿用同一 taskId，不新建 Task（A12/A13）").isEqualTo(taskId);
        assertThat(resumed.path("taskState").asText()).isEqualTo("COMPLETED");
    }

    // ---------- F087-45：真实多跳流式调用树冒烟 ----------

    @Test
    @Disabled("上游依赖缺陷跳过：mainplan→trip→hotel 多跳委派在恢复路径抛 REMOTE_BATCH_PARENT_MISMATCH，"
            + "首轮请求即 FAILED，非 TS SDK（SUT）缺陷，见 draft-feat-087-remote-batch-parent-mismatch.md")
    @Tag("story-feat-087-45")
    @Story("FEAT-087.F087-45: 真实多跳流式调用树冒烟")
    @DisplayName("F087-45 STREAMING 触发 mainplan→trip→hotel 多跳委派，调用树模式 AVAILABLE")
    void f08745RealCallTreeSmoke() {
        String url = assumeRealUrl("feat087.real.travel.url", "FEAT087_REAL_TRAVEL_URL");
        ObjectNode cli = realClient("RUNTIME", url, "conv-real-45");

        ArrayNode ops = JSON.createArrayNode();
        ops.add(streamingInvoke("请帮我规划一个三亚三天两晚的行程，包含景点安排和酒店推荐。",
                "inv-real-45", "state:COMPLETED", REAL_OP_TIMEOUT_MS));
        ObjectNode result = harness.runDriver(Feat087TsSdkHarness.scenario(cli, ops), REAL_DRIVER_TIMEOUT_MS);

        assertNoFatal(result);
        JsonNode snapshot = opResult(result, 0).path("snapshot");
        assertThat(snapshot.path("taskState").asText()).isEqualTo("COMPLETED");
        assertThat(snapshot.path("callTreeAvailability").asText())
                .as("STREAMING 下调用树模式必须可用（A24/A27）").isEqualTo("AVAILABLE");
        assertThat(snapshot.path("finalText").asText()).isNotBlank();

        // 软证据：委派是否发生、树快照内容随 LLM 行为浮动，仅记录不计 FAIL
        System.out.println("[F087-45] 树快照事件数=" + Feat087TsSdkHarness.treeEvents(result).size()
                + "，finalText=" + snapshot.path("finalText").asText());
    }
}
