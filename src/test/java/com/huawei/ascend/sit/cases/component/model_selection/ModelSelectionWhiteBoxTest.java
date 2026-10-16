package com.huawei.ascend.sit.cases.component.model_selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.llm.LlmModelCatalog;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.LlmProperties;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-050/051 — White-box unit tests for PR #217 core classes.
 *
 * <p>Directly tests the model-selection implementation classes without launching a demo
 * agent JVM. Covers parameter validation, catalog construction, config resolution, and
 * edge-case handling.
 *
 * <p>Test groups:
 * <ul>
 *   <li>{@link NormalizeModelNameTests} — SC-PARM-01/02/03/04/13: model_name validation</li>
 *   <li>{@link LlmModelCatalogTests} — SC-CORE-01~09: catalog construction validation</li>
 *   <li>{@link LlmConfigResolverTests} — SC-CFG-01/02/03/09: catalog resolution & caching</li>
 * </ul>
 */
@Tag("component")
@Tag("feat-050-051")
@Tag("white-box")
@DisplayName("FEAT-050/051 白盒测试 — PR #217 核心类")
class ModelSelectionWhiteBoxTest {

    // ---- helpers ----

    private static final CredentialDecryptor PASSTHROUGH = ciphertext -> ciphertext;

    private static LlmModelCatalog.ModelDefinition modelDef(String id) {
        return new LlmModelCatalog.ModelDefinition("OpenAI", "key-" + id,
            "https://" + id + ".example/v1", "model-" + id, true);
    }

    private static LlmProperties configuredProperties() {
        LlmProperties props = new LlmProperties();
        props.setApiKey("spring-key");
        props.setApiBase("https://spring.example/v1");
        props.setModelName("spring-model");
        return props;
    }

    private static LlmConfigResolver resolver(LlmProperties props, CredentialDecryptor decryptor) {
        return new LlmConfigResolver(props, new MockEnvironment(), decryptor);
    }

    // =================================================================
    // 1. ServeRequest.normalizeModelName — SC-PARM-01/02/03/04/13
    // =================================================================

    @Nested
    @DisplayName("SC-PARM: model_name 参数验证 (ServeRequest.normalizeModelName)")
    class NormalizeModelNameTests {

        @Test
        @DisplayName("SC-PARM-01: null → Optional.empty() (使用默认模型)")
        void null_returnsEmpty() {
            assertThat(ServeRequest.normalizeModelName(null)).isEmpty();
        }

        @Test
        @DisplayName("SC-PARM-02: 合法字符串 → Optional.of(原值)")
        void validString_returnsValue() {
            assertThat(ServeRequest.normalizeModelName("alias-b")).hasValue("alias-b");
        }

