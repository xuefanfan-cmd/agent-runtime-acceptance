/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FEAT-032 共享支持：SUT 制品探针（反射，零编译期依赖）、错误表面契约常量、通用 fixture 工具。
 *
 * <p><b>SUT 现状</b>（2026-10-23 整改后）：
 * PR !404（{@code feat/multimodal-entry-m1m2}，head 1dab2530）已于 2026-10-20 合入
 * agent-core-java develop——core/foundation/llm/multimodal 契约包随 0.1.17 源码重打包进入
 * classpath；solution 侧 agent-core-ext-multimodal-tools 0.1.3 与验证基座 agent
 * common/example/multi-modal-tools-demo 0.1.3（common!882）已上库并可从源码构建。
 * 本特性用例仍分两层，但 SUT 直驱层已激活：
 * <ul>
 *   <li><b>契约基线层</b>：mirror 方法逐条锁定设计文档 §2.2-2.8 的 wire 契约（reason 字符串、
 *       默认限额、失败语义），无条件执行，作为断言基线；</li>
 *   <li><b>SUT 直驱层</b>：PR !404 入口契约经 {@link Feat032Pr404EntryDirectTest} 以真实
 *       ReActAgent 离线直驱；图片/文档工具经 demo 模块工具类直驱
 *       （{@link Feat032SutToolDirectTest} / {@link Feat032DocumentToolDirectTest}）；
 *       全链路真实模型场景经 {@link Feat032DemoAgentInvoker}（基于 demo 的
 *       {@code MultimodalToolsAgent}）驱动，制品缺失时按 dependency-gated 语义 SKIPPED
 *       （对应 references/test-case-conventions.md 断言规范第 5 条）。</li>
 * </ul>
 *
 * <p>所有 reason 字符串逐字取自 L2 设计 §2.8 错误表面；默认限额取自 L2 §6.2。
 *
 * @since 0.1.2
 */
final class Feat032SutSupport {

    // ---- 错误表面契约常量（L2 §2.8，逐字） ----
    static final String REASON_CONTENT_INVALID = "CONTENT_INVALID";
    static final String REASON_SOURCE_UNRESOLVED = "SOURCE_UNRESOLVED";
    static final String REASON_FILE_PROCESSING_REQUIRED = "FILE_PROCESSING_REQUIRED";
    static final String REASON_IMAGE_LOAD_FAILED = "IMAGE_LOAD_FAILED";
    static final String REASON_IMAGE_PROCESS_FAILED = "IMAGE_PROCESS_FAILED";
    static final String REASON_VERSION_CONFLICT = "VERSION_CONFLICT";
    static final String REASON_CONTENT_LIMIT_EXCEEDED = "CONTENT_LIMIT_EXCEEDED";
    static final String REASON_ENVIRONMENT_UNAVAILABLE = "ENVIRONMENT_UNAVAILABLE";
    static final String REASON_FILE_INVALID = "FILE_INVALID";
    static final String REASON_FILE_ENCRYPTED = "FILE_ENCRYPTED";
    static final String REASON_FORMAT_UNSUPPORTED = "FORMAT_UNSUPPORTED";
    static final String REASON_OPERATION_UNSUPPORTED = "OPERATION_UNSUPPORTED";
    static final String REASON_CALCULATION_INCOMPLETE = "CALCULATION_INCOMPLETE";
    static final String REASON_FORMULA_UNSUPPORTED = "FORMULA_UNSUPPORTED";

    /** L2 §7.3 实测错误文案（legacy image_url 塞裸本地路径）。 */
    static final String LEGACY_IMAGE_URL_BARE_PATH_MESSAGE =
            "legacy image_url must be a data: or http(s): URL; local files require source kind 'path'";

