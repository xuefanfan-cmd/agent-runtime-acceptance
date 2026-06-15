package com.huawei.ascend.sit.lifecycle;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.AgentCard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An ordered set of SUT agents launched together and wired into a chain.
 *
 * <p>This is the framework's top-level lifecycle abstraction. A test (typically
 * per-class, via {@code BaseManagedStackTest}) declares <em>which</em> agents it
 * needs and <em>how</em> each is configured; {@code start()} launches them
 * <strong>leaf-first</strong>, reading each downstream's resolved base URL and
 * injecting it into its upstream's {@code agent-runtime.remote-agents[0].url}.
 * Random free ports make multiple stacks coexist in one JVM without conflict.
 *
 * <p>Example — single agent (A-01, no chain, no LLM):
 * <pre>{@code
 * SutStack stack = SutStack.builder(config)
 *         .agent("mainplan")
 *         .start();
 * AgentCard card = stack.client("mainplan").getAgentCard();
 * }</pre>
 *
 * <p>Example — full chain (auto-wires mainplan→trip→hotel):
 * <pre>{@code
 * SutStack stack = SutStack.builder(config)
 *         .agent("hotel")
 *         .agent("trip", a -> a.role(MIDDLE).downstream("hotel"))
 *         .agent("mainplan", a -> a.role(ENTRY).downstream("trip"))
 *         .start();
 * }</pre>
 *
 * <p>Agent coordinates and base profile default from {@code sut.agents.<name>.*}
 * in {@code application-*.yml}; the per-agent consumer overrides anything.
 * Implements {@link AutoCloseable} so it can back {@code @AfterAll} teardown.
 */
public final class SutStack implements AutoCloseable {

    private final SutLauncher launcher;
    private final Map<String, Entry> specs;     // declaration order
    private final Map<String, SutInstance> instances = new LinkedHashMap<>();
    private final boolean streaming;

    private SutStack(SutLauncher launcher, Map<String, Entry> specs, boolean streaming) {
        this.launcher = launcher;
        this.specs = specs;
        this.streaming = streaming;
    }

    /** Begin describing a stack against the given configuration. */
    public static Builder builder(TestConfig config) {
        return new Builder(config);
    }

    /** Launch all declared agents leaf-first, wiring each upstream to its downstream. */
    public SutStack start() {
        Set<String> launched = new java.util.HashSet<>();
        while (launched.size() < specs.size()) {
            boolean progressed = false;
            for (Entry entry : specs.values()) {
                String name = entry.agent.name();
                if (launched.contains(name)) {
                    continue;
                }
                String downstream = entry.agent.downstream();
                if (downstream != null && !launched.contains(downstream)) {
                    continue; // wait until the downstream is up so we know its URL
                }
                if (downstream != null) {
                    entry.config.downstreamUrl(instances.get(downstream).baseUrl());
                }
                instances.put(name, launcher.start(entry.agent, entry.config));
                launched.add(name);
                progressed = true;
            }
            if (!progressed) {
                throw new IllegalStateException(
                        "Could not resolve leaf-first launch order (cycle or unknown downstream) "
                                + "for agents: " + specs.keySet());
            }
        }
        return this;
    }

    /** Base URL of a launched agent, e.g. {@code http://localhost:38211}. */
    public String baseUrl(String name) {
        return requireInstance(name).baseUrl();
    }

    /** Resolved port of a launched agent. */
    public int port(String name) {
        return requireInstance(name).port();
    }

    /**
     * Build an {@link A2aServiceClient} bound to the agent's resolved base URL.
     * Constructed the same way as the external-SUT client, so tests switch
     * between managed and pre-deployed SUTs without changing call sites.
     */
    public A2aServiceClient client(String name) {
        String baseUrl = requireInstance(name).baseUrl();
        AgentCard card = A2A.getAgentCard(baseUrl);
        // sync (message/send) unless the stack opted into streaming (message/stream).
        ClientConfig clientConfig = new ClientConfig.Builder()
                .setAcceptedOutputModes(List.of("text"))
                .setStreaming(streaming)
                .build();
        Client client = Client.builder(card)
                .clientConfig(clientConfig)
                .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                .build();
        return new A2aServiceClient(baseUrl, client, card);
    }

    /** Stop all agents in reverse launch order. */
    @Override
    public void close() {
        List<String> order = new java.util.ArrayList<>(instances.keySet());
        java.util.Collections.reverse(order);
        for (String name : order) {
            instances.get(name).stop();
        }
    }

