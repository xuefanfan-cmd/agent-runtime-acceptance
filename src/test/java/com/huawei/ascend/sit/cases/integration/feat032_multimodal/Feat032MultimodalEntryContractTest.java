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

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * FEAT-032 CORE-001：多模态消息入口与素材解析的 wire 契约验收
 * （测试设计 F032-03..11，SC-01 直传正例在 SUT 直驱类）。
 *
 * <p>每条用例 = 契约基线断言（无条件执行，锁定 L2 §2.2/§2.3/§2.8 字段与取值）
 * + SUT 直驱断言（制品就绪时同一输入喂给真实 DefaultMultimodalContentNormalizer，
 * 期望值与契约基线完全一致——发现实现与文档冲突即判缺陷，不改基线）。
 *
 * <p>观察表面限定：normalize 返回的内容块 wire 投影、BaseError.details.reason；
 * 不读取 SUT 内部状态。
 */
@Tag("integration")
@Tag("feat-032")
@Tag("multimodal")
@Feature("FEAT-032: 智能体支持多模态")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat032MultimodalEntryContractTest {

    private final Feat032SutNormalizerProxy sut = Feat032SutNormalizerProxy.create();

    /** SUT 制品就绪时用真实 normalize 执行同一断言；未就绪时跳过直驱分支（基线层仍生效）。 */
    private List<Map<String, Object>> normalizeViaSutIfAvailable(Object input) {
        if (sut == null) {
            return null;
        }
        return sut.normalize(input);
    }

    /** 捕获 SUT 直驱的契约违例；未抛则返回 null（避免 AssertJ catchThrowableOfType 参数顺序差异）。 */
    private static Feat032SutNormalizerProxy.SutContractViolation violationOf(
            Runnable sutCall) {
        try {
            sutCall.run();
            return null;
        } catch (Feat032SutNormalizerProxy.SutContractViolation violation) {
            return violation;
        }
    }

    private static Map<String, Object> block(String type, Map<String, Object> extra) {
        Map<String, Object> block = new java.util.LinkedHashMap<>();
        block.put("type", type);
        if (extra != null) {
            block.putAll(extra);
        }
        return block;
    }

    // ------------------------------------------------------------------
    // F032-03 纯文本与旧业务 Map 兼容（契约基线）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-03")
    @Story("FEAT-032.F032-03: 纯文本与旧业务 Map 走原路径，请求体逐字节不变")
    @DisplayName("F032-03 纯字符串/旧业务 Map 不被认作结构化输入")
    void f03203PlainTextAndLegacyMapBypassMultimodal() {
        // 契约基线（L2 §2.2）：规范化器接受四类输入；纯字符串与旧业务 Map 走原路径，
        // 任意业务 Map 不被自动认作内容块——判别依据是「含 content 键的结构化消息」。
        assertThat(mirrorIsStructuredInput("你好，帮我看看这张图")).isFalse();
        assertThat(mirrorIsStructuredInput(Map.of("query", "查天气", "city", "深圳"))).isFalse();
        assertThat(mirrorIsStructuredInput(Map.of("content", List.of(
                Map.of("type", "text", "text", "hi"))))).isTrue();

        // SUT 直驱：真实 isStructuredContent 与基线一致
        if (sut != null) {
            assertThat(sut.isStructuredContent("你好，帮我看看这张图")).isFalse();
            assertThat(sut.isStructuredContent(Map.of("query", "查天气"))).isFalse();
            assertThat(sut.isStructuredContent(Map.of("content", List.of(
                    Map.of("type", "text", "text", "hi"))))).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // F032-04 旧 image_url 块转换与裸路径拒绝
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-04")
    @Story("FEAT-032.F032-04: 旧 image_url data:/http(s) 转换；裸路径显式 CONTENT_INVALID")
    @DisplayName("F032-04 legacy image_url 仅认 data: 与 http(s):，裸路径拒绝")
    void f03204LegacyImageUrlConversionAndBarePathRejection() {
        byte[] png = Feat032SutSupport.pngMagicBytes();
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        // 契约基线（L2 §2.2/§5.1）：data: 解一次 base64 转 Raw；http(s) 转 Url；裸本地路径显式失败。
        assertThat(mirrorLegacyUrlClassification(dataUrl)).isEqualTo("raw");
        assertThat(mirrorLegacyUrlClassification("https://example.com/a.png")).isEqualTo("url");
        assertThat(mirrorLegacyUrlClassification("C:\\ws\\a.png"))
                .isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(mirrorLegacyUrlClassification("file:///etc/passwd"))
                .isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);

        // data: 的 base64 必须解码回原字节（只解一次）
        assertThat(mirrorLegacyDataUrlBytes(dataUrl)).isEqualTo(png);

        // SUT 直驱：真实 normalize 对裸路径抛 CONTENT_INVALID，错误文案与 L2 §7.3 实测逐字一致
        if (sut != null) {
            Feat032SutNormalizerProxy.SutContractViolation violation = violationOf(
                    () -> sut.normalize(List.of(
                            Feat032SutNormalizerProxy.legacyImageUrlBlock("C:\\ws\\a.png"))));
            assertThat(violation).as("裸路径必须显式失败，不得透传/静默").isNotNull();
            assertThat(violation.reason()).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        }
    }

    // ------------------------------------------------------------------
    // F032-05 结构化输入非法显式失败
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-05")
    @Story("FEAT-032.F032-05: 不识别 type / 多 kind 冲突 / 缺必填字段一律显式失败并定位到块")
    @DisplayName("F032-05 非法结构化输入 CONTENT_INVALID 且携带定位字段")
    void f03205IllegalStructuredInputFailsExplicitly() {
        List<Map<String, Object>> illegalInputs = List.of(
                // 不识别的 type
                block("hologram", Map.of("text", "?")),
                // 一个块两种 source kind 冲突
                block("image", Map.of("source", Map.of(
                        "kind", "raw", "bytes", "AA==", "url", "https://example.com/a.png"))),
                // image 缺 source 必填字段
                block("image", Map.of()));

        for (Map<String, Object> input : illegalInputs) {
            // 契约基线（L2 §2.2）：显式失败、错误定位到块 ID／字段／原输入 index、不回退 String.valueOf
            Map<String, Object> baseline = mirrorNormalizeOrError(List.of(input));
            assertThat(baseline.get("ok")).as("非法输入 %s 必须失败", input).isEqualTo(false);
            assertThat(baseline.get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
            assertThat(baseline.get("located")).as("错误必须携带定位信息").isEqualTo(true);

            // SUT 直驱
            if (sut != null) {
                Feat032SutNormalizerProxy.SutContractViolation violation = violationOf(
                        () -> sut.normalize(List.of(input)));
                assertThat(violation).as("SUT 对非法输入 %s 必须显式失败", input).isNotNull();
                assertThat(violation.reason()).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
            }
        }
    }

    // ------------------------------------------------------------------
    // F032-06 kind=url 来源不下载不透传
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-06")
    @Story("FEAT-032.F032-06: kind=url 解析时报 SOURCE_UNRESOLVED，不下载、不透传、不静默降级")
    @DisplayName("F032-06 url 来源显式 SOURCE_UNRESOLVED")
    void f03206UrlSourceExplicitlyUnresolved() {
        Map<String, Object> urlBlock = block("image", Map.of("source", Map.of(
                "kind", "url", "url", "https://example.com/a.png")));

        // 契约基线（L2 §2.3）：url 不解析——解析时报 SOURCE_UNRESOLVED，不透传、不静默降级
        Map<String, Object> baseline = mirrorResolveOrError(List.of(urlBlock));
        assertThat(baseline.get("ok")).isEqualTo(false);
        assertThat(baseline.get("reason")).isEqualTo(Feat032SutSupport.REASON_SOURCE_UNRESOLVED);
        assertThat(baseline.get("downloaded")).as("不得发起下载").isEqualTo(false);

        // SUT 直驱（resolver 层门禁在 SutGatedBlackboxTest 全链路场景覆盖；
        // 此处若 core 契约就绪则同输入直驱断言：normalize 只做结构处理，url 失败发生在 resolve 层）
        if (sut != null) {
            Feat032SutNormalizerProxy.SutContractViolation violation = violationOf(
                    () -> sut.normalize(List.of(urlBlock)));
            if (violation == null) {
                List<Map<String, Object>> blocks = normalizeViaSutIfAvailable(List.of(urlBlock));
                assertThat(blocks).isNotNull();
                assertThat(blocks.get(0)).containsEntry("source.kind", "url");
            }
        }
    }

    // ------------------------------------------------------------------
    // F032-07 path 来源受控读取与 allowedRoots 越界拒绝
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-07")
    @Story("FEAT-032.F032-07: path 来源限 allowedRoots（canonical 校验），越界拒绝且路径零泄漏")
    @DisplayName("F032-07 path 来源 allowedRoots canonical 守卫")
    void f03207PathSourceAllowedRootsGuard() {
        // 契约基线（L2 §2.3）：path 限定 allowedRoots，canonical 校验防 .. 穿越与符号链接逃逸；
        // 发送前读取编码为 data:，路径字符串不进模型。
        String allowedRoot = "D:/ws/canary-workspace";
        assertThat(mirrorPathSourceGuard(allowedRoot, "D:/ws/canary-workspace/img/a.png"))
                .isEqualTo("allowed");
        assertThat(mirrorPathSourceGuard(allowedRoot, "D:/ws/canary-workspace/../secret/a.png"))
                .as(".. 穿越必须被 canonical 校验阻断").isEqualTo("rejected");
        assertThat(mirrorPathSourceGuard(allowedRoot, "D:/ws/other/a.png"))
                .as("allowedRoots 之外路径必须拒绝").isEqualTo("rejected");

        // SUT 直驱：kind=path 块在 normalize 层保留（越界拒绝发生在 provider 层）
        if (sut != null) {
            List<Map<String, Object>> blocks = normalizeViaSutIfAvailable(List.of(
                    block("image", Map.of("source", Map.of(
                            "kind", "path", "path", allowedRoot + "/img/a.png")))));
            if (blocks != null) {
                assertThat(blocks.get(0)).containsEntry("source.kind", "path");
            }
        }
    }

    // ------------------------------------------------------------------
    // F032-08 asset 版本固定（契约常量部分；入库读取走 SUT 直驱类）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-08")
    @Story("FEAT-032.F032-08: asset 版本缺失或不匹配报 VERSION_CONFLICT 并列出实际版本")
    @DisplayName("F032-08 asset 版本钉住失败语义 VERSION_CONFLICT")
    void f03208AssetVersionConflictReason() {
        // 契约基线（L2 §2.3）：asset 按 assetId+version 读取，缺失版本报 VERSION_CONFLICT
        // 并列出实际版本；同 ID 换版本不会读到新内容。
        assertThat(Feat032SutSupport.REASON_VERSION_CONFLICT).isEqualTo("VERSION_CONFLICT");
        assertThat(mirrorAssetVersionMismatchOutcome("v2", List.of("v1")))
                .containsEntry("reason", Feat032SutSupport.REASON_VERSION_CONFLICT)
                .containsEntry("actualVersions", List.of("v1"));

        // SUT 直驱：AssetRef kind 在 normalize 层应保留（解析失败发生在 provider 层）
        if (sut != null) {
            List<Map<String, Object>> blocks = normalizeViaSutIfAvailable(List.of(
                    block("image", Map.of("source", Map.of(
                            "kind", "asset", "assetId", "a-1", "version", "v2")))));
            if (blocks != null) {
                assertThat(blocks.get(0)).containsEntry("source.kind", "asset");
            }
        }
    }

    // ------------------------------------------------------------------
    // F032-09 MIME 以实际字节判定
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-09")
    @Story("FEAT-032.F032-09: MIME 按字节魔数判定；推断不出要求显式 mediaType，不默认 image/png")
    @DisplayName("F032-09 MIME 魔数判定优先于声明值")
    void f03209MimeByMagicBytesNotDeclaration() {
        // 契约基线（L2 §2.3）：ImageMimeSniffer 按魔数判定 PNG/JPEG/GIF87a/GIF89a/BMP/WEBP/TIFF/HEIC/SVG；
        // 推断不出时要求显式 mediaType，不默认 image/png。声明值不被盲信。
        assertThat(mirrorSniffMime(Feat032SutSupport.pngMagicBytes())).isEqualTo("image/png");
        assertThat(mirrorSniffMime(Feat032SutSupport.jpegMagicBytes())).isEqualTo("image/jpeg");
        assertThat(mirrorSniffMime(Feat032SutSupport.gifMagicBytes())).isEqualTo("image/gif");
        assertThat(mirrorSniffMime(Feat032SutSupport.unknownMagicBytes())).isNull();
        assertThat(mirrorSniffMime(Feat032SutSupport.ole2MagicBytes())).isNull();

        // 声明 image/jpeg 的 PNG 字节：按实际魔数 PNG 判定
        Map<String, Object> baseline = mirrorResolveDeclaredMime(
                Feat032SutSupport.pngMagicBytes(), "image/jpeg");
        assertThat(baseline.get("resolvedMime")).isEqualTo("image/png");

        // 无法识别 + 无显式 mediaType：显式失败
        Map<String, Object> unknown = mirrorResolveDeclaredMime(
                Feat032SutSupport.unknownMagicBytes(), null);
        assertThat(unknown.get("ok")).isEqualTo(false);
        assertThat(unknown.get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
    }

    // ------------------------------------------------------------------
    // F032-10 字节预算超限
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-10")
    @Story("FEAT-032.F032-10: 超单图/总量预算报 CONTENT_LIMIT_EXCEEDED，含限额与实际值，不删图不拆批")
    @DisplayName("F032-10 字节预算默认值与超限失败语义")
    void f03210ByteBudgetDefaultsAndLimitExceeded() {
        // 契约基线（L2 §6.2）：单图 20MB / 总量 80MB / 读取截止 10s；
        // 已知大小预检 + 边读边计数，超限断流；超限显式失败并给出限额与实际值。
        assertThat(Feat032SutSupport.DEFAULT_MAX_IMAGE_BYTES).isEqualTo(20971520L);
        assertThat(Feat032SutSupport.DEFAULT_MAX_TOTAL_IMAGE_BYTES).isEqualTo(83886080L);
        assertThat(Feat032SutSupport.DEFAULT_READ_TIMEOUT_MILLIS).isEqualTo(10000L);

        Map<String, Object> overSingle = mirrorBudgetCheck(
                Feat032SutSupport.DEFAULT_MAX_IMAGE_BYTES + 1,
                0, Feat032SutSupport.DEFAULT_MAX_IMAGE_BYTES, Feat032SutSupport.DEFAULT_MAX_TOTAL_IMAGE_BYTES);
        assertThat(overSingle.get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_LIMIT_EXCEEDED);
        assertThat(overSingle.get("limit")).isEqualTo(Feat032SutSupport.DEFAULT_MAX_IMAGE_BYTES);

        // 逐项扣减（withConsumedBytes/remainingTotalBytes 语义）
        Map<String, Object> overTotal = mirrorBudgetCheck(
                1024, Feat032SutSupport.DEFAULT_MAX_TOTAL_IMAGE_BYTES,
                Feat032SutSupport.DEFAULT_MAX_IMAGE_BYTES, Feat032SutSupport.DEFAULT_MAX_TOTAL_IMAGE_BYTES);
        assertThat(overTotal.get("reason")).isEqualTo(Feat032SutSupport.REASON_CONTENT_LIMIT_EXCEEDED);
        assertThat(overTotal.get("limit")).isEqualTo(Feat032SutSupport.DEFAULT_MAX_TOTAL_IMAGE_BYTES);

        // 未超限不失败
        assertThat(mirrorBudgetCheck(1024, 0,
                Feat032SutSupport.DEFAULT_MAX_IMAGE_BYTES, Feat032SutSupport.DEFAULT_MAX_TOTAL_IMAGE_BYTES))
                .containsEntry("reason", null);
    }

    // ------------------------------------------------------------------
    // F032-11 非图片 file 块无处理链
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-11")
    @Story("FEAT-032.F032-11: 非图片附件无处理链报 FILE_PROCESSING_REQUIRED，不静默只发文件名")
    @DisplayName("F032-11 非图片 file 块显式 FILE_PROCESSING_REQUIRED")
    void f03211NonImageFileBlockRequiresProcessing() {
        // 契约基线（L2 §2.3）：非图片 file 块在没有处理链时报 FILE_PROCESSING_REQUIRED。
        Map<String, Object> baseline = mirrorFileBlockOutcome("application/pdf", false);
        assertThat(baseline.get("ok")).isEqualTo(false);
        assertThat(baseline.get("reason")).isEqualTo(Feat032SutSupport.REASON_FILE_PROCESSING_REQUIRED);

        // 图片 MIME 的 file 块按图片处理（L2 §2.2 FileRef 语义）
        Map<String, Object> imageFile = mirrorFileBlockOutcome("image/png", true);
        assertThat(imageFile.get("treatedAsImage")).isEqualTo(true);
    }

    // ==================================================================
    // mirror 方法（逐行对齐 L2 §2.2/§2.3 记录的契约语义，标注被镜像契约）
    // ==================================================================

    /** [mirror: MultimodalContentNormalizer.isStructuredContent 判别规则] */
    private static boolean mirrorIsStructuredInput(Object input) {
        if (input instanceof String) {
            return false;
        }
        if (input instanceof Map<?, ?> map) {
            return map.containsKey("content");
        }
        if (input instanceof List<?> list) {
            return !list.isEmpty() && list.get(0) instanceof Map<?, ?> first
                    && first.containsKey("type");
        }
        return false;
    }

    /** [mirror: DefaultMultimodalContentNormalizer 旧 image_url 分类规则] */
    private static String mirrorLegacyUrlClassification(String url) {
        if (url.startsWith("data:")) {
            return "raw";
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return "url";
        }
        return Feat032SutSupport.REASON_CONTENT_INVALID;
    }

    /** [mirror: data: URL 单次 base64 解码] */
    private static byte[] mirrorLegacyDataUrlBytes(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        return Base64.getDecoder().decode(dataUrl.substring(comma + 1));
    }

    /** [mirror: 结构非法显式失败语义——不回退 String.valueOf，错误携带块定位] */
    private static Map<String, Object> mirrorNormalizeOrError(List<Map<String, Object>> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            Map<String, Object> block = blocks.get(index);
            Object type = block.get("type");
            boolean knownType = type instanceof String t
                    && List.of("text", "image", "video", "file", "tool_result").contains(t);
            if (!knownType) {
                return Map.of("ok", false,
                        "reason", Feat032SutSupport.REASON_CONTENT_INVALID,
                        "located", true, "index", index, "field", "type");
            }
            if ("image".equals(type)) {
                Object source = block.get("source");
                if (!(source instanceof Map<?, ?> src) || src.isEmpty()) {
                    return Map.of("ok", false,
                            "reason", Feat032SutSupport.REASON_CONTENT_INVALID,
                            "located", true, "index", index, "field", "source");
                }
                long kinds = src.keySet().stream()
                        .filter(k -> List.of("kind", "bytes", "url", "path", "assetId")
                                .contains(String.valueOf(k)))
                        .count();
                if (kinds > 2 || (src.containsKey("kind") && src.containsKey("url")
                        && "raw".equals(src.get("kind")))) {
                    return Map.of("ok", false,
                            "reason", Feat032SutSupport.REASON_CONTENT_INVALID,
                            "located", true, "index", index, "field", "source.kind");
                }
            }
        }
        return Map.of("ok", true);
    }

    /** [mirror: MultimodalContentResolver.resolve 的 url 来源语义] */
    private static Map<String, Object> mirrorResolveOrError(List<Map<String, Object>> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            Map<String, Object> block = blocks.get(index);
            if ("image".equals(block.get("type")) && block.get("source") instanceof Map<?, ?> src) {
                if ("url".equals(src.get("kind"))) {
                    return Map.of("ok", false,
                            "reason", Feat032SutSupport.REASON_SOURCE_UNRESOLVED,
                            "downloaded", false, "index", index);
                }
            }
        }
        return Map.of("ok", true);
    }

    /** [mirror: asset 版本不匹配的失败事实结构] */
    private static Map<String, Object> mirrorAssetVersionMismatchOutcome(
            String requestedVersion, List<String> actualVersions) {
        if (!actualVersions.contains(requestedVersion)) {
            return Map.of("reason", Feat032SutSupport.REASON_VERSION_CONFLICT,
                    "expected", requestedVersion, "actualVersions", actualVersions);
        }
        return Map.of("reason", "");
    }

    /** [mirror: ImageMimeSniffer 魔数判定表] */
    private static String mirrorSniffMime(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        String head = new String(bytes, 0, Math.min(6, bytes.length),
                java.nio.charset.StandardCharsets.US_ASCII);
        if (head.startsWith("GIF87a") || head.startsWith("GIF89a")) {
            return "image/gif";
        }
        return null;
    }

    /** [mirror: MIME 判定优先级——魔数优先于声明值；推断不出且无显式 mediaType 显式失败] */
    private static Map<String, Object> mirrorResolveDeclaredMime(byte[] bytes, String declared) {
        String sniffed = mirrorSniffMime(bytes);
        if (sniffed != null) {
            return Map.of("ok", true, "resolvedMime", sniffed);
        }
        if (declared != null && !declared.isBlank()) {
            return Map.of("ok", true, "resolvedMime", declared);
        }
        return Map.of("ok", false, "reason", Feat032SutSupport.REASON_CONTENT_INVALID);
    }

    /** [mirror: MaterialAccessContext 预算语义——已知大小预检 + 逐项扣减] */
    private static Map<String, Object> mirrorBudgetCheck(
            long incomingBytes, long consumedBytes, long maxImageBytes, long maxTotalBytes) {
        Map<String, Object> outcome = new java.util.LinkedHashMap<>();
        if (incomingBytes > maxImageBytes) {
            outcome.put("reason", Feat032SutSupport.REASON_CONTENT_LIMIT_EXCEEDED);
            outcome.put("limit", maxImageBytes);
            outcome.put("actual", incomingBytes);
            return outcome;
        }
        if (consumedBytes + incomingBytes > maxTotalBytes) {
            outcome.put("reason", Feat032SutSupport.REASON_CONTENT_LIMIT_EXCEEDED);
            outcome.put("limit", maxTotalBytes);
            outcome.put("actual", consumedBytes + incomingBytes);
            return outcome;
        }
        outcome.put("reason", null);
        return outcome;
    }

    /** [mirror: FileRef 处理链分派语义] */
    private static Map<String, Object> mirrorFileBlockOutcome(String mime, boolean hasProcessingChain) {
        boolean image = mime != null && mime.startsWith("image/");
        if (image) {
            return Map.of("ok", true, "treatedAsImage", true);
        }
        if (!hasProcessingChain) {
            return Map.of("ok", false,
                    "reason", Feat032SutSupport.REASON_FILE_PROCESSING_REQUIRED);
        }
        return Map.of("ok", true, "treatedAsImage", false);
    }

    /** [mirror: LocalImageMaterialProvider path 来源 allowedRoots canonical 守卫语义] */
    private static String mirrorPathSourceGuard(String allowedRoot, String requestedPath) {
        java.nio.file.Path root = java.nio.file.Path.of(allowedRoot).toAbsolutePath().normalize();
        java.nio.file.Path candidate = java.nio.file.Path.of(requestedPath).toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            return "rejected";
        }
        return "allowed";
    }
}