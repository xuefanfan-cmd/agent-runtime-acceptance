package com.huawei.ascend.sit.base;

import org.junit.jupiter.api.Tag;

/**
 * Base class for component-level tests (single module boundary).
 *
 * <p>Component tests verify a single module's external contract in isolation.
 * External unstable dependencies (e.g. LLM APIs) may be mocked, but
 * infrastructure dependencies (DB, Kafka) should remain real.</p>
 *
 * <p>Inherits infrastructure initialization from {@link BaseIntegrationTest}.
 * Does NOT wait for full SUT health — assumes the relevant component
 * surface is already reachable.</p>
 *
 * <p>All component-level tests are tagged {@code "component"} by default.</p>
 */
@Tag("component")
public abstract class BaseComponentTest extends BaseIntegrationTest {

    // Component-level tests share the same client infrastructure
    // but may selectively mock specific endpoints or services.
    // Override @BeforeEach in subclasses to set up mocks as needed.
}
