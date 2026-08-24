package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

/**
 * FEAT-028 SSE 用例共享收集器：发起 `SendStreamingMessage`，按行读 SSE 流，
 * 每条 `data:` 行反序列化为 {@link Frame} 记录（原始 JSON + 时间戳 + 事件类型）。
 *
 * <p><b>wire 事实</b>（见 [[a2a-wire-contract]]）：流式帧为
 * `result.statusUpdate.{taskId,status.state}` 或 `result.artifactUpdate.taskId`（StreamingEventKind
 * protobuf JSON 名），不是 `result.task`。
 */
final class EdpaSseCollector {

    /** 一条 SSE data 帧的快照。 */
    static final class Frame {
        final long timestampMs;
        final String rawData;
        final JsonNode parsed; // null 当 parse 失败
        final String eventKind; // "statusUpdate" / "artifactUpdate" / "unknown"

        Frame(long ts, String raw, JsonNode parsed, String kind) {
            this.timestampMs = ts;
            this.rawData = raw;
            this.parsed = parsed;
            this.eventKind = kind;
        }
    }

    private EdpaSseCollector() {}

    /**
     * 发起 SSE 请求，收集所有帧直到流关闭或 {@code capMs} 超时（sender 侧关闭 or 客户端 timeout）。
     * @return 帧列表（按到达顺序）。
     */
    static List<Frame> collect(HttpClient http, String a2aUrl, String requestBody, long capMs) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Frame> frames = new ArrayList<>();
        // 诊断落盘（临时，可通过 -Dsit.feat028.dump-sse-path=/tmp/foo.jsonl 打开）——为回答"SSE 里的
        // status 值是否对"这类灰盒问题提供离线分析材料。
        String dumpPath = System.getProperty("sit.feat028.dump-sse-path", "");
        java.io.PrintWriter dump = null;
        if (!dumpPath.isEmpty()) {
            dump = new java.io.PrintWriter(new java.io.FileWriter(dumpPath, true), true);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(a2aUrl))
                .timeout(Duration.ofMillis(capMs))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        long deadline = System.currentTimeMillis() + capMs;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while (System.currentTimeMillis() < deadline && (line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                JsonNode parsed = null;
                String kind = "unknown";
                try {
                    parsed = mapper.readTree(data);
                    JsonNode result = parsed.path("result");
                    if (!result.path("statusUpdate").isMissingNode()) kind = "statusUpdate";
                    else if (!result.path("artifactUpdate").isMissingNode()) kind = "artifactUpdate";
                } catch (Exception ignore) {
                    // 保留 rawData，parsed=null
                }
                if (dump != null) dump.println(data);
                frames.add(new Frame(System.currentTimeMillis(), data, parsed, kind));
            }
        } catch (Exception streamEnd) {
            // 超时/服务端关流：以已收帧为准。
        } finally {
            if (dump != null) dump.close();
        }
        return frames;
    }
}
