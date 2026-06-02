package com.huawei.ascend.sit.base;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

/**
 * Base class for end-to-end acceptance tests (full chain, no Mock).
 *
 * <p>E2E tests exercise the complete SUT stack from client entry point
 * to final response, with no mocked components. They verify that
 * architectural goals are met across all module boundaries.</p>
 *
 * <p>Automatically waits for SUT health before running any test,
 * ensuring all services are fully initialized.</p>
 *
 * <p>All E2E tests are tagged {@code "e2e"} by default.</p>
 */
@Tag("e2e")
public abstract class BaseE2ETest extends BaseIntegrationTest {

    @BeforeAll
    static void ensureSutHealthy() {
        awaitSutHealthy();
    }
}
