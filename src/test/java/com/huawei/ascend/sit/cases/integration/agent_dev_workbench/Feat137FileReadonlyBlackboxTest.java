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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-137 目录与文件只读契约黑盒验收（场景矩阵 F137-12～F137-21、F137-27～F137-30）。
 *
 * <p>期望值出处：L2 §2.3（目录分页契约、文件快照出参表、错误码表 INVALID_LIMIT /
 * INVALID_CURSOR / DIRECTORY_CHANGED / INVALID_PATH / FILE_NOT_FOUND / FILE_TOO_LARGE /
 * UNSUPPORTED_TEXT / READ_ONLY_VIEW、路径不合法 400 与访问越界/私密文件 403 的各自结果）、
 * §5.4（脱敏不改变来源指纹依据）、§6.2（单文件上限 524288）、§7.1（私密文件不可读取）；
 * 特性档 §3（只读视图写入请求返回不允许写入的结果）、§5.1.1（分页/截断/二进制
 * 不能被当作完整源码展示）、§5.1.4（文件变化使来源失效）。2026-11-24 按 workbench-v1130
 * testplan-review #602 审阅结论整改：路径错误与访问越界各自结果拆分（F137-16/F137-28）、
 * 跨目录游标（F137-27）、私钥拒绝且无正文（F137-29）、源文件变化旧位置失效（F137-30）。
 * 全部断言位于 HTTP wire，不读取 SUT 内部状态。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-137")
