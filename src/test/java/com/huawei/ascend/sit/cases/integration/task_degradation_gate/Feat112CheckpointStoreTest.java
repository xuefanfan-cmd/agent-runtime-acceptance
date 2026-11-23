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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * A task failure with no stable code, no structured category and no core error result, after a guardrail denial in
 * the same logical task (feature §5.1.3 line 258, §6). The DA checkpointer touches its store when an agent run
 * starts, interrupts and ends; so the task is parked on a real question (checkpoint saved to the test-owned Redis),
 * the store is stopped, and the answer resumes the task: restoring the checkpoint fails before any model call.
 * The DEBUG terminal-fact log confirms the classification input (INFRASTRUCTURE_FAILURE, empty code).
 */
@Feat112Integration
class Feat112CheckpointStoreTest {

    @Test
    @Covers(value = "FEAT-112-E15", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-E15] 主链曾被护栏拒绝、随后原因不明失败：按系统异常计分子")
    void deniedThenUnexplainedFailureCountsNumerator(Scenario s) throws Exception {
        var form = DeploymentForm.standard().named("e53").withRedisCheckpointer().withTerminalFactLog();
        try (var deployment = GateDeployment.start(form); var d = deployment.driver(s)) {
            var before = d.prepareEmptyClosed();
            Path outside = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().getRoot()
                    .resolve("feat112-outside-" + UUID.randomUUID()).resolve("denied.txt");
            d.script(List.of(
                    GateDriver.toolCallResponse("call-feat112-1", "write_file", Map.of("path", outside.toString(), "content", "feat112")),
                    GateDriver.toolCallResponse("call-feat112-2", "ask_user",
                            Map.of("questions", List.of(Map.of("question", "Choose a harmless greeting?"))))),
                    "a denied write, then a real question");
            String session = d.session();
            var stream = d.sut().post("/api/agents/run-stream", Map.of("sessionId", session, "input", "Write a note"));
            s.activation("the write was denied by the guardrail and reported to the model",
                    stream.status() == 200 && stream.body().contains("denied"), EvidenceSource.SSE, stream.evidenceRef());
            s.activation("the outside path was not written", !Files.exists(outside), EvidenceSource.PROCESS, outside.toString());
            JsonNode parked = awaitParked(d, session);
            s.activation("the task is parked on awaiting_answer (checkpoint saved to the store)",
                    "awaiting_answer".equals(parked.path("resumeState").asText()), EvidenceSource.HTTP, "session " + session);
            String taskId = parked.path("id").asText();
            deployment.redis().stop();
            s.activation("checkpoint store stopped while the DA keeps running", deployment.redis().stopped(),
                    EvidenceSource.PROCESS, deployment.redis().describe());
            Path log = deployment.processLog();
            long offset = Files.size(log);
            var answer = d.sut().post("/api/agents/answer/" + taskId + "?sessionId=" + session, Map.of("answer", "Hello"));
            String terminal = terminalOf(d, session);
            s.activation("the resumed task failed (was " + terminal + "; answer HTTP " + answer.status() + ")",
                    "failed".equals(terminal), EvidenceSource.HTTP, answer.evidenceRef());
            String fact = tail(log, offset).lines().filter(line -> line.contains("terminal fact taskId=" + taskId)).findFirst().orElse("");
            boolean unexplained = Pattern.compile("class=INFRASTRUCTURE_FAILURE code=\\s*$").matcher(fact).find();
            s.activation("terminal fact without a stable code (" + (fact.isEmpty() ? "no terminal-fact log line" : fact.strip()) + ")",
                    unexplained, EvidenceSource.LOG, log + " from byte " + offset);
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "after", after.gate(), after.evidenceRef())
                    .instance(before.instanceId(), "§5.1.8").samples(1, "§5.1.3/§6/验收出口5").failures(1, "§5.1.3/§6/验收出口5");
        }
    }

    private static JsonNode awaitParked(GateDriver d, String session) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        JsonNode task = null;
        do {
            JsonNode tasks = d.tasks(session);
            if (tasks.size() == 1) {
                task = tasks.get(0);
                if ("awaiting_answer".equals(task.path("resumeState").asText()) || !"running".equals(task.path("status").asText())) return task;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        return task == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : task;
    }

    private static String terminalOf(GateDriver d, String session) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status = "no task listed";
        do {
            JsonNode tasks = d.tasks(session);
            if (tasks.size() == 1) {
                JsonNode task = tasks.get(0);
                status = task.path("status").asText();
                if (!"running".equals(status)) return status;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        return status;
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
}
