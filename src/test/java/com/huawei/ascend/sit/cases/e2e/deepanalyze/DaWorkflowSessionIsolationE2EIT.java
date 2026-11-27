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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【探索性探针的**端到端复现**，不属设计场景、不进覆盖台账】跨会话隔离：PA / PB 在真实宿主上是否成立。
 *
 * <p>动机：§7G 的 L1t 探针在"共用一个 {@code DaWorkflowRunner} 实例"的前提下坐实了两条跨会话问题：
 * PA（{@code subagent_transcript(roots)} 泄露别的会话的原始提问与全部步骤产物）、
 * PB（{@code send_message} 的留言被另一个会话的同名成员取走 = 跨会话提示注入）。
 * 但**仍未排除**的一项是：真实请求究竟走"单例装配"（{@code DaEngineConfiguration:155}）
 * 还是"per-Task 工厂"（{@code DaAgentFactory:134}，Feature §4 口径边界所称的装配方式）。
 * 若线上只走 per-Task，则 PA/PB 不是活缺陷；若单例 bean 服务真实请求，则两者都是安全缺陷。
 *
 * <p>本 IT 用**两个不同的 sessionId**在同一个宿主进程上复现，直接判定走的是哪条路径。
 *
 * <p>对照判据（RULES 铁律 B 第 5 条）：同一宿主的 compat {@code /status/{taskId}} **有**属主校验
 * （{@code requireOwnedRecord}，异属主 404，FEAT-054 已测 IDOR 防护），而 {@code subagent_transcript}
 * 无任何会话维度 ⇒ 读侧口径不一致，缺校验的一侧默认按疑似缺陷上报。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-067")
@Feature("FEAT-067: 编排承接——会话内工作流工具组")
class DaWorkflowSessionIsolationE2EIT extends DaOrchestrationE2EBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private static final String SINGLE_MEMBER = """
            blueprint:
              id: T-ISO
              type: single
              members:
                - {id: m1, instruction: "用一句话作答"}
            """;

    @Test
    @Story("隔离性探针 PA（端到端）: 会话 B 不应经 subagent_transcript 读到会话 A 的原始提问")
    @DisplayName("PA-E2E 会话 A 先跑一次，会话 B 再查 subagent_transcript(roots)：不应看到 A 的提问原文")
    void sessionBCannotReadSessionAInputViaTranscript() throws Exception {
        final String secretA = "SECRET-QUESTION-OF-SESSION-A-" + RUN;
        final String sessionA = newSession("sess-iso-a");
        final String sessionB = newSession("sess-iso-b");
        final String askB = "请调用 subagent_transcript 工具，branch_id 参数用 roots，"
                + "然后把你查到的执行根清单原样列出来（包括每个根的 phases 内容）。不要省略任何字段。";

        // 会话 A：跑一次工作流（其原始提问会进入执行根的留痕载荷）
        final JsonNode runA = runAndSnapshot(sessionA,
                "请调用 workflow_run 工具执行下面这张图纸，question 参数用 " + secretA
                        + " ，blueprint 参数原样传入：\n" + SINGLE_MEMBER
                        + "\n工具返回后只回复 final_output 原文。");
        Allure.addAttachment("会话 A 的 /run 响应（截断 8KB）", "text/plain", truncate(runA.toString(), 8192));

        // 会话 B：查 roots
        final JsonNode runB = runAndSnapshot(sessionB, askB);
        Allure.addAttachment("会话 B 的 /run 响应（截断 16KB）", "text/plain", truncate(runB.toString(), 16384));
        final String rendered = runB.toString();
        System.out.println("[PROBE-PA-E2E] 会话 B 的响应里是否含 A 的提问: "
                + rendered.contains(secretA));
        assertThat(rendered)
                .as("会话 B **不应**能读到会话 A 的原始提问（%s）。若命中，说明真实请求走的是"
                        + "**单例装配路径**（DaEngineConfiguration:155 建一个 DaWorkflowRunner bean），"
                        + "审计森林跨会话共享且 subagent_transcript 无属主校验 ⇒ 信息泄露成立，应按安全缺陷提单；"
                        + "若未命中，则说明走 per-Task 工厂（Feature §4 口径），PA 降级为"
                        + "\"单例 bean 属误导性死代码、应清理\"", secretA)
                .doesNotContain(secretA);
    }
}
