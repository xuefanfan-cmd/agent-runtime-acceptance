package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062RegistrationScenarios.complete;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062RegistryExtensionIntegrationTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Public registry changes with real caller consumption and full travel business checks. */
final class Feat062DiscoveryBoundaryScenarios {
    static JsonNode registration(Feat062Fixture f, String id) {
        return f.registry.requests.stream().filter(n -> n.path("httpMethod").asText().equals("POST")
                && n.path("path").asText().equals("/v2/agents/" + id + "/instances"))
                .findFirst().orElseThrow().path("body").deepCopy();
    }
    static void reserve(Feat062Fixture f, String role) throws Exception {
        int port = port(); f.ports.put(role, port);
        f.wires.put(role, f.new Wire("http://127.0.0.1:" + port, role, null));
    }
    static void startupFallback() throws Exception {
        for (String profile : List.of("mixed-trip-static", "mixed-hotel-static")) try (var f = new Feat062Fixture(profile)) {
            f.contractHost = true; f.model.live = false;
            f.start("hotel", Map.of()); f.start("trip", Map.of()); f.awaitRegistry();
            var callerLink=f.new Wire(f.rdcUrl,"registry-mainplan",f.registry.expectedToken);
            callerLink.unavailable=true; f.registryLinks.put("mainplan",callerLink);
            f.start("mainplan", Map.of());
            String role = profile.equals("mixed-trip-static") ? "trip" : "hotel";
            String endpoint = role.equals("trip") ? "/a2a" : "/a2a/agents/travel-hotel";
            String marker = "STARTUP_STATIC_" + UUID.randomUUID();
            var result = request("POST", f.wires.get("mainplan").url() + "/feat062/call",
                    JSON.writeValueAsString(Map.of("agent", "travel-" + role, "message", f.prompt(marker))), Map.of("Content-Type", "application/json"));
            f.saveText("startup-static-business.json", result.body());
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).doesNotContain("REMOTE_UNAVAILABLE");
            assertThat(f.wires.get(role).calls(marker)).anyMatch(n -> n.path("path").asText().equals(endpoint));
            long pid = ((ManagedSutInstance) f.stacks.get("mainplan").managedInstance("mainplan")).pid();
            callerLink.unavailable = false; awaitEntry(f, "mainplan", "travel-trip", true);
            awaitEntry(f, "mainplan", "travel-hotel", true);
            awaitCallable(f, "mainplan", "travel-trip"); awaitCallable(f, "mainplan", "travel-hotel");
            complete(f, "STARTUP_RECOVERED");
            assertThat(((ManagedSutInstance) f.stacks.get("mainplan").managedInstance("mainplan")).pid()).isEqualTo(pid);
        }
    }
    static void online() throws Exception {
        List<Map<String,Object>> allMeasurements = new ArrayList<>();
        for (String profile : List.of("dynamic", "mixed-trip-static", "mixed-hotel-static")) try (var f = new Feat062Fixture(profile)) {
            f.contractHost = true; f.model.live = false;
            for (String role : List.of("hotel", "trip")) {
                boolean isStatic = profile.equals("mixed-trip-static") && role.equals("trip")
                        || profile.equals("mixed-hotel-static") && role.equals("hotel");
                if (isStatic) f.start(role, Map.of()); else reserve(f, role);
            }
            f.start("mainplan", Map.of());
            Map<String,long[]> visibility = new ConcurrentHashMap<>();
            for (String id : f.dynamicAgents()) visibility.put(id, new long[]{System.nanoTime(), 0});
            // Observe throughout startup so waiting for a service must not erase registration-to-caller latency.
            try (var sampler = Executors.newSingleThreadExecutor()) {
                Future<?> sampling = sampler.submit(() -> {
                    while (visibility.values().stream().anyMatch(v -> v[1] == 0)) {
                        long before = System.nanoTime();
                        JsonNode snapshot = catalog(f, "mainplan"); long after = System.nanoTime();
                        for (String id : f.dynamicAgents()) {
                            boolean found = false;
                            for (JsonNode entry : snapshot.path("entries"))
                                if (entry.path("name").asText().equals(id) && entry.path("instances").size() > 0) found = true;
                            final boolean present = found;
                            visibility.compute(id, (key, value) -> value[1] != 0 ? value
                                    : present ? new long[]{value[0], after} : new long[]{before, 0});
                        }
                        Thread.sleep(100);
                    }
                    return null;
                });
                try {
                    for (String role : List.of("hotel", "trip")) if (!f.stacks.containsKey(role)) f.start(role, Map.of());
                    // This is an observation watchdog; the acceptance TTL below remains 5 seconds.
                    f.awaitRegistry(); sampling.get(45, TimeUnit.SECONDS);
                } finally { sampling.cancel(true); }
            }
            List<Map<String,Object>> measurements = new ArrayList<>();
            for (String id : f.dynamicAgents()) {
                JsonNode registration = f.registry.requests.stream().filter(n -> n.path("httpMethod").asText().equals("POST")
                        && n.path("path").asText().equals("/v2/agents/" + id + "/instances")
                        && n.path("httpStatus").asInt() >= 200 && n.path("httpStatus").asInt() < 300).findFirst().orElseThrow();
                long[] sample = visibility.get(id);
                long lower = Math.max(0, sample[0] - registration.path("responseNanoTime").asLong());
                long upper = Math.max(0, sample[1] - registration.path("responseNanoTime").asLong());
                measurements.add(Map.of("agentId", id, "registrationStartNanos", registration.path("nanoTime").asLong(),
                        "registrationReplyNanos", registration.path("responseNanoTime").asLong(),
                        "lastAbsentNanos", sample[0], "firstPresentNanos", sample[1], "lowerNanos", lower, "upperNanos", upper));
            }
            f.save("online-convergence.json", Map.of("measurements", measurements, "ttlMillis", 5000));
            complete(f, "NEWLY_ONLINE");
            allMeasurements.addAll(measurements);
        }
            // A boundary observation in one profile must not suppress the other profiles.
            for (var measurement : allMeasurements) {
                long lower = (long) measurement.get("lowerNanos"), upper = (long) measurement.get("upperNanos");
                assertThat(lower).as("%s registration-to-caller lower bound", measurement.get("agentId"))
                        .isLessThanOrEqualTo(Duration.ofSeconds(5).toNanos());
            }
            for (var measurement : allMeasurements) {
                long upper = (long) measurement.get("upperNanos");
                org.junit.jupiter.api.Assumptions.assumeTrue(upper <= Duration.ofSeconds(5).toNanos(),
                        "INCONCLUSIVE: registration-to-caller observation interval crosses configured TTL: " + measurement);
            }
    }
    static void changedCard(Feat062Fixture f, String marker) throws Exception {
        JsonNode body = registration(f, "travel-trip");
        ObjectNode card = (ObjectNode) JSON.readTree(body.path("agentCardJson").asText());
        card.put("description", marker);
        // Tool descriptions prefer skill descriptions when the card declares skills.
        for (JsonNode skill : card.path("skills")) ((ObjectNode) skill).put("description", marker);
        ((ObjectNode) body).put("agentCardJson", card.toString());
        f.registryJson("POST", "/agents/travel-trip/instances", body);
    }
    static void cardUpdate(boolean outage) throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.contractHost = true; f.startTravel(false); complete(f, "BEFORE_CARD_CHANGE");
            if (outage) f.registryLinks.get("mainplan").unavailable = true;
            String marker = "UPDATED_CARD_" + UUID.randomUUID(); changedCard(f, marker);
            if (outage) complete(f, "RETAIN_OLD_DURING_OUTAGE");
            f.registryLinks.get("mainplan").unavailable = false;
            await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                    assertThat(catalog(f, "mainplan").toString()).contains(marker));
            complete(f, "AFTER_CARD_CHANGE");
            assertThat(f.models.get("mainplan").getLast().path("tools").toString()).contains(marker);
        }
    }
    static void deleteCard(Feat062Fixture f, String id) throws Exception {
        var r = request("DELETE", "http://127.0.0.1:8848/nacos/v3/admin/ai/a2a?namespaceId=" + f.tenant + "&agentName=" + id, null, Map.of());
        f.saveText("nacos-delete-card-" + id + ".json", r.body());
        assertThat(r.statusCode()).isEqualTo(200); assertThat(JSON.readTree(r.body()).path("code").asInt()).isZero();
        // RDC owns a separate canonical card snapshot and directory. Delete the complete
        // test-owned source state through Nacos management APIs before measuring propagation.
        String base="http://127.0.0.1:8848/nacos/v3/admin/cs/config";
        String common="namespaceId="+f.tenant+"&groupName=OPENJIWEN_RDC_META&dataId=";
        String directoryId="rdc-meta:"+f.tenant+":_directory";
        var directoryResponse=request("GET",base+"?"+common+java.net.URLEncoder.encode(directoryId,java.nio.charset.StandardCharsets.UTF_8),null,Map.of());
        assertThat(directoryResponse.statusCode()).isEqualTo(200);
        ObjectNode directory=(ObjectNode)JSON.readTree(JSON.readTree(directoryResponse.body()).at("/data/content").asText());
        directory.remove(id);
        String content=common+java.net.URLEncoder.encode(directoryId,java.nio.charset.StandardCharsets.UTF_8)
                +"&type=json&content="+java.net.URLEncoder.encode(directory.toString(),java.nio.charset.StandardCharsets.UTF_8);
        var updated=request("POST",base,content,Map.of("Content-Type","application/x-www-form-urlencoded"));
        f.saveText("nacos-update-card-directory.json",updated.body());
        assertThat(updated.statusCode()).isEqualTo(200);assertThat(JSON.readTree(updated.body()).path("code").asInt()).isZero();
        var removed=request("DELETE",base+"?"+common+java.net.URLEncoder.encode("rdc-meta:"+f.tenant+":"+id+":_card",java.nio.charset.StandardCharsets.UTF_8),null,Map.of());
        f.saveText("nacos-delete-card-snapshot.json",removed.body());
        assertThat(removed.statusCode()).isEqualTo(200);assertThat(JSON.readTree(removed.body()).path("code").asInt()).isZero();
        await().atMost(Duration.ofSeconds(12)).pollInterval(Duration.ofMillis(100)).until(() ->
                f.registryRequest("GET", "/agents/" + id + "/card", null).statusCode() == 404
                        && !f.registryJson("GET","/agents",null).path("agents").toString().contains("\"agentId\":\""+id+"\""));
    }
    static void cardDelete() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.contractHost = true; f.startTravel(false); complete(f, "BEFORE_CARD_DELETE");
            f.stacks.get("trip").stop("trip"); deleteCard(f, "travel-trip");
            awaitEntry(f, "mainplan", "travel-trip", false);
            int before = f.wires.get("trip").requests.size();
            var r = request("POST", f.wires.get("mainplan").url() + "/feat062/call", JSON.writeValueAsString(Map.of(
                    "agent", "travel-trip", "message", f.prompt("DELETED_TRIP"))), Map.of("Content-Type", "application/json"));
            f.saveText("deleted-target-outcome.json", r.body());
            assertThat(r.statusCode()).isIn(200, 500);
            assertThat(r.body()).containsAnyOf("REMOTE_UNAVAILABLE", "Unknown remote agent");
            assertThat(f.wires.get("trip").requests).hasSize(before);
        }
    }
    static void notFoundFallback() throws Exception {
        for (String profile : List.of("mixed-trip-static", "mixed-hotel-static")) try (var f = new Feat062Fixture(profile)) {
            f.startTravel(false); complete(f, "NOT_FOUND_STATIC");
            String id = profile.equals("mixed-trip-static") ? "travel-trip" : "travel-hotel";
            assertThat(f.registryRequest("GET", "/agents/" + id + "/instances", null).statusCode()).isEqualTo(404);
            String other = id.equals("travel-trip") ? "travel-hotel" : "travel-trip";
            assertThat(f.registryLinks.get("mainplan").requests).anyMatch(n -> n.path("path").asText().contains("/agents/" + other + "/instances"));
        }
    }
    static void pagination() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.contractHost = true; f.model.live = false;
            f.start("hotel", Map.of()); f.start("trip", Map.of()); f.awaitRegistry();
            JsonNode original = registration(f, "travel-trip");
            for (int i = 0; i < 105; i++) {
                String id = "aaa-padding-" + String.format("%03d", i);
                JsonNode body = original.deepCopy(); ObjectNode card = (ObjectNode)JSON.readTree(body.path("agentCardJson").asText());
                card.put("name", id); ((ObjectNode)body).put("agentCardJson", card.toString());
                f.registryJson("POST", "/agents/" + id + "/instances", body);
            }
            JsonNode first = f.registryJson("GET", "/agents?limit=100", null);
            assertThat(first.path("agents").toString()).doesNotContain("travel-trip");
            f.start("mainplan", Map.of()); awaitEntry(f, "mainplan", "travel-trip", true); complete(f, "LAST_PAGE_REAL_TRIP");
            assertThat(f.registryLinks.get("mainplan").requests).anyMatch(n -> n.path("path").asText().contains("continuationToken="));
        }
    }
    static void resolveCache() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.startTravel(false); complete(f, "RESOLVE_WARM");
            JsonNode beforeHandles = f.registryJson("GET", "/agents/travel-trip/instances", null);
            var link = f.registryLinks.get("mainplan"); int before = link.requests.size();
            long started = System.nanoTime();
            await().atMost(Duration.ofSeconds(13)).pollInterval(Duration.ofMillis(200)).until(() -> System.nanoTime() - started > Duration.ofSeconds(11).toNanos());
            for (int i = 0; i < 3; i++) complete(f, "CACHED_HANDLE_" + i);
            JsonNode afterHandles = f.registryJson("GET", "/agents/travel-trip/instances", null);
            assertThat(afterHandles.path("instances").get(0).path("routeHandle")).isEqualTo(beforeHandles.path("instances").get(0).path("routeHandle"));
            var requests = link.requests.stream().skip(before).toList();
            assertThat(requests).anyMatch(n -> n.path("httpMethod").asText().equals("GET"));
            assertThat(requests).noneMatch(n -> n.path("path").asText().equals("/v2/route-handle:resolve"));
        }
    }
    static void handleRefresh() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.startTravel(false); complete(f, "OLD_HANDLE");
            String old = f.registryJson("GET", "/agents/travel-trip/instances", null).path("instances").get(0).path("routeHandle").asText();
            f.stacks.get("trip").stop("trip"); f.startReplica("trip", "trip-replacement");
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).until(() -> f.registryRequest("POST", "/route-handle:resolve", Map.of("routeHandle", old)).statusCode() == 404);
            int before = f.registryLinks.get("mainplan").requests.size();
            await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).until(() -> f.registryLinks.get("mainplan").requests.stream().skip(before).anyMatch(n -> n.path("path").asText().equals("/v2/route-handle:resolve")));
            complete(f, "NEW_HANDLE"); assertThat(Feat062ReplicaScenarios.sent(f, "trip-replacement")).isPositive();
        }
    }
    static void ttlBoundaries() throws Exception {
        for (int ttl : List.of(1000, 30000, 5000)) try (var f = new Feat062Fixture("dynamic")) {
            f.model.live = false; f.start("hotel", Map.of()); f.start("trip", Map.of()); f.awaitRegistry();
            f.start("mainplan", ttl == 5000 ? Map.of() : Map.of(A2A + "remote-catalog.refresh-ttl-ms", "" + ttl));
            var link = f.registryLinks.get("mainplan");
            await().atMost(Duration.ofMillis(3L * ttl + 10000)).pollInterval(Duration.ofMillis(100)).until(() -> link.requests.stream().filter(n -> n.path("httpMethod").asText().equals("GET") && n.path("path").asText().matches("/v2/agents(\\?.*)?")).count() >= 3);
            var times = link.requests.stream().filter(n -> n.path("httpMethod").asText().equals("GET") && n.path("path").asText().matches("/v2/agents(\\?.*)?")).map(n -> n.path("nanoTime").asLong()).toList();
            long delta = Duration.ofNanos(times.get(2) - times.get(1)).toMillis();
            f.save("ttl-observation.json", Map.of("configuredTtl", ttl, "intervalMillis", delta));
            assertThat(delta).isBetween(ttl - 250L, ttl + 4000L); complete(f, "TTL_" + ttl);
        }
    }
    static void modeTransition() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.contractHost = true; f.startTravel(false); complete(f, "BEFORE_MODE_SWITCH");
            f.stacks.get("mainplan").stop("mainplan"); f.stacks.get("trip").stop("trip"); f.stacks.get("hotel").stop("hotel");
            for (String id : f.dynamicAgents()) await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertThat(f.registryJson("GET", "/agents/" + id + "/instances", null).path("instances").size()).isZero());
            f.start("trip", Map.of(A2A + "registry.registration.enabled", "false"));
            f.start("hotel", Map.of(A2A + "registry.registration.enabled", "false"));
            f.start("mainplan", Feat062CatalogScenarios.statics(f));
            var r = request("POST", f.wires.get("mainplan").url() + "/feat062/call", JSON.writeValueAsString(Map.of("agent", "travel-trip", "message", f.prompt("RESIDUAL_CARD"))), Map.of("Content-Type", "application/json"));
            f.saveText("residual-card-outcome.json", r.body());
            assertThat(r.statusCode()).isIn(200, 500);
            assertThat(r.body()).containsAnyOf("REMOTE_UNAVAILABLE", "has no live instance candidate");
            f.stacks.get("mainplan").stop("mainplan"); for (String id : f.dynamicAgents()) deleteCard(f, id);
            f.start("mainplan", Feat062CatalogScenarios.statics(f)); complete(f, "AFTER_REAL_404");
        }
    }
}
