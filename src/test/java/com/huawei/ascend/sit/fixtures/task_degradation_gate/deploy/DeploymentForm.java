package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A declared deployment of the FEAT-112 carrier: gate settings and other Spring properties layered over the
 * engine's delivered configuration (service authentication stays at its delivered default, on), which model double
 * serves the DA, and which test-owned environment it runs in
 * (backing stores, clock control). Scenarios whose verdict depends on these values own the deployment that
 * carries them; no test relies on whatever an external environment happens to run.
 */
public record DeploymentForm(String name, Map<String, String> properties, Model model,
                             String scenarioActrule, Set<Infra> infra) {
    public enum Model {
        /** The demo-owned {@code LlmFaultInjectionStub}, launched from the provisioned carrier artifact. */
        FAULT_STUB,
        /** The test-owned {@code CorrelatedModelStub}: per-request markers and barriers for ordering scenarios. */
        CORRELATED_STUB
    }

    /** Test-owned environment a deployment needs; each is started by the deployment and released with it. */
    public enum Infra {
        /** A Redis container the DA uses as its checkpointer (core {@code checkpointer.type=redis}); can be stopped. */
        REDIS_CHECKPOINTER,
        /** A PostgreSQL container with the shared data-plane baseline, wired as the DA data plane. */
        POSTGRES_DATA_PLANE,
        /** libfaketime on the DA process (Linux): its wall clock can be moved while it runs. */
        CLOCK_CONTROL
    }

    public static final String GATE = "openjiuwen.service.degradation-gate.";
    public static final String AUTH_ENABLED = "deepanalyze.auth.enabled";

    public DeploymentForm {
        if (name == null || !name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("form name [a-z0-9-]+");
        properties = Map.copyOf(properties);
        infra = infra == null || infra.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(infra));
    }

    public DeploymentForm(String name, Map<String, String> properties, Model model) {
        this(name, properties, model, null, Set.of());
    }

    public static DeploymentForm gate(String name, int minSamples, String threshold, String window, String quiet) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(GATE + "enabled", "true");
        p.put(GATE + "min-sample-size", Integer.toString(minSamples));
        p.put(GATE + "failure-rate-threshold", threshold);
        p.put(GATE + "window", window);
        p.put(GATE + "open-quiet-period", quiet);
        return new DeploymentForm(name, p, Model.FAULT_STUB);
    }

    /** Short window and quiet period so a class of scenarios can open, recover and drain between tests. */
    public static DeploymentForm standard() {
        return gate("standard", 5, "0.5", "60s", "10s");
    }

    public static DeploymentForm disabled() {
        return standard().named("disabled").with(GATE + "enabled", "false");
    }

    public DeploymentForm named(String newName) {
        return new DeploymentForm(newName, properties, model, scenarioActrule, infra);
    }

    public DeploymentForm with(String key, String value) {
        Map<String, String> p = new LinkedHashMap<>(properties);
        p.put(key, value);
        return new DeploymentForm(name, p, model, scenarioActrule, infra);
    }

    /**
     * Service authentication declared explicitly ({@code deepanalyze.auth.enabled=true}) on top of the delivered
     * default, which already enables it. Every form gets the engine secret and every driver carries it; this only
     * changes where the setting comes from.
     */
    public DeploymentForm withExplicitServiceAuthentication() {
        return with(AUTH_ENABLED, "true");
    }

    /** Whether the form sets the authentication switch itself instead of leaving it to the delivered default. */
    public boolean declaresAuthentication() {
        return properties.containsKey(AUTH_ENABLED);
    }

    public DeploymentForm withCorrelatedModel() {
        return new DeploymentForm(name, properties, Model.CORRELATED_STUB, scenarioActrule, infra);
    }

    /**
     * A deployment-side actrule overlay (merged stricter over the framework asset). The DA reads overlays from
     * {@code ./scenarios/<name>/governance/actrule.yaml}, so this form gets its own working directory.
     */
    public DeploymentForm withScenarioActrule(String yaml) {
        return new DeploymentForm(name, properties, model, yaml, infra);
    }

    public DeploymentForm withRedisCheckpointer() {
        return withInfra(Infra.REDIS_CHECKPOINTER);
    }

    public DeploymentForm withPostgresDataPlane() {
        return withInfra(Infra.POSTGRES_DATA_PLANE);
    }

    public DeploymentForm withClockControl() {
        return withInfra(Infra.CLOCK_CONTROL);
    }

    /** DEBUG terminal-fact log ({@code terminal fact taskId= class= code=}): the per-task classification on the log level. */
    public DeploymentForm withTerminalFactLog() {
        return with("logging.level.com.openjiuwen.da.compat.DaDegradationSupport", "DEBUG");
    }

    private DeploymentForm withInfra(Infra added) {
        EnumSet<Infra> next = infra.isEmpty() ? EnumSet.noneOf(Infra.class) : EnumSet.copyOf(infra);
        next.add(added);
        return new DeploymentForm(name, properties, model, scenarioActrule, next);
    }

    public boolean needs(Infra required) {
        return infra.contains(required);
    }

    public String gateSetting(String key) {
        return properties.get(GATE + key);
    }
}
