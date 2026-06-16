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
 * An ordered set of SUT agents brought up together and wired into a chain.
 *
 * <p>This is the framework's top-level lifecycle abstraction. A test (typically per-class,
 * via {@code BaseManagedStackTest}) declares <em>which</em> agents it needs and <em>how</em>
 * each is provided — either {@linkplain Builder#agent(String) managed} (launched as a
 * {@code java -jar} process on a random port) or {@linkplain Builder#remoteAgent(String, String)
 * remote} (a pre-deployed service used by address only). {@code start()} brings them up in
 * dependency order and, for each <em>managed</em> upstream, injects its downstream's resolved
 * base URL into {@code agent-runtime.remote-agents[0].url}.
 *
 * <p><b>Launch order is derived, not imposed.</b> The only constraint is that an upstream's
 * downstream must already be ready (its base URL known) before the upstream is launched —
 * because the downstream URL is injected as a startup arg. A managed downstream becomes ready
 * when its PID-resolved port serves the agent card; a remote downstream is ready immediately.
 * For a linear chain this yields leaf-first order; independent agents may be brought up in any
 * order. Random ports (via {@code --server.port=0}, resolved from the PID) make multiple stacks
 * coexist in one JVM without conflict.
 *
 * <p>Example — full managed chain (auto-wires mainplan→trip→hotel):
 * <pre>{@code
 * SutStack stack = SutStack.builder(config)
 *         .agent("hotel")
 *         .agent("trip", a -> a.role(MIDDLE).downstream("hotel"))
 *         .agent("mainplan", a -> a.role(ENTRY).downstream("trip"))
 *         .start();
 * }</pre>
 *
 * <p>Example — fully remote chain (no processes launched; the framework only talks to them):
 * <pre>{@code
 * SutStack stack = SutStack.builder(config)
 *         .remoteAgent("hotel", "http://hotel.host:8081")
 *         .remoteAgent("trip",  "http://trip.host:13001")
 *         .remoteAgent("mainplan", "http://mainplan.host:8080")
 *         .start();
 * }</pre>
 *
 * <p>Agent coordinates and base profile default from {@code sut.agents.<name>.*} in
 * {@code application-*.yml}; the per-agent consumer overrides anything. Implements
 * {@link AutoCloseable} so it can back {@code @AfterAll} teardown (managed agents are stopped;
 * remote agents are left untouched).
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

    /**
     * Bring up all declared agents in dependency order. Remote agents are registered by address
     * (no launch); managed agents are launched once their downstream is ready, with that
     * downstream's resolved base URL injected into {@code agent-runtime.remote-agents[0].url}.
     */
    public SutStack start() {
        Set<String> ready = new java.util.HashSet<>();
        while (ready.size() < specs.size()) {
            boolean progressed = false;
            for (Entry entry : specs.values()) {
                String name = entry.agent.name();
                if (ready.contains(name)) {
                    continue;
                }
                String downstream = entry.agent.downstream();
                if (downstream != null && !ready.contains(downstream)) {
                    continue; // wait until the downstream is ready so its base URL is known
                }
                if (entry.agent.isRemote()) {
                    instances.put(name, new RemoteSutInstance(name, entry.agent.remoteUrl()));
                } else {
                    if (downstream != null) {
                        entry.config.downstreamUrl(instances.get(downstream).baseUrl());
                    }
                    instances.put(name, launcher.start(entry.agent, entry.config));
                }
                ready.add(name);
                progressed = true;
            }
            if (!progressed) {
                throw new IllegalStateException(
                        "Could not resolve launch order (cycle or unknown downstream) "
                                + "for agents: " + specs.keySet());
            }
        }
        return this;
    }

    /** Base URL of an agent, e.g. {@code http://localhost:38211} (managed) or a remote address. */
    public String baseUrl(String name) {
        return requireInstance(name).baseUrl();
    }

    /**
     * Resolved port of a <em>managed</em> agent. Remote agents have no framework-allocated port;
     * call {@link #baseUrl(String)} for those.
     */
    public int port(String name) {
        SutInstance instance = requireInstance(name);
        if (instance instanceof ManagedSutInstance managed) {
            return managed.port();
        }
        throw new IllegalStateException(
                "Agent '" + name + "' is remote (" + instance.baseUrl() + "); no managed port.");
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

    /**
     * Tear down in reverse launch order: managed agents are stopped; remote agents are left
     * untouched (the framework never stops a service it did not start).
     */
    @Override
    public void close() {
        List<String> order = new java.util.ArrayList<>(instances.keySet());
        java.util.Collections.reverse(order);
        for (String name : order) {
            instances.get(name).close();
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
         * Declare a <em>pre-deployed</em> agent at the given base URL (with port). The framework
         * assumes it is already up — it is never launched or stopped; only its address is used.
         * Equivalent to {@code agent(name, a -> a.remoteUrl(url).downstream(...))} with further
         * overrides.
         */
        public Builder remoteAgent(String name, String remoteUrl) {
            return agent(name, a -> a.remoteUrl(remoteUrl));
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
        private String remoteUrl;                        // non-null => pre-deployed (no launch)
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

        /**
         * Mark this agent as <em>pre-deployed</em> at the given base URL (with port). The
         * framework uses it by address only — never launches or stops it. Mutually exclusive
         * with {@link #artifact(String)}: setting a remote URL makes the agent remote.
         */
        public AgentBuilder remoteUrl(String remoteUrl) {
            this.remoteUrl = remoteUrl;
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
            // Remote (pre-deployed): address-only, no artifact/profile/port resolution.
            if (remoteUrl != null && !remoteUrl.isBlank()) {
                SutAgent agent = new SutAgent(name, null, remoteUrl, role, downstream, remoteAgentsProperty);
                return new Entry(agent, configOverrides);
            }
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
