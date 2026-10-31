package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062RegistrationScenarios.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class Feat062AuthenticationScenarios {
    static void headers() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.startTravel(false); complete(f, "AUTHENTICATED_BUSINESS");
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(() -> f.registry.requests.stream().anyMatch(n -> n.path("path").asText().endsWith("/heartbeat")));
            f.stacks.get("trip").stop("trip"); f.stacks.get("hotel").stop("hotel");
            var requests = f.registry.requests;
            assertThat(requests).allSatisfy(n -> {
                assertThat(n.path("authorizationMatches").asBoolean()).isTrue();
                assertThat(n.path("tenant").asText()).isEqualTo(f.tenant);
                assertThat(n.path("path").asText()).doesNotContain(f.tenant);
                assertThat(n.path("body").toString()).doesNotContain(f.tenant);
            });
            for (String method : List.of("GET", "POST", "PUT", "DELETE")) assertThat(requests).anyMatch(n -> n.path("httpMethod").asText().equals(method));
            try (var paths = Files.walk(f.evidence)) {
                for (Path path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".log") || p.toString().endsWith(".txt")).toList())
                    assertThat(Files.readString(path)).as("redacted evidence %s", path).doesNotContain(f.token, "Bearer " + f.token);
            }
        }
    }
    static void rejected(String kind) throws Exception {
        Map<String,String> config = switch (kind) {
            case "403" -> Map.of("openjiuwen.registry.auth.caller-tenant-bindings.test-caller", "tenant-A");
            case "410" -> Map.of("openjiuwen.registry.registration-paths[0]", "reconciliation");
            default -> Map.of();
        };
        for (String role : List.of("trip", "hotel")) try (var f = new Feat062Fixture("dynamic", null, null, config)) {
            f.model.live = false; if (kind.equals("403")) f.registry.callerRef = "test-caller";
            Map<String,String> settings = kind.equals("401") ? Map.of(A2A + "registry.token", "incorrect-test-token") : Map.of();
            var result = f.probeStartup(role, settings);
            assertThat(result.exited()).as("registration %s must fail startup: %s", kind, result.log()).isTrue();
            assertThat(result.ready()).isFalse(); assertThat(result.exitCode()).isNotZero();
            boolean observed = false;
            try (var paths = Files.walk(f.evidence)) {
                String code = kind.equals("401") ? "UNAUTHENTICATED" : kind.equals("403") ? "TENANT_MISMATCH" : "REGISTRATION_DISABLED";
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith("-response.txt")).toList()) if (Files.readString(path).contains(code)) observed = true;
                assertThat(observed).as("real RDC must return expected %s, not fixture-synthesized error", code).isTrue();
            }
            JsonNode listing = f.registryJson("GET", "/agents", null); assertThat(listing.path("agents").size()).isZero();
        }
    }
    static void runtimeOutage(boolean limited) throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.contractHost = true; f.startTravel(false); complete(f, "BEFORE_REGISTRATION_CHANNEL_FAULT");
            int ttl = heartbeatTtl(f); var link = f.registryLinks.get("trip"); int before = link.requests.size();
            if (limited) link.heartbeatStatus = 429; else link.unavailable = true;
            long start = System.nanoTime();
            await().atMost(Duration.ofSeconds(ttl + 20L)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertThat(f.registryJson("GET", "/agents/travel-trip/instances", null).path("instances").size()).isZero());
            var failedHeartbeats = link.requests.stream().skip(before).filter(n -> n.path("path").asText().endsWith("/heartbeat")).toList();
            assertThat(failedHeartbeats).isNotEmpty();
            if (limited) {
                assertThat(failedHeartbeats).allSatisfy(n -> assertThat(n.path("injectedStatus").asInt()).isEqualTo(429));
                for (int i = 1; i < failedHeartbeats.size(); i++) assertThat(Duration.ofNanos(failedHeartbeats.get(i).path("nanoTime").asLong() - failedHeartbeats.get(i - 1).path("nanoTime").asLong()).toMillis()).isGreaterThanOrEqualTo(500);
            }
            link.heartbeatStatus = 0; link.unavailable = false;
            await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertThat(f.registryJson("GET", "/agents/travel-trip/instances", null).path("instances").size()).isEqualTo(1));
            f.save("registration-channel-recovery.json", Map.of("serverTtlSeconds", ttl, "elapsedNanos", System.nanoTime() - start, "injected429", limited));
            await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                JsonNode snapshot = Feat062RegistryExtensionIntegrationTest.catalog(f, "mainplan");
                boolean ready = false;
                for (JsonNode entry : snapshot.path("entries")) if (entry.path("name").asText().equals("travel-trip"))
                    ready = entry.path("instances").size() > 0;
                assertThat(ready).as("caller has consumed the re-registered live candidate").isTrue();
            });
            complete(f, "AFTER_REGISTRATION_CHANNEL_RECOVERY");
        }
    }
}
