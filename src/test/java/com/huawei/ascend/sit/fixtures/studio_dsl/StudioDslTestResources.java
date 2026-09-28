/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class StudioDslTestResources {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private StudioDslTestResources() {
    }

    public static String text(String name) {
        String path = "/testdata/studio_dsl/contract/" + name;
        try (InputStream input = StudioDslTestResources.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalArgumentException("missing test resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read test resource: " + path, exception);
        }
    }

    public static byte[] bytes(String name) {
        return text(name).getBytes(StandardCharsets.UTF_8);
    }

    public static Map<String, Object> map(String name) {
        try {
            return MAPPER.readValue(text(name), MAP_TYPE);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse test resource: " + name, exception);
        }
    }

    public static Path copy(String name, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        return Files.writeString(target, text(name), StandardCharsets.UTF_8);
    }
}
