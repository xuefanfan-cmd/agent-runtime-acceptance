/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat056;

import static org.assertj.core.api.Assertions.assertThat;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * FEAT-056 交付形态的包级校验（C01/C02/C23）。
 *
 * <p>这些用例校验的是"构建产物本身"：原库是不是普通 Library JAR、两个二开应用是否嵌入同一份未改动的原库、
 * 重建是否只改变外层应用包。产物由平台构建步骤预先产出，路径可用系统属性覆盖：
 * {@code -Dfeat056.example.dir=<agentcore-instance-secondary-development 目录>}、
 * {@code -Dfeat056.customerB.dir=<customer-b 目录>}、
 * {@code -Dfeat056.customerBv2.dir=<customer-b-v2 目录>}。
 *
 * <p>产物缺失时按 {@code Assumptions} 跳过而不是判失败——缺产物是前置不具备，不是产品结论。
 *
 * @since 0.1.2
 */
@Tag("integration")
@Tag("feat-056")
@Feature("FEAT-056: 业务 Agent（Lib JAR）二次开发扩展点")
class AgentInstanceCustomizerPackagingContractTest {
    private static final String LIBRARY_JAR_NAME = "deepagent-a-library-0.1.2.jar";
    private static final String EMBEDDED_LIBRARY_ENTRY = "BOOT-INF/lib/" + LIBRARY_JAR_NAME;

    private static final Path EXAMPLE_DIR = workspacePath("feat056.example.dir",
            "02-Code/_worktrees/agent-solution-feat056-d4044eeb/common/example/"
                    + "agentcore-instance-secondary-development");
    private static final Path CUSTOMER_B_DIR = workspacePath("feat056.customerB.dir",
            ".codex/tmp/20261012-feat056/customer-b");
    private static final Path CUSTOMER_B_V2_DIR = workspacePath("feat056.customerBv2.dir",
            ".codex/tmp/20261012-feat056/customer-b-v2");

    @Test
    @Story("FEAT-056-C01: 原库以不可独立启动的 Library JAR 交付")
    void libraryJarIsPlainLibraryWithoutBootLoader() throws Exception {
        Path library = EXAMPLE_DIR.resolve("deepagent-a-library/target/" + LIBRARY_JAR_NAME);
        Assumptions.assumeTrue(Files.isRegularFile(library), "library jar not built: " + library);

        try (ZipFile jar = new ZipFile(library.toFile())) {
            assertThat(names(jar))
                    .as("Library JAR 不得是 Spring Boot 可执行包")
                    .noneMatch(name -> name.startsWith("BOOT-INF/"))
                    .noneMatch(name -> name.startsWith("org/springframework/boot/loader/"));
            assertThat(names(jar))
                    .as("必须包含业务库配置类")
                    .anyMatch(name -> name.endsWith(
                            "com/openjiuwen/example/secondary/library/AgentAConfiguration.class"));
            assertThat(jar.getEntry("META-INF/MANIFEST.MF")).as("普通库仍应有 manifest").isNotNull();
        }
    }

    @Test
    @Story("FEAT-056-C02: 两个二开应用嵌入同一份未改动的原库")
    void bothAppsEmbedTheSameUnchangedLibraryJar() throws Exception {
        Path library = EXAMPLE_DIR.resolve("deepagent-a-library/target/" + LIBRARY_JAR_NAME);
        Path appA = EXAMPLE_DIR.resolve("customer-agent-runtime/target/customer-agent-runtime-exec.jar");
        Path appB = CUSTOMER_B_DIR.resolve("target/customer-b-runtime-exec.jar");
        Assumptions.assumeTrue(Files.isRegularFile(library) && Files.isRegularFile(appA)
                && Files.isRegularFile(appB), "artifacts not built");

        String libraryHash = sha256(library);
        assertThat(embeddedEntryHash(appA, EMBEDDED_LIBRARY_ENTRY))
                .as("A 应用包内原库必须等于独立构建值")
                .isEqualTo(libraryHash);
        assertThat(embeddedEntryHash(appB, EMBEDDED_LIBRARY_ENTRY))
                .as("B 应用包内原库必须等于独立构建值")
                .isEqualTo(libraryHash);
        assertThat(sha256(appA))
                .as("两个应用的外层包必须不同（各自扩展不同）")
                .isNotEqualTo(sha256(appB));
    }

