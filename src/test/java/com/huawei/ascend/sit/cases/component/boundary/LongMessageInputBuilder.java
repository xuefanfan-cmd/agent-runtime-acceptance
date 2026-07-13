package com.huawei.ascend.sit.cases.component.boundary;

/**
 * Builds a travel-scenario long user message for C-07 (template + padding loop).
 */
final class LongMessageInputBuilder {

    private LongMessageInputBuilder() {
    }

    /** Returns text with length {@code >= minInputChars}. */
    static String build(String travelTemplatePrefix, String paddingSentence, int minInputChars) {
        StringBuilder sb = new StringBuilder(travelTemplatePrefix);
        while (sb.length() < minInputChars) {
            sb.append(paddingSentence);
        }
        return sb.toString();
    }
}
