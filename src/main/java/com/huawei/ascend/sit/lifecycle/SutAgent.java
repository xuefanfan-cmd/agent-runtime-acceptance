package com.huawei.ascend.sit.lifecycle;

/**
 * Immutable descriptor of one System-Under-Test agent.
 *
 * <p>Declares <em>what</em> the agent is — its logical name, its Maven artifact,
 * its role in the chain, and (for non-leaf agents) the name of the downstream
 * agent it dispatches to. The {@link SutStack} turns these descriptors into
 * running {@link SutInstance}s in leaf-first order, injecting each downstream's
 * resolved base URL into its upstream via {@link AgentConfig#REMOTE_AGENTS_URL}.
 */
public final class SutAgent {

    /** Position of an agent in the dispatch chain. Drives leaf-first launch order. */
    public enum Role {
        /** Entry point — has a downstream, no upstream (e.g. mainplan). */
        ENTRY,
        /** Middle — has both a downstream and an upstream (e.g. trip). */
        MIDDLE,
        /** Leaf — no downstream, the chain terminator (e.g. hotel). */
        LEAF
    }

    private final String name;
    private final MavenArtifact artifact;
    private final Role role;
    private final String downstream;          // null for LEAF
    private final String remoteAgentsProperty; // where the downstream URL is injected

    public SutAgent(String name, MavenArtifact artifact, Role role,
                    String downstream, String remoteAgentsProperty) {
        this.name = requireNonBlank(name, "name");
        this.artifact = requireNonNull(artifact, "artifact");
        this.role = requireNonNull(role, "role");
        this.downstream = downstream; // may be null for LEAF
        this.remoteAgentsProperty = remoteAgentsProperty != null
                ? remoteAgentsProperty
                : AgentConfig.REMOTE_AGENTS_URL;
    }

    public String name() {
        return name;
    }

    public MavenArtifact artifact() {
        return artifact;
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
