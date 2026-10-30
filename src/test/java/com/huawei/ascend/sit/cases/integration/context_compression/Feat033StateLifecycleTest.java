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
 * FEAT-033 §3.29 TC-FEAT-033.state-save-load — R10 session persistence contract,
 * 2026-10-30 拆分为两条 TC（demo R1 答复 §2.4 建议）：
 *
 * <ul>
 *   <li><b>R10.1 保存恢复不重压</b>（{@link #s18SaveLoadRoundTripIsStrictlyIdempotent}）：
 *       {@code /__load-state} 不加 {@code injectAfterLoad}（默认 0），严格 round-trip。
 *       Round-2 trigger-compact 应该走 NO_OP — 因为 Round-1 compact 后 context ≡
 *       Round-1 after（摘要 user + 保护尾部），没有可压缩中间段。<br>
 *       <b>能红证明</b>：产品哪天 R10.1 场景 Round-2 走 LLM_COMPACTED → 说明
 *       state restore 不严格（messages 被偷偷添加）→ TC FAIL。
 *   <li><b>R10.2 restored state 能继续 compact</b>（{@link #s18R10_2RestoredStateCanContinueCompact}）：
 *       {@code /__load-state?injectAfterLoad=6} — demo 侧 state restore 后额外注入
 *       6 条带标识符的 user 消息（≥ messagesToKeep + 2），让 Round-2 分区后真有
 *       可压缩中间段。Round-2 应该走 LLM_COMPACTED SUCCESS。<br>
 *       <b>能红证明</b>：产品哪天 injectAfterLoad=6 后 Round-2 还是 NO_OP → 说明
 *       state restore 后 processor 不能工作 → TC FAIL。
 * </ul>
 *
 * <p>Scenario s18 driver 两条共用：
 * <ol>
 *   <li>{@code POST /trigger-compact?scenario=s18&contextId=X} — initial compaction
 *   <li>{@code POST /__save-state?contextId=X} — snapshot
 *   <li>{@code POST /__load-state?contextId=X&scenario=s18[&injectAfterLoad=N]} — restore
 *   <li>{@code POST /trigger-compact?scenario=s18&contextId=X} — second compaction
 * </ol>
 *
 * <p>R10 共同 invariants（两条 TC 共享）：
 * <ul>
 *   <li>load-state 响应 {@code message_count_before == message_count_after}（严格 round-trip）
 *   <li>no secrets in the state payload（apiKey must be stripped before save）
 *   <li>二次 compact 的 message_count_before 跟 Round-1 after 相关（state 真的恢复了）
 * </ul>
 *
 * <p><b>Note on compressionHistory（L2 Line 209）：</b> L2 规定 "运行中状态不跨进程恢复；
 * saveState/loadState 仅维护算法状态版本，稳定摘要存在消息历史中"。compressionHistory
 * 是运行时 compact 产生的事件 history，属于"运行中状态"，不在 saveState/loadState
 * 契约内。R10 的证据应该通过 messages 中的摘要 user 消息（L2 Line 211）+ Round-2
 * 能基于 restored state 继续工作来验证，不是通过 compressionHistory size。
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033StateLifecycleTest {

    private static final Duration SHORT = Duration.ofSeconds(10);

    /**
     * R10.1 — 严格 round-trip 不重压。Round-2 trigger 走 NO_OP。
     */
    @Test
    void s18SaveLoadRoundTripIsStrictlyIdempotent() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s18", "valid", Map.of())) {
            String contextId = "ctx-s18";

            // Round-1: initial compact populates compressionHistory + 摘要 user 消息
            // 入 messages history。
            TriggerResponse round1 = s.http().trigger("s18", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round1.statusCode()).as("round-1 trigger HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 1, SHORT);

            // Save state.
            TriggerResponse saved = s.http().saveState(contextId, SHORT);
            assertThat(saved.statusCode()).as("__save-state HTTP status").isEqualTo(200);
            assertThat(saved.body("saved"))
                    .as("__save-state response includes saved=true")
                    .isEqualTo(Boolean.TRUE);
            // Credential-sentinel red-line: the save payload must not leak the apiKey.
            assertThat(saved.rawBody())
                    .as("__save-state response must not leak apiKey literal")
                    .doesNotContain("stub-key");
            assertThat(saved.rawBody())
                    .as("__save-state response must not leak Bearer/sk- tokens")
                    .doesNotContainPattern("sk-[A-Za-z0-9]{10,}")
                    .doesNotContainPattern("Bearer\\s+[A-Za-z0-9._-]{20,}");

            // Load state — 不带 injectAfterLoad（默认 0），严格 round-trip。
            TriggerResponse loaded = s.http().loadState(contextId, "s18", SHORT);
            assertThat(loaded.statusCode()).as("__load-state HTTP status").isEqualTo(200);
            assertThat(loaded.body("loaded"))
                    .as("__load-state response includes loaded=true")
                    .isEqualTo(Boolean.TRUE);

            int before = ((Number) loaded.body("message_count_before")).intValue();
            int after = ((Number) loaded.body("message_count_after")).intValue();
            assertThat(after)
                    .as("R10.1 — message_count_before(%d) == message_count_after(%d) 严格 round-trip",
                            before, after)
                    .isEqualTo(before);

            // Round-2: second trigger against the restored state. R10.1 场景没 injectAfterLoad，
            // Round-1 compact 后 context 结构 = 摘要 user + 保护尾部，没有可压缩中间段
            // → L2 §3.2 Line 102 "没有中间段时 no-op" → Round-2 走 NO_OP NO_COMPRESSIBLE_MIDDLE
            // 或 BELOW_THRESHOLD（取决于 Round-1 after 的 token 数相对 trigger threshold 的位置）。
            TriggerResponse round2 = s.http().trigger("s18", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round2.statusCode()).as("round-2 trigger HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 2, SHORT);

            // 2026-10-30 SIT 升级：R10.1 判据重设 — 原昨天加的 assertRequired("LLM_COMPACTED") +
            // assertForbidden("outcome=NO_OP") 判据组对 R10.1 严格 round-trip 场景不合理
            // （demo 答复 §2.4 明确 R10.1 场景 Round-2 "可观察 NO_OP 或 BELOW_THRESHOLD"）。
            //
            // R10.1 的能红判据应该是 "Round-2 必须有 outcome=NO_OP 日志行"（证明 restored state
            // 真的没多消息可压，反证 state restore 严格 round-trip 成立）：
            //   - 若 Round-2 走 LLM_COMPACTED → log tail 整体没 outcome=NO_OP → assertRequired FAIL
            //     ✓ 能红（说明 restored state 多了消息，state 不严格）
            //   - 若 Round-2 走 DETERMINISTIC_COMPACTED → 同上，log tail 整体没 outcome=NO_OP
            //     → assertRequired FAIL ✓ 能红
            //   - 若 Round-2 走 NO_OP → log tail 有 outcome=NO_OP → PASS（符合 R10.1 严格 round-trip）
            //
            // Round-1 行会打 outcome=LLM_COMPACTED，所以 log 整体一定有 LLM_COMPACTED；
            // R10.1 不加 assertForbidden("outcome=LLM_COMPACTED")（会被 Round-1 行误伤）。
            // 分 Round-2 的区分能力全靠 outcome=NO_OP 的 "必须存在"。
            s.tailer().assertRequired("outcome=NO_OP");

            // Two phase=complete lines prove the restored state remained usable
            // for a fresh compaction round（即使走 NO_OP，也应该打 phase=complete 行）。
            assertThat(s.tailer().completeCount())
                    .as("phase=complete lines after save+load round trip")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    /**
     * R10.2 — restored state 能继续 compact。{@code injectAfterLoad=6}
     * 让 Round-2 分区后真有可压缩中间段。Round-2 trigger 走 LLM_COMPACTED SUCCESS。
     */
    @Test
    void s18R10_2RestoredStateCanContinueCompact() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s18", "valid", Map.of())) {
            String contextId = "ctx-s18-r10-2";

            // Round-1: initial compact.
            TriggerResponse round1 = s.http().trigger("s18", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round1.statusCode()).as("round-1 trigger HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 1, SHORT);

            // Save state.
            TriggerResponse saved = s.http().saveState(contextId, SHORT);
            assertThat(saved.statusCode()).as("__save-state HTTP status").isEqualTo(200);
            assertThat(saved.body("saved"))
                    .as("__save-state response includes saved=true")
                    .isEqualTo(Boolean.TRUE);

            // Load state — injectAfterLoad=6（≥ messagesToKeep + 2，s18 profile messagesToKeep=4）
            // let demo 侧在 state restore 后额外注入 6 条带标识符的 user 消息。
            TriggerResponse loaded = s.http().loadState(contextId, "s18", 6, SHORT);
            assertThat(loaded.statusCode()).as("__load-state HTTP status").isEqualTo(200);
            assertThat(loaded.body("loaded"))
                    .as("__load-state response includes loaded=true")
                    .isEqualTo(Boolean.TRUE);

            // demo R1 答复 §2.4 新字段：message_count_after_load（post round-trip, pre inject）
            // + messages_injected_after_load。
            int before = ((Number) loaded.body("message_count_before")).intValue();
            Object injectedObj = loaded.body("messages_injected_after_load");
            assertThat(injectedObj)
                    .as("R10.2 — load-state response includes messages_injected_after_load")
                    .isNotNull();
            int injected = ((Number) injectedObj).intValue();
            assertThat(injected)
                    .as("R10.2 — messages_injected_after_load == 6 (requested injectAfterLoad)")
                    .isEqualTo(6);

            int after = ((Number) loaded.body("message_count_after")).intValue();
            assertThat(after)
                    .as("R10.2 — message_count_after(%d) == message_count_before(%d) + injected(%d)",
                            after, before, injected)
                    .isEqualTo(before + injected);

            // Round-2: second compact. injectAfterLoad=6 让 Round-2 分区后真有可压缩中间段
            // → outcome=LLM_COMPACTED reason=SUCCESS。
            TriggerResponse round2 = s.http().trigger("s18", contextId, DEFAULT_TRIGGER_TIMEOUT);
            assertThat(round2.statusCode()).as("round-2 trigger HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 2, SHORT);

            s.tailer().assertRequired("outcome=LLM_COMPACTED");
            s.tailer().assertRequired("reason=SUCCESS");
            s.tailer().assertForbidden("outcome=DETERMINISTIC_COMPACTED");
            // 能红证明：产品 injectAfterLoad=6 后 Round-2 还是 NO_OP → 说明 state restore 后
            // processor 不能工作 → FAIL。
            s.tailer().assertForbidden("outcome=NO_OP reason=NO_COMPRESSIBLE_MIDDLE");

            assertThat(s.tailer().completeCount())
                    .as("phase=complete lines (round-1 + round-2)")
                    .isGreaterThanOrEqualTo(2);
        }
    }
}
