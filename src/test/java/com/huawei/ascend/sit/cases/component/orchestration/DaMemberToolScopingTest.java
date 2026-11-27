/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.da.config.DaProperties;
import com.openjiuwen.da.toolcontext.DaToolContext;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S30：成员工具集**按发起方会话范围收敛**（真实调用产品代码，确定性、不用模型）。
 *
 * <p><b>Oracle</b>：{@code FEAT-067-orchestration-tool-trio.md:50}（MUST，docs {@code main@65118fda}／{@code !379}）
 * 末句「**联网开关与知识库范围继续收敛实际工具集**」；{@code Feat-Func-067:52} 给出落点
 * 「(DaWorkflowRunner.java:58-64/:234-256)」，即 {@code DaWorkflowRunner.scopedTools(tools, context, toolContext)}。
 *
 * <p><b>为什么用反射</b>：{@code scopedTools} 与 {@code MEMBER_TOOLS} 都是 **package-private**
 * （{@code com.openjiuwen.da.workflow} 包内），SIT 用例不在该包 ⇒ 只能反射取。反射在这里**不削弱判据**：
 * 调用的是产品**同一份真实实现**（不是桩、不是复制品），入参 {@link DaToolContext} 与
 * {@link DaProperties.ToolContext} 都是产品的 public 类型，取缺省值即生产缺省值。
 * 对方同族 UT 参照：{@code DaWorkflowMemberTest#webOff_dropsWebTools_kbScopeKeepsKbTools} /
 * {@code #noKbScope_dropsKbTools_webOnKeepsWebTools}（{@code !937} 新增）——本用例**不重复**它们，
 * 而是把两维做成**四象限全矩阵**并断言"剩下的集合恰好等于清单减去对应族"，比"不含某几件"更强。
 *
 * <p>两族的定义取自 {@code DaProperties.ToolContext} 的**生产缺省值**（不自己编）：
 * {@code webTools} = web_search/web_fetch/wikipedia/scholar_search/archive_org_lookup/pubchem_query；
 * {@code conditionalTools} = kb_search/expand/doc_grep/wiki_browse/db_query/timeline_build/graph_build。
 * 与 14 件成员清单求交后：web 族 3 件（web_search/wikipedia/scholar_search）、kb 族 4 件
 * （kb_search/doc_grep/expand/wiki_browse）⇒ 两族在成员清单内**不重叠**，可独立验证。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 会话内编排工具组")
class DaMemberToolScopingTest {

    private static final String SESSION = "sess-067-s30";

    @SuppressWarnings("unchecked")
    private static List<String> memberTools() throws ReflectiveOperationException {
        final Field f = DaWorkflowRunner.class.getDeclaredField("MEMBER_TOOLS");
        f.setAccessible(true);
        return List.copyOf((List<String>) f.get(null));
    }

    private static DaProperties.ToolContext productionToolContext() {
        // 取生产缺省值：不自己设 webTools/conditionalTools，避免把"我方编的族定义"当成判据
        return new DaProperties().getToolContext();
    }

    private static List<String> scoped(final List<String> tools, final DaToolContext ctx,
            final DaProperties.ToolContext toolContext) throws ReflectiveOperationException {
        final Method m = DaWorkflowRunner.class.getDeclaredMethod("scopedTools", List.class, DaToolContext.class,
                DaProperties.ToolContext.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<String> out = (List<String>) m.invoke(null, tools, ctx, toolContext);
        return out;
    }

    private static List<String> intersection(final List<String> a, final List<String> b) {
        final List<String> out = new ArrayList<>(a);
        out.retainAll(b);
        return out;
    }

    @Test
    @Story("067-S30: 联网开关与知识库范围独立收敛成员工具集（四象限全矩阵）")
    @DisplayName("067-S30 成员工具按发起方会话范围收敛：web/kb 两开关四象限，每象限剩下的集合恰好等于 14 件减去对应族")
    void webAndKbScopesIndependentlyConvergeMemberToolSet() throws ReflectiveOperationException {
        final List<String> all = memberTools();
        final DaProperties.ToolContext tc = productionToolContext();
        final List<String> webFamily = intersection(all, tc.getWebTools());
        final List<String> kbFamily = intersection(all, tc.getConditionalTools());

        // 前置事实核对：两族在成员清单内的构成（与 L2/Feature 的口径一致性由此建立，不靠假设）
        assertThat(webFamily)
                .as("成员清单与生产 webTools 的交集应为 3 件（web_search/wikipedia/scholar_search）；实收=%s", webFamily)
                .containsExactlyInAnyOrder("web_search", "wikipedia", "scholar_search");
        assertThat(kbFamily)
                .as("成员清单与生产 conditionalTools 的交集应为 4 件（kb_search/doc_grep/expand/wiki_browse）；实收=%s", kbFamily)
                .containsExactlyInAnyOrder("kb_search", "doc_grep", "expand", "wiki_browse");
        assertThat(intersection(webFamily, kbFamily))
                .as("两族在成员清单内不应重叠（否则无法独立验证两个开关）").isEmpty();

        final List<String> expectedAll = all;
        final List<String> expectedWebOff = minus(all, webFamily);
        final List<String> expectedKbOff = minus(all, kbFamily);
        final List<String> expectedBothOff = minus(all, union(webFamily, kbFamily));

        final StringBuilder evidence = new StringBuilder();
        evidence.append("成员清单（14 件）=").append(all)
                .append("\nweb 族=").append(webFamily)
                .append("\nkb 族=").append(kbFamily).append('\n');

        // ① 联网开 + 有知识库范围 ⇒ 全量 14 件
        final List<String> q1 = scoped(all, new DaToolContext(SESSION, "t-067-s30", List.of("kb-e2e-a"), true), tc);
        assertThat(q1).as("① webSearch=true + kbScope 非空 ⇒ 应收敛为全量 14 件（一件不减）")
                .containsExactlyInAnyOrderElementsOf(expectedAll);

        // ② 联网关 + 有知识库范围 ⇒ 只掉 web 族 3 件，kb 族必须保留
        final List<String> q2 = scoped(all, new DaToolContext(SESSION, "t-067-s30", List.of("kb-e2e-a"), false), tc);
        assertThat(q2).as("② webSearch=false + kbScope 非空 ⇒ 应恰好去掉 web 族 3 件、保留 kb 族")
                .containsExactlyInAnyOrderElementsOf(expectedWebOff);
        assertThat(q2).as("② 联网关闭不得误伤知识库类工具（过度抑制同样是缺陷）")
                .containsAll(kbFamily);

        // ③ 联网开 + 无知识库范围 ⇒ 只掉 kb 族 4 件，web 族必须保留
        final List<String> q3 = scoped(all, new DaToolContext(SESSION, "t-067-s30", List.of(), true), tc);
        assertThat(q3).as("③ webSearch=true + kbScope 为空 ⇒ 应恰好去掉 kb 族 4 件、保留 web 族")
                .containsExactlyInAnyOrderElementsOf(expectedKbOff);
        assertThat(q3).as("③ 无知识库范围不得误伤联网类工具").containsAll(webFamily);

        // ④ 两个都关 ⇒ 掉 7 件，剩 7 件；且剩下的必须全是"既非 web 也非 kb"的本地只读工具
        final List<String> q4 = scoped(all, new DaToolContext(SESSION, "t-067-s30", List.of(), false), tc);
        assertThat(q4).as("④ webSearch=false + kbScope 为空 ⇒ 应恰好去掉两族共 7 件")
                .containsExactlyInAnyOrderElementsOf(expectedBothOff);
        assertThat(q4).as("④ 收敛后剩下的必须全是文件系统/笔记/文档/PDF/SQL 类本地只读工具")
                .containsExactlyInAnyOrder("read_file", "grep", "glob", "list_files", "notebook_read",
                        "pdf_read", "run_sql");

        // ⑤ kbScope=null 与 kbScope=[] 同义（record 紧凑构造器把 null 归一为空列表）
        final List<String> q5 = scoped(all, new DaToolContext(SESSION, "t-067-s30", null, true), tc);
        assertThat(q5).as("⑤ kbScope=null 应与 kbScope=[] 同义（都判为无知识库范围）")
                .containsExactlyInAnyOrderElementsOf(q3);

        // ⑥ 不得增件：任何象限的结果都必须是原清单的子集（收敛只能减，不能加）
        for (List<String> q : List.of(q1, q2, q3, q4, q5)) {
            assertThat(new LinkedHashSet<>(all)).as("⑥ 收敛只能减不能加：结果必须是 14 件的子集").containsAll(q);
            assertThat(new LinkedHashSet<>(q)).as("⑥ 收敛结果内不应有重复项").hasSize(q.size());
        }

        evidence.append("① web开+kb有 = ").append(q1).append('\n')
                .append("② web关+kb有 = ").append(q2).append('\n')
                .append("③ web开+kb无 = ").append(q3).append('\n')
                .append("④ web关+kb无 = ").append(q4).append('\n')
                .append("⑤ kbScope=null = ").append(q5).append('\n');
        Allure.addAttachment("067-S30 四象限收敛实测（真实调用 DaWorkflowRunner.scopedTools）", "text/plain",
                evidence.toString());
    }

    private static List<String> minus(final List<String> from, final List<String> what) {
        final List<String> out = new ArrayList<>(from);
        out.removeAll(what);
        return out;
    }

    private static List<String> union(final List<String> a, final List<String> b) {
        final Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return new ArrayList<>(out);
    }
}
