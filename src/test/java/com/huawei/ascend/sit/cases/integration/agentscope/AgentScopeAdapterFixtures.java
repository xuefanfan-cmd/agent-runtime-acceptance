/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.huawei.ascend.sit.config.TestConfig
 *  org.assertj.core.api.AbstractBooleanAssert
 *  org.assertj.core.api.AbstractStringAssert
 *  org.assertj.core.api.Assertions
 *  org.junit.jupiter.api.Assumptions
 */
package com.huawei.ascend.sit.cases.integration.agentscope;

import com.huawei.ascend.sit.config.TestConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.AbstractBooleanAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;

final class AgentScopeAdapterFixtures {
    static final String TRIP_AGENT = "travel-trip-agentscope";
    static final String HOTEL_AGENT = "agentscope-hotel";
    static final String HOTEL_HARNESS_AGENT = "agentscope-hotel-harness";
    static final String QUERY_SEARCH = "\u5e2e\u6211\u67e5\u4e00\u4e0b 2026-09-01 \u5230 2026-09-02 \u5317\u4eac 800 \u5143\u4ee5\u5185\u7684\u56db\u661f\u7ea7\u9152\u5e97";
    static final String QUERY_DETAIL = "\u67e5\u4e00\u4e0b BJ-001 \u8fd9\u5bb6\u9152\u5e97\u7684\u8be6\u60c5,\u4ee5\u53ca\u6240\u6709\u623f\u578b";
    static final String QUERY_BOOKING = "\u5e2e\u6211\u9884\u8ba2 BJ-001 \u7684 BJ-001-R1 \u623f\u578b,2026-09-01 \u5165\u4f4f\u5230 2026-09-02,\u5bbe\u5ba2\u59d3\u540d\u5f20\u4e09";
    static final String QUERY_CRM_LOOKUP = "\u67e5\u4e00\u4e0b\u5ba2\u6237 C001 \u7684\u5408\u7ea6\u7b49\u7ea7";
    static final String EXTERNAL_TOOL_RESULT_TEXT = "VIP \u94c2\u91d1,\u6708\u5ea6\u5dee\u65c5\u4e0a\u9650 3000 \u5143,\u504f\u597d\u54c1\u724c:\u5168\u5b63/\u4e9a\u6735";
    static final String EXTERNAL_TOOL_NAME_KEYWORD = "lookup_customer_profile";
    static final String INTERRUPT_KIND_CONFIRMATION = "confirmation";
    static final String APPROVE = "APPROVE";
    static final String REJECT = "REJECT";
    static final String INTERRUPT_KIND_TOOL_RESULT = "tool_result";
    static final String ORDER_ID_PREFIX = "BK-";
    static final List<String> STACK_LEAK_MARKERS = List.of("java.io.IOException", "Caused by:", "Exception in thread", "at java.base/", "at org.springframework.", "at reactor.");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3L);
    private static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    private AgentScopeAdapterFixtures() {
    }

    static Map<String, Object> confirmationResumeMetadata() {
        return Map.of("_interrupt", Map.of("payload", Map.of("kind", INTERRUPT_KIND_CONFIRMATION)));
    }

    static Map<String, Object> toolResultResumeMetadata() {
        return Map.of("_interrupt", Map.of("payload", Map.of("kind", INTERRUPT_KIND_TOOL_RESULT)));
    }

    static void assertNoStackLeak(String blob) {
        for (String marker : STACK_LEAK_MARKERS) {
            ((AbstractStringAssert)Assertions.assertThat((String)blob).as("\u6700\u7ec8 text \u4e0d\u5f97\u6cc4\u9732 JVM \u5806\u6808\uff1a" + marker, new Object[0])).doesNotContain(new CharSequence[]{marker});
        }
    }

    static void assertOrderIdPresent(String blob) {
        ((AbstractStringAssert)Assertions.assertThat((String)blob).as("\u5de5\u5177\u771f\u8c03\u8bc1\u636e\uff1atext \u5e94\u542b %s \u524d\u7f00 ID\uff08LLM \u65e0\u6cd5\u7f16\u9020\uff09", new Object[]{ORDER_ID_PREFIX})).contains(new CharSequence[]{ORDER_ID_PREFIX});
    }

    static void assertNoOrderId(String blob) {
        ((AbstractStringAssert)Assertions.assertThat((String)blob).as("REJECT \u77ed\u8def\uff1aAgentScope adapter \u672a\u771f\u8c03\u4e0b\u6e38 tool\uff0ctext \u4e0d\u5f97\u542b %s \u524d\u7f00 ID", new Object[]{ORDER_ID_PREFIX})).doesNotContain(new CharSequence[]{ORDER_ID_PREFIX});
    }

    static void assertRejectSemantics(String blob) {
        boolean hit = blob.contains("\u53d6\u6d88") || blob.contains("\u5df2\u53d6\u6d88") || blob.contains("\u64a4\u9500") || blob.contains("cancel") || blob.contains("Cancel");
        ((AbstractBooleanAssert)Assertions.assertThat((boolean)hit).as("REJECT \u540e text \u5e94\u542b '\u53d6\u6d88'/'\u5df2\u53d6\u6d88'/'cancel' \u7b49\u8bed\u4e49\u5173\u952e\u8bcd\uff08\u5f31\u65ad\u8a00\uff09", new Object[0])).isTrue();
    }

    static String contextIdFor(String slug) {
        return "ctx-feat002-agsc-" + slug + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static boolean isAgentCardReachable(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(baseUrl + AGENT_CARD_PATH)).timeout(PROBE_TIMEOUT).GET().build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        }
        catch (Exception e) {
            return false;
        }
    }

    static void assumeTripAndHotelReady(TestConfig config) {
        String tripUrl = AgentScopeAdapterFixtures.tripBaseUrl(config);
        String hotelUrl = AgentScopeAdapterFixtures.hotelBaseUrl(config);
        Assumptions.assumeTrue((boolean)AgentScopeAdapterFixtures.isAgentCardReachable(tripUrl), (String)("SIT skipped: \u7236\u4fa7 agent-card unreachable at " + tripUrl + " \u2014 \u5148\u8d77 dist/travel-trip-0.1.0.jar\uff088092\uff09\uff0c\u53c2\u8003 CLIENT-INTEGRATION-GUIDE \u00a73"));
        Assumptions.assumeTrue((boolean)AgentScopeAdapterFixtures.isAgentCardReachable(hotelUrl), (String)("SIT skipped: AgentScope \u6837\u4f8b agent-card unreachable at " + hotelUrl + " \u2014 \u5148\u8d77 dist/agentscope-hotel-0.1.0.jar\uff0818120\uff09\uff0c\u53c2\u8003 CLIENT-INTEGRATION-GUIDE \u00a73"));
    }

    static void assumeTripReadyAndHotelUnreachable(TestConfig config) {
        String tripUrl = AgentScopeAdapterFixtures.tripBaseUrl(config);
        String hotelUrl = AgentScopeAdapterFixtures.hotelBaseUrl(config);
        Assumptions.assumeTrue((boolean)AgentScopeAdapterFixtures.isAgentCardReachable(tripUrl), (String)("SIT skipped: \u7236\u4fa7 agent-card unreachable at " + tripUrl));
        Assumptions.assumeFalse((boolean)AgentScopeAdapterFixtures.isAgentCardReachable(hotelUrl), (String)("SIT skipped: R1 \u9700\u8981 AgentScope \u6837\u4f8b agent \u4e0d\u53ef\u8fbe\uff0c\u4f46\u63a2\u6d4b\u5230 " + hotelUrl + " \u53ef\u8fbe\u3002\u5148\u5173\u505c dist/agentscope-hotel-0.1.0.jar \u518d\u6267\u884c\u672c\u7528\u4f8b\u3002"));
    }

    static void assumeHarnessReady(TestConfig config) {
        String harnessUrl = AgentScopeAdapterFixtures.harnessBaseUrl(config);
        Assumptions.assumeTrue((boolean)AgentScopeAdapterFixtures.isAgentCardReachable(harnessUrl), (String)("SIT skipped: AgentScope HarnessAgent \u53d8\u4f53 agent-card unreachable at " + harnessUrl + " \u2014 \u5148\u8d77 dist/agentscope-hotel-harness-0.1.0.jar\uff08\u9ed8\u8ba4\u7aef\u53e3 18121\uff0c\u9700 DEEPSEEK_* \u73af\u5883\u53d8\u91cf\uff09\uff0c\u53c2\u8003 dist/agentscope-hotel-harness-demo-src-0.1.0.zip \u5185 README.md \u00a7\u8fd0\u884c"));
    }

    static String tripBaseUrl(TestConfig config) {
        return config.getString("sut.agents.travel-trip-agentscope.url", "http://localhost:8092");
    }

    static String hotelBaseUrl(TestConfig config) {
        return config.getString("sut.agents.agentscope-hotel.url", "http://localhost:18120");
    }

    static String harnessBaseUrl(TestConfig config) {
        return config.getString("sut.agents.agentscope-hotel-harness.url", "http://localhost:18121");
    }
}
