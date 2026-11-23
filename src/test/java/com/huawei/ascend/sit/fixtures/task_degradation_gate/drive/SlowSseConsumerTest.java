package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import org.junit.jupiter.api.*;
import java.net.URI;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@Tag("selftest")
class SlowSseConsumerTest {
    @Test void consumesSustainedLocalSourceAtBoundedPace() throws Exception {
        try (var source = new SustainedModelStream(100000, 8192);
             var slow = new SlowSseConsumer(URI.create(source.baseUrl() + "/chat/completions"), "{}", null)) {
            long read = slow.consume(Duration.ofMillis(600), Duration.ofMillis(100));
            assertTrue(read > 0); assertTrue(read <= 1024, "128-byte reads remain deliberately slow");
            assertTrue(source.emittedBytes() > read * 10, "actual producer/consumer imbalance");
            assertTrue(source.emittedFrames() < 100000, "stream remains active during observation");
        }
    }
    @Test void rejectsNonLocalServices() {
        assertThrows(IllegalArgumentException.class, () -> new SlowSseConsumer(URI.create("https://localhost/test"), "{}", null));
    }
}
