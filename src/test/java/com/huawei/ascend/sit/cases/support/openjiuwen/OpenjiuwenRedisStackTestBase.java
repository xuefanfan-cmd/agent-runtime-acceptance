package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.Set;

/**
 * Base for openjiuwen tests that need a managed Testcontainers Redis alongside the SUT stack.
 *
 * <p>Redis is started before the stack and torn down after; the stack receives
 * {@code REDIS_HOST}/{@code REDIS_PORT} on agents via {@link OpenjiuwenStackSupport}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class OpenjiuwenRedisStackTestBase {

    protected TestConfig config;
    protected SutStack stack;
    private BackingServices redisBacking;

    /** Redis endpoint captured at stack start for OJ-06+ runtime gates. */
    protected OpenjiuwenStackSupport.RedisEndpoint redisEndpoint;

    @BeforeAll
    void startRedisStack() throws IOException {
        config = TestConfig.load();
        redisBacking = new BackingServices(config, Set.of("redis"), new TestContainerFactory(null));
        OpenjiuwenStackSupport.RedisEndpoint redis =
                OpenjiuwenStackSupport.parseRedisEndpoint(redisBacking.url("redis"));
        redisEndpoint = redis;
        stack = buildRedisStack(config, redis.host(), redis.port())
                .backingServices(redisBacking)
                .start();
        OpenjiuwenRedisCheckpointerGate.assertAgentsUseRedisCheckpointer(
                stack, OpenjiuwenStackSupport.HOTEL, OpenjiuwenStackSupport.TRIP, OpenjiuwenStackSupport.MAINPLAN);
    }

    /**
     * Subclasses describe agents + Redis env (typically via {@link OpenjiuwenStackSupport}).
     */
    protected abstract SutStack.Builder buildRedisStack(TestConfig config, String redisHost, String redisPort);

    @AfterAll
    void tearDownRedisStack() {
        if (stack != null) {
            stack.close();
        }
        if (redisBacking != null) {
            redisBacking.close();
        }
    }

    protected A2aServiceClient client(String name) {
        return stack.client(name);
    }

    protected TestConfig getConfig() {
        return config;
    }
}
