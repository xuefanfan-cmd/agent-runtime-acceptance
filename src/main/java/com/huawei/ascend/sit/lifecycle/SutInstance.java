package com.huawei.ascend.sit.lifecycle;

/**
 * A ready SUT agent the framework can talk to.
 *
 * <p>Closed by {@link SutStack} in reverse launch order. There are two kinds:
 * <ul>
 *   <li>{@link Managed} — a {@code java -jar} process the framework launched (random port via
 *       {@code --server.port=0}, actual port resolved from its PID); {@link Managed#close()}
 *       stops that process.</li>
 *   <li>{@link Remote} — a pre-deployed service used by address only; {@link Remote#close()}
 *       is a <strong>no-op</strong>: the framework never starts or stops a service it does
 *       not own.</li>
 * </ul>
 * The sealed hierarchy makes "never stop a remote service" a compile-time-checked invariant.
 */
public sealed interface SutInstance extends AutoCloseable permits ManagedSutInstance, RemoteSutInstance {

    /** Logical agent name (the key used in {@code SutStack.agent(name, ...)}). */
    String name();

    /** Base URL without trailing slash, e.g. {@code http://localhost:38211}. */
    String baseUrl();

    /** {@code true} if this instance is a pre-deployed service the framework does not own. */
    default boolean isRemote() {
        return false;
    }

    /**
     * Tear the instance down. Narrowed from {@link AutoCloseable#close()} to throw nothing:
     * managed agents stop their process idempotently; remote agents are a no-op.
     */
    @Override
    void close();
}
