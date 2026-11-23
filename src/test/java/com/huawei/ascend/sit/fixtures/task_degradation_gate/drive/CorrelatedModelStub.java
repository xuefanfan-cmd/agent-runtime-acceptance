package com.huawei.ascend.sit.fixtures.task_degradation_gate.drive;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-owned OpenAI-compatible model: marker matching + explicit barriers, never global FIFO. */
public final class CorrelatedModelStub implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Upper bound for a held model call to arrive: generous because a DA under libfaketime starts its first model call slowly. */
    private static final long ENTRY_SECONDS = 60;
    private final HttpServer server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    public final class Plan {
        final String marker; final CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger(); volatile boolean failure; volatile String tool; volatile Map<String,Object> arguments;
        /** Second barrier for the model call that continues the task after an answer (call 2 onwards). */
        final CountDownLatch continuationEntered = new CountDownLatch(1), continued = new CountDownLatch(1);
        volatile boolean holdContinuation;
        Plan(String marker) { this.marker = marker; }
        public void awaitEntered() throws Exception { if (!entered.await(ENTRY_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("model marker not observed: " + marker); }
        /** Holds the next model call after the first one (the continuation after an answer) until released. */
        public void holdContinuation() { holdContinuation = true; }
        public void awaitContinuationEntered() throws Exception {
            if (!continuationEntered.await(ENTRY_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("continuation of marker not observed: " + marker);
        }
        public void releaseContinuation() { events.add(marker + " continuation released"); continued.countDown(); }
        public synchronized void release(boolean failure) {
            if (released.getCount() == 0) return;
            this.failure = failure; events.add(marker + " release " + failure); released.countDown();
        }
        public synchronized void tool(String name, Map<String,Object> arguments) {
            if (released.getCount() == 0) throw new IllegalStateException("plan already released");
            this.tool=name; this.arguments=Map.copyOf(arguments); release(false);
        }
        /** Teardown only: never overwrites an outcome already chosen by the test. */
        public synchronized void unblockForCleanup() { continued.countDown(); if (released.getCount() != 0) release(false); }
        public int calls() { return calls.get(); }
    }
    public CorrelatedModelStub(int port) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0); server.setExecutor(workers);
        server.createContext("/",this::handle); server.start();
    }
    public String url() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    public Plan hold(String marker) {
        if (!marker.matches("feat112-[a-zA-Z0-9-]+")) throw new IllegalArgumentException("safe unique marker required");
        Plan plan=new Plan(marker); if(plans.putIfAbsent(marker,plan)!=null)throw new IllegalArgumentException("duplicate marker");return plan;
    }
    public void save(Path file) throws Exception { Files.createDirectories(file.getParent()); Files.write(file,events); }
    private void handle(HttpExchange ex) throws java.io.IOException {
        try {
            if(!ex.getRequestMethod().equals("POST") || !ex.getRequestURI().getPath().equals("/v1/chat/completions")) { send(ex,404,"{}"); return; }
            byte[] bytes=ex.getRequestBody().readNBytes(1048577); if(bytes.length>1048576){send(ex,413,"{}");return;}
            JsonNode request=JSON.readTree(bytes); String messages=request.path("messages").toString();
            List<Plan> matched=plans.values().stream().filter(p->messages.contains(p.marker)).toList();
            if(matched.size()!=1){send(ex,400,"{\"error\":{\"message\":\"exactly one test marker required\"}}");return;}
            Plan p=matched.get(0);int call=p.calls.incrementAndGet();events.add(p.marker+" entered "+call);p.entered.countDown();
            if(!p.released.await(90,TimeUnit.SECONDS)){send(ex,504,"{}");return;}
            if(call>1 && p.holdContinuation){
                events.add(p.marker+" continuation entered "+call);p.continuationEntered.countDown();
                if(!p.continued.await(90,TimeUnit.SECONDS)){send(ex,504,"{}");return;}
            }
            if(p.failure){send(ex,500,"{\"error\":{\"message\":\"ENGINE_MODEL_ERROR\",\"code\":\"ENGINE_MODEL_ERROR\",\"type\":\"server_error\"}}");return;}
            ObjectNode message=JSON.createObjectNode().put("role","assistant").put("content","controlled greeting");String finish="stop";
            if(p.tool!=null && call==1){finish="tool_calls";message.put("content","");var function=message.putArray("tool_calls").addObject().put("id","call-"+p.marker).put("type","function").putObject("function");function.put("name",p.tool).put("arguments",JSON.writeValueAsString(p.arguments));}
            ObjectNode completion=JSON.createObjectNode().put("id","response-"+p.marker).put("created",0).put("model","stub-model").put("object","chat.completion");
            var choice=completion.putArray("choices").addObject().put("index",0).put("finish_reason",finish);choice.set("message",message);
            if(!request.path("stream").asBoolean()){send(ex,200,completion.toString());return;}
            ex.getResponseHeaders().set("Content-Type","text/event-stream");ex.sendResponseHeaders(200,0);
            completion.put("object","chat.completion.chunk");choice.remove("message");
            if(message.has("tool_calls"))((ObjectNode)message.path("tool_calls").get(0)).put("index",0);
            choice.set("delta",message);choice.putNull("finish_reason");event(ex,completion.toString());
            choice.set("delta",JSON.createObjectNode());choice.put("finish_reason",finish);event(ex,completion.toString());event(ex,"[DONE]");
        } catch(InterruptedException e){Thread.currentThread().interrupt();} finally {ex.close();}
    }
    private static void event(HttpExchange ex,String value)throws java.io.IOException {ex.getResponseBody().write(("data: "+value+"\n\n").getBytes(StandardCharsets.UTF_8));ex.getResponseBody().flush();}
    private static void send(HttpExchange ex,int status,String body)throws java.io.IOException {byte[] bytes=body.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json");ex.sendResponseHeaders(status,bytes.length);ex.getResponseBody().write(bytes);}
    @Override public void close(){
        plans.values().forEach(Plan::unblockForCleanup);server.stop(0);workers.shutdownNow();
        try { if (!workers.awaitTermination(5,TimeUnit.SECONDS)) throw new IllegalStateException("model workers did not terminate"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("model cleanup interrupted",e); }
    }
}
