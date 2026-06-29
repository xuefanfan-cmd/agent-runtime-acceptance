package com.huawei.ascend.sit.lifecycle;

import com.huawei.ascend.sit.config.TestConfig;

import java.util.Map;

/**
 * Declaration of one backing service, read from {@code sut.services.<name>}.
 *
 * <p>Structural fields only: {@code image}/{@code port} drive the managed container,
 * {@code url} (non-blank) overrides to remote, {@code env} is passed to the container via
 * {@code withEnv}. Static credentials do <em>not</em> live here — they go to the agent via
 * {@code sut.agents.<name>.spring.properties}. Fields hold <em>raw</em> config strings (incl.
 * unresolved {@code ${...}}); {@link BackingServices} resolves placeholders when it decides
 * remote-vs-managed and passes env to the container.
 */
public record BackingService(String name, String image, int port, String url, Map<String, String> env) {
    public BackingService {
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    /** Parse {@code sut.services.<name>} (raw; no {@code ${}} resolution) into a declaration. */
    public static BackingService from(TestConfig config, String name) {
        return new BackingService(name,
                config.getString("sut.services." + name + ".image", ""),
                config.getInt("sut.services." + name + ".port", 0),
                config.getString("sut.services." + name + ".url", ""),
                config.getStringMap("sut.services." + name + ".env"));
    }
}