    // ---- 默认限额契约常量（L2 §6.2） ----
    static final long DEFAULT_MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    static final long DEFAULT_MAX_TOTAL_IMAGE_BYTES = 80L * 1024 * 1024;
    static final long DEFAULT_READ_TIMEOUT_MILLIS = 10_000L;
    static final int IMAGE_OBSERVE_MAX_BATCH = 20;
    static final int OBSERVE_CONCURRENCY = 4;
    static final long MAX_DECODED_PIXELS = 50_000_000L;
    static final long MAX_OUTPUT_SIDE = 20_000L;

    /** clarity 预设（L2 §2.5）：预设名 - [最长边, 质量]。 */
    static final Map<String, double[]> CLARITY_PRESETS = Map.of(
            "low", new double[]{1024, 0.75},
            "default", new double[]{2048, 0.85},
            "high", new double[]{4096, 0.92});

    // ---- SUT 类名（L2 §4 变更清单逐字） ----
    static final String CLS_NORMALIZER = "com.openjiuwen.core.foundation.llm.multimodal.MultimodalContentNormalizer";
    static final String CLS_DEFAULT_NORMALIZER =
            "com.openjiuwen.core.foundation.llm.multimodal.DefaultMultimodalContentNormalizer";
    static final String CLS_CONTENT_BLOCK_TYPE = "com.openjiuwen.core.foundation.llm.multimodal.ContentBlockType";
    static final String CLS_RESOLVER = "com.openjiuwen.core.foundation.llm.multimodal.MultimodalContentResolver";
    static final String CLS_MATERIAL_ACCESS_CONTEXTS =
            "com.openjiuwen.core.foundation.llm.multimodal.MaterialAccessContexts";
    static final String CLS_SOLUTION_SUITE = "com.openjiuwen.agents.multimodal.ObservationToolSuite";
    static final String CLS_SOLUTION_MATERIAL_SERVICE =
            "com.openjiuwen.agents.multimodal.material.ImageMaterialService";
    static final String CLS_SOLUTION_CLARITY_PRESET =
            "com.openjiuwen.agents.multimodal.preprocess.ClarityPreset";
    /** 验证基座 agent（agent-solution common/example/multi-modal-tools-demo，common!882）。 */
    static final String CLS_DEMO_AGENT = "com.openjiuwen.example.multimodal.tools.MultimodalToolsAgent";

    private Feat032SutSupport() {
    }

    /** 反射加载 SUT 类；制品未发布返回 null（不抛异常，由门禁判定）。 */
    static Class<?> tryLoad(String className) {
        try {
            return Class.forName(className, false, Feat032SutSupport.class.getClassLoader());
        } catch (Throwable notPresent) {
            return null;
        }
    }

    /** core 侧 multimodal 契约（agent-core-java feat/multimodal-entry-m1m2）是否在 classpath。 */
    static boolean coreContractPresent() {
        return tryLoad(CLS_DEFAULT_NORMALIZER) != null;
    }

    /** solution 侧多模态工具模块（agent-core-ext-multimodal-tools）是否在 classpath。 */
    static boolean solutionToolsPresent() {
        return tryLoad(CLS_SOLUTION_SUITE) != null;
    }

    /** 验证基座 agent（multi-modal-tools-demo 的 MultimodalToolsAgent）是否在 classpath。 */
    static boolean demoAgentPresent() {
        return tryLoad(CLS_DEMO_AGENT) != null;
    }

    /** SUT 直驱依赖门禁：core 契约制品就绪；否则按 dependency-gated 跳过并在报告写明原因。 */
    static void assumeSutCoreContract() {
        org.junit.jupiter.api.Assumptions.assumeTrue(coreContractPresent(),
                "FEAT-032 dependency-gated: agent-core-java multimodal 契约制品未就绪"
                        + "（PR !404 已于 2026-10-20 合入 develop；需从源码重打包 0.1.17，当前 classpath 无 "
                        + CLS_DEFAULT_NORMALIZER + "）");
    }

