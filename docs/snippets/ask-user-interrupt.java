package com.openjiuwen.example.rail;

import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.harness.rails.interrupt.AskUserRail;
import com.openjiuwen.harness.rails.interrupt.AskUserTool;

/**
 * 叠加片段：为 ReAct/BaseAgent 增加框架内建的结构化追问能力。
 *
 * <p>复制到目标工程的 agent 包并调整 package。此类只完成 core 语义层装配：
 * ToolCard 加入 AbilityManager，AskUserRail 加入 Agent 回调链。
 * runtime 层仍须把 {@link AskUserTool} 执行体注册到 Runner ResourceMgr。
 */
public final class AskUserInterruptSupport {

    private AskUserInterruptSupport() {
    }

    public static AskUserBinding attach(BaseAgent agent) {
        return attach(agent, "cn");
    }

    public static AskUserBinding attach(BaseAgent agent, String language) {
        AskUserTool tool = new AskUserTool(language);
        agent.getAbilityManager().add(tool.getCard());
        agent.registerRail(new AskUserRail());
        return new AskUserBinding(tool, agent.getCard().getId());
    }

    /** 语义层产物：交给 runtime 层注册工具执行体。 */
    public record AskUserBinding(AskUserTool tool, String agentId) {
    }
}
