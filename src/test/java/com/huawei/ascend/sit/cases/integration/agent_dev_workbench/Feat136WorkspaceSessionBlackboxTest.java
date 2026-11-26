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
 * FEAT-136 工作区与开发会话契约黑盒验收（场景矩阵 F136-01～F136-08）。
 *
 * <p>期望值出处：L2 §2.2（工作区/会话契约：201 字段集、400/409/404 错误面、
 * 改名保留原工程路径、会话创建失败不删除工作区）、特性档 §2.3（创建开发上下文 MUST）。
 * 全部断言位于 HTTP wire，不读取 SUT 内部状态。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-136")
@Feature("FEAT-136: 对话式开发高码智能体")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat136WorkspaceSessionBlackboxTest {

    private Feat136WorkbenchSupport wb;
    private String wsId;
    private String wsName;
    private String wsPath;
    private String llmBlocker;

    private final List<String> createdWorkspaceIds = new ArrayList<>();

    @BeforeAll
    void probeBackendAndCreateWorkspace() throws Exception {
        TestConfig config = TestConfig.load();
        // 共享前置：免鉴权就绪探测 + 本地账号登录（App.java：/api/* 除 /api/deployment、/api/auth/* 均需鉴权）
        Feat136WorkbenchSupport.Ready ready = Feat136WorkbenchSupport.ready(config);
        assumeTrue(ready.wb() != null, ready.blocker());
        wb = ready.wb();
        // 会话前置：默认模型提供方（F136-05/F136-08 依赖）
        llmBlocker = Feat136WorkbenchSupport.ensureDefaultLlmFromEnv(wb);

        // F136-01 前置产物：共享工作区
        wsName = "feat136-ws-" + Feat136WorkbenchSupport.runId();
        Feat136WorkbenchSupport.Resp created = wb.sendWithBody("POST", "/api/workspaces",
                wb.workspaceByName(wsName));
        if (created.status() == 201) {
            wsId = created.body().path("id").asText();
            wsPath = created.body().path("path").asText();
            createdWorkspaceIds.add(wsId);
        }
    }

    @AfterAll
    void cleanupWorkspaces() throws Exception {
        for (String id : createdWorkspaceIds) {
            try {
                wb.delete("/api/workspaces/" + id);
            } catch (Exception ignored) {
                // 清理尽力而为
            }
        }
    }

    @Test
    @Tag("story-feat-136-01")
    @Story("FEAT-136.F136-01: 创建工作区返回完整记录字段")
    @DisplayName("F136-01 创建工作区（名称方式）→ 201 + id/ownerId/name/path/createdAt/lastUsedAt")
    void f13601CreateWorkspaceByName() throws Exception {
        String name = "feat136-ws-" + Feat136WorkbenchSupport.runId();
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST", "/api/workspaces",
                wb.workspaceByName(name));

        assertThat(resp.status()).as("创建工作区应返回 201（L2 §2.2）").isEqualTo(201);
        JsonNode body = resp.body();
        assertThat(body.path("id").asText()).as("body.id 非空 string").isNotBlank();
        assertThat(body.path("ownerId").asText()).as("body.ownerId 非空 string").isNotBlank();
        assertThat(body.path("name").asText()).as("body.name == 请求值").isEqualTo(name);
        assertThat(body.path("path").asText()).as("body.path 非空 string").isNotBlank();
        assertThat(body.path("createdAt").asText()).as("body.createdAt 存在").isNotEmpty();
        assertThat(body.path("lastUsedAt").asText()).as("body.lastUsedAt 存在").isNotEmpty();
        createdWorkspaceIds.add(body.path("id").asText());
    }

    @Test
    @Tag("story-feat-136-02")
    @Story("FEAT-136.F136-02: 缺少名称与目录条件时拒绝创建")
    @DisplayName("F136-02 创建工作区 body={} → 400")
    void f13602CreateWorkspaceWithoutNameAndPath() throws Exception {
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST", "/api/workspaces", wb.obj());
        assertThat(resp.status()).as("缺 name 与 path 应 400（L2 §2.2 场景表）").isEqualTo(400);
    }

    @Test
    @Tag("story-feat-136-03")
    @Story("FEAT-136.F136-03: 首次按路径登记成功，重复登记返回 409")
    @DisplayName("F136-03 首次按路径登记 → 201；同目录重复登记 → 409")
    void f13603PathRegistrationSuccessThenDuplicateConflict() throws Exception {
        // 审阅 #599 A3：原用例只测重复登记失败，须补首次按路径登记成功。
        // 后端约束（App.java explicitWorkspaceTarget）：路径必须位于当前用户工作区根目录之内且目录已存在。
        // 用户工作区根目录从 F136-01 按名称登记返回的 path 的父目录推导（local 形态同机部署）。
        assumeTrue(wsPath != null && !wsPath.isBlank(),
                "前置 F136-01 工作区未创建成功，无法推导用户工作区根目录");
        java.nio.file.Path userRoot = java.nio.file.Path.of(wsPath).getParent();
        assertThat(userRoot).as("可从工作区路径推导用户根目录").isNotNull();
        java.nio.file.Path projectDir = userRoot.resolve("feat136-path-reg-" + Feat136WorkbenchSupport.runId());
        java.nio.file.Files.createDirectories(projectDir);

        Feat136WorkbenchSupport.Resp first = wb.sendWithBody("POST", "/api/workspaces",
                wb.workspaceByPath(projectDir.toString()));

        assertThat(first.status())
                .as("首次按路径登记应 201（审阅 #599 A3 修订；L2 §2.2）")
                .isEqualTo(201);
        assertThat(first.body().path("id").asText()).as("首次登记返回 id 非空").isNotBlank();
        String firstId = first.body().path("id").asText();
        if (!firstId.isBlank()) {
            createdWorkspaceIds.add(firstId);
        }

        // 同一目录重复登记：409
        Feat136WorkbenchSupport.Resp duplicate = wb.sendWithBody("POST", "/api/workspaces",
                wb.workspaceByPath(projectDir.toString()));
        assertThat(duplicate.status()).as("同一目录重复登记应 409（L2 §2.2）").isEqualTo(409);
    }

    @Test
    @Tag("story-feat-136-04")
    @Story("FEAT-136.F136-04: 未知工作区不可见")
    @DisplayName("F136-04 PATCH 未知工作区 → 404 WORKSPACE_NOT_FOUND")
    void f13604UnknownWorkspaceNotFound() throws Exception {
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("PATCH", "/api/workspaces/nonexistent-feat136",
                wb.workspaceByName("x"));
        assertThat(resp.status()).as("未知工作区应 404（L2 §2.2 场景表）").isEqualTo(404);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 WORKSPACE_NOT_FOUND（L2 §2.2）")
                .isEqualTo("WORKSPACE_NOT_FOUND");
    }

    @Test
    @Tag("story-feat-136-05")
    @Story("FEAT-136.F136-05: 创建开发会话返回 opencode 引擎契约")
    @DisplayName("F136-05 创建开发会话 → 201 + engineId=opencode + llm 快照")
    void f13605CreateSessionForWorkspace() throws Exception {
        assumeTrue(wsId != null, "前置 F136-01 工作区未创建成功");
        assumeTrue(llmBlocker == null, llmBlocker);
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST", "/api/sessions",
                wb.sessionCreate(wsId, "feat136-session"));

        assertThat(resp.status()).as("会话创建应 201（L2 §2.2）").isEqualTo(201);
        JsonNode body = resp.body();
        assertThat(body.path("engineId").asText()).as("engineId == opencode（L2 §2.2）").isEqualTo("opencode");
        assertThat(body.path("workspaceId").asText()).as("workspaceId == 请求值").isEqualTo(wsId);
        assertThat(body.path("id").asText()).as("body.id 非空").isNotBlank();
        assertThat(body.path("cwd").asText()).as("body.cwd 非空").isNotBlank();
        assertThat(body.path("status").asText()).as("body.status 非空").isNotBlank();
        assertThat(body.path("live").isBoolean()).as("body.live 为 boolean").isTrue();
        assertThat(body.path("llm").isObject()).as("body.llm 为快照对象（L2 §2.2）").isTrue();
    }

    @Test
    @Tag("story-feat-136-06")
    @Story("FEAT-136.F136-06: 未知工作区创建会话被拒")
    @DisplayName("F136-06 未知 workspaceId 创建会话 → 404")
    void f13606CreateSessionOnUnknownWorkspace() throws Exception {
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST", "/api/sessions",
                wb.sessionCreate("nonexistent-feat136", "feat136-session"));
        assertThat(resp.status()).as("未知工作区创建会话应 404（L2 §2.2）").isEqualTo(404);
    }

    @Test
    @Tag("story-feat-136-07")
    @Story("FEAT-136.F136-07: 工作区改名保留原工程路径")
    @DisplayName("F136-07 PATCH 改名 → path 与创建时逐字一致")
    void f13607RenameKeepsPath() throws Exception {
        assumeTrue(wsId != null && wsPath != null, "前置 F136-01 工作区未创建成功");
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("PATCH", "/api/workspaces/" + wsId,
                wb.workspaceByName(wsName + "-renamed"));

        assertThat(resp.status()).as("改名应 200（L2 §2.2）").isEqualTo(200);
        assertThat(resp.body().path("path").asText())
                .as("改名保留原工程路径（特性档 §5.1.1）")
                .isEqualTo(wsPath);
        assertThat(resp.body().path("name").asText()).as("name 为新值").isEqualTo(wsName + "-renamed");
    }

    @Test
    @Tag("story-feat-136-08")
    @Story("FEAT-136.F136-08: 会话创建失败不删除工作区")
    @DisplayName("F136-08 引擎不可连时会话创建失败且工作区保留")
    void f13608WorkspaceSurvivesSessionFailure() throws Exception {
        assumeTrue(wsName != null, "前置 F136-01 工作区未创建成功");
        // 引擎/模型不可连属当前部署已知事实（L2 §7.2 场景旅程：开发连接初始化失败）
        Feat136WorkbenchSupport.Resp create = wb.sendWithBody("POST", "/api/sessions",
                wb.sessionCreate(wsId, "feat136-session-failpath"));

        if (create.status() == 500) {
            // 会话创建失败：工作区必须仍出现在列表（L2 §2.2：会话创建失败不删除工作区）
            Feat136WorkbenchSupport.Resp list = wb.get("/api/workspaces");
            assertThat(list.status()).isEqualTo(200);
            boolean found = false;
            for (JsonNode item : list.body().path("items")) {
                if (wsName.equals(item.path("name").asText())) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("会话创建 500 后工作区仍存在于列表").isTrue();
        } else {
            // 引擎实际可连：记录实际行为，不伪造失败注入
            assertThat(create.status())
                    .as("引擎可连时会话创建成功（201），失败注入仅在引擎不可连时成立")
                    .isEqualTo(201);
        }
    }
}