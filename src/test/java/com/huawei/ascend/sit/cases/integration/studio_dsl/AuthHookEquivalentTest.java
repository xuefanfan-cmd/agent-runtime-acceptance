/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.studio_dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.TokenEndpointStubFixture;
import com.openjiuwen.studio.dsl.auth.AuthCredentialsSource;
import com.openjiuwen.studio.dsl.auth.AuthHookApplicator;
import com.openjiuwen.studio.dsl.auth.AuthHookException;
import com.openjiuwen.studio.dsl.auth.IamAuthCredentials;
import com.openjiuwen.studio.dsl.auth.OauthCredentials;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag("integration")
@Tag("studio-dsl")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class AuthHookEquivalentTest extends StudioDslContractTestBase {
    @AfterEach
    void resetAuthState() {
        AuthCredentialsSource.clearOverrides();
        AuthHookApplicator.resetDefaults();
    }

    @Test
    @Story("RT-031-04-19: IAM 与 OAUTH 鉴权 hook 等价实现")
    @DisplayName("固定 Java hook 通过真实 HTTP 取 token、注入并保留可区分失败")
    void applyFixedJavaAuthHooksWithoutPythonLoading() throws Exception {
        try (TokenEndpointStubFixture token = new TokenEndpointStubFixture()) {
            verifyIamSuccess(token);
            verifyOauthSuccess(token);
            verifyMissingConfigurationWithoutHttp(token);
            verifyTokenFailures(token);
            verifyNoHookIsNoOp(token);
        }
    }

    private static void verifyIamSuccess(TokenEndpointStubFixture token) throws Exception {
        token.enqueueIamSuccess("iam-canary-031");
        AuthCredentialsSource.setIamOverride(new IamAuthCredentials(
                token.endpoint("/iam/v3/auth/tokens"),
                "acceptance-domain",
                "acceptance-project",
                "acceptance-user",
                "acceptance-password",
                null,
                null));
        Map<String, String> headers = new LinkedHashMap<>();
        AuthHookApplicator.applyPluginAuthIfNeeded(
                hookConfig("plugin_auth", "Z:/missing/iam_auth.py:auth", "HIS_IAM"),
                headers,
                new LinkedHashMap<>(),
                "rt-031-04-19-iam");

        TokenEndpointStubFixture.RequestRecord request = token.takeRequest(Duration.ofSeconds(5));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/iam/v3/auth/tokens");
        assertThat(request.header("Content-Type")).startsWith("application/json");
        assertThat(request.body())
                .contains("acceptance-user", "acceptance-password", "acceptance-project")
                .doesNotContain("iam_auth.py");
        assertThat(headers).containsEntry("X-Acceptance-Iam", "iam-canary-031");
    }

    private static void verifyOauthSuccess(TokenEndpointStubFixture token) throws Exception {
        token.enqueueOauthSuccess("oauth-canary-031");
        AuthCredentialsSource.setOauthOverride(new OauthCredentials(
                token.endpoint("/oauth/token"), "acceptance-client", "acceptance-secret", "openid profile"));
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        AuthHookApplicator.applyMcpAuthIfNeeded(
                hookConfig("mcp_auth", "Z:/missing/mcp_auth.py:auth", "OAUTH"),
                headers,
                query,
                "rt-031-04-19-oauth");

        TokenEndpointStubFixture.RequestRecord request = token.takeRequest(Duration.ofSeconds(5));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/oauth/token");
        assertThat(request.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(request.header("Authorization")).startsWith("Basic ");
        assertThat(request.body())
                .contains("grant_type=client_credentials", "client_id=acceptance-client", "scope=openid+profile")
                .doesNotContain("mcp_auth.py");
        assertThat(headers).containsEntry("Authorization", "Bearer oauth-canary-031");
        assertThat(query).containsEntry("access_token", "oauth-canary-031");
    }

    private static void verifyMissingConfigurationWithoutHttp(TokenEndpointStubFixture token) {
        int before = token.requestCount();
        AuthCredentialsSource.setIamOverride(new IamAuthCredentials("", "", "", "", "", "", ""));
        assertThatThrownBy(() -> AuthHookApplicator.applyPluginAuthIfNeeded(
                        hookConfig("plugin_auth", "missing.py:auth", "HIS_IAM"),
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>(),
                        "rt-031-04-19-missing"))
                .isInstanceOfSatisfying(AuthHookException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AUTH_HOOK_CONFIG_MISSING"));
        assertThat(token.requestCount() - before).isZero();
    }

    private static void verifyTokenFailures(TokenEndpointStubFixture token) throws Exception {
        token.enqueueFailure(401);
        AuthCredentialsSource.setIamOverride(new IamAuthCredentials(
                token.endpoint("/iam/failure"), "d", "p", "u", "pw", null, null));
        assertThatThrownBy(() -> AuthHookApplicator.applyPluginAuthIfNeeded(
                        hookConfig("plugin_auth", "failure.py:auth", "HIS_IAM"),
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>(),
                        "rt-031-04-19-http-failure"))
                .isInstanceOfSatisfying(AuthHookException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AUTH_HOOK_FAILED"));
        assertThat(token.takeRequest(Duration.ofSeconds(5)).path()).isEqualTo("/iam/failure");

        token.enqueueMissingToken();
        AuthCredentialsSource.setOauthOverride(new OauthCredentials(
                token.endpoint("/oauth/missing-token"), "cid", "secret", "scope"));
        assertThatThrownBy(() -> AuthHookApplicator.applyMcpAuthIfNeeded(
                        hookConfig("mcp_auth", "missing-token.py:auth", "OAUTH"),
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>(),
                        "rt-031-04-19-missing-token"))
                .isInstanceOfSatisfying(AuthHookException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AUTH_HOOK_FAILED"));
        assertThat(token.takeRequest(Duration.ofSeconds(5)).path()).isEqualTo("/oauth/missing-token");
    }

    private static void verifyNoHookIsNoOp(TokenEndpointStubFixture token) {
        int before = token.requestCount();
        Map<String, String> headers = new LinkedHashMap<>();
        AuthHookApplicator.applyPluginAuthIfNeeded(
                Map.of("auth", Map.of("scope", "API_KEY")), headers, new LinkedHashMap<>(), "inline");
        AuthHookApplicator.applyMcpAuthIfNeeded(Map.of(), headers, new LinkedHashMap<>(), "none");
        assertThat(headers).isEmpty();
        assertThat(token.requestCount() - before).isZero();
    }

    private static Map<String, Object> hookConfig(String role, String path, String scope) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("domain", "mcp_auth".equals(role) ? "query" : "headers");
        target.put("auth_keys", List.of("X-Acceptance-Iam"));
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("scope", scope);
        auth.put("target", target);
        return Map.of(
                "plugin_dependency", Map.of("hook_function", Map.of(role, path)),
                "auth", auth);
    }
}
