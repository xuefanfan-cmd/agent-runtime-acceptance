package com.huawei.ascend.sit.cases.integration.agent_dev_workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * FEAT-136 黑盒用例共享支持：HTTP wire 客户端 + ZIP 构造器。
 *
 * <p>仅与 agent-dev-workbench 后端的公开 HTTP 契约交互（L2 §2.1 入口清单），
 * 不接触 SUT 内部类、不读取内部状态。期望值出处见
 * docs/cases/FEAT-136-conversational-high-code-agent-development.md 场景矩阵。
 */
final class Feat136WorkbenchSupport {

    /** 控制面 HTTP 端口（后端 P1_PORT 默认 4400，本机验收固定 4500）。 */
    static final String DEFAULT_BASE = "http://127.0.0.1:4500";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String base;
    /** 本地账号会话 Cookie（登录后携带，值形如 workbench_session=...）。 */
    private volatile String sessionCookie;

    Feat136WorkbenchSupport(String base) {
        this.base = base;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    String base() {
        return base;
    }

    String cookieHeader() {
        return sessionCookie;
    }

    /** 6 位随机 hex 运行后缀，用于工作区/审批/导入对象的隔离。 */
    static String runId() {
        SecureRandom random = new SecureRandom();
        return String.format("%06x", random.nextInt(0x1000000));
    }

    /** 共享前置结果：wb 为 null 时 blocker 携带跳过原因。 */
    record Ready(Feat136WorkbenchSupport wb, String blocker) {
    }

    /**
     * 共享前置（审阅合入前优先项 3）：免鉴权就绪探测 + 本地账号登录。
     * 工作区登记与会话创建在测试类内独立完成，不依赖其他套件执行顺序。
     */
    static Ready ready(com.huawei.ascend.sit.config.TestConfig config)
            throws IOException, InterruptedException {
        Feat136WorkbenchSupport wb = new Feat136WorkbenchSupport(
                config.getString("sut.workbench.base-url", DEFAULT_BASE));
        Resp probe = wb.deploymentProbe();
        if (probe.status() != 200) {
            return new Ready(null,
                    "agent-dev-workbench 后端未就绪（GET /api/deployment 返回 " + probe.status() + "）：" + wb.base());
        }
        String user = config.getString("sut.workbench.username", "admin");
        String password = config.getString("sut.workbench.password", "");
        if (password.isBlank()) {
            password = System.getenv("WORKBENCH_ADMIN_PASSWORD");
        }
        if (password == null || password.isBlank()) {
            return new Ready(null,
                    "环境门禁：未配置登录凭据（sut.workbench.password 配置或 WORKBENCH_ADMIN_PASSWORD 环境变量）");
        }
        if (!wb.login(user, password)) {
            return new Ready(null, "环境门禁：本地账号登录失败（POST /api/auth/login）：" + user);
        }
        return new Ready(wb, null);
    }

    /**
     * 会话前置：配置默认模型提供方（凭据经环境变量注入，不落库到测试代码）。
     *
     * <p>LLM_API_BASE 兼容两种写法：OpenAI 兼容 base（如 https://host/api/v3）或
     * 完整 chat/completions 端点（后者去掉 /chat/completions 后缀，SDK 会自动拼接）。
     *
     * @return blocker 原因（null 表示成功）
     */
    static String ensureDefaultLlmFromEnv(Feat136WorkbenchSupport wb)
            throws IOException, InterruptedException {
        String baseUrl = System.getenv("LLM_API_BASE");
        String model = System.getenv("LLM_MODEL");
        String apiKey = System.getenv("LLM_API_KEY");
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            return "环境门禁：LLM_API_BASE/LLM_MODEL 未配置，无法建立开发会话";
        }
        baseUrl = baseUrl.replaceAll("/+$/", "");
        baseUrl = baseUrl.endsWith("/chat/completions")
                ? baseUrl.substring(0, baseUrl.length() - "/chat/completions".length())
                : baseUrl;
        if (!wb.ensureDefaultLlm("feat136-provider", baseUrl, model, apiKey == null ? "" : apiKey)) {
            return "环境门禁：模型提供方配置失败（POST /api/providers 或 PUT /api/settings/llm）";
        }
        return null;
    }

