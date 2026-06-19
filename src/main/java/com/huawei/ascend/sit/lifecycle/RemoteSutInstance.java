package com.huawei.ascend.sit.lifecycle;

/**
 * A pre-deployed SUT agent used by address only.
 *
 * <p>The framework did not launch it and <strong>does not own its lifecycle</strong>: no port
 * is allocated, no process is held, and {@link #close()} is a no-op (the framework never stops
 * a service it did not start). The base URL — including its port — comes from
 * {@code sut.agents.<name>.url} in YAML (the test declares the agent with
 * {@code SutStack.Builder.agent(name)}; a configured {@code url} makes it remote). Use the same
 * as any other instance: {@link SutStack#client(String)} binds an A2A client to {@link #baseUrl()}.
 */
public final class RemoteSutInstance implements SutInstance {

    private final String name;
    private final String baseUrl;

    public RemoteSutInstance(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    /** No-op: a remote service is not owned by the framework and must not be stopped here. */
    @Override
    public void close() {
        // intentionally empty
    }
}
