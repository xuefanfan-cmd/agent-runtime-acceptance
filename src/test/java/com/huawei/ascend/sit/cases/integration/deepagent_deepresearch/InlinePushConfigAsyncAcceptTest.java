package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.inline-push-config-async-accept — SendMessage 内联
 * {@code pushNotificationConfig} 应<b>异步非阻塞返回</b>,不等 handler 跑完.
 *
 * <p><b>Spec 依据</b>(version-scope FEAT-001 2026-07-24 + L2 2026-07-25):
 * <ul>
 *   <li>version-scope §2 能力表:「SendMessage 支持内联 pushNotificationConfig 携带,SUT 应立即
 *     返回 Task 骨架(非阻塞语义),后续状态迁移由 callback 交付」——原 config CRUD 4 个 method
 *     显式下线,SendMessage 内联参数是唯一入口。</li>
 *   <li>L2 §2.7 callback receiver 契约的前提是 SUT 侧不再"卡住 sendMessage" 等 Task 完成。</li>
 * </ul>
 *
 * <p><b>用例形态</b>:
 * <ol>
 *   <li>POST {@code SendMessage} + {@code params.pushNotificationConfig={url,token}}(SIT
 *     placeholder URL,SUT 不应尝试连接);</li>
 *   <li>计时 response 返回耗时,应显著低于完整 handler 时长(设 {@link #ASYNC_RESPONSE_BUDGET_MS} 上限);</li>
 *   <li>response 应含 result 且是一个 Task 骨架,状态在 {@code TASK_STATE_SUBMITTED /
 *     TASK_STATE_WORKING / TASK_STATE_INPUT_REQUIRED} 之一(<b>非</b> {@code COMPLETED},
 *     因为不阻塞 handler);允许 SUT 立即返 {@code WORKING}。</li>
 * </ol>
 *
 * <p><b>为什么允许 INPUT_REQUIRED</b>:内联 config 情况下如果首轮 prompt 缺项 agent 可能在
 * 内联阶段就走 ask_user,这是<b>非阻塞</b>的另一表现形态,不应判 FAIL。相反,如果 SUT
 * 直接返 {@code COMPLETED},说明 SUT <b>阻塞等 handler 跑完</b>,违反 spec 非阻塞承诺。
 *
 * <p><b>用底层 HTTP</b>:SDK 1.0.0.Final 的 {@code Message.Builder} 未必暴露
 * {@code pushNotificationConfig} 字段(spec 变更晚于 SDK 版本),走 raw JSON 直发最贴合契约。
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.inline-push-config-async-accept: SendMessage 内联 pushNotificationConfig 应非阻塞返回")
class InlinePushConfigAsyncAcceptTest extends BaseManagedStackTest {

    /**
     * <b>为什么打 search 而不是 deep-research</b>:deep-research jar 0.1.0 的 remote-agents 严校验
     * 让 startup 拓扑复杂,且其 SendMessage 参数 schema 对未知 field 更严;search agent 是同一构建
     * 家族里最小的 A2A 服务实体,能干净地探"runtime 是否实现 v2 spec 内联 pushNotificationConfig"。
     * spec §2 对内联 config 的要求是<b>runtime 级别</b>,不特化 deep-research —— search agent 若也
     * 未实现,即 SUT 家族整体尚未落地 v2 inline 入口。
     */
    private static final String SEARCH = "search";
    private static final String PLACEHOLDER_WEBHOOK_URL = "http://sit-placeholder.example/webhook";

    /**
     * 非阻塞返回时间预算 —— 完整 handler(LLM 至少 5s+)绝不会在这个窗口内跑完,
     * response < 15s 且状态非 COMPLETED 才是"非阻塞立即回骨架"的证据。
     */
    private static final long ASYNC_RESPONSE_BUDGET_MS = 15_000;

    /** COMPLETED 说明阻塞等完,违约。非终态是非阻塞证据。 */
    private static final List<String> ACCEPTABLE_INITIAL_STATES = List.of(
            "TASK_STATE_SUBMITTED",
            "TASK_STATE_WORKING",
            "TASK_STATE_INPUT_REQUIRED",
            "submitted",
            "working",
            "input-required");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // search agent 无 remote-agents 依赖,直接起;显式打开 push-notifications 让 capabilities 声明"支持",
        // 从而覆盖真实的 inline pushNotificationConfig 处理路径(否则 SUT 可能因 capability=false 直接短路)。
        //
        // search agent jar 0.1.0 的 application.yml 里 openjiuwen.demo.search-agent.api-key
        // 未绑 ${LLM_API_KEY:} env 占位(deep-research 绑了) —— SUT jar 侧遗漏,需由 test 侧强注。
        // @ConfigurationProperties(prefix="openjiuwen.demo.search-agent") + Java 字段 apiKey
        // → 走 relaxed binding,property key 为 openjiuwen.demo.search-agent.api-key。
        // 值取自 ~/.llmrc 的 LLM_API_KEY env,不硬编码到源码。
        String llmApiKey = System.getenv("LLM_API_KEY");
        return SutStack.builder(config)
                .agent(SEARCH, a -> a
                        .env("SEARCH_AGENT_PUSH_NOTIFICATIONS", "true")
                        .property("openjiuwen.demo.search-agent.api-key", llmApiKey));
    }

    @Test
    @DisplayName("FEAT-001.inline-push-config-async-accept: 内联 config → 快速回 Task 骨架 + 非 COMPLETED")
    void inlinePushConfigReturnsAsyncTaskSkeleton() throws Exception {
        String contextId = "ctx-inline-push-" + UUID.randomUUID().toString().substring(0, 8);
        String messageId = UUID.randomUUID().toString();
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
        String configToken = "sit-token-" + UUID.randomUUID().toString().substring(0, 8);

        // A2A SDK 1.0.0.Final schema: MessageSendParams { message, configuration, metadata, tenant },
        // 其中 configuration = MessageSendConfiguration { ..., taskPushNotificationConfig, returnImmediately }。
        // TaskPushNotificationConfig.Builder.build() 里 SDK Assert 强制要求 id + url 都非 null
        // (checkNotNullParam),所以 id 必须显式提供 —— SDK-strict,不是 SUT 强加的。
        // returnImmediately=true 是显式请求非阻塞的 flag;不设 SUT 有权同步返 COMPLETED。
        String body = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"inline-push-%s\","
                        + "\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{"
                        + "\"role\":\"ROLE_USER\","
                        + "\"messageId\":\"%s\","
                        + "\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"帮我查一下 DeepSeek-R1 官方定价\"}]"
                        + "},"
                        + "\"configuration\":{"
                        + "\"taskPushNotificationConfig\":{"
                        + "\"id\":\"%s\","
                        + "\"url\":\"%s\","
                        + "\"token\":\"%s\""
                        + "},"
                        + "\"returnImmediately\":true"
                        + "}}}",
                UUID.randomUUID().toString().substring(0, 8),
                messageId,
                contextId,
                configId,
                PLACEHOLDER_WEBHOOK_URL,
                configToken);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = post("/a2a", body);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(response.statusCode())
                .as("FEAT-001.inline-push-config: HTTP status 应为 200\nbody=%s", response.body())
                .isEqualTo(200);

        assertThat(elapsed)
                .as("FEAT-001.inline-push-config: response 应在 %d ms 内到达(非阻塞承诺),实测 %d ms\n"
                                + "若超时,说明 SUT 卡住等 handler 跑完 → 违反 spec §2 内联异步语义\nbody=%s",
                        ASYNC_RESPONSE_BUDGET_MS, elapsed, response.body())
                .isLessThanOrEqualTo(ASYNC_RESPONSE_BUDGET_MS);

        JsonNode node = mapper.readTree(response.body());
        assertThat(node.has("error"))
                .as("FEAT-001.inline-push-config: 不应返 error(SUT 应接受内联 config)\nbody=%s", response.body())
                .isFalse();

        JsonNode result = node.path("result");
        assertThat(result.isMissingNode() || result.isNull())
                .as("FEAT-001.inline-push-config: 应含 result 节点(Task 骨架)\nbody=%s", response.body())
                .isFalse();

        String state = extractState(result);
        assertThat(state)
                .as("FEAT-001.inline-push-config: result 应含 status.state\nresult=%s", result)
                .isNotBlank();

        assertThat(ACCEPTABLE_INITIAL_STATES)
                .as("FEAT-001.inline-push-config: 初始 state 应为非终态(submitted/working/input-required),"
                                + "实测 '%s'。若为 COMPLETED,说明 SUT 阻塞等完 handler,违约。\nresult=%s",
                        state, result)
                .contains(state);
    }

    /**
     * A2A SDK 1.0.0.Final 里 Task 的 status.state 字段序列化可能是
     * {@code "TASK_STATE_WORKING"} enum 名或 {@code "working"} kebab —— 两种都接受。
     *
     * <p>SUT 实测 response 形态是 {@code result.task.status.state}(SDK 里 TaskEvent
     * 序列化把 Task 包在 {@code task} 字段下,即使 non-streaming 也一样),
     * 兼容原有 {@code result.status.state} / {@code result.state} 直接暴露的形态。
     */
    private static String extractState(JsonNode result) {
        JsonNode taskStatusState = result.path("task").path("status").path("state");
        if (taskStatusState.isTextual()) return taskStatusState.asText();
        JsonNode statusState = result.path("status").path("state");
        if (statusState.isTextual()) return statusState.asText();
        JsonNode topState = result.path("state");
        if (topState.isTextual()) return topState.asText();
        return "";
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stack.baseUrl(SEARCH) + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
