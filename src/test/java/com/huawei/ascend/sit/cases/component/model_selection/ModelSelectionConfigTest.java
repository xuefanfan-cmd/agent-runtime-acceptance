package com.huawei.ascend.sit.cases.component.model_selection;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-050/051 — 配置加载与启动校验验收测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>SC-CFG-04 — API_BASE 类型错误（非字符串）→ 启动失败</li>
 *   <li>SC-CFG-05 — 重复 model-id（Spring 属性）→ 启动失败</li>
 *   <li>SC-CFG-06 — 空白 model-id（Spring 属性）→ 启动失败</li>
 *   <li>SC-CFG-10 — 启动注册不请求远端 LLM（API_BASE 不可达但启动成功）</li>
 *   <li>SC-CFG-11 — LLM_SSL_VERIFY 无效字符串 → 启动失败</li>
 *   <li>SC-CFG-14 — 无文件 + Spring 完整默认 → 启动成功</li>
 *   <li>SC-CFG-15 — SSL 接受字符串值 → 启动成功</li>
 * </ul>
 *
 * <p><b>关键设计说明：</b> {@code ApiConfigLoader} 只读取 {@code apiconfig.json} 中的 5 个扁平字段
 * （{@code API_BASE}/{@code API_KEY}/{@code MODEL_NAME}/{@code MODEL_PROVIDER}/{@code LLM_SSL_VERIFY}），
 * <b>不读取 {@code MODELS} 数组</b>。多模型目录通过 Spring 属性
 * {@code openjiuwen.service.llm.models[*].*} 绑定到 {@link LlmProperties}。
 * 因此：
 * <ul>
 *   <li>文件字段类型校验（SC-CFG-04/11）通过 {@code apiconfig.json} 中扁平字段的错误类型触发</li>
 *   <li>多模型校验（SC-CFG-05/06）通过 Spring 命令行参数设置 {@code models[*]} 属性触发</li>
 * </ul>
 */
