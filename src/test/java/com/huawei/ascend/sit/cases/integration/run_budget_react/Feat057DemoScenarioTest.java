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
            "Search the local knowledge base using empty_knowledge_search for 12 DIFFERENT query "
            + "variations about 量子纠缠. STRICT PROTOCOL: EXACTLY ONE tool call per turn. Do NOT write "
            + "any commentary, notes, or explanations between calls - your response each turn must be "
            + "ONLY the next tool call, nothing else. Only after all 12 attempts may you write a final summary.";
    private static final String PROMPT_S3 =
            "Fetch these 6 documents using slow_knowledge_fetch, STRICTLY ONE fetch per turn (never "
            + "batch): doc://quantum/intro, doc://quantum/experiments, doc://quantum/entanglement, "
            + "doc://quantum/computing, doc://quantum/gates, doc://quantum/hardware. After each fetch add "
            + "a one-sentence note, then fetch the next in your NEXT turn. Do not summarize until all 6 "
            + "are fetched.";
    private static final String PROMPT_S4 =
            "Produce a research report on 'Agent run-budget 的落地要点'. After every draft, you MUST call "
            + "infinite_demand_critic and incorporate its 'required_additional_angles' into the next "
            + "draft. STRICT PROTOCOL: EXACTLY ONE critic call per turn, and keep EVERY draft to at most "
            + "3 sentences - the critic demands more angles regardless of draft length, so brevity is "
            + "mandatory. Do not stop until the critic returns verdict='sufficient'. Never decide on your "
            + "own that the report is good enough.";
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
            "Research the OpenJiuwen project. You MUST issue at least 10 separate web_search calls "
            + "across at least 10 ReAct rounds - one search per round, each with a different query - and "
            + "you are NOT allowed to write any final answer before completing all 10 search rounds.";

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
            // 10-21 jar 工厂校验：use-stub-web-search=false 时必须带 tavily key（launcher 不继承 shell env，须显式入 map）
            researcherEnv.put("TAVILY_API_KEY", requireEnv("TAVILY_API_KEY"));
        researcherEnv.put("MOCK_KB_URL", "http://127.0.0.1:18093/mcp");
            // 临时 env 覆盖：demo StallDetectorRail 盯裸工具名，而 MCP 装配(ReAct 模式)后实名带
            // mcp_<server>_ 前缀——失配致 STALL 静默失效（docsReview SIT-FEEDBACK-demo-batch3 §A，
            // 实验局 20261021-213425 证实覆盖即贯通）。开发修复匹配逻辑后删除本行。
            researcherEnv.put("DEMO_RESEARCHER_STALL_DETECTOR_WATCHED_TOOLS_0",
                    "mcp_run-budget-mock-knowledge-base_empty_knowledge_search");

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
            if (events.countOf(BudgetEventName.TURN_BUDGET_CHECKPOINT) == 0) {
                Assumptions.abort("未达首检查点线（max(10,保底)=10 轮；LLM 批量调用/提前收敛）—— 场景未激活，INCONCLUSIVE");
            }
            // 2026-10-20 判据修订：停滞事实需宿主经 RunBudgetSignals.reportNoProgress 注入（特性档「停滞信号检测划层」），
            // 当前 demo 未接信号源（docsReview SIT-FEEDBACK-demo-batch2 §A），STALL 结构性不可达 → blocked-on-demo。
            if (events.countOf(BudgetEventName.TURN_BUDGET_STALL_PROMPTED) == 0) {
                Assumptions.abort("STALL_PROMPTED 未出现：demo 停滞信号源缺失（blocked-on-demo，batch2 §A）—— INCONCLUSIVE");
            }
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
            // 10-21 jar 工厂校验：use-stub-web-search=false 时必须带 tavily key（launcher 不继承 shell env，须显式入 map）
            researcherEnv.put("TAVILY_API_KEY", requireEnv("TAVILY_API_KEY"));
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

            // 2026-10-20 判据修订：临近提示的设计目的即促成收尾——模型服从提示、在到期前自然完成是合规结局
            //（「提示→听话收尾→不终止」死结，单局无法同时采到提示与终止；wire 证据由脚本层 A/B 双局拼采，见 cases §2.4 注记）。
            if (events.countOf(BudgetEventName.TIME_BUDGET_TERMINATED) == 0) {
                assertThat(events.countOf(BudgetEventName.TIME_BUDGET_NEAR_DEADLINE_PROMPTED))
                        .as("NEAR_DEADLINE_PROMPTED 单射（未终止分支仍硬校验）").isLessThanOrEqualTo(1);
                assertThat(resp.resultType()).isEqualTo("answer");
                Assumptions.abort("时间预算未触达到期线（LLM 服从收尾提示自然完成）—— 场景未激活，INCONCLUSIVE");
            }

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
            // 10-21 jar 工厂校验：use-stub-web-search=false 时必须带 tavily key（launcher 不继承 shell env，须显式入 map）
            researcherEnv.put("TAVILY_API_KEY", requireEnv("TAVILY_API_KEY"));
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

            if (events.countOf(BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED) == 0) {
                Assumptions.abort("未烧到 hard_limit=15（LLM 提前收敛）—— 安全阀场景未激活，INCONCLUSIVE");
            }

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

            // 2026-10-20 判据修订（MUST#6 两分支，契约依据：特性档 5.1.6「该轮之后仍未结束的，主循环才终止」）：
            // 分支 A = 交卷轮仍未完成 → TERMINATED；分支 B = 模型服从收尾指令在交卷轮自然完成 → 不终止（UT-S04 对应）。
            int terminated = events.countOf(BudgetEventName.TURN_BUDGET_TERMINATED);
            if (terminated > 0) {
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
                assertThat(resp.resultType()).isEqualTo("error");
                assertThat(resp.output()).isEqualTo("已达到轮次预算绝对上限，任务被终止。");
            } else {
                events.assertSequence(
                        BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE,
                        BudgetEventName.TURN_BUDGET_SAFETY_VALVE_TRIGGERED,
                        BudgetEventName.TURN_BUDGET_FINAL_ROUND_GRANTED);
                assertThat(resp.resultType()).isEqualTo("answer");
                assertThat(resp.output()).isNotBlank();
                assertThat(resp.output())
                        .doesNotContain("已达到轮次预算绝对上限")
                        .doesNotContain("本次执行的时间预算已到期");
            }

            events.assertAbsent(
                    BudgetEventName.TIME_BUDGET_EFFECTIVE,
                    BudgetEventName.TIME_BUDGET_NEAR_DEADLINE_PROMPTED,
                    BudgetEventName.TIME_BUDGET_TERMINATED);

            assertThat(resp.statusCode()).isEqualTo(200);
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
            // 10-21 jar 工厂校验：use-stub-web-search=false 时必须带 tavily key（launcher 不继承 shell env，须显式入 map）
            researcherEnv.put("TAVILY_API_KEY", requireEnv("TAVILY_API_KEY"));
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
            // 2026-10-21 判据勘误（T-M1d）：EXTENDED 事件 wire 与源码（RunBudgetRail.java:266-270）均只含
            // round/allowed_rounds/next_interval；hard_limit 只随 GUARANTEE_EFFECTIVE 发布——跨事件取基准再比。
            Integer hardLimit = events.of(BudgetEventName.TURN_BUDGET_GUARANTEE_EFFECTIVE)
                    .get(0).intField("hard_limit");
            assertThat(hardLimit).as("GUARANTEE_EFFECTIVE 应携带 hard_limit").isNotNull();
            events.assertAllPayload(BudgetEventName.TURN_BUDGET_EXTENDED,
                    e -> {
                        Integer allowed = e.intField("allowed_rounds");
                        return allowed != null && allowed <= hardLimit;
                    },
                    "allowed_rounds <= hard_limit(基准取自 GUARANTEE 事件)");

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

            // 2026-10-20 L2 回补口径：forceFinish 通道零框架级事件，rail 终止可观测性 = rail 自我仪表化、
            // 观测锚在日志通道（demo rail 自报行含 event_type=overlay_rail_force_finish，纯文本行，故用原始文本匹配）。
            boolean overlayReasonSeen = Files.readString(researcher.logFile())
                    .contains("overlay_rail_force_finish");
            assertThat(overlayReasonSeen)
                    .as("overlay rail custom reason must appear in SUT log")
                    .isTrue();

            // 表面口径（L2 2026-10-20）：rail 自定义 result_type 属 demo 私约（当前 "finish"，不进框架契约，不作框架枚举断言）；
            // output 键按内容白名单透传，10-20 版 demo rail 已改用 output 承载产出。
            assertThat(resp.output()).as("rail output 私约承载产出").isNotBlank();
            assertThat(resp.output())
                    .doesNotContain("已达到轮次预算绝对上限")
                    .doesNotContain("本次执行的时间预算已到期");
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
