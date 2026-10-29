package com.huawei.ascend.sit.cases.integration.agent_client_ts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FEAT-087 黑盒驱动夹具：解析 SUT（TS SDK 产物）位置，通过 Node 子进程执行
 * driver.mjs 场景脚本，回收 NDJSON（op 结果与事件）。SDK 产物或 Node 运行时
 * 缺失时经 assumeTrue 判 SKIPPED（dependency-gated）。
 */
final class Feat087TsSdkHarness implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_SDK_DIR =
            "D:/code/test/agent-solution/common/agent-client/agent-client-sdk-for-typescript";
    private static final long DEFAULT_DRIVER_TIMEOUT_MS = 120_000;

    private final Feat087MockA2aServer server = new Feat087MockA2aServer();
    private final Path sdkDir;

    Feat087TsSdkHarness() {
        String configured = System.getProperty("feat087.sdk.dir", System.getenv("FEAT087_SDK_DIR"));
        sdkDir = Path.of(configured == null ? DEFAULT_SDK_DIR : configured);
    }

    /** 环境门禁：SDK 产物与 Node 运行时任一缺失即 SKIPPED。 */
    static void assumeEnvironmentReady(Path sdkDir) {
        Assumptions.assumeTrue(Files.isDirectory(sdkDir.toFile().toPath()),
                "FEAT-087 SUT 未交付：缺少 " + sdkDir + "（dependency-gated）");
        Process version = null;
        try {
            version = new ProcessBuilder("node", "--version").start();
            boolean ok = version.waitFor(10, TimeUnit.SECONDS)
                    && version.getInputStream().readAllBytes().length > 0;
            Assumptions.assumeTrue(ok, "node 不可用（FEAT-087 需要 Node >= 18）");
        } catch (IOException | InterruptedException e) {
            Assumptions.assumeTrue(false, "node 不可用：" + e.getMessage());
        } finally {
            if (version != null) {
                version.destroyForcibly();
            }
        }
    }

    Path sdkDir() {
        return sdkDir;
    }

    Feat087MockA2aServer server() {
        return server;
    }

    /**
     * 执行一个场景：scenario = { client, tools?, exposure?, ops: [...] }，
     * 返回 { ops: [...], events: [...], handlerLog: [...] }。
     */
    ObjectNode runDriver(ObjectNode scenario) {
        return runDriver(scenario, DEFAULT_DRIVER_TIMEOUT_MS);
    }

    ObjectNode runDriver(ObjectNode scenario, long timeoutMs) {
        ObjectNode full = JSON.createObjectNode();
        full.put("sdkDir", sdkDir.toString().replace('\\', '/'));
        full.setAll(scenario);
        String driver = System.getProperty("feat087.driver",
                "src/test/resources/feat087/driver.mjs");
        Process process = null;
        try {
            process = new ProcessBuilder("node", driver)
                    .directory(Path.of("").toAbsolutePath().toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            // 必须并发排空 stdout：driver 每帧输出完整 call tree（真实模型流可达 GB 级），
            // Windows 管道缓冲仅 64KB；若等 waitFor 后再读，stdout.write 会阻塞
            // Node 事件循环，SDK 随之停止读取 SSE，真实服务端用例将全部假性超时。
            // 逐行增量解析，避免累计原始字节后再整体转串导致 OOM。
            ObjectNode result = JSON.createObjectNode();
            ArrayNode ops = result.putArray("ops");
            ArrayNode events = result.putArray("events");
            result.set("handlerLog", JSON.createArrayNode());
            final BufferedReader stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            final long[] keptEventBytes = {0L};
            Thread drainer = new Thread(() -> {
                try (BufferedReader in = stdoutReader) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        consumeDriverLine(line, result, ops, events, keptEventBytes);
                    }
                } catch (IOException ignored) {
                    // 进程被销毁时读流抛出属预期
                }
            });
            drainer.setDaemon(true);
            drainer.start();
            OutputStream stdin = process.getOutputStream();
            stdin.write(full.toString().getBytes(StandardCharsets.UTF_8));
            stdin.close();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            drainer.join(5000);
            if (!finished) {
                result.put("driverTimeout", true);
            }
            return result;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("driver 执行失败", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 事件累计字节上限：真实模型流的树事件 O(n²) 膨胀（可达 GB 级），超出后仅保留 ops/done/fatal。 */
    private static final long MAX_KEPT_EVENT_BYTES = 64L * 1024 * 1024;

    private void consumeDriverLine(String line, ObjectNode result, ArrayNode ops, ArrayNode events,
                                   long[] keptEventBytes) {
        if (line == null || line.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (IOException e) {
            return;
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "op", "done", "fatal" -> {
                if ("done".equals(type) || "fatal".equals(type)) {
                    if (node.hasNonNull("handlerLog")) {
                        result.set("handlerLog", node.get("handlerLog"));
                    }
                    if ("fatal".equals(type)) {
                        result.set("fatal", node.path("error"));
                    }
                } else {
                    ops.add(node);
                }
            }
            // driver 除 op/done/fatal 外全部为事件行：树事件为 type=event 包装，
            // 业务事件（status/terminal/diagnostic/confirmation.* 等）直接携带类型
            default -> {
                if (keptEventBytes[0] < MAX_KEPT_EVENT_BYTES) {
                    keptEventBytes[0] += line.length();
                    events.add(node);
                }
            }
        }
    }

    // ---------- 场景构造辅助 ----------

    static ObjectNode clientSpec(String kind, String url, String conversationId) {
        ObjectNode client = JSON.createObjectNode();
        client.put("kind", kind);
        client.put("url", url);
        client.put("conversationId", conversationId);
        return client;
    }

    static ObjectNode scenario(ObjectNode client, ArrayNode ops) {
        ObjectNode scenario = JSON.createObjectNode();
        scenario.set("client", client);
        scenario.set("ops", ops);
        return scenario;
    }

    static ObjectNode op(String op) {
        ObjectNode node = JSON.createObjectNode();
        node.put("op", op);
        return node;
    }

    static ObjectNode invokeOp(String mode, String input, String invocationId, String await) {
        ObjectNode node = op("invoke");
        node.put("mode", mode);
        node.put("input", input);
        if (invocationId != null) {
            node.put("invocationId", invocationId);
        }
        if (await != null) {
            node.put("await", await);
        }
        return node;
    }

    static JsonNode opResult(ObjectNode driverResult, int index) {
        return driverResult.path("ops").path(index);
    }

    static List<JsonNode> events(ObjectNode driverResult, String type) {
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode ev : driverResult.path("events")) {
            if (type.equals(ev.path("type").asText())) {
                matched.add(ev);
            }
        }
        return matched;
    }

    /** 调用树快照事件：driver 以 type=event、kind=tree 发射（区别于业务事件）。 */
    static List<JsonNode> treeEvents(ObjectNode driverResult) {
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode ev : driverResult.path("events")) {
            if ("tree".equals(ev.path("kind").asText())) {
                matched.add(ev);
            }
        }
        return matched;
    }

    @Override public void close() {
        server.close();
    }
}
