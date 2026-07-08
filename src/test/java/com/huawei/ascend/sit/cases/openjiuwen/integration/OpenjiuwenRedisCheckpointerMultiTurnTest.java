package com.huawei.ascend.sit.cases.openjiuwen.integration;

import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenRedisStackTestBase;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenRedisCheckpointerGate;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStreamingTwoTurnRunner;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenTwoTurnScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * OJ-06 — openjiuwen Redis checkpointer multi-turn continuity (full chain, streaming).
 *
 * <p>See {@code docs/cases/OJ-06-openjiuwen-redis-checkpointer-multi-turn.md}.</p>
 *
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} before launch.
 * Docker is required for Testcontainers Redis.</p>
 */
@Tag("integration")
@Tag("openjiuwen")
@Tag("nightly")
class OpenjiuwenRedisCheckpointerMultiTurnTest extends OpenjiuwenRedisStackTestBase {

    @Override
    protected SutStack.Builder buildRedisStack(TestConfig config, String redisHost, String redisPort) {
        return OpenjiuwenStackSupport.fullChainRedisStreaming(config, redisHost, redisPort);
    }

    @Test
    @DisplayName("OJ-06: Redis checkpointer 全链流式 — Turn2 理解 Turn1 上下文")
    void oj06_redisMultiTurnStreaming_preservesContextAcrossTurns() throws InterruptedException, IOException {
        OpenjiuwenTwoTurnScenarioData scenario = OpenjiuwenTwoTurnScenarioData.loadOj06();
        OpenjiuwenStreamingTwoTurnRunner.Result result = OpenjiuwenStreamingTwoTurnRunner.run(
                client(OpenjiuwenStackSupport.MAINPLAN), scenario, "OJ-06");
        OpenjiuwenRedisCheckpointerGate.assertRedisHasCheckpointData(
                redisEndpoint.host(),
                OpenjiuwenRedisCheckpointerGate.parsePort(redisEndpoint.port()),
                "OJ-06 after Turn1 contextId=" + result.contextId());
    }
}
