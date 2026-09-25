package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code da.data.media-taverse}：媒体元数据读面与路径穿越防御。
 *
 * <p>媒体目录布局（被测实现 {@code DaMediaStore} L23）：{@code {DA_DATA_DIR}/sessions/{sessionId}/media/{mediaId}/meta.json}。
 * 本用例**自建**该目录与 meta.json（测试侧 fixture，不改业务代码），把 {@code deepanalyze.data.media-dir}
 * 指向它，从而在无外部共享卷的前提下验证读面。
 *
 * <p>主断言：① 合法 mediaId 被解析并跑通任务；② 不存在的 mediaId 返回 400（媒体解析失败即拒）；
 * ③ 路径穿越型 mediaId 必须被拒（不得读到媒体目录之外的文件）。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaDataMediaTaverseE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Path MEDIA_ROOT = Path.of("target", "sit-logs", "media-fixture-054").toAbsolutePath();

    private String sessionId;
    private String mediaId;
    private String blockedMediaId;
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
                    agent.property("deepanalyze.data.media-dir", MEDIA_ROOT.toString())
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    @BeforeAll
    void buildMediaFixtureAndClient() throws Exception {
        sessionId = "sess-media-" + UUID.randomUUID();
        mediaId = "media-054-fixture";
        Path mediaDir = MEDIA_ROOT.resolve("sessions").resolve(sessionId).resolve("media").resolve(mediaId);
        Files.createDirectories(mediaDir);
        Files.writeString(mediaDir.resolve("meta.json"),
                "{\"mediaId\":\"" + mediaId + "\",\"mimeType\":\"text/plain\","
                        + "\"fileName\":\"feat054-note.txt\",\"size\":26}",
                StandardCharsets.UTF_8);
        // 白名单整改：媒体解析先通过，fileName 为白名单外扩展名，才会真正走到 L2 §2.9 的校验
        blockedMediaId = "media-054-blocked";
        Path blockedDir = MEDIA_ROOT.resolve("sessions").resolve(sessionId).resolve("media").resolve(blockedMediaId);
        Files.createDirectories(blockedDir);
        Files.writeString(blockedDir.resolve("meta.json"),
                "{\"mediaId\":\"" + blockedMediaId + "\",\"mimeType\":\"application/octet-stream\","
                        + "\"fileName\":\"payload.exe\",\"size\":128}",
                StandardCharsets.UTF_8);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        base = client(AGENT).getBaseUrl();
        Allure.addAttachment("媒体 fixture", "text/plain", mediaDir.toString());
    }

    @Test
    @Story("da.data.media-taverse: 合法 mediaId 被解析并跑通")
    @DisplayName("da.data.media-taverse: 媒体元数据读面可读 + 任务完成")
    void existingMediaIsReadAndTaskCompletes() throws Exception {
        HttpResponse<String> response = post("/agent/run", "{\"sessionId\":\"" + sessionId
                + "\",\"input\":\"请用一句话说明你收到了一个附件，不要调用任何工具。\","
                + "\"mediaIds\":[\"" + mediaId + "\"]}");
        Allure.addAttachment("POST /agent/run（带媒体）", "text/plain", response.body());
        assertThat(response.statusCode()).as("媒体元数据可读时任务应被受理").isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("status").asText()).isEqualTo("completed");
    }

    @Test
    @Story("da.data.media-taverse: 不存在的 mediaId 被拒")
    @DisplayName("da.data.media-taverse: 未知 mediaId → 400 媒体解析失败")
    void unknownMediaReturnsBadRequest() throws Exception {
        HttpResponse<String> response = post("/agent/run", "{\"sessionId\":\"" + sessionId
                + "\",\"input\":\"附件说明\",\"mediaIds\":[\"no-such-media-054\"]}");
        Allure.addAttachment("POST /agent/run（未知媒体）", "text/plain", response.body());
        assertThat(response.statusCode()).as("媒体不存在必须拒绝（不得静默降级）").isEqualTo(400);
        assertThat(response.body()).containsIgnoringCase("media");
    }

    @Test
    @Story("da.data.media-taverse: 路径穿越被拒")
    @DisplayName("da.data.media-taverse: 穿越型 mediaId → 被拒，不读媒体目录外文件")
    void traversalMediaIdIsRejected() throws Exception {
        HttpResponse<String> response = post("/agent/run", "{\"sessionId\":\"" + sessionId
                + "\",\"input\":\"附件说明\",\"mediaIds\":[\"..%2F..%2Fetc%2Fpasswd\",\"../../etc/passwd\"]}");
        Allure.addAttachment("POST /agent/run（穿越 mediaId）", "text/plain", response.body());
        assertThat(response.statusCode())
                .as("穿越型 mediaId 必须被拒（400/404），不得按文件系统路径解析")
                .isIn(400, 404);
        assertThat(response.body()).as("响应体不得回显系统文件内容").doesNotContain("root:");
    }

    /**
     * {@code da.file.whitelist} 的**ISSUE #367 修复后回归（2026-09-23 由降级恢复为真执行）**。
     *
     * <p>历史：上一轮该用例的 PASS 作废（拒绝发生在媒体解析阶段、未触达白名单），随后以 {@code @Disabled}
     * 降级登记，升级触发条件＝"#367 修复后改回真执行并复跑"。修复 PR !672 让 DA 兼容端点的
     * {@code mediaIds} 媒体路径在媒体解析成功后、任务受理前调用
     * {@code DaFileWhitelistValidator.validateFileName(meta.fileName())}，白名单外扩展名返回 400
     * {@code {error:"DA-FILE-003: ..."}}。本用例让媒体解析先成功（fixture 文件真实存在）、文件名却是
     * 白名单外扩展名（.exe），从而真正打到该复用口。
     */
    @Test
    @Story("da.file.whitelist: 白名单外文件类型被拒（真触达校验）")
    @DisplayName("da.file.whitelist: .exe 附件 → 400 且业务错误码 DA-FILE-003")
    void nonWhitelistedFileTypeIsRejectedWithBusinessCode() throws Exception {
        HttpResponse<String> response = post("/agent/run", "{\"sessionId\":\"" + sessionId
                + "\",\"input\":\"附件说明\",\"mediaIds\":[\"" + blockedMediaId + "\"]}");
        Allure.addAttachment("POST /agent/run（白名单外扩展名）", "text/plain", response.body());
        assertThat(response.statusCode()).as("白名单外文件类型必须拒绝").isEqualTo(400);
        assertThat(response.body())
                .as("拒绝必须来自白名单校验（DA-FILE-003），而不是媒体解析失败")
                .contains("DA-FILE-003");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
