/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.studio_dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.fixtures.studio_dsl.RecordingObjectStore;
import com.huawei.ascend.sit.fixtures.studio_dsl.JulLogCaptureFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslTestResources;
import com.openjiuwen.studio.dsl.fetch.bundle.OfflineBundleFetcher;
import com.openjiuwen.studio.dsl.fetch.cli.FetchCli;
import com.openjiuwen.studio.dsl.fetch.error.FetchException;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("component")
@Tag("contract")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class OfflineBundleFetcherContractTest extends StudioDslContractTestBase {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("默认拉取仅请求 metadata key 并写出完整 manifest")
    @Story("RT-031-04-01: release=false 清单选择与下载")
    void fetchMetadataRowsAndWriteManifest() throws Exception {
        RecordingObjectStore store = new RecordingObjectStore(Map.of(
                "workflow/ir/wf-main.json", StudioDslTestResources.bytes("minimal-start-end.json")));
        Path export = StudioDslTestResources.copy("export-release-both.jsonl", tempDir.resolve("export.jsonl"));
        Path bundle = tempDir.resolve("bundle");

        OfflineBundleFetcher.FetchResult result = new OfflineBundleFetcher(store, false).fetch(export, bundle);

        assertThat(store.reads()).containsOnlyKeys("workflow/ir/wf-main.json");
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.resourceId()).isEqualTo("wf-main");
            assertThat(row.objectKey()).isEqualTo("workflow/ir/wf-main.json");
            assertThat(row.localRelative()).isEqualTo("ir/wf-main.json");
        });
        JsonNode manifest = MAPPER.readTree(bundle.resolve("manifest.json").toFile());
        assertThat(manifest.at("/irPathToLocal/workflow~1ir~1wf-main.json").asText())
                .isEqualTo("ir/wf-main.json");
    }

    @Test
    @DisplayName("发布拉取缺 release_version.ir_path 时失败且不访问对象存储")
    @Story("RT-031-04-02: release=true 与缺字段失败")
    void rejectMissingReleasePathWithoutFallback() throws Exception {
        RecordingObjectStore releaseStore = new RecordingObjectStore(Map.of(
                "workflow/ir/wf-main-release.json", StudioDslTestResources.bytes("minimal-start-end.json")));
        Path both = StudioDslTestResources.copy("export-release-both.jsonl", tempDir.resolve("both.jsonl"));
        OfflineBundleFetcher.FetchResult releaseResult = new OfflineBundleFetcher(releaseStore, true)
                .fetch(both, tempDir.resolve("release-bundle"));
        assertThat(releaseStore.reads()).containsOnlyKeys("workflow/ir/wf-main-release.json");
        assertThat(releaseResult.rows()).singleElement().satisfies(row ->
                assertThat(row.objectKey()).isEqualTo("workflow/ir/wf-main-release.json"));

        RecordingObjectStore store = new RecordingObjectStore(Map.of(
                "workflow/ir/wf-main.json", StudioDslTestResources.bytes("minimal-start-end.json")));
        Path export = StudioDslTestResources.copy("export-missing-release.jsonl", tempDir.resolve("export.jsonl"));
        Path bundle = tempDir.resolve("bundle");

        assertThatThrownBy(() -> new OfflineBundleFetcher(store, true).fetch(export, bundle))
                .isInstanceOfSatisfying(FetchException.class, exception ->
                        assertThat(exception.code()).isEqualTo("FETCH_IR_PATH_MISSING"));
        assertThat(store.reads()).isEmpty();
        assertThat(bundle.resolve("manifest.json")).doesNotExist();
    }

    @Test
    @DisplayName("缺失子工作流仅写入定位 warning 且不递归补拉")
    @Story("RT-031-04-04: 子工作流跟随拉取与缺失 WARN")
    void warnAndSkipMissingSubWorkflow() throws Exception {
        RecordingObjectStore store = new RecordingObjectStore(Map.of(
                "workflow/ir/wf-parent.json", StudioDslTestResources.bytes("parent-missing-child.json")));
        Path export = StudioDslTestResources.copy("export-parent-only.jsonl", tempDir.resolve("export.jsonl"));

        OfflineBundleFetcher.FetchResult result =
                new OfflineBundleFetcher(store, false).fetch(export, tempDir.resolve("bundle"));

        assertThat(store.reads()).containsOnlyKeys("workflow/ir/wf-parent.json");
        assertThat(result.warnings()).singleElement()
                .asString()
                .contains("wf-parent", "workflow/ir/wf-child.json");
        assertThat(result.irPathToLocal()).doesNotContainKey("workflow/ir/wf-child.json");
        JsonNode manifest = MAPPER.readTree(result.bundleDir().resolve("manifest.json").toFile());
        assertThat(manifest.path("warnings").toString())
                .contains("wf-parent", "workflow/ir/wf-child.json");
    }

    @Test
    @DisplayName("CLI 只接受 Studio JSONL 并在失败前保持对象存储和目录不变")
    @Story("RT-031-04-21: 清单输入边界与 CLI 失败表面")
    void rejectDirectIdManifestBeforeObjectRead() throws Exception {
        RecordingObjectStore store = new RecordingObjectStore(Map.of());
        Path export = StudioDslTestResources.copy("direct-id-manifest.jsonl", tempDir.resolve("direct.jsonl"));

        assertThatThrownBy(() -> new OfflineBundleFetcher(store, false)
                .fetch(export, tempDir.resolve("bundle")))
                .isInstanceOfSatisfying(FetchException.class, exception ->
                        assertThat(exception.code()).isEqualTo("FETCH_WORKFLOW_ID_MISSING"));
        assertThat(store.reads()).isEmpty();

        try (JulLogCaptureFixture logs = new JulLogCaptureFixture(FetchCli.class)) {
            FetchCli.main(new String[] {"--help"});
            assertThatThrownBy(() -> FetchCli.main(new String[] {}))
                    .isInstanceOfSatisfying(FetchException.class, exception ->
                            assertThat(exception.code()).isEqualTo("FETCH_CLI_INVALID"));
            assertThat(logs.messages()).anySatisfy(message ->
                    assertThat(message).contains("Usage", "--export", "--bundle-dir"));
        }

        Path missingBundle = tempDir.resolve("missing-bundle");
        assertThatThrownBy(() -> FetchCli.main(new String[] {
            "--export", tempDir.resolve("missing.jsonl").toString(),
            "--obs-root", tempDir.resolve("obs").toString(),
            "--bundle-dir", missingBundle.toString()
        })).isInstanceOfSatisfying(FetchException.class, exception ->
                assertThat(exception.code()).isEqualTo("FETCH_EXPORT_MISSING"));
        assertThat(missingBundle).doesNotExist();

        Path invalidBundle = tempDir.resolve("invalid-bundle");
        assertThatThrownBy(() -> FetchCli.main(new String[] {
            "--unknown", "value", "--bundle-dir", invalidBundle.toString()
        })).isInstanceOfSatisfying(FetchException.class, exception ->
                assertThat(exception.code()).isEqualTo("FETCH_CLI_INVALID"));
        assertThat(invalidBundle).doesNotExist();

        Path obsRoot = tempDir.resolve("legal-obs");
        StudioDslTestResources.copy(
                "minimal-start-end.json", obsRoot.resolve("workflow/ir/wf-main.json"));
        Path legalExport = StudioDslTestResources.copy(
                "export-release-both.jsonl", tempDir.resolve("legal.jsonl"));
        Path legalBundle = tempDir.resolve("legal-bundle");
        FetchCli.main(new String[] {
            "--export", legalExport.toString(),
            "--obs-root", obsRoot.toString(),
            "--bundle-dir", legalBundle.toString()
        });
        assertThat(legalBundle.resolve("ir/wf-main.json")).isRegularFile();
        JsonNode legalManifest = MAPPER.readTree(legalBundle.resolve("manifest.json").toFile());
        assertThat(legalManifest.path("rowCount").asInt()).isEqualTo(1);
        assertThat(legalManifest.at("/irPathToLocal/workflow~1ir~1wf-main.json").asText())
                .isEqualTo("ir/wf-main.json");
    }
}
