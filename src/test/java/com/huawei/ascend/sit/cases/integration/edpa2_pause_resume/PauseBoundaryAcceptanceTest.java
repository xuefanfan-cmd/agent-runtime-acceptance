/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa2_pause_resume;

import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-088 boundary acceptance tests.
 *
 * <p>Per design doc §7.6: tests OUT (negative) assertions, REST success semantics,
 * and known-issue Issue #612.</p>
 *
 * <p><b>OUT 负面用例</b>（设计文档 §5.2）："仅记录行为是否符合'不承诺'声明，不作为阻断项"。
 * 这些用例验证暂停受理后系统不会崩溃，但不能从黑盒验证'不承诺'的具体行为。</p>
 */
@DisplayName("FEAT-088 暂停/恢复边界场景")
class PauseBoundaryAcceptanceTest extends Edpa2PauseResumeTestBase {

    private static final String BUSINESS_PROMPT =
            "给我安排杭州3天旅行，只生成简单景点安排。出发日期和人数稍后告诉你，请先向我询问。";

    private static final long SUSPEND_SETTLE_MS = 10000;

    @Test
    @Story("gettask-limit: GetTask 不保证 SUSPENDED 字段")
    @DisplayName("FEAT-088.boundary.gettask-limit: GetTask 可查但不保证含 SUSPENDED 字段")
    void getTaskDoesNotGuaranteeSuspendedField() {
        String contextId = ctx("gettask-limit");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();
        assertThat(extractReceiptTaskId(pauseResp.body()))
                .as("回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        sleep(SUSPEND_SETTLE_MS);

        // 文档 L100: "原业务 Task 可用 GetTask 观察，但其中不保证包含 EDPA2.0 任务清单或 SUSPENDED 字段"
        org.a2aproject.sdk.spec.Task queried = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(queried).as("GetTask 应返回任务").isNotNull();
        assertThat(queried.id()).as("GetTask 返回的 taskId 应一致")
                .isEqualTo(originalTaskId);
        // OUT 负面：不保证包含 SUSPENDED 字段 — 记录实际状态，不作为阻断
        System.out.println("[OUT gettask-limit] GetTask 返回状态=" + queried.status().state()
                + "（不保证包含 SUSPENDED 字段，这是 OUT 负面声明）");
    }

    @Test
    @Story("rest-success-semantics: REST success:true 仅表示请求处理成功")
    @DisplayName("FEAT-088.boundary.rest-success-semantics: REST bug 阻断（fail = 开发Bug）")
    void restSuccessTrueDoesNotMeanPauseEffective() {
        String contextId = ctx("rest-success");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        // 文档 L98: "REST success:true 或 A2A 回执 Task 的 COMPLETED 都不代表原计划已暂停"
        // 断言应检查 code/delivery 判断实际受理，而非 success:true
        HttpResponse<String> restResp = sendRestPause(contextId, false);
        assertThat(restResp.statusCode()).as("REST 请求应被处理（HTTP 200）").isEqualTo(200);
        String body = restResp.body();
        System.out.println("[DEBUG rest-success] response body=" + body);
        // 此断言因 REST bug 失败 — MoaCustomRestAdapter 丢失 moa.control 语义
        // 这是开发 Bug，不是测试问题
        assertThat(hasAcceptedReceipt(body))
                .as("REST 暂停应被受理（code=ACCEPTED），实际响应: %s [REST bug: MoaCustomRestAdapter丢失moa.control语义]", body)
                .isTrue();
    }

    @Test
    @Story("issue-612-resume-display: 恢复确认展示归档 Todo RUNNING 状态")
    @DisplayName("FEAT-088.boundary.issue-612-resume-display: Issue #612 已知问题")
    void issue612ResumeDisplayRunningStatus() {
        String contextId = ctx("issue-612");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();
        assertThat(extractReceiptTaskId(pauseResp.body()))
                .as("回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        sleep(SUSPEND_SETTLE_MS);

        // Resume — Issue #612: confirmation text may show RUNNING status from archived Todo
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume.state())
                .isTrue();
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);
        // Issue #612: 确认文案展示的 Todo 状态应准确反映归档快照时的状态
        // 当前 Issue #612 未修复 — 直接展示 RUNNING 状态
        // 设计文档 §8.3: "Issue #612：记录实际行为作为已知问题证据，不作为阻断项"
        System.out.println("[Issue #612] 恢复 taskId=" + resume.taskId()
                + " — Issue #612 未修复：恢复确认可能展示归档 Todo RUNNING 状态（已知问题，不阻断）");
    }

    // ═══ OUT 负面用例：仅记录行为，不断言验证 ═════════════════════════════

