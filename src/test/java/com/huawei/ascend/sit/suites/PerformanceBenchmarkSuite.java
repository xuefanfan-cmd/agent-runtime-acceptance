package com.huawei.ascend.sit.suites;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Performance benchmark suite — long-running, high-concurrency tests.
 *
 * <p>Runs every test under {@code cases/performance/}. These tests
 * are resource-intensive and should be run on dedicated hardware
 * matching production topology.</p>
 *
 * <p>Corresponds to SIT-PERF-01~08 in the integration test design.</p>
 *
 * <p>Run via Maven (separate profile or explicit invocation):
 * <pre>
 * mvn test -Dtest=PerformanceBenchmarkSuite -Dgroups=performance
 * </pre>
 */
@Suite
@SelectPackages("com.huawei.ascend.sit.cases.performance")
public class PerformanceBenchmarkSuite {
}
