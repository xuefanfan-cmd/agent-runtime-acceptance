package com.huawei.ascend.sit.transport;

import java.net.http.HttpClient;

/**
 * Framework HTTP clients for cleartext SUT endpoints. The JDK's {@link HttpClient#newHttpClient()}
 * defaults to {@link HttpClient.Version#HTTP_2}, which on a {@code http://} URI emits an RFC 7540 h2c
 * upgrade — {@code Upgrade: h2c} + {@code Connection: Upgrade, HTTP2-Settings} + {@code HTTP2-Settings}
 * headers — that a strict/custom SUT server (agent card, {@code /v1/query}, envmock mid-platform) may
 * reject. {@link #newHttp1Client()} is the only client the framework should build for a cleartext SUT
 * endpoint: bare HTTP/1.1, no upgrade headers. (HTTPS uses ALPN, so the version is moot there, but
 * pinning HTTP/1.1 is harmless and uniform.)
 *
 * <p>One factory = one rule, so the wire ({@link RestExchange}), the SUT readiness probe
 * ({@code ProcessLauncher}), and the mid-platform calls ({@code MidConversationSupport}) all speak the
 * same bare HTTP/1.1. Guarded by {@code RestExchangeHttpVersionTest} (wire-level) + {@code HttpClientsTest}
 * (version contract).
 */
public final class HttpClients {

    private HttpClients() { }

    /** An HTTP/1.1 client — the only kind the framework uses to a cleartext SUT endpoint. */
    public static HttpClient newHttp1Client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }
}
