package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.config.TestConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.logging.Logger;

/**
 * Resolves Redis URL for B-03 / B-04 managed mode: optional configured URL or Testcontainers.
 */
final class RedisCheckpointerSupport {

    private static final Logger LOG = Logger.getLogger(RedisCheckpointerSupport.class.getName());

    /** YAML / system property / env {@code SUT_MIDDLEWARE_REDIS_URL}. */
    static final String REDIS_URL_CONFIG_KEY = "sut.middleware.redis-url";

    private static final String REDIS_IMAGE = "redis:7-alpine";

    private RedisCheckpointerSupport() {
    }

    /**
     * Lazy holder: uses {@link #REDIS_URL_CONFIG_KEY} when set; otherwise starts one Redis container.
     */
    static final class ManagedRedis {

        private GenericContainer<?> container;
        private String configuredUrl;

        String redisUrl(TestConfig config) {
            if (configuredUrl == null) {
                configuredUrl = resolveConfiguredUrl(config);
            }
            if (configuredUrl != null && !configuredUrl.isBlank()) {
                LOG.info("B-03/B-04 managed redis-url (configured)=" + configuredUrl);
                return configuredUrl;
            }
            if (container == null) {
                LOG.info("B-03/B-04 starting Testcontainers Redis image=" + REDIS_IMAGE);
                container = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                        .withExposedPorts(6379);
                container.start();
            }
            String dynamic = "redis://" + container.getHost() + ":" + container.getMappedPort(6379);
            LOG.info("B-03/B-04 managed redis-url (testcontainers)=" + dynamic);
            return dynamic;
        }

        void stopIfStarted() {
            if (container != null) {
                container.stop();
                container = null;
            }
        }
    }

    static ManagedRedis managed() {
        return new ManagedRedis();
    }

    static String resolveConfiguredUrl(TestConfig config) {
        String fromConfig = config.getString(REDIS_URL_CONFIG_KEY, "");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return fromConfig.trim();
        }
        String fromEnv = System.getenv("REDIS_URL");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return null;
    }
}
