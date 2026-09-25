package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.fixtures.moduledecoupling.MavenConsumerFixture;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-XXX1: KnowledgeBase 模块依赖解耦")
@Tag("FEAT-XXX1")
@Tag("integration")
@Tag("contract")
class KnowledgeBaseModuleDecouplingAcceptanceTest {
    private static final String CORE_MAIN = "acceptance.knowledgebase.CoreOnlyConsumer";
    private static final String RETRIEVAL_MAIN = "acceptance.knowledgebase.RetrievalConsumer";

    @Test
    @Story("FEAT-XXX1.knowledgebase.core-only: Core-only 独立消费")
    @DisplayName("Feat-XXX1 Core-only 应用可打包运行且不携带 Retrieval 专用依赖")
    void coreOnlyConsumerPackagesAndRunsWithoutRetrieval() {
        var build = MavenConsumerFixture.build("knowledgebase-core");
        var run = build.run(CORE_MAIN);

        assertThat(run.value("STATUS")).isEqualTo("CORE_ONLY_OK");
        assertThat(run.value("RETRIEVAL_CLASS")).isEqualTo("absent");
        assertThat(build.classpathContains("agent-core-retrieval-java")).isFalse();
        assertThat(build.classpathContains("milvus-sdk-java")).isFalse();
        assertThat(build.classpathContains("pgvector")).isFalse();
        assertThat(build.classpathContains("pdfbox")).isFalse();
        assertThat(build.classpathContains("poi-ooxml")).isFalse();
        assertThat(build.classpathContains("dashscope-sdk-java")).isFalse();
    }

    @Test
    @Story("FEAT-XXX1.knowledgebase.local-flow: Retrieval 本地主流程兼容")
    @DisplayName("Feat-XXX1 Retrieval 应用可用 InMemory 链路写入并检索文档")
    void retrievalConsumerAddsAndRetrievesLocalDocument() {
        String canary = "kb-" + UUID.randomUUID();
        var build = MavenConsumerFixture.build("knowledgebase-retrieval");
        var run = build.run(RETRIEVAL_MAIN, "flow", canary);

        assertThat(build.classpathContains("agent-core-retrieval-java")).isTrue();
        assertThat(run.value("STATUS")).isEqualTo("RETRIEVAL_FLOW_OK");
        assertThat(run.value("CANARY")).isEqualTo(canary);
        assertThat(Integer.parseInt(run.value("RESULT_COUNT"))).isPositive();
    }

    @Test
    @Story("FEAT-XXX1.knowledgebase.workflow-component: Workflow 组件随 Retrieval 交付")
    @DisplayName("Feat-XXX1 KnowledgeRetrievalComponent 可由 Retrieval 消费工程加载")
    void retrievalConsumerLoadsWorkflowKnowledgeComponent() {
        var core = MavenConsumerFixture.build("knowledgebase-core").run(CORE_MAIN);
        var retrieval = MavenConsumerFixture.build("knowledgebase-retrieval")
                .run(RETRIEVAL_MAIN, "workflow-component", "unused");

        assertThat(core.value("RETRIEVAL_CLASS")).isEqualTo("absent");
        assertThat(retrieval.value("STATUS")).isEqualTo("WORKFLOW_COMPONENT_OK");
        assertThat(retrieval.value("COMPONENT_CLASS"))
                .isEqualTo("com.openjiuwen.retrieval.workflow.component.resource.KnowledgeRetrievalComponent");
    }
}

