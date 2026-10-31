package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Test-owned deployment and transparent observations; product routing stays in the real Runtime. */
final class Feat062Fixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String A2A = "openjiuwen.service.a2a.";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1).build();
    final String run = "f062-" + UUID.randomUUID().toString().substring(0, 12);
    final String tenant;
    final String mode;
    final Path evidence;
    final Path m2;
    final String token = UUID.randomUUID().toString();
    final List<Process> children = new CopyOnWriteArrayList<>();
    final Map<String, SutStack> stacks = new LinkedHashMap<>();
    final Map<String, Wire> wires = new LinkedHashMap<>();
    final Map<String, Wire> registryLinks = new LinkedHashMap<>();
    final Map<String, Integer> ports = new LinkedHashMap<>();
    final Map<String, List<String>> jvmArguments = new LinkedHashMap<>();
    final Map<String, List<JsonNode>> models = new ConcurrentHashMap<>();
    final List<String> fixtureErrors = new CopyOnWriteArrayList<>();
    final Model model;
    Wire registry;
    Process rdc;
    String rdcUrl;
    boolean namespaceCreated;
    boolean ownsRdc = true;
    final String inventoryCanary;
    boolean contractHost;
    boolean interruptTrip;
    boolean noExtension;
    String encryptedToken, cipherKey;
    final Map<String,String> rdcProperties;

    Feat062Fixture(String mode) throws Exception { this(mode, null, null); }
    Feat062Fixture(String mode, String sharedRdc, String sharedToken) throws Exception {
        this(mode, sharedRdc, sharedToken, Map.of());
    }
    Feat062Fixture(String mode, String sharedRdc, String sharedToken, Map<String,String> rdcProperties) throws Exception {
        this.mode = mode;
        this.rdcProperties = rdcProperties;
        tenant = run + "-" + mode;
        evidence = Path.of("target/feature-acceptance-testing/feat062/observations", run + "-" + mode).toAbsolutePath();
        Files.createDirectories(evidence);
        m2 = Path.of(Objects.requireNonNull(System.getenv("SUT_M2_REPO"), "SUT_M2_REPO required in WSL"));
        inventoryCanary = "F062_" + run.substring(5).replace("-", "");
        model = new Model();
        try {
            prepareInventory();
            if (!mode.equals("static")) {
                createNamespace();
                if (sharedRdc == null) startRdc();
                else { ownsRdc = false; rdcUrl = sharedRdc; }
                registry = new Wire(rdcUrl, "registry", sharedToken == null ? token : sharedToken);
                await().atMost(Duration.ofSeconds(100)).pollInterval(Duration.ofMillis(200))
                        .ignoreExceptions().until(() -> registryRequest("GET", "/agents", null).statusCode() == 200);
            }
        } catch (Exception e) { close(); throw e; }
    }
    void prepareInventory() throws Exception {
        Path resource = evidence.resolve("inventory/mock/hotels.json");
        Files.createDirectories(resource.getParent());
        try (ZipFile zip = new ZipFile(jar("hotel").toFile())) {
            JsonNode data = JSON.readTree(zip.getInputStream(zip.getEntry("BOOT-INF/classes/mock/hotels.json")));
            for (JsonNode city : data.path("cities")) for (JsonNode hotel : city.path("hotels")) {
                ((ObjectNode) hotel).put("chineseName", "嘉禾" + inventoryCanary + "商务酒店（" + hotel.path("hotelId").asText() + "店）");
            }
            save("inventory/mock/hotels.json", data);
        }
    }
    Path jar(String role) {
        if (noExtension) return Path.of("target/feature-acceptance-testing/feat062/noext/source/agent-" + role
                + "/target/travel-demo-" + role + "-0.1.2.jar").toAbsolutePath();
        return m2.resolve("com/openjiuwen/example/travel-demo-" + role + "/0.1.2/travel-demo-" + role + "-0.1.2.jar");
    }
    void createNamespace() throws Exception {
        var response = request("POST", "http://127.0.0.1:8848/nacos/v3/admin/core/namespace",
                "namespaceId=" + tenant + "&namespaceName=" + tenant + "&namespaceDesc=FEAT062-acceptance",
                Map.of("Content-Type", "application/x-www-form-urlencoded"));
        saveText("namespace-create.json", response.body());
        assertThat(response.statusCode()).as("Nacos create isolated namespace").isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("code").asInt()).isZero();
        namespaceCreated = true;
    }
    void startRdc() throws Exception {
        int port = port(); rdcUrl = "http://127.0.0.1:" + port;
        Path jar = m2.resolve("com/openjiuwen/registry-discovery-center/0.1.3/registry-discovery-center-0.1.3.jar");
        assertThat(jar).isRegularFile();
        var command = new ArrayList<>(List.of(java(), "-Xms64m", "-Xmx512m", "-Dfile.encoding=UTF-8", "-jar", jar.toString(),
                "--server.port=" + port, "--openjiuwen.registry.backend=nacos",
                "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"));
        command.add("--openjiuwen.registry.api.rate-limit.limit-per-minute=10000");
        rdcProperties.forEach((key,value) -> command.add("--" + key + "=" + value));
        var pb = new ProcessBuilder(command).directory(evidence.toFile()).redirectErrorStream(true)
                .redirectOutput(evidence.resolve("rdc.log").toFile());
        pb.environment().put("RDC_REGISTRY_API_TOKEN", token);
        pb.environment().put("RDC_NACOS_SERVER_ADDR", "127.0.0.1:8848");
        pb.environment().put("RDC_ROUTE_HANDLE_SECRET", UUID.randomUUID() + "-" + UUID.randomUUID());
        rdc = pb.start(); children.add(rdc);
        save("rdc-process.json", Map.of("pid", rdc.pid(), "jar", jar.toString(), "port", port));
    }
    void startTravel(boolean live) throws Exception {
        model.live = live;
        for (String role : List.of("hotel", "trip")) start(role, Map.of());
        if (!mode.equals("static")) awaitRegistry();
        start("mainplan", Map.of());
        if (!mode.equals("static")) {
            // Observe caller consumption, rather than treating RDC readiness as caller readiness.
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).until(() ->
                    modelsReady() || registry.requests.stream().anyMatch(n -> n.path("path").asText().equals("/v2/route-handle:resolve")));
        }
    }
    boolean modelsReady() { return false; }
    SutStack start(String role, Map<String, String> overrides) throws Exception {
        int p = ports.computeIfAbsent(role, x -> port());
        Wire wire = wires.computeIfAbsent(role, x -> {
            try { return new Wire("http://127.0.0.1:" + p, role, null); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
        String registryBase = "http://127.0.0.1:1";
        if (registry != null) {
            Wire link = registryLinks.computeIfAbsent(role, x -> {
                try { return new Wire(rdcUrl, "registry-" + role, registry.expectedToken); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
            registryBase = link.url();
        }
        final String registryEndpoint = registryBase;
        Map<String,Object> agent = new LinkedHashMap<>();
        agent.put("group", "com.openjiuwen.example"); agent.put("artifact", "travel-demo-" + role);
        agent.put("version", "0.1.2"); agent.put("port", p); agent.put("remote-agents-prefix", A2A + "remote-agents");
        var config = TestConfig.fromMap(Map.of("sut", Map.of("m2", Map.of("repo", m2.toString()),
                "agents", Map.of(role, agent), "timeout", Map.of("startup-seconds", 120))));
        SutStack stack = SutStack.builder(config).launcher((sut, options) -> {
            try { return launch(role, options); }
            catch (Exception e) { throw new IllegalStateException("Fixture launch " + role, e); }
        }).agent(role, a -> {
            a.profile(mode + (contractHost ? ",feat062-contract" : "")).property(A2A + "public-url", wire.url())
                    .property(A2A + "registry.base-url", registryEndpoint)
                    .property(A2A + "registry.tenant-id", tenant)
                    .property(A2A + "registry.registration.port", String.valueOf(wire.port()))
                    .property("logging.file.name", evidence.resolve(role + "-application.log").toString())
                    .property("openjiuwen.travel." + role + ".llm.api-base", model.base(role))
                    .property("openjiuwen.travel." + role + ".llm.max-iterations", "12")
                    .env("RDC_TOKEN", encryptedToken != null ? encryptedToken : registry == null ? token : registry.expectedToken);
            if (encryptedToken != null) a.property("feat062.decrypt-token", "true").env("FEAT062_CIPHER_KEY", cipherKey);
            if (interruptTrip && role.equals("trip")) a.property("feat062.interrupt-trip", "true");
            if (role.equals("mainplan")) {
                a.env("TRAVEL_TRIP_A2A_URL", wires.get("trip").url())
                        .env("TRAVEL_HOTEL_A2A_URL", wires.get("hotel").url() + "/a2a/agents/travel-hotel");
            }
            overrides.forEach(a::property);
        }).start();
        stacks.put(role, stack); return stack;
    }
    ManagedSutInstance launch(String role, AgentConfig options) throws Exception {
        return launch(role, role, options);
    }
    ManagedSutInstance launch(String role, String label, AgentConfig options) throws Exception {
        Path log = evidence.resolve(label + ".log");
        var command = new ArrayList<>(List.of(java(), "-Xms64m", "-Xmx512m", "-Dfile.encoding=UTF-8"));
        command.addAll(jvmArguments.getOrDefault(role, List.of()));
        if (role.equals("hotel") || contractHost) command.addAll(List.of("-Dloader.path=" +
                (contractHost ? compileHost(role) + "," : "") + evidence.resolve("inventory"),
                "-cp", jar(role).toString(), "org.springframework.boot.loader.launch.PropertiesLauncher"));
        else command.addAll(List.of("-jar", jar(role).toString()));
        command.addAll(options.toProgramArgs());
        var pb = new ProcessBuilder(command).directory(evidence.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        options.environment().forEach(pb.environment()::put);
        Process process = pb.start(); children.add(process);
        save(label + "-process.json", Map.of("pid", process.pid(), "jar", jar(role).toString(), "port", options.port(),
                "profile", options.profile(), "externalInventory", role.equals("hotel")));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException(role + " exited " + process.exitValue() + "; " + log);
            try {
                if (request("GET", "http://127.0.0.1:" + options.port() + "/.well-known/agent-card.json", null, Map.of()).statusCode() == 200)
                    return new ManagedSutInstance(label, process.pid(), options.port(), "http://127.0.0.1:" + options.port(), process, log);
            } catch (IOException ignored) { /* bounded readiness polling */ }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new IllegalStateException(role + " startup watchdog; " + log);
    }
    void startReplica(String role, String label) throws Exception {
        int p = ports.computeIfAbsent(label, ignored -> port());
        Wire wire = wires.computeIfAbsent(label, ignored -> {
            try { return new Wire("http://127.0.0.1:" + p, label, null); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
        Map<String,Object> agent = Map.of("group", "com.openjiuwen.example", "artifact", "travel-demo-" + role,
                "version", "0.1.2", "port", p, "remote-agents-prefix", A2A + "remote-agents");
        var config = TestConfig.fromMap(Map.of("sut",Map.of("m2",Map.of("repo",m2.toString()),"agents",Map.of(label,agent))));
        var stack = SutStack.builder(config).launcher((sut, options) -> {
            try { return launch(role,label,options); } catch(Exception e) { throw new IllegalStateException("replica launch",e); }
        }).agent(label, a -> a.profile(mode + (contractHost ? ",feat062-contract" : ""))
                .property(A2A+"public-url",wire.url()).property(A2A+"registry.base-url",registry==null?"http://127.0.0.1:1":registry.url())
                .property(A2A+"registry.tenant-id",tenant).property(A2A+"registry.registration.enabled",registry==null?"false":"true")
                .property(A2A+"registry.discovery.enabled","false").property(A2A+"registry.registration.port",String.valueOf(wire.port()))
                .property("openjiuwen.travel."+role+".llm.api-base",model.base(role))
                .property("logging.file.name",evidence.resolve(label+"-application.log").toString())
                .env("RDC_TOKEN",registry==null?token:registry.expectedToken)).start();
        stacks.put(label,stack);
    }
    void awaitRegistry() {
        Set<String> expected = dynamicAgents();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).ignoreExceptions().untilAsserted(() -> {
            JsonNode directory = registryJson("GET", "/agents", null);
            Set<String> ids = new HashSet<>(); directory.path("agents").forEach(n -> ids.add(n.path("agentId").asText()));
            assertThat(ids).containsExactlyInAnyOrderElementsOf(expected);
            for (String id : expected) assertThat(registryJson("GET", "/agents/" + id + "/instances", null).path("instances").size()).isEqualTo(1);
        });
    }
    Path compileHost(String role) throws Exception {
        Path root = Path.of("target/feature-acceptance-testing/feat062/host", role).toAbsolutePath();
        Path output = root.resolve("classes"); Path marker = output.resolve("com/openjiuwen/example/travel/" + role + "/Feat062ContractConfiguration.class");
        Path libraries = root.resolve("dependencies"), bootClasses = root.resolve("demo-classes");
        Files.createDirectories(libraries); Files.createDirectories(bootClasses); Files.createDirectories(output);
        Path imports = output.resolve("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        Files.createDirectories(imports.getParent());
        Files.writeString(imports, "com.openjiuwen.example.travel." + role + ".Feat062ContractConfiguration\n");
        try (ZipFile zip = new ZipFile(jar(role).toFile())) {
            for (var entry : Collections.list(zip.entries())) {
                Path dest = entry.getName().startsWith("BOOT-INF/lib/") ? libraries.resolve(entry.getName().substring(13))
                        : entry.getName().startsWith("BOOT-INF/classes/") ? bootClasses.resolve(entry.getName().substring(17)) : null;
                if (dest == null || entry.isDirectory()) continue;
                Files.createDirectories(dest.getParent()); if (!Files.isRegularFile(dest))
                    try (var in = zip.getInputStream(entry)) { Files.copy(in, dest); }
            }
        }
        // Reuse the demo's real user-input Tool/Rail as business input for the leaf interruption case.
        try (ZipFile zip = new ZipFile(jar("mainplan").toFile())) {
            for (var entry : Collections.list(zip.entries())) {
                if (!entry.getName().startsWith("BOOT-INF/classes/com/openjiuwen/example/travel/mainplan/tools/RequestUserInputTool")
                        && !entry.getName().startsWith("BOOT-INF/classes/com/openjiuwen/example/travel/mainplan/rails/UserInputInterruptRail")) continue;
                if (entry.isDirectory()) continue;
                for (Path base : List.of(bootClasses, output)) {
                    Path dest = base.resolve(entry.getName().substring(17)); Files.createDirectories(dest.getParent());
                    try (var in = zip.getInputStream(entry)) { Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING); }
                }
            }
        }
        String template;
        try (var in = Feat062Fixture.class.getResourceAsStream("/testdata/feat062/Feat062ContractConfiguration.java.template")) {
            template = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8).replace("travel.ROLE", "travel." + role);
        }
        String hosted="";
        if(role.equals("hotel")) try(var in=Feat062Fixture.class.getResourceAsStream("/testdata/feat062/hosted-caller.java.fragment")) {
            hosted=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        }
        template = template.replace("HOSTED_CONFIGURATION",hosted);
        Path source = root.resolve("Feat062ContractConfiguration.java");
        if (Files.isRegularFile(marker) && Files.isRegularFile(source) && Files.readString(source).equals(template)) return output;
        Files.writeString(source, template);
        String cp;
        try (var files = Files.list(libraries)) { cp = bootClasses + File.pathSeparator + String.join(File.pathSeparator, files.map(Path::toString).toList()); }
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (var log = Files.newOutputStream(root.resolve("compile.log"))) {
            int result = compiler.run(null, log, log, "-encoding", "UTF-8", "-cp", cp, "-d", output.toString(), source.toString());
            if (result != 0) throw new IllegalStateException("contract adapter compilation; " + root.resolve("compile.log"));
        }
        return output;
    }
    record Startup(boolean exited, int exitCode, boolean ready, Path log, String diagnostic) { }
    Startup probeStartup(String role, Map<String,String> overrides) throws Exception {
        int p = port(); String key = role + "-probe-" + UUID.randomUUID(); Path log = evidence.resolve(key + ".log");
        Map<String,String> properties = new LinkedHashMap<>();
        properties.put("server.port", String.valueOf(p)); properties.put("spring.profiles.active", mode + (contractHost ? ",feat062-contract" : ""));
        properties.put(A2A + "public-url", "http://127.0.0.1:" + p);
        properties.put(A2A + "registry.base-url", registry == null ? "http://127.0.0.1:1" : registry.url());
        properties.put(A2A + "registry.tenant-id", tenant);
        properties.put(A2A + "registry.registration.address", "127.0.0.1");
        properties.put(A2A + "registry.registration.port", String.valueOf(p));
        properties.put("openjiuwen.travel." + role + ".llm.api-base", model.base(role));
        properties.put("logging.file.name", evidence.resolve(key + "-application.log").toString());
        properties.putAll(overrides);
        var command = new ArrayList<>(List.of(java(), "-Xms64m", "-Xmx512m"));
        if(contractHost) command.addAll(List.of("-Dloader.path="+compileHost(role),"-cp",jar(role).toString(),"org.springframework.boot.loader.launch.PropertiesLauncher"));
        else command.addAll(List.of("-jar",jar(role).toString()));
        properties.forEach((k,v) -> command.add("--" + k + "=" + v));
        var pb = new ProcessBuilder(command).directory(evidence.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().put("RDC_TOKEN", registry == null ? token : registry.expectedToken);
        Process process = pb.start(); children.add(process);
        boolean ready = false; long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        try {
            while (process.isAlive() && System.nanoTime() < until) {
                try { ready = request("GET", "http://127.0.0.1:" + p + "/.well-known/agent-card.json", null, Map.of()).statusCode() == 200; }
                catch (IOException ignored) { /* not ready yet */ }
                // The card endpoint may open before startup registration finishes and rejects configuration.
                // Negative probes must observe natural process exit, not terminate on this transient window.
                TimeUnit.MILLISECONDS.sleep(100);
            }
            ready = ready && process.isAlive();
            String diagnostic = Files.readString(log);
            Startup outcome = new Startup(!process.isAlive(), process.isAlive() ? -1 : process.exitValue(), ready, log, diagnostic);
            save(key + "-startup.json", Map.of("exited", outcome.exited(), "exitCode", outcome.exitCode(), "ready", ready, "log", log.toString()));
            return outcome;
        } finally { if (process.isAlive()) { process.destroy(); if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly(); } }
    }
    Set<String> dynamicAgents() {
        return switch(mode) {
            case "dynamic" -> Set.of("travel-trip", "travel-hotel", "travel-hotel-alt");
            case "mixed-trip-static" -> Set.of("travel-hotel", "travel-hotel-alt");
            case "mixed-hotel-static" -> Set.of("travel-trip");
            default -> Set.of();
        };
    }
    HttpResponse<String> registryRequest(String method, String path, Object data) throws Exception {
        String body = data == null ? null : JSON.writeValueAsString(data);
        return request(method, rdcUrl + "/v2" + path, body,
                Map.of("Authorization", "Bearer " + (registry == null ? token : registry.expectedToken), "X-Tenant-ID", tenant, "Content-Type", "application/json"));
    }
    JsonNode registryJson(String method, String path, Object data) throws Exception {
        var r = registryRequest(method, path, data);
        assertThat(r.statusCode()).as("RDC %s %s", method, path).isEqualTo(200);
        JsonNode json = JSON.readTree(r.body()); save("rdc-" + UUID.randomUUID() + ".json", redact(json)); return json;
    }
    Call send(String role, String endpoint, String input, boolean streaming, String context, String task) throws Exception {
        var message = new LinkedHashMap<String,Object>();
        message.put("role", "ROLE_USER"); message.put("messageId", UUID.randomUUID().toString());
        message.put("contextId", context); message.put("parts", List.of(Map.of("text", input)));
        if (task != null) message.put("taskId", task);
        var params = Map.of("message", message);
        return rpc(role, endpoint, streaming ? "SendStreamingMessage" : "SendMessage", params);
    }
    Call rpc(String role, String endpoint, String method, Object params) throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String,Object> body = Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
        save("request-" + id + ".json", body);
        var response = request("POST", wires.get(role).url() + endpoint, JSON.writeValueAsString(body),
                Map.of("Content-Type", "application/json", "Accept", method.equals("SendStreamingMessage") ? "text/event-stream" : "application/json"));
        saveText("response-" + id + (method.equals("SendStreamingMessage") ? ".sse" : ".json"), response.body());
        List<JsonNode> frames = new ArrayList<>();
        if (method.equals("SendStreamingMessage")) {
            for (String line : response.body().split("\\R")) if (line.startsWith("data:")) {
                String data = line.substring(5).trim(); if (!data.isEmpty() && !data.equals("[DONE]")) frames.add(JSON.readTree(data));
            }
        } else frames.add(JSON.readTree(response.body()));
        return new Call(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), List.copyOf(frames), response.body());
    }
    record Call(int status, String contentType, List<JsonNode> frames, String raw) {
        JsonNode task() {
            for (JsonNode frame : frames) if (frame.path("result").has("task")) return frame.path("result").path("task");
            return JSON.createObjectNode();
        }
    }
    String prompt(String marker) {
        return marker + " 出差人张三，深圳到北京，2026-11-10入住，2026-11-12退房，三天两晚，"
                + "每晚预算人民币900元，最低四星，不限品牌和区域。仅查询不预订。"
                + "请实际先调用travel-trip再调用travel-hotel，收到两者结果后汇总，并原样保留酒店名称中的F062标记。";
    }
    List<JsonNode> modelRequests(String role, String marker) {
        return models.getOrDefault(role, List.of()).stream().filter(n -> n.path("messages").toString().contains(marker)).toList();
    }
    List<JsonNode> toolResults(String role, String marker, String name) {
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode req : modelRequests(role, marker)) {
            Set<String> ids = new HashSet<>();
            for (JsonNode msg : req.path("messages")) for (JsonNode call : msg.path("tool_calls"))
                if (call.at("/function/name").asText().equals(name)) ids.add(call.path("id").asText());
            for (JsonNode msg : req.path("messages")) if (msg.path("role").asText().equals("tool") && ids.contains(msg.path("tool_call_id").asText())) values.add(msg);
        }
        return values;
    }
    void assertHealthyFixture() { assertThat(fixtureErrors).as("transparent recorder errors").isEmpty(); }
    static synchronized int port() {
        // Coordinate all test JVMs and keep delayed process listeners outside the ephemeral client range.
        Path state = Path.of(System.getProperty("java.io.tmpdir"), "feat062-listener-ports.state");
        try (var file = java.nio.channels.FileChannel.open(state, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE); var lock = file.lock()) {
            var buffer = java.nio.ByteBuffer.allocate(4);
            int next = file.read(buffer) == 4 ? buffer.flip().getInt() : 10000;
            for (int i = 0; i < 18000; i++) {
                int candidate = next; next = next >= 27999 ? 10000 : next + 1;
                try (var socket = new ServerSocket()) {
                    socket.bind(new InetSocketAddress("127.0.0.1", candidate));
                    buffer.clear().putInt(next).flip(); file.position(0); file.write(buffer); file.truncate(4);
                    return candidate;
                } catch (BindException occupied) { /* inspect next listener port */ }
            }
            throw new IOException("No free FEAT062 listener port in [10000,27999]");
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    static String java() { return Path.of(System.getProperty("java.home"), "bin/java").toString(); }
    static HttpResponse<String> request(String method, String url, String body, Map<String,String> headers) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(method.equals("GET") ? 5 : 300));
        headers.forEach(req::header);
        return HTTP.send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    void save(String name, Object value) throws IOException { saveText(name, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    void saveText(String name, String value) throws IOException {
        Path path = evidence.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, value);
    }
    static JsonNode redact(JsonNode original) {
        JsonNode node = original.deepCopy();
        redactInPlace(node); return node;
    }
    static void redactInPlace(JsonNode node) {
        if (node.isObject()) for (String key : List.of("routeHandle", "token", "routeHandleSecret"))
            if (node.has(key)) ((ObjectNode) node).put(key, "<redacted>");
        node.forEach(Feat062Fixture::redactInPlace);
    }
    final class Wire implements AutoCloseable {
        final HttpServer server;
        final ExecutorService threads = Executors.newCachedThreadPool();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final String target, name, expectedToken;
        final AtomicInteger sequence = new AtomicInteger();
        volatile boolean unavailable;
        volatile long rpcDelayMillis;
        volatile String rpcResponse;
        volatile int heartbeatStatus;
        volatile boolean truncateStream;
        volatile boolean requireClientCertificate;
        volatile String callerRef;
        final boolean secure;
        volatile Consumer<JsonNode> transform = node -> { };
        Wire(String target, String name, String expectedToken) throws IOException {
            this(target, name, expectedToken, null);
        }
        Wire(String target, String name, String expectedToken, javax.net.ssl.SSLContext tls) throws IOException {
            this.target = target; this.name = name; this.expectedToken = expectedToken;
            secure = tls != null;
            if (tls == null) server = HttpServer.create(new InetSocketAddress("127.0.0.1", Feat062Fixture.port()), 0);
            else {
                var https = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", Feat062Fixture.port()), 0);
                https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls) {
                    public void configure(com.sun.net.httpserver.HttpsParameters parameters) {
                        var settings = getSSLContext().getDefaultSSLParameters();
                        settings.setNeedClientAuth(requireClientCertificate); parameters.setSSLParameters(settings);
                    }
                }); server = https;
            }
            if (URI.create(target).getPort() == server.getAddress().getPort()) {
                server.stop(0); threads.shutdownNow();
                throw new BindException("Recorder must not forward to its own listener: " + name);
            }
            server.setExecutor(threads);
            server.createContext("/", this::handle); server.start();
        }
        int port() { return server.getAddress().getPort(); }
        String url() { return (secure ? "https" : "http") + "://127.0.0.1:" + port(); }
        void handle(HttpExchange x) throws IOException {
            int index = sequence.incrementAndGet();
            try (x) {
                byte[] bytes = x.getRequestBody().readAllBytes();
                ObjectNode observation = JSON.createObjectNode().put("path", x.getRequestURI().toString())
                        .put("httpMethod", x.getRequestMethod()).put("nanoTime", System.nanoTime());
                if (x instanceof com.sun.net.httpserver.HttpsExchange https) {
                    try { observation.put("tlsPeerPrincipal", https.getSSLSession().getPeerPrincipal().getName()); }
                    catch (javax.net.ssl.SSLPeerUnverifiedException anonymousCardFetch) { observation.put("tlsPeerPrincipal", "anonymous"); }
                }
                if (bytes.length > 0) observation.set("body", redact(JSON.readTree(bytes)));
                if (expectedToken != null) {
                    observation.put("authorizationMatches", ("Bearer " + expectedToken).equals(x.getRequestHeaders().getFirst("Authorization")));
                    observation.put("tenant", x.getRequestHeaders().getFirst("X-Tenant-ID"));
                }
                requests.add(observation); save(name + "/wire-" + index + "-request.json", observation);
                if (expectedToken != null && registry != null && registry != this) registry.requests.add(observation);
                if (unavailable || expectedToken != null && registry != null && registry != this && registry.unavailable) {
                    x.sendResponseHeaders(503, -1); return;
                }
                if (heartbeatStatus > 0 && x.getRequestURI().getPath().endsWith("/heartbeat")) {
                    observation.put("injectedStatus", heartbeatStatus);
                    save(name + "/wire-" + index + "-request.json", observation);
                    byte[] limited = "{\"code\":\"RATE_LIMITED\",\"message\":\"controlled rate limit\"}".getBytes(StandardCharsets.UTF_8);
                    x.getResponseHeaders().set("Content-Type", "application/json");
                    x.sendResponseHeaders(heartbeatStatus, limited.length); x.getResponseBody().write(limited); return;
                }
                boolean businessRpc = expectedToken == null && observation.at("/body/method").asText().startsWith("Send");
                if (businessRpc && rpcDelayMillis > 0) TimeUnit.MILLISECONDS.sleep(rpcDelayMillis);
                if (businessRpc && rpcResponse != null) {
                    byte[] data = rpcResponse.replace("$ID", observation.at("/body/id").asText()).getBytes(StandardCharsets.UTF_8);
                    x.getResponseHeaders().set("Content-Type", "application/json");
                    x.sendResponseHeaders(200, data.length); x.getResponseBody().write(data); return;
                }
                var forward = HttpRequest.newBuilder(URI.create(target + x.getRequestURI()))
                        .timeout(Duration.ofSeconds(x.getRequestMethod().equals("GET") ? 5 : 290));
                for (String header : List.of("Content-Type", "Accept", "Authorization", "X-Tenant-ID", "X-Caller-Ref")) {
                    String value = x.getRequestHeaders().getFirst(header); if (value != null) forward.header(header, value);
                }
                if (callerRef != null) { forward.setHeader("X-Caller-Ref", callerRef); observation.put("testCallerHeaderAdded", true); }
                var reply = HTTP.send(forward.method(x.getRequestMethod(), bytes.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofInputStream());
                observation.put("httpStatus", reply.statusCode()).put("responseNanoTime", System.nanoTime());
                save(name + "/wire-" + index + "-request.json", observation);
                String contentType = reply.headers().firstValue("Content-Type").orElse("application/json");
                x.getResponseHeaders().set("Content-Type", contentType);
                x.sendResponseHeaders(reply.statusCode(), 0);
                try (InputStream in = reply.body(); OutputStream out = x.getResponseBody();
                     OutputStream evidenceOut = Files.newOutputStream(evidence.resolve(name + "/wire-" + index + "-response.txt"))) {
                    if (x.getRequestMethod().equals("GET") && expectedToken == null) {
                        JsonNode card = JSON.readTree(in.readAllBytes()); transform.accept(card);
                        byte[] data = JSON.writeValueAsBytes(card); evidenceOut.write(data); out.write(data);
                    } else if (expectedToken != null) {
                        byte[] data = in.readAllBytes(); JsonNode json = JSON.readTree(data);
                        if (json != null) evidenceOut.write(JSON.writeValueAsBytes(redact(json)));
                        out.write(data);
                    } else {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = in.read(buffer)) >= 0) { out.write(buffer, 0, count); out.flush(); evidenceOut.write(buffer, 0, count);
                            if (businessRpc && truncateStream && contentType.contains("text/event-stream")) break;
                        }
                    }
                }
            } catch (Exception e) {
                if (!(e instanceof IOException && (rpcDelayMillis > 0 || truncateStream)))
                    fixtureErrors.add(name + ":" + e.getClass().getName());
            }
        }
        List<JsonNode> calls(String marker) { return requests.stream().filter(n -> n.path("body").toString().contains(marker)).toList(); }
        public void close() { server.stop(0); threads.shutdownNow(); }
    }
    final class Model implements AutoCloseable {
        final HttpServer server;
        final ExecutorService threads = Executors.newCachedThreadPool();
        final AtomicInteger sequence = new AtomicInteger();
        volatile boolean live = true;
        Model() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", Feat062Fixture.port()), 0); server.setExecutor(threads);
            server.createContext("/", this::handle); server.start();
        }
        String base(String role) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + role + "/v1"; }
        void handle(HttpExchange x) throws IOException {
            try (x) {
                String[] parts = x.getRequestURI().getPath().split("/", 3); String role = parts[1];
                byte[] bytes = x.getRequestBody().readAllBytes(); JsonNode req = JSON.readTree(bytes);
                models.computeIfAbsent(role, ignored -> new CopyOnWriteArrayList<>()).add(req);
                int index = sequence.incrementAndGet(); save("model/" + role + "-" + index + "-request.json", req);
                if (!live || "true".equals(System.getenv("FEAT062_CONTROLLED_INPUTS"))) { deterministic(x, req, role); return; }
                var request = HttpRequest.newBuilder(URI.create(System.getenv("LLM_API_BASE").replaceAll("/+$", "") + parts[2].replaceFirst("^v1", "")))
                        .timeout(Duration.ofSeconds(280)).header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + Objects.requireNonNull(System.getenv("LLM_API_KEY")))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
                var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                save("model/" + role + "-" + index + "-status.json", Map.of("httpStatus", response.statusCode()));
                x.getResponseHeaders().set("Content-Type", response.headers().firstValue("Content-Type").orElse("application/json"));
                x.sendResponseHeaders(response.statusCode(), 0);
                try (var in = response.body(); var out = x.getResponseBody()) { byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) >= 0) { out.write(buffer, 0, n); out.flush(); }
                }
            } catch (Exception e) { fixtureErrors.add("model:" + e.getClass().getName()); }
        }
        void deterministic(HttpExchange x, JsonNode req, String role) throws Exception {
            String messages = req.path("messages").toString();
            Set<String> completed = new LinkedHashSet<>(); StringBuilder results = new StringBuilder();
            for (JsonNode msg : req.path("messages")) {
                if (msg.path("role").asText().equals("tool")) {
                    results.append(msg.path("content").asText());
                    String id = msg.path("tool_call_id").asText();
                    for (JsonNode assistant : req.path("messages")) for (JsonNode call : assistant.path("tool_calls"))
                        if (call.path("id").asText().equals(id)) completed.add(call.at("/function/name").asText());
                }
            }
            Map<String,JsonNode> tools = new LinkedHashMap<>();
            req.path("tools").forEach(n -> tools.put(n.at("/function/name").asText(), n));
            String user = ""; for (JsonNode msg : req.path("messages"))
                if (msg.path("role").asText().equals("user")) user = msg.path("content").asText();
            String name = null; Map<String,Object> args = new LinkedHashMap<>();
            if (role.equals("mainplan")) {
                name = tools.containsKey("feat062-unavailable-probe") && !tools.containsKey("travel-trip")
                        && !completed.contains("feat062-unavailable-probe") ? "feat062-unavailable-probe"
                        : !completed.contains("travel-trip") && tools.containsKey("travel-trip") ? "travel-trip"
                        : !completed.contains("travel-hotel") && tools.containsKey("travel-hotel") ? "travel-hotel" : null;
                args.put("remoteInput", user);
            } else if (role.equals("trip") && messages.contains("DOWNSTREAM_WAIT_") && !messages.contains("补充日期")) {
                name = "request_user_input"; args.put("follow_up_message", "请补充出发与返程日期。");
            } else if (role.equals("hotel") && messages.contains("HOSTED_CALL_TRIP_")
                    && tools.containsKey("travel-trip") && !completed.contains("travel-trip")) {
                name="travel-trip"; args.put("remoteInput",user);
            } else if (role.equals("hotel") && !completed.contains("hotel_search")) {
                name = "hotel_search"; args.putAll(Map.of("cityName", "北京", "checkIn", "2026-11-10",
                        "checkOut", "2026-11-12", "maxPricePerNight", 900, "minStar", 4));
            }
            ObjectNode message = JSON.createObjectNode().put("role", "assistant");
            if (name != null && tools.containsKey(name)) {
                message.putNull("content"); var call = message.putArray("tool_calls").addObject();
                call.put("id", "call-" + UUID.randomUUID()).put("type", "function");
                call.putObject("function").put("name", name).put("arguments", JSON.writeValueAsString(args));
            } else message.put("content", role.equals("trip") ? user + " 已完成深圳至北京三天行程安排：首日出行、第二日商务工作、第三日返程。"
                    : "完整差旅规划：" + results);
            ObjectNode response = JSON.createObjectNode().put("id", "chatcmpl-" + UUID.randomUUID())
                    .put("model", "feat062-controlled-input").put("created", 1);
            String finish = message.has("tool_calls") ? "tool_calls" : "stop";
            String body;
            if (req.path("stream").asBoolean()) {
                response.put("object", "chat.completion.chunk");
                var choice = response.putArray("choices").addObject().put("index", 0);
                if (message.has("tool_calls")) ((ObjectNode) message.path("tool_calls").get(0)).put("index", 0);
                choice.set("delta", message); choice.putNull("finish_reason");
                ObjectNode last = response.deepCopy();
                ((ObjectNode) last.path("choices").get(0)).set("delta", JSON.createObjectNode());
                ((ObjectNode) last.path("choices").get(0)).put("finish_reason", finish);
                body = "data: " + response + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n";
                x.getResponseHeaders().set("Content-Type", "text/event-stream");
            } else {
                response.put("object", "chat.completion");
                var choice = response.putArray("choices").addObject().put("index", 0).put("finish_reason", finish);
                choice.set("message", message); body = response.toString();
                x.getResponseHeaders().set("Content-Type", "application/json");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes);
        }
        public void close() { server.stop(0); threads.shutdownNow(); }
    }
    @Override public void close() {
        var reverse = new ArrayList<>(stacks.values()); Collections.reverse(reverse); reverse.forEach(SutStack::close);
        for (Process child : children) if (child.isAlive()) {
            child.destroy(); try { if (!child.waitFor(15, TimeUnit.SECONDS)) child.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); child.destroyForcibly(); }
        }
        wires.values().forEach(Wire::close); registryLinks.values().forEach(Wire::close); if (registry != null) registry.close(); model.close();
        if (namespaceCreated) try {
            var response = request("DELETE", "http://127.0.0.1:8848/nacos/v3/admin/core/namespace?namespaceId=" + tenant, null, Map.of());
            saveText("namespace-delete.json", response.body());
        } catch (Exception e) { fixtureErrors.add("namespace-cleanup:" + e.getClass().getName()); }
    }
}
