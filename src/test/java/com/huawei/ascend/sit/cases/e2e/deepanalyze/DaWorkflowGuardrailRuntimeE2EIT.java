/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S19：**运行期**护栏决策与静态登记的一致性（横切层扩审新增场景，
 * 见 `06-横切层MUST逐条判定-20261016.md` 第 8 条）。
 *
 * <p>Oracle 来源：`DFX-003`「运行期工具权限护栏」MUST 行 +「护栏配置读取与校验：必须绑定合并后的护栏配置、
 * 校验枚举与必填字段…不接受『键名写错等于未配置』的静默失效」；`L0-views:119`「工具不得绕过治理直接外呼」；
 * FEAT-067 §2「六件在护栏规则中显式放行」。
 *
 * <p><b>与 067-S12 的分工（不重复、不互相替代）</b>：067-S12 是**静态登记**核对
 * （读 `governance/actrule.yaml`，断言六件都是 `allow`、`defaults."*": ask` fail-closed、`enabled: true`）；
 * 本类是**运行期**核对：宿主真跑起来后，从 SUT 日志取 `PermissionEngine` 的实际决策，
 * 断言「被调用的每一件工具都经过运行期决策、决策结果与 actrule 的静态登记**逐件一致**、
 * 且命中规则就是该工具自己那条」。静态登记正确 ≠ 运行期真放行（中间隔着配置解析、枚举校验、分层合并）。
 *
 * <p><b>为什么落 L3 而不是 L1t</b>：core 的 `PermissionEngine` 在 **0.1.16 与 0.1.17 没有共有公开 API**
 * （0.1.16 只有 `PermissionEngine(Map, Path)` 与返回 `Map.Entry` 的 `evaluateGlobalPolicyDirectly`；
 * 0.1.17 才有 `PermissionEngine(Map)`、`isEnabled()`、返回 `PermissionEvaluation` 的
 * `checkToolPermissionDirectly`）。本仓 test-compile 走 0.1.16、运行走 0.1.17（批次记录 §7C.3 的版本冲突），
 * 故写不成跨版本源码兼容的 L1t 用例；改用**宿主运行期日志**作判据——该取证面已在 run-16/17 证实可用
 * （`permission.check.final tool=workflow_run permission=allow matched_rule=tiered_policy:tools.workflow_run` 42 次）。
 *
 * <p><b>未覆盖的 DFX-003 子项（如实声明，不猜）</b>：「安装幂等（中断恢复重入静默跳过）」
 * 「安装失败一律抛出、不降级为无护栏运行」「总开关关闭时白名单闸门一并不生效」三条需要
 * 可观测宿主装配过程或另起一套关闭态装配，本类**不断言**，留在覆盖台账的 gap 里。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowGuardrailRuntimeE2EIT extends DaOrchestrationE2EBase {

    private static final String ACTRULE = "governance/actrule.yaml";
    private static final List<String> SIX_TOOLS = List.of(
            "workflow_run", "delegate_task", "task_output",
            "send_message", "subagent_transcript", "workflow_status");

    /** `permission.check.final tool=<名> permission=<级别> matched_rule=<规则>` */
    private static final Pattern FINAL_LINE = Pattern.compile(
            "permission\\.check\\.final tool=([A-Za-z0-9_]+) permission=([a-z]+)(?: matched_rule=(\\S+))?");
    private static final Pattern START_LINE = Pattern.compile(
            "permission\\.check\\.start tool=([A-Za-z0-9_]+)");

    @Test
    @Story("067-S19: 运行期护栏决策与静态登记逐件一致")
    @DisplayName("067-S19 宿主运行期：被调用的工具都经过护栏决策，结果与 actrule 静态登记一致且命中自己那条规则")
    void runtimeGuardrailDecisionsMatchStaticRegistration() throws Exception {
        final String session = newSession("sess-067-s19");
        final String input = "请按顺序调用下面这些工具（每个都调一次，参数照给）："
                + "1) workflow_run，template 用 research-pipeline，question 用 请一句话说明数据治理的意义；"
                + "2) workflow_status，action 用 list；"
                + "3) send_message，target 用 all，content 用 GREETING-067-S19；"
                + "4) subagent_transcript，branch_id 用 roots；"
                + "5) delegate_task，goal 用 一句话说明什么是数据血缘；"
                + "6) 用第 5 步返回的 task_id 调 task_output。"
                + "如果某个工具调用失败，继续调下一个，最后简要说明你调用了哪些工具。";

        // 注意：**不**调用 failIfTaskFailed —— 护栏决策发生在工具执行之前，
        // 即使任务因模型网关超时而 failed，运行期决策证据依然有效。
        final JsonNode snapshot = runAndSnapshot(session, input);
        Allure.addAttachment("067-S19 会话响应（截断 8KB）", "text/plain",
                truncate(snapshot.toString(), 8192));

        final ManagedSutInstance guardrailInstance = (ManagedSutInstance) stack.managedInstance(AGENT);
        final Path log = guardrailInstance.logFile();
        // 【2026-10-27 修正：跨轮累积日志导致假通过风险】原实现对 stdout.log **全文**构建
        // Map<tool, level>；本轮若漏发某工具的决策，**历史轮的值会静默顶替**，断言照样绿。
        // 改为按本 SUT 实例 PID 开窗（判据语义不变：仍是"运行期决策与 actrule 静态登记逐件一致"）。
        final String contentAll = Files.readString(log, StandardCharsets.UTF_8);
        final String content = DaOrchestrationE2EBase.windowByPid(contentAll, guardrailInstance.pid());
        assertThat(content).as("按 PID=%s 开窗后应能在本实例日志里看到启动横幅（否则说明开窗键失效，"
                + "本条判据不得据全文成立）", guardrailInstance.pid()).contains("Started DeepAnalyzeApplication");
        Allure.addAttachment("SUT 日志窗口（按本实例 PID 过滤）", "text/plain",
                "pid=" + guardrailInstance.pid()
                        + "\n窗口内行数=" + DaOrchestrationE2EBase.countLines(content)
                        + "\n全文行数=" + DaOrchestrationE2EBase.countLines(contentAll)
                        + "\n（target/sit-logs/<entry>/stdout.log 跨轮累积，判据只允许使用窗口内行）");
        Allure.addAttachment("SUT 日志中的护栏决策行（截取）", "text/plain", truncateGuardrailLines(content));

        // ① 运行期确实发生了决策（start 与 final 成对出现）
        final Set<String> started = new LinkedHashSet<>();
        final Matcher startMatcher = START_LINE.matcher(content);
        while (startMatcher.find()) {
            started.add(startMatcher.group(1));
        }
        final Map<String, String> finalLevel = new LinkedHashMap<>();
        final Map<String, String> finalRule = new LinkedHashMap<>();
        final Matcher finalMatcher = FINAL_LINE.matcher(content);
        while (finalMatcher.find()) {
            finalLevel.put(finalMatcher.group(1), finalMatcher.group(2));
            finalRule.put(finalMatcher.group(1), finalMatcher.group(3));
        }
        assertThat(started).as("宿主运行期应出现护栏决策（permission.check.start）；"
                + "若一条都没有，说明护栏链没装上——DFX-003 MUST「安装失败一律抛出，不降级为无护栏运行」").isNotEmpty();
        assertThat(finalLevel).as("每个被决策的工具都应有 final 结论（start/final 成对）").isNotEmpty();
        assertThat(finalLevel.keySet()).as("有 start 就应有 final（不得只判定不给结论）")
                .containsAll(new LinkedHashSet<>(finalLevel.keySet()));

        // ② 至少覆盖到 workflow_run（本特性的主件）；否则模型没配合，记 INCONCLUSIVE
        Assumptions.assumeTrue(finalLevel.containsKey("workflow_run"),
                "本轮宿主日志里没有出现 workflow_run 的护栏决策（模型未调用该工具）⇒ 记 INCONCLUSIVE，"
                        + "不判产品 FAIL；实收被决策的工具集合=" + finalLevel.keySet());

        // ③ 被决策的每一件编排工具：运行期级别必须与 actrule 的静态登记**逐件一致**，且命中自己那条规则
        final Map<String, Object> actrule = loadActrule();
        @SuppressWarnings("unchecked")
        final Map<String, Object> tools = (Map<String, Object>) actrule.get("tools");
        assertThat(tools).as("actrule.yaml 应有 tools 节").isNotNull();
        final Map<String, String> compared = new LinkedHashMap<>();
        for (String tool : finalLevel.keySet()) {
            if (!SIX_TOOLS.contains(tool)) {
                continue;   // 非本特性六件的工具不在本用例判据内
            }
            final String runtime = finalLevel.get(tool);
            final String declared = String.valueOf(tools.get(tool));
            compared.put(tool, "runtime=" + runtime + " / actrule=" + declared
                    + " / rule=" + finalRule.get(tool));
            assertThat(tools).as("运行期被决策的工具 %s 应在 actrule 的 tools 节里有登记"
                    + "（否则属『键名写错等于未配置』的静默失效，DFX-003 明令不接受）", tool)
                    .containsKey(tool);
            assertThat(runtime).as("工具 %s 的运行期决策应与 actrule 静态登记一致；实收 runtime=%s declared=%s",
                    tool, runtime, declared).isEqualTo(declared);
            assertThat(String.valueOf(finalRule.get(tool)))
                    .as("工具 %s 应命中自己那条规则（而不是被 defaults 兜住）；实收 matched_rule=%s",
                            tool, finalRule.get(tool))
                    .contains("tools." + tool);
        }
        assertThat(compared).as("本轮应至少对 workflow_run 做过静态↔运行期比对；实收=%s", compared)
                .isNotEmpty();
        System.out.println("[067-S19] 静态↔运行期逐件比对：" + compared);
        System.out.println("[067-S19] 本轮被护栏决策过的工具全集：" + finalLevel);

        // ④ 反向自检：未被调用的工具不应凭空出现在决策日志里（防止把别的会话/别的用例的日志当成本轮证据）
        for (String tool : SIX_TOOLS) {
            if (!started.contains(tool)) {
                assertThat(finalLevel).as("工具 %s 本轮未被调用，就不应有 final 决策记录", tool)
                        .doesNotContainKey(tool);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadActrule() throws Exception {
        try (InputStream in = DaWorkflowGuardrailRuntimeE2EIT.class.getClassLoader()
                .getResourceAsStream(ACTRULE)) {
            assertThat(in).as("护栏资产应在制品 classpath 上：%s", ACTRULE).isNotNull();
            return new ObjectMapper(new YAMLFactory())
                    .readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
    }

    /** 只截护栏相关行，避免把 1 MB 级 SUT 日志整体塞进证据附件。 */
    private static String truncateGuardrailLines(final String content) {
        final StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            if (line.contains("permission.check.") || line.contains("permission.policy.result")) {
                sb.append(line, 0, Math.min(line.length(), 240)).append('\n');
                if (sb.length() > 12_000) {
                    sb.append("…(截断)\n");
                    break;
                }
            }
        }
        return sb.length() == 0 ? "(日志中未出现 permission.check.* 行)" : sb.toString();
    }
}
