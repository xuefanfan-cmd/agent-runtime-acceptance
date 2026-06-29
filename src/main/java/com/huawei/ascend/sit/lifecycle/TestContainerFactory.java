package com.huawei.ascend.sit.lifecycle;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * Default {@link ContainerFactory} backed by Testcontainers {@link GenericContainer}: exposes the
 * internal port (random mapped host port for parallel coexistence), applies env via {@code withEnv}.
 */
public final class TestContainerFactory implements ContainerFactory {

    @Override
    public ManagedContainer start(String image, int port, Map<String, String> env) {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(port);
        env.forEach(c::withEnv);
        c.start();
        return new Started(c, port);
    }

    private static final class Started implements ManagedContainer {
        private final GenericContainer<?> container;
        private final int port;

        Started(GenericContainer<?> container, int port) {
            this.container = container;
            this.port = port;
        }

        @Override
        public String host() {
            return container.getHost();
        }

        @Override
        public int mappedPort() {
            return container.getMappedPort(port);
        }

        @Override
        public void close() {
            container.stop();
        }
    }
}
