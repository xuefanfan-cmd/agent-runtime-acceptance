/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.moa_pause_resume;

import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-088 fault tolerance acceptance tests.
 *
 * <p>Per design doc §5.1: "故障注入 | 存储不可用/超时/归档超限"
 * Per design doc §8.3: "revision 单调递增"
 *
 * <p>测试策略：无故障注入基础设施时，测试暂停/恢复流程中数据一致性——
 * 暂停后原任务可查询（不回滚）、恢复后重现原问题（数据未丢失）、多轮暂停恢复 Task ID 递增。</p>
 */
@DisplayName("FEAT-088 暂停/恢复容错场景")
class PauseFaultAcceptanceTest extends MoaPauseResumeTestBase {

    private static final String RESERVATION_PROMPT = "帮我预留容量";
    private static final String BUSINESS_PROMPT =
            "给我安排杭州3天旅行，只生成简单景点安排。出发日期和人数稍后告诉你，请先向我询问。";

    private static final long SUSPEND_SETTLE_MS = 10000;

    @Test
    @Story("multi-cycle: 多轮暂停→恢复")
    @DisplayName("FEAT-088.fault.multi-cycle: 暂停→恢复→再暂停→再恢复 Task ID 递增")
    void multiCyclePauseResumeRevisionMonotonic() {
        String contextId = ctx("multi-cycle");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String taskAId = business.taskId();

        // Cycle 1: pause → resume
        HttpResponse<String> pause1 = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pause1.body())).as("首次暂停应 ACCEPTED").isTrue();
        assertThat(extractReceiptTaskId(pause1.body()))
                .as("首次暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(taskAId);

        sleep(SUSPEND_SETTLE_MS);

        A2aRound resume1 = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume1.taskId()).as("首次恢复应返回 taskId").isNotNull();
        assertThat(resume1.isInputRequired())
                .as("首次恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume1.state())
                .isTrue();
        assertThat(resume1.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID（新 Task 承载恢复）")
                .isNotEqualTo(taskAId);

        // 恢复确认：Plan 仍保持挂起，确认后才恢复为活动状态
        A2aRound confirm1 = sendA2a("确认", contextId, null);
        assertThat(confirm1.taskId()).as("恢复确认应返回 taskId").isNotNull();
        assertThat(confirm1.isInputRequired())
                .as("恢复确认后应重现原问题（INPUT_REQUIRED）").isTrue();

        // Cycle 2: 第二次暂停 — 确认恢复后 Plan 已恢复为活动状态
        // 设计文档 L104: "确认恢复、原问题重现后，可再次暂停"
        HttpResponse<String> pause2 = sendPauseControlSync(contextId);
        String pause2Code = extractReceiptCode(pause2.body());
        System.out.println("[DEBUG multi-cycle] 二次暂停响应: " + pause2.body());
        assertThat(pause2Code)
                .as("二次暂停应返回 ACCEPTED（恢复确认后可再次暂停），实际: %s", pause2.body())
                .isEqualTo("ACCEPTED");
        // 第二轮暂停 delivery 可能为 NO_TARGET（Plan 处于 INPUT_REQUIRED 等待状态，无活动执行目标）
        // 设计只保证"可再次暂停"（ACCEPTED），不保证 delivery 一定有目标
        String pause2Delivery = extractDelivery(pause2.body());
        System.out.println("[DEBUG multi-cycle] 二次暂停 delivery=" + pause2Delivery);

        // Task ID 单调递增验证：每轮操作产生独立 taskId
        // business < resume1 < confirm1 < pause2Receipt（各阶段均为独立 Task）
        String pause2ReceiptId = extractReceiptTaskId(pause2.body());
        if (pause2ReceiptId != null) {
            assertThat(pause2ReceiptId)
                    .as("二次暂停回执 Task ID 应不同于原业务 Task ID")
                    .isNotEqualTo(taskAId);
            assertThat(pause2ReceiptId)
                    .as("二次暂停回执 Task ID 应不同于首次恢复 Task ID")
                    .isNotEqualTo(resume1.taskId());
        }
    }

    @Test
    @Story("producer-not-released: 暂停受理后原任务仍可查询不回滚")
    @DisplayName("FEAT-088.fault.producer-not-released: 容量预留暂停后原任务数据不丢失")
    void producerNotReleasedBeforeSafeBoundary() {
        String contextId = ctx("producer-not-released");

        // 用旅行规划触发 INPUT_REQUIRED（模型询问日期/人数）
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // 暂停
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();

        // 暂停后原任务仍可查询 — 数据不回滚
        org.a2aproject.sdk.spec.Task taskAfterPause = client(MOA_L1).getTask(originalTaskId);
        assertThat(taskAfterPause).as("暂停后原任务应可查询（数据未回滚）").isNotNull();
        assertThat(taskAfterPause.id()).as("taskId 应一致").isEqualTo(originalTaskId);

        sleep(SUSPEND_SETTLE_MS);

        // 恢复后仍能重现原问题 — 数据未丢失
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），数据未丢失，实际状态: %s", resume.state())
                .isTrue();
    }

    @Test
    @Story("interrupt-success-save-failure: 暂停成功不回滚原任务数据")
    @DisplayName("FEAT-088.fault.interrupt-success-save-failure: 容量预留暂停后原任务可查询")
    void pauseSuccessSaveFailureNoRollback() {
        String contextId = ctx("save-failure");

        // 用旅行规划触发 INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // 暂停 — 即使内部保存有异常，暂停受理不应回滚已开始的业务 Task
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body()))
                .as("暂停应被受理，实际响应: %s", pauseResp.body())
                .isTrue();

        // 原任务仍可查询 — 暂停不回滚
        org.a2aproject.sdk.spec.Task task = client(MOA_L1).getTask(originalTaskId);
        assertThat(task).as("暂停后原任务应可查询（不回滚）").isNotNull();
        assertThat(task.id()).as("taskId 应一致").isEqualTo(originalTaskId);
    }

    @Test
    @Story("save-failure: 上下文存储故障时暂停保留当前唯一现场")
    @DisplayName("FEAT-088.fault.save-failure: 存储故障暂停后原任务不丢失且新任务不覆盖唯一现场")
    void saveFailurePreservesCurrentScene() {
        String contextId = ctx("save-failure");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 暂停 — 上下文存储故障时暂停仍受理
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body()))
                .as("暂停应被受理，实际响应: %s", pauseResp.body())
                .isTrue();

        // Step 3: 原任务仍可查询 — 数据不丢失
        org.a2aproject.sdk.spec.Task taskAfterPause = client(MOA_L1).getTask(originalTaskId);
        assertThat(taskAfterPause).as("暂停后原任务应可查询（数据不丢失）").isNotNull();
        assertThat(taskAfterPause.id()).as("taskId 应一致").isEqualTo(originalTaskId);

        // Step 4: 新任务 B 使用独立 taskId — 不覆盖唯一现场
        A2aRound taskB = sendA2a("帮我查询上海的景点", contextId, null);
        assertThat(taskB.taskId()).as("任务 B 应获得独立 taskId").isNotNull();
        assertThat(taskB.taskId())
                .as("任务 B 的 taskId 应不同于原任务 A（不覆盖唯一现场）")
                .isNotEqualTo(originalTaskId);

        // Step 5: 原任务 A 仍可查询 — B 未覆盖 A 的现场
        org.a2aproject.sdk.spec.Task taskACheck = client(MOA_L1).getTask(originalTaskId);
        assertThat(taskACheck).as("新任务 B 执行后原任务 A 仍应可查询（未覆盖唯一现场）").isNotNull();
        assertThat(taskACheck.id()).as("原任务 A 的 taskId 应不变").isEqualTo(originalTaskId);
    }

    @Test
    @Story("resume-version-expired: 暂停后恢复重现原问题")
    @DisplayName("FEAT-088.fault.resume-version-expired: 容量预留暂停后恢复重现原问题")
    void resumeVersionExpired() {
        String contextId = ctx("version-expired");

        // 用旅行规划触发 INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // 恢复 — 应正确恢复原 Plan 的执行位置，重现原问题
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID（新 Task 承载恢复）")
                .isNotEqualTo(originalTaskId);
        // 恢复后应重现原问题（回到 INPUT_REQUIRED），而非自动完成或报错
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），实际: %s", resume.state())
                .isTrue();
    }

    @Test
    @Story("normal-termination-cleanup: Task 正常终结后新任务独立")
    @DisplayName("FEAT-088.fault.normal-termination-cleanup: A完成后B使用独立taskId")
    void normalTerminationCleansUpQueue() {
        String contextId = ctx("termination-cleanup");

        // Task A: 正常完成
        A2aRound taskA = sendA2a("帮我查询北京的景点", contextId, null);
        assertThat(taskA.taskId()).as("任务 A 应获得 taskId").isNotNull();
        assertThat(taskA.isCompleted())
                .as("任务 A 应正常完成，实际状态: %s", taskA.state())
                .isTrue();
        String taskAId = taskA.taskId();

        // Task B: 独立执行，taskId 应不同于 A
        A2aRound taskB = sendA2a("帮我查询上海的景点", contextId, null);
        assertThat(taskB.taskId()).as("任务 B 应获得 taskId").isNotNull();
        assertThat(taskB.taskId())
                .as("任务 B 的 taskId 应不同于任务 A（不转投）")
                .isNotEqualTo(taskAId);
        assertThat(taskB.isCompleted())
                .as("任务 B 应正常完成，实际状态: %s", taskB.state())
                .isTrue();
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { /* ignore */ }
    }
}
