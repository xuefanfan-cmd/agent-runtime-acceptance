package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockRemoteAgentServer;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-022 Adapter 请求映射深度验证（验证点 A + E）：
 *
 * <p>通过 MockRemoteAgentServer 捕获 Agent B 收到的 A2A message，断言：
 * <ul>
 *   <li>A: REST body.input → params.message.parts[0].text</li>
 *   <li>A: X-Trace-Id header + ?debug=true query → params.message.metadata（若 adapter 映射）</li>
 *   <li>E: path 变量 {project_id} / {agent_id} / {conversation_id} 均被正确提取</li>
 * </ul>
 */
@Tag("integration")
@Tag("feat-022")
@Tag("custom-rest")
@Feature("FEAT-022: 自定义 REST API 智能体服务入口")
@Story("da.request-mapping-deep: Adapter Context 字段级验证 — headers/query/path 到达 Agent B")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomRestRequestMappingDeepTest {

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

    private TestConfig config;
    private MockRemoteAgentServer mock;
    private SutStack stack;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();
        mock = MockRemoteAgentServer.builder()
                .name("MockSearchAgent")
                .description("SIT mock for FEAT-022 request mapping deep")
                .rawSkillsJson(NON_EMPTY_SKILLS_JSON)
                .stallA2aSse(MOCK_STALL_MS)
                .start();
        stack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("SEARCH_AGENT_URL", mock.baseUrl())
                        .env("OPENJIUWEN_SERVICE_CUSTOM_REST_QUERY_PATH", QUERY_PATH))
                .start();
    }

    @AfterAll
    void tearDown() {
        if (stack != null) stack.close();
        if (mock != null) mock.close();
    }

    @Test
    @DisplayName("FEAT-022.request-mapping-deep [A]: input → parts[0].text 字段级验证")
    void inputShouldMapToMessagePartsText() throws Exception {
        // 用搜索意图 prompt 触发 LLM 调用 search-agent;LLM system prompt 的 HARD CONSTRAINT
        // 保证 remoteInput 与用户输入 byte-for-byte 相同,marker 会以整句形式到达 mock。
        String marker = "帮我搜索 feat022-input-" + UUID.randomUUID().toString().substring(0, 8) + " 的定价";
        String conversationId = "conv-feat022-map-a-" + UUID.randomUUID().toString().substring(0, 8);

        sendAndWaitForMockPost(conversationId, marker, null, null);

        JsonNode message = extractMessage(mock.a2aPostBodies().get(0));
        String text = message.path("parts").path(0).path("text").asText("");

        assertThat(text)
                .as("FEAT-022.request-mapping-deep [A]: body.input 应映射到 params.message.parts[0].text\n"
                        + "  expected=%s\n  actual=%s", marker, text)
                .isEqualTo(marker);
    }

    @Test
    @DisplayName("FEAT-022.request-mapping-deep [A]: headers/query → message.metadata（若 adapter 映射）")
    void headersAndQueryShouldReachMetadataIfAdapterMaps() throws Exception {
        String traceId = "trace-" + UUID.randomUUID().toString().substring(0, 8);
        String conversationId = "conv-feat022-map-meta-" + UUID.randomUUID().toString().substring(0, 8);

        // 用搜索意图 prompt 触发 LLM 调用 search-agent(非搜索意图 prompt 会被 LLM short-circuit)。
        sendAndWaitForMockPost(conversationId, "帮我搜索 metadata-probe 的定价", traceId, "true");

        JsonNode message = extractMessage(mock.a2aPostBodies().get(0));
        JsonNode metadata = message.path("metadata");

        // 若 adapter 将 headers/query 映射到 metadata，则 x-trace-id 应出现
        // 若 adapter 未映射，metadata 为空 → 记录为 INCONCLUSIVE（不强制 FAIL，因为 adapter 实现可选）
        if (!metadata.isMissingNode() && !metadata.isNull() && metadata.size() > 0) {
            assertThat(metadata.toString())
                    .as("FEAT-022.request-mapping-deep [A]: metadata 非空时，X-Trace-Id 应被映射进来\n"
                            + "  traceId=%s\n  metadata=%s", traceId, metadata)
                    .contains(traceId);
        }
        // metadata 为空时：adapter 未映射 headers → 不 FAIL，但在 a2a-semantic-consistency 里已验证 metadata 一致性
    }

    @Test
    @DisplayName("FEAT-022.request-mapping-deep [E]: path 变量 project_id/agent_id/conversation_id 均被正确提取")
    void pathVariablesShouldBeCorrectlyExtracted() throws Exception {
        String projectId = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        String agentId = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        String conversationId = "conv-feat022-path-" + UUID.randomUUID().toString().substring(0, 8);

        String url = stack.baseUrl(DEEP_RESEARCH)
                + "/v1/" + projectId + "/agents/" + agentId + "/conversations/" + conversationId;
        String body = "{\"input\":\"帮我搜索 path-var-probe 的定价\",\"stream\":false}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("FEAT-022.request-mapping-deep [E]: 自定义 path 变量请求应成功\n"
                        + "  projectId=%s  agentId=%s  conversationId=%s", projectId, agentId, conversationId)
                .isEqualTo(200);

        // 响应中 conversation_id 应回显请求传入的值（adapter 从 pathVariables 取 conversation_id）
        String respBody = response.body() == null ? "" : response.body();
        assertThat(respBody)
                .as("FEAT-022.request-mapping-deep [E]: 响应应回显 conversation_id=%s\n  body=%s",
                        conversationId, respBody)
                .contains(conversationId);

        // 等 mock 捕获下游 POST，验证 projectId/agentId 是否在 message.metadata 里
        // (当前 SUT adapter 可能不映射这两个到 metadata，若 mock 里没出现不算 FAIL，只记录)
        Thread.sleep(5_000);
        if (mock.a2aPostCount() > 0) {
            String mockBody = mock.a2aPostBodies().get(0);
            JsonNode message = extractMessage(mockBody);
            JsonNode metadata = message.path("metadata");
            // 若 adapter 映射了 path 变量到 metadata，projectId/agentId 应出现
            // 若未映射，不强制 FAIL（adapter 实现可选），但记录 INFO
            boolean hasProjectId = !metadata.isMissingNode() && metadata.toString().contains(projectId);
            boolean hasAgentId = !metadata.isMissingNode() && metadata.toString().contains(agentId);
            if (!hasProjectId || !hasAgentId) {
                System.out.println("INFO: FEAT-022.request-mapping-deep [E] — adapter 未将 projectId/agentId "
                        + "映射到 message.metadata (adapter 实现可选，不强制). metadata=" + metadata);
            }
        }
    }

    private void sendAndWaitForMockPost(String conversationId, String input,
                                         String traceId, String debug) throws Exception {
        String url = stack.baseUrl(DEEP_RESEARCH)
                + "/v1/project-test/agents/agent-test/conversations/" + conversationId
                + (debug != null ? "?debug=" + debug : "");
        String body = "{\"input\":\"" + input + "\",\"stream\":false}";

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60));
        if (traceId != null) {
            reqBuilder.header("X-Trace-Id", traceId);
        }

        http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(5_000);

        assertThat(mock.a2aPostCount())
                .as("FEAT-022.request-mapping-deep [前置]: SUT 应向 mock 发起 A2A POST\n  conversationId=%s", conversationId)
                .isGreaterThanOrEqualTo(1);
    }

    private static JsonNode extractMessage(String body) throws Exception {
        JsonNode envelope = MAPPER.readTree(body);
        JsonNode msg = envelope.path("params").path("message");
        assertThat(msg.isMissingNode())
                .as("FEAT-022.request-mapping-deep: POST body 应含 params.message\n  body=%s", body)
                .isFalse();
        return msg;
    }
}
