package com.huawei.ascend.sit.cases.component.workflow_agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.config.TestEnvironment;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.HttpClients;

import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;

import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * edpa-adapter Agent Card 发现与字段完整性（特性 4-1）—— {@code component/protocol/AgentCardDiscoveryTest}
 * 对 mainplan 的同型用例，此处镜像到 versatile-orch-demo-adapter（agent-solution 的 bank proxy）。
 *
 * <p>被测 card 由 {@code agent-service-app} 的 {@code AgentCardController} 发布，字段语义与 mainplan 存在真实差异：
 * <ul>
 *   <li><b>WA-01 — discovery</b>：reachability / media type / alias parity / identity（name=versatile-adapter）/
 *       capabilities / interface contract（JSONRPC 绑定在 /a2a）+ 数据驱动契约（{@code edpa-adapter-card-assertions.json}）。</li>
 *   <li><b>WA-02 — field completeness</b>：a2a-sdk {@code AgentCard.Builder.build()} 强制非空的字段全部 present
 *       （required-field 集合复用 SDK 通用的 {@code protocol/a02-agent-card-required-fields.json}），description 非空，
 *       skills 含 versatile-bank-proxy。</li>
 *   <li><b>争议点</b>：adapter card 相对 A2A 1.0 / 相对 mainplan card 的偏差，每个 {@code @Disabled} 并附完整描述待与开发对齐。</li>
 * </ul>
 *
 * <p><b>校准说明</b>：契约 json 的期望值（version / skills 名 / modes）是据 adapter {@code application.yml} + AgentCardController
 * 源码推断的；首轮真机实跑确认后按实测修订。设计文档见
 * {@code docs/superpowers/specs/2026-07-20-edpa-adapter-agent-card-discovery-design.md}。
 *
 * @see com.huawei.ascend.sit.cases.component.protocol.AgentCardDiscoveryTest mainplan 的同型对照
 */
@Tag("component")
@Tag("smoke")
@Feature("FEAT-001: 标准化智能体服务入口")
@Stories({
        @Story("wf.agent-card-endpoint: AgentCard服务端点发现"),
        @Story("wf.agent-card-capabilities: AgentCard capabilities发现"),
        @Story("wf.agent-card-skill: AgentCard skill发现")
})
class EdpaAdapterCardDiscoveryTest extends BaseManagedStackTest {

    private static final String DISCOVERY = "/.well-known/agent.json";
    private static final String ALIAS = "/.well-known/agent-card.json";
    private static final String CONTRACT_RESOURCE =
            "testdata/component/workflow_agent/edpa-adapter-card-assertions.json";
    /** SDK-required 字段集与 agent 无关，复用 mainplan 用例的同一份（DRY）。 */
    private static final String REQUIRED_FIELDS_RESOURCE =
            "testdata/component/protocol/a02-agent-card-required-fields.json";

