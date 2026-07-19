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

    /** Default safety cap on SUT interactions per turn (kick-off + driven rounds). See {@link Turn}. */
    public static final int DEFAULT_MAX_INTERACTIONS = 100;

    private final String gatewayBaseUrl;
    private final MidConversationSupport mid;
    private final ConversationIdentity identity;
    private final ConversationIdMapper mapper;
    private final Duration timeout;
    private final int maxInteractions;
    private final ConversationTransport transport;
    private final String cid;
    private final List<TurnResult> turns = new ArrayList<>();

    private Conversation(Builder b) {
        this.gatewayBaseUrl = b.gatewayBaseUrl;
        this.mid = new MidConversationSupport(b.midBaseUrl);
        this.identity = b.identity;
        this.mapper = b.mapper;
        this.timeout = b.timeout;
        this.maxInteractions = b.maxInteractions;
        this.transport = b.transport != null ? b.transport : new RestVersatileTransport();
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
    /** Safety cap on SUT POSTs per turn (kick-off + driven rounds); default {@link #DEFAULT_MAX_INTERACTIONS}. */
    int maxInteractions() { return maxInteractions; }
    String cidValue() { return cid; }
    ConversationTransport transport() { return transport; }
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
        private int maxInteractions = DEFAULT_MAX_INTERACTIONS;
        private String requestedCid;
        private ConversationTransport transport;

        private Builder(String g, String m) { this.gatewayBaseUrl = g; this.midBaseUrl = m; }
        public Builder identity(ConversationIdentity i) { this.identity = i; return this; }
        public Builder mapper(ConversationIdMapper m) { this.mapper = m; return this; }
        public Builder timeout(Duration t) { this.timeout = t; return this; }
        /**
         * Safety cap on how many SUT POSTs a single turn may make (kick-off + driven rounds). Default
         * {@link #DEFAULT_MAX_INTERACTIONS} (100). A fault in the called business that stops the driving
         * loop from ever reaching a natural terminal (step-ui never complete <em>and</em> next-request
         * never null) would otherwise loop forever; at the cap the turn stops and {@link TurnResult#capped()}
         * is set so a test can detect it instead of hanging. Must be ≥ 1.
         */
        public Builder maxInteractions(int n) {
            if (n < 1) throw new IllegalArgumentException("maxInteractions must be >= 1, got " + n);
            this.maxInteractions = n;
            return this;
        }
        public Builder conversationId(String c) { this.requestedCid = c; return this; }
        /** Override the outbound transport (default: {@link RestVersatileTransport}). Test/future use. */
        public Builder transport(ConversationTransport t) { this.transport = t; return this; }
        public Conversation open() {
            if (identity == null) throw new IllegalStateException("identity required");
            return new Conversation(this);
        }
    }
}
