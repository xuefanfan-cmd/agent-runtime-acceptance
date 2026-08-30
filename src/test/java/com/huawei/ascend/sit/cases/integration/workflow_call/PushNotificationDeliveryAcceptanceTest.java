package com.huawei.ascend.sit.cases.integration.workflow_call;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.client.WireLoggerResolver;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.huawei.ascend.sit.transport.OutboundMessage;
import com.huawei.ascend.sit.transport.PushCallbackReceiver;
import com.huawei.ascend.sit.transport.SessionLabels;
import com.huawei.ascend.sit.transport.WireLogger;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0xx.push-delivery — A2A push-notification <b>delivery</b> acceptance (Direction B: harness as receiver).
 *
 * <p>Two test cases:
 * <ul>
 *   <li>{@link #agentCardAdvertisesPushNotifications()} — the {@code expense-review-main} AgentCard advertises
 *       the {@code pushNotifications} capability (validates Task 6's YAML flag took effect).</li>
 *   <li>{@link #pushDeliveredOnCompleted()} — proves the SUT's outbound {@code HttpPushNotificationSender}
 *       actually POSTs the terminal Task to a client-registered callback URL when the task reaches COMPLETED.</li>
 * </ul>
 * This complements {@code PushConfigCrudTest} (config-storage CRUD) with actual delivery.
 *
 * <p><b>Scenario:</b> {@code expense-review-main} 场景2 (COMPLIANT_EXPENSE) — driven over
 * <b>A2A_SYNC ({@code message/send}) with {@code returnImmediately=true}</b>: the SUT returns on the first
 * Task event (SUBMITTED) — the inline push config is persisted at that point ({@code DefaultRequestHandler:508})
 * — and the task completes in the SUT's background. The send round returns on that first event
 * ({@code mayReachState(TASK_STATE_SUBMITTED)}, lenient); the terminal COMPLETED is then observed via the
 * PUSH on the receiver, not via the send response. This is the canonical "register webhook, wait for
 * completion" pattern and the most robust ordering (config persists unambiguously before COMPLETED fires
 * the push). The default A2A_STREAM issues {@code SendStreamingMessage}, which does not carry the inline
 * config the same way; sync is required.
 *
 * <p><b>SUT wire contract (source-verified vs {@code HttpPushNotificationSender} + the SDK):</b> the push
 * body is {@code {"jsonrpc":"2.0","result":{"task":<Task>},"notificationId":"<id>"}} — the Task is wrapped
 * under {@code result.task} because {@code JsonUtil.OBJECT_MAPPER} registers
 * {@code StreamingEventKindTypeAdapter}, which serializes a {@code Task} (a {@code StreamingEventKind})
 * under its {@code kind()} discriminator {@code "task"} (confirmed by the SUT's own receiver,
 * {@code A2aPushNotificationCallbackController.callbackTask}, parsing {@code result.task}). Header
 * {@code X-A2A-Notification-Id} and body {@code notificationId} are the same value =
 * {@code SHA-256(taskId + ":" + configId)}. Any 2xx acks; dedup by notificationId.
 *
 * <p><b>configId source (source-verified):</b> {@code DefaultRequestHandler} persists the inline config as
 * {@code builder(clientConfig).taskId(createdTask.id()).build()} — it keeps the client-provided {@code id}
 * and only overrides {@code taskId}. {@code InMemoryPushNotificationConfigStore.setInfo} reassigns the id
 * ONLY when the client id is empty; a non-empty {@link #CLIENT_CONFIG_ID} is preserved verbatim. So the
 * stored configId == {@link #CLIENT_CONFIG_ID}, and the SHA-256 is computed from it client-side. (The SUT's
 * JSON-RPC endpoint does NOT expose {@code ListTaskPushNotificationConfig} — "Method not found" — so the id
 * cannot be read back via List; it is taken client-side from the value sent.)
 *
 * <p><b>Real-machine gate:</b> needs a managed SUT stack + {@code LLM_API_KEY}; run on the real machine.
 */
@Tag("integration")
@Feature("FEAT-0xx: A2A Push Notification 投递")
@Story("a2a.push-delivery: webhook 接收端验收 SUT 外推 POST")
class PushNotificationDeliveryAcceptanceTest extends BaseManagedStackTest {

    private static final String ENTRY_AGENT = "expense-review-main";
    private static final String WORKFLOW_AGENT = "expense-review-workflow";

    /** 场景 2 —— 合规报销，单轮自动通过至 COMPLETED（值对齐 AbstractExpenseReviewAcceptanceTest，已验证 auto-approve）。 */
    private static final String COMPLIANT_EXPENSE = "审核这笔报销：机票3000，酒店2晚每晚500共1000，餐费200";

    /**
     * The client-supplied push-config id. Non-empty on purpose: {@code InMemoryPushNotificationConfigStore}
     * reassigns the id only when it is empty, so this exact value becomes the stored configId and feeds the
     * SHA-256 {@code notificationId}. The SUT's JSON-RPC endpoint does not support ListTaskPushNotificationConfig,
     * so the id is asserted client-side from this constant.
     */
    private static final String CLIENT_CONFIG_ID = "sit-inline-push";

    private static final long PUSH_AWAIT_MS = 60_000L;

    private PushCallbackReceiver receiver;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .agent(WORKFLOW_AGENT)
                .agent(ENTRY_AGENT, a -> a.downstream(WORKFLOW_AGENT));
    }

    @AfterEach
    void stopReceiver() {
        if (receiver != null) {
            receiver.close();
        }
    }

    @Test
    @DisplayName("expense-review-main AgentCard 广告 pushNotifications 能力（Task 6 YAML flag 生效）")
    void agentCardAdvertisesPushNotifications() {
        AgentCard card = client(ENTRY_AGENT).getAgentCard();
        assertThat(card.capabilities())
                .as("AgentCard.capabilities 非空（card 解析正常）")
                .isNotNull();
        assertThat(card.capabilities().pushNotifications())
                .as("capabilities.pushNotifications=true（Task 6 YAML openjiuwen.service.a2a.push-notifications 已生效）")
                .isTrue();
    }

    @Test
    @DisplayName("A2A push: COMPLIANT_EXPENSE 单轮 COMPLETED → SUT POST 终态任务到 webhook 接收端")
    void pushDeliveredOnCompleted() throws Exception {
        receiver = PushCallbackReceiver.start();
        // Wire the push into the wire-log on ARRIVAL (issue #2): the push is a server-initiated POST that
        // bypasses InteractionFlow.executeRound's logRound, so the test itself persists it. Registering the
        // sink BEFORE driving is load-bearing — if the send-path or the terminal assertion fails, the push
        // has still been logged the moment it arrived; a post-assertion logPushWire call (the prior design)
        // never ran on failure, hiding the exact delivery we most need to see when diagnosing.
        //
        // SessionLabels is a ThreadLocal bound to THIS (JUnit) thread; the onPush sink fires on the
        // HttpServer's dispatch thread, where the label is unset. Resolving the log name inside the sink
        // would therefore miss the label and name the file after the raw contextId UUID. Capture the label
        // here and replay it in the sink — the same capture-and-replay pattern ParallelStepDriver uses for
        // its virtual threads.
        String pushLogSession = SessionLabels.current();
        receiver.onPush(push -> logPushWire(push, receiver.url(), pushLogSession));
        TaskPushNotificationConfig cfg = TaskPushNotificationConfig.builder()
                .id(CLIENT_CONFIG_ID)   // non-empty → SUT keeps it as the stored configId (drives the SHA-256)
                .url(receiver.url())
                .build();

        // 1) Drive 场景2 over A2A_SYNC with returnImmediately=true (non-blocking message/send): the SUT
        //    returns on the first Task event (SUBMITTED) — at which point the inline push config is already
        //    persisted (DefaultRequestHandler:508) — and the task completes in the SUT's background.
        //    mayReachState (lenient, non-terminal) returns execute() on that first event instead of awaiting
        //    a terminal state the early return will not deliver. The terminal COMPLETED is then observed
        //    via the PUSH (step 2): the canonical "register webhook, wait for completion" pattern, and the
        //    most robust ordering (config persists unambiguously before COMPLETED fires the push).
        InteractionFlow.FlowResult result = InteractionFlow.of(client(ENTRY_AGENT))
                .protocol(MessageProtocol.A2A_SYNC)
                .withReturnImmediately(true)
                .withMetadata(Map.of("userId", "manual-user", "agentId", ENTRY_AGENT))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .withPushNotificationConfig(cfg)
                .send(COMPLIANT_EXPENSE)
                    .mayReachState(TaskState.TASK_STATE_SUBMITTED)
                .execute();
        String taskId = result.lastTaskId();
        String contextId = result.round(0).contextId();
        TaskState earlyReturnState = result.round(0).taskState();

        // 2) Await the push on the receiver. Its arrival itself proves the inline config persisted
        //    (the sender reads it from the store to fire at all). On timeout, fail with a diagnostic
        //    dump of what the receiver DID get + the exact SUT log lines to check — every link of the
        //    push chain is source-verified correct on A2A_SYNC, so a timeout localizes a runtime break.
        TaskState pushed;
        try {
            pushed = receiver.exchange().awaitTerminalState(PUSH_AWAIT_MS);
        } catch (AssertionError timeout) {
            throw new AssertionError(diagnoseNoPush(timeout, taskId, contextId, earlyReturnState, receiver),
                    timeout);
        }

        // 3) Assert the delivered Task identity + state.
        assertThat(pushed).as("receiver 观察到的终态应为 COMPLETED").isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(receiver.received()).as("receiver 应至少收到一条 push").isNotEmpty();
        PushCallbackReceiver.ReceivedPush push = receiver.received().get(0);
        assertThat(push.state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
        assertThat(push.taskId()).as("push body result.task.id 应为被驱动的 taskId").isEqualTo(taskId);
        assertThat(push.contextId()).as("push body result.task.contextId 应为被驱动的 contextId").isEqualTo(contextId);

        // 4) Assert the notification-id contract: header == body == SHA-256(taskId + ":" + configId).
        //    configId = CLIENT_CONFIG_ID (the SUT keeps the non-empty client id — source-verified).
        String expectedId = sha256Hex(taskId + ":" + CLIENT_CONFIG_ID);
        assertThat(push.notificationIdHeader())
                .as("X-A2A-Notification-Id 应等于 SHA-256(taskId:configId)；若不等，SUT 可能用了非默认 configStore 重分配了 id")
                .isEqualTo(expectedId);
        assertThat(push.notificationIdBody())
                .as("body notificationId 应与 header 同值（同一 notificationId 经两路投递）")
                .isEqualTo(push.notificationIdHeader());

        // 5) Persisted on arrival via the onPush sink registered before driving (issue #2: a push that
        //    arrives is logged even if the assertion below it would fail). No post-assertion logPushWire.
    }

    /**
     * On push timeout, surface what the receiver actually got (if anything) and the exact SUT log lines to
     * check. The push is a 6-link chain — client threads config ({@code A2aSyncTransport:48} →
     * {@code A2aServiceClient:245}) → sync handler persists it ({@code DefaultRequestHandler.onMessageSend:508},
     * guarded by {@code shouldAddPushInfo}) → same shared {@code InMemoryPushNotificationConfigStore} bean →
     * {@code MainEventBusProcessor} fires on the terminal {@code StreamingEventKind} ({@code :245}) →
     * {@code HttpPushNotificationSender} reads {@code firstConfig}, checks {@code isCallbackState}, POSTs →
     * URL policy passes loopback. All source-verified, so a timeout means a runtime break this diagnostic
     * localizes via the receiver's raw POSTs + the SUT log.
     *
     * @param earlyReturnState the state the send round returned on (issue #1): SUBMITTED/WORKING means
     *                         {@code returnImmediately=true} was honored (early return); COMPLETED means it
     *                         BLOCKED to terminal instead — the SUT honored returnImmediately=false (or the
     *                         SDK forced a blocking sync), so the push should have fired during the send.
     */
    private static String diagnoseNoPush(AssertionError timeout, String taskId, String contextId,
                                         TaskState earlyReturnState, PushCallbackReceiver receiver) {
        List<PushCallbackReceiver.ReceivedPush> got = receiver.received();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("No terminal push within ").append(PUSH_AWAIT_MS).append("ms")
                .append(" (taskId=").append(taskId)
                .append(", contextId=").append(contextId)
                .append(", receiver=").append(receiver.url()).append(").\n\n");

        if (got.isEmpty()) {
            sb.append("Receiver got 0 POSTs → the SUT never delivered. Driven with returnImmediately=true,\n");
            sb.append("so the task completes in the SUT's BACKGROUND after the send returned; a missing push\n");
            sb.append("means either the background task never reached COMPLETED, or a push-chain link broke.\n");
            sb.append("The send round returned on state ").append(earlyReturnState).append(" — ");
            boolean earlyReturned = earlyReturnState != null && !earlyReturnState.isFinal();
            if (earlyReturned) {
                sb.append("an EARLY return (returnImmediately honored: the send did NOT block to COMPLETED,\n");
                sb.append("so the terminal push was always going to be observed here, on the receiver).\n");
            } else {
                sb.append("a BLOCKED-to-terminal return (returnImmediately was NOT honored — the send\n");
                sb.append("waited for COMPLETED). The push should have fired DURING the send; its absence\n");
                sb.append("here means the terminal→sender dispatch broke. Grep the wire-log request for\n");
                sb.append("\"returnImmediately\":true in params.configuration to confirm what was sent.\n");
            }
            sb.append("\nCheck the SUT log for task ").append(taskId).append(":\n");
            sb.append("  • grep the task id — did it reach COMPLETED in the background? If it stalled at\n");
            sb.append("    WORKING/INPUT_REQUIRED, no terminal push ever fires.\n");
            sb.append("  • grep \"Storing push notification config for new task\" — if ABSENT, the inline config\n");
            sb.append("    was NOT persisted: shouldAddPushInfo() returned false (params.configuration()\n");
            sb.append("    .taskPushNotificationConfig() was null → SDK dropped the inline field on the wire).\n");
            sb.append("    firstConfig(taskId) then reads an empty store → no POST.\n");
            sb.append("  • grep \"Sending push notification for task ").append(taskId).append("\" — if ABSENT,\n");
            sb.append("    the COMPLETED StreamingEventKind never reached MainEventBusProcessor, or taskSnapshot\n");
            sb.append("    was null (isCallbackState(null)=false, silent).\n");
            sb.append("  • grep \"A2A push notification delivery failed\" → POST IOException (SUT process cannot\n");
            sb.append("    reach ").append(receiver.url()).append("; cross-namespace/container loopback).\n");
            sb.append("  • grep \"Rejected A2A push notification\" → callbackUrl policy rejected the URL.\n");
        } else {
            sb.append("Receiver got ").append(got.size())
              .append(" POST(s), but none parsed to a terminal TaskState → result.status.state absent/unparseable.\n");
            for (int i = 0; i < got.size(); i++) {
                PushCallbackReceiver.ReceivedPush p = got.get(i);
                sb.append("  [").append(i).append("] X-A2A-Notification-Id=").append(p.notificationIdHeader())
                  .append(" state=").append(p.state()).append(" taskId=").append(p.taskId()).append('\n');
                sb.append("      body: ").append(truncate(p.body(), 400)).append('\n');
            }
            sb.append("  Expected body: {\"jsonrpc\":\"2.0\",\"result\":{\"task\":{\"id\":..,\n");
            sb.append("  \"status\":{\"state\":\"TASK_STATE_COMPLETED\"},\"contextId\":..}},\n");
            sb.append("  \"notificationId\":\"<sha256>\"} (result.task wrapper — the SDK\n");
            sb.append("  StreamingEventKindTypeAdapter shape the SUT emits). A bare result.{..} is also\n");
            sb.append("  tolerated, but result.task.* is the authoritative SUT shape.\n");
        }
        sb.append("\nOriginal timeout: ").append(timeout.getMessage());
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(" + s.length() + " chars)";
    }

    /**
     * Manually persist the SUT's push POST to the wire-log as round 2 (protocol tag "A2A_PUSH"). The push
     * is a server-initiated direction that bypasses {@code InteractionFlow.executeRound}'s logRound, so the
     * test replays it with the shared logger.
     *
     * <p><b>PUSH is RECEIVE-ONLY.</b> The harness sends nothing in round 2 — it stands up the
     * {@link PushCallbackReceiver} webhook and listens; there is no 发送报文 flow. The SUT's POST is an
     * INBOUND message to the harness, so the whole round is recorded on the RESPONSE side and round 2
     * carries NO wire-request block. The full received HTTP message — request-line, {@code Content-Type},
     * the {@code X-A2A-Notification-Id} header the SHA-256 contract asserts against, and the JSON-RPC body —
     * is the RESPONSE event's raw frame (the concrete 收报文 the SUT delivered), mirroring how a subscribe
     * round keeps each streamed frame as the event raw. The structured REQUEST is sparse (taskId/contextId
     * only — identifying context for correlating with r1, not a sent payload).
     *
     * <p>{@code sessionLabel} is captured on the JUnit thread at registration (see
     * {@link #pushDeliveredOnCompleted()}); the onPush sink runs on the HttpServer's dispatch thread, where
     * the {@code SessionLabels} ThreadLocal is unset, so resolving the name inside the sink would fall back
     * to the raw contextId UUID (the r2 filename bug). Best-effort: any exception is swallowed
     * ({@link PushCallbackReceiver#onPush} also catches it, so a failing log never blocks the 202 ack).
     */
    private static void logPushWire(PushCallbackReceiver.ReceivedPush push, String receiverUrl,
                                    String sessionLabel) {
        try {
            WireLogger logger = WireLoggerResolver.resolved();
            if (!logger.enabled()) {
                return;
            }
            // Label > contextId > nosession — mirrors SessionLabels.resolveLogName. The label was captured
            // on the JUnit thread (the sink's dispatch thread has no ThreadLocal); contextId is the in-sink
            // fallback when no label was captured (extension absent).
            String session = (sessionLabel != null && !sessionLabel.isBlank())
                    ? sessionLabel : push.contextId();
            // Sparse request: identifying context only (the push carries its own taskId/contextId, parsed
            // from the body). text/metadata are null — the harness sends nothing in a receive-only push.
            OutboundMessage request = new OutboundMessage(null, null, push.taskId(), push.contextId());
            // The full received HTTP message (request-line + headers + body) = the concrete 收报文 the SUT
            // delivered. It is the RESPONSE event's raw frame (the inbound message), not a wire-request —
            // the harness did not send it.
            String receivedWire = "POST " + receiverUrl + "\n"
                    + "Content-Type: application/json\n"
                    + "X-A2A-Notification-Id: " + push.notificationIdHeader() + "\n\n"
                    + push.body();
            InboundEvent delivered = push.state() != null
                    ? InboundEvent.state(push.state(), push.taskId(), push.contextId(), receivedWire)
                    : InboundEvent.content("", receivedWire);   // stateless push (parse gap): still log the msg
            logger.logRound("A2A_PUSH", 2, session, request, List.of(delivered), null, null);
        } catch (RuntimeException e) {
            // Wire-log persistence is best-effort; never fail the test.
        }
    }

    /** SHA-256 hex — mirrors HttpPushNotificationSender.notificationId(taskId, configId) exactly. */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
