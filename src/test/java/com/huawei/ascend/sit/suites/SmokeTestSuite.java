package com.huawei.ascend.sit.suites;

import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Smoke test suite — core main-path tests across all layers.
 *
 * <p>Picks up every test tagged {@code "smoke"} across component,
 * integration, and e2e layers. Designed for fast feedback in CI pipelines.</p>
 *
 * <p>Run via Maven:
 * <pre>
 * mvn test -Dsurefire.includeTags=smoke
 * </pre>
 * Or via JUnit Platform Suite:
 * <pre>
 * mvn test -Dtest=SmokeTestSuite
 * </pre>
 */
@Suite
@SelectPackages("com.huawei.ascend.sit.cases")
@IncludeTags("smoke")
public class SmokeTestSuite {
}
