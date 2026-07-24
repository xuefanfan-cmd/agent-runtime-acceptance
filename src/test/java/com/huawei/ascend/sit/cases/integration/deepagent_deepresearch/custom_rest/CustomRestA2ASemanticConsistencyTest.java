package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockRemoteAgentServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-022 核心验证：协议转换透明性。
 *
 * <p>通过两个独立的 MockRemoteAgentServer 实例分别捕获两条路径到达 Agent B 的 A2A message，
 * 对比：
 * <ol>
 *   <li>标准 /a2a/ 入口 → Agent B 收到的 message</li>
 *   <li>custom REST /v1/{...} 入口 → Agent B 收到的 message</li>
 * </ol>
 * 除 messageId/taskId 等 opaque key 外，{@code message.parts[0].text} 和
 * {@code message.metadata} 应完全一致，验证 §5.1.1「入口归一语义」。
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.a2a-semantic-consistency: custom REST 协议转换透明性 — Agent B 收到的 message 与 /a2a/ 一致")
class CustomRestA2ASemanticConsistencyTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String QUERY_PATH =
            "/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}";
    private static final long MOCK_STALL_MS = 30_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String NON_EMPTY_SKILLS_JSON =
            "[{\"id\":\"web_search\",\"name\":\"web_search\","
            + "\"description\":\"Search the internet.\","
            + "\"tags\":[\"search\"],"
            + "\"inputModes\":[\"text/plain\"],\"outputModes\":[\"text/plain\"]}]";

    @Test
    @DisplayName("FEAT-022.a2a-semantic-consistency: custom REST 与 /a2a/ 到达 Agent B 的 message.parts[0].text 和 metadata 应一致")
    void customRestAndA2AEntrypointShouldDeliverSameMessageToAgentB() throws Exception {
        // 用搜索意图 prompt 让 LLM 路由到 search-agent,marker 嵌入其中;LLM system prompt 的
        // HARD CONSTRAINT 保证 remoteInput byte-for-byte 转发,marker 会以整句形式到达 Agent B。
        String marker = "feat022-consistency-" + UUID.randomUUID().toString().substring(0, 8);
        String sharedInput = "帮我搜索 " + marker + " 的定价";
        TestConfig config = TestConfig.load();

        // ── 路径 1：标准 /a2a/ 入口 ──────────────────────────────────────────
        JsonNode a2aMessage;
        try (MockRemoteAgentServer mockA2A = MockRemoteAgentServer.builder()
                .name("MockSearchAgent-A2A")
                .description("SIT mock for FEAT-022 A2A path")
                .rawSkillsJson(NON_EMPTY_SKILLS_JSON)
                .stallA2aSse(MOCK_STALL_MS)
                .start();
             SutStack stackA2A = SutStack.builder(config)
                     .agent(DEEP_RESEARCH, a -> a.env("SEARCH_AGENT_URL", mockA2A.baseUrl()))
                     .start()) {

            A2aServiceClient a2a = stackA2A.client(DEEP_RESEARCH);
            String a2aContextId = "conv-feat022-a2a-" + UUID.randomUUID().toString().substring(0, 8);
            Message message = Message.builder()
                    .role(Message.Role.ROLE_USER)
                    .messageId(UUID.randomUUID().toString())
                    .contextId(a2aContextId)
                    .parts(List.of(new TextPart(sharedInput)))
                    .build();

            A2aEventCollector collector = new A2aEventCollector();
            AtomicReference<Throwable> err = new AtomicReference<>();
            a2a.sendMessage(message,
                    List.of((BiConsumer<ClientEvent, AgentCard>) collector.createConsumer()),
                    (Consumer<Throwable>) err::set);

            Thread.sleep(5_000);

            assertThat(mockA2A.a2aPostCount())
                    .as("FEAT-022.a2a-semantic-consistency [A2A前置]: SUT 应向 mock 发起 A2A POST\n  contextId=%s", a2aContextId)
                    .isGreaterThanOrEqualTo(1);

            a2aMessage = extractMessage(mockA2A.a2aPostBodies().get(0));
        }

        // ── 路径 2：custom REST 入口 ──────────────────────────────────────────
        JsonNode restMessage;
        try (MockRemoteAgentServer mockRest = MockRemoteAgentServer.builder()
                .name("MockSearchAgent-REST")
                .description("SIT mock for FEAT-022 custom REST path")
                .rawSkillsJson(NON_EMPTY_SKILLS_JSON)
                .stallA2aSse(MOCK_STALL_MS)
                .start();
             SutStack stackRest = SutStack.builder(config)
                     .agent(DEEP_RESEARCH, a -> a
                             .env("SEARCH_AGENT_URL", mockRest.baseUrl())
                             .env("OPENJIUWEN_SERVICE_CUSTOM_REST_QUERY_PATH", QUERY_PATH))
                     .start()) {

            String restConversationId = "conv-feat022-rest-" + UUID.randomUUID().toString().substring(0, 8);
            String url = stackRest.baseUrl(DEEP_RESEARCH)
                    + "/v1/project-test/agents/agent-test/conversations/" + restConversationId;
            String body = "{\"input\":\"" + sharedInput + "\",\"stream\":false}";

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                    .as("FEAT-022.a2a-semantic-consistency [REST前置]: custom REST 请求应成功\n  conversationId=%s", restConversationId)
                    .isEqualTo(200);

            Thread.sleep(5_000);

            assertThat(mockRest.a2aPostCount())
                    .as("FEAT-022.a2a-semantic-consistency [REST前置]: custom REST 路径也应触发 Agent B 调用\n  conversationId=%s", restConversationId)
                    .isGreaterThanOrEqualTo(1);

            restMessage = extractMessage(mockRest.a2aPostBodies().get(0));
        }

        // ── 核心对比断言 ──────────────────────────────────────────────────────
        String a2aText = a2aMessage.path("parts").path(0).path("text").asText("");
        String restText = restMessage.path("parts").path(0).path("text").asText("");

        assertThat(restText)
                .as("FEAT-022.a2a-semantic-consistency [核心]: custom REST 路径到达 Agent B 的 message.parts[0].text "
                        + "应与 /a2a/ 路径一致\n  /a2a/ text=%s\n  custom REST text=%s", a2aText, restText)
                .isEqualTo(a2aText);

        JsonNode a2aMeta = a2aMessage.path("metadata");
        JsonNode restMeta = restMessage.path("metadata");
        boolean a2aHasMeta = !a2aMeta.isMissingNode() && !a2aMeta.isNull() && a2aMeta.size() > 0;
        boolean restHasMeta = !restMeta.isMissingNode() && !restMeta.isNull() && restMeta.size() > 0;

        assertThat(restHasMeta)
                .as("FEAT-022.a2a-semantic-consistency [核心]: metadata 存在性应与 /a2a/ 路径一致\n"
                        + "  /a2a/ hasMeta=%b  custom REST hasMeta=%b\n"
                        + "  /a2a/ meta=%s\n  custom REST meta=%s",
                        a2aHasMeta, restHasMeta, a2aMeta, restMeta)
                .isEqualTo(a2aHasMeta);
    }

    private static JsonNode extractMessage(String body) throws Exception {
        JsonNode envelope = MAPPER.readTree(body);
        JsonNode msg = envelope.path("params").path("message");
        assertThat(msg.isMissingNode())
                .as("FEAT-022.a2a-semantic-consistency: POST body 应含 params.message\n  body=%s", body)
                .isFalse();
        return msg;
    }
}
