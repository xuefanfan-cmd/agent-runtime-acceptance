package com.huawei.ascend.sit.conversation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 网关 transport：POST 到 {@code /v1/{project_id}/agents/{agent_id}/conversations/{cid}?type=controller&workspace_id=N}，
 * 消费 text/event-stream，逐行解析进 {@link ConversationEventCollector}，流结束 markStreamEnd()。
 * 使用 JDK {@link HttpClient} + {@link HttpResponse.BodyHandlers#ofLines()}。
 */
public final class ConversationClient {

    private final HttpClient http = HttpClient.newHttpClient();

    public void postConversation(String gatewayBaseUrl, String projectId, String agentId, String cid,
                                 int workspaceId, String jsonBody, ConversationEventCollector collector) {
        String url = gatewayBaseUrl + "/v1/" + projectId + "/agents/" + agentId
                + "/conversations/" + cid + "?type=controller&workspace_id=" + workspaceId;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        try {
            HttpResponse<java.util.stream.Stream<String>> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());
            int status = resp.statusCode();
            if (status != 200) {
                throw new RuntimeException("gateway POST failed: HTTP " + status + " @ " + url);
            }
            resp.body().forEach(line -> {
                SseEvent e = SseEvent.parse(line);
                if (e != null) collector.add(e);
            });
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new RuntimeException("gateway POST error @ " + url, ex);
        } finally {
            collector.markStreamEnd();
        }
    }
}
