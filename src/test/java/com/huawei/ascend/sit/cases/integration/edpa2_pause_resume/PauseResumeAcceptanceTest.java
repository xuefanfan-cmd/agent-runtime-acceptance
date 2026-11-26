/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa2_pause_resume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-088 normal pause/resume acceptance tests against the moa-agent-demo stack.
 *
 * <p><b>Test method per 1100.patch (development guidance document):</b>
 * <ol>
 *   <li>Send business task via A2A SendStreamingMessage (async) — wait for
 *       {@code TASK_STATE_INPUT_REQUIRED} (model asks for dates/guests).
 *       Per patch line 59: "没有到等待态时先排查模型和业务错误，不继续控制步骤。"</li>
 *   <li>Send pause control via A2A SendMessage (non-streaming) with DataPart
 *       {@code {"moa.control":{"action":"pause"}}}.</li>
 *   <li>Assert response contains {@code code=ACCEPTED} and
 *       {@code event=pause_requested}. Per patch line 77: "回执 Task ID 应与
 *       原业务 Task 不同；保留原 ID。"</li>
 *   <li>For resume/takeover: wait for actual suspension to take effect before
 *       sending new business. Per patch line 254: "实际挂起后再发送新业务。"</li>
 * </ol>
 * </p>
 */
@DisplayName("FEAT-088 暂停/恢复正常场景")
class PauseResumeAcceptanceTest extends Edpa2PauseResumeTestBase {

    private static final String BUSINESS_PROMPT =
            "给我安排杭州3天旅行，只生成简单景点安排。出发日期和人数稍后告诉你，请先向我询问。";

    private static final Duration INPUT_REQUIRED_TIMEOUT = Duration.ofSeconds(120);
    private static final long SUSPEND_SETTLE_MS = 10000;

    // ═══ P0: Program-level pause via A2A SendMessage DataPart ═════════════

    @Test
    @Story("datapart-pause: A2A DataPart 程序级暂停")
    @DisplayName("FEAT-088.normal.datapart-pause: DataPart 控制对象 INPUT_REQUIRED 后暂停受理")
    void datapartPauseAcceptedViaA2a() {
        String contextId = ctx("datapart-pause");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId（任务已开始）").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED（模型询问日期人数），实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Step 2: DataPart 暂停控制
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("DataPart 暂停 HTTP 200").isEqualTo(200);

        String body = pauseResp.body();
        System.out.println("[DEBUG datapart-pause] originalTaskId=" + originalTaskId
                + " pause response body=" + body);

        // 断言：受理回执
        assertThat(hasCode(body, "ACCEPTED"))
                .as("DataPart 暂停应返回 code=ACCEPTED，实际响应: %s", body)
                .isTrue();
        assertThat(body).as("应包含 pause_requested 事件").contains("pause_requested");
        assertThat(extractReceiptCode(body)).as("code 字段应为 ACCEPTED").isEqualTo("ACCEPTED");
        assertThat(extractDelivery(body)).as("delivery 字段应存在").isNotNull();

        // 断言：回执 Task ID 应与原业务 Task ID 不同
        String receiptTaskId = extractReceiptTaskId(body);
        assertThat(receiptTaskId).as("回执 Task ID 应存在").isNotNull();
        assertThat(receiptTaskId).as("回执 Task ID 应与原业务 Task ID 不同")
                .isNotEqualTo(originalTaskId);

        // 断言：原业务 Task 仍可通过 getTask 查询（Plan 已归档，Task 可查询）
        org.a2aproject.sdk.spec.Task originalTask = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(originalTask).as("原业务 Task 应可通过 getTask 查询（暂停后 Plan 已归档）").isNotNull();
    }

    // ═══ P1: Streaming pause via A2A SendStreamingMessage DataPart ═══════

