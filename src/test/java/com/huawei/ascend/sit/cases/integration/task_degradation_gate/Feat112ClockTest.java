package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.ClockControl;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.Feat112Integration;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.GateDeployment;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.OrderedControlRun;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * Terminal-time rules that only a moved clock can reach from outside (feature §5.1.2, §5.1.4, acceptance exit 15):
 * terminal facts are stamped with the DA wall clock, the window watermark only moves forward, so moving the DA
 * process clock back (libfaketime, {@link DeploymentForm.Infra#CLOCK_CONTROL}) makes a later terminal land earlier
 * than recorded samples (inside the window) or below the window floor (expired). Every shift is confirmed from a
 * time the DA itself reports (task {@code createdAt}, response {@code Date}) before anything is judged.
 */
@Feat112Integration
class Feat112ClockTest {
    private static final Duration WINDOW = Duration.ofSeconds(60);
    /**
     * Back past the window floor. A fact stays expired while (real time since the last forward reading) is below the
     * margin, so the margin covers the steps between the shift and the facts that must expire. Under libfaketime the DA
     * runs several times slower (first model call ~13s on the reference Linux host), so the margin is two minutes.
     */
    private static final Duration EXPIRING_SHIFT = WINDOW.plusSeconds(120);

    private static DeploymentForm ordered(String name) {
        // 30s quiet: a slowed DA must not reach HALF_OPEN before the steps that need OPEN are done
        return DeploymentForm.gate(name, 2, "0.5", WINDOW.toSeconds() + "s", "30s").withCorrelatedModel().withClockControl()
                .withTerminalFactLog();
    }

    /** Records whether the DA clock is behind the test clock by about {@code expected}, from a DA response Date header. */
    private static void shifted(Scenario s, DaEvidenceClient.Exchange response, Instant received, Duration expected) {
        Long lag = ClockControl.reportedLagSeconds(response.headers(), received);
        s.activation("DA clock moved back about " + expected.toSeconds() + "s (response Date lags the test clock by " + lag + "s)",
                lag != null && Math.abs(lag - expected.toSeconds()) <= 10, EvidenceSource.HTTP, response.evidenceRef());
    }

    @Test
    @Covers(value = "FEAT-112-B6", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-B6] 窗口内乱序到达的终态照常计入")
    void lateTerminalInsideWindowCounts(Scenario s) throws Exception {
        var form = DeploymentForm.gate("b6", 20, "0.5", "5m", "60s").withClockControl();
        try (var deployment = GateDeployment.start(form); var d = deployment.driver(s)) {
            d.prepareEmptyClosed();
            d.complete(false);
            deployment.clock().set(Duration.ofSeconds(-60));
            d.failures();
            String session = d.session();
            Instant sent = Instant.now();
            var response = d.run(session);
            s.precondition("task failed after the shift", response.status() == 500 && "failed".equals(response.json().path("status").asText()),
                    EvidenceSource.HTTP, response.evidenceRef());
            var task = d.tasks(session).get(0);
            long lag = Duration.between(Instant.parse(task.path("createdAt").asText()), sent).toSeconds();
            s.activation("the failed task was stamped about 60s behind the earlier healthy sample (createdAt lags the test clock by "
                    + lag + "s)", lag >= 50 && lag <= 75, EvidenceSource.HTTP, "session " + session);
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "afterLateTerminal", after.gate(), after.evidenceRef())
                    .samples(2, "§5.1.2").failures(1, "§5.1.2").state("CLOSED", "§5.1.2");
        }
    }

    @Test
    @Covers(value = "FEAT-112-B7", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-B7] OPEN 中旧任务的过期失败不记样本、不改变状态")
    void expiredFailureWhileOpenIsIgnored(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(ordered("b7-open"))) {
            deployment.record(s);
            try (var run = new OrderedControlRun(s.id(), deployment.client(deployment.secret(), s.id()), deployment.correlatedModel(), s)
                    .awaitingFactsIn(deployment.processLog())) {
                run.empty();
                var old = run.submit("old", false);
                old.plan().awaitEntered();
                run.open();
                deployment.clock().set(EXPIRING_SHIFT.negated());
                var probeRequest = run.client().post("/api/agents/run", Map.of("sessionId", "feat112-" + UUID.randomUUID(),
                        "input", "Reply with a short greeting"));
                shifted(s, probeRequest, Instant.now(), EXPIRING_SHIFT);
                var beforeFact = run.gate();
                s.precondition("gate OPEN before the old task ends", "OPEN".equals(beforeFact.state()),
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, beforeFact.evidenceRef());
                old.plan().release(true);
                run.finished(old, "failed");
                var after = run.gate();
                // retryAfterSeconds is not compared: the quiet period runs on the raw clock, so the shift alone moves it
                GateSnapshotChecks.of(s, s.id(), "afterExpiredFailure", after.gate(), after.evidenceRef())
                        .state("OPEN", "§5.1.4/验收出口15").samples(beforeFact.samples(), "§5.1.4").failures(beforeFact.failures(), "§5.1.4");
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-B7", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-B7] HALF_OPEN 中旧任务的过期失败不回 OPEN，当前探针随后健康仍可关门")
    void expiredFailureWhileHalfOpenDoesNotVetoProbe(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(ordered("b7-half"))) {
            deployment.record(s);
            try (var run = new OrderedControlRun(s.id(), deployment.client(deployment.secret(), s.id()), deployment.correlatedModel(), s)
                    .awaitingFactsIn(deployment.processLog())) {
                run.empty();
                var old = run.submit("old", false);
                old.plan().awaitEntered();
                run.open();
                run.half();
                var probe = run.submit("probe", false);
                probe.plan().awaitEntered();
                deployment.clock().set(EXPIRING_SHIFT.negated());
                var refused = run.client().post("/api/agents/run", Map.of("sessionId", "feat112-" + UUID.randomUUID(),
                        "input", "Reply with a short greeting"));
                shifted(s, refused, Instant.now(), EXPIRING_SHIFT);
                var beforeFact = run.gate();
                s.precondition("HALF_OPEN with the probe in flight before the old task ends",
                        "HALF_OPEN".equals(beforeFact.state()) && beforeFact.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, beforeFact.evidenceRef());
                old.plan().release(true);
                run.finished(old, "failed");
                var afterOld = run.gate();
                GateSnapshotChecks.of(s, s.id(), "afterExpiredFailure", afterOld.gate(), afterOld.evidenceRef())
                        .state("HALF_OPEN", "§5.1.4/验收出口15").probeInFlight(true, "§5.1.4")
                        .samples(beforeFact.samples(), "§5.1.4").failures(beforeFact.failures(), "§5.1.4");
                // let real time pass the window floor again so the probe's own terminal is a valid (unexpired) fact
                Thread.sleep(EXPIRING_SHIFT.minus(WINDOW).plusSeconds(5).toMillis());
                probe.plan().release(false);
                run.finished(probe, "completed");
                var afterProbe = run.gate();
                Expect.equal(s, s.id(), "probeStillClosesGate", "§5.1.4/验收出口15", Category.INTERNAL_STATE,
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, afterProbe.evidenceRef(), afterProbe.state(), "CLOSED");
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-E9", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E9] 当前探针过期落定只释放名额，保持 HALF_OPEN")
    void expiredProbeOnlyReleasesSlot(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(ordered("e9"))) {
            deployment.record(s);
            try (var run = new OrderedControlRun(s.id(), deployment.client(deployment.secret(), s.id()), deployment.correlatedModel(), s)
                    .awaitingFactsIn(deployment.processLog())) {
                run.empty();
                run.open();
                run.half();
                var healthy = run.submit("probe-healthy", false);
                healthy.plan().awaitEntered();
                deployment.clock().set(EXPIRING_SHIFT.negated());
                var refused = run.client().post("/api/agents/run", Map.of("sessionId", "feat112-" + UUID.randomUUID(),
                        "input", "Reply with a short greeting"));
                shifted(s, refused, Instant.now(), EXPIRING_SHIFT);
                var before = run.gate();
                s.precondition("HALF_OPEN with the probe in flight", "HALF_OPEN".equals(before.state()) && before.probeInFlight(),
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, before.evidenceRef());
                healthy.plan().release(false);
                run.finished(healthy, "completed");
                var afterHealthy = run.gate();
                GateSnapshotChecks.of(s, s.id(), "afterExpiredHealthyProbe", afterHealthy.gate(), afterHealthy.evidenceRef())
                        .state("HALF_OPEN", "§5.1.4/§5.1.6").probeInFlight(false, "§5.1.4/§5.1.6").samples(before.samples(), "§5.1.4");
                var failing = run.submit("probe-failing", false);
                failing.plan().awaitEntered();
                var occupied = run.gate();
                Expect.equal(s, s.id(), "nextTaskBecomesProbe", "§5.1.4/§5.1.6", Category.EXTERNAL_CONTRACT,
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, occupied.evidenceRef(), occupied.probeInFlight(), true);
                failing.plan().release(true);
                run.finished(failing, "failed");
                var afterFailing = run.gate();
                GateSnapshotChecks.of(s, s.id(), "afterExpiredFailingProbe", afterFailing.gate(), afterFailing.evidenceRef())
                        .state("HALF_OPEN", "§5.1.4/§5.1.6").probeInFlight(false, "§5.1.4/§5.1.6")
                        .samples(before.samples(), "§5.1.4").failures(before.failures(), "§5.1.4");
            }
        }
    }
}
