/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.multimodal.MultimodalObservationTools;
import com.openjiuwen.agents.multimodal.ObservationToolServices;
import com.openjiuwen.agents.multimodal.desktop.LocalRobotConnector;
import com.openjiuwen.agents.multimodal.tools.ImageCropTool;
import com.openjiuwen.agents.multimodal.tools.ImageObserveTool;
import com.openjiuwen.agents.multimodal.tools.ImageResizeTool;
import com.openjiuwen.agents.multimodal.tools.ImageRotateTool;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.example.multimodal.tools.SampleImages;
import com.openjiuwen.harness.tools.ToolOutput;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FEAT-032 CORE-002 工具直驱验收：基于 multi-modal-tools-demo 的同一工具链
 * （ObservationToolServices + image_observe/image_crop/image_rotate/image_resize/
 * desktop_screenshot，与 {@code MultimodalToolsAgent} 挂载的工具完全同源），
 * 离线执行（工具链不需要模型）。
 *
 * <p>覆盖场景：F032-16（保序与失败隔离）、F032-17（批量上限）、F032-18/19/20
 * （region/旋转/resize 校验）、F032-21（原图不可变与缓存命中）、F032-22（桌面截图
 * 元数据与 asset: 链式复用）、F032-23（headless 显式失败）。断言落在 ToolOutput
 * data 事实字段（items/multimodal/asset/transform/reason），不读工具内部状态。
 *
 * <p>fixture 复用 demo 模块 {@link SampleImages} 样张（2400x1600 报表页：
 * 表格区 TABLE_REGION 裁出 660x200）。
 *
 * @since 0.1.2
 */
