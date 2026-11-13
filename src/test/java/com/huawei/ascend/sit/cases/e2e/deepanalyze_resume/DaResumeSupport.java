package com.huawei.ascend.sit.cases.e2e.deepanalyze_resume;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FEAT-110 任务断点续传 e2e 支撑（本包独立类；不改动共享 {@link DaCompatGuardrailSupport}，
 * 只复用其既有静态方法——SSE 读取/HTTP/客户端）。
 *
 * <p>判据来源（实现前已读，2026-11-10；被测制品 0.1.3-feat109111 = agent-solution common@6fd13012b）：
 * <ul>
 *   <li>端点契约 {@code DaCompatController#resume}：POST /api/agents/resume/{taskId}，
 *       Header {@code X-Da-Session-Id}（属主校验，run-04 教训同款）+ {@code Idempotency-Key}
 *       （必填非空，按 taskId 隔离）；体 {@code {answer, toolCallId?}}，answer strip 后非空
 *       且 strip 值参与幂等摘要（不折叠大小写）。首次受理 202
 *       {@code {taskId,status:"resumed",duplicate:false,streamUrl}}；同键同载荷重放 200
 *       {@code duplicate:true}（重放优先于终态检查，202 发出才固化——受理前失败回滚不烧键）；
 *       同键不同载荷 409 ENGINE_RESUME_CONFLICT；活跃非待答/终态新键 409
 *       ENGINE_TASK_NOT_RESUMABLE；续跑在途新键 409 ENGINE_RESUME_IN_FLIGHT；多待确认省略
 *       目标 409 ENGINE_TOOL_CALL_TARGET_REQUIRED；未知任务/错 session/未知或跨任务 toolCallId
 *       404 "Task not found"；缺键/空白 answer/toolCallId 非原值形态 400 ENGINE_VALIDATION。</li>
 *   <li>status 投影 {@code toTaskResponse}：resumeState ∈ none|awaiting_answer|resume_in_flight、
 *       resumable（运行中且待答）、lastEventId=当前最大已分配事件 id（缓冲 GC 后可 null——
 *       DV-110-04 双栏，本批只断「缓冲在时非空」共同面）。</li>
 *   <li>SSE 帧 {@code writeEvent}：{@code id: <envelopeId>\nevent: <event>\ndata: <JSON>}；
 *       done=业务终态事件；重连重放已含 done（或任务已完成）时以 {@code reconnect_done}
 *       哨兵收尾（不计业务终态——testplan §7.3）；Last-Event-ID 传 envelope id，
 *       重放按 seq 去重、live 泵按 seq 去重。</li>
 *   <li>answer 语义 {@code permissionPayload/isApprovalText}：批准白名单
 *       允许/同意/批准/确认/是/yes/y/ok/approve/approved（strip+小写比较）；其余文本=拒绝并携带原文。</li>
 *   <li>期望常量基线标注：409/400/404/202/200 各错误码与响应体字段均在 0.1.3-feat109111
 *       源码上读出（6fd13012b）；换基线必须重测（implementation.md 期望常量随基线漂移纪律）。</li>
 * </ul>
 */
public final class DaResumeSupport {

    /** 本批 SUT 常量（与 deepanalyze-guardrail 条目同源——110/109/111 同宿主同制品）。 */
    public static final String AGENT = DaCompatGuardrailSupport.AGENT;
    public static final String SUT_VERSION = DaCompatGuardrailSupport.SUT_VERSION;

    /** 业务终态事件名（pumpReplayThenLive 以 "done" 判终态收尾）。 */
    public static final String EVENT_DONE = "done";
    /** 重连重放已完成时的收尾哨兵（不计业务终态）。 */
    public static final String EVENT_RECONNECT_DONE = "reconnect_done";

    private static final Pattern STRING_FIELD = Pattern
            .compile("\"" + "(\\w+)" + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern BOOL_FIELD = Pattern
            .compile("\"" + "(\\w+)" + "\"\\s*:\\s*(true|false)");

    private DaResumeSupport() {
    }

    /* ==================== HTTP 面 ==================== */

    /**
     * POST /resume/{taskId}（幂等键必填；属主头必带——run-04 教训）。
     *
     * @param answer      回答原文（JSON 转义由本方法处理；首尾空白由调用方按用例意图传入——
     *                    SUT strip 后参与摘要，空白差异=规范化等价）
     * @param toolCallId  可选目标（null=省略字段）
     * @param idempotencyKey 幂等键（按用例唯一）
     */
    public static HttpResponse<String> resume(HttpClient http, String base, String taskId,
                                              String session, String idempotencyKey, String answer,
                                              String toolCallId, Duration timeout)
            throws java.io.IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{\"answer\":\"").append(escape(answer)).append('"');
        if (toolCallId != null) {
            body.append(",\"toolCallId\":\"").append(escape(toolCallId)).append('"');
        }
        body.append('}');
        return DaCompatGuardrailSupport.post(http, base, "/api/agents/resume/" + taskId, body.toString(),
                timeout, Map.of("X-Da-Session-Id", session, "Idempotency-Key", idempotencyKey));
    }

    /** 无幂等键变体（400 负向构造用）。 */
    public static HttpResponse<String> resumeNoKey(HttpClient http, String base, String taskId,
                                                   String session, String answer, Duration timeout)
            throws java.io.IOException, InterruptedException {
        return DaCompatGuardrailSupport.post(http, base, "/api/agents/resume/" + taskId,
                "{\"answer\":\"" + escape(answer) + "\"}", timeout,
                Map.of("X-Da-Session-Id", session));
    }

    /** GET /status/{taskId}（属主头必带）。 */
    public static HttpResponse<String> status(HttpClient http, String base, String taskId,
                                              String session, Duration timeout)
            throws java.io.IOException, InterruptedException {
        return DaCompatGuardrailSupport.get(http, base, "/api/agents/status/" + taskId, timeout,
                Map.of("X-Da-Session-Id", session));
    }

    /** 错 session 变体（404 防枚举负向构造用）。 */
    public static HttpResponse<String> statusWrongSession(HttpClient http, String base, String taskId,
                                                          String wrongSession, Duration timeout)
            throws java.io.IOException, InterruptedException {
        return DaCompatGuardrailSupport.get(http, base, "/api/agents/status/" + taskId, timeout,
                Map.of("X-Da-Session-Id", wrongSession));
    }

    /* ==================== JSON 顶层字段提取（黑盒正则，仅顶层有限字段） ==================== */

    /** 顶层字符串字段（无则 null）。 */
    public static String jsonString(String body, String name) {
        if (body == null) {
            return null;
        }
        Matcher m = STRING_FIELD.matcher(body);
        while (m.find()) {
            if (m.group(1).equals(name)) {
                return m.group(2);
            }
        }
        return null;
    }

    /** 顶层布尔字段（无则 null）。 */
    public static Boolean jsonBool(String body, String name) {
        if (body == null) {
            return null;
        }
        Matcher m = BOOL_FIELD.matcher(body);
        while (m.find()) {
            if (m.group(1).equals(name)) {
                return Boolean.parseBoolean(m.group(2));
            }
        }
        return null;
    }

    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /* ==================== 挂起构造 ==================== */

    /** 一次挂起任务的完整观察句柄（流与读取线程保持活动，供 resume 前后继续观察）。 */
    public record SuspendedTask(String sessionId, String taskId, String toolCallId, String askPayload,
                                String fileName, String marker, HttpResponse<InputStream> stream,
                                List<SseFrame> frames, Thread readerThread) {

        /** ask_user 之后新收到的帧（含 resume 后续跑段）。 */
        public List<SseFrame> framesSnapshot() {
            return List.copyOf(frames);
        }

        /** 等读取线程退出（done 后 SUT 关流 / 读超时 / 断连），返回最终帧快照。 */
        public List<SseFrame> awaitReader(long millis) throws InterruptedException {
            readerThread.join(millis);
            return framesSnapshot();
        }

        /** 模拟客户端断连：关输入流，读取线程随之退出（run-02 教训：逐帧回调已实时落列表）。 */
        public void disconnect() throws java.io.IOException, InterruptedException {
            stream.body().close();
            readerThread.join(10_000);
        }
    }

    /**
     * 发起 write_file 任务并等待 ask_user(permission_confirm) 挂起（109 已验证构造）。
     * 返回时 SSE 流仍打开、后台读取线程继续收集——resume 前后共用同一观察序列
     * （run-11 教训：record 必须与 reader 共享同一列表，拷贝快照会让 resume 后帧不可见）。
     */
    public static SuspendedTask startSuspendedTask(HttpClient http, String base, Path workspaceRoot,
                                                   String sessionSuffix, String marker,
                                                   Duration sseReadTimeout, long suspendTimeoutMillis)
            throws Exception {
        String session = "sess-f110-" + sessionSuffix + "-" + System.nanoTime();
        String fileName = "f110-" + sessionSuffix + "-" + System.nanoTime() + ".md";
        String input = "请使用 write_file 工具创建文件 " + fileName + "，内容为：" + marker
                + "。只创建这一个文件，不要执行任何其他工具，完成后直接结束任务。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + escape(input) + "\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(180));

        List<SseFrame> collected = Collections.synchronizedList(new ArrayList<>());

        Thread reader = new Thread(() -> {
            try {
                // run-02 教训：后台线程必须逐帧回调（readFrames 只在 EOF/stop 后返回，轮询恒空）
                DaCompatGuardrailSupport.readFramesInto(stream, collected::add, f -> false,
                        sseReadTimeout);
            } catch (Exception e) {
                collected.add(new SseFrame("reader-error", "reader-error", String.valueOf(e)));
            }
        }, "f110-sse-reader");
        reader.setDaemon(true);
        reader.start();

        String taskId = null;
        String toolCallId = null;
        String askPayload = null;
        long deadline = System.currentTimeMillis() + suspendTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            synchronized (collected) {
                for (SseFrame f : collected) {
                    if (f.data() != null && f.data().contains("\"kind\":\"permission_confirm\"")) {
                        taskId = firstNonBlank(f.field("taskId"), taskId);
                        toolCallId = firstNonBlank(f.field("toolCallId"), toolCallId);
                        askPayload = f.data();
                    }
                }
            }
            if (toolCallId != null) {
                break;
            }
            Thread.sleep(400);
        }
        if (toolCallId == null) {
            reader.join(10_000);
            throw new IllegalStateException(suspendTimeoutMillis / 1000 + "s 内未捕获 ask_user"
                    + "(permission_confirm)；帧数=" + collected.size() + "；末帧="
                    + (collected.isEmpty() ? "<空>" : collected.get(collected.size() - 1).data()));
        }
        return new SuspendedTask(session, taskId, toolCallId, askPayload, fileName, marker, stream,
                collected, reader);
    }

    /* ==================== 断言辅助 ==================== */

    public static boolean anyContains(List<SseFrame> frames, String needle) {
        return frames.stream().anyMatch(f -> f.data() != null && f.data().contains(needle));
    }

    /** event 行等于 name 的帧数（业务 done 与 reconnect_done 由此区分）。 */
    public static long countEvent(List<SseFrame> frames, String name) {
        return frames.stream().filter(f -> name.equals(f.event())).count();
    }

    /** data 内含 "seq": 的帧提取 seq 数值（109 同款；无则跳过）。 */
    public static List<Long> seqSeries(List<SseFrame> frames) {
        return frames.stream()
                .map(SseFrame::data)
                .filter(d -> d != null && d.contains("\"seq\":"))
                .map(d -> d.replaceAll(".*\"seq\":\\s*(\\d+).*", "$1"))
                .filter(s -> s.matches("\\d+"))
                .map(Long::parseLong)
                .toList();
    }

    /** 帧证据落盘（run-03 教训：allure 附件不落盘——直接写文件留证）。 */
    public static void dumpFrames(Path dir, String name, String header, List<SseFrame> frames) {
        try {
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder(header).append("\n---frames---\n");
            for (int i = 0; i < frames.size(); i++) {
                SseFrame f = frames.get(i);
                sb.append('[').append(i).append("] id=").append(f.id()).append(" event=")
                        .append(f.event()).append(" data=").append(f.data()).append('\n');
            }
            Files.writeString(dir.resolve(name), sb.toString());
        } catch (Exception e) {
            // dump 失败不吞主断言，但留痕
            System.err.println("[f110-dump-failed] " + e);
        }
    }

    public static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
