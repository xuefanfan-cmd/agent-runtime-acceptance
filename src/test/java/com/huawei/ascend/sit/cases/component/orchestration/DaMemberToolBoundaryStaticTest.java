/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S28 / 067-S29：成员与委托执行体的**工具边界**（静态合同核对）。
 *
 * <p><b>Oracle 来源（2026-10-27 增量审视新增，docs {@code main@65118fda}／{@code !379}）</b>：
 * <ul>
 *   <li>{@code FEAT-067-orchestration-tool-trio.md:50}（**MUST**「成员与委托只读工具」）：「按发起方会话范围挂载
 *       **14 件只读工具**:read_file/grep/glob/list_files/notebook_read/kb_search/doc_grep/expand/web_search/
 *       wikipedia/wiki_browse/scholar_search/pdf_read/run_sql;run_sql 的只读性由平台 SELECT/WITH 限制与只读事务保证;
 *       **无 caller 会话上下文时不挂工具**,联网开关与知识库范围继续收敛实际工具集」</li>
 *   <li>{@code FEAT-067:51}（**MUST NOT**「写工具与编排六件」）：「成员与委托执行体**不得挂**
 *       write_file/edit_file/bash/powershell,也**不得挂** workflow_run/workflow_status/delegate_task/
 *       task_output/send_message/subagent_transcript,避免越权写入与递归编排」</li>
 *   <li>{@code Feat-Func-067-orchestration-tool-trio.md:52}（落点）：「工作流/委托成员只挂 14 件只读工具,不挂写工具、
 *       执行沙箱工具与编排六件;无 caller 会话上下文时不挂工具…(DaWorkflowRunner.java:58-64/:234-256)」；
 *       {@code :195} 验收账本另记「30 轮实现级安全上限」</li>
 * </ul>
 *
 * <p><b>历史（必须在判据里显式钉住，否则会误判）</b>：这两条条款是 {@code !379}（2026-10-27）才写进文档的，
 * 此前 {@code FEAT-067:50} 是 {@code OUT}「工作流成员默认无工具(裸执行体);成员工具子集挂载待裁决」，
 * 本批《变更包范围卡》§4 据此把它判为范围外。而实现侧 {@code MemberAgentEquipment}（"mounts registered tools"）
 * 由 {@code 0f9d51173}（2026-10-15）引入，在本批转测基线 {@code f9da5b54f} 里**不存在**、自 {@code ce9019d86}
 * （r3，2026-10-19）起进入后续全部基线 ⇒ **文档滞后实现六周**，归属与生效时点见 {@code Technical-AF/docs#40}。
 *
 * <p>🔴 **清单本身在批次执行期内变过**：{@code MEMBER_TOOLS} 在 r3 {@code ce9019d86} 与 r4 {@code cf4571b30}
 * 上是 **13 件**（无 {@code run_sql}），{@code run_sql} 由 {@code 3921165f4}（2026-10-23）加入，r6
 * {@code 027c73f41} 与最新 {@code abe44f646} 才是 **14 件**。⇒ 本用例的"14 件"判据**自 r5/r6 起适用**；
 * 若在 r4 及更早制品上跑必然 FAIL，那不是产品缺陷而是基线早于条款生效点。
 *
 * <p><b>为什么是 static 形态</b>：本条判的是"声明的工具清单与红线"，属设计本来只要求静态核对的条目
 * （{@code required_form=static}），静态断言即充分、不属降级。运行期"确实挂上了、且只用清单内的工具"
 * 由 067-S14（REQ-057，L3 端到端）与 067-S30（REQ-056，范围收敛）分别承接。
 *
 * <p><b>源码定位方式</b>同 065-S21：① 系统属性 {@code deepanalyze.source.root} ② 环境变量
 * {@code DEEPANALYZE_SOURCE_ROOT}；都缺时以 {@link Assumptions} 中止（SKIPPED ⇒ INCONCLUSIVE，
 * **不判产品 FAIL**，也不假装通过）。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 会话内编排工具组")
class DaMemberToolBoundaryStaticTest {

    private static final String RUNNER_SOURCE = "com/openjiuwen/da/workflow/DaWorkflowRunner.java";

    /** FEAT-067:50（MUST）逐字列出的 14 件只读工具，顺序按文档原文。 */
    private static final List<String> DOC_READ_ONLY_TOOLS = List.of(
            "read_file", "grep", "glob", "list_files", "notebook_read",
            "kb_search", "doc_grep", "expand", "web_search", "wikipedia",
            "wiki_browse", "scholar_search", "pdf_read", "run_sql");

    /** FEAT-067:51（MUST NOT）第一组：写工具与执行沙箱工具。 */
    private static final List<String> FORBIDDEN_WRITE_TOOLS =
            List.of("write_file", "edit_file", "bash", "powershell");

    /** FEAT-067:51（MUST NOT）第二组：编排六件（防递归编排）。 */
    private static final List<String> FORBIDDEN_ORCHESTRATION_TOOLS = List.of(
            "workflow_run", "workflow_status", "delegate_task", "task_output",
            "send_message", "subagent_transcript");

    /** L2-067:195 记载的"30 轮实现级安全上限"。 */
    private static final int DOC_MEMBER_MAX_ITERATIONS = 30;

    private static final Pattern MEMBER_TOOLS = Pattern.compile(
            "MEMBER_TOOLS\\s*=\\s*List\\.of\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern MAX_ITERATIONS = Pattern.compile(
            "MEMBER_MAX_ITERATIONS\\s*=\\s*(\\d+)");

    @Test
    @Story("067-S28: 成员只读工具集 == 文档 14 件，且与 MUST NOT 的 10 件互斥")
    @DisplayName("067-S28 MEMBER_TOOLS 逐字等于 FEAT-067:50 的 14 件只读工具，且不含写工具/执行沙箱工具/编排六件；MEMBER_MAX_ITERATIONS=30")
    void memberToolSetMatchesDocAndExcludesWriteAndOrchestrationTools() throws IOException {
        final String text = readRunnerSource();

        // ---- ① 取出实现侧声明的工具清单 ----
        final Matcher m = MEMBER_TOOLS.matcher(text);
        assertThat(m.find())
                .as("应能在 DaWorkflowRunner.java 里定位 `MEMBER_TOOLS = List.of(...)`（L2-067:52 点名的落点）").isTrue();
        final List<String> declared = new ArrayList<>();
        final Matcher lit = STRING_LITERAL.matcher(m.group(1));
        while (lit.find()) {
            declared.add(lit.group(1));
        }
        Allure.addAttachment("067-S28 MEMBER_TOOLS 实现侧原文与解析结果", "text/plain",
                "原文=" + m.group(0) + "\n解析出 " + declared.size() + " 件=" + declared
                        + "\n文档（FEAT-067:50@main@65118fda）14 件=" + DOC_READ_ONLY_TOOLS);

        // ---- ② 主断言：与文档 14 件逐字一致（不许多、不许少、不许改名） ----
        assertThat(declared)
                .as("成员只读工具集应与 FEAT-067:50（MUST）列出的 14 件**逐字一致**；"
                        + "多一件＝越过承诺面，少一件＝承诺未兑现，改名＝文档与实现漂移")
                .containsExactlyInAnyOrderElementsOf(DOC_READ_ONLY_TOOLS);
        assertThat(new LinkedHashSet<>(declared))
                .as("清单内不应有重复项（重复会让「件数」与「覆盖面」两个口径打架）")
                .hasSize(DOC_READ_ONLY_TOOLS.size());

        // ---- ③ MUST NOT 红线：写工具/执行沙箱工具 + 编排六件，一件都不许出现 ----
        final List<String> forbidden = new ArrayList<>(FORBIDDEN_WRITE_TOOLS);
        forbidden.addAll(FORBIDDEN_ORCHESTRATION_TOOLS);
        assertThat(declared)
                .as("FEAT-067:51（MUST NOT）：成员与委托执行体不得挂写工具/执行沙箱工具（%s），"
                        + "也不得挂编排六件（%s）——前者防越权写入，后者防递归编排",
                        FORBIDDEN_WRITE_TOOLS, FORBIDDEN_ORCHESTRATION_TOOLS)
                .doesNotContainAnyElementsOf(forbidden);

        // ---- ④ 全文件级红线：MEMBER_TOOLS 之外也不许给成员/委托另挂禁用工具 ----
        // 只查"挂载点"附近，避免把注释或工具卡常量误判为挂载。
        final List<String> mountLeaks = new ArrayList<>();
        for (String tool : forbidden) {
            final Matcher mm = Pattern.compile("(MEMBER_TOOLS|memberExecutor|MemberAgentEquipment\\.equip)[^;]*\""
                    + Pattern.quote(tool) + "\"", Pattern.DOTALL).matcher(text);
            if (mm.find()) {
                mountLeaks.add(tool);
            }
        }
        assertThat(mountLeaks)
                .as("MUST NOT 红线不止于清单本身：成员/委托的**挂载路径**上也不得出现这 10 件；命中=%s", mountLeaks)
                .isEmpty();

        // ---- ⑤ 迭代上限（L2-067:195「30 轮实现级安全上限」） ----
        // 【2026-11-24 r9 适配】r9 基线（common@babb882b9）把成员 ReAct 轮次上限从源码常量
        // MEMBER_MAX_ITERATIONS 改为**配置键** deepanalyze.workflow.member-max-iterations
        // （DaWorkflowRunner:860 `config.setMaxIterations(properties.getWorkflow().getMemberMaxIterations())`，
        // :73 注释明写「默认 30」）——判据实质（30 轮上限与 L2 一致）不变，断言落点跟随实现演进：
        // 主断言走产品公开 API（配置默认值），源码正则降级为"常量形态"的兼容证据（存在才核对，不存在不 FAIL）。
        assertThat(new com.openjiuwen.da.config.DaProperties().getWorkflow().getMemberMaxIterations())
                .as("成员 ReAct 轮次上限的配置默认值应与 L2-067:195 记载的 %d 一致"
                        + "（deepanalyze.workflow.member-max-iterations，DaWorkflowRunner 从 DaProperties 供值）",
                        DOC_MEMBER_MAX_ITERATIONS)
                .isEqualTo(DOC_MEMBER_MAX_ITERATIONS);
        assertThat(readRunnerSource())
                .as("配置键必须真的接到成员轮次：DaWorkflowRunner 源码应引用 getMemberMaxIterations()"
                        + "（否则该键是死的——配置写了也不生效）")
                .contains("getMemberMaxIterations()");
        final Matcher im = MAX_ITERATIONS.matcher(text);
        if (im.find()) {
            // 常量形态（r8 及更早基线）在场时兼容核对；r9 起常量已删除，此增强证据自然缺席
            assertThat(Integer.parseInt(im.group(1)))
                    .as("若源码仍保留 MEMBER_MAX_ITERATIONS 常量，其值应与 L2 一致（%d）", DOC_MEMBER_MAX_ITERATIONS)
                    .isEqualTo(DOC_MEMBER_MAX_ITERATIONS);
        }
    }

    @Test
    @Story("067-S29: 无 caller 会话上下文时不挂工具（工具集为空）")
    @DisplayName("067-S29 取不到发起方会话上下文时成员工具集为空（orElse(List.of())），且范围收敛只在 caller 存在时发生")
    void noCallerContextMeansNoToolsMounted() throws IOException {
        final String text = readRunnerSource();

        // ---- ① 无 caller ⇒ 空工具集：必须是 orElse(List.of())，不能是 orElse(tools) 之类 ----
        // 不用一条长正则去匹配整个链式表达式（嵌套括号会让正则既脆又难读）：改为**先取出那条语句**再分项断言，
        // 这样即使实现调整换行/参数顺序，判据仍然精确指向"无上下文时给空集"这一语义。
        final String assignStatement = extractStatement(text, "caller.map(");
        assertThat(assignStatement)
                .as("应能定位成员工具集的赋值语句（含 `caller.map(`）——FEAT-067:50 后半句与 L2-067:52 的落点")
                .isNotBlank();
        assertThat(assignStatement)
                .as("FEAT-067:50 后半句「**无 caller 会话上下文时不挂工具**」要求：取不到 DaToolContext 时工具集必须是"
                        + " `List.of()`（空），即 `caller.map(context -> scopedTools(...)).orElse(List.of())`。"
                        + "若写成 orElse(tools) 就会在无会话上下文时把全量 14 件挂上去，越过会话隔离。"
                        + "\n实收语句=%s", assignStatement)
                .contains("scopedTools(")
                .contains(".orElse(List.of())");
        assertThat(assignStatement)
                .as("该语句不得出现 orElse(tools)/orElseGet 之类把原始清单兜底挂上的写法；实收=%s", assignStatement)
                .doesNotContain("orElse(tools")
                .doesNotContain("orElseGet");
        Allure.addAttachment("067-S29 无 caller ⇒ 空工具集：被断言的那条语句原文", "text/plain", assignStatement);

        // ---- ② caller 的来源必须是 DaToolContextHolder（发起方会话在工具线程上取） ----
        assertThat(text)
                .as("caller 应来自 DaToolContextHolder.get()——成员跑在调度器线程池里，取不到就只能判无上下文")
                .contains("DaToolContextHolder.get()");

        // ---- ③ scopedTools 只应有一处调用点，且就在上面那个 map 里（不得有绕过收敛的旁路挂载） ----
        final int callSites = countMatches(text, "scopedTools(");
        assertThat(callSites)
                .as("scopedTools 的出现次数应为 2（1 处方法定义 + 1 处调用，且调用点在 caller.map 内）；"
                        + "实收 %d 次 ⇒ 出现旁路挂载或多处收敛口径不一致", callSites)
                .isEqualTo(2);

        // ---- ④ 挂载入口只认收敛后的集合（不得把未收敛的 tools 直接交给 equip） ----
        final Pattern equip = Pattern.compile("MemberAgentEquipment\\.equip\\([^;]*?\\);", Pattern.DOTALL);
        final Matcher em = equip.matcher(text);
        final List<String> equipCalls = new ArrayList<>();
        while (em.find()) {
            equipCalls.add(em.group(0).replaceAll("\\s+", " "));
        }
        assertThat(equipCalls).as("应能定位成员装备入口 MemberAgentEquipment.equip(...)").isNotEmpty();
        for (String call : equipCalls) {
            assertThat(call)
                    .as("装备入口必须传**收敛后**的集合（scoped），不得直传原始 tools；实收调用=%s", call)
                    .containsPattern("\\bscoped\\b");
        }
        Allure.addAttachment("067-S29 无 caller 不挂工具：源码证据", "text/plain",
                "equip 调用点 " + equipCalls.size() + " 处：\n" + String.join("\n", equipCalls)
                        + "\n\nscopedTools 出现次数=" + callSites + "（1 定义 + 1 调用）"
                        + "\ncaller 来源含 DaToolContextHolder.get()=" + text.contains("DaToolContextHolder.get()"));
    }

    private static String readRunnerSource() throws IOException {
        final Path root = resolveSourceRoot();
        final Path runner = root.resolve(RUNNER_SOURCE);
        assertThat(Files.isRegularFile(runner)).as("被测源码应存在：%s", runner).isTrue();
        final String text = Files.readString(runner, StandardCharsets.UTF_8);
        assertThat(text).as("被测源码不应为空：%s", runner).isNotBlank();
        return text;
    }

    /**
     * 取出包含 {@code marker} 的**那一条语句**（从上一个 `;` 或 `{` 之后起，到下一个 `;` 为止），
     * 并把连续空白压成单空格，便于做稳定断言。找不到时返回空串（由调用方断言 isNotBlank 报可读原因）。
     */
    private static String extractStatement(final String text, final String marker) {
        final int at = text.indexOf(marker);
        if (at < 0) {
            return "";
        }
        int start = at;
        while (start > 0 && text.charAt(start - 1) != ';' && text.charAt(start - 1) != '{') {
            start--;
        }
        int end = text.indexOf(';', at);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end + 1).replaceAll("\\s+", " ").trim();
    }

    private static int countMatches(final String haystack, final String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            final int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    private static Path resolveSourceRoot() {
        final String fromProperty = System.getProperty("deepanalyze.source.root");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Path.of(fromProperty.trim());
        }
        final String fromEnv = System.getenv("DEEPANALYZE_SOURCE_ROOT");
        Assumptions.assumeTrue(fromEnv != null && !fromEnv.isBlank(),
                "未提供被测源码根：请加 -Ddeepanalyze.source.root=<deepanalyze-java/engine 的 src/main/java 绝对路径>，"
                        + "或设环境变量 DEEPANALYZE_SOURCE_ROOT。缺它时本用例记 SKIPPED（结论 INCONCLUSIVE），不判产品 FAIL。");
        return Path.of(fromEnv.trim());
    }
}
