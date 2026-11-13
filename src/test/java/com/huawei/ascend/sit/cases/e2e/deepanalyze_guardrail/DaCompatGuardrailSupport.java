package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.MavenArtifact;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * FEAT-109/110/111 批次共享支撑（20261106-feat-109-110-111）：
 * DA 兼容面（guardrail/resume/reflection 三特性同宿主）的 SUT 接线、
 * 受控 HTTP hook 接收端与 HTTP/SSE 读取工具。
 *
 * <p>被测制品：deepanalyze-engine {@value #SUT_VERSION}
 * （agent-solution common@6fd13012b 批次源码构建；勿与其他批次版本混用）。
 * agent 注册名 {@code deepanalyze-guardrail} 为本批新增条目（application-openjiuwen.yml，
 * ENV-3.11：新增不改既有 deepanalyze / deepanalyze-dataplane 条目）。
 */
public final class DaCompatGuardrailSupport {

    public static final String SUT_GROUP = "com.openjiuwen";
    public static final String SUT_ARTIFACT = "deepanalyze-engine";
    public static final String SUT_VERSION = "0.1.3-feat109111";
    public static final String AGENT = "deepanalyze-guardrail";

    private DaCompatGuardrailSupport() {
    }

    /** 模型环境透传（与 FEAT-054/069 线同口径：宿主按 DA_MODEL_* 解析，源为通用 LLM_*）。 */
    public static void bindModelEnvironment(SutStack.AgentBuilder agent) {
        passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
        passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
        passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
        String apiKey = System.getenv("LLM_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            agent.property("LLM_API_KEY", apiKey);
        }
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    /**
     * 本批基础栈：streaming + guardrail agent + redis serviceBinding（栈自管容器，ENV-4.3 口径）。
     * 用例经 {@code customizer} 追加 hooks/compat 配置（用例级开关按前缀直通纪律）。
     */
    public static SutStack.Builder guardrailStack(TestConfig config,
                                                  Function<SutStack.AgentBuilder, SutStack.AgentBuilder> customizer) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                    customizer.apply(agent);
                });
    }

    /* ==================== 受控 HTTP hook 接收端 ==================== */

    /** 一次受控 hook 请求的记录（headers 全量 + body 原文）。 */
    public record RecordedHook(String method, String path, Map<String, List<String>> headers,
                               String body) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .filter(v -> !v.isEmpty())
                    .map(v -> v.get(0))
                    .findFirst().orElse(null);
        }
    }

    /**
     * 进程内受控 HTTP hook 接收端（测试装置，不改业务代码）。
     * 记录全部请求原文；响应由用例经 {@code responder} 编程（默认 200 空体）。
     * 作为 PreToolUse/PostToolUse 等 HTTP hook 的白名单目标：端口动态分配，
     * 用例把 {@code host:port} 写进 deepanalyze.compat.hooks.http-host-whitelist。
     */
    public static final class RecordedHookServer implements AutoCloseable {

        private final HttpServer server;
        private final List<RecordedHook> requests = new CopyOnWriteArrayList<>();
        private final Object responderLock = new Object();
        private int respondStatus = 200;
        private String respondBody = "";
        private final java.util.Map<String, java.util.List<String>> respondHeaders =
                new java.util.HashMap<>();

        public RecordedHookServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", exchange -> handle(exchange));
            this.server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            requests.add(new RecordedHook(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), body));
            int status;
            String responseBody;
            synchronized (responderLock) {
                status = respondStatus;
                responseBody = respondBody;
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().putAll(respondHeaders);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        }

        /** host:port 形态（写 http-host-whitelist 用；DA 白名单按 host 精确匹配，L2 §6.2）。 */
        public String hostPort() {
            return "127.0.0.1:" + server.getAddress().getPort();
        }

        public String baseUrl() {
            return "http://" + hostPort();
        }

        public List<RecordedHook> requests() {
            return List.copyOf(requests);
        }

        public int requestCount() {
            return requests.size();
        }

        public void reset() {
            requests.clear();
            synchronized (responderLock) {
                respondHeaders.clear();
            }
        }

        /** 编程下一响应（hook 结果矩阵构造用：200/非 2xx/body）。 */
        public void respondWith(int status, String body) {
            synchronized (responderLock) {
                this.respondStatus = status;
                this.respondBody = body;
                this.respondHeaders.clear();
            }
        }

        /** 编程 302 重定向（F109-14 白名单重定向格：Location 指向指定 URL）。 */
        public void respondRedirect(String location) {
            synchronized (responderLock) {
                this.respondStatus = 302;
                this.respondBody = "";
                this.respondHeaders.clear();
                this.respondHeaders.put("Location", List.of(location));
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /* ==================== HTTP / SSE ==================== */

    /** SSE 帧（data: 行为主；DA 事件流的载荷是 data: 上的 JSON）。 */
    public record SseFrame(String id, String event, String data) {
        /** data JSON 里取顶层字符串字段（无则 null）。 */
        public String field(String name) {
            if (data == null) {
                return null;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(data);
            return m.find() ? m.group(1) : null;
        }
    }

    /** run-stream 受理（返回 SSE 输入流；调用方负责读帧与关流）。 */
    public static HttpResponse<InputStream> openRunStream(HttpClient http, String base, String body,
                                                           Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/agents/run-stream"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("run-stream 未按 SSE 受理: HTTP " + response.statusCode()
                    + " body=" + new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        return response;
    }

    /** GET /stream/{taskId} 重连观察（Last-Event-ID 游标可选）。 */
    public static HttpResponse<InputStream> openStream(HttpClient http, String base, String taskId,
                                                       String sessionId, String lastEventId, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/agents/stream/" + taskId))
                .timeout(timeout)
                .header("Accept", "text/event-stream")
                .header("X-Da-Session-Id", sessionId)
                .GET();
        if (lastEventId != null && !lastEventId.isBlank()) {
            builder.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<InputStream> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("stream 重连未受理: HTTP " + response.statusCode());
        }
        return response;
    }

    /**
     * 增量读帧：读到 {@code stop} 判真（含该帧）即返回；EOF 也返回。
     * 阻塞读放进调用线程（配合有界轮询模式由调用方起后台线程）。
     */
    public static List<SseFrame> readFrames(HttpResponse<InputStream> response,
                                            java.util.function.Predicate<SseFrame> stop, Duration timeout)
            throws IOException {
        List<SseFrame> frames = new ArrayList<>();
        readFramesInto(response, frames::add, stop, timeout);
        return frames;
    }

    /**
     * 逐帧回调版（run-02 教训：后台线程用 readFrames 时帧只在方法返回后才可读，
     * 主线程轮询共享列表恒空）——每解析出一帧立即回调 {@code sink}，调用方可在另一线程实时消费。
     */
    public static void readFramesInto(HttpResponse<InputStream> response,
                                      java.util.function.Consumer<SseFrame> sink,
                                      java.util.function.Predicate<SseFrame> stop, Duration timeout)
            throws IOException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String id = null;
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (System.currentTimeMillis() > deadline) {
                    sink.accept(new SseFrame(id, event, "<read-timeout>"));
                    break;
                }
                if (line.isBlank()) {
                    if (!data.isEmpty() || event != null || id != null) {
                        SseFrame frame = new SseFrame(id, event, data.toString());
                        sink.accept(frame);
                        id = null;
                        event = null;
                        data.setLength(0);
                        if (stop != null && stop.test(frame)) {
                            break;
                        }
                    }
                    continue;
                }
                if (line.startsWith("id:")) {
                    id = line.substring(3).stripLeading();
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).stripLeading();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
        }
    }

    public static HttpClient newHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static HttpResponse<String> get(HttpClient http, String base, String path,
                                           Duration timeout) throws IOException, InterruptedException {
        return get(http, base, path, timeout, Map.of());
    }

    public static HttpResponse<String> get(HttpClient http, String base, String path,
                                           Duration timeout, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .GET();
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static HttpResponse<String> post(HttpClient http, String base, String path, String body,
                                            Duration timeout) throws IOException, InterruptedException {
        return post(http, base, path, body, timeout, Map.of());
    }

    public static HttpResponse<String> post(HttpClient http, String base, String path, String body,
                                            Duration timeout, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 发起 POST 并把 SSE 流读到 EOF，返回全部 data: 载荷行（按到达序；
     * 兼容 "data: x" 与 "data:x" 两种前缀——与 DaCompatSseSupport/DaDataplaneSupport 同口径）。
     */
    public static List<String> postAndDrainSse(HttpClient http, String base, String path, String body,
                                               Map<String, String> headers, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<InputStream> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("run-stream 未按 SSE 受理: HTTP " + response.statusCode()
                    + " body=" + new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        List<String> payloads = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    payloads.add(line.substring(5).stripLeading());
                }
            }
        }
        return payloads;
    }

    /** SUT exec jar 定位（m2repo 中的批次制品；构建前调用会给出明确报错）。 */
    public static Path sutExecJar() {
        String m2Repo = TestConfig.load().getString("sut.m2.repo",
                System.getProperty("user.home") + "/.m2/repository");
        return new MavenArtifact(SUT_GROUP, SUT_ARTIFACT, SUT_VERSION, "exec").jarPath(m2Repo);
    }
}
