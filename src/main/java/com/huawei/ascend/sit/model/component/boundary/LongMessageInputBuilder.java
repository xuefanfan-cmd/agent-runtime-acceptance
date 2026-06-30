package com.huawei.ascend.sit.model.component.boundary;

/**
 * Builds a travel-scenario long user message for C-07 (template + padding loop).
 */
public final class LongMessageInputBuilder {

    private LongMessageInputBuilder() {
    }

    /** Returns text with length {@code >= scenario.minInputChars()}. */
    public static String build(LongTravelMessageScenarioData scenario) {
        StringBuilder sb = new StringBuilder(scenario.travelTemplatePrefix());
        while (sb.length() < scenario.minInputChars()) {
            sb.append(scenario.paddingSentence());
        }
        return sb.toString();
    }
}
