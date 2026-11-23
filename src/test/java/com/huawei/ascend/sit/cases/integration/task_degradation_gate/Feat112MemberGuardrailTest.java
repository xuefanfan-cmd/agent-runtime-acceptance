package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.Feat112Integration;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.GateDeployment;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * Guardrail denials inside a workflow member (feature §6: a denial seen during the task must not rewrite the
 * classification). The delivered actrule allows every member tool, so the deployment adds a scenario overlay that
 * puts {@code read_file} on confirmation; a member has no confirmation channel and turns that into a denial
 * (fail-closed). The member is scripted to call {@code read_file}; every attempt is denied, so no tool output ever
 * reaches it. How the member then ends (iteration budget or a synthesis turn) is carrier-internal and not asserted;
 * §6 only requires the denial not to rewrite the main task's classification (main completes, sample+1, failure+0).
 */
@Feat112Integration
class Feat112MemberGuardrailTest {
    private static final int MEMBER_ITERATIONS = 2;

    @Test
    @Covers(value = "FEAT-112-E16", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E16] 成员调用因护栏拒绝耗尽、主任务正常完成：只计分母")
    void memberExhaustedByDenialsThenTaskCompletes(Scenario s) throws Exception {
        DeploymentForm form = DeploymentForm.standard().named("e54")
                .with("deepanalyze.workflow.member-max-iterations", Integer.toString(MEMBER_ITERATIONS))
                .withScenarioActrule("enabled: true\ntools:\n  read_file: ask\n");
        try (var deployment = GateDeployment.start(form); var d = deployment.driver(s)) {
            var before = d.prepareEmptyClosed();
            List<Map<String, Object>> script = new ArrayList<>();
            script.add(GateDriver.toolCallResponse("call-feat112-main", "workflow_run", Map.of("mode", "single",
                    "goal", "Summarize the readme", "agents", List.of(Map.of("id", "m1", "role", "explore",
                            "task", "Read README.md and summarize it")))));
            for (int i = 1; i <= MEMBER_ITERATIONS; i++) {
                script.add(GateDriver.toolCallResponse("call-feat112-member-" + i, "read_file", Map.of("path", "README.md")));
            }
            d.script(script, "workflow member scripted to call only read_file");
            JsonNode statsBefore = d.stats();
            Path log = deployment.processLog();
            long logOffset = Files.size(log);
            String session = d.session();
            var stream = d.sut().post("/api/agents/run-stream", Map.of("sessionId", session,
                    "input", "Summarize the readme with a workflow"));
            String terminal = terminalOf(d, session);
            JsonNode statsAfter = d.stats();
            String logTail = tail(log, logOffset);
            String body = stream.body();
            int failure = body.indexOf("Ability execution error");
            s.activation("workflow member was built and ran" + (failure < 0 ? ""
                            : " (workflow_run failed: " + body.substring(failure, Math.min(body.length(), failure + 220)) + ")"),
                    body.contains("workflow_complete") && failure < 0, EvidenceSource.SSE, stream.evidenceRef());
            long served = GateDriver.count(statsAfter, "totalRequests") - GateDriver.count(statsBefore, "totalRequests");
            // The carrier drives a model call per member turn plus main/member synthesis calls; the exact count is
            // carrier-internal (a member synthesis call was added after the scripted tool turns). Assert the scripted
            // member turns were all served with nothing left hanging, not a fixed total.
            s.activation("workflow drove the model through the scripted member turns with nothing pending "
                            + "(served=" + served + ", pending=" + GateDriver.count(statsAfter, "pendingResponses") + ")",
                    served >= MEMBER_ITERATIONS + 2 && GateDriver.count(statsAfter, "pendingResponses") == 0,
                    EvidenceSource.STUB, "stub stats");
            boolean deniedInStream = body.contains("PERMISSION_DENIED");
            boolean deniedInLog = logTail.contains("PERMISSION_DENIED");
            s.activation("member tool attempts were denied (stream=" + deniedInStream + ", log=" + deniedInLog + ")",
                    deniedInStream || deniedInLog, deniedInStream ? EvidenceSource.SSE : EvidenceSource.LOG,
                    deniedInStream ? stream.evidenceRef() : log + " from byte " + logOffset);
            // The member never gets a successful read_file: every attempt is denied (fail-closed), so no tool output
            // ever reaches it. Its own end mechanism (iteration budget vs. a synthesis turn) is carrier-internal and
            // not asserted; §6 only requires the denial not to rewrite the main task's classification.
            long denials = (body + logTail).split("PERMISSION_DENIED", -1).length - 1;
            s.activation("every member read_file attempt was denied, none succeeded (denials=" + denials + ")",
                    denials >= MEMBER_ITERATIONS && !body.contains("\"toolName\":\"read_file\",\"output\":\"{"),
                    EvidenceSource.SSE, stream.evidenceRef());
            s.activation("task reached terminal completed (was " + terminal + ")", "completed".equals(terminal),
                    EvidenceSource.HTTP, "session " + session);
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "after", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, "§5.1.3/§6/验收出口5")
                    .failures(0, "§5.1.3/§6/验收出口5");
        }
    }

    private static String tail(Path log, long offset) throws Exception {
        try (var file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            if (length <= offset) return "";
            byte[] bytes = new byte[(int) Math.min(length - offset, 8 * 1024 * 1024)];
            file.seek(offset);
            file.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String terminalOf(GateDriver d, String session) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status = "no task listed";
        do {
            JsonNode tasks = d.tasks(session);
            if (tasks.size() == 1) {
                status = tasks.get(0).path("status").asText();
                if (!"running".equals(status)) return status;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < until);
        return status;
    }
}
