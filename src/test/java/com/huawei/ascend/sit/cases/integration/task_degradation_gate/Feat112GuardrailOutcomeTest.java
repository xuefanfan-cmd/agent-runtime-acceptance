package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.Feat112Integration;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.GateDeployment;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DenyingHook;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * How a guardrail denial in the task relates to its final failure (feature §5.1.3, §6): a failure caused by the
 * guardrail only counts in the denominator (E18), while an unrelated failure after a denial still counts in the
 * numerator (E17). Each scenario owns its deployment.
 */
@Feat112Integration
class Feat112GuardrailOutcomeTest {
    private static final int MAIN_ITERATIONS = 2;
    private static final int MEMBER_ITERATIONS = 2;

    /**
     * The main chain records causal guardrail evidence only where a PreToolUse hook denies a call (the permission
     * host's own refusals, such as a write outside the workspace, are not causal evidence). A per-request hook denies
     * every {@code write_file}; the model asks for it on every turn until the fixed iteration budget runs out.
     */
    @Test
    @Covers(value = "FEAT-112-E18", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E18] 护栏正常拦截导致任务最终失败：只计分母")
    void guardrailExhaustionCountsDenominatorOnly(Scenario s) throws Exception {
        DeploymentForm form = DeploymentForm.standard().named("e18")
                .with("deepanalyze.model.max-iterations", Integer.toString(MAIN_ITERATIONS))
                .with("deepanalyze.hooks.http-allowed-hosts", "127.0.0.1");
        try (var hook = new DenyingHook(); var deployment = GateDeployment.start(form); var d = deployment.driver(s)) {
            var before = d.prepareEmptyClosed();
            Path outside = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().getRoot()
                    .resolve("feat112-e18-" + UUID.randomUUID()).resolve("denied.txt");
            List<Map<String, Object>> script = new ArrayList<>();
            for (int i = 1; i <= MAIN_ITERATIONS + 2; i++) {
                script.add(GateDriver.toolCallResponse("call-feat112-e18-" + i, "write_file",
                        Map.of("path", outside.toString(), "content", "feat112")));
            }
            d.script(script, "every model turn asks for write_file");
            JsonNode statsBefore = d.stats();
            String session = d.session();
            Map<String, Object> denyingHook = Map.of("id", "feat112-e18-deny", "event", "PreToolUse", "type", "http",
                    "matcher", "write_file", "enabled", true, "config", Map.of("url", hook.url()));
            var run = d.sut().post("/api/agents/run", Map.of("sessionId", session, "input", "Write a note",
                    "runtimeConfig", Map.of("version", 1, "tenantId", "default-user", "sessionId", session,
                            "hooks", List.of(denyingHook))));
            String terminal = terminalOf(d, session).path("status").asText();
            JsonNode statsAfter = d.stats();
            long served = GateDriver.count(statsAfter, "totalRequests") - GateDriver.count(statsBefore, "totalRequests");
            s.activation("the task failed (HTTP " + run.status() + ", terminal " + terminal + ")", "failed".equals(terminal),
                    EvidenceSource.HTTP, run.evidenceRef());
            s.activation("every model turn's tool call reached the hook and was denied (model turns=" + served
                            + ", hook denials=" + hook.calls() + ", iteration budget=" + MAIN_ITERATIONS + ")",
                    served >= 1 && served <= MAIN_ITERATIONS && hook.calls() == served, EvidenceSource.STUB, "stub stats and hook counter");
            s.activation("the denied tool never ran", !Files.exists(outside), EvidenceSource.PROCESS, outside.toString());
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "after", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, "§5.1.3/§6/验收出口5").failures(0, "§5.1.3/§6/验收出口5");
        }
    }

    /**
     * A workflow member is exhausted by denials (as in E16), then the main task fails for an unrelated reason with no
     * code, no category and no core error result (as in E15: the checkpoint store is gone when the answer resumes it).
     */
    @Test
    @Covers(value = "FEAT-112-E17", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E17] 成员调用因护栏拒绝耗尽、主任务另因无关原因失败：按系统异常计分子")
    void memberDenialThenUnrelatedFailureCountsNumerator(Scenario s) throws Exception {
        DeploymentForm form = DeploymentForm.standard().named("e17")
                .with("deepanalyze.workflow.member-max-iterations", Integer.toString(MEMBER_ITERATIONS))
                .withScenarioActrule("enabled: true\ntools:\n  read_file: ask\n")
                .withRedisCheckpointer();
        try (var deployment = GateDeployment.start(form); var d = deployment.driver(s)) {
            var before = d.prepareEmptyClosed();
            List<Map<String, Object>> script = new ArrayList<>();
            script.add(GateDriver.toolCallResponse("call-feat112-main", "workflow_run", Map.of("mode", "single",
                    "goal", "Summarize the readme", "agents", List.of(Map.of("id", "m1", "role", "explore",
                            "task", "Read README.md and summarize it")))));
            for (int i = 1; i <= MEMBER_ITERATIONS; i++) {
                script.add(GateDriver.toolCallResponse("call-feat112-member-" + i, "read_file", Map.of("path", "README.md")));
            }
            // the member's synthesis turn (served by the stub's healthy default answer), then the main agent asks a question
            script.add(Map.of("code", 200));
            script.add(GateDriver.toolCallResponse("call-feat112-ask", "ask_user",
                    Map.of("questions", List.of(Map.of("question", "Choose a harmless greeting?")))));
            d.script(script, "member denied until exhausted, then a real question from the main agent");
            String session = d.session();
            var stream = d.sut().postSseUntil("/api/agents/run-stream", Map.of("sessionId", session,
                    "input", "Summarize the readme with a workflow"), "ask_user", Duration.ofSeconds(180));
            String body = stream.body();
            long denials = body.split("PERMISSION_DENIED", -1).length - 1;
            s.activation("the workflow member ran and every read_file attempt was denied (denials=" + denials + ")",
                    body.contains("workflow_complete") && denials >= MEMBER_ITERATIONS
                            && !body.contains("\"toolName\":\"read_file\",\"output\":\"{"), EvidenceSource.SSE, stream.evidenceRef());
            JsonNode parked = awaitParked(d, session);
            s.activation("the main task is parked on awaiting_answer (checkpoint saved to the store)",
                    "awaiting_answer".equals(parked.path("resumeState").asText()), EvidenceSource.HTTP, "session " + session);
            String taskId = parked.path("id").asText();
            deployment.redis().stop();
            s.activation("checkpoint store stopped while the DA keeps running", deployment.redis().stopped(),
                    EvidenceSource.PROCESS, deployment.redis().describe());
            JsonNode statsBefore = d.stats();
            var answer = d.sut().post("/api/agents/answer/" + taskId + "?sessionId=" + session, Map.of("answer", "Hello"));
            JsonNode terminal = terminalOf(d, session);
            s.activation("the resumed task failed (was " + terminal.path("status").asText() + "; answer HTTP " + answer.status() + ")",
                    "failed".equals(terminal.path("status").asText()), EvidenceSource.HTTP, answer.evidenceRef());
            String error = terminal.path("error").asText("");
            s.activation("the public task record reports the checkpoint store read failure (error: " + error + ")",
                    error.contains("Failed to get key") && error.contains("agent_state"), EvidenceSource.HTTP, "session " + session);
            JsonNode statsAfter = d.stats();
            s.activation("no model request after the resume (model and core error channels not involved)",
                    GateDriver.count(statsAfter, "generation") == GateDriver.count(statsBefore, "generation")
                            && GateDriver.count(statsAfter, "totalRequests") == GateDriver.count(statsBefore, "totalRequests"),
                    EvidenceSource.STUB, "stub stats before " + statsBefore + " after " + statsAfter);
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "after", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, "§5.1.3/§6/验收出口5").failures(1, "§5.1.3/§6/验收出口5");
        }
    }

    private static JsonNode awaitParked(GateDriver d, String session) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        JsonNode task = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        do {
            JsonNode tasks = d.tasks(session);
            if (tasks.size() == 1) {
                task = tasks.get(0);
                if ("awaiting_answer".equals(task.path("resumeState").asText()) || !"running".equals(task.path("status").asText())) return task;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        return task;
    }

    private static JsonNode terminalOf(GateDriver d, String session) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        JsonNode task = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        do {
            JsonNode tasks = d.tasks(session);
            if (tasks.size() == 1) {
                task = tasks.get(0);
                if (!"running".equals(task.path("status").asText())) return task;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        return task;
    }
}
