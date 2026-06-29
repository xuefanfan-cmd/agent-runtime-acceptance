package com.huawei.ascend.sit.lifecycle;

/** A backing-service container started by a {@link ContainerFactory}; test JVM's view of its address. */
public interface ManagedContainer extends AutoCloseable {
    /** Host the test JVM / agents reach the container on (container's {@code getHost()}). */
    String host();
    /** Mapped host port for the declared internal port (container's {@code getMappedPort(port)}). */
    int mappedPort();
    /** Stop the container. */
    @Override
    void close();
}