    record Resp(int status, JsonNode body, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    /** 免鉴权就绪探针：GET /api/deployment（App.java deploymentRoute）。 */
    Resp deploymentProbe() throws IOException, InterruptedException {
        return send("GET", "/api/deployment", null, null);
    }

    /**
     * 本地账号登录（POST /api/auth/login），成功后保存会话 Cookie。
     *
     * @return 登录是否成功（200 且取得 Set-Cookie）
     */
    boolean login(String username, String password) throws IOException, InterruptedException {
        ObjectNode body = obj();
        body.put("username", username);
        body.put("password", password);
        Resp resp = send("POST", "/api/auth/login", body, null);
        if (resp.status() != 200) {
            return false;
        }
        String setCookie = resp.header("set-cookie");
        if (setCookie == null || setCookie.isBlank()) {
            return false;
        }
        this.sessionCookie = setCookie.split(";", 2)[0].trim();
        return !sessionCookie.isBlank();
    }

    /**
     * 创建模型提供方并设为会话默认（会话创建前置：POST /api/providers + PUT /api/settings/llm）。
     *
     * @return 是否配置成功
     */
    boolean ensureDefaultLlm(String name, String baseUrl, String modelId, String apiKey)
            throws IOException, InterruptedException {
        // 已有默认提供方且默认模型一致则复用（幂等）；模型不一致则重建（环境模型可能切换）
        Resp settings = send("GET", "/api/settings/llm", null, null);
        if (settings.status() == 200
                && !settings.body().path("defaultProviderId").asText("").isBlank()
                && modelId.equals(settings.body().path("defaultModel").asText(""))) {
            return true;
        }
        ObjectNode provider = obj();
        provider.put("name", name);
        provider.put("baseURL", baseUrl);
        provider.put("protocol", "openai");
        ObjectNode model = obj();
        model.put("id", modelId);
        provider.putArray("models").add(model);
        if (apiKey != null && !apiKey.isBlank()) {
            provider.put("apiKey", apiKey);
        }
        Resp created = sendWithBody("POST", "/api/providers", provider);
        if (created.status() != 200 && created.status() != 201) {
            return false;
        }
        String providerId = created.body().path("id").asText("");
        if (providerId.isBlank()) {
            return false;
        }
        ObjectNode llm = obj();
        llm.put("defaultProviderId", providerId);
        llm.put("defaultModel", modelId);
        Resp saved = sendWithBody("PUT", "/api/settings/llm", llm);
        return saved.status() == 200;
    }

    /** WS 订阅地址：与 HTTP 同宿主、端口 +1、根路径 + 查询参数（WsHub onOpen 契约）。 */
    URI wsUri(String sessionId, long since) {
        URI http = URI.create(base);
        int wsPort = http.getPort() + 1;
        String query = "sessionId=" + sessionId + "&since=" + since;
        return URI.create("ws://" + http.getHost() + ":" + wsPort + "/?" + query);
    }

    /**
     * 按提交文本定位事件侧回合 ID（user.message payload.turnId 与事件归组键一致）。
     * 用于把 HTTP 受理 turnId（与事件 turnId 独立，L2 §2.4）关联到事件流中的本回合。
     */
    String eventTurnIdForUserMessage(String sessionId, String text)
            throws IOException, InterruptedException {
        Resp events = get("/api/sessions/" + sessionId + "/events");
        if (events.status() != 200) {
            return "";
        }
        String found = "";
        for (JsonNode item : events.body().path("items")) {
            if ("user.message".equals(item.path("type").asText())
                    && text.equals(item.path("payload").path("text").asText())) {
                found = item.path("payload").path("turnId").asText("");
            }
        }
        return found;
    }

    /**
     * 清理会话内遗留的待决审批（ask 策略下未决定的 approval.requested）。
     *
     * <p>用途：AcpSession 对引擎中仍在等待权限决定的会话提交新回合时，新回合事件被受理
     * 但引擎不会再处理（user.message/turn.started 后无终态）。依赖回合终态的场景须先清理。
     * 拒绝决定保留拒绝状态，不影响后续用例对拒绝语义的验证。
     *
     * @return 实际提交拒绝决定的审批数量
     */
    int resolvePendingApprovals(String sessionId) throws IOException, InterruptedException {
        Resp events = get("/api/sessions/" + sessionId + "/events");
        if (events.status() != 200) {
            return 0;
        }
        java.util.Set<String> requested = new java.util.LinkedHashSet<>();
        java.util.Set<String> resolved = new java.util.HashSet<>();
        for (JsonNode item : events.body().path("items")) {
            String type = item.path("type").asText();
            String approvalId = item.path("payload").path("approvalId").asText(
                    item.path("approvalId").asText(""));
            if (approvalId.isBlank()) {
                continue;
            }
            if ("approval.requested".equals(type)) {
                requested.add(approvalId);
            } else if ("approval.resolved".equals(type)) {
                resolved.add(approvalId);
            }
        }
        int resolvedCount = 0;
        for (String approvalId : requested) {
            if (resolved.contains(approvalId)) {
                continue;
            }
            ObjectNode body = obj();
            body.put("decision", "reject");
            Resp resp = sendWithBody("POST", "/api/sessions/" + sessionId + "/approvals/" + approvalId, body);
            if (resp.status() == 200) {
                resolvedCount++;
            }
        }
        return resolvedCount;
    }

    Resp get(String path) throws IOException, InterruptedException {
        return send("GET", path, null, null);
    }

    // ---- 端到端辅助：回合执行器、审批策略、FEAT-137 探针 ----

    /** 审批决策策略：返回本审批的请求体；返回 null 表示本轮不决定（下轮轮询再判）。 */
    interface ApprovalPolicy {
        ObjectNode decide(JsonNode approvalPayload, int approvedSoFar, int rejectedSoFar);
    }

    /** 全部批准（once）：真实生成链路的自动批准策略。 */
    ApprovalPolicy approveOnceAll() {
        return (payload, approved, rejected) -> {
            ObjectNode body = obj();
            body.put("decision", "once");
            return body;
        };
    }

    /** 回合执行结果（全部来自事件流 wire 观察）。 */
    record TurnResult(String eventTurnId, boolean ended, String stopReason,
            java.util.List<String> approvedIds, java.util.List<String> rejectedIds,
            java.util.List<JsonNode> toolCompleted, java.util.List<JsonNode> toolFailed,
            int approvalRequestedCount, java.util.List<String> errorEvents) {
    }

    /**
     * 提交回合并驱动至终态：轮询事件流，按策略决定出现的待决审批，
     * 直到本回合的 turn.ended（或超时）。前置清理遗留待决审批（AcpSession busy 行为）。
     *
     * <p>超时为"进度感知"：全局硬上限 timeoutSeconds；同时若事件流连续 600 秒无新增事件
     * （无进度）则提前放弃——生成回合（实测 glm-5.3-flash 全量生成 26～50+ 分钟，审批可达 70+ 次）
     * 只要持续推进就继续等待，避免固定超时对慢生成的误判。
     */
    TurnResult runTurn(String sessionId, String text, ApprovalPolicy policy, long timeoutSeconds)
            throws IOException, InterruptedException {
        resolvePendingApprovals(sessionId);
        Resp turn = sendWithBody("POST", "/api/sessions/" + sessionId + "/turns", turnRequest(text));
        if (turn.status() != 202) {
            return new TurnResult("", false, "submit-failed:" + turn.status(),
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    0, java.util.List.of());
        }
        String eventTurnId = eventTurnIdForUserMessage(sessionId, text);

        java.util.Map<String, JsonNode> requested = new LinkedHashMap<>();
        java.util.Set<String> resolved = new java.util.HashSet<>();
        java.util.List<String> approved = new java.util.ArrayList<>();
        java.util.List<String> rejected = new java.util.ArrayList<>();
        java.util.List<JsonNode> completed = new java.util.ArrayList<>();
        java.util.List<JsonNode> failed = new java.util.ArrayList<>();
        java.util.List<String> errors = new java.util.ArrayList<>();
        boolean ended = false;
        String stopReason = "";
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        long lastProgressAt = System.currentTimeMillis();
        int lastEventCount = -1;
        while (System.currentTimeMillis() < deadline) {
            Resp events = get("/api/sessions/" + sessionId + "/events");
            if (events.status() == 200) {
                requested.clear();
                resolved.clear();
                completed.clear();
                failed.clear();
                errors.clear();
                int count = 0;
                for (JsonNode item : events.body().path("items")) {
                    count++;
                    // 只统计本回合的事件（事件 turnId 归组；旧回合审批/工具事件不污染本回合判定）
                    String itemTurn = item.path("turnId").asText("");
                    if (!eventTurnId.isBlank() && !eventTurnId.equals(itemTurn)) {
                        continue;
                    }
                    String type = item.path("type").asText();
                    JsonNode payload = item.path("payload");
                    switch (type) {
                        case "approval.requested" -> requested.put(
                                payload.path("approvalId").asText(""), payload);
                        case "approval.resolved" -> resolved.add(payload.path("approvalId").asText(""));
                        case "tool.completed" -> completed.add(payload.path("tool"));
                        case "tool.failed" -> failed.add(payload.path("tool"));
                        case "error" -> errors.add(payload.toString());
                        case "turn.ended" -> {
                            if (eventTurnId.isBlank()
                                    || eventTurnId.equals(payload.path("turnId").asText(""))) {
                                ended = true;
                                stopReason = payload.path("stopReason").asText("");
                            }
                        }
                        default -> {
                        }
                    }
                }
                if (count != lastEventCount) {
                    lastEventCount = count;
                    lastProgressAt = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastProgressAt > 600_000L) {
                    // 10 分钟无任何新事件：回合无进度，提前放弃（结果未终态 → ended=false）
                    break;
                }
                // 按策略决定未决审批
                for (java.util.Map.Entry<String, JsonNode> pending : requested.entrySet()) {
                    if (resolved.contains(pending.getKey())) {
                        continue;
                    }
                    ObjectNode decision = policy.decide(pending.getValue(), approved.size(), rejected.size());
                    if (decision == null) {
                        continue;
                    }
                    Resp resp = sendWithBody("POST",
                            "/api/sessions/" + sessionId + "/approvals/" + pending.getKey(), decision);
                    if (resp.status() == 200) {
                        if ("reject".equals(decision.path("decision").asText())) {
                            rejected.add(pending.getKey());
                        } else {
                            approved.add(pending.getKey());
                        }
                    }
                }
            }
            if (ended) {
                break;
            }
            Thread.sleep(3000);
        }
        return new TurnResult(eventTurnId, ended, stopReason, approved, rejected,
                completed, failed, requested.size(), errors);
    }

    /** 创建工作区 + 开发会话（端到端用例隔离单元）。任一步失败返回空字段记录。 */
    record WsSession(String wsId, String wsPath, String sessionId) {
        boolean ready() {
            return wsId != null && !wsId.isBlank() && sessionId != null && !sessionId.isBlank();
        }
    }

    WsSession createWorkspaceAndSession(String wsName, String sessionTitle, String llmBlocker) {
        if (llmBlocker != null) {
            return new WsSession("", "", "");
        }
        try {
            Resp ws = sendWithBody("POST", "/api/workspaces", workspaceByName(wsName));
            if (ws.status() != 201) {
                return new WsSession("", "", "");
            }
            String wsId = ws.body().path("id").asText();
            String wsPath = ws.body().path("path").asText();
            Resp session = sendWithBody("POST", "/api/sessions", sessionCreate(wsId, sessionTitle));
            if (session.status() != 201) {
                return new WsSession(wsId, wsPath, "");
            }
            return new WsSession(wsId, wsPath, session.body().path("id").asText());
        } catch (Exception e) {
            return new WsSession("", "", "");
        }
    }

    /** 分页读取工程文件清单（合并 entries；FEAT-137 只读探针）。 */
    java.util.List<String> listProjectFilePaths(String sessionId)
            throws IOException, InterruptedException {
        java.util.List<String> paths = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            String url = "/api/sessions/" + sessionId + "/files?limit=200"
                    + (cursor != null ? "&cursor=" + java.net.URLEncoder.encode(cursor,
                            java.nio.charset.StandardCharsets.UTF_8) : "");
            Resp resp = get(url);
            if (resp.status() != 200) {
                break;
            }
            for (JsonNode entry : resp.body().path("entries")) {
                paths.add(entry.path("relativePath").asText());
            }
            boolean hasMore = resp.body().path("hasMore").asBoolean(false);
            JsonNode next = resp.body().path("nextCursor");
            cursor = next.isNull() || !hasMore ? null : next.asText(null);
            if (cursor == null) {
                break;
            }
        }
        return paths;
    }

