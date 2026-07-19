package com.huawei.ascend.sit.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

/**
 * Shared, family-agnostic REST wire on the JDK {@link HttpClient}. One place owns the raw HTTP/SSE
 * mechanics so each family adapter ({@code RestVersatileTransport} for Conversation now, a future
 * {@code RestQueryTransport} for Interaction later) only shapes its URL + body and decodes the reply.
 *
 * <ul>
 *   <li>{@link #postSse(URI, String, Consumer)} — POST + consume a {@code text/event-stream} reply
 *       one line at a time (the caller keeps only {@code data:} frames).</li>
 *   <li>{@link #postJson(URI, String)} — POST + return a JSON reply body (non-streaming mode).</li>
 * </ul>
 *
 * <p>Both throw {@link RuntimeException} on non-200 or IO failure. The caller owns stream-end
 * bookkeeping (e.g. {@code markStreamEnd()} in a {@code finally}) — this wire does not know about
 * collectors.
 */
public final class RestExchange implements RestIo {

    private final HttpClient http;

    public RestExchange() {
        // HTTP/1.1 (not the JDK HTTP_2 default) so a cleartext http:// POST carries no h2c upgrade
        // (Upgrade: h2c / Connection / HTTP2-Settings) that a strict SUT endpoint may reject.
        // See HttpClients + RestExchangeHttpVersionTest.
        this(HttpClients.newHttp1Client());
    }

    /** Test seam: inject a pre-built {@link HttpClient}. */
    RestExchange(HttpClient http) {
        this.http = http;
    }

    /**
     * POST {@code jsonBody} and stream the {@code text/event-stream} reply; each body line is handed
     * to {@code lineConsumer} (caller keeps only {@code data:} lines). Blocks until the stream ends.
     *
     * @throws RuntimeException on non-200 status or transport failure
     */
    public void postSse(URI uri, String jsonBody, Consumer<String> lineConsumer) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        try {
            HttpResponse<java.util.stream.Stream<String>> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());
            int status = resp.statusCode();
            if (status != 200) {
                throw new RuntimeException("REST POST failed: HTTP " + status + " @ " + uri);
            }
            resp.body().forEach(lineConsumer);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new RuntimeException("REST POST error @ " + uri, ex);
        }
    }

    /**
     * POST {@code jsonBody} and return the JSON reply body (non-streaming mode,
     * e.g. {@code /v1/query} with {@code stream:false}).
     *
     * @throws RuntimeException on non-200 status or transport failure
     */
    public String postJson(URI uri, String jsonBody) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status != 200) {
                throw new RuntimeException("REST POST failed: HTTP " + status + " @ " + uri);
            }
            return resp.body();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception ex) {
            throw new RuntimeException("REST POST error @ " + uri, ex);
        }
    }
}
