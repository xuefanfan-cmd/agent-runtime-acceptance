package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incremental black-box coverage for the PR #366 / L2 PR #73 deltas.
 * The two servers in this test are dependency stubs: they do not implement Gateway rules.
 */
@Feature("FEAT-011: 客户端调用路由转发")
@Tag("feat-011")
@Tag("integration")
@Tag("blackbox")
class GatewayPr366DeltaBlackboxTest {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private MockWebServer rdc;
    private MockWebServer runtime;
    private SutStack stack;
    private GatewayStubState state;
    private String unreachableRuntimeUrl;

    @BeforeEach
    void start() throws Exception {
        state = new GatewayStubState();
        rdc = new MockWebServer();
        rdc.setDispatcher(state.rdcDispatcher());
        rdc.start();
        runtime = new MockWebServer();
        runtime.setDispatcher(state.runtimeDispatcher());
        runtime.start();
        try (ServerSocket socket = new ServerSocket(0)) {
            unreachableRuntimeUrl = "http://127.0.0.1:" + socket.getLocalPort() + "/";
        }

        TestConfig config = TestConfig.load();
        stack = SutStack.builder(config)
                .agent("gateway-direct", gateway -> gateway
                        .property("gateway.rdc.base-url", rdc.url("/").toString()))
                .start();
    }

    @AfterEach
    void stop() throws Exception {
        if (stack != null) {
            stack.close();
        }
        if (runtime != null) {
            runtime.shutdown();
        }
        if (rdc != null) {
            rdc.shutdown();
        }
    }

    @Test
    @Story("F011-C4-01: 业务空列表 → ROUTE_NO_CANDIDATES")
    @DisplayName("C4 阻塞创建：业务空列表与 RDC 不可用区分")
    void emptyBusinessCandidatesReturnRouteNoCandidates() throws Exception {
        state.searchStatus = 200;
        state.emptyCandidates = true;
        HttpResponse<String> response = post(create("c4-empty-sync", "SendMessage", "c4-empty"));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("ROUTE_NO_CANDIDATES")
                .doesNotContain("RDC_UNAVAILABLE", "endpointUrl", "routeHandle", "localhost");
        assertThat(state.searches.get()).isEqualTo(1);
        assertThat(state.resolves.get()).isZero();
        assertThat(state.runtimeCalls.get()).isZero();
        evidence("C4-empty-sync", response);
    }

    @Test
    @Story("F011-C4-02: 业务空列表流式 → ROUTE_NO_CANDIDATES")
    @DisplayName("C4 流式创建：业务空列表不进入 Runtime")
    void emptyBusinessCandidatesStreamingReturnRouteNoCandidates() throws Exception {
        state.searchStatus = 200;
        state.emptyCandidates = true;
        HttpResponse<String> response = post(create("c4-empty-stream", "SendStreamingMessage", "c4-empty-stream"));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("ROUTE_NO_CANDIDATES")
                .doesNotContain("endpointUrl", "routeHandle", "localhost");
        assertThat(state.searches.get()).isEqualTo(1);
        assertThat(state.resolves.get()).isZero();
        assertThat(state.runtimeCalls.get()).isZero();
        evidence("C4-empty-stream", response);
    }

    @Test
    @Story("F011-C4-03: RDC search 5xx → RDC_UNAVAILABLE")
    @DisplayName("C4 阻塞创建：RDC 502/503/504 空缓存应返回 RDC_UNAVAILABLE")
    void rdc503WithEmptyCacheReturnsRdcUnavailable() throws Exception {
        List<String> unexpected = new ArrayList<>();
        for (int status : new int[] {502, 503, 504}) {
            int searchesBefore = state.searches.get();
            state.searchStatus = status;
            HttpResponse<String> response = post(create("c4-rdc-" + status, "SendMessage", "c4-rdc-" + status));

            assertThat(response.statusCode()).isEqualTo(503);
            if (!response.body().contains("RDC_UNAVAILABLE")) {
                unexpected.add(status + "=>" + response.body());
            }
            assertThat(response.body()).doesNotContain("endpointUrl", "routeHandle", "localhost");
            assertThat(state.searches.get()).isEqualTo(searchesBefore + 1);
            assertThat(state.resolves.get()).isZero();
            assertThat(state.runtimeCalls.get()).isZero();
            evidence("C4-sync-http-" + status, response);
        }
        assertThat(unexpected).as("all 5xx search failures must be RDC_UNAVAILABLE").isEmpty();
    }

