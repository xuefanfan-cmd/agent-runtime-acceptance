package com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.MavenArtifact;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-111 轨迹反思与成本追踪测试支撑（20261106-feat-109-110-111 批次）。
 *
 * <p><b>观察面（实现前已读 SUT 源码固化，L2-04/L2-05 悬置在实现层有答案）：</b></p>
 * <ul>
 *   <li>反思处置记录＝logger 名 {@code da_reflection_result} 的 INFO 行
 *       （{@code DaReflectionScheduler.ResultSink.logging()}：taskId/sessionId/outcome/
 *       memoryId/reason/durationMs 六槽位），落 {@code target/sit-logs/<agent>/stdout.log}。</li>
 *   <li>成本指标＝Micrometer 六指标经 OtlpMeterRegistry 推送（openjiuwen.service.otel.enabled=true；
 *       端点 env OTEL_EXPORTER_OTLP_METRICS_ENDPOINT、推送间隔 env OTEL_METRIC_EXPORT_INTERVAL
 *       ——micrometer-registry-otlp 1.16.6 OtlpConfig 字节码核实）；writer 冲刷间隔
 *       deepanalyze.cost.metric-export-interval（CostMetricsConfig.exportInterval）。
 *       OTLP/HTTP 缺省 http/protobuf（jar 内无 JSON 编码面）→ 接收端按 wire format 解析。</li>
 *   <li>agent_memory 写面＝env DA_TOOL_PLATFORM_URL（tool-platform server 的 base_url 缺省空），
 *       POST {base}/v1/tools/agent_memory，成功＝2xx＋success=true＋非空 id
 *       （{@code DaReflectionClients.HttpMemoryWriter}）。</li>
 *   <li>模型连接面＝env DA_MODEL_PROVIDER/DA_MODEL_NAME/DA_MODEL_BASE_URL＋LLM_API_KEY
 *       （engine application.yml model.model/backend 块）；确定性执行体＝测试侧装配方
 *       （{@link ModelStubServer}，用户 2026-11-09 批准双轨，不属 Mock LLM 红线）。</li>
 * </ul>
 *
 * <p>agent 复用本批次 yml 条目 {@code deepanalyze-guardrail}（reflection 属性全部由用例
 * property 注入、yml 不含任何 reflection/cost 键——默认开关全关纪律）。</p>
 */
public final class DaReflectionCostSupport {

    public static final String SUT_GROUP = "com.openjiuwen";
    public static final String SUT_ARTIFACT = "deepanalyze-engine";
    /** r2（@76bcad137）：#44/45/46 裁决修复版——settings 读面/自进化门已移除，恢复 L1 双门。 */
    public static final String SUT_VERSION = "0.1.3-feat109111-r2";

    /** 复用批次共享 agent 条目（application-openjiuwen.yml；ENV-3.11 不新增不改旧）。 */
    public static final String AGENT = "deepanalyze-guardrail";

    /** 六指标名（{@code LlmCostMetricNames} 契约面，测试按名字取数）。 */
    public static final String M_TOKEN_USAGE = "openjiuwen.llm.token.usage";
    public static final String M_COST_USD = "openjiuwen.llm.cost.usd";
    public static final String M_UNKNOWN_PRICE = "openjiuwen.llm.cost.unknown_price";
    public static final String M_USAGE_MISSING = "openjiuwen.llm.usage.missing";
    public static final String M_USAGE_INVALID = "openjiuwen.llm.usage.invalid";
    public static final String M_USAGE_OVERFLOW = "openjiuwen.llm.usage.overflow";

    public static final String TAG_SESSION = "session.id";
    public static final String TAG_TASK = "openjiuwen.task.id";
    public static final String TAG_PURPOSE = "openjiuwen.llm.call.purpose";
    public static final String TAG_TOKEN_TYPE = "gen_ai.token.type";
    public static final String TAG_MODEL = "gen_ai.response.model";
    public static final String TAG_PROVIDER = "gen_ai.provider.name";
    public static final String TAG_MODEL_SOURCE = "openjiuwen.llm.model_identity_source";
    public static final String TAG_PRICE_PROFILE = "openjiuwen.cost.price_profile";

    /**
     * 确定性执行体默认模型身份（价目线按此配 price line）。provider 必须是 core
     * 注册表内的合法名（DaEngineConfiguration 启动 fail-fast：unavailable provider
     * 报错即注册表清单）——stub 说 OpenAI 兼容协议，故用 OpenAI；model 名不校验、
     * 保持 stub-model 以便与真实网关流量区分。
     */
    public static final String STUB_PROVIDER = "OpenAI";
    public static final String STUB_MODEL = "stub-model";

