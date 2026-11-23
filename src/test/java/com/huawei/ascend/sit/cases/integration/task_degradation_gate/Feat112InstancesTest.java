package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.Feat112Integration;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.GateDeployment;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.RejectionJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.RejectionJudge.Rejection;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/** Instance-local state across processes and restarts (feature §5.1.7, §5.1.8, acceptance exits 17 and 20). */
@Feat112Integration
class Feat112InstancesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static DeploymentForm form(String name, String quiet) {
        return DeploymentForm.gate(name, 2, "0.5", "10m", quiet);
    }

    @Test
    @Covers(value = {"FEAT-112-H1", "FEAT-112-H2", "FEAT-112-H3"}, layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-H1/H2/H3] 两个进程各自维护窗口、状态与实例标识")
    void independentInstances(Scenario s) throws Exception {
        try (var a = GateDeployment.start(form("h-a", "10s"), GateDeployment.AGENT);
             var b = GateDeployment.start(form("h-b", "120s"), GateDeployment.PEER_AGENT);
             var da = a.driver(s); var db = b.driver(s)) {
            var emptyB = db.prepareEmptyClosed();
            var openA = da.openGate();
            s.precondition("two distinct instances", !openA.instanceId().equals(emptyB.instanceId()),
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, openA.evidenceRef() + " ; " + emptyB.evidenceRef());
            var untouchedB = db.snapshot();
            GateSnapshotChecks.of(s, "FEAT-112-H1", "bWhileAOpen", untouchedB.gate(), untouchedB.evidenceRef())
                    .state("CLOSED", "验收出口17/§5.1.8").samples(0, "验收出口17").instance(emptyB.instanceId(), "§5.1.8");
            var refused = da.run(da.session());
            s.accept("FEAT-112-H2", RejectionJudge.judge("FEAT-112-H2", s.context(), Observation.captured(
                    new Rejection(refused.status(), refused.headers(), JSON.readTree(refused.body())), EvidenceSource.HTTP,
                    refused.evidenceRef()), "OPEN", openA.instanceId()).stream()
                    .filter(f -> f.ruleId().endsWith(".status") || f.ruleId().endsWith(".instanceId")).toList());
            var diagnosticA = da.snapshot();
            GateSnapshotChecks.of(s, "FEAT-112-H2", "aDiagnostic", diagnosticA.gate(), diagnosticA.evidenceRef())
                    .instance(openA.instanceId(), "验收出口17/§5.1.8");
            var openB = db.openGate();
            da.establishState("HALF_OPEN", Duration.ofSeconds(30));
            da.reset();
            var recovered = da.complete(false);
            s.precondition("instance A recovered to CLOSED through a real probe", "CLOSED".equals(recovered.after().state()),
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, recovered.after().evidenceRef());
            var stillB = db.snapshot();
            GateSnapshotChecks.of(s, "FEAT-112-H3", "bAfterARecovery", stillB.gate(), stillB.evidenceRef())
                    .state("OPEN", "验收出口17/§5.1.8").instance(openB.instanceId(), "§5.1.8")
                    .samples(openB.samples(), "验收出口17").retryAfterPositive("§5.1.8");
        }
    }

    @Test
    @Covers(value = "FEAT-112-I1", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-I1] OPEN 中重启后新进程以 CLOSED 空窗口初始化")
    void restartFromOpenInitialisesNewProcess(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(form("i1", "60s"))) {
            String oldInstance;
            try (var d = deployment.driver(s)) {
                oldInstance = d.openGate().instanceId();
            }
            long oldPid = deployment.pid();
            deployment.restart();
            s.activation("owned process actually restarted", deployment.pid() != oldPid, EvidenceSource.PROCESS,
                    "pid " + oldPid + " -> " + deployment.pid());
            try (var fresh = deployment.driver(s)) {
                var initial = fresh.snapshot();
                GateSnapshotChecks.of(s, s.id(), "newProcess", initial.gate(), initial.evidenceRef())
                        .newInstance(oldInstance, "验收出口20/§5.1.7").state("CLOSED", "验收出口20/§5.1.7")
                        .samples(0, "§5.1.7").failures(0, "§5.1.7").probeInFlight(false, "§5.1.7");
                // the startup record a monitor correlates with the restart (formal carrier per the merged DA L2)
                String newInstance = initial.instanceId();
                java.util.List<String> wired = java.nio.file.Files.readAllLines(deployment.processLog()).stream()
                        .filter(line -> line.contains("da degradation gate wired")).toList();
                Expect.that(s, s.id(), "startupRecordNamesNewInstance", "验收出口20/§5.1.7", Category.EXTERNAL_CONTRACT,
                        EvidenceSource.LOG, deployment.processLog().toString(), wired,
                        "a startup record of the new process carrying enabled=true, instanceId=" + newInstance
                                + " and the delivery-default field",
                        lines -> lines.stream().anyMatch(line -> line.contains("instanceId=" + newInstance)
                                && line.contains("enabled=true") && line.contains("delivery default applied=")));
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-H4", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-H4] 两个实例的半开探针名额互不影响")
    void halfOpenSlotsAreIndependent(Scenario s) throws Exception {
        try (var a = GateDeployment.start(form("h4-a", "10s"), GateDeployment.AGENT);
             var b = GateDeployment.start(form("h4-b", "10s"), GateDeployment.PEER_AGENT);
             var da = a.driver(s); var db = b.driver(s)) {
            da.openGate();
            db.openGate();
            var halfA = da.establishState("HALF_OPEN", Duration.ofSeconds(60));
            var halfB = db.establishState("HALF_OPEN", Duration.ofSeconds(60));
            s.precondition("both instances HALF_OPEN with free slots", !halfA.probeInFlight() && !halfB.probeInFlight(),
                    EvidenceSource.DIAGNOSTIC_SNAPSHOT, halfA.evidenceRef() + " ; " + halfB.evidenceRef());
            da.delayed();
            var probeA = da.async(da.session(), new CountDownLatch(0));
            var occupiedA = awaitProbe(da);
            s.activation("a slow task holds A's probe slot", occupiedA.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, occupiedA.evidenceRef());
            var bWhileA = db.snapshot();
            GateSnapshotChecks.of(s, s.id(), "bWhileAOccupied", bWhileA.gate(), bWhileA.evidenceRef())
                    .state("HALF_OPEN", "验收出口17").probeInFlight(false, "验收出口17");
            db.delayed();
            var probeB = db.async(db.session(), new CountDownLatch(0));
            var occupiedB = awaitProbe(db);
            Expect.equal(s, s.id(), "bAdmitsItsOwnProbe", "验收出口17", Category.EXTERNAL_CONTRACT, EvidenceSource.DIAGNOSTIC_SNAPSHOT,
                    occupiedB.evidenceRef(), occupiedB.probeInFlight(), true);
            var refused = da.run(da.session());
            s.accept(RejectionJudge.judge(s.id(), s.context(), Observation.captured(
                    new Rejection(refused.status(), refused.headers(), JSON.readTree(refused.body())), EvidenceSource.HTTP,
                    refused.evidenceRef()), "HALF_OPEN", occupiedA.instanceId()).stream()
                    .filter(f -> f.ruleId().endsWith(".status") || f.ruleId().endsWith(".state") || f.ruleId().endsWith(".instanceId")).toList());
            probeA.get(60, TimeUnit.SECONDS);
            probeB.get(60, TimeUnit.SECONDS);
        }
    }

    private static com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver.Snapshot awaitProbe(
            com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver d) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var snapshot = d.snapshot();
        while (!snapshot.probeInFlight() && System.nanoTime() < until) {
            Thread.sleep(50);
            snapshot = d.snapshot();
        }
        return snapshot;
    }

    @Test
    @Covers(value = "FEAT-112-I2", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-I2] 重启丢弃在途半开探针，不持久化旧窗口")
    void restartDiscardsLiveProbe(Scenario s) throws Exception {
        try (var deployment = GateDeployment.start(form("i2", "1s"))) {
            String oldInstance;
            long oldPid;
            try (var d = deployment.driver(s)) {
                d.openGate();
                d.establishState("HALF_OPEN", Duration.ofSeconds(10));
                d.reset();
                d.delayed();
                String session = d.session();
                var future = d.async(session, new CountDownLatch(0));
                d.awaitActive(session);
                var old = d.snapshot();
                s.activation("a live probe holds the slot before restart", "HALF_OPEN".equals(old.state()) && old.probeInFlight()
                        && !future.isDone(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, old.evidenceRef());
                oldInstance = old.instanceId();
                oldPid = deployment.pid();
                deployment.restart();
                try {
                    future.get(65, TimeUnit.SECONDS);
                } catch (ExecutionException expectedDisconnect) {
                    // the request to the stopped process ends with a transport error
                }
                d.acknowledgeProcessTermination(future);
                d.processRestarted();
            }
            s.activation("owned process actually restarted", deployment.pid() != oldPid, EvidenceSource.PROCESS,
                    "pid " + oldPid + " -> " + deployment.pid());
            try (var fresh = deployment.driver(s)) {
                var initial = fresh.snapshot();
                GateSnapshotChecks.of(s, s.id(), "newProcess", initial.gate(), initial.evidenceRef())
                        .newInstance(oldInstance, "§5.1.7").state("CLOSED", "§5.1.7").probeInFlight(false, "§5.1.7")
                        .samples(0, "§5.1.7").failures(0, "§5.1.7");
            }
        }
    }
}
