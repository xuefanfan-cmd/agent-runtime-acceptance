package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-06 — 跨 session 的用户级记忆（同 userId 召回历史推荐）.
 *
 * <p>四路严格串行：plan-A（成都→南京）→ plan-B（广州→杭州）→ recall-NJ → recall-HZ。
 * 4 路共享 {@code metadata.userId}，各自独立 {@code message.contextId}。
 * plan 阶段从 artifact 文本正则抽出实际推荐的 brand-token；recall 阶段断言文本命中
 * 各自 plan 的 brand-token 且不交叉污染。
 *
 * <p>判定四档：A plan 可观测到非空品牌 / B recall-NJ 命中 brand-A /
 * C recall-HZ 命中 brand-B / D 两路 recall 不交叉污染。详见 docs/cases/B-06-*.md。
 *
 * <p>顺序刻意排成 plan-A → plan-B → recall-NJ → recall-HZ，让 plan-B 在 recall-NJ
 * 之前落地——同时验证 plan-B 不会冲走 NJ 维度的 memory 条目（同 userId 内
 * 多 destination 共存）。
 *
 * <p>同 {@link ConcurrentSessionIsolationTest}，本类依赖三 agent 全链 + 真 LLM，
 * 归 {@code integration} 层。栈走默认 streaming 模式。
 *
 * <p>本类不走 {@link com.huawei.ascend.sit.client.InteractionFlow}—— DSL 把"每轮自动续
 * contextId"封进 {@code executeRound}，对本用例反而屏蔽了我们要观测的"同 userId 不同
 * contextId"协议面。
 */
@Tag("integration")
class CrossSessionUserMemoryTest extends BaseManagedStackTest {

    private static final String CASES_RESOURCE =
            "testdata/integration/react_travel/b06-memory-cases.json";
    private static final long ROUND_TIMEOUT_MS = 240_000;
    private static final String MAINPLAN = "mainplan";
    private static final String TRIP = "trip";
    private static final String HOTEL = "hotel";

    // 品牌抽取：取"酒店"二字前的 2-5 个汉字作为 brand-token，跳过 stop-list 内的泛指词。
    // 规则刻意不预设品牌白名单——brand-token 是 mainplan 返回什么就记什么。详见 B-06 doc §6。
    private static final Pattern HOTEL_BRAND_PATTERN =
            Pattern.compile("([\\u4e00-\\u9fa5]{2,5})酒店");

    // LLM <think> 块剥离：SUT 会把 LLM 思考链原样 stream 进 artifact，里面常带跨 session
    // memory 的诊断痕迹（"系统注入的 Relevant memory 包含杭州/南京两条…"），不算用户可见输出。
    // B-06 全部断言只看用户面，所以统一在 extractArtifactText 里剥掉 <think>...</think>。
    private static final Pattern THINK_BLOCK_PATTERN =
            Pattern.compile("<think>[\\s\\S]*?</think>");
    private static final Set<String> STOP_WORDS = Set.of(
            "四星级", "五星级", "三星级",
            "商务", "经济型", "经济", "快捷", "连锁",
            "推荐", "目标", "预订", "预定",
            "该", "此", "这家", "那家"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .agent(HOTEL)
                .agent(TRIP, a -> a.downstream(HOTEL))
                .agent(MAINPLAN, a -> a.downstream(TRIP));
    }

    @Test
    @DisplayName("B-06: 同 userId 跨 session — plan 阶段推荐的酒店在 recall 阶段被准确召回且不交叉")
    void recallsRememberHotelsAcrossSessionsForSameUser() throws Exception {
        CasesBundle bundle = loadCases();
        A2aServiceClient client = client(MAINPLAN);

        // Per-run 唯一后缀：让 userId / sessionId 在每次跑时都不同，避免 mainplan 的
        // user-level memory 残留前次跑的脏数据（实测残留会让 LLM 把第 N 次跑判为
        // "第 N 次重复同一查询"而拒绝 replan）。同时让 a2a-sdk 服务端不会把上次跑的
        // contextId 误判为续轮。runSuffix 在 4 路之间是同一个值——这是 B-06 的本意。
        String runSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        String sharedUserId = bundle.sharedUserId() + runSuffix;

        // 严格按时序串行。顺序 plan-A → plan-B → recall-NJ → recall-HZ 让 plan-B 在 recall-NJ
        // 之前落地，可同时验证 plan-B 不会冲走 NJ 维度的 memory 条目。
        PlanOutcome planA = runPlan(client, sharedUserId, withSuffix(bundle.pairs().get(0).plan(), runSuffix));
        PlanOutcome planB = runPlan(client, sharedUserId, withSuffix(bundle.pairs().get(1).plan(), runSuffix));
        RecallOutcome recallNJ = runRecall(client, sharedUserId, withSuffix(bundle.pairs().get(0).recall(), runSuffix));
        RecallOutcome recallHZ = runRecall(client, sharedUserId, withSuffix(bundle.pairs().get(1).recall(), runSuffix));

        dumpOutcomesForDiagnostics(planA, planB, recallNJ, recallHZ);

        assertNoStreamErrors(planA, planB, recallNJ, recallHZ);
        assertPlansHaveObservableBrand(planA, planB);
        assertRecallContainsOwnBrand(recallNJ, planA, "B-06.B (NJ)");
        assertRecallContainsOwnBrand(recallHZ, planB, "B-06.C (HZ)");
        assertRecallsDoNotCrossPollinate(recallNJ, recallHZ, planA, planB);
    }

