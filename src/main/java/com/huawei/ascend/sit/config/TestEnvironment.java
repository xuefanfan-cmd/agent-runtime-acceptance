package com.huawei.ascend.sit.config;

/**
 * Supported test environment profiles.
 *
 * <p>Each profile corresponds to an {@code application-<name>.yml}
 * configuration file under {@code src/test/resources/}.</p>
 */
public enum TestEnvironment {

    /** Local development environment (default) */
    LOCAL,

    /** System Integration Testing environment */
    SIT,

    /** User Acceptance Testing environment */
    UAT;

    /**
     * Resolve from environment variable or system property.
     * Falls back to {@code LOCAL} if not set.
     */
    public static TestEnvironment current() {
        String env = System.getProperty("test.env",
                System.getenv().getOrDefault("TEST_ENV", "LOCAL"));
        return valueOf(env.toUpperCase());
    }
}
