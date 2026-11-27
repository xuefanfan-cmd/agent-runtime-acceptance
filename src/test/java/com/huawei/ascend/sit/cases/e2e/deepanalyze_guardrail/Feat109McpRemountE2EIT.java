package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_reflection.DaReflectionCostSupport;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_resume.DaResumeSupport;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-109 指纹轮换重挂行为面（F109-17，!512 合并版终口径）：
 * <b>黑盒重挂行为面 runnable；指纹值/等价类=白盒 {@code DaRuntimeMcpToolsTest} 组合承接
 * （docs#58 裁决）</b>——本类不断言指纹值本身。
 *
 * <p>构造（stub 确定性执行体轨道；机制依据 2026-11-24 产品源码复核：DaRunParamMapper
 * META_RUNTIME_CONFIG 载荷入口 → DaAgentHandler.registerRuntimeConfig 建档 →
 * DaRuntimeConfigRail.beforeInvoke → DaRuntimeMcpTools.ensureMounted 指纹自算
 * （SHA-256 前 24 hex，authHeaders/env/hooks 全量进散列）→ serverId=canary@&lt;12hex&gt; →
 * DaMcpSharedConnection.acquire（共享键=租户+serverId——同指纹租约复用、变指纹新连接）；
 * 续跑沿用首轮快照（CONTINUATION_CARRIED_KEYS 任务期钉扎），故「同任务族不同配置」
 * 以同会话多个独立任务构造）：</p>
 *
 * <ul>
 *   <li>T1 alpha：runtimeConfig.mcpServers 携 TS 原生键 MCP server（type=openapi，
 *       url=本测进程落盘的 OpenAPI spec 文件路径，spec servers.url 指回本进程受控接收端；
 *       authHeaders.Authorization=Bearer alpha）→ 接收端捕获 alpha。</li>
 *   <li>T2 beta（同会话新任务、authHeaders 轮换）→ 接收端捕获 beta 且 <b>零 alpha</b>——
 *       重挂新配置生效、旧凭据不再被用（若错误复用旧挂载，出站仍是 alpha，被此断言拦截）。</li>
 *   <li>T3 与 T2 配置逐字相同 → 接收端仍收到 beta——不变配置继续可用（复用判定=同指纹
 *       不重挂，白盒 fingerprintOf_effectiveFieldsUnchanged_reusable 承接等价类）。</li>
 *   <li>权限面：场景 actrule 注入 {@code tools.mcp_canary_echo: allow}（FEAT-040 范式场景级
 *       覆盖、AfterAll 清理）——被测工具免确认直接执行。run-41b/41c 实证：MCP 工具默认
 *       defaults.*=ask，而 ask→会话收口→agent 重建→再挂载撞「MCP server id already exists」
 *       （r3 unmount 不回收 core 全局资源）→ not_found——确认链与指纹轮换正交且在本制品上
 *       是坏死面，故经场景 actrule 放行被测工具；确认面本身由 109 既有确认闭环类承接。</li>
 * </ul>
 *
 * <p>stub 用例注记：script 队列耗尽回落普通回答（ENV-5.17）即任务收尾形态；
 * ENV-5.21 三坑不适用（主路径单步、无成员派遣）。</p>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109McpRemountE2EIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration SSE_READ_TIMEOUT = Duration.ofSeconds(180);
    private static final String TOOL_NAME = "mcp_canary_echo";
    private static final String TASK_INPUT =
            "请调用 mcp_canary_echo 工具（query 参数传 q），得到结果后直接结束任务。";

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static DaReflectionCostSupport.ModelStubServer stub;
    private static McpEchoReceiver receiver;

    /** 受控 MCP 接收端：捕获每次工具调用的 Authorization 头（出站凭据轴）。 */
    private static final class McpEchoReceiver implements AutoCloseable {
        record CapturedCall(long atMillis, String authorization) {
        }

        private final com.sun.net.httpserver.HttpServer server;
        private final List<CapturedCall> calls = new CopyOnWriteArrayList<>();

        McpEchoReceiver() throws java.io.IOException {
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/tools/echo", exchange -> {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                calls.add(new CapturedCall(System.currentTimeMillis(), auth));
                try (var in = exchange.getRequestBody()) {
                    in.readAllBytes();
                }
                byte[] payload = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                // 强制短连接（run-41i 教训链：排除共享 HttpClient 连接池被 keep-alive 污染的假说）
                exchange.getResponseHeaders().set("Connection: close".split(":")[0],
                        "close");
                exchange.sendResponseHeaders(200, payload.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            server.start();
        }

        int size() {
            return calls.size();
        }

        List<CapturedCall> since(int mark) {
            return List.copyOf(calls.subList(Math.min(mark, calls.size()), calls.size()));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        // 场景 actrule 注入（FEAT-040 合并取严范式内的场景级覆盖）：mcp_canary_echo: allow——
        // 免确认直接执行。构造依据（run-41b/41c 实证链）：MCP 工具默认走 defaults.*=ask，
        // 而 ask→会话收口→agent 重建→再挂载撞「MCP server id already exists」（r3 unmount
        // 不回收 core 全局资源）→ 工具视图丢失 not_found——确认链与指纹轮换正交且在本制品上
        // 是坏死面，故本类经场景 actrule 放行被测工具（SUT cwd=检出根，scenarios/ 启动期扫描）。
        writeScenarioActrule();
        config = TestConfig.load();
        receiver = new McpEchoReceiver();
        // OpenAPI spec（白盒 dynamicConfigCanary 同款形态）：serverPath=spec 文件路径，
        // DaOpenApiClient 从文件装载操作索引、POST 到 spec servers.url
        Path spec = tempDir.resolve("f109-17-mcp-spec.json");
        Files.writeString(spec, """
                {
                  "openapi": "3.0.0",
                  "info": {"title": "f109-17 canary", "version": "1"},
                  "servers": [{"url": "http://127.0.0.1:%d"}],
                  "paths": {
                    "/v1/tools/echo": {
                      "post": {
                        "operationId": "echo",
                        "requestBody": {"content": {"application/json": {"schema": {
                          "type": "object",
                          "properties": {
                            "query": {"type": "string"},
                            "__da_session": {"type": "string"},
                            "__da_tenant": {"type": "string"}
                          },
                          "required": ["query"]
                        }}}},
                        "responses": {"200": {"description": "ok"}}
                      }
                    }
                  }
                }
                """.formatted(receiver.server.getAddress().getPort()), StandardCharsets.UTF_8);
        specPath = spec.toAbsolutePath().toString();

        stub = new DaReflectionCostSupport.ModelStubServer();
        // guardrailStack（streaming=true）——与 109/110 全绿类同栈形态（run-41b~41g 教训：
        // baseStack 非流式形态下工具异常后的二次模型调用曾挂起，未在本批制品上验证过多轮
        // 模型调用+工具异常路径）
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent ->
                DaReflectionCostSupport.bindStubModel(agent, stub)).start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaReflectionCostSupport.AGENT).getBaseUrl();
    }

    private static String specPath;

    /** 场景 actrule fixture（检出根 scenarios/<dir>/governance/actrule.yaml；AfterAll 清理）。 */
    private static Path scenarioActruleDir;

    private static void writeScenarioActrule() throws java.io.IOException {
        Path scenariosRoot = Path.of(System.getProperty("basedir", System.getProperty("user.dir")),
                "scenarios");
        scenarioActruleDir = scenariosRoot.resolve("f109-17-mcp-allow");
        Files.createDirectories(scenarioActruleDir.resolve("governance"));
        Files.writeString(scenarioActruleDir.resolve("governance").resolve("actrule.yaml"),
                """
                        enabled: true
                        tools:
                          mcp_canary_echo: allow
                        """, StandardCharsets.UTF_8);
    }

    @AfterAll
    static void tearDown() {
        for (AutoCloseable c : List.of(stack, stub, receiver)) {
            try {
                if (c != null) {
                    c.close();
                }
            } catch (Exception e) {
                System.err.println("[f109-17-close] " + e);
            }
        }
        // 场景 fixture 用后即清（临时产物收尾纪律）
        if (scenarioActruleDir != null) {
            try {
                java.util.stream.Stream<Path> tree = java.nio.file.Files.walk(scenarioActruleDir);
                tree.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                tree.close();
            } catch (Exception e) {
                System.err.println("[f109-17-cleanup] " + e);
            }
        }
    }

    /** 发起带 runtimeConfig 的任务（SSE），有界应答权限确认，等收尾并断言唯一 done/零 error。 */
    private static void runCanaryTask(String session, String credential) throws Exception {
        Map<String, Object> server = new java.util.LinkedHashMap<>();
        server.put("id", "canary");
        server.put("name", "canary");
        // clientType=openapi-da（引擎 SPI 客户端 DaOpenApiClient——authHeaders+spec 文件全功能路径；
        // run-41b 实证：core 内置 "openapi" 型客户端在 Runner 分发下挂载失败
        // [runtime mcp server canary unavailable]，与白盒金丝雀直用 DaOpenApiClient 的路径不同）
        server.put("clientType", "openapi-da");
        server.put("url", specPath);
        server.put("authHeaders", Map.of("Authorization", "Bearer " + credential));
        Map<String, Object> runtime = Map.of(
                "version", 1,
                "tenantId", "default-user",
                "sessionId", session,
                "mcpServers", List.of(server));
        String body = MAPPER.writeValueAsString(Map.of(
                "sessionId", session, "input", TASK_INPUT, "runtimeConfig", runtime));

        // 同步 /run（F111 全绿类既有模式：stub 多轮调用已在 run-01f 证明可用；
        // run-41b~41h 教训：SSE 流观察面在工具异常/长任务下出现收尾帧缺失，弃用流观察）
        stub.scriptToolArgs(List.of(new AbstractMap.SimpleEntry<>(TOOL_NAME, Map.of("query", "q"))));
        // S9 前置步守卫（solution#849，11-25 迁移至共享实现——lsof 补 -a 修正）：turn-0 后
        // 30s 未达 count=2 即取证（n≤3）；完成=stub count≥2
        DaReflectionCostSupport.startStallWatchdog(stack, DaReflectionCostSupport.AGENT,
                session, Path.of("target", "f109-17-dump"), stub.requests()::size,
                () -> stub.requests().size() >= 2);
        HttpResponse<String> run = DaCompatGuardrailSupport.post(http, base, "/api/agents/run", body,
                Duration.ofSeconds(240));
        dumpStubRequests(session, credential);
        assertThat(run.statusCode()).as("/run 受理: " + run.body()).isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode bodyJson = MAPPER.readTree(run.body());
        assertThat(bodyJson.path("status").asText("")).as("任务终态 completed").isEqualTo("completed");
        Allure.addAttachment("任务证据 " + session, "text/plain",
                "credential=Bearer " + credential + " status=" + bodyJson.path("status").asText("")
                        + " stubRequests=" + stub.requests().size());
    }

    /** stub 请求清单落盘（诊断辅助；截断超长体，凭据回显面双保险截断）。 */
    private static void dumpStubRequests(String session, String credential) {
        try {
            Path dumpDir = Path.of("target", "f109-17-dump");
            Files.createDirectories(dumpDir);
            StringBuilder sb = new StringBuilder("session=").append(session)
                    .append(" credential=Bearer ").append(credential).append('\n');
            int i = 0;
            for (DaReflectionCostSupport.StubRequest r : stub.requests()) {
                sb.append("--- stub request #").append(i++).append(" path=").append(r.path())
                        .append(" at=").append(r.receivedAtMillis()).append('\n');
                String body = r.body();
                if (body != null && body.contains(credential)) {
                    sb.append("[body contains credential — truncated]\n--- end\n");
                    continue;
                }
                if (body != null && body.length() > 20_000) {
                    sb.append(body, 0, 20_000).append("\n[truncated at 20000 chars]\n--- end\n");
                } else {
                    sb.append(body).append("\n--- end\n");
                }
            }
            Files.writeString(dumpDir.resolve(session + "-stub-requests.txt"), sb.toString(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[f109-17-dump-failed] " + e);
        }
    }

    @Test
    @Story("F109-17: 指纹轮换重挂行为面——authHeaders 轮换→重挂新凭据到达被调方；不变配置继续可用（指纹值/等价类=白盒组合承接）")
    @DisplayName("F109-17: 凭据轮换重挂与不变复用")
    void remountOnCredentialRotation() throws Exception {
        String session = "sess-f109-17-" + System.nanoTime();
        String alpha = "alpha-" + System.nanoTime();
        String beta = "beta-" + System.nanoTime();

        // ---- T1：alpha 挂载，接收端捕获 alpha ----
        int mark1 = receiver.size();
        runCanaryTask(session, alpha);
        List<McpEchoReceiver.CapturedCall> w1 = receiver.since(mark1);
        assertThat(w1).as("T1 窗口：接收端须捕获 MCP 工具调用").isNotEmpty();
        assertThat(w1).as("T1 窗口全部出站携带 alpha 凭据")
                .extracting(McpEchoReceiver.CapturedCall::authorization)
                .containsOnly("Bearer " + alpha);

        // ---- T2：authHeaders 轮换为 beta——重挂新配置（不复用旧凭据）----
        int mark2 = receiver.size();
        runCanaryTask(session, beta);
        List<McpEchoReceiver.CapturedCall> w2 = receiver.since(mark2);
        assertThat(w2).as("T2 窗口：接收端须捕获 MCP 工具调用").isNotEmpty();
        assertThat(w2).as("T2 窗口全部出站携带 beta 凭据")
                .extracting(McpEchoReceiver.CapturedCall::authorization)
                .containsOnly("Bearer " + beta);

        // ---- T3：配置与 T2 逐字相同——不变配置继续可用（复用判定=同指纹，白盒承接）----
        int mark3 = receiver.size();
        runCanaryTask(session, beta);
        List<McpEchoReceiver.CapturedCall> w3 = receiver.since(mark3);
        assertThat(w3).as("T3 窗口：不变配置下工具调用仍到达接收端（凭据仍为 beta）")
                .isNotEmpty();
        assertThat(w3).as("T3 窗口全部出站携带 beta 凭据")
                .extracting(McpEchoReceiver.CapturedCall::authorization)
                .containsOnly("Bearer " + beta);

        Allure.addAttachment("F109-17 重挂行为面结论", "text/plain",
                "T1 alpha→捕获 alpha；T2 轮换 beta→捕获 beta 且零 alpha（重挂生效，"
                        + "错误复用旧挂载会被拦截）；T3 不变配置→仍捕获 beta（同指纹复用面由"
                        + "白盒 DaRuntimeMcpToolsTest 组合承接——docs#58 裁决）");
    }
}