    @Test
    @Story("FEAT-056-C23: 重建只改变外层应用包，原库不重发；旁挂 JAR 不进入应用类路径")
    void rebuildChangesOnlyOuterApplicationJarAndSiblingJarStaysOutside() throws Exception {
        Path library = EXAMPLE_DIR.resolve("deepagent-a-library/target/" + LIBRARY_JAR_NAME);
        Path v1 = CUSTOMER_B_DIR.resolve("target/customer-b-runtime-exec.jar");
        Path v2 = CUSTOMER_B_V2_DIR.resolve("target/customer-b-runtime-exec.jar");
        Assumptions.assumeTrue(Files.isRegularFile(library) && Files.isRegularFile(v1)
                && Files.isRegularFile(v2), "artifacts not built");

        assertThat(sha256(v1)).as("重建后外层包必须变化").isNotEqualTo(sha256(v2));
        String libraryHash = sha256(library);
        assertThat(embeddedEntryHash(v1, EMBEDDED_LIBRARY_ENTRY)).isEqualTo(libraryHash);
        assertThat(embeddedEntryHash(v2, EMBEDDED_LIBRARY_ENTRY))
                .as("重建不改变原库，原库不需要重发")
                .isEqualTo(libraryHash);

        // 旁挂/热加载边界：应用包只从自身 BOOT-INF/lib 加载依赖，部署目录旁的普通 JAR 不在其中。
        try (ZipFile jar = new ZipFile(v2.toFile())) {
            assertThat(names(jar))
                    .as("应用包内不得出现旁挂 JAR（如 customer-c-hotload-sidecar）")
                    .noneMatch(name -> name.contains("hotload-sidecar"));
        }
    }

    @Test
    @Story("FEAT-056-C31: 被执行的主 Library、宿主与扩展可追溯到官方示例源码")
    void officialExampleProvenanceMatchesTheExecutedArtifacts() throws Exception {
        Path library = EXAMPLE_DIR.resolve("deepagent-a-library/target/" + LIBRARY_JAR_NAME);
        Path appA = EXAMPLE_DIR.resolve("customer-agent-runtime/target/customer-agent-runtime-exec.jar");
        Path librarySource = EXAMPLE_DIR.resolve("deepagent-a-library/src/main/java/com/openjiuwen/example/"
                + "secondary/library/AgentAConfiguration.java");
        Path extensionSource = EXAMPLE_DIR.resolve("customer-agent-runtime/src/main/java/com/openjiuwen/example/"
                + "secondary/customer/CustomerExtensions.java");
        Path entrySource = EXAMPLE_DIR.resolve("customer-agent-runtime/src/main/java/com/openjiuwen/example/"
                + "secondary/customer/CustomerRuntimeApplication.java");
        Assumptions.assumeTrue(Files.isRegularFile(library) && Files.isRegularFile(appA)
                && Files.isRegularFile(librarySource) && Files.isRegularFile(extensionSource)
                && Files.isRegularFile(entrySource), "official example sources/artifacts incomplete");

        // 执行用的库必须就是这份官方示例源码构建出来的普通库（同 C02 的哈希口径）。
        assertThat(embeddedEntryHash(appA, EMBEDDED_LIBRARY_ENTRY))
                .as("宿主包内原库必须等于官方示例源码构建的库")
                .isEqualTo(sha256(library));

        // 宿主入口与扩展必须来自官方示例源码，而不是测试侧另写的替代实现。
        try (ZipFile jar = new ZipFile(appA.toFile())) {
            assertThat(jar.getEntry("META-INF/MANIFEST.MF")).isNotNull();
            try (InputStream manifest = jar.getInputStream(jar.getEntry("META-INF/MANIFEST.MF"))) {
                String text = new String(manifest.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                // MANIFEST 的长值按规范每 72 字节折行（换行 + 一个空格续行），断言前先解折行。
                String unfolded = text.replace("\r\n", "\n").replaceAll("\n ", "");
                assertThat(unfolded)
                        .as("可执行包入口必须是官方示例的宿主启动类")
                        .contains("com.openjiuwen.example.secondary.customer.CustomerRuntimeApplication");
            }
            assertThat(names(jar))
                    .as("扩展类必须编进宿主包")
                    .anyMatch(name -> name.endsWith(
                            "com/openjiuwen/example/secondary/customer/CustomerExtensions.class"));
        }
        assertThat(Files.readString(extensionSource))
                .as("官方示例扩展源码必须注册 AgentInstanceCustomizer 并新增 Rail/Tool")
                .contains("AgentInstanceCustomizer")
                .contains("registerRail")
                .contains("registerHarnessTool");
    }

    private static java.util.Set<String> names(ZipFile jar) {
        return jar.stream().map(ZipEntry::getName).collect(java.util.stream.Collectors.toSet());
    }

    private static String embeddedEntryHash(Path appJar, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(appJar.toFile())) {
            ZipEntry entry = jar.getEntry(entryName);
            assertThat(entry).as("%s 内必须存在 %s", appJar.getFileName(), entryName).isNotNull();
            try (InputStream input = jar.getInputStream(entry)) {
                return digest(input);
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return digest(input);
        }
    }

    private static String digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().withUpperCase().formatHex(digest.digest(input.readAllBytes()));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static Path workspacePath(String propertyName, String relative) {
        String override = System.getProperty(propertyName);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String root = System.getProperty("feat056.workspace.root", "/mnt/d/01-文档资料/01-Projects/04-Test_Work");
        return Path.of(root, relative);
    }
}