    @Test
    @Story("F011-C4-04: RDC search 5xx 流式 → RDC_UNAVAILABLE")
    @DisplayName("C4 流式创建：RDC 502/503/504 空缓存应返回 RDC_UNAVAILABLE")
    void rdc5xxStreamingWithEmptyCacheReturnsRdcUnavailable() throws Exception {
        List<String> unexpected = new ArrayList<>();
        for (int status : new int[] {502, 503, 504}) {
            int searchesBefore = state.searches.get();
            state.searchStatus = status;
            HttpResponse<String> response = post(create("c4-stream-rdc-" + status,
                    "SendStreamingMessage", "c4-stream-rdc-" + status));

            assertThat(response.statusCode()).isEqualTo(503);
            if (!response.body().contains("RDC_UNAVAILABLE")) {
                unexpected.add(status + "=>" + response.body());
            }
            assertThat(response.body()).doesNotContain("endpointUrl", "routeHandle", "localhost");
            assertThat(state.searches.get()).isEqualTo(searchesBefore + 1);
            assertThat(state.resolves.get()).isZero();
            assertThat(state.runtimeCalls.get()).isZero();
            evidence("C4-stream-http-" + status, response);
        }
        assertThat(unexpected).as("all streaming 5xx search failures must be RDC_UNAVAILABLE").isEmpty();
    }

    @Test
    @Story("F011-C4-05: RDC search 网络故障 → RDC_UNAVAILABLE")
    @DisplayName("C4 RDC 连接拒绝：应返回 RDC_UNAVAILABLE")
    void rdcConnectionFailureWithEmptyCacheReturnsRdcUnavailable() throws Exception {
        rdc.shutdown();
        HttpResponse<String> response = post(create("c4-rdc-network", "SendMessage", "c4-rdc-network"));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("RDC_UNAVAILABLE")
                .doesNotContain("endpointUrl", "routeHandle", "localhost");
        assertThat(state.resolves.get()).isZero();
        assertThat(state.runtimeCalls.get()).isZero();
        evidence("C4-network-sync", response);
    }

    @Test
    @Story("F011-C4-05: RDC search 网络故障流式 → RDC_UNAVAILABLE")
    @DisplayName("C4 流式创建：RDC 连接拒绝应返回 RDC_UNAVAILABLE")
    void rdcConnectionFailureStreamingWithEmptyCacheReturnsRdcUnavailable() throws Exception {
        rdc.shutdown();
        HttpResponse<String> response = post(create("c4-rdc-network-stream",
                "SendStreamingMessage", "c4-rdc-network-stream"));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("RDC_UNAVAILABLE")
                .doesNotContain("endpointUrl", "routeHandle", "localhost");
        assertThat(state.resolves.get()).isZero();
        assertThat(state.runtimeCalls.get()).isZero();
        evidence("C4-network-stream", response);
    }

    @Test
    @Story("F011-C4-06: RDC search 4xx 合同观测")
    @DisplayName("C4 RDC 4xx：受控失败、零 Runtime，稳定错误码待确认")
    void rdc4xxIsControlledWithoutRuntimeCallButClassificationRemainsPartial() throws Exception {
        for (int status : new int[] {400, 401, 403, 404}) {
            int searchesBefore = state.searches.get();
            state.searchStatus = status;
            HttpResponse<String> response = post(create("c4-rdc-" + status, "SendMessage", "c4-rdc-" + status));

            assertThat(response.statusCode()).isBetween(400, 599);
            assertThat(response.body()).doesNotContain("endpointUrl", "routeHandle", "localhost");
            assertThat(state.searches.get()).isEqualTo(searchesBefore + 1);
            assertThat(state.resolves.get()).isZero();
            assertThat(state.runtimeCalls.get()).isZero();
            evidence("C4-partial-http-" + status, response);
        }
    }

    @Test
    @Story("F011-C3-01: DIRECT 阻塞传输失败 → UNKNOWN")
    @DisplayName("C3 阻塞创建：未获 taskId 的 Runtime 传输失败返回 DIRECT_TRANSPORT_UNKNOWN")
    void directBlockingTransportFailureReturnsUnknownAndRetryIsExplicit() throws Exception {
        state.runtimeUnavailable.set(true);
        String body = create("c3-sync-same-message", "SendMessage", "c3-sync");
        HttpResponse<String> first = post(body);

        assertUnknown(first);
        assertThat(state.resolves.get()).isEqualTo(1);
        assertThat(state.runtimeCalls.get()).isZero();

        state.runtimeUnavailable.set(false);
        HttpResponse<String> second = post(body);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("task-c3-sync");
        assertThat(state.resolves.get()).isEqualTo(2);
        assertThat(state.runtimeCalls.get()).isEqualTo(1);
        evidence("C3-sync-retry", second);
    }

