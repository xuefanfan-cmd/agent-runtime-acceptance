package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/** FEAT-016 black-box acceptance tests for the external registry-discovery-center JAR. */
@Feature("016")
@Tag("feat-016")
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat016RegistryRouteQueryBlackboxTest {
    private static final String REGISTRY = "registry-center";
    private static final String DB_USER = "agent_rdc";
    private static final String DB_PASSWORD = "agent_rdc";
    private static final AtomicInteger ENDPOINT_PORTS = new AtomicInteger(21000);

    private BackingServices backingServices;
    private SutStack stack;
    private RegistryHttpFixture registry;
    private RegistryStateFixture state;
    private PostgresControl postgres;

    @BeforeAll
    void startRegistry() {
        TestConfig config = TestConfig.load();
        backingServices = new BackingServices(
                config, Set.of("postgres"), new TestContainerFactory(null));
        try {
            stack = SutStack.builder(config)
                    .backingServices(backingServices)
                    .agent(REGISTRY)
                    .start();
            registry = new RegistryHttpFixture(stack.baseUrl(REGISTRY));
            state = new RegistryStateFixture(
                    "jdbc:postgresql://" + backingServices.url("postgres") + "/agent_rdc",
                    DB_USER,
                    DB_PASSWORD);
            postgres = PostgresControl.forMappedPort(mappedPort(backingServices.url("postgres")));
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> assertThat(registry.queryByAgent("ready", "missing", null))
                            .isEmpty());
        } catch (RuntimeException ex) {
            if (stack != null) {
                stack.close();
            }
            backingServices.close();
            throw ex;
        }
    }

    @AfterAll
    void stopRegistry() {
        if (stack != null) {
            stack.close();
        }
        if (backingServices != null) {
            backingServices.close();
        }
    }

    @Nested
    class QueryAndView {
        @Test
        @Tag("blackbox")
        @Story("三维已知目标查询")
        @DisplayName("Feat-016 agentId/serviceId/capability 查询返回精确候选集合")
        void feat016KnownTargetQueriesSupportAgentServiceAndExactCapability() throws Exception {
            String run = shortId();
            String tenant = "tenant-query-" + run;
            String agentA = "agent-a-" + run;
            String agentB = "agent-b-" + run;
            String service = "wealth-svc-" + run;
            String capability = "wealth.purchase." + run;

            EntrySpec a1 = entry(tenant, agentA, service, "1.0", 100, capability, "shared." + run);
            EntrySpec a2 = entry(tenant, agentA, service, "1.0", 90, capability);
            EntrySpec b1 = entry(tenant, agentB, service, "1.0", 80, "shared." + run);
            registry.register(a1);
            registry.register(a2);
            registry.register(b1);

            assertThat(registry.queryByAgent(tenant, agentA, null)).hasSize(2);
            assertThat(registry.queryByService(tenant, service, null)).hasSize(3);
            assertThat(registry.queryByCapability(tenant, capability, null)).hasSize(2);
            assertThat(registry.queryByCapability(tenant, "wealth.purchase", null)).isEmpty();
            assertThat(registry.queryByCapability(tenant, capability.toUpperCase(), null)).isEmpty();
            assertThat(registry.queryByAgent(tenant, "missing-" + run, null)).isEmpty();
        }

        @Test
        @Tag("blackbox")
        @Story("三维统一查询语义")
        @DisplayName("Feat-016 三个查询维度共享过滤、排序和空结果语义")
        void feat016QueryDimensionsShareFilteringOrderingAndEmptySemantics() throws Exception {
            String run = shortId();
            String tenant = "tenant-unified-" + run;
            String agent = "agent-unified-" + run;
            String service = "service-unified-" + run;
            String capability = "unified." + run;
            EntrySpec highV1 = entry(tenant, agent, service, "v1", 300, capability);
            EntrySpec midV1 = entry(tenant, agent, service, "v1", 200, capability);
            EntrySpec lowV2 = entry(tenant, agent, service, "v2", 100, capability);
            EntrySpec offline = entry(tenant, agent, service, "v1", 400, capability);
            for (EntrySpec spec : List.of(highV1, midV1, lowV2, offline)) {
                registry.register(spec);
            }
            state.updateStatus(offline, "OFFLINE");

            List<ArrayNode> unfiltered = List.of(
                    registry.queryByAgent(tenant, agent, null),
                    registry.queryByService(tenant, service, null),
                    registry.queryByCapability(tenant, capability, null));
            List<String> expectedOrder = textValues(unfiltered.get(0), "routeHandle");
            assertThat(expectedOrder).hasSize(3);
            for (ArrayNode result : unfiltered) {
                assertThat(textValues(result, "routeHandle")).containsExactlyElementsOf(expectedOrder);
                assertThat(textValues(result, "health")).doesNotContain("OFFLINE");
            }

            List<ArrayNode> v1Results = List.of(
                    registry.queryByAgent(tenant, agent, "v1"),
                    registry.queryByService(tenant, service, "v1"),
                    registry.queryByCapability(tenant, capability, "v1"));
            List<String> expectedV1Order = textValues(v1Results.get(0), "routeHandle");
            assertThat(expectedV1Order).hasSize(2);
            for (ArrayNode result : v1Results) {
                assertThat(textValues(result, "routeHandle")).containsExactlyElementsOf(expectedV1Order);
                assertThat(textValues(result, "contractVersion")).containsOnly("v1");
            }

            assertThat(registry.queryByAgent(tenant, "missing-" + run, null)).isEmpty();
            assertThat(registry.queryByService(tenant, "missing-" + run, null)).isEmpty();
            assertThat(registry.queryByCapability(tenant, "missing-" + run, null)).isEmpty();
            assertThat(registry.queryByAgent(tenant, agent, "missing-version")).isEmpty();
            assertThat(registry.queryByService(tenant, service, "missing-version")).isEmpty();
            assertThat(registry.queryByCapability(tenant, capability, "missing-version")).isEmpty();
        }

        @Test
        @Tag("blackbox")
        @Story("服务端实例标识")
        @DisplayName("Feat-016 caller 伪造 instanceId 无效且服务端按 endpoint 派生实例标识")
        void feat016ServiceIdIsLogicalAndInstanceIdIsServerDerivedAndOpaque() throws Exception {
            String run = shortId();
            String forgedInstanceId = "forged-instance-" + run;
            EntrySpec spec = entry(
                    "tenant-instance-" + run,
                    "agent-instance-" + run,
                    "logical-service-" + run,
                    "1.0",
                    100,
                    "instance." + run);

            registry.register(spec, Map.of("instanceId", forgedInstanceId));
            JsonNode candidate = registry.queryByAgent(spec.tenantId(), spec.agentId(), null).get(0);
            assertThat(candidate.path("serviceId").asText()).isEqualTo(spec.serviceId());
            assertThat(candidate.has("instanceId")).isFalse();

            JsonNode resolution = registry.json(registry.resolve(
                    candidate.path("routeHandle").asText(), spec.tenantId()).body());
            assertThat(resolution.path("instanceId").asText())
                    .isEqualTo(spec.instanceId())
                    .isNotEqualTo(forgedInstanceId);
        }

        @Test
        @Tag("blackbox")
        @Story("多实例排序与系统视图")
        @DisplayName("Feat-016 多实例按权重排序且查询视图不泄漏物理或 Task 信息")
        void feat016MultiInstanceViewIsSortedAndDoesNotLeakPhysicalOrTaskState() throws Exception {
            String run = shortId();
            String tenant = "tenant-view-" + run;
            String agent = "agent-view-" + run;
            String service = "service-view-" + run;
            EntrySpec low = entry(tenant, agent, service, "1.0", 20, "view." + run);
            EntrySpec high = entry(tenant, agent, service, "1.0", 200, "view." + run);
            registry.register(low);
            registry.register(high);

            ArrayNode result = registry.queryByAgent(tenant, agent, null);
            assertThat(result).hasSize(2);
            assertThat(result.get(0).path("weight").asInt()).isEqualTo(200);
            assertThat(result.get(1).path("weight").asInt()).isEqualTo(20);
            assertThat(result.get(0).path("routeHandle").asText())
                    .startsWith("v2:")
                    .isNotEqualTo(result.get(1).path("routeHandle").asText());

            Set<String> keys = new HashSet<>();
            collectFieldNames(result, keys);
            assertThat(keys).contains(
                    "serviceId", "routeHandle", "health", "contractVersion",
                    "capabilityVersion", "weight", "region", "maxConcurrency",
                    "agentName", "frameworkType");
            assertThat(keys).doesNotContain(
                    "endpointUrl", "routeKey", "instanceId", "topic", "databaseKey",
                    "task", "taskState", "hierarchy", "progress", "contextMemory", "orchestration");
            assertThat(result.toString())
                    .doesNotContain(low.endpointUrl(), high.endpointUrl(), low.routeKey(), high.routeKey());
        }

        @Test
        @Tag("blackbox")
        @Story("元数据和调度边界")
        @DisplayName("Feat-016 registry 返回全部 selection hints 且不成为 payload 或全局调度通道")
        void feat016RouteQueryIsMetadataOnlyAndReturnsHintsWithoutGlobalScheduling() throws Exception {
            String run = shortId();
            String tenant = "tenant-boundary-" + run;
            String agent = "agent-boundary-" + run;
            String service = "service-boundary-" + run;
            EntrySpec east = entry(tenant, agent, service, "1.0", 200, "boundary." + run)
                    .withSelectionHints("cn-east-1", 7);
            EntrySpec west = entry(tenant, agent, service, "1.0", 10, "boundary." + run)
                    .withSelectionHints("cn-west-1", 31);
            registry.register(east, Map.of(
                    "token", "token-canary-" + run,
                    "payload", "payload-canary-" + run,
                    "topic", "topic-canary-" + run,
                    "databaseKey", "database-canary-" + run));
            registry.register(west);

            ArrayNode candidates = registry.queryByAgent(tenant, agent, null);
            assertThat(candidates).hasSize(2);
            assertThat(textValues(candidates, "region")).containsExactly("cn-east-1", "cn-west-1");
            assertThat(intValues(candidates, "maxConcurrency")).containsExactly(7, 31);

            Set<String> fields = new HashSet<>();
            collectFieldNames(candidates, fields);
            assertThat(fields).doesNotContain(
                    "token", "payload", "topic", "databaseKey", "endpointUrl", "instanceId", "routeKey");
            assertThat(candidates.toString()).doesNotContain(
                    "token-canary-" + run, "payload-canary-" + run,
                    "topic-canary-" + run, "database-canary-" + run);

            JsonNode resolved = registry.json(registry.resolve(
                    candidates.get(0).path("routeHandle").asText(), tenant).body());
            Set<String> resolutionFields = new LinkedHashSet<>();
            resolved.fieldNames().forEachRemaining(resolutionFields::add);
            assertThat(resolutionFields)
                    .containsExactlyInAnyOrder("instanceId", "endpointUrl", "routeKey", "contractVersion");
        }

        @Test
        @Tag("blackbox")
        @Story("候选次级排序")
        @DisplayName("Feat-016 同权重实例按 lastHeartbeat 新鲜度排序")
        void feat016EqualWeightCandidatesUseHeartbeatAsStableSecondarySort() throws Exception {
            String run = shortId();
            String tenant = "tenant-heartbeat-" + run;
            String agent = "agent-heartbeat-" + run;
            String service = "service-heartbeat-" + run;
            EntrySpec older = entry(tenant, agent, service, "1.0", 100, "heartbeat." + run);
            EntrySpec fresher = entry(tenant, agent, service, "1.0", 100, "heartbeat." + run);
            registry.register(older);
            registry.register(fresher);
            state.updateHeartbeat(older, "2026-01-01 00:00:00+00");
            state.updateHeartbeat(fresher, "2026-07-20 00:00:00+00");

            ArrayNode result = registry.queryByAgent(tenant, agent, null);
            assertThat(result).hasSize(2);
            JsonNode firstResolution = registry.json(registry.resolve(
                    result.get(0).path("routeHandle").asText(), tenant).body());
            JsonNode secondResolution = registry.json(registry.resolve(
                    result.get(1).path("routeHandle").asText(), tenant).body());
            assertThat(firstResolution.path("endpointUrl").asText()).isEqualTo(fresher.endpointUrl());
            assertThat(secondResolution.path("endpointUrl").asText()).isEqualTo(older.endpointUrl());
        }

        @Test
        @Tag("blackbox")
        @Story("版本与可用性过滤")
        @DisplayName("Feat-016 contractVersion 精确过滤并返回 ONLINE/DEGRADED/DRAINING、排除 OFFLINE")
        void feat016ContractVersionAndHealthFiltersApplyToDiscovery() throws Exception {
            String run = shortId();
            String tenant = "tenant-filter-" + run;
            String agent = "agent-filter-" + run;
            String service = "service-filter-" + run;
            String capability = "filter." + run;
            EntrySpec online = entry(tenant, agent, service, "v1", 100, capability);
            EntrySpec degraded = entry(tenant, agent, service, "v1", 90, capability);
            EntrySpec draining = entry(tenant, agent, service, "v2", 80, capability);
            EntrySpec offline = entry(tenant, agent, service, "v2", 70, capability);
            for (EntrySpec spec : List.of(online, degraded, draining, offline)) {
                registry.register(spec);
            }
            state.updateStatus(degraded, "DEGRADED");
            state.updateStatus(draining, "DRAINING");
            state.updateStatus(offline, "OFFLINE");

            ArrayNode all = registry.queryByService(tenant, service, null);
            assertThat(textValues(all, "health"))
                    .containsExactly("ONLINE", "DEGRADED", "DRAINING");
            assertThat(registry.queryByAgent(tenant, agent, "v1")).hasSize(2);
            assertThat(registry.queryByService(tenant, service, "v2")).hasSize(1);
            assertThat(registry.queryByCapability(tenant, capability, "missing")).isEmpty();
            assertThat(textValues(all, "capabilityVersion")).containsOnly("cap-1");
        }

        @Test
        @Tag("blackbox")
        @Story("重注册状态机")
        @DisplayName("Feat-016 重注册保留 DRAINING 并把 DEGRADED/OFFLINE 重置为 ONLINE")
        void feat016ReregistrationPreservesDrainingAndResetsOtherStatesToOnline() throws Exception {
            String run = shortId();
            String tenant = "tenant-reregister-" + run;
            String agent = "agent-reregister-" + run;
            String service = "service-reregister-" + run;
            EntrySpec draining = entry(tenant, agent, service, "1.0", 300, "reregister." + run);
            EntrySpec degraded = entry(tenant, agent, service, "1.0", 200, "reregister." + run);
            EntrySpec offline = entry(tenant, agent, service, "1.0", 100, "reregister." + run);
            for (EntrySpec spec : List.of(draining, degraded, offline)) {
                registry.register(spec);
            }
            state.updateStatus(draining, "DRAINING");
            state.updateStatus(degraded, "DEGRADED");
            state.updateStatus(offline, "OFFLINE");

            registry.register(draining);
            registry.register(degraded);
            registry.register(offline);

            assertThat(textValues(registry.queryByAgent(tenant, agent, null), "health"))
                    .containsExactly("DRAINING", "ONLINE", "ONLINE");
        }
    }

    @Nested
    class HandleTenantAndErrors {
        @Test
        @Tag("blackbox")
        @Story("路由引用解析与错误")
        @DisplayName("Feat-016 handle 精确解析实例并拒绝跨租户、旧格式和已删除实例")
        void feat016RouteHandleResolvesOneInstanceAndRejectsUnsafeReuse() throws Exception {
            String run = shortId();
            String tenant = "tenant-handle-" + run;
            EntrySpec spec = entry(
                    tenant, "agent-handle-" + run, "service-handle-" + run, "1.5", 100, "handle." + run);
            registry.register(spec);
            String handle = registry.queryByAgent(tenant, spec.agentId(), null)
                    .get(0).path("routeHandle").asText();

            HttpResponse<String> resolved = registry.resolve(handle, tenant);
            assertThat(resolved.statusCode()).isEqualTo(200);
            JsonNode body = registry.json(resolved.body());
            assertThat(body.path("instanceId").asText()).isEqualTo(spec.instanceId());
            assertThat(body.path("endpointUrl").asText()).isEqualTo(spec.endpointUrl());
            assertThat(body.path("routeKey").asText()).isEqualTo(spec.routeKey());
            assertThat(body.path("contractVersion").asText()).isEqualTo("1.5");

            HttpResponse<String> crossTenant = registry.resolve(handle, "tenant-other-" + run);
            assertError(crossTenant, 400, "tenant_isolation_violation");
            assertError(registry.resolve("v1:broken", tenant), 400, "malformed_handle");
            assertError(registry.resolveRequest("{}"), 400, "invalid_request");

            registry.deregister(spec);
            assertError(registry.resolve(handle, tenant), 404, "entry_not_found");
        }

        @Test
        @Tag("blackbox")
        @Story("三维查询 handle 解析")
        @DisplayName("Feat-016 三个查询维度返回的 handle 均解析到同一具体实例")
        void feat016ForwardingLayerResolvesHandlesFromEveryQueryDimension() throws Exception {
            String run = shortId();
            String tenant = "tenant-resolve-dim-" + run;
            String capability = "resolve.dimension." + run;
            EntrySpec spec = entry(
                    tenant, "agent-resolve-dim-" + run, "service-resolve-dim-" + run,
                    "contract-resolve", 100, capability);
            registry.register(spec);

            List<ArrayNode> queryResults = List.of(
                    registry.queryByAgent(tenant, spec.agentId(), null),
                    registry.queryByService(tenant, spec.serviceId(), null),
                    registry.queryByCapability(tenant, capability, null));
            Set<String> handles = new HashSet<>();
            for (ArrayNode result : queryResults) {
                assertThat(result).hasSize(1);
                String handle = result.get(0).path("routeHandle").asText();
                handles.add(handle);
                HttpResponse<String> response = registry.resolve(handle, tenant);
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode resolution = registry.json(response.body());
                assertThat(resolution.path("instanceId").asText()).isEqualTo(spec.instanceId());
                assertThat(resolution.path("endpointUrl").asText()).isEqualTo(spec.endpointUrl());
                assertThat(resolution.path("routeKey").asText()).isEqualTo(spec.routeKey());
                assertThat(resolution.path("contractVersion").asText()).isEqualTo(spec.contractVersion());
            }
            assertThat(handles).containsOnly(queryResults.get(0).get(0).path("routeHandle").asText());
        }

        @Test
        @Tag("blackbox")
        @Story("四字段实例删除")
        @DisplayName("Feat-016 删除一个具体实例不会删除同 agent/service 的兄弟副本")
        void feat016DeletedInstanceHandleDoesNotFallbackToAnotherReplica() throws Exception {
            String run = shortId();
            String tenant = "tenant-delete-" + run;
            String agent = "agent-delete-" + run;
            String service = "service-delete-" + run;
            EntrySpec removed = entry(tenant, agent, service, "1.0", 200, "delete." + run);
            EntrySpec sibling = entry(tenant, agent, service, "1.0", 100, "delete." + run);
            registry.register(removed);
            registry.register(sibling);

            ArrayNode before = registry.queryByAgent(tenant, agent, null);
            String removedHandle = handleForEndpoint(before, tenant, removed.endpointUrl());
            String siblingHandle = handleForEndpoint(before, tenant, sibling.endpointUrl());
            registry.deregister(removed);

            assertError(registry.resolve(removedHandle, tenant), 404, "entry_not_found");
            JsonNode siblingResolution = registry.json(registry.resolve(siblingHandle, tenant).body());
            assertThat(siblingResolution.path("endpointUrl").asText()).isEqualTo(sibling.endpointUrl());
            assertThat(registry.queryByAgent(tenant, agent, null)).hasSize(1);
        }

        @Test
        @Tag("blackbox")
        @Story("路由引用与请求参数校验")
        @DisplayName("Feat-016 畸形 handle 和空白必填参数均在 HTTP 边界 fail-fast")
        void feat016MalformedHandlesAndBlankRequiredParametersFailFast() throws Exception {
            String tenant = "tenant-invalid-" + shortId();
            List<String> malformedHandles = List.of(
                    "not-prefixed",
                    "v1:" + Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)),
                    "v2:",
                    "v2:not-base64",
                    v2Handle("[]"),
                    v2Handle("{}"),
                    v2Handle("{\"tenantId\":\"" + tenant + "\",\"agentId\":7}"),
                    v2Handle("{\"tenantId\":\"" + tenant + "\",\"agentId\":\"a\","
                            + "\"serviceId\":\"s\",\"instanceId\":\"i\",\"routeKey\":\"r\"}"));
            for (String handle : malformedHandles) {
                assertError(registry.resolve(handle, tenant), 400, "malformed_handle");
            }

            for (String body : List.of(
                    "{}",
                    "{\"routeHandle\":\"v2:any\"}",
                    "{\"tenantId\":\"" + tenant + "\"}",
                    "{\"routeHandle\":\"   \",\"tenantId\":\"" + tenant + "\"}",
                    "{\"routeHandle\":\"v2:any\",\"tenantId\":\"   \"}")) {
                assertError(registry.resolveRequest(body), 400, "invalid_request");
            }

            assertError(registry.get("/api/registry/instances/%20/agent"), 400, "invalid_request");
            assertError(registry.get("/api/registry/instances/" + tenant + "/%20"), 400, "invalid_request");
            assertError(registry.get("/api/registry/instances/by-service/%20/service"), 400, "invalid_request");
            assertError(registry.get("/api/registry/instances/by-capability/" + tenant + "/%20"),
                    400, "invalid_request");
            assertThat(registry.get("/api/registry/instances/" + tenant).statusCode()).isEqualTo(404);
        }

        @Test
        @Tag("blackbox")
        @Story("三维查询租户隔离")
        @DisplayName("Feat-016 三个查询维度对相同目标 ID 均保持租户隔离")
        void feat016AllQueryDimensionsRemainTenantScopedForIdenticalTargetIds() throws Exception {
            String run = shortId();
            String tenantA = "tenant-dim-a-" + run;
            String tenantB = "tenant-dim-b-" + run;
            String agent = "agent-dim-shared-" + run;
            String service = "service-dim-shared-" + run;
            String capability = "tenant.dimension." + run;
            EntrySpec entryA = entry(tenantA, agent, service, "tenant-a-v1", 100, capability);
            EntrySpec entryB = entry(tenantB, agent, service, "tenant-b-v1", 100, capability);
            registry.register(entryA);
            registry.register(entryB);

            List<ArrayNode> tenantAResults = List.of(
                    registry.queryByAgent(tenantA, agent, null),
                    registry.queryByService(tenantA, service, null),
                    registry.queryByCapability(tenantA, capability, null));
            List<ArrayNode> tenantBResults = List.of(
                    registry.queryByAgent(tenantB, agent, null),
                    registry.queryByService(tenantB, service, null),
                    registry.queryByCapability(tenantB, capability, null));

            for (int dimension = 0; dimension < tenantAResults.size(); dimension++) {
                ArrayNode resultA = tenantAResults.get(dimension);
                ArrayNode resultB = tenantBResults.get(dimension);
                assertThat(resultA).hasSize(1);
                assertThat(resultB).hasSize(1);
                assertThat(textValues(resultA, "contractVersion")).containsExactly("tenant-a-v1");
                assertThat(textValues(resultB, "contractVersion")).containsExactly("tenant-b-v1");
                assertThat(resultA.get(0).path("routeHandle").asText())
                        .isNotEqualTo(resultB.get(0).path("routeHandle").asText());
            }

            String unknownTenant = "tenant-dim-unknown-" + run;
            assertThat(registry.queryByAgent(unknownTenant, agent, null)).isEmpty();
            assertThat(registry.queryByService(unknownTenant, service, null)).isEmpty();
            assertThat(registry.queryByCapability(unknownTenant, capability, null)).isEmpty();
        }

        @Test
        @Tag("blackbox")
        @Story("租户隔离与反枚举")
        @DisplayName("Feat-016 同名目标按租户隔离且不存在、OFFLINE 均返回空数组")
        void feat016TenantIsolationAndAntiEnumerationProduceNoCrossTenantHints() throws Exception {
            String run = shortId();
            String tenantA = "tenant-a-" + run;
            String tenantB = "tenant-b-" + run;
            String agent = "agent-shared-" + run;
            String service = "service-shared-" + run;
            EntrySpec a = entry(tenantA, agent, service, "tenant-a-version", 100, "shared." + run);
            EntrySpec b = entry(tenantB, agent, service, "tenant-b-version", 100, "shared." + run);
            registry.register(a);
            registry.register(b);

            ArrayNode resultA = registry.queryByAgent(tenantA, agent, null);
            ArrayNode resultB = registry.queryByAgent(tenantB, agent, null);
            assertThat(textValues(resultA, "contractVersion")).containsExactly("tenant-a-version");
            assertThat(textValues(resultB, "contractVersion")).containsExactly("tenant-b-version");
            assertThat(resultA.get(0).path("routeHandle").asText())
                    .isNotEqualTo(resultB.get(0).path("routeHandle").asText());

            state.updateStatus(a, "OFFLINE");
            assertThat(registry.queryByAgent(tenantA, agent, null)).isEmpty();
            assertThat(registry.queryByAgent("unknown-tenant-" + run, agent, null)).isEmpty();
            assertThat(registry.queryByAgent(tenantA, "unknown-agent-" + run, null)).isEmpty();
        }

        @Test
        @Tag("blackbox")
        @Story("权威存储不可用与恢复")
        @DisplayName("Feat-016 PostgreSQL 中断时显式失败且恢复后无需重启 registry")
        void feat016RegistryQueriesRecoverAfterPostgresReturns() throws Exception {
            String run = shortId();
            String tenant = "tenant-db-failure-" + run;
            EntrySpec spec = entry(
                    tenant, "agent-db-failure-" + run, "service-db-failure-" + run,
                    "1.0", 100, "db.failure." + run);
            registry.register(spec);
            String handle = registry.queryByAgent(tenant, spec.agentId(), null)
                    .get(0).path("routeHandle").asText();
            long registryPid = managedRegistry().pid();

            HttpResponse<String> failureResponse = null;
            Throwable transportFailure = null;
            postgres.pause();
            try {
                try {
                    failureResponse = registry.queryResponseByAgent(tenant, spec.agentId(), null);
                } catch (Throwable ex) {
                    transportFailure = ex;
                }
                assertThat(managedRegistry().isAlive()).isTrue();
                assertThat(managedRegistry().pid()).isEqualTo(registryPid);
            } finally {
                postgres.resume();
                state.awaitReady();
            }

            if (failureResponse != null) {
                assertThat(failureResponse.statusCode()).isGreaterThanOrEqualTo(500);
                assertThat(failureResponse.body())
                        .doesNotContain(
                                spec.endpointUrl(), spec.routeKey(), DB_PASSWORD,
                                "jdbc:", "postgresql", "HikariPool", "agent_registry_mvp");
            } else {
                assertThat(transportFailure).isNotNull();
            }
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> assertThat(registry.queryByAgent(tenant, spec.agentId(), null))
                            .hasSize(1));
            assertThat(registry.json(registry.resolve(handle, tenant).body())
                    .path("endpointUrl").asText()).isEqualTo(spec.endpointUrl());
            assertThat(managedRegistry().pid()).isEqualTo(registryPid);
        }

        @Test
        @Tag("blackbox")
        @Story("registry 生命周期")
        @DisplayName("Feat-016 registry 连续三轮重启保持三维查询、解析和连接生命周期稳定")
        void feat016RegistryRestartPreservesRoutesWithoutLeakingProcessesOrConnections() throws Exception {
            String run = shortId();
            String tenant = "tenant-restart-" + run;
            EntrySpec spec = entry(
                    tenant, "agent-restart-" + run, "service-restart-" + run, "1.0", 100, "restart." + run);
            registry.register(spec);
            int baselineConnections = state.applicationConnectionCount();
            Set<Long> pids = new HashSet<>();
            pids.add(managedRegistry().pid());

            for (int cycle = 0; cycle < 3; cycle++) {
                ManagedSutInstance stopped = managedRegistry();
                stack.stop(REGISTRY);
                assertThat(stopped.isAlive()).isFalse();
                assertThat(catchThrowable(() -> registry.queryByAgent(tenant, spec.agentId(), null)))
                        .isNotNull();

                stack.start(REGISTRY);
                registry = new RegistryHttpFixture(stack.baseUrl(REGISTRY));
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                        .untilAsserted(() -> assertThat(registry.queryByAgent(tenant, spec.agentId(), null))
                                .hasSize(1));
                assertThat(registry.queryByService(tenant, spec.serviceId(), null)).hasSize(1);
                assertThat(registry.queryByCapability(tenant, spec.capabilities().get(0), null)).hasSize(1);
                String handle = registry.queryByAgent(tenant, spec.agentId(), null)
                        .get(0).path("routeHandle").asText();
                assertThat(registry.resolve(handle, tenant).statusCode()).isEqualTo(200);
                assertThat(pids.add(managedRegistry().pid())).isTrue();
            }

            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> assertThat(state.applicationConnectionCount())
                            .isLessThanOrEqualTo(baselineConnections + 2));
        }

        @Test
        @Tag("contract")
        @Story("PostgreSQL RLS")
        @DisplayName("Feat-016 restricted role 与 repository set_config 共同提供租户纵深隔离")
        void feat016BoundTenantContextAndDatabaseRlsProvideDefenseInDepth() throws Exception {
            String run = shortId().replace("-", "");
            String tenantA = "tenant-rls-a-" + run;
            String tenantB = "tenant-rls-b-" + run;
            String agent = "agent-rls-" + run;
            registry.register(entry(tenantA, agent, "service-rls", "contract-a", 100, "rls"));
            registry.register(entry(tenantB, agent, "service-rls", "contract-b", 100, "rls"));

            String role = "feat016_rls_" + run;
            String password = "RlsPass_" + run;
            state.createRestrictedRole(role, password);
            try {
                assertThat(state.visibleRowsWithoutTenant(role, password)).isZero();

                DriverManagerDataSource dataSource = new DriverManagerDataSource(state.jdbcUrl(), role, password);
                JdbcAgentRegistryRepository restrictedRepository = new JdbcAgentRegistryRepository(dataSource);
                List<AgentRegistryRepository.RegistryRow> rowsA =
                        restrictedRepository.listByAgentId(tenantA, agent, null);
                List<AgentRegistryRepository.RegistryRow> rowsB =
                        restrictedRepository.listByAgentId(tenantB, agent, null);
                assertThat(rowsA).extracting(AgentRegistryRepository.RegistryRow::contractVersion)
                        .containsExactly("contract-a");
                assertThat(rowsB).extracting(AgentRegistryRepository.RegistryRow::contractVersion)
                        .containsExactly("contract-b");
            } finally {
                state.dropRole(role);
            }
        }

        @Test
        @Tag("blackbox")
        @Story("审计与诊断脱敏")
        @DisplayName("Feat-016 registry 审计包含稳定字段且不记录物理 endpoint 或凭据")
        void feat016AuditLogCarriesObservableFieldsWithoutPhysicalSecrets() throws Exception {
            String run = shortId();
            String traceId = "016" + run.replace("-", "") + "abcdef0123456789";
            EntrySpec spec = entry(
                    "tenant-audit-" + run, "agent-audit-" + run, "service-audit-" + run,
                    "audit-contract", 100, "audit." + run);
            long offset = Files.exists(managedRegistry().logFile())
                    ? Files.size(managedRegistry().logFile()) : 0L;

            registry.register(spec, Map.of(), traceId);

            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> {
                        String appended = readLogAfter(managedRegistry().logFile(), offset);
                        assertThat(appended)
                                .contains("registryOp=register", "traceId=" + traceId,
                                        "tenantId=" + spec.tenantId(), "agentId=" + spec.agentId(),
                                        "contractVersion=audit-contract", "capabilityVersion=cap-1",
                                        "health=ONLINE", "outcome=success", "latencyMs=")
                                .doesNotContain(spec.endpointUrl(), spec.routeKey(), DB_PASSWORD);
                    });
        }

        @Test
        @Disabled("Known FEAT-016 gap: endpoint lookup currently does not filter OFFLINE rows")
        @Tag("known-gap")
        @Story("OFFLINE route handle")
        @DisplayName("Feat-016 OFFLINE 实例的旧 handle 不得继续解析为新路由")
        void feat016OfflineInstanceHandleCannotBeResolvedForNewRouting() throws Exception {
            String run = shortId();
            String tenant = "tenant-offline-" + run;
            EntrySpec spec = entry(
                    tenant, "agent-offline-" + run, "service-offline-" + run, "1.0", 100, "offline." + run);
            registry.register(spec);
            String handle = registry.queryByAgent(tenant, spec.agentId(), null)
                    .get(0).path("routeHandle").asText();
            state.updateStatus(spec, "OFFLINE");

            assertThat(registry.queryByAgent(tenant, spec.agentId(), null)).isEmpty();
            assertError(registry.resolve(handle, tenant), 404, "entry_not_found");
        }
    }

    private static EntrySpec entry(String tenantId, String agentId, String serviceId,
                                   String contractVersion, int weight, String... capabilities) {
        int port = ENDPOINT_PORTS.getAndIncrement();
        return new EntrySpec(
                tenantId,
                agentId,
                serviceId,
                "http://127.0.0.1:" + port,
                "route/" + agentId + "/" + port,
                contractVersion,
                "cap-1",
                weight,
                "cn-east-1",
                10,
                List.of(capabilities));
    }

    private static List<String> textValues(ArrayNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    private static List<Integer> intValues(ArrayNode array, String field) {
        List<Integer> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asInt()));
        return values;
    }

    private String handleForEndpoint(ArrayNode candidates, String tenant, String endpoint) throws Exception {
        for (JsonNode candidate : candidates) {
            String handle = candidate.path("routeHandle").asText();
            if (endpoint.equals(registry.json(registry.resolve(handle, tenant).body())
                    .path("endpointUrl").asText())) {
                return handle;
            }
        }
        throw new AssertionError("No route handle resolved to the expected endpoint");
    }

    private static String v2Handle(String json) {
        return "v2:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static int mappedPort(String hostAndPort) {
        return Integer.parseInt(hostAndPort.substring(hostAndPort.lastIndexOf(':') + 1));
    }

    private static String readLogAfter(java.nio.file.Path path, long offset) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        int start = Math.toIntExact(Math.min(offset, bytes.length));
        return new String(Arrays.copyOfRange(bytes, start, bytes.length), StandardCharsets.UTF_8);
    }

    private static void collectFieldNames(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                names.add(entry.getKey());
                collectFieldNames(entry.getValue(), names);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectFieldNames(child, names));
        }
    }

    private static void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(new ObjectMapper().readTree(response.body()).path("error").asText()).isEqualTo(code);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ManagedSutInstance managedRegistry() {
        assertThat(stack.managedInstance(REGISTRY)).isInstanceOf(ManagedSutInstance.class);
        return (ManagedSutInstance) stack.managedInstance(REGISTRY);
    }

    private record EntrySpec(
            String tenantId,
            String agentId,
            String serviceId,
            String endpointUrl,
            String routeKey,
            String contractVersion,
            String capabilityVersion,
            int weight,
            String region,
            int maxConcurrency,
            List<String> capabilities) {
        String instanceId() {
            URI endpoint = URI.create(endpointUrl);
            return endpoint.getHost() + "-" + endpoint.getPort();
        }

        EntrySpec withSelectionHints(String newRegion, int newMaxConcurrency) {
            return new EntrySpec(
                    tenantId, agentId, serviceId, endpointUrl, routeKey, contractVersion,
                    capabilityVersion, weight, newRegion, newMaxConcurrency, capabilities);
        }
    }

    private static final class RegistryHttpFixture {
        private final String baseUrl;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        private final ObjectMapper mapper = new ObjectMapper();

        RegistryHttpFixture(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        void register(EntrySpec spec) throws Exception {
            register(spec, Map.of(), null);
        }

        void register(EntrySpec spec, Map<String, String> extraFields) throws Exception {
            register(spec, extraFields, null);
        }

        void register(EntrySpec spec, Map<String, String> extraFields, String traceId) throws Exception {
            ObjectNode body = mapper.createObjectNode();
            body.put("tenantId", spec.tenantId());
            body.put("agentId", spec.agentId());
            body.put("serviceId", spec.serviceId());
            body.put("agentName", "acceptance-" + spec.agentId());
            body.put("frameworkType", "JIUWEN");
            body.put("routeKey", spec.routeKey());
            body.put("contractVersion", spec.contractVersion());
            body.put("capabilityVersion", spec.capabilityVersion());
            body.put("endpointUrl", spec.endpointUrl());
            body.put("maxConcurrency", spec.maxConcurrency());
            body.put("weight", spec.weight());
            body.put("region", spec.region());
            ArrayNode caps = body.putArray("capabilities");
            spec.capabilities().forEach(caps::add);
            extraFields.forEach(body::put);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/registry/register"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            if (traceId != null) {
                request.header("X-Trace-Id", traceId);
            }
            HttpResponse<String> response = send(request
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build());
            assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        }

        ArrayNode queryByAgent(String tenantId, String agentId, String contractVersion) throws Exception {
            return query("/api/registry/instances/" + segment(tenantId) + "/" + segment(agentId), contractVersion);
        }

        ArrayNode queryByService(String tenantId, String serviceId, String contractVersion) throws Exception {
            return query("/api/registry/instances/by-service/" + segment(tenantId) + "/" + segment(serviceId),
                    contractVersion);
        }

        ArrayNode queryByCapability(String tenantId, String capability, String contractVersion) throws Exception {
            return query("/api/registry/instances/by-capability/" + segment(tenantId) + "/" + segment(capability),
                    contractVersion);
        }

        HttpResponse<String> queryResponseByAgent(
                String tenantId, String agentId, String contractVersion) throws Exception {
            return queryResponse(
                    "/api/registry/instances/" + segment(tenantId) + "/" + segment(agentId),
                    contractVersion);
        }

        HttpResponse<String> resolve(String handle, String tenantId) throws Exception {
            ObjectNode request = mapper.createObjectNode();
            request.put("routeHandle", handle);
            request.put("tenantId", tenantId);
            return resolveRequest(mapper.writeValueAsString(request));
        }

        HttpResponse<String> resolveRequest(String json) throws Exception {
            return send(HttpRequest.newBuilder(uri("/api/registry/route-handle/resolve"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build());
        }

        void deregister(EntrySpec spec) throws Exception {
            String path = "/api/registry/deregister/" + segment(spec.tenantId())
                    + "/" + segment(spec.agentId())
                    + "/" + segment(spec.serviceId())
                    + "/" + segment(spec.instanceId());
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).DELETE().build());
            assertThat(response.statusCode()).isEqualTo(204);
        }

        JsonNode json(String value) throws Exception {
            return mapper.readTree(value);
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build());
        }

        private ArrayNode query(String path, String contractVersion) throws Exception {
            HttpResponse<String> response = queryResponse(path, contractVersion);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode parsed = mapper.readTree(response.body());
            assertThat(parsed.isArray()).as(response.body()).isTrue();
            return (ArrayNode) parsed;
        }

        private HttpResponse<String> queryResponse(String path, String contractVersion) throws Exception {
            String suffix = contractVersion == null
                    ? "" : "?contractVersion=" + URLEncoder.encode(contractVersion, StandardCharsets.UTF_8);
            return send(HttpRequest.newBuilder(uri(path + suffix))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build());
        }

        private HttpResponse<String> send(HttpRequest request) throws Exception {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create(baseUrl + path);
        }

        private static String segment(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    private static final class RegistryStateFixture {
        private final String jdbcUrl;
        private final String user;
        private final String password;

        RegistryStateFixture(String jdbcUrl, String user, String password) {
            this.jdbcUrl = jdbcUrl;
            this.user = user;
            this.password = password;
        }

        String jdbcUrl() {
            return jdbcUrl;
        }

        void awaitReady() {
            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(250))
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                             Statement statement = connection.createStatement();
                             ResultSet result = statement.executeQuery("SELECT 1")) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.getInt(1)).isEqualTo(1);
                        }
                    });
        }

        int applicationConnectionCount() throws Exception {
            String sql = "SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND pid <> pg_backend_pid()";
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }

        void createRestrictedRole(String role, String rolePassword) throws Exception {
            assertSqlIdentifier(role);
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD '"
                        + rolePassword.replace("'", "''") + "'");
                statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
                statement.execute("GRANT SELECT ON agent_registry_mvp TO " + role);
            }
        }

        int visibleRowsWithoutTenant(String role, String rolePassword) throws Exception {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, role, rolePassword);
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT count(*) FROM agent_registry_mvp")) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }

        void dropRole(String role) throws Exception {
            assertSqlIdentifier(role);
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP OWNED BY " + role);
                statement.execute("DROP ROLE " + role);
            }
        }

        private static void assertSqlIdentifier(String value) {
            if (!value.matches("[a-z][a-z0-9_]{0,62}")) {
                throw new IllegalArgumentException("Unsafe SQL identifier");
            }
        }

        void updateStatus(EntrySpec spec, String status) throws Exception {
            String sql = "UPDATE agent_registry_mvp SET status = ? "
                    + "WHERE tenant_id = ? AND agent_id = ? AND service_id = ? AND instance_id = ?";
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, status);
                statement.setString(2, spec.tenantId());
                statement.setString(3, spec.agentId());
                statement.setString(4, spec.serviceId());
                statement.setString(5, spec.instanceId());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        }

        void updateHeartbeat(EntrySpec spec, String timestamp) throws Exception {
            String sql = "UPDATE agent_registry_mvp SET last_heartbeat = CAST(? AS timestamptz) "
                    + "WHERE tenant_id = ? AND agent_id = ? AND service_id = ? AND instance_id = ?";
            try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password);
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, timestamp);
                statement.setString(2, spec.tenantId());
                statement.setString(3, spec.agentId());
                statement.setString(4, spec.serviceId());
                statement.setString(5, spec.instanceId());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        }
    }

    private static final class PostgresControl {
        private final String containerId;

        private PostgresControl(String containerId) {
            this.containerId = containerId;
        }

        static PostgresControl forMappedPort(int mappedPort) {
            var docker = DockerClientFactory.instance().client();
            return docker.listContainersCmd().exec().stream()
                    .filter(container -> container.getPorts() != null
                            && Arrays.stream(container.getPorts()).anyMatch(port ->
                            port.getPublicPort() != null && port.getPublicPort() == mappedPort))
                    .findFirst()
                    .map(container -> new PostgresControl(container.getId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot find managed PostgreSQL container for mapped port " + mappedPort));
        }

        void pause() {
            DockerClientFactory.instance().client().pauseContainerCmd(containerId).exec();
        }

        void resume() {
            DockerClientFactory.instance().client().unpauseContainerCmd(containerId).exec();
        }
    }
}
