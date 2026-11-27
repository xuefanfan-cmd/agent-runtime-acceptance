/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.da.config.DaGuardrailAssembler;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 067-S19（补）：**DA 宿主自己的护栏配置面**——`DaGuardrailAssembler` 的读取、校验与合并取严。
 *
 * <p><b>作用域更正（重要，2026-10-17 查清）</b>：`DFX-003` 的 front-matter 是 `module: agent-runtime`，
 * 其三条 MUST（安装幂等 / 安装失败一律抛出不降级 / 总开关关闭时护栏链整体不安装）的主语都是
 * 「**agent-runtime 扩展层**」；而本期被测面是 **DA 宿主**（`deepanalyze-java`），它走的是自己的装配路径：
 * `DaEngineConfiguration.buildPermissions(:537-550)` → `DaGuardrailAssembler.translate/mergeStricter`
 * → `DeepAgentConfig.builder().permissions(snapshot)` → core 的
 * `ToolGuardrail.install(DeepAgent, ToolGuardrailOptions)`。
 * ⇒ **那三条 MUST 的义务主体不是本期 SUT**，把它们当本期判据去断言属作用域错判
 * （与本批 D7 的作用域问题同类）。护栏**安装语义**（幂等、失败不降级）归 core 的
 * `agent-core-ext-tool-guardrail` / DFX-004，不在本变更包范围内。
 *
 * <p><b>本类测的是确属本期 SUT 的那一段</b>：宿主把 `governance/actrule.yaml` 翻译成权限快照的过程。
 * 判据取自 DFX-003「护栏配置读取与校验」的**同族口径**（该行的义务主体虽是 agent-runtime，
 * 但 DA 宿主实现了同一职责，且 FEAT-067 §2 明写「六件在护栏规则中**显式放行**；新增编排工具必须同步护栏登记」
 * ——登记能否被正确读取与校验，正是本类的对象）：
 * ① 解析失败 / 空文档 / 级别取值非法 → **一律抛出**并带来源名与键定位，
 *    **不接受"键名写错等于未配置"的静默失效**；
 * ② `enabled` / `tools` / `defaults` 三段被完整搬运（不得丢键、不得改值）；
 * ③ 多来源合并**取严**（`deny &gt; ask &gt; allow`），`enabled` 取**或**。
 *
 * <p>与已有用例的分工：067-S12 核对 actrule 的**静态登记内容**（六件均 allow、defaults fail-closed）；
 * 067-S19 的 L3 用例核对**运行期决策**与静态登记逐件一致；本类核对**中间的翻译与合并环节**——
 * 三者串起来才是"登记 → 翻译 → 运行期决策"的完整链路，任一环静默失真都会让前两者失去意义。
 */
