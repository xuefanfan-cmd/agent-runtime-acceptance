package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;

@Tag("integration") @Tag("openjiuwen") @Tag("feat-062") @Tag("blackbox")
@Feature("FEAT-062: 直连模式支持 Agent 动态注册和发现")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat062DirectRegistryReactAgentTest {
    Feat062Fixture fixture;
    Feat062Fixture travel(String profile) throws Exception {
        if (fixture != null && !fixture.mode.equals(profile)) { fixture.close(); fixture = null; }
        if (fixture == null) {
            fixture = new Feat062Fixture(profile);
            fixture.startTravel(true);
        }
        return fixture;
    }
    @AfterAll void stop() { if (fixture != null) fixture.close(); }

    void sync(String profile) throws Exception {
        var f = travel(profile); String marker = "SYNC_" + UUID.randomUUID();
        var call = f.send("mainplan", "/a2a", f.prompt(marker), false, marker, null);
        JsonNode task = completed(f, call, "mainplan", "/a2a");
        assertChain(f, marker, task); assertSources(f);
    }
    void stream(String profile) throws Exception {
        var f = travel(profile); String marker = "SSE_" + UUID.randomUUID();
        int tripBefore = f.wires.get("trip").requests.size(), hotelBefore = f.wires.get("hotel").requests.size();
        var call = f.send("mainplan", "/a2a", f.prompt(marker), true, marker, null);
        assertThat(call.contentType()).contains("text/event-stream");
        JsonNode task = completed(f, call, "mainplan", "/a2a");
        assertChain(f, marker, task);
        for (String role : List.of("trip", "hotel")) {
            int before = role.equals("trip") ? tripBefore : hotelBefore;
            List<JsonNode> requests = f.wires.get(role).requests.subList(before, f.wires.get(role).requests.size());
            var methods = requests.stream().map(n -> n.at("/body/method").asText()).filter(n -> n.startsWith("Send")).toList();
            assertThat(methods).as("%s remote protocol preserves streaming capability", role)
                    .isNotEmpty().containsOnly("SendStreamingMessage");
            List<JsonNode> events = new ArrayList<>(); collect(call.frames(), "agentEvent", events);
            assertThat(events).as("%s remote events are projected into the parent SSE", role)
                    .anyMatch(n -> n.at("/target/agentId").asText().equals("travel-" + role)
                            || n.at("/source/agentId").asText().equals("travel-" + role));
        }
    }
    void parentResume(String profile) throws Exception {
        var f = travel(profile); String marker = "RESUME_" + UUID.randomUUID();
        JsonNode paused = unwrap(f.send("mainplan", "/a2a", marker + " 我要去北京出差，日期和差标还没有提供，请先追问。", false, marker, null).task());
        assertThat(state(paused)).isEqualTo("INPUT_REQUIRED");
        var call = f.send("mainplan", "/a2a", f.prompt(marker), false, paused.path("contextId").asText(), paused.path("id").asText());
        JsonNode resumed = completed(f, call, "mainplan", "/a2a");
        assertThat(resumed.path("id").asText()).isEqualTo(paused.path("id").asText());
        assertThat(resumed.path("contextId").asText()).isEqualTo(paused.path("contextId").asText());
        assertChain(f, marker, resumed);
    }
    void hostedAddressing(String profile) throws Exception {
        var f = travel(profile);
        JsonNode listing = JSON.readTree(request("GET", f.wires.get("hotel").url() + "/a2a/agents", null, Map.of()).body());
        Set<String> ids = new HashSet<>(); listing.path("agents").forEach(n -> ids.add(n.asText()));
        assertThat(ids).containsExactlyInAnyOrder("travel-hotel", "travel-hotel-alt");
        for (String id : ids) {
            String endpoint = "/a2a/agents/" + id;
            var card = JSON.readTree(request("GET", f.wires.get("hotel").url() + endpoint + "/.well-known/agent-card.json", null, Map.of()).body());
            assertThat(card.path("name").asText()).isEqualTo(id);
            assertThat(card.path("supportedInterfaces").toString()).contains(f.wires.get("hotel").url() + endpoint);
            String marker = "HOSTED_" + UUID.randomUUID();
            JsonNode task = completed(f, f.send("hotel", endpoint, f.prompt(marker), false, marker, null), "hotel", endpoint);
            assertThat(task.path("artifacts").toString()).contains(f.inventoryCanary);
            assertThat(f.wires.get("hotel").calls(marker)).anyMatch(n -> n.path("path").asText().equals(endpoint));
            assertThat(f.modelRequests("hotel", marker)).anyMatch(n -> n.path("messages").get(0).path("content").asText()
                    .contains("当前酒店实例标识：" + id));
            if (f.dynamicAgents().contains(id)) {
                JsonNode candidates = f.registryJson("GET", "/agents/" + id + "/instances", null);
                assertThat(candidates.path("instances").size()).isEqualTo(1);
                JsonNode route = f.registryJson("POST", "/route-handle:resolve", Map.of("routeHandle", candidates.path("instances").get(0).path("routeHandle").asText()));
                assertThat(route.path("endpointUrl").asText()).isEqualTo(f.wires.get("hotel").url() + endpoint);
            }
        }
    }
    void hostedIsolation(String profile) throws Exception {
        var f = travel(profile); String context = "ISOLATION_" + UUID.randomUUID();
        Map<String, JsonNode> tasks = new LinkedHashMap<>();
        for (String id : List.of("travel-hotel", "travel-hotel-alt")) {
            String endpoint = "/a2a/agents/" + id;
            tasks.put(id, completed(f, f.send("hotel", endpoint, f.prompt(context + id), false, context, null), "hotel", endpoint));
        }
        assertThat(tasks.get("travel-hotel").path("id").asText()).isNotEqualTo(tasks.get("travel-hotel-alt").path("id").asText());
        for (String id : tasks.keySet()) {
            String other = id.equals("travel-hotel") ? "travel-hotel-alt" : "travel-hotel";
            JsonNode task = tasks.get(id);
            var own = f.rpc("hotel", "/a2a/agents/" + id, "GetTask", Map.of("id", task.path("id").asText()));
            assertThat(own.frames().getFirst().has("error")).isFalse();
            var cross = f.rpc("hotel", "/a2a/agents/" + other, "GetTask", Map.of("id", task.path("id").asText()));
            assertThat(cross.frames().getFirst().has("error")).as("cross-hosted task access is rejected").isTrue();
            var continued = f.send("hotel", "/a2a/agents/" + id, f.prompt(context + id) + "请在本实例会话继续查询酒店。", false, context, null);
            JsonNode next = completed(f, continued, "hotel", "/a2a/agents/" + id);
            assertThat(next.path("contextId").asText()).isEqualTo(context);
            assertThat(next.path("artifacts").toString()).contains(id, f.inventoryCanary).doesNotContain("[" + other + "]");
        }
        f.assertHealthyFixture();
    }
    void downstreamResume(String profile) throws Exception {
        // A scripted model supplies the interruption input; Runtime creates and resumes real A2A tasks.
        if (fixture != null) { fixture.close(); fixture = null; }
        var f = new Feat062Fixture(profile); fixture = f;
        f.contractHost = true; f.interruptTrip = true; f.startTravel(false);
        try {
            String marker = "DOWNSTREAM_WAIT_" + UUID.randomUUID();
            var call = f.send("mainplan", "/a2a", f.prompt(marker), true, marker, null);
            JsonNode paused = current(f, call, "mainplan", "/a2a");
            assertThat(state(paused)).isEqualTo("INPUT_REQUIRED");
            List<JsonNode> before = f.wires.get("trip").requests.stream().filter(n -> n.at("/body/method").asText().startsWith("Send")).toList();
            assertThat(before).isNotEmpty();
            var resumedCall = f.send("mainplan", "/a2a", "补充日期2026-11-10至2026-11-12，预算900元。" + marker,
                    true, paused.path("contextId").asText(), paused.path("id").asText());
            JsonNode done = completed(f, resumedCall, "mainplan", "/a2a");
            assertThat(done.path("id").asText()).isEqualTo(paused.path("id").asText());
            assertChain(f, marker, done);
            var tripCalls = f.wires.get("trip").requests.stream().filter(n -> n.at("/body/method").asText().startsWith("Send")
                    && n.path("body").toString().contains(marker)).toList();
            assertThat(tripCalls).hasSize(2);
            assertThat(tripCalls.get(1).at("/body/params/message/taskId").asText()).isNotBlank();
        } finally { f.close(); fixture = null; }
    }
    @Test @Order(1) @Tag("story-feat-062-static-sync")
    @Story("FEAT-062.static.sync: 纯静态：同步双下游闭环") @DisplayName("Feat-062 纯静态：同步双下游闭环")
    void feat062StaticSync() throws Exception { sync("static"); }

    @Test @Order(2) @Tag("story-feat-062-static-stream")
    @Story("FEAT-062.static.stream: 纯静态：SSE 与下游流式兼容") @DisplayName("Feat-062 纯静态：SSE 与下游流式兼容")
    void feat062StaticStream() throws Exception { stream("static"); }

    @Test @Order(3) @Tag("story-feat-062-static-parent-resume")
    @Story("FEAT-062.static.parent-resume: 纯静态：主任务追问恢复") @DisplayName("Feat-062 纯静态：主任务追问恢复")
    void feat062StaticParentResume() throws Exception { parentResume("static"); }

    @Test @Order(4) @Tag("story-feat-062-static-downstream-resume")
    @Story("FEAT-062.static.downstream-resume: 纯静态：远端 INPUT_REQUIRED 恢复") @DisplayName("Feat-062 纯静态：远端 INPUT_REQUIRED 恢复")
    void feat062StaticDownstreamResume() throws Exception { downstreamResume("static"); }

    @Test @Order(5) @Tag("story-feat-062-static-hosted-addressing")
    @Story("FEAT-062.static.hosted-addressing: 纯静态：两 hosted 身份独立寻址") @DisplayName("Feat-062 纯静态：两 hosted 身份独立寻址")
    void feat062StaticHostedAddressing() throws Exception { hostedAddressing("static"); }

    @Test @Order(6) @Tag("story-feat-062-static-hosted-isolation")
    @Story("FEAT-062.static.hosted-isolation: 纯静态：hosted 任务与上下文隔离") @DisplayName("Feat-062 纯静态：hosted 任务与上下文隔离")
    void feat062StaticHostedIsolation() throws Exception { hostedIsolation("static"); }

    @Test @Order(7) @Tag("story-feat-062-dynamic-sync")
    @Story("FEAT-062.dynamic.sync: 纯动态：同步双下游闭环") @DisplayName("Feat-062 纯动态：同步双下游闭环")
    void feat062DynamicSync() throws Exception { sync("dynamic"); }

    @Test @Order(8) @Tag("story-feat-062-dynamic-stream")
    @Story("FEAT-062.dynamic.stream: 纯动态：SSE 与下游流式兼容") @DisplayName("Feat-062 纯动态：SSE 与下游流式兼容")
    void feat062DynamicStream() throws Exception { stream("dynamic"); }

    @Test @Order(9) @Tag("story-feat-062-dynamic-parent-resume")
    @Story("FEAT-062.dynamic.parent-resume: 纯动态：主任务追问恢复") @DisplayName("Feat-062 纯动态：主任务追问恢复")
    void feat062DynamicParentResume() throws Exception { parentResume("dynamic"); }

    @Test @Order(10) @Tag("story-feat-062-dynamic-downstream-resume")
    @Story("FEAT-062.dynamic.downstream-resume: 纯动态：远端 INPUT_REQUIRED 恢复") @DisplayName("Feat-062 纯动态：远端 INPUT_REQUIRED 恢复")
    void feat062DynamicDownstreamResume() throws Exception { downstreamResume("dynamic"); }

    @Test @Order(11) @Tag("story-feat-062-dynamic-hosted-addressing")
    @Story("FEAT-062.dynamic.hosted-addressing: 纯动态：两 hosted 身份独立寻址") @DisplayName("Feat-062 纯动态：两 hosted 身份独立寻址")
    void feat062DynamicHostedAddressing() throws Exception { hostedAddressing("dynamic"); }

    @Test @Order(12) @Tag("story-feat-062-dynamic-hosted-isolation")
    @Story("FEAT-062.dynamic.hosted-isolation: 纯动态：hosted 任务与上下文隔离") @DisplayName("Feat-062 纯动态：hosted 任务与上下文隔离")
    void feat062DynamicHostedIsolation() throws Exception { hostedIsolation("dynamic"); }

    @Test @Order(13) @Tag("story-feat-062-mixed-trip-static-sync")
    @Story("FEAT-062.mixed-trip-static.sync: Trip 静态、Hotel 动态：同步双下游闭环") @DisplayName("Feat-062 Trip 静态、Hotel 动态：同步双下游闭环")
    void feat062MixedTripStaticSync() throws Exception { sync("mixed-trip-static"); }

    @Test @Order(14) @Tag("story-feat-062-mixed-trip-static-stream")
    @Story("FEAT-062.mixed-trip-static.stream: Trip 静态、Hotel 动态：SSE 与下游流式兼容") @DisplayName("Feat-062 Trip 静态、Hotel 动态：SSE 与下游流式兼容")
    void feat062MixedTripStaticStream() throws Exception { stream("mixed-trip-static"); }

    @Test @Order(15) @Tag("story-feat-062-mixed-trip-static-parent-resume")
    @Story("FEAT-062.mixed-trip-static.parent-resume: Trip 静态、Hotel 动态：主任务追问恢复") @DisplayName("Feat-062 Trip 静态、Hotel 动态：主任务追问恢复")
    void feat062MixedTripStaticParentResume() throws Exception { parentResume("mixed-trip-static"); }

    @Test @Order(16) @Tag("story-feat-062-mixed-trip-static-downstream-resume")
    @Story("FEAT-062.mixed-trip-static.downstream-resume: Trip 静态、Hotel 动态：远端 INPUT_REQUIRED 恢复") @DisplayName("Feat-062 Trip 静态、Hotel 动态：远端 INPUT_REQUIRED 恢复")
    void feat062MixedTripStaticDownstreamResume() throws Exception { downstreamResume("mixed-trip-static"); }

    @Test @Order(17) @Tag("story-feat-062-mixed-trip-static-hosted-addressing")
    @Story("FEAT-062.mixed-trip-static.hosted-addressing: Trip 静态、Hotel 动态：两 hosted 身份独立寻址") @DisplayName("Feat-062 Trip 静态、Hotel 动态：两 hosted 身份独立寻址")
    void feat062MixedTripStaticHostedAddressing() throws Exception { hostedAddressing("mixed-trip-static"); }

    @Test @Order(18) @Tag("story-feat-062-mixed-trip-static-hosted-isolation")
    @Story("FEAT-062.mixed-trip-static.hosted-isolation: Trip 静态、Hotel 动态：hosted 任务与上下文隔离") @DisplayName("Feat-062 Trip 静态、Hotel 动态：hosted 任务与上下文隔离")
    void feat062MixedTripStaticHostedIsolation() throws Exception { hostedIsolation("mixed-trip-static"); }

    @Test @Order(19) @Tag("story-feat-062-mixed-hotel-static-sync")
    @Story("FEAT-062.mixed-hotel-static.sync: Trip 动态、Hotel 静态：同步双下游闭环") @DisplayName("Feat-062 Trip 动态、Hotel 静态：同步双下游闭环")
    void feat062MixedHotelStaticSync() throws Exception { sync("mixed-hotel-static"); }

    @Test @Order(20) @Tag("story-feat-062-mixed-hotel-static-stream")
    @Story("FEAT-062.mixed-hotel-static.stream: Trip 动态、Hotel 静态：SSE 与下游流式兼容") @DisplayName("Feat-062 Trip 动态、Hotel 静态：SSE 与下游流式兼容")
    void feat062MixedHotelStaticStream() throws Exception { stream("mixed-hotel-static"); }

    @Test @Order(21) @Tag("story-feat-062-mixed-hotel-static-parent-resume")
    @Story("FEAT-062.mixed-hotel-static.parent-resume: Trip 动态、Hotel 静态：主任务追问恢复") @DisplayName("Feat-062 Trip 动态、Hotel 静态：主任务追问恢复")
    void feat062MixedHotelStaticParentResume() throws Exception { parentResume("mixed-hotel-static"); }

    @Test @Order(22) @Tag("story-feat-062-mixed-hotel-static-downstream-resume")
    @Story("FEAT-062.mixed-hotel-static.downstream-resume: Trip 动态、Hotel 静态：远端 INPUT_REQUIRED 恢复") @DisplayName("Feat-062 Trip 动态、Hotel 静态：远端 INPUT_REQUIRED 恢复")
    void feat062MixedHotelStaticDownstreamResume() throws Exception { downstreamResume("mixed-hotel-static"); }

    @Test @Order(23) @Tag("story-feat-062-mixed-hotel-static-hosted-addressing")
    @Story("FEAT-062.mixed-hotel-static.hosted-addressing: Trip 动态、Hotel 静态：两 hosted 身份独立寻址") @DisplayName("Feat-062 Trip 动态、Hotel 静态：两 hosted 身份独立寻址")
    void feat062MixedHotelStaticHostedAddressing() throws Exception { hostedAddressing("mixed-hotel-static"); }

    @Test @Order(24) @Tag("story-feat-062-mixed-hotel-static-hosted-isolation")
    @Story("FEAT-062.mixed-hotel-static.hosted-isolation: Trip 动态、Hotel 静态：hosted 任务与上下文隔离") @DisplayName("Feat-062 Trip 动态、Hotel 静态：hosted 任务与上下文隔离")
    void feat062MixedHotelStaticHostedIsolation() throws Exception { hostedIsolation("mixed-hotel-static"); }

    static String state(JsonNode task) { return task.at("/status/state").asText().replace("TASK_STATE_", ""); }
    @Test @Order(60) @Tag("story-feat-062-registration-standard-card")
    @Story("FEAT-062.registration.standard-card: 标准进程单卡注册保真") @DisplayName("Feat-062 标准进程单卡注册保真")
    void feat062RegistrationStandardCard() throws Exception { Feat062RegistrationScenarios.standardCard(); }

    @Test @Order(61) @Tag("story-feat-062-registration-hosted-cards")
    @Story("FEAT-062.registration.hosted-cards: hosted 多卡多 endpoint 注册") @DisplayName("Feat-062 hosted 多卡多 endpoint 注册")
    void feat062RegistrationHostedCards() throws Exception { Feat062RegistrationScenarios.hostedCards(); }

    @Test @Order(62) @Tag("story-feat-062-registration-upsert")
    @Story("FEAT-062.registration.upsert: 同键重注册幂等") @DisplayName("Feat-062 同键重注册幂等")
    void feat062RegistrationUpsert() throws Exception { Feat062RegistrationScenarios.upsert(); }

    @Test @Order(63) @Tag("story-feat-062-registration-heartbeat")
    @Story("FEAT-062.registration.heartbeat: 按服务端响应持续心跳保活") @DisplayName("Feat-062 按服务端响应持续心跳保活")
    void feat062RegistrationHeartbeat() throws Exception { Feat062RegistrationScenarios.heartbeat(); }

    @Test @Order(64) @Tag("story-feat-062-registration-heartbeat-not-found")
    @Story("FEAT-062.registration.heartbeat-not-found: 心跳 404 自动重注册") @DisplayName("Feat-062 心跳 404 自动重注册")
    void feat062RegistrationHeartbeatNotFound() throws Exception { Feat062RegistrationScenarios.heartbeatNotFound(); }

    @Test @Order(65) @Tag("story-feat-062-registration-graceful-stop")
    @Story("FEAT-062.registration.graceful-stop: 优雅停机逐实例注销") @DisplayName("Feat-062 优雅停机逐实例注销")
    void feat062RegistrationGracefulStop() throws Exception { Feat062RegistrationScenarios.gracefulStop(); }

    @Test @Order(26) @Tag("story-feat-062-static-contract-retry")
    @Story("FEAT-062.static-contract.retry: 静态首拉失败后按 30s 周期重试") @DisplayName("Feat-062 静态首拉失败后按 30s 周期重试")
    void feat062StaticContractRetry() throws Exception { Feat062StaticScenarios.retry(); }

    @Test @Order(27) @Tag("story-feat-062-static-contract-permanent-cache")
    @Story("FEAT-062.static-contract.permanent-cache: 静态成功卡永久持有") @DisplayName("Feat-062 静态成功卡永久持有")
    void feat062StaticContractPermanentCache() throws Exception { Feat062StaticScenarios.permanentCache(); }

    @Test @Order(28) @Tag("story-feat-062-static-contract-card-url-normalization")
    @Story("FEAT-062.static-contract.card-url-normalization: 静态 well-known 路径与尾斜杠") @DisplayName("Feat-062 静态 well-known 路径与尾斜杠")
    void feat062StaticContractCardUrlNormalization() throws Exception { Feat062StaticScenarios.normalization(); }

    @Test @Order(29) @Tag("story-feat-062-static-contract-invalid-entry")
    @Story("FEAT-062.static-contract.invalid-entry: 静态必填字段与索引诊断") @DisplayName("Feat-062 静态必填字段与索引诊断")
    void feat062StaticContractInvalidEntry() throws Exception { Feat062StaticScenarios.invalidEntry(); }

    @Test @Order(30) @Tag("story-feat-062-static-contract-streaming-disabled")
    @Story("FEAT-062.static-contract.streaming-disabled: 静态 streaming=false 门控") @DisplayName("Feat-062 静态 streaming=false 门控")
    void feat062StaticContractStreamingDisabled() throws Exception { Feat062StaticScenarios.streamingDisabled(); }

    @Test @Order(31) @Tag("story-feat-062-static-contract-address-source")
    @Story("FEAT-062.static-contract.address-source: 静态配置地址与卡声明地址不同时取配置") @DisplayName("Feat-062 静态配置地址与卡声明地址不同时取配置")
    void feat062StaticContractAddressSource() throws Exception { Feat062StaticScenarios.addressSource(); }

    @Test @Order(45) @Tag("story-feat-062-switch-registration-only")
    @Story("FEAT-062.switch.registration-only: 仅开注册、调用侧仍保持静态") @DisplayName("Feat-062 仅开注册、调用侧仍保持静态")
    void feat062SwitchRegistrationOnly() throws Exception { Feat062CatalogScenarios.registrationOnly(); }

    @Test @Order(46) @Tag("story-feat-062-switch-both-enabled")
    @Story("FEAT-062.switch.both-enabled: 同一进程同时注册与发现") @DisplayName("Feat-062 同一进程同时注册与发现")
    void feat062SwitchBothEnabled() throws Exception { Feat062CatalogScenarios.bothEnabled(); }

    @Test @Order(47) @Tag("story-feat-062-catalog-cached-outage")
    @Story("FEAT-062.catalog.cached-outage: RDC 不可用时缓存动态条目继续服务") @DisplayName("Feat-062 RDC 不可用时缓存动态条目继续服务")
    void feat062CatalogCachedOutage() throws Exception { Feat062CatalogScenarios.cachedOutage(); }

    @Test @Order(48) @Tag("story-feat-062-catalog-uncached-static-outage")
    @Story("FEAT-062.catalog.uncached-static-outage: RDC 不可用、无动态缓存时静态兜底") @DisplayName("Feat-062 RDC 不可用、无动态缓存时静态兜底")
    void feat062CatalogUncachedStaticOutage() throws Exception { Feat062CatalogScenarios.uncachedStaticOutage(); }

    @Test @Order(49) @Tag("story-feat-062-catalog-precedence")
    @Story("FEAT-062.catalog.precedence: 同 agentId 动态遮蔽静态") @DisplayName("Feat-062 同 agentId 动态遮蔽静态")
    void feat062CatalogPrecedence() throws Exception { Feat062CatalogScenarios.precedence(false); }

    @Test @Order(50) @Tag("story-feat-062-catalog-empty-no-fallback")
    @Story("FEAT-062.catalog.empty-no-fallback: 升级窗口 200 空候选不回静态") @DisplayName("Feat-062 升级窗口 200 空候选不回静态")
    void feat062CatalogEmptyNoFallback() throws Exception { Feat062CatalogScenarios.precedence(true); }

    @Test @Order(51) @Tag("story-feat-062-catalog-resolve-address")
    @Story("FEAT-062.catalog.resolve-address: 动态调用只取 D4 物理地址") @DisplayName("Feat-062 动态调用只取 D4 物理地址")
    void feat062CatalogResolveAddress() throws Exception { Feat062CatalogScenarios.resolveAddress(); }

    @Test @Order(70) @Tag("story-feat-062-routing-scale-out")
    @Story("FEAT-062.routing.scale-out: 同 agentId 跨进程扩容") @DisplayName("Feat-062 同 agentId 跨进程扩容")
    void feat062RoutingScaleOut() throws Exception { Feat062ReplicaScenarios.scaleOut(); }

    @Test @Order(71) @Tag("story-feat-062-registration-no-cross-delete")
    @Story("FEAT-062.registration.no-cross-delete: 一进程下线不误删同目标另一副本") @DisplayName("Feat-062 一进程下线不误删同目标另一副本")
    void feat062RegistrationNoCrossDelete() throws Exception { Feat062ReplicaScenarios.noCrossDelete(); }

    @Test @Order(72) @Tag("story-feat-062-registration-crash-stop")
    @Story("FEAT-062.registration.crash-stop: 进程崩溃摘除与目录收敛") @DisplayName("Feat-062 进程崩溃摘除与目录收敛")
    void feat062RegistrationCrashStop() throws Exception { Feat062ReplicaScenarios.crashStop(); }

    @Test @Order(73) @Tag("story-feat-062-routing-equal-weight")
    @Story("FEAT-062.routing.equal-weight: 等权首发分布") @DisplayName("Feat-062 等权首发分布")
    void feat062RoutingEqualWeight() throws Exception { Feat062ReplicaScenarios.distribution(false); }

    @Test @Order(74) @Tag("story-feat-062-routing-weighted")
    @Story("FEAT-062.routing.weighted: 3:1 权重首发分布") @DisplayName("Feat-062 3:1 权重首发分布")
    void feat062RoutingWeighted() throws Exception { Feat062ReplicaScenarios.distribution(true); }

    void invalidStartup(String role, Map<String,String> properties, String diagnostic) throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            var result = f.probeStartup(role, properties);
            assertThat(result.exited()).as("invalid configuration must fail startup; %s", result.log()).isTrue();
            assertThat(result.ready()).isFalse(); assertThat(result.exitCode()).isNotZero();
            assertThat(result.diagnostic()).containsIgnoringCase(diagnostic);
        }
    }
    @Test @Order(25) @Tag("story-feat-062-static-contract-default-equivalence")
    @Story("FEAT-062.static-contract.default-equivalence: 默认全关与无扩展静态等价") @DisplayName("Feat-062 默认全关与无扩展静态等价")
    void feat062StaticContractDefaultEquivalence() throws Exception {
        List<Set<String>> contracts = new ArrayList<>();
        for (boolean without : List.of(false, true)) try (var f = new Feat062Fixture("static")) {
            f.noExtension = without; f.startTravel(false);
            Set<String> cards = new TreeSet<>();
            for (String role : List.of("mainplan", "trip", "hotel")) {
                JsonNode card = JSON.readTree(request("GET", f.wires.get(role).url() + "/.well-known/agent-card.json", null, Map.of()).body());
                cards.add(card.path("name").asText() + ":" + card.at("/capabilities/streaming").asBoolean());
            }
            String marker = "DEFAULT_" + UUID.randomUUID();
            var task = completed(f, f.send("mainplan", "/a2a", f.prompt(marker), true, marker, null), "mainplan", "/a2a");
            assertChain(f, marker, task); contracts.add(cards);
            for (String role : List.of("trip", "hotel")) assertThat(f.wires.get(role).calls(marker)).anyMatch(n ->
                    n.at("/body/method").asText().equals("SendStreamingMessage"));
            try (var zip = new java.util.zip.ZipFile(f.jar("mainplan").toFile())) {
                assertThat(Collections.list(zip.entries()).stream().anyMatch(e -> e.getName().contains("agent-service-app-direct-registry"))).isEqualTo(!without);
            }
        }
        assertThat(contracts.get(0)).isEqualTo(contracts.get(1));
    }
    @Test @Order(42) @Tag("story-feat-062-switch-extension-missing")
    @Story("FEAT-062.switch.extension-missing: 发现开启但未部署扩展") @DisplayName("Feat-062 发现开启但未部署扩展")
    void feat062SwitchExtensionMissing() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.noExtension = true;
            try (var zip = new java.util.zip.ZipFile(f.jar("mainplan").toFile())) {
                assertThat(Collections.list(zip.entries()).stream().anyMatch(e -> e.getName().contains("direct-registry"))).isFalse();
            }
            var outcome = f.probeStartup("mainplan", Map.of());
            assertThat(outcome.exited()).as("discovery enabled without extension must fail fast; %s", outcome.log()).isTrue();
            assertThat(outcome.ready()).isFalse(); assertThat(outcome.diagnostic()).containsIgnoringCase("extension");
        }
    }
    @Test @Order(35) @Tag("story-feat-062-switch-invalid-policy")
    @Story("FEAT-062.switch.invalid-policy: 非法取址策略快速失败") @DisplayName("Feat-062 非法取址策略快速失败")
    void feat062SwitchInvalidPolicy() throws Exception {
        invalidStartup("mainplan", Map.of(A2A + "registry.discovery.call-address-policy", "bad-policy"), "call-address-policy");
    }
    @Test @Order(36) @Tag("story-feat-062-switch-ttl-low")
    @Story("FEAT-062.switch.ttl-low: TTL 小于下界拒绝") @DisplayName("Feat-062 TTL 小于下界拒绝")
    void feat062SwitchTtlLow() throws Exception {
        for (String value : List.of("999", "-1")) invalidStartup("mainplan", Map.of(A2A + "remote-catalog.refresh-ttl-ms", value), "refresh-ttl-ms");
    }
    @Test @Order(37) @Tag("story-feat-062-switch-ttl-high")
    @Story("FEAT-062.switch.ttl-high: TTL 大于上界拒绝") @DisplayName("Feat-062 TTL 大于上界拒绝")
    void feat062SwitchTtlHigh() throws Exception { invalidStartup("mainplan", Map.of(A2A + "remote-catalog.refresh-ttl-ms", "30001"), "refresh-ttl-ms"); }
    @Test @Order(39) @Tag("story-feat-062-switch-public-url-missing")
    @Story("FEAT-062.switch.public-url-missing: 注册缺声明地址快速失败") @DisplayName("Feat-062 注册缺声明地址快速失败")
    void feat062SwitchPublicUrlMissing() throws Exception {
        for (String role : List.of("trip", "hotel")) invalidStartup(role, Map.of(A2A + "public-url", ""), "public-url");
    }
    @Test @Order(40) @Tag("story-feat-062-switch-connection-config-missing")
    @Story("FEAT-062.switch.connection-config-missing: 接入参数缺失快速失败") @DisplayName("Feat-062 接入参数缺失快速失败")
    void feat062SwitchConnectionConfigMissing() throws Exception {
        for (String role : List.of("mainplan", "trip")) for (String field : List.of("base-url", "token", "tenant-id"))
            invalidStartup(role, Map.of(A2A + "registry." + field, ""), field);
    }
    @Test @Order(41) @Tag("story-feat-062-switch-registration-address-missing")
    @Story("FEAT-062.switch.registration-address-missing: 注册路由地址必填") @DisplayName("Feat-062 注册路由地址必填")
    void feat062SwitchRegistrationAddressMissing() throws Exception {
        for (String role : List.of("trip", "hotel")) for (String field : List.of("address", "port"))
            invalidStartup(role, Map.of(A2A + "registry.registration." + field, field.equals("port") ? "0" : ""), field);
    }
    @Test @Order(68) @Tag("story-feat-062-registration-startup-unreachable")
    @Story("FEAT-062.registration.startup-unreachable: 注册启动期 RDC 不可达快速失败") @DisplayName("Feat-062 注册启动期 RDC 不可达快速失败")
    void feat062RegistrationStartupUnreachable() throws Exception {
        for (String role : List.of("trip", "hotel")) invalidStartup(role, Map.of(A2A + "registry.base-url", "http://127.0.0.1:" + port()), "register");
    }
    static JsonNode unwrap(JsonNode result) { return result.has("task") ? result.path("task") : result; }
    static JsonNode current(Feat062Fixture f, Call call, String role, String endpoint) throws Exception {
        assertThat(call.status()).isEqualTo(200);
        assertThat(call.frames()).isNotEmpty().noneMatch(n -> n.has("error"));
        JsonNode task = call.task();
        if (!task.path("id").asText().isEmpty()) return unwrap(f.rpc(role, endpoint, "GetTask", Map.of("id", task.path("id").asText())).frames().getFirst().path("result"));
        List<JsonNode> found = new ArrayList<>(); collect(call.frames(), "taskId", found);
        assertThat(found).as("SSE must expose task identity").isNotEmpty();
        return unwrap(f.rpc(role, endpoint, "GetTask", Map.of("id", found.getFirst().asText())).frames().getFirst().path("result"));
    }
    static JsonNode completed(Feat062Fixture f, Call call, String role, String endpoint) throws Exception {
        JsonNode task = current(f, call, role, endpoint);
        assertThat(state(task)).as("real task terminal state").isEqualTo("COMPLETED"); return task;
    }
    static void assertChain(Feat062Fixture f, String marker, JsonNode task) throws Exception {
        Map<String,String> logs = new LinkedHashMap<>();
        for (String role : List.of("mainplan", "trip", "hotel"))
            logs.put(role, String.join("\n", java.nio.file.Files.readAllLines(f.evidence.resolve(role + ".log"))));
        assertChain(f, marker, task, logs);
    }
    static void assertChain(Feat062Fixture f, String marker, JsonNode task, Map<String,String> logs) throws Exception {
        for (String target : List.of("travel-trip", "travel-hotel")) {
            List<JsonNode> results = f.toolResults("mainplan", marker, target);
            assertThat(results).as("%s result fed into parent model", target).isNotEmpty();
            assertThat(results).allSatisfy(n -> assertThat(n.path("content").asText())
                    .isNotBlank().doesNotContain("REMOTE_UNAVAILABLE", "REMOTE_TIMEOUT", "REMOTE_ERROR"));
        }
        assertThat(f.toolResults("mainplan", marker, "travel-hotel").toString()).contains(f.inventoryCanary);
        assertThat(task.path("artifacts").toString()).contains(f.inventoryCanary);
        String parentLog = logs.get("mainplan");
        for (String target : List.of("travel-trip", "travel-hotel")) assertThat(parentLog.lines().filter(s ->
                s.contains("parentTaskId=" + task.path("id").asText()) && s.contains("remoteAgentId=" + target)
                        && s.contains("state=COMPLETED")).count()).as("parent-correlated successful %s invocation", target).isPositive();
        for (String role : List.of("trip", "hotel")) {
            assertThat(logs.get(role).lines()
                    .filter(s -> s.contains("Remote invocation state ") && s.contains("conversationId=" + task.path("contextId").asText())).count())
                    .as("leaf does not delegate onward").isZero();
        }
        f.assertHealthyFixture();
    }
    static void assertSources(Feat062Fixture f) throws Exception {
        if (f.registry == null) return;
        f.awaitRegistry();
        for (String id : List.of("travel-trip", "travel-hotel")) {
            if (f.dynamicAgents().contains(id)) {
                JsonNode candidates = f.registryJson("GET", "/agents/" + id + "/instances", null);
                assertThat(candidates.path("instances")).isNotEmpty();
            } else assertThat(f.registryRequest("GET", "/agents/" + id + "/instances", null).statusCode()).isEqualTo(404);
        }
        assertThat(f.registry.requests).allSatisfy(n -> {
            assertThat(n.path("authorizationMatches").asBoolean()).isTrue(); assertThat(n.path("tenant").asText()).isEqualTo(f.tenant);
        });
    }
    static void collect(Iterable<JsonNode> nodes, String field, List<JsonNode> result) { for (JsonNode node : nodes) collect(node, field, result); }
    static void collect(JsonNode node, String field, List<JsonNode> result) {
        if (node.has(field)) result.add(node.get(field)); if (node.isContainerNode()) node.forEach(n -> collect(n, field, result));
    }

    @Test @Order(100) @Tag("story-feat-062-static-contract-timeout")
    @Story("FEAT-062.static-contract.timeout: 静态 timeoutSeconds 保真") @DisplayName("Feat-062 静态 timeoutSeconds 保真")
    void feat062StaticContractTimeout() throws Exception { Feat062TransportBoundaryScenarios.staticTimeout(); }

    @Test @Order(100) @Tag("story-feat-062-static-contract-tls")
    @Story("FEAT-062.static-contract.tls: 静态 TLS 字段保真") @DisplayName("Feat-062 静态 TLS 字段保真")
    void feat062StaticContractTls() throws Exception { Feat062TransportBoundaryScenarios.tls(); }

    @Test @Order(100) @Tag("story-feat-062-switch-ttl-boundaries")
    @Story("FEAT-062.switch.ttl-boundaries: TTL 合法边界与默认周期") @DisplayName("Feat-062 TTL 合法边界与默认周期")
    void feat062SwitchTtlBoundaries() throws Exception { Feat062DiscoveryBoundaryScenarios.ttlBoundaries(); }

    @Test @Order(100) @Tag("story-feat-062-catalog-startup-fallback")
    @Story("FEAT-062.catalog.startup-fallback: 首拉失败不阻断就绪、有静态兜底") @DisplayName("Feat-062 首拉失败不阻断就绪、有静态兜底")
    void feat062CatalogStartupFallback() throws Exception { Feat062DiscoveryBoundaryScenarios.startupFallback(); }

    @Test @Order(100) @Tag("story-feat-062-catalog-online")
    @Story("FEAT-062.catalog.online: 新下游上线 TTL 内可委派") @DisplayName("Feat-062 新下游上线 TTL 内可委派")
    void feat062CatalogOnline() throws Exception { Feat062DiscoveryBoundaryScenarios.online(); }

    @Test @Order(100) @Tag("story-feat-062-catalog-card-update")
    @Story("FEAT-062.catalog.card-update: 动态卡参数更新") @DisplayName("Feat-062 动态卡参数更新")
    void feat062CatalogCardUpdate() throws Exception { Feat062DiscoveryBoundaryScenarios.cardUpdate(false); }

    @Test @Order(100) @Tag("story-feat-062-catalog-card-delete")
    @Story("FEAT-062.catalog.card-delete: 卡删除传播与 404 语义") @DisplayName("Feat-062 卡删除传播与 404 语义")
    void feat062CatalogCardDelete() throws Exception { Feat062DiscoveryBoundaryScenarios.cardDelete(); }

    @Test @Order(100) @Tag("story-feat-062-catalog-pagination")
    @Story("FEAT-062.catalog.pagination: D3 多页全量发现") @DisplayName("Feat-062 D3 多页全量发现")
    void feat062CatalogPagination() throws Exception { Feat062DiscoveryBoundaryScenarios.pagination(); }

    @Test @Order(100) @Tag("story-feat-062-catalog-not-found-fallback")
    @Story("FEAT-062.catalog.not-found-fallback: D1 404 时静态兜底") @DisplayName("Feat-062 D1 404 时静态兜底")
    void feat062CatalogNotFoundFallback() throws Exception { Feat062DiscoveryBoundaryScenarios.notFoundFallback(); }

    @Test @Order(100) @Tag("story-feat-062-catalog-recovery")
    @Story("FEAT-062.catalog.recovery: 注册发现服务恢复后的目录对齐") @DisplayName("Feat-062 注册发现服务恢复后的目录对齐")
    void feat062CatalogRecovery() throws Exception { Feat062DiscoveryBoundaryScenarios.cardUpdate(true); }

    @Test @Order(100) @Tag("story-feat-062-catalog-resolve-cache")
    @Story("FEAT-062.catalog.resolve-cache: 相同 routeHandle 不重复解析") @DisplayName("Feat-062 相同 routeHandle 不重复解析")
    void feat062CatalogResolveCache() throws Exception { Feat062DiscoveryBoundaryScenarios.resolveCache(); }

    @Test @Order(100) @Tag("story-feat-062-catalog-handle-refresh")
    @Story("FEAT-062.catalog.handle-refresh: 句柄失效后重新发现与解析") @DisplayName("Feat-062 句柄失效后重新发现与解析")
    void feat062CatalogHandleRefresh() throws Exception { Feat062DiscoveryBoundaryScenarios.handleRefresh(); }

    @Test @Order(100) @Tag("story-feat-062-registration-runtime-outage")
    @Story("FEAT-062.registration.runtime-outage: 运行期注册通道断连及重放") @DisplayName("Feat-062 运行期注册通道断连及重放")
    void feat062RegistrationRuntimeOutage() throws Exception { Feat062AuthenticationScenarios.runtimeOutage(false); }

    @Test @Order(100) @Tag("story-feat-062-routing-scale-in")
    @Story("FEAT-062.routing.scale-in: 同 agentId 跨进程缩容") @DisplayName("Feat-062 同 agentId 跨进程缩容")
    void feat062RoutingScaleIn() throws Exception { Feat062TransportBoundaryScenarios.scaleIn(); }

    @Test @Order(100) @Tag("story-feat-062-routing-invalid-weight")
    @Story("FEAT-062.routing.invalid-weight: 权重缺省或非正值的等权降级") @DisplayName("Feat-062 权重缺省或非正值的等权降级")
    void feat062RoutingInvalidWeight() throws Exception { Feat062TransportBoundaryScenarios.invalidWeight(); }

    @Test @Order(100) @Tag("story-feat-062-routing-connection-refused")
    @Story("FEAT-062.routing.connection-refused: 首事件前连接拒绝 failover") @DisplayName("Feat-062 首事件前连接拒绝 failover")
    void feat062RoutingConnectionRefused() throws Exception { Feat062TransportBoundaryScenarios.fault("refused"); }

    @Test @Order(100) @Tag("story-feat-062-routing-request-timeout")
    @Story("FEAT-062.routing.request-timeout: 首事件前超时 failover") @DisplayName("Feat-062 首事件前超时 failover")
    void feat062RoutingRequestTimeout() throws Exception { Feat062TransportBoundaryScenarios.fault("timeout"); }

    @Test @Order(100) @Tag("story-feat-062-routing-all-exhausted")
    @Story("FEAT-062.routing.all-exhausted: 候选全部耗尽结构化失败") @DisplayName("Feat-062 候选全部耗尽结构化失败")
    void feat062RoutingAllExhausted() throws Exception { Feat062TransportBoundaryScenarios.fault("exhausted"); }

    @Test @Order(100) @Tag("story-feat-062-routing-protocol-failure")
    @Story("FEAT-062.routing.protocol-failure: 协议错误不切换候选") @DisplayName("Feat-062 协议错误不切换候选")
    void feat062RoutingProtocolFailure() throws Exception { Feat062TransportBoundaryScenarios.fault("protocol"); }

    @Test @Order(100) @Tag("story-feat-062-routing-business-failure")
    @Story("FEAT-062.routing.business-failure: 业务失败不切换候选") @DisplayName("Feat-062 业务失败不切换候选")
    void feat062RoutingBusinessFailure() throws Exception { Feat062TransportBoundaryScenarios.fault("FAILED"); Feat062TransportBoundaryScenarios.fault("REJECTED"); }

    @Test @Order(100) @Tag("story-feat-062-routing-input-required")
    @Story("FEAT-062.routing.input-required: 等待输入不触发候选切换") @DisplayName("Feat-062 等待输入不触发候选切换")
    void feat062RoutingInputRequired() throws Exception { Feat062TransportBoundaryScenarios.inputRequired(); }

    @Test @Order(100) @Tag("story-feat-062-routing-first-event-lock")
    @Story("FEAT-062.routing.first-event-lock: 远端首事件后断流不得换实例") @DisplayName("Feat-062 远端首事件后断流不得换实例")
    void feat062RoutingFirstEventLock() throws Exception { Feat062TransportBoundaryScenarios.fault("first-event"); }

    @Test @Order(100) @Tag("story-feat-062-isolation-mode-transition")
    @Story("FEAT-062.isolation.mode-transition: 同 namespace 切换模式的残留卡语义") @DisplayName("Feat-062 同 namespace 切换模式的残留卡语义")
    void feat062IsolationModeTransition() throws Exception { Feat062DiscoveryBoundaryScenarios.modeTransition(); }

    @Test @Order(100) @Tag("story-feat-062-auth-headers")
    @Story("FEAT-062.auth.headers: 注册与发现认证头、租户头保真") @DisplayName("Feat-062 注册与发现认证头、租户头保真")
    void feat062AuthHeaders() throws Exception { Feat062AuthenticationScenarios.headers(); }

    @Test @Order(100) @Tag("story-feat-062-auth-unauthenticated")
    @Story("FEAT-062.auth.unauthenticated: 注册鉴权 401 拒绝") @DisplayName("Feat-062 注册鉴权 401 拒绝")
    void feat062AuthUnauthenticated() throws Exception { Feat062AuthenticationScenarios.rejected("401"); }

    @Test @Order(100) @Tag("story-feat-062-auth-tenant-mismatch")
    @Story("FEAT-062.auth.tenant-mismatch: 注册租户越界 403 拒绝") @DisplayName("Feat-062 注册租户越界 403 拒绝")
    void feat062AuthTenantMismatch() throws Exception { Feat062AuthenticationScenarios.rejected("403"); }

    @Test @Order(100) @Tag("story-feat-062-auth-registration-disabled")
    @Story("FEAT-062.auth.registration-disabled: 注册 410 的路径指引") @DisplayName("Feat-062 注册 410 的路径指引")
    void feat062AuthRegistrationDisabled() throws Exception { Feat062AuthenticationScenarios.rejected("410"); }

    @Test @Order(100) @Tag("story-feat-062-auth-rate-limited")
    @Story("FEAT-062.auth.rate-limited: 运行期 429 退避恢复") @DisplayName("Feat-062 运行期 429 退避恢复")
    void feat062AuthRateLimited() throws Exception { Feat062AuthenticationScenarios.runtimeOutage(true); }
}
