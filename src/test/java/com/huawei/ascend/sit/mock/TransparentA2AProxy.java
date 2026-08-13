package com.huawei.ascend.sit.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * 透明 HTTP 代理,用于在 SIT 场景抓取 deep-research → search-agent(或任意 upstream)
 * 的整条 HTTP 载荷 —— 不依赖 SUT 日志.
 *
 * <p><b>用途</b>:BUG-009 outbound 探测.把代理 port 灌给 deep-research 的
 * {@code SEARCH_AGENT_URL},让所有出站 A2A 调用先落到代理,代理:
 * <ol>
 *   <li>把 method + path + headers + body 完整落到 {@link CapturedExchange};</li>
 *   <li>透传给真 upstream(如真 search-agent),原样返回响应体;</li>
 *   <li>同步捕获响应状态 + headers + body,便于断言.</li>
 * </ol>
 *
 * <p><b>断言场景示例</b>:
 * <pre>
 *   assertThat(proxy.captured())
 *       .filteredOn(e -> "POST".equals(e.method()) &amp;&amp; e.path().endsWith("/a2a"))
 *       .anySatisfy(e -&gt; assertThat(e.requestBody()).contains("taskPushNotificationConfig"));
 * </pre>
 *
 * <p><b>Wire 侧考虑</b>:
 * <ul>
 *   <li>只支持 http:// (SIT 全是 loopback,无 TLS);</li>
 *   <li>透明代理只改 Host header 让 upstream 认 URL,其余 header 一律透传;</li>
 *   <li>不做流式 chunk 处理 —— A2A JSON-RPC POST/GET 都是 request-response 一发一收.</li>
 * </ul>
 */
