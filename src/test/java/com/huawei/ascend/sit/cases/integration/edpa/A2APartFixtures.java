/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FEAT-036（A2A Part 多模态数据传输）共享 fixture（RawFileFactory + Part 构造 + raw JSON-RPC）。
 *
 * <p><b>依据</b>：docs/cases/FEAT-036-a2a-part-file-and-data-transfer.md（下称测试设计）
 * §3.3 Fixture 清单、§4 覆盖矩阵、§6 子用例 G/W/T。契约取值逐字对齐 L2 Feat-Func-036
 * §2.2 异常响应表 / §4.1 A2aPartLimits（docs 私仓 b1f50a6c）。
 *
 * <p><b>wire 事实</b>（SDK 1.0.0.Final）：JSON-RPC method 名为 SendMessage /
 * SendStreamingMessage（非 1.0 前的 message/send 斜杠形，见 transport/WireRequestRenderer 注）；
 * endpoint 为 baseUrl + "/a2a"；Part 为扁平判别成员 text/raw/url/data（L2 §5.1，A2A v1.0
 * 扁平化），共享元数据 filename/mediaType。
 *
 * <p>本类只提供测试侧构造，不依赖产品内部类（黑盒边界，测试设计 §2）。
 */
final class A2APartFixtures {

    /** 测试命名前缀（测试设计 §3.5：contextId 带 feat036 前缀避免撞 key，T-M20）。 */
    static final String CTX_PREFIX = "ctx-feat036-";

    /** A2A SDK 1.0.0.Final wire 方法名。 */
    static final String METHOD_SEND = "SendMessage";
    static final String METHOD_SEND_STREAMING = "SendStreamingMessage";

    /** 单 raw 解码后字节上限 = 10MB（L2 §4.1 DEFAULT_MAX_RAW_BYTES，特性档 §2 MUST）。 */
    static final int MAX_RAW_BYTES = 10 * 1024 * 1024;
    /** 单次请求 Part 总数上限（L2 §4.1 DEFAULT_MAX_PARTS，特性档 §2 MUST）。 */
    static final int MAX_PARTS = 100;
    /** 单 text/data Part 上限 = 1MB（L2 §4.1，特性档未载——测试设计 §9 存疑 2）。 */
    static final int MAX_TEXT_DATA_BYTES = 1024 * 1024;
    /** 单条 A2A 消息（请求体）大小上限 = 100MB（L2 §6.1 max-message-bytes，特性档未载）。 */
    static final int MAX_MESSAGE_BYTES = 100 * 1024 * 1024;

    /** -32602 InvalidParams（特性档 §3；断言锚定 code 数值与 message 关键短语，见 §9 存疑 6）。 */
    static final int CODE_INVALID_PARAMS = -32602;

    /** error message 关键短语（L2 §7.3 错误表面验收表逐字，T-M1d）。 */
    static final String MSG_MUTEX = "exactly one of text/raw/url/data";
    static final String MSG_BASE64 = "not valid base64";
    static final String MSG_RAW_LIMIT = "exceeds max-raw-bytes";
    static final String MSG_PARTS_LIMIT = "exceeds max-parts";
    static final String MSG_TEXT_DATA_LIMIT = "exceeds max-text-data-bytes";
    static final String MSG_URL_SCHEME = "must use http or https scheme";
    static final String MSG_URL_BLANK = "must be a non-blank string";
    static final String MSG_AT_LEAST_ONE = "must contain at least one part";
    static final String MSG_FILENAME_METADATA_LIMIT = "exceeds size limit";

    /** 业务层文件类型拒绝错误码（特性档 §2/§3，业务 Agent 侧产生、runtime 不吞掉）。 */
    static final String CODE_EDP_FILE_003 = "EDP-FILE-003";

