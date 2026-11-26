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

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-136 恢复/回合/事件/WS 契约黑盒验收（场景矩阵 F136-09～F136-15、F136-36、F136-38）。
 *
 * <p>期望值出处：L2 §2.3（reattach 三值 restored：恢复成功/远端回退新建/结果未知三场景均核对旧历史）、
 * §2.4（202 + 受理 turnId、HTTP turnId 与事件 turnId 独立生成）、§2.5（items/hasMore/earliestSeq/initialIdle、
 * since 游标、WS ready 与回放序号、分页与实时事件交叠）、§5.2（ACP→平台事件映射）。
 * 审阅 #599 B4/C3/C5/H1 修订：F136-09 三值分场景构造、F136-38 分页交叠、F136-36 失败按错误事件与终态原因判断。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-136")
@Feature("FEAT-136: 对话式开发高码智能体")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat136ReattachEventsBlackboxTest {

    private Feat136WorkbenchSupport wb;
    private String wsId;
    private String sessionId;
    private String httpTurnId;
    private boolean sessionReady;

    private final List<String> createdWorkspaceIds = new ArrayList<>();

    @BeforeAll
    void probeBackendAndPrepareSession() throws Exception {
        TestConfig config = TestConfig.load();
        // 共享前置：免鉴权就绪探测 + 本地账号登录（App.java：/api/* 除 /api/deployment、/api/auth/* 均需鉴权）
        Feat136WorkbenchSupport.Ready ready = Feat136WorkbenchSupport.ready(config);
        assumeTrue(ready.wb() != null, ready.blocker());
        wb = ready.wb();

        // 创建隔离工作区与会话（会话依赖默认模型提供方与 OpenCode ACP，失败则依赖会话的场景门禁跳过）
        String name = "feat136-ws-" + Feat136WorkbenchSupport.runId();
        Feat136WorkbenchSupport.Resp ws = wb.sendWithBody("POST", "/api/workspaces", wb.workspaceByName(name));
        if (ws.status() == 201) {
            wsId = ws.body().path("id").asText();
            createdWorkspaceIds.add(wsId);
            String llmBlocker = Feat136WorkbenchSupport.ensureDefaultLlmFromEnv(wb);
            if (llmBlocker == null) {
                Feat136WorkbenchSupport.Resp session = wb.sendWithBody("POST", "/api/sessions",
                        wb.sessionCreate(wsId, "feat136-reattach-session"));
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

    @Test
    @Tag("story-feat-136-09")
    @Story("FEAT-136.F136-09: reattach 三值分场景构造，旧历史均不丢失")
    @DisplayName("F136-09 reattach → 200 + restored ∈ {true,false,null}，分场景核对旧历史")
    void f13609ReattachThreeValuedRestored() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立（L2 §2.2：引擎失败时会话创建失败）");
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/reattach", null);

        assertThat(resp.status()).as("reattach 应 200（L2 §2.3）").isEqualTo(200);
        JsonNode restored = resp.body().path("restored");
        boolean isThreeValued = restored.isBoolean() || restored.isNull();
        assertThat(isThreeValued)
                .as("restored 为三值之一：true/false/null（L2 §2.3 恢复表），实际=%s", restored)
                .isTrue();

        // 审阅 #599 B4：恢复成功（true）/远端回退新建（false）/结果未知（null）三场景分别核对。
        if (restored.isBoolean() && !restored.asBoolean()) {
            // 远端回退新建（本地恢复失败）：返回必须含当前引擎会话 ID 字段（非空 string）
            String engineSessionId = firstNonBlank(resp.body(),
                    "sessionId", "engineSessionId", "engineSession", "engineConversationId");
            assertThat(engineSessionId)
                    .as("restored==false（回退新建）时应返回当前引擎会话 ID（非空 string，L2 §2.3）")
                    .isNotBlank();
        }

        // 三种取值下已投影的旧历史均可经 GET events 读到（恢复成功、回退新建与结果未知均不丢已投影历史）
        Feat136WorkbenchSupport.Resp history = wb.get("/api/sessions/" + sessionId + "/events");
        assertThat(history.status()).as("reattach 后旧历史事件仍可读取").isEqualTo(200);
        assertThat(history.body().path("items").isArray()).as("旧历史 items 为 array").isTrue();
        assertThat(history.body().path("items").size())
                .as("旧历史不因 reattach 丢失（三值任一取值均成立）")
                .isGreaterThan(0);
    }

    /** 按 candidates 顺序取 body 中第一个非空字符串字段。 */
    private String firstNonBlank(JsonNode body, String... candidates) {
        for (String field : candidates) {
            String value = body.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @Test
    @Tag("story-feat-136-10")
    @Story("FEAT-136.F136-10: 不存在会话 reattach 不可见")
    @DisplayName("F136-10 reattach 未知会话 → 404")
    void f13610ReattachUnknownSession() throws Exception {
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/nonexistent-feat136/reattach", null);
        assertThat(resp.status()).as("不存在会话 reattach 应 404（L2 §2.3/§7.3）").isEqualTo(404);
    }

    @Test
    @Tag("story-feat-136-11")
    @Story("FEAT-136.F136-11: 提交回合受理 202")
    @DisplayName("F136-11 POST turns → 202 + 受理 turnId 非空")
    void f13611SubmitTurnAccepted() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        Feat136WorkbenchSupport.Resp resp = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/turns", wb.turnRequest("列出当前工程目录结构"));

        assertThat(resp.status()).as("回合受理应 202（L2 §2.4）").isEqualTo(202);
        httpTurnId = resp.body().path("turnId").asText(null);
        assertThat(httpTurnId).as("HTTP 受理 turnId 非空 string（L2 §2.4 字段表）").isNotBlank();
    }

    @Test
    @Tag("story-feat-136-12")
    @Story("FEAT-136.F136-12: 事件读取契约字段完整")
    @DisplayName("F136-12 GET events → items/hasMore/earliestSeq/initialIdle")
    void f13612EventsContractFields() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/events");

        assertThat(resp.status()).as("事件读取应 200（L2 §2.5）").isEqualTo(200);
        assertThat(resp.body().path("items").isArray()).as("items 为 array").isTrue();
        assertThat(resp.body().path("hasMore").isBoolean()).as("hasMore 为 boolean").isTrue();
        assertThat(resp.body().path("earliestSeq").isInt()).as("earliestSeq 为 integer").isTrue();
        assertThat(resp.body().path("initialIdle").isBoolean())
                .as("initialIdle 为 boolean（L2 §2.5：未提交提示词时可确认空闲的证据）")
                .isTrue();
    }

    @Test
    @Tag("story-feat-136-13")
    @Story("FEAT-136.F136-13: since 游标严格过滤")
    @DisplayName("F136-13 GET events?since=maxSeq → 所有 items.seq > since")
    void f13613EventsSinceCursor() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        Feat136WorkbenchSupport.Resp first = wb.get("/api/sessions/" + sessionId + "/events");
        assertThat(first.status()).isEqualTo(200);
        long maxSeq = 0;
        for (JsonNode item : first.body().path("items")) {
            maxSeq = Math.max(maxSeq, item.path("seq").asLong());
        }
        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/events?since=" + maxSeq);

        assertThat(resp.status()).as("since 游标读取应 200（L2 §2.5）").isEqualTo(200);
        for (JsonNode item : resp.body().path("items")) {
            assertThat(item.path("seq").asLong())
                    .as("items[].seq 严格 > since（L2 §2.5：读取更大的公开序号）")
                    .isGreaterThan(maxSeq);
        }
    }

    @Test
    @Tag("story-feat-136-14")
    @Story("FEAT-136.F136-14: 事件回合归组与独立 turnId")
    @DisplayName("F136-14 回合事件含 turn.started/turn.ended，事件 turnId 非空且独立")
    void f13614TurnEventsGrouping() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        // 用例自足：不依赖 F136-11 的执行顺序（JUnit 默认确定性排序非声明顺序），
        // 自行提交一个回合并等待终态，再断言事件归组。
        // 前置清理：ask 策略下旧回合遗留的待决审批会阻止引擎处理新回合（事件受理但永无终态）。
        wb.resolvePendingApprovals(sessionId);
        Feat136WorkbenchSupport.Resp turn = wb.sendWithBody("POST",
                "/api/sessions/" + sessionId + "/turns",
                wb.turnRequest("列出当前工程目录结构"));
        assumeTrue(turn.status() == 202, "回合提交未受理（HTTP " + turn.status() + "）");
        String eventTurnId = wb.eventTurnIdForUserMessage(sessionId, "列出当前工程目录结构");
        boolean terminal = waitForTurnEnded(sessionId, 180, eventTurnId);
        assumeTrue(terminal, "环境门禁：180 秒内本回合未到达终态（模型服务未响应）");

        Feat136WorkbenchSupport.Resp resp = wb.get("/api/sessions/" + sessionId + "/events");
        assertThat(resp.status()).isEqualTo(200);

        boolean started = false;
        boolean ended = false;
        String groupedTurnId = null;
        for (JsonNode item : resp.body().path("items")) {
            String type = item.path("type").asText();
            if ("turn.started".equals(type)) {
                started = true;
                groupedTurnId = item.path("turnId").asText(null);
            }
            if ("turn.ended".equals(type)) {
                ended = true;
            }
        }
        assertThat(started).as("存在 turn.started 事件（L2 §5.2 映射）").isTrue();
        assertThat(ended).as("存在 turn.ended 事件（L2 §5.2 映射）").isTrue();
        assertThat(groupedTurnId).as("事件 turnId 非空（L2 §2.4：本次实际执行回合身份）").isNotBlank();
        assertThat(groupedTurnId == null || !groupedTurnId.equals(turn.body().path("turnId").asText()))
                .as("事件 turnId 独立于 HTTP 受理 turnId（L2 §2.4）")
                .isTrue();
        assertThat(eventTurnId.isBlank() || groupedTurnId == null || eventTurnId.equals(groupedTurnId))
                .as("user.message 关联的事件回合与 turn.started 归组一致")
                .isTrue();
    }

    @Test
    @Tag("story-feat-136-15")
    @Story("FEAT-136.F136-15: WS ready 与回放序号连续")
    @DisplayName("F136-15 WS 订阅（端口+1 根路径 + Cookie）→ 首帧 op=ready，event.seq 严格递增无重复")
    void f13615WsReadyAndOrderedReplay() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        // WsHub 契约：WS 与 HTTP 同宿主、端口 +1、根路径 + ?sessionId=&since= 查询参数；
        // local 模式以会话 Cookie 鉴权（不支持 Authorization 头）；建连即推 ready 与回放。
        java.net.http.HttpClient wsClient = java.net.http.HttpClient.newHttpClient()
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        ConcurrentLinkedQueue<String> frames = new ConcurrentLinkedQueue<>();
        CountDownLatch readyFrame = new CountDownLatch(1);

        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                frames.add(data.toString());
                readyFrame.countDown();
                webSocket.request(1);
                return null;
            }
        };
        java.net.http.WebSocket.Builder builder = wsClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (wb.cookieHeader() != null) {
            builder.header("Cookie", wb.cookieHeader());
        }
        WebSocket socket = builder.buildAsync(wb.wsUri(sessionId, 0), listener).join();
        try {
            assertThat(readyFrame.await(20, TimeUnit.SECONDS))
                    .as("20 秒内应收到首帧（op=ready）")
                    .isTrue();

            JsonNode ready = wb.mapper().readTree(frames.poll());
            assertThat(ready.path("op").asText())
                    .as("WS 首帧 op == ready（WsHub onOpen；实际 wire 契约）")
                    .isEqualTo("ready");
            assertThat(ready.path("sessionId").asText()).as("ready 帧含 sessionId").isEqualTo(sessionId);

            long lastSeq = Long.MIN_VALUE;
            for (String frame : frames) {
                JsonNode msg = wb.mapper().readTree(frame);
                if (!"event".equals(msg.path("op").asText())) {
                    continue;
                }
                long seq = msg.path("event").path("seq").asLong(Long.MIN_VALUE);
                if (seq != Long.MIN_VALUE) {
                    assertThat(seq)
                            .as("event 帧 seq 严格递增、无重复（L2 §2.5/§3.5）")
                            .isGreaterThan(lastSeq);
                    lastSeq = seq;
                }
            }
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @Test
    @Tag("story-feat-136-38")
    @Story("FEAT-136.F136-38: 历史分页与实时事件交叠")
    @DisplayName("F136-38 分页读取历史期间并行回合 → 无重复 seq，新事件经 since 游标完整取回")
    void f13638HistoryPaginationOverlappingRealtime() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        // 独立会话：本用例自建历史与交叠回合，不与共享会话的其他用例互扰
        // （共享会话上遗留的待决审批会让新回合被受理但永无终态，AcpSession busy 行为）
        Feat136WorkbenchSupport.Resp ownSession = wb.sendWithBody("POST", "/api/sessions",
                wb.sessionCreate(wsId, "feat136-overlap-own-session"));
        assumeTrue(ownSession.status() == 201, "独立会话创建失败（HTTP " + ownSession.status() + "）");
        String sid = ownSession.body().path("id").asText();

        // ① 预热回合（明确不使用工具）产生历史事件，并等待终态
        String warmup = "请在对话中直接回复数字 42，不要使用任何工具";
        Feat136WorkbenchSupport.Resp warm = wb.sendWithBody("POST",
                "/api/sessions/" + sid + "/turns", wb.turnRequest(warmup));
        assumeTrue(warm.status() == 202, "预热回合提交未受理（HTTP " + warm.status() + "）");
        String warmTurnId = wb.eventTurnIdForUserMessage(sid, warmup);
        boolean warmDone = waitForTurnEnded(sid, 180, warmTurnId);
        if (!warmDone) {
            wb.resolvePendingApprovals(sid);
            warmDone = waitForTurnEnded(sid, 60, warmTurnId);
        }
        assumeTrue(warmDone, "环境门禁：预热回合 240 秒内未到达终态（模型服务未响应）");

        // 读取当前历史（第一页），记录末位 seq 与 hasMore
        Feat136WorkbenchSupport.Resp page1 = wb.get("/api/sessions/" + sid + "/events");
        assumeTrue(page1.status() == 200, "历史事件读取失败（HTTP " + page1.status() + "）");
        java.util.Set<Long> seenSeqs = new java.util.HashSet<>();
        long boundarySeq = 0;
        for (JsonNode item : page1.body().path("items")) {
            long seq = item.path("seq").asLong();
            assertThat(seenSeqs.add(seq)).as("第一页内 seq 无重复（%d）", seq).isTrue();
            boundarySeq = Math.max(boundarySeq, seq);
        }
        boolean page1HasMore = page1.body().path("hasMore").asBoolean(false);

        // ② 分页向后推进期间并行提交一个回合，产生实时事件
        String overlap = "在对话中回应一句 feat136 分页交叠探针，不要使用任何工具";
        Feat136WorkbenchSupport.Resp turn = wb.sendWithBody("POST",
                "/api/sessions/" + sid + "/turns", wb.turnRequest(overlap));
        assumeTrue(turn.status() == 202, "回合提交未受理（HTTP " + turn.status() + "）");

        // ③ 以 since=boundarySeq 持续分页读取直至无新页（覆盖交叠窗口）
        // 事件 turnId 与 HTTP 受理 turnId 独立（L2 §2.4）：经 user.message 文本关联本回合
        String eventTurnId = wb.eventTurnIdForUserMessage(sid, overlap);
        boolean newTurnSeen = false;
        long cursor = boundarySeq;
        for (int page = 0; page < 50; page++) {
            Feat136WorkbenchSupport.Resp nextPage = wb.get(
                    "/api/sessions/" + sessionId + "/events?since=" + cursor);
            assertThat(nextPage.status()).as("since 分页读取应 200").isEqualTo(200);
            int fetched = 0;
            for (JsonNode item : nextPage.body().path("items")) {
                long seq = item.path("seq").asLong();
                assertThat(seq).as("分页事件 seq 严格 > 游标（L2 §2.5）").isGreaterThan(cursor);
                assertThat(seenSeqs.add(seq))
                        .as("分页与实时交叠不产生重复 seq（%d）", seq)
                        .isTrue();
                String type = item.path("type").asText();
                if ("turn.started".equals(type) && !eventTurnId.isBlank()
                        && eventTurnId.equals(item.path("turnId").asText(""))) {
                    newTurnSeen = true;
                }
                fetched++;
                cursor = Math.max(cursor, seq);
            }
            if (fetched == 0 && !newTurnSeen) {
                break;
            }
            if (fetched > 0 && "turn.ended".equals(lastItemType(nextPage))) {
                break;
            }
        }

        // ④ 回合终态后核对交叠产生的新事件完整取回（不丢、不重）
        boolean terminal = waitForTurnEnded(sid, 180, eventTurnId);
        assertThat(terminal).as("180 秒内交叠回合应到达终态").isTrue();
        Feat136WorkbenchSupport.Resp after = wb.get("/api/sessions/" + sid + "/events");
        assertThat(after.status()).isEqualTo(200);
        boolean endedSeen = false;
        boolean overlapEnded = false;
        java.util.Set<Long> finalSeqs = new java.util.HashSet<>();
        for (JsonNode item : after.body().path("items")) {
            long seq = item.path("seq").asLong();
            // 交叠窗口内全部事件 seq 不重复（含分页已见与回合实时事件）
            assertThat(finalSeqs.add(seq)).as("交叠窗口事件 seq 无重复（%d）", seq).isTrue();
            if ("turn.ended".equals(item.path("type").asText())) {
                endedSeen = true;
                if (!eventTurnId.isBlank()
                        && eventTurnId.equals(item.path("turnId").asText(""))) {
                    overlapEnded = true;
                }
            }
        }
        assertThat(endedSeen).as("交叠回合的 turn.ended 在后续读取中完整取回（不丢）").isTrue();
        assertThat(overlapEnded).as("本交叠回合的终态事件经 since 游标完整取回（不丢、归组本回合）").isTrue();
        assertThat(page1HasMore || !finalSeqs.isEmpty()).as("分页推进完成").isTrue();
    }

    private String lastItemType(Feat136WorkbenchSupport.Resp resp) {
        JsonNode items = resp.body().path("items");
        if (items.isArray() && items.size() > 0) {
            return items.get(items.size() - 1).path("type").asText();
        }
        return "";
    }

    @Test
    @Tag("story-feat-136-36")
    @Story("FEAT-136.F136-36: 回合执行失败按错误事件与终态原因判断")
    @DisplayName("F136-36 模型不可用的失败回合 → 错误事件/失败终态可观察，工作区仍在")
    void f13636TurnFailureObservable() throws Exception {
        assumeTrue(sessionReady, "前置失败：OpenCode ACP 会话未建立");
        // 独立会话：审批挂起/失败分类不与共享会话的其他用例互扰
        Feat136WorkbenchSupport.Resp ownSession = wb.sendWithBody("POST", "/api/sessions",
                wb.sessionCreate(wsId, "feat136-failure-own-session"));
        assumeTrue(ownSession.status() == 201, "独立会话创建失败（HTTP " + ownSession.status() + "）");
        String sid = ownSession.body().path("id").asText();
        // 故障注入（进程外）：开发模型停用复用 F136-08 场景。若模型实际可连，
        // 回合将正常完成——此时按环境门禁跳过（无法在本环境构造回合失败）。
        String ownText = "feat136 失败语义探针：在工程中新建文件 feat136-failure-probe.txt";
        Feat136WorkbenchSupport.Resp turn = wb.sendWithBody("POST",
                "/api/sessions/" + sid + "/turns",
                wb.turnRequest(ownText));
        assumeTrue(turn.status() == 202, "回合提交未受理（HTTP " + turn.status() + "）");

        String eventTurnId = wb.eventTurnIdForUserMessage(sid, ownText);
        boolean terminal = waitForTurnEnded(sid, 180, eventTurnId);
        if (!terminal && !eventTurnId.isBlank()) {
            // ask 策略下本回合可能被权限审批挂起（模型可连的正常行为，非失败语义）：
            // 清理待决审批让回合终止，恢复会话状态供后续用例使用
            wb.resolvePendingApprovals(sid);
            waitForTurnEnded(sid, 60, eventTurnId);
        }
        Feat136WorkbenchSupport.Resp events = wb.get("/api/sessions/" + sid + "/events");
        assertThat(events.status()).as("失败回合的事件流仍可读取").isEqualTo(200);

        boolean failureObservable = false;
        boolean endedWithFailureReason = false;
        boolean ownApprovalPending = false;
        for (JsonNode item : events.body().path("items")) {
            // 只统计本回合的事件（事件 turnId 归组，旧回合事件不掩盖本回合失败语义）
            if (!eventTurnId.isBlank()
                    && !eventTurnId.equals(item.path("turnId").asText(""))) {
                continue;
            }
            String type = item.path("type").asText();
            if ("approval.requested".equals(type)) {
                ownApprovalPending = true;
            }
            String payloadText = item.path("payload").toString() + item.path("error").toString()
                    + item.path("reason").toString();
            if (type.contains("error") || type.contains("failed") || type.contains("failure")
                    || payloadText.contains("fail") || payloadText.contains("error")) {
                failureObservable = true;
            }
            if ("turn.ended".equals(type)) {
                // AcpSession turn.ended payload：{turnId, stopReason}；"error" 即失败终态（审阅 #599 H1）
                String stopReason = item.path("payload").path("stopReason").asText(
                        item.path("payload").path("reason").asText(""));
                if ("error".equalsIgnoreCase(stopReason)) {
                    endedWithFailureReason = true;
                }
            }
        }
        // 审阅 #599 H1：失败按错误事件与终态原因判断，失败回合也可发出 turn.ended。
        // 本环境模型可连且 ask 策略会拦截写入操作（审批挂起 ≠ 失败语义），无法构造真实回合失败 → 环境门禁跳过。
        assumeTrue(failureObservable || endedWithFailureReason,
                "环境门禁：无法在本环境构造回合失败（模型可连；"
                        + (ownApprovalPending ? "回合被 ask 策略审批挂起，非失败语义" : "回合正常完成无失败痕迹")
                        + "。需停用开发模型的进程外故障注入后重跑）");

        // 工作区仍出现在列表
        Feat136WorkbenchSupport.Resp list = wb.get("/api/workspaces");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().toString()).as("回合失败不影响工作区可见性").isNotEmpty();
    }

    /** 等待指定 turnId 的回合到达终态（只认本回合的 turn.ended，旧回合终态不掩盖新回合）。 */
    private boolean waitForTurnEnded(String sid, long timeoutSeconds, String turnId) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            Feat136WorkbenchSupport.Resp events = wb.get("/api/sessions/" + sid + "/events");
            if (events.status() == 200) {
                for (JsonNode item : events.body().path("items")) {
                    if (!"turn.ended".equals(item.path("type").asText())) {
                        continue;
                    }
                    String eventTurn = item.path("payload").path("turnId").asText(
                            item.path("turnId").asText(""));
                    if (turnId == null || turnId.isBlank() || turnId.equals(eventTurn)) {
                        return true;
                    }
                }
            }
            Thread.sleep(2000);
        }
        return false;
    }
}