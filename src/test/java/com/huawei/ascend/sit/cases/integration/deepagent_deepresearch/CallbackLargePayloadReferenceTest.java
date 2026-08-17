package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockCallbackReceiver;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-001 矩阵 <b>D10</b> —— callback <b>大载荷</b>的承载形态。
 *
 * <p><b>Spec 依据</b>：§2「callback 大载荷引用」/§5.1.4：文件类、多模态类、artifact 大正文或超过回调
 * 承载策略的结果必须沿用 {@code SendMessage} result 中 SDK/A2A 可表达的 <b>artifact / file/data part /
 * metadata 或 Task 查询引用</b>承载，<b>不得为 callback 单独发明强制字段</b>；必要时可经 {@code GetTask}
 * 取回全文。
 *
 * <p><b>先行实测事实（2026-08-17）</b>：D2 运行观察到 COMPLETED 回调 body ~30KB，artifacts 全量<b>内联
 * 直发</b>。「超过回调承载策略」的阈值属实现策略，内联不自动判违约；本用例的<b>硬断言</b>钉在 spec 的
 * 确定性 MUST 上：
 * <ol>
 *   <li>body 顶层键 ⊆ {notificationId, jsonrpc, id, result}——无 callback 自造强制字段；</li>
 *   <li>artifacts parts 均为标准 A2A part 形态（text/file/data 至少其一），无自造必备字段；</li>
 *   <li>{@code GetTask} 可取回全文：callback 内联的 artifact 文本必须能在 GetTask 快照中找到
 *       （引用一致性——callback 不是内容的唯一来源）。</li>
 * </ol>
 * 内联/引用的形态选择作为<b>观察记录</b>输出（≥10KB 内联时记录为「策略=内联直发」），供与开发
 * 对齐承载策略阈值，不作硬断言。
 *
 * <p><b>Tag</b>：manual —— 依赖本地 search jar + 真实 LLM/检索；prompt 刻意选高产出查询制造大正文。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.callback-large-payload-reference (D10): 大正文沿 A2A 表面承载、无自造字段、GetTask 可取回全文")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackLargePayloadReferenceTest {

    private static final Logger LOG = Logger.getLogger(CallbackLargePayloadReferenceTest.class.getName());
    private static final String SEARCH = "search";
    /** 高产出查询：宽泛主题 + 显式要求多来源与完整摘要，稳定产出大段检索结果（2026-08-17 同类查询实测 ~30KB）。 */
    private static final String USER_INPUT =
            "查一下近一周现货黄金行情和主要机构对后市金价的预测，至少给 8 条来源，每条保留完整摘要片段，不要精简。";

    private static final long TERMINAL_POLL_TIMEOUT_MS = 100_000;
    private static final long DELIVERY_WAIT_MS = 45_000;
    private static final int LARGE_BODY_THRESHOLD = 10_240;
    private static final Set<String> ALLOWED_TOP_LEVEL = Set.of("notificationId", "jsonrpc", "id", "result");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private TestConfig config;
    private SutStack searchStack;
    private MockCallbackReceiver receiver;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        receiver = MockCallbackReceiver.start();
        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a.property("openjiuwen.demo.search-agent.api-key",
                        System.getenv("LLM_API_KEY")))
                .start();
        LOG.info("[d10-large] search ready at " + searchStack.baseUrl(SEARCH)
                + ", receiver at " + receiver.callbackUrl());
    }

    @AfterAll
    void tearDown() {
        if (searchStack != null) searchStack.close();
        if (receiver != null) receiver.close();
    }

    @Test
    @DisplayName("FEAT-001.callback-large-payload (D10): 大载荷沿 A2A 表面承载；顶层无自造字段；GetTask 可取回全文")
    void largePayloadReusesA2aSurfaceAndIsRetrievableViaGetTask() throws Exception {
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"d10-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"ctx-d10-%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"taskPushNotificationConfig\":{\"id\":\"pn-%s\",\"url\":\"%s\"},"
                        + "\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                UUID.randomUUID().toString().substring(0, 8), USER_INPUT,
                UUID.randomUUID().toString().substring(0, 8), receiver.callbackUrl());
        HttpResponse<String> ack = post(body);
        assertThat(ack.statusCode()).isEqualTo(200);
        String taskId = mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null);
        assumeTrue(taskId != null, "未取到 taskId，INCONCLUSIVE");

        String terminal = pollTerminal(taskId);
        assumeTrue("TASK_STATE_COMPLETED".equals(terminal),
                "终态=" + terminal + "（非 COMPLETED，大载荷场景未成立），INCONCLUSIVE");
        assertThat(receiver.awaitAtLeast(1, DELIVERY_WAIT_MS)).as("终态后应有投递").isTrue();

        String payload = receiver.captured().get(0).body();
        int size = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        JsonNode root = mapper.readTree(payload);

        // 硬断言 1：顶层键白名单——无 callback 自造强制字段。
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            assertThat(ALLOWED_TOP_LEVEL)
                    .as("§5.1.4: callback body 顶层出现非 A2A/JSON-RPC 表面的自造字段 '%s'（body 顶层键须 ⊆ %s）",
                            key, ALLOWED_TOP_LEVEL)
                    .contains(key);
        }

        // 硬断言 2：artifacts parts 均为标准 part 形态。
        JsonNode artifacts = root.path("result").path("task").path("artifacts");
        assumeTrue(artifacts.isArray() && artifacts.size() > 0, "callback 无 artifacts，大载荷断言无对象，INCONCLUSIVE");
        StringBuilder cbText = new StringBuilder();
        for (JsonNode artifact : artifacts) {
            for (JsonNode part : artifact.path("parts")) {
                boolean standard = part.has("text") || part.has("file") || part.has("data");
                assertThat(standard)
                        .as("§5.1.4: artifact part 须为标准 A2A 形态（text/file/data 至少其一），实测键=%s",
                                fieldNamesOf(part))
                        .isTrue();
                cbText.append(part.path("text").asText(""));
            }
        }

        // 硬断言 3：GetTask 可取回全文（callback 内联文本 ⊆ GetTask 快照文本）。
        HttpResponse<String> gt = post(String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                UUID.randomUUID().toString().substring(0, 8), taskId));
        assertThat(gt.statusCode()).isEqualTo(200);
        StringBuilder gtText = new StringBuilder();
        JsonNode gtRoot = mapper.readTree(gt.body());
        JsonNode gtArtifacts = gtRoot.path("result").path("task").path("artifacts");
        if (!gtArtifacts.isArray() || gtArtifacts.size() == 0) {
            gtArtifacts = gtRoot.path("result").path("artifacts"); // GetTask 裸 Task 形状
        }
        for (JsonNode artifact : gtArtifacts) {
            for (JsonNode part : artifact.path("parts")) gtText.append(part.path("text").asText(""));
        }
        String cbSample = cbText.length() > 200 ? cbText.substring(0, 200) : cbText.toString();
        assertThat(gtText.toString())
                .as("§2/§5.1.4: 必要时可经 GetTask 取回全文——callback 内联文本必须能在 GetTask 快照中找到"
                        + "（前 200 字符抽样比对）")
                .contains(cbSample);

        // 观察记录：承载策略（内联 vs 引用）。
        LOG.info(String.format("[d10-large] body=%d bytes, artifacts=%d, 承载策略=%s（阈值 %d；策略选择属实现口径，"
                        + "记录供与开发对齐，不作硬断言）",
                size, artifacts.size(),
                size >= LARGE_BODY_THRESHOLD ? "大正文内联直发" : "正文规模未达大载荷阈值",
                LARGE_BODY_THRESHOLD));
        assumeTrue(size >= LARGE_BODY_THRESHOLD,
                "本轮回调 body=" + size + " bytes 未达大载荷阈值，三条硬断言已过但「大载荷」前提弱，标 INCONCLUSIVE 供复跑");
    }

    // —— helpers ——

    private static String fieldNamesOf(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        n.fieldNames().forEachRemaining(k -> sb.append(k).append(","));
        return sb.toString();
    }

    private String pollTerminal(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + TERMINAL_POLL_TIMEOUT_MS;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = post(String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                    UUID.randomUUID().toString().substring(0, 8), taskId));
            if (resp.statusCode() == 200) {
                // wire 事实（2026-08-17）：GetTask 的 result 是裸 Task；SendMessage ack 才包 task 一层。
                JsonNode root = mapper.readTree(resp.body());
                String s = root.path("result").path("task").path("status").path("state").asText(null);
                if (s == null) s = root.path("result").path("status").path("state").asText(null);
                if (s != null) {
                    last = s;
                    if (s.contains("COMPLETED") || s.contains("FAILED")
                            || s.contains("CANCELED") || s.contains("REJECTED")) return s;
                }
            }
            Thread.sleep(2_000);
        }
        return last;
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(searchStack.baseUrl(SEARCH) + "/a2a"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
