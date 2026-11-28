/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.da.config.DaProperties;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import com.openjiuwen.harness.tools.ToolOutput;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S01：{@code workflow_run} 模板启动的全出参契约。
 *
 * <p>Oracle 来源：FEAT-067 §2「workflow_run」（MUST）「出参：状态、workflow_id(SSE 订阅键)、
 * 收口产物、逐步留痕(步骤/成员/跳过/产物/**留痕分支标识 branch_id**——subagent_transcript 查询入参的来源)」；
 * §4「模板启动」场景；L2-067 §2.1。
 *
 * <p>主断言：① {@code status=completed}；② {@code workflow_id} 非空白；③ {@code final_output} 非空白
 * 且等于末步 output；④ {@code steps[]} 条数 == 该模板图纸编译后的步数，每步含
 * id/member/skipped/output/**branch_id** 五个键，member 序列与图纸编译产物一致。
 *
 * <p>故障与主断言：若出参缺 branch_id（ISSUE #33 V3 回归）则断言 ④ 失败；若收口取错步则断言 ③ 失败；
 * 若条数与图纸不符则说明宿主改了编排语义（违反 FEAT-067 §6 第 2 条）。
 *
 * <p>层次说明：这是 **L1t 组件层**——六件工具的公开构造器依赖全部可注入
 * （{@code DaWorkflowRunner(Function<MemberSpec,SubAgentExecutor>, int)}），因此工具契约无需拉起宿主进程、
 * 无需模型凭据即可验证。入参用与主 Agent 实际传入**完全相同**的 Map 形态。
 * "主 Agent 能否自主够到工具"由 067-S16（端到端）承接，不在本用例范围。
 *
 * <p>不应断言：branch_id 的具体格式（实现约定）；错误文案的具体措辞；本次运行的事件订阅可达性
 * （FEAT-067 §2 明写挂 1130 评估项、不作为当期验收内容）。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowRunToolContractTest {

    /** 生产装配需要 baseRoot（DaWorkflowTools.attach:40）；缺它 workflow_run 走"不落盘"降级形态。 */
    @TempDir
    Path baseRoot;

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TEMPLATE = "research-pipeline";

    @Test
    @Story("067-S01: workflow_run 模板启动全契约")
    @DisplayName("067-S01 模板启动：出参含 status/workflow_id/final_output/steps[]（steps 含 branch_id）")
    void templateRunReturnsFullContract() throws Exception {
        // Given：先从 classpath 独立读出同一份模板并装载，作为"期望步数与成员序"的对照基准
        final String templateYaml = readClasspath("workflow-templates/" + TEMPLATE + ".yaml");
        final BlueprintSpec expected = BlueprintLoader.load(templateYaml);
        final List<String> expectedMembers = new ArrayList<>();
        final int expectedSteps = expected.steps().size();
        assertThat(expectedSteps).as("模板图纸编译后应至少有 1 步").isPositive();

        // Given：注入确定性成员执行体工厂（L2-067 §2.6 的生产注入点，非 Mock LLM）
        final Function<MemberSpec, SubAgentExecutor> factory =
                member -> (userInput, subGoal) -> "M-067-S01-" + member.id() + "-" + RUN;
        final DaWorkflowRunner runner = DaWorkflowTestSupport.newRunner(factory, 4);
        final WorkflowRunTool tool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);

        // When：用与主 Agent 完全相同的入参形态调用
        final Object raw = tool.invoke(
                Map.of("template", TEMPLATE, "question", "U-067-S01-" + RUN), Map.of());

        // Then：工具级返回信封
        assertThat(raw).as("invoke 应返回 ToolOutput 信封").isInstanceOf(ToolOutput.class);
        final ToolOutput out = (ToolOutput) raw;
        assertThat(out.isSuccess()).as("成功路径 isSuccess 应为 true；error=%s", out.getError()).isTrue();
        assertThat(out.getData()).as("成功路径应带 data").isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        final Map<String, Object> data = (Map<String, Object>) out.getData();

        // Then①：status
        assertThat(data).as("出参应含 status").containsEntry("status", "completed");

        // Then②：workflow_id（SSE 订阅键的来源，FEAT-067 §5.1.4「不出流的事件面等于不存在」）
        assertThat(data.get("workflow_id")).as("出参应含 workflow_id").isInstanceOf(String.class);
        assertThat((String) data.get("workflow_id")).as("workflow_id 不应为空白").isNotBlank();

        // Then④：steps[] 逐步留痕
        assertThat(data.get("steps")).as("出参应含 steps").isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
        assertThat(steps).as("steps 条数应等于模板图纸编译后的步数（宿主不得改编排语义）")
                .hasSize(expectedSteps);
        for (Map<String, Object> step : steps) {
            assertThat(step).as("每步留痕应含五个键（含 #33 V3 修复后透出的 branch_id）")
                    .containsKeys("id", "member", "skipped", "output", "branch_id");
            assertThat((String) step.get("branch_id"))
                    .as("branch_id 不应为空白——它是 subagent_transcript 必填入参的唯一会话侧来源")
                    .isNotBlank();
            assertThat((String) step.get("member")).as("member 应是图纸声明的成员之一").isNotBlank();
            expectedMembers.add((String) step.get("member"));
        }
        // 成员序应与图纸编译产物的步骤声明序一致
        final List<String> specMembers = expected.steps().stream().map(s -> s.member()).toList();
        assertThat(expectedMembers).as("steps 的成员序应与图纸编译产物一致")
                .containsExactlyElementsOf(specMembers);

        // Then③：final_output == 末步 output 且非空白
        final Object lastOutput = steps.get(steps.size() - 1).get("output");
        assertThat(data.get("final_output")).as("收口产物应等于末步产物").isEqualTo(lastOutput);
        assertThat((String) data.get("final_output")).as("收口产物不应为空白").isNotBlank();
        // 标记串格式为 M-067-S01-<成员 id>-<RUN>，故期望串必须带上末步的成员 id
        // （首跑漏了成员 id 那一段导致误报，属测试缺陷，已修正）
        final String lastMember = (String) steps.get(steps.size() - 1).get("member");
        assertThat((String) data.get("final_output"))
                .as("收口产物应含本用例唯一标记（证明真执行而非空跑）")
                .contains("M-067-S01-" + lastMember + "-" + RUN);
    }

    /** 067-S02 的三个实例：只给图纸 / 只给模板 / 两者同给（图纸优先）。 */
    static Stream<Arguments> precedenceForms() {
        return Stream.of(
                Arguments.of("① 只给 blueprint（单兵图纸，1 步）", "blueprint-only", true, false, 1),
                Arguments.of("② 只给 template（research-pipeline，多步）", "template-only", false, true, -1),
                Arguments.of("③ 同时给 blueprint 与 template（图纸优先，模板被忽略）",
                        "both-blueprint-wins", true, true, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("precedenceForms")
    @Story("067-S02: 图纸直给 + 图纸优先 + 同步返回即结束")
    @DisplayName("067-S02 图纸/模板二选一、同给时图纸优先；invoke 同步返回即执行已结束")
    void blueprintTakesPrecedenceAndInvokeIsSynchronous(final String label, final String idSuffix,
            final boolean giveBlueprint, final boolean giveTemplate, final int expectedSteps)
            throws Exception {
        final Function<MemberSpec, SubAgentExecutor> factory =
                member -> (userInput, subGoal) -> "M-067-S02-" + member.id() + "-" + RUN;
        final DaWorkflowRunner runner = DaWorkflowTestSupport.newRunner(factory, 4);
        final WorkflowRunTool tool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);

        final Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("question", "U-067-S02-" + idSuffix + "-" + RUN);
        if (giveBlueprint) {
            args.put("blueprint", DaWorkflowTestSupport.singleMemberBlueprint("T-067-S02-" + idSuffix, "mSolo"));
        }
        if (giveTemplate) {
            args.put("template", TEMPLATE);
        }

        final Map<String, Object> data = DaWorkflowTestSupport.dataOf(
                DaWorkflowTestSupport.output(tool.invoke(args, Map.of())));

        assertThat(data).as("%s：status 应为 completed", label).containsEntry("status", "completed");
        final List<Map<String, Object>> steps = DaWorkflowTestSupport.stepsOf(data);

        // 期望步数：图纸直给为 1 步；模板启动等于该模板图纸编译后的步数（独立装载取基准）
        final int expected = expectedSteps >= 0 ? expectedSteps
                : BlueprintLoader.load(readClasspath("workflow-templates/" + TEMPLATE + ".yaml"))
                        .steps().size();
        assertThat(steps).as("%s：steps 条数应为 %s（③ 若等于模板步数即图纸未优先）", label, expected)
                .hasSize(expected);
        if (giveBlueprint) {
            assertThat(steps).as("%s：图纸优先 ⇒ 成员应是图纸声明的 mSolo", label)
                    .allSatisfy(step -> assertThat(step.get("member")).isEqualTo("mSolo"));
        }
        // 同步返回即结束：返回时 steps[] 已完整（每步都有 output 与 branch_id，不是"进行中"占位）
        for (Map<String, Object> step : steps) {
            assertThat(step).as("%s：每步留痕应含五个键", label)
                    .containsKeys("id", "member", "skipped", "output", "branch_id");
            assertThat((String) step.get("output")).as("%s：返回即结束 ⇒ 每步 output 应已就绪", label)
                    .isNotBlank();
            assertThat((String) step.get("branch_id")).as("%s：每步 branch_id 应非空", label).isNotBlank();
        }
        assertThat((String) data.get("final_output")).as("%s：收口产物应非空", label).isNotBlank();
        assertThat((String) data.get("workflow_id")).as("%s：workflow_id 应非空", label).isNotBlank();
    }

    // ==================================================================================
    // 067-S27：`workflow_run` 工具调用超时——**对外承诺面**（2026-10-21 新增，REQ-052）
    //
    // Oracle 三来源对账（三方一致才认定 1800 是承诺值、不是测试侧推测）：
    //   ① Feature-067 §3 外部接口表（docs@b4f45c06 / !343）：「deepanalyze.workflow.run-timeout-seconds |
    //      YAML 配置 | workflow_run 工具调用超时(秒)，默认 1800；core 叠加运行预算(FEAT-057)时实际上限取
    //      min(该值, 剩余预算)」；§6 新增约束条明写「workflow_run 的工具调用超时**属对外承诺面**…
    //      配置键必须随本特性文档登记，不许先进实现后补文档」。
    //   ② L2-067 §6 配置表：默认 1800；作用点 `DaWorkflowRunner` 三参构造(`DaEngineConfiguration.java:162-164`)
    //      → `WorkflowRunTool` ToolCard `resilience.timeout_s`(`:109`)；core **只在卡片未声明时**回落默认 300、
    //      叠加运行预算时取 min(`AbilityManager.java:1030-1034`)、硬上限 3600。
    //   ③ 源码（common@ce9019d86）：`DaProperties$Workflow:672` runTimeoutSeconds = 1800；
    //      `DaWorkflowRunner:50` DEFAULT_RUN_TIMEOUT_SECONDS = 1800、`:79` 两参构造委托三参、
    //      `:105` public getRunTimeoutSeconds()；`WorkflowRunTool:109`
    //      `.properties(Map.of("resilience", Map.of("timeout_s", runner.getRunTimeoutSeconds())))`。
    //
    // **明确不断言**（依据见测试设计细档 §6 的 067-S27 小节与 §11.4）：
    //   - min(1800, 剩余预算) 的钳制、剩余预算 ≤0 的分支、硬上限 3600 的截断、"卡片未声明回落 300"
    //     ⇒ 全是 **core 侧** `AbilityManager:1030-1034` 的行为，不在本特性 SUT 内，按承接方判定门禁归
    //     `agent-core-java`，SIT 不重复覆盖；
    //   - `DaWorkflowRunner:96` 的 `Math.max(1, runTimeoutSeconds)`（非法值收敛为 1）
    //     ⇒ **实现事实、文档未承诺**，断它即测试侧自行补写产品规则（门禁禁止）；
    //   - "shipped 模板在 1800 下能跑完" ⇒ 需真机且当前会先撞 D5（成员 60s，`agent-solution#461`），
    //     归因不干净；等 #461 落地后由 067-S16 / 065-S23 / FEAT-066-G1 一并回归。
    // ==================================================================================

    /**
     * 从 ToolCard 的**公开导出面**取 {@code resilience.timeout_s}（不用反射）。
     *
     * <p>「键存在」本身就是一条断言：L2-067 §6 明写「core **只在卡片未声明时**回落默认 300」，
     * 所以"声明了"是承诺的一部分，也正是 D3（`agent-solution#460`）原报缺陷
     * （「`WorkflowRunTool` 未声明 call timeout ⇒ 回落 300.0 s ⇒ 两个 shipped 模板跑不完」）的直接反证。
     */
    private static Object resilienceTimeoutOf(final WorkflowRunTool tool) {
        final Map<String, Object> props = tool.getCard().getProperties();
        assertThat(props).as("ToolCard 应带 properties（`WorkflowRunTool:109` 写入 resilience 段）").isNotNull();
        final Object resilience = props.get("resilience");
        assertThat(resilience)
                .as("ToolCard.properties 应**声明** resilience 段——L2-067 §6：core 只在卡片未声明时回落默认 300，"
                        + "故『声明了』本身即承诺的一部分（D3 的直接反证）；实收 properties=%s", props)
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        final Map<String, Object> r = (Map<String, Object>) resilience;
        assertThat(r).as("resilience 段应含 timeout_s 键；实收=%s", r).containsKey("timeout_s");
        return r.get("timeout_s");
    }

    @Test
    @Story("067-S27: workflow_run 工具调用超时属对外承诺面")
    @DisplayName("067-S27① 卡片声明 resilience.timeout_s，且**产品默认**为 1800；DaProperties 的默认值同为 1800")
    void toolCardDeclaresResilienceTimeoutWithDocumentedDefault() {
        final Function<MemberSpec, SubAgentExecutor> factory =
                member -> (userInput, subGoal) -> "M-067-S27-" + member.id() + "-" + RUN;

        // 🔴 刻意用**两参**构造器（`DaWorkflowRunner:79` 委托 `DEFAULT_RUN_TIMEOUT_SECONDS`），
        // **不用** `DaWorkflowTestSupport.newRunner`——后者是三参、显式传 PRODUCTION_RUN_TIMEOUT_SECONDS=1800，
        // 用它就等于"断言我方 fixture 传进去的值"，而不是"断言产品默认值"，属正向自检放错侧（§7AN.3 同族失误）。
        final DaWorkflowRunner runner = new DaWorkflowRunner(factory, 4);
        final WorkflowRunTool tool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);

        assertThat(resilienceTimeoutOf(tool))
                .as("产品默认的工具调用超时应为 **1800** 秒（Feature-067 §3 + L2-067 §6 配置表 + `DaWorkflowRunner:50`）")
                .isEqualTo(1800);
        assertThat(runner.getRunTimeoutSeconds())
                .as("两参构造应落到 DEFAULT_RUN_TIMEOUT_SECONDS=1800（`DaWorkflowRunner:79`）")
                .isEqualTo(1800);
        assertThat(resilienceTimeoutOf(tool))
                .as("卡片值应与 `runner.getRunTimeoutSeconds()` **同源**（证明卡片不是写死的常量）")
                .isEqualTo(runner.getRunTimeoutSeconds());

        // 配置键侧的默认值：`deepanalyze.workflow.run-timeout-seconds` 未配置时 = 1800（`DaProperties:672`）
        final DaProperties props = new DaProperties();
        assertThat(props.getWorkflow().getRunTimeoutSeconds())
                .as("配置键 deepanalyze.workflow.run-timeout-seconds 的**默认值**应为 1800（`DaProperties:672`）")
                .isEqualTo(1800);
    }

    @Test
    @Story("067-S27: workflow_run 工具调用超时属对外承诺面")
    @DisplayName("067-S27② 超时**可配**：改配置值后卡片随之改变（取 900 与 3600 两点，证明不是写死常量）")
    void toolCardTimeoutFollowsTheConfiguredRunTimeout() {
        final Function<MemberSpec, SubAgentExecutor> factory =
                member -> (userInput, subGoal) -> "M-067-S27-" + member.id() + "-" + RUN;
        // 900 = 低于默认；3600 = L2-067 §6 所述的 core 硬上限值（此处只作"可配到该值"的取值点，
        // **不断言** core 的截断行为——那属 core 侧，见本节头注）
        for (int configured : new int[] {900, 3600}) {
            // 走 DaProperties 的读写器，模拟"配置键 → 属性对象"这一段（YAML 绑定由 Spring 负责，不在此覆盖）
            final DaProperties props = new DaProperties();
            props.getWorkflow().setRunTimeoutSeconds(configured);
            assertThat(props.getWorkflow().getRunTimeoutSeconds())
                    .as("DaProperties 应能承载配置值 %d（`getRunTimeoutSeconds`/`setRunTimeoutSeconds`）", configured)
                    .isEqualTo(configured);
            // 三参构造 = 生产装配点 `DaEngineConfiguration:162-164` 的形态
            final DaWorkflowRunner runner = new DaWorkflowRunner(
                    factory, props.getWorkflow().getParallelism(), props.getWorkflow().getRunTimeoutSeconds());
            final WorkflowRunTool tool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);
            assertThat(resilienceTimeoutOf(tool))
                    .as("配置为 %d 时，ToolCard 的 resilience.timeout_s 应随之为 %d（否则该配置键是死的——即 D6 同族故障）",
                            configured, configured)
                    .isEqualTo(configured);
        }
    }

    @Test
    @Story("067-S27: workflow_run 工具调用超时属对外承诺面")
    @DisplayName("067-S27③ 配置键真的接到了工具卡片：装配点唯一、两条装配路径共用同一 runner（静态源码取证）")
    void runTimeoutConfigKeyIsWiredOnceAndSharedByBothAssemblyPaths() throws IOException {
        final Path root = DaWorkflowTemplateResolutionTest.resolveDeepanalyzeSourceRoot();
        final Path engineConfig = root.resolve("com/openjiuwen/da/config/DaEngineConfiguration.java");
        final Path agentFactory = root.resolve("com/openjiuwen/da/factory/DaAgentFactory.java");
        final Path daProperties = root.resolve("com/openjiuwen/da/config/DaProperties.java");
        final Path runTool = root.resolve("com/openjiuwen/da/workflow/WorkflowRunTool.java");
        for (Path f : List.of(engineConfig, agentFactory, daProperties, runTool)) {
            assertThat(Files.isRegularFile(f)).as("被测源码应存在：%s", f).isTrue();
        }
        final String cfg = Files.readString(engineConfig, StandardCharsets.UTF_8);
        final String fac = Files.readString(agentFactory, StandardCharsets.UTF_8);
        final String props = Files.readString(daProperties, StandardCharsets.UTF_8);
        final String tool = Files.readString(runTool, StandardCharsets.UTF_8);

        // ① 唯一装配点必须把**配置键的值**传进三参构造器（L2-067 §6 点名的作用点）
        assertThat(cfg).as("单例装配路径应以三参构造 DaWorkflowRunner，并把配置键的值传进去（`DaEngineConfiguration:162-164`）")
                .contains("new DaWorkflowRunner(")
                .contains("properties.getWorkflow().getRunTimeoutSeconds()");
        // ② 配置键本身要有默认值与读写器（否则"可配"无从谈起）
        assertThat(props).as("DaProperties 应声明 run-timeout-seconds 的默认值与读写器（`:672`/`:698`/`:707`）")
                .contains("private int runTimeoutSeconds = 1800")
                .contains("public int getRunTimeoutSeconds()")
                .contains("public void setRunTimeoutSeconds(int runTimeoutSeconds)");
        // ③ 工具侧把 runner 的值写进卡片（`WorkflowRunTool:109`）
        assertThat(tool).as("WorkflowRunTool 应把 runner 的超时值写进 ToolCard 的 resilience.timeout_s（`:109`）")
                .contains("\"resilience\"")
                .contains("\"timeout_s\"")
                .contains("runner.getRunTimeoutSeconds()");
        // ④ 🔴 反绕行断言：全 engine 源码树里 `new DaWorkflowRunner(` **只能有一处**。
        //    若将来出现第二处（例如 per-Task 工厂自建 runner 用默认超时），配置键就会被那条路径绕过——
        //    这正是 D6（"配置是死的"）的同族故障形态，必须在静态面就拦住。
        final List<String> callSites = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                final String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains("new DaWorkflowRunner(")) {
                    callSites.add(root.relativize(file) + ":"
                            + (text.substring(0, text.indexOf("new DaWorkflowRunner(")).split("\n").length));
                }
            }
        }
        assertThat(callSites).as("engine 源码树里 DaWorkflowRunner 的构造点应**唯一**（否则配置键可被第二条路径绕过）；实收=%s",
                callSites).hasSize(1);
        // ⑤ per-Task 工厂路径是**接收**同一个 runner 实例，不是另建 ⇒ 两条装配路径共用同一超时值
        assertThat(fac).as("per-Task 工厂路径不得自建 DaWorkflowRunner（应接收同一实例，`DaAgentFactory:73`/`:83`）")
                .doesNotContain("new DaWorkflowRunner(");
        assertThat(fac).as("per-Task 工厂路径应以构造入参持有 runner（`DaAgentFactory:50`/`:73`）")
                .contains("DaWorkflowRunner workflowRunner");
    }

    private static String readClasspath(final String path) throws IOException {
        try (InputStream in = DaWorkflowRunToolContractTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("classpath 资源应存在：%s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
