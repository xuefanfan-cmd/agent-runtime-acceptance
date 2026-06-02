package com.huawei.ascend.sit.cases.performance;

import com.huawei.ascend.sit.base.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance-level tests for A2A interface concurrency.
 *
 * <p>Validates the SUT's behaviour under concurrent load:
 * no errors, unique task IDs, stable latency under moderate concurrency.</p>
 *
 * <p>Uses Java 21 virtual threads for lightweight concurrency.</p>
 */
@Tag("performance")
@Disabled("示例用例，待联调验证后逐个放开")
class A2aConcurrencyTest extends BaseIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 50;
    private static final int TIMEOUT_SECONDS = 60;

    @Test
    @DisplayName("PERF: 50 concurrent message sends all succeed with unique task IDs")
    void concurrentSendMessage_allShouldSucceed() throws Exception {
        // given
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<String>> futures = new ArrayList<>();
        CountDownLatch startLatch = new CountDownLatch(1);

        // when — submit 50 concurrent message sends
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                startLatch.await(); // synchronize start
                return a2aClient.sendMessage("并发测试天气查询城市" + idx);
            }));
        }
        startLatch.countDown(); // fire all at once

        // then — all should return valid task IDs
        List<String> taskIds = new ArrayList<>();
        for (Future<String> future : futures) {
            String taskId = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(taskId).isNotBlank();
            taskIds.add(taskId);
        }

        // then — all task IDs should be unique
        assertThat(taskIds).hasSize(CONCURRENT_REQUESTS);
        assertThat(taskIds.stream().distinct().count()).isEqualTo(CONCURRENT_REQUESTS);

        executor.shutdown();
    }
}
