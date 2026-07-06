package com.huawei.ascend.sit.conversation;

import com.huawei.ascend.sit.conversation.mid.dto.StepUI;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 单步结果：一次 POST + 其 SSE 流。 */
public record Step(
        int index,
        StepUI stepUi,                 // 本步中台快照；Step0 / SCRIPT = null
        String stepLabel,              // 声明的 step_id 标注（可空）
        Map<String, String> selection, // 本步选择 kv；auto/Step0 = 空
        ConversationRequest request,   // 本步发给 edpa 的报文
        List<SseEvent> events,         // 本步该次 POST 的 SSE 事件
        Duration elapsed) {}
