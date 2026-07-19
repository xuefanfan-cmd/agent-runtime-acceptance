package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.spec.TaskState;

import java.util.Objects;

/**
 * One neutral inbound signal in an interaction's response stream. Symmetric with the outbound
 * message types ({@code transport/OutboundMessage}, {@code conversation/ConversationOutbound}).
 *
 * <p>{@link Kind#STATE} carries the {@link TaskState} (A2A delivers these natively; REST has them
 * synthesised by {@link SseStateClassifier}). The LLM-interaction payload kinds —
 * {@link Kind#ANSWER} (the agent's final processed result, {@code payload.output}),
 * {@link Kind#LLM_OUTPUT} (streaming output tokens, {@code payload.content}),
 * {@link Kind#LLM_REASONING} (chain-of-thought, {@code payload.content}) and
 * {@link Kind#LLM_USAGE} (token/latency metadata, no text — the payload lives in {@code raw}) —
 * come from the typed {@code {type,index,payload}} envelopes both protocols carry (A2A: a JSON
 * string in the artifact part text; REST: the {@code data} node of an SSE frame).
 * {@link Kind#CONTENT} is the fallback for plain/untyped text (a plain-text artifact or message,
 * or a legacy {@code data.text} frame). {@link Kind#INTERACTION} is the REST SUT's mid-turn
 * "agent needs more info" signal — a typed {@code __interaction__} envelope; its text is the
 * agent's clarifying question, and it maps to the synthesised {@code INPUT_REQUIRED} state (REST
 * has no native task state). {@link Kind#ERROR} carries an error message. {@code raw} is the
 * protocol-native frame escape hatch (e.g. an A2A {@code ClientEvent}, or the parsed payload node)
 * for deep inspection — {@code null} for synthesised events.
 */
public record InboundEvent(
        Kind kind,
        TaskState state,
        String text,
        String taskId,
        String contextId,
        Object raw) {

    public enum Kind { STATE, ANSWER, LLM_OUTPUT, LLM_REASONING, LLM_USAGE, CONTENT, INTERACTION, ERROR }

    public InboundEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        if (kind == Kind.STATE) {
            Objects.requireNonNull(state, "state"); // only STATE carries a non-null state
        }
        taskId = taskId == null ? "" : taskId;
        contextId = contextId == null ? "" : contextId;
    }

    public static InboundEvent state(TaskState state, String taskId, String contextId, Object raw) {
        return new InboundEvent(Kind.STATE, state, "", taskId, contextId, raw);
    }

    /** Synthesised terminal state (no native frame, blank ids). */
    public static InboundEvent state(TaskState state) {
        return state(state, "", "", null);
    }

    /** The agent's final processed result ({@code payload.output}). */
    public static InboundEvent answer(String text, Object raw) {
        return new InboundEvent(Kind.ANSWER, null, text == null ? "" : text, "", "", raw);
    }

    /** Streaming LLM output tokens ({@code payload.content}). */
    public static InboundEvent llmOutput(String text, Object raw) {
        return new InboundEvent(Kind.LLM_OUTPUT, null, text == null ? "" : text, "", "", raw);
    }

    /** Chain-of-thought reasoning ({@code payload.content}). */
    public static InboundEvent llmReasoning(String text, Object raw) {
        return new InboundEvent(Kind.LLM_REASONING, null, text == null ? "" : text, "", "", raw);
    }

    /** Token/latency usage metadata — no text; the parsed payload node is kept in {@code raw}. */
    public static InboundEvent llmUsage(Object raw) {
        return new InboundEvent(Kind.LLM_USAGE, null, "", "", "", raw);
    }

    /** Plain/untyped text fallback (a plain artifact/message, or a legacy {@code data.text} frame). */
    public static InboundEvent content(String text, Object raw) {
        return new InboundEvent(Kind.CONTENT, null, text, "", "", raw);
    }

    /**
     * A mid-turn user-input request — the REST SUT's {@code __interaction__} envelope, the REST
     * equivalent of {@code INPUT_REQUIRED}. Its text is the agent's clarifying question. Synthesises
     * {@code INPUT_REQUIRED} via {@link SseStateClassifier}.
     */
    public static InboundEvent interaction(String text, Object raw) {
        return new InboundEvent(Kind.INTERACTION, null, text == null ? "" : text, "", "", raw);
    }

    public static InboundEvent error(String text, Object raw) {
        return new InboundEvent(Kind.ERROR, null, text, "", "", raw);
    }
}
