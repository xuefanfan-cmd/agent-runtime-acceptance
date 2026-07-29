package com.huawei.ascend.sit.conversation;

import java.util.Map;

/**
 * A declared manual-step selection: an optional step-id label (matched against the current step when
 * non-null) and the key-value form inputs to inject. Extracted from {@link Turn} so {@link DriveMode}
 * subtypes can reference it.
 */
public record DeclaredSelection(String label, Map<String, String> kv) {
    public DeclaredSelection {
        kv = kv == null ? Map.of() : Map.copyOf(kv);
    }
}
