package com.huawei.ascend.sit.judge;

/** The recorded acceptance findings did not pass; the result record holds the per-scenario detail. */
public final class AcceptanceFailure extends AssertionError {
    public AcceptanceFailure(String message) {
        super(message);
    }
}
