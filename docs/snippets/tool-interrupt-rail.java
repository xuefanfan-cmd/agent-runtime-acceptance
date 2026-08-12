package com.openjiuwen.example.rail;

import java.util.List;
import java.util.Map;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

/** 拦截指定高风险工具，首次执行时中断，恢复输入为 approve/yes 时放行，否则拒绝。 */
public final class ConfirmToolExecutionRail extends BaseInterruptRail {

    public ConfirmToolExecutionRail() {
        super(List.of("write_file"));
    }

    @Override
    protected InterruptDecision resolveInterrupt(
            AgentCallbackContext context, ToolCall toolCall, Object resumeInput) {
        if (resumeInput == null) {
            String arguments = toolCall.getArguments() == null ? "" : toolCall.getArguments();
            return interrupt(InterruptRequest.builder()
                    .message("工具 " + toolCall.getName() + " 即将执行，是否继续？")
                    .context(Map.of("tool", toolCall.getName(), "arguments", arguments))
                    .build());
        }
        String decision = String.valueOf(resumeInput);
        if ("approve".equalsIgnoreCase(decision) || "yes".equalsIgnoreCase(decision)) {
            return approve();
        }
        return reject(Map.of("cancelled", true, "reason", "user rejected tool execution"));
    }
}
