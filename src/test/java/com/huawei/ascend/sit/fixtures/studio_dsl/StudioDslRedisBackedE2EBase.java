/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

/**
 * 需要拉起 {@code studio-dsl-ir-sit} 宿主的标准栈基类。
 *
 * <p>PR707 起 studio-dsl 的会话变量与 Start 节点装配统一经 {@code RuntimeRedisClient}，
 * 未装配即 fail-fast（{@code STUDIO-DSL-REDIS-CLIENT-UNAVAILABLE}）。因此凡拉起该宿主的用例
 * 都必须注入客户端：这里统一开启 runtime Redis 中间件，并把 Testcontainers Redis 的
 * host/port 注入 {@code openjiuwen.service.middleware.redis.default.*}。</p>
 *
 * <p>需要「未配置 Redis」形态的负例不要继承本类，改用无绑定别名 agent（
 * {@code studio-dsl-ir-sit-noredis}）。</p>
 */
public abstract class StudioDslRedisBackedE2EBase extends BaseManagedStackTest {
    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config).agent("studio-dsl-ir-sit", agent -> agent
                .property("openjiuwen.service.middleware.checkpointer.type", "redis")
                .property("openjiuwen.service.middleware.checkpointer.redis-ref", "default")
                .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.host", "{{host}}")
                .serviceBinding("redis", "openjiuwen.service.middleware.redis.default.port", "{{port}}"));
    }
}
