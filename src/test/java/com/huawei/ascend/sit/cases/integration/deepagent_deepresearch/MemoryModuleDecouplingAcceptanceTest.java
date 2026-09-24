package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.fixtures.moduledecoupling.MavenConsumerFixture;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-XXX2: Memory 模块拆分与依赖架构优化")
@Tag("FEAT-XXX2")
@Tag("integration")
@Tag("contract")
class MemoryModuleDecouplingAcceptanceTest {
    private static final String MAIN = "acceptance.memory.MemoryConsumer";

    @Test
    @Story("FEAT-XXX2.memory.core-only: 不带 Memory 的 Agent 打包和运行")
    @DisplayName("Feat-XXX2 Core-only Agent 可打包运行且不携带 Memory 实现")
    void coreOnlyAgentPackagesAndRunsWithoutMemory() {
        String query = "memory-decoupling-" + UUID.randomUUID();
        var build = MavenConsumerFixture.build("memory-core");
        var run = build.run(MAIN, query);

        assertThat(build.classpathContains("agent-core-memory-java")).isFalse();
        assertThat(run.value("STATUS")).isEqualTo("AGENT_OK");
        assertThat(run.value("MEMORY_RUNTIME")).isEqualTo("absent");
        assertStableAgentResult(run, query);
    }

    @Test
    @Story("FEAT-XXX2.memory.enabled: 带 Memory 的 Agent 打包和运行对照")
    @DisplayName("Feat-XXX2 增加 Memory 主制品后 Agent 基础运行表现保持一致")
    void memoryEnabledAgentPackagesAndPreservesCoreBehavior() {
        String query = "memory-decoupling-" + UUID.randomUUID();
        var coreBuild = MavenConsumerFixture.build("memory-core");
        var memoryBuild = MavenConsumerFixture.build("memory-enabled");
        var core = coreBuild.run(MAIN, query);
        var memory = memoryBuild.run(MAIN, query);

        assertThat(memoryBuild.classpathCount("agent-core-memory-java")).isEqualTo(1);
        assertThat(memory.value("STATUS")).isEqualTo("AGENT_OK");
        assertThat(memory.value("MEMORY_RUNTIME")).isEqualTo("present");
        assertStableAgentResult(core, query);
        assertStableAgentResult(memory, query);
        assertThat(memory.value("AGENT_NAME")).isEqualTo(core.value("AGENT_NAME"));
        assertThat(memory.value("MODE")).isEqualTo(core.value("MODE"));
        assertThat(memory.value("QUERY")).isEqualTo(core.value("QUERY"));
    }

    private static void assertStableAgentResult(MavenConsumerFixture.ConsumerRun run, String query) {
        assertThat(run.value("AGENT_NAME")).isEqualTo("deep_agent");
        assertThat(run.value("MODE")).isEqualTo("normal");
        assertThat(run.value("QUERY")).isEqualTo(query);
    }
}

