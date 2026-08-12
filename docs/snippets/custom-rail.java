package com.openjiuwen.example.rail;

import java.util.List;
import java.util.Map;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

/**
 * 叠加片段：自定义 Rail——终态答案关键词护栏。
 *
 * <p>复制到工程 agent 包（语义层），按目标工程调整 package；复制后注册：
 * ReActAgent：{@code agent.registerRail(new FinalAnswerGuardRail(List.of("敏感词")));}
 * DeepAgent：在调用 HarnessFactory 前经
 * {@code DeepAgentConfig.builder().rails(List.of(new FinalAnswerGuardRail(List.of("敏感词"))))}
 * 声明；DeepAgent 本身没有 registerRail。
 *
 * <p>机制：afterModelCall 钩子里把输入转型为 ModelCallInputs，取响应 AssistantMessage；
 * 无 tool_calls 即终态答案，命中拒绝词时 requestForceFinish 短路 ReAct 循环——
 * invoke 返回的是结构化 Map（含 degraded / reason 键），不是纯字符串。
 */
public class FinalAnswerGuardRail extends AgentRail {

    private final List<String> deniedKeywords;

    public FinalAnswerGuardRail(List<String> deniedKeywords) {
        this.deniedKeywords = List.copyOf(deniedKeywords);
    }

    @Override
    public void afterModelCall(AgentCallbackContext context) {
        if (!(context.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage message)) {
            return;
        }
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            return;   // 工具调用轮不是终态答案
        }
        String output = message.getContentAsString() != null ? message.getContentAsString() : "";
        for (String keyword : deniedKeywords) {
            if (output.contains(keyword)) {
                context.requestForceFinish(Map.of(
                        "output", "",
                        "degraded", true,
                        "reason", "final answer blocked by keyword: " + keyword));
                return;
            }
        }
    }
}
