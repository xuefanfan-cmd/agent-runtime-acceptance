package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054：{@code da.run.minimal-task} —— 经**标准 A2A 入口**发起一次 DA 场景任务并完成产出结果。
 *
 * <p>设计侧 2026-09-22 裁决（B-Q1）：工具团队最小工具集未交付期间，**零工具最小任务可作替代路径**，
 * 四条建立在"任务能跑通"之上的验收照常进行；工具能力本身按前置未满足单独记账。
 * 本类只判"任务能跑完并产出结果"，不判工具装配面（那是 {@code GAP-054-01} 保留的前置项）。
 *
 * <p>模型连接：宿主按 {@code DA_MODEL_*} / {@code LLM_API_KEY} 环境变量解析模型连接面
 * （见被测模块 {@code application.yml} 的 {@code deepanalyze.model.backend}）。
 * 本类把这些变量**按进程环境**传给 SUT（{@code AgentBuilder#env}），不写进命令行参数，
 * 避免密钥出现在进程参数里。
 *
 * <p>主断言：任务状态到达 {@code TASK_STATE_COMPLETED} 且产出正文非空。
 * 若"模型根本没被调用 / 任务被前置挡住 / 结果为空"，主断言必然失败。
 *
 * <p>Oracle 来源：特性 §2 验收出口 #2（主流程入口）+ 设计侧 B-Q1 裁决。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaMinimalTaskE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";

    /**
     * 最小任务语料：明确要求"不调用工具"，使它在零工具条件下也是合法请求
     * （不依赖工具团队交付面）。
     */
    private static final String INPUT_TEXT =
            "请用一句话说明你能处理哪一类数据分析任务，不要调用任何工具。";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    // 运行时 A2A TaskStore 与 core checkpointer 走 Redis（application.yml 的
                    // ${REDIS_HOST:redis} / ${REDIS_PORT:6379} 默认值在本地解析不到 "redis" 主机）。
                    // 引用 backing-services 里的 redis 后由栈自管拉起容器，并把动态 host/port
                    // 以 Spring 命令行属性注入；写法与 PlanAgentDirectStreamingRedisTest 一致。
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    /** 把运行器已注入的 LLM 环境变量按宿主约定的变量名传给 SUT 进程。 */
    private static void bindModelEnvironment(SutStack.AgentBuilder agent) {
        passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
        passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
        passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    @Test
    @Story("da.run.minimal-task: 零工具最小任务经标准入口跑通")
    @DisplayName("da.run.minimal-task: 标准 A2A 入口发起一次 DA 场景任务 → COMPLETED 且产出非空结果")
    void minimalTaskReachesCompletedWithoutTools() {
        InteractionFlow.of(client(AGENT))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send(INPUT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(text -> {
                        Allure.addAttachment("da.run.minimal-task 产出正文", "text/plain", text);
                        assertThat(text)
                                .as("主断言：零工具最小任务必须产出非空结果，否则验收出口 #2 不成立")
                                .isNotBlank();
                    })
                .execute();
    }

    /**
     * {@code da.estimate.event}：复杂度估算结果的可观察取证。
     *
     * <p>设计侧 2026-09-22 裁决（B-Q6）：接受"结构化日志事件 {@code da_complexity_estimate} + DFX-001 日志路由"
     * 作为"对外可见"的取证路径（用例标 partial，结论注明取证依赖）。本用例从 SUT 日志实证该事件在场。
     */
    @Test
    @Story("da.estimate.event: 复杂度估算事件可观测（档位+建议轮数）")
    @DisplayName("da.estimate.event: SUT 日志出现 da_complexity_estimate 且带档位与建议轮数")
    void complexityEstimateIsObservableInSutLog() throws Exception {
        InteractionFlow.of(client(AGENT))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send(INPUT_TEXT)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(text -> assertThat(text).isNotBlank())
                .execute();

        java.nio.file.Path log = ((com.huawei.ascend.sit.lifecycle.ManagedSutInstance)
                stack.managedInstance(AGENT)).logFile();
        String content = java.nio.file.Files.readString(log);
        Allure.addAttachment("估算事件原文（截取）", "text/plain",
                content.lines().filter(line -> line.contains("da_complexity_estimate")).findFirst()
                        .orElse("(未找到 da_complexity_estimate 行)"));
        assertThat(content)
                .as("估算结果须以结构化日志事件对外可见（设计侧 B-Q6 裁决的取证路径）")
                .contains("event=da_complexity_estimate")
                .contains("tier=")
                .contains("suggestedRounds=");
    }
}
