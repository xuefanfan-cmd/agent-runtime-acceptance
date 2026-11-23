package com.huawei.ascend.sit.judge;

import java.util.Map;
import java.util.TreeMap;

/**
 * What a run was judged against besides the artifact under test: the test code revision, the contract baseline the
 * expectations cite, the testplan revision and the build manifest of the carrier. A verdict that changes between two
 * runs can then be attributed to a changed product, a changed judge or a changed expectation.
 *
 * <p>The launcher declares each item as a system property {@code acceptance.provenance.<key>}; every result record
 * carries all of them and the report header lists them, flagging runs whose records disagree.</p>
 */
public final class RunProvenance {
    public static final String PREFIX = "acceptance.provenance.";

    private RunProvenance() {}

    /** Declared provenance of this JVM's run, keyed without the prefix, in stable order; blank values are omitted. */
    public static Map<String, String> current() {
        Map<String, String> declared = new TreeMap<>();
        System.getProperties().forEach((k, v) -> {
            String key = String.valueOf(k);
            String value = String.valueOf(v).trim();
            if (key.startsWith(PREFIX) && key.length() > PREFIX.length() && !value.isEmpty()) {
                declared.put(key.substring(PREFIX.length()), value);
            }
        });
        return declared;
    }
}
