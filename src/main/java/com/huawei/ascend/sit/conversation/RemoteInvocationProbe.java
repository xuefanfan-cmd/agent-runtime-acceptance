package com.huawei.ascend.sit.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives parallel child conversation ids from an event stream. The runtime constructs each child cid
 * as {@code parentCid + ":" + batchId + ":" + toolCallId} for multi-member batches; the ingredients
 * ride on artifact-part metadata {@code _remote_invocation.{batchId, toolCallId}}, which the
 * {@link ConversationInteractionAdapter} surfaces into {@link SseEvent} data under key
 * {@code _remote_invocation}. This is the pure, SDK-free half of the parallel-driver derivation —
 * the SDK extraction (navigating {@code ClientEvent} → {@code TaskArtifactUpdateEvent} →
 * {@code TextPart.metadata}) lives in the adapter.
 */
public final class RemoteInvocationProbe {

    private RemoteInvocationProbe() {}

    /** A derived child conversation reference. */
    public record ChildRef(String childCid, String toolCallId) {}

    /**
     * Derive the distinct child conversation refs from the given events. Dedupes by
     * {@code (batchId, toolCallId)}, preserving first-seen order. Returns an empty list when no event
     * carries a complete {@code _remote_invocation.{batchId, toolCallId}} projection.
     *
     * <p>Handles both wire shapes the adapter emits: a single projection as a {@code Map} (one child per
     * event) and a multi-member fan-out batch as a {@code List<Map>} (several children bundled in one
     * event's artifact parts).
     */
    public static List<ChildRef> derive(String parentCid, List<SseEvent> events) {
        Map<DedupKey, ChildRef> byKey = new LinkedHashMap<>();
        if (events == null) {
            return List.of();
        }
        for (SseEvent e : events) {
            Map<String, Object> data = e.data();
            if (data == null) {
                continue;
            }
            Object ri = data.get("_remote_invocation");
            if (ri instanceof Map<?, ?> m) {
                accumulate(parentCid, m, byKey);                       // single projection (one child)
            } else if (ri instanceof List<?> list) {
                for (Object item : list) {                             // multi-member batch (List<Map>)
                    if (item instanceof Map<?, ?> im) {
                        accumulate(parentCid, im, byKey);
                    }
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** Extract {@code (batchId, toolCallId)} from one projection and record its child ref (first-seen wins). */
    private static void accumulate(String parentCid, Map<?, ?> m, Map<DedupKey, ChildRef> byKey) {
        String batchId = stringOf(m.get("batchId"));
        String toolCallId = stringOf(m.get("toolCallId"));
        if (batchId.isBlank() || toolCallId.isBlank()) {
            return;
        }
        byKey.putIfAbsent(new DedupKey(batchId, toolCallId),
                new ChildRef(parentCid + ":" + batchId + ":" + toolCallId, toolCallId));
    }

    /**
     * The parallel fan-out children: the members of the batchId that carries ≥2 distinct toolCallIds.
     * A serial round produces single-member batches (one remote invocation each — e.g. the balance
     * query); the parallel fan-out is ONE batch with N members (the transfer legs). Returns empty when
     * no batch has ≥2 members (no fan-out in these events), so the caller can keep driving serially.
     *
     * <p>This is the precise fan-out signal: it excludes an already-completed serial invocation (like
     * the balance) that happens to ride in the same round as the fan-out — that serial invocation is a
     * 1-member batch, not the ≥2-member fan-out batch. {@link #derive} returns EVERY projection (for
     * diagnostics); this returns only the fan-out batch to drive.
     */
    public static List<ChildRef> fanOutChildren(String parentCid, List<SseEvent> events) {
        Map<String, LinkedHashMap<DedupKey, ChildRef>> byBatch = new LinkedHashMap<>();
        if (events == null) {
            return List.of();
        }
        for (SseEvent e : events) {
            Map<String, Object> data = e.data();
            if (data == null) {
                continue;
            }
            Object ri = data.get("_remote_invocation");
            if (ri instanceof Map<?, ?> m) {
                accumulateBatched(parentCid, m, byBatch);
            } else if (ri instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> im) {
                        accumulateBatched(parentCid, im, byBatch);
                    }
                }
            }
        }
        // Pick the batch with the most distinct members; if ≥2, it is the fan-out.
        String fanOutBatch = null;
        int max = 0;
        for (Map.Entry<String, LinkedHashMap<DedupKey, ChildRef>> en : byBatch.entrySet()) {
            if (en.getValue().size() > max) {
                max = en.getValue().size();
                fanOutBatch = en.getKey();
            }
        }
        if (max < 2) {
            return List.of();
        }
        return new ArrayList<>(byBatch.get(fanOutBatch).values());
    }

    /** Record one projection under its batchId (first-seen-per-(batchId,toolCallId) wins). */
    private static void accumulateBatched(String parentCid, Map<?, ?> m,
                                          Map<String, LinkedHashMap<DedupKey, ChildRef>> byBatch) {
        String batchId = stringOf(m.get("batchId"));
        String toolCallId = stringOf(m.get("toolCallId"));
        if (batchId.isBlank() || toolCallId.isBlank()) {
            return;
        }
        byBatch.computeIfAbsent(batchId, k -> new LinkedHashMap<>())
                .putIfAbsent(new DedupKey(batchId, toolCallId),
                        new ChildRef(parentCid + ":" + batchId + ":" + toolCallId, toolCallId));
    }

    /**
     * Dedup key for the parallel-driver derivation. A record (not a {@code batchId + ":" + toolCallId}
     * string) so a {@code ':'} inside either value cannot collide two distinct members into one entry.
     */
    private record DedupKey(String batchId, String toolCallId) {}

    private static String stringOf(Object o) {
        return o == null ? "" : o.toString();
    }
}
