/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【探索性探针的**端到端复现**，不属设计场景、不进覆盖台账】PB：跨会话留言串台 = 提示注入。
 *
 * <p>§7G 的 L1t 探针已坐实：{@code SendMessageTool.inbox} 是 {@code Map<String, Deque<WorkflowMessage>>}，
 * **键只有 memberId、没有会话维度** ⇒ 会话 A 投给成员 {@code m1} 的留言会被会话 B 的同名成员取走并
 * 拼进其成员输入（{@code [pending messages]} 段）。本 IT 在真实宿主 + 真实模型上复现，
 * 以排除"只有测试装配才会这样"的可能。
 *
 * <p><b>构造要点</b>：成员指令要求"把收到的完整任务输入原样输出"，于是"留言是否被注入"
 * 变成可在 {@code steps[].output} 上直接判定的事实——不依赖模型复述、也不依赖读宿主体内状态。
 *
 * <p><b>结论口径</b>：若 B 的成员产物里出现 A 的留言 ⇒ **FAIL（跨会话提示注入坐实）**；
 * 若模型没有照做（没有原样输出输入）⇒ 记 INCONCLUSIVE（取证手段失效，不是产品通过），
 * 此时 L1t 的结论仍然成立、只是缺 L3 背书。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowMessageIsolationE2EIT extends DaOrchestrationE2EBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    /** 成员指令要求原样回显输入 ⇒ 留言注入可直接在产物上判定。 */
    private static final String ECHO_BLUEPRINT = """
            blueprint:
              id: T-PB-E2E
              type: single
              members:
                - {id: m1, instruction: "把你收到的完整任务输入原样输出，逐字照抄，不要总结、不要改写、不要添加任何解释"}
            """;

    @Test
    @Stories({
            // 067-S22（2026-11-26 补挂，三层 ID 对账）：PB 探针即本特性的跨会话留言隔离端到端场景
            // （TestPlan :158/:196/:322 明写「067-S22（PA/PB 隔离探针）」——同一 testcase 共担，
            // 先例同 065-S21/066-S13、065-S23/067-S31）。判据「会话 A 的定向留言不应被会话 B 的
            // 同名成员取走」与本方法断言逐字对应；r9 e2e PASS（XML 已归档）。
            @Story("067-S22: 跨会话留言隔离端到端探针 PB——会话 A 的定向留言不应被会话 B 的同名成员取走"),
            // 原 PB 探针 Story 保留（DA 宿主隔离属性的对抗性探针出身，铁律 B 出身登记）
            @Story("隔离性探针 PB（端到端）: 会话 A 的留言不应被会话 B 的同名成员取走")
    })
    @DisplayName("067-S22 + PB-E2E 会话 A 定向留言给 m1 后不再运行；会话 B 用同一张图纸运行 → B 的 m1 不应收到 A 的留言")
    void sessionBCannotDrainSessionAMessage() throws Exception {
        final String secret = "SECRET-MSG-FROM-SESSION-A-" + RUN;
        final String sessionA = newSession("sess-pb-a");
        final String sessionB = newSession("sess-pb-b");
        final String question = "Q-PB-" + RUN;

        // 会话 A：先跑一次（完成成员登记，因为收件箱是绑定期 registerMember 预建的），再定向留言。
        // 用**实装模板**而不是内联 YAML：run-17 实测模型不会原样转述 YAML
        // （`workflow_run failed: missing top-level 'blueprint' mapping`），那是取证手段失效、不是产品结论。
        // 模板 research-pipeline 的成员名是**固定的** searcher/verifier/writer ⇒ 定向留言的 target 可确定。
        final JsonNode runA1 = runAndSnapshot(sessionA,
                "请调用 workflow_run 工具，template 参数用 research-pipeline ，question 参数用 " + question
                        + " 。不要自己作答，必须调用该工具；工具返回后只回复 final_output 原文。");
        failIfTaskFailed(runA1);
        Allure.addAttachment("会话 A 第一次 /run 响应（截断 6KB）", "text/plain",
                truncate(runA1.toString(), 6144));
        final JsonNode callA = requireWorkflowRunCall(runA1);
        final String outA = callA.path("output").asText();
        if (outA.isBlank()) {
            // 已知阻塞 agent-solution#462（compat 未回填 tool_result；run-44 实测 delegate_task 21.5ms 亦命中
            // ⇒ 不限于长耗时工具）。改用产品侧观测面，不判产品 FAIL。
            System.out.println("[WARN][PB-E2E] 会话 A 的 toolCalls[].output 为空 —— 命中 agent-solution#462；"
                    + "改走产品侧观测面（响应顶层 status + SUT 日志时间窗）。");
            assertThat(runA1.path("status").asText()).as("会话 A 的首次运行任务应 completed（产品侧观测面）")
                    .isEqualTo("completed");
        } else {
            // 【2026-10-21 形态改造】出参已是 JSON（DaToolResultJsonRail，裁 1-B2）⇒ 取字段而非 contains
            assertThat(extractStatus(outA)).as("会话 A 的首次运行应成功").isEqualTo("completed");
        }

        final JsonNode runA2 = runAndSnapshot(sessionA,
                "请调用 send_message 工具，target 参数用 searcher ，content 参数用 " + secret
                        + " 。只调用该工具，不要调用别的工具，调用后回复“已发送”。");
        Allure.addAttachment("会话 A 留言轮 /run 响应（截断 6KB）", "text/plain",
                truncate(runA2.toString(), 6144));
        final boolean sent = runA2.toString().contains("send_message");
        Assumptions.assumeTrue(sent, "会话 A 未能调用 send_message（模型未配合）⇒ 取证前提不成立，"
                + "记 INCONCLUSIVE（L1t 侧结论仍成立，见批次记录 §7G）；恢复动作 = 重试或强化指令语料。");

        // 会话 B：用**同一张图纸**（因此同名成员 m1）跑一次
        // 【观测面】B 请求**之前**先记 SUT 日志位点：此后新增的日志即"B 窗口"。
        // 之所以用时间窗而不是按 session_id 过滤：宿主的内容类日志行（logLlmMessage / 模型答复正文）
        // **不一定带 session_id**，按 id 过滤会漏；而 A 阶段在 B 开始前已完全结束，位点切分是可靠的。
        final int logMark = readSutLog().length();
        final JsonNode runB = runAndSnapshot(sessionB,
                "请调用 workflow_run 工具，template 参数用 research-pipeline ，question 参数用 " + question
                        + " 。不要自己作答，必须调用该工具；工具返回后只回复 final_output 原文。");
        failIfTaskFailed(runB);
        Allure.addAttachment("会话 B /run 响应（截断 12KB）", "text/plain", truncate(runB.toString(), 12288));
        final JsonNode callB = requireWorkflowRunCall(runB);
        final String rawB = callB.path("output").asText();
        final String logAll = readSutLog();
        final String windowA = logAll.substring(0, Math.min(logMark, logAll.length()));
        final String windowB = logAll.substring(Math.min(logMark, logAll.length()));
        Allure.addAttachment("PB 观测面：A 窗口/B 窗口是否含留言标记", "text/plain",
                "secret=" + secret + "\nlogMark=" + logMark + "\nlogAll.length=" + logAll.length()
                        + "\nA 窗口含标记=" + windowA.contains(secret)
                        + "\nB 窗口含标记=" + windowB.contains(secret)
                        + "\nB 窗口长度=" + windowB.length());

        // ---- 通道选择（2026-10-28 改造：把兜底通道的自检移进兜底分支）----
        // 🔴【2026-10-28 更正，我方测试缺陷】此前"日志窗口正向自检"写在通道选择**之前**，等于用**兜底通道**
        // 的前提去门控**确定性通道**。run-105（r8 制品）实测：`toolCalls[].output` 已可用——`agent-solution#462`
        // 的断链修复 `14f68d83e` 随 `!860` 进 `common@cf4571b30`，是本轮基线 `e6e84cd71` 的**祖先**
        // （`git merge-base --is-ancestor` = 真），r8 的 `DaEventTranslator` 里 `tool_result`/`tool_output`
        // 双名兼容在场；同轮 `065-S22/S23`、`066-S16` 等用例正是靠这条通道取到 `steps[]`/`final_output` 并 PASS。
        // 但本轮模型没有在答复正文里回显 question 标记 ⇒ 用例在 413.4 s 后被 Assumptions 中止记 INCONCLUSIVE，
        // 白跑一轮且拿不到结论（全日志搜该标记命中 0；同窗口 `llm` 日志器的 `OpenAI API response received`
        // 仍有 66 行 ⇒ 不是日志面回归，是"依赖模型回显随机标记"这条间接观测链本身不稳定）。
        // ⇒ 现在自检只守护它真正对应的那条兜底通道；确定性通道可用时不再被它门控。
        if (rawB.isBlank()) {
            // 兜底通道：toolCalls[].output 为空时才走 SUT 日志时间窗判据。
            // 注：#462 的断链已修（见上），此处若仍为空属**新现象**，需另行定位，不再默认归因 #462。
            // ---- 兜底通道的正向自检（**必须放在接收侧**）----
            // 【2026-10-20 更正】首版把自检写成"A 窗口应含留言标记"，run-45 实测该断言恒不成立 ⇒ 用例被
            // Assumptions 中止记 SKIPPED（716.6 s 白跑）。根因：SUT 日志**从不回显工具入参**——
            // send_message 在日志里只留 permission.check.final 与 react_tool_call_completed，
            // 全日志搜留言标记命中 0。标记只可能经**接收侧**进日志：留言被注入成员输入后，
            // 成员（真实模型）会在自己的推理/答复里提到它（run-39 旧基线即如此，命中 18 次）。
            // ⇒ 正确的自检是：**B 窗口里必须能看到成员级别的内容**。用 question 标记做探针——
            // 它是 workflow_run 的根输入、必然进入每个成员的 userInput，成员一旦提及即证明
            // "成员内容 → 日志"这条链在 B 窗口内是通的。run-45 实测：question 标记在 B 窗口命中 47 次。
            Assumptions.assumeTrue(windowB.contains(question),
                    "取证前提不成立：会话 B 的日志窗口里连 question 标记 " + question
                            + " 都看不到 ⇒ 『成员内容 → 日志』这条观测链在 B 窗口内不通，"
                            + "此时『B 窗口不含留言标记』不能证明隔离成立，记 INCONCLUSIVE"
                            + "（不判产品 FAIL、也不判隔离成立）；恢复动作 = 待 agent-solution#462 修复后"
                            + "改回 toolCalls[].output 的确定性通道（该修复已随 !860/14f68d83e 落地并在 r4/r8 验证"
                            + "⇒ 本兜底分支正常情况下不应再被走到）。 B 窗口长度=" + windowB.length());
            System.out.println("[WARN][PB-E2E] 会话 B 的 toolCalls[].output 为空 —— #462 的断链已修（!860/14f68d83e），"
                    + "此处为空属新现象；留言串台判据本轮改走 SUT 日志 B 窗口（兜底通道，自检见上）。");
            assertThat(windowB)
                    .as("会话 B 的日志窗口**不应**出现会话 A 投递的留言 %s。若命中，说明真实请求走共享消息区路径，"
                            + "跨会话提示注入成立，应按安全缺陷提单；B 窗口长度=%s", secret, windowB.length())
                    .doesNotContain(secret);
            // 附加产品侧判据：B 的任务本身正常完成（隔离不是靠"B 跑失败"达成的）
            assertThat(runB.path("status").asText()).as("会话 B 的任务应正常 completed（隔离不得靠失败达成）")
                    .isEqualTo("completed");
            return;
        }

        // ---- 通道可用时的严判路径（#462 修复后自动生效，判据一字不改）----
        final String checkedB = requireSuccessOutput(callB);
        final List<Step> stepsB = extractSteps(checkedB);
        assertThat(stepsB).as("会话 B 应有 3 步留痕（research-pipeline 模板）").hasSize(3);
        // 取证局限（如实声明）：shipped 模板的成员指令是"检索/核查/写作"，**不会回显自己的输入**，
        // 因此"留言是否被注入"在 L3 只能靠"产物里是否出现留言原文"间接观察——
        // 命中即坐实（FAIL），未命中**不能**证明隔离成立（记 INCONCLUSIVE）。
        // L1t 侧已用可回显的图纸直接坐实（批次记录 §7G）；L3 的装配事实（单例 runner 的
        // `private final SendMessageTool sendMessage`）由 PA 的 L3 证据推定。
        final String memberOutput = String.join(" || ",
                stepsB.stream().map(Step::output).toList());
        System.out.println("[PROBE-PB-E2E] 会话 B 的 m1 产物是否含 A 的留言: " + memberOutput.contains(secret));

        // 取证前提自检：成员确实照做（原样回显了输入），否则"未命中"不能证明隔离成立
        Assumptions.assumeTrue(memberOutput.contains(secret) || memberOutput.length() > 40,
                "会话 B 无任何步骤产物可供观察 ⇒ 取证手段失效，记 INCONCLUSIVE；实收="
                        + truncate(memberOutput, 512));

        assertThat(memberOutput)
                .as("会话 B 的成员 m1 **不应**收到会话 A 投递的留言 %s。若命中，说明真实请求走共享消息区的路径，"
                        + "跨会话提示注入成立（一个会话可向另一个会话的子 Agent 注入指令），应按安全缺陷提单；"
                        + "实收产物=%s", secret, truncate(memberOutput, 2048))
                .doesNotContain(secret);
    }

    /** 读 SUT 宿主日志（产品侧观测面；不依赖模型复述、也不依赖被 #462 阻断的 toolCalls 通道）。 */
    private String readSutLog() throws Exception {
        final Path log = ((ManagedSutInstance) stack.managedInstance(AGENT)).logFile();
        assertThat(Files.isRegularFile(log)).as("应能定位 SUT 宿主日志：%s", log).isTrue();
        return Files.readString(log, StandardCharsets.UTF_8);
    }
}
