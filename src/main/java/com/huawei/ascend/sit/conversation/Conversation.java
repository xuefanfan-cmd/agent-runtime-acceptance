package com.huawei.ascend.sit.conversation;

import com.huawei.ascend.sit.conversation.mid.MidConversationSupport;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话生命周期：持 conversation_id（经 {@link ConversationIdMapper}），可跨多个 Turn 复用同一 cid。
 * 用 {@link #at(String,String)} 直接指定 网关/中台 base URL（内进程测试用）。
 * 真实栈用 {@link #on(SutStack)} 便捷入口（取 edpa 网关 URL + envmock 中台 URL）。
 */
public final class Conversation implements AutoCloseable {

    private final String gatewayBaseUrl;
    private final MidConversationSupport mid;
    private final ConversationIdentity identity;
    private final ConversationIdMapper mapper;
    private final Duration timeout;
    private final ConversationClient gatewayClient;
    private final String cid;
    private final List<TurnResult> turns = new ArrayList<>();

    private Conversation(Builder b) {
        this.gatewayBaseUrl = b.gatewayBaseUrl;
        this.mid = new MidConversationSupport(b.midBaseUrl);
        this.identity = b.identity;
        this.mapper = b.mapper;
        this.timeout = b.timeout;
        this.gatewayClient = new ConversationClient();
        this.cid = mapper.open(b.requestedCid);
    }

    public String cid() { return cid; }
    public List<TurnResult> turns() { return List.copyOf(turns); }
    public ConversationResult result() { return new ConversationResult(cid, turns()); }
    public Turn turn(String input) { return new Turn(this, input); }
    void recordTurn(TurnResult r) { turns.add(r); }

    // package-private 访问器供 Turn 使用：
    String gatewayBaseUrl() { return gatewayBaseUrl; }
    ConversationIdentity identity() { return identity; }
    Duration timeout() { return timeout; }
    String cidValue() { return cid; }
    ConversationClient gatewayClient() { return gatewayClient; }
    MidConversationSupport mid() { return mid; }

    @Override public void close() {}

    public static Builder at(String gatewayBaseUrl, String midBaseUrl) {
        return new Builder(gatewayBaseUrl, midBaseUrl);
    }

    /**
     * Convenience entry for a real {@link SutStack}: gateway = {@code stack.baseUrl("edpa")},
     * mid-platform = {@code stack.serviceUrl("envmock")}. The stack must already be started and own the
     * {@code envmock} backing service (declared via {@code sut.services.envmock} + a managed agent's
     * {@code service-bindings.envmock}).
     */
    public static Builder on(SutStack stack) {
        return at(stack.baseUrl("edpa"), stack.serviceUrl("envmock"));
    }

    public static final class Builder {
        private final String gatewayBaseUrl, midBaseUrl;
        private ConversationIdentity identity;
        private ConversationIdMapper mapper = ConversationIdMapper.direct();
        private Duration timeout = Duration.ofSeconds(300);
        private String requestedCid;

        private Builder(String g, String m) { this.gatewayBaseUrl = g; this.midBaseUrl = m; }
        public Builder identity(ConversationIdentity i) { this.identity = i; return this; }
        public Builder mapper(ConversationIdMapper m) { this.mapper = m; return this; }
        public Builder timeout(Duration t) { this.timeout = t; return this; }
        public Builder conversationId(String c) { this.requestedCid = c; return this; }
        public Conversation open() {
            if (identity == null) throw new IllegalStateException("identity required");
            return new Conversation(this);
        }
    }
}
