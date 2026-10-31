package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062DirectRegistryReactAgentTest.*;

@Tag("integration") @Tag("openjiuwen") @Tag("feat-062") @Tag("blackbox")
@Feature("FEAT-062: 直连模式支持 Agent 动态注册和发现")
class Feat062NamespaceIsolationReactAgentTest {
    static void routes(Feat062Fixture f) throws Exception {
        for (String id : f.dynamicAgents()) {
            String role = id.equals("travel-trip") ? "trip" : "hotel";
            JsonNode instances = f.registryJson("GET", "/agents/" + id + "/instances", null).path("instances");
            assertThat(instances.size()).isEqualTo(1);
            JsonNode route = f.registryJson("POST", "/route-handle:resolve", Map.of("routeHandle", instances.get(0).path("routeHandle").asText()));
            String endpoint = role.equals("trip") ? "/a2a" : "/a2a/agents/" + id;
            assertThat(route.path("endpointUrl").asText()).isEqualTo(f.wires.get(role).url() + endpoint);
        }
    }
    static JsonNode plan(Feat062Fixture f, String marker) throws Exception {
        var task = completed(f, f.send("mainplan", "/a2a", f.prompt(marker), false, marker, null), "mainplan", "/a2a");
        assertChain(f, marker, task); return task;
    }
    @Test @Tag("story-feat-062-isolation-namespace-concurrent")
    @Story("FEAT-062.isolation.namespace-concurrent: 同 RDC 跨 namespace 同名 Agent 并发") @DisplayName("Feat-062 同 RDC 跨 namespace 同名 Agent 并发")
    void feat062IsolationNamespaceConcurrent() throws Exception {
        try (var a = new Feat062Fixture("dynamic"); var b = new Feat062Fixture("dynamic", a.rdcUrl, a.token)) {
            a.startTravel(false); plan(a, "A_BEFORE_SECOND_NAMESPACE_" + UUID.randomUUID()); routes(a);
            b.startTravel(false); plan(b, "B_AFTER_SECOND_NAMESPACE_" + UUID.randomUUID()); routes(b);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var fa = pool.submit(() -> plan(a, "NS_A_" + UUID.randomUUID()));
                var fb = pool.submit(() -> plan(b, "NS_B_" + UUID.randomUUID()));
                JsonNode ta = fa.get(300, TimeUnit.SECONDS), tb = fb.get(300, TimeUnit.SECONDS);
                assertThat(ta.toString()).contains(a.inventoryCanary).doesNotContain(b.inventoryCanary);
                assertThat(tb.toString()).contains(b.inventoryCanary).doesNotContain(a.inventoryCanary);
            }
            routes(a); routes(b);
            for (var f : List.of(a,b)) {
                String marker = "NS_SSE_" + UUID.randomUUID();
                var streamed = f.send("mainplan", "/a2a", f.prompt(marker), true, marker, null);
                assertThat(streamed.contentType()).contains("text/event-stream");
                var task = completed(f, streamed, "mainplan", "/a2a"); assertChain(f, marker, task);
                assertThat(task.toString()).doesNotContain(f == a ? b.inventoryCanary : a.inventoryCanary);
            }
        }
    }
    @Test @Tag("story-feat-062-isolation-namespace-churn")
    @Story("FEAT-062.isolation.namespace-churn: 另一 namespace 上下线不污染本域") @DisplayName("Feat-062 另一 namespace 上下线不污染本域")
    void feat062IsolationNamespaceChurn() throws Exception {
        try (var a = new Feat062Fixture("dynamic"); var b = new Feat062Fixture("dynamic", a.rdcUrl, a.token)) {
            a.startTravel(false); plan(a, "A_BEFORE_NAMESPACE_CHURN_" + UUID.randomUUID()); routes(a);
            b.startTravel(false); plan(b, "B_BEFORE_NAMESPACE_CHURN_" + UUID.randomUUID()); routes(b);
            Map<String,String> original = new HashMap<>();
            for (String id : a.dynamicAgents()) original.put(id, a.registryJson("GET", "/agents/" + id + "/instances", null).path("instances").get(0).path("instanceId").asText());
            try (var pool = Executors.newSingleThreadExecutor()) {
                for (int round = 0; round < 2; round++) {
                    var runningA = pool.submit(() -> plan(a, "A_CHURN_" + UUID.randomUUID()));
                    b.stacks.get("trip").stop("trip"); b.stacks.get("hotel").stop("hotel");
                    routes(a); runningA.get(300, TimeUnit.SECONDS);
                    b.stacks.get("hotel").start("hotel"); b.stacks.get("trip").start("trip"); b.awaitRegistry();
                    routes(a);
                    // B's own restart readiness is distinct from the cross-namespace oracle on A.
                    // The online scenario measures the registration-to-callable TTL separately.
                    await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(100))
                            .untilAsserted(() -> routes(b));
                    for (String id : original.keySet()) assertThat(a.registryJson("GET", "/agents/" + id + "/instances", null)
                            .path("instances").get(0).path("instanceId").asText()).isEqualTo(original.get(id));
                }
            }
            plan(a, "A_STILL_HEALTHY_" + UUID.randomUUID());
        }
    }
    @Test @Tag("story-feat-062-isolation-namespace-resume")
    @Story("FEAT-062.isolation.namespace-resume: 跨 namespace 并发时恢复仍完整") @DisplayName("Feat-062 跨 namespace 并发时恢复仍完整")
    void feat062IsolationNamespaceResume() throws Exception {
        try (var a = new Feat062Fixture("dynamic"); var b = new Feat062Fixture("dynamic", a.rdcUrl, a.token)) {
            a.startTravel(true); routes(a);
            String marker = "A_RESUME_" + UUID.randomUUID();
            JsonNode paused = a.send("mainplan", "/a2a", marker + " 我要去北京出差，尚未提供日期和差标，请先追问。", false, marker, null).task();
            assertThat(state(paused)).isEqualTo("INPUT_REQUIRED");
            b.startTravel(false); plan(b, "B_BEFORE_A_RESUMES_" + UUID.randomUUID()); routes(b);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var runningB = pool.submit(() -> plan(b, "B_DURING_RESUME_" + UUID.randomUUID()));
                var resumed = a.send("mainplan", "/a2a", a.prompt(marker), false,
                        paused.path("contextId").asText(), paused.path("id").asText());
                JsonNode done = completed(a, resumed, "mainplan", "/a2a"); assertChain(a, marker, done);
                assertThat(done.path("id").asText()).isEqualTo(paused.path("id").asText());
                assertThat(done.toString()).doesNotContain(b.inventoryCanary); runningB.get(300, TimeUnit.SECONDS);
            }
            routes(a); routes(b);
        }
    }
}
