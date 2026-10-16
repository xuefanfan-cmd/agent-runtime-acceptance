/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.concurrency.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 并发 driver：用固定线程池同时向 SUT 发起 N 个请求，收集每个请求的归一结果。
 * 用于 DFX-006 的准入限流（超限 503）、额度无泄漏、并发创建线程安全等并发断言。
 *
 * @since 0.1.0
 */
public final class ConcurrentDriver {
    private ConcurrentDriver() {
    }

    /**
     * 并发发起 {@code totalRequests} 个请求，线程池大小 {@code concurrency}。
     *
     * @param concurrency
     *            并发 worker 数
     * @param totalRequests
     *            请求总数
     * @param taskFactory
     *            按下标生成每个请求（通常各用独立 contextId）
     * @return 每个请求的结果（顺序与请求下标一致）
     */
    public static <T> List<T> drive(int concurrency, int totalRequests, Function<Integer, T> taskFactory) {
        ExecutorService pool = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int i = 0; i < totalRequests; i++) {
                final int index = i;
                tasks.add(() -> taskFactory.apply(index));
            }
            List<Future<T>> futures = pool.invokeAll(tasks);
            List<T> results = new ArrayList<>(totalRequests);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            throw new IllegalStateException("concurrent drive interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("concurrent drive failed", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
