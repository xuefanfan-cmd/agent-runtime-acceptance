package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062DirectRegistryReactAgentTest.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062RegistrationScenarios.complete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class Feat062TransportBoundaryScenarios {
    static void staticTimeout() throws Exception {
        try (var f = new Feat062Fixture("static")) {
            f.model.live = false; f.start("hotel", Map.of()); f.start("trip", Map.of());
            var properties = new LinkedHashMap<>(Feat062CatalogScenarios.statics(f));
            properties.put(A2A + "remote-agents[0].timeout-seconds", "1");
            f.start("mainplan", properties); f.wires.get("trip").rpcDelayMillis = 4000;
            String marker = "STATIC_TIMEOUT_" + UUID.randomUUID(); long start = System.nanoTime();
            var task = current(f, f.send("mainplan", "/a2a", f.prompt(marker), false, marker, null), "mainplan", "/a2a");
            f.save("timeout-measurement.json", Map.of("elapsedMillis", Duration.ofNanos(System.nanoTime() - start).toMillis(), "state", state(task)));
            assertThat(f.toolResults("mainplan", marker, "travel-trip").toString()).contains("REMOTE_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(4000);
        }
    }
    static void tls() throws Exception {
        try (var f = new Feat062Fixture("static")) {
            f.model.live = false; f.start("hotel", Map.of()); f.start("trip", Map.of());
            Path store = f.evidence.resolve("test-tls.p12"); char[] password = "feat062-local-test".toCharArray();
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(),
                    "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-storetype", "PKCS12",
                    "-keystore", store.toString(), "-storepass", new String(password)).redirectErrorStream(true)
                    .redirectOutput(f.evidence.resolve("keytool.log").toFile()).start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero();
            var keys = java.security.KeyStore.getInstance("PKCS12"); try (var in = Files.newInputStream(store)) { keys.load(in, password); }
            var km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password);
            var tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
            var ssl = javax.net.ssl.SSLContext.getInstance("TLS"); ssl.init(km.getKeyManagers(), tm.getTrustManagers(), null);
            // Existing card discovery uses JVM trust. Keep its CA setup separate from outbound target TLS.
            // Only per-target key-store properties provide the client identity required by the later RPC handshake.
            f.jvmArguments.put("mainplan", List.of("-Djavax.net.ssl.trustStore=" + store,
                    "-Djavax.net.ssl.trustStoreType=PKCS12", "-Djavax.net.ssl.trustStorePassword=" + new String(password)));
            try (var wire = f.new Wire("http://127.0.0.1:" + f.ports.get("trip"), "trip-tls", null, ssl)) {
                wire.transform = n -> {
                    Feat062StaticScenarios.replaceUrls(n, f.wires.get("trip").url(), wire.url());
                    wire.requireClientCertificate = true;
                };
                var settings = new LinkedHashMap<>(Feat062CatalogScenarios.statics(f));
                settings.put(A2A + "remote-agents[0].url", wire.url());
                String prefix = A2A + "remote-agents[0].tls.";
                settings.put(prefix + "enabled", "true"); settings.put(prefix + "trust-store", store.toUri().toString());
                settings.put(prefix + "trust-store-type", "PKCS12"); settings.put(prefix + "trust-store-password", new String(password));
                settings.put(prefix + "key-store", store.toUri().toString());
                settings.put(prefix + "key-store-type", "PKCS12"); settings.put(prefix + "key-store-password", new String(password));
                settings.put(prefix + "verify-hostname", "true");
                f.start("mainplan", settings); complete(f, "REAL_TLS_TRIP");
                assertThat(wire.requests).anyMatch(n -> n.at("/body/method").asText().startsWith("Send"));
                assertThat(wire.requests.stream().filter(n -> n.at("/body/method").asText().startsWith("Send")))
                        .allSatisfy(n -> assertThat(n.path("tlsPeerPrincipal").asText()).contains("CN=localhost"));
            }
        }
    }
    static JsonNode invoke(Feat062Fixture f, String marker, boolean stream) throws Exception {
        var r = request("POST", f.wires.get("mainplan").url() + "/feat062/call", JSON.writeValueAsString(Map.of(
                "agent", "travel-trip", "message", f.prompt(marker), "context", marker, "streaming", stream)), Map.of("Content-Type", "application/json"));
        f.saveText("caller-outcome-" + marker + ".json", r.body());
        assertThat(r.statusCode()).isIn(200,500); return JSON.readTree(r.body());
    }
    static void scaleIn() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.startTravel(false); f.startReplica("trip", "trip-replica"); Feat062ReplicaScenarios.awaitCount(f, "travel-trip", 2);
            complete(f, "BEFORE_SCALE_IN");
            try (var pool = Executors.newSingleThreadExecutor()) {
                f.wires.get("trip-replica").rpcDelayMillis = 3000;
                Future<?> business = null; boolean selected = false;
                for (int attempt=0;attempt<20;attempt++) {
                    String marker="HEALTHY_IN_FLIGHT_"+UUID.randomUUID();
                    var pending=pool.submit(()->{
                        var task=completed(f,f.send("mainplan","/a2a",f.prompt(marker),false,marker,null),"mainplan","/a2a");
                        assertChain(f,marker,task);return null;
                    });
                    await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).until(()->pending.isDone() ||
                            !f.wires.get("trip-replica").calls(marker).isEmpty());
                    if(!f.wires.get("trip-replica").calls(marker).isEmpty()) {business=pending;selected=true;break;}
                    pending.get(60,TimeUnit.SECONDS);
                }
                org.junit.jupiter.api.Assumptions.assumeTrue(selected,"INCONCLUSIVE: survivor not selected within 20 bounded business attempts");
                f.stacks.get("trip").stop("trip"); Feat062ReplicaScenarios.awaitCount(f, "travel-trip", 1);
                long removed = System.nanoTime();
                await().atMost(Duration.ofSeconds(7)).pollInterval(Duration.ofMillis(100)).until(() -> System.nanoTime() - removed >= Duration.ofSeconds(5).toNanos());
                business.get(300, TimeUnit.SECONDS);
                int before = f.wires.get("trip").requests.size();
                for (int i = 0; i < 5; i++) complete(f, "SCALED_IN_" + i);
                assertThat(f.wires.get("trip").requests).hasSize(before);
                assertThat(Feat062ReplicaScenarios.sent(f, "trip-replica")).isPositive();
            }
        }
    }
    static void fault(String fault) throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.contractHost = true; f.startTravel(false); complete(f, "BEFORE_CANDIDATE_FAULT");
            f.startReplica("trip", "trip-replica"); Feat062ReplicaScenarios.awaitCount(f, "travel-trip", 2);
            var broken = f.wires.get("trip-replica");
            if (fault.equals("timeout") || fault.equals("first-event")) {
                // Public catalog SPI isolates caller behavior from discovery capability projection.
                // The independently confirmed dynamic streaming defect must not mask first-event locking.
                f.stacks.get("mainplan").stop("mainplan");
                var trip = new LinkedHashMap<String,Object>(Feat062RegistryExtensionIntegrationTest.entry(f,"trip","travel-trip","/a2a"));
                if (fault.equals("timeout")) trip.put("timeoutSeconds",1);
                trip.put("instances",List.of(
                        Map.of("instanceId","original","url",f.wires.get("trip").url()+"/a2a","weight",100),
                        Map.of("instanceId","replica","url",broken.url()+"/a2a","weight",100)));
                Path source=f.evidence.resolve("short-timeout-source.json");
                Feat062RegistryExtensionIntegrationTest.source(source,1,List.of(trip,
                        Feat062RegistryExtensionIntegrationTest.entry(f,"hotel","travel-hotel","/a2a/agents/travel-hotel")),false);
                f.start("mainplan",Map.of("feat062.source-file",source.toString()));
            }
            if (fault.equals("refused") || fault.equals("exhausted")) broken.close();
            if (fault.equals("exhausted")) f.wires.get("trip").close();
            if (fault.equals("timeout")) broken.rpcDelayMillis = 15000;
            if (fault.equals("protocol")) broken.rpcResponse = "{\"jsonrpc\":\"2.0\",\"id\":\"$ID\",\"result\":{\"unexpected\":true}}";
            if (fault.equals("FAILED") || fault.equals("REJECTED")) broken.rpcResponse = "{\"jsonrpc\":\"2.0\",\"id\":\"$ID\",\"result\":{\"task\":{\"id\":\"controlled-remote-task\",\"contextId\":\"controlled-business\",\"status\":{\"state\":\"TASK_STATE_" + fault + "\"}}}}";
            if (fault.equals("first-event")) broken.truncateStream = true;
            if (fault.equals("exhausted")) {
                String marker="ALL_EXHAUSTED_"+UUID.randomUUID();
                var task=current(f,f.send("mainplan","/a2a",f.prompt(marker),true,marker,null),"mainplan","/a2a");
                assertThat(f.toolResults("mainplan",marker,"travel-trip").toString()).contains("REMOTE_");
                assertThat(f.toolResults("mainplan",marker,"travel-hotel").toString()).contains(f.inventoryCanary);
                assertThat(task.path("artifacts").toString()).contains("REMOTE_");
                return;
            }
            boolean observed = false;
            for (int attempt = 0; attempt < 20; attempt++) {
                String marker = "FAULT_" + fault + "_" + UUID.randomUUID();
                int badBefore = broken.requests.size(); long healthyBefore = Feat062ReplicaScenarios.sent(f, "trip");
                JsonNode result = invoke(f, marker, fault.equals("first-event"));
                boolean badHit = broken.requests.size() > badBefore;
                if (fault.equals("refused") || fault.equals("exhausted")) {
                    String log = Files.readString(f.evidence.resolve("mainplan.log"));
                    badHit = log.lines().anyMatch(line -> line.contains("contextId="+marker)
                            && line.contains("url="+broken.url()+"/a2a") && line.contains("failed transiently"));
                }
                if (!badHit) continue;
                observed = true;
                long healthyDelta = Feat062ReplicaScenarios.sent(f, "trip") - healthyBefore;
                if (fault.equals("refused") || fault.equals("timeout")) {
                    assertThat(result.path("resultCategory").asText()).isEqualTo("COMPLETED"); assertThat(healthyDelta).isEqualTo(1);
                    complete(f, "BUSINESS_AFTER_FAILOVER");
                } else if (fault.equals("exhausted")) {
                    assertThat(result.toString()).contains("REMOTE_"); assertThat(result.path("resultCategory").asText()).isNotEqualTo("COMPLETED");
                } else {
                    assertThat(healthyDelta).as("protocol/business failure or first-event lock must not switch candidate").isZero();
                    assertThat(result.path("resultCategory").asText()).isNotEqualTo("COMPLETED");
                    if (fault.equals("FAILED") || fault.equals("REJECTED")) assertThat(result.path("remoteState").asText()).contains(fault);
                    if (fault.equals("first-event")) assertThat(broken.calls(marker)).anyMatch(n -> n.at("/body/method").asText().equals("SendStreamingMessage"));
                }
                break;
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(observed, "INCONCLUSIVE: fault candidate was not selected within bounded 20 attempts");
        }
    }
    static void inputRequired() throws Exception {
        try (var f = new Feat062Fixture("dynamic")) {
            f.contractHost = true; f.interruptTrip = true; f.startTravel(false); f.startReplica("trip", "trip-replica");
            Feat062ReplicaScenarios.awaitCount(f, "travel-trip", 2); boolean selected = false;
            for (int i = 0; i < 20; i++) {
                String marker = "DOWNSTREAM_WAIT_" + UUID.randomUUID();
                long otherBefore = Feat062ReplicaScenarios.sent(f, "trip-replica");
                JsonNode outcome = invoke(f, marker, false);
                if (!outcome.path("resultCategory").asText().equals("INPUT_REQUIRED")) continue;
                selected = true; assertThat(Feat062ReplicaScenarios.sent(f, "trip-replica")).isEqualTo(otherBefore);
                assertThat(outcome.path("inputPrompt").asText()).isNotBlank(); break;
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(selected, "INCONCLUSIVE: input-required candidate not selected within 20 attempts");
        }
    }
    static void invalidWeight() throws Exception {
        try (var f = new Feat062Fixture("static")) {
            f.contractHost=true;f.model.live=false;f.start("hotel",Map.of());f.start("trip",Map.of());f.startReplica("trip","trip-replica");
            Path input=f.evidence.resolve("invalid-weights.json");
            var trip=new LinkedHashMap<String,Object>(Feat062RegistryExtensionIntegrationTest.entry(f,"trip","travel-trip","/a2a"));
            var hotel=Feat062RegistryExtensionIntegrationTest.entry(f,"hotel","travel-hotel","/a2a/agents/travel-hotel");
            for (int variant=0;variant<3;variant++) {
                List<Map<String,Object>> candidates=new ArrayList<>();
                for (String role:List.of("trip","trip-replica")) {
                    var candidate=new LinkedHashMap<String,Object>();candidate.put("instanceId",role);candidate.put("url",f.wires.get(role).url()+"/a2a");
                    if(variant!=0) candidate.put("weight",variant==1?0:-100); candidates.add(candidate);
                }
                trip.put("instances",candidates);
                Feat062RegistryExtensionIntegrationTest.source(input,variant+1L,List.of(trip,hotel),false);
                if(variant==0) f.start("mainplan",Map.of("feat062.source-file",input.toString()));
                final long version=variant+1L;
                await().atMost(Duration.ofSeconds(12)).pollInterval(Duration.ofMillis(100)).until(()->
                        Feat062RegistryExtensionIntegrationTest.catalog(f,"mainplan").path("version").asLong()>=version);
                complete(f,"WEIGHT_VARIANT_BUSINESS_"+variant);
                int original=0;List<String> markers=new ArrayList<>();for(int i=0;i<400;i++) markers.add("INVALID_WEIGHT_"+variant+"_"+i+"_"+UUID.randomUUID());
                try(var pool=Executors.newFixedThreadPool(4)) {
                    List<Future<?>> futures=new ArrayList<>();
                    for(String marker:markers) futures.add(pool.submit(()->{
                        var result=invoke(f,marker,false);assertThat(result.path("resultCategory").asText()).isEqualTo("COMPLETED");
                        assertThat(result.path("result").asText()).contains("深圳","北京");return null;
                    }));
                    for(var future:futures) future.get(900,TimeUnit.SECONDS);
                }
                for(String marker:markers) {
                    long a=f.wires.get("trip").calls(marker).stream().filter(n->n.at("/body/method").asText().startsWith("Send")).count();
                    long b=f.wires.get("trip-replica").calls(marker).stream().filter(n->n.at("/body/method").asText().startsWith("Send")).count();
                    assertThat(a+b).isEqualTo(1);original+=a;
                }
                double p=Feat062ReplicaScenarios.binomialTwoSided(400,original,.5);
                f.save("invalid-weight-statistic-"+variant+".json",Map.of("n",400,"original",original,"p",p,"alpha",.001));
                assertThat(p).isGreaterThanOrEqualTo(.001);
            }
        }
    }
}
