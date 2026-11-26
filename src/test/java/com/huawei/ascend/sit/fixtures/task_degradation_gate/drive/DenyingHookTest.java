package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Tag("selftest")
class DenyingHookTest {
    @Test
    void deniesEveryPostAndCountsIt() throws Exception {
        try (var hook = new DenyingHook()) {
            var client = HttpClient.newHttpClient();
            for (int i = 0; i < 2; i++) {
                var response = client.send(HttpRequest.newBuilder(URI.create(hook.url()))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"event\":\"PreToolUse\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("\"allowed\":false"));
            }
            assertEquals(2, hook.calls());
            assertTrue(hook.url().startsWith("http://127.0.0.1:"));
        }
    }
}
