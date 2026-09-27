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
class ToolScriptSkillIsolationTest extends AbstractToolScriptSitTest {
    @Override
    protected String scenarioName() {
        return "skill-isolation";
    }

    @Test
    @Story("ts039.typed-merge-skill-isolation: typed 合并与 Skill 扁平话术隔离")
    @DisplayName("TS039-C27: Skill flat scripts do not overwrite typed intent snapshots")
    void skillFlatScriptsDoNotOverwriteTypedIntentSnapshots() throws Exception {
        assertPair(invokeLegacy("call_versatile", "intent_a", "NO_MATCH"),
                "call_versatile", "TYPED_SCENE_A_START", "TYPED_SCENE_A_END");
        assertPair(invokeLegacy("call_versatile", "intent_b", "FRAMEWORK_B_KEY"),
                "call_versatile", "FRAMEWORK_B_KEY_START", "FRAMEWORK_B_KEY_END");
    }
}
