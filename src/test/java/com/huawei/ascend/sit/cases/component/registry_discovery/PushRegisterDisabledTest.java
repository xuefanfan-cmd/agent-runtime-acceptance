package com.huawei.ascend.sit.cases.component.registry_discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
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
 * FEAT-015 P0 — deployment-discovery 开启时 push 注册被拒（无 fixture 依赖）。
 */
@Tag("component")
@Tag("registry-discovery")
@Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
class PushRegisterDisabledTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static String rdcBaseUrl;

    @BeforeAll
    static void loadConfig() {
        TestConfig config = TestConfig.load();
        rdcBaseUrl = config.getString("sut.external.rdc.base-url", "http://localhost:8092");
    }

    @Test
    @DisplayName("deployment-discovery 开启时 POST /register → 410 push_registration_disabled")
    @Story("discover.push-register-disabled: deployment-discovery 开启时 push 注册被拒")
    void pushRegisterReturns410WhenDeploymentDiscoveryEnabled() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(rdcBaseUrl + "/api/registry/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"tenantId\":\"t1\",\"agentId\":\"a1\"}"))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode())
                .as("push register should be disabled when deployment-discovery.enabled=true")
                .isEqualTo(410);

        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.get("error").asText())
                .as("error code")
                .isEqualTo("push_registration_disabled");
    }
}
