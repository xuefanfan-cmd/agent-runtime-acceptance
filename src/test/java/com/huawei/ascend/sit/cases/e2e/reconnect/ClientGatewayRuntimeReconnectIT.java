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

@Feature("FEAT-011: 客户端调用路由转发")
@Tag("feat-006")
@Tag("feat-011")
@Tag("e2e")
class ClientGatewayRuntimeReconnectIT {

    @Test
    @Stories({
            @Story("F011-E01: ReAct Gateway 真实断点重连"),
            @Story("F006-E01: Gateway Endpoint 断流恢复")
    })
    @DisplayName("Feat-006/011 Client 经 Gateway 断流后恢复原 ReAct Task")
    void clientReconnectsThroughGatewayToOriginalRuntimeTask() throws Exception {
        Assumptions.assumeTrue(ReActReconnectFixture.hasLlmCredentials(),
                "blocked/not-run: ReAct Gateway E2E requires LLM_API_KEY");
        try (ReActReconnectFixture environment = ReActReconnectFixture.gateway()) {
            ReconnectJourney.Result result = ReconnectJourney.execute(environment);

            assertThat(result.endpointType()).isEqualTo("GATEWAY");
            assertThat(result.taskId()).isNotBlank();
            assertThat(result.outputText()).isNotBlank();
        }
    }
}
