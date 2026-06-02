package com.huawei.ascend.sit.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON utility wrapper around Jackson {@link ObjectMapper}.
 *
 * <p>Provides a shared, pre-configured ObjectMapper instance for
 * consistent serialization/deserialization across the framework.</p>
 */
public final class JsonUtils {

    private JsonUtils() {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .findAndRegisterModules();

    /** Get the shared ObjectMapper instance. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Serialize an object to a JSON string. */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /** Serialize an object to a pretty-printed JSON string. */
    public static String toPrettyJson(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    /** Deserialize a JSON string to the given type. */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON deserialization failed for " + type.getSimpleName(), e);
        }
    }
}
