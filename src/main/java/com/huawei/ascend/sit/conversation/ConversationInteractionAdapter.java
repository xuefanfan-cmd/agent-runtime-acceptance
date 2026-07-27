package com.huawei.ascend.sit.conversation;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.client.WireLoggerResolver;
import com.huawei.ascend.sit.transport.A2aStreamingTransport;
import com.huawei.ascend.sit.transport.A2aStreamingWire;
import com.huawei.ascend.sit.transport.InboundEvent;
import com.huawei.ascend.sit.transport.InboundExchange;
import com.huawei.ascend.sit.transport.MessageProtocol;
import com.huawei.ascend.sit.transport.MessageTransport;
import com.huawei.ascend.sit.transport.OutboundMessage;
import com.huawei.ascend.sit.transport.RestGatewayTransport;
import com.huawei.ascend.sit.transport.RestExchange;
import com.huawei.ascend.sit.transport.RestQueryTransport;
import com.huawei.ascend.sit.transport.RestVersatileTransport;
import com.huawei.ascend.sit.transport.SessionLabels;
import com.huawei.ascend.sit.transport.WireLogger;
import com.huawei.ascend.sit.transport.WireRequestRenderer;
import com.huawei.ascend.sit.utils.JsonUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.a2aproject.sdk.spec.TaskState;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A {@link ConversationTransport} that drives the plan-agent <b>directly</b> — bypassing the
 * edpa-gateway — over {@code InteractionFlow}'s reused wire. Carries {@link MessageProtocol#A2A_STREAM}
 * (JSON-RPC {@code SendStreamingMessage} SSE) or the REST family:
 * {@link MessageProtocol#REST_QUERY}/{@link MessageProtocol#REST_QUERY_SYNC} on {@code POST /v1/query}
 * and {@link MessageProtocol#REST_REACTIVE}/{@link MessageProtocol#REST_REACTIVE_SYNC} on the byte-identical
 * {@code POST /v1/query/reactive}; the {@code stream} flag and endpoint path are derived per protocol.
 *
 * <p><b>What this is.</b> An <em>adapter</em>, not a transport. {@code Turn}'s driving loop is the
 * dynamic driver — each round's text is fetched from envexplorer at runtime (a per-step READ), so it
 * cannot be statically declared the way {@code InteractionFlow.send(text)} declares rounds. This
 * adapter only plugs {@link ConversationTransport#send(ConversationOutbound, ConversationEventCollector)}
 * onto {@code InteractionFlow}'s <em>reused</em> wire ({@link A2aStreamingTransport} /
 * {@link RestQueryTransport}) — it adds no new wire logic. The bridge per {@code send}:
 * <ol>
 *   <li>rebuild the EDPA envelope from the rendered body ({@code inputs}/{@code headers}) plus the
 *       caller identity — the gateway's inbound job, done here because the direct path bypasses the
 *       gateway — and project it per protocol (A2A: under {@code metadata} + {@code parts[0].text}=
 *       {@code {"query":...,"intent":...}}; REST: a pre-rendered body),</li>
 *   <li>resolve continuation — carry the prior {@code taskId} when the prior round did not reach a
 *       terminal state (A2A contract), and pin the conversation {@code cid} as {@code contextId} on
 *       every round so the plan-agent threads one conversation (REST continuation is by
 *       {@code conversation_id} = the same pinned cid),</li>
 *   <li>fire the reused transport and await the round's resolution ({@code INPUT_REQUIRED} for a
 *       manual step, a terminal for an auto/final step),</li>
 *   <li>translate each text-bearing {@link InboundEvent} into a {@link SseEvent} so the collector
 *       sees the same shape {@link RestVersatileTransport} produces, then mark the stream ended.</li>
 * </ol>
 *
 * <p><b>One instance = one conversation.</b> It remembers {@code prevTaskId}/{@code prevState}
 * across {@code send} calls exactly as {@code InteractionFlow.execute} does, so a multi-turn
 * {@code INPUT_REQUIRED} → resume sequence continues the same A2A task. Only the taskId is tracked
 * from server responses; {@code contextId} is always the pinned cid — the
 * {@code InteractionFlow.withContextId(cid)} model.
 *
 * <p><b>Wire-contract calibration points</b> (cannot be verified without a real plan-agent + LLM
 * run): (1) the rebuilt envelope matches what the plan-agent's {@code VersatileRequestExtractor}
 * consumes — verified against the extractor source, pending an end-to-end run;
 * (2) continuation is by taskId (A2A) / conversation_id (REST); (3) REST {@code user_id}/{@code space_id}
 * are demo placeholders ("demo-user"/"demo-space") — not carried in {@code ConversationIdentity}, may
 * need real values; (4) the plan-agent exposes {@code /v1/query} for {@code REST_QUERY} (else it 404s —
 * the same assumption the gateway REST mode makes), and the endpoint threads {@code workspace_id}/
 * {@code type} as URL params exactly as the gateway does.
 */
public final class ConversationInteractionAdapter implements ConversationTransport {

    private final MessageProtocol protocol;
    private final A2aServiceClient client;       // null when a transport is injected via using() or built from baseUrl
    private final String baseUrl;                // gateway base URL on the forBaseUrl path; null on the client path
    private MessageTransport transport;          // null until the first send (both the client + baseUrl paths)
    private final long timeoutMs;

    /**
     * Query-param keys dropped from the REST {@code /v1/query} URL and the A2A {@code metadata.query}
     * (both normally carry {@code type=controller} + {@code workspace_id=N}). Empty by default — all params
     * sent, matching the gateway. Add a key via {@link #disableQueryParam(String)} to drop it for a
     * downstream that does not expect it (e.g. {@code "type"} for the multi-workflow adapter, whose
     * workflow endpoints route by {@code intent}, not the controller type).
     */
    private final Set<String> disabledQueryParams = new LinkedHashSet<>();

    /** The two routing query params the gateway threads; the only valid args to {@link #disableQueryParam}. */
    private static final Set<String> KNOWN_QUERY_PARAMS = Set.of("type", "workspace_id");

    // Wire logging — the SAME shared, JVM-cached sink InteractionFlow uses
    // (WireLoggerResolver.resolved()), so this adapter's rounds land in the SAME per-run directory as
    // the flow's. NOOP unless sut.wire-log.enabled. Mirrors InteractionFlow.executeRound's post-await
    // logRound call; without it the Conversation/adapter path logged nothing (the gap that hid
    // PlanAgentDirectStreamingTest's wire). Null → resolved from config on first send(); test-injected
    // otherwise (see withWireLogger).
    private WireLogger wireLogger;
    private int round = 0;

    // Continuation state across sends — mirrors InteractionFlow.execute's lastTaskId/lastState.
    // contextId is pinned to the conversation cid (read per-send from out.conversationId()), not tracked here.
    private String prevTaskId = null;
    private TaskState prevState = null;

    /**
     * @param protocol  {@link MessageProtocol#A2A_STREAM} or one of the REST family
     *                  ({@link MessageProtocol#REST_QUERY}/{@code _SYNC} on {@code /v1/query},
     *                  {@link MessageProtocol#REST_REACTIVE}/{@code _SYNC} on {@code /v1/query/reactive})
     * @param client    the plan-agent A2A client — its {@code getBaseUrl()} anchors the REST endpoint
     *                  and its {@code sendMessage} drives the A2A wire
     * @param timeoutMs per-round resolution timeout (await {@code INPUT_REQUIRED} or a terminal)
     */
    public ConversationInteractionAdapter(MessageProtocol protocol, A2aServiceClient client, long timeoutMs) {
        this(protocol, client, null, null, timeoutMs);
    }

    /**
     * Build an adapter for a gateway base URL <em>without</em> an {@link A2aServiceClient} — the
     * {@code Conversation} default-transport path ({@link MessageProtocol#REST_VERSATILE}), which has only
     * the gateway URL (no plan-agent client to anchor on). The transport is built lazily on the first
     * {@link #send} from this base URL, exactly as the client path builds it from
     * {@code client.getBaseUrl()}. REST-only (A2A needs the client's {@code sendMessageStreaming}).
     */
    static ConversationInteractionAdapter forBaseUrl(MessageProtocol protocol, String baseUrl, long timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required for forBaseUrl, got " + baseUrl);
        }
        return new ConversationInteractionAdapter(protocol, null, baseUrl, null, timeoutMs);
    }

    /** Private ctor with a pre-built transport — backs the {@link #using} test seam. */
    private ConversationInteractionAdapter(MessageProtocol protocol, MessageTransport transport, long timeoutMs) {
        this(protocol, null, null, transport, timeoutMs);
    }

    /** Single private ctor — every factory route funnels through here. */
    private ConversationInteractionAdapter(MessageProtocol protocol, A2aServiceClient client,
                                           String baseUrl, MessageTransport transport, long timeoutMs) {
        this.protocol = protocol;
        this.client = client;
        this.baseUrl = baseUrl;
        this.transport = transport;
        this.timeoutMs = timeoutMs;
    }

    /** The REST base URL: {@code client.getBaseUrl()} on the client path, else the {@link #baseUrl} field. */
    private String base() {
        return client != null ? client.getBaseUrl() : baseUrl;
    }

    /**
     * Test-only: inject a transport directly, bypassing client-based {@code transportFor} (mirrors
     * {@code InteractionFlow.using(MessageTransport)}). Pair with {@link #withWireLogger} so the
     * post-await wire-log call is unit-testable with a stub transport and a recording logger.
     */
    static ConversationInteractionAdapter using(MessageProtocol protocol, MessageTransport transport, long timeoutMs) {
        return new ConversationInteractionAdapter(protocol, transport, timeoutMs);
    }

    /**
     * Test-only injection of the wire logger. Production resolves it from {@code sut.wire-log.*} via
     * {@link WireLoggerResolver} on the first {@link #send}. Mirrors {@code InteractionFlow.withWireLogger}.
     */
    ConversationInteractionAdapter withWireLogger(WireLogger logger) {
        this.wireLogger = logger;
        return this;
    }

    /**
     * Drop the named query param from the REST {@code /v1/query} URL and the A2A {@code metadata.query}
     * (both normally carry {@code type=controller} + {@code workspace_id=N}). Pass {@code "type"} to drop
     * ONLY the {@code controller} type — {@code workspace_id} stays — for a downstream that routes by
     * something other than the controller type (e.g. the multi-workflow adapter, whose workflow endpoints
     * route by {@code intent}). Default: no param disabled (matches the gateway). Repeatable (each call
     * adds a key). Unknown keys are rejected — only {@code "type"}/{@code "workspace_id"} are recognised.
     */
    public ConversationInteractionAdapter disableQueryParam(String key) {
        if (key == null || !KNOWN_QUERY_PARAMS.contains(key)) {
            throw new IllegalArgumentException(
                    "Unknown query param '" + key + "'; recognised: " + KNOWN_QUERY_PARAMS);
        }
        disabledQueryParams.add(key);
        return this;
    }

    private MessageTransport transportFor(ConversationOutbound out, Set<String> disabled) {
        int workspaceId = out.workspaceId();
        String base = base();
        return switch (protocol) {
            // A2A_STREAM binds the streaming SDK Client (sendMessageStreaming → SSE message/stream);
            // client::sendMessage is the legacy sync default and would make A2A_STREAM wire-identical
            // to sync.
            case A2A_STREAM -> new A2aStreamingTransport(new A2aStreamingWire(client::sendMessageStreaming));
            case REST_QUERY, REST_QUERY_SYNC, REST_REACTIVE, REST_REACTIVE_SYNC -> {
                URI ep = isReactive(protocol)
                        ? restReactiveEndpoint(base, workspaceId, disabled)
                        : restQueryEndpoint(base, workspaceId, disabled);
                yield new RestQueryTransport(new RestExchange(), ep, isStreaming(protocol));
            }
            // REST_GATEWAY (high-code adapter) + REST_VERSATILE (low-code gateway) share the SAME identity-bearing
            // conversation endpoint (/v1/{pid}/agents/{aid}/conversations/{cid}?type=controller&workspace_id=N)
            // and the SAME GatewayStreamingTransport wire loop (POST → leaf.map() → SseStateClassifier); they differ
            // only in the leaf class + SSE classifier — GatewayEventMapping (adapter, custom_rsp_data/think_chunk)
            // vs VersatileEventMapping (gateway, bare {event,data}). Both streaming-only.
            case REST_GATEWAY -> new RestGatewayTransport(new RestExchange(),
                    restConversationEndpoint(base, out.projectId(), out.agentId(),
                            out.conversationId(), workspaceId, disabled));
            case REST_VERSATILE -> new RestVersatileTransport(new RestExchange(),
                    restConversationEndpoint(base, out.projectId(), out.agentId(),
                            out.conversationId(), workspaceId, disabled));
            default -> throw new IllegalArgumentException(
                    "ConversationInteractionAdapter supports A2A_STREAM + REST family + REST_GATEWAY + REST_VERSATILE, got " + protocol);
        };
    }

    /**
     * The plan-agent REST endpoint, mirroring the gateway's {@code RestPlanAgentClient.restEndpoint}:
     * {@code <base>/v1/query?type=controller&workspace_id=<N>}. The runtime surfaces URL query params as
     * {@code ServeRequest.metadata.query}, which threads {@code workspace_id}/{@code type} onward to the
     * downstream — a bare {@code /v1/query} drops them. The A2A path carries the same pair in
     * {@code metadata.query}, so the bare REST URL was an A2A/REST asymmetry: the REST body was right
     * but it threaded no {@code workspace_id}. A trailing slash on the base is tolerated. All params are
     * sent by default; drop one via {@link #restQueryEndpoint(String, int, Set)}.
     */
    static URI restQueryEndpoint(String baseUrl, int workspaceId) {
        return restQueryEndpoint(baseUrl, workspaceId, Set.of());
    }

    /**
     * Same as {@link #restQueryEndpoint(String, int)} but lets the caller drop named query params — pass
     * e.g. {@code Set.of("type")} to keep only {@code workspace_id} (the workspace is always useful to the
     * downstream) for a downstream that routes by something other than the controller type (e.g. the
     * multi-workflow adapter's workflow endpoints, which route by {@code intent}).
     */
    static URI restQueryEndpoint(String baseUrl, int workspaceId, Set<String> disabled) {
        return restEndpoint(baseUrl, "v1/query", workspaceId, disabled);
    }

    /**
     * Shared builder for the REST-family endpoints — both the servlet {@code /v1/query} and the WebFlux
     * {@code /v1/query/reactive} controllers take the same {@code type}/{@code workspace_id} routing pair
     * as query params, with the same trailing-slash tolerance and the same disable semantics. Callers
     * pass the path segment ({@code "v1/query"} / {@code "v1/query/reactive"}); the leading slash is
     * added here.
     */
    static URI restEndpoint(String baseUrl, String pathSeg, int workspaceId, Set<String> disabled) {
        String path = baseUrl.endsWith("/") ? baseUrl + pathSeg : baseUrl + "/" + pathSeg;
        List<String> params = new ArrayList<>();
        if (!disabled.contains("type")) {
            params.add("type=controller");
        }
        if (!disabled.contains("workspace_id")) {
            params.add("workspace_id=" + workspaceId);
        }
        return URI.create(params.isEmpty() ? path : path + "?" + String.join("&", params));
    }

    /**
     * The plan-agent WebFlux reactive endpoint: {@code <base>/v1/query/reactive?type=controller&workspace_id=<N>}
     * — same shape as {@link #restQueryEndpoint(String, int, Set)} but on the reactive controller path.
     * Used by {@code REST_REACTIVE}/{@code REST_REACTIVE_SYNC}; same trailing-slash tolerance and disable
     * semantics as the servlet variant.
     */
    static URI restReactiveEndpoint(String baseUrl, int workspaceId, Set<String> disabled) {
        return restEndpoint(baseUrl, "v1/query/reactive", workspaceId, disabled);
    }

    /**
     * The identity-bearing conversation endpoint shared by {@code REST_GATEWAY} (high-code adapter) and
     * {@code REST_VERSATILE} (low-code gateway): {@code <base>/v1/{pid}/agents/{aid}/
     * conversations/{cid}?type=controller&workspace_id=<N>} — mirroring the gateway's conversation
     * endpoint shape. Distinct from the flat {@code /v1/query} REST family. Reuses
     * {@link #restEndpoint(String, String, int, Set)} (same trailing-slash tolerance + disable
     * semantics) by composing the conversation path as the path segment.
     */
    static URI restConversationEndpoint(String baseUrl, String projectId, String agentId, String conversationId,
                                        int workspaceId, Set<String> disabled) {
        String pathSeg = "v1/" + projectId + "/agents/" + agentId + "/conversations/" + conversationId;
        return restEndpoint(baseUrl, pathSeg, workspaceId, disabled);
    }

    /** True for the streaming REST-family protocols ({@code stream:true} → SSE). */
    static boolean isStreaming(MessageProtocol p) {
        return switch (p) {
            case REST_QUERY, REST_REACTIVE -> true;
            case REST_QUERY_SYNC, REST_REACTIVE_SYNC -> false;
            default -> throw new IllegalArgumentException("non-REST protocol passed to isStreaming: " + p);
        };
    }

    /** True for the reactive-path REST protocols (endpoint {@code /v1/query/reactive}). */
    static boolean isReactive(MessageProtocol p) {
        return p == MessageProtocol.REST_REACTIVE || p == MessageProtocol.REST_REACTIVE_SYNC;
    }

    @Override
    public void send(ConversationOutbound out, ConversationEventCollector collector) {
        try {
            // The transport is built on the first send, not in the ctor: the REST endpoint must carry
            // the conversation's workspace_id (mirroring the gateway's RestPlanAgentClient.restEndpoint),
            // and REST_GATEWAY additionally carries pid/aid/cid — all first available here, on out. They are
            // constant for one conversation (= one adapter instance), so a once-only lazy build is correct.
            if (transport == null) {
                transport = transportFor(out, disabledQueryParams);
            }
            boolean continuation = prevTaskId != null && !prevTaskId.isEmpty()
                    && prevState != null && !prevState.isFinal();
            String taskId = continuation ? prevTaskId : null;
            String contextId = out.conversationId();   // pin the cid on every round (one conversation)
            OutboundMessage message = buildOutbound(out, taskId, contextId);

            InboundExchange exchange = transport.send(message);

            // Await the round's resolution. awaitInputRequired returns promptly once INPUT_REQUIRED
            // (manual step) OR a terminal (auto/final) appears; on timeout it returns false with neither.
            boolean inputRequired = exchange.awaitInputRequired(timeoutMs);
            TaskState resolved;
            if (inputRequired) {
                resolved = TaskState.TASK_STATE_INPUT_REQUIRED;
            } else {
                resolved = lastFinalState(exchange);
                if (resolved == null) {
                    throw new AssertionError("Round did not resolve (no INPUT_REQUIRED or terminal) within "
                            + timeoutMs + "ms; trajectory=" + exchange.stateTrajectory());
                }
            }

            // Bridge each text-bearing InboundEvent → SseEvent (text under data.text, the shape
            // RestVersatileTransport produces). STATE/LLM_USAGE carry no text and have no gateway SSE
            // equivalent, so they are dropped.
            for (InboundEvent e : exchange.events()) {
                if (e.text() != null && !e.text().isEmpty()) {
                    collector.add(new SseEvent(e.kind().name().toLowerCase(Locale.ROOT),
                            Map.of("text", e.text())));
                }
            }

            // Remember A2A continuation: the server-assigned taskId (blank for REST, which has no taskId).
            String observedTaskId = exchange.taskId();
            if (observedTaskId != null && !observedTaskId.isEmpty()) {
                prevTaskId = observedTaskId;
            }
            prevState = resolved;

            // Wire log: record this round now that the exchange has settled (the await above is the
            // synchronization point) — mirrors InteractionFlow.executeRound. The same A2A async-delivery
            // race applies here (send returns before events arrive on the callback thread), so the call
            // must be post-await; logging inside transport.send would capture an empty response list.
            if (wireLogger == null) {
                wireLogger = WireLoggerResolver.resolved();
            }
            if (wireLogger.enabled()) {
                // The using() test seam has neither client nor baseUrl → no endpoint (preserves the
                // historical null). The client + forBaseUrl paths both resolve a real endpoint, so the
                // gateway (forBaseUrl) path now gets a wire-log too (Design 1 goal).
                String endpoint = (client == null && baseUrl == null)
                        ? null : endpointFor(out, disabledQueryParams);
                String wireRequest = WireRequestRenderer.render(protocol, message, endpoint);
                wireLogger.logRound(protocol.name(), ++round, sessionIdOf(message), message,
                        exchange.events(), wireRequest);
            }
        } finally {
            collector.markStreamEnd();
        }
    }

    /** Session id for the wire-log filename: JUnit invocation label → contextId → {@code "nosession"}. */
    private static String sessionIdOf(OutboundMessage m) {
        return SessionLabels.resolveLogName(m.contextId());
    }

    /**
     * Wire endpoint for the paste-ready request — the same target {@code transportFor} sends to: A2A →
     * the agent card URL (base URL fallback); REST → the workspace-scoped {@code /v1/query} URL. Resolved
     * only when a client is present (the {@code using()} unit-test seam has none → caller passes null).
     */
    private String endpointFor(ConversationOutbound out, Set<String> disabled) {
        int workspaceId = out.workspaceId();
        String base = base();
        return switch (protocol) {
            case A2A_STREAM -> {
                String url = client.getAgentCard() == null ? null : client.getAgentCard().url();
                yield (url == null || url.isBlank()) ? base : url;
            }
            case REST_QUERY, REST_QUERY_SYNC ->
                    restQueryEndpoint(base, workspaceId, disabled).toString();
            case REST_REACTIVE, REST_REACTIVE_SYNC ->
                    restReactiveEndpoint(base, workspaceId, disabled).toString();
            case REST_GATEWAY, REST_VERSATILE ->
                    restConversationEndpoint(base, out.projectId(), out.agentId(),
                            out.conversationId(), workspaceId, disabled).toString();
            default -> null;
        };
    }

    /** Last final state in the exchange's trajectory, or {@code null} if none arrived. */
    private static TaskState lastFinalState(InboundExchange exchange) {
        TaskState last = null;
        for (TaskState s : exchange.stateTrajectory()) {
            if (s != null && s.isFinal()) {
                last = s;
            }
        }
        return last;
    }

    /**
     * Rebuild the EDPA envelope (the gateway's inbound job — the direct path bypasses the gateway) and
     * project it per protocol: A2A gets {@code text}={@code {query,intent}} JSON + the envelope under
     * {@code metadata}; REST gets a pre-rendered {@code body}. Continuation hints (taskId/contextId)
     * are threaded through unchanged.
     */
    private OutboundMessage buildOutbound(ConversationOutbound out, String taskId, String contextId) {
        EdpaEnvelope env = EdpaEnvelope.parse(out.jsonBody());
        String textPart = queryIntentJson(env.query, env.intent);
        return switch (protocol) {
            case A2A_STREAM -> new OutboundMessage(textPart, a2aMetadata(out, env, disabledQueryParams), taskId, contextId, null);
            case REST_QUERY, REST_QUERY_SYNC, REST_REACTIVE, REST_REACTIVE_SYNC ->
                    new OutboundMessage(null, null, taskId, contextId,
                            restBody(out, env, textPart, isStreaming(protocol)));
            // REST_GATEWAY (high-code adapter): streaming-only, so stream is always true. Posts the full
            // EDPA envelope — the same restBody shape as the REST family, just addressed to the adapter's
            // cid-bearing /v1/{pid}/agents/{aid}/conversations/{cid} URL (built in transportFor).
            case REST_GATEWAY ->
                    new OutboundMessage(null, null, taskId, contextId,
                            restBody(out, env, textPart, true));
            // REST_VERSATILE (low-code gateway — the Conversation default): posts out.jsonBody() VERBATIM.
            // The Conversation path's body IS the full gateway EDPA body (ConversationRequest.toJson()),
            // so it is posted unchanged — the same contract the old conversation.RestVersatileTransport
            // had. No restBody rebuild / EdpaEnvelope.parse reshaping: the body is already gateway-shaped.
            case REST_VERSATILE ->
                    new OutboundMessage(null, null, taskId, contextId, out.jsonBody());
            default -> throw new IllegalArgumentException(
                    "ConversationInteractionAdapter supports A2A_STREAM + REST family + REST_GATEWAY + REST_VERSATILE, got " + protocol);
        };
    }

    /**
     * A2A projection: the EDPA envelope under {@code metadata} ({@code body}/{@code headers}/{@code query}).
     * {@code metadata.query} mirrors the REST URL: it carries {@code type=controller} + {@code workspace_id}
     * by default, minus any key in {@code disabled} — so a downstream that routes by something other than
     * the controller type still gets the workspace.
     */
    private static Map<String, Object> a2aMetadata(ConversationOutbound out, EdpaEnvelope env,
                                                   Set<String> disabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("custom_data", Map.of("inputs", env.inputs));
        body.put("agent_id", out.agentId());
        body.put("conversation_id", out.conversationId());
        // Always streaming: this adapter only carries A2A_STREAM (A2A_SYNC does not route here), so the
        // flag is unconditional — unlike restBody's parameterized stream flag for the REST family.
        body.put("stream", true);
        body.put("timeout", out.timeout());
        body.put("input", Map.of("query", env.query));
        body.put("role_name", out.roleName());
        body.put("role_id", out.roleId());

        Map<String, Object> query = new LinkedHashMap<>();
        if (!disabled.contains("type")) {
            query.put("type", "controller");
        }
        if (!disabled.contains("workspace_id")) {
            query.put("workspace_id", String.valueOf(out.workspaceId()));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("body", body);
        meta.put("headers", env.headers);
        meta.put("query", query);
        return meta;
    }

    /** REST projection: the flat EDPA REST body, pre-rendered to a JSON string for verbatim POST. */
    private static String restBody(ConversationOutbound out, EdpaEnvelope env, String textPart, boolean stream) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", env.query);
        input.put("intent", env.intent);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", out.conversationId());
        body.put("stream", stream);
        body.put("user_id", REST_USER_ID);     // demo placeholder (not in ConversationIdentity)
        body.put("space_id", REST_SPACE_ID);   // demo placeholder (not in ConversationIdentity)
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", textPart);
        body.put("messages", List.of(userMessage));
        body.put("agent_id", out.agentId());
        body.put("input", input);
        body.put("timeout", out.timeout());
        body.put("role_id", out.roleId());
        body.put("role_name", out.roleName());
        body.put("custom_data", Map.of("inputs", env.inputs));
        return JsonUtils.toJsonCompact(body);
    }

    /** The {@code parts[0].text} / {@code messages[0].content} value: {@code {"query":...,"intent":...}}. */
    private static String queryIntentJson(String query, String intent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", query);
        m.put("intent", intent);
        return JsonUtils.toJsonCompact(m);
    }

    /** REST demo placeholders — {@code user_id}/{@code space_id} are not carried in {@code ConversationIdentity}. */
    private static final String REST_USER_ID = "demo-user";
    private static final String REST_SPACE_ID = "demo-space";

    /** Parsed EDPA source body: the {@code inputs}/{@code headers} maps plus this round's query/intent. */
    private static final class EdpaEnvelope {
        final Map<String, Object> inputs;
        final Map<String, Object> headers;
        final String query;
        final String intent;

        private EdpaEnvelope(Map<String, Object> inputs, Map<String, Object> headers, String query, String intent) {
            this.inputs = inputs;
            this.headers = headers;
            this.query = query;
            this.intent = intent;
        }

        static EdpaEnvelope parse(String jsonBody) {
            try {
                JsonNode root = JsonUtils.mapper().readTree(jsonBody);
                Map<String, Object> inputs = toMap(root.path("inputs"));
                Map<String, Object> headers = toMap(root.path("headers"));
                String query = textOf(root.path("inputs").path("query"));
                String intent = textOf(root.path("inputs").path("intent"));
                return new EdpaEnvelope(inputs, headers, query, intent);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to parse EDPA body: " + jsonBody, e);
            }
        }

        private static Map<String, Object> toMap(JsonNode node) {
            if (node == null || node.isMissingNode() || !node.isObject()) {
                return new LinkedHashMap<>();
            }
            return JsonUtils.mapper().convertValue(node, new TypeReference<Map<String, Object>>() {});
        }

        private static String textOf(JsonNode node) {
            if (node == null || node.isNull() || node.isMissingNode()) {
                return "";
            }
            return node.isTextual() ? node.asText() : node.toString();
        }
    }
}
