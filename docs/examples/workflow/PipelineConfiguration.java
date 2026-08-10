package com.openjiuwen.examples.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openjiuwen.core.application.schema.WorkflowAgentConfig;
import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.core.workflow.component.BranchComponent;
import com.openjiuwen.core.workflow.component.End;
import com.openjiuwen.core.workflow.component.Start;
import com.openjiuwen.core.workflow.component.llm.LLMCompConfig;
import com.openjiuwen.core.workflow.component.llm.LLMComponent;
import com.openjiuwen.core.workflow.component.llm.QuestionerComponent;
import com.openjiuwen.core.workflow.component.llm.QuestionerConfig;
import com.openjiuwen.core.workflow.component.tool.ToolComponent;
import com.openjiuwen.core.workflow.component.tool.ToolComponentConfig;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

/**
 * 最小完整闭环：LLM 处理 -> 工具校验 -> 分支 -> 人工(HITL)/自动两个收尾。
 * 命名中性化，只演示框架能力，不含业务逻辑。
 *
 * <p>配套文件：application.yml（服务端点与 LLM 配置）、CheckTool.java（工具实现）。
 */
@Configuration(proxyBeanMethods = false)
public class PipelineConfiguration {

    @Bean
    AgentHandler pipelineHandler(
            @Value("${pipeline.api-key:}") String apiKey,
            @Value("${pipeline.api-base:}") String apiBase,
            @Value("${pipeline.model-name:gpt-4o-mini}") String modelName) {
        WorkflowAgent agent = new WorkflowAgent(WorkflowAgentConfig.builder()
                .id("pipeline")
                .description("LLM 处理 -> 工具校验 -> 分支 -> 人工/自动收尾")
                .build());
        agent.addWorkflows(List.of(buildWorkflow(apiKey, apiBase, modelName)));
        return new JiuwenCoreAgentHandler(agent);   // 库存 handler 直接托管，不子类化
    }

    static Workflow buildWorkflow(String apiKey, String apiBase, String modelName) {
        ModelClientConfig clientCfg = ModelClientConfig.builder()
                .clientProvider("openai")
                .apiKey(apiKey)
                .apiBase(apiBase)
                .verifySsl(true)
                .build();
        ModelRequestConfig reqCfg = ModelRequestConfig.builder()
                .modelName(modelName)
                .temperature(0.0)
                .maxTokens(1024)
                .build();

        Workflow wf = new Workflow(WorkflowCard.builder()
                .id("pipeline").name("示例流水线").version("1.0")
                .description("最小闭环").build());

        // 1) Start：把入参 query 引入图内（右值 ${query} 引用顶层入参）
        wf.setStartComp("start", new Start(), Map.of("query", "${query}"), null);

        // 2) LLM 节点：结构化 JSON 输出（{{query}} = 本组件局部输入键）
        LLMCompConfig llmCfg = new LLMCompConfig();
        llmCfg.setModelClientConfig(clientCfg);
        llmCfg.setModelConfig(reqCfg);
        llmCfg.setSystemPromptTemplate(new SystemMessage("抽取输入中的数值并以 JSON 返回。"));
        llmCfg.setUserPromptTemplate(new UserMessage("处理：{{query}}"));
        llmCfg.setResponseFormat(new LinkedHashMap<>(Map.of("type", "json")));
        llmCfg.setOutputConfig(new LinkedHashMap<>(Map.of(
                "total", Map.of("type", "number", "description", "合计"),
                "summary", Map.of("type", "string", "description", "摘要"))));
        wf.addWorkflowComp("transform", new LLMComponent(llmCfg),
                Map.of("query", "${start.query}"), null);            // ${} = 图引擎跨节点引用

        // 3) 工具节点：本地函数工具；下游引用须带 .data（${check.data.risk}）
        wf.addWorkflowComp("check",
                new ToolComponent(new ToolComponentConfig()).bindTool(new CheckTool()),
                Map.of("total", "${transform.total}"), null);

        // 4) 分支：risk=high 走人工，否则自动收尾；兜底分支必须存在
        BranchComponent branch = new BranchComponent();
        branch.addBranch("${check.data.risk} == \"high\"", "confirm", "high");
        branch.addBranch("true", "finish", "normal");
        wf.addWorkflowComp("route", branch,
                Map.of("risk", "${check.data.risk}"), null);

        // 5a) 人工审批节点（HITL）：执行到这里挂起并抛出中断（A2A 侧 INPUT_REQUIRED）
        QuestionerConfig qCfg = new QuestionerConfig();
        qCfg.setModelClientConfig(clientCfg);
        qCfg.setModelConfig(reqCfg);
        qCfg.setResponseType("reply_directly");
        qCfg.setExtractFieldsFromResponse(false);
        qCfg.setQuestionContent("风险超阈值，请输入 'approved' 通过，或说明拒绝理由。");
        wf.addWorkflowComp("confirm", new QuestionerComponent(qCfg),
                Map.of("summary", "${transform.summary}"), null);

        // 5b) 自动收尾节点（文本输出）
        LLMCompConfig autoCfg = new LLMCompConfig();
        autoCfg.setModelClientConfig(clientCfg);
        autoCfg.setModelConfig(reqCfg);
        autoCfg.setSystemPromptTemplate(new SystemMessage("生成通过报告。"));
        autoCfg.setUserPromptTemplate(new UserMessage("依据：{{summary}}"));
        autoCfg.setResponseFormat(new LinkedHashMap<>(Map.of("type", "text")));
        autoCfg.setOutputConfig(new LinkedHashMap<>(Map.of(
                "text", Map.of("type", "string", "description", "报告"))));
        wf.addWorkflowComp("finish", new LLMComponent(autoCfg),
                Map.of("summary", "${transform.summary}"), null);

        // 6) End：收集两分支各自的结果字段（未走到的分支为 null）
        wf.setEndComp("end", new End(),
                Map.of("manual_result", "${confirm.user_response}",
                        "auto_result", "${finish.text}"), null);

        // 7) 连线（分支 -> 目标的边由 BranchComponent 自路由，不用 addConnection）
        wf.addConnection("start", "transform");
        wf.addConnection("transform", "check");
        wf.addConnection("check", "route");
        wf.addConnection("confirm", "end");
        wf.addConnection("finish", "end");
        return wf;
    }
}
