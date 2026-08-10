package com.openjiuwen.examples.deepagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.TaskCompletionRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

/**
 * 最小完整闭环：DeepAgent 任务循环（TaskCompletionRail 驱动）+ 受限工作区文件工具 + 托管。
 * 命名中性化，只演示框架能力，不含业务逻辑。
 *
 * <p>配套文件：application.yml（服务端点、LLM 与工作区路径）、WorkspaceFileTools.java（受限文件工具）。
 */
@Configuration(proxyBeanMethods = false)
public class DeepAgentConfiguration {

    /** DeepAgent 持有工作区资源：随 Spring 容器关闭时释放。 */
    @Bean(destroyMethod = "close")
    DeepAgent notesDeepAgent(
            @Value("${deep.api-key:}") String apiKey,
            @Value("${deep.api-base:}") String apiBase,
            @Value("${deep.model-name:gpt-4o-mini}") String modelName,
            @Value("${deep.workspace-path:./data/deep-workspace}") String workspacePath) {
        Path root = Path.of(workspacePath).toAbsolutePath().normalize();

        // 1) 完成判定 Rail：交付物就绪前持续任务循环；指令中 {query} 会被当前请求替换
        TaskCompletionRail completionRail = new TaskCompletionRail(
                "持续维护工作区交付物。根据当前请求创建或更新文件；当前请求如下：\n{query}",
                "ARTIFACTS_READY",
                1, false, 3, Duration.ofSeconds(300), List.of());

        // 2) DeepAgentConfig：任务循环 + 工作区 + 受限文件工具 + 模型（model/backend 为 Map 传递）
        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt("你是交付物维护助手。只维护工作区内的 Markdown 文件，"
                        + "写入完整内容后复读检查，确认一致再发出完成信号。")
                .maxIterations(8)
                .enableTaskLoop(true)
                .completionTimeout(300.0)
                .workspacePath(root.toString())
                .language("cn")
                .restrictToWorkDir(true)
                .tools(WorkspaceFileTools.create(root))
                .rails(List.of(completionRail))
                .model(Map.of("model", modelName, "temperature", 0.1, "top_p", 0.8))
                .backend(Map.of("provider", "OpenAI", "api_key", apiKey,
                        "api_base", apiBase, "verify_ssl", true, "timeout", 120L))
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(root.toString())
                .language("cn")
                .build();
        AgentCard card = AgentCard.builder()
                .id("notes-deep").name("notes-deep")
                .description("任务循环 + 受限工作区文件工具")
                .build();

        // 3) 官方工厂装配（Workspace 初始化、默认 Rail 注入、工具注册一次完成）
        DeepAgent agent = HarnessFactory.createDeepAgent(card, config, workspace);
        agent.ensureInitialized();
        return agent;
    }

    @Bean
    AgentHandler deepHandler(DeepAgent notesDeepAgent) {
        return new JiuwenCoreAgentHandler(notesDeepAgent);
    }
}
