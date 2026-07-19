package com.huawei.ascend.sit.transport;

import java.net.URI;
import java.util.function.Consumer;

/**
 * The two-method REST IO seam used by REST Interaction adapters ({@code RestQueryTransport}). The
 * concrete JDK-{@code HttpClient} wire {@link RestExchange} implements it; tests supply a stub
 * without subclassing the {@code final} concrete class. Signature-identical to {@link RestExchange}'s
 * two public methods.
 */
public interface RestIo {
    void postSse(URI uri, String jsonBody, Consumer<String> lineConsumer);
    String postJson(URI uri, String jsonBody);
}
