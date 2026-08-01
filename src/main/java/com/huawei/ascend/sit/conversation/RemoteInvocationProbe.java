package com.huawei.ascend.sit.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

/**
 * Derives parallel child conversation ids from an event stream. The runtime constructs each child cid
 * as {@code parentCid + ":" + batchId + ":" + toolCallId} for multi-member batches; the ingredients
 * ride on part metadata under key {@code _remote_invocation.{batchId, toolCallId}}.
 *
 * <p>Two extraction strategies feed ONE shared batch-aggregation core ({@link #fanOutFromProjections}):
 * <ul>
 *   <li>The {@link SseEvent} path ({@link #derive} / {@link #fanOutChildren}) consumes
 *       adapter-projected event data ({@link SseEvent#data()} → {@code _remote_invocation}).</li>
 *   <li>The {@link ClientEvent} path ({@link #fromClientEvents} / {@link #hasFanOut}) navigates the
 *       raw A2A SDK events directly ({@link TaskUpdateEvent} / {@link MessageEvent} → parts →
 *       {@code _remote_invocation} metadata), so black-box tests driving the SDK can observe fan-out
 *       without the adapter.</li>
 * </ul>
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
        return fanOutFromProjections(parentCid, projectionsFromSseEvents(events));
    }

    /** Flatten {@code SseEvent.data["_remote_invocation"]} (a {@code Map} or {@code List<Map>}) into a projection list. */
    private static List<Map<String, Object>> projectionsFromSseEvents(List<SseEvent> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (events == null) {
            return out;
        }
        for (SseEvent e : events) {
            Map<String, Object> data = e.data();
            if (data == null) {
                continue;
            }
            collectProjection(data.get("_remote_invocation"), out);
        }
        return out;
    }

    /**
     * Batch-aggregation core shared by both the {@code SseEvent} and {@code ClientEvent} paths:
     * pick the batch with the most members; if it has ≥2, it is the fan-out.
     */
    private static List<ChildRef> fanOutFromProjections(String parentCid, List<Map<String, Object>> projections) {
        Map<String, LinkedHashMap<DedupKey, ChildRef>> byBatch = new LinkedHashMap<>();
        for (Map<String, Object> m : projections) {
            accumulateBatched(parentCid, m, byBatch);
        }
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

    // ===== Black-box SDK entry (does not depend on SseEvent / ConversationInteractionAdapter) =====

    /**
     * Black-box entry: extract the parallel fan-out child conversation refs directly from raw A2A SDK
     * {@link ClientEvent}s.
     *
     * <p>Navigates {@code TaskUpdateEvent → TaskArtifactUpdateEvent → Artifact.parts()} (and
     * {@code MessageEvent → Message.parts()}), reads {@code _remote_invocation} from each
     * {@link TextPart}/{@link DataPart} {@code metadata()}, and reuses the batch-aggregation core to
     * return the children of the batch with ≥2 members. Does not depend on
     * {@code ConversationInteractionAdapter}/{@link SseEvent} — lets black-box tests driven by
     * {@code A2aServiceClient} + {@code A2aEventCollector} observe fan-out (closing the black-box
     * observation gap). The returned members share their source and semantics with
     * {@link #fanOutChildren(String, List)}.
     *
     * @param parentCid parent conversation cid (child cid = {@code parentCid:batchId:toolCallId})
     * @param events    raw SDK events collected by the black box ({@code A2aEventCollector.snapshotAllEvents()})
     * @return child refs of the fan-out batch; empty when no batch has ≥2 members
     */
    public static List<ChildRef> fromClientEvents(String parentCid, List<ClientEvent> events) {
        return fanOutFromProjections(parentCid, projectionsFromClientEvents(events));
    }

    /**
     * Black-box convenience: whether the event stream contains a parallel fan-out batch (≥2 members).
     *
     * @return true iff some batch carries ≥2 distinct toolCallIds
     */
    public static boolean hasFanOut(String parentCid, List<ClientEvent> events) {
        return !fromClientEvents(parentCid, events).isEmpty();
    }

    /**
     * Extract every {@code _remote_invocation} projection from raw {@link ClientEvent}s (each is a
     * {@code {batchId, toolCallId}} map). Intentionally mirrors
     * {@code ConversationInteractionAdapter.remoteInvocationsFrom} — keep the two in sync if the SDK
     * event hierarchy changes.
     */
    private static List<Map<String, Object>> projectionsFromClientEvents(List<ClientEvent> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (events == null) {
            return out;
        }
        for (ClientEvent e : events) {
            if (e instanceof TaskUpdateEvent tue) {
                if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue && aue.artifact() != null) {
                    appendProjectionsFromParts(aue.artifact().parts(), out);
                }
            } else if (e instanceof MessageEvent me) {
                Message m = me.getMessage();
                if (m != null) {
                    appendProjectionsFromParts(m.parts(), out);
                }
            }
        }
        return out;
    }

    /** Walk {@code parts} and collect {@code _remote_invocation} projections from each {@link TextPart}/{@link DataPart} {@code metadata()}. */
    private static void appendProjectionsFromParts(List<Part<?>> parts, List<Map<String, Object>> out) {
        if (parts == null) {
            return;
        }
        for (Part<?> p : parts) {
            Map<String, Object> md = partMetadata(p);
            if (md == null) {
                continue;
            }
            collectProjection(md.get("_remote_invocation"), out);
        }
    }

    /** A part's {@code metadata()} map ({@link TextPart}/{@link DataPart} expose the same channel); other part types carry no projection channel. */
    private static Map<String, Object> partMetadata(Part<?> p) {
        if (p instanceof TextPart tp) {
            return tp.metadata();
        }
        if (p instanceof DataPart dp) {
            return dp.metadata();
        }
        return null;
    }

    /** Flatten one {@code _remote_invocation} value (a single-member {@code Map} or a multi-member {@code List<Map>} batch) into {@code out}. */
    @SuppressWarnings("unchecked")
    private static void collectProjection(Object ri, List<Map<String, Object>> out) {
        if (ri instanceof Map<?, ?> m) {
            out.add((Map<String, Object>) m);
        } else if (ri instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> im) {
                    out.add((Map<String, Object>) im);
                }
            }
        }
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
