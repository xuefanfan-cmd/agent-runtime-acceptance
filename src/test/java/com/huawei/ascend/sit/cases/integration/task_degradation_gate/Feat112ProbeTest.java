package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.Expect;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.RejectionJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.RejectionJudge.Rejection;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/** Half-open probe: single admission and the outcomes that close or release it (feature §5.1.6). */
class Feat112ProbeTest extends DeployedGateCase {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected DeploymentForm form() {
        return DeploymentForm.standard();
    }

    private static GateDriver.Snapshot halfOpenWithFreeSlot(Scenario s, GateDriver d) throws Exception {
        d.openGate();
        var half = d.establishState("HALF_OPEN", Duration.ofSeconds(90));
        s.precondition("probe slot free", !half.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, half.evidenceRef());
        d.reset();
        return half;
    }

    @Test
    @Covers(value = {"FEAT-112-C2", "FEAT-112-D4", "FEAT-112-D5", "FEAT-112-E1.1", "FEAT-112-E1.2"}, layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-C2/D4/D5/E1.1/E1.2] 半开时重叠请求恰一个真实受理，其余 503 且省略恢复时间")
    void overlappingRequestsAdmitExactlyOneProbe(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            var half = halfOpenWithFreeSlot(s, d);
            d.delayed();
            CountDownLatch start = new CountDownLatch(1);
            List<String> sessions = new ArrayList<>();
            List<Future<DaEvidenceClient.Exchange>> requests = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                sessions.add(d.session());
                requests.add(d.async(sessions.get(i), start));
            }
            start.countDown();
            long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (requests.stream().filter(Future::isDone).count() < 4 && System.nanoTime() < until) Thread.sleep(25);
            long answered = requests.stream().filter(Future::isDone).count();
            s.activation("requests overlapped: at least one still in flight while the others were answered", answered < 5,
                    EvidenceSource.HTTP, "5 concurrent sessions " + sessions);
            var occupied = d.snapshot();
            long modelUntil = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (GateDriver.count(d.stats(), "activeRequests") < 1 && System.nanoTime() < modelUntil) Thread.sleep(25);
            long active = GateDriver.count(d.stats(), "activeRequests");
            Expect.equal(s, "FEAT-112-E1.1", "answeredWhileWinnerInFlight", "验收出口10/§5.1.6", Category.EXTERNAL_CONTRACT,
                    EvidenceSource.HTTP, "5 concurrent sessions", answered, 4L);
            Expect.equal(s, "FEAT-112-E1.1", "realModelCalls", "§5.1.6/验收出口10", Category.INTERNAL_EXECUTION_OBSERVABILITY,
                    EvidenceSource.STUB, "stub stats", active, 1L);
            for (String id : List.of("FEAT-112-C2", "FEAT-112-E1.1")) {
                GateSnapshotChecks.of(s, id, "occupied", occupied.gate(), occupied.evidenceRef())
                        .state("HALF_OPEN", "§5.1.6").probeInFlight(true, "§5.1.6/§3.2").instance(half.instanceId(), "§5.1.8");
            }
            int admitted = 0;
            for (int i = 0; i < 5; i++) {
                var future = requests.get(i);
                JsonNode tasks = d.tasks(sessions.get(i));
                if (!future.isDone()) {
                    admitted++;
                    Expect.equal(s, "FEAT-112-C2", "admittedTaskListed", "§5.1.1/验收出口10", Category.EXTERNAL_CONTRACT,
                            EvidenceSource.HTTP, "session " + sessions.get(i), tasks.size(), 1);
                    continue;
                }
                var response = future.get();
                var refusal = Observation.captured(new Rejection(response.status(), response.headers(), parse(response.body())),
                        EvidenceSource.HTTP, response.evidenceRef());
                s.accept("FEAT-112-E1.2", RejectionJudge.judge("FEAT-112-E1.2", s.context(), refusal, "HALF_OPEN", half.instanceId()));
                s.accept("FEAT-112-D4", RejectionJudge.judge("FEAT-112-D4", s.context(), refusal, "HALF_OPEN", half.instanceId())
                        .stream().filter(f -> f.ruleId().endsWith(".retry")).toList());
                s.accept("FEAT-112-D5", RejectionJudge.judge("FEAT-112-D5", s.context(), refusal, "HALF_OPEN", half.instanceId())
                        .stream().filter(f -> f.ruleId().endsWith(".state") || f.ruleId().endsWith(".instanceId")).toList());
                Expect.equal(s, "FEAT-112-E1.2", "refusedNotListed-" + i, "§3.1/能力表“不制造假任务”", Category.EXTERNAL_CONTRACT,
                        EvidenceSource.HTTP, "session " + sessions.get(i), tasks.size(), 0);
            }
            Expect.equal(s, "FEAT-112-E1.1", "admittedCount", "验收出口10", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    "5 concurrent sessions", admitted, 1);
            for (var request : requests) {
                var response = request.get(60, TimeUnit.SECONDS);
                if (response.status() != 503) {
                    Expect.equal(s, "FEAT-112-C2", "probeResult", "§5.1.6", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                            response.evidenceRef(), response.status() + "/" + response.json().path("status").asText(), "200/completed");
                }
            }
        }
    }

    @Test
    @Covers(value = "FEAT-112-E3", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E3] 半开探针以预期业务失败结束可证明健康并关门")
    void businessFailureProbeCloses(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            halfOpenWithFreeSlot(s, d);
            String marker = "feat112-business-outcome-" + UUID.randomUUID();
            d.tool("business_failure", Map.of("output", marker));
            var probe = d.runThroughStub(false);
            s.activation("probe ended through the business-outcome rail (failed, error is the exact output)",
                    probe.status() == 500 && "failed".equals(probe.json().path("status").asText())
                            && marker.equals(probe.json().path("error").asText()), EvidenceSource.HTTP, probe.evidenceRef());
            var closed = d.awaitState("CLOSED", Duration.ofSeconds(10));
            GateSnapshotChecks.of(s, s.id(), "afterProbe", closed.gate(), closed.evidenceRef())
                    .state("CLOSED", "§5.1.6/验收出口12").samples(0, "验收出口12").failures(0, "验收出口12")
                    .probeInFlight(false, "§5.1.6");
        }
    }

    @Test
    @Covers(value = "FEAT-112-E6", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E6] 半开探针到达真实 awaiting_answer 时关门且不记样本")
    void awaitingAnswerProbeClosesWithoutSample(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            halfOpenWithFreeSlot(s, d);
            d.tool("ask_user", Map.of("questions", List.of(Map.of("question", "Choose a harmless greeting?"))));
            String session = d.session();
            d.asyncStream(session, "Ask one clarification question");
            var waiting = d.awaitQuestion(session);
            var closed = d.awaitState("CLOSED", Duration.ofSeconds(10));
            GateSnapshotChecks.of(s, s.id(), "afterQuestion", closed.gate(), closed.evidenceRef())
                    .state("CLOSED", "验收出口13/§5.1.6").probeInFlight(false, "验收出口13")
                    .samples(0, "验收出口13").failures(0, "验收出口13");
            Expect.equal(s, s.id(), "taskStillResumable", "验收出口13", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    "session " + session, waiting.path("resumable").asBoolean(false), true);
        }
    }

    @Test
    @Covers(value = "FEAT-112-E10", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E10] 探针待答关门后，回答续跑与 inject 只让该逻辑任务记一次")
    void answeredContinuationCountsOnce(Scenario s) throws Exception {
        try (var d = deployment().driver(s)) {
            halfOpenWithFreeSlot(s, d);
            d.tool("ask_user", Map.of("questions", List.of(Map.of("question", "Choose a harmless greeting?"))));
            String session = d.session();
            d.asyncStream(session, "Ask one clarification question");
            var waiting = d.awaitQuestion(session);
            String taskId = waiting.path("id").asText();
            var closed = d.awaitState("CLOSED", Duration.ofSeconds(10));
            s.precondition("probe awaiting closed the gate without a sample (E6)",
                    "CLOSED".equals(closed.state()) && closed.samples() == 0, EvidenceSource.DIAGNOSTIC_SNAPSHOT, closed.evidenceRef());
            d.delayedFailure();
            var answer = d.sut().post("/api/agents/answer/" + taskId + "?sessionId=" + session, Map.of("answer", "Hello"));
            s.activation("answer accepted and the continuation started", answer.status() == 200, EvidenceSource.HTTP, answer.evidenceRef());
            long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (GateDriver.count(d.stats(), "activeRequests") < 1 && System.nanoTime() < until) Thread.sleep(25);
            s.activation("the continuation's model call is in flight", GateDriver.count(d.stats(), "activeRequests") >= 1,
                    EvidenceSource.STUB, "stub stats");
            var inject = d.sut().post("/api/agents/inject/" + taskId + "?sessionId=" + session,
                    Map.of("message", "Keep the greeting short"));
            s.activation("inject accepted during the continuation", inject.status() == 200, EvidenceSource.HTTP, inject.evidenceRef());
            String terminal = "running";
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < deadline) {
                terminal = d.status(session, taskId).path("status").asText();
                if (!"running".equals(terminal)) break;
                Thread.sleep(50);
            }
            s.activation("the continued task finally failed through the injected model failure", "failed".equals(terminal),
                    EvidenceSource.HTTP, "task " + taskId);
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "afterContinuation", after.gate(), after.evidenceRef())
                    .samples(1, "验收出口13/能力表“每任务只记一次”").failures(1, "验收出口13/能力表“每任务只记一次”");
            Expect.equal(s, s.id(), "singleTask", "能力表“每任务只记一次”", Category.EXTERNAL_CONTRACT, EvidenceSource.HTTP,
                    "session " + session, d.tasks(session).size(), 1);
        }
    }

    @Test
    @Covers(value = "FEAT-112-E7", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E7] 半开探针取消落定后释放名额并保持 HALF_OPEN")
    void probeCancellationReleasesSlot(Scenario s) throws Exception {
        cancel(s, true);
    }

    @Test
    @Covers(value = "FEAT-112-K20", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-K20] 真实执行后取消不计分子分母")
    void cancelledTaskCreatesNoSample(Scenario s) throws Exception {
        cancel(s, false);
    }

    private void cancel(Scenario s, boolean probe) throws Exception {
        try (var d = deployment().driver(s)) {
            if (probe) halfOpenWithFreeSlot(s, d);
            else d.prepareEmptyClosed();
            d.delayed();
            var before = d.snapshot();
            String session = d.session();
            var future = d.async(session, new CountDownLatch(0));
            String taskId = d.awaitActive(session).path("id").asText();
            if (probe) {
                var occupied = d.snapshot();
                s.activation("the running task holds the probe slot", occupied.probeInFlight(),
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, occupied.evidenceRef());
            }
            // counts compared from the instant of cancellation: older samples may expire from the window meanwhile,
            // which only lowers the counts; a cancelled task must not raise them
            var atCancel = d.snapshot();
            d.cancel(session, taskId);
            future.get(60, TimeUnit.SECONDS);
            var after = d.snapshot();
            String clause = probe ? "验收出口14/§5.1.6" : "§5.1.3/验收出口5";
            GateSnapshotChecks.of(s, s.id(), "afterCancel", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").probeInFlight(false, clause)
                    .state(probe ? "HALF_OPEN" : "CLOSED", clause);
            Expect.that(s, s.id(), "cancelAddsNoSample", clause, Category.EXTERNAL_CONTRACT, EvidenceSource.DIAGNOSTIC_SNAPSHOT,
                    atCancel.evidenceRef() + " -> " + after.evidenceRef(), after.samples() + "/" + after.failures(),
                    "samples/failures not above " + atCancel.samples() + "/" + atCancel.failures() + " (window expiry may only lower them)",
                    counts -> after.samples() <= atCancel.samples() && after.failures() <= atCancel.failures());
            if (probe) {
                // the released slot is usable: the next real task becomes the probe
                String nextSession = d.session();
                var next = d.async(nextSession, new CountDownLatch(0));
                String nextTask = d.awaitActive(nextSession).path("id").asText();
                var occupied = d.snapshot();
                Expect.equal(s, s.id(), "nextTaskBecomesProbe", clause, Category.EXTERNAL_CONTRACT,
                        EvidenceSource.DIAGNOSTIC_SNAPSHOT, occupied.evidenceRef(), occupied.probeInFlight(), true);
                d.cancel(nextSession, nextTask);
                next.get(60, TimeUnit.SECONDS);
            }
        }
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception notJson) {
            return null;
        }
    }
}
