/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.studio_dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.sit.fixtures.studio_dsl.LocalS3CompatibleStoreFixture;
import com.huawei.ascend.sit.fixtures.studio_dsl.StudioDslContractTestBase;
import com.openjiuwen.studio.dsl.fetch.bundle.OfflineBundleFetcher;
import com.openjiuwen.studio.dsl.fetch.error.FetchException;
import com.openjiuwen.studio.dsl.fetch.store.FilesystemObjectStore;
import com.openjiuwen.studio.dsl.fetch.store.ObjectStore;
import com.openjiuwen.studio.dsl.fetch.store.ObjectStoreFactory;
import com.openjiuwen.studio.dsl.fetch.store.ObsFetchConfig;
import com.openjiuwen.studio.dsl.fetch.store.S3CompatibleObjectStore;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Tag("integration")
@Tag("studio-dsl")
@Tag("feat-031")
@Feature("FEAT-031: Studio DSL Java 承载")
class ObsFetchIntegrationTest extends StudioDslContractTestBase {
    private static final String KEY = "workflow/ir/rt-031-04-03.json";

    @TempDir
    Path tempDir;

    @Test
    @Story("RT-031-04-03: OBS 故障表面")
    @DisplayName("S3 不可达、拒绝、缺 key 和读失败均可诊断且无有效 manifest")
    void reportObsFailuresWithoutPartialBundle() throws Exception {
        Path export = export("rt-031-04-03", KEY);
        assertFailure(
                new ObsFetchConfig(
                        LocalS3CompatibleStoreFixture.unavailableEndpoint(),
                        "us-east-1",
                        LocalS3CompatibleStoreFixture.ACCESS_KEY,
                        LocalS3CompatibleStoreFixture.SECRET_KEY,
                        LocalS3CompatibleStoreFixture.BUCKET),
                export,
                tempDir.resolve("unavailable"),
                "FETCH_OBS_UNAVAILABLE");

        try (LocalS3CompatibleStoreFixture obs = new LocalS3CompatibleStoreFixture()) {
            assertInjectedFailure(obs, export, "denied", 403, "FETCH_OBS_READ_FAILED");
            assertInjectedFailure(obs, export, "missing", 404, "FETCH_OBS_KEY_MISSING");
            assertInjectedFailure(obs, export, "read-failed", 500, "FETCH_OBS_READ_FAILED");
        }
    }

