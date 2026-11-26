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
 * FEAT-088 linkage acceptance tests (cross-feature with FEAT-089).
 *
 * <p>FEAT-089 long-pause recovery scenarios are blocked.
 * The REST→A2A cross-protocol test is executable now.</p>
 */
@DisplayName("FEAT-088 FEAT-089 联动场景")
class Feat089LinkageAcceptanceTest extends Edpa2PauseResumeTestBase {

    private static final String BUSINESS_PROMPT =
            "给我安排杭州3天旅行，只生成简单景点安排。出发日期和人数稍后告诉你，请先向我询问。";

    private static final long SUSPEND_SETTLE_MS = 10000;

    @Test
    @Story("interrupt-then-longpause-recovery: 暂停后超 TTL 恢复")
    @DisplayName("FEAT-088.cross.interrupt-then-longpause-recovery: 超TTL走FEAT-089路径恢复")
    void interruptThenLongPauseRecovery() {
        // FEAT-089 TTL 超时恢复在当前环境无法触发（TTL 默认 24h）
        // 可测部分：暂停→恢复完整链路验证（同 session 内恢复）
        // 当 FEAT-089 实现后，此用例应增加 sleep(TTL) 后跨 session 恢复验证
        String contextId = ctx("longpause-recovery");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // 暂停
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();
        assertThat(extractReceiptTaskId(pauseResp.body()))
                .as("回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        sleep(SUSPEND_SETTLE_MS);

        // 恢复（同 session 内——FEAT-089 超TTL场景待实现后补充）
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.taskId())
                .as("恢复 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume.state())
                .isTrue();

        // 原任务仍可查询——挂起记录未因恢复而丢失
        org.a2aproject.sdk.spec.Task taskAfterResume = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(taskAfterResume).as("恢复后原任务应仍可查询").isNotNull();
        assertThat(taskAfterResume.id()).as("taskId 应一致").isEqualTo(originalTaskId);

        System.out.println("[FEAT-089 blocked] 同 session 恢复验证通过 — 超 TTL 跨 session 恢复待 FEAT-089 实现");
    }

    @Test
    @Story("interrupt-success-save-failure: 暂停成功+保存失败联动")
    @DisplayName("FEAT-088.cross.interrupt-success-save-failure: 暂停不回滚+保存失败有日志")
    void interruptSuccessSaveFailureLinkage() {
        // FEAT-089 保存失败联动需故障注入，当前环境无 Toxiproxy
        // 可测部分：暂停成功后原任务不回滚（数据一致性）
        String contextId = ctx("save-failure-linkage");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // 暂停——即使内部保存有异常，暂停受理不应回滚已开始的业务 Task
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body()))
                .as("暂停应被受理，实际响应: %s", pauseResp.body())
                .isTrue();
        assertThat(extractReceiptTaskId(pauseResp.body()))
                .as("回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        // 原任务仍可查询——暂停不回滚
        org.a2aproject.sdk.spec.Task task = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(task).as("暂停后原任务应可查询（不回滚）").isNotNull();
        assertThat(task.id()).as("taskId 应一致").isEqualTo(originalTaskId);

        sleep(SUSPEND_SETTLE_MS);

        // 恢复后仍能重现原问题——数据未丢失
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("恢复应返回 taskId").isNotNull();
        assertThat(resume.isInputRequired())
                .as("恢复后应重现原问题（INPUT_REQUIRED），数据未丢失，实际状态: %s", resume.state())
                .isTrue();

        System.out.println("[FEAT-089 blocked] 暂停不回滚验证通过 — 保存失败故障注入待 FEAT-089 实现");
    }

