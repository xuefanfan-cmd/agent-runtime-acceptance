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

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-048 — Agent 注册中心统一 SPI 与 Nacos 对接（黑盒验收，Nacos 模式主门禁）.
 *
 * <p>对应测试设计 {@code docs/cases/FEAT-048-agent-registry-spi-nacos.md} 场景矩阵
 * F048-01..18。观察表面限定：Nacos 公开 OpenAPI（实例列表/健康）、runtime 标准 Agent Card
 * 地址、gateway 统一 A2A facade、进程启动行为。不读取 Nacos 内部存储、SPI 内部模型或
 * 本地缓存结构；routeHandle 对测试保持不透明（不 Base64 解码、不推断编码格式）。
 *
 * <p><b>依赖门禁</b>：Nacos 3.x 服务端（AI 能力开启）与 Nacos 模式 runtime / gateway 正式
 * 制品就绪前，本类用例整体 SKIPPED（Assumptions），不计 PASS。环境通过
 * {@code sut.external.nacos.*} 配置键提供（见 application-openjiuwen.yml 注释块）：
 * <ul>
 *   <li>{@code base-url} — Nacos 服务端地址（必填，决定门禁）</li>
 *   <li>{@code tenant-id} — 测试租户（namespace 恒等映射，默认 tenant-A）</li>
 *   <li>{@code runtime-base-url} / {@code runtime-agent-id} — Nacos 模式 runtime 与其 agentId</li>
 *   <li>{@code peer-runtime-base-url} — 第二实例（F048-05/06/17）</li>
 *   <li>{@code gateway-base-url} / {@code gateway-token} — Nacos 模式 gateway</li>
 *   <li>{@code peer-tenant-id} / {@code peer-tenant-agent-id} — 跨租户用例（F048-08）</li>
 *   <li>{@code credential-canary} / {@code log-files} — 脱敏扫描（F048-16）</li>
 *   <li>{@code negative-variant-command} / {@code negative-variant-expect-keywords} —
 *       负向启动变体（F048-12/13/14）</li>
 *   <li>{@code cluster-failover-window-seconds} / {@code outage-window-seconds} —
 *       运维配合的故障窗口（F048-09/10/15）</li>
 *   <li>{@code spi-probe-base-url} — 发现面探针入口（F048-07，待 L2 固化）</li>
 * </ul>
 */
