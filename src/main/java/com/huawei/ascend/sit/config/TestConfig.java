package com.huawei.ascend.sit.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Test framework configuration manager.
 *
 * <p>Reads environment-specific YAML configuration files and provides
 * typed access to SUT connection parameters, auth credentials, and
 * timeout settings.</p>
 *
 * <p>Configuration sources (priority high → low):
 * <ol>
 *   <li>Environment variables (e.g. {@code SPRING_AI_ASCEND_BEARER_TOKEN})</li>
 *   <li>System properties (e.g. {@code -Dsut.base.url=...})</li>
 *   <li>YAML file matching the active {@link TestEnvironment} profile</li>
 * </ol>
 */
public class TestConfig {

    private static final String CONFIG_PREFIX = "application-";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final Map<String, Object> properties;

    private TestConfig(Map<String, Object> properties) {
        this.properties = properties;
    }

    /**
     * Load configuration for the given environment.
     */
    public static TestConfig load(TestEnvironment env) {
        String filename = CONFIG_PREFIX + env.name().toLowerCase() + ".yml";
        try (InputStream is = TestConfig.class.getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                throw new IllegalStateException("Configuration file not found: " + filename);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> props = YAML_MAPPER.readValue(is, Map.class);
            return new TestConfig(props);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load configuration: " + filename, e);
        }
    }

    /**
     * Load configuration for the current environment.
     */
    public static TestConfig load() {
        return load(TestEnvironment.current());
    }

    /**
     * Get a string property, with fallback to system property then environment variable.
     */
    public String getString(String key) {
        // 1. System property override
        String sysProp = System.getProperty(key.replace('.', '_').replace('_', '-'));
        if (sysProp != null) {
            return sysProp;
        }

        // 2. Environment variable override (dot → underscore, uppercase)
        String envVar = System.getenv(key.toUpperCase().replace('.', '_'));
        if (envVar != null) {
            return envVar;
        }

        // 3. YAML value
        return getNestedValue(key);
    }

    /**
     * Get a string property with a default value.
     */
    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Get an integer property.
     */
    public int getInt(String key, int defaultValue) {
        String value = getString(key);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    /**
     * Get a long property.
     */
    public long getLong(String key, long defaultValue) {
        String value = getString(key);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    // --- Convenience accessors for common SUT config ---

    /** SUT base URL (e.g. http://localhost:8080) */
    public String getBaseUrl() {
        return getString("sut.base.url", "http://localhost:8080");
    }

    /** Bearer token for authentication */
    public String getBearerToken() {
        return getString("sut.auth.bearer-token",
                System.getenv("SPRING_AI_ASCEND_BEARER_TOKEN"));
    }

    /** Tenant ID for X-Tenant-Id header */
    public String getTenantId() {
        return getString("sut.auth.tenant-id",
                System.getenv("SPRING_AI_ASCEND_TENANT_ID"));
    }

    /** Default poll timeout in seconds for async operations */
    public int getPollTimeoutSeconds() {
        return getInt("sut.timeout.poll-seconds", 30);
    }

    /** Default poll interval in milliseconds */
    public int getPollIntervalMs() {
        return getInt("sut.timeout.poll-interval-ms", 1000);
    }

    // --- Internal ---

    @SuppressWarnings("unchecked")
    private String getNestedValue(String dottedKey) {
        String[] parts = dottedKey.split("\\.");
        Object current = properties;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        return current != null ? current.toString() : null;
    }
}
