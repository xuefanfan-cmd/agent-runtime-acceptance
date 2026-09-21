package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.cases.integration.react_travel.HostedRemoteCatalogBlackboxSupport;
import com.huawei.ascend.sit.config.TestConfig;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("integration")
@Tag("openjiuwen")
@Tag("deepagent")
@Tag("feat-037")
@Tag("blackbox")
@Feature("FEAT-037: 单进程多智能体实例托管")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeepAgentHostedRemoteCatalogBlackboxTest {
    private static final String HOSTED_AGENT = "hosted-deep";

    private TestConfig config;
    private HostedRemoteCatalogBlackboxSupport.ToolCallingOpenAiFixture model;

    @BeforeAll
    void setUpFixture() {
        config = TestConfig.load();
        model = HostedRemoteCatalogBlackboxSupport.ToolCallingOpenAiFixture.start();
    }

    @AfterAll
    void closeFixture() {
        if (model != null) {
            model.close();
        }
    }

    private HostedRemoteCatalogBlackboxSupport driver() {
        return new HostedRemoteCatalogBlackboxSupport(config, HOSTED_AGENT, true, model);
    }

    @Test
    @Tag("story-feat-037-t36")
    @Story("FEAT-037.T36: 远端目录继承、追加和覆盖闭环")
    @DisplayName("FEAT-037 T36 DeepAgent 远端目录按实例继承追加并完整覆盖")
    void t36InheritanceAppendAndCompleteOverride() throws Exception {
        driver().t36InheritanceAppendAndCompleteOverride();
    }

    @Test
    @Tag("story-feat-037-t37")
    @Story("FEAT-037.T37: 局部发现失败不回退且独立恢复")
    @DisplayName("FEAT-037 T37 DeepAgent 局部发现失败不回退全局且独立恢复")
    void t37FailedLocalDiscoveryDoesNotFallBackAndRecoversIndependently() throws Exception {
        driver().t37FailedLocalDiscoveryDoesNotFallBackAndRecoversIndependently();
    }

    @Test
    @Tag("story-feat-037-t38")
    @Story("FEAT-037.T38: 全局后续发现传播不覆盖局部名称")
    @DisplayName("FEAT-037 T38 DeepAgent 全局后续发现传播且不覆盖局部同名目标")
    void t38LateGlobalDiscoveryPropagatesWithoutReplacingOverride() throws Exception {
        driver().t38LateGlobalDiscoveryPropagatesWithoutReplacingOverride();
    }

    @Test
    @Tag("story-feat-037-t39")
    @Story("FEAT-037.T39: Intent 候选、委派和恢复使用实例有效目录")
    @DisplayName("FEAT-037 T39 DeepAgent Intent 候选委派和恢复按实例目录隔离")
    void t39IntentUsesInstanceCatalogAndRecoversIndependently() throws Exception {
        driver().t39IntentUsesInstanceCatalogAndRecoversIndependently();
    }

    @Test
    @Tag("story-feat-037-t40")
    @Story("FEAT-037.T40: 同名同 URL 的 TLS Client 缓存隔离")
    @DisplayName("FEAT-037 T40 DeepAgent 同名同 URL 的 TLS Client 按实例目录隔离")
    void t40TlsClientCacheIsCatalogScoped() throws Exception {
        driver().t40TlsClientCacheIsCatalogScoped();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HostedRemoteCatalogBlackboxSupport.InvalidRemoteVariant.class)
    @Tag("story-feat-037-t41")
    @Story("FEAT-037.T41: 非法局部远端配置启动失败")
    @DisplayName("FEAT-037 T41 DeepAgent 非法局部远端配置在 ready 前失败")
    void t41InvalidLocalConfigurationFailsBeforeReady(
            HostedRemoteCatalogBlackboxSupport.InvalidRemoteVariant variant) {
        driver().t41InvalidLocalConfigurationFailsBeforeReady(variant);
    }

    @Test
    @Tag("story-feat-037-t42")
    @Story("FEAT-037.T42: 不支持目录绑定的 Caller 拒绝局部配置")
    @DisplayName("FEAT-037 T42 DeepAgent 旧式 Caller 拒绝实例局部远端目录")
    void t42LegacyCallerRejectsLocalCatalog() {
        driver().t42LegacyCallerRejectsLocalCatalog();
    }

    @Test
    @Tag("story-feat-037-t43")
    @Story("FEAT-037.T43: 同一旧式 Caller 保持全局配置兼容")
    @DisplayName("FEAT-037 T43 DeepAgent 旧式 Caller 保持全局远端调用兼容")
    void t43LegacyCallerRetainsGlobalCompatibility() throws Exception {
        driver().t43LegacyCallerRetainsGlobalCompatibility();
    }
}
