package com.huawei.ascend.sit.lifecycle;

import java.util.List;

/**
 * Immutable descriptor of one System-Under-Test agent.
 *
 * <p>Declares <em>what</em> the agent is — its logical name, its launch mode
 * ({@linkplain #artifact() managed Java jar}, {@linkplain #pythonRuntime() managed Python
 * module}, or {@linkplain #remoteUrl() pre-deployed remote}), and the names of the downstream
 * agents it dispatches to. The {@link SutStack} turns these descriptors into
 * {@link SutInstance}s: a managed Java agent is launched as a {@code java -jar} process
 * (random port via {@code --server.port=0}, actual port resolved from its PID); a managed
 * Python agent is launched as a {@code python -u -m <module>} process (port picked by the
 * framework and injected via a per-agent env var, since the Python demo modules read their
 * bind port from an env var rather than a program arg); a remote agent is assumed already
 * up and used by address only — the framework never starts or stops it.
 *
 * <p>The three launch modes are mutually exclusive: exactly one of {@link #artifact()},
 * {@link #pythonRuntime()}, or {@link #remoteUrl()} is set. Either way, the {@link SutStack}
 * injects each downstream's resolved base URL — into the upstream's
 * {@code remote-agents[i].url} slot for Java agents, or into a named env var for Python
 * agents (via {@code sut.agents.<name>.python.downstream-env}).
 */
public final class SutAgent {

    private final String name;
    private final MavenArtifact artifact;   // set iff managed Java
    private final PythonRuntime pythonRuntime; // set iff managed Python
    private final String remoteUrl;         // set iff remote (pre-deployed)
    private final List<String> downstreams; // empty for a leaf (chain terminator)

    /** Managed Java agent: {@code artifact} set, other modes null. */
    public SutAgent(String name, MavenArtifact artifact, List<String> downstreams) {
        this(name, artifact, null, null, downstreams);
    }

    /** Managed Python agent: {@code pythonRuntime} set, other modes null. */
    public SutAgent(String name, PythonRuntime pythonRuntime, List<String> downstreams) {
        this(name, null, pythonRuntime, null, downstreams);
    }

    /**
     * Java-or-remote common constructor kept for source compatibility with call sites that
     * predate the Python launch mode. Delegates to the full four-mode constructor.
     */
    public SutAgent(String name, MavenArtifact artifact, String remoteUrl, List<String> downstreams) {
        this(name, artifact, null, remoteUrl, downstreams);
    }

    /** Full constructor; exactly one of {@code artifact}/{@code pythonRuntime}/{@code remoteUrl} must be non-null. */
    public SutAgent(String name, MavenArtifact artifact, PythonRuntime pythonRuntime,
                    String remoteUrl, List<String> downstreams) {
        this.name = requireNonBlank(name, "name");
        this.artifact = artifact;
        this.pythonRuntime = pythonRuntime;
        this.remoteUrl = remoteUrl == null || remoteUrl.isBlank() ? null : remoteUrl;
        int set = 0;
        if (this.artifact != null) set++;
        if (this.pythonRuntime != null) set++;
        if (this.remoteUrl != null) set++;
        if (set != 1) {
            throw new IllegalArgumentException(
                    "Agent '" + name + "' must be exactly one of: managed Java (artifact set), "
                            + "managed Python (pythonRuntime set), or remote (remoteUrl set).");
        }
        this.downstreams = downstreams == null ? List.of() : List.copyOf(downstreams);
    }

    public String name() {
        return name;
    }

    /** Maven coordinates of the managed Java jar, or {@code null} for non-Java modes. */
    public MavenArtifact artifact() {
        return artifact;
    }

    /** Python launch descriptor, or {@code null} for non-Python modes. */
    public PythonRuntime pythonRuntime() {
        return pythonRuntime;
    }

    /** Pre-deployed base URL (with port), or {@code null} for a managed agent. */
    public String remoteUrl() {
        return remoteUrl;
    }

    /** {@code true} for a pre-deployed agent used by address only (never launched/stopped). */
    public boolean isRemote() {
        return remoteUrl != null;
    }

    /** {@code true} for a managed Python agent (launched by {@code PythonProcessLauncher}). */
    public boolean isPython() {
        return pythonRuntime != null;
    }

    /**
     * Logical names of the downstream agents, in declaration order (each maps to one
     * {@code remote-agents[i]} slot). Empty for a leaf / chain terminator.
     */
    public List<String> downstreams() {
        return downstreams;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
