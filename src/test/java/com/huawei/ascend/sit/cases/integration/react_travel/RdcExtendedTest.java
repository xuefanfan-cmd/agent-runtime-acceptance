package com.huawei.ascend.sit.cases.integration.react_travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.*;

/**
 * FEAT-015 — 多实例去重 / 多版本共存 / 自定义 Provider。
 * 需 SutStack 支持多实例或自定义 Provider Bean，当前 @Disabled。
 */
@Tag("integration") @Tag("react-travel") @Tag("feat-015")
@Feature("FEAT-015: Agent Card 注册与发现")
@Disabled("需 SutStack 支持同 agent 双实例 (dedup) / 双版本 jar (coexist) / 自定义 Provider Bean (custom)")
class RdcExtendedTest {

    @Test @DisplayName("多实例发布同一 Card → 一个候选")
    @Story("rdc.multi-instance-dedup: 多实例去重")
    void multiInstanceDedupTest() {
        // 前置：2 个 hotel 实例暴露相同 version 的 card → discover 返回 1 个候选
    }

    @Test @DisplayName("多版本共存 → 版本过滤")
    @Story("rdc.multi-version-coexist: 多版本共存")
    void multiVersionCoexistTest() {
        // 前置：hotel v1.0.0 和 v2.0.0 共存 → 不加约束得 2 候选，加 capabilityVersion=1.0.0 得 1 个
    }

    @Test @DisplayName("自定义 Provider Bean → binding-defaults 生效")
    @Story("rdc.custom-provider: 自定义 Provider")
    void customProviderIntegrationTest() {
        // 前置：实现 DeploymentDiscoveryProvider 接口并注册为 Spring Bean
    }
}