    private static PlanCase withSuffix(PlanCase c, String suffix) {
        return new PlanCase(c.sessionId() + suffix, c.destination(), c.input());
    }

    private static RecallCase withSuffix(RecallCase c, String suffix) {
        return new RecallCase(c.sessionId() + suffix, c.input());
    }

    // ---- single round drivers ----

    private PlanOutcome runPlan(A2aServiceClient client, String userId, PlanCase pc) throws Exception {
        RoundResult r = sendOneTurn(client, userId, pc.sessionId(), pc.input());
        String text = extractArtifactText(r.finalTask());
        String brand = extractFirstHotelBrand(text);
        return new PlanOutcome(pc.sessionId(), pc.destination(), text, brand,
                r.finalState(), r.streamError());
    }

    private RecallOutcome runRecall(A2aServiceClient client, String userId, RecallCase rc) throws Exception {
        RoundResult r = sendOneTurn(client, userId, rc.sessionId(), rc.input());
        String text = extractArtifactText(r.finalTask());
        return new RecallOutcome(rc.sessionId(), text, r.finalState(), r.streamError());
    }

    private RoundResult sendOneTurn(A2aServiceClient client, String userId,
                                    String sessionId, String input) throws Exception {
        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        // metadata.userId 是 4 路共享键 — runtime 据此作为 user-level memory 的 key。
        // 不传 tenantId（runtime 落 "default"，同 A-11-1 口径）。
        Map<String, Object> metadata = Map.of(
                "userId", userId,
                "agentId", "main-plan-agent");
        // sessionId 走 message.contextId — A2aAgentExecutor 从 contextId 派生
        // RuntimeIdentity.sessionId（同 A-11-1 §9 contextId 路由说明）。
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(sessionId)
                .parts(List.of(new TextPart(input)))
                .build();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = streamError::set;

        client.sendMessage(message, metadata, consumers, errorHandler);
        TaskState finalState = collector.awaitTerminalState(ROUND_TIMEOUT_MS);

        String taskId = collector.findFirstTaskId();
        Task finalTask = taskId.isEmpty() ? null : client.getTask(taskId);
        return new RoundResult(finalState, finalTask, streamError.get());
    }

    // ---- diagnostics ----

    /**
     * 临时诊断：dump 四路 artifact 文本头，便于定位 brand 抽取 vs 真 cross-pollination 的歧义。
     * 跑稳后可移除；保留时注意 stdout 噪声。
     */
    private static void dumpOutcomesForDiagnostics(
            PlanOutcome planA, PlanOutcome planB,
            RecallOutcome recallNJ, RecallOutcome recallHZ) {
        System.out.println("==== B-06 diagnostic dump ====");
        System.out.println("[plan-A " + planA.destination() + "] brand='" + planA.extractedBrand()
                + "' state=" + planA.finalState() + "\n  artifact head: "
                + truncate(planA.artifactText(), 400));
        System.out.println("[plan-B " + planB.destination() + "] brand='" + planB.extractedBrand()
                + "' state=" + planB.finalState() + "\n  artifact head: "
                + truncate(planB.artifactText(), 400));
        System.out.println("[recall-NJ] state=" + recallNJ.finalState()
                + "\n  artifact head: " + truncate(recallNJ.artifactText(), 400));
        System.out.println("[recall-HZ] state=" + recallHZ.finalState()
                + "\n  artifact head: " + truncate(recallHZ.artifactText(), 400));
        System.out.println("==== end diagnostic dump ====");
    }

