/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public abstract class StudioDslContractTestBase extends BaseManagedStackTest {
    /**
     * 进程内契约测试直接用 SDK 装配 / 执行 Studio IR。PR707 起会话 KV 取用即 fail-fast：
     * 未安装 {@code RuntimeRedisClient} 时会抛 {@code STUDIO-DSL-REDIS-CLIENT-UNAVAILABLE}。
     *
     * <p>本基类下的用例验证的是 IR 装配与节点语义，不验证 Redis 交互，因此统一注入产品自带的
     * 进程内实现；需要验证「未配置即 fail-fast」的用例请勿继承本基类。</p>
     */
    @BeforeAll
    static void installInMemoryConversationVals() {
        ConversationValsStores.setDefault(ConversationValsStores.memoryStore());
    }

    @AfterAll
    static void clearConversationValsOverride() {
        ConversationValsStores.setDefault(null);
    }

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config);
    }
}
