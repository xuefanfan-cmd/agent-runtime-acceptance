package com.huawei.ascend.sit.transport;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.huawei.ascend.sit.utils.JsonUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link WireLogger} that writes one human-readable file per round to
 * {@code <baseDir>/<runId>/<sessionId>-r<round>-<protocol>.log}. The per-run {@code runId} subdir
 * groups one JVM/test-run so repeated runs do not interleave. Each file has a REQUEST section (the
 * {@link OutboundMessage}: text, metadata, continuation ids, and — for {@code REST_QUERY}, which
 * carries the whole payload as a pre-rendered body — that body, pretty-printed) and a RESPONSE
 * section (every {@link InboundEvent}: kind/state/ids + decoded text + the raw frame).
 *
 * <p><b>Bounded & streamed.</b> The round is written line by line to a {@link BufferedWriter}; no
 * whole-log {@code String} is ever materialized. Every text/raw field is capped at
 * {@link #MAX_FIELD_CHARS}: a pathological frame — e.g. an A2A event whose {@code raw} is the SDK
 * {@code ClientEvent}/{@code Task} that serializes to megabytes of conversation history/artifacts —
 * is truncated with a marker rather than allowed to grow the output unbounded. Without these two
 * guards a single large round blew past Java's ~2.1B-char array limit (an
 * {@code OutOfMemoryError: Required array length … is too large}) inside a monolithic
 * {@code StringBuilder}. Object {@code raw}s are serialized straight to the (bounded) writer via
 * Jackson so the cap applies <em>during</em> serialization, never materializing the full JSON.
 *
 * <p>Config-free: {@code baseDir} and {@code runId} are constructor inputs (the client-layer
 * {@code WireLoggerResolver} reads them from {@code TestConfig}). IO failures are logged at WARNING
 * and swallowed — wire logging must never break a test.
 */
public final class FileWireLogger implements WireLogger {

    private static final Logger LOG = Logger.getLogger(FileWireLogger.class.getName());

    /** Max chars written for any single text or raw field; the rest is dropped with a marker. */
    static final int MAX_FIELD_CHARS = 262_144;   // 256 KB per field

    /**
     * Mapper for {@code raw} frames: the shared {@link JsonUtils} mapper plus an {@link OffsetDateTime}
     * serializer. A2A SDK events carry a {@code TaskStatus.timestamp} of type {@link OffsetDateTime};
     * the classpath has {@code jackson-databind} but not {@code jackson-datatype-jsr310}, so the base
     * mapper throws {@code InvalidDefinitionException} on that field. Serializing it to an ISO string
     * here keeps A2A raw frames readable instead of collapsing to a default {@code toString()}.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper RAW_MAPPER =
            JsonUtils.mapper().copy().registerModule(new SimpleModule()
                    .addSerializer(OffsetDateTime.class, new JsonSerializer<OffsetDateTime>() {
                        @Override
                        public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
                                throws IOException {
                            gen.writeString(value.toString());   // ISO offset, e.g. 2026-07-13T21:16:03Z
                        }
                    }));

    private final Path baseDir;
    private final String runId;

    public FileWireLogger(Path baseDir, String runId) {
        this.baseDir = baseDir;
        this.runId = runId;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void logRound(String protocol, int round, String sessionId,
                         OutboundMessage request, List<InboundEvent> response) {
        logRound(protocol, round, sessionId, request, response, null, null);
    }

    @Override
    public void logRound(String protocol, int round, String sessionId,
                         OutboundMessage request, List<InboundEvent> response, String wireRequest) {
        logRound(protocol, round, sessionId, request, response, wireRequest, null);
    }

    @Override
    public void logRound(String protocol, int round, String sessionId,
                         OutboundMessage request, List<InboundEvent> response,
                         String wireRequest, WireTiming timing) {
        String session = (sessionId == null || sessionId.isBlank()) ? "nosession" : sessionId;
        String name = sanitize(session) + "-r" + round + "-" + sanitize(protocol) + ".log";
        Path file = baseDir.resolve(runId).resolve(name);
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writeRound(w, protocol, round, session, request, response, wireRequest, timing);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "wire-log write failed: " + file, e);
        }
    }

    /** Stream the round to {@code w} — never accumulate the whole log in memory. */
    private static void writeRound(BufferedWriter w, String protocol, int round, String sessionId,
                                   OutboundMessage req, List<InboundEvent> resp,
                                   String wireRequest, WireTiming timing) throws IOException {
        w.write("# protocol: "); w.write(protocol);
        w.write("  round: "); w.write(Integer.toString(round));
        w.write("  sessionId: "); w.write(sessionId); w.write('\n');
        if (timing != null) {
            // Wall-clock send instant in the JVM's LOCAL zone (with offset, e.g. +08:00) so the value
            // aligns with the co-hosted agent's own logs — the SUT runs as a child process on the same
            // host, so it shares this TZ; a UTC "Z" value was off by 8h and impossible to correlate.
            // The settle duration is monotonic and zone-free.
            w.write("# sent: ");
            w.write(OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(timing.sentEpochMillis()), ZoneId.systemDefault()).toString());
            w.write("  duration: "); w.write(Long.toString(timing.durationMillis())); w.write("ms\n");
        }
        w.write('\n');

