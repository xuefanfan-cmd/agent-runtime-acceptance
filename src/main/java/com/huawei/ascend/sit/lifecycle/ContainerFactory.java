package com.huawei.ascend.sit.lifecycle;

import java.util.Map;

/**
 * Strategy seam for starting a backing-service container. The default {@link TestContainerFactory}
 * uses Testcontainers {@code GenericContainer}; tests inject a fake to exercise resolution without
 * Docker (parallels {@link SutLauncher} for agents).
 */
public interface ContainerFactory {
    /** Start {@code image} exposing {@code port}, applying {@code env} via {@code withEnv}; block until ready. */
    ManagedContainer start(String image, int port, Map<String, String> env);
}