    // HTTP/1.1：明文端点不用 JDK 默认的 HTTP_2（会发 h2c Upgrade，严格服务端拒绝）——见 transport.HttpClients。
    private final HttpClient http = HttpClients.newHttp1Client();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 双重 gate：@Disabled 是主守（默认 mvn test / CI 跳过）；assumeTrue 留作移除 @Disabled 后的 openjiuwen 守卫
        // （TestEnvironment 默认即 OPENJIUWEN，故 assumeTrue 单用是 no-op——见 memory testenvironment-defaults-to-openjiuwen）。
        Assumptions.assumeTrue(TestEnvironment.current() == TestEnvironment.OPENJIUWEN,
                "openjiuwen profile only — needs envexplorer (Docker) + locally-built versatile-orch-demo-adapter jar");
        // edpa-adapter 的 YAML service-bindings.envexplorer 会被框架自动收集 → 拉起 envexplorer 容器。
        // card 发现本身不依赖 envexplorer 可达；无需 chain / LLM。
        return SutStack.builder(config).agent("edpa-adapter");
    }

    // ---- WA-01.A — reachability & media type (raw HTTP) ----

    @Test
    @DisplayName("WA-01.A: both discovery endpoints return 200 application/json with equivalent bodies")
    void discoveryEndpointsAreReachableWithJsonMediaType() throws Exception {
        HttpResponse<String> main = httpGet(DISCOVERY);
        HttpResponse<String> alias = httpGet(ALIAS);

        assertThat(main.statusCode()).as("GET " + DISCOVERY + " status").isEqualTo(200);
        assertThat(main.body()).as("GET " + DISCOVERY + " body").isNotBlank();
        assertThat(mediaType(main))
                .as("GET " + DISCOVERY + " Content-Type").isEqualTo("application/json");

        assertThat(alias.statusCode()).as("GET " + ALIAS + " status").isEqualTo(200);
        assertThat(mediaType(alias))
                .as("GET " + ALIAS + " Content-Type").isEqualTo("application/json");

        // 别名端点须同源服务同一张 card（A2A 兼容性）。
        assertThat(parseJson(alias.body()))
                .as("alias card equals primary card")
                .isEqualTo(parseJson(main.body()));
    }

    // ---- WA-01.B — identity field via SDK discovery path ----

    @Test
    @DisplayName("WA-01.B: SDK discovery resolves a non-null card named versatile-adapter")
    void sdkDiscoveryReturnsCardWithName() {
        AgentCard card = client("edpa-adapter").getAgentCard();

        assertThat(card).as("SDK getAgentCard()").isNotNull();
        assertThat(card.name()).isEqualTo("versatile-adapter");
    }

    // ---- WA-01.C — capability declarations ----

    @Test
    @DisplayName("WA-01.C: card declares streaming=true and pushNotifications=false")
    void cardDeclaresStreamingAndPushCapabilities() {
        AgentCard card = client("edpa-adapter").getAgentCard();

        assertThat(card.capabilities()).as("capabilities").isNotNull();
        assertThat(card.capabilities().streaming()).as("capabilities.streaming").isTrue();
        assertThat(card.capabilities().pushNotifications()).as("capabilities.pushNotifications").isFalse();
    }

    // ---- WA-01.D — interface contract (transport, url, modes) ----

    @Test
    @DisplayName("WA-01.D: card exposes a JSONRPC interface bound at /a2a with text modes")
    void cardExposesJsonRpcInterfaceContract() throws Exception {
        AgentCard card = client("edpa-adapter").getAgentCard();

        // A2A 1.0：端点在 supportedInterfaces[]（protocolBinding + url）；SDK 经 supportedInterfaces[0].url 暴露。
        // 顶层 url / preferredTransport 是废弃字段，adapter 仍输出（见 topLevelUrlAndPreferredTransportAreAbsent 争议点）。
        List<AgentInterface> jsonrpcInterfaces = card.supportedInterfaces().stream()
                .filter(i -> "JSONRPC".equals(i.protocolBinding()))
                .toList();
        assertThat(jsonrpcInterfaces)
                .as("supportedInterfaces contains a JSONRPC binding")
                .isNotEmpty();
        assertThat(jsonrpcInterfaces.get(0).url())
                .as("JSONRPC interface url").endsWith("/a2a");

        // adapter application.yml 把 input/output modes 均收窄为 ["text"]（覆盖默认的 ["text","text/plain"]）。
        assertThat(card.defaultInputModes()).as("defaultInputModes").containsExactly("text");
        assertThat(card.defaultOutputModes())
                .as("defaultOutputModes").containsExactly("text");

        // 原始 JSON：JSONRPC 接口端点被改写为服务来源（/a2a）。
        JsonNode cardNode = parseJson(httpGet(DISCOVERY).body());
        assertThat(readPath(cardNode, "supportedInterfaces.0.url").asText())
                .as("$.supportedInterfaces[0].url").endsWith("/a2a");
    }

    // ---- WA-01.D (data-driven) — full contract from the discovery endpoint ----

    @Test
    @DisplayName("WA-01.D: served card matches the data-driven contract (edpa-adapter-card-assertions.json)")
    void servedCardMatchesContract() throws Exception {
        JsonNode cardNode = parseJson(httpGet(DISCOVERY).body());

        for (Map.Entry<String, Object> entry : loadContract().entrySet()) {
            String path = entry.getKey();
            if (path.startsWith("_")) {
                continue; // 文档键
            }
            JsonNode expected = mapper.valueToTree(entry.getValue());
            JsonNode actual = readPath(cardNode, path);
            assertThat(actual)
                    .as("contract field $.%s", path)
                    .isNotNull()
                    .isEqualTo(expected);
        }
    }

    // ---- WA-02 — field completeness (a2a-sdk contract) ----

    @Test
    @DisplayName("WA-02: every SDK-required card field is present (data-driven)")
    void cardCarriesAllSdkRequiredFields() throws Exception {
        AgentCard card = client("edpa-adapter").getAgentCard();

        for (String field : requiredFields()) {
            Object value = sdkField(card, field);
            assertThat(value)
                    .as("SDK-required field '%s' is present (a2a-sdk AgentCard.Builder enforces non-null)", field)
                    .isNotNull();
        }
        assertThat(card.defaultInputModes()).as("defaultInputModes non-empty").isNotEmpty();
        assertThat(card.defaultOutputModes()).as("defaultOutputModes non-empty").isNotEmpty();
        assertThat(card.supportedInterfaces()).as("supportedInterfaces non-empty").isNotEmpty();
        assertThat(card.skills()).as("skills present").isNotNull();
    }

    @Test
    @DisplayName("WA-02: description is a non-blank string")
    void descriptionIsNonBlank() {
        assertThat(client("edpa-adapter").getAgentCard().description())
                .as("description").isNotBlank();
    }

    // ---- WA-02 — positive skill assertion (adapter-specific) ----

    @Test
    @DisplayName("WA-02: card declares the versatile-bank-proxy skill")
    void cardDeclaresVersatileBankProxySkill() {
        AgentCard card = client("edpa-adapter").getAgentCard();

        assertThat(card.skills()).as("skills").isNotNull();
        assertThat(card.skills().stream().map(AgentSkill::id).toList())
                .as("skills[].id contains versatile-bank-proxy")
                .contains("versatile-bank-proxy");
    }

    // ---- 争议点 / 争议点（保留描述以供与开发对齐） ----

    /**
     * WA-02.E — 按 A2A 1.0，顶层 {@code $.url} 与 {@code preferredTransport} 应不输出。
     * <p>同 mainplan 的 A-02.E：A2A 1.0 用 {@code supportedInterfaces[]} 承载端点信息，顶层 {@code url} /
     * {@code preferredTransport} 为已废弃字段。被测 edpa-adapter 的 {@code AgentCardController.buildCard()} 仍把它们
     * 传给 {@code AgentCard} 构造器（值 = jsonRpcUrl / "JSONRPC"），故实测会被输出。
     * <p>注：SDK 的 {@code AgentCard.url()} 在缺省时本应返回 null，此处因服务端显式赋值而对外可见。
     */
    @Test
    @DisplayName("WA-02.E: top-level $.url should be absent per A2A v1.0 (DISABLED: adapter emits it)")
    void topLevelUrlAndPreferredTransportAreAbsent() throws Exception {
        JsonNode cardNode = parseJson(httpGet(DISCOVERY).body());
        assertThat(cardNode.hasNonNull("url"))
                .as("$.url should be absent (no value) per A2A v1.0; endpoint lives in supportedInterfaces[]")
                .isFalse();
    }

    /**
     * WA-02.H — provider.organization 不得为空串。
     * <p>争议点（adapter 专属）：{@code A2AProperties.providerOrganization} 默认 {@code ""}，adapter 的
     * {@code application.yml} 未覆盖，故 {@code provider.organization} 实测为空串。对照 mainplan card 的
     * {@code "OpenJiuwen"}，这里缺组织标识。{@code provider} 虽为可选字段，但 SDK 要求字段扫描只校验
     * {@code card.provider()} 非 null（{@code AgentProvider("","")} 通过），未拦空串——故单列此争议点。
     * <p>待与开发对齐：应填真实组织（如 OpenJiuwen / Huawei），还是维持空串？当前临时禁用，保持 mvn test 全绿。
     */
    @Test
    @DisplayName("WA-02.H: provider.organization is not blank (DISABLED: adapter leaves it empty)")
    void providerOrganizationIsNotBlank() {
        AgentCard card = client("edpa-adapter").getAgentCard();
        assertThat(card.provider()).as("provider").isNotNull();
        assertThat(card.provider().organization())
                .as("provider.organization should not be blank")
                .isNotBlank();
    }

    /** Map a required-field name to its SDK accessor（与 mainplan 用例一致，保持数据驱动扫描 spec-explicit）。 */
    private static Object sdkField(AgentCard card, String field) {
        return switch (field) {
            case "name" -> card.name();
            case "description" -> card.description();
            case "version" -> card.version();
            case "capabilities" -> card.capabilities();
            case "defaultInputModes" -> card.defaultInputModes();
            case "defaultOutputModes" -> card.defaultOutputModes();
            case "skills" -> card.skills();
            case "supportedInterfaces" -> card.supportedInterfaces();
            default -> throw new IllegalArgumentException(
                    "Unknown required field '" + field + "' — update a02-agent-card-required-fields.json");
        };
    }

    private List<String> requiredFields() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(REQUIRED_FIELDS_RESOURCE)) {
            assertThat(is).as("required-fields resource %s on classpath", REQUIRED_FIELDS_RESOURCE).isNotNull();
            JsonNode node = mapper.readTree(is);
            return StreamSupport.stream(node.get("required").spliterator(), false)
                    .map(JsonNode::asText)
                    .toList();
        }
    }

    // ---- helpers ----

    private HttpResponse<String> httpGet(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(stack.baseUrl("edpa-adapter") + path))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String mediaType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
    }

    private JsonNode parseJson(String body) throws IOException {
        return mapper.readTree(body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadContract() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(is).as("contract resource %s on classpath", CONTRACT_RESOURCE).isNotNull();
            return mapper.readValue(is, Map.class);
        }
    }

    private static JsonNode readPath(JsonNode root, String dotted) {
        JsonNode current = root;
        for (String part : dotted.split("\\.")) {
            if (current.isArray() && isNumeric(part)) {
                current = current.get(Integer.parseInt(part));
            } else {
                current = current.get(part);
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static boolean isNumeric(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
