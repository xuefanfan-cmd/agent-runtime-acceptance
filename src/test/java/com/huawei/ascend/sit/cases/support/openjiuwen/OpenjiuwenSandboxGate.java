package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hard gates for OJ-09 / OJ-10 jiuwenbox + hotel {@code sandbox} profile tests.
 */
public final class OpenjiuwenSandboxGate {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String SANDBOX_PROFILE_MARKER = "profile is active: \"sandbox\"";
    private static final String SANDBOX_TOOLS_REGISTERED_MARKER = "Registered 3 sandbox tool(s) on hotel agent";

    private OpenjiuwenSandboxGate() {
    }

    /** OJ-09.0 / OJ-10 — jiuwenbox health before hotel starts. */
    public static void assertSandboxHealthy(String healthUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("jiuwenbox health GET %s", healthUrl)
                .isEqualTo(200);
        String body = response.body() == null ? "" : response.body().toLowerCase(Locale.ROOT);
        assertThat(body.contains("ok") || body.contains("healthy") || body.contains("status"))
                .as("jiuwenbox health body should indicate readiness: %s", response.body())
                .isTrue();
    }

    /** OJ-09.C — hotel started with {@code sandbox} profile and registered sandbox tools. */
    public static void assertHotelUsesSandboxProfile(SutStack stack) throws IOException {
        Path log = hotelStdoutLog(stack);
        String content = Files.readString(log, StandardCharsets.UTF_8);
        String runSlice = sliceSinceLast(content, SANDBOX_PROFILE_MARKER);

        assertThat(lastLineContaining(runSlice, SANDBOX_PROFILE_MARKER))
                .as("OJ-09.C hotel active profile sandbox (see %s)", log)
                .isNotNull();
        assertThat(lastLineContaining(runSlice, SANDBOX_TOOLS_REGISTERED_MARKER))
                .as("OJ-09.C hotel sandbox tool registration (rebuild agent-openjiuwen-travel-hotel:0.1.0 if missing; see %s)", log)
                .isNotNull();
    }

    /**
     * OJ-09.E — sandbox executeCode / executeCmd must succeed in hotel logs for the current run:
     * no jiuwenbox 409 / state=error, and at least one {@code Tool result} with {@code code=0}
     * whose stdout contains the expected substring (guards against LLM-only fake-green replies).
     */
    public static void assertSandboxExecSucceeded(SutStack stack, String expectedStdoutSubstring) throws IOException {
        Path log = hotelStdoutLog(stack);
        String runSlice = sliceSinceLast(
                Files.readString(log, StandardCharsets.UTF_8), SANDBOX_TOOLS_REGISTERED_MARKER);
        String expected = expectedStdoutSubstring == null || expectedStdoutSubstring.isBlank()
                ? "ok"
                : expectedStdoutSubstring;

        long sandboxGatewayFailures = runSlice.lines()
                .filter(line -> line.contains("Tool execution error:"))
                .filter(line -> line.contains("199001")
                        || line.contains("state is error")
                        || line.contains("HTTP 409")
                        || line.contains("gateway_code")
                        || line.contains("gateway_shell"))
                .count();
        assertThat(sandboxGatewayFailures)
                .as("OJ-09.E sandbox tool must not fail (199001 / HTTP 409 / state is error); see %s", log)
                .isZero();

        long stateErrorHits = runSlice.lines()
                .filter(line -> line.contains("state is error"))
                .count();
        assertThat(stateErrorHits)
                .as("OJ-09.E jiuwenbox sandbox must not enter error state; see %s", log)
                .isZero();

        boolean sandboxStdoutOk = runSlice.lines()
                .filter(line -> line.contains("Tool result:"))
                .filter(line -> !line.contains("Tool result: None"))
                .filter(line -> line.contains("code=0"))
                .anyMatch(line -> line.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT)));
        assertThat(sandboxStdoutOk)
                .as("OJ-09.E sandbox tool must return code=0 with stdout containing '%s' (not LLM-only reply); see %s",
                        expected, log)
                .isTrue();
    }

    /** OJ-10.P — hotel HTTP probe after an error / timeout scenario. */
    public static void assertHotelResponds(SutStack stack) throws IOException, InterruptedException {
        String url = stack.baseUrl(OpenjiuwenStackSupport.HOTEL) + "/.well-known/agent.json";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("OJ-10.P hotel agent card probe %s", url)
                .isEqualTo(200);
    }

    /** OJ-10.T — timeout or failure is visible to the user. */
    public static void assertTimeoutOrFailureVisible(
            OpenjiuwenStreamingSingleTurnRunner.Result result,
            List<String> timeoutKeywords,
            String label) {
        boolean nonCompleted = result.terminalState() != org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED;
        boolean keywordHit = containsAnyIgnoreCase(result.responseText(), timeoutKeywords);
        assertThat(nonCompleted || keywordHit)
                .as("%s timeout/failure visibility (state=%s, keywords=%s, text truncated=%s)",
                        label,
                        result.terminalState(),
                        timeoutKeywords,
                        truncate(result.responseText(), 200))
                .isTrue();
    }

    /** OJ-10.E — illegal command error is visible in the reply. */
    public static void assertErrorVisible(
            OpenjiuwenStreamingSingleTurnRunner.Result result,
            List<String> errorKeywords,
            String label) {
        assertThat(result.responseText())
                .as("%s response text", label)
                .isNotBlank();
        assertThat(containsAnyIgnoreCase(result.responseText(), errorKeywords))
                .as("%s error keywords (state=%s, text truncated=%s)",
                        label,
                        result.terminalState(),
                        truncate(result.responseText(), 200))
                .isTrue();
    }

    /**
     * Resolves jiuwenbox endpoint from env ({@code SANDBOX_HEALTH_URL} or {@code SANDBOX_HOST}/
     * {@code SANDBOX_PORT}) with testdata default fallback.
     */
    public static SandboxEndpoint resolveSandboxEndpoint(String defaultHealthUrl) {
        String healthUrl = firstNonBlank(
                System.getenv("SANDBOX_HEALTH_URL"),
                defaultHealthUrl);
        URI uri = URI.create(healthUrl);
        String host = firstNonBlank(System.getenv("SANDBOX_HOST"), uri.getHost());
        int port = parsePort(System.getenv("SANDBOX_PORT"), uri.getPort() > 0 ? uri.getPort() : 8321);
        String normalizedHealth = healthUrl.endsWith("/health")
                ? healthUrl
                : "http://" + host + ":" + port + "/health";
        return new SandboxEndpoint(host, String.valueOf(port), normalizedHealth);
    }

    private static int parsePort(String envPort, int fallback) {
        if (envPort == null || envPort.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(envPort.trim());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second;
    }

    private static boolean containsAnyIgnoreCase(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private static String sliceSinceLast(String logContent, String marker) {
        int lastIdx = logContent.toLowerCase(Locale.ROOT).lastIndexOf(marker.toLowerCase(Locale.ROOT));
        if (lastIdx < 0) {
            return logContent;
        }
        return logContent.substring(lastIdx);
    }

    private static String lastLineContaining(String logContent, String needle) {
        String last = null;
        for (String line : logContent.split("\n")) {
            if (line.contains(needle)) {
                last = line;
            }
        }
        return last;
    }

    private static Path hotelStdoutLog(SutStack stack) {
        var instance = stack.managedInstance(OpenjiuwenStackSupport.HOTEL);
        assertThat(instance)
                .as("managed hotel agent for sandbox log gate")
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }

    public record SandboxEndpoint(String host, String port, String healthUrl) {
    }
}
