package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.transport.a2a.GatewayTransportProvider;
import com.openjiuwen.client.transport.a2a.RuntimeTransportProvider;
import com.openjiuwen.client.transport.spi.TransportProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Controllable Runtime/Gateway HTTP/SSE peer for tests of the public Client SDK. */
final class ClientSdkBlackboxFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    private final MockWebServer gateway = new MockWebServer();

    ClientSdkBlackboxFixture() throws IOException {
        gateway.start();
    }

    AgentClient client() {
        return client(EndpointType.GATEWAY);
    }

    AgentClient client(EndpointType endpointType) {
        return AgentClients.builder()
                .endpointType(endpointType)
                .endpointUrl(gateway.url("/").toString())
                .credentialProvider(conversationId -> "acceptance-token")
                .build();
    }

    AgentClient client(EndpointType endpointType, Duration idleTimeout) {
        TransportProvider transport = endpointType == EndpointType.RUNTIME
                ? new RuntimeTransportProvider(gateway.url("/").toString(), JSON, idleTimeout)
                : new GatewayTransportProvider(gateway.url("/").toString(), JSON, idleTimeout);
        return AgentClients.builder()
                .transport(transport)
                .credentialProvider(conversationId -> "acceptance-token")
                .build();
    }

    String baseUrl() {
        return gateway.url("/").toString();
    }

    void enqueueSse(String... resultJson) {
        enqueueSse(0, resultJson);
    }

    void enqueueDelayedSse(long bodyDelayMillis, String... resultJson) {
        enqueueSse(bodyDelayMillis, resultJson);
    }

    private void enqueueSse(long bodyDelayMillis, String... resultJson) {
        StringBuilder body = new StringBuilder();
        for (String result : resultJson) {
            body.append(sseFrame(result));
        }
        gateway.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeadersDelay(100, TimeUnit.MILLISECONDS)
                .setBodyDelay(bodyDelayMillis, TimeUnit.MILLISECONDS)
                .setBody(body.toString()));
    }

    private static String sseFrame(String resultJson) {
        return "data: {\"jsonrpc\":\"2.0\",\"id\":\"acceptance\",\"result\":"
                + resultJson + "}\n\n";
    }

    void enqueueJson(String resultJson) {
        gateway.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"acceptance\",\"result\":"
                        + resultJson + "}"));
    }

    void enqueueHttpError(int status, String code) {
        gateway.enqueue(new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"" + code + "\",\"message\":\"rejected\"}"));
    }

    void enqueueJsonRpcError(int code, String message) {
        gateway.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"acceptance\",\"error\":{\"code\":"
                        + code + ",\"message\":" + json(message) + "}}"));
    }

    JsonNode takeRequest() throws Exception {
        return takeRequest(true);
    }

    JsonNode takeRequest(boolean expectAuthorization) throws Exception {
        return takeTimedRequest(expectAuthorization).body();
    }

    TimedRequest takeTimedRequest(boolean expectAuthorization) throws Exception {
        var request = gateway.takeRequest(5, TimeUnit.SECONDS);
        long receivedAtNanos = System.nanoTime();
        assertThat(request).as("Gateway request").isNotNull();
        assertThat(request.getPath()).isEqualTo("/a2a");
        if (expectAuthorization) {
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer acceptance-token");
        } else {
            assertThat(request.getHeader("Authorization")).isNull();
        }
        return new TimedRequest(JSON.readTree(request.getBody().readUtf8()), receivedAtNanos);
    }

    boolean hasRequestWithin(Duration timeout) throws InterruptedException {
        return gateway.takeRequest(timeout.toMillis(), TimeUnit.MILLISECONDS) != null;
    }

    static String status(String taskId, String contextId, String state, String text) {
        String message = text == null ? "" : ",\"message\":{\"parts\":[{\"text\":" + json(text) + "}]}";
        return "{\"statusUpdate\":{\"taskId\":" + json(taskId)
                + ",\"contextId\":" + json(contextId)
                + ",\"status\":{\"state\":" + json(state) + message + "}}}";
    }

    static String inputRequired(String taskId, String contextId, String toolCallId,
                                String toolName, String arguments) {
        return "{\"statusUpdate\":{\"taskId\":" + json(taskId)
                + ",\"contextId\":" + json(contextId)
                + ",\"status\":{\"state\":\"TASK_STATE_INPUT_REQUIRED\","
                + "\"message\":{\"parts\":[{\"text\":\"tool requested\"}],\"metadata\":{\"_interrupt\":{"
                + "\"toolCallId\":" + json(toolCallId) + ",\"toolName\":" + json(toolName)
                + ",\"context\":{\"_interrupt_kind\":\"client_tool\",\"arguments\":"
                + arguments + "}}}}}}}";
    }

    static String userInputRequired(String taskId, String contextId, String prompt) {
        return "{\"statusUpdate\":{\"taskId\":" + json(taskId)
                + ",\"contextId\":" + json(contextId)
                + ",\"status\":{\"state\":\"TASK_STATE_INPUT_REQUIRED\","
                + "\"message\":{\"parts\":[{\"text\":" + json(prompt) + "}],"
                + "\"metadata\":{\"_interrupt\":{\"message\":" + json(prompt)
                + ",\"context\":{\"_interrupt_kind\":\"user_input\"}}}}}}}";
    }

    static String task(String taskId, String contextId, String state, String text) {
        String message = text == null ? "" : ",\"message\":{\"parts\":[{\"text\":" + json(text) + "}]}";
        return "{\"task\":{\"id\":" + json(taskId) + ",\"contextId\":" + json(contextId)
                + ",\"status\":{\"state\":" + json(state) + message + "}}}";
    }

    static String taskSnapshot(String taskId, String contextId, String state, String text) {
        String artifacts = text == null ? "" : ",\"artifacts\":[{\"artifactId\":\"result\","
                + "\"parts\":[{\"text\":" + json(text) + "}]}]";
        return "{\"id\":" + json(taskId) + ",\"contextId\":" + json(contextId)
                + ",\"status\":{\"state\":" + json(state) + "}" + artifacts + "}";
    }

    private static String json(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static EventProbe subscribe(InvocationCall call) {
        EventProbe probe = new EventProbe();
        call.events().subscribe(probe);
        return probe;
    }

    record TimedRequest(JsonNode body, long receivedAtNanos) {
    }

    @Override
    public void close() throws IOException {
        gateway.shutdown();
    }

    static final class EventProbe implements Flow.Subscriber<InvocationEvent> {
        private final List<InvocationEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(InvocationEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        List<InvocationEvent> await() throws InterruptedException {
            assertThat(done.await(8, TimeUnit.SECONDS)).as("SDK event stream terminated").isTrue();
            assertThat(error).as("SDK event stream error").isNull();
            return List.copyOf(events);
        }

        List<InvocationEvent> awaitEvent(Predicate<InvocationEvent> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline && events.stream().noneMatch(predicate) && error == null) {
                Thread.sleep(25);
            }
            assertThat(error).as("SDK event stream error").isNull();
            assertThat(events).as("SDK event stream contains matching event").anyMatch(predicate);
            return List.copyOf(events);
        }

        Throwable awaitError() throws InterruptedException {
            assertThat(done.await(8, TimeUnit.SECONDS)).as("SDK event stream terminated").isTrue();
            assertThat(error).isNotNull();
            return error;
        }
    }
}
