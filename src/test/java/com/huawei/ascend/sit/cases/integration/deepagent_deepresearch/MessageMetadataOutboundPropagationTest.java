package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

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
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * issue-52 完整链路 watchdog —— 同时覆盖根因 1（入站）和根因 2（出站）。
 *
 * <p><b>根因 1</b>: {@code A2aJsonRpcController.buildMessage} L214-231 不读
 * {@code params.message.metadata}，导致 runtime 里 {@code Message.metadata=null}。
 *
 * <p><b>根因 2</b>: {@code A2ARemoteAgentClient.prepareCall} L126-135 不写
 * {@code msgBuilder.metadata(...)}，导致转发给下游 remote agent 的
 * {@code params.message.metadata} 丢失。
 *
 * <p><b>为什么一个用例覆盖两个根因</b>: 拓扑为
 * {@code [Test] --metadata--> [deep-research] --metadata--> [mock search-agent]}，
 * 只有**根因 1 和根因 2 都修复**时，mock 才能收到我们发的 marker：
 * <ul>
 *   <li>根因 1 未修复 → 入站 metadata 被丢 → 即使根因 2 修了，下游也收不到 → RED</li>
 *   <li>根因 1 已修复 + 根因 2 未修复 → metadata 被接收但出站时丢 → 下游收不到 → RED</li>
 *   <li>两个根因都修复 → 下游收到完整 marker → GREEN</li>
 * </ul>
 *
 * <p><b>观测面</b>: deep-research 调用下游 search-agent 时，通过 mock 的
 * {@code a2aPostBodies()} 捕获 wire 上的 JSON body。如果两个根因都已修复，body 里
 * {@code params.message.metadata} 应含我们发给 deep-research 的 marker；如果任一根因复现，
 * 则 {@code params.message.metadata} 为 null/{}。
 *
 * <p><b>用例设计</b>:
 * <ul>
 *   <li><b>拓扑</b>: 本地起 deep-research (managed) + mock search-agent (通过 SEARCH_AGENT_URL
 *       env 注入 mock baseUrl)</li>
 *   <li><b>Send</b>: 发 message.metadata 带唯一 marker (UUID) 到 deep-research (覆盖根因 1)</li>
 *   <li><b>Mock</b>: search-agent mock 返回非空 skills + stall SSE (让 SUT 有足够时间发起下游调用)</li>
 *   <li><b>Assert</b>: 从 mock.a2aPostBodies() 里解析第一个 POST body，断言
 *       {@code params.message.metadata["x-test-marker"]} == 我们发的 marker (覆盖根因 1 + 根因 2):
 *       <ul>
 *         <li>命中 → 两个根因都已修复 (GREEN)</li>
 *         <li>缺失 → 至少有一个根因复现 (RED)</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p><b>红/绿含义</b>:
 * <ul>
 *   <li>GREEN — buildMessage 和 prepareCall 都已修复 metadata 透传，或此前未回归</li>
 *   <li>RED — issue-52 至少一个根因复现: 入站丢失 或 出站丢失</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.message-metadata-end-to-end: params.message.metadata 须完整透传 (issue-52 根因 1 + 根因 2)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageMetadataOutboundPropagationTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final long SEND_TIMEOUT_MS = 180_000;
    private static final long MOCK_STALL_MS = 30_000;

    /** 非空 skills —— 让 tool spec 正常装配，SUT 会调下游 */
    private static final String NON_EMPTY_SKILLS_JSON =
            "[{\"id\":\"web_search\","
            + "\"name\":\"web_search\","
            + "\"description\":\"Search the internet and return result summaries.\","
            + "\"tags\":[\"search\"],"
            + "\"inputModes\":[\"text\",\"text/plain\"],"
            + "\"outputModes\":[\"text\",\"text/plain\"]}]";

    private TestConfig config;
    private MockRemoteAgentServer mock;
    private SutStack deepStack;

    @BeforeAll
    void startStack() throws IOException {
        config = TestConfig.load();
        mock = MockRemoteAgentServer.builder()
                .name("MockSearchAgent")
                .description("SIT mock for issue-52 root cause 2")
                .rawSkillsJson(NON_EMPTY_SKILLS_JSON)
                .stallA2aSse(MOCK_STALL_MS)
                .start();

        deepStack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a.env("SEARCH_AGENT_URL", mock.baseUrl()))
                .start();
    }

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
    @DisplayName("FEAT-001.message-metadata-end-to-end: 完整透传 message.metadata (issue-52 根因 1 入站 + 根因 2 出站)")
    void messageMetadataMustBePropagatedEndToEnd() throws Exception {
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        String marker = "issue52-root2-" + UUID.randomUUID();
        String contextId = "ctx-issue52-root2-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> messageMetadata = new HashMap<>();
        messageMetadata.put("x-test-marker", marker);
        messageMetadata.put("x-test-source", "feat001-issue52-outbound");

        // 明确 route 到下游 search 的 prompt
        String userInput = "帮我搜索 2026 年 7 月全球黄金价格盘中最高价";

        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(userInput)))
                .metadata(messageMetadata)
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = sendError::set;

        a2a.sendMessage(message, consumers, errorHandler);

        if (sendError.get() != null) {
            fail("issue-52 根因 2: sendMessage 失败", sendError.get());
        }

        // 不等终态(远端一直 stall SSE,LLM 兜底汇总要走完整轮 —— issue-52 只关心出站 wire body,
        // sleep 足够时间让 SUT 完成 card fetch + 发起 A2A POST 即可)
        Thread.sleep(5000);

        // 层 1 前置: SUT 应至少拉过一次 card 且发起过一次 A2A POST
        assertThat(mock.cardGetCount())
                .as("issue-52 根因 2 [前置]: SUT 应至少拉取 mock 的 card；若为 0 说明 SEARCH_AGENT_URL 未生效"
                        + "\n  contextId=%s\n  mock.baseUrl=%s", contextId, mock.baseUrl())
                .isGreaterThanOrEqualTo(1);
        assertThat(mock.a2aPostCount())
                .as("issue-52 根因 2 [前置]: SUT 应至少向 mock /a2a 发起一次 POST；若为 0 说明 tool 未装配或 planner 未 route"
                        + "\n  contextId=%s\n  mock.baseUrl=%s",
                        contextId, mock.baseUrl())
                .isGreaterThanOrEqualTo(1);

        // 核心断言: 解析第一个 POST body，检查 params.message.metadata["x-test-marker"]
        List<String> bodies = mock.a2aPostBodies();
        assertThat(bodies)
                .as("issue-52 根因 2: mock 应捕获至少一个 POST body\ncontextId=%s", contextId)
                .isNotEmpty();

        String firstBody = bodies.get(0);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode envelope = mapper.readTree(firstBody);

        JsonNode params = envelope.path("params");
        assertThat(params.isMissingNode())
                .as("issue-52 根因 2: POST body 应含 params\nbody=%s", firstBody)
                .isFalse();

        JsonNode messageNode = params.path("message");
        assertThat(messageNode.isMissingNode())
                .as("issue-52 根因 2: params 应含 message\nbody=%s", firstBody)
                .isFalse();

        JsonNode metadata = messageNode.path("metadata");
        boolean present = metadata != null && !metadata.isMissingNode() && !metadata.isNull()
                && metadata.isObject() && metadata.size() > 0;

        assertThat(present)
                .as("issue-52 完整链路复现: SUT 入站 (A2aJsonRpcController.buildMessage L214-231) "
                        + "或出站 (A2ARemoteAgentClient.prepareCall L126-135) 未透传 params.message.metadata。\n"
                        + "  【根因 1 - 入站】buildMessage 缺 .metadata() 调用 → 入站 metadata 被丢 → 下游收不到\n"
                        + "  【根因 2 - 出站】prepareCall 里 msgBuilder 缺 .metadata() → 出站时丢失 → 下游收不到\n"
                        + "  发送的 metadata: {x-test-marker=%s, x-test-source=%s}\n"
                        + "  下游收到的 message.metadata: %s\n"
                        + "  message 全文: %s\n"
                        + "  如为 null / 缺失 / {}，说明至少一个根因复现。",
                        marker, "feat001-issue52-outbound", metadata, messageNode)
                .isTrue();

        assertThat(metadata.path("x-test-marker").asText(""))
                .as("issue-52 根因 2: 下游 message.metadata['x-test-marker'] 应回显 '%s'\nmetadata=%s",
                        marker, metadata)
                .isEqualTo(marker);
    }
}
