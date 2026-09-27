package com.huawei.ascend.sit.cases.integration.tscript;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ToolScriptFixtureSupport {
    private ToolScriptFixtureSupport() {
    }

    static SutStack.Builder stackBuilder(TestConfig config, String sut, String scenario, String providerMode) {
        return stackBuilder(config, sut, scenario, providerMode, null);
    }

    /**
     * Same stack as {@link #stackBuilder(TestConfig, String, String, String)} with an optional model
     * base URL override, used by tests that insert a local counting endpoint in front of the real LLM.
     */
    static SutStack.Builder stackBuilder(TestConfig config, String sut, String scenario, String providerMode,
                                         String modelBaseUrl) {
        return SutStack.builder(config).agent(sut, agent -> {
            agent.env("EDP_AGENT_SCENARIO_HOME", AbstractToolScriptSitTest.scenarioPath(scenario).toString());
            agent.serviceBinding("redis", "deep-agent.redis.host", "{{host}}")
                    .serviceBinding("redis", "deep-agent.redis.port", "{{port}}");
            if (providerMode != null && !providerMode.isBlank()) {
                agent.env("TSCRIPT_PROVIDER_MODE", providerMode);
            }
            if (modelBaseUrl != null && !modelBaseUrl.isBlank()) {
                agent.env("EDP_AGENT_MODEL_BASE_URL", modelBaseUrl);
            }
        });
    }

    static String log(SutStack stack, String sut) throws IOException {
        SutInstance instance = stack.managedInstance(sut);
        if (!(instance instanceof ManagedSutInstance managed)
                || !Files.isRegularFile(managed.logFile())) {
            return "";
        }
        StringBuilder log = new StringBuilder(Files.readString(managed.logFile()));
        Path parent = managed.logFile().getParent();
        if (parent != null) {
            Path runLog = parent.resolve("run").resolve("run.log");
            if (Files.isRegularFile(runLog)) {
                log.append(System.lineSeparator()).append(Files.readString(runLog));
            }
        }
        return log.toString();
    }
}
