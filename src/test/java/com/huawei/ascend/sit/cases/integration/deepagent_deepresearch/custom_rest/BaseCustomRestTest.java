package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch.custom_rest;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

abstract class BaseCustomRestTest extends BaseManagedStackTest {

    protected static final String DEEP_RESEARCH = "deep-research";
    static final String PROJECT_ID = "project-test";
    static final String AGENT_ID = "agent-test";
    private static final String QUERY_PATH =
            "/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}";

    protected final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .env("OPENJIUWEN_SERVICE_CUSTOM_REST_QUERY_PATH", QUERY_PATH));
    }

    protected String customRestUrl(String conversationId) {
        return stack.baseUrl(DEEP_RESEARCH)
                + "/v1/" + PROJECT_ID + "/agents/" + AGENT_ID + "/conversations/" + conversationId;
    }

    protected HttpResponse<String> postSync(String conversationId, String input) throws Exception {
        return post(conversationId, input, false, false);
    }

    protected HttpResponse<String> postStreaming(String conversationId, String input) throws Exception {
        return post(conversationId, input, true, true);
    }

    private HttpResponse<String> post(String conversationId, String input,
                                       boolean stream, boolean acceptSse) throws Exception {
        String body = "{\"input\":\"" + input.replace("\"", "\\\"") + "\",\"stream\":" + stream + "}";
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(customRestUrl(conversationId)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120));
        if (acceptSse) {
            req.header("Accept", "text/event-stream");
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
