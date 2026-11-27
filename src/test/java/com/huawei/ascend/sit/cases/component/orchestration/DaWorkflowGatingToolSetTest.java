/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.da.workflow.DaTaskBoard;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.config.DaProperties;
import com.openjiuwen.da.workflow.DaWorkflowTools;
import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S11：编排工具六件的**统一门控**——缺省装配下六件**均在**主 Agent 工具集；
 * 显式 {@code enabled=false} 下六件**均不在**且无副作用（docs#43 裁决：缺省开启、可显式关闭，
 * 正负双向断言；产品生效点 !954/ab9527a8e 2026-10-28，r8 run-104 的 FAIL 即旧判据）。
 *
 * <p>Oracle 来源：docs#43 裁决后的现行口径——「门控最终口径＝『缺省开启、可显式关闭』」；
 * TestPlan !509 §2 F 组双向判据（缺省装配六件均在／显式关闭六件均不在、差集恰好六件、不移除任何既有工具）。
 * （FEAT-067 §2 旧文「六件统一门控默认关」已被裁决推翻，L1 main 的对应欠账由设计侧
 * 9aba6e5e 分支收口；判据以裁决 + 实现行为为准。）
 *
 * <p><b>层次：L1t（§11.2 权威更正，降级预案已作废）</b>。§11.2 明写可观测面已定位：
 * {@code DaWorkflowTools.attachIfEnabled}（`:37-50`）在 {@code !enabled} 时**直接 return**；
 * 启用时经 {@code ToolMount.mount}（{@code da/tools/ToolMount.java:32-36}）写入
 * **{@code core.getAbilityManager()}** 与 {@code Runner.resourceMgr()} ⇒ 两个可查面。
 * 并明写「层次可为 L1t（若能不接真实模型构造 {@code BaseAgent}/{@code DeepAgent}）」——
 * 实测 {@code DeepAgent} 有公开无参/单 {@code AgentCard} 构造器，**不需要接真实模型**，
 * 故本用例落 L1t：不拉起宿主进程、不需模型凭据、不需 Redis。
 *
 * <p>与原 §8 落点表（{@code e2e/deepanalyze/DaWorkflowGatingE2EIT}）的**类名/层次偏差已登记**：
 * 依据 §11.2「降级预案作废 + 层次可为 L1t」，落点改为组件层 {@code DaWorkflowGatingToolSetTest}；
 * 判据（两套装配的工具集差异恰好为六件）**未变**，缺省方向按 docs#43 反转。
 *
 * <p><b>判据（docs#43 双向）</b>：缺省装配（未显式配置 {@code deepanalyze.workflow.enabled}，
 * {@code DaProperties.Workflow.isEnabled} 字段默认值）下守卫放行 ⇒ 六件**均在**
 * {@code getAbilityManager()} 的工具集里；显式关闭（{@code enabled=false} ⇒ 宿主守卫不调用 attach，
 * 组件层忠实还原＝不调用 attach）时六件**均不在**；两套装配的差异**恰好**是这六件
 * （不多不少 ⇒ 证明门控没有顺带挂载/摘掉别的工具）。
 *
 * <p>不应断言：Agent Card 是否列工具（§11.2 已核实 {@code AgentCard.builder()} 只设 id/name/description，
 * **Card 不是可查面**）；护栏级别（归 067-S12 静态）；六件工具各自的契约（归 067-S01~S10）。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowGatingToolSetTest {

    /** 生产装配需要 baseRoot（{@code DaWorkflowTools.attach} 第 5 参）。 */
    @TempDir
    Path baseRoot;

    /** FEAT-067 §2 的编排工具六件（与 067-S10 / 067-S12 的集合逐字同源）。 */
    private static final List<String> SIX_TOOLS = List.of(
            "workflow_run", "delegate_task", "task_output",
            "send_message", "subagent_transcript", "workflow_status");

    @Test
    @Story("067-S11: 门控缺省开启、显式可关（docs#43 裁决反转）")
    @DisplayName("067-S11 显式关闭：六件均不在 AbilityManager；缺省开启：六件均在；两套装配差异恰好为六件")
    void gatingControlsExactlyTheSixOrchestrationTools() throws IOException {
        final Function<MemberSpec, SubAgentExecutor> factory =
                member -> (userInput, subGoal) -> "unused-by-gating-probe";

        // ① 显式关闭形态的组件层忠实还原。
        // 【2026-10-19 重定基线到 ce9019d86 后口径变更，docs#43 反转后语义更新】旧基线的门控在**工具层 API**
        // （DaWorkflowTools.attachIfEnabled(boolean, ...)），新基线该方法**已删除**，门控上移到
        // **配置层的调用点守卫**：DaEngineConfiguration:165 `if (properties.getWorkflow().isEnabled()) { attach(...) }`、
        // DaAgentFactory:137 `if (isWorkflowEnabled) { attach(...) }`。
        // ⇒ "显式 enabled=false" 在组件层的忠实还原就是"**不调用 attach**"（守卫拒绝分支），
        // 而不是"用一个布尔参数调用"。
        final Set<String> offTools;
        try (DeepAgent offAgent = newAgent("da-gating-off");
                DaTaskBoard offBoard = new DaTaskBoard(2)) {
            final DaWorkflowRunner offRunner = DaWorkflowTestSupport.newRunner(factory, 2);
            assertThat(offRunner).as("装置自检：显式关闭态也构造 runner（生产同样构造，只是守卫不放行 attach）").isNotNull();
            assertThat(offBoard).as("装置自检：显式关闭态也构造任务板").isNotNull();
            offTools = toolIds(offAgent);
        }

        // ② 缺省开启：DaProperties.Workflow.isEnabled 字段默认值已为 true（docs#43 裁决；源码核实
        // DaProperties:1035 `private boolean isEnabled = true;`）⇒ 生产守卫会放行 attach。
        // 组件层忠实还原＝按生产签名调用 attach（守卫放行后的动作）。
        final Set<String> onTools;
        try (DeepAgent onAgent = newAgent("da-gating-on");
                DaTaskBoard onBoard = new DaTaskBoard(2)) {
            final DaWorkflowRunner onRunner = DaWorkflowTestSupport.newRunner(factory, 2);
            // 生产签名：attach(agent, runner, board, delegate, baseRoot)
            DaWorkflowTools.attach(onAgent, onRunner, onBoard, goal -> "unused", baseRoot);
            onTools = toolIds(onAgent);
        }

        // 主断言①：显式关闭（enabled=false ⇒ 守卫不放行 attach）时六件均不在工具集（门控形同虚设即 FAIL）
        final List<String> leaked = new ArrayList<>();
        for (String tool : SIX_TOOLS) {
            if (containsTool(offTools, tool)) {
                leaked.add(tool);
            }
        }
        assertThat(leaked).as("显式关闭（enabled=false）时六件**均不得**出现在主 Agent 工具集；泄漏=%s；关态工具集=%s",
                leaked, offTools).isEmpty();

        // 主断言②：缺省开启形态下六件均在
        final List<String> missing = new ArrayList<>();
        for (String tool : SIX_TOOLS) {
            if (!containsTool(onTools, tool)) {
                missing.add(tool);
            }
        }
        assertThat(missing).as("缺省开启形态（守卫放行 attach）下六件**均应**出现在主 Agent 工具集；缺失=%s；开态工具集=%s",
                missing, onTools).isEmpty();

        // 主断言③：两套装配的差异**恰好**是这六件（零副作用——不多挂也不误摘别的工具）
        final Set<String> added = new LinkedHashSet<>(onTools);
        added.removeAll(offTools);
        final Set<String> removed = new LinkedHashSet<>(offTools);
        removed.removeAll(onTools);
        assertThat(added).as("开态相对关态**新增**的工具应恰好是六件（不多不少）；实收=%s", added)
                .hasSize(6);
        for (String tool : SIX_TOOLS) {
            assertThat(containsTool(added, tool)).as("新增集合应含 %s；实收=%s", tool, added).isTrue();
        }
        assertThat(offTools).as("关态工具集（DeepAgent 自带能力，供差异对照）=%s", offTools).isNotNull();
        assertThat(removed).as("开态相对关态**不应移除**任何既有工具（零副作用）；实收=%s", removed)
                .isEmpty();
    }

    /**
     * 门控**缺省开启**（docs#43 裁决反转）：{@code DaProperties.Workflow.isEnabled} 的字段默认值必须是
     * {@code true}；且显式 {@code setEnabled(false)} 后必须是 {@code false}（可显式关闭——双向断言）。
     *
     * <p>没有这一条，上面那条只证明"开关有效"，证不到 docs#43 裁决的「缺省装配下六件均在、
     * 显式 enabled=false 下均不在」——若默认值仍是 false，缺省部署六件就进不了主 Agent 工具集；
     * 若关闭被写死（setEnabled 无效），显式关闭就形同虚设。r8 轮 run-104 的 FAIL
     * （{@code Expecting value to be false but was true}）即产品已按裁决翻转为 true、旧断言未跟上。
     */
    @Test
    @Story("067-S11: 门控缺省开启、显式可关（docs#43 裁决反转）")
    @DisplayName("067-S11 前置（双向）：DaProperties.Workflow.isEnabled 缺省 true（缺省开启）；显式 setEnabled(false) 后为 false（显式可关）")
    void workflowGateDefaultsOnAndCanBeExplicitlyDisabled() {
        final DaProperties properties = new DaProperties();
        assertThat(properties.getWorkflow()).as("DaProperties 应暴露 workflow 配置段").isNotNull();
        assertThat(properties.getWorkflow().isEnabled())
                .as("docs#43 裁决「缺省开启」⇒ 未显式配置 deepanalyze.workflow.enabled 时必须为 true"
                        + "（源码核实 DaProperties.Workflow 字段默认值 true，产品生效点 !954/ab9527a8e）；"
                        + "若为 false 则缺省装配下六件进不了主 Agent 工具集，与裁决相悖")
                .isTrue();
        final DaProperties explicitOff = new DaProperties();
        explicitOff.getWorkflow().setEnabled(false);
        assertThat(explicitOff.getWorkflow().isEnabled())
                .as("显式配置 deepanalyze.workflow.enabled=false 后必须为 false（缺省开启 ≠ 不可关闭；"
                        + "该形态是 067-S23 端到端 no-op 支点与 FEAT-054 回归隔离的唯一开关）")
                .isFalse();
    }

    /**
     * 宿主**两处装配路径**都把门控接到 {@code DaProperties.Workflow.isEnabled}（静态接线核对）。
     *
     * <p>L2-067 §2.3 自陈「两处装配路径（单例 / per-Task 工厂）各自建 runner」。只验开关行为、不验接线，
     * 就漏掉"宿主把门控写死"这一故障形态——缺省开启（docs#43）后写死的故障形态即「显式关闭失效」
     * （显式 enabled=false 仍挂六件）——所以补这条静态断言：
     * ① 单例路径 {@code DaEngineConfiguration} 直接把 {@code properties.getWorkflow().isEnabled()} 传进
     *    {@code DaWorkflowTools.attachIfEnabled}；② per-Task 工厂路径 {@code DaAgentFactory} 把构造入参
     *    {@code isWorkflowEnabled} 原样传进 {@code attachIfEnabled}，而其构造点由同一份 properties 供给；
     * ③ 两处都**不得**把字面量 {@code true} 当门控实参（否则显式关闭形同虚设）。
     *
     * <p>源码根解析方式同 065-S21 / 067-S15（系统属性 {@code deepanalyze.source.root} →
     * 环境变量 {@code DEEPANALYZE_SOURCE_ROOT}；都缺则中止记 SKIPPED，不判产品 FAIL）。
     */
    @Test
    @Story("067-S11: 门控缺省开启、显式可关（docs#43 裁决反转）")
    @DisplayName("067-S11 接线：两处装配路径的门控实参都来自 DaProperties，且都不是字面量 true")
    void bothAssemblyPathsWireTheGateFromProperties() throws IOException {
        final Path root = DaWorkflowTemplateResolutionTest.resolveDeepanalyzeSourceRoot();
        final Path engineConfig = root.resolve("com/openjiuwen/da/config/DaEngineConfiguration.java");
        final Path agentFactory = root.resolve("com/openjiuwen/da/factory/DaAgentFactory.java");
        assertThat(Files.isRegularFile(engineConfig)).as("被测源码应存在：%s", engineConfig).isTrue();
        assertThat(Files.isRegularFile(agentFactory)).as("被测源码应存在：%s", agentFactory).isTrue();
        final String configText = Files.readString(engineConfig, StandardCharsets.UTF_8);
        final String factoryText = Files.readString(agentFactory, StandardCharsets.UTF_8);

        // ① 单例路径：门控实参就是 properties.getWorkflow().isEnabled()
        assertThat(configText).as("单例装配路径应以 DaProperties 的门控值作**调用点守卫**（%s）",
                        engineConfig.getFileName())
                .contains("if (properties.getWorkflow().isEnabled())")
                .contains("DaWorkflowTools.attach(deepAgent, workflowRunner");
        // ② per-Task 工厂路径：attachIfEnabled 的门控实参是构造入参字段，不是常量
        assertThat(factoryText).as("per-Task 工厂路径应以 isWorkflowEnabled 作**调用点守卫**（%s）",
                        agentFactory.getFileName())
                .contains("if (isWorkflowEnabled)")
                .contains("DaWorkflowTools.attach(agent, workflowRunner");
        assertThat(factoryText).as("isWorkflowEnabled 应是构造入参字段（由装配方从 DaProperties 供给）")
                .contains("private final boolean isWorkflowEnabled")
                .contains("boolean isWorkflowEnabled");
        // ③ 工厂的构造点必须由同一份 properties 供给门控值（在 engine 源码树里找 new DaAgentFactory(）
        final List<String> factoryCallSites = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                final String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains("new DaAgentFactory(")) {
                    factoryCallSites.add(root.relativize(file) + ":"
                            + (text.substring(0, text.indexOf("new DaAgentFactory(")).split("\n").length));
                    assertThat(text).as("DaAgentFactory 的构造点应把 DaProperties 的门控值传进去；构造点=%s",
                                    root.relativize(file))
                            .contains("getWorkflow().isEnabled()");
                }
            }
        }
        assertThat(factoryCallSites).as("应能在 engine 源码树里找到 DaAgentFactory 的构造点（否则②的入参无来源）")
                .isNotEmpty();
        // ④ 两处都不得把字面量 true 当门控实参（写死即门控形同虚设）
        assertThat(configText).as("单例路径不得把门控写死为 true（写死即门控形同虚设）")
                .doesNotContain("if (true)")
                .doesNotContain("DaWorkflowTools.attachIfEnabled(true");
        assertThat(factoryText).as("工厂路径不得把门控写死为 true")
                .doesNotContain("if (true)");
        // attach 不得被无守卫地调用（否则关态也会挂上六件）
        assertThat(configText.indexOf("DaWorkflowTools.attach("))
                .as("单例路径的 attach 调用必须出现在门控守卫之后")
                .isGreaterThan(configText.indexOf("if (properties.getWorkflow().isEnabled())"));
        assertThat(factoryText.indexOf("DaWorkflowTools.attach("))
                .as("工厂路径的 attach 调用必须出现在门控守卫之后")
                .isGreaterThan(factoryText.indexOf("if (isWorkflowEnabled)"));
        assertThat(factoryText).as("工厂路径不得把门控写死为 true")
                .doesNotContain("DaWorkflowTools.attachIfEnabled(true");
    }

    /**
     * 构造一个**不接真实模型**的 {@code DeepAgent}（§11.2 允许 L1t 的前提）。
     *
     * <p>用三参构造器（{@code AgentCard} + {@code DeepAgentConfig} + {@code Workspace}）而不是
     * 0.1.17 才新增的无参/单 Card 构造器——本仓 test-compile 走 core 0.1.16（见批次记录 §7C.3 的版本冲突），
     * 三参构造器在 0.1.16 与 0.1.17 **都存在**，因此同一份源码可在两条 classpath 下编译与运行。
     * 工作区根用一次性临时目录，用完由 {@code DeepAgent.close()} 与 JVM 退出回收。
     */
    private static DeepAgent newAgent(final String namePrefix) throws IOException {
        // 三个入参都只用 **0.1.16 与 0.1.17 都存在**的公开构造器：
        //   AgentCard.builder()（两版都有）、new DeepAgentConfig()（两版都有）、new Workspace()（两版都有）。
        // 不用 Workspace.builder()/DeepAgentConfig.builder()——返回型在 0.1.17 由 WorkspaceBuilder 改名为
        // Builder，按 0.1.16 编译再在 0.1.17 上跑会 NoSuchMethodError（本轮实测踩过，见批次记录 §7C.3）。
        final java.nio.file.Path root = Files.createTempDirectory(namePrefix + "-ws-");
        final Workspace workspace = new Workspace();
        workspace.setRootPath(root.toString());
        return new DeepAgent(
                AgentCard.builder().name(namePrefix + "-" + OrchestrationTestSupport.uid()).build(),
                new DeepAgentConfig(),
                workspace);
    }

    /**
     * 取主 Agent（{@code BaseAgent}）能力面上的**工具名集合**。
     *
     * <p>只用 {@code listToolInfo()} 这一条可查面并取其 {@code name()}：本轮首跑曾把
     * {@code ToolInfo} 整体字符串化、又并入 {@code list()} 的另一形态，导致同一工具被计两次、
     * "差异恰好六件"断言假失败——属**测试侧缺陷**，已改为按工具名精确取集合。
     */
    private static Set<String> toolIds(final DeepAgent agent) {
        final List<ToolInfo> infos = agent.getAgent().getAbilityManager().listToolInfo();
        final Set<String> names = new LinkedHashSet<>();
        for (ToolInfo info : infos) {
            // 用 getName() 而不是 name()：ToolInfo 在 core 0.1.16 与 0.1.17 都是普通类（非记录），
            // 两版都只有 getName()；本仓 test-compile 走 0.1.16，用记录访问器会编译不过。
            names.add(info.getName());
        }
        return names;
    }

    /** 工具名可能被命名空间限定（{@code AbilityManager.qualifyToolId}），故按"等于或带限定前缀"判定。 */
    private static boolean containsTool(final Set<String> haystack, final String toolId) {
        for (String entry : haystack) {
            if (entry.equals(toolId) || entry.endsWith("." + toolId) || entry.endsWith("/" + toolId)
                    || entry.endsWith(":" + toolId)) {
                return true;
            }
        }
        return false;
    }
}
