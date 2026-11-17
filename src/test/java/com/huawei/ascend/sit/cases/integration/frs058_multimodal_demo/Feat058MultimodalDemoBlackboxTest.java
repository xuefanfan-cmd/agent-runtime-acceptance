/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.FaqMode;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.MAX_RAW_BYTES;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.SseTurn;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.fillBytes;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.jsonRpcSend;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.jsonRpcTaskMethod;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.multipart;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.parseSse;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.postA2a;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.postUrl;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.rawPart;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.resultId;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.resultTaskTolerant;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.taskIdOf;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.textPart;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.wireContainsPart;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FRS-058 多模态能力展示智能体 Demo——multipart 入站 + A2A 出站 wire 面（Demo 装配面 SUT）。
 *
 * <p>SUT：multi-modal-FAQ-agent 0.1.2（faq-poc-agent，agent-solution@65efeecd 2026-11-10 交付，
 * 手机银行多模态问题定位 Agent：DeepAgent 基线 + custom-rest multipart 入口 + 视觉模型识图判类
 * + 纯文本提炼 + A2A 出站 FAQ（faq-workflow 远程工具）+ FaqPocSolutionRail 结构化方案卡片）。
 * FAQ 工作流以测试自有 FaqStub 替身（A2A JSONRPC 捕获 + matched/solutions 三形态/无匹配/503/
 * 失效图片可控应答）。</p>
 *
 * <p>观察面：HTTP 状态码、multipart 入口 SSE 响应（status/chunk/solution/input_required/error/
 * final）、JSON-RPC envelope、FAQ stub 出站 wire 快照、Task 状态机。断言不读 SUT 内部状态。
 * 场景矩阵见 docs/cases/FRS-058-multimodal-problem-locating-demo.md。</p>
 */
