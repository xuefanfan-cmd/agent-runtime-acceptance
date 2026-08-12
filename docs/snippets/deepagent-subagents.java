package com.openjiuwen.example.deepagent;

import java.util.List;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.subagents.SubAgentConfig;

/** 可叠加到 DeepAgentConfig.subagents(...) 的进程内审校子代理声明。 */
public final class DeepAgentSubagents {

    private DeepAgentSubagents() {
    }

    public static List<Object> reviewer() {
        return List.of(SubAgentConfig.builder()
                .agentCard(AgentCard.builder()
                        .name("reviewer")
                        .description("审校交付物文本，指出缺漏并给出修订意见")
                        .build())
                .systemPrompt("审校主代理给出的交付物文本，不直接操作文件。")
                .maxIterations(4)
                .build());
    }
}