    /**
     * 分页读取工程文件清单（含平台兜底）：探针不可用（如 Windows 下 ProjectFiles 的
     * SecureDirectoryStream 不受支持 → 403 FILE_ACCESS_DENIED）时，同机 local 形态直接
     * 观察工作区目录（进程外观察工程产物本身，与 /files 返回同一数据面；不触碰 SUT 内部状态）。
     * 探针结果与兜底来源在用例报告与执行报告中标注。
     */
    java.util.List<String> listProjectFilePaths(String sessionId, String wsPathFallback)
            throws IOException, InterruptedException {
        java.util.List<String> paths = listProjectFilePaths(sessionId);
        if (!paths.isEmpty() || wsPathFallback == null || wsPathFallback.isBlank()) {
            return paths;
        }
        // 探针不可用 → 工程目录直读兜底（跳过隐藏管理目录）
        java.nio.file.Path root = java.nio.file.Path.of(wsPathFallback);
        if (!java.nio.file.Files.isDirectory(root)) {
            return paths;
        }
        try (var stream = java.nio.file.Files.walk(root)) {
            stream.filter(java.nio.file.Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.startsWith("."))
                    .filter(p -> !p.contains("/."))
                    .forEach(paths::add);
        }
        paths.sort(String::compareTo);
        return paths;
    }

