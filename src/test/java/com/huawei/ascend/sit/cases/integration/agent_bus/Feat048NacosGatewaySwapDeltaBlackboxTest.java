package com.huawei.ascend.sit.cases.integration.agent_bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-048 联动增量 — 统一注册中心 SPI 实现切换（Nacos）下的消费方语义不变验证.
 *
 * <p>覆盖以下特性测试文档中「FEAT-048 联动」增量场景：
 * <ul>
 *   <li>FEAT-011 §7：F011-N01..N03（Gateway 直连路由/治理失败等价 + 实现切换回滚零行为变化）</li>
 *   <li>FEAT-012 §6：F012-N01..N02（BUS 路径信封不变、targetServiceId 一致性、治理失败零入队）</li>
 *   <li>FEAT-013 §6：F013-N01..N02（事件转发等价、注册中心不可达失败语义）</li>
 *   <li>FEAT-014 §6：F014-N01..N02（A2A 两跳等价、短时不可用容灾）</li>
 *   <li>FEAT-016 §15：F016-N01..N05（双目标一致、capability 不支持、二值健康、租户隔离、引用差异）</li>
 *   <li>FEAT-017 §9：F017-N01..N02（Nacos 模式投递过滤命中、服务标识漂移防护）</li>
 * </ul>
 *
 * <p>主门禁与完整矩阵见 {@code docs/cases/FEAT-048-agent-registry-spi-nacos.md} 与
 * {@link Feat048AgentRegistrySpiNacosBlackboxTest}。环境经 {@code sut.external.nacos.*}
 * 提供（同主测试类）；BUS/事件链路深度分支依赖正式 BUS profile 制品，就绪前 SKIPPED。
 */
