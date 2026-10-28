/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression;

import com.openjiuwen.agents.contextcompression.StructuredCompactProcessorConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.ProviderType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-033 §3.14 TC-FEAT-033.config-fail-fast — SIT-added TC
 * (L2 §2.3 + §7.3 row 1).
 *
 * <p>Does not launch the demo fat jars. Reaches into the published
 * {@code agent-core-ext-context-compression:0.1.3} artifact to construct
 * {@link StructuredCompactProcessorConfig} with each illegal value permutation,
 * then asserts build-time {@link IllegalArgumentException} is thrown. Also
 * verifies the exception messages don't leak API keys / base URLs / tokens
 * (L2 §7.3 "无凭据泄露" red-line).
 *
 * <p>Covers:
 * <ul>
 *   <li>setter-level primitives: {@code triggerTotalTokens} 0/-1,
 *       {@code messagesToKeep} -1, {@code compressionCallMaxTokens} 0,
 *       {@code identifierLimit} 0,
 *       {@code minMiddleRetentionRatio} {0.0, 1.0, -0.1, 1.5};
 *   <li>validate-level pair check: {@code model} without {@code modelClient}
 *       and the reverse (L2 §2.2 "两者均省略时使用确定性摘录，仅配置其中一项时报错").
 * </ul>
 *
 * <p>Rationale: demo scenarios s0..s11 cover <b>runtime</b> fallback reasons
 * (e.g. {@code MODEL_NOT_CONFIGURED} at s8 is a runtime downgrade, not a
 * build-time rejection). None covers the hard requirement that
 * illegal config throws at construction. This TC fills that gap, and does so
 * with a direct in-process build — no probe-agent / stub launch needed.
 */
@Tag("integration")
@Tag("feat-033")
@Tag("unit-like")
final class Feat033ConfigFailFastTest {

    /** L2 §7.3 red-line: no credentials / configuration content in error messages. */
    private static final List<String> SECRET_NEEDLES = List.of(
            "api-key", "api_key", "apiKey",
            "authorization", "Authorization",
            "secret", "password", "bearer", "Bearer",
            "sk-stub-fail-fast-test-key",
            "http://127.0.0.1:65535");

    @Test
    void triggerTotalTokensZeroRejected() {
        StructuredCompactProcessorConfig config = new StructuredCompactProcessorConfig();
        assertThatThrownBy(() -> config.setTriggerTotalTokens(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggerTotalTokens")
                .satisfies(e -> assertNoSecrets(e.getMessage()));
    }

    @Test
    void triggerTotalTokensNegativeRejected() {
        StructuredCompactProcessorConfig config = new StructuredCompactProcessorConfig();
        assertThatThrownBy(() -> config.setTriggerTotalTokens(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggerTotalTokens")
                .satisfies(e -> assertNoSecrets(e.getMessage()));
    }

    @Test
    void compressionCallMaxTokensZeroRejected() {
        StructuredCompactProcessorConfig config = new StructuredCompactProcessorConfig();
        assertThatThrownBy(() -> config.setCompressionCallMaxTokens(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compressionCallMaxTokens")
                .satisfies(e -> assertNoSecrets(e.getMessage()));
    }

    @Test
    void messagesToKeepNegativeRejected() {
        StructuredCompactProcessorConfig config = new StructuredCompactProcessorConfig();
        assertThatThrownBy(() -> config.setMessagesToKeep(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messagesToKeep")
                .satisfies(e -> assertNoSecrets(e.getMessage()));
    }

    @Test
    void identifierLimitZeroRejected() {
        StructuredCompactProcessorConfig config = new StructuredCompactProcessorConfig();
        assertThatThrownBy(() -> config.setIdentifierLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identifierLimit")
                .satisfies(e -> assertNoSecrets(e.getMessage()));
    }

    /** L2 §6.2 "minMiddleRetentionRatio ∈ (0, 1) 严格开区间". */
    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0, -0.1, 1.5})
    void minMiddleRetentionRatioOutsideOpenUnitRejected(double invalid) {
        StructuredCompactProcessorConfig config = new StructuredCompactProcessorConfig();
        assertThatThrownBy(() -> config.setMinMiddleRetentionRatio(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minMiddleRetentionRatio")
                .satisfies(e -> assertNoSecrets(e.getMessage()));
    }

    /**
     * L2 §2.2 pair check: model set but modelClient null is a hard build-time
     * reject. The default config already has valid primitive fields, so
     * {@link StructuredCompactProcessorConfig#validate()} reaches the pair
     * check at index 3 and throws before any downstream shape check.
     */
    @Test
    void modelWithoutModelClientRejected() {
        StructuredCompactProcessorConfig config = new StructuredCompactProcessorConfig();
        ModelRequestConfig model = new ModelRequestConfig();
        model.setModelName("stub-summary-model");
        model.setMaxTokens(1000);
        config.setModel(model);
        // modelClient left null.

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model and modelClient")
                .satisfies(e -> assertNoSecrets(e.getMessage()));
    }

    /** Symmetric half: modelClient set, model null → pair-check reject. */
    @Test
    void modelClientWithoutModelRejected() {
        StructuredCompactProcessorConfig config = new StructuredCompactProcessorConfig();
        // Use secret-looking apiKey + apiBase so that any accidental leak into
        // the exception message is caught by assertNoSecrets below.
        ModelClientConfig client = ModelClientConfig.builder()
                .clientProvider(ProviderType.OPEN_AI)
                .apiKey("sk-stub-fail-fast-test-key")
                .apiBase("http://127.0.0.1:65535/v1")
                .timeout(1.0)
                .build();
        config.setModelClient(client);
        // model left null.

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model and modelClient")
                .satisfies(e -> assertNoSecrets(e.getMessage()));
    }

    private static void assertNoSecrets(String message) {
        assertThat(message).as("exception message").isNotNull();
        for (String needle : SECRET_NEEDLES) {
            assertThat(message)
                    .as("exception message must not leak credential-like substring '%s'", needle)
                    .doesNotContain(needle);
        }
    }
}
