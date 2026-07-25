package com.huawei.ascend.sit.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FEAT-004 SIT 用 A2A 远端 mock —— JDK 内置 {@link HttpServer},仅暴露两个端点:
 *
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} —— 返回一份合法的 {@code AgentCard} JSON,
 *       {@link Builder#emptySkills() skills 是否为空}由构造侧决定。用于喂给 agent-runtime 的
 *       Card Cache 拉取路径,让 SUT 侧走真实的解析 + 装配决策。</li>
 *   <li>{@code POST /a2a} —— 行为由 {@link A2aMode} 控制:
 *     <ul>
 *       <li>{@link A2aMode#REJECT}(默认):无脑返 A2A JSON-RPC internal error
 *           ({@code -32603}),同时把 request body + count 记下来,供断言 <b>SUT 是否误路由了远端</b>。
 *           P1.1 「skills=[] 不注入 Tool」场景用它。</li>
 *       <li>{@link A2aMode#STALL_SSE}:设 {@code Content-Type: text/event-stream} 头开个 SSE 流,
 *           <em>不发任何事件</em>,连接保持 {@link Builder#a2aStallMillis} 后主动关闭。用于观测
 *           {@code REMOTE_TIMEOUT} / {@code REMOTE_STREAM_CLOSED} 两档 <b>回灌 LLM</b> 路径
 *           (开发组 2026-07-23 澄清模型 —— 这两档不直接报 FAILED,而是把稳定码作为 tool-result
 *           喂给 LLM,由 LLM 决策终态)。若 SUT 侧先关连接,写 SSE keep-alive 时抛 IOException
 *           → 记入 {@link #a2aClientClosedCount()},作为"SUT 感知远端 stall 并主动断开"的辅助信号。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>用意</b>:两个 mode 覆盖两条不同 spec 分支:
 * <ul>
 *   <li>REJECT:验证 SUT 是否 <em>不该发起</em> A2A 请求(reject 只是防止 SUT 走错更远,零 POST 才是断言核心)。</li>
 *   <li>STALL_SSE:验证 BUG-004 修复后 A2ARemoteAgentClient 能感知 SSE close/EOF,分派
 *       {@code REMOTE_STREAM_CLOSED},回灌 LLM 兜底汇总到终态(不 hang)。</li>
 * </ul>
 *
 * <p><b>线程安全</b>:计数用 {@link AtomicInteger}/{@link AtomicLong},body 列表用 {@link CopyOnWriteArrayList};
 * HttpServer 用 cached thread pool 执行器,支持 STALL_SSE 期间 card 请求并发处理
 * (JDK 默认单调用线程会被 stall 阻塞死)。
 *
 * <p><b>lifecycle</b>:{@link AutoCloseable},测试侧可放进 try-with-resources 或 @AfterAll close.
 *
 * <pre>{@code
 * // P1.1 用法(默认 REJECT,skills=[]):
 * try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
 *         .emptySkills().start()) { ... }
 *
 * // P2 用法(STALL_SSE,非空 skills 让 tool spec 装配):
 * try (MockRemoteAgentServer mock = MockRemoteAgentServer.builder()
 *         .rawSkillsJson("[{\"id\":\"web_search\",\"name\":\"web_search\",\"description\":\"...\"}]")
 *         .stallA2aSse(30_000).start()) { ... }
 * }</pre>
 */
public final class MockRemoteAgentServer implements AutoCloseable {

    /** {@code POST /a2a} 的应答策略。 */
    public enum A2aMode {
        /** 立即返回 A2A JSON-RPC internal error(-32603)。 */
        REJECT,
        /** 打开 SSE 流,不发事件,保持 {@code stallMillis} 后主动关闭。 */
        STALL_SSE,
        /** 打开 SSE 流,发 N 个 chunk 后主动关闭连接(模拟远端崩溃/TCP 断连)。 */
        ABORT_AFTER_CHUNKS
    }

    private final HttpServer server;
    private final String baseUrl;
    private final AtomicInteger a2aPostCount = new AtomicInteger();
    private final List<String> a2aPostBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger cardGetCount = new AtomicInteger();
    private final AtomicInteger a2aClientClosedCount = new AtomicInteger();
    private final AtomicLong a2aLastHoldMillis = new AtomicLong();
    private final AtomicInteger a2aAbortedCount = new AtomicInteger();

    private MockRemoteAgentServer(HttpServer server, String baseUrl) {
        this.server = server;
        this.baseUrl = baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public int a2aPostCount() {
        return a2aPostCount.get();
    }

    public List<String> a2aPostBodies() {
        return Collections.unmodifiableList(a2aPostBodies);
    }

    public int cardGetCount() {
        return cardGetCount.get();
    }

    /**
     * STALL_SSE 模式下,SUT 在 mock 主动收束前关闭 /a2a 连接的次数
     * —— {@code >0} 说明 SUT 在等待过程中主动中止了远端调用(可能是 stream-timeout 触发)。
     */
    public int a2aClientClosedCount() {
        return a2aClientClosedCount.get();
    }

    /**
     * ABORT_AFTER_CHUNKS 模式下,mock 发送 N 个 chunk 后主动关闭连接的次数
     * —— {@code >0} 说明 mock 执行了 abort 逻辑(模拟远端崩溃/TCP 断连)。
     */
    public int a2aAbortedCount() {
        return a2aAbortedCount.get();
    }

    /**
     * STALL_SSE 模式下,最近一次 /a2a 连接从 accept 到 close 的实际持续时长(ms)。
     * <ul>
     *   <li>≈ {@code stallMillis}:mock 达到自身超时才主动关(SUT 侧无 stream-timeout / 未生效)。</li>
     *   <li>&lt; {@code stallMillis}:SUT 在自己的 stream-timeout 内主动关闭。</li>
     * </ul>
     */
    public long a2aLastHoldMillis() {
        return a2aLastHoldMillis.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Card fields are hand-crafted JSON so the mock has zero SDK dependency for the wire
     * shape —— 一次成型、拿真 SearchAgent 卡为模板参照。
     */
    public static final class Builder {
        private String name = "MockRemoteAgent";
        private String description = "SIT mock remote agent";
        private String version = "0.1.0";
        private String skillsJson = "[]";
        private A2aMode a2aMode = A2aMode.REJECT;
        private long a2aStallMillis = 30_000L;
        private int abortChunkCount = 3;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /** {@code skills=[]}:P1.1 关键约束触发点。 */
        public Builder emptySkills() {
            this.skillsJson = "[]";
            return this;
        }

        /**
         * 以原始 JSON 数组形式注入 skills(高级用法,例如 P2 需要非空 skills 让 tool spec 装配)。
         * 传入的字符串必须是合法的 JSON 数组字面量,由调用方保证。
         */
        public Builder rawSkillsJson(String rawJsonArray) {
            this.skillsJson = rawJsonArray;
            return this;
        }

        /**
         * 切换 {@code POST /a2a} 为 {@link A2aMode#STALL_SSE} 模式,并设置 mock 端最长持续时间
         * (ms)—— 期间 SUT 若主动断开,{@link #a2aClientClosedCount()} 递增;超过此时长 mock
         * 主动关闭并计入 {@link #a2aLastHoldMillis()}。
         */
        public Builder stallA2aSse(long stallMillis) {
            this.a2aMode = A2aMode.STALL_SSE;
            this.a2aStallMillis = stallMillis;
            return this;
        }

        /**
         * 切换 {@code POST /a2a} 为 {@link A2aMode#ABORT_AFTER_CHUNKS} 模式,并设置发送多少个
         * SSE chunk 后主动关闭连接(模拟远端崩溃/TCP 断连)。{@link #a2aAbortedCount()} 递增。
         */
        public Builder abortA2aSseAfterChunks(int chunkCount) {
            this.a2aMode = A2aMode.ABORT_AFTER_CHUNKS;
            this.abortChunkCount = chunkCount;
            return this;
        }

        public MockRemoteAgentServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            String baseUrl = "http://127.0.0.1:" + port;
            String a2aUrl = baseUrl + "/a2a";
            String cardJson = buildCardJson(a2aUrl);

            MockRemoteAgentServer holder = new MockRemoteAgentServer(server, baseUrl);
            A2aMode capturedMode = a2aMode;
            long capturedStallMs = a2aStallMillis;
            int capturedAbortChunks = abortChunkCount;

            server.createContext("/.well-known/agent-card.json", exchange -> {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respond(exchange, 405, "text/plain", "Method Not Allowed");
                    return;
                }
                holder.cardGetCount.incrementAndGet();
                respond(exchange, 200, "application/json", cardJson);
            });

            server.createContext("/a2a", exchange -> {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    respond(exchange, 405, "text/plain", "Method Not Allowed");
                    return;
                }
                byte[] body = exchange.getRequestBody().readAllBytes();
                holder.a2aPostCount.incrementAndGet();
                holder.a2aPostBodies.add(new String(body, StandardCharsets.UTF_8));

                if (capturedMode == A2aMode.REJECT) {
                    String reject = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,"
                            + "\"message\":\"mock a2a endpoint should not be routed\"}}";
                    respond(exchange, 200, "application/json", reject);
                    return;
                }
                if (capturedMode == A2aMode.STALL_SSE) {
                    // STALL_SSE:开个 SSE 流,不发事件,周期性写 keep-alive 注释以探测客户端关闭
                    stallSse(exchange, holder, capturedStallMs);
                    return;
                }
                if (capturedMode == A2aMode.ABORT_AFTER_CHUNKS) {
                    // ABORT_AFTER_CHUNKS:发 N 个 chunk 后主动关闭连接,模拟远端崩溃/TCP 断连
                    abortSse(exchange, holder, capturedAbortChunks);
                    return;
                }
            });

            // 用 cached thread pool:STALL_SSE 会长时间占用 handler 线程,单线程默认执行器会
            // 把并发的 card 拉取 / 后续 POST 全部阻塞。
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return holder;
        }

        private String buildCardJson(String a2aUrl) {
            // 以真实 SearchAgent(:18091) card 为模板,替换 skills / name / description / url.
            // 字段完整度按 SDK 反序列化最小可用集,不省略 supportedInterfaces / preferredTransport.
            return "{"
                    + "\"name\":\"" + jsonEscape(name) + "\","
                    + "\"description\":\"" + jsonEscape(description) + "\","
                    + "\"provider\":{\"organization\":\"SIT-Mock\",\"url\":\"\"},"
                    + "\"version\":\"" + jsonEscape(version) + "\","
                    + "\"documentationUrl\":null,"
                    + "\"capabilities\":{\"streaming\":true,\"pushNotifications\":false,"
                    + "\"extendedAgentCard\":false,\"extensions\":[]},"
                    + "\"defaultInputModes\":[\"text\",\"text/plain\"],"
                    + "\"defaultOutputModes\":[\"text\",\"text/plain\"],"
                    + "\"skills\":" + skillsJson + ","
                    + "\"securitySchemes\":{},"
                    + "\"securityRequirements\":[],"
                    + "\"iconUrl\":null,"
                    + "\"supportedInterfaces\":[{"
                    + "\"protocolBinding\":\"JSONRPC\","
                    + "\"url\":\"" + jsonEscape(a2aUrl) + "\","
                    + "\"tenant\":null,"
                    + "\"protocolVersion\":\"1.0\"}],"
                    + "\"signatures\":[],"
                    + "\"url\":\"" + jsonEscape(a2aUrl) + "\","
                    + "\"preferredTransport\":\"JSONRPC\","
                    + "\"additionalInterfaces\":[]"
                    + "}";
        }

        private static String jsonEscape(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder(s.length() + 2);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.toString();
        }
    }

    /**
     * STALL_SSE 处理:发 SSE 头 → 200/chunked → 定期写 keep-alive 注释探测客户端关闭。
     * 写失败 = 客户端已关闭 → 记入 {@link #a2aClientClosedCount};走到 deadline 则 mock 主动关。
     * 关键:必须先 {@code sendResponseHeaders(200, 0)} 让响应头落地,SDK 才能进入 SSE 读循环
     * (0 = chunked,length unknown)。
     */
    private static void stallSse(HttpExchange exchange, MockRemoteAgentServer holder, long stallMs) {
        long start = System.currentTimeMillis();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        try (OutputStream os = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(200, 0);
            os.flush();
            long deadline = start + stallMs;
            long clientClosedFlag = 0;
            while (System.currentTimeMillis() < deadline) {
                try {
                    // SSE comment(: prefix)—— 客户端会忽略,但服务端可用来探测连接是否仍开着
                    os.write(":keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException clientGone) {
                    clientClosedFlag = 1;
                    break;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (clientClosedFlag == 1) {
                holder.a2aClientClosedCount.incrementAndGet();
            }
        } catch (IOException e) {
            // sendResponseHeaders / initial flush 挂了 —— 视作 client-closed 亦 acceptable
            holder.a2aClientClosedCount.incrementAndGet();
        } finally {
            holder.a2aLastHoldMillis.set(System.currentTimeMillis() - start);
        }
    }

    /**
     * ABORT_AFTER_CHUNKS 处理:发 SSE 头 → 200/chunked → 发送 N 个 chunk → 主动关闭连接。
     * 用于模拟远端进程崩溃、TCP 断连、网络错误等中途异常场景。
     * 每个 chunk 是一个合法的 SSE 事件(text chunk),让 SUT 开始处理流后被中断。
     */
    private static void abortSse(HttpExchange exchange, MockRemoteAgentServer holder, int chunkCount) {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        try (OutputStream os = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(200, 0);
            os.flush();

            // 发送 N 个 SSE chunk(模拟开始流式传输)
            for (int i = 0; i < chunkCount; i++) {
                String chunk = "event: text\n"
                        + "data: {\"type\":\"text\",\"text\":\"Chunk " + (i + 1) + " from mock\"}\n\n";
                os.write(chunk.getBytes(StandardCharsets.UTF_8));
                os.flush();

                // 每个 chunk 间隔 100ms,模拟真实流式传输
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 发完 N 个 chunk 后,直接关闭连接(不发 done 事件)—— 模拟远端崩溃
            holder.a2aAbortedCount.incrementAndGet();
            // try-with-resources 会自动关闭 os,触发 TCP 连接断开
        } catch (IOException e) {
            // 任何 IO 异常都算 abort 成功(可能是 SUT 先关了连接)
            holder.a2aAbortedCount.incrementAndGet();
        }
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
