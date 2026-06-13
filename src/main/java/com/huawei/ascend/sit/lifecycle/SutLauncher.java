package com.huawei.ascend.sit.lifecycle;

/**
 * Strategy for bringing a {@link SutAgent} up as a ready {@link SutInstance}.
 *
 * <p>The default {@link ProcessLauncher} runs the agent as a black-box
 * {@code java -jar} process (no SUT code on the test classpath, in keeping with
 * the black-box philosophy). Alternative implementations can launch in-process
 * (fastest, dev only — pulls SUT classes in) or in a container (strongest
 * isolation, for CI/gating) without changing how tests describe their stack.
 *
 * <p>Implementations are responsible for: resolving the artifact, selecting a
 * port, starting the process, and blocking until the agent is ready
 * ({@code GET /.well-known/agent.json} → 200).
 */
public interface SutLauncher {

    /**
     * Start the agent described by {@code agent} using the overrides in {@code config},
     * blocking until it is ready.
     *
     * @return a ready {@link SutInstance}
     */
    SutInstance start(SutAgent agent, AgentConfig config);
}
