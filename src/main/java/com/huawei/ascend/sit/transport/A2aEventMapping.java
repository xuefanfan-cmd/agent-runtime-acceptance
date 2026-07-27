package com.huawei.ascend.sit.transport;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UpdateEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps A2A SDK {@link ClientEvent}s to neutral {@link InboundEvent}s. Task-bearing events (a
 * {@link TaskEvent} or a {@link TaskUpdateEvent} whose update is a {@link TaskStatusUpdateEvent})
 * → one {@link InboundEvent} {@code STATE}; artifact-update {@link TaskUpdateEvent}s and
 * {@link MessageEvent}s → one event <em>per text part</em>, classified by {@link LlmPayload}: a
 * part whose text is a typed {@code {type,index,payload}} envelope becomes an
 * {@link InboundEvent.Kind#ANSWER}/{@code LLM_OUTPUT}/{@code LLM_REASONING}/{@code LLM_USAGE} event,
 * a plain-text part falls back to {@link InboundEvent.Kind#CONTENT}.
 *
 * <p>This is the A2A-specific half of the unified model. {@link #mergeArtifactText(List)} reproduces
 * the legacy collector's append/replace artifact-text reconstruction over the generic event list via
 * {@code .raw}. {@link #contentEventsOf(Task)} surfaces a terminal task's answer as classified events
 * (used by {@link A2aSyncTransport}, which receives no streamed chunks).
 */
public final class A2aEventMapping {

    private A2aEventMapping() {}

    /**
     * One {@link ClientEvent} → zero-or-more neutral events. Artifact/message events yield one event
     * per text part; STATE events yield one. Empty list for events with no neutral representation.
     */
    public static List<InboundEvent> toEventList(ClientEvent event) {
        if (event == null) {
            return List.of();
        }
        if (event instanceof TaskEvent te) {
            Task t = te.getTask();
            return t == null ? List.of() : List.of(state(t, event));
        }
        if (event instanceof TaskUpdateEvent tue) {
            UpdateEvent upd = tue.getUpdateEvent();
            if (upd instanceof TaskStatusUpdateEvent) {
                Task t = tue.getTask();
                return t == null ? List.of() : List.of(state(t, event));
            }
            if (upd instanceof TaskArtifactUpdateEvent aue) {
                return partEvents(aue.artifact() == null ? null : aue.artifact().parts(), event);
            }
            return List.of();
        }
        if (event instanceof MessageEvent me) {
            Message m = me.getMessage();
            return partEvents(m == null ? null : m.parts(), event);
        }
        return List.of();
    }

    /** Convenience: the first neutral event for {@code event}, or {@code null} if there is none. */
    public static InboundEvent toEvent(ClientEvent event) {
        List<InboundEvent> es = toEventList(event);
        return es.isEmpty() ? null : es.get(0);
    }

    /** Map a list, flattening multi-part events and dropping nothing (nulls already excluded). */
    public static List<InboundEvent> toEvents(List<ClientEvent> events) {
        List<InboundEvent> out = new ArrayList<>();
        for (ClientEvent e : events) {
            out.addAll(toEventList(e));
        }
        return out;
    }

    /**
     * The {@link Task} carried by a task-bearing {@link ClientEvent} (a {@link TaskEvent} or a
     * {@link TaskUpdateEvent}); {@code null} otherwise. This is the sync-terminal extraction point —
     * it works for either event shape the SDK may deliver for {@code message/send}.
     */
    public static Task taskOf(ClientEvent event) {
        if (event instanceof TaskEvent te) {
            return te.getTask();
        }
        if (event instanceof TaskUpdateEvent tue) {
            return tue.getTask();
        }
        return null;
    }

    /**
     * Reconstruct the JSON-RPC wire envelope of an A2A SDK {@link ClientEvent} for evidentiary logging.
     *
     * <p>The SDK's SSE layer ({@code SSEEventListener}) parses each frame into a {@link ClientEvent} and
     * discards the raw bytes, so sit's consumer layer ({@code BiConsumer<ClientEvent, AgentCard>}) only
     * ever sees Java objects. Serializing those directly yields the SDK's record shape (e.g.
     * {@code {"task":...,"updateEvent":...}} for a {@link TaskUpdateEvent}) rather than the wire shape
     * that crossed the SSE stream (e.g. {@code {"jsonrpc":"2.0","id":null,"result":{"artifactUpdate":{...}}}}).
     * This rebuilds the envelope — keyed by the SDK's own {@code STREAMING_EVENT_ID} discriminators
     * ({@code artifactUpdate}/{@code statusUpdate}) plus the {@code message}/{@code task} frame kinds — so
     * wire logs read like the frames on the wire. Structurally faithful, not byte-identical (field order
     * and null-handling may differ from the true SSE bytes).
     *
     * <p>For a {@link TaskUpdateEvent} only the {@code updateEvent} is placed under the discriminator
     * (not the bundled {@code Task}): that mirrors the wire frame and avoids re-serializing the cumulative
     * task (the O(n²) guard from {@link #contentEventsOf}).
     *
     * @param raw an {@link InboundEvent#raw()} value — a {@link ClientEvent} for A2A, something else for REST
     * @return the envelope as a serializable {@link Map}, or {@code null} if {@code raw} is not a
     *         {@link ClientEvent} (the caller then serializes the object as-is)
     */
    public static Map<String, Object> wireEnvelopeOf(Object raw) {
        if (!(raw instanceof ClientEvent event)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (event instanceof TaskUpdateEvent tue) {
            UpdateEvent upd = tue.getUpdateEvent();
            if (upd instanceof TaskArtifactUpdateEvent aue) {
                result.put(TaskArtifactUpdateEvent.STREAMING_EVENT_ID, aue);
            } else if (upd instanceof TaskStatusUpdateEvent sue) {
                result.put(TaskStatusUpdateEvent.STREAMING_EVENT_ID, sue);
            }
        } else if (event instanceof MessageEvent me) {
            if (me.getMessage() != null) {
                result.put("message", me.getMessage());
            }
        } else if (event instanceof TaskEvent te) {
            if (te.getTask() != null) {
                result.put("task", te.getTask());
            }
        }
        if (result.isEmpty()) {
            return null;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", null);
        envelope.put("result", result);
        return envelope;
    }

    /**
     * Classified answer events for a task's reply surface: one event per text part across the task's
     * artifacts (typed envelopes via {@link LlmPayload}, plain text → {@link InboundEvent.Kind#ANSWER}).
     * If the task has no artifact parts, falls back to the status message's text, then the last history
     * entry's text, as ANSWER events — the same priority order as the legacy task-text extractor.
     * Empty list if the task carries no answer text at all.
     *
     * <p>Derived events carry {@code raw = null} <em>by design</em>. They originate from a terminal
     * {@link Task} snapshot, so {@code raw = the Task} would re-serialize the entire cumulative task
     * (artifacts + history) into every part-event's wire-log line — O(n²), which blew A2A-sync wire
     * logs to hundreds of MB on real SUTs. The raw is unused downstream ({@link #mergeArtifactText(List)}
     * matches {@code raw instanceof TaskUpdateEvent} only), and each part's text already lives in the
     * event's {@link InboundEvent#text()}.
     */
    public static List<InboundEvent> contentEventsOf(Task t) {
        if (t == null) {
            return List.of();
        }
        List<InboundEvent> out = new ArrayList<>();
        if (t.artifacts() != null) {
            for (Artifact artifact : t.artifacts()) {
                if (artifact != null) {
                    out.addAll(partEvents(artifact.parts(), null, true));
                }
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        // No artifact text — fall back to status message, then last history entry. Every text the
        // task carries IS its reply, so surface plain text as ANSWER (e.g. an INPUT_REQUIRED task's
        // clarifying question lives in status.message).
        if (t.status() != null && t.status().message() != null) {
            out.addAll(partEvents(t.status().message().parts(), null, true));
        }
        if (out.isEmpty() && t.history() != null && !t.history().isEmpty()) {
            Message last = t.history().get(t.history().size() - 1);
            if (last != null) {
                out.addAll(partEvents(last.parts(), null, true));
            }
        }
        return out;
    }

    /**
     * One classified event per text part: typed {@code {type,index,payload}} envelopes via
     * {@link LlmPayload}; a plain-text part becomes {@link InboundEvent.Kind#CONTENT} by default (live
     * streamed chunks are intermediate), or {@link InboundEvent.Kind#ANSWER} when
     * {@code plainTextAsAnswer} is set — used by {@link #contentEventsOf}, where every text a settled
     * task carries IS its reply. Non-{@link TextPart}s and null texts are skipped.
     */
    private static List<InboundEvent> partEvents(List<Part<?>> parts, Object raw) {
        return partEvents(parts, raw, false);
    }

    private static List<InboundEvent> partEvents(List<Part<?>> parts, Object raw, boolean plainTextAsAnswer) {
        if (parts == null) {
            return List.of();
        }
        List<InboundEvent> out = new ArrayList<>();
        for (Part<?> part : parts) {
            if (part instanceof TextPart tp && tp.text() != null) {
                InboundEvent typed = LlmPayload.classify(tp.text(), raw);
                if (typed != null) {
                    out.add(typed);
                } else {
                    out.add(plainTextAsAnswer
                            ? InboundEvent.answer(tp.text(), raw)
                            : InboundEvent.content(tp.text(), raw));
                }
            }
        }
        return out;
    }

    private static InboundEvent state(Task t, Object raw) {
        TaskState s = t.status() == null ? null : t.status().state();
        return InboundEvent.state(s, t.id(), t.contextId(), raw);
    }

    /**
     * Faithful A2A artifact-text merge: per-artifactId, {@code append=true} concatenates,
     * {@code append=false/null} replaces; distinct artifacts joined by a blank line. Fed from the
     * generic event list via {@code .raw} (the {@code TaskUpdateEvent}/{@code TaskArtifactUpdateEvent}).
     */
    public static String mergeArtifactText(List<InboundEvent> events) {
        Map<String, StringBuilder> byArtifact = new LinkedHashMap<>();
        for (InboundEvent e : events) {
            if (e.raw() instanceof TaskUpdateEvent tue
                    && tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue
                    && aue.artifact() != null) {
                Artifact art = aue.artifact();
                String chunk = textOf(art);
                if (chunk.isEmpty()) continue;
                String key = art.artifactId() != null && !art.artifactId().isBlank()
                        ? art.artifactId()
                        : (art.name() != null && !art.name().isBlank() ? "name=" + art.name() : "anonymous");
                StringBuilder buf = byArtifact.computeIfAbsent(key, k -> new StringBuilder());
                if (Boolean.TRUE.equals(aue.append())) {
                    buf.append(chunk);
                } else {
                    buf.setLength(0);
                    buf.append(chunk);
                }
            }
        }
        return String.join("\n\n", byArtifact.values().stream()
                .map(StringBuilder::toString).toList());
    }

    private static String textOf(Artifact artifact) {
        return artifact == null ? "" : partsText(artifact.parts());
    }

    private static String partsText(List<Part<?>> parts) {
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart tp && tp.text() != null) {
                sb.append(tp.text());
            }
        }
        return sb.toString();
    }
}
