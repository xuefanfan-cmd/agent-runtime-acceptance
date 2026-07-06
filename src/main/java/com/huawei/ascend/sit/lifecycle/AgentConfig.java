package com.huawei.ascend.sit.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable builder for the configuration of a single SUT agent instance.
 *
 * <p>Normalises every override into Spring Boot <em>command-line args</em>
 * ({@code --key=value}). Command-line args take the highest precedence in Spring
 * Boot's property resolution, so they deterministically override the agent's
 * bundled {@code application.yaml} — including profile-specific overlays.
 * This single mechanism is how the framework flexibly configures the whole chain
 * without touching agent code: ports, middleware (checkpointer in-memory↔redis),
 * LLM wiring, and downstream URL injection all flow through {@link #property}.
 */
public final class AgentConfig {

    /**
     * Default Spring property-key prefix for wiring an upstream agent to its downstreams (the
     * spring-ai-ascend agent-runtime convention). Spring Boot binds a list from indexed keys, so the
     * i-th downstream goes to {@code agent-runtime.remote-agents[i].url}. Override per agent — e.g.
     * for one built on a different runtime framework — via {@code sut.agents.<name>.remote-agents-prefix}.
     */
    public static final String REMOTE_AGENTS_URL_PREFIX = "agent-runtime.remote-agents";

    /**
     * How {@code ProcessLauncher} decides a managed agent is ready (and, for a random port, which
     * listening port is the server). {@link #AGENT_CARD} (default) probes
     * {@code GET /.well-known/agent.json} for a 200 — the A2A convention, which also disambiguates
     * the server port when the process opens several listeners. {@link #TCP} treats the process as
     * ready the moment it owns a TCP LISTEN port (recovered from the PID via {@link ListeningPorts})
     * — for non-A2A services (e.g. a hand-written REST gateway) that do not serve an agent card.
     */
    public enum ReadyMode {
        /** Ready when {@code GET /.well-known/agent.json} returns 200. */
        AGENT_CARD,
        /** Ready when the process owns at least one TCP LISTEN port. */
        TCP
    }

    private String profile = "";
    private int port = 0; // 0 => the OS assigns a random port (--server.port=0)
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Map<String, String> environment = new LinkedHashMap<>();

    /**
     * Per-downstream custom Spring property key that replaces the default positional
     * {@code <remoteAgentsPrefix>[i].url} slot, keyed by downstream name. An absent entry means that
     * downstream falls back to a positional slot. Seeded by
     * {@code SutStack.AgentBuilder.downstream(name, propertyKey)}.
     */
    private final Map<String, String> downstreamPropertyKeys = new LinkedHashMap<>();

    /**
     * Property-key prefix used to inject a downstream agent's base URL: the i-th downstream goes to
     * {@code <prefix>[i].url}. Defaults to {@link #REMOTE_AGENTS_URL_PREFIX}; overridden per agent
     * from {@code sut.agents.<name>.remote-agents-prefix} for agents built on a different runtime
     * framework (e.g. {@code openjiuwen.service.a2a.remote-agents}).
     */
    private String remoteAgentsPrefix = REMOTE_AGENTS_URL_PREFIX;

    /**
     * Per-agent JVM {@code -D} system-property overrides, layered on top of the global
     * {@code sut.java.system-properties} (a per-agent value replaces the same-named global one).
     * Seeded from {@code sut.agents.<name>.java.system-properties} by
     * {@code SutStack.AgentBuilder.build()}.
     */
    private final Map<String, String> jvmSystemProperties = new LinkedHashMap<>();

    /**
     * When non-null, the framework redirects this managed agent's advertised agent-card endpoint
     * through a toxiproxy fault link: at launch it injects {@code --<key>=<listenUrl><path>} so the
     * agent advertises the proxy (not its real address) in its card. Any caller that discovers the
     * agent via its card then routes JSON-RPC through the proxy — {@code resetPeer()} can sever it.
     * {@code null} (default) ⇒ no redirect. See {@code SutStack} for the two-phase wiring.
     */
    private String cardEndpointRedirectKey;
    private String cardEndpointRedirectPath = "/a2a";

    /** How readiness is determined for this agent (default {@link ReadyMode#AGENT_CARD}). */
    private ReadyMode readyMode = ReadyMode.AGENT_CARD;

    public AgentConfig profile(String profile) {
        this.profile = profile;
        return this;
    }

    public String profile() {
        return profile;
    }

    public AgentConfig port(int port) {
        this.port = port;
        return this;
    }

    public int port() {
        return port;
    }

    /** Add a Spring Boot property override (emitted as {@code --key=value}). */
    public AgentConfig property(String key, String value) {
        properties.put(key, value);
        return this;
    }

    public Map<String, String> properties() {
        return properties;
    }

    /** Add an environment variable for the launched process (e.g. LLM API keys). */
    public AgentConfig env(String key, String value) {
        environment.put(key, value);
        return this;
    }

    public Map<String, String> environment() {
        return environment;
    }

    /**
     * The property-key prefix used to inject downstream URLs ({@code <prefix>[i].url}); defaults to
     * {@link #REMOTE_AGENTS_URL_PREFIX}. Overridden per agent from YAML.
     */
    public String remoteAgentsPrefix() {
        return remoteAgentsPrefix;
    }

    /**
     * Override the property-key prefix for downstream URL injection. Package-private — set only by
     * {@code SutStack.AgentBuilder.build()} from {@code sut.agents.<name>.remote-agents-prefix}; not
     * exposed on the public builder API (YAML-only, consistent with managed/remote/profile). A blank
     * value is a no-op (keeps the default); otherwise the value is trimmed and a single trailing dot
     * is stripped (guards against a {@code "foo.bar."} typo producing {@code foo.bar.[i].url}).
     */
    AgentConfig remoteAgentsPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return this;
        }
        String normalized = prefix.trim();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.remoteAgentsPrefix = normalized;
        return this;
    }

    /**
     * Per-agent JVM {@code -D} overrides (read-only view). Layered on top of the global
     * {@code sut.java.system-properties}; a per-agent value replaces the same-named global one.
     */
    public Map<String, String> jvmSystemProperties() {
        return Collections.unmodifiableMap(jvmSystemProperties);
    }

    /**
     * Add one per-agent JVM {@code -D} override. Package-private — seeded only by
     * {@code SutStack.AgentBuilder.build()} from {@code sut.agents.<name>.java.system-properties};
     * not exposed on the public builder API (YAML-only, consistent with remoteAgentsPrefix).
     */
    AgentConfig jvmSystemProperty(String key, String value) {
        jvmSystemProperties.put(key, value);
        return this;
    }

    /**
     * Wire a downstream agent's base URL into this upstream's i-th remote-agents slot:
     * {@code <remoteAgentsPrefix>[i].url} (default {@code agent-runtime.remote-agents[i].url}).
     * Index order is the caller's (declaration order).
     */
    public AgentConfig downstreamUrl(int index, String url) {
        return property(remoteAgentsPrefix + "[" + index + "].url", url);
    }

    /**
     * Register a custom property key for a named downstream: at launch the framework writes the
     * downstream's resolved base URL into this key (via {@link #property}) instead of the default
     * positional {@code <remoteAgentsPrefix>[i].url} slot. Package-private — seeded only by
     * {@code SutStack.AgentBuilder.downstream(name, propertyKey)}.
     */
    AgentConfig downstreamPropertyKey(String downstream, String propertyKey) {
        downstreamPropertyKeys.put(downstream, propertyKey);
        return this;
    }

    /**
     * The custom property key registered for a named downstream, or {@code null} when the downstream
     * should fall back to the default positional slot. Read by {@code SutStack.start()}.
     */
    public String downstreamPropertyKey(String downstream) {
        return downstreamPropertyKeys.get(downstream);
    }

    /**
     * Redirect this agent's advertised agent-card endpoint through a toxiproxy fault link, using the
     * default path {@code /a2a}. The framework injects {@code --<propertyKey>=<listenUrl>/a2a} at
     * launch. Equivalent to {@link #cardEndpointRedirect(String, String)} with {@code "/a2a"}.
     */
    public AgentConfig cardEndpointRedirect(String propertyKey) {
        return cardEndpointRedirect(propertyKey, "/a2a");
    }

    /**
     * Redirect this agent's advertised agent-card endpoint through a toxiproxy fault link, injecting
     * {@code --<propertyKey>=<listenUrl><path>} at launch. The path defaults to {@code /a2a} (the
     * A2A endpoint path); override it only if an agent serves A2A at a different path — never pass
     * an empty string (a bare base makes the SDK POST the root path → 404).
     */
    public AgentConfig cardEndpointRedirect(String propertyKey, String path) {
        this.cardEndpointRedirectKey = propertyKey;
        this.cardEndpointRedirectPath = path;
        return this;
    }

    /** The Spring property key whose value is redirected to the proxy, or {@code null} if no redirect. */
    public String cardEndpointRedirectKey() {
        return cardEndpointRedirectKey;
    }

    /** The path appended to the proxy listen URL in the injected property value (default {@code /a2a}). */
    public String cardEndpointRedirectPath() {
        return cardEndpointRedirectPath;
    }

    /** How readiness is determined for this agent (default {@link ReadyMode#AGENT_CARD}). */
    public AgentConfig readyMode(ReadyMode readyMode) {
        this.readyMode = readyMode == null ? ReadyMode.AGENT_CARD : readyMode;
        return this;
    }

    /** How readiness is determined for this agent (default {@link ReadyMode#AGENT_CARD}). */
    public ReadyMode readyMode() {
        return readyMode;
    }

    /**
     * Build the Spring Boot program-argument list: {@code server.port} (always emitted —
     * {@code 0} lets the OS pick a random port, which the launcher then resolves from the
     * PID), active profile, then property overrides in insertion order.
     */
    public List<String> toProgramArgs() {
        List<String> args = new ArrayList<>(properties.size() + 2);
        args.add("--server.port=" + port);
        if (profile != null && !profile.isBlank()) {
            args.add("--spring.profiles.active=" + profile);
        }
        properties.forEach((key, value) -> args.add("--" + key + "=" + value));
        return args;
    }
}
