package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Feature("FEAT-016: 运行时实例路由查询")
@Tag("feat-016")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteQueryExternalBlackboxTest {
    private BackingServices services;
    private SutStack stack;
    private MockWebServer runtime;
    private AgentBusExternalFixture fixture;

    @BeforeAll
    void startExternalProducts() throws Exception {
        runtime = new MockWebServer();
        runtime.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("/health".equals(request.getPath())) {
                    return new MockResponse().setResponseCode(200).setBody("ok");
                }
                String response = "{\"jsonrpc\":\"2.0\",\"id\":\"stub\",\"result\":{\"task\":{"
                        + "\"id\":\"task-route\",\"contextId\":\"context-route\","
                        + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\","
                        + "\"message\":{\"parts\":[{\"text\":\"route-ok\"}]}}}}}";
                return new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(response);
            }
        });
        runtime.start();

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
    void stopExternalProducts() throws Exception {
        if (stack != null) {
            stack.close();
        }
        if (services != null) {
            services.close();
        }
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-016.query.known-agent-multi-instance: 已知 Agent 多实例查询")
    @Tag("story-feat-016-query-known-agent-multi-instance")
    @DisplayName("Feat-016 已知 Agent 查询返回全部不透明可路由实例")
    void feat016KnownAgentQueryReturnsEveryOpaqueRoutableInstance() throws Exception {
        String agent = "multi-" + UUID.randomUUID();
        fixture.registerRuntime(agent, "service-a-" + agent, runtime.url("/").toString(), "1.0", 200);
        fixture.registerRuntime(agent, "service-b-" + agent, "http://127.0.0.1:19091", "1.0", 100);

        JsonNode candidates = fixture.queryByAgent(agent);
        assertThat(candidates).hasSize(2);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.path("routeHandle").asText()).isNotBlank();
            assertThat(candidate.has("endpointUrl")).isFalse();
            assertThat(candidate.has("instanceId")).isFalse();
        });
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-016.query.availability-and-isolation: 可用性版本与反枚举")
    @Tag("story-feat-016-query-availability-and-isolation")
    @DisplayName("Feat-016 版本和租户过滤不泄漏其他实例")
    void feat016AvailabilityVersionAndTenantSemanticsAreProjectedWithoutEnumeration() throws Exception {
        String agent = "versioned-" + UUID.randomUUID();
        fixture.registerRuntime(agent, "v1-" + agent, runtime.url("/").toString(), "v1", 100);
        fixture.registerRuntime(agent, "v2-" + agent, "http://127.0.0.1:19092", "v2", 90);
        assertThat(fixture.queryByAgent(AgentBusExternalFixture.TENANT, agent, "v1")).hasSize(1)
                .allSatisfy(candidate -> assertThat(candidate.path("contractVersion").asText()).isEqualTo("v1"));
        assertThat(fixture.queryByAgent("other-tenant", agent, null)).isEmpty();
        assertThat(fixture.queryByAgent("other-tenant", "missing", null)).isEmpty();
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-016.failure.explicit-and-recovery: 错误与中心恢复")
    @Tag("story-feat-016-failure-explicit-and-recovery")
    @DisplayName("Feat-016 错误显式且注册中心重启后查询恢复")
    void feat016ErrorsAreExplicitAndQueriesRecoverAfterRegistryRestart() throws Exception {
        String agent = "restart-" + UUID.randomUUID();
        fixture.registerRuntime(agent, "service-" + agent, runtime.url("/").toString());
        assertThat(fixture.resolve("v1:broken", AgentBusExternalFixture.TENANT).statusCode()).isEqualTo(400);

        stack.stop("registry-center");
        stack.start("registry-center");
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(fixture.queryByAgent(agent)).hasSize(1));
    }

    @Test
    @Tag("contract")
    @Story("FEAT-016.resolve.forwarding-contract: 转发层路由引用解析")
    @Tag("story-feat-016-resolve-forwarding-contract")
    @DisplayName("Feat-016 转发解析保持 handle 不透明并按租户隔离")
    void feat016ForwardingResolveKeepsHandleOpaqueAndTenantScoped() throws Exception {
        String agent = "resolve-" + UUID.randomUUID();
        fixture.registerRuntime(agent, "service-" + agent, runtime.url("/").toString());
        String handle = fixture.queryByAgent(agent).get(0).path("routeHandle").asText();
        assertThat(handle).doesNotContain("127.0.0.1", "localhost", "http");
        var resolved = fixture.resolve(handle, AgentBusExternalFixture.TENANT);
        assertThat(resolved.statusCode()).isEqualTo(200);
        assertThat(AgentBusExternalFixture.JSON.readTree(resolved.body()).path("endpointUrl").asText())
                .startsWith("http://localhost:");
        assertThat(fixture.resolve(handle, "other-tenant").statusCode()).isEqualTo(400);
    }

    @Test
    @Tag("blackbox")
    @Story("FEAT-016.gateway.route-consumption: Gateway 消费路由查询")
    @Tag("story-feat-016-gateway-route-consumption")
    @DisplayName("Feat-016 Gateway 查询 RDC 并消费不透明路由")
    void feat016GatewayQueriesRdcAndConsumesOpaqueRoute() throws Exception {
        String agent = "gateway-route-" + UUID.randomUUID();
        fixture.registerRuntime(agent, "service-" + agent, runtime.url("/").toString());
        var response = fixture.direct(agent, "route canary");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("route-ok").doesNotContain("routeHandle", "endpointUrl");
        RecordedRequest forwarded = runtime.takeRequest(5, TimeUnit.SECONDS);
        while (forwarded != null && "/health".equals(forwarded.getPath())) {
            forwarded = runtime.takeRequest(5, TimeUnit.SECONDS);
        }
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/a2a");
    }
}