    @Test
    @Story("F011-C3-03: DIRECT 流式传输失败 → UNKNOWN")
    @DisplayName("C3 流式创建：未获 taskId 的 Runtime 传输失败返回 DIRECT_TRANSPORT_UNKNOWN")
    void directStreamingTransportFailureReturnsUnknownAndRetryIsExplicit() throws Exception {
        state.runtimeUnavailable.set(true);
        String body = create("c3-stream-same-message", "SendStreamingMessage", "c3-stream");
        HttpResponse<String> first = post(body);

        assertUnknown(first);
        assertThat(state.resolves.get()).isEqualTo(1);
        assertThat(state.runtimeCalls.get()).isZero();

        state.runtimeUnavailable.set(false);
        HttpResponse<String> second = post(body);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.headers().firstValue("content-type").orElse(""))
                .containsIgnoringCase("text/event-stream");
        assertThat(second.body()).contains("task-c3-stream");
        assertThat(state.resolves.get()).isEqualTo(2);
        assertThat(state.runtimeCalls.get()).isEqualTo(1);
        evidence("C3-stream-retry", second);
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl("gateway-direct") + "/a2a"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer acceptance-token")
                .header("Content-Type", "application/json")
                .header("Accept", body.contains("SendStreamingMessage") ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void evidence(String scenario, HttpResponse<String> response) {
        System.out.printf("EVIDENCE scenario=%s status=%d body=%s search=%d resolve=%d runtime=%d%n",
                scenario, response.statusCode(), response.body(), state.searches.get(),
                state.resolves.get(), state.runtimeCalls.get());
    }

    private static void assertUnknown(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode error = AgentBusExternalFixture.JSON.readTree(response.body()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32053);
        assertThat(error.at("/data/code").asText()).isEqualTo("DIRECT_TRANSPORT_UNKNOWN");
        assertThat(error.at("/data/retryable").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("task-c3", "endpointUrl", "routeHandle");
    }

    private String create(String messageId, String method, String text) throws Exception {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + messageId + "\",\"method\":\""
                + method + "\",\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\""
                + messageId + "\",\"contextId\":\"ctx-" + messageId + "\",\"parts\":[{\"text\":\""
                + text + "\"}]},\"metadata\":{\"agentId\":\"agent-" + messageId + "\"}}}";
    }

    private final class GatewayStubState {
        private final AtomicInteger searches = new AtomicInteger();
        private final AtomicInteger resolves = new AtomicInteger();
        private final AtomicInteger runtimeCalls = new AtomicInteger();
        private final AtomicBoolean runtimeUnavailable = new AtomicBoolean();
        private volatile int searchStatus = 200;
        private volatile boolean emptyCandidates;

        Dispatcher rdcDispatcher() {
            return new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().startsWith("/api/registry/instances/")) {
                        searches.incrementAndGet();
                        if (searchStatus != 200) {
                            return new MockResponse().setResponseCode(searchStatus).setBody("rdc failure");
                        }
                        if (emptyCandidates) {
                            return json("[]");
                        }
                        return json("[{\"routeHandle\":\"handle-1\",\"serviceId\":\"service-1\",\"weight\":100}]");
                    }
                    if ("/api/registry/route-handle/resolve".equals(request.getPath())) {
                        resolves.incrementAndGet();
                        String endpoint = runtimeUnavailable.get() ? unreachableRuntimeUrl : runtime.url("/").toString();
                        return json("{\"endpointUrl\":\"" + endpoint + "\"}");
                    }
                    return new MockResponse().setResponseCode(404);
                }
            };
        }

        Dispatcher runtimeDispatcher() {
            return new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (!"/a2a".equals(request.getPath())) {
                        return new MockResponse().setResponseCode(404);
                    }
                    runtimeCalls.incrementAndGet();
                    String body = request.getBody().readUtf8();
                    String task = body.contains("c3-stream") ? "task-c3-stream" : "task-c3-sync";
                    if (body.contains("SendStreamingMessage")) {
                        return new MockResponse().setResponseCode(200)
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody("data: {\"jsonrpc\":\"2.0\",\"id\":\"stub\",\"result\":{\"id\":\""
                                        + task + "\"}}\n\n");
                    }
                    return json("{\"jsonrpc\":\"2.0\",\"id\":\"stub\",\"result\":{\"id\":\""
                            + task + "\"}}");
                }
            };
        }

        private MockResponse json(String body) {
            return new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body);
        }
    }
}
