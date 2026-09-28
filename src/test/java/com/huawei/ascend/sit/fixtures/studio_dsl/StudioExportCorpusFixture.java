/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.studio.dsl.ir.IrAssembleContext;
import com.openjiuwen.studio.dsl.ir.IrAssembleResult;
import com.openjiuwen.studio.dsl.ir.IrModelMapping;
import com.openjiuwen.studio.dsl.ir.StudioIrSdk;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class StudioExportCorpusFixture {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT = "/testdata/studio_dsl/studio-export/";

    private StudioExportCorpusFixture() {
    }

    public static Manifest manifest() {
        try {
            return MAPPER.readValue(bytes("manifest.json"), Manifest.class);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse Studio export corpus manifest", exception);
        }
    }

    public static Map<String, Object> artifactIr(Manifest manifest, String artifactId) {
        Artifact artifact = manifest.artifactById().get(artifactId);
        if (artifact == null) {
            throw new IllegalArgumentException("unknown Studio export artifact: " + artifactId);
        }
        byte[] content = bytes(artifact.file());
        String actualHash = sha256(content);
        if (!actualHash.equalsIgnoreCase(artifact.sha256())) {
            throw new IllegalStateException(
                    "Studio export artifact hash mismatch: " + artifact.file() + " expected="
                            + artifact.sha256() + " actual=" + actualHash);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> ir = MAPPER.readValue(content, LinkedHashMap.class);
            return ir;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse Studio export artifact: " + artifact.file(), exception);
        }
    }

    public static Map<String, Object> component(
            Manifest manifest, CatalogEntry entry, String componentId) {
        Map<String, Object> ir = artifactIr(manifest, entry.artifact());
        return components(ir).stream()
                .filter(component -> componentId.equals(String.valueOf(component.get("id"))))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "component " + componentId + " is missing from artifact " + entry.artifact()));
    }

    public static IrAssembleResult assembleArtifact(Manifest manifest, String artifactId) {
        Map<String, Object> ir = artifactIr(manifest, artifactId);
        String workflowId = String.valueOf(ir.get("workflowId"));
        IrModelMapping mapping = IrModelMapping.filledPlaceholders(
                IrModelMapping.collectModelNames(ir), "https://model.invalid/v1", "corpus-placeholder-key");
        return StudioIrSdk.loadResult(ir, IrAssembleContext.builder()
                .workflowId(workflowId)
                .modelMapping(mapping)
                .build());
    }

    public static String manifestSha256() {
        return sha256(bytes("manifest.json"));
    }

    /** Derives RT-031-04-27 coverage only from the exported IR graph. */
    public static Set<String> derivedDimensions(Map<String, Object> ir) {
        List<Map<String, Object>> nodes = components(ir);
        Set<String> dimensions = new LinkedHashSet<>();
        Set<String> types = nodes.stream()
                .map(node -> String.valueOf(node.get("type")).toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<Map<String, Object>> connections = objectList(ir.get("connections"));
        Map<String, Set<String>> adjacency = new HashMap<>();
        Map<String, Integer> plainFanOut = new HashMap<>();
        for (Map<String, Object> connection : connections) {
            Map<String, Object> source = objectMap(connection.get("source"));
            Map<String, Object> target = objectMap(connection.get("target"));
            String sourceId = String.valueOf(source.getOrDefault("componentId", ""));
            String targetId = String.valueOf(target.getOrDefault("componentId", ""));
            if (!sourceId.isBlank() && !targetId.isBlank()) {
                adjacency.computeIfAbsent(sourceId, ignored -> new LinkedHashSet<>()).add(targetId);
                if (!source.containsKey("branchId")) {
                    plainFanOut.merge(sourceId, 1, Integer::sum);
                }
            }
        }
        boolean hasTwoEdgePath = adjacency.values().stream()
                .flatMap(Set::stream)
                .anyMatch(adjacency::containsKey);
        if (hasTwoEdgePath) {
            dimensions.add("sequential");
        }
        if (booleanValue(objectMap(ir.get("configs")).get("stream"))
                || nodes.stream().anyMatch(node -> booleanValue(
                        objectMap(node.get("configs")).get("isStreamOut")))) {
            dimensions.add("streaming");
        }
        if (types.stream().anyMatch(type -> type.contains("branch") || type.contains("intentdetection"))
                || connections.stream().anyMatch(connection ->
                        objectMap(connection.get("source")).containsKey("branchId"))) {
            dimensions.add("branch");
        }
        if (types.stream().anyMatch(type -> type.contains("aggregation") || type.contains("aggregate"))
                || plainFanOut.values().stream().anyMatch(count -> count > 1)) {
            dimensions.add("parallel");
        }
        if (types.contains("jiuwen.loop")) {
            dimensions.add("loop");
        }
        if (types.stream().anyMatch(type -> type.contains("workflowcomposite") || type.contains("subworkflow"))) {
            dimensions.add("subworkflow");
        }
        if (types.stream().anyMatch(type -> type.contains("paramoutput") || type.contains("paramextraction"))) {
            dimensions.add("parameter-expansion");
        }
        if (types.stream().anyMatch(type -> type.equals("jiuwen.input")
                || type.equals("jiuwen.questioner") || type.equals("ei.qa"))) {
            dimensions.add("user-interaction");
        }
        if (types.stream().anyMatch(type -> type.contains("plugin") || type.contains("mcp")
                || type.contains("knowledge"))) {
            dimensions.add("external-call");
        }
        if (types.stream().anyMatch(type -> type.contains("exception"))) {
            dimensions.add("exception");
        }
        if (types.stream().anyMatch(type -> type.contains("card"))) {
            dimensions.add("card");
        }
        return Set.copyOf(dimensions);
    }

    private static List<Map<String, Object>> components(Map<String, Object> ir) {
        Object raw = ir.get("components");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("Studio export artifact has no components list");
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(value -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> component = (Map<String, Object>) value;
                    return component;
                })
                .toList();
    }

    private static List<Map<String, Object>> objectList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(StudioExportCorpusFixture::objectMap).toList();
    }

    private static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        map.forEach((key, value) -> converted.put(String.valueOf(key), value));
        return converted;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static byte[] bytes(String relativePath) {
        String path = ROOT + relativePath;
        try (InputStream input = StudioExportCorpusFixture.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("missing Studio export resource: " + path);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read Studio export resource: " + path, exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record Manifest(
            String schemaVersion,
            Studio studio,
            List<CatalogSource> catalogSources,
            List<CatalogEntry> catalog,
            List<Artifact> artifacts,
            List<String> representativeDimensions,
            String executionState) {
        public Map<String, Artifact> artifactById() {
            return artifacts.stream().collect(Collectors.toMap(
                    Artifact::id, Function.identity(), (left, right) -> right, LinkedHashMap::new));
        }
    }

    public record Studio(String repository, String branch, String commit, String captureMode) {
    }

    public record CatalogSource(
            String role, String repository, String commit, String path, String sha256) {
    }

    public record CatalogEntry(
            String node,
            String availability,
            String exportMode,
            String exportType,
            String catalogGate,
            String corpusStatus,
            String artifact,
            List<String> componentIds,
            String observedType,
            List<String> expandedTypes,
            List<String> criticalFields,
            Map<String, List<String>> componentCriticalFields,
            String nestedParentId) {
        public List<String> criticalFieldsFor(String componentId) {
            if (componentCriticalFields != null && componentCriticalFields.containsKey(componentId)) {
                return componentCriticalFields.get(componentId);
            }
            return criticalFields == null ? List.of() : criticalFields;
        }
    }

    public record Artifact(
            String id,
            String file,
            String sourcePath,
            String sha256,
            String workflowId,
            List<String> dimensions,
            String knownBlocker) {
    }
}
