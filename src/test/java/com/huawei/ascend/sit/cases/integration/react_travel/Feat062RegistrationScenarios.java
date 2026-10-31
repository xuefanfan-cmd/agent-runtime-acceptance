package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Reusable scenario actions, each invoked by its own uniquely traced JUnit method. */
final class Feat062RegistrationScenarios {
    static void standardCard() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.startTravel(false); compareCard(f, "trip", "travel-trip", "/a2a"); complete(f, "STANDARD");
            var registered = f.registry.requests.stream().filter(n -> n.path("httpMethod").asText().equals("POST")
                    && n.path("path").asText().endsWith("/instances")).map(n -> n.path("path").asText()).toList();
            assertThat(registered).contains("/v2/agents/travel-trip/instances");
            assertThat(registered.stream().filter(s -> s.contains("travel-trip")).count()).isEqualTo(1);
        }
    }
    static void hostedCards() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.startTravel(false);
            for (String id : List.of("travel-hotel", "travel-hotel-alt")) compareCard(f, "hotel", id, "/a2a/agents/" + id);
            var posts = f.registry.requests.stream().filter(n -> n.path("httpMethod").asText().equals("POST")
                    && n.path("path").asText().contains("travel-hotel") && n.path("path").asText().endsWith("/instances")).toList();
            assertThat(posts).hasSize(2); complete(f, "HOSTED");
        }
    }
    static void compareCard(Feat062Fixture f, String role, String id, String endpoint) throws Exception {
        String base = role.equals("trip") ? "" : endpoint;
        JsonNode publicCard = JSON.readTree(request("GET", f.wires.get(role).url() + base + "/.well-known/agent-card.json", null, Map.of()).body());
        JsonNode registryCard = f.registryJson("GET", "/agents/" + id + "/card", null);
        assertThat(registryCard.path("name").asText()).isEqualTo(id);
        assertThat(registryCard.path("rawJson").asText()).isNotBlank();
        JsonNode registeredCard = JSON.readTree(registryCard.path("rawJson").asText());
        f.save("published-card-" + id + ".json", publicCard);
        f.save("registered-card-" + id + ".json", registeredCard);
        assertThat(withoutNulls(registeredCard)).isEqualTo(withoutNulls(publicCard));
        JsonNode candidates = f.registryJson("GET", "/agents/" + id + "/instances", null).path("instances");
        assertThat(candidates.size()).isEqualTo(1);
        JsonNode resolved = f.registryJson("POST", "/route-handle:resolve", Map.of("routeHandle", candidates.get(0).path("routeHandle").asText()));
        assertThat(resolved.path("endpointUrl").asText()).isEqualTo(f.wires.get(role).url() + endpoint);
    }
    static JsonNode withoutNulls(JsonNode input) {
        JsonNode copy = input.deepCopy();
        if (copy.isObject()) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) copy;
            List<String> fields = new ArrayList<>(); object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (object.path(field).isNull()) object.remove(field);
                else object.set(field, withoutNulls(object.path(field)));
            }
        } else if (copy.isArray()) {
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) copy;
            for (int i = 0; i < array.size(); i++) array.set(i, withoutNulls(array.get(i)));
        }
        return copy;
    }
    static void upsert() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.startTravel(false);
            for (String id : List.of("travel-trip", "travel-hotel", "travel-hotel-alt")) {
                var candidates = f.registryJson("GET", "/agents/" + id + "/instances", null).path("instances");
                String original = candidates.get(0).path("instanceId").asText();
                JsonNode body = f.registry.requests.stream().filter(n -> n.path("httpMethod").asText().equals("POST")
                        && n.path("path").asText().equals("/v2/agents/" + id + "/instances")).findFirst().orElseThrow().path("body");
                var result = f.registryJson("POST", "/agents/" + id + "/instances", body);
                assertThat(result.path("instanceId").asText()).isEqualTo(original);
                var after = f.registryJson("GET", "/agents/" + id + "/instances", null).path("instances");
                assertThat(after.size()).isEqualTo(1); assertThat(after.get(0).path("instanceId").asText()).isEqualTo(original);
            }
            complete(f, "UPSERT");
        }
    }
    static int heartbeatTtl(Feat062Fixture f) throws Exception {
        try (var files = Files.walk(f.evidence)) {
            for (Path path : files.filter(p -> p.getParent().getFileName().toString().startsWith("registry")
                    && p.getFileName().toString().endsWith("-response.txt")).toList()) {
                JsonNode body = JSON.readTree(Files.readString(path));
                int ttl = body.at("/heartbeat/ttlSeconds").asInt(); if (ttl > 0) return ttl;
            }
        }
        throw new IllegalStateException("Fixture did not capture server heartbeat policy");
    }
    static void heartbeat() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.startTravel(false); int ttl = heartbeatTtl(f); List<Map<String,Object>> samples = new ArrayList<>();
            long start = System.nanoTime();
            await().atMost(Duration.ofSeconds(2L * ttl + 15)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                for (String id : f.dynamicAgents()) {
                    var result = f.registryRequest("GET", "/agents/" + id + "/instances", null);
                    int count = JSON.readTree(result.body()).path("instances").size();
                    samples.add(Map.of("nanoTime", System.nanoTime(), "agent", id, "count", count));
                    assertThat(count).as("healthy registered identity must remain online").isEqualTo(1);
                }
                return System.nanoTime() - start >= Duration.ofSeconds(2L * ttl).toNanos();
            });
            f.save("heartbeat-samples.json", Map.of("serverTtlSeconds", ttl, "samples", samples, "falseRemovalCount", 0));
            assertThat(f.registry.requests.stream().filter(n -> n.path("path").asText().endsWith("/heartbeat")).count()).isGreaterThanOrEqualTo(6);
            complete(f, "HEARTBEAT");
        }
    }
    static void heartbeatNotFound() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.contractHost = true;
            f.startTravel(false);
            for (String id : List.of("travel-trip", "travel-hotel")) {
                String instance = f.registryJson("GET", "/agents/" + id + "/instances", null).path("instances").get(0).path("instanceId").asText();
                int postsBefore = (int) f.registry.requests.stream().filter(n -> n.path("path").asText().equals("/v2/agents/" + id + "/instances")
                        && n.path("httpMethod").asText().equals("POST")).count();
                assertThat(f.registryRequest("DELETE", "/agents/" + id + "/instances/" + instance, null).statusCode()).isEqualTo(204);
                await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).ignoreExceptions().untilAsserted(() -> {
                    long posts = f.registry.requests.stream().filter(n -> n.path("path").asText().equals("/v2/agents/" + id + "/instances")
                            && n.path("httpMethod").asText().equals("POST")).count();
                    assertThat(posts).isGreaterThan(postsBefore);
                    var candidates = f.registryJson("GET", "/agents/" + id + "/instances", null).path("instances");
                    assertThat(candidates.size()).isEqualTo(1); assertThat(candidates.get(0).path("instanceId").asText()).isEqualTo(instance);
                });
            }
            compareCard(f, "hotel", "travel-hotel-alt", "/a2a/agents/travel-hotel-alt");
            await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                JsonNode entries = Feat062RegistryExtensionIntegrationTest.catalog(f, "mainplan").path("entries");
                for (String id : List.of("travel-trip", "travel-hotel")) {
                    List<JsonNode> found = new ArrayList<>();
                    entries.forEach(entry -> { if (entry.path("name").asText().equals(id)) found.add(entry); });
                    assertThat(found).hasSize(1);
                    assertThat(found.getFirst().path("instances").size()).isEqualTo(1);
                }
            });
            complete(f, "HEAL");
        }
    }
    static void gracefulStop() throws Exception {
        for (String role : List.of("trip", "hotel")) try (var f = new Feat062Fixture("dynamic")) {
            f.startTravel(false); complete(f, "BEFORE_STOP");
            Set<String> expected = role.equals("trip") ? Set.of("travel-trip") : Set.of("travel-hotel", "travel-hotel-alt");
            int before = f.registry.requests.size(); f.stacks.get(role).stop(role);
            List<JsonNode> deletes = f.registry.requests.stream().skip(before)
                    .filter(n -> n.path("httpMethod").asText().equals("DELETE")).toList();
            assertThat(deletes).hasSize(expected.size());
            for (String id : expected) {
                assertThat(deletes).anyMatch(n -> n.path("path").asText().startsWith("/v2/agents/" + id + "/instances/"));
                await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                        assertThat(f.registryJson("GET", "/agents/" + id + "/instances", null).path("instances").size()).isZero());
            }
            String surviving = role.equals("trip") ? "travel-hotel" : "travel-trip";
            assertThat(f.registryJson("GET", "/agents/" + surviving + "/instances", null).path("instances").size()).isEqualTo(1);
        }
    }
    static void complete(Feat062Fixture f, String name) throws Exception {
        String marker = name + "_" + UUID.randomUUID();
        var call = f.send("mainplan", "/a2a", f.prompt(marker), false, marker, null);
        var task = Feat062DirectRegistryReactAgentTest.completed(f, call, "mainplan", "/a2a");
        Feat062DirectRegistryReactAgentTest.assertChain(f, marker, task);
    }
}