    /** 共享 HTTP 客户端（长超时：SUT 冷启动 + 100MB 量级报文传输）。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 合法 https url 占位（非真实资源——R-RT-2 约束 runtime 不下载，仅透传）。 */
    static final String URL_HTTPS = "https://files.example.com/credit/report-2026Q3.pdf";
    /** 合法 http url 占位（http/https 均在白名单内，L2 §4.1）。 */
    static final String URL_HTTP = "http://files.example.com/credit/report-2026Q3.pdf";

    private static final ObjectMapper JSON = new ObjectMapper();

    private A2APartFixtures() {
    }

    // ------------------------------------------------------------------
    // Part 构造（扁平判别成员，L2 §5.1）
    // ------------------------------------------------------------------

    static Map<String, Object> textPart(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", text);
        return part;
    }

    /** inline base64 字节 Part。 */
    static Map<String, Object> rawPart(byte[] bytes) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("raw", base64(bytes));
        return part;
    }

    static Map<String, Object> rawPartBase64(String base64) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("raw", base64);
        return part;
    }

    /** 文件 URL 引用 Part（runtime 不下载、不替换为字节，特性档 §5.1.2）。 */
    static Map<String, Object> urlPart(String url) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("url", url);
        return part;
    }

    /** 结构化 JSON Part。 */
    static Map<String, Object> dataPart(Object value) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("data", value);
        return part;
    }

    /** 共享文件元数据（附加到任一 Part 上，原样保留语义）。 */
    static Map<String, Object> withMeta(Map<String, Object> part, String filename, String mediaType) {
        if (filename != null) {
            part.put("filename", filename);
        }
        if (mediaType != null) {
            part.put("mediaType", mediaType);
        }
        return part;
    }

    /** part 级 metadata object（既有 parseMetadata 语义，L2 §2.2）。 */
    static Map<String, Object> withMetadata(Map<String, Object> part, Map<String, Object> metadata) {
        part.put("metadata", metadata);
        return part;
    }

    // ------------------------------------------------------------------
    // 确定性字节与 base64（RawFileFactory）
    // ------------------------------------------------------------------

    /** 固定 seed 的确定性字节（可复现，T-M6），可选嵌入唯一 marker 供泄漏探针（SEC #31）。 */
    static byte[] deterministicBytes(int size, String marker) {
        byte[] bytes = new byte[size];
        new Random(3612036L + size).nextBytes(bytes);
        if (marker != null && size > marker.length()) {
            byte[] head = marker.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(head, 0, bytes, 0, head.length);
        }
        return bytes;
    }

    static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 长度为 len 的 ASCII 填充串（data/text 边界用，序列化后恰 len 字节）。 */
    static String asciiFill(int len) {
        byte[] bytes = new byte[len];
        Arrays.fill(bytes, (byte) 'x');
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------
    // raw JSON-RPC 直发（绕过 SDK 便利层，断言 wire 层表面，T-M21）
    // ------------------------------------------------------------------

    /** 构造 SendMessage / SendStreamingMessage 请求体（parts 全量自定义）。 */
    static String jsonRpc(String method, List<Map<String, Object>> parts) {
        ObjectNode root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", "feat036-" + UUID.randomUUID());
        root.put("method", method);
        ObjectNode params = root.putObject("params");
        ObjectNode message = params.putObject("message");
        message.put("role", "ROLE_USER");
        message.put("messageId", "msg-feat036-" + UUID.randomUUID());
        message.put("contextId", CTX_PREFIX + UUID.randomUUID().toString().substring(0, 8));
        ArrayNode partsNode = message.putArray("parts");
        for (Map<String, Object> part : parts) {
            ObjectNode node = partsNode.addObject();
            part.forEach((key, value) -> node.set(key, JSON.valueToTree(value)));
        }
        return root.toString();
    }

    /** 解析 JSON-RPC 响应的 error.code（无 error 时返回 null）。 */
    static Integer errorCode(String responseBody) throws IOException {
        JsonNode error = JSON.readTree(responseBody).path("error");
        return error.isMissingNode() || error.isNull() ? null : error.path("code").asInt();
    }

    /** 解析 JSON-RPC 响应的 error.message（无 error 时返回空串）。 */
    static String errorMessage(String responseBody) throws IOException {
        return JSON.readTree(responseBody).path("error").path("message").asText("");
    }

    /** 解析 JSON-RPC 响应的 result.task.id（SDK SendMessageResponse 包装为 result.task；无 result 时返回 null）。 */
    static String resultId(String responseBody) throws IOException {
        JsonNode id = JSON.readTree(responseBody).path("result").path("task").path("id");
        return id.isMissingNode() || id.isNull() ? null : id.asText();
    }

    /** 解析 JSON-RPC 响应的 result.task 节点（缺失时返回 missing node，断言侧自行展开）。 */
    static JsonNode resultTask(String responseBody) throws IOException {
        return JSON.readTree(responseBody).path("result").path("task");
    }

    // ------------------------------------------------------------------
    // HTTP 直发（含 chunked 行，val.body-over-100mb-413）
    // ------------------------------------------------------------------

    /** POST baseUrl + "/a2a"（带 Content-Length；ofByteArray 保证非 chunked，CL 缺失会被 SDK 以 413 拒绝）。 */
    static java.net.http.HttpResponse<String> post(String baseUrl, String body)
            throws IOException, InterruptedException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(baseUrl + "/a2a"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .build();
        return HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * POST baseUrl + "/a2a"（LLM 驱动阻塞单轮：规划 + 委托 + 回程）。
     * 拓扑 B（edp-agent 宿主）出站用例专用——与 postUrl 同款超时预算：
     * 单轮实测 ~40s（glm-5.3），预留 SDK 3 次退避重试与模型侧偶发空响应重拉的余量。
     */
    static java.net.http.HttpResponse<String> postSlow(String baseUrl, String body)
            throws IOException, InterruptedException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(baseUrl + "/a2a"))
                .timeout(Duration.ofSeconds(240))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .build();
        return HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * POST baseUrl + "/a2a"（chunked：不声明 Content-Length）。
     * 对应测试设计 #20 val.body-over-100mb-413 行 1「CL 缺失 → HTTP 413」（L2 §2.2 步骤 1）。
     */
    static java.net.http.HttpResponse<String> postChunked(String baseUrl, byte[] bodyBytes)
            throws IOException, InterruptedException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(baseUrl + "/a2a"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArrays(
                        Collections.singletonList(bodyBytes)))
                .build();
        return HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /** POST 任意绝对 URL（带 Content-Length），multipart 入口（custom-rest query-path）用。 */
    static java.net.http.HttpResponse<String> postUrl(String url, String contentType, byte[] bodyBytes)
            throws IOException, InterruptedException {
        return postUrl(url, contentType, bodyBytes, 240);
    }

    /**
     * POST 任意绝对 URL（带 Content-Length），multipart 入口（custom-rest query-path）用。
     *
     * <p>默认 240s 预算按「单轮规划 + 委托 + 回程 ~40s」设计（glm-5.3，12-08 实测）；
     * 大附件（10MB 量级）或多轮规划场景经重载放宽（2026-09-14 报告 §四.1：
     * 本环境 LLM 单轮实测 90–120s，240s 对 10MB 附件用例不足）。
     *
     * @param timeoutSeconds 客户端整体超时预算（秒）
     */
    static java.net.http.HttpResponse<String> postUrl(String url, String contentType, byte[] bodyBytes,
            int timeoutSeconds) throws IOException, InterruptedException {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", contentType)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();
        return HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // multipart/form-data 报文构造（MultipartRequestBuilder）
    // ------------------------------------------------------------------

    /** multipart 文件字段。 */
    record FileField(String fieldName, String filename, String contentType, byte[] bytes) {
    }

    /**
     * 构造 multipart/form-data 报文。
     *
     * @return contentType（含 boundary）与 body 字节
     */
    static Map.Entry<String, byte[]> multipart(List<FileField> files, List<String[]> formFields) {
        String boundary = "feat036-" + UUID.randomUUID();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            for (String[] field : formFields) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                        + field[0] + "\"\r\n\r\n" + field[1] + "\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            for (FileField file : files) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                        + file.fieldName() + "\"; filename=\"" + file.filename() + "\"\r\n"
                        + "Content-Type: " + file.contentType() + "\r\n\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.write(file.bytes());
                out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return Map.entry("multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    // ------------------------------------------------------------------
    // FileServerStub（测试设计 §3.3：强制 token 凭据、无凭据 401、记录全部请求，动态端口）
    // ------------------------------------------------------------------

    /**
     * 文件服务桩：仅接受带合法 token 的 GET；无 token 一律 401；记录全部请求。
     *
     * <p>承载 #5/#24「runtime 不下载」与 #32「资源侧鉴权」的观察面（网络边界，T-M15）。
     */
    static final class FileServerStub implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;
        private final String token;
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final List<String> recordedRequests = new CopyOnWriteArrayList<>();
        private final List<Boolean> recordedTokenValid = new CopyOnWriteArrayList<>();

        FileServerStub(String token) throws IOException {
            this.token = token;
            this.server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/files", this::handle);
            this.server.start();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String target = exchange.getRequestURI().toString();
            boolean tokenValid = target.contains("token=" + token);
            recordedRequests.add(target);
            recordedTokenValid.add(tokenValid);
            if (!tokenValid) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            byte[] content = files.get(name);
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }

        void publish(String name, byte[] content) {
            files.put(name, content);
        }

        /** 该文件的带 token 下载 URL（token 仅下游 Workflow 客户端持有，runtime 无从获得）。 */
        String url(String name) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/files/" + name + "?token=" + token;
        }

        /** 全部请求（含无凭据请求；用于断言 runtime 未发起下载）。 */
        List<String> requests() {
            return List.copyOf(recordedRequests);
        }

        /** 用例间状态复位（请求日志；与 GatewayStub.reset() 同理，见其注释）。 */
        void reset() {
            recordedRequests.clear();
            recordedTokenValid.clear();
        }

        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // GatewayStub（测试设计 §3.3：下游 A2A 网关桩——记录 wire、可注入 5xx、重复投递计数，动态端口）
    // ------------------------------------------------------------------

    /**
     * 下游 A2A 网关桩：模拟低码 Workflow 侧的 A2A Agent。
     *
     * <p>承载 OUT 组判据面（#23 出站 wire、#24 格式保持、#25 重复投递计数/耗尽回填）。
     * 可注入「前 N 次请求 503」模拟对端故障（测试自有桩，产品 demo 无故障注入能力，
     * 见测试设计 §3.4 选型豁免记录）。
     */
    static final class GatewayStub implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;
        private final List<String> requestBodies = new CopyOnWriteArrayList<>();
        private final AtomicInteger remainingFailures = new AtomicInteger();
        private final AtomicInteger totalRequests = new AtomicInteger();

        GatewayStub() throws IOException {
            this.server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/a2a", this::handle);
            this.server.start();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            // SDK A2ACardResolver 在发送前 GET <url>/.well-known/agent-card.json 解析 AgentCard；
            // 无合法卡片则 discovery 失败、registry 不收录 → 委托时报 "Unknown remote agent"（12-08 实测）。
            // 卡片字段与真实 agent-runtime 服务对齐（supportedInterfaces/protocolVersion 等为 SDK 反序列化必填）。
            String path = exchange.getRequestURI().toString();
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && (path.endsWith("/.well-known/agent-card.json")
                            || path.endsWith("/.well-known/agent.json"))) {
                byte[] card = ("{\"name\":\"gateway-stub\",\"description\":\"FEAT-036 downstream A2A gateway stub\","
                        + "\"provider\":{\"organization\":\"sit-test\",\"url\":\"\"},\"version\":\"1.0.0\","
                        + "\"documentationUrl\":null,"
                        + "\"capabilities\":{\"streaming\":true,\"pushNotifications\":false,"
                        + "\"extendedAgentCard\":false,\"extensions\":[]},"
                        + "\"defaultInputModes\":[\"text\",\"text/plain\"],"
                        + "\"defaultOutputModes\":[\"text\",\"text/plain\"],"
                        + "\"skills\":[{\"id\":\"stub\",\"name\":\"stub\",\"description\":\"record wire\","
                        + "\"tags\":[],\"examples\":[],\"inputModes\":[\"text\",\"text/plain\"],"
                        + "\"outputModes\":[\"text\",\"text/plain\"],\"securityRequirements\":[]}],"
                        + "\"securitySchemes\":{},\"securityRequirements\":[],\"iconUrl\":null,"
                        + "\"supportedInterfaces\":[{\"protocolBinding\":\"JSONRPC\",\"url\":\""
                        + a2aUrl() + "\",\"tenant\":null,\"protocolVersion\":\"1.0\"}],"
                        + "\"signatures\":[],\"url\":\"" + a2aUrl() + "\",\"preferredTransport\":\"JSONRPC\","
                        + "\"additionalInterfaces\":[]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, card.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(card);
                }
                return;
            }
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            totalRequests.incrementAndGet();
            if (remainingFailures.getAndUpdate(v -> v > 0 ? v - 1 : v) > 0) {
                // 对端 5xx：触发 L2 §2.4 步骤 5 的中断恢复重放路径
                byte[] payload = "{\"error\":\"injected-503\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
                return;
            }
            requestBodies.add(body);
            String request = exchange.getRequestURI().toString();
            String rpcId = "unknown";
            try {
                rpcId = JSON.readTree(body).path("id").asText("unknown");
            } catch (IOException ignored) {
                // 非 JSON 请求体也照常记录（出站 wire 断言用）
            }
            // 响应格式对齐真实 agent-runtime 服务（SDK SendMessageResponse oneof 包裹 result.task；
            // 无 kind/lastChunk 字段，status 带 timestamp，history 必填数组——直放 Task 字段会触发
            // InvalidParamsJsonMappingException "id in message SendMessageResponse"，12-08 实测）。
            String result = "{\"jsonrpc\":\"2.0\",\"id\":\"" + rpcId + "\",\"result\":{\"task\":{"
                    + "\"id\":\"gw-task-" + requestBodies.size() + "\","
                    + "\"contextId\":\"ctx-gw\","
                    + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\",\"timestamp\":\"2026-01-01T00:00:00Z\"},"
                    + "\"artifacts\":[{\"artifactId\":\"gw-art-1\",\"parts\":[{\"text\":"
                    + "\"workflow done\"}]}],"
                    + "\"history\":[]}}}";
            byte[] payload = result.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }

        /** 注入「接下来 N 次请求返回 503」（#25 中断恢复重放）。 */
        void failNext(int n) {
            remainingFailures.set(n);
        }

        /**
         * 用例间状态复位（wire 快照 / 计数器 / 故障注入）。
         * 拓扑 B 宿主（edp-agent/mp SUT）真实产生委托流量后，跨用例累积的
         * acceptedBodies()/totalRequests() 会使 get(0) 命中上一用例的 wire、
         * 计数混入历史请求（16:00 实测）——每条用例前必须复位。
         */
        void reset() {
            requestBodies.clear();
            totalRequests.set(0);
            remainingFailures.set(0);
        }

        /** 已被接受（非 5xx 拒绝）的请求 wire 快照，按到达顺序。 */
        List<String> acceptedBodies() {
            return List.copyOf(requestBodies);
        }

        /** 总请求数（含被 503 拒绝的；重复投递计数 = totalRequests - 首次）。 */
        int totalRequests() {
            return totalRequests.get();
        }

        int port() {
            return server.getAddress().getPort();
        }

        String a2aUrl() {
            return "http://127.0.0.1:" + port() + "/a2a";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
