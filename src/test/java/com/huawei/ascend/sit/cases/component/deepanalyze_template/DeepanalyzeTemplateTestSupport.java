/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.deepanalyze_template;

import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.harness.tools.ToolOutput;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-068 团队模板库组件层用例的共用装配设施（**非测试类，不含任何 {@code @Test}**）。
 *
 * <p>与 FEAT-065/066/067 线的 {@code OrchestrationTestSupport}/{@code DaWorkflowTestSupport} 同一装配哲学：
 * 被测单元的公开构造器全部可注入，无需拉起宿主进程、无需模型凭据。确定性成员执行体由测试提供是
 * FEAT-065 §3「成员绑定契约」明文交给装配方的设计位置——<b>这不是 Mock LLM</b>（真实模型形态由
 * 068-S10/S11 端到端承接，两层互为对照）。
 *
 * <p>模板资产读取自被测制品 classpath（engine plain jar 的 {@code /workflow-templates/*.yaml}），
 * 制品坐标 {@code deepanalyze-engine:0.1.3-feat068}（源=agent-solution common@6cdf72a6b，
 * 指纹见批次 evidence/20261019-build-feat068/）。
 */
final class DeepanalyzeTemplateTestSupport {

    /** 实装静态模板名（FEAT-068 Feature §2 行1 / L2 §2.1，随制品 classpath 对账基准）。 */
    static final List<String> SHIPPED_TEMPLATES = List.of(
            "research-pipeline", "comprehensive-analysis", "comprehensive-deep-analysis",
            "generic-parallel-retrieval", "parallel-deep-retrieval", "parallel-research",
            "multi-perspective-council", "zero-hallucination");

    private DeepanalyzeTemplateTestSupport() {
    }

    /** 用例隔离：每次调用生成唯一后缀，标记串不依赖方法顺序或残留状态。 */
    static String uid() {
        return Long.toHexString(System.nanoTime()) + "-" + (int) (Math.random() * 0x7fffffff);
    }

    /** 读被测制品 classpath 上的随仓模板资产全文。 */
    static String readClasspath(final String path) throws IOException {
        try (InputStream in = DeepanalyzeTemplateTestSupport.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("classpath 资源应存在：%s（被测制品 workflow-templates 随包资产）", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 确定性成员执行体工厂：每个成员回显「标记-成员 id-唯一后缀」。 */
    static Function<MemberSpec, SubAgentExecutor> echoFactory(final String marker) {
        return member -> (userInput, subGoal) -> marker + "-" + member.id();
    }

    /* ---- ToolOutput 信封解包（与 067 线 DaWorkflowTestSupport 同型，独立实现避免跨包依赖） ---- */

    static ToolOutput output(final Object raw) {
        assertThat(raw).as("工具 invoke 应返回 ToolOutput 信封（返回其它类型即契约变形）")
                .isInstanceOf(ToolOutput.class);
        return (ToolOutput) raw;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> dataOf(final ToolOutput out) {
        assertThat(out.isSuccess()).as("成功路径 isSuccess 应为 true；error=%s", out.getError()).isTrue();
        assertThat(out.getData()).as("成功路径应带 data").isInstanceOf(Map.class);
        return (Map<String, Object>) out.getData();
    }

    static String errorOf(final ToolOutput out) {
        assertThat(out.isSuccess()).as("失败路径 isSuccess 应为 false").isFalse();
        assertThat(out.getError()).as("失败路径应带可读 error 文案").isNotNull().isNotBlank();
        return out.getError();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> stepsOf(final Map<String, Object> data) {
        assertThat(data.get("steps")).as("出参应含 steps").isInstanceOf(List.class);
        return (List<Map<String, Object>>) data.get("steps");
    }

    /** 单成员单步流水线图纸（最小合法图纸，用于双入参优先级对照）。 */
    static String singleMemberBlueprint(final String blueprintId, final String stepId) {
        return """
                blueprint:
                  id: %s
                  type: pipeline
                  members:
                    - {id: m1, instruction: "只输出一句话：S08 全文优先对照标记"}
                  steps:
                    - {id: %s, member: m1}
                """.formatted(blueprintId, stepId);
    }

    /** 解析被测源码根（静态合同用例；缺省记 SKIPPED，不判产品 FAIL——与 067 线同约定）。 */
    static Path resolveDeepanalyzeSourceRoot() throws IOException {
        final String fromProperty = System.getProperty("deepanalyze.source.root");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Path.of(fromProperty.trim());
        }
        final String fromEnv = System.getenv("DEEPANALYZE_SOURCE_ROOT");
        org.junit.jupiter.api.Assumptions.assumeTrue(fromEnv != null && !fromEnv.isBlank(),
                "未提供 DA 宿主源码根：加 -Ddeepanalyze.source.root=<engine src/main/java 绝对路径>。"
                        + "缺它时本用例记 SKIPPED（INCONCLUSIVE），不判产品 FAIL。");
        final Path root = Path.of(fromEnv.trim());
        assertThat(Files.isDirectory(root)).as("被测源码根应存在：%s", root).isTrue();
        return root;
    }
}
