/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.transport.InboundEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行为对齐取证夹具（FEAT-031 §4.2「运行保真对齐」要求的行为对齐手段）。
 *
 * <p>用途：从一轮真实 A2A 交互的事件流里提取「这次实际走了哪些节点」（行为轨迹），
 * 与 golden 文件里的期望轨迹比对。轨迹来自 studio-dsl 宿主对外发出的节点帧
 * （payload 内含 {@code node_id}），不依赖节点 I/O 日志，因此在 {@code #302} 未修复时同样可用。</p>
 *
 * <p>边界：本夹具只回答「走哪条边 / 经过哪些节点」；它不替代节点执行语义的验证（那属 FEAT-031 §3）。</p>
 *
 * @since 2026-09-20
 */
public final class StudioDslBehaviorTraceFixture {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 节点帧里的 node_id（兼容原始 JSON 与转义 JSON 两种形态，比对前统一反转义）。 */
    private static final Pattern NODE_ID = Pattern.compile("\"node_id\"\\s*:\\s*\"([^\"]+)\"");

    private StudioDslBehaviorTraceFixture() {}

    /**
     * 从交互事件里提取节点轨迹（保持出现顺序，去掉连续重复）。
     *
     * @param events 某一轮的入站事件
     * @return 节点 id 序列，例如 {@code [node_start, node_branch, node_llm_default, node_agg, node_end]}
     */
    public static List<String> nodeTrace(List<InboundEvent> events) {
        List<String> trace = new ArrayList<>();
        if (events == null) {
            return List.copyOf(trace);
        }
        for (InboundEvent event : events) {
            scan(event.text(), trace);
            scan(String.valueOf(event.raw()), trace);
        }
        return List.copyOf(trace);
    }

    private static void scan(String haystack, List<String> trace) {
        if (haystack == null || haystack.isEmpty()) {
            return;
        }
        String normalized = haystack.replace("\\\"", "\"");
        Matcher matcher = NODE_ID.matcher(normalized);
        while (matcher.find()) {
            String nodeId = matcher.group(1);
            if (nodeId.isBlank()) {
                continue;
            }
            if (!trace.isEmpty() && trace.get(trace.size() - 1).equals(nodeId)) {
                continue;
            }
            trace.add(nodeId);
        }
    }

    /**
     * 从宿主 I/O 采样文本（{@code HostState.ioTraceSample}）里提取节点轨迹。
     *
     * <p>该 SIT 宿主的采样格式不在本仓维护，因此这里不假设帧格式：直接按"已知节点 id 的出现顺序"还原
     * 轨迹（同一 id 的连续重复只记一次）。已知节点 id 来自被测 IR 的 components。</p>
     *
     * @param sample 宿主 I/O 采样文本，可为 null/空
     * @param knownNodeIds 被测 IR 里的节点 id 集合
     * @return 节点 id 序列；采样为空或无命中时返回空列表（调用方按增强证据处理）
     */
    public static List<String> nodeTraceFromIoTrace(String sample, Collection<String> knownNodeIds) {
        List<String> trace = new ArrayList<>();
        if (sample == null || sample.isEmpty() || knownNodeIds == null || knownNodeIds.isEmpty()) {
            return List.copyOf(trace);
        }
        List<Hit> hits = new ArrayList<>();
        for (String nodeId : knownNodeIds) {
            if (nodeId == null || nodeId.isBlank()) {
                continue;
            }
            int from = 0;
            while (true) {
                int index = sample.indexOf(nodeId, from);
                if (index < 0) {
                    break;
                }
                hits.add(new Hit(index, nodeId));
                from = index + nodeId.length();
            }
        }
        hits.sort(Comparator.comparingInt(Hit::index));
        for (Hit hit : hits) {
            if (trace.isEmpty() || !trace.get(trace.size() - 1).equals(hit.nodeId())) {
                trace.add(hit.nodeId());
            }
        }
        return List.copyOf(trace);
    }

    private record Hit(int index, String nodeId) {}

    /**
     * 读取行为 golden 文件。
     *
     * @param resourcePath classpath 路径，例如 {@code /testdata/studio_dsl/behavior-golden/x.json}
     * @return golden 内容
     */
    public static BehaviorGolden loadGolden(String resourcePath) {
        try (InputStream in = StudioDslBehaviorTraceFixture.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("behavior golden not found: " + resourcePath);
            }
            JsonNode root = MAPPER.readTree(in);
            List<String> trace = new ArrayList<>();
            for (JsonNode node : root.path("expectedTrace")) {
                trace.add(node.asText());
            }
            return new BehaviorGolden(
                    root.path("scenario").asText(""),
                    trace,
                    markers(root.path("expectedMarkers")),
                    root.path("expectedMarker").asText(""),
                    root.path("forbiddenMarker").asText(""),
                    root.path("expectationSource").asText(""),
                    resourcePath);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 行为 golden：期望轨迹 + 期望命中的下游标记。 */
    public record BehaviorGolden(
            String scenario,
            List<String> expectedTrace,
            List<String> expectedMarkers,
            String expectedMarker,
            String forbiddenMarker,
            String expectationSource,
            String resourcePath) {}

    private static List<String> markers(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : nodes) {
            values.add(node.asText());
        }
        return List.copyOf(values);
    }
}
