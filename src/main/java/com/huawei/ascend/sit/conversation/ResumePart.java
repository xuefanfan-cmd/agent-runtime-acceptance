package com.huawei.ascend.sit.conversation;

/**
 * One parallel child's resume input for a batch round: the routing {@code toolCallId} (the child
 * member's remote-invocation id) and the child's resume {@code query} text. The adapter renders each
 * part onto the A2A {@code Message.parts} with {@code metadata.toolCallId} so the runtime routes it.
 */
public record ResumePart(String toolCallId, String query) {
}