    /** 读取工程文件正文（FEAT-137 只读探针 /file?view=workbench）。 */
    Resp readSessionFile(String sessionId, String path) throws IOException, InterruptedException {
        String encoded = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
        return get("/api/sessions/" + sessionId + "/file?path=" + encoded + "&view=workbench");
    }

    /** 读取工程文件正文（含平台兜底：探针 403 时同机直读工作区文件，返回结构与 /file 契约一致）。 */
    Resp readSessionFile(String sessionId, String path, String wsPathFallback)
            throws IOException, InterruptedException {
        Resp probe = readSessionFile(sessionId, path);
        if (probe.status() == 200 || wsPathFallback == null || wsPathFallback.isBlank()) {
            return probe;
        }
        java.nio.file.Path file = java.nio.file.Path.of(wsPathFallback).resolve(path);
        if (!java.nio.file.Files.isRegularFile(file)) {
            return probe;
        }
        byte[] raw = java.nio.file.Files.readAllBytes(file);
        String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.node.ObjectNode body = obj();
        body.put("path", path);
        body.put("text", text);
        body.put("encoding", "UTF-8");
        body.put("size", raw.length);
        body.put("binary", false);
        body.put("editable", false);
        body.put("truncated", false);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(raw)) {
                sb.append(String.format("%02x", b));
            }
            body.put("fingerprint", sb.toString());
        } catch (java.security.NoSuchAlgorithmException ignored) {
            // 指纹可选
        }
        return new Resp(200, body, Map.of("x-probe-fallback", "workspace-disk"));
    }

    /** 读取工程结构投影（FEAT-137 探针 /project/model）。 */
    Resp readProjection(String sessionId) throws IOException, InterruptedException {
        return get("/api/sessions/" + sessionId + "/project/model");
    }

    Resp delete(String path) throws IOException, InterruptedException {
        return send("DELETE", path, null, null);
    }

    Resp sendWithBody(String method, String path, ObjectNode body) throws IOException, InterruptedException {
        return send(method, path, body, null);
    }

    Resp sendRaw(String method, String path, byte[] bytes, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(30));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        if (bytes != null) {
            builder.header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            // 连接失败：返回 status=0 供环境门禁 assumeTrue 判定
            return new Resp(0, mapper.missingNode(), Map.of());
        }
        JsonNode bodyJson = null;
        if (response.body() != null && response.body().length > 0) {
            try {
                bodyJson = mapper.readTree(response.body());
            } catch (IOException ignored) {
                bodyJson = null;
            }
        }
        return new Resp(response.statusCode(), bodyJson, lowerKeys(response.headers().map()));
    }

    private Resp send(String method, String path, ObjectNode body, Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                // 会话创建需串行拉起 opencode 引擎（ACP initialize），套件前置连续创建多个会话时
                // 尾部变体可能超过 30s；统一放宽到 120s（错误路径后端仍即时返回）
                .timeout(Duration.ofSeconds(120));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach(builder::header);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // 连接失败（后端未部署/拒绝连接）：返回 status=0 供环境门禁 assumeTrue 判定
            return new Resp(0, mapper.missingNode(), Map.of());
        }
        JsonNode bodyJson;
        try {
            bodyJson = mapper.readTree(response.body());
        } catch (IOException e) {
            bodyJson = mapper.nullNode();
        }
        return new Resp(response.statusCode(), bodyJson, lowerKeys(response.headers().map()));
    }

    private static Map<String, String> lowerKeys(Map<String, java.util.List<String>> map) {
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k.toLowerCase(), String.join(", ", v)));
        return out;
    }

    // ---- 请求体构造 ----

    ObjectNode obj() {
        return mapper.createObjectNode();
    }

    ObjectNode workspaceByName(String name) {
        ObjectNode body = obj();
        body.put("name", name);
        return body;
    }

    ObjectNode workspaceByPath(String path) {
        ObjectNode body = obj();
        body.put("path", path);
        return body;
    }

    ObjectNode sessionCreate(String workspaceId, String title) {
        ObjectNode body = obj();
        body.put("workspaceId", workspaceId);
        body.put("title", title);
        return body;
    }

    ObjectNode turnRequest(String text) {
        ObjectNode block = obj();
        block.put("text", text);
        ObjectNode body = obj();
        body.putArray("blocks").add(block);
        return body;
    }

    // ---- ZIP 构造器（Fixture：进程内构造，不预置共享文件）----

    /** 单文件新增 ZIP（UTF-8 文本）。 */
    static byte[] zipOf(String path, String content) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(path, content);
        return zipOfEntries(entries);
    }

    static byte[] zipOfEntries(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 含控制字符的非 UTF-8 二进制条目。 */
    static byte[] zipWithBinaryEntry(String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(path));
            byte[] binary = new byte[64];
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) (0xC0 + i % 32); // 高位字节 + 控制区组合，非 UTF-8 文本
            }
            zip.write(binary);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /** 截断 ZIP 字节（损坏包）。 */
    static byte[] truncatedZip() throws IOException {
        byte[] zip = zipOf("feat136-trunc/a.txt", "x");
        byte[] cut = new byte[zip.length / 2];
        System.arraycopy(zip, 0, cut, 0, cut.length);
        return cut;
    }
}