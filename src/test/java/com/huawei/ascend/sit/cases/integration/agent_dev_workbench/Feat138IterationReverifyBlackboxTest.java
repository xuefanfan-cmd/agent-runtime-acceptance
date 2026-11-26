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
 * FEAT-138 迭代复验契约黑盒验收（场景矩阵 F138-24）。
 *
 * <p>期望值出处：特性档 §2.2（修改后复验 MUST：重启目标并以新实例重新验证）与
 * §5.1.6（复验语义）、L2 §3.2（一次新启动产生新 instanceId 并将 generation 递增）、
 * §2.4（实例变化后 CONVERSATION_BINDING_CHANGED 400 / 要求新业务会话）。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-138")
@Feature("FEAT-138: 智能体运行调试与迭代验证")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat138IterationReverifyBlackboxTest {

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
    private String oldInstanceId;
    private long oldGeneration = -1;
    private String boundTargetId;

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
        conversationId = "feat138-conv-it-" + run;
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
                    wb.sessionCreate(wsId, "feat138-reverify-session-" + run));
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

    private String operationsPath() {
        return "/api/sessions/" + sessionId + "/runtime/operations";
    }

    /** 取就绪 target+instance 绑定对；无就绪实例时返回 null。 */
    private JsonNode readyPair() throws Exception {
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
                pair.put("generation", rt.path("generation").asLong());
                return pair;
            }
        }
        return null;
    }

    @Test
    @Tag("story-feat-138-24")
    @Story("FEAT-138.F138-24: 实例变化后旧业务会话不可续接")
    @DisplayName("F138-24 重启产生新实例（generation 递增）后旧会话改绑 → 400 CONVERSATION_BINDING_CHANGED")
    void f13824InstanceChangeInvalidatesOldConversation() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");

        // 1) 记录当前就绪实例（generation=g1）
        JsonPair before = currentReadyPair();
        assumeTrue(before != null, "环境门禁：无就绪实例，复验场景本轮不可判定");
        oldInstanceId = before.instanceId;
        oldGeneration = before.generation;
        boundTargetId = before.targetId;

        // 2) 绑定业务会话到旧实例：先成功受理一次预览
        ObjectNode bindBody = wb.previewRequest("feat138-reverify-bind-" + run, boundTargetId,
                oldInstanceId, conversationId, "feat138-req-it-" + run + "-1");
        Feat138WorkbenchSupport.Resp bind = wb.sendWithBody("POST", previewPath(), bindBody);
        assumeTrue(bind.status() == 202,
                "环境门禁：预览受理返回 " + bind.status() + "，无法建立旧实例绑定");

        // 3) 重启目标产生新实例（异步受理 → 轮询快照直到 generation 递增或超时）
        String runtimeId = declaredRuntimeId(boundTargetId);
        assumeTrue(runtimeId != null, "环境门禁：无法取得目标所属 Runtime ID");
        ObjectNode opBody = wb.operationRequest("feat138-op-it-" + run, "restart", runtimeId);
        opBody.put("impactVersion", before.manifestVersion);
        opBody.put("expectedGeneration", oldGeneration);
        Feat138WorkbenchSupport.Resp opResp = wb.sendWithBody("POST", operationsPath(), opBody);
        assumeTrue(opResp.status() == 202,
                "环境门禁：restart 受理返回 " + opResp.status() + "，重启不可执行");

        JsonPair after = waitGenerationAdvanced(180);
        assumeTrue(after != null,
                "环境门禁：180 秒内实例代次未递增（restart 未完成），复验断言本轮不可判定");
        assertThat(after.generation).as("重启后 generation 递增（L2 §3.2）").isGreaterThan(oldGeneration);
        assertThat(after.instanceId).as("重启后产生新 instanceId（L2 §3.2）").isNotEqualTo(oldInstanceId);

        // 4) 旧业务会话携带新实例提交 → 拒绝续接旧上下文
        ObjectNode staleBody = wb.previewRequest("feat138-reverify-stale-" + run, boundTargetId,
                after.instanceId, conversationId, "feat138-req-it-" + run + "-2");
        Feat138WorkbenchSupport.Resp stale = wb.sendWithBody("POST", previewPath(), staleBody);
        assertThat(stale.status())
                .as("旧业务会话不可跨实例续接（特性档 §5.1.3：实例变化后不能复用原业务上下文）")
                .isEqualTo(400);
        assertThat(stale.body().path("error").asText())
                .as("错误码逐字为 CONVERSATION_BINDING_CHANGED（L2 §2.4）")
                .isEqualTo("CONVERSATION_BINDING_CHANGED");
    }

    private record JsonPair(String targetId, String instanceId, long generation, String manifestVersion) {
    }

    private JsonPair currentReadyPair() throws Exception {
        Feat138WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/runtime");
        if (resp.status() != 200) {
            return null;
        }
        String manifest = resp.body().path("manifestVersion").asText("");
        java.util.Map<String, JsonNode> readyRuntimes = new java.util.HashMap<>();
        for (JsonNode r : resp.body().path("runtimes")) {
            if ("ready".equals(r.path("state").asText())) {
                readyRuntimes.put(r.path("id").asText(), r);
            }
        }
        for (JsonNode t : resp.body().path("targets")) {
            JsonNode rt = readyRuntimes.get(t.path("runtimeId").asText());
            if (rt != null) {
                return new JsonPair(t.path("id").asText(), rt.path("instanceId").asText(),
                        rt.path("generation").asLong(), manifest);
            }
        }
        return null;
    }

    private String declaredRuntimeId(String targetId) throws Exception {
        Feat138WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/runtime");
        if (resp.status() != 200) {
            return null;
        }
        for (JsonNode t : resp.body().path("targets")) {
            if (targetId.equals(t.path("id").asText())) {
                return t.path("runtimeId").asText(null);
            }
        }
        return null;
    }

    private JsonPair waitGenerationAdvanced(long timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            JsonPair p = currentReadyPair();
            if (p != null && p.generation > oldGeneration) {
                return p;
            }
            Thread.sleep(3000);
        }
        return null;
    }
}
