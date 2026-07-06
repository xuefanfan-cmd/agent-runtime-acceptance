package com.huawei.ascend.sit.lifecycle;

import com.huawei.ascend.sit.config.TestConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves and owns the backing services referenced by a {@link SutStack} — the generalisation of
 * {@code SutStack}'s toxiproxy {@code ownedContainer} pattern.
 *
 * <p>Built from {@code sut.services.<name>} declarations + the set of referenced service names.
 * Each referenced service is resolved once (deduped by name): a non-blank {@code url}
 * (or {@code sut.mode=remote}) ⇒ remote (url used as-is, no container); otherwise {@code image}+{@code port}
 * ⇒ managed (started via the {@link ContainerFactory}, address = {@code host:mappedPort}). Framework-side
 * values ({@code url}, {@code env}) have {@code ${VAR[:default]}} resolved by {@link EnvPlaceholders}.
 * Implements {@link AutoCloseable} — {@link #close()} stops owned containers in reverse start order.
 */
public final class BackingServices implements AutoCloseable {

    private final Function<String, String> envLookup;
    private final Map<String, Resolved> resolved = new LinkedHashMap<>();
    private final List<ManagedContainer> owned = new ArrayList<>();

    public BackingServices(TestConfig config, Set<String> referenced, ContainerFactory factory) {
        this(config, referenced, factory, System::getenv);
    }

    /** Package-private: tests inject an env lookup to exercise {@code ${}} resolution without real env vars. */
    BackingServices(TestConfig config, Set<String> referenced, ContainerFactory factory,
                    Function<String, String> envLookup) {
        this.envLookup = envLookup;
        boolean remoteMode = "remote".equalsIgnoreCase(config.getString("sut.mode", ""));
        try {
            for (String svc : referenced) {
                resolved.put(svc, resolve(BackingService.from(config, svc), remoteMode, factory));
            }
        } catch (RuntimeException e) {
            close(); // tear down anything already started so we never leak a container on a failed build
            throw e;
        }
    }

    private Resolved resolve(BackingService decl, boolean remoteMode, ContainerFactory factory) {
        String url = EnvPlaceholders.resolve(decl.url(), envLookup).trim();
        boolean remote = remoteMode || !url.isBlank();
        if (remote) {
            if (url.isBlank()) {
                throw new IllegalStateException("Backing service '" + decl.name()
                        + "' has no url and sut.mode=remote; cannot start a container in remote mode.");
            }
            return new Resolved(url, true, null);
        }
        if (decl.image().isBlank()) {
            throw new IllegalStateException("Backing service '" + decl.name() + "' is neither remote (no url) "
                    + "nor managed (no image); declare sut.services." + decl.name() + ".{url|image}.");
        }
        Map<String, String> env = new LinkedHashMap<>();
        decl.env().forEach((k, v) -> env.put(k, EnvPlaceholders.resolve(v, envLookup)));
        ManagedContainer container = factory.start(decl.name(), decl.image(), decl.port(), env);
        owned.add(container);
        return new Resolved(container.host() + ":" + container.mappedPort(), false, container);
    }

    /** Bare {@code host:mappedPort} (managed) or the resolved remote url; the binding template wraps it. */
    public String url(String svc) {
        return require(svc).url;
    }

    public boolean isRemote(String svc) {
        return require(svc).remote;
    }

    private Resolved require(String svc) {
        Resolved r = resolved.get(svc);
        if (r == null) {
            throw new IllegalStateException("Backing service '" + svc + "' was not resolved. Resolved: "
                    + resolved.keySet());
        }
        return r;
    }

    @Override
    public void close() {
        for (int i = owned.size() - 1; i >= 0; i--) {
            owned.get(i).close();
        }
        owned.clear();
    }

    private record Resolved(String url, boolean remote, ManagedContainer container) {}
}
