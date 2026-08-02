package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.WireLoggerResolver;
import com.huawei.ascend.sit.transport.A2aEventMapping;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.InboundExchange;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.huawei.ascend.sit.transport.OutboundMessage;
import com.huawei.ascend.sit.transport.SessionLabels;
import com.huawei.ascend.sit.transport.WireLogger;
import com.huawei.ascend.sit.transport.WireRequestRenderer;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 费用报销审核 Workflow Agent —— 端到端验收模板基类（人工审批中断/恢复 + 条件路由自动通过）。
 *
 * <p>驱动 {@code com.huawei.ascend:expense-review-workflow:0.2.0-SNAPSHOT}（单 jar 双 profile）：
 * <ul>
 *   <li>{@code expense-review-workflow}（默认 profile）—— 8 节点 DAG：Start→LLM(analyze)→Tool(check_policy)→
 *       LLM(audit)→Branch(route)→[risk=high: Questioner(approve)] 或 [risk=none: LLM(auto_approve)]→End。</li>
 *   <li>{@code expense-review-main}（{@code main} profile）—— 主控 ReActAgent，由 LLM 决策把报销请求作为
 *       远程 A2A 工具 {@code review_expense} 调用 workflow。</li>
 * </ul>
 *
 * <p><b>seam</b>：{@link #buildStack}（栈/中间件，abstract，继承自 {@link BaseManagedStackTest}）—— 叶子决定
 * in-memory 或 redis 中间件。流程、断言、协议参数化（4 种线协议）全部固定在基类（{@code final} 模板方法），叶子不改。
 *
 * <p><b>两场景</b>（对齐 README 场景 1/2），均经 {@code expense-review-main} 驱动（完整拓扑 main→workflow 远程 A2A）：
 * <ul>
 *   <li><b>场景 1 — 超标报销 → 人工审批（Path A）</b>：住宿 800/晚 &gt; 600、客户晚餐 800 &gt; 300 ⇒ {@code risk=high}
 *       ⇒ Questioner ⇒ 流式 {@code SUBMITTED → WORKING → INPUT_REQUIRED}（审批提示）；续接 {@code "approved"}
 *       （{@link InteractionFlow} 续轮自动携 taskId+contextId 续传原任务）⇒ {@code … → COMPLETED}。</li>
 *   <li><b>场景 2 — 合规报销 → 自动通过（Path B）</b>：全部条目在限额内 ⇒ {@code risk=none} ⇒ auto_approve ⇒
 *       流式 {@code SUBMITTED → WORKING → COMPLETED}（无需人工审批）。</li>
 * </ul>
 *
 * <p><b>场景 3 —— A2A SUBSCRIBE 订阅观察（传输层，非参数化）。</b>除上述两个业务场景外，基类还含一个 {@code @Test}
 * （非参数化，固定 {@code A2A_STREAM}）覆盖 {@link MessageProtocol} 的订阅传输：发送超标报销至 {@code INPUT_REQUIRED}
 * （稳定暂停的非终态）→ 旁路通道订阅该 taskId → 断言快照 → APPROVE 续轮驱动至 {@code COMPLETED} → 断言订阅通道观察到
 * 完整生命周期 {@code INPUT_REQUIRED → WORKING → COMPLETED}。订阅不能作为会话起点，故不与场景 1/2 同形参数化；
 * in-memory/redis/redis-cluster 三叶子经各自 {@link #buildStack} 自动继承运行。详见 {@link #subscribeObservesInputRequiredSnapshotAndLifecycle}。
 *
 * <p><b>协议参数化（4 种线协议）。</b>两场景各以 {@code @ParameterizedTest} 覆盖 {@link MessageProtocol} 全部四值
 * （A2A / REST 各一对流式·sync），与 {@code StreamingTravelPlanningTest} 同型——这是一面调试矩阵：哪个协议单元变红，
 * 就指向哪个 transport adapter 还没接好。每个调用以 {@code <scenario>-<protocol>} 为 {@code sessionId}，故八次调用不撞。
 *
 * <p><b>分协议断言（两层）：</b>{@code .awaitState(...)} 对四协议都严格（{@code InboundExchange} 已归一化终态/中断态），
 * 与 transport 无关；流式状态轨迹（{@code SUBMITTED → WORKING → terminal}）仅断言于 {@code A2A_STREAM}——sync 与 REST
 * 只呈现终态（已由 {@code .awaitState(...)} 覆盖），故 {@link #assertStreamTrajectory} 对其余三协议为 no-op。
 *
 * <p><b>调试矩阵：</b>场景 2（单轮 {@code COMPLETED}，终态可达）四协议应全绿；场景 1 是多轮 {@code INPUT_REQUIRED}，
 * 该中断态仅在 {@code A2A_STREAM} 下可靠呈现——sync/REST 下可能失败或超时，正是"这些 transport 尚未呈现
 * INPUT_REQUIRED"的信号（REST 侧即网关 INPUT_REQUIRED 透传仍未标定，属独立问题）。
 *
 * <p><b>断言形式。</b>该 SUT 把结果发在自定义 {@code workflow_final} 类型（非标准 {@code answer}）下，故各轮文本断言用
 * {@code .assertGenerated(...)}（读 {@code generatedText()} 超集，覆盖 {@code llm_output}/{@code content}/{@code answer}）；
 * {@code workflow_final.payload.output} 经共享分类器 {@code LlmPayload} 认作 {@code answerText()}（{@code TYPE_WORKFLOW_FINAL}→
 * {@code ANSWER}，两协议同构）。语义上 {@code workflow_final} 即结果。
 *
 * @see ExpenseReviewAcceptanceTest in-memory 变体
 * @see ExpenseReviewRedisAcceptanceTest redis 中间件变体
 * @see com.huawei.ascend.sit.cases.integration.react_travel.StreamingTravelPlanningTest
 */
@Tag("integration")
abstract class AbstractExpenseReviewAcceptanceTest extends BaseManagedStackTest {

    /** 驱动入口：主控 ReActAgent（完整拓扑 main→workflow）。需兜底时一行切 "expense-review-workflow"。 */
    protected static final String ENTRY_AGENT = "expense-review-main";
    /** 内嵌 8 节点 DAG 的 workflow agent，被 main 作为远程 A2A 工具 review_expense 调用。 */
    protected static final String WORKFLOW_AGENT = "expense-review-workflow";

    /** 场景 1 —— 超标报销：住宿 800/晚 &gt; 600、客户晚餐 800 &gt; 300 ⇒ risk=high ⇒ 人工审批。 */
    private static final String OVER_LIMIT_EXPENSE =
            "帮我审核这笔报销：机票5000，酒店3晚每晚800共2400，客户晚餐800";

    /** 场景 1 续轮 —— 经理审批通过，续传原任务恢复 workflow。 */
    private static final String APPROVE = "approved";

    private static final Logger LOG = Logger.getLogger(AbstractExpenseReviewAcceptanceTest.class.getName());

    /** 场景 2 —— 合规报销：机票 3000≤5000、住宿 500≤600、餐 200≤300 ⇒ risk=none ⇒ 自动通过。 */
    private static final String COMPLIANT_EXPENSE =
            "审核这笔报销：机票3000，酒店2晚每晚500共1000，餐费200";

    // buildStack(TestConfig) 不在此实现 —— 继承自 BaseManagedStackTest 仍为 abstract，叶子类 override 它切换中间件。

    // ---- 场景 1：超标报销 → 人工审批 → 续接恢复（Path A）----

    /**
     * 超标报销触发 {@code INPUT_REQUIRED}（审批提示），续接 {@code "approved"} 恢复至 {@code COMPLETED}。
     * 参数化覆盖全部四种线协议。
     *
     * <p>轮 1 流式 {@code SUBMITTED → WORKING → INPUT_REQUIRED}（审批提示，非空）；
     * 轮 2（续轮，携原 taskId+contextId）以 {@code WORKING → COMPLETED} 收尾（容忍续轮是否重发 {@code SUBMITTED}），
     * 审核结果非空。多轮 {@code INPUT_REQUIRED} 仅在 {@code A2A_STREAM} 下可靠呈现（见类注释调试矩阵）；状态轨迹
     * 仅在 {@code A2A_STREAM} 下断言，其余三协议 {@link #assertStreamTrajectory} 为 no-op（终态由 {@code .awaitState(...)} 覆盖）。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC", "REST_QUERY", "REST_QUERY_SYNC", "REST_REACTIVE", "REST_REACTIVE_SYNC"})
    @DisplayName("场景1: 超标报销 → INPUT_REQUIRED → 续接 approved → COMPLETED（Path A）")
    protected final void overLimitExpenseRequiresApprovalThenCompletesOnApprove(MessageProtocol protocol) {
        InteractionFlow.of(client(ENTRY_AGENT))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "expense-review-main"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                // 轮 1 — 超标（住宿 800>600、晚餐 800>300）：workflow 走 risk=high ⇒ Questioner 审批节点。
                .send(OVER_LIMIT_EXPENSE)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "场景1 轮1 流式状态序列: SUBMITTED → WORKING → INPUT_REQUIRED",
                            false, TaskState.TASK_STATE_INPUT_REQUIRED))
                    .assertGenerated(generated -> assertThat(generated)
                            .as("轮1 回复（审批提示）非空（多为 llm_output/content，无离散 ANSWER）")
                            .isNotBlank())
                // 轮 2 — 经理审批。InteractionFlow 续轮自动携 taskId+contextId 续传原任务，workflow 恢复至 End。
                .send(APPROVE)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "场景1 轮2 流式状态含 WORKING → COMPLETED（续轮——SUBMITTED 可选）",
                            true, TaskState.TASK_STATE_COMPLETED))
                    .assertGenerated(generated -> assertThat(generated)
                            .as("轮2 审核结果非空（workflow_final 在干净帧计入 answerText，两协议同构）")
                            .isNotBlank())
                .execute();
    }

    // ---- 场景 2：合规报销 → 自动通过（Path B）----

    /**
     * 合规报销（全部条目在限额内）⇒ {@code risk=none} ⇒ auto_approve ⇒
     * 流式 {@code SUBMITTED → WORKING → COMPLETED}（无需人工审批），回答非空且实质。
     * 参数化覆盖全部四种线协议；单轮 {@code COMPLETED}（终态可达）四协议应全绿（见类注释调试矩阵）。
     * 状态轨迹仅在 {@code A2A_STREAM} 下断言，其余三协议 {@link #assertStreamTrajectory} 为 no-op（终态由 {@code .awaitState(...)} 覆盖）。
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = MessageProtocol.class, mode = EnumSource.Mode.INCLUDE,
            names = {"A2A_STREAM", "A2A_SYNC", "REST_QUERY", "REST_QUERY_SYNC", "REST_REACTIVE", "REST_REACTIVE_SYNC"})
    @DisplayName("场景2: 合规报销 → 自动通过 COMPLETED（Path B）")
    protected final void compliantExpenseAutoApprovesAndCompletes(MessageProtocol protocol) {
        InteractionFlow.of(client(ENTRY_AGENT))
                .protocol(protocol)
                .withMetadata(Map.of("userId", "manual-user", "agentId", "expense-review-main"))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send(COMPLIANT_EXPENSE)
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertThat(assertStreamTrajectory(protocol,
                            "场景2 流式状态序列: SUBMITTED → WORKING → COMPLETED",
                            false, TaskState.TASK_STATE_COMPLETED))
                    .assertGenerated(generated -> {
                        assertThat(generated).as("自动通过结果非空").isNotBlank();
                        assertThat(generated.length())
                                .as("结果实质（非空错误/拒答）")
                                .isGreaterThan(8);
                    })
                .execute();
    }

    // ---- 场景 3：A2A SUBSCRIBE 订阅观察（传输层，非参数化）----

    /**
     * A2A {@code SubscribeToTask} 验收（A2A_STREAM 驱动 R1/R3 + SDK 订阅）。<b>[DISABLED — SDK 客户端 SSE 行读硬上限]</b>
     *
     * <p><b>DISABLED 原因（根因 = SDK 缺陷，非 SUT 缺陷）：</b>SDK 的 {@code ServerSentEventParser.MAX_LINE_LENGTH=65536}
     * （64KB/行，<b>不可配</b>，{@code a2a-java/http-client/.../ServerSentEventParser.java:20}）。订阅首帧快照由服务端
     * {@code onSubscribeToTask} 经 {@code insertingProcessor(..., task)} 把<b>整个任务</b>序列化为<b>单条</b> SSE {@code data:}
     * 行；而本 SUT 的 workflow 在流式 LLM 节点会累积大量 per-chunk {@code llm_reasoning}/{@code llm_usage} artifact（每 token
     * 一对），A2A_STREAM 驱动下单条快照行远超 64KB → SDK 的 {@code Client.subscribeToTask} 读到首帧即抛
     * {@code IllegalArgumentException: Line exceeds maximum length of 65536 characters} → {@code onError} →
     * {@code CancellationException: Request cancelled}。
     *
     * <p><b>已排除的它因：</b>服务端正常——探针/curl 直读同一 {@code /a2a} 端点 = {@code 200 text/event-stream} + 合法
     * INPUT_REQUIRED 快照；{@code onSubscribeToTask} 对 INPUT_REQUIRED 无抛错路径（{@code isFinal()=false}，快照经
     * {@code insertingProcessor} 预置）。纯 SDK 行读上限过小（artifact 累积是 workflow 合法行为）。errorHandler 已存证
     * 上述错因（不再被原 no-op 吞掉）。
     *
     * <p><b>修复方向（任一即可恢复本测试）：</b>① SDK 侧放开/抬高 {@code MAX_LINE_LENGTH}；② 或服务端订阅首帧只发
     * <b>精简快照</b>（剥 artifact）。在任一修复前本测试必失败，故 DISABLED。
     *
     * <p><b>尝试性变体：</b>{@link #subscribeObservesLifecycleWhenDrivenBySync} 用 A2A_SYNC 驱动 R1/R3，探查 sync 是否
     * 不累积流式 artifact（快照更小、落在 64KB 内）——假设未经真机验证。
     *
     * <p><b>原设计说明（保留供恢复参考）：</b>订阅通过客户端直驱（测试持有 {@link InboundExchange}）——对 SDK 阻塞/异步
     * 读 SSE 都鲁棒，且可在 APPROVE 前 await 到快照帧消除终态竞态。{@code A2aSubscribeTransport} 本身由其单元测试覆盖。
     * 场景结构（R1→INPUT_REQUIRED→SDK 订阅→R3 APPROVE→COMPLETED）抽到 {@link #driveOverLimitToInputRequired} +
     * {@link #observeSubscribeLifecycle}，本测试与 sync 变体共享。
     *
     * <p><b>轨迹断言契约：</b>{@code containsSubsequence(INPUT_REQUIRED, WORKING, COMPLETED)} 编码"SUT 向订阅者广播中间
     * 状态迁移"假设——若真机 SUT 仅推快照+终态而跳过 WORKING，本断言会失败（定位为 SUT 订阅广播行为，非测试缺陷）。
     */
    @Test
    @Disabled("SDK ServerSentEventParser 硬上限 64KB/行 (MAX_LINE_LENGTH=65536, 不可配); 本 SUT 流式累积 artifact 使订阅首帧快照单行超限 → subscribeToTask 抛错 cancel. 服务端正常(探针/curl=200 SSE). 根因在 SDK, 见 javadoc + subscribeObservesLifecycleWhenDrivenBySync")
    @DisplayName("场景3: A2A_SUBSCRIBE 订阅 INPUT_REQUIRED 任务 → APPROVE 驱动 → COMPLETED（DISABLED: SDK 64KB SSE 行上限）")
    protected final void subscribeObservesInputRequiredSnapshotAndLifecycle() {
        long timeoutMs = config.getPollTimeoutSeconds() * 1000L;
        Map<String, Object> meta = Map.of("userId", "manual-user", "agentId", "expense-review-main");
        SubscribeTarget target = driveOverLimitToInputRequired(MessageProtocol.A2A_STREAM, timeoutMs, meta);
        observeSubscribeLifecycle(MessageProtocol.A2A_STREAM, target, timeoutMs, meta);
    }

    /**
     * 尝试性变体：用 {@link MessageProtocol#A2A_SYNC} 驱动 R1（超标报销→INPUT_REQUIRED）与 R3（APPROVE→COMPLETED），
     * 订阅仍走 SDK 的 {@code subscribeToTask}。<b>假设</b>：A2A_SYNC（{@code message/send}）不产生 A2A_STREAM 那种
     * per-token 流式 artifact，任务快照更小、可能落在 SDK 的 64KB SSE 行上限内 → SDK 订阅能读到首帧。
     *
     * <p><b>不确定点（需真机验证）：</b>① A2A_SYNC 能否可靠呈现 INPUT_REQUIRED（见类注释调试矩阵——sync 下该中断态
     * "可能失败或超时"）；② 任务 artifact 的累积是否真与 R1 客户端读协议无关（服务端处理同构则累积不变，快照仍超
     * 64KB → 本测试同样失败，即证伪假设）。本测试即为此实验，结构与上方 DISABLED 用例同构（共享
     * {@link #driveOverLimitToInputRequired} + {@link #observeSubscribeLifecycle}），仅 R1/R3 协议不同。
     */
    @Test
    @DisplayName("场景3(sync): A2A_SYNC 驱动 R1/R3 + SDK 订阅观察生命周期（探查快照是否落在 64KB 内）")
    protected final void subscribeObservesLifecycleWhenDrivenBySync() {
        long timeoutMs = config.getPollTimeoutSeconds() * 1000L;
        Map<String, Object> meta = Map.of("userId", "manual-user", "agentId", "expense-review-main");
        SubscribeTarget target = driveOverLimitToInputRequired(MessageProtocol.A2A_SYNC, timeoutMs, meta);
        observeSubscribeLifecycle(MessageProtocol.A2A_SYNC, target, timeoutMs, meta);
    }

    /** R1 产物：可订阅的 taskId + 续轮 R3 所需的 contextId。 */
    private record SubscribeTarget(String taskId, String contextId) { }

    /**
     * Round 1：用指定协议驱动超标报销 → 暂停在 {@code INPUT_REQUIRED}（天然可订阅的非终态），返回可订阅的
     * taskId + 续传 contextId。{@code protocol} 参数化以便 STREAM/SYNC 两种驱动方式复用。
     */
    private SubscribeTarget driveOverLimitToInputRequired(MessageProtocol protocol, long timeoutMs,
                                                          Map<String, Object> meta) {
        InteractionFlow.FlowResult r1 = InteractionFlow.of(client(ENTRY_AGENT))
                .protocol(protocol)
                .withMetadata(meta)
                .withTimeoutMs(timeoutMs)
                .send(OVER_LIMIT_EXPENSE)
                    .awaitState(TaskState.TASK_STATE_INPUT_REQUIRED)
                .execute();
        String taskId = r1.lastTaskId();
        String contextId = r1.round(0).contextId();
        assertThat(taskId).as("round1 产生了可订阅的 taskId").isNotBlank();
        assertThat(contextId).as("round1 产生了 contextId（R3 续传需要）").isNotBlank();
        return new SubscribeTarget(taskId, contextId);
    }

    /**
     * Round 2（SDK 订阅）+ Round 3（{@code r3Protocol} 续轮驱动 APPROVE → COMPLETED）+ 订阅生命周期断言。
     *
     * <p>订阅走 SDK 的 {@code subscribeToTask}（客户端直驱，测试持有 {@link InboundExchange}）。errorHandler 必须
     * 捕获并落日志：SDK 的 {@code subscribeToTask} 是唯一不做 sync 降级的 A2A 调用（{@code sendMessage} 在卡片不
     * 支持流式时回退 {@code message/send}，{@code subscribeToTask} 直接把失败 routed 到 errorHandler），吞掉则
     * 订阅失败只表现为"超时无应答"、真因丢失。{@code r3Protocol} 参数化 R3 的驱动协议（STREAM/SYNC），结构上
     * R3 必须夹在快照断言与终态断言之间——订阅须在 APPROVE 驱动期间观察状态迁移。
     */
    private void observeSubscribeLifecycle(MessageProtocol r3Protocol, SubscribeTarget target,
                                           long timeoutMs, Map<String, Object> meta) {
        String taskId = target.taskId();
        String contextId = target.contextId();
        A2aServiceClient entry = client(ENTRY_AGENT);
        InboundExchange subscribed = new InboundExchange();
        AtomicReference<Throwable> subscribeError = new AtomicReference<>();
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "a2a-subscribe-observer");
            t.setDaemon(true);
            return t;
        });
        try {
            exec.submit(() -> {
                try {
                    LOG.warning("[subscribe-sdk] 调用 subscribeTask（taskId=" + taskId + "）");
                    entry.subscribeTask(taskId,
                            List.of((ClientEvent ev, AgentCard card) -> {
                                // 可观测性：SDK 是否真的投递了事件（探针/curl 已证服务端会推快照）。
                                LOG.warning("[subscribe-sdk] consumer 收到 ClientEvent: "
                                        + ev.getClass().getSimpleName());
                                A2aEventMapping.toEventList(ev).forEach(subscribed::add);
                            }),
                            error -> {
                                // 异步错因（SSE 读 / SDK 行读超 64KB / 服务端以 JSON-RPC error 顶替 SSE / parse）：
                                // 不吞——落日志 + 存证，供下方快照断言随失败信息一起抛出。
                                LOG.log(Level.SEVERE, "A2A_SUBSCRIBE 通道报错（taskId=" + taskId + "）", error);
                                subscribeError.set(error);
                            });
                    LOG.warning("[subscribe-sdk] subscribeTask 已返回（异步 SSE 读已启动）");
                } catch (RuntimeException | Error e) {
                    // 同步错因（transport 构造/postAsyncSSE 失败 → A2AClientException，unchecked）：
                    // 本会在线程池任务里抛出、被 exec.submit 静默吞掉，此处显式捕获并入同一存证。
                    LOG.log(Level.SEVERE, "A2A_SUBSCRIBE 同步调用抛错（taskId=" + taskId + "）", e);
                    subscribeError.set(e);
                }
            });

            // 在 APPROVE 之前 await 到快照帧 —— 证明订阅已挂载，消除终态竞态。
            // 若订阅报错，错因随断言失败信息一起抛出（而非吞成无差别的超时）。
            TaskState firstSnapshot = subscribed.awaitAnyState(timeoutMs);
            assertThat(firstSnapshot)
                    .as("订阅首帧快照状态 = INPUT_REQUIRED；若订阅通道报错，错因为: %s",
                            subscribeError.get() == null
                                    ? "<无错因捕获 —— 纯超时 / 服务端未投递快照帧>"
                                    : subscribeError.get())
                    .isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);

            // --- Round 3: APPROVE 续轮（新 flow，withTaskId+withContextId 续传原任务）→ COMPLETED ---
            // withRoundOffset(2): 本流内部是 round 1,但它是该 session 的第 3 个逻辑轮(R1=超标报销 r1、
            // R2=订阅 r2、R3=approve),偏移 2 使 wire-log 记为 r3,避免与 R1 的 r1 同名覆盖。
            InteractionFlow.of(client(ENTRY_AGENT))
                    .protocol(r3Protocol)
                    .withMetadata(meta)
                    .withTimeoutMs(timeoutMs)
                    .withTaskId(taskId)
                    .withContextId(contextId)
                    .withRoundOffset(2)
                    .send(APPROVE)
                        .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .execute();

            // --- 断言：订阅通道观察到完整生命周期 ---
            assertThat(subscribed.awaitTerminalState(timeoutMs))
                    .as("订阅通道终态 = COMPLETED；若订阅通道中途报错，错因为: %s",
                            subscribeError.get() == null
                                    ? "<无错因捕获 —— 纯超时 / 终态未投递>"
                                    : subscribeError.get())
                    .isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(subscribed.stateTrajectory())
                    .as("订阅轨迹: INPUT_REQUIRED(快照) → WORKING → COMPLETED")
                    .containsSubsequence(
                            TaskState.TASK_STATE_INPUT_REQUIRED,
                            TaskState.TASK_STATE_WORKING,
                            TaskState.TASK_STATE_COMPLETED);
        } finally {
            // 诊断探针先跑（即便落盘/关池抛错也不影响）：绕过 SDK 直发 SubscribeToTask，
            // 打印服务端原始 status / Content-Type / 首帧，区分"服务端正常 SSE 流"与
            // "onSubscribeToTask 抛错 → 返回 JSON-RPC error（非 SSE）"。
            probeSubscribeRawResponse(a2aEndpoint(entry), taskId);
            // 订阅走客户端直驱(旁路 executeRound 的 logRound),须手动落盘其请求/响应为 round 2,
            // 否则 SUBSCRIBE 报文不进 sit-logs/wire。即便上面断言失败也照记(best-effort)。
            logSubscribeWire(taskId, contextId, entry, subscribed);
            exec.shutdownNow();
        }
    }

    // ---- helpers（与 StreamingTravelPlanningTest 同风格）----

    /**
     * 断言 {@link MessageProtocol#A2A_STREAM} 下的流式任务状态轨迹。sync 与 REST transport 只呈现终态（无
     * {@code SUBMITTED}/{@code WORKING}），而终态已由上游 {@code .awaitState(...)} 拘住——故对这些协议无可额外
     * 校验，本断言器为 no-op。{@code continuation} 切换流式匹配：严格（{@code containsExactly}：
     * {@code SUBMITTED → WORKING → terminal}，新轮）或宽松（{@code containsSubsequence}：{@code WORKING → terminal}，
     * {@code SUBMITTED} 可选，续轮）——运行时恢复进行中的任务时可能合法重发 {@code SUBMITTED}。
     */
    private static Consumer<InteractionFlow.RoundContext> assertStreamTrajectory(
            MessageProtocol protocol, String description, boolean continuation, TaskState terminal) {
        return ctx -> {
            if (protocol != MessageProtocol.A2A_STREAM) {
                return;
            }
            List<TaskState> trajectory = distinctStatesInOrder(ctx.events());
            if (continuation) {
                assertThat(trajectory)
                        .as(description)
                        .containsSubsequence(TaskState.TASK_STATE_WORKING, terminal);
            } else {
                assertThat(trajectory)
                        .as(description)
                        .containsExactly(
                                TaskState.TASK_STATE_SUBMITTED,
                                TaskState.TASK_STATE_WORKING,
                                terminal);
            }
        };
    }

    /** 去重保序的流式任务状态轨迹（多次 WORKING 进度只计一次），用于断言状态机序列。 */
    private static List<TaskState> distinctStatesInOrder(List<InboundEvent> events) {
        List<TaskState> seen = new ArrayList<>();
        for (InboundEvent e : events) {
            if (e.kind() == InboundEvent.Kind.STATE && !seen.contains(e.state())) {
                seen.add(e.state());
            }
        }
        return seen;
    }

    /**
     * 手动把客户端直驱的订阅旁路通道落盘成一份 wire-log。{@code client.subscribeTask} 绕过了
     * {@code InteractionFlow.executeRound} 的 {@link WireLogger#logRound},故订阅的请求(SUBSCRIBE 信封)
     * 与响应(完整生命周期事件)默认不进 sit-logs/wire。这里复刻 executeRound 的落盘:同一 {@link WireLogger}
     * ({@link WireLoggerResolver#resolved()})、同一 session 标签、渲染同一份 paste-ready 请求,记为 round 2
     * (R1=超标报销 r1、R2=订阅 r2、R3=approve 经 {@code withRoundOffset(2)} 记为 r3)。Best-effort:任何异常
     * 都吞掉,落盘永不中断测试(与 {@code FileWireLogger} 吞 IO 的契约一致)。
     */
    private static void logSubscribeWire(String taskId, String contextId,
                                         A2aServiceClient entry, InboundExchange subscribed) {
        try {
            WireLogger logger = WireLoggerResolver.resolved();
            if (!logger.enabled()) {
                return;
            }
            OutboundMessage request = new OutboundMessage(null, null, taskId, contextId);
            String wireRequest = WireRequestRenderer.render(
                    MessageProtocol.A2A_SUBSCRIBE, request, a2aEndpoint(entry));
            logger.logRound(MessageProtocol.A2A_SUBSCRIBE.name(), 2,
                    SessionLabels.resolveLogName(contextId), request, subscribed.events(), wireRequest, null);
        } catch (RuntimeException e) {
            // 落盘是 best-effort:不中断测试。
        }
    }

    /**
     * 诊断探针：绕过 SDK，对 SUT 的 A2A 端点直接发一记 {@code SubscribeToTask}，打印服务端原始应答
     * （状态码 / Content-Type / 首 ~2KB）。用于定位订阅"零事件超时"的真因——
     * <ul>
     *   <li>Content-Type 为 {@code application/json}（+ error 体）⇒ {@code onSubscribeToTask} 抛错，
     *       服务端以 JSON-RPC error 顶替了 SSE 流；客户端 SSE reader 见不到 data: 行 ⇒ 无差别超时。</li>
     *   <li>Content-Type 为 {@code text/event-stream} 且首帧即 INPUT_REQUIRED 快照 ⇒ 服务端正常，
     *       问题在 SDK 投递或反向代理缓冲。</li>
     *   <li>{@code text/event-stream} 但 4s 内零字节 ⇒ 快照未投递 / 被代理缓冲。</li>
     * </ul>
     * Best-effort：任何异常只落日志，不中断测试。
     */
    private static void probeSubscribeRawResponse(String endpoint, String taskId) {
        String jsonRpc = "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"SubscribeToTask\","
                + "\"params\":{\"id\":\"" + taskId + "\"}}";
        LOG.warning("[subscribe-probe] POST " + endpoint + "  SubscribeToTask  id=" + taskId);
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("A2A-Version", "1.0")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonRpc))
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> resp = http.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            String ct = resp.headers().firstValue("Content-Type").orElse("<none>");
            LOG.warning("[subscribe-probe] status=" + resp.statusCode() + "  Content-Type=" + ct);
            java.io.InputStream bodyStream = resp.body();
            StringBuilder first = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[2048];
                    int n = bodyStream.read(buf);
                    if (n > 0) {
                        first.append(new String(buf, 0, n));
                    }
                } catch (java.io.IOException ignored) {
                    // 关闭/中断：忽略
                }
            });
            reader.setDaemon(true);
            reader.start();
            reader.join(4000L);            // SSE 流可能一直开着，最多等 4s 首帧
            try {
                bodyStream.close();        // 解除 read 阻塞、结束服务端 SseEmitter
            } catch (java.io.IOException ignored) {
                // 忽略
            }
            LOG.warning("[subscribe-probe] first-bytes=" + (first.length() == 0
                    ? "<<空——4s 内服务端未投递任何字节>>" : first));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[subscribe-probe] 探针失败", e);
        }
    }

    /**
     * A2A JSON-RPC 端点 URL：取 agent card 第一个 supportedInterface 的 url（= {@code /a2a} JSON-RPC 端点），
     * 与 SDK {@code JSONRPCTransport} 投递目标对齐——SDK 用
     * {@code Utils.buildBaseUrl(agentCard.supportedInterfaces().get(0), tenant)}（参见 {@code getFavoriteInterface}）。
     * 注意 {@code agentCard.url()} 是基地址（不含 {@code /a2a}），探针打到那里会 404——这正是上一轮探针的 bug
     * （base URL 无 handler ⇒ 404，而 curl 命中 {@code /a2a} 成功取到 SSE 快照）。
     * 兜底：agent card / interfaces 缺失时拼 {@code baseUrl + "/a2a"}。
     */
    private static String a2aEndpoint(A2aServiceClient client) {
        AgentCard card = client.getAgentCard();
        if (card != null && card.supportedInterfaces() != null && !card.supportedInterfaces().isEmpty()) {
            return card.supportedInterfaces().get(0).url();
        }
        String base = client.getBaseUrl();
        return (base == null || base.isBlank()) ? null : base.replaceAll("/+$", "") + "/a2a";
    }
}
