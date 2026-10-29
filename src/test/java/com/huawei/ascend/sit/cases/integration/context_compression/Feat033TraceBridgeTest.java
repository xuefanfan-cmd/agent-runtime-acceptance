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
 * FEAT-033 §3.28 TC-FEAT-033.tracebridge-span-event — R09 TraceBridge contract.
 *
 * <p>The 4-level assembly chain installs an OTel {@code TraceBridge} that
 * forwards the processor's {@code attempt_reasons} / {@code outcome} /
 * {@code reason} / {@code counter_id} / {@code identifiers_*} into span
 * events for the parent request span. The demo exposes:
 * <ul>
 *   <li>{@code GET /__span-events} — dump the InMemorySpanExporter contents.
 *   <li>{@code POST /__reset-span-exporter} — clear the exporter between tests.
 *   <li>{@code GET /__assembly-info} — probe whether the bridge is installed
 *       ({@code trace_bridge_installed} true/false).
 *   <li>{@code POST /__trace-bridge-close} — unsubscribe the current handle;
 *       the next assembly-info call reports {@code trace_bridge_installed=false}.
 *   <li>{@code POST /__trace-bridge-reinstall} — close then re-register, so
 *       the SIT can observe "uninstalled window → reinstalled" sequencing.
 * </ul>
 *
 * <p>Two TCs:
 * <ul>
 *   <li>{@code traceBridgeEmitsSpanEventOnCompact} — Phase-1 happy path:
 *       trigger-compact-via-rail → span-events contains the structured compact
 *       event with the processor attributes.
 *   <li>{@code traceBridgeCloseAndReinstall} — Phase-1 contract:
 *       assembly-info toggles correctly and after reinstall the next compact
 *       again emits a span event.
 * </ul>
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033TraceBridgeTest {

    private static final Duration SHORT = Duration.ofSeconds(10);

    /**
     * §3.28 TC-FEAT-033.tracebridge-span-event — rail-dispatched compact must
     * surface a span event carrying the processor outcome. The TC uses the
     * 4-level assembly path ({@code POST /trigger-compact-via-rail}) so the
     * installed {@code ContextProcessorRail} wires the processor's events
     * into the request span.
     */
    @Test
    void traceBridgeEmitsSpanEventOnCompact() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s1", "valid", Map.of())) {
            TriggerResponse assemblyBefore = s.http().assemblyInfo(SHORT);
            assertThat(assemblyBefore.statusCode()).as("__assembly-info HTTP status").isEqualTo(200);
            assertThat(assemblyBefore.body("trace_bridge_installed"))
                    .as("trace_bridge_installed at startup")
                    .isEqualTo(Boolean.TRUE);
            assertThat(assemblyBefore.body("processor_type"))
                    .as("processor_type exposed by /__assembly-info")
                    // 2026-10-29 SIT 对齐 L2 Line 240 PROCESSOR_TYPE 常量二进制值。
                    .isEqualTo("StructuredCompactProcessor");

            TriggerResponse resetResp = s.http().resetSpanExporter(SHORT);
            assertThat(resetResp.statusCode()).as("__reset-span-exporter HTTP status").isEqualTo(200);

            TriggerResponse rail = s.http().triggerViaRail("s1", DEFAULT_TRIGGER_TIMEOUT);
            assertThat(rail.statusCode()).as("trigger-compact-via-rail HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 1, SHORT);

            // rail_dispatch_begin / rail_dispatch_end are the authoritative
            // 4-level-assembly evidence; TraceBridge span event is the OTel
            // counterpart.
            s.tailer().assertRequired("rail_dispatch_begin");
            // 2026-10-29 SIT 对齐产品日志实际格式：rail_dispatch_end 后先打 agent_id= 字段
            // 再打 status=ok；原 pattern `rail_dispatch_end status=ok` 要求紧邻不符实际。
            s.tailer().assertRequired("rail_dispatch_end agent_id=context-probe-agent status=ok");

            TriggerResponse spanEvents = s.http().spanEvents(SHORT);
            assertThat(spanEvents.statusCode()).as("__span-events HTTP status").isEqualTo(200);
            // 2026-10-29 SIT 对齐 span events 实际 attribute：event name 是
            // "context.compression_state"（L2 Line 215），processor 字段值 PascalCase 类名。
            assertThat(spanEvents.rawBody())
                    .as("span events include compression_state event emitted by StructuredCompactProcessor")
                    .contains("StructuredCompactProcessor");
            assertThat(spanEvents.rawBody())
                    .as("span event records outcome=LLM_COMPACTED")
                    .contains("LLM_COMPACTED");
        }
    }

    /**
     * §3.28 TraceBridge close + reinstall — SIT must be able to observe the
     * handle transitioning from installed → closed → reinstalled. The next
     * rail-dispatched compact after reinstall must again emit span events
     * (proving the new handle took over).
     */
    @Test
    void traceBridgeCloseAndReinstall() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s1", "valid", Map.of())) {
            TriggerResponse before = s.http().assemblyInfo(SHORT);
            assertThat(before.body("trace_bridge_installed"))
                    .as("installed at startup")
                    .isEqualTo(Boolean.TRUE);

            TriggerResponse closeResp = s.http().traceBridgeClose(SHORT);
            assertThat(closeResp.statusCode()).as("__trace-bridge-close HTTP status").isEqualTo(200);
            assertThat(closeResp.body("was_installed"))
                    .as("close response reports was_installed=true")
                    .isEqualTo(Boolean.TRUE);
            assertThat(closeResp.body("currently_installed"))
                    .as("close response reports currently_installed=false")
                    .isEqualTo(Boolean.FALSE);

            TriggerResponse afterClose = s.http().assemblyInfo(SHORT);
            assertThat(afterClose.body("trace_bridge_installed"))
                    .as("installed after close")
                    .isEqualTo(Boolean.FALSE);

            TriggerResponse reinstall = s.http().traceBridgeReinstall(SHORT);
            assertThat(reinstall.statusCode()).as("__trace-bridge-reinstall HTTP status").isEqualTo(200);
            assertThat(reinstall.body("installed"))
                    .as("reinstall response reports installed=true")
                    .isEqualTo(Boolean.TRUE);

            TriggerResponse afterReinstall = s.http().assemblyInfo(SHORT);
            assertThat(afterReinstall.body("trace_bridge_installed"))
                    .as("installed after reinstall")
                    .isEqualTo(Boolean.TRUE);

            // Smoke: after reinstall the rail path still surfaces span events.
            TriggerResponse resetResp = s.http().resetSpanExporter(SHORT);
            assertThat(resetResp.statusCode()).as("__reset-span-exporter HTTP status").isEqualTo(200);
            TriggerResponse rail = s.http().triggerViaRail("s1", DEFAULT_TRIGGER_TIMEOUT);
            assertThat(rail.statusCode()).as("post-reinstall rail trigger HTTP status").isEqualTo(200);
            CompactProbeHarness.waitForCompleteCount(s.tailer(), 1, SHORT);

            TriggerResponse spanEvents = s.http().spanEvents(SHORT);
            assertThat(spanEvents.rawBody())
                    .as("span events after reinstall still carry StructuredCompactProcessor evidence")
                    // 2026-10-29 SIT 对齐 L2 Line 215 的 context.compression_state 事件 attribute：
                    // processor 字段值是 PROCESSOR_TYPE 常量 PascalCase "StructuredCompactProcessor"。
                    .contains("StructuredCompactProcessor");
        }
    }
}
