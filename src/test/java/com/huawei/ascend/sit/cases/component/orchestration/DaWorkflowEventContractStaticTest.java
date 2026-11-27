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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S15：事件契约不含无生产者的事件声明（静态反向断言）。
 *
 * <p>Oracle 来源：FEAT-067 §3「契约**不声明没有生产者的事件**」+ §5.1.4「契约诚实：只声明已接线的事件，
 * 缺口标注为缺口」；L2-067 §2.5 自陈「!805 **删五件死契约**」。
 *
 * <p>判据：① {@code DaPushEvents} 公开声明的**工作流事件恰好两件**
 * （{@code workflow_start} / {@code workflow_complete}，与 Feature §3 声明的清单一致）；
 * ② 每件都有**可指认的生产者**（{@code DaWorkflowRunner} 里的调用点，给出文件与行号作证据）；
 * ③ 不存在第三件工作流事件声明，也不存在无调用点的方法（⇒ 死契约复活即 FAIL，正是 !805 修过的问题）。
 *
 * <p>这是设计**本来只要求静态验证**的条目（形态 {@code static}），静态断言即充分、不属降级。
 * 与 067-S13 的关系：S13 的"事件实际发出面"因无进程内观测点仍 {@code blocked}，
 * 本条从**契约面**证明"声明的事件都有生产者"，两者不重叠、不互相替代。
 *
 * <p><b>源码定位方式</b>同 065-S21：系统属性 {@code deepanalyze.source.root} →
 * 环境变量 {@code DEEPANALYZE_SOURCE_ROOT}；都缺时中止（SKIPPED ⇒ INCONCLUSIVE，不判产品 FAIL）。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowEventContractStaticTest {

    private static final String EVENTS_SOURCE = "com/openjiuwen/da/compat/DaPushEvents.java";
    private static final String RUNNER_SOURCE = "com/openjiuwen/da/workflow/DaWorkflowRunner.java";
    /**
     * 【2026-10-26 r6 新增扫描面】事件发射被重构为**两级**：
     * {@code DaWorkflowRunner} →（实例）{@code DaWorkflowEvents} → {@code DaPushEvents}（契约发射点）。
     * 原用例只扫 runner 里的 {@code DaPushEvents.workflowXxx(}，重构后恒为空 ⇒ **误报"死契约复活"**。
     */
    private static final String EVENTS_BRIDGE_SOURCE = "com/openjiuwen/da/workflow/DaWorkflowEvents.java";

    /** Feature §3 声明的工作流事件清单（恰好两件）。 */
    private static final List<String> DECLARED_EVENTS = List.of("workflow_start", "workflow_complete");

    @Test
    @Story("067-S15: 事件契约不含无生产者的事件声明")
    @DisplayName("067-S15 工作流事件恰好两件、各有生产者调用点、无第三件也无死契约")
    void workflowEventContractHasNoProducerlessDeclaration() throws IOException {
        final String events = DaWorkflowTemplateResolutionTest.readDeepanalyzeSource(EVENTS_SOURCE);
        final String runner = DaWorkflowTemplateResolutionTest.readDeepanalyzeSource(RUNNER_SOURCE);

        // ① 事件名字面量恰好两件（扫 "workflow_*" 形态的字符串字面量）
        final Set<String> eventLiterals = stringLiteralsWithPrefix(events, "workflow_");
        assertThat(eventLiterals)
                .as("DaPushEvents 里声明的工作流事件名应恰好为 %s（多一件即可能出现死契约，少一件即契约缺项）；"
                        + "实收=%s", DECLARED_EVENTS, eventLiterals)
                .containsExactlyInAnyOrderElementsOf(DECLARED_EVENTS);

        // ① 补：公开的事件方法也应恰好两件（方法面与事件名字面量面双向对账）
        final Set<String> workflowMethods = publicStaticVoidMethods(events).stream()
                .filter(name -> name.toLowerCase().startsWith("workflow"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertThat(workflowMethods).as("工作流事件的公开方法应恰好两件（workflowStart / workflowComplete）；"
                + "实收=%s", workflowMethods).containsExactlyInAnyOrder("workflowStart", "workflowComplete");

        // ② 每件都有可指认的生产者。
        // 【2026-10-26 r6 适配：两级生产者链，**设计判据本身不变**】
        //   (a) 契约发射点：DaWorkflowEvents 里必须有 DaPushEvents.workflowStart( 与 .workflowComplete(
        //       —— 这是"事件真的会被发出去"的落点，无落点即死契约复活（!805 修过的问题）
        //   (b) 生产者：DaWorkflowRunner 里必须有 DaWorkflowEvents.start(（启动）与 events.complete(（收口）
        //   (c) L2-067 §2.5 的 finally 口径：events.complete( 必须**多于 1 处**
        //       （r6 实测 3 处：:192 成功路径 / :199 catch 面内补发 / :208 finally 面外兜底）
        final String bridge = DaWorkflowTemplateResolutionTest.readDeepanalyzeSource(EVENTS_BRIDGE_SOURCE);
        for (String method : List.of("workflowStart", "workflowComplete")) {
            final List<Integer> callSites = lineNumbersContaining(bridge, "DaPushEvents." + method + "(");
            assertThat(callSites).as("事件方法 %s 应有契约发射点（无发射点即死契约复活，正是 !805 修过的问题）；"
                    + "被扫文件=%s；实收行号=%s", method, EVENTS_BRIDGE_SOURCE, callSites).isNotEmpty();
        }
        final List<Integer> startSites = lineNumbersContaining(runner, "DaWorkflowEvents.start(");
        final List<Integer> completeSites = lineNumbersContaining(runner, "events.complete(");
        assertThat(startSites).as("workflow_start 的生产者调用点行数（%s）；实收=%s", RUNNER_SOURCE, startSites)
                .isNotEmpty();
        assertThat(completeSites).as("workflow_complete 的生产者调用点行数（%s）；"
                + "应多于 1 处——成功路径 + catch 面内补发 + finally 面外兜底（L2-067 §2.5 finally 口径）；实收=%s",
                RUNNER_SOURCE, completeSites).hasSizeGreaterThan(1);

        // ③ 订阅键形态与 Feature §3 一致：workflow:{workflowId}
        assertThat(events).as("契约里的订阅键形态应为 \"workflow:\" + workflowId（Feature §3）")
                .contains("\"workflow:\" + workflowId");

        // 反向自检：扫描机制本身有效
        assertThat(events).as("扫描自检：应能扫到类名 DaPushEvents").contains("DaPushEvents");
        assertThat(runner).as("扫描自检：应能扫到类名 DaWorkflowRunner").contains("DaWorkflowRunner");
        assertThat(bridge).as("扫描自检：应能扫到类名 DaWorkflowEvents（否则 (a) 组断言是空扫）")
                .contains("DaWorkflowEvents");
        assertThat(publicStaticVoidMethods(events)).as("扫描自检：DaPushEvents 应有其它域的公开事件方法")
                .hasSizeGreaterThan(2);
    }

    /** 扫出源码里所有形如 {@code "workflow_xxx"} 的字符串字面量。 */
    private static Set<String> stringLiteralsWithPrefix(final String text, final String prefix) {
        final Set<String> found = new LinkedHashSet<>();
        int from = 0;
        while (true) {
            final int open = text.indexOf('"' + prefix, from);
            if (open < 0) {
                break;
            }
            final int close = text.indexOf('"', open + 1);
            if (close < 0) {
                break;
            }
            found.add(text.substring(open + 1, close));
            from = close + 1;
        }
        return found;
    }

    /** 扫出源码里所有 {@code public static void <name>(} 的方法名。 */
    private static List<String> publicStaticVoidMethods(final String text) {
        final List<String> names = new ArrayList<>();
        for (String raw : text.split("\n")) {
            final String line = raw.trim();
            if (!line.startsWith("public static void ")) {
                continue;
            }
            final int open = line.indexOf('(');
            if (open < 0) {
                continue;
            }
            final String head = line.substring("public static void ".length(), open).trim();
            if (!head.isEmpty()) {
                names.add(head);
            }
        }
        return names;
    }

    /** 返回含有指定子串的行号（1 起）。 */
    private static List<Integer> lineNumbersContaining(final String text, final String needle) {
        final List<Integer> lines = new ArrayList<>();
        final String[] all = text.split("\n");
        for (int i = 0; i < all.length; i++) {
            if (all[i].contains(needle)) {
                lines.add(i + 1);
            }
        }
        return lines;
    }
}
