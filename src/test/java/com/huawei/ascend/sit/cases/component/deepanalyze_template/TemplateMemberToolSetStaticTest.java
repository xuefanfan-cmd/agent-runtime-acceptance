/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.deepanalyze_template;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 068-S11 成员工具面静态对账（细档 S11 判据块，docs#37 / !379 裁决落定后的承载形态）：
 * 模板成员与委托执行体的工具面 = <b>14 件只读工具（MUST）</b>，且与写工具、编排六件<b>交集为空（MUST NOT）</b>。
 *
 * <p>Oracle 来源：FEAT-067 §2 两条 MUST/MUST NOT 行＋L2-067 成员工厂段（!379 {@code cbd1ff5d} 同步回写）；
 * FEAT-068 细档 S11 节成员工具判据块（2026-10-27 裁决重写，原 degraded 挂载解除后的静态承载）。
 * 14 件清单（细档 S11 逐字）：read_file/grep/glob/list_files/notebook_read/kb_search/doc_grep/expand/
 * web_search/wikipedia/wiki_browse/scholar_search/pdf_read/run_sql——run_sql 只读性由平台 SELECT/WITH
 * 限制与只读事务保证（L2-067，本类不重复断言其实现）。
 *
 * <p>观测面分工：e2e＝成员工具实调记录 ⊆ 白名单（SUT 日志成员工具 outcome 面，随执行轮取证）；
 * <b>静态＝{@code DaWorkflowRunner.MEMBER_TOOLS} 与 L2-067 清单对账（本类承载，读被测制品对应源码）</b>。
 * 模型口径 temperature/max_tokens 缺省 0.3/4000 可经宿主配置覆盖——L2 明记"可覆盖"，不作 gating。
 *
 * <p>源码锚点（r6=babb882b9，2026-11-24 实读）：DaWorkflowRunner.java :84-86
 * {@code static final List<String> MEMBER_TOOLS = List.of("read_file", …, "run_sql")}。
 * 缺源码根时记 SKIPPED（INCONCLUSIVE），不判产品 FAIL。
 */
@Tag("component")
@Tag("feat-068")
@Feature("FEAT-068: 团队模板库")
class TemplateMemberToolSetStaticTest {

    /** L2-067 / FEAT-067 §2 的 14 件只读工具（对账基准，细档 S11 判据块逐字）。 */
    private static final List<String> EXPECTED_MEMBER_TOOLS = List.of(
            "read_file", "grep", "glob", "list_files", "notebook_read",
            "kb_search", "doc_grep", "expand", "web_search", "wikipedia", "wiki_browse",
            "scholar_search", "pdf_read", "run_sql");

    /** 写工具（FEAT-067 §2 MUST NOT 面）：交集必须为空。 */
    private static final List<String> WRITE_TOOLS = List.of("write_file", "edit_file", "bash", "powershell");

    /** 编排六件（FEAT-067 §2 MUST NOT 面）：交集必须为空。 */
    private static final List<String> ORCHESTRATION_TOOLS = List.of(
            "workflow_run", "workflow_status", "delegate_task", "task_output", "send_message", "subagent_transcript");

    @Test
    @Story("068-S11: 真机 e2e——parallel-deep-retrieval 点名全链路（含成员工具面静态对账）")
    @DisplayName("068-S11 成员工具面静态对账：MEMBER_TOOLS 恰为 14 件只读（MUST），与写工具/编排六件交集为空（MUST NOT）")
    void memberToolSetMatchesL2WhitelistAndExcludesWriteAndOrchestration() throws IOException {
        final Path root = DeepanalyzeTemplateTestSupport.resolveDeepanalyzeSourceRoot();
        final String source = Files.readString(
                root.resolve("com/openjiuwen/da/workflow/DaWorkflowRunner.java"), StandardCharsets.UTF_8);

        // 定位 MEMBER_TOOLS = List.of( … ) 字面量（基线 :84-86，跨行声明、无嵌套括号）
        final int anchor = source.indexOf("MEMBER_TOOLS = List.of(");
        assertThat(anchor).as("应能扫到 DaWorkflowRunner.MEMBER_TOOLS 声明（成员工具面真源）").isNotNegative();
        final int open = source.indexOf('(', anchor);
        final int close = source.indexOf(')', open);
        assertThat(close).as("MEMBER_TOOLS 的 List.of( 应有收尾右括号").isGreaterThan(open);
        final String literal = source.substring(open + 1, close);

        final List<String> actual = new ArrayList<>();
        final Matcher matcher = Pattern.compile("\"([a-z_0-9]+)\"").matcher(literal);
        while (matcher.find()) {
            actual.add(matcher.group(1));
        }
        assertThat(actual).as("MEMBER_TOOLS 字面量应解析出工具名（0 个说明解析方式失效）").isNotEmpty();

        // MUST：恰为 14 件只读工具（与 L2-067 清单双向一致）
        assertThat(actual).as("成员工具面应恰为 L2-067 的 14 件只读工具（多/少/改名都破坏 caller 收敛契约）")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_MEMBER_TOOLS);

        // MUST NOT：与写工具、编排六件交集为空
        final List<String> writeOverlap = new ArrayList<>(actual);
        writeOverlap.retainAll(WRITE_TOOLS);
        assertThat(writeOverlap).as("成员工具面不得含写工具（FEAT-067 §2 MUST NOT）；交集=%s", writeOverlap)
                .isEmpty();
        final List<String> orchestrationOverlap = new ArrayList<>(actual);
        orchestrationOverlap.retainAll(ORCHESTRATION_TOOLS);
        assertThat(orchestrationOverlap)
                .as("成员工具面不得含编排六件（FEAT-067 §2 MUST NOT）；交集=%s", orchestrationOverlap)
                .isEmpty();
    }
}
