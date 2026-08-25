package com.huawei.ascend.sit.cases.e2e.reconnect;

import com.huawei.ascend.sit.fixtures.reconnect.ReActReconnectFixture;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Feature("FEAT-006: 客户端发起标准化智能体调用")
@Tag("feat-001")
@Tag("feat-006")
@Tag("e2e")
class ClientRuntimeReconnectIT {

    @Test
    @Stories({
            @Story("F006-R01/R03/R04: Runtime 直连断流恢复"),
            @Story("F001-R01-R05: Runtime Task 断流续行、查询和重订阅")
    })
    @DisplayName("Feat-001/006 Client 直连 Runtime 断流后恢复原 ReAct Task")
    void clientReconnectsToOriginalRuntimeTask() throws Exception {
        Assumptions.assumeTrue(ReActReconnectFixture.hasLlmCredentials(),
                "blocked/not-run: ReAct Runtime E2E requires LLM_API_KEY");
        try (ReActReconnectFixture environment = ReActReconnectFixture.runtimeDirect()) {
            ReconnectJourney.Result result = ReconnectJourney.execute(environment);

            assertThat(result.endpointType()).isEqualTo("RUNTIME");
            assertThat(result.taskId()).isNotBlank();
            assertThat(result.outputText()).isNotBlank();
        }
    }
}