        @Test
        @DisplayName("SC-PARM-03a: 空字符串 → IllegalArgumentException")
        void emptyString_throws() {
            assertThatThrownBy(() -> ServeRequest.normalizeModelName(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("SC-PARM-03b: 纯空白字符串 → IllegalArgumentException")
        void blankString_throws() {
            assertThatThrownBy(() -> ServeRequest.normalizeModelName("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("SC-PARM-03c: tab/换行空白字符串 → IllegalArgumentException")
        void tabNewlineBlank_throws() {
            assertThatThrownBy(() -> ServeRequest.normalizeModelName("\t\n\r "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("SC-PARM-04a: Integer → IllegalArgumentException")
        void integer_throws() {
            assertThatThrownBy(() -> ServeRequest.normalizeModelName(123))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("SC-PARM-04b: Boolean → IllegalArgumentException")
        void boolean_throws() {
            assertThatThrownBy(() -> ServeRequest.normalizeModelName(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("SC-PARM-04c: Double → IllegalArgumentException")
        void doubleValue_throws() {
            assertThatThrownBy(() -> ServeRequest.normalizeModelName(3.14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("SC-PARM-04d: List → IllegalArgumentException")
        void list_throws() {
            assertThatThrownBy(() -> ServeRequest.normalizeModelName(List.of("a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("SC-PARM-04e: Map → IllegalArgumentException")
        void map_throws() {
            assertThatThrownBy(() -> ServeRequest.normalizeModelName(Map.of("k", "v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("SC-PARM-13a: 前后空格 → trim 后返回")
        void leadingTrailingSpaces_trimmed() {
            assertThat(ServeRequest.normalizeModelName(" alias-b ")).hasValue("alias-b");
        }

        @Test
        @DisplayName("SC-PARM-13b: tab 前缀 → trim 后返回")
        void tabPrefix_trimmed() {
            assertThat(ServeRequest.normalizeModelName("\talias-b")).hasValue("alias-b");
        }

        @Test
        @DisplayName("SC-PARM-13c: 多层空白包裹 → trim 后返回")
        void multipleWhitespace_trimmed() {
            assertThat(ServeRequest.normalizeModelName("  \t alias-b \n ")).hasValue("alias-b");
        }

        @Test
        @DisplayName("SC-PARM-13d: 内部空格保留")
        void internalSpace_preserved() {
            assertThat(ServeRequest.normalizeModelName(" alias b ")).hasValue("alias b");
        }

        @Test
        @DisplayName("SC-PARM-13e: 中文字符串 → 原样返回（trim 仅去除空白）")
        void chineseString_returned() {
            assertThat(ServeRequest.normalizeModelName("模型A")).hasValue("模型A");
        }

        @Test
        @DisplayName("SC-PARM-13f: 前后中文空格 → trim 后返回 (\\u3000)")
        void ideographicSpace_notTrimmedByStringTrim() {
            // \u3000 is an ideographic space (full-width space)
            // Java's String.trim() removes characters <= \u0020 (space), NOT \u3000
            // So "　alias-b　".trim() returns "　alias-b　" — this is a known behavior
            String input = "\u3000alias-b\u3000";
            Optional<String> result = ServeRequest.normalizeModelName(input);
            // \u3000 is > \u0020, so trim() does NOT remove it
            // The string is non-empty after trim, so it should be returned as-is
            assertThat(result).hasValue("\u3000alias-b\u3000");
        }
    }

    // =================================================================
    // 2. LlmModelCatalog — SC-CORE-01~09
    // =================================================================

    @Nested
    @DisplayName("SC-CORE: LlmModelCatalog 构造验证")
    class LlmModelCatalogTests {

        @Test
        @DisplayName("SC-CORE-01: 单模型 catalog 构造成功")
        void singleModel_catalogCreated() {
            Map<String, LlmModelCatalog.ModelDefinition> models = new LinkedHashMap<>();
            models.put("default", modelDef("default"));
            LlmModelCatalog catalog = new LlmModelCatalog("default", models,
                0.6, 0.8, Duration.ofSeconds(60));

            assertThat(catalog.defaultId()).isEqualTo("default");
            assertThat(catalog.models()).hasSize(1).containsKey("default");
            assertThat(catalog.temperature()).isEqualTo(0.6);
            assertThat(catalog.topP()).isEqualTo(0.8);
            assertThat(catalog.timeout()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("SC-CORE-02: 多模型 catalog 构造成功")
        void multipleModels_catalogCreated() {
            Map<String, LlmModelCatalog.ModelDefinition> models = new LinkedHashMap<>();
            models.put("alias-a", modelDef("alias-a"));
            models.put("alias-b", modelDef("alias-b"));
            LlmModelCatalog catalog = new LlmModelCatalog("alias-a", models,
                0.6, 0.8, Duration.ofSeconds(60));

            assertThat(catalog.defaultId()).isEqualTo("alias-a");
            assertThat(catalog.models()).hasSize(2)
                .containsKeys("alias-a", "alias-b");
        }

        @Test
        @DisplayName("SC-CORE-03: null defaultId → IllegalArgumentException")
        void nullDefaultId_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog(null, Map.of("a", modelDef("a")),
                0.6, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultId");
        }

        @Test
        @DisplayName("SC-CORE-04: 空白 defaultId → IllegalArgumentException")
        void blankDefaultId_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("  ", Map.of("a", modelDef("a")),
                0.6, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultId");
        }

        @Test
        @DisplayName("SC-CORE-05: defaultId 不在 models 中 → IllegalArgumentException")
        void defaultIdNotInModels_throws() {
            Map<String, LlmModelCatalog.ModelDefinition> models = new LinkedHashMap<>();
            models.put("alias-a", modelDef("alias-a"));
            assertThatThrownBy(() -> new LlmModelCatalog("alias-x", models,
                0.6, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default model");
        }

        @Test
        @DisplayName("SC-CORE-06: null models map → NullPointerException")
        void nullModelsMap_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("default", null,
                0.6, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("models");
        }

        @Test
        @DisplayName("SC-CORE-07: modelId 含前后空格 → IllegalArgumentException (must be trimmed)")
        void untrimmedModelId_throws() {
            Map<String, LlmModelCatalog.ModelDefinition> models = new LinkedHashMap<>();
            models.put(" untrimmed ", modelDef("x"));
            assertThatThrownBy(() -> new LlmModelCatalog("default", models,
                0.6, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trimmed");
        }

        @Test
        @DisplayName("SC-CORE-08: 空白 modelId → IllegalArgumentException")
        void blankModelId_throws() {
            Map<String, LlmModelCatalog.ModelDefinition> models = new LinkedHashMap<>();
            models.put("  ", modelDef("x"));
            assertThatThrownBy(() -> new LlmModelCatalog("default", models,
                0.6, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelId");
        }

        @Test
        @DisplayName("SC-CORE-09: null ModelDefinition → NullPointerException")
        void nullModelDefinition_throws() {
            Map<String, LlmModelCatalog.ModelDefinition> models = new LinkedHashMap<>();
            models.put("default", null);
            assertThatThrownBy(() -> new LlmModelCatalog("default", models,
                0.6, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(NullPointerException.class);
        }

        // --- Temperature validation ---

        @Test
        @DisplayName("SC-CORE-10a: 负数 temperature → IllegalArgumentException")
        void negativeTemperature_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("default", Map.of("default", modelDef("default")),
                -0.1, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampling");
        }

        @Test
        @DisplayName("SC-CORE-10b: NaN temperature → IllegalArgumentException")
        void nanTemperature_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("default", Map.of("default", modelDef("default")),
                Double.NaN, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampling");
        }

        @Test
        @DisplayName("SC-CORE-10c: 无穷 temperature → IllegalArgumentException")
        void infiniteTemperature_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("default", Map.of("default", modelDef("default")),
                Double.POSITIVE_INFINITY, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampling");
        }

        @Test
        @DisplayName("SC-CORE-10d: temperature=0 → 构造成功 (0 是合法值)")
        void zeroTemperature_succeeds() {
            LlmModelCatalog catalog = new LlmModelCatalog("default",
                Map.of("default", modelDef("default")), 0, 0.8, Duration.ofSeconds(60));
            assertThat(catalog.temperature()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("SC-CORE-10e: 超高 temperature=100 → 构造成功 (无上限校验)")
        void veryHighTemperature_succeeds() {
            // Note: no upper bound check — this may be a design choice or a missing validation
            LlmModelCatalog catalog = new LlmModelCatalog("default",
                Map.of("default", modelDef("default")), 100.0, 0.8, Duration.ofSeconds(60));
            assertThat(catalog.temperature()).isEqualTo(100.0);
        }

        // --- topP validation ---

        @Test
        @DisplayName("SC-CORE-11a: 负数 topP → IllegalArgumentException")
        void negativeTopP_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("default", Map.of("default", modelDef("default")),
                0.6, -0.1, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("SC-CORE-11b: topP > 1 → IllegalArgumentException")
        void topPGreaterThanOne_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("default", Map.of("default", modelDef("default")),
                0.6, 1.1, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("SC-CORE-11c: topP=0 → 构造成功")
        void zeroTopP_succeeds() {
            LlmModelCatalog catalog = new LlmModelCatalog("default",
                Map.of("default", modelDef("default")), 0.6, 0, Duration.ofSeconds(60));
            assertThat(catalog.topP()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("SC-CORE-11d: topP=1 → 构造成功")
        void oneTopP_succeeds() {
            LlmModelCatalog catalog = new LlmModelCatalog("default",
                Map.of("default", modelDef("default")), 0.6, 1.0, Duration.ofSeconds(60));
            assertThat(catalog.topP()).isEqualTo(1.0);
        }

        // --- timeout validation ---

        @Test
        @DisplayName("SC-CORE-12a: null timeout → IllegalArgumentException")
        void nullTimeout_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("default", Map.of("default", modelDef("default")),
                0.6, 0.8, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("SC-CORE-12b: zero timeout → IllegalArgumentException")
        void zeroTimeout_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("default", Map.of("default", modelDef("default")),
                0.6, 0.8, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        }

        @Test
        @DisplayName("SC-CORE-12c: 负数 timeout → IllegalArgumentException")
        void negativeTimeout_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog("default", Map.of("default", modelDef("default")),
                0.6, 0.8, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
        }

        // --- ModelDefinition validation ---

        @Test
        @DisplayName("SC-CORE-13a: blank provider → IllegalArgumentException")
        void blankProvider_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog.ModelDefinition(
                "  ", "key", "https://a.example/v1", "model-a", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider");
        }

        @Test
        @DisplayName("SC-CORE-13b: blank apiKey → IllegalArgumentException")
        void blankApiKey_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog.ModelDefinition(
                "OpenAI", "  ", "https://a.example/v1", "model-a", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
        }

        @Test
        @DisplayName("SC-CORE-13c: blank apiBase → IllegalArgumentException")
        void blankApiBase_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog.ModelDefinition(
                "OpenAI", "key", "  ", "model-a", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiBase");
        }

        @Test
        @DisplayName("SC-CORE-13d: blank modelName → IllegalArgumentException")
        void blankModelName_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog.ModelDefinition(
                "OpenAI", "key", "https://a.example/v1", "  ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelName");
        }

        @Test
        @DisplayName("SC-CORE-13e: null provider → IllegalArgumentException")
        void nullProvider_throws() {
            assertThatThrownBy(() -> new LlmModelCatalog.ModelDefinition(
                null, "key", "https://a.example/v1", "model-a", true))
                .isInstanceOf(IllegalArgumentException.class);
        }

        // --- Immutability ---

        @Test
        @DisplayName("SC-CORE-14: models map 是不可变的")
        void modelsMapIsImmutable() {
            LlmModelCatalog catalog = new LlmModelCatalog("default",
                Map.of("default", modelDef("default")), 0.6, 0.8, Duration.ofSeconds(60));
            assertThatThrownBy(() -> catalog.models().put("new", modelDef("new")))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("SC-CORE-15: ModelDefinition.toString() 不泄露凭据")
        void modelDefinitionToString_redactsCredentials() {
            LlmModelCatalog.ModelDefinition def = new LlmModelCatalog.ModelDefinition(
                "OpenAI", "super-secret-key", "https://a.example/v1", "gpt-4", true);
            assertThat(def.toString()).doesNotContain("super-secret-key");
        }

        // --- Potential Bug: defaultId not checked for trimming ---

        @Test
        @DisplayName("SC-CORE-BUG-01: defaultId 含前后空格 → 误导性错误 'Catalog must contain its default model'")
        void untrimmedDefaultId_misleadingError() {
            // defaultId = " default " (with spaces)
            // models map key = "default" (trimmed, passes validation)
            // copy.containsKey(" default ") → false → misleading error
            Map<String, LlmModelCatalog.ModelDefinition> models = new LinkedHashMap<>();
            models.put("default", modelDef("default"));
            assertThatThrownBy(() -> new LlmModelCatalog(" default ", models,
                0.6, 0.8, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                // BUG: should say "defaultId must be trimmed" but says "Catalog must contain its default model"
                .hasMessageContaining("default model");
        }
    }

    // =================================================================
    // 3. LlmConfigResolver — SC-CFG-01/02/03/09
    // =================================================================

    @Nested
    @DisplayName("SC-CFG: LlmConfigResolver 目录解析")
    class LlmConfigResolverTests {

        @TempDir
        private Path tempDir;

        @Test
        @DisplayName("SC-CFG-01: 正常 catalog 解析 — 默认模型 + 额外模型")
        void normalCatalog_defaultAndAdditionalModels() {
            LlmProperties props = configuredProperties();
            props.setModelId("my-default");

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            LlmModelCatalog catalog = resolver.resolveCatalog();

            assertThat(catalog.defaultId()).isEqualTo("my-default");
            assertThat(catalog.models()).hasSize(2)
                .containsKeys("my-default", "alias-b");
            assertThat(catalog.models().get("alias-b").apiKey()).isEqualTo("key-b");
            assertThat(catalog.models().get("alias-b").apiBase()).isEqualTo("https://b.example/v1");
            assertThat(catalog.models().get("alias-b").modelName()).isEqualTo("model-b");
        }

        @Test
        @DisplayName("SC-CFG-02: modelId 未配置时默认为 'default'")
        void noModelId_defaultsToDefault() {
            LlmProperties props = configuredProperties();
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            LlmModelCatalog catalog = resolver.resolveCatalog();

            assertThat(catalog.defaultId()).isEqualTo("default");
            assertThat(catalog.models()).containsOnlyKeys("default");
        }

        @Test
        @DisplayName("SC-CFG-03: modelId 被 trim")
        void modelIdIsTrimmed() {
            LlmProperties props = configuredProperties();
            props.setModelId("  trimmed-id  ");

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            LlmModelCatalog catalog = resolver.resolveCatalog();

            assertThat(catalog.defaultId()).isEqualTo("trimmed-id");
        }

        @Test
        @DisplayName("SC-CFG-04: 额外模型 modelId 被 trim")
        void additionalModelIdIsTrimmed() {
            LlmProperties props = configuredProperties();

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("  alias-b  ");
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            LlmModelCatalog catalog = resolver.resolveCatalog();

            assertThat(catalog.models()).containsKey("alias-b");
        }

        @Test
        @DisplayName("SC-CFG-05: 重复 modelId → IllegalStateException")
        void duplicateModelId_throws() {
            LlmProperties props = configuredProperties();
            props.setModelId("shared-id");

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("shared-id"); // same as default
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            assertThatThrownBy(resolver::resolveCatalog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
        }

        @Test
        @DisplayName("SC-CFG-06: 两个额外模型重复 modelId → IllegalStateException")
        void duplicateAdditionalModelIds_throws() {
            LlmProperties props = configuredProperties();

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");

            LlmProperties.ModelProperties modelB2 = new LlmProperties.ModelProperties();
            modelB2.setModelId("alias-b"); // duplicate
            modelB2.setApiKey("key-b2");
            modelB2.setApiBase("https://b2.example/v1");
            modelB2.setModelName("model-b2");

            props.setModels(List.of(modelB, modelB2));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            assertThatThrownBy(resolver::resolveCatalog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
        }

        @Test
        @DisplayName("SC-CFG-07: 额外模型缺少 modelId → IllegalStateException")
        void additionalModelMissingId_throws() {
            LlmProperties props = configuredProperties();

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            // modelId not set
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            assertThatThrownBy(resolver::resolveCatalog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model-id");
        }

        @Test
        @DisplayName("SC-CFG-08: 额外模型缺少 apiKey → IllegalStateException")
        void additionalModelMissingApiKey_throws() {
            LlmProperties props = configuredProperties();

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            // apiKey not set
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            assertThatThrownBy(resolver::resolveCatalog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
        }

        @Test
        @DisplayName("SC-CFG-09: 额外模型缺少 apiBase → IllegalStateException")
        void additionalModelMissingApiBase_throws() {
            LlmProperties props = configuredProperties();

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            modelB.setApiKey("key-b");
            // apiBase not set
            modelB.setModelName("model-b");
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            assertThatThrownBy(resolver::resolveCatalog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-base");
        }

        @Test
        @DisplayName("SC-CFG-10: 额外模型缺少 modelName → IllegalStateException")
        void additionalModelMissingModelName_throws() {
            LlmProperties props = configuredProperties();

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            // modelName not set
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            assertThatThrownBy(resolver::resolveCatalog)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model-name");
        }

        // --- Default values ---

        @Test
        @DisplayName("SC-CFG-11: 默认 temperature=0.6, topP=0.8, timeout=60s")
        void defaultValues_applied() {
            LlmProperties props = configuredProperties();
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            LlmModelCatalog catalog = resolver.resolveCatalog();

            assertThat(catalog.temperature()).isEqualTo(0.6);
            assertThat(catalog.topP()).isEqualTo(0.8);
            assertThat(catalog.timeout()).isEqualTo(Duration.ofSeconds(60));
        }

        // --- Caching ---

        @Test
        @DisplayName("SC-CFG-12: resolveCatalog() 缓存 — 多次调用返回同一实例")
        void resolveCatalog_cached() {
            LlmProperties props = configuredProperties();
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            LlmModelCatalog first = resolver.resolveCatalog();
            LlmModelCatalog second = resolver.resolveCatalog();

            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("SC-CFG-13: resolve() 缓存 — 多次调用返回同一实例")
        void resolve_cached() {
            LlmProperties props = configuredProperties();
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            ResolvedLlmConfig first = resolver.resolve();
            ResolvedLlmConfig second = resolver.resolve();

            assertThat(second).isSameAs(first);
        }

        // --- Credential decryption ---

        @Test
        @DisplayName("SC-CFG-14: apiKey 通过 CredentialDecryptor 解密")
        void apiKey_decrypted() {
            LlmProperties props = configuredProperties();
            props.setApiKey("ENC:my-key");

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            modelB.setApiKey("ENC:my-key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            props.setModels(List.of(modelB));

            CredentialDecryptor decryptor = ciphertext -> ciphertext.replace("ENC:", "plain:");
            LlmConfigResolver resolver = resolver(props, decryptor);

            LlmModelCatalog catalog = resolver.resolveCatalog();
            assertThat(catalog.models().get("default").apiKey()).isEqualTo("plain:my-key");
            assertThat(catalog.models().get("alias-b").apiKey()).isEqualTo("plain:my-key-b");
        }

        // --- Provider default ---

        @Test
        @DisplayName("SC-CFG-15: 额外模型未配 provider → 默认 'OpenAI'")
        void additionalModelMissingProvider_defaultsToOpenAI() {
            LlmProperties props = configuredProperties();

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            // provider not set
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            LlmModelCatalog catalog = resolver.resolveCatalog();

            assertThat(catalog.models().get("alias-b").provider()).isEqualTo("OpenAI");
        }

        // --- SSL verify default ---

        @Test
        @DisplayName("SC-CFG-16: 额外模型 sslVerify=null → 默认 true")
        void additionalModelNullSslVerify_defaultsToTrue() {
            LlmProperties props = configuredProperties();

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            modelB.setSslVerify(null);
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            LlmModelCatalog catalog = resolver.resolveCatalog();

            assertThat(catalog.models().get("alias-b").sslVerify()).isTrue();
        }

        @Test
        @DisplayName("SC-CFG-17: 额外模型 sslVerify=false → false")
        void additionalModelSslVerifyFalse_isFalse() {
            LlmProperties props = configuredProperties();

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            modelB.setSslVerify(false);
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            LlmModelCatalog catalog = resolver.resolveCatalog();

            assertThat(catalog.models().get("alias-b").sslVerify()).isFalse();
        }

        @Test
        @DisplayName("SC-CFG-08: 根 SSL_VERIFY=false 不污染列表项 SSL_VERIFY=true")
        void rootSslVerifyFalseDoesNotPolluteListItem() {
            LlmProperties props = configuredProperties();
            props.setSslVerify(false);

            LlmProperties.ModelProperties modelB = new LlmProperties.ModelProperties();
            modelB.setModelId("alias-b");
            modelB.setApiKey("key-b");
            modelB.setApiBase("https://b.example/v1");
            modelB.setModelName("model-b");
            modelB.setSslVerify(true);
            props.setModels(List.of(modelB));

            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);
            LlmModelCatalog catalog = resolver.resolveCatalog();

            assertThat(catalog.models().get("alias-b").sslVerify()).isTrue();
        }

        // --- File-based config ---

        @Test
        @DisplayName("SC-CFG-18: apiconfig.json 根字段被读取（API_BASE, API_KEY 等）")
        void apiconfigJson_rootFieldsLoaded() throws Exception {
            Path file = tempDir.resolve("apiconfig.json");
            Files.writeString(file, """
                {
                  "API_BASE": "https://file.example/v1",
                  "API_KEY": "file-key",
                  "MODEL_PROVIDER": "FileProvider",
                  "MODEL_NAME": "file-model",
                  "LLM_SSL_VERIFY": false
                }
                """);
            LlmProperties props = new LlmProperties();
            props.setConfigFile(file.toString());
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            ResolvedLlmConfig config = resolver.resolveRequired();
            assertThat(config.getApiBase()).isEqualTo("https://file.example/v1");
            assertThat(config.getApiKey()).isEqualTo("file-key");
            assertThat(config.getProvider()).isEqualTo("FileProvider");
            assertThat(config.getModelName()).isEqualTo("file-model");
            assertThat(config.isSslVerify()).isFalse();
        }

        @Test
        @DisplayName("SC-CFG-19: apiconfig.json MODELS 数组被忽略 — 仅 Spring properties 的 models 生效")
        void apiconfigJson_modelsArrayIgnored() throws Exception {
            Path file = tempDir.resolve("apiconfig.json");
            Files.writeString(file, """
                {
                  "API_BASE": "https://a.example/v1",
                  "API_KEY": "key-a",
                  "MODEL_NAME": "model-a",
                  "MODELS": [{"MODEL_ID": "file-only", "API_KEY": "file-key", "API_BASE": "https://x.example/v1", "MODEL_NAME": "x"}]
                }
                """);
            LlmProperties props = new LlmProperties();
            props.setConfigFile(file.toString());
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            LlmModelCatalog catalog = resolver.resolveCatalog();
            // file-only model from MODELS array should NOT appear
            assertThat(catalog.models()).doesNotContainKey("file-only");
            assertThat(catalog.models()).containsOnlyKeys("default");
        }

        @Test
        @DisplayName("SC-CFG-20: YAML 配置文件被正确加载")
        void apiconfigYaml_loaded() throws Exception {
            Path file = tempDir.resolve("apiconfig.yaml");
            Files.writeString(file, """
                API_BASE: https://yaml.example/v1
                API_KEY: yaml-key
                MODEL_PROVIDER: YamlProvider
                MODEL_NAME: yaml-model
                LLM_SSL_VERIFY: false
                """);
            LlmProperties props = new LlmProperties();
            props.setConfigFile(file.toString());
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            ResolvedLlmConfig config = resolver.resolveRequired();
            assertThat(config.getApiBase()).isEqualTo("https://yaml.example/v1");
            assertThat(config.getApiKey()).isEqualTo("yaml-key");
            assertThat(config.getProvider()).isEqualTo("YamlProvider");
        }

        @Test
        @DisplayName("SC-CFG-21: LLM_SSL_VERIFY 字符串 'true'/'false' 被正确解析")
        void sslVerifyStringParsed() throws Exception {
            Path file = tempDir.resolve("apiconfig.json");
            Files.writeString(file, """
                {
                  "API_BASE": "https://a.example/v1",
                  "API_KEY": "key",
                  "MODEL_NAME": "model",
                  "LLM_SSL_VERIFY": "false"
                }
                """);
            LlmProperties props = new LlmProperties();
            props.setConfigFile(file.toString());
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            ResolvedLlmConfig config = resolver.resolveRequired();
            assertThat(config.isSslVerify()).isFalse();
        }

        @Test
        @DisplayName("SC-CFG-22: LLM_SSL_VERIFY 非法值 → IllegalStateException")
        void sslVerifyInvalid_throws() throws Exception {
            Path file = tempDir.resolve("apiconfig.json");
            Files.writeString(file, """
                {
                  "API_BASE": "https://a.example/v1",
                  "API_KEY": "key",
                  "MODEL_NAME": "model",
                  "LLM_SSL_VERIFY": "yes"
                }
                """);
            LlmProperties props = new LlmProperties();
            props.setConfigFile(file.toString());
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            assertThatThrownBy(resolver::resolveRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_SSL_VERIFY");
        }

        @Test
        @DisplayName("SC-CFG-23: 配置文件 > 1 MiB → IllegalStateException")
        void fileTooLarge_throws() throws Exception {
            Path file = tempDir.resolve("apiconfig.json");
            // Create a file slightly over 1 MiB
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"API_KEY\":\"");
            // 1 MiB = 1048576 bytes. We need > 1 MiB total.
            // Each 'x' is 1 byte. We need about 1,048,560 x's + closing.
            sb.append("x".repeat(1_048_560));
            sb.append("\",\"API_BASE\":\"https://a.example/v1\",\"MODEL_NAME\":\"m\"}");
            Files.writeString(file, sb.toString());
            assertThat(Files.size(file)).isGreaterThan(1024L * 1024L);

            LlmProperties props = new LlmProperties();
            props.setConfigFile(file.toString());
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            assertThatThrownBy(resolver::resolveRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 MiB");
        }

        @Test
        @DisplayName("SC-CFG-24: 配置文件不存在 → IllegalStateException")
        void fileNotFound_throws() {
            LlmProperties props = new LlmProperties();
            props.setConfigFile("/nonexistent/path/apiconfig.json");
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            assertThatThrownBy(resolver::resolveRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readable");
        }

        @Test
        @DisplayName("SC-CFG-25: Spring properties 优先于文件值")
        void springPropertiesPreferOverFile() throws Exception {
            Path file = tempDir.resolve("apiconfig.json");
            Files.writeString(file, """
                {
                  "API_BASE": "https://file.example/v1",
                  "API_KEY": "file-key",
                  "MODEL_NAME": "file-model"
                }
                """);
            LlmProperties props = new LlmProperties();
            props.setConfigFile(file.toString());
            props.setApiKey("spring-key");
            props.setApiBase("https://spring.example/v1");
            props.setModelName("spring-model");
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            ResolvedLlmConfig config = resolver.resolveRequired();
            assertThat(config.getApiKey()).isEqualTo("spring-key");
            assertThat(config.getApiBase()).isEqualTo("https://spring.example/v1");
            assertThat(config.getModelName()).isEqualTo("spring-model");
        }

        @Test
        @DisplayName("SC-CFG-26: 默认模型缺少 apiKey → IllegalStateException")
        void defaultModelMissingApiKey_throws() {
            LlmProperties props = new LlmProperties();
            // no apiKey, apiBase, modelName set
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            assertThatThrownBy(resolver::resolveRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
        }

        @Test
        @DisplayName("SC-CFG-27: CredentialDecryptor 返回 null → apiKey 为空 → resolveRequired 抛异常")
        void decryptorReturnsNull_throws() {
            LlmProperties props = configuredProperties();
            CredentialDecryptor nullDecryptor = ciphertext -> null;
            LlmConfigResolver resolver = resolver(props, nullDecryptor);

            assertThatThrownBy(resolver::resolveRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
        }

        // --- Placeholder resolution ---

        @Test
        @DisplayName("SC-CFG-28: 配置文件中 ${ENV_VAR} 占位符被解析")
        void placeholderResolved() throws Exception {
            Path file = tempDir.resolve("apiconfig.json");
            Files.writeString(file, """
                {
                  "API_BASE": "https://${PLACEHOLDER_HOST}.example/v1",
                  "API_KEY": "key",
                  "MODEL_NAME": "model"
                }
                """);
            LlmProperties props = new LlmProperties();
            props.setConfigFile(file.toString());
            MockEnvironment env = new MockEnvironment();
            env.setProperty("PLACEHOLDER_HOST", "resolved-host");
            LlmConfigResolver resolver = new LlmConfigResolver(props, env, PASSTHROUGH);

            ResolvedLlmConfig config = resolver.resolveRequired();
            assertThat(config.getApiBase()).isEqualTo("https://resolved-host.example/v1");
        }

        @Test
        @DisplayName("SC-CFG-29: 未解析的占位符 → IllegalStateException (不泄露占位符内容)")
        void unresolvedPlaceholder_throws() throws Exception {
            Path file = tempDir.resolve("apiconfig.json");
            Files.writeString(file, """
                {
                  "API_BASE": "https://${UNRESOLVED_HOST}.example/v1",
                  "API_KEY": "key",
                  "MODEL_NAME": "model"
                }
                """);
            LlmProperties props = new LlmProperties();
            props.setConfigFile(file.toString());
            LlmConfigResolver resolver = resolver(props, PASSTHROUGH);

            assertThatThrownBy(resolver::resolveRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder")
                .hasMessageContaining("API_BASE");
        }
    }
}
