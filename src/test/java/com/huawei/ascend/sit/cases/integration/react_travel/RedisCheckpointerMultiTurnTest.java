package com.huawei.ascend.sit.cases.integration.react_travel;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.cases.component.singleagent.CheckpointerRemoteMode;
import com.huawei.ascend.sit.cases.component.singleagent.TwoTurnDialogueRunner;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

/**
 * B-03 — Redis Checkpointer multi-turn continuity (特性 2-2).
 *
 * <p>Managed mode: {@code mainplan-redis} agent (see {@code application-local.yml}) binds
 * {@code sut.services.redis} via backing service; {@code main-plan-agent.redis-url} is injected
 * by {@link SutStack}, not by test code.</p>
 *
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} (or equivalent)
 * before launch so managed agents can reach the model; remote mode uses LLM on the pre-deployed
 * SUT. See {@code docs/cases/reactagent/B-03-redis-checkpointer-multi-turn.md}.</p>
 */
@Tag("integration")
@Tag("smoke")
class RedisCheckpointerMultiTurnTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(RedisCheckpointerMultiTurnTest.class.getName());

    /** Managed agent with {@code service-bindings.redis} in {@code application-local.yml}. */
    static final String MAINPLAN_REDIS = "mainplan-redis";

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        if (CheckpointerRemoteMode.isRemoteMode(config)) {
            String mainplanUrl = config.getString("sut.agents.mainplan.url");
            LOG.info("B-03 remote stack: mainplan=" + mainplanUrl);
            return SutStack.builder(config)
                    .streaming(true)
                    .remoteAgent("mainplan", mainplanUrl);
        }
        LOG.info("B-03 managed stack: mainplan-redis + backing service redis (application-local.yml)");
        return SutStack.builder(config)
                .streaming(true)
                .agent("hotel")
                .agent("trip", a -> a.downstream("hotel"))
                .agent(MAINPLAN_REDIS, a -> {
                    a.downstream("trip");
                    a.property("main-plan-agent.checkpointer", "redis");
                });
    }

    @Test
    @DisplayName("B-03: Redis checkpointer 多轮对话 — Turn2 理解 Turn1 上下文")
    void b03_redisMultiTurn_preservesContextAcrossTurns() throws InterruptedException {
        String clientAgent = CheckpointerRemoteMode.isRemoteMode(getConfig()) ? "mainplan" : MAINPLAN_REDIS;
        TwoTurnDialogueRunner.run(client(clientAgent), "B-03");
    }
}
