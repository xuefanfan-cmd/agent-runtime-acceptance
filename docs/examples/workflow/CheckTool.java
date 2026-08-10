package com.openjiuwen.examples.workflow;

import java.util.List;
import java.util.Map;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;

/**
 * 最小工具实现：LocalFunction = ToolCard（id/描述/输入 JSON Schema）+ execute 函数。
 * 返回 Map；接入 ToolComponent 后，下游经 ${check.data.risk} 引用
 * （非 RESTful 工具返回被框架包在 data 键下）。
 */
public final class CheckTool extends LocalFunction {

    public CheckTool() {
        super(ToolCard.builder()
                        .id("check").name("check")
                        .description("校验输入并给出风险等级")
                        .inputParams(Map.of(
                                "type", "object",
                                "properties", Map.of("total", Map.of(
                                        "type", "number", "description", "合计")),
                                "required", List.of("total")))
                        .build(),
                CheckTool::execute);
    }

    static Map<String, Object> execute(Map<String, Object> inputs) {
        double total = ((Number) inputs.getOrDefault("total", 0)).doubleValue();
        return Map.of("risk", total > 1000 ? "high" : "none");
    }
}
