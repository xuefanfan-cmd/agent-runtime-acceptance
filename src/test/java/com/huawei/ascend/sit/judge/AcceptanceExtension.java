package com.huawei.ascend.sit.judge;

import io.qameta.allure.Allure;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs each test as a scenario execution: resolves a {@link Scenario} from {@link Covers}, writes one result
 * record per execution under {@code target/acceptance-results/<runId>/}, and derives the JUnit status from the
 * recorded verdict. Blocking outcomes fail the test; outcomes that are all EXCLUDED/UNSUPPORTED abort it.
 */
public final class AcceptanceExtension implements BeforeEachCallback, ParameterResolver, AfterTestExecutionCallback {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(AcceptanceExtension.class);
    public static final String RUN_ID = System.getProperty("acceptance.run-id",
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

    public static Path runDirectory() {
        return Path.of(System.getProperty("acceptance.results-dir", "target/acceptance-results"), RUN_ID);
    }

    private record State(Scenario scenario, String startedAt) {}

    @Override
    public void beforeEach(ExtensionContext context) {
        Method method = context.getRequiredTestMethod();
        Covers covers = method.getAnnotation(Covers.class);
        if (covers == null) {
            throw new IllegalStateException(method + " must declare @Covers(scenario IDs, layer)");
        }
        String testId = context.getRequiredTestClass().getSimpleName() + "#" + method.getName();
        Scenario scenario = new Scenario(new ScenarioVerdict(List.of(covers.value()), covers.layer()), testId);
        context.getStore(NAMESPACE).put(context.getUniqueId(), new State(scenario, Instant.now().toString()));
        for (String id : covers.value()) Allure.label("scenario", id);
        Allure.label("acceptanceLayer", covers.layer().name());
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == Scenario.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return state(context).scenario();
    }

    @Override
    public void afterTestExecution(ExtensionContext context) throws Exception {
        State state = state(context);
        ScenarioVerdict verdict = state.scenario().verdict();
        Throwable thrown = context.getExecutionException().orElse(null);
        if (thrown != null && !(thrown instanceof PreconditionNotMet) && !(thrown instanceof AcceptanceFailure)) {
            verdict.terminate(thrown);
        }
        List<ScenarioVerdict.Result> results = verdict.results();
        String testId = context.getRequiredTestClass().getSimpleName() + "#" + context.getRequiredTestMethod().getName();
        String file = testId.replace('#', '.') + "-" + Integer.toHexString(context.getUniqueId().hashCode()) + ".json";
        verdict.write(runDirectory().resolve(file), RUN_ID, testId, state.startedAt(), Instant.now().toString());
        for (ScenarioVerdict.Result r : results) {
            Allure.parameter("verdict " + r.scenarioId(), r.outcome() + "/" + r.reason().code());
        }
        if (thrown != null) return;
        String summary = results.stream().map(r -> r.scenarioId() + "=" + r.outcome() + "/" + r.reason().code()
                + " " + r.detail()).collect(Collectors.joining("\n"));
        if (results.stream().anyMatch(ScenarioVerdict.Result::blocking)) throw new AcceptanceFailure(summary);
        if (results.stream().allMatch(r -> r.outcome() == EvidenceChecks.Outcome.EXCLUDED
                || r.outcome() == EvidenceChecks.Outcome.UNSUPPORTED)) {
            throw new TestAbortedException(summary);
        }
    }

    private State state(ExtensionContext context) {
        State state = context.getStore(NAMESPACE).get(context.getUniqueId(), State.class);
        if (state == null) throw new IllegalStateException("no scenario execution for " + context.getUniqueId());
        return state;
    }
}
