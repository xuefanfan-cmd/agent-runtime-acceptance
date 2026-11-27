/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 067-S24：**装配口径一致性门禁**（静态，测试侧自我看守，非产品判据）。
 *
 * <p><b>为什么必须有这条</b>：2026-10-19 重定基线到 {@code ce9019d86} 时查出——六件编排工具里
 * **4 件**在组件层用例里的构造方式与宿主真实装配点 {@code DaWorkflowTools.attach} 不一致：
 * {@code WorkflowRunTool} 少 {@code baseRoot}、{@code WorkflowStatusTool} 少 {@code transcripts}、
 * {@code send_message} 由测试自建而非取 {@code runner.getSendMessage()}、
 * {@code SubagentTranscriptTool} 用旧包旧构造形（少 {@code ownerKey}）。
 * 而**少参数的构造器在新基线里仍然存在**（所以编译能过），产品自己的 javadoc 把它们定性为**降级形态**：
 * {@code WorkflowRunTool(runner)}=「构造工具（**不落盘：长输出只截断**）」、
 * {@code WorkflowStatusTool(board)}=「构造工具（**只查委托任务**）」、{@code transcripts}=「**null 表示不查运行**」。
 *
 * <p>后果：组件层会"**全绿**"地验证**产品不使用的构造路径**。最严重的一处——
 * {@code SubagentTranscriptTool} 旧形不含 {@code ownerKey}，而 {@code ownerKey} 正是 D1
 * （跨会话留痕泄露，`agent-solution#458`）修复所在的参数 ⇒ 用旧形去"回归验证 D1 已修"会得出**假的通过结论**。
 *
 * <p>这次只是因为 {@code SubagentTranscriptTool} **换了包**才编不过而暴露；**其余三件是静默的**。
 * 本用例就是把这道检查固化下来：任何一方（产品装配点 / 测试侧 fixture 与用例）漂移都会红。
 *
 * <p><b>故障与主断言</b>：若产品改了 {@code attach} 的构造口径而我方 fixture 未跟 ⇒ 断言组① FAIL；
 * 若任何组件层用例绕过 fixture 直接 {@code new} 出降级形态 ⇒ 断言组② FAIL。
 *
 * <p><b>这不是产品判据</b>：本条不承接任何 REQ，属测试侧质量门禁；台账中记为独立场景 067-S24，
 * 不计入设计要求的覆盖分母。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowToolsWiringParityTest {

    /** 组件层用例所在目录（相对 surefire 的 basedir）。 */
    private static final String CASES_DIR =
            "src/test/java/com/huawei/ascend/sit/cases/component/orchestration";

    /** 单参 {@code new WorkflowRunTool(x)} —— 降级形态（不落盘）。 */
    private static final Pattern RUN_TOOL_ONE_ARG =
            Pattern.compile("new\\s+WorkflowRunTool\\s*\\(\\s*[^,()]+\\)");
    /** 单参 {@code new WorkflowStatusTool(x)} —— 降级形态（只查委托任务）。 */
    private static final Pattern STATUS_TOOL_ONE_ARG =
            Pattern.compile("new\\s+WorkflowStatusTool\\s*\\(\\s*[^,()]+\\)");
    /** 单参 {@code new SubagentTranscriptTool(x)} —— 旧形，不含 ownerKey。 */
    private static final Pattern TRANSCRIPT_ONE_ARG =
            Pattern.compile("new\\s+SubagentTranscriptTool\\s*\\(\\s*[^,()]+\\)");

    @Test
    @Story("067-S24: 组件层构造口径与宿主装配点 DaWorkflowTools.attach 逐项一致")
    @DisplayName("067-S24 静态门禁：产品装配口径未漂移，且无组件层用例绕用降级构造器")
    void componentFixturesMatchTheProductionWiringPoint() throws IOException {
        final Path root = DaWorkflowTemplateResolutionTest.resolveDeepanalyzeSourceRoot();
        final Path attach = root.resolve("com/openjiuwen/da/workflow/DaWorkflowTools.java");
        assertThat(Files.isRegularFile(attach)).as("宿主装配点源码应存在：%s", attach).isTrue();
        final String prod = Files.readString(attach, StandardCharsets.UTF_8);

        // ---------- 断言组①：产品装配点的六件构造口径（漂移即红） ----------
        assertThat(prod).as("① 装配点应挂 workflow_run，且为**两参含 baseRoot**（少参形态=不落盘降级）")
                .contains("new WorkflowRunTool(runner, baseRoot)");
        assertThat(prod).as("① 装配点应挂 delegate_task（board + delegate 函数）")
                .contains("new DelegateTaskTool(board, delegate)");
        assertThat(prod).as("① 装配点应挂 task_output（board）")
                .contains("new TaskOutputTool(board)");
        assertThat(prod).as("① 装配点的 send_message 必须取 **runner 持有的实例**（与调度器消费面同源），不得自建")
                .contains("runner.getSendMessage()");
        // 【2026-10-26 r6 适配】subagent_transcript 由两参升为**三参**（新增 taskLookup＝board::activityOf，
        // 委托任务 id → 成员活动记录）；workflow_status 由两参升为**三参**（新增 runner.getSendMessage()）。
        // ownerKey 仍是 D1（#458）会话分区修复所在的参数，判据保留并**加严**（三参逐项都在）。
        assertThat(prod).as("① 装配点应挂 subagent_transcript，且为 orchestration 包的 **(RunTranscripts, ownerKey, "
                        + "taskLookup)** 三参形——ownerKey 是 D1（#458）会话分区修复所在的参数，"
                        + "taskLookup 是成员活动查询（少参形态＝查不到成员活动）")
                .contains("SubagentTranscriptTool(runner.getTranscripts(),")
                .contains("DaOrchestrationScope::currentKey")
                .contains("board::activityOf");
        assertThat(prod).as("① 装配点应挂 workflow_status，且为**三参含 transcripts 与 sendMessage**"
                        + "（少参形态=只查委托任务、查不到留言投递面）")
                .contains("new WorkflowStatusTool(board, runner.getTranscripts(), runner.getSendMessage())");
        // 门控在**调用点守卫**，不在工具层（新基线已删除 attachIfEnabled）
        assertThat(prod).as("① 装配点不应再有工具层门控方法 attachIfEnabled（门控已上移到配置层调用点守卫）")
                .doesNotContain("attachIfEnabled");

        // ---------- 断言组②：测试侧 fixture 用的是同一口径 ----------
        final Path cases = resolveCasesDir();
        final Path fixture = cases.resolve("DaWorkflowTestSupport.java");
        assertThat(Files.isRegularFile(fixture)).as("共用 fixture 应存在：%s", fixture).isTrue();
        final String fx = Files.readString(fixture, StandardCharsets.UTF_8);
        assertThat(fx).as("② fixture 的 runner 必须用**三参**构造器（含 runTimeoutSeconds），与 DaEngineConfiguration:162-164 一致")
                .contains("new DaWorkflowRunner(factory, parallelism, PRODUCTION_RUN_TIMEOUT_SECONDS)");
        assertThat(fx).as("② fixture 的 workflow_run 必须带 baseRoot")
                .contains("new WorkflowRunTool(runner, baseRoot)");
        assertThat(fx).as("② fixture 的 send_message 必须取 runner.getSendMessage()")
                .contains("return runner.getSendMessage();");
        assertThat(fx).as("② fixture 的 subagent_transcript 必须用 (getTranscripts(), ownerKey, board::activityOf) 形")
                .contains("new SubagentTranscriptTool(runner.getTranscripts(), ownerKey, board::activityOf)");
        assertThat(fx).as("② fixture 的 workflow_status 必须带 transcripts 与 sendMessage")
                .contains("new WorkflowStatusTool(board, runner.getTranscripts(), runner.getSendMessage())");

        // ---------- 断言组③：任何组件层用例都不得绕用降级构造器 ----------
        final List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(cases)) {
            for (Path f : walk.filter(Files::isRegularFile)
                    .filter(x -> x.getFileName().toString().endsWith(".java")).toList()) {
                final String name = f.getFileName().toString();
                if (name.equals("DaWorkflowToolsWiringParityTest.java")) {
                    continue;   // 本类自身含这些正则字面量，排除
                }
                final String text = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                collect(violations, name, RUN_TOOL_ONE_ARG, text,
                        "new WorkflowRunTool(单参) —— 降级形态「不落盘：长输出只截断」，应改用 fixture.runTool()/newRunTool(runner, baseRoot)");
                collect(violations, name, STATUS_TOOL_ONE_ARG, text,
                        "new WorkflowStatusTool(单参) —— 降级形态「只查委托任务」，应改用 fixture.statusTool()/newStatusTool(board, runner)");
                collect(violations, name, TRANSCRIPT_ONE_ARG, text,
                        "new SubagentTranscriptTool(单参) —— 旧形不含 ownerKey（D1 修复所在参数），应改用 fixture.transcriptTool(ownerKey)");
                if (text.contains("new SendMessageTool(")) {
                    violations.add(name + ": new SendMessageTool(...) —— 会得到与调度器消费面**不同源**的另一个收件箱，"
                            + "应改用 fixture.sendMessage()/sendMessageOf(runner)");
                }
                if (text.contains("getAuditForest(")) {
                    violations.add(name + ": runner.getAuditForest() —— 该方法在新基线已删除，"
                            + "应改用 runner.getTranscripts()");
                }
            }
        }
        assertThat(violations).as("③ 组件层用例不得绕用降级构造器（命中即说明该用例验的是产品不走的路径）；违规=%s",
                violations).isEmpty();
    }

    private static void collect(final List<String> out, final String file, final Pattern p,
            final String text, final String why) {
        final Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(file + ": " + m.group().replaceAll("\\s+", " ") + " —— " + why);
        }
    }

    /** 去掉行注释与块注释，避免把 Javadoc 里引用的旧写法误判为违规。 */
    private static String stripComments(final String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static Path resolveCasesDir() {
        final String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        final Path p = Path.of(basedir, CASES_DIR);
        // 注意：Assumptions.abort(...) 是**无条件中止**（传 null 也会中止），条件式必须用 assumeTrue
        assumeTrue(Files.isDirectory(p),
                "定位不到组件层用例目录：" + p + "（basedir=" + basedir + "）");
        return p;
    }
}
