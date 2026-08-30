package com.huawei.ascend.sit.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

/**
 * FEAT-027 extraction core: derives the parallel fan-out from the wire the refreshed runtime emits.
 * The runtime no longer tags fan-out with part-level {@code _remote_invocation.{batchId,toolCallId}}
 * projections; instead (source-verified against RemoteInvocationBatchCoordinator / A2AProtocolAdapter):
 *
 * <ul>
 *   <li><b>delegation events</b> — a standard {@code TaskArtifactUpdateEvent} whose artifact
 *       {@code metadata.agentEvent = {type:"delegation", source:{agentId,taskId},
 *       target:{agentId,taskId}}} (artifactId {@code delegation:<parentTaskId>:<childTaskId>}).
 *       The client builds its call tree from these (FEAT-027 §2 调用树构建).</li>
 *   <li><b>producer labels</b> — every forwarded remote output/status artifact carries
 *       {@code agentEvent.source.{agentId,taskId}} ({@code type:"output"/"status"}); the client
 *       demultiplexes interleaved streams by that label (FEAT-027 §2 流式输出分流).</li>
 *   <li><b>pending-member interrupts</b> — a round that ends waiting on N remote members emits a
 *       terminal {@code statusUpdate} whose {@code status.message.metadata._interrupt.items[]} lists
 *       each pending member's {@code toolCallId}. N≥2 is the drivable parallel fan-out; N==1 is a
 *       serial remote step (resume routes to the single member without tagging).</li>
 * </ul>
 *
 * <p>{@link ConversationInteractionAdapter} projects both onto {@link SseEvent#data()}:
 * {@code data.agentEvent} (the artifact metadata map, verbatim) and {@code data.interruptItems}
 * (the pending toolCallId list). This probe reads those projections.
 *
 * <p><b>Legacy black-box entry kept for DA-09</b>: {@link #fromClientEvents}/{@link #hasFanOut} walk
 * raw SDK events for {@code _remote_invocation} projections — the format the pre-refresh runtime
 * emitted. The current runtime no longer produces it (0 references in agent-runtime-java); the entry
 * stays until DA-09's parallel-search probe is migrated to delegation events.
 */
public final class RemoteInvocationProbe {

    private RemoteInvocationProbe() {}

    /** One call-tree node identity: an {@code agentId} + {@code taskId} pair (FEAT-027 producer/delegation ref). */
    public record AgentRef(String agentId, String taskId) {}

    /**
     * One delegation event: {@code source} (the delegating agent's node) → {@code target} (the delegated
     * child node). Distinct {@code target.taskId}s are distinct children; {@code target.agentId} names the
     * downstream agent (e.g. {@code versatile-adapter}).
     */
    public record Delegation(AgentRef source, AgentRef target) {}

    /**
     * The delegation events in arrival order, deduped by {@code target.taskId} (the runtime re-emits a
     * member's delegation artifact on later rounds — e.g. the balance leg's artifact re-appears carrying
     * its accumulated result — so first-seen wins). Empty when no event carries a complete
     * {@code agentEvent{type=delegation, source, target}} projection.
     */
    public static List<Delegation> delegations(List<SseEvent> events) {
        Map<String, Delegation> byTargetTask = new LinkedHashMap<>();
        if (events == null) {
            return List.of();
        }
        for (SseEvent e : events) {
            Map<String, Object> agentEvent = agentEventOf(e);
            if (agentEvent == null || !"delegation".equals(agentEvent.get("type"))) {
                continue;
            }
            AgentRef source = refOf(agentEvent.get("source"));
            AgentRef target = refOf(agentEvent.get("target"));
            if (source == null || target == null || target.taskId() == null || target.taskId().isBlank()) {
                continue;
            }
            byTargetTask.putIfAbsent(target.taskId(), new Delegation(source, target));
        }
        return new ArrayList<>(byTargetTask.values());
    }

