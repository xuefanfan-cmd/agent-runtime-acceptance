package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockRemoteAgentServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-004.remote-card-recovery — 下游 Card 拉取失败的启动容错与重试恢复(testplan A3).
 *
 * <p><b>特性依据</b>:FEAT-004 §2「远程 Agent 静态接入 MUST」+ §4 场景「配置并暴露下游 Agent」——
 * runtime 基于配置接入下游;下游暂不可达不应使 runtime 启动失败(fail-fast 校验只查 url 非空),
 * discovery 按周期重试,下游上线后能力自动可用(实测重试周期 30s)。
 *
 * <p><b>观察面</b>:层 1 = dr 自身 agent-card 可达(启动成功);层 2 = mock 下游上线后,
 * mock 收到 card 拉取请求(cardGetCount>=1,重试触达的直接证据)+ dr stdout 出现 Discovered 日志。
 * 不发 LLM 请求(纯启动/发现面,不依赖模型)。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-004")
@Feature("FEAT-004: 任务驱动远程智能体通信")
@Story("da.remote-card-recovery: 下游不可达时启动容错 + discovery 周期重试恢复(§2/§4)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteAgentCardRecoveryTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long DISCOVERY_RETRY_WINDOW_MS = 75_000; // 重试周期 30s ×2 + 余量

    private SutStack deepStack;
    private MockRemoteAgentServer mock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @AfterAll
    void tearDown() {
        if (deepStack != null) {
            deepStack.close();
        }
        if (mock != null) {
            mock.close();
        }
    }

    @Test
    @DisplayName("FEAT-004.remote-card-recovery: 下游不可达不阻塞启动;下游上线后 discovery 重试触达")
    void startupToleratesUnreachableDownstreamAndRecovers() throws Exception {
        // 预定一个当前无人监听的端口
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        // 层 1:下游不可达时启动 —— SutStack.start() 内部等待 dr agent-card 就绪,启动失败会抛异常
        TestConfig config = TestConfig.load();
        deepStack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", "http://127.0.0.1:" + port)
                        .env("VERIFY_AGENT_URL", "http://127.0.0.1:1"))
                .start();
        String drBase = deepStack.baseUrl(DEEP_RESEARCH);
        HttpResponse<String> card = http.send(
                HttpRequest.newBuilder(URI.create(drBase + "/.well-known/agent-card.json"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(card.statusCode())
                .as("FEAT-004 §2 [层1]: 下游 Card 不可达不得使 runtime 启动失败(url 非空校验通过即启动)")
                .isEqualTo(200);

        // 层 2:在预定端口上线 mock 下游,等 discovery 重试触达
        mock = MockRemoteAgentServer.builder()
                .port(port)
                .name("search-agent")
                .description("SIT mock for card recovery")
                .rawSkillsJson("[{\"id\":\"web-search\",\"name\":\"web-search\","
                        + "\"description\":\"Search the internet.\",\"tags\":[\"search\"]}]")
                .start();

        long deadline = System.currentTimeMillis() + DISCOVERY_RETRY_WINDOW_MS;
        while (System.currentTimeMillis() < deadline && mock.cardGetCount() == 0) {
            Thread.sleep(2000);
        }
        assertThat(mock.cardGetCount())
                .as("FEAT-004 §4 [层2]: 下游上线后 %ds 内 discovery 重试应触达其 card 端点(实测重试周期 30s)",
                        DISCOVERY_RETRY_WINDOW_MS / 1000)
                .isGreaterThanOrEqualTo(1);

        // 层 3(健康度,弱断言):dr stdout 出现 Discovered 日志——路径存在才检查,不作硬前置
        Path drLog = Path.of("target/sit-logs/deep-research/stdout.log");
        if (Files.exists(drLog)) {
            String logText = Files.readString(drLog, StandardCharsets.UTF_8);
            assertThat(logText)
                    .as("[层3 健康度] dr 日志应出现远端发现成功记录")
                    .contains("Discovered remote agent");
        }
    }
}
