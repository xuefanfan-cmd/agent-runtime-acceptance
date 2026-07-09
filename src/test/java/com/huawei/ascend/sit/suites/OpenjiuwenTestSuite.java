package com.huawei.ascend.sit.suites;

import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Openjiuwen travel-chain SIT suite (OJ-01..OJ-11).
 *
 * <p>Run with {@code -Dtest.env=openjiuwen}.</p>
 */
@Suite
@SelectPackages({
        "com.huawei.ascend.sit.cases.component",
        "com.huawei.ascend.sit.cases.integration"
})
@IncludeTags("openjiuwen")
public class OpenjiuwenTestSuite {
}
