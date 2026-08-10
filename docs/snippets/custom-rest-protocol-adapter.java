package com.openjiuwen.example.customrest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.springframework.stereotype.Component;

import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;

/**
 * 宿主协议 ↔ A2A 契约的双向转换（本示例的协议约定）：
 *
 * <p>请求：POST /v1/chat/{conversation_id}，body {"input": "...", "stream": false}
 * <br>同步响应：JSON 信封 {conversation_id, state, answer}
 * <br>流式响应：SSE 帧 chunk × N → final，错误统一 error 帧
 *
 * <p>契约要点：message.contextId 必填（业务会话 ID，多轮续传与会话互斥的键）；
 * 正常响应/事件投影必须返回 Jackson 可序列化的非 null 值；错误投影返回 null 时框架使用兜底错误信封。
 */
@Component
public class CustomProtocolAdapter implements CustomRestProtocolAdapter {

    @Override
    public A2ASendCommand toA2ARequest(Context context) {
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart(String.valueOf(context.body().getOrDefault("input", ""))))
                .messageId(UUID.randomUUID().toString())
                // 必填：业务会话 ID。为空 → 400 invalid_custom_request
                .contextId(context.pathVariables().get("conversation_id"))
                .build();
        // 要不要流式由宿主协议字段决定；客户端不接受 SSE 时框架返回 406
        boolean stream = Boolean.parseBoolean(
                String.valueOf(context.body().getOrDefault("stream", "false")));
        return new A2ASendCommand(MessageSendParams.builder().message(message).build(), stream);
    }

    @Override
    public Object fromA2ATask(Task task, Context context) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("conversation_id", context.pathVariables().getOrDefault("conversation_id", ""));
        envelope.put("state", task.status() == null ? "" : String.valueOf(task.status().state()));
        // 演示协议：A2A Task 原样放入信封；生产实现通常在此抽取最终文本
        envelope.put("answer", task);
        return envelope;
    }

    @Override
    public SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context) {
        String type = "chunk";
        if (event instanceof TaskStatusUpdateEvent status && status.isFinalOrInterrupted()) {
            type = status.status().state().isInterrupted() ? "interrupt" : "final";
        }
        return new SseEvent(type, event);
    }

    @Override
    public Object fromError(CustomRestError error, Context context) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("conversation_id", context.pathVariables().getOrDefault("conversation_id", ""));
        envelope.put("error", Map.of("code", error.code(), "message", error.message()));
        return envelope;
    }

    @Override
    public SseEvent fromStreamError(CustomRestError error, Context context) {
        return new SseEvent("error", fromError(error, context));
    }
}
