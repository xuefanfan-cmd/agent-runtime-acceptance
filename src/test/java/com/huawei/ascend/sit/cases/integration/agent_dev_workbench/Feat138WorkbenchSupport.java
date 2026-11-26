package com.huawei.ascend.sit.cases.integration.agent_dev_workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

/**
 * FEAT-138 黑盒用例共享支持：HTTP wire 客户端 + 请求体构造器。
 *
 * <p>仅与 agent-dev-workbench 后端的公开 HTTP 契约交互（L2 §2.1 入口清单：
 * /api/sessions/{id}/runtime、/runtime/operations、/preview、/preview/history、
 * /preview/cancel），不接触 SUT 内部类、不读取内部状态。期望值出处见
 * docs/cases/FEAT-138-agent-runtime-debug-iteration-verification.md 场景矩阵。
 */
final class Feat138WorkbenchSupport {

    /**
     * 控制面端口为 P1_PORT（本机验收 4500）；设计文档默认 7310 是远端形态 agent-host 端口
     * （FEAT-136 执行报告整改项 2），故默认 base-url 取 4500。
     */
    static final String DEFAULT_BASE = "http://127.0.0.1:4500";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String base;
    /** 本地账号登录后的会话 Cookie（除 /api/deployment、/api/auth/* 外全部 /api/* 必需）。 */
    private String sessionCookie;

    Feat138WorkbenchSupport(String base) {
        this.base = base;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    String base() {
        return base;
    }

    /**
     * 共享前置（审阅合入前优先项 3）：免鉴权就绪探测 + 本账号登录。
     *
     * @return blocker 原因（null 表示就绪）
     */
    String probeAndLogin(com.huawei.ascend.sit.config.TestConfig config)
            throws IOException, InterruptedException {
        Resp probe = get("/api/deployment");
        if (probe.status() != 200) {
            return "agent-dev-workbench 后端未就绪（GET /api/deployment 返回 "
                    + probe.status() + "）：" + base();
        }
        String user = config.getString("sut.workbench.username", "admin");
        String password = config.getString("sut.workbench.password", "");
        if (password == null || password.isBlank()) {
            password = System.getenv("WORKBENCH_ADMIN_PASSWORD");
        }
        if (password == null || password.isBlank()) {
            return "环境门禁：未配置登录凭据（sut.workbench.password 配置或 WORKBENCH_ADMIN_PASSWORD 环境变量）";
        }
        if (!login(user, password)) {
            return "环境门禁：本地账号登录失败（POST /api/auth/login）：" + user;
        }
        String llmBlocker = ensureDefaultLlmFromEnv();
        if (llmBlocker != null) {
            return llmBlocker;
        }
        return null;
    }

    /**
     * 启动声明目标并等待就绪（FRS §6 WB-02-02 旅程前置：受理 → 轮询操作结果 → 目标就绪）。
     * 已存在就绪实例时直接复用。
     *
     * @return blocker 原因（null 表示目标就绪）
     */
    String ensureReadyInstance(String sessionId, String run, long timeoutSeconds)
            throws IOException, InterruptedException {
        Resp snap = get("/api/sessions/" + sessionId + "/runtime");
        if (snap.status() != 200) {
            return "环境门禁：运行快照不可读（GET runtime 返回 " + snap.status() + "）";
        }
        for (JsonNode t : snap.body().path("targets")) {
            if ("ready".equals(t.path("state").asText())) {
                return null;
            }
        }
        JsonNode firstTarget = snap.body().path("targets").isEmpty()
                ? null
                : snap.body().path("targets").get(0);
        if (firstTarget == null) {
            return "环境门禁：运行声明无可用 target";
        }
        String runtimeId = firstTarget.path("runtimeId").asText(null);
        if (runtimeId == null) {
            return "环境门禁：target 缺 runtimeId";
        }
        String opId = "feat138-op-start-" + run + "-" + System.currentTimeMillis();
        ObjectNode body = operationRequest(opId, "start", runtimeId);
        Resp resp = sendWithBody("POST", "/api/sessions/" + sessionId + "/runtime/operations", body);
        if (resp.status() != 202) {
            return "环境门禁：start 受理返回 " + resp.status() + "（" + resp.body().toString() + "）";
        }
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            Resp after = get("/api/sessions/" + sessionId + "/runtime");
            if (after.status() == 200) {
                for (JsonNode t : after.body().path("targets")) {
                    if ("ready".equals(t.path("state").asText())) {
                        return null;
                    }
                }
                JsonNode opRecord = after.body().path("operations").path(opId);
                String status = opRecord.path("status").asText("");
                if (!status.isEmpty() && !"running".equals(status)) {
                    return "环境门禁：start 操作终态 " + status
                            + "（" + opRecord.toString() + "），实例未就绪";
                }
            }
            Thread.sleep(3000);
        }
        return "环境门禁：" + timeoutSeconds + " 秒内目标未就绪（start 操作仍在执行）";
    }