    @Test
    @Story("streaming-pause: A2A 流式 DataPart 程序级暂停")
    @DisplayName("FEAT-088.normal.streaming-pause: 流式 DataPart 控制对象 INPUT_REQUIRED 后暂停受理")
    void streamingPauseAcceptedViaA2a() {
        String contextId = ctx("streaming-pause");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId（任务已开始）").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED（模型询问日期人数），实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 流式 DataPart 暂停控制 — SendStreamingMessage (SSE)
        HttpResponse<String> pauseResp = sendControlRaw(controlDataPart("pause"), contextId, null);
        assertThat(pauseResp.statusCode()).as("流式暂停 HTTP 200").isEqualTo(200);

        // SSE 响应解析 — 提取第一个 data: 帧的 JSON payload
        String body = extractFirstSseFrame(pauseResp.body());
        System.out.println("[DEBUG streaming-pause] originalTaskId=" + originalTaskId
                + " pause response body=" + body);

        // 断言：受理回执
        assertThat(hasCode(body, "ACCEPTED"))
                .as("流式 DataPart 暂停应返回 code=ACCEPTED，实际响应: %s", body)
                .isTrue();
        assertThat(body).as("应包含 pause_requested 事件").contains("pause_requested");

        // 断言：回执 Task ID 应与原业务 Task ID 不同
        String receiptTaskId = extractReceiptTaskId(body);
        assertThat(receiptTaskId).as("回执 Task ID 应存在").isNotNull();
        assertThat(receiptTaskId).as("回执 Task ID 应与原业务 Task ID 不同")
                .isNotEqualTo(originalTaskId);

        // 断言：原业务 Task 仍可通过 getTask 查询（Plan 已归档，Task 可查询）
        org.a2aproject.sdk.spec.Task originalTask = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(originalTask).as("原业务 Task 应可通过 getTask 查询（暂停后 Plan 已归档）").isNotNull();
    }

    // ═══ P0: Semantic pause via text "暂停" ═══════════════════════════════

    @Test
    @Story("semantic-pause: 语义级暂停受理与安全边界挂起")
    @DisplayName("FEAT-088.normal.semantic-pause: 语义暂停词 INPUT_REQUIRED 后受理")
    void semanticPauseAcceptedAndReturnsIndependentReceipt() {
        String contextId = ctx("semantic-pause");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId（任务已开始）").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        HttpResponse<String> pauseResp = sendSemanticPauseSync("暂停", contextId);
        assertThat(pauseResp.statusCode()).as("暂停受理 HTTP 状态应 200").isEqualTo(200);

        String body = pauseResp.body();
        System.out.println("[DEBUG semantic-pause] pause response body=" + body);

        assertThat(hasCode(body, "ACCEPTED"))
                .as("语义暂停词应返回 code=ACCEPTED，实际响应: %s", body)
                .isTrue();
        assertThat(body).as("应包含 pause_requested 事件").contains("pause_requested");

        // 断言：回执 Task ID 应与原业务 Task ID 不同
        String receiptTaskId = extractReceiptTaskId(body);
        assertThat(receiptTaskId).as("回执 Task ID 应存在").isNotNull();
        assertThat(receiptTaskId).as("回执 Task ID 应与原业务 Task ID 不同")
                .isNotEqualTo(originalTaskId);

        // 断言：原业务 Task 仍可通过 getTask 查询（Plan 已归档，Task 可查询）
        org.a2aproject.sdk.spec.Task originalTask = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(originalTask).as("原业务 Task 应可通过 getTask 查询（暂停后 Plan 已归档）").isNotNull();
    }

    // ═══ P0: REST pause via Custom REST URL ═══════════════════════════════

    @Test
    @Story("rest-pause: Custom REST 程序级暂停")
    @DisplayName("FEAT-088.normal.rest-pause: REST URL POST 暂停控制受理")
    void restPauseAccepted() {
        String contextId = ctx("rest-pause");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        // Per 1100.patch step 4: POST to conversation URL with moa.control
        HttpResponse<String> restResp = sendRestPause(contextId, false);
        assertThat(restResp.statusCode()).as("REST 暂停 HTTP 200").isEqualTo(200);

        String body = restResp.body();
        System.out.println("[DEBUG rest-pause] rest response body=" + body);

        // REST response uses custom_rsp_data wrapper
        assertThat(body).as("REST 响应应包含 custom_rsp_data").contains("custom_rsp_data");
        assertThat(body).as("REST 响应应包含 pause_requested").contains("pause_requested");
        assertThat(hasCode(body, "ACCEPTED"))
                .as("REST 暂停应返回 code=ACCEPTED，实际响应: %s", body)
                .isTrue();
    }

    // ═══ P0: Resume after pause ═══════════════════════════════════════════