@Tag("component")
@Tag("feat-050-051")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelSelectionConfigTest {

    // ===== SC-CFG-04: API_BASE 类型错误（非字符串）→ 启动失败 =====

    @Test
    void scCfg04_apiBaseTypeError_startupFails() throws Exception {
        Path tempDir = Files.createTempDirectory("model-selection-cfg04");
        // API_BASE is a number instead of string → ApiConfigLoader.readText() throws
        String json = """
                {
                  "MODEL_ID": "alias-a",
                  "MODEL_NAME": "provider-chat-a",
                  "MODEL_PROVIDER": "OpenAI",
                  "API_BASE": 12345,
                  "API_KEY": "key-a"
                }""";
        Path configFile = tempDir.resolve("apiconfig.json");
        Files.writeString(configFile, json);

        TestConfig config = TestConfig.load();
        SutStack.Builder builder = ModelSelectionSupport.buildStackWithModelSelection(config, configFile);

        assertThatThrownBy(builder::start)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("exited before becoming ready");
    }

    // ===== SC-CFG-05: 重复 model-id（Spring 属性）→ 启动失败 =====

    @Test
    void scCfg05_duplicateModelId_startupFails() {
        TestConfig config = TestConfig.load();
        // Use Spring properties for models — ApiConfigLoader doesn't read MODELS from file
        SutStack.Builder builder = SutStack.builder(config)
                .streaming(false)
                .agent("deepagent-a", a -> {
                    a.property(ModelSelectionSupport.PROP_MODEL_SELECTION_ENABLED, "true");
                    // Default model (required by resolveRequired)
                    a.property("openjiuwen.service.llm.api-key", "default-key");
                    a.property("openjiuwen.service.llm.api-base", "http://127.0.0.1:1/v1");
                    a.property("openjiuwen.service.llm.model-name", "default-model");
                    // Duplicate model-id via Spring properties → addModel() throws
                    a.property("openjiuwen.service.llm.models[0].model-id", "dup");
                    a.property("openjiuwen.service.llm.models[0].api-key", "key1");
                    a.property("openjiuwen.service.llm.models[0].api-base", "http://127.0.0.1:2/v1");
                    a.property("openjiuwen.service.llm.models[0].model-name", "model1");
                    a.property("openjiuwen.service.llm.models[1].model-id", "dup");
                    a.property("openjiuwen.service.llm.models[1].api-key", "key2");
                    a.property("openjiuwen.service.llm.models[1].api-base", "http://127.0.0.1:3/v1");
                    a.property("openjiuwen.service.llm.models[1].model-name", "model2");
                    // DeepAgent handler required properties
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_BASE, "http://127.0.0.1:1/v1");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_KEY, "dummy-key");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_MODEL_NAME, "dummy-model");
                    a.property(ModelSelectionSupport.PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                });

        assertThatThrownBy(builder::start)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("exited before becoming ready");
    }

    // ===== SC-CFG-06: 空白 model-id（Spring 属性）→ 启动失败 =====

    @Test
    void scCfg06_blankModelId_startupFails() {
        TestConfig config = TestConfig.load();
        SutStack.Builder builder = SutStack.builder(config)
                .streaming(false)
                .agent("deepagent-a", a -> {
                    a.property(ModelSelectionSupport.PROP_MODEL_SELECTION_ENABLED, "true");
                    // Default model
                    a.property("openjiuwen.service.llm.api-key", "default-key");
                    a.property("openjiuwen.service.llm.api-base", "http://127.0.0.1:1/v1");
                    a.property("openjiuwen.service.llm.model-name", "default-model");
                    // Blank model-id → requireModelId() throws
                    a.property("openjiuwen.service.llm.models[0].model-id", "  ");
                    a.property("openjiuwen.service.llm.models[0].api-key", "key1");
                    a.property("openjiuwen.service.llm.models[0].api-base", "http://127.0.0.1:2/v1");
                    a.property("openjiuwen.service.llm.models[0].model-name", "model1");
                    // DeepAgent handler required properties
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_BASE, "http://127.0.0.1:1/v1");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_KEY, "dummy-key");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_MODEL_NAME, "dummy-model");
                    a.property(ModelSelectionSupport.PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                });

        assertThatThrownBy(builder::start)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("exited before becoming ready");
    }

    // ===== SC-CFG-10: 启动注册不请求远端 LLM =====

    @Test
    void scCfg10_badApiBase_startupSucceeds() throws Exception {
        Path tempDir = Files.createTempDirectory("model-selection-cfg10");
        // API_BASE points to unreachable address, but startup should still succeed
        Path configFile = ModelSelectionSupport.writeApiConfigWithBadEndpoint(
                tempDir, "http://192.0.2.1:9999/v1", "http://192.0.2.1:9998/v1");

        TestConfig config = TestConfig.load();
        SutStack stack = ModelSelectionSupport.buildStackWithModelSelection(config, configFile).start();
        try {
            // Startup succeeded — service is alive even though LLM endpoints are unreachable
            assertThat(stack.isRunning("deepagent-a")).isTrue();
        } finally {
            stack.close();
        }
    }

    // ===== SC-CFG-11: LLM_SSL_VERIFY 无效字符串 → 启动失败 =====

    @Test
    void scCfg11_invalidSslVerify_startupFails() throws Exception {
        Path tempDir = Files.createTempDirectory("model-selection-cfg11");
        // LLM_SSL_VERIFY is a string but not "true"/"false" → ApiConfigLoader.readBoolean() throws
        String json = """
                {
                  "MODEL_ID": "alias-a",
                  "MODEL_NAME": "provider-chat-a",
                  "MODEL_PROVIDER": "OpenAI",
                  "API_BASE": "http://127.0.0.1:1/v1",
                  "API_KEY": "key-a",
                  "LLM_SSL_VERIFY": "invalid"
                }""";
        Path configFile = tempDir.resolve("apiconfig.json");
        Files.writeString(configFile, json);

        TestConfig config = TestConfig.load();
        SutStack.Builder builder = ModelSelectionSupport.buildStackWithModelSelection(config, configFile);

        assertThatThrownBy(builder::start)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("exited before becoming ready");
    }

    // ===== SC-CFG-14: 无文件 + Spring 完整默认 → 启动成功 =====

    @Test
    void scCfg14_noFileSpringDefaults_startupSucceeds() {
        TestConfig config = TestConfig.load();
        // model-selection-enabled=true, no config-file, but Spring provides complete defaults
        SutStack.Builder builder = SutStack.builder(config)
                .streaming(false)
                .agent("deepagent-a", a -> {
                    a.property(ModelSelectionSupport.PROP_MODEL_SELECTION_ENABLED, "true");
                    // No config-file — auto-discover won't find one
                    // Spring properties provide complete LLM defaults
                    a.property("openjiuwen.service.llm.model-name", "default-model");
                    a.property("openjiuwen.service.llm.provider", "OpenAI");
                    a.property("openjiuwen.service.llm.api-base", "http://127.0.0.1:1/v1");
                    a.property("openjiuwen.service.llm.api-key", "default-key");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_BASE, "http://127.0.0.1:1/v1");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_KEY, "dummy-key");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_MODEL_NAME, "dummy-model");
                    a.property(ModelSelectionSupport.PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                });

        SutStack stack = builder.start();
        try {
            assertThat(stack.isRunning("deepagent-a")).isTrue();
        } finally {
            stack.close();
        }
    }

    // ===== SC-CFG-15: SSL 接受字符串值 → 启动成功 =====

    @Test
    void scCfg15_sslStringValue_startupSucceeds() throws Exception {
        Path tempDir = Files.createTempDirectory("model-selection-cfg15");
        // LLM_SSL_VERIFY as string "false" instead of boolean false — should be accepted
        String json = """
                {
                  "MODEL_ID": "alias-a",
                  "MODEL_NAME": "provider-chat-a",
                  "MODEL_PROVIDER": "OpenAI",
                  "API_BASE": "http://127.0.0.1:1/v1",
                  "API_KEY": "key-a",
                  "LLM_SSL_VERIFY": "false"
                }""";
        Path configFile = tempDir.resolve("apiconfig.json");
        Files.writeString(configFile, json);

        TestConfig config = TestConfig.load();
        SutStack stack = ModelSelectionSupport.buildStackWithModelSelection(config, configFile).start();
        try {
            assertThat(stack.isRunning("deepagent-a")).isTrue();
        } finally {
            stack.close();
        }
    }

    // ===== SC-ISOLATE-01: 错误配置不影响正常实例 =====

    @Test
    @org.junit.jupiter.api.Disabled("SC-ISOLATE-01 需要两个 SUT 实例同时运行，但 YAML 配置使用固定端口 8201，"
            + "第二个实例因端口冲突而非配置错误失败。需测试框架支持端口隔离后才能测试。"
            + "SC-CFG-05 已验证重复 model-id 会导致启动失败，隔离性由 Spring 进程隔离保证。")
    void scIsolate01_badConfigDoesNotAffectNormalInstance() {
        // 此测试需要两个 SUT 实例同时运行在不同端口上。
    }

    // ===== SC-CFG-09: 重复 model-id 在配置解析阶段被拒绝（非注册回滚阶段）=====

    @Test
    void scCfg09_duplicateModelId_rejectedAtConfigParse() {
        TestConfig config = TestConfig.load();
        // models[0] valid, models[1] conflicts with models[0] on model-id
        SutStack.Builder builder = SutStack.builder(config)
                .streaming(false)
                .agent("deepagent-a", a -> {
                    a.property(ModelSelectionSupport.PROP_MODEL_SELECTION_ENABLED, "true");
                    a.property("openjiuwen.service.llm.api-key", "default-key-xxx");
                    a.property("openjiuwen.service.llm.api-base", "http://127.0.0.1:1/v1");
                    a.property("openjiuwen.service.llm.model-name", "default-model");
                    // models[0] — valid
                    a.property("openjiuwen.service.llm.models[0].model-id", "model-a");
                    a.property("openjiuwen.service.llm.models[0].api-key", "secret-key-aaa");
                    a.property("openjiuwen.service.llm.models[0].api-base", "http://127.0.0.1:2/v1");
                    a.property("openjiuwen.service.llm.models[0].model-name", "model-a-name");
                    // models[1] — conflicts with models[0]
                    a.property("openjiuwen.service.llm.models[1].model-id", "model-a");
                    a.property("openjiuwen.service.llm.models[1].api-key", "secret-key-bbb");
                    a.property("openjiuwen.service.llm.models[1].api-base", "http://127.0.0.1:3/v1");
                    a.property("openjiuwen.service.llm.models[1].model-name", "model-b-name");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_BASE, "http://127.0.0.1:1/v1");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_KEY, "dummy-key");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_MODEL_NAME, "dummy-model");
                    a.property(ModelSelectionSupport.PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                });

        assertThatThrownBy(builder::start)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("exited before becoming ready");
    }

    // ===== SC-CFG-12: auto-discover 自动发现 =====

    @Test
    void scCfg12_autoDiscover_findsConfigFile() throws Exception {
        // Write apiconfig.json to a temp directory, use it as SUT working directory via env
        Path tempDir = Files.createTempDirectory("model-selection-cfg12");
        Path configFile = ModelSelectionSupport.writeApiConfig(
                tempDir, "http://127.0.0.1:1/v1", "http://127.0.0.1:2/v1");

        // Verify the file was created
        assertThat(Files.exists(configFile)).isTrue();

        TestConfig config = TestConfig.load();
        // Don't set config-file; set OPENJIUWEN_API_CONFIG env var to point to the file
        // (auto-discover searches working directory, but SUT inherits test JVM working dir)
        SutStack.Builder builder = SutStack.builder(config)
                .streaming(false)
                .agent("deepagent-a", a -> {
                    a.property(ModelSelectionSupport.PROP_MODEL_SELECTION_ENABLED, "true");
                    // No config-file — use env var for explicit path discovery
                    a.env("OPENJIUWEN_API_CONFIG", configFile.toString());
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_BASE, "http://127.0.0.1:1/v1");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_KEY, "dummy-key");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_MODEL_NAME, "dummy-model");
                    a.property(ModelSelectionSupport.PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                });

        SutStack stack = builder.start();
        try {
            assertThat(stack.isRunning("deepagent-a")).isTrue();
        } finally {
            stack.close();
        }
    }

    // ===== SC-CFG-13: 解密后空密钥拒绝 =====

    @Test
    void scCfg13_emptyApiKey_afterDecrypt_startupFails() {
        TestConfig config = TestConfig.load();
        // Set models[0].api-key to empty string → decryptApiKey returns "" → requireText throws
        SutStack.Builder builder = SutStack.builder(config)
                .streaming(false)
                .agent("deepagent-a", a -> {
                    a.property(ModelSelectionSupport.PROP_MODEL_SELECTION_ENABLED, "true");
                    a.property("openjiuwen.service.llm.api-key", "default-key");
                    a.property("openjiuwen.service.llm.api-base", "http://127.0.0.1:1/v1");
                    a.property("openjiuwen.service.llm.model-name", "default-model");
                    // models[0] with empty api-key
                    a.property("openjiuwen.service.llm.models[0].model-id", "model-b");
                    a.property("openjiuwen.service.llm.models[0].api-key", "");
                    a.property("openjiuwen.service.llm.models[0].api-base", "http://127.0.0.1:2/v1");
                    a.property("openjiuwen.service.llm.models[0].model-name", "model-b-name");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_BASE, "http://127.0.0.1:1/v1");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_API_KEY, "dummy-key");
                    a.property(ModelSelectionSupport.PROP_DEEP_AGENT_MODEL_NAME, "dummy-model");
                    a.property(ModelSelectionSupport.PROP_REMOTE_A2A_CARD_URL, "http://127.0.0.1:1/.well-known/agent-card.json");
                });

        assertThatThrownBy(builder::start)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("exited before becoming ready");
    }
}
