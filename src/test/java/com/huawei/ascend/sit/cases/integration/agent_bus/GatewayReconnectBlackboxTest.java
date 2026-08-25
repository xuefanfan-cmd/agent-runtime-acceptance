package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Feature("FEAT-011: 客户端调用路由转发")
@Tag("feat-011")
@Tag("integration")
@Tag("blackbox")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayReconnectBlackboxTest {
    private BackingServices services;
    private SutStack stack;
    private RuntimeStub runtimeA;
    private RuntimeStub runtimeB;
    private AgentBusExternalFixture fixture;

    @BeforeAll
    void startGatewayStack() throws Exception {
        runtimeA = new RuntimeStub("owner-a");
        runtimeB = new RuntimeStub("owner-b");
        runtimeA.start();
        runtimeB.start();

        TestConfig config = TestConfig.load();
        services = new BackingServices(config, Set.of("postgres"), new TestContainerFactory(null));
        stack = SutStack.builder(config).backingServices(services)
                .agent("registry-center")
                .agent("gateway-direct", gateway -> gateway.downstream(
                        "registry-center", "gateway.rdc.base-url"))
                .start();
        fixture = AgentBusExternalFixture.forEndpoints(
                stack.baseUrl("registry-center"), stack.baseUrl("gateway-direct"), null);
    }

    @AfterAll
    void stopGatewayStack() throws Exception {
        if (stack != null) {
            stack.close();
        }
        if (services != null) {
            services.close();
        }
        if (runtimeA != null) {
            runtimeA.close();
        }
        if (runtimeB != null) {
            runtimeB.close();
        }
    }

    @Test
    @Story("F011-R01: GetTask 路由到原 owner")
    @DisplayName("Feat-011 GetTask 只回创建所得 owner")
    void getTaskReturnsToOriginalOwner() throws Exception {
        String agentId = registerTwoOwners("query-owner");
        var created = fixture.direct(agentId, "query original owner");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String taskId = taskId(created.body());
        RuntimeStub owner = ownerOf(taskId);
        RuntimeStub other = owner == runtimeA ? runtimeB : runtimeA;
        int ownerQueriesBefore = owner.count("GetTask");
        int otherQueriesBefore = other.count("GetTask");
        int createsBefore = totalCount("SendMessage");

        var response = fixture.directGetTask(taskId);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(taskId(response.body())).isEqualTo(taskId);
        assertThat(owner.count("GetTask")).isEqualTo(ownerQueriesBefore + 1);
        assertThat(other.count("GetTask")).isEqualTo(otherQueriesBefore);
        assertThat(totalCount("SendMessage")).isEqualTo(createsBefore);
    }

    @Test
    @Story("F011-R02: Task 快照透明透传且不泄漏拓扑")
    @DisplayName("Feat-011 GetTask 透明返回工作中与终态快照")
    void getTaskTransparentlyReturnsWorkingAndTerminalSnapshots() throws Exception {
        String agentId = registerTwoOwners("query-snapshot");
        var created = fixture.direct(agentId, "query transparent snapshot");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String taskId = taskId(created.body());
        RuntimeStub owner = ownerOf(taskId);
        RuntimeStub other = owner == runtimeA ? runtimeB : runtimeA;
        int ownerQueriesBefore = owner.count("GetTask");
        int otherQueriesBefore = other.count("GetTask");

        var first = fixture.directGetTask(taskId);
        owner.terminal(taskId);
        var second = fixture.directGetTask(taskId);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        JsonNode working = AgentBusExternalFixture.JSON.readTree(first.body()).path("result");
        JsonNode completed = AgentBusExternalFixture.JSON.readTree(second.body()).path("result");
        assertThat(working.path("id").asText()).isEqualTo(taskId);
        assertThat(completed.path("id").asText()).isEqualTo(taskId);
        assertThat(working.at("/status/state").asText()).isEqualTo("TASK_STATE_WORKING");
        assertThat(completed.at("/status/state").asText()).isEqualTo("TASK_STATE_COMPLETED");
        assertThat(working.at("/artifacts/0/parts/0/text").asText()).isEqualTo("snapshot-" + owner.name());
        assertThat(working.at("/history/0/parts/0/text").asText()).isEqualTo("history-" + owner.name());
        assertThat(working.at("/metadata/snapshotOwner").asText()).isEqualTo(owner.name());
        assertThat(working.at("/metadata/outputOffset").asInt()).isEqualTo(37);
        assertThat(completed.path("artifacts")).isEqualTo(working.path("artifacts"));
        assertThat(completed.path("history")).isEqualTo(working.path("history"));
        assertThat(completed.path("metadata")).isEqualTo(working.path("metadata"));
        assertThat(completed.at("/status/message/parts/0/text").asText())
                .isEqualTo("terminal-" + owner.name());
        assertThat(first.body() + second.body())
                .doesNotContain("routeHandle", "endpointUrl", owner.url());
        assertThat(owner.count("GetTask")).isEqualTo(ownerQueriesBefore + 2);
        assertThat(other.count("GetTask")).isEqualTo(otherQueriesBefore);
    }

    @Test
    @Story("F011-R03: 重复 GetTask 无执行副作用")
    @DisplayName("Feat-011 重复 GetTask 不重新创建或触达其他 owner")
    void repeatedGetTaskDoesNotRecreateTask() throws Exception {
        String agentId = registerTwoOwners("query-repeat");
        var created = fixture.direct(agentId, "query without side effects");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String taskId = taskId(created.body());
        RuntimeStub owner = ownerOf(taskId);
        RuntimeStub other = owner == runtimeA ? runtimeB : runtimeA;
        int ownerQueriesBefore = owner.count("GetTask");
        int otherQueriesBefore = other.count("GetTask");
        int createsBefore = totalCount("SendMessage");

        var first = fixture.directGetTask(taskId);
        var second = fixture.directGetTask(taskId);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(taskId(first.body())).isEqualTo(taskId);
        assertThat(taskId(second.body())).isEqualTo(taskId);
        assertThat(owner.count("GetTask")).isEqualTo(ownerQueriesBefore + 2);
        assertThat(other.count("GetTask")).isEqualTo(otherQueriesBefore);
        assertThat(totalCount("SendMessage")).isEqualTo(createsBefore);
    }

    @Test
    @Story("F011-S01: SubscribeToTask 回原 owner 并桥接事件")
    @DisplayName("Feat-011 SubscribeToTask 桥接原 owner 当前快照和后续事件")
    void subscribeReturnsToOriginalOwnerAndBridgesEvents() throws Exception {
        String agentId = registerTwoOwners("subscribe-owner");
        var created = fixture.direct(agentId, "subscribe original owner");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String taskId = taskId(created.body());
        RuntimeStub owner = ownerOf(taskId);
        RuntimeStub other = owner == runtimeA ? runtimeB : runtimeA;
        int ownerSubscriptionsBefore = owner.count("SubscribeToTask");
        int otherSubscriptionsBefore = other.count("SubscribeToTask");

        var subscription = fixture.directSubscribeTask(taskId);

        assertThat(subscription.statusCode()).as(subscription.body()).isEqualTo(200);
        assertThat(subscription.body()).contains(taskId, "TASK_STATE_WORKING", "TASK_STATE_COMPLETED")
                .doesNotContain("routeHandle", "endpointUrl", owner.url());
        assertThat(owner.count("SubscribeToTask")).isEqualTo(ownerSubscriptionsBefore + 1);
        assertThat(other.count("SubscribeToTask")).isEqualTo(otherSubscriptionsBefore);
    }

    @Test
    @Story("F011-S02: 重订阅不重新执行或创建 Task")
    @DisplayName("Feat-011 SubscribeToTask 不发送第二个创建类请求")
    void subscribeDoesNotSendCreateAgain() throws Exception {
        String agentId = registerTwoOwners("subscribe-no-create");
        var created = fixture.direct(agentId, "subscribe without recreation");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String taskId = taskId(created.body());
        int createsBefore = totalCount("SendMessage");
        int streamingCreatesBefore = totalCount("SendStreamingMessage");
        int subscriptionsBefore = totalCount("SubscribeToTask");

        var subscription = fixture.directSubscribeTask(taskId);

        assertThat(subscription.statusCode()).as(subscription.body()).isEqualTo(200);
        assertThat(subscription.body()).contains(taskId);
        assertThat(totalCount("SubscribeToTask")).isEqualTo(subscriptionsBefore + 1);
        assertThat(totalCount("SendMessage")).isEqualTo(createsBefore);
        assertThat(totalCount("SendStreamingMessage")).isEqualTo(streamingCreatesBefore);
    }

    @Test
    @Story("F011-S03: Client 断开只释放 SSE Bridge")
    @DisplayName("Feat-011 Client 主动断开订阅时释放 Bridge 且不取消或重建 Task")
    void clientDisconnectReleasesBridgeWithoutCancelingOrRecreatingTask() throws Exception {
        String agentId = registerSingleOwner("client-disconnect", runtimeA);
        String taskId = taskId(fixture.direct(agentId, "client disconnect reconnect").body());
        runtimeA.slowSubscription(taskId);
        int createsBefore = totalCount("SendMessage");
        int subscriptionsBefore = totalCount("SubscribeToTask");
        int cancelsBefore = totalCount("CancelTask");
        int logCountBefore = bridgeReleaseLogCount();

        HttpResponse<InputStream> response = fixture.directSubscribeTaskStream(taskId);
        assertThat(response.statusCode()).isEqualTo(200);
        try (InputStream input = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            assertThat(readFirstDataLine(reader)).contains(taskId);
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(bridgeReleaseLogCount()).isGreaterThan(logCountBefore));
        assertThat(totalCount("SendMessage")).isEqualTo(createsBefore);
        assertThat(totalCount("SubscribeToTask")).isEqualTo(subscriptionsBefore + 1);
        assertThat(totalCount("CancelTask")).isEqualTo(cancelsBefore);
    }

    @Disabled("blocked: current Gateway response omits Content-Type after writing SubscribeToTask SSE")
    @Test
    @Story("F011-S01.media-type: SubscribeToTask 使用标准 SSE media type")
    @DisplayName("Feat-011 SubscribeToTask 响应声明 text/event-stream")
    void subscribeResponseUsesEventStreamContentType() throws Exception {
        String agentId = registerTwoOwners("media");
        String taskId = taskId(fixture.direct(agentId, "subscribe media type").body());

        var subscription = fixture.directSubscribeTask(taskId);

        assertThat(subscription.headers().firstValue("content-type").orElse(""))
                .containsIgnoringCase("text/event-stream");
    }

    @Disabled("blocked: Gateway must preserve terminal SubscribeToTask JSON-RPC error before this can pass")
    @Test
    @Story("F011-S04: 终态订阅错误透明返回")
    @DisplayName("Feat-011 终态订阅保留 UnsupportedOperation 供 Client 回退 GetTask")
    void terminalSubscriptionPreservesProtocolError() throws Exception {
        String agentId = registerTwoOwners("terminal");
        var created = fixture.direct(agentId, "terminal reconnect");
        String taskId = taskId(created.body());
        ownerOf(taskId).terminal(taskId);

        var subscription = fixture.directSubscribeTask(taskId);

        assertThat(subscription.body()).contains("-32004", "task is already terminal");
        assertThat(taskId(fixture.directGetTask(taskId).body())).isEqualTo(taskId);
    }

    @Test
    @Story("F011-R01.invalid-query: GetTask 参数校验")
    @DisplayName("Feat-011 GetTask 缺少 taskId 时受控失败且不触达 Runtime")
    void getTaskWithoutTaskIdIsRejectedBeforeForwarding() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"missing-id\","
                + "\"method\":\"GetTask\",\"params\":{}}";
        int beforeA = runtimeA.methods().size();
        int beforeB = runtimeB.methods().size();

        var response = fixture.postRaw(false, body, AgentBusExternalFixture.TOKEN);

        assertThat(response.statusCode()).as(response.body()).isBetween(400, 499);
        assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl", "localhost");
        assertThat(runtimeA.methods()).hasSize(beforeA);
        assertThat(runtimeB.methods()).hasSize(beforeB);
    }

    @Test
    @Story("GW-R02: 未知 Task owner")
    @DisplayName("Feat-011 未知 taskId 不重选 owner 且不触达 Runtime")
    void unknownTaskIdIsRejectedWithoutForwarding() throws Exception {
        int beforeA = runtimeA.methods().size();
        int beforeB = runtimeB.methods().size();

        var unknown = fixture.directGetTask("task-never-owned-" + UUID.randomUUID());

        assertThat(unknown.statusCode()).as(unknown.body()).isBetween(200, 299);
        assertThat(unknown.body()).contains("CONTINUATION_FAILED", "no sticky owner")
                .doesNotContain("routeHandle", "endpointUrl", "localhost");
        assertThat(runtimeA.methods()).hasSize(beforeA);
        assertThat(runtimeB.methods()).hasSize(beforeB);
    }

    @Test
    @Story("GW-R03: routeHandle 解析失败")
    @DisplayName("Feat-011 原 owner 注销后 routeHandle 解析失败且不触达 Runtime")
    void staleRouteHandleIsRejectedWithoutForwarding() throws Exception {
        String agentId = registerSingleOwner("stale-route", runtimeA);
        var created = fixture.direct(agentId, "stale route handle");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String taskId = taskId(created.body());
        int beforeOwnerRequests = runtimeA.requests();
        int beforeOtherRequests = runtimeB.requests();
        int cancelsBefore = totalCount("CancelTask");
        fixture.deregisterRuntime(agentId);

        var response = fixture.directGetTask(taskId);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(response.body()).contains("ROUTE_RESOLVE_FAILED")
                .doesNotContain("routeHandle", "endpointUrl", runtimeA.url(), runtimeB.url());
        assertThat(runtimeA.requests()).isEqualTo(beforeOwnerRequests);
        assertThat(runtimeB.requests()).isEqualTo(beforeOtherRequests);
        assertThat(totalCount("CancelTask")).isEqualTo(cancelsBefore);
    }

    @Test
    @Story("F011-R05: Runtime TaskNotFound 透传")
    @DisplayName("Feat-011 Runtime 的 TASK_NOT_FOUND 原样返回且不回退其他 owner")
    void runtimeTaskNotFoundIsPreservedWithoutFailover() throws Exception {
        String agentId = registerSingleOwner("task-not-found", runtimeA);
        var created = fixture.direct(agentId, "task not found reconnect");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String taskId = taskId(created.body());
        runtimeA.missing(taskId);
        int beforeOwnerQueries = runtimeA.count("GetTask");
        int beforeOwnerCreates = runtimeA.count("SendMessage");
        int beforeOtherRequests = runtimeB.methods().size();

        var response = fixture.directGetTask(taskId);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":-32001", "task not found")
                .doesNotContain("routeHandle", "endpointUrl", "localhost");
        assertThat(runtimeA.count("GetTask")).isEqualTo(beforeOwnerQueries + 1);
        assertThat(runtimeA.count("SendMessage")).isEqualTo(beforeOwnerCreates);
        assertThat(runtimeB.methods()).hasSize(beforeOtherRequests);
    }

    @Test
    @Story("F011-R04: Runtime 不可达")
    @DisplayName("Feat-011 原 owner Runtime 不可达时 Gateway 失败且不回退其他 owner")
    void unavailableOwnerRuntimeDoesNotFailOverToAnotherOwner() throws Exception {
        String agentId = registerTwoOwners("unavailable");
        String taskId = taskId(fixture.direct(agentId, "bind unavailable owner").body());
        RuntimeStub owner = ownerOf(taskId);
        RuntimeStub other = owner == runtimeA ? runtimeB : runtimeA;
        int beforeOwnerRequests = owner.requests();
        int beforeOtherRequests = other.requests();
        owner.unavailable(true);

        try {
            var response = fixture.directGetTask(taskId);

            assertThat(owner.requests()).isEqualTo(beforeOwnerRequests + 1);
            assertThat(other.requests()).isEqualTo(beforeOtherRequests);
            assertThat(response.statusCode()).as(response.body()).isBetween(400, 599);
            assertThat(response.body()).doesNotContain("routeHandle", "endpointUrl", "localhost");
        } finally {
            owner.unavailable(false);
        }
    }

    private String registerTwoOwners(String scenario) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String agentId = "rc-" + scenario + "-" + unique;
        fixture.registerRuntime(agentId, "svc-a-" + unique, runtimeA.url(), "1.0", 100);
        fixture.registerRuntime(agentId, "svc-b-" + unique, runtimeB.url(), "1.0", 100);
        return agentId;
    }

    private String registerSingleOwner(String scenario, RuntimeStub owner) throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String agentId = "rc-" + scenario + "-" + unique;
        fixture.registerRuntime(agentId, "svc-" + unique, owner.url(), "1.0", 100);
        return agentId;
    }

    private RuntimeStub ownerOf(String taskId) {
        if (taskId.startsWith(runtimeA.name())) {
            return runtimeA;
        }
        assertThat(taskId).startsWith(runtimeB.name());
        return runtimeB;
    }

    private int totalCount(String method) {
        return runtimeA.count(method) + runtimeB.count(method);
    }

    private int bridgeReleaseLogCount() throws IOException {
        assertThat(stack.managedInstance("gateway-direct"))
                .isInstanceOf(ManagedSutInstance.class);
        Path log = ((ManagedSutInstance) stack.managedInstance("gateway-direct")).logFile();
        try (var lines = Files.lines(log)) {
            return (int) lines.filter(line -> line.contains("forward bridge release")).count();
        }
    }

    private static String readFirstDataLine(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data:")) {
                return line.substring("data:".length()).strip();
            }
        }
        throw new AssertionError("subscription ended before its first data frame");
    }

    private static String taskId(String body) throws Exception {
        for (String line : body.lines().toList()) {
            String candidate = line.startsWith("data:") ? line.substring("data:".length()).strip() : line.strip();
            if (candidate.isEmpty() || !candidate.startsWith("{")) {
                continue;
            }
            JsonNode root = AgentBusExternalFixture.JSON.readTree(candidate);
            JsonNode result = root.path("result");
            String id = result.path("id").asText(result.path("task").path("id").asText());
            if (!id.isBlank()) {
                return id;
            }
        }
        throw new AssertionError("response contains no task id: " + body);
    }

    private static final class RuntimeStub implements AutoCloseable {
        private final String name;
        private final MockWebServer server = new MockWebServer();
        private final CopyOnWriteArrayList<String> methods = new CopyOnWriteArrayList<>();
        private final Set<String> terminalTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Set<String> missingTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Set<String> slowSubscriptions = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private volatile int requests;
        private volatile boolean unavailable;

        private RuntimeStub(String name) {
            this.name = name;
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    return response(request);
                }
            });
        }

        void start() throws IOException {
            server.start();
        }

        String name() {
            return name;
        }

        String url() {
            return server.url("/").toString();
        }

        List<String> methods() {
            return List.copyOf(methods);
        }

        int count(String method) {
            return (int) methods.stream().filter(method::equals).count();
        }

        int requests() {
            return requests;
        }

        void terminal(String taskId) {
            terminalTasks.add(taskId);
        }

        void missing(String taskId) {
            missingTasks.add(taskId);
        }

        void slowSubscription(String taskId) {
            slowSubscriptions.add(taskId);
        }

        void unavailable(boolean value) {
            unavailable = value;
        }

        private MockResponse response(RecordedRequest request) {
            if ("/health".equals(request.getPath())) {
                return new MockResponse().setResponseCode(200).setBody("ok");
            }
            requests++;
            if (unavailable) {
                return new MockResponse().setResponseCode(503).setBody("runtime unavailable");
            }
            try {
                JsonNode root = AgentBusExternalFixture.JSON.readTree(request.getBody().readUtf8());
                String method = root.path("method").asText();
                methods.add(method);
                return switch (method) {
                    case "SendMessage" -> createResponse(root);
                    case "GetTask" -> getTaskResponse(root.path("params").path("id").asText());
                    case "SubscribeToTask" -> subscribeResponse(root.path("params").path("id").asText());
                    default -> json("{\"jsonrpc\":\"2.0\",\"id\":\"stub\",\"error\":{"
                            + "\"code\":-32601,\"message\":\"method not found\"}}");
                };
            } catch (Exception error) {
                return new MockResponse().setResponseCode(500).setBody(error.toString());
            }
        }

        private MockResponse createResponse(JsonNode request) {
            String agentId = request.at("/params/metadata/agentId").asText("unknown-agent");
            String taskId = name + "-task-" + agentId;
            String body = "{\"jsonrpc\":\"2.0\",\"id\":\"stub\",\"result\":{\"task\":{"
                    + "\"id\":\"" + taskId + "\",\"contextId\":\"context-" + name + "\","
                    + "\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}}";
            return json(body);
        }

        private MockResponse getTaskResponse(String taskId) {
            if (missingTasks.contains(taskId)) {
                return json("{\"jsonrpc\":\"2.0\",\"id\":\"stub\",\"error\":{"
                        + "\"code\":-32001,\"message\":\"task not found\"}}");
            }
            boolean terminal = terminalTasks.contains(taskId);
            String state = terminal ? "TASK_STATE_COMPLETED" : "TASK_STATE_WORKING";
            String statusMessage = terminal ? ",\"message\":{\"role\":\"ROLE_AGENT\","
                    + "\"messageId\":\"terminal-message-" + name + "\",\"parts\":[{"
                    + "\"text\":\"terminal-" + name + "\"}]}" : "";
            String body = "{\"jsonrpc\":\"2.0\",\"id\":\"stub\",\"result\":{"
                    + "\"id\":\"" + taskId + "\",\"contextId\":\"context-" + name + "\","
                    + "\"status\":{\"state\":\"" + state + "\"" + statusMessage + "},"
                    + "\"artifacts\":[{\"artifactId\":\"snapshot\",\"parts\":[{"
                    + "\"text\":\"snapshot-" + name + "\"}]}],"
                    + "\"history\":[{\"role\":\"ROLE_USER\","
                    + "\"messageId\":\"history-message-" + name + "\",\"parts\":[{"
                    + "\"text\":\"history-" + name + "\"}]}],"
                    + "\"metadata\":{\"snapshotOwner\":\"" + name + "\",\"outputOffset\":37}}}";
            return json(body);
        }

        private MockResponse subscribeResponse(String taskId) {
            if (terminalTasks.contains(taskId)) {
                return json("{\"jsonrpc\":\"2.0\",\"id\":\"stub\",\"error\":{"
                        + "\"code\":-32004,\"message\":\"task is already terminal\"}}");
            }
            String working = taskResult(taskId, "TASK_STATE_WORKING");
            String completed = taskResult(taskId, "TASK_STATE_COMPLETED");
            if (slowSubscriptions.contains(taskId)) {
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < 100; i++) {
                    body.append("event: jsonrpc\ndata: ")
                            .append(working).append("\n\n");
                }
                return new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setChunkedBody(body.toString(), 256)
                        .throttleBody(256, 100, TimeUnit.MILLISECONDS);
            }
            return new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: jsonrpc\ndata: " + working + "\n\n"
                            + "event: jsonrpc\ndata: " + completed + "\n\n");
        }

        private String taskResult(String taskId, String state) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"stub\",\"result\":{"
                    + "\"id\":\"" + taskId + "\",\"contextId\":\"context-" + name + "\","
                    + "\"status\":{\"state\":\"" + state + "\"}}}";
        }

        private static MockResponse json(String body) {
            return new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json").setBody(body);
        }

        @Override
        public void close() throws IOException {
            server.shutdown();
        }
    }
}
