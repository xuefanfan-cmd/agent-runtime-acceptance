package com.huawei.ascend.sit.conversation;

import com.huawei.ascend.sit.conversation.mid.dto.StepUI;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 单轮结果：一次自然语言输入 + 自推进循环产出的所有 Step。 */
public record TurnResult(
        String query,
        List<Step> steps,
        StepUI terminalStep,           // STEP_UI 终态；SCRIPT = null
        boolean capped,                // true ⇒ 交互上限熔断：循环未到自然终态就被 maxInteractions 停住（被调业务疑似故障/死循环）
        Duration elapsed) {

    /** 本轮所有 POST 的 SSE 事件聚合。 */
    public List<SseEvent> allEvents() {
        List<SseEvent> all = new ArrayList<>();
        steps.forEach(s -> all.addAll(s.events()));
        return all;
    }
}
