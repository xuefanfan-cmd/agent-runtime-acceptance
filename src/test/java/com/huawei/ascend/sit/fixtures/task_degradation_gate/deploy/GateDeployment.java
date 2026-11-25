package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.CorrelatedModelStub;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.DaEvidenceClient;
import com.huawei.ascend.sit.fixtures.task_degradation_gate.drive.GateDriver;
import com.huawei.ascend.sit.judge.EvidenceChecks.EvidenceSource;
import com.huawei.ascend.sit.judge.Scenario;
import com.huawei.ascend.sit.lifecycle.AgentConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One owned FEAT-112 carrier process in a declared {@link DeploymentForm}: the model double it talks to, the
 * SutStack-managed DA, its readiness and its artifact identity. Remote instances are refused: every FEAT-112
 * scenario depends on gate configuration, restart or process isolation that only an owned process can guarantee.
 */
public final class GateDeployment implements AutoCloseable {
    public static final String AGENT = "feat112";
    /** Second YAML entry with the same coordinates, so two instances never share a launcher log or port lookup. */
    public static final String PEER_AGENT = "feat112-peer";
    private static final String MODEL_BASE_URL = "deepanalyze.model.backend.base_url";
    private static final String ENGINE_SECRET = "deepanalyze.auth.engine-secret";
    private static final String CHECKPOINTER = "openjiuwen.service.middleware.checkpointer.type";

    /** Model double running and stack described; not launched yet. */
    public static final class Prepared implements AutoCloseable {
        private final DeploymentForm form;
        private final String agent;
        private final Feat112Artifacts artifacts;
        private final ModelStubProcess faultStub;
        private final CorrelatedModelStub correlated;
        private final String secret;
        private final SutStack.Builder builder;
        private final Environment environment;

        private Prepared(DeploymentForm form, String agent, Feat112Artifacts artifacts, ModelStubProcess faultStub,
                         CorrelatedModelStub correlated, String secret, SutStack.Builder builder, Environment environment) {
            this.form = form;
            this.agent = agent;
            this.artifacts = artifacts;
            this.faultStub = faultStub;
            this.correlated = correlated;
            this.secret = secret;
            this.builder = builder;
            this.environment = environment;
        }

        public SutStack.Builder builder() {
            return builder;
        }

        @Override
        public void close() throws Exception {
            Exception primary = null;
            if (faultStub != null) {
                try { faultStub.close(); } catch (Exception e) { primary = e; }
            }
            if (correlated != null) {
                try { correlated.close(); } catch (Exception e) { if (primary == null) primary = e; else primary.addSuppressed(e); }
            }
            try { environment.close(); } catch (Exception e) { if (primary == null) primary = e; else primary.addSuppressed(e); }
            if (primary != null) throw primary;
        }
    }

    /** Test-owned environment declared by the form ({@link DeploymentForm.Infra}); absent members are null. */
    static final class Environment implements AutoCloseable {
        RedisCheckpointStore redis;
        PostgresDataPlane postgres;
        ClockControl clock;

