/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.dynamic_model_config;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** FEAT-050-CORE §5.1.7 / FRS-050 AC-27：并发新增与查询快照语义。 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
class DynamicModelConcurrencyContractTest extends DynamicModelStackTestBase {

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return carrier(config, endpointA, endpointB, Map.of("dmcsit.default-model-id", "A", "dmcsit.instance-enabled", "true", "dmcsit.instance-model-id", "A"));
    }

    @BeforeEach
    void resetPool() {
        control().reset();
    }

    @Test
    @Story("dmc.concurrency.add: 并发新增模型不丢失、同 id 并发仅一次成功")
    @DisplayName("并发新增 D/E/F 全部登记；同 id 并发新增只有一个成功")
    void concurrentAddModelKeepsAllModels() throws Exception {
        DynamicModelControlClient client = control();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (String id : List.of("D", "E", "F")) {
                jobs.add(() -> client.add(id, "model-" + id.toLowerCase(), endpointB.baseUrl()).ok());
            }
            List<Future<Boolean>> results = pool.invokeAll(jobs, 60, TimeUnit.SECONDS);
            for (Future<Boolean> result : results) {
                assertThat(result.get()).as("并发新增应全部成功").isTrue();
            }
            assertThat(client.list().toString()).as("清单应含 D/E/F").contains("D", "E", "F");

            AtomicInteger success = new AtomicInteger();
            java.util.List<String> duplicateBodies = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Callable<Void>> duplicates = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                duplicates.add(() -> {
                    DynamicModelControlClient.Response response =
                            client.add("G", "model-g", endpointA.baseUrl());
                    duplicateBodies.add("status=" + response.status() + " body=" + response.rawBody());
                    if (response.accepted()) {
                        success.incrementAndGet();
                    }
                    return null;
                });
            }
            pool.invokeAll(duplicates, 60, TimeUnit.SECONDS);
            assertThat(success.get())
                    .as("同 id 并发新增只应有一次成功（实际成功次数=%s，清单=%s，四次响应=%s）",
                            success.get(), client.list(), duplicateBodies)
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Story("dmc.concurrency.list: 写操作并发期间查询返回完整快照且不含密钥")
    @DisplayName("写操作并发期间查询清单不抛异常、结果完整且只含模型标识")
    void listSnapshotDuringWrites() throws Exception {
        DynamicModelControlClient client = control();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> writer = pool.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    client.add("W" + i, "model-w" + i, endpointA.baseUrl());
                }
            });
            List<String> observed = new ArrayList<>();
            while (!writer.isDone()) {
                String snapshot = client.list().toString();
                observed.add(snapshot);
                assertThat(snapshot).as("快照只能是模型标识，不应包含密钥字段").doesNotContain("apiKey");
            }
            assertThat(observed).as("并发期间应至少取到一次快照").isNotEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Story("dmc.concurrency.remove: 并发删除同一模型仅一次成功")
    @DisplayName("两个线程并发删除同一模型时，只有一次被受理，另一个被拒绝")
    void concurrentRemoveOfSameModelSucceedsOnce() throws Exception {
        DynamicModelControlClient client = control();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<DynamicModelControlClient.Response> remove = () -> client.remove("B");
            List<Future<DynamicModelControlClient.Response>> results =
                    pool.invokeAll(List.of(remove, remove), 60, TimeUnit.SECONDS);
            int succeeded = 0;
            java.util.List<String> bodies = new java.util.ArrayList<>();
            for (Future<DynamicModelControlClient.Response> result : results) {
                DynamicModelControlClient.Response response = result.get();
                bodies.add("status=" + response.status() + " body=" + response.rawBody());
                if (response.accepted()) {
                    succeeded++;
                }
            }
            assertThat(succeeded)
                    .as("并发删除同一模型只应有一次被受理（设计 §5.1.7：删除并发保证幂等）；"
                            + "实际受理次数=%s，两次响应=%s，删除后清单=%s",
                            succeeded, bodies, client.list())
                    .isEqualTo(1);
            assertThat(client.list().toString()).as("清单不应再包含 B").doesNotContain("\"B\"");
        } finally {
            pool.shutdownNow();
        }
    }
}
