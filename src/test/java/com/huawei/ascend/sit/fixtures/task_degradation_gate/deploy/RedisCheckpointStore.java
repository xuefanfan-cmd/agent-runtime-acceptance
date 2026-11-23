package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedContainer;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A test-owned Redis the DA uses as its core checkpointer ({@code openjiuwen.service.middleware.checkpointer.type=redis}).
 * The carrier's own application.yml replaces the engine's, so the checkpointer and its Redis reference are wired
 * explicitly here. A scenario can stop the store while the DA keeps running.
 */
public final class RedisCheckpointStore implements AutoCloseable {
    private static final String MIDDLEWARE = "openjiuwen.service.middleware.";
    private final ManagedContainer container;
    private final String image;
    /** Read once while the container runs: Testcontainers refuses a port lookup after the store is stopped. */
    private final String host;
    private final int port;
    private volatile boolean stopped;

    private RedisCheckpointStore(ManagedContainer container, String image) {
        this.container = container;
        this.image = image;
        this.host = container.host();
        this.port = container.mappedPort();
    }

    static RedisCheckpointStore start(TestConfig config, String formName) {
        String image = config.getString("sut.services.redis.image", "redis:7-alpine");
        try {
            ManagedContainer container = new TestContainerFactory(Path.of("target", "sit-logs"))
                    .start("feat112-" + formName + "-redis", image, 6379, Map.of());
            return new RedisCheckpointStore(container, image);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("deployment form " + formName + " needs a Redis container (" + image
                    + "); Testcontainers could not start it: " + failure.getMessage(), failure);
        }
    }

    /** Spring properties that point the DA checkpointer at this store. */
    public Map<String, String> properties() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(MIDDLEWARE + "checkpointer.type", "redis");
        p.put(MIDDLEWARE + "checkpointer.redis-ref", "default");
        p.put(MIDDLEWARE + "redis.default.type", "standalone");
        p.put(MIDDLEWARE + "redis.default.host", host);
        p.put(MIDDLEWARE + "redis.default.port", Integer.toString(port));
        p.put(MIDDLEWARE + "redis.default.database", "0");
        return p;
    }

    public String describe() {
        return image + " at " + host + ":" + port + (stopped ? " (stopped)" : "");
    }

    /** Stops the store while the DA keeps running: the outage a scenario injects. */
    public void stop() {
        if (stopped) return;
        stopped = true;
        container.close();
    }

    public boolean stopped() {
        return stopped;
    }

    @Override
    public void close() {
        stop();
    }
}
