package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/** Transport events are not task terminals (feature capability "传输事件不独立记账", §5.1.5, acceptance exit 6). */
class Feat112TransportTest extends DeployedGateCase {
    private static final String CLAUSE = "能力表“传输事件不独立记账”/验收出口6";

    @Override
    protected DeploymentForm form() {
        return DeploymentForm.standard();
    }

    @Test
    @Covers(value = "FEAT-112-J1", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-J1] 原始 run-stream 断开后任务继续成功，只计分母")
    void disconnectedStreamTaskCompletes(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var before = d.prepareEmptyClosed();
            d.delayed();
            String session = d.session();
            var sse = d.sut().disconnectRunStreamAfterStart(Map.of("sessionId", session, "input", "Reply with a short greeting"));
            s.activation("original run-stream served a start frame before the client disconnected it", sse.servedWithStart(),
                    EvidenceSource.SSE, sse.evidenceRef());
            String task = d.awaitActive(session).path("id").asText();
            String terminal = awaitTerminal(d, session, task);
            Expect.equal(s, s.id(), "taskContinues", CLAUSE, Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP, "task " + task,
                    terminal, "completed");
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "after", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, CLAUSE).failures(0, CLAUSE);
        }
    }

    @Test
    @Covers(value = "FEAT-112-J2", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-J2] 原始 run-stream 断开后任务最终系统失败，计一次分子分母")
    void disconnectedStreamFailureCountedOnce(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var before = d.prepareEmptyClosed();
            d.delayedFailure();
            String session = d.session();
            var sse = d.sut().disconnectRunStreamAfterStart(Map.of("sessionId", session, "input", "Reply with a short greeting"));
            s.activation("original run-stream served a start frame before the client disconnected it", sse.servedWithStart(),
                    EvidenceSource.SSE, sse.evidenceRef());
            String task = d.awaitActive(session).path("id").asText();
            String terminal = awaitTerminal(d, session, task);
            s.activation("task finally failed through the injected model failure",
                    "failed".equals(terminal) && GateDriver.count(d.stats(), "errorResponses") > 0, EvidenceSource.HTTP, "task " + task);
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "after", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, CLAUSE).failures(1, CLAUSE);
            Expect.equal(s, s.id(), "singleTask", "§5.1.4", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP, "session " + session,
                    d.tasks(session).size(), 1);
        }
    }

    @Test
    @Covers(value = "FEAT-112-J3", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-J3] /stream 多次重连不新增样本、不重复执行")
    void replayReconnectsAddNothing(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var before = d.prepareEmptyClosed();
            d.delayed();
            String session = d.session();
            var future = d.async(session, new CountDownLatch(0));
            String task = d.awaitActive(session).path("id").asText();
            long calls = GateDriver.count(d.stats(), "totalRequests");
            for (int i = 0; i < 3; i++) {
                var replay = d.sut().disconnectAfterStart("/api/agents/stream/" + task + "?sessionId=" + session);
                String sse = replay.evidenceRef();
                s.activation("replay " + i + " served and overlapped the live execution", replay.servedWithStart() && !future.isDone(),
                        EvidenceSource.SSE, sse);
                var during = d.snapshot();
                Expect.equal(s, s.id(), "noModelCallOnReplay" + i, "§5.1.5", Category.INTERNAL_EXECUTION_OBSERVABILITY,
                        EvidenceSource.STUB, sse, GateDriver.count(d.stats(), "totalRequests"), calls);
                GateSnapshotChecks.of(s, s.id(), "duringReplay" + i, during.gate(), during.evidenceRef()).samples(0, CLAUSE);
                Expect.equal(s, s.id(), "singleTaskOnReplay" + i, "§5.1.5", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                        "session " + session, d.tasks(session).size(), 1);
            }
            var response = future.get(60, TimeUnit.SECONDS);
            Expect.equal(s, s.id(), "taskCompletes", "§5.1.5", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    response.evidenceRef(), response.status() + "/" + response.json().path("status").asText(), "200/completed");
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "after", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, CLAUSE).failures(0, CLAUSE);
            Expect.equal(s, s.id(), "noDuplicateExecution", "§5.1.5", Category.INTERNAL_EXECUTION_OBSERVABILITY,
                    EvidenceSource.STUB, "stub stats", GateDriver.count(d.stats(), "totalRequests"), calls);
            var finishedReplay = d.sut().disconnectAfterStart("/api/agents/stream/" + task + "?sessionId=" + session);
            var afterReplay = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "afterFinishedReplay", afterReplay.gate(), afterReplay.evidenceRef())
                    .samples(1, CLAUSE).failures(0, CLAUSE);
            Expect.equal(s, s.id(), "noModelCallOnFinishedReplay", "§5.1.5", Category.INTERNAL_EXECUTION_OBSERVABILITY,
                    EvidenceSource.STUB, finishedReplay.evidenceRef(), GateDriver.count(d.stats(), "totalRequests"), calls);
        }
    }

    private static String awaitTerminal(GateDriver d, String session, String task) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        String status;
        do {
            status = d.status(session, task).path("status").asText();
            if (!"running".equals(status)) return status;
            Thread.sleep(25);
        } while (System.nanoTime() < until);
        return status;
    }
}
