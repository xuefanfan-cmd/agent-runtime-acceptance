package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * FEAT-054 DA 兼容端点的 SSE 读取支持（线格式见 L2 §2.11）。
 *
 * <p>每事件一帧：{@code id: {taskId}-{seq}} / {@code event: <名>} / {@code data: <单行 JSON>}，
 * 帧间空行；心跳是 {@code : keepalive} 注释行。
 *
 * <p>DA 前端只认**带空格**的 {@code event: } / {@code data: } 前缀（{@code id:} 与注释行兼容无空格），
 * 所以本读取器保留原始行，供断言"字段名后必须带空格"。
 */
final class DaCompatSseSupport {

    /** 一帧；{@code heartbeat=true} 表示这是注释行（心跳）。 */
    record Frame(String id, String event, String data, List<String> rawLines, boolean heartbeat) {

        boolean eventHasSpacePrefix() {
            return rawLines.stream().anyMatch(line -> line.startsWith("event: "));
        }

        boolean dataHasSpacePrefix() {
            return rawLines.stream().anyMatch(line -> line.startsWith("data: "));
        }

        long dataLineCount() {
            return rawLines.stream().filter(line -> line.startsWith("data:")).count();
        }
    }

    private DaCompatSseSupport() {}

    static HttpResponse<InputStream> openRunStream(HttpClient client, String base, String sessionId, String input,
            Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/agent/run-stream"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(DaCompatStreamTestBase.SESSION_HEADER, sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(
                        DaCompatStreamTestBase.runStreamBody(sessionId, input)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    static HttpResponse<InputStream> openStream(HttpClient client, String base, String taskId, String sessionId,
            String lastEventId, Duration timeout) throws Exception {
        String query = lastEventId == null || lastEventId.isBlank() ? "" : "?lastEventId=" + lastEventId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/agent/stream/" + taskId + query))
                .timeout(timeout)
                .header(DaCompatStreamTestBase.SESSION_HEADER, sessionId)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    static HttpResponse<String> getStatus(HttpClient client, String base, String taskId, String sessionId,
            Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/agent/status/" + taskId))
                .timeout(timeout)
                .header(DaCompatStreamTestBase.SESSION_HEADER, sessionId)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 读到 {@code stop} 为真的帧（含该帧）为止；到 EOF 或超时也返回已读到的帧。 */
    static List<Frame> readFrames(InputStream stream, Predicate<Frame> stop, Duration timeout) {
        List<Frame> collected = new ArrayList<>();
        ExecutorService pool = newDaemonPool("da-sse-reader");
        Future<?> future = pool.submit((java.util.concurrent.Callable<Void>) () -> {
            readInto(stream, stop, collected);
            return null;
        });
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return List.copyOf(collected);
        } catch (TimeoutException timeoutFailure) {
            closeQuietly(stream);
            throw new IllegalStateException("SSE 读取超时（" + timeout.toSeconds() + "s）："
                    + "未在期限内出现期望的帧终止条件", timeoutFailure);
        } catch (Exception failure) {
            throw new IllegalStateException("SSE 读取失败: " + failure.getMessage(), failure);
        } finally {
            pool.shutdownNow();
            // 命中停止条件即视为客户端收流结束：关闭连接（重连用例正是靠这一步模拟断线）。
            closeQuietly(stream);
        }
    }

    /**
     * 一直读到服务端关流（EOF）为止，返回全部帧。
     *
     * <p>用于"done 后必须关流"这类判据：若服务端不关流，本方法在超时后抛错（即 FAIL 信号）；
     * 正常返回本身就证明连接已按契约关闭。
     */
    static List<Frame> readFramesUntilEof(InputStream stream, Duration timeout) {
        List<Frame> collected = new ArrayList<>();
        ExecutorService pool = newDaemonPool("da-sse-eof-reader");
        Future<?> future = pool.submit((java.util.concurrent.Callable<Void>) () -> {
            readInto(stream, frame -> false, collected);
            return null;
        });
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return List.copyOf(collected);
        } catch (TimeoutException timeoutFailure) {
            closeQuietly(stream);
            throw new IllegalStateException("服务端未在 " + timeout.toSeconds() + "s 内关流（done 后应关流）", timeoutFailure);
        } catch (Exception failure) {
            throw new IllegalStateException("SSE 读取失败: " + failure.getMessage(), failure);
        } finally {
            pool.shutdownNow();
            closeQuietly(stream);
        }
    }

    /**
     * 在线读取器：后台持续读流、边读边暴露已到达的帧，供"先拿 taskId 再打断点/取消/注入"的用例使用。
     *
     * <p>与 {@link #readFrames} 的区别是**不主动关流**，只由调用方 {@link #close()} 收尾。
     */
    static LiveReader liveReader(InputStream stream, Predicate<Frame> stop) {
        LiveReader reader = new LiveReader(stream, stop);
        reader.start();
        return reader;
    }

    static final class LiveReader {

        private final InputStream stream;
        private final Predicate<Frame> stop;
        private final List<Frame> frames = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile Throwable failure;
        private Thread thread;

        private LiveReader(InputStream stream, Predicate<Frame> stop) {
            this.stream = stream;
            this.stop = stop;
        }

        private void start() {
            thread = new Thread(() -> {
                try {
                    readInto(stream, stop, frames);
                } catch (Throwable thrown) {
                    failure = thrown;
                }
            }, "da-sse-live-reader");
            thread.setDaemon(true);
            thread.start();
        }

        List<Frame> snapshot() {
            synchronized (frames) {
                return List.copyOf(frames);
            }
        }

        Frame awaitFrame(Predicate<Frame> predicate, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (Frame frame : snapshot()) {
                    if (predicate.test(frame)) {
                        return frame;
                    }
                }
                if (failure != null) {
                    throw new IllegalStateException("SSE 读取失败: " + failure.getMessage(), failure);
                }
                sleepQuietly(50L);
            }
            throw new IllegalStateException("等待期望事件帧超时（" + timeout.toSeconds() + "s）");
        }

        List<Frame> awaitFinish(Duration timeout) {
            try {
                thread.join(timeout.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return snapshot();
        }

        void close() {
            closeQuietly(stream);
        }
    }

    private static void readInto(InputStream stream, Predicate<Frame> stop, List<Frame> frames) throws Exception {
        List<String> raw = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                Frame frame = build(raw);
                raw.clear();
                if (frame != null) {
                    frames.add(frame);
                    if (stop.test(frame)) {
                        break;
                    }
                }
                continue;
            }
            raw.add(line);
        }
    }

    private static ExecutorService newDaemonPool(String threadName) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Frame build(List<String> rawLines) {
        if (rawLines.isEmpty()) {
            return null;
        }
        String id = null;
        String event = null;
        StringBuilder data = null;
        boolean heartbeat = true;
        for (String line : rawLines) {
            if (line.startsWith(":")) {
                continue;
            }
            heartbeat = false;
            if (line.startsWith("id:")) {
                id = line.substring(3).trim();
            } else if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data == null) {
                    data = new StringBuilder();
                } else {
                    data.append('\n');
                }
                data.append(line.substring(5).trim());
            }
        }
        return new Frame(id, event, data == null ? null : data.toString(), List.copyOf(rawLines), heartbeat);
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // 关闭失败不影响已采集证据
        }
    }
}
