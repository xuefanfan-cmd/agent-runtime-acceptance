package com.huawei.ascend.sit.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * SIT-only mock 扮演 search-agent 作为 deep-research 的下游被调方 + 反向 callback 发射方。
 *
 * <p><b>用途</b>:验证 deep-research 作 caller 的 push notification 端到端契约:
 * <ol>
 *   <li>deep-research 收到上游 SendMessage(不带 callback URL)后,自己调 search-agent 时应携带
 *       {@code taskPushNotificationConfig{url,id,token}} —— 本 mock 捕获这个 pushConfig 到
 *       {@link #capturedPushConfigs()},SIT 侧可断言 URL/id/token 是否符合契约(非空、URL 指回
 *       deep-research 自身 /a2a/push-notifications/callback、id/token 唯一);</li>
 *   <li>search-agent 完成任务后应<b>反向</b> POST callback 到 URL_B(即 deep-research 的 receiver
 *       端点),携带 {@code X-A2A-Notification-Id + Authorization: Bearer <token>};本 mock 用
 *       {@link CallbackBehavior} 变体测试 deep-research 侧 receiver 的 auth 校验:</li>
 * </ol>
 *
 * <table border="1">
 *   <tr><th>Behavior</th><th>Authorization</th><th>X-A2A-Notification-Id</th><th>期望 deep-research 侧</th></tr>
 *   <tr><td>{@link CallbackBehavior#HAPPY}</td><td>Bearer &lt;正 token&gt;</td><td>正 nid</td><td>200/202 接受</td></tr>
 *   <tr><td>{@link CallbackBehavior#WRONG_TOKEN}</td><td>Bearer sit-bad-token</td><td>正 nid</td><td>401/403 拒</td></tr>
 *   <tr><td>{@link CallbackBehavior#MISSING_TOKEN}</td><td>(不设)</td><td>正 nid</td><td>401/403 拒</td></tr>
 *   <tr><td>{@link CallbackBehavior#WRONG_TASK_ID}</td><td>Bearer &lt;正 token&gt;</td><td>正 nid,但 callback body 里 result.task.id 篡改为随机 UUID</td><td>404 binding-not-found</td></tr>
 * </table>
 *
 * <p><b>Binding 判定依据</b>(dev-team 2026-08-08 明确):receiver 侧真正 binding lookup 用
 * {@code callback.result.task.id == shadow._remote_batch.members[].remoteTaskId};
 * {@code notificationId} 仅参与幂等/冲突检查,不用于 binding。因此 WRONG_TASK_ID 场景
 * 篡改 callback body 里的 task.id 是显性化 binding-not-found 的正确手段。
 *
 * <p><b>Wire 兼容</b>:agent card 模板抄 {@link MockRemoteAgentServer#buildCardJson},
 * capabilities.pushNotifications = <b>false</b>(符合用户 spec:search-agent 是被动 sender 侧)。
 * SendMessage response 返 JSON-RPC {@code result.task} 骨架,让 SDK 反序列化通过;task.status.state
 * 默认 {@code TASK_STATE_COMPLETED},让 deep-research 认为 sub-agent 已完成。
 *
 * <p><b>Callback 时机</b>:收到 SendMessage 后,先返 skeleton response,然后 scheduled 200ms 后
 * 异步 POST callback。间隔让 deep-research 有时间处理 skeleton response 并进入等待状态。
 *
 * <p><b>线程安全</b>:计数用 {@link AtomicInteger},列表用 {@link CopyOnWriteArrayList};HttpServer
 * 用 cached thread pool 避免 handler 阻塞。
 */
public final class MockSearchAgentServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MockSearchAgentServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum CallbackBehavior {
        /** 用捕获的 pushConfig.token + pushConfig.id + response 分配的 taskId 正确 fire callback. */
        HAPPY,
        /** Bearer 携错 token,nid/taskId 均正确. */
        WRONG_TOKEN,
        /** 不携 Authorization header,nid/taskId 均正确. */
        MISSING_TOKEN,
        /** Authorization + nid 正确,但 callback body 里 result.task.id 用随机 UUID 篡改
         *  → 期望 receiver 用 taskId lookup 落空,返 404 binding-not-found. */
        WRONG_TASK_ID,
        /** 不 fire callback(测超时 / lost 场景). */
        SKIP
    }

    /** 记录 deep-research 发过来的 pushNotificationConfig 三元组. */
    public record CapturedPushConfig(String url, String id, String token) {}

    /** 记录 mock fire callback 后 deep-research 返回的响应. */
    public record CallbackResult(int status, String body, String responseHeaders) {}

    private final HttpServer server;
    private final String baseUrl;
    private final CallbackBehavior behavior;
    private final long callbackDelayMs;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mock-search-callback");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final AtomicInteger a2aPostCount = new AtomicInteger();
    private final List<String> a2aPostBodies = new CopyOnWriteArrayList<>();
    private final List<CapturedPushConfig> capturedPushConfigs = new CopyOnWriteArrayList<>();
    private final List<CallbackResult> callbackResults = new CopyOnWriteArrayList<>();
    private final AtomicInteger cardGetCount = new AtomicInteger();

    private MockSearchAgentServer(HttpServer server, String baseUrl, CallbackBehavior behavior, long callbackDelayMs) {
        this.server = server;
        this.baseUrl = baseUrl;
        this.behavior = behavior;
        this.callbackDelayMs = callbackDelayMs;
    }

    public String baseUrl() { return baseUrl; }
    public int a2aPostCount() { return a2aPostCount.get(); }
    public List<String> a2aPostBodies() { return Collections.unmodifiableList(a2aPostBodies); }
    public List<CapturedPushConfig> capturedPushConfigs() { return Collections.unmodifiableList(capturedPushConfigs); }
    public List<CallbackResult> callbackResults() { return Collections.unmodifiableList(callbackResults); }
    public int cardGetCount() { return cardGetCount.get(); }

    @Override
    public void close() {
        scheduler.shutdownNow();
        server.stop(0);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private CallbackBehavior behavior = CallbackBehavior.HAPPY;
        private long callbackDelayMs = 200L;
        private boolean advertisePushNotifications = false;

        public Builder callbackBehavior(CallbackBehavior behavior) {
            this.behavior = behavior;
            return this;
        }

        public Builder callbackDelayMs(long ms) {
            this.callbackDelayMs = ms;
            return this;
        }

        /**
         * Whether AgentCard advertises {@code capabilities.pushNotifications=true}.
         * <p>User's spec: search-agent is push=false. But some SUT implementations may filter
         * outbound pushConfig attachment by sub-agent's advertised cap; toggling to true isolates
         * whether the gap is in SUT's outbound gating vs the env flag itself.
         */
        public Builder advertisePushNotifications(boolean value) {
            this.advertisePushNotifications = value;
            return this;
        }

        public MockSearchAgentServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            String baseUrl = "http://127.0.0.1:" + port;
            String a2aUrl = baseUrl + "/a2a";
            String cardJson = buildCardJson(a2aUrl, advertisePushNotifications);

            MockSearchAgentServer holder = new MockSearchAgentServer(server, baseUrl, behavior, callbackDelayMs);

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
                handleA2aSendMessage(exchange, holder);
            });

            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return holder;
        }

        private static String buildCardJson(String a2aUrl, boolean advertisePushNotifications) {
            // 兼容 A2A SDK 反序列化最小可用集. pushNotifications 由 Builder toggle 控制,
            // 用户 spec 定位:search 是被动 sender 侧,自己不做 outbound push (默认 false);
            // 但探针场景需要翻 true 以隔离 SUT 是否按 sub-agent card cap 过滤 outbound attach.
            return "{"
                    + "\"name\":\"MockSearchAgent\","
                    + "\"description\":\"SIT mock search-agent that fires callbacks\","
                    + "\"provider\":{\"organization\":\"SIT-Mock\",\"url\":\"\"},"
                    + "\"version\":\"0.1.0\","
                    + "\"documentationUrl\":null,"
                    + "\"capabilities\":{\"streaming\":true,\"pushNotifications\":"
                    + advertisePushNotifications
                    + ",\"extendedAgentCard\":false,\"extensions\":[]},"
                    + "\"defaultInputModes\":[\"text\",\"text/plain\"],"
                    + "\"defaultOutputModes\":[\"text\",\"text/plain\"],"
                    + "\"skills\":[{\"id\":\"web_search\",\"name\":\"web_search\","
                    + "\"description\":\"web search tool\",\"tags\":[\"search\"]}],"
                    + "\"securitySchemes\":{},"
                    + "\"securityRequirements\":[],"
                    + "\"iconUrl\":null,"
                    + "\"supportedInterfaces\":[{"
                    + "\"protocolBinding\":\"JSONRPC\","
                    + "\"url\":\"" + a2aUrl + "\","
                    + "\"tenant\":null,"
                    + "\"protocolVersion\":\"1.0\"}],"
                    + "\"signatures\":[],"
                    + "\"url\":\"" + a2aUrl + "\","
                    + "\"preferredTransport\":\"JSONRPC\","
                    + "\"additionalInterfaces\":[]"
                    + "}";
        }
    }

    private static void handleA2aSendMessage(HttpExchange exchange, MockSearchAgentServer holder) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
        holder.a2aPostCount.incrementAndGet();
        holder.a2aPostBodies.add(bodyStr);

        String rpcId = null;
        String contextId = "ctx-mock-" + UUID.randomUUID().toString().substring(0, 8);
        // 关键:此 taskId 会作为 sub-agent 的 remoteTaskId 被 deep-research 侧 shadow._remote_batch
        // 记录;稍后 callback body 里 result.task.id 必须回填这个 <b>同一</b> taskId,receiver 才能
        // 用 callback.result.task.id == shadow._remote_batch.members[].remoteTaskId 命中 binding。
        // (dev-team 2026-08-08 明确:notificationId 仅幂等/冲突用,真正 binding 判定是 task.id)
        String taskId = UUID.randomUUID().toString();

        try {
            JsonNode root = MAPPER.readTree(bodyStr);
            rpcId = root.path("id").asText(null);
            JsonNode params = root.path("params");
            String bodyContextId = params.path("message").path("contextId").asText(null);
            if (bodyContextId != null && !bodyContextId.isBlank()) contextId = bodyContextId;

            // 捕获 pushConfig(deep-research 携出的 URL_B + id + token)
            JsonNode pushConfig = params.path("configuration").path("taskPushNotificationConfig");
            if (!pushConfig.isMissingNode() && !pushConfig.isNull()) {
                CapturedPushConfig cap = new CapturedPushConfig(
                        pushConfig.path("url").asText(null),
                        pushConfig.path("id").asText(null),
                        pushConfig.path("token").asText(null));
                holder.capturedPushConfigs.add(cap);
                LOG.info("[mock-search] captured pushConfig " + cap + " (subAgentTaskId=" + taskId + ")");

                // schedule callback fire based on behavior — 关键:把 response 侧同一 taskId 传下去
                if (holder.behavior != CallbackBehavior.SKIP) {
                    final String cbContextId = contextId;
                    final String cbTaskId = taskId;
                    holder.scheduler.schedule(() -> holder.fireCallback(cap, cbContextId, cbTaskId),
                            holder.callbackDelayMs, TimeUnit.MILLISECONDS);
                }
            } else {
                LOG.warning("[mock-search] SendMessage did NOT carry pushConfig — deep-research 未附带 callback URL");
            }
        } catch (Exception e) {
            LOG.warning("[mock-search] failed to parse SendMessage body: " + e.getMessage());
        }

        // 返 skeleton response(taskId 已在 try 前 allocated,response 与 callback 共用同一 id)
        String response = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{"
                        + "\"task\":{"
                        + "\"id\":\"%s\","
                        + "\"contextId\":\"%s\","
                        + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},"
                        + "\"artifacts\":[{\"artifactId\":\"mock-artifact\",\"parts\":[{\"text\":\"mock search result\"}]}],"
                        + "\"history\":[]"
                        + "}}}",
                rpcId == null ? "null" : "\"" + rpcId + "\"", taskId, contextId);
        respond(exchange, 200, "application/json", response);
    }

    private void fireCallback(CapturedPushConfig cap, String contextId, String subAgentTaskId) {
        if (cap.url() == null || cap.url().isBlank()) {
            LOG.warning("[mock-search] pushConfig.url is blank, skip callback fire");
            return;
        }

        // nid 仅参与幂等/冲突检查(dev-team 2026-08-08),全场景保持正确 —— 保证只有 taskId 篡改时
        // 才能观察到 binding 层 rejection,不被 nid 侧噪声干扰。
        String nid = cap.id();
        // 回填 SendMessage response 里同一 taskId(HAPPY / TOKEN 场景) 或随机 UUID(WRONG_TASK_ID)。
        // 这是 receiver 侧 binding 判定的真正依据:callback.result.task.id ==
        // shadow._remote_batch.members[].remoteTaskId。
        String taskId = behavior == CallbackBehavior.WRONG_TASK_ID
                ? UUID.randomUUID().toString()
                : subAgentTaskId;
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"notificationId\":\"%s\",\"result\":{"
                        + "\"task\":{"
                        + "\"id\":\"%s\","
                        + "\"contextId\":\"%s\","
                        + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},"
                        + "\"artifacts\":[{\"artifactId\":\"mock-cb-artifact\",\"parts\":[{\"text\":\"callback body\"}]}]"
                        + "}}}",
                nid, taskId, contextId);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(cap.url()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-A2A-Notification-Id", nid);

        switch (behavior) {
            case HAPPY:
            case WRONG_TASK_ID:
                if (cap.token() != null && !cap.token().isBlank()) {
                    builder.header("Authorization", "Bearer " + cap.token());
                }
                break;
            case WRONG_TOKEN:
                builder.header("Authorization", "Bearer sit-bad-token-" + UUID.randomUUID());
                break;
            case MISSING_TOKEN:
                // 不设 Authorization
                break;
            case SKIP:
                return;
        }

        try {
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            callbackResults.add(new CallbackResult(
                    response.statusCode(), response.body(), response.headers().toString()));
            LOG.info(String.format("[mock-search] callback fire [%s] → status=%d body=%s",
                    behavior, response.statusCode(), response.body()));
        } catch (Exception e) {
            LOG.warning("[mock-search] callback fire failed: " + e.getMessage());
            callbackResults.add(new CallbackResult(-1, "IOException: " + e.getMessage(), ""));
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