    private SutInstance requireInstance(String name) {
        SutInstance instance = instances.get(name);
        if (instance == null) {
            throw new IllegalStateException(
                    "No running instance named '" + name + "'. Declared: " + specs.keySet());
        }
        return instance;
    }

    /** A declared agent plus its overrides. */
    private record Entry(SutAgent agent, AgentConfig config) {}

    // ---- builder ----

    /** Fluent builder for a {@link SutStack}. */
    public static final class Builder {

        private final TestConfig config;
        private SutLauncher launcher;
        private boolean streaming = false;
        private final Map<String, Entry> specs = new LinkedHashMap<>();

        private Builder(TestConfig config) {
            this.config = config;
        }

        /** Override the default {@link ProcessLauncher} backend. */
        public Builder launcher(SutLauncher launcher) {
            this.launcher = launcher;
            return this;
        }

        /**
         * Whether clients built by {@link #client(String)} use streaming ({@code message/stream},
         * {@code true}) or synchronous ({@code message/send}, {@code false}). Defaults to sync
         * ({@code false}); the SUT does not currently support streaming.
         */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /** Declare an agent using only defaults from {@code sut.agents.<name>.*}. */
        public Builder agent(String name) {
            return agent(name, a -> {});
        }

        /**
         * Declare an agent and configure it. The consumer receives an
         * {@link AgentBuilder} pre-seeded with config defaults (artifact, profile).
         */
        public Builder agent(String name, Consumer<AgentBuilder> configure) {
            AgentBuilder builder = new AgentBuilder(config, name);
            configure.accept(builder);
            Entry entry = builder.build();
            if (specs.containsKey(name)) {
                throw new IllegalStateException("Agent '" + name + "' declared twice");
            }
            specs.put(name, entry);
            return this;
        }

        /** Launch the declared agents and return the started stack. */
        public SutStack start() {
            if (launcher == null) {
                launcher = new ProcessLauncher(config);
            }
            return new SutStack(launcher, specs, streaming).start();
        }
    }

    /** Per-agent configurator used inside {@link Builder#agent(String, Consumer)}. */
    public static final class AgentBuilder {

        private final TestConfig config;
        private final String name;
        private MavenArtifact artifact;
        private SutAgent.Role role = SutAgent.Role.LEAF;
        private String downstream;
        private String remoteAgentsProperty = AgentConfig.REMOTE_AGENTS_URL;
        private final AgentConfig configOverrides = new AgentConfig();

        private AgentBuilder(TestConfig config, String name) {
            this.config = config;
            this.name = name;
        }

        /** Set the artifact; defaults to {@code sut.agents.<name>.*} coordinates. */
        public AgentBuilder artifact(String gav) {
            this.artifact = MavenArtifact.parse(gav);
            return this;
        }

        public AgentBuilder role(SutAgent.Role role) {
            this.role = role;
            return this;
        }

        /** Name of the downstream agent to wire into (via remote-agents[0].url). */
        public AgentBuilder downstream(String downstream) {
            this.downstream = downstream;
            return this;
        }

        /** Spring profile to activate for this agent (default from config). */
        public AgentBuilder profile(String profile) {
            this.configOverrides.profile(profile);
            return this;
        }

        /** Spring Boot property override ({@code --key=value}). */
        public AgentBuilder property(String key, String value) {
            this.configOverrides.property(key, value);
            return this;
        }

        /** Environment variable for the launched process. */
        public AgentBuilder env(String key, String value) {
            this.configOverrides.env(key, value);
            return this;
        }

        private Entry build() {
            MavenArtifact resolvedArtifact = artifact != null ? artifact : defaultArtifact();
            if (configOverrides.profile() == null || configOverrides.profile().isBlank()) {
                String defaultProfile = config.getString("sut.agents." + name + ".profile", "");
                if (defaultProfile != null && !defaultProfile.isBlank()) {
                    configOverrides.profile(defaultProfile);
                }
            }
            SutAgent agent = new SutAgent(name, resolvedArtifact, role, downstream, remoteAgentsProperty);
            return new Entry(agent, configOverrides);
        }

        private MavenArtifact defaultArtifact() {
            String group = config.getString("sut.agents." + name + ".group");
            String artifactId = config.getString("sut.agents." + name + ".artifact");
            String version = config.getString("sut.agents." + name + ".version");
            if (group == null || artifactId == null || version == null) {
                throw new IllegalStateException(
                        "No artifact configured for agent '" + name + "'. Set sut.agents." + name
                                + ".{group,artifact,version} or call .artifact(g:a:v).");
            }
            return new MavenArtifact(group, artifactId, version);
        }
    }
}
