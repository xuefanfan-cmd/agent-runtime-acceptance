package com.huawei.ascend.sit.conversation;

import java.util.List;

/** 对话级结果：cid + 累计的各 Turn 结果。 */
public record ConversationResult(String cid, List<TurnResult> turns) {}
