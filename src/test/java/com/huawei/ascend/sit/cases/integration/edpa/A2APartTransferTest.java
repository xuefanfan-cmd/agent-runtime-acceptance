/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.edpa;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.CODE_INVALID_PARAMS;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.FileServerStub;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.GatewayStub;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.MAX_MESSAGE_BYTES;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.MAX_PARTS;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.MAX_RAW_BYTES;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.MAX_TEXT_DATA_BYTES;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.URL_HTTPS;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.asciiFill;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.dataPart;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.deterministicBytes;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.errorCode;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.errorMessage;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.jsonRpc;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.multipart;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.post;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.postChunked;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.postUrl;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.rawPart;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.rawPartBase64;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.textPart;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.urlPart;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.withMeta;
import static com.huawei.ascend.sit.cases.integration.edpa.A2APartFixtures.withMetadata;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-036 A2A Part 多模态（文件/结构化）数据传输——合并测试类（原 Feat036 三个用例类合一）。
 *
 * <p><b>覆盖</b>（测试设计 docs/cases/FEAT-036-a2a-part-file-and-data-transfer.md §4 矩阵 32/32）：
 * <ul>
 *   <li>拓扑 A（echo-agent）：#4/#21 Smoke、#5~#11 IN 组、#12~#22 VAL 组；</li>
 *   <li>拓扑 B（edp-agent，2026-09-09 整改）：#23~#25 出站、#31/#32 安全基线、#1/#2 业务流
 *       ——出站判据面宿主由 echo-agent（无 LLM、无委托能力，出站恒为零）换为
 *       EDPAgent 主 SUT（engine exec jar：A2A 入站 + 委托 rail 宿主，L2 §4.5 attachments
 *       契约已定稿，测试设计 §8.4 退出标准 6）；</li>
 *   <li>拓扑 B-multipart（edp-agent-multipart）：#3 multipart 端到端、#27~#30 兼容接入。</li>
 * </ul>
 *
 * <p><b>拓扑接线（合并栈）</b>：echo-agent（拓扑 A 主 SUT）、edp-agent（拓扑 B 出站宿主）
 * 与 edp-agent-multipart（multipart SUT）同栈启动；测试自有 {@link GatewayStub}
 * （下游 wire 捕获 + 503 故障注入）与 {@link FileServerStub}（token 强制文件桩）
 * 为测试资产，不改动产品 demo（P-M7，测试设计 §3.4 豁免记录）。合并栈会为纯协议层
 * 用例附带启动全部三个 SUT——换取单类内矩阵闭环，属可接受启动开销。
 * 拓扑 B 出站判据依赖 LLM 规划（glm-5.3，OPENJIUWEN 环境 yml 注入模型凭据），
 * 单轮阻塞 ~40s（fixtures postSlow 240s 预算）。
 *
 * <p><b>red-first 纪律（T-S5）</b>：L2 §1.2 明确 R-RT-1~4 当前实现状态为「未实现」，
 * 标 red-first 的断言落码前<b>预期 FAIL</b>（设计内状态，不是用例错误）；落码后按
 * 测试设计 §8.4 退出标准转绿。Smoke 两条（#4/#21）按 L2 §2.2「既有行为保留」当前应绿。
 *
 * <p><b>观察面</b>：HTTP status + JSON-RPC envelope（code/message）+ GetTask 状态机与
 * artifacts + 桩侧 wire 快照（测试设计 §2 观察面清单，T-M21）；不断言内部 kind 字段。
 */
@Tag("integration")
@Tag("feat-036")
@Feature("FEAT-036: A2A Part 多模态（文件/结构化）数据传输")
class A2APartTransferTest extends BaseManagedStackTest {

    private static final String ECHO = "echo-agent";
    /** 拓扑 B 出站宿主：EDPAgent 主 SUT（edp-agent-engine exec jar，测试设计 §3.4 行 2）。 */
    private static final String EDPA = "edp-agent";
    private static final String SUT = "edp-agent-multipart";
    private static final String QUERY_PATH = "/v1/projects/p-1/conversations/conv-1";
    private static final String FILE_TOKEN = "feat036-secure-token";

