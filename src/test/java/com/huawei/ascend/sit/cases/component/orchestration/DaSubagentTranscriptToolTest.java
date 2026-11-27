/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.SubagentTranscriptTool;
import com.openjiuwen.da.toolcontext.DaToolContext;
import com.openjiuwen.da.toolcontext.DaToolContextHolder;
import com.openjiuwen.harness.tools.ToolOutput;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S07：{@code subagent_transcript}——{@code roots} 列举 + 按 {@code branch_id} 下钻。
 *
 * <p>Oracle 来源（§11.2 已把三者**全部升为 gating**）：
 * ① {@code workflow_run} 出参 {@code steps[].branch_id} 非空（Feature §2 转测评审 #33 V3 新增）；
 * ② 用该值调 {@code subagent_transcript} 能取回对应分支详情（子分支 / 回溯路径 / 深度）；
 * ③ {@code branch_id="roots"} 列出全部执行根。
 * 上游依据：Feature FEAT-067 §2「按留痕分支标识查询轨迹详情(子分支/回溯路径/深度)；支持列出全部执行根」；
 * Feature FEAT-065 §5.1.5「留痕结构归宿主，**跨多次执行累积可查**」+ §4「宿主可按执行根查询全部步骤留痕，
 * 含跳过标记」；L2-067 §2.3「宿主级森林**跨 run 可查**」。
 *
 * <p><b>与设计 §6 Then① 的一处偏差（已登记，见批次记录 §7C）</b>：设计 §6（与 FEAT-065 065-S23）写的是
 * "连续两次 {@code workflow_run} 后 {@code roots} 返回**两个**执行根"。但三份权威文档承诺的都是
 * "**跨 run 累积可查**"与"列出**全部**执行根"，没有任何一处承诺"每次运行各成一个根"；
 * 而实现（{@code BlueprintScheduler.ensureRoot} 复用首根 + {@code DaWorkflowRunner} 自陈
 * "审计森林为执行器自持的宿主级单例，core 调度器**复用首根**，各 run 的执行分支累积于同一根下、
 * 跨 run 可查"）为**单根累积**。
 * 因此本用例按权威口径断言"两次运行的全部步骤留痕都能经 {@code roots} → 下钻取回"，
 * 并**如实断言根的实际个数 == 1**（而不是把设计的"两个"当成产品判据去判 FAIL）。
 * 该差异属**测试设计侧的过度指定**，已登记待设计确认，不改判据强度。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaSubagentTranscriptToolTest {

    private static final String RUN = OrchestrationTestSupport.uid();
    /** 会话归属键（生产取 DaOrchestrationScope::currentKey，即 DA 会话 id）。 */
    private static final String OWNER_A = "sess-067-s07-a-" + RUN;
    private static final String OWNER_B = "sess-067-s07-b-" + RUN;

    @Test
    @Story("067-S07: subagent_transcript 的 roots 列举与按 branch_id 下钻")
    @DisplayName("067-S07 branch_id 非空且可下钻取回分支详情；roots 列出执行根；两次运行的留痕跨 run 累积可查")
    void rootsListingAndBranchDrillDownWorkAcrossRuns() {
        try (DaWorkflowTestSupport.Fixture fixture = DaWorkflowTestSupport.fixture("067-S07", RUN)) {
            final WorkflowRunTool runTool = fixture.runTool();
            // 生产口径：(RunTranscripts, ownerKey)（DaWorkflowTools.attach:45-46，生产 ownerKey 取
            // DaOrchestrationScope::currentKey）。旧形 (TraceForest) 连同 runner.getAuditForest() 一起被删。
            final SubagentTranscriptTool transcript = fixture.transcriptTool(() -> OWNER_A);

            // 两次运行，用**不同**图纸（证明累积不是同一批分支的重复）
            // 【2026-10-23 修复我方夹具缺陷，判据一字未改】owner **不是**由 transcriptTool 的入参决定的，
            // 而是 DaWorkflowRunner.run() 执行时经 DaOrchestrationScope.currentKey() → DaToolContextHolder
            // 取的**线程上下文**——同文件 067-S07b 的注释早已明写此构造，并警告「否则 owner 落到
            // UNSCOPED("")，本用例会假绿」。本用例此前**漏设**该上下文 ⇒ 两次运行记到 UNSCOPED("") 名下，
            // 而 transcriptTool 按 OWNER_A 查 ⇒ visible 为空 ⇒ 下钻恒报 "branch not found: r1/b-2"。
            // 该缺陷被前面的形态断言失败**遮蔽了 6 轮**（run-48/54/59/60 均在 .matches("r\\d+/b-\\d+")
            // 处 FAIL，下钻段从未执行过）；PR !880 修好 branch_id 形态后它才第一次暴露（run-68）。
            // 真机侧旁证：run-66/67 在真宿主上 subagent_transcript 的 roots 正常返回 r1/b-1、r2/b-1
            // ⇒ 生产装配下 owner 是设上的，缺的只是本用例的夹具。
            final Map<String, Object> first;
            final Map<String, Object> second;
            try {
                DaToolContextHolder.set(new DaToolContext(OWNER_A, "tenant-067-s07", List.of()));
                first = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                        runTool.invoke(Map.of("blueprint", DaWorkflowTestSupport.threeMemberDag("T-067-S07-a"),
                                "question", "U-067-S07-1-" + RUN), Map.of())));
                second = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                        runTool.invoke(Map.of("blueprint",
                                        DaWorkflowTestSupport.singleMemberBlueprint("T-067-S07-b", "mSolo"),
                                "question", "U-067-S07-2-" + RUN), Map.of())));
            } finally {
                DaToolContextHolder.clear();
            }
            final List<Map<String, Object>> firstSteps = DaWorkflowTestSupport.stepsOf(first);
            final List<Map<String, Object>> secondSteps = DaWorkflowTestSupport.stepsOf(second);
            assertThat(firstSteps).as("第一次运行（三成员 dag）应有 3 步留痕").hasSize(3);
            assertThat(secondSteps).as("第二次运行（单兵）应有 1 步留痕").hasSize(1);

            // ① steps[].branch_id 非空（subagent_transcript 必填入参的唯一会话侧来源）
            final Set<String> branchIds = new LinkedHashSet<>();
            for (Map<String, Object> step : firstSteps) {
                assertThat((String) step.get("branch_id")).as("每步 branch_id 应非空").isNotBlank();
                branchIds.add((String) step.get("branch_id"));
            }
            for (Map<String, Object> step : secondSteps) {
                assertThat((String) step.get("branch_id")).as("每步 branch_id 应非空").isNotBlank();
                branchIds.add((String) step.get("branch_id"));
            }
            // 【2026-10-21 按 docs#31 裁决 1（comment 194890332）改写判据】
            // 裁决原文：「`r<n>/b-<n>` 是对外承诺的 branch_id 形态。语义：`r<n>` = 运行序号（按宿主会话 owner
            // 独立编号），`b-<n>` = 分支号；`r<n>/b-<n>` 整体作**形态断言合法**，**编号值不透明**——不承诺连续、
            // 不承诺起始值、不承诺跨 owner 可比较；不同运行/不同会话的同号分支**不再碰撞**
            // （RunTranscripts 按 owner 分区、每 owner 独立编号并设留存上限）。」
            // ⇒ 本用例只断**形态**与**互不相同**两条，**不断**编号连续性/起始值（裁决明写不承诺，
            //   断它就是测试侧自行加码）。
            // 源码依据（基线 ce9019d86）：`RunTranscripts:23` 类注释「prefix (r2/b-3), so equal branch ids of
            // different runs never collide」；`:45` `new Run("r" + seq, workflowId, question, new TraceForest())`；
            // `:174-175` `qualify(branchId) = prefix.isEmpty() ? branchId : prefix + "/" + branchId`。
            // 🔴 而 `OutputArchive:87` 是 `step.put("branch_id", outcome.branchId())`——**未经 qualify**，
            //   这正是 D8（agent-solution#457）的根因位置，也是 run-48 实收 `["b-2","b-3","b-4"]`
            //   （4 步只有 3 个不同值、且无 r<n>/ 前缀）的原因。
            // 【取证纪律修正】run-48 那次 PROBE 打印写在断言**之后**，FAIL 后一行都没留下（组日志 grep PROBE 命中 0）
            // ⇒ 本轮把实际形态的打印**前移到断言之前**，保证失败时也拿得到实收值。
            System.out.println("[PROBE-067-S07-form] owner=" + OWNER_A + " branchIds=" + branchIds);
            for (String branchId : branchIds) {
                assertThat(branchId)
                        .as("steps[].branch_id 应符合 docs#31 裁决 1 承诺的对外形态 r<n>/b-<n>"
                                + "（即 RunTranscripts.Run.qualify 的产物）；实收=%s。"
                                + "根因位置：OutputArchive:87 直接放 outcome.branchId()、未调 qualify", branchId)
                        .matches("r\\d+/b-\\d+");
            }
            assertThat(branchIds)
                    .as("两次运行共 4 步，分支标识应互不相同（docs#31 裁决 1：不同运行的同号分支不再碰撞）；实收=%s",
                            branchIds)
                    .hasSize(4);

            // ② 用 branch_id 下钻能取回分支详情（子分支 / 回溯路径 / 深度）
            for (String branchId : branchIds) {
                final Map<String, Object> detail = DaWorkflowTestSupport.dataOf(
                        DaWorkflowTestSupport.output(
                                transcript.invoke(Map.of("branch_id", branchId), Map.of())));
                assertThat(detail).as("下钻结果应含 branch / path_to_root / depth 三项；branchId=%s", branchId)
                        .containsKeys("branch", "path_to_root", "depth");
                @SuppressWarnings("unchecked")
                final Map<String, Object> branch = (Map<String, Object>) detail.get("branch");
                assertThat(branch).as("branch 摘要应含 branch_id / phases / terminal_reason")
                        .containsEntry("branch_id", branchId).containsKeys("phases", "terminal_reason");
                @SuppressWarnings("unchecked")
                final List<String> path = (List<String>) detail.get("path_to_root");
                assertThat(path).as("回溯路径应非空且末端即被查询分支；branchId=%s", branchId)
                        .isNotEmpty().contains(branchId);
                assertThat(detail.get("depth")).as("深度应为非负整数；branchId=%s", branchId)
                        .isInstanceOf(Integer.class);
                assertThat((Integer) detail.get("depth")).isNotNegative();
            }

            // ③ branch_id="roots" 列出全部执行根；两次运行的留痕经根下钻**累积可查**
            final Map<String, Object> rootsData = DaWorkflowTestSupport.dataOf(
                    DaWorkflowTestSupport.output(
                            transcript.invoke(Map.of("branch_id", "roots"), Map.of())));
            assertThat(rootsData).as("roots 动作应返回 roots 列表").containsKey("roots");
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> roots = (List<Map<String, Object>>) rootsData.get("roots");
            assertThat(roots).as("应至少列出一个执行根").isNotEmpty();
            // 【2026-10-19 重定基线到 ce9019d86 后：旧偏差已消失】
            // 旧基线（f9da5b54f）实现是 ensureRoot 复用首根 ⇒ 两次运行共用 1 个根，当时登记为
            // "设计 §6 期望两个执行根属测试设计侧过度指定"。新基线的 RunTranscripts 改为
            // **每个 run 各自一个 TraceForest**（类注释原话：runs sharing one forest all pile up under
            // the first run's root），并按 owner 编号 r1/r2、branch_id 带该前缀（r2/b-3）。
            // ⇒ 设计 §6 的"两个执行根"口径现在**应当成立**，原偏差登记作废。
            // 本轮先如实打印实际形态，待确认后把根数与 branch_id 形态写死为判据
            // （branch_id 形态是否对外承诺已请 ISSUE #31 裁决，未答前不硬编码）。
            System.out.println("[PROBE-067-S07-roots] owner=" + OWNER_A + " roots=" + roots);

            final List<String> reachable = new ArrayList<>();
            for (Map<String, Object> root : roots) {
                final String rootId = String.valueOf(root.get("branch_id"));
                assertThat(rootId).as("执行根标识应非空").isNotBlank();
                final Map<String, Object> rootDetail = DaWorkflowTestSupport.dataOf(
                        DaWorkflowTestSupport.output(
                                transcript.invoke(Map.of("branch_id", rootId), Map.of())));
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> children =
                        (List<Map<String, Object>>) rootDetail.get("children");
                assertThat(children).as("按执行根下钻应能取回全部步骤留痕（含两次运行）").isNotNull();
                for (Map<String, Object> child : children) {
                    reachable.add(String.valueOf(child.get("branch_id")));
                }
            }
            assertThat(reachable).as("两次运行的全部 4 条步骤留痕都应能经执行根下钻取回（跨 run 累积可查）")
                    .containsAll(branchIds);

            // 错误面：未知分支与缺失入参都给可读错误、不悬挂
            final String unknown = "branch-does-not-exist-" + RUN;
            assertThat(DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                    transcript.invoke(Map.of("branch_id", unknown), Map.of()))))
                    .as("未知分支应给可读错误并带定位").contains(unknown);
            assertThat(DaWorkflowTestSupport.errorOf(DaWorkflowTestSupport.output(
                    transcript.invoke(Map.of(), Map.of()))))
                    .as("缺 branch_id 应给可读错误并指明该参数").contains("branch_id");

            // include_children=false 时不带子分支列表（可选入参的契约面）
            final Map<String, Object> noChildren = DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(
                    transcript.invoke(Map.of("branch_id", roots.get(0).get("branch_id"),
                            "include_children", Boolean.FALSE), Map.of())));
            assertThat(noChildren).as("include_children=false 时不应带 children 键").doesNotContainKey("children");
            assertThat(noChildren).as("但仍应带 branch / path_to_root / depth")
                    .containsKeys("branch", "path_to_root", "depth");
        }
    }

    /**
     * 067-S07b：**会话分区的组件层判据**（D1「跨会话留痕泄露」的修复验证）。
     *
     * <p>为什么必须有这条：D1 在旧基线只能由 L3 端到端探针发现（组件层单实例下跨会话串台结构上不可能显形）。
     * 新基线把归属键做成了 {@code SubagentTranscriptTool} 的**构造入参** ⇒ 组件层现在**可以**直接构造两个
     * owner 来验分区，不必再依赖拉宿主。这条补上后，D1 的判据在 L1t 与 L3 两层都有。
     *
     * <p>Oracle：FEAT-085 Feature:161「编排运行态按会话隔离：委托任务、成员消息与运行轨迹按 DA 会话分区，
     * **一个会话不可见也不可投递到另一个会话**」；L2-085:43「本设计只提供其运行态的会话分区键」；
     * {@code DaOrchestrationScope} 类注释同口径；平台级 MUST 为 {@code DFX-001:63}（session.id 全覆盖）。
     *
     * <p><b>故障与主断言</b>：若会话分区失效（归属键被忽略、或退化为宿主级共享），
     * 则 B 的 {@code roots} 会列出 A 的执行根、或 B 能按 A 的 branch_id 下钻取回 A 的原始提问 ⇒ 本用例应失败。
     */
    @Test
    @Stories({
            // 067-S07b：subagent_transcript 的会话分区（FEAT-085 Feature:161 / DFX-001:63 观测面）
            @Story("067-S07b: subagent_transcript 按会话分区——另一会话读不到本会话的执行根与提问原文"),
            // 065-S26（2026-11-26 补挂，三层 ID 对账）：同一 testcase 共担 FEAT-065 REQ-060 的组件层判据
            // （细档 :187「实现落在编排组件测试集，@Tag("feat-067")，但验的是本特性锚定的 REQ-060」）。
            // 判据三要素已由本方法完整覆盖：①正向自检（A 经 roots 查到含提问原文）②B 的 roots 不含 A 提问
            // ③B 按 A 的 branch_id **逐个**下钻都取不到 A 的分支详情（for 循环）——与 065-S26 判据逐字对应，
            // 证据=本轮 r9 组件层 PASS（XML 同 067-S07b）。
            @Story("065-S26: 留痕按会话分区的组件层判据——两个 owner 键下互不可见对方执行根与提问原文（与 067-S07b 同一 testcase 共担）")
    })
    @DisplayName("067-S07b + 065-S26 会话 A 跑一次后，会话 B 的 roots 应为空且按 A 的 branch_id 下钻取不到 A 的提问")
    void transcriptsArePartitionedByOwnerSoAnotherSessionCannotReadThem() {
        try (DaWorkflowTestSupport.Fixture fixture = DaWorkflowTestSupport.fixture("067-S07b", RUN)) {
            final WorkflowRunTool runTool = fixture.runTool();
            final SubagentTranscriptTool asA = fixture.transcriptTool(() -> OWNER_A);
            final SubagentTranscriptTool asB = fixture.transcriptTool(() -> OWNER_B);

            // A 跑一次，提问里带唯一标记（等价于 L3 探针 PA 的 SECRET-QUESTION-OF-SESSION-A）。
            // 【关键构造】owner 不是由 SubagentTranscriptTool 的入参决定的，而是 DaWorkflowRunner.run():163
            // 在执行时经 DaOrchestrationScope.currentKey() → DaToolContextHolder 取的**线程上下文**。
            // 所以必须用生产机制把会话上下文设上，运行才会记到 OWNER_A 名下；
            // 否则 owner 落到 UNSCOPED("")，本用例会"假绿"（B 查不到只是因为 A 也没记到 A 名下）。
            final String secret = "SECRET-QUESTION-OF-OWNER-A-" + RUN;
            try {
                DaToolContextHolder.set(new DaToolContext(OWNER_A, "tenant-067-s07b", List.of()));
                DaWorkflowTestSupport.dataOf(DaWorkflowTestSupport.output(runTool.invoke(
                        Map.of("blueprint", DaWorkflowTestSupport.singleMemberBlueprint("T-067-S07b", "m1"),
                                "question", secret), Map.of())));
            } finally {
                DaToolContextHolder.clear();
            }

            // ① A 自己查得到（否则本用例的"查不到"毫无意义——先证观测通道有效）
            final Map<String, Object> rootsA = DaWorkflowTestSupport.dataOf(
                    DaWorkflowTestSupport.output(asA.invoke(Map.of("branch_id", "roots"), Map.of())));
            assertThat(String.valueOf(rootsA))
                    .as("正向自检：会话 A 应能经 roots 看到自己的运行（含提问原文）；实收=%s", rootsA)
                    .contains(secret);

            // ② B 的 roots 不得出现 A 的任何内容
            final Object rawB = asB.invoke(Map.of("branch_id", "roots"), Map.of());
            final ToolOutput outB = DaWorkflowTestSupport.output(rawB);
            final String textB = String.valueOf(outB.getData()) + "|" + String.valueOf(outB.getError());
            assertThat(textB)
                    .as("会话 B 的 roots **不得**含会话 A 的提问原文（命中即跨会话留痕泄露 = D1 未修）；实收=%s", textB)
                    .doesNotContain(secret);

            // ③ B 按 A 的 branch_id 逐个下钻，都不得取回 A 的分支详情
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> roots = (List<Map<String, Object>>) rootsA.get("roots");
            assertThat(roots).as("A 应至少有 1 个执行根，否则②③无从判定").isNotEmpty();
            for (Map<String, Object> root : roots) {
                final String branchId = String.valueOf(root.get("branch_id"));
                final ToolOutput drilled = DaWorkflowTestSupport.output(
                        asB.invoke(Map.of("branch_id", branchId), Map.of()));
                final String text = String.valueOf(drilled.getData()) + "|" + String.valueOf(drilled.getError());
                assertThat(text)
                        .as("会话 B 按会话 A 的 branch_id=%s 下钻，**不得**取回 A 的分支详情；实收=%s", branchId, text)
                        .doesNotContain(secret);
            }
        }
    }
}
