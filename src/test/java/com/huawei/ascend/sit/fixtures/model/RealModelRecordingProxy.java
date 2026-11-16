/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实模型的"透传记录代理"：把请求**原样**转发给真实大模型，把响应**逐字节**流回被测系统。
 *
 * <p>唯一副作用有两项，都不代替模型产出：① 按请求路径累计收单次数（供"请求级模型路由"判定）；
 * ② 可在回写响应前保持 N 毫秒（供"在途请求不被改道 / 完整返回"的前置）。代理不构造、不改写、
 * 不缓存任何响应体，因此不触犯"测试代码永不 Mock LLM"红线——被测回答始终来自真实模型。</p>
 *
 * <p>上游凭据来自执行环境（WSL 的 {@code ~/.llmrc}），由 runner 透传；本类只读取、不打印。
 * 与 {@code cases.integration.tscript.LlmCountingProxy} 同机制，这里抽成可复用的 fixtures 组件。</p>
 *
 * @since 2026-09-22
 */
public final class RealModelRecordingProxy implements AutoCloseable {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "host", "content-length", "connection", "keep-alive", "transfer-encoding", "upgrade",
            "proxy-connection", "expect");

    private static final Set<String> STRIPPED_RESPONSE_HEADERS = Set.of(
            "content-length", "transfer-encoding", "connection", "keep-alive");

    private final HttpServer server;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String upstreamBase;
    private final String upstreamModel;
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, AtomicInteger> callsByPath = new ConcurrentHashMap<>();
    private final List<RequestRecord> received =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong holdMillis = new AtomicLong();

    /** 一次被转发的请求：路径 + 报文（用于核对会话/模型参数是否随请求送达）。 */
    public record RequestRecord(String path, String body) {
    }

    private RealModelRecordingProxy(String upstreamBase, String upstreamModel) throws IOException {
        this.upstreamBase = upstreamBase.endsWith("/")
                ? upstreamBase.substring(0, upstreamBase.length() - 1)
                : upstreamBase;
        this.upstreamModel = upstreamModel;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::forward);
        this.server.setExecutor(Executors.newCachedThreadPool());
        this.server.start();
    }

    /**
     * 用一份真实凭据启动代理。
     *
     * @param envPrefix 环境变量前缀，例如 {@code LLM_} 或 {@code LLM2_}
     * @return 已就绪的代理实例
     * @throws IllegalStateException 凭据缺失，或上游地址不可解析
     */
    public static RealModelRecordingProxy start(String envPrefix) {
        String upstreamBase = require(envPrefix + "API_BASE");
        String model = System.getenv(envPrefix + "MODEL");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(envPrefix + "MODEL is not available to the test JVM");
        }
        try {
            return new RealModelRecordingProxy(upstreamBase, model);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot start real-model recording proxy for " + envPrefix, exception);
        }
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not available to the test JVM");
        }
        return value;
    }

    /** 交给被测系统的 api-base；被测系统会自行追加 {@code /chat/completions}。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 该代理背后的真实模型名（用于登记到被测系统的模型配置，不用于断言）。 */
    public String modelName() {
        return upstreamModel;
    }

    /** 转发到的真实上游地址（仅供测试侧排查打印，不含凭据）。 */
    public String upstreamBase() {
        return upstreamBase;
    }

    public int requestCount() {
        return calls.get();
    }

    public List<String> pathsSince(int baseline) {
        List<String> paths = new ArrayList<>();
        for (RequestRecord record : recordsSince(baseline)) {
            paths.add(record.path());
        }
        return paths;
    }

    /**
     * 与既有记录端点同名的别名：返回自 baseline 之后收到的请求记录。
     *
     * @param baseline 调用前记录的 {@link #requestCount()}
     * @return 增量请求记录列表
     */
    public List<RequestRecord> requestsSince(int baseline) {
        return recordsSince(baseline);
    }

    private List<RequestRecord> recordsSince(int baseline) {
        synchronized (received) {
            if (baseline < 0 || baseline > received.size()) {
                return List.of();
            }
            return new ArrayList<>(received.subList(baseline, received.size()));
        }
    }

    public Map<String, Integer> callsByPath() {
        Map<String, Integer> snapshot = new ConcurrentHashMap<>();
        callsByPath.forEach((key, value) -> snapshot.put(key, value.get()));
        return snapshot;
    }

    /**
     * 在回写响应前保持的毫秒数；0 表示不停留。
     *
     * <p>用于构造"在途请求"：被测系统的模型调用会真实发生并真实等待，不是伪造响应。</p>
     *
     * @param millis 保持时长（毫秒）
     */
    public void holdResponseMillis(long millis) {
        holdMillis.set(Math.max(0L, millis));
    }

    public void reset() {
        calls.set(0);
        callsByPath.clear();
        synchronized (received) {
            received.clear();
        }
        holdMillis.set(0L);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void forward(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            calls.incrementAndGet();
            callsByPath.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new RequestRecord(path, new String(body, java.nio.charset.StandardCharsets.UTF_8)));
            HttpResponse<InputStream> response = client.send(upstreamRequest(exchange, path, body),
                    HttpResponse.BodyHandlers.ofInputStream());
            long hold = holdMillis.get();
            if (hold > 0) {
                try {
                    Thread.sleep(hold);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            response.headers().map().forEach((name, values) -> copyResponseHeader(exchange, name, values));
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read = in.read(buffer);
                while (read >= 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                    read = in.read(buffer);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            try {
                exchange.sendResponseHeaders(502, -1);
            } catch (RuntimeException ignored) {
                // 响应已开始写出，调用方会看到流中断。
            }
        } finally {
            exchange.close();
        }
    }

    private HttpRequest upstreamRequest(HttpExchange exchange, String path, byte[] body) {
        String relative = path;
        if (upstreamBase.endsWith("/v1") && relative.startsWith("/v1/")) {
            relative = relative.substring("/v1".length());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(upstreamBase + relative));
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (values == null || values.isEmpty() || HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            try {
                builder.header(name, values.get(0));
            } catch (IllegalArgumentException ignored) {
                // 受限头名被丢弃；上游调用仍携带完整报文。
            }
        });
        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        return builder.method(exchange.getRequestMethod(), publisher).build();
    }

    private void copyResponseHeader(HttpExchange exchange, String name, List<String> values) {
        if (name == null || values == null || values.isEmpty()
                || STRIPPED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        try {
            exchange.getResponseHeaders().put(name, new ArrayList<>(values));
        } catch (RuntimeException ignored) {
            // 头透传尽力而为；状态码与响应体才是判据。
        }
    }
}
