package com.huawei.ascend.sit.conversation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.ascend.sit.utils.TestDataLoader;
import java.util.Map;

/**
 * 固定的调用方身份（前端固定字段），可跨多轮/多 Turn 复用。从 testdata JSON 加载默认值。
 * spec §7 #4：真实取值需对齐。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversationIdentity(
        String roleName,
        String agentId,
        String roleId,
        String projectId,
        int workspaceId,
        Map<String, String> customDataInputs) {

    public static final String DEFAULT_TESTDATA_PATH = "component/conversation/conversation-identity.json";

    public static ConversationIdentity loadDefault() {
        return TestDataLoader.load(DEFAULT_TESTDATA_PATH, ConversationIdentity.class);
    }
}
