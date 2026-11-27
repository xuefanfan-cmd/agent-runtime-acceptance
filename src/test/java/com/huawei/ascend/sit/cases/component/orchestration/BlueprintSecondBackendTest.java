/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.BlueprintLoader;
import com.openjiuwen.agents.orchestration.BlueprintSpec;
import com.openjiuwen.agents.orchestration.BlueprintType;
import com.openjiuwen.agents.orchestration.BlueprintWorkflowCompiler;
import com.openjiuwen.core.workflow.Workflow;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 065-S16 / S17：第二执行后端（蓝图 → core Workflow 组件图编译器）。
 *
 * <p>Oracle 来源：FEAT-065 §2「第二执行后端」+ §5.2「第二后端并行」；L2-065 §2.5「第二后端编译契约」
 * 与 §3.8「第二后端编译流」。
 *
 * <p><b>§11.2 更正：S16/S17 均为 gating</b>——Feature §2 新裁决「第二后端**本体为可选能力**；
 * 若提供，则…**拒绝义务在提供该后端时为 MUST**」，而本期该后端**已提供**（GAP-065-05 已关闭）。
 *
 * <p>S17 拦住的故障最要紧：core WorkflowAgent **无并行执行**，若编译器把 dag/parallel/
 * cross_validation 悄悄压扁成串行图，就是**静默降级**——语义变了却不报错。因此断言
 * "三型全部被拒 + 消息指引回 {@code BlueprintScheduler} + 不产生任何组件图 + 执行体零调用"。
 *
 * <p>S16 的结构判据用 core 的**公开导出面** {@code Workflow.draw()}（mermaid 文本）核对：
 * 节点集合 == {@code start} + 每步一个组件 + {@code end}，且连线是**一条线性链**
 * （每个节点出度/入度恰为 1，首节点无入边、末节点无出边）——出现任何分叉或汇合即失败。
 *
 * <p>不应断言：core Workflow 引擎的**执行行为**（L2 §1.3 明示"WF 引擎行为由 core 自身测试覆盖"，
 * 且 Feature §2 OUT「core 仓改动」）；异常**类名**（L2 给的 {@code IllegalArgumentException} 属实现方式）
 * ⇒ S17 只断言"抛出可识别的运行时异常且消息含指引"，不断言具体类型。
 * L2 §3.8 的"首步 query 走 userInput 位、后续步上游产物进 {@code [upstream outputs]} 段"
 * 需要真正跑通 core 图引擎（要 session/ModelContext），属 core 侧执行行为 ⇒ 本类**不断言**，
 * 只在批次记录登记为 OUT/non-gating 增强项。
 */
@Tag("component")
@Tag("feat-065")
@Feature("FEAT-065: 确定性编排层(蓝图调度)")
class BlueprintSecondBackendTest {

    private static final String RUN = OrchestrationTestSupport.uid();

    private static final String PIPELINE = """
            blueprint:
              id: T-065-S16-pipeline
              type: pipeline
              members:
                - {id: m1, instruction: "汇总技术要点"}
                - {id: m2, instruction: "汇总合规要点"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
            """;

    private static final String SINGLE = """
            blueprint:
              id: T-065-S16-single
              type: single
              members:
                - {id: m1, instruction: "独立作答"}
            """;

    private static final String DAG = """
            blueprint:
              id: T-065-S17-dag
              type: dag
              members:
                - {id: m1, instruction: "技术视角"}
                - {id: m2, instruction: "合规视角"}
                - {id: m3, instruction: "汇点"}
              steps:
                - {id: s1, member: m1}
                - {id: s2, member: m2}
                - {id: s3, member: m3, depends_on: [s1, s2]}
            """;

    private static final String PARALLEL = """
            blueprint:
              id: T-065-S17-parallel
              type: parallel
              members:
                - {id: m1, instruction: "技术视角"}
                - {id: m2, instruction: "合规视角"}
            """;

    private static final String CROSS_VALIDATION = """
            blueprint:
              id: T-065-S17-cv
              type: cross_validation
              perspectives:
                - {id: pa, instruction: "技术视角"}
                - {id: pb, instruction: "合规视角"}
              auditor: {id: aud, instruction: "汇总审计"}
            """;

    /**
     * core 的组件图导出面（{@code Workflow.draw()}）默认关闭，由 {@code WORKFLOW_DRAWABLE} 开关控制，
     * 且在 {@code Workflow} **构造期**决定是否建图 ⇒ 必须在 {@code compile} 之前置位。
     * 该开关只影响"是否额外维护一份可绘图结构"，不改变编译产物语义。
     */
    @BeforeAll
    static void enableGraphExport() {
        System.setProperty("WORKFLOW_DRAWABLE", "true");
    }

