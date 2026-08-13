package com.huawei.ascend.sit.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * FEAT-001 SIT 用 push-notification callback 捕获 mock —— JDK 内置 {@link HttpServer},
 * 只暴露一个端点 {@code POST /callback},捕获 body + headers + 时间戳,供断言"最外层调用方
 * 是否收到 runtime 的回调"。
 *
 * <p><b>用意</b>:在 cascade 场景(caller → deep-research → search-agent → back)里扮演
 * <b>最外层调用方的 receiver</b>,让测试可以断言:
 * <ul>
 *   <li>是否收到 ≥1 次 callback(判定"链是否通到最外层");</li>
 *   <li>callback 里的 {@code X-A2A-Notification-Id} 是否符合幂等契约;</li>
 *   <li>{@code Authorization} header 是否携带最外层 config 提交时的 token
 *       (项目实际 [[push-notification-security-model]]:token-on-callback)。</li>
 * </ul>
 *
 * <p><b>与 {@link MockRemoteAgentServer} 的差别</b>:后者扮演 sub-agent(暴露 card + /a2a),
 * 本 mock 扮演 callback 目标(只暴露 /callback)。职责不同,故独立类。
 *
 * <p><b>行为</b>:所有请求默认返 200 + {@code {"status":"ok"}};如需模拟"receiver 侧鉴权失败"
 * 未来可加 mode(暂不实现,避免过度设计)。
 *
 * <p><b>线程安全</b>:{@link CopyOnWriteArrayList} 存 captured,并发 poll-wait 由
 * {@link #awaitAtLeast(int, long)} 支持。
 *
 * <p><b>lifecycle</b>:{@link AutoCloseable},测试侧 try-with-resources 或 @AfterAll close.
 */
public final class MockCallbackReceiver implements AutoCloseable {

    /** 单次 callback 抓取快照(不可变)。 */
    public static final class CapturedCallback {
        private final String body;
        private final Map<String, List<String>> headers;
        private final long timestampMs;

        CapturedCallback(String body, Map<String, List<String>> headers, long timestampMs) {
            this.body = body;
            this.headers = Map.copyOf(headers);
            this.timestampMs = timestampMs;
        }

        public String body() { return body; }

        public Map<String, List<String>> headers() { return headers; }

        public long timestampMs() { return timestampMs; }

        /** case-insensitive 拿第一个 header 值,不存在返 null. */
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    List<String> vs = e.getValue();
                    return (vs == null || vs.isEmpty()) ? null : vs.get(0);
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "CapturedCallback{ts=" + timestampMs + ", headers=" + headers + ", body=" + body + "}";
        }
    }

    private static final String CALLBACK_PATH = "/callback";

    private final HttpServer server;
    private final String baseUrl;
    private final List<CapturedCallback> captured = new CopyOnWriteArrayList<>();

    private MockCallbackReceiver(HttpServer server, String baseUrl) {
        this.server = server;
        this.baseUrl = baseUrl;
    }

    public static MockCallbackReceiver start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        MockCallbackReceiver holder = new MockCallbackReceiver(server, baseUrl);

        server.createContext(CALLBACK_PATH, exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headerSnapshot = new HashMap<>();
            exchange.getRequestHeaders().forEach(headerSnapshot::put);
            holder.captured.add(new CapturedCallback(
                    new String(body, StandardCharsets.UTF_8),
                    headerSnapshot,
                    System.currentTimeMillis()));
            respond(exchange, 200, "application/json", "{\"status\":\"ok\"}");
        });

        // cached pool:并发多路 callback 抵达不排队;与 MockRemoteAgentServer 保持同款。
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return holder;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 供测试 SendMessage 时填 {@code pushNotificationConfig.url} 的完整 URL. */
    public String callbackUrl() {
        return baseUrl + CALLBACK_PATH;
    }

    public int count() {
        return captured.size();
    }

    public List<CapturedCallback> captured() {
        return List.copyOf(captured);
    }

    /**
     * Poll-wait for at least {@code n} captured callbacks. 200ms 步长,总超时 {@code timeoutMs}.
     *
     * @return true if reached within timeout, false on timeout.
     */
    public boolean awaitAtLeast(int n, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (captured.size() >= n) return true;
            TimeUnit.MILLISECONDS.sleep(200);
        }
        return captured.size() >= n;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
