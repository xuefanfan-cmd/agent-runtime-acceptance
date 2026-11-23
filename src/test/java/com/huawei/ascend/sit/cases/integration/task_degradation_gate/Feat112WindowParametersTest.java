package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.Feat112Integration;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.GateDeployment;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.RejectionJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.RejectionJudge.Rejection;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.SequenceJudge;
import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;

import java.time.Duration;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * Window thresholds and their legal boundaries. Each scenario owns a deployment whose gate parameters are the
 * subject of the verdict (feature §3.3, §5.1.2, acceptance exits 2, 3, 4, 19).
 */
@Feat112Integration
class Feat112WindowParametersTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static DeploymentForm form(String name, int minimum, String threshold, String window, String quiet) {
        return DeploymentForm.gate(name, minimum, threshold, window, quiet);
    }

    /** One W-family finding after an observed terminal on the same instance inside one declared window. */
    private static void window(Scenario s, String id, GateDriver.Snapshot before, GateDriver.Completed after,
                               long samples, long failures, String state, boolean withinWindow) {
        s.accept(id, java.util.List.of(SequenceJudge.window(id, s.context(), Observation.captured(
                new SequenceJudge.Window(before.gate(), after.after().gate(), after.terminal(), withinWindow),
                EvidenceSource.DIAGNOSTIC_SNAPSHOT, after.after().evidenceRef()), after.terminal(), samples, failures, state)));
    }

    @Test
    @Covers(value = "FEAT-112-A7", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-A7] min-sample-size=1 合法，一次真实系统失败即开门")
    void minimumOne(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(form("a7", 1, "0.5", "10m", "60s")); var d = deployment.driver(s)) {
            var before = d.prepareEmptyClosed();
            window(s, s.id(), before, d.complete(true), 1, 1, "OPEN", true);
        }
    }

    @Test
    @Covers(value = "FEAT-112-A8", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-A8] failure-rate-threshold=1.0 合法，混合窗口不开门、全失败开门")
    void thresholdOne(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(form("a8-mixed", 2, "1.0", "10m", "60s")); var d = deployment.driver(s)) {
            var before = d.prepareEmptyClosed();
            d.complete(false);
            window(s, s.id(), before, d.complete(true), 2, 1, "CLOSED", true);
        }
        try (var deployment = GateDeployment.start(form("a8-all", 2, "1.0", "10m", "60s")); var d = deployment.driver(s)) {
            var before = d.prepareEmptyClosed();
            d.complete(true);
            window(s, s.id(), before, d.complete(true), 2, 2, "OPEN", true);
        }
    }

    @Test
    @Covers(value = "FEAT-112-A9", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-A9] open-quiet-period=0 合法，开门后可立即进入 HALF_OPEN 而非 CLOSED")
    void zeroQuietPeriod(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(form("a9", 1, "0.5", "10m", "0s")); var d = deployment.driver(s)) {
            d.prepareEmptyClosed();
            var failed = d.complete(true);
            GateSnapshotChecks.of(s, s.id(), "afterFailure", failed.after().gate(), failed.after().evidenceRef())
                    .state("HALF_OPEN", "验收出口19/§5.1.7").probeInFlight(false, "§5.1.6").failures(1, "§5.1.3");
        }
    }

    @Test
    @Covers(value = {"FEAT-112-B1.1", "FEAT-112-B1.2", "FEAT-112-B1.3"}, layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-B1.1/B1.2/B1.3] 前 19 个失败不开门，第 20 个记账后开门，第 21 个新任务被拒")
    void sampleBoundary(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(form("b1", 20, "0.5", "10m", "60s")); var d = deployment.driver(s)) {
            var before = d.prepareEmptyClosed();
            for (int i = 1; i <= 19; i++) window(s, "FEAT-112-B1.1", before, d.complete(true), i, i, "CLOSED", true);
            var twentieth = d.complete(true);
            window(s, "FEAT-112-B1.2", before, twentieth, 20, 20, "OPEN", true);
            var refused = d.run(d.session());
            s.accept("FEAT-112-B1.3", RejectionJudge.judge("FEAT-112-B1.3", s.context(), Observation.captured(
                    new Rejection(refused.status(), refused.headers(), JSON.readTree(refused.body())), EvidenceSource.HTTP,
                    refused.evidenceRef()), "OPEN", twentieth.after().instanceId()).stream()
                    .filter(f -> f.ruleId().endsWith(".status") || f.ruleId().endsWith(".code")).toList());
            var after = d.snapshot();
            GateSnapshotChecks.of(s, "FEAT-112-B1.3", "afterRefusal", after.gate(), after.evidenceRef())
                    .samples(20, "§3.1/验收出口5");
        }
    }

    @Test
    @Covers(value = "FEAT-112-B2", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-B2] 30 正常 + 10 失败（25%）不开门")
    void belowThreshold(Scenario s) throws Exception {
        mixed(s, "b2", 30, "CLOSED");
    }

    @Test
    @Covers(value = {"FEAT-112-B3", "FEAT-112-C1"}, layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-B3/C1] 10 正常 + 10 失败（50%）在样本量与阈值同时满足时开门")
    void exactThreshold(Scenario s) throws Exception {
        mixed(s, "b3-c1", 10, "OPEN");
    }

    private void mixed(Scenario s, String name, int healthy, String expected) throws Exception {
        try (var deployment = GateDeployment.start(form(name, 20, "0.5", "10m", "60s")); var d = deployment.driver(s)) {
            var before = d.prepareEmptyClosed();
            long started = System.nanoTime();
            for (int i = 0; i < healthy; i++) d.complete(false);
            for (int i = 1; i <= 10; i++) {
                var after = d.complete(true);
                boolean within = System.nanoTime() - started < Duration.ofMinutes(9).toNanos();
                String state = i == 10 ? expected : "CLOSED";
                for (String id : s.ids()) window(s, id, before, after, healthy + i, i, state, within);
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-B5", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-B5] 健康样本先过期后残窗双阈值达标，新任务到达即开门")
    void residualWindowOpensOnArrival(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(form("b5", 2, "0.5", "30s", "60s")); var d = deployment.driver(s)) {
            d.prepareEmptyClosed();
            long firstStart = System.nanoTime();
            for (int i = 0; i < 4; i++) d.complete(false);
            long healthyEnd = System.nanoTime();
            s.precondition("healthy cohort recorded within 5s", healthyEnd - firstStart < Duration.ofSeconds(5).toNanos(),
                    EvidenceSource.PROCESS, "elapsed " + (healthyEnd - firstStart) / 1_000_000 + "ms");
            sleepUntil(healthyEnd + Duration.ofSeconds(10).toNanos());
            d.complete(true);
            var mixed = d.complete(true).after();
            s.precondition("mixed window 6 samples / 2 failures still CLOSED inside the window",
                    mixed.samples() == 6 && mixed.failures() == 2 && "CLOSED".equals(mixed.state())
                            && System.nanoTime() - firstStart < Duration.ofSeconds(25).toNanos(),
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, mixed.evidenceRef());
            d.reset();
            sleepUntil(healthyEnd + Duration.ofSeconds(31).toNanos());
            var arrival = d.run(d.session());
            s.accept(RejectionJudge.judge(s.id(), s.context(), Observation.captured(
                    new Rejection(arrival.status(), arrival.headers(), JSON.readTree(arrival.body())), EvidenceSource.HTTP,
                    arrival.evidenceRef()), "OPEN", mixed.instanceId()).stream()
                    .filter(f -> f.ruleId().endsWith(".status") || f.ruleId().endsWith(".code") || f.ruleId().endsWith(".state")).toList());
            var residual = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "residual", residual.gate(), residual.evidenceRef())
                    .state("OPEN", "§5.1.2/场景表“异常率超标”").samples(2, "§5.1.2").failures(2, "§5.1.2");
        }
    }

    private static void sleepUntil(long nanoTime) throws InterruptedException {
        while (System.nanoTime() < nanoTime) Thread.sleep(50);
    }

    @Test
    @Covers(value = {"FEAT-112-C7", "FEAT-112-B4"}, layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-C7/B4] OPEN 中样本过期不关门；静默期满转 HALF_OPEN；无流量不制造探针")
    void expiredSamplesDoNotCloseGate(Scenario s) throws Exception {
        // window (20s) shorter than the quiet period (60s): samples drain while the gate is still OPEN
        try (var deployment = GateDeployment.start(DeploymentForm.gate("c7", 2, "0.5", "20s", "60s"));
             var d = deployment.driver(s)) {
            var opened = d.openGate();
            d.reset();
            JsonNode statsBefore = d.stats();
            List<GateDriver.Snapshot> draining = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            GateDriver.Snapshot last;
            do {
                last = d.snapshot();
                draining.add(last);
                if (last.samples() == 0) break;
                Thread.sleep(250);
            } while (System.nanoTime() < deadline);
            s.precondition("all samples expired before the quiet period ended", last.samples() == 0 && last.retryAfterSeconds() > 0,
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, last.evidenceRef());
            String drainRefs = draining.getFirst().evidenceRef() + " .. " + last.evidenceRef();
            Expect.that(s, "FEAT-112-C7", "openWhileDraining", "验收出口4/§5.1.2", Category.INTERNAL_STATE,
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, drainRefs, draining.stream().map(GateDriver.Snapshot::state).toList(),
                    "OPEN at every observation until the window is empty", list -> list.stream().allMatch("OPEN"::equals));
            GateSnapshotChecks.of(s, "FEAT-112-B4", "drained", last.gate(), last.evidenceRef())
                    .samples(0, "验收出口4/§5.1.2").failures(0, "验收出口4/§5.1.2").state("OPEN", "验收出口4");
            var half = d.awaitState("HALF_OPEN", Duration.ofSeconds(75));
            GateSnapshotChecks.of(s, "FEAT-112-C7", "afterQuiet", half.gate(), half.evidenceRef())
                    .state("HALF_OPEN", "§5.1.7/验收出口9").probeInFlight(false, "验收出口4/§5.1.6");
            List<GateDriver.Snapshot> idle = new ArrayList<>();
            long idleUntil = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < idleUntil) {
                idle.add(d.snapshot());
                Thread.sleep(500);
            }
            String idleRefs = idle.getFirst().evidenceRef() + " .. " + idle.getLast().evidenceRef();
            Expect.that(s, "FEAT-112-C7", "staysHalfOpenWithoutTraffic", "验收出口4/§5.1.6", Category.INTERNAL_STATE,
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, idleRefs, idle.stream().map(GateDriver.Snapshot::state).toList(),
                    "HALF_OPEN throughout", list -> list.stream().allMatch("HALF_OPEN"::equals));
            Expect.that(s, "FEAT-112-C7", "noSyntheticProbe", "验收出口4/§5.1.6", Category.INTERNAL_STATE,
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, idleRefs, idle.stream().map(GateDriver.Snapshot::probeInFlight).toList(),
                    "probeInFlight=false throughout", list -> !list.contains(Boolean.TRUE));
            Expect.that(s, "FEAT-112-C7", "sameInstance", "§5.1.8", Category.INTERNAL_STATE, EvidenceSource.DIAGNOSTIC_SNAPSHOT,
                    idleRefs, idle.stream().map(GateDriver.Snapshot::instanceId).distinct().toList(), opened.instanceId(),
                    ids -> ids.equals(List.of(opened.instanceId())));
            JsonNode statsAfter = d.stats();
            Expect.equal(s, "FEAT-112-C7", "noModelCall", "验收出口4/§5.2", Category.INTERNAL_EXECUTION_OBSERVABILITY,
                    EvidenceSource.STUB, "stub stats", List.of(GateDriver.count(statsAfter, "generation"), GateDriver.count(statsAfter, "totalRequests")),
                    List.of(GateDriver.count(statsBefore, "generation"), GateDriver.count(statsBefore, "totalRequests")));
        }
    }
}
