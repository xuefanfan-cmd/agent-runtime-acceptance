package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DA-01 — deep-research + search 两 agent 的 AgentCard 发现与字段完整性 (场景 1).
 *
 * <p>参考手工脚本 {@code deepagent测试结果.txt §1}：deep-research 同时暴露三个等价的
 * 发现端点——{@code /.well-known/agent-card.json}、{@code /.well-known/agent.json}、
 * {@code /a2a/.well-known/agent-card.json}——三份 JSON body 应完全相同。SDK 侧
 * {@code A2A.getAgentCard(baseUrl)}（由 {@link com.huawei.ascend.sit.lifecycle.SutStack#client(String)}
 * 使用）会解析同一份 card，用来验证 SDK 与 raw HTTP 视图一致。
 *
 * <p>断言分层：
 * <ul>
 *   <li><b>reachability + 等价性</b>：两 agent 的三个端点各自 200 + application/json；
 *       同一 agent 的三份 body 完全等价（含 alias 与 /a2a/ 变体）。</li>
 *   <li><b>deep-research 契约</b>：来自 §1 curl 输出的真值——name/provider.organization/version/
 *       capabilities/defaultInputModes/defaultOutputModes/skills[0]/supportedInterfaces[0].protocolBinding
 *       + url endsWith /a2a / preferredTransport。</li>
 *   <li><b>search 最小验证</b>：card 可发现、name/version 非空、至少一个 supportedInterface。
 *       search 目前只作为 deep-research 的下游被间接触达，没有更强的手工契约参考，暂只做可达性 + 基本 well-formed。</li>
 * </ul>
 *
 * <p>本类只做 read-only 探测，不发送 A2A 消息；标 {@code component} + {@code smoke}
 * 与 travel 侧 A-01 保持一致。
 */
@Tag("component")
@Tag("smoke")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Stories({
        @Story("da.agent-card-discovery: /.well-known/agent-card.json 端点发现与三端点等价"),
        @Story("da.agent-card-contract: card 关键字段契约快照")
})
class AgentCardDiscoveryTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";

    private static final String WELL_KNOWN_AGENT_JSON = "/.well-known/agent.json";
    private static final String WELL_KNOWN_AGENT_CARD_JSON = "/.well-known/agent-card.json";
    private static final String A2A_WELL_KNOWN_AGENT_CARD_JSON = "/a2a/.well-known/agent-card.json";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 两 agent 都用 remote 方式（url-only），框架不会启动进程；只用于寻址 + SDK client 构造。
        return SutStack.builder(config)
                .agent(DEEP_RESEARCH)
                .agent(SEARCH);
    }

    // ---- DA-01.A — reachability + 三端点等价 ----

    @Test
    @DisplayName("DA-01.A: deep-research 三个发现端点 200 application/json 且 body 等价")
    void deepResearchDiscoveryEndpointsAreReachableAndEquivalent() throws Exception {
        assertDiscoveryEndpointsEquivalent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("DA-01.A: search 三个发现端点 200 application/json 且 body 等价")
    void searchDiscoveryEndpointsAreReachableAndEquivalent() throws Exception {
        assertDiscoveryEndpointsEquivalent(SEARCH);
    }

    // ---- DA-01.B — SDK 视图 vs raw HTTP 视图对齐 ----

    @Test
    @DisplayName("DA-01.B: deep-research SDK getAgentCard() 解析到与 raw well-known 一致的关键字段")
    void deepResearchSdkCardMatchesRawWellKnown() throws Exception {
        AgentCard sdkCard = client(DEEP_RESEARCH).getAgentCard();
        JsonNode raw = parseJson(httpGet(DEEP_RESEARCH, WELL_KNOWN_AGENT_CARD_JSON).body());

        assertThat(sdkCard.name()).as("SDK card.name")
                .isEqualTo(raw.get("name").asText());
        assertThat(sdkCard.version()).as("SDK card.version")
                .isEqualTo(raw.get("version").asText());
    }

    // ---- DA-01.C — deep-research 契约（真值来自 §1 curl 输出）----

    @Test
    @DisplayName("DA-01.C: deep-research card 关键字段符合 §1 手工契约")
    void deepResearchCardMatchesManualContract() {
        AgentCard card = client(DEEP_RESEARCH).getAgentCard();

        assertThat(card.name()).as("name").isEqualTo("DeepResearchAgent");
        assertThat(card.version()).as("version").isEqualTo("0.1.0");
        assertThat(card.description())
                .as("description").isNotBlank();

        // provider 结构 —— provider.url 当前 SUT 发空串，做现状快照式断言
        assertThat(card.provider()).as("provider").isNotNull();
        assertThat(card.provider().organization())
                .as("provider.organization").isEqualTo("OpenJiuwen");
        assertThat(card.provider().url())
                .as("provider.url (SUT 当前发空串)").isEqualTo("");

        // capabilities: 手工脚本明确 streaming=true, pushNotifications=false
        assertThat(card.capabilities()).as("capabilities").isNotNull();
        assertThat(card.capabilities().streaming())
                .as("capabilities.streaming").isTrue();
        assertThat(card.capabilities().pushNotifications())
                .as("capabilities.pushNotifications").isFalse();

        // 输入 / 输出模式
        assertThat(card.defaultInputModes())
                .as("defaultInputModes").containsExactly("text", "text/plain");
        assertThat(card.defaultOutputModes())
                .as("defaultOutputModes").containsExactly("text", "text/plain");

        // skills[0] — deep_research 是本 agent 的唯一 skill
        assertThat(card.skills()).as("skills").isNotNull().isNotEmpty();
        assertThat(card.skills().get(0).id())
                .as("skills[0].id").isEqualTo("deep_research");
        assertThat(card.skills().get(0).name())
                .as("skills[0].name").isEqualTo("Deep Research");
        assertThat(card.skills().get(0).tags())
                .as("skills[0].tags").contains("research", "comparison", "llm");

        // supportedInterfaces[0] — JSONRPC 绑定；url 结尾 /a2a；protocolVersion 锁 1.0；tenant 未启用为 null
        assertThat(card.supportedInterfaces())
                .as("supportedInterfaces").isNotEmpty();
        assertThat(card.supportedInterfaces().get(0).protocolBinding())
                .as("supportedInterfaces[0].protocolBinding").isEqualTo("JSONRPC");
        assertThat(card.supportedInterfaces().get(0).url())
                .as("supportedInterfaces[0].url").endsWith("/a2a");
        assertThat(card.supportedInterfaces().get(0).protocolVersion())
                .as("supportedInterfaces[0].protocolVersion").isEqualTo("1.0");
        assertThat(card.supportedInterfaces().get(0).tenant())
                .as("supportedInterfaces[0].tenant (SUT 未启用多租户)").isNull();
    }

    /**
     * 补充断言：手工脚本 §1 明示顶层 {@code $.url} 与 {@code preferredTransport} 已废弃，deep-research
     * 目前仍在发（值为 {@code /a2a} 与 {@code JSONRPC}）。这里断"应存在且值合法"以固化当下现实——若
     * SUT 后续按 A2A 1.0 规范去掉这两个字段，本断言会 FAIL 提示"契约变更"，需与本类的 A-02.E 系用例
     * 对齐一次决策。
     */
    @Test
    @DisplayName("DA-01.C: deep-research card 顶层 $.url 与 $.preferredTransport 存在（记录 SUT 现状）")
    void deepResearchCardTopLevelUrlAndTransportSnapshot() throws Exception {
        JsonNode raw = parseJson(httpGet(DEEP_RESEARCH, WELL_KNOWN_AGENT_CARD_JSON).body());
        assertThat(raw.hasNonNull("url"))
                .as("$.url 顶层字段仍在（deprecated per A2A 1.0 但 SUT 目前仍发）").isTrue();
        assertThat(raw.get("url").asText())
                .as("$.url 值").endsWith("/a2a");
        assertThat(raw.hasNonNull("preferredTransport"))
                .as("$.preferredTransport 顶层字段仍在").isTrue();
        assertThat(raw.get("preferredTransport").asText())
                .as("$.preferredTransport 值").isEqualTo("JSONRPC");
    }

    // ---- DA-01.D — search 最小 well-formed 校验 ----

    @Test
    @DisplayName("DA-01.D: search card 可 SDK 发现，name/version/supportedInterfaces 非空")
    void searchCardIsMinimallyWellFormed() {
        AgentCard card = client(SEARCH).getAgentCard();
        assertThat(card).as("SDK getAgentCard()").isNotNull();
        assertThat(card.name()).as("search card.name").isNotBlank();
        assertThat(card.version()).as("search card.version").isNotBlank();
        assertThat(card.capabilities()).as("search card.capabilities").isNotNull();
        assertThat(card.supportedInterfaces())
                .as("search card.supportedInterfaces").isNotEmpty();
    }

    // ---- helpers ----

    private void assertDiscoveryEndpointsEquivalent(String agent) throws Exception {
        HttpResponse<String> a = httpGet(agent, WELL_KNOWN_AGENT_JSON);
        HttpResponse<String> b = httpGet(agent, WELL_KNOWN_AGENT_CARD_JSON);
        HttpResponse<String> c = httpGet(agent, A2A_WELL_KNOWN_AGENT_CARD_JSON);

        for (HttpResponse<String> r : java.util.List.of(a, b, c)) {
            assertThat(r.statusCode()).as("GET %s status", r.uri()).isEqualTo(200);
            assertThat(r.body()).as("GET %s body", r.uri()).isNotBlank();
            assertThat(mediaType(r)).as("GET %s Content-Type", r.uri())
                    .isEqualTo("application/json");
        }

        JsonNode ja = parseJson(a.body());
        JsonNode jb = parseJson(b.body());
        JsonNode jc = parseJson(c.body());
        assertThat(jb).as("%s: agent-card.json 与 agent.json 等价", agent).isEqualTo(ja);
        assertThat(jc).as("%s: /a2a/.well-known 变体与顶层 well-known 等价", agent).isEqualTo(ja);
    }

    private HttpResponse<String> httpGet(String agent, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(stack.baseUrl(agent) + path))
                        .timeout(Duration.ofSeconds(10))
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
}