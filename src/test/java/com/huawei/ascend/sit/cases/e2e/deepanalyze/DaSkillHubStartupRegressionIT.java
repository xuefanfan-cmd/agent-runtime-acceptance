package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
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
 * FEAT-054 既有面回归：Skill Hub 启动期链路。
 *
 * <p>本特性在 {@code agent-runtime-ext-java} 内新增了 Skill Hub 的**运行期访问**扩展
 * （{@code SkillHubRuntimeAccess} / {@code SkillHubRuntimeAccessAutoConfiguration} /
 * SPI 子接口 {@code SkillHubRuntimeProvider}），与既有的**启动期整批拉取**链路共存于同一
 * 版本号（0.1.2）内。本类验证这条回归：把启动期拉取开关打开后，宿主仍能正常启动并对外服务。
 *
 * <p><b>本类不断言</b>：启动期整批下载的实际行为（需要可达的 Skill Hub 服务；当前配置里的
 * 端点是示例地址，不可达）。因此本类只覆盖"启动链路未被新增扩展破坏"这一面，
 * 更完整的启动期语义回归需要可达的 Hub 或开发侧承接，已在覆盖台账登记。
 *
 * <p>对应范围卡 §3 回归项 {@code rg.skillhub.startup}。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaSkillHubStartupRegressionIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";

    private HttpClient http;
    private String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // 打开既有启动期拉取开关：若新增的运行期访问扩展与此链路冲突，宿主会在启动阶段暴露。
        return SutStack.builder(config).agent(AGENT, a -> a
                .property("openjiuwen.service.middleware.skillhub.enabled", "true"));
    }

    private void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            base = client(AGENT).getBaseUrl();
        }
    }

    @Test
    @Story("rg.skillhub.startup: 启动期拉取开关打开后宿主仍可服务")
    @DisplayName("rg.skillhub.startup/startup-with-pull-enabled: 能力名片与探活仍可用")
    void hostStillServesWithStartupPullEnabled() throws Exception {
        initClient();

        assertThat(client(AGENT).getAgentCard())
                .as("启动期拉取开关打开后，SUT 仍应完成启动并可被发现")
                .isNotNull();

        HttpResponse<String> health = get("/health");
        Allure.addAttachment("GET /health（skillhub.enabled=true）", "text/plain", health.body());
        assertThat(health.statusCode()).as("探活仍应返回 200").isEqualTo(200);

        HttpResponse<String> card = get("/.well-known/agent-card.json");
        Allure.addAttachment("GET /.well-known/agent-card.json（skillhub.enabled=true）",
                "application/json", card.body());
        assertThat(card.statusCode()).as("服务入口仍应可用").isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
