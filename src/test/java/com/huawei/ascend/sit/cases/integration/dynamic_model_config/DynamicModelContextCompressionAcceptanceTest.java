/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.dynamic_model_config;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-050-CORE §5.1.4 / FRS-050 AC-20：上下文压缩环节跟随本次请求的主模型。
 *
 * <p><b>已登记的降级（partial）</b>：本用例需要"确定性触发上下文压缩"的前置，当前载体尚未给出
 * 可控的压缩阈值/窗口配置，因此断言写成"若观察到第二次模型调用，则它必须发往 B 端点"，并把
 * "未观察到压缩调用"作为前置未具备上报（不判产品通过）。覆盖台账记录恢复条件：具备可控压缩触发
 * 手段后升级为真执行。</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelContextCompressionAcceptanceTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB,
                Map.of("dmcsit.default-model-id", "A", "dmcsit.instance-enabled", "true",
                        "dmcsit.instance-model-id", "A", "dmcsit.tool-step-enabled", "true"));
    }

    /**
     * 触发行灌入的字符数（≈10 万 token）。
     *
     * <p>构造思路（依据 DialogueCompressor 源码）：它先算"可压缩前缀"，前缀为空就 noop、不发模型调用；
     * 而 CurrentRoundCompressor 会在窗口越过 100000 token 时裁掉旧消息。所以先用**小消息**把消息数堆过
     * {@code messagesToKeep=10}（此时 token 远低于阈值，不会被裁剪），最后一次性灌入大上下文：
     * 同一轮里 DialogueCompressor 先于 CurrentRoundCompressor 执行，前缀仍是完整的旧历史 → 产生真实摘要调用。</p>
     */
    private static final int FILLER_CHARS = 60_000;

    /**
     * 最多灌入轮数（每轮约 1.5 万 token）。
     *
     * <p>构造要点（每次构造失败后的修正）：用**中等消息**连续灌入，让"可压缩前缀"里始终是
     * 有实际内容的问答对——若用极小消息堆消息数，前缀无压缩收益；若用单条超大消息冲过阈值，
     * 同阈值的 CurrentRoundCompressor 会先把老消息裁掉。中等消息在越过 100000 的那一刻，
     * 前缀里仍是几段有内容的对话，DialogueCompressor 才有东西可总结、进而发起真实的模型调用。</p>
     */
    private static final int MAX_TURNS = 12;

    @Test
    @Story("dmc.context.compressor: 压缩调用跟随请求所选模型")
    @DisplayName("同会话把上下文推过压缩阈值后，压缩调用同样发往请求指定的 B 端点")
    void compressorFollowsMainModel() {
        int baselineA = endpointA.requestCount();
        int baselineB = endpointB.requestCount();

        String contextId = "dmc-context-compressor-" + System.nanoTime();

        // 逐轮灌入中等消息：哪一轮出现"携带压缩提示词的模型调用"，就是压缩真正发生的轮次。
        int compressionTurnStart = -1;
        int compressionTurn = -1;
        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            int before = endpointB.requestCount();
            var round = sendInSession(contextId, filler("turn-" + turn), Map.of("model_name", "B"));
            assertRealModelAnswered(round, "dmc.context.compressor");
            // 正常轮次对模型的调用恒为 1 次；该轮 ≥2 次说明除答题外还发生了一次压缩调用。
            boolean compressed = endpointB.requestsSince(before).size() >= 2;
            if (compressed) {
                compressionTurn = turn;
                compressionTurnStart = before;
                break;
            }
        }

        int triggeringCount = compressionTurnStart < 0 ? 0 : endpointB.requestsSince(compressionTurnStart).size();

        assertThat(compressionTurn)
                .as("应在 %s 轮中等消息之内出现一次真实的压缩模型调用（B 端点收到携带压缩提示词的请求）", MAX_TURNS)
                .isPositive();
        assertThat(endpointA.requestsSince(baselineA))
                .as("压缩调用不得发往默认模型 A 端点（设计 AC-20：跟随主模型）")
                .isEmpty();
        assertThat(triggeringCount)
                .as("窗口跨过阈值后应观察到压缩产生的模型调用；该轮 B 端点调用次数=%s", triggeringCount)
                .isGreaterThanOrEqualTo(2);
        assertThat(endpointB.requestCount() - baselineB)
                .as("整场会话的模型调用都应落在 B 端点")
                .isGreaterThanOrEqualTo(compressionTurn + 1);
    }

    private static String filler(String tag) {
        String chunk = "alpha bravo charlie delta echo foxtrot golf hotel india juliett ";
        StringBuilder builder = new StringBuilder(FILLER_CHARS + chunk.length() + 64);
        builder.append("dmc-ctx-probe ").append(tag).append(' ');
        while (builder.length() < FILLER_CHARS) {
            builder.append(chunk);
        }
        builder.append(" reply briefly.");
        return builder.toString();
    }
}

