package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CorrelatedModelStubTest {
    @Test void explicitReleaseIsRequestScopedAndFailurePersistsAcrossRetries() throws Exception {
        try(var stub=new CorrelatedModelStub(0)) {
            var old=stub.hold("feat112-old"); var probe=stub.hold("feat112-probe");
            var client=HttpClient.newHttpClient();
            var a=client.sendAsync(request(stub.url(),"feat112-old",false),HttpResponse.BodyHandlers.ofString());
            var b=client.sendAsync(request(stub.url(),"feat112-probe",true),HttpResponse.BodyHandlers.ofString());
            old.awaitEntered();probe.awaitEntered(); assertFalse(a.isDone());assertFalse(b.isDone());
            probe.release(false);assertTrue(b.get(5,TimeUnit.SECONDS).body().contains("[DONE]"));assertFalse(a.isDone());
            old.release(true);assertEquals(500,a.get(5,TimeUnit.SECONDS).statusCode());
            old.release(false); old.unblockForCleanup();
            assertThrows(IllegalStateException.class,()->old.tool("ask_user",java.util.Map.of()));
            assertEquals(500,client.send(request(stub.url(),"feat112-old",false),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(2,old.calls());assertEquals(1,probe.calls());
            assertEquals(400,client.send(request(stub.url(),"unmatched",false),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void originalStreamCollectorUsesPostAndCollectsStart() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var method=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/api/agents/run-stream",e->{method.set(e.getRequestMethod());
            e.getRequestBody().readAllBytes(); byte[] bytes="event: start\ndata: {\"taskId\":\"test-task\"}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type","text/event-stream");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.start();
        try {var client=new DaEvidenceClient("http://127.0.0.1:"+server.getAddress().getPort(),null,"original-stream-selftest");
            var capture=client.disconnectRunStreamAfterStart(java.util.Map.of("sessionId","test","input","hello"));String file=capture.evidenceRef();assertTrue(capture.servedWithStart());
            assertEquals("POST",method.get());assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of(file)).contains("intentional_disconnect_after_start"));
        } finally {server.stop(0);}
    }
    private HttpRequest request(String url,String marker,boolean stream){return HttpRequest.newBuilder(URI.create(url+"/v1/chat/completions"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\""+marker+"\"}],\"stream\":"+stream+"}")).build();}
}
