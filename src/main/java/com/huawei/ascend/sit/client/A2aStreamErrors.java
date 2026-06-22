package com.huawei.ascend.sit.client;

import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * Classifies A2A streaming transport errors that occur when an SSE connection closes normally.
 */
public final class A2aStreamErrors {

    private A2aStreamErrors() {
    }

    /** {@code true} when the SDK cancels the HTTP stream after a normal SSE close. */
    public static boolean isBenignShutdown(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof CancellationException) {
                return true;
            }
            if (current instanceof IOException io && io.getMessage() != null
                    && io.getMessage().toLowerCase().contains("cancel")) {
                return true;
            }
        }
        return false;
    }
}
