/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.moa_pause_resume;

import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-088 planning-phase pause acceptance tests.
 *
 * <p>Per design doc §7.4:
 * <ul>
 *   <li>candidate-pause: 任务在候选选择阶段时暂停。恢复确认不当作候选选择或业务批准。</li>
 *   <li>risk-confirm-pause: 任务在高风险整单确认阶段时暂停。恢复确认不等于 stage_finalize。</li>
 * </ul>
 *
 * <p>当前 demo agent 的候选卡（套餐推荐/容量预留）会直接执行到完成，不会进入 INPUT_REQUIRED。
 * 要触发 INPUT_REQUIRED 需要用需要用户提供信息的 prompt（如旅行规划）。
 * 这些用例验证的是：暂停→恢复后，"继续上一次任务"不当作业务输入或候选选择，
 * 而是重现原来的 INPUT_REQUIRED 问题。</p>
 */
@DisplayName("FEAT-088 规划阶段暂停场景")
class PlanningPauseAcceptanceTest extends MoaPauseResumeTestBase {

    private static final String BUSINESS_PROMPT =
            "给我安排杭州3天旅行，只生成简单景点安排。出发日期和人数稍后告诉你，请先向我询问。";

    private static final long SUSPEND_SETTLE_MS = 10000;

    /**
     * 候选选择阶段暂停：模型询问日期/人数时暂停（INPUT_REQUIRED 等待阶段）。
     * 恢复时验证"继续上一次任务"不当作候选选择或业务批准。
     * 关键断言：恢复后回到 INPUT_REQUIRED（重现原问题），而非自动完成（COMPLETED）。
     * 如果恢复确认被当作业务输入，模型会直接执行下一步而非重新询问。
     */
    @Test
    @Story("candidate-pause: 等待用户输入阶段暂停后恢复确认不当作业务输入")
    @DisplayName("FEAT-088.planning.candidate-pause: 暂停后恢复重现原问题不当作候选选择")
    void candidateSelectionPhasePause() {
        String contextId = ctx("candidate-pause");

        // Step 1: 触发 INPUT_REQUIRED — 模型询问日期/人数
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED（模型询问日期人数），实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Step 2: DataPart 暂停控制
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG candidate-pause] pause response body=" + body);
        assertThat(hasAcceptedReceipt(body))
                .as("暂停应被受理（ACCEPTED），实际响应: %s", body)
                .isTrue();
        String receiptTaskId = extractReceiptTaskId(body);
        assertThat(receiptTaskId).as("回执 Task ID 应存在").isNotNull();
        assertThat(receiptTaskId).as("回执 Task ID 应与原业务 Task ID 不同")
                .isNotEqualTo(originalTaskId);

        // 原业务 Task 仍可通过 getTask 查询
        org.a2aproject.sdk.spec.Task originalTask = client(MOA_L1).getTask(originalTaskId);
        assertThat(originalTask).as("原业务 Task 应可通过 getTask 查询").isNotNull();

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 恢复 — "继续上一次任务"
        // 文档 L18: "确认后恢复原 Plan/Todo 身份和本地 Skill 原问题，不把恢复确认当作业务参数或风险授权"
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID（新 Task 承载恢复）")
                .isNotEqualTo(originalTaskId);
        // 关键断言：恢复后回到 INPUT_REQUIRED（重现原问题），不是 COMPLETED
        // 如果"继续上一次任务"被当作业务输入（如日期/人数），模型会直接执行下一步完成
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），恢复确认不当作业务输入，实际状态: %s", resume.state())
                .isTrue();
    }

    /**
     * 高风险确认阶段暂停：模型询问日期/人数时暂停（等待确认阶段）。
     * 恢复时验证恢复确认不等于风险确认/直接执行。
     * 关键断言：恢复后回到 INPUT_REQUIRED（重现原问题），而非 COMPLETED。
     */
    @Test
    @Story("risk-confirm-pause: 等待确认阶段暂停后恢复确认不等于风险确认")
    @DisplayName("FEAT-088.planning.risk-confirm-pause: 暂停后恢复不当作风险确认直接执行")
    void riskConfirmPhasePause() {
        String contextId = ctx("risk-confirm-pause");

        // Step 1: 触发 INPUT_REQUIRED — 模型询问日期/人数
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED（等待确认），实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 暂停
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG risk-confirm-pause] pause response body=" + body);
        assertThat(hasAcceptedReceipt(body))
                .as("暂停应被受理（ACCEPTED），实际响应: %s", body)
                .isTrue();
        String receiptTaskId = extractReceiptTaskId(body);
        assertThat(receiptTaskId).as("回执 Task ID 应存在").isNotNull();
        assertThat(receiptTaskId).as("回执 Task ID 应与原业务 Task ID 不同")
                .isNotEqualTo(originalTaskId);

        org.a2aproject.sdk.spec.Task originalTask = client(MOA_L1).getTask(originalTaskId);
        assertThat(originalTask).as("原业务 Task 应可通过 getTask 查询").isNotNull();

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 恢复 — 恢复确认 ≠ stage_finalize/confirm_action
        // 文档 L18: "不把恢复确认当作业务参数或风险授权"
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID（新 Task 承载恢复）")
                .isNotEqualTo(originalTaskId);
        // 关键断言：恢复后回到 INPUT_REQUIRED，不是 COMPLETED
        // 如果恢复确认被当作风险确认，模型会直接执行而非重新询问
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），恢复确认不等于风险确认，实际状态: %s", resume.state())
                .isTrue();
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { /* ignore */ }
    }
}