    /**
     * FEAT-027 §5.4 断点重连恢复:rebuild the call tree from a {@code GetTask} snapshot ({@link Task}) —
     * the delegation records live in the snapshot's {@code artifacts[].metadata.agentEvent} and
     * {@code history[].metadata.agentEvent} (A2A Task schema has no dedicated "call-tree state" field,
     * §5.7). Semantics mirror {@link #delegations(List)}: only {@code type=delegation} records with a
     * complete source/target count, deduped by {@code target.taskId}, first-seen wins — the runtime's
     * re-emitted delegation artifacts land in the snapshot just as they do on the live wire, so the same
     * dedup applies. Null task / null artifacts / null history all degrade to an empty tree.
     */
    public static List<Delegation> delegationsOfTask(Task task) {
        Map<String, Delegation> byTargetTask = new LinkedHashMap<>();
        if (task == null) {
            return List.of();
        }
        if (task.artifacts() != null) {
            for (Artifact a : task.artifacts()) {
                collectDelegation(a == null ? null : a.metadata(), byTargetTask);
            }
        }
        if (task.history() != null) {
            for (Message m : task.history()) {
                collectDelegation(m == null ? null : m.metadata(), byTargetTask);
            }
        }
        return new ArrayList<>(byTargetTask.values());
    }

    /** One metadata map's {@code agentEvent} delegation record (if any) into the dedup collector. */
    private static void collectDelegation(Map<String, Object> metadata, Map<String, Delegation> byTargetTask) {
        if (metadata == null || !(metadata.get("agentEvent") instanceof Map<?, ?> v)) {
            return;
        }
        Map<String, Object> agentEvent = castToStringMap(v);
        if (!"delegation".equals(agentEvent.get("type"))) {
            return;
        }
        AgentRef source = refOf(agentEvent.get("source"));
        AgentRef target = refOf(agentEvent.get("target"));
        if (source == null || target == null || target.taskId() == null || target.taskId().isBlank()) {
            return;
        }
        byTargetTask.putIfAbsent(target.taskId(), new Delegation(source, target));
    }