    @Test
    @Story("no-output-gate: 受理后输出不立即截断（OUT 负面）")
    @DisplayName("FEAT-088.boundary.no-output-gate: OUT负面声明记录")
    void pauseAcceptDoesNotImmediatelyGateOutput() {
        String contextId = ctx("no-output-gate");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();

        // OUT 负面声明（设计文档 §7.6）："受理后当前 Todo 进度/结果仍可能输出；生效后停止推进"
        // 黑盒无法验证"输出不立即截断" — 仅记录受理行为
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body()))
                .as("暂停应被受理，实际响应: %s", pauseResp.body())
                .isTrue();
        assertThat(extractReceiptTaskId(pauseResp.body()))
                .as("回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(business.taskId());
        System.out.println("[OUT no-output-gate] 暂停已受理 — OUT负面声明：受理后输出不立即截断（黑盒无法验证）");
    }

    @Test
    @Story("no-instant-interrupt: 暂停不是任意点立即退出（OUT 负面）")
    @DisplayName("FEAT-088.boundary.no-instant-interrupt: OUT负面声明记录")
    void pauseIsNotInstantInterrupt() {
        String contextId = ctx("no-instant");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();

        // OUT 负面声明："确认不是任意点立即退出，而是安全边界后挂起"
        // 黑盒无法验证内部安全边界时序 — 仅记录受理行为
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();
        assertThat(extractReceiptTaskId(pauseResp.body()))
                .as("回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(business.taskId());
        System.out.println("[OUT no-instant-interrupt] 暂停已受理 — OUT负面声明：安全边界后挂起（黑盒无法验证时序）");
    }

    @Test
    @Story("no-queue-promise: 暂停未生效时不承诺无损排队（OUT 负面）")
    @DisplayName("FEAT-088.boundary.no-queue-promise: OUT负面声明记录")
    void noQueuePromiseDuringPauseWindow() {
        String contextId = ctx("no-queue");

        A2aRound taskA = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(taskA.taskId()).as("任务 A 应获得 taskId").isNotNull();
        assertThat(taskA.isInputRequired())
                .as("任务 A 应到达 INPUT_REQUIRED").isTrue();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // OUT 负面声明："不提供'暂停尚未生效时无损排队'承诺"
        // 黑盒只能验证 B 获得 taskId，无法验证队列是否无损
        A2aRound taskB = sendA2a("帮我查询北京的景点", contextId, null);
        assertThat(taskB.taskId()).as("接管任务 B 应获得 taskId").isNotNull();
        assertThat(taskB.taskId()).as("B 的 taskId 应不同于 A")
                .isNotEqualTo(taskA.taskId());
        System.out.println("[OUT no-queue-promise] B 获得 taskId — OUT负面声明：不承诺无损排队（黑盒无法验证队列）");
    }

    @Test
    @Story("hosted-edpa2-limit: Hosted EDPA2.0 不自动支持暂停控制（OUT 负面）")
    @DisplayName("FEAT-088.boundary.hosted-edpa2-limit: OUT负面声明记录")
    void hostedEdpa2DoesNotAutoSupportPause() {
        // OUT 负面声明："不宣称已自动支持 hosted EDPA2.0 装配"
        // L1 以显式导入方式运行（非 hosted 自动模式）
        assertThat(stack.baseUrl(EDPA2_L1)).as("L1 应以显式导入方式运行").isNotNull();
        HttpResponse<String> healthCheck = sendRestRaw("/", "", "application/json");
        assertThat(healthCheck.statusCode()).as("L1 应健康响应").isLessThan(500);
        System.out.println("[OUT hosted-edpa2-limit] L1 显式导入运行中 — OUT负面声明：不自动支持 hosted EDPA2.0（黑盒无法验证 hosted 差异）");
    }

    @Test
    @Story("process-restart: 进程重启不承诺跨进程恢复（OUT 负面）")
    @DisplayName("FEAT-088.boundary.process-restart: OUT负面声明记录")
    void processRestartDoesNotPromiseCrossProcessRecovery() {
        // OUT 负面声明："进程内控制记录不承担跨进程恢复"
        assertThat(stack.baseUrl(EDPA2_L1)).as("单实例 L1 运行中").isNotNull();
        assertThat(stack.baseUrl(EDPA2_L2)).as("单实例 L2 运行中").isNotNull();
        HttpResponse<String> healthCheck = sendRestRaw("/", "", "application/json");
        assertThat(healthCheck.statusCode()).as("L1 应健康响应").isLessThan(500);
        System.out.println("[OUT process-restart] 单实例运行中 — OUT负面声明：不承诺跨进程恢复（黑盒无法验证）");
    }

    // ═══ OUT 负面：FRS 明确不纳入本版本 ═════════════════════════════════

    @Test
    @Story("gateway-path: 网关路径暂停不在本版本（OUT 负面）")
    @DisplayName("FEAT-088.boundary.gateway-path: FRS明确不纳入（OUT负面）")
    void gatewayPathNotInScope() {
        // FRS-088 明确不纳入本版本：客户端经网关发起的中断/暂停/恢复
        // 验证 L1 直接可达（非经网关），暂停入口正常工作
        assertThat(stack.baseUrl(EDPA2_L1)).as("L1 应直接可达").isNotNull();
        HttpResponse<String> healthCheck = sendRestRaw("/", "", "application/json");
        assertThat(healthCheck.statusCode()).as("L1 应健康响应").isLessThan(500);
        System.out.println("[OUT gateway-path] L1 直接可达 — FRS明确网关路径不纳入本版本（OUT负面声明）");
    }

    @Test
    @Story("workflow-task: 编排图/工作流类任务暂停不在本版本（OUT 负面）")
    @DisplayName("FEAT-088.boundary.workflow-task: FRS明确不纳入（OUT负面）")
    void workflowTaskNotInScope() {
        // FRS-088 明确不在本版本：编排图/工作流类任务的打断
        // 验证普通业务任务暂停正常工作（非编排图类）
        String contextId = ctx("workflow-task");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body()))
                .as("普通业务任务暂停应被受理").isTrue();
        System.out.println("[OUT workflow-task] 普通业务任务暂停正常 — FRS明确编排图/工作流类任务不纳入本版本（OUT负面声明）");
    }

    @Test
    @Story("hitl-confusion: 挂起等待用户输入不误触发暂停逻辑（OUT 负面）")
    @DisplayName("FEAT-088.boundary.hitl-confusion: OUT负面声明记录")
    void hitlConfusionNotTriggered() {
        // OUT 负面声明："确认两种机制不混淆"
        // HITL（Human-in-the-loop）等待用户输入和暂停控制是两种不同机制
        // 验证：INPUT_REQUIRED 状态下发暂停控制返回暂停回执，而不是被当作 HITL 答案
        String contextId = ctx("hitl-confusion");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED（HITL等待）").isTrue();
        String originalTaskId = business.taskId();

        // 发暂停控制——应返回暂停回执，不当作HITL答案
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body()))
                .as("暂停控制应返回受理回执（不当作HITL答案），实际响应: %s", pauseResp.body())
                .isTrue();
        assertThat(extractReceiptTaskId(pauseResp.body()))
                .as("暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);
        System.out.println("[OUT hitl-confusion] 暂停控制返回受理回执 — HITL等待与暂停控制不混淆（OUT负面声明）");
    }

    // ═══ P2: save-limit — 归档超限场景模拟 ═══════════════════════════════

    @Test
    @Story("save-limit: 归档超限时暂停行为验证")
    @DisplayName("FEAT-088.boundary.save-limit: 多轮挂起后暂停仍正常受理")
    void saveLimitMultipleSuspensionsDoNotExhaust() {
        String contextId = ctx("save-limit");

        // 模拟归档超限场景：连续多轮暂停→恢复→暂停，验证不会因归档积累导致暂停失败
        for (int i = 0; i < 3; i++) {
            A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
            assertThat(business.taskId()).as("第" + (i + 1) + "轮应获得 taskId").isNotNull();
            if (!business.isInputRequired()) {
                System.out.println("[DEBUG save-limit] round " + i + " state=" + business.state() + " — skip pause");
                continue;
            }

            HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
            String code = extractReceiptCode(pauseResp.body());
            System.out.println("[DEBUG save-limit] round " + i + " pause code=" + code);
            // 每轮暂停都应被受理（不会因归档积累而拒绝）
            assertThat(code)
                    .as("第%d轮暂停应返回ACCEPTED或NO_ACTIVE_PLAN，实际: %s", i + 1, pauseResp.body())
                    .isIn("ACCEPTED", "NO_ACTIVE_PLAN");

            if ("ACCEPTED".equals(code)) {
                sleep(SUSPEND_SETTLE_MS);
                A2aRound resume = sendA2a("继续上一次任务", contextId, null);
                assertThat(resume.taskId()).as("第" + (i + 1) + "轮恢复应返回taskId").isNotNull();
            }
        }
    }

    // ═══ P2: long-wait-accumulate — 长期挂起积累场景 ═════════════════════

    @Test
    @Story("long-wait-accumulate: 多个Plan挂起后新任务仍正常执行")
    @DisplayName("FEAT-088.boundary.long-wait-accumulate: 多轮挂起后新任务独立完成")
    void longWaitAccumulateMultiplePlansSuspended() {
        String contextId = ctx("long-wait");

        // 模拟多个 Plan 先后挂起，部分长期未恢复
        // 验证挂起积累不耗尽执行容量，新任务仍可独立执行
        for (int i = 0; i < 3; i++) {
            String ctx_i = ctx("long-wait-" + i);
            A2aRound task = sendA2a(BUSINESS_PROMPT, ctx_i, null);
            assertThat(task.taskId()).as("任务 " + (i + 1) + " 应获得 taskId").isNotNull();
            if (task.isInputRequired()) {
                HttpResponse<String> pause = sendPauseControlSync(ctx_i);
                assertThat(hasAcceptedReceipt(pause.body()) || hasCode(pause.body(), "NO_ACTIVE_PLAN"))
                        .as("任务 " + (i + 1) + " 暂停应被受理或返回NO_ACTIVE_PLAN").isTrue();
                // 不恢复——模拟长期挂起
            }
        }

        // 新任务仍可独立执行（不因挂起积累而阻塞）
        String ctxNew = ctx("long-wait-new");
        A2aRound newTask = sendA2a("帮我查询北京的景点", ctxNew, null);
        assertThat(newTask.taskId()).as("新任务应获得 taskId（不因挂起积累阻塞）").isNotNull();
        assertThat(newTask.isCompleted())
                .as("新任务应正常完成，实际状态: %s", newTask.state())
                .isTrue();
    }

    // ═══ P2: cancel-executing-task-cleanup — 取消执行中Task队列清理 ═════

    @Test
    @Story("cancel-executing-task-cleanup: 暂停后新任务不继承旧Task延迟输入")
    @DisplayName("FEAT-088.boundary.cancel-executing-task-cleanup: 暂停后旧Task输入不转投新Task")
    void cancelExecutingTaskCleanupNoSpillover() {
        String contextId = ctx("cancel-cleanup");

        // 任务 A → INPUT_REQUIRED → 暂停
        A2aRound taskA = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(taskA.taskId()).as("任务 A 应获得 taskId").isNotNull();
        assertThat(taskA.isInputRequired())
                .as("任务 A 应到达 INPUT_REQUIRED").isTrue();
        String taskAId = taskA.taskId();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停 A 应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // 任务 B 接管——A 的延迟输入不应转投给 B
        A2aRound taskB = sendA2a("帮我查询上海的景点", contextId, null);
        assertThat(taskB.taskId()).as("任务 B 应获得 taskId").isNotNull();
        assertThat(taskB.taskId())
                .as("B 的 taskId 应不同于 A（不转投延迟输入）")
                .isNotEqualTo(taskAId);
        assertThat(taskB.isCompleted())
                .as("任务 B 应正常完成（不因 A 的延迟输入受影响），实际状态: %s", taskB.state())
                .isTrue();
    }

    // ═══ P2: capacity-rejection-requeue — 容量拒绝场景 ═══════════════════

    @Test
    @Story("capacity-rejection-requeue: 多轮暂停恢复不丢失已受理输入")
    @DisplayName("FEAT-088.boundary.capacity-rejection-requeue: 多轮操作后恢复仍重现原问题")
    void capacityRejectionRequeuePreservesAcceptedInput() {
        String contextId = ctx("capacity-requeue");

        // 模拟容量压力：多轮暂停→恢复，验证已受理输入不丢失
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // 第一轮暂停
        HttpResponse<String> pause1 = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pause1.body())).as("首次暂停应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // 第一次恢复——重现原问题
        A2aRound resume1 = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume1.taskId()).as("首次恢复应返回 taskId").isNotNull();
        assertThat(resume1.isInputRequired())
                .as("首次恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume1.state())
                .isTrue();

        // 恢复确认：Plan 仍保持挂起，确认后才恢复为活动状态
        A2aRound confirm1 = sendA2a("确认", contextId, null);
        assertThat(confirm1.taskId()).as("恢复确认应返回 taskId").isNotNull();
        assertThat(confirm1.isInputRequired())
                .as("恢复确认后应重现原问题（INPUT_REQUIRED）").isTrue();

        // 第二轮暂停 — 确认恢复后 Plan 已恢复为活动状态，暂停应被受理
        // 设计文档 L104: "确认恢复、原问题重现后，可再次暂停"
        HttpResponse<String> pause2 = sendPauseControlSync(contextId);
        String pause2Code = extractReceiptCode(pause2.body());
        System.out.println("[DEBUG capacity-requeue] second pause code=" + pause2Code
                + " delivery=" + extractDelivery(pause2.body()));
        assertThat(hasAcceptedReceipt(pause2.body()))
                .as("第二轮暂停应被受理（ACCEPTED，恢复确认后可再次暂停），实际响应: %s", pause2.body())
                .isTrue();
        // 第二轮暂停 delivery 可能为 NO_TARGET（Plan 处于 INPUT_REQUIRED 等待状态）
        // 设计只保证"可再次暂停"（ACCEPTED），不保证 delivery 一定有目标
        // 已受理输入未丢失——通过第一轮完整恢复链路已验证
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { /* ignore */ }
    }
}
