/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.e2e.studio_dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ISSUE399 / PR707 负例：宿主侧没有 {@code RuntimeRedisClient}（未配置 Redis）时，
 * Studio DSL 会话变量链路必须 fail-fast，且不得再出现旧实现的「自建 {@code :6379} 连接 + 静默读空」。
 *
 * <p>与 {@link StudioDslRuntimeE2EIT} 的正例配对：正例证明配置 Redis 后会话变量可写可读，
 * 负例证明未配置时不再静默失败。</p>
 *
 * <p>限制：本夹具在宿主装配期就构造节点，所以 fail-fast 表现为「启动失败」；懒装配宿主会表现为
 * 首次取用时抛同一异常。两者都不是旧的静默读空行为。</p>
 */
@Tag("e2e")
@Tag("studio-dsl")
@Tag("feat-031")
@Tag("issue-399")
@Feature("FEAT-031: Studio DSL Java 承载")
class StudioDslRedisUnconfiguredIT {
    /** 无 Redis 绑定的同名制品别名（见 application-local.yml），保证宿主拿不到 RuntimeRedisClient。 */
    private static final String AGENT = "studio-dsl-ir-sit-noredis";

    /** 宿主标准输出：ProcessLauncher 按 {@code <sut.logging.dir>/<agent>/stdout.log} 落盘。 */
    private static final Path HOST_STDOUT = Path.of("target/sit-logs/" + AGENT + "/stdout.log");

    @Test
    @Story("ISSUE399.PR707-neg: 未装配 RuntimeRedisClient → fail-fast 且不连 :6379")
    @DisplayName("未配置 Redis 时宿主 fail-fast，且不产生 :6379 连接告警")
    void unconfiguredHostFailsFastWithoutDefaultRedisConnect() throws IOException {
        TestConfig config = TestConfig.load();
        long logOffset = Files.exists(HOST_STDOUT) ? Files.size(HOST_STDOUT) : 0L;

        // 该别名不声明 service-binding，也不开启 checkpointer.type=redis ⇒ 宿主无 RuntimeRedisClient Bean。
        assertThatThrownBy(() -> SutStack.builder(config).agent(AGENT).start())
                .as("未安装 RuntimeRedisClient 时宿主必须启动失败，而不是带病运行")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AGENT)
                .hasMessageContaining("process exited before becoming ready");

        String appended = readAppended(logOffset);
        assertThat(appended)
                .as("未配置 Redis 时禁止再向空地址自建连接（旧实现行为）")
                .doesNotContain(":6379")
                .doesNotContain("Failed to get session vals")
                .doesNotContain("Failed to save session vals");
        assertThat(appended)
                .as("失败原因必须是 DSL 的 fail-fast 契约，而不是静默读空")
                .contains("STUDIO-DSL-REDIS-CLIENT-UNAVAILABLE");
    }

    /** 只读取本轮追加的宿主日志，避免历史运行内容干扰断言。 */
    private static String readAppended(long offset) throws IOException {
        if (!Files.exists(HOST_STDOUT)) {
            return "";
        }
        try (RandomAccessFile file = new RandomAccessFile(HOST_STDOUT.toFile(), "r")) {
            long start = Math.min(offset, file.length());
            file.seek(start);
            byte[] buffer = new byte[(int) (file.length() - start)];
            file.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }
}
