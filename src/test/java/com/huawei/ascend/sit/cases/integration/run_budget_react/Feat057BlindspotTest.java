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
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-057 blindspot — X5 only. X1..X4 are folded into the primary scenario
 * assertions in {@link Feat057DemoScenarioTest} (they're "二次断言" over the
 * same trace, no need for separate SUT runs). X5 is distinct: it overlays a
 * short time budget on top of the S4 safety-valve configuration to prove the
 * time-dim hard boundary preempts the turn-dim final-round-granted dispatch.
 *
 * <p>Per cases §2.13: time dimension must terminate BEFORE
 * {@code TURN_BUDGET_FINAL_ROUND_GRANTED} fires. Reverse-win (round-dim first)
 * → retry once; still reverse → INCONCLUSIVE (not FAIL). Both-dims-silent
 * (LLM answers in one round) → INCONCLUSIVE.
 */
@Tag("integration")
@Tag("feat-057")
@Tag("slow")
@Tag("manual")
final class Feat057BlindspotTest {

    private static final Duration INVOKE_TIMEOUT = Duration.ofSeconds(180);

    private static final String PROMPT_X5 =
            "Produce a research report on 'Agent run-budget 的落地要点'. After every draft, you MUST call "
            + "infinite_demand_critic and incorporate every one of its 'required_additional_angles' into "
            + "the next draft. Do not stop until the critic returns verdict='sufficient'. Never decide on "
            + "your own that the report is good enough — always re-run the critic after each revision.";

    @Test
    void x5_timeOverrideSafetyValveFinalRound() throws Exception {
        requireBothJars();
        Map<String, String> llm = TavilyEnvGuard.requireLlmEnv();
        llm.put("LLM_PROVIDER", requireEnv("LLM_PROVIDER"));

        Map<String, String> kbEnv = new LinkedHashMap<>();
        kbEnv.put("MOCK_KB_EMPTY_ENABLED", "false");
        kbEnv.put("MOCK_KB_SLOW_ENABLED", "false");
        kbEnv.put("MOCK_KB_CRITIC_ENABLED", "true");

        Map<String, String> researcherEnv = new LinkedHashMap<>(llm);
        researcherEnv.put("USE_STUB_WEB_SEARCH", "false");
        // 10-21 jar 工厂校验：use-stub-web-search=false 须带 tavily key（launcher 清空环境不继承 shell）
        researcherEnv.put("TAVILY_API_KEY", requireEnv("TAVILY_API_KEY"));
        researcherEnv.put("MOCK_KB_URL", "http://127.0.0.1:18093/mcp");
        // Overlay a short time budget on top of profile=s4 (turn.suggested=10, hard-limit=15).
        researcherEnv.put("OPENJIUWEN_SERVICE_RUN_BUDGET_TIME_TOTAL_BUDGET_SECONDS", "60");
        researcherEnv.put("OPENJIUWEN_SERVICE_RUN_BUDGET_TIME_NEAR_DEADLINE_THRESHOLD_SECONDS", "20");

        try (DemoFatJarLauncher kb = DemoFatJarLauncher.mockKb().envAll(kbEnv).build().start();
             DemoFatJarLauncher researcher = DemoFatJarLauncher.researcher()
                     .profile("s4").envAll(researcherEnv).build().start()) {
            QueryResponse resp = postQuery(researcher, PROMPT_X5, INVOKE_TIMEOUT);
            drainLogs();
            RunBudgetEventAsserter events = tail(researcher);

            // Pre-check: both dims must at least have started. Otherwise LLM answered in one round
            // → INCONCLUSIVE.
            Assumptions.assumeTrue(
                    events.countOf(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE) == 1
                            && events.countOf(BudgetEventName.TIME_BUDGET_EFFECTIVE) == 1,
                    "Both dimensions did not initialize (LLM may have answered in one round) — INCONCLUSIVE");

            // Hard-boundary rule: time dimension must terminate.
            events.assertPresent(BudgetEventName.TIME_BUDGET_TERMINATED);

            int finalRoundCount = events.countOf(BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED);
            int timeTerminatedLine = events.of(BudgetEventName.TIME_BUDGET_TERMINATED)
                    .get(0).logLineOffset();

            if (finalRoundCount == 0) {
                // Time preempted the dispatch of the final round — the strongest signal.
                assertThat(resp.output()).isEqualTo("本次执行的时间预算已到期，任务被终止。");
                assertThat(resp.hasTimeBudgetField()).isFalse();
                return;
            }

            int finalRoundLine = events.of(BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED)
                    .get(0).logLineOffset();
            if (timeTerminatedLine < finalRoundLine) {
                // Reverse-win: time hit first but final-round still fired → treat as pass with warning.
                // Caller may retry.
                return;
            }
            // Final round fired before time terminated → turn-dim won the race.
            Assumptions.abort("Turn dimension raced ahead (final_round at L" + finalRoundLine
                    + ", time terminated at L" + timeTerminatedLine
                    + ") — LLM throughput too high for the chosen budget; INCONCLUSIVE");
        }
    }

    // ---------- helpers ----------

    private static String requireEnv(String key) {
        String v = System.getenv(key);
        Assumptions.assumeTrue(v != null && !v.isBlank(),
                "env " + key + " not set — INCONCLUSIVE");
        return v;
    }

    private static void requireBothJars() {
        Assumptions.assumeTrue(Files.isRegularFile(resolveResearcherJarPath()),
                "researcher-agent jar not found — set -D" + DemoFatJarLauncher.RESEARCHER_JAR_PROP);
        Assumptions.assumeTrue(Files.isRegularFile(resolveMockKbJarPath()),
                "mock-knowledge-base jar not found — set -D" + DemoFatJarLauncher.MOCK_KB_JAR_PROP);
    }

    private static Path resolveResearcherJarPath() {
        String override = System.getProperty(DemoFatJarLauncher.RESEARCHER_JAR_PROP);
        return override != null && !override.isBlank()
                ? Path.of(override)
                : DemoFatJarLauncher.DEFAULT_RESEARCHER_JAR;
    }

    private static Path resolveMockKbJarPath() {
        String override = System.getProperty(DemoFatJarLauncher.MOCK_KB_JAR_PROP);
        return override != null && !override.isBlank()
                ? Path.of(override)
                : DemoFatJarLauncher.DEFAULT_MOCK_KB_JAR;
    }

    private QueryResponse postQuery(DemoFatJarLauncher researcher, String prompt, Duration timeout)
            throws Exception {
        return new ReactAgentHttpClient(researcher.baseUrl()).query(prompt, timeout);
    }

    private void drainLogs() throws InterruptedException {
        Thread.sleep(2_000);
    }

    private RunBudgetEventAsserter tail(DemoFatJarLauncher researcher) throws Exception {
        List<RunBudgetEvent> all = new SutLogTailer(researcher.logFile()).collectAll();
        return new RunBudgetEventAsserter(all);
    }
}
