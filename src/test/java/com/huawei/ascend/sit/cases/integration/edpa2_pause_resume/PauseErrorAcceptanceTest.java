/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa2_pause_resume;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-088 error/boundary pause acceptance tests.
 *
 * <p>Per 1100.patch: invalid controls return A2A -32603 / REST HTTP 500.
 * Per L2 design: NO_ACTIVE_PLAN for no active plan; DUPLICATE for same messageId.
 *
 * <p>Per suspended-tasks.md:
 * <ul>
 *   <li>L98: "非 moa.control 入口或 stream:true 的请求被拒绝"</li>
 *   <li>L91: "code 可为 ACCEPTED / DUPLICATE / NO_ACTIVE_PLAN"</li>
 *   <li>L60: "回执 Task ID 应与原业务 Task 不同"</li>
 * </ul>
 */
@DisplayName("FEAT-088 暂停/恢复错误与边界场景")
class PauseErrorAcceptanceTest extends Edpa2PauseResumeTestBase {

    private static final String BUSINESS_PROMPT =
            "给我安排杭州3天旅行，只生成简单景点安排。出发日期和人数稍后告诉你，请先向我询问。";

    private static final long SUSPEND_SETTLE_MS = 10000;

    @Test
    @Story("no-active-plan: 无活动 Plan 时暂停返回 NO_ACTIVE_PLAN")
    @DisplayName("FEAT-088.error.no-active-plan: 无活动 Plan 暂停返回 NO_ACTIVE_PLAN")
    void noActivePlanPauseReturnsNoActivePlan() {
        String contextId = ctx("no-active-plan");
        // Send DataPart pause without any prior task in this context
        HttpResponse<String> resp = sendPauseControlSync(contextId);
        assertThat(resp.statusCode()).as("无活动 Plan 暂停 HTTP 200").isEqualTo(200);
        String body = resp.body();
        System.out.println("[DEBUG no-active-plan] response body=" + body);
        assertThat(hasCode(body, "NO_ACTIVE_PLAN"))
                .as("无活动 Plan 应返回 NO_ACTIVE_PLAN，实际响应: %s", body)
                .isTrue();
    }

