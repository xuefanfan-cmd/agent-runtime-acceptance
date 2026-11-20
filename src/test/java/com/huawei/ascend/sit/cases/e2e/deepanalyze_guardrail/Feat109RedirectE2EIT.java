package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.RecordedHookServer;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-109 白名单重定向格（F109-14 后半，L1 出口 11 / L2 §6.2 重定向逐跳检查）。
 *
 * <p>判据：
 * <ul>
 *   <li>「白名单 URL 重定向到非白名单主机均在出网前拒绝且不发送凭据头」（L1 出口 11）；
 *       L2：重定向最多 3 跳、逐跳白名单校验、凭据头只在目标通过检查后发送。</li>
 *   <li>构造：白名单只含接收端 A；A 响应 302 → Location 指向接收端 B（同机另一端口，**不在白名单**）。
 *       断言：①B 零请求（重定向目标未出网）；②hook_result denied/failed（PreToolUse deny 工具
 *       不执行）；③A 收到的初始请求可带凭据头（headers 原值只发白名单目标）。</li>
 *   <li>DV-109-03 口径：白名单违规是执行时拦截（BLOCKED_TARGET/START_FAILED 形态）而非受理前
 *       400——本格断言 hook_result 状态与 B 零请求，不断言 400。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109RedirectE2EIT {

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;
    private static RecordedHookServer whitelistServer;
    private static RecordedHookServer offlistServer;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat109-redirect-ws");
        whitelistServer = new RecordedHookServer();
        offlistServer = new RecordedHookServer();
        // 白名单只含 A——B（offlistServer）刻意排除
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        .property("deepanalyze.compat.hooks.http-host-whitelist",
                                whitelistServer.hostPort()))
                .start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaCompatGuardrailSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
        if (whitelistServer != null) {
            whitelistServer.close();
        }
        if (offlistServer != null) {
            offlistServer.close();
        }
    }

    @Test
    @Story("F109-14b: 白名单 URL 重定向到非白名单主机——出网前拒绝，非白名单主机零请求")
    @DisplayName("F109-14b: 重定向出界拦截")
    void redirectOffWhitelistRejected() throws Exception {
        // A 收到初始请求后 302 → B（非白名单）
        whitelistServer.respondRedirect(offlistServer.baseUrl() + "/hook");
        String session = "sess-f109rd-" + System.nanoTime();
        String fileName = "redirect-" + System.nanoTime() + ".txt";
        Files.createDirectories(workspaceRoot.resolve(session));
        Files.writeString(workspaceRoot.resolve(session).resolve(fileName), "rd-content");
        String hook = "{\"id\":\"rd-pre\",\"event\":\"PreToolUse\",\"type\":\"http\","
                + "\"config\":{\"url\":\"" + whitelistServer.baseUrl() + "/hook\","
                + "\"headers\":{\"X-Canary-Cred\":\"cred-only-for-whitelist\"}},\"enabled\":true}";
        String input = "使用 read_file 工具读取文件 " + fileName + "，然后结束。不要执行其他工具。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\","
                + "\"runtimeConfig\":{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\""
                + session + "\",\"hooks\":[" + hook + "]}}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(150));
        List<SseFrame> frames = DaCompatGuardrailSupport.readFrames(stream, f -> false,
                Duration.ofSeconds(150));

        // ①非白名单主机 B 零请求（重定向未出网到 B）
        assertThat(offlistServer.requestCount())
                .as("重定向目标（非白名单主机）零请求——出网前拦截").isZero();
        // ②A 收到了初始请求（白名单目标正常触达）
        assertThat(whitelistServer.requestCount())
                .as("白名单主机收到初始 POST").isGreaterThanOrEqualTo(1);
        // ③PreToolUse deny：工具不执行（hook_result denied/failed + 无工具成功结果）
        boolean hookDenied = frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("\"hookId\":\"rd-pre\"")
                && (f.data().contains("\"status\":\"denied\"") || f.data().contains("\"status\":\"failed\"")));
        assertThat(hookDenied).as("重定向出界 → hook_result denied/failed").isTrue();
        boolean toolRan = frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("rd-content"));
        assertThat(toolRan).as("工具未执行（PreToolUse deny 后业务工具不执行）").isFalse();
        Allure.addAttachment("F109-14b 证据", "text/plain",
                "A请求数=" + whitelistServer.requestCount() + " B请求数=" + offlistServer.requestCount()
                        + " hookDenied=" + hookDenied + " toolRan=" + toolRan);
        whitelistServer.reset();
    }
}