    // ---- B-06 assertions ----

    private static void assertNoStreamErrors(SessionOutcome... outcomes) {
        for (SessionOutcome o : outcomes) {
            Throwable err = o.streamError();
            if (err == null) continue;
            // Post-terminal 清理异常是 SDK 在串行复用 client 时的清理 race：
            // awaitTerminalState 一拿到 terminal 事件就返回（不等 SSE 完全 close），下一路
            // sendMessage 复用 SDK client 时取消上一路的残流，errorHandler 收到
            // CancellationException / IOException("Request cancelled") 等不同包装。
            // 关键定位：finalState.isFinal()=true 表明本路 terminal 事件已收到、协议层数据完整，
            // post-terminal 的清理异常不影响 B-06 业务语义层断言。
            boolean benignCleanup = o.finalState() != null && o.finalState().isFinal();
            assertThat(benignCleanup)
                    .as("session %s 流中产生非预期异常 (finalState=%s): %s",
                            o.sessionId(), o.finalState(), err)
                    .isTrue();
        }
    }

    /** B-06.A — plan 终态为 COMPLETED 且 artifact 能抽出非空 brand-token. */
    private static void assertPlansHaveObservableBrand(PlanOutcome planA, PlanOutcome planB) {
        assertThat(planA.finalState())
                .as("B-06.A: plan-A 应到达 COMPLETED（否则 artifact 业务语义层失去观测面）")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(planB.finalState())
                .as("B-06.A: plan-B 应到达 COMPLETED")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(planA.extractedBrand())
                .as("B-06.A: plan-A artifact 须能抽出实际推荐的酒店品牌\nartifact 文本头 200 字: %s",
                        truncate(planA.artifactText(), 200))
                .isNotBlank();
        assertThat(planB.extractedBrand())
                .as("B-06.A: plan-B artifact 须能抽出实际推荐的酒店品牌\nartifact 文本头 200 字: %s",
                        truncate(planB.artifactText(), 200))
                .isNotBlank();
    }

    /** B-06.B / B-06.C — recall 命中自身 plan 的 brand-token. */
    private static void assertRecallContainsOwnBrand(
            RecallOutcome recall, PlanOutcome plan, String label) {
        assertThat(recall.artifactText())
                .as("%s: recall %s 的 artifact 应包含 plan %s 实际推荐的品牌 '%s'\nrecall 文本头 200 字: %s",
                        label, recall.sessionId(), plan.sessionId(), plan.extractedBrand(),
                        truncate(recall.artifactText(), 200))
                .contains(plan.extractedBrand());
    }

    /**
     * B-06.D — 两路 recall 不交叉污染（仅当 brand-A != brand-B 时可判）.
     *
     * <p>裸品牌 token 不可判：plan 输入里"协议品牌 全季/亚朵/希尔顿欢朋"会被 recall 忠实回放
     * 原始筛选条件，这是召回的合法行为，不是污染。改用两路证据：
     * <ul>
     *   <li>"&lt;brand&gt;酒店" 后缀——只有真的把对方推荐写进答复才会命中；</li>
     *   <li>对方目的地名（南京 / 杭州）——recall-NJ 提到杭州即明确的上下文串流。</li>
     * </ul>
     */
    private static void assertRecallsDoNotCrossPollinate(
            RecallOutcome recallNJ, RecallOutcome recallHZ,
            PlanOutcome planA, PlanOutcome planB) {
        Assumptions.assumeFalse(
                planA.extractedBrand().equals(planB.extractedBrand()),
                "B-06.D INCONCLUSIVE: plan-A / plan-B 巧合推同品牌 '%s'，cross-pollination 观测面坍塌"
                        .formatted(planA.extractedBrand()));
        String brandBHotel = planB.extractedBrand() + "酒店";
        String brandAHotel = planA.extractedBrand() + "酒店";
        assertThat(recallNJ.artifactText())
                .as("B-06.D: recall-NJ 不应包含 plan-B 的实际推荐 '%s' 或目的地 '%s'\nrecall-NJ 文本头 300 字: %s",
                        brandBHotel, planB.destination(), truncate(recallNJ.artifactText(), 300))
                .doesNotContain(brandBHotel)
                .doesNotContain(planB.destination());
        assertThat(recallHZ.artifactText())
                .as("B-06.D: recall-HZ 不应包含 plan-A 的实际推荐 '%s' 或目的地 '%s'\nrecall-HZ 文本头 300 字: %s",
                        brandAHotel, planA.destination(), truncate(recallHZ.artifactText(), 300))
                .doesNotContain(brandAHotel)
                .doesNotContain(planA.destination());
    }

