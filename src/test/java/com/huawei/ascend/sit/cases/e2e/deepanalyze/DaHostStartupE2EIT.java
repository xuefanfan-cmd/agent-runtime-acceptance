package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 冒烟：DA 宿主（deepanalyze-java）能否作为 SUT 被框架拉起并进入可用态。
 *
 * <p>本类是 36 个场景的地基：只要它跑通，就证明
 * <ul>
 *   <li>{@code sut.agents.deepanalyze} 的坐标声明能被 {@link SutStack} 解析；</li>
 *   <li>制品（{@code com.openjiuwen:deepanalyze-engine:<sut.agents.deepanalyze.version>}，
 *       classifier {@code exec}）能在本地仓库被找到并拉起；</li>
 *   <li>runtime 的服务入口已就绪（能力名片端点可达）；</li>
 *   <li>探活端点可用。</li>
 * </ul>
 *
 * <p>对应设计 §2 验收出口 #1；逐用例判定规则见
 * {@code 01-Design_File/20260921/FEAT-054/FEAT-054-测试设计初稿.md} 的
 * {@code da.deploy.startup}。
 *
 * <p>刻意不断言：启动耗时阈值（只记录观测值）、能力名片各字段的语义正确性
 * （那是 {@code da.card.truthfulness} 的职责）。
 */
@Tag("e2e")
@Tag("smoke")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaHostStartupE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    private static final String CARD_PATH = "/.well-known/agent-card.json";
    private static final String HEALTH_PATH = "/health";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 单 Agent 宿主，无下游：DA 是直连 HTTP 部署形态（设计 §2 决策记录）。
        return SutStack.builder(config).agent(AGENT, agent -> DaModelEnvironment.bind(agent));
    }

    @Test
    @Story("da.deploy.startup: 独立部署启动与就绪")
    @DisplayName("da.deploy.startup: DA 宿主被拉起、探活与能力名片可达")
    void serviceStartsAndReportsReady() throws Exception {
        AgentCard card = client(AGENT).getAgentCard();

        assertThat(card)
                .as("能力名片必须可通过框架的发现路径取到——取不到说明 SUT 没起来")
                .isNotNull();
        assertThat(card.name())
                .as("能力名片 name 不得为空（FEAT-027：多实例需可区分）")
                .isNotBlank();

        String baseUrl = client(AGENT).getBaseUrl();
        assertThat(baseUrl)
                .as("解析出的 base URL 必须是绝对地址")
                .startsWith("http");

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> health = get(http, baseUrl + HEALTH_PATH);
        Allure.addAttachment("GET " + HEALTH_PATH, "text/plain", health.body());
        assertThat(health.statusCode())
                .as("探活端点 " + HEALTH_PATH + " 应返回 200")
                .isEqualTo(200);

        HttpResponse<String> cardResponse = get(http, baseUrl + CARD_PATH);
        Allure.addAttachment("GET " + CARD_PATH, "application/json", cardResponse.body());
        assertThat(cardResponse.statusCode())
                .as("能力名片端点 " + CARD_PATH + " 应返回 200")
                .isEqualTo(200);
        assertThat(cardResponse.headers().firstValue("content-type").orElse(""))
                .as("能力名片端点应返回 JSON")
                .contains("json");
        assertThat(cardResponse.body())
                .as("能力名片响应体不得为空")
                .isNotBlank();
        assertThat(cardResponse.body())
                .as("能力名片响应体应含 name 字段（与 SDK 发现路径一致）")
                .contains("\"name\"");
    }

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