@Tag("integration")
@Tag("frs-058")
@Feature("FRS-058: 多模态能力展示智能体Demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Feat058MultimodalDemoBlackboxTest extends BaseManagedStackTest {

    private static final String SUT = "faq-poc-agent";

    /** C-04 深链补全基准（APP_BASE，测试注入值——断言深链 url 按该值补全）。 */
    private static final String APP_BASE_URL = "http://127.0.0.1:39001";

    private static Feat058WireSupport.FaqStub faq;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        // SUT 版本指纹：启动即输出坐标，防止跑旧产物全绿
        System.out.printf("[FRS-058 SUT 指纹] %s -> %s:%s:%s%n", SUT,
                config.getString("sut.agents." + SUT + ".group", "?"),
                config.getString("sut.agents." + SUT + ".artifact", "?"),
                config.getString("sut.agents." + SUT + ".version", "?"));
        try {
            faq = new Feat058WireSupport.FaqStub();
        } catch (IOException e) {
            throw new IllegalStateException("FRS-058 FAQ stub 启动失败", e);
        }
        String visionKey = firstNonBlank(System.getenv("VISION_API_KEY"), System.getenv("LLM_API_KEY"));
        // 依赖门禁：凭据缺失按 dependency-gated SKIPPED 收敛（@BeforeAll 内抛
        // TestAbortedException，JUnit 将整类标记为 SKIPPED，不计 ERROR/PASS），
        // 不以硬失败冒充执行结果
        if (visionKey == null || visionKey.isBlank()) {
            throw new org.opentest4j.TestAbortedException(
                    "FRS-058 dependency-gated: VISION_API_KEY/LLM_API_KEY 未注入（C-01 视觉模型凭据）");
        }
        return SutStack.builder(config)
                .streaming(false)
                .agent(SUT, a -> a
                        // C-03：FAQ A2A 目标地址（card 发现 + message/send 同源）与静态图补全基地址
                        .env("FAQ_BASE_URL", faq.a2aUrl())
                        // C-04：深链补全基准
                        .env("APP_BASE_URL", APP_BASE_URL)
                        // §8.1 超时注入面：faq-workflow 远程调用预算经 env 松散绑定收紧至 20s
                        // （runtime 默认 300s；FRS058-12 超时分支以 40s 桩延迟真实触发超时收敛，
                        // 其余用例 FAQ stub 应答为毫秒级，20s 预算不受影响）。
                        // 注意：env 对 indexed list 不与 application.yml 合并（gap-run1 取证：
                        // 仅注入 timeout-seconds 会产生 name 为空的不完整条目导致启动失败），
                        // 故经 env 完整定义 index-0 条目（name/url 与 yml 一致，指向同一 FAQ 桩；
                        // 即使 env 与 yml 拆成两个条目，同名注册按 name 覆盖，行为一致）
                        .env("OPENJIUWEN_SERVICE_A2A_REMOTEAGENTS_0_NAME", "faq-workflow")
                        .env("OPENJIUWEN_SERVICE_A2A_REMOTEAGENTS_0_URL", faq.a2aUrl())
                        .env("OPENJIUWEN_SERVICE_A2A_REMOTEAGENTS_0_TIMEOUTSECONDS", "20")
                        .env("OPENJIUWEN_SERVICE_A2A_REMOTEAGENTS_0_STREAMING", "false")
                        // C-01：视觉模型三件套（glm-5.3-flash@ark，OpenAI 兼容；凭据经环境变量，不落库）
                        .env("VISION_API_KEY", visionKey)
                        .env("VISION_BASE_URL", firstNonBlank(System.getenv("VISION_BASE_URL"),
                                "https://ark.cn-beijing.volces.com/api/coding/v3"))
                        .env("VISION_MODEL", "glm-5.3-flash")
                        .env("VISION_SSL_VERIFY", "false"));
    }

    @AfterAll
    static void tearDownStub() {
        if (faq != null) {
            faq.close();
        }
    }

    @BeforeEach
    void resetStub() {
        // FAQ stub wire 快照/计数/故障注入用例间复位（防串用例命中旧 wire）
        faq.reset();
    }

    private static String firstNonBlank(String left, String right) {
        return left != null && !left.isBlank() ? left : right;
    }

    /** multipart 南向入口 URL（每用例独立 conversation，隔离上下文）。 */
    private String multipartUrl() {
        return client(SUT).getBaseUrl() + "/v1/projects/p-frs058/conversations/conv-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private HttpResponse<String> upload(List<Feat058WireSupport.FileField> files,
            List<String[]> formFields) throws Exception {
        Map.Entry<String, byte[]> body = multipart(files, formFields);
        return postUrl(multipartUrl(), body.getKey(), body.getValue());
    }

    /** SUT 的 A2A 入口基址（JSONRPC，/a2a 由 postA2a 追加）。 */
    private String a2aUrl() {
        return client(SUT).getBaseUrl();
    }

    /** Task 树内 state 深取（status.state → state → 深收集文本，形态容错）。 */
    private static String taskStateOf(JsonNode task) {
        String state = task.path("status").path("state").asText("");
        if (state.isBlank()) {
            state = task.path("state").asText("");
        }
        if (state.isBlank()) {
            state = Feat058WireSupport.collectTaskText(task).trim();
        }
        return state.toUpperCase();
    }

    private static boolean isTerminalState(String state) {
        return state.contains("COMPLETED") || state.contains("INPUT_REQUIRED")
                || state.contains("CANCELED") || state.contains("FAILED");
    }

    /** GetTask 轮询至终态（超时返回最后一次查询结果）。 */
    private JsonNode awaitTerminalTask(String taskId, int timeoutSeconds)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        JsonNode task = Feat058WireSupport.JSON.createObjectNode();
        while (true) {
            HttpResponse<String> query = postA2a(a2aUrl(), jsonRpcTaskMethod("GetTask", taskId));
            if (query.statusCode() == 200) {
                task = resultTaskTolerant(query.body());
                if (isTerminalState(taskStateOf(task))) {
                    return task;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                return task;
            }
            Thread.sleep(5000);
        }
    }

    /** 同会话逐条发送文本续接，直至终态（最多 maxTurns 轮），返回最后一轮投影。 */
    private SseTurn continueUntilTerminal(String convUrl, List<String> messages, int maxTurns)
            throws IOException, InterruptedException {
        SseTurn last = null;
        int sent = 0;
        for (String message : messages) {
            if (sent >= maxTurns) {
                break;
            }
            Map.Entry<String, byte[]> body = multipart(List.of(),
                    List.<String[]>of(new String[]{"message", message}));
            HttpResponse<String> resp = postUrl(convUrl, body.getKey(), body.getValue());
            assertThat(resp.statusCode())
                    .as("同会话续接请求应被接受（HTTP 200）").isEqualTo(200);
            last = parseSse(resp.body());
            sent++;
            if (last.completed() || last.errorCode() != null) {
                break;
            }
        }
        return last;
    }

    // ------------------------------------------------------------------
    // FRS058-08 纯文本调用兼容（FR-09/AC-07，用例 6.3）
    // ------------------------------------------------------------------
    @Test
    @Order(1)
    @Tag("story-frs058-08")
    @Story("FRS-058.FRS058-08: 仅文字无截图——入口/Task/响应语义不变，无图片处理类报错")
    @DisplayName("FRS058-08 纯文本提问正常作答且无图片处理类报错")
    void frs05808TextOnlyCompatibility() throws Exception {
        HttpResponse<String> response = upload(List.of(),
                List.<String[]>of(new String[]{"message", "忘记登录密码怎么办"}));
        assertThat(response.statusCode())
                .as("纯文本请求应被入口接受（HTTP 200）").isEqualTo(200);
        SseTurn turn = parseSse(response.body());
        assertThat(turn.completed())
                .as("纯文本链路应到达 completed 终态（实际 final=%s error=%s/%s）",
                        turn.finalState(), turn.errorCode(), turn.errorMessage())
                .isTrue();
        assertThat(turn.fullText())
                .as("纯文本应获得非空作答（存量链路语义不变）").isNotBlank();
        assertThat(response.body())
                .as("响应不得出现图片处理类报错（协议层 -32602 / 业务层 EDP-FILE- / "
                        + "FILE_TYPE_UNSUPPORTED / FORMAT_UNSUPPORTED）")
                .doesNotContain("-32602")
                .doesNotContain("EDP-FILE-")
                .doesNotContain("FILE_TYPE_UNSUPPORTED")
                .doesNotContain("FORMAT_UNSUPPORTED");
    }

    // ------------------------------------------------------------------
    // FRS058-09 截图超限（FR-08/AC-06，用例 7.1，主门禁）
    // ------------------------------------------------------------------
    @Test
    @Order(2)
    @Tag("story-frs058-09")
    @Story("FRS-058.FRS058-09: 截图超 10MB 在协议层被拒且不出站（-32602 或等价参数错误）")
    @DisplayName("FRS058-09 超限截图显式拒绝、零出站、不静默成功")
    void frs05809OversizedScreenshotRejected() throws Exception {
        byte[] oversized = fillBytes(MAX_RAW_BYTES + 1, "feat058-oversized");
        HttpResponse<String> response = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", oversized)),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        // 2026-11-17 严格断言恢复（issue #717 修复验收 1）：agent-solution@1a29a2fa 起，
        // 截图校验失败经 FaqPocFileValidationAdvice 投影为结构化错误体
        // {"code":"FILE_TOO_LARGE","message":...,"retryable":false}——业务码必须上 wire，
        // 不再接受"任意 4xx 即算"的宽断言（该宽断言为 2026-11-10 缺陷期的过渡整改）
        assertThat(response.statusCode())
                .as("超限截图应被拒绝为 HTTP 4xx，实际 HTTP=" + response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(response.body())
                .as("超限截图错误表面必须含结构化业务码 FILE_TOO_LARGE（issue #717 修复后"
                        + "业务码不再丢失），body 前 400 字="
                        + response.body().substring(0, Math.min(400, response.body().length())))
                .contains("FILE_TOO_LARGE");
        assertThat(response.body())
                .as("错误体应为结构化 JSON（含 code 字段，非 Spring 默认错误体）").contains("\"code\"");
        assertThat(faq.totalRequests())
                .as("拒绝路径不得产生 FAQ 出站请求（拒于协议层，不带病转发）").isZero();

        // 子分支 2（用例 7.1：文件字段无法解析/结构不合法）：multipart 报文中途截断——
        // 合法 boundary 开头 + 文件 part 头完整、part 体未写完即截断（无收尾 boundary），
        // 文件字段无法完整解析，协议层应显式拒绝，不进入业务与识图链路
        // （不注入"完全无 boundary 结构"的报文：该形态会被降级为空表单而非解析失败，run2 取证）
        int wireBeforeMalformed = faq.totalRequests();
        String boundary = "feat058-truncated-" + UUID.randomUUID();
        java.io.ByteArrayOutputStream truncated = new java.io.ByteArrayOutputStream();
        try {
            truncated.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"screenshot\";"
                    + " filename=\"error-page.png\"\r\nContent-Type: image/png\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            truncated.write(anomalySampleBytes(), 0, anomalySampleBytes().length / 2);
            // 截断：无 part 收尾 CRLF，无终止 boundary
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        HttpResponse<String> malformed = postUrl(multipartUrl(),
                "multipart/form-data; boundary=" + boundary, truncated.toByteArray());
        assertThat(malformed.statusCode())
                .as("文件字段无法解析（part 中途截断）的 multipart 报文应被协议层显式拒绝"
                        + "（4xx/5xx 参数错误，不静默成功），实际 HTTP=" + malformed.statusCode())
                .isGreaterThanOrEqualTo(400);
        assertThat(faq.totalRequests())
                .as("报文解析失败路径不得产生 FAQ 出站请求").isEqualTo(wireBeforeMalformed);
    }

    // ------------------------------------------------------------------
    // FRS058-01 multipart 图文入站不丢图（FR-01/AC-01，用例 6.1，主门禁）
    // ------------------------------------------------------------------
    @Test
    @Order(3)
    @Tag("story-frs058-01")
    @Story("FRS-058.FRS058-01: multipart 提交截图+文字，截图完整进入 Agent 并被理解（不丢图）")
    @DisplayName("FRS058-01 multipart 截图+文字入站且 Agent 回答引用截图内唯一错误码")
    void frs05801MultipartImageTextAcceptedAndUnderstood() throws Exception {
        // 样张：程序生成异常页面（唯一错误码 TX-9527），与识图测试类同款锚点
        byte[] screenshot = anomalySampleBytes();
        HttpResponse<String> response = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", screenshot)),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(response.statusCode())
                .as("multipart 图文提交应被入口接受（HTTP 200）").isEqualTo(200);
        SseTurn turn = parseSse(response.body());
        assertThat(turn.completed())
                .as("图文链路应到达 completed 终态（不静默失败；实际 final=%s error=%s/%s）",
                        turn.finalState(), turn.errorCode(), turn.errorMessage())
                .isTrue();
        assertThat(turn.fullText())
                .as("Agent 回答应引用截图内唯一错误码 TX-9527（截图完整进入且被识图，"
                        + "FR-01 不丢图 + FR-02 识图；FAQ 方案卡片 url 中的 9527 不构成识图证据）")
                .contains("TX-9527");
    }

    // ------------------------------------------------------------------
    // FRS058-03 A2A 出站转发为纯文本问题（FR-05/AC-04，主门禁）
    // ------------------------------------------------------------------
    @Test
    @Order(4)
    @Tag("story-frs058-03")
    @Story("FRS-058.FRS058-03: 出站转发为纯文本问题——FAQ wire 含 text Part、不含截图图片数据")
    @DisplayName("FRS058-03 出站 wire 为纯文本问题且不携带截图 base64")
    void frs05803OutboundPureTextOnly() throws Exception {
        byte[] screenshot = anomalySampleBytes();
        HttpResponse<String> response = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", screenshot)),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(response.statusCode())
                .as("multipart 图文提交应被接受").isEqualTo(200);
        assertThat(parseSse(response.body()).completed())
                .as("图文链路应到达 completed 终态（提炼与 FAQ 委托完整收敛）").isTrue();
        assertThat(faq.totalRequests())
                .as("应产生 FAQ 出站委托请求").isGreaterThanOrEqualTo(1);
        String wire = String.join("\n", faq.acceptedBodies());
        assertThat(wireContainsPart(wire, "text"))
                .as("出站 wire 应含 text Part（纯文本问题）").isTrue();
        assertThat(wire)
                .as("FAQ 检索只依赖纯文本（FRS-058 第 2 节约束）：出站 wire 不得携带截图 base64")
                .doesNotContain(java.util.Base64.getEncoder().encodeToString(screenshot));
        assertThat(wire)
                .as("出站文本非空且与提交意图相关（识图判类 transfer_error + 文字诉求融合提炼）")
                .containsAnyOf("转账", "TX-9527", "错误码", "报错", "限额");
    }

    // ------------------------------------------------------------------
    // FRS058-10 文件类型不在白名单（FR-08/AC-06，用例 7.2）
    // ------------------------------------------------------------------
    @Test
    @Order(5)
    @Tag("story-frs058-10")
    @Story("FRS-058.FRS058-10: 非 png/jpg 类型被业务层白名单拒绝（FILE_TYPE_UNSUPPORTED 或等价），不静默转发")
    @DisplayName("FRS058-10 gif 类型被业务白名单拒绝且不出站")
    void frs05810FileTypeWhitelist() throws Exception {
        // GIF89a 魔数 + 填充：业务类型非 png/jpg（体积合法，命中业务白名单校验分支）
        byte[] gif = fillBytes(64 * 1024, "GIF89a-feat058");
        HttpResponse<String> response = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "animation.gif",
                        "image/gif", gif)),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        // 2026-11-17 严格断言恢复（issue #717 修复验收 1）：业务码 FILE_TYPE_UNSUPPORTED
        // 必须以结构化 code 字段上 wire，不再接受"任意 4xx 即算"的宽断言
        assertThat(response.statusCode())
                .as("非 png/jpg 类型应被拒绝为 HTTP 4xx，实际 HTTP=" + response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(response.body())
                .as("非 png/jpg 类型错误表面必须含结构化业务码 FILE_TYPE_UNSUPPORTED"
                        + "（issue #717 修复后业务码不再丢失），body 前 400 字="
                        + response.body().substring(0, Math.min(400, response.body().length())))
                .contains("FILE_TYPE_UNSUPPORTED");
        assertThat(response.body())
                .as("错误体应为结构化 JSON（含 code 字段，非 Spring 默认错误体）").contains("\"code\"");
        assertThat(String.join("\n", faq.acceptedBodies()))
                .as("白名单拒绝路径不得把该文件转发给 FAQ（不带病出站）")
                .doesNotContain(java.util.Base64.getEncoder().encodeToString(gif));
    }

    // ------------------------------------------------------------------
    // FRS058-04 FAQ 三形态方案组织图文+url 回复（FR-06/FR-07/AC-05，用例 6.4）
    // ------------------------------------------------------------------
    @Test
    @Order(6)
    @Tag("story-frs058-04")
    @Story("FRS-058.FRS058-04: FAQ 返回文本/url/图片三形态方案，最终回复三类齐全、不错位")
    @DisplayName("FRS058-04 FAQ 三形态方案回流后 solution 卡片三类齐全且 url 按 C-03/C-04 补全")
    void frs05804FaqThreeFormSolution() throws Exception {
        faq.setMode(FaqMode.SOLUTION);
        HttpResponse<String> response = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(response.statusCode())
                .as("图文提交应被接受").isEqualTo(200);
        SseTurn turn = parseSse(response.body());
        assertThat(turn.completed()).as("链路应到达 completed 终态").isTrue();
        assertThat(turn.solution())
                .as("应产生 solution 结构化方案卡片帧（FaqPocSolutionRail 流水线⑤）").isNotNull();
        List<Map<String, Object>> items = turn.solutionItems();
        assertThat(items)
                .as("三形态方案齐全（文本步骤/web url/图片）").hasSizeGreaterThanOrEqualTo(3);
        assertThat(items)
                .as("文本形态不错位（type=text 承载处理步骤）")
                .anyMatch(item -> "text".equals(item.get("type"))
                        && String.valueOf(item.get("content")).contains("安全中心"));
        assertThat(items)
                .as("web url 形态原样透传（外部完整 url 不改写）")
                .anyMatch(item -> "https://bank.example.com/help/9527".equals(item.get("url")));
        assertThat(items)
                .as("图片形态按 C-03 补全（相对 /static/ url 以 FAQ_BASE_URL 为基准）")
                .anyMatch(item -> "image".equals(item.get("type"))
                        && String.valueOf(item.get("url")).startsWith(faq.a2aUrl())
                        && String.valueOf(item.get("url")).contains("9527.png"));
        assertThat(items)
                .as("深链按 C-04 补全（相对 /#/ url 以 APP_BASE_URL 为基准）")
                .anyMatch(item -> String.valueOf(item.get("url")).startsWith(APP_BASE_URL)
                        && String.valueOf(item.get("url")).contains("/#/page/"));
        assertThat(turn.solutionAnswer())
                .as("方案主答复非空").isNotBlank();
    }

    // ------------------------------------------------------------------
    // FRS058-12 FAQ 无匹配 vs FAQ 不可用（FR-08/AC-06，用例 7.4）
    // ------------------------------------------------------------------
    @Test
    @Order(7)
    @Tag("story-frs058-12")
    @Story("FRS-058.FRS058-12: FAQ 无匹配与 FAQ 调用失败两类结果可区分，均不伪造方案")
    @DisplayName("FRS058-12 FAQ 无匹配提示与不可用提示两类可区分且不伪造方案")
    void frs05812FaqNoMatchVsUnavailable() throws Exception {
        // 分支 1：FAQ 无匹配（stub 应答 matched=false）
        faq.setMode(FaqMode.NO_MATCH);
        HttpResponse<String> noMatch = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(noMatch.statusCode())
                .as("FAQ 无匹配时请求仍应正常受理（HTTP 200）").isEqualTo(200);
        SseTurn noMatchTurn = parseSse(noMatch.body());
        assertThat(noMatchTurn.completed()).as("无匹配分支应正常收敛").isTrue();
        String noMatchAnswer = noMatchTurn.fullText();
        assertThat(noMatchAnswer).as("无匹配应给出明确提示")
                .containsAnyOf("暂未找到", "无匹配", "未找到", "未检索", "没有找到", "转人工");
        assertThat(noMatchAnswer)
                .as("无匹配时不得伪造方案（不出现具体操作步骤）")
                .doesNotContain("安全中心");

        // 分支 2：FAQ 调用失败（stub 连续 503，覆盖引擎重试预算）
        faq.reset();
        faq.failNext(30);
        HttpResponse<String> failed = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(failed.statusCode()).isEqualTo(200);
        SseTurn failedTurn = parseSse(failed.body());
        assertThat(failedTurn.completed()).as("FAQ 失败分支应正常收敛（不悬挂）").isTrue();
        String failedAnswer = failedTurn.fullText();
        assertThat(failedAnswer).as("FAQ 不可用应给出失败/可重试类明确提示")
                .containsAnyOf("不可用", "失败", "稍后", "重试", "暂时", "异常");
        assertThat(failedAnswer)
                .as("FAQ 失败时不得伪造方案")
                .doesNotContain("安全中心");
        // 两类结果可区分（rail 规范后缀互斥）：FaqPocSolutionRail 对两类分支收敛出
        // 各自固定的收尾话术——无匹配 NO_MATCH="暂未找到对应方案，请换个问法或转人工"、
        // 调用失败 UNAVAILABLE="FAQ 服务暂时不可用，请稍后重试"（SUT FaqPocSolutionRail.java:47-48）。
        // 判定必须锚定规范后缀本身而非泛化词集：模型复述样张内嵌错误文案
        // （如"转账渠道连接失败，请稍后重试"）会与词集碰撞（run14 实证），但
        // rail 后缀只会由对应分支追加——互斥性以后缀存在性判定。
        assertThat(noMatchAnswer)
                .as("无匹配分支应以 rail NO_MATCH 规范后缀收敛（锚定 FaqPocSolutionRail.java:47）")
                .contains("暂未找到对应方案，请换个问法或转人工");
        assertThat(noMatchAnswer)
                .as("无匹配分支不得携带 rail UNAVAILABLE 规范后缀（两类后缀互斥）")
                .doesNotContain("FAQ 服务暂时不可用，请稍后重试");
        assertThat(failedAnswer)
                .as("FAQ 失败分支应以 rail UNAVAILABLE 规范后缀收敛（锚定 FaqPocSolutionRail.java:48）")
                .contains("FAQ 服务暂时不可用，请稍后重试");
        assertThat(failedAnswer)
                .as("FAQ 失败分支不得携带 rail NO_MATCH 规范后缀（两类后缀互斥）")
                .doesNotContain("暂未找到对应方案，请换个问法或转人工");

        // 分支 3（§8.1 FAQ 调用真实超时）：stub 应答前延迟 40s > 注入的远程调用预算 20s
        // （buildStack 经 env 收紧 openjiuwen.service.a2a.remote-agents[0].timeout-seconds=20），
        // 由客户端侧超时收敛而非 503 故障——与分支 2 注入路径不同
        faq.reset();
        faq.setDelayMillis(40_000);
        HttpResponse<String> timedOut = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(timedOut.statusCode())
                .as("FAQ 超时场景请求仍应被入口受理（HTTP 200）").isEqualTo(200);
        SseTurn timeoutTurn = parseSse(timedOut.body());
        assertThat(timeoutTurn.completed())
                .as("FAQ 调用超时应按远程调用既有失败机制收敛（Task 终态，不悬挂）").isTrue();
        assertThat(timeoutTurn.fullText())
                .as("FAQ 超时应给出可重试类明确提示（不静默、不伪造方案）")
                .containsAnyOf("不可用", "失败", "稍后", "重试", "暂时", "异常");
        assertThat(timeoutTurn.fullText())
                .as("FAQ 超时不得伪造方案").doesNotContain("安全中心");
        assertThat(faq.totalRequests())
                .as("超时注入路径应真实到达 FAQ（请求已被桩接收后悬挂至客户端超时）")
                .isGreaterThanOrEqualTo(1);
        faq.reset();
    }

    // ------------------------------------------------------------------
    // FRS058-13 FAQ 图片不可访问降级（FR-08/AC-06，用例 7.5）
    // ------------------------------------------------------------------
    @Test
    @Order(8)
    @Tag("story-frs058-13")
    @Story("FRS-058.FRS058-13: FAQ 图片失效时保留文本+url 方案并明示图片缺失，不阻断整体答复")
    @DisplayName("FRS058-13 FAQ 图片不可访问时文本+url 保留且图片缺失可见")
    void frs05813FaqDeadImageDegradation() throws Exception {
        faq.setMode(FaqMode.DEAD_IMAGE);
        HttpResponse<String> response = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(response.statusCode())
                .as("图片失效场景整体答复不阻断（HTTP 200）").isEqualTo(200);
        SseTurn turn = parseSse(response.body());
        assertThat(turn.completed())
                .as("整体答复不因图片失效而失败（终态 completed）").isTrue();
        List<Map<String, Object>> items = turn.solutionItems();
        assertThat(items)
                .as("文本方案保留").anyMatch(item -> "text".equals(item.get("type"))
                        && String.valueOf(item.get("content")).contains("安全中心"));
        assertThat(items)
                .as("url 方案保留").anyMatch(item -> String.valueOf(item.get("url"))
                        .contains("/faq-help"));
        assertThat(items)
                .as("图片引用未被静默丢弃（失效图片连同占位/提示原样透传，红线：不裁剪 FAQ 返回内容）")
                .anyMatch(item -> "image".equals(item.get("type"))
                        && String.valueOf(item.get("url")).contains("missing-9527.png")
                        && String.valueOf(item.get("note")).contains("加载失败"));
    }

    // ------------------------------------------------------------------
    // FRS058-05 信息不足追问后同会话续接（FR-03/AC-03，用例 6.2）
    // ------------------------------------------------------------------
    @Test
    @Order(9)
    @Tag("story-frs058-05")
    @Story("FRS-058.FRS058-05: 信息不足先追问（澄清提问），同会话补充后续接完成排障，不要求重发截图")
    @DisplayName("FRS058-05 信息不足先澄清提问、同会话补充后完成排障")
    void frs05805ClarifyAndContinueSameConversation() throws Exception {
        String convUrl = client(SUT).getBaseUrl() + "/v1/projects/p-frs058/conversations/conv-"
                + UUID.randomUUID().toString().substring(0, 8);
        // 第一轮：无法唯一判定问题的截图 + 笼统文字
        Map.Entry<String, byte[]> first = multipart(
                List.of(new Feat058WireSupport.FileField("screenshot", "vague.png",
                        "image/png", vagueSampleBytes())),
                List.<String[]>of(new String[]{"message", "用不了"}));
        HttpResponse<String> firstResp = postUrl(convUrl, first.getKey(), first.getValue());
        assertThat(firstResp.statusCode())
                .as("第一轮图文提交应被接受").isEqualTo(200);
        SseTurn firstTurn = parseSse(firstResp.body());
        assertThat(firstTurn.inputRequired())
                .as("信息不足应发起澄清追问（input_required 帧，回复为澄清提问）").isTrue();
        assertThat(firstTurn.inputRequiredQuestion())
                .as("澄清提问话术非空").isNotBlank();

        // 第二轮：同会话补充答复（不重发截图），续接至终态并给出方案
        SseTurn last = continueUntilTerminal(convUrl,
                List.of("补充：转账页面提示错误码 TX-9527，转账渠道连接失败"), 2);
        assertThat(last).as("补充后应有续接答复").isNotNull();
        assertThat(last.completed())
                .as("补充后应续接完成排障（终态 completed）").isTrue();
        assertThat(last.fullText())
                .as("续接作答非空且给出方案（FAQ 命中，安全中心方案卡片可见）")
                .contains("安全中心");
        assertThat(last.fullText())
                .as("续接作答不得要求用户重新提交截图")
                .doesNotContain("重新上传")
                .doesNotContain("重新发送截图")
                .doesNotContain("重新提交截图");
    }

    // ------------------------------------------------------------------
    // FRS058-06 多轮追问仍信息不足显式收敛（用例 7.6）
    // ------------------------------------------------------------------
    @Test
    @Order(10)
    @Tag("story-frs058-06")
    @Story("FRS-058.FRS058-06: 多轮补充仍无法判定时给出明确的无法定位结论与建议，不伪造方案不无限追问")
    @DisplayName("FRS058-06 多轮追问仍信息不足显式收敛")
    void frs05806MultiRoundInsufficientExplicitClose() throws Exception {
        faq.setMode(FaqMode.NO_MATCH);
        String convUrl = client(SUT).getBaseUrl() + "/v1/projects/p-frs058/conversations/conv-"
                + UUID.randomUUID().toString().substring(0, 8);
        // 首轮：模糊截图 + 笼统描述（用例 7.6 前置），后两轮纯文本笼统补充（每轮同会话）
        Map.Entry<String, byte[]> first = multipart(
                List.of(new Feat058WireSupport.FileField("screenshot", "vague.png",
                        "image/png", vagueSampleBytes())),
                List.<String[]>of(new String[]{"message", "用不了"}));
        HttpResponse<String> firstResp = postUrl(convUrl, first.getKey(), first.getValue());
        assertThat(firstResp.statusCode()).as("首轮图文请求应被接受").isEqualTo(200);
        SseTurn firstTurn = parseSse(firstResp.body());
        assertThat(firstTurn.inputRequired()).as("信息不足应先澄清追问").isTrue();

        SseTurn last = continueUntilTerminal(convUrl,
                List.of("还是用不了", "就是用不了，没有更多信息了"), 3);
        assertThat(last).as("多轮补充后应有收敛答复").isNotNull();
        assertThat(last.completed())
                .as("多轮仍信息不足应显式收敛（Task 终态而非持续挂起）").isTrue();
        assertThat(last.fullText())
                .as("收敛话术应含无法定位/建议更换提问方式/转人工类结论")
                .containsAnyOf("无法", "不能", "建议", "转人工", "更换", "补充", "报错码", "95588", "暂未");
        assertThat(last.fullText())
                .as("收敛时不得伪造方案（不出现具体操作步骤）")
                .doesNotContain("安全中心");
    }

    // ------------------------------------------------------------------
    // FRS058-02 识图判类与纯文本提炼（FR-02/FR-04/AC-02，主门禁）——SUT 黑盒面
    // （2026-11-12 整改：识图断言面从 multi-modal-tools-demo 进程内驱动收敛到
    // 本 SUT 端到端链路——SUT 识图为自有实现 MultimodalInputRail，不经 demo 链；
    // demo 链自身的 wire/工具面断言归还 FEAT-032 测试域）
    // ------------------------------------------------------------------
    @Test
    @Order(16)
    @Tag("story-frs058-02")
    @Story("FRS-058.FRS058-02: 识图判类与纯文本提炼——真实视觉模型读样张错误码并产出纯文本问题")
    @DisplayName("FRS058-02 SUT 端到端识图判类并产出纯文本提炼与 FAQ 委托")
    void frs05802VisionReadAndDistillPureTextQuestion() throws Exception {
        byte[] screenshot = anomalySampleBytes();
        HttpResponse<String> response = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", screenshot)),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(response.statusCode())
                .as("multipart 图文提交应被入口接受（HTTP 200）").isEqualTo(200);
        SseTurn turn = parseSse(response.body());
        assertThat(turn.completed())
                .as("识图链路应到达 completed 终态（实际 final=%s error=%s/%s）",
                        turn.finalState(), turn.errorCode(), turn.errorMessage())
                .isTrue();
        String answer = turn.fullText();
        assertThat(answer)
                .as("最终回复应引用样张内唯一错误码 TX-9527（证明识图真实发生，非幻觉/非空答）")
                .contains("TX-9527");
        assertThat(answer)
                .as("提炼结果应为纯文本（FR-04），不含图片二进制/base64 数据")
                .doesNotContain("base64")
                .doesNotContain("data:image");
        assertThat(faq.totalRequests())
                .as("识图判类后应以纯文本问题委托 FAQ（FR-04 提炼产物可用）")
                .isGreaterThanOrEqualTo(1);
        String wire = String.join("\n", faq.acceptedBodies());
        assertThat(wire)
                .as("出站提炼问题应与样张异常一致（判类 transfer_error 域）")
                .containsAnyOf("转账", "TX-9527", "错误码", "报错");
    }

    // ------------------------------------------------------------------
    // FRS058-07 追问挂起期间用户取消（FRS-058 第 8.1 节，FEAT-008 取消传播）
    // 【暂缓执行】SUT 缺陷挂起（issues/draft-frs058-cancel-input-required-not-propagated.md，
    // P1）：A2aJsonRpcController 未分发 CancelTask（-32601），挂起终态写回层亦缺失——
    // 缺陷修复（新增 CancelTask 分支 + 挂起任务直写 TaskStore CANCELED）后移除 @Disabled
    // 恢复执行，并回归 FRS058-05（挂起-续接不受影响）。
    // ------------------------------------------------------------------
    @Test
    // 2026-11-17 run3 复跑实证：行为与登记时一致（CancelTask 仍返回 -32601 信封掩码）。
    // 上游处置（agent-runtime-java@c977cb18，refs agent-solution issue #642）：按当前设计
    // 范围，公开 JSON-RPC 入口保持 CancelTask 不可用——缺陷以"设计范围排除"口径关闭，
    // 非代码缺陷待修。FRS-058 §8.1"取消传播"与该设计决策的口径冲突需特性文档侧裁决；
    // 裁决前本用例维持 @Disabled（非环境跳过），裁决后按新口径整改用例或恢复执行。
    @Disabled("SUT 缺陷挂起（issues/draft-frs058-cancel-input-required-not-propagated.md）："
            + "A2aJsonRpcController 未分发 CancelTask（-32601）+ 挂起终态写回缺失。"
            + "2026-11-17 上游按设计范围排除处置（agent-runtime-java@c977cb18 refs #642），"
            + "特性文档裁决前维持禁用")
    @Order(11)
    @Tag("story-frs058-07")
    @Story("FRS-058.FRS058-07: 追问挂起期间用户取消——Task 进入 CANCELED，不再续接")
    @DisplayName("FRS058-07 挂起任务取消后收敛为 CANCELED")
    void frs05807CancelDuringSuspension() throws Exception {
        // 固定会话（取消后"续接不再推进"断言需复用同一 conversation）
        String convUrl = client(SUT).getBaseUrl() + "/v1/projects/p-frs058/conversations/conv-"
                + UUID.randomUUID().toString().substring(0, 8);
        // 非流式提交含截图的模糊请求（stream=false 返回 Task 信封），等待 ask_user 挂起
        Map.Entry<String, byte[]> body = multipart(
                List.of(new Feat058WireSupport.FileField("screenshot", "vague.png",
                        "image/png", vagueSampleBytes())),
                List.<String[]>of(new String[]{"message", "用不了"}, new String[]{"stream", "false"}));
        HttpResponse<String> sent = postUrl(convUrl, body.getKey(), body.getValue());
        assertThat(sent.statusCode()).as("非流式提交应被受理").isEqualTo(200);
        String taskId = taskIdOf(sent.body());
        assertThat(taskId).as("非流式信封应返回 task_id").isNotBlank();
        assertThat(sent.body().toUpperCase())
                .as("挂起信封应反映 INPUT_REQUIRED 态").contains("INPUT_REQUIRED");

        HttpResponse<String> cancel = postA2a(a2aUrl(), jsonRpcTaskMethod("CancelTask", taskId));
        assertThat(cancel.statusCode()).as("CancelTask 应被受理").isEqualTo(200);
        // JSON-RPC 错误信封也以 HTTP 200 返回——受理判定必须下探到信封本身，
        // 否则"Method not found: CancelTask"（SUT 未实现 tasks/cancel，探针实证
        // -32601）会被 HTTP 200 掩盖，直到后续状态断言才间接暴露
        assertThat(cancel.body())
                .as("CancelTask JSON-RPC 信封不得携带 error（方法必须真实受理，非 -32601 掩码）")
                .doesNotContain("\"error\"");

        HttpResponse<String> query = postA2a(a2aUrl(), jsonRpcTaskMethod("GetTask", taskId));
        assertThat(query.statusCode()).as("GetTask 应可查询").isEqualTo(200);
        JsonNode task = resultTaskTolerant(query.body());
        String state = taskStateOf(task);
        assertThat(state)
                .as("取消后 Task 应收敛为 CANCELED（既有 Task 取消机制），实际=" + state)
                .contains("CANCEL");

        // 后续续接不再推进（矩阵期望第 2 条）：取消后向原会话补发续接消息，
        // 原 Task 不得被复活推进——GetTask 复核仍收敛为 CANCELED（无残留挂起态）。
        // 续接消息自身的新建/拒绝行为不属于本矩阵断言面，此处不断言其内容
        Map.Entry<String, byte[]> followUp = multipart(List.of(),
                List.<String[]>of(new String[]{"message", "补充：转账页面提示错误码 TX-9527"}));
        HttpResponse<String> followUpResp = postUrl(convUrl, followUp.getKey(), followUp.getValue());
        assertThat(followUpResp.statusCode())
                .as("取消后的续接请求应被入口受理（HTTP 200，受理行为本身不改变取消语义）")
                .isEqualTo(200);
        HttpResponse<String> queryAfterFollowUp = postA2a(a2aUrl(),
                jsonRpcTaskMethod("GetTask", taskId));
        assertThat(queryAfterFollowUp.statusCode()).as("续接后 GetTask 应可查询").isEqualTo(200);
        String stateAfterFollowUp = taskStateOf(resultTaskTolerant(queryAfterFollowUp.body()));
        assertThat(stateAfterFollowUp)
                .as("续接消息后原 Task 应仍保持 CANCELED（后续续接不再推进），实际="
                        + stateAfterFollowUp)
                .contains("CANCEL");
    }

    // ------------------------------------------------------------------
    // FRS058-15 多模态处理过程可观测（FR-11/AC-09，DFX-001）
    // ------------------------------------------------------------------
    @Test
    @Order(12)
    @Tag("story-frs058-15")
    @Story("FRS-058.FRS058-15: 处理过程轨迹可查询——status 阶段留痕 + Task 留痕覆盖输入与回复（DFX-001 复用）")
    @DisplayName("FRS058-15 Task 轨迹留痕可查询")
    void frs05815TrajectoryObservable() throws Exception {
        // (1) multipart SSE 全链路：status 帧按阶段留痕（设计矩阵"轨迹覆盖各关键步骤"）——
        // 接收解析（received）、识图判类（vision，附截图请求映射）、FAQ 转发（stub 出站
        // wire）、回复组织（solution 卡片/chunk 答复）
        HttpResponse<String> streamed = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(streamed.statusCode())
                .as("全链路 multipart 提交应被接受").isEqualTo(200);
        SseTurn turn = parseSse(streamed.body());
        assertThat(turn.completed()).as("全链路应到达 completed 终态").isTrue();
        assertThat(turn.statusStages())
                .as("status 轨迹应覆盖接收解析阶段（received，stage 字段留痕），实际阶段="
                        + turn.statusStages())
                .contains("received");
        assertThat(turn.statusStages())
                .as("status 轨迹应覆盖识图判类阶段（vision，附截图请求映射），实际阶段="
                        + turn.statusStages())
                .contains("vision");
        assertThat(turn.solution() != null || !turn.fullText().isBlank())
                .as("回复组织步骤留痕（solution 方案卡片或 chunk 答复非空）").isTrue();
        assertThat(faq.totalRequests())
                .as("FAQ 转发步骤在出站 wire 可观测").isGreaterThanOrEqualTo(1);

        // (2) 按 Task 查询轨迹（A2A 入口，矩阵步骤 2）：Task history/artifacts 留痕——
        // history 恒空为已登记实现缺陷（issues/draft-frs058-task-history-empty-dfx.md），
        // 断言保留、失败归因该 issue，不以近似行为冒充通过
        HttpResponse<String> sent = postA2a(a2aUrl(), jsonRpcSend(List.of(
                rawPart(anomalySampleBytes(), "error-page.png", "image/png"),
                textPart("转账时报错怎么办"))));
        assertThat(sent.statusCode()).as("A2A SendMessage 应被受理").isEqualTo(200);
        JsonNode task = resultTaskTolerant(sent.body());
        String taskId = task.path("id").asText("");
        if (taskId.isBlank()) {
            taskId = resultId(sent.body());
        }
        String state = taskStateOf(task);
        if (taskId != null && !taskId.isBlank() && !isTerminalState(state)) {
            // SendMessage 受理即返回（WORKING）——终态前轮询 GetTask 再取轨迹
            task = awaitTerminalTask(taskId, 360);
            state = taskStateOf(task);
        }
        assertThat(state)
                .as("Task 应到达终态（COMPLETED/FAILED 等，非空），实际=" + state).isNotBlank();
        assertThat(task.path("history").size())
                .as("Task history 应留痕处理过程（至少含用户输入与 Agent 回复）")
                .isGreaterThanOrEqualTo(1);
        assertThat(task.path("artifacts").size())
                .as("Task artifacts 应承载回复组织产物").isGreaterThanOrEqualTo(1);
        // wire 面：FAQ stub 侧出站留痕（转发步骤可观测）
        assertThat(faq.totalRequests())
                .as("FAQ 转发步骤在出站 wire 可观测").isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // FRS058-14 装配配置化可复现（FR-10/AC-08，附录 A）
    // ------------------------------------------------------------------
    @Test
    @Order(13)
    @Tag("story-frs058-14")
    @Story("FRS-058.FRS058-14: C-01/C-02/C-03/C-04 装配项以配置提供，干净环境可复现部署")
    @DisplayName("FRS058-14 装配项均以配置提供（入口/FAQ 地址/视觉模型经配置注入生效）")
    void frs05814AssemblyConfiguration() throws Exception {
        // C-02 multipart 入口：custom-rest query-path 以模块 application.yml 配置提供，
        // 本用例与全类其他用例均经该入口提交成功——此处以最小纯文本请求复核入口可用。
        HttpResponse<String> entry = upload(List.of(),
                List.<String[]>of(new String[]{"message", "忘记登录密码怎么办"}));
        assertThat(entry.statusCode())
                .as("C-02：multipart 南向入口经配置 query-path 可用").isEqualTo(200);
        assertThat(parseSse(entry.body()).completed())
                .as("C-02：纯文本入口链路正常收敛").isTrue();

        // C-01/C-03/C-04：全链路一次——视觉模型、FAQ 目标地址、深链基准均经环境变量注入
        faq.setMode(FaqMode.SOLUTION);
        HttpResponse<String> full = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(full.statusCode()).isEqualTo(200);
        SseTurn turn = parseSse(full.body());
        assertThat(turn.completed()).as("全链路应正常收敛").isTrue();
        assertThat(faq.totalRequests())
                .as("C-03：FAQ_BASE_URL 环境变量注入的出站目标可达").isGreaterThanOrEqualTo(1);
        assertThat(turn.solutionItems())
                .as("C-03：静态图相对 url 按 FAQ_BASE_URL 注入值补全")
                .anyMatch(item -> String.valueOf(item.get("url")).startsWith(faq.a2aUrl())
                        && String.valueOf(item.get("url")).contains("9527.png"));
        assertThat(turn.fullText())
                .as("C-01：VISION_MODEL 注入的视觉模型完成识图（回答引用样张错误码 TX-9527，"
                        + "FAQ 方案卡片 url 中的 9527 不构成识图证据）")
                .contains("TX-9527");
        assertThat(turn.solutionItems())
                .as("C-04：深链相对 url 按 APP_BASE_URL 注入值补全")
                .anyMatch(item -> String.valueOf(item.get("url")).startsWith(APP_BASE_URL)
                        && String.valueOf(item.get("url")).contains("/#/page/"));
        io.qameta.allure.Allure.parameter("C-01 视觉模型",
                "VISION_MODEL=glm-5.3-flash + VISION_BASE_URL/VISION_API_KEY（环境变量注入）");
        io.qameta.allure.Allure.parameter("C-02 multipart 入口",
                "openjiuwen.service.custom-rest.query-path=/v1/projects/{project_id}/conversations/{conversation_id}（模块 application.yml）");
        io.qameta.allure.Allure.parameter("C-03 FAQ 地址", "FAQ_BASE_URL=" + faq.a2aUrl());
        io.qameta.allure.Allure.parameter("C-04 APP_BASE", "APP_BASE_URL=" + APP_BASE_URL);
    }

    // ------------------------------------------------------------------
    // FRS058-16 追问答复补充新截图纳入识图续接（FR-03/AC-03，§5.2 分支"追问答复补充图片"）
    // ------------------------------------------------------------------
    @Test
    @Order(14)
    @Tag("story-frs058-16")
    @Story("FRS-058.FRS058-16: 追问答复中附带新截图——新截图作为新增图片 Part 纳入识图，"
            + "同一 Task 合并原截图继续判定并完成排障")
    @DisplayName("FRS058-16 追问答复补充新截图后同会话续接且新图内容进入出站提炼")
    void frs05816ClarifySupplementWithNewScreenshot() throws Exception {
        faq.setMode(FaqMode.SOLUTION);
        String convUrl = client(SUT).getBaseUrl() + "/v1/projects/p-frs058/conversations/conv-"
                + UUID.randomUUID().toString().substring(0, 8);
        // 第一轮：模糊截图 + 笼统文字 → 澄清追问挂起
        Map.Entry<String, byte[]> first = multipart(
                List.of(new Feat058WireSupport.FileField("screenshot", "vague.png",
                        "image/png", vagueSampleBytes())),
                List.<String[]>of(new String[]{"message", "用不了"}));
        HttpResponse<String> firstResp = postUrl(convUrl, first.getKey(), first.getValue());
        assertThat(firstResp.statusCode()).as("第一轮图文提交应被接受").isEqualTo(200);
        assertThat(parseSse(firstResp.body()).inputRequired())
                .as("前置：信息不足应先澄清追问挂起").isTrue();

        // 第二轮：同一会话补充答复【携带新截图】（TX-9527 异常样张，不重发原模糊截图）
        Map.Entry<String, byte[]> second = multipart(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "补充：新截图如上，请结合判断"}));
        HttpResponse<String> secondResp = postUrl(convUrl, second.getKey(), second.getValue());
        assertThat(secondResp.statusCode())
                .as("追问答复携带新截图应被同一会话接受（HTTP 200）").isEqualTo(200);
        SseTurn last = parseSse(secondResp.body());
        if (!last.completed()) {
            last = continueUntilTerminal(convUrl, List.of("错误码就在新截图里"), 2);
        }
        assertThat(last).as("补充新截图后应有续接答复").isNotNull();
        assertThat(last.completed())
                .as("新截图补充后应续接完成排障（终态 completed）").isTrue();
        // 新截图被纳入识图与提炼：出站 FAQ 检索问题含新样张唯一锚点 TX-9527
        // （原模糊样张无任何可判定信息，TX-9527 只能来自追问答复中的新截图）
        String wire = String.join("\n", faq.acceptedBodies());
        assertThat(wire)
                .as("出站提炼问题应包含追问答复新截图的唯一错误码 TX-9527"
                        + "（新截图作为新增图片 Part 纳入识图，FR-03 §5.2 分支）")
                .contains("TX-9527");
        assertThat(last.fullText())
                .as("续接作答非空且给出方案（FAQ 命中，安全中心方案卡片可见）")
                .contains("安全中心");
        assertThat(last.fullText())
                .as("续接不得要求用户重新提交截图")
                .doesNotContain("重新上传")
                .doesNotContain("重新发送截图")
                .doesNotContain("重新提交截图");
    }

    // ------------------------------------------------------------------
    // FRS058-17 跨用户会话上下文隔离（FRS-058 §8.3 / §11.3）
    // ------------------------------------------------------------------
    @Test
    @Order(15)
    @Tag("story-frs058-17")
    @Story("FRS-058.FRS058-17: 会话上下文不得在不同用户间串用——用户A的截图内容"
            + "不得泄漏进用户B的答复与出站检索")
    @DisplayName("FRS058-17 跨用户会话上下文不串用")
    void frs05817CrossUserContextIsolation() throws Exception {
        faq.setMode(FaqMode.SOLUTION);
        // 用户A：独立会话提交含唯一锚点 TX-9527 的截图，正常完成排障
        HttpResponse<String> userA = upload(
                List.of(new Feat058WireSupport.FileField("screenshot", "error-page.png",
                        "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        assertThat(userA.statusCode()).as("用户A图文提交应被接受").isEqualTo(200);
        assertThat(parseSse(userA.body()).completed())
                .as("前置：用户A应正常完成排障").isTrue();

        // 用户B：独立 project + 独立 conversation，纯文本无关问题（与A的截图内容无交集）
        int wireBeforeUserB = faq.totalRequests();
        String userBUrl = client(SUT).getBaseUrl()
                + "/v1/projects/p-frs058-user-b/conversations/conv-"
                + UUID.randomUUID().toString().substring(0, 8);
        Map.Entry<String, byte[]> userBBody = multipart(List.of(),
                List.<String[]>of(new String[]{"message", "帮我看看最近一期理财产品的购买入口在哪里"}));
        HttpResponse<String> userB = postUrl(userBUrl, userBBody.getKey(), userBBody.getValue());
        assertThat(userB.statusCode())
                .as("用户B独立会话请求应被接受（HTTP 200）").isEqualTo(200);
        SseTurn userBTurn = parseSse(userB.body());
        assertThat(userBTurn.completed())
                .as("用户B纯文本请求应正常收敛").isTrue();
        assertThat(userBTurn.fullText())
                .as("用户B应获得与自身问题相关的非空作答").isNotBlank();
        assertThat(userBTurn.fullText())
                .as("用户B答复不得包含用户A截图的唯一错误码 TX-9527（§8.3 会话上下文不跨用户串用）")
                .doesNotContain("TX-9527");
        assertThat(String.join("\n", faq.acceptedBodies()
                .subList(Math.min(wireBeforeUserB, faq.acceptedBodies().size()),
                        faq.acceptedBodies().size())))
                .as("用户B出站检索不得携带用户A会话的截图内容（TX-9527）")
                .doesNotContain("TX-9527");
    }

    // ------------------------------------------------------------------
    // 样张 fixture
    // ------------------------------------------------------------------

    /** 异常页面样张（含唯一错误码 TX-9527，与识图测试类同款生成逻辑）。 */
    private static byte[] anomalySampleBytes() {
        return renderedSample("手机银行-转账页面异常", "错误码: TX-9527",
                "提示: 转账渠道连接失败，请稍后重试");
    }

    /** 信息不足样张（仅模糊标题，无任何可判定错误信息）。 */
    private static byte[] vagueSampleBytes() {
        return renderedSample("应用页面", "界面已加载", "（无错误信息）");
    }

    /**
     * 程序生成样张 png（白底、首行标题加粗，余行黑字；内容唯一可判定，
     * 保证识图断言锚点是样张真实内容而非模型幻觉，与识图测试类同款渲染逻辑）。
     */
    private static byte[] renderedSample(String... lines) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                480, 240, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 480, 240);
        g.setColor(new java.awt.Color(0xB4, 0x1B, 0x1B));
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 22));
        g.drawString(lines[0], 24, 56);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 18));
        int y = 116;
        for (int i = 1; i < lines.length; i++) {
            g.drawString(lines[i], 24, y);
            y += 40;
        }
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
