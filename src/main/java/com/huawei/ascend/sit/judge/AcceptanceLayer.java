package com.huawei.ascend.sit.judge;

/**
 * The acceptance layer that carries a scenario. A requirement the public surface cannot observe moves to the
 * next layer instead of being skipped; it is NOT_OBSERVED(protocol_unobservable) only when every layer is blind.
 */
public enum AcceptanceLayer {
    /** A real SUT process observed only through its public interfaces (HTTP, SSE, diagnostics, process control). */
    PUBLIC_SURFACE,
    /** The real product classes driven in-process, for mechanisms the public surface cannot observe. */
    COMPONENT
}
