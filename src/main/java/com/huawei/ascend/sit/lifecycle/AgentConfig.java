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

    /** Spring property used to wire an upstream agent to its downstream in the chain. */
    public static final String REMOTE_AGENTS_URL = "agent-runtime.remote-agents[0].url";

    private String profile = "";
    private int port = 0; // 0 => the launcher selects a free port
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

    /** Wire the downstream agent's base URL into this upstream's remote-agents slot. */
    public AgentConfig downstreamUrl(String url) {
        return property(REMOTE_AGENTS_URL, url);
    }

    /**
     * Build the Spring Boot program-argument list: resolved {@code server.port},
     * active profile, then property overrides in insertion order. The launcher is
     * responsible for setting the resolved port before calling this.
     */
    public List<String> toProgramArgs() {
        List<String> args = new ArrayList<>(properties.size() + 2);
        if (port > 0) {
            args.add("--server.port=" + port);
        }
        if (profile != null && !profile.isBlank()) {
            args.add("--spring.profiles.active=" + profile);
        }
        properties.forEach((key, value) -> args.add("--" + key + "=" + value));
        return args;
    }
}
