/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.dynamic_model_config;

import com.huawei.ascend.sit.config.TestConfig;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-050-CORE 静态合同：直接对**候选制品的 class 字节**取证（不经过类加载，避免被测试类路径里的其他版本抢先加载）。
 *
 * <p>覆盖：请求级模型标识为独立字段（REQ-009）、保留无上下文取模型入口（REQ-027）、
 * timeout 由调用方封装而 core 不解析（REQ-024）、模型清单内存存储（REQ-016，字段在父类 AbstractManager）。</p>
 */
@Tag("integration")
@Tag("dynamic-model-config")
@Tag("feat-050")
@Feature("FEAT-050: 动态模型配置（agent-core）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicModelStaticContractTest {

    private static final String CORE_ARTIFACT =
            "com/openjiuwen/agent-core-java/0.1.16/agent-core-java-0.1.16.jar";
    private static final String CORE_PREFIX = "com/openjiuwen/";

    private Path coreJar;
    private JarFile jar;

    @BeforeAll
    void openCandidateArtifact() throws IOException {
        TestConfig config = TestConfig.load();
        String repo = config.getString("sut.m2.repo", System.getProperty("user.home") + "/.m2/repository");
        coreJar = Path.of(repo).resolve(CORE_ARTIFACT);
        assertThat(Files.exists(coreJar)).as("候选制品应存在于共享 Maven 仓库：%s", coreJar).isTrue();
        jar = new JarFile(coreJar.toFile());
    }

    @Test
    @Story("dmc.api.shape: 请求级标识为独立字段、无上下文取模型入口保留（静态合同）")
    @DisplayName("候选制品含独立的 dynamicModelId 字段与读写方法，且 ReActAgent 保留无参取模型入口")
    void contextFieldAndNoArgAccessor() throws IOException {
        String context = classBytes("com/openjiuwen/core/singleagent/rail/AgentCallbackContext");
        assertThat(context)
                .as("请求上下文应含独立的 dynamicModelId 字段（REQ-009）")
                .contains("dynamicModelId");
        assertThat(context).as("应含 getDynamicModelId（REQ-009）").contains("getDynamicModelId");
        assertThat(context).as("应含 setDynamicModelId（REQ-009）").contains("setDynamicModelId");

        String reactAgent = classBytes("com/openjiuwen/core/singleagent/agents/ReActAgent");
        assertThat(reactAgent)
                .as("ReActAgent 应保留无参 getLlm 入口，供构造期等无上下文场景使用（REQ-027）")
                .contains("getLlm");
    }

    @Test
    @Story("dmc.api.shape: timeout 由调用方封装，core 不解析（静态合同）")
    @DisplayName("候选制品的模型请求配置不含 timeout 属性；模型注册入口只接收供应器")
    void coreDoesNotParseTimeout() throws IOException {
        String requestConfig = classBytes("com/openjiuwen/core/foundation/llm/schema/ModelRequestConfig");
        assertThat(requestConfig.toLowerCase(java.util.Locale.ROOT))
                .as("core 不应解析 timeout 字段（REQ-024：由调用方封装进供应器）")
                .doesNotContain("timeout");

        String resourceMgr = classBytes("com/openjiuwen/core/runner/resourcemanager/ResourceMgr");
        assertThat(resourceMgr)
                .as("模型注册入口应接收供应器（REQ-024）")
                .contains("java/util/function/Supplier");
    }

    @Test
    @Story("dmc.storage.memory: 模型清单内存存储、不持久化（静态合同）")
    @DisplayName("模型清单容器为内存 Map（声明在父类），且不含数据库/Redis/文件持久化字段")
    void modelRegistryIsInMemory() throws IOException {
        String abstractManager = classBytes("com/openjiuwen/core/runner/resourcemanager/AbstractManager");
        assertThat(abstractManager).as("模型清单容器字段应为 providers（REQ-016）").contains("providers");
        assertThat(abstractManager)
                .as("容器应为 Map 族的内存容器（REQ-016：内存；class 常量池描述符形如 Ljava/util/…Map;）")
                .matches("(?s).*Map;.*");

        String modelMgr = classBytes("com/openjiuwen/core/runner/resourcemanager/ModelMgr");
        String lower = modelMgr.toLowerCase(java.util.Locale.ROOT);
        assertThat(lower)
                .as("模型管理器不应依赖数据库/Redis/文件持久化（REQ-016）")
                .doesNotContain("javax/sql").doesNotContain("java/sql").doesNotContain("redis")
                .doesNotContain("java/io/file").doesNotContain("java/nio/file/path");
    }

    private String classBytes(String className) throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        String target = className + ".class";
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().endsWith(target) && entry.getName().startsWith(CORE_PREFIX)) {
                try (InputStream in = jar.getInputStream(entry)) {
                    return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                }
            }
        }
        throw new IllegalStateException("class not found in candidate artifact: " + className);
    }
}
