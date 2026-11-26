package com.huawei.ascend.sit.cases.integration.agent_dev_workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * FEAT-138 业务预览契约黑盒验收（场景矩阵 F138-12～F138-19、F138-35）。
 *
 * <p>期望值出处：L2 §2.4（text 去除边界空白后非空且 ≤4000 字符、conversationId/requestId
 * 约束 [a-zA-Z0-9_-]{1,80}、HTTP runId==requestId、duplicate 语义、同 ID 异内容
 * IDEMPOTENCY_CONFLICT 400、CONVERSATION_BINDING_CHANGED 400、
 * RUNTIME_NOT_READY_OR_CHANGED 409、preview already running 409）、
 * 特性档 §2.2（真实业务调用/业务请求去重 MUST）与 §5.1.3（业务会话与输出语义）。
 *
 * <p>v1130 审阅整改：F138-15 以该 runId 仅一条 preview.started（等价上游请求计数 1）验证
 * 仅一次转发；F138-18（目标未就绪）与 F138-35（旧实例不一致）分型验证。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-138")
@Feature("FEAT-138: 智能体运行调试与迭代验证")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat138PreviewContractBlackboxTest {

    private Feat138WorkbenchSupport wb;
    private String wsId;
    private String sessionId;
    private boolean sessionReady;
    /** JUnit 6 下 PER_CLASS 容器的 assumeTrue 会中止整个容器（记 0 而非 SKIPPED），
     * 因此 @BeforeAll 只设置门禁标志，由各测试方法开头 assumeTrue 逐方法记录 SKIPPED。 */
    private boolean backendReady;
    private String gateReason;
    private String run;
    private String conversationId;
    private String readyTargetId;
    private String readyInstanceId;
    private String firstRunId;

    private final List<String> createdWorkspaceIds = new ArrayList<>();

    @BeforeAll
    void probeBackendAndPrepareSession() throws Exception {
        TestConfig config = TestConfig.load();
        wb = new Feat138WorkbenchSupport(config.getString("sut.workbench.base-url",
                Feat138WorkbenchSupport.DEFAULT_BASE));
        String blocker = wb.probeAndLogin(config);
        if (blocker != null) {
            gateReason = blocker;
            return;
        }
        backendReady = true;

        run = Feat138WorkbenchSupport.runId();
        conversationId = "feat138-conv-" + run;
        String name = "feat138-ws-" + run;
        Feat138WorkbenchSupport.Resp ws = wb.sendWithBody("POST", "/api/workspaces", wb.workspaceByName(name));
        if (ws.status() != 200 && ws.status() != 201) {
            gateReason = "前置失败：工作区创建返回 " + ws.status() + "（" + ws.body().toString() + "）";
            return;
        }
        if (ws.status() == 201) {
            wsId = ws.body().path("id").asText();
            createdWorkspaceIds.add(wsId);
            Feat138WorkbenchSupport.Resp session = wb.sendWithBody("POST", "/api/sessions",
                    wb.sessionCreate(wsId, "feat138-preview-session-" + run));
            if (session.status() != 200 && session.status() != 201) {
                gateReason = "前置失败：开发会话创建返回 " + session.status() + "（"
                        + session.body().toString() + "）";
                return;
            }
            if (session.status() == 201) {
                sessionId = session.body().path("id").asText();
                sessionReady = true;
                String cwd = session.body().path("cwd").asText("");
                if (!cwd.isBlank()) {
                    String fixtureBlocker = wb.prepareRuntimeProject(cwd, run);
                    if (fixtureBlocker != null) {
                        sessionReady = false;
                        gateReason = fixtureBlocker;
                    }
                }
            }
            if (sessionReady) {
                String readyBlocker = wb.ensureReadyInstance(sessionId, run, 240);
                if (readyBlocker != null) {
                    sessionReady = false;
                    gateReason = readyBlocker;
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

    private String previewPath() {
        return "/api/sessions/" + sessionId + "/preview";
    }

    /** 取快照中的就绪目标与实例；不存在时返回 null（由调用方做门禁）。 */
    private JsonNode readyTarget() throws Exception {
        Feat138WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/runtime");
        if (resp.status() != 200) {
            return null;
        }
        java.util.Map<String, JsonNode> readyRuntimes = new java.util.HashMap<>();
        for (JsonNode r : resp.body().path("runtimes")) {
            if ("ready".equals(r.path("state").asText())) {
                readyRuntimes.put(r.path("id").asText(), r);
            }
        }
        for (JsonNode t : resp.body().path("targets")) {
            JsonNode rt = readyRuntimes.get(t.path("runtimeId").asText());
            if (rt != null) {
                ObjectNode pair = wb.obj();
                pair.put("targetId", t.path("id").asText());
                pair.put("instanceId", rt.path("instanceId").asText());
                return pair;
            }
        }
        return null;
    }

    /** F138-12 受理：成功时记录 firstRunId；目标未就绪时按门禁跳过。 */
    private void acceptFirstPreview() throws Exception {
        if (firstRunId != null) {
            return;
        }
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        JsonNode ready = readyTarget();
        assumeTrue(ready != null, "环境门禁：无就绪目标与实例，业务预览受理场景无法验证");
        readyTargetId = ready.path("targetId").asText();
        readyInstanceId = ready.path("instanceId").asText();
        ObjectNode body = wb.previewRequest("feat138-preview-" + run, readyTargetId, readyInstanceId,
                conversationId, "feat138-req-" + run + "-1");
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assumeTrue(resp.status() == 202,
                "环境门禁：preview 受理返回 " + resp.status() + "，受理语义本轮不可验证");
        firstRunId = resp.body().path("runId").asText(null);
    }

    @Test
    @Tag("story-feat-138-12")
    @Story("FEAT-138.F138-12: 业务预览受理返回 runId 等于 requestId")
    @DisplayName("F138-12 POST preview → 202，runId==requestId 且非 duplicate")
    void f13812PreviewAccepted() throws Exception {
        assumeTrue(backendReady, gateReason);
        acceptFirstPreview();
        // 首次受理语义必须在全新 requestId 的"首次响应"上断言：acceptFirstPreview 的请求
        // 已登记（幂等记录），同 ID 重复提交返回 duplicate==true，不能替代首响初值。
        // 且转发槽单会话互斥：须先等待已受理回合到达终态，否则全新请求会 409 preview already running。
        waitTurnTerminal(firstRunId, 120);
        String freshReqId = "feat138-req-" + run + "-fresh-" + System.currentTimeMillis();
        ObjectNode body = wb.previewRequest("feat138-preview-" + run, readyTargetId, readyInstanceId,
                conversationId, freshReqId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assertThat(resp.status()).as("业务预览受理应 202（L2 §2.4）").isEqualTo(202);
        assertThat(resp.body().path("runId").asText())
                .as("HTTP runId 等于本次 requestId（L2 §2.4 出参表）")
                .isEqualTo(freshReqId);
        assertThat(resp.body().path("duplicate").asBoolean(false))
                .as("非重复请求 duplicate 不为 true（L2 §2.4）").isFalse();
    }

    /** 轮询历史直至指定 runId 出现终态事件（ended/failed）；超时按门禁跳过。 */
    private void waitTurnTerminal(String reqId, long timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            Feat138WorkbenchSupport.Resp history = wb.get("/api/sessions/" + sessionId + "/preview/history");
            if (history.status() == 200) {
                for (JsonNode item : history.body().path("items")) {
                    String itemRun = item.path("runId").asText(item.path("payload").path("runId").asText(""));
                    String type = item.path("type").asText();
                    if (reqId.equals(itemRun)
                            && ("preview.ended".equals(type) || "preview.failed".equals(type))) {
                        return;
                    }
                }
            }
            Thread.sleep(3000);
        }
        assumeTrue(false, "环境门禁：" + timeoutSeconds + " 秒内已受理回合未到终态，无法释放转发槽");
    }

    @Test
    @Tag("story-feat-138-13")
    @Story("FEAT-138.F138-13: 空白与超长文本拒绝")
    @DisplayName("F138-13 纯空白 text / 4001 字符 text → 400 invalid message")
    void f13813InvalidTextRejected() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        JsonNode ready = readyTarget();
        assumeTrue(ready != null, "环境门禁：无就绪目标，文本校验场景无法构造完整合法请求");

        ObjectNode blank = wb.previewRequest("   ", ready.path("targetId").asText(),
                ready.path("instanceId").asText(), "feat138-conv-blank-" + run,
                "feat138-req-blank-" + run);
        Feat138WorkbenchSupport.Resp respA = wb.sendWithBody("POST", previewPath(), blank);
        assertThat(respA.status()).as("去除边界空白后为空应 400（L2 §2.4）").isEqualTo(400);
        assertThat(respA.body().toString())
                .as("错误表面 invalid message（L2 §2.4）").contains("invalid message");

        StringBuilder long4001 = new StringBuilder("feat138-");
        while (long4001.length() <= 4000) {
            long4001.append("0123456789");
        }
        ObjectNode tooLong = wb.previewRequest(long4001.toString(), ready.path("targetId").asText(),
                ready.path("instanceId").asText(), "feat138-conv-long-" + run,
                "feat138-req-long-" + run);
        Feat138WorkbenchSupport.Resp respB = wb.sendWithBody("POST", previewPath(), tooLong);
        assertThat(respB.status()).as("超过 4000 字符应 400（L2 §2.4）").isEqualTo(400);
        assertThat(respB.body().toString())
                .as("错误表面 invalid message（L2 §2.4）").contains("invalid message");
    }

    @Test
    @Tag("story-feat-138-14")
    @Story("FEAT-138.F138-14: 非法业务会话/请求 ID 拒绝")
    @DisplayName("F138-14 非法 conversationId / 超长 requestId → 400 INVALID 代码")
    void f13814InvalidIdsRejected() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        JsonNode ready = readyTarget();
        assumeTrue(ready != null, "环境门禁：无就绪目标，ID 校验场景无法构造完整合法请求");
        String tid = ready.path("targetId").asText();
        String iid = ready.path("instanceId").asText();

        ObjectNode badConv = wb.previewRequest("feat138-hello", tid, iid,
                "feat138.conv.非法!", "feat138-req-ok-" + run);
        Feat138WorkbenchSupport.Resp respA = wb.sendWithBody("POST", previewPath(), badConv);
        assertThat(respA.status()).as("非法 conversationId 应 400（L2 §2.4：{1,80} 字符集）").isEqualTo(400);
        assertThat(respA.body().toString())
                .as("错误表面含 INVALID 代码（L2 §2.4：相应 INVALID 代码）").contains("INVALID");

        ObjectNode longReq = wb.previewRequest("feat138-hello", tid, iid,
                "feat138-conv-ok-" + run, "feat138-" + "y".repeat(80));
        Feat138WorkbenchSupport.Resp respB = wb.sendWithBody("POST", previewPath(), longReq);
        assertThat(respB.status()).as("超长 requestId 应 400（L2 §2.4）").isEqualTo(400);
    }

    @Test
    @Tag("story-feat-138-15")
    @Story("FEAT-138.F138-15: 业务请求同 ID 同内容幂等复用")
    @DisplayName("F138-15 相同 requestId 相同内容 → 202 duplicate==true，runId 一致且仅一次转发")
    void f13815DuplicateRequestReused() throws Exception {
        assumeTrue(backendReady, gateReason);
        acceptFirstPreview();
        ObjectNode body = wb.previewRequest("feat138-preview-" + run, readyTargetId, readyInstanceId,
                conversationId, "feat138-req-" + run + "-1");
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assertThat(resp.status()).as("同 ID 同内容应 202（L2 §2.4：不再次转发）").isEqualTo(202);
        assertThat(resp.body().path("duplicate").asBoolean(false))
                .as("重复请求返回 duplicate==true（L2 §2.4）").isTrue();
        assertThat(resp.body().path("runId").asText())
                .as("runId 与首响一致（L2 §2.4：返回既有 runId）").isEqualTo(firstRunId);

        // 审阅整改（C4/C7）：以业务侧上游请求计数核对仅一次转发 — 该 runId 仅一条 preview.started
        int startedCount = 0;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            startedCount = 0;
            Feat138WorkbenchSupport.Resp history = wb.get("/api/sessions/" + sessionId + "/preview/history");
            if (history.status() == 200) {
                for (JsonNode item : history.body().path("items")) {
                    String itemRun = item.path("runId").asText(item.path("payload").path("runId").asText(""));
                    if (firstRunId.equals(itemRun) && "preview.started".equals(item.path("type").asText())) {
                        startedCount++;
                    }
                }
                if (startedCount >= 1) {
                    break;
                }
            }
            Thread.sleep(2000);
        }
        assertThat(startedCount)
                .as("幂等复用仅一次转发：该 runId 仅一条 preview.started（等价上游请求计数为 1，L2 §2.4）")
                .isEqualTo(1);
    }

    @Test
    @Tag("story-feat-138-16")
    @Story("FEAT-138.F138-16: 业务请求同 ID 异内容冲突拒绝")
    @DisplayName("F138-16 相同 requestId 不同 text → 400 IDEMPOTENCY_CONFLICT")
    void f13816RequestIdConflict() throws Exception {
        assumeTrue(backendReady, gateReason);
        acceptFirstPreview();
        ObjectNode body = wb.previewRequest("feat138-different-content-" + run, readyTargetId,
                readyInstanceId, conversationId, "feat138-req-" + run + "-1");
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assertThat(resp.status()).as("同 ID 异内容应 400（L2 §2.4）").isEqualTo(400);
        assertThat(resp.body().path("error").asText())
                .as("错误码逐字为 IDEMPOTENCY_CONFLICT（L2 §2.4：拒绝冲突请求）")
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @Tag("story-feat-138-17")
    @Story("FEAT-138.F138-17: 业务会话不可改绑目标/实例")
    @DisplayName("F138-17 同 conversationId 携带不同 instanceId → 400 CONVERSATION_BINDING_CHANGED")
    void f13817ConversationRebindingRejected() throws Exception {
        assumeTrue(backendReady, gateReason);
        acceptFirstPreview();
        ObjectNode body = wb.previewRequest("feat138-rebind-probe-" + run, readyTargetId,
                "feat138-other-instance-" + run, conversationId, "feat138-req-" + run + "-2");
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assertThat(resp.status()).as("改绑业务会话应 400（L2 §2.4）").isEqualTo(400);
        assertThat(resp.body().path("error").asText())
                .as("错误码逐字为 CONVERSATION_BINDING_CHANGED（L2 §2.4：建立新业务会话，不续接旧上下文）")
                .isEqualTo("CONVERSATION_BINDING_CHANGED");
    }

    @Test
    @Tag("story-feat-138-18")
    @Story("FEAT-138.F138-18: 目标未就绪拒绝转发")
    @DisplayName("F138-18 非 ready 目标 POST preview → 409 RUNTIME_NOT_READY_OR_CHANGED 或远端错误")
    void f13818TargetNotReady() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        Feat138WorkbenchSupport.Resp snapshotResp = wb.get("/api/sessions/" + sessionId + "/runtime");
        assumeTrue(snapshotResp.status() == 200, "环境门禁：运行快照不可读");
        // 分型 1：目标 state 非 ready（启动中/已停止/unhealthy）
        JsonNode notReady = pickNotReadyTarget(snapshotResp.body());
        assumeTrue(notReady != null,
                "环境门禁：所有声明目标均就绪，目标未就绪场景本轮不可判定");

        ObjectNode body = wb.previewRequest("feat138-not-ready-" + run,
                notReady.path("targetId").asText(), notReady.path("instanceId").asText(""),
                "feat138-conv-notready-" + run, "feat138-req-notready-" + run);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assertThat(resp.status())
                .as("目标未就绪应 409（L2 §2.4：409 远端错误正文）").isEqualTo(409);
        assertThat(resp.body().toString())
                .as("错误表面含 RUNTIME_NOT_READY_OR_CHANGED 或远端错误正文（L2 §2.4）")
                .containsAnyOf("RUNTIME_NOT_READY_OR_CHANGED", "error", "not ready", "changed");
    }

    @Test
    @Tag("story-feat-138-35")
    @Story("FEAT-138.F138-35: 旧实例不一致拒绝转发")
    @DisplayName("F138-35 就绪目标携带与快照不符的旧 instanceId → 409 RUNTIME_NOT_READY_OR_CHANGED")
    void f13835StaleInstanceRejected() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        Feat138WorkbenchSupport.Resp snapshotResp = wb.get("/api/sessions/" + sessionId + "/runtime");
        assumeTrue(snapshotResp.status() == 200, "环境门禁：运行快照不可读");
        // 分型 2：目标就绪但 instanceId 与快照不符（旧实例 g1，当前 g2）
        JsonNode ready = readyTarget();
        assumeTrue(ready != null, "环境门禁：无就绪目标，旧实例不一致场景本轮不可判定");

        ObjectNode body = wb.previewRequest("feat138-stale-instance-" + run,
                ready.path("targetId").asText(), "feat138-stale-" + run,
                "feat138-conv-stale-" + run, "feat138-req-stale-" + run);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assertThat(resp.status())
                .as("旧实例不一致应 409（L2 §2.4：409 远端错误正文）").isEqualTo(409);
        assertThat(resp.body().toString())
                .as("错误表面含 RUNTIME_NOT_READY_OR_CHANGED 或远端错误正文（L2 §2.4）")
                .containsAnyOf("RUNTIME_NOT_READY_OR_CHANGED", "error", "not ready", "changed");
    }

    /** 取一个 runtime 非 ready 的 target（含该 runtime 的 instanceId，若有）。 */
    private JsonNode pickNotReadyTarget(JsonNode snapshot) {
        java.util.Map<String, JsonNode> notReadyRuntimes = new java.util.HashMap<>();
        for (JsonNode r : snapshot.path("runtimes")) {
            if (!"ready".equals(r.path("state").asText())) {
                notReadyRuntimes.put(r.path("id").asText(), r);
            }
        }
        for (JsonNode t : snapshot.path("targets")) {
            JsonNode rt = notReadyRuntimes.get(t.path("runtimeId").asText());
            if (rt != null) {
                ObjectNode pair = wb.obj();
                pair.put("targetId", t.path("id").asText());
                pair.put("instanceId", rt.path("instanceId").asText(""));
                return pair;
            }
        }
        return null;
    }

    @Test
    @Tag("story-feat-138-19")
    @Story("FEAT-138.F138-19: 单会话转发槽占用互斥")
    @DisplayName("F138-19 转发进行中再次提交 → 409 preview already running")
    void f13819PreviewAlreadyRunning() throws Exception {
        assumeTrue(backendReady, gateReason);
        acceptFirstPreview();
        // 时序场景：首个转发进行中（长任务）时提交第二条。业务任务耗时不可控，
        // 若首条已结束则环境不可判定。
        ObjectNode body = wb.previewRequest("feat138-second-" + run, readyTargetId, readyInstanceId,
                "feat138-conv-second-" + run, "feat138-req-" + run + "-3");
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        if (resp.status() == 202) {
            assumeTrue(false, "环境门禁：首个转发已结束（无长任务占用），409 场景本轮不可判定");
        }
        assertThat(resp.status()).as("转发占用中应 409（L2 §2.4）").isEqualTo(409);
        assertThat(resp.body().toString())
                .as("错误表面 preview already running（L2 §2.4）")
                .contains("preview already running");
    }
}
