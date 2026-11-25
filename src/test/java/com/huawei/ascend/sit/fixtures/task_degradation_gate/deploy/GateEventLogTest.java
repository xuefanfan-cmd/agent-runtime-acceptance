package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("selftest")
class GateEventLogTest {
    @Test
    void parsesKeysAfterTheEventTokenWhateverThePrefix() {
        var event = GateEventLog.parse("2026-11-25 INFO [exec-1] c.o.DegradationGate - event=degradation_gate_transition "
                + "from=HALF_OPEN to=OPEN reason=system_failure generation=3 instanceId=i-1 trigger=terminal_fact taskId=t-9");
        assertNotNull(event);
        assertEquals("degradation_gate_transition", event.name());
        assertEquals("system_failure", event.key("reason"));
        assertEquals(3, event.number("generation"));
        assertEquals(-1, event.number("missing"));
        assertNull(GateEventLog.parse("da degradation gate wired enabled=true instanceId=i-1"));
    }

    @Test
    void readsOnlyNamedEventsAfterTheOffset(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("process.log");
        Files.writeString(log, "x event=degradation_gate_transition to=OPEN reason=threshold_reached generation=0\n", StandardCharsets.UTF_8);
        long offset = GateEventLog.mark(log);
        Files.writeString(log, "y event=degradation_probe_released instanceId=i\n"
                + "z event=degradation_gate_transition from=OPEN to=HALF_OPEN reason=quiet_elapsed generation=0\r\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        var events = GateEventLog.since(log, offset, "degradation_gate_transition");
        assertEquals(1, events.size());
        assertEquals("quiet_elapsed", events.get(0).key("reason"));
        assertEquals(0, GateEventLog.mark(dir.resolve("absent.log")));
    }
}
