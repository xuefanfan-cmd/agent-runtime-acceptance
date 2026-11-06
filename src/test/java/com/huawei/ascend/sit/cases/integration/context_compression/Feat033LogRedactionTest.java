/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.context_compression;

import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness;
import com.huawei.ascend.sit.cases.integration.context_compression.fixture.CompactProbeHarness.Scenario;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * FEAT-033 §5.3 TC-LOG-ZERO-LEAK — processor log redaction contract (L2 §5.3).
 *
 * <p>L2 §5.3 mandates that the following categories never surface in any
 * observable log emitted by {@code StructuredCompactProcessor}:
 * <ul>
 *   <li>对话正文（conversation message content）
 *   <li>摘要内容（LLM summary output）
 *   <li>工具输入输出（tool call input/output）
 *   <li>模型配置参数（model configuration fields）
 *   <li>凭据（credentials: API keys, tokens, secrets）
 *   <li>原始异常堆栈（raw exception stack traces — exception <em>type</em> and
 *       <em>message</em> are permitted; full stack frames are not）
 * </ul>
 *
 * <p>This TC injects a distinctive credential sentinel
 * ({@code SENTINEL-CREDENTIAL-DO-NOT-LEAK-7f3a9c}) as the LLM API key so
 * that any accidental leak into the probe-agent log file is immediately
 * detectable. A session-body sentinel
 * ({@code SENTINEL-SESSION-BODY-DO-NOT-LEAK-9d2e4b}) is used to seed the
 * fixture's user message if the demo profile supports custom message injection;
 * otherwise the check falls back to the credential sentinel only.
 *
 * <p>The raw-exception-stacktrace check prohibits full Java stack frames
 * (lines matching {@code \tat com\.}) from appearing outside of the probe's
 * own SIT-layer stack output. It is intentionally scoped to
 * {@code com.openjiuwen} package prefix to avoid false positives from
 * infrastructure logging.
 *
 * <p>Java落点: {@code Feat033LogRedactionTest.credentialSentinelNotLeakedToLog}
 * + {@code rawExceptionStackTraceNotLeakedToLog}.
 */
@Tag("integration")
@Tag("feat-033")
@Tag("slow")
@Tag("manual")
final class Feat033LogRedactionTest {

    /**
     * Credential sentinel injected as {@code LLM_API_KEY}. Any occurrence in
     * the probe-agent log file is a zero-tolerance credential leak per L2 §5.3.
     */
    private static final String CREDENTIAL_SENTINEL =
            Feat033ConfigFailFastTest.CREDENTIAL_SENTINEL;

    /**
     * Session-body sentinel placed in a user message by the demo fixture.
     * L2 §5.3: "禁止对话正文进入日志".
     */
    private static final String SESSION_BODY_SENTINEL =
            "SENTINEL-SESSION-BODY-DO-NOT-LEAK-9d2e4b";

    /**
     * §5.3 credential zero-leak — LLM API key injected as the credential
     * sentinel must not appear anywhere in the probe-agent log after a
     * successful compression run.
     *
     * <p>Uses s1 profile (happy path, full LLM call chain) so the processor
     * exercises all log-emitting code paths. The stub-key env override carries
     * the sentinel so the actual HTTP call uses it as the Authorization header
     * value; any accidental echo in the log surfaces the sentinel.
     */
    @Test
    void credentialSentinelNotLeakedToLog() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();

        // Override LLM_API_KEY with the sentinel. The stub server accepts any
        // key, so the compression completes normally while the sentinel
        // travels through the entire model-client credential path.
        Map<String, String> probeExtras = Map.of("LLM_API_KEY", CREDENTIAL_SENTINEL);

        try (Scenario s = CompactProbeHarness.launch("s1", "valid", Map.of(), probeExtras)) {
            s.triggerAndWait("s1", CompactProbeHarness.DEFAULT_TRIGGER_TIMEOUT);

            // Credential sentinel must never appear in any log line.
            s.tailer().assertForbidden(CREDENTIAL_SENTINEL);

            // Generic credential pattern guards (belt-and-suspenders)
            s.tailer().assertForbidden("Bearer [A-Za-z0-9._-]{20,}");
        }
    }

    /**
     * §5.3 session-body zero-leak — conversation message content must not
     * appear in the processor log (L2 §5.3: "禁止对话正文进入日志").
     *
     * <p>The session-body sentinel is passed to the probe as the custom user
     * message seed via {@code PROBE_INJECT_USER_MESSAGE} env var (if the demo
     * profile supports this injection point). If the demo does not expose this
     * env var, the test asserts the sentinel is absent as a sanity check
     * (the absence is trivially true if it was never injected; the meaningful
     * coverage is provided when the demo wires the injection).
     *
     * <p>When the demo adds the {@code PROBE_INJECT_USER_MESSAGE} env var
     * support, remove the TODO comment and confirm coverage is live.
     *
     * <p>TODO(demo): wire {@code PROBE_INJECT_USER_MESSAGE} env var in the
     * context-probe-agent to seed the initial user message with the given
     * value. Without this, session-body leak can only be observed indirectly
     * via manual inspection.
     */
    @Test
    void sessionBodySentinelNotLeakedToLog() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();

        // Inject the sentinel both as a user-message seed (if supported) and
        // as a recognisable string we can grep for in the log.
        Map<String, String> probeExtras = Map.of(
                "PROBE_INJECT_USER_MESSAGE", SESSION_BODY_SENTINEL);

        try (Scenario s = CompactProbeHarness.launch("s1", "valid", Map.of(), probeExtras)) {
            s.triggerAndWait("s1", CompactProbeHarness.DEFAULT_TRIGGER_TIMEOUT);

            // Session body sentinel must not leak into the log.
            s.tailer().assertForbidden(SESSION_BODY_SENTINEL);
        }
    }

    /**
     * §5.3 raw exception stack trace zero-leak — full Java stack frames from
     * {@code com.openjiuwen} packages must not appear in the processor log.
     *
     * <p>L2 §5.3: "原始异常" must not enter the log. This prohibits lines like
     * {@code at com.openjiuwen.agents.contextcompression.StructuredCompactProcessor
     * .compress(StructuredCompactProcessor.java:234)}. Exception type and
     * message lines are permitted; {@code \tat com\.openjiuwen} frame lines
     * are not.
     *
     * <p>Uses the s7 (5xx fallback) profile to exercise the error code path,
     * since that path is most likely to log exception details.
     */
    @Test
    void rawExceptionStackTraceNotLeakedToLog() throws Exception {
        CompactProbeHarness.assumeFatJarsPresent();
        try (Scenario s = CompactProbeHarness.launch("s7", "5xx-error", Map.of())) {
            s.triggerAndWait("s7", CompactProbeHarness.DEFAULT_TRIGGER_TIMEOUT);

            // Raw stack frame lines from openjiuwen packages must not appear.
            // Pattern: tab + "at com.openjiuwen." — the standard Java stack
            // frame format when printed via printStackTrace / log.error(e).
            s.tailer().assertForbidden("\tat com\\.openjiuwen\\.");
        }
    }
}