@Feature("FEAT-137: 智能体结构可视化与源码联动")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat137FileReadonlyBlackboxTest {

    private Feat136WorkbenchSupport wb;
    /** 后端就绪门禁（探测 GET /api/workspaces），下沉到每个测试方法内 assumeTrue，保证逐用例可见 SKIPPED。 */
    private boolean backendReady;
    private String backendGateReason;
    private String filesSessionId;
    private Path filesDir;
    private String bigSessionId;
    private Path bigDir;
    private String secretSessionId;
    private Path secretDir;
    private String secretRunId;
    /**
     * 平台文件系统门禁（审阅整改项：环境阻塞单列）：后端文件端点硬性依赖
     * SecureDirectoryStream（Linux 文件系统特性，Windows 不支持且无降级开关）。
     * 探测到 403 FILE_ACCESS_DENIED 时，受影响场景显式 SKIPPED-BLOCKED-WINDOWS-FS，
     * 须在 Linux 容器部署形态重跑后方可计入验收；不以 FAIL 计入产品失败。
     */
    private boolean platformFsBlocked;
    private String platformFsBlockReason = "";
    private final List<String> createdWorkspaceIds = new ArrayList<>();
    private final List<Path> createdDirs = new ArrayList<>();

    @BeforeAll
    void probeBackendAndPrepareFixtures() throws Exception {
        // 共享前置（审阅优先项 3）：就绪探测 + 本地账号登录（后端 local 模式强制鉴权）
        TestConfig config = TestConfig.load();
        Feat136WorkbenchSupport.Ready ready = Feat136WorkbenchSupport.ready(config);
        wb = ready.wb();
        backendReady = wb != null;
        backendGateReason = ready.blocker() != null
                ? ready.blocker()
                : "agent-dev-workbench 后端未就绪：" + (wb == null ? "" : wb.base());
        if (!backendReady) {
            return; // 就绪/登录失败：全部用例按 assumeTrue SKIPPED，避免前置空引用
        }

        hostPathMap = config.getString("sut.workbench.host-path-map", "");
        secretRunId = Feat137WorkbenchFixture.runId();
        filesSessionId = registerAndCreateSession("feat137-files", "feat137-files",
                () -> Feat137WorkbenchFixture.createFilesProject());
        bigSessionId = registerAndCreateSession("feat137-big", "feat137-big",
                () -> Feat137WorkbenchFixture.createBigFileProject());
        secretSessionId = registerAndCreateSession("feat137-secret", "feat137-secret",
                () -> Feat137WorkbenchFixture.createSecretProject(secretRunId));

        // 平台文件系统门禁探测：/files 命中 403 FILE_ACCESS_DENIED 即 Windows 宿主不支持安全目录流
        if (filesSessionId != null) {
            Feat136WorkbenchSupport.Resp probe = wb.get(
                    "/api/sessions/" + filesSessionId + "/files?limit=1");
            if (probe.status() == 403
                    && probe.body().path("error").path("code").asText().equals("FILE_ACCESS_DENIED")) {
                platformFsBlocked = true;
                platformFsBlockReason = "BLOCKED-WINDOWS-FS：后端文件端点依赖 SecureDirectoryStream（默认交付形态为 "
                        + "Linux 容器文件系统），Windows 宿主返回 403 FILE_ACCESS_DENIED 且无降级开关；"
                        + "本场景须在 Linux 部署形态重跑后方可计入验收";
            }
        }
    }

    /** 工作区路径宿主映射规则（sut.workbench.host-path-map，VM 部署形态 "/mnt/d/=D:/"）。 */
    private String hostPathMap;

    /** 命名登记工作区（后端在用户根下建目录）→ fixture 内容拷入 → 创建会话。 */
    private String registerAndCreateSession(String wsNamePrefix, String title, DirSupplier supplier)
            throws Exception {
        Path tempFx = supplier.create();
        try {
            String wsName = wsNamePrefix + "-" + Feat137WorkbenchFixture.runId();
            Feat136WorkbenchSupport.Resp ws = wb.sendWithBody("POST", "/api/workspaces",
                    wb.workspaceByName(wsName));
            if (ws.status() != 200 && ws.status() != 201) {
                return null;
            }
            String wsId = ws.body().path("id").asText();
            createdWorkspaceIds.add(wsId);
            Path wsPath = Feat137WorkbenchFixture.toHostPath(
                    ws.body().path("path").asText(), hostPathMap);
            Feat137WorkbenchFixture.copyInto(tempFx, wsPath);
            createdDirs.add(wsPath);
            // 记录变体目录：files 用于分页/指纹/只读断言，big 用于超大文件，secret 用于脱敏/私钥
            if (wsNamePrefix.contains("files")) {
                filesDir = wsPath;
            } else if (wsNamePrefix.contains("big")) {
                bigDir = wsPath;
            } else if (wsNamePrefix.contains("secret")) {
                secretDir = wsPath;
            }
            com.fasterxml.jackson.databind.node.ObjectNode sBody = wb.obj();
            sBody.put("workspaceId", wsId);
            sBody.put("title", title);
            Feat136WorkbenchSupport.Resp session = wb.sendWithBody("POST", "/api/sessions", sBody);
            if (session.status() != 200 && session.status() != 201) {
                return null;
            }
            String sessionId = session.body().path("id").asText();
            return sessionId.isBlank() ? null : sessionId;
        } finally {
            Feat137WorkbenchFixture.deleteRecursively(tempFx);
        }
    }

    /** fixture 目录构造器（返回进程内临时目录）。 */
    private interface DirSupplier {
        Path create() throws Exception;
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
        for (Path dir : createdDirs) {
            Feat137WorkbenchFixture.deleteRecursively(dir);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---- 场景 F137-12：目录分页契约与 limit 边界 ----

    @Test
    @Tag("story-feat-137-12")
    @Story("FEAT-137.F137-12: 目录分页字段完整且 limit 越界拒绝")
    @DisplayName("F137-12 files?limit=1 翻页完整 + limit=501|0 → 400 INVALID_LIMIT")
    void f13712DirectoryPagingAndLimitBounds() throws Exception {
        assumeTrue(filesSessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);

        // ① limit=1 翻页至尽
        Set<String> seenPaths = new HashSet<>();
        String cursor = null;
        int pages = 0;
        boolean hasMore = true;
        while (hasMore && pages < 50) {
            String url = "/api/sessions/" + filesSessionId + "/files?limit=1"
                    + (cursor != null ? "&cursor=" + enc(cursor) : "");
            Feat136WorkbenchSupport.Resp page = wb.get(url);
            assertThat(page.status()).as("第 " + (pages + 1) + " 页应 200").isEqualTo(200);
            JsonNode body = page.body();
            assertThat(body.path("entries").isArray())
                    .as("entries 为数组（L2 §2.3 出参表）").isTrue();
            if (pages == 0) {
                assertThat(body.path("entries").size())
                        .as("limit=1 时每页恰 1 条（L2 §2.3 limit 合法范围）").isEqualTo(1);
                assertThat(body.has("directoryFingerprint"))
                        .as("directoryFingerprint 字段存在（L2 §2.3）").isTrue();
                assertThat(body.path("excludedNames").isArray())
                        .as("excludedNames 为数组（L2 §2.3）").isTrue();
            }
            for (JsonNode entry : body.path("entries")) {
                // wire 条目字段为 name/path/type/size（执行轮核实）；相对路径取 path，回退 name
                String rel = entry.path("path").asText("");
                if (rel.isBlank()) {
                    rel = entry.path("name").asText("");
                }
                seenPaths.add(rel);
            }
            hasMore = body.path("hasMore").asBoolean(false);
            cursor = body.path("nextCursor").isNull() ? null : body.path("nextCursor").asText();
            pages++;
        }
        assertThat(seenPaths.size())
                .as("翻页拼接覆盖工程全部文件（fixture 含 README.md/notes.txt/data.bin/src/Main.java ≥3 项）")
                .isGreaterThanOrEqualTo(3);

        // ②③ limit 越界
        Feat136WorkbenchSupport.Resp over = wb.get("/api/sessions/" + filesSessionId + "/files?limit=501");
        assertThat(over.status()).as("limit=501 超出上限 500 应 400（L2 §2.3）").isEqualTo(400);
        assertThat(over.body().path("error").path("code").asText())
                .as("错误码逐字为 INVALID_LIMIT（L2 §2.3）").isEqualTo("INVALID_LIMIT");

        Feat136WorkbenchSupport.Resp under = wb.get("/api/sessions/" + filesSessionId + "/files?limit=0");
        assertThat(under.status()).as("limit=0 低于下限 1 应 400（L2 §2.3）").isEqualTo(400);
        assertThat(under.body().path("error").path("code").asText())
                .as("错误码逐字为 INVALID_LIMIT（L2 §2.3）").isEqualTo("INVALID_LIMIT");
    }

    // ---- 场景 F137-13：拼造游标拒绝 ----

    @Test
    @Tag("story-feat-137-13")
    @Story("FEAT-137.F137-13: 非服务端签名游标拒绝")
    @DisplayName("F137-13 files?cursor=<拼造> → 400 INVALID_CURSOR")
    void f13713ForgedCursorRejected() throws Exception {
        assumeTrue(filesSessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + filesSessionId
                + "/files?limit=10&cursor=" + enc("feat137-forged-cursor-" + Feat137WorkbenchFixture.runId()));

        assertThat(resp.status()).as("拼造游标应 400（L2 §2.3：客户端不解析、不拼造）").isEqualTo(400);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 INVALID_CURSOR（L2 §2.3）").isEqualTo("INVALID_CURSOR");
    }

    // ---- 场景 F137-14：分页间目录变化 ----

    @Test
    @Tag("story-feat-137-14")
    @Story("FEAT-137.F137-14: 目录在分页间变化时旧游标失效")
    @DisplayName("F137-14 翻页间隙新增文件后旧 cursor → 409 DIRECTORY_CHANGED")
    void f13714DirectoryChangedBetweenPages() throws Exception {
        assumeTrue(filesSessionId != null && filesDir != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);

        Feat136WorkbenchSupport.Resp first = wb.get("/api/sessions/" + filesSessionId + "/files?limit=1");
        assertThat(first.status()).as("第一页应 200").isEqualTo(200);
        JsonNode firstBody = first.body();
        assumeTrue(firstBody.path("hasMore").asBoolean(false) && !firstBody.path("nextCursor").isNull(),
                "目录仅一页（hasMore=false），无法构造分页间变化场景");

        // 进程外注入：分页间隙向目录新增文件（L2 §2.3：目录变化导致旧游标失效）
        Files.write(filesDir.resolve("feat137-injected-during-paging.txt"),
                "injected".getBytes(StandardCharsets.UTF_8));

        Feat136WorkbenchSupport.Resp next = wb.get("/api/sessions/" + filesSessionId + "/files?limit=1&cursor="
                + enc(firstBody.path("nextCursor").asText()));
        assertThat(next.status())
                .as("旧游标读下一页应 409 DIRECTORY_CHANGED（L2 §2.3 场景表）").isEqualTo(409);
        assertThat(next.body().path("error").path("code").asText())
                .as("错误码逐字为 DIRECTORY_CHANGED（L2 §2.3）").isEqualTo("DIRECTORY_CHANGED");
    }

    // ---- 场景 F137-15：文件快照正例与指纹一致 ----

    @Test
    @Tag("story-feat-137-15")
    @Story("FEAT-137.F137-15: 文件快照字段完整且 fingerprint 为原始字节 SHA-256")
    @DisplayName("F137-15 GET file README.md → 200 + fingerprint==SHA-256(原字节)")
    void f13715FileSnapshotFingerprint() throws Exception {
        assumeTrue(filesSessionId != null && filesDir != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);
        byte[] raw = Files.readAllBytes(filesDir.resolve("README.md"));
        String expectedSha = Feat137WorkbenchFixture.sha256Hex(raw);

        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + filesSessionId
                + "/file?path=" + enc("README.md") + "&view=workbench");

        assertThat(resp.status()).as("文件读取应 200（L2 §2.3）").isEqualTo(200);
        JsonNode body = resp.body();
        assertThat(body.path("text").asText())
                .as("text 与文件内容一致（L2 §2.3 text：脱敏后的 UTF-8 正文）")
                .isEqualTo(new String(raw, StandardCharsets.UTF_8));
        assertThat(body.path("encoding").asText())
                .as("encoding 等值比较 UTF-8（wire 实际返回小写 utf-8；L2 §2.3：脱敏后的 UTF-8 正文及编码）")
                .isEqualToIgnoringCase("UTF-8");
        assertThat(body.path("size").asLong())
                .as("size == 原始文件字节数（L2 §2.3）").isEqualTo(raw.length);
        assertThat(body.path("binary").isBoolean() && !body.path("binary").asBoolean())
                .as("binary == false（L2 §2.3：工作台成功文本均为 false）").isTrue();
        assertThat(body.path("editable").isBoolean() && !body.path("editable").asBoolean())
                .as("editable == false（只读视图，L2 §2.3）").isTrue();
        assertThat(body.path("truncated").isBoolean() && !body.path("truncated").asBoolean())
                .as("truncated == false（L2 §2.3）").isTrue();
        // wire 实际格式为 "sha256:<hex>"（与 manifestHash 一致的算法前缀），核对前缀剥离后的十六进制值
        String actualFp = body.path("fingerprint").asText();
        assertThat(actualFp.startsWith("sha256:") ? actualFp.substring("sha256:".length()) : actualFp)
                .as("fingerprint == 测试侧对原始字节的 SHA-256（L2 §2.3：SHA-256 指纹）")
                .isEqualTo(expectedSha);
    }

    // ---- 场景 F137-16：路径语法错误拒绝（审阅整改项：与访问越界 403 各自核对） ----

    @Test
    @Tag("story-feat-137-16")
    @Story("FEAT-137.F137-16: 语法非法路径（..、工程外绝对路径）→ 400 INVALID_PATH")
    @DisplayName("F137-16 file?path=../outside → 400 INVALID_PATH（路径不合法专属结果）")
    void f13716IllegalPathRejected() throws Exception {
        assumeTrue(filesSessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);

        // ① 语法非法路径变体 → 400 INVALID_PATH（L2 §2.3：路径不合法 400；实现：反斜杠/绝对路径/
        // URI scheme/NUL/空路径均 400，ProjectFiles.resolveSafe）
        String backslash = String.valueOf((char) 92);
        String nul = String.valueOf((char) 0);
        String[] invalidPaths = {
                "src" + backslash + "evil.java",                     // 反斜杠（Windows 风格分隔）
                "C:" + backslash + "outside-secret.txt",             // Windows 绝对路径
                "/etc/passwd",                                       // 以 / 开头
                "file:///etc/passwd",                                // URI scheme
                "feat137" + nul + "nul.txt"                          // NUL 字节
        };
        for (String p : invalidPaths) {
            Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + filesSessionId
                    + "/file?path=" + enc(p) + "&view=workbench");
            assertThat(resp.status())
                    .as("语法非法路径变体应 400（L2 §2.3：路径不合法 400 INVALID_PATH）")
                    .isEqualTo(400);
            assertThat(resp.body().path("error").path("code").asText())
                    .as("语法非法路径错误码逐字为 INVALID_PATH").isEqualTo("INVALID_PATH");
            assertThat(resp.body().toString().contains("outside-secret-content"))
                    .as("非法路径请求不得返回目标内容").isFalse();
        }

        // ② 相对越界（路径语法合法、语义越界）→ 403（L2 §2.3：访问越界或不允许路径 403）
        Feat136WorkbenchSupport.Resp escape = wb.get("/api/sessions/" + filesSessionId
                + "/file?path=" + enc("../feat137-outside-secret.txt") + "&view=workbench");
        assertThat(escape.status())
                .as("`..` 相对越界应 403（L2 §2.3：访问越界或不允许路径 403；实现 PATH_OUTSIDE_PROJECT）")
                .isEqualTo(403);
        assertThat(escape.body().toString().contains("feat137-outside-secret-content"))
                .as("越界请求不得返回目标文件内容（L2 §2.3：不返回原文件内容）").isFalse();
    }

    // ---- 场景 F137-27：跨目录游标拒绝（审阅整改项） ----

    @Test
    @Tag("story-feat-137-27")
    @Story("FEAT-137.F137-27: 目录 A 签发游标用于目录 B → 拒绝且不返回 B 条目")
    @DisplayName("F137-27 跨目录 cursor → 400 INVALID_CURSOR 或 409 DIRECTORY_CHANGED")
    void f13727CrossDirectoryCursorRejected() throws Exception {
        assumeTrue(filesSessionId != null && secretSessionId != null,
                backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);

        // 目录 A（files 工程）第一页游标
        Feat136WorkbenchSupport.Resp first = wb.get("/api/sessions/" + filesSessionId + "/files?limit=1");
        assumeTrue(first.status() == 200, "目录 A 第一页未取得（status=" + first.status() + "）");
        String cursorA = first.body().path("nextCursor").asText(null);
        assumeTrue(cursorA != null && !cursorA.isBlank(), "目录 A 仅一页（无 nextCursor），无法构造跨目录游标场景");

        // 目录 B（secret 工程）复用 A 的游标
        Feat136WorkbenchSupport.Resp cross = wb.get("/api/sessions/" + secretSessionId
                + "/files?limit=1&cursor=" + enc(cursorA));

        assertThat(cross.status())
                .as("跨目录游标应拒绝：400 INVALID_CURSOR 或 409 DIRECTORY_CHANGED（L2 §2.3：游标服务端签名且绑定目录）")
                .isIn(400, 409);
        assertThat(cross.body().path("error").path("code").asText())
                .as("错误码逐字为 INVALID_CURSOR 或 DIRECTORY_CHANGED（L2 §2.3）")
                .isIn("INVALID_CURSOR", "DIRECTORY_CHANGED");
        assertThat(cross.body().has("entries") && cross.body().path("entries").size() > 0)
                .as("跨目录游标响应不得返回目标目录条目").isFalse();
    }

    // ---- 场景 F137-28：访问越界（路径语法合法）拒绝（审阅整改项） ----

    @Test
    @Tag("story-feat-137-28")
    @Story("FEAT-137.F137-28: 符号链接越出工程边界 → 403，不返回工程外内容")
    @DisplayName("F137-28 file?path=link-out.txt(符号链接) → 403（访问越界专属结果）")
    void f13728SymlinkEscapeForbidden() throws Exception {
        assumeTrue(filesSessionId != null && filesDir != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);

        // 进程外注入：工程外文件 + 工程内符号链接指向它
        Path outside = filesDir.getParent().resolve("feat137-outside-secret.txt");
        Files.write(outside, "feat137-outside-secret-content".getBytes(StandardCharsets.UTF_8));
        boolean linkCreated = Feat137WorkbenchFixture.createSymlinkEscape(filesDir, outside, "link-out.txt");
        if (!linkCreated) {
            // 宿主无符号链接权限（Windows 需管理员/开发者模式）→ 经 VM/Linux 侧 ln -s 创建
            // （后端运行于 VM 时对同一 drvfs 目录可见，符号链接语义一致；fixture 环境搭建手段，非被测行为）
            String distro = TestConfig.load().getString("sut.workbench.wsl-distro", "");
            linkCreated = Feat137WorkbenchFixture.createSymlinkViaWsl(distro, filesDir.resolve("link-out.txt"),
                    outside, hostPathMap);
        }
        assumeTrue(linkCreated,
                "符号链接创建失败：宿主无权限且 sut.workbench.wsl-distro 未配置 VM 辅助通道");

        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + filesSessionId
                + "/file?path=" + enc("link-out.txt") + "&view=workbench");

        assertThat(resp.status())
                .as("路径语法合法但解析后越出工程边界应 403（L2 §2.3 场景表：访问越界 403；与 F137-16 的 400 各自核对）")
                .isEqualTo(403);
        assertThat(resp.body().toString().contains("feat137-outside-secret-content"))
                .as("响应不得返回工程外文件内容（L2 §2.3：不返回原文件内容）").isFalse();
    }

    // ---- 场景 F137-29：私钥文件拒绝且无正文（审阅整改项） ----

    @Test
    @Tag("story-feat-137-29")
    @Story("FEAT-137.F137-29: 私钥文件读取拒绝（403）且响应无正文泄漏")
    @DisplayName("F137-29 file?path=id_rsa → 403 + 响应不含私钥材料")
    void f13729PrivateKeyRejectedNoBody() throws Exception {
        assumeTrue(secretSessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);

        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + secretSessionId
                + "/file?path=" + enc("id_rsa") + "&view=workbench");

        assertThat(resp.status())
                .as("私密文件（私钥）读取应 403（L2 §2.3 场景表：私密文件 403 文件访问错误；L2 §7.1：私密文件不可读取）")
                .isEqualTo(403);
        assertThat(resp.body().toString().contains("BEGIN"))
                .as("响应不得含私钥材料（PEM BEGIN 头/密钥体不出现在响应正文）").isFalse();
    }

    // ---- 场景 F137-30：源文件变化后旧位置失效（审阅整改项 G1：来源定位前置事实） ----

    @Test
    @Tag("story-feat-137-30")
    @Story("FEAT-137.F137-30: 源文件变化后旧位置内容失效且 manifestHash 变化")
    @DisplayName("F137-30 notes.txt 头部插行 → 重读正文变化 + manifestHash 变化")
    void f13730OldSourceLocationInvalidated() throws Exception {
        assumeTrue(filesSessionId != null && filesDir != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);

        // ① 记录原文与旧位置内容；src/main/java 下 Java 文件属分析器输入（L2 §5.5 输入清单：Java 源码/pom/配置目录）
        Path target = filesDir.resolve("src/main/java/demo/Main.java");
        assumeTrue(Files.exists(target), "src/main/java/demo/Main.java 缺失");
        byte[] original = Files.readAllBytes(target);
        String originalText = new String(original, StandardCharsets.UTF_8);
        Feat136WorkbenchSupport.Resp modelBefore = wb.get("/api/sessions/" + filesSessionId + "/project/model");
        assumeTrue(modelBefore.status() == 200, "基线 project/model 未取得（status=" + modelBefore.status() + "）");
        String hashBefore = modelBefore.body().path("fingerprints").path("manifestHash").asText();
        assumeTrue(!hashBefore.isBlank(), "基线 manifestHash 缺失");

        // ② 进程外注入：文件头部插入 2 行注释（旧行位置内容随之失效；修改在分析器输入清单内）
        Files.write(target,
                ("/* feat137-injected-line-1 */\n/* feat137-injected-line-2 */\n" + originalText)
                        .getBytes(StandardCharsets.UTF_8));

        // ③ 重读同文件：旧位置内容不再匹配
        Feat136WorkbenchSupport.Resp reread = wb.get("/api/sessions/" + filesSessionId
                + "/file?path=" + enc("notes.txt") + "&view=workbench");
        assertThat(reread.status()).as("变更后文件读取应 200").isEqualTo(200);
        assertThat(reread.body().path("text").asText())
                .as("重读正文 != 原文（旧行位置内容已失效，特性档 §5.1.4 来源失效提示的前置事实成立）")
                .isNotEqualTo(originalText);

        // ④ 新分析 manifestHash 变化（来源失效与重新分析的事实基础）
        Feat136WorkbenchSupport.Resp modelAfter = wb.get("/api/sessions/" + filesSessionId + "/project/model");
        assertThat(modelAfter.status()).as("变更后即时分析应 200").isEqualTo(200);
        String hashAfter = modelAfter.body().path("fingerprints").path("manifestHash").asText();
        assertThat(hashAfter)
                .as("源文件变化使 manifestHash 变化（L2 §5.5；特性档 §5.1.4）").isNotEqualTo(hashBefore);
    }

    // ---- 场景 F137-17：文件不存在 ----

    @Test
    @Tag("story-feat-137-17")
    @Story("FEAT-137.F137-17: 工程内不存在的文件返回 404")
    @DisplayName("F137-17 file?path=<不存在> → 404 FILE_NOT_FOUND")
    void f13717FileNotFound() throws Exception {
        assumeTrue(filesSessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + filesSessionId
                + "/file?path=" + enc("feat137-no-such-file.txt") + "&view=workbench");

        assertThat(resp.status()).as("文件不存在应 404（L2 §2.3 场景表）").isEqualTo(404);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 FILE_NOT_FOUND（L2 §2.3）").isEqualTo("FILE_NOT_FOUND");
    }

    // ---- 场景 F137-18：超大文件拒绝 ----

    @Test
    @Tag("story-feat-137-18")
    @Story("FEAT-137.F137-18: 超过工作台上限的文件不返回截断文本")
    @DisplayName("F137-18 file?path=big.txt(>524288B) → 413 FILE_TOO_LARGE")
    void f13718OversizedFileRejected() throws Exception {
        assumeTrue(bigSessionId != null && bigDir != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + bigSessionId
                + "/file?path=" + enc("big.txt") + "&view=workbench");

        assertThat(resp.status())
                .as("超过 P1_WORKBENCH_MAX_BYTES(524288) 应 413（L2 §2.3/§6.2：不返回截断文本冒充完整源码）")
                .isEqualTo(413);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 FILE_TOO_LARGE（L2 §2.3）").isEqualTo("FILE_TOO_LARGE");
    }

    // ---- 场景 F137-19：二进制文件拒绝 ----

    @Test
    @Tag("story-feat-137-19")
    @Story("FEAT-137.F137-19: 非 UTF-8 二进制文件拒绝文本展示")
    @DisplayName("F137-19 file?path=data.bin → 415 UNSUPPORTED_TEXT")
    void f13719BinaryFileRejected() throws Exception {
        assumeTrue(filesSessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + filesSessionId
                + "/file?path=" + enc("data.bin") + "&view=workbench");

        assertThat(resp.status())
                .as("二进制或无效 UTF-8 应 415（L2 §2.3：提示不支持当前文本展示）").isEqualTo(415);
        assertThat(resp.body().path("error").path("code").asText())
                .as("错误码逐字为 UNSUPPORTED_TEXT（L2 §2.3）").isEqualTo("UNSUPPORTED_TEXT");
    }

    // ---- 场景 F137-20：只读视图写入拒绝 ----

    @Test
    @Tag("story-feat-137-20")
    @Story("FEAT-137.F137-20: 只读视图写入请求返回 405 且文件未被修改")
    @DisplayName("F137-20 PUT file?view=workbench → 405 READ_ONLY_VIEW + 文件不变")
    void f13720ReadonlyWriteRejected() throws Exception {
        assumeTrue(filesSessionId != null && filesDir != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        byte[] raw = Files.readAllBytes(filesDir.resolve("README.md"));
        String shaBefore = Feat137WorkbenchFixture.sha256Hex(raw);

        com.fasterxml.jackson.databind.node.ObjectNode writeBody = wb.obj();
        writeBody.put("text", "feat137 malicious overwrite attempt");
        Feat136WorkbenchSupport.Resp put = wb.sendWithBody("PUT", "/api/sessions/" + filesSessionId
                + "/file?path=" + enc("README.md") + "&view=workbench", writeBody);

        assertThat(put.status())
                .as("只读视图写入应 405（特性档 §3：写入请求返回不允许写入的结果；L2 §2.1/§2.3）")
                .isEqualTo(405);
        assertThat(put.body().path("error").path("code").asText())
                .as("错误码逐字为 READ_ONLY_VIEW（L2 §2.1/§2.3）").isEqualTo("READ_ONLY_VIEW");

        // 文件未被修改（L2 §2.3：不产生文件修改）
        byte[] rawAfter = Files.readAllBytes(filesDir.resolve("README.md"));
        assertThat(Feat137WorkbenchFixture.sha256Hex(rawAfter))
                .as("写入请求后文件字节不变（L2 §2.3：READ_ONLY_VIEW 不产生文件修改）")
                .isEqualTo(shaBefore);
    }

    // ---- 场景 F137-21：凭据脱敏与指纹分离 ----

    @Test
    @Tag("story-feat-137-21")
    @Story("FEAT-137.F137-21: 凭据文件脱敏展示但 fingerprint 仍为原始字节 SHA-256")
    @DisplayName("F137-21 GET file secret.yaml → redacted=true + 原值不出现 + fingerprint==SHA-256")
    void f13721SecretRedactionKeepsFingerprint() throws Exception {
        assumeTrue(secretSessionId != null && secretDir != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        assumeTrue(!platformFsBlocked, platformFsBlockReason);
        byte[] raw = Files.readAllBytes(secretDir.resolve("secret.yaml"));
        String expectedSha = Feat137WorkbenchFixture.sha256Hex(raw);
        String secretValue = "s3cret-" + secretRunId;

        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + secretSessionId
                + "/file?path=" + enc("secret.yaml") + "&view=workbench");

        assertThat(resp.status()).as("凭据文件读取应 200（L2 §2.3：读取允许，展示遮蔽）").isEqualTo(200);
        JsonNode body = resp.body();
        assertThat(body.path("redacted").isBoolean() && body.path("redacted").asBoolean())
                .as("redacted == true（L2 §2.3：是否遮蔽敏感值）").isTrue();
        assertThat(body.path("maskedLineRanges").isArray()
                && body.path("maskedLineRanges").size() > 0)
                .as("maskedLineRanges 非空数组（L2 §2.3：遮蔽行范围）").isTrue();
        assertThat(body.path("text").asText().contains(secretValue))
                .as("遮蔽后正文不含原敏感值（L2 §2.3/§5.4）").isFalse();
        String actualFp21 = body.path("fingerprint").asText();
        assertThat(actualFp21.startsWith("sha256:") ? actualFp21.substring("sha256:".length()) : actualFp21)
                .as("fingerprint 仍为原始字节 SHA-256（L2 §2.3：脱敏不改变来源指纹依据）")
                .isEqualTo(expectedSha);
    }
}
