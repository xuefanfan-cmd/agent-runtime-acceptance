package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-109 静态校验受理前 400（F109-16，零模型成本——直接构造非法 runtimeConfig.hooks）。
 *
 * <p>判据（L2 §2.1/§7.3，实现事实口径）：
 * <ul>
 *   <li>enabled callback、未知事件、workdir 词法越界 → taskId 分配/任务注册/start 前 HTTP 400
 *       {@code ENGINE_VALIDATION}，无任务记录与 hook/工具副作用。</li>
 *   <li>非法 matcher 仅在 PreToolUse/PostToolUse 上前置拒绝；PostToolUseFailure 与生命周期事件
 *       忽略 matcher（含畸形形态）——DV-109-06 口径，本类同时正向验证「忽略」面。</li>
 *   <li>disabled 不支持项可忽略（enabled=false → 不校验、任务正常受理）。</li>
 * </ul>
 *
 * <p>构造：POST /api/agents/run-stream 带非法 hooks 变体 → 断言 400 + code=ENGINE_VALIDATION +
 * body 无 taskId（未建立可查询任务记录）。受理成功对照（disabled 项 + 合法 hooks）→ 200 SSE 流。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109HooksValidationE2EIT {

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent).start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaCompatGuardrailSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    /** 构造 run-stream 请求体（hooks 数组内联 JSON）。 */
    private static String bodyWithHooks(String session, String hooksJson) {
        return "{\"sessionId\":\"" + session + "\",\"input\":\"你好\",\"runtimeConfig\":"
                + "{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\"" + session
                + "\",\"hooks\":" + hooksJson + "}}";
    }

    private static HttpResponse<String> run(String session, String hooksJson) throws Exception {
        return DaCompatGuardrailSupport.post(http, base, "/api/agents/run-stream",
                bodyWithHooks(session, hooksJson), Duration.ofSeconds(30));
    }

    private static void assertRejected(HttpResponse<String> resp, String variant) {
        assertThat(resp.statusCode()).as(variant + " → 受理前 400").isEqualTo(400);
        assertThat(resp.body()).as(variant + " → code=ENGINE_VALIDATION")
                .contains("ENGINE_VALIDATION");
        assertThat(resp.body()).as(variant + " → 不建立任务记录（无 taskId）").doesNotContain("taskId");
        Allure.addAttachment("F109-16 " + variant, "text/plain",
                "HTTP " + resp.statusCode() + " body=" + resp.body());
    }

    @Test
    @Story("F109-16a: enabled callback 受理前 400")
    @DisplayName("F109-16a: enabled callback 400")
    void enabledCallbackRejectedBeforeStart() throws Exception {
        assertRejected(run("sess-f109v-cb-" + System.nanoTime(),
                "[{\"id\":\"cb-1\",\"event\":\"SessionStart\",\"type\":\"callback\",\"enabled\":true}]"),
                "enabled callback");
    }

    @Test
    @Story("F109-16b: 未知事件受理前 400")
    @DisplayName("F109-16b: 未知事件 400")
    void unknownEventRejectedBeforeStart() throws Exception {
        assertRejected(run("sess-f109v-ue-" + System.nanoTime(),
                "[{\"id\":\"h-1\",\"event\":\"NotARealEvent\",\"type\":\"command\","
                        + "\"config\":{\"command\":\"true\"},\"enabled\":true}]"),
                "未知事件");
    }

    @Test
    @Story("F109-16c: command 工作目录越界受理前 400")
    @DisplayName("F109-16c: workdir 越界 400")
    void workdirEscapeRejectedBeforeStart() throws Exception {
        // 词法越界：绝对路径脱离 command-root（L2 §1.3.9：词法校验有、realpath 无）
        assertRejected(run("sess-f109v-wd-" + System.nanoTime(),
                "[{\"id\":\"h-1\",\"event\":\"PreToolUse\",\"type\":\"command\","
                        + "\"config\":{\"command\":\"true\",\"workdir\":\"/etc\"},\"enabled\":true}]"),
                "workdir 词法越界");
    }

    @Test
    @Story("F109-16d: 非法 matcher 仅 PreToolUse/PostToolUse 前置拒绝")
    @DisplayName("F109-16d: PreToolUse 非法 matcher 400")
    void invalidMatcherOnPreToolUseRejected() throws Exception {
        assertRejected(run("sess-f109v-im-" + System.nanoTime(),
                "[{\"id\":\"h-1\",\"event\":\"PreToolUse\",\"type\":\"command\","
                        + "\"matcher\":\"[invalid\",\"config\":{\"command\":\"true\"},\"enabled\":true}]"),
                "PreToolUse 非法 matcher");
    }

    @Test
    @Story("F109-14c(#45-2 裁决): HTTP hook 未配匹配白名单 → 建档/start 前受理 400（执行时拦截保留作纵深）")
    @DisplayName("F109-14c: 白名单缺失受理前 400")
    void httpHookWithoutWhitelistRejectedBeforeStart() throws Exception {
        // #45 裁决：保留 MUST，非匹配目标在任务建档/start 前返回 400（r2 制品 76bcad137 实现）
        assertRejected(run("sess-f109v-wl-" + System.nanoTime(),
                "[{\"id\":\"h-1\",\"event\":\"PreToolUse\",\"type\":\"http\","
                        + "\"config\":{\"url\":\"http://127.0.0.1:9/hook\"},\"enabled\":true}]"),
                "HTTP hook 白名单缺失（裁决后受理 400）");
    }

    @Test
    @Story("F109-16e: 非 blocking 事件忽略 matcher（畸形形态不前置拒绝，DV-109-06 正向面）")
    @DisplayName("F109-16e: 生命周期事件忽略 matcher")
    void lifecycleEventIgnoresMatcher() throws Exception {
        // Stop 事件带畸形 matcher → 不前置 400（受理进入 SSE 流即证明未被拒绝）
        HttpResponse<String> resp = run("sess-f109v-ig-" + System.nanoTime(),
                "[{\"id\":\"h-1\",\"event\":\"Stop\",\"type\":\"command\","
                        + "\"matcher\":\"[invalid\",\"config\":{\"command\":\"true\"},\"enabled\":true}]");
        assertThat(resp.statusCode()).as("生命周期事件忽略 matcher（不 400，按 SSE 受理）")
                .isEqualTo(200);
        Allure.addAttachment("F109-16e", "text/plain",
                "HTTP " + resp.statusCode() + "（畸形 matcher 在 Stop 上被忽略，受理进入事件流）");
    }

    @Test
    @Story("F109-16f: disabled 不支持项可忽略（enabled=false 不校验、任务正常受理）")
    @DisplayName("F109-16f: disabled 项忽略")
    void disabledItemIgnored() throws Exception {
        HttpResponse<String> resp = run("sess-f109v-di-" + System.nanoTime(),
                "[{\"id\":\"cb-1\",\"event\":\"SessionStart\",\"type\":\"callback\",\"enabled\":false}]");
        assertThat(resp.statusCode()).as("disabled callback 不校验，按 SSE 受理 200").isEqualTo(200);
        Allure.addAttachment("F109-16f", "text/plain", "HTTP " + resp.statusCode());
    }
}
