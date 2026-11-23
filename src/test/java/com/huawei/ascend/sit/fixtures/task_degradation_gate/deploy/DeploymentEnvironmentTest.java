package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Offline checks of the declared-environment fixtures (no Docker, no libfaketime needed). */
@Tag("selftest")
class DeploymentEnvironmentTest {

    @Test
    void faketimeOffsetsAreSignedSeconds() {
        assertEquals("+0", ClockControl.format(Duration.ZERO));
        assertEquals("-90", ClockControl.format(Duration.ofSeconds(-90)));
        assertEquals("+5", ClockControl.format(Duration.ofSeconds(5)));
    }

    @Test
    void reportedLagComesFromTheDateHeaderTheProcessWrote() {
        Instant received = Instant.parse("2026-11-17T08:00:00Z");
        String behind = DateTimeFormatter.RFC_1123_DATE_TIME.format(received.minusSeconds(90).atOffset(ZoneOffset.UTC));
        assertEquals(90L, ClockControl.reportedLagSeconds(Map.of("date", List.of(behind)), received));
        assertEquals(90L, ClockControl.reportedLagSeconds(Map.of("Date", List.of(behind)), received));
        assertNull(ClockControl.reportedLagSeconds(Map.of(), received));
        assertNull(ClockControl.reportedLagSeconds(Map.of("date", List.of("not a date")), received));
    }

    @Test
    void dataPlaneBaselineSplitsIntoTerminatedStatements() throws Exception {
        String baseline;
        try (InputStream stream = PostgresDataPlane.class.getResourceAsStream(PostgresDataPlane.BASELINE)) {
            assertNotNull(stream, "baseline on the test classpath");
            baseline = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> statements = PostgresDataPlane.statements(baseline);
        assertFalse(statements.isEmpty());
        assertTrue(statements.stream().allMatch(sql -> sql.strip().endsWith(";")));
        assertTrue(statements.stream().anyMatch(sql -> sql.contains("agent_teams")), "team table present for the team lookup");
        assertThrows(IllegalStateException.class, () -> PostgresDataPlane.statements("SELECT 1"));
    }

    @Test
    void formsDeclareTheirEnvironmentAndKeepItAcrossDerivations() {
        var form = DeploymentForm.standard().withRedisCheckpointer().withClockControl().named("env").with("k", "v")
                .withCorrelatedModel().withTerminalFactLog();
        assertTrue(form.needs(DeploymentForm.Infra.REDIS_CHECKPOINTER));
        assertTrue(form.needs(DeploymentForm.Infra.CLOCK_CONTROL));
        assertFalse(form.needs(DeploymentForm.Infra.POSTGRES_DATA_PLANE));
        assertEquals("DEBUG", form.properties().get("logging.level.com.openjiuwen.da.compat.DaDegradationSupport"));
        assertFalse(DeploymentForm.standard().needs(DeploymentForm.Infra.REDIS_CHECKPOINTER));
    }
}
