package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.mock.MockCallbackReceiver;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-001.failed-callback-error-code — 断言 sender fire 的 <b>FAILED callback body 必须携带
 * 结构化 error code</b>,否则用例红,以显性化 [[FEAT-001-failed-task-stable-error-code-contract-missing]]
 * spec gap。
 *
 * <p><b>Spec 依据</b>(version-scope FEAT-001 §5.1.6 + §5.1.8 + FEAT-001-failed-task-stable-
 * error-code-contract-missing.md):
 * <ul>
 *   <li>「handler 输出 FAILED 时必须形成 failed Task 表面,并携带<b>可供客户端程序化判断</b>的错误信息」;</li>
 *   <li>gap doc §5.2 / §5.4 已定位:当前 SUT 只在 status.message 里塞自然语言 TextPart(仅
 *     {@code errorMessage} 字符串),<b>无稳定 error code / retryable / origin 结构化字段</b>。</li>
 * </ul>
 *
 * <p><b>关键设计</b>:与 {@link TaskFailedPayloadTest} 视角不同 —— 那份看 <b>Task 表面</b>
 * (通过 A2A client 拿终态 Task 对象),本份看 <b>sender fire 的 callback body</b>(通过
 * {@link MockCallbackReceiver} 直接收到的 HTTP payload)。sender callback 是 push notification
 * 通道的净负载,和 Task 表面可能语义相似但字段可能不完全一致 —— push channel 是否有结构化 code 与
 * pull channel 是否有,是两个独立契约,都要覆盖。
 *
 * <p><b>断言 shape</b>:与 TaskFailedPayloadTest §5.1.6 层 3 同款「三选一」:
 * <ol>
 *   <li>(a) callback body 里存在 <b>结构化 DataPart</b>(通过 {@code parts[i].type == data} 或
 *       {@code parts[i].kind == data} 判识,兼容 SDK 不同 shape);</li>
 *   <li>(b) callback body 的 TextPart 文本本身是 <b>JSON object</b>(不算自然语言字符串);</li>
 *   <li>(c) callback body 里(包括 status/status.message/status.message.metadata/顶层 metadata)
 *       递归找到任意约定 error 字段名(见 {@link #PROGRAMMATIC_KEYS})。</li>
 * </ol>
 * 三条皆无 → 客户端只能靠 TextPart 自然语言启发式解析 → 违反 §5.1.6 → 红。
 *
 * <p><b>预期结果</b>:当前 SUT 阶段本用例<b>大概率红</b>(参照 gap doc §5.2:{@code failAndDrain()}
 * 构造 Message 只塞 TextPart(errorMessage))。红即证明 gap 存在,SUT 补齐后自动绿。<b>不要</b>
 * relax 断言。
 *
 * <p><b>触发机制</b>:与 {@link FailedSenderFireTest} 共享 —— 用 invalid api-key。<b>2026-08-08
 * 实测确认该触发器命中 §S2 buggy 路径</b>(LLM 401 → generic RuntimeException → InternalError →
 * sender skip),而非 §S3 working。因此本用例当前会在"callback 未到达"前置阶段就红,error-code
 * 层的断言 <b>暂时无从评估</b>。等 SUT 修 §S2 后前置满足,error-code 断言方可发挥主判力。这两层
 * 断言彼此独立:
 * <ul>
 *   <li>前置 red = §S2 gap([[push-notification-sender-not-activated]] 中记录);</li>
 *   <li>error-code red = §5.1.6 stable-error-code gap([[FEAT-001-failed-task-stable-error-code-contract-missing]])。</li>
 * </ul>
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-001")
@Tag("manual")
@Feature("FEAT-001: 标准化智能体服务入口")
@Story("da.failed-callback-error-code: FAILED sender callback 必须含结构化 error code(§5.1.6 gap red-first)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailedCallbackErrorCodeTest {

    private static final Logger LOG = Logger.getLogger(FailedCallbackErrorCodeTest.class.getName());

    private static final String SEARCH = "search";

    private static final String PROMPT = "帮我搜索 2026 年 7 月全球黄金价格盘中最高价";

    private static final String INVALID_API_KEY = "SIT-INVALID-API-KEY-" + UUID.randomUUID();

    /** 与 FailedSenderFireTest 保持一致 —— LLM 401 通常几秒,60s 覆盖 warmup + retry. */
    private static final long FAILED_CALLBACK_WAIT_MS = 60_000L;

    /** 允许作为 "程序化判断" 信号的字段名(case-insensitive, substring 匹配). */
    private static final List<String> PROGRAMMATIC_KEYS =
            List.of("error", "errorcode", "code", "reason", "type");

    private TestConfig config;
    private SutStack searchStack;
    private MockCallbackReceiver mockReceiver;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    void startStack() throws Exception {
        config = TestConfig.load();

        mockReceiver = MockCallbackReceiver.start();
        LOG.info("[failed-code] MockCallbackReceiver ready at " + mockReceiver.callbackUrl());

        searchStack = SutStack.builder(config)
                .agent(SEARCH, a -> a
                        .property("openjiuwen.demo.search-agent.api-key", INVALID_API_KEY))
                .start();
        LOG.info("[failed-code] search-agent ready at " + searchStack.baseUrl(SEARCH)
                + " (api-key intentionally invalid)");
    }

    @AfterAll
    void tearDown() {
        if (searchStack != null) searchStack.close();
        if (mockReceiver != null) mockReceiver.close();
    }

    @Test
    @DisplayName("FEAT-001.failed-callback-error-code: FAILED callback body 必须含结构化 error code"
            + "(DataPart / JSON TextPart / metadata error 字段三选一)")
    void failedCallbackBodyMustCarryStructuredErrorCode() throws Exception {
        String contextId = "ctx-failed-code-" + UUID.randomUUID().toString().substring(0, 8);
        String messageId = UUID.randomUUID().toString();
        String configId = "sit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
        String configToken = "sit-token-" + UUID.randomUUID().toString().substring(0, 8);

        String body = buildSendMessage(
                "failed-code-" + UUID.randomUUID().toString().substring(0, 8),
                messageId, contextId, PROMPT,
                configId, mockReceiver.callbackUrl(), configToken);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = post(searchStack.baseUrl(SEARCH) + "/a2a", body);
        LOG.info(String.format("[failed-code] SendMessage status=%d body=%s",
                response.statusCode(), response.body()));

        assertThat(response.statusCode())
                .as("[failed-code] SendMessage HTTP status 应 200(非阻塞骨架)\nbody=%s", response.body())
                .isEqualTo(200);

        LOG.info(String.format("[failed-code] observing %d ms for FAILED callback ...",
                FAILED_CALLBACK_WAIT_MS));
        boolean reached = mockReceiver.awaitAtLeast(1, FAILED_CALLBACK_WAIT_MS);
        long totalElapsed = System.currentTimeMillis() - t0;
        dumpCaptured(t0);

        // 前置:sender 必须 fire —— 如未 fire,是 FailedSenderFireTest 覆盖的另一契约,本用例主张 gap
        // 的关注点(结构化 code)无处施加,直接 red 并给出明确诊断。
        assertThat(reached)
                .as("[failed-code][前置] 期望 %d ms 内收到 FAILED callback,实测 %d 次。"
                                + "若 0,先看 FailedSenderFireTest —— 可能 auth 失败走的是 §S2 buggy 路径"
                                + "(RuntimeException → InternalError → sender lost),而非 §S3 working path。"
                                + "本用例前置未满足,error-code 断言无法评估。elapsed=%d ms",
                        FAILED_CALLBACK_WAIT_MS, mockReceiver.count(), totalElapsed)
                .isTrue();

        MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(0);
        JsonNode cbBody = mapper.readTree(cb.body());

        Diagnosis diag = diagnose(cbBody);
        LOG.info(String.format("[failed-code] diagnosis: %s", diag));

        assertThat(diag.hasStructuredSignal())
                .as("[failed-code] §5.1.6 承诺「可供客户端程序化判断的错误信息」,FAILED callback body 应满足以下之一:\n"
                                + "  (a) 至少一个 part 是结构化 DataPart(type/kind = data),或\n"
                                + "  (b) TextPart 文本自身是 JSON 对象,或\n"
                                + "  (c) body 里(含 status/message/metadata 递归查)出现约定 error 字段:%s\n"
                                + "  三者皆无 → 客户端只能靠自然语言启发式解析 → 违反 §5.1.6。\n"
                                + "  当前预期红 —— gap doc §5.2:failAndDrain 只塞 TextPart(errorMessage)。\n"
                                + "  diagnosis=%s\n  callback.body=%s",
                        PROGRAMMATIC_KEYS, diag, cb.body())
                .isTrue();
    }

    /**
     * 深度扫描 callback body,判断是否存在结构化程序化信号:
     * <ul>
     *   <li>DataPart:parts[i] 里存在 {@code type=data} 或 {@code kind=data} 或裸露 {@code data} 字段;</li>
     *   <li>JSON TextPart:任一 TextPart 的 text 本身是 JSON 对象;</li>
     *   <li>error 字段:body 任意深度 field 名匹配 {@link #PROGRAMMATIC_KEYS}。</li>
     * </ul>
     */
    private Diagnosis diagnose(JsonNode body) {
        boolean hasDataPart = false;
        boolean hasJsonTextPart = false;
        String textSample = null;

        JsonNode parts = firstNonMissingParts(body);
        if (parts != null && parts.isArray()) {
            for (JsonNode part : parts) {
                String type = part.path("type").asText(null);
                String kind = part.path("kind").asText(null);
                if ("data".equalsIgnoreCase(type) || "data".equalsIgnoreCase(kind)
                        || part.has("data")) {
                    hasDataPart = true;
                }
                JsonNode text = part.path("text");
                if (text.isTextual()) {
                    String t = text.asText();
                    if (textSample == null) {
                        textSample = t.length() <= 200 ? t : t.substring(0, 200) + "...";
                    }
                    if (looksLikeJsonObject(t)) hasJsonTextPart = true;
                }
            }
        }

        String foundKey = findProgrammaticKey(body);

        return new Diagnosis(hasDataPart, hasJsonTextPart, foundKey, textSample);
    }

    /** 兼容多种 body shape:parts / status.message.parts / task.status.message.parts / message.parts. */
    private static JsonNode firstNonMissingParts(JsonNode body) {
        JsonNode[] candidates = {
                body.path("parts"),
                body.path("status").path("message").path("parts"),
                body.path("task").path("status").path("message").path("parts"),
                body.path("message").path("parts")
        };
        for (JsonNode c : candidates) {
            if (c != null && c.isArray()) return c;
        }
        return null;
    }

    /** 递归找第一个匹配 {@link #PROGRAMMATIC_KEYS} 的 field name,返 "path::key" 便于日志诊断. */
    private static String findProgrammaticKey(JsonNode node) {
        return findProgrammaticKey(node, "$");
    }

    private static String findProgrammaticKey(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isValueNode()) return null;
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String lower = name.toLowerCase();
                for (String want : PROGRAMMATIC_KEYS) {
                    if (lower.equals(want) || lower.contains(want)) {
                        return path + "." + name;
                    }
                }
                String deep = findProgrammaticKey(node.get(name), path + "." + name);
                if (deep != null) return deep;
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String deep = findProgrammaticKey(node.get(i), path + "[" + i + "]");
                if (deep != null) return deep;
            }
        }
        return null;
    }

    private static boolean looksLikeJsonObject(String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        if (trimmed.length() < 2) return false;
        if (trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            return false;
        }
        return trimmed.matches("(?s)\\{\\s*\"[^\"]+\"\\s*:.*}");
    }

    private static String buildSendMessage(String rpcId, String messageId, String contextId,
                                            String prompt, String configId, String callbackUrl,
                                            String configToken) {
        return String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":\"%s\","
                        + "\"method\":\"SendMessage\",\"params\":{"
                        + "\"message\":{"
                        + "\"role\":\"ROLE_USER\","
                        + "\"messageId\":\"%s\","
                        + "\"contextId\":\"%s\","
                        + "\"parts\":[{\"text\":\"%s\"}]"
                        + "},"
                        + "\"configuration\":{"
                        + "\"taskPushNotificationConfig\":{"
                        + "\"id\":\"%s\","
                        + "\"url\":\"%s\","
                        + "\"token\":\"%s\""
                        + "},"
                        + "\"returnImmediately\":true"
                        + "}}}",
                rpcId, messageId, contextId, prompt,
                configId, callbackUrl, configToken);
    }

    private void dumpCaptured(long t0) {
        for (int i = 0; i < mockReceiver.captured().size(); i++) {
            MockCallbackReceiver.CapturedCallback cb = mockReceiver.captured().get(i);
            LOG.info(String.format(
                    "[failed-code] callback #%d at t+%d ms | headers=%s%n  body=%s",
                    i, cb.timestampMs() - t0, cb.headers(), cb.body()));
        }
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private record Diagnosis(boolean hasDataPart, boolean hasJsonTextPart,
                              String foundKeyPath, String textSample) {

        boolean hasStructuredSignal() {
            return hasDataPart || hasJsonTextPart || foundKeyPath != null;
        }

        @Override
        public String toString() {
            return "hasDataPart=" + hasDataPart
                    + " hasJsonTextPart=" + hasJsonTextPart
                    + " foundKeyPath=" + foundKeyPath
                    + " textSample=" + textSample;
        }
    }
}
