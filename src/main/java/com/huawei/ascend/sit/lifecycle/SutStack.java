package com.huawei.ascend.sit.lifecycle;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fault.FaultLink;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.AgentCard;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * base URL into {@code <remoteAgentsPrefix>[i].url} — by default {@code agent-runtime.remote-agents[i].url}
 * (the agent-runtime convention), overridable per agent via {@code sut.agents.<name>.remote-agents-prefix}
 * for agents built on a different runtime framework (one slot per downstream, in declaration order).
 * An agent with several downstreams gets several slots. {@link AgentBuilder#downstream(String, String)}
 * overrides this: it sends a downstream's resolved URL to an agent-specific property key (with no
 * positional slot) when the address must land somewhere other than {@code remote-agents[i].url}.
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

    /** Fault links created for agents that declared cardEndpointRedirect, keyed by agent name. */
    private final Map<String, FaultLink> faultLinks = new LinkedHashMap<>();

    /**
     * The toxiproxy container backing the fault links. {@code explicitContainer} is provided by the
     * test (the test owns its lifecycle); {@code ownedContainer} is started on demand by the stack
     * when some agent declares a redirect and no container was provided. {@link #container()}
     * resolves whichever is set.
     */
    private final ToxiproxyContainer explicitContainer;
    private ToxiproxyContainer ownedContainer;

    /**
     * Backing services this stack <em>owns</em> (started by the builder, stopped in {@link #close()}),
     * generalising {@code ownedContainer}. {@code null} when no backing services are referenced, or when
     * the test provided its own via {@code Builder.backingServices(...)} (test-owned, never stopped here —
     * mirrors {@code explicitContainer}).
     */
    private final BackingServices ownedServices;

    private SutStack(SutLauncher launcher, Map<String, Entry> specs, boolean streaming,
                     ToxiproxyContainer explicitContainer, BackingServices ownedServices) {
        this.launcher = launcher;
        this.specs = specs;
        this.streaming = streaming;
        this.explicitContainer = explicitContainer;
        this.ownedServices = ownedServices;
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
        // If any agent opts into a cardEndpointRedirect and the test didn't provide a container,
        // the stack owns one for the duration of its life (stopped in close()).
        if (needsFaultContainer() && container() == null) {
            ownedContainer = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:latest");
            ownedContainer.start();
        }

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
                    // Inject each downstream's resolved base URL. A custom-key downstream
                    // (downstream(name, propertyKey)) goes to its agent-specific property and skips a
                    // positional slot; the rest fill <remoteAgentsPrefix>[i].url contiguously.
                    int slot = 0;
                    for (String dsName : downstreams) {
                        String url = instances.get(dsName).baseUrl();
                        String customKey = entry.config.downstreamPropertyKey(dsName);
                        if (customKey != null) {
                            entry.config.property(customKey, url);
                        } else {
                            entry.config.downstreamUrl(slot, url);
                            slot++;
                        }
                    }
                    instances.put(name, startManaged(name, entry));
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

    /**
     * Launch one managed agent, optionally threading its advertised agent-card endpoint through a
     * toxiproxy fault link. Two-phase wiring (the ordering is the crux): the link and its listen URL
     * must exist <em>before</em> launch so the redirect property can carry that URL as a launch arg;
     * the link's upstream can only be associated to the agent's <em>real</em> port <em>after</em>
     * launch resolves it. Both phases complete before this returns.
     */
    private SutInstance startManaged(String name, Entry entry) {
        FaultLink link = null;
        if (entry.config.cardEndpointRedirectKey() != null) {
            // Placeholder upstream — listenUrl() is already stable and gets baked into the launch arg.
            link = FaultLink.toxiproxy(container(), "fault-" + name);
            entry.config.property(entry.config.cardEndpointRedirectKey(),
                    link.listenUrl() + entry.config.cardEndpointRedirectPath());
        }
        SutInstance instance = launcher.start(entry.agent, entry.config);
        if (link != null) {
            if (!(instance instanceof ManagedSutInstance managed)) {
                throw new IllegalStateException(
                        "Agent '" + name + "' declared cardEndpointRedirect but the launcher returned a "
                                + "non-managed instance; redirect needs a resolvable managed port.");
            }
            // Associate the link to the agent's real port now that launch resolved it. listen stays put.
            link.retarget(FaultLink.DEFAULT_UPSTREAM_HOST, managed.port());
            faultLinks.put(name, link);
        }
        return instance;
    }

    /** Whether any declared agent opted into a cardEndpointRedirect (i.e. needs a toxiproxy container). */
    private boolean needsFaultContainer() {
        for (Entry entry : specs.values()) {
            if (entry.config.cardEndpointRedirectKey() != null) {
                return true;
            }
        }
        return false;
    }

    /** The toxiproxy container backing fault links: the test-provided one, else the stack-owned one. */
    private ToxiproxyContainer container() {
        return explicitContainer != null ? explicitContainer : ownedContainer;
    }

    /**
     * The fault link for an agent that declared {@code cardEndpointRedirect} — call
     * {@code resetPeer()}/{@code restore()} on it to inject/recover a TCP break.
     *
     * @throws IllegalStateException if the agent did not declare a redirect (and thus has no link).
     */
    public FaultLink faultLink(String name) {
        FaultLink link = faultLinks.get(name);
        if (link == null) {
            throw new IllegalStateException("Agent '" + name
                    + "' did not declare cardEndpointRedirect, so no fault link exists for it. "
                    + "Agents with a redirect: " + faultLinks.keySet());
        }
        return link;
    }

    /** Base URL of an agent, e.g. {@code http://localhost:38211} (managed) or a remote address. */
    public String baseUrl(String name) {
        return requireInstance(name).baseUrl();
    }

    /**
     * Absolute HTTP base URL of a backing service this stack owns (e.g. the {@code envmock} mid-platform),
     * e.g. {@code http://localhost:33128}. Managed backing services are exposed by {@link BackingServices}
     * as a bare {@code host:port} (the binding {@code url-template} adds the scheme when injected into
     * agents); this accessor normalizes that to an absolute {@code http://} URL so HTTP clients can use it
     * directly — mirroring {@link #baseUrl(String)}, which agents already advertise with a scheme. A remote
     * backing service (declared with a {@code url}) is returned verbatim.
     *
     * @throws IllegalStateException if the stack owns no backing services, or the named service was not
     *     referenced by any managed agent's {@code service-bindings}.
     */
    public String serviceUrl(String serviceName) {
        if (ownedServices == null) {
            throw new IllegalStateException("Backing service '" + serviceName
                    + "' is not resolvable: this stack owns no backing services. Declare sut.services."
                    + serviceName + " and reference it from a managed agent's service-bindings "
                    + "(sut.agents.<name>.service-bindings." + serviceName + ").");
        }
        String url = ownedServices.url(serviceName);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "http://" + url;
        }
        return url;
    }

    /**
     * The running instance for a launched agent, or {@code null} if the agent was never started
     * (e.g. remote-only stack). Used by integration gates that read managed agent stdout logs.
     */
    public SutInstance managedInstance(String name) {
        return instances.get(name);
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
     * Tear down in the only correct order: managed agents first (reverse launch order; remote agents
     * untouched), then fault links (proxy.delete — needs the container still alive), then the
     * stack-owned toxiproxy container. A test-provided ({@code explicitContainer}) is never stopped
     * here — its lifecycle is the test's.
     */
    @Override
    public void close() {
        List<String> order = new java.util.ArrayList<>(instances.keySet());
        java.util.Collections.reverse(order);
        for (String name : order) {
            instances.get(name).close();
        }
        for (FaultLink link : faultLinks.values()) {
            link.close();
        }
        faultLinks.clear();
        if (ownedServices != null) {
            ownedServices.close();
        }
        if (ownedContainer != null) {
            ownedContainer.stop();
            ownedContainer = null;
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
        private ToxiproxyContainer explicitContainer;
        private BackingServices explicitBackingServices;
        private ContainerFactory containerFactory;
        private final Map<String, Entry> specs = new LinkedHashMap<>();

        private Builder(TestConfig config) {
            this.config = config;
        }

        /**
         * Provide a toxiproxy container the stack should use for any {@code cardEndpointRedirect}
         * agents (advanced — e.g. sharing one container across stacks). The stack never stops it;
         * its lifecycle stays the test's. When omitted, the stack starts (and stops) its own
         * container on demand the moment some agent declares a redirect.
         */
        public Builder toxiproxy(ToxiproxyContainer container) {
            this.explicitContainer = container;
            return this;
        }

        /**
         * Provide a pre-built {@link BackingServices} the stack should use for any {@code service-bindings}
         * agents (advanced — e.g. sharing one set across stacks). The stack never closes it; its lifecycle
         * stays the test's (mirrors {@link #toxiproxy(ToxiproxyContainer)}). When omitted, the stack builds
         * (and closes) its own from {@code sut.services.*} on demand.
         */
        public Builder backingServices(BackingServices services) {
            this.explicitBackingServices = services;
            return this;
        }

        /**
         * Override the default {@link TestContainerFactory} used to start managed backing-service containers.
         * Intended for tests (inject a fake to exercise resolution without Docker).
         */
        public Builder containerFactory(ContainerFactory factory) {
            this.containerFactory = factory;
            return this;
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

        /**
         * Declare a remote (pre-deployed) agent at an explicit URL, overriding the YAML-driven mode
         * resolution. Use when the address is not the standard {@code sut.agents.<name>.url} — e.g. a
         * per-phase URL (B-04) or a fault variant ({@code url-llm-down}, C-09). The framework never
         * launches or stops it; any downstream wiring is the deployer's responsibility.
         */
        public Builder remoteAgent(String name, String url) {
            return remoteAgent(name, url, a -> {});
        }

        /** Variant of {@link #remoteAgent(String, String)} with per-agent configuration. */
        public Builder remoteAgent(String name, String url, Consumer<AgentBuilder> configure) {
            AgentBuilder builder = new AgentBuilder(config, name);
            builder.forceRemote(url);
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
            Set<String> referenced = collectReferencedServices();
            BackingServices owned = null;
            BackingServices forInjection;
            if (explicitBackingServices != null) {
                forInjection = explicitBackingServices;      // test-owned: injected but not closed by stack
            } else if (referenced.isEmpty()) {
                forInjection = null;
            } else {
                owned = new BackingServices(config, referenced,
                        containerFactory != null ? containerFactory
                                : new TestContainerFactory(ProcessLauncher.resolveLogDir(config)));
                forInjection = owned;
            }
            if (forInjection != null) {
                injectServiceBindings(forInjection);
            }
            return new SutStack(launcher, specs, streaming, explicitContainer, owned).start();
        }

        /** Union of services bound by managed agents (remote agents' bindings are ignored). */
        private Set<String> collectReferencedServices() {
            Set<String> refs = new java.util.LinkedHashSet<>();
            for (Entry entry : specs.values()) {
                if (!entry.agent().isRemote()) {
                    refs.addAll(config.getKeys("sut.agents." + entry.agent().name() + ".service-bindings"));
                }
            }
            return refs;
        }

        /** Inject each managed agent's {@code service-bindings} into its {@link AgentConfig}. */
        private void injectServiceBindings(BackingServices services) {
            for (Entry entry : specs.values()) {
                if (entry.agent().isRemote()) {
                    continue;
                }
                for (ServiceBinding b : readBindings(entry.agent().name())) {
                    String composed = EnvPlaceholders.resolve(
                            b.urlTemplate().replace("{{url}}", services.url(b.serviceName())));
                    entry.config().property(b.urlKey(), composed);
                }
            }
        }

        private List<ServiceBinding> readBindings(String agentName) {
            List<ServiceBinding> out = new java.util.ArrayList<>();
            for (String svc : config.getKeys("sut.agents." + agentName + ".service-bindings")) {
                String base = "sut.agents." + agentName + ".service-bindings." + svc;
                String urlKey = config.getString(base + ".url-key", "");
                if (urlKey.isBlank()) {
                    throw new IllegalStateException("Agent '" + agentName + "' service-binding '"
                            + svc + "' is missing url-key.");
                }
                out.add(new ServiceBinding(svc, urlKey, config.getString(base + ".url-template", "{{url}}")));
            }
            return out;
        }
    }

    /** Per-agent configurator used inside {@link Builder#agent(String, Consumer)}. */
    public static final class AgentBuilder {

        private final TestConfig config;
        private final String name;
        private final List<String> downstreams = new ArrayList<>();
        private final AgentConfig configOverrides = new AgentConfig();

        /** When set, build() emits a remote entry at this URL, overriding YAML mode resolution. */
        private String forcedRemoteUrl;

        private AgentBuilder(TestConfig config, String name) {
            this.config = config;
            this.name = name;
        }

        /**
         * Force this agent remote at an explicit URL — the escape hatch behind
         * {@link Builder#remoteAgent(String, String)} for addresses not carried by
         * {@code sut.agents.<name>.url} (per-phase / fault-variant URLs).
         */
        AgentBuilder forceRemote(String url) {
            this.forcedRemoteUrl = url;
            return this;
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
         * Wire a single downstream and inject its resolved base URL into a custom Spring property key
         * (instead of the default positional {@code <remoteAgentsPrefix>[i].url} slot). Use when the
         * downstream's address must land in an agent-specific property — e.g. a gateway that reads its
         * plan-agent's base URL from {@code some.prefix.plan-agent-base-url} (the agent itself appends
         * any endpoint path). The downstream name is still registered for readiness (its base URL is
         * known before this agent launches), and a custom-key downstream consumes no positional index.
         *
         * <pre>{@code
         * .agent("gateway", a -> a.downstream("plan-agent", "app.gateway.plan-agent-base-url"))
         * }</pre>
         */
        public AgentBuilder downstream(String downstream, String propertyKey) {
            this.downstreams.add(downstream);                                  // readiness: name → SutAgent.downstreams
            this.configOverrides.downstreamPropertyKey(downstream, propertyKey); // custom-key injection at start()
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

        /**
         * How readiness is determined for this agent (default {@link AgentConfig.ReadyMode#AGENT_CARD}).
         * Use {@link AgentConfig.ReadyMode#TCP} for non-A2A services that do not serve
         * {@code /.well-known/agent.json} (e.g. a REST gateway) — the agent is ready once it owns a
         * TCP LISTEN port. Also settable per agent via {@code sut.agents.<name>.ready-mode}.
         */
        public AgentBuilder readyMode(AgentConfig.ReadyMode readyMode) {
            this.configOverrides.readyMode(readyMode);
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

        /**
         * Redirect this agent's advertised agent-card endpoint through a toxiproxy fault link so any
         * caller discovering it via its card routes JSON-RPC through the proxy. The framework injects
         * {@code --<propertyKey>=<proxyListenUrl>/a2a} at launch; once the stack is up, retrieve the
         * link via {@link SutStack#faultLink(String)} to {@code resetPeer()}/{@code restore()}.
         * Only meaningful for managed agents — declaring it on a remote agent fails fast at build().
                 */
        public AgentBuilder cardEndpointRedirect(String propertyKey) {
            return cardEndpointRedirect(propertyKey, "/a2a");
        }

        /**
         * Variant of {@link #cardEndpointRedirect(String)} with an explicit A2A endpoint path (default
         * {@code /a2a}). Override only if the agent serves A2A at a different path; never pass empty.
         */
        public AgentBuilder cardEndpointRedirect(String propertyKey, String path) {
            this.configOverrides.cardEndpointRedirect(propertyKey, path);
            return this;
        }

        private Entry build() {
            // Mode is resolved from YAML by default — a configured `url` ⇒ remote (address only),
            // otherwise the group/artifact/version block ⇒ managed (launched). forceRemote() overrides
            // this for tests that must point at an address not under sut.agents.<name>.url (e.g. a
            // per-phase URL, or a fault variant like url-llm-down).
            String yamlUrl = config.getString("sut.agents." + name + ".url", "");
            String remoteUrl = forcedRemoteUrl != null ? forcedRemoteUrl : yamlUrl;
            boolean remote = !remoteUrl.isBlank();
            if (remote && configOverrides.cardEndpointRedirectKey() != null) {
                throw new IllegalStateException("Agent '" + name + "' is remote (url=" + remoteUrl
                        + "); cardEndpointRedirect is only supported on managed agents — the framework "
                        + "cannot inject a launch property into a pre-deployed service.");
            }
            if (remote) {
                return remoteEntry(remoteUrl);
            }
            MavenArtifact resolvedArtifact = defaultArtifact();
            if (configOverrides.profile() == null || configOverrides.profile().isBlank()) {
                String defaultProfile = config.getString("sut.agents." + name + ".profile", "");
                if (defaultProfile != null && !defaultProfile.isBlank()) {
                    configOverrides.profile(defaultProfile);
                }
            }
            String yamlPrefix = config.getString("sut.agents." + name + ".remote-agents-prefix", "");
            if (!yamlPrefix.isBlank()) {
                configOverrides.remoteAgentsPrefix(yamlPrefix);
            }
            String yamlReadyMode = config.getString("sut.agents." + name + ".ready-mode", "");
            if (!yamlReadyMode.isBlank()) {
                try {
                    configOverrides.readyMode(AgentConfig.ReadyMode
                            .valueOf(yamlReadyMode.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Agent '" + name + "' has unknown ready-mode '"
                            + yamlReadyMode + "'; expected one of "
                            + Arrays.toString(AgentConfig.ReadyMode.values()));
                }
            }
            config.getStringMap("sut.agents." + name + ".java.system-properties")
                    .forEach(configOverrides::jvmSystemProperty);
            config.getStringMap("sut.agents." + name + ".spring.properties")
                    .forEach(configOverrides::property);
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