    /** SUT 直驱依赖门禁：验证基座 agent（multi-modal-tools-demo）就绪。 */
    static void assumeDemoAgentReady() {
        org.junit.jupiter.api.Assumptions.assumeTrue(demoAgentPresent() && solutionToolsPresent(),
                "FEAT-032 dependency-gated: multi-modal-tools-demo / agent-core-ext-multimodal-tools "
                        + "制品未就绪（需 agent-solution common!882 构建 demo 0.1.3 与工具模块 0.1.3）");
    }

    /**
     * LLM 环境门禁：真实模型场景（密钥仅经环境变量注入，不落盘）。
     * 优先 demo 约定的 VISION_API_KEY / VISION_BASE_URL，兼容测试执行约定的 LLM_API_*。
     */
    static void assumeLlmEnvReady() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                visionApiKey() != null && visionApiBase() != null,
                "FEAT-032 dependency-gated: VISION_API_KEY / VISION_BASE_URL（或 LLM_API_KEY / LLM_API_BASE）"
                        + "环境变量未注入（真实模型场景）");
    }

    /** 视觉模型 API Key：VISION_API_KEY 优先，回退 LLM_API_KEY；缺失返回 null。 */
    static String visionApiKey() {
        return firstNonBlank(System.getenv("VISION_API_KEY"), System.getenv("LLM_API_KEY"));
    }

    /**
     * 视觉模型端点：VISION_BASE_URL 优先，回退 LLM_API_BASE；缺失返回 null。
     * 兼容直接注入完整 chat completions URL 的注入方式（如
     * {@code https://.../v3/chat/completions}），剥离尾缀后交由客户端拼接。
     */
    static String visionApiBase() {
        String base = firstNonBlank(System.getenv("VISION_BASE_URL"), System.getenv("LLM_API_BASE"));
        if (base == null) {
            return null;
        }
        return base.endsWith("/chat/completions") ? base.substring(0, base.length() - "/chat/completions".length()) : base;
    }

    /** 视觉模型 ID：VISION_MODEL 优先，回退 LLM_MODEL / FEAT032_MODEL；缺省 qwen3-vl-plus（demo 默认）。 */
    static String visionModel() {
        String model = firstNonBlank(System.getenv("VISION_MODEL"), System.getenv("LLM_MODEL"),
                System.getenv("FEAT032_MODEL"));
        return model != null ? model : "qwen3-vl-plus";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** sha256 十六进制摘要（小写），与 L2「版本＝内容摘要」口径一致。 */
    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 生成最小合法 docx（zip 容器，仅 [Content_Types].xml / _rels/.rels / word/document.xml）。 */
    static byte[] minimalDocx(String paragraphXml) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", utf8("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml"
                 ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>"""));
        parts.put("_rels/.rels", utf8("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                 Target="word/document.xml"/></Relationships>"""));
        parts.put("word/document.xml", utf8("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>%s</w:body></w:document>""".formatted(paragraphXml)));
        return zipBytes(parts);
    }

    /** 顺序稳定的 zip 字节构造（供 zip part 逐字节比对的 fixture）。 */
    static byte[] zipBytes(Map<String, byte[]> parts) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
                for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                    zos.putNextEntry(new java.util.zip.ZipEntry(part.getKey()));
                    zos.write(part.getValue());
                    zos.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 读取 zip 全部 part 字节（保序），供「仅目标 part 变化、其余字节零改动」断言。 */
    static Map<String, byte[]> zipParts(byte[] zipBytes) {
        try {
            Map<String, byte[]> parts = new LinkedHashMap<>();
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(zipBytes))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    parts.put(entry.getName(), zis.readAllBytes());
                }
            }
            return parts;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 生成最小合法 xlsx（workbook + sheet1，零共享字符串表的精简集）。 */
    static byte[] minimalXlsx(String sheetXml) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", utf8("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml"
                 ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml"
                 ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>"""));
        parts.put("_rels/.rels", utf8("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                 Target="xl/workbook.xml"/></Relationships>"""));
        parts.put("xl/workbook.xml", utf8("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"
                 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/></sheets>
                </workbook>"""));
        parts.put("xl/_rels/workbook.xml.rels", utf8("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                 Target="worksheets/sheet1.xml"/></Relationships>"""));
        parts.put("xl/worksheets/sheet1.xml", utf8(sheetXml));
        return zipBytes(parts);
    }

    /** 简单 PNG 字节（1x1 白点，魔数 89 50 4E 47），供 MIME 魔数判定 fixture。 */
    static byte[] pngMagicBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00,
                0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
                0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
                0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    }

    static byte[] jpegMagicBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46,
                0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9};
    }

    static byte[] gifMagicBytes() {
        byte[] gif = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[gif.length + 8];
        System.arraycopy(gif, 0, all, 0, gif.length);
        return all;
    }

    static byte[] ole2MagicBytes() {
        return new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1,
                0x1A, (byte) 0xE1, 0x00, 0x00, 0x00, 0x00};
    }

    static byte[] pdfMagicBytes() {
        return "%PDF-1.7\n%fake-fixture\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] unknownMagicBytes() {
        return new byte[]{0x00, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00, 0x7F, 0x7F};
    }

    // ------------------------------------------------------------------
    // SUT 直驱代理（反射；仅调用 L2 §4 逐字记录的 public API）
    // ------------------------------------------------------------------

    /** 反射读取对象上名为 fieldName 的字段（含父类）；不存在返回 null。 */
    static Object field(Object target, String fieldName) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field f = type.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从异常对象上提取 SUT 错误表面的 details（getDetails() 方法或 details 字段）。
     * L2 §2.8：core 侧 BaseError 携带 details.reason。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> errorDetails(Throwable error) {
        Throwable t = error;
        while (t != null) {
            try {
                Method m = t.getClass().getMethod("getDetails");
                Object details = m.invoke(t);
                if (details instanceof Map<?, ?> map) {
                    return (Map<String, Object>) map;
                }
            } catch (ReflectiveOperationException ignore) {
                // fall through to field lookup
            }
            Object details = field(t, "details");
            if (details instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            t = t.getCause();
        }
        return Map.of();
    }

    /** 把反射得到的 SUT 对象图压平成可断言的 Map（type/source kind/id 等契约字段）。 */
    static Map<String, Object> describeBlock(Object contentBlock) {
        Map<String, Object> desc = new LinkedHashMap<>();
        if (contentBlock == null) {
            return desc;
        }
        Object type = invokeNoArg(contentBlock, "type");
        if (type != null) {
            Object wire = invokeNoArg(type, "wireValue");
            desc.put("type", wire != null ? wire.toString() : type.toString());
        }
        Object id = invokeNoArg(contentBlock, "id");
        desc.put("id", id != null ? id.toString() : null);
        Object source = invokeNoArg(contentBlock, "source");
        if (source != null) {
            Object kind = invokeNoArg(source, "kind");
            desc.put("source.kind", kind != null ? kind.toString() : null);
            Object sourceId = invokeNoArg(source, "sourceId");
            desc.put("source.sourceId", sourceId != null ? sourceId.toString() : null);
        }
        return desc;
    }

    static Object invokeNoArg(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** 经反射调用 static 方法；找不到返回 null。 */
    static Object invokeStatic(Class<?> type, String method, Class<?>[] paramTypes, Object[] args) {
        try {
            Method m = type.getMethod(method, paramTypes);
            return m.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static Object newInstance(Class<?> type) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** 枚举常量名集合（反射 values()）。 */
    static List<String> enumNames(Class<?> enumType) {
        List<String> names = new ArrayList<>();
        Object[] constants = enumType.getEnumConstants();
        if (constants != null) {
            for (Object constant : constants) {
                names.add(((Enum<?>) constant).name());
            }
        }
        return names;
    }
}