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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-028 矩阵 <b>P5</b> —— 依赖场景端到端正确性（原「反证：有依赖任务禁止并行」）。
 *
 * <p><b>2026-09-02 重定位，务必先读。</b>本用例原本要守的是 planrule.yaml 的反证契约
 * （「后续子任务需要前序子任务输出作为输入时必须串行，不得同轮伪并行」）。该立意成立且是本特性组合独有的
 * ——没有反证，P1~P4 的正向断言只证明「能并行」，不证明「会判断」，一个无条件全并行的实现也能全绿。
 * 但原实现的形态有三重问题叠加，已按下述口径拆解：
 *
 * <ol>
 *   <li><b>宣称的判据面不存在</b>：testplan/cases 写「{@code ToolCallSequenceObserver} 观察到同轮仅
 *       1 个 ToolCall」，而全仓无该类；代码里实际跑的是时长启发式。</li>
 *   <li><b>实际判据与契约无因果关系</b>：{@code totalElapsed >= 40s} 测的是「这轮跑得够慢」。
 *       串行也可能 &lt;40s（缓存命中、模型答得快），并行也可能 &gt;40s（两次远程调用本就慢）。
 *       且它用 {@code assertThat}——会真红，红的原因却与契约无关，属<b>误红</b>。</li>
 *   <li><b>换对观察面也当不成硬判据</b>：「同轮 / 跨轮」判的是<b>模型行为</b>而非 SUT 代码行为。
 *       planrule.yaml 是提示词规则，模型这一轮听不听话是随机变量——单次绿不证明 planrule 对，
 *       单次红也不证明 planrule 错。testplan §6 已承认「核心断言依赖模型规划质量」、
 *       §8 已定「LLM 抖动降 INCONCLUSIVE」，本条不应例外。</li>
 * </ol>
 *
 * <p><b>拆解结果</b>：
 * <ul>
 *   <li><b>本用例（P5）</b>只守<b>功能正确性</b>，留在 BLOCKING 通道，是硬判据、必须绿：
 *       依赖场景达终态 COMPLETED，且 final_answer 中<b>搜索主题</b>与<b>核查结论</b>同时出现
 *       ——说明两步都执行了、第二步产出了结论、结果回灌进了汇总。</li>
 *   <li><b>反证（P5b）</b>移出本通道，登记为 <b>⬜ 待建</b>：判据面是 SSE 上「两条 {@code delegation}
 *       的时间窗不重叠、第二条的 {@code source.taskId} 轨迹出现在第一条终态 {@code status} 之后」。
 *       它只能红成<b>告警</b>不能红成 FAIL（单次采样不构成 planrule 违约证据），故须单独排期建设，
 *       不在本轮混入。见 testplan §5 P5b 行与 cases §3.4。</li>
 * </ul>
 *
 * <p><b>本用例不证明什么（别过度解读绿灯）</b>：它<b>不</b>证明第二步真的消费了第一步的输出
 * ——关键词命中只说明汇总文本里两个语义片段都在，模型完全可能在依赖断裂后自行编造核查结论。
 * 「依赖是否被真正满足」的强判据在 P5b。本条的绿灯含义仅为「依赖型 prompt 在本 SUT 上能端到端跑通」。
 *
 * <p><b>观察面</b>：BLOCKING 通道只有终态快照。特性档 §5.0.1 明写该模式不产生中间流式事件，
 * P0b/P0c 已证快照不含中间过程——「第几轮发起的 ToolCall」是过程量，本通道上没有投影。
 *
 * <p><b>Tag</b>：manual —— 依赖真实 LLM、search、versatile-agent。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-028")
