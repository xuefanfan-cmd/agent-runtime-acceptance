package com.huawei.ascend.sit.cases.integration.tscript;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TS038-C16 probe: the script capability must not add model calls.
 *
 * <p>The SUT model base is pointed at {@link LlmCountingProxy}, which relays to the real LLM unchanged
 * and only counts forwarded calls. The SUT therefore still talks to the real model; no response is
 * mocked or rewritten.
 */
@Tag("integration")
@Tag("tscript")
@Tag("feat-038")
@Feature("FEAT-038: 业务扩展工具话术")
class ToolScriptLlmCallCountTest extends AbstractToolScriptSitTest {
    private static LlmCountingProxy proxy;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return ToolScriptFixtureSupport.stackBuilder(config, sutName(), scenarioName(), providerMode(),
                countingProxy().baseUrl());
    }

    @AfterAll
    static void stopCountingProxy() {
        if (proxy != null) {
            proxy.close();
            proxy = null;
        }
    }

    @Test
    @Story("ts038.zero-llm: 话术能力零额外模型调用")
    @DisplayName("TS038-C16: a scripted tool adds no extra model call")
    void scriptedToolAddsNoExtraModelCall() throws Exception {
        LlmCountingProxy counter = countingProxy();

        counter.reset();
        invoke("tscript_probe", "plain", "finance");
        int scriptedCalls = counter.calls();
        assertThat(counts().getOrDefault("execute.tscript_probe", 0))
                .as("the scripted tool must actually run")
                .isPositive();

        counter.reset();
        invoke("tscript_no_scripts", "plain", null);
        int unscriptedCalls = counter.calls();
        assertThat(counts().getOrDefault("execute.tscript_no_scripts", 0))
                .as("the unscripted tool must actually run")
                .isPositive();

        assertThat(scriptedCalls).as("scripted tool model calls").isPositive();
        assertThat(unscriptedCalls).as("unscripted tool model calls").isPositive();
        assertThat(scriptedCalls)
                .as("counted paths %s", counter.callsByPath())
                .isEqualTo(unscriptedCalls);
    }

    private static synchronized LlmCountingProxy countingProxy() {
        if (proxy == null) {
            try {
                proxy = LlmCountingProxy.start();
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to start the model call counting proxy", failure);
            }
        }
        return proxy;
    }
}
