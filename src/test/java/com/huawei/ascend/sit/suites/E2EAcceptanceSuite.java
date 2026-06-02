package com.huawei.ascend.sit.suites;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * End-to-end acceptance suite — full chain validation, no mocks.
 *
 * <p>Runs every test under {@code cases/e2e/}, simulating real user
 * journeys through the complete SUT stack.</p>
 *
 * <p>Corresponds to SIT-E2E-01~06 in the integration test design.</p>
 *
 * <p>Run via Maven Failsafe (integration-test phase):
 * <pre>
 * mvn verify -Dtest=E2EAcceptanceSuite
 * </pre>
 */
@Suite
@SelectPackages("com.huawei.ascend.sit.cases.e2e")
public class E2EAcceptanceSuite {
}
