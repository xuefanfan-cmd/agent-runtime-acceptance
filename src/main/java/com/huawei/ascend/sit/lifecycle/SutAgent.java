package com.huawei.ascend.sit.lifecycle;

import java.util.List;

/**
 * Immutable descriptor of one System-Under-Test agent.
 *
 * <p>Declares <em>what</em> the agent is — its logical name, its launch mode
 * ({@linkplain #artifact() managed jar} <em>or</em> {@linkplain #remoteUrl() pre-deployed
 * remote}), and the names of the downstream agents it dispatches to. The {@link SutStack} turns
 * these descriptors into {@link SutInstance}s: a managed agent is launched as a {@code java -jar}
 * process (random port via {@code --server.port=0}, actual port resolved from its PID); a remote
 * agent is assumed already up and used by address only — the framework never starts or stops it.
 *
 * <p>The two launch modes are mutually exclusive: exactly one of {@link #artifact()} /
 * {@link #remoteUrl()} is set. Either way, the {@link SutStack} injects each downstream's
 * resolved base URL into its upstream's {@code remote-agents[i].url} slots (managed agents only;
 * a remote agent's internal wiring is its deployer's responsibility).
 */
public final class SutAgent {

    private final String name;
    private final MavenArtifact artifact;   // null when remote
    private final String remoteUrl;         // null when managed
    private final List<String> downstreams; // empty for a leaf (chain terminator)

    /** Managed (launched) agent: {@code artifact} set, {@code remoteUrl} null. */
    public SutAgent(String name, MavenArtifact artifact, List<String> downstreams) {
        this(name, artifact, null, downstreams);
    }

    /** Common constructor; exactly one of {@code artifact}/{@code remoteUrl} must be non-null. */
    public SutAgent(String name, MavenArtifact artifact, String remoteUrl, List<String> downstreams) {
        this.name = requireNonBlank(name, "name");
        this.artifact = artifact;
        this.remoteUrl = remoteUrl == null || remoteUrl.isBlank() ? null : remoteUrl;
        if ((artifact == null) == (this.remoteUrl == null)) {
            throw new IllegalArgumentException(
                    "Agent '" + name + "' must be exactly one of: managed (artifact set) "
                            + "or remote (remoteUrl set).");
        }
        this.downstreams = downstreams == null ? List.of() : List.copyOf(downstreams);
    }

    public String name() {
        return name;
    }

    /** Maven coordinates of the managed jar, or {@code null} for a remote agent. */
    public MavenArtifact artifact() {
        return artifact;
    }

    /** Pre-deployed base URL (with port), or {@code null} for a managed agent. */
    public String remoteUrl() {
        return remoteUrl;
    }

    /** {@code true} for a pre-deployed agent used by address only (never launched/stopped). */
    public boolean isRemote() {
        return remoteUrl != null;
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
