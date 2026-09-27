package com.huawei.ascend.sit.cases.integration.tscript;

import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Legacy-downstream probes for FEAT-039. The {@code legacy-downstream} scenario points {@code call_mcp}
 * at a controllable SIT-only stub script, so the returned body (ui_notice / versatile_query) is
 * deterministic while the tool itself stays the product's built-in {@code call_mcp}.
 */
@Tag("integration")
@Tag("tscript")
@Tag("feat-039")
@Feature("FEAT-039: 业务扩展工具话术（EDPA）")
class ToolScriptLegacyDownstreamTest extends AbstractToolScriptSitTest {
    private static final String STUB_CANARY = "TSCRIPT_DOWNSTREAM";
    private static final Path MARKER = Path.of("/tmp/tscript-legacy-downstream-marker.log");

    @Override
    protected String scenarioName() {
        return "legacy-downstream";
    }

    @Test
    @Story("ts039.uinotice-over-keyword: 存量 ui_notice 覆盖关键字话术")
    @DisplayName("TS039-C33: a legacy ui_notice result overrides the keyword script")
    void legacyUiNoticeOverridesKeywordScript() throws Exception {
        String canary = newCanary();
        Path marker = markerPath(canary);
        ToolScriptWireClient.Run run = send("TSCRIPT call exactly call_mcp once with JSON arguments "
                + "{\"script_command\":\"" + stubCommand("notice", canary, marker) + "\",\"script_params\":{},"
                + "\"query_intent\":\"intent_a\",\"query_description\":\"KEY_SECOND " + canary + "\"}. "
                + "The tool arguments object must contain exactly these four fields: script_command, script_params,"
                + " query_intent, query_description. Copy the three string values character by character and do not"
                + " drop query_description even if you consider it redundant. Do not rename the tool. After the tool"
                + " result, answer with " + canary + " only.");

        assertThat(markerLines(marker, canary))
                .as("the controllable downstream stub must have produced the tool result")
                .contains("notice " + canary);
        assertThat(run.events("call_mcp", "tool_start"))
                .as("tool_start comes from the keyword entry of the hit description")
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.path("content").asText()).isEqualTo("DOWNSTREAM_KEY_START"));
        assertThat(run.events("call_mcp", "tool_end"))
                .as("tool_end must come from ui_notice and must not fall back to the keyword entry")
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.path("content").asText()).isEqualTo("DOWNSTREAM_NOTICE_END"));
    }

    @Test
    @Story("ts039.dynamic-description-keyword: 动态 description 参与关键字匹配且原文不外泄")
    @DisplayName("TS039-C34: a rail-injected versatile description drives the keyword script and never reaches the front end")
    void railInjectedDescriptionNeverReachesContent() throws Exception {
        String canary = newCanary();
        Path marker = markerPath(canary);
        String context = "ctx-" + canary;

        // Step 1: one request that only calls call_mcp, so the stub's versatile_query is cached by the product.
        ToolScriptWireClient.Run mcpRun = send(context, "TSCRIPT call exactly call_mcp once with JSON arguments "
                + "{\"script_command\":\"" + stubCommand("versatile", canary, marker) + "\",\"script_params\":{},"
                + "\"query_intent\":\"intent_a\",\"query_description\":\"\"}. Copy script_command character by"
                + " character and keep query_description empty. Do not call any other tool. Then answer with "
                + canary + " only.");

        assertThat(markerLines(marker, canary))
                .as("step 1 must run the controllable stub so the versatile query reaches the product cache")
                .contains("versatile " + canary);
        assertThat(mcpRun.statusCode()).isEqualTo(200);

        // Step 2: same conversation context, empty description -> the product Rail injects the cached query.
        ToolScriptWireClient.Run run = send(context, "TSCRIPT call exactly call_versatile once with JSON arguments "
                + "{\"agent_name\":\"versatile-sit\",\"query_intent\":\"intent_a\",\"query_description\":\"\"}. "
                + "Keep query_description empty and do not call any other tool. Then answer with " + canary + " only.");

        Map<String, Integer> counters = counts();
        // 2026-09-16（docs PR#189 L2 §3.3）：动态描述改为走调用级内部快照（EventDescription），不再改写模型入参。
        // fixture 探针在 beforeToolCall 读的是工具入参，因此 versatile.description.* 计数已不能作为 Given 判据。
        // Given 改由可消费行为证明：生效描述只有含 KEY_SECOND 才会命中 keyword 条目（DOWNSTREAM_KEY_END）；
        // 若描述未被注入，下面的事件断言会回落 intent_a 默认值 DOWNSTREAM_A_END 而失败。探针计数仅作 Allure 留证。
        Allure.parameter("versatileProbeCounters", counters.toString());

        List<String> contents = run.scriptEvents().stream()
                .map(event -> event.path("content").asText())
                .toList();
        assertThat(contents).as("legacy events must be observed").isNotEmpty();
        assertThat(contents)
                .as("the rail-injected description (canary marker) must never become front-end content")
                .noneMatch(content -> content.contains(canary) || content.contains(STUB_CANARY));
        // 2026-09-14 设计与开发对齐：动态注入的描述同样参与关键字命中（与 L2 §5.4 一致），
        // 因此 call_versatile 的话术必须取关键字条目；命中文案作为 Allure 参数留证。
        assertThat(run.events("call_versatile", "tool_end"))
                .as("keyword entry must win for the injected description")
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.path("content").asText()).isEqualTo("DOWNSTREAM_KEY_END"));
        Allure.parameter("legacyEventContents", String.join("|", contents));
    }

    private static Path markerPath(String canary) {
        return MARKER;
    }

    private static String stubCommand(String mode, String canary, Path marker) {
        return scenarioPath("legacy-downstream")
                .resolve("stubs")
                .resolve("legacy_downstream_stub.sh")
                + " " + mode + " " + canary;
    }

    private static String markerLines(Path marker, String canary) throws Exception {
        assertThat(marker).as("stub marker file").exists();
        String content = Files.readString(marker, StandardCharsets.UTF_8);
        assertThat(content).as("stub marker content must belong to this case").contains(canary);
        return content;
    }
}