    // ---- text / brand extraction ----

    /**
     * 从拼接后的 artifact 文本里抽出第一个非停用的 "X酒店" 品牌前缀。
     * 不预设品牌白名单——返回 mainplan 实际写在响应里的 2-5 字汉字 token。
     * 抽空返回 ""，由 B-06.A 上层断言转 FAIL（plan 阶段没真给出酒店推荐）。
     */
    private static String extractFirstHotelBrand(String artifactText) {
        if (artifactText == null || artifactText.isEmpty()) return "";
        Matcher m = HOTEL_BRAND_PATTERN.matcher(artifactText);
        while (m.find()) {
            String candidate = m.group(1);
            if (!STOP_WORDS.contains(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * Concatenate the task's textual output: artifacts → status message → last history message.
     * Mirrors {@code ConcurrentSessionIsolationTest.extractArtifactText} 以便统一抽取面，
     * 对 SUT 是否填 {@code artifacts} 不敏感。
     */
    private static String extractArtifactText(Task task) {
        if (task == null) return "";
        StringBuilder sb = new StringBuilder();
        if (task.artifacts() != null) {
            for (Artifact a : task.artifacts()) {
                appendText(sb, a.parts());
            }
        }
        if (sb.length() == 0 && task.status() != null && task.status().message() != null) {
            appendText(sb, task.status().message().parts());
        }
        if (sb.length() == 0 && task.history() != null && !task.history().isEmpty()) {
            appendText(sb, task.history().get(task.history().size() - 1).parts());
        }
        return stripThinkBlocks(sb.toString());
    }

    /** 剥掉 LLM 思考链：&lt;think&gt;...&lt;/think&gt; 不属于用户可见契约，B-06 全部断言只看剥后文本。 */
    private static String stripThinkBlocks(String text) {
        if (text == null || text.isEmpty()) return text;
        return THINK_BLOCK_PATTERN.matcher(text).replaceAll("");
    }

    private static void appendText(StringBuilder sb, List<Part<?>> parts) {
        if (parts == null) return;
        for (Part<?> p : parts) {
            if (p instanceof TextPart tp && tp.text() != null) {
                sb.append(tp.text()).append('\n');
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ---- data loading ----

    private CasesBundle loadCases() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CASES_RESOURCE)) {
            assertThat(is).as("cases resource %s on classpath", CASES_RESOURCE).isNotNull();
            JsonNode root = mapper.readTree(is);
            String sharedUserId = root.get("sharedUserId").asText();
            assertThat(sharedUserId).as("$.sharedUserId").isNotBlank();
            JsonNode arr = root.get("pairs");
            assertThat(arr).as("$.pairs array").isNotNull();
            assertThat(arr.size()).as("B-06 needs exactly 2 (plan, recall) pairs").isEqualTo(2);

            List<PlanRecallPair> pairs = new ArrayList<>();
            for (JsonNode n : arr) {
                JsonNode p = n.get("plan");
                JsonNode r = n.get("recall");
                PlanCase plan = new PlanCase(
                        p.get("sessionId").asText(),
                        p.get("destination").asText(),
                        p.get("input").asText());
                RecallCase recall = new RecallCase(
                        r.get("sessionId").asText(),
                        r.get("input").asText());
                pairs.add(new PlanRecallPair(plan, recall));
            }
            return new CasesBundle(sharedUserId, pairs);
        }
    }

    // ---- inner types ----

    private interface SessionOutcome {
        String sessionId();
        TaskState finalState();
        Throwable streamError();
    }

    private record PlanCase(String sessionId, String destination, String input) {}
    private record RecallCase(String sessionId, String input) {}
    private record PlanRecallPair(PlanCase plan, RecallCase recall) {}
    private record CasesBundle(String sharedUserId, List<PlanRecallPair> pairs) {}

    private record RoundResult(TaskState finalState, Task finalTask, Throwable streamError) {}

    private record PlanOutcome(
            String sessionId,
            String destination,
            String artifactText,
            String extractedBrand,
            TaskState finalState,
            Throwable streamError) implements SessionOutcome {}

    private record RecallOutcome(
            String sessionId,
            String artifactText,
            TaskState finalState,
            Throwable streamError) implements SessionOutcome {}
}