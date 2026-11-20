package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-109 场景快速通道（F109-18 正向面）。
 *
 * <p>判据：
 * <ul>
 *   <li>快速通道语义（L1 §5.1.1）：{@code push_content type!=file} 在工具通过 DA 预检、shell/内置
 *       拒绝检查且合并规则结果为通用默认 ask 时，把该 ask 裁为 allow——不弹确认。</li>
 *   <li>实现位：DaPermissionHost 快速通道（L2 §3.1，仅匹配 push_content type!=file 与
 *       db_query connectionId=list）；actrule 资产注释（push_content: ask「其余类型由
 *       DaPermissionHost 直接放行」）。</li>
 *   <li>取严边界：显式 deny 不被快速通道放宽——场景层 deny 的取严语义已由 Feat109DenyE2EIT
 *       （run-07）真机承接（bash 场景 deny→直接拒绝无确认），本类引用不重复建实例。</li>
 *   <li>db_query connectionId=list 面：依赖 DB 连接构造，defer（范围卡 §4.4 关联；push_content
 *       代表已承接快速通道语义主断言）。</li>
 * </ul>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109FastPathE2EIT {

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

    @Test
    @Story("F109-18a: push_content type!=file 快速通道——通用默认 ask 被裁为 allow，不弹确认")
    @DisplayName("F109-18a: push_content 快速通道放行")
    void pushContentFastPathBypassesConfirm() throws Exception {
        String session = "sess-f109fp-" + System.nanoTime();
        String input = "使用 push_content 工具推送一段文字内容（type 为 text），内容为：f109-fast-path-"
                + System.nanoTime() + "。不要执行其他任何工具，推送后结束。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\"}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(150));
        List<SseFrame> frames = DaCompatGuardrailSupport.readFrames(stream, f -> false,
                Duration.ofSeconds(150));
        try {
            Path dir = Path.of("target", "f109-dump");
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder("session=" + session + "\n---frames---\n");
            for (int i = 0; i < frames.size(); i++) {
                sb.append("[").append(i).append("] data=").append(frames.get(i).data()).append('\n');
            }
            Files.writeString(dir.resolve(session + ".txt"), sb.toString());
        } catch (Exception e) {
            Allure.addAttachment("dump 失败", "text/plain", String.valueOf(e));
        }

        // 模型旁路分流：push_content 未被调用（纯文本回复）⇒ INCONCLUSIVE
        boolean pushCalled = frames.stream().anyMatch(f -> f.data() != null
                && (f.data().contains("\"toolName\":\"push_content\"")
                || f.data().contains("push_content")));
        org.junit.jupiter.api.Assumptions.assumeTrue(pushCalled,
                "INCONCLUSIVE——模型未调 push_content（纯文本回复未触达快速通道面）；帧数=" + frames.size());

        boolean confirmOnPush = frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("\"kind\":\"permission_confirm\"")
                && "push_content".equals(f.field("toolName")));
        assertThat(confirmOnPush).as("push_content(type!=file) 不弹权限确认（快速通道裁为 allow）")
                .isFalse();
        boolean pushSucceeded = frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("push_content")
                && (f.data().contains("\"status\":\"completed\"")
                || f.data().contains("pushed") || f.data().contains("推送")));
        assertThat(pushSucceeded).as("push_content 实际执行（快速通道后放行）").isTrue();
        Allure.addAttachment("F109-18a 证据", "text/plain", "帧数=" + frames.size()
                + " confirmOnPush=" + confirmOnPush + " pushSucceeded=" + pushSucceeded);
    }
}
