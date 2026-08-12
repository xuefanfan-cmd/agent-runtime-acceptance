package com.openjiuwen.examples.react.agent;

import java.util.List;
import java.util.Map;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;

/** ToolCard（语义契约）+ LocalFunction（本地执行体）。 */
public final class TextStatsTool extends LocalFunction {

    public TextStatsTool() {
        super(ToolCard.builder()
                        .id("text_stats").name("text_stats")
                        .description("统计输入文本的字符数、词数与行数")
                        .inputParams(Map.of(
                                "type", "object",
                                "properties", Map.of("text", Map.of(
                                        "type", "string", "description", "待统计的文本")),
                                "required", List.of("text")))
                        .build(),
                TextStatsTool::execute);
    }

    static Map<String, Object> execute(Map<String, Object> inputs) {
        String text = String.valueOf(inputs.getOrDefault("text", ""));
        long chars = text.codePointCount(0, text.length());
        long words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        long lines = text.isEmpty() ? 0 : text.lines().count();
        return Map.of("chars", chars, "words", words, "lines", lines);
    }
}
