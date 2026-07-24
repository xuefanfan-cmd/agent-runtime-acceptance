package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.controller.InstanceRouteController;
import com.openjiuwen.rdc.health.MvpHealthProbeScheduler;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.EntryNotFoundException;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.InvalidDiscoveryQueryException;
import com.openjiuwen.rdc.model.MalformedRouteHandleException;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.service.AgentDiscoveryService;
import com.openjiuwen.rdc.service.PgMvpDiscoveryServiceImpl;
import com.openjiuwen.rdc.tenant.TenantContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FEAT-016 public Service, DTO, tenant, handle and health-probe contracts. */
@Feature("016")
@Tag("feat-016")
@Tag("integration")
@Tag("contract")
class Feat016RegistryRouteQueryContractTest {
    private static final String TENANT = "tenant-contract";
    private static final String AGENT = "agent-contract";
    private static final String SERVICE = "service-contract";
    private static final String INSTANCE = "127.0.0.1-28080";
    private static final String CAPABILITY = "wealth.purchase";
    private static final String CONTRACT = "1.0";
    private static final String ROUTE_KEY = "route/contract";
    private static final String ENDPOINT = "http://127.0.0.1:28080";

    private FakeRepository repository;
    private TestTenantContext tenantContext;
    private SimpleMeterRegistry meterRegistry;
    private RegistryObservabilityConfig observability;
    private PgMvpDiscoveryServiceImpl discovery;
    private InstanceRouteController controller;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        tenantContext = new TestTenantContext();
        meterRegistry = new SimpleMeterRegistry();
        observability = new RegistryObservabilityConfig(meterRegistry);
        discovery = new PgMvpDiscoveryServiceImpl(repository, tenantContext, observability, null);
        controller = new InstanceRouteController(discovery, repository);
    }

    @AfterEach
    void clearTenant() {
        tenantContext.current = null;
    }

    @Nested
    class DiscoveryContract {
        @Test
        @Story("统一查询 Service")
        @DisplayName("Feat-016 三个公开查询维度共享 DTO、版本和空结果合同")
        void feat016PublicServiceSupportsAllDimensionsAndRichDto() {
            AgentRegistryRepository.RegistryRow row = sampleRow("ONLINE");
            repository.rows = List.of(row);

            List<AgentCardDto> byAgent = discovery.searchInstancesByAgentId(TENANT, AGENT, CONTRACT);
            assertThat(repository.lastQuery).isEqualTo(new Query("agentId", TENANT, AGENT, CONTRACT));
            List<AgentCardDto> byService = discovery.searchByServiceId(TENANT, SERVICE, CONTRACT);
            assertThat(repository.lastQuery).isEqualTo(new Query("serviceId", TENANT, SERVICE, CONTRACT));
            List<AgentCardDto> byCapability = discovery.searchByCapability(TENANT, CAPABILITY, CONTRACT);
            assertThat(repository.lastQuery).isEqualTo(new Query("capability", TENANT, CAPABILITY, CONTRACT));

            for (List<AgentCardDto> result : List.of(byAgent, byService, byCapability)) {
                assertThat(result).hasSize(1);
                AgentCardDto dto = result.get(0);
                assertThat(dto.getServiceId()).isEqualTo(SERVICE);
                assertThat(dto.getRouteHandle()).startsWith("v2:");
                assertThat(dto.getHealth()).isEqualTo("ONLINE");
                assertThat(dto.getContractVersion()).isEqualTo(CONTRACT);
                assertThat(dto.getCapabilityVersion()).isEqualTo("cap-2");
                assertThat(dto.getWeight()).isEqualTo(120);
                assertThat(dto.getRegion()).isEqualTo("cn-east-1");
                assertThat(dto.getMaxConcurrency()).isEqualTo(20);
                assertThat(dto.getFrameworkType()).isEqualTo(FrameworkType.JIUWEN);
            }

            assertThat(discovery.searchInstancesByAgentId(TENANT, "missing", null)).isEmpty();
            assertThat(discovery.searchByServiceId(TENANT, "missing", null)).isEmpty();
            assertThat(discovery.searchByCapability(TENANT, "missing", null)).isEmpty();
            assertThat(meterRegistry.find("agent_bus_registry_op_total")
                    .tag("op", "discover").tag("outcome", "success").counter().count()).isEqualTo(3.0);
            assertThat(meterRegistry.find("agent_bus_registry_op_total")
                    .tag("op", "discover").tag("outcome", "not_found").counter().count()).isEqualTo(3.0);
        }

        @Test
        @Story("公开参数合同")
        @DisplayName("Feat-016 controller 对三个查询维度的空白必填参数 fail-fast")
        void feat016BlankRequiredParametersFailFastWithoutDefaultTenant() {
            assertThatThrownBy(() -> controller.listInstances(" ", AGENT, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listInstances(TENANT, "\t", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listInstancesByService("", SERVICE, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listInstancesByService(TENANT, " ", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listInstancesByCapability(" ", CAPABILITY, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.listInstancesByCapability(TENANT, "\n", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.resolveRouteHandle(
                    new InstanceRouteController.ResolveRequest(" ", TENANT, null), null, null, null))
                    .isInstanceOf(InvalidDiscoveryQueryException.class);
            assertThatThrownBy(() -> controller.resolveRouteHandle(
                    new InstanceRouteController.ResolveRequest("v2:any", " ", null), null, null, null))
                    .isInstanceOf(InvalidDiscoveryQueryException.class);
            assertThat(repository.queryCount).isZero();
        }

        @Test
        @Story("TenantContext 防御")
        @DisplayName("Feat-016 bound TenantContext 不一致时三个查询均拒绝跨租户")
        void feat016BoundTenantContextRejectsEveryCrossTenantQueryDimension() {
            repository.rows = List.of(sampleRow("ONLINE"));
            tenantContext.current = "tenant-other";

            assertThatThrownBy(() -> discovery.searchInstancesByAgentId(TENANT, AGENT, null))
                    .isInstanceOf(TenantIsolationViolationException.class);
            assertThatThrownBy(() -> discovery.searchByServiceId(TENANT, SERVICE, null))
                    .isInstanceOf(TenantIsolationViolationException.class);
            assertThatThrownBy(() -> discovery.searchByCapability(TENANT, CAPABILITY, null))
                    .isInstanceOf(TenantIsolationViolationException.class);
            assertThat(repository.queryCount).isZero();
            assertThat(meterRegistry.find("agent_bus_registry_op_total")
                    .tag("op", "discover")
                    .tag("outcome", "tenant_isolation_violation")
                    .counter().count()).isEqualTo(3.0);
        }
    }

    @Nested
    class RouteHandleContract {
        @Test
        @Story("v2 route handle")
        @DisplayName("Feat-016 v2 handle 恢复四字段实例身份并忽略未来扩展字段")
        void feat016V2HandleResolvesExactInstanceAndIgnoresFutureFields() throws Exception {
            repository.endpoint = Optional.of(
                    new AgentRegistryRepository.EndpointEntry(ENDPOINT, ROUTE_KEY, CONTRACT));
            String handle = handle(Map.of(
                    "tenantId", TENANT,
                    "agentId", AGENT,
                    "serviceId", SERVICE,
                    "instanceId", INSTANCE,
                    "routeKey", ROUTE_KEY,
                    "contractVersion", CONTRACT,
                    "consulAddr", "future.internal",
                    "consulPort", 8500));

            RouteResolution resolution = discovery.resolveRouteHandle(handle, TENANT);

            assertThat(repository.endpointLookup)
                    .isEqualTo(List.of(TENANT, AGENT, SERVICE, INSTANCE));
            assertThat(resolution.instanceId()).isEqualTo(INSTANCE);
            assertThat(resolution.endpointUrl()).isEqualTo(ENDPOINT);
            assertThat(resolution.routeKey()).isEqualTo(ROUTE_KEY);
            assertThat(resolution.contractVersion()).isEqualTo(CONTRACT);
        }

        @Test
        @Story("route handle 错误")
        @DisplayName("Feat-016 畸形、旧格式、跨租户和不存在实例产生稳定异常")
        void feat016HandleContractRejectsMalformedLegacyCrossTenantAndMissingEntry() throws Exception {
            List<String> malformed = List.of(
                    "",
                    "not-prefixed",
                    "v1:" + Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)),
                    "v2:not-base64",
                    handle(Map.of("tenantId", TENANT)));
            for (String required : requiredFields(INSTANCE).keySet()) {
                Map<String, Object> missingOne = new LinkedHashMap<>(requiredFields(INSTANCE));
                missingOne.remove(required);
                malformed = append(malformed, handle(missingOne));
            }
            malformed.forEach(value -> assertThatThrownBy(() -> discovery.resolveRouteHandle(value, TENANT))
                    .isInstanceOf(MalformedRouteHandleException.class));

            String valid = handle(requiredFields(INSTANCE));
            assertThatThrownBy(() -> discovery.resolveRouteHandle(valid, "tenant-other"))
                    .isInstanceOf(TenantIsolationViolationException.class);
            assertThatThrownBy(() -> discovery.resolveRouteHandle(valid, TENANT))
                    .isInstanceOf(EntryNotFoundException.class)
                    .hasMessageContaining("entry not found");
            assertThat(meterRegistry.find("agent_bus_registry_op_total")
                    .tag("op", "resolve").tag("outcome", "malformed_handle")
                    .counter().count()).isEqualTo(malformed.size());
        }
    }

    @Test
    @Story("探活状态转换")
    @DisplayName("Feat-016 探活成功和失败只更新对应具体实例的 ONLINE/DEGRADED 状态")
    void feat016HealthProbeTransitionsEachConcreteInstance() throws Exception {
        try (ProbeServer probe = ProbeServer.start()) {
            repository.probeTargets = List.of(
                    new AgentRegistryRepository.ProbeTarget(
                            TENANT, "agent-ok", SERVICE, "instance-ok", probe.baseUrl() + "/ok"),
                    new AgentRegistryRepository.ProbeTarget(
                            TENANT, "agent-fail", SERVICE, "instance-fail", probe.baseUrl() + "/fail"));
            MvpHealthProbeScheduler scheduler = new MvpHealthProbeScheduler(
                    repository, observability, 50L, 20);

            scheduler.probeOnlineAgents();

            assertThat(repository.scanArguments).hasSize(2);
            assertThat(repository.updates).containsExactly(
                    new AgentRegistryRepository.StatusUpdate(
                            TENANT, "agent-ok", SERVICE, "instance-ok", "ONLINE", true),
                    new AgentRegistryRepository.StatusUpdate(
                            TENANT, "agent-fail", SERVICE, "instance-fail", "DEGRADED", false));
            assertThat(probe.paths).containsExactlyInAnyOrder("/ok/health", "/fail/health");
        }
    }

    @Test
    @Timeout(value = 12, unit = TimeUnit.SECONDS)
    @Story("探活故障窗口")
    @DisplayName("Feat-016 read timeout、connect failure 不阻断后续实例探活且使用配置的 stale/limit")
    void feat016HealthProbeTimeoutAndConnectFailureRemainBoundedPerInstance() throws Exception {
        try (ProbeServer probe = ProbeServer.start()) {
            int refusedPort = closedPort();
            repository.probeTargets = List.of(
                    new AgentRegistryRepository.ProbeTarget(
                            TENANT, "agent-slow", SERVICE, "instance-slow", probe.baseUrl() + "/slow"),
                    new AgentRegistryRepository.ProbeTarget(
                            TENANT, "agent-refused", SERVICE, "instance-refused",
                            "http://127.0.0.1:" + refusedPort),
                    new AgentRegistryRepository.ProbeTarget(
                            TENANT, "agent-after", SERVICE, "instance-after", probe.baseUrl() + "/ok"));
            long staleWindowMs = 1_234L;
            long before = System.currentTimeMillis();
            MvpHealthProbeScheduler scheduler = new MvpHealthProbeScheduler(
                    repository, observability, staleWindowMs, 3);

            long started = System.nanoTime();
            scheduler.probeOnlineAgents();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long after = System.currentTimeMillis();

            assertThat(elapsedMs).isLessThan(8_000L);
            assertThat(repository.scanArguments.get(0))
                    .isBetween(before - staleWindowMs - 250L, after - staleWindowMs + 250L);
            assertThat(repository.scanArguments.get(1)).isEqualTo(3L);
            assertThat(repository.updates).containsExactly(
                    new AgentRegistryRepository.StatusUpdate(
                            TENANT, "agent-slow", SERVICE, "instance-slow", "DEGRADED", false),
                    new AgentRegistryRepository.StatusUpdate(
                            TENANT, "agent-refused", SERVICE, "instance-refused", "DEGRADED", false),
                    new AgentRegistryRepository.StatusUpdate(
                            TENANT, "agent-after", SERVICE, "instance-after", "ONLINE", true));
            assertThat(meterRegistry.find("agent_bus_registry_op_total")
                    .tag("op", "probe").tag("outcome", "probe_failed").counter().count()).isEqualTo(2.0);
            assertThat(meterRegistry.find("agent_bus_registry_op_total")
                    .tag("op", "probe").tag("outcome", "success").counter().count()).isEqualTo(1.0);
        }
    }

    @Test
    @Story("探活调度配置")
    @DisplayName("Feat-016 probe interval 和 stale window 使用外部配置占位符")
    void feat016ProbeIntervalAndStaleWindowAreExternallyConfigurable() throws Exception {
        Method scheduledMethod = MvpHealthProbeScheduler.class.getMethod("probeOnlineAgents");
        assertThat(scheduledMethod.getAnnotation(Scheduled.class).fixedDelayString())
                .isEqualTo("${agent-bus.registry.mvp.probe-interval-ms:5000}");

        Constructor<?> publicConstructor = Arrays.stream(MvpHealthProbeScheduler.class.getConstructors())
                .filter(constructor -> constructor.getParameterCount() == 4)
                .findFirst()
                .orElseThrow();
        Value staleValue = publicConstructor.getParameters()[2].getAnnotation(Value.class);
        assertThat(staleValue).isNotNull();
        assertThat(staleValue.value())
                .isEqualTo("${agent-bus.registry.mvp.probe-stale-before-ms:5000}");
    }

    @Test
    @Disabled("Known FEAT-016 gap: connect/read timeout values are currently hard-coded to 2000 ms")
    @Tag("known-gap")
    @Story("探活超时配置")
    @DisplayName("Feat-016 probe connect/read timeout 应支持外部配置")
    void feat016ProbeConnectAndReadTimeoutsAreExternallyConfigurable() {
        Set<String> configuredKeys = Arrays.stream(MvpHealthProbeScheduler.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameters()))
                .map(parameter -> parameter.getAnnotation(Value.class))
                .filter(java.util.Objects::nonNull)
                .map(Value::value)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(configuredKeys).anyMatch(value -> value.contains("probe-connect-timeout-ms"));
        assertThat(configuredKeys).anyMatch(value -> value.contains("probe-read-timeout-ms"));
    }

    @Test
    @Story("event-bus 边界")
    @DisplayName("Feat-016 registry 查询核心合同不依赖 event-bus 或消息 broker 类型")
    void feat016RegistryQueryContractDoesNotDependOnEventBus() throws Exception {
        for (Class<?> contractType : List.of(
                AgentDiscoveryService.class,
                PgMvpDiscoveryServiceImpl.class,
                JdbcAgentRegistryRepository.class)) {
            String constantPool = new String(classBytes(contractType), StandardCharsets.ISO_8859_1)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(constantPool)
                    .as(contractType.getName())
                    .doesNotContain("eventbus", "event-bus", "kafka", "pulsar", "rocketmq");
        }
    }

    private static AgentRegistryRepository.RegistryRow sampleRow(String status) {
        return new AgentRegistryRepository.RegistryRow(
                SERVICE,
                INSTANCE,
                AGENT,
                "Acceptance Agent",
                FrameworkType.JIUWEN,
                ROUTE_KEY,
                CONTRACT,
                "cap-2",
                120,
                "cn-east-1",
                20,
                status,
                List.of(CAPABILITY));
    }

    private static <T> List<T> append(List<T> source, T value) {
        List<T> copy = new ArrayList<>(source);
        copy.add(value);
        return List.copyOf(copy);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Cannot read class resource " + resource);
            }
            return input.readAllBytes();
        }
    }

    private static Map<String, Object> requiredFields(String instanceId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenantId", TENANT);
        fields.put("agentId", AGENT);
        fields.put("serviceId", SERVICE);
        fields.put("instanceId", instanceId);
        fields.put("routeKey", ROUTE_KEY);
        fields.put("contractVersion", CONTRACT);
        return fields;
    }

    private static String handle(Map<String, ?> fields) throws Exception {
        ObjectNode node = new ObjectMapper().createObjectNode();
        fields.forEach((key, value) -> {
            if (value instanceof Number number) {
                node.put(key, number.longValue());
            } else {
                node.put(key, String.valueOf(value));
            }
        });
        return "v2:" + Base64.getEncoder().encodeToString(
                new ObjectMapper().writeValueAsBytes(node));
    }

    private record Query(String dimension, String tenantId, String value, String contractVersion) {
    }

    private static final class TestTenantContext implements TenantContext {
        private String current;

        @Override
        public String current() {
            return current;
        }
    }

    private static final class FakeRepository implements AgentRegistryRepository {
        private List<RegistryRow> rows = List.of();
        private List<ProbeTarget> probeTargets = List.of();
        private final List<StatusUpdate> updates = new ArrayList<>();
        private Optional<EndpointEntry> endpoint = Optional.empty();
        private Query lastQuery;
        private int queryCount;
        private List<String> endpointLookup = List.of();
        private List<Long> scanArguments = List.of();

        @Override
        public void upsert(AgentRegistryEntry entry, String a2aAgentCardJson) {
            throw new UnsupportedOperationException("not used by this contract fixture");
        }

        @Override
        public boolean delete(String tenantId, String agentId) {
            return false;
        }

        @Override
        public boolean delete(String tenantId, String agentId, String serviceId) {
            return false;
        }

        @Override
        public boolean delete(String tenantId, String agentId, String serviceId, String instanceId) {
            return false;
        }

        @Override
        public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
            scanArguments = List.of(staleBeforeMillis, (long) limit);
            return probeTargets;
        }

        @Override
        public boolean updateStatus(StatusUpdate update) {
            updates.add(update);
            return true;
        }

        @Override
        public List<RegistryRow> listByAgentId(String tenantId, String agentId, String contractVersion) {
            lastQuery = new Query("agentId", tenantId, agentId, contractVersion);
            queryCount++;
            return matching(rows.stream().filter(row -> row.agentId().equals(agentId)).toList(), contractVersion);
        }

        @Override
        public List<RegistryRow> listByServiceId(String tenantId, String serviceId, String contractVersion) {
            lastQuery = new Query("serviceId", tenantId, serviceId, contractVersion);
            queryCount++;
            return matching(rows.stream().filter(row -> row.serviceId().equals(serviceId)).toList(), contractVersion);
        }

        @Override
        public List<RegistryRow> listByCapability(String tenantId, String capability, String contractVersion) {
            lastQuery = new Query("capability", tenantId, capability, contractVersion);
            queryCount++;
            return matching(rows.stream().filter(row -> row.capabilities().contains(capability)).toList(),
                    contractVersion);
        }

        @Override
        public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId,
                                                    String serviceId, String instanceId) {
            endpointLookup = List.of(tenantId, agentId, serviceId, instanceId);
            return endpoint;
        }

        @Override
        public List<DiscoveryRow> queryByTargetSelector(DiscoveryFilter filter) {
            return List.of();
        }

        @Override
        public void reconcileUpsert(ReconcileUpsertCommand command) {
        }

        @Override
        public List<InstanceKey> listInstanceKeysBySource(String sourceId) {
            return List.of();
        }

        @Override
        public void markDraining(String tenantId, String agentId, String serviceId) {
        }

        @Override
        public void markRemoved(String tenantId, String agentId, String serviceId) {
        }

        @Override
        public void markSourceStale(String sourceId) {
        }

        @Override
        public void markSourceFresh(String sourceId) {
        }

        @Override
        public List<InstanceKey> listDrainingPastGrace(java.time.Instant cutoff) {
            return List.of();
        }

        @Override
        public List<InstanceKey> listExpiredLeases(java.time.Instant now) {
            return List.of();
        }

        @Override
        public long getLastProcessedRevision(String sourceId) {
            return 0;
        }

        @Override
        public void updateLastProcessedRevision(String sourceId, long revision) {
        }

        @Override
        public void updateLastProcessedRevision(String sourceId, long revision, String snapshotFingerprint) {
        }

        @Override
        public Optional<String> getSnapshotFingerprint(String sourceId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findCardDigest(String tenantId, String agentId, String serviceId) {
            return Optional.empty();
        }

        @Override
        public void reconcilePending(ReconcilePendingCommand command) {
        }

        @Override
        public void markRefreshDegraded(String tenantId, String agentId, String serviceId) {
        }

        @Override
        public Optional<ResolveRow> findForResolve(String tenantId, String agentId,
                                                   String serviceId, String instanceId) {
            return Optional.empty();
        }

        private static List<RegistryRow> matching(List<RegistryRow> source, String contractVersion) {
            if (contractVersion == null) {
                return List.copyOf(source);
            }
            return source.stream().filter(row -> contractVersion.equals(row.contractVersion())).toList();
        }
    }

    private static final class ProbeServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());

        private ProbeServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static ProbeServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            ProbeServer fixture = new ProbeServer(server, executor);
            server.setExecutor(executor);
            server.createContext("/ok/health", exchange -> fixture.respond(exchange, 204));
            server.createContext("/fail/health", exchange -> fixture.respond(exchange, 503));
            server.createContext("/slow/health", fixture::respondSlowly);
            server.start();
            return fixture;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void respond(HttpExchange exchange, int status) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        private void respondSlowly(HttpExchange exchange) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(Duration.ofSeconds(10));
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
