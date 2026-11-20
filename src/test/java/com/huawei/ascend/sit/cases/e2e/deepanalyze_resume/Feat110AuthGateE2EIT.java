package com.huawei.ascend.sit.cases.e2e.deepanalyze_resume;

import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-110 服务间认证门（F110-10 的 auth 负向格，DV-110-09 部署项；独立 SUT 实例）。
 *
 * <p>判据（被测制品 0.1.3-feat109111-r2 = agent-solution common@76bcad137，
 * DaServiceAuthFilter:70-92）：deepanalyze.auth.enabled=true + engine-secret 必填
 * （缺失启动失败）→ /api/agents/** 全部 compat 面受守护；无/错 X-Engine-Secret →
 * 401 {"error":"unauthorized"}（常量时间比较）；对头放行。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-110")
@Feature("FEAT-110: 任务断点续传")
class Feat110AuthGateE2EIT {

    private static final String SECRET = "sit-auth-secret-110";

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat110-auth-workspace");
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        .property("deepanalyze.auth.enabled", "true")
                        .property("deepanalyze.auth.engine-secret", SECRET))
                .start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaResumeSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    @Story("F110-10: auth.enabled=true 时无密钥/错密钥 401、对密钥放行（compat 面全守护）")
    @DisplayName("F110-10auth: 服务间认证门")
    void authGateRejectsWithoutValidSecret() throws Exception {
        // 无密钥 → 401
        HttpResponse<String> noHeader = DaCompatGuardrailSupport.post(http, base,
                "/api/agents/run-stream", "{\"sessionId\":\"s\",\"input\":\"x\"}", Duration.ofSeconds(15));
        assertThat(noHeader.statusCode()).as("无 X-Engine-Secret 401").isEqualTo(401);
        assertThat(noHeader.body()).contains("unauthorized");
        // 错密钥 → 401
        HttpResponse<String> wrong = DaCompatGuardrailSupport.get(http, base, "/api/agents/tools",
                Duration.ofSeconds(15), Map.of("X-Engine-Secret", "wrong-secret"));
        assertThat(wrong.statusCode()).as("错密钥 401").isEqualTo(401);
        // 对密钥 → 放行（tools 列表为公开读面，200 即通道放行证据）
        HttpResponse<String> right = DaCompatGuardrailSupport.get(http, base, "/api/agents/tools",
                Duration.ofSeconds(15), Map.of("X-Engine-Secret", SECRET));
        assertThat(right.statusCode()).as("对密钥放行（200）").isEqualTo(200);
        Allure.addAttachment("F110-10auth 证据", "text/plain",
                "noHeader=401 wrong=401 right=200（守护范围 /api/agents/**）");
    }
}
