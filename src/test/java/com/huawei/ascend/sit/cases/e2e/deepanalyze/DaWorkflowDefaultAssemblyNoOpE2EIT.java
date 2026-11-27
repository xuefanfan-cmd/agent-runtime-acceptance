/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S23（L3，2026-10-19 新增；2026-11-24 按 docs#43 裁决反转口径）：**显式关闭 no-op 实测**。
 *
 * <p><b>为什么要这条用例</b>：docs#43 裁决后门控最终口径＝「**缺省开启、可显式关闭**」（产品生效点
 * !954/ab9527a8e 2026-10-28），FEAT-067 对部署的 no-op 支点由「默认部署」改为「**显式关闭**」——
 * 「显式配置 {@code deepanalyze.workflow.enabled=false} ⇒ FEAT-067 对该部署是 no-op」是排除
 * 26 个 FEAT-054 宿主装配面回归用例的支点（范围卡 §3 第 1 条按裁决改写）。
 * 该支点此前只以"依据/理由"的形式出现在多份制品里，<b>无一处挂过运行结果</b>⇒ 属"论证"而非"证据"。
 * 本条把它变成实测。
 *
 * <p><b>反证线索（为什么不能只靠推理）</b>：{@code Feat-Func-067:180}「护栏（actrule.yaml）：六件显式 allow」
 * 改的是**宿主共享配置文件**⇒ 即使六件工具门控关着，护栏规则集也变了，"配置层面 no-op"并不显然成立。
 *
 * <p><b>可观测面的选择（关键，避免"以无证据当证据"）</b>：宿主日志里的 {@code tool=<name>} 只在
 * <b>调用时</b>打印；显式关闭装配下没有人调用六件 ⇒ 用"日志里搜不到六件"来判会把**观测通道失效**误读成
 * **门控生效**。因此本用例改用 {@code event=react_model_call_started … tool_count=N}——
 * 这是**注册期导出量**（该次模型调用可见的工具数），与模型是否调用工具无关。
 * 由于主 Agent 在一次会话里也会出现 {@code tool_count=0} 的调用（run-39 实测 24 次 33 / 2 次 0），
 * 判据取<b>最大值</b>而非全等。
 *
 * <p><b>正向对照（证明该通道看得见六件）</b>：门控**开**的同一制品实测
 * {@code max(tool_count) = 34}（r8 基线 {@code 0.1.3-feat065067-r8} 实测，run-105：默认装配（反转后即开态）
 * 主 Agent 工具数 {@code expected 28 but was 34} 的实测面）。
 * 故显式关闭必须恰为 {@code 34 − 6 = 28}。<b>⚠️ r9 基线（common@babb882b9）需重实测开态值</b>
 * ——WorkflowRunTool ±290 行变更可能改变工具面，执行时先从同轮开态宿主日志取 {@code max(tool_count)}
 * 校准 {@link #TOOL_COUNT_GATE_ON}，再跑本条（类 Javadoc「随制品基线漂移，换基线必须重新实测」）。
 *
 * <p><b>🔴 常量随基线漂移（run-51 实测抓到，如实登记）</b>：本常量原为 {@code 33}
 * （r2 基线 run-39 实测）→ r3 = 34（run-44/45/51 三轮一致）→ r8 = 34（run-105）。
 * **该漂移是测试侧常量过期，不是产品缺陷**，排除依据三条：① 同轮门控开态实测（正向对照，同制品同轮）；
 * ② 关闭装配宿主日志里六件工具名出现次数**全为 0**；③ 差集恰为 6 = 六件。
 * <b>教训</b>：跑之前先查既有证据就能避免误 FAIL。
 * SKILL《执行与证据》「主断言与代表性复核」明写"已有同判据的真实失败证据可复用"⇒ 不必再拉一套开态宿主。
 * 开/关态"差集恰好六件、且不移除任何既有工具"在 L1t 已由
 * {@code DaWorkflowGatingToolSetTest}（067-S11）确定性证明，本条不重复。
 *
 * <p><b>主断言能否拦住目标故障</b>：若显式关闭失效（六件仍被装配）⇒ max=开态 ≠ 开态−6，FAIL；
 * 若门控过度抑制（连既有工具一起丢）⇒ max &lt; 开态−6，FAIL；若宿主根本起不来 ⇒ 栈启动即失败 + 启动标记断言失败。
 *
 * <p><b>已登记的代表性偏差</b>：SUT 条目带 {@code deepanalyze.model.max-iterations: 4}（生产默认 15），
 * 与门控无关但⇒ 本条 PASS **不得**读成"生产 max-iterations 默认下也能起"。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowDefaultAssemblyNoOpE2EIT extends BaseManagedStackTest {

    /** 显式关闭 SUT 条目：与 {@code deepanalyze-feat065067} 逐键相同，且显式带 {@code deepanalyze.workflow.enabled=false}。 */
    private static final String AGENT = "deepanalyze-feat065067-wfoff";

    /** FEAT-067 §2 的六件编排工具（统一门控）。 */
    private static final List<String> SIX_TOOLS = List.of(
            "workflow_run", "delegate_task", "task_output",
            "send_message", "subagent_transcript", "workflow_status");

    /**
     * 门控**开**时主 Agent 的 tool_count 实测值（正向对照）。
     *
     * <p>⚠️ <b>这是随制品基线漂移的量，换基线必须重新实测</b>：
     * r2（{@code common@f9da5b54f}）= 33（run-39）；r3（{@code common@ce9019d86}）= <b>34</b>
     * （run-44 / run-45 / run-51 三轮一致）；r8（run-105）= 34；
     * <b>r9（{@code common@babb882b9}）= 35</b>——2026-11-24 同轮开态宿主日志按 PID 开窗实测
     * （三个 SUT 实例 43100/45068/46488 一致，证据
     * {@code evidence/20261124-r9-run-e2e/}；比 r8 多 1 件，属 WorkflowRunTool 契约内变更）。
     */
    private static final int TOOL_COUNT_GATE_ON = 35;

    /** 显式关闭（{@code deepanalyze.workflow.enabled=false}）时的期望值 = 开态 − 六件。 */
    private static final int TOOL_COUNT_GATE_OFF = TOOL_COUNT_GATE_ON - SIX_TOOLS.size();

    private static final Pattern TOOL_COUNT = Pattern.compile(
            "event=react_model_call_started\\s+agent_id=deepanalyze-agent\\b.*?tool_count=(\\d+)");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(6);
    private static final String SESSION_HEADER = "X-Da-Session-Id";

    @Override
    protected SutStack.Builder buildStack(final TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    DaModelEnvironment.bind(agent);
                    DaModelEnvironment.bindModelKey(agent);
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    @Test
    @Story("067-S23: 显式关闭 no-op 实测（docs#43 反转：no-op 支点=显式 enabled=false）")
    @DisplayName("067-S23 显式配置 deepanalyze.workflow.enabled=false：宿主能起能服务，主 Agent 工具面恰好少六件")
    void explicitDisabledAssemblyIsNoOpForShippedDeployment() throws Exception {
        // ---- ① 显式关闭装配下宿主能起、能完成一次真实会话（no-op 的第一层含义：不影响可用性）----
        final String sessionId = "sess-067-s23-" + UUID.randomUUID().toString().substring(0, 8);
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        final String root = stack.baseUrl(AGENT);
        assertThat(root).as("应能取到显式关闭 SUT 的 base url").isNotBlank();
        final String base = root.endsWith("/") ? root + "api/agents" : root + "/api/agents";

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("input", "请只回复两个字：收到。不要调用任何工具。");
        final HttpRequest run = HttpRequest.newBuilder()
                .uri(URI.create(base + "/run"))
                .timeout(RUN_TIMEOUT)
                .header("Content-Type", "application/json")
                .header(SESSION_HEADER, sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> resp = http.send(run, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Allure.addAttachment("显式关闭装配 POST /api/agents/run 响应（截断 8KB）", "text/plain",
                resp.body().length() > 8192 ? resp.body().substring(0, 8192) : resp.body());
        assertThat(resp.statusCode())
                .as("显式关闭装配下宿主应能正常受理会话并返回 200；实收 body 前 512=%s",
                        resp.body().substring(0, Math.min(512, resp.body().length())))
                .isEqualTo(200);
        final JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.path("taskId").asText()).as("显式关闭装配下应正常产生 taskId").isNotBlank();

        // ---- ② 取宿主日志（注册期可观测面）----
        final ManagedSutInstance noopInstance = (ManagedSutInstance) stack.managedInstance(AGENT);
        final Path log = noopInstance.logFile();
        assertThat(Files.isRegularFile(log)).as("应能定位 SUT 宿主日志：%s", log).isTrue();
        // 【2026-10-27 修正：跨轮累积日志】原实现对 stdout.log **全文**取 tool_count 的 max 并断言等于
        // 门控关态值，同时对全文做 `tool=<name>` 命中扫描。该文件跨轮累积 ⇒ ①任何历史轮出现过更大的
        // tool_count（例如门控开态的轮次）就会**假失败**；②本轮真实值即使不对，只要历史里有对的值也可能
        // **假通过**；③"宿主应启动成功"这条正向自检对全文恒成立，等于没检。改为按本 SUT 实例 PID 开窗。
        final String textAll = Files.readString(log, StandardCharsets.UTF_8);
        final String text = DaOrchestrationE2EBase.windowByPid(textAll, noopInstance.pid());
        Allure.addAttachment("宿主日志路径", "text/plain", log.toAbsolutePath().toString());
        Allure.addAttachment("宿主日志窗口（按本实例 PID 过滤）", "text/plain",
                "pid=" + noopInstance.pid()
                        + "\n窗口内行数=" + DaOrchestrationE2EBase.countLines(text)
                        + "\n全文行数=" + DaOrchestrationE2EBase.countLines(textAll)
                        + "\n（target/sit-logs/<entry>/stdout.log 跨轮累积，判据只允许使用窗口内行）");

        // 正向自检：宿主确实起来了（否则"看不到六件"毫无意义）
        assertThat(text).as("显式关闭装配下宿主应启动成功（否则本条的其它断言都是空转）")
                .contains("Started DeepAnalyzeApplication");

        // ---- ③ 主断言：注册期工具面恰好少六件 ----
        final List<Integer> counts = new ArrayList<>();
        final Matcher m = TOOL_COUNT.matcher(text);
        while (m.find()) {
            counts.add(Integer.parseInt(m.group(1)));
        }
        assertThat(counts)
                .as("观测通道自检：日志里应能解析到主 Agent（agent_id=deepanalyze-agent）的 tool_count；"
                        + "解析到 0 条说明正则或日志格式失效，本条不能据此判门控生效")
                .isNotEmpty();
        final int max = counts.stream().mapToInt(Integer::intValue).max().orElseThrow();
        Allure.addAttachment("tool_count 观测值", "text/plain",
                "全部=" + counts + "\nmax=" + max + "\n期望(显式关闭)=" + TOOL_COUNT_GATE_OFF
                        + "\n正向对照(门控开, run-39)=" + TOOL_COUNT_GATE_ON);
        assertThat(max)
                .as("显式关闭（deepanalyze.workflow.enabled=false）下，主 Agent 可见工具数应恰好为"
                        + " 门控开态 %d − 六件 %d = %d；实收 max=%d，全部观测值=%s。"
                        + " 若等于 %d ⇒ 门控失效（六件被无条件装配，本期代码对默认部署**不是** no-op）；"
                        + " 若小于 %d ⇒ 门控过度抑制（连既有工具一起丢了，同样是 no-op 不成立）",
                        TOOL_COUNT_GATE_ON, SIX_TOOLS.size(), TOOL_COUNT_GATE_OFF, max, counts,
                        TOOL_COUNT_GATE_ON, TOOL_COUNT_GATE_OFF)
                .isEqualTo(TOOL_COUNT_GATE_OFF);

        // ---- ④ 辅断言：六件在显式关闭装配下既未被调用、也未进入权限决策面 ----
        final List<String> leaked = new ArrayList<>();
        for (String tool : SIX_TOOLS) {
            if (text.contains("tool=" + tool)) {
                leaked.add(tool);
            }
        }
        assertThat(leaked)
                .as("显式关闭装配下六件不应出现在任何 tool=<name> 的运行期决策/调用记录里；命中=%s"
                        + "（注：此项为辅助证据，主判据是 ③ 的 tool_count）", leaked)
                .isEmpty();
    }
}
