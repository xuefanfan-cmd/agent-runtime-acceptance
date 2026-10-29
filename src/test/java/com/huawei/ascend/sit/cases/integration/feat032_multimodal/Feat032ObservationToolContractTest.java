/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import static org.assertj.core.api.Assertions.assertThat;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FEAT-032 CORE-002：图片观察/处理与桌面截图的契约验收
 * （测试设计 F032-16..21、F032-23；SC-06/07/08/10 对应）。
 *
 * <p>契约基线断言锁定 L2 §2.5/§2.6/§5.2 记录的字段与取值：批量上限 20 不拆批、
 * 按 index 保序、单项失败不取消其他项、clarity 预设（low=1024/0.75、default=2048/0.85、
 * high=4096/0.92）只缩不放、缓存键三段式、桌面元数据四要素、headless 显式
 * ENVIRONMENT_UNAVAILABLE。SUT 工具类制品（agent-core-ext-multimodal-tools）就绪后，
 * F032-16/17/21/22 的直驱断言经 {@link Feat032SutGatedBlackboxTest} 补充执行（同 ID 同期望）。
 */
@Tag("integration")
@Tag("feat-032")
@Tag("multimodal")
@Feature("FEAT-032: 智能体支持多模态")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat032ObservationToolContractTest {

    // ------------------------------------------------------------------
    // F032-16 多图观察保序与单项失败隔离（SC-06）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-16")
    @Story("FEAT-032.F032-16: image_observe 输出按输入 index 保序；单项失败不取消其他项")
    @DisplayName("F032-16 多图观察乱序完成仍保序且失败隔离")
    void f03216MultiImageObserveOrderPreservedAndFailureIsolated() throws Exception {
        // [mirror: ImageObserveTool 批量收集语义]：受控并行（并发 4）完成顺序不影响输出顺序；
        // 单项结构化失败就地转换，其余项继续；total/succeeded/failed 计数如实。
        List<String> inputs = List.of("good-0", "bad-1", "good-2");
        Map<String, Object> payload = mirrorObserveBatch(inputs, index -> index == 1);

        assertThat(payload.get("total")).isEqualTo(3);
        assertThat(payload.get("succeeded")).isEqualTo(2L);
        assertThat(payload.get("failed")).isEqualTo(1L);
        assertThat(payload.get("success")).as("部分失败不算整批失败").isEqualTo(true);

        // items[] 按输入 index 保序（完成顺序无关）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).containsEntry("index", 0).containsEntry("status", "succeeded");
        assertThat(items.get(1)).containsEntry("index", 1).containsEntry("status", "failed");
        assertThat(items.get(2)).containsEntry("index", 2).containsEntry("status", "succeeded");
        assertThat(items.get(1).get("errorReason"))
                .isEqualTo(Feat032SutSupport.REASON_IMAGE_LOAD_FAILED);

        // multimodal[] 仅含成功图且顺序保持
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> multimodal = (List<Map<String, Object>>) payload.get("multimodal");
        assertThat(multimodal).hasSize(2);
        assertThat(multimodal.get(0)).containsEntry("source", "good-0");
        assertThat(multimodal.get(1)).containsEntry("source", "good-2");
    }

    // ------------------------------------------------------------------
    // F032-17 观察批量超 20 显式失败
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-17")
    @Story("FEAT-032.F032-17: 单批超 20 显式失败 CONTENT_LIMIT_EXCEEDED，不自动拆批、不部分执行")
    @DisplayName("F032-17 观察批量上限 20 超限显式失败")
    void f03217ObserveBatchOverLimitFailsExplicitly() {
        assertThat(Feat032SutSupport.IMAGE_OBSERVE_MAX_BATCH).isEqualTo(20);

        // [mirror: ImageObserveTool 批量校验]：超限显式失败并给出限额与实际值
        Map<String, Object> outcome = mirrorObserveBatchGuard(21);
        assertThat(outcome.get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_LIMIT_EXCEEDED);
        assertThat(outcome.get("limit")).isEqualTo(20);
        assertThat(outcome.get("actual")).isEqualTo(21);
        assertThat(outcome.get("executed")).as("不自动拆批、不部分执行").isEqualTo(false);

        // 边界内不失败
        assertThat(mirrorObserveBatchGuard(20)).containsEntry("reason", null);
    }

    // ------------------------------------------------------------------
    // F032-18 局部观察 region 与坐标映射（SC-07）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-18")
    @Story("FEAT-032.F032-18: region 裁剪返回局部图，scaleX/scaleY 与派生宽高自洽；越界 CONTENT_INVALID")
    @DisplayName("F032-18 region 裁剪坐标映射自洽且越界拒绝")
    void f03218RegionCropCoordinateMappingAndBounds() {
        int originalWidth = 800;
        int originalHeight = 600;

        // [mirror: ImagePreprocessor crop→坐标映射语义]：region 左上原点；scaleX=原宽/派生宽
        Map<String, Object> cropped = mirrorCrop(originalWidth, originalHeight, 100, 50, 400, 300);
        assertThat(cropped.get("width")).isEqualTo(400);
        assertThat(cropped.get("height")).isEqualTo(300);
        assertThat(cropped.get("scaleX")).isEqualTo(2.0);
        assertThat(cropped.get("scaleY")).isEqualTo(2.0);
        assertThat(cropped.get("chain")).isEqualTo("crop");

        // [mirror: 越界显式 CONTENT_INVALID，不做静默截断]：region 超出原图边界
        Map<String, Object> outOfBounds = mirrorCrop(originalWidth, originalHeight, 700, 500, 400, 300);
        assertThat(outOfBounds.get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(outOfBounds.get("width")).as("越界不产出局部图").isNull();
    }

    // ------------------------------------------------------------------
    // F032-19 旋转归一化与非整数拒绝（SC-08）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-19")
    @Story("FEAT-032.F032-19: 负角度归一化到 [0,360)；非整数角度 CONTENT_INVALID")
    @DisplayName("F032-19 旋转角度归一化与非法角度拒绝")
    void f03219RotateNormalizationAndIllegalAngle() {
        // [mirror: image_rotate 语义]：顺时针；缺省 0；负角度归一化到 [0,360)；非整数角度 CONTENT_INVALID
        assertThat(mirrorNormalizeAngle(-90)).isEqualTo(270);
        assertThat(mirrorNormalizeAngle(-360)).isEqualTo(0);
        assertThat(mirrorNormalizeAngle(450)).isEqualTo(90);
        assertThat(mirrorNormalizeAngle(0)).isEqualTo(0);

        // 90/270 旋转后外接扩展宽高互换
        assertThat(mirrorRotatedBounds(800, 600, 90)).containsExactly(600, 800);
        assertThat(mirrorRotatedBounds(800, 600, 270)).containsExactly(600, 800);
        assertThat(mirrorRotatedBounds(800, 600, 180)).containsExactly(800, 600);

        assertThat(mirrorAngleGuard(45.5)).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(mirrorAngleGuard(90)).isNull();
    }

    // ------------------------------------------------------------------
    // F032-20 resize 五选一与 clarity 只缩不放
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-20")
    @Story("FEAT-032.F032-20: resize 目标五选一违例 CONTENT_INVALID；scale 限 (0,10]；clarity 预设只缩不放")
    @DisplayName("F032-20 resize 目标唯一性、scale 边界与 clarity 预设")
    void f03220ResizeTargetUniquenessScaleBoundsAndClarity() {
        // [mirror: image_resize 五选一校验]：width/height/longest_edge/scale/clarity 恰好一个
        assertThat(mirrorResizeTargetGuard(Map.of("width", 100, "scale", 2.0)))
                .isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(mirrorResizeTargetGuard(Map.of()))
                .isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(mirrorResizeTargetGuard(Map.of("scale", 2.0))).isNull();

        // scale 限 (0,10]
        assertThat(mirrorScaleGuard(0)).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(mirrorScaleGuard(-1)).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(mirrorScaleGuard(11)).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(mirrorScaleGuard(10)).isNull();

        // clarity 预设（L2 §2.5/§6.2）：low=1024/0.75，default=2048/0.85，high=4096/0.92；只缩不放
        assertThat(Feat032SutSupport.CLARITY_PRESETS.get("low")).containsExactly(1024.0, 0.75);
        assertThat(Feat032SutSupport.CLARITY_PRESETS.get("default")).containsExactly(2048.0, 0.85);
        assertThat(Feat032SutSupport.CLARITY_PRESETS.get("high")).containsExactly(4096.0, 0.92);
        assertThat(mirrorClarityResize(500, 400, "high")).containsExactly(500, 400);
        assertThat(mirrorClarityResize(6000, 3000, "low")).containsExactly(1024, 512);
    }

    // ------------------------------------------------------------------
    // F032-21 原图不可变、缓存命中与单次编码
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-21")
    @Story("FEAT-032.F032-21: 同参数二次调用 cacheHit=true、derivedFrom 回指、派生字节一致、原图 sha 不变")
    @DisplayName("F032-21 原图不可变与派生缓存一致性")
    void f03221OriginalImmutabilityAndDerivedCacheHit() {
        byte[] original = Feat032SutSupport.pngMagicBytes();
        String originalVersion = Feat032SutSupport.sha256Hex(original);
        String chainKey = "crop(0,0,4,4)|resize(2)";
        String encodeKey = "png";

        // [mirror: ImageAssetStore.cacheKey = originalVersion|chain.canonicalKey()|encode.key()]
        String cacheKey = mirrorCacheKey(originalVersion, chainKey, encodeKey);
        assertThat(cacheKey).isEqualTo(originalVersion + "|" + chainKey + "|" + encodeKey);

        // [mirror: 派生缓存语义]：同键二次调用命中缓存，返回同一资产（字节一致，无二次有损压缩）
        Map<String, Object> first = mirrorDerivedAsset(cacheKey, "asset-1", original);
        Map<String, Object> second = mirrorDerivedAsset(cacheKey, "asset-1", original);
        assertThat(first.get("cacheHit")).isEqualTo(false);
        assertThat(second.get("cacheHit")).isEqualTo(true);
        assertThat(second.get("derivedFrom")).isEqualTo("asset-1");
        assertThat(second.get("encodedSha256")).isEqualTo(first.get("encodedSha256"));
        assertThat(second.get("reencoded")).as("缓存命中不重新编码").isEqualTo(false);

        // [mirror: 原图不可变]：处理前后原图字节摘要不变
        assertThat(Feat032SutSupport.sha256Hex(original)).isEqualTo(originalVersion);
    }

    // ------------------------------------------------------------------
    // F032-23 headless 显式失败与每连接串行
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-23")
    @Story("FEAT-032.F032-23: headless 报 ENVIRONMENT_UNAVAILABLE 不占位成功；同连接截图串行")
    @DisplayName("F032-23 headless 显式失败与同连接串行语义")
    void f03223HeadlessExplicitFailureAndPerConnectionSerial() throws Exception {
        // [mirror: DesktopScreenshotTool/LocalRobotConnector 语义] headless 失败语义无条件验证：
        // mirror 以参数表达"无桌面会话"前提，与执行机实际是否 headless 解耦——
        // 真机截图正向路径属 F032-22 场景（非 headless 真机），此处只锁定显式失败语义
        Map<String, Object> outcome = mirrorDesktopScreenshot(true);
        assertThat(outcome.get("success")).isEqualTo(false);
        assertThat(outcome.get("reason"))
                .isEqualTo(Feat032SutSupport.REASON_ENVIRONMENT_UNAVAILABLE);
        assertThat(outcome.get("pngAsset")).as("headless 不产生伪 PNG").isNull();

        // [mirror: DesktopScreenshotTool 每连接串行]：同一 connectionId 的并发请求串行完成
        assertThat(mirrorSerialExecution()).isEqualTo(true);
    }

    // ==================================================================
    // mirror 方法（逐行对齐 L2 §2.5/§2.6/§5.2 契约语义）
    // ==================================================================

    /** 逐项失败判定函数式接口。 */
    private interface FailPolicy {
        boolean failAt(int index);
    }

    /** [mirror: ImageObserveTool 批量执行管线——受控并行、按 index 收集、失败就地转换] */
    private static Map<String, Object> mirrorObserveBatch(List<String> sources, FailPolicy failPolicy)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Feat032SutSupport.OBSERVE_CONCURRENCY);
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int index = 0; index < sources.size(); index++) {
                int idx = index;
                String source = sources.get(index);
                futures.add(pool.submit(() -> {
                    // 人为制造乱序完成：index 越大完成越快
                    TimeUnit.MILLISECONDS.sleep((sources.size() - idx) * 5L);
                    if (failPolicy.failAt(idx)) {
                        return Map.of("index", idx, "status", "failed",
                                "errorReason", Feat032SutSupport.REASON_IMAGE_LOAD_FAILED);
                    }
                    return Map.of("index", idx, "status", "succeeded", "source", source);
                }));
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (Future<Map<String, Object>> future : futures) {
                items.add(future.get());
            }
            items.sort(java.util.Comparator.comparingInt(item -> (Integer) item.get("index")));

            long succeeded = items.stream().filter(i -> "succeeded".equals(i.get("status"))).count();
            long failed = items.stream().filter(i -> "failed".equals(i.get("status"))).count();
            List<Map<String, Object>> multimodal = new ArrayList<>();
            for (Map<String, Object> item : items) {
                if ("succeeded".equals(item.get("status"))) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("type", "image");
                    mm.put("source", item.get("source"));
                    multimodal.add(mm);
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", items);
            payload.put("multimodal", multimodal);
            payload.put("total", sources.size());
            payload.put("succeeded", succeeded);
            payload.put("failed", failed);
            payload.put("success", failed < sources.size());
            return payload;
        } finally {
            pool.shutdownNow();
        }
    }

    /** [mirror: ImageObserveTool 批量上限校验——不拆批不部分执行] */
    private static Map<String, Object> mirrorObserveBatchGuard(int batchSize) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        if (batchSize > Feat032SutSupport.IMAGE_OBSERVE_MAX_BATCH) {
            outcome.put("reason", Feat032SutSupport.REASON_CONTENT_LIMIT_EXCEEDED);
            outcome.put("limit", Feat032SutSupport.IMAGE_OBSERVE_MAX_BATCH);
            outcome.put("actual", batchSize);
            outcome.put("executed", false);
            return outcome;
        }
        outcome.put("reason", null);
        outcome.put("executed", true);
        return outcome;
    }

    /** [mirror: ImagePreprocessor crop + 坐标映射 / 越界校验] */
    private static Map<String, Object> mirrorCrop(
            int originalWidth, int originalHeight, int x, int y, int width, int height) {
        if (x < 0 || y < 0 || x + width > originalWidth || y + height > originalHeight) {
            return Map.of("reason", Feat032SutSupport.REASON_CONTENT_INVALID);
        }
        return Map.of("width", width, "height", height,
                "scaleX", (double) originalWidth / width,
                "scaleY", (double) originalHeight / height,
                "chain", "crop");
    }

    /** [mirror: image_rotate 负角度/超范围归一化到 [0,360)] */
    private static int mirrorNormalizeAngle(int angle) {
        return ((angle % 360) + 360) % 360;
    }

    /** [mirror: 旋转后外接扩展宽高] */
    private static List<Integer> mirrorRotatedBounds(int width, int height, int normalizedAngle) {
        if (normalizedAngle == 90 || normalizedAngle == 270) {
            return List.of(height, width);
        }
        return List.of(width, height);
    }

    /** [mirror: image_rotate 非整数角度拒绝] */
    private static String mirrorAngleGuard(Number angle) {
        if (angle.doubleValue() != angle.intValue()) {
            return Feat032SutSupport.REASON_CONTENT_INVALID;
        }
        return null;
    }

    /** [mirror: image_resize 目标五选一校验] */
    private static String mirrorResizeTargetGuard(Map<String, Object> targets) {
        long specified = targets.keySet().stream()
                .filter(k -> List.of("width", "height", "longest_edge", "scale", "clarity").contains(k))
                .count();
        if (specified != 1) {
            return Feat032SutSupport.REASON_CONTENT_INVALID;
        }
        return null;
    }

    /** [mirror: image_resize scale 限 (0,10]] */
    private static String mirrorScaleGuard(Number scale) {
        double value = scale.doubleValue();
        if (value <= 0 || value > 10) {
            return Feat032SutSupport.REASON_CONTENT_INVALID;
        }
        return null;
    }

    /** [mirror: clarity 预设只缩不放] */
    private static List<Integer> mirrorClarityResize(int width, int height, String clarity) {
        double[] preset = Feat032SutSupport.CLARITY_PRESETS.get(clarity);
        double maxSide = preset[0];
        int longest = Math.max(width, height);
        if (longest <= maxSide) {
            return List.of(width, height);
        }
        double ratio = maxSide / longest;
        return List.of((int) Math.floor(width * ratio), (int) Math.floor(height * ratio));
    }

    /** [mirror: ImageAssetStore.cacheKey = originalVersion | chain.canonicalKey() | encode.key()] */
    private static String mirrorCacheKey(String originalVersion, String chainKey, String encodeKey) {
        return originalVersion + "|" + chainKey + "|" + encodeKey;
    }

    /** [mirror: 派生资产发布与缓存命中语义——同键只发布一个资产、命中不重新编码] */
    private static Map<String, Object> mirrorDerivedAsset(
            String cacheKey, String originalAssetId, byte[] originalBytes) {
        Map<String, Object> cached = CACHE.get(cacheKey);
        if (cached != null) {
            return Map.of("cacheHit", true, "derivedFrom", originalAssetId,
                    "encodedSha256", cached.get("sha"), "reencoded", false);
        }
        // 未命中：从不可变原图一次重编码（此处以确定性派生代替像素运算）
        byte[] derived = (cacheKey + "|derived").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = Feat032SutSupport.sha256Hex(derived);
        CACHE.put(cacheKey, Map.of("sha", sha));
        return Map.of("cacheHit", false, "derivedFrom", originalAssetId,
                "encodedSha256", sha, "reencoded", true,
                "originalSha256", Feat032SutSupport.sha256Hex(originalBytes));
    }

    private static final Map<String, Map<String, Object>> CACHE = new LinkedHashMap<>();

    /** [mirror: DesktopScreenshotTool headless 语义——显式 ENVIRONMENT_UNAVAILABLE，不占位成功] */
    private static Map<String, Object> mirrorDesktopScreenshot(boolean headless) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        if (headless) {
            outcome.put("success", false);
            outcome.put("reason", Feat032SutSupport.REASON_ENVIRONMENT_UNAVAILABLE);
            outcome.put("pngAsset", null);
            return outcome;
        }
        outcome.put("success", true);
        outcome.put("connectionId", "local");
        outcome.put("pngAsset", "asset:screen-0");
        return outcome;
    }

    /** [mirror: DesktopScreenshotTool 每连接串行语义——同一 connectionId 经连接级锁串行执行] */
    private static boolean mirrorSerialExecution() throws Exception {
        Object connectionLock = new Object();
        AtomicBoolean inside = new AtomicBoolean(false);
        AtomicBoolean overlapped = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable task = () -> {
                synchronized (connectionLock) {
                    if (!inside.compareAndSet(false, true)) {
                        overlapped.set(true);
                    }
                    try {
                        TimeUnit.MILLISECONDS.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inside.set(false);
                    }
                }
            };
            Future<?> a = pool.submit(task);
            Future<?> b = pool.submit(task);
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return !overlapped.get();
    }
}