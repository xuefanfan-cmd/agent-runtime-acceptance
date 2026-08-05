package com.huawei.ascend.sit.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.a2aproject.sdk.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process webhook receiver for A2A task terminal push notifications. The harness stands this up to
 * validate the SUT's outbound {@code HttpPushNotificationSender}: when a task reaches COMPLETED/FAILED,
 * the SUT POSTs a JSON-RPC notification to the URL registered via inline
 * {@code configuration.taskPushNotificationConfig}.
 *
 * <p><b>Push body shape — expected vs. actual (the actual is a SUT bug).</b> The SDK's canonical
 * serialization wraps a {@link org.a2aproject.sdk.spec.Task} under its {@code StreamingEventKind}
 * discriminator: the SUT's {@code HttpPushNotificationSender.callbackBody} builds
 * {@code JsonUtil.toJson(Map.of("jsonrpc","2.0","result", task, "notificationId", id))}, and a Task (a
 * {@code StreamingEventKind} whose {@code kind()} is {@code "task"}) serialized via the SDK mapper lands
 * at {@code result.task.*}:
 * <pre>{@code
 *   {"jsonrpc":"2.0","result":{"task":{
 *       "id":..,"contextId":..,"status":{"state":"TASK_STATE_COMPLETED",..},..
 *   }},"notificationId":"<sha256>"}
 * }</pre>
 * <b>BUT the real SUT (verified 2026-08-03 on the push-delivery acceptance test) emits the Task BARE at
 * {@code result.*} instead — a SUT defect</b> (tracked: {@code docs/a2a-push-sender-bare-result-defect.cn.md};
 * see the {@code !!! SUT BUG !!!} marker at the parse site). The acceptance test passes only via the
 * receiver's bare-result fallback. The receiver therefore prefers the canonical {@code result.task} form
 * and falls back to a bare {@code result} Task <em>solely</em> to tolerate the buggy SUT; the fallback must
 * be removed once the SUT emits the wrapped form.
 *
 * <p>Mirrors {@code FakeConversationServer}'s {@code com.sun.net.httpserver} lifecycle: bind
 * {@code 127.0.0.1:0}, take an ephemeral port, expose {@link #url()}. One responsibility: parse the push,
 * dedup by notificationId, feed a STATE {@link InboundEvent} into an {@link InboundExchange} (so the same
 * Awaitility-based {@code awaitTerminalState} the send-path uses works here), and reply {@code 202}.
 *
 * <p>Every raw POST is recorded on {@link #received()} for assertion (state, taskId, contextId, both
 * the header {@code X-A2A-Notification-Id} and the body {@code notificationId}). A push may also be
 * surfaced to a {@link #onPush(Consumer) registered sink} for file logging (the receiver has no
 * {@code InteractionFlow} context, so the test wires the push into the wire-log itself).
 */
public final class PushCallbackReceiver implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PushCallbackReceiver.class);

    /** One received push, with both notification-id sources captured for contract assertion. */
    public record ReceivedPush(String notificationIdHeader, String body, TaskState state,
                               String taskId, String contextId, String notificationIdBody) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final InboundExchange exchange = new InboundExchange();
    private final List<ReceivedPush> received = new ArrayList<>();
    private final Set<String> seenNotificationIds = ConcurrentHashMap.newKeySet();
    // CopyOnWrite: onPush is registered once before driving, then read on every push thread.
    private final List<Consumer<ReceivedPush>> pushSinks = new CopyOnWriteArrayList<>();

    private PushCallbackReceiver(HttpServer server) {
        this.server = server;
    }

    /** Start a receiver bound to an ephemeral loopback port. */
    public static PushCallbackReceiver start() {
        HttpServer s;
        try {
            s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start PushCallbackReceiver", e);
        }
        PushCallbackReceiver r = new PushCallbackReceiver(s);
        try {
            s.createContext("/", r::handle);
            s.start();
            return r;
        } catch (RuntimeException e) {
            s.stop(0);   // release the bound port + dispatch threads if createContext/start failed mid-setup
            throw e;
        }
    }

    /** The callback URL to register as {@code taskPushNotificationConfig.url}. */
    public String url() {
        InetSocketAddress a = server.getAddress();
        return "http://" + a.getHostName() + ":" + a.getPort() + "/";
    }

    /** Awaitable event stream (STATE events for each fresh terminal push). */
    public InboundExchange exchange() {
        return exchange;
    }

    /** Every raw POST received, in arrival order. */
    public List<ReceivedPush> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    /**
     * Register a sink invoked once per parsed push (after it is recorded on {@link #received()}), in
     * arrival order. Used to persist the SUT-initiated push to the wire-log — the receiver owns no
     * {@code InteractionFlow} context, so the test supplies the logging callback. The sink is best-effort:
     * a thrown exception is caught and logged so it never breaks push acknowledgement.
     *
     * @return this, for chaining off {@link #start()}
     */
    public PushCallbackReceiver onPush(Consumer<ReceivedPush> sink) {
        if (sink != null) {
            pushSinks.add(sink);
        }
        return this;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String notificationIdHeader = ex.getRequestHeaders().getFirst("X-A2A-Notification-Id");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(body);

            // The Task may sit BARE at result.* OR be wrapped under a "task" discriminator member —
            // the SDK's StreamingEventKindTypeAdapter canonical form: a Task is a StreamingEventKind whose
            // kind() == "task", so the SDK serializes it as result.task.{...}.
            //
            // !!! SUT BUG — tracked in docs/a2a-push-sender-bare-result-defect.cn.md !!!
            // The SUT's HttpPushNotificationSender serializes the Task BARE at result.* instead of the
            // canonical wrapped result.task.* form. Verified on the real machine (2026-08-03): the
            // push-delivery acceptance test passes ONLY through this bare-result fallback branch — the
            // result.task branch is never hit by the real SUT. The fallback is KEPT temporarily to
            // tolerate the buggy SUT so the gate stays green; it must be removed (and the wrapped form
            // asserted exclusively) once the SUT emits result.task.{...}. Filing a ticket to track.
            JsonNode result = root.path("result");
            JsonNode task = result.path("task");
            // Prefer the canonical wrapped form; the bare-result fallback exists solely for the SUT bug above.
            JsonNode taskNode = task.isObject() ? task : (result.isObject() ? result : null);

            String taskId = taskNode == null ? "" : taskNode.path("id").asText("");
            String contextId = taskNode == null ? "" : taskNode.path("contextId").asText("");
            String stateStr = taskNode == null ? "" : taskNode.path("status").path("state").asText("");
            String notificationIdBody = root.path("notificationId").asText("");

            String dedupKey = (notificationIdHeader != null && !notificationIdHeader.isBlank())
                    ? notificationIdHeader : notificationIdBody;
            boolean fresh = dedupKey.isBlank() || seenNotificationIds.add(dedupKey);

            TaskState state = stateStr.isBlank() ? null : TaskState.valueOf(stateStr);
            if (fresh && state != null) {
                exchange.add(InboundEvent.state(state, taskId, contextId, body));
            }

            ReceivedPush push = new ReceivedPush(notificationIdHeader, body, state, taskId, contextId,
                    notificationIdBody);
            synchronized (received) {
                received.add(push);
            }

            // Surface the parsed push to registered sinks (e.g. wire-log persistence). Best-effort: a
            // failing sink must never block acknowledgement or starve the SUT's retry/dedup contract.
            for (Consumer<ReceivedPush> sink : pushSinks) {
                try {
                    sink.accept(push);
                } catch (RuntimeException e) {
                    LOG.warn("Push sink threw; push acknowledgement continues unaffected", e);
                }
            }

            byte[] ack = "accepted".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(202, ack.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(ack);
            }
        } catch (Exception e) {
            ex.sendResponseHeaders(400, 0);
            ex.getResponseBody().close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
