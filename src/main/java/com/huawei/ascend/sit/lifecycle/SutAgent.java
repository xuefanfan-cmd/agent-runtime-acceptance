package com.huawei.ascend.sit.lifecycle;

/**
 * Immutable descriptor of one System-Under-Test agent.
 *
 * <p>Declares <em>what</em> the agent is — its logical name, its launch mode
 * ({@linkplain #artifact() managed jar} <em>or</em> {@linkplain #remoteUrl() pre-deployed
 * remote}), its role in the chain, and (for non-leaf agents) the name of the downstream agent
 * it dispatches to. The {@link SutStack} turns these descriptors into {@link SutInstance}s:
 * a managed agent is launched as a {@code java -jar} process (random port via
 * {@code --server.port=0}, actual port resolved from its PID); a remote agent is assumed
 * already up and used by address only — the framework never starts or stops it.
 *
 * <p>The two launch modes are mutually exclusive: exactly one of {@link #artifact()} /
 * {@link #remoteUrl()} is set. Either way, the {@link SutStack} injects each downstream's
 * resolved base URL into its upstream's {@code remote-agents[0].url} (managed agents only;
 * a remote agent's internal wiring is its deployer's responsibility).
 */
public final class SutAgent {

    /** Position of an agent in the dispatch chain (leaf-first emerges from downstream-ready ordering). */
    public enum Role {
        /** Entry point — has a downstream, no upstream (e.g. mainplan). */
        ENTRY,
        /** Middle — has both a downstream and an upstream (e.g. trip). */
        MIDDLE,
        /** Leaf — no downstream, the chain terminator (e.g. hotel). */
        LEAF
    }

    private final String name;
    private final MavenArtifact artifact;      // null when remote
    private final String remoteUrl;            // null when managed
    private final Role role;
    private final String downstream;          // null for LEAF
    private final String remoteAgentsProperty; // where the downstream URL is injected

    /** Managed (launched) agent: {@code artifact} set, {@code remoteUrl} null. */
    public SutAgent(String name, MavenArtifact artifact, Role role,
                    String downstream, String remoteAgentsProperty) {
        this(name, artifact, null, role, downstream, remoteAgentsProperty);
    }

    /** Common constructor; exactly one of {@code artifact}/{@code remoteUrl} must be non-null. */
    public SutAgent(String name, MavenArtifact artifact, String remoteUrl, Role role,
                    String downstream, String remoteAgentsProperty) {
        this.name = requireNonBlank(name, "name");
        this.role = requireNonNull(role, "role");
        this.artifact = artifact;
        this.remoteUrl = remoteUrl == null || remoteUrl.isBlank() ? null : remoteUrl;
        if ((artifact == null) == (this.remoteUrl == null)) {
            throw new IllegalArgumentException(
                    "Agent '" + name + "' must be exactly one of: managed (artifact set) "
                            + "or remote (remoteUrl set).");
        }
        this.downstream = downstream; // may be null for LEAF
        this.remoteAgentsProperty = remoteAgentsProperty != null
                ? remoteAgentsProperty
                : AgentConfig.REMOTE_AGENTS_URL;
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

    public Role role() {
        return role;
    }

    /** Logical name of the downstream agent, or {@code null} for a leaf. */
    public String downstream() {
        return downstream;
    }

    public String remoteAgentsProperty() {
        return remoteAgentsProperty;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
