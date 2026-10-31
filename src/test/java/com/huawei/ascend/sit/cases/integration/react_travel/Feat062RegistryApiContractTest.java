package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.assertThat;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;

@Tag("integration") @Tag("openjiuwen") @Tag("feat-062") @Tag("contract")
@Feature("FEAT-062: 直连模式支持 Agent 动态注册和发现")
class Feat062RegistryApiContractTest {
    @Test @Tag("story-feat-062-contract-instance-readiness")
    @Story("FEAT-062.contract.instance-readiness: hosted 未就绪不返回部分清单") @DisplayName("Feat-062 hosted 未就绪不返回部分清单")
    void feat062ContractInstanceReadiness() throws Exception {
        try(var f=new Feat062Fixture("static")) {
            f.contractHost=true;f.model.live=false;
            for(String role:List.of("hotel","trip")) {
                Path observation=f.evidence.resolve("pre-ready-"+role+".json");
                Map<String,String> settings = new LinkedHashMap<>();
                settings.put("feat062.readiness-file", observation.toString());
                if (role.equals("hotel")) settings.put("feat062.hosted-caller", "true");
                f.start(role, settings);
                assertThat(observation).isRegularFile(); JsonNode before=JSON.readTree(Files.readString(observation));
                if(role.equals("hotel")) {
                    assertThat(before.path("status").asInt()).isEqualTo(503);
                    assertThat(before.path("reason").asText()).isEqualTo("AGENT_NOT_READY");
                    assertThat(before.has("count")).isFalse();
                } else { assertThat(before.path("status").asInt()).isEqualTo(200); assertThat(before.path("count").asInt()).isEqualTo(1); }
                var ready=request("GET",f.wires.get(role).url()+"/feat062/instances",null,Map.of());
                assertThat(ready.statusCode()).isEqualTo(200); assertThat(JSON.readTree(ready.body()).size()).isEqualTo(role.equals("hotel")?2:1);
            }
            f.start("mainplan",Map.of());Feat062RegistrationScenarios.complete(f,"READY_COMPLETE_INVENTORY");
        }
    }
    @Test @Tag("story-feat-062-contract-instance-readonly")
    @Story("FEAT-062.contract.instance-readonly: 注册实例清单只读保真") @DisplayName("Feat-062 注册实例清单只读保真")
    void feat062ContractInstanceReadonly() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.contractHost = true; f.startTravel(false);
            Map<String,String> before = new LinkedHashMap<>();
            for (String role : List.of("trip", "hotel")) {
                var r = request("GET", f.wires.get(role).url() + "/feat062/instances", null, Map.of());
                assertThat(r.statusCode()).isEqualTo(200); before.put(role, r.body());
                JsonNode descriptors = JSON.readTree(r.body());
                assertThat(descriptors.size()).isEqualTo(role.equals("trip") ? 1 : 2);
                for (JsonNode descriptor : descriptors) {
                    String id = descriptor.path("agentId").asText(); String path = descriptor.path("endpointPath").asText();
                    assertThat(path).isEqualTo(role.equals("trip") ? "/a2a" : "/a2a/agents/" + id);
                    String cardBase = role.equals("trip") ? "" : path;
                    JsonNode external = JSON.readTree(request("GET", f.wires.get(role).url() + cardBase + "/.well-known/agent-card.json", null, Map.of()).body());
                    assertThat(descriptor.path("card")).isEqualTo(external);
                }
                f.save("instance-contract-" + role + ".json", descriptors);
            }
            String marker = "READONLY_" + UUID.randomUUID();
            var call = f.send("mainplan", "/a2a", f.prompt(marker), false, marker, null);
            var task = Feat062DirectRegistryReactAgentTest.completed(f, call, "mainplan", "/a2a");
            Feat062DirectRegistryReactAgentTest.assertChain(f, marker, task);
            for (String role : before.keySet()) {
                assertThat(request("GET", f.wires.get(role).url() + "/feat062/instances", null, Map.of()).body()).isEqualTo(before.get(role));
            }
            f.awaitRegistry();
        }
    }
    void unknown(boolean discovery, boolean outage) throws Exception {
        try (var f = new Feat062Fixture(discovery ? "dynamic" : "static")) {
            f.contractHost = true; f.model.live = false;
            if (discovery) {
                f.start("hotel", Map.of()); f.start("trip", Map.of());
                f.awaitRegistry();
                // Isolate the caller's first discovery; registration and heartbeats remain healthy.
                var callerLink = f.new Wire(f.rdcUrl, "registry-mainplan", f.registry.expectedToken);
                callerLink.unavailable = true; f.registryLinks.put("mainplan", callerLink);
            } else {
                f.start("hotel", Map.of()); f.start("trip", Map.of());
            }
            f.start("mainplan", Map.of(A2A + "remote-agents", "", "feat062.unavailable-probe", "true"));
            String name = "travel-trip";
            int before = f.wires.get("trip").requests.size() + f.wires.get("hotel").requests.size();
            var response = request("POST", f.wires.get("mainplan").url() + "/feat062/call",
                    JSON.writeValueAsString(Map.of("agent", name, "message", f.prompt(name))), Map.of("Content-Type", "application/json"));
            f.saveText("unknown-caller-response.json", response.body());
            assertThat(response.statusCode()).as("unavailable target must return explicit failure").isIn(200, 400, 404, 500, 503);
            // The Caller SPI can expose an exception; the documented REMOTE_* domain is produced by the real batch coordinator.
            assertThat(response.body()).containsAnyOf("Unknown remote agent", "REMOTE_UNAVAILABLE");
            String unavailableMarker = "UNAVAILABLE_BUSINESS_" + UUID.randomUUID();
            var unavailableTask = Feat062DirectRegistryReactAgentTest.current(f,
                    f.send("mainplan", "/a2a", f.prompt(unavailableMarker), false, unavailableMarker, null), "mainplan", "/a2a");
            f.save("unavailable-business-task.json", unavailableTask);
            assertThat(f.toolResults("mainplan", unavailableMarker, "feat062-unavailable-probe").toString()).contains("REMOTE_UNAVAILABLE");
            assertThat(f.wires.get("trip").requests.size() + f.wires.get("hotel").requests.size()).isEqualTo(before);
            if (discovery && !outage) {
                f.registryLinks.get("mainplan").unavailable = false;
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).ignoreExceptions().untilAsserted(() -> {
                    var catalog = request("GET", f.wires.get("mainplan").url() + "/feat062/catalog", null, Map.of());
                    assertThat(catalog.body()).contains("travel-trip", "travel-hotel");
                });
                Feat062RegistryExtensionIntegrationTest.awaitCallable(f,"mainplan","travel-trip");
                Feat062RegistryExtensionIntegrationTest.awaitCallable(f,"mainplan","travel-hotel");
                String marker = "RECOVER_" + UUID.randomUUID();
                var task = Feat062DirectRegistryReactAgentTest.completed(f, f.send("mainplan", "/a2a", f.prompt(marker), false, marker, null), "mainplan", "/a2a");
                Feat062DirectRegistryReactAgentTest.assertChain(f, marker, task);
            }
        }
    }
    @Test @Tag("story-feat-062-catalog-startup-empty")
    @Story("FEAT-062.catalog.startup-empty: 纯动态首拉前未知目标显式失败") @DisplayName("Feat-062 纯动态首拉前未知目标显式失败")
    void feat062CatalogStartupEmpty() throws Exception { unknown(true, false); }
    @Test @Tag("story-feat-062-catalog-disabled-unknown")
    @Story("FEAT-062.catalog.disabled-unknown: 未开发现且无静态目标显式失败") @DisplayName("Feat-062 未开发现且无静态目标显式失败")
    void feat062CatalogDisabledUnknown() throws Exception { unknown(false, false); }
    @Test @Tag("story-feat-062-catalog-uncached-no-route")
    @Story("FEAT-062.catalog.uncached-no-route: RDC 不可用且无任何本地信息") @DisplayName("Feat-062 RDC 不可用且无任何本地信息")
    void feat062CatalogUncachedNoRoute() throws Exception { unknown(true, true); }
    @Test @Tag("story-feat-062-contract-architecture")
    @Story("FEAT-062.contract.architecture: Runtime 注册中心依赖边界守护") @DisplayName("Feat-062 Runtime 注册中心依赖边界守护")
    void feat062ContractArchitecture() throws Exception {
        Path m2 = Path.of(System.getenv("SUT_M2_REPO"));
        Path jar = m2.resolve("com/openjiuwen/agent-service-app/0.1.4/agent-service-app-0.1.4.jar");
        Path pom = jar.resolveSibling("agent-service-app-0.1.4.pom");
        assertThat(jar).isRegularFile(); assertThat(pom).isRegularFile();
        List<String> violations = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (var entry : Collections.list(zip.entries())) if (entry.getName().endsWith(".class")) {
                String constants = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of("com/openjiuwen/agent/registry/", "com/alibaba/nacos/", "com/openjiuwen/service/ext/directregistry/"))
                    if (constants.contains(forbidden)) violations.add(entry.getName() + ": " + forbidden);
            }
        }
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var doc = factory.newDocumentBuilder().parse(pom.toFile());
        var dependencies = doc.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            var d = (org.w3c.dom.Element) dependencies.item(i); String artifact = d.getElementsByTagName("artifactId").item(0).getTextContent();
            boolean test = d.getElementsByTagName("scope").getLength() > 0 && d.getElementsByTagName("scope").item(0).getTextContent().equals("test");
            if (!test && (artifact.contains("direct-registry") || artifact.contains("nacos") || artifact.contains("registry-discovery-center"))) violations.add("pom: " + artifact);
        }
        Path evidence = Path.of("target/feature-acceptance-testing/feat062/architecture.json"); Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, JSON.writeValueAsString(Map.of("jar", jar.toString(), "pom", pom.toString(), "violations", violations)));
        assertThat(violations).isEmpty();
    }
}