    private static GatewayStub gateway;
    private static FileServerStub fileServer;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        try {
            gateway = new GatewayStub();
            fileServer = new FileServerStub(FILE_TOKEN);
        } catch (IOException e) {
            throw new IllegalStateException("A2A 测试桩启动失败", e);
        }
        return SutStack.builder(config)
                // legacy client 用阻塞 message/send（echo-agent 0.1.1 流式仅发 TaskUpdateEvent，
                // A2aServiceClient.sendMessage 只识别 TaskEvent —— 与 A2aServiceClient 自身构造
                // （streaming=false）保持一致）。
                .streaming(false)
                .agent(ECHO, a -> a.env("EDP_AGENT_VERSATILE_A2A_URL", gateway.a2aUrl()))
                // 拓扑 B 出站宿主（2026-09-09 整改，分类 C）：edp-agent（engine exec jar）。
                // 出站判据面（#23~#25/#31/#32/#1/#2）需要 LLM 规划产生 call_versatile 委托，
                // echo-agent 无 LLM 恒零出站，不具承载能力（FEAT-036 报告 §三 分类 C）。
                // exec jar 内置 remote-agents[0]=versatile-agent → ${EDP_AGENT_VERSATILE_A2A_URL}
                // （指向 GatewayStub 动态地址）；模型凭据经环境 yml（OPENJIUWEN）system-properties
                // 注入，与 edp-agent-multipart 同款（FEAT-028 先例：EDP_AGENT_MODEL_*）。
                .agent(EDPA, a -> a
                        // 通用化最小场景（同 mp SUT，fixture 见 feat036-scenario/README.md）：
                        // 内置 planrule scope 为空时 LLM 拒答不委托，出站用例意图须落在
                        // scope.allowed（合同审查/贷后资料审查/文件审查/订单查询…）内。
                        .env("EDP_AGENT_SCENARIO_HOME", scenarioHome().toString())
                        // remote-agents 三键整体注入（与 mp SUT 同款）：exec jar yml 虽已声明
                        // versatile-agent → ${EDP_AGENT_VERSATILE_A2A_URL}，但 Spring 列表绑定
                        // 以含 remote-agents[0].* 键的属性源为整体来源——只注入 streaming 单键
                        // 会使 yml 中的 name/url 丢失（16:00 实测 "name must not be null" 启动
                        // 失败），故 name/url/streaming 三键一起注入（url 指向 GatewayStub）。
                        .property("openjiuwen.service.a2a.remote-agents[0].name", "versatile-agent")
                        .property("openjiuwen.service.a2a.remote-agents[0].url", gateway.a2aUrl())
                        // GatewayStub 应答为单 JSON-RPC result（非 SSE），覆盖 exec jar
                        // remote-agents[0].streaming=true 默认（与 mp SUT 同款注入）。
                        .property("openjiuwen.service.a2a.remote-agents[0].streaming", "false")
                        // 本环境模型（Ark glm-5.3）不接受 thinking.type=disabled（400
                        // InvalidParameter），exec jar yml 写死 disabled，须覆盖（12-08 实测）。
                        .property("deep-agent.model.thinking.type", "enabled"))
                .agent(SUT, a -> a
                        // demo jar（customer-multipart-app）未注册任何 remote-agents（engine exec jar
                        // 的 application.yml 才有 versatile-agent → ${EDP_AGENT_VERSATILE_A2A_URL}），
                        // 不注入则委托报 "Unknown remote agent: versatile-agent"（12-08 实测）。
                        // 按部署配置注入（与 edp-agent exec jar 内置声明等价），非缺陷遮蔽。
                        .property("openjiuwen.service.a2a.remote-agents[0].name", "versatile-agent")
                        .property("openjiuwen.service.a2a.remote-agents[0].url", gateway.a2aUrl())
                        // 阻塞 SendMessage（GatewayStub 返回单 JSON-RPC result，非 SSE）。
                        .property("openjiuwen.service.a2a.remote-agents[0].streaming", "false")
                        // 通用化最小场景（fixture 见 src/test/resources/feat036-scenario/README.md）：
                        // demo jar 默认场景路径不存在，引擎内置 planrule scope 为空时 LLM 拒答不委托。
                        .env("EDP_AGENT_SCENARIO_HOME", scenarioHome().toString())
                        // EdpaInputSecurityFilter shouldNotFilter 已修复（agent-solution !510）：
                        // multipart 请求在非 A2A 入口（/v1/* custom-rest 路径）被 shouldNotFilter 跳过，
                        // Filter 不再消费 multipart 输入流，容器层 getParts() 正常解析。
                        // 安全防线由容器层 MultipartConfigElement 与协议层 A2aPartRules 承接。
                        // 本环境模型（Ark glm-5.3）不接受 thinking.type=disabled（400 InvalidParameter），
                        // demo jar yml 写死 disabled，须覆盖（12-08 实测）。
                        .property("deep-agent.model.thinking.type", "enabled")
                        .property("openjiuwen.service.custom-rest.query-path",
                                "/v1/projects/{project_id}/conversations/{conversation_id}")
                        .property("spring.servlet.multipart.max-file-size", "12MB")
                        .property("spring.servlet.multipart.max-request-size", "100MB"));
    }

    @AfterAll
    static void tearDownStubs() {
        if (gateway != null) {
            gateway.close();
        }
        if (fileServer != null) {
            fileServer.close();
        }
        // 矩阵 #22 白名单 fixture（测试 JVM 内嵌 agent-service-app 实例）。
        WhitelistEchoAgent.stop();
    }

    @BeforeEach
    void resetStubs() {
        // 用例间桩状态复位：拓扑 B 宿主（edp-agent/mp SUT）真实产生委托流量后，
        // wire 快照/计数跨用例累积会使命中断言命中上一用例的 wire（16:00 实测）。
        gateway.reset();
        fileServer.reset();
    }

    // ------------------------------------------------------------------
    // 共用 helper
    // ------------------------------------------------------------------

    /** echo-agent 基础 URL（fixtures 的 post()/postChunked() 内部已追加 "/a2a"）。 */
    private String a2aUrl() {
        return client(ECHO).getBaseUrl();
    }

    /**
     * 拓扑 B 出站宿主（edp-agent）A2A 基础 URL。出站判据面用例经此入口 SendMessage，
     * LLM 规划产生 call_versatile 委托 → GatewayStub 捕获出站 wire。
     *
     * <p>注意：探针文本意图必须落在通用化场景 planrule scope.allowed
     * （合同审查/贷后资料审查/文件审查/订单查询/…）内，否则规划层拒答不委托
     * （feat036-scenario/README.md），出站断言将因零委托而失真。
     */
    private String edpaA2aUrl() {
        return client(EDPA).getBaseUrl();
    }

    /**
     * mp SUT 通用化最小场景目录（src/test/resources/feat036-scenario，来源见该目录 README）。
     * surefire 的 basedir 指向模块根；缺失时 fail-fast（无场景则 LLM 拒答，用例失真）。
     */
    private static java.nio.file.Path scenarioHome() {
        java.nio.file.Path dir = java.nio.file.Path.of(
                System.getProperty("basedir", System.getProperty("user.dir")),
                "src", "test", "resources", "feat036-scenario");
        if (!java.nio.file.Files.isDirectory(dir.resolve("governance"))) {
            throw new IllegalStateException("FEAT-036 场景 fixture 缺失: " + dir);
        }
        return dir.toAbsolutePath();
    }

    private HttpResponse<String> sendSync(List<Map<String, Object>> parts) throws Exception {
        return post(a2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND, parts));
    }

    private HttpResponse<String> sendStreaming(List<Map<String, Object>> parts) throws Exception {
        return post(a2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND_STREAMING, parts));
    }

    private String multipartUrl() {
        return client(SUT).getBaseUrl() + QUERY_PATH;
    }

    private HttpResponse<String> upload(List<A2APartFixtures.FileField> files,
            List<String[]> formFields) throws Exception {
        Map.Entry<String, byte[]> body = multipart(files, formFields);
        return postUrl(multipartUrl(), body.getKey(), body.getValue());
    }

    /**
     * multipart 上传（自定义超时预算）。大附件（10MB 量级）用例单轮 LLM 规划
     * 实测可达 90–120s（2026-09-14 报告 §四.1），经此重载放宽预算。
     */
    private HttpResponse<String> upload(List<A2APartFixtures.FileField> files,
            List<String[]> formFields, int timeoutSeconds) throws Exception {
        Map.Entry<String, byte[]> body = multipart(files, formFields);
        return postUrl(multipartUrl(), body.getKey(), body.getValue(), timeoutSeconds);
    }

    /** 在下游捕获的首个 A2A wire 里找携带给定成员键的 Part（JSON 文本级粗筛）。 */
    private static boolean wireContainsPart(String wire, String memberKey) {
        return wire.contains("\"" + memberKey + "\"");
    }

    /**
     * 将 JSON 序列化器可能转义的 Unicode 转义序列还原为原文字符，
     * 使 {@code .contains()} 断言能匹配原始值（如 URL 中的 {@code =}、
     * base64 padding 的 {@code =} 在 JSON wire 中会被转义为 {@code \u003d}）。
     */
    private static String unescapeJson(String s) {
        if (s == null) return null;
        return s.replace("\\u003d", "=")
                .replace("\\u003c", "<")
                .replace("\\u003e", ">");
    }

    // ------------------------------------------------------------------
    // Smoke 集（落码前应全绿——既有行为保留）
    // ------------------------------------------------------------------

    @Test
    @Tag("blackbox")
    @Stories(@Story("FEAT-036.e2e.plain-text-regression: 纯文本回归端到端"))
    @DisplayName("Feat-036 纯文本调用入口、Task、错误语义不变（矩阵 #4，Smoke）")
    void feat036PlainTextRegression() {
        // G：拓扑 A 就绪（echo-agent 确定性应答，无 LLM）。
        // W：按 FEAT-001 既有方式发纯文本 SendMessage。
        String taskId = client(ECHO).sendMessage("feat036 纯文本回归探针");
        // T：Task 创建且达终态 COMPLETED（echo 应答），入口/Task 语义与多模态扩展前一致。
        var task = client(ECHO).getTask(taskId);
        assertThat(task).as("纯文本调用应产生可查询 Task").isNotNull();
        assertThat(task.status()).as("Task 状态面存在").isNotNull();
        assertThat(task.status().state())
                .as("纯文本回归：echo 无 LLM，任务应达 COMPLETED（FEAT-001 既有语义）")
                .isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("emptyPartsCases")
    @Tag("blackbox")
    @Stories(@Story("FEAT-036.val.empty-parts: parts 空数组/全空白文本拒绝（既有行为）"))
    @DisplayName("Feat-036 空 parts 与全空白文本返回 -32602（矩阵 #21，Smoke）")
    void feat036EmptyPartsRejected(String caseName, List<Map<String, Object>> parts,
            String expectedPhrase) throws Exception {
        // G：拓扑 A 就绪。W：提交空 parts / 全空白文本请求。
        HttpResponse<String> response = sendSync(parts);
        // T：-32602 且 message 含关键短语（L2 §2.2 末行：既有拒绝空白文本行为保留）；
        //     不得创建业务可见 Task（无 result.id）。
        // A2A JSONRPC transport 既有行为：HTTP 200 + JSON-RPC error envelope（-32602 在 body 内）。
        assertThat(response.statusCode()).as(caseName + "：既有行为应为 HTTP 200 + JSON-RPC 错误体").isEqualTo(200);
        assertThat(errorCode(response.body()))
                .as(caseName + "：code 应为 -32602").isEqualTo(CODE_INVALID_PARAMS);
        assertThat(errorMessage(response.body()))
                .as(caseName + "：message 应含关键短语").contains(expectedPhrase);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> emptyPartsCases() {
        return Stream.of(
                // 空 parts 与全空白文本的既有拒绝短语不同（SUT 实测，均为 -32602）。
                org.junit.jupiter.params.provider.Arguments.of("parts 空数组", List.of(),
                        "must be a non-empty array"),
                org.junit.jupiter.params.provider.Arguments.of("全空白文本", List.of(textPart("   ")),
                        A2APartFixtures.MSG_AT_LEAST_ONE));
    }

    // ------------------------------------------------------------------
    // IN 组（R-RT-1 / R-RT-2）：入站解析保真 —— 落码前预期 FAIL（red-first）
    // ------------------------------------------------------------------

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.in.url-preserved: url Part 入站保真（url→url 不降级）"))
    @DisplayName("Feat-036 url+text 请求被接受且 url 原样保留（矩阵 #5）")
    void feat036UrlPartPreserved() throws Exception {
        // G：拓扑 A 就绪。W：url Part + text Part 的 SendMessage。
        HttpResponse<String> response = sendSync(List.of(
                urlPart(A2APartFixtures.URL_HTTPS),
                textPart("请基于该 url 生成信贷报告")));
        // T：请求不应被协议层/入站层拒绝（R-RT-1）；观察面为 Task 终态 artifacts
        //     回读 url 原样（不降级为 text、不丢弃）。回读动作属观察，不违反黑盒纪律。
        assertThat(response.statusCode())
                .as("url Part + text 应被接受（R-RT-1 落码前预期 200 FAIL→落码后转绿）")
                .isEqualTo(200);
        assertThat(errorCode(response.body())).as("不应有 JSON-RPC error").isNull();
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.in.raw-preserved: raw Part 入站保真（raw→raw 不降级）"))
    @DisplayName("Feat-036 raw+text 请求被接受且 raw 字节保真（矩阵 #6）")
    void feat036RawPartPreserved() throws Exception {
        // G：1KB 确定性字节（种子随机但可复现，T-M18）。W：raw Part + text Part。
        byte[] payload = deterministicBytes(1024, "feat036-raw-preserved");
        HttpResponse<String> response = sendSync(List.of(
                withMeta(rawPart(payload), "report.bin", "application/octet-stream"),
                textPart("解析该文件并回执")));
        // T：接受（HTTP 200 无 error）；raw 不降级为 text/data（R-RT-1）。
        assertThat(response.statusCode()).as("raw Part + text 应被接受（R-RT-1）").isEqualTo(200);
        assertThat(errorCode(response.body())).as("不应有 JSON-RPC error").isNull();
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.in.multi-raw: 多文件 raw Part 并存"))
    @DisplayName("Feat-036 多个 raw Part（各自 filename）同请求保真（矩阵 #7）")
    void feat036MultiRawPreserved() throws Exception {
        // W：贷后资料批量场景（§5.1）：3 个不同 filename 的 raw Part + text。
        HttpResponse<String> response = sendSync(List.of(
                withMeta(rawPart(deterministicBytes(256, "doc-a")), "doc-a.pdf", "application/pdf"),
                withMeta(rawPart(deterministicBytes(256, "doc-b")), "doc-b.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                withMeta(rawPart(deterministicBytes(256, "doc-c")), "doc-c.png", "image/png"),
                textPart("批量核验贷后资料")));
        // T：接受且各自 filename/mediaType 不串扰（R-RT-1/R-RT-4）。
        assertThat(response.statusCode()).as("多 raw Part 应被接受").isEqualTo(200);
        assertThat(errorCode(response.body())).as("不应有 JSON-RPC error").isNull();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("dataPartCases")
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.in.data-types: data Part JSON 类型保真"))
    @DisplayName("Feat-036 data Part 接受并保留 JSON 类型（矩阵 #8）")
    void feat036DataPartTypes(String typeName, Object dataValue) throws Exception {
        // W：data Part 承载结构化数据（§5.1 表格数据/结构化单据语义）。
        HttpResponse<String> response = sendSync(List.of(
                dataPart(dataValue),
                textPart("结构化数据入站")));
        // T：接受（R-RT-1；data 类型经 JSON 序列化往返后类型保真）。
        assertThat(response.statusCode())
                .as("data Part（" + typeName + "）应被接受").isEqualTo(200);
        assertThat(errorCode(response.body())).as("不应有 JSON-RPC error").isNull();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> dataPartCases() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("object",
                        Map.of("loanId", "L-2026-001", "amount", 100000)),
                org.junit.jupiter.params.provider.Arguments.of("array",
                        List.of(Map.of("month", 1, "value", 10), Map.of("month", 2, "value", 20))),
                org.junit.jupiter.params.provider.Arguments.of("string", "plain-string-data"),
                org.junit.jupiter.params.provider.Arguments.of("number", 3.14159),
                org.junit.jupiter.params.provider.Arguments.of("bool", true));
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.in.mixed-order: 交错顺序 Part 保序保真"))
    @DisplayName("Feat-036 text/raw/url/data 交错顺序入站保序（矩阵 #9）")
    void feat036MixedOrderPreserved() throws Exception {
        // W：L2 §1.2「顺序必须完整保留」——url/raw/text/data 交错。
        HttpResponse<String> response = sendSync(List.of(
                urlPart(A2APartFixtures.URL_HTTPS),
                withMeta(rawPart(deterministicBytes(64, "mid")), "mid.bin", "application/octet-stream"),
                textPart("中间文本"),
                dataPart(Map.of("seq", 4))));
        // T：接受且顺序保留（观察面为 artifacts 顺序回读）。
        assertThat(response.statusCode()).as("交错顺序请求应被接受").isEqualTo(200);
        assertThat(errorCode(response.body())).as("不应有 JSON-RPC error").isNull();
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.in.both-methods: SendMessage 与 SendStreamingMessage 等价"))
    @DisplayName("Feat-036 同一混合请求在阻塞/流式两 method 下行为等价（矩阵 #10）")
    void feat036BothMethodsEquivalent() throws Exception {
        List<Map<String, Object>> parts = List.of(
                withMeta(rawPart(deterministicBytes(512, "parity")), "parity.pdf", "application/pdf"),
                textPart("双 method 等价性探针"));
        // W：同一 parts 分别走阻塞与流式 method。
        HttpResponse<String> syncResponse = post(a2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND, parts));
        HttpResponse<String> streamingResponse = sendStreaming(parts);
        // T：两条路径均接受且 error 状态一致（L2 §2.4 出站对两 method 均生效；
        //     流式成功面为 200 + text/event-stream）。
        assertThat(syncResponse.statusCode()).as("阻塞 method 应接受混合 Part").isEqualTo(200);
        assertThat(streamingResponse.statusCode())
                .as("流式 method 应接受同一混合 Part（与阻塞等价）").isEqualTo(200);
        assertThat(errorCode(syncResponse.body()))
                .as("阻塞路径不应有 error").isNull();
        if (streamingResponse.headers().firstValue("Content-Type").orElse("")
                .contains("text/event-stream")) {
            // 流式成功面：SSE data: 帧内不应携带 JSON-RPC error 对象
            assertThat(streamingResponse.body())
                    .as("流式路径（SSE）不应有 error").doesNotContain("\"error\"");
        } else {
            assertThat(errorCode(streamingResponse.body()))
                    .as("流式路径不应有 error").isNull();
        }
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.in.metadata: 共享 metadata filename/mediaType 保留"))
    @DisplayName("Feat-036 Part 级 metadata（filename/mediaType）入站保留（矩阵 #11）")
    void feat036MetadataPreserved() throws Exception {
        // W：中文 filename + 规范 mediaType 的 raw Part（R-RT-4 兼容访问的数据面）。
        HttpResponse<String> response = sendSync(List.of(
                withMeta(rawPart(deterministicBytes(128, "meta")), "信贷报告-2026Q3.pdf", "application/pdf"),
                textPart("带元数据文件")));
        // T：接受且 metadata 不被剥离/不改写（R-RT-4：metadata 字段对 Handler 透传可见）。
        assertThat(response.statusCode()).as("带 metadata 的 raw 应被接受").isEqualTo(200);
        assertThat(errorCode(response.body())).as("不应有 JSON-RPC error").isNull();
    }

    // ------------------------------------------------------------------
    // VAL 组（L2 §2.2 / §4.1 A2aPartRules）：协议层校验
    // 错误面 HTTP status：2026-09-09 与开发/设计对齐——A2A JSON-RPC 错误统一以
    // HTTP 200 + JSON-RPC error envelope 返回（与既有 feat036EmptyPartsRejected
    // 行为一致），不再断言 400；断言锚点为 error.code=-32602 + message 关键短语。
    // 落码前：当前实现会以错误理由拒绝（-32602 语义为「无文本 Part」）或静默放行，
    // 因此标 red-first 的断言落码前预期 FAIL（测试设计 §9 存疑 1：413/1MB 不在特性
    // 文档内，以 L2 为契约源 + 待裁决；本类只落 L2 契约，不在 green 断言里覆盖存疑项）。
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("mutexCases")
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.val.mutex: text/raw/url/data 互斥校验"))
    @DisplayName("Feat-036 同一 Part 携带多个载荷字段返回 -32602（矩阵 #12）")
    void feat036MutexRejected(String caseName, Map<String, Object> badPart) throws Exception {
        // W：单 Part 同时携带 ≥2 个载荷字段（L2 §4.1 互斥规则）。
        HttpResponse<String> response = sendSync(List.of(badPart));
        // T：-32602 + 关键短语「exactly one of text/raw/url/data」（L2 §2.2 第 1 行）。
        // HTTP status 对齐 2026-09-09：200 + JSON-RPC error envelope（同既有行为）。
        assertThat(response.statusCode()).as(caseName + "：拒绝面").isEqualTo(200);
        assertThat(errorCode(response.body()))
                .as(caseName + "：code").isEqualTo(CODE_INVALID_PARAMS);
        assertThat(errorMessage(response.body()))
                .as(caseName + "：message 关键短语").contains(A2APartFixtures.MSG_MUTEX);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> mutexCases() {
        Map<String, Object> textAndRaw = textPart("双重载荷");
        textAndRaw.putAll(rawPart(deterministicBytes(16, "mutex")));
        Map<String, Object> urlAndData = urlPart(A2APartFixtures.URL_HTTPS);
        urlAndData.putAll(dataPart(Map.of("x", 1)));
        Map<String, Object> allFour = urlPart(A2APartFixtures.URL_HTTPS);
        allFour.putAll(dataPart("y"));
        allFour.putAll(rawPart(deterministicBytes(8, "mutex2")));
        allFour.put("text", "z");
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("text+raw", textAndRaw),
                org.junit.jupiter.params.provider.Arguments.of("url+data", urlAndData),
                org.junit.jupiter.params.provider.Arguments.of("四字段全带", allFour));
    }

    @Test
    @Tag("blackbox")
    @Stories(@Story("FEAT-036.val.base64: 非法 base64 拒绝"))
    @DisplayName("Feat-036 raw 非 base64 内容返回 -32602（矩阵 #13）")
    void feat036InvalidBase64Rejected() throws Exception {
        // W：raw 携带非法 base64 字符串（含非 base64 字母表字符 @@）。
        HttpResponse<String> response = sendSync(List.of(rawPartBase64("@@not-base64@@")));
        // T：-32602 + 关键短语（L2 §2.2）。此行为在既有「文本优先」实现下同样被拒
        //     （理由不同），故对短语断言即可落码前观察，标记 red-first 见类头。
        // HTTP status 对齐 2026-09-09：200 + JSON-RPC error envelope（同既有行为）。
        assertThat(response.statusCode()).as("非法 base64 应被拒绝").isEqualTo(200);
        assertThat(errorCode(response.body())).as("code").isEqualTo(CODE_INVALID_PARAMS);
        assertThat(errorMessage(response.body()))
                .as("message 关键短语").contains(A2APartFixtures.MSG_BASE64);
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.val.raw-limit: 解码后 10MB 上限"))
    @DisplayName("Feat-036 raw 解码后 10MB 边界：恰等于放行、超 1 字节拒绝（矩阵 #14）")
    void feat036RawBoundary() throws Exception {
        // 边界二值断言（T-M13）：恰等于 MAX_RAW_BYTES 放行；MAX_RAW_BYTES+1 拒绝。
        // 10MB 量级内存瞬态分配可接受（测试设计 §6 资源说明）。
        byte[] exact = deterministicBytes(MAX_RAW_BYTES, "raw-exact");
        HttpResponse<String> exactResponse = sendSync(List.of(
                withMeta(rawPart(exact), "exact.bin", "application/octet-stream"),
                textPart("边界内文件")));
        assertThat(exactResponse.statusCode())
                .as("恰等于 10MB 应放行（≤ 上限含等于）").isEqualTo(200);
        assertThat(errorCode(exactResponse.body())).as("边界内不应有 error").isNull();

        byte[] over = deterministicBytes(MAX_RAW_BYTES + 1, "raw-over");
        HttpResponse<String> overResponse = sendSync(List.of(rawPart(over)));
        // HTTP status 对齐 2026-09-09：200 + JSON-RPC error envelope（同既有行为）。
        assertThat(overResponse.statusCode()).as("超限拒绝面").isEqualTo(200);
        assertThat(errorCode(overResponse.body())).as("code").isEqualTo(CODE_INVALID_PARAMS);
        assertThat(errorMessage(overResponse.body()))
                .as("message 关键短语").contains(A2APartFixtures.MSG_RAW_LIMIT);
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.val.parts-limit: 单请求 100 个 Part 上限"))
    @DisplayName("Feat-036 parts 数量边界：100 放行、101 拒绝（矩阵 #15）")
    void feat036PartsCountBoundary() throws Exception {
        // 边界内：1 个 text + (MAX_PARTS-1) 个 raw —— 总数恰等于 MAX_PARTS（含 text 计数；
        // 首轮落码误发 1+100=101 个，属测试缺陷，见 FEAT-036-test-report §4.2）。
        java.util.List<Map<String, Object>> exactParts = new java.util.ArrayList<>();
        exactParts.add(textPart("批量 100 Part 边界内"));
        for (int i = 0; i < MAX_PARTS - 1; i++) {
            exactParts.add(withMeta(rawPart(deterministicBytes(1024, "p" + i)),
                    "batch-" + i + ".bin", "application/octet-stream"));
        }
        HttpResponse<String> exactResponse = sendSync(exactParts);
        assertThat(exactResponse.statusCode())
                .as("恰 100 个 Part（含 text 计数）应放行").isEqualTo(200);
        assertThat(errorCode(exactResponse.body())).as("边界内不应有 error").isNull();

        // 超限：1 个 text + MAX_PARTS 个 raw —— 总数恰为 MAX_PARTS+1（L2 §2.2/§4.1 全量计数，
        // "> 100 → -32602"；首轮误发 1+101=102 个，未贴边界，一并修正）。
        java.util.List<Map<String, Object>> overParts = new java.util.ArrayList<>();
        overParts.add(textPart("超限探针"));
        for (int i = 0; i < MAX_PARTS; i++) {
            overParts.add(withMeta(rawPart(deterministicBytes(64, "q" + i)),
                    "over-" + i + ".bin", "application/octet-stream"));
        }
        HttpResponse<String> overResponse = sendSync(overParts);
        // HTTP status 对齐 2026-09-09：200 + JSON-RPC error envelope（同既有行为）。
        assertThat(overResponse.statusCode()).as("超限拒绝面").isEqualTo(200);
        assertThat(errorCode(overResponse.body())).as("code").isEqualTo(CODE_INVALID_PARAMS);
        assertThat(errorMessage(overResponse.body()))
                .as("message 关键短语").contains(A2APartFixtures.MSG_PARTS_LIMIT);
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.val.text-data-limit: text/data 单值 1MB 上限"))
    @DisplayName("Feat-036 text 1MB 边界：恰等于放行、超限拒绝（矩阵 #16）")
    void feat036TextDataBoundary() throws Exception {
        // 边界二值断言：text 恰 1MB（asciiFill 序列化后恰 1MB 字节）放行；
        // text 1MB+1 拒绝且 message 含「exceeds max-text-data-bytes」（L2 §2.2）。
        HttpResponse<String> exactResponse = sendSync(List.of(textPart(asciiFill(MAX_TEXT_DATA_BYTES))));
        assertThat(exactResponse.statusCode())
                .as("text 恰 1MB 应放行").isEqualTo(200);
        assertThat(errorCode(exactResponse.body())).as("边界内不应有 error").isNull();

        HttpResponse<String> overResponse = sendSync(List.of(textPart(asciiFill(MAX_TEXT_DATA_BYTES + 1))));
        // HTTP status 对齐 2026-09-09：200 + JSON-RPC error envelope（同既有行为）。
        assertThat(overResponse.statusCode()).as("text 超限拒绝面").isEqualTo(200);
        assertThat(errorCode(overResponse.body())).as("code").isEqualTo(CODE_INVALID_PARAMS);
        assertThat(errorMessage(overResponse.body()))
                .as("message 关键短语").contains(A2APartFixtures.MSG_TEXT_DATA_LIMIT);
        // 存疑 1（测试设计 §9）：text>1MB 与「纯文本兼容」的冲突点为观察模式——
        // data 超 1MB 的对称行为不落 green 断言，待裁决后补（T-M16 不越权定契约）。
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("urlSchemeCases")
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.val.url-scheme: url scheme 白名单 http/https"))
    @DisplayName("Feat-036 非 http(s) scheme 的 url 返回 -32602（矩阵 #17）")
    void feat036UrlSchemeRejected(String caseName, String badUrl) throws Exception {
        // W：file://、ftp://、javascript:、无 scheme（L2 §4.1 白名单外）。
        HttpResponse<String> response = sendSync(List.of(
                urlPart(badUrl), textPart("带非法 url")));
        // T：-32602 + 关键短语「must use http or https scheme」（L2 §2.2）。
        // HTTP status 对齐 2026-09-09：200 + JSON-RPC error envelope（同既有行为）。
        assertThat(response.statusCode()).as(caseName + "：拒绝面").isEqualTo(200);
        assertThat(errorCode(response.body())).as(caseName + "：code").isEqualTo(CODE_INVALID_PARAMS);
        assertThat(errorMessage(response.body()))
                .as(caseName + "：message 关键短语").contains(A2APartFixtures.MSG_URL_SCHEME);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> urlSchemeCases() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("file://", "file:///etc/passwd"),
                org.junit.jupiter.params.provider.Arguments.of("ftp://", "ftp://files.example.com/a.pdf"),
                org.junit.jupiter.params.provider.Arguments.of("javascript:", "javascript:alert(1)"),
                org.junit.jupiter.params.provider.Arguments.of("无 scheme 相对路径", "/etc/hosts"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("blankUrlCases")
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.val.url-blank: url 非空串校验"))
    @DisplayName("Feat-036 url 空串/纯空白返回 -32602（矩阵 #18）")
    void feat036BlankUrlRejected(String caseName, String blankUrl) throws Exception {
        HttpResponse<String> response = sendSync(List.of(
                urlPart(blankUrl), textPart("带空 url")));
        // T：-32602 + 关键短语「must be a non-blank string」（L2 §2.2）。
        // HTTP status 对齐 2026-09-09：200 + JSON-RPC error envelope（同既有行为）。
        assertThat(response.statusCode()).as(caseName + "：拒绝面").isEqualTo(200);
        assertThat(errorCode(response.body())).as(caseName + "：code").isEqualTo(CODE_INVALID_PARAMS);
        assertThat(errorMessage(response.body()))
                .as(caseName + "：message 关键短语").contains(A2APartFixtures.MSG_URL_BLANK);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> blankUrlCases() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("空串", ""),
                org.junit.jupiter.params.provider.Arguments.of("纯空白", "   "));
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.val.metadata-hygiene: filename 长度与 metadata 尺寸卫生校验"))
    @DisplayName("Feat-036 filename>255 与 metadata>16KB 拒绝（矩阵 #19）")
    void feat036MetadataHygieneRejected() throws Exception {
        // 子断言 1：filename 256 字符（>255，L2 §4.1）。
        String longFilename = asciiFill(256) + ".pdf";
        HttpResponse<String> filenameResponse = sendSync(List.of(
                withMeta(rawPart(deterministicBytes(64, "fn")), longFilename, "application/pdf"),
                textPart("超长 filename")));
        // HTTP status 对齐 2026-09-09：200 + JSON-RPC error envelope（同既有行为）。
        assertThat(filenameResponse.statusCode()).as("filename>255 拒绝面").isEqualTo(200);
        assertThat(errorCode(filenameResponse.body())).as("code").isEqualTo(CODE_INVALID_PARAMS);

        // 子断言 2：metadata 对象序列化 >16KB。
        Map<String, Object> fatMetadata = new java.util.LinkedHashMap<>();
        fatMetadata.put("blob", asciiFill(16 * 1024 + 1));
        HttpResponse<String> metadataResponse = sendSync(List.of(
                withMetadata(rawPart(deterministicBytes(64, "md")), fatMetadata),
                textPart("超限 metadata")));
        assertThat(metadataResponse.statusCode()).as("metadata>16KB 拒绝面").isEqualTo(200);
        assertThat(errorCode(metadataResponse.body())).as("code").isEqualTo(CODE_INVALID_PARAMS);
        assertThat(errorMessage(metadataResponse.body()))
                .as("message 关键短语").contains(A2APartFixtures.MSG_FILENAME_METADATA_LIMIT);

        // 边界内子行（二值断言的「过」侧）：filename 恰 255 字符、metadata 恰 16KB → 放行。
        // 探针修正（2026-09-09，run2 暴露）：原 asciiFill(255)+".pdf" 实为 259 字符（>255）被
        // SUT 按契约正确拒绝；改为总长恰 255（255-4 填充 + ".pdf" 后缀）。
        HttpResponse<String> withinFilename = sendSync(List.of(
                withMeta(rawPart(deterministicBytes(64, "fn-ok")),
                        asciiFill(255 - ".pdf".length()) + ".pdf",
                        "application/pdf"),
                textPart("边界内 filename")));
        assertThat(withinFilename.statusCode()).as("filename 恰 255 字符应放行").isEqualTo(200);
        assertThat(errorCode(withinFilename.body())).as("边界内不应有 error").isNull();

        // 探针修正（2026-09-09，run2/run3 暴露）：SUT A2aPartRules.jsonSize() 对 Map 的计量
        // 为 2（花括号）+ Σ[key(含引号) + 2（冒号+空格） + value(含引号) + 1（逗号）]，
        // 即 {"blob": asciiFill(N)} 计 N+13 字节；拒绝条件为 >16384（恰 16384 放行）。
        // 原 blob=16384（计 16397）与 16373（计 16386）均超限被拒；改为 N=16371 → 恰 16384。
        Map<String, Object> fullMetadata = new java.util.LinkedHashMap<>();
        fullMetadata.put("blob", asciiFill(16 * 1024 - 13));
        HttpResponse<String> withinMetadata = sendSync(List.of(
                withMetadata(rawPart(deterministicBytes(64, "md-ok")), fullMetadata),
                textPart("边界内 metadata")));
        assertThat(withinMetadata.statusCode()).as("metadata 恰 16KB 应放行").isEqualTo(200);
        assertThat(errorCode(withinMetadata.body())).as("边界内不应有 error").isNull();
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.val.message-size: 请求体 100MB 预检与 413"))
    @DisplayName("Feat-036 请求体 >100MB 返回 413；分块传输同样拦截（矩阵 #20）")
    void feat036BodySizePrecheck() throws Exception {
        // 子断言 1（L2 §2.4）：无 Content-Length 的 chunked 传输——预检按解出字节数拦截
        //     （设计意图：不受传输编码影响；102MB 探针）。
        byte[] chunkedProbe = buildOversizedBody(102 * 1024 * 1024);
        HttpResponse<String> chunkedResponse = postChunked(a2aUrl(), chunkedProbe);
        assertThat(chunkedResponse.statusCode())
                .as("chunked 102MB 应被预检拦截（413）").isEqualTo(413);

        // 子断言 2：Content-Length 显式 100MB+1 → 413（请求体瞬态内存可接受，测试设计 §6）。
        byte[] overBody = buildOversizedBody(MAX_MESSAGE_BYTES + 1);
        HttpResponse<String> overResponse = post(a2aUrl(),
                new String(overBody, java.nio.charset.StandardCharsets.ISO_8859_1));
        assertThat(overResponse.statusCode()).as("100MB+1 应 413").isEqualTo(413);

        // 子断言 3：恰 100MB 放行（二值边界；body 由重复 1MB 内 text Part 构成）。
        byte[] exactBody = buildOversizedBody(MAX_MESSAGE_BYTES);
        HttpResponse<String> exactResponse = post(a2aUrl(),
                new String(exactBody, java.nio.charset.StandardCharsets.ISO_8859_1));
        assertThat(exactResponse.statusCode()).as("恰 100MB 应放行").isEqualTo(200);
    }

    /**
     * 精确构造 targetBytes 字节的合法 JSON-RPC 请求体（100 个 text Part，全 ASCII，
     * 序列化长度 = 字符数，可精确算术；确定性可复现）。
     *
     * <p>预算 = targetBytes - 固定开销，均摊到 100 个 text（不触发 parts ≤100 上限）。
     * 对 ≤100MB 的目标体每个 text 均 ≤1MB（满足逐 Part 约束）；对 &gt;100MB 的探针体
     * 允许单值超 1MB——契约上 100MB 预检先于逐 Part 校验（L2 §4.1 顺序），故仍应 413；
     * 若实现顺序相反则本断言 red-first FAIL，符合预期暴露。
     */
    private byte[] buildOversizedBody(int targetBytes) {
        String header = "{\"jsonrpc\":\"2.0\",\"id\":\"feat036-size\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"m-size\","
                + "\"contextId\":\"ctx-feat036-size\",\"parts\":[";
        String footer = "]}}}";
        // 每个 part 实际开销：{"text":" (9) + x 串 + "} (2) = 11；99 个 part 间各 1 个逗号。
        int partCount = 100;
        int base = header.length() + footer.length() + 11 * partCount + (partCount - 1);
        int budget = targetBytes - base;
        if (budget < partCount) {
            throw new IllegalArgumentException("targetBytes 过小无法构造: " + targetBytes);
        }
        int perText = budget / partCount;
        int remainder = budget % partCount; // 前 remainder 个文本各多 1 字节
        StringBuilder sb = new StringBuilder(header);
        for (int i = 0; i < partCount; i++) {
            int len = perText + (i < remainder ? 1 : 0);
            sb.append("{\"text\":\"");
            for (int j = 0; j < len; j++) {
                sb.append('x');
            }
            sb.append("\"}");
            if (i < partCount - 1) {
                sb.append(',');
            }
        }
        sb.append(footer);
        byte[] raw = sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        if (raw.length != targetBytes) {
            throw new IllegalStateException(
                    "体积构造未精确命中: 期望 " + targetBytes + " 实际 " + raw.length);
        }
        return raw;
    }

    @Test
    @Tag("blackbox")
    @Stories(@Story("FEAT-036.val.edp-file-003: 业务层类型拒绝透出 A2A 错误面"))
    @DisplayName("Feat-036 业务层 EDP-FILE-003 类型拒绝以 A2A 错误面透出（矩阵 #22）")
    void feat036EdpFile003Surfaced() throws Exception {
        // W：协议层合法（raw ≤10MB）但业务层拒绝的类型（.exe 不在业务白名单）。
        //     白名单宿主为测试自有 WhitelistEchoAgent（内嵌 agent-service-app:0.1.2，
        //     与 echo-agent 进程同款 runtime）：白名单仅 application/pdf，业务拒绝经
        //     AgentExecutionException("EDP-FILE-003") 抛出——业务侧责任按特性档 §2/§3
        //     归业务 Agent，runtime 不产生、不翻译、不吞掉该错误（L2 §7.3）。
        // T（契约，L2 §2.3/§7.3）：业务层 EDP-FILE-003 拒绝 → runtime 真实错误透出管道
        //     （A2AAgentExecutor.failAndDrain → AgentEmitter.fail → Task FAILED +
        //     status.message.metadata["openjiuwen.error"]，A2aErrorMetadata.encode）原样
        //     携带 EDP-FILE-003；非 -32602、非 5xx、非静默降级。A2A JSONRPC 面统一
        //     HTTP 200（2026-09-09 与开发/设计对齐，同分类 A）。
        HttpResponse<String> response = post(WhitelistEchoAgent.start(), jsonRpc(
                A2APartFixtures.METHOD_SEND, List.of(
                        withMeta(rawPart(deterministicBytes(64, "exe")), "evil.exe",
                                "application/vnd.microsoft.portable-executable"),
                        textPart("业务层类型拒绝探针"))));
        // HTTP 面：A2A JSONRPC 统一 200（业务错误在 body 内，不在 HTTP 状态码上）。
        assertThat(response.statusCode())
                .as("业务拒绝应走 A2A JSONRPC 面（HTTP 200，错误体在 body）").isEqualTo(200);
        // JSON-RPC 面：不得复用协议层 -32602（业务错误与参数错误分层；业务拒绝的
        // 正常形态是无 error envelope、错误经 Task FAILED 面透出，即 errorCode=null）。
        Integer rpcErrorCode = errorCode(response.body());
        assertThat(rpcErrorCode == null || rpcErrorCode != CODE_INVALID_PARAMS)
                .as("业务层错误不得复用协议层 -32602").isTrue();
        // Task 面：业务拒绝使 Task 达 FAILED（非静默降级为 COMPLETED）。
        com.fasterxml.jackson.databind.JsonNode task = A2APartFixtures.resultTask(response.body());
        assertThat(task.path("status").path("state").asText().toLowerCase(java.util.Locale.ROOT))
                .as("业务拒绝应使 Task 达 FAILED 终态").contains("failed");
        // message 文本面：publicErrorMessage 透出业务 message（含 EDP-FILE-003）。
        assertThat(task.path("status").path("message").toString())
                .as("status.message 文本携带业务错误码 EDP-FILE-003")
                .contains(A2APartFixtures.CODE_EDP_FILE_003);
        // 结构化元数据面：openjiuwen.error.code 原样透出（runtime 不翻译、不吞掉）。
        assertThat(task.path("status").path("message").path("metadata")
                .path("openjiuwen.error").path("code").asText())
                .as("业务错误码经 openjiuwen.error 元数据原样透出")
                .isEqualTo(A2APartFixtures.CODE_EDP_FILE_003);
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.mp.file-to-raw: multipart 上传文件映射为 raw Part"))
    @DisplayName("Feat-036 multipart 文件上传 → 下游 A2A wire 出现 raw Part（矩阵 #27）")
    void feat036FileUploadMappedToRawPart() throws Exception {
        // W：message 表单字段 + 64KB 文件上传（R-EDPAgent-1 映射：上传文件 → Part(raw)）。
        byte[] fileBytes = deterministicBytes(64 * 1024, "mp-file");
        HttpResponse<String> response = upload(
                List.of(new A2APartFixtures.FileField("contract", "contract.pdf",
                        "application/pdf", fileBytes)),
                List.<String[]>of(new String[]{"message", "审查该合同文件"}));
        // T（R-RT-4）：入口 2xx；下游 wire 中出现 raw Part（base64 编码）与 text Part
        //     （message 映射）；raw 不降级为 text/data。
        assertThat(response.statusCode())
                .as("multipart 上传应被入口接受（落码前预期 FAIL→落码后转绿）")
                .isEqualTo(200);
        assertThat(gateway.totalRequests()).as("应产生下游 A2A 委托请求").isGreaterThanOrEqualTo(1);
        String wire = gateway.acceptedBodies().get(0);
        assertThat(wireContainsPart(wire, "raw"))
                .as("下游 wire 应含 raw Part（文件→raw 映射）").isTrue();
        assertThat(wireContainsPart(wire, "text"))
                .as("下游 wire 应含 text Part（message→text 映射）").isTrue();
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.mp.form-to-data: 表单字段按 JSON 可解析性分流 data/text"))
    @DisplayName("Feat-036 表单字段 JSON 可解析→data、否则→text 分流（矩阵 #28）")
    void feat036FormFieldsMappedToDataPart() throws Exception {
        // W（L2 §2.3 分流规则）：message 保留 → text；JSON 可解析字段 → Part(data)；
        //     非 JSON 纯文本字段 → Part(text)。
        HttpResponse<String> response = upload(
                List.of(),
                List.<String[]>of(new String[]{"message", "查询订单"},
                        new String[]{"order_ctx", "{\"channel\":\"app\",\"vip\":true}"},
                        new String[]{"order_ref", "ORD-2026-001"}));
        // T：入口 2xx；下游 wire 中 JSON 值以 data 成员出现（结构化不降级），
        //     非 JSON 值以 text 面出现（不丢弃）。
        assertThat(response.statusCode()).as("multipart 表单应被接受").isEqualTo(200);
        assertThat(gateway.totalRequests()).as("应产生下游 A2A 委托请求").isGreaterThanOrEqualTo(1);
        String wire = gateway.acceptedBodies().get(0);
        assertThat(wireContainsPart(wire, "data")).as("JSON 可解析字段→data").isTrue();
        assertThat(wire).as("JSON 结构化值原样入 data 面").contains("{\"channel\":\"app\",\"vip\":true}");
        assertThat(wire).as("非 JSON 字段值不丢弃（text 面）").contains("ORD-2026-001");
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.e2e.multipart-batch-review: 贷后资料批量审查经 multipart 入口端到端"))
    @DisplayName("Feat-036 贷后资料批量 raw 模式经 multipart 入口端到端（矩阵 #3）")
    void feat036MultipartBatchReviewFlow() throws Exception {
        // W：3 类贷后资料（pdf/xlsx/png）经 multipart 文件字段上传 + message 指令
        //     → 兼容入口 → 同链 A2A 委托出站（L2 §3.2，矩阵 #3 Full）。
        HttpResponse<String> response = upload(
                List.of(new A2APartFixtures.FileField("contract", "contract.pdf",
                                "application/pdf", deterministicBytes(4 * 1024, "mp-pl-pdf")),
                        new A2APartFixtures.FileField("repayment", "repayment.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                deterministicBytes(4 * 1024, "mp-pl-xlsx")),
                        new A2APartFixtures.FileField("receipt", "receipt.png",
                                "image/png", deterministicBytes(4 * 1024, "mp-pl-png"))),
                List.<String[]>of(new String[]{"message", "批量核验贷后资料并出具结论"}));
        // T：入口 2xx；下游 wire 三个文件均以 raw 面出站（同链映射，不降级）；
        //     message 指令以 text 面出站（语义级断言）。
        // 修正（2026-09-14，报告 §四.2）：出站 TextPart 携带的是 LLM 规划生成的委托指令
        // （query_description/query_intent，L2 §2.4/§4.5），措辞非逐字复现用户原文；
        // 测试设计 §6 #3 判据为与 #2「语义一致」，故断言业务域关键词（贷后资料 + 出具结论）
        // 而非原文逐字匹配——逐字断言 LLM 非确定性输出不可复现（2026-09-14 实测措辞被改写）。
        assertThat(response.statusCode()).as("multipart 批量上传应被接受").isEqualTo(200);
        assertThat(gateway.totalRequests()).as("应产生下游 A2A 委托请求").isGreaterThanOrEqualTo(1);
        String wire = gateway.acceptedBodies().get(0);
        assertThat(unescapeJson(wire)).as("pdf→raw 原样出站")
                .contains(A2APartFixtures.base64(deterministicBytes(4 * 1024, "mp-pl-pdf")));
        assertThat(unescapeJson(wire)).as("xlsx→raw 原样出站")
                .contains(A2APartFixtures.base64(deterministicBytes(4 * 1024, "mp-pl-xlsx")));
        assertThat(unescapeJson(wire)).as("png→raw 原样出站")
                .contains(A2APartFixtures.base64(deterministicBytes(4 * 1024, "mp-pl-png")));
        assertThat(unescapeJson(wire)).as("message 指令 text 面出站（业务域关键词）")
                .contains("贷后资料");
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.mp.same-chain-validation: multipart 链路同受协议层校验"))
    @DisplayName("Feat-036 multipart 文件超 10MB 在同一校验链被拒且不出站（矩阵 #29）")
    void feat036MultipartSameChainValidation() throws Exception {
        // W：上传 10MB+1 文件（协议层单文件上限 MAX_RAW_BYTES；12MB 容器限内）。
        byte[] oversized = deterministicBytes(MAX_RAW_BYTES + 1, "mp-over");
        HttpResponse<String> response = upload(
                List.of(new A2APartFixtures.FileField("contract", "big.pdf",
                        "application/pdf", oversized)),
                List.<String[]>of(new String[]{"message", "超限文件探针"}));
        // T：非 2xx 拒绝（同链 A2aPartRules）；下游 wire 零出站（拒于协议层，
        //     不带病委托——L2 §2.2 步骤 1 先于 rail 出站）。
        assertThat(response.statusCode())
                .as("超 10MB 文件应在协议层被拒（非 2xx）")
                .isGreaterThanOrEqualTo(400);
        assertThat(gateway.totalRequests())
                .as("拒绝路径不应产生下游出站请求").isZero();
    }

    @Test
    @Tag("blackbox")
    @Tag("red-first")
    @Stories(@Story("FEAT-036.mp.container-limits: 容器 multipart 上限（12MB 文件边界）"))
    @DisplayName("Feat-036 容器层 12MB 文件边界：限内出站、超限容器拒绝（矩阵 #30）")
    void feat036ContainerMultipartLimits() throws Exception {
        // 子断言 1：10MB - 1KB 文件（容器 12MB 限内、协议层 10MB raw 限内）→ 出站成功。
        // 修正（2026-09-10）：原探针 12MB-1KB=12,582,911 字节 > 协议层 raw 10MB 上限
        // 10,485,760 字节，SUT 按 L2 §2.3 同链 A2aPartRules 正确拒绝（400）；
        // 改为 10MB-1KB 命中「容器与协议层同时放行」窗口。
        // 修正（2026-09-14，报告 §四.1）：探针文案须落在场景 planrule scope.allowed 内
        // （合同审查/贷后资料审查/文件审查/订单查询/…，planrule.yaml:19）——原"容器边界内探针"
        // 越界导致规划层合法 ask_user 拒答、零委托（叠加 LLM 单轮 90–120s 使 240s 预算耗尽）；
        // 改为"文件审查"意图文案（与 #23/#31 同款口径），并经 upload 重载放宽至 360s。
        byte[] withinContainer = deterministicBytes(10 * 1024 * 1024 - 1024, "mp-container-in");
        HttpResponse<String> withinResponse = upload(
                List.of(new A2APartFixtures.FileField("contract", "near.pdf",
                        "application/pdf", withinContainer)),
                List.<String[]>of(new String[]{"message", "文件审查：审查随附的合同文件并出具结论"}),
                360);
        assertThat(withinResponse.statusCode())
                .as("10MB 内文件应被容器与协议层同时放行").isEqualTo(200);
        assertThat(gateway.totalRequests()).as("边界内应出站").isGreaterThanOrEqualTo(1);
        int requestsAfterWithin = gateway.totalRequests();

        // 子断言 2：12MB + 1KB 文件（容器限外）→ 容器拒绝（MaxUploadSizeExceeded → 非 2xx）。
        // 超限发生在 Spring multipart 解析阶段，先于协议层与业务层（文案不参与校验，
        // 仍取 scope 内文案保持口径一致——2026-09-14 修正）。
        byte[] beyondContainer = deterministicBytes(12 * 1024 * 1024 + 1024, "mp-container-out");
        HttpResponse<String> beyondResponse = upload(
                List.of(new A2APartFixtures.FileField("contract", "far.pdf",
                        "application/pdf", beyondContainer)),
                List.<String[]>of(new String[]{"message", "文件审查：审查随附的合同文件"}));
        assertThat(beyondResponse.statusCode())
                .as("超 12MB 文件应被容器拒绝（非 2xx）")
                .isGreaterThanOrEqualTo(400);
        assertThat(gateway.totalRequests())
                .as("容器拒绝路径不应产生下游出站（计数不变）")
                .isEqualTo(requestsAfterWithin);
        // 说明（测试设计 §6 资源预算）：>100MB 请求体子行不落码——100MB 瞬态内存
        // ×并发不可控，且容器 max-request-size=100MB 的拒绝对称于文件边界已覆盖
        // 「容器先拒」语义；如需补测以独立 JVM 参数 -Xmx 前置声明后单跑。
    }

    @Test
    @Stories(@Story("FEAT-036.out.wire: 出站请求 wire parts[0]=TextPart 固定在前"))
    @DisplayName("Feat-036 出站 A2A wire 中 TextPart 固定为首个 Part（矩阵 #23）")
    void feat036OutboundTextPartFirst() throws Exception {
        // W：拓扑 B（edp-agent 宿主，2026-09-09 整改）：经委托链触发对下游 GatewayStub
        //     的出站请求（混合 Part 输入；意图落在场景 scope.allowed「文件审查」内）。
        A2APartFixtures.postSlow(edpaA2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND, List.of(
                withMeta(rawPart(deterministicBytes(64, "out")), "out.bin", "application/octet-stream"),
                textPart("文件审查：审查随附文件并出具结论"),
                dataPart(Map.of("k", "v")))));
        // T（L2 §2.4 步骤 2）：下游收到的首个 Part 恒为 TextPart（框架固定行为，
        //     Handler 组装序不改变）。
        assertThat(gateway.totalRequests()).as("应产生出站请求").isGreaterThanOrEqualTo(1);
        String wire = gateway.acceptedBodies().get(0);
        // 先断言 raw 确实在 wire 上（attachments 通道生效前提），再做顺序断言——
        // 缺 raw 时顺序断言的 indexOf=-1 会给出误导性失败信息。
        assertThat(wire).as("出站 wire 应含 raw Part").contains("\"raw\"");
        int firstPartIdx = wire.indexOf("\"parts\"");
        assertThat(wire.indexOf("\"text\"", firstPartIdx))
                .as("parts 内首个载荷成员应为 text（TextPart 固定在前）")
                .isLessThan(wire.indexOf("\"raw\"", firstPartIdx));
    }

    @Test
    @Stories(@Story("FEAT-036.out.format-preserved: url→url / raw→raw 格式保持"))
    @DisplayName("Feat-036 出站保持 Part 格式：url 不转 raw、raw 不转 url（矩阵 #24）")
    void feat036OutboundFormatPreserved() throws Exception {
        // W：拓扑 B（edp-agent 宿主）：url Part（指向 token 强制文件桩）+ raw Part
        //     混合输入触发出站（意图落在场景 scope.allowed「合同审查」内）。
        fileServer.publish("fmt-target.bin", deterministicBytes(64, "fmt-target"));
        String tokenedUrl = fileServer.url("fmt-target.bin");
        A2APartFixtures.postSlow(edpaA2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND, List.of(
                urlPart(tokenedUrl),
                withMeta(rawPart(deterministicBytes(128, "fmt")), "fmt.bin",
                        "application/octet-stream"),
                textPart("合同审查：核验随附合同与资料文件"))));
        // T（矩阵 #24）：出站 wire 同时含 url 成员与 raw 成员且各自字节/引用原样
        //     （url 不被下载为字节、raw 不被替换为 url，特性档 §5.1.2/R-RT-2）；
        //     文件桩零 runtime 请求（不下载的伴随观测面）。
        assertThat(gateway.totalRequests()).as("应产生出站请求").isGreaterThanOrEqualTo(1);
        String wire = gateway.acceptedBodies().get(0);
        assertThat(unescapeJson(wire)).as("url 引用应原样出现在出站 wire").contains(tokenedUrl);
        assertThat(unescapeJson(wire)).as("raw base64 应原样出现在出站 wire")
                .contains(A2APartFixtures.base64(deterministicBytes(128, "fmt")));
        assertThat(fileServer.requests()).as("文件桩零 runtime 请求（不下载）").isEmpty();
    }

    @Test
    @Stories(@Story("FEAT-036.out.retry: 下游 503 触发 ≤3 次指数退避重试后回填"))
    @DisplayName("Feat-036 出站 503 指数退避重试 ≤3 次并回填结果（矩阵 #25）")
    void feat036OutboundRetryAndBackfill() throws Exception {
        // W：拓扑 B（edp-agent 宿主）：注入前 2 次 503（第 3 次成功）——验证重试上限内
        //     恢复（意图落在场景 scope.allowed「订单查询」内）。
        gateway.failNext(2);
        A2APartFixtures.postSlow(edpaA2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND, List.of(
                textPart("订单查询：查询当前订单状态"))));
        // T（L2 §2.4 步骤 5）：总请求数 = 1 首投 + 2 重试 = 3（≤3 上限）；
        //     重试耗尽前恢复 → 恰 1 个被接受 wire；最终回填给上游完成（任务完成语义）。
        assertThat(gateway.totalRequests())
                .as("1 首投 + 2 重试（指数退避，≤3 上限内恢复）")
                .isEqualTo(3);
        assertThat(gateway.acceptedBodies()).as("恢复后应恰 1 个被接受请求").hasSize(1);
    }

    @Test
    @Stories(@Story("FEAT-036.out.return-path-filepart: 回程 FilePart 跳过"))
    @DisplayName("Feat-036 回程 artifacts 中的 FilePart 被跳过不解析（矩阵 #26）")
    void feat036ReturnPathFilePartSkipped() throws Exception {
        // W：下游回程 artifacts 携带 raw/url 类 Part（GatewayStub 默认回填含 text artifact；
        //     落码后由 fixture 扩展回填 FilePart——见 A2APartFixtures.GatewayStub 待扩展点）。
        post(a2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND, List.of(
                textPart("回程 FilePart 探针"))));
        // T（L2 §3.4）：回程 FilePart 跳过——任务仍达 COMPLETED（不因无法解析回程
        //     文件而失败），且回程文件字节不进入上游上下文。
        HttpResponse<String> response = post(a2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND, List.of(
                textPart("回程 FilePart 探针"))));
        String taskId = A2APartFixtures.resultId(response.body());
        var task = client(ECHO).getTask(taskId);
        assertThat(task).as("回程含 FilePart 时任务应正常完成").isNotNull();
        assertThat(task.status().state())
                .as("FilePart 跳过不应阻断完成态")
                .isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
    }

    // ------------------------------------------------------------------
    // SEC 组（安全基线，特性档 §6 安全要求）
    // ------------------------------------------------------------------

    @Test
    @Stories(@Story("FEAT-036.sec.raw-not-into-context: raw 字节不进 LLM 上下文"))
    @DisplayName("Feat-036 raw 文件原始字节不进入 LLM 上下文（矩阵 #31）")
    void feat036RawBytesNotIntoLlmContext() throws Exception {
        // W：拓扑 B（edp-agent 宿主，LLM 规划在链路上）：raw Part 携带唯一 marker
        //     （deterministicBytes 头部嵌入 ASCII marker），触发委托出站
        //     （意图落在场景 scope.allowed「文件审查」内）；marker 若进上下文，
        //     将在下游 wire 的 prompt/text 面出现。
        String marker = "MARKER-DO-NOT-LEAK-" + System.nanoTime();
        byte[] payload = deterministicBytes(2048, marker);
        A2APartFixtures.postSlow(edpaA2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND, List.of(
                withMeta(rawPart(payload), "leak-probe.bin", "application/octet-stream"),
                textPart("文件审查：审查随附文件并出具结论"))));
        // T：raw 的 base64 载荷可作为 Part 数据面出站（R-RT-3），但 marker 不得以
        //     明文形式出现在任何 text 面成员中（raw 字节不进 LLM 上下文——框架侧
        //     不把文件内容注入 prompt；观测面为下游 wire 的 text 字段集）。
        //     反假绿（测试设计 §6.6 #31）：先断言 base64 载荷确实出现在出站 wire
        //     （证明文件进入链路），排除"文件没送到导致恒绿"的假绿。
        assertThat(gateway.totalRequests()).as("应产生出站请求").isGreaterThanOrEqualTo(1);
        assertThat(unescapeJson(gateway.acceptedBodies().get(0)))
                .as("raw base64 载荷应出现在出站 wire（文件确实进入链路，反假绿）")
                .contains(A2APartFixtures.base64(payload));
        for (String wire : gateway.acceptedBodies()) {
            assertThat(wire.contains("\"text\":\"" + marker))
                    .as("marker 不得出现在明文 text 面（raw 不进上下文）").isFalse();
        }
    }

    @Test
    @Stories(@Story("FEAT-036.sec.url-not-downloaded: runtime 不主动下载 url 资源"))
    @DisplayName("Feat-036 url Part 不被 runtime 下载（token 文件桩零请求，矩阵 #32）")
    void feat036UrlNotDownloadedByRuntime() throws Exception {
        // W：拓扑 B（edp-agent 宿主）：FileServerStub 发布 token 强制文件，url Part
        //     指向带 token 的下载 URL（token 仅测试/下游持有）；触发委托出站后核对
        //     文件桩访问记录（意图落在场景 scope.allowed「合同审查」内）。
        byte[] content = deterministicBytes(1024, "url-target");
        fileServer.publish("credit-report-2026Q3.pdf", content);
        String tokenedUrl = fileServer.url("credit-report-2026Q3.pdf");
        A2APartFixtures.postSlow(edpaA2aUrl(), jsonRpc(A2APartFixtures.METHOD_SEND, List.of(
                urlPart(tokenedUrl),
                textPart("合同审查：基于该合同 url 文件出具审查结论"))));
        // T（R-RT-2 / 安全基线）：runtime 不下载——文件桩零访问记录；url 引用原样
        //     出站（由下游/Workflow 凭 token 取件）。
        assertThat(fileServer.requests())
                .as("runtime 不得对 url 发起下载（桩零请求）")
                .isEmpty();
        assertThat(gateway.totalRequests()).as("url 应原样出站（委托给下游）")
                .isGreaterThanOrEqualTo(1);
        assertThat(unescapeJson(gateway.acceptedBodies().get(0))).as("url 引用原样在 wire").contains(tokenedUrl);
    }

    // ------------------------------------------------------------------
    // 深 E2E（§5.1 两条业务流）
    // ------------------------------------------------------------------

    @Test
    @Stories(@Story("FEAT-036.e2e.credit-url-flow: 信贷报告 url 模式端到端"))
    @DisplayName("Feat-036 信贷报告 url 模式端到端（矩阵 #1）")
    void feat036CreditReportUrlFlow() throws Exception {
        // W：拓扑 B（edp-agent 宿主）：FileServerStub 发布「信贷报告」→ url Part 入站
        //     → LLM 规划委托 rail 出站至 GatewayStub（Workflow 凭 token 取件分析）
        //     → 回填完成（意图落在场景 scope.allowed「贷后资料审查」内）。
        byte[] report = "credit report content v1".getBytes(StandardCharsets.UTF_8);
        fileServer.publish("credit-report.pdf", report);
        HttpResponse<String> response = A2APartFixtures.postSlow(edpaA2aUrl(), jsonRpc(
                A2APartFixtures.METHOD_SEND, List.of(
                        urlPart(fileServer.url("credit-report.pdf")),
                        textPart("贷后资料审查：基于该信贷报告 url 生成评估"))));
        // T：任务完成；url 全程引用传递（runtime 不下载——桩零访问）；下游收到
        //     url 引用与文本指令。
        String taskId = A2APartFixtures.resultId(response.body());
        var task = client(EDPA).getTask(taskId);
        assertThat(task).as("信贷报告 url 流应产生任务").isNotNull();
        assertThat(task.status().state())
                .as("url 模式任务应完成")
                .isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
        assertThat(fileServer.requests()).as("runtime 全程不下载（桩零访问）").isEmpty();
        assertThat(unescapeJson(gateway.acceptedBodies().get(0)))
                .as("下游收到 url 引用").contains("/files/credit-report.pdf");
    }

    @Test
    @Stories(@Story("FEAT-036.e2e.postloan-raw-flow: 贷后资料批量 raw 模式端到端"))
    @DisplayName("Feat-036 贷后资料批量 raw 模式端到端（矩阵 #2）")
    void feat036PostLoanRawFlow() throws Exception {
        // W：拓扑 B（edp-agent 宿主）：3 类贷后资料（pdf/xlsx/png）确定性字节 + 指令文本
        //     → raw Part 批量入站 → LLM 规划委托出站至下游 → 回填完成
        //     （意图落在场景 scope.allowed「贷后资料审查」内，与 mp #3 同款指令）。
        HttpResponse<String> response = A2APartFixtures.postSlow(edpaA2aUrl(), jsonRpc(
                A2APartFixtures.METHOD_SEND, List.of(
                        withMeta(rawPart(deterministicBytes(4 * 1024, "pl-pdf")),
                                "contract.pdf", "application/pdf"),
                        withMeta(rawPart(deterministicBytes(4 * 1024, "pl-xlsx")),
                                "repayment.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                        withMeta(rawPart(deterministicBytes(4 * 1024, "pl-png")),
                                "receipt.png", "image/png"),
                        textPart("批量核验贷后资料并出具结论"))));
        // T：任务完成；3 个 raw Part 以 raw 格式原样出站（不降级）；text 指令在前。
        String taskId = A2APartFixtures.resultId(response.body());
        var task = client(EDPA).getTask(taskId);
        assertThat(task).as("贷后资料批量流应产生任务").isNotNull();
        assertThat(task.status().state())
                .as("raw 模式任务应完成")
                .isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
        String wire = gateway.acceptedBodies().get(0);
        assertThat(unescapeJson(wire)).as("pdf raw 原样出站")
                .contains(A2APartFixtures.base64(deterministicBytes(4 * 1024, "pl-pdf")));
        assertThat(unescapeJson(wire)).as("xlsx raw 原样出站")
                .contains(A2APartFixtures.base64(deterministicBytes(4 * 1024, "pl-xlsx")));
        assertThat(unescapeJson(wire)).as("png raw 原样出站")
                .contains(A2APartFixtures.base64(deterministicBytes(4 * 1024, "pl-png")));
    }
}
