/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FRS-058 wire 层测试支撑：multipart 报文构造、A2A JSON-RPC 直发、FAQ 工作流 fixture。
 *
 * <p>FAQ fixture 承接 FRS-058 中 [外部] FAQ 工作流（仅文本检索，返回文本/web url/图片方案）：
 * 测试进程内 HTTP 服务，A2A JSONRPC 绑定（含 AgentCard discovery 应答），支持
 * 三形态方案 / 无匹配 / 503 故障注入 / 失效图片四种可控模式，并捕获全部出站 wire。
 * Fixture 为测试资产，不改动产品 demo。</p>
 */
final class Feat058WireSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    /** 协议层单 raw 解码后字节上限 = 10MB（FEAT-036 §4.1，FRS-058 图片约束沿用）。 */
    static final int MAX_RAW_BYTES = 10 * 1024 * 1024;
    /** 协议层参数错误码（FRS-058 用例 7.1：-32602 InvalidParams 或等价错误）。 */
    static final int CODE_INVALID_PARAMS = -32602;
    /** 业务层类型白名单拒绝码（FRS-058 用例 7.2：EDP-FILE-003 或等价业务错误表面）。 */
    static final String CODE_EDP_FILE_003 = "EDP-FILE-003";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Feat058WireSupport() {
    }

    // ------------------------------------------------------------------
    // multipart
    // ------------------------------------------------------------------

    record FileField(String fieldName, String filename, String contentType, byte[] bytes) {
    }

    /** 构造 multipart/form-data 报文（文件字段 + 文本表单字段）。 */
    static Map.Entry<String, byte[]> multipart(List<FileField> files, List<String[]> formFields) {
        String boundary = "feat058-" + UUID.randomUUID();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            for (String[] field : formFields) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                        + field[0] + "\"\r\n\r\n" + field[1] + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            for (FileField file : files) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                        + file.fieldName() + "\"; filename=\"" + file.filename() + "\"\r\n"
                        + "Content-Type: " + file.contentType() + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(file.bytes());
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return Map.entry("multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    /** 长度为 len 的 ASCII 填充字节（超限样张用）。 */
    static byte[] fillBytes(int len, String seed) {
        byte[] bytes = new byte[len];
        byte[] seedBytes = seed.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < len; i++) {
            bytes[i] = seedBytes[i % seedBytes.length];
        }
        return bytes;
    }

    // ------------------------------------------------------------------
    // HTTP 直发
    // ------------------------------------------------------------------

    /** POST 任意 URL（multipart 入口用；默认 480s 预算——glm-5.3-flash 深推理单轮 10~30s，
     *  deep-agent 50 步 ReAct 的长澄清循环实测可超 240s，见 FRS058 run6 取证）。 */
    static HttpResponse<String> postUrl(String url, String contentType, byte[] bodyBytes)
            throws IOException, InterruptedException {
        return postUrl(url, contentType, bodyBytes, 480);
    }

    static HttpResponse<String> postUrl(String url, String contentType, byte[] bodyBytes, int timeoutSeconds)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** POST baseUrl + "/a2a"（LLM 驱动阻塞单轮，480s 预算，与 multipart 入口一致）。 */
    static HttpResponse<String> postA2a(String baseUrl, String body)
            throws IOException, InterruptedException {
        return postUrl(baseUrl + "/a2a", "application/json",
                body.getBytes(StandardCharsets.UTF_8), 480);
    }

    // ------------------------------------------------------------------
    // A2A JSON-RPC
    // ------------------------------------------------------------------

    /** 构造 SendMessage 请求体（role/messageId/contextId/parts 全量自定义）。 */
    static String jsonRpcSend(List<Map<String, Object>> parts) {
        ObjectNode root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", "feat058-" + UUID.randomUUID());
        root.put("method", "SendMessage");
        ObjectNode params = root.putObject("params");
        ObjectNode message = params.putObject("message");
        message.put("role", "ROLE_USER");
        message.put("messageId", "msg-feat058-" + UUID.randomUUID());
        message.put("contextId", "ctx-feat058-" + UUID.randomUUID().toString().substring(0, 8));
        ArrayNode partsNode = message.putArray("parts");
        for (Map<String, Object> part : parts) {
            ObjectNode node = partsNode.addObject();
            part.forEach((key, value) -> node.set(key, JSON.valueToTree(value)));
        }
        return root.toString();
    }

    /** 构造 GetTask / CancelTask 等以 taskId 为参数的请求体。 */
    static String jsonRpcTaskMethod(String method, String taskId) {
        ObjectNode root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", "feat058-" + UUID.randomUUID());
        root.put("method", method);
        root.putObject("params").put("id", taskId);
        return root.toString();
    }

    /** A2A raw Part（文件字节，base64 编码；FEAT-036 Part(raw) 形态，filename/mediaType 元数据同款）。 */
    static Map<String, Object> rawPart(byte[] bytes, String filename, String mediaType) {
        Map<String, Object> part = new java.util.LinkedHashMap<>();
        part.put("raw", java.util.Base64.getEncoder().encodeToString(bytes));
        part.put("filename", filename);
        part.put("mediaType", mediaType);
        return part;
    }

    /** A2A text Part。 */
    static Map<String, Object> textPart(String text) {
        return Map.of("text", text);
    }

    static Integer errorCode(String responseBody) throws IOException {
        JsonNode error = JSON.readTree(responseBody).path("error");
        return error.isMissingNode() || error.isNull() ? null : error.path("code").asInt();
    }

    static String errorMessage(String responseBody) throws IOException {
        return JSON.readTree(responseBody).path("error").path("message").asText("");
    }

    static String resultId(String responseBody) throws IOException {
        JsonNode id = JSON.readTree(responseBody).path("result").path("task").path("id");
        return id.isMissingNode() || id.isNull() ? null : id.asText();
    }

    static JsonNode resultTask(String responseBody) throws IOException {
        return JSON.readTree(responseBody).path("result").path("task");
    }

    /** GetTask 形态容错：优先 result.task，退化到 result 本体（不同 SUT 版本 result 直接承载 Task）。 */
    static JsonNode resultTaskTolerant(String responseBody) throws IOException {
        JsonNode root = JSON.readTree(responseBody);
        JsonNode task = root.path("result").path("task");
        return task.isMissingNode() || task.isNull() ? root.path("result") : task;
    }

    /**
     * 深度收集 JSON 树上的业务文本字段（text/answer/description/message/content/state），
     * 用于 REST 信封缺省 answer 时从 Task 挂起/完成载荷中还原澄清话术与最终作答。
     */
    static String collectTaskText(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        collectTaskText(node, sb);
        return sb.toString();
    }

    private static void collectTaskText(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode value = field.getValue();
                if (value.isTextual() && Set.of("text", "answer", "description", "message",
                        "content", "state", "role", "raw").contains(name)) {
                    sb.append(value.asText()).append('\n');
                } else {
                    collectTaskText(value, sb);
                }
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectTaskText(child, sb));
        }
    }

    /** 在出站 wire 文本中粗筛携带给定成员键的 Part。 */
    static boolean wireContainsPart(String wire, String memberKey) {
        return wire != null && wire.contains("\"" + memberKey + "\"");
    }

    /** Task 信封 task_id 提取（形态容错：非 Task 信封返回 null）。 */
    static String taskIdOf(String responseBody) {
        try {
            JsonNode id = JSON.readTree(responseBody).path("task_id");
            return id.isMissingNode() || id.isNull() ? null : id.asText();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // SSE 流式响应解析（multi-modal-FAQ-agent 装配面：chunk/solution/input_required/error/final）
    // ------------------------------------------------------------------

    /** 一轮 multipart 流式响应的 wire 投影（SSE 契约见 multi-modal-FAQ-agent README §5.2）。 */
    record SseTurn(String rawBody, String chunkText, Map<String, Object> solution,
            String inputRequiredQuestion, String finalState, String errorCode, String errorMessage,
            List<String> statusStages) {

        /** 方案素材列表（solution.items，可能为空）。 */
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> solutionItems() {
            Object items = solution == null ? null : solution.get("items");
            return items instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        }

        /** 方案主答复（solution.answer，可能为 null）。 */
        String solutionAnswer() {
            return solution == null ? null : String.valueOf(solution.get("answer"));
        }

        /** 全文（chunk 增量 + solution.answer + items 原文拼接，供 contains 类断言）。 */
        String fullText() {
            StringBuilder sb = new StringBuilder(chunkText == null ? "" : chunkText);
            if (solutionAnswer() != null) {
                sb.append('\n').append(solutionAnswer());
            }
            for (Map<String, Object> item : solutionItems()) {
                sb.append('\n').append(item);
            }
            return sb.toString();
        }

        /** 终态为 completed（final 帧 task_state=completed）。 */
        boolean completed() {
            return "completed".equals(finalState);
        }

        /** 本轮以 INPUT_REQUIRED 澄清追问收束（input_required 帧）。 */
        boolean inputRequired() {
            return inputRequiredQuestion != null;
        }
    }

    /** 解析 SSE 文本（event:/data: 行对），投影一轮交互。 */
    static SseTurn parseSse(String body) throws IOException {
        StringBuilder chunks = new StringBuilder();
        Map<String, Object> solution = null;
        String question = null;
        String finalState = null;
        String errCode = null;
        String errMsg = null;
        List<String> stages = new ArrayList<>();
        String event = "";
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5).strip();
                    Map<?, ?> payload = null;
                    try {
                        Object parsed = JSON.readValue(data, Object.class);
                        payload = parsed instanceof Map<?, ?> map ? map : null;
                    } catch (IOException ignored) {
                        // 非 JSON data 帧：chunk 事件按纯文本处理
                    }
                    switch (event) {
                        case "chunk" -> {
                            Object delta = payload == null ? data : payload.get("delta");
                            if (delta != null) {
                                chunks.append(delta);
                            }
                        }
                        case "solution" -> {
                            if (payload != null) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> cast = (Map<String, Object>) payload;
                                solution = cast;
                            }
                        }
                        case "input_required" -> {
                            if (payload != null) {
                                question = String.valueOf(payload.get("question"));
                            }
                        }
                        case "final" -> {
                            if (payload != null) {
                                finalState = String.valueOf(payload.get("task_state"));
                            }
                        }
                        case "error" -> {
                            if (payload != null) {
                                errCode = String.valueOf(payload.get("code"));
                                errMsg = String.valueOf(payload.get("message"));
                            }
                        }
                        case "status" -> {
                            // status 帧 stage 字段留痕（FRS058-15 关键步骤覆盖断言面）
                            if (payload != null && payload.get("stage") != null) {
                                stages.add(String.valueOf(payload.get("stage")));
                            }
                        }
                        default -> {
                            // 空 delta 等帧：不参与断言投影
                        }
                    }
                }
            }
        }
        return new SseTurn(body, chunks.toString(), solution, question, finalState, errCode,
                errMsg, List.copyOf(stages));
    }

    // ------------------------------------------------------------------
    // FAQ 工作流 fixture（[外部] FAQ 替身：A2A 捕获 + 可控应答 + 故障注入）
    // ------------------------------------------------------------------

    /** FAQ 应答模式。 */
    enum FaqMode {
        /** 三形态方案：文本步骤 + web url + 图片引用（用例 6.4）。 */
        SOLUTION,
        /** 无匹配方案（用例 7.4 无匹配分支）。 */
        NO_MATCH,
        /** 三形态方案但图片 url 指向本桩 404 端点（用例 7.5）。 */
        DEAD_IMAGE
    }

    static final class FaqStub implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;
        private final List<String> requestBodies = new CopyOnWriteArrayList<>();
        private final AtomicInteger remainingFailures = new AtomicInteger();
        private final AtomicInteger totalRequests = new AtomicInteger();
        private volatile FaqMode mode = FaqMode.SOLUTION;
        /** 每次 message/send 应答前的延迟毫秒数（>0 时真实超时注入，reset 清除）。 */
        private volatile long delayMillis = 0;

        FaqStub() throws IOException {
            this.server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/a2a", this::handleA2a);
            // card 发现 fallback：AgentCard 发现按 remote-agents[].url 基地址拼接
            // /.well-known/agent-card.json（url 带 /a2a 后缀时由上方前缀 context 承接）
            this.server.createContext("/.well-known", this::handleA2a);
            this.server.createContext("/faq-img", this::handleImage);
            this.server.start();
        }

        void setMode(FaqMode mode) {
            this.mode = mode;
        }

        /** 注入「接下来 N 次请求返回 503」（FAQ 调用失败，用例 7.4 不可用分支）。 */
        void failNext(int n) {
            remainingFailures.set(n);
        }

        /**
         * 注入「后续每次 message/send 应答前延迟 delay 毫秒」（§8.1 FAQ 真实超时注入——
         * 与 503 故障不同，延迟超过远程调用超时预算时由客户端侧超时收敛）。reset 清除。
         */
        void setDelayMillis(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        void reset() {
            requestBodies.clear();
            totalRequests.set(0);
            remainingFailures.set(0);
            delayMillis = 0;
            mode = FaqMode.SOLUTION;
        }

        List<String> acceptedBodies() {
            return List.copyOf(requestBodies);
        }

        int totalRequests() {
            return totalRequests.get();
        }

        String a2aUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a";
        }

        /** 指向本桩 404 图片端点的 url（DEAD_IMAGE 模式方案内图片引用）。 */
        String deadImageUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/faq-img/missing-9527.png";
        }

        private void handleImage(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            // 始终 404：图片引用失效（用例 7.5 前置）
            byte[] payload = "{\"error\":\"faq-image-missing\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, payload.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }

        private void handleA2a(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().toString();
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && (path.endsWith("/.well-known/agent-card.json")
                    || path.endsWith("/.well-known/agent.json"))) {
                byte[] card = ("{\"name\":\"faq-workflow\",\"description\":\"FRS-058 FAQ workflow stub\","
                        + "\"provider\":{\"organization\":\"sit-test\",\"url\":\"\"},\"version\":\"1.0.0\","
                        + "\"documentationUrl\":null,"
                        + "\"capabilities\":{\"streaming\":true,\"pushNotifications\":false,"
                        + "\"extendedAgentCard\":false,\"extensions\":[]},"
                        + "\"defaultInputModes\":[\"text\",\"text/plain\"],"
                        + "\"defaultOutputModes\":[\"text\",\"text/plain\"],"
                        + "\"skills\":[{\"id\":\"faq-retrieval\",\"name\":\"faq-retrieval\","
                        + "\"description\":\"text-only FAQ retrieval\",\"tags\":[],\"examples\":[],"
                        + "\"inputModes\":[\"text\",\"text/plain\"],"
                        + "\"outputModes\":[\"text\",\"text/plain\"],\"securityRequirements\":[]}],"
                        + "\"securitySchemes\":{},\"securityRequirements\":[],\"iconUrl\":null,"
                        + "\"supportedInterfaces\":[{\"protocolBinding\":\"JSONRPC\",\"url\":\""
                        + a2aUrl() + "\",\"tenant\":null,\"protocolVersion\":\"1.0\"}],"
                        + "\"signatures\":[],\"url\":\"" + a2aUrl() + "\",\"preferredTransport\":\"JSONRPC\","
                        + "\"additionalInterfaces\":[]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, card.length);
                try (java.io.OutputStream os = exchange.getResponseBody()) {
                    os.write(card);
                }
                return;
            }
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            totalRequests.incrementAndGet();
            if (remainingFailures.getAndUpdate(v -> v > 0 ? v - 1 : v) > 0) {
                byte[] payload = "{\"error\":\"injected-503\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, payload.length);
                try (java.io.OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
                return;
            }
            requestBodies.add(body);
            // 真实超时注入：应答前延迟（超过客户端远程调用预算即触发超时收敛，§8.1）
            long delay = delayMillis;
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            String rpcId = "unknown";
            try {
                rpcId = JSON.readTree(body).path("id").asText("unknown");
            } catch (IOException ignored) {
                // 非 JSON 请求体也照常记录（出站 wire 断言用）
            }
            String artifactText;
            switch (mode) {
                // FAQ 工具结果契约（multi-modal-FAQ-agent FaqPocSolutionRail）：主答复文本 +
                // 尾部 {"matched":...,"solutions":[...]} JSON——matched=false → 暂未找到提示；
                // solutions 原样透传为方案卡片 items（url 由 adapter 按 C-03/C-04 补全）。
                case NO_MATCH -> artifactText = "FAQ 检索无匹配方案"
                        + "{\"matched\":false,\"solutions\":[]}";
                case DEAD_IMAGE -> {
                    String helpUrl = a2aUrl().replace("/a2a", "/faq-help");
                    artifactText = "解决方案：请进入 我的-安全中心-账户安全 完成重置。帮助页：" + helpUrl
                            + " 示意图：" + deadImageUrl()
                            + "{\"matched\":true,\"solutions\":["
                            + "{\"type\":\"text\",\"title\":\"处理步骤\",\"content\":\"进入 我的-安全中心-账户安全 完成重置\"},"
                            + "{\"type\":\"web\",\"title\":\"帮助页\",\"url\":\"" + helpUrl + "\"},"
                            + "{\"type\":\"image\",\"title\":\"重置示意图\",\"url\":\"" + deadImageUrl()
                            + "\",\"note\":\"示意图加载失败时请点击帮助页查看操作步骤\"}]}";
                }
                default -> artifactText = "解决方案：请进入 我的-安全中心-账户安全 完成重置。"
                        + "帮助页：https://bank.example.com/help/9527 示意图：/static/faq/9527.png"
                        + "{\"matched\":true,\"solutions\":["
                        + "{\"type\":\"text\",\"title\":\"处理步骤\",\"content\":\"进入 我的-安全中心-账户安全，在账户安全页完成登录密码重置\"},"
                        + "{\"type\":\"web\",\"title\":\"帮助页\",\"url\":\"https://bank.example.com/help/9527\"},"
                        + "{\"type\":\"image\",\"title\":\"重置示意图\",\"url\":\"/static/faq/9527.png\"},"
                        + "{\"type\":\"deeplink\",\"title\":\"安全中心页面\",\"url\":\"/#/page/security-center\"}]}";
            }
            // 响应格式对齐真实 agent-runtime 服务（SendMessageResponse result.task 包装，
            // status 带 timestamp，history 必填数组——与 FEAT-036 GatewayStub 同构）。
            // artifact 文本含 matched/solutions JSON 尾部（内嵌双引号），必须经 Jackson
            // 序列化转义——手拼字符串会产生非法 JSON（run11 取证：A2A 客户端
            // MalformedJsonException $.result.task.artifacts[0].parts[0].text）。
            ObjectNode responseRoot = JSON.createObjectNode();
            responseRoot.put("jsonrpc", "2.0");
            responseRoot.put("id", rpcId);
            ObjectNode taskNode = responseRoot.putObject("result").putObject("task");
            taskNode.put("id", "faq-task-" + requestBodies.size());
            taskNode.put("contextId", "ctx-faq");
            ObjectNode statusNode = taskNode.putObject("status");
            statusNode.put("state", "TASK_STATE_COMPLETED");
            statusNode.put("timestamp", "2026-01-01T00:00:00Z");
            ObjectNode artifactNode = taskNode.putArray("artifacts").addObject();
            artifactNode.put("artifactId", "faq-art-1");
            artifactNode.putArray("parts").addObject().put("text", artifactText);
            taskNode.putArray("history");
            byte[] payload = responseRoot.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
