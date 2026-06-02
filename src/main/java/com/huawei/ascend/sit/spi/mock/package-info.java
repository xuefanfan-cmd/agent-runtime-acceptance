/**
 * SPI mock implementations for test isolation.
 *
 * <p>Place mock/stub implementations of SPIs published by spring-ai-ascend
 * here. These can be loaded via Java's {@link java.util.ServiceLoader}
 * mechanism to inject controlled behaviour during integration tests.</p>
 *
 * <p>Usage: Implement the SPI interface from spring-ai-ascend, then register
 * via {@code META-INF/services/<fully.qualified.SpiInterface>}.</p>
 *
 * <p><b>Note:</b> This package is reserved for future use. Currently, the
 * acceptance tests operate as black-box tests against running SUT instances
 * and do not inject SPI mocks.</p>
 */
package com.huawei.ascend.sit.spi.mock;
