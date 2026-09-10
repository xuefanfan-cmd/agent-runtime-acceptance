package com.openjiuwen.example.studiodsl;

import com.openjiuwen.core.application.schema.WorkflowAgentConfig;
import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.studio.dsl.nodes.FlowEndNode;
import com.openjiuwen.studio.dsl.nodes.FlowLlmNode;
import com.openjiuwen.studio.dsl.nodes.FlowStartNode;
import com.openjiuwen.studio.dsl.flowstart.FlowStartConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles a Studio DSL workflow Agent for FEAT-031 acceptance testing.
 *
 * <p>The workflow uses {@link FlowStartNode} / {@link FlowLlmNode} / {@link FlowEndNode}
 * from {@code agent-core-ext-studio-dsl} — NOT the native agent-core-java
 * {@code Start} / {@code LLMComponent} / {@code End}. This is the key distinction:
 * the SUT exercises the FEAT-031 node type extension layer.
 *
 * <p>Workflow: start → llm → end
 * - start: receives user query, initializes context
 * - llm: calls LLM with system prompt + user query, returns response
 * - end: emits final answer
 *
 * @since 2026-08-27
 */
@Configuration(proxyBeanMethods = false)
public class StudioDslDemoConfiguration {

    @Bean
    AgentHandler studioDslDemoHandler(
            @Value("${studio-dsl.api-key:${LLM_API_KEY:}}") String apiKey,
            @Value("${studio-dsl.api-base:${LLM_API_BASE:http://localhost:4000/v1}}") String apiBase,
            @Value("${studio-dsl.model-name:${LLM_MODEL:gpt-4o-mini}}") String modelName,
            @Value("${studio-dsl.ssl-verify:${LLM_SSL_VERIFY:true}}") boolean sslVerify) {

        Workflow workflow = buildSimpleLlmWorkflow(apiKey, apiBase, modelName, sslVerify);

        WorkflowAgentConfig cfg = WorkflowAgentConfig.builder()
                .id("studio-dsl-demo")
                .description("FEAT-031 Studio DSL demo — start→llm→end workflow")
                .build();
        WorkflowAgent agent = new WorkflowAgent(cfg);
        agent.addWorkflows(List.of(workflow));
        return new JiuwenCoreAgentHandler(agent);
    }

    /**
     * Build a minimal workflow: start → llm → end.
     *
     * <p>This workflow exercises three FEAT-031 node types:
     * <ul>
     *   <li>{@link FlowStartNode} (jiuwen.start) — receives query, initializes context</li>
     *   <li>{@link FlowLlmNode} (jiuwen.LLMComponent) — calls LLM, returns response</li>
     *   <li>{@link FlowEndNode} (jiuwen.end) — emits final answer</li>
     * </ul>
     */
    static Workflow buildSimpleLlmWorkflow(String apiKey, String apiBase,
                                           String modelName, boolean sslVerify) {
        WorkflowCard card = WorkflowCard.builder()
                .id("studio-dsl-simple-llm")
                .name("Studio DSL Simple LLM")
                .version("1.0")
                .description("start → llm → end (FEAT-031 node types)")
                .build();
        Workflow wf = new Workflow(card);

        // --- start node (jiuwen.start) ---
        FlowStartNode start = new FlowStartNode("start",
                new FlowStartConfig(Map.of(), "start"));
        wf.setStartComp("start", start,
                Map.of("query", "${query}"), null);

        // --- llm node (jiuwen.LLMComponent) ---
        // LlmChainConfig reads model name from configs.model.modelName,
        // and api_key/api_base from configs.model.extension.
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("api_key", apiKey);
        extension.put("api_base", apiBase);
        extension.put("verify_ssl", sslVerify);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("modelName", modelName);
        model.put("modelType", "openai");
        model.put("extension", extension);

        Map<String, Object> llmConfigs = new LinkedHashMap<>();
        llmConfigs.put("name", "llm");
        llmConfigs.put("model", model);
        llmConfigs.put("systemPrompt",
                "你是一个智能助手，请简洁地回答用户的问题。");
        llmConfigs.put("userPromptTemplate", "{{query}}");
        llmConfigs.put("responseFormat", Map.of("type", "text"));
        // LlmChainConfig.validate() requires: templateContent, userFields.outputs
        llmConfigs.put("templateContent", List.of(
                Map.of("role", "system", "content", "你是一个智能助手，请简洁地回答用户的问题。"),
                Map.of("role", "user", "content", "{{query}}")));
        llmConfigs.put("userFields", Map.of(
                "outputs", List.of(
                        Map.of("name", "answer", "type", "string",
                                "description", "LLM 回答"))));
        FlowLlmNode llm = new FlowLlmNode("llm", llmConfigs);
        wf.addWorkflowComp("llm", llm,
                Map.of("query", "${start.query}"), null);

        // --- end node (jiuwen.end) ---
        Map<String, Object> endConfigs = new LinkedHashMap<>();
        endConfigs.put("name", "end");
        endConfigs.put("responseTemplate", "{{llm.answer}}");
        FlowEndNode end = new FlowEndNode("end", endConfigs);
        wf.addWorkflowComp("end", end,
                Map.of("answer", "${llm.userFields.answer}"), null);

        // --- connections ---
        wf.addConnection("start", "llm");
        wf.addConnection("llm", "end");

        return wf;
    }
}
