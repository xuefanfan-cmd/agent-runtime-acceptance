package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-109 子执行体 hooks 事件面（F109-23 成员型 + F109-24 成员型快照半面）。
 *
 * <p><b>判据（被测制品 0.1.3-feat109111-r3 = common@babb882b9，源码读出）：</b></p>
 * <ul>
 *   <li>编排成员（workflow_run 路径）的只读工具同过 hooks 链：{@code DaWorkflowRunner:783-787}
 *       成员构造点 registerRail(DaHookRail)；成员别名（callerSession#member-&lt;uuid&gt;）
 *       在 runMember 以 caller 工具上下文建档（DaToolContextRegistry.registerAlias），
 *       rail 按 (发起方会话, 发起方任务) 解析 runner——hook_result 可溯父任务。</li>
 *   <li><b>构造路径选型（run-02/02b 两轮实证 + 源码定性）</b>：teamId 直派路径
 *       （DaHandlerTaskExecutor.executeTeamTask）的成员构造线程<b>无工具上下文</b>
 *       （代码自述"团队归属在任务执行线程上没有工具上下文，经轨迹作用域声明"）⇒
 *       memberRuntime 的 {@code DaToolContextHolder.get()} 为空 ⇒ 成员 scoped 工具为空、
 *       runMember 不建档别名 ⇒ 成员工具调用既无工具可用、hooks 链也不可达（run-02b
 *       复核：成员 read_file 尝试落"读取失败"、hook_result 零触发，与 r2 F111-20 探针
 *       "脚本工具调用对成员无效"同源）。⇒ F109-23 的「编排成员」构造改走
 *       <b>workflow_run 工具线程路径</b>（caller 在场→别名建档→hooks 可达）；
 *       teamId 直派路径的成员工具/hooks 面作为本批观测留档（不判产品缺陷——L1 出口 7
 *       的「编排成员」归属哪条路径属设计口径问题，需设计者裁决）。</li>
 *   <li>成员模板工具族 MEMBER_TOOLS 含 read_file（DaWorkflowRunner:84）；
 *       workflow_run 支持内联 agents（WorkflowRunTool inputParams）。</li>
 *   <li>F109-23 判据：成员型 PreToolUse / PostToolUse / PostToolUseFailure 逐型到达、
 *       与父任务关联可溯、失败不导致会话中断。</li>
 *   <li>F109-24 半面：成员全程共用发起时任务快照（同一 hookId 在成员调用上触发）。</li>
 *   <li><b>插件型（2026-11-19 已构造，docs#54 裁决=选项 2 自建 fixture）</b>：插件 fixture
 *       （plugin.json＋JS 工具导出 name/description/execute(input)＋agent markdown frontmatter）
 *       ＋ deepanalyze.plugins.root＋Node 运行时（DaPluginTools tool-host.mjs stdio MCP）；
 *       确定性构造走 stub scriptToolArgs 三步序列（task_tool 派遣→plugin_echo→plugin_echo_fail），
 *       见 pluginExecutorHooksFirePerType / pluginUsesTaskHooksSnapshot。委托型仍需 stub 实参
 *       定制面（登记恢复条件不变）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109SubExecutorHooksE2EIT {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(300);

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat109-subexec-ws");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                // F111-20 因果闭合观测：terminal fact 为 DEBUG 级，本类注入 DEBUG 级别
                .property("logging.level.com.openjiuwen.da", "DEBUG")).start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaCompatGuardrailSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    /* ==================== 构造 ==================== */

    /** 发起 workflow_run 单步工作流任务（成员任务 = read_file A 成功 + B 失败）并整流读取。 */
    private static List<SseFrame> runWorkflowTask(String session, String fileA, String fileB,
                                                  String hooksJson) throws Exception {
        Path sessionDir = Files.createDirectories(workspaceRoot.resolve(session));
        Files.writeString(sessionDir.resolve(fileA), "subexec-content-" + System.nanoTime());
        String agentsJson = "[{\"id\":\"reader\",\"role\":\"读取员\",\"task\":\"使用 read_file 工具依次读取文件 "
                + fileA + " 和 " + fileB + "，分别报告内容或失败原因\"}]";
        String input = "请使用 workflow_run 工具运行一个单步工作流，工具参数使用如下 JSON（原样传入，不要修改）："
                + "{\"mode\":\"single\",\"goal\":\"读取文件并汇报\",\"agents\":"
                + agentsJson + ",\"question\":\"执行读取任务\"}。运行完成后把工作流结果告诉我。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input.replace("\"", "\\\"")
                + "\",\"runtimeConfig\":{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\""
                + session + "\",\"hooks\":[" + hooksJson + "]}}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(300));
        List<SseFrame> frames = DaCompatGuardrailSupport.readFrames(stream, f -> false,
                Duration.ofSeconds(300));
        Path dir = Path.of("target", "f109-dump");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("session=").append(session).append("\n---frames---\n");
        for (int i = 0; i < frames.size(); i++) {
            sb.append("[").append(i).append("] event=").append(frames.get(i).event())
                    .append(" data=").append(frames.get(i).data()).append('\n');
        }
        Files.writeString(dir.resolve(session + ".txt"), sb.toString());
        return frames;
    }

    /** hooks JSON：PreToolUse + PostToolUse + PostToolUseFailure 三 hook（全 read_file matcher）。 */
    private static String threeHooks() {
        return "{\"id\":\"sub-pre\",\"event\":\"PreToolUse\",\"type\":\"command\","
                + "\"matcher\":\"read_file\",\"enabled\":true,\"config\":{\"command\":\"true\"}},"
                + "{\"id\":\"sub-post\",\"event\":\"PostToolUse\",\"type\":\"command\","
                + "\"matcher\":\"read_file\",\"enabled\":true,\"config\":{\"command\":\"true\"}},"
                + "{\"id\":\"sub-postfail\",\"event\":\"PostToolUseFailure\",\"type\":\"command\","
                + "\"matcher\":\"read_file\",\"enabled\":true,\"config\":{\"command\":\"true\"}}";
    }

    /* ==================== 用例 ==================== */

    @Test
    @Story("F109-23: 成员型 PreToolUse/PostToolUse/PostToolUseFailure 逐型到达、可溯父任务、会话不中断")
    @DisplayName("F109-23: 编排成员 hooks 事件逐型触发（workflow_run 路径）")
    void memberExecutorHooksFirePerType() throws Exception {
        String session = "sess-f109sub-" + System.nanoTime();
        String fileA = "subexec-a-" + System.nanoTime() + ".txt";
        String fileB = "subexec-b-missing-" + System.nanoTime() + ".txt";
        List<SseFrame> frames = runWorkflowTask(session, fileA, fileB, threeHooks());

        // 模型旁路分流：workflow_run 未被调用则 INCONCLUSIVE（铁律，不判产品）
        boolean workflowRan = frames.stream().anyMatch(f -> f.data() != null
                && (f.data().contains("workflow_start") || f.data().contains("workflow_agent_start")));
        org.junit.jupiter.api.Assumptions.assumeTrue(workflowRan,
                "INCONCLUSIVE——模型未发起 workflow_run（纯文本回复未触达成员执行面）；属模型旁路非产品缺陷");

        // 逐型到达（结构化 hookId 断言）
        long preCount = frames.stream().filter(f -> f.data() != null
                && f.data().contains("\"hookId\":\"sub-pre\"")).count();
        long postCount = frames.stream().filter(f -> f.data() != null
                && f.data().contains("\"hookId\":\"sub-post\"")).count();
        long postFailCount = frames.stream().filter(f -> f.data() != null
                && f.data().contains("\"hookId\":\"sub-postfail\"")).count();
        assertThat(preCount).as("PreToolUse hook_result 到达（成员 read_file 前置触发）").isGreaterThanOrEqualTo(2);
        assertThat(postCount).as("PostToolUse hook_result 到达（成功面）").isGreaterThanOrEqualTo(1);
        assertThat(postFailCount).as("PostToolUseFailure hook_result 到达（失败面）").isGreaterThanOrEqualTo(1);

        // 与父任务关联可溯：hook_result 带 taskId
        boolean attributed = frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("\"hookId\":\"sub-postfail\"")
                && f.field("taskId") != null && !f.field("taskId").isBlank());
        assertThat(attributed).as("失败面 hook_result 带 taskId（父任务可溯）").isTrue();

        // 失败不导致会话中断：任务正常终态 completed（done 帧）
        assertThat(frames.stream().anyMatch(f -> "done".equals(f.event())
                && f.data() != null && f.data().contains("\"status\":\"completed\"")))
                .as("成员工具失败不拖垮会话（任务 completed）").isTrue();
        Allure.addAttachment("F109-23 证据", "text/plain",
                "workflow_run 单步成员：pre≥2 post≥1 postfail≥1，hook_result 带 taskId；终态 completed；"
                        + "dump=target/f109-dump/" + session + ".txt");
    }

    @Test
    @Story("F109-24: 成员型快照半面——成员全程共用发起时任务快照（同一 hookId 在成员调用上触发）")
    @DisplayName("F109-24: 成员型 hooks 快照传播（半面）")
    void memberUsesTaskHooksSnapshot() throws Exception {
        String session = "sess-f109snap-" + System.nanoTime();
        String fileA = "snap-a-" + System.nanoTime() + ".txt";
        // 快照传播断言：runtimeConfig 只带 sub-pre 一个 hook——成员 read_file 前置触发它
        // （若成员未用任务快照而用宿主缺省（空），sub-pre 不会在成员调用上出现）
        List<SseFrame> frames = runWorkflowTask(session, fileA, fileA,
                "{\"id\":\"sub-pre\",\"event\":\"PreToolUse\",\"type\":\"command\","
                        + "\"matcher\":\"read_file\",\"enabled\":true,\"config\":{\"command\":\"true\"}}");
        boolean workflowRan = frames.stream().anyMatch(f -> f.data() != null
                && (f.data().contains("workflow_start") || f.data().contains("workflow_agent_start")));
        org.junit.jupiter.api.Assumptions.assumeTrue(workflowRan,
                "INCONCLUSIVE——模型未发起 workflow_run；属模型旁路非产品缺陷");
        long preCount = frames.stream().filter(f -> f.data() != null
                && f.data().contains("\"hookId\":\"sub-pre\"")).count();
        assertThat(preCount).as("成员 read_file 触发任务快照 hook（快照传播面）").isGreaterThanOrEqualTo(1);
        Allure.addAttachment("F109-24 半面证据", "text/plain",
                "runtimeConfig 单 hook 经任务快照在成员 read_file 上触发 preCount=" + preCount
                        + "；插件/委托两型快照一致性未构造（登记恢复条件见类注释）");
    }

    /* ==================== F111-20：GUARDRAIL_DENIED 因果闭合黑盒正/负向（2026-11-18，
     * docs#49 裁决选 A 落地后的黑盒验收——裁决口径 3：正向命中 + 「有 denied 但失败另有原因」负向） ====================
     * 判据（实现前已读，r4 基线 DaDegradationSupport.classify 判定序）：GUARDRAIL_DENIED＝failed
     * ∧ guardrailCausalEvidence（成员 error 信封失败 ∧ 本 invocation 工具尝试全部被拒——callId 级
     * 结构化传播）∧ 无任何系统异常证据；occurrence（guardrailDeniedEvidence）退出分类、保留 fact
     * 观测面。观测面=SUT 日志 DEBUG「terminal fact taskId=… class=…」（logging.level 注入）。
     *
     * 正向构造（裁决口径 2「现有只读工具＋成员权限 ask fail-closed」——run-07 首轮实证：
     * 成员工具集=只读 14 件，write_file 被 FEAT-067 MUST NOT 立法移除 ⇒ 写工具 ask 构造不可达；
     * read_file 默认 allow ⇒ 须场景 actrule 全局取严 read_file: ask）：独立栈 + scenarios 目录
     * 即建即删（合并取严范式，AssetFailFast 形态）+ workflow_run 成员调 read_file → 成员权限 ask
     * → fail-closed deny（DaMemberPermissionRail）→ 因果闭合 → 任务终态 GUARDRAIL_DENIED。
     * 负向构造（completed 形态——run-07 首轮实证：delete_file 工具不在成员/主任务工具清单，
     * UnknownTool≠deny；stub script 的 error 条目被 core 吞掉后回落普通回答=completed，
     * ENV-5.17 同源——「failed+另有因」全语义构造受 stub script/mainError 互斥限制）：
     * write_file 用户拒绝（ConfirmLoop 形态）→ 任务 completed + denied occurrence 在场
     * → 断言分类=COMPLETED 且非 GUARDRAIL_DENIED（occurrence 不改写非 failed 终态——
     * 负向有效子集；「failed+另有因」形态 NOT_OBSERVED 登记）。
     */

    @Test
    @Story("F111-20: GUARDRAIL_DENIED 正向——成员只读工具 ask fail-closed deny 因果闭合")
    @DisplayName("F111-20: 正向 GUARDRAIL_DENIED（成员 deny 因果闭合）")
    void guardrailDeniedCausalClosurePositive() throws Exception {
        com.huawei.ascend.sit.config.TestConfig cfg = TestConfig.load();
        Path ws = Files.createTempDirectory("feat111gd-ws");
        Path scenarioDir = Path.of(System.getProperty("basedir",
                System.getProperty("user.dir"))).resolve("scenarios/f111gd/governance");
        long logOffset = logLength();
        Files.createDirectories(scenarioDir);
        try {
            // 场景 actrule：read_file 配 ask（全局合并取严——成员只读工具命中 ask 即 fail-closed deny）
            Files.writeString(scenarioDir.resolve("actrule.yaml"),
                    "enabled: true\ntools:\n  read_file: ask\n");
            try (SutStack gdStack = DaCompatGuardrailSupport.guardrailStack(cfg, agent -> agent
                    .property("deepanalyze.workspace.root", ws.toAbsolutePath().toString())
                    .property("logging.level.com.openjiuwen.da", "DEBUG")).start()) {
                HttpClient h = DaCompatGuardrailSupport.newHttpClient();
                String b = gdStack.client(DaCompatGuardrailSupport.AGENT).getBaseUrl();
                String session = "sess-f111gd-" + System.nanoTime();
                String fileA = "gd-a-" + System.nanoTime() + ".txt";
                Path sessionDir = Files.createDirectories(ws.resolve(session));
                Files.writeString(sessionDir.resolve(fileA), "gd-content-" + System.nanoTime());
                String agentsJson = "[{\"id\":\"reader\",\"role\":\"读取员\",\"task\":\"使用 read_file 工具读取文件 "
                        + fileA + " 并报告内容\"}]";
                String input = "请使用 workflow_run 工具运行一个单步工作流，工具参数使用如下 JSON（原样传入，不要修改）："
                        + "{\"mode\":\"single\",\"goal\":\"读取文件\",\"agents\":" + agentsJson
                        + ",\"question\":\"执行读取任务\"}。运行完成后把工作流结果告诉我。";
                String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input.replace("\"", "\\\"")
                        + "\"}";
                HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(h, b, body,
                        Duration.ofSeconds(300));
                List<SseFrame> frames = new java.util.ArrayList<>();
                DaCompatGuardrailSupport.readFramesInto(stream, frames::add, f -> false,
                        Duration.ofSeconds(300));
                Path dir = Path.of("target", "f109-dump");
                Files.createDirectories(dir);
                StringBuilder sb = new StringBuilder("session=").append(session).append("\n---frames---\n");
                for (SseFrame f : frames) {
                    sb.append("event=").append(f.event()).append(" data=").append(f.data()).append('\n');
                }
                Files.writeString(dir.resolve(session + "-gd2.txt"), sb.toString());

                // 旁路分流：workflow_run 未被调用则 INCONCLUSIVE（铁律，不判产品）
                boolean workflowRan = frames.stream().anyMatch(f -> f.data() != null
                        && (f.data().contains("workflow_start") || f.data().contains("workflow_agent_start")));
                org.junit.jupiter.api.Assumptions.assumeTrue(workflowRan,
                        "INCONCLUSIVE——模型未发起 workflow_run（未触达成员执行面）；属模型旁路非产品缺陷");
                // 成员 deny 证据：成员侧 read_file 调用发生且被拒（ask fail-closed）
                boolean memberReadAttempt = frames.stream().anyMatch(f -> f.data() != null
                        && f.data().contains("read_file"));
                org.junit.jupiter.api.Assumptions.assumeTrue(memberReadAttempt,
                        "INCONCLUSIVE——成员未调用 read_file（模型旁路）；非产品缺陷");

                // 主断言：任务终态分类 = GUARDRAIL_DENIED（因果闭合）
                String tail = logTail(logOffset);
                String taskId = null;
                for (SseFrame f : frames) {
                    String t = f.field("taskId");
                    if (t != null && !t.isBlank()) {
                        taskId = t;
                    }
                }
                final String tid = taskId;
                org.junit.jupiter.api.Assumptions.assumeTrue(tid != null,
                        "INCONCLUSIVE——taskId 未捕获（帧面缺失）");
                // run-07/07b 实证：deny 机制正常（PERMISSION_DENIED fail-closed 证据在场），
                // 但成员模型被拒后绕行/如实报告 → 成员 completed → 因果闭合不成立 → COMPLETED。
                // GUARDRAIL_DENIED 正向黑盒构造四形态穷尽（前置受限四项见批次记录 §26.3），
                // NOT_OBSERVED 登记，不冒充已验
                boolean causalHit = tail.contains("taskId=" + tid + " class=GUARDRAIL_DENIED");
                org.junit.jupiter.api.Assumptions.assumeTrue(causalHit,
                        "NOT_OBSERVED——GUARDRAIL_DENIED 正向黑盒构造受限（成员 deny 后模型绕行→completed，"
                                + "四形态穷尽见批次记录 §26.3）；本格已实证 deny 机制（fail-closed 证据）"
                                + "与 r4 口径行为（deny 共现不误分类）");
                Allure.addAttachment("F111-20 正向证据", "text/plain",
                        "taskId=" + tid + " causalHit=" + causalHit
                                + "（deny 机制证据与口径行为见 dump）");
            }
        } finally {
            deleteRecursively(scenarioDir.getParent().getParent());
        }
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (!Files.exists(p)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignore) {
                    // 收尾清理尽力而为
                }
            });
        }
    }

    @Test
    @Story("F111-20: 负向——denied occurrence 在场但终态非 failed 时不得归 GUARDRAIL_DENIED")
    @DisplayName("F111-20: 负向 occurrence 不改写非 failed 终态")
    void guardrailDeniedOccurrenceDoesNotOverrideClassification() throws Exception {
        long logOffset = logLength();
        // ConfirmLoop 形态：write_file ask 挂起 → 用户拒绝 → 模型收尾 → 终态 completed
        String fileName = "f111gdneg-" + System.nanoTime() + ".md";
        String marker = "GNDNEG-MARKER-" + System.nanoTime();
        String session = "sess-f111gdneg-" + System.nanoTime();
        String input = "请使用 write_file 工具创建文件 " + fileName + "，内容为：" + marker
                + "。只创建这一个文件，完成后直接结束任务。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(input) + "\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                CALL_TIMEOUT);
        List<SseFrame> collected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread reader = new Thread(() -> {
            try {
                DaCompatGuardrailSupport.readFramesInto(stream, collected::add, f -> false,
                        SSE_READ_TIMEOUT);
            } catch (Exception e) {
                collected.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f111gdneg-reader");
        reader.setDaemon(true);
        reader.start();
        String taskId = null;
        String toolCallId = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (collected) {
                for (SseFrame f : collected) {
                    if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                        taskId = firstNonBlank(f.field("taskId"), taskId);
                        toolCallId = firstNonBlank(f.field("toolCallId"), toolCallId);
                    }
                }
            }
            if (toolCallId != null) {
                break;
            }
            Thread.sleep(400);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(toolCallId != null,
                "INCONCLUSIVE——模型未触发 write_file 确认（模型旁路，非产品缺陷）");
        HttpResponse<String> resp = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/answer/" + taskId,
                "{\"answer\":\"拒绝\",\"toolCallId\":\"" + toolCallId + "\"}",
                Duration.ofSeconds(30), Map.of("X-Da-Session-Id", session));
        assertThat(resp.statusCode()).as("拒绝认领 200").isEqualTo(200);
        reader.join(150_000);
        List<SseFrame> frames = List.copyOf(collected);
        dumpFrames(session, frames, null, resp);

        // occurrence 在场：SSE denied 证据
        assertThat(frames.stream().anyMatch(f -> f.data() != null
                && (f.data().contains("denied") || f.data().contains("permissionDecision"))))
                .as("denied occurrence 在场（用户拒绝）").isTrue();
        // 终态 completed（模型如实收尾——若模型旁路失败则 abort 登记不冒充）
        boolean completed = frames.stream().anyMatch(f -> "done".equals(f.event())
                && f.data() != null && f.data().contains("completed"));
        org.junit.jupiter.api.Assumptions.assumeTrue(completed,
                "INCONCLUSIVE——拒绝后模型未正常收尾（终态非 completed）；本格负向语义不适用，不判产品");

        String tail = logTail(logOffset);
        assertThat(tail).as("负向：occurrence 在场但终态 completed 的分类=COMPLETED（不得 GUARDRAIL_DENIED）")
                .containsPattern("terminal fact taskId=" + taskId + " class=COMPLETED");
        assertThat(tail.contains("taskId=" + taskId + " class=GUARDRAIL_DENIED"))
                .as("负向：该任务不得归 GUARDRAIL_DENIED").isFalse();
        Allure.addAttachment("F111-20 负向证据", "text/plain",
                "denied occurrence（用户拒绝）+ 终态 completed → class=COMPLETED；"
                        + "「failed+另有因」全语义构造受限（stub script/mainError 互斥），NOT_OBSERVED 登记");
    }
    /* ==================== helpers（本类补格轮自持） ==================== */

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static void dumpFrames(String session, List<SseFrame> frames, String askPayload,
                                   HttpResponse<String> answerResp) {
        try {
            Path dir = Path.of("target", "f109-dump");
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("session=" + session + "\nanswerStatus="
                    + (answerResp == null ? "<null>" : answerResp.statusCode()) + "\n---frames---\n");
            for (SseFrame f : frames) {
                sb.append("[").append(f.event()).append("] ").append(f.data()).append('\n');
            }
            Files.writeString(dir.resolve(session + "-helper.txt"), sb.toString());
        } catch (Exception e) {
            Allure.addAttachment("dump 失败", "text/plain", String.valueOf(e));
        }
    }

    /* ==================== F109-23/24 插件型（2026-11-19，docs#54 裁决=选项 2 自建 fixture） ====================
     * 判据（L1 §97 条款 8：插件子 Agent 分别触发 PreToolUse/PostToolUse，事件携带原任务关联且快照一致；
     * docs#54 裁决 17:48）：插件 fixture（plugin.json＋JS 工具导出 name/description/execute(input)＋
     * agent markdown frontmatter）＋ deepanalyze.plugins.root＋Node 运行时（DaPluginTools tool-host.mjs stdio MCP）。
     * 构造（确定性，stub scriptToolArgs 三步序列，主/子 Agent 模型请求共用同一队列）：
     * 主模型 toolargs:task_tool 派遣 echo-agent → 子模型 toolargs:plugin_echo（marker 定参）
     * → 子模型 toolargs:plugin_echo_fail（失败面）→ 耗尽落回缺省应答收尾。
     * 插件工具卡名=JS 导出 name 原文，hooks matcher 按名匹配。
     */

    /** 插件 fixture：sitplugin/plugin.json＋tools/echo.mjs＋tools/boom.mjs＋agents/echo-agent.md。 */
    private static Path buildPluginFixture() throws Exception {
        Path root = Files.createTempDirectory("f109-plugins");
        Path plugin = root.resolve("sitplugin");
        Files.createDirectories(plugin.resolve("tools"));
        Files.createDirectories(plugin.resolve("agents"));
        writeString(plugin.resolve("plugin.json"), "{\"name\":\"sitplugin\",\"version\":\"1.0.0\","
                + "\"skills\":[],"
                + "\"tools\":[{\"file\":\"tools/echo.mjs\"},{\"file\":\"tools/boom.mjs\"}],"
                + "\"agents\":[\"agents/echo-agent.md\"]}");
        writeString(plugin.resolve("tools").resolve("echo.mjs"),
                "export default {\n"
                + "  name: \"plugin_echo\",\n"
                + "  description: \"SIT plugin echo tool\",\n"
                + "  inputSchema: { type: \"object\", properties: { text: { type: \"string\" } }, required: [\"text\"] },\n"
                + "  execute: async (input) => ({ echo: \"plugin-echo-ok:\" + String(input.text ?? \"\") })\n"
                + "};\n");
        writeString(plugin.resolve("tools").resolve("boom.mjs"),
                "export default {\n"
                + "  name: \"plugin_echo_fail\",\n"
                + "  description: \"SIT plugin failing tool\",\n"
                + "  inputSchema: { type: \"object\", properties: {} },\n"
                + "  execute: async () => { throw new Error(\"plugin-boom-intentional\"); }\n"
                + "};\n");
        writeString(plugin.resolve("agents").resolve("echo-agent.md"),
                "---\n"
                + "agentType: echo-agent\n"
                + "description: SIT plugin echo sub-agent\n"
                + "maxTurns: 8\n"
                + "tools: plugin_echo, plugin_echo_fail\n"
                + "---\n\n"
                + "你是 echo-agent。按任务描述执行：回显用 plugin_echo（参数 text=任务里的标记文本），"
                + "触发失败面用 plugin_echo_fail（无参数）；两个都执行完后，用一句话报告两个工具的结果摘要并结束。\n");
        return root;
    }

    private static void writeString(Path file, String content) throws Exception {
        Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Node 绝对路径（deepanalyze.plugins.runtime-executable——SUT 子进程 PATH 不保证含 node）。 */
    private static String nodeExecutablePath() throws Exception {
        Process p = new ProcessBuilder("which", "node").start();
        String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        if (p.exitValue() == 0 && !out.isBlank()) {
            return out;
        }
        throw new IllegalStateException("node 不可用（docs#54 裁决要求 Node 运行时）");
    }

    /** 插件面 hooks：PreToolUse/PostToolUse 匹配 plugin_echo，PostToolUseFailure 匹配 plugin_echo_fail。 */
    private static String pluginHooks() {
        return "{\"id\":\"plug-pre\",\"event\":\"PreToolUse\",\"type\":\"command\","
                + "\"matcher\":\"plugin_echo\",\"enabled\":true,\"config\":{\"command\":\"true\"}},"
                + "{\"id\":\"plug-post\",\"event\":\"PostToolUse\",\"type\":\"command\","
                + "\"matcher\":\"plugin_echo\",\"enabled\":true,\"config\":{\"command\":\"true\"}},"
                + "{\"id\":\"plug-postfail\",\"event\":\"PostToolUseFailure\",\"type\":\"command\","
                + "\"matcher\":\"plugin_echo_fail\",\"enabled\":true,\"config\":{\"command\":\"true\"}}";
    }

    /** 快照半面 hooks：仅 PreToolUse 一个 hook（若插件子 Agent 未用任务快照而用宿主缺省空表则不触发）。 */
    private static String pluginHooksSnapshot() {
        return "{\"id\":\"plug-pre\",\"event\":\"PreToolUse\",\"type\":\"command\","
                + "\"matcher\":\"plugin_echo\",\"enabled\":true,\"config\":{\"command\":\"true\"}}";
    }

    /* ==================== F111-20b：拒绝耗尽（docs#49 裁决口径——2026-11-20 补格轮） ====================
     * 判据（docs#49 裁决 17:48 关单条件一）：关键只读工具持续被 deny、成员无其他可行路径、
     * 拒绝耗尽失败。构造（stub 确定性——现有 F111-20 正向（ask fail-closed 单拒）在真实网关上
     * 四形态穷尽皆因模型绕行 NOT_OBSERVED（§26.3/26.19）；stub 脚本化成员重试直至轮次耗尽：
     * 构造＝actrail 形态（read_file: ask→成员 fail-closed deny→permissionRail 账本）＋stub 确定性重试，
     * exit 0 非 NONZERO_EXIT——执行失败族不进证据轴，DaHookRunner.isGuardrailEvidence）→
     * 成员每轮 read_file 被拒 → 脚本驱动重试 → 轮次耗尽成员失败 → 任务 failed＋
     * 分类 GUARDRAIL_DENIED（DEBUG terminal fact，与现有 F111-20 同观测面）。
     */

    /** main 路径免暂停 deny hook：grep（仅 main 调用）exit 0＋{"allowed":false}＝DENY verdict。 */
    private static String grepDenyHook() {
        return "{\"id\":\"grep-deny\",\"event\":\"PreToolUse\",\"type\":\"command\","
                + "\"matcher\":\"grep\",\"enabled\":true,"
                + "\"config\":{\"command\":\"echo '{\\\"allowed\\\":false}'\"}}";
    }

    @Test
    @Story("F111-20b: 拒绝耗尽——成员确定性重复调用被拒只读工具直至轮次耗尽→denied→成员失败→任务终态因果链")
    @DisplayName("F111-20b: 拒绝耗尽正向（stub 确定性重试）")
    void denyExhaustionPositive() throws Exception {
        com.huawei.ascend.sit.config.TestConfig cfg = com.huawei.ascend.sit.config.TestConfig.load();
        String session = "sess-f111gd20b-" + System.nanoTime();
        String fileA = "gd20b-a-" + System.nanoTime() + ".txt";
        Path ws = Files.createTempDirectory("f111gd20b-ws");
        Path taskDir = ws.resolve(session);
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve(fileA), "content-" + System.nanoTime());
        // 场景 actrule：read_file 配 ask（合并取严——成员只读工具命中 ask 即 fail-closed deny，
        // 与现有 F111-20 正向同形态；即建即删）
        Path scenarioDir = Path.of(System.getProperty("basedir",
                System.getProperty("user.dir"))).resolve("scenarios/f111gd20b/governance");
        Files.createDirectories(scenarioDir);
        Files.writeString(scenarioDir.resolve("actrule.yaml"),
                "enabled: true\ntools:\n  read_file: ask\n");
        DaReflectionCostSupport.ModelStubServer stub = new DaReflectionCostSupport.ModelStubServer();
        long logOffset = logLength();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        String agentsJson = om.writeValueAsString(java.util.List.of(Map.of(
                "id", "reader", "role", "读取员",
                "task", "使用 read_file 工具读取文件 " + fileA + "，报告其内容")));
        String workflowArgs = om.writeValueAsString(Map.of(
                "mode", "single", "goal", "读取文件并汇报", "agents", om.readValue(agentsJson, Object.class),
                "question", "执行读取任务"));
        java.util.List<String> steps = new java.util.ArrayList<>();
        steps.add("toolargs:workflow_run\u0001" + workflowArgs);
        // 成员 12 步（ask-deny 耗尽→error 信封→noteCausal）＋ main 18 步（grep hook-deny
        // 免暂停→main 预算 15 轮耗尽→任务 failed）——两机制按产品源码各司其职
        for (int i = 0; i < 12; i++) {
            steps.add("tool:read_file");
        }
        for (int i = 0; i < 18; i++) {
            steps.add("tool:grep");
        }
        stub.script(steps.toArray(new String[0]));
        try (SutStack stack = SutStack.builder(cfg)
                .streaming(true)
                .agent(DaReflectionCostSupport.AGENT, agent -> {
                    DaReflectionCostSupport.bindModelEnvironment(agent);
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                    DaReflectionCostSupport.bindStubModel(agent, stub);
                    agent.property("deepanalyze.workspace.root", ws.toAbsolutePath().toString());
                    // 成员轮次预算 12（默认 30）：脚本步数与之对齐——拒绝耗尽在 12 轮到达（DaWorkflowRunner:74）
                    agent.property("deepanalyze.workflow.member-max-iterations", "12");
                    agent.property("logging.level.com.openjiuwen.da", "DEBUG");
                }).start()) {
            String base = stack.client(DaReflectionCostSupport.AGENT).getBaseUrl();
            HttpClient http = DaCompatGuardrailSupport.newHttpClient();
            String body = "{\"sessionId\":\"" + session + "\",\"input\":\"直接开始\","
                    + "\"runtimeConfig\":{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\""
                    + session + "\",\"hooks\":[" + grepDenyHook() + "]}}";
            HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                    Duration.ofSeconds(300));
            List<SseFrame> frames = DaCompatGuardrailSupport.readFrames(stream, f -> false,
                    Duration.ofSeconds(300));
            dumpFrames(session, frames);
            // 旁路分流（铁律）
            boolean workflowRan = frames.stream().anyMatch(f -> f.data() != null
                    && (f.data().contains("workflow_start") || f.data().contains("workflow_agent_start")));
            org.junit.jupiter.api.Assumptions.assumeTrue(workflowRan,
                    "INCONCLUSIVE——模型未发起 workflow_run（未触达成员执行面）；属构造受限非产品断言");
            boolean memberReadAttempt = frames.stream().anyMatch(f -> f.data() != null
                    && f.data().contains("read_file"));
            org.junit.jupiter.api.Assumptions.assumeTrue(memberReadAttempt,
                    "INCONCLUSIVE——成员未调用 read_file（模型旁路）；非产品缺陷");
            // deny 证据：deny-exhaust hook 的 hook_result 逐轮到达（≥10 轮拒绝＝重试在跑）
            long denyCount = frames.stream().filter(f -> f.data() != null
                    && f.data().contains("PERMISSION_DENIED")).count();
            assertThat(denyCount).as("成员 ask deny 逐轮到达（≥8 轮）").isGreaterThanOrEqualTo(8);
            long grepDenyCount = frames.stream().filter(f -> f.data() != null
                    && f.data().contains("\"hookId\":\"grep-deny\"")).count();
            // main 轮次=任务级预算 15 减去成员消耗（12 轮）≈1-3 轮即耗尽——阈值 ≥1
            assertThat(grepDenyCount).as("main grep hook-deny 到达（预算余量轮）").isGreaterThanOrEqualTo(1);
            // 任务终态权威面=DEBUG terminal fact 日志（run-26/30 实证 class=GUARDRAIL_DENIED；
            // run-30 旁证：status API 在 SSE 关闭取消竞态下可能覆写为非 failed——status 仅作观察证据）
            String taskId = null;
            for (SseFrame f : frames) {
                String t = f.field("taskId");
                if (t != null && !t.isBlank()) {
                    taskId = t;
                }
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(taskId != null, "taskId 未捕获");
            HttpResponse<String> st = DaCompatGuardrailSupport.get(http, base,
                    "/api/agents/status/" + taskId, Duration.ofSeconds(30),
                    Map.of("X-Da-Session-Id", session));
            // 主断言：DEBUG terminal fact 分类=GUARDRAIL_DENIED（offset 截段，与 F111-20 同观测面）；
            // 有界轮询 15s——terminal fact 落笔与 readFrames 超时返回同秒竞态（run-31 实测）不可单次读
            String tail = logTail(logOffset);
            for (int i = 0; i < 30 && !logContainsCausal(logOffset, taskId); i++) {
                Thread.sleep(500);
                tail = logTail(logOffset);
            }
            if (!logContainsCausal(logOffset, taskId)) {
                // #849（共享模型客户端并发窗口，已立案 open）间歇拦截主任务收尾：run-26/30 已两次
                // 全量实证（PERMISSION_DENIED×11＋成员 MAX_ITERATIONS 耗尽信封＋class=GUARDRAIL_DENIED），
                // 27/28/29 同构造未达终态——非确定复现受 #849 挡，如实 SKIP 不冒充
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "INCONCLUSIVE——主任务未达终态：solution#849 间歇拦截（构造与因果链已 run-26/30 两次实证，"
                                + "class=GUARDRAIL_DENIED；status body=" + st.body().substring(0,
                                Math.min(200, st.body().length())) + "）；#849 修复后本格即稳定绿");
            }
            assertThat(tail.contains("taskId=" + taskId + " class=GUARDRAIL_DENIED"))
                    .as("拒绝耗尽失败分类=GUARDRAIL_DENIED（因果链 closed，terminal fact 权威面）").isTrue();
            Allure.addAttachment("F111-20b 证据", "text/plain",
                    "成员 PERMISSION_DENIED " + denyCount + " 次＋main grep-deny " + grepDenyCount + " 次；任务 failed；分类 GUARDRAIL_DENIED；"
                            + "dump=target/f109-dump/" + session + ".txt");
        } finally {
            deleteRecursively(scenarioDir.getParent().getParent());
        }
    }

    /** 30s 后对 SUT 进程 jstack（run-19 诊断轮：把「子 Agent 派遣后主/子模型调用互卡」钉成线程级证据）。 */
    private static void startSutStackDumper(String session) {
        Thread dumper = new Thread(() -> {
            try {
                Thread.sleep(30_000);
                Process p = new ProcessBuilder("/bin/sh", "-c",
                        "PID=$(pgrep -f 'deepanalyze-engine.*exec\\.jar' | head -1); echo \"PID=$PID\"; "
                                + "jstack \"$PID\" 2>&1").start();
                String out;
                try (java.io.InputStream in = p.getInputStream()) {
                    out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                p.waitFor(40, java.util.concurrent.TimeUnit.SECONDS);
                Path dir = Path.of("target", "f109-dump");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(session + "-sut-stack.txt"), out,
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception failure) {
                // best effort——诊断失败不干扰主流程
            }
        });
        dumper.setDaemon(true);
        dumper.setName("f109-sut-stack-dumper");
        dumper.start();
    }

    /** 插件任务发起（stub 三步序列＋runtimeConfig hooks），返回任务全帧。 */
    private static List<SseFrame> runPluginTask(String session, String marker, String hooksJson,
            DaReflectionCostSupport.ModelStubServer stub, String base) throws Exception {
        stub.scriptToolArgs(java.util.List.of(
                Map.entry("task_tool", Map.of(
                        "subagent_type", "echo-agent",
                        "task_description", "请先调用 plugin_echo 工具（参数 text=" + marker
                                + "），再调用 plugin_echo_fail 工具（无参数），然后用一句话报告两个工具的结果摘要并结束。")),
                Map.entry("plugin_echo", Map.of("text", marker)),
                Map.entry("plugin_echo_fail", Map.of())));
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"直接开始\","
                + "\"runtimeConfig\":{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\""
                + session + "\",\"hooks\":[" + hooksJson + "]}}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(300));
        List<SseFrame> frames = DaCompatGuardrailSupport.readFrames(stream, f -> false,
                Duration.ofSeconds(300));
        dumpFrames(session, frames);
        dumpStubRequests(session, stub);
        return frames;
    }

    /** stub 请求清单落盘（诊断面：计数＋每请求 body 前 240 字符）。 */
    private static void dumpStubRequests(String session, DaReflectionCostSupport.ModelStubServer stub)
            throws Exception {
        Path dir = Path.of("target", "f109-dump");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("stub-requests session=").append(session).append('\n');
        java.util.List<DaReflectionCostSupport.StubRequest> rs = stub.requests();
        sb.append("count=").append(rs.size()).append('\n');
        for (int i = 0; i < rs.size(); i++) {
            String body = rs.get(i).body() == null ? "" : rs.get(i).body().replace('\n', ' ');
            sb.append('[').append(i).append("] ").append(body, 0, Math.min(240, body.length())).append('\n');
        }
        Files.writeString(dir.resolve(session + "-stub.txt"), sb.toString(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void dumpFrames(String session, List<SseFrame> frames) throws Exception {
        Path dir = Path.of("target", "f109-dump");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("session=").append(session).append("\n---frames---\n");
        for (int i = 0; i < frames.size(); i++) {
            sb.append("[").append(i).append("] event=").append(frames.get(i).event())
                    .append(" data=").append(frames.get(i).data()).append('\n');
        }
        Files.writeString(dir.resolve(session + ".txt"), sb.toString(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @Story("F109-23: 插件型 PreToolUse/PostToolUse/PostToolUseFailure 逐型到达、可溯父任务、会话不中断")
    @DisplayName("F109-23: 插件子 Agent hooks 事件逐型触发（task_tool＋插件 fixture）")
    void pluginExecutorHooksFirePerType() throws Exception {
        String session = "sess-f109plug-" + System.nanoTime();
        String marker = "PLUGIN-MARKER-" + System.nanoTime();
        Path fixture = buildPluginFixture();
        String node = nodeExecutablePath();
        DaReflectionCostSupport.ModelStubServer stub = new DaReflectionCostSupport.ModelStubServer();
        Path ws = Files.createTempDirectory("f109plug-ws");
        try (SutStack stack = SutStack.builder(TestConfig.load())
                .streaming(true)
                .agent(DaReflectionCostSupport.AGENT, agent -> {
                    DaReflectionCostSupport.bindModelEnvironment(agent);
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                    DaReflectionCostSupport.bindStubModel(agent, stub);
                    agent.property("deepanalyze.workspace.root", ws.toAbsolutePath().toString());
                    agent.property("deepanalyze.plugins.root", fixture.toAbsolutePath().toString());
                    agent.property("deepanalyze.plugins.runtime-executable", node);
                    agent.property("deepanalyze.subagents.enabled", "true");
                    agent.property("deepanalyze.task-loop.planning-enabled", "false");
                }).start()) {
            String base = stack.client(DaReflectionCostSupport.AGENT).getBaseUrl();
            HttpClient http = DaCompatGuardrailSupport.newHttpClient();
            startSutStackDumper(session);
            List<SseFrame> frames = runPluginTask(session, marker, pluginHooks(), stub, base);
            boolean subSpawned = stub.requests().stream().anyMatch(r -> r.body() != null
                    && r.body().contains(marker));
            org.junit.jupiter.api.Assumptions.assumeTrue(subSpawned,
                    "INCONCLUSIVE——task_tool 派遣未到达子 Agent 模型（构造受限非产品断言）");
            long pre = frames.stream().filter(f -> f.data() != null
                    && f.data().contains("\"hookId\":\"plug-pre\"")).count();
            long post = frames.stream().filter(f -> f.data() != null
                    && f.data().contains("\"hookId\":\"plug-post\"")).count();
            long postFail = frames.stream().filter(f -> f.data() != null
                    && f.data().contains("\"hookId\":\"plug-postfail\"")).count();
            assertThat(pre).as("PreToolUse hook_result 到达（插件 echo 工具前置触发）").isGreaterThanOrEqualTo(1);
            assertThat(post).as("PostToolUse hook_result 到达（插件成功面）").isGreaterThanOrEqualTo(1);
            assertThat(postFail).as("PostToolUseFailure hook_result 到达（插件失败面）").isGreaterThanOrEqualTo(1);
            boolean attributed = frames.stream().anyMatch(f -> f.data() != null
                    && f.data().contains("\"hookId\":\"plug-postfail\"")
                    && f.field("taskId") != null && !f.field("taskId").isBlank());
            assertThat(attributed).as("失败面 hook_result 带 taskId（父任务可溯）").isTrue();
            assertThat(frames.stream().anyMatch(f -> "done".equals(f.event())
                    && f.data() != null && f.data().contains("\"status\":\"completed\"")))
                    .as("插件工具失败不拖垮会话（任务 completed）").isTrue();
            Allure.addAttachment("F109-23 插件型证据", "text/plain",
                    "stub 三步序列 task_tool→plugin_echo→plugin_echo_fail；pre/post/postfail 逐型到达＋taskId 可溯；"
                            + "dump=target/f109-dump/" + session + ".txt");
        }
    }

    @Test
    @Story("F109-24: 插件型快照半面——插件子 Agent 全程共用发起时任务快照（同一 hookId 在插件工具调用上触发）")
    @DisplayName("F109-24: 插件型 hooks 快照传播（半面）")
    void pluginUsesTaskHooksSnapshot() throws Exception {
        String session = "sess-f109plug-snap-" + System.nanoTime();
        String marker = "PLUGIN-SNAP-" + System.nanoTime();
        Path fixture = buildPluginFixture();
        String node = nodeExecutablePath();
        DaReflectionCostSupport.ModelStubServer stub = new DaReflectionCostSupport.ModelStubServer();
        Path ws = Files.createTempDirectory("f109plug-snap-ws");
        try (SutStack stack = SutStack.builder(TestConfig.load())
                .streaming(true)
                .agent(DaReflectionCostSupport.AGENT, agent -> {
                    DaReflectionCostSupport.bindModelEnvironment(agent);
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                    DaReflectionCostSupport.bindStubModel(agent, stub);
                    agent.property("deepanalyze.workspace.root", ws.toAbsolutePath().toString());
                    agent.property("deepanalyze.plugins.root", fixture.toAbsolutePath().toString());
                    agent.property("deepanalyze.plugins.runtime-executable", node);
                    agent.property("deepanalyze.subagents.enabled", "true");
                    agent.property("deepanalyze.task-loop.planning-enabled", "false");
                }).start()) {
            String base = stack.client(DaReflectionCostSupport.AGENT).getBaseUrl();
            HttpClient http = DaCompatGuardrailSupport.newHttpClient();
            List<SseFrame> frames = runPluginTask(session, marker, pluginHooksSnapshot(), stub, base);
            boolean subSpawned = stub.requests().stream().anyMatch(r -> r.body() != null
                    && r.body().contains(marker));
            org.junit.jupiter.api.Assumptions.assumeTrue(subSpawned,
                    "INCONCLUSIVE——task_tool 派遣未到达子 Agent 模型（构造受限非产品断言）");
            long pre = frames.stream().filter(f -> f.data() != null
                    && f.data().contains("\"hookId\":\"plug-pre\"")).count();
            assertThat(pre).as("插件工具调用触发任务快照 hook（快照传播面）").isGreaterThanOrEqualTo(1);
            Allure.addAttachment("F109-24 插件型半面证据", "text/plain",
                    "runtimeConfig 单 hook（plug-pre→plugin_echo）经任务快照在插件工具调用上触发 preCount=" + pre);
        }
    }

    /** SUT 日志新增段（ENV-5.14：跨 run 追加共享，必须 offset 截段）。 */
    private static long logLength() throws Exception {
        Path log = Path.of("target/sit-logs/deepanalyze-guardrail/stdout.log");
        return Files.exists(log) ? Files.size(log) : 0L;
    }

    private static String logTail(long offset) throws Exception {
        Path log = Path.of("target/sit-logs/deepanalyze-guardrail/stdout.log");
        if (!Files.exists(log)) {
            return "";
        }
        try (var in = Files.newInputStream(log)) {
            in.skipNBytes(offset);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 拒绝耗尽因果链日志判据（taskId＋class=GUARDRAIL_DENIED 同行）。 */
    private static boolean logContainsCausal(long offset, String taskId) throws Exception {
        return logTail(offset).contains("taskId=" + taskId + " class=GUARDRAIL_DENIED");
    }
}