        static Environment start(TestConfig config, DeploymentForm form) throws Exception {
            var environment = new Environment();
            try {
                if (form.needs(DeploymentForm.Infra.CLOCK_CONTROL)) environment.clock = ClockControl.prepare(config, form.name());
                if (form.needs(DeploymentForm.Infra.REDIS_CHECKPOINTER)) environment.redis = RedisCheckpointStore.start(config, form.name());
                if (form.needs(DeploymentForm.Infra.POSTGRES_DATA_PLANE)) environment.postgres = PostgresDataPlane.start(config, form.name());
                return environment;
            } catch (Exception | Error failure) {
                try { environment.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        Map<String, String> properties() {
            Map<String, String> p = new java.util.LinkedHashMap<>();
            if (redis != null) p.putAll(redis.properties());
            if (postgres != null) p.putAll(postgres.properties());
            return p;
        }

        Map<String, String> processEnvironment() {
            return clock == null ? Map.of() : clock.environment();
        }

        String describe() {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (redis != null) parts.add("redis " + redis.describe());
            if (postgres != null) parts.add("postgres " + postgres.describe());
            if (clock != null) parts.add(clock.describe());
            return parts.isEmpty() ? "none" : String.join("; ", parts);
        }

        @Override
        public void close() throws Exception {
            Exception primary = null;
            for (AutoCloseable member : new AutoCloseable[] {redis, postgres, clock}) {
                if (member == null) continue;
                try { member.close(); } catch (Exception e) { if (primary == null) primary = e; else primary.addSuppressed(e); }
            }
            if (primary != null) throw primary;
        }
    }

    private final Prepared prepared;
    private final SutStack stack;
    private final boolean ownsStack;

    private GateDeployment(Prepared prepared, SutStack stack, boolean ownsStack) {
        this.prepared = prepared;
        this.stack = stack;
        this.ownsStack = ownsStack;
    }

    public static Prepared prepare(TestConfig config, DeploymentForm form, String agent) throws Exception {
        String remote = config.getString("sut.agents." + agent + ".url", "");
        if (remote != null && !remote.isBlank()) {
            throw new IllegalStateException("FEAT-112 needs an owned process; remove sut.agents." + agent + ".url");
        }
        Feat112Artifacts artifacts = Feat112Artifacts.resolve(config, agent);
        ModelStubProcess faultStub = null;
        CorrelatedModelStub correlated = null;
        Environment environment = null;
        try {
            environment = Environment.start(config, form);
            Map<String, String> environmentProperties = environment.properties();
            Map<String, String> processEnvironment = environment.processEnvironment();
            String modelOrigin;
            if (form.model() == DeploymentForm.Model.FAULT_STUB) {
                faultStub = ModelStubProcess.start(artifacts.jar());
                modelOrigin = faultStub.url();
            } else {
                correlated = new CorrelatedModelStub(0);
                modelOrigin = correlated.url();
            }
            Map<String, String> model = Map.of(MODEL_BASE_URL, modelOrigin + "/v1",
                    "deepanalyze.model.backend.client_provider", "openai",
                    "deepanalyze.model.backend.api_key", "feat112-test-stub-only");
            Map<String, String> yaml = config.getStringMap("sut.agents." + agent + ".spring.properties");
            for (String key : model.keySet()) {
                if (yaml.containsKey(key) || form.properties().containsKey(key)) {
                    throw new IllegalArgumentException("model wiring is owned by the deployment, not configurable: " + key);
                }
            }
            for (String key : List.of(ENGINE_SECRET, CHECKPOINTER)) {
                if (yaml.containsKey(key) || form.properties().containsKey(key)) {
                    throw new IllegalArgumentException("owned by the deployment, not configurable: " + key);
                }
            }
            // The delivered configuration enables service authentication and fails startup without the secret.
            String secret = "feat112-" + UUID.randomUUID();
            // The delivered checkpointer is Redis; a form without a test-owned Redis keeps checkpoints in memory.
            Map<String, String> store = form.needs(DeploymentForm.Infra.REDIS_CHECKPOINTER) ? Map.of() : Map.of(CHECKPOINTER, "in_memory");
            java.nio.file.Path workingDirectory = null;
            if (form.scenarioActrule() != null) {
                workingDirectory = java.nio.file.Files.createTempDirectory("feat112-" + form.name() + "-");
                java.nio.file.Path overlay = workingDirectory.resolve("scenarios/feat112/governance/actrule.yaml");
                java.nio.file.Files.createDirectories(overlay.getParent());
                java.nio.file.Files.writeString(overlay, form.scenarioActrule(), java.nio.charset.StandardCharsets.UTF_8);
            }
            java.nio.file.Path cwd = workingDirectory;
            SutStack.Builder builder = SutStack.builder(config).agent(agent, a -> {
                if (cwd != null) a.workingDirectory(cwd);
                a.readyMode(AgentConfig.ReadyMode.TCP);
                form.properties().forEach(a::property);
                store.forEach(a::property);
                environmentProperties.forEach(a::property);
                model.forEach(a::property);
                processEnvironment.forEach(a::env);
                a.env("DA_ENGINE_SECRET", secret);
            });
            return new Prepared(form, agent, artifacts, faultStub, correlated, secret, builder, environment);
        } catch (Exception | Error failure) {
            try {
                if (faultStub != null) faultStub.close();
                if (correlated != null) correlated.close();
                if (environment != null) environment.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Waits until the launched DA serves its diagnostic surface. */
    public static GateDeployment attach(Prepared prepared, SutStack stack, boolean ownsStack) throws Exception {
        var deployment = new GateDeployment(prepared, stack, ownsStack);
        try {
            deployment.awaitReady();
            return deployment;
        } catch (Exception | Error failure) {
            if (ownsStack) {
                try { deployment.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    public static GateDeployment start(DeploymentForm form) throws Exception {
        return start(form, AGENT);
    }

    public static GateDeployment start(DeploymentForm form, String agent) throws Exception {
        Prepared prepared = prepare(TestConfig.load(), form, agent);
        SutStack stack;
        try {
            stack = prepared.builder().start();
        } catch (Exception | Error failure) {
            try { prepared.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return attach(prepared, stack, true);
    }

    private void awaitReady() throws Exception {
        var client = new DaEvidenceClient(baseUrl(), prepared.secret, "deployment-" + prepared.form.name());
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (true) {
            if (!stack.isRunning(prepared.agent)) throw new IllegalStateException("DA process exited during startup");
            try {
                if (client.get("/v1/health/detail").status() == 200) return;
            } catch (java.io.IOException notYet) {
                // bounded startup wait only
            }
            if (System.nanoTime() > deadline) throw new IllegalStateException("DA diagnostic surface not ready within 120s");
            Thread.sleep(250);
        }
    }

    /** A driver for one scenario execution; records the deployment form and artifact the verdict rests on. */
    public GateDriver driver(Scenario scenario) throws Exception {
        record(scenario);
        String stub = prepared.faultStub == null ? null : prepared.faultStub.url();
        return new GateDriver(baseUrl(), stub, prepared.secret, String.join("_", scenario.ids()), scenario);
    }

    /** Records the deployment form and artifact identity as the base of the scenario's verdict. */
    public void record(Scenario scenario) {
        scenario.artifact(prepared.artifacts.identity());
        scenario.precondition("deployment form " + prepared.form.name() + " running", stack.isRunning(prepared.agent),
                EvidenceSource.PROCESS, "agent=" + prepared.agent + " pid=" + pid() + " base=" + baseUrl()
                        + " form=" + prepared.form.properties() + " authentication="
                        + (prepared.form.declaresAuthentication() ? "declared by the form" : "delivered default")
                        + " environment=" + prepared.environment.describe());
    }

    /** The Redis checkpoint store of a form declaring {@link DeploymentForm.Infra#REDIS_CHECKPOINTER}. */
    public RedisCheckpointStore redis() {
        if (prepared.environment.redis == null) throw new IllegalStateException("form " + prepared.form.name() + " has no Redis checkpointer");
        return prepared.environment.redis;
    }

    /** The data-plane database of a form declaring {@link DeploymentForm.Infra#POSTGRES_DATA_PLANE}. */
    public PostgresDataPlane postgres() {
        if (prepared.environment.postgres == null) throw new IllegalStateException("form " + prepared.form.name() + " has no PostgreSQL data plane");
        return prepared.environment.postgres;
    }

    /** The clock control of a form declaring {@link DeploymentForm.Infra#CLOCK_CONTROL}. */
    public ClockControl clock() {
        if (prepared.environment.clock == null) throw new IllegalStateException("form " + prepared.form.name() + " has no clock control");
        return prepared.environment.clock;
    }

    /** Client with explicit credentials ({@code null} = none), for authentication scenarios. */
    public DaEvidenceClient client(String secret, String caseId) throws Exception {
        return new DaEvidenceClient(baseUrl(), secret, caseId);
    }

    public DeploymentForm form() {
        return prepared.form;
    }

    public String secret() {
        return prepared.secret;
    }

    public CorrelatedModelStub correlatedModel() {
        if (prepared.correlated == null) throw new IllegalStateException("form " + prepared.form.name() + " uses the fault stub");
        return prepared.correlated;
    }

    public String baseUrl() {
        return stack.baseUrl(prepared.agent);
    }

    public long pid() {
        if (stack.managedInstance(prepared.agent) instanceof ManagedSutInstance managed) return managed.pid();
        throw new IllegalStateException("process ownership missing for " + prepared.agent);
    }

    /** Output of the owned process (appended across restarts). */
    public java.nio.file.Path processLog() {
        if (stack.managedInstance(prepared.agent) instanceof ManagedSutInstance managed) return managed.logFile();
        throw new IllegalStateException("process ownership missing for " + prepared.agent);
    }

    /** Stops and relaunches the owned process; drivers created before the restart point at the old address. */
    public void restart() throws Exception {
        stack.stop(prepared.agent);
        stack.start(prepared.agent);
        awaitReady();
    }

    @Override
    public void close() throws Exception {
        Exception primary = null;
        if (ownsStack) {
            try { stack.close(); } catch (Exception e) { primary = e; }
        }
        try {
            prepared.close();
        } catch (Exception e) {
            if (primary == null) primary = e; else primary.addSuppressed(e);
        }
        if (primary != null) throw primary;
    }
}