    /**
     * 配置默认模型提供方（凭据经环境变量注入，不落库到测试代码）。
     * 运行控制链（runtime_control）与业务预览依赖该提供方。
     *
     * @return blocker 原因（null 表示就绪或环境未配置模型，由具体用例门禁判定）
     */
    String ensureDefaultLlmFromEnv() throws IOException, InterruptedException {
        String baseUrl = System.getenv("LLM_API_BASE");
        String model = System.getenv("LLM_MODEL");
        String apiKey = System.getenv("LLM_API_KEY");
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            return null;
        }
        baseUrl = baseUrl.replaceAll("/+$/", "");
        baseUrl = baseUrl.endsWith("/chat/completions")
                ? baseUrl.substring(0, baseUrl.length() - "/chat/completions".length())
                : baseUrl;
        Resp settings = get("/api/settings/llm");
        if (settings.status() == 200
                && !settings.body().path("defaultProviderId").asText("").isBlank()
                && model.equals(settings.body().path("defaultModel").asText(""))) {
            return null;
        }
        ObjectNode provider = obj();
        provider.put("name", "feat138-provider");
        provider.put("baseURL", baseUrl);
        provider.put("protocol", "openai");
        ObjectNode modelNode = obj();
        modelNode.put("id", model);
        provider.putArray("models").add(modelNode);
        if (apiKey != null && !apiKey.isBlank()) {
            provider.put("apiKey", apiKey);
        }
        Resp created = sendWithBody("POST", "/api/providers", provider);
        if (created.status() != 200 && created.status() != 201) {
            return "环境门禁：模型提供方配置失败（POST /api/providers 返回 " + created.status() + "）";
        }
        String providerId = created.body().path("id").asText("");
        if (providerId.isBlank()) {
            return "环境门禁：模型提供方创建未返回 id";
        }
        ObjectNode llm = obj();
        llm.put("defaultProviderId", providerId);
        llm.put("defaultModel", model);
        Resp saved = sendWithBody("PUT", "/api/settings/llm", llm);
        return saved.status() == 200
                ? null
                : "环境门禁：默认模型设置失败（PUT /api/settings/llm 返回 " + saved.status() + "）";
    }

    /**
     * 在会话工程目录写入 schema 3 运行声明 fixture（deploy/deploy.json）并布置业务产物
     * （进程外 fixture：声明来自用户工程目录，不改写 SUT 内部状态）。
     *
     * <p>variant 语义（审阅门禁恢复）：
     * <ul>
     * <li>{@code single}（默认）：main（业务 jar，single）+ aux（同 jar，不自动启动）——
     *     为 F138-18 提供恒为非 ready 的声明目标；</li>
     * <li>{@code shared}：main（hosted 多 agent jar：/a2a/agents 暴露 agent-a/agent-b）
     *     + aux；targets whole-project(main/agent-a)、api-entry(main/agent-b)、
     *     aux-entry(aux)——为 F138-26 提供承载 ≥2 目标的共享实例；</li>
     * <li>{@code unhealthy}：main healthPath 指向不存在路径（健康失败注入，F138-32）；</li>
     * <li>{@code missing-artifact}：main artifact 指向不存在的产物（缺产物注入，F138-31）；</li>
     * <li>{@code ghost}：main 正常 + ghost-entry target 引用未声明的 runtimeId
     *     （托管目标未注册注入，F138-33，清单校验整体拒绝 UNKNOWN_RUNTIME）。</li>
     * </ul>
     *
     * @return blocker 原因（null 表示就绪）
     */
    String prepareRuntimeProject(String cwd, String run) {
        return prepareRuntimeProject(cwd, run, "single");
    }

    String prepareRuntimeProject(String cwd, String run, String variant) {
        if ("none".equals(variant)) {
            return null; // 无运行声明形态（F138-03 无远端通道判定用）
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(cwd);
            java.nio.file.Path deployDir = dir.resolve("deploy");
            java.nio.file.Files.createDirectories(deployDir);
            boolean shared = "shared".equals(variant);
            boolean unhealthy = "unhealthy".equals(variant);
            boolean missingArtifact = "missing-artifact".equals(variant);
            boolean ghost = "ghost".equals(variant);

            ObjectNode manifest = obj();
            manifest.put("schema", 3);
            manifest.put("agent", obj().put("name", "feat138-demo-agent"));
            manifest.put("manifestVersion", "feat138-manifest-" + run);
            manifest.put("defaultTargetId", "whole-project");

            com.fasterxml.jackson.databind.node.ArrayNode runtimes = manifest.putArray("runtimes");
            ObjectNode main = runtimes.addObject();
            main.put("id", "main");
            main.put("name", "主 Runtime");
            main.put("artifact", missingArtifact ? "deploy/missing-artifact.jar" : "deploy/demo-agent.jar");
            ObjectNode launch = main.putObject("launch");
            launch.put("kind", "java-jar");
            launch.putArray("jvmArgs").add("-Xmx512m");
            launch.putArray("args").add("--server.address=127.0.0.1").add("--server.port={{self.http.port}}");
            ObjectNode envSpec = main.putObject("environment");
            envSpec.putObject("LLM_API_KEY").put("ref", "model.apiKey");
            envSpec.putObject("LLM_API_BASE").put("ref", "model.baseURL");
            envSpec.putObject("LLM_MODEL").put("ref", "model.modelId");
            main.putObject("http").put("bind", "127.0.0.1").put("port", "dynamic");
            boolean unknownResult = "unknown-result".equals(variant);
            ObjectNode mainHealth = main.putObject("health");
            mainHealth.put("path", unhealthy ? "/no-such-health-404" : "/health")
                    .put("expectField", "agent_loaded")
                    .put("startPeriod", unknownResult ? 700 : (unhealthy ? 120 : 60));
            if (shared) {
                main.putObject("discovery").put("mode", "hosted").put("path", "/a2a/agents");
            } else {
                main.putObject("discovery").put("mode", "single");
            }
            main.putArray("dependsOn");

            if (!ghost && !"none".equals(variant)) {
                // single / shared 形态追加 aux Runtime（不自动启动，恒为非 ready）
                ObjectNode aux = runtimes.addObject();
                aux.put("id", "aux");
                aux.put("name", "辅助 Runtime");
                aux.put("artifact", "deploy/demo-agent.jar");
                ObjectNode auxLaunch = aux.putObject("launch");
                auxLaunch.put("kind", "java-jar");
                auxLaunch.putArray("jvmArgs").add("-Xmx512m");
                auxLaunch.putArray("args").add("--server.address=127.0.0.1").add("--server.port={{self.http.port}}");
                ObjectNode auxEnv = aux.putObject("environment");
                auxEnv.putObject("LLM_API_KEY").put("ref", "model.apiKey");
                auxEnv.putObject("LLM_API_BASE").put("ref", "model.baseURL");
                auxEnv.putObject("LLM_MODEL").put("ref", "model.modelId");
                aux.putObject("http").put("bind", "127.0.0.1").put("port", "dynamic");
                aux.putObject("health").put("path", "/health").put("expectField", "agent_loaded").put("startPeriod", 60);
                aux.putObject("discovery").put("mode", "single");
                aux.putArray("dependsOn");
            }

            com.fasterxml.jackson.databind.node.ArrayNode targets = manifest.putArray("targets");
            ObjectNode whole = targets.addObject();
            whole.put("id", "whole-project");
            whole.put("name", "整个工程");
            whole.put("runtimeId", "main");
            ObjectNode wholeQuery = whole.putObject("query");
            wholeQuery.put("protocol", "openjiuwen-query").put("path", "/v1/query");
            if (shared) {
                wholeQuery.put("agentId", "agent-a");
                ObjectNode api = targets.addObject();
                api.put("id", "api-entry");
                api.put("name", "API 入口");
                api.put("runtimeId", "main");
                api.putObject("query").put("protocol", "openjiuwen-query").put("path", "/v1/query")
                        .put("agentId", "agent-b");
            }
            if (!ghost) {
                ObjectNode auxEntry = targets.addObject();
                auxEntry.put("id", "aux-entry");
                auxEntry.put("name", "辅助入口");
                auxEntry.put("runtimeId", "aux");
                auxEntry.putObject("query").put("protocol", "openjiuwen-query").put("path", "/v1/query");
            } else {
                // F138-33：声明中引用未注册（未声明）的 Runtime
                ObjectNode ghostEntry = targets.addObject();
                ghostEntry.put("id", "ghost-entry");
                ghostEntry.put("name", "未注册入口");
                ghostEntry.put("runtimeId", "ghost-runtime");
                ghostEntry.putObject("query").put("protocol", "openjiuwen-query").put("path", "/v1/query");
            }
            java.nio.file.Files.writeString(dir.resolve("deploy").resolve("deploy.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    java.nio.charset.StandardCharsets.UTF_8);

            if (!missingArtifact) {
                java.nio.file.Path travel = businessJar("SUT_WORKBENCH_RUNTIME_JAR",
                        "/example/travel-demo-mainplan/0.1.3/travel-demo-mainplan-0.1.3.jar");
                if (travel == null) {
                    return "环境门禁：业务产物缺失（travel-demo-mainplan），可经 SUT_WORKBENCH_RUNTIME_JAR 指定";
                }
                java.nio.file.Path artifact = deployDir.resolve("demo-agent.jar");
                if (!java.nio.file.Files.exists(artifact)) {
                    java.nio.file.Files.copy(travel, artifact);
                }
            }
            if (shared) {
                java.nio.file.Path hosted = businessJar("SUT_WORKBENCH_HOSTED_JAR",
                        "/example/multi-react-travel-hosted-demo/0.1.3/multi-react-travel-hosted-demo-0.1.3.jar");
                if (hosted == null) {
                    return "环境门禁：hosted 业务产物缺失（multi-react-travel-hosted-demo），可经 SUT_WORKBENCH_HOSTED_JAR 指定";
                }
                java.nio.file.Path hostedArtifact = deployDir.resolve("hosted-agent.jar");
                if (!java.nio.file.Files.exists(hostedArtifact)) {
                    java.nio.file.Files.copy(hosted, hostedArtifact);
                }
                // shared 形态 main 使用 hosted 产物
                ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("runtimes").get(0))
                        .put("artifact", "deploy/hosted-agent.jar");
                java.nio.file.Files.writeString(dir.resolve("deploy").resolve("deploy.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            return null;
        } catch (Exception e) {
            return "环境门禁：运行声明 fixture 写入失败：" + e.getMessage();
        }
    }

    /** 从 .m2 解析业务产物路径；环境变量优先。缺失返回 null。 */
    private java.nio.file.Path businessJar(String envKey, String m2Path) {
        String source = System.getenv(envKey);
        if (source == null || source.isBlank()) {
            source = System.getProperty("user.home") + "/.m2/repository/com/openjiuwen" + m2Path;
        }
        java.nio.file.Path p = java.nio.file.Path.of(source);
        return java.nio.file.Files.exists(p) ? p : null;
    }

    /** 自建工作区 + 会话 + fixture 的组合前置（F138-31/32/33 自足构造）。 */
    record FixtureSession(String blocker, String sessionId, String workspaceId) {
    }

    FixtureSession createFixtureSession(com.huawei.ascend.sit.config.TestConfig config,
                                        String wsName, String title, String run, String variant)
            throws IOException, InterruptedException {
        String blocker = probeAndLogin(config);
        if (blocker != null) {
            return new FixtureSession(blocker, null, null);
        }
        Resp ws = sendWithBody("POST", "/api/workspaces", workspaceByName(wsName));
        if (ws.status() != 200 && ws.status() != 201) {
            return new FixtureSession("环境门禁：工作区创建返回 " + ws.status(), null, null);
        }
        String wsId = ws.body().path("id").asText("");
        Resp session = sendWithBody("POST", "/api/sessions", sessionCreate(wsId, title));
        if (session.status() != 200 && session.status() != 201) {
            return new FixtureSession("环境门禁：会话创建返回 " + session.status()
                    + "（" + session.body().toString() + "）", null, wsId);
        }
        String sessionId = session.body().path("id").asText("");
        String cwd = session.body().path("cwd").asText("");
        if (cwd.isBlank()) {
            return new FixtureSession("环境门禁：会话响应缺 cwd", sessionId, wsId);
        }
        String fixtureBlocker = prepareRuntimeProject(cwd, run, variant);
        if (fixtureBlocker != null) {
            return new FixtureSession(fixtureBlocker, sessionId, wsId);
        }
        return new FixtureSession(null, sessionId, wsId);
    }

    /** 本地账号登录（POST /api/auth/login），成功后保存会话 Cookie。 */
    boolean login(String username, String password) throws IOException, InterruptedException {
        ObjectNode body = obj();
        body.put("username", username);
        body.put("password", password);
        Resp resp = send("POST", "/api/auth/login", body);
        if (resp.status() != 200) {
            return false;
        }
        String setCookie = resp.header("set-cookie");
        if (setCookie == null || setCookie.isBlank()) {
            return false;
        }
        this.sessionCookie = setCookie.split(";", 2)[0].trim();
        return true;
    }

    /** 6 位随机 hex 运行后缀，用于工作区/操作/请求/业务会话对象的隔离。 */
    static String runId() {
        SecureRandom random = new SecureRandom();
        return String.format("%06x", random.nextInt(0x1000000));
    }

    record Resp(int status, JsonNode body, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    Resp get(String path) throws IOException, InterruptedException {
        return send("GET", path, null);
    }

    Resp delete(String path) throws IOException, InterruptedException {
        return send("DELETE", path, null);
    }

    Resp sendWithBody(String method, String path, ObjectNode body) throws IOException, InterruptedException {
        return send(method, path, body);
    }

    private Resp send(String method, String path, ObjectNode body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                // 运行操作受理内含同步远端 inspect（控制链 11~15s+），预览/历史轮询亦经控制链；
                // 30s 会把慢但正常的受理误判为 status=0，放宽到 120s。
                .timeout(Duration.ofSeconds(120));
        if (sessionCookie != null && !sessionCookie.isBlank()) {
            builder.header("Cookie", sessionCookie);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8));
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
        Map<String, String> lower = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> lower.put(k.toLowerCase(), String.join(", ", v)));
        return new Resp(response.statusCode(), bodyJson, lower);
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

    ObjectNode sessionCreate(String workspaceId, String title) {
        ObjectNode body = obj();
        body.put("workspaceId", workspaceId);
        body.put("title", title);
        return body;
    }

    /** 运行操作请求体（L2 §2.3 入参）。 */
    ObjectNode operationRequest(String operationId, String action, String runtimeId) {
        ObjectNode body = obj();
        body.put("operationId", operationId);
        body.put("action", action);
        body.put("runtimeId", runtimeId);
        return body;
    }

    /** 业务预览请求体（L2 §2.4 入参：text/targetId/instanceId/conversationId/requestId）。 */
    ObjectNode previewRequest(String text, String targetId, String instanceId,
                              String conversationId, String requestId) {
        ObjectNode body = obj();
        body.put("text", text);
        body.put("targetId", targetId);
        body.put("instanceId", instanceId);
        body.put("conversationId", conversationId);
        body.put("requestId", requestId);
        return body;
    }
}
