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
class ToolScriptMissingSpiTest extends AbstractToolScriptSitTest {
    @Override
    protected String scenarioName() {
        return "missing-spi";
    }

    @Test
    @Story("ts039.trio-missing-spi: 缺 SPI 时不注册且不发话术")
    @DisplayName("TS039-C02: allowed name without an SPI provider remains unavailable")
    void allowedNameWithoutProviderRemainsUnavailable() throws Exception {
        ToolScriptWireClient.Run run = invoke("tscript_missing_provider", "plain", "finance");
        assertThat(run.scriptEvents()).isEmpty();
        assertThat(counts().keySet()).noneMatch(key -> key.contains("tscript_missing_provider"));
    }
}