    /**
     * 九码固定文本（L1 §3.1 / SUT {@code DaLessonCodes} 逐字——入库内容必须 ∈ 本集合，
     * 测试不断言具体命中哪个码）。
     */
    public static final java.util.Set<String> NINE_FIXED_TEXTS = java.util.Set.of(
            "遇到限流时应先降低请求频率并采用退避，避免连续重试放大故障。",
            "共享凭据或配额异常应先修复部署配置，不应让任务反复消耗请求。",
            "模型端点临时故障应使用有界退避并区分可恢复与永久失败。",
            "调用工具前校验参数和输入约束，避免以无效输入重复执行。",
            "工具基础设施失败应先修复连接和运行环境，不把它误判为业务无结果。",
            "轮次耗尽前应优先缩小目标或调整策略，避免无进展重复操作。",
            "上下文不足时应先压缩并保留关键证据，避免继续堆叠原始历史。",
            "编排失败应先核对依赖、成员状态和结果汇聚，避免盲目重放整个工作流。",
            "未分类失败应保留结构化错误事实并先补充诊断，避免过早归因。");

    private DaReflectionCostSupport() {
    }

    /* ==================== 栈装配 ==================== */

    /**
     * F111 基础栈：非流式（/run 同步面；模型调用走非流式路径，usage 从响应 JSON
     * 确定性取得）＋ redis 绑定。reflection/cost/OTLP/记忆写面由 customizer 按需注入。
     */
    public static SutStack.Builder baseStack(TestConfig config,
            java.util.function.Consumer<SutStack.AgentBuilder> customizer) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                    customizer.accept(agent);
                });
    }

    /** 模型环境透传（与 DaCompatGuardrailSupport 同口径；key 走 property 最高优先级）。 */
    public static void bindModelEnvironment(SutStack.AgentBuilder agent) {
        passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
        passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
        passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
        String apiKey = System.getenv("LLM_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            agent.property("LLM_API_KEY", apiKey);
        }
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    /**
     * 确定性执行体接线：模型三要素指向 stub（env 后写覆盖透传值）。
     */
    public static SutStack.AgentBuilder bindStubModel(SutStack.AgentBuilder agent, ModelStubServer stub) {
        agent.env("DA_MODEL_BASE_URL", stub.baseUrl());
        agent.env("DA_MODEL_NAME", STUB_MODEL);
        agent.env("DA_MODEL_PROVIDER", STUB_PROVIDER);
        return agent;
    }

    /**
     * 观测面接线：OTLP 接收端（秒级推送）＋ agent_memory 受控写面。
     */
    public static SutStack.AgentBuilder wireObservability(SutStack.AgentBuilder agent, OtlpReceiverServer otlp,
            MemoryReceiverServer memory) {
        agent.property("openjiuwen.service.otel.enabled", "true");
        agent.property("deepanalyze.cost.metric-export-interval", "PT1S");
        agent.env("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", otlp.baseUrl() + "/v1/metrics");
        agent.env("OTEL_METRIC_EXPORT_INTERVAL", "1000");
        agent.env("DA_TOOL_PLATFORM_URL", memory.baseUrl());
        return agent;
    }

    /** 一条价目（USD/百万 token；cached 传 null＝无缓存价）。 */
    public static SutStack.AgentBuilder priceLine(SutStack.AgentBuilder agent, int index, String provider,
            String model, String inputPerMillion, String cachedPerMillion, String outputPerMillion) {
        String p = "deepanalyze.cost.prices[" + index + "].";
        agent.property(p + "provider", provider);
        agent.property(p + "model", model);
        agent.property(p + "input-per-million", inputPerMillion);
        if (cachedPerMillion != null) {
            agent.property(p + "cached-input-per-million", cachedPerMillion);
        }
        agent.property(p + "output-per-million", outputPerMillion);
        return agent;
    }

    /**
     * 双门组合（enabled＋single-tenant-declared——#44 裁决后 L1 双门：settings 读面与
     * 自进化 YAML 键已移除，selfEvolution 参数保留占位恒不注入）。reason 分派值仅
     * reflection_disabled 与 tenant_gate 两种（self_evolution_disabled 面已不存在）。
     */
    public static SutStack.AgentBuilder gates(SutStack.AgentBuilder agent, boolean enabled,
            boolean selfEvolution, boolean declared) {
        agent.property("deepanalyze.reflection.enabled", String.valueOf(enabled));
        agent.property("deepanalyze.reflection.single-tenant-declared", String.valueOf(declared));
        return agent;
    }

    /** 三门全开便捷形态。 */
    public static SutStack.AgentBuilder gatesOpen(SutStack.AgentBuilder agent) {
        return gates(agent, true, true, true);
    }

    /** SUT stdout 日志文件（ProcessLauncher.resolveLogDir 同口径：basedir/target/sit-logs）。 */
    public static Path sutLogFile() {
        String basedir = System.getProperty("basedir", System.getProperty("user.dir"));
        return Path.of(basedir, "target", "sit-logs", AGENT, "stdout.log");
    }

    /* ==================== da_reflection_result 记录解析 ==================== */

    /** 一条 da_reflection_result 处置记录（六槽位，缺席槽位为 null）。 */
    public record ReflectionRecord(String taskId, String sessionId, String outcome, String memoryId,
            String reason, long durationMs) {
    }

    private static final Pattern RECORD_PATTERN = Pattern.compile(
            "da_reflection_result taskId=(\\S+) sessionId=(\\S+) outcome=(\\S+) memoryId=(\\S+) reason=(\\S+)"
                    + " durationMs=(\\d+)");

    /** 解析 SUT 日志中的全部 da_reflection_result 行（按读取时刻快照）。 */
    public static List<ReflectionRecord> reflectionRecords() {
        Path log = sutLogFile();
        if (!Files.exists(log)) {
            return List.of();
        }
        List<ReflectionRecord> records = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                Matcher m = RECORD_PATTERN.matcher(line);
                if (m.find()) {
                    records.add(new ReflectionRecord(m.group(1), m.group(2), m.group(3),
                            "null".equals(m.group(4)) ? null : m.group(4),
                            "null".equals(m.group(5)) ? null : m.group(5),
                            Long.parseLong(m.group(6))));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return records;
    }

    public static List<ReflectionRecord> recordsForSession(String sessionId) {
        return reflectionRecords().stream().filter(r -> sessionId.equals(r.sessionId())).toList();
    }

    /** SUT 日志含给定文本片段（启动失败键名 / re-admission WARN 等检索用）。 */
    public static boolean sutLogContains(String fragment) {
        Path log = sutLogFile();
        if (!Files.exists(log)) {
            return false;
        }
        try {
            return Files.readString(log, StandardCharsets.UTF_8).contains(fragment);
        } catch (IOException e) {
            return false;
        }
    }

    /** 有界轮询至判真（超时返回 false，不抛）。 */
    public static boolean await(Duration timeout, Duration interval, java.util.function.BooleanSupplier probe) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (probe.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return probe.getAsBoolean();
    }

    /**
     * 确定性业务失败构造（stub 回 business_failure 工具调用）。actrule 默认 {@code *: ask}
     * 且场景合并取严（mergeStricter，run-29 实证 awaiting_answer）——业务结论的显式授权
     * 是真实权限面：本辅助走 SSE 捕获 ask_user(permission_confirm) → /answer 允许 →
     * 任务落 failed 终态，返回 taskId。
     */
    public static String runBusinessFailureViaConfirm(HttpClient http, String base, String session)
            throws Exception {
        return runBusinessFailureViaConfirm(http, base, session, "宣告业务失败");
    }

    public static String runBusinessFailureViaConfirm(HttpClient http, String base, String session,
            String input) throws Exception {
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\"}";
        HttpResponse<java.io.InputStream> stream =
                com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport
                        .openRunStream(http, base, body, Duration.ofSeconds(240));
        List<String> frames = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try {
                com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport
                        .readFramesInto(stream, f -> frames.add(f.data()), f -> false, Duration.ofSeconds(230));
            } catch (Exception ignored) {
                // EOF/超时由回答或读取上限产生，断言不依赖读取结果
            }
        }, "f111-bf-reader-" + session);
        reader.setDaemon(true);
        reader.start();
        String taskId = null;
        java.util.Set<String> answered = new java.util.HashSet<>();
        // 有界多答循环：确认入口在执行前后各一次（run-30 实证），每个新 toolCallId 各答一次，
        // 直到流 EOF（settle）或上限
        for (int round = 0; round < 3; round++) {
            String pending = null;
            boolean got = await(Duration.ofSeconds(60), Duration.ofMillis(200), () -> {
                synchronized (frames) {
                    for (String d : frames) {
                        if (d == null || !d.contains("\"kind\":\"permission_confirm\"")) {
                            continue;
                        }
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("\"toolCallId\":\"([^\"]+)\"").matcher(d);
                        if (m.find() && !answered.contains(m.group(1))) {
                            return true;
                        }
                    }
                }
                return false;
            });
            if (!got) {
                break;
            }
            synchronized (frames) {
                for (String d : frames) {
                    if (d == null || !d.contains("\"kind\":\"permission_confirm\"")) {
                        continue;
                    }
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\"toolCallId\":\"([^\"]+)\"").matcher(d);
                    if (m.find() && !answered.contains(m.group(1))) {
                        pending = m.group(1);
                        java.util.regex.Matcher m2 = java.util.regex.Pattern
                                .compile("\"taskId\":\"([^\"]+)\"").matcher(d);
                        if (m2.find() && taskId == null) {
                            taskId = m2.group(1);
                        }
                        break;
                    }
                }
            }
            assertThat(pending).as("待确认 toolCallId").isNotBlank();
            HttpResponse<String> answer =
                    com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport
                            .post(http, base, "/api/agents/answer/" + taskId,
                                    "{\"answer\":\"允许\",\"toolCallId\":\"" + pending + "\"}",
                                    Duration.ofSeconds(30), Map.of("X-Da-Session-Id", session));
            assertThat(answer.statusCode()).as("/answer 受理: " + answer.body()).isEqualTo(200);
            answered.add(pending);
        }
        reader.join(150_000);
        assertThat(taskId).as("业务失败任务的 taskId").isNotBlank();
        return taskId;
    }

    /** 会话任务快照里的首个 taskId（/tasks/{sessionId}）。 */
    public static String firstTaskId(List<Map<String, Object>> tasks) {
        for (Map<String, Object> task : tasks) {
            Object id = task.get("id");
            if (id instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /* ==================== 确定性执行体（模型 stub） ==================== */

    /** 一次模型请求的记录。 */
    public record StubRequest(String path, String body, long receivedAtMillis) {
        public boolean isReflection() {
            return body != null && body.contains("lessonCodes");
        }
    }

    /** usage 注入形态（确定性计价构造）。 */
    public enum UsageMode {
        /** 精确 1000/200/500（缓存价公式主构造）。 */
        EXACT,
        /** usage 对象在场但缺必需 token 字段（missing 判定构造）。 */
        MISSING_FIELDS,
        /** 负 input（invalid 判定构造）。 */
        NEGATIVE_INPUT,
        /** cached &gt; input（invalid 判定构造）。 */
        CACHED_OVER_INPUT,
        /** 字段在场的真实 0（不误报 missing、计价真实 0）。 */
        ALL_ZERO
    }

    /**
     * 进程内确定性执行体（OpenAI 兼容 /chat/completions；测试侧装配方——只控制外部
     * 依赖与记录证据，不实现被测产品规则）。按请求体分流：含 lessonCodes 标记＝反思
     * 调用，其余＝主调用。支持流式与非流式两种请求形态。
     */
    public static final class ModelStubServer implements AutoCloseable {

        private final HttpServer server;

        private final List<StubRequest> requests = new CopyOnWriteArrayList<>();

        private final Object lock = new Object();

        // 主调用行为
        private boolean mainToolCallBusinessFailure;

        private String mainAnswer = "stub 主回答：任务已完成。";

        private int mainErrorStatus;

        private String mainErrorBody = "";

        private long mainDelayMillis;

        // 反思调用行为
        private String reflectionCode = "TOOL_INPUT_INVALID";

        private boolean reflectionFreeText;

        private long reflectionDelayMillis;

        // usage 形态（主/反思同用）
        private UsageMode usageMode = UsageMode.EXACT;

        // business_failure 工具 output 参数文本（金丝雀注入点）
        private String toolOutputText = "业务结论：目标记录不存在（stub 构造）";

        // 脚本模式：非探针/非反思请求按序消费（"tool:<名>"=工具调用 / "error:<码>"=HTTP 错误 /
        // "answer"=普通回答）；耗尽后回落普通回答。GUARDRAIL_DENIED 构造用。
        private java.util.ArrayDeque<String[]> script;

        /** 按请求序编程非探针响应：条目 "tool:<名>" / "error:<码>" / "answer"。 */
        public void script(String... entries) {
            this.script = new java.util.ArrayDeque<>();
            for (String entry : entries) {
                this.script.add(entry.split(":", 2));
            }
        }

        private final AtomicInteger seq = new AtomicInteger();

        public ModelStubServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", exchange -> handle(exchange));
            this.server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            requests.add(new StubRequest(exchange.getRequestURI().getPath(), body, System.currentTimeMillis()));
            try {
                sleep(mainDelayMillis);
                boolean reflection = body.contains("lessonCodes");
                if (reflection) {
                    sleep(reflectionDelayMillis);
                }
                if (!reflection && mainErrorStatus > 0) {
                    respond(exchange, mainErrorStatus, mainErrorBody);
                    return;
                }
                if (!reflection && script != null && !script.isEmpty()) {
                    String[] step = script.poll();
                    if (step[0].equals("error")) {
                        respond(exchange, Integer.parseInt(step[1]),
                                "{\"error\":{\"message\":\"stub scripted failure\"}}");
                        return;
                    }
                    if (step[0].equals("tool")) {
                        respondCompletion(exchange, null, true, usageJson(), step[1]);
                        return;
                    }
                    // "answer" 落回普通回答
                }
                String usage = usageJson();
                if (reflection) {
                    String content = reflectionFreeText
                            ? "反思输出超限自由文本" + "x".repeat(64)
                            : reflectionCode;
                    respondCompletion(exchange, content, false, usage);
                    return;
                }
                // 成员执行体强制探针（ToolCallingEnforcingModel，__probe_tool__）——必须以
                // tool_calls 应答，否则 ToolCallingBypassException 整轮失败
                boolean probeRequest = body.contains("__probe_tool__");
                boolean toolCall;
                String content;
                synchronized (lock) {
                    toolCall = probeRequest || mainToolCallBusinessFailure;
                }
                if (toolCall) {
                    respondCompletion(exchange, null, true, usage);
                } else {
                    synchronized (lock) {
                        content = mainAnswer;
                    }
                    respondCompletion(exchange, content, false, usage);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, "{\"error\":{\"message\":\"stub interrupted\"}}");
            }
        }

        /** 流式请求回 SSE（OpenAI delta 形态——core 解析器读 delta.content；末块空 choices 带 usage）。 */
        private void respondCompletion(HttpExchange exchange, String content, boolean toolCall, String usage)
                throws IOException {
            respondCompletion(exchange, content, toolCall, usage, "business_failure");
        }

        private void respondCompletion(HttpExchange exchange, String content, boolean toolCall, String usage,
                String toolName) throws IOException {
            String escaped = content == null ? null
                    : content.replace("\\", "\\\\").replace("\"", "\\\"");
            String firstDelta;
            String args;
            String finish;
            boolean probe = lastRequest() != null && lastRequest().body().contains("__probe_tool__");
            if (toolCall && probe) {
                // 成员执行体强制探针（ToolCallingEnforcingModel）——以 __probe_tool__ 调用应答
                args = "{}";
                firstDelta = "{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_stub_probe_" + seq.incrementAndGet() + "\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"__probe_tool__\",\"arguments\":\"" + args + "\"}}]}";
                finish = "tool_calls";
            } else if (toolCall) {
                args = "{\\\"output\\\":\\\"" + toolOutputText.replace("\\", "\\\\").replace("\"", "\\\"") + "\\\"}";
                firstDelta = "{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_stub_" + seq.incrementAndGet() + "\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"" + toolName + "\",\"arguments\":\"" + args + "\"}}]}";
                finish = "tool_calls";
            } else {
                args = null;
                firstDelta = "{\"role\":\"assistant\",\"content\":"
                        + (escaped == null ? "null" : "\"" + escaped + "\"") + "}";
                finish = "stop";
            }
            boolean stream = bodyWantsStream(lastRequest());
            if (stream) {
                String head = "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[";
                StringBuilder sse = new StringBuilder();
                sse.append("data: ").append(head)
                        .append("{\"index\":0,\"delta\":").append(firstDelta).append(",\"finish_reason\":null}")
                        .append("]}\n\n");
                sse.append("data: ").append(head)
                        .append("{\"index\":0,\"delta\":{},\"finish_reason\":\"").append(finish).append("\"}")
                        .append("]}\n\n");
                sse.append("data: {\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[],")
                        .append("\"usage\":").append(usage).append("}\n\n");
                sse.append("data: [DONE]\n\n");
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                byte[] bytes = sse.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            String choice;
            if (toolCall && probe) {
                choice = "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
                        + "\"tool_calls\":[{\"id\":\"call_stub_probe_" + seq.incrementAndGet() + "\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"__probe_tool__\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}";
            } else if (toolCall) {
                choice = "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
                        + "\"tool_calls\":[{\"id\":\"call_stub_" + seq.incrementAndGet() + "\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"" + toolName + "\",\"arguments\":\"" + args + "\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}";
            } else {
                choice = "{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                        + (escaped == null ? "null" : "\"" + escaped + "\"") + "},"
                        + "\"finish_reason\":\"stop\"}";
            }
            respond(exchange, 200, "{\"id\":\"stub-" + seq.incrementAndGet() + "\",\"object\":\"chat.completion\","
                    + "\"created\":" + (System.currentTimeMillis() / 1000) + ",\"model\":\"" + STUB_MODEL + "\","
                    + "\"choices\":[" + choice + "],\"usage\":" + usage + "}");
        }

        private static void sleep(long millis) throws InterruptedException {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        }

        private static boolean bodyWantsStream(StubRequest request) {
            return request != null && request.body() != null && request.body().contains("\"stream\":true");
        }

        private StubRequest lastRequest() {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }

        private String usageJson() {
            return switch (usageMode) {
                case EXACT -> "{\"prompt_tokens\":1000,\"completion_tokens\":500,\"total_tokens\":1500,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":200}}";
                case MISSING_FIELDS -> "{\"total_tokens\":1500}";
                case NEGATIVE_INPUT -> "{\"prompt_tokens\":-5,\"completion_tokens\":100,\"total_tokens\":95}";
                case CACHED_OVER_INPUT -> "{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150,"
                        + "\"prompt_tokens_details\":{\"cached_tokens\":200}}";
                case ALL_ZERO -> "{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}";
            };
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        /* ---- 行为编程 ---- */

        /** 主调用回 business_failure 工具调用（确定性 BUSINESS_FAILURE 构造）。 */
        public void mainBusinessFailureToolCall() {
            synchronized (lock) {
                this.mainToolCallBusinessFailure = true;
            }
        }

        /** 主调用回指定错误（HTTP 语义即 core 分类输入：401/402/429/400+context 关键字）。 */
        public void mainError(int status, String body) {
            synchronized (lock) {
                this.mainErrorStatus = status;
                this.mainErrorBody = body;
            }
        }

        public void mainDelay(long millis) {
            this.mainDelayMillis = millis;
        }

        /** 注入 business_failure 工具 output 参数文本（金丝雀差分基线用）。 */
        public void toolOutputText(String text) {
            this.toolOutputText = text;
        }

        public void reflectionCode(String code) {
            this.reflectionCode = code;
        }

        /** 反思调用回超限自由文本（lesson_uncontrolled 构造）。 */
        public void reflectionFreeText() {
            this.reflectionFreeText = true;
        }

        /** 反思调用延迟毫秒（timeout 构造）。 */
        public void reflectionDelay(long millis) {
            this.reflectionDelayMillis = millis;
        }

        public void usageMode(UsageMode mode) {
            this.usageMode = mode;
        }

        public String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        public List<StubRequest> requests() {
            return List.copyOf(requests);
        }

        public long mainCalls() {
            return requests.stream().filter(r -> !r.isReflection()).count();
        }

        public long reflectionCalls() {
            return requests.stream().filter(StubRequest::isReflection).count();
        }

        /** 最近一次反思请求体（金丝雀/结构化字段断言用）。 */
        public String lastReflectionBody() {
            StubRequest last = null;
            for (StubRequest r : requests) {
                if (r.isReflection()) {
                    last = r;
                }
            }
            return last == null ? null : last.body();
        }

        public void reset() {
            requests.clear();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * 确定性失败构造（run-30/31 转向）：stub 主调用回 HTTP 401＋auth 签名（core 分类
     * AUTH_FAILED→181011→CREDENTIAL_OR_QUOTA，永久不可重试）——不经工具与权限面，
     * 任务直接落 failed 终态。业务失败（business_failure 工具）构造因 per-Task 装配下
     * 执行内二次确认不可恢复（run-30/31 日志级实证，ISSUE 候选）转 blocked。
     */
    public static void credentialFailure(ModelStubServer stub) {
        stub.mainError(401, "{\"error\":{\"message\":\"model call authentication failed permanently, "
                + "reason: stub 构造\",\"type\":\"invalid_request_error\"}}");
    }


    /* ==================== agent_memory 受控写面 ==================== */

    /** 一次 agent_memory 写请求的记录（path 用于过滤挂载期连通性探测——run-36 实证）。 */
    public record MemoryWrite(String path, String sessionId, String tenantId, String body,
            long receivedAtMillis) {
    }

    /**
     * 进程内受控 agent_memory 接收端（外部工具平台的测试替身——只记录与应答，
     * id 按内容哈希派生：同内容同 id，承接出口 7「同码复用 memoryId」的宿主侧记录断言）。
     */
    public static final class MemoryReceiverServer implements AutoCloseable {

        private final HttpServer server;

        private final List<MemoryWrite> writes = new CopyOnWriteArrayList<>();

        private volatile int errorStatus;

        private volatile long delayMillis;

        public MemoryReceiverServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", exchange -> handle(exchange));
            this.server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            writes.add(new MemoryWrite(exchange.getRequestURI().getPath(),
                    header(exchange, "X-Session-Id"), header(exchange, "X-Tenant-Id"),
                    body, System.currentTimeMillis()));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (errorStatus > 0) {
                respond(exchange, errorStatus, "{\"success\":false}");
                return;
            }
            String content = jsonField(body, "content");
            respond(exchange, 200, "{\"success\":true,\"id\":\"mem-" + shortHash(content) + "\"}");
        }

        private static String header(HttpExchange exchange, String name) {
            List<String> values = exchange.getRequestHeaders().get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        /** 顶层字符串字段提取（body 为 Jackson 序列化的扁平对象）。 */
        private static String jsonField(String body, String field) {
            if (body == null) {
                return "";
            }
            Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(body);
            return m.find() ? m.group(1) : "";
        }

        private static String shortHash(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(
                        (value == null ? "" : value).getBytes(StandardCharsets.UTF_8))).substring(0, 8);
            } catch (Exception e) {
                return String.valueOf(value == null ? 0 : value.hashCode());
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        public String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        public List<MemoryWrite> writes() {
            return List.copyOf(writes);
        }

        /** 真实 agent_memory 写入数（排除挂载期连通性探测等非写请求）。 */
        public int writeCount() {
            return memoryWrites().size();
        }

        /** path 为 /v1/tools/agent_memory 的写请求（真正的记忆写入面）。 */
        public List<MemoryWrite> memoryWrites() {
            return writes.stream()
                    .filter(w -> "/v1/tools/agent_memory".equals(w.path()))
                    .toList();
        }

        public void failWith(int status) {
            this.errorStatus = status;
        }

        public void delay(long millis) {
            this.delayMillis = millis;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /* ==================== 受控 OTLP 接收端（protobuf wire 解析） ==================== */

    /** 一个指标序列（一次推送中的累计值）。 */
    public record OtlpSeries(String name, Map<String, String> tags, double value) {
    }

    /** 一次 OTLP 推送快照。 */
    public record OtlpPush(long receivedAtMillis, List<OtlpSeries> series) {
    }

    /**
     * 进程内受控 OTLP/HTTP 接收端（http/protobuf；wire format 最小解析——
     * ExportMetricsServiceRequest → ResourceMetrics[1] → ScopeMetrics[2] → Metric{name=1,sum=7}
     * → Sum{data_points=1} → NumberDataPoint{attributes=7, as_double=4, as_int=6}；
     * KeyValue{key=1,value=2} → AnyValue{string_value=1}）。
     */
    public static final class OtlpReceiverServer implements AutoCloseable {

        private final HttpServer server;

        private final List<OtlpPush> pushes = new CopyOnWriteArrayList<>();

        public OtlpReceiverServer() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", exchange -> handle(exchange));
            this.server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] bytes;
            try (InputStream in = exchange.getRequestBody()) {
                bytes = in.readAllBytes();
            }
            if (bytes.length > 0) {
                try {
                    pushes.add(new OtlpPush(System.currentTimeMillis(), OtlpWire.parse(bytes)));
                } catch (Exception e) {
                    // 解析失败保留空推送留痕，不让接收端异常拖垮 SUT 推送
                    pushes.add(new OtlpPush(System.currentTimeMillis(), List.of()));
                }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }

        public String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        public List<OtlpPush> pushes() {
            return List.copyOf(pushes);
        }

        public int pushCount() {
            return pushes.size();
        }

        /**
         * 全部推送中，名字与标签都匹配的序列累计值的最大总和（CUMULATIVE 语义下
         * 单调不降；序列退役后不再出现——取最大值即其退役前终值）。
         */
        public double maxSum(String metricName, Predicate<Map<String, String>> tagMatch) {
            double max = 0;
            for (OtlpPush push : pushes) {
                double sum = 0;
                for (OtlpSeries s : push.series()) {
                    if (s.name().equals(metricName) && tagMatch.test(s.tags())) {
                        sum += s.value();
                    }
                }
                max = Math.max(max, sum);
            }
            return max;
        }

        /** 全部推送中匹配序列的最新值（不跨标签组合求和）。 */
        public double latestValue(String metricName, Predicate<Map<String, String>> tagMatch) {
            Double latest = null;
            for (OtlpPush push : pushes) {
                for (OtlpSeries s : push.series()) {
                    if (s.name().equals(metricName) && tagMatch.test(s.tags())) {
                        latest = s.value();
                    }
                }
            }
            return latest == null ? 0 : latest;
        }

        /** 是否出现过名字与标签都匹配的序列。 */
        public boolean hasSeries(String metricName, Predicate<Map<String, String>> tagMatch) {
            for (OtlpPush push : pushes) {
                for (OtlpSeries s : push.series()) {
                    if (s.name().equals(metricName) && tagMatch.test(s.tags())) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 最近一次推送是否包含匹配序列（退役的序列不再被推送——退役判据）。 */
        public boolean latestPushHas(String metricName, Predicate<Map<String, String>> tagMatch) {
            if (pushes.isEmpty()) {
                return false;
            }
            for (OtlpSeries s : pushes.get(pushes.size() - 1).series()) {
                if (s.name().equals(metricName) && tagMatch.test(s.tags())) {
                    return true;
                }
            }
            return false;
        }

        /** 最近一次推送中匹配序列的累计和（退役重开断言用——maxSum 会记住退役前并存瞬间的历史峰值）。 */
        public double latestPushSum(String metricName, Predicate<Map<String, String>> tagMatch) {
            if (pushes.isEmpty()) {
                return 0;
            }
            double sum = 0;
            for (OtlpSeries s : pushes.get(pushes.size() - 1).series()) {
                if (s.name().equals(metricName) && tagMatch.test(s.tags())) {
                    sum += s.value();
                }
            }
            return sum;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * 从本批制品 jar 提取资源文本（fat jar 走 BOOT-INF/classes 前缀；版本钉 0.1.3-feat109111，
     * DaDataplaneSupport.readArtifactResource 同款）。
     */
    public static String artifactResourceText(String resource) throws IOException {
        Path jar = sutExecJar();
        try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(jar, Map.of())) {
            for (String candidate : List.of("BOOT-INF/classes/" + resource, resource)) {
                Path entry = fs.getPath(candidate);
                if (Files.exists(entry)) {
                    return Files.readString(entry, StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("制品内未找到资源 " + resource + "（jar=" + jar + "）");
    }

    /** 从本批制品 jar 的资源读 SQL 并按分号切分（先剥行注释；agent-schema.sql 无函数体/美元引用）。 */
    public static List<String> statementsFromArtifact(String resource) throws IOException {
        String script = artifactResourceText(resource);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n")) {
            String stripped = line.replaceFirst("--.*$", "");
            if (stripped.isBlank()) {
                continue;
            }
            current.append(stripped).append('\n');
            if (stripped.trim().endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            throw new IllegalStateException("脚本存在未以分号结尾的残留语句: " + tail);
        }
        return statements;
    }

    /** SUT exec jar 定位（m2repo 中的批次制品；构建前调用会给出明确报错）。 */
    public static Path sutExecJar() {
        String m2Repo = TestConfig.load().getString("sut.m2.repo",
                System.getProperty("user.home") + "/.m2/repository");
        return new MavenArtifact(SUT_GROUP, SUT_ARTIFACT, SUT_VERSION, "exec").jarPath(m2Repo);
    }

    /** OTLP protobuf wire format 最小解析器（无 protobuf 依赖；只取本测试需要的字段）。 */
    static final class OtlpWire {

        private OtlpWire() {
        }

        static List<OtlpSeries> parse(byte[] data) {
            List<OtlpSeries> series = new ArrayList<>();
            for (byte[] resourceMetrics : messages(data, 1)) {
                for (byte[] scopeMetrics : messages(resourceMetrics, 2)) {
                    for (byte[] metric : messages(scopeMetrics, 2)) {
                        parseMetric(metric, series);
                    }
                }
            }
            return series;
        }

        private static void parseMetric(byte[] metric, List<OtlpSeries> out) {
            String name = null;
            byte[] sum = null;
            for (Field field : fields(metric)) {
                if (field.number() == 1 && field.wireType() == 2) {
                    name = new String(field.value(), StandardCharsets.UTF_8);
                } else if (field.number() == 7 && field.wireType() == 2) {
                    sum = field.value();
                }
            }
            if (name == null || sum == null) {
                return;
            }
            for (byte[] dataPoint : messages(sum, 1)) {
                parseDataPoint(name, dataPoint, out);
            }
        }

        private static void parseDataPoint(String metricName, byte[] dataPoint, List<OtlpSeries> out) {
            Map<String, String> tags = new LinkedHashMap<>();
            double value = 0;
            for (Field field : fields(dataPoint)) {
                if (field.number() == 7 && field.wireType() == 2) {
                    parseKeyValue(field.value(), tags);
                } else if (field.number() == 4 && field.wireType() == 1) {
                    value = Double.longBitsToDouble(field.fixed64());
                } else if (field.number() == 6 && field.wireType() == 1) {
                    value = field.fixed64();
                }
            }
            out.add(new OtlpSeries(metricName, Map.copyOf(tags), value));
        }

        private static void parseKeyValue(byte[] keyValue, Map<String, String> tags) {
            String key = null;
            String value = null;
            for (Field field : fields(keyValue)) {
                if (field.number() == 1 && field.wireType() == 2) {
                    key = new String(field.value(), StandardCharsets.UTF_8);
                } else if (field.number() == 2 && field.wireType() == 2) {
                    for (Field any : fields(field.value())) {
                        if (any.number() == 1 && any.wireType() == 2) {
                            value = new String(any.value(), StandardCharsets.UTF_8);
                        }
                    }
                }
            }
            if (key != null && value != null) {
                tags.put(key, value);
            }
        }

        record Field(int number, int wireType, byte[] value) {
            long fixed64() {
                long result = 0;
                for (int i = 7; i >= 0; i--) {
                    result = (result << 8) | (value[i] & 0xFFL);
                }
                return result;
            }
        }

        /** 顺序读取一个 length-delimited 缓冲内的全部字段。 */
        static List<Field> fields(byte[] buffer) {
            List<Field> out = new ArrayList<>();
            int pos = 0;
            while (pos < buffer.length) {
                int[] tag = readVarint(buffer, pos);
                pos = tag[1];
                int number = tag[0] >>> 3;
                int wireType = tag[0] & 0x7;
                if (wireType == 0) {
                    pos = readVarint(buffer, pos)[1];
                } else if (wireType == 1) {
                    if (pos + 8 > buffer.length) {
                        break;
                    }
                    out.add(new Field(number, wireType, java.util.Arrays.copyOfRange(buffer, pos, pos + 8)));
                    pos += 8;
                } else if (wireType == 5) {
                    if (pos + 4 > buffer.length) {
                        break;
                    }
                    out.add(new Field(number, wireType, java.util.Arrays.copyOfRange(buffer, pos, pos + 4)));
                    pos += 4;
                } else if (wireType == 2) {
                    int[] len = readVarint(buffer, pos);
                    pos = len[1];
                    if (pos + len[0] > buffer.length) {
                        break;
                    }
                    out.add(new Field(number, wireType, java.util.Arrays.copyOfRange(buffer, pos, pos + len[0])));
                    pos += len[0];
                } else {
                    break;
                }
            }
            return out;
        }

        /** 抽取指定编号的全部 length-delimited 子消息。 */
        static List<byte[]> messages(byte[] buffer, int fieldNumber) {
            List<byte[]> out = new ArrayList<>();
            for (Field field : fields(buffer)) {
                if (field.number() == fieldNumber && field.wireType() == 2) {
                    out.add(field.value());
                }
            }
            return out;
        }

        private static int[] readVarint(byte[] buffer, int pos) {
            int result = 0;
            int shift = 0;
            while (pos < buffer.length) {
                byte b = buffer[pos++];
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            return new int[] {result, pos};
        }
    }
}
