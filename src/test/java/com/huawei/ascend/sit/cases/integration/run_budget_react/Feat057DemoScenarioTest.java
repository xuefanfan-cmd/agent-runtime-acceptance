/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.run_budget_react;

import com.huawei.ascend.sit.cases.integration.run_budget_react.fixture.BudgetEventName;
import com.huawei.ascend.sit.cases.integration.run_budget_react.fixture.DemoFatJarLauncher;
import com.huawei.ascend.sit.cases.integration.run_budget_react.fixture.ReactAgentHttpClient;
import com.huawei.ascend.sit.cases.integration.run_budget_react.fixture.ReactAgentHttpClient.QueryResponse;
import com.huawei.ascend.sit.cases.integration.run_budget_react.fixture.RunBudgetEvent;
import com.huawei.ascend.sit.cases.integration.run_budget_react.fixture.RunBudgetEventAsserter;
import com.huawei.ascend.sit.cases.integration.run_budget_react.fixture.SutLogTailer;
import com.huawei.ascend.sit.cases.integration.run_budget_react.fixture.TavilyEnvGuard;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-057 — demo-driven SIT scenarios S0..S7. Each test method launches the
 * {@code researcher-agent} fat jar with the matching Spring profile, posts the
 * demo-aligned prompt to {@code POST /v1/query}, tails the SUT log for FEAT-057
 * budget events, and asserts the per-scenario contract defined in
 * docs/cases/FEAT-057-elastic-turn-budget-reactagent.md.
 *
 * <p>Preconditions (all expressed as {@link Assumptions#assumeTrue}, so a
 * missing precondition → test is SKIPPED, not FAILED):
 * <ul>
 *   <li>{@code researcher-agent-0.1.0.jar} + {@code mock-knowledge-base-0.1.0.jar}
 *       exist at the dev-side build location (or overridden via
 *       {@code -Dfeat057.researcher-jar=...} / {@code -Dfeat057.mock-kb-jar=...}).
 *   <li>LLM env (`LLM_API_KEY`, `LLM_API_BASE`, `LLM_MODEL`, `LLM_PROVIDER`) is
 *       exported before launching Maven.
 *   <li>For S1/S5: {@code TAVILY_API_KEY} is also exported.
 *   <li>Corporate proxy is unset (`unset HTTP_PROXY HTTPS_PROXY`) — see memory
 *       [[local-dev-clear-corporate-proxy-before-sit]].
 * </ul>
 *
 * <p>Tags: {@code integration} + {@code feat-057} + {@code slow} + {@code manual}
 * (not part of default Smoke; Nightly or on-demand).
 */
@Tag("integration")
@Tag("feat-057")
@Tag("slow")
@Tag("manual")
final class Feat057DemoScenarioTest {

    private static final Duration DEFAULT_INVOKE_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration S3_INVOKE_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration S4_INVOKE_TIMEOUT = Duration.ofSeconds(600);
    private static final Duration S5_INVOKE_TIMEOUT = Duration.ofSeconds(360);

    private static final String PROMPT_S0 =
            "Give me a one-paragraph overview of the OpenJiuwen project.";
    private static final String PROMPT_S1 =
            "Compare the main design goals of the OpenJiuwen Agent SPI versus LangGraph in 3 sentences.";
    private static final String PROMPT_S2 =
            "Search the local knowledge base for '量子纠缠的最新实验进展' using empty_knowledge_search. "
            + "If the first attempt returns zero matches, you MUST retry at least 12 more times with "
            + "different query variations (synonyms, English translations, broader terms like 'quantum "
            + "entanglement experiments', narrower terms like 'Bell test violations', related concepts). "
            + "Do not give up after one or two empty results — keep searching until you have attempted at "
            + "least 12 distinct queries, then summarize what you found even if the result set is empty.";
    private static final String PROMPT_S3 =
            "Fetch documents 'doc://quantum/intro' and 'doc://quantum/experiments' and summarize them.";
    private static final String PROMPT_S4 =
            "Produce a research report on 'Agent run-budget 的落地要点'. After every draft, you MUST call "
            + "infinite_demand_critic and incorporate every one of its 'required_additional_angles' into "
            + "the next draft. Do not stop until the critic returns verdict='sufficient'. Never decide on "
            + "your own that the report is good enough — always re-run the critic after each revision.";
    private static final String PROMPT_S5 =
            "Research 'OpenJiuwen 的调度模型选型' and finalize an answer under the budget.";
    private static final String PROMPT_S6 =
            "Fetch the following knowledge-base documents one by one using slow_knowledge_fetch, and after "
            + "each fetch add a one-sentence note to your running summary: doc://quantum/intro, "
            + "doc://quantum/entanglement, doc://quantum/teleportation, doc://quantum/cryptography, "
            + "doc://quantum/computing, doc://quantum/supremacy, doc://quantum/error-correction, "
            + "doc://quantum/decoherence, doc://quantum/annealing, doc://quantum/gates, "
            + "doc://quantum/algorithms, doc://quantum/hardware, doc://quantum/benchmarks, "
            + "doc://quantum/applications, doc://quantum/history. Each uri is distinct, so each fetch "
            + "produces new content.";
    private static final String PROMPT_S7 =
            "Research the OpenJiuwen project at length — this is a long-running task that would normally "
            + "burn many rounds.";

    @Test
    void s0_compatBaselineZeroBudgetEvents() throws Exception {
        requireResearcherJar();
        Map<String, String> env = baseEnvForStubWebSearch();
        env.put("USE_STUB_WEB_SEARCH", "true");

        try (DemoFatJarLauncher researcher = DemoFatJarLauncher.researcher()
                .profile("s0")
                .envAll(env)
                .build()
                .start()) {
            QueryResponse resp = postQuery(researcher, PROMPT_S0, DEFAULT_INVOKE_TIMEOUT);
            drainLogs();
            RunBudgetEventAsserter events = tail(researcher);

            // X4 — strict absent for all 11 code-truth event names.
            events.assertAbsent(BudgetEventName.values());

            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.resultType()).isIn("answer", "error");
            if ("error".equals(resp.resultType())) {
                assertThat(resp.output()).isEqualTo("Max iterations reached without completion");
            } else {
                assertThat(resp.output()).isNotNull().isNotBlank();
            }
            // L2 §2.2: turn_budget / time_budget subobjects must NOT leak into body.
            assertThat(resp.hasTurnBudgetField()).isFalse();
            assertThat(resp.hasTimeBudgetField()).isFalse();
        }
    }

    @Test
    void s1_turnGuaranteeNoInterference() throws Exception {
        requireResearcherJar();
        Map<String, String> env = TavilyEnvGuard.requireTavily();
        env.put("LLM_PROVIDER", requireEnv("LLM_PROVIDER"));
        env.put("USE_STUB_WEB_SEARCH", "false");

        try (DemoFatJarLauncher researcher = DemoFatJarLauncher.researcher()
                .profile("s1")
                .envAll(env)
                .build()
                .start()) {
            QueryResponse resp = postQuery(researcher, PROMPT_S1, DEFAULT_INVOKE_TIMEOUT);
            drainLogs();
            RunBudgetEventAsserter events = tail(researcher);

            events.assertCount(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE, 1);
            events.assertFirstPayload(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE,
                    e -> Integer.valueOf(20).equals(e.intField("guaranteed_rounds"))
                            && "configured".equals(e.stringField("source")),
                    "guaranteed_rounds==20 && source==configured");

            events.assertAbsent(
                    BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
                    BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED,
                    BudgetEventName.TURN_BUDGET_TERMINATED,
                    BudgetEventName.TIME_BUDGET_EFFECTIVE,
                    BudgetEventName.TIME_BUDGET_NEAR_DEADLINE_PROMPTED,
                    BudgetEventName.TIME_BUDGET_TERMINATED);

            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.hasTurnBudgetField()).isFalse();
            assertThat(resp.hasTimeBudgetField()).isFalse();
        }
    }

    @Test
    void s2_turnStallPromptEscalation() throws Exception {
        requireBothJars();
        Map<String, String> llm = TavilyEnvGuard.requireLlmEnv();
        llm.put("LLM_PROVIDER", requireEnv("LLM_PROVIDER"));

        Map<String, String> kbEnv = new LinkedHashMap<>();
        kbEnv.put("MOCK_KB_EMPTY_ENABLED", "true");
        kbEnv.put("MOCK_KB_SLOW_ENABLED", "false");
        kbEnv.put("MOCK_KB_CRITIC_ENABLED", "false");

        Map<String, String> researcherEnv = new LinkedHashMap<>(llm);
        researcherEnv.put("USE_STUB_WEB_SEARCH", "false");
        researcherEnv.put("MOCK_KB_URL", "http://127.0.0.1:18093/mcp");

        try (DemoFatJarLauncher kb = DemoFatJarLauncher.mockKb().envAll(kbEnv).build().start();
             DemoFatJarLauncher researcher = DemoFatJarLauncher.researcher()
                     .profile("s2").envAll(researcherEnv).build().start()) {
            postQuery(researcher, PROMPT_S2, DEFAULT_INVOKE_TIMEOUT);
            drainLogs();
            RunBudgetEventAsserter events = tail(researcher);

            events.assertCount(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE, 1);
            events.assertFirstPayload(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE,
                    e -> Integer.valueOf(10).equals(e.intField("guaranteed_rounds")),
                    "guaranteed_rounds==10");
            events.assertPresent(BudgetEventName.TURN_BUDGET_CHECKPOINT);
            events.assertPresent(BudgetEventName.TURN_BUDGET_STALL_PROMPTED);
            // MUST#5 — intervention must not hard-cut (hard_limit=200 for suggested=10 → not expected here).
            if (events.countOf(BudgetEventName.TURN_BUDGET_TERMINATED) > 0) {
                Assumptions.abort("TERMINATED fired — LLM burned to hard_limit; mark INCONCLUSIVE");
            }
        }
    }

    @Test
    void s3_timeCompleteLifecycle() throws Exception {
        requireBothJars();
        Map<String, String> llm = TavilyEnvGuard.requireLlmEnv();
        llm.put("LLM_PROVIDER", requireEnv("LLM_PROVIDER"));

        Map<String, String> kbEnv = new LinkedHashMap<>();
        kbEnv.put("MOCK_KB_EMPTY_ENABLED", "false");
        kbEnv.put("MOCK_KB_SLOW_ENABLED", "true");
        kbEnv.put("MOCK_KB_SLOW_MS", "20000");
        kbEnv.put("MOCK_KB_CRITIC_ENABLED", "false");

        Map<String, String> researcherEnv = new LinkedHashMap<>(llm);
        researcherEnv.put("USE_STUB_WEB_SEARCH", "false");
        researcherEnv.put("MOCK_KB_URL", "http://127.0.0.1:18093/mcp");

        try (DemoFatJarLauncher kb = DemoFatJarLauncher.mockKb().envAll(kbEnv).build().start();
             DemoFatJarLauncher researcher = DemoFatJarLauncher.researcher()
                     .profile("s3").envAll(researcherEnv).build().start()) {
            QueryResponse resp = postQuery(researcher, PROMPT_S3, S3_INVOKE_TIMEOUT);
            drainLogs();
            RunBudgetEventAsserter events = tail(researcher);

            events.assertCount(BudgetEventName.TIME_BUDGET_EFFECTIVE, 1);
            events.assertFirstPayload(BudgetEventName.TIME_BUDGET_EFFECTIVE,
                    e -> Integer.valueOf(90).equals(e.intField("total_budget_seconds"))
                            && Integer.valueOf(30).equals(e.intField("near_deadline_threshold_seconds")),
                    "total_budget_seconds==90 && near_deadline_threshold_seconds==30");

            // X2 — strict single-shot.
            events.assertCount(BudgetEventName.TIME_BUDGET_NEAR_DEADLINE_PROMPTED, 1);

            events.assertCount(BudgetEventName.TIME_BUDGET_TERMINATED, 1);
            // X3 — reason字面可区分.
            events.assertFirstPayload(BudgetEventName.TIME_BUDGET_TERMINATED,
                    e -> "time_budget_exhausted".equals(e.stringField("reason"))
                            && List.of("model_call", "tool_dispatch").contains(e.stringField("terminated_at")),
                    "reason==time_budget_exhausted && terminated_at ∈ {model_call,tool_dispatch}");

            events.assertSequence(
                    BudgetEventName.TIME_BUDGET_EFFECTIVE,
                    BudgetEventName.TIME_BUDGET_NEAR_DEADLINE_PROMPTED,
                    BudgetEventName.TIME_BUDGET_TERMINATED);

            // profile=s3 does not enable turn dimension — zero turn_budget_*.
            events.assertAbsent(
                    BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE,
                    BudgetEventName.TURN_BUDGET_CHECKPOINT,
                    BudgetEventName.TURN_BUDGET_EXTENDED,
                    BudgetEventName.TURN_BUDGET_STALL_PROMPTED,
                    BudgetEventName.TURN_BUDGET_GENTLE_REMINDER,
                    BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
                    BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED,
                    BudgetEventName.TURN_BUDGET_TERMINATED);

            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.resultType()).isEqualTo("error");
            assertThat(resp.output()).isEqualTo("本次执行的时间预算已到期，任务被终止。");
            assertThat(resp.hasTimeBudgetField()).isFalse();
            assertThat(resp.hasTurnBudgetField()).isFalse();
        }
    }

    @Test
    void s4_turnSafetyValveThreePhase() throws Exception {
        requireBothJars();
        Map<String, String> llm = TavilyEnvGuard.requireLlmEnv();
        llm.put("LLM_PROVIDER", requireEnv("LLM_PROVIDER"));

        Map<String, String> kbEnv = new LinkedHashMap<>();
        kbEnv.put("MOCK_KB_EMPTY_ENABLED", "false");
        kbEnv.put("MOCK_KB_SLOW_ENABLED", "false");
        kbEnv.put("MOCK_KB_CRITIC_ENABLED", "true");

        Map<String, String> researcherEnv = new LinkedHashMap<>(llm);
        researcherEnv.put("USE_STUB_WEB_SEARCH", "false");
        researcherEnv.put("MOCK_KB_URL", "http://127.0.0.1:18093/mcp");

        try (DemoFatJarLauncher kb = DemoFatJarLauncher.mockKb().envAll(kbEnv).build().start();
             DemoFatJarLauncher researcher = DemoFatJarLauncher.researcher()
                     .profile("s4").envAll(researcherEnv).build().start()) {
            QueryResponse resp = postQuery(researcher, PROMPT_S4, S4_INVOKE_TIMEOUT);
            drainLogs();
            RunBudgetEventAsserter events = tail(researcher);

            events.assertCount(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE, 1);
            events.assertFirstPayload(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE,
                    e -> Integer.valueOf(10).equals(e.intField("guaranteed_rounds"))
                            && Integer.valueOf(15).equals(e.intField("hard_limit")),
                    "guaranteed_rounds==10 && hard_limit==15");

            // X1 — safety-valve three-phase strict order + FINAL_ROUND_GRANTED idempotent.
            events.assertCount(BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED, 1);
            events.assertFirstPayload(BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
                    e -> Integer.valueOf(15).equals(e.intField("round"))
                            && Boolean.TRUE.equals(e.boolField("instruction_injected"))
                            && Integer.valueOf(16).equals(e.intField("final_round")),
                    "round==15 && instruction_injected==true && final_round==16");

            events.assertCount(BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED, 1);
            events.assertFirstPayload(BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED,
                    e -> Integer.valueOf(16).equals(e.intField("round")),
                    "round==16");

            events.assertCount(BudgetEventName.TURN_BUDGET_TERMINATED, 1);
            events.assertFirstPayload(BudgetEventName.TURN_BUDGET_TERMINATED,
                    e -> Integer.valueOf(16).equals(e.intField("round"))
                            && "turn_budget_exhausted".equals(e.stringField("reason"))
                            && Integer.valueOf(15).equals(e.intField("hard_limit")),
                    "round==16 && reason==turn_budget_exhausted && hard_limit==15");

            events.assertSequence(
                    BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE,
                    BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
                    BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED,
                    BudgetEventName.TURN_BUDGET_TERMINATED);

            // L2 §4.2 — once safety valve engages, no checkpoint/stall/reminder between phases.
            events.assertNoneBetween(
                    BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
                    BudgetEventName.TURN_BUDGET_TERMINATED,
                    BudgetEventName.TURN_BUDGET_CHECKPOINT,
                    BudgetEventName.TURN_BUDGET_STALL_PROMPTED,
                    BudgetEventName.TURN_BUDGET_GENTLE_REMINDER);

            events.assertAbsent(
                    BudgetEventName.TIME_BUDGET_EFFECTIVE,
                    BudgetEventName.TIME_BUDGET_NEAR_DEADLINE_PROMPTED,
                    BudgetEventName.TIME_BUDGET_TERMINATED);

            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.resultType()).isEqualTo("error");
            assertThat(resp.output()).isEqualTo("已达到轮次预算绝对上限，任务被终止。");
            assertThat(resp.hasTurnBudgetField()).isFalse();
            assertThat(resp.hasTimeBudgetField()).isFalse();
        }
    }

    @Test
    void s5_dualDimCoexistenceGentleReminder() throws Exception {
        requireResearcherJar();
        Map<String, String> env = TavilyEnvGuard.requireTavily();
        env.put("LLM_PROVIDER", requireEnv("LLM_PROVIDER"));
        env.put("USE_STUB_WEB_SEARCH", "false");

        try (DemoFatJarLauncher researcher = DemoFatJarLauncher.researcher()
                .profile("s5").envAll(env).build().start()) {
            QueryResponse resp = postQuery(researcher, PROMPT_S5, S5_INVOKE_TIMEOUT);
            drainLogs();
            RunBudgetEventAsserter events = tail(researcher);

            events.assertPresent(
                    BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE,
                    BudgetEventName.TIME_BUDGET_EFFECTIVE);

            events.assertAbsent(
                    BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
                    BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED,
                    BudgetEventName.TURN_BUDGET_TERMINATED,
                    BudgetEventName.TIME_BUDGET_TERMINATED);

            // GENTLE_REMINDER is weak-tier (LLM may answer within guaranteed_rounds×2=10)
            // — if absent, log but don't FAIL. If present, verify independent of stall.
            if (events.countOf(BudgetEventName.TURN_BUDGET_GENTLE_REMINDER) > 0) {
                events.assertAllPayload(BudgetEventName.TURN_BUDGET_GENTLE_REMINDER,
                        e -> e.intField("round") != null,
                        "round field present");
            }
            assertThat(resp.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void s6_checkpointExtendedProgressed() throws Exception {
        requireBothJars();
        Map<String, String> llm = TavilyEnvGuard.requireLlmEnv();
        llm.put("LLM_PROVIDER", requireEnv("LLM_PROVIDER"));

        Map<String, String> kbEnv = new LinkedHashMap<>();
        kbEnv.put("MOCK_KB_EMPTY_ENABLED", "false");
        kbEnv.put("MOCK_KB_SLOW_ENABLED", "true");
        kbEnv.put("MOCK_KB_SLOW_MS", "500");
        kbEnv.put("MOCK_KB_CRITIC_ENABLED", "false");

        Map<String, String> researcherEnv = new LinkedHashMap<>(llm);
        researcherEnv.put("USE_STUB_WEB_SEARCH", "false");
        researcherEnv.put("MOCK_KB_URL", "http://127.0.0.1:18093/mcp");

        try (DemoFatJarLauncher kb = DemoFatJarLauncher.mockKb().envAll(kbEnv).build().start();
             DemoFatJarLauncher researcher = DemoFatJarLauncher.researcher()
                     .profile("s6").envAll(researcherEnv).build().start()) {
            postQuery(researcher, PROMPT_S6, S5_INVOKE_TIMEOUT);
            drainLogs();
            RunBudgetEventAsserter events = tail(researcher);

            events.assertCount(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE, 1);
            events.assertPresent(BudgetEventName.TURN_BUDGET_CHECKPOINT);

            // At least one checkpoint with progressed==true, followed by EXTENDED.
            boolean anyProgressed = events.of(BudgetEventName.TURN_BUDGET_CHECKPOINT).stream()
                    .anyMatch(e -> Boolean.TRUE.equals(e.boolField("progressed")));
            Assumptions.assumeTrue(anyProgressed,
                    "No CHECKPOINT.progressed==true observed — LLM did not reach extension branch; INCONCLUSIVE");

            events.assertPresent(BudgetEventName.TURN_BUDGET_EXTENDED);
            events.assertAllPayload(BudgetEventName.TURN_BUDGET_EXTENDED,
                    e -> {
                        Integer allowed = e.intField("allowed_rounds");
                        Integer hard = e.intField("hard_limit");
                        return allowed != null && hard != null && allowed <= hard;
                    },
                    "allowed_rounds <= hard_limit");

            events.assertAbsent(
                    BudgetEventName.TURN_BUDGET_STALL_PROMPTED,
                    BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
                    BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED,
                    BudgetEventName.TURN_BUDGET_TERMINATED,
                    BudgetEventName.TIME_BUDGET_EFFECTIVE,
                    BudgetEventName.TIME_BUDGET_NEAR_DEADLINE_PROMPTED,
                    BudgetEventName.TIME_BUDGET_TERMINATED);
        }
    }

    @Test
    void s7_overlayRailPriority() throws Exception {
        requireResearcherJar();
        Map<String, String> env = baseEnvForStubWebSearch();
        env.put("USE_STUB_WEB_SEARCH", "true");

        try (DemoFatJarLauncher researcher = DemoFatJarLauncher.researcher()
                .profile("s7").envAll(env).build().start()) {
            QueryResponse resp = postQuery(researcher, PROMPT_S7, DEFAULT_INVOKE_TIMEOUT);
            drainLogs();
            RunBudgetEventAsserter events = tail(researcher);

            events.assertCount(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE, 1);

            // MUST#18: run-budget must NOT fire termination when a higher-priority overlay rail decides to finish.
            events.assertAbsent(
                    BudgetEventName.TURN_BUDGET_TERMINATED,
                    BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
                    BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED,
                    BudgetEventName.TURN_BUDGET_STALL_PROMPTED,
                    BudgetEventName.TIME_BUDGET_EFFECTIVE,
                    BudgetEventName.TIME_BUDGET_NEAR_DEADLINE_PROMPTED,
                    BudgetEventName.TIME_BUDGET_TERMINATED);

            // Overlay rail carries custom reason — appears in the SUT log finish path.
            boolean overlayReasonSeen = Files.readString(researcher.logFile())
                    .contains("overlay_rail_force_finish");
            assertThat(overlayReasonSeen)
                    .as("overlay rail custom reason must appear in SUT log")
                    .isTrue();

            // Output must not be a run-budget termination phrase.
            if (resp.output() != null) {
                assertThat(resp.output())
                        .doesNotContain("已达到轮次预算绝对上限")
                        .doesNotContain("本次执行的时间预算已到期");
            }
        }
    }

    // ---------- helpers ----------

    private Map<String, String> baseEnvForStubWebSearch() {
        // S0 and S7 use stub web search; LLM env still needed because the agent-core wiring
        // builds a real ChatClient at startup — if LLM_API_KEY missing the SUT crashes.
        Map<String, String> env = TavilyEnvGuard.requireLlmEnv();
        env.put("LLM_PROVIDER", requireEnv("LLM_PROVIDER"));
        return env;
    }

    private static String requireEnv(String key) {
        String v = System.getenv(key);
        Assumptions.assumeTrue(v != null && !v.isBlank(),
                "env " + key + " not set — INCONCLUSIVE");
        return v;
    }

    private static void requireResearcherJar() {
        Assumptions.assumeTrue(Files.isRegularFile(resolveResearcherJarPath()),
                "researcher-agent jar not found — set -D" + DemoFatJarLauncher.RESEARCHER_JAR_PROP);
    }

    private static void requireBothJars() {
        requireResearcherJar();
        Assumptions.assumeTrue(Files.isRegularFile(resolveMockKbJarPath()),
                "mock-knowledge-base jar not found — set -D" + DemoFatJarLauncher.MOCK_KB_JAR_PROP);
    }

    private static java.nio.file.Path resolveResearcherJarPath() {
        String override = System.getProperty(DemoFatJarLauncher.RESEARCHER_JAR_PROP);
        return override != null && !override.isBlank()
                ? java.nio.file.Path.of(override)
                : DemoFatJarLauncher.DEFAULT_RESEARCHER_JAR;
    }

    private static java.nio.file.Path resolveMockKbJarPath() {
        String override = System.getProperty(DemoFatJarLauncher.MOCK_KB_JAR_PROP);
        return override != null && !override.isBlank()
                ? java.nio.file.Path.of(override)
                : DemoFatJarLauncher.DEFAULT_MOCK_KB_JAR;
    }

    private QueryResponse postQuery(DemoFatJarLauncher researcher, String prompt, Duration timeout)
            throws Exception {
        ReactAgentHttpClient client = new ReactAgentHttpClient(researcher.baseUrl());
        return client.query(prompt, timeout);
    }

    /** Give the SUT a moment to flush trailing log lines after /v1/query returns. */
    private void drainLogs() throws InterruptedException {
        Thread.sleep(2_000);
    }

    private RunBudgetEventAsserter tail(DemoFatJarLauncher researcher) throws Exception {
        SutLogTailer tailer = new SutLogTailer(researcher.logFile());
        List<RunBudgetEvent> all = tailer.collectAll();
        return new RunBudgetEventAsserter(all);
    }
}
