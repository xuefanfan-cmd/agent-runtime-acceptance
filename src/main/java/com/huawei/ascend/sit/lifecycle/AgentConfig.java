package com.huawei.ascend.sit.lifecycle;

import java.util.ArrayList;
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
     * Spring property prefix for wiring an upstream agent to its downstreams. Spring Boot binds a
     * list from indexed keys, so the i-th downstream goes to {@code agent-runtime.remote-agents[i].url}.
     */
    public static final String REMOTE_AGENTS_URL_PREFIX = "agent-runtime.remote-agents";

    private String profile = "";
    private int port = 0; // 0 => the OS assigns a random port (--server.port=0)
    private final Map<String, String> properties = new LinkedHashMap<>();
    private final Map<String, String> environment = new LinkedHashMap<>();

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
     * Wire a downstream agent's base URL into this upstream's i-th remote-agents slot:
     * {@code agent-runtime.remote-agents[i].url}. Index order is the caller's (declaration order).
     */
    public AgentConfig downstreamUrl(int index, String url) {
        return property(REMOTE_AGENTS_URL_PREFIX + "[" + index + "].url", url);
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
