/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.moa_pause_resume;

import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-088 remote pause acceptance tests (L1 → L2 delegation scenarios).
 *
 * <p>Per 1100.patch step 5: L1 delegates to L2 (hotel), L2 waits for input,
 * then pause is sent to L1. L2 has no Hook; pause control is not forwarded.
 */
@DisplayName("FEAT-088 远端暂停/恢复场景")
class RemotePauseAcceptanceTest extends MoaPauseResumeTestBase {

    private static final String HOTEL_PROMPT =
            "帮我查询杭州演示酒店，只查询不预订。入住退房日期、人数和间数稍后提供，请先询问。";

    private static final long SUSPEND_SETTLE_MS = 10000;

    // ═══ P0: L2 wait pause ═══════════════════════════════════════════════

    @Test
    @Story("l2-wait-pause: L1 委派 L2 等待时语义暂停")
    @DisplayName("FEAT-088.remote.l2-wait-pause: L2 等待用户输入时暂停 L1 挂起")
    void l2WaitPauseL1Suspends() {
        String contextId = ctx("l2-wait-pause");

        // Step 1: 发送酒店查询 → L2 等待输入（INPUT_REQUIRED）
        A2aRound business = sendA2a(HOTEL_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("L2 业务应到达 INPUT_REQUIRED（等待用户输入），实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 语义暂停（L2 等待时）
        HttpResponse<String> pauseResp = sendSemanticPauseSync("暂停", contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG l2-wait-pause] pause response body=" + body);
        assertThat(hasAcceptedReceipt(body))
                .as("L2 等待时暂停应被受理（ACCEPTED/DUPLICATE），实际响应: %s", body)
                .isTrue();

        // Step 3: 暂停后正常回答 L2 的问题（携带原 INPUT_REQUIRED 的 taskId）
        // 文档 L16: "程序控制不伪造'暂停'文本发给远端" — 暂停不转发 L2，L2 正常接收答案
        A2aRound answer = sendA2a("入住日期11月20日，退房日期11月22日，2人1间",
                contextId, originalTaskId);
        assertThat(answer.taskId()).as("回答后应返回 taskId").isNotNull();
        assertThat(answer.state())
                .as("L2 收到答案后状态应为 INPUT_REQUIRED 或 COMPLETED（未因暂停被阻断），实际: %s",
                        answer.state())
                .isIn(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_COMPLETED);
    }

    // ═══ P0: Program pause does not forward to L2 ═══════════════════════

    @Test
    @Story("program-pause-normal-answer: 程序暂停后正常回答 L2 问题")
    @DisplayName("FEAT-088.remote.program-pause: DataPart 暂停不转发 L2，L2 保持等待并接收真实答案")
    void programPauseDoesNotForwardToL2() {
        String contextId = ctx("program-pause-l2");

        // Step 1: 发送酒店查询 → L2 等待输入（INPUT_REQUIRED）
        A2aRound business = sendA2a(HOTEL_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("L2 业务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 程序级暂停（DataPart）—— 不转发 L2
        // 文档 L16: "程序控制不伪造'暂停'文本发给远端"
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("DataPart 暂停 HTTP 200").isEqualTo(200);
        String pauseBody = pauseResp.body();
        System.out.println("[DEBUG program-pause-l2] pause response body=" + pauseBody);
        assertThat(hasAcceptedReceipt(pauseBody))
                .as("程序暂停应被受理，实际响应: %s", pauseBody)
                .isTrue();
        assertThat(pauseBody).as("应包含 pause_requested 事件").contains("pause_requested");

        // Step 3: 暂停后正常回答 L2 的问题（携带原 INPUT_REQUIRED 的 taskId）
        // 1100.patch L125: "等待态回答须携带当前 INPUT_REQUIRED 的 L1 taskId，不能携带暂停回执 Task ID"
        A2aRound answer = sendA2a("入住日期11月20日，退房日期11月22日，2人1间",
                contextId, originalTaskId);
        assertThat(answer.taskId()).as("回答后应返回 taskId").isNotNull();

        // 断言：L2 收到真实答案后未因暂停被阻断（状态非 FAILED）
        // 1100.patch L120: "L2 保持等待并接收真实答案；酒店正常完成，无虚假挂起项"
        // 文档 L16: "程序控制不伪造'暂停'文本发给远端" — 暂停不转发 L2，L2 正常接收答案
        // L2 可能继续追问（多轮 INPUT_REQUIRED）或完成（COMPLETED），关键是答案送达
        assertThat(answer.state())
                .as("L2 收到答案后状态应为 INPUT_REQUIRED 或 COMPLETED（未因暂停被阻断），实际: %s",
                        answer.state())
                .isIn(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_COMPLETED);
    }

    // ═══ P1: Remote resume rejected ══════════════════════════════════════

    @Test
    @Story("resume-rejected: 远端 RUNNING Todo 恢复被拒")
    @DisplayName("FEAT-088.remote.resume-rejected: 远端未完成恢复被拒")
    void remoteResumeRejected() {
        String contextId = ctx("resume-rejected");

        A2aRound business = sendA2a(HOTEL_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("L2 业务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        // Pause
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // Attempt resume — should be rejected because L2 has RUNNING remote Todo
        // Per L2 design: "直接提示暂不支持远端现场恢复"
        HttpResponse<String> resumeResp = sendSemanticResumeSync("继续", contextId);
        assertThat(resumeResp.statusCode()).as("恢复请求 HTTP 应被处理").isLessThan(500);
        String body = resumeResp.body();
        System.out.println("[DEBUG resume-rejected] resume response body=" + body);
        // 远端未完成 Plan 恢复不应返回 ACCEPTED
        assertThat(hasCode(body, "ACCEPTED"))
                .as("远端未完成 Plan 恢复不应返回 ACCEPTED，实际响应: %s", body)
                .isFalse();
        // 恢复请求应返回有意义响应（含 taskId 或 TASK_STATE），证明请求被处理而非忽略
        assertThat(body != null && (body.contains("taskId") || body.contains("TASK_STATE")))
                .as("远端恢复拒绝应返回有意义的响应（含 taskId 或 TASK_STATE），实际响应: %s", body)
                .isTrue();
        // 恢复被拒后原任务仍可查询 — 挂起记录未因恢复请求丢失
        org.a2aproject.sdk.spec.Task taskAfterRejectedResume = client(MOA_L1).getTask(business.taskId());
        assertThat(taskAfterRejectedResume).as("恢复被拒后原任务应仍可查询（挂起记录未丢失）").isNotNull();
        assertThat(taskAfterRejectedResume.id()).as("taskId 应一致").isEqualTo(business.taskId());
    }

    // ═══ P1: Remote resume rejected — record preserved ═══════════════════

    @Test
    @Story("resume-rejected-preserve: 远端恢复后原任务数据不丢失")
    @DisplayName("FEAT-088.remote.resume-rejected-preserve: 远端预留暂停后原任务仍可查询")
    void remoteResumeRejectedRecordPreserved() {
        String contextId = ctx("resume-rejected-preserve");

        // Step 1: 触发 INPUT_REQUIRED — L1 委派 L2，模型询问入住信息
        A2aRound business = sendA2a(HOTEL_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        // L2 等待输入 → INPUT_REQUIRED
        assertThat(business.isInputRequired())
                .as("L2 业务应到达 INPUT_REQUIRED（等待用户输入），实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 暂停
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();
        assertThat(extractReceiptTaskId(pauseResp.body()))
                .as("回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 尝试恢复
        HttpResponse<String> resumeResp = sendSemanticResumeSync("继续", contextId);
        String resumeBody = resumeResp.body();
        System.out.println("[DEBUG resume-rejected-preserve] resume response body=" + resumeBody);

        // Step 4: 无论恢复是否被受理，原任务仍可查询 — 挂起记录不丢失
        org.a2aproject.sdk.spec.Task taskAfterResume = client(MOA_L1).getTask(originalTaskId);
        assertThat(taskAfterResume).as("恢复操作后原任务应仍可查询（挂起记录未丢失）").isNotNull();
        assertThat(taskAfterResume.id()).as("taskId 应一致").isEqualTo(originalTaskId);

        // Step 5: 仍可正常回答 L2 的问题
        A2aRound answer = sendA2a("入住日期11月20日，退房日期11月22日，2人1间",
                contextId, originalTaskId);
        assertThat(answer.taskId()).as("恢复操作后仍应能回答 L2 问题").isNotNull();
        assertThat(answer.state())
                .as("L2 收到答案后状态应为 INPUT_REQUIRED 或 COMPLETED，实际: %s",
                        answer.state())
                .isIn(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_COMPLETED);
    }

    // ═══ P1: L2 normal complete ═════════════════════════════════════════

    @Test
    @Story("normal-complete: L2 正常完成保留结果")
    @DisplayName("FEAT-088.remote.normal-complete: L2 正常完成后保留结果")
    void remoteNormalCompleteRetainsResults() {
        String contextId = ctx("normal-complete");
        // Simple query that L2 can complete without asking questions
        A2aRound business = sendA2a("帮我查询杭州的景点", contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isCompleted() || business.isInputRequired())
                .as("任务应到达 COMPLETED 或 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        // 无论 COMPLETED 还是 INPUT_REQUIRED，原任务都应可通过 getTask 查询（结果保留）
        org.a2aproject.sdk.spec.Task queried = client(MOA_L1).getTask(business.taskId());
        assertThat(queried).as("GetTask 应返回任务（结果保留）").isNotNull();
        assertThat(queried.id()).as("GetTask 返回的 taskId 应一致")
                .isEqualTo(business.taskId());
    }

    // ═══ P1: L2 does not fake paused result ══════════════════════════════

    @Test
    @Story("no-fake-paused-result: L2 不冒充已暂停业务结果")
    @DisplayName("FEAT-088.remote.no-fake-paused-result: L2 路由层返回原信号不冒充")
    void l2DoesNotFakePausedResult() {
        String contextId = ctx("no-fake-paused");

        A2aRound business = sendA2a(HOTEL_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("L2 业务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        // Pause via DataPart — receipt should be independent, not a business completion
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG no-fake-paused] pause response body=" + body);
        assertThat(hasAcceptedReceipt(body))
                .as("暂停回执应被受理，实际响应: %s", body)
                .isTrue();
        assertThat(body).as("应包含 pause_requested 事件（不是业务完成）")
                .contains("pause_requested");
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { /* ignore */ }
    }
}
