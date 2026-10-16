package com.huawei.ascend.sit.cases.component.model_selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.huawei.ascend.sit.utils.JsonUtils;

/**
 * Shared constants and apiconfig.json generation for FEAT-050/051 model-selection acceptance tests.
 *
 * <p>Generates the deployment-level model catalog file ({@code apiconfig.json}) that
 * {@code LlmConfigResolver} reads at startup, and provides a {@link SutStack} builder
 * that injects the correct Spring properties for {@code model-selection-enabled}
 * and {@code config-file}.</p>
 */
final class ModelSelectionSupport {

    // ---- Public alias (MODEL_ID) vs provider model name (MODEL_NAME) ----
    static final String ALIAS_A = "alias-a";
    static final String ALIAS_B = "alias-b";
    static final String PROVIDER_MODEL_A = "provider-chat-a";
    static final String PROVIDER_MODEL_B = "provider-chat-b";

    // ---- Mock response content ----
    static final String RESPONSE_A = "MODEL_A_RESPONSE";
    static final String RESPONSE_B = "MODEL_B_RESPONSE";

    // ---- Test API keys (fake, non-sensitive) ----
    static final String API_KEY_A = "test-key-a";
    static final String API_KEY_B = "test-key-b";

    // ---- Spring property keys ----
    static final String PROP_MODEL_SELECTION_ENABLED = "openjiuwen.service.llm.model-selection-enabled";
    static final String PROP_CONFIG_FILE = "openjiuwen.service.llm.config-file";
    static final String PROP_MAINPLAN_API_KEY = "main-plan-agent.api-key";
    static final String PROP_MAINPLAN_API_BASE = "main-plan-agent.api-base";
    static final String PROP_MAINPLAN_MODEL_NAME = "main-plan-agent.model-name";

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    private ModelSelectionSupport() {
    }

    /** Serialize a map to compact JSON (for REST query bodies). */
    static String toJson(Object value) {
        return JsonUtils.toJsonCompact(value);
    }

    /**
     * Generate an {@code apiconfig.json} with root default = alias-a and MODELS list = [alias-b],
     * pointing at the given mock endpoints.
     *
     * @param dir       directory to write the file into (created if absent)
     * @param mockAUrl  base URL of mock endpoint A (e.g. {@code http://127.0.0.1:38211/v1})
     * @param mockBUrl  base URL of mock endpoint B
     * @return the absolute path to the generated file
     */
    static Path writeApiConfig(Path dir, String mockAUrl, String mockBUrl) throws IOException {
        Files.createDirectories(dir);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("MODEL_ID", ALIAS_A);
        root.put("MODEL_NAME", PROVIDER_MODEL_A);
        root.put("MODEL_PROVIDER", "OpenAI");
        root.put("API_BASE", mockAUrl);
        root.put("API_KEY", API_KEY_A);
        root.put("LLM_SSL_VERIFY", false);

        Map<String, Object> modelB = new LinkedHashMap<>();
        modelB.put("MODEL_ID", ALIAS_B);
        modelB.put("MODEL_NAME", PROVIDER_MODEL_B);
        modelB.put("MODEL_PROVIDER", "OpenAI");
        modelB.put("API_BASE", mockBUrl);
        modelB.put("API_KEY", API_KEY_B);
        modelB.put("LLM_SSL_VERIFY", false);

        root.put("MODELS", java.util.List.of(modelB));

        Path file = dir.resolve("apiconfig.json");
        JSON.writeValue(file.toFile(), root);
        return file;
    }

    /**
     * Generate an {@code apiconfig.json} with root default = alias-a pointing at an
     * unreachable address, and MODELS list = [alias-b] pointing at the given mock endpoint.
     * Used for failure-injection tests where alias-b must fail.
     *
     * @param dir            directory to write the file into
     * @param unreachableUrl an unreachable API_BASE for alias-a (not used in failure tests,
     *                       but root default must be present for startup)
     * @param mockBUrl       base URL of mock endpoint B (may be set to error mode)
     * @return the absolute path to the generated file
     */
    static Path writeApiConfigWithBadEndpoint(Path dir, String unreachableUrl, String mockBUrl)
            throws IOException {
        Files.createDirectories(dir);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("MODEL_ID", ALIAS_A);
        root.put("MODEL_NAME", PROVIDER_MODEL_A);
        root.put("MODEL_PROVIDER", "OpenAI");
        root.put("API_BASE", unreachableUrl);
        root.put("API_KEY", API_KEY_A);
        root.put("LLM_SSL_VERIFY", false);

        Map<String, Object> modelB = new LinkedHashMap<>();
        modelB.put("MODEL_ID", ALIAS_B);
        modelB.put("MODEL_NAME", PROVIDER_MODEL_B);
        modelB.put("MODEL_PROVIDER", "OpenAI");
        modelB.put("API_BASE", mockBUrl);
        modelB.put("API_KEY", API_KEY_B);
        modelB.put("LLM_SSL_VERIFY", false);

        root.put("MODELS", java.util.List.of(modelB));

        Path file = dir.resolve("apiconfig.json");
        JSON.writeValue(file.toFile(), root);
        return file;
    }

    // ---- deepagent-a required Spring properties (dummy values; not used when model-selection is on)
    static final String PROP_DEEP_AGENT_API_BASE = "openjiuwen.demo.deep-agent.llm.api-base";
    static final String PROP_DEEP_AGENT_API_KEY = "openjiuwen.demo.deep-agent.llm.api-key";
    static final String PROP_DEEP_AGENT_MODEL_NAME = "openjiuwen.demo.deep-agent.llm.model-name";
    static final String PROP_REMOTE_A2A_CARD_URL = "REMOTE_A2A_CARD_URL";

    /**
     * Build a SutStack with the deepagent-a agent and model-selection enabled, pointing at the
     * given apiconfig.json path.
     */
    static SutStack.Builder buildStackWithModelSelection(TestConfig config, Path configFile) {
        return SutStack.builder(config)
                .streaming(false)
                .agent("deepagent-a", a -> {
                    a.property(PROP_MODEL_SELECTION_ENABLED, "true");
                    a.property(PROP_CONFIG_FILE, configFile.toString());
                    // deepagent-a requires these properties at startup validation even when model-selection is on
                    a.property(PROP_DEEP_AGENT_API_BASE, "http://127.0.0.1:1/v1");
                    a.property(PROP_DEEP_AGENT_API_KEY, "dummy-key");
                    a.property(PROP_DEEP_AGENT_MODEL_NAME, "dummy-model");
                    a.property(PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                    a.property("openjiuwen.service.query.webflux.enabled", "true");
                });
    }

    /**
     * Build a SutStack with the deepagent-a agent and model-selection disabled, using
     * real LLM connection via Spring properties (compat test).
     */
    static SutStack.Builder buildStackWithoutModelSelection(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent("deepagent-a", a -> {
                    a.property(PROP_DEEP_AGENT_API_BASE, "https://ws-z7f9qzn05hmkiq2l.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
                    a.property(PROP_DEEP_AGENT_API_KEY, "sk-ws-H.EYDDYHI.HYqI.MEUCIEKAH1AhoakSi8o1BKKvnj1xnRr9DKAbV6Qs5lWu1fxXAiEAv8YfUh2zA6d0LMvQtR7fDpa4IvBP95etVohKVupZ9R8");
                    a.property(PROP_DEEP_AGENT_MODEL_NAME, "qwen3.8-max");
                    a.property(PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                });
    }
}
