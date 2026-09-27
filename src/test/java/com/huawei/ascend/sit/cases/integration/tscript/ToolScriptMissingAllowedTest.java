package com.huawei.ascend.sit.cases.integration.tscript;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Tag("tscript")
@Tag("feat-039")
@Feature("FEAT-039: 业务扩展工具话术（EDPA）")
class ToolScriptMissingAllowedTest extends AbstractToolScriptSitTest {
    @Override
    protected String scenarioName() {
        return "missing-allowed";
    }

    @Test
    @Story("ts039.trio-missing-allowedtools: 缺 allowed_tools 时不注册且不发话术")
    @DisplayName("TS039-C03: an SPI provider excluded by allowed_tools remains unavailable")
    void providerExcludedByAllowedToolsRemainsUnavailable() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_probe", "plain", "finance");
        assertThat(run.events("tscript_probe", "tool_start")).isEmpty();
        assertThat(run.events("tscript_probe", "tool_end")).isEmpty();
        assertThat(counts().getOrDefault("execute.tscript_probe", 0)).isZero();
    }
}
