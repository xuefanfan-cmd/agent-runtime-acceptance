/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.studio.dsl.ir.IrModelMapping;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrAssembleContext;
import com.openjiuwen.studio.dsl.ir.controller.ControllerIrSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** FEAT-031 §7 controller IR test resources and deterministic map helpers. */
public final class ControllerIrTestFixture {
    public static final String ROOT_AGENT_ID = "71abe847-30b8-4bd2-a150-b52c006e93f9";
    public static final String MODEL_ID = "1788a9e4-c729-40db-8dd8-7cc20b3e6b7d";
    public static final String BUNDLE_RESOURCE_ROOT = "/testdata/studio_dsl/controller_ir/bundle/";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final List<String> BUNDLE_FILES = List.of(
            "manifest.json",
            "model-mapping.json",
            "ir/71abe847-30b8-4bd2-a150-b52c006e93f9.json",
            "ir/62e729c3-70b3-42eb-a47b-d35bfa7e75ce.json",
            "ir/13208a1d-45c9-401f-b24f-0f9cc37f9a35.json",
            "ir/bf54eb96-d433-4ddd-bfc6-d33b490d3b74.json",
            "ir/8513a1b2-b053-488d-87f6-1fab954a9001.json");

    private ControllerIrTestFixture() {
    }

    public static Map<String, Object> realRootIr() {
        return resourceMap("ir/" + ROOT_AGENT_ID + ".json");
    }

    public static Map<String, Object> resourceMap(String relative) {
        try {
            return MAPPER.readValue(resourceBytes(relative), MAP_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse controller IR resource: " + relative, exception);
        }
    }

    public static byte[] resourceBytes(String relative) {
        String path = BUNDLE_RESOURCE_ROOT + relative;
        return classpathBytes(path);
    }

    public static byte[] classpathBytes(String path) {
        try (InputStream input = ControllerIrTestFixture.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("missing classpath resource: " + path);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read classpath resource: " + path, exception);
        }
    }

    public static Map<String, Map<String, Object>> realIrByPath() {
        Map<String, Object> manifest = resourceMap("manifest.json");
        @SuppressWarnings("unchecked")
        Map<String, String> pathMap = (Map<String, String>) manifest.get("irPathToLocal");
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        pathMap.forEach((irPath, localPath) -> result.put(irPath, resourceMap(localPath)));
        return result;
    }

    public static Path materializeBundle(Path directory) {
        try {
            Files.createDirectories(directory);
            for (String relative : BUNDLE_FILES) {
                Path target = directory.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.write(target, resourceBytes(relative));
            }
            return directory;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot materialize controller IR bundle", exception);
        }
    }

    public static IrModelMapping resolvedModelMapping() {
        return IrModelMapping.filledPlaceholders(
                Set.of(MODEL_ID), "http://127.0.0.1:1", "contract-key", "OpenAI");
    }

    public static ControllerIrAssembleContext mapContext(Map<String, Map<String, Object>> irByPath) {
        return ControllerIrAssembleContext.builder()
                .irSource(new ControllerIrSource.MapControllerIrSource(irByPath))
                .modelMapping(resolvedModelMapping())
                .build();
    }

    public static Map<String, Object> minimalRoot() {
        Map<String, Object> configs = new LinkedHashMap<>();
        configs.put("mode", "Controller");
        configs.put("maxIteration", 20);
        configs.put("chatHistoryMaxTurn", 20);
        configs.put("specify_workflow_order", false);
        configs.put("sysPromptTemplate", "");
        configs.put("agents", new ArrayList<>());
        configs.put("workflows", new ArrayList<>());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "0.6.0");
        root.put("agentId", "root");
        root.put("agentVersion", "0.6.0");
        root.put("agentName", "root-agent");
        root.put("description", "root-description");
        root.put("configs", configs);
        return root;
    }

    public static Map<String, Object> minimalChild(String agentId) {
        Map<String, Object> child = minimalRoot();
        child.put("agentId", agentId);
        child.put("agentName", agentId + "-name");
        child.put("description", agentId + "-description");
        return child;
    }

    public static Map<String, Object> childReference(String id, String irPath) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("id", id);
        reference.put("ir_path", irPath);
        return reference;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> configs(Map<String, Object> ir) {
        return (Map<String, Object>) ir.get("configs");
    }

    public static String stringValue(Map<String, Object> map, String key) {
        return String.valueOf(map.get(key));
    }

    public static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
