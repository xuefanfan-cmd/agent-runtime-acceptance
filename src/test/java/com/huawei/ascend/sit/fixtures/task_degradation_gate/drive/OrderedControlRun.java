package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Orders task outcomes against a DA whose model is a {@link CorrelatedModelStub}: each task's model call is
 * matched by a marker and held at a barrier until the test releases it, never by a global FIFO. Setup steps are
 * recorded as preconditions or activation evidence.
 */
public final class OrderedControlRun implements AutoCloseable {
    public record Task(String session, CorrelatedModelStub.Plan plan, Future<DaEvidenceClient.Exchange> response) {}

    private final String id;
    private final CorrelatedModelStub model;
    private final DaEvidenceClient client;
    private final Scenario scenario;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final List<Task> tasks = new ArrayList<>();
    private String instance;
    /** DA process log with the terminal-fact DEBUG line ({@code withTerminalFactLog}); null when not awaited. */
    private Path factLog;

    public OrderedControlRun(String id, DaEvidenceClient client, CorrelatedModelStub model, Scenario scenario) {
        this.id = id;
        this.client = client;
        this.model = model;
        this.scenario = scenario;
    }

    public DaEvidenceClient client() {
        return client;
    }

    /**
     * After each finished task, wait until the DA has handed its terminal fact to the gate before anything reads the
     * gate. The DA marks a task finished before it dispatches the fact; on a slowed DA (libfaketime) a snapshot taken
     * right after the task completes can predate the fact. The DEBUG line is written after the gate processed it.
     */
    public OrderedControlRun awaitingFactsIn(Path processLog) {
        this.factLog = processLog;
        return this;
    }

    /** Current gate snapshot; the instance must stay the same for the whole run. */
    public GateDriver.Snapshot gate() throws Exception {
        var response = client.get("/v1/health/detail");
        JsonNode gate = response.status() == 200 ? response.json().path("degradationGate") : null;
        boolean readable = gate != null && gate.path("instanceId").isTextual() && !gate.path("instanceId").asText().isBlank();
        if (!readable) scenario.infrastructure("diagnostic snapshot readable", false, EvidenceSource.DIAGNOSTIC_SNAPSHOT, response.evidenceRef());
        if (instance == null) instance = gate.path("instanceId").asText();
        else scenario.precondition("same instance for the whole ordered run", instance.equals(gate.path("instanceId").asText()),
                EvidenceSource.DIAGNOSTIC_SNAPSHOT, response.evidenceRef());
        return new GateDriver.Snapshot(gate, response.evidenceRef());
    }

    /** Empty CLOSED window; an OPEN/HALF_OPEN gate is recovered through a real healthy probe. */
    public void empty() throws Exception {
        var gate = gate();
        if (!"CLOSED".equals(gate.state())) {
            half();
            var recover = submit("recover", false);
            recover.plan().awaitEntered();
            recover.plan().release(false);
            finished(recover, "completed");
            gate = gate();
        }
        scenario.precondition("gate CLOSED with an empty window", "CLOSED".equals(gate.state()) && gate.samples() == 0,
                EvidenceSource.DIAGNOSTIC_SNAPSHOT, gate.evidenceRef());
    }

    public Task submit(String role, boolean stream) {
        String marker = "feat112-" + role + "-" + UUID.randomUUID();
        var plan = model.hold(marker);
        String session = "feat112-" + UUID.randomUUID();
        var future = pool.submit(() -> client.post("/api/agents/" + (stream ? "run-stream" : "run"),
                Map.of("sessionId", session, "input", marker + " Reply with a short greeting")));
        var task = new Task(session, plan, future);
        tasks.add(task);
        return task;
    }

    public JsonNode task(Task task) throws Exception {
        var response = client.get("/api/agents/tasks/" + task.session() + "?sessionId=" + task.session());
        JsonNode list = response.status() == 200 ? response.json() : null;
        if (list == null || list.size() != 1) {
            scenario.infrastructure("exactly one task for the session", false, EvidenceSource.HTTP, response.evidenceRef());
        }
        return list.get(0);
    }

