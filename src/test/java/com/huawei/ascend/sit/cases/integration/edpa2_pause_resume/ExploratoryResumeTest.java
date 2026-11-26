/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa2_pause_resume;

import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-088 探索性测试：从 Bug #2 根因出发，发散测试恢复流程完整性。
 *
 * <p>测试方向：
 * 1. 恢复确认阶段暂停（Plan 是否已注册到活动索引）
 * 2. 恢复后回答业务问题（Plan 能否正常推进）
 * 3. 跨 context 恢复（不应跨 context 恢复）
 * 4. 双重恢复（第二次"继续"应当作业务输入）
 * 5. getTask 查询回执 Task（回执 Task 是否可查）
 * 6. 恢复后原 Plan revision 递增验证
 */
@DisplayName("FEAT-088 探索性测试：恢复流程完整性")
class ExploratoryResumeTest extends Edpa2PauseResumeTestBase {

    private static final String BUSINESS_PROMPT =
            "给我安排杭州3天旅行，只生成简单景点安排。出发日期和人数稍后告诉你，请先向我询问。";

    private static final long SUSPEND_SETTLE_MS = 10000;

    @Test
    @Story("resume-confirm-pause: 恢复确认阶段暂停")
    @DisplayName("FEAT-088.explore.resume-confirm-pause: 恢复后INPUT_REQUIRED阶段发暂停")
    void resumeConfirmPhasePause() {
        String contextId = ctx("explore-resume-confirm-pause");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 语义暂停 → ACCEPTED
        HttpResponse<String> pauseResp = sendSemanticPauseSync("暂停", contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("首次暂停应 ACCEPTED").isTrue();
        assertThat(extractReceiptTaskId(pauseResp.body()))
                .as("暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 恢复 → INPUT_REQUIRED（恢复确认阶段，Plan 仍保持挂起）
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume.state())
                .isTrue();
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        // Step 4: 恢复确认阶段发暂停 — Plan 仍保持挂起，应返回 NO_ACTIVE_PLAN
        // 设计文档: "等待恢复确认时 Plan 仍保持挂起，此时 INPUT_REQUIRED 仅表示等待确认，
        // 暂停入口返回 NO_ACTIVE_PLAN"
        HttpResponse<String> pauseDuringConfirm = sendPauseControlSync(contextId);
        System.out.println("[DEBUG explore resume-confirm-pause] pause during confirm=" + pauseDuringConfirm.body());
        assertThat(extractReceiptCode(pauseDuringConfirm.body()))
                .as("恢复确认阶段暂停应返回 NO_ACTIVE_PLAN（Plan 仍挂起），实际响应: %s", pauseDuringConfirm.body())
                .isEqualTo("NO_ACTIVE_PLAN");

        // Step 5: 确认恢复 → INPUT_REQUIRED（原问题重现，Plan 已恢复为活动状态）
        A2aRound confirm = sendA2a("确认", contextId, null);
        assertThat(confirm.taskId()).as("恢复确认应返回 taskId").isNotNull();
        assertThat(confirm.isInputRequired())
                .as("恢复确认后应重现原问题（INPUT_REQUIRED），实际状态: %s", confirm.state())
                .isTrue();

        // Step 6: 确认恢复后发暂停 — Plan 已恢复为活动状态，暂停应被受理
        HttpResponse<String> pause2 = sendPauseControlSync(contextId);
        System.out.println("[DEBUG explore resume-confirm-pause] second pause response=" + pause2.body());
        assertThat(hasAcceptedReceipt(pause2.body()))
                .as("恢复确认后暂停应被受理（ACCEPTED，存在活动 Plan），实际响应: %s", pause2.body())
                .isTrue();
        assertThat(extractReceiptTaskId(pause2.body()))
                .as("二次暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);
    }

    @Test
    @Story("resume-then-answer: 恢复后回答业务问题")
    @DisplayName("FEAT-088.explore.resume-then-answer: 恢复后回答日期人数能推进Plan")
    void resumeThenAnswerBusinessQuestion() {
        String contextId = ctx("explore-resume-answer");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 暂停 → ACCEPTED
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应 ACCEPTED").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 恢复 → INPUT_REQUIRED
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED）").isTrue();

        // Step 4: 回答业务问题 — 应推进 Plan（不应报错或当作恢复确认）
        // 设计文档 L18: "确认后恢复原 Plan/Todo 身份和本地 Skill 原问题"
        A2aRound answer = sendA2a("2人，明天出发", contextId, null);
        System.out.println("[DEBUG explore resume-answer] answer state=" + answer.state()
                + " taskId=" + answer.taskId());
        // 回答后应推进 Plan — 可能 COMPLETED（完成规划）或 INPUT_REQUIRED（继续追问）
        // 不应 FAILED 或 UNRECOGNIZED
        assertThat(answer.state())
                .as("回答后应推进 Plan（COMPLETED 或 INPUT_REQUIRED），实际状态: %s", answer.state())
                .isIn(TaskState.TASK_STATE_COMPLETED, TaskState.TASK_STATE_INPUT_REQUIRED);
        assertThat(answer.taskId()).as("回答应返回 taskId").isNotNull();
    }

    @Test
    @Story("cross-context-resume: 跨context恢复")
    @DisplayName("FEAT-088.explore.cross-context-resume: contextB不应恢复contextA的挂起任务")
    void crossContextResumeShouldFail() {
        String contextA = ctx("explore-cross-ctx-a");
        String contextB = ctx("explore-cross-ctx-b");

        // contextA: 业务请求 → INPUT_REQUIRED → 暂停
        A2aRound businessA = sendA2a(BUSINESS_PROMPT, contextA, null);
        assertThat(businessA.isInputRequired()).as("A 应到达 INPUT_REQUIRED").isTrue();
        String taskAId = businessA.taskId();

        HttpResponse<String> pauseA = sendPauseControlSync(contextA);
        assertThat(hasAcceptedReceipt(pauseA.body())).as("A 暂停应 ACCEPTED").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // contextB: 发"继续上一次任务" — 不应恢复 A 的挂起任务
        A2aRound resumeB = sendA2a("继续上一次任务", contextB, null);
        System.out.println("[DEBUG explore cross-context] B resume state=" + resumeB.state()
                + " taskId=" + resumeB.taskId());
        // B 没有挂起记录，"继续"应当作业务输入（非恢复）
        // 不应返回 A 的 taskId，不应触发恢复确认
        if (resumeB.taskId() != null) {
            assertThat(resumeB.taskId())
                    .as("B 的 taskId 不应等于 A 的 taskId（不跨 context 恢复）")
                    .isNotEqualTo(taskAId);
        }
    }

    @Test
    @Story("double-resume: 双重恢复")
    @DisplayName("FEAT-088.explore.double-resume: 第二次继续应当作业务输入")
    void doubleResumeSecondAsBusinessInput() {
        String contextId = ctx("explore-double-resume");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.isInputRequired()).as("应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 暂停 → ACCEPTED
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应 ACCEPTED").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 第一次"继续" → INPUT_REQUIRED（恢复确认）
        A2aRound resume1 = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume1.isInputRequired())
                .as("首次恢复应重现 INPUT_REQUIRED").isTrue();
        assertThat(resume1.taskId()).as("首次恢复 taskId").isNotNull();

        // Step 4: 第二次"继续" — 应当作业务输入，而非再次触发恢复
        // 设计文档 L18: "不把恢复确认当作业务参数或风险授权"
        // 此时 Plan 已恢复并处于 INPUT_REQUIRED，第二次"继续"应被模型当作业务文本
        A2aRound resume2 = sendA2a("继续上一次任务", contextId, null);
        System.out.println("[DEBUG explore double-resume] second resume state=" + resume2.state()
                + " taskId=" + resume2.taskId());
        // 第二次"继续"不应触发恢复流程（无挂起记录可恢复）
        // 应作为业务输入被处理 — COMPLETED 或 INPUT_REQUIRED
        assertThat(resume2.state())
                .as("第二次'继续'应作为业务输入被处理，实际状态: %s", resume2.state())
                .isIn(TaskState.TASK_STATE_COMPLETED, TaskState.TASK_STATE_INPUT_REQUIRED);
    }

    @Test
    @Story("gettask-receipt: 查询回执Task")
    @DisplayName("FEAT-088.explore.gettask-receipt: 暂停回执Task可查询且状态正确")
    void getTaskReceiptTaskTest() {
        String contextId = ctx("explore-gettask-receipt");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.isInputRequired()).as("应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 暂停 → ACCEPTED + receiptTaskId
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应 ACCEPTED").isTrue();
        String receiptTaskId = extractReceiptTaskId(pauseResp.body());
        assertThat(receiptTaskId)
                .as("回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        // Step 3: getTask 查询原业务 Task — 应可查询（不回滚）
        Task originalTask = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(originalTask).as("原任务应可查询").isNotNull();
        assertThat(originalTask.id()).as("原 taskId 应一致").isEqualTo(originalTaskId);

        // Step 4: getTask 查询回执 Task — 设计文档 L60: "独立回执 Task"
        // 回执 Task 是否可查？状态是什么？
        Task receiptTask = client(EDPA2_L1).getTask(receiptTaskId);
        System.out.println("[DEBUG explore gettask-receipt] receiptTask=" + receiptTask);
        // 回执 Task 应该可查询（设计文档说它是独立 Task）
        // 如果返回 null，说明回执 Task 不是持久化的 — 可能是 bug
        assertThat(receiptTask)
                .as("回执 Task 应可查询（独立 Task），receiptTaskId=%s", receiptTaskId)
                .isNotNull();
        assertThat(receiptTask.id())
                .as("回执 taskId 应一致")
                .isEqualTo(receiptTaskId);
        // 回执 Task 状态应为 COMPLETED（设计文档 L17: "独立回执 Task 的 COMPLETED"）
        assertThat(receiptTask.status().state())
                .as("回执 Task 状态应为 COMPLETED，实际: %s", receiptTask.status().state())
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }

    @Test
    @Story("resume-revision-increment: 恢复后Plan revision递增")
    @DisplayName("FEAT-088.explore.resume-revision: 恢复后新Task的contextId一致且taskId不同")
    void resumeRevisionIncrement() {
        String contextId = ctx("explore-revision");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.isInputRequired()).as("应到达 INPUT_REQUIRED").isTrue();
        String taskA = business.taskId();
        String ctxA = business.contextId();

        // Step 2: 暂停 → ACCEPTED
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应 ACCEPTED").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 恢复 → INPUT_REQUIRED
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.isInputRequired())
                .as("恢复后应重现 INPUT_REQUIRED").isTrue();
        String taskB = resume.taskId();
        String ctxB = resume.contextId();

        // contextId 应一致（同一会话）
        assertThat(ctxB)
                .as("恢复后 contextId 应与原会话一致")
                .isEqualTo(ctxA);

        // taskId 应不同（新 Task 承载恢复）
        assertThat(taskB)
                .as("恢复 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(taskA);

        // Step 4: 再次暂停→恢复 → taskId 再次不同
        HttpResponse<String> pause2 = sendPauseControlSync(contextId);
        String pause2Code = extractReceiptCode(pause2.body());
        System.out.println("[DEBUG explore revision] second pause code=" + pause2Code);

        if ("ACCEPTED".equals(pause2Code)) {
            sleep(SUSPEND_SETTLE_MS);
            A2aRound resume2 = sendA2a("继续上一次任务", contextId, null);
            assertThat(resume2.taskId()).as("二次恢复 taskId").isNotNull();
            assertThat(resume2.taskId())
                    .as("二次恢复 Task ID 应不同于首次恢复 Task ID（revision 递增）")
                    .isNotEqualTo(taskB);
            assertThat(resume2.taskId())
                    .as("二次恢复 Task ID 应不同于原业务 Task ID")
                    .isNotEqualTo(taskA);
        }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { /* ignore */ }
    }
}
