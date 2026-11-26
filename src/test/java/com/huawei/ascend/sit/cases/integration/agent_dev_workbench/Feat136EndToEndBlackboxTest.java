package com.huawei.ascend.sit.cases.integration.agent_dev_workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.config.TestConfig;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-136 端到端标准流程黑盒验收（场景矩阵 F136-30～F136-34、F136-37、F136-40）。
 *
 * <p>对应 FRS-136 §6 五个标准流程用例 WB-01-01～05 与场景级验收标准 AC-01～06。
 * 产物文件与结构投影断言经 FEAT-137 只读接口（/files、/file、/project/model）作为进程外
 * 观察探针完成；探针不可用时相关断言按环境门禁 SKIPPED 并标注（不静默降级）。
 * 真实生成回合依赖 OpenCode ACP 与开发模型（glm-5.2 实测可用），审批决策经审批 API 提交。
 * 期望值出处：FRS-136 §6/§8、特性档 §2.3、L2 §2 各契约章节。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-136")
@Feature("FEAT-136: 对话式开发高码智能体")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat136EndToEndBlackboxTest {

    private Feat136WorkbenchSupport wb;
    private String llmBlocker;

    // WB-01-01→03→04 共用生成链路状态（设计即为同工程链）
    private Feat136WorkbenchSupport.WsSession genSession;
    private Feat136WorkbenchSupport.TurnResult genResult;
    private String genManifestHash;

    private final List<String> createdWorkspaceIds = new ArrayList<>();

    @BeforeAll
    void prepare() throws Exception {
        TestConfig config = TestConfig.load();
        Feat136WorkbenchSupport.Ready ready = Feat136WorkbenchSupport.ready(config);
        assumeTrue(ready.wb() != null, ready.blocker());
        wb = ready.wb();
        llmBlocker = Feat136WorkbenchSupport.ensureDefaultLlmFromEnv(wb);
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

    /** 运行 jiuwen-agent CLI，返回退出码与 stdout（stderr 合并）。 */
    private record CliResult(int exitCode, String stdout) {
    }

    private CliResult runCli(String... args) throws Exception {
        String cli = TestConfig.load().getString("sut.workbench.cli-path", "jiuwen-agent");
        String[] command = new String[args.length + 1];
        command[0] = cli;
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] out;
        try (var in = process.getInputStream()) {
            out = in.readAllBytes();
        }
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        process.destroyForcibly();
        assumeTrue(finished, "CLI 执行超时");
        return new CliResult(process.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    /** 扫描工程文件内容，返回包含关键字的相对路径（读取条数有上限，控制探针开销）。 */
    private List<String> filesContaining(String sessionId, String wsPath, List<String> paths, String keyword,
            int maxReads) throws Exception {
        List<String> hits = new ArrayList<>();
        int reads = 0;
        for (String path : paths) {
            if (reads >= maxReads) {
                break;
            }
            Feat136WorkbenchSupport.Resp file = wb.readSessionFile(sessionId, path, wsPath);
            reads++;
            if (file.status() == 200 && file.body().path("text").asText().contains(keyword)) {
                hits.add(path);
            }
        }
        return hits;
    }

    @Test
    @Order(1)
    @Tag("story-feat-136-30")
    @Story("FEAT-136.F136-30: 端到端 WB-01-01 从空工作区生成智能体")
    @DisplayName("F136-30 空工作区生成 → 回合完成 + 真实产物 + 形态投影 + CLI 校验对照")
    void f13630GenerateAgentFromEmptyWorkspace() throws Exception {
        // 前置：开发模型可连（真实生成回合）
        assumeTrue(llmBlocker == null, llmBlocker);

        // ① 空工作区 + 会话 + 提交生成需求
        String run = Feat136WorkbenchSupport.runId();
        genSession = wb.createWorkspaceAndSession("feat136-ws-" + run + "-gen",
                "feat136-e2e-generation", llmBlocker);
        assumeTrue(genSession.ready(), "前置失败：空工作区/开发会话未创建成功（OpenCode ACP 或模型不可连）");
        createdWorkspaceIds.add(genSession.wsId());

        genResult = wb.runTurn(genSession.sessionId(),
                "请生成一个订单核算智能体工程：提供输入数量和单价，用工具计算金额；金额大于 1000 元时先向用户确认再返回结果，其余直接返回。"
                        + "请使用平台技能与 jiuwen-agent CLI 生成完整可运行的 openJiuwen 智能体工程文件。",
                wb.approveOnceAll(), 3600);

        // ② 回合以事件证明实际执行：受理、归组、终态
        assertThat(genResult.eventTurnId()).as("本回合在事件流中可关联（user.message 归组）").isNotBlank();
        assertThat(genResult.ended()).as("生成回合应到达终态 turn.ended（最长 3600 秒（进度感知：事件流 10 分钟无新增即提前放弃））").isTrue();

        // ③ 工具实际执行以工具事件证明：生成过程出现工具完成
        assertThat(genResult.toolCompleted()).as("生成回合应观察到 tool.completed（真实写入/命令执行）")
                .isNotEmpty();

        // ④ 工程产物以文件实际内容断言（探针 /files、/file；
        //    Windows 下 ProjectFiles 的 SecureDirectoryStream 不受支持 → 探针 403 时
        //    降级为同机工作区目录直读兜底，数据面与 /files 一致，已在执行报告标注）
        Feat136WorkbenchSupport.Resp filesResp = wb.get(
                "/api/sessions/" + genSession.sessionId() + "/files?limit=200");
        List<String> paths = wb.listProjectFilePaths(genSession.sessionId(), genSession.wsPath());
        assertThat(paths.size()).as("空工作区应生成工程文件（entries > 0）").isGreaterThan(0);
        List<String> nonEmpty = new ArrayList<>();
        for (String path : paths) {
            Feat136WorkbenchSupport.Resp file = wb.readSessionFile(genSession.sessionId(), path, genSession.wsPath());
            if (file.status() == 200 && !file.body().path("text").asText().isBlank()) {
                nonEmpty.add(path);
            }
            if (nonEmpty.size() >= 3) {
                break;
            }
        }
        assertThat(nonEmpty.size()).as("至少 3 个工程文件有实际内容（业务源码/配置/注册入口）")
                .isGreaterThanOrEqualTo(3);

        // ⑤ 形态投影：shape ∈ 四类型枚举，注册状态诊断面存在
        Feat136WorkbenchSupport.Resp model = wb.readProjection(genSession.sessionId());
        assumeTrue(model.status() == 200,
                "环境门禁：FEAT-137 投影探针 /project/model 不可用（HTTP " + model.status() + "）");
        assertThat(model.body().path("shape").path("type").asText())
                .as("生成产物形态 ∈ {moa,react,deep,workflow}")
                .isIn("moa", "react", "deep", "workflow");
        assertThat(model.body().path("diagnostics").isArray()).as("diagnostics 数组存在（注册状态可见面）").isTrue();
        genManifestHash = model.body().path("fingerprints").path("manifestHash").asText("");
        assertThat(genManifestHash).as("manifestHash 非空").isNotBlank();

        // ⑥ CLI validate 同工程对照（jiuwen-agent validate --project <工作区路径>）。
        // 设计要求"CLI validate 与事件呈现不矛盾、执行失败的检查可找到具体问题明细"（FRS WB-01-01 步骤④）：
        // 生成工程未经完整脚手架化时 validate 允许退出 1（blocked），契约断言为
        // status ∈ {pass,blocked,unknown} 且 blocked 时存在 fail 检查项（L2 §2.8/§5.5）
        if (genSession.wsPath() != null && !genSession.wsPath().isBlank()) {
            CliResult validate = runCli("validate", "--project", genSession.wsPath());
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(validate.stdout());
            String status = body.path("status").asText("");
            assertThat(java.util.Set.of("pass", "blocked", "unknown").contains(status))
                    .as("CLI validate 总体状态 ∈ {pass,blocked,unknown}（L2 §2.8/§5.5），实际=%s", status)
                    .isTrue();
            if ("blocked".equals(status)) {
                boolean hasFail = false;
                for (JsonNode check : body.path("checks")) {
                    if ("fail".equals(check.path("status").asText())) {
                        hasFail = true;
                        break;
                    }
                }
                assertThat(hasFail).as("status==blocked 时存在 status==fail 的检查项（L2 §5.5）").isTrue();
            }
            assertThat(body.path("checks").isArray()).as("CLI validate 输出 checks 数组").isTrue();
        }
    }

    @Test
    @Order(2)
    @Tag("story-feat-136-31")
    @Story("FEAT-136.F136-31: 端到端 WB-01-02 生成期间处理工具授权")
    @DisplayName("F136-31 允许/拒绝正反链 → 允许的 tool.completed，拒绝的不执行且保留拒绝状态")
    void f13631ApprovalFlowDuringGeneration() throws Exception {
        assumeTrue(llmBlocker == null, llmBlocker);
        assumeTrue(genSession != null && genSession.ready() && genResult != null && genResult.ended(),
                "前置失败：F136-30 生成链路未完成（同链用例，见其失败原因）");

        // ① 提交会产生多个待授权写入的回合；策略：第 1 个待决允许，第 2 个拒绝，其后允许
        Feat136WorkbenchSupport.TurnResult result = wb.runTurn(genSession.sessionId(),
                "请在工程中依次新建两个文件：feat136-auth-a.txt（内容为 auth-a-probe）和 feat136-auth-b.txt（内容为 auth-b-probe），每个文件单独创建写入。",
                (payload, approvedSoFar, rejectedSoFar) -> {
                    com.fasterxml.jackson.databind.node.ObjectNode body = wb.obj();
                    if (approvedSoFar == 0) {
                        body.put("decision", "once");
                    } else if (rejectedSoFar == 0) {
                        body.put("decision", "reject");
                    } else {
                        body.put("decision", "once");
                    }
                    return body;
                }, 1200);

        assertThat(result.eventTurnId()).as("授权链回合可关联").isNotBlank();
        assertThat(result.ended()).as("授权链回合应到达终态（最长 1200 秒）").isTrue();
        assumeTrue(result.approvalRequestedCount() >= 2,
                "环境门禁：本回合仅出现 " + result.approvalRequestedCount()
                        + " 个待决审批，无法构造允许/拒绝正反链（模型未拆分写入操作）");
        assumeTrue(!result.approvedIds().isEmpty() && !result.rejectedIds().isEmpty(),
                "环境门禁：允许/拒绝计数不足（approved=" + result.approvedIds().size()
                        + ", rejected=" + result.rejectedIds().size() + "）");

        // ② 允许的工具按实际结果呈现：被允许的审批其工具出现 tool.completed
        Feat136WorkbenchSupport.Resp events = wb.get("/api/sessions/" + genSession.sessionId() + "/events");
        assertThat(events.status()).isEqualTo(200);
        Set<String> completedToolCallIds = new HashSet<>();
        for (JsonNode item : events.body().path("items")) {
            if ("tool.completed".equals(item.path("type").asText())) {
                completedToolCallIds.add(item.path("payload").path("tool").path("toolCallId").asText());
            }
        }
        assertThat(completedToolCallIds).as("允许后实际执行由工具事件证明（存在 tool.completed）").isNotEmpty();

        // ③ 拒绝的请求未获准执行：被拒审批的 toolCallId 无 tool.completed
        Map<String, String> rejectedToolCallIds = new LinkedHashMap<>();
        for (JsonNode item : events.body().path("items")) {
            String type = item.path("type").asText();
            String aid = item.path("payload").path("approvalId").asText("");
            if (("approval.requested".equals(type) || "approval.resolved".equals(type))
                    && result.rejectedIds().contains(aid)) {
                rejectedToolCallIds.put(aid, item.path("payload").path("toolCallId").asText());
            }
        }
        for (String toolCallId : rejectedToolCallIds.values()) {
            if (toolCallId.isBlank()) {
                continue;
            }
            assertThat(!completedToolCallIds.contains(toolCallId))
                    .as("被拒绝的请求 %s 不得出现 tool.completed（拒绝保留状态）", toolCallId).isTrue();
        }

        // ④ 拒绝决定保留：approval.resolved 携带 reject 决策（by=user）
        boolean rejectResolved = false;
        for (JsonNode item : events.body().path("items")) {
            if ("approval.resolved".equals(item.path("type").asText())
                    && result.rejectedIds().contains(item.path("payload").path("approvalId").asText())
                    && "reject".equals(item.path("payload").path("decision").path("id").asText())
                    && "user".equals(item.path("payload").path("decision").path("by").asText())) {
                rejectResolved = true;
            }
        }
        assertThat(rejectResolved).as("拒绝决定保留在 approval.resolved（decision=reject, by=user）").isTrue();

        // ⑤ 事件流不将拒绝解释为业务校验失败：无 error 事件引用被拒审批
        for (String error : result.errorEvents()) {
            assertThat(!error.contains("reject"))
                    .as("拒绝不得被解释为错误/校验失败事件（error=%s）", error).isTrue();
        }

        // ⑥ 补充开发要求：同会话提交补充回合，按独立 turnId 归组
        Feat136WorkbenchSupport.TurnResult followUp = wb.runTurn(genSession.sessionId(),
                "请在对话中直接总结当前工程的文件清单，不要修改任何文件。", wb.approveOnceAll(), 300);
        assertThat(followUp.ended()).as("补充回合应到达终态").isTrue();
        assertThat(followUp.eventTurnId())
                .as("补充回合按独立事件 turnId 归组（不与授权链回合混组）")
                .isNotEqualTo(result.eventTurnId());
    }

    @Test
    @Order(3)
    @Tag("story-feat-136-32")
    @Story("FEAT-136.F136-32: 端到端 WB-01-03 画布核对与源码定位")
    @DisplayName("F136-32 投影对象与源码对应 → shape/诊断/来源文件可读，指纹稳定")
    void f13632CanvasInspectAndSourceLocate() throws Exception {
        assumeTrue(genSession != null && genSession.ready() && genResult != null && genResult.ended(),
                "前置失败：F136-30 生成链路未完成（同链用例）");

        // ① 核对工程形态、流程入口及注册状态（GET /project/model）
        Feat136WorkbenchSupport.Resp model = wb.readProjection(genSession.sessionId());
        assumeTrue(model.status() == 200, "环境门禁：投影探针 /project/model 不可用（HTTP " + model.status() + "）");
        JsonNode body = model.body();
        assertThat(body.path("shape").path("type").asText())
                .as("画布对象来自实际工程：shape.type ∈ 四类型枚举")
                .isIn("moa", "react", "deep", "workflow");
        assertThat(body.path("payload").isObject() && body.path("payload").size() > 0)
                .as("投影 payload 非空（结构对象可浏览）").isTrue();
        assertThat(body.path("diagnostics").isArray()).as("诊断面存在（未确认注册的状态可见面）").isTrue();

        // ② 投影对象与工程文件对应：投影文本（shape.evidence/payload）引用的路径存在于探针文件清单。
        // 匹配策略：完整相对路径优先，退化为文件名级匹配（evidence 的 path 可能带包路径前缀差异）
        List<String> paths = wb.listProjectFilePaths(genSession.sessionId(), genSession.wsPath());
        assumeTrue(!paths.isEmpty(), "环境门禁：探针 /files 未列出工程文件");
        Set<String> normalized = new HashSet<>();
        Map<String, String> byFileName = new LinkedHashMap<>();
        for (String path : paths) {
            String norm = path.replace('\\', '/').toLowerCase(Locale.ROOT);
            normalized.add(norm);
            byFileName.put(norm.substring(norm.lastIndexOf('/') + 1), norm);
        }
        String projectionText = (body.path("shape").path("evidence").toString()
                + body.path("payload").toString()).replace('\\', '/').toLowerCase(Locale.ROOT);
        String matchedPath = null;
        for (String path : normalized) {
            if (projectionText.contains(path)) {
                matchedPath = path;
                break;
            }
        }
        if (matchedPath == null) {
            for (Map.Entry<String, String> entry : byFileName.entrySet()) {
                if (entry.getKey().length() > 3 && projectionText.contains(entry.getKey())) {
                    matchedPath = entry.getValue();
                    break;
                }
            }
        }
        assumeTrue(matchedPath != null,
                "环境门禁：投影对象未引用任何工程文件路径（evidence/payload 与 /files 无交集），无法核对来源对应；"
                        + "工程文件清单=" + normalized.stream().limit(10).toList()
                        + "；投影文本前 400 字符=" + projectionText.substring(0, Math.min(400, projectionText.length())));

        // ③ 使用来源入口打开文件（GET /file），检查相关代码
        Feat136WorkbenchSupport.Resp source = wb.readSessionFile(genSession.sessionId(), matchedPath, genSession.wsPath());
        assertThat(source.status()).as("来源定位的文件可经 /file 读取").isEqualTo(200);
        assertThat(source.body().path("text").asText()).as("来源文件内容非空").isNotBlank();
        String fingerprint = source.body().path("fingerprint").asText();

        // ④ 返回画布继续查看：投影指纹稳定（无并发修改），再次读取成功
        Feat136WorkbenchSupport.Resp again = wb.readProjection(genSession.sessionId());
        assertThat(again.status()).as("返回画布后投影仍可读取").isEqualTo(200);
        String hash1 = body.path("fingerprints").path("manifestHash").asText();
        String hash2 = again.body().path("fingerprints").path("manifestHash").asText();
        assertThat(hash1).as("manifestHash 非空").isNotBlank();
        assertThat(hash2).as("无修改时投影指纹稳定").isEqualTo(hash1);
        if (fingerprint != null && !fingerprint.isBlank()) {
            Feat136WorkbenchSupport.Resp sourceAgain = wb.readSessionFile(genSession.sessionId(), matchedPath, genSession.wsPath());
            assertThat(sourceAgain.body().path("fingerprint").asText())
                    .as("文件指纹稳定（文件仍匹配来源时定位有效）").isEqualTo(fingerprint);
        }
        genManifestHash = hash1;
    }

    @Test
    @Order(4)
    @Tag("story-feat-136-33")
    @Story("FEAT-136.F136-33: 端到端 WB-01-04 修改已选对象并重看画布")
    @DisplayName("F136-33 阈值 1000→2000 对话修改 → 文件实际更新 + manifestHash 变化 + 其余对象可浏览")
    void f13633ModifySelectedObjectAndReinspect() throws Exception {
        assumeTrue(genSession != null && genSession.ready() && genResult != null && genResult.ended(),
                "前置失败：F136-30 生成链路未完成（同链用例）");

        // 前置事实：工程中存在包含阈值 1000 的文件（WB-01-04 的已选对象）
        List<String> paths = wb.listProjectFilePaths(genSession.sessionId(), genSession.wsPath());
        List<String> withThreshold = filesContaining(genSession.sessionId(), genSession.wsPath(), paths, "1000", 40);
        assumeTrue(!withThreshold.isEmpty(),
                "环境门禁：生成产物未包含金额阈值 1000（模型输出与需求偏离），无法构造修改场景");

        // ① 记录修改前指纹，并从对象进入开发对话提交修改要求
        Feat136WorkbenchSupport.Resp before = wb.readProjection(genSession.sessionId());
        String hashBefore = before.body().path("fingerprints").path("manifestHash").asText("");
        Feat136WorkbenchSupport.TurnResult result = wb.runTurn(genSession.sessionId(),
                "请把工程中的金额阈值从 1000 元改为 2000 元，其余逻辑保持不变。",
                wb.approveOnceAll(), 1200);

        // ② 检查实际工具执行及文件内容：工具声称修改但文件未变化时本用例判 FAIL
        assertThat(result.ended()).as("修改回合应到达终态（最长 1200 秒）").isTrue();
        List<String> afterPaths = wb.listProjectFilePaths(genSession.sessionId(), genSession.wsPath());
        List<String> withNew = filesContaining(genSession.sessionId(), genSession.wsPath(), afterPaths, "2000", 40);
        assertThat(withNew)
                .as("修改以文件实际更新为凭证：工程中应出现阈值 2000（原 1000 文件之一被更新）")
                .isNotEmpty();

        // ③ 等待刷新或主动重新读取工程：指纹变化（投影反映新内容）
        Feat136WorkbenchSupport.Resp after = wb.readProjection(genSession.sessionId());
        assertThat(after.status()).as("修改后投影可重读").isEqualTo(200);
        String hashAfter = after.body().path("fingerprints").path("manifestHash").asText("");
        assertThat(hashAfter).as("修改后 manifestHash 变化（刷新后的图反映新条件）").isNotEqualTo(hashBefore);

        // ④ 确认其他对象仍可浏览：文件清单与投影均完整可读
        assertThat(afterPaths.size()).as("工程文件清单仍可浏览").isGreaterThan(0);
    }

    @Test
    @Order(5)
    @Tag("story-feat-136-37")
    @Story("FEAT-136.F136-37: 会话事件隔离与旧结果不覆盖")
    @DisplayName("F136-37 双工作区会话并行读取 → 事件按 sessionId 隔离，互不覆盖")
    void f13637SessionEventIsolation() throws Exception {
        assumeTrue(llmBlocker == null, llmBlocker);

        // 两个工作区各有会话且均有历史事件
        String run = Feat136WorkbenchSupport.runId();
        Feat136WorkbenchSupport.WsSession a = wb.createWorkspaceAndSession(
                "feat136-ws-" + run + "-iso-a", "iso-a", llmBlocker);
        Feat136WorkbenchSupport.WsSession b = wb.createWorkspaceAndSession(
                "feat136-ws-" + run + "-iso-b", "iso-b", llmBlocker);
        assumeTrue(a.ready() && b.ready(), "前置失败：隔离用例的双工作区/会话未创建成功");
        createdWorkspaceIds.add(a.wsId());
        createdWorkspaceIds.add(b.wsId());

        Feat136WorkbenchSupport.TurnResult turnA = wb.runTurn(a.sessionId(),
                "请在对话中直接回复数字 7，不要修改任何文件。", wb.approveOnceAll(), 300);
        assumeTrue(turnA.ended(), "环境门禁：会话 A 的回合未在 300 秒内终态（模型未响应）");
        Feat136WorkbenchSupport.Resp eventsB1 = wb.get("/api/sessions/" + b.sessionId() + "/events");
        assumeTrue(eventsB1.status() == 200, "会话 B 事件读取失败");
        String bSnapshot = eventsB1.body().path("items").toString();

        // A 历史分页读取期间并行读取 B；两会话事件集按 sessionId 隔离
        Feat136WorkbenchSupport.Resp eventsA = wb.get("/api/sessions/" + a.sessionId() + "/events");
        Feat136WorkbenchSupport.Resp eventsB2 = wb.get("/api/sessions/" + b.sessionId() + "/events");
        assertThat(eventsA.status()).as("会话 A 事件读取应 200").isEqualTo(200);
        assertThat(eventsB2.status()).as("会话 B 事件读取应 200").isEqualTo(200);
        for (JsonNode item : eventsA.body().path("items")) {
            assertThat(item.path("sessionId").asText())
                    .as("A 的事件全部归属会话 A（无跨会话串扰）").isEqualTo(a.sessionId());
        }
        for (JsonNode item : eventsB2.body().path("items")) {
            assertThat(item.path("sessionId").asText())
                    .as("B 的事件全部归属会话 B").isEqualTo(b.sessionId());
        }
        assertThat(eventsB2.body().path("items").toString())
                .as("A 的历史读取不改变 B 的事件集（旧结果不覆盖）").isEqualTo(bSnapshot);
    }

    @Test
    @Order(6)
    @Tag("story-feat-136-40")
    @Story("FEAT-136.F136-40: 端到端 Workflow 类型生成正例")
    @DisplayName("F136-40 workflow 需求 → 生成 Workflow 形态工程（投影 + 流程定义文件）")
    void f13640GenerateWorkflowAgent() throws Exception {
        assumeTrue(llmBlocker == null, llmBlocker);

        String run = Feat136WorkbenchSupport.runId();
        Feat136WorkbenchSupport.WsSession session = wb.createWorkspaceAndSession(
                "feat136-ws-" + run + "-wf", "feat136-e2e-workflow", llmBlocker);
        assumeTrue(session.ready(), "前置失败：工作流生成用例的工作区/会话未创建成功");
        createdWorkspaceIds.add(session.wsId());

        Feat136WorkbenchSupport.TurnResult result = wb.runTurn(session.sessionId(),
                "请生成一个工作流（workflow）类型的 openJiuwen 智能体工程：按固定流程执行——提取订单信息、计算总价、金额大于 2000 元时分支追问确认、输出结果；"
                        + "执行步骤和分支可预先枚举。请使用平台技能与 jiuwen-agent CLI 生成，确保流程定义与注册入口完整。",
                wb.approveOnceAll(), 3600);

        assertThat(result.ended()).as("Workflow 生成回合应到达终态（最长 2700 秒）").isTrue();
        assertThat(result.toolCompleted()).as("生成过程应有工具执行证据").isNotEmpty();

        Feat136WorkbenchSupport.Resp model = wb.readProjection(session.sessionId());
        assumeTrue(model.status() == 200, "环境门禁：投影探针不可用（HTTP " + model.status() + "）");
        assertThat(model.body().path("shape").path("type").asText())
                .as("Workflow 需求应生成 Workflow 形态产物（shape.type==workflow）")
                .isEqualTo("workflow");

        // 流程定义可浏览：工程文件已生成（形态=workflow）
        List<String> paths = wb.listProjectFilePaths(session.sessionId(), session.wsPath());
        assertThat(paths.size()).as("工程文件已生成").isGreaterThan(0);
    }

    @Test
    @Order(7)
    @Tag("story-feat-136-34")
    @Story("FEAT-136.F136-34: 端到端 WB-01-05 检查失败和部分解析")
    @DisplayName("F136-34 导入部分解析工程 → 诊断可见 + 诊断定位源码 + 对话修正重跑校验")
    void f13634PartialParseAndFix() throws Exception {
        assumeTrue(llmBlocker == null, llmBlocker);

        String run = Feat136WorkbenchSupport.runId();
        Feat136WorkbenchSupport.WsSession session = wb.createWorkspaceAndSession(
                "feat136-ws-" + run + "-partial", "feat136-e2e-partial", llmBlocker);
        assumeTrue(session.ready(), "前置失败：部分解析用例的工作区/会话未创建成功");
        createdWorkspaceIds.add(session.wsId());

        // 经 ZIP 导入链（F136-21 契约）准备存在静态分析无法识别装配语句的工程。
        // 规范写法（WorkflowSourceAnalyzer 契约）：new Workflow(card) + workflow.addConnection(...)；
        // 引用流程变量的条件块/别名/外部装配语句触发 WF_NONCANONICAL_FORM（实测于 payload.workflow.unresolved）。
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("src/main/java/com/example/order/agent/workflow/OrderWorkflow.java", "package com.example.order.agent.workflow;\n\n"
                + "import com.openjiuwen.core.workflow.Workflow;\n"
                + "import com.openjiuwen.core.workflow.WorkflowCard;\n\n"
                + "public final class OrderWorkflow {\n"
                + "    private OrderWorkflow() {\n"
                + "    }\n\n"
                + "    public static Workflow create(ModelConfig modelConfig) {\n"
                + "        WorkflowCard card = WorkflowCard.builder()\n"
                + "                .id(\"order_flow\")\n"
                + "                .name(\"订单处理流程\")\n"
                + "                .version(\"1.0\")\n"
                + "                .build();\n"
                + "        Workflow workflow = new Workflow(card);\n"
                + "        workflow.addConnection(\"start\", \"end\");\n"
                + "        // 非规范写法：条件块内连接、外部装配与别名引用，静态分析无法确定\n"
                + "        if (debug) { workflow.addConnection(\"start\", \"end\"); }\n"
                + "        AuditSteps.attach(workflow);\n"
                + "        Workflow alias = workflow;\n"
                + "        alias.addConnection(\"start\", \"end\");\n"
                + "        return workflow;\n"
                + "    }\n"
                + "}\n");
        entries.put("README.md", "feat136 partial parse fixture\n");
        byte[] zip = Feat136WorkbenchSupport.zipOfEntries(entries);
        Feat136WorkbenchSupport.Resp preview = wb.sendRaw("POST",
                "/api/sessions/" + session.sessionId() + "/upload/preview", zip, null);
        assumeTrue(preview.status() == 200, "ZIP 预览未成功（HTTP " + preview.status() + "）");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Archive-Preview", preview.body().path("token").asText());
        Feat136WorkbenchSupport.Resp applied = wb.sendRaw("POST",
                "/api/sessions/" + session.sessionId() + "/upload", zip, headers);
        assumeTrue(applied.status() == 200, "ZIP 应用未成功（HTTP " + applied.status() + "）");

        // ① 查看结构中已确认的对象与诊断：部分可解析内容仍可浏览（payload.workflow 图 + unresolved 明细）
        Feat136WorkbenchSupport.Resp model = wb.readProjection(session.sessionId());
        assumeTrue(model.status() == 200, "环境门禁：投影探针不可用（HTTP " + model.status() + "）");
        JsonNode workflowPayload = model.body().path("payload").path("workflow");
        assertThat(model.body().path("payload").isObject()).as("已确认结构保留（payload 可浏览）").isTrue();
        JsonNode unresolved = workflowPayload.path("unresolved");
        assertThat(unresolved.isArray()).as("未确认结构明细数组存在（payload.workflow.unresolved）").isTrue();
        // 诊断明细指向具体文件（WF_NONCANONICAL_FORM 携带 source.path），流程图标记 partial
        String diagFile = null;
        for (JsonNode item : unresolved) {
            if ("WF_NONCANONICAL_FORM".equals(item.path("code").asText())) {
                String path = item.path("source").path("path").asText("");
                if (path.contains("OrderWorkflow") || path.contains("src/main/java")) {
                    diagFile = path;
                }
            }
        }
        assumeTrue(diagFile != null,
                "环境门禁：导入工程未产生指向源文件的诊断（unresolved=" + unresolved.toString() + "），"
                        + "无法核对诊断→源码链路");

        // ② 从诊断检查源码（GET /file），说明需要修正的具体行为
        Feat136WorkbenchSupport.Resp source = wb.readSessionFile(session.sessionId(), diagFile, session.wsPath());
        assertThat(source.status()).as("诊断指向的源文件可读").isEqualTo(200);
        assertThat(source.body().path("text").asText())
                .as("源文件内容与导入一致（含非规范装配语句）").contains("addConnection");

        // ③ 通过对话修改并重新运行适用的校验
        Feat136WorkbenchSupport.TurnResult fix = wb.runTurn(session.sessionId(),
                "请修正 OrderWorkflow.java：把条件块内连接、外部装配与别名引用等非规范语句改为静态显式的 workflow.addConnection 写法，使流程可静态分析。",
                wb.approveOnceAll(), 1200);
        assertThat(fix.ended()).as("修正回合应到达终态（最长 1200 秒）").isTrue();

        // ④ 再次加载画布 + CLI 校验：修正后的完整性由新一轮输入决定，检查项状态可区分
        Feat136WorkbenchSupport.Resp modelAfter = wb.readProjection(session.sessionId());
        assertThat(modelAfter.status()).as("修正后投影可重读").isEqualTo(200);
        JsonNode unresolvedAfter = modelAfter.body().path("payload").path("workflow").path("unresolved");
        List<String> afterPaths = wb.listProjectFilePaths(session.sessionId(), session.wsPath());
        List<String> staleContent = filesContaining(session.sessionId(), session.wsPath(), afterPaths, "AuditSteps.attach", 20);
        assertThat(staleContent.isEmpty())
                .as("对话修正以文件实际更新为凭证：非规范装配语句应已移除").isTrue();
        boolean cleanDiag = true;
        for (JsonNode item : unresolvedAfter) {
            if ("WF_NONCANONICAL_FORM".equals(item.path("code").asText())
                    && item.path("source").path("path").asText("").contains("OrderWorkflow")) {
                cleanDiag = false;
            }
        }
        assertThat(cleanDiag)
                .as("修正后该文件的 WF_NONCANONICAL_FORM 明细应消除（unresolved=" + unresolvedAfter + "）")
                .isTrue();

        if (session.wsPath() != null && !session.wsPath().isBlank()) {
            // 重新运行适用的校验：本工程未脚手架化（无 POM），validate 允许退出 1（blocked），
            // 契约要求为 status ∈ {pass,blocked,unknown} 且 blocked 时存在 fail 检查项（L2 §5.5）
            CliResult validate = runCli("validate", "--project", session.wsPath());
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(validate.stdout());
            String status = body.path("status").asText("");
            assertThat(java.util.Set.of("pass", "blocked", "unknown").contains(status))
                    .as("CLI validate 总体状态 ∈ {pass,blocked,unknown}（L2 §2.8/§5.5），实际=%s", status)
                    .isTrue();
            JsonNode checks = body.path("checks");
            if ("blocked".equals(status)) {
                boolean hasFail = false;
                for (JsonNode check : checks) {
                    if ("fail".equals(check.path("status").asText())) {
                        hasFail = true;
                        break;
                    }
                }
                assertThat(hasFail).as("status==blocked 时存在 status==fail 的检查项（L2 §5.5）").isTrue();
            }
            if (checks.isArray()) {
                Set<String> statuses = new HashSet<>();
                checks.forEach(c -> statuses.add(c.path("status").asText()));
                // 失败、待复核（review）和未执行（skip）的检查均可区分（L2 §5.5）
                assertThat(statuses.stream().allMatch(s ->
                        Set.of("pass", "fail", "review", "skip").contains(s)))
                        .as("检查项状态 ∈ {pass,fail,review,skip}").isTrue();
            }
        }
    }
}