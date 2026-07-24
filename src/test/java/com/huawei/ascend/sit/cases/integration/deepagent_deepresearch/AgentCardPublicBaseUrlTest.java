package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.agent-card-public-base-url — Agent Card URL 按公开 base 解析.
 *
 * <p>FEAT-001 §3「{@code /.well-known/agent-card.json}」补充说明 + §5.1.1「Agent Card 发现语义」。
 * card 里所有对外可拨的 URL（顶层 {@code $.url} + {@code supportedInterfaces[*].url}）必须是**可被外部客户端拨打的绝对 URL**，
 * 不能是 loopback / 私有 hostname / 无 scheme —— 否则跨集群 / 跨租户 / 网关外的客户端拿到 card 却无法访问。
 *
 * <p><b>Scope</b>：SIT 侧无法直接读 SUT 的 {@code agent-runtime.access.a2a.public-base-url} 配置，
 * 所以本用例走"可拨性"约束，而不是"URL 前缀等于某具体 base"。约束项：
 * <ul>
 *   <li>URL 必须是绝对 URL（有 scheme + host）；</li>
 *   <li>scheme ∈ {http, https}；</li>
 *   <li>host 不是 {@code localhost} / {@code 127.0.0.1} / {@code 0.0.0.0}（防止 SUT 把内部 bind 地址泄漏成 card 里的公开 URL）；</li>
 *   <li>如果 SUT 声明了 public-base-url，所有 URL 都应指向同一 host（防止一部分 URL 逃逸）。</li>
 * </ul>
 *
 * <p><b>与 {@link AgentCardDiscoveryTest} 的分工</b>：DA-01 只断言 {@code $.url endsWith "/a2a"}，
 * 不关心 host 是否可拨。本用例专门守 host 可拨性。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.agent-card-public-base-url: Agent Card URL 按公开 base 解析 (可拨性守卫)")
class AgentCardPublicBaseUrlTest extends BaseManagedStackTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final List<String> LOOPBACK_HOSTS = List.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1");

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(DEEP_RESEARCH);
    }

    @Test
    @DisplayName("FEAT-001.agent-card-public-base-url: card 所有对外 URL 应可拨（绝对 URL + 非 loopback）")
    void cardUrlsAreExternallyDialable() {
        A2aServiceClient a2a = client(DEEP_RESEARCH);
        AgentCard card = a2a.getAgentCard();

        // 顶层 $.url —— A2A 1.0 已弃用但 SUT 仍在发（见 DA-01.C 快照）。存在则校验可拨性。
        String topUrl = card.url();
        if (topUrl != null && !topUrl.isBlank()) {
            assertUrlIsExternallyDialable("card.$.url", topUrl);
        }

        // supportedInterfaces[*].url —— A2A 1.0 主推的入口清单。每个都必须可拨。
        List<AgentInterface> ifaces = card.supportedInterfaces();
        assertThat(ifaces)
                .as("FEAT-001.agent-card-public-base-url: supportedInterfaces 应至少含一个入口")
                .isNotNull()
                .isNotEmpty();
        for (int i = 0; i < ifaces.size(); i++) {
            AgentInterface iface = ifaces.get(i);
            assertUrlIsExternallyDialable(
                    String.format("card.supportedInterfaces[%d].url (binding=%s)", i, iface.protocolBinding()),
                    iface.url());
        }

        // host 一致性：所有对外 URL 的 host 应指向同一个（防止一部分 URL 逃逸到不同 hostname）
        String canonicalHost = URI.create(ifaces.get(0).url()).getHost();
        for (int i = 1; i < ifaces.size(); i++) {
            String host = URI.create(ifaces.get(i).url()).getHost();
            assertThat(host)
                    .as("FEAT-001.agent-card-public-base-url: supportedInterfaces[%d].url host 应与首条一致\n"
                            + "  first=%s, current=%s", i, canonicalHost, host)
                    .isEqualTo(canonicalHost);
        }
        if (topUrl != null && !topUrl.isBlank()) {
            String topHost = URI.create(topUrl).getHost();
            assertThat(topHost)
                    .as("FEAT-001.agent-card-public-base-url: 顶层 $.url host 应与 supportedInterfaces 一致\n"
                            + "  top=%s, iface=%s", topHost, canonicalHost)
                    .isEqualTo(canonicalHost);
        }
    }

    private static void assertUrlIsExternallyDialable(String fieldLabel, String url) {
        assertThat(url)
                .as("FEAT-001.agent-card-public-base-url: %s 应非空", fieldLabel)
                .isNotBlank();

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new AssertionError(fieldLabel + " 不是合法 URI: " + url, ex);
        }
        assertThat(uri.isAbsolute())
                .as("FEAT-001.agent-card-public-base-url: %s 应是绝对 URL (含 scheme)\nvalue=%s",
                        fieldLabel, url)
                .isTrue();
        assertThat(uri.getScheme())
                .as("FEAT-001.agent-card-public-base-url: %s scheme 应 ∈ {http, https}\nvalue=%s",
                        fieldLabel, url)
                .isIn("http", "https");
        assertThat(uri.getHost())
                .as("FEAT-001.agent-card-public-base-url: %s 应包含 host\nvalue=%s", fieldLabel, url)
                .isNotBlank();
        assertThat(uri.getHost())
                .as("FEAT-001.agent-card-public-base-url: %s host 不应是 loopback (%s)\nvalue=%s",
                        fieldLabel, LOOPBACK_HOSTS, url)
                .isNotIn(LOOPBACK_HOSTS);
    }
}