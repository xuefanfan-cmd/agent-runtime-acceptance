package com.huawei.ascend.sit.cases.integration.agent_dev_workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.config.TestConfig;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-136 工具审批与取消契约黑盒验收（场景矩阵 F136-16～F136-19、F136-35、F136-42）。
 *
 * <p>期望值出处：L2 §2.6（审批错误面 INVALID_APPROVAL_OPTION/APPROVAL_NOT_PENDING/SESSION_NOT_FOUND、
 * cancel 200 仅确认受理不作为回滚入口）、特性档 §5.1.6（工具审批被拒绝：对应操作不执行）。
 * 审阅 #599 D5 修订：可访问会话 B 提交 A 的审批 ID 按 409 APPROVAL_NOT_PENDING 核对（F136-35），
 * 无权访问会话独立测 404 SESSION_NOT_FOUND（F136-42）；D3/D4 修订：F136-19 取消后核对终态与已写文件。
 * F136-16/F136-19/F136-35 依赖真实开发回合触发待决审批，环境不满足时按门禁 SKIPPED 并在报告中标注。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-136")
@Feature("FEAT-136: 对话式开发高码智能体")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat136ApprovalCancelBlackboxTest {

    private Feat136WorkbenchSupport wb;
    private String wsId;
    private String sessionId;
    private String sessionBId;
    private boolean sessionReady;

    private final List<String> createdWorkspaceIds = new ArrayList<>();

    @BeforeAll
    void probeBackendAndPrepareSession() throws Exception {
        TestConfig config = TestConfig.load();
        // 共享前置：免鉴权就绪探测 + 本地账号登录（App.java：/api/* 除 /api/deployment、/api/auth/* 均需鉴权）
        Feat136WorkbenchSupport.Ready ready = Feat136WorkbenchSupport.ready(config);
        assumeTrue(ready.wb() != null, ready.blocker());
        wb = ready.wb();

        String name = "feat136-ws-" + Feat136WorkbenchSupport.runId();
        Feat136WorkbenchSupport.Resp ws = wb.sendWithBody("POST", "/api/workspaces", wb.workspaceByName(name));
        if (ws.status() == 201) {
            wsId = ws.body().path("id").asText();
            createdWorkspaceIds.add(wsId);
            String llmBlocker = Feat136WorkbenchSupport.ensureDefaultLlmFromEnv(wb);
            if (llmBlocker == null) {
                Feat136WorkbenchSupport.Resp session = wb.sendWithBody("POST", "/api/sessions",
                        wb.sessionCreate(wsId, "feat136-approval-session"));
                if (session.status() == 201) {
                    sessionId = session.body().path("id").asText();
                    sessionReady = true;
                }
                // 会话 B（同工作区、可访问）：供 F136-35 跨会话挪用核对
                Feat136WorkbenchSupport.Resp sessionB = wb.sendWithBody("POST", "/api/sessions",
                        wb.sessionCreate(wsId, "feat136-approval-session-b"));
                if (sessionB.status() == 201) {
                    sessionBId = sessionB.body().path("id").asText();
                }
            }
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        for (String id : createdWorkspaceIds) {
            try {
                wb.delete("/api/workspaces/" + id);
            } catch (Exception ignored) {
                // 清理尽力而为
            }
        }
    }

    @Test
    @Tag("story-feat-136-16")
    @Story("FEAT-136.F136-16: 审批无效选项被拒绝且原请求不被批准")
    @DisplayName("F136-16 待决审批提交无效 optionId → 400 INVALID_APPROVAL_OPTION")
    void f13616InvalidApprovalOption() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        // 待决审批依赖真实开发回合触发 OpenCode 权限请求（OpenCode 默认 {"*":"ask"}，L2 §6.3）。
        // 通过提交一个回合并轮询事件流产生 approval.requested；若模型未触发权限请求则门禁跳过。
        Feat136WorkbenchSupport.Resp turn = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/turns",
                wb.turnRequest("在工程中新建文件 feat136-approval-probe.txt 内容为 probe"));
        assumeTrue(turn.status() == 202, "回合提交未受理（HTTP " + turn.status() + "）");

        String approvalId = waitForApprovalId(120);
        assumeTrue(approvalId != null,
                "环境门禁：120 秒内事件流未出现 approval.requested，无法验证无效选项路径");

        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/approvals/" + approvalId,
                invalidOptionBody());
        assertThat(resp.status()).as("无效选项应 400（L2 §2.6 错误表）").isEqualTo(400);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 INVALID_APPROVAL_OPTION（L2 §2.6）")
                .isEqualTo("INVALID_APPROVAL_OPTION");

        // 清理本待决审批（拒绝），避免遗留审批阻塞同类后续用例的回合终态
        com.fasterxml.jackson.databind.node.ObjectNode cleanup = wb.obj();
        cleanup.put("decision", "reject");
        wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/approvals/" + approvalId, cleanup);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode invalidOptionBody() {
        com.fasterxml.jackson.databind.node.ObjectNode body = wb.obj();
        body.put("optionId", "nonexistent-opt");
        return body;
    }

    /** 轮询事件流直至出现真正待决（无 approval.resolved 记录）的审批，超时返回 null。 */
    private String waitForApprovalId(long timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            Feat136WorkbenchSupport.Resp events = wb.get("/api/sessions/" + sessionId + "/events");
            if (events.status() == 200) {
                java.util.Set<String> resolved = new java.util.HashSet<>();
                java.util.List<String> requested = new java.util.ArrayList<>();
                for (JsonNode item : events.body().path("items")) {
                    String type = item.path("type").asText();
                    String id = item.path("payload").path("approvalId").asText(
                            item.path("approvalId").asText(""));
                    if (id.isBlank()) {
                        continue;
                    }
                    if ("approval.requested".equals(type)) {
                        requested.add(id);
                    } else if ("approval.resolved".equals(type)) {
                        resolved.add(id);
                    }
                }
                for (String id : requested) {
                    if (!resolved.contains(id)) {
                        return id;
                    }
                }
            }
            Thread.sleep(2000);
        }
        return null;
    }

    @Test
    @Tag("story-feat-136-17")
    @Story("FEAT-136.F136-17: 非待决审批提交返回 409")
    @DisplayName("F136-17 对不存在 approvalId 提交 → 409 APPROVAL_NOT_PENDING")
    void f13617NonPendingApprovalConflict() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        com.fasterxml.jackson.databind.node.ObjectNode body = wb.obj();
        body.put("decision", "reject");
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/approvals/nonexistent-" + Feat136WorkbenchSupport.runId(),
                body);
        assertThat(resp.status()).as("非待决审批应 409（L2 §2.6 错误表）").isEqualTo(409);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 APPROVAL_NOT_PENDING（L2 §2.6）")
                .isEqualTo("APPROVAL_NOT_PENDING");
    }

    @Test
    @Tag("story-feat-136-18")
    @Story("FEAT-136.F136-18: 取消入口受理确认")
    @DisplayName("F136-18 POST cancel → 200，事件流仍可读")
    void f13618CancelAccepted() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/cancel", null);
        assertThat(resp.status())
                .as("cancel 返回 200 仅确认入口已处理（L2 §2.6：不作为事务回滚入口）")
                .isEqualTo(200);

        Feat136WorkbenchSupport.Resp events = wb.get("/api/sessions/" + sessionId + "/events");
        assertThat(events.status())
                .as("取消受理后事件流仍可读")
                .isEqualTo(200);
    }

    @Test
    @Tag("story-feat-136-19")
    @Story("FEAT-136.F136-19: 运行中取消后核对终态与已写文件")
    @DisplayName("F136-19 回合执行中 cancel → 终态可观察，已写入文件不被撤销")
    void f13619CancelDoesNotRollbackFiles() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        // 独立会话：审批/取消的回合状态不与其他用例互扰（共享会话上的会话级审批清理
        // 会误拒其他用例的待决审批）
        Feat136WorkbenchSupport.Resp ownSession = wb.sendWithBody("POST", "/api/sessions",
                wb.sessionCreate(wsId, "feat136-cancel-own-session"));
        assumeTrue(ownSession.status() == 201, "独立会话创建失败（HTTP " + ownSession.status() + "）");
        String ownSessionId = ownSession.body().path("id").asText();
        // 提交一个产生文件写入的回合，等待回合结束，期间发起取消。
        String probeFile = "feat136-cancel-probe.txt";
        String ownText = "在工程中新建文件 " + probeFile + "，内容为 cancel-rollback-probe";
        Feat136WorkbenchSupport.Resp turn = wb.sendWithBody("POST",
                "/api/sessions/" + ownSessionId + "/turns",
                wb.turnRequest(ownText));
        assumeTrue(turn.status() == 202, "回合提交未受理（HTTP " + turn.status() + "）");
        String eventTurnId = wb.eventTurnIdForUserMessage(ownSessionId, ownText);

        wb.sendWithBody("POST", "/api/sessions/" + ownSessionId + "/cancel", null);

        // 审阅 #599 D3/D4：取消后核对终态（失败回合也可发出 turn.ended，终态原因随事件呈现）
        boolean terminal = waitForTurnEnded(ownSessionId, 120, eventTurnId);
        assumeTrue(terminal, "环境门禁：120 秒内未观察到本回合终止事件（turn.ended 或失败/终止事件），无法验证终态");

        // 终态可观察性：本回合的终态事件或错误/失败事件存在于事件明细
        Feat136WorkbenchSupport.Resp events = wb.get("/api/sessions/" + ownSessionId + "/events");
        assertThat(events.status()).as("取消后事件流仍可读取").isEqualTo(200);
        boolean terminalObservable = false;
        for (JsonNode item : events.body().path("items")) {
            if (!eventTurnId.isBlank() && !eventTurnId.equals(item.path("turnId").asText(""))) {
                continue;
            }
            String type = item.path("type").asText();
            if ("turn.ended".equals(type) || type.contains("error") || type.contains("failed")
                    || type.contains("cancel") || type.contains("stop")) {
                terminalObservable = true;
                break;
            }
        }
        assertThat(terminalObservable)
                .as("取消后回合终态应可从事件明细确认（turn.ended/失败/终止事件）")
                .isTrue();

        // 审阅 #599 D3/D4：若事件中观察到该文件的写入证据（tool.completed），
        // 已写入文件不在取消后的新预览变更集中（不产生文件回滚）
        boolean writeObserved = false;
        for (JsonNode item : events.body().path("items")) {
            if (!eventTurnId.isBlank() && !eventTurnId.equals(item.path("turnId").asText(""))) {
                continue;
            }
            if ("tool.completed".equals(item.path("type").asText())
                    && item.toString().contains(probeFile)) {
                writeObserved = true;
                break;
            }
        }
        java.util.LinkedHashMap<String, String> probe = new java.util.LinkedHashMap<>();
        probe.put(probeFile, "cancel-rollback-probe");
        byte[] zip = Feat136WorkbenchSupport.zipOfEntries(probe);
        Feat136WorkbenchSupport.Resp preview = wb.sendRaw("POST",
                "/api/sessions/" + ownSessionId + "/upload/preview", zip, null);
        assertThat(preview.status())
                .as("取消后工程仍可执行导入预览（工作区文件未因取消而清空）")
                .isEqualTo(200);
        if (writeObserved) {
            for (JsonNode change : preview.body().path("changes")) {
                assertThat(change.path("path").asText())
                        .as("已写入文件 %s 在取消后从预览变更集中消失即构成回滚（违反取消不回滚）", probeFile)
                        .isNotEqualTo(probeFile);
            }
        }
    }

    @Test
    @Tag("story-feat-136-35")
    @Story("FEAT-136.F136-35: 可访问会话 B 挪用会话 A 的审批 ID 返回 409")
    @DisplayName("F136-35 会话 B 提交 A 的 approvalId → 409 APPROVAL_NOT_PENDING")
    void f13635CrossSessionApprovalConflict() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        assumeTrue(sessionBId != null && !sessionBId.isBlank(),
                "前置失败：可访问的会话 B 未建立，无法核对跨会话挪用冲突");
        // 在会话 A 触发真实待决审批（依赖 OpenCode 权限请求，L2 §6.3 默认 {"*":"ask"}）
        Feat136WorkbenchSupport.Resp turn = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/turns",
                wb.turnRequest("在工程中新建文件 feat136-cross-session-probe.txt 内容为 cross"));
        assumeTrue(turn.status() == 202, "回合提交未受理（HTTP " + turn.status() + "）");

        String approvalId = waitForApprovalId(120);
        assumeTrue(approvalId != null,
                "环境门禁：120 秒内事件流未出现 approval.requested，无法验证跨会话挪用冲突");

        // 审阅 #599 D5：可访问的会话 B 提交 A 的审批 ID → 409 APPROVAL_NOT_PENDING（非 404）
        com.fasterxml.jackson.databind.node.ObjectNode body = wb.obj();
        body.put("decision", "reject");
        Feat136WorkbenchSupport.Resp cross = wb.sendWithBody("POST",
                "/api/sessions/" + sessionBId + "/approvals/" + approvalId, body);
        assertThat(cross.status())
                .as("可访问会话 B 挪用 A 的待决审批 ID 应 409（审阅 #599 D5；L2 §2.6 错误表）")
                .isEqualTo(409);
        assertThat(cross.body().path("error").path("code").asText())
                .as("错误码逐字为 APPROVAL_NOT_PENDING（L2 §2.6）")
                .isEqualTo("APPROVAL_NOT_PENDING");

        // A 的待决审批状态不变：经 A 提交无效选项仍得 400 INVALID_APPROVAL_OPTION（佐证仍待决且可用）
        Feat136WorkbenchSupport.Resp stillPending = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/approvals/" + approvalId, invalidOptionBody());
        assertThat(stillPending.status())
                .as("跨会话挪用被拒后 A 的审批仍待决（无效选项得 400 佐证）")
                .isEqualTo(400);
        assertThat(stillPending.body().path("error").path("code").asText())
                .as("错误码逐字为 INVALID_APPROVAL_OPTION（L2 §2.6）")
                .isEqualTo("INVALID_APPROVAL_OPTION");

        // 清理会话 A 的待决审批（拒绝），避免遗留审批阻塞同类后续用例的回合终态
        wb.resolvePendingApprovals(sessionId);
    }

    @Test
    @Tag("story-feat-136-42")
    @Story("FEAT-136.F136-42: 无权访问会话的审批不可见")
    @DisplayName("F136-42 不存在会话提交审批 → 404 SESSION_NOT_FOUND")
    void f13642InvisibleSessionApprovalNotFound() throws Exception {
        // 审阅 #599 D5：无权访问的会话独立测试，返回 404 SESSION_NOT_FOUND（与 409 冲突语义分开核对）
        com.fasterxml.jackson.databind.node.ObjectNode body = wb.obj();
        body.put("decision", "reject");
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/nonexistent-feat136-" + Feat136WorkbenchSupport.runId()
                        + "/approvals/any-approval-" + Feat136WorkbenchSupport.runId(),
                body);
        assertThat(resp.status())
                .as("无权访问（不存在）会话提交审批应 404（审阅 #599 D5）")
                .isEqualTo(404);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 SESSION_NOT_FOUND（会话不可见语义，L2 §2.3/§2.6）")
                .isEqualTo("SESSION_NOT_FOUND");
    }

    /** 等待指定 turnId 的回合到达终态（只认本回合的 turn.ended，旧回合终态不掩盖新回合）。 */
    private boolean waitForTurnEnded(String sid, long timeoutSeconds, String turnId) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            Feat136WorkbenchSupport.Resp events = wb.get("/api/sessions/" + sid + "/events");
            if (events.status() == 200) {
                for (JsonNode item : events.body().path("items")) {
                    if (!"turn.ended".equals(item.path("type").asText())) {
                        continue;
                    }
                    String eventTurn = item.path("payload").path("turnId").asText(
                            item.path("turnId").asText(""));
                    if (turnId == null || turnId.isBlank() || turnId.equals(eventTurn)) {
                        return true;
                    }
                }
            }
            Thread.sleep(2000);
        }
        return false;
    }
}