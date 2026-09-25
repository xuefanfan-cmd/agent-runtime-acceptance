package com.huawei.ascend.sit.cases.integration.react_travel;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@Tag("openjiuwen")
@Tag("react-travel")
@Tag("feat-037")
@Tag("blackbox")
@Feature("FEAT-037: 单进程多智能体实例托管")
class ReactAgentMultiInstanceHostingBlackboxTest extends BaseManagedStackTest {

    private static final String HOSTED_AGENT = "hosted-react";
    private final MultiInstanceHostingBlackboxSupport.OpenAiEchoFixture model =
            MultiInstanceHostingBlackboxSupport.OpenAiEchoFixture.start();

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent(HOSTED_AGENT,
                agent -> MultiInstanceHostingBlackboxSupport.configureHosted(agent, model));
    }

    @AfterAll
    void closeModelFixture() {
        model.close();
    }

    private MultiInstanceHostingBlackboxSupport driver() {
        return new MultiInstanceHostingBlackboxSupport(config, stack, HOSTED_AGENT, "mainplan", "REACT",
                "HOSTED_TRAVEL_AGENT_A", "HOSTED_TRAVEL_AGENT_B", model,
                "openjiuwen.travel.mainplan.llm");
    }

    @Test
    @Tag("story-feat-037-t01")
    @Story("FEAT-037.T01: 双实例注册与目录")
    @DisplayName("FEAT-037 T01 ReactAgent 双实例注册并发布目录")
    void t01HostedCatalogPublishesBothInstances() throws Exception {
        driver().t01HostedCatalogPublishesBothInstances();
    }

    @Test
    @Tag("story-feat-037-t02")
    @Story("FEAT-037.T02: 非法实例名启动失败")
    @DisplayName("FEAT-037 T02 ReactAgent 非法实例名使进程启动失败")
    void t02InvalidInstanceNameFailsStartup() {
        driver().t02InvalidInstanceNameFailsStartup();
    }

    @Test
    @Tag("story-feat-037-t03")
    @Story("FEAT-037.T03: 重复实例名启动失败")
    @DisplayName("FEAT-037 T03 ReactAgent 重复实例名快速失败")
    void t03DuplicateInstanceNameFailsFast() {
        driver().t03DuplicateInstanceNameFailsFast();
    }

    @Test
    @Tag("story-feat-037-t04")
    @Story("FEAT-037.T04: 部分装配失败回滚")
    @DisplayName("FEAT-037 T04 ReactAgent 部分装配失败时整体回滚")
    void t04PartialAssemblyRollsBack() {
        driver().t04PartialAssemblyRollsBack();
    }

    @Test
    @Tag("story-feat-037-t05")
    @Story("FEAT-037.T05: 默认实例选择")
    @DisplayName("FEAT-037 T05 ReactAgent 默认取首项且允许显式覆盖")
    void t05CatalogOrderSelectsDefault() throws Exception {
        driver().t05CatalogOrderSelectsDefault();
    }

    @Test
    @Tag("story-feat-037-t06")
    @Story("FEAT-037.T06: 实例级 Agent Card")
    @DisplayName("FEAT-037 T06 ReactAgent 实例 Card 指向对应实例入口")
    void t06InstanceCardRoutesToTarget() throws Exception {
        driver().t06InstanceCardRoutesToTarget();
    }

    @Test
    @Tag("story-feat-037-t07")
    @Story("FEAT-037.T07: 未知实例 Card")
    @DisplayName("FEAT-037 T07 ReactAgent 未知实例 Card 返回 404")
    void t07UnknownInstanceCardReturnsNotFound() throws Exception {
        driver().t07UnknownInstanceCardReturnsNotFound();
    }

    @Test
    @Tag("story-feat-037-t08")
    @Story("FEAT-037.T08: 实例路径同步路由")
    @DisplayName("FEAT-037 T08 ReactAgent 同步请求按实例路径隔离路由")
    void t08PathSyncRoutesToTarget() throws Exception {
        driver().t08PathSyncRoutesToTarget();
    }

    @Test
    @Tag("story-feat-037-t09")
    @Story("FEAT-037.T09: 实例路径流式路由")
    @DisplayName("FEAT-037 T09 ReactAgent 流式请求按实例路径隔离路由")
    void t09PathStreamingRoutesToTarget() throws Exception {
        driver().t09PathStreamingRoutesToTarget();
    }

    @Test
    @Tag("story-feat-037-t10")
    @Story("FEAT-037.T10: GetTask 归属隔离")
    @DisplayName("FEAT-037 T10 ReactAgent GetTask 仅对归属实例可见")
    void t10GetTaskIsInstanceScoped() throws Exception {
        driver().t10GetTaskIsInstanceScoped();
    }

    @Test
    @Tag("story-feat-037-t11")
    @Story("FEAT-037.T11: SubscribeToTask 归属隔离")
    @DisplayName("FEAT-037 T11 ReactAgent SubscribeToTask 仅对归属实例可见")
    void t11SubscribeIsInstanceScoped() throws Exception {
        driver().t11SubscribeIsInstanceScoped();
    }

    @Test
    @Tag("story-feat-037-t13")
    @Story("FEAT-037.T13: 根 A2A 默认路由")
    @DisplayName("FEAT-037 T13 ReactAgent 根 A2A 同步和流式请求路由到默认实例")
    void t13RootA2ADefaultsToFirstInstance() throws Exception {
        driver().t13RootA2ADefaultsToFirstInstance();
    }

    @Test
    @Tag("story-feat-037-t14")
    @Story("FEAT-037.T14: REST 实例选择")
    @DisplayName("FEAT-037 T14 ReactAgent REST 入口支持默认和显式实例选择")
    void t14RestQueryUsesAgentSelection() throws Exception {
        driver().t14RestQueryUsesAgentSelection();
    }

    @Test
    @Tag("story-feat-037-t15")
    @Story("FEAT-037.T15: 未知实例诊断")
    @DisplayName("FEAT-037 T15 ReactAgent 未知实例返回明确错误且不列出可用实例")
    void t15UnknownInstanceReturnsProtocolErrorWithoutCatalog() throws Exception {
        driver().t15UnknownInstanceReturnsProtocolErrorWithoutCatalog();
    }

    @Test
    @Tag("story-feat-037-t16")
    @Story("FEAT-037.T16: 路由身份不可伪造")
    @DisplayName("FEAT-037 T16 ReactAgent metadata 不能覆盖入口选择的实例")
    void t16MetadataCannotForgeRoute() throws Exception {
        driver().t16MetadataCannotForgeRoute();
    }

    @Test
    @Tag("story-feat-037-t17")
    @Story("FEAT-037.T17: 响应格式兼容")
    @DisplayName("FEAT-037 T17 ReactAgent A2A 保持原响应格式且不注入托管身份")
    void t17ResponsePreservesProtocolWithoutHostedMetadata() throws Exception {
        driver().t17ResponsePreservesProtocolWithoutHostedMetadata();
    }

    @Test
    @Tag("story-feat-037-t18")
    @Story("FEAT-037.T18: 内存同步会话隔离")
    @DisplayName("FEAT-037 T18 ReactAgent 相同会话标识在实例间保持内存隔离")
    void t18MemorySessionsSameIdAreIsolated() throws Exception {
        driver().t18MemorySessionsSameIdAreIsolated();
    }

    @Test
    @Tag("story-feat-037-t19")
    @Story("FEAT-037.T19: 内存流式会话隔离")
    @DisplayName("FEAT-037 T19 ReactAgent 相同会话标识的流式历史保持隔离")
    void t19MemoryStreamingSameIdIsolated() throws Exception {
        driver().t19MemoryStreamingSameIdIsolated();
    }

    @Test
    @Tag("story-feat-037-t20")
    @Story("FEAT-037.T20: 内存异步任务隔离")
    @DisplayName("FEAT-037 T20 ReactAgent 相同会话标识的异步任务保持归属隔离")
    void t20MemoryAsyncTasksSameIdIsolated() throws Exception {
        driver().t20MemoryAsyncTasksSameIdIsolated();
    }

    @Test
    @Tag("story-feat-037-t21")
    @Story("FEAT-037.T21: Redis 同步会话隔离")
    @DisplayName("FEAT-037 T21 ReactAgent Redis 重启恢复后同步会话保持实例隔离")
    void t21RedisSyncSessionsSameIdIsolated() throws Exception {
        driver().t21RedisSyncSessionsSameIdIsolated();
    }

    @Test
    @Tag("story-feat-037-t22")
    @Story("FEAT-037.T22: Redis 流式会话隔离")
    @DisplayName("FEAT-037 T22 ReactAgent Redis 重启恢复后流式会话保持实例隔离")
    void t22RedisStreamingSessionsSameIdIsolated() throws Exception {
        driver().t22RedisStreamingSessionsSameIdIsolated();
    }

    @Test
    @Tag("story-feat-037-t23")
    @Story("FEAT-037.T23: Redis 异步任务隔离")
    @DisplayName("FEAT-037 T23 ReactAgent Redis 重启恢复后任务保持实例归属")
    void t23RedisAsyncTasksSameIdIsolated() throws Exception {
        driver().t23RedisAsyncTasksSameIdIsolated();
    }

    @Test
    @Tag("story-feat-037-t25")
    @Story("FEAT-037.T25: 实例生命周期一次性")
    @DisplayName("FEAT-037 T25 ReactAgent 每个托管实例各启动和停止一次")
    void t25EachInstanceLifecycleRunsOnce() throws Exception {
        driver().t25EachInstanceLifecycleRunsOnce();
    }

    @Test
    @Tag("story-feat-037-t26")
    @Story("FEAT-037.T26: 共享会话清理边界")
    @DisplayName("FEAT-037 T26 ReactAgent 同名会话 reset 后保持目标实例路由")
    void t26SharedSessionResetKeepsTargetRouting() throws Exception {
        driver().t26SharedSessionResetKeepsTargetRouting();
    }

    @Test
    @Tag("story-feat-037-t27")
    @Story("FEAT-037.T27: 进程级额度共享")
    @DisplayName("FEAT-037 T27 ReactAgent 两个实例共享进程级并发额度")
    void t27ProcessQuotaIsShared() throws Exception {
        driver().t27ProcessQuotaIsShared();
    }

    @Test
    @Tag("story-feat-037-t28")
    @Story("FEAT-037.T28: 单实例零影响兼容")
    @DisplayName("FEAT-037 T28 单实例 ReactAgent 的 Card、同步和流式入口保持兼容")
    void t28SingleHandlerApplicationRemainsCompatible() throws Exception {
        driver().t28SingleHandlerApplicationRemainsCompatible();
    }

    @Test
    @Tag("story-feat-037-t29")
    @Story("FEAT-037.T29: 影子任务归属隔离")
    @DisplayName("FEAT-037 T29 ReactAgent 影子任务保持托管实例归属")
    void t29ShadowTasksKeepInstanceOwnership() {
        driver().t29ShadowTasksKeepInstanceOwnership();
    }

    @Test
    @Tag("story-feat-037-t30")
    @Story("FEAT-037.T30: 错误实例使用目标局部 TaskNotFound")
    @DisplayName("FEAT-037 T30 ReactAgent 错误实例访问返回既有 TaskNotFound 且不跨实例搜索")
    void t30WrongInstanceUsesTaskNotFoundWithoutOwnerLookup() throws Exception {
        driver().t30WrongInstanceUsesTaskNotFoundWithoutOwnerLookup();
    }

    @Test
    @Tag("story-feat-037-t31")
    @Story("FEAT-037.T31: 活跃任务进程汇总")
    @DisplayName("FEAT-037 T31 ReactAgent 无参数查询汇总两个实例的活动任务")
    void t31ActiveTaskQueryAggregatesProcessTasks() throws Exception {
        driver().t31ActiveTaskQueryAggregatesProcessTasks();
    }

    @Test
    @Tag("story-feat-037-t32")
    @Story("FEAT-037.T32: 活跃任务按实例筛选")
    @DisplayName("FEAT-037 T32 ReactAgent agentId 仅返回目标实例任务且保留进程额度")
    void t32ActiveTaskQueryFiltersKnownInstance() throws Exception {
        driver().t32ActiveTaskQueryFiltersKnownInstance();
    }

    @Test
    @Tag("story-feat-037-t33")
    @Story("FEAT-037.T33: 已注册空闲实例负载")
    @DisplayName("FEAT-037 T33 ReactAgent 已注册空闲实例返回 200 和空任务列表")
    void t33ActiveTaskQueryReturnsEmptyForKnownIdleInstance() throws Exception {
        driver().t33ActiveTaskQueryReturnsEmptyForKnownIdleInstance();
    }

    @Test
    @Tag("story-feat-037-t34")
    @Story("FEAT-037.T34: 空白实例负载参数")
    @DisplayName("FEAT-037 T34 ReactAgent 空或空白 agentId 返回 400")
    void t34ActiveTaskQueryRejectsBlankInstance() throws Exception {
        driver().t34ActiveTaskQueryRejectsBlankInstance();
    }

    @Test
    @Tag("story-feat-037-t35")
    @Story("FEAT-037.T35: 未知实例负载查询")
    @DisplayName("FEAT-037 T35 ReactAgent 未知 agentId 返回 404 且不回退")
    void t35ActiveTaskQueryRejectsUnknownInstanceWithoutFallback() throws Exception {
        driver().t35ActiveTaskQueryRejectsUnknownInstanceWithoutFallback();
    }
}
