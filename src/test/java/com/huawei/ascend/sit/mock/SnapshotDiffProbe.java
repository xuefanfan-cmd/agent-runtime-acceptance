package com.huawei.ascend.sit.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FEAT-028 P0b 首轮真机的<b>快照高频轮询探测器</b>—— 后台线程按固定间隔 {@code GetTask(taskId)}
 * 拉父任务快照，全量 dump 每次的 body 与提取到的 state，供用例事后分析：
 * <ul>
 *   <li>在 WORKING 期间是否有多个子任务进展的可观察证据；</li>
 *   <li>子任务进展的<b>承载位</b>（artifacts / history / metadata / status.message 任一或组合）；</li>
 *   <li>字段路径、字段名、字段值形态（P0b 首轮探测的核心目标）。</li>
 * </ul>
 *
 * <p><b>用法</b>：
 * <pre>{@code
 * try (SnapshotDiffProbe probe = SnapshotDiffProbe.start(baseUrl, taskId, 1000)) {
 *     // ... 发起 SendMessage 后等待终态
 *     awaitTerminalOrTimeout();
 * }
 * probe.snapshots().forEach(snap -> LOG.info(snap.body()));
 * }</pre>
 *
 * <p><b>为什么单独一个 fixture</b>：`A2aServiceClient` 走 SDK 反序列化，会丢失字段级 wire 事实
 * （SDK 只反出 Task 抽象），P0b 探测承载位必须拿到原始 JSON。同时高频轮询独立于用例主逻辑，
 * 避免与 A2A SDK 客户端的连接竞争。
 *
 * <p><b>lifecycle</b>：{@link AutoCloseable}，try-with-resources 或显式 close。停止后 {@link #snapshots()}
 * 返回全量快照供用例断言。
 */
public final class SnapshotDiffProbe implements AutoCloseable {

    /** 一次快照的抓取记录。 */
    public static final class Snapshot {
        private final long timestampMs;
        private final int httpStatus;
        private final String body;

        Snapshot(long ts, int status, String body) {
            this.timestampMs = ts;
            this.httpStatus = status;
            this.body = body;
        }

        public long timestampMs() { return timestampMs; }

        public int httpStatus() { return httpStatus; }

        public String body() { return body; }

        /** 便捷：从 body 抽 GetTask 结果的 state（GetTask 返 result 裸 Task，见 [[a2a-wire-contract]]）。 */
        public String stateOrNull(ObjectMapper mapper) {
            try {
                JsonNode root = mapper.readTree(body);
                String s = root.path("result").path("status").path("state").asText(null);
                if (s == null) s = root.path("result").path("task").path("status").path("state").asText(null);
                return s;
            } catch (Exception parseFail) {
                return null;
            }
        }
    }

    private final String a2aUrl;
    private final String taskId;
    private final long intervalMs;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Snapshot> snapshots = new ArrayList<>();
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private SnapshotDiffProbe(String a2aUrl, String taskId, long intervalMs) {
        this.a2aUrl = a2aUrl;
        this.taskId = taskId;
        this.intervalMs = intervalMs;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SnapshotDiffProbe-" + taskId.substring(0, Math.min(8, taskId.length())));
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动后台轮询。{@code intervalMs} 建议 500~1500ms（既能捕获 WORKING 中间态，又不过度压 SUT）。
     */
    public static SnapshotDiffProbe start(String sutBaseUrl, String taskId, long intervalMs) {
        SnapshotDiffProbe probe = new SnapshotDiffProbe(sutBaseUrl + "/a2a", taskId, intervalMs);
        probe.executor.submit(probe::pollLoop);
        return probe;
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                String body = String.format(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"probe-%s\",\"method\":\"GetTask\",\"params\":{\"id\":\"%s\"}}",
                        UUID.randomUUID().toString().substring(0, 8), taskId);
                HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(a2aUrl))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofString());
                synchronized (snapshots) {
                    snapshots.add(new Snapshot(System.currentTimeMillis(), resp.statusCode(), resp.body()));
                }
                Thread.sleep(intervalMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignore) {
                // 单次快照异常不打断轮询；下一轮继续尝试。
            }
        }
    }

    /** 全量快照（不可变快照复制），按时间顺序。 */
    public List<Snapshot> snapshots() {
        synchronized (snapshots) {
            return List.copyOf(snapshots);
        }
    }

    /** 已抓的快照数。 */
    public int count() {
        synchronized (snapshots) {
            return snapshots.size();
        }
    }

    @Override
    public void close() {
        running.set(false);
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
