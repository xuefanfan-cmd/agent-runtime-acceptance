package com.huawei.ascend.sit.lifecycle;

/**
 * {@link SutLauncher} that dispatches to the right per-runtime launcher based on the agent's
 * mode: {@link PythonProcessLauncher} for a managed Python agent (i.e. {@link SutAgent#isPython()}
 * is {@code true}), else {@link ProcessLauncher} for a managed Java jar. Remote agents never
 * reach a launcher — {@link SutStack#start()} handles them separately.
 *
 * <p>Used by {@link SutStack.Builder#start()} as the default so a mixed-runtime stack
 * (e.g. a Java caller + Python deep-research agent) just works out of the box; tests that need
 * a specialized backend can still install a single-runtime launcher via
 * {@link SutStack.Builder#launcher(SutLauncher)}.
 */
public final class CompositeLauncher implements SutLauncher {

    private final SutLauncher javaLauncher;
    private final SutLauncher pythonLauncher;

    public CompositeLauncher(SutLauncher javaLauncher, SutLauncher pythonLauncher) {
        if (javaLauncher == null) {
            throw new IllegalArgumentException("javaLauncher must not be null");
        }
        if (pythonLauncher == null) {
            throw new IllegalArgumentException("pythonLauncher must not be null");
        }
        this.javaLauncher = javaLauncher;
        this.pythonLauncher = pythonLauncher;
    }

    @Override
    public SutInstance start(SutAgent agent, AgentConfig config) {
        if (agent.isPython()) {
            return pythonLauncher.start(agent, config);
        }
        return javaLauncher.start(agent, config);
    }
}
