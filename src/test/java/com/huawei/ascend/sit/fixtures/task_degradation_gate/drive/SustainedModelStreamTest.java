package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@Tag("selftest")
class SustainedModelStreamTest {
    @Test void emitsActualSseFramesAndCompletion() throws Exception {
        try (var source = new SustainedModelStream(300, 256)) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(source.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(300, source.emittedFrames());
            assertTrue(source.emittedBytes() > 300 * 256);
            assertTrue(response.body().endsWith("data: [DONE]\n\n"));
            assertEquals(301, response.body().split("chat.completion.chunk", -1).length);
        }
    }
    @Test void stopsBlockedProducerAfterSlowReaderDisconnect() throws Exception {
        try (var source = new SustainedModelStream(100000, 65536)) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(source.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                assertTrue(stream.readNBytes(32).length > 0);
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (source.emittedFrames() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(source.emittedFrames() >= 2);
                assertTrue(source.emittedFrames() < 100000, "producer must still be active, not an already finished short stream");
            }
        }
    }
}
