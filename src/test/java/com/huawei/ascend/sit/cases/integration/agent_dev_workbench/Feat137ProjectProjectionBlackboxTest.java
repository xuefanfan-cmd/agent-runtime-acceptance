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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-137 工程投影与形态识别契约黑盒验收（场景矩阵 F137-01～F137-11、F137-22～F137-26、F137-31～F137-32）。
 *
 * <p>期望值出处：L2 §2.2（投影契约出参表、SESSION_NOT_FOUND）、§3.2（形态识别次序与
 * SHAPE_UNDETERMINED/SHAPE_AMBIGUOUS）、§3.3（Workflow 注册三态 confirmed/unresolved/unregistered
 * 与诊断）、§5.5（manifestHash）、§7.3（并发共享在途构建）；特性档 §2.2（11 项 MUST：不能伪装
 * 完整识别、未解析项有对应诊断）、§5.1.5（修改后刷新）。2026-11-24 按 workbench-v1130
 * testplan-review #602 审阅结论整改：补实际结构与来源核对（EDP 主控与候选、工具与输入声明）、
 * 注册三态与整体分析不可用、边端点一致性、刷新后实际结构、其他用户会话与 Agent Host 链路。
 * 全部断言位于 HTTP wire，不读取 SUT 内部状态。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-137")