    /**
     * The toolCallIds of the remote members this round ended waiting on ({@code _interrupt.items}),
     * first-seen order, deduped. These are the resume routing keys: the runtime's batch coordinator
     * routes per-part input by {@code parts[i].metadata.toolCallId} → member. Empty when the round
     * carried no pending-member interrupt.
     */
    public static List<String> pendingToolCallIds(List<SseEvent> events) {
        Set<String> ids = new LinkedHashSet<>();
        if (events == null) {
            return List.of();
        }
        for (SseEvent e : events) {
            Object items = (e.data() == null) ? null : e.data().get("interruptItems");
            if (!(items instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    ids.add(s);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * The drivable parallel fan-out: the pending-member toolCallIds, but ONLY when the round waits on
     * ≥2 members ("Multiple remote agents require input"). A single-member wait is a serial remote
     * step — the caller keeps driving serially (the runtime auto-routes an untagged resume to the one
     * pending member), so returning empty there lets {@link Turn#runParallel()}'s serial phase continue.
     */
    public static List<String> fanOutToolCallIds(List<SseEvent> events) {
        List<String> pending = pendingToolCallIds(events);
        return pending.size() >= 2 ? pending : List.of();
    }

    /**
     * The producer label of an event ({@code agentEvent.source.{agentId,taskId}}), or {@code null} when
     * the event carries no label — per FEAT-027 §5.2 an unlabelled output belongs to the call-tree ROOT
     * (the delegating agent itself), so {@code null} is a valid attribution, not an error.
     */
    static AgentRef producerOf(SseEvent e) {
        Map<String, Object> agentEvent = agentEventOf(e);
        return agentEvent == null ? null : refOf(agentEvent.get("source"));
    }

    /**
     * Demultiplex an interleaved reply stream by producer label (FEAT-027 流式输出分流): events keyed by
     * {@code agentEvent.source.taskId}, arrival order preserved within each bucket. The {@code null} key
     * collects unlabelled events — per FEAT-027 §5.2 those belong to the call-tree ROOT (the delegating
     * agent's own output), not an error, so they are collected rather than dropped. Iterate via
     * {@code entrySet()} (not {@code get(null)} — some Map implementations reject null-key lookups).
     */
    public static Map<String, List<SseEvent>> streamsByProducer(List<SseEvent> events) {
        Map<String, List<SseEvent>> out = new LinkedHashMap<>();
        if (events == null) {
            return out;
        }
        for (SseEvent e : events) {
            AgentRef producer = producerOf(e);
            String key = (producer == null || producer.taskId() == null || producer.taskId().isBlank())
                    ? null : producer.taskId();
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        return out;
    }

    /**
     * The distinct producer labels that emitted at least one {@code type:"output"} agentEvent, first-seen
     * order — the FEAT-027 invariant a parallel fan-out reply must satisfy: ≥2 distinct producers each
     * carrying output proves the interleaved stream was genuinely multi-source (not one child's stream
     * echoed twice, and not the root's own output).
     */
    public static List<AgentRef> outputProducers(List<SseEvent> events) {
        Map<String, AgentRef> byTask = new LinkedHashMap<>();
        if (events == null) {
            return List.of();
        }
        for (SseEvent e : events) {
            Map<String, Object> agentEvent = agentEventOf(e);
            if (agentEvent == null || !"output".equals(agentEvent.get("type"))) {
                continue;
            }
            AgentRef producer = refOf(agentEvent.get("source"));
            if (producer == null || producer.taskId() == null || producer.taskId().isBlank()) {
                continue;
            }
            byTask.putIfAbsent(producer.taskId(), producer);
        }
        return new ArrayList<>(byTask.values());
    }

    /** The {@code data.agentEvent} map of an event, or {@code null} when absent/not a map. */
    private static Map<String, Object> agentEventOf(SseEvent e) {
        Object v = (e.data() == null) ? null : e.data().get("agentEvent");
        return (v instanceof Map<?, ?> m) ? castToStringMap(m) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToStringMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** One {@code {agentId,taskId}} ref from an agentEvent's {@code source}/{@code target} node, or null. */
    private static AgentRef refOf(Object node) {
        if (!(node instanceof Map<?, ?> m)) {
            return null;
        }
        Object agentId = m.get("agentId");
        Object taskId = m.get("taskId");
        return new AgentRef(agentId == null ? "" : agentId.toString(),
                taskId == null ? null : taskId.toString());
    }

    // ===== Legacy black-box entry (pre-refresh wire: _remote_invocation projections) — DA-09 =====

    /** A derived child conversation reference (legacy {@code _remote_invocation} wire). */
    public record ChildRef(String childCid, String toolCallId) {}

    /**
     * Black-box entry: extract the parallel fan-out child conversation refs directly from raw A2A SDK
     * {@link ClientEvent}s, keyed on {@code _remote_invocation} part metadata.
     *
     * @deprecated the refreshed runtime (FEAT-027) no longer emits {@code _remote_invocation}; use
     *             {@link #delegations(List)} / {@link #fanOutToolCallIds(List)} on the adapter-projected
     *             events. Kept for DA-09's parallel-search probe until it migrates.
     * @param parentCid parent conversation cid (child cid = {@code parentCid:batchId:toolCallId})
     * @param events    raw SDK events collected by the black box ({@code A2aEventCollector.snapshotAllEvents()})
     * @return child refs of the fan-out batch; empty when no batch has ≥2 members
     */
    @Deprecated
    public static List<ChildRef> fromClientEvents(String parentCid, List<ClientEvent> events) {
        return fanOutFromProjections(parentCid, projectionsFromClientEvents(events));
    }

    /**
     * Black-box convenience: whether the event stream contains a parallel fan-out batch (≥2 members).
     *
     * @deprecated see {@link #fromClientEvents(String, List)}
     */
    @Deprecated
    public static boolean hasFanOut(String parentCid, List<ClientEvent> events) {
        return !fromClientEvents(parentCid, events).isEmpty();
    }

    /**
     * Extract every {@code _remote_invocation} projection from raw {@link ClientEvent}s (each is a
     * {@code {batchId, toolCallId}} map). Intentionally mirrors the adapter's legacy part walk — keep
     * the two in sync if the SDK event hierarchy changes.
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
            Map<String, Object> md = legacyPartMetadata(p);
            if (md == null) {
                continue;
            }
            collectProjection(md.get("_remote_invocation"), out);
        }
    }

    /** A part's {@code metadata()} map ({@link TextPart}/{@link DataPart} expose the same channel); other part types carry no projection channel. */
    private static Map<String, Object> legacyPartMetadata(Part<?> p) {
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

    /**
     * Batch-aggregation core: pick the batch with the most members; if it has ≥2, it is the fan-out.
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
     * Dedup key for the legacy parallel-driver derivation. A record (not a {@code batchId + ":" + toolCallId}
     * string) so a {@code ':'} inside either value cannot collide two distinct members into one entry.
     */
    private record DedupKey(String batchId, String toolCallId) {}

    private static String stringOf(Object o) {
        return o == null ? "" : o.toString();
    }
}