@Tag("component")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaGuardrailAssemblerContractTest {

    private static final String SIX_TOOLS_ACTRULE = """
            enabled: true
            defaults:
              "*": ask
            tools:
              workflow_run: allow
              delegate_task: allow
              task_output: allow
              send_message: allow
              subagent_transcript: allow
              workflow_status: allow
              delete_file: deny
            """;

    private final DaGuardrailAssembler assembler = new DaGuardrailAssembler();

    @Test
    @Story("067-S19: 护栏配置读取与校验（DA 宿主侧）")
    @DisplayName("067-S19① actrule 翻译：enabled/tools/defaults 三段完整搬运，六件级别逐件正确")
    void translateCarriesAllThreeSectionsVerbatim() {
        final Map<String, Object> snapshot = assembler.translate(SIX_TOOLS_ACTRULE, "framework");

        assertThat(snapshot).as("权限快照应含 enabled / tools / defaults 三段").containsKeys("enabled", "tools", "defaults");
        assertThat(snapshot.get("enabled")).as("enabled 应原样搬运为布尔 true").isEqualTo(Boolean.TRUE);

        @SuppressWarnings("unchecked")
        final Map<String, Object> tools = (Map<String, Object>) snapshot.get("tools");
        assertThat(tools).as("tools 段应完整搬运（含非六件的 delete_file，不得挑着搬）").hasSize(7);
        for (String tool : new String[] {"workflow_run", "delegate_task", "task_output",
                "send_message", "subagent_transcript", "workflow_status"}) {
            assertThat(tools).as("六件之一 %s 应在快照里", tool).containsKey(tool);
            assertThat(tools.get(tool)).as("六件之一 %s 的级别应为 allow", tool).isEqualTo("allow");
        }
        assertThat(tools.get("delete_file")).as("deny 级条目也应原样搬运").isEqualTo("deny");

        @SuppressWarnings("unchecked")
        final Map<String, Object> defaults = (Map<String, Object>) snapshot.get("defaults");
        assertThat(defaults).as("defaults 段应保留通配 * 的 fail-closed 口径").containsEntry("*", "ask");
    }

    private record BadActrule(String label, String yaml, String expectedLocator) {
    }

    static Stream<Arguments> badActrules() {
        return Stream.of(
                new BadActrule("① YAML 语法错误", "enabled: true\ntools:\n  - a: b\n broken: [", "parse failed"),
                // 空文档在 **Jackson 解析阶段**就抛（"No content to map due to end-of-input"），
                // 走不到 translate 里的 `raw == null` → "actrule yaml empty" 那条分支
                // ⇒ 该分支对"空字符串"输入实际不可达（属实现里的一处死分支，已在批次记录登记为观察项，
                //   非缺陷：两种路径都抛出同一异常类型且都带来源名，行为一致）。
                new BadActrule("② 空文档", "", "No content to map"),
                new BadActrule("③ tools 级别取值非法（拼错的枚举）", """
                        enabled: true
                        tools:
                          workflow_run: permit
                        """, "workflow_run"),
                new BadActrule("④ defaults 级别取值非法", """
                        enabled: true
                        defaults:
                          "*": maybe
                        """, "defaults"),
                new BadActrule("⑤ 级别大小写变体（Allow）——按实现口径应被拒（枚举校验不容大小写）", """
                        enabled: true
                        tools:
                          workflow_run: Allow
                        """, "workflow_run"))
                .map(v -> Arguments.of(v.label(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badActrules")
    @Story("067-S19: 护栏配置读取与校验（DA 宿主侧）")
    @DisplayName("067-S19② 非法 actrule 一律抛出并带来源名与定位，不接受静默失效")
    void invalidActruleAlwaysThrowsWithLocator(final String label, final BadActrule bad) {
        final Throwable thrown = catchThrowableOfType(
                () -> assembler.translate(bad.yaml(), "scenario:probe"), Throwable.class);
        assertThat(thrown).as("%s：非法 actrule 必须抛出，不得静默降级为\"无护栏\"或\"未配置\"；"
                + "DFX-003 同族口径明写「不接受『键名写错等于未配置』的静默失效」", label).isNotNull();
        assertThat(thrown).as("%s：异常类型应是宿主的资产异常 DaAssetException；实收=%s",
                label, thrown.getClass().getName())
                .isInstanceOf(com.openjiuwen.da.assembly.DaAssetException.class);
        final String message = String.valueOf(thrown.getMessage());
        assertThat(message).as("%s：消息应带来源名（可追溯到是哪一份 actrule）；实收=[%s]", label, message)
                .contains("scenario:probe");
        if (!bad.expectedLocator().isBlank()) {
            assertThat(message).as("%s：消息应带定位 %s；实收=[%s]", label, bad.expectedLocator(), message)
                    .contains(bad.expectedLocator());
        }
        System.out.println("[067-S19②] " + label + " -> " + thrown.getClass().getSimpleName() + ": " + message);
    }

    @Test
    @Story("067-S19: 护栏配置读取与校验（DA 宿主侧）")
    @DisplayName("067-S19③ 多来源合并取严：deny > ask > allow，enabled 取或，键集合并集")
    void mergeStricterTakesTheStrictestLevel() {
        final Map<String, Object> framework = assembler.translate(SIX_TOOLS_ACTRULE, "framework");
        // 场景侧覆盖：把 workflow_run 收紧为 ask、把 task_output 收紧为 deny、新增一件工具
        final Map<String, Object> scenario = assembler.translate("""
                enabled: false
                tools:
                  workflow_run: ask
                  task_output: deny
                  kb_search: allow
                """, "scenario:tighten");

        final Map<String, Object> merged = assembler.mergeStricter(framework, scenario);

        @SuppressWarnings("unchecked")
        final Map<String, Object> tools = (Map<String, Object>) merged.get("tools");
        assertThat(tools.get("workflow_run")).as("allow 与 ask 合并应取更严的 ask").isEqualTo("ask");
        assertThat(tools.get("task_output")).as("allow 与 deny 合并应取最严的 deny").isEqualTo("deny");
        assertThat(tools.get("kb_search")).as("仅一侧声明的键应保留（并集）").isEqualTo("allow");
        assertThat(tools.get("delegate_task")).as("未被覆盖的键应保持原级别").isEqualTo("allow");
        assertThat(tools.get("delete_file")).as("deny 不应被任何一侧放宽").isEqualTo("deny");
        // 取严是不可逆的：反向合并（宽松在后）结果必须相同
        final Map<String, Object> mergedReversed = assembler.mergeStricter(scenario, framework);
        @SuppressWarnings("unchecked")
        final Map<String, Object> toolsReversed = (Map<String, Object>) mergedReversed.get("tools");
        assertThat(toolsReversed).as("取严合并应与顺序无关（否则可用加载顺序绕过治理）")
                .containsExactlyInAnyOrderEntriesOf(tools);
        // enabled 取或：任一侧开启即开启（不得因覆盖侧 false 而整体关闭护栏）
        assertThat(merged.get("enabled")).as("enabled 应取或：framework=true + scenario=false ⇒ true").isEqualTo(Boolean.TRUE);
        final Map<String, Object> bothOff = assembler.mergeStricter(
                assembler.translate("enabled: false\ntools: {}\n", "a"),
                assembler.translate("enabled: false\ntools: {}\n", "b"));
        assertThat(bothOff.get("enabled")).as("两侧都关时才应为 false").isEqualTo(Boolean.FALSE);
        // defaults 也参与合并取严
        final Map<String, Object> mergedDefaults = assembler.mergeStricter(framework,
                assembler.translate("enabled: true\ndefaults:\n  \"*\": deny\n", "scenario:deny-all"));
        @SuppressWarnings("unchecked")
        final Map<String, Object> defaults = (Map<String, Object>) mergedDefaults.get("defaults");
        assertThat(defaults.get("*")).as("defaults 的通配 * 也应取严（ask 与 deny ⇒ deny）").isEqualTo("deny");
    }

    @Test
    @Story("067-S19: 护栏配置读取与校验（DA 宿主侧）")
    @DisplayName("067-S19④ 总开关：enabled=false 被如实搬运（不被翻译成 true、也不丢键）")
    void masterSwitchFlagIsCarriedFaithfully() {
        final Map<String, Object> off = assembler.translate("""
                enabled: false
                defaults:
                  "*": ask
                tools:
                  workflow_run: allow
                """, "framework-off");
        assertThat(off.get("enabled")).as("enabled=false 必须如实搬运为 Boolean.FALSE"
                + "（若被翻成 true 即总开关失效；若丢键则下游按缺省处理，属静默失真）")
                .isEqualTo(Boolean.FALSE);
        @SuppressWarnings("unchecked")
        final Map<String, Object> tools = (Map<String, Object>) off.get("tools");
        assertThat(tools).as("总开关关闭时白名单内容仍应被完整搬运（是否生效由下游安装环节决定，"
                + "翻译层不得自行删改）").containsEntry("workflow_run", "allow");

        // 缺 enabled 键时按 false 处理（Boolean.TRUE.equals(null) == false）——不得默认开启
        final Map<String, Object> missing = assembler.translate("tools:\n  workflow_run: allow\n", "framework-nokey");
        assertThat(missing.get("enabled")).as("缺 enabled 键应按 false 处理（不得默认开启护栏，"
                + "也不得抛异常——这是 fail-safe 方向的缺省）").isEqualTo(Boolean.FALSE);

        // 反向自检：合法级别三值都能通过校验
        final Map<String, Object> allLevels = new LinkedHashMap<>();
        for (String level : new String[] {"allow", "ask", "deny"}) {
            allLevels.put(level, assembler.translate("enabled: true\ntools:\n  t_" + level + ": " + level + "\n",
                    "lvl-" + level).get("tools"));
        }
        assertThat(allLevels).as("allow/ask/deny 三个合法级别都应通过校验").hasSize(3);
        assertThatThrownBy(() -> assembler.translate("enabled: true\ntools:\n  t: ALLOW\n", "upper"))
                .as("大写 ALLOW 应被拒（枚举校验不容大小写；若被接受则与上面第 ⑤ 实例矛盾）")
                .isInstanceOf(com.openjiuwen.da.assembly.DaAssetException.class);
    }
}