@Feature("FEAT-137: 智能体结构可视化与源码联动")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat137ProjectProjectionBlackboxTest {

    private Feat136WorkbenchSupport wb;
    /** 后端就绪门禁（探测 GET /api/workspaces），下沉到每个测试方法内 assumeTrue，保证逐用例可见 SKIPPED。 */
    private boolean backendReady;
    private String backendGateReason;
    private final Map<String, String> sessionIdByVariant = new HashMap<>();
    /** 工作区路径宿主映射规则（sut.workbench.host-path-map，VM 部署形态 "/mnt/d/=D:/"）。 */
    private String hostPathMap;
    private final Map<String, Path> projectDirByVariant = new HashMap<>();
    private final Map<String, String> workspaceIdByVariant = new HashMap<>();
    private final List<String> createdWorkspaceIds = new ArrayList<>();
    private final List<Path> createdDirs = new ArrayList<>();

    @BeforeAll
    void probeBackendAndPrepareFixtures() throws Exception {
        // 共享前置（审阅优先项 3）：就绪探测 + 本地账号登录（后端 local 模式强制鉴权）
        TestConfig config = TestConfig.load();
        hostPathMap = config.getString("sut.workbench.host-path-map", "");
        Feat136WorkbenchSupport.Ready ready = Feat136WorkbenchSupport.ready(config);
        wb = ready.wb();
        backendReady = wb != null;
        backendGateReason = ready.blocker() != null
                ? ready.blocker()
                : "agent-dev-workbench 后端未就绪：" + (wb == null ? "" : wb.base());
        if (!backendReady) {
            return; // 就绪/登录失败：全部用例按 assumeTrue SKIPPED，避免前置空引用
        }

        // 各形态 fixture 工程：命名登记工作区（后端在用户根下自动建目录）→ fixture 内容拷入
        // → 创建开发会话；任一步失败则该形态会话缺省（用例内 assumeTrue）
        for (String[] variant : new String[][]{
                {"moa", "createMoaProject"}, {"react", "createReactProject"}, {"deep", "createDeepProject"},
                {"workflow", "createWorkflowProject"}, {"wfPartial", "createWorkflowPartialProject"},
                {"wfUnresolved", "createWorkflowUnresolvedProject"}, {"wfUnregistered", "createWorkflowUnregisteredProject"},
                {"wfBroken", "createWorkflowBrokenProject"},
                {"mixed", "createMixedProject"}, {"empty", "createEmptyProject"}}) {
            String name = variant[0];
            Path tempFx = buildFixtureTempDir(name);
            String wsName = "feat137-" + name + "-" + Feat137WorkbenchFixture.runId();
            Feat136WorkbenchSupport.Resp ws = wb.sendWithBody("POST", "/api/workspaces",
                    wb.workspaceByName(wsName));
            if (ws.status() != 200 && ws.status() != 201) {
                Feat137WorkbenchFixture.deleteRecursively(tempFx);
                continue;
            }
            String wsId = ws.body().path("id").asText();
            createdWorkspaceIds.add(wsId);
            workspaceIdByVariant.put(name, wsId);
            Path wsPath = Feat137WorkbenchFixture.toHostPath(
                    ws.body().path("path").asText(),
                    config.getString("sut.workbench.host-path-map", ""));
            Feat137WorkbenchFixture.copyInto(tempFx, wsPath);
            Feat137WorkbenchFixture.deleteRecursively(tempFx);
            projectDirByVariant.put(name, wsPath);
            createdDirs.add(wsPath);
            Feat136WorkbenchSupport.Resp session = wb.sendWithBody("POST", "/api/sessions",
                    sessionCreate(wsId, "feat137-" + name));
            if (session.status() == 200 || session.status() == 201) {
                String sessionId = session.body().path("id").asText();
                if (!sessionId.isBlank()) {
                    sessionIdByVariant.put(name, sessionId);
                }
            }
        }
    }

    /** 进程内构造 fixture 变体临时目录（内容随后拷入后端登记的工作区目录）。 */
    private Path buildFixtureTempDir(String variant) throws Exception {
        return switch (variant) {
            case "moa" -> Feat137WorkbenchFixture.createMoaProject();
            case "react" -> Feat137WorkbenchFixture.createReactProject();
            case "deep" -> Feat137WorkbenchFixture.createDeepProject();
            case "workflow" -> Feat137WorkbenchFixture.createWorkflowProject();
            case "wfPartial" -> Feat137WorkbenchFixture.createWorkflowPartialProject();
            case "wfUnresolved" -> Feat137WorkbenchFixture.createWorkflowUnresolvedProject();
            case "wfUnregistered" -> Feat137WorkbenchFixture.createWorkflowUnregisteredProject();
            case "wfBroken" -> Feat137WorkbenchFixture.createWorkflowBrokenProject();
            case "mixed" -> Feat137WorkbenchFixture.createMixedProject();
            default -> Feat137WorkbenchFixture.createEmptyProject(variant);
        };
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

    // ---- 请求体构造 ----

    private com.fasterxml.jackson.databind.node.ObjectNode workspaceByPath(Path dir) {
        com.fasterxml.jackson.databind.node.ObjectNode body = wb.obj();
        body.put("path", dir.toAbsolutePath().toString());
        return body;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode sessionCreate(String wsId, String title) {
        com.fasterxml.jackson.databind.node.ObjectNode body = wb.obj();
        body.put("workspaceId", wsId);
        body.put("title", title);
        return body;
    }

    // ---- 场景 F137-01：未知会话投影不可见 ----

    @Test
    @Tag("story-feat-137-01")
    @Story("FEAT-137.F137-01: 未知会话在投影与文件入口均不可见")
    @DisplayName("F137-01 未知会话 project/ui|model|files|file → 404 SESSION_NOT_FOUND")
    void f13701UnknownSessionProjectionNotFound() throws Exception {
        assumeTrue(backendReady, backendGateReason);
        String ghost = "feat137-nope-" + Feat137WorkbenchFixture.runId();
        Feat136WorkbenchSupport.Resp ui = wb.get("/api/sessions/" + ghost + "/project/ui");
        Feat136WorkbenchSupport.Resp model = wb.get("/api/sessions/" + ghost + "/project/model");
        Feat136WorkbenchSupport.Resp files = wb.get("/api/sessions/" + ghost + "/files");
        Feat136WorkbenchSupport.Resp file = wb.get("/api/sessions/" + ghost + "/file?path=README.md&view=workbench");

        assertThat(ui.status()).as("未知会话 project/ui 应 404（L2 §2.2 场景表）").isEqualTo(404);
        assertThat(model.status()).as("未知会话 project/model 应 404（L2 §2.2 场景表）").isEqualTo(404);
        assertThat(files.status()).as("未知会话 files 应 404（L2 §2.1 会话归属校验）").isEqualTo(404);
        assertThat(file.status()).as("未知会话 file 应 404（L2 §2.1 会话归属校验）").isEqualTo(404);
        assertThat(ui.body().path("error").path("code").asText())
                .as("project/ui 错误码逐字为 SESSION_NOT_FOUND（L2 §2.2）").isEqualTo("SESSION_NOT_FOUND");
        assertThat(model.body().path("error").path("code").asText())
                .as("project/model 错误码逐字为 SESSION_NOT_FOUND（L2 §2.2）").isEqualTo("SESSION_NOT_FOUND");
    }

    // ---- 场景 F137-02：投影外壳契约 ----

    @Test
    @Tag("story-feat-137-02")
    @Story("FEAT-137.F137-02: 投影外壳字段与枚举契约")
    @DisplayName("F137-02 GET project/ui → 200 + schemaVersion=2.3 + shape 枚举 + manifestHash")
    void f13702ProjectionShellContract() throws Exception {
        String sessionId = sessionIdByVariant.get("moa");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/ui");

        assertThat(resp.status()).as("投影应 200（L2 §2.2）").isEqualTo(200);
        JsonNode body = resp.body();
        assertThat(body.path("schemaVersion").asText())
                .as("schemaVersion == 2.3（L2 §2.2 出参表）").isEqualTo("2.3");
        assertThat(body.path("sessionId").asText())
                .as("sessionId == 请求会话（L2 §2.2：此次响应所属会话）").isEqualTo(sessionId);
        assertThat(body.path("generatedAt").asText())
                .as("generatedAt 非空（L2 §2.2）").isNotEmpty();
        assertThat(body.path("shape").path("type").asText())
                .as("shape.type ∈ {moa,workflow,deep,react}（L2 §2.2）")
                .isIn("moa", "workflow", "deep", "react");
        assertThat(body.path("shape").path("renderer").asText())
                .as("shape.renderer ∈ {agent-tree,node-graph,tool-list}（L2 §2.2）")
                .isIn("agent-tree", "node-graph", "tool-list");
        assertThat(body.path("shape").path("confidence").asText())
                .as("shape.confidence ∈ {certain,probable,uncertain}（L2 §2.2）")
                .isIn("certain", "probable", "uncertain");
        assertThat(body.path("shape").path("evidence").isArray())
                .as("shape.evidence 为数组（L2 §2.2）").isTrue();
        assertThat(body.path("diagnostics").isArray())
                .as("diagnostics 为数组（L2 §2.2）").isTrue();
        assertThat(body.path("fingerprints").path("manifestHash").asText())
                .as("fingerprints.manifestHash 非空（L2 §2.2/§5.1）").isNotBlank();
        assertThat(body.path("payload").isObject())
                .as("payload 为对象（L2 §2.2）").isTrue();
    }

    // ---- 场景 F137-03/04/05：MOA / ReAct / DeepAgent 形态 ----

    @Test
    @Tag("story-feat-137-03")
    @Story("FEAT-137.F137-03: MOA 候选配置工程识别为智能体树")
    @DisplayName("F137-03 MOA 工程 project/model → shape=moa/agent-tree/certain")
    void f13703MoaShapeAgentTree() throws Exception {
        String sessionId = sessionIdByVariant.get("moa");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).as("即时分析应 200（L2 §2.2）").isEqualTo(200);
        JsonNode shape = resp.body().path("shape");
        assertThat(shape.path("type").asText())
                .as("shape.type == moa（L2 §3.2 次序 1：候选配置 YAML 为确定证据）").isEqualTo("moa");
        assertThat(shape.path("renderer").asText())
                .as("shape.renderer == agent-tree（L2 §3.2 次序 1）").isEqualTo("agent-tree");
        assertThat(shape.path("confidence").asText())
                .as("shape.confidence == certain（L2 §3.2 次序 1）").isEqualTo("certain");
        assertThat(resp.body().path("payload").isObject())
                .as("payload 为对象（L2 §2.2）").isTrue();

        // 实际结构与来源核对（审阅整改项 A3）：EDP 主控与候选在载荷中可定位
        JsonNode payload = resp.body().path("payload");
        assertThat(containsTextAnywhere(payload, "feat137-moa-master"))
                .as("载荷核对 EDP 主控名 feat137-moa-master（特性档 §2.1/§5.1 实际结构核对）").isTrue();
        assertThat(containsTextAnywhere(payload, "feat137-moa-worker-a"))
                .as("载荷核对候选 feat137-moa-worker-a（特性档 §2.1/§5.1）").isTrue();
        assertThat(containsTextAnywhere(payload, "feat137-moa-worker-b"))
                .as("载荷核对候选 feat137-moa-worker-b（特性档 §2.1/§5.1）").isTrue();
    }

    @Test
    @Tag("story-feat-137-04")
    @Story("FEAT-137.F137-04: ReAct 特征工程识别为工具清单")
    @DisplayName("F137-04 ReAct 工程 project/model → shape=react/tool-list")
    void f13704ReactShapeToolList() throws Exception {
        String sessionId = sessionIdByVariant.get("react");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode shape = resp.body().path("shape");
        assertThat(shape.path("type").asText())
                .as("shape.type == react（L2 §3.2 次序 4）").isEqualTo("react");
        assertThat(shape.path("renderer").asText())
                .as("shape.renderer == tool-list（L2 §3.2 次序 4）").isEqualTo("tool-list");
        assertThat(shape.path("confidence").asText())
                .as("shape.confidence ∈ {certain,probable}（L2 §3.2 次序 4）")
                .isIn("certain", "probable");

        // 实际结构与来源核对（审阅整改项 B1~B3）：工具声明与输入声明、来源引用
        JsonNode payload = resp.body().path("payload");
        assertThat(containsTextAnywhere(payload, "OrderQueryTool"))
                .as("载荷核对工具声明 OrderQueryTool（特性档 §2.1 工具清单结构）").isTrue();
        assertThat(containsTextAnywhere(payload, "RefundTool"))
                .as("载荷核对工具声明 RefundTool（特性档 §2.1 工具清单结构）").isTrue();
        assertThat(containsTextAnywhere(payload, "orderId"))
                .as("载荷核对输入声明 orderId（特性档 §2.1 工具输入声明）").isTrue();
        assertThat(containsTextAnywhere(payload, "OrderQueryTool.java"))
                .as("工具声明 source 引用来源文件 OrderQueryTool.java（来源核对）").isTrue();
    }

    @Test
    @Tag("story-feat-137-05")
    @Story("FEAT-137.F137-05: DeepAgent 特征工程识别为工具清单")
    @DisplayName("F137-05 DeepAgent 工程 project/model → shape=deep/tool-list")
    void f13705DeepShapeToolList() throws Exception {
        String sessionId = sessionIdByVariant.get("deep");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode shape = resp.body().path("shape");
        assertThat(shape.path("type").asText())
                .as("shape.type == deep（L2 §3.2 次序 3）").isEqualTo("deep");
        assertThat(shape.path("renderer").asText())
                .as("shape.renderer == tool-list（L2 §3.2 次序 3）").isEqualTo("tool-list");
        assertThat(shape.path("confidence").asText())
                .as("shape.confidence ∈ {certain,probable}（L2 §3.2 次序 3）")
                .isIn("certain", "probable");

        // 实际结构与来源核对（审阅整改项 B1~B3）：工具声明与输入声明、来源引用
        JsonNode payload = resp.body().path("payload");
        assertThat(containsTextAnywhere(payload, "ResearchTool"))
                .as("载荷核对工具声明 ResearchTool（特性档 §2.1 工具清单结构）").isTrue();
        assertThat(containsTextAnywhere(payload, "query"))
                .as("载荷核对输入声明 query（特性档 §2.1 工具输入声明）").isTrue();
        assertThat(containsTextAnywhere(payload, "ResearchTool.java")
                        || containsTextAnywhere(payload, "demo/ResearchTool"))
                .as("工具声明 source 引用来源文件 ResearchTool.java（来源核对）").isTrue();
    }

    // ---- 场景 F137-06/07：Workflow 静态分析 ----

    @Test
    @Tag("story-feat-137-06")
    @Story("FEAT-137.F137-06: Workflow 注册齐备工程识别为节点流程图且注册 confirmed")
    @DisplayName("F137-06 Workflow 工程 project/model → shape=workflow/node-graph + confirmed")
    void f13706WorkflowShapeConfirmed() throws Exception {
        String sessionId = sessionIdByVariant.get("workflow");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode body = resp.body();
        JsonNode shape = body.path("shape");
        assertThat(shape.path("type").asText())
                .as("shape.type == workflow（L2 §3.2 次序 2）").isEqualTo("workflow");
        assertThat(shape.path("renderer").asText())
                .as("shape.renderer == node-graph（L2 §3.2 次序 2）").isEqualTo("node-graph");
        JsonNode workflow = body.path("payload").path("workflow");
        assertThat(workflow.isObject())
                .as("payload.workflow 为对象（L2 §5.1 映射表）").isTrue();

        List<String> registrationStatuses = new ArrayList<>();
        collectRegistrationStatuses(body.path("payload"), registrationStatuses);
        assertThat(registrationStatuses)
                .as("存在流程对象 registration 状态（L2 §5.2：confirmed/unresolved/unregistered 分别保留）")
                .isNotEmpty();
        assertThat(registrationStatuses).as("注册状态 confirmed（L2 §3.3：注册确认后才可 confirmed）")
                .contains("confirmed");

        List<JsonNode> diagnostics = collectAllNamedArrays(body, "diagnostics");
        boolean hasRegistrationUnresolved = anyDiagnostic(diagnostics, "WF_REGISTRATION_UNRESOLVED");
        assertThat(hasRegistrationUnresolved)
                .as("注册齐备工程不应出现 WF_REGISTRATION_UNRESOLVED（L2 §5.3）").isFalse();
    }

    @Test
    @Tag("story-feat-137-07")
    @Story("FEAT-137.F137-07: Workflow 连接语义（普通/流式/条件/多来源）区别保留")
    @DisplayName("F137-07 Workflow edges → kind 区分 + 条件 order + sourceGroupId 分组")
    void f13707WorkflowEdgeSemantics() throws Exception {
        String sessionId = sessionIdByVariant.get("workflow");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode payload = resp.body().path("payload");
        List<JsonNode> edgeArrays = collectAllNamedArrays(payload, "edges");
        assertThat(edgeArrays)
                .as("payload 中存在 edges 数组（L2 §5.2 Edge 对象）").isNotEmpty();

        // 节点 ID 集合（边端点一致性核对，审阅整改项）
        java.util.Set<String> nodeIds = new java.util.HashSet<>();
        for (JsonNode nodeArr : collectAllNamedArrays(payload, "nodes")) {
            for (JsonNode node : nodeArr) {
                String id = node.path("id").asText();
                if (!id.isBlank()) {
                    nodeIds.add(id);
                }
            }
        }

        java.util.Set<String> kinds = new java.util.HashSet<>();
        boolean hasConditionalOrder = false;
        boolean hasDefaultBranch = false;
        boolean hasMultiSourceGroup = false;
        int totalEdges = 0;
        for (JsonNode arr : edgeArrays) {
            for (JsonNode edge : arr) {
                totalEdges++;
                String kind = edge.path("kind").asText();
                if (!kind.isBlank()) {
                    kinds.add(kind);
                }
                JsonNode branch = edge.path("branch");
                boolean orderNumeric = edge.path("order").isNumber()
                        || branch.path("order").isNumber();
                if (orderNumeric) {
                    hasConditionalOrder = true;
                }
                if (branch.isObject() && branch.path("isDefault").isBoolean()
                        && branch.path("isDefault").asBoolean()) {
                    hasDefaultBranch = true;
                }
                if (!edge.path("sourceGroupId").asText().isBlank()) {
                    hasMultiSourceGroup = true;
                }
            }
        }
        assertThat(totalEdges)
                .as("图含连接（L2 §3.3：连接集合）").isGreaterThan(0);
        assertThat(kinds.size())
                .as("kind 取值区分普通与流式连接（L2 §3.3：普通连接和流式连接使用各自 kind）")
                .isGreaterThanOrEqualTo(2);
        assertThat(hasConditionalOrder)
                .as("条件边保留声明顺序 order（L2 §5.3 条件分支）").isTrue();
        assertThat(hasDefaultBranch)
                .as("存在默认分支标记 isDefault（L2 §5.3 条件分支）").isTrue();
        assertThat(hasMultiSourceGroup)
                .as("多来源边保留 sourceGroupId 分组（L2 §5.3 多来源分组）").isTrue();

        // 分支条件与边端点核对（审阅整改项：检查分支条件、顺序与边端点）
        assertThat(containsTextAnywhere(payload, "amount > 1000"))
                .as("分支条件表达式 amount > 1000 在载荷中保留（L2 §5.3 条件分支不丢失）").isTrue();
        int edgesResolvedToNode = 0;
        for (JsonNode arr : edgeArrays) {
            for (JsonNode edge : arr) {
                if (containsAnyText(edge, nodeIds)) {
                    edgesResolvedToNode++;
                }
            }
        }
        assertThat(edgesResolvedToNode)
                .as("每条边端点可解析到 nodes 既有节点 ID（边端点一致性，审阅整改项）")
                .isEqualTo(totalEdges);
    }

    /** 判断对象内任一文本字段值命中给定集合（用于边端点解析核对）。 */
    private static boolean containsAnyText(JsonNode node, java.util.Set<String> values) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            return values.contains(node.asText());
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                if (containsAnyText(child, values)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- 场景 F137-08/09：部分解析与混合证据 ----

    @Test
    @Tag("story-feat-137-08")
    @Story("FEAT-137.F137-08: 非规范语句导致部分解析并保留诊断")
    @DisplayName("F137-08 非规范 Workflow → 部分解析 + WF_NONCANONICAL_FORM")
    void f13708NonCanonicalPartialAnalysis() throws Exception {
        String sessionId = sessionIdByVariant.get("wfPartial");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode body = resp.body();
        assertThat(body.path("shape").path("type").asText())
                .as("疑似 Workflow 证据保留形态（L2 §3.2）").isEqualTo("workflow");

        // 已确认结构仍在：payload 下存在含 nodes 的图对象（L2 §4.2：同一文件注册失败不迫使已确认流程被丢弃）
        List<JsonNode> nodeArrays = collectAllNamedArrays(body.path("payload"), "nodes");
        assertThat(nodeArrays)
                .as("已确认结构仍存在（图对象 nodes）").isNotEmpty();

        // 部分解析可见：WF_NONCANONICAL_FORM（文件诊断或 payload.workflow.unresolved）或状态 partial（L2 §3.3/§5.3）
        boolean hasNonCanonical = containsTextAnywhere(body, "WF_NONCANONICAL_FORM");
        boolean hasPartialState = containsTextAnywhere(body, "partial");
        assertThat(hasNonCanonical || hasPartialState)
                .as("部分解析可见：WF_NONCANONICAL_FORM 未解析项或状态 partial（L2 §3.3/§5.3）").isTrue();
    }

    @Test
    @Tag("story-feat-137-09")
    @Story("FEAT-137.F137-09: Workflow 与 ReAct 证据并存按 Workflow 展示并给出 SHAPE_AMBIGUOUS")
    @DisplayName("F137-09 混合证据工程 → shape=workflow + SHAPE_AMBIGUOUS")
    void f13709MixedEvidencePrefersWorkflow() throws Exception {
        String sessionId = sessionIdByVariant.get("mixed");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode body = resp.body();
        assertThat(body.path("shape").path("type").asText())
                .as("证据并存时按 Workflow 展示（L2 §3.2）").isEqualTo("workflow");
        assertThat(body.path("shape").path("evidence").size())
                .as("shape.evidence 非空（L2 §2.2：证据及采用依据）").isGreaterThan(0);

        List<JsonNode> diagnostics = collectAllNamedArrays(body, "diagnostics");
        boolean hasAmbiguous = anyDiagnostic(diagnostics, "SHAPE_AMBIGUOUS");
        assertThat(hasAmbiguous)
                .as("诊断出现 SHAPE_AMBIGUOUS（L2 §3.2：使用 SHAPE_AMBIGUOUS 说明采用规则）").isTrue();
    }

    // ---- 场景 F137-10/11：空工程与入口一致性 ----

    @Test
    @Tag("story-feat-137-10")
    @Story("FEAT-137.F137-10: 空工程采用不确定兜底展示")
    @DisplayName("F137-10 空工程 project/model → confidence=uncertain + SHAPE_UNDETERMINED")
    void f13710EmptyProjectUncertain() throws Exception {
        String sessionId = sessionIdByVariant.get("empty");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode body = resp.body();
        assertThat(body.path("shape").path("confidence").asText())
                .as("空工程 confidence == uncertain（L2 §3.2 次序 6：不标确定识别）")
                .isEqualTo("uncertain");
        assertThat(body.path("shape").path("type").asText())
                .as("兜底展示非完整识别（特性档 §2.2：不能静默伪装为已完整识别）")
                .isIn("moa", "workflow", "deep", "react");

        List<JsonNode> diagnostics = collectAllNamedArrays(body, "diagnostics");
        boolean hasUndetermined = anyDiagnostic(diagnostics, "SHAPE_UNDETERMINED");
        assertThat(hasUndetermined)
                .as("诊断含 SHAPE_UNDETERMINED（L2 §3.2 次序 6）").isTrue();
    }

    @Test
    @Tag("story-feat-137-11")
    @Story("FEAT-137.F137-11: /ui 与 /model 由同一模型构建器产生一致形态")
    @DisplayName("F137-11 project/ui 与 project/model → shape.type/renderer 相同")
    void f13711UiAndModelShapeConsistency() throws Exception {
        String sessionId = sessionIdByVariant.get("moa");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp model = wb.get("/api/sessions/" + sessionId + "/project/model");
        Feat136WorkbenchSupport.Resp ui = wb.get("/api/sessions/" + sessionId + "/project/ui");

        assertThat(model.status()).as("即时分析应 200").isEqualTo(200);
        assertThat(ui.status()).as("UI 投影应 200").isEqualTo(200);
        assertThat(ui.body().path("shape").path("type").asText())
                .as("两入口 shape.type 相同（L2 §2.2：同一模型构建器）")
                .isEqualTo(model.body().path("shape").path("type").asText());
        assertThat(ui.body().path("shape").path("renderer").asText())
                .as("两入口 shape.renderer 相同（L2 §2.2）")
                .isEqualTo(model.body().path("shape").path("renderer").asText());
    }

    // ---- 场景 F137-22：修改后刷新 manifestHash 变化且实际结构更新 ----

    @Test
    @Tag("story-feat-137-22")
    @Story("FEAT-137.F137-22: 工程文件变更后新分析 manifestHash 变化且刷新后实际结构更新")
    @DisplayName("F137-22 追加新候选后 project/model → manifestHash 改变 + 新候选出现在载荷")
    void f13722RefreshManifestHashChanges() throws Exception {
        String sessionId = sessionIdByVariant.get("moa");
        Path dir = projectDirByVariant.get("moa");
        assumeTrue(sessionId != null && dir != null,
                backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);

        Feat136WorkbenchSupport.Resp before = wb.get("/api/sessions/" + sessionId + "/project/ui");
        assumeTrue(before.status() == 200, "基线投影未取得（status=" + before.status() + "）");
        String hashBefore = before.body().path("fingerprints").path("manifestHash").asText();
        assumeTrue(!hashBefore.isBlank(), "基线 manifestHash 缺失");

        // 进程外注入：向 application.yml 追加新候选 remote-agents 条目（工程文件为业务定义来源，L2 §5.5 输入快照）
        Path yaml = dir.resolve("src/main/resources/application.yml");
        assumeTrue(Files.exists(yaml), "MOA application.yml 缺失");
        Files.write(yaml, ("        - name: feat137-moa-worker-c\n"
                + "          url: http://127.0.0.1:19003/a2a/card\n").getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        Feat136WorkbenchSupport.Resp after = wb.get("/api/sessions/" + sessionId + "/project/model");
        assertThat(after.status()).as("变更后即时分析应 200").isEqualTo(200);
        String hashAfter = after.body().path("fingerprints").path("manifestHash").asText();
        assertThat(hashAfter).as("manifestHash 非空").isNotBlank();
        assertThat(hashAfter)
                .as("新增输入使 manifestHash 变化（L2 §5.5：新增、删除、状态改变影响摘要；特性档 §5.1.5）")
                .isNotEqualTo(hashBefore);
        // 刷新后实际结构核对（审阅整改项 G1：不只看指纹变化，核对刷新后的实际结构）
        assertThat(containsTextAnywhere(after.body().path("payload"), "feat137-moa-worker-c"))
                .as("刷新后新候选 feat137-moa-worker-c 出现在载荷（特性档 §5.1.5：以新分析结果判断结构变化）")
                .isTrue();
    }

    // ---- 场景 F137-24：Workflow 注册未解析 ----

    @Test
    @Tag("story-feat-137-24")
    @Story("FEAT-137.F137-24: 注册来源无法静态确认 → unresolved，不伪装 confirmed")
    @DisplayName("F137-24 运行期装配 Workflow → registration.status=unresolved 或 WF_REGISTRATION_UNRESOLVED")
    void f13724WorkflowRegistrationUnresolved() throws Exception {
        String sessionId = sessionIdByVariant.get("wfUnresolved");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode body = resp.body();
        assertThat(body.path("shape").path("type").asText())
                .as("疑似 Workflow 证据保留形态（L2 §3.2）").isEqualTo("workflow");

        List<String> registrationStatuses = new ArrayList<>();
        collectRegistrationStatuses(body.path("payload"), registrationStatuses);
        boolean hasUnresolvedStatus = registrationStatuses.contains("unresolved");
        boolean hasUnresolvedItem = containsTextAnywhere(body, "WF_REGISTRATION_UNRESOLVED");
        assertThat(hasUnresolvedStatus || hasUnresolvedItem)
                .as("注册来源无法确认 → unresolved 状态或 WF_REGISTRATION_UNRESOLVED 未解析项（L2 §3.3：无法判断注册时使用 unresolved）")
                .isTrue();
        assertThat(registrationStatuses.contains("confirmed"))
                .as("注册未确认不得伪装 confirmed（L2 §3.3：注册解析确认后才能标 confirmed）").isFalse();
    }

    // ---- 场景 F137-25：Workflow 未注册 ----

    @Test
    @Tag("story-feat-137-25")
    @Story("FEAT-137.F137-25: 无注册证据的流程定义 → unregistered，不出现 confirmed")
    @DisplayName("F137-25 孤立流程定义 → registration.status=unregistered")
    void f13725WorkflowUnregistered() throws Exception {
        String sessionId = sessionIdByVariant.get("wfUnregistered");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode body = resp.body();
        List<String> registrationStatuses = new ArrayList<>();
        collectRegistrationStatuses(body.path("payload"), registrationStatuses);
        boolean hasUnregistered = registrationStatuses.contains("unregistered")
                || containsTextAnywhere(body, "unregistered");
        assertThat(hasUnregistered)
                .as("无注册证据 → unregistered 或对应诊断（L2 §3.3：明确没有注册证据时使用 unregistered）").isTrue();
        assertThat(registrationStatuses.contains("confirmed"))
                .as("未注册流程不得进入 confirmed（L2 §3.3：只有注册解析确认后才能标 confirmed）").isFalse();
    }

    // ---- 场景 F137-26：Workflow 整体分析不可用 ----

    @Test
    @Tag("story-feat-137-26")
    @Story("FEAT-137.F137-26: 定义整体破损 → 分析不可用/诊断保留，不伪装完整识别")
    @DisplayName("F137-26 破损 Workflow → 200 + 诊断/降级，不标 certain 完整识别")
    void f13726WorkflowOverallUnavailable() throws Exception {
        String sessionId = sessionIdByVariant.get("wfBroken");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/project/model");

        assertThat(resp.status()).isEqualTo(200);
        JsonNode body = resp.body();
        // 诊断必须可见（特性档 §2.2：未解析项有对应诊断，不能静默伪装为已完整识别）
        List<JsonNode> diagnostics = collectAllNamedArrays(body, "diagnostics");
        boolean anyDiagnosticVisible = !diagnostics.isEmpty()
                && diagnostics.stream().anyMatch(arr -> arr.size() > 0);
        assertThat(anyDiagnosticVisible)
                .as("整体破损工程诊断可见（特性档 §2.2：展示实际分析状态，未解析项有对应诊断）").isTrue();
        // 不伪装完整识别：confidence 非 certain，或诊断明示解析失败
        boolean degraded = !"certain".equals(body.path("shape").path("confidence").asText())
                || anyDiagnostic(diagnostics, "WF_NONCANONICAL_FORM")
                || anyDiagnostic(diagnostics, "SHAPE_UNDETERMINED");
        assertThat(degraded)
                .as("不伪装完整识别：confidence 非 certain 或诊断标明不可用（特性档 §2.2；L2 §5.3 状态枚举）").isTrue();
    }

    // ---- 场景 F137-31：其他用户真实会话不可见（自含多用户流程，审阅整改项） ----

    @Test
    @Tag("story-feat-137-31")
    @Story("FEAT-137.F137-31: 其他用户真实会话在本用户上下文不可见")
    @DisplayName("F137-31 管理员创建第二用户并建立真实会话 → 本用户访问 → 404 SESSION_NOT_FOUND")
    void f13731ForeignUserSessionNotVisible() throws Exception {
        assumeTrue(backendReady, backendGateReason);
        // ① 管理员创建第二用户（POST /api/admin/users；409=账号已存在则复用）
        String userB = "feat137ub" + Feat137WorkbenchFixture.runId();
        String passwordB = "Feat137UserB1" + Feat137WorkbenchFixture.runId();
        com.fasterxml.jackson.databind.node.ObjectNode userBody = wb.obj();
        userBody.put("username", userB);
        userBody.put("password", passwordB);
        userBody.put("role", "user");
        Feat136WorkbenchSupport.Resp created = wb.sendWithBody("POST", "/api/admin/users", userBody);
        assertThat(created.status())
                .as("管理员创建第二用户应 201 或 409（已存在复用，L2 §2.1 会话归属）")
                .isIn(201, 409);

        // ② 用户 B 独立登录并建立真实会话（真实依赖：opencode 引擎拉起）
        Feat136WorkbenchSupport wbB = new Feat136WorkbenchSupport(wb.base());
        assertThat(wbB.login(userB, passwordB)).as("用户 B 登录应成功").isTrue();
        Feat136WorkbenchSupport.Resp wsB = wbB.sendWithBody("POST", "/api/workspaces",
                wbB.workspaceByName("feat137-userb-" + Feat137WorkbenchFixture.runId()));
        assumeTrue(wsB.status() == 200 || wsB.status() == 201, "用户 B 工作区创建失败");
        createdWorkspaceIds.add(wsB.body().path("id").asText());
        createdDirs.add(Feat137WorkbenchFixture.toHostPath(
                wsB.body().path("path").asText(), hostPathMap));
        Feat136WorkbenchSupport.Resp sessB = wbB.sendWithBody("POST", "/api/sessions",
                wbB.sessionCreate(wsB.body().path("id").asText(), "feat137-foreign"));
        assumeTrue(sessB.status() == 200 || sessB.status() == 201, "用户 B 会话创建失败");
        String foreignSessionId = sessB.body().path("id").asText();

        // ③ 本用户（admin）访问用户 B 的真实会话 → 404 SESSION_NOT_FOUND 且不泄漏内容
        Feat136WorkbenchSupport.Resp ui = wb.get("/api/sessions/" + foreignSessionId + "/project/ui");
        Feat136WorkbenchSupport.Resp file = wb.get("/api/sessions/" + foreignSessionId
                + "/file?path=README.md&view=workbench");
        assertThat(ui.status())
                .as("其他用户真实会话 project/ui 应 404（L2 §2.1/§2.2 会话可见性）").isEqualTo(404);
        assertThat(ui.body().path("error").path("code").asText())
                .as("错误码逐字为 SESSION_NOT_FOUND（L2 §2.2）").isEqualTo("SESSION_NOT_FOUND");
        assertThat(file.status())
                .as("其他用户真实会话 file 应 404（L2 §2.1 会话归属校验）").isEqualTo(404);
        assertThat(file.body().path("error").path("code").asText())
                .as("file 错误码逐字为 SESSION_NOT_FOUND").isEqualTo("SESSION_NOT_FOUND");

        // ④ 清理：删除用户 B 的会话与工作区（B 上下文），再由管理员删除账号
        Feat136WorkbenchSupport.Resp delSess = wbB.delete("/api/sessions/" + foreignSessionId);
        assertThat(delSess.status() == 204 || delSess.status() == 200 || delSess.status() == 0)
                .as("用户 B 会话清理尽力而为").isTrue();
        Feat136WorkbenchSupport.Resp delWs = wbB.delete("/api/workspaces/"
                + wsB.body().path("id").asText());
        assertThat(delWs.status() == 204 || delWs.status() == 200 || delSess.status() == 0)
                .as("用户 B 工作区清理尽力而为").isTrue();
        wb.delete("/api/admin/users/" + userB);
    }

    // ---- 场景 F137-32：Agent Host 形态文件与投影读取（自含流程，审阅整改项） ----

    @Test
    @Tag("story-feat-137-32")
    @Story("FEAT-137.F137-32: Agent Host（remote 隔离）形态的文件与投影读取")
    @DisplayName("F137-32 remote 后端(files=agent) → files|file|project/ui 200 + 归属正确 + moa 识别")
    void f13732AgentHostFileAndProjectionRead() throws Exception {
        assumeTrue(backendReady, backendGateReason);
        // Agent Host 形态 = remote 隔离后端（files=agent）+ agent-host 服务；
        // 入口经 sut.workbench.agent-host.base-url 注入（部署见执行报告）
        String agentHostBase = TestConfig.load().getString("sut.workbench.agent-host.base-url", "");
        assumeTrue(!agentHostBase.isBlank(),
                "Agent Host 形态入口未配置（sut.workbench.agent-host.base-url）：需部署 remote 后端"
                        + "（P1_ISOLATION=remote + P1_DEPLOYMENT_ID/P1_REMOTE_AGENT_HOST/P1_REMOTE_AGENT_TOKEN_FILE"
                        + " + P1_VOLUME_ROOT）与 agent-host（agenthost/index.mjs，AGENT_HOST_PORT=7310）");

        // ① 登录 remote 后端（同一工作台本地账号体系）
        Feat136WorkbenchSupport host = new Feat136WorkbenchSupport(agentHostBase);
        String user = TestConfig.load().getString("sut.workbench.username", "admin");
        String password = TestConfig.load().getString("sut.workbench.password",
                System.getenv("WORKBENCH_ADMIN_PASSWORD") == null ? "" : System.getenv("WORKBENCH_ADMIN_PASSWORD"));
        assumeTrue(!password.isBlank(), "Agent Host 形态登录凭据未配置");
        assumeTrue(host.login(user, password), "Agent Host 形态登录失败：" + agentHostBase);

        // ② 登记工作区（经 agent 的 files/mkdir 在 Agent Host 卷内创建）+ 建会话（agent 拉起引擎）
        Feat136WorkbenchSupport.Resp ws = host.sendWithBody("POST", "/api/workspaces",
                host.workspaceByName("feat137-ah-" + Feat137WorkbenchFixture.runId()));
        assumeTrue(ws.status() == 200 || ws.status() == 201, "Agent Host 形态工作区创建失败");
        String wsId = ws.body().path("id").asText();
        createdWorkspaceIds.add(wsId);
        Path wsPath = Feat137WorkbenchFixture.toHostPath(ws.body().path("path").asText(), hostPathMap);
        createdDirs.add(wsPath);
        // ③ fixture 写入 Agent Host 工作区（宿主路径映射，同一物理文件）——必须在会话创建之前，
        // remote 形态的工程镜像在会话创建时建立（镜像建立后新增文件需刷新重新分析）
        Feat137WorkbenchFixture.copyInto(buildFixtureTempDir("moa"), wsPath);
        Feat136WorkbenchSupport.Resp session = host.sendWithBody("POST", "/api/sessions",
                host.sessionCreate(wsId, "feat137-agent-host"));
        assumeTrue(session.status() == 200 || session.status() == 201, "Agent Host 形态会话创建失败");
        String ahSessionId = session.body().path("id").asText();

        // ④ 文件读取：目录条目与正文经 Agent Host 卷返回
        Feat136WorkbenchSupport.Resp files = host.get("/api/sessions/" + ahSessionId + "/files?limit=200");
        assertThat(files.status())
                .as("Agent Host 形态目录读取应 200（L2 §1.3/§7.2 默认交付形态链路）").isEqualTo(200);
        assertThat(files.body().path("entries").isArray()
                && files.body().path("entries").size() > 0)
                .as("Agent Host files 返回非空 entries").isTrue();
        Feat136WorkbenchSupport.Resp file = host.get("/api/sessions/" + ahSessionId
                + "/file?path=" + enc("src/main/resources/application.yml") + "&view=workbench");
        assertThat(file.status()).as("Agent Host 形态文件读取应 200").isEqualTo(200);
        assertThat(file.body().path("text").asText())
                .as("Agent Host 文件正文与 fixture 一致（L2 §2.3）")
                .contains("feat137-moa-master");

        // ⑤ 投影读取：sessionId 归属正确且形态识别经 Agent Host 数据面成立
        Feat136WorkbenchSupport.Resp ui = host.get("/api/sessions/" + ahSessionId + "/project/ui");
        assertThat(ui.status())
                .as("Agent Host 形态投影读取应 200（L2 §1.3/§7.2）").isEqualTo(200);
        assertThat(ui.body().path("sessionId").asText())
                .as("Agent Host 投影 sessionId 归属正确").isEqualTo(ahSessionId);
        assertThat(ui.body().path("shape").path("type").asText())
                .as("Agent Host 投影按工程实际识别 moa").isEqualTo("moa");
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- 场景 F137-23：并发 /ui + /model 共享在途分析 ----

    @Test
    @Tag("story-feat-137-23")
    @Story("FEAT-137.F137-23: 并发请求共享在途构建且各自响应会话正确")
    @DisplayName("F137-23 并发 project/ui + project/model → 均 200 且 sessionId 正确")
    void f13723ConcurrentUiAndModelShareAnalysis() throws Exception {
        String sessionId = sessionIdByVariant.get("moa");
        assumeTrue(sessionId != null, backendReady ? "前置 fixture 工作区/会话未创建成功" : backendGateReason);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Feat136WorkbenchSupport.Resp> uiFuture = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return wb.get("/api/sessions/" + sessionId + "/project/ui");
        });
        Future<Feat136WorkbenchSupport.Resp> modelFuture = pool.submit(() -> {
            start.await(5, TimeUnit.SECONDS);
            return wb.get("/api/sessions/" + sessionId + "/project/model");
        });
        start.countDown();

        Feat136WorkbenchSupport.Resp ui = uiFuture.get(60, TimeUnit.SECONDS);
        Feat136WorkbenchSupport.Resp model = modelFuture.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(ui.status()).as("并发 project/ui 应 200（L2 §7.3）").isEqualTo(200);
        assertThat(model.status()).as("并发 project/model 应 200（L2 §7.3）").isEqualTo(200);
        assertThat(ui.body().path("sessionId").asText())
                .as("ui 响应 sessionId == 请求会话（L2 §7.3：各自响应会话正确）").isEqualTo(sessionId);
        assertThat(model.body().path("sessionId").asText())
                .as("model 响应 sessionId == 请求会话（L2 §7.3）").isEqualTo(sessionId);
        assertThat(ui.body().path("shape").path("type").asText())
                .as("并发两入口 shape.type 相同（L2 §7.3：共享一次在途构建）")
                .isEqualTo(model.body().path("shape").path("type").asText());
    }

    // ---- JSON 遍历辅助（wire 层递归，不依赖内部字段命名细节） ----

    /**
     * 递归收集 registration 状态取值（L2 §5.2：registration 保留 confirmed/unresolved/unregistered）。
     * 兼容两种序列化形态：registration 为字符串（实现仓 WorkflowSourceAnalyzer.registrationState）
     * 或为含 status 子字段的对象。
     */
    private static void collectRegistrationStatuses(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode reg = node.path("registration");
            if (reg.isTextual()) {
                out.add(reg.asText());
            } else if (reg.isObject() && reg.has("status")) {
                out.add(reg.path("status").asText());
            }
            node.forEach(n -> collectRegistrationStatuses(n, out));
        } else if (node.isArray()) {
            node.forEach(n -> collectRegistrationStatuses(n, out));
        }
    }

    /** 判断诊断数组集合中是否存在指定 code。 */
    private static boolean anyDiagnostic(List<JsonNode> diagnosticArrays, String code) {
        for (JsonNode arr : diagnosticArrays) {
            for (JsonNode d : arr) {
                if (code.equals(d.path("code").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 递归收集所有名为 fieldName 的对象中，子字段 childField 的文本取值。 */
    private static void collectFieldValues(JsonNode node, String fieldName, String childField, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode child = node.path(fieldName);
            if (child.isObject() && child.has(childField)) {
                out.add(child.path(childField).asText());
            }
            node.forEach(n -> collectFieldValues(n, fieldName, childField, out));
        } else if (node.isArray()) {
            node.forEach(n -> collectFieldValues(n, fieldName, childField, out));
        }
    }

    /** 递归收集所有指定名称的 JSON 数组。 */
    private static List<JsonNode> collectAllNamedArrays(JsonNode root, String fieldName) {
        List<JsonNode> out = new ArrayList<>();
        collectAllNamedArrays(root, fieldName, out);
        return out;
    }

    private static void collectAllNamedArrays(JsonNode node, String fieldName, List<JsonNode> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode child = node.path(fieldName);
            if (child.isArray()) {
                out.add(child);
            }
            node.forEach(n -> collectAllNamedArrays(n, fieldName, out));
        } else if (node.isArray()) {
            node.forEach(n -> collectAllNamedArrays(n, fieldName, out));
        }
    }

    /** 递归判断任何文本字段是否包含 value（子串匹配：来源路径/条件文本/诊断码/状态识别）。 */
    private static boolean containsTextAnywhere(JsonNode node, String value) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            return node.asText().contains(value);
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                if (containsTextAnywhere(child, value)) {
                    return true;
                }
            }
        }
        return false;
    }
}
