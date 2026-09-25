package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.config.TestConfig;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code da.asset.version}：条款版本标识可回溯、模板漂移可被工具检出（静态形态）。
 *
 * <p>判据来源：特性 §3.1「模板版本钉死」+ §2 验收出口 #5/#6；设计侧 2026-09-22 澄清（A-Q5）：
 * {@code baseCoreVersion} 是**内容来源版本**（允许与消费版本不同），漂移检出工具必须把
 * "来源版本 ≠ 实际消费版本"作为漂移信号输出。
 *
 * <p>本类对**实际发布制品**做静态核对（不依赖运行时）：资产元数据文件在制品内齐备、且带可回溯的
 * 版本字段。模板漂移工具的"检出结论"由开发侧白盒（{@code TemplateDriftCheckerTest}，3 用例）承接，
 * 本线只做制品侧可回溯性核对。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaAssetVersionE2EIT {

    @Test
    @Story("da.asset.version: 资产元数据在制品内可回溯")
    @DisplayName("da.asset.version: 发布制品内资产 meta 齐备且带版本字段")
    void assetMetadataIsTraceableInArtifact() throws Exception {
        Path jar = resolveArtifact();
        assertThat(Files.isReadable(jar)).as("发布制品必须可读: " + jar).isTrue();

        List<String> metaEntries = new ArrayList<>();
        StringBuilder compressionMeta = new StringBuilder();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("BOOT-INF/classes/prompt-assets/") && name.endsWith(".meta.yaml")) {
                    metaEntries.add(name);
                }
                if (name.endsWith("prompt-assets/templates/compression/template.meta.yaml")) {
                    try (InputStream stream = zip.getInputStream(entry)) {
                        compressionMeta.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        Allure.addAttachment("制品内资产 meta 条目", "text/plain", metaEntries.toString());
        Allure.addAttachment("压缩模板 meta 内容", "text/yaml", compressionMeta.toString());

        assertThat(metaEntries)
                .as("条款/模板/子 Agent/段落四类资产 meta 必须随制品发布（版本可回溯的前提）")
                .anyMatch(name -> name.contains("/clauses/"))
                .anyMatch(name -> name.contains("/templates/"))
                .anyMatch(name -> name.contains("/subagents/"))
                .anyMatch(name -> name.contains("/segments/"));

        String meta = compressionMeta.toString();
        assertThat(meta).as("压缩模板 meta 必须在场").isNotBlank();
        assertThat(meta).as("模板 meta 必须带可回溯的版本标识").containsIgnoringCase("version");
        assertThat(meta)
                .as("受控副本须标明基线来源版本（A-Q5：来源版本允许与消费版本不同；字段名以仓内实际为准）")
                .containsAnyOf("baseCoreVersion", "base_core_version", "coreVersion", "core_version", "0.1.15");
    }

    private static Path resolveArtifact() {
        String repo = System.getenv("SUT_M2_REPO");
        if (repo == null || repo.isBlank()) {
            repo = Path.of(System.getProperty("user.home"), ".m2", "repository").toString();
        }
        // 制品版本与被测 SUT 的声明同源（application-<env>.yml 的 sut.agents.deepanalyze.version，
        // 可被 SUT_AGENTS_DEEPANALYZE_VERSION 覆盖），不把版本写死在用例里。
        TestConfig config = TestConfig.load();
        String version = config.getString("sut.agents.deepanalyze.version");
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(
                    "未声明被测制品版本：请在 application-<env>.yml 设置 sut.agents.deepanalyze.version，"
                            + "或用 SUT_AGENTS_DEEPANALYZE_VERSION 覆盖");
        }
        String classifier = config.getString("sut.agents.deepanalyze.classifier", "exec");
        String relative = "com/openjiuwen/deepanalyze-engine/" + version
                + "/deepanalyze-engine-" + version + "-" + classifier + ".jar";
        return Path.of(repo, relative.split("/"));
    }
}