public final class TransparentA2AProxy implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TransparentA2AProxy.class.getName());

    /**
     * 一次完整 HTTP 交换记录 —— 请求 + 响应对.
     * requestHeaders/responseHeaders 只记录关键 header 集合(Content-Type/Content-Length
     * /Authorization/X-A2A-*),避免日志爆炸.
     */
    public record CapturedExchange(
            long timestampMs,
            String method,
            String path,
            String requestBody,
            Map<String, List<String>> requestHeaders,
            int responseStatus,
            String responseBody,
            Map<String, List<String>> responseHeaders) {}

    private final HttpServer server;
    private final String baseUrl;
    private final String upstreamBaseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final List<CapturedExchange> captured = new CopyOnWriteArrayList<>();

    /**
     * upstream 侧可能用 localhost / 127.0.0.1 / hostname 三种形式指向自己的 bind port,
     * agent-card 里回填哪种由 SUT `public-url` 或 bind 决定. rewrite 时三种都要替换.
     */
    private final List<String> upstreamUrlVariants;

    private TransparentA2AProxy(HttpServer server, String baseUrl, String upstreamBaseUrl) {
        this.server = server;
        this.baseUrl = baseUrl;
        this.upstreamBaseUrl = upstreamBaseUrl;
        int upstreamPort;
        try {
            upstreamPort = URI.create(upstreamBaseUrl).getPort();
        } catch (Exception e) {
            upstreamPort = -1;
        }
        if (upstreamPort > 0) {
            this.upstreamUrlVariants = List.of(
                    "http://localhost:" + upstreamPort,
                    "http://127.0.0.1:" + upstreamPort);
        } else {
            this.upstreamUrlVariants = List.of(upstreamBaseUrl);
        }
    }

    public String baseUrl() { return baseUrl; }
    public List<CapturedExchange> captured() { return List.copyOf(captured); }

    /**
     * 只返回带 taskPushNotificationConfig 的请求体 —— 便于用例直接断言.
     * 空列表意味着 SUT outbound 从未附着 pushConfig(BUG-009 outbound smoking gun).
     */
    public List<CapturedExchange> capturedWithPushConfig() {
        return captured.stream()
                .filter(e -> e.requestBody() != null
                        && e.requestBody().contains("taskPushNotificationConfig"))
                .toList();
    }

    @Override
    public void close() { server.stop(0); }

    public static TransparentA2AProxy startForwarding(String upstreamBaseUrl) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        String normalizedUpstream = upstreamBaseUrl.endsWith("/")
                ? upstreamBaseUrl.substring(0, upstreamBaseUrl.length() - 1)
                : upstreamBaseUrl;

        TransparentA2AProxy proxy = new TransparentA2AProxy(server, baseUrl, normalizedUpstream);

        server.createContext("/", proxy::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        LOG.info("[proxy] listening at " + baseUrl + " → forwarding to " + normalizedUpstream);
        return proxy;
    }

    private void handle(HttpExchange exchange) throws IOException {
        long t0 = System.currentTimeMillis();
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getRawPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String fullPath = rawQuery == null ? rawPath : rawPath + "?" + rawQuery;

        byte[] reqBodyBytes = exchange.getRequestBody().readAllBytes();
        String reqBodyStr = new String(reqBodyBytes, StandardCharsets.UTF_8);
        Map<String, List<String>> reqHeaders = Map.copyOf(exchange.getRequestHeaders());

        HttpRequest.Builder upstreamReq = HttpRequest.newBuilder(
                        URI.create(upstreamBaseUrl + fullPath))
                .timeout(Duration.ofSeconds(60));
        for (Map.Entry<String, List<String>> h : reqHeaders.entrySet()) {
            String name = h.getKey();
            if (name == null) continue;
            String lower = name.toLowerCase();
            if (lower.equals("host") || lower.equals("content-length")
                    || lower.equals("connection") || lower.equals("expect")
                    || lower.equals("transfer-encoding") || lower.equals("upgrade")) {
                continue;
            }
            for (String v : h.getValue()) upstreamReq.header(name, v);
        }
        if ("GET".equalsIgnoreCase(method)) {
            upstreamReq.GET();
        } else if ("DELETE".equalsIgnoreCase(method) && reqBodyBytes.length == 0) {
            upstreamReq.DELETE();
        } else {
            upstreamReq.method(method, HttpRequest.BodyPublishers.ofByteArray(reqBodyBytes));
        }

        HttpResponse<byte[]> upstreamResp;
        try {
            upstreamResp = httpClient.send(upstreamReq.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            String err = "[proxy] upstream fail: " + e.getClass().getSimpleName() + " " + e.getMessage();
            LOG.warning(err);
            captured.add(new CapturedExchange(t0, method, fullPath, reqBodyStr, reqHeaders,
                    -1, err, Map.of()));
            byte[] out = err.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, out.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            return;
        }

        String respBodyStr = new String(upstreamResp.body(), StandardCharsets.UTF_8);
        // agent-card discovery 返 body 里 upstream 的 url/interfaces url 字段全是真 upstream 地址,
        // deep-research 会拿这些 url 做后续 SendMessage 直连而<b>绕过 proxy</b>. 必须把 upstream URL
        // 替换成 proxy URL,才能让所有出站 A2A 请求都过 proxy 抓包.
        // 只对 200 响应且是 JSON 内容做替换,避免误伤二进制 / 错误响应.
        // 只对 200 响应做 URL 变体替换 —— agent-card 里 upstream 的 url/interfaces url 可能以
        // localhost:PORT 或 127.0.0.1:PORT 两种形式出现,取决于 upstream 侧 PUBLIC_URL 配置和
        // 内部 bind 决定,任一变体命中都要 rewrite 成 proxy URL,才能让 deep-research 后续 SendMessage
        // 直接命中 proxy 而不是真 upstream.
        String rewrittenBodyStr = respBodyStr;
        byte[] rewrittenBodyBytes = upstreamResp.body();
        if (upstreamResp.statusCode() == 200) {
            boolean changed = false;
            for (String variant : upstreamUrlVariants) {
                if (rewrittenBodyStr.contains(variant)) {
                    rewrittenBodyStr = rewrittenBodyStr.replace(variant, baseUrl);
                    changed = true;
                }
            }
            if (changed) {
                rewrittenBodyBytes = rewrittenBodyStr.getBytes(StandardCharsets.UTF_8);
            }
        }
        Map<String, List<String>> respHeaders = Map.copyOf(upstreamResp.headers().map());
        captured.add(new CapturedExchange(t0, method, fullPath, reqBodyStr, reqHeaders,
                upstreamResp.statusCode(), rewrittenBodyStr, respHeaders));

        for (Map.Entry<String, List<String>> h : respHeaders.entrySet()) {
            String name = h.getKey();
            if (name == null) continue;
            String lower = name.toLowerCase();
            if (lower.equals("content-length") || lower.equals("transfer-encoding")
                    || lower.equals("connection")) continue;
            for (String v : h.getValue()) exchange.getResponseHeaders().add(name, v);
        }
        exchange.sendResponseHeaders(upstreamResp.statusCode(), rewrittenBodyBytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(rewrittenBodyBytes); }
    }
}
