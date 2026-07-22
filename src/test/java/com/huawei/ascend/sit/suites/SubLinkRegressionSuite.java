package com.huawei.ascend.sit.suites;



import org.junit.platform.suite.api.ExcludeTags;

import org.junit.platform.suite.api.SelectPackages;

import org.junit.platform.suite.api.Suite;



/**

 * Sub-chain regression suite — all integration-level tests.

 *

 * <p>Runs every test under {@code cases/integration/}, validating

 * 2~3 module combinations and inter-module contracts.</p>

 *

 * <p>Excludes {@code openjiuwen} tests (require {@code -Dtest.env=openjiuwen}).</p>

 *

 * <p>Corresponds to SIT-SC-01~05 in the integration test design.</p>

 *

 * <p>Run via Maven:

 * <pre>

 * mvn test -Dtest=SubLinkRegressionSuite

 * </pre>

 */

@Suite

@SelectPackages({
        "com.huawei.ascend.sit.cases.component.workflow_call",
        "com.huawei.ascend.sit.cases.integration.workflow_call"
})
public class SubLinkRegressionSuite {

}