    @Test
    @Story("065-S16: 第二后端编译流水线与单兵图纸")
    @DisplayName("065-S16 pipeline 两步：编译成功，组件图为 start → s1 → s2 → end 的线性链")
    void pipelineCompilesToSerialComponentGraph() {
        final BlueprintSpec spec = BlueprintLoader.load(PIPELINE);
        final var loaded = OrchestrationTestSupport.load(PIPELINE, "065-S16", RUN);

        final Workflow workflow = BlueprintWorkflowCompiler.compile(
                spec, OrchestrationTestSupport.bindings(loaded.recorders));

        assertThat(workflow).as("pipeline 型应能被第二后端编译").isNotNull();
        assertThat(workflow.getCard()).as("编译产物应带工作流卡").isNotNull();
        assertThat(workflow.getCard().getId()).as("卡 id 应沿用图纸 id").isEqualTo("T-065-S16-pipeline");
        assertThat(workflow.getCard().getName()).as("卡名应沿用图纸 id").isEqualTo("T-065-S16-pipeline");
        assertThat(workflow.getCard().getDescription())
                .as("卡描述应标明来源于哪张图纸").contains("T-065-S16-pipeline");

        final String graph = workflow.draw();
        assertThat(graph).as("编译产物应可导出组件图（mermaid）；实收=[%s]", graph).isNotBlank();
        final List<String> labels = nodeLabels(graph);
        assertThat(labels).as("组件图节点标签应为 start + 每步一个组件 + end；实收图=[%s]", graph)
                .containsExactly("start", "s1", "s2", "end");
        assertThat(labels).as("组件数应与步数对应（2 步 + Start/End 收口 = 4 个组件）").hasSize(4);
        assertLinearChain(graph, labels);

        // 编译阶段不执行任何成员（只建图）
        loaded.recorders.forEach((memberId, rec) -> assertThat(rec.calls())
                .as("编译阶段不应调用成员 %s 的执行体", memberId).isZero());
    }

    @Test
    @Story("065-S16: 第二后端编译流水线与单兵图纸")
    @DisplayName("065-S16 single 单成员：编译成功，组件图为 start → 单步 → end 的线性链")
    void singleCompilesToOneStepComponentGraph() {
        final BlueprintSpec spec = BlueprintLoader.load(SINGLE);
        final var loaded = OrchestrationTestSupport.load(SINGLE, "065-S16-single", RUN);

        final Workflow workflow = BlueprintWorkflowCompiler.compile(
                spec, OrchestrationTestSupport.bindings(loaded.recorders));

        assertThat(workflow).as("single 型应能被第二后端编译").isNotNull();
        assertThat(workflow.getCard().getId()).isEqualTo("T-065-S16-single");
        assertThat(spec.steps()).as("single 省略 steps 时应由成员推导出恰好一步").hasSize(1);
        final String stepId = spec.steps().get(0).id();
        final String graph = workflow.draw();
        final List<String> labels = nodeLabels(graph);
        assertThat(labels).as("组件图节点标签应为 start + 推导出的单步 + end；实收图=[%s]", graph)
                .containsExactly("start", stepId, "end");
        assertLinearChain(graph, labels);
    }

