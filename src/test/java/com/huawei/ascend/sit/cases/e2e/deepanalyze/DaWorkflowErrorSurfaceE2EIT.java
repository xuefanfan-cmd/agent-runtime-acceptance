/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067-S25 / 067-S26（L3，2026-10-20 新增）：**错误表面与异步性的端到端判据**。
 *
 * <p><b>为什么必须有 L3 这一层</b>：FEAT-067 的 REQ-004「坏图纸与执行失败返回可读错误文案，**会话不炸**」、
 * REQ-044「门控开启 + 图纸含违规 → 工具返回可读错误文案，**会话不炸**」、REQ-005「异步提交即返回、**不阻塞会话**」
 * 三条里的"会话不炸 / 不阻塞会话"是**宿主线程与 Task 生命周期性质**，组件层（067-S03 / 067-S04）
 * 只能断言"工具返回 error 对象而非抛异常"与"invoke 立即返回"，**证不到会话存活**。
 * 批次记录 §7AD.2 据此把这三条判为 **A 类：必须补 L3**。
 *
 * <p><b>为什么不受 {@code agent-solution#462} 阻塞（预判，非实测）</b>：#462 是 compat 聚合层在
 * <b>长耗时工具</b>上丢失 {@code tool_result}（{@code toolCalls[].output} 缺失、{@code status} 卡 {@code running}）。
 * 本类两个场景的工具调用都是<b>快</b>的——违规图纸在 {@code WorkflowRunTool:122-126} 被
 * {@code catch (BlueprintException)} <b>装载期即返回</b>、不进成员执行；{@code delegate_task} 是
 * {@code board.submit(...)} 后<b>立即返回</b>（{@code DelegateTaskTool:59-67}）。
 * 实测支点：run-41 里 PA 探针经<b>同一通道</b>读毫秒级 {@code subagent_transcript} 的结果<b>成功</b>。
 * ⚠️ 若本类首跑出现 {@code output} 为空，则说明 #462 <b>不限于长工具</b>，须扩大其影响面描述并回报，
 * 届时本类结论记 INCONCLUSIVE 而非产品 FAIL。
 *
 * <p><b>模型不配合的处置（沿用设计明文）</b>：主 Agent 未调用目标工具时，
 * {@link DaOrchestrationE2EBase#requireWorkflowRunCall} / 本类的 {@code requireToolCall} 以
 * {@code Assumptions.abort} 记 <b>INCONCLUSIVE（SKIPPED）</b>，<b>不判产品 FAIL</b>。
 *
 * <p><b>L1t 与 L3 的分工（不重复计功）</b>：错误文案的<b>五态齐备</b>与 {@code delegate_task} 的
 * <b>四态 + 有界等待证明立即返回</b>由 067-S03 / 067-S04 在组件层确定性承接；
 * 本类只补 L3 独有的那半——<b>真实宿主下会话是否存活、是否被阻塞</b>。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowErrorSurfaceE2EIT extends DaOrchestrationE2EBase {

    /**
     * 违规图纸：**不可识别的 type**。
     *
     * <p>刻意写成<b>单行 flow-style YAML</b>——真实模型要把它原样搬进 {@code blueprint} 入参，
     * 多行块状 YAML 极易被模型重新格式化/修正掉违规点，导致用例退化为 INCONCLUSIVE。
     * {@code type: mesh} 与组件层 065-S03 的 {@code T-065-S03-type} 用例同源（装载器报
     * {@code unknown blueprint.type=mesh}），保证 L1t 与 L3 打的是<b>同一个违规族</b>。
     */
    private static final String BAD_BLUEPRINT =
            "blueprint: {id: T-067-S25-bad, type: mesh, members: [{id: m1, instruction: x}]}";

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @Story("067-S25: 违规图纸 → 工具级可读错误，且同一会话随后仍能正常服务（会话不炸）")
    @DisplayName("067-S25 门控开的真宿主里传违规图纸：错误可读含定位、不抛穿会话，且同会话下一次请求仍成功")
    void badBlueprintYieldsReadableErrorAndSessionSurvives() throws Exception {
        final String session = newSession("sess-067-s25");

        // ---- ① 违规图纸 ----
        // 【2026-10-21 加固】run-64 实测：模型把 fixture 的顶层 `blueprint:` 一词剥掉、只传了花括号部分
        // （实收 input.blueprint = "{id: T-067-S25-bad, type: mesh, …}"）⇒ 装载器改报
        // "missing top-level 'blueprint' mapping"，违规族没打到 type: mesh。prompt 里显式点名
        // "包含开头的 blueprint: 一词和它后面的空格"，把搬参歧义堵掉。
        final JsonNode first = runAndSnapshot(session,
                "请调用 workflow_run 工具。blueprint 参数的值必须**逐字符等于**下面这一整行，"
                        + "**包含开头的 `blueprint:` 一词和它后面的空格**——不要只传花括号部分，"
                        + "不要修改、不要修正、不要重新格式化、不要补字段、不要去掉任何词。"
                        + "question 参数传 Q-067-S25。这一整行是：\n"
                        + BAD_BLUEPRINT);
        final JsonNode call = requireWorkflowRunCall(first);   // 模型未调用 ⇒ INCONCLUSIVE，不判 FAIL
        Allure.addAttachment("067-S25 第一次请求响应全文（截断 8KB）", "text/plain", truncate(first.toString(), 8192));

        // ①-a 产品侧判据（**不依赖 compat 的 toolCalls 通道**）：工具错误没有升级成任务失败
        assertThat(first.path("status").asText())
                .as("违规图纸只应造成**工具级**错误，不得让任务本身失败（Feature-067 §2「不炸会话」的第一层含义）；实收响应=%s",
                        truncate(first.toString(), 1024))
                .isEqualTo("completed");

        // ①-b 错误文案：**能拿到就严判，拿不到就 WARN 并说明由谁承接**（不因此判 FAIL）
        final String out = call.path("output").asText();
        Allure.addAttachment("067-S25 违规图纸的 workflow_run toolCall 原文（截断 8KB）", "text/plain",
                truncate(call.toString(), 8192));
        if (out.isBlank()) {
            // 已知阻塞：agent-solution#462 —— compat 聚合层未把 tool_result 回填进 toolCalls[]，
            // 记录停在 status="running"、无 output。run-42 实测：**delegate_task（22.9 ms）同样命中**，
            // 故 #462 不限于长耗时工具（我方此前"只打长工具"的收窄已被本轮证伪，须回报扩大影响面）。
            System.out.println("[WARN][067-S25] toolCalls[].output 为空 —— 命中 agent-solution#462。"
                    + "错误**文案**的判据由 L1t 067-S03（DaWorkflowErrorSurfaceTest，五态文案 + 违规定位）确定性承接，"
                    + "L3 不重复计功；本条在 L3 只判「错误停在工具级、未穿会话」+「会话存活」，改用产品侧观测面。");
        } else {
            // 【2026-10-21 形态改造】原判据 contains("success=false") 依赖 ToolOutput.toString() 的信封文本。
            // DaToolResultJsonRail（裁 1-B2，AFTER_TOOL_CALL 优先级 110）只序列化 data，且在 error 非空时
            // 写 {"error":…} ⇒ 信封串在新形态下**不复存在**。语义判据不变：仍判"这是工具级错误、文案可读含定位"，
            // 只把观测面换成 JSON 的 error 键。
            // 【2026-11-24 r9 形态适配】r9 起工具级错误出参为**纯文本行**（ToolArgs.fail/ToolOutput.error
            // 直出文案，HEAD WorkflowRunTool:148/:174 `workflow_run failed: …`），不再包 JSON 信封——
            // r8 时代的「JSON 对象」断言属测试侧过度指定（TestPlan/细档 0 处承诺 JSON 形态；判据实质=
            // 「工具级错误、文案可读含定位、停在工具级」）。适配=**双形态兼容**：JSON 信封（r8 及更早）
            // 取 error 键；纯文本（r9 起）取整行。后续断言（fail-fast 前缀、定位词）不变。
            JsonNode errJson = null;
            final int from = out.indexOf('{');
            final int to = out.lastIndexOf('}');
            if (from >= 0 && to > from) {
                try {
                    final JsonNode candidate = MAPPER.readTree(out.substring(from, to + 1));
                    if (candidate.isObject()) {
                        errJson = candidate;
                    }
                } catch (Exception ignored) {
                    // 非 JSON 形态 ⇒ 走纯文本分支
                }
            }
            final String errText = errJson != null
                    ? errJson.path("error").asText("")
                    : out.strip();
            assertThat(errText)
                    .as("违规图纸应是工具级错误（判据实质＝错误文案非空可读，形态兼容 JSON 信封与纯文本行）；实收=%s",
                            truncate(out, 1024))
                    .isNotBlank();
            // 硬判据（形态无关、违规族无关）：fail-fast 前缀，不静默降级
            assertThat(errText).as("错误文案应指明 workflow_run 失败（fail-fast，不静默降级）；实收=%s",
                            truncate(out, 1024))
                    .contains("workflow_run failed");

            // 【2026-10-21 拆两层】"违规族同源性"判据（mesh）依赖**模型原样搬参**这一 L3 固有不确定前提。
            // run-64 实测模型剥掉了顶层 `blueprint:` 一词 ⇒ 产品改报 "missing top-level 'blueprint' mapping"
            // （这是对它实际收到的入参的**正确**报错），断言 contains("mesh") 因此不成立——**不是产品缺陷**。
            // 处置：定位判据改为**从实测入参推导**期望定位词（因此始终非空洞、且随模型搬参结果自适应）；
            // 违规族是否打到 type: mesh 单独判读并如实登记，未打到时该**子**判据记 INCONCLUSIVE，
            // 由 L1t 065-S03（T-065-S03-type，装载器报 unknown blueprint.type=mesh）确定性承接
            // ——即 BAD_BLUEPRINT 注释里写明的同源关系。不整例 abort，因为 ①-a/①-c/② 与违规族无关、仍须严判。
            final String actualBlueprint = call.path("input").path("blueprint").asText("");
            final boolean preservedTopLevelKey = actualBlueprint.stripLeading().startsWith("blueprint:");
            final String expectedLocator = preservedTopLevelKey ? "mesh" : "blueprint";
            assertThat(errText)
                    .as("错误文案应含违规定位——须指认入参中**实际**违规的构造（本轮实测入参%s顶层 "
                                    + "`blueprint:` 键 ⇒ 期望定位词 %s）；实收=%s",
                            preservedTopLevelKey ? "含" : "缺", expectedLocator, truncate(out, 1024))
                    .contains(expectedLocator);
            Allure.addAttachment("067-S25 取证前提判读：模型是否原样搬参 + 违规族是否打到", "text/plain",
                    "fixture 原文=" + BAD_BLUEPRINT
                            + "\n实测 input.blueprint=" + actualBlueprint
                            + "\n原样搬参（含顶层 blueprint: 键）=" + preservedTopLevelKey
                            + "\n违规族打到 type: mesh=" + preservedTopLevelKey
                            + "\n错误文案=" + errText
                            + (preservedTopLevelKey
                                    ? "\n判读：打到目标违规族，与 L1t 065-S03 同源，L3 严判成立。"
                                    : "\n判读：**违规族未打到 type: mesh**（模型改写了入参）⇒『不可识别类型』这一子判据"
                                    + "本轮记 INCONCLUSIVE，由 L1t 065-S03（T-065-S03-type，装载器报 "
                                    + "unknown blueprint.type=mesh）确定性承接；L3 本轮实际验证的是"
                                    + "『缺顶层 blueprint 键』这一违规族的：错误可读含定位 + 错误停在工具级 + 会话不炸。"
                                    + "\n不判产品 FAIL——产品对它实际收到的入参报错正确。"));
            if (!preservedTopLevelKey) {
                System.out.println("[INCONCLUSIVE][067-S25] 模型改写了入参（剥掉顶层 blueprint: 键）⇒ "
                        + "『不可识别类型 mesh』子判据本轮无法验证，由 L1t 065-S03 承接；"
                        + "L3 仍严判『错误可读含定位 + 停在工具级 + 会话不炸』。实收错误=" + errText);
            }
            // 【探针，不预判】!860 声称"原生 error 载荷映射为 failed"。但静态推演显示：JSON rail 已把
            // ToolOutput 信封拍平成 String 写回 ToolCallInputs（core AbilityManager#preferRewrittenInputs
            // 会用它替换 ExecutionResult.result()），而 core ToolLifecycleOutputFactory#isErrorResult 靠
            // 反射读 success 字段 ⇒ 对 String 恒读不到 ⇒ 可能恒判 completed，令 !860 的映射对 DA 宿主工具
            // 永不生效。**按纪律不以静态推演改判**：这里只记录实测值，由本轮执行结果决定要不要提单。
            final String observedToolStatus = call.path("status").asText("");
            Allure.addAttachment("067-S25 探针：工具级错误时 toolCalls[].status 的实测值", "text/plain",
                    "toolCalls[].status=" + observedToolStatus
                            + "\n出参 error 键非空=" + (!errText.isBlank())
                            + "\n任务级 status=" + first.path("status").asText("")
                            + "\n判读：若 status=completed，则 !860 的 error→failed 映射在本宿主工具面上未生效"
                            + "（JSON rail 拍平信封所致），属错误面口径漂移，需提单；若 =failed 则映射生效。"
                            + "\n工具出参原文（截断 2KB）=" + truncate(out, 2048));
            System.out.println("[PROBE][067-S25] 工具级错误时 toolCalls[].status=" + observedToolStatus
                    + "（expected-by-!860=failed；静态推演疑似 completed）");
        }

        // ①-c 产品侧观测面：SUT 日志里该工具调用**正常返回**（未抛异常），即"错误停在工具级"
        // 【2026-10-27 修正】按本用例 sessionId 窗口过滤，不再全文扫描（跨轮累积日志会混入他轮记录）
        // 【2026-11-24 r9 形态适配】r9 起工具 outcome 分类细化：ToolOutput(success=false) 记
        // `outcome=business_error`（业务错误正常返回、未穿工具层），不再是 r8 的 `outcome=success`。
        // 判据实质「错误停在工具级」在 r9 的正确观测面＝business_error（若出现 exception/system_error
        // 才说明错误穿出了工具层）。断言改为双形态兼容：outcome=success（r8 形态）或
        // outcome=business_error（r9 形态）任一 ≥1 即"停在工具级"。
        final String log = readSutLogForSession(session);
        final int successCount = countOccurrences(log, "tool_name=workflow_run outcome=success");
        final int businessErrorCount = countOccurrences(log, "tool_name=workflow_run outcome=business_error");
        Allure.addAttachment("067-S25 工具 outcome 观测（r8/r9 双形态兼容）", "text/plain",
                "outcome=success 计数=" + successCount
                        + "\noutcome=business_error 计数=" + businessErrorCount);
        assertThat(successCount + businessErrorCount)
                .as("SUT 日志应记录 workflow_run 的调用**正常返回**（r8 形态 outcome=success ／ r9 形态"
                        + " outcome=business_error=业务错误以 ToolOutput(success=false) 正常返回、未抛异常——"
                        + "两者都证明错误停在工具级；若为 exception/system_error 则说明错误穿出了工具层）；"
                        + "实收 success=%d business_error=%d", successCount, businessErrorCount)
                .isGreaterThanOrEqualTo(1);

        // ---- ② 会话不炸：同一 sessionId 再发一次普通请求，应正常完成 ----
        final JsonNode second = runAndSnapshot(session, "请只回复两个字：收到。不要调用任何工具。");
        Allure.addAttachment("067-S25 同会话第二次请求响应（截断 6KB）", "text/plain", truncate(second.toString(), 6144));
        assertThat(second.path("status").asText())
                .as("违规图纸之后，**同一会话**应仍能正常完成任务（『会话不炸』的实质判据）；实收=%s",
                        truncate(second.toString(), 1024))
                .isEqualTo("completed");
        assertThat(second.path("output").asText())
                .as("第二次请求应有非空答复正文（会话未被前一次工具错误破坏）").isNotBlank();
        assertThat(countOccurrences(readSutLog(), "All tasks completed for session " + session))
                .as("SUT 日志应记录该会话**两次**任务完成（两次请求各一次）；实收次数不足说明第二次请求未真正被受理")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @Story("067-S26: delegate_task 异步提交即返回，且同一会话随后立即可被服务（不阻塞会话）")
    @DisplayName("067-S26 真宿主里 delegate_task 返回 task_id 即结束，同会话紧接着的第二次请求不被长任务阻塞")
    void delegateTaskDoesNotBlockTheSession() throws Exception {
        final String session = newSession("sess-067-s26");

        // ---- ① 异步提交即返回：出参含 task_id ----
        final long t0 = System.nanoTime();
        final JsonNode first = runAndSnapshot(session,
                "请调用 delegate_task 工具，goal 参数传：请写一段约 600 字的分析，主题是"
                        + "「团队在一个季度内交付新系统的主要风险」。只调用这一个工具，拿到 task_id 后直接把它告诉我。");
        final long delegateMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        final JsonNode call = requireToolCall(first, "delegate_task");   // 模型未调用 ⇒ INCONCLUSIVE
        final String out = call.path("output").asText();
        Allure.addAttachment("067-S26 delegate_task 出参原文（截断 8KB）", "text/plain", truncate(out, 8192));
        if (out.isBlank()) {
            System.out.println("[WARN][067-S26] toolCalls[].output 为空 —— 命中 agent-solution#462（delegate_task 仅 22.9 ms 亦命中，"
                    + "证其不限于长耗时工具）。task_id/submitted 的**出参契约**由 L1t 067-S04 确定性承接；"
                    + "L3 本条只判「提交即返回、不阻塞会话」，改用产品侧观测面（日志 outcome= 与 duration_ms）。");
        } else {
            assertThat(out).as("delegate_task 应返回 submitted 与 task_id（Feature-067 §2：出参任务标识、异步提交即返回）；实收=%s",
                            truncate(out, 1024))
                    .contains("submitted")
                    .contains("task_id");
        }
        // 产品侧观测面：delegate_task 的工具调用应是**毫秒级返回**（异步提交即返回的直接证据）
        // 【2026-10-27 修正：跨轮累积日志导致的假失败】原实现对 stdout.log **全文**取 max，而该文件是跨轮累积的活文件
        // （本批已累积自 2026-10-21 起全部轮次）。run-88 实测：本轮 delegate_task 仅 20.26 ms，但全文最大值是
        // run-87（2026-10-26 17:39:05，PID 88404，session sess-067-s19-8b21ea5e）的 **423322.36 ms** ⇒ 断言
        // `< 5000` 假失败。改为**按本用例 sessionId 过滤**后再取 max，判据语义（异步提交即返回、不得同步阻塞）不变。
        final String log = readSutLogForSession(session);
        final Matcher m = Pattern.compile("tool_name=delegate_task outcome=success duration_ms=([0-9.]+)").matcher(log);
        double maxDuration = -1.0D;
        while (m.find()) {
            maxDuration = Math.max(maxDuration, Double.parseDouble(m.group(1)));
        }
        assertThat(maxDuration)
                .as("SUT 日志里 delegate_task 的工具调用耗时应为毫秒级（异步提交即返回，不等委托体跑完）；"
                        + "未匹配到则说明该观测面失效，本条不得据此判通过")
                .isGreaterThanOrEqualTo(0.0D);
        Allure.addAttachment("067-S26 delegate_task 工具耗时（ms）", "text/plain", String.valueOf(maxDuration));
        assertThat(maxDuration).as("delegate_task 应在 5 秒内返回（**仅统计本会话窗口内**的调用）；若接近委托体真实耗时即说明变成了同步阻塞")
                .isLessThan(5000.0D);

        // ---- ② 不阻塞会话：同一会话紧接着的第二次请求应正常返回 ----
        final long t1 = System.nanoTime();
        final JsonNode second = runAndSnapshot(session, "请只回复两个字：收到。不要调用任何工具。");
        final long secondMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);
        Allure.addAttachment("067-S26 计时与第二次请求响应", "text/plain",
                "delegate 请求耗时=" + delegateMillis + " ms\n第二次请求耗时=" + secondMillis + " ms\n响应（截断 6KB）="
                        + truncate(second.toString(), 6144));
        assertThat(second.path("status").asText())
                .as("delegate_task 提交后，**同一会话**应能立即被服务（『不阻塞会话』的实质判据）；实收=%s",
                        truncate(second.toString(), 1024))
                .isEqualTo("completed");
        assertThat(second.path("output").asText())
                .as("第二次请求应有非空答复正文").isNotBlank();
        assertThat(countOccurrences(readSutLog(), "All tasks completed for session " + session))
                .as("SUT 日志应记录该会话**两次**任务完成（委托请求 + 紧接的普通请求）；"
                        + "不足两次说明委托把会话堵住了")
                .isGreaterThanOrEqualTo(2);
        // 说明（不作为硬判据）：委托体是真实模型的裸 ReActAgent（**2026-10-27 更正**：docs `!379` 后它按发起方
        // 会话范围**带 14 件只读工具**，不再是"无工具"，见 FEAT-067:50/:51 与 067-S28/S30/S31），600 字分析通常远慢于一次"回复收到"。
        // "在长任务完成前就返回"这一半由组件层 067-S04 用**有界等待**确定性证明；L3 只补"真实宿主下会话不被阻塞"。
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @Story("067-S32: 委托执行体的模型口径（REQ-033 的 L3-C 执行证据）")
    @DisplayName("067-S32 真宿主里 delegate_task 的委托执行体走非流式 + temperature=0.3 + max_tokens=4000（FEAT-067:38 缺省口径），且与主 Agent 的流式/无 max_tokens 形态可区分")
    void delegateExecutorUsesHostModelContract() throws Exception {
        final String session = newSession("sess-067-s32");
        final ManagedSutInstance instance = (ManagedSutInstance) stack.managedInstance(AGENT);

        // 【开窗：位点 + PID 双条件】`Before request chat model …` 这类**模型参数行不带 session_id**
        // （内容/参数类日志行的通病，见 DaWorkflowMessageIsolationE2EIT 的同类说明），按会话过滤会漏；
        // 而同一轮里本类的其它用例也会发请求，只按 PID 过滤会混入 ⇒ 先记位点取"本用例之后新增"的片段，
        // 再按 PID 过滤掉别的进程写进同一文件的内容。两条一起用才既不漏也不混。
        final int mark = readSutLog().length();

        final JsonNode snapshot = runAndSnapshot(session,
                "请调用 delegate_task 工具，goal 参数传：请用一句话说明为什么要做任务委托。"
                        + "只调用这一个工具，拿到 task_id 后直接把它告诉我。");
        failIfTaskFailed(snapshot);
        requireToolCall(snapshot, "delegate_task");   // 模型未调用 ⇒ INCONCLUSIVE（不判产品 FAIL）

        final String full = readSutLog();
        final String window = DaOrchestrationE2EBase.windowByPid(
                full.substring(Math.min(mark, full.length())), instance.pid());
        assertThat(window).as("按位点+PID 开窗后应能在本实例日志里看到本用例的模型请求参数行").isNotBlank();

        // 解析每一条模型请求的参数三元组（temperature / max_tokens / is_stream）
        final List<String> nonStreaming = new ArrayList<>();
        final List<String> streaming = new ArrayList<>();
        final Matcher m = Pattern.compile(
                "Before request chat model[^\\n]*?temperature=([^,]+),[^\\n]*?max_tokens=([^,]+),[^\\n]*?is_stream=(\\w+)")
                .matcher(window);
        while (m.find()) {
            final String line = "temperature=" + m.group(1) + " max_tokens=" + m.group(2) + " is_stream=" + m.group(3);
            if ("false".equals(m.group(3))) {
                nonStreaming.add(line);
            } else {
                streaming.add(line);
            }
        }
        Allure.addAttachment("067-S32 委托执行体模型口径（位点+PID 开窗后的实测参数行）", "text/plain",
                "session=" + session + "\npid=" + instance.pid() + "\n位点=" + mark + "\n全文长度=" + full.length()
                        + "\n窗口内行数=" + DaOrchestrationE2EBase.countLines(window)
                        + "\n\n非流式调用 " + nonStreaming.size() + " 条：\n" + String.join("\n", nonStreaming)
                        + "\n\n流式调用 " + streaming.size() + " 条：\n" + String.join("\n", streaming));

        // ① 委托执行体必须真的发起过模型调用（否则本条无从评估）
        assertThat(nonStreaming)
                .as("FEAT-067:38（docs !379 改写后）：委托执行体**与工作流成员同模型口径**（temperature/max_tokens 缺省 0.3/4000，"
                            + "可经宿主模型配置覆盖），且成员/委托走**非流式**；"
                        + "本用例应至少观测到 1 条非流式模型请求。实收非流式 %d 条、流式 %d 条",
                        nonStreaming.size(), streaming.size())
                .isNotEmpty();

        // ② 缺省口径：temperature=0.3、max_tokens=4000（FEAT-067:38 / L067:195 明文的缺省值）
        for (String line : nonStreaming) {
            assertThat(line)
                    .as("FEAT-067:38 + L067:195：委托/成员执行体的采样参数缺省为 temperature 0.3、max_tokens 4000；"
                            + "实收=%s。（`!894` 起该两项可经 `deepanalyze.model.model` 覆盖，本用例跑在缺省配置下，"
                            + "故断言缺省值；改值对照属 r7 的复验项）", line)
                    .contains("temperature=0.3")
                    .contains("max_tokens=4000");
        }

        // ③ 两种形态必须**可区分**：主 Agent 走流式且不设 max_tokens 上限
        assertThat(streaming)
                .as("同一会话里主 Agent 的模型调用应是**流式**（否则无法证明「非流式那一条来自委托执行体」）")
                .isNotEmpty();
        for (String line : streaming) {
            assertThat(line)
                    .as("主 Agent 走宿主缺省口径：is_stream=true 且不设 max_tokens 上限（实收=%s）"
                            + "——这一对照是②的证据基础：若两者形态相同，就无法把 4000 归给委托执行体", line)
                    .contains("is_stream=true")
                    .contains("max_tokens=null");
        }
    }

    /** 取指定工具的调用记录；模型未调用该工具 ⇒ INCONCLUSIVE（不判产品 FAIL）。 */
    private JsonNode requireToolCall(final JsonNode snapshot, final String toolName) {
        final JsonNode calls = snapshot.path("toolCalls");
        assertThat(calls.isArray()).as("响应应含 toolCalls 数组；实收=%s", truncate(String.valueOf(snapshot), 2048)).isTrue();
        final StringBuilder names = new StringBuilder();
        for (JsonNode call : calls) {
            final String name = call.path("toolName").asText();
            if (toolName.equals(name)) {
                Allure.addAttachment(toolName + " 调用记录原文（截断 16KB）", "text/plain", truncate(call.toString(), 16384));
                return call;
            }
            names.append(name).append(' ');
        }
        org.junit.jupiter.api.Assumptions.abort("主 Agent 本轮未调用 " + toolName + "（模型未配合，属 L3 不确定性）；"
                + "实收 toolCalls 的 toolName 集合=[" + names.toString().trim() + "]。按设计明文记 INCONCLUSIVE，不判 FAIL；"
                + "恢复动作 = 重试或强化指令语料。");
        throw new IllegalStateException("unreachable");
    }

    /**
     * 读 SUT 宿主日志并**按会话窗口过滤**。
     *
     * <p>{@code target/sit-logs/<entry>/stdout.log} 是**跨轮累积**的活文件（本批实测已累积自 2026-10-21 起的
     * 全部轮次、9 MB 量级）。对全文做正则/计数会把他轮记录算进本轮判据——既可能**假失败**
     * （run-88：067-S26 取到 run-87 的 423322.36 ms），也可能**假通过**（存在性/min 类判断被历史快调用掩盖）。
     * 故一律先按本用例自己的 sessionId 过滤再断言；skill `references/execution-and-evidence.md`
     * 「引用多轮追加的共享日志做计数统计时，数字必须带统计时点窗口」即此意。
     *
     * <p>已经天然带会话维度的断言（如 {@code "All tasks completed for session " + session}）不受影响，
     * 保持原样，避免无谓改动。
     */
    private String readSutLogForSession(final String session) throws Exception {
        final String full = readSutLog();
        final StringBuilder windowed = new StringBuilder();
        for (String line : full.split("\n")) {
            if (line.contains("session_id=" + session) || line.contains("session " + session)
                    || line.contains("sessionId=" + session) || line.contains("conversationId=" + session)) {
                windowed.append(line).append('\n');
            }
        }
        Allure.addAttachment("SUT 日志会话窗口（本用例 sessionId 过滤后行数 / 全文行数）", "text/plain",
                "session=" + session + "\n窗口内行数=" + windowed.toString().split("\n").length
                        + "\n全文行数=" + full.split("\n").length
                        + "\n（全文为跨轮累积活文件，判据只允许使用窗口内行）");
        return windowed.toString();
    }

    /** 读 SUT 宿主日志（产品侧观测面，不依赖模型复述、也不依赖 compat 的 toolCalls 通道）。 */
    private String readSutLog() throws Exception {
        final Path log = ((ManagedSutInstance) stack.managedInstance(AGENT)).logFile();
        assertThat(Files.isRegularFile(log)).as("应能定位 SUT 宿主日志：%s", log).isTrue();
        return Files.readString(log, StandardCharsets.UTF_8);
    }

    private static int countOccurrences(final String haystack, final String needle) {
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
}
