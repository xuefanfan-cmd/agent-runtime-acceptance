/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression;

import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness.Scenario;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHttpClient.TriggerResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness.DEFAULT_TRIGGER_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-033 §3.29 TC-FEAT-033.state-save-load — R10 session persistence contract.
 *
 * <p>Scenario s18 drives:
 * <ol>
 *   <li>{@code POST /trigger-compact?scenario=s18} — initial compaction fills
 *       {@code compressionHistory}.
 *   <li>{@code POST /__save-state?contextId=X} — snapshot the serializable
 *       state ({@code compressionHistory} + context metadata).
 *   <li>{@code POST /__load-state?contextId=X&scenario=s18} — clear the live
 *       context and restore from the saved snapshot. Response must carry
 *       {@code message_count_before == message_count_after} and the restored
 *       compressionHistory size.
 *   <li>{@code POST /trigger-compact?scenario=s18} — second compaction after
 *       restore. Processor emits a second {@code phase=complete} line and
 *       outcome=LLM_COMPACTED reason=SUCCESS, proving the restored state is
 *       live-usable.
 * </ol>
 *
 * <p>R10 invariants:
 * <ul>
 *   <li>load-state response: {@code message_count_before == message_count_after}.
 *   <li>second compact: outcome=LLM_COMPACTED, reason=SUCCESS.
 *   <li>no secrets in the state payload (apiKey must be stripped before save).
 * </ul>
 *
 * <p><b>Note on compressionHistory:</b> L2 Line 209 规定 "运行中状态不跨进程恢复；
 * saveState/loadState 仅维护算法状态版本，稳定摘要存在消息历史中"。compressionHistory
 * 是运行时 compact 产生的事件 history，属于"运行中状态"，不在 saveState/loadState 契约内。
 * R10 "保存恢复不重压" 的证据应该通过 messages 中的摘要 user 消息（L2 Line 211）+ Round-2
 * 能基于 restored state 继续工作来验证，不是通过 compressionHistory size。
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033StateLifecycleTest {

    private static final Duration SHORT = Duration.ofSeconds(10);

    @Test
    void s18SaveLoadRoundTripRestoresHistoryAndKeepsMessageCount() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s18", "valid", Map.of())) {
            String contextId = "ctx-s18";

            // 2026-10-29 SIT 修：Round-1 trigger 必须跟 saveState/loadState 用同一个 contextId，
            // 否则 trigger 操作 demo 默认 "probe-s18" context，saveState 查 "ctx-s18" 不存在
            // → saved=false。扩展 CompactProbeHttpClient.trigger 支持 contextId override 后，
            // 两处 trigger 都显式传 "ctx-s18"。
            // Round-1: initial compact populates compressionHistory.
            TriggerResponse round1 = s.http().trigger("s18", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round1.statusCode()).as("round-1 trigger HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 1, SHORT);

            // Save state.
            TriggerResponse saved = s.http().saveState(contextId, SHORT);
            assertThat(saved.statusCode()).as("__save-state HTTP status").isEqualTo(200);
            assertThat(saved.body("saved"))
                    .as("__save-state response includes saved=true")
                    .isEqualTo(Boolean.TRUE);
            // Credential-sentinel red-line: the save payload must not leak the
            // apiKey. ApiKey literal is "stub-key" in this test; just prove it
            // and any Bearer/sk- form never leak.
            assertThat(saved.rawBody())
                    .as("__save-state response must not leak apiKey literal")
                    .doesNotContain("stub-key");
            assertThat(saved.rawBody())
                    .as("__save-state response must not leak Bearer/sk- tokens")
                    .doesNotContainPattern("sk-[A-Za-z0-9]{10,}")
                    .doesNotContainPattern("Bearer\\s+[A-Za-z0-9._-]{20,}");

            // Load state.
            TriggerResponse loaded = s.http().loadState(contextId, "s18", SHORT);
            assertThat(loaded.statusCode()).as("__load-state HTTP status").isEqualTo(200);
            assertThat(loaded.body("loaded"))
                    .as("__load-state response includes loaded=true")
                    .isEqualTo(Boolean.TRUE);

            int before = ((Number) loaded.body("message_count_before")).intValue();
            int after = ((Number) loaded.body("message_count_after")).intValue();
            assertThat(after)
                    .as("R10 — message_count_before(%d) == message_count_after(%d)", before, after)
                    .isEqualTo(before);
            // 2026-10-29 SIT 删：原判据 `compression_history_size >= 1` 违反 L2 Line 209 契约
            // "运行中状态不跨进程恢复；saveState/loadState 仅维护算法状态版本，稳定摘要存在消息
            // 历史中"。compressionHistory 是运行时 compact 产生的事件 history（每次 compact push
            // 一个 compression_usage event detail map），属于"运行中状态"，L2 明确不要求跨
            // save/load round-trip 保留。R10 "保存恢复不重压" 的证据应该通过 messages 中的
            // 摘要 user 消息（L2 Line 211"摘要写为历史引用 user 消息"）+ Round-2 能基于 restored
            // state 继续工作（下面的 phase=complete lines >= 2 判据）来验证。

            // Round-2: second compact against the restored state.
            TriggerResponse round2 = s.http().trigger("s18", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round2.statusCode()).as("round-2 trigger HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 2, SHORT);

            s.tailer().assertRequired("outcome=LLM_COMPACTED");
            s.tailer().assertRequired("reason=SUCCESS");
            s.tailer().assertForbidden("outcome=DETERMINISTIC_COMPACTED");
            // 2026-10-29 SIT 补"能红"判据（防假绿）：
            //
            // r5 TC 原判据 `assertRequired("outcome=LLM_COMPACTED")` 对 log tail 整体搜索，
            // Round-1 的日志行就能满足 — 无法分 Round-2 独立验证。本轮实证 Round-2 实际走
            // outcome=NO_OP reason=NO_COMPRESSIBLE_MIDDLE（跟 s17 同根 — fixture 结构里
            // Round-1 compact 后 messages = 摘要 user 消息 + 保护尾部 4 条，没有可压缩中间
            // 段 → L2 §3.2 "没有中间段时 no-op"），但原判据看不见，TC 假绿。
            //
            // s18 TC 场景下 Round-1 和 Round-2 都**应该** LLM_COMPACTED（才真能验证 R10
            // "restored state 真做 compact"），所以整个 log 不应该有 outcome=NO_OP。
            // 加 forbidden 让 TC 对 Round-2 NO_OP 场景 FAIL，暴露 fixture 结构问题
            // （归属 demo 侧 — 跟 s17 根因同，需 demo 改 saveState/loadState 流程或 fixture
            // 让 Round-2 真有可压缩中间段）。
            s.tailer().assertForbidden("outcome=NO_OP");

            // Two phase=complete lines prove the restored state remained usable
            // for a fresh compaction round.
            assertThat(s.tailer().completeCount())
                    .as("phase=complete lines after save+load round trip")
                    .isGreaterThanOrEqualTo(2);
        }
    }
}
