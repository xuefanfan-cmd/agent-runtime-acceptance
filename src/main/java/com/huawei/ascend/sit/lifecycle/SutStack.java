package com.huawei.ascend.sit.lifecycle;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.AgentCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An ordered set of SUT agents brought up together and wired into a chain (or a fan-out graph).
 *
 * <p>This is the framework's top-level lifecycle abstraction. A test (typically per-class,
 * via {@code BaseManagedStackTest}) declares <em>which</em> agents it needs and the
 * <em>topology</em> — for each agent, its downstream(s). {@code start()} brings them up in
 * dependency order and, for each <em>managed</em> upstream, injects each downstream's resolved
 * base URL into {@code agent-runtime.remote-agents[i].url} (one slot per downstream, in
 * declaration order). An agent with several downstreams gets several slots.
 *
 * <p><b>Managed vs remote is resolved from YAML — there is no code override.</b> For an agent
 * declared with {@code .agent(name)}, the framework reads {@code sut.agents.<name>}: a
 * {@code url} ⇒ remote (address only), {@code group}/{@code artifact}/{@code version} ⇒ managed
 * (launched). So switching a chain between managed and remote is a YAML edit, not a code change.
 *
 * <p><b>Launch order is derived, not imposed.</b> The only constraint is that an upstream's
 * downstreams must already be ready (their base URLs known) before the upstream is launched —
 * because each downstream URL is injected as a startup arg. A managed downstream becomes ready
 * when its PID-resolved port serves the agent card; a remote downstream is ready immediately.
 * For a linear chain this yields leaf-first order; independent agents may be brought up in any
 * order. Random ports (via {@code --server.port=0}, resolved from the PID) make multiple stacks
 * coexist in one JVM without conflict.
 *
 * <p>Example — a linear chain (auto-wires mainplan→trip→hotel); each agent's mode comes from YAML.
 * {@code .downstream(name)} wires a single downstream (the common case):
 * <pre>{@code
 * SutStack stack = SutStack.builder(config)
 *         .agent("hotel")
 *         .agent("trip", a -> a.downstream("hotel"))
 *         .agent("mainplan", a -> a.downstream("trip"))
 *         .start();
 * }</pre>
 * A fan-out agent uses {@code .downstreams(...)} (varargs) — each becomes its own
 * {@code remote-agents[i]} slot:
 * <pre>{@code
 * .agent("orchestrator", a -> a.downstreams("trip", "weather"))   // [0]=trip, [1]=weather
 * }</pre>
 * Either declaration runs fully managed when YAML gives each a {@code group/artifact/version},
 * or fully remote when YAML gives each a {@code url} (no processes are launched; the framework
 * only talks to them) — with no test-code change.
 *
 * <p>Agent coordinates and base profile default from {@code sut.agents.<name>.*} in
 * {@code application-*.yml}; the per-agent consumer may still override topology and profile
 * ({@code .downstream(...)} / {@code .downstreams(...)}, {@code .profile(...)}). Implements
 * {@link AutoCloseable} so it can back {@code @AfterAll} teardown (managed agents are stopped;
 * remote agents are left untouched).
 *
 * <p><b>Fault injection.</b> Once the stack is up, {@link #stop(String)} terminates a single
 * managed agent (the agent becomes unreachable at its base URL) and {@link #start(String)} revives
 * it on the <em>same</em> port — so upstreams' injected {@code remote-agents[i].url} keep working
 * with no rewiring. {@link #isRunning(String)} reports liveness. Remote agents cannot be
 * stopped/started (the framework does not own them).
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
     * (no launch); managed agents are launched once all their downstreams are ready, with each
     * downstream's resolved base URL injected into {@code agent-runtime.remote-agents[i].url}
     * (in declaration order).
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
                List<String> downstreams = entry.agent.downstreams();
                if (!ready.containsAll(downstreams)) {
                    continue; // wait until every downstream is ready so its base URL is known
                }
                if (entry.agent.isRemote()) {
                    instances.put(name, new RemoteSutInstance(name, entry.agent.remoteUrl()));
                } else {
                    for (int i = 0; i < downstreams.size(); i++) {
                        entry.config.downstreamUrl(i, instances.get(downstreams.get(i)).baseUrl());
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

    // ---- fault injection: per-agent stop / start (with port reuse) ----

    /**
     * Whether the named agent is currently up. For a managed agent this is its process liveness;
     * for a remote agent the framework does not own it and assumes it is up.
     */
    public boolean isRunning(String name) {
        SutInstance instance = requireInstance(name);
        if (instance instanceof ManagedSutInstance managed) {
            return managed.isAlive();
        }
        return true;
    }

    /**
     * Stop a single managed agent (fault injection): terminate its process but keep its resolved
     * port and base URL on record, so {@link #start(String)} can revive it on the <em>same</em>
     * port. While stopped the agent is unreachable at its base URL (calls fail with connection
     * refused) — i.e. the injected fault. Idempotent: stopping an already-stopped agent is a no-op.
     *
     * @throws IllegalStateException if the agent is remote (the framework never stops a service it
     *     did not start) or was never launched.
     */
    public void stop(String name) {
        SutInstance instance = requireInstance(name);
        if (instance instanceof RemoteSutInstance) {
            throw new IllegalStateException(
                    "Agent '" + name + "' is remote; the framework cannot stop a service it does not own.");
        }
        instance.close(); // ManagedSutInstance.close() is idempotent; process dies, port stays on record
    }

    /**
     * (Re)start a previously-stopped managed agent, reusing the port it first came up on. Because
     * the port is stable, every upstream that had this agent's base URL injected into
     * {@code agent-runtime.remote-agents[i].url} keeps working with no rewiring. The downstream URLs
     * this agent itself points at are re-emitted from its original config (those downstreams keep
     * their ports too), so the chain re-forms as-is.
     *
     * @throws IllegalStateException if the agent is still running (stop it first), is remote, was
     *     never launched, or could not come back up on its original port.
     */
    public void start(String name) {
        Entry entry = specs.get(name);
        if (entry == null) {
            throw new IllegalStateException(
                    "No agent declared named '" + name + "'. Declared: " + specs.keySet());
        }
        SutInstance instance = instances.get(name);
        if (instance == null) {
            throw new IllegalStateException(
                    "Agent '" + name + "' was never launched; start the stack first.");
        }
        if (instance instanceof RemoteSutInstance) {
            throw new IllegalStateException(
                    "Agent '" + name + "' is remote; the framework cannot (re)start a service it does not own.");
        }
        ManagedSutInstance managed = (ManagedSutInstance) instance;
        if (managed.isAlive()) {
            throw new IllegalStateException("Agent '" + name + "' is still running; stop() it first.");
        }
        // Pin the original port so the launcher's fixed-port path rebinds to it — keeping every
        // injected remote-agents[i].url valid without rewiring upstreams.
        int reusedPort = managed.port();
        entry.config().port(reusedPort);
        SutInstance revived = launcher.start(entry.agent(), entry.config());
        if (!(revived instanceof ManagedSutInstance m)) {
            throw new IllegalStateException("Restart of '" + name + "' did not yield a managed instance.");
        }
        if (m.port() != reusedPort) {
            throw new IllegalStateException("Restarted '" + name + "' on port " + m.port()
                    + " but expected the reused port " + reusedPort + ".");
        }
        instances.put(name, revived);
    }

    /**
     * Build an {@link A2aServiceClient} bound to the agent's resolved base URL.
     * Constructed the same way as the external-SUT client, so tests switch
     * between managed and pre-deployed SUTs without changing call sites.
     */
    public A2aServiceClient client(String name) {
        String baseUrl = requireInstance(name).baseUrl();
        AgentCard card = A2A.getAgentCard(baseUrl);
        // streaming (message/stream) by default; streaming(false) opts down to message/send.
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
        private boolean streaming = true;
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
         * {@code true}) or synchronous ({@code message/send}, {@code false}). Defaults to streaming
         * ({@code true}); pass {@code false} to force the synchronous {@code message/send} path.
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
         * {@link AgentBuilder} pre-seeded with config defaults (profile). An agent's launch mode
         * (managed vs remote) is <em>not</em> set here — it is resolved from
         * {@code sut.agents.<name>} in YAML: a {@code url} ⇒ remote, {@code group/artifact/version}
         * ⇒ managed. The consumer only adjusts topology / profile / properties.
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
        private final List<String> downstreams = new ArrayList<>();
        private final AgentConfig configOverrides = new AgentConfig();

        private AgentBuilder(TestConfig config, String name) {
            this.config = config;
            this.name = name;
        }

        /**
         * Wire a single downstream agent (the common, linear-chain case). Convenience for
         * {@code downstreams(name)}; the downstream's resolved base URL is injected into
         * {@code agent-runtime.remote-agents[0].url} at launch.
         */
        public AgentBuilder downstream(String downstream) {
            this.downstreams.add(downstream);
            return this;
        }

        /**
         * Wire one or more downstream agents. Each becomes its own indexed slot
         * ({@code agent-runtime.remote-agents[i].url}, in the given order), so a fan-out agent
         * dispatches to several downstreams.
         */
        public AgentBuilder downstreams(String... downstreams) {
            for (String d : downstreams) {
                this.downstreams.add(d);
            }
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
            // Mode is resolved purely from YAML: a configured `url` ⇒ remote (address only);
            // otherwise the group/artifact/version block ⇒ managed (launched). There is no code
            // override — switching an agent between managed and remote is a YAML edit.
            String yamlUrl = config.getString("sut.agents." + name + ".url", "");
            if (!yamlUrl.isBlank()) {
                return remoteEntry(yamlUrl);
            }
            MavenArtifact resolvedArtifact = defaultArtifact();
            if (configOverrides.profile() == null || configOverrides.profile().isBlank()) {
                String defaultProfile = config.getString("sut.agents." + name + ".profile", "");
                if (defaultProfile != null && !defaultProfile.isBlank()) {
                    configOverrides.profile(defaultProfile);
                }
            }
            SutAgent agent = new SutAgent(name, resolvedArtifact, downstreams);
            return new Entry(agent, configOverrides);
        }

        /** Build a remote (address-only) entry — no artifact/profile/port resolution. */
        private Entry remoteEntry(String url) {
            SutAgent agent = new SutAgent(name, null, url, downstreams);
            return new Entry(agent, configOverrides);
        }

        private MavenArtifact defaultArtifact() {
            String group = config.getString("sut.agents." + name + ".group");
            String artifactId = config.getString("sut.agents." + name + ".artifact");
            String version = config.getString("sut.agents." + name + ".version");
            if (group == null || artifactId == null || version == null) {
                throw new IllegalStateException(
                        "No artifact configured for agent '" + name + "'. Set sut.agents." + name
                                + ".{group,artifact,version} (managed), or sut.agents." + name
                                + ".url (remote), in application-*.yml.");
            }
            return new MavenArtifact(group, artifactId, version);
        }
    }
}