    static Stream<Arguments> rejectedShapes() {
        return Stream.of(
                Arguments.of("依赖图(dag)", DAG, "T-065-S17-dag"),
                Arguments.of("全并行(parallel)", PARALLEL, "T-065-S17-parallel"),
                Arguments.of("交叉验证(cross_validation)", CROSS_VALIDATION, "T-065-S17-cv"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedShapes")
    @Story("065-S17: 第二后端拒绝并行型图纸（不得静默降级为串行）")
    @DisplayName("065-S17 dag/parallel/cross_validation 三型全部被拒且消息指引回 BlueprintScheduler")
    void parallelShapesAreRejectedWithGuidance(final String label, final String yaml,
            final String blueprintId) {
        final BlueprintSpec spec = BlueprintLoader.load(yaml);
        final var loaded = OrchestrationTestSupport.load(yaml, "065-S17-" + blueprintId, RUN);

        final Throwable[] thrown = new Throwable[1];
        final Workflow[] produced = new Workflow[1];
        try {
            produced[0] = BlueprintWorkflowCompiler.compile(
                    spec, OrchestrationTestSupport.bindings(loaded.recorders));
        } catch (RuntimeException ex) {
            thrown[0] = ex;
        }

        assertThat(thrown).as("%s 属并行型，第二后端无并行能力 ⇒ **必须拒绝**；"
                + "若编译成功即静默降级为串行图（语义被改却不报错）", label).isNotNull();
        assertThat(produced[0]).as("%s 被拒时不应产生任何组件图", label).isNull();
        assertThat(thrown[0].getMessage())
                .as("%s 的拒绝消息应带图纸 id 定位；实收=[%s]", label, thrown[0].getMessage())
                .contains("blueprint id=" + blueprintId);
        assertThat(thrown[0].getMessage())
                .as("%s 的拒绝消息应**指引回主后端** BlueprintScheduler；实收=[%s]",
                        label, thrown[0].getMessage())
                .contains("BlueprintScheduler");
        // 被拒时不得先跑一部分成员
        loaded.recorders.forEach((memberId, rec) -> assertThat(rec.calls())
                .as("%s 被拒时成员 %s 的执行体不应被调用", label, memberId).isZero());
        // 交叉验证型经装载期编译后对外表现为 DAG（FEAT-066 §3），仍属并行型 ⇒ 同样被拒
        if (CROSS_VALIDATION.equals(yaml)) {
            assertThat(spec.type()).as("cross_validation 装载后应表现为 DAG")
                    .isEqualTo(BlueprintType.DAG);
        }
    }

    /** 解析 mermaid 的节点声明行，按出现序返回 {@code [节点id, 标签]}。不用正则，逐行切分即可。 */
    private static List<String[]> nodeDeclarations(final String mermaid) {
        final List<String[]> nodes = new ArrayList<>();
        for (String raw : mermaid.split("\n")) {
            final String line = raw.trim();
            if (!line.startsWith("node_") || line.contains("-->")) {
                continue;
            }
            final int open = indexOfFirst(line, '(', '[');
            final int close = Math.max(line.lastIndexOf(')'), line.lastIndexOf(']'));
            if (open < 0 || close <= open) {
                continue;
            }
            String text = line.substring(open + 1, close).trim();
            if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
                text = text.substring(1, text.length() - 1);
            }
            nodes.add(new String[] {line.substring(0, open).trim(), text});
        }
        return nodes;
    }

    private static List<String> nodeLabels(final String mermaid) {
        final List<String> labels = new ArrayList<>();
        for (String[] node : nodeDeclarations(mermaid)) {
            labels.add(node[1]);
        }
        return labels;
    }

    /** 解析 mermaid 的连线行 {@code node_a --> node_b}。 */
    private static List<String[]> edges(final String mermaid) {
        final List<String[]> result = new ArrayList<>();
        for (String raw : mermaid.split("\n")) {
            final String line = raw.trim();
            final int at = line.indexOf("-->");
            if (at < 0 || !line.startsWith("node_")) {
                continue;
            }
            result.add(new String[] {line.substring(0, at).trim(), line.substring(at + 3).trim()});
        }
        return result;
    }

    /**
     * 断言组件图是**一条线性链**：按节点出现序两两相连，每个节点出度/入度恰为 1，
     * 首节点无入边、末节点无出边。这正是"第二后端无并行"的结构判据——任何分叉或汇合即失败。
     */
    private static void assertLinearChain(final String mermaid, final List<String> labels) {
        final List<String[]> edges = edges(mermaid);
        final List<String[]> nodes = nodeDeclarations(mermaid);
        assertThat(edges).as("组件图应有连线；实收图=[%s]", mermaid).isNotEmpty();
        assertThat(edges).as("N 个节点的线性链应恰有 N-1 条边；实收图=[%s]", mermaid)
                .hasSize(labels.size() - 1);
        final Map<String, Integer> outDegree = new LinkedHashMap<>();
        final Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String[] edge : edges) {
            outDegree.merge(edge[0], 1, Integer::sum);
            inDegree.merge(edge[1], 1, Integer::sum);
        }
        for (int i = 0; i < labels.size() - 1; i++) {
            final String from = nodes.get(i)[0];
            final String to = nodes.get(i + 1)[0];
            assertThat(edges).as("应存在边 %s(%s) → %s(%s)；实收图=[%s]",
                    labels.get(i), from, labels.get(i + 1), to, mermaid)
                    .anyMatch(e -> e[0].equals(from) && e[1].equals(to));
            assertThat(outDegree.getOrDefault(from, 0))
                    .as("节点 %s 的出度应恰为 1（不得分叉成并行）；实收图=[%s]", labels.get(i), mermaid)
                    .isEqualTo(1);
            assertThat(inDegree.getOrDefault(to, 0))
                    .as("节点 %s 的入度应恰为 1（不得多路汇合）；实收图=[%s]", labels.get(i + 1), mermaid)
                    .isEqualTo(1);
        }
        assertThat(outDegree).as("末节点 %s 不应有出边", labels.get(labels.size() - 1))
                .doesNotContainKey(nodes.get(nodes.size() - 1)[0]);
        assertThat(inDegree).as("首节点 %s 不应有入边", labels.get(0)).doesNotContainKey(nodes.get(0)[0]);
    }

    private static int indexOfFirst(final String text, final char a, final char b) {
        final int ia = text.indexOf(a);
        final int ib = text.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }
}
