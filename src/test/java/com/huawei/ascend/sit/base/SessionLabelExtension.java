package com.huawei.ascend.sit.base;

import com.huawei.ascend.sit.transport.SessionLabels;
import io.qameta.allure.Allure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Derives a human-readable session label per test invocation and installs it into
 * {@link SessionLabels} (the main-code, JUnit-free holder consumed by the wire-log naming in
 * {@code InteractionFlow}/{@code ConversationInteractionAdapter}). Registered on
 * {@link BaseManagedStackTest} via {@code @ExtendWith} (inherited by all subclasses); classes
 * outside that hierarchy keep the old contextId-based naming.
 *
 * <p><b>Derivation (spec §3.4).</b> Base: (1) a {@code @DisplayName} starting with an ASCII letter
 * is cut at the first {@code ':'} (the repo's slug convention — {@code "A-07: …"},
 * {@code "FEAT-001.<slug>: …"}); without a colon it is used whole, truncated at 48 chars.
 * (2) Otherwise the method name's first two camelCase words. A test-template invocation
 * (parameterized/repeated; detected via {@code [test-template-invocation:} in the uniqueId)
 * appends its rendered display name normalized to filesystem-safe dashes
 * ({@code "[1] A2A_STREAM"} → {@code "1-A2A_STREAM"}).
 *
 * <p><b>Collision net (spec §3.5).</b> Label must be unique per JVM run — SUT sessions (e.g.
 * Redis checkpointer) can persist across tests, and two cases sharing a label would silently
 * cross-contaminate. A JVM-wide registry maps label → owning uniqueId; a second invocation
 * deriving an already-taken label gets the smallest free {@code -N} suffix plus a stderr warning
 * pointing at the fix (a slug-style {@code @DisplayName}). The registry is append-only for the
 * run: labels stay reserved after {@code afterEach} precisely because SUT state outlives the test.
 */
public final class SessionLabelExtension implements BeforeEachCallback, AfterEachCallback {

    /** Max chars for a colon-less English {@code @DisplayName} used as the base (rule 2). */
    static final int MAX_BASE_CHARS = 48;

    /** JVM-run-wide label registry: label → the invocation uniqueId that first claimed it. */
    private static final ConcurrentMap<String, String> LABEL_OWNER = new ConcurrentHashMap<>();

    @Override
    public void beforeEach(ExtensionContext context) {
        String label = resolveLabel(context);
        SessionLabels.set(label);
        // Report ↔ wire-log correlation, same pattern as BaseManagedStackTest's startTimestamp.
        Allure.parameter("sessionLabel", label);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        SessionLabels.clear();
    }

    /** Full pipeline: derive base, append template suffix, de-collide. Package-private for tests. */
    static String resolveLabel(ExtensionContext ctx) {
        return register(ctx.getUniqueId(), deriveBase(ctx) + templateSuffix(ctx));
    }

    /** Rules 1-3: displayName slug, else colon-less displayName (capped), else camelCase words. */
    static String deriveBase(ExtensionContext ctx) {
        Method method = ctx.getRequiredTestMethod();
        DisplayName dn = method.getAnnotation(DisplayName.class);
        if (dn != null) {
            String value = dn.value().trim();
            if (!value.isEmpty() && isAsciiLetter(value.charAt(0))) {
                int colon = value.indexOf(':');
                if (colon > 0) {
                    return value.substring(0, colon).trim();
                }
                return value.length() <= MAX_BASE_CHARS ? value : value.substring(0, MAX_BASE_CHARS);
            }
        }
        return firstTwoCamelWords(method.getName());
    }

    /** Rule 4: {@code "-"} + normalized rendered invocation name for template invocations, else "". */
    static String templateSuffix(ExtensionContext ctx) {
        if (!ctx.getUniqueId().contains("[test-template-invocation:")) {
            return "";
        }
        String norm = ctx.getDisplayName().replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return "-" + (norm.isEmpty() ? "x" : norm);
    }

    /**
     * Claim {@code base} for {@code uniqueId}; on collision with a DIFFERENT invocation append the
     * smallest free {@code -N} (N starting at 2) and warn on stderr.
     */
    static String register(String uniqueId, String base) {
        String candidate = base;
        int n = 2;
        while (true) {
            String owner = LABEL_OWNER.putIfAbsent(candidate, uniqueId);
            if (owner == null || owner.equals(uniqueId)) {
                if (!candidate.equals(base)) {
                    System.err.println("[session-label] label collision on '" + base + "', using '"
                            + candidate + "'. Add a slug-style @DisplayName to fix.");
                }
                return candidate;
            }
            candidate = base + "-" + n++;
        }
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * First two camelCase words of {@code name}: a new word starts before an uppercase letter that
     * follows a non-uppercase char (digits stay inside their segment). Fewer than two words → all.
     */
    static String firstTwoCamelWords(String name) {
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(name.charAt(i - 1))) {
                words.add(cur.toString());
                cur.setLength(0);
                if (words.size() == 2) {
                    break;
                }
            }
            cur.append(c);
        }
        if (words.size() < 2 && cur.length() > 0) {
            words.add(cur.toString());
        }
        String joined = String.join("", words.subList(0, Math.min(2, words.size())));
        return joined.isEmpty() ? "test" : joined;
    }
}
