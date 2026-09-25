package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared black-box driver; both Agent families expose identical FEAT-037 cases. */
public final class MultiInstanceHostingBlackboxSupport {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MODEL_REQUEST_TIMEOUT = Duration.ofSeconds(180);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID_VALUE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final TestConfig config;
    private final SutStack stack;
    private final String hostedName;
    private final String legacyName;
    private final String family;
    private final String identityA;
    private final String identityB;
    private final OpenAiEchoFixture model;
    private final String legacyPropertyPrefix;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public MultiInstanceHostingBlackboxSupport(TestConfig config, SutStack stack, String hostedName,
            String legacyName, String family, String identityA, String identityB,
            OpenAiEchoFixture model, String legacyPropertyPrefix) {
        this.config = config;
        this.stack = stack;
        this.hostedName = hostedName;
        this.legacyName = legacyName;
        this.family = family;
        this.identityA = identityA;
        this.identityB = identityB;
        this.model = model;
        this.legacyPropertyPrefix = legacyPropertyPrefix;
    }

    public static void configureHosted(SutStack.AgentBuilder agent, OpenAiEchoFixture model) {
        agent.property("openjiuwen.demo.hosted.api-key", "feat037-fixture-key")
                .property("openjiuwen.demo.hosted.api-base", model.apiBase())
                .property("openjiuwen.demo.hosted.model-name", "feat037-echo")
                .property("openjiuwen.demo.hosted.provider", "OpenAI")
                .property("openjiuwen.demo.hosted.ssl-verify", "false");
    }

    public void t01HostedCatalogPublishesBothInstances() throws Exception {
        JsonNode listing = catalog(stack);
        assertThat(listing.path("agents").isArray()).isTrue();
        assertThat(agentIds(listing)).containsExactly("agent-a", "agent-b");
        assertThat(listing.path("defaultAgent").asText()).isEqualTo("agent-a");
        assertCard("agent-a", identityA);
        assertCard("agent-b", identityB);
        ManagedSutInstance process = managed(stack);
        assertThat(process.isAlive()).isTrue();
        assertThat(process.pid()).isPositive();
    }

    public void t02InvalidInstanceNameFailsStartup() {
        for (String invalid : List.of(" ", "-starts-with-hyphen", "bad/id", "bad.id")) {
            assertStartupFails(Map.of("openjiuwen.demo.hosted.agent-a-id", invalid));
        }
    }

    public void t03DuplicateInstanceNameFailsFast() {
        assertStartupFails(Map.of("openjiuwen.demo.hosted.agent-b-id", "agent-a"));
    }

    public void t04PartialAssemblyRollsBack() {
        assertStartupFails(Map.of("openjiuwen.service.a2a.agents.agent-b.agent-name", ""));
    }

    public void t05CatalogOrderSelectsDefault() throws Exception {
        JsonNode defaultListing = catalog(stack);
        assertThat(agentIds(defaultListing)).containsExactly("agent-a", "agent-b");
        assertThat(defaultListing.path("defaultAgent").asText()).isEqualTo("agent-a");
        String firstCanary = canary("T05-first");
        assertRoute(send(stack, null, "SendMessage", firstCanary, context("t05-a"), null, Map.of()),
                "agent-a", firstCanary);

        try (SutStack explicit = startHosted(Map.of("openjiuwen.demo.hosted.default-agent", "agent-b"))) {
            JsonNode listing = catalog(explicit);
            assertThat(agentIds(listing)).containsExactly("agent-a", "agent-b");
            assertThat(listing.path("defaultAgent").asText()).isEqualTo("agent-b");
            String canary = canary("T05-explicit");
            assertRoute(send(explicit, null, "SendMessage", canary, context("t05-b"), null, Map.of()),
                    "agent-b", canary);
        }
    }

    public void t06InstanceCardRoutesToTarget() throws Exception {
        for (String id : List.of("agent-a", "agent-b")) {
            HttpResponse<String> response = get(stack, "/a2a/agents/" + id + "/.well-known/agent-card.json");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode card = JSON.readTree(response.body());
            String url = card.path("supportedInterfaces").path(0).path("url").asText();
            assertThat(url).endsWith("/a2a/agents/" + id);
            String canary = canary("T06-" + id);
            assertRoute(post(url, rpc("SendMessage", canary, context("t06-" + id), null, Map.of()), false),
                    id, canary);
        }
    }

