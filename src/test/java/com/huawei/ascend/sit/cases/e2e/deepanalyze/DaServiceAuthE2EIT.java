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
 * FEAT-054：服务间认证过滤器（设计 §3.2 服务入口行 MUST；验收出口 #15）。
 *
 * <p>本类用 {@link SutStack.Builder#agent(String, java.util.function.Consumer)} 在**测试类
 * 维度**把认证打开（认证默认关闭，属被测默认行为，因此必须显式覆写才能验这条）。
 * 所用的密钥是**测试专用固定值**，不是任何真实凭据。
 *
 * <p>对应场景 {@code da.auth.filter}；逐用例判定规则见
 * {@code 01-Design_File/20260921/FEAT-054/FEAT-054-测试设计初稿.md}。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaServiceAuthE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    /** 测试专用共享密钥（仅用于本用例，非真实凭据）。 */
    private static final String TEST_SECRET = "sit-feat054-test-secret";
    private static final String SECRET_HEADER = "X-Engine-Secret";

    private HttpClient http;
    private String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(AGENT, a -> DaModelEnvironment.bind(a)
                .property("deepanalyze.auth.enabled", "true")
                .property("deepanalyze.auth.engine-secret", TEST_SECRET));
    }

    private void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            base = client(AGENT).getBaseUrl();
        }
    }

    @Test
    @Story("da.auth.filter: 无密钥请求一律 401 且响应体不含密钥信息")
    @DisplayName("da.auth.filter/without-secret: GET /agent/status 无 X-Engine-Secret → 401")
    void requestWithoutSecretIsRejected() throws Exception {
        initClient();
        HttpResponse<String> r = get("/agent/status/whatever-task", null);
        Allure.addAttachment("无密钥访问 /agent/status", "text/plain", r.body());
        assertThat(r.statusCode()).as("受守护端点缺密钥必须拒绝").isEqualTo(401);
        assertThat(r.body()).as("拒绝体为统一 unauthorized 形态").contains("unauthorized");
    }

    @Test
    @Story("da.auth.filter: 错误密钥与缺失密钥同体，不泄露差异")
    @DisplayName("da.auth.filter/wrong-secret: GET /agent/status 错密钥 → 401 且与无密钥同体")
    void requestWithWrongSecretIsRejectedIdentically() throws Exception {
        initClient();
        HttpResponse<String> without = get("/agent/status/whatever-task", null);
        HttpResponse<String> wrong = get("/agent/status/whatever-task", "definitely-not-the-secret");
        Allure.addAttachment("无密钥响应体", "text/plain", without.body());
        Allure.addAttachment("错密钥响应体", "text/plain", wrong.body());
        assertThat(wrong.statusCode()).as("错密钥必须拒绝").isEqualTo(401);
        assertThat(wrong.body())
                .as("两种失败必须不可区分（不形成密钥探测 oracle）")
                .isEqualTo(without.body());
    }

    @Test
    @Story("da.auth.filter: 合法密钥放行（进入业务逻辑）")
    @DisplayName("da.auth.filter/with-secret: GET /agent/status 合法密钥 → 通过过滤器")
    void requestWithValidSecretPassesFilter() throws Exception {
        initClient();
        HttpResponse<String> r = get("/agent/status/no-such-task-054", TEST_SECRET);
        Allure.addAttachment("合法密钥访问 /agent/status", "text/plain", r.body());
        assertThat(r.statusCode())
                .as("合法密钥应通过过滤器并进入业务逻辑（任务不存在→404，而非 401）")
                .isEqualTo(404);
    }

    @Test
    @Story("da.auth.filter: 探活端点不在守护范围")
    @DisplayName("da.auth.filter/health-unguarded: GET /health 无密钥 → 200")
    void healthEndpointIsNotGuarded() throws Exception {
        initClient();
        HttpResponse<String> r = get("/health", null);
        Allure.addAttachment("无密钥访问 /health", "text/plain", r.body());
        assertThat(r.statusCode())
                .as("探活端点按惯例不经服务间认证过滤器")
                .isEqualTo(200);
    }

    private HttpResponse<String> get(String path, String secret) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (secret != null) {
            b = b.header(SECRET_HEADER, secret);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
