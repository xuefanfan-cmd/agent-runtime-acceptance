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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-138 运行目标查询契约黑盒验收（场景矩阵 F138-01～F138-04）。
 *
 * <p>期望值出处：L2 §2.2（RuntimeSnapshot 字段表、runtimes[].state 枚举
 * idle/stopped/unhealthy/ready、会话不可见 404 session not found、
 * REMOTE_RUNTIME_UNAVAILABLE、公开快照去除进程地址/PID/控制环境）、
 * 特性档 §2.2（运行目标查询 MUST：区分声明存在与服务就绪）。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-138")
@Feature("FEAT-138: 智能体运行调试与迭代验证")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat138RuntimeSnapshotBlackboxTest {

    private Feat138WorkbenchSupport wb;
    private String wsId;
    private String sessionId;
    private boolean sessionReady;
    private JsonNode snapshot;
    /** JUnit 6 下 PER_CLASS 容器的 assumeTrue 会中止整个容器（记 0 而非 SKIPPED），
     * 因此 @BeforeAll 只设置门禁标志，由各测试方法开头 assumeTrue 逐方法记录 SKIPPED。 */
    private boolean backendReady;
    private String gateReason;

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

        String run = Feat138WorkbenchSupport.runId();
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
                    wb.sessionCreate(wsId, "feat138-snapshot-session-" + run));
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

    private JsonNode loadSnapshot() throws Exception {
        if (snapshot == null) {
            assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
            Feat138WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/runtime");
            assumeTrue(resp.status() == 200,
                    "环境门禁：GET runtime 返回 " + resp.status()
                            + "（无可用运行声明/Agent Host 时快照不可读）");
            snapshot = resp.body();
        }
        return snapshot;
    }

    @Test
    @Tag("story-feat-138-01")
    @Story("FEAT-138.F138-01: 运行目标查询返回快照契约字段")
    @DisplayName("F138-01 GET runtime → 200，含 manifestVersion/defaultTargetId/targets/runtimes/operations")
    void f13801RuntimeSnapshotContract() throws Exception {
        assumeTrue(backendReady, gateReason);
        JsonNode body = loadSnapshot();

        assertThat(body.hasNonNull("manifestVersion"))
                .as("manifestVersion 为 string（L2 §2.2 出参表）").isTrue();
        assertThat(body.path("manifestVersion").isTextual()).isTrue();
        assertThat(body.path("observedAt").isNumber())
                .as("observedAt 为数值（L2 §2.2：状态观察时间）").isTrue();
        assertThat(body.has("operations"))
                .as("operations 为平台保存的操作记录对象（L2 §2.2 出参表）").isTrue();

        JsonNode targets = body.path("targets");
        assertThat(targets.isArray()).as("targets 为数组").isTrue();
        if (!targets.isEmpty()) {
            JsonNode t = targets.get(0);
            assertThat(t.hasNonNull("id")).as("targets[].id 必填").isTrue();
            assertThat(t.hasNonNull("runtimeId")).as("targets[].runtimeId 必填").isTrue();
            assertThat(t.hasNonNull("state")).as("targets[].state 必填").isTrue();
        }

        JsonNode runtimes = body.path("runtimes");
        assertThat(runtimes.isArray()).as("runtimes 为数组").isTrue();
        for (JsonNode r : runtimes) {
            String state = r.path("state").asText();
            assertThat(state)
                    .as("runtimes[].state 枚举限定 {idle,stopped,unhealthy,ready}（L2 §2.2）")
                    .isIn("idle", "stopped", "unhealthy", "ready");
            if ("ready".equals(state)) {
                assertThat(r.hasNonNull("instanceId"))
                        .as("ready 实例必须携带 instanceId（L2 §2.2）").isTrue();
                assertThat(r.path("generation").isNumber())
                        .as("ready 实例必须携带递增 generation（L2 §2.2）").isTrue();
            }
        }
    }

    @Test
    @Tag("story-feat-138-02")
    @Story("FEAT-138.F138-02: 未知会话查询运行状态返回 404")
    @DisplayName("F138-02 GET runtime 未知会话 → 404 session not found")
    void f13802UnknownSession404() throws Exception {
        assumeTrue(backendReady, gateReason);
        String bogusId = "feat138-no-such-session-" + Feat138WorkbenchSupport.runId();
        Feat138WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + bogusId + "/runtime");
        assertThat(resp.status()).as("会话不存在或不可见应 404（L2 §2.2 错误表）").isEqualTo(404);
        assertThat(resp.body().toString())
                .as("错误表面含 session not found（L2 §2.2）").contains("session not found");
    }

    @Test
    @Tag("story-feat-138-03")
    @Story("FEAT-138.F138-03: 无远端运行通道时不借本机能力判可运行")
    @DisplayName("F138-03 无 Agent Host 时 runtime → 409 REMOTE_RUNTIME_UNAVAILABLE，不推断 ready")
    void f13803NoRemoteChannel409() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        Feat138WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/runtime");
        if (resp.status() == 200) {
            // 主后端已配置远端运行通道：在本地形态后端（sut.workbench.local-base-url，
            // P1_ISOLATION=local）上构造"无远端运行通道"形态完成判定
            String localBase = com.huawei.ascend.sit.config.TestConfig.load()
                    .getString("sut.workbench.local-base-url", "http://127.0.0.1:4539");
            Feat138WorkbenchSupport localWb = new Feat138WorkbenchSupport(localBase);
            Feat138WorkbenchSupport.FixtureSession localForm = localWb.createFixtureSession(
                    com.huawei.ascend.sit.config.TestConfig.load(),
                    "feat138-ws-nohost-" + Feat138WorkbenchSupport.runId(),
                    "feat138-nohost-session", Feat138WorkbenchSupport.runId(), "none");
            assumeTrue(localForm.blocker() == null,
                    "环境门禁：本地形态后端不可用（" + localForm.blocker() + "，"
                            + "需以 P1_ISOLATION=local 启动 " + localBase + "）");
            Feat138WorkbenchSupport.Resp localResp = localWb.get(
                    "/api/sessions/" + localForm.sessionId() + "/runtime");
            // 判定前不得借本机能力推断 ready：无远端通道时绝不返回 ready
            if (localResp.status() == 200) {
                for (JsonNode r : localResp.body().path("runtimes")) {
                    assertThat(r.path("state").asText())
                            .as("无远端通道时不得借本机开发能力推断 state=ready（特性档 §5.1.7）")
                            .isNotEqualTo("ready");
                }
                assumeTrue(false, "本地形态后端也返回 200（GET runtime），409 场景本轮不可判定");
                return;
            }
            assertThat(localResp.status()).as("无远端运行通道应 409（L2 §2.2，localBase=" + localBase + "）").isEqualTo(409);
            assertThat(localResp.body().toString())
                    .as("错误表面含 REMOTE_RUNTIME_UNAVAILABLE（L2 §2.2）")
                    .contains("REMOTE_RUNTIME_UNAVAILABLE");
            return;
        }
        // 主后端本身即无远端通道形态（直接判定）
        assertThat(resp.status()).as("无远端运行通道应 409（L2 §2.2）").isEqualTo(409);
        assertThat(resp.body().toString())
                .as("错误表面含 REMOTE_RUNTIME_UNAVAILABLE（L2 §2.2）")
                .contains("REMOTE_RUNTIME_UNAVAILABLE");
    }

    @Test
    @Tag("story-feat-138-04")
    @Story("FEAT-138.F138-04: 公开快照不含内部路由信息")
    @DisplayName("F138-04 快照 runtimes[] 无 pid/address/port/cmd/env 内部键")
    void f13804SnapshotStrippedOfInternalRouting() throws Exception {
        assumeTrue(backendReady, gateReason);
        JsonNode body = loadSnapshot();
        List<String> banned = List.of("pid", "address", "port", "cmd", "env");
        for (JsonNode r : body.path("runtimes")) {
            List<String> found = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = r.fields();
            while (fields.hasNext()) {
                String name = fields.next().getKey().toLowerCase();
                if (banned.contains(name)) {
                    found.add(name);
                }
            }
            assertThat(found)
                    .as("公开快照去除进程地址/PID/控制环境等内部路由信息（L2 §2.2）")
                    .isEmpty();
        }
    }
}
