package com.huawei.ascend.sit.conversation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.utils.JsonUtils;

/**
 * 对话请求报文构建器。逐轮覆盖 query/intent，{@link #toJson()} 产出 EDPA 的 {@code custom_data}
 * 值加上 transport headers——即 {@code {"inputs":{...}, "headers":{...}}}。
 *
 * <p>{@code inputs} 部分：{@code query}/{@code intent} 逐轮覆盖，外加 identity 固定字段。
 * {@code headers} 部分：网关将其提升进 A2A {@code metadata.headers}（按白名单过滤）；{@code stream:"true"}
 * 始终携带（EDPA 调用方总是请求流式回复）。
 *
 * <p>EDPA 全量报文的其余字段（{@code role_name}/{@code agent_id}/{@code role_id}/{@code stream}/
 * {@code conversation_id}/{@code timeout} 以及镜像出的 {@code input.query}）由 edpa-gateway 在入站时
 * 重建：固定身份字段来自网关配置，{@code conversation_id} 来自 URL path，{@code input.query} 来自
 * 此处 {@code inputs.query}。调用方身份（agentId/projectId/workspaceId）仍经 URL 携带，不在 body 内。
 */
public final class ConversationRequest {

    private final String conversationId, timeout, query, intent;
    private final java.util.Map<String, String> fixedInputs;
    private final java.util.Map<String, String> headers;

    private ConversationRequest(Builder b) {
        this.conversationId = b.conversationId;
        this.timeout = b.timeout;
        this.query = b.query;
        this.intent = b.intent;
        this.fixedInputs = b.id.customDataInputs() == null ? java.util.Map.of() : b.id.customDataInputs();
        this.headers = b.headers;
    }

    public String query() { return query; }

    /**
     * Wire form: {@code {"inputs":{...}, "headers":{"stream":"true", ...}}} — the EDPA custom_data
     * value plus transport headers. {@code stream:"true"} is forced last (the gateway always streams).
     */
    public String toJson() {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        ObjectNode inputs = root.putObject("inputs");
        inputs.put("query", query);
        inputs.put("intent", intent == null ? "" : intent);
        fixedInputs.forEach((k, v) -> { if (!k.equals("query") && !k.equals("intent")) inputs.put(k, v); });

        ObjectNode headers = root.putObject("headers");
        this.headers.forEach(headers::put);
        headers.put("stream", "true");   // forced invariant: the gateway always streams the reply

        return JsonUtils.toJson(root);
    }

    public static Builder from(ConversationIdentity id) { return new Builder(id); }

    public static final class Builder {
        private final ConversationIdentity id;
        private String query = "", intent = "", conversationId = "", timeout = "300";
        private final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        private Builder(ConversationIdentity id) { this.id = id; }
        public Builder query(String q) { this.query = q; return this; }
        public Builder intent(String i) { this.intent = i; return this; }
        public Builder conversationId(String c) { this.conversationId = c; return this; }
        public Builder timeout(String t) { this.timeout = t; return this; }
        /** Add a transport header forwarded into A2A {@code metadata.headers} (whitelist-filtered). */
        public Builder header(String name, String value) { this.headers.put(name, value); return this; }
        public ConversationRequest build() { return new ConversationRequest(this); }
    }
}
