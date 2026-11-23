package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.Feat112Integration;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.GateDeployment;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.OrderedControlRun;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Duration;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * Ordering of outcomes against a correlated model double: late probe success versus an older failure
 * (feature §5.1.4, acceptance exit 15), and in-flight controls while OPEN (capability "只拦新增负担", exit 8).
 */
@Feat112Integration
class Feat112OrderedControlTest {
    private static DeploymentForm correlated(String name, String quiet) {
        return DeploymentForm.gate(name, 2, "0.5", "10m", quiet).withCorrelatedModel();
    }

    @Test
    @Covers(value = "FEAT-112-C5", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-C5] 旧任务故障先记账则回 OPEN，迟到探针成功不得关门")
    void oldFailureBeforeProbeSuccess(Scenario s) throws Exception {
        ordered(s, false);
    }

    @Test
    @Covers(value = "FEAT-112-C6", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-C6] 探针先关门后，旧任务故障按 CLOSED 窗口正常记账")
    void probeClosureBeforeOldFailure(Scenario s) throws Exception {
        ordered(s, true);
    }

    private void ordered(Scenario s, boolean probeFirst) throws Exception {
        try (var deployment = GateDeployment.start(correlated(probeFirst ? "c6" : "c5", "10s"))) {
            deployment.record(s);
            try (var run = new OrderedControlRun(s.id(), deployment.client(deployment.secret(), s.id()), deployment.correlatedModel(), s)) {
                run.empty();
                var old = run.submit("old", false);
                old.plan().awaitEntered();
                run.open();
                run.half();
                var probe = run.submit("probe", false);
                probe.plan().awaitEntered();
                var occupied = run.gate();
                s.activation("probe holds the slot while the older task is still in flight", occupied.probeInFlight(),
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, occupied.evidenceRef());
                if (probeFirst) {
                    probe.plan().release(false);
                    run.finished(probe, "completed");
                    var closed = run.gate();
                    GateSnapshotChecks.of(s, s.id(), "afterProbe", closed.gate(), closed.evidenceRef())
                            .state("CLOSED", "验收出口15/§5.1.4").samples(0, "验收出口12").failures(0, "验收出口12");
                    old.plan().release(true);
                    run.finished(old, "failed");
                    var after = run.gate();
                    GateSnapshotChecks.of(s, s.id(), "afterOldFailure", after.gate(), after.evidenceRef())
                            .state("CLOSED", "验收出口15/§5.1.2").samples(1, "验收出口15/§5.1.2").failures(1, "验收出口15/§5.1.2");
                } else {
                    old.plan().release(true);
                    run.finished(old, "failed");
                    var reopened = run.gate();
                    GateSnapshotChecks.of(s, s.id(), "afterOldFailure", reopened.gate(), reopened.evidenceRef())
                            .state("OPEN", "验收出口15/§5.1.4");
                    probe.plan().release(false);
                    run.finished(probe, "completed");
                    var after = run.gate();
                    GateSnapshotChecks.of(s, s.id(), "afterLateProbe", after.gate(), after.evidenceRef())
                            .stateIn(List.of("OPEN", "HALF_OPEN"), "验收出口15/§5.1.4");
                }
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-F5", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-F5] OPEN 下 9 个在途任务入口都保持原契约")
    void controlsWhileOpen(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(correlated("freg", "60s"))) {
            deployment.record(s);
            try (var run = new OrderedControlRun(s.id(), deployment.client(deployment.secret(), s.id()), deployment.correlatedModel(), s)) {
                run.empty();
                var active = run.submit("active", false);
                active.plan().awaitEntered();
                var waiting = awaiting(s, run, "waiting");
                var waitingMessage = awaiting(s, run, "waiting-message");
                var waitingResume = awaiting(s, run, "waiting-resume");
                String activeId = run.task(active).path("id").asText();
                var client = run.client();
                // CLOSED baselines of the read-only entrances, taken on the same running task
                Map<String, String> paths = new LinkedHashMap<>();
                paths.put("status", "/api/agents/status/" + activeId + "?sessionId=" + active.session());
                paths.put("task", "/api/agents/task/" + activeId + "?sessionId=" + active.session());
                paths.put("tasks", "/api/agents/tasks/" + active.session() + "?sessionId=" + active.session());
                Map<String, String> closed = new LinkedHashMap<>();
                for (var entry : paths.entrySet()) closed.put(entry.getKey(), contract(client.get(entry.getValue())));
                // CLOSED baselines of the state-changing entrances, each on its own task in the same state
                var baseline = run.submit("active-baseline", false);
                baseline.plan().awaitEntered();
                String baselineId = run.task(baseline).path("id").asText();
                closed.put("inject", contract(client.post("/api/agents/inject/" + baselineId + "?sessionId=" + baseline.session(),
                        Map.of("message", "Use a short harmless greeting"))));
                closed.put("cancel", contract(client.post("/api/agents/cancel/" + baselineId + "?sessionId=" + baseline.session(), Map.of())));
                baseline.plan().release(false);
                run.awaitStatus(baseline, "cancelled", false);
                closed.put("answer", answeredWhileClosed(run, awaiting(s, run, "waiting-baseline"), "/api/agents/answer/",
                        Map.of("answer", "Hello"), Map.of()));
                closed.put("message", answeredWhileClosed(run, awaiting(s, run, "waiting-message-baseline"), "/api/agents/message/",
                        Map.of("answer", "Hello"), Map.of()));
                closed.put("resume", answeredWhileClosed(run, awaiting(s, run, "waiting-resume-baseline"), "/api/agents/resume/",
                        Map.of("answer", "Hello"), Map.of("Idempotency-Key", "feat112-" + UUID.randomUUID())));
                var stillClosed = run.gate();
                s.precondition("baselines taken while the gate is CLOSED", "CLOSED".equals(stillClosed.state()),
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, stillClosed.evidenceRef());
                run.open();
                for (var entry : paths.entrySet()) {
                    var response = client.get(entry.getValue());
                    notDegraded(s, entry.getKey(), response.status(), response.body(), response.evidenceRef());
                    Expect.equal(s, s.id(), entry.getKey() + "SameAsClosed", "验收出口8/§3.1", Category.EXTERNAL_CONTRACT,
                            EvidenceSource.HTTP, response.evidenceRef(), contract(response), closed.get(entry.getKey()));
                }
                var replay = client.disconnectAfterStart("/api/agents/stream/" + activeId + "?sessionId=" + active.session());
                Expect.that(s, s.id(), "replayServed", "验收出口8/§3.1", Category.EXTERNAL_CONTRACT, EvidenceSource.SSE,
                        replay.evidenceRef(), replay, "200 text/event-stream with a start frame while OPEN",
                        com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient.SseCapture::servedWithStart);
                var injected = client.post("/api/agents/inject/" + activeId + "?sessionId=" + active.session(),
                        Map.of("message", "Use a short harmless greeting"));
                notDegraded(s, "inject", injected.status(), injected.body(), injected.evidenceRef());
                Expect.equal(s, s.id(), "injectSameAsClosed", "验收出口8/§3.1", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                        injected.evidenceRef(), contract(injected), closed.get("inject"));
                var cancelled = client.post("/api/agents/cancel/" + activeId + "?sessionId=" + active.session(), Map.of());
                notDegraded(s, "cancel", cancelled.status(), cancelled.body(), cancelled.evidenceRef());
                Expect.equal(s, s.id(), "cancelSameAsClosed", "验收出口8/§3.1", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                        cancelled.evidenceRef(), contract(cancelled), closed.get("cancel"));
                active.plan().release(false);
                var activeTerminal = run.awaitStatus(active, "cancelled", false);
                Expect.equal(s, s.id(), "cancelSettles", "验收出口8", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                        "session " + active.session(), activeTerminal.path("status").asText(), "cancelled");
                var stillOpen = run.gate();
                s.precondition("gate still OPEN before answering", "OPEN".equals(stillOpen.state()),
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, stillOpen.evidenceRef());
                answered(s, run, waiting, "/api/agents/answer/", "answer", Map.of("answer", "Hello"), Map.of(), closed.get("answer"));
                answered(s, run, waitingMessage, "/api/agents/message/", "message", Map.of("answer", "Hello"), Map.of(),
                        closed.get("message"));
                answered(s, run, waitingResume, "/api/agents/resume/", "resume", Map.of("answer", "Hello"),
                        Map.of("Idempotency-Key", "feat112-" + UUID.randomUUID()), closed.get("resume"));
            }
        }
    }

    /** A task parked on a real awaiting_answer before the gate opens. */
    private static OrderedControlRun.Task awaiting(Scenario s, OrderedControlRun run, String role) throws Exception {
        var task = run.submit(role, true);
        task.plan().awaitEntered();
        task.plan().tool("ask_user", Map.of("questions", List.of(Map.of("question", "Choose a harmless greeting?"))));
        var question = run.awaitStatus(task, "awaiting_answer", true);
        s.activation(role + ": a real awaiting_answer task exists before OPEN", "awaiting_answer".equals(question.path("resumeState").asText()),
                EvidenceSource.HTTP, "session " + task.session());
        return task;
    }

    /** CLOSED baseline of an answering entrance: the response contract, after the answered task has completed. */
    private static String answeredWhileClosed(OrderedControlRun run, OrderedControlRun.Task task, String route,
                                              Map<String, String> body, Map<String, String> headers) throws Exception {
        String taskId = run.task(task).path("id").asText();
        var response = run.client().post(route + taskId + "?sessionId=" + task.session(), body, headers);
        run.awaitStatus(task, "completed", false);
        return contract(response);
    }

    /** Answers a parked task through one entrance while OPEN: same contract as CLOSED, and the task completes. */
    private static void answered(Scenario s, OrderedControlRun run, OrderedControlRun.Task task, String route, String name,
                                 Map<String, String> body, Map<String, String> headers, String closedContract) throws Exception {
        String taskId = run.task(task).path("id").asText();
        var response = run.client().post(route + taskId + "?sessionId=" + task.session(), body, headers);
        notDegraded(s, name, response.status(), response.body(), response.evidenceRef());
        Expect.equal(s, s.id(), name + "SameAsClosed", "验收出口8/§3.1", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                response.evidenceRef(), contract(response), closedContract);
        var terminal = run.awaitStatus(task, "completed", false);
        Expect.equal(s, s.id(), name + "TaskCompletes", "验收出口8", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                "session " + task.session(), terminal.path("status").asText(), "completed");
    }

    /** Status code plus the task status field: what a CLOSED and an OPEN response of a read entrance must share. */
    private static String contract(com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient.Exchange response) {
        String status;
        try {
            var json = response.json();
            status = json.isArray() ? (json.size() == 1 ? json.get(0).path("status").asText() : "list:" + json.size())
                    : json.path("status").asText();
        } catch (Exception notJson) {
            status = "non-json";
        }
        return response.status() + "/" + status;
    }

    @Test
    @Covers(value = "FEAT-112-C8", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-C8] 探针取消落定后，旧任务的未过期系统故障仍让门回 OPEN")
    void oldFailureAfterProbeCancellationReopens(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(correlated("c8", "10s"))) {
            deployment.record(s);
            try (var run = new OrderedControlRun(s.id(), deployment.client(deployment.secret(), s.id()), deployment.correlatedModel(), s)) {
                run.empty();
                var old = run.submit("old", false);
                old.plan().awaitEntered();
                run.open();
                run.half();
                var probe = run.submit("probe", false);
                probe.plan().awaitEntered();
                String probeId = run.task(probe).path("id").asText();
                var occupied = run.gate();
                s.activation("probe holds the slot while the older task is still in flight", occupied.probeInFlight(),
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, occupied.evidenceRef());
                var cancel = run.client().post("/api/agents/cancel/" + probeId + "?sessionId=" + probe.session(), Map.of());
                s.activation("probe cancellation accepted", cancel.status() == 200, EvidenceSource.HTTP, cancel.evidenceRef());
                probe.plan().release(false);
                var cancelled = run.awaitStatus(probe, "cancelled", false);
                s.activation("probe cancellation settled", "cancelled".equals(cancelled.path("status").asText()),
                        EvidenceSource.HTTP, "session " + probe.session());
                var released = run.gate();
                s.precondition("cancellation kept HALF_OPEN and released the slot",
                        "HALF_OPEN".equals(released.state()) && !released.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, released.evidenceRef());
                old.plan().release(true);
                run.finished(old, "failed");
                var reopened = run.gate();
                GateSnapshotChecks.of(s, s.id(), "afterOldFailure", reopened.gate(), reopened.evidenceRef())
                        .state("OPEN", "验收出口15/§5.1.4").probeInFlight(false, "§5.1.7").retryAfterPositive("验收出口15/§5.1.7");
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-C9", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-C9] OPEN 中旧任务的未过期系统故障重算静默期")
    void oldFailureWhileOpenRestartsQuiet(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(correlated("c9", "40s"))) {
            deployment.record(s);
            try (var run = new OrderedControlRun(s.id(), deployment.client(deployment.secret(), s.id()), deployment.correlatedModel(), s)) {
                run.empty();
                var old = run.submit("old", false);
                old.plan().awaitEntered();
                run.open();
                Thread.sleep(Duration.ofSeconds(12).toMillis());
                var before = run.gate();
                s.precondition("OPEN with the quiet period partly elapsed", "OPEN".equals(before.state())
                        && before.retryAfterSeconds() > 0 && before.retryAfterSeconds() <= 30, EvidenceSource.DIAGNOSTIC_SNAPSHOT, before.evidenceRef());
                old.plan().release(true);
                run.finished(old, "failed");
                var after = run.gate();
                GateSnapshotChecks.of(s, s.id(), "afterOldFailure", after.gate(), after.evidenceRef()).state("OPEN", "§5.1.2/§5.1.7");
                Expect.that(s, s.id(), "quietRestarted", "§5.1.2/§5.1.7", Category.EXTERNAL_CONTRACT, EvidenceSource.DIAGNOSTIC_SNAPSHOT,
                        before.evidenceRef() + " -> " + after.evidenceRef(), after.retryAfterSeconds(),
                        "retryAfterSeconds back near the full quiet period (>= 37s, was " + before.retryAfterSeconds() + ")",
                        remaining -> remaining >= 37);
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-E12", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E12] 认领后待答任务数触顶被拒，名额释放，后续真实任务可成为探针")
    void awaitingCapAfterClaimReleasesSlot(Scenario s) throws Exception {
        var form = correlated("e12", "10s").with("deepanalyze.compat.max-awaiting-tasks", "1");
        try (var deployment = GateDeployment.start(form)) {
            deployment.record(s);
            try (var run = new OrderedControlRun(s.id(), deployment.client(deployment.secret(), s.id()), deployment.correlatedModel(), s)) {
                run.empty();
                var old = run.submit("old", true);
                old.plan().awaitEntered();
                run.open();
                run.half();
                old.plan().tool("ask_user", Map.of("questions", List.of(Map.of("question", "Choose a harmless greeting?"))));
                var parked = run.awaitStatus(old, "awaiting_answer", true);
                s.precondition("an older task holds the only awaiting slot", "awaiting_answer".equals(parked.path("resumeState").asText()),
                        EvidenceSource.HTTP, "session " + old.session());
                var gate = run.gate();
                s.precondition("HALF_OPEN with the probe slot free", "HALF_OPEN".equals(gate.state()) && !gate.probeInFlight(),
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, gate.evidenceRef());
                var refusedTask = run.submit("refused", false);
                var refused = refusedTask.response().get(30, java.util.concurrent.TimeUnit.SECONDS);
                String code = refused.status() == 503 ? refused.json().path("code").asText() : "";
                s.activation("the new task was refused by the awaiting cap after the claim (HTTP 503)", refused.status() == 503,
                        EvidenceSource.HTTP, refused.evidenceRef());
                Expect.equal(s, s.id(), "existingBusyNotDegraded", "验收出口18/§5.1.9", Category.EXTERNAL_CONTRACT,
                        EvidenceSource.HTTP, refused.evidenceRef(), code, "ENGINE_BUSY");
                var afterRefusal = run.gate();
                GateSnapshotChecks.of(s, s.id(), "afterRefusal", afterRefusal.gate(), afterRefusal.evidenceRef())
                        .state("HALF_OPEN", "验收出口18/§5.1.6").probeInFlight(false, "验收出口18");
                String oldId = run.task(old).path("id").asText();
                var cancel = run.client().post("/api/agents/cancel/" + oldId + "?sessionId=" + old.session(), Map.of());
                s.precondition("the parked task was cancelled to free the awaiting slot", cancel.status() == 200,
                        EvidenceSource.HTTP, cancel.evidenceRef());
                run.awaitStatus(old, "cancelled", false);
                var next = run.submit("next-probe", false);
                next.plan().awaitEntered();
                var occupied = run.gate();
                Expect.equal(s, s.id(), "nextTaskBecomesProbe", "验收出口18/§5.1.6", Category.EXTERNAL_CONTRACT,
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, occupied.evidenceRef(), occupied.probeInFlight(), true);
                next.plan().release(false);
                run.finished(next, "completed");
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-E8", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E8] 认领后执行池拒绝：既有 ENGINE_BUSY，名额释放、保持 HALF_OPEN，后续真实任务可成为探针")
    void poolRejectionAfterClaimReleasesSlot(Scenario s) throws Exception {
        // One execution thread: a resumed task (continuations share the task pool) holds it while a new task is admitted.
        var form = correlated("e8", "10s").with("deepanalyze.compat.max-concurrent-tasks", "1");
        try (var deployment = GateDeployment.start(form)) {
            deployment.record(s);
            try (var run = new OrderedControlRun(s.id(), deployment.client(deployment.secret(), s.id()), deployment.correlatedModel(), s)) {
                run.empty();
                var parked = awaiting(s, run, "parked");
                run.open();
                run.half();
                parked.plan().holdContinuation();
                String parkedId = run.task(parked).path("id").asText();
                var answer = run.client().post("/api/agents/answer/" + parkedId + "?sessionId=" + parked.session(),
                        Map.of("answer", "Hello"));
                s.precondition("the parked task was resumed through /answer while HALF_OPEN", answer.status() == 200,
                        EvidenceSource.HTTP, answer.evidenceRef());
                parked.plan().awaitContinuationEntered();
                var before = run.gate();
                s.precondition("HALF_OPEN with the probe slot free while the resumed task holds the only execution thread",
                        "HALF_OPEN".equals(before.state()) && !before.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, before.evidenceRef());
                var rejectedTask = run.submit("rejected", false);
                var rejected = rejectedTask.response().get(30, java.util.concurrent.TimeUnit.SECONDS);
                String body = rejected.body() == null ? "" : rejected.body();
                s.activation("the new task was refused by the saturated execution pool after admission (503, too many running tasks)",
                        rejected.status() == 503 && body.contains("too many running tasks"), EvidenceSource.HTTP, rejected.evidenceRef());
                Expect.equal(s, s.id(), "existingBusyNotDegraded", "验收出口18/§5.1.6", Category.EXTERNAL_CONTRACT,
                        EvidenceSource.HTTP, rejected.evidenceRef(), rejected.json().path("code").asText(), "ENGINE_BUSY");
                var afterRefusal = run.gate();
                GateSnapshotChecks.of(s, s.id(), "afterRefusal", afterRefusal.gate(), afterRefusal.evidenceRef())
                        .state("HALF_OPEN", "验收出口18/§5.1.6").probeInFlight(false, "验收出口18/§5.1.6")
                        .samples(before.samples(), "§5.1.3");
                parked.plan().releaseContinuation();
                run.awaitStatus(parked, "completed", false);
                var next = run.submit("next-probe", false);
                next.plan().awaitEntered();
                var occupied = run.gate();
                Expect.equal(s, s.id(), "nextTaskBecomesProbe", "验收出口18/§5.1.6", Category.EXTERNAL_CONTRACT,
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, occupied.evidenceRef(), occupied.probeInFlight(), true);
                next.plan().release(false);
                run.finished(next, "completed");
            }
        }
    }

    private static void notDegraded(Scenario s, String control, int status, String body, String ref) {
        Expect.that(s, s.id(), control + "NotDegraded", "能力表“只拦新增负担”/§3.1", Category.EXTERNAL_CONTRACT,
                EvidenceSource.HTTP, ref, status + " " + body, "no ENGINE_DEGRADED, no degraded=true",
                text -> !text.contains("ENGINE_DEGRADED") && !text.replace(" ", "").contains("\"degraded\":true"));
    }
}