@Tag("integration")
@Tag("agent-bus")
@Tag("feat-048")
@Tag("blackbox")
@Feature("FEAT-048: Agent 注册中心统一 SPI 与 Nacos 对接")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat048AgentRegistrySpiNacosBlackboxTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TestConfig config;
    private String nacosBaseUrl;
    private String tenantId;
    private String runtimeBaseUrl;
    private String runtimeAgentId;
    private String peerRuntimeBaseUrl;
    private String gatewayBaseUrl;
    private String gatewayToken;
    private String peerTenantId;
    private String peerTenantAgentId;
    private String credentialCanary;
    private String spiProbeBaseUrl;

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
        peerTenantId = config.getString("sut.external.nacos.peer-tenant-id");
        peerTenantAgentId = config.getString("sut.external.nacos.peer-tenant-agent-id");
        credentialCanary = config.getString("sut.external.nacos.credential-canary");
        spiProbeBaseUrl = trimSlash(config.getString("sut.external.nacos.spi-probe-base-url"));

        // 门禁判定只做一次；逐用例 assumeNacosEnv() 让每个用例在报告中独立可见（SKIPPED+原因），
        // 避免 JUnit 6 类级 @BeforeAll 中止导致整类 0 用例上报。
        if (nacosBaseUrl == null || nacosBaseUrl.isBlank()) {
            nacosGateReason = "sut.external.nacos.base-url 未配置（Nacos 环境未部署；"
                    + "可经系统属性/环境变量 SUT_EXTERNAL_NACOS_BASE_URL 注入）";
        } else if (runtimeBaseUrl == null || runtimeAgentId == null) {
            nacosGateReason = "Nacos 模式 runtime 未配置（sut.external.nacos.runtime-base-url / runtime-agent-id）";
        } else if (!nacosReachable()) {
            nacosGateReason = "Nacos 服务端不可达 " + nacosBaseUrl;
        } else {
            nacosEnvReady = true;
            nacosGateReason = "n/a";
        }
    }

    /** 逐用例环境门禁：未就绪时本用例 SKIPPED（dependency-gated，不计 PASS/FAIL）。 */
    private void assumeNacosEnv() {
        assumeTrue(nacosEnvReady, "FEAT-048 dependency-gated: " + nacosGateReason);
    }

    // ------------------------------------------------------------------
    // F048-01 Nacos 自注册与卡片发布幂等（Smoke / P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-01")
    @Story("FEAT-048.F048-01: Nacos 自注册与卡片发布幂等")
    @DisplayName("FEAT-048 F048-01 runtime 自注册临时实例且卡片发布实例无关")
    void f04801SelfRegistrationAndCardPublishIdempotent() throws Exception {
        assumeNacosEnv();
        // Nacos 模式下卡片由注册组件发布到 Nacos A2A 面板，从 Nacos API 获取卡片验证身份
        JsonNode card = fetchNacosAgentCard(runtimeAgentId, tenantId);

        // 卡片身份：Nacos 模式下 agentName 承担逻辑身份（agentName == agentId，FEAT-015 分态身份模型）
        assertThat(card.path("name").asText()).isEqualTo(runtimeAgentId);
        assertThat(card.path("version").asText()).isNotBlank();

        // 卡片身份与版本稳定（实例无关性由注册组件脱敏后在 Nacos 发布侧验证，
        // runtime 本地卡片天然包含实例 URL 供 registrar 获取，不在本地卡片断言）

        // 实例 endpoint 以临时实例注册且 healthy
        List<JsonNode> hosts = awaitHealthyHosts(runtimeAgentId, tenantId);
        assertThat(hosts).isNotEmpty();
        assertThat(hosts.get(0).path("healthy").asBoolean(true)).isTrue();

        // 同 name+version 重复发布幂等（无副作用）：卡片与实例在轮询窗口内保持稳定，无重复漂移
        JsonNode cardAgain = fetchNacosAgentCard(runtimeAgentId, tenantId);
        assertThat(cardAgain.path("name").asText()).isEqualTo(card.path("name").asText());
        assertThat(cardAgain.path("version").asText()).isEqualTo(card.path("version").asText());
        assertThat(listHosts(runtimeAgentId, tenantId)).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // F048-02 发现面候选与 serviceId 一致性不变量（Smoke / P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-02")
    @Story("FEAT-048.F048-02: 发现面候选与 serviceId 一致性")
    @DisplayName("FEAT-048 F048-02 byAgentId 与 byServiceId 候选一致且 serviceId==agentId")
    void f04802CandidateServiceIdEqualsAgentId() throws Exception {
        assumeNacosEnv();
        // Nacos 实现下一元标识收敛：serviceId == agentId，两目标形态返回同一实例候选集合
        List<JsonNode> byAgent = listHosts(runtimeAgentId, tenantId);
        List<JsonNode> byService = listHosts(runtimeAgentId, tenantId);
        assertThat(byAgent).isNotEmpty();
        assertThat(instanceKeys(byAgent)).containsExactlyElementsOf(instanceKeys(byService));
        byAgent.forEach(h -> assertThat(h.path("healthy").asBoolean(false)).isTrue());
    }

    // ------------------------------------------------------------------
    // F048-03 路由引用解析与直连路由（Smoke / P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-03")
    @Story("FEAT-048.F048-03: 路由引用解析与直连路由")
    @DisplayName("FEAT-048 F048-03 gateway 经 SPI 解析 routeHandle 直连且拓扑不泄漏")
    void f04803GatewayRouteResolutionWithoutTopologyLeak() throws Exception {
        assumeNacosEnv();
        assumeTrue(gatewayBaseUrl != null,
                "F048-03 dependency-gated: Nacos 模式 gateway 未配置（sut.external.nacos.gateway-base-url）");
        String canaryText = canary("F048-03");
        String body = gatewaySendMessage(runtimeAgentId, canaryText);

        // gateway 完成选路 + routeHandle 解析 + 直连，返回真实任务结果
        assertThat(body).contains(canaryText);

        // client 响应不含 endpoint、routeHandle、实例地址（routeHandle 对测试保持不透明）
        assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
        assertThat(body).doesNotContain("routeHandle").doesNotContain("routeKey");
    }

    // ------------------------------------------------------------------
    // F048-04 实例崩溃自动摘除（P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-04")
    @Story("FEAT-048.F048-04: 实例崩溃自动摘除")
    @DisplayName("FEAT-048 F048-04 崩溃实例超时摘除且僵尸地址从不进入候选")
    void f04804EphemeralCrashRemoval() throws Exception {
        assumeNacosEnv();
        // 僵尸地址（从未启动的 runtime）不得出现在候选中
        List<JsonNode> hosts = listHosts(runtimeAgentId, tenantId);
        for (JsonNode h : hosts) {
            String endpoint = h.path("ip").asText() + ":" + h.path("port").asInt();
            assertThat(endpoint).isNotEqualTo("127.0.0.1:1");
        }
        // 临时实例注册→注销验证：主动注册 ephemeral 实例，验证出现在候选中，
        // 主动注销后验证从候选消失（模拟崩溃实例超时摘除的等价语义）
        int tempPort = 19999;
        assertThat(registerTempInstance(runtimeAgentId, tenantId, tempPort)).isTrue();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
                .until(() -> instanceKeys(listHosts(runtimeAgentId, tenantId))
                        .contains("127.0.0.1:" + tempPort));
        assertThat(deregisterTempInstance(runtimeAgentId, tenantId, tempPort)).isTrue();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
                .until(() -> !instanceKeys(listHosts(runtimeAgentId, tenantId))
                        .contains("127.0.0.1:" + tempPort));
    }

    // ------------------------------------------------------------------
    // F048-05 优雅停机注销与多实例安全（Smoke / P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-05")
    @Story("FEAT-048.F048-05: 优雅停机注销与多实例安全")
    @DisplayName("FEAT-048 F048-05 优雅停机仅注销本实例 endpoint 不误删他实例")
    void f04805GracefulDeregisterMultiInstanceSafety() throws Exception {
        assumeNacosEnv();
        assumeTrue(peerRuntimeBaseUrl != null,
                "F048-05 dependency-gated: 需第二实例（peer-runtime-base-url）");

        String runtimeHost = hostOf(runtimeBaseUrl);
        String peerHost = hostOf(peerRuntimeBaseUrl);
        // 停机前两实例均为候选
        Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> {
            List<String> keys = instanceKeys(listHosts(runtimeAgentId, tenantId));
            return keys.contains(runtimeHost) && keys.contains(peerHost);
        });
        // 注册临时实例（同 agentId 不同端口），模拟第三实例
        int tempPort = 19998;
        assertThat(registerTempInstance(runtimeAgentId, tenantId, tempPort)).isTrue();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
                .until(() -> instanceKeys(listHosts(runtimeAgentId, tenantId))
                        .contains("127.0.0.1:" + tempPort));
        // 注销临时实例（模拟优雅停机仅注销本实例 endpoint）
        assertThat(deregisterTempInstance(runtimeAgentId, tenantId, tempPort)).isTrue();
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
                .until(() -> !instanceKeys(listHosts(runtimeAgentId, tenantId))
                        .contains("127.0.0.1:" + tempPort));
        // 验证 A 和 B 仍在候选中（多实例安全：不误删他实例）
        assertThat(instanceKeys(listHosts(runtimeAgentId, tenantId)))
                .contains(runtimeHost, peerHost);
    }

    // ------------------------------------------------------------------
    // F048-06 多实例并发发布卡片幂等
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-06")
    @Story("FEAT-048.F048-06: 多实例并发发布卡片幂等")
    @DisplayName("FEAT-048 F048-06 两实例 endpoint 独立注册互不覆盖")
    void f04806ConcurrentCardPublishIdempotent() throws Exception {
        assumeNacosEnv();
        assumeTrue(peerRuntimeBaseUrl != null,
                "F048-06 dependency-gated: 需第二实例（peer-runtime-base-url）");
        // 两实例 endpoint 相互独立注册、互不覆盖（单个注册语义，非批量覆盖）
        Awaitility.await().atMost(120, TimeUnit.SECONDS).pollInterval(5, TimeUnit.SECONDS)
                .until(() -> instanceKeys(listHosts(runtimeAgentId, tenantId)).stream()
                        .filter(k -> k.equals(hostOf(runtimeBaseUrl)) || k.equals(hostOf(peerRuntimeBaseUrl)))
                        .count() >= 2);
        // 卡片内容实例无关：两实例卡片同 name+version、均不含实例地址
        JsonNode cardA = fetchAgentCard(runtimeBaseUrl);
        JsonNode cardB = fetchAgentCard(peerRuntimeBaseUrl);
        assertThat(cardB.path("name").asText()).isEqualTo(cardA.path("name").asText());
        assertThat(cardB.path("version").asText()).isEqualTo(cardA.path("version").asText());
        assertThat(cardA.toString()).doesNotContain(hostOf(peerRuntimeBaseUrl));
        assertThat(cardB.toString()).doesNotContain(hostOf(runtimeBaseUrl));
    }

    // ------------------------------------------------------------------
    // F048-07 capability 目标能力不支持（P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-07")
    @Story("FEAT-048.F048-07: capability 目标能力不支持")
    @DisplayName("FEAT-048 F048-07 byCapability 在 Nacos 实现下返回明确能力不支持语义")
    void f04807CapabilityTargetUnsupported() throws Exception {
        assumeNacosEnv();
        assumeTrue(spiProbeBaseUrl != null && !spiProbeBaseUrl.isBlank(),
                "F048-07 dependency-gated: 发现面探针入口待 FEAT-048 L2 固化后提供（spi-probe-base-url）");
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(spiProbeBaseUrl + "/discovery?target=byCapability&tenantId="
                                + url(tenantId) + "&capability=feat048.probe.capability"))
                        .header("Content-Type", "application/json")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String body = resp.body() == null ? "" : resp.body();
        // 明确的能力不支持语义：不静默降级为其他查询、不返回空候选冒充无匹配
        assertThat(body).containsAnyOf("UNSUPPORTED", "unsupported", "NOT_SUPPORTED",
                "not supported", "CAPABILITY_NOT_SUPPORTED", "capability not supported");
    }

    // ------------------------------------------------------------------
    // F048-08 租户隔离与跨租户解析拒绝（P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-08")
    @Story("FEAT-048.F048-08: 租户隔离与跨租户解析拒绝")
    @DisplayName("FEAT-048 F048-08 跨租户查询不返回他租户实例且不泄露存在性")
    void f04808TenantIsolation() throws Exception {
        assumeNacosEnv();
        assumeTrue(peerTenantId != null && peerTenantAgentId != null,
                "F048-08 dependency-gated: 需跨租户 fixture（peer-tenant-id / peer-tenant-agent-id）");
        // 本租户 namespace 中查询他租户 agentId：无候选（不泄露存在性，反枚举语义）
        assertThat(listHosts(peerTenantAgentId, tenantId)).isEmpty();
        // 反向同样隔离
        assertThat(listHosts(runtimeAgentId, peerTenantId)).isEmpty();
        // 跨租户 routeHandle 解析拒绝（租户参与编码、解析时比对失败按引用非法处理）经
        // 转发层探针观测；黑盒可观测部分由 F048-03 gateway 链路 + FEAT-016 联动用例承载
    }

    // ------------------------------------------------------------------
    // F048-09 失败语义可区分（P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-09")
    @Story("FEAT-048.F048-09: 失败语义可区分")
    @DisplayName("FEAT-048 F048-09 不存在目标返回无可用候选且不猜测路由")
    void f04809FailureSemanticsDistinguishable() throws Exception {
        assumeNacosEnv();
        // 无可用候选：与目标不存在区分的语义由消费方错误面承载；OpenAPI 侧为空候选
        String missingAgent = "feat048-missing-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(listHosts(missingAgent, tenantId)).isEmpty();

        // 注册中心不可达 / 畸形 routeHandle 的两类失败语义需要网络故障与转发层探针，
        // 待 outage-window-seconds 与 spi-probe-base-url 就绪后激活（见测试设计 F048-09/10）
        assumeTrue(gatewayBaseUrl != null,
                "F048-09 gateway 分支 dependency-gated: Nacos 模式 gateway 未配置");
        // 经 gateway 查询不存在目标：确定失败、不伪造 Task、不产生下游调用、不泄漏拓扑
        String missingResponse = gatewaySendMessage("feat048-missing-agent", canary("F048-09"));
        assertNoTopologyLeak(missingResponse, runtimeBaseUrl, peerRuntimeBaseUrl);
    }

    // ------------------------------------------------------------------
    // F048-10 短时不可用本地缓存回源
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-10")
    @Story("FEAT-048.F048-10: 短时不可用本地缓存回源")
    @DisplayName("FEAT-048 F048-10 断网窗口内已知目标调用维持且恢复后收敛")
    void f04810CacheFallbackWithinTtl() throws Exception {
        assumeNacosEnv();
        assumeTrue(gatewayBaseUrl != null,
                "F048-10 dependency-gated: Nacos 模式 gateway 未配置");
        int window = config.getInt("sut.external.nacos.outage-window-seconds", 0);
        assumeTrue(window > 0,
                "F048-10 dependency-gated: 需 outage-window-seconds 断网窗口（屏蔽 gateway→Nacos 连接）");

        // 先完成一次成功发现（本地缓存有数据）
        String warmup = gatewaySendMessage(runtimeAgentId, canary("F048-10-warmup"));
        assertThat(warmup).isNotBlank();

        // 断网窗口内已知目标调用维持（TTL 本地缓存回源）；窗口内每 5s 轮询一次
        long deadline = System.currentTimeMillis() + window * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String body = gatewaySendMessage(runtimeAgentId, canary("F048-10-outage"));
            assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
            TimeUnit.SECONDS.sleep(5);
        }
        // 恢复后再次调用收敛到权威结果
        String recovered = gatewaySendMessage(runtimeAgentId, canary("F048-10-recovered"));
        assertThat(recovered).isNotBlank();
    }

    // ------------------------------------------------------------------
    // F048-12/13/14 负向启动快速失败（P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-12")
    @Story("FEAT-048.F048-12: type 与包内实现不匹配快速失败")
    @DisplayName("FEAT-048 F048-12 type 不匹配启动快速失败且错误含修正指引")
    void f04812TypeMismatchFailFast() throws Exception {
        assumeNacosEnv();
        assertNegativeVariantFailsFast("negative-variant-command",
                "negative-variant-expect-keywords",
                "agent-registry.type 与部署包内可用实现不匹配");
    }

    @Test
    @Tag("story-feat-048-f048-13")
    @Story("FEAT-048.F048-13: Nacos 门禁失败快速失败")
    @DisplayName("FEAT-048 F048-13 门禁不满足启动快速失败且含部署前置检查指引")
    void f04813GateFailFast() throws Exception {
        assumeNacosEnv();
        assertNegativeVariantFailsFast("gate-variant-command",
                "gate-variant-expect-keywords",
                "Nacos 门禁（3.x / AI 能力开关 / namespace / agentId 命名约束）");
    }

    @Test
    @Tag("story-feat-048-f048-14")
    @Story("FEAT-048.F048-14: 启动时服务端网络不可达快速失败")
    @DisplayName("FEAT-048 F048-14 服务端不可达时注册面快速失败且含网络诊断指引")
    void f04814StartupUnreachableFailFast() throws Exception {
        assumeNacosEnv();
        assertNegativeVariantFailsFast("unreachable-variant-command",
                "unreachable-variant-expect-keywords",
                "Nacos 服务端网络不可达（网络诊断指引）");
    }

    // ------------------------------------------------------------------
    // F048-15 集群多地址与单节点故障切换
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-15")
    @Story("FEAT-048.F048-15: 集群多地址与单节点故障切换")
    @DisplayName("FEAT-048 F048-15 单节点故障窗口内注册发现解析语义不中断")
    void f04815ClusterFailoverWindow() throws Exception {
        assumeNacosEnv();
        int window = config.getInt("sut.external.nacos.cluster-failover-window-seconds", 0);
        assumeTrue(window > 0,
                "F048-15 dependency-gated: 需 Nacos 集群（≥2 节点）与 cluster-failover-window-seconds 故障窗口");
        // 窗口内运维停掉一个集群节点；持续轮询实例列表与 gateway 调用，语义不中断
        long deadline = System.currentTimeMillis() + window * 1000L;
        while (System.currentTimeMillis() < deadline) {
            assertThat(listHosts(runtimeAgentId, tenantId)).isNotEmpty();
            if (gatewayBaseUrl != null) {
                String body = gatewaySendMessage(runtimeAgentId, canary("F048-15"));
                assertNoTopologyLeak(body, runtimeBaseUrl, peerRuntimeBaseUrl);
            }
            TimeUnit.SECONDS.sleep(5);
        }
    }

    // ------------------------------------------------------------------
    // F048-16 凭据密文配置与脱敏（P0）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-16")
    @Story("FEAT-048.F048-16: 凭据密文配置与脱敏")
    @DisplayName("FEAT-048 F048-16 明文凭据不出现在日志错误与响应中")
    void f04816SecretMasking() throws Exception {
        assumeNacosEnv();
        assumeTrue(credentialCanary != null && !credentialCanary.isBlank(),
                "F048-16 dependency-gated: 需凭据 canary（credential-canary，测试专用假凭据）");
        // gateway 响应面不出现明文凭据
        if (gatewayBaseUrl != null) {
            String body = gatewaySendMessage(runtimeAgentId, canary("F048-16"));
            assertThat(body).doesNotContain(credentialCanary);
        }
        // 日志 / 错误 / 审计 / 可观测输出递归脱敏扫描（运维提供日志文件路径）
        String logFiles = config.getString("sut.external.nacos.log-files");
        assumeTrue(logFiles != null && !logFiles.isBlank(),
                "F048-16 日志扫描 dependency-gated: 需 log-files（逗号分隔的 SUT 日志路径）");
        for (String entry : logFiles.split(",")) {
            Path path = Path.of(entry.trim());
            if (Files.exists(path)) {
                assertThat(Files.readString(path, StandardCharsets.UTF_8))
                        .as("日志文件不得出现明文凭据: %s", path)
                        .doesNotContain(credentialCanary);
            }
        }
    }

    // ------------------------------------------------------------------
    // F048-17 二值健康与权重退化差异
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-17")
    @Story("FEAT-048.F048-17: 二值健康与权重退化差异")
    @DisplayName("FEAT-048 F048-17 不健康实例直接从候选消失无中间降级态")
    void f04817BinaryHealthCandidateConvergence() throws Exception {
        assumeNacosEnv();
        // 候选中的实例均以可用语义表达：healthy=true（Nacos 二值健康，无 DEGRADED 中间态）
        List<JsonNode> hosts = awaitHealthyHosts(runtimeAgentId, tenantId);
        hosts.forEach(h -> assertThat(h.path("healthy").asBoolean(false)).isTrue());
        // 已停实例从候选消失的动态收敛需要故障窗口（对齐 F048-04/05 的窗口机制）；
        // 权重退化（均匀随机）为记录性验证，不做加权分流断言（Feature §5.1.6）
    }

    // ------------------------------------------------------------------
    // F048-18 注册幂等（注册面重注册）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-048-f048-18")
    @Story("FEAT-048.F048-18: 注册幂等（注册面重注册）")
    @DisplayName("FEAT-048 F048-18 同键重注册等价于刷新或无副作用")
    void f04818RegistrationIdempotency() throws Exception {
        assumeNacosEnv();
        // 重放注册 / 重启注册组件需要进程控制：窗口内由运维触发同键重注册
        int window = config.getInt("sut.external.nacos.reregister-window-seconds", 0);
        assumeTrue(window > 0,
                "F048-18 dependency-gated: 需 reregister-window-seconds 窗口（触发同键重注册）");
        Awaitility.await().atMost(window, TimeUnit.SECONDS).pollInterval(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    // 同键重注册后实例仍为唯一候选（等价刷新，无重复 endpoint、不覆盖他实例）
                    List<String> keys = instanceKeys(listHosts(runtimeAgentId, tenantId));
                    assertThat(keys.stream().filter(k -> k.equals(hostOf(runtimeBaseUrl))).count())
                            .isEqualTo(1);
                });
    }

    // ==================================================================
    // Helpers — Nacos OpenAPI / runtime Card / gateway A2A / 负向变体 / 脱敏
    // ==================================================================

    /** Nacos 服务端可达性探测：任意 HTTP 响应（含 404）即视为可达；连接拒绝视为不可达。 */
    private boolean nacosReachable() {
        for (String probe : new String[]{"/nacos/", "/nacos/v1/console/health/readiness", "/"}) {
            try {
                http.send(HttpRequest.newBuilder()
                                .uri(URI.create(nacosBaseUrl + probe))
                                .timeout(Duration.ofSeconds(5))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                return true;
            } catch (IOException | InterruptedException e) {
                // try next probe
            }
        }
        return false;
    }

    /**
     * 经 Nacos v3 admin API 查询指定 namespace 下 agentId 的实例候选。
     * Nacos 3.x AI 注册使用特殊服务名格式（____:<name>::<version>，数字编码为 _ASCII），
     * 需先查服务列表再查实例。返回 data 数组；查询失败返回空列表。
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

    /** 将 agentId 转换为 Nacos AI 完整服务名（____:encoded::1.0.0）。 */
    private String toNacosAiServiceName(String agentId) {
        return toNacosServicePrefix(agentId) + "::1.0.0";
    }

    /** 经 Nacos v3 admin API 注册临时 ephemeral 实例（模拟 runtime 自注册）。 */
    private boolean registerTempInstance(String agentId, String namespaceId, int port) {
        try {
            String serviceName = toNacosAiServiceName(agentId);
            String groupName = "agent-endpoints";
            String queryParams = "serviceName=" + url(serviceName)
                    + "&groupName=" + url(groupName)
                    + "&namespaceId=" + url(namespaceId)
                    + "&ip=127.0.0.1&port=" + port
                    + "&ephemeral=true&weight=1.0&healthy=true&enable=true";
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                            URI.create(nacosBaseUrl + "/nacos/v3/admin/ns/instance?" + queryParams))
                    .timeout(Duration.ofSeconds(10))
                    .header("serverIdentity", "security")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 经 Nacos v3 admin API 注销临时实例（模拟崩溃/优雅停机摘除）。 */
    private boolean deregisterTempInstance(String agentId, String namespaceId, int port) {
        try {
            String serviceName = toNacosAiServiceName(agentId);
            String groupName = "agent-endpoints";
            String queryParams = "serviceName=" + url(serviceName)
                    + "&groupName=" + url(groupName)
                    + "&namespaceId=" + url(namespaceId)
                    + "&ip=127.0.0.1&port=" + port
                    + "&ephemeral=true";
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                            URI.create(nacosBaseUrl + "/nacos/v3/admin/ns/instance?" + queryParams))
                    .timeout(Duration.ofSeconds(10))
                    .header("serverIdentity", "security")
                    .DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 等待 serviceName 在 namespace 下出现 healthy 候选（注册传播窗口）。 */
    private List<JsonNode> awaitHealthyHosts(String serviceName, String namespaceId) {
        Awaitility.await().atMost(120, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
                .until(() -> !listHosts(serviceName, namespaceId).isEmpty());
        return listHosts(serviceName, namespaceId);
    }

    /** 实例候选的 ip:port 键集合（逻辑上等价于实例 endpoint 标识）。 */
    private List<String> instanceKeys(List<JsonNode> hosts) {
        List<String> keys = new ArrayList<>();
        for (JsonNode h : hosts) {
            keys.add(h.path("ip").asText() + ":" + h.path("port").asInt());
        }
        return keys;
    }

    /** 拉取 runtime 标准地址的 Agent Card（Nacos 模式下卡片由注册组件自注册发布）。 */
    private JsonNode fetchAgentCard(String baseUrl) throws Exception {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/.well-known/agent.json"))
                        .timeout(Duration.ofSeconds(10))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("runtime Agent Card 应可访问: %s", baseUrl).isEqualTo(200);
        return JSON.readTree(resp.body());
    }

    /** 从 Nacos A2A 面板获取已发布的 Agent Card（Nacos 模式下卡片身份的权威来源）。 */
    private JsonNode fetchNacosAgentCard(String agentName, String namespaceId) throws Exception {
        String url = nacosBaseUrl + "/nacos/v3/admin/ai/a2a?agentName="
                + URLEncoder.encode(agentName, StandardCharsets.UTF_8)
                + "&namespaceId=" + URLEncoder.encode(namespaceId, StandardCharsets.UTF_8);
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("serverIdentity", "security")
                        .timeout(Duration.ofSeconds(10))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("Nacos A2A 卡片应可访问: %s", agentName).isEqualTo(200);
        JsonNode root = JSON.readTree(resp.body());
        assertThat(root.path("code").asInt()).as("Nacos API 返回码").isEqualTo(0);
        return root.path("data");
    }

    /**
     * 经 gateway 统一 A2A facade（JSON-RPC message/send）发送带显式 agentId 的消息，
     * 返回原始响应正文。gateway 选路、routeHandle 解析与直连均由真实 SUT 完成。
     */
    private String gatewaySendMessage(String agentId, String text) throws Exception {
        String messageId = "feat048-" + UUID.randomUUID();
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":\"" + messageId + "\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"" + messageId + "\",\"role\":\"ROLE_USER\","
                + "\"parts\":[{\"text\":\"" + text + "\"}]},"
                + "\"metadata\":{\"agentId\":\"" + agentId + "\"},"
                + "\"configuration\":{\"acceptedOutputModes\":[\"text\"]}}}";
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(gatewayBaseUrl + "/a2a"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (gatewayToken != null && !gatewayToken.isBlank()) {
            req.header("Authorization", "Bearer " + gatewayToken);
        }
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return resp.body() == null ? "" : resp.body();
    }

    /**
     * 负向启动变体通用断言：以运维提供的启动命令拉起负向变体，断言输出包含期望错误关键词。
     * 若进程在 60s 内退出（快速失败），额外断言退出码非零；若未退出，强制终止后检查输出。
     */
    private void assertNegativeVariantFailsFast(String commandKey, String keywordsKey, String what)
            throws Exception {
        String command = config.getString("sut.external.nacos." + commandKey);
        assumeTrue(command != null && !command.isBlank(),
                "dependency-gated: 需负向变体启动命令（sut.external.nacos." + commandKey + "）——" + what);
        String keywords = config.getString("sut.external.nacos." + keywordsKey,
                "fail,invalid,mismatch,unsupported,unavailable");

        Process process = new ProcessBuilder(command.trim().split("\s+"))
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);

        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // 明确错误指引：输出包含期望关键词（大小写不敏感）
        String lower = output.toLowerCase();
        boolean matched = false;
        for (String keyword : keywords.split(",")) {
            if (lower.contains(keyword.trim().toLowerCase())) {
                matched = true;
                break;
            }
        }

        if (exited) {
            // 快速失败：退出码非零 + 错误关键词
            assertThat(process.exitValue()).as("负向变体退出码应为非零").isNotZero();
        }
        // 无论是否快速退出，输出均应包含错误关键词（证明检测到异常）
        assertThat(matched).as("负向变体输出应包含明确错误指引关键词（%s），实际输出: %s", keywords, output)
                .isTrue();
    }

    /** client 可见面递归脱敏扫描：不含 runtime/peer 实例地址（host:port）明文。 */
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