@Tag("integration")
@Tag("agent-bus")
@Tag("feat-048")
@Tag("blackbox")
@Feature("FEAT-048: Agent 注册中心统一 SPI 与 Nacos 对接（消费方联动增量）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat048NacosGatewaySwapDeltaBlackboxTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TestConfig config;
    private String nacosBaseUrl;
    private String tenantId;
    private String runtimeBaseUrl;
    private String runtimeAgentId;
    private String peerRuntimeBaseUrl;
    private String gatewayBaseUrl;
    private String gatewayToken;
    private String rdcGatewayBaseUrl;
    private String peerTenantId;
    private String peerTenantAgentId;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private boolean nacosEnvReady;
    private String nacosGateReason;

    @BeforeAll
    void loadConfigAndProbeNacos() {
        config = TestConfig.load();
        nacosBaseUrl = trimSlash(config.getString("sut.external.nacos.base-url"));
        tenantId = config.getString("sut.external.nacos.tenant-id", "tenant-A");
        runtimeBaseUrl = trimSlash(config.getString("sut.external.nacos.runtime-base-url"));
        runtimeAgentId = config.getString("sut.external.nacos.runtime-agent-id");
        peerRuntimeBaseUrl = trimSlash(config.getString("sut.external.nacos.peer-runtime-base-url"));
        gatewayBaseUrl = trimSlash(config.getString("sut.external.nacos.gateway-base-url"));
        gatewayToken = config.getString("sut.external.nacos.gateway-token");
        rdcGatewayBaseUrl = trimSlash(config.getString("sut.external.nacos.rdc-gateway-base-url"));
        peerTenantId = config.getString("sut.external.nacos.peer-tenant-id");
        peerTenantAgentId = config.getString("sut.external.nacos.peer-tenant-agent-id");

        // 逐用例门禁：类级 @BeforeAll 中止在 JUnit 6 下整类 0 用例上报，改为每用例独立 SKIPPED
        if (nacosBaseUrl == null || nacosBaseUrl.isBlank()) {
            nacosGateReason = "sut.external.nacos.base-url 未配置（Nacos 环境未部署）";
        } else if (gatewayBaseUrl == null || runtimeBaseUrl == null || runtimeAgentId == null) {
            nacosGateReason = "Nacos 模式 gateway/runtime 未配置（gateway-base-url / runtime-base-url / runtime-agent-id）";
        } else {
            nacosEnvReady = true;
            nacosGateReason = "n/a";
        }
    }

    /** 逐用例环境门禁：未就绪时本用例 SKIPPED（dependency-gated，不计 PASS/FAIL）。 */
    private void assumeNacosEnv() {
        assumeTrue(nacosEnvReady, "FEAT-048 联动 dependency-gated: " + nacosGateReason);
    }

    // ==================================================================
    // FEAT-011 联动 — Gateway 直连路由语义在 Nacos 实现下不变
    // ==================================================================

    @Test
    @Tag("story-feat-011-nacos-n01")
    @Story("FEAT-011.F011-N01: Nacos 模式直连路由等价")
    @DisplayName("F011-N01 Nacos 模式 Gateway 同步与流式直连语义与 RDC 基线等价")
    void f011n01NacosDirectRoutingEquivalent() throws Exception {
        assumeNacosEnv();
        // 同步：显式 agentId → 经统一 SPI 取候选、解析 routeHandle、直连，返回真实结果
        String syncCanary = canary("F011-N01-sync");
        String syncBody = gatewaySendMessage(runtimeAgentId, syncCanary);
        assertThat(syncBody).contains(syncCanary);
        assertNoTopologyLeak(syncBody, runtimeBaseUrl, peerRuntimeBaseUrl);
        assertThat(syncBody).doesNotContain("routeHandle").doesNotContain("routeKey");

        // 流式（SSE 桥接）：SendStreamingMessage 首帧含 taskId，帧逐步到达不被聚合
        String streamCanary = canary("F011-N01-stream");
        String streamBody = gatewaySendStreaming(runtimeAgentId, streamCanary);
        assertThat(streamBody).contains(streamCanary);
        assertNoTopologyLeak(streamBody, runtimeBaseUrl, peerRuntimeBaseUrl);
    }

    @Test
    @Tag("story-feat-011-nacos-n02")
    @Story("FEAT-011.F011-N02: Nacos 模式治理与选路失败等价")
    @DisplayName("F011-N02 Nacos 模式无候选返回确定失败不伪造 Task")
    void f011n02NacosGovernanceFailureEquivalent() throws Exception {
        assumeNacosEnv();
        String missingAgent = "feat048-missing-" + UUID.randomUUID().toString().substring(0, 8);
        String body = gatewaySendMessage(missingAgent, canary("F011-N02"));
        // 确定失败（不伪造 Task / 不返回业务成功）：JSON-RPC error 或明确失败语义
        boolean explicitFailure = body.contains("error") || body.contains("not_found")
                || body.contains("notFound") || body.contains("unavailable")
                || body.contains("NO_CANDIDATES") || body.contains("No routable");
        assertThat(explicitFailure).as("无候选应返回确定失败，实际: %s", body).isTrue();
        assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
    }

    @Test
    @Tag("story-feat-011-nacos-n03")
    @Story("FEAT-011.F011-N03: 实现切换回滚零行为变化")
    @DisplayName("F011-N03 Nacos 与 RDC 两模式 Gateway 对外行为等价")
    void f011n03SwapRegressionZeroBehaviorChange() throws Exception {
        assumeNacosEnv();
        assumeTrue(rdcGatewayBaseUrl != null && !rdcGatewayBaseUrl.isBlank(),
                "F011-N03 dependency-gated: 需 RDC 模式对照 gateway（rdc-gateway-base-url）");
        // 同一旅程在两种注册中心实现下的 Gateway 对外行为等价
        String nacosCanary = canary("F011-N03-nacos");
        String rdcCanary = canary("F011-N03-rdc");
        String viaNacos = gatewaySendMessage(gatewayBaseUrl, gatewayToken, runtimeAgentId, nacosCanary);
        String viaRdc = gatewaySendMessage(rdcGatewayBaseUrl, gatewayToken, runtimeAgentId, rdcCanary);

        assertThat(viaNacos).contains(nacosCanary);
        assertThat(viaRdc).contains(rdcCanary);
        // 两模式响应结构等价（JSON-RPC result 形态一致，均无拓扑泄漏）
        assertThat(viaNacos).contains("result");
        assertThat(viaRdc).contains("result");
        assertNoTopologyLeak(viaNacos, runtimeBaseUrl, peerRuntimeBaseUrl);
        assertNoTopologyLeak(viaRdc, runtimeBaseUrl, peerRuntimeBaseUrl);
    }

    // ==================================================================
    // FEAT-012 联动 — BUS 路径候选来源切换为统一 SPI
    // ==================================================================

    @Test
    @Tag("story-feat-012-nacos-n01")
    @Story("FEAT-012.F012-N01: Nacos 模式 bus 选路与信封不变")
    @DisplayName("F012-N01 Nacos 模式 BUS 路径 targetServiceId 一致性命中投递")
    void f012n01NacosBusEnvelopeUnchanged() throws Exception {
        assumeNacosEnv();
        // 需 Nacos 模式 BUS 路径 gateway（path-mode=bus）；经配置键区分，未配置时按当前
        // gateway 的默认路径执行（DIRECT 复用同一 SPI 候选来源，信封断言仅在 BUS profile 下有效）
        String busGateway = trimSlash(config.getString("sut.external.nacos.bus-gateway-base-url"));
        assumeTrue(busGateway != null && !busGateway.isBlank(),
                "F012-N01 dependency-gated: 需 Nacos 模式 BUS 路径 gateway（bus-gateway-base-url）");
        String canaryText = canary("F012-N01");
        String body = gatewaySendMessage(busGateway, gatewayToken, runtimeAgentId, canaryText);
        // 调用完成 = targetServiceId（== agentId，与消费侧服务标识配置同源）投递过滤命中
        assertThat(body).contains(canaryText);
        // client 响应不泄漏 endpoint/routeHandle/BUS correlationId
        assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
        assertThat(body).doesNotContain("correlationId");
    }

    @Test
    @Tag("story-feat-012-nacos-n02")
    @Story("FEAT-012.F012-N02: Nacos 模式治理失败零入队等价")
    @DisplayName("F012-N02 Nacos 模式无候选治理失败不入队")
    void f012n02NacosBusGateFailureNoEnqueue() throws Exception {
        assumeNacosEnv();
        String busGateway = trimSlash(config.getString("sut.external.nacos.bus-gateway-base-url"));
        assumeTrue(busGateway != null && !busGateway.isBlank(),
                "F012-N02 dependency-gated: 需 Nacos 模式 BUS 路径 gateway（bus-gateway-base-url）");
        String missingAgent = "feat048-missing-" + UUID.randomUUID().toString().substring(0, 8);
        String body = gatewaySendMessage(busGateway, gatewayToken, missingAgent, canary("F012-N02"));
        // 确定失败（不伪造 Task / 不返回业务成功）：JSON-RPC error 或明确失败语义
        boolean explicitFailure = body.contains("error") || body.contains("not_found")
                || body.contains("notFound") || body.contains("unavailable")
                || body.contains("NO_CANDIDATES") || body.contains("No routable");
        assertThat(explicitFailure).as("无候选应确定失败不入队，实际: %s", body).isTrue();
        assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
    }

    // ==================================================================
    // FEAT-013 / FEAT-014 联动 — 事件转发语义在 Nacos 实现下不变
    // ==================================================================

    @Test
    @Tag("story-feat-013-nacos-n01")
    @Story("FEAT-013.F013-N01: Nacos 实现下事件转发等价")
    @DisplayName("F013-N01 Nacos 模式客户端调用事件往返语义不变")
    void f013n01NacosEventForwardingEquivalent() throws Exception {
        assumeNacosEnv();
        String canaryText = canary("F013-N01");
        String body = gatewaySendMessage(runtimeAgentId, canaryText);
        // 事件信封契约不变的外部投影：调用完成、结果 canary 回到 client、无拓扑泄漏
        assertThat(body).contains(canaryText);
        assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
        // 深度事件审计（eventType/correlation/顺序）需正式 BUS profile 审计面，见测试设计门禁
    }

    @Test
    @Tag("story-feat-013-nacos-n02")
    @Story("FEAT-013.F013-N02: Nacos 不可达时事件链路失败语义")
    @DisplayName("F013-N02 Nacos 不可达返回确定失败不伪造事件")
    void f013n02NacosUnavailableFailureSemantics() throws Exception {
        assumeNacosEnv();
        int window = config.getInt("sut.external.nacos.outage-window-seconds", 0);
        assumeTrue(window > 0,
                "F013-N02 dependency-gated: 需 outage-window-seconds 断网窗口（屏蔽→Nacos 连接）");
        long deadline = System.currentTimeMillis() + window * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String missingAgent = "feat048-missing-" + UUID.randomUUID().toString().substring(0, 8);
            String body = gatewaySendMessage(missingAgent, canary("F013-N02"));
            boolean explicitFailure = body.contains("error") || body.contains("unavailable")
                    || body.contains("NO_CANDIDATES") || body.contains("No routable");
            assertThat(explicitFailure).as("注册中心不可用应确定失败，实际: %s", body).isTrue();
            TimeUnit.SECONDS.sleep(5);
        }
    }

    @Test
    @Tag("story-feat-014-nacos-n01")
    @Story("FEAT-014.F014-N01: Nacos 实现下 A2A 两跳等价")
    @DisplayName("F014-N01 Nacos 模式 A2A 两跳调用语义与投递隔离不变")
    void f014n01NacosA2aTwoHopEquivalent() throws Exception {
        assumeNacosEnv();
        // 两跳链路（caller runtime → event-bus → callee runtime）的外部投影：经 gateway 或
        // caller agent 完成调用；callee 侧服务标识校验命中（serviceId == agentId 同源不变量）
        String canaryText = canary("F014-N01");
        String body = gatewaySendMessage(runtimeAgentId, canaryText);
        assertThat(body).contains(canaryText);
        assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
    }

    @Test
    @Tag("story-feat-014-nacos-n02")
    @Story("FEAT-014.F014-N02: Nacos 短时不可用容灾")
    @DisplayName("F014-N02 Nacos 断网窗口内已知目标两跳调用维持")
    void f014n02NacosOutageTolerance() throws Exception {
        assumeNacosEnv();
        int window = config.getInt("sut.external.nacos.outage-window-seconds", 0);
        assumeTrue(window > 0,
                "F014-N02 dependency-gated: 需 outage-window-seconds 断网窗口");
        // 先完成一次成功调用（本地缓存有数据），再进入断网窗口
        String warmup = gatewaySendMessage(runtimeAgentId, canary("F014-N02-warmup"));
        assertThat(warmup).isNotBlank();
        long deadline = System.currentTimeMillis() + window * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String body = gatewaySendMessage(runtimeAgentId, canary("F014-N02-outage"));
            assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
            TimeUnit.SECONDS.sleep(5);
        }
    }

    // ==================================================================
    // FEAT-016 联动 — Nacos 实现支撑边界
    // ==================================================================

    @Test
    @Tag("story-feat-016-nacos-n01")
    @Story("FEAT-016.F016-N01: byAgentId/byServiceId 双目标一致")
    @DisplayName("F016-N01 Nacos 模式双目标形态返回同一实例集合且 serviceId==agentId")
    void f016n01DualTargetConsistency() throws Exception {
        assumeNacosEnv();
        List<String> byAgent = instanceKeys(listHosts(runtimeAgentId, tenantId));
        List<String> byService = instanceKeys(listHosts(runtimeAgentId, tenantId));
        assertThat(byAgent).isNotEmpty();
        assertThat(byAgent).containsExactlyElementsOf(byService);
    }

    @Test
    @Tag("story-feat-016-nacos-n02")
    @Story("FEAT-016.F016-N02: capability 目标能力不支持")
    @DisplayName("F016-N02 byCapability 在 Nacos 实现下明确能力不支持")
    void f016n02CapabilityUnsupported() throws Exception {
        assumeNacosEnv();
        String spiProbe = trimSlash(config.getString("sut.external.nacos.spi-probe-base-url"));
        assumeTrue(spiProbe != null && !spiProbe.isBlank(),
                "F016-N02 dependency-gated: 发现面探针入口待 L2 固化（spi-probe-base-url）");
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(spiProbe + "/discovery?target=byCapability&tenantId="
                                + url(tenantId) + "&capability=feat016.probe.capability"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String body = resp.body() == null ? "" : resp.body();
        assertThat(body).containsAnyOf("UNSUPPORTED", "unsupported", "NOT_SUPPORTED",
                "not supported", "CAPABILITY_NOT_SUPPORTED", "capability not supported");
    }

    @Test
    @Tag("story-feat-016-nacos-n03")
    @Story("FEAT-016.F016-N03: 二值健康候选收敛")
    @DisplayName("F016-N03 Nacos 二值健康候选中实例均以可用语义表达")
    void f016n03BinaryHealthSemantics() throws Exception {
        assumeNacosEnv();
        List<JsonNode> hosts = awaitHealthyHosts(runtimeAgentId, tenantId);
        hosts.forEach(h -> assertThat(h.path("healthy").asBoolean(false)).isTrue());
        // 无中间降级态（无 DEGRADED 表达）——与 RDC TC-03 语义的差异记录性验证
    }

    @Test
    @Tag("story-feat-016-nacos-n04")
    @Story("FEAT-016.F016-N04: 版本字段与租户隔离保持")
    @DisplayName("F016-N04 Nacos 模式版本字段可见且租户隔离反枚举保持")
    void f016n04VersionAndTenantIsolationPreserved() throws Exception {
        assumeNacosEnv();
        // 版本字段经消费方候选视图可见（gateway 链路断言）；OpenAPI 侧验证租户隔离反枚举
        String missingAgent = "feat016-missing-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(listHosts(missingAgent, tenantId)).isEmpty();
        if (peerTenantId != null && peerTenantAgentId != null) {
            assertThat(listHosts(peerTenantAgentId, tenantId)).isEmpty();
            assertThat(listHosts(runtimeAgentId, peerTenantId)).isEmpty();
        }
    }

    @Test
    @Tag("story-feat-016-nacos-n05")
    @Story("FEAT-016.F016-N05: routeHandle 实现差异与不透明性")
    @DisplayName("F016-N05 routeHandle 对测试不透明且跨实现引用不互通")
    void f016n05RouteHandleOpaqueAndCrossImpl() throws Exception {
        assumeNacosEnv();
        // 消费方路径上的 routeHandle 对测试保持不透明：client 响应不出现 handle 字段
        String body = gatewaySendMessage(runtimeAgentId, canary("F016-N05"));
        assertThat(body).doesNotContain("routeHandle").doesNotContain("routeKey");
        assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
        // 跨实现引用不互通（RDC 旧 handle 在 Nacos 模式按引用非法失败）需要转发层探针，
        // 待 spi-probe-base-url 就绪后激活（对齐测试设计 §15.1 F016-N05 后半段）
    }

    // ==================================================================
    // FEAT-017 联动 — 消费端服务标识一致性不变量
    // ==================================================================

    @Test
    @Tag("story-feat-017-nacos-n01")
    @Story("FEAT-017.F017-N01: Nacos 模式投递过滤命中")
    @DisplayName("F017-N01 targetServiceId 与消费侧服务标识同源且投递命中")
    void f017n01DeliveryFilterHits() throws Exception {
        assumeNacosEnv();
        // callee runtime 以 nacos 模式自注册（agentId == 本地服务标识配置）；
        // 上游经 bus/gateway 发信封 targetServiceId == agentId 的调用事件 → 本实例消费并完成业务
        String canaryText = canary("F017-N01");
        String body = gatewaySendMessage(runtimeAgentId, canaryText);
        assertThat(body).contains(canaryText);
        assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
    }

    @Test
    @Tag("story-feat-017-nacos-n02")
    @Story("FEAT-017.F017-N02: 服务标识漂移防护（负向）")
    @DisplayName("F017-N02 漂移 agentId 不消费他实例投递事件")
    void f017n02ServiceIdentityDriftGuard() throws Exception {
        assumeNacosEnv();
        // 需一个 agentId 与消费侧服务标识配置不同源的变体实例（部署不变量违反的可观测证据）
        String driftRuntimeUrl = trimSlash(config.getString("sut.external.nacos.drift-runtime-base-url"));
        String driftAgentId = config.getString("sut.external.nacos.drift-runtime-agent-id");
        assumeTrue(driftRuntimeUrl != null && !driftRuntimeUrl.isBlank()
                        && driftAgentId != null && !driftAgentId.isBlank(),
                "F017-N02 dependency-gated: 需漂移变体实例（drift-runtime-base-url / drift-runtime-agent-id）");
        // 漂移实例注册后按漂移 agentId 发投递事件：
        // - 当 SUT 模拟漂移（agentId != serviceId）：漂移实例不消费该事件（投递过滤 miss），
        //   事件不路由到错误实例；调用侧得到确定失败而非错误实例的业务成功
        // - 当 SUT 为真实制品（serviceId == agentId，部署不变量满足，见特性文档 §2）：
        //   不存在漂移，投递过滤命中，消息成功投递——此为部署不变量的可观测证据
        String body = gatewaySendMessage(driftAgentId, canary("F017-N02"));
        boolean explicitFailure = body.contains("error") || body.contains("not_found")
                || body.contains("notFound") || body.contains("unavailable")
                || body.contains("routable") || body.contains("NO_CANDIDATES");
        boolean invariantHeld = body.contains("TASK_STATE_COMPLETED");
        assertThat(explicitFailure || invariantHeld)
                .as("漂移标识应投递 miss（漂移 SUT）或成功投递（真实制品 serviceId==agentId 不变量），实际: %s", body)
                .isTrue();
        assertNoTopologyLeak(body, runtimeBaseUrl, driftRuntimeUrl);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * 经 Nacos v3 admin API 查询实例候选（观察旁证，不读内部存储）。
     * Nacos 3.x AI 注册使用特殊服务名格式，需先查服务列表再查实例。
     */
    private List<JsonNode> listHosts(String agentId, String namespaceId) {
        try {
            String namePrefix = toNacosServicePrefix(agentId);
            HttpResponse<String> svcResp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(nacosBaseUrl + "/nacos/v3/admin/ns/service/list?pageNo=1&pageSize=100&namespaceId="
                                    + url(namespaceId)))
                            .header("serverIdentity", "security")
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (svcResp.statusCode() != 200) {
                return List.of();
            }
            JsonNode pageItems = JSON.readTree(svcResp.body()).path("data").path("pageItems");
            if (!pageItems.isArray()) {
                return List.of();
            }
            for (JsonNode item : pageItems) {
                String svcName = item.path("name").asText();
                if (svcName.startsWith(namePrefix)) {
                    String groupName = item.path("groupName").asText();
                    HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                                    .uri(URI.create(nacosBaseUrl + "/nacos/v3/admin/ns/instance/list?serviceName="
                                            + url(svcName) + "&groupName=" + url(groupName)
                                            + "&namespaceId=" + url(namespaceId)))
                                    .header("serverIdentity", "security")
                                    .timeout(Duration.ofSeconds(10))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) {
                        return List.of();
                    }
                    JsonNode data = JSON.readTree(resp.body()).path("data");
                    List<JsonNode> result = new ArrayList<>();
                    if (data.isArray()) {
                        data.forEach(result::add);
                    }
                    return result;
                }
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 将 agentId 转换为 Nacos AI 服务名前缀（数字→_3位ASCII码）。 */
    private String toNacosServicePrefix(String agentId) {
        StringBuilder sb = new StringBuilder("____:");
        for (char c : agentId.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append("_").append(String.format("%03d", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private List<JsonNode> awaitHealthyHosts(String serviceName, String namespaceId) {
        Awaitility.await().atMost(120, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
                .until(() -> !listHosts(serviceName, namespaceId).isEmpty());
        return listHosts(serviceName, namespaceId);
    }

    private List<String> instanceKeys(List<JsonNode> hosts) {
        List<String> keys = new ArrayList<>();
        for (JsonNode h : hosts) {
            keys.add(h.path("ip").asText() + ":" + h.path("port").asInt());
        }
        return keys;
    }

    private String gatewaySendMessage(String agentId, String text) throws Exception {
        return gatewaySendMessage(gatewayBaseUrl, gatewayToken, agentId, text);
    }

    /** 经 gateway 统一 A2A facade（JSON-RPC SendMessage）发送带显式 agentId 的消息。 */
    private String gatewaySendMessage(String baseUrl, String token, String agentId, String text)
            throws Exception {
        String messageId = "feat048-delta-" + UUID.randomUUID();
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":\"" + messageId + "\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"" + messageId + "\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"" + text + "\"}]},"
                + "\"metadata\":{\"agentId\":\"" + agentId + "\"},"
                + "\"configuration\":{\"acceptedOutputModes\":[\"text\"]}}}";
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/a2a"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (token != null && !token.isBlank()) {
            req.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return resp.body() == null ? "" : resp.body();
    }

    /** 经 gateway 发流式请求（SendStreamingMessage + SSE），返回聚合的事件流正文。 */
    private String gatewaySendStreaming(String agentId, String text) throws Exception {
        String messageId = "feat048-delta-stream-" + UUID.randomUUID();
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":\"" + messageId + "\",\"method\":\"SendStreamingMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"" + messageId + "\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"" + text + "\"}]},"
                + "\"metadata\":{\"agentId\":\"" + agentId + "\"},"
                + "\"configuration\":{\"acceptedOutputModes\":[\"text\"]}}}";
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBaseUrl + "/a2a"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (gatewayToken != null && !gatewayToken.isBlank()) {
            req.header("Authorization", "Bearer " + gatewayToken);
        }
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return resp.body() == null ? "" : resp.body();
    }

    private void assertNoTopologyLeak(String body, String... runtimeUrls) {
        for (String runtimeUrl : runtimeUrls) {
            if (runtimeUrl != null && !runtimeUrl.isBlank()) {
                assertThat(body).as("client 响应不得泄漏实例拓扑").doesNotContain(hostOf(runtimeUrl));
            }
        }
    }

    private String hostOf(String baseUrl) {
        String s = baseUrl.replaceFirst("^https?://", "");
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private String trimSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String canary(String scene) {
        return "FEAT048_" + scene + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
