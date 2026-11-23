package com.huawei.ascend.sit.cases.integration.task_degradation_gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.Feat112Contract;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeployedGateCase;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy.DeploymentForm;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.GateSnapshotChecks;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge.Comparison;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.PrecedenceJudge.Reply;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.judge.SequenceJudge;
import com.huawei.ascend.sit.judge.Covers;
import com.huawei.ascend.sit.judge.EvidenceChecks.Category;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.EvidenceChecks.Observation;
import com.huawei.ascend.sit.judge.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.huawei.ascend.sit.judge.AcceptanceLayer.PUBLIC_SURFACE;

/**
 * Validation precedes admission (feature §3.1, §5.1.9, acceptance exit 18). The oracle for "既有校验错误" is the
 * same invalid request answered with the gate CLOSED; under OPEN and HALF_OPEN it must get that same error, not
 * ENGINE_DEGRADED, and must not claim the probe, create a task, touch the window or reach the model.
 */
class Feat112ValidationPrecedenceTest extends DeployedGateCase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ENTRIES = List.of("/api/agents/run", "/api/agents/run-stream");
    private Path mediaRoot;

    @Override
    protected DeploymentForm form() {
        try {
            Files.createDirectories(Path.of("target"));
            mediaRoot = Files.createTempDirectory(Path.of("target"), "feat112-media-").toAbsolutePath();
        } catch (IOException e) {
            throw new IllegalStateException("media fixture directory", e);
        }
        return DeploymentForm.standard().named("validation").with(DeploymentForm.GATE + "open-quiet-period", "20s")
                .with("deepanalyze.data.media-dir", mediaRoot.toString())
                .with("deepanalyze.security.file-whitelist[0]", "txt");
    }

    /** One invalid request kind: its body depends only on the session it is sent in. */
    private interface InvalidRequest {
        Map<String, Object> body(String session) throws IOException;
    }

    @Test
    @Covers(value = "FEAT-112-F2", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-F2] OPEN/HALF_OPEN 下参数错误仍返回既有校验错误")
    void invalidParametersPrecedeGate(Scenario s) throws Exception {
        Map<String, InvalidRequest> kinds = new LinkedHashMap<>();
        kinds.put("missingSessionId", session -> Map.of("input", "Reply with a short greeting"));
        kinds.put("missingInputAndMedia", session -> Map.of("sessionId", session));
        kinds.put("unsupportedRuntimeConfigVersion", session -> Map.of("sessionId", session, "input", "Reply with a short greeting",
                "runtimeConfig", Map.of("version", 999)));
        verify(s, kinds);
    }

    @Test
    @Covers(value = "FEAT-112-F3", layer = PUBLIC_SURFACE)
    @DisplayName("[FEAT-112-F3] OPEN/HALF_OPEN 下媒体与 team 校验仍返回既有校验错误")
    void mediaAndTeamValidationPrecedeGate(Scenario s) throws Exception {
        String missingMedia = "media-" + UUID.randomUUID();
        String deniedMedia = "media-" + UUID.randomUUID();
        String team = "feat112-missing-team-" + UUID.randomUUID();
        Map<String, InvalidRequest> kinds = new LinkedHashMap<>();
        kinds.put("mediaMissing", session -> Map.of("sessionId", session, "input", "Inspect this attachment",
                "mediaIds", List.of(missingMedia)));
        kinds.put("mediaNotWhitelisted", session -> {
            Path directory = mediaRoot.resolve("sessions").resolve(session).resolve("media").resolve(deniedMedia);
            Files.createDirectories(directory);
            Path meta = directory.resolve("meta.json");
            if (!Files.exists(meta)) {
                Files.writeString(meta, JSON.writeValueAsString(Map.of("mediaId", deniedMedia,
                        "fileName", "harmless.feat112blocked", "mimeType", "text/plain", "size", 0,
                        "createdAt", Instant.now().toString())), StandardOpenOption.CREATE_NEW);
            }
            return Map.of("sessionId", session, "input", "Inspect this attachment", "mediaIds", List.of(deniedMedia));
        });
        kinds.put("teamUnresolvable", session -> Map.of("sessionId", session, "input", "hello", "teamId", team));
        verify(s, kinds);
    }

    private void verify(Scenario s, Map<String, InvalidRequest> kinds) throws Exception {
        try (var d = deployment().driver(s)) {
            d.prepareEmptyClosed();
            Map<String, String> sessions = new LinkedHashMap<>();
            Map<String, Reply> ungated = new LinkedHashMap<>();
            for (var kind : kinds.entrySet()) {
                for (String route : ENTRIES) {
                    String key = kind.getKey() + route;
                    String session = d.session();
                    sessions.put(key, session);
                    var response = d.sut().post(route, kind.getValue().body(session));
                    Reply reply = Reply.of(response.status(), parse(response.body()));
                    s.activation(key + " refused by validation with the gate CLOSED", reply.refusedByExistingContract(),
                            EvidenceSource.HTTP, response.evidenceRef());
                    if (kind.getKey().startsWith("team")) {
                        s.check(s.id(), Feat112Contract.observation(s.id(), "teamBranch" + route.replace('/', '.'), "§5.1.9",
                                        Category.EXTERNAL_CONTRACT),
                                Observation.captured(reply.status() + " " + reply.error(), EvidenceSource.HTTP, response.evidenceRef()),
                                "which team branch this deployment exercises (diagnostic only)", branch -> true);
                    }
                    ungated.put(key, reply);
                }
            }
            d.openGate();
            gated(s, d, kinds, sessions, ungated, "OPEN");
            var half = d.establishState("HALF_OPEN", Duration.ofSeconds(90));
            s.precondition("probe slot free", !half.probeInFlight(), EvidenceSource.DIAGNOSTIC_SNAPSHOT, half.evidenceRef());
            gated(s, d, kinds, sessions, ungated, "HALF_OPEN");
            var after = d.snapshot();
            GateSnapshotChecks.of(s, s.id(), "afterHalfOpenRequests", after.gate(), after.evidenceRef())
                    .state("HALF_OPEN", "验收出口18").probeInFlight(false, "§5.1.1/验收出口18");
        }
    }

    private void gated(Scenario s, GateDriver d, Map<String, InvalidRequest> kinds, Map<String, String> sessions,
                       Map<String, Reply> ungated, String state) throws Exception {
        for (var kind : kinds.entrySet()) {
            for (String route : ENTRIES) {
                String key = kind.getKey() + route;
                String session = sessions.get(key);
                var before = d.snapshot();
                s.precondition("gate " + state + " before " + key, state.equals(before.state())
                        && (!"OPEN".equals(state) || before.retryAfterSeconds() >= 2), EvidenceSource.DIAGNOSTIC_SNAPSHOT, before.evidenceRef());
                JsonNode tasksBefore = d.tasks(session);
                JsonNode statsBefore = d.stats();
                DaEvidenceClient.Exchange response = d.sut().post(route, kind.getValue().body(session));
                var after = d.snapshot();
                String ref = response.evidenceRef() + " (" + state + ", " + key + ")";
                s.accept(PrecedenceJudge.judge(s.id(), s.context(), Observation.captured(
                        new Comparison(ungated.get(key), Reply.of(response.status(), parse(response.body()))), EvidenceSource.HTTP, ref)));
                var effects = new SequenceJudge.SideEffects(before.gate(), after.gate(), tasksBefore, d.tasks(session),
                        statsBefore, d.stats(), state.equals(after.state()));
                s.accept(SequenceJudge.noSideEffects(s.id(), s.context(), Observation.captured(effects, EvidenceSource.DIAGNOSTIC_SNAPSHOT, ref)));
                GateSnapshotChecks.of(s, s.id(), state + "." + key, after.gate(), after.evidenceRef())
                        .probeInFlight(false, "§5.1.1/验收出口18");
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