@Tag("integration")
@Tag("feat-032")
@Tag("multimodal")
@Tag("sut-direct")
@Feature("FEAT-032: 智能体支持多模态")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat032SutToolDirectTest {

    @TempDir
    static Path workspace;

    private ObservationToolServices services;
    private Path samplePage;
    private Path brokenImage;

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeTrue(Feat032SutSupport.solutionToolsPresent() && Feat032SutSupport.demoAgentPresent(),
                "FEAT-032 dependency-gated: agent-core-ext-multimodal-tools / multi-modal-tools-demo "
                        + "制品未就绪（需 agent-solution common!882 构建）");
        services = ObservationToolServices.createDefault();
        samplePage = SampleImages.writeSamplePage(workspace);
        brokenImage = workspace.resolve("broken.png");
        Files.write(brokenImage, "not-an-image-at-all".getBytes(StandardCharsets.UTF_8));
    }

    private static ToolOutput invoke(Tool tool, Map<String, Object> inputs) {
        try {
            Object raw = tool.invoke(inputs, Map.of());
            assertThat(raw).isInstanceOf(ToolOutput.class);
            return (ToolOutput) raw;
        } catch (java.lang.Exception ex) {
            throw new IllegalStateException("tool invoke failed: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolOutput output) {
        assertThat(output.getData()).isInstanceOf(Map.class);
        return (Map<String, Object>) output.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    // ------------------------------------------------------------------
    // F032-16 多图观察保序与单项失败隔离（SC-06，主门禁）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-16")
    @Story("FEAT-032.F032-16: 3 图（中间坏图）观察按输入 index 保序，单项失败不取消其他项")
    @DisplayName("F032-16[直驱] 多图观察保序与失败隔离")
    void f03216ObserveBatchOrderPreservedAndFailureIsolated() {
        List<Map<String, Object>> images = List.of(
                Map.of("source", samplePage.toString(), "description", "整页"),
                Map.of("source", brokenImage.toString(), "description", "坏图"),
                Map.of("source", samplePage.toString(), "description", "整页重看"));
        ToolOutput output = invoke(new ImageObserveTool(services), Map.of("images", images));

        assertThat(output.isSuccess()).as("部分失败不算整批失败").isTrue();
        Map<String, Object> data = dataOf(output);
        assertThat(data.get("total")).isEqualTo(3);
        assertThat(data.get("succeeded")).isEqualTo(2);
        assertThat(data.get("failed")).isEqualTo(1);

        List<Map<String, Object>> items = listOf(data.get("items"));
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).containsEntry("index", 0).containsEntry("status", "success");
        assertThat(items.get(1)).containsEntry("index", 1).containsEntry("status", "failed");
        assertThat(items.get(2)).containsEntry("index", 2).containsEntry("status", "success");
        assertThat(field(items.get(1), "error", "reason"))
                .isEqualTo(Feat032SutSupport.REASON_IMAGE_LOAD_FAILED);

        // multimodal[] 仅含成功图且顺序保持；data_url 存在但工具文本侧不承载（由 buildToolMessage 摘要）
        List<Map<String, Object>> multimodal = listOf(data.get("multimodal"));
        assertThat(multimodal).hasSize(2);
        assertThat(multimodal.get(0)).containsEntry("source_path", samplePage.toString());
        assertThat(multimodal.get(1)).containsEntry("source_path", samplePage.toString());
    }

    // ------------------------------------------------------------------
    // F032-17 观察批量超 20 显式失败
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-17")
    @Story("FEAT-032.F032-17: 单批 21 张显式失败 CONTENT_LIMIT_EXCEEDED，不自动拆批、不部分执行")
    @DisplayName("F032-17[直驱] 观察批量上限 20 超限显式失败")
    void f03217ObserveBatchOverLimitFailsExplicitly() {
        List<Map<String, Object>> images = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            images.add(Map.of("source", samplePage.toString()));
        }
        ToolOutput output = invoke(new ImageObserveTool(services), Map.of("images", images));

        assertThat(output.isSuccess()).isFalse();
        Map<String, Object> data = dataOf(output);
        assertThat(data.get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_LIMIT_EXCEEDED);
        assertThat(String.valueOf(data.get("message")))
                .as("失败信息给出限额与实际值，且不自动拆批").contains("20").contains("21");
        assertThat(data.containsKey("items")).as("不部分执行（无逐项结果）").isFalse();
    }

    // ------------------------------------------------------------------
    // F032-18 region 裁剪坐标映射（SC-07）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-18")
    @Story("FEAT-032.F032-18: TABLE_REGION 裁出 660x200，scaleX/scaleY 与派生宽高自洽；越界 CONTENT_INVALID")
    @DisplayName("F032-18[直驱] region 裁剪坐标映射自洽且越界拒绝")
    void f03218RegionCropCoordinateMappingAndBounds() {
        ImageCropTool crop = new ImageCropTool(services);
        Map<String, Object> inputs = Map.of(
                "source", samplePage.toString(),
                "region", SampleImages.TABLE_REGION,
                "description", "表格区");
        ToolOutput output = invoke(crop, inputs);

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = dataOf(output);
        Map<String, Object> asset = assetOf(data);
        assertThat(asset.get("width")).isEqualTo(660);
        assertThat(asset.get("height")).isEqualTo(200);
        assertThat(asset.get("derivedFrom")).as("derivedFrom 回指原图资产").isNotNull();
        assertThat(field(data, "transform", "scaleX")).isEqualTo(660 / 2400.0);
        assertThat(field(data, "transform", "scaleY")).isEqualTo(200 / 1600.0);

        // 越界 region：显式 CONTENT_INVALID，不静默截断
        ToolOutput overflow = invoke(crop, Map.of(
                "source", samplePage.toString(),
                "region", Map.of("x", 2000, "y", 1500, "width", 800, "height", 400)));
        assertThat(overflow.isSuccess()).isFalse();
        assertThat(dataOf(overflow).get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
    }

    // ------------------------------------------------------------------
    // F032-19 旋转归一化与非整数拒绝（SC-08）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-19")
    @Story("FEAT-032.F032-19: angle=-90 等价 270 执行（宽高互换）；angle=45.5 报 CONTENT_INVALID")
    @DisplayName("F032-19[直驱] 旋转归一化与非整数拒绝")
    void f03219RotateNormalizationAndNonIntegerRejected() {
        ImageRotateTool rotate = new ImageRotateTool(services);

        ToolOutput normalized = invoke(rotate,
                Map.of("source", samplePage.toString(), "angle", -90));
        assertThat(normalized.isSuccess()).isTrue();
        Map<String, Object> asset = assetOf(dataOf(normalized));
        assertThat(asset.get("width")).as("外接扩展后宽高互换").isEqualTo(1600);
        assertThat(asset.get("height")).isEqualTo(2400);

        ToolOutput fractional = invoke(rotate,
                Map.of("source", samplePage.toString(), "angle", 45.5));
        assertThat(fractional.isSuccess()).isFalse();
        assertThat(dataOf(fractional).get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
    }

    // ------------------------------------------------------------------
    // F032-20 resize 五选一与 clarity 只缩不放
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-20")
    @Story("FEAT-032.F032-20: width+scale 并给 / scale=0 / scale=11 均 CONTENT_INVALID")
    @DisplayName("F032-20[直驱] resize 参数五选一与 scale 边界")
    void f03220ResizeParameterValidation() {
        ImageResizeTool resize = new ImageResizeTool(services);

        ToolOutput bothGiven = invoke(resize, Map.of(
                "source", samplePage.toString(), "width", 800, "scale", 2.0));
        assertThat(bothGiven.isSuccess()).isFalse();
        assertThat(dataOf(bothGiven).get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);

        ToolOutput zeroScale = invoke(resize, Map.of("source", samplePage.toString(), "scale", 0));
        assertThat(zeroScale.isSuccess()).isFalse();
        assertThat(dataOf(zeroScale).get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);

        ToolOutput overScale = invoke(resize, Map.of("source", samplePage.toString(), "scale", 11));
        assertThat(overScale.isSuccess()).isFalse();
        assertThat(dataOf(overScale).get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
    }

    // ------------------------------------------------------------------
    // F032-21 原图不可变、缓存命中与单次编码（主链路）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-21")
    @Story("FEAT-032.F032-21: 相同版本+相同参数二次裁剪 cacheHit=true，派生字节一致，原图 sha 不变")
    @DisplayName("F032-21[直驱] 原图不可变、缓存命中与单次编码")
    void f03221OriginalImmutableCacheHitSingleEncoding() throws Exception {
        ImageCropTool crop = new ImageCropTool(services);
        Map<String, Object> inputs = Map.of(
                "source", samplePage.toString(),
                "region", SampleImages.TABLE_REGION);
        String originalSha = Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(samplePage));

        ToolOutput first = invoke(crop, inputs);
        assertThat(first.isSuccess()).isTrue();
        Map<String, Object> firstData = dataOf(first);
        assertThat(field(firstData, "asset", "cacheHit")).isEqualTo(Boolean.FALSE);

        ToolOutput second = invoke(crop, inputs);
        assertThat(second.isSuccess()).isTrue();
        Map<String, Object> secondData = dataOf(second);
        assertThat(field(secondData, "asset", "cacheHit"))
                .as("相同图片版本+相同参数二次调用命中缓存").isEqualTo(Boolean.TRUE);
        assertThat(field(secondData, "asset", "derivedFrom"))
                .isEqualTo(field(firstData, "asset", "derivedFrom"));
        // 派生图字节一致（不发生二次有损压缩）：multimodal[].data_url 完全一致
        assertThat(multimodalDataUrl(secondData)).isEqualTo(multimodalDataUrl(firstData));
        // 原图字节 sha256 处理前后不变
        assertThat(Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(samplePage)))
                .isEqualTo(originalSha);
    }

    // ------------------------------------------------------------------
    // F032-22 桌面截图元数据与资产链式复用（真机；headless 由 F032-23 验失败语义）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-22")
    @Story("FEAT-032.F032-22: 截图元数据四要素齐全，资产可被 image_crop 以 asset: 链式复用")
    @DisplayName("F032-22[直驱] 桌面截图元数据与 asset: 链式复用")
    void f03222DesktopScreenshotMetadataAndAssetChaining() {
        Assumptions.assumeTrue(!java.awt.GraphicsEnvironment.isHeadless(),
                "FEAT-032 dependency-gated: headless 环境无桌面会话（失败语义见 F032-23 直驱）");
        Tool tool = MultimodalObservationTools.createDesktopScreenshotTool(services, new LocalRobotConnector());
        ToolOutput shot = invoke(tool, Map.of());

        assertThat(shot.isSuccess()).isTrue();
        Map<String, Object> data = dataOf(shot);
        assertThat(String.valueOf(data.get("connectionId"))).as("connectionId 缺省 local").contains("local");
        assertThat(String.valueOf(data.get("machineId"))).isNotBlank();
        assertThat(String.valueOf(data.get("capturedAt"))).as("ISO 时间戳").isNotBlank();
        Map<String, Object> asset = assetOf(data);
        assertThat(asset.get("assetId")).isNotNull();

        // 资产链式复用：image_crop 以 asset:<assetId> 处理截图产物
        ToolOutput chained = invoke(new ImageCropTool(services), Map.of(
                "source", "asset:" + asset.get("assetId"),
                "region", Map.of("x", 0, "y", 0, "width", 100, "height", 100)));
        assertThat(chained.isSuccess()).as("asset: 引用可被其他图片工具复用").isTrue();
        assertThat(field(dataOf(chained), "asset", "assetId")).isNotNull();
    }

    // ------------------------------------------------------------------
    // F032-23 headless 显式失败（无桌面会话环境）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-23")
    @Story("FEAT-032.F032-23: headless 下截图显式 ENVIRONMENT_UNAVAILABLE，无占位成功、无伪 PNG")
    @DisplayName("F032-23[直驱] headless 截图显式失败语义")
    void f03223HeadlessScreenshotFailsExplicitly() {
        Assumptions.assumeTrue(java.awt.GraphicsEnvironment.isHeadless(),
                "FEAT-032 dependency-gated: 需 headless 环境（有桌面会话时正向语义见 F032-22）；"
                        + "桌面机激活命令：mvn test -Pheadless-gate "
                        + "-Dtest=Feat032SutToolDirectTest#f03223HeadlessScreenshotFailsExplicitly");
        Tool tool = MultimodalObservationTools.createDesktopScreenshotTool(services, new LocalRobotConnector());
        ToolOutput shot = invoke(tool, Map.of());

        assertThat(shot.isSuccess()).as("不返回占位成功").isFalse();
        Map<String, Object> data = dataOf(shot);
        assertThat(data.get("reason")).isEqualTo(Feat032SutSupport.REASON_ENVIRONMENT_UNAVAILABLE);
        assertThat(String.valueOf(data)).as("无伪 PNG 产物").doesNotContain("data:image");
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> assetOf(Map<String, Object> data) {
        assertThat(data.get("asset")).as("成功结果必须携带 asset 事实块").isInstanceOf(Map.class);
        return (Map<String, Object>) data.get("asset");
    }

    @SuppressWarnings("unchecked")
    private static Object field(Map<String, Object> data, String section, String key) {
        Object nested = data.get(section);
        assertThat(nested).as("data.%s 必须存在", section).isInstanceOf(Map.class);
        return ((Map<String, Object>) nested).get(key);
    }

    @SuppressWarnings("unchecked")
    private static String multimodalDataUrl(Map<String, Object> data) {
        List<Map<String, Object>> multimodal = listOf(data.get("multimodal"));
        assertThat(multimodal).hasSize(1);
        Object dataUrl = multimodal.get(0).get("data_url");
        assertThat(dataUrl).as("multimodal 项携带 data_url").isNotNull();
        return String.valueOf(dataUrl);
    }
}
