package com.huawei.ascend.sit.cases.integration.tscript;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@Tag("tscript")
@Tag("feat-039")
@Feature("FEAT-039: 业务扩展工具话术（EDPA）")
class ToolScriptScenarioOverrideTest extends AbstractToolScriptSitTest {
    @Override
    protected String scenarioName() {
        return "keyword-override";
    }

    @Test
    @Story("ts039.keyword-scene-override: 场景级按 intent 整体覆盖框架级")
    @DisplayName("TS039-C12: scenario intent replacement does not field-merge")
    void scenarioIntentReplacementDoesNotFieldMerge() throws Exception {
        assertPair(invokeLegacy("call_versatile", "intent_a", "FRAMEWORK_KEY"),
                "call_versatile", "SCENE_A_START", "SCENE_A_END");
        assertPair(invokeLegacy("call_versatile", "intent_b", "FRAMEWORK_B_KEY"),
                "call_versatile", "FRAMEWORK_B_KEY_START", "FRAMEWORK_B_KEY_END");
    }
}
