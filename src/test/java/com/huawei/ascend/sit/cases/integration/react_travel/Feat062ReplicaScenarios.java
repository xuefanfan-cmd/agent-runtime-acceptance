package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062Fixture.*;
import static com.huawei.ascend.sit.cases.integration.react_travel.Feat062RegistrationScenarios.complete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class Feat062ReplicaScenarios {
    static JsonNode candidates(Feat062Fixture f,String id) throws Exception {
        return f.registryJson("GET","/agents/"+id+"/instances",null).path("instances");
    }
    static void awaitCount(Feat062Fixture f,String id,int count) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).ignoreExceptions().untilAsserted(()->assertThat(candidates(f,id).size()).isEqualTo(count));
    }
    static void scaleOut() throws Exception {
        try(var f=new Feat062Fixture("dynamic")) {
            f.startTravel(false);complete(f,"BEFORE_SCALE");f.startReplica("trip","trip-replica");awaitCount(f,"travel-trip",2);
            for(int i=0;i<20;i++) {
                complete(f,"SCALE_OUT_"+i);
                if(sent(f,"trip-replica")>0&&sent(f,"trip")>1) break;
            }
            assertThat(sent(f,"trip-replica")).as("second actual process receives new business calls").isPositive();
            assertThat(sent(f,"trip")).isGreaterThan(1);
        }
    }
    static long sent(Feat062Fixture f,String role) {
        return f.wires.get(role).requests.stream().filter(n->n.at("/body/method").asText().startsWith("Send")).count();
    }
    static void noCrossDelete() throws Exception {
        for(String role:List.of("trip","hotel")) try(var f=new Feat062Fixture("dynamic")) {
            f.startTravel(false);complete(f,"BEFORE_REPLICA");String label=role+"-replica";
            f.startReplica(role,label);Set<String> identities=role.equals("trip")?Set.of("travel-trip"):Set.of("travel-hotel","travel-hotel-alt");
            Map<String,String> survivor=new LinkedHashMap<>();
            for(String id:identities) {
                awaitCount(f,id,2);
                for(JsonNode candidate:candidates(f,id)) {
                    var route=f.registryJson("POST","/route-handle:resolve",Map.of("routeHandle",candidate.path("routeHandle").asText()));
                    if(route.path("endpointUrl").asText().startsWith(f.wires.get(label).url())) survivor.put(id,candidate.path("instanceId").asText());
                }
            }
            int before=f.registry.requests.size();f.stacks.get(role).stop(role);
            for(String id:identities) {
                awaitCount(f,id,1);assertThat(candidates(f,id).get(0).path("instanceId").asText()).isEqualTo(survivor.get(id));
                assertThat(f.registry.requests.stream().skip(before).filter(n->n.path("httpMethod").asText().equals("DELETE")))
                        .noneMatch(n->n.path("path").asText().endsWith(survivor.get(id)));
            }
            // Wait for observed caller refresh, then require an entire fresh business result from the surviving topology.
            long start=System.nanoTime();await().atMost(Duration.ofSeconds(7)).pollInterval(Duration.ofMillis(100))
                    .until(()->System.nanoTime()-start>Duration.ofSeconds(5).toNanos());
            complete(f,"SURVIVOR_STILL_SERVES");assertThat(sent(f,label)).isPositive();
        }
    }
    static void crashStop() throws Exception {
        for(String role:List.of("trip","hotel")) try(var f=new Feat062Fixture("dynamic")) {
            f.startTravel(false);complete(f,"PRE_CRASH");f.startReplica(role,role+"-replica");
            String id=role.equals("trip")?"travel-trip":"travel-hotel";awaitCount(f,id,2);
            int before=f.registry.requests.size();int ttl=Feat062RegistrationScenarios.heartbeatTtl(f);
            var victim=(ManagedSutInstance)f.stacks.get(role).managedInstance(role);
            ProcessHandle.of(victim.pid()).orElseThrow().destroyForcibly();
            await().atMost(Duration.ofSeconds(ttl+5L)).pollInterval(Duration.ofMillis(100)).untilAsserted(()->assertThat(candidates(f,id).size()).isEqualTo(1));
            assertThat(f.registry.requests.stream().skip(before)).noneMatch(n->n.path("httpMethod").asText().equals("DELETE"));
            long removed=System.nanoTime();await().atMost(Duration.ofSeconds(7)).pollInterval(Duration.ofMillis(100))
                    .until(()->System.nanoTime()-removed>Duration.ofSeconds(5).toNanos());
            complete(f,"POST_CRASH_SURVIVOR");assertThat(sent(f,role+"-replica")).isPositive();
        }
    }
    static void distribution(boolean weighted) throws Exception {
        try(var f=new Feat062Fixture("dynamic")) {
            f.startTravel(false);f.startReplica("trip","trip-replica");awaitCount(f,"travel-trip",2);
            if(weighted) {
                for(String label:List.of("trip","trip-replica")) {
                    var body=f.registry.requests.stream().filter(n->n.path("httpMethod").asText().equals("POST")
                            &&n.path("path").asText().equals("/v2/agents/travel-trip/instances")
                            &&n.at("/body/port").asInt()==f.wires.get(label).port()).findFirst().orElseThrow().path("body").deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("weight",label.equals("trip")?300:100);
                    f.registryJson("POST","/agents/travel-trip/instances",body);
                }
                assertThat(candidates(f,"travel-trip").toString()).contains("300");
            }
            long ready=System.nanoTime();await().atMost(Duration.ofSeconds(7)).pollInterval(Duration.ofMillis(100))
                    .until(()->System.nanoTime()-ready>Duration.ofSeconds(5).toNanos());
            List<String> markers=new ArrayList<>();for(int i=0;i<400;i++) markers.add("WEIGHT_SAMPLE_"+i+"_"+UUID.randomUUID());
            Map<String,JsonNode> businessTasks = new ConcurrentHashMap<>();
            try(var pool=Executors.newFixedThreadPool(4)) {
                List<Future<?>> results=new ArrayList<>();
                for(String marker:markers) results.add(pool.submit(()->{
                    var call=f.send("mainplan","/a2a",f.prompt(marker),false,marker,null);
                    var task=Feat062DirectRegistryReactAgentTest.completed(f,call,"mainplan","/a2a");
                    businessTasks.put(marker,task);return null;
                }));
                for(var result:results) result.get(900,TimeUnit.SECONDS);
            }
            Map<String,String> businessLogs = new LinkedHashMap<>();
            for (String role : List.of("mainplan", "trip", "hotel")) businessLogs.put(role,
                    String.join("\n", java.nio.file.Files.readAllLines(f.evidence.resolve(role + ".log"))));
            for (String marker : markers) Feat062DirectRegistryReactAgentTest.assertChain(f,marker,businessTasks.get(marker),businessLogs);
            int original=0;List<Map<String,Object>> observations=new ArrayList<>();
            for(String marker:markers) {
                List<Map<String,Object>> attempts=new ArrayList<>();
                for(String label:List.of("trip","trip-replica")) for(JsonNode n:f.wires.get(label).calls(marker))
                    if(n.at("/body/method").asText().startsWith("Send")) attempts.add(Map.of("endpoint",label,"nanoTime",n.path("nanoTime").asLong()));
                assertThat(attempts).as("healthy sample must have exactly one attempt").hasSize(1);
                if(attempts.getFirst().get("endpoint").equals("trip")) original++;
                observations.add(Map.of("marker",marker,"attempt",attempts.getFirst()));
            }
            double p=weighted?.75:.5;double pvalue=binomialTwoSided(400,original,p);
            f.save("weight-statistic.json",Map.of("n",400,"originalCount",original,"p",p,"twoSidedP",pvalue,"alpha",.001,"samples",observations));
            assertThat(pvalue).as("predeclared exact two-sided binomial test").isGreaterThanOrEqualTo(.001);
            if(weighted) assertThat(candidates(f,"travel-trip").toString()).contains("300");
        }
    }
    static double binomialTwoSided(int n,int observed,double p) {
        double[] probabilities=new double[n+1]; probabilities[0]=Math.pow(1-p,n);
        for(int k=1;k<=n;k++) probabilities[k]=probabilities[k-1]*(n-k+1)/k*p/(1-p);
        double threshold=probabilities[observed]*(1+1e-10),sum=0;
        for(double value:probabilities) if(value<=threshold) sum+=value;
        return Math.min(1,sum);
    }
}
