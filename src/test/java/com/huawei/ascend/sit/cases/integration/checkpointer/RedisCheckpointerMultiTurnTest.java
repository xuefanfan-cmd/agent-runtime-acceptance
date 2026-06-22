package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutAgent;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.integration.checkpointer.B03ScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.fail;

/**
 * B-03 — Redis Checkpointer multi-turn continuity (特性 2-2).
 *
 * <p>See {@code docs/cases/B-03-redis-checkpointer-multi-turn.md}.</p>
 */
@Tag("integration")
@Tag("smoke")
@EnabledIf("com.huawei.ascend.sit.cases.integration.checkpointer.B03Gate#isExecutable")
class RedisCheckpointerMultiTurnTest extends BaseManagedStackTest {

    private static final Logger LOG = Logger.getLogger(RedisCheckpointerMultiTurnTest.class.getName());

    private static final GenericContainer<?> REDIS = createRedisIfNeeded();

    private static GenericContainer<?> createRedisIfNeeded() {
        if (B03Gate.isRemoteMode()) {
            return null;
        }
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        container.start();
        return container;
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        if (B03Gate.isRemoteMode(config)) {
            String mainplanUrl = config.getString("sut.agents.mainplan.url");
            LOG.info("B-03 remote stack: mainplan=" + mainplanUrl);
            return SutStack.builder(config)
                    .streaming(true)
                    .remoteAgent("mainplan", mainplanUrl);
        }
        requireManagedPrerequisites();
        String redisUrl = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
        LOG.info("B-03 managed stack with Testcontainers redis-url=" + redisUrl);
        return SutStack.builder(config)
                .streaming(true)
                .agent("hotel")
                .agent("trip", a -> a.role(SutAgent.Role.MIDDLE).downstream("hotel"))
                .agent("mainplan", a -> {
                    a.role(SutAgent.Role.ENTRY).downstream("trip");
                    a.property("main-plan-agent.checkpointer", "redis");
                    a.property("main-plan-agent.redis-url", redisUrl);
                    String apiKey = System.getenv(B03Gate.LLM_KEY_ENV);
                    if (apiKey != null && !apiKey.isBlank()) {
                        a.property("main-plan-agent.api-key", apiKey);
                    }
                });
    }

    @Test
    @DisplayName("B-03: Redis checkpointer 多轮对话 — Turn2 理解 Turn1 上下文")
    void b03_redisMultiTurn_preservesContextAcrossTurns() throws InterruptedException {
        if (!B03Gate.isRemoteMode()) {
            requireManagedPrerequisites();
        }
        B03ScenarioData scenario = B03ScenarioData.loadDefault();
        B03TwoTurnDialogueRunner.run(client("mainplan"), scenario, "B-03");
    }

    private static void requireManagedPrerequisites() {
        if (!B03Gate.hasLlmKey()) {
            fail("SIT_LLM_API_KEY or LLM_API_KEY must be set for managed B-03");
        }
    }
}
