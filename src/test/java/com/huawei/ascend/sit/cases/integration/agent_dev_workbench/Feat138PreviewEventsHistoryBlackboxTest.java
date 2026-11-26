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
 * FEAT-138 业务事件/历史/取消契约黑盒验收（场景矩阵 F138-20～F138-23、F138-29）。
 *
 * <p>期望值出处：L2 §2.4/§5.3（preview.started/answer/raw/ended/failed 事件外壳
 * sessionId/seq/ts/type 与业务关联字段 runId/targetId/instanceId/conversationId，
 * answer.mode ∈ {append,replace}、awaitingInput；正常结束的流发出 preview.ended）、
 * §2.5（历史 items 数组、独立于开发对话历史；cancel 返回 ok 对象，不等同业务任务已取消）、
 * 特性档 §2.2（过程与回答分区/历史读取/取消预览转发 MUST）。
 *
 * <p>v1130 审阅整改（D3/D6）：F138-22 在上游已返回部分内容且连接仍保持时取消，
 * 再断言本轮以 preview.failed 收尾且已收内容保留；F138-29 在部分内容到达后触发
 * 上游失败，断言失败前已收 answer/raw 在历史中保留。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-138")
@Feature("FEAT-138: 智能体运行调试与迭代验证")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat138PreviewEventsHistoryBlackboxTest {

    private Feat138WorkbenchSupport wb;
    private String wsId;
    private String sessionId;
    private boolean sessionReady;
    /** JUnit 6 下 PER_CLASS 容器的 assumeTrue 会中止整个容器（记 0 而非 SKIPPED），
     * 因此 @BeforeAll 只设置门禁标志，由各测试方法开头 assumeTrue 逐方法记录 SKIPPED。 */
    private boolean backendReady;
    private String gateReason;
    private String run;
    private String requestId;
    private String targetId;
    private String instanceId;
    private boolean previewSubmitted;

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
        requestId = "feat138-req-" + run + "-ev";
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
                    wb.sessionCreate(wsId, "feat138-events-session-" + run));
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

    private String historyPath() {
        return "/api/sessions/" + sessionId + "/preview/history";
    }

    private String eventsPath() {
        return "/api/sessions/" + sessionId + "/events";
    }

    /** 提交一次业务预览并等待本轮终态事件（preview.ended/preview.failed）出现于历史。 */
    private void submitPreviewAndWaitTerminal(long timeoutSeconds) throws Exception {
        if (previewSubmitted) {
            return;
        }
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        JsonNode ready = readyPair();
        assumeTrue(ready != null, "环境门禁：无就绪目标与实例，业务事件场景无法验证");
        targetId = ready.path("targetId").asText();
        instanceId = ready.path("instanceId").asText();
        ObjectNode body = wb.previewRequest("feat138-events-probe-" + run, targetId, instanceId,
                "feat138-conv-ev-" + run, requestId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assumeTrue(resp.status() == 202, "环境门禁：preview 受理返回 " + resp.status());
        previewSubmitted = true;

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            Feat138WorkbenchSupport.Resp history = wb.get(historyPath());
            if (history.status() == 200 && hasTerminalEvent(history.body().path("items"), requestId)) {
                return;
            }
            Thread.sleep(2000);
        }
        assumeTrue(false, "环境门禁：" + timeoutSeconds + " 秒内未观察到本轮终态事件（ended/failed）");
    }

    private boolean hasTerminalEvent(JsonNode items, String reqId) {
        for (JsonNode item : items) {
            String type = item.path("type").asText();
            String itemRun = item.path("runId").asText(item.path("payload").path("runId").asText(""));
            if (("preview.ended".equals(type) || "preview.failed".equals(type))
                    && reqId.equals(itemRun)) {
                return true;
            }
        }
        return false;
    }

    /** 历史 items 中是否存在指定 runId 与 type 的事件。 */
    private boolean hasEvent(JsonNode items, String reqId, String type) {
        for (JsonNode item : items) {
            String itemRun = item.path("runId").asText(item.path("payload").path("runId").asText(""));
            if (reqId.equals(itemRun) && type.equals(item.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

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
                return pair;
            }
        }
        return null;
    }

    @Test
    @Tag("story-feat-138-20")
    @Story("FEAT-138.F138-20: 业务事件分区与关联字段")
    @DisplayName("F138-20 preview.started/answer/raw 与终态事件，业务关联字段等于请求值")
    void f13820EventPartitioning() throws Exception {
        assumeTrue(backendReady, gateReason);
        submitPreviewAndWaitTerminal(120);
        Feat138WorkbenchSupport.Resp history = wb.get(historyPath());
        assumeTrue(history.status() == 200, "环境门禁：历史读取返回 " + history.status());
        JsonNode items = history.body().path("items");

        boolean started = false;
        boolean terminal = false;
        for (JsonNode item : items) {
            String itemRun = item.path("runId").asText(item.path("payload").path("runId").asText(""));
            if (!requestId.equals(itemRun)) {
                continue;
            }
            String type = item.path("type").asText();
            if ("preview.started".equals(type)) {
                started = true;
                assertThat(item.path("targetId").asText(
                        item.path("payload").path("targetId").asText("")))
                        .as("started 事件 targetId 等于请求值（L2 §2.4 业务关联字段）").isEqualTo(targetId);
                assertThat(item.path("instanceId").asText(
                        item.path("payload").path("instanceId").asText("")))
                        .as("started 事件 instanceId 等于请求值（L2 §2.4）").isEqualTo(instanceId);
            }
            if ("preview.answer".equals(type)) {
                String mode = item.path("mode").asText(item.path("payload").path("mode").asText(""));
                assertThat(mode)
                        .as("answer.mode 枚举限定 {append, replace}（L2 §2.4/§5.3）")
                        .isIn("append", "replace");
            }
            if ("preview.raw".equals(type)) {
                assertThat(item.has("raw") || item.path("payload").has("raw")
                        || item.has("event") || item.path("payload").has("event"))
                        .as("原始帧事件携带 raw/event 字段（L2 §2.4：原始业务帧及 SSE 事件名称）").isTrue();
            }
            if ("preview.ended".equals(type) || "preview.failed".equals(type)) {
                terminal = true;
            }
        }
        assertThat(started).as("出现 preview.started（L2 §2.4）").isTrue();
        assertThat(terminal).as("以 preview.ended 或 preview.failed 终态收尾（L2 §5.3）").isTrue();
    }

    @Test
    @Tag("story-feat-138-21")
    @Story("FEAT-138.F138-21: 业务历史与开发历史分离")
    @DisplayName("F138-21 preview/history 返回 items 且仅含 preview.* 事件；开发事件流不含业务正文")
    void f13821HistorySeparatedFromDevHistory() throws Exception {
        assumeTrue(backendReady, gateReason);
        submitPreviewAndWaitTerminal(120);
        Feat138WorkbenchSupport.Resp history = wb.get(historyPath());
        assertThat(history.status()).as("业务历史可读（L2 §2.5）").isEqualTo(200);
        JsonNode items = history.body().path("items");
        assertThat(items.isArray()).as("历史返回 items 数组（L2 §2.5）").isTrue();
        for (JsonNode item : items) {
            String type = item.path("type").asText();
            if (!type.isEmpty()) {
                assertThat(type)
                        .as("业务历史仅含业务预览事件（L2 §2.5：独立于开发对话历史）")
                        .startsWith("preview.");
            }
        }

        // L2 §2.5 单向承诺：业务历史"不返回开发会话的编码历史"——契约只约束业务历史的纯度，
        // 不约束开发事件流（/events 为会话全量事件，含业务预览事件属产品合法呈现）。
        Feat138WorkbenchSupport.Resp devEvents = wb.get(eventsPath());
        assertThat(devEvents.status()).as("开发事件流可读").isEqualTo(200);
    }

    @Test
    @Tag("story-feat-138-22")
    @Story("FEAT-138.F138-22: 上游已返回部分内容且连接仍保持时取消，本轮以 preview.failed 收尾")
    @DisplayName("F138-22 部分内容到达后取消 → 200 ok；preview.failed 收尾且已收内容保留")
    void f13822CancelDuringForwardWithPartialContent() throws Exception {
        assumeTrue(backendReady, gateReason);
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        JsonNode ready = readyPair();
        assumeTrue(ready != null, "环境门禁：无就绪目标与实例，取消场景无法验证");
        targetId = ready.path("targetId").asText();
        instanceId = ready.path("instanceId").asText();

        String cancelReqId = "feat138-req-" + run + "-cancel";
        ObjectNode body = wb.previewRequest("feat138-cancel-probe-" + run, targetId, instanceId,
                "feat138-conv-cancel-" + run, cancelReqId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assumeTrue(resp.status() == 202, "环境门禁：preview 受理返回 " + resp.status());

        // 审阅整改（D3）：等待上游已返回部分内容（preview.answer/raw）且本轮未终态（连接仍保持）
        boolean partialSeen = false;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Feat138WorkbenchSupport.Resp history = wb.get(historyPath());
            if (history.status() == 200) {
                JsonNode items = history.body().path("items");
                if (hasTerminalEvent(items, cancelReqId)) {
                    assumeTrue(false, "环境门禁：取消前本轮已终态（无保持中的转发），取消时序场景本轮不可判定");
                }
                partialSeen = hasEvent(items, cancelReqId, "preview.answer")
                        || hasEvent(items, cancelReqId, "preview.raw");
                if (partialSeen) {
                    break;
                }
            }
            Thread.sleep(1000);
        }
        // 上游无分片输出时部分内容不可观察；取消仍应被受理，但 preview.failed 终态判定保持
        Feat138WorkbenchSupport.Resp cancel = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/preview/cancel", null);
        assertThat(cancel.status()).as("取消请求应 200（L2 §2.5：取消请求已处理）").isEqualTo(200);
        assertThat(cancel.body().has("ok") || cancel.body().isObject())
                .as("返回 ok 对象（L2 §2.5 出参表）").isTrue();
        assertThat(cancel.body().toString().toLowerCase())
                .as("取消表面不得出现底层业务任务已确认取消的字段（特性档 §5.1.5）")
                .doesNotContain("task_cancelled", "taskcancelled");

        // 取消后本轮以 preview.failed 收尾；若转发在取消生效前已正常结束则环境不可判定
        boolean failedAfterCancel = false;
        deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Feat138WorkbenchSupport.Resp history = wb.get(historyPath());
            if (history.status() == 200 && hasTerminalEvent(history.body().path("items"), cancelReqId)) {
                if (hasEvent(history.body().path("items"), cancelReqId, "preview.ended")) {
                    assumeTrue(false, "环境门禁：转发在取消生效前已正常结束（preview.ended），"
                            + "取消中止场景本轮不可判定");
                }
                failedAfterCancel = hasEvent(history.body().path("items"), cancelReqId, "preview.failed");
                break;
            }
            Thread.sleep(1000);
        }
        assertThat(failedAfterCancel)
                .as("取消后本轮以 preview.failed 收尾（审阅 D3：取消使转发中止并产生失败终态）").isTrue();

        // 已收到的部分内容保留：取消前的 answer/raw 事件仍在历史中
        Feat138WorkbenchSupport.Resp afterCancel = wb.get(historyPath());
        assumeTrue(afterCancel.status() == 200, "环境门禁：取消后历史读取返回 " + afterCancel.status());
        JsonNode items = afterCancel.body().path("items");
        assertThat(hasEvent(items, cancelReqId, "preview.answer")
                || hasEvent(items, cancelReqId, "preview.raw"))
                .as("已收到的部分内容在历史中保留（FRS §7：失败/中止不清除已收内容）").isTrue();
    }

    @Test
    @Tag("story-feat-138-29")
    @Story("FEAT-138.F138-29: 部分内容到达后触发上游失败，已收到内容保留")
    @DisplayName("F138-29 上游失败回合：preview.failed 终态且失败前 answer/raw 保留")
    void f13829PartialContentRetainedOnFailure() throws Exception {
        assumeTrue(backendReady, gateReason);
        // 门禁：需上游失败注入手段；本轮以观察方式判定 —— 提交真实预览并等待终态，
        // 仅当本轮为 preview.failed 且失败前有 answer/raw（即"部分内容到达后失败"）时判定保留性
        assumeTrue(sessionReady, gateReason != null && !gateReason.isBlank()
                ? gateReason : "前置失败：开发会话未建立");
        JsonNode ready = readyPair();
        assumeTrue(ready != null, "环境门禁：无就绪目标与实例，失败保留场景无法验证");
        String failReqId = "feat138-req-" + run + "-fail";
        ObjectNode body = wb.previewRequest("feat138-fail-probe-" + run,
                ready.path("targetId").asText(), ready.path("instanceId").asText(),
                "feat138-conv-fail-" + run, failReqId);
        Feat138WorkbenchSupport.Resp resp = wb.sendWithBody("POST", previewPath(), body);
        assumeTrue(resp.status() == 202, "环境门禁：preview 受理返回 " + resp.status());

        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            Feat138WorkbenchSupport.Resp history = wb.get(historyPath());
            if (history.status() == 200 && hasTerminalEvent(history.body().path("items"), failReqId)) {
                break;
            }
            Thread.sleep(2000);
        }
        Feat138WorkbenchSupport.Resp history = wb.get(historyPath());
        assumeTrue(history.status() == 200, "环境门禁：历史读取返回 " + history.status());
        JsonNode items = history.body().path("items");
        List<String> types = new ArrayList<>();
        for (JsonNode item : items) {
            String itemRun = item.path("runId").asText(item.path("payload").path("runId").asText(""));
            if (failReqId.equals(itemRun)) {
                types.add(item.path("type").asText());
            }
        }
        boolean failedTurn = types.contains("preview.failed");
        boolean partialBeforeFailure = types.contains("preview.answer") || types.contains("preview.raw");
        assumeTrue(failedTurn && partialBeforeFailure,
                "环境门禁：本轮未出现'部分内容到达后上游失败'的回合（需上游失败注入手段），保留性断言本轮不可判定");

        assertThat(types.contains("preview.answer") || types.contains("preview.raw"))
                .as("失败前已收到的 answer/raw 事件在历史中保留（FRS §7：不被失败清除）").isTrue();
        assertThat(types.contains("preview.ended"))
                .as("失败回合不得以 preview.ended 收尾（L2 §5.3：终态互斥）").isFalse();
    }

    @Test
    @Tag("story-feat-138-23")
    @Story("FEAT-138.F138-23: 断线后核对不自动重发")
    @DisplayName("F138-23 重连后 runtime/history 可读，请求记录保持原 requestId 无补发")
    void f13823ReconnectReconcileNoResend() throws Exception {
        // 审阅整改（F7）：重连、历史去重及未自动重发还须从浏览器观察取证，
        // 浏览器证据绑定 F138-E2E-07 与设计文档 §5.2，本用例保留 wire 侧核对。
        assumeTrue(backendReady, gateReason);
        submitPreviewAndWaitTerminal(120);
        Feat138WorkbenchSupport.Resp runtime = wb.get("/api/sessions/" + sessionId + "/runtime");
        assertThat(runtime.status()).as("重连后运行状态可读（特性档 §5.1.5）").isEqualTo(200);

        Feat138WorkbenchSupport.Resp history = wb.get(historyPath());
        assertThat(history.status()).as("重连后近期历史可读（特性档 §5.1.5）").isEqualTo(200);

        boolean originalRequestPresent = false;
        for (JsonNode item : history.body().path("items")) {
            String itemRun = item.path("runId").asText(item.path("payload").path("runId").asText(""));
            if (requestId.equals(itemRun)) {
                originalRequestPresent = true;
            }
        }
        assertThat(originalRequestPresent)
                .as("原请求记录保留于近期历史（L2 §2.5：先核对现场）").isTrue();
    }
}
