package com.huawei.ascend.sit.lifecycle;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.Map;

/**
 * Default {@link ContainerFactory} backed by Testcontainers {@link GenericContainer}: exposes the
 * internal port (random mapped host port for parallel coexistence), applies env via {@code withEnv}.
 *
 * <p>When constructed with a non-null {@code logDir}, each started service container also streams its
 * stdout+stderr to {@code <logDir>/<name>/stdout.log} via a {@link ContainerLogAppender} log consumer,
 * mirroring {@link ProcessLauncher}'s per-process redirect so every SUT service (process or container)
 * lives under one uniform {@code target/sit-logs/<name>/} tree.
 */
public final class TestContainerFactory implements ContainerFactory {

    private final Path logDir;

    /** Capture container logs into {@code logDir/<name>/stdout.log}. Pass {@code null} to disable. */
    public TestContainerFactory(Path logDir) {
        this.logDir = logDir;
    }

    @Override
    public ManagedContainer start(String name, String image, int port, Map<String, String> env) {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(port);
        env.forEach(c::withEnv);
        ContainerLogAppender appender = (logDir != null && name != null && !name.isBlank())
                ? ContainerLogAppender.open(logDir, name) : null;
        if (appender != null) {
            c.withLogConsumer(appender); // registered before start() → captures from container boot
        }
        try {
            c.start();
        } catch (RuntimeException e) {
            if (appender != null) {
                appender.closeQuietly(); // never leak the writer if the container fails to start
            }
            throw e;
        }
        return new Started(c, port, appender);
    }

    private static final class Started implements ManagedContainer {
        private final GenericContainer<?> container;
        private final int port;
        private final ContainerLogAppender appender;

        Started(GenericContainer<?> container, int port, ContainerLogAppender appender) {
            this.container = container;
            this.port = port;
            this.appender = appender;
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
            try {
                container.stop(); // ends the docker log stream first so the consumer drains
            } finally {
                if (appender != null) {
                    appender.closeQuietly();
                }
            }
        }
    }
}
