package com.huawei.ascend.sit.utils;

import com.huawei.ascend.sit.config.TestConfig;

import java.util.Map;

/**
 * Authentication utility for constructing SUT auth context.
 *
 * <p>Aligns with the SUT adapter's auth declaration:
 * <ul>
 *   <li>Scheme: bearer</li>
 *   <li>Token from env var: {@code SPRING_AI_ASCEND_BEARER_TOKEN}</li>
 *   <li>Additional header: {@code X-Tenant-Id} from env var {@code SPRING_AI_ASCEND_TENANT_ID}</li>
 * </ul>
 *
 * <p>Note: When using the A2A SDK Client, auth headers are typically handled
 * by the SDK's transport layer. This utility remains for cases where direct
 * header construction is needed (e.g. cross-tenant isolation tests).</p>
 */
public final class AuthUtils {

    private AuthUtils() {}

    /** HTTP Authorization header name. */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** X-Tenant-Id header name. */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    /** Environment variable name for the bearer token. */
    public static final String BEARER_TOKEN_ENV = "SPRING_AI_ASCEND_BEARER_TOKEN";

    /** Environment variable name for the tenant ID. */
    public static final String TENANT_ID_ENV = "SPRING_AI_ASCEND_TENANT_ID";

    /**
     * Build the standard auth headers map for SUT requests.
     *
     * @param config the test configuration
     * @return a map of header names to values
     */
    public static Map<String, String> buildAuthHeaders(TestConfig config) {
        return Map.of(
                HEADER_AUTHORIZATION, "Bearer " + config.getBearerToken(),
                HEADER_TENANT_ID, config.getTenantId()
        );
    }

    /**
     * Build auth headers for a secondary tenant (cross-tenant isolation tests).
     *
     * @param bearerToken the secondary tenant's bearer token
     * @param tenantId    the secondary tenant's ID
     * @return a map of header names to values
     */
    public static Map<String, String> buildCrossTenantHeaders(String bearerToken, String tenantId) {
        return Map.of(
                HEADER_AUTHORIZATION, "Bearer " + bearerToken,
                HEADER_TENANT_ID, tenantId
        );
    }

    /**
     * Read the bearer token from the environment.
     */
    public static String getTokenFromEnv() {
        String token = System.getenv(BEARER_TOKEN_ENV);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    BEARER_TOKEN_ENV + " environment variable is not set");
        }
        return token;
    }

    /**
     * Read the tenant ID from the environment.
     */
    public static String getTenantIdFromEnv() {
        String tenantId = System.getenv(TENANT_ID_ENV);
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                    TENANT_ID_ENV + " environment variable is not set");
        }
        return tenantId;
    }
}
