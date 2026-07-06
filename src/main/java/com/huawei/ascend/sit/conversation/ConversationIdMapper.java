package com.huawei.ascend.sit.conversation;

import java.util.UUID;

/**
 * conversation_id 映射 SPI。open(requestedCid) 返回用于 edpa 网关与中台的 cid。
 * spec §5.3：direct() 已实现（高码 cid == 中台 cid）；split() 1:N 拆分预留（接口未定义）。
 */
public interface ConversationIdMapper {

    String open(String requestedCid);

    /** 直连 1:1：返回入参；入参空则生成 UUID。 */
    static ConversationIdMapper direct() {
        return new DirectConversationIdMapper();
    }

    // 预留：static ConversationIdMapper split(...) —— 高码拆子 cid、SSE 按子会话返回；接口待定义。

    final class DirectConversationIdMapper implements ConversationIdMapper {
        @Override public String open(String requestedCid) {
            return (requestedCid == null || requestedCid.isBlank()) ? UUID.randomUUID().toString() : requestedCid;
        }
    }
}
