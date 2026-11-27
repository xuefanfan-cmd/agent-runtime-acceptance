/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S09：模板名白名单与路径穿越拒绝。
 *
 * <p>Oracle 来源：FEAT-067 §2「模板名白名单（字母数字连字符下划线）防路径穿越；未知名返回可读错误」；
 * L2-067 §2.4（实现正则 {@code [a-zA-Z0-9-_]+}）。Feature §6 明文要求覆盖"模板白名单与穿越拒绝"。
 *
 * <p><b>两段证据</b>：
 * <ul>
 *   <li><b>动态</b>（9 个参数实例）：正例解析成功并执行；全部反例返回可读 {@code error}、
 *       不抛异常、不悬挂（{@code @Timeout} 兜住）。</li>
 *   <li><b>静态</b>（{@link #whitelistIsCheckedBeforeAnyResourceAccess()}）：读被测源码，
 *       断言白名单正则的校验**出现在任何资源访问之前**。</li>
 * </ul>
 *
 * <p><b>为什么需要静态那一段（与设计 §6 的取证方式有一处必要调整，已登记偏差）</b>：
 * 设计 §6 提议"用不存在的穿越目标 + 断言错误文案为『非法名』而非『文件不存在』来间接证明白名单先生效"。
 * 实测该取证方式**不可用**：实现对"非法名"与"名合法但资产不存在"**返回同一句** {@code unknown template: <名>}，
 * 两者在文案上不可区分；且被测制品以 **jar** 形态在 classpath 上，jar 条目按字面名查表、
 * 含 {@code ..} 的名字本来就无法命中，因此"穿越是否真的被拦"在动态面上不可观测。
 * 故改为用静态序关系证明（白名单校验先于资源访问），判据强度不降。
 *
 * <p>另：设计 §6 的正例②写作 {@code zero_hallucination}（下划线），但实装八套资产里没有该名
 * （实装为 {@code zero-hallucination}，见 067-S10 的四方对账）。本用例把 {@code zero-hallucination}
 * 作正例、把 {@code zero_hallucination} 作"字符集合法但资产不存在"的实例，两处都如实断言，
 * 该文档笔误已登记待设计确认。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowTemplateResolutionTest {

    /** 生产装配需要 baseRoot（DaWorkflowTools.attach:40）；缺它 workflow_run 走"不落盘"降级形态。 */
    @TempDir
    Path baseRoot;

    private static final String RUN = OrchestrationTestSupport.uid();

    private record TemplateForm(String label, String templateName, boolean expectSuccess) {
    }

    static Stream<Arguments> templateForms() {
        return Stream.of(
                new TemplateForm("正例① 实装模板 research-pipeline", "research-pipeline", true),
                new TemplateForm("正例② 实装模板 zero-hallucination", "zero-hallucination", true),
                new TemplateForm("反例① 上跳穿越 ../secret", "../secret", false),
                new TemplateForm("反例② 目录分隔 a/b", "a/b", false),
                new TemplateForm("反例③ 反斜杠穿越 ..\\x", "..\\x", false),
                new TemplateForm("反例④ NUL 编码 a%00b", "a%00b", false),
                new TemplateForm("反例⑤ 空串", "", false),
                new TemplateForm("反例⑥ 字符集合法但资产不存在 no-such-template", "no-such-template", false),
                new TemplateForm("反例⑦ 字符集合法（含下划线）但资产不存在 zero_hallucination",
                        "zero_hallucination", false))
                .map(v -> Arguments.of(v.label(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("templateForms")
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @Story("067-S09: 模板名白名单与穿越拒绝")
    @DisplayName("067-S09 正例解析成功并执行；全部反例返回可读 error，不抛异常、不悬挂")
    void whitelistAcceptsShippedNamesAndRejectsTraversal(final String label, final TemplateForm form) {
        final Function<MemberSpec, SubAgentExecutor> factory =
                member -> (userInput, subGoal) -> "M-067-S09-" + member.id() + "-" + RUN;
        final DaWorkflowRunner runner = DaWorkflowTestSupport.newRunner(factory, 4);
        final WorkflowRunTool tool = DaWorkflowTestSupport.newRunTool(runner, baseRoot);

        final Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("question", "U-067-S09-" + RUN);
        args.put("template", form.templateName());
        final Object raw = tool.invoke(args, Map.of());
        final var out = DaWorkflowTestSupport.output(raw);

        if (form.expectSuccess()) {
            final Map<String, Object> data = DaWorkflowTestSupport.dataOf(out);
            assertThat(data).as("%s：正例应执行成功", label).containsEntry("status", "completed");
            assertThat(DaWorkflowTestSupport.stepsOf(data)).as("%s：应有逐步留痕", label).isNotEmpty();
            assertThat((String) data.get("workflow_id")).as("%s：应外流 workflow_id", label).isNotBlank();
        } else {
            final String error = DaWorkflowTestSupport.errorOf(out);
            assertThat(error).as("%s：反例应返回可读错误而不是抛异常穿栈；实收=[%s]", label, error)
                    .isNotBlank();
            if (!form.templateName().isBlank()) {
                // 名合法但不存在 与 名非法 走同一句文案（实现事实，已在类注释说明）
                assertThat(error).as("%s：文案应带被查询的模板名定位；实收=[%s]", label, error)
                        .contains("template").contains(form.templateName());
            } else {
                // 空串既不是图纸也不是模板 ⇒ 落到"双缺"族文案
                assertThat(error).as("%s：空模板名应落到 blueprint/template 双缺族文案；实收=[%s]", label, error)
                        .contains("blueprint").contains("template");
            }
        }
    }

    /**
     * 静态取证：白名单正则校验**先于**任何资源访问。
     *
     * <p>源码根按 ① 系统属性 {@code deepanalyze.source.root} ② 环境变量
     * {@code DEEPANALYZE_SOURCE_ROOT} 解析；都缺时以 {@link Assumptions} 中止
     * （记 SKIPPED ⇒ INCONCLUSIVE，不判产品 FAIL）。
     */
    @Test
    @Story("067-S09: 模板名白名单与穿越拒绝")
    @DisplayName("067-S09 静态：白名单校验出现在 getResourceAsStream 之前，且正则只放行 [a-zA-Z0-9-_]")
    void whitelistIsCheckedBeforeAnyResourceAccess() throws IOException {
        // 【2026-10-19 重定基线到 ce9019d86：取证挂点迁移】
        // 旧基线（f9da5b54f）白名单正则在 da/workflow/WorkflowRunTool.java（loadTemplate 内联）；
        // 新基线 loadTemplate 已重构为**委托** BlueprintTemplates.load(WorkflowRunTool.class, name)，
        // 白名单常量 SAFE_NAME 与两处过滤都在 agent-core-ext-orchestration 的 BlueprintTemplates 里。
        // ⇒ 判据不变（L2-067 §2.4「名字白名单 [a-zA-Z0-9-_]+ 防路径穿越」+「白名单先于资源访问」），
        //    只把取证位置从 DA 宿主迁到 orchestration 模块，并**追加一条链路完整性断言**（DA 侧确实委托过去）。
        final Path orchRoot = resolveOrchestrationSourceRoot();
        final Path source = orchRoot.resolve("com/openjiuwen/agents/orchestration/BlueprintTemplates.java");
        assertThat(Files.isRegularFile(source)).as("被测源码应存在：%s", source).isTrue();
        final String text = Files.readString(source, StandardCharsets.UTF_8);

        final int whitelist = text.indexOf("[a-zA-Z0-9-_]+");
        final int resourceAccess = text.indexOf("getResourceAsStream");
        assertThat(whitelist).as("应能在源码里找到白名单正则 [a-zA-Z0-9-_]+（L2-067 §2.4；新基线位于 BlueprintTemplates.SAFE_NAME）")
                .isNotNegative();
        assertThat(resourceAccess).as("应能在源码里找到模板资源访问点").isNotNegative();
        assertThat(whitelist).as("白名单校验必须**先于**资源访问出现（否则穿越名会被拿去拼路径）；"
                        + "白名单偏移=%s，资源访问偏移=%s", whitelist, resourceAccess)
                .isLessThan(resourceAccess);
        // 链路完整性：DA 宿主的 loadTemplate 必须真的委托到 BlueprintTemplates.load，
        // 否则上面验的白名单与产品实际走的路径无关（防止"验了一个没人调用的工具类"）。
        final Path daRoot = resolveDeepanalyzeSourceRoot();
        final Path runTool = daRoot.resolve("com/openjiuwen/da/workflow/WorkflowRunTool.java");
        assertThat(Files.isRegularFile(runTool)).as("被测源码应存在：%s", runTool).isTrue();
        assertThat(Files.readString(runTool, StandardCharsets.UTF_8))
                .as("WorkflowRunTool.loadTemplate 应委托 BlueprintTemplates.load（否则白名单取证点与产品路径脱节）")
                .contains("BlueprintTemplates.load(");
        // 白名单只放行字母数字连字符下划线 ⇒ 不含 . / \ % 等穿越字符
        assertThat("[a-zA-Z0-9-_]+").as("白名单字符集不应放行路径分隔符或点号")
                .doesNotContain(".").doesNotContain("/").doesNotContain("\\").doesNotContain("%");
        // 反向自检：读取方式有效（两个文件各自自检，避免"读错文件也能过"）
        assertThat(text).as("扫描自检：BlueprintTemplates 应能扫到类名").contains("class BlueprintTemplates");
        assertThat(Files.readString(runTool, StandardCharsets.UTF_8))
                .as("扫描自检：WorkflowRunTool 应能扫到类名").contains("class WorkflowRunTool");
    }

    static Path resolveDeepanalyzeSourceRoot() {
        final String fromProperty = System.getProperty("deepanalyze.source.root");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Path.of(fromProperty.trim());
        }
        final String fromEnv = System.getenv("DEEPANALYZE_SOURCE_ROOT");
        Assumptions.assumeTrue(fromEnv != null && !fromEnv.isBlank(),
                "未提供 DA 宿主源码根：请加 -Ddeepanalyze.source.root=<deepanalyze-java engine 的 "
                        + "src/main/java 绝对路径>，或设环境变量 DEEPANALYZE_SOURCE_ROOT。"
                        + "缺它时本用例记 SKIPPED（结论 INCONCLUSIVE），不判产品 FAIL。");
        return Path.of(fromEnv.trim());
    }

    /** 供 067-S15 复用：读 DA 宿主源码文本。 */
    static String readDeepanalyzeSource(final String relativePath) throws IOException {
        final Path file = resolveDeepanalyzeSourceRoot().resolve(relativePath);
        assertThat(Files.isRegularFile(file)).as("被测源码应存在：%s", file).isTrue();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 供 067-S15 复用：列出实装模板名（与 067-S10 的对账基准独立取一次，作交叉核对）。 */
    static List<String> shippedTemplateNames() {
        return List.of("research-pipeline", "comprehensive-analysis", "comprehensive-deep-analysis",
                "generic-parallel-retrieval", "multi-perspective-council", "parallel-deep-retrieval",
                "parallel-research", "zero-hallucination");
    }

    /**
     * 解析 {@code agent-core-ext-orchestration} 的源码根：① 系统属性 {@code orchestration.source.root}
     * ② 环境变量 {@code ORCHESTRATION_SOURCE_ROOT}；都缺时以 {@link Assumptions} 中止（记 SKIPPED ⇒
     * INCONCLUSIVE，不判产品 FAIL）。
     */
    static Path resolveOrchestrationSourceRoot() {
        final String fromProperty = System.getProperty("orchestration.source.root");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Path.of(fromProperty);
        }
        final String fromEnv = System.getenv("ORCHESTRATION_SOURCE_ROOT");
        Assumptions.abort(fromEnv == null || fromEnv.isBlank()
                ? "未提供被测源码根：请加 -Dorchestration.source.root=<agent-core-ext-orchestration 的 src/main/java 绝对路径>"
                : null);
        return Path.of(fromEnv);
    }
}