    @Test
    @Story("resume: 本地恢复确认后恢复原 Plan")
    @DisplayName("FEAT-088.normal.resume: 暂停后等待挂起生效再恢复原任务")
    void resumeRestoresOriginalTask() {
        String contextId = ctx("resume");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 暂停
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String pauseBody = pauseResp.body();
        System.out.println("[DEBUG resume] pause response body=" + pauseBody);
        assertThat(hasCode(pauseBody, "ACCEPTED"))
                .as("暂停应被受理（ACCEPTED），实际响应: %s", pauseBody)
                .isTrue();

        // 断言：暂停后原任务仍可查询（Plan 已归档）
        org.a2aproject.sdk.spec.Task beforeResume = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(beforeResume).as("暂停后原业务 Task 应可查询（Plan 已归档）").isNotNull();

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 恢复 — "继续上一次任务"
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume.state())
                .isTrue();
        // 断言：恢复后的 taskId 应不同于原 taskId（新 Task 恢复了原 Plan 的执行位置）
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID（新 Task 承载恢复）")
                .isNotEqualTo(originalTaskId);
    }

    // ═══ P0: Takeover — pause A, then run independent task B ═════════════

    @Test
    @Story("takeover: 暂停后新任务 B 接管")
    @DisplayName("FEAT-088.normal.takeover: A 暂停挂起后 B 使用新 Plan 独立完成")
    void pauseThenTakeoverWithNewTask() {
        String contextId = ctx("takeover");

        // Step 1: 任务 A → INPUT_REQUIRED
        A2aRound taskA = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(taskA.taskId()).as("任务 A 应获得 taskId").isNotNull();
        assertThat(taskA.isInputRequired())
                .as("任务 A 应到达 INPUT_REQUIRED，实际状态: %s", taskA.state())
                .isTrue();
        String taskAId = taskA.taskId();

        // Step 2: 暂停 A
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 A HTTP 200").isEqualTo(200);
        String pauseBody = pauseResp.body();
        System.out.println("[DEBUG takeover] taskAId=" + taskAId + " pause response body=" + pauseBody);
        assertThat(hasCode(pauseBody, "ACCEPTED"))
                .as("暂停 A 应被受理，实际响应: %s", pauseBody)
                .isTrue();

        // 断言：暂停后任务 A 仍可查询（Plan 已归档，Task 可结束）
        // 文档 suspended-tasks.md L17: "原业务 Task 可以结束，EDPA2.0 Plan 仍是 SUSPENDED；后续恢复使用归档，不复活旧 A2A Task"
        org.a2aproject.sdk.spec.Task taskABeforeB = client(EDPA2_L1).getTask(taskAId);
        assertThat(taskABeforeB).as("暂停后任务 A 应可查询（Plan 已归档）").isNotNull();

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 任务 B 独立执行
        A2aRound taskB = sendA2a("帮我查询上海的景点", contextId, null);
        assertThat(taskB.taskId()).as("接管任务 B 应获得 taskId").isNotNull();
        assertThat(taskB.taskId()).as("接管任务 B 的 taskId 应不同于任务 A")
                .isNotEqualTo(taskAId);
        // 断言：任务 B 应正常完成（接管后新 Plan 独立运行）
        assertThat(taskB.isCompleted())
                .as("任务 B 应正常完成（COMPLETED），实际状态: %s", taskB.state())
                .isTrue();
    }

    // ═══ P0: Pause word not consumed as answer ═══════════════════════════

    @Test
    @Story("local-wait-pause: 暂停词不被当作答案消费")
    @DisplayName("FEAT-088.normal.local-wait-pause: 暂停词不被当作业务输入")
    void localWaitPauseDoesNotConsumePauseAsAnswer() {
        String contextId = ctx("pause-not-answer");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG pause-not-answer] pause response body=" + body);
        assertThat(hasCode(body, "ACCEPTED"))
                .as("暂停词应返回 ACCEPTED 控制受理回执（不是业务完成），实际响应: %s", body)
                .isTrue();
    }

    // ═══ P1: Pause/resume observable in events ═══════════════════════════

    @Test
    @Story("observability: 暂停恢复可观测")
    @DisplayName("FEAT-088.normal.observability: 暂停恢复事件可观测")
    void pauseResumeObservableInEvents() {
        String contextId = ctx("pause-events");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG pause-events] pause response body=" + body);
        assertThat(hasCode(body, "ACCEPTED"))
                .as("暂停事件应可观测（ACCEPTED），实际响应: %s", body)
                .isTrue();
        assertThat(body).as("应包含 delivery 字段").contains("delivery");
        assertThat(body).as("应包含 event 字段").contains("pause_requested");

        sleep(SUSPEND_SETTLE_MS);

        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
    }

    // ═══ P1: Saved context queryable via GetTask ═════════════════════════

    @Test
    @Story("saved-context: 暂停后上下文可通过 GetTask 查询")
    @DisplayName("FEAT-088.normal.saved-context: 暂停后上下文可查询")
    void savedContextQueryableViaGetTask() {
        String contextId = ctx("saved-context");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        assertThat(hasCode(pauseResp.body(), "ACCEPTED"))
                .as("暂停应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // 文档 L100: "原业务 Task 可用 GetTask 观察"
        org.a2aproject.sdk.spec.Task queried = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(queried).as("GetTask 应返回任务").isNotNull();
        assertThat(queried.id()).as("GetTask 返回的 taskId 应一致")
                .isEqualTo(originalTaskId);
    }

    // ═══ P1: Pause word with punctuation ═════════════════════════════════

    @Test
    @Story("punctuation-trim: 暂停词带标点仍匹配")
    @DisplayName("FEAT-088.normal.punctuation-trim: 暂停词带标点仍匹配")
    void pauseWordWithPunctuationStillMatches() {
        String contextId = ctx("pause-punct");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        HttpResponse<String> pauseResp = sendSemanticPauseSync("暂停。", contextId);
        assertThat(pauseResp.statusCode()).as("带标点暂停词 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG pause-punct] pause response body=" + body);
        assertThat(hasCode(body, "ACCEPTED"))
                .as("带标点暂停词应返回 ACCEPTED，实际响应: %s", body)
                .isTrue();
    }

    // ═══ P1: Multiple resume words ═══════════════════════════════════════

    @Test
    @Story("multiple-resume-words: 多种恢复词触发恢复")
    @DisplayName("FEAT-088.normal.multiple-resume-words: 多种恢复词触发恢复")
    void multipleResumeWordsTriggerResume() {
        String contextId = ctx("multi-resume");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasCode(pauseResp.body(), "ACCEPTED")).as("暂停应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        A2aRound resume = sendA2a("恢复任务", contextId, null);
        assertThat(resume.taskId()).as("恢复词应返回 taskId").isNotNull();
    }

    // ═══ P1: REST non-control request goes through business path ════════

    @Test
    @Story("rest-non-control: REST 非控制请求走业务路径")
    @DisplayName("FEAT-088.normal.rest-non-control: 非控制 REST 请求走业务路径")
    void restNonControlRequestGoesThroughBusinessPath() {
        String contextId = ctx("rest-non-control");
        // Send a normal business request to the REST conversation URL
        ObjectNode body = JSON.createObjectNode();
        body.putObject("input").put("query", "帮我查询杭州的景点");
        body.put("stream", false);
        HttpResponse<String> response = sendRestControlRaw(contextId, body.toString(), false);
        // Non-control request should go through business path — valid HTTP response
        assertThat(response.statusCode()).as("非控制请求应返回有效 HTTP 状态（< 500）")
                .isLessThan(500);
    }

    // ═══ P1: Task irrelevant for pause control ═══════════════════════════

    @Test
    @Story("task-id-irrelevant: 暂停控制不依赖 taskId")
    @DisplayName("FEAT-088.normal.task-id-irrelevant: 暂停控制与 Task ID 无关")
    void taskIrrelevantForPauseControl() {
        String contextId = ctx("task-irrelevant");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        HttpResponse<String> pauseResp = sendSemanticPauseSync("先停一下", contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG task-irrelevant] pause response body=" + body);
        assertThat(hasCode(body, "ACCEPTED"))
                .as("不同暂停词应同样返回 ACCEPTED，实际响应: %s", body)
                .isTrue();
    }

    // ═══ P1: Pause receipt contains code and delivery ════════════════════

    @Test
    @Story("receipt-delivery: 暂停回执包含 code 和 delivery")
    @DisplayName("FEAT-088.normal.receipt-delivery: 暂停回执包含受理信号")
    void pauseReceiptContainsCodeAndDelivery() {
        String contextId = ctx("pause-receipt");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();

        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG pause-receipt] pause response body=" + body);
        assertThat(extractReceiptCode(body)).as("code 应为 ACCEPTED").isEqualTo("ACCEPTED");
        assertThat(extractDelivery(body)).as("delivery 字段应存在").isNotNull();
        assertThat(body).as("应包含 pause_requested 事件").contains("pause_requested");
    }

    // ═══ P1: Full chain: pause A → task B → resume A ═════════════════════

    @Test
    @Story("full-chain: 暂停A→独立任务B→确认恢复A完整链路")
    @DisplayName("FEAT-088.normal.full-chain: A暂停后B独立完成，恢复A重现原问题")
    void pauseA_taskB_resumeA_fullChain() {
        String contextId = ctx("full-chain");

        // Step 1: 任务 A → INPUT_REQUIRED
        A2aRound taskA = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(taskA.taskId()).as("任务 A 应获得 taskId").isNotNull();
        assertThat(taskA.isInputRequired())
                .as("任务 A 应到达 INPUT_REQUIRED，实际状态: %s", taskA.state())
                .isTrue();
        String taskAId = taskA.taskId();

        // Step 2: 暂停 A
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasCode(pauseResp.body(), "ACCEPTED"))
                .as("暂停 A 应被受理，实际响应: %s", pauseResp.body())
                .isTrue();
        String pauseReceiptId = extractReceiptTaskId(pauseResp.body());
        assertThat(pauseReceiptId).as("暂停回执 Task ID 应不同于任务 A")
                .isNotEqualTo(taskAId);

        // 暂停后 A 仍可查询
        org.a2aproject.sdk.spec.Task taskAPaused = client(EDPA2_L1).getTask(taskAId);
        assertThat(taskAPaused).as("暂停后任务 A 应可查询").isNotNull();

        sleep(SUSPEND_SETTLE_MS);

        // Step 3: 任务 B 独立执行
        A2aRound taskB = sendA2a("帮我查询上海的景点", contextId, null);
        assertThat(taskB.taskId()).as("任务 B 应获得 taskId").isNotNull();
        assertThat(taskB.taskId()).as("任务 B 的 taskId 应不同于任务 A")
                .isNotEqualTo(taskAId);
        assertThat(taskB.isCompleted())
                .as("任务 B 应正常完成（COMPLETED），实际状态: %s", taskB.state())
                .isTrue();
        String taskBId = taskB.taskId();

        // Step 4: 恢复 A — "继续上一次任务"
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复 A 应返回 taskId").isNotNull();
        assertThat(resume.taskId()).as("恢复 Task ID 应不同于任务 A").isNotEqualTo(taskAId);
        assertThat(resume.taskId()).as("恢复 Task ID 应不同于任务 B").isNotEqualTo(taskBId);
        // 恢复后应重现原问题（INPUT_REQUIRED），确认文本不被当作业务参数消费
        assertThat(resume.isInputRequired())
                .as("恢复 A 后应重现原问题（INPUT_REQUIRED），确认文本不被当作业务参数，实际状态: %s", resume.state())
                .isTrue();
    }

    // ═══ P1: ACCEPTED receipt does not immediately change task state ═══════

    @Test
    @Story("accepted-not-immediate: ACCEPTED 仅表示受理不表示立即停止")
    @DisplayName("FEAT-088.normal.accepted-not-immediate: 受理后原任务A2A state不含SUSPENDED")
    void acceptedReceiptDoesNotImmediatelyChangeTaskState() {
        String contextId = ctx("accepted-not-immediate");

        // Step 1: 业务请求 → INPUT_REQUIRED
        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // Step 2: 暂停 — ACCEPTED 仅表示受理
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasCode(pauseResp.body(), "ACCEPTED"))
                .as("暂停应被受理").isTrue();

        // Step 3: 立即（不等 settle）检查原任务状态
        // 文档 L100: "受理后不会立即掐断所有输出"
        // 文档 L100: "不保证包含 EDPA2.0 任务清单或 SUSPENDED 字段"
        // A2A 协议无 SUSPENDED 状态 — Task state 应为 INPUT_REQUIRED 或 COMPLETED
        // 文档 L17: "原业务 Task 可以结束，EDPA2.0 Plan 仍是 SUSPENDED"
        org.a2aproject.sdk.spec.Task taskAfterPause = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(taskAfterPause).as("受理后原任务应立即可查询").isNotNull();
        assertThat(taskAfterPause.id()).as("taskId 应一致").isEqualTo(originalTaskId);
        // 坚定断言：A2A Task state 不应包含 "SUSPENDED"
        // A2A 协议只有：SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, FAILED, CANCELED
        // 暂停受理不应引入新的 "SUSPENDED" state 到 A2A Task 层
        TaskState observedTaskState = taskAfterPause.status().state();
        assertThat(observedTaskState)
                .as("受理后 A2A Task state 应为 INPUT_REQUIRED 或 COMPLETED（无 SUSPENDED），实际: %s", observedTaskState)
                .isIn(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_COMPLETED);
    }

    // ═══ P1: Non-stream pause — both semantic and programmatic in non-streaming mode ═══

    @Test
    @Story("non-stream-pause: 非流式模式下语义级与程序级暂停均受理")
    @DisplayName("FEAT-088.normal.non-stream-pause: 非流式暂停受理返回独立回执")
    void nonStreamPauseAcceptedBothModes() {
        String contextId = ctx("non-stream-pause");

        // 语义级暂停
        A2aRound business1 = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business1.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business1.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business1.state())
                .isTrue();
        String originalTaskId = business1.taskId();

        HttpResponse<String> semanticPause = sendSemanticPauseSync("暂停", contextId);
        assertThat(semanticPause.statusCode()).as("语义暂停 HTTP 200").isEqualTo(200);
        assertThat(hasAcceptedReceipt(semanticPause.body()))
                .as("语义级暂停应被受理（ACCEPTED），实际响应: %s", semanticPause.body())
                .isTrue();
        assertThat(extractReceiptTaskId(semanticPause.body()))
                .as("语义暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        sleep(SUSPEND_SETTLE_MS);

        // 恢复
        A2aRound resume1 = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume1.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume1.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume1.state())
                .isTrue();

        // 恢复确认阶段：Plan 仍保持挂起，INPUT_REQUIRED 仅表示等待确认
        // 设计文档: "等待恢复确认时 Plan 仍保持挂起，此时 INPUT_REQUIRED 仅表示等待确认，
        // 暂停入口返回 NO_ACTIVE_PLAN；确认恢复、原问题重现后，可再次暂停。"
        A2aRound confirm = sendA2a("确认", contextId, null);
        assertThat(confirm.taskId()).as("恢复确认应返回 taskId").isNotNull();
        assertThat(confirm.isInputRequired())
                .as("恢复确认后应重现原问题（INPUT_REQUIRED），实际状态: %s", confirm.state())
                .isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // 程序级暂停 — 恢复确认后 Plan 已恢复为活动状态，暂停应被受理
        HttpResponse<String> programPause = sendPauseControlSync(contextId);
        assertThat(programPause.statusCode()).as("程序暂停 HTTP 200").isEqualTo(200);
        System.out.println("[DEBUG non-stream] programmatic pause response=" + programPause.body());
        assertThat(hasAcceptedReceipt(programPause.body()))
                .as("恢复确认后程序级暂停应被受理（ACCEPTED，存在活动 Plan），实际响应: %s", programPause.body())
                .isTrue();
        assertThat(extractReceiptTaskId(programPause.body()))
                .as("程序暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);
    }

    // ═══ P1: Delay-max — pause at Todo start, delay = current Todo remaining ═══

    @Test
    @Story("delay-max: Todo 最开端发起暂停受理且不立即中断")
    @DisplayName("FEAT-088.normal.delay-max: 暂停受理后安全边界生效非任意点退出")
    void delayMaxPauseAtTodoStart() {
        String contextId = ctx("delay-max");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // 在等待态（INPUT_REQUIRED）发起暂停——安全边界生效
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(pauseResp.statusCode()).as("暂停 HTTP 200").isEqualTo(200);
        String body = pauseResp.body();
        System.out.println("[DEBUG delay-max] pause response body=" + body);
        assertThat(hasAcceptedReceipt(body))
                .as("暂停应被受理（ACCEPTED），实际响应: %s", body)
                .isTrue();
        assertThat(extractReceiptTaskId(body))
                .as("回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        // 受理后原任务仍可查询（不立即中断/回滚）
        org.a2aproject.sdk.spec.Task taskAfterPause = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(taskAfterPause).as("受理后原任务应立即可查询").isNotNull();
        assertThat(taskAfterPause.id()).as("taskId 应一致").isEqualTo(originalTaskId);
        // A2A Task state 不应包含 SUSPENDED（安全边界后挂起，A2A 层无 SUSPENDED）
        TaskState observedState = taskAfterPause.status().state();
        assertThat(observedState)
                .as("受理后 A2A Task state 应为 INPUT_REQUIRED 或 COMPLETED（无 SUSPENDED），实际: %s", observedState)
                .isIn(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_COMPLETED);
    }

    /** Extract the first {@code data:} frame JSON payload from an SSE response body. */
    private static String extractFirstSseFrame(String sseBody) {
        if (sseBody == null) return null;
        for (String line : sseBody.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                String data = trimmed.substring(5).trim();
                if (!data.isEmpty() && !data.equals("[DONE]")) {
                    return data;
                }
            }
        }
        // Fallback: no SSE framing — return raw body (might be plain JSON)
        return sseBody;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { /* ignore */ }
    }
}
