package com.huawei.ascend.sit.client;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;

import java.util.List;

/**
 * Shared readable-text extraction from A2A {@link Task} snapshots.
 *
 * <p>Used by protocol tests (A-04, A-05, A-07) so send/get comparisons use the same rules.
 * Priority: artifacts → status message → last history entry.</p>
 */
public final class TaskTextExtractor {

    private TaskTextExtractor() {
    }

    /** Extract concatenated text from a task snapshot; result is trimmed. */
    public static String textOf(Task task) {
        if (task == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) {
                appendText(sb, artifact.parts());
            }
        }
        if (sb.length() == 0 && task.status() != null && task.status().message() != null) {
            appendText(sb, task.status().message().parts());
        }
        if (sb.length() == 0 && task.history() != null && !task.history().isEmpty()) {
            appendText(sb, task.history().get(task.history().size() - 1).parts());
        }
        return sb.toString().trim();
    }

    private static void appendText(StringBuilder sb, List<Part<?>> parts) {
        if (parts == null) {
            return;
        }
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                sb.append(textPart.text());
            }
        }
    }
}