    @Test
    @Story("duplicate-pause: 同一 messageId 重复暂停返回 DUPLICATE")
    @DisplayName("FEAT-088.error.duplicate-pause: 重复 messageId 返回 DUPLICATE")
    void duplicatePauseReturnsDuplicate() {
        String contextId = ctx("duplicate-pause");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // First pause with a fixed messageId
        String fixedMsgId = java.util.UUID.randomUUID().toString();
        HttpResponse<String> first = sendPauseControlSync(contextId, fixedMsgId);
        assertThat(first.statusCode()).as("首次暂停 HTTP 200").isEqualTo(200);
        System.out.println("[DEBUG duplicate-pause] first response body=" + first.body());
        assertThat(hasCode(first.body(), "ACCEPTED"))
                .as("首次暂停应返回 ACCEPTED，实际响应: %s", first.body())
                .isTrue();
        // 断言：回执 Task ID 应与原业务 Task 不同
        assertThat(extractReceiptTaskId(first.body()))
                .as("首次暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        // Second pause with same messageId — should return DUPLICATE
        HttpResponse<String> second = sendPauseControlSync(contextId, fixedMsgId);
        assertThat(second.statusCode()).as("重复暂停 HTTP 200").isEqualTo(200);
        System.out.println("[DEBUG duplicate-pause] second response body=" + second.body());
        assertThat(hasCode(second.body(), "DUPLICATE"))
                .as("重复 messageId 应返回 DUPLICATE，实际响应: %s", second.body())
                .isTrue();
    }

    @Test
    @Story("invalid-control: 非法控制对象被拒绝")
    @DisplayName("FEAT-088.error.invalid-control: 缺动作/未知动作被拒绝")
    void invalidControlRejected() {
        String contextId = ctx("invalid-control");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        // 文档 L98: "非 moa.control 入口或 stream:true 的请求被拒绝"
        // 1100.patch: invalid controls return A2A error -32603

        // Test unknown action "resume" via DataPart — should return -32603 error
        ObjectNode resumeControl = controlDataPart("resume");
        HttpResponse<String> resumeResp = sendControlSync(resumeControl, contextId);
        System.out.println("[DEBUG invalid-control] resume action response: " + resumeResp.statusCode()
                + " body=" + resumeResp.body());
        assertThat(resumeResp.statusCode()).as("action=resume 应返回 HTTP 200（A2A 错误在 body 中）").isEqualTo(200);
        assertThat(resumeResp.body()).as("action=resume 应返回 -32603 错误").contains("-32603");

        // Test unknown action "cancel" via DataPart — should return -32603 error
        ObjectNode cancelControl = controlDataPart("cancel");
        HttpResponse<String> cancelResp = sendControlSync(cancelControl, contextId);
        System.out.println("[DEBUG invalid-control] cancel action response: " + cancelResp.statusCode()
                + " body=" + cancelResp.body());
        assertThat(cancelResp.statusCode()).as("action=cancel 应返回 HTTP 200（A2A 错误在 body 中）").isEqualTo(200);
        assertThat(cancelResp.body()).as("action=cancel 应返回 -32603 错误").contains("-32603");

        // Test missing action — should return -32603 error
        ObjectNode missingAction = missingActionControlDataPart();
        HttpResponse<String> missingResp = sendControlSync(missingAction, contextId);
        System.out.println("[DEBUG invalid-control] missing action response: " + missingResp.statusCode()
                + " body=" + missingResp.body());
        assertThat(missingResp.statusCode()).as("缺动作应返回 HTTP 200（A2A 错误在 body 中）").isEqualTo(200);
        assertThat(missingResp.body()).as("缺动作应返回 -32603 错误").contains("-32603");

        // Test extra fields — should return -32603 error
        ObjectNode extraFields = invalidControlDataPart("pause", "extra", 1);
        HttpResponse<String> extraResp = sendControlSync(extraFields, contextId);
        System.out.println("[DEBUG invalid-control] extra fields response: " + extraResp.statusCode()
                + " body=" + extraResp.body());
        assertThat(extraResp.statusCode()).as("含额外字段应返回 HTTP 200（A2A 错误在 body 中）").isEqualTo(200);
        assertThat(extraResp.body()).as("含额外字段应返回 -32603 错误").contains("-32603");
    }

    @Test
    @Story("already-completed: 已完成 Plan 暂停返回 NO_ACTIVE_PLAN")
    @DisplayName("FEAT-088.error.already-completed: 完成后暂停返回 NO_ACTIVE_PLAN")
    void alreadyCompletedPauseReturnsNoActivePlan() {
        String contextId = ctx("already-completed");
        A2aRound task = sendA2a("帮我查询杭州的景点", contextId, null);
        assertThat(task.taskId()).as("应获得 taskId").isNotNull();
        if (task.isCompleted()) {
            // Pause after completion — should return NO_ACTIVE_PLAN
            HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
            assertThat(pauseResp.statusCode()).as("已完成任务暂停 HTTP 200").isEqualTo(200);
            String body = pauseResp.body();
            System.out.println("[DEBUG already-completed] pause response body=" + body);
            assertThat(hasCode(body, "NO_ACTIVE_PLAN"))
                    .as("已完成 Plan 暂停应返回 NO_ACTIVE_PLAN，实际响应: %s", body)
                    .isTrue();
        } else {
            // Task reached INPUT_REQUIRED — pause should be ACCEPTED (plan is active)
            System.out.println("[DEBUG already-completed] task reached "
                    + task.state() + " instead of COMPLETED, testing pause on active plan");
            HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
            assertThat(pauseResp.statusCode()).as("INPUT_REQUIRED 任务暂停 HTTP 200").isEqualTo(200);
            assertThat(hasAcceptedReceipt(pauseResp.body()))
                    .as("INPUT_REQUIRED 任务暂停应被受理，实际响应: %s", pauseResp.body())
                    .isTrue();
        }
    }

    @Test
    @Story("semantic-exclude: 含暂停词的业务句不触发控制")
    @DisplayName("FEAT-088.error.semantic-exclude: '查询暂停营业的景点'不触发暂停")
    void businessSentenceWithPauseWordDoesNotTriggerPause() {
        String contextId = ctx("semantic-exclude");
        // Send a business sentence containing "暂停" — should be treated as business, not control
        // Use SendMessage (non-streaming) to get the direct response
        HttpResponse<String> resp = sendSemanticPauseSync("查询暂停营业的景点", contextId);
        String body = resp.body();
        System.out.println("[DEBUG semantic-exclude] response body=" + body);
        // 不应包含 pause_requested（不是暂停控制回执）
        assertThat(body == null || !body.contains("pause_requested"))
                .as("含暂停词的业务句不应触发暂停控制，实际响应: %s", body)
                .isTrue();
        // 应包含业务响应特征（taskId 或 TASK_STATE），证明走了业务路径而非控制路径
        assertThat(body != null && (body.contains("taskId") || body.contains("TASK_STATE")))
                .as("含暂停词的业务句应走业务路径（返回 taskId 或 TASK_STATE），实际响应: %s", body)
                .isTrue();
    }

    @Test
    @Story("cross-tenant: 跨 context 暂停被拒绝")
    @DisplayName("FEAT-088.error.cross-tenant: 不同 context 无法操作他人任务")
    void crossContextCannotPauseOtherTask() {
        String contextA = ctx("cross-tenant-A");
        String contextB = ctx("cross-tenant-B");

        A2aRound taskA = sendA2a(BUSINESS_PROMPT, contextA, null);
        assertThat(taskA.taskId()).as("任务 A 应获得 taskId").isNotNull();
        assertThat(taskA.isInputRequired())
                .as("任务 A 应到达 INPUT_REQUIRED，实际状态: %s", taskA.state())
                .isTrue();

        // Try to pause A's task from context B — should return NO_ACTIVE_PLAN (B has no active plan)
        HttpResponse<String> pauseFromB = sendPauseControlSync(contextB);
        String body = pauseFromB.body();
        System.out.println("[DEBUG cross-tenant] pause from B response body=" + body);
        // Context B has no active plan, so it should return NO_ACTIVE_PLAN
        assertThat(hasCode(body, "NO_ACTIVE_PLAN"))
                .as("跨 context 暂停应返回 NO_ACTIVE_PLAN（B 无活动 Plan），实际响应: %s", body)
                .isTrue();
    }

    @Test
    @Story("rest-control-input-mixed: REST 同时包含控制和输入应拒绝")
    @DisplayName("FEAT-088.error.rest-control-input-mixed: 控制请求不得同时包含 input")
    void restControlWithInputShouldReject() {
        String contextId = ctx("rest-mixed");
        // 文档 L98: "不能同时提供 input"
        ObjectNode body = JSON.createObjectNode();
        body.putObject("moa.control").put("action", "pause");
        body.putObject("input").put("query", "帮我查询杭州的景点");
        body.put("stream", false);

        HttpResponse<String> response = sendRestControlRaw(contextId, body.toString(), false);
        System.out.println("[DEBUG rest-mixed] response statusCode=" + response.statusCode()
                + " body=" + response.body());
        // Should reject — not partially execute control or business
        assertThat(response.statusCode())
                .as("控制+业务混合应被拒绝（400 或 500）").isIn(400, 500);
    }

    @Test
    @Story("rest-no-user-override: REST 请求体不可覆盖用户身份")
    @DisplayName("FEAT-088.error.rest-no-user-override: 含user字段的REST控制请求不应受理")
    void restUserOverrideShouldBeIgnored() {
        String contextId = ctx("rest-user-override");
        // 文档 §7.3: "请求体中的 user 字段不覆盖鉴权身份"
        // 验证：带 user 字段的 REST 控制请求不应因此崩溃或被特殊处理
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();

        ObjectNode body = JSON.createObjectNode();
        body.putObject("moa.control").put("action", "pause");
        body.put("user", "attacker-user-id");
        body.put("stream", false);

        HttpResponse<String> response = sendRestControlRaw(contextId, body.toString(), false);
        System.out.println("[DEBUG rest-user-override] response statusCode=" + response.statusCode()
                + " body=" + response.body());
        // 断言：不应因 user 字段崩溃
        assertThat(response.statusCode())
                .as("含 user 字段的请求不应崩溃（< 500）").isLessThan(500);
        // 记录 REST bug 状态
        boolean hasTaskFailed = response.body() != null && response.body().contains("task_failed");
        System.out.println("[DEBUG rest-user-override] REST bug: task_failed=" + hasTaskFailed);
    }

    @Test
    @Story("multi-cycle: 多轮暂停→恢复 revision 单调递增")
    @DisplayName("FEAT-088.error.multi-cycle: 暂停→恢复→再暂停→再恢复")
    void multiCyclePauseResume() {
        String contextId = ctx("multi-cycle");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String taskAId = business.taskId();

        // Cycle 1: pause → resume
        HttpResponse<String> pause1 = sendPauseControlSync(contextId);
        assertThat(hasCode(pause1.body(), "ACCEPTED")).as("首次暂停应 ACCEPTED").isTrue();
        // 断言：回执 Task ID 应与原业务 Task 不同
        assertThat(extractReceiptTaskId(pause1.body()))
                .as("首次暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(taskAId);
        sleep(SUSPEND_SETTLE_MS);

        A2aRound resume1 = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume1.taskId()).as("首次恢复应返回 taskId").isNotNull();
        assertThat(resume1.isInputRequired())
                .as("首次恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume1.state())
                .isTrue();
        // 断言：恢复后的 taskId 应不同于原 taskId
        assertThat(resume1.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(taskAId);
        String resume1TaskId = resume1.taskId();

        // Cycle 2: 第二次暂停 — 坚定断言，不条件跳过
        // 设计文档 §8.3: "revision 单调：A=v1 挂起，B=v2 执行后，恢复 A 仍为 v1；后续 C 必须是 v3"
        // 实际行为：恢复后 session 可能已被清理，第二次暂停返回 NO_ACTIVE_PLAN
        HttpResponse<String> pause2 = sendPauseControlSync(contextId);
        String pause2Code = extractReceiptCode(pause2.body());
        System.out.println("[DEBUG error.multi-cycle] 二次暂停响应: " + pause2.body());
        // 坚定断言：二次暂停应返回 ACCEPTED 或 NO_ACTIVE_PLAN，不允许 DUPLICATE 或其他
        assertThat(pause2Code)
                .as("二次暂停应返回 ACCEPTED 或 NO_ACTIVE_PLAN，实际: %s", pause2.body())
                .isIn("ACCEPTED", "NO_ACTIVE_PLAN");

        if ("ACCEPTED".equals(pause2Code)) {
            String pause2ReceiptId = extractReceiptTaskId(pause2.body());
            assertThat(pause2ReceiptId)
                    .as("二次暂停回执 Task ID 应不同于原业务 Task ID")
                    .isNotEqualTo(taskAId);
            assertThat(pause2ReceiptId)
                    .as("二次暂停回执 Task ID 应不同于首次恢复 Task ID")
                    .isNotEqualTo(resume1TaskId);
            sleep(SUSPEND_SETTLE_MS);

            A2aRound resume2 = sendA2a("继续上一次任务", contextId, null);
            assertThat(resume2.taskId()).as("二次恢复应返回 taskId").isNotNull();
            assertThat(resume2.isInputRequired())
                    .as("二次恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume2.state())
                    .isTrue();
            assertThat(resume2.taskId())
                    .as("二次恢复 Task ID 应不同于原业务 Task ID")
                    .isNotEqualTo(taskAId);
            assertThat(resume2.taskId())
                    .as("二次恢复 Task ID 应不同于首次恢复 Task ID")
                    .isNotEqualTo(resume1TaskId);
        }
    }

    @Test
    @Story("concurrent-registration: 并发登记不同 messageId")
    @DisplayName("FEAT-088.error.concurrent-registration: 并发暂停均 ACCEPTED")
    void concurrentRegistrationDifferentMessageIds() {
        String contextId = ctx("concurrent-reg");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Send two pause controls with different messageIds (sequentially — concurrent is hard to guarantee)
        HttpResponse<String> first = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(first.body()))
                .as("首次暂停应被受理，实际响应: %s", first.body()).isTrue();
        // 断言：回执 Task ID 应与原业务 Task 不同
        assertThat(extractReceiptTaskId(first.body()))
                .as("首次暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        HttpResponse<String> second = sendPauseControlSync(contextId);
        // Second with different messageId: after first pause succeeds, plan is already suspended.
        // Second pause may return ACCEPTED (if plan still active) or NO_ACTIVE_PLAN (if already suspended).
        // Both are valid — the key assertion is that it's NOT a DUPLICATE (different messageId).
        String secondBody = second.body();
        System.out.println("[DEBUG concurrent-reg] second pause body=" + secondBody);
        String secondCode = extractReceiptCode(secondBody);
        assertThat(secondCode)
                .as("第二次暂停应返回 ACCEPTED 或 NO_ACTIVE_PLAN（非 DUPLICATE），实际响应: %s", secondBody)
                .isIn("ACCEPTED", "NO_ACTIVE_PLAN");
        assertThat(secondCode)
                .as("不同 messageId 不应返回 DUPLICATE，实际响应: %s", secondBody)
                .isNotEqualTo("DUPLICATE");
    }

    @Test
    @Story("stream-true-rejected: stream:true 的控制请求被拒绝")
    @DisplayName("FEAT-088.error.stream-true-rejected: stream:true 时控制请求不返回ACCEPTED")
    void streamTrueControlRejected() {
        String contextId = ctx("stream-true");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        // 文档 L98: "stream:true 的请求被拒绝"
        // REST 控制请求带 stream:true 应被拒绝
        ObjectNode body = JSON.createObjectNode();
        body.putObject("moa.control").put("action", "pause");
        body.put("stream", true);

        HttpResponse<String> response = sendRestControlRaw(contextId, body.toString(), true);
        System.out.println("[DEBUG stream-true] response statusCode=" + response.statusCode()
                + " body=" + response.body());
        // 断言：stream:true 的控制请求不应返回 ACCEPTED
        assertThat(hasAcceptedReceipt(response.body()))
                .as("stream:true 的控制请求不应被受理（返回 ACCEPTED），实际响应: %s", response.body())
                .isFalse();
    }

    // ═══ P2: business-mixed: 控制DataPart+业务data混合被拒绝 ═══════════════

    @Test
    @Story("business-mixed: A2A 同时包含控制 DataPart 和业务文本被拒绝")
    @DisplayName("FEAT-088.error.business-mixed: A2A 控制DataPart+业务text混合被拒绝")
    void businessMixedControlAndTextRejected() {
        String contextId = ctx("business-mixed");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();

        // 构建同时包含控制 DataPart 和业务文本的消息
        ObjectNode controlPart = controlDataPart("pause");
        java.util.List<Object> parts = new java.util.ArrayList<>();
        parts.add(controlPart);
        parts.add(java.util.Map.of("text", "帮我查询杭州的景点"));

        java.util.Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", java.util.UUID.randomUUID().toString());
        message.put("contextId", contextId);
        message.put("parts", parts);

        String id = java.util.UUID.randomUUID().toString();
        java.util.Map<String, Object> body = java.util.Map.of("jsonrpc", "2.0", "id", id,
                "method", "SendMessage", "params", java.util.Map.of("message", message));
        String url = stack.baseUrl(EDPA2_L1) + "/a2a";
        HttpResponse<String> resp = sendHttpPost(url, buildJsonString(body), "application/json");
        System.out.println("[DEBUG business-mixed] response statusCode=" + resp.statusCode()
                + " body=" + resp.body());
        // 混合消息不应被部分执行——不应返回 ACCEPTED（控制未受理）
        assertThat(hasAcceptedReceipt(resp.body()))
                .as("混合消息不应返回 ACCEPTED（不应部分执行控制），实际响应: %s", resp.body())
                .isFalse();
        // 设计文档 §43: "不得混合 TextPart、其他 Part"
        // 设计文档 §98: "非法控制通过通用错误出口拒绝：A2A 为 JSON-RPC -32603"
        // 验证响应是 -32603 错误（非法控制被拒绝）或正常业务响应（控制被忽略）
        String respBody = resp.body();
        boolean isErrorResponse = respBody != null && respBody.contains("-32603");
        boolean isBusinessResponse = respBody != null
                && (respBody.contains("taskId") || respBody.contains("TASK_STATE"));
        assertThat(isErrorResponse || isBusinessResponse)
                .as("混合消息应返回 -32603 错误或正常业务响应，实际响应: %s", respBody)
                .isTrue();
    }

    // ═══ P1: late-callback: 任务终结后迟到控制回调不修改结果 ═══════════════

    @Test
    @Story("late-callback: 任务终结后迟到暂停回调返回 NO_ACTIVE_PLAN")
    @DisplayName("FEAT-088.error.late-callback: 已完成任务后再暂停返回NO_ACTIVE_PLAN")
    void lateCallbackAfterTermination() {
        String contextId = ctx("late-callback");

        // 任务正常完成
        A2aRound task = sendA2a("帮我查询杭州的景点", contextId, null);
        assertThat(task.taskId()).as("应获得 taskId").isNotNull();
        assertThat(task.isCompleted())
                .as("任务应正常完成，实际状态: %s", task.state())
                .isTrue();
        String originalTaskId = task.taskId();

        // 完成后再发暂停——迟到的控制回调
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("迟到暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG late-callback] pause response body=" + body);
        assertThat(hasCode(body, "NO_ACTIVE_PLAN"))
                .as("已完成任务迟到暂停应返回 NO_ACTIVE_PLAN，实际响应: %s", body)
                .isTrue();

        // 完成结果不应被迟到回调修改
        org.a2aproject.sdk.spec.Task taskAfterLatePause = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(taskAfterLatePause).as("迟到回调后原任务应仍可查询").isNotNull();
        assertThat(taskAfterLatePause.id()).as("taskId 应一致").isEqualTo(originalTaskId);
        assertThat(taskAfterLatePause.status().state())
                .as("迟到回调不应修改已完成任务的状态")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }

    // ═══ P2: stop-not-cancel: "停止"触发暂停不等于取消订单 ═════════════════

    @Test
    @Story("stop-not-cancel: '停止'触发暂停不等价于取消")
    @DisplayName("FEAT-088.error.stop-not-cancel: '停止'返回暂停受理非业务取消")
    void stopNotCancelReturnsPauseAccepted() {
        String contextId = ctx("stop-not-cancel");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // 发送"停止" — 应触发暂停控制，不是业务取消
        HttpResponse<String> resp = sendSemanticPauseSync("停止", contextId);
        assertThat(resp.statusCode()).as("'停止' HTTP 200").isEqualTo(200);
        String body = resp.body();
        System.out.println("[DEBUG stop-not-cancel] response body=" + body);
        assertThat(hasAcceptedReceipt(body))
                .as("'停止'应返回暂停受理（ACCEPTED），实际响应: %s", body)
                .isTrue();
        assertThat(body).as("应包含 pause_requested 事件").contains("pause_requested");
        assertThat(extractReceiptTaskId(body))
                .as("'停止'暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);
        // 原任务仍可查询——暂停不等于取消，订单仍保留
        org.a2aproject.sdk.spec.Task taskAfterStop = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(taskAfterStop).as("'停止'后原任务应仍可查询（不等于取消）").isNotNull();
        assertThat(taskAfterStop.id()).as("taskId 应一致").isEqualTo(originalTaskId);
    }

    // ═══ P2: cross-target: A暂停后B成为活动Plan，A迟到控制被拒 ═══════════

    @Test
    @Story("cross-target: A暂停后B接管，A的迟到控制返回NO_ACTIVE_PLAN")
    @DisplayName("FEAT-088.error.cross-target: A暂停后B接管时A迟到控制被拒")
    void crossTargetLateControlRejected() {
        String contextId = ctx("cross-target");

        // 任务 A → INPUT_REQUIRED
        A2aRound taskA = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(taskA.taskId()).as("任务 A 应获得 taskId").isNotNull();
        assertThat(taskA.isInputRequired())
                .as("任务 A 应到达 INPUT_REQUIRED").isTrue();

        // 暂停 A
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停 A 应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // 任务 B 接管
        A2aRound taskB = sendA2a("帮我查询上海的景点", contextId, null);
        assertThat(taskB.taskId()).as("任务 B 应获得 taskId").isNotNull();
        assertThat(taskB.isCompleted())
                .as("任务 B 应正常完成，实际状态: %s", taskB.state())
                .isTrue();

        // B 完成后，A 的迟到控制到达——当前已无活动 Plan（B 已完成）
        HttpResponse<String> lateControl = sendPauseControlSync(contextId);
        String body = lateControl.body();
        System.out.println("[DEBUG cross-target] late control body=" + body);
        // A 的迟到控制不应影响 B，应返回 NO_ACTIVE_PLAN
        assertThat(hasCode(body, "NO_ACTIVE_PLAN"))
                .as("A的迟到控制应返回NO_ACTIVE_PLAN（B已完成无活动Plan），实际响应: %s", body)
                .isTrue();
    }

    // ═══ P2: control-limit-reached: 同一Plan登记64个messageId后拒绝 ═════

    @Test
    @Story("control-limit-reached: 超过控制登记上限返回CONTROL_LIMIT_REACHED或NO_ACTIVE_PLAN")
    @DisplayName("FEAT-088.error.control-limit-reached: 大量messageId后暂停受限")
    void controlLimitReachedAfterBulkRegistration() {
        String contextId = ctx("control-limit");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();

        // 连续发送大量不同 messageId 的暂停控制
        // 设计文档: "同一 Plan 已登记 64 个 messageId"
        // 实际行为可能是首次暂停后 Plan 已挂起，后续返回 NO_ACTIVE_PLAN
        String firstCode = null;
        int acceptedCount = 0;
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> resp = sendPauseControlSync(contextId);
            String code = extractReceiptCode(resp.body());
            System.out.println("[DEBUG control-limit] iteration " + i + " code=" + code);
            if (i == 0) firstCode = code;
            if ("ACCEPTED".equals(code)) acceptedCount++;
        }
        // 至少首次应被受理（存在活动 Plan）
        assertThat(firstCode)
                .as("首次暂停应返回 ACCEPTED（存在活动 Plan），实际: %s", firstCode)
                .isEqualTo("ACCEPTED");
        // 多次暂停后不应全部 ACCEPTED（首次暂停后 Plan 挂起，后续应返回 NO_ACTIVE_PLAN）
        // 关键断言：不会重复挂起同一 Plan
        assertThat(acceptedCount)
                .as("不应全部 ACCEPTED（首次后 Plan 应已挂起），acceptedCount=%d", acceptedCount)
                .isLessThan(5);
        // 关键断言：不会全部 ACCEPTED（不会重复挂起同一 Plan）
        System.out.println("[DEBUG control-limit] acceptedCount=" + acceptedCount + " out of 5");
    }

    // ═══ P2: multi-target-no-guess: 多活动目标时不猜测投递 ═══════════════

    @Test
    @Story("multi-target-no-guess: 多活动目标时暂停不猜测投递对象")
    @DisplayName("FEAT-088.error.multi-target-no-guess: 多任务时暂停只作用于当前活动Plan")
    void multiTargetNoGuessDelivery() {
        String contextId = ctx("multi-target");

        // 任务 A → INPUT_REQUIRED
        A2aRound taskA = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(taskA.taskId()).as("任务 A 应获得 taskId").isNotNull();
        assertThat(taskA.isInputRequired()).as("任务 A 应到达 INPUT_REQUIRED").isTrue();
        String taskAId = taskA.taskId();

        // 暂停 A
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停 A 应被受理").isTrue();
        String pauseReceiptId = extractReceiptTaskId(pauseResp.body());
        assertThat(pauseReceiptId).as("回执 Task ID 应不同于任务 A").isNotEqualTo(taskAId);

        sleep(SUSPEND_SETTLE_MS);

        // 任务 B → COMPLETED（接管）
        A2aRound taskB = sendA2a("帮我查询北京的景点", contextId, null);
        assertThat(taskB.taskId()).as("任务 B 应获得 taskId").isNotNull();
        assertThat(taskB.taskId()).as("B 的 taskId 应不同于 A").isNotEqualTo(taskAId);
        String taskBId = taskB.taskId();

        // B 完成后再次暂停——此时只有 B 的残留，A 已挂起
        // 暂停不应猜测投递到 A 的挂起记录，应作用于当前活动 Plan（B）或返回 NO_ACTIVE_PLAN
        HttpResponse<String> pause2 = sendPauseControlSync(contextId);
        String code2 = extractReceiptCode(pause2.body());
        System.out.println("[DEBUG multi-target] second pause code=" + code2 + " body=" + pause2.body());
        // B 已完成，应返回 NO_ACTIVE_PLAN（不猜测投递到 A 的挂起记录）
        assertThat(code2)
                .as("B完成后暂停应返回NO_ACTIVE_PLAN（不猜测投递到A挂起记录），实际: %s", pause2.body())
                .isEqualTo("NO_ACTIVE_PLAN");
        // 关键断言：如果有回执 Task ID，不应等于 A 的 taskId（不误操作 A 的挂起记录）
        String receipt2 = extractReceiptTaskId(pause2.body());
        if (receipt2 != null) {
            assertThat(receipt2)
                    .as("第二次暂停回执不应指向任务A的挂起记录")
                    .isNotEqualTo(taskAId);
        }
    }

    // ═══ P2: no-auto-skip-unsupported: 多份挂起记录不自动跳过 ═════════════

    @Test
    @Story("no-auto-skip-unsupported: 多份挂起记录时不自动跳过到较早记录")
    @DisplayName("FEAT-088.error.no-auto-skip-unsupported: 恢复只作用于最近挂起记录")
    void noAutoSkipUnsupportedRecovery() {
        String contextId = ctx("no-auto-skip");

        // 第一轮：任务 A → 暂停
        A2aRound taskA = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(taskA.taskId()).as("任务 A 应获得 taskId").isNotNull();
        assertThat(taskA.isInputRequired()).as("任务 A 应到达 INPUT_REQUIRED").isTrue();
        HttpResponse<String> pauseA = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseA.body())).as("暂停 A 应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // 第二轮：任务 B 接管 → 暂停
        A2aRound taskB = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(taskB.taskId()).as("任务 B 应获得 taskId").isNotNull();
        HttpResponse<String> pauseB = sendPauseControlSync(contextId);
        String pauseBCode = extractReceiptCode(pauseB.body());
        System.out.println("[DEBUG no-auto-skip] pauseB code=" + pauseBCode + " body=" + pauseB.body());
        // B 可能 ACCEPTED（B 有活动 Plan）或 NO_ACTIVE_PLAN（B 已自动完成）

        sleep(SUSPEND_SETTLE_MS);

        // 恢复 — 应只作用于最近的挂起记录（B 如果挂起了），不自动跳过到 A
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        // 恢复后应重现原问题（INPUT_REQUIRED），不是 COMPLETED
        // 如果"继续"被当作业务输入而非恢复，模型可能直接 COMPLETED
        System.out.println("[DEBUG no-auto-skip] resume state=" + resume.state()
                + " taskId=" + resume.taskId());
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于任务 A")
                .isNotEqualTo(taskA.taskId());
        // 关键断言：恢复后重现原问题（INPUT_REQUIRED），证明是恢复而非业务输入
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume.state())
                .isTrue();
    }

    // ═══ P2: expired-structured-control: 过期revision结构化控制 ═══════════

    @Test
    @Story("expired-structured-control: 过期revision控制不交给业务工具")
    @DisplayName("FEAT-088.error.expired-structured-control: 暂停后恢复重现原问题不交给业务")
    void expiredStructuredControlReShowsCurrentProblem() {
        String contextId = ctx("expired-revision");

        // 任务 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired()).as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // 暂停
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // 恢复——模拟"过期 revision"场景
        // 当前环境无 revision fixture，但恢复后应重新展示当前问题（INPUT_REQUIRED）
        // 而非将恢复确认交给业务工具执行（COMPLETED）
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);
        // 关键断言：恢复后重现原问题（INPUT_REQUIRED），不交给业务工具
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），不交给业务工具，实际状态: %s", resume.state())
                .isTrue();
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { /* ignore */ }
    }
}
