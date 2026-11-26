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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-136 ZIP 导入契约黑盒验收（场景矩阵 F136-20～F136-26、F136-39）。
 *
 * <p>期望值出处：L2 §2.7（token 为 64 位小写十六进制、changes[] 的 path/before/after、
 * PREVIEW_REQUIRED/INVALID_ARCHIVE/UNSUPPORTED_TEXT/INVALID_PATH/PREVIEW_CHANGED、
 * files 为实际变更数）、§3.6（令牌绑定目标内容，未涉及文件变化不影响令牌）。
 * 审阅 #599 E 组修订：F136-39 覆盖同名 ZIP 覆盖预览差异与确认前文件保持原样
 * （「快速开始」上传同名 ZIP 的浏览器确认场景归页面验收，本用例为其 wire 前置事实）。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-136")
@Feature("FEAT-136: 对话式开发高码智能体")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat136ZipImportBlackboxTest {

    private static final Pattern TOKEN_64_HEX = Pattern.compile("[0-9a-f]{64}");

    private Feat136WorkbenchSupport wb;
    private String wsId;
    private String sessionId;
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
                        wb.sessionCreate(wsId, "feat136-zip-session"));
                if (session.status() == 201) {
                    sessionId = session.body().path("id").asText();
                    sessionReady = true;
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
        return "/api/sessions/" + sessionId + "/upload/preview";
    }

    private String uploadPath() {
        return "/api/sessions/" + sessionId + "/upload";
    }

    @Test
    @Tag("story-feat-136-20")
    @Story("FEAT-136.F136-20: ZIP 预览返回令牌与变更集")
    @DisplayName("F136-20 upload/preview → token 64位小写hex + changes[0].before==null")
    void f13620PreviewReturnsTokenAndChanges() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        byte[] zip = Feat136WorkbenchSupport.zipOf("feat136-import/README.md", "# feat136 import probe\n");
        Feat136WorkbenchSupport.Resp resp = wb.sendRaw("POST", previewPath(), zip, null);

        assertThat(resp.status()).as("预览应 200（L2 §2.7）").isEqualTo(200);
        String token = resp.body().path("token").asText();
        assertThat(token).as("token 非空").isNotBlank();
        assertThat(TOKEN_64_HEX.matcher(token).matches())
                .as("token 为 64 位小写十六进制（L2 §2.7）")
                .isTrue();
        JsonNode changes = resp.body().path("changes");
        assertThat(changes.isArray() && changes.size() == 1)
                .as("changes 恰含 1 个文件").isTrue();
        assertThat(changes.get(0).path("path").asText())
                .as("changes[0].path == 导入路径").isEqualTo("feat136-import/README.md");
        assertThat(changes.get(0).path("before").isNull())
                .as("新增文件 before == null（L2 §2.7）").isTrue();
        assertThat(changes.get(0).path("after").asText()).as("after 非空").isNotBlank();
    }

    @Test
    @Tag("story-feat-136-21")
    @Story("FEAT-136.F136-21: 应用返回实际变更数，重复应用为零变更")
    @DisplayName("F136-21 upload 带 token → files==1；同内容再次预览 changes 为空")
    void f13621ApplyAndRePreviewEmptyChanges() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        byte[] zip = Feat136WorkbenchSupport.zipOf("feat136-import/apply.md", "apply probe\n");
        Feat136WorkbenchSupport.Resp preview = wb.sendRaw("POST", previewPath(), zip, null);
        assumeTrue(preview.status() == 200, "预览未成功（HTTP " + preview.status() + "）");
        String token = preview.body().path("token").asText();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Archive-Preview", token);
        Feat136WorkbenchSupport.Resp applied = wb.sendRaw("POST", uploadPath(), zip, headers);
        assertThat(applied.status()).as("应用应 200（L2 §2.7）").isEqualTo(200);
        assertThat(applied.body().path("files").asInt())
                .as("files == 实际变更数 1（L2 §2.7）")
                .isEqualTo(1);

        Feat136WorkbenchSupport.Resp again = wb.sendRaw("POST", previewPath(), zip, null);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("changes").size())
                .as("内容不变时重复预览 changes 为空（L2 §3.6：没有变化的文件不进变更集）")
                .isZero();
    }

    @Test
    @Tag("story-feat-136-22")
    @Story("FEAT-136.F136-22: 应用缺少预览令牌被拒绝")
    @DisplayName("F136-22 upload 无 X-Archive-Preview → 400 PREVIEW_REQUIRED")
    void f13622ApplyWithoutTokenRejected() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        byte[] zip = Feat136WorkbenchSupport.zipOf("feat136-import/no-token.md", "no token\n");
        Feat136WorkbenchSupport.Resp resp = wb.sendRaw("POST", uploadPath(), zip, null);

        assertThat(resp.status()).as("缺令牌应用应 400（L2 §2.7 错误表）").isEqualTo(400);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 PREVIEW_REQUIRED（L2 §2.7）")
                .isEqualTo("PREVIEW_REQUIRED");
    }

    @Test
    @Tag("story-feat-136-23")
    @Story("FEAT-136.F136-23: 损坏 ZIP 在预览阶段被拒绝")
    @DisplayName("F136-23 截断 ZIP 预览 → 400 INVALID_ARCHIVE")
    void f13623CorruptedZipRejected() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        byte[] broken = Feat136WorkbenchSupport.truncatedZip();
        Feat136WorkbenchSupport.Resp resp = wb.sendRaw("POST", previewPath(), broken, null);

        assertThat(resp.status()).as("损坏 ZIP 应 400（L2 §2.7 错误表）").isEqualTo(400);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 INVALID_ARCHIVE（L2 §2.7）")
                .isEqualTo("INVALID_ARCHIVE");
    }

    @Test
    @Tag("story-feat-136-24")
    @Story("FEAT-136.F136-24: 二进制条目被拒绝")
    @DisplayName("F136-24 含控制字符条目预览 → 400 UNSUPPORTED_TEXT")
    void f13624BinaryEntryRejected() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        byte[] zip = Feat136WorkbenchSupport.zipWithBinaryEntry("feat136-bin/data.bin");
        Feat136WorkbenchSupport.Resp resp = wb.sendRaw("POST", previewPath(), zip, null);

        assertThat(resp.status()).as("二进制条目应 400（L2 §2.7：只接受 UTF-8 文本）").isEqualTo(400);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 UNSUPPORTED_TEXT（L2 §2.7）")
                .isEqualTo("UNSUPPORTED_TEXT");
    }

    @Test
    @Tag("story-feat-136-25")
    @Story("FEAT-136.F136-25: 路径穿越条目在预览阶段即被拒绝")
    @DisplayName("F136-25 条目 ../escape.txt 预览 → 400 INVALID_PATH 且无工程变更")
    void f13625PathTraversalRejected() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        byte[] zip = Feat136WorkbenchSupport.zipOf("../feat136-escape.txt", "traversal probe\n");
        Feat136WorkbenchSupport.Resp resp = wb.sendRaw("POST", previewPath(), zip, null);

        assertThat(resp.status()).as("路径穿越应 400（L2 §2.7：拒绝路径穿越）").isEqualTo(400);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码含 INVALID_PATH（L2 §2.7 错误表）")
                .contains("INVALID_PATH");
    }

    @Test
    @Tag("story-feat-136-26")
    @Story("FEAT-136.F136-26: 目标变化后旧预览令牌失效")
    @DisplayName("F136-26 目标被 v3 覆盖后提交 v2 旧令牌 → 409 PREVIEW_CHANGED")
    void f13626StaleTokenRejectedAfterTargetChanged() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        String target = "feat136-import/stale-token.md";

        // v2 预览：取得 token-A（不应用）
        byte[] zipV2 = Feat136WorkbenchSupport.zipOf(target, "stale token probe v2\n");
        Feat136WorkbenchSupport.Resp previewV2 = wb.sendRaw("POST", previewPath(), zipV2, null);
        assumeTrue(previewV2.status() == 200, "v2 预览未成功（HTTP " + previewV2.status() + "）");
        String tokenA = previewV2.body().path("token").asText();

        // v3 预览并应用：目标内容变为 v3
        byte[] zipV3 = Feat136WorkbenchSupport.zipOf(target, "fresh content v3\n");
        Feat136WorkbenchSupport.Resp previewV3 = wb.sendRaw("POST", previewPath(), zipV3, null);
        assumeTrue(previewV3.status() == 200, "v3 预览未成功");
        Map<String, String> headersV3 = new LinkedHashMap<>();
        headersV3.put("X-Archive-Preview", previewV3.body().path("token").asText());
        Feat136WorkbenchSupport.Resp appliedV3 = wb.sendRaw("POST", uploadPath(), zipV3, headersV3);
        assumeTrue(appliedV3.status() == 200, "v3 应用未成功（HTTP " + appliedV3.status() + "）");

        // 旧令牌 token-A 对已变化目标提交：必须 409 PREVIEW_CHANGED（L2 §2.7/§7.3 ZIP 并发修改）
        Map<String, String> staleHeaders = new LinkedHashMap<>();
        staleHeaders.put("X-Archive-Preview", tokenA);
        Feat136WorkbenchSupport.Resp stale = wb.sendRaw("POST", uploadPath(), zipV2, staleHeaders);

        assertThat(stale.status())
                .as("目标内容与预览不一致应 409（L2 §2.7 错误表）")
                .isEqualTo(409);
        assertThat(stale.body().path("error").path("code").asText())
                .as("错误码逐字为 PREVIEW_CHANGED（L2 §2.7）")
                .isEqualTo("PREVIEW_CHANGED");

        // 工程内容保持 v3：再次预览 v2 内容应出现 before==v3 的覆盖变更
        Feat136WorkbenchSupport.Resp check = wb.sendRaw("POST", previewPath(), zipV2, null);
        assertThat(check.status()).isEqualTo(200);
        boolean beforeIsV3 = false;
        for (JsonNode change : check.body().path("changes")) {
            if (target.equals(change.path("path").asText())
                    && change.path("before").asText("").contains("fresh content v3")) {
                beforeIsV3 = true;
            }
        }
        assertThat(beforeIsV3).as("目标内容保持 v3，未被旧确认覆盖").isTrue();
    }

    @Test
    @Tag("story-feat-136-39")
    @Story("FEAT-136.F136-39: 同名 ZIP 覆盖预览差异与确认前文件保持原样")
    @DisplayName("F136-39 同名覆盖预览 → before/after 差异；确认前文件保持原样；应用后生效")
    void f13639SameNameOverwritePreviewDiffAndHoldBeforeConfirm() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        String target = "feat136-import/overwrite-probe.md";
        String v1 = "original content v1\n";
        String v2 = "overwritten content v2\n";

        // ① 先以 v1 内容导入目标文件，建立工程现有内容
        byte[] zipV1 = Feat136WorkbenchSupport.zipOf(target, v1);
        Feat136WorkbenchSupport.Resp previewV1 = wb.sendRaw("POST", previewPath(), zipV1, null);
        assumeTrue(previewV1.status() == 200, "v1 预览未成功（HTTP " + previewV1.status() + "）");
        java.util.Map<String, String> headersV1 = new LinkedHashMap<>();
        headersV1.put("X-Archive-Preview", previewV1.body().path("token").asText());
        Feat136WorkbenchSupport.Resp appliedV1 = wb.sendRaw("POST", uploadPath(), zipV1, headersV1);
        assumeTrue(appliedV1.status() == 200, "v1 应用未成功（HTTP " + appliedV1.status() + "）");

        // ② 同名 v2 预览：呈现覆盖差异 before==v1 现有内容、after==v2 新内容
        byte[] zipV2 = Feat136WorkbenchSupport.zipOf(target, v2);
        Feat136WorkbenchSupport.Resp previewV2 = wb.sendRaw("POST", previewPath(), zipV2, null);
        assertThat(previewV2.status()).as("同名覆盖预览应 200（L2 §2.7）").isEqualTo(200);
        JsonNode changes = previewV2.body().path("changes");
        JsonNode targetChange = null;
        for (JsonNode change : changes) {
            if (target.equals(change.path("path").asText())) {
                targetChange = change;
            }
        }
        assertThat(targetChange).as("同名覆盖预览 changes 恰含目标文件").isNotNull();
        assertThat(targetChange.path("before").asText())
                .as("覆盖差异 before == 工程现有 v1 内容（用户可见差异，审阅 #599 E 组）")
                .isEqualTo(v1);
        assertThat(targetChange.path("after").asText())
                .as("覆盖差异 after == v2 新内容")
                .isEqualTo(v2);

        // ③ 确认（应用）前文件保持原样：再次预览核对 before 仍为 v1（未自动应用）
        Feat136WorkbenchSupport.Resp beforeConfirm = wb.sendRaw("POST", previewPath(), zipV2, null);
        assertThat(beforeConfirm.status()).isEqualTo(200);
        boolean holdOriginal = false;
        for (JsonNode change : beforeConfirm.body().path("changes")) {
            if (target.equals(change.path("path").asText())
                    && v1.equals(change.path("before").asText())) {
                holdOriginal = true;
            }
        }
        assertThat(holdOriginal)
                .as("确认前工程文件保持 v1 原样（预览不产生应用，审阅 #599 E 组）")
                .isTrue();

        // ④ 带 token 应用 v2 后变更生效：files==1；重复预览无变更
        String tokenV2 = previewV2.body().path("token").asText();
        java.util.Map<String, String> headersV2 = new LinkedHashMap<>();
        headersV2.put("X-Archive-Preview", tokenV2);
        Feat136WorkbenchSupport.Resp appliedV2 = wb.sendRaw("POST", uploadPath(), zipV2, headersV2);
        assertThat(appliedV2.status()).as("确认后应用应 200（L2 §2.7）").isEqualTo(200);
        assertThat(appliedV2.body().path("files").asInt())
                .as("应用返回实际变更数 files==1（L2 §2.7）")
                .isEqualTo(1);

        Feat136WorkbenchSupport.Resp afterApply = wb.sendRaw("POST", previewPath(), zipV2, null);
        assertThat(afterApply.status()).isEqualTo(200);
        assertThat(afterApply.body().path("changes").size())
                .as("应用生效后同内容重复预览 changes 为空（L2 §3.6）")
                .isZero();
    }
}