    public DaEvidenceClient.Exchange status(Task task, String taskId) throws Exception {
        return client.get("/api/agents/status/" + taskId + "?sessionId=" + task.session());
    }

    /** Polls the task until the status (or resume state) is reached; returns the last observation. */
    public JsonNode awaitStatus(Task task, String value, boolean resume) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        JsonNode last;
        do {
            last = task(task);
            if (value.equals(last.path(resume ? "resumeState" : "status").asText())) return last;
            Thread.sleep(25);
        } while (System.nanoTime() < until);
        return last;
    }

    /** The released task reached the terminal the test chose for it. */
    public void finished(Task task, String expected) throws Exception {
        var response = task.response().get(120, TimeUnit.SECONDS);
        boolean reached = response.status() == (expected.equals("failed") ? 500 : 200)
                && expected.equals(response.json().path("status").asText())
                && expected.equals(awaitStatus(task, expected, false).path("status").asText());
        scenario.activation("released task reached terminal " + expected, reached, EvidenceSource.HTTP, response.evidenceRef());
        if (factLog != null) awaitFact(task(task).path("id").asText());
    }

    private void awaitFact(String taskId) throws Exception {
        String line = "terminal fact taskId=" + taskId;
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        boolean seen;
        do {
            seen = Files.isRegularFile(factLog) && new String(Files.readAllBytes(factLog), StandardCharsets.UTF_8).contains(line);
            if (!seen) Thread.sleep(100);
        } while (!seen && System.nanoTime() < until);
        scenario.activation("terminal fact of task " + taskId + " processed by the gate (DA log)", seen, EvidenceSource.LOG,
                factLog.toString());
    }

    public void open() throws Exception {
        for (int i = 0; i < 20 && !"OPEN".equals(gate().state()); i++) {
            var t = submit("fault", false);
            t.plan().awaitEntered();
            t.plan().release(true);
            finished(t, "failed");
        }
        var gate = gate();
        scenario.precondition("gate OPEN", "OPEN".equals(gate.state()), EvidenceSource.DIAGNOSTIC_SNAPSHOT, gate.evidenceRef());
    }

    public void half() throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        GateDriver.Snapshot gate;
        do {
            gate = gate();
            if ("HALF_OPEN".equals(gate.state())) break;
            Thread.sleep(25);
        } while (System.nanoTime() < until);
        scenario.precondition("gate HALF_OPEN with the probe slot free",
                "HALF_OPEN".equals(gate.state()) && !gate.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, gate.evidenceRef());
    }

    @Override
    public void close() throws Exception {
        Throwable primary = null;
        try {
            for (Task t : tasks) {
                try {
                    var response = client.get("/api/agents/tasks/" + t.session() + "?sessionId=" + t.session());
                    if (response.status() == 200) {
                        for (JsonNode state : response.json()) {
                            if ("running".equals(state.path("status").asText())) {
                                client.post("/api/agents/cancel/" + state.path("id").asText() + "?sessionId=" + t.session(), Map.of());
                            }
                        }
                    }
                } catch (Exception | AssertionError e) {
                    primary = append(primary, e);
                } finally {
                    t.plan().unblockForCleanup();
                    t.response().cancel(true);
                }
            }
        } finally {
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("request workers did not terminate");
            } catch (Exception e) {
                primary = append(primary, e);
            }
            try {
                model.save(Path.of("target/feat112-evidence", id + "-barriers-" + UUID.randomUUID() + ".log"));
            } catch (Exception e) {
                primary = append(primary, e);
            }
        }
        if (primary instanceof Exception e) throw e;
        if (primary instanceof Error e) throw e;
    }

    private static Throwable append(Throwable primary, Throwable next) {
        if (primary == null) return next;
        primary.addSuppressed(next);
        return primary;
    }
}
