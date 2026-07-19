package com.huawei.ascend.sit.transport;

import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves the active {@link MessageProtocol} from the environment. Lookup order:
 * system property {@code MESSAGE_PROTOCOL} → environment variable {@code MESSAGE_PROTOCOL}
 * → empty (no protocol forced; the flow uses its own default, preserving prior behavior).
 *
 * <p>An explicit builder override ({@link #resolve(MessageProtocol)}) wins over the environment.
 * Resolution is the <em>only</em> coupling between test code and the selection knob, so flows stay
 * agnostic of how the choice is made.
 *
 * <p>Both sources are injectable via the package-private constructor so the precedence rules are
 * unit-testable without touching the real JVM environment.
 */
public final class ProtocolResolver {

    public static final String PROPERTY = "MESSAGE_PROTOCOL";
    public static final String ENV = "MESSAGE_PROTOCOL";

    private final Function<String, String> systemProperty;
    private final Function<String, String> environment;

    public ProtocolResolver() {
        this(System::getProperty, System::getenv);
    }

    ProtocolResolver(Function<String, String> systemProperty, Function<String, String> environment) {
        this.systemProperty = systemProperty;
        this.environment = environment;
    }

    /** The protocol forced by the environment, or empty if none is set (flow default applies). */
    public Optional<MessageProtocol> active() {
        return resolve(null);
    }

    /**
     * Effective protocol: an explicit {@code override} wins; otherwise the environment value;
     * otherwise empty (no override, no env knob → flow default).
     *
     * @param override a builder/.protocol() override, or null for "use the environment"
     * @return the effective protocol, or empty if neither override nor environment sets one
     */
    public Optional<MessageProtocol> resolve(MessageProtocol override) {
        if (override != null) {
            return Optional.of(override);
        }
        String sys = systemProperty.apply(PROPERTY);
        if (sys != null && !sys.isBlank()) {
            return Optional.of(MessageProtocol.parse(sys));
        }
        String env = environment.apply(ENV);
        if (env != null && !env.isBlank()) {
            return Optional.of(MessageProtocol.parse(env));
        }
        return Optional.empty();
    }
}
