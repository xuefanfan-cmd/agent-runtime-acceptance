package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>R1</b>（2026-08-24 新增）—— EDPA 并行下 <b>SubscribeToTask 重订阅</b>：
 * 子任务粒度可见性组合验收。
 *
 * <p><b>Spec 依据</b>：
 * <ul>
 *   <li>FEAT-001 §62【新增特性】「活动 Task 重订阅」MUST：Runtime 必须支持
 *       {@code SubscribeToTask(params.id=taskId)}——首帧=当前 Task 快照，之后=挂接成功后的新事件；
 *       不重新执行 Agent、不重触发副作用。基础 wire 契约已由 FEAT-001 `TaskResubscribeTest`
 *       (E3+E4) 在 search-agent SUT 上验证。</li>
 *   <li>FEAT-028 §2 范围（2026-08-24 加）：EDPA 场景客户端应能通过三条通道观察子任务信息——
 *       ①SSE 实时（P3/P4）；②GetTask 快照（P0b/P0c）；③SubscribeToTask 重订阅（本用例）。</li>
 * </ul>
 *
 * <p><b>本用例主权 vs FEAT-001</b>：
 * <ul>
 *   <li>FEAT-001 侧只测「首帧是快照 + taskId 一致」这类基础 wire 契约；</li>
 *   <li>本用例只测「EDPA 并行场景下，首帧快照 + 后续事件应能观察到子任务信息」——
 *       {@link #hardCheck2ChildVisibility 全字段递归扫描}，不预设 wire 承载位。</li>
 * </ul>
 *
 * <p><b>不预设 wire 字段名/结构</b>（用户 2026-08-24 明示：wire 承载位归设计定，测试只保证客户端
 * 能观察到）。命中判据集合：①除 parent taskId 外的其他 taskId 值；②除 EDPAgent 名外的其他
 * agentId 值（如 {@code search-agent}）；③非 parent Task 的 state 值（如 {@code agentEvent.source.state}
 * 或类似路径的 state 字段）。三种命中方式任意一处即算硬 2 PASS。
 *
 * <p><b>红-first 承接</b>：若全字段扫描无任何命中，作为 issue #93 缺陷簇第 4 处 red-first 观察
 * （与 P0b/P0c/C3 同源，同一根因：runtime 内部有完整信息但未跨越 core-runtime 边界回投客户端可见面）。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM + LLM 并行规划。
 */
@Tag("integration") @Tag("edpa") @Tag("feat-028") @Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("R1.subscribe-to-task-resubscribe: 并行下 SubscribeToTask 首帧快照 + 后续事件应看到子任务")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaSubscribeToTaskResubscribeTest {

    private static final Logger LOG = Logger.getLogger(EdpaSubscribeToTaskResubscribeTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";

    private static final long SSE_FIRST_WORKING_CAP_MS = 60_000L;
    private static final long RESUBSCRIBE_READ_CAP_MS =
            Long.getLong("sit.feat028.r1-resub-read-cap-ms", 45_000L);
    /** 到达 max frames 后主动结束 SSE 读取（避免读到父 Task 终态才断）。 */
    private static final int RESUBSCRIBE_MAX_FRAMES =
            Integer.getInteger("sit.feat028.r1-resub-max-frames", 500);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private TestConfig config;
    private SutStack searchStack;
    private SutStack edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null,
                "[r1] 需 EDP_AGENT_MODEL_* 环境变量，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[r1] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.R1: SubscribeToTask 重订阅——首帧应为父快照 + 全字段扫描应看到子任务信息")
    void subscribeToTaskReturnsSnapshotFirstAndExposesChildDelegates() throws Exception {
        String a2aUrl = edpStack.baseUrl(EDP_AGENT) + "/a2a";
        String contextId = "ctx-feat028-r1-" + UUID.randomUUID().toString().substring(0, 8);

        // 步骤 1：SendStreamingMessage 发并行 prompt，读到父 taskId + WORKING 后主动断开
        String parentTaskId = startParallelAndDetach(a2aUrl, contextId);
        assumeTrue(parentTaskId != null, "[r1] 未取到父 taskId，INCONCLUSIVE");
        LOG.info("[r1] parentTaskId=" + parentTaskId + "，SSE 断开完成");

        // 步骤 2：调 SubscribeToTask(params.id=parentTaskId)
        String subBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"r1-%s\",\"method\":\"SubscribeToTask\",\"params\":{\"id\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8), parentTaskId);
        HttpRequest req = HttpRequest.newBuilder(URI.create(a2aUrl))
                .timeout(Duration.ofMillis(RESUBSCRIBE_READ_CAP_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(subBody)).build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        LOG.info("[r1] SubscribeToTask HTTP=" + resp.statusCode() + " ct=" + ct);

        // 非 SSE 应答：可能是 method-not-found 或其他错误——red-first 显性化实现缺口
        if (!ct.contains("event-stream")) {
            String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode err = mapper.readTree(errBody).path("error");
            assertThat(err.isMissingNode())
                    .as("[r1] ⭐ 硬 1（FEAT-001 §62）：SubscribeToTask 应返回 SSE 流；"
                            + "实测非流式应答 + 错误码=%s message=%s（-32601 即 method 未实现——red-first 承接 issue #93）"
                            + "\nbody=%s", err.path("code").asText(""), err.path("message").asText(""), errBody)
                    .isTrue();
            // 走到此处即已挂
            assertThat(ct).as("非 SSE 也非 error——未知形态\nbody=%s", errBody).contains("event-stream");
            return;
        }

        // 步骤 3：收重订阅 SSE 所有帧
        List<JsonNode> allFrames = new ArrayList<>();
        long cap = System.currentTimeMillis() + RESUBSCRIBE_READ_CAP_MS;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < cap
                    && allFrames.size() < RESUBSCRIBE_MAX_FRAMES
                    && (line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                try {
                    allFrames.add(mapper.readTree(data));
                } catch (Exception ignore) { /* 保留形态判定，跳过解析失败的帧 */ }
            }
        } catch (Exception streamEnd) { /* 服务端关流或超时：以已收帧为准 */ }
        LOG.info("[r1] 重订阅收帧数=" + allFrames.size());
        assertThat(allFrames)
                .as("[r1] 硬 1：重订阅 SSE 流应至少推出 1 帧").isNotEmpty();

        // 硬 1：首帧应为父 Task 快照（taskId 一致 + status.state 存在）
        JsonNode firstFrame = allFrames.get(0);
        JsonNode result = firstFrame.path("result");
        String frameTaskId = firstNonBlank(
                result.path("task").path("id").asText(null),
                firstNonBlank(result.path("statusUpdate").path("taskId").asText(null),
                        result.path("id").asText(null)));
        String frameState = firstNonBlank(
                result.path("task").path("status").path("state").asText(null),
                firstNonBlank(result.path("statusUpdate").path("status").path("state").asText(null),
                        result.path("status").path("state").asText(null)));
        assertThat(frameTaskId)
                .as("[r1] ⭐ 硬 1（FEAT-001 §62）：首帧 taskId 应等于 parent（%s）\n首帧=%s",
                        parentTaskId, firstFrame)
                .isEqualTo(parentTaskId);
        assertThat(frameState)
                .as("[r1] ⭐ 硬 1（FEAT-001 §62）：首帧快照应携带 status.state\n首帧=%s", firstFrame)
                .isNotNull();
        LOG.info("[r1] PASS 硬 1：首帧=父 Task 快照 state=" + frameState);

        // 硬 2：全字段扫描应命中子任务信息（复用 EdpaChildVisibilityScanner）
        EdpaChildVisibilityScanner.Result scan = new EdpaChildVisibilityScanner.Result();
        for (JsonNode frame : allFrames) EdpaChildVisibilityScanner.scanInto(frame, parentTaskId, scan);
        LOG.info("[r1] 子任务可见性扫描: " + scan.summary());

        boolean visible = scan.anyChildEvidence();
        assertThat(visible)
                .as("[r1] ⭐ 硬 2（FEAT-028 R1 主权）：SubscribeToTask 首帧快照 + 后续事件的全字段扫描"
                        + "应命中至少一处子任务信息（子 taskId / 子 agentId / 子 state 之一），"
                        + "实测三项均空——**red-first 承接 issue #93 缺陷簇第 4 处**（与 P0b/P0c/C3 同源）。"
                        + "\n总帧数=%d parent taskId=%s\n"
                        + "承接说明：wire 承载位由设计与开发定义，此处不预设字段形态；本用例修复后自动转 PASS。",
                        allFrames.size(), parentTaskId)
                .isTrue();
        LOG.info("[r1] PASS 硬 2：子任务信息可见（"
                + "childTaskIds=" + scan.childTaskIds.size()
                + " childAgentIds=" + scan.childAgentIds.size()
                + " subStates=" + scan.subStateValues.size() + "）");
    }

    // 保留步骤 1 helper 之下的必要部分，全字段扫描逻辑移到 EdpaChildVisibilityScanner。

    // —— 步骤 1 helper ——

    /**
     * 起流式并行任务，读到父 taskId + WORKING 后立即断开 SSE，返回父 taskId。
     * 模拟"客户端拿到 taskId 后网络断连"的前提。
     */
    private String startParallelAndDetach(String a2aUrl, String contextId) throws Exception {
        String streamBody = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"r1-start-%s\",\"method\":\"SendStreamingMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]}}}",
                UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_HOMOG_PARALLEL);
        HttpRequest req = HttpRequest.newBuilder(URI.create(a2aUrl))
                .timeout(Duration.ofMillis(SSE_FIRST_WORKING_CAP_MS))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(streamBody)).build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) return null;
        String taskId = null;
        String lastState = null;
        long cap = System.currentTimeMillis() + SSE_FIRST_WORKING_CAP_MS;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < cap && (line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                JsonNode result = mapper.readTree(line.substring(5).trim()).path("result");
                if (taskId == null) {
                    taskId = firstNonBlank(result.path("statusUpdate").path("taskId").asText(null),
                            firstNonBlank(result.path("artifactUpdate").path("taskId").asText(null),
                                    firstNonBlank(result.path("task").path("id").asText(null),
                                            result.path("id").asText(null))));
                }
                String st = firstNonBlank(
                        result.path("statusUpdate").path("status").path("state").asText(null),
                        firstNonBlank(result.path("task").path("status").path("state").asText(null),
                                result.path("status").path("state").asText(null)));
                if (st != null) lastState = st;
                if (taskId != null && lastState != null && lastState.contains("WORKING")) break;
            }
        }
        // 借助 try-with-resources 结束流式读取即模拟断连（不消费剩余流）
        return taskId;
    }

    // —— 通用 helpers ——

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }
}