    public void t07UnknownInstanceCardReturnsNotFound() throws Exception {
        HttpResponse<String> missing = get(stack,
                "/a2a/agents/missing-agent/.well-known/agent-card.json");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).doesNotContain(identityA, identityB);
        assertThat(get(stack, "/a2a/agents/agent-a/.well-known/agent-card.json").statusCode()).isEqualTo(200);
    }

    public void t08PathSyncRoutesToTarget() throws Exception {
        for (String id : List.of("agent-a", "agent-b")) {
            String canary = canary("T08-" + id);
            assertRoute(send(stack, id, "SendMessage", canary, context("t08-" + id), null, Map.of()),
                    id, canary);
        }
    }

    public void t09PathStreamingRoutesToTarget() throws Exception {
        for (String id : List.of("agent-a", "agent-b")) {
            String canary = canary("T09-" + id);
            HttpResponse<String> response = send(stack, id, "SendStreamingMessage", canary,
                    context("t09-" + id), null, Map.of());
            assertThat(contentType(response)).containsIgnoringCase("text/event-stream");
            assertThat(ssePayloads(response.body())).isNotEmpty();
            assertRoute(response, id, canary);
        }
    }

    public void t10GetTaskIsInstanceScoped() throws Exception {
        String canary = canary("T10");
        HttpResponse<String> created = send(stack, "agent-a", "SendMessage", canary,
                context("t10"), null, Map.of());
        String taskId = requireTaskId(created.body());
        HttpResponse<String> owner = operation(stack, "agent-a", "GetTask", taskId);
        HttpResponse<String> peer = operation(stack, "agent-b", "GetTask", taskId);
        assertThat(owner.body()).contains(taskId).doesNotContain("\"error\"");
        assertRpcError(peer);
        assertThat(peer.body()).doesNotContain(canary, identityA);
    }

    public void t11SubscribeIsInstanceScoped() throws Exception {
        String canary = canary("T11");
        String taskId = requireTaskId(send(stack, "agent-a", "SendMessage", canary,
                context("t11"), null, Map.of()).body());
        HttpResponse<String> owner = operation(stack, "agent-a", "SubscribeToTask", taskId);
        HttpResponse<String> peer = operation(stack, "agent-b", "SubscribeToTask", taskId);
        assertThat(owner.body()).contains(taskId);
        assertRpcError(peer);
        assertThat(peer.body()).doesNotContain(canary, identityA);
    }

    public void t13RootA2ADefaultsToFirstInstance() throws Exception {
        String sync = canary("T13-sync");
        assertRoute(send(stack, null, "SendMessage", sync, context("t13-sync"), null, Map.of()),
                "agent-a", sync);
        String stream = canary("T13-stream");
        assertRoute(send(stack, null, "SendStreamingMessage", stream, context("t13-stream"), null, Map.of()),
                "agent-a", stream);
    }

    public void t14RestQueryUsesAgentSelection() throws Exception {
        for (String path : List.of("/v1/query", "/query", "/v1/query/reactive")) {
            String defaultCanary = canary("T14-default");
            assertRoute(rest(stack, path, defaultCanary, null, path.endsWith("reactive")),
                    "agent-a", defaultCanary);
            String selectedCanary = canary("T14-selected");
            assertRoute(rest(stack, path, selectedCanary, "agent-b", path.endsWith("reactive")),
                    "agent-b", selectedCanary);
        }
        HttpResponse<String> invalid = rest(stack, "/v1/query", canary("T14-invalid"), "bad/id", false);
        assertThat(invalid.statusCode()).isEqualTo(400);
        HttpResponse<String> missing = rest(stack, "/v1/query", canary("T14-missing"), "missing-agent", false);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).doesNotContain(identityA, identityB);
    }

    public void t15UnknownInstanceReturnsProtocolErrorWithoutCatalog() throws Exception {
        HttpResponse<String> response = send(stack, "missing-agent", "SendMessage", canary("T15"),
                context("t15"), null, Map.of());
        assertRpcError(response);
        JsonNode payload = firstPayload(response.body());
        JsonNode error = payload.path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32602);
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(payload.has("result")).isFalse();
        assertThat(error.has("data")).isFalse();
        assertThat(error.has("details")).isFalse();
        assertThat(response.body()).doesNotContain("agent-a", "agent-b");
        assertThat(response.body()).doesNotContain(identityA, identityB);
    }

    public void t16MetadataCannotForgeRoute() throws Exception {
        Map<String, Object> forged = Map.of("agent_id", "agent-b", "agentId", "agent-b",
                "hostedAgent", "agent-b");
        String pathCanary = canary("T16-path");
        assertRoute(send(stack, "agent-a", "SendMessage", pathCanary, context("t16-path"), null, forged),
                "agent-a", pathCanary);
        String rootCanary = canary("T16-root");
        assertRoute(send(stack, null, "SendMessage", rootCanary, context("t16-root"), null, forged),
                "agent-a", rootCanary);
    }

    public void t17ResponsePreservesProtocolWithoutHostedMetadata() throws Exception {
        for (String id : List.of("agent-a", "agent-b")) {
            String marker = canary("T17-" + id);
            HttpResponse<String> response = send(stack, id, "SendMessage", marker,
                    context("t17-" + id), null, Map.of());
            assertRoute(response, id, marker);
            JsonNode root = JSON.readTree(response.body());
            assertThat(root.path("result").isObject()).as(response.body()).isTrue();
            assertThat(root.has("error")).as(response.body()).isFalse();
            assertThat(metadataContainsIdentity(root, "agent-a"))
                    .as("Runtime must not inject hosted identity into response metadata: %s", response.body())
                    .isFalse();
            assertThat(metadataContainsIdentity(root, "agent-b"))
                    .as("Runtime must not inject hosted identity into response metadata: %s", response.body())
                    .isFalse();
        }
    }

    public void t18MemorySessionsSameIdAreIsolated() throws Exception {
        String shared = context("t18-shared");
        String markerA = canary("T18-memory-a");
        String markerB = canary("T18-memory-b");
        assertRoute(send(stack, "agent-a", "SendMessage", markerA, shared, null, Map.of()), "agent-a", markerA);
        assertRoute(send(stack, "agent-b", "SendMessage", markerB, shared, null, Map.of()), "agent-b", markerB);
        HttpResponse<String> recallA = send(stack, "agent-a", "SendMessage", canary("T18-recall-a"),
                shared, null, Map.of());
        HttpResponse<String> recallB = send(stack, "agent-b", "SendMessage", canary("T18-recall-b"),
                shared, null, Map.of());
        assertThat(recallA.body()).contains(markerA).doesNotContain(markerB);
        assertThat(recallB.body()).contains(markerB).doesNotContain(markerA);
    }

    public void t19MemoryStreamingSameIdIsolated() throws Exception {
        String shared = context("t19-shared");
        String markerA = canary("T19-memory-a");
        String markerB = canary("T19-memory-b");
        assertRoute(send(stack, "agent-a", "SendStreamingMessage", markerA, shared, null, Map.of()),
                "agent-a", markerA);
        assertRoute(send(stack, "agent-b", "SendStreamingMessage", markerB, shared, null, Map.of()),
                "agent-b", markerB);
        HttpResponse<String> recallA = send(stack, "agent-a", "SendStreamingMessage", canary("T19-recall-a"),
                shared, null, Map.of());
        HttpResponse<String> recallB = send(stack, "agent-b", "SendStreamingMessage", canary("T19-recall-b"),
                shared, null, Map.of());
        assertThat(recallA.body()).contains(markerA).doesNotContain(markerB);
        assertThat(recallB.body()).contains(markerB).doesNotContain(markerA);
    }

    public void t20MemoryAsyncTasksSameIdIsolated() throws Exception {
        String shared = context("t20-shared");
        String taskA = requireTaskId(send(stack, "agent-a", "SendMessage", canary("T20-a"),
                shared, null, Map.of()).body());
        String taskB = requireTaskId(send(stack, "agent-b", "SendMessage", canary("T20-b"),
                shared, null, Map.of()).body());
        assertThat(taskA).isNotEqualTo(taskB);
        assertThat(operation(stack, "agent-a", "GetTask", taskA).body()).contains(taskA);
        assertThat(operation(stack, "agent-b", "GetTask", taskB).body()).contains(taskB);
        assertRpcError(operation(stack, "agent-b", "GetTask", taskA));
        assertRpcError(operation(stack, "agent-a", "GetTask", taskB));
    }

    public void t21RedisSyncSessionsSameIdIsolated() throws Exception {
        redisHistoryIsolation(false, "T21");
    }

    public void t22RedisStreamingSessionsSameIdIsolated() throws Exception {
        redisHistoryIsolation(true, "T22");
    }

    public void t23RedisAsyncTasksSameIdIsolated() throws Exception {
        SutStack redis = startRedisHosted();
        try {
            String shared = context("t23-shared");
            String taskA = requireTaskId(send(redis, "agent-a", "SendMessage", canary("T23-a"),
                    shared, null, Map.of()).body());
            String taskB = requireTaskId(send(redis, "agent-b", "SendMessage", canary("T23-b"),
                    shared, null, Map.of()).body());
            redis.stop(hostedName);
            redis.start(hostedName);
            assertThat(operation(redis, "agent-a", "GetTask", taskA).body()).contains(taskA);
            assertThat(operation(redis, "agent-b", "GetTask", taskB).body()).contains(taskB);
            assertRpcError(operation(redis, "agent-b", "GetTask", taskA));
            assertRpcError(operation(redis, "agent-a", "GetTask", taskB));
        } finally {
            redis.close();
        }
    }

    public void t25EachInstanceLifecycleRunsOnce() throws Exception {
        Path log;
        Map<String, Integer> before = new LinkedHashMap<>();
        String[] lifecycleMarkers = {"operation=start result=begin", "operation=start result=success",
                "operation=stop result=begin", "operation=stop result=success"};
        Path existingLog = managed(stack).logFile();
        String existing = Files.exists(existingLog) ? Files.readString(existingLog) : "";
        for (String id : List.of("agent-a", "agent-b")) {
            for (String marker : lifecycleMarkers) {
                before.put(id + marker, count(existing, "Hosted agent agentId=" + id + " " + marker));
            }
        }
        try (SutStack temporary = startHosted(Map.of())) {
            log = managed(temporary).logFile();
        }
        String content = Files.readString(log);
        for (String id : List.of("agent-a", "agent-b")) {
            for (String marker : lifecycleMarkers) {
                String key = id + marker;
                assertThat(count(content, "Hosted agent agentId=" + id + " " + marker)
                        - before.getOrDefault(key, 0)).isEqualTo(1);
            }
        }
    }

    public void t26SharedSessionResetKeepsTargetRouting() throws Exception {
        String shared = context("t26-shared");
        String markerB = canary("T26-peer-b");
        String markerA = canary("T26-owner-a");
        assertRoute(send(stack, "agent-b", "SendMessage", markerB, shared, null, Map.of()),
                "agent-b", markerB);
        assertRoute(send(stack, "agent-a", "SendMessage", markerA, shared, null, Map.of()),
                "agent-a", markerA);
        Map<String, Object> reset = new LinkedHashMap<>();
        reset.put("agent_id", "agent-a");
        reset.put("conversation_id", shared);
        HttpResponse<String> cleared = post(base(stack) + "/v1/reset_conversation",
                JSON.writeValueAsString(reset), false);
        assertThat(cleared.statusCode()).isBetween(200, 299);
        String recall = canary("T26-recall-b");
        HttpResponse<String> peer = send(stack, "agent-b", "SendMessage", recall,
                shared, null, Map.of());
        assertRoute(peer, "agent-b", recall);
        System.out.printf("FEAT-037 T26 Core boundary: family=%s peerHistoryRetained=%s%n",
                family, peer.body().contains(markerB));
    }

    public void t27ProcessQuotaIsShared() throws Exception {
        try (SutStack quota = startHosted(Map.of(
                "openjiuwen.service.concurrency.max-concurrent-tasks", "1"))) {
            String holdMarker = canary("T27-hold");
            CompletableFuture<HttpResponse<String>> admitted;
            HttpResponse<String> rejected;
            try (OpenAiEchoFixture.HoldHandle hold = model.hold(holdMarker, 1)) {
                admitted = http.sendAsync(request(quota, "agent-a", "SendStreamingMessage",
                        holdMarker, context("t27-a")), HttpResponse.BodyHandlers.ofString());
                hold.awaitArrivals();
                rejected = http.send(request(quota, "agent-b", "SendStreamingMessage",
                        canary("T27-b"), context("t27-b")), HttpResponse.BodyHandlers.ofString());
                assertThat(rejected.statusCode())
                        .as("agent-b must be rejected while agent-a owns the single process permit")
                        .isEqualTo(503);
            }
            assertThat(admitted.join().statusCode()).isEqualTo(200);
            assertThat(managed(quota).pid()).isPositive();
        }
    }

    public void t31ActiveTaskQueryAggregatesProcessTasks() throws Exception {
        try (SutStack observed = startHosted(Map.of(
                "openjiuwen.service.concurrency.max-concurrent-tasks", "4"))) {
            String holdMarker = canary("T31-hold");
            String contextA = context("t31-a");
            String contextB = context("t31-b");
            CompletableFuture<HttpResponse<String>> taskA;
            CompletableFuture<HttpResponse<String>> taskB;
            try (OpenAiEchoFixture.HoldHandle hold = model.hold(holdMarker, 2)) {
                taskA = sendStreamingAsync(observed, "agent-a", holdMarker + " " + canary("T31-a"), contextA);
                taskB = sendStreamingAsync(observed, "agent-b", holdMarker + " " + canary("T31-b"), contextB);
                hold.awaitArrivals();

                JsonNode snapshot = activeTaskSnapshot(observed, null);
                assertThat(snapshot.path("maxConcurrentTasks").asInt()).isEqualTo(4);
                assertThat(snapshot.path("currentActiveTasks").asInt()).isEqualTo(2);
                assertTaskSnapshot(snapshot, List.of(contextA, contextB));
            }
            assertThat(taskA.join().statusCode()).isEqualTo(200);
            assertThat(taskB.join().statusCode()).isEqualTo(200);
        }
    }

    public void t32ActiveTaskQueryFiltersKnownInstance() throws Exception {
        try (SutStack observed = startHosted(Map.of(
                "openjiuwen.service.concurrency.max-concurrent-tasks", "4"))) {
            String holdMarker = canary("T32-hold");
            String contextA = context("t32-a");
            String contextB = context("t32-b");
            CompletableFuture<HttpResponse<String>> taskA;
            CompletableFuture<HttpResponse<String>> taskB;
            try (OpenAiEchoFixture.HoldHandle hold = model.hold(holdMarker, 2)) {
                taskA = sendStreamingAsync(observed, "agent-a", holdMarker + " " + canary("T32-a"), contextA);
                taskB = sendStreamingAsync(observed, "agent-b", holdMarker + " " + canary("T32-b"), contextB);
                hold.awaitArrivals();

                JsonNode snapshotA = activeTaskSnapshot(observed, "agent-a");
                assertThat(snapshotA.path("maxConcurrentTasks").asInt()).isEqualTo(4);
                assertThat(snapshotA.path("currentActiveTasks").asInt()).isEqualTo(1);
                assertTaskSnapshot(snapshotA, List.of(contextA));

                JsonNode snapshotB = activeTaskSnapshot(observed, "agent-b");
                assertThat(snapshotB.path("maxConcurrentTasks").asInt()).isEqualTo(4);
                assertThat(snapshotB.path("currentActiveTasks").asInt()).isEqualTo(1);
                assertTaskSnapshot(snapshotB, List.of(contextB));
            }
            assertThat(taskA.join().statusCode()).isEqualTo(200);
            assertThat(taskB.join().statusCode()).isEqualTo(200);
        }
    }

    public void t33ActiveTaskQueryReturnsEmptyForKnownIdleInstance() throws Exception {
        try (SutStack observed = startHosted(Map.of(
                "openjiuwen.service.concurrency.max-concurrent-tasks", "4"))) {
            String holdMarker = canary("T33-hold");
            CompletableFuture<HttpResponse<String>> active;
            try (OpenAiEchoFixture.HoldHandle hold = model.hold(holdMarker, 1)) {
                active = sendStreamingAsync(observed, "agent-a", holdMarker, context("t33-a"));
                hold.awaitArrivals();

                JsonNode idle = activeTaskSnapshot(observed, "agent-b");
                assertThat(idle.path("maxConcurrentTasks").asInt()).isEqualTo(4);
                assertThat(idle.path("currentActiveTasks").asInt()).isZero();
                assertThat(idle.path("tasks").isArray()).isTrue();
                assertThat(idle.path("tasks").size()).isZero();
            }
            assertThat(active.join().statusCode()).isEqualTo(200);
        }
    }

    public void t34ActiveTaskQueryRejectsBlankInstance() throws Exception {
        for (String query : List.of("?agentId=", "?agentId=%20%20")) {
            HttpResponse<String> response = get(stack, "/v1/current_active_tasks" + query);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        }
    }

    public void t35ActiveTaskQueryRejectsUnknownInstanceWithoutFallback() throws Exception {
        HttpResponse<String> response = get(stack, "/v1/current_active_tasks?agentId=missing-agent");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(response.body()).doesNotContain(identityA, identityB);
    }

    public void t28SingleHandlerApplicationRemainsCompatible() throws Exception {
        try (SutStack legacy = SutStack.builder(config)
                .agent(legacyName, agent -> configureLegacy(agent))
                .start()) {
            AgentCard card = legacy.client(legacyName).getAgentCard();
            assertThat(card.name()).isNotBlank();
            assertThat(card.capabilities()).isNotNull();
            assertThat(card.capabilities().streaming()).isTrue();
            assertThat(http.send(HttpRequest.newBuilder(URI.create(legacy.baseUrl(legacyName) + "/a2a/agents"))
                    .timeout(REQUEST_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(404);
            String syncCanary = canary("T28-sync");
            String streamCanary = canary("T28-stream");
            HttpResponse<String> sync = sendLegacy(legacy, "SendMessage", syncCanary, context("t28-sync"));
            HttpResponse<String> stream = sendLegacy(legacy, "SendStreamingMessage", streamCanary,
                    context("t28-stream"));
            assertThat(sync.statusCode()).isEqualTo(200);
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(sync.body()).contains(syncCanary);
            assertThat(stream.body()).contains(streamCanary);
            assertThat(requireTaskId(sync.body())).isNotEqualTo(requireTaskId(stream.body()));
        }
    }

    public void t29ShadowTasksKeepInstanceOwnership() {
        Assumptions.assumeTrue(false,
                "dependency-gated: the hosted demo has no documented remote A2A fixture/profile for shadow tasks");
    }

    public void t30WrongInstanceUsesTaskNotFoundWithoutOwnerLookup() throws Exception {
        String context = context("t30-owner");
        String taskId = requireTaskId(send(stack, "agent-a", "SendMessage", canary("T30-owner"),
                context, null, Map.of()).body());
        HttpResponse<String> wrongOwner = operation(stack, "agent-b", "GetTask", taskId);
        String missingId = UUID.randomUUID().toString();
        HttpResponse<String> genuinelyMissing = operation(stack, "agent-b", "GetTask", missingId);
        assertRpcError(wrongOwner);
        assertRpcError(genuinelyMissing);
        JsonNode wrong = firstPayload(wrongOwner.body());
        JsonNode missing = firstPayload(genuinelyMissing.body());
        String wrongSurface = normalizedError(wrong, taskId);
        String missingSurface = normalizedError(missing, missingId);
        assertThat(wrongSurface)
                .as("wrong-instance lookup must use the existing target-local TaskNotFound surface")
                .isEqualTo(missingSurface);

        HttpResponse<String> continuation = send(stack, "agent-b", "SendMessage", canary("T30-resume"),
                context, taskId, Map.of());
        assertRpcError(continuation);
        assertThat(firstPayload(continuation.body()).path("error").path("code").asInt())
                .isEqualTo(wrong.path("error").path("code").asInt());
        assertThat(operation(stack, "agent-a", "GetTask", taskId).body()).contains(taskId);
    }

    private void redisHistoryIsolation(boolean streaming, String caseId) throws Exception {
        SutStack redis = startRedisHosted();
        try {
            String shared = context(caseId.toLowerCase() + "-shared");
            String markerA = canary(caseId + "-redis-a");
            String markerB = canary(caseId + "-redis-b");
            String method = streaming ? "SendStreamingMessage" : "SendMessage";
            assertRoute(send(redis, "agent-a", method, markerA, shared, null, Map.of()), "agent-a", markerA);
            assertRoute(send(redis, "agent-b", method, markerB, shared, null, Map.of()), "agent-b", markerB);
            redis.stop(hostedName);
            redis.start(hostedName);
            HttpResponse<String> recallA = send(redis, "agent-a", method, canary(caseId + "-recall-a"),
                    shared, null, Map.of());
            HttpResponse<String> recallB = send(redis, "agent-b", method, canary(caseId + "-recall-b"),
                    shared, null, Map.of());
            assertThat(recallA.body()).contains(markerA, identityA).doesNotContain(markerB, identityB);
            assertThat(recallB.body()).contains(markerB, identityB).doesNotContain(markerA, identityA);
        } finally {
            redis.close();
        }
    }

    private SutStack startRedisHosted() {
        try {
            return SutStack.builder(config).agent(hostedName, agent -> {
                configureHosted(agent, model);
                agent.property("openjiuwen.service.middleware.checkpointer.type", "redis")
                        .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                        .property("openjiuwen.service.middleware.redis.default.type", "standalone")
                        .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.host", "{{host}}")
                        .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.port", "{{port}}");
            }).start();
        } catch (RuntimeException unavailable) {
            Assumptions.assumeTrue(false, "env-gated Redis hosted stack unavailable: " + unavailable.getMessage());
            throw unavailable;
        }
    }

    private void assertStartupFails(Map<String, String> properties) {
        assertThatThrownBy(() -> startHosted(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("process exited before becoming ready");
    }

    private SutStack startHosted(Map<String, String> properties) {
        return SutStack.builder(config).agent(hostedName, agent -> {
            configureHosted(agent, model);
            properties.forEach(agent::property);
        }).start();
    }

    private void configureLegacy(SutStack.AgentBuilder agent) {
        agent.property(legacyPropertyPrefix + ".api-key", "feat037-fixture-key")
                .property(legacyPropertyPrefix + ".api-base", model.apiBase())
                .property(legacyPropertyPrefix + ".model-name", "feat037-echo")
                .property(legacyPropertyPrefix + ".ssl-verify", "false");
        if ("DEEP".equals(family)) {
            // The legacy DeepResearch example declares two remote agents whose URLs
            // are mandatory at binding time. T28 only verifies its local A2A contract.
            agent.env("SEARCH_AGENT_URL", "http://127.0.0.1:1")
                    .env("VERIFY_AGENT_URL", "http://127.0.0.1:1");
        }
    }

    private void assertCard(String id, String expectedIdentity) throws Exception {
        HttpResponse<String> response = get(stack, "/a2a/agents/" + id + "/.well-known/agent-card.json");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode card = JSON.readTree(response.body());
        assertThat(card.path("name").asText()).containsIgnoringCase(id.substring(id.length() - 1));
        assertThat(card.path("skills").toString()).contains(id);
        assertThat(card.path("supportedInterfaces").path(0).path("url").asText())
                .endsWith("/a2a/agents/" + id);
        assertThat(expectedIdentity).isNotBlank();
    }

    private HttpResponse<String> rest(SutStack target, String path, String text, String agentId,
            boolean streaming) throws Exception {
        return rest(target, path, text, agentId, context("rest"), streaming);
    }

    private HttpResponse<String> rest(SutStack target, String path, String text, String agentId,
            String conversationId, boolean streaming) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", conversationId);
        body.put("message", text);
        body.put("stream", streaming);
        if (agentId != null) {
            body.put("agent_id", agentId);
        }
        return post(base(target) + path, JSON.writeValueAsString(body), streaming);
    }

    private HttpResponse<String> send(SutStack target, String id, String method, String text,
            String context, String taskId, Map<String, Object> metadata) throws Exception {
        String path = id == null ? "/a2a" : "/a2a/agents/" + id;
        return post(base(target) + path, rpc(method, text, context, taskId, metadata),
                "SendStreamingMessage".equals(method) || "SubscribeToTask".equals(method));
    }

    private HttpResponse<String> sendLegacy(SutStack target, String method, String text, String context)
            throws Exception {
        return post(target.baseUrl(legacyName) + "/a2a", rpc(method, text, context, null, Map.of()),
                "SendStreamingMessage".equals(method));
    }

    private HttpResponse<String> operation(SutStack target, String id, String method, String taskId)
            throws Exception {
        String path = "/a2a/agents/" + id;
        return post(base(target) + path, rpc(method, null, null, taskId, Map.of()),
                "SubscribeToTask".equals(method));
    }

    private CompletableFuture<HttpResponse<String>> sendStreamingAsync(SutStack target, String id,
            String text, String context) throws Exception {
        return http.sendAsync(request(target, id, "SendStreamingMessage", text, context),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest request(SutStack target, String id, String method, String text, String context)
            throws Exception {
        return request(target, id, method, text, context, null);
    }

    private HttpRequest request(SutStack target, String id, String method, String text, String context,
            String taskId) throws Exception {
        return HttpRequest.newBuilder(URI.create(base(target) + "/a2a/agents/" + id))
                .timeout(MODEL_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        rpc(method, text, context, taskId, Map.of())))
                .build();
    }

    private JsonNode activeTaskSnapshot(SutStack target, String agentId) throws Exception {
        String suffix = agentId == null ? "" : "?agentId=" + agentId;
        HttpResponse<String> response = get(target, "/v1/current_active_tasks" + suffix);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode snapshot = JSON.readTree(response.body());
        assertThat(snapshot.path("tasks").isArray()).as(response.body()).isTrue();
        return snapshot;
    }

    private static void assertTaskSnapshot(JsonNode snapshot, List<String> expectedConversationIds) {
        List<String> actualConversationIds = new ArrayList<>();
        snapshot.path("tasks").forEach(task -> {
            assertThat(task.path("taskId").asText()).isNotBlank();
            assertThat(task.path("conversationId").asText()).isNotBlank();
            assertThat(task.path("status").asText()).isEqualTo("WORKING");
            assertThat(task.path("startedAt").asText()).isNotBlank();
            assertThat(task.has("agentId")).isFalse();
            assertThat(task.has("agent_id")).isFalse();
            actualConversationIds.add(task.path("conversationId").asText());
        });
        assertThat(actualConversationIds).containsExactlyInAnyOrderElementsOf(expectedConversationIds);
    }

    private static String rpc(String method, String text, String context, String taskId,
            Map<String, Object> metadata) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        if ("GetTask".equals(method) || "SubscribeToTask".equals(method)) {
            params.put("id", taskId);
        } else {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "ROLE_USER");
            message.put("messageId", UUID.randomUUID().toString());
            message.put("parts", List.of(Map.of("text", text)));
            if (context != null) {
                message.put("contextId", context);
            }
            if (taskId != null) {
                message.put("taskId", taskId);
            }
            params.put("message", message);
            if (metadata != null && !metadata.isEmpty()) {
                params.put("metadata", metadata);
            }
        }
        return JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", UUID.randomUUID().toString(),
                "method", method, "params", params));
    }

    private HttpResponse<String> get(SutStack target, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base(target) + path))
                        .timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String url, String body, boolean streaming) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(MODEL_REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (streaming) {
            request.header("Accept", "text/event-stream");
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode catalog(SutStack target) throws Exception {
        HttpResponse<String> response = get(target, "/a2a/agents");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private void assertRoute(HttpResponse<String> response, String id, String canary) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains(canary, identity(id));
        assertThat(response.body()).doesNotContain(identity(peer(id)));
    }

    private static void assertRpcError(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(firstPayload(response.body()).path("error").isObject()).as(response.body()).isTrue();
    }

    private String identity(String id) {
        return "agent-a".equals(id) ? identityA : identityB;
    }

    private static String peer(String id) {
        return "agent-a".equals(id) ? "agent-b" : "agent-a";
    }

    private ManagedSutInstance managed(SutStack target) {
        return (ManagedSutInstance) target.managedInstance(hostedName);
    }

    private String base(SutStack target) {
        return target.baseUrl(hostedName);
    }

    private String canary(String caseId) {
        return "FEAT037_" + family + "_" + caseId + "_" + UUID.randomUUID();
    }

    private static String context(String label) {
        return "feat037-" + label + "-" + UUID.randomUUID();
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("content-type").orElse("");
    }

    private static List<String> agentIds(JsonNode listing) {
        List<String> ids = new ArrayList<>();
        listing.path("agents").forEach(node -> ids.add(node.asText()));
        return ids;
    }

    private static List<JsonNode> ssePayloads(String body) throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        for (String line : body.lines().toList()) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).strip();
            if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                frames.add(JSON.readTree(payload));
            }
        }
        return frames;
    }

    private static JsonNode firstPayload(String body) throws Exception {
        String trimmed = body.strip();
        if (trimmed.startsWith("{")) {
            return JSON.readTree(trimmed);
        }
        List<JsonNode> frames = ssePayloads(body);
        if (frames.isEmpty()) {
            throw new AssertionError("No JSON-RPC payload in response: " + body);
        }
        return frames.get(0);
    }

    private static String requireTaskId(String body) throws Exception {
        String trimmed = body.strip();
        if (trimmed.startsWith("{")) {
            return requireTaskId(JSON.readTree(trimmed), body);
        }
        for (JsonNode frame : ssePayloads(body)) {
            String id = taskId(frame);
            if (!id.isBlank()) {
                return id;
            }
        }
        throw new AssertionError("No task ID in response: " + body);
    }

    private static String requireTaskId(JsonNode root, String body) {
        String id = taskId(root);
        assertThat(id).as(body).isNotBlank();
        return id;
    }

    private static String taskId(JsonNode root) {
        String id = root.path("result").path("task").path("id").asText();
        if (id.isBlank()) {
            id = root.path("result").path("id").asText();
        }
        if (id.isBlank()) {
            id = root.path("result").path("statusUpdate").path("taskId").asText();
        }
        if (id.isBlank()) {
            id = root.path("result").path("artifactUpdate").path("taskId").asText();
        }
        return id;
    }

    private static boolean metadataContainsIdentity(JsonNode node, String id) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if ("metadata".equals(field.getKey()) && field.getValue().toString().contains(id)) {
                    return true;
                }
                if (metadataContainsIdentity(field.getValue(), id)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (metadataContainsIdentity(item, id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizedError(JsonNode root, String taskId) {
        JsonNode error = root.path("error");
        String text = error.path("code").asText() + ":" + error.path("message").asText()
                + ":" + error.path("data").toString() + ":" + error.path("details").toString();
        text = text.replace(taskId, "<task-id>");
        return UUID_VALUE.matcher(text).replaceAll("<uuid>");
    }

    private static int count(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    /** Deterministic OpenAI-compatible model transport used only as an input fixture. */
    public static final class OpenAiEchoFixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final Map<String, HoldGate> holds = new ConcurrentHashMap<>();

        private OpenAiEchoFixture(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        public static OpenAiEchoFixture start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = Executors.newCachedThreadPool();
                OpenAiEchoFixture fixture = new OpenAiEchoFixture(server, executor);
                server.createContext("/", fixture::reply);
                server.setExecutor(executor);
                server.start();
                return fixture;
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot start FEAT-037 model fixture", failure);
            }
        }

        public String apiBase() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        public HoldHandle hold(String marker, int expectedArrivals) {
            if (marker == null || marker.isBlank() || expectedArrivals < 1) {
                throw new IllegalArgumentException("Hold marker and positive arrival count are required");
            }
            HoldGate gate = new HoldGate(expectedArrivals);
            if (holds.putIfAbsent(marker, gate) != null) {
                throw new IllegalStateException("Duplicate FEAT-037 hold marker: " + marker);
            }
            return new HoldHandle(marker, gate);
        }

        private void reply(HttpExchange exchange) throws IOException {
            try (exchange) {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                String raw = request.toString();
                String identity = raw.contains("HOSTED_TRAVEL_AGENT_A") ? "HOSTED_TRAVEL_AGENT_A"
                        : raw.contains("HOSTED_TRAVEL_AGENT_B") ? "HOSTED_TRAVEL_AGENT_B"
                        : raw.contains("HOSTED_DEEP_AGENT_A") ? "HOSTED_DEEP_AGENT_A"
                        : raw.contains("HOSTED_DEEP_AGENT_B") ? "HOSTED_DEEP_AGENT_B" : "LEGACY_OK";
                List<String> userTexts = new ArrayList<>();
                collectUserTexts(request.path("messages"), userTexts);
                String content = identity + " " + String.join(" ", userTexts);
                awaitMatchingHold(content);
                byte[] response;
                if (request.path("stream").asBoolean()) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    String first = JSON.writeValueAsString(envelope("chat.completion.chunk",
                            Map.of("index", 0, "delta", Map.of("role", "assistant", "content", content))));
                    String last = JSON.writeValueAsString(envelope("chat.completion.chunk",
                            Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")));
                    response = ("data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    response = JSON.writeValueAsBytes(envelope("chat.completion",
                            Map.of("index", 0, "message", Map.of("role", "assistant", "content", content),
                                    "finish_reason", "stop")));
                }
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        }

        private static Map<String, Object> envelope(String type, Map<String, Object> choice) {
            return Map.of("id", "feat037-model", "object", type, "created", 1,
                    "model", "feat037-echo", "choices", List.of(choice));
        }

        private void awaitMatchingHold(String content) throws IOException {
            for (Map.Entry<String, HoldGate> entry : holds.entrySet()) {
                if (!content.contains(entry.getKey())) {
                    continue;
                }
                HoldGate gate = entry.getValue();
                gate.arrivals.countDown();
                try {
                    if (!gate.release.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release FEAT-037 model hold");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting on FEAT-037 model hold", interrupted);
                }
                return;
            }
        }

        private static void collectUserTexts(JsonNode messages, List<String> out) {
            if (!messages.isArray()) {
                return;
            }
            for (JsonNode message : messages) {
                if (!"user".equalsIgnoreCase(message.path("role").asText())) {
                    continue;
                }
                JsonNode content = message.path("content");
                if (content.isTextual()) {
                    out.add(content.asText());
                } else {
                    for (JsonNode part : content) {
                        String text = part.path("text").asText();
                        if (!text.isBlank()) {
                            out.add(text);
                        }
                    }
                }
            }
        }

        @Override
        public void close() {
            holds.values().forEach(gate -> gate.release.countDown());
            holds.clear();
            server.stop(0);
            executor.shutdownNow();
        }

        private static final class HoldGate {
            private final CountDownLatch arrivals;
            private final CountDownLatch release = new CountDownLatch(1);

            private HoldGate(int expectedArrivals) {
                this.arrivals = new CountDownLatch(expectedArrivals);
            }
        }

        public final class HoldHandle implements AutoCloseable {
            private final String marker;
            private final HoldGate gate;

            private HoldHandle(String marker, HoldGate gate) {
                this.marker = marker;
                this.gate = gate;
            }

            public void awaitArrivals() throws InterruptedException {
                assertThat(gate.arrivals.await(30, TimeUnit.SECONDS))
                        .as("all expected model requests must reach hold %s", marker)
                        .isTrue();
            }

            @Override
            public void close() {
                gate.release.countDown();
                holds.remove(marker, gate);
            }
        }
    }
}
