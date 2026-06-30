package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.integration.checkpointer.RedisMultiTurnScenarioData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

/**
 * B-03 — Redis Checkpointer multi-turn continuity (特性 2-2).
 *
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} (or equivalent)
 * before launch so managed agents can reach the model; remote mode uses LLM on the pre-deployed
 * SUT. See {@code docs/cases/B-03-redis-checkpointer-multi-turn.md}.</p>
 */
@Tag("integration")
@Tag("smoke")
class RedisCheckpointerMultiTurnTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(RedisCheckpointerMultiTurnTest.class.getName());

    private static final RedisCheckpointerSupport.ManagedRedis MANAGED_REDIS = RedisCheckpointerSupport.managed();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        if (CheckpointerRemoteMode.isRemoteMode(config)) {
            String mainplanUrl = config.getString("sut.agents.mainplan.url");
            LOG.info("B-03 remote stack: mainplan=" + mainplanUrl);
            return SutStack.builder(config)
                    .streaming(true)
                    .remoteAgent("mainplan", mainplanUrl);
        }
        String redisUrl = MANAGED_REDIS.redisUrl(config);
        LOG.info("B-03 managed stack redis-url=" + redisUrl);
        return SutStack.builder(config)
                .streaming(true)
                .agent("hotel")
                .agent("trip", a -> a.downstream("hotel"))
                .agent("mainplan", a -> {
                    a.downstream("trip");
                    a.property("main-plan-agent.checkpointer", "redis");
                    a.property("main-plan-agent.redis-url", redisUrl);
                });
    }

    @AfterAll
    void stopManagedRedis() {
        MANAGED_REDIS.stopIfStarted();
    }

    @Test
    @DisplayName("B-03: Redis checkpointer 多轮对话 — Turn2 理解 Turn1 上下文")
    void b03_redisMultiTurn_preservesContextAcrossTurns() throws InterruptedException {
        RedisMultiTurnScenarioData scenario = RedisMultiTurnScenarioData.loadDefault();
        TwoTurnDialogueRunner.run(client("mainplan"), scenario, "B-03");
    }
}
