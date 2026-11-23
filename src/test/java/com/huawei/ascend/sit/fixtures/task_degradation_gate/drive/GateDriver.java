package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Drives one DA instance through its public surface and the declared fault stub. It establishes scenario states
 * and records them as preconditions or activation evidence; it never decides acceptance. No reflection,
 * synthetic terminal facts or direct gate mutation.
 */
public final class GateDriver implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT = "Reply with a short greeting";

    /** The {@code degradationGate} object of one diagnostic response and where that response was recorded. */
    public record Snapshot(JsonNode gate, String evidenceRef) {
        public String state() { return gate.path("state").asText(); }
        public String instanceId() { return gate.path("instanceId").asText(); }
        public long samples() { return count(gate, "sampleCount"); }
        public long failures() { return count(gate, "systemFailureCount"); }
        public boolean probeInFlight() { return gate.path("probeInFlight").asBoolean(); }
        public long retryAfterSeconds() { return gate.path("retryAfterSeconds").asLong(-1); }
    }

    /** A task that reached its public terminal through the declared stub, and the snapshot taken right after. */
    public record Completed(Snapshot after, String terminal, String exchangeRef) {}

    private final DaEvidenceClient sut;
    private final DaEvidenceClient stub;
    private final Scenario scenario;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final List<Future<?>> submitted = new ArrayList<>();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    /** {@code stubUrl == null} for deployments whose model is a test-owned in-process stub. */
    public GateDriver(String sutUrl, String stubUrl, String secret, String caseId, Scenario scenario) throws Exception {
        this.scenario = scenario;
        this.sut = new DaEvidenceClient(sutUrl, secret, caseId);
        this.stub = stubUrl == null ? null : new DaEvidenceClient(stubUrl, null, caseId + "-stub");
    }

    public DaEvidenceClient sut() { return sut; }

    public Scenario scenario() { return scenario; }

    public String session() {
        String session = "feat112-" + UUID.randomUUID();
        sessions.add(session);
        return session;
    }

    // ---- observation -------------------------------------------------------------------------------------

    public Snapshot snapshot() throws Exception {
        var response = sut.get("/v1/health/detail");
        JsonNode gate = response.status() == 200 ? response.json().get("degradationGate") : null;
        boolean readable = gate != null && gate.isObject() && gate.path("instanceId").isTextual()
                && countable(gate, "sampleCount") && countable(gate, "systemFailureCount");
        if (!readable) {
            scenario.infrastructure("diagnostic snapshot readable (HTTP 200 with degradationGate)", false,
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, response.evidenceRef());
        }
        return new Snapshot(gate, response.evidenceRef());
    }

    /** Polls until the state is reached; returns the matching or the last snapshot without judging it. */
    public Snapshot awaitState(String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Snapshot last;
        do {
            last = snapshot();
            if (expected.equals(last.state())) return last;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return last;
    }

    public Snapshot establishState(String expected, Duration timeout) throws Exception {
        Snapshot reached = awaitState(expected, timeout);
        scenario.precondition("gate reaches " + expected + " within " + timeout.toSeconds() + "s",
                expected.equals(reached.state()), EvidenceSource.DIAGNOSTIC_SNAPSHOT, reached.evidenceRef());
        return reached;
    }

    public JsonNode stats() throws Exception {
        var response = requireStub().get("/__test/stats");
        if (response.status() != 200) {
            scenario.infrastructure("fault stub statistics readable", false, EvidenceSource.STUB, response.evidenceRef());
        }
        return response.json();
    }

    public JsonNode tasks(String session) throws Exception {
        var response = sut.get("/api/agents/tasks/" + session + "?sessionId=" + session);
        JsonNode tasks = response.status() == 200 ? response.json() : null;
        if (tasks == null || !tasks.isArray()) {
            scenario.infrastructure("task list readable for the test session", false, EvidenceSource.HTTP, response.evidenceRef());
        }
        return tasks;
    }

    public JsonNode status(String session, String task) throws Exception {
        var response = sut.get("/api/agents/status/" + task + "?sessionId=" + session);
        if (response.status() != 200) {
            scenario.infrastructure("task status readable", false, EvidenceSource.HTTP, response.evidenceRef());
        }
        return response.json();
    }

    // ---- fault stub control ------------------------------------------------------------------------------

    public void reset() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        JsonNode stats = stats();
        while (count(stats, "activeRequests") != 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
            stats = stats();
        }
        var response = requireStub().post("/__test/reset", Map.of());
        if (response.status() != 200) {
            scenario.infrastructure("fault stub reset with no request in flight", false, EvidenceSource.STUB, response.evidenceRef());
        }
    }

    private void pattern(List<Map<String, Object>> responses, String purpose) throws Exception {
        var response = requireStub().post("/__test/set-pattern", Map.of("responses", responses));
        if (response.status() != 200) {
            scenario.infrastructure("fault stub accepts " + purpose, false, EvidenceSource.STUB, response.evidenceRef());
        }
    }

    public void failures() throws Exception {
        pattern(List.of(Map.of("errorCode", "ENGINE_MODEL_ERROR", "repeat", 256)), "model 5xx pattern");
    }

    public void delayed() throws Exception {
        pattern(List.of(Map.of("code", 200, "delay", 30000, "repeat", 256)), "delayed healthy responses");
    }

    public void delayedFailure() throws Exception {
        pattern(List.of(Map.of("errorCode", "ENGINE_MODEL_ERROR", "delay", 15000),
                Map.of("errorCode", "ENGINE_MODEL_ERROR", "repeat", 255)), "delayed model 5xx");
    }

    /** Provider-shaped context-length rejection so the product's own classifier assigns 181012. */
    public void contextTooLong() throws Exception {
        Map<String, Object> error = Map.of("code", "context_length_exceeded", "type", "invalid_request_error",
                "message", "This model's maximum context length is 8192 tokens. However, your messages resulted in 9001 tokens.");
        pattern(List.of(Map.of("code", 400, "body", Map.of("error", error))), "provider context-length rejection");
    }

    /** A provider-shaped error body ({@code {"error": ...}}) with the given HTTP status, served once. */
    public void providerRejection(int status, Map<String, Object> error) throws Exception {
        pattern(List.of(Map.of("code", status, "body", Map.of("error", error))), "provider rejection " + error.get("code"));
    }

    public void tool(String name, Map<String, Object> arguments) throws Exception {
        toolThen(name, arguments, List.of());
    }

    /** One model tool call, then the given responses (an exhausted pattern falls back to the stub's healthy default). */
    public void toolThen(String name, Map<String, Object> arguments, List<Map<String, Object>> then) throws Exception {
        List<Map<String, Object>> responses = new java.util.ArrayList<>();
        responses.add(toolCallResponse("call-feat112", name, arguments));
        responses.addAll(then);
        pattern(responses, "tool call " + name);
    }

    /**
     * An ordered model script, served to whichever conversation calls next (main agent and workflow members share the
     * model endpoint). An exhausted script falls back to the stub's healthy default.
     */
    public void script(List<Map<String, Object>> responses, String purpose) throws Exception {
        pattern(responses, purpose);
    }

    /** A stub pattern entry answering with one tool call; call ids must be unique within a conversation. */
    public static Map<String, Object> toolCallResponse(String callId, String name, Map<String, Object> arguments) throws Exception {
        String encoded = JSON.writeValueAsString(arguments);
        Map<String, Object> body = Map.of("id", "feat112-" + callId, "object", "chat.completion", "model", "deepanalyze-model",
                "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls", "message",
                        Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", callId,
                                "type", "function", "function", Map.of("name", name, "arguments", encoded)))))));
        return Map.of("code", 200, "body", body);
    }

    /** Stub limit on one response pattern (LlmFaultInjectionStub MAX_PATTERN_SIZE). */
    private static final int STUB_PATTERN_LIMIT = 256;

    /**
     * Model failures that exhaust the product's retries, as a pattern tail for {@link #toolThen} (one slot is left
     * for the tool call so the whole pattern stays within the stub's limit).
     */
    public static List<Map<String, Object>> exhaustingFailures() {
        return List.of(Map.of("errorCode", "ENGINE_MODEL_ERROR", "repeat", STUB_PATTERN_LIMIT - 1));
    }

    /** Provider-shaped 500s whose error message carries the given text (for leak checks of raw provider errors). */
    public static List<Map<String, Object>> exhaustingFailuresSaying(String message) {
        return List.of(Map.of("code", 500, "body", Map.of("error", Map.of("message", message, "type", "server_error")),
                "repeat", STUB_PATTERN_LIMIT - 1));
    }

    public void failuresSaying(String message) throws Exception {
        pattern(exhaustingFailuresSaying(message), "model 5xx carrying a sentinel");
    }

    private DaEvidenceClient requireStub() {
        if (stub == null) throw new IllegalStateException("this deployment has no fault stub");
        return stub;
    }

    // ---- tasks -------------------------------------------------------------------------------------------

    public DaEvidenceClient.Exchange run(String session) throws Exception {
        sessions.add(session);
        return sut.post("/api/agents/run", Map.of("sessionId", session, "input", PROMPT));
    }

    public DaEvidenceClient.Exchange run() throws Exception {
        return run(session());
    }

    /**
     * Runs one task and records that its outcome came through the declared stub: a healthy run must reach the
     * stub, an injected failure must be served by it. Otherwise the outcome came from another path (wrong model
     * base URL, real provider) and the scenario was not activated.
     */
    public DaEvidenceClient.Exchange runThroughStub(boolean injectedFailure) throws Exception {
        JsonNode before = stats();
        var response = run();
        JsonNode after = stats();
        String counter = injectedFailure ? "errorResponses" : "totalRequests";
        boolean served = count(after, "generation") == count(before, "generation") && count(after, counter) > count(before, counter);
        scenario.activation("task outcome passed through the declared stub (" + counter + " increased)", served,
                EvidenceSource.STUB, response.evidenceRef());
        return response;
    }

    /** Healthy or injected-failure task whose public terminal is recorded as the precondition of what follows. */
    public Completed complete(boolean fail) throws Exception {
        if (fail) failures(); else reset();
        var response = runThroughStub(fail);
        String terminal = fail ? "failed" : "completed";
        boolean reached = response.status() == (fail ? 500 : 200) && terminal.equals(response.json().path("status").asText());
        scenario.precondition("task reached public terminal " + terminal, reached, EvidenceSource.HTTP, response.evidenceRef());
        return new Completed(snapshot(), terminal, response.evidenceRef());
    }

    public Snapshot prepareEmptyClosed() throws Exception {
        Snapshot gate = snapshot();
        if ("OPEN".equals(gate.state())) gate = establishState("HALF_OPEN", Duration.ofSeconds(90));
        if ("HALF_OPEN".equals(gate.state())) {
            scenario.precondition("probe slot free for a recovery task", !gate.probeInFlight(),
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, gate.evidenceRef());
            reset();
            var recovery = runThroughStub(false);
            scenario.precondition("recovery task completed", recovery.status() == 200, EvidenceSource.HTTP, recovery.evidenceRef());
            gate = establishState("CLOSED", Duration.ofSeconds(20));
        }
        scenario.precondition("gate CLOSED before the scenario", "CLOSED".equals(gate.state()),
                EvidenceSource.DIAGNOSTIC_SNAPSHOT, gate.evidenceRef());
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (gate.samples() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
            gate = snapshot();
        }
        scenario.precondition("empty CLOSED window (deployment window short enough to drain)",
                gate.samples() == 0 && "CLOSED".equals(gate.state()), EvidenceSource.DIAGNOSTIC_SNAPSHOT, gate.evidenceRef());
        reset();
        return gate;
    }

    /** Opens the gate with genuine failed tasks served by the stub; every step is recorded as a precondition. */
    public Snapshot openGate() throws Exception {
        String instance = prepareEmptyClosed().instanceId();
        for (int i = 0; i < 100; i++) {
            Snapshot before = snapshot();
            failures();
            var response = runThroughStub(true);
            boolean failed = response.status() == 500 && "failed".equals(response.json().path("status").asText());
            scenario.precondition("injected task failed (HTTP 500, status failed)", failed, EvidenceSource.HTTP, response.evidenceRef());
            Snapshot after = snapshot();
            scenario.precondition("same instance while opening", instance.equals(after.instanceId()),
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, after.evidenceRef());
            scenario.precondition("failed task counted as a system failure", after.failures() > before.failures(),
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, after.evidenceRef());
            if ("OPEN".equals(after.state())) return after;
        }
        Snapshot last = snapshot();
        scenario.precondition("gate OPEN within 100 failed tasks", false, EvidenceSource.DIAGNOSTIC_SNAPSHOT, last.evidenceRef());
        return last;
    }

    public Future<DaEvidenceClient.Exchange> async(String session, CountDownLatch start) {
        sessions.add(session);
        Future<DaEvidenceClient.Exchange> task = workers.submit(() -> {
            start.await();
            return run(session);
        });
        submitted.add(task);
        return task;
    }

    public Future<DaEvidenceClient.Exchange> asyncStream(String session, String input) {
        sessions.add(session);
        Future<DaEvidenceClient.Exchange> task = workers.submit(() -> sut.post("/api/agents/run-stream",
                Map.of("sessionId", session, "input", input)));
        submitted.add(task);
        return task;
    }

    /** Expected transport loss after an owned process restart: forget only an already-settled future. */
    public void acknowledgeProcessTermination(Future<?> future) {
        if (!future.isDone()) throw new IllegalStateException("transport has not settled");
        submitted.remove(future);
    }

    /** The process this driver addressed was restarted: its sessions ended with it and need no cleanup. */
    public void processRestarted() {
        for (Future<?> future : submitted) {
            if (!future.isDone()) throw new IllegalStateException("acknowledge in-flight requests before forgetting the process");
        }
        sessions.clear();
    }

    public JsonNode awaitActive(String session) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode tasks;
        boolean active;
        String ref;
        do {
            tasks = tasks(session);
            JsonNode stats = stats();
            active = tasks.size() == 1 && count(stats, "activeRequests") > 0;
            ref = "session " + session;
            if (active) break;
            Thread.sleep(25);
        } while (System.nanoTime() < until);
        scenario.activation("task registered and its model call in flight", active, EvidenceSource.STUB, ref);
        return tasks.get(0);
    }

    public JsonNode awaitQuestion(String session) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode tasks;
        boolean waiting;
        do {
            tasks = tasks(session);
            waiting = tasks.size() == 1 && "awaiting_answer".equals(tasks.get(0).path("resumeState").asText());
            if (waiting) break;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        scenario.activation("task reached a real awaiting_answer", waiting, EvidenceSource.HTTP, "session " + session);
        return tasks.get(0);
    }

    /** Requests cancellation and records that the public terminal settled to cancelled. */
    public JsonNode cancel(String session, String task) throws Exception {
        var response = sut.post("/api/agents/cancel/" + task + "?sessionId=" + session, Map.of());
        scenario.activation("cancellation acknowledged", response.status() == 200
                && "cancelled".equals(response.json().path("status").asText()), EvidenceSource.HTTP, response.evidenceRef());
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        JsonNode state;
        do {
            state = status(session, task);
            if ("cancelled".equals(state.path("status").asText())) break;
            Thread.sleep(25);
        } while (System.nanoTime() < until);
        scenario.activation("cancellation settled to public terminal cancelled",
                "cancelled".equals(state.path("status").asText()), EvidenceSource.HTTP, "task " + task);
        return state;
    }

    boolean workersTerminated() {
        return workers.isTerminated();
    }

    @Override
    public void close() throws Exception {
        Throwable cleanup = null;
        try {
            Set<String> owned = new HashSet<>(sessions);
            for (String session : owned) {
                try {
                    var response = sut.get("/api/agents/tasks/" + session + "?sessionId=" + session);
                    if (response.status() != 200) throw new IllegalStateException("task list unavailable during cleanup: HTTP " + response.status());
                    for (JsonNode task : response.json()) {
                        if ("running".equals(task.path("status").asText())) {
                            var cancel = sut.post("/api/agents/cancel/" + task.path("id").asText() + "?sessionId=" + session, Map.of());
                            if (cancel.status() != 200 && cancel.status() != 404) {
                                throw new IllegalStateException("cleanup cancel failed: HTTP " + cancel.status());
                            }
                        }
                    }
                } catch (Exception | AssertionError failure) {
                    cleanup = append(cleanup, failure);
                }
            }
        } finally {
            for (Future<?> future : submitted) if (!future.isDone()) future.cancel(true);
            workers.shutdownNow();
            try {
                if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("worker cleanup timed out");
            } catch (Exception failure) {
                cleanup = append(cleanup, failure);
            }
            if (stub != null) {
                try {
                    // A model call still held by the stub (e.g. a delayed response to a restarted process) must
                    // drain first: the stub refuses reset while a request is active.
                    long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
                    while (System.nanoTime() < deadline) {
                        var stats = stub.get("/__test/stats");
                        JsonNode body = stats.status() == 200 ? stats.json() : null;
                        if (body == null || !countable(body, "activeRequests") || body.get("activeRequests").longValue() == 0) break;
                        Thread.sleep(100);
                    }
                    var reset = stub.post("/__test/reset", Map.of());
                    if (reset.status() != 200) throw new IllegalStateException("stub reset failed during cleanup: HTTP " + reset.status());
                } catch (Exception failure) {
                    cleanup = append(cleanup, failure);
                }
            }
        }
        if (cleanup instanceof Exception exception) throw exception;
        if (cleanup instanceof Error error) throw error;
    }

    private static Throwable append(Throwable primary, Throwable next) {
        if (primary == null) return next;
        primary.addSuppressed(next);
        return primary;
    }

    private static boolean countable(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    public static long count(JsonNode node, String field) {
        if (node == null || !countable(node, field)) {
            throw new IllegalStateException("missing, non-integral or negative " + field);
        }
        return node.get(field).longValue();
    }
}
