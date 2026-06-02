package com.huawei.ascend.sit.cases.integration.scenario;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.huawei.ascend.sit.base.BaseIntegrationTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.RequestProvider;
import com.huawei.ascend.sit.client.ScenarioExecutionResult;
import com.huawei.ascend.sit.client.ScenarioExecutor;
import com.huawei.ascend.sit.client.TransitionResolver;
import com.huawei.ascend.sit.model.scenario.ScenarioDefinition;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data-driven multi-turn interaction scenario tests.
 *
 * <p>Loads YAML scenario definitions from {@code testdata/scenario/} and executes
 * each one through the {@link ScenarioExecutor}. Test code explicitly provides
 * request messages (via {@link RequestProvider}) and evaluates results.</p>
 *
 * <h3>Three-way separation:</h3>
 * <ol>
 *   <li><b>YAML</b> — defines step structure, transitions, and context initialization</li>
 *   <li><b>Test code</b> — constructs requests, evaluates results</li>
 *   <li><b>Executor</b> — drives steps, collects events, delegates to providers</li>
 * </ol>
 */
@Tag("integration")
@Disabled("示例用例，待联调验证后逐个放开")
public class ScenarioDrivenTest extends BaseIntegrationTest {

    private static ScenarioExecutor executor;
    private static YAMLMapper yamlMapper;

    @BeforeAll
    static void initExecutor() {
        executor = new ScenarioExecutor(a2aClient)
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .withMaxSteps(20);

        yamlMapper = YAMLMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    // ===== Transfer Scenario =====

    /**
     * Request provider for the transfer scenario.
     * Each step's request is explicitly defined in test code —
     * intermediate requests can reference previous responses or external APIs.
     */
    private RequestProvider transferRequestProvider() {
        return (stepId, ctx) -> switch (stepId) {
            case "on_transfer_request" -> A2A.toUserMessage("我要进行快速转账");
            case "on_bankcards_input" -> A2A.toUserMessage("查看我的银行卡列表");
            case "on_payee_input" -> {
                // Intermediate request: could use ctx.lastResult() or call third-party API
                // String cardData = mockCardService.getCards();
                // ctx.put("cardData", cardData);
                yield A2A.toUserMessage("选择第一张收款卡");
            }
            case "on_paycard_input" -> A2A.toUserMessage("选择储蓄卡付款");
            case "on_confirm_card" -> A2A.toUserMessage("确认卡号信息正确");
            case "on_confirm_remit" -> A2A.toUserMessage("确认转账");
            case "on_remit_return" -> A2A.toUserMessage("查看转账结果");
            default -> throw new IllegalArgumentException("Unknown step: " + stepId);
        };
    }

    @Test
    @DisplayName("转账流程：完整7步场景执行成功")
    void transferScenario_shouldCompleteAllSteps() throws Exception {
        // given — load scenario, define request provider and transition resolver
        ScenarioDefinition scenario = loadScenario("scenario/transfer-scenario.yaml");
        RequestProvider requestProvider = transferRequestProvider();
        TransitionResolver resolver = TransitionResolver.yamlTransitions();

        // when — execute the scenario
        ScenarioExecutionResult result = executor.execute(scenario, requestProvider, resolver);

        // then — scenario should complete successfully
        assertThat(result.completed())
                .as("Scenario '%s' should complete to END", scenario.name())
                .isTrue();

        assertThat(result.error())
                .as("Scenario '%s' should have no errors", scenario.name())
                .isNull();

        assertThat(result.stepResults())
                .as("Transfer scenario should have executed multiple steps")
                .isNotEmpty();

        // Evaluate each step
        for (ScenarioExecutionResult.StepResult step : result.stepResults()) {
            assertThat(step.taskState())
                    .as("Step '%s' should reach a valid state", step.stepId())
                    .isIn(
                            TaskState.TASK_STATE_COMPLETED,
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            TaskState.TASK_STATE_WORKING,
                            TaskState.TASK_STATE_SUBMITTED);
            assertThat(step.error())
                    .as("Step '%s' should have no error", step.stepId())
                    .isNull();
            assertThat(step.events())
                    .as("Step '%s' should have captured events", step.stepId())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("转账流程：每个步骤产生有效的 task ID、请求内容和事件流")
    void transferScenario_producesValidTaskIdsAndEvents() throws Exception {
        // given
        ScenarioDefinition scenario = loadScenario("scenario/transfer-scenario.yaml");

        // when
        ScenarioExecutionResult result = executor.execute(scenario, transferRequestProvider());

        // then — first step should have valid data
        assertThat(result.stepResults())
                .as("Should have executed at least one step")
                .isNotEmpty();

        ScenarioExecutionResult.StepResult firstStep = result.stepResults().get(0);
        assertThat(firstStep.taskId())
                .as("First step should produce a valid task ID")
                .isNotBlank();
        assertThat(firstStep.requestContent())
                .as("First step should record request content")
                .isNotNull();
        assertThat(firstStep.events())
                .as("First step should capture event stream")
                .isNotNull();
    }

    // ===== Helper: load scenario from YAML =====

    private ScenarioDefinition loadScenario(String relativePath) throws Exception {
        String resourcePath = "testdata/" + relativePath;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Scenario file not found: " + resourcePath);
            }
            return yamlMapper.readValue(is, ScenarioDefinition.class);
        }
    }
}