        w.write("====== REQUEST ======\n");
        w.write("text:\n"); writeIndented(w, req.text());
        w.write("metadata: "); w.write(req.metadata() == null || req.metadata().isEmpty()
                ? "(none)" : String.valueOf(req.metadata())); w.write('\n');
        w.write("taskId: "); w.write(blankOr(req.taskId())); w.write('\n');
        w.write("contextId: "); w.write(blankOr(req.contextId())); w.write('\n');
        // REST_QUERY carries the whole payload as a pre-rendered body (text/metadata are null); without
        // this line the REST request section rendered blank. A2A leaves body null → no line emitted.
        if (req.body() != null) {
            w.write("body:\n"); writeIndented(w, prettyJsonOrRaw(req.body()));
        }
        w.write('\n');

        // Paste-ready, copyable wire request (HTTP request-line + headers + body) for manual replay —
        // written verbatim with no "  | " prefix so it can be box-selected whole. Omitted when the caller
        // supplied none (the using(transport) unit-test seam, or a NOOP-adjacent path).
        if (wireRequest != null && !wireRequest.isEmpty()) {
            w.write("====== WIRE REQUEST (paste-ready) ======\n");
            writeVerbatim(w, wireRequest);
            w.write("========================================\n\n");
        }

        w.write("====== RESPONSE ("); w.write(Integer.toString(resp.size())); w.write(" events) ======\n");
        int i = 1;
        for (InboundEvent e : resp) {
            w.write('['); w.write(Integer.toString(i++)); w.write("] "); w.write(e.kind().toString());
            if (e.kind() == InboundEvent.Kind.STATE) {
                w.write("  state="); w.write(String.valueOf(e.state()));
            }
            if (!isBlank(e.taskId())) {
                w.write("  task="); w.write(e.taskId());
            }
            if (!isBlank(e.contextId())) {
                w.write("  ctx="); w.write(e.contextId());
            }
            w.write('\n');
            if (e.text() != null && !e.text().isEmpty()) {
                writeIndented(w, e.text());
            }
            Object raw = e.raw();
            if (raw != null) {
                writeRaw(w, raw);
            }
        }
    }

    /**
     * Render a raw frame (capped): strings verbatim, objects as compact JSON. For an A2A SDK
     * {@code ClientEvent} the object is first rebuilt as its JSON-RPC wire envelope
     * ({@code {"jsonrpc":"2.0","id":null,"result":{"artifactUpdate":...}}}) via
     * {@link A2aEventMapping#wireEnvelopeOf(Object)} — the consumer layer only ever sees the parsed
     * Java object, so serializing it directly would show the SDK's record shape, not the frame on the
     * wire. Non-{@code ClientEvent} raws (REST payloads, {@code String}s) pass through unchanged.
     *
     * <p>The object is serialized into a <em>capped in-memory buffer</em>, never the live writer. The
     * old impl streamed straight to the writer and, on a serialization failure, appended
     * {@code String.valueOf(raw)} AFTER the half-flushed JSON — a corrupt hybrid (e.g.
     * {@code …"timestamp"TaskUpdateEvent@31f75323}): Jackson had already written up to the
     * {@link OffsetDateTime} field before throwing. Buffering lets us DISCARD the partial output on
     * failure and emit a clean {@code toString()} instead. The cap still bounds a pathological raw (a
     * terminal {@code Task} snapshot), so the writer never sees an unbounded blob.
     */
    private static void writeRaw(BufferedWriter w, Object raw) throws IOException {
        w.write("  raw: ");
        if (raw instanceof String s) {
            writeCapped(w, s);
            w.write('\n');
            return;
        }
        // Reconstruct the wire envelope for A2A ClientEvents; serialize REST/path-through raws as-is.
        Object envelope = A2aEventMapping.wireEnvelopeOf(raw);
        Object target = envelope != null ? envelope : raw;
        StringWriter buffer = new StringWriter();
        BoundedWriter cap = new BoundedWriter(buffer, MAX_FIELD_CHARS);
        String rendered;
        try {
            RAW_MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT).writeValue(cap, target);
            rendered = buffer.toString();
        } catch (Exception ex) {
            // Partial JSON in `buffer` is discarded — never mixed with the toString fallback.
            rendered = String.valueOf(raw);
        }
        if (cap.truncated()) {
            w.write(rendered, 0, Math.min(rendered.length(), MAX_FIELD_CHARS));
            w.write(" … <truncated>");
        } else {
            writeCapped(w, rendered);
        }
        w.write('\n');
    }

    /** Write {@code s} line by line under a {@code "  | "} prefix; cap the total before scanning. */
    private static void writeIndented(BufferedWriter w, String s) throws IOException {
        if (s == null || s.isEmpty()) {
            w.write('\n');
            return;
        }
        boolean over = s.length() > MAX_FIELD_CHARS;
        String view = over ? s.substring(0, MAX_FIELD_CHARS) : s;
        int start = 0;
        while (start < view.length()) {
            int nl = view.indexOf('\n', start);
            int end = nl < 0 ? view.length() : nl;
            w.write("  | ");
            w.write(view, start, end - start);
            w.write('\n');
            if (nl < 0) break;
            start = nl + 1;
        }
        if (over) {
            w.write("  | … <truncated, original "); w.write(Integer.toString(s.length())); w.write(" chars>\n");
        }
    }

    /**
     * Pretty-print a JSON body string for readability in the log; fall back to the raw string when it
     * isn't valid JSON. The result is later capped + line-prefixed by {@link #writeIndented}.
     */
    private static String prettyJsonOrRaw(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        try {
            return JsonUtils.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(JsonUtils.mapper().readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    /** Write {@code s}, truncated to {@link #MAX_FIELD_CHARS} with a marker when it exceeds that. */
    private static void writeCapped(BufferedWriter w, String s) throws IOException {
        if (s.length() <= MAX_FIELD_CHARS) {
            w.write(s);
            return;
        }
        w.write(s, 0, MAX_FIELD_CHARS);
        w.write(" … <truncated, original "); w.write(Integer.toString(s.length())); w.write(" chars>");
    }

    /**
     * Write a paste-ready block verbatim — no {@code "  | "} prefix, so a human can box-select the whole
     * HTTP request-line + headers + body and copy it into a client. Capped at {@link #MAX_FIELD_CHARS} so
     * a pathological body cannot grow the file unbounded; the block otherwise never carries a prefix.
     */
    private static void writeVerbatim(BufferedWriter w, String block) throws IOException {
        w.write(block, 0, Math.min(block.length(), MAX_FIELD_CHARS));
        if (block.length() > MAX_FIELD_CHARS) {
            w.write("\n… <truncated, original "); w.write(Integer.toString(block.length())); w.write(" chars>");
        }
        w.write('\n');
    }

    private static String blankOr(String s) {
        return isBlank(s) ? "(none)" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Keep filenames safe: [A-Za-z0-9._-], everything else → '-'. */
    private static String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "x";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' ? c : '-');
        }
        return sb.toString();
    }

    /**
     * A {@link Writer} proxy that forwards up to {@code max} chars to the underlying writer, then
     * silently drops the rest and flags {@link #truncated()}. Used to bound Jackson's streamed JSON
     * output; it never throws, so Jackson completes normally (just truncated).
     */
    private static final class BoundedWriter extends Writer {
        private final Writer out;
        private final long max;
        private long written;
        private boolean truncated;

        BoundedWriter(Writer out, long max) {
            this.out = out;
            this.max = max;
        }

        boolean truncated() {
            return truncated;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            if (truncated || len <= 0) {
                return;
            }
            long remaining = max - written;
            if (len <= remaining) {
                out.write(cbuf, off, len);
                written += len;
            } else {
                if (remaining > 0) {
                    out.write(cbuf, off, (int) remaining);
                    written += remaining;
                }
                truncated = true;
            }
        }

        @Override
        public void flush() {
            // no-op — the underlying BufferedWriter is flushed/closed by the caller.
        }

        @Override
        public void close() {
            // no-op — do not close the underlying writer (it is reused for the rest of the round).
        }
    }
}