    @Test
    @Story("interrupt-then-longpause-newcmd: 暂停后超TTL新指令恢复上下文")
    @DisplayName("FEAT-088.cross.interrupt-then-longpause-newcmd: 超TTL新指令先恢复上下文")
    void interruptThenLongPauseNewCmd() {
        // FEAT-089 超 TTL 新指令恢复在当前环境无法触发（TTL 默认 24h）
        // 可测部分：暂停后新指令（非"继续"）的正常行为验证
        String contextId = ctx("longpause-newcmd");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED").isTrue();
        String originalTaskId = business.taskId();

        // 暂停
        HttpResponse<String> pauseResp = sendPauseControlSync(contextId);
        assertThat(hasAcceptedReceipt(pauseResp.body())).as("暂停应被受理").isTrue();

        sleep(SUSPEND_SETTLE_MS);

        // 发送新指令（非"继续上一次任务"）——应作为新任务处理，不恢复挂起的 Plan
        A2aRound newCmd = sendA2a("帮我查询北京的景点", contextId, null);
        assertThat(newCmd.taskId()).as("新指令应返回 taskId").isNotNull();
        assertThat(newCmd.taskId())
                .as("新指令 taskId 应不同于原业务 taskId")
                .isNotEqualTo(originalTaskId);
        assertThat(newCmd.isCompleted())
                .as("新指令应正常完成（非恢复挂起 Plan），实际状态: %s", newCmd.state())
                .isTrue();

        // 原任务仍可查询——新指令不覆盖挂起记录
        org.a2aproject.sdk.spec.Task task = client(EDPA2_L1).getTask(originalTaskId);
        assertThat(task).as("新指令后原任务应仍可查询（挂起记录未覆盖）").isNotNull();
        assertThat(task.id()).as("taskId 应一致").isEqualTo(originalTaskId);

        System.out.println("[FEAT-089 blocked] 新指令不恢复挂起Plan验证通过 — 超 TTL 新指令恢复待 FEAT-089 实现");
    }

    @Test
    @Story("rest-pause-a2a-resume: REST暂停→A2A恢复跨协议")
    @DisplayName("FEAT-088.cross.rest-pause-a2a-resume: 跨协议暂停恢复衔接正常")
    void restPauseA2aResumeCrossProtocol() {
        String contextId = ctx("cross-protocol");

        A2aRound business = sendA2a(BUSINESS_PROMPT, contextId, null);
        assertThat(business.taskId()).as("应获得 taskId").isNotNull();
        assertThat(business.isInputRequired())
                .as("业务任务应到达 INPUT_REQUIRED，实际状态: %s", business.state())
                .isTrue();
        String originalTaskId = business.taskId();

        // Pause via Custom REST
        // 注意：当前存在 REST bug（MoaCustomRestAdapter 丢失 moa.control 语义），
        // REST 暂停返回 task_failed 而非 ACCEPTED。此用例在 REST bug 修复前会失败。
        HttpResponse<String> restPause = sendRestPause(contextId, false);
        assertThat(restPause.statusCode()).as("REST 暂停 HTTP 200").isEqualTo(200);
        String restBody = restPause.body();
        System.out.println("[DEBUG cross-protocol] REST pause response body=" + restBody);
        assertThat(hasAcceptedReceipt(restBody))
                .as("REST 暂停应被受理（REST bug 修复后此断言应通过），实际响应: %s", restBody)
                .isTrue();
        // 断言：回执 Task ID 应与原业务 Task 不同
        assertThat(extractReceiptTaskId(restBody))
                .as("REST 暂停回执 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);

        // Wait for suspension to take effect
        sleep(SUSPEND_SETTLE_MS);

        // Resume via A2A (cross-protocol)
        A2aRound resume = sendA2a("继续上一次任务", contextId, null);
        assertThat(resume.taskId()).as("跨协议恢复应返回 taskId").isNotNull();
        assertThat(resume.isInputRequired())
                .as("跨协议恢复后应重现原问题（INPUT_REQUIRED），实际状态: %s", resume.state())
                .isTrue();
        // 断言：恢复后的 taskId 应不同于原 taskId
        assertThat(resume.taskId())
                .as("跨协议恢复 Task ID 应不同于原业务 Task ID")
                .isNotEqualTo(originalTaskId);
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { /* ignore */ }
    }
}
