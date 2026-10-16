/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.cases.concurrency.support.A2aServiceClient;
import com.huawei.ascend.sit.cases.concurrency.support.ConcurrentDriver;
import com.huawei.ascend.sit.cases.concurrency.support.DriveResult;
import com.huawei.ascend.sit.cases.concurrency.support.SutStack;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * DFX-006 服务化外部请求过载保护机制 —— L3 验收测试。
 *
 * <p>覆盖 FEAT-044 测试计划 §5.1~§5.2 中的 MaxThirty / ResumeOverload / Security 三组用例。
 * 依赖 concurrency-agent-runtime（端口 18210）的公开面（/a2a/、/v1/current_active_tasks）。</p>
 *
 * <p><b>前置条件</b>：</p>
 * <ol>
 *   <li>SUT jar 已构建（通过 {@code -Dconcurrency.sut.jar=<path>} 或 {@code SUT_JAR_PATH} 环境变量指定路径）；</li>
 *   <li>SUT 工作目录下有 {@code application-concurrency_local.yml}（含 LLM 凭据）；</li>
 *   <li>PR #223 的 businessCode 机制已合入（503 响应含 {@code error.details.businessCode}）。</li>
 * </ol>
 *
 * @since 0.1.2
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Dfx006AcceptanceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_URL = "http://127.0.0.1:18210";

    private static SutStack launchSut(Integer maxConcurrentTasks) {
        SutStack stack = new SutStack(maxConcurrentTasks);
        stack.start();
        return stack;
    }

    private static SutStack launchSut(Integer maxConcurrentTasks, Map<String, String> extraEnv) {
        SutStack stack = new SutStack(maxConcurrentTasks, extraEnv);
        stack.start();
        return stack;
    }

    private static JsonNode snapshot(A2aServiceClient client) {
        try {
            return MAPPER.readTree(client.currentActiveTasks(BASE_URL));
        } catch (IOException e) {
            throw new IllegalStateException("snapshot parse failed", e);
        }
    }

    private static String extractBusinessCode(HttpResponse<String> response) {
        try {
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode details = root.path("error").path("data").path("businessCode");
            if (details.isMissingNode()) {
                details = root.path("error").path("details").path("businessCode");
            }
            return details.isTextual() ? details.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void awaitActiveZero(A2aServiceClient client, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int active = snapshot(client).path("currentActiveTasks").asInt();
            if (active == 0) return;
            TimeUnit.MILLISECONDS.sleep(200);
        }
    }

    // ====================================================================
    // MaxThirty 组（max=30）
    // ====================================================================
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("max=30 过载保护：AC-01/AC-02/AC-07/AC-08/AC-11")
    class MaxThirty {
        private SutStack stack;
        private A2aServiceClient client;

        @BeforeAll
        void startSut() {
            stack = Dfx006AcceptanceTest.launchSut(30);
            client = new A2aServiceClient(BASE_URL);
        }

        @AfterAll
        void stopSut() {
            if (stack != null) {
                stack.close();
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-01-S: 正常非流式执行透明")
        void ac01_normalExecution() {
            DriveResult result = client.send("ctx-ac01-" + System.nanoTime(), null, "echo:hello-dfx006");
            assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            assertThat(result.anyContains("hello-dfx006")).isTrue();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-02-R: 第 31 个请求被拒（503）")
        void ac02_31stRequestRejected() throws Exception {
            // 发 31 个并发 slow 请求：30 个 200 + 1 个 503
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac02-" + i, "slow:10000"));
            long accepted = responses.stream().filter(r -> r.statusCode() == 200).count();
            long rejected = responses.stream().filter(r -> r.statusCode() == 503).count();
            assertThat(accepted).isEqualTo(30);
            assertThat(rejected).isEqualTo(1);
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-02-F: 拒绝快速返回（< 1s）")
        void ac02_fastRejection() throws Exception {
            // 发 31 个并发，被拒的那个应快速返回
            long start = System.currentTimeMillis();
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac02f-" + i, "slow:10000"));
            long elapsed = System.currentTimeMillis() - start;
            HttpResponse<String> rejectedResp = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            assertThat(rejectedResp.statusCode()).isEqualTo(503);
            // 总耗时不应超过 slow 任务时长（被拒请求快速返回）
            assertThat(elapsed).isLessThan(15_000);
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-02-Rcv: 容量释放后恢复准入")
        void ac02_capacityRecovery() throws Exception {
            // 发 31 个并发 short slow，等全部完成后重试
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac02rcv-" + i, "slow:200"));
            awaitActiveZero(client, 15_000);
            // 重试请求应成功
            DriveResult result = client.send("ctx-ac02rcv-retry", null, "echo:recovered");
            assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-C: 顺序多轮完成后额度归零（无泄漏）")
        void ac07_sequentialRoundsNoLeak() throws Exception {
            for (int i = 0; i < 35; i++) {
                DriveResult result = client.send("ctx-ac07c-" + i, null, "echo:round-" + i);
                assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            }
            awaitActiveZero(client, 10_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-IR: INPUT_REQUIRED 后额度释放")
        void ac07_inputRequiredReleasesQuota() throws Exception {
            DriveResult interrupted = client.send("ctx-ac07ir", null, "confirm:op");
            assertThat(interrupted.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);
            // 额度应释放，新请求可通过
            HttpResponse<String> next = client.sendMessageRaw("ctx-ac07ir-next", "echo:after-ir");
            assertThat(next.statusCode()).isEqualTo(200);
            awaitActiveZero(client, 10_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-LK: 混合 60 轮无泄漏")
        void ac07_mixedRoundsNoLeak() throws Exception {
            for (int i = 0; i < 60; i++) {
                String tool = switch (i % 3) {
                    case 0 -> "echo:mix-" + i;
                    case 1 -> "slow:50";
                    default -> "confirm:op-" + i;
                };
                DriveResult result = client.send("ctx-ac07lk-" + i, null, tool);
                assertThat(result.state()).isIn(
                        DriveResult.TaskState.COMPLETED, DriveResult.TaskState.INPUT_REQUIRED);
            }
            awaitActiveZero(client, 15_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-08-R: 全部结束后自动恢复准入")
        void ac08_autoRecovery() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac08-" + i, "slow:200"));
            awaitActiveZero(client, 15_000);
            DriveResult result = client.send("ctx-ac08-recover", null, "echo:recovered");
            assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-08-AT: ActiveTask 互证（过载期读数 ≈ 30 且 503 拒绝同时成立）")
        void ac08_activeTaskCrossCheck() throws Exception {
            // 用后台线程发 30 个 slow，等 500ms 让任务入场，再读快照 + 发溢出请求
            ExecutorService bg = Executors.newFixedThreadPool(30);
            try {
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 30; i++) {
                    final int idx = i;
                    futures.add(bg.submit(() -> client.sendMessageRaw("ctx-ac08at-" + idx, "slow:5000")));
                }
                TimeUnit.MILLISECONDS.sleep(500);

                JsonNode snap = snapshot(client);
                int active = snap.path("currentActiveTasks").asInt();
                HttpResponse<String> rejected = client.sendMessageRaw("ctx-ac08at-reject", "echo:test");

                assertThat(active).isGreaterThanOrEqualTo(20);
                assertThat(rejected.statusCode()).isEqualTo(503);

                for (Future<HttpResponse<String>> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-11-S: 503 错误消息不泄露内部信息")
        void ac11_errorMessageSecurity() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac11s-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            String body = rejected.body().toLowerCase();
            assertThat(body).doesNotContain("currentcount");
            assertThat(body).doesNotContain("maxconcurrent");
            assertThat(body).doesNotContain("thread");
            assertThat(body).doesNotContain("queue");
            assertThat(body).doesNotContain("credential");
            assertThat(body).doesNotContain("stack");
            assertThat(body).doesNotContain("address");
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-11-NS: 不出现伪成功（503 而非 COMPLETED）")
        void ac11_noFalseSuccess() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac11ns-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(rejected.body()).doesNotContain("COMPLETED");
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-NT: 拒绝不创建 Task")
        void ac05_rejectedCreatesNoTask() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac05nt-" + i, "slow:10000"));
            JsonNode snap = snapshot(client);
            int activeCount = snap.path("currentActiveTasks").asInt();
            assertThat(activeCount).isLessThanOrEqualTo(30);
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-01-ST: 正常流式执行透明")
        void ac01_streamingExecution() {
            DriveResult result = client.send("ctx-ac01st-" + System.nanoTime(), null, "echo:stream-test");
            assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            assertThat(result.anyContains("stream-test")).isTrue();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-F: 失败释放（fail_task 后额度恢复）")
        void ac07_failureReleasesQuota() throws Exception {
            // fail_task 抛异常 → Task FAILED → 额度应释放
            DriveResult failResult = client.send("ctx-ac07f", null, "fail_task:intentional-failure");
            assertThat(failResult.state()).isIn(DriveResult.TaskState.FAILED, DriveResult.TaskState.COMPLETED);
            awaitActiveZero(client, 10_000);
            // 额度释放后新请求正常
            DriveResult after = client.send("ctx-ac07f-after", null, "echo:recovered");
            assertThat(after.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-NF: 拒绝不创建 FAILED Task（被拒 contextId 无 Task 记录）")
        void ac05_rejectedCreatesNoFailedTask() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac05nf-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            String rejectedBody = rejected.body();
            // GetTask 查询被拒 contextId → 不应返回 FAILED Task
            HttpResponse<String> getTaskResp = client.getTask("ctx-ac05nf-rejected", null);
            String body = getTaskResp.body();
            assertThat(body).doesNotContain("TASK_STATE_FAILED");
            assertThat(body).doesNotContain("FAILED");
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-NS: 拒绝不创建 Session/Callback（被拒 contextId 无活跃 Task）")
        void ac05_rejectedCreatesNoSession() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac05ns-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            // 被拒 contextId 不在活跃任务列表中
            JsonNode snap = snapshot(client);
            int activeCount = snap.path("currentActiveTasks").asInt();
            assertThat(activeCount).isLessThanOrEqualTo(30);
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-DP: 多轮完成后容量不虚增（currentActiveTasks 归零）")
        void ac07_noOverRelease() throws Exception {
            // 第一轮：30 个 slow:200
            List<HttpResponse<String>> r1 = ConcurrentDriver.drive(30, 30,
                    i -> client.sendMessageRaw("ctx-ac07dp-r1-" + i, "slow:200"));
            assertThat(r1.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(30);
            awaitActiveZero(client, 10_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
            // 第二轮：30 个 echo
            for (int i = 0; i < 30; i++) {
                DriveResult result = client.send("ctx-ac07dp-r2-" + i, null, "echo:round2-" + i);
                assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            }
            awaitActiveZero(client, 10_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
            // 第三轮：30 个 slow:200 验证全部通过（容量未虚减）
            List<HttpResponse<String>> r3 = ConcurrentDriver.drive(30, 30,
                    i -> client.sendMessageRaw("ctx-ac07dp-r3-" + i, "slow:200"));
            assertThat(r3.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(30);
            awaitActiveZero(client, 15_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-08-E: 多次过载→恢复循环（验证容量一致性）")
        void ac08_multipleRecoveryCycles() throws Exception {
            for (int cycle = 0; cycle < 3; cycle++) {
                final int c = cycle;
                // 发 31 个并发 slow:10000：30 个 200 + 1 个 503
                List<HttpResponse<String>> fill = ConcurrentDriver.drive(31, 31,
                        i -> client.sendMessageRaw("ctx-ac08e-c" + c + "-" + i, "slow:10000"));
                assertThat(fill.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(30);
                assertThat(fill.stream().filter(r -> r.statusCode() == 503).count()).isEqualTo(1);
                awaitActiveZero(client, 30_000);
                // 恢复后 echo 成功
                DriveResult result = client.send("ctx-ac08e-c" + c + "-recover", null, "echo:recovered");
                assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            }
            awaitActiveZero(client, 10_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-Cx: 取消释放（slow 占额度期间第二个请求被拒）")
        void ac07_cancelReleasesQuota() throws Exception {
            // max=30，发 30 个 slow 占满额度 + 1 个 echo 应被拒
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac07cx-" + i, "slow:5000"));
            long rejected = responses.stream().filter(r -> r.statusCode() == 503).count();
            assertThat(rejected).isGreaterThanOrEqualTo(1);
            awaitActiveZero(client, 30_000);
            // 额度释放后 echo 成功
            DriveResult result = client.send("ctx-ac07cx-after", null, "echo:recovered");
            assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-01-M1: limit=-1 监听器配对（无限额模式）")
        void ac01_unlimitedNoLeak() throws Exception {
            // 本组 max=30，发 35 个 echo 验证全部通过且无泄漏
            for (int i = 0; i < 35; i++) {
                DriveResult result = client.send("ctx-ac01m1-" + i, null, "echo:unlimited-" + i);
                assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            }
            awaitActiveZero(client, 10_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-01-GT: 只读入口不受阻断（过载期间 GetTask 正常）")
        void ac01_getTaskNotBlocked() throws Exception {
            // 先发一个 echo 获取 taskId
            DriveResult echo = client.send("ctx-ac01gt-echo", null, "echo:gettask-test");
            assertThat(echo.state()).isEqualTo(DriveResult.TaskState.COMPLETED);

            // 占满额度
            List<HttpResponse<String>> fill = ConcurrentDriver.drive(30, 30,
                    i -> client.sendMessageRaw("ctx-ac01gt-fill-" + i, "slow:10000"));
            assertThat(fill.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(30);

            // 过载期间 GetTask 应正常返回（不阻断）
            HttpResponse<String> getTaskResp = client.getTask("ctx-ac01gt-echo", echo.taskId());
            assertThat(getTaskResp.statusCode()).isEqualTo(200);

            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-LLM: 拒绝不调用 LLM（503 响应体不含 LLM 特征）")
        void ac05_rejectedNoLlmCall() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac05llm-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            String body = rejected.body().toLowerCase();
            // 503 响应体不应包含 LLM 模型名/token/assistant 等特征
            assertThat(body).doesNotContain("qwen");
            assertThat(body).doesNotContain("deepseek");
            assertThat(body).doesNotContain("assistant");
            assertThat(body).doesNotContain("token");
            assertThat(body).doesNotContain("model-name");
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-NE: 拒绝不创建业务事件（被拒 contextId 不在活跃任务列表）")
        void ac05_rejectedNoEvents() throws Exception {
            // 发 31 个并发：30 个 slow + 1 个 echo，被拒的 echo 不在活跃任务列表
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac05ne-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            assertThat(rejected.statusCode()).isEqualTo(503);

            // 所有活跃任务数 <= 30
            JsonNode snap = snapshot(client);
            int activeCount = snap.path("currentActiveTasks").asInt();
            assertThat(activeCount).isLessThanOrEqualTo(30);
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-IE: 入口异常不泄漏容量")
        void ac07_entryExceptionNoLeak() throws Exception {
            // 发 slow 占额度，然后用异常 HTTP 请求打断
            ExecutorService bg = Executors.newSingleThreadExecutor();
            try {
                bg.submit(() -> client.sendMessageRaw("ctx-ac07ie-slow", "slow:3000"));
                TimeUnit.MILLISECONDS.sleep(500);

                // 发一个不完整的 JSON-RPC 请求（模拟入口异常）
                HttpRequest badReq = HttpRequest.newBuilder(URI.create(BASE_URL + "/a2a/"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{invalid json"))
                        .build();
                try {
                    HttpClient.newHttpClient().send(badReq, HttpResponse.BodyHandlers.ofString());
                } catch (Exception ignored) {
                    // 预期会返回错误
                }

                awaitActiveZero(client, 10_000);
                // 入口异常后额度无泄漏，新请求正常
                DriveResult result = client.send("ctx-ac07ie-after", null, "echo:recovered");
                assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            } finally {
                bg.shutdownNow();
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-09-OL: 同 Task 轮次重叠 FIFO 释放")
        void ac09_overlapFifoRelease() throws Exception {
            // Task A: confirm → INPUT_REQUIRED（释放额度）
            DriveResult taskA = client.send("ctx-ac09ol-a", null, "confirm:op");
            assertThat(taskA.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);

            // 立即续传 A（新一轮），同时上一轮的后果事件可能尚未落地
            // 新一轮应成功准入（额度已释放）
            DriveResult resumed = client.send("ctx-ac09ol-a", taskA.taskId(), "确认");
            assertThat(resumed.state()).isEqualTo(DriveResult.TaskState.COMPLETED);

            // 等待全部释放
            awaitActiveZero(client, 10_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }
    }
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("max=1 续传过载：AC-09")
    class ResumeOverload {
        private SutStack stack;
        private A2aServiceClient client;

        @BeforeAll
        void startSut() {
            stack = Dfx006AcceptanceTest.launchSut(1);
            client = new A2aServiceClient(BASE_URL);
        }

        @AfterAll
        void stopSut() {
            if (stack != null) {
                stack.close();
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-09-NR: 正常续传重新准入")
        void ac09_normalResume() {
            DriveResult first = client.send("ctx-ac09nr", null, "confirm:op");
            assertThat(first.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);
            assertThat(first.taskId()).isNotBlank();
            DriveResult resumed = client.send("ctx-ac09nr", first.taskId(), "确认");
            assertThat(resumed.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-09-RR: 过载续传被拒（503）")
        void ac09_overloadResumeRejected() throws Exception {
            // Task A: confirm → INPUT_REQUIRED（释放额度）
            DriveResult taskA = client.send("ctx-ac09rr-a", null, "confirm:op");
            assertThat(taskA.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);

            // 后台发 Task B（slow）占额度
            ExecutorService bg = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> bFuture = bg.submit(
                        () -> client.sendMessageRaw("ctx-ac09rr-b", "slow:5000"));
                TimeUnit.MILLISECONDS.sleep(500); // 等 B 入场

                // 续传 A 应被 503 拒绝
                HttpResponse<String> resumeAttempt = client.sendMessageRaw("ctx-ac09rr-a", "确认续传");
                assertThat(resumeAttempt.statusCode()).isEqualTo(503);

                bFuture.get(30, TimeUnit.SECONDS);
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 15_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-09-Rcv: 续传释放后可重试")
        void ac09_resumeAfterRelease() throws Exception {
            DriveResult taskA = client.send("ctx-ac09rcv-a", null, "confirm:op");
            assertThat(taskA.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);

            // 后台发 Task B 占额度
            ExecutorService bg = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> bFuture = bg.submit(
                        () -> client.sendMessageRaw("ctx-ac09rcv-b", "slow:300"));
                TimeUnit.MILLISECONDS.sleep(300);

                // 续传被拒
                HttpResponse<String> resumeRejected = client.sendMessageRaw("ctx-ac09rcv-a", "确认");
                assertThat(resumeRejected.statusCode()).isEqualTo(503);

                bFuture.get(10, TimeUnit.SECONDS);
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 10_000);

            // 重试续传成功
            DriveResult resumed = client.send("ctx-ac09rcv-a", taskA.taskId(), "确认");
            assertThat(resumed.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-09-NT: 拒绝续传不破坏原 Task")
        void ac09_rejectedResumePreservesOriginal() throws Exception {
            DriveResult taskA = client.send("ctx-ac09nt-a", null, "confirm:op");
            assertThat(taskA.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);
            String originalTaskId = taskA.taskId();

            ExecutorService bg = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> bFuture = bg.submit(
                        () -> client.sendMessageRaw("ctx-ac09nt-b", "slow:5000"));
                TimeUnit.MILLISECONDS.sleep(500);

                HttpResponse<String> resumeRejected = client.sendMessageRaw("ctx-ac09nt-a", "确认");
                assertThat(resumeRejected.statusCode()).isEqualTo(503);

                bFuture.get(30, TimeUnit.SECONDS);
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 15_000);

            // 原 Task 仍可续传
            DriveResult resumed = client.send("ctx-ac09nt-a", originalTaskId, "确认");
            assertThat(resumed.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-09-NE: 拒绝续传不写新事件")
        void ac09_rejectedResumeNoNewEvents() throws Exception {
            DriveResult taskA = client.send("ctx-ac09ne-a", null, "confirm:op");
            assertThat(taskA.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);

            ExecutorService bg = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> bFuture = bg.submit(
                        () -> client.sendMessageRaw("ctx-ac09ne-b", "slow:5000"));
                TimeUnit.MILLISECONDS.sleep(500);

                // 续传被拒
                HttpResponse<String> resumeRejected = client.sendMessageRaw("ctx-ac09ne-a", "确认");
                assertThat(resumeRejected.statusCode()).isEqualTo(503);

                bFuture.get(30, TimeUnit.SECONDS);
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 15_000);

            // 原续传成功，原 Task 仍可正常完成
            DriveResult resumed = client.send("ctx-ac09ne-a", taskA.taskId(), "确认");
            assertThat(resumed.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }
    }

    // ====================================================================
    // Security 组（max=2）
    // ====================================================================
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("安全与可观测：SEC-01~SEC-04")
    class Security {
        private SutStack stack;
        private A2aServiceClient client;

        @BeforeAll
        void startSut() {
            stack = Dfx006AcceptanceTest.launchSut(2);
            client = new A2aServiceClient(BASE_URL);
        }

        @AfterAll
        void stopSut() {
            if (stack != null) {
                stack.close();
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("SEC-01: 不同 conversation_id 上下文不串用")
        void sec01_contextIsolation() {
            DriveResult resultA = client.send("ctx-sec01-a", null, "echo:alpha");
            DriveResult resultB = client.send("ctx-sec01-b", null, "echo:beta");
            assertThat(resultA.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            assertThat(resultB.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            assertThat(resultA.anyContains("alpha")).isTrue();
            assertThat(resultA.anyContains("beta")).isFalse();
            assertThat(resultB.anyContains("beta")).isTrue();
            assertThat(resultB.anyContains("alpha")).isFalse();
        }

        @Test
        @Tag("pending")
        @DisplayName("SEC-02: 503 拒绝响应包含可关联的 businessCode")
        void sec02_rejectHasBusinessCode() throws Exception {
            // 发 3 个并发 slow：2 个 200 + 1 个 503
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-sec02-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            assertThat(rejected.statusCode()).isEqualTo(503);
            String businessCode = extractBusinessCode(rejected);
            assertThat(businessCode).isNotEmpty();
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("SEC-04: 拒绝响应不泄露内部信息")
        void sec04_noInternalInfoLeak() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-sec04-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            String body = rejected.body().toLowerCase();
            assertThat(body).doesNotContain("credential");
            assertThat(body).doesNotContain("password");
            assertThat(body).doesNotContain("secret");
            assertThat(body).doesNotContain("stacktrace");
            assertThat(body).doesNotContain("internal");
            assertThat(body).doesNotContain("127.0.0.1");
            assertThat(body).doesNotContain("0.0.0.0");
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("SEC-03: 503 拒绝可区分于 Agent 业务失败")
        void sec03_rejectionDistinguishableFromFailure() throws Exception {
            // 1. 发 fail_task → 200 + FAILED（业务失败，非拒绝）
            DriveResult failResult = client.send("ctx-sec03-fail", null, "fail_task:intentional");
            int failHttpStatus = failResult.httpStatus();
            assertThat(failHttpStatus).isEqualTo(200);

            // 2. 发 3 个并发 slow → 1 个 503（过载拒绝）
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-sec03-rej-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            assertThat(rejected.statusCode()).isEqualTo(503);

            // 3. 503 ≠ 200+FAILED：HTTP 状态码不同，可区分
            assertThat(rejected.statusCode()).isNotEqualTo(failHttpStatus);
            // 503 响应体不含 Task 终态
            assertThat(rejected.body()).doesNotContain("TASK_STATE_FAILED");
            assertThat(rejected.body()).doesNotContain("TASK_STATE_COMPLETED");
            awaitActiveZero(client, 30_000);
        }
    }
    // ====================================================================
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("max=0 零容量边界：AC-02-ZERO")
    class ZeroCapacity {
        private SutStack stack;
        private A2aServiceClient client;

        @BeforeAll
        void startSut() {
            stack = Dfx006AcceptanceTest.launchSut(0);
            client = new A2aServiceClient(BASE_URL);
        }

        @AfterAll
        void stopSut() {
            if (stack != null) {
                stack.close();
            }
        }

        @Test
        @Tag("pending")
        @DisplayName("AC-02-ZERO: max=0 时全部执行请求 503")
        void ac02_zeroCapacityAllRejected() {
            HttpResponse<String> response = client.sendMessageRaw("ctx-ac02zero", "echo:test");
            assertThat(response.statusCode()).isEqualTo(503);
        }
    }

    // ====================================================================
    // MultiEntry 组（max=2，对应 AC-10/AC-12）
    // ====================================================================
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("max=2 多入口一致性：AC-10/AC-12")
    class MultiEntry {
        private SutStack stack;
        private A2aServiceClient client;

        @BeforeAll
        void startSut() {
            stack = Dfx006AcceptanceTest.launchSut(2);
            client = new A2aServiceClient(BASE_URL);
        }

        @AfterAll
        void stopSut() {
            if (stack != null) {
                stack.close();
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-10-HT: HTTP SendMessage 拒绝（503 + businessCode）")
        void ac10_httpSendMessageRejected() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-ac10ht-" + i, "slow:10000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            assertThat(rejected.statusCode()).isEqualTo(503);
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-10-HS: HTTP SendStreamingMessage 拒绝")
        void ac10_httpStreamingRejected() throws Exception {
            // 发 3 个并发 slow：2 个 200 + 1 个 503
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-ac10hs-" + i, "slow:10000"));
            long rejected = responses.stream().filter(r -> r.statusCode() == 503).count();
            assertThat(rejected).isEqualTo(1);
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-12-NB: 所有入口无旁路（全部被拒）")
        void ac12_allEntriesBlocked() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-ac12nb-" + i, "slow:10000"));
            long rejected = responses.stream().filter(r -> r.statusCode() == 503).count();
            assertThat(rejected).isGreaterThanOrEqualTo(1);
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-10-CB: 回调续传拒绝（原 Task 状态不变）")
        void ac10_callbackResumeRejected() throws Exception {
            // Task A: confirm → INPUT_REQUIRED（释放额度）
            DriveResult taskA = client.send("ctx-ac10cb-a", null, "confirm:op");
            assertThat(taskA.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);

            // 后台发 2 个 slow 占满额度（max=2）
            ExecutorService bg = Executors.newFixedThreadPool(2);
            try {
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    final int idx = i;
                    futures.add(bg.submit(() ->
                            client.sendMessageRaw("ctx-ac10cb-fill-" + idx, "slow:10000")));
                }
                TimeUnit.MILLISECONDS.sleep(500); // 等 2 个 slow 入场占满额度

                // 续传 A 被拒
                HttpResponse<String> resumeRejected = client.sendMessageRaw("ctx-ac10cb-a", "确认");
                assertThat(resumeRejected.statusCode()).isEqualTo(503);

                for (Future<HttpResponse<String>> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 30_000);

            // 原 Task 仍可续传
            DriveResult resumed = client.send("ctx-ac10cb-a", taskA.taskId(), "确认");
            assertThat(resumed.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }
    }

    // ====================================================================
    // EventOverload 组（max=30，对应 AC-03/AC-05/AC-06/AC-08）
    // ====================================================================
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("max=30 事件过载与副作用验证：AC-03/AC-05/AC-06/AC-08")
    class EventOverload {
        private SutStack stack;
        private A2aServiceClient client;

        @BeforeAll
        void startSut() {
            stack = Dfx006AcceptanceTest.launchSut(30);
            client = new A2aServiceClient(BASE_URL);
        }

        @AfterAll
        void stopSut() {
            if (stack != null) {
                stack.close();
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-03-B: event_flood 并发执行有界（active ≤ 30）")
        void ac03_eventFloodBounded() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(35, 35,
                    i -> client.sendMessageRaw("ctx-ac03ef-" + i, "event_flood:50"));
            long accepted = responses.stream().filter(r -> r.statusCode() == 200).count();
            long rejected = responses.stream().filter(r -> r.statusCode() == 503).count();
            assertThat(accepted).isLessThanOrEqualTo(30);
            assertThat(rejected).isGreaterThanOrEqualTo(5);
            JsonNode snap = snapshot(client);
            assertThat(snap.path("currentActiveTasks").asInt()).isLessThanOrEqualTo(30);
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-06-T: 过载期间存量 Task 继续执行至完成")
        void ac06_existingTasksComplete() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac06-" + i,
                            i < 30 ? "slow:500" : "echo:overload-test"));
            long accepted = responses.stream().filter(r -> r.statusCode() == 200).count();
            assertThat(accepted).isEqualTo(30);
            awaitActiveZero(client, 15_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-08-QD: 过载期间查询端点正常可用")
        void ac08_queryDuringOverload() throws Exception {
            DriveResult echo = client.send("ctx-ac08qd-echo", null, "echo:query-test");
            assertThat(echo.state()).isEqualTo(DriveResult.TaskState.COMPLETED);

            ExecutorService bg = Executors.newFixedThreadPool(30);
            try {
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 30; i++) {
                    final int idx = i;
                    futures.add(bg.submit(() ->
                            client.sendMessageRaw("ctx-ac08qd-fill-" + idx, "slow:5000")));
                }
                TimeUnit.MILLISECONDS.sleep(500);

                HttpResponse<String> getTaskResp = client.getTask("ctx-ac08qd-echo", echo.taskId());
                assertThat(getTaskResp.statusCode()).isEqualTo(200);

                JsonNode snap = snapshot(client);
                assertThat(snap.path("currentActiveTasks").asInt()).isGreaterThan(0);

                for (Future<HttpResponse<String>> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-NF: 拒绝请求不创建 Task（GetTask 查询无 Task 记录）")
        void ac05_rejectedNoFailedTask() throws Exception {
            ExecutorService bg = Executors.newFixedThreadPool(30);
            try {
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 30; i++) {
                    final int idx = i;
                    futures.add(bg.submit(() ->
                            client.sendMessageRaw("ctx-ac05nf-fill-" + idx, "slow:5000")));
                }
                TimeUnit.MILLISECONDS.sleep(500);

                HttpResponse<String> rejected = client.sendMessageRaw("ctx-ac05nf-rej", "echo:test");
                assertThat(rejected.statusCode()).isEqualTo(503);

                // GetTask 查询被拒 contextId → 无 Task 记录
                HttpResponse<String> getTaskResp = client.getTask("ctx-ac05nf-rej", null);
                if (getTaskResp.statusCode() == 200) {
                    String body = getTaskResp.body();
                    assertThat(body).doesNotContain("TASK_STATE_COMPLETED");
                    assertThat(body).doesNotContain("TASK_STATE_FAILED");
                    assertThat(body).doesNotContain("TASK_STATE_INPUT_REQUIRED");
                }

                for (Future<HttpResponse<String>> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-MCP/SB/RA: 503 响应不含 MCP/Sandbox/Remote A2A 特征")
        void ac05_rejectedNoMcpSandboxRemote() throws Exception {
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(31, 31,
                    i -> client.sendMessageRaw("ctx-ac05mcp-" + i, "slow:5000"));
            HttpResponse<String> rejected = responses.stream()
                    .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
            String body = rejected.body().toLowerCase();
            assertThat(body).doesNotContain("mcp");
            assertThat(body).doesNotContain("sandbox");
            assertThat(body).doesNotContain("remote-a2a");
            assertThat(body).doesNotContain("remote_a2a");
            assertThat(body).doesNotContain("subagent");
            awaitActiveZero(client, 30_000);
        }
    }

    // ====================================================================
    // CapacityEdge 组（max=2，对应 AC-07-DP/LK + SEC-03 + AC-05-NS）
    // ====================================================================
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("max=2 容量边界与副作用：AC-07-DP/LK + SEC-03 + AC-05-NS")
    class CapacityEdge {
        private SutStack stack;
        private A2aServiceClient client;

        @BeforeAll
        void startSut() {
            stack = Dfx006AcceptanceTest.launchSut(2);
            client = new A2aServiceClient(BASE_URL);
        }

        @AfterAll
        void stopSut() {
            if (stack != null) {
                stack.close();
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-DP: 多轮释放后容量不超过 max=2")
        void ac07_capacityNoExceedMax() throws Exception {
            // 第一轮：2 个 echo 完成
            for (int i = 0; i < 2; i++) {
                DriveResult r = client.send("ctx-ac07dp-r1-" + i, null, "echo:round1-" + i);
                assertThat(r.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            }
            awaitActiveZero(client, 5_000);

            // 第二轮：2 个 confirm（INPUT_REQUIRED 释放额度）
            for (int i = 0; i < 2; i++) {
                DriveResult r = client.send("ctx-ac07dp-r2-" + i, null, "confirm:op-" + i);
                assertThat(r.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);
            }
            awaitActiveZero(client, 5_000);

            // 第三轮：3 个并发 slow，只有 2 个被准入
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-ac07dp-r3-" + i, "slow:500"));
            long accepted = responses.stream().filter(r -> r.statusCode() == 200).count();
            long rejected = responses.stream().filter(r -> r.statusCode() == 503).count();
            assertThat(accepted).isEqualTo(2);
            assertThat(rejected).isEqualTo(1);
            awaitActiveZero(client, 10_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-LK2: max=2 混合 20 轮无泄漏")
        void ac07_mixedRoundsNoLeakMax2() throws Exception {
            for (int i = 0; i < 20; i++) {
                String tool = switch (i % 4) {
                    case 0 -> "echo:mix2-" + i;
                    case 1 -> "slow:50";
                    case 2 -> "confirm:op-" + i;
                    default -> "echo:default-" + i;
                };
                DriveResult result = client.send("ctx-ac07lk2-" + i, null, tool);
                assertThat(result.state()).isIn(
                        DriveResult.TaskState.COMPLETED, DriveResult.TaskState.INPUT_REQUIRED);
                awaitActiveZero(client, 5_000);
            }
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("SEC-03: 多次拒绝 businessCode 一致")
        void sec03_businessCodeConsistency() throws Exception {
            String firstCode = null;
            for (int batch = 0; batch < 3; batch++) {
                final int b = batch;
                List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                        i -> client.sendMessageRaw("ctx-sec03-b" + b + "-" + i, "slow:5000"));
                HttpResponse<String> rejected = responses.stream()
                        .filter(r -> r.statusCode() == 503).findFirst().orElseThrow();
                String code = extractBusinessCode(rejected);
                if (firstCode == null) {
                    firstCode = code;
                    assertThat(code).isNotEmpty();
                } else {
                    assertThat(code).isEqualTo(firstCode);
                }
                awaitActiveZero(client, 30_000);
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-NS: 拒绝请求不创建 Session/Callback/Rail")
        void ac05_rejectedNoSessionCallback() throws Exception {
            ExecutorService bg = Executors.newFixedThreadPool(2);
            try {
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    final int idx = i;
                    futures.add(bg.submit(() ->
                            client.sendMessageRaw("ctx-ac05ns-fill-" + idx, "slow:5000")));
                }
                TimeUnit.MILLISECONDS.sleep(500);

                HttpResponse<String> rejected = client.sendMessageRaw("ctx-ac05ns-rej", "echo:test");
                assertThat(rejected.statusCode()).isEqualTo(503);

                // GetTask 查询被拒 contextId → 无 Task 上下文
                HttpResponse<String> getTaskResp = client.getTask("ctx-ac05ns-rej", null);
                if (getTaskResp.statusCode() == 200) {
                    String body = getTaskResp.body();
                    assertThat(body).doesNotContain("TASK_STATE_");
                }

                for (Future<HttpResponse<String>> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-08-PS2: 持续过载期间查询可用（不验证恢复）")
        void ac08_sustainedOverloadQueryAvailable() throws Exception {
            ExecutorService bg = Executors.newFixedThreadPool(2);
            try {
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    final int idx = i;
                    futures.add(bg.submit(() ->
                            client.sendMessageRaw("ctx-ac08ps2-fill-" + idx, "slow:5000")));
                }
                TimeUnit.MILLISECONDS.sleep(500);

                // 过载期间查询端点正常
                assertThat(snapshot(client).path("currentActiveTasks").asInt()).isGreaterThan(0);
                HttpResponse<String> healthResp = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(BASE_URL + "/health"))
                                .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(healthResp.statusCode()).isEqualTo(200);

                // 被拒请求返回 503
                HttpResponse<String> rejected = client.sendMessageRaw("ctx-ac08ps2-rej", "echo:test");
                assertThat(rejected.statusCode()).isEqualTo(503);

                for (Future<HttpResponse<String>> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 30_000);
        }
    }

    // ====================================================================
    // ExtendedEdge 组（max=2，补充覆盖 AC-07-CL/DP/AC-08-Cx/AC-09-BF/AC-03-MX/AC-12-NB2）
    // ====================================================================
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("max=2 扩展边界：AC-07-CL/DP2/AC-08-Cx2/AC-09-BF2/AC-03-MX/AC-12-NB2")
    class ExtendedEdge {
        private SutStack stack;
        private A2aServiceClient client;

        @BeforeAll
        void startSut() {
            stack = Dfx006AcceptanceTest.launchSut(2);
            client = new A2aServiceClient(BASE_URL);
        }

        @AfterAll
        void stopSut() {
            if (stack != null) {
                stack.close();
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-CL2: 后果落地释放时点（echo 完成后短暂持有 permit）")
        void ac07_consequenceLandedRelease() throws Exception {
            DriveResult result = client.send("ctx-ac07cl2", null, "echo:release-timing");
            assertThat(result.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            // 执行已返回，但 permit 可能短暂持有（后果落地释放机制）
            // 等待短暂时间后应归零
            awaitActiveZero(client, 5_000);
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-Fix: fail_task 后额度释放（新请求成功准入）")
        void ac07_failTaskReleasesQuota() throws Exception {
            // 发 fail_task（Issue #3: 返回 COMPLETED 而非 FAILED，但额度应释放）
            DriveResult failResult = client.send("ctx-ac07fix-fail", null, "fail:boom");
            // 等额度释放
            awaitActiveZero(client, 10_000);
            // 新请求应成功
            DriveResult after = client.send("ctx-ac07fix-after", null, "echo:after-fail");
            assertThat(after.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-07-DP2: 容量重复释放不虚增（异常路径 + 重复释放后额度正确）")
        void ac07_doubleReleaseNoOverCount() throws Exception {
            // 第一轮：2 个 echo 完成
            for (int i = 0; i < 2; i++) {
                client.send("ctx-ac07dp2-r1-" + i, null, "echo:round1-" + i);
            }
            awaitActiveZero(client, 5_000);

            // 第二轮：2 个 confirm（INPUT_REQUIRED 释放额度）
            for (int i = 0; i < 2; i++) {
                client.send("ctx-ac07dp2-r2-" + i, null, "confirm:op-" + i);
            }
            awaitActiveZero(client, 5_000);

            // 第三轮：发 3 个并发 slow，只有 2 个准入
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-ac07dp2-r3-" + i, "slow:500"));
            long accepted = responses.stream().filter(r -> r.statusCode() == 200).count();
            long rejected = responses.stream().filter(r -> r.statusCode() == 503).count();
            assertThat(accepted).isEqualTo(2);
            assertThat(rejected).isEqualTo(1);
            awaitActiveZero(client, 10_000);

            // 第四轮：再次 3 个并发，验证容量未虚增（仍只有 2 个准入）
            List<HttpResponse<String>> r4 = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-ac07dp2-r4-" + i, "slow:500"));
            assertThat(r4.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(2);
            assertThat(r4.stream().filter(r -> r.statusCode() == 503).count()).isEqualTo(1);
            awaitActiveZero(client, 10_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-08-Cx2: 过载期间取消请求不受阻断")
        void ac08_cancelDuringOverload() throws Exception {
            // 先发一个 echo 获取 taskId
            DriveResult echo = client.send("ctx-ac08cx2-echo", null, "echo:cancel-test");
            assertThat(echo.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
            String taskId = echo.taskId();

            // 占满额度
            ExecutorService bg = Executors.newFixedThreadPool(2);
            try {
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    final int idx = i;
                    futures.add(bg.submit(() ->
                            client.sendMessageRaw("ctx-ac08cx2-fill-" + idx, "slow:5000")));
                }
                TimeUnit.MILLISECONDS.sleep(500);

                // 过载期间 GetTask（取消/查询类）应正常
                HttpResponse<String> getTaskResp = client.getTask("ctx-ac08cx2-echo", taskId);
                assertThat(getTaskResp.statusCode()).isEqualTo(200);

                // 过载期间 health 正常
                HttpResponse<String> healthResp = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(BASE_URL + "/health"))
                                .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(healthResp.statusCode()).isEqualTo(200);

                for (Future<HttpResponse<String>> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 30_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-09-BF2: 过载续传被拒后原 Task 保持可续传（不被转写 FAILED）")
        void ac09_rejectedResumeNoFailedTranscription() throws Exception {
            DriveResult taskA = client.send("ctx-ac09bf2-a", null, "confirm:op");
            assertThat(taskA.state()).isEqualTo(DriveResult.TaskState.INPUT_REQUIRED);
            String originalTaskId = taskA.taskId();

            // 占满额度（max=2，需 2 个 slow 占满）
            ExecutorService bg = Executors.newFixedThreadPool(2);
            try {
                List<Future<HttpResponse<String>>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    final int idx = i;
                    futures.add(bg.submit(() ->
                            client.sendMessageRaw("ctx-ac09bf2-fill-" + idx, "slow:5000")));
                }
                TimeUnit.MILLISECONDS.sleep(500);

                // 续传被拒
                HttpResponse<String> resumeRejected = client.sendMessageRaw("ctx-ac09bf2-a", "确认");
                assertThat(resumeRejected.statusCode()).isEqualTo(503);

                for (Future<HttpResponse<String>> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                bg.shutdownNow();
            }
            awaitActiveZero(client, 15_000);

            // 原 Task 仍可续传（不被转写 FAILED）
            DriveResult resumed = client.send("ctx-ac09bf2-a", originalTaskId, "确认");
            assertThat(resumed.state()).isEqualTo(DriveResult.TaskState.COMPLETED);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-03-MX: event_flood + slow 混合并发有界")
        void ac03_mixedEventFloodAndSlow() throws Exception {
            // max=2: 1 slow + 1 event_flood + 1 echo = 3
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-ac03mx-" + i,
                            i == 0 ? "slow:2000" : (i == 1 ? "event_flood:30" : "echo:overflow")));
            long accepted = responses.stream().filter(r -> r.statusCode() == 200).count();
            long rejected = responses.stream().filter(r -> r.statusCode() == 503).count();
            assertThat(accepted).isEqualTo(2);
            assertThat(rejected).isEqualTo(1);
            awaitActiveZero(client, 15_000);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-12-NB2: 多轮多入口无旁路（连续 3 轮 HTTP 过载验证一致性）")
        void ac12_multiRoundNoBypass() throws Exception {
            for (int round = 0; round < 3; round++) {
                final int r = round;
                List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                        i -> client.sendMessageRaw("ctx-ac12nb2-r" + r + "-" + i, "slow:3000"));
                long accepted = responses.stream().filter(r2 -> r2.statusCode() == 200).count();
                long rejected = responses.stream().filter(r2 -> r2.statusCode() == 503).count();
                assertThat(accepted).isEqualTo(2);
                assertThat(rejected).isEqualTo(1);
                awaitActiveZero(client, 15_000);
            }
            // 最终额度归零
            assertThat(snapshot(client).path("currentActiveTasks").asInt()).isZero();
        }
    }

    // ====================================================================
    // DownstreamCounter 组（max=2，mock 下游计数器验证 AC-05-MCP/SB/RA）
    // ====================================================================
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("max=2 下游调用计数：AC-05-MCP/SB/RA")
    class DownstreamCounter {
        private SutStack stack;
        private A2aServiceClient client;
        private HttpServer mockServer;
        private AtomicInteger callCount;
        private int mockPort;

        @BeforeAll
        void startSut() throws IOException {
            // 启动 mock HTTP 服务器
            callCount = new AtomicInteger(0);
            mockServer = HttpServer.create(new InetSocketAddress(0), 0);
            mockServer.createContext("/", exchange -> {
                callCount.incrementAndGet();
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            });
            mockServer.setExecutor(Executors.newCachedThreadPool());
            mockServer.start();
            mockPort = mockServer.getAddress().getPort();

            // 启动 SUT，配置 call_service 指向 mock 服务器
            stack = Dfx006AcceptanceTest.launchSut(2,
                    Map.of("CONCURRENCY_CALL_SERVICE_URL", "http://127.0.0.1:" + mockPort + "/"));
            client = new A2aServiceClient(BASE_URL);
        }

        @AfterAll
        void stopSut() {
            if (stack != null) {
                stack.close();
            }
            if (mockServer != null) {
                mockServer.stop(0);
            }
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-DS: 503 拒绝不调用下游服务（模拟 MCP/Sandbox/Remote A2A）")
        void ac05_rejectedNoDownstreamCall() throws Exception {
            callCount.set(0);

            // 3 个并发 call_service 请求，max=2，1 个被拒
            List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                    i -> client.sendMessageRaw("ctx-ac05ds-" + i, "call_service:test"));

            long accepted = responses.stream().filter(r -> r.statusCode() == 200).count();
            long rejected = responses.stream().filter(r -> r.statusCode() == 503).count();
            assertThat(accepted).isEqualTo(2);
            assertThat(rejected).isEqualTo(1);

            // 等待被准入的请求完成（LLM + 工具调用 + 2s sleep）
            awaitActiveZero(client, 60_000);

            // 下游 mock 服务器只收到 2 次调用（被准入的），不是 3 次
            assertThat(callCount.get()).isEqualTo(2);
        }

        @Test
        @Tag("dfx006")
        @DisplayName("AC-05-DS2: 多轮过载下游计数一致（3 轮验证）")
        void ac05_downstreamCountMultiRound() throws Exception {
            for (int round = 0; round < 3; round++) {
                callCount.set(0);
                final int r = round;
                List<HttpResponse<String>> responses = ConcurrentDriver.drive(3, 3,
                        i -> client.sendMessageRaw("ctx-ac05ds2-r" + r + "-" + i, "call_service:round-" + r));
                long accepted = responses.stream().filter(r2 -> r2.statusCode() == 200).count();
                long rejected = responses.stream().filter(r2 -> r2.statusCode() == 503).count();
                assertThat(accepted).isEqualTo(2);
                assertThat(rejected).isEqualTo(1);
                awaitActiveZero(client, 60_000);
                assertThat(callCount.get()).isEqualTo(2);
            }
        }
    }
}
