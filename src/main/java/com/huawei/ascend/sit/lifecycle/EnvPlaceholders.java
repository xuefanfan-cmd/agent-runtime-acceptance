package com.huawei.ascend.sit.lifecycle;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${VAR}} / {@code ${VAR:default}} placeholders against an environment lookup.
 *
 * <p>Used by the backing-services mechanism for values consumed <em>framework-side</em> — a service's
 * remote {@code url}, its container {@code env}, and a binding's {@code url-template}. These never
 * reach the agent JVM (where Spring would resolve them), and {@link com.huawei.ascend.sit.config.TestConfig#getString}
 * does not resolve {@code ${...}}, so the framework resolves them here. Static credentials under
 * {@code sut.agents.<name>.spring.properties} are <em>not</em> resolved here — the agent's Spring
 * resolves those natively.
 *
 * <p>Testable via the {@link #resolve(String, Function)} overload with a map-backed lookup; the
 * {@link #resolve(String)} convenience uses {@link System#getenv(String)}.
 */
public final class EnvPlaceholders {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    private EnvPlaceholders() {}

    /** Resolve every {@code ${VAR[:default]}} against {@link System#getenv(String)}. */
    public static String resolve(String value) {
        return resolve(value, System::getenv);
    }

    /** Resolve every {@code ${VAR[:default]}} against {@code envLookup}; plain text passes through. */
    public static String resolve(String value, Function<String, String> envLookup) {
        if (value == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String env = envLookup.apply(m.group(1));
            String def = m.group(2); // null when no ":default" group
            String replacement = env != null ? env : (def != null ? def : "");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