    @Test
    @Tag("security")
    @Story("RT-031-04-18: OBS 最小权限凭据")
    @DisplayName("同一只读凭据仅允许 path-style GET workflow/ir/*")
    void enforceReadOnlyWorkflowIrScope() throws Exception {
        String allowed = "workflow/ir/rt-031-04-18-allowed.json";
        String denied = "studio/admin/rt-031-04-18-denied.json";
        byte[] expected = "rt-031-04-18".getBytes(StandardCharsets.UTF_8);
        try (LocalS3CompatibleStoreFixture obs = new LocalS3CompatibleStoreFixture().put(allowed, expected);
                S3CompatibleObjectStore store = new S3CompatibleObjectStore(obs.config())) {
            int before = obs.requestCount();

            assertThat(store.getObject(allowed)).isEqualTo(expected);
            assertThatThrownBy(() -> store.getObject(denied))
                    .isInstanceOfSatisfying(FetchException.class,
                            exception -> assertThat(exception.code()).isEqualTo("FETCH_OBS_READ_FAILED"));

            List<LocalS3CompatibleStoreFixture.RequestRecord> requests = obs.requestsSince(before);
            assertThat(requests).hasSize(2).allSatisfy(request -> {
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.path()).startsWith("/" + LocalS3CompatibleStoreFixture.BUCKET + "/");
                assertThat(request.authorization())
                        .contains("Credential=" + LocalS3CompatibleStoreFixture.ACCESS_KEY + "/");
            });
            assertThat(requests).extracting(LocalS3CompatibleStoreFixture.RequestRecord::objectKey)
                    .containsExactly(allowed, denied);
        }
    }

    private void assertInjectedFailure(
            LocalS3CompatibleStoreFixture obs,
            Path export,
            String name,
            int status,
            String code) {
        obs.fail(KEY, status);
        int before = obs.requestCount();
        assertFailure(obs.config(), export, tempDir.resolve(name), code);
        assertThat(obs.requestsSince(before)).isNotEmpty().allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.objectKey()).isEqualTo(KEY);
        });
    }

    private static void assertFailure(ObsFetchConfig config, Path export, Path bundle, String code) {
        try (S3CompatibleObjectStore store = new S3CompatibleObjectStore(config)) {
            assertThatThrownBy(() -> new OfflineBundleFetcher(store, false).fetch(export, bundle))
                    .isInstanceOfSatisfying(FetchException.class,
                            exception -> assertThat(exception.code()).isEqualTo(code));
            assertThat(bundle.resolve("manifest.json")).doesNotExist();
        }
    }

    private Path export(String workflowId, String key) throws Exception {
        Path export = tempDir.resolve(workflowId + ".jsonl");
        String row = "{\"resource_type\":\"workflow\",\"metadata\":{\"id\":\""
                + workflowId + "\",\"ir_path\":\"" + key + "\"}}\n";
        return Files.writeString(export, row, StandardCharsets.UTF_8);
    }

    /**
     * L2 §7.4：{@code FilesystemObjectStore} 仅作单测 / 离线镜像适配器，{@code --obs-root} 时生效，
     * <strong>不得充当默认生产实现</strong>（默认交付锁定 {@link S3CompatibleObjectStore}）。本用例走公共
     * 工厂与公开 store API，不访问网络、不依赖 Studio。
     */
    @Test
    @Story("RT-031-04-18/mirror: FilesystemObjectStore 离线镜像桩")
    @DisplayName("镜像桩：obs-root 生效、字节一致、失败码精确，且不作为默认生产实现")
    void filesystemMirrorStoreHonorsKeyScopeAndFailureSurface() throws Exception {
        String allowed = "workflow/ir/rt-031-04-18-mirror.json";
        String missing = "workflow/ir/rt-031-04-18-missing.json";
        byte[] expected = "rt-031-04-18-mirror".getBytes(StandardCharsets.UTF_8);
        Path root = tempDir.resolve("obs-root");
        Files.createDirectories(root.resolve("workflow/ir"));
        Files.write(root.resolve(allowed), expected);
        Files.write(tempDir.resolve("outside-canary.json"), "outside".getBytes(StandardCharsets.UTF_8));

        // ① --obs-root 生效：工厂返回镜像桩（离线路径）
        assertThat(ObjectStoreFactory.create(root, null))
                .as("obs-root 非空时工厂必须选择 FilesystemObjectStore")
                .isInstanceOf(FilesystemObjectStore.class);

        ObjectStore mirror = new FilesystemObjectStore(root);

        // ② 读取成功：字节与镜像文件逐字节一致
        assertThat(mirror.getObject(allowed))
                .as("镜像路径 workflow/ir/* 的读取结果")
                .isEqualTo(expected);

        // ③ 失败面：空 key / 路径逃逸 / 缺 key 均为可诊断的 FETCH_OBS_KEY_MISSING
        assertThatThrownBy(() -> mirror.getObject(""))
                .as("空 key")
                .isInstanceOfSatisfying(FetchException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FETCH_OBS_KEY_MISSING"));
        assertThatThrownBy(() -> mirror.getObject("../outside-canary.json"))
                .as("路径逃逸必须被拒绝")
                .isInstanceOfSatisfying(FetchException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FETCH_OBS_KEY_MISSING"));
        assertThatThrownBy(() -> mirror.getObject(missing))
                .as("缺 key")
                .isInstanceOfSatisfying(FetchException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FETCH_OBS_KEY_MISSING"));

        // ④ 不得充当默认生产实现：未提供 obs-root 时必须落到 S3 实现
        ObsFetchConfig s3Config = new ObsFetchConfig(
                LocalS3CompatibleStoreFixture.unavailableEndpoint(),
                "us-east-1",
                LocalS3CompatibleStoreFixture.ACCESS_KEY,
                LocalS3CompatibleStoreFixture.SECRET_KEY,
                LocalS3CompatibleStoreFixture.BUCKET);
        assertThat(ObjectStoreFactory.create(null, s3Config))
                .as("obs-root 为空时不得返回镜像桩")
                .isInstanceOf(S3CompatibleObjectStore.class);

        // ⑤ 镜像根缺失（null）→ FETCH_OBS_UNAVAILABLE
        assertThatThrownBy(() -> new FilesystemObjectStore(null))
                .as("镜像根为 null")
                .isInstanceOfSatisfying(FetchException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FETCH_OBS_UNAVAILABLE"));
    }
}
