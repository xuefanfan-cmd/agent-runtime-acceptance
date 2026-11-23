package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import com.huawei.ascend.sit.judge.AcceptanceLayer;
import com.huawei.ascend.sit.judge.EvidenceChecks.Outcome;
import com.huawei.ascend.sit.judge.EvidenceChecks.Reason;
import com.huawei.ascend.sit.judge.PreconditionNotMet;
import com.huawei.ascend.sit.judge.Scenario;
import com.huawei.ascend.sit.judge.ScenarioVerdict;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** Driver behaviour against a local fake DA/stub: activation recording and owned-resource cleanup. */
@Tag("selftest")
class GateDriverTest {
    private static Scenario scenario() {
        return new Scenario(new ScenarioVerdict(List.of("FEAT-112-selftest"), AcceptanceLayer.PUBLIC_SURFACE), "GateDriverTest");
    }

    @Test
    void failedTaskListStillCancelsWorkerAndAttemptsReset() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean reset = new AtomicBoolean();
        server.createContext("/", e -> {
            String path = e.getRequestURI().getPath();
            int code = 200;
            String body = "{}";
            if (path.equals("/api/agents/run")) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            } else if (path.startsWith("/api/agents/tasks/")) code = 503;
            else if (path.equals("/__test/reset")) { reset.set(true); code = 500; }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try { e.sendResponseHeaders(code, bytes.length); e.getResponseBody().write(bytes); } finally { e.close(); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var d = new GateDriver(url, url, null, "cleanup-failure", scenario());
            var pending = d.async(d.session(), new CountDownLatch(0));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var failure = assertThrows(IllegalStateException.class, d::close);
            assertTrue(failure.getMessage().contains("503"));
            assertTrue(failure.getSuppressed().length >= 1);
            assertTrue(pending.isCancelled());
            assertTrue(d.workersTerminated());
            assertTrue(reset.get());
        } finally {
            release.countDown();
            server.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void outcomeNotServedByStubIsRecordedAsMissingActivationAndSessionIsCleaned() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> session = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        server.createContext("/", e -> {
            String path = e.getRequestURI().getPath();
            String body = "{}";
            int code = 200;
            if (path.equals("/api/agents/run")) {
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getRequestBody());
                session.set(json.path("sessionId").asText());
                code = 500;
                body = "{\"status\":\"failed\"}";
            } else if (path.startsWith("/api/agents/tasks/")) {
                body = "[{\"id\":\"owned-task\",\"status\":\"running\"}]";
            } else if (path.equals("/api/agents/cancel/owned-task")) cancelled.set(true);
            else if (path.equals("/__test/stats")) body = "{\"generation\":1,\"totalRequests\":0,\"errorResponses\":0,\"activeRequests\":0}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(code, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            Scenario s = scenario();
            var d = new GateDriver(url, url, null, "not-served", s);
            var thrown = assertThrows(PreconditionNotMet.class, () -> d.complete(true));
            assertEquals(Reason.CONDITIONAL_NOT_TRIGGERED, thrown.reason());
            var result = s.verdict().results().getFirst();
            assertEquals(Outcome.FAIL, result.outcome());
            assertEquals(Reason.CONDITIONAL_NOT_TRIGGERED, result.reason());
            d.close();
            assertNotNull(session.get());
            assertTrue(cancelled.get());
            assertTrue(d.workersTerminated());
        } finally {
            server.stop(0);
        }
    }
}
