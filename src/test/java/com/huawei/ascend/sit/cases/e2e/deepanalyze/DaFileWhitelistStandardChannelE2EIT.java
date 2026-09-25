package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code da.file.whitelist}：**ISSUE #367 修复后回归**，走标准 A2A 消息通道触达文件类型白名单。
 *
 * <p>背景：原报告的缺陷是"两侧形状脱节"——runtime 适配层
 * （{@code A2AProtocolAdapter.extractParts}）把入站文件 part 归一为
 * {@code kind=raw|url} + <b>平铺</b> {@code filename}，而 DA 校验器
 * （{@code DaFileWhitelistValidator.extractFileName}）只认 {@code file.fileName} 与
 * {@code kind=file}+{@code fileName}，两侧无交集 ⇒ 线上合法请求无法被白名单拦截。
 *
 * <p><b>线上 Part 契约（实测 + 源码双证）</b>：A2A JSON-RPC 的每个 part 直接就是内容字段本身，
 * <b>必须且只能</b>携带 {@code text}/{@code raw}/{@code url}/{@code data} 之一，{@code kind} 由字段推导、
 * 不是入参（{@code A2aJsonRpcParamsParser.parseNormalizedPart} / {@code singleContentKind}；
 * {@code A2aPartRules.validateOne}）。实测发 SDK 风格 {@code {"kind":"file","file":{…}}} 或把 raw 写成
 * {@code bytesBase64} 都会得到
 * {@code -32602 Invalid params: params.message.parts[1] must contain exactly one of text/raw/url/data}。
 * 因此携带文件名的合法线上编码只有两种（{@code filename}/{@code mediaType} 为平铺同级字段）：
 * {@code {"raw":"<base64>","filename":…}} 与 {@code {"url":"http(s)://…","filename":…}}
 * （{@code url} 强制 http/https 协议）。本类即按这两种形状发送。
 *
 * <p>修复（PR !672）：校验器增认平铺 {@code filename} 键（任意 kind）。本类因此把断言口径从
 * "复现缺陷（不得出现 DA-FILE-003）"反转为"修复后必须出现 DA-FILE-003"：
 *
 * <ul>
 *   <li>反驳面：白名单外 {@code .exe} 的 {@code raw} 与 {@code url} 两种线上编码稳定返回 {@code DA-FILE-003}；</li>
 *   <li>对照面：白名单内 {@code .txt} 的 {@code raw} 请求不得出现该拒绝，且必须真的进入执行
 *       （不得是协议层 {@code -32602} 拒绝——否则本类会退化成空过）。</li>
 * </ul>
 *
 * <p>默认白名单来自被测件 {@code application.yml}：pdf/docx/doc/txt/md/xlsx/csv/png/jpg
 * （{@code deepanalyze.security.file-whitelist}），本类不覆盖该配置。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaFileWhitelistStandardChannelE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    /** 反驳面：白名单外扩展名。 */
    private static final String NON_WHITELISTED = "payload.exe";
    /** 对照面：白名单内扩展名。 */
    private static final String WHITELISTED = "note.txt";
    private static final Duration REJECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONTROL_TIMEOUT = Duration.ofMinutes(5);

    private HttpClient http;
    private String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
                    passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
                    passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
                    passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    private void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            base = client(AGENT).getBaseUrl();
        }
    }

    @Test
    @Story("da.file.whitelist: 白名单外 raw part（平铺 filename）被拒 DA-FILE-003")
    @DisplayName("da.file.whitelist: raw + filename=payload.exe → DA-FILE-003（#367 关闭回归）")
    void nonWhitelistedFileBytesPartIsRejectedWithBusinessCode() throws Exception {
        initClient();
        HttpResponse<String> response = send(rawFilePart(NON_WHITELISTED, "application/octet-stream"),
                REJECT_TIMEOUT);
        attach("标准通道 raw part（" + NON_WHITELISTED + "）响应", response);
        assertRejectedWithBusinessCode(response, "kind=raw + 平铺 filename（runtime 归一形状）");
    }

    @Test
    @Story("da.file.whitelist: 白名单外 url part（平铺 filename）被拒 DA-FILE-003")
    @DisplayName("da.file.whitelist: url + filename=payload.exe → DA-FILE-003（#367 关闭回归）")
    void nonWhitelistedFileUriPartIsRejectedWithBusinessCode() throws Exception {
        initClient();
        HttpResponse<String> response = send(urlFilePart(NON_WHITELISTED), REJECT_TIMEOUT);
        attach("标准通道 url part（" + NON_WHITELISTED + "）响应", response);
        assertRejectedWithBusinessCode(response, "kind=url + 平铺 filename（runtime 归一形状）");
    }

    @Test
    @Story("da.file.whitelist: 白名单内 raw part 不被拒且真正进入执行（对照）")
    @DisplayName("da.file.whitelist: raw + filename=note.txt → 无 DA-FILE-003，且非协议拒绝")
    void whitelistedFilePartIsAcceptedAndReachesExecution() throws Exception {
        initClient();
        HttpResponse<String> response = send(rawFilePart(WHITELISTED, "text/plain"), CONTROL_TIMEOUT);
        attach("标准通道对照（" + WHITELISTED + "）响应", response);

        assertThat(response.body())
                .as("对照面：白名单内扩展名不得被白名单拒绝")
                .doesNotContain("DA-FILE-003");
        assertThat(response.body())
                .as("对照面必须真的进入执行（若为协议层 -32602 拒绝，则反驳面的'被拒'也就无法归因白名单，"
                        + "本类会退化为空过）")
                .doesNotContain("-32602");
        assertThat(response.body())
                .as("受理后应返回 SSE 事件流（event: 帧）")
                .contains("event:");
    }

    private void assertRejectedWithBusinessCode(HttpResponse<String> response, String shape) {
        assertThat(response.body())
                .as("形状 %s 携带白名单外文件名时必须被白名单拒绝并给出业务错误码 DA-FILE-003", shape)
                .contains("DA-FILE-003");
        assertThat(response.body())
                .as("拒绝必须出现在链路内（不得是请求根本没被解析）")
                .doesNotContain("Unsupported part");
    }

    private void attach(String title, HttpResponse<String> response) {
        Allure.addAttachment(title, "text/plain",
                "HTTP " + response.statusCode() + "\n" + response.body());
    }

    /**
     * 线上 raw 文件 part：内容字段 {@code raw}（base64） + 平铺 {@code filename}/{@code mediaType}。
     * 这是携带文件名且能通过 {@code A2aPartRules} 结构校验的两种编码之一；适配层归一后即
     * {@code kind=raw, bytesBase64, filename}。
     */
    private String rawFilePart(String fileName, String mimeType) {
        return envelope("{\"raw\":\"aGVsbG8=\",\"filename\":\"" + fileName
                + "\",\"mediaType\":\"" + mimeType + "\"}");
    }

    /**
     * 线上 url 文件 part：内容字段 {@code url} + 平铺 {@code filename}。
     * 注意 {@code A2aPartRules.validateOne} 强制 url 使用 http/https，故此处用 https 占位主机名。
     */
    private String urlFilePart(String fileName) {
        return envelope("{\"url\":\"https://files.invalid.example/" + fileName
                + "\",\"filename\":\"" + fileName + "\",\"mediaType\":\"application/octet-stream\"}");
    }

    private String envelope(String filePartJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + UUID.randomUUID() + "\",\"method\":\"SendStreamingMessage\","
                + "\"params\":{\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"" + UUID.randomUUID() + "\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"附件说明：请确认收到附件，不要调用任何工具。\"},"
                + filePartJson + "]}}}";
    }

    private HttpResponse<String> send(String body, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                // A2A JSON-RPC 端点为 /a2a（A2AServicePaths.A2A_JSONRPC），不是根路径
                .uri(URI.create(base.endsWith("/") ? base + "a2a" : base + "/a2a"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
