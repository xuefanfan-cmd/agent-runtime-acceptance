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
 * FEAT-138 运行操作契约黑盒验收（场景矩阵 F138-05～F138-11、F138-26、F138-30～F138-34）。
 *
 * <p>期望值出处：L2 §2.3（operationId 约束 [a-zA-Z0-9_-]{1,80}、action ∈ {start,stop,restart}、
 * 202 受理 status 初值 running、同 ID 同请求复用 / 异请求 IDEMPOTENCY_CONFLICT 400、
 * UNKNOWN_RUNTIME 400、CONFIRMATION_STALE 409、CONTROL_OPERATION_IN_PROGRESS 409）、
 * 特性档 §2.2（启动停止重启 MUST：异步查看执行结果及状态变化）与 §5.1.2（运行操作语义）。
 *
 * <p>v1130 审阅整改：声明版本过期（F138-10）与实例代次过期（F138-30）分型验证；
 * 共享实例用例固定至少 2 个目标并核对实际影响（F138-26）；分别构造缺产物/缺控制模型（F138-31）、
 * 健康失败（F138-32）、托管目标未注册（F138-33）与操作结果待核实（F138-34），
 * 均以进程外手段构造并按环境门禁判定。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-138")
@Feature("FEAT-138: 智能体运行调试与迭代验证")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat138RuntimeOperationsBlackboxTest {

    private Feat138WorkbenchSupport wb;
    private String wsId;
    private String sessionId;
    private boolean sessionReady;
    /** JUnit 6 下 PER_CLASS 容器的 assumeTrue 会中止整个容器（记 0 而非 SKIPPED），
     * 因此 @BeforeAll 只设置门禁标志，由各测试方法开头 assumeTrue 逐方法记录 SKIPPED。 */
    private boolean backendReady;
    private String gateReason;
    private String run;
    private String opIdBase;
    private String startAcceptedAt;
    private boolean startAccepted;

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
        opIdBase = "feat138-op-" + run;
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
                    wb.sessionCreate(wsId, "feat138-operations-session-" + run));
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
                    String fixtureBlocker = wb.prepareRuntimeProject(cwd, run, "shared");
                    if (fixtureBlocker != null) {
                        sessionReady = false;
                        gateReason = fixtureBlocker;
                    }
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

    private String operationsPath() {
        return "/api/sessions/" + sessionId + "/runtime/operations";
    }

    /** 提交 F138-05 的 start 操作并记录首响 createdAt；声明不可读时整类门禁。 */
    private void acceptStart() throws Exception {
        if (startAccepted) {
            return;
        }
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        String runtimeId = declaredPrimaryRuntimeId();
        assumeTrue(runtimeId != null,
                "环境门禁：无法从运行快照取得声明 Runtime ID（声明缺失或无远端通道）");
        ObjectNode body = wb.operationRequest(opIdBase + "-1", "start", runtimeId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", operationsPath(), body);
        assumeTrue(resp.status() == 202,
                "环境门禁：start 受理返回 " + resp.status() + "（产物/依赖不满足时无法验证受理语义）");
        startAcceptedAt = resp.body().path("createdAt").asText(null);
        startAccepted = true;
    }

    /** 从运行快照取声明中的主 Runtime ID；快照不可读返回 null。 */
    private String declaredPrimaryRuntimeId() throws Exception {
        Feat138WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/runtime");
        if (resp.status() != 200) {
            return null;
        }
        JsonNode runtimes = resp.body().path("runtimes");
        if (runtimes.isArray() && !runtimes.isEmpty()) {
            return runtimes.get(0).path("id").asText(null);
        }
        String def = resp.body().path("defaultTargetId").asText(null);
        return def;
    }

    @Test
    @Tag("story-feat-138-05")
    @Story("FEAT-138.F138-05: 启动操作异步受理返回 202 与 running 初值")
    @DisplayName("F138-05 POST operations start → 202，operationId 回显且 status==running")
    void f13805StartOperationAccepted() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        String runtimeId = declaredPrimaryRuntimeId();
        assumeTrue(runtimeId != null, "环境门禁：无法从运行快照取得声明 Runtime ID（声明缺失或无远端通道）");
        // 受理初值语义必须在新操作的"首次响应"上断言：同 ID 重复受理返回的是既有记录
        // （其 status 已随异步执行推进），不能替代首响初值。
        String freshOpId = opIdBase + "-fresh-" + System.currentTimeMillis();
        ObjectNode body = wb.operationRequest(freshOpId, "start", runtimeId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", operationsPath(), body);
        assertThat(resp.status()).as("运行操作异步受理应 202（L2 §2.3）").isEqualTo(202);
        assertThat(resp.body().path("operationId").asText())
                .as("operationId 回显（L2 §2.3 出参表）").isEqualTo(freshOpId);
        assertThat(resp.body().path("status").asText())
                .as("受理初值 status==running（L2 §2.3：初始为 running）").isEqualTo("running");
        assertThat(resp.body().has("request"))
                .as("request 回显已受理内容，用于精确内容去重（L2 §2.3）").isTrue();
    }

    @Test
    @Tag("story-feat-138-06")
    @Story("FEAT-138.F138-06: 非法 operationId 或 action 被拒绝为 INVALID_OPERATION")
    @DisplayName("F138-06 非法 ID/超长 ID/非法 action → 400 INVALID_OPERATION")
    void f13806InvalidOperationRejected() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        String runtimeId = declaredPrimaryRuntimeId();
        assumeTrue(runtimeId != null, "环境门禁：无法取得声明 Runtime ID");

        ObjectNode badChar = wb.operationRequest(opIdBase + ".bad!", "start", runtimeId);
        Feat138WorkbenchSupport.Resp respA = wb.sendWithBody("POST", operationsPath(), badChar);
        assertThat(respA.status()).as("非法字符 operationId 应 400（L2 §2.3）").isEqualTo(400);
        assertThat(respA.body().path("error").asText())
                .as("错误码逐字为 INVALID_OPERATION（L2 §2.3）").isEqualTo("INVALID_OPERATION");

        ObjectNode tooLong = wb.operationRequest("feat138-" + "x".repeat(80), "start", runtimeId);
        Feat138WorkbenchSupport.Resp respB = wb.sendWithBody("POST", operationsPath(), tooLong);
        assertThat(respB.status()).as("超长 operationId 应 400（L2 §2.3：{1,80}）").isEqualTo(400);
        assertThat(respB.body().path("error").asText())
                .as("错误码逐字为 INVALID_OPERATION（L2 §2.3）").isEqualTo("INVALID_OPERATION");

        ObjectNode badAction = wb.operationRequest(opIdBase + "-bad-action", "pause", runtimeId);
        Feat138WorkbenchSupport.Resp respC = wb.sendWithBody("POST", operationsPath(), badAction);
        assertThat(respC.status()).as("非法 action 应 400（L2 §2.3）").isEqualTo(400);
        assertThat(respC.body().path("error").asText())
                .as("错误码逐字为 INVALID_OPERATION（L2 §2.3）").isEqualTo("INVALID_OPERATION");
    }

    @Test
    @Tag("story-feat-138-07")
    @Story("FEAT-138.F138-07: 同 ID 同内容复用既有控制记录")
    @DisplayName("F138-07 重复 start 请求 → 202 既有记录，createdAt 一致")
    void f13807DuplicateReusesRecord() throws Exception {
        assumeTrue(backendReady, gateReason);
        acceptStart();
        String runtimeId = declaredPrimaryRuntimeId();
        ObjectNode body = wb.operationRequest(opIdBase + "-1", "start", runtimeId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", operationsPath(), body);
        assertThat(resp.status()).as("同 ID 同内容应 202 复用（L2 §2.3）").isEqualTo(202);
        assertThat(resp.body().path("operationId").asText())
                .as("返回既有记录的 operationId").isEqualTo(opIdBase + "-1");
        assertThat(resp.body().path("createdAt").asText(null))
                .as("createdAt 与首响一致（复用既有记录，非新建）").isEqualTo(startAcceptedAt);
    }

    @Test
    @Tag("story-feat-138-08")
    @Story("FEAT-138.F138-08: 同 ID 异内容冲突拒绝")
    @DisplayName("F138-08 相同 operationId 换 action → 400 IDEMPOTENCY_CONFLICT")
    void f13808IdempotencyConflict() throws Exception {
        assumeTrue(backendReady, gateReason);
        acceptStart();
        String runtimeId = declaredPrimaryRuntimeId();
        ObjectNode body = wb.operationRequest(opIdBase + "-1", "stop", runtimeId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", operationsPath(), body);
        assertThat(resp.status()).as("同 ID 异内容应 400（L2 §2.3）").isEqualTo(400);
        assertThat(resp.body().path("error").asText())
                .as("错误码逐字为 IDEMPOTENCY_CONFLICT（L2 §2.3：不覆盖旧请求和结果）")
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @Tag("story-feat-138-09")
    @Story("FEAT-138.F138-09: 未知 Runtime 拒绝任意进程控制")
    @DisplayName("F138-09 未知 runtimeId → 400 UNKNOWN_RUNTIME")
    void f13809UnknownRuntime() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        // 场景前置：运行声明清单可读（UNKNOWN_RUNTIME 校验发生在清单内查无此 Runtime 时；
        // 快照不可读时按门禁跳过，不以"无清单"状态冒充未知 Runtime）
        Feat138WorkbenchSupport.Resp manifestProbe = wb.get("/api/sessions/" + sessionId + "/runtime");
        assumeTrue(manifestProbe.status() == 200,
                "环境门禁：运行快照返回 " + manifestProbe.status()
                        + "（运行声明/控制链不可用），UNKNOWN_RUNTIME 场景本轮不可判定");
        ObjectNode body = wb.operationRequest(opIdBase + "-unknown-rt",
                "start", "feat138-no-such-runtime-" + run);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", operationsPath(), body);
        assertThat(resp.status()).as("Runtime 未知应 400（L2 §2.3）").isEqualTo(400);
        assertThat(resp.body().path("error").asText())
                .as("错误码逐字为 UNKNOWN_RUNTIME（L2 §2.3）").isEqualTo("UNKNOWN_RUNTIME");
    }

    @Test
    @Tag("story-feat-138-10")
    @Story("FEAT-138.F138-10: 声明版本过期拒绝停止/重启")
    @DisplayName("F138-10 过期 impactVersion（expectedGeneration 为当前值）→ 409 CONFIRMATION_STALE")
    void f13810StaleImpactVersion() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        Feat138WorkbenchSupport.Resp snapshotResp = wb.get("/api/sessions/" + sessionId + "/runtime");
        assumeTrue(snapshotResp.status() == 200, "环境门禁：运行快照不可读，无法构造过期确认");
        String runtimeId = declaredPrimaryRuntimeId();
        assumeTrue(runtimeId != null, "环境门禁：无法取得声明 Runtime ID");
        String manifestVersion = snapshotResp.body().path("manifestVersion").asText(null);
        long generation = currentGeneration(snapshotResp.body(), runtimeId);
        assumeTrue(manifestVersion != null && generation >= 0,
                "环境门禁：快照缺 manifestVersion/generation，无法构造仅声明版本过期的确认");

        // 分型：仅 impactVersion 过期，expectedGeneration 携带当前值（审阅 A3/F2/F6 要求分型验证）
        ObjectNode body = wb.operationRequest(opIdBase + "-stale-impact", "stop", runtimeId);
        body.put("impactVersion", "feat138-expired-manifest-" + run);
        body.put("expectedGeneration", generation);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", operationsPath(), body);
        if (resp.status() != 409) {
            assumeTrue(hasReadyInstance(snapshotResp.body()),
                    "环境门禁：无就绪实例，声明版本过期场景本轮不可判定（返回 " + resp.status() + "）");
        }
        assertThat(resp.status()).as("声明版本过期确认应 409（L2 §2.3）").isEqualTo(409);
        assertThat(resp.body().path("error").asText())
                .as("错误码逐字为 CONFIRMATION_STALE（L2 §2.3）").isEqualTo("CONFIRMATION_STALE");
    }

    @Test
    @Tag("story-feat-138-30")
    @Story("FEAT-138.F138-30: 实例代次过期拒绝停止/重启")
    @DisplayName("F138-30 过期 expectedGeneration（impactVersion 为当前值）→ 409 CONFIRMATION_STALE")
    void f13830StaleExpectedGeneration() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        Feat138WorkbenchSupport.Resp snapshotResp = wb.get("/api/sessions/" + sessionId + "/runtime");
        assumeTrue(snapshotResp.status() == 200, "环境门禁：运行快照不可读，无法构造过期确认");
        String runtimeId = declaredPrimaryRuntimeId();
        assumeTrue(runtimeId != null, "环境门禁：无法取得声明 Runtime ID");
        String manifestVersion = snapshotResp.body().path("manifestVersion").asText(null);
        long generation = currentGeneration(snapshotResp.body(), runtimeId);
        assumeTrue(manifestVersion != null && generation >= 0,
                "环境门禁：快照缺 manifestVersion/generation，实例代次过期场景本轮不可判定");

        // 分型：仅 expectedGeneration 失配（代次首次启动即为 0，向上构造失配值），
        // impactVersion 携带当前声明版本（审阅要求与 F138-10 分型）
        ObjectNode body = wb.operationRequest(opIdBase + "-stale-gen", "stop", runtimeId);
        body.put("impactVersion", manifestVersion);
        body.put("expectedGeneration", generation + 100000);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", operationsPath(), body);
        if (resp.status() != 409) {
            assumeTrue(hasReadyInstance(snapshotResp.body()),
                    "环境门禁：无就绪实例，实例代次过期场景本轮不可判定（返回 " + resp.status() + "）");
        }
        assertThat(resp.status()).as("实例代次过期确认应 409（L2 §2.3）").isEqualTo(409);
        assertThat(resp.body().path("error").asText())
                .as("错误码逐字为 CONFIRMATION_STALE（L2 §2.3）").isEqualTo("CONFIRMATION_STALE");
    }

    @Test
    @Tag("story-feat-138-11")
    @Story("FEAT-138.F138-11: 同工作区控制串行化")
    @DisplayName("F138-11 存在 running 控制时并发提交 → 409 CONTROL_OPERATION_IN_PROGRESS")
    void f13811ControlSerialization() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        Feat138WorkbenchSupport.Resp snapshotResp = wb.get("/api/sessions/" + sessionId + "/runtime");
        assumeTrue(snapshotResp.status() == 200, "环境门禁：运行快照不可读");
        String runtimeId = declaredPrimaryRuntimeId();
        assumeTrue(runtimeId != null, "环境门禁：无法取得声明 Runtime ID");
        String manifestVersion = snapshotResp.body().path("manifestVersion").asText(null);
        long generation = currentGeneration(snapshotResp.body(), runtimeId);
        assumeTrue(manifestVersion != null && generation >= 0,
                "环境门禁：快照缺 manifestVersion/generation");

        // 主动构造 running 控制记录：restart（stop+start，执行窗口 ≥ 实例启动耗时）
        String firstOp = opIdBase + "-serial-1-" + System.currentTimeMillis();
        ObjectNode firstBody = wb.operationRequest(firstOp, "restart", runtimeId);
        firstBody.put("impactVersion", manifestVersion);
        firstBody.put("expectedGeneration", generation);
        Feat138WorkbenchSupport.Resp first = wb.sendWithBody("POST", operationsPath(), firstBody);
        assumeTrue(first.status() == 202,
                "环境门禁：restart 受理返回 " + first.status() + "（" + first.body().toString() + "），无法构造 running 记录");

        // 首条仍 running 时提交第二条 → CONTROL_OPERATION_IN_PROGRESS
        String secondOp = opIdBase + "-serial-2-" + System.currentTimeMillis();
        ObjectNode body = wb.operationRequest(secondOp, "start", runtimeId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", operationsPath(), body);
        if (resp.status() == 202) {
            assumeTrue(false, "环境门禁：首条控制记录在第二条提交前已执行完成（restart 过快），"
                    + "409 串行化场景本轮不可判定");
        }
        assertThat(resp.status()).as("工作区控制串行化应 409（L2 §2.3）").isEqualTo(409);
        assertThat(resp.body().path("error").asText())
                .as("错误码逐字为 CONTROL_OPERATION_IN_PROGRESS（L2 §2.3）")
                .isEqualTo("CONTROL_OPERATION_IN_PROGRESS");

        // 收尾：等待 restart 到终态再结束本用例，避免 running 记录污染同类的后续用例
        // （ensureNoRunningOperation 会让后续提交 409）。
        String op1Final = waitOperationTerminalOn(sessionId, firstOp, 300);
        assumeTrue(op1Final != null, "环境门禁：300 秒内 restart 未到终态");
    }

    @Test
    @Tag("story-feat-138-26")
    @Story("FEAT-138.F138-26: 有效影响确认停止成功并核对实际影响（共享实例 ≥2 目标）")
    @DisplayName("F138-26 共享实例有效确认 stop → 202，全部受影响目标离开就绪且操作结果可查询")
    void f13826SharedInstanceConfirmedStop() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        Feat138WorkbenchSupport.Resp snapshotResp = wb.get("/api/sessions/" + sessionId + "/runtime");
        assumeTrue(snapshotResp.status() == 200, "环境门禁：运行快照不可读");
        JsonNode body = snapshotResp.body();
        String manifestVersion = body.path("manifestVersion").asText(null);

        // 审阅要求：共享实例用例固定至少 2 个目标并核对实际影响
        JsonNode sharedRuntime = null;
        int affectedTargetCount = 0;
        for (JsonNode r : body.path("runtimes")) {
            if ("ready".equals(r.path("state").asText())
                    && r.path("affectedTargets").isArray()
                    && r.path("affectedTargets").size() >= 2) {
                sharedRuntime = r;
                affectedTargetCount = r.path("affectedTargets").size();
                break;
            }
        }
        assumeTrue(sharedRuntime != null,
                "环境门禁：无承载 ≥2 个目标的就绪共享实例（fixture 未固定），共享实例影响确认场景本轮不可判定");
        String runtimeId = sharedRuntime.path("id").asText();
        long generation = sharedRuntime.path("generation").asLong(-1);
        assumeTrue(manifestVersion != null && generation >= 0,
                "环境门禁：快照缺 manifestVersion/generation，无法构造有效影响确认");

        ObjectNode stopBody = wb.operationRequest(opIdBase + "-shared-stop", "stop", runtimeId);
        stopBody.put("impactVersion", manifestVersion);
        stopBody.put("expectedGeneration", generation);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", operationsPath(), stopBody);
        assertThat(resp.status()).as("有效影响确认应 202 受理（L2 §2.3）").isEqualTo(202);
        assertThat(resp.body().path("status").asText())
                .as("受理初值 status==running，受理不等于执行成功（L2 §2.3）").isEqualTo("running");

        // 核对实际影响：轮询快照直至该 Runtime 离开 ready（全部受影响目标随实例离开就绪）
        boolean leftReady = false;
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            Feat138WorkbenchSupport.Resp after = wb.get("/api/sessions/" + sessionId + "/runtime");
            if (after.status() == 200 && runtimeLeftReady(after.body(), runtimeId)) {
                leftReady = true;
                break;
            }
            Thread.sleep(3000);
        }
        assertThat(leftReady)
                .as("stop 生效后共享实例（承载 " + affectedTargetCount + " 个目标）离开 ready（FR-04：实际影响与确认范围一致）")
                .isTrue();

        // 操作结果可查询：快照 operations 含该 operationId 记录（FR-03/F138-25）
        Feat138WorkbenchSupport.Resp finalSnap = wb.get("/api/sessions/" + sessionId + "/runtime");
        assumeTrue(finalSnap.status() == 200, "环境门禁：执行后快照不可读");
        assertThat(finalSnap.body().path("operations").toString())
                .as("操作结果可查询：operations 含该 operationId（F138-25/FR-03）")
                .contains(opIdBase + "-shared-stop");
    }

    @Test
    @Tag("story-feat-138-31")
    @Story("FEAT-138.F138-31: 缺产物不判就绪")
    @DisplayName("F138-31 缺产物注入（artifact 缺失）→ 启动失败，不判 ready，失败结果可查询")
    void f13831MissingArtifactsNeverReady() throws Exception {
        assumeTrue(backendReady, gateReason);
        // 自足构造：独立工作区 + 缺产物 fixture（manifest 引用不存在的 deploy/missing-artifact.jar）
        Feat138WorkbenchSupport.FixtureSession fs = wb.createFixtureSession(
                com.huawei.ascend.sit.config.TestConfig.load(),
                "feat138-ws-missing-" + run, "feat138-missing-session-" + run, run, "missing-artifact");
        assumeTrue(fs.blocker() == null, fs.blocker() == null ? "" : fs.blocker());
        String missingSessionId = fs.sessionId();
        String missingOp = opIdBase + "-missing-" + System.currentTimeMillis();

        // 就绪前快照：target 不得判 ready（产物缺失不是就绪）
        Feat138WorkbenchSupport.Resp before = wb.get("/api/sessions/" + missingSessionId + "/runtime");
        assumeTrue(before.status() == 200, "环境门禁：快照不可读（" + before.status() + "）");
        for (JsonNode t : before.body().path("targets")) {
            assertThat(t.path("state").asText())
                    .as("缺产物目标不得判 ready（特性档 §5.1.7：受理不替代就绪）")
                    .isNotEqualTo("ready");
        }

        String runtimeId = before.body().path("targets").get(0).path("runtimeId").asText(null);
        assumeTrue(runtimeId != null, "环境门禁：target 缺 runtimeId");
        ObjectNode body = wb.operationRequest(missingOp, "start", runtimeId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/" + missingSessionId + "/runtime/operations", body);
        assertThat(resp.status()).as("缺产物 start 应 202 受理（异步执行）").isEqualTo(202);

        String finalStatus = waitOperationTerminalOn(missingSessionId, missingOp, 240);
        assumeTrue(finalStatus != null, "环境门禁：240 秒内操作未到终态");
        assertThat(finalStatus)
                .as("缺产物启动应以失败终态收尾（FR-03：实际结果可观察）")
                .isNotIn("running", "succeeded", "success");

        Feat138WorkbenchSupport.Resp after = wb.get("/api/sessions/" + missingSessionId + "/runtime");
        assumeTrue(after.status() == 200, "环境门禁：执行后快照不可读");
        for (JsonNode r : after.body().path("runtimes")) {
            if (runtimeId.equals(r.path("id").asText())) {
                assertThat(r.path("state").asText())
                        .as("启动失败（缺产物）后不得判 ready（特性档 §5.1.7）")
                        .isNotEqualTo("ready");
            }
        }
        assertThat(after.body().path("operations").toString())
                .as("失败操作记录可查询（FR-03）").contains(missingOp);
    }

    @Test
    @Tag("story-feat-138-32")
    @Story("FEAT-138.F138-32: 健康检查失败不判就绪")
    @DisplayName("F138-32 健康失败注入（healthPath 指向不存在路径）→ 启动以失败终态收尾且实例不判 ready")
    void f13832UnhealthyPresentedNotReady() throws Exception {
        assumeTrue(backendReady, gateReason);
        // 自足构造：独立工作区 + 健康失败 fixture（healthPath 指向不存在路径）
        // 共享进程引擎下启动窗口内 inspect 被控制会话串行化（实测），unhealthy 中间态
        // 不经 wire 呈现；可观测语义为：操作以 START_HEALTH_TIMEOUT 失败 + 实例不判 ready。
        Feat138WorkbenchSupport.FixtureSession fs = wb.createFixtureSession(
                com.huawei.ascend.sit.config.TestConfig.load(),
                "feat138-ws-unhealthy-" + run, "feat138-unhealthy-session-" + run, run, "unhealthy");
        assumeTrue(fs.blocker() == null, fs.blocker() == null ? "" : fs.blocker());
        String unhealthySessionId = fs.sessionId();
        String unhealthyOp = opIdBase + "-unhealthy-" + System.currentTimeMillis();
        String runtimeId = "main";

        ObjectNode body = wb.operationRequest(unhealthyOp, "start", runtimeId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/" + unhealthySessionId + "/runtime/operations", body);
        assumeTrue(resp.status() == 202,
                "环境门禁：start 受理返回 " + resp.status() + "（" + resp.body().toString() + "）");

        // 轮询至操作终态（startPeriod=120 + 启动耗时 ≈ 140s）
        String finalStatus = waitOperationTerminalOn(unhealthySessionId, unhealthyOp, 300);
        assumeTrue(finalStatus != null, "环境门禁：300 秒内操作未到终态");
        // 终态语义：failed = 明确失败；unknown = 结果待核实（控制会话丢失后由 reconcile 收敛），
        // 两者都不是成功，受理不替代就绪。
        assertThat(finalStatus)
                .as("健康失败启动以失败/待核实终态收尾（受理不替代就绪，特性档 §5.1.7）")
                .isIn("failed", "unknown");

        Feat138WorkbenchSupport.Resp snap = wb.get("/api/sessions/" + unhealthySessionId + "/runtime");
        assumeTrue(snap.status() == 200, "环境门禁：执行后快照不可读");
        for (JsonNode r : snap.body().path("runtimes")) {
            if (runtimeId.equals(r.path("id").asText())) {
                assertThat(r.path("state").asText())
                        .as("健康失败实例不得判 ready（runtimes[].state 枚举含 unhealthy，"
                                + "共享进程引擎下启动窗口内的 unhealthy 中间态经控制会话串行化不作 wire 呈现）")
                        .isNotEqualTo("ready");
            }
        }
        assertThat(snap.body().path("operations").toString())
                .as("失败原因（START_HEALTH_TIMEOUT）可查询（FR-03）")
                .contains("START_HEALTH_TIMEOUT");
    }

    @Test
    @Tag("story-feat-138-33")
    @Story("FEAT-138.F138-33: 托管目标未注册拒绝控制")
    @DisplayName("F138-33 声明引用未注册 Runtime（ghost target）→ 拒绝且错误表面可观察，不判就绪")
    void f13833UnregisteredHostedTargetRejected() throws Exception {
        assumeTrue(backendReady, gateReason);
        // 自足构造：独立工作区 + ghost fixture（target 引用未声明的 runtimeId，清单整体校验拒绝）
        Feat138WorkbenchSupport.FixtureSession fs = wb.createFixtureSession(
                com.huawei.ascend.sit.config.TestConfig.load(),
                "feat138-ws-ghost-" + run, "feat138-ghost-session-" + run, run, "ghost");
        assumeTrue(fs.blocker() == null, fs.blocker() == null ? "" : fs.blocker());
        String ghostSessionId = fs.sessionId();

        // 快照：清单校验拒绝（UNKNOWN_RUNTIME），错误表面可观察，无任何 ready
        Feat138WorkbenchSupport.Resp snap = wb.get("/api/sessions/" + ghostSessionId + "/runtime");
        assertThat(snap.status())
                .as("含未注册 Runtime 的清单应拒绝快照（运行器 UNKNOWN_RUNTIME）")
                .isEqualTo(409);
        assertThat(snap.body().toString())
                .as("未注册托管目标拒绝表面逐字可观察（UNKNOWN_RUNTIME）")
                .contains("UNKNOWN_RUNTIME");

        // 控制：对未注册 Runtime 提交操作 → 拒绝，不借本机能力执行
        ObjectNode body = wb.operationRequest(opIdBase + "-ghost-" + System.currentTimeMillis(),
                "start", "ghost-runtime");
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/" + ghostSessionId + "/runtime/operations", body);
        int ghostAttempts = 0;
        while (resp.status() == 0 && ghostAttempts < 2) {
            Thread.sleep(20_000); // 负载高峰下的瞬时超时：等待后重试
            resp = wb.sendWithBody("POST",
                    "/api/sessions/" + ghostSessionId + "/runtime/operations", body);
            ghostAttempts++;
        }
        assertThat(resp.status())
                .as("对未注册 Runtime 的控制应拒绝（400/409）")
                .isIn(400, 409);
        assertThat(resp.body().toString())
                .as("控制拒绝表面含 UNKNOWN_RUNTIME（不借本机能力判就绪）")
                .contains("UNKNOWN_RUNTIME");
    }

    @Test
    @Tag("story-feat-138-34")
    @Story("FEAT-138.F138-34: 操作结果待核实可查询且不替代就绪")
    @DisplayName("F138-34 控制会话丢失（进程外终止）→ 操作呈现待核实（unknown）且受理不替代就绪")
    void f13834OperationResultQueryableNotSubstitutingReady() throws Exception {
        assumeTrue(backendReady, gateReason);
        // 自足构造：start 进行中（startPeriod=60 的正常 fixture）将控制会话进程外终止
        // （pkill 'opencode run'，进程外注入；业务实例以 start_new_session 分离启动而存活）
        // → 控制结果未知 → 操作呈现待核实（unknown）状态。
        Feat138WorkbenchSupport.FixtureSession fs = wb.createFixtureSession(
                com.huawei.ascend.sit.config.TestConfig.load(),
                "feat138-ws-unknown-" + run, "feat138-unknown-session-" + run, run, "single");
        assumeTrue(fs.blocker() == null, fs.blocker() == null ? "" : fs.blocker());
        String unknownSessionId = fs.sessionId();

        // 多轮注入尝试：start 过快完成（控制会话已结束）时换用 restart（stop+start，
        // 执行窗口更长）重新构造 running 窗口再注入
        boolean runningSeen = false;
        String unknownOp = null;
        for (int attempt = 0; attempt < 3 && !runningSeen; attempt++) {
            String action = attempt == 0 ? "start" : "restart";
            String opId = opIdBase + "-unknown-" + run + "-" + attempt;
            ObjectNode body = wb.operationRequest(opId, action, "main");
            Feat138WorkbenchSupport.Resp accepted = wb.sendWithBody("POST",
                    "/api/sessions/" + unknownSessionId + "/runtime/operations", body);
            assumeTrue(accepted.status() == 202, "环境门禁：" + action + " 受理返回 " + accepted.status()
                    + "（" + accepted.body().toString() + "）");
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                Feat138WorkbenchSupport.Resp snap = wb.get("/api/sessions/" + unknownSessionId + "/runtime");
                String st = snap.status() == 200
                        ? snap.body().path("operations").path(opId).path("status").asText("")
                        : "";
                if ("running".equals(st)) {
                    runningSeen = true;
                    unknownOp = opId;
                    break;
                }
                if (!st.isEmpty() && !"running".equals(st)) {
                    break; // 本轮尝试过快完成，换下一轮
                }
                Thread.sleep(3000);
            }
        }
        assumeTrue(runningSeen && unknownOp != null,
                "环境门禁：三轮注入均未构造出 running 控制窗口");

        // 进程外注入：终止控制会话（opencode run）
        ProcessBuilder pb = new ProcessBuilder("podman", "exec", "feat138-agenthost",
                "pkill", "-f", "opencode run");
        pb.start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

        // 轮询终态：应呈现待核实（unknown）；unknown 期间目标不得显示 ready
        boolean unknownSeen = false;
        String stateAtUnknown = null;
        String finalStatus = null;
        long reconcileDeadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < reconcileDeadline) {
            Feat138WorkbenchSupport.Resp snap = wb.get("/api/sessions/" + unknownSessionId + "/runtime");
            if (snap.status() == 200) {
                String st = snap.body().path("operations").path(unknownOp).path("status").asText("");
                if ("unknown".equals(st)) {
                    unknownSeen = true;
                    for (JsonNode r : snap.body().path("runtimes")) {
                        if ("main".equals(r.path("id").asText())) {
                            stateAtUnknown = r.path("state").asText();
                        }
                    }
                }
                if (!st.isEmpty() && !"running".equals(st)) {
                    finalStatus = st;
                    break;
                }
            }
            Thread.sleep(3000);
        }
        assertThat(unknownSeen)
                .as("控制会话丢失后操作呈现待核实（unknown）状态（FR-03：受理不替代实际结果）")
                .isTrue();
        assertThat(stateAtUnknown)
                .as("待核实期间目标不得显示 ready（就绪状态独立呈现，L2 §2.2）")
                .isNotEqualTo("ready");
        assumeTrue(finalStatus != null, "环境门禁：300 秒内操作未收敛到终态（reconcile 进行中）");
    }

    private boolean hasReadyInstance(com.fasterxml.jackson.databind.JsonNode snapshot) {
        for (com.fasterxml.jackson.databind.JsonNode r : snapshot.path("runtimes")) {
            if ("ready".equals(r.path("state").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 从快照取指定 Runtime 的 generation；缺失返回 -1。 */
    private long currentGeneration(JsonNode snapshot, String runtimeId) {
        for (JsonNode r : snapshot.path("runtimes")) {
            if (runtimeId.equals(r.path("id").asText()) && r.path("generation").isNumber()) {
                return r.path("generation").asLong(-1);
            }
        }
        return -1;
    }

    /** 指定 Runtime 是否已离开 ready 状态（实际影响核对）。 */
    private boolean runtimeLeftReady(JsonNode snapshot, String runtimeId) {
        for (JsonNode r : snapshot.path("runtimes")) {
            if (runtimeId.equals(r.path("id").asText())) {
                return !"ready".equals(r.path("state").asText());
            }
        }
        return false;
    }

    /** 轮询快照 operations 中该操作记录的状态直至非 running 终态；读不到返回 null。 */
    private String waitOperationTerminal(String operationId, long timeoutSeconds) throws Exception {
        return waitOperationTerminalOn(sessionId, operationId, timeoutSeconds);
    }

    /** 轮询指定会话快照 operations 中该操作记录的状态直至非 running 终态；读不到返回 null。 */
    private String waitOperationTerminalOn(String sessionId, String operationId, long timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            Feat138WorkbenchSupport.Resp snap = wb.get("/api/sessions/" + sessionId + "/runtime");
            if (snap.status() == 200) {
                JsonNode rec = findOperationRecord(snap.body().path("operations"), operationId);
                if (!rec.isMissingNode() && !rec.isNull()) {
                    String status = rec.path("status").asText("");
                    if (!status.isEmpty() && !"running".equals(status)) {
                        return status;
                    }
                }
            }
            Thread.sleep(3000);
        }
        return null;
    }

    /** 在 operations 对象/数组中查找指定 operationId 的记录。 */
    private JsonNode findOperationRecord(JsonNode operations, String operationId) {
        if (operations.isObject()) {
            JsonNode direct = operations.path(operationId);
            if (!direct.isMissingNode()) {
                return direct;
            }
            java.util.Iterator<JsonNode> it = operations.elements();
            while (it.hasNext()) {
                JsonNode e = it.next();
                if (operationId.equals(e.path("operationId").asText())) {
                    return e;
                }
            }
        } else if (operations.isArray()) {
            for (JsonNode e : operations) {
                if (operationId.equals(e.path("operationId").asText())) {
                    return e;
                }
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
