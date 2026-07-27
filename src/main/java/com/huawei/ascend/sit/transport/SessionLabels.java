package com.huawei.ascend.sit.transport;

/**
 * Per-test-invocation session label — the human-readable tag used as the wire-log filename identity
 * ({@code <label>-r<round>-<protocol>.log}). Set by the test-side {@code SessionLabelExtension}
 * (src/test) from the JUnit {@code ExtensionContext} in {@code beforeEach} and cleared in
 * {@code afterEach}; consumed here in main code by {@code InteractionFlow} and
 * {@code ConversationInteractionAdapter} when they name a round's wire log.
 *
 * <p><b>JUnit-free by design.</b> This class lives in src/main and must NOT reference JUnit — the
 * extension (src/test) is the only JUnit-aware piece; it pushes the derived label through
 * {@link #set(String)}. Outside a JUnit run (or in classes that do not register the extension) the
 * holder is simply empty and naming falls back as before.
 *
 * <p><b>Thread model.</b> JUnit runs one invocation's {@code beforeEach}/test body/{@code afterEach}
 * on a single thread, and both consumers resolve the name synchronously on that same thread (the
 * post-await point in {@code executeRound}/{@code send}), so a {@link ThreadLocal} is exact.
 * Concurrent opt-in classes ({@code @Execution(CONCURRENT)}) get one label per invocation thread;
 * {@link #clear()} in {@code afterEach} prevents leakage across ForkJoinPool worker reuse.
 */
public final class SessionLabels {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SessionLabels() {
    }

    /** Install the current invocation's label (test-side extension only). */
    public static void set(String label) {
        CURRENT.set(label);
    }

    /** The current thread's label, or {@code null} when unset (non-JUnit caller / unregistered class). */
    public static String current() {
        return CURRENT.get();
    }

    /** Drop the current thread's label. Must run in {@code afterEach} — pool threads are reused. */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Wire-log filename identity: invocation label > contextId > {@code "nosession"}.
     * A {@code null}/blank holder value counts as unset (mirrors the old {@code sessionIdOf} blanks).
     * metadata {@code sessionId} is intentionally NOT consulted — that channel was removed (see
     * spec §1 决策 3); the value no longer influences log naming anywhere.
     */
    public static String resolveLogName(String contextId) {
        String label = CURRENT.get();
        if (label != null && !label.isBlank()) {
            return label;
        }
        return (contextId == null || contextId.isBlank()) ? "nosession" : contextId;
    }
}
