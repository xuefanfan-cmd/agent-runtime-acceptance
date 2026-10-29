package com.huawei.ascend.sit.cases.integration.agent_client_ts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * FEAT-087 受控 A2A 测试服务端（mock-runtime / mock-gateway 行为模拟）。
 * 按方法名消费脚本化响应规则：SSE 帧序列、JSON 结果、HTTP/JSON-RPC 错误、
 * 挂起、中途断流与创建幂等；记录全部请求报文供 wire 断言。
 */
final class Feat087MockA2aServer implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();

    private record Rule(Predicate<String> method, Predicate<ObjectNode> body,
                        UnaryOperator<MockResponse> action, boolean persistent) { }

    /**
     * JSON-RPC 2.0 §4.3：响应 id 必须与请求一致。回包构造时先嵌入占位符，
     * dispatch 时统一替换为实际请求 id（支持预先构造好的帧序列）。
     */
    private static final String REQ_ID_PLACEHOLDER = "$__REQ_ID__";

    private final MockWebServer server = new MockWebServer();
    private final ConcurrentLinkedQueue<Rule> rules = new ConcurrentLinkedQueue<>();
    private final List<ObjectNode> requests = new ArrayList<>();
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicInteger maxInflight = new AtomicInteger();

    Feat087MockA2aServer() {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                // CORS：F087-02 浏览器直连分支从静态页面源发起跨域调用
                if ("OPTIONS".equals(request.getMethod())) {
                    return new MockResponse().setResponseCode(204)
                            .setHeader("Access-Control-Allow-Origin", "*")
                            .setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
                            .setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
                            .setHeader("Access-Control-Max-Age", "600");
                }
                ObjectNode body = parseBody(request);
                record(request, body);
                inflight.incrementAndGet();
                maxInflight.accumulateAndGet(inflight.get(), Math::max);
                try {
                    String method = body.path("method").asText("");
                    String reqId = body.hasNonNull("id") ? body.get("id").asText() : "rpc-mock";
                    Rule rule = pollRule(method, body);
                    MockResponse response = rule != null
                            ? rule.action().apply(new MockResponse())
                            : json(200, jsonRpcError(-32603, "INTERNAL_ERROR", "no rule for " + method));
                    return echoRequestId(response, reqId);
                } finally {
                    inflight.decrementAndGet();
                }
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized void record(RecordedRequest request, ObjectNode body) {
        body.put("__authorization", request.getHeader("Authorization") == null ? "" : request.getHeader("Authorization"));
        body.put("__contentType", request.getHeader("Content-Type") == null ? "" : request.getHeader("Content-Type"));
        requests.add(body);
    }

    private static ObjectNode parseBody(RecordedRequest request) {
        try {
            return (ObjectNode) JSON.readTree(request.getBody().readUtf8());
        } catch (Exception e) {
            ObjectNode node = JSON.createObjectNode();
            node.put("unparsable", request.getBody().readUtf8());
            return node;
        }
    }

    private Rule pollRule(String method, ObjectNode body) {
        for (Rule r : rules) {
            if (r.method().test(method) && r.body().test(body)) {
                if (!r.persistent()) {
                    rules.remove(r);
                }
                return r;
            }
        }
        return null;
    }

    String url() {
        return server.url("/a2a").toString();
    }

    synchronized int requestCount() {
        return requests.size();
    }

    int maxInflight() {
        return maxInflight.get();
    }

    synchronized List<ObjectNode> takeRequests() {
        List<ObjectNode> copy = new ArrayList<>(requests);
        requests.clear();
        return copy;
    }

    void onMethod(String method, UnaryOperator<MockResponse> action) {
        rules.add(new Rule(m -> m.equals(method), b -> true, action, false));
    }

    /** 按请求体谓词匹配（如区分创建与续接：有无 taskId）。 */
    void onWhere(Predicate<ObjectNode> bodyMatch, UnaryOperator<MockResponse> action) {
        rules.add(new Rule(m -> !m.isEmpty(), bodyMatch, action, false));
    }

    /** 清空全部规则（用例间隔离，防止 onEvery 持久规则跨用例泄漏）。 */
    synchronized void resetRules() {
        rules.clear();
    }

    /** 队列式：任意方法按入队顺序消费（同一方法多次不同响应）。 */
    void onNext(UnaryOperator<MockResponse> action) {
        rules.add(new Rule(m -> !m.isEmpty(), b -> true, action, false));
    }

    /**
     * 持久规则：每次匹配都生效、不消费（用于"每次恢复均失败/均成功"等
     * 需要重复命中的故障注入，L2 §3.4 恢复分支）。
     */
    void onEvery(Predicate<ObjectNode> bodyMatch, UnaryOperator<MockResponse> action) {
        rules.add(new Rule(m -> !m.isEmpty(), bodyMatch, action, true));
    }

    // ---------- 响应构造 ----------

    static MockResponse json(int status, JsonNode body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setHeader("Access-Control-Allow-Origin", "*")
                .setBody(JSON.valueToTree(body).toString());
    }

    static MockResponse sse(List<String> frames) {
        StringBuilder body = new StringBuilder();
        for (String frame : frames) {
            body.append(frame);
        }
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("Access-Control-Allow-Origin", "*")
                .setBody(body.toString());
    }

    /** 中途断流：发送部分帧后断开连接（无终态帧）。 */
    static MockResponse sseThenDisconnect(List<String> frames) {
        StringBuilder body = new StringBuilder();
        for (String frame : frames) {
            body.append(frame);
        }
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body.toString(), 8)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
    }

    /** 连接建立即断开（请求结果完全未知）。 */
    static MockResponse disconnectAtStart() {
        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
    }

    /** 挂起 delayMs 后再返回响应（模拟慢响应 / 创建结果不明）。 */
    static MockResponse hangThen(long delayMs, MockResponse response) {
        return response.setHeadersDelay(delayMs, TimeUnit.MILLISECONDS);
    }

    // ---------- wire 报文构造（对齐 L2 §5 样例） ----------

    static ObjectNode rpcResult(Object result) {
        ObjectNode n = JSON.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("id", REQ_ID_PLACEHOLDER);
        n.set("result", JSON.valueToTree(result));
        return n;
    }

    static ObjectNode jsonRpcError(int code, String message) {
        return jsonRpcError(code, message, null);
    }

    static ObjectNode jsonRpcError(int code, String message, String stableCode) {
        ObjectNode n = JSON.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("id", REQ_ID_PLACEHOLDER);
        ObjectNode err = n.putObject("error");
        err.put("code", code);
        err.put("message", message);
        if (stableCode != null) {
            err.putObject("data").put("code", stableCode);
        }
        return n;
    }

    /** 将响应体中的 id 占位符替换为实际请求 id（JSON-RPC 2.0 §4.3）。 */
    private static MockResponse echoRequestId(MockResponse response, String reqId) {
        okio.Buffer buffer = response.getBody();
        if (buffer == null) {
            return response;
        }
        // getBody() 返回副本，读取不影响原响应
        String bodyText = buffer.readUtf8();
        if (!bodyText.contains(REQ_ID_PLACEHOLDER)) {
            return response;
        }
        // 含占位符的回包均经 json()/sse() 构造（HTTP 200）
        // 含占位符的回包均经 json()/sse() 构造（HTTP 200）；保留延迟与套接字策略
        return new MockResponse().setResponseCode(200)
                .setHeaders(response.getHeaders())
                .setBody(bodyText.replace(REQ_ID_PLACEHOLDER, reqId))
                .setHeadersDelay(response.getHeadersDelay(java.util.concurrent.TimeUnit.MILLISECONDS),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .setBodyDelay(response.getBodyDelay(java.util.concurrent.TimeUnit.MILLISECONDS),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .setSocketPolicy(response.getSocketPolicy());
    }

    static String sseOf(JsonNode result) {
        return "event: jsonrpc\ndata: " + JSON.valueToTree(result).toString() + "\n\n";
    }

    static String sseComment(String text) {
        return ": " + text + "\n\n";
    }

    /** statusUpdate 流帧；interrupt 可空。 */
    static ObjectNode statusUpdate(String taskId, String contextId, String state, String text, JsonNode interrupt) {
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode status = frame.putObject("statusUpdate");
        status.put("taskId", taskId).put("contextId", contextId).put("kind", "status-update");
        ObjectNode st = status.putObject("status");
        st.put("state", state);
        ObjectNode message = st.putObject("message");
        message.put("role", "ROLE_AGENT");
        // A2A 协议 parts 为数组（SDK collectPartsText 仅识别数组形态）
        message.putArray("parts").addObject().put("text", text == null ? "" : text);
        ObjectNode metadata = message.putObject("metadata");
        if (interrupt != null) {
            metadata.set("_interrupt", interrupt);
        }
        return frame;
    }

    /** GetTask / 订阅快照：result 为 Task 本体（L2 §5.8）。 */
    static ObjectNode task(String taskId, String contextId, String state, String text, JsonNode interrupt) {
        ObjectNode task = JSON.createObjectNode();
        task.put("id", taskId).put("contextId", contextId);
        ObjectNode st = task.putObject("status");
        st.put("state", state);
        ObjectNode message = st.putObject("message");
        message.put("role", "ROLE_AGENT");
        message.putArray("parts").addObject().put("text", text == null ? "" : text);
        ObjectNode metadata = message.putObject("metadata");
        if (interrupt != null) {
            metadata.set("_interrupt", interrupt);
        }
        return task;
    }

    /** OpenAI 风格 toolCall 流帧（TOOL_CALL_START / ARGS / END / RESULT）。 */
    static ObjectNode openAiToolFrame(String phase, String taskId, String contextId,
                                      String toolCallId, String toolName, String argsDelta, String result) {
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode payload = frame.putObject("artifactUpdate");
        payload.put("taskId", taskId).put("contextId", contextId);
        ObjectNode event = payload.putObject("metadata").putObject("agentEvent");
        event.put("type", "TOOL_CALL_" + phase);
        event.putObject("source").put("agentId", "root-agent").put("taskId", taskId);
        if (toolCallId != null) {
            event.put("toolCallId", toolCallId);
        }
        if (toolName != null) {
            event.put("toolName", toolName);
        }
        if (argsDelta != null) {
            event.put("argsDelta", argsDelta);
        }
        if (result != null) {
            event.put("result", result);
        }
        return frame;
    }

    @Override public void close() {
        try {
            server.shutdown();
        } catch (IOException e) {
            // 关闭失败不影响测试断言
        }
    }
}