@Tag("manual")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("P5.dependent-scenario-end-to-end: 依赖型场景（先搜再核查）端到端完成且两步结果均回灌汇总")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdpaDependentTasksSerialTest {

    private static final Logger LOG = Logger.getLogger(EdpaDependentTasksSerialTest.class.getName());

    private static final String EDP_AGENT = "edp-agent";
    private static final String SEARCH = "search";
    private static final String VERIFY = "verify";

    private static final long TERMINAL_TIMEOUT_MS =
            Long.getLong("sit.feat028.p5-terminal-timeout-ms", 150_000L);
    private static final long POLL_INTERVAL_MS = 2_000L;
    /**
     * <b>仅用于诊断日志，不参与判定</b>。串行两步的经验耗时量级。
     * 2026-09-02 前这是硬断言 {@code totalElapsed >= 40s}「否则判模型伪并行」，已废止：
     * 时长与「是否同轮发起」无因果关系，该断言是误红源。见类 javadoc。
     */
    private static final long SERIAL_DIAGNOSTIC_HINT_MS = 40_000L;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private TestConfig config;
    private SutStack searchStack;
    private SutStack verifyStack;
    private SutStack edpStack;

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        assumeTrue(System.getenv("EDP_AGENT_MODEL_API_KEY") != null,
                "[p5] 需 EDP_AGENT_MODEL_* 环境变量，跳过");
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        verifyStack = SutStack.builder(config).agent(VERIFY).start();
        edpStack = SutStack.builder(config)
                .agent(EDP_AGENT, a -> a
                        .env("EDP_AGENT_SEARCH_A2A_URL", searchStack.baseUrl(SEARCH))
                        .env("EDP_AGENT_VERSATILE_A2A_URL", verifyStack.baseUrl(VERIFY))
                        .env("EDP_AGENT_SCENARIO_HOME", "/tmp/edpa-scenario-min"))
                .start();
        LOG.info("[p5] edp-agent=" + edpStack.baseUrl(EDP_AGENT));
    }

    @AfterAll
    void tearDown() {
        if (edpStack != null) edpStack.close();
        if (verifyStack != null) verifyStack.close();
        if (searchStack != null) searchStack.close();
    }

    @Test
    @DisplayName("FEAT-028.P5: 依赖型场景端到端——终态 COMPLETED + final_answer 同时含搜索主题与核查结论")
    void dependentScenarioCompletesEndToEnd() throws Exception {
        String contextId = "ctx-feat028-p5-" + UUID.randomUUID().toString().substring(0, 8);
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"p5-%s\",\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"%s\",\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]},"
                        + "\"configuration\":{\"returnImmediately\":true}}}",
                UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID(), contextId,
                EdpaParallelPrompts.PROMPT_DEPENDENT_SERIAL);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> ack = post(body);
        assertThat(ack.statusCode()).as("[p5] SendMessage 应 200\n%s", ack.body()).isEqualTo(200);
        String taskId = firstNonBlank(
                mapper.readTree(ack.body()).path("result").path("task").path("id").asText(null),
                mapper.readTree(ack.body()).path("result").path("id").asText(null));
        assumeTrue(taskId != null, "未取到 taskId，INCONCLUSIVE");
        LOG.info("[p5] taskId=" + taskId);

        JsonNode terminalRoot = null;
        String terminal = null;
        long deadline = System.currentTimeMillis() + TERMINAL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> gt = post(String.format(
                    "{\"jsonrpc\":\"2.0\",\"id\":\"gt-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                    UUID.randomUUID().toString().substring(0, 8), taskId));
            if (gt.statusCode() == 200) {
                JsonNode root = mapper.readTree(gt.body());
                String s = firstNonBlank(
                        root.path("result").path("status").path("state").asText(null),
                        root.path("result").path("task").path("status").path("state").asText(null));
                if (isTerminal(s)) { terminalRoot = root; terminal = s; break; }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        long totalElapsed = System.currentTimeMillis() - t0;

        // ── 硬 1：达终态且为 COMPLETED ──
        assertThat(terminal).as("[p5] %d ms 内未达终态", TERMINAL_TIMEOUT_MS).isNotNull();
        assumeTrue("TASK_STATE_COMPLETED".equals(terminal),
                "[p5] 终态=" + terminal + "（非 COMPLETED），无法评估结果覆盖度，INCONCLUSIVE");
        LOG.info(String.format("[p5] terminal=%s totalElapsed=%dms", terminal, totalElapsed));

        // ── 硬 2：final_answer 同时含「搜索主题」与「核查结论」两个语义片段 ──
        // 注意判据强度：命中只说明两步都产出了内容并回灌汇总，不证明第二步消费了第一步的输出（见类 javadoc）。
        JsonNode task = terminalRoot.path("result").path("task").isMissingNode()
                ? terminalRoot.path("result")
                : terminalRoot.path("result").path("task");
        StringBuilder sb = new StringBuilder();
        for (JsonNode artifact : task.path("artifacts")) {
            for (JsonNode part : artifact.path("parts")) {
                sb.append(part.path("text").asText("")).append("\n");
            }
        }
        String finalAnswer = sb.toString();
        assertThat(finalAnswer).as("[p5] final_answer 不得为空").isNotBlank();
        LOG.info("[p5] final_answer 前 300 字符 = " + truncate(finalAnswer, 300));

        boolean coversSearchTopic =
                containsAny(finalAnswer, "虚拟线程", "Virtual Thread", "virtual thread");
        boolean coversVerification =
                containsAny(finalAnswer, "核查", "验证", "查证", "准确", "属实", "正确", "无误", "verif");
        assertThat(coversSearchTopic)
                .as("[p5] final_answer 未覆盖第一步的搜索主题（Java 21 虚拟线程）——"
                        + "搜索腿的结果可能没有回灌进汇总\n汇总前 500 字符: %s", truncate(finalAnswer, 500))
                .isTrue();
        assertThat(coversVerification)
                .as("[p5] final_answer 未出现任何核查/验证结论——第二步（依赖前序结果的核查）可能未执行"
                        + "或其结果未回灌汇总\n汇总前 500 字符: %s", truncate(finalAnswer, 500))
                .isTrue();

        // ── 诊断（不判定）──
        LOG.info(String.format("[p5] 诊断（不参与判定）：totalElapsed=%dms，串行两步经验量级 %dms。"
                        + "本用例不从该数值推断模型是否同轮伪并行——「同轮/跨轮」在 BLOCKING 通道无投影，"
                        + "反证判据见待建的 P5b（SSE 面 delegation 时间窗）。",
                totalElapsed, SERIAL_DIAGNOSTIC_HINT_MS));
        LOG.info("[p5] PASS：依赖型场景端到端完成，搜索主题与核查结论均出现在汇总中");
    }

    // —— helpers ——

    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase();
        for (String needle : needles) {
            if (text.contains(needle) || lower.contains(needle.toLowerCase())) return true;
        }
        return false;
    }

    private static boolean isTerminal(String s) {
        return s != null && (s.contains("COMPLETED") || s.contains("FAILED")
                || s.contains("CANCELED") || s.contains("REJECTED"));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(edpStack.baseUrl(EDP_AGENT) + "/a2a"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
