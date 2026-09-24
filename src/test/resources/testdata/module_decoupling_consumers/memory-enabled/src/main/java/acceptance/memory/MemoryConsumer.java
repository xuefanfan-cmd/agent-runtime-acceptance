package acceptance.memory;

import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.spi.memory.MemoryRuntimeResolver;

import java.util.Map;

public final class MemoryConsumer {
    private MemoryConsumer() {
    }

    public static void main(String[] args) {
        String query = args.length > 0 ? args[0] : "memory-decoupling";
        DeepAgentConfig config = DeepAgentConfig.builder()
                .isTaskLoopEnabled(false)
                .isTaskPlanningEnabled(false)
                .addGeneralPurposeAgent(false)
                .enableSkillDiscovery(false)
                .workspacePath("target/consumer-workspace")
                .build();
        try (DeepAgent agent = HarnessFactory.createDeepAgent(config)) {
            Map<String, Object> result = agent.invoke(Map.of(
                    "query", query,
                    "conversation_id", "memory-decoupling-session"));
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = (Map<String, Object>) result.get("inputs");
            System.out.println("MEMORY_RUNTIME=" + (MemoryRuntimeResolver.find().isPresent() ? "present" : "absent"));
            System.out.println("AGENT_NAME=" + result.get("agent_name"));
            System.out.println("MODE=" + result.get("mode"));
            System.out.println("QUERY=" + inputs.get("query"));
            System.out.println("STATUS=AGENT_OK");
        }
    }
}